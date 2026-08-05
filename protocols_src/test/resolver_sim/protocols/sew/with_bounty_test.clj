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

;; ── boundary adversarial cases (pre-Stage-C review) ───────────────────────

(deftest conflicting-plan-for-same-obligation-fails
  (let [plan (evaluated-plan)
        tampered (assoc plan :plan/context {:note "tampered"})
        tampered (assoc tampered :plan/hash (wb-plan/plan-hash tampered))
        world (:world (sew-wb/apply-with-bounty-plan plan {}))]
    (is (not= (:plan/hash plan) (:plan/hash tampered)))
    (is (= (:plan/obligation-id plan) (:plan/obligation-id tampered)))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"conflicting application"
         (sew-wb/apply-with-bounty-plan tampered world)))))

(deftest different-obligation-same-duplicate-key-rejected
  (let [plan (evaluated-plan)
        other (evaluation/evaluate-with-bounty
               {:policy proof/review-policy
                :base-result {:resolved-amount 10000}
                :base-operation-root "sha256:op"
                :event-context {:review/finalised? true
                                :event/actor :researcher/alice}
                :parameter-context {:fixture/review-bounty-rate 500}
                :parameter-context-root proof/parameter-context-root
                :extension-map (fixtures/extension-map)
                :sealed? true
                :token :token/eth
                :funding-available 1000
                :adapter-support sew-wb/adapter-support})
        other-plan (:plan other)
        world (:world (sew-wb/apply-with-bounty-plan plan {}))]
    (is (= :applied (:status other)))
    (is (not= (:plan/obligation-id plan) (:plan/obligation-id other-plan)))
    (is (= (:plan/no-duplicate-creation-key plan)
           (:plan/no-duplicate-creation-key other-plan)))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"duplicate obligation"
         (sew-wb/apply-with-bounty-plan other-plan world)))))

(deftest idempotent-replay-with-drifted-state-fails
  (let [plan (evaluated-plan)
        world (:world (sew-wb/apply-with-bounty-plan plan {}))
        drifted (update world :with-bounty/backings dissoc (str "backing-" (:plan/obligation-id plan)))]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"drifted state"
         (sew-wb/apply-with-bounty-plan plan drifted)))))

(deftest mid-application-failure-is-atomic
  (let [base (evaluated-plan)
        obligation (first (filter #(= :prf.effect/obligation-create.v2 (:effect/contract %))
                                  (:plan/effects base)))
        custody-add {:effect/type :custody/held-adjustment
                     :effect/contract :prf.effect/custody-held-adjustment.v1
                     :effect/direction :add
                     :effect/account :bounty-reserve
                     :effect/amount 500
                     :effect/token :token/usdc
                     :held/kind :bounty-reserve-reservation
                     :parameter/context {:parameter-context/type :protocol-parameters
                                         :parameter-context/root proof/parameter-context-root
                                         :parameter-context/version 1}
                     :parameter/address {:parameter/path [:bounties :review-reserve]}}
        custody-sub (assoc custody-add :effect/direction :sub :effect/amount 1000)
        plan-result (wb-plan/build-with-bounty-plan
                     {:policy-root (:plan/policy-root base)
                      :base-operation-root (:plan/base-operation-root base)
                      :base-result-root (:plan/base-result-root base)
                      :extensions-resolution-root (:plan/extensions-resolution-root base)
                      :adapter sew-wb/adapter-support
                      :effects [custody-add obligation custody-sub]
                      :effect-schema-roots (:plan/effect-schema-roots base)
                      :funding-available 1000})
        tampered (:plan plan-result)
        world-before {}]
    (is (= :valid (:status plan-result)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (sew-wb/apply-with-bounty-plan tampered world-before)))
    (is (= world-before
           (try (sew-wb/apply-with-bounty-plan tampered world-before)
                (catch clojure.lang.ExceptionInfo _ world-before))))))

(deftest transition-binds-exact-artifacts
  (let [plan (evaluated-plan)
        result (sew-wb/apply-with-bounty-plan plan {})
        world (:world result)
        transition (:transition result)]
    (is (:valid? (verification/verify-transition-binds-world transition world)))
    (is (:valid? (verification/verify-transition-with-plan transition plan)))
    (is (not (:valid? (verification/verify-transition-binds-world
                       transition (dissoc world :with-bounty/payables)))))
    (is (= 1 (count (:custody/adjustment-roots transition))))
    (is (string? (get-in transition [:custody/adjustment-roots 0 :artifact/hash])))))

(deftest claimable-derives-from-backed-payable
  (let [plan (evaluated-plan)
        world (:world (sew-wb/apply-with-bounty-plan plan {}))]
    (is (:valid? (verification/verify-application-world world)))
    (is (not (:valid? (verification/verify-application-world
                       (update world :with-bounty/payables dissoc (:plan/obligation-id plan))))))
    (is (not (:valid? (verification/verify-application-world
                       (update world :with-bounty/backings
                               dissoc (str "backing-" (:plan/obligation-id plan)))))))))
