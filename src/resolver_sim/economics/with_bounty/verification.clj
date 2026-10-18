(ns resolver-sim.economics.with-bounty.verification
  "Structural verification for with-bounty artifacts (ADR-0006 D8).

   Each artifact ships with its structural verifier as it lands: policy root
   (Stage 1), invocation/evaluation envelope and effect (Stage 2), application
   plan (Stage 3), transition evidence (Stage 4). These are structural checks
   — recomputing committed roots and validating shapes. They are NOT
   independent verification and must not be labelled as such (ADR-0006 D6);
   implementation replay and verifier composition are Stage C."
  (:require [resolver-sim.assurance.custody :as custody]
            [resolver-sim.economics.bounty-payable :as bp]
            [resolver-sim.economics.bounty-payable-backing :as bpb]
            [resolver-sim.economics.effects :as effects]
            [resolver-sim.economics.with-bounty.funding-evidence :as funding-evidence]
            [resolver-sim.economics.with-bounty.obligation-settlement :as obligation-settlement]
            [resolver-sim.economics.with-bounty.payout-evidence :as payout-evidence]
            [resolver-sim.economics.with-bounty.application-plan :as wb-plan]
            [resolver-sim.economics.with-bounty.policy :as policy]
            [resolver-sim.economics.with-bounty.transition-evidence :as wb-transition]
            [resolver-sim.hash.canonical :as hc]))

(defn verify-policy-root
  "Structural: the committed policy root recomputes from the policy."
  [policy policy-root]
  (let [computed (policy/with-bounty-policy-root policy)]
    (if (= computed policy-root)
      {:valid? true}
      {:valid? false :violations
       [{:violation/id :violation/policy-root-mismatch
         :details {:committed policy-root :computed computed}}]})))

(defn verify-invocation-evidence
  "Structural: an invocation evidence entry carries a deterministic 64-hex
   invocation id and a capability reference."
  [evidence]
  (let [envelope (get-in evidence [:invocation/evidence-envelope])
        id (get-in envelope [:invocation/id])]
    (cond
      (not (map? envelope))
      {:valid? false :violations
       [{:violation/id :violation/missing-invocation-envelope :details {}}]}

      (not (and (string? id) (= 64 (count id))))
      {:valid? false :violations
       [{:violation/id :violation/invalid-invocation-id :details {:id id}}]}

      (not (vector? (:capability/ref envelope)))
      {:valid? false :violations
       [{:violation/id :violation/missing-capability-ref
         :details {:capability/ref (:capability/ref envelope)}}]}

      :else {:valid? true})))

(defn verify-effect
  "Structural: the effect validates against its versioned contract."
  [effect]
  (effects/validate-effect effect))

(defn verify-effects
  [effects]
  (effects/validate-effects effects))

(defn verify-application-plan
  "Structural: the committed plan hash recomputes."
  [plan]
  (wb-plan/verify-with-bounty-plan plan))

(defn verify-transition-evidence
  "Structural: the committed transition evidence hash recomputes."
  [evidence]
  (wb-transition/verify-transition-evidence evidence))

(defn verify-transition-binds-world
  "Verify transition integrity and that its successor root commits to the
   supplied post-state. This is structural evidence, not external execution."
  [evidence world]
  (let [evidence-result (wb-transition/verify-transition-evidence evidence)
        world-root (hc/hash-with-intent {:hash/intent :world-structure} world)]
    (if (and (:valid? evidence-result)
             (= world-root (:world-after-root evidence)))
      {:valid? true}
      {:valid? false
       :violations (cond-> []
                     (not (:valid? evidence-result))
                     (conj {:violation/id :violation/transition-evidence-invalid
                            :details evidence-result})
                     (not= world-root (:world-after-root evidence))
                     (conj {:violation/id :violation/transition-world-root-mismatch
                            :details {:committed (:world-after-root evidence)
                                      :computed world-root}}))})))

(defn verify-composition-receipt
  "Structural: a composition receipt has the committed shape for its status."
  [receipt]
  (let [status (:composition/status receipt)
        violations (cond-> []
                     (not= :economics/with-bounty (:composition/type receipt))
                     (conj {:violation/id :violation/invalid-composition-type
                            :details {:type (:composition/type receipt)}})

                     (not (contains? #{:applied :skipped :failed} status))
                     (conj {:violation/id :violation/invalid-composition-status
                            :details {:status status}})

                     (not (string? (:composition/policy-root receipt)))
                     (conj {:violation/id :violation/missing-composition-policy-root
                            :details {}})

                     (not (string? (:composition/base-operation-root receipt)))
                     (conj {:violation/id :violation/missing-base-operation-root
                            :details {}})

                     (not (string? (:extensions/resolution-root receipt)))
                     (conj {:violation/id :violation/missing-resolution-root
                            :details {}})

                     (not (map? (:bounty/eligibility receipt)))
                     (conj {:violation/id :violation/missing-eligibility-evidence
                            :details {}})

                     (= :applied status)
                     (cond-> (not (string? (:bounty/effect-root receipt)))
                       (conj {:violation/id :violation/missing-effect-root :details {}})
                       (not (string? (:bounty/application-plan-root receipt)))
                       (conj {:violation/id :violation/missing-application-plan-root
                              :details {}})))]
    (if (seq violations)
      {:valid? false :violations violations}
      {:valid? true})))

;; ── cross-artifact reconciliation ─────────────────────────────────────────

(defn verify-receipt-with-plan
  "Reconciliation: the receipt's committed roots match the plan it references.
   Guards against a receipt that binds a valid effect root but the wrong
   application-plan root, or a different resolution root."
  [receipt plan]
  (let [violations (cond-> []
                     (not= (:bounty/application-plan-root receipt) (:plan/hash plan))
                     (conj {:violation/id :violation/receipt-plan-root-mismatch
                            :details {:committed (:bounty/application-plan-root receipt)
                                      :expected (:plan/hash plan)}})

                     (not= (:bounty/effect-root receipt) (first (:plan/effect-roots plan)))
                     (conj {:violation/id :violation/receipt-effect-root-mismatch
                            :details {:committed (:bounty/effect-root receipt)
                                      :expected (first (:plan/effect-roots plan))}})

                     (not= (:bounty/obligation-id receipt) (:plan/obligation-id plan))
                     (conj {:violation/id :violation/receipt-obligation-id-mismatch
                            :details {:committed (:bounty/obligation-id receipt)
                                      :expected (:plan/obligation-id plan)}})

                     (not= (:extensions/resolution-root receipt)
                           (:plan/extensions-resolution-root plan))
                     (conj {:violation/id :violation/receipt-resolution-root-mismatch
                            :details {:committed (:extensions/resolution-root receipt)
                                      :expected (:plan/extensions-resolution-root plan)}}))]
    (if (seq violations)
      {:valid? false :violations violations}
      {:valid? true})))

(defn verify-transition-with-plan
  "Reconciliation: the transition evidence binds the plan it references."
  [transition plan]
  (let [violations (cond-> []
                     (not= (:plan/root transition) (:plan/hash plan))
                     (conj {:violation/id :violation/transition-plan-root-mismatch
                            :details {:committed (:plan/root transition)
                                      :expected (:plan/hash plan)}})

                     (not= (:effect-root transition) (first (:plan/effect-roots plan)))
                     (conj {:violation/id :violation/transition-effect-root-mismatch
                            :details {:committed (:effect-root transition)
                                      :expected (first (:plan/effect-roots plan))}})

                     (not= (:combined-effect-root transition)
                           (:plan/combined-effect-root plan))
                     (conj {:violation/id :violation/transition-effect-set-mismatch
                            :details {:committed (:combined-effect-root transition)
                                      :expected (:plan/combined-effect-root plan)}}))]
    (if (seq violations)
      {:valid? false :violations violations}
      {:valid? true})))

(defn- custody-artifact-valid?
  [world adjustment]
  (let [artifact (get-in world [:held-artifacts (:held-adjustment/id adjustment)])]
    (= artifact (when adjustment (custody/build-held-custody-artifact adjustment)))))

(defn- payout-custody-valid?
  [world payable backing]
  (let [context (:backing/context backing)
        id (:payout/custody-adjustment-id context)
        adjustment (some #(when (= id (:held-adjustment/id %)) %)
                         (:held-adjustments world []))
        artifact (get-in world [:held-artifacts id])]
    (and (string? id)
         (= (:payout/custody-artifact-hash context) (:artifact/hash artifact))
         (custody-artifact-valid? world adjustment)
         (= :out (:held/direction adjustment))
         (= :bounty-direct-payout (:held/reason adjustment))
         (= (:payable/amount payable) (:amount adjustment))
         (= (get-in context [:funding/token]) (:token adjustment))
         (= (get-in context [:funding/account]) (:held/account adjustment))
         (= (:payable/beneficiary payable) (:owner/address adjustment))
         (= (:payout/evidence-root context) (:payout/evidence-root adjustment)))))

(defn- payout-settlement-valid?
  [world payable backing payout-trust-policy observation-trust-policy
   expected-settlement-context required? rpc-client expected-rpc-context
   require-rpc-observation?]
  (let [entry (get-in world [:with-bounty/settlements (:payable/id payable)])]
    (if-not entry
      (not required?)
      (let [context (:backing/context backing)
            predecessor-payable (let [p (assoc payable :payable/lifecycle :backed)]
                                  (assoc p :payable/hash (bp/payable-hash p)))
            predecessor-context (dissoc context :payout/evidence-root
                                        :payout/predecessor-root :payout/backing-root
                                        :payout/custody-adjustment-id
                                        :payout/custody-artifact-hash)
            predecessor-backing-base (assoc backing
                                             :backing/lifecycle :committed
                                             :backing/payable-root
                                             (:payout/predecessor-root context)
                                             :backing/context predecessor-context)
            predecessor-backing (assoc predecessor-backing-base :backing/hash
                                        (bpb/backing-hash predecessor-backing-base))
            replay (obligation-settlement/verify-settlement
                    {:settlement (:settlement entry)
                     :payable-before predecessor-payable
                     :payable-after payable
                     :backing predecessor-backing
                     :payout-receipt (:payout-receipt entry)
                     :payout-trust-policy payout-trust-policy
                     :observation (:observation entry)
                     :observation-trust-policy observation-trust-policy
                     :expected-context expected-settlement-context
                     :rpc-client rpc-client
                     :expected-rpc-context expected-rpc-context
                     :require-rpc-observation? require-rpc-observation?})]
        (:valid? replay)))))

(defn verify-application-world
  "Reconcile a with-bounty application world using the payable, backing, and
   held-custody artifact verifiers. Every payable must have exactly one valid,
   amount- and distribution-matching backing with source allocations summing to
   its amount; every such payable must have its exact derived claimable amount.
   The claimable domain is protocol-specific and supplied by the caller."
  [world & [opts]]
  (let [domain (or (:claimable/domain opts) :liability/bounty-payable)
        funding-trust-policy (:funding-trust-policy opts)
        payout-trust-policy (:payout-trust-policy opts)
        observation-trust-policy (:observation-trust-policy opts)
        expected-settlement-context (:expected-settlement-context opts)
        rpc-client (:rpc-client opts)
        expected-rpc-context (:expected-rpc-context opts)
        require-rpc-observation? (true? (:require-rpc-observation? opts))
        require-settlement-evidence? (true? (:require-settlement-evidence? opts))
        payables (vals (:with-bounty/payables world {}))
        backings (vals (:with-bounty/backings world {}))
        payable-roots (set (map :payable/hash payables))
        backing-payable-roots (map :backing/payable-root backings)
        invalid-payables (into [] (remove #(-> % bp/verify-bounty-payable :valid?) payables))
        invalid-backings (into [] (remove #(-> % bpb/verify-bounty-payable-backing :valid?) backings))
        invalid-custody (into [] (remove #(custody-artifact-valid? world %)
                                         (:held-adjustments world [])))
        unbacked (into [] (remove #(some #{%} backing-payable-roots)) payable-roots)
        duplicate-backing-roots (into [] (keep (fn [[root n]] (when (> n 1) root)))
                                      (frequencies backing-payable-roots))
        orphan-backings (into []
                              (remove #(contains? payable-roots (:backing/payable-root %)))
                              backings)
        inconsistent-backings
        (into []
              (filter (fn [backing]
                        (let [payable (some #(when (= (:payable/hash %)
                                                      (:backing/payable-root backing))
                                               %)
                                            payables)]
                          (and payable
                               (or (not= (:payable/id payable) (:backing/payable-id backing))
                                   (not= (:payable/distribution-root payable)
                                         (:backing/distribution-root backing))
                                   (not= (:payable/amount payable) (:backing/amount backing))
                                   (not= (:backing/amount backing)
                                         (bpb/backing-amount-reconciliation backing))))))
                      backings))
        backing-reservation-evidence-errors
        (into []
              (filter (fn [backing]
                        (let [context (:backing/context backing)
                              funding-source (:funding/source context)
                              funding-token (:funding/token context)
                              funding-account (:funding/account context)
                              funding-operation-root (:funding/operation-root context)
                              funding-context-root (:funding/parameter-context-root context)
                              funding-address (:funding/parameter-address context)
                              available-before (:funding/available-before context)
                              reserved-amount (:funding/reserved-amount context)
                              available-after (:funding/available-after context)
                              evidence-class (:funding/evidence-class context)
                              source-evidence (:funding/source-evidence context)
                              source-root (:funding/source-root context)
                              payable (some #(when (= (:payable/hash %) (:backing/payable-root backing)) %)
                                            payables)
                              adjustment-id (:custody/adjustment-id context)
                              artifact-hash (:custody/artifact-hash context)
                              adjustment (some #(when (= adjustment-id
                                                         (:held-adjustment/id %)) %)
                                               (:held-adjustments world []))
                              artifact (get-in world [:held-artifacts adjustment-id])
                              account (first (keys (:backing/source-allocations backing)))
                              adjustment-context-root
                              (get-in adjustment [:parameter/context :parameter-context/root])
                              raw-adjustment-address (:parameter/address adjustment)
                              adjustment-address (if (map? raw-adjustment-address)
                                                   (:parameter/path raw-adjustment-address)
                                                   raw-adjustment-address)]
                          (or (not= :declared-reserve funding-source)
                              (not= :authority-attested evidence-class)
                              (not= source-root (:funding/source-root source-evidence))
                              (not (:valid? (funding-evidence/verify-source
                                             source-evidence funding-trust-policy
                                             {:funding/source-kind :declared-reserve
                                              :funding/account funding-account
                                              :funding/token funding-token
                                              :funding/operation-root funding-operation-root
                                              :funding/beneficiary (:payable/beneficiary payable)
                                              :funding/parameter-context-root funding-context-root
                                              :funding/parameter-address funding-address})))
                              (not (and (integer? available-before)
                                        (integer? reserved-amount)
                                        (integer? available-after)
                                        (not (neg? available-after))
                                        (= (:backing/amount backing) reserved-amount)
                                        (= available-after
                                           (- available-before reserved-amount))))
                              (not= (:backing/distribution-root backing)
                                    funding-operation-root)
                              (nil? adjustment-id)
                              (not= artifact-hash (:artifact/hash artifact))
                              (not (custody-artifact-valid? world adjustment))
                              (not= funding-token (:token adjustment))
                              (not= :in (:held/direction adjustment))
                              (not= funding-account account)
                              (not= {funding-account (:backing/amount backing)}
                                    (:backing/source-allocations backing))
                              (not= account (:held/account adjustment))
                              (not= funding-context-root adjustment-context-root)
                              (not= funding-address adjustment-address)
                              (not= (:backing/amount backing) (:amount adjustment)))))
                      backings))
        reserve-usage-errors
        (into []
              (keep (fn [[source-key source-backings]]
                      (let [contexts (map :backing/context source-backings)
                            balances (set (map :funding/available-before contexts))
                            available (first balances)
                            reserved (reduce + 0 (map :backing/amount source-backings))]
                        (when (or (not= 1 (count balances))
                                  (not (integer? available))
                                  (> reserved available))
                          {:source source-key
                           :available-balances balances
                           :reserved reserved}))))
              (group-by (fn [backing]
                          (select-keys (:backing/context backing)
                                       [:funding/source :funding/account :funding/token
                                        :funding/parameter-context-root
                                        :funding/parameter-address]))
                        backings))
        invalid-lifecycles
        (into []
              (filter (fn [p]
                        (let [matching (filter #(= (:payable/hash p)
                                                   (:backing/payable-root %)) backings)
                              backing (first matching)
                              claimable (get-in world [:claimable-v2 (:payable/id p)
                                                       domain (:payable/beneficiary p)] 0)]
                          (or (not= 1 (count matching))
                              (case (:payable/lifecycle p)
                                :pending-backing (or (pos? claimable) (some? backing))
                              :backed (or (not= :committed (:backing/lifecycle backing))
                                          (not (:valid? (funding-evidence/verify-source
                                                         (:funding/source-evidence
                                                          (:backing/context backing))
                                                         funding-trust-policy
                                                         {:funding/source-kind :declared-reserve
                                                          :funding/account (get-in backing [:backing/context :funding/account])
                                                          :funding/token (get-in backing [:backing/context :funding/token])
                                                          :funding/operation-root (:payable/distribution-root p)
                                                          :funding/beneficiary (:payable/beneficiary p)
                                                          :funding/parameter-context-root
                                                          (get-in backing [:backing/context :funding/parameter-context-root])
                                                          :funding/parameter-address
                                                          (get-in backing [:backing/context :funding/parameter-address])})))
                                            (not= (:payable/amount p) claimable))
                                :settled (or (not= :consumed (:backing/lifecycle backing))
                                             (pos? claimable)
                                             (not (payout-custody-valid? world p backing))
                                             (not (:valid? (payout-evidence/verify-settled-payout
                                                            p backing
                                                            (get-in world [:with-bounty/payout-events
                                                                           (:payable/id p)] [])
                                                            payout-trust-policy)))
                                             (not (payout-settlement-valid?
                                                   world p backing payout-trust-policy
                                                   observation-trust-policy
                                                   expected-settlement-context
                                                   require-settlement-evidence?
                                                   rpc-client expected-rpc-context
                                                   require-rpc-observation?)))
                                ;; Cancellation/release also lacks a recorded
                                ;; lifecycle transition artifact.
                                :cancelled true
                                true))))
              payables))
        violations (cond-> []
                     (seq invalid-payables)
                     (conj {:violation/id :violation/invalid-payable-artifacts
                            :details {:payables invalid-payables}})

                     (seq invalid-backings)
                     (conj {:violation/id :violation/invalid-backing-artifacts
                            :details {:backings invalid-backings}})

                     (seq invalid-custody)
                     (conj {:violation/id :violation/invalid-custody-artifacts
                            :details {:adjustments invalid-custody}})

                     (seq unbacked)
                     (conj {:violation/id :violation/payable-without-backing
                            :details {:payable-roots unbacked}})

                     (seq orphan-backings)
                     (conj {:violation/id :violation/backing-without-payable
                            :details {:backings orphan-backings}})

                     (seq inconsistent-backings)
                     (conj {:violation/id :violation/backing-payable-reconciliation-failed
                            :details {:backings inconsistent-backings}})

                     (seq duplicate-backing-roots)
                     (conj {:violation/id :violation/duplicate-payable-backing
                            :details {:payable-roots duplicate-backing-roots}})

                     (seq backing-reservation-evidence-errors)
                     (conj {:violation/id :violation/backing-without-reservation-evidence
                            :details {:backings backing-reservation-evidence-errors}})

                     (seq reserve-usage-errors)
                     (conj {:violation/id :violation/bounty-reserve-overcommitted
                            :details {:sources reserve-usage-errors}})

                     (seq invalid-lifecycles)
                     (conj {:violation/id :violation/payable-lifecycle-inconsistent
                            :details {:payables invalid-lifecycles}}))]
    (if (seq violations)
      {:valid? false :violations violations}
      {:valid? true})))

(defn reconcile-transition-artifacts-with-world
  "Membership reconciliation only: every custody adjustment root in transition
   evidence resolves to a world artifact, and every referenced payable/backing
   root exists in the world. This does not prove a state transition; it does not
   replay the protocol transition."
  [transition world]
  (let [custody-roots (:custody/adjustment-roots transition [])
        unbound-artifacts (into []
                                (keep (fn [c]
                                        (let [id (:held-adjustment/id c)
                                              artifact (get-in world [:held-artifacts id])]
                                          (when (not= (:artifact/hash c)
                                                      (:artifact/hash artifact))
                                            c)))
                                      custody-roots))
        payable-roots (set (:payable/roots transition []))
        backing-roots (set (:backing/roots transition []))
        world-payable-roots (set (map :payable/hash
                                      (vals (:with-bounty/payables world {}))))
        world-backing-roots (set (map :backing/hash
                                      (vals (:with-bounty/backings world {}))))
        missing-payables (seq (remove world-payable-roots payable-roots))
        missing-backings (seq (remove world-backing-roots backing-roots))
        violations (cond-> []
                     (seq unbound-artifacts)
                     (conj {:violation/id :violation/custody-artifact-not-bound
                            :details {:unbound unbound-artifacts}})

                     missing-payables
                     (conj {:violation/id :violation/payable-root-not-in-world
                            :details {:roots (vec missing-payables)}})

                     missing-backings
                     (conj {:violation/id :violation/backing-root-not-in-world
                            :details {:roots (vec missing-backings)}}))]
    (if (seq violations)
      {:valid? false :violations violations}
      {:valid? true})))
