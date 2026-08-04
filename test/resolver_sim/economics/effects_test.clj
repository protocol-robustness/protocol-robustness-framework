(ns resolver-sim.economics.effects-test
  "Phase 5: typed, versioned effect intents, adapter support validation, and
   the effects application plan (v2)."
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.economics.effects :as fx]
            [resolver-sim.economics.slash-distribution :as sd]
            [resolver-sim.economics.slash-distribution-application-plan :as plan]))

(def effect-policy
  {:schema-version "slash-distribution-policy.v1"
   :policy/id :test.policy/effect
   :policy/version 1
   :allocation {:method :weighted :scale 10000
                :weights {:test.allocation/a 10000}
                :remainder-to :test.allocation/a}
   :awards
   [{:award/id :test.award/reward
     :amount {:method :rate-of-gross
              :parameter-key :test.parameter/reward-rate
              :scale 10000
              :rounding :floor}
     :eligibility {:trigger :test.trigger/qualified-event
                   :beneficiary-role :test.role/reporter
                   :requires-evidence-reference? true}
     :funding {:method :weighted-deduction :scale 10000
               :weights {:test.allocation/a 10000}
               :remainder-to :test.allocation/a}
     :settlement {:allocation-id :test.allocation/reward-pool
                  :obligation-kind :test.obligation/reward}}]})

(defn- build-distribution
  []
  (sd/build-slash-distribution
   {:gross-amount 1000
    :policy effect-policy
    :parameter-context {:source-root "sha256:test" :values {:test.parameter/reward-rate 500}}
    :resolved-awards [{:award/id :test.award/reward
                       :eligibility {:trigger :test.trigger/qualified-event
                                     :evidence-reference "sha256:evidence"}
                       :beneficiary {:participant/id :test.participant/alice
                                     :participant/role :test.role/reporter}}]
    :context {}}))

;; ── validation ────────────────────────────────────────────────────────────

(deftest validate-effect-accepts-known-contracts
  (is (:valid? (fx/validate-effect
                {:effect/type :balance/credit
                 :effect/contract :prf.effect/balance-credit.v1
                 :effect/account :test.allocation/reward-pool
                 :effect/amount 50})))
  (is (:valid? (fx/validate-effect
                {:effect/type :obligation/create
                 :effect/contract :prf.effect/obligation-create.v1
                 :obligation/type :test.obligation/reward
                 :obligation/amount 50
                 :obligation/owner :test.participant/alice}))))

(deftest validate-effect-rejects-missing-and-unknown-contracts
  (is (some #(= :violation/effect-missing-contract (:violation/id %))
            (:violations (fx/validate-effect {:effect/type :balance/credit}))))
  (is (some #(= :violation/unknown-effect-contract (:violation/id %))
            (:violations (fx/validate-effect
                          {:effect/contract :prf.effect/does-not-exist.v1})))))

(deftest validate-effect-rejects-schema-mismatch
  (let [{:keys [valid? violations]}
        (fx/validate-effect
         {:effect/type :balance/credit
          :effect/contract :prf.effect/balance-credit.v1
          :effect/account :test.allocation/reward-pool
          :effect/amount "fifty"})]
    (is (not valid?))
    (is (some #(= :violation/schema-type-mismatch (:violation/id %)) violations))))

(deftest effect-schema-roots-deterministic
  (is (= (fx/effect-schema-root (get fx/effect-schema-maps :prf.effect/balance-credit.v1))
         (fx/effect-schema-root (get fx/effect-schema-maps :prf.effect/balance-credit.v1))))
  (is (= 3 (count fx/effect-schema-roots)))
  (is (every? #(= 64 (count %)) (vals fx/effect-schema-roots))))

;; ── derivation ────────────────────────────────────────────────────────────

(deftest award->effects-shape
  (let [award {:award/id :test.award/reward
               :award/amount 50
               :beneficiary {:participant/id :test.participant/alice}
               :settlement {:allocation-id :test.allocation/reward-pool
                            :obligation-kind :test.obligation/reward}}
        [credit obligation] (fx/award->effects award)]
    (is (= :prf.effect/balance-credit.v1 (:effect/contract credit)))
    (is (= :test.allocation/reward-pool (:effect/account credit)))
    (is (= 50 (:effect/amount credit)))
    (is (= :prf.effect/obligation-create.v1 (:effect/contract obligation)))
    (is (= :test.participant/alice (:obligation/owner obligation)))))

(deftest distribution->effects-valid
  (let [dist (:distribution (build-distribution))
        {:keys [effects valid?]} (fx/distribution->effects dist)]
    (is valid?)
    (is (= 2 (count effects)))
    (is (= #{:prf.effect/balance-credit.v1 :prf.effect/obligation-create.v1}
           (set (map :effect/contract effects))))))

;; ── adapter support (fail-before-mutation) ────────────────────────────────

(deftest supported-effect?-declaration
  (let [support {:adapter/id :sew/v1
                 :adapter/supported-effects
                 #{:prf.effect/balance-credit.v1 :prf.effect/obligation-create.v1}}
        effect {:effect/contract :prf.effect/balance-credit.v1}
        unsupported {:effect/contract :prf.effect/custody-held-adjustment.v1}]
    (is (fx/supported-effect? support effect))
    (is (not (fx/supported-effect? support unsupported)))))

(deftest validate-effects-for-adapter-fails-closed
  (let [dist (:distribution (build-distribution))
        {:keys [effects]} (fx/distribution->effects dist)
        full {:adapter/id :sew/v1
              :adapter/supported-effects
              #{:prf.effect/balance-credit.v1 :prf.effect/obligation-create.v1}}
        partial {:adapter/id :sew/v1
                 :adapter/supported-effects #{:prf.effect/balance-credit.v1}}]
    (is (:valid? (fx/validate-effects-for-adapter full effects)))
    (let [{:keys [valid? violations]}
          (fx/validate-effects-for-adapter partial effects)]
      (is (not valid?))
      (is (some #(= :violation/unsupported-effect-for-adapter (:violation/id %))
                violations)))))

(deftest effect->transition-projection
  (is (= {:transition/type :credit
          :transition/account :test.allocation/reward-pool
          :transition/amount 50}
         (fx/effect->transition
          {:effect/type :balance/credit
           :effect/contract :prf.effect/balance-credit.v1
           :effect/account :test.allocation/reward-pool
           :effect/amount 50})))
  (is (= :custody-credit
         (:transition/type
          (fx/effect->transition
           {:effect/type :custody/held-adjustment
            :effect/contract :prf.effect/custody-held-adjustment.v1
            :effect/direction :add
            :effect/account :appeal-bond
            :effect/amount 100})))))

;; ── application plan v2 ───────────────────────────────────────────────────

(deftest build-effects-plan-commits-validated-effects
  (let [dist (:distribution (build-distribution))
        {:keys [status plan]}
        (plan/build-effects-plan
         {:distribution dist
          :policy effect-policy
          :idempotency-key [:test-app 0]
          :context {}})]
    (is (= :valid status))
    (is (= plan/schema-version-v2 (:schema-version plan)))
    (is (= 2 (count (:plan/effects plan))))
    (is (contains? (:plan/effect-schema-roots plan) :prf.effect/balance-credit.v1))
    (is (= 3 (count (:plan/effect-schema-roots plan))))
    (is (= 64 (count (:plan/hash plan))))
    (is (:valid? (plan/validate-application-plan plan)))
    (is (:valid? (plan/verify-application-plan plan)))))

(deftest effects-plan-tamper-detected
  (let [dist (:distribution (build-distribution))
        {:keys [plan]} (plan/build-effects-plan
                        {:distribution dist
                         :policy effect-policy
                         :idempotency-key [:test-app 1]
                         :context {}})
        tampered (-> plan
                     (assoc-in [:plan/effects 0 :effect/amount] 999)
                     (assoc :plan/hash "ignored"))]
    (is (not (:valid? (plan/verify-application-plan tampered))))))

(deftest plan-effects-for-adapter-validation
  (let [dist (:distribution (build-distribution))
        {:keys [plan]} (plan/build-effects-plan
                        {:distribution dist
                         :policy effect-policy
                         :idempotency-key [:test-app 2]
                         :context {}})
        support {:adapter/id :sew/v1
                 :adapter/supported-effects
                 #{:prf.effect/balance-credit.v1 :prf.effect/obligation-create.v1}}]
    (is (:valid? (plan/validate-plan-effects-for-adapter support plan)))
    (is (not (:valid? (plan/validate-plan-effects-for-adapter
                       {:adapter/id :sew/v1
                        :adapter/supported-effects #{:prf.effect/balance-credit.v1}}
                       plan))))))

(deftest v1-plan-still-verifies
  (let [dist (:distribution (build-distribution))
        {:keys [status plan]}
        (plan/build-application-plan
         {:distribution dist
          :policy effect-policy
          :idempotency-key [:test-app 3]
          :context {}})]
    (is (= :valid status))
    (is (= plan/schema-version (:schema-version plan)))
    (is (:valid? (plan/verify-application-plan plan)))
    (is (nil? (:plan/effects plan)))))
