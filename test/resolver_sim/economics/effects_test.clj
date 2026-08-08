(ns resolver-sim.economics.effects-test
  "Phase 5: typed, versioned effect intents, adapter support validation, and
   the effects application plan (v2)."
  (:require [clojure.test :refer [deftest is testing]]
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
                 :obligation/owner :test.participant/alice})))
  (is (:valid? (fx/validate-effect
                {:effect/type :custody/held-adjustment
                 :effect/contract :prf.effect/custody-held-adjustment.v1
                 :effect/direction :add
                 :effect/account :escrow
                 :effect/amount 100
                 :held/kind :escrow
                 :owner/address "0xowner"
                 :parameter/address "0xparameter"})))
  (is (:valid? (fx/validate-effect
                {:effect/type :custody/held-adjustment
                 :effect/contract :prf.effect/custody-held-adjustment.v2
                 :effect/action "add-held"
                 :effect/account :escrow
                 :effect/amount 100
                 :effect/token :usdc
                 :held/kind :escrow
                 :owner/address "0xowner"
                 :parameter/address "0xparameter"}))))

(deftest validate-custody-effect-requires-kind
  (let [{:keys [valid? violations]}
        (fx/validate-effect
         {:effect/type :custody/held-adjustment
          :effect/contract :prf.effect/custody-held-adjustment.v1
          :effect/direction :add
          :effect/account :escrow
          :effect/amount 100})]
    (is (not valid?))
    (is (some #(= :violation/missing-schema-key (:violation/id %)) violations))))

(deftest validate-custody-v2-fails-closed
  (testing "v2 direction is DERIVED, never independently supplied"
    (let [{:keys [valid? violations]}
          (fx/validate-effect
           {:effect/type :custody/held-adjustment
            :effect/contract :prf.effect/custody-held-adjustment.v2
            :effect/action "add-held"
            :effect/direction :add
            :effect/account :escrow
            :effect/amount 100
            :effect/token :usdc
            :held/kind :escrow})]
      (is (not valid?))
      (is (some #(= :violation/custody-v2-derived-direction (:violation/id %)) violations))))
  (testing "an unknown held action fails closed"
    (let [{:keys [valid? violations]}
          (fx/validate-effect
           {:effect/type :custody/held-adjustment
            :effect/contract :prf.effect/custody-held-adjustment.v2
            :effect/action "withdraw"
            :effect/account :escrow
            :effect/amount 100
            :effect/token :usdc
            :held/kind :escrow})]
      (is (not valid?))
      (is (some #(= :violation/unsupported-held-action (:violation/id %)) violations))))
  (testing "a v1 effect with an explicit action is bound to the contract"
    (is (nil? (fx/effect-held-direction
               {:effect/type :custody/held-adjustment
                :effect/contract :prf.effect/custody-held-adjustment.v1
                :effect/action "not-a-real-action"})))))

(deftest custody-effect-projects-to-add-held-opts
  (testing "v2 direction-bound projection: the action is the effect's canonical
            held action, never hardcoded"
    (let [effect {:effect/type :custody/held-adjustment
                  :effect/contract :prf.effect/custody-held-adjustment.v2
                  :effect/action "add-held"
                  :effect/account :escrow
                  :effect/amount 100
                  :held/kind :reward
                  :parameter/context "sew:governance-snapshot"
                  :parameter/address "sew:params/reward-rate"}]
      (is (= {:action "add-held"
              :reason :reward
              :parameter/context "sew:governance-snapshot"
              :parameter/address "sew:params/reward-rate"
              :extra {:held/account :escrow}}
             (fx/custody-effect->add-held-opts effect)))))
  (testing "an outbound (sub-held) v2 effect projects to the sub-held action"
    (is (= "sub-held"
           (:action (fx/custody-effect->add-held-opts
                     {:effect/type :custody/held-adjustment
                      :effect/contract :prf.effect/custody-held-adjustment.v2
                      :effect/action "sub-held"
                      :effect/account :escrow
                      :effect/amount 40
                      :held/kind :release})))))
  (testing "a v1 effect derives its action from :effect/direction"
    (is (= "sub-held"
           (:action (fx/custody-effect->add-held-opts
                     {:effect/type :custody/held-adjustment
                      :effect/contract :prf.effect/custody-held-adjustment.v1
                      :effect/direction :sub
                      :effect/account :escrow
                      :effect/amount 40
                      :held/kind :release})))))
  (is (nil? (fx/custody-effect->add-held-opts
             {:effect/type :balance/credit
              :effect/contract :prf.effect/balance-credit.v1
              :effect/account :x :effect/amount 1}))))

(deftest v1-direction-to-action-is-a-legacy-interpretation-rule
  (testing "the held-action vocabulary is many-to-one onto direction
            (add-held->:in; sub-held/finalize-released/refund-held->:out), so
            v1 :out cannot be inverted to a precise action"
    (is (= {"add-held" :in "sub-held" :out "finalize-released" :out "refund-held" :out}
           fx/held-action->direction)))
  (testing "v1 never distinguished refund, release finalization, and subtraction:
            v1 :out is interpreted as sub-held (the accounting outbound action),
            never as refund-held or finalize-released"
    (let [v1-out {:effect/type :custody/held-adjustment
                  :effect/contract :prf.effect/custody-held-adjustment.v1
                  :effect/direction :out
                  :effect/account :escrow
                  :effect/amount 40
                  :held/kind :release}
          v1-sub {:effect/type :custody/held-adjustment
                  :effect/contract :prf.effect/custody-held-adjustment.v1
                  :effect/direction :sub
                  :effect/account :escrow
                  :effect/amount 40
                  :held/kind :release}]
      (is (= "sub-held" (fx/effect-action v1-out)))
      (is (= "sub-held" (fx/effect-action v1-sub)))
      (is (not= "refund-held" (fx/effect-action v1-out)))
      (is (not= "finalize-released" (fx/effect-action v1-out)))))
  (testing "the legacy interpretation is explicit and read-only: it is declared
            as a rule, not presented as generic derivation"
    (is (= {:in "add-held" :out "sub-held"} fx/v1-legacy-direction->action))))

;; ── canonical held-adjustment records ─────────────────────────────────────

(def custody-effect
  {:effect/type :custody/held-adjustment
   :effect/contract :prf.effect/custody-held-adjustment.v2
   :effect/action "add-held"
   :effect/account :escrow
   :effect/amount 100
   :effect/token :usdc
   :held/kind :reward
   :owner/address "0xowner"
   :parameter/context {:parameter-context/type :protocol-parameters
                       :parameter-context/root
                       "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                       :parameter-context/version 1
                       :parameter-context/scope-id :sew.default}
   :parameter/address {:parameter/id :sew.params/reward-rate}})

(deftest add-held-adjustment-builds-canonical-record
  (let [record (fx/add-held-adjustment custody-effect :usdc
                                       {:held-adjustment/id "held-adjustment-1"
                                        :held/before 500
                                        :held/after 600})]
    (is (= :in (:held/direction record)))
    (is (= "add-held" (:held/action record)))
    (is (= :usdc (:token record)))
    (is (= 100 (:amount record)))
    (is (= :escrow (:held/account record)))
    (is (= :reward (:held/reason record)))
    (is (= "0xowner" (:owner/address record)))
    (is (= (:parameter/context custody-effect) (:parameter/context record)))
    (is (= (:parameter/address custody-effect) (:parameter/address record)))
    (is (= "held-adjustment-1" (:held-adjustment/id record)))))

(deftest held-adjustment-uses-effect-action
  (testing "v2 direction derives from the canonical action contract"
    (is (= :in (:held/direction
                (fx/held-adjustment custody-effect
                                    :usdc {:held/before 500 :held/after 600}))))
    (is (= :out (:held/direction
                 (fx/held-adjustment (assoc custody-effect :effect/action "sub-held")
                                     :usdc {:held/before 600 :held/after 500}))))))

(deftest held-adjustment-validity
  (is (fx/held-adjustment-valid? custody-effect))
  (is (not (fx/held-adjustment-valid? (dissoc custody-effect :held/kind))))
  (is (not (fx/held-adjustment-valid?
            (dissoc custody-effect :parameter/context)))))

(deftest held-adjustment-invalid-attribution-throws-on-build
  (is (thrown? clojure.lang.ExceptionInfo
               (fx/add-held-adjustment (dissoc custody-effect :parameter/context)
                                       :usdc {}))))

(deftest held-adjustment-root-deterministic-and-sensitive
  (let [r (fx/held-adjustment
           custody-effect :usdc {:held-adjustment/id "held-adjustment-1"
                                 :held/before 500 :held/after 600})]
    (is (= (fx/held-adjustment-root r) (fx/held-adjustment-root r)))
    (is (= 64 (count (fx/held-adjustment-root r))))
    (is (not= (fx/held-adjustment-root r)
              (fx/held-adjustment-root (assoc r :held/after 700))))))

(deftest custody-effect-conflicts-detected
  (let [base {:effect/type :custody/held-adjustment
              :effect/contract :prf.effect/custody-held-adjustment.v2
              :effect/account :escrow
              :effect/amount 10
              :held/kind :reward}
        add (assoc base :effect/action "add-held")
        sub (assoc base :effect/action "sub-held")
        other-account (assoc sub :effect/account :appeal-bond)]
    (is (empty? (fx/custody-effect-conflicts [])))
    (is (= 1 (count (fx/custody-effect-conflicts [add sub]))))
    (is (= :escrow (:effect/account (first (fx/custody-effect-conflicts [add sub])))))
    (is (empty? (fx/custody-effect-conflicts [add add])))
    (is (empty? (fx/custody-effect-conflicts [add other-account])))))

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
  (is (= 5 (count fx/effect-schema-roots)))
  (is (every? #(= 64 (count %)) (vals fx/effect-schema-roots))))

(deftest obligation-create-v2-validates
  (let [effect {:effect/type :obligation/create
                :effect/contract :prf.effect/obligation-create.v2
                :obligation/type :bounty-payable
                :obligation/id "sha256:obligation"
                :obligation/amount 500
                :obligation/token :token/usdc
                :obligation/owner :researcher/alice
                :obligation/funding {:source :declared-reserve}
                :obligation/subject {:operation-root "sha256:op"
                                     :bounty-id :review-completion}
                :effect/provenance {:policy-root "sha256:policy"}}]
    (is (:valid? (fx/validate-effect effect)))
    (is (contains? fx/effect-schema-roots :prf.effect/obligation-create.v2))
    (is (not (:valid? (fx/validate-effect (assoc effect :obligation/token "USDC")))))))

(deftest normalize-v1-obligation-create->v2
  (let [v1 {:effect/type :obligation/create
            :effect/contract :prf.effect/obligation-create.v1
            :obligation/type :test.obligation/reward
            :obligation/amount 50
            :obligation/owner :test.participant/alice}
        v2 (fx/normalize-v1-obligation-create v1)]
    (is (= :prf.effect/obligation-create.v2 (:effect/contract v2)))
    (is (string? (:obligation/id v2)))
    (is (= 50 (:obligation/amount v2)))
    (is (:valid? (fx/validate-effect v2)))
    (is (= :token/unspecified (:obligation/token v2)))))

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
  (let [custody-transition
        (fx/effect->transition
         {:effect/type :custody/held-adjustment
          :effect/contract :prf.effect/custody-held-adjustment.v2
          :effect/action "add-held"
          :effect/account :appeal-bond
          :effect/amount 100
          :held/kind :appeal-bond})]
    (is (= :custody (:transition/type custody-transition)))
    (is (= "add-held" (:held/action custody-transition)))
    (is (= :in (:held/direction custody-transition)))
    (is (= :appeal-bond (:held/kind custody-transition)))))

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
    (is (= 5 (count (:plan/effect-schema-roots plan))))
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
