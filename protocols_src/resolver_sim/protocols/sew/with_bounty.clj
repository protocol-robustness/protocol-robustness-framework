(ns resolver-sim.protocols.sew.with-bounty
  "Sew reference adapter for the generic with-bounty composition (ADR-0005
   Phase 6; ADR-0006 D5).

   Applies a validated with-bounty application plan to a Sew world through the
   canonical paths only:
   - :prf.effect/custody-held-adjustment.v1 / .v2 → accounting/add-held or
     sub-held (canonical held-custody mutation, direction-bound by the
     effect's canonical held action; the generic compositor never mutates
     custody);
   - :prf.effect/obligation-create.v2        → bounty-payable, bounty-payable-
     backing, and claimable records.

   Invariants enforced here:
   - fail-before-mutation: preflight (plan verification, effect schema validity,
     adapter support, committed-adapter match, funding preconditions) precedes
     any mutation;
   - atomicity: application is a pure reduction, so no partial state is ever
     observable if any step fails;
   - no-duplicate-creation: at most one live obligation per the plan's
     no-duplicate-creation key (operation-root, bounty-id, recipient);
   - idempotent replay: a retry for a known plan root only returns success when
     the expected payable, backing, claimable, and custody state are actually
     present; drifted state fails rather than falsely succeeding;
   - claimability derives from the successfully created, backed payable.

   Resulting available-actions derive from the resulting Sew world state, not
   from direct extension injection."
  (:require [resolver-sim.assurance.custody :as custody]
            [resolver-sim.economics.bounty-payable :as bp]
            [resolver-sim.economics.bounty-payable-backing :as bpb]
            [resolver-sim.economics.effects :as effects]
            [resolver-sim.economics.with-bounty.application-plan :as wb-plan]
            [resolver-sim.economics.with-bounty.funding-evidence :as funding-evidence]
            [resolver-sim.economics.with-bounty.obligation-settlement :as obligation-settlement]
            [resolver-sim.economics.with-bounty.payout-evidence :as payout-evidence]
            [resolver-sim.economics.with-bounty.transition-evidence :as wb-transition]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.protocols.sew.accounting :as act]
            [resolver-sim.protocols.sew.held-mutation-admission :as held-admission]))

(def adapter-held-actions
  "The held-custody ACTIONS this adapter is entitled to execute (its producer
   capability). Global protocol vocabulary support
   (effects/supported-held-action?) does NOT entitle an adapter to every action:
   with-bounty reserves and releases custody through the accounting primitives,
   so it may only execute add-held / sub-held. A crafted effect carrying a
   globally valid but locally unsupported action (e.g. \"refund-held\" or
   \"finalize-released\") is rejected — it cannot acquire a capability merely
   because the action exists elsewhere in the custody protocol."
  #{"add-held" "sub-held"})

(def adapter-support
  "The adapter-support declaration the with-bounty plans for this adapter are
   validated against and committed under (:plan/adapter)."
  {:adapter/id :sew/v1
   :adapter/supported-effects
   #{:prf.effect/obligation-create.v2
     :prf.effect/custody-held-adjustment.v1
     :prf.effect/custody-held-adjustment.v2}
   :adapter/held-actions adapter-held-actions})

(def claimable-domain
  "Domain under which a with-bounty payable becomes claimable."
  :liability/bounty-payable)

(defn- canonical-address
  "String address form of an identity keyword/string without a leading colon."
  [v]
  (if (keyword? v)
    (subs (str v) 1)
    (str v)))

(defn- obligation-effect
  [plan]
  (first (filter #(= :prf.effect/obligation-create.v2 (:effect/contract %))
                 (:plan/effects plan []))))

(defn- custody-effects
  [plan]
  (filterv #(contains? #{:prf.effect/custody-held-adjustment.v1
                         :prf.effect/custody-held-adjustment.v2}
                       (:effect/contract %))
           (:plan/effects plan [])))

(defn- custody-action-violations
  "Fail-before-mutation check: every custody effect's held action must be in
   this adapter's allowed-action subset (adapter-held-actions). A globally
   valid held action the adapter cannot execute is a violation, so a crafted
   v2 effect cannot acquire adapter capability merely because the action exists
   in the custody protocol."
  [effects]
  (into []
        (keep (fn [effect]
                (when (= :custody/held-adjustment (:effect/type effect))
                  (let [action (effects/effect-action effect)]
                    (when-not (contains? adapter-held-actions action)
                      {:violation/id :violation/unsupported-custody-action-for-adapter
                       :details {:action action
                                 :effect/contract (:effect/contract effect)
                                 :adapter/held-actions (vec (sort adapter-held-actions))}})))))
        effects))

;; ── preflight (fail-before-mutation) ─────────────────────────────────────

(defn preflight
  "Validate a with-bounty application plan before any mutation: plan
   verification (including recomputation of committed derived fields), effect
   schema validity, adapter support, committed-adapter match, and creation-time
   funding preconditions. Idempotency and no-duplicate-creation are resolved by
   apply-with-bounty-plan, not here."
  ([plan] (preflight plan nil))
  ([plan funding-trust-policy]
  (let [plan-verify (wb-plan/verify-with-bounty-plan plan)
        effect-check (effects/validate-effects-for-adapter
                      adapter-support (:plan/effects plan []))
        adapter (:plan/adapter plan)
        obligation (first (filter #(= :prf.effect/obligation-create.v2
                                      (:effect/contract %))
                                  (:plan/effects plan [])))
        custody (first (custody-effects plan))
        source-verification
        (funding-evidence/verify-source
         (get-in plan [:plan/context :funding/source-evidence]) funding-trust-policy
         {:funding/source-kind :declared-reserve
          :funding/account (:effect/account custody)
          :funding/token (:obligation/token obligation)
          :funding/operation-root (get-in obligation [:obligation/subject :operation-root])
          :funding/beneficiary (canonical-address (:obligation/owner obligation))
          :funding/parameter-context-root (get-in obligation [:obligation/funding
                                                               :parameter-context-root])
          :funding/parameter-address (get-in obligation [:obligation/funding
                                                         :parameter-address])})
        errors (cond-> []
                 (not (:valid? plan-verify))
                 (into (map (fn [e]
                              {:violation/id :violation/with-bounty-plan-invalid
                               :details {:error e}}))
                           (:errors plan-verify))

                 (nil? adapter)
                 (conj {:violation/id :violation/with-bounty-adapter-not-committed
                        :details {}})

                 (and (map? adapter) (not= adapter adapter-support))
                 (conj {:violation/id :violation/with-bounty-adapter-mismatch
                        :details {:committed adapter
                                  :expected adapter-support}})

                 (not (:valid? effect-check))
                 (into (:violations effect-check))

                 (seq (custody-action-violations (:plan/effects plan [])))
                 (into (custody-action-violations (:plan/effects plan [])))

                 (false? (get-in plan [:plan/preconditions :funding/available?]))
                 (conj {:violation/id :violation/insufficient-bounty-funding
                        :details {:precondition :funding/available?}})

                 (not (:valid? source-verification))
                 (conj {:violation/id :violation/bounty-funding-evidence-invalid
                        :details {:reason (:reason source-verification)}}))]
    (if (seq errors)
      {:valid? false :violations (vec errors)}
      {:valid? true}))))

;; ── effect application (canonical paths only) ─────────────────────────────

(defn- apply-custody-effect
  "Reserve (or release) bounty custody through the canonical add-held/sub-held
   paths. The action is the effect's CANONICAL held action (explicit on v2,
   interpreted under the legacy rule on v1) — the adapter never invents or
   overrides the action. Only actions in this adapter's allowed subset
   (adapter-held-actions) are applied; a globally valid but locally unsupported
   action fails closed before any mutation."
  [world effect]
  (let [action (effects/effect-action effect)
        token (:effect/token effect)
        amount (:effect/amount effect)]
    (when-not (contains? adapter-held-actions action)
      (throw (ex-info "with-bounty: custody action not in adapter's allowed subset"
                      {:violation/id :violation/unsupported-custody-action-for-adapter
                       :action action
                       :effect/contract (:effect/contract effect)
                       :adapter/held-actions (vec (sort adapter-held-actions))})))
    (let [opts (-> (effects/custody-effect->add-held-opts effect)
                   (update :extra merge
                           {:held/account (:effect/account effect)}
                           (when (:owner/address effect)
                             {:owner/address (:owner/address effect)})))]
      (case action
        "add-held" (held-admission/admit-and-add-held! world token amount opts
                                                       {:operation-id :sew/bounty-custody-reserve})
        "sub-held" (act/sub-held world token amount opts)))))

(declare custody-reservation-matches?)

(defn- custody-artifact-binding
  "Exact held-adjustment binding for transition evidence: the adjustment id and
   the content-addressed artifact hash, not merely the amount or account."
  [world]
  (let [adjustment (last (:held-adjustments world))
        id (:held-adjustment/id adjustment)
        artifact (get-in world [:held-artifacts id])]
    {:held-adjustment/id id
     :artifact/hash (:artifact/hash artifact)}))

(defn- reserve-source-allocations
  "Source allocations for the payable backing, derived only from the required
   custody reservation effect. Preflight rejects plans without that evidence."
  [plan effect]
  (let [custody (first (custody-effects plan))
        account (:effect/account custody)]
    {account (:obligation/amount effect)}))

(defn- apply-obligation-effect
  "Create the Sew bounty-payable, its backing, and the claimable record.
   Claimability derives from the created, backed payable — it is not written
   independently."
  [world plan effect]
  (let [obligation-id (:obligation/id effect)
        amount (:obligation/amount effect)
        dist-root (:plan/base-operation-root plan)
        payable (bp/build-bounty-payable
                 {:payable/id obligation-id
                  :distribution-root dist-root
                  :beneficiary (canonical-address (:obligation/owner effect))
                  :amount amount
                  :kind (:obligation/type effect)
                  :lifecycle :pending-backing})
        backed-payable (bp/transition-payable-lifecycle payable :backed)
        reservation-effect (first (custody-effects plan))
        reservation (last (filter #(custody-reservation-matches?
                                    world reservation-effect %)
                                  (:held-adjustments world [])))
        reservation-artifact (get-in world [:held-artifacts
                                            (:held-adjustment/id reservation)])
        _ (when-not (and reservation reservation-artifact)
            (throw (ex-info "with-bounty: backing requires an applied custody reservation"
                            {:violation/id :violation/bounty-reservation-evidence-missing
                             :obligation/id obligation-id})))
        backing (bpb/build-bounty-payable-backing
                 {:payable-root (:payable/hash backed-payable)
                  :payable-id (:payable/id payable)
                  :distribution-root dist-root
                  :amount amount
                  :source-allocations (reserve-source-allocations plan effect)
                  :kind :funding-deduction-restricted
                  :context {:funding/source :declared-reserve
                            :funding/token (:obligation/token effect)
                            :funding/account (:effect/account reservation-effect)
                            :funding/operation-root dist-root
                            :funding/parameter-context-root
                            (get-in effect [:obligation/funding :parameter-context-root])
                            :funding/parameter-address
                            (get-in effect [:obligation/funding :parameter-address])
                            :funding/available-before (:plan/funding-available plan)
                            :funding/reserved-amount amount
                            :funding/available-after (- (:plan/funding-available plan) amount)
                            :funding/evidence-class :authority-attested
                            :funding/source-evidence
                            (get-in plan [:plan/context :funding/source-evidence])
                            :funding/source-root
                            (get-in plan [:plan/context :funding/source-evidence
                                          :funding/source-root])
                            :custody/adjustment-id (:held-adjustment/id reservation)
                            :custody/artifact-hash (:artifact/hash reservation-artifact)}})
        ;; The adapter creates the claimable accounting entry only after the
        ;; reservation effect has been applied and a backing artifact exists.
        world' (-> world
                   (act/record-claimable-v2 obligation-id claimable-domain
                                            (:payable/beneficiary backed-payable)
                                            (:payable/amount backed-payable))
                   (assoc-in [:with-bounty/payables (:payable/id backed-payable)] backed-payable)
                   (assoc-in [:with-bounty/backings (:backing/id backing)] backing))]
    {:world world' :payable backed-payable :backing backing}))

;; ── idempotent replay state verification ─────────────────────────────────

(defn- custody-reservation-matches?
  "True when an adjustment and its stored custody artifact are valid and the
   adjustment has the exact plan-derived reservation fields."
  [world effect adjustment]
  (let [id (:held-adjustment/id adjustment)
        artifact (get-in world [:held-artifacts id])
        expected-artifact (try
                            (custody/build-held-custody-artifact adjustment)
                            (catch Exception _ nil))]
    (and (= {:held/direction (effects/effect-held-direction effect)
             :token (:effect/token effect)
             :amount (:effect/amount effect)
             :held/action (effects/effect-action effect)
             :held/account (:effect/account effect)
             :held/reason (:held/kind effect)
             :owner/address (:owner/address effect)
             :parameter/context (:parameter/context effect)
             :parameter/address (:parameter/address effect)}
            (select-keys adjustment
                         [:held/direction :token :amount :held/action
                          :held/account :held/reason :owner/address
                          :parameter/context :parameter/address]))
         (= id (:held-adjustment/id artifact))
         (= expected-artifact artifact))))

(defn- custody-reservations-complete?
  "Match planned custody effects one-for-one with exact, artifact-backed held
   adjustments. Consuming a match prevents one reservation from satisfying two
   identical planned effects."
  [world custody-effects]
  (loop [effects custody-effects
         available (:held-adjustments world [])]
    (if-let [effect (first effects)]
      (let [[before matches] (split-with #(not (custody-reservation-matches?
                                                world effect %))
                                         available)]
        (if (first matches)
          (recur (rest effects) (into (vec before) (rest matches)))
          false))
      true)))

(defn- applied-state-complete?
  "True only when the idempotent world contains the exact payable and backing
   derived from this plan, verified through their existing artifact verifiers,
   plus the derived claimable and artifact-backed custody reservations."
  [world plan]
  (let [obligation (obligation-effect plan)
        obligation-id (:plan/obligation-id plan)
        amount (:obligation/amount obligation)
        beneficiary (canonical-address (:obligation/owner obligation))
        expected-payable (bp/build-bounty-payable
                          {:payable/id obligation-id
                           :distribution-root (:plan/base-operation-root plan)
                           :beneficiary beneficiary
                           :amount amount
                           :kind (:obligation/type obligation)
                           :lifecycle :pending-backing})
        expected-payable (bp/transition-payable-lifecycle expected-payable :backed)
        expected-backing (bpb/build-bounty-payable-backing
                          {:payable-root (:payable/hash expected-payable)
                           :payable-id obligation-id
                           :distribution-root (:plan/base-operation-root plan)
                           :amount amount
                           :source-allocations (reserve-source-allocations plan obligation)
                           :kind :funding-deduction-restricted
                           :context (let [reservation (last (filter #(custody-reservation-matches?
                                                                       world
                                                                       (first (custody-effects plan))
                                                                       %)
                                                                     (:held-adjustments world [])))
                                         artifact (get-in world [:held-artifacts
                                                                 (:held-adjustment/id reservation)])]
                                     {:funding/source :declared-reserve
                                      :funding/token (:obligation/token obligation)
                                      :funding/account (:effect/account
                                                        (first (custody-effects plan)))
                                      :funding/operation-root
                                      (:plan/base-operation-root plan)
                                      :funding/parameter-context-root
                                      (get-in obligation [:obligation/funding
                                                          :parameter-context-root])
                                      :funding/parameter-address
                                      (get-in obligation [:obligation/funding
                                                          :parameter-address])
                                      :funding/available-before
                                      (:plan/funding-available plan)
                                      :funding/reserved-amount amount
                                      :funding/available-after
                                      (- (:plan/funding-available plan) amount)
                                      :funding/evidence-class :authority-attested
                                      :funding/source-evidence
                                      (get-in plan [:plan/context :funding/source-evidence])
                                      :funding/source-root
                                      (get-in plan [:plan/context :funding/source-evidence
                                                    :funding/source-root])
                                      :custody/adjustment-id (:held-adjustment/id reservation)
                                      :custody/artifact-hash (:artifact/hash artifact)})})
        payable (get-in world [:with-bounty/payables obligation-id])
        backing (get-in world [:with-bounty/backings (:backing/id expected-backing)])]
    (and (= expected-payable payable)
         (:valid? (bp/verify-bounty-payable payable))
         (= expected-backing backing)
         (:valid? (bpb/verify-bounty-payable-backing backing))
         (= amount (get-in world [:claimable-v2 obligation-id
                                  claimable-domain beneficiary]))
         (custody-reservations-complete? world (custody-effects plan)))))

;; ── application ───────────────────────────────────────────────────────────

(defn- world-root
  [world]
  (hc/hash-with-intent {:hash/intent :world-structure} world))

(defn- transition-evidence
  [{:keys [plan world-before world-after payables backings
           custody-adjustment-roots idempotent?]}]
  (wb-transition/build-transition-evidence
   {:plan plan
    :effect-root (first (:plan/effect-roots plan))
    :world-before-root (world-root world-before)
    :world-after-root (world-root world-after)
    :payable-roots (mapv :payable/hash payables)
    :backing-roots (mapv :backing/hash backings)
    :custody-adjustment-roots custody-adjustment-roots
    :idempotent? idempotent?}))

(defn- reserve-usage
  "Reconstruct prior reservations for the signed declared-reserve source scope.
   This prevents two plans from each spending the full attested balance. The
   attestation authenticates the authority's assertion, not live chain custody."
  [plan world]
  (let [obligation (obligation-effect plan)
        funding (:obligation/funding obligation)
        custody (first (custody-effects plan))
        key (select-keys {:funding/source (:source funding)
                          :funding/account (:effect/account custody)
                          :funding/token (:obligation/token obligation)
                          :funding/parameter-context-root
                          (:parameter-context-root funding)
                          :funding/parameter-address (:parameter-address funding)}
                         [:funding/source :funding/account :funding/token
                          :funding/parameter-context-root :funding/parameter-address])
        used (reduce + 0
                     (for [backing (vals (:with-bounty/backings world {}))
                           :let [context (:backing/context backing)]
                           :when (= key (select-keys context (keys key)))]
                       (:backing/amount backing)))
        available (:plan/funding-available plan)
        requested (:obligation/amount obligation)]
    {:used used
     :available available
     :requested requested
     :available? (and (integer? available)
                      (<= (+ used requested) available))}))

(defn apply-with-bounty-plan
  "Apply a validated with-bounty application plan to a Sew world.

   Preflight validation precedes any mutation (fail-before-mutation).
   Semantics by existing world state:

   - the plan's idempotency key already holds this plan root → no-op, but only
     after verifying the expected payable/backing/claimable/custody state is
     actually present (drifted state fails);
   - the idempotency key holds a different plan root for the same obligation →
     conflicting application fails atomically;
   - a different live obligation exists under the same no-duplicate-creation key
     → duplicate creation fails atomically;
   - otherwise the effects are applied atomically (a pure reduction) and the
     idempotency key and live-obligation index are committed together.

   Returns {:world <updated-world> :idempotent? bool :transition <evidence> ...}."
  ([plan world] (apply-with-bounty-plan plan world nil))
  ([plan world funding-trust-policy]
  (let [preflight-result (preflight plan funding-trust-policy)]
    (when-not (:valid? preflight-result)
      (throw (ex-info "with-bounty: preflight failed before mutation"
                      {:violation/id :violation/with-bounty-preflight-failed
                       :violations (:violations preflight-result)})))
    (let [idem-key (:plan/idempotency-key plan)
          stored-hash (get-in world idem-key)
          dup-key (:plan/no-duplicate-creation-key plan)
          live (when dup-key (get-in world [:with-bounty/live-obligations dup-key]))
          obligation-id (:plan/obligation-id plan)]
      (cond
        (and (some? stored-hash) (= stored-hash (:plan/hash plan)))
        (if (applied-state-complete? world plan)
          {:world world :idempotent? true
           :payables [] :backings [] :custody-adjustment-roots []
           :transition (transition-evidence
                        {:plan plan :world-before world :world-after world
                         :payables [] :backings [] :custody-adjustment-roots []
                         :idempotent? true})}
          (throw (ex-info "with-bounty: idempotent replay found drifted state"
                          {:violation/id :violation/with-bounty-state-drift
                           :plan/root (:plan/hash plan)
                           :obligation/id obligation-id})))

        (some? stored-hash)
        (throw (ex-info "with-bounty: conflicting application for the same obligation"
                        {:violation/id :violation/conflicting-bounty-application
                         :stored stored-hash :expected (:plan/hash plan)}))

        (and (some? live) (not= live obligation-id))
        (throw (ex-info "with-bounty: duplicate obligation for the same no-duplicate key"
                        {:violation/id :violation/duplicate-bounty-creation
                         :no-duplicate-creation-key dup-key
                         :live live :incoming obligation-id}))

        (some? live)
        (throw (ex-info "with-bounty: corrupt live-obligation index"
                        {:violation/id :violation/with-bounty-corrupt-state
                         :no-duplicate-creation-key dup-key
                         :live live :obligation/id obligation-id}))

        (not (:available? (reserve-usage plan world)))
        (let [{:keys [used available requested]} (reserve-usage plan world)]
          (throw (ex-info "with-bounty: declared reserve would be overcommitted"
                          {:violation/id :violation/bounty-reserve-overcommitted
                           :used used :available available :requested requested})))

        :else
        (let [world-before world
              {:keys [world payables backings custody-adjustment-roots]}
              (reduce (fn [{:keys [world payables backings custody-adjustment-roots] :as acc}
                           effect]
                        (case (:effect/type effect)
                          :custody/held-adjustment
                          (let [world' (apply-custody-effect world effect)]
                            (assoc acc
                                   :world world'
                                   :custody-adjustment-roots
                                   (conj custody-adjustment-roots
                                         (custody-artifact-binding world'))))

                          :obligation/create
                          (let [{:keys [world payable backing]}
                                (apply-obligation-effect world plan effect)]
                            (assoc acc
                                   :world world
                                   :payables (conj payables payable)
                                   :backings (conj backings backing)))

                          acc))
                      {:world world-before
                       :payables [] :backings [] :custody-adjustment-roots []}
                      (sort-by #(if (= :custody/held-adjustment (:effect/type %)) 0 1)
                               (:plan/effects plan [])))
              world' (-> world
                         (assoc-in idem-key (:plan/hash plan))
                         (assoc-in [:with-bounty/live-obligations dup-key]
                                   obligation-id))
              transition (transition-evidence
                          {:plan plan
                           :world-before world-before
                           :world-after world'
                           :payables payables
                           :backings backings
                           :custody-adjustment-roots custody-adjustment-roots
                           :idempotent? false})]
          {:world world' :idempotent? false
           :payables payables :backings backings
           :custody-adjustment-roots custody-adjustment-roots
           :transition transition}))))))

(defn apply-verified-direct-payout
  "Apply a trusted authority's receipt for a completed direct bounty payment.

   This does not move funds. It only records an externally executed payment
   after verifying a signed receipt against the supplied trust policy. V1 is
   full-payment only; partial receipts and replays fail closed."
  [world payable-id receipt trust-policy]
  (let [payable (get-in world [:with-bounty/payables payable-id])
        backing (some #(when (= (:payable/hash payable) (:backing/payable-root %)) %)
                      (vals (:with-bounty/backings world {})))
        beneficiary (:payable/beneficiary payable)
        amount (:payable/amount payable)
        token (get-in backing [:backing/context :funding/token])
        operation-root (get-in backing [:backing/context :funding/operation-root])
        claimable (get-in world [:claimable-v2 payable-id claimable-domain beneficiary] 0)
        history (get-in world [:with-bounty/payout-events payable-id] [])
        successor (when (= :backed (:payable/lifecycle payable))
                    (let [p (assoc payable :payable/lifecycle :settled)]
                      (assoc p :payable/hash (bp/payable-hash p))))
        verification (payout-evidence/verify-payout
                      receipt trust-policy
                      {:payout/sequence (inc (count history))
                       :payout/payable-id payable-id
                       :payout/predecessor-root (:payable/hash payable)
                       :payout/successor-root (:payable/hash successor)
                       :payout/backing-root (:backing/hash backing)
                       :payout/beneficiary beneficiary
                       :payout/token token
                       :payout/amount amount
                       :payout/operation-root operation-root})]
    (when-not (and payable backing successor
                   (= :committed (:backing/lifecycle backing))
                   (= amount (:backing/amount backing))
                   (= amount claimable)
                   (not-any? #(= (:payout/id receipt) (:payout/id %)) history)
                   (:valid? verification))
      (throw (ex-info "with-bounty: direct payout receipt rejected"
                      {:violation/id :violation/bounty-direct-payout-rejected
                       :reason (or (:reason verification)
                                   (cond
                                     (nil? payable) :payable-not-found
                                     (not= :backed (:payable/lifecycle payable)) :payable-not-backed
                                     (not= amount claimable) :claimable-amount-mismatch
                                     :else :backing-or-replay-mismatch))})))
    (let [world-debited (held-admission/admit-and-sub-held!
                         world token amount
                         {:action "bounty-direct-payout"
                          :reason :bounty-direct-payout
                          :parameter/context
                          {:parameter-context/type :protocol-parameters
                           :parameter-context/root
                           (get-in backing [:backing/context :funding/parameter-context-root])
                           :parameter-context/version 1}
                          :parameter/address
                          {:parameter/path
                           (get-in backing [:backing/context :funding/parameter-address])}
                          :extra {:held/account
                                  (get-in backing [:backing/context :funding/account])
                                  :owner/address beneficiary
                                  :bounty/payable-id payable-id
                                  :payout/evidence-root (:event-root verification)}}
                         {:operation-id :sew/bounty-direct-payout})
          payout-adjustment (last (:held-adjustments world-debited))
          payout-artifact (get-in world-debited [:held-artifacts
                                                  (:held-adjustment/id payout-adjustment)])
          settled-context (assoc (:backing/context backing)
                                 :payout/evidence-root (:event-root verification)
                                 :payout/predecessor-root (:payable/hash payable)
                                 :payout/backing-root (:backing/hash backing)
                                 :payout/custody-adjustment-id
                                 (:held-adjustment/id payout-adjustment)
                                 :payout/custody-artifact-hash
                                 (:artifact/hash payout-artifact))
          settled-backing (-> backing
                              (assoc :backing/lifecycle :consumed
                                     :backing/payable-root (:payable/hash successor)
                                     :backing/context settled-context)
                              (assoc :backing/hash
                                     (bpb/backing-hash
                                      (assoc backing :backing/lifecycle :consumed
                                             :backing/payable-root (:payable/hash successor)
                                             :backing/context settled-context))))
          world' (-> world-debited
                     (assoc-in [:with-bounty/payables payable-id] successor)
                     (assoc-in [:with-bounty/backings (:backing/id backing)] settled-backing)
                     (assoc-in [:claimable-v2 payable-id claimable-domain beneficiary] 0)
                     (update-in [:with-bounty/payout-events payable-id] (fnil conj []) receipt))]
      {:world world' :payable successor :backing settled-backing
       :payout-event receipt :verification verification})))

(defn apply-observed-direct-payout
  "Apply a full direct payment only when a canonical settlement can be built
   from the signed payout receipt and a separately trusted chain observation.
   The observation trust policy defines whether the evidence is controlled
   execution, included, or finalized; this adapter does not query a chain."
  [world payable-id receipt payout-trust-policy observation
   observation-trust-policy expected-context]
  (let [payable (get-in world [:with-bounty/payables payable-id])
        backing (some #(when (= (:payable/hash payable) (:backing/payable-root %)) %)
                      (vals (:with-bounty/backings world {})))
        successor (bp/transition-payable-lifecycle payable :settled)
        settlement (obligation-settlement/build-settlement
                    {:payable-before payable
                     :payable-after successor
                     :backing backing
                     :payout-receipt receipt
                     :payout-trust-policy payout-trust-policy
                     :observation observation
                     :observation-trust-policy observation-trust-policy
                     :expected-context expected-context})
        result (apply-verified-direct-payout world payable-id receipt
                                             payout-trust-policy)
        world' (assoc-in (:world result)
                         [:with-bounty/settlements payable-id]
                         {:settlement settlement
                          :observation observation
                          :payout-receipt receipt})]
    (assoc result :world world' :settlement settlement)))
