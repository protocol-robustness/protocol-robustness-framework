(ns resolver-sim.economics.with-bounty.application-plan-test
  "Stage B: composition application plan build/verify and creation-time
   fail-before-mutation preconditions (ADR-0006 D3/D5)."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.economics.with-bounty.application-plan :as wb-plan]
            [resolver-sim.economics.with-bounty.identity :as identity]
            [resolver-sim.economics.with-bounty.policy :as policy]
            [resolver-sim.economics.with-bounty.proof :as proof]))

(def policy-root
  (policy/with-bounty-policy-root proof/review-policy))

(def obligation-effect
  {:effect/type :obligation/create
   :effect/contract :prf.effect/obligation-create.v2
   :obligation/type :bounty-payable
   :obligation/id (identity/bounty-obligation-id
                   {:operation-root "sha256:op"
                    :bounty-id :review-completion
                    :recipient :researcher/alice
                    :token :token/usdc
                    :amount 500
                    :policy-root policy-root})
   :obligation/amount 500
   :obligation/token :token/usdc
   :obligation/owner :researcher/alice
   :obligation/funding {:source :declared-reserve
                        :parameter-address [:bounties :review-reserve]
                        :parameter-context-root "sha256:params"}
   :obligation/subject {:operation-root "sha256:op"
                        :bounty-id :review-completion}
   :effect/provenance {:policy-root policy-root
                       :eligibility-invocation-id "sha256:elig"
                       :amount-invocation-id "sha256:amt"
                       :extensions/resolution-root "sha256:res"}})

(def custody-effect
  {:effect/type :custody/held-adjustment
   :effect/contract :prf.effect/custody-held-adjustment.v1
   :effect/direction :add
   :effect/account :bounty-reserve
   :effect/amount 500
   :effect/token :token/usdc
   :held/kind :bounty-reserve-reservation
   :owner/address :researcher/alice
   :parameter/context "sha256:params"
   :parameter/address [:bounties :review-reserve]})

(def base-opts
  {:policy-root policy-root
   :base-operation-root "sha256:op"
   :base-result-root "sha256:op"
   :extensions-resolution-root "sha256:res"
   :adapter {:adapter/id :fixture/v1
             :adapter/supported-effects
             #{:prf.effect/obligation-create.v2
               :prf.effect/custody-held-adjustment.v1}}
   :effects [obligation-effect custody-effect]
   :effect-schema-roots {:prf.effect/obligation-create.v2 "sha256:v2"
                         :prf.effect/custody-held-adjustment.v1 "sha256:held"}
   :funding-available 1000})

(defn- build-plan
  ([] (build-plan {}))
  ([overrides] (wb-plan/build-with-bounty-plan (merge base-opts overrides))))

(deftest valid-plan-builds-and-verifies
  (let [{:keys [status plan]} (build-plan)]
    (is (= :valid status))
    (is (= "with-bounty-application-plan.v1" (:schema-version plan)))
    (is (= 64 (count (:plan/hash plan))))
    (is (= (:plan/hash plan)
           (get-in (build-plan) [:plan :plan/hash])))
    (is (= 2 (count (:plan/effects plan))))
    (is (= 2 (count (:plan/effect-roots plan))))
    (is (every? #(= 64 (count %)) (:plan/effect-roots plan)))
    (is (= 64 (count (:plan/combined-effect-root plan))))
    (is (= (:obligation/id obligation-effect) (:plan/obligation-id plan)))
    (is (= ["sha256:op" :review-completion :researcher/alice]
           (:plan/no-duplicate-creation-key plan)))
    (is (= "sha256:res" (:plan/extensions-resolution-root plan)))
    (is (= "sha256:op" (:plan/base-result-root plan)))
    (is (= :fixture/v1 (get-in plan [:plan/adapter :adapter/id])))
    (is (= 1000 (:plan/funding-available plan)))
    (is (every? true? (vals (:plan/preconditions plan))))
    (is (:valid? (wb-plan/validate-with-bounty-plan plan)))
    (is (:valid? (wb-plan/verify-with-bounty-plan plan)))))

(deftest plan-tamper-detected
  (let [{:keys [plan]} (build-plan)
        tampered (assoc plan :plan/hash "ignored")]
    (is (not (:valid? (wb-plan/verify-with-bounty-plan tampered))))))

(deftest effect-root-deterministic
  (is (= (wb-plan/effect-root obligation-effect)
         (wb-plan/effect-root obligation-effect)))
  (is (= 64 (count (wb-plan/effect-root obligation-effect)))))

(deftest malformed-effect-fails-plan
  (let [bad (assoc-in base-opts [:effects 0 :effect/contract] :prf.effect/does-not-exist)
        {:keys [status violations]} (wb-plan/build-with-bounty-plan bad)]
    (is (= :invalid status))
    (is (some #(= :violation/effect-schema-invalid (:violation/id %)) violations))))

(deftest negative-amount-fails-plan
  (let [neg (assoc-in base-opts [:effects 0 :obligation/amount] -5)
        {:keys [status violations]} (wb-plan/build-with-bounty-plan neg)]
    (is (= :invalid status))
    (is (some #(= :violation/bounty-amount-out-of-bounds (:violation/id %)) violations))))

(deftest amount-over-declared-maximum-fails-plan
  (let [over (assoc base-opts :declared-maximum 400)
        {:keys [status violations]} (wb-plan/build-with-bounty-plan over)]
    (is (= :invalid status))
    (is (some #(= :violation/bounty-amount-out-of-bounds (:violation/id %)) violations))))

(deftest insufficient-funding-fails-plan
  (let [{:keys [status violations]} (wb-plan/build-with-bounty-plan
                                     (assoc base-opts :funding-available 100))]
    (is (= :invalid status))
    (is (some #(= :violation/insufficient-bounty-funding (:violation/id %)) violations))))

(deftest unknown-plan-key-rejected
  (let [{:keys [plan]} (build-plan)
        tampered (assoc plan :plan/unknown "extra")]
    (is (not (:valid? (wb-plan/validate-with-bounty-plan tampered))))))

(deftest missing-adapter-commitment-rejected
  (let [{:keys [plan]} (wb-plan/build-with-bounty-plan (dissoc base-opts :adapter))]
    (is (nil? (:plan/adapter plan)))
    (is (not (:valid? (wb-plan/validate-with-bounty-plan plan))))))

(deftest reordered-effects-change-plan-root
  (let [{:keys [plan]} (build-plan)
        reordered (wb-plan/build-with-bounty-plan
                   (assoc base-opts :effects [custody-effect obligation-effect]))]
    (is (= :valid (:status reordered)))
    (is (not= (:plan/hash plan) (get-in reordered [:plan :plan/hash])))
    (is (not= (:plan/combined-effect-root plan)
              (get-in reordered [:plan :combined-effect-root])))))

(deftest v1-obligation-effect-rejected-by-plan
  (let [v1 {:effect/type :obligation/create
            :effect/contract :prf.effect/obligation-create.v1
            :obligation/type :bounty-payable
            :obligation/amount 500
            :obligation/owner :researcher/alice}
        {:keys [status violations]} (wb-plan/build-with-bounty-plan
                                     (assoc base-opts :effects [v1 custody-effect]))]
    (is (= :invalid status))
    (is (some #(= :violation/obligation-id-mismatch (:violation/id %)) violations))))

(deftest derived-fields-verified-by-recomputation
  (let [{:keys [plan]} (build-plan)
        tampered-roots (assoc plan :plan/effect-roots
                              [(apply str (repeat 64 "f"))
                               (apply str (repeat 64 "e"))])]
    (is (not (:valid? (wb-plan/verify-with-bounty-plan tampered-roots))))
    (let [tampered-preconditions (assoc-in plan [:plan/preconditions :funding/available?] false)]
      (is (not (:valid? (wb-plan/verify-with-bounty-plan tampered-preconditions)))))))

(deftest tampered-obligation-id-fails-plan
  (let [tampered (assoc-in base-opts [:effects 0 :obligation/id] "sha256:wrong")
        {:keys [status violations]} (wb-plan/build-with-bounty-plan tampered)]
    (is (= :invalid status))
    (is (some #(= :violation/obligation-id-mismatch (:violation/id %)) violations))))

(deftest adapter-support-validation
  (let [{:keys [plan]} (build-plan)
        support {:adapter/id :sew/v1
                 :adapter/supported-effects
                 #{:prf.effect/obligation-create.v2 :prf.effect/custody-held-adjustment.v1}}]
    (is (:valid? (wb-plan/validate-with-bounty-plan-for-adapter support plan)))
    (is (not (:valid? (wb-plan/validate-with-bounty-plan-for-adapter
                       {:adapter/id :sew/v1
                        :adapter/supported-effects #{:prf.effect/obligation-create.v2}}
                       plan))))))

(deftest application-plan-projection-frozen
  (testing "the committed projection table is frozen (ADR-0006 R11); changing it
            requires a v2 domain, because attestations and package roots bind it"
    (is (= [:schema-version
            :plan/policy-root
            :plan/base-operation-root
            :plan/base-result-root
            :plan/base-plan-root
            :plan/extensions-resolution-root
            :plan/adapter
            :plan/effects
            :plan/effect-roots
            :plan/combined-effect-root
            :plan/effect-schema-roots
            :plan/declared-maximum
            :plan/funding-available
            :plan/obligation-id
            :plan/no-duplicate-creation-key
            :plan/preconditions
            :plan/idempotency-key
            :plan/context]
           wb-plan/plan-hash-projection-fields))))

(deftest base-plan-root-composes-into-roots
  (let [with-base (wb-plan/build-with-bounty-plan
                   (assoc base-opts :base-plan-root "sha256:base-plan"))]
    (is (= :valid (:status with-base)))
    (is (= "sha256:base-plan" (get-in with-base [:plan :plan/base-plan-root])))
    (is (not= (:plan/combined-effect-root (get-in (build-plan) [:plan]))
              (:plan/combined-effect-root (get-in with-base [:plan]))))))
