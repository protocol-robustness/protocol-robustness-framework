(ns resolver-sim.protocols.sew.with-bounty
  "Sew reference adapter for the generic with-bounty composition (ADR-0005
   Phase 6; ADR-0006 D5).

   Applies a validated with-bounty application plan to a Sew world through the
   canonical paths only:
   - :prf.effect/custody-held-adjustment.v1 → accounting/add-held (canonical
     held-custody mutation; the generic compositor never mutates custody);
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
  (:require [resolver-sim.economics.bounty-payable :as bp]
            [resolver-sim.economics.bounty-payable-backing :as bpb]
            [resolver-sim.economics.effects :as effects]
            [resolver-sim.economics.with-bounty.application-plan :as wb-plan]
            [resolver-sim.economics.with-bounty.transition-evidence :as wb-transition]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.protocols.sew.accounting :as act]))

(def adapter-support
  "The adapter-support declaration the with-bounty plans for this adapter are
   validated against and committed under (:plan/adapter)."
  {:adapter/id :sew/v1
   :adapter/supported-effects
   #{:prf.effect/obligation-create.v2
     :prf.effect/custody-held-adjustment.v1}})

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
  (filterv #(= :prf.effect/custody-held-adjustment.v1 (:effect/contract %))
           (:plan/effects plan [])))

;; ── preflight (fail-before-mutation) ─────────────────────────────────────

(defn preflight
  "Validate a with-bounty application plan before any mutation: plan
   verification (including recomputation of committed derived fields), effect
   schema validity, adapter support, committed-adapter match, and creation-time
   funding preconditions. Idempotency and no-duplicate-creation are resolved by
   apply-with-bounty-plan, not here."
  [plan]
  (let [plan-verify (wb-plan/verify-with-bounty-plan plan)
        effect-check (effects/validate-effects-for-adapter
                      adapter-support (:plan/effects plan []))
        adapter (:plan/adapter plan)
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

                 (false? (get-in plan [:plan/preconditions :funding/available?]))
                 (conj {:violation/id :violation/insufficient-bounty-funding
                        :details {:precondition :funding/available?}}))]
    (if (seq errors)
      {:valid? false :violations (vec errors)}
      {:valid? true})))

;; ── effect application (canonical paths only) ─────────────────────────────

(defn- apply-custody-effect
  "Reserve bounty funding custody through the canonical add-held path. Returns
   the updated world; the appended held-adjustment record and its content-
   addressed artifact carry the evidence."
  [world effect]
  (let [token (:effect/token effect)
        amount (:effect/amount effect)
        opts (-> (effects/custody-effect->add-held-opts effect)
                 (update :extra merge
                         {:held/account (:effect/account effect)}
                         (when (:owner/address effect)
                           {:owner/address (:owner/address effect)})))]
    (act/add-held world token amount opts)))

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
  "Source allocations for the payable backing: the custody account the reserve
   reservation targets, if present, else the default bounty-reserve account."
  [plan effect]
  (let [custody (first (custody-effects plan))
        account (or (:effect/account custody) :bounty-reserve)]
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
        backing (bpb/build-bounty-payable-backing
                 {:payable-root (:payable/hash payable)
                  :payable-id (:payable/id payable)
                  :distribution-root dist-root
                  :amount amount
                  :source-allocations (reserve-source-allocations plan effect)
                  :kind :funding-deduction-restricted})
        world' (-> world
                   (act/record-claimable-v2 obligation-id claimable-domain
                                            (:payable/beneficiary payable)
                                            (:payable/amount payable))
                   (assoc-in [:with-bounty/payables (:payable/id payable)] payable)
                   (assoc-in [:with-bounty/backings (:backing/id backing)] backing))]
    {:world world' :payable payable :backing backing}))

;; ── idempotent replay state verification ─────────────────────────────────

(defn- applied-state-complete?
  "True when the world actually carries the state a successful application of
   this plan would have produced: the payable, its backing, the derived
   claimable amount, and the custody reservations."
  [world plan]
  (let [obligation (obligation-effect plan)
        obligation-id (:plan/obligation-id plan)
        amount (:obligation/amount obligation)
        beneficiary (canonical-address (:obligation/owner obligation))
        payable (get-in world [:with-bounty/payables obligation-id])
        backing (when payable
                  (some #(= (:payable/hash payable) (:backing/payable-root %))
                        (vals (:with-bounty/backings world {}))))
        custody-count (count (custody-effects plan))]
    (and payable
         backing
         (= amount (get-in world [:claimable-v2 obligation-id
                                  claimable-domain beneficiary]))
         (<= custody-count (count (:held-adjustments world []))))))

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
    :custody/adjustment-roots custody-adjustment-roots
    :idempotent? idempotent?}))

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
  [plan world]
  (let [preflight-result (preflight plan)]
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
                      (:plan/effects plan []))
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
