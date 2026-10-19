(ns resolver-sim.protocols.sew.with-bounty-test
  "Stage B vertical slice, Sew side: apply a validated with-bounty application
   plan to a Sew world through the canonical payable/backing and held-custody
   paths. Covers preflight fail-before-mutation, idempotency (no duplicate
   economic state under retry), reserve conservation, and bound transition
   evidence (design note §19 Sew parity)."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.economics.effects :as effects]
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
                          :funding-source-evidence (fixtures/funding-source {})
                          :funding-trust-policy fixtures/funding-trust-policy
          :adapter-support sew-wb/adapter-support})]
    (is (= :applied status) (pr-str violations))
    plan))

(defn- apply-plan [plan world]
  (sew-wb/apply-with-bounty-plan plan world fixtures/funding-trust-policy))

(defn- obligation-id
  [plan]
  (:plan/obligation-id plan))

(deftest preflight-passes-on-valid-plan
  (let [plan (evaluated-plan)]
    (is (:valid? (sew-wb/preflight plan fixtures/funding-trust-policy)))))

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
    (is (not (:valid? (sew-wb/preflight tampered fixtures/funding-trust-policy))))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"preflight failed"
         (apply-plan tampered world-before)))
    (is (= world-before (try (apply-plan tampered world-before)
                             (catch clojure.lang.ExceptionInfo _ world-before))))))

(deftest apply-creates-payable-backing-claimable-and-custody
  (let [plan (evaluated-plan)
        result (apply-plan plan {})
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
    (is (= :backed (get-in (first (vals (:with-bounty/payables world)))
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

(deftest observed-direct-payout-retains-and-replays-canonical-settlement
  (let [plan (evaluated-plan)
        world (:world (apply-plan plan {}))
        payable (get-in world [:with-bounty/payables (:plan/obligation-id plan)])
        backing (some #(when (= (:payable/hash payable) (:backing/payable-root %)) %)
                      (vals (:with-bounty/backings world)))
        amount (:payable/amount payable)
        receipt (fixtures/payout-receipt payable backing)
        observation (fixtures/chain-observation
                     {:observation/payable-id (:payable/id payable)
                      :observation/transaction-hash
                      (str "tx:fixture:" (:payable/id payable))
                      :observation/asset (get-in backing [:backing/context :funding/token])
                      :observation/operation-root
                      (get-in backing [:backing/context :funding/operation-root])
                      :observation/funding-source-root
                      (get-in backing [:backing/context :funding/source-root])
                      :observation/prf-funding-source-root
                      (get-in backing [:backing/context :funding/source-root])
                      :observation/beneficiary (:payable/beneficiary payable)
                      :observation/amount amount
                      :observation/reserve-before 1000
                      :observation/reserve-after (- 1000 amount)
                      :observation/beneficiary-before 5
                      :observation/beneficiary-after (+ 5 amount)})
        result (sew-wb/apply-observed-direct-payout
                world (:payable/id payable) receipt fixtures/payout-trust-policy
                observation fixtures/chain-observer-trust-policy
                {:chain-id 31337
                 :contract "0x000000000000000000000000000000000000b017"
                 :chain-instance-root (str "sha256:" (apply str (repeat 64 "b")))
                 :genesis-hash (str "0x" (apply str (repeat 64 "0")))
                 :contract-code-sha256 (str "sha256:" (apply str (repeat 64 "a")))
                 :observation-source-id "test-rpc-observer"
                 :asset :token/usdc
                 :funding-source-root
                 (get-in backing [:backing/context :funding/source-root])})
        settled-world (:world result)]
    (is (= "obligation-settlement.v1"
           (get-in settled-world [:with-bounty/settlements (:payable/id payable)
                                  :settlement :settlement/schema])))
    (is (:valid? (verification/verify-application-world
                  settled-world
                  {:funding-trust-policy fixtures/funding-trust-policy
                   :payout-trust-policy fixtures/payout-trust-policy
                   :observation-trust-policy fixtures/chain-observer-trust-policy
                   :expected-settlement-context
                   {:chain-id 31337
                    :contract "0x000000000000000000000000000000000000b017"
                    :chain-instance-root (str "sha256:" (apply str (repeat 64 "b")))
                    :genesis-hash (str "0x" (apply str (repeat 64 "0")))
                    :contract-code-sha256 (str "sha256:" (apply str (repeat 64 "a")))
                    :observation-source-id "test-rpc-observer"
                    :asset :token/usdc
                    :funding-source-root
                    (get-in backing [:backing/context :funding/source-root])}
                   :require-settlement-evidence? true})))
    (is (not (:valid? (verification/verify-application-world
                       (update-in settled-world
                                  [:with-bounty/settlements (:payable/id payable)
                                   :settlement :settlement/paid] dec)
                       {:funding-trust-policy fixtures/funding-trust-policy
                        :payout-trust-policy fixtures/payout-trust-policy
                        :observation-trust-policy fixtures/chain-observer-trust-policy
                        :expected-settlement-context
                        {:chain-id 31337
                         :contract "0x000000000000000000000000000000000000b017"
                         :chain-instance-root (str "sha256:" (apply str (repeat 64 "b")))
                         :genesis-hash (str "0x" (apply str (repeat 64 "0")))
                         :contract-code-sha256 (str "sha256:" (apply str (repeat 64 "a")))
                         :observation-source-id "test-rpc-observer"
                         :asset :token/usdc
                         :funding-source-root
                         (get-in backing [:backing/context :funding/source-root])}
                        :require-settlement-evidence? true}))))))

(deftest retry-is-idempotent-without-duplicate-state
  (let [plan (evaluated-plan)
        first-result (apply-plan plan {})
        second-result (apply-plan plan (:world first-result))
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
         (apply-plan plan world)))
    (is (= "sha256:conflicting" (get-in world (:plan/idempotency-key plan))))))

(deftest reserve-conservation-holds
  (let [plan (evaluated-plan)
        result (apply-plan plan {})
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
        tampered (assoc plan :plan/context
                        (assoc (:plan/context plan) :note "tampered"))
        tampered (assoc tampered :plan/hash (wb-plan/plan-hash tampered))
        world (:world (apply-plan plan {}))]
    (is (not= (:plan/hash plan) (:plan/hash tampered)))
    (is (= (:plan/obligation-id plan) (:plan/obligation-id tampered)))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"conflicting application"
         (apply-plan tampered world)))))

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
                :funding-source-evidence (fixtures/funding-source
                                          {:token :token/eth})
                :funding-trust-policy fixtures/funding-trust-policy
                :adapter-support sew-wb/adapter-support})
        other-plan (:plan other)
        world (:world (apply-plan plan {}))]
    (is (= :applied (:status other)))
    (is (not= (:plan/obligation-id plan) (:plan/obligation-id other-plan)))
    (is (= (:plan/no-duplicate-creation-key plan)
           (:plan/no-duplicate-creation-key other-plan)))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"duplicate obligation"
         (apply-plan other-plan world)))))

(deftest idempotent-replay-with-drifted-state-fails
  (let [plan (evaluated-plan)
        world (:world (apply-plan plan {}))
        drifted (update world :with-bounty/backings dissoc (str "backing-" (:plan/obligation-id plan)))]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"drifted state"
         (apply-plan plan drifted)))))

(deftest idempotent-replay-requires-exact-custody-reservation
  (let [plan (evaluated-plan)
        world (:world (apply-plan plan {}))
        drifted (assoc-in world [:held-adjustments 0 :held/reason] :other-reservation)]
    ;; A held-adjustment count alone must not permit a mismatched reservation to
    ;; satisfy a replay of the committed plan.
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"drifted state"
         (apply-plan plan drifted)))))

(deftest duplicate-reservation-fails-before-mutation
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
                      :funding-available 1000
                      :funding-evidence (get-in base [:plan/context :funding/source-evidence])})
        tampered (:plan plan-result)
        world-before {}]
    (is (= :invalid (:status plan-result)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (apply-plan tampered world-before)))
    (is (= world-before
           (try (apply-plan tampered world-before)
                (catch clojure.lang.ExceptionInfo _ world-before))))))

(deftest transition-binds-exact-artifacts
  (let [plan (evaluated-plan)
        result (apply-plan plan {})
        world (:world result)
        transition (:transition result)
        adjustment-id (get-in transition [:custody/adjustment-roots 0 :held-adjustment/id])]
    (is (:valid? (verification/verify-transition-binds-world transition world)))
    (is (:valid? (verification/verify-transition-with-plan transition plan)))
    (is (not (:valid? (verification/verify-transition-binds-world
                       transition (dissoc world :with-bounty/payables)))))
    (is (not (:valid? (verification/verify-transition-binds-world
                       transition
                       (assoc-in world [:held-artifacts adjustment-id :artifact/hash]
                                 "tampered-artifact-hash")))))
    (is (= 1 (count (:custody/adjustment-roots transition))))
    (is (string? (get-in transition [:custody/adjustment-roots 0 :artifact/hash])))))

(deftest claimable-derives-from-backed-payable
  (let [plan (evaluated-plan)
        world (:world (apply-plan plan {}))]
    (is (:valid? (verification/verify-application-world
                  world {:funding-trust-policy fixtures/funding-trust-policy})))
    (is (not (:valid? (verification/verify-application-world
                       (update world :with-bounty/payables dissoc (:plan/obligation-id plan))))))
    (is (not (:valid? (verification/verify-application-world
                       (update world :with-bounty/backings
                               dissoc (str "backing-" (:plan/obligation-id plan)))))))))

(deftest adapter-held-actions-are-a-subset-of-protocol-vocabulary
  (testing "the adapter's allowed actions are a subset of the global protocol
            vocabulary, so global support never implies adapter capability"
    (is (set/subset? sew-wb/adapter-held-actions
                     effects/supported-held-actions))
    (is (contains? sew-wb/adapter-held-actions "add-held"))
    (is (contains? sew-wb/adapter-held-actions "sub-held"))
    (is (not (contains? sew-wb/adapter-held-actions "refund-held")))))

(deftest preflight-rejects-globally-valid-but-locally-unsupported-custody-action
  (testing "a crafted v2 custody effect carrying a globally valid held action
            the adapter cannot execute (refund-held) fails preflight BEFORE any
            mutation — it cannot acquire capability merely because the action
            exists in the custody protocol"
    (let [plan (evaluated-plan)
          effects (mapv (fn [e]
                          (if (= :custody/held-adjustment (:effect/type e))
                            (assoc e :effect/action "refund-held")
                            e))
                        (:plan/effects plan))
          tampered (assoc plan
                          :plan/effects effects
                          :plan/hash (wb-plan/plan-hash
                                      (assoc plan :plan/effects effects)))
          world-before {}
          result (sew-wb/preflight tampered fixtures/funding-trust-policy)]
      (is (not (:valid? result)))
      (is (some #(= :violation/unsupported-custody-action-for-adapter (:violation/id %))
                (:violations result)))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"preflight failed"
           (apply-plan tampered world-before)))
      (is (= world-before (try (apply-plan tampered world-before)
                               (catch clojure.lang.ExceptionInfo _ world-before)))))))

(deftest direct-payout-requires-authenticated-full-payment-and-is-replay-safe
  (let [plan (evaluated-plan)
        funded-world (:world (apply-plan plan {}))
        payable (get-in funded-world [:with-bounty/payables (:plan/obligation-id plan)])
        backing (first (vals (:with-bounty/backings funded-world)))
        receipt (fixtures/payout-receipt payable backing)
        settled (sew-wb/apply-verified-direct-payout
                 funded-world (:payable/id payable) receipt fixtures/payout-trust-policy)
        world (:world settled)]
    (is (= :settled (get-in world [:with-bounty/payables (:payable/id payable)
                                   :payable/lifecycle])))
    (is (= :consumed (get-in world [:with-bounty/backings (:backing/id backing)
                                    :backing/lifecycle])))
    (is (zero? (get-in world [:claimable-v2 (:payable/id payable)
                              :liability/bounty-payable (:payable/beneficiary payable)])))
    (is (= 1 (count (get-in world [:with-bounty/payout-events (:payable/id payable)]))))
    (is (zero? (get-in world [:total-held :token/usdc])))
    (is (= 2 (count (:held-adjustments world))))
    (is (:valid? (verification/verify-application-world
                  world {:funding-trust-policy fixtures/funding-trust-policy
                         :payout-trust-policy fixtures/payout-trust-policy})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (sew-wb/apply-verified-direct-payout
                  world (:payable/id payable) receipt fixtures/payout-trust-policy)))))

(deftest direct-payout-rejects-forgery-untrusted-authority-and-partial-payment
  (let [plan (evaluated-plan)
        world (:world (apply-plan plan {}))
        payable (get-in world [:with-bounty/payables (:plan/obligation-id plan)])
        backing (first (vals (:with-bounty/backings world)))
        receipt (fixtures/payout-receipt payable backing)
        tampered (assoc receipt :payout/amount (dec (:payout/amount receipt)))
        partial (fixtures/payout-receipt payable backing {:amount (dec (:payable/amount payable))})]
    (doseq [[bad bad-trust] [[tampered fixtures/payout-trust-policy]
                             [receipt {:trusted-keys []}]
                             [partial fixtures/payout-trust-policy]]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (sew-wb/apply-verified-direct-payout
                    world (:payable/id payable) bad bad-trust))))))
