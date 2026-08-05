(ns resolver-sim.protocols.sew.with-bounty-test
  "Stage B vertical slice, Sew side: apply a validated with-bounty application
   plan to a Sew world through the canonical payable/backing and held-custody
   paths. Covers preflight fail-before-mutation, idempotency (no duplicate
   economic state under retry), reserve conservation, and bound transition
   evidence (design note §19 Sew parity)."
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.economics.with-bounty.application-plan :as wb-plan]
            [resolver-sim.economics.with-bounty.evaluation :as evaluation]
            [resolver-sim.economics.with-bounty.fixtures :as fixtures]
            [resolver-sim.economics.with-bounty.proof :as proof]
            [resolver-sim.economics.with-bounty.verification :as verification]
            [resolver-sim.protocols.sew.with-bounty :as sew-wb]))

(defn- evaluated-plan
  "Run the generic evaluation (fixture extension map) and return the applied
   plan."
  []
  (let [{:keys [status plan violations]}
        (evaluation/evaluate-with-bounty
         {:policy proof/review-policy
          :base-result {:resolved-amount 10000}
          :base-operation-root "sha256:op"
          :event-context {:review/finalised? true
                          :event/actor :researcher/alice}
          :parameter-context {:fixture/review-bounty-rate 500}
          :parameter-context-root proof/parameter-context-root
          :extension-map (fixtures/extension-map)
          :sealed? true
          :token :token/usdc
          :funding-available 1000
          :adapter-support sew-wb/adapter-support})]
    (is (= :applied status) (pr-str violations))
    plan))

(defn- obligation-id
  [plan]
  (:plan/obligation-id plan))

(deftest preflight-passes-on-valid-plan
  (let [plan (evaluated-plan)]
    (is (:valid? (sew-wb/preflight plan)))))

(deftest preflight-fails-on-unsupported-effect-before-mutation
  (let [plan (evaluated-plan)
        unsupported {:effect/type :balance/credit
                     :effect/contract :prf.effect/balance-credit.v1
                     :effect/account :somewhere
                     :effect/amount 500}
        tampered (-> plan
                     (update :plan/effects conj unsupported)
                     (assoc :plan/hash (wb-plan/plan-hash (assoc plan :plan/effects
                                                                 (conj (:plan/effects plan)
                                                                       unsupported)))))
        world-before {}]
    (is (not (:valid? (sew-wb/preflight tampered))))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"preflight failed"
         (sew-wb/apply-with-bounty-plan tampered world-before)))
    (is (= world-before (try (sew-wb/apply-with-bounty-plan tampered world-before)
                             (catch clojure.lang.ExceptionInfo _ world-before))))))

(deftest apply-creates-payable-backing-claimable-and-custody
  (let [plan (evaluated-plan)
        result (sew-wb/apply-with-bounty-plan plan {})
        world (:world result)]
    (is (false? (:idempotent? result)))
    ;; reserve custody via the canonical held path
    (is (= 500 (get-in world [:total-held :token/usdc])))
    (is (= 1 (count (:held-adjustments world))))
    (is (= :in (get-in world [:held-adjustments 0 :held/direction])))
    (is (= :bounty-reserve-reservation (get-in world [:held-adjustments 0 :held/reason])))
    ;; payable + backing
    (is (= 1 (count (:with-bounty/payables world))))
    (is (= 1 (count (:with-bounty/backings world))))
    (is (= :pending-backing (get-in (first (vals (:with-bounty/payables world)))
                                    [:payable/lifecycle])))
    (is (= 64 (count (get-in (first (vals (:with-bounty/payables world)))
                             [:payable/hash]))))
    ;; claimable recorded in the resulting world (available actions derive from state)
    (is (= 500 (get-in world [:claimable-v2 (obligation-id plan)
                              :liability/bounty-payable "researcher/alice"])))
    ;; idempotency key records the applied plan root
    (is (= (:plan/hash plan) (get-in world (:plan/idempotency-key plan))))
    ;; transition evidence is bound and verifiable
    (is (= 64 (count (:transition/hash (:transition result)))))
    (is (= (:plan/hash plan) (get-in result [:transition :plan/root])))
    (is (= (first (:plan/effect-roots plan)) (:effect-root (:transition result))))
    (is (:valid? (verification/verify-transition-evidence (:transition result))))))

(deftest retry-is-idempotent-without-duplicate-state
  (let [plan (evaluated-plan)
        first-result (sew-wb/apply-with-bounty-plan plan {})
        second-result (sew-wb/apply-with-bounty-plan plan (:world first-result))
        world (:world second-result)]
    (is (true? (:idempotent? second-result)))
    (is (= (:world first-result) (:world second-result)))
    ;; no duplicate economic state
    (is (= 1 (count (:with-bounty/payables world))))
    (is (= 1 (count (:with-bounty/backings world))))
    (is (= 1 (count (:held-adjustments world))))
    (is (= 500 (get-in world [:claimable-v2 (obligation-id plan)
                              :liability/bounty-payable "researcher/alice"])))
    (is (= 500 (get-in world [:total-held :token/usdc])))))

(deftest conflicting-application-fails-atomically
  (let [plan (evaluated-plan)
        world (assoc-in {} (:plan/idempotency-key plan) "sha256:conflicting")]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"conflicting application"
         (sew-wb/apply-with-bounty-plan plan world)))
    (is (= "sha256:conflicting" (get-in world (:plan/idempotency-key plan))))))

(deftest reserve-conservation-holds
  (let [plan (evaluated-plan)
        result (sew-wb/apply-with-bounty-plan plan {})
        world (:world result)
        backing (first (vals (:with-bounty/backings world)))
        payable (first (vals (:with-bounty/payables world)))]
    ;; reserve before (0) + newly reserved (500) = reserve after (500)
    (is (= 500 (+ (get-in {} [:total-held :token/usdc] 0)
                  (get-in world [:total-held :token/usdc]))))
    ;; backing amount equals the reserve allocation and the payable amount
    (is (= 500 (:backing/amount backing)))
    (is (= 500 (:payable/amount payable)))
    (is (= 500 (reduce + 0 (vals (:backing/source-allocations backing)))))))
