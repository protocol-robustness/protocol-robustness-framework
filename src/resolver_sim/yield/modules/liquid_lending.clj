(ns resolver-sim.yield.modules.liquid-lending
  "Liquid-lending yield archetype using the decision-based accrual engine.

   Shared withdrawals are decision-led and evidence-bearing. Persisted pro-rata
   propagations are applied only when their committed token, position snapshots,
   obligation lineage, original priority, and execution order still match the
   world being mutated."
  (:require [resolver-sim.yield.position :as pos]
            [resolver-sim.yield.token :as tok]
            [resolver-sim.yield.accrual :as accrual]
            [resolver-sim.yield.partial-fill :as partial-fill]
            [resolver-sim.yield.pro-rata-propagation-policy :as propagation-policy]
            [resolver-sim.yield.exact-math :as m]
            [resolver-sim.yield.accounting :as acct]
            [resolver-sim.yield.market-state :as market-state]
            [resolver-sim.util.attribution :as attr]
            [resolver-sim.util.evidence :as util-evidence]
            [resolver-sim.yield.evidence :as ye]
            [resolver-sim.time.context :as time-ctx]
            [resolver-sim.time.deadlines :as dl]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.evidence.capture :as evidence]))

(def ^:private propagation-content-hash-field :propagation/content-hash)
(def ^:private preconditions-hash-field :application/preconditions-hash)

(defn- normalize-token [token]
  (tok/normalize token))

(defn- resolve-now [world]
  (time-ctx/block-ts world))

(defn- token= [a b]
  (= (normalize-token a) (normalize-token b)))

(defn- canonical-hash
  "Use the repository's canonical application hashing boundary for embedded
   state commitments as well as application artifacts."
  [value]
  (partial-fill/application-hash
   (if (map? value) value {:value value})))

(defn canonical-hash-safe
  "Canonical hash that normalizes ratios (and other types unsafe for
    canonical-bytes) into deterministic safe equivalents before hashing.
   Used for position data that may contain ratio fields like :shares,
   :entry-index, and :current-index."
  [value]
  (letfn [(walk [v]
            (cond
              (instance? clojure.lang.Ratio v) (long (Math/round (double v)))
              (instance? Double v) (double v)
              (instance? Float v) (double v)
              (map? v) (persistent! (reduce-kv (fn [m k v] (assoc! m (walk k) (walk v))) (transient {}) v))
              (vector? v) (mapv walk v)
              (set? v) (set (map walk v))
              :else v))]
    (canonical-hash (walk value))))

(defn- content-address-ledger
  "Attach a content-addressed fixed-point certificate to a withdrawal ledger
   record, mirroring the partial-fill decision artifacts: :ledger/hash is the
   canonical hash of the record and :ledger/preimage is its canonical pr-str
   fixed-point serialization. This makes the batch allocation bound
   (`filled ≤ available`) independently re-verifiable against the fixed-point
   canonical form instead of being a self-authored, un-hashed map."
  [record]
  (let [base (dissoc record :ledger/hash :ledger/preimage)
        hash (hc/hash-with-intent {:hash/intent :evidence-record} base)]
    (assoc record
           :ledger/preimage (pr-str base)
           :ledger/hash (str "sha256:" hash))))

(defn ledger-hash-valid?
  "Verify a withdrawal ledger record's fixed-point certificate: its :ledger/hash
   must equal the canonical hash of its non-hash fields (the :ledger/preimage
   fixed point). Returns false when the record carries no hash (legacy)."
  [record]
  (when-let [h (:ledger/hash record)]
    (= h (str "sha256:"
              (hc/hash-with-intent {:hash/intent :evidence-record}
                                   (dissoc record :ledger/hash :ledger/preimage))))))

(defn- fail!
  ([message reason]
   (fail! message reason {}))
  ([message reason data]
   (throw (ex-info message (assoc data :reason reason)))))

(defn- non-negative-integer! [value reason data]
  (when-not (and (integer? value) (not (neg? value)))
    (fail! "Expected a non-negative integer" reason
           (assoc data :value value)))
  (long value))

(defn- positive-integer? [value]
  (and (integer? value) (pos? value)))

(defn- base-position-id [owner-id position]
  (or (:position/id position) owner-id))

(defn- deferred-original-priority [position]
  (get-in position [:deferred-position :position/original-priority]))

(defn- authoritative-original-priority
  "Return the immutable priority for a position.

   A deferred lineage may repeat the base priority, but may not contradict it.
   Missing priority defaults to Long/MAX_VALUE for legacy positions."
  [owner-id position]
  (let [base-priority (:original-priority position)
        deferred-priority (deferred-original-priority position)]
    (when (and (some? base-priority)
               (some? deferred-priority)
               (not= base-priority deferred-priority))
      (fail! "Position priority contradicts deferred lineage"
             :original-priority-lineage-mismatch
             {:owner-id owner-id
              :base-priority base-priority
              :deferred-priority deferred-priority}))
    (non-negative-integer!
     (or deferred-priority base-priority Long/MAX_VALUE)
     :missing-or-invalid-original-priority
     {:owner-id owner-id})))

(defn classify-shared-withdrawal-position
  "Classify a position before choosing its shared-withdrawal request amount.

   A deferred amount is authoritative only for an unwinding position with an
   active, later-liquidity deferred record and a positive integer residual."
  [position]
  (let [base-status (:status position)
        deferred (:deferred-position position)
        current-amount (:position/current-amount deferred)
        deferred-eligible? (and (= :deferred-withdrawal
                                   (:position/type deferred))
                                (= :active (:position/status deferred))
                                (= :later-liquidity
                                   (:position/eligibility deferred))
                                (positive-integer? current-amount))]
    (cond
      (and (= :active base-status) (nil? deferred))
      {:classification :ordinary-base-request
       :amount/source :base-position}

      (= :active base-status)
      {:classification :position-state-contradiction
       :base-status base-status
       :deferred-status (:position/status deferred)
       :reason :active-base-with-stale-deferred-position}

      (and (= :unwinding base-status) deferred-eligible?)
      {:classification :eligible-deferred-request
       :amount/source :deferred-position}

      (= :unwinding base-status)
      {:classification :invalid-incomplete-deferred-state
       :base-status base-status
       :deferred-status (:position/status deferred)
       :reason :unwinding-without-eligible-deferred-position}

      :else
      {:classification :ineligible-position
       :base-status base-status
       :reason :position-not-active-or-unwinding})))

(defn- get-in-token [world path module-id token & keys]
  (let [token* (normalize-token token)
        value (or (get-in world (into path [module-id token*]))
                  (get-in world (into path [module-id (name token*)])))]
    (if (seq keys)
      (get-in value keys)
      value)))

(defn- position-request-amount [position]
  (reduce + 0
          (vals (:requested
                 (partial-fill/calculate-fulfillment Long/MAX_VALUE position)))))

(defn- source-liquidity-balance [world token]
  (or (get-in world [:total-held token])
      (get-in world [:yield/held-balances token])
      (get-in world [:yield/held-balances (name token)])
      0))

(defn- held-position-balance
  "Read a single Sew held-ledger position value from either the flattened
   `:held/positions` map or the `:held-ledger/index :by-position` view."
  [world position-id]
  (or (get-in world [:held/positions position-id])
      (get-in world [:held-ledger/index :by-position position-id])
      0))

(defn- escrow-custody-base
  "The escrow's own custody value (principal + accrued-yield partitions) in a
   Sew world.

   A withdrawal must be settled against the position's own custody, not the
   aggregate token pool (`:total-held` / `:yield/held-balances`): the aggregate
   includes other escrows' custody and bond/fee reserves that this position
   cannot draw on. Settling against the aggregate makes shortfall detection
   over-optimistic when more than one escrow shares a token (the Sew finalize
   guard then hard-reverts with :insufficient-custody-position instead of
   deferring). Returns nil when the world carries no held-ledger custody for
   this escrow, in which case callers fall back to the aggregate pool."
  [world token owner-id]
  (when (and (vector? owner-id) (= :sew/escrow (first owner-id)))
    (let [wf-id (second owner-id)
          principal-pos [:held/position token :escrow-principal wf-id]
          yield-pos     [:held/position token :yield-custody wf-id]
          principal     (held-position-balance world principal-pos)
          yield         (held-position-balance world yield-pos)
          custody       (+ (long principal) (long yield))]
      (when (pos? custody)
        custody))))

(defn- position-custody-base
  "Resolve the liquidity base a withdrawal may draw on for one position:
   the escrow's own custody when the enclosing world tracks it (Sew), else the
   aggregate token pool. Used to compute `recoverable = base × available-ratio`."
  [world token owner-id]
  (or (escrow-custody-base world token owner-id)
      (source-liquidity-balance world token)
      0))

;; ---------------------------------------------------------------------------
;; deposit
;; ---------------------------------------------------------------------------

(defn deposit
  "Create a yield position using ratio-based entry-index accounting.

   Position identity is immutable: depositing with an existing owner/position
   ID is rejected rather than overwriting lineage and assigning a new
   'original' priority. This operation does not update :total-held because the
   enclosing escrow path already records custody."
  [world module op]
  (attr/with-attribution
    {:deposit/module-id (:module/id module)
     :deposit/position-id (:owner/id op)
     :deposit/token (:token op)}
    (let [owner-id (:owner/id op)
          amount (:amount op)
          token (normalize-token (:token op))
          module-id (:module/id module)]
      (when (contains? (:yield/positions world {}) owner-id)
        (fail! "Yield position ID already exists"
               :yield-position-id-conflict
               {:owner-id owner-id
                :module-id module-id
                :token token}))
      (let [index (m/ratio (or (get-in-token world
                                             [:yield/indices]
                                             module-id
                                             token)
                               1))
            shares (m/shares-from-principal-and-index (long amount) index)
            sequence-number (get-in world
                                    [:yield/deposit-seq module-id token]
                                    0)
            world' (-> world
                       (assoc-in [:yield/deposit-seq module-id token]
                                 (inc sequence-number))
                       (assoc-in [:yield/positions owner-id]
                                 (pos/make-position
                                  {:owner/id owner-id
                                   :position/id owner-id
                                   :module/id module-id
                                   :token token
                                   :principal (long amount)
                                   :shares shares
                                   :entry-index index
                                   :original-priority sequence-number})))]
        (evidence/capture-event-evidence!
         :yield-deposit
         {:deposit/before-positions (:yield/positions world)}
         {:deposit/after-positions (:yield/positions world')}
         {:deposit/params {:owner/id owner-id
                           :amount amount
                           :token token
                           :module/id module-id
                           :original-priority sequence-number}}
         nil
         {:world-before world
          :world-after world'})
        world'))))

;; ---------------------------------------------------------------------------
;; accrue
;; ---------------------------------------------------------------------------

(defn- accrue-from-index-schedule
  "Accrue active positions for one module/token from the scheduled index."
  [world _module token module-id now scheduled-index]
  (attr/with-attribution
    {:accrue/module-id module-id
     :accrue/token token
     :accrue/index scheduled-index
     :accrue/mode :index-schedule}
    (let [snapshot-positions (:yield/positions world {})
          snapshot-world (assoc-in world
                                   [:yield/indices module-id token]
                                   scheduled-index)
          updates (->> snapshot-positions
                       (filter (fn [[_ position]]
                                 (and (= (:module/id position) module-id)
                                      (token= (:token position) token)
                                      (= (:status position) :active))))
                       vec
                       (util-evidence/contextual-pmap
                        (fn [[owner-id position]]
                          (let [updated (acct/update-position-yield
                                         snapshot-world
                                         position
                                         scheduled-index)
                                old-yield (:unrealized-yield position 0)
                                yield-delta (- (:unrealized-yield updated 0)
                                               old-yield)]
                            [owner-id updated yield-delta]))))
          world' (reduce (fn [next-world [owner-id updated yield-delta]]
                           (-> next-world
                               (assoc-in [:yield/positions owner-id] updated)
                               (update-in [:total-yield-generated token]
                                          (fnil + 0)
                                          yield-delta)))
                         snapshot-world
                         updates)]
      (evidence/capture-event-evidence!
       :yield-accrue
       {:accrue/before-indices (:yield/indices world)
        :accrue/before-positions (:yield/positions world)}
       {:accrue/after-indices (:yield/indices world')
        :accrue/after-positions (:yield/positions world')}
       {:accrue/params {:module-id module-id
                        :token token
                        :scheduled-index scheduled-index
                        :event-time now
                        :mode :index-schedule}}
       nil
       {:world-before world
        :world-after world'})
      world')))

(defn accrue
  "Accrue all active positions for a module/token using the decision engine."
  [world module op]
  (let [token (normalize-token (:token op))
        dt (:dt op)
        module-id (:module/id module)
        now (resolve-now world)
        market (market-state/get-market-state world module-id token now)
        scheduled-index (:index market)]
    (if (and scheduled-index (not (zero? scheduled-index)))
      (accrue-from-index-schedule
       world module token module-id now scheduled-index)
      (let [snapshot-positions (:yield/positions world {})
            decisions (->> snapshot-positions
                           (filter (fn [[_ position]]
                                     (and (= (:module/id position) module-id)
                                          (token= (:token position) token)
                                          (= (:status position) :active))))
                           vec
                           (util-evidence/contextual-pmap
                            (fn [[owner-id _position]]
                              [owner-id
                               (accrual/accrual-decision
                                world
                                {:module-id module-id
                                 :token token
                                 :position-id owner-id
                                 :now now
                                 :dt dt})])))]
        ;; All decisions were computed against the same pre-accrual snapshot, so the
        ;; recoverable-liquidity cap must be coordinated at module granularity —
        ;; otherwise each position independently spends the same net-solvent headroom.
        (let [coordinated (accrual/coordinate-recoverable-liquidity-cap
                           world module-id token
                           (mapv second decisions))]
          (reduce (fn [next-world [[owner-id _] decision]]
                    (accrual/apply-accrual-decision-with-attribution
                     next-world
                     decision))
                  world
                  (mapv vector decisions coordinated)))))))

;; ---------------------------------------------------------------------------
;; single withdrawal
;; ---------------------------------------------------------------------------

(defn withdraw
  "Withdraw one active yield position after crystallizing its final yield."
  [world module op]
  (let [owner-id (:owner/id op)
        position-path [:yield/positions owner-id]
        position (get-in world position-path)
        module-id (:module/id module)]
    (attr/with-attribution
      {:withdraw/module-id module-id
       :withdraw/position-id owner-id}
      (cond
        (nil? position) world
        (not= (:status position) :active) world
        (not= (:module/id position) module-id) world

        :else
        (let [token (normalize-token (:token position))
              now (resolve-now world)
              accrual-decision (accrual/accrual-decision
                                world
                                {:module-id module-id
                                 :token token
                                 :position-id owner-id
                                 :now now
                                 :dt 0})
              world-after-accrue
              (accrual/apply-accrual-decision-with-attribution
               world
               accrual-decision)
              position-after-accrue (get-in world-after-accrue position-path)
              base-recoverable (position-custody-base
                                world-after-accrue token owner-id)
              market (market-state/get-market-state
                      world-after-accrue module-id token now)
              available-ratio (:available-ratio market 1.0)
              shortfall-model (:shortfall-model market)
              recoverable (long (* base-recoverable available-ratio))
              gross-amount (+ (:principal position-after-accrue 0)
                              (:unrealized-yield position-after-accrue 0))
              settlement (partial-fill/calculate-fulfillment
                          (max 0 recoverable)
                          position-after-accrue)
              decision-artifact
              (when (partial-fill/partial-fill? settlement)
                (partial-fill/decision-artifact
                 position-after-accrue
                 settlement
                 {:decision-source :yield-withdraw}))
              filled (:filled settlement {})
              deferred-map (:deferred settlement {})
              haircut-map (:haircut settlement {})
              fulfilled-total (reduce + 0 (vals filled))
              deferred-total (reduce + 0 (vals deferred-map))
              haircut-total (reduce + 0 (vals haircut-map))
              basis-total (reduce + 0 (vals (:requested settlement {})))
              unrealized (:unrealized-yield position-after-accrue 0)
              negative-unrealized (min 0 unrealized)
              adjusted-basis (+ basis-total negative-unrealized)
              shortfall
              (when (pos? (- adjusted-basis fulfilled-total))
                (let [shortfall-reason (or (:type shortfall-model)
                                           :liquidity-shortfall)
                      recoverable? (:recoverable shortfall-model true)
                      extra-deferred (if (and recoverable?
                                              (pos? unrealized))
                                       unrealized
                                       0)]
                  {:reason shortfall-reason
                   :basis-amount (+ adjusted-basis
                                    (if (pos? unrealized) unrealized 0))
                    ;; The negative-unrealized term folded into :basis-amount at
                    ;; settlement. The position's own :unrealized-yield is zeroed
                    ;; on settle, so invariants must read this recorded fold rather
                    ;; than the (post-settlement) position value to reconcile the
                    ;; shortfall splits to basis (f + d + h + fold == basis).
                   :basis-negative-unrealized negative-unrealized
                    ;; Exact economic value backing this settlement (principal +
                    ;; realized + unrealized at crystallization). check-aggregate
                    ;; uses it as the value bound so basis can never exceed the
                    ;; position's real value — the old reconstruction (principal +
                    ;; deferred-amount) double-counted deferred principal and made
                    ;; the over-count check vacuous.
                   :settlement-value (+ (long (:principal position-after-accrue 0))
                                        (long (:realized-yield position-after-accrue 0))
                                        (long (:unrealized-yield position-after-accrue 0)))
                   :available-ratio (if (pos? gross-amount)
                                      (/ (rationalize fulfilled-total)
                                         (rationalize gross-amount))
                                      1)
                   :fulfilled-amount fulfilled-total
                   :deferred-amount (+ (if recoverable? deferred-total 0)
                                       extra-deferred)
                   :haircut-amount (if recoverable?
                                     haircut-total
                                     (+ deferred-total
                                        haircut-total
                                        extra-deferred))
                   :as-of-index (:current-index position-after-accrue)
                   :started-at now}))
              realized-yield
              (if shortfall
                (max 0
                     (min unrealized
                          (- fulfilled-total
                             (:principal position-after-accrue 0))))
                unrealized)
              updated-position
              (-> position-after-accrue
                  (assoc :partial-fill-affected? (boolean shortfall))
                  (assoc :status (if shortfall :unwinding :withdrawn))
                  (assoc :realized-yield realized-yield)
                  (assoc :unrealized-yield 0)
                  (assoc :shortfall shortfall))
              world-with-position
              (cond-> (assoc-in world-after-accrue
                                position-path
                                updated-position)
                decision-artifact
                (partial-fill/attach-decision-artifact decision-artifact))
              final-world
              (cond-> (update-in world-with-position
                                 [:yield/withdrawal-ledger]
                                 (fnil conj [])
                                 (content-address-ledger
                                  {:ledger/kind :yield/withdrawal-single
                                   :ledger/id [:withdrawal-single module-id token
                                               owner-id (resolve-now world-after-accrue)]
                                   :ledger/domain "withdrawal-ledger.v1"
                                   :ledger/module-id module-id
                                   :ledger/token token
                                   :ledger/owner-ids [owner-id]
                                   :ledger/run-id (:run/id world-after-accrue)
                                   :ledger/execution-id (:execution/id world-after-accrue)
                                   :ledger/run-root (partial-fill/ledger-run-root world-after-accrue)
                                   :ledger/request-set-root
                                   (partial-fill/ledger-request-set-root
                                    [owner-id]
                                    [{:owner-id owner-id
                                      :requested basis-total}])
                                   :ledger/available (max 0 (long recoverable))
                                   :ledger/requested basis-total
                                   :ledger/filled fulfilled-total
                                   :ledger/deferred deferred-total
                                   :ledger/haircut haircut-total
                                   :ledger/rows [{:owner-id owner-id
                                                  :requested basis-total
                                                  :filled fulfilled-total
                                                  :deferred deferred-total
                                                  :haircut haircut-total}]}))
                shortfall
                (ye/emit-shortfall-event
                 :yield.shortfall/deferred-created
                 owner-id
                 {:deferred-amount deferred-total
                  :haircut-amount haircut-total
                  :fulfilled-amount fulfilled-total
                  :basis-amount basis-total
                  :available-ratio (:available-ratio shortfall 1.0)
                  :shortfall-kind (name (or (:reason shortfall) :unknown))}))]
          (evidence/capture-event-evidence!
           :yield-withdraw
           {:withdraw/before-positions (:yield/positions world)}
           {:withdraw/after-positions (:yield/positions final-world)}
           {:withdraw/params {:owner/id owner-id
                              :module/id module-id
                              :token token
                              :event-time now
                              :shortfall shortfall}
            :withdraw/partial-fill-decision decision-artifact}
           nil
           {:world-before world
            :world-after final-world})
          final-world)))))

;; ---------------------------------------------------------------------------
;; shared pro-rata withdrawal: lineage and application contracts
;; ---------------------------------------------------------------------------

(defn record-closed-deferred-position
  "Insert an immutable deferred-position closure record keyed by position ID."
  [history record]
  (let [history (or history {})
        position-id (:position/id record)
        existing (get history position-id)]
    (when-not position-id
      (fail! "Closed deferred position is missing an ID"
             :missing-deferred-position-id
             {:record record}))
    (cond
      (nil? existing) (assoc history position-id record)
      (= existing record) history
      :else
      (fail! "Deferred position history conflict"
             :deferred-position-history-conflict
             {:position-id position-id
              :existing existing
              :replacement record}))))

(defn application-order-key
  "Canonical application ordering.

   Protocol order is step first and event sequence second. Identity fields scope
   otherwise equal coordinates to one canonical execution."
  [order]
  [(:run-id order)
   (:execution-id order)
   (:scenario-id order)
   (:step order)
   (:event-id order)])

(defn application-order-compare [a b]
  (compare (application-order-key a)
           (application-order-key b)))

(defn- current-application-order [world]
  (let [temporal (time-ctx/temporal-context world)
        order {:schema-version "pro-rata-application-order.v2"
               :run-id (:run/id world)
               :execution-id (:execution/id world)
               :scenario-id (get-in world [:params :scenario-id])
               :step (:step temporal)
               :event-id (:event-seq temporal)}]
    (doseq [field [:run-id :execution-id :scenario-id :step :event-id]]
      (when (nil? (get order field))
        (fail! "Application order is incomplete"
               :incomplete-application-order
               {:field field
                :application-order order})))
    (non-negative-integer! (:step order)
                           :invalid-application-step
                           {:application-order order})
    (non-negative-integer! (:event-id order)
                           :invalid-application-event-id
                           {:application-order order})
    order))

(defn- lineage-history-rounds [position]
  (keep (fn [[_ record]]
          (let [round (:position/round record)]
            (when (and (integer? round) (not (neg? round)))
              (long round))))
        (:deferred-position-history position {})))

(defn- next-lineage-round [position]
  (let [active-round (get-in position
                             [:deferred-position :position/round]
                             0)
        maximum-history-round (reduce max 0 (lineage-history-rounds position))]
    (inc (max (long active-round) maximum-history-round))))

(defn- lineage-origin-reference
  "Create the durable, independently verifiable origin commitment for a lineage.

   This deliberately commits only the immutable identity, queue-domain, and
   priority inputs. The mutable base position is later updated as it unwinds,
   so hashing its full pre-deferral state cannot authenticate the origin after
   the first partial fill."
  [owner-id position]
  {:schema-version "yield-lineage-origin.v1"
   :position-id (base-position-id owner-id position)
   :owner-id owner-id
   :module-id (:module/id position)
   :token (normalize-token (:token position))
   :root-obligation-id (base-position-id owner-id position)
   :original-priority (authoritative-original-priority owner-id position)})

;; ---------------------------------------------------------------------------
;; queue policy contract
;; ---------------------------------------------------------------------------

(defn- secondary-position-id
  "Canonical immutable position identifier for queue ordering.
   A deferred lineage shares one content-addressed root; a base position commits
   the same origin reference hash. This is the declared :secondary-position-id
   tie-break for equal original priorities and is stable across the whole
   lineage including the genesis base position."
  [position]
  (or (get-in position [:deferred-position :deferred/lineage-root])
      (let [owner-id (or (:owner/id position)
                         (:position/participant-id position))]
        (canonical-hash-safe (lineage-origin-reference owner-id position)))))

(defn queue-domain
  "Declared queue domain for a shared-liquidity withdrawal: scope by module,
   token, and liquidity pool so that entries from different pools are not
   compared against each other."
  [module-id token]
  {:module-id module-id
   :token (normalize-token token)
   :liquidity-pool :shared-liquidity-pool})

(defn same-queue-domain?
  "Are two queue domain declarations identical (same module, token, pool)?"
  [a b]
  (= (:queue/domain a) (:queue/domain b)))

(defn compare-queue-entries
  "Compare two declared queue entries by original-priority (ascending) then the
   immutable secondary position id. Returns :incomparable when the entries live
   in different queue domains — cross-pool entries are not globally ordered."
  [a b]
  (if-not (same-queue-domain? a b)
    :incomparable
    (let [ka (:queue/key a)
          kb (:queue/key b)
          by-priority (compare (long (:original-priority ka))
                               (long (:original-priority kb)))]
      (if (zero? by-priority)
        (compare (str (:secondary-position-id ka))
                 (str (:secondary-position-id kb)))
        by-priority))))

;; ---------------------------------------------------------------------------
;; merge policy: single-origin lineage
;; ---------------------------------------------------------------------------

(defn lineage-origin-ids
  "Distinct committed origin identifiers across a position's whole deferred
   lineage (active + archived), keyed on the canonical :root-obligation-id.
   More than one distinct identifier indicates a multi-origin merge. Origin
   position-id continuity is validated separately by the origin-reference check,
   so a mislabeled origin position id is not conflated with a true merge."
  [position]
  (->> (cons (:deferred-position position)
             (vals (:deferred-position-history position {})))
       (remove nil?)
       (keep :position/root-obligation-id)
       distinct
       vec))

(defn single-origin-lineage?
  "A lineage is single-origin iff it commits at most one distinct root
   obligation identifier across all of its records. Multi-origin merges are
   forbidden."
  [position]
  (<= (count (lineage-origin-ids position)) 1))

(defn- validate-active-deferred-lineage!
  "Require and authenticate canonical lineage anchors on an active deferred
   position. Missing fields are not treated as matching legacy absence."
  [owner-id position]
  (when-let [deferred (:deferred-position position)]
    (when (= :deferred-withdrawal (:position/type deferred))
      (when-not (single-origin-lineage? position)
        (fail! "Deferred lineage merges more than one origin"
               :multi-origin-lineage-merge
               {:owner-id owner-id
                :origin-ids (lineage-origin-ids position)}))
      (doseq [key [:deferred/lineage-root
                   :deferred/predecessor-hash
                   :deferred/original-position
                   :position/original-priority
                   :position/root-obligation-id]]
        (when (nil? (get deferred key))
          (fail! "Active deferred position is missing a lineage anchor"
                 :missing-deferred-lineage-anchor
                 {:owner-id owner-id :missing key})))
      (let [origin (:deferred/original-position deferred)
            expected-origin (lineage-origin-reference owner-id position)
            expected-root (canonical-hash-safe expected-origin)]
        (when-not (= origin expected-origin)
          (fail! "Deferred position origin does not match its base position"
                 :deferred-lineage-origin-mismatch
                 {:owner-id owner-id
                  :expected expected-origin
                  :actual origin}))
        (when-not (= expected-root (:deferred/lineage-root deferred))
          (fail! "Deferred lineage root does not authenticate its origin"
                 :deferred-lineage-origin-root-mismatch
                 {:owner-id owner-id
                  :expected expected-root
                  :actual (:deferred/lineage-root deferred)})))
      (let [parent-id (:position/parent-id deferred)
            origin-position-id (get-in deferred
                                       [:deferred/original-position :position-id])
            requires-archive? (and parent-id
                                   (not= parent-id origin-position-id))]
        (when (and requires-archive?
                   (nil? (get (:deferred-position-history position) parent-id)))
          (fail! "Deferred predecessor archive is missing"
                 :missing-deferred-predecessor-archive
                 {:owner-id owner-id
                  :parent-id parent-id}))
        (when-let [parent (get (:deferred-position-history position) parent-id)]
          (when (nil? (:position/pre-closure-hash parent))
            (fail! "Archived deferred predecessor is missing a pre-closure commitment"
                   :missing-deferred-pre-closure-commitment
                   {:owner-id owner-id
                    :parent-id parent-id}))
          (when-not (= (:deferred/lineage-root parent)
                       (:deferred/lineage-root deferred))
            (fail! "Deferred lineage root differs from its archived predecessor"
                   :deferred-lineage-root-continuity-mismatch
                   {:owner-id owner-id
                    :parent-id parent-id
                    :expected (:deferred/lineage-root parent)
                    :actual (:deferred/lineage-root deferred)}))
          (when-not (= (:position/pre-closure-hash parent)
                       (:deferred/predecessor-hash deferred))
            (fail! "Deferred predecessor hash does not match archived pre-closure commitment"
                   :deferred-predecessor-hash-mismatch
                   {:owner-id owner-id
                    :parent-id parent-id
                    :expected (:position/pre-closure-hash parent)
                    :actual (:deferred/predecessor-hash deferred)}))
          (when-not (= (:position/id deferred) (:position/successor-id parent))
            (fail! "Archived deferred predecessor does not link to active successor"
                   :deferred-lineage-successor-mismatch
                   {:owner-id owner-id
                    :parent-id parent-id
                    :expected-successor (:position/id deferred)
                    :actual-successor (:position/successor-id parent)})))))))

(defn- position-state-commitment [owner-id position]
  (validate-active-deferred-lineage! owner-id position)
  ;; TODO(deferral.v1):
  ;; The deferred-position commitment below is the implicit deferral artifact.
  ;; resolver-sim.deferral formalizes this as deferral.v1 (stable :deferral/id +
  ;; hashed snapshots + lineage chain). Migrating this site to emit deferral.v1
  ;; would change :deferred-position-hash / position-hash values and the
  ;; invariants that reconstruct them — defer until a versioned migration is
  ;; acceptable. See GitHub issue: "deferral.v1 first-class hashed artifact".
  {:owner-id owner-id
   :position-hash (canonical-hash-safe position)
   :position-id (base-position-id owner-id position)
   :module-id (:module/id position)
   :token (normalize-token (:token position))
   :status (:status position)
   :original-priority (authoritative-original-priority owner-id position)
   :deferred-position-id (get-in position
                                 [:deferred-position :position/id])
   :deferred-position-hash
   (when-let [deferred (:deferred-position position)]
     (canonical-hash-safe deferred))
   :deferred-current-amount
   (get-in position [:deferred-position :position/current-amount])
   :root-obligation-id
   (or (get-in position
               [:deferred-position :position/root-obligation-id])
       (base-position-id owner-id position))})

(defn- lineage-root
  "Return the durable genesis lineage root.
   The first root authenticates an immutable origin reference rather than a
   mutable full position snapshot; later deferrals inherit that same root."
  [owner-id position current-deferred]
  (if current-deferred
    (get current-deferred :deferred/lineage-root)
    (canonical-hash-safe (lineage-origin-reference owner-id position))))

(defn- predecessor-hash
  "Return the immediate predecessor hash for the deferred position chain.
   For the first deferral: hash of the base position commitment.
   For subsequent deferrals: hash of the prior deferred position."
  [position current-deferred]
  (if current-deferred
    (canonical-hash-safe current-deferred)
    (let [base-state (position-state-commitment (or (:owner/id position)
                                                    (:position/participant-id position))
                                                position)]
      (:position-hash base-state))))

(defn- build-application-preconditions
  [world module-id token owners requested-by-owner]
  (mapv
   (fn [owner-id]
     (let [position (get-in world [:yield/positions owner-id])
           classification (classify-shared-withdrawal-position position)
           commitment (position-state-commitment owner-id position)
           eligible-obligation (long (get requested-by-owner owner-id 0))
           obligation-id (:root-obligation-id commitment)
           source-position-id
           (or (:deferred-position-id commitment)
               (:position-id commitment))]
       (merge commitment
              {:schema-version "pro-rata-application-precondition.v1"
               :participant-id owner-id
               :module-id module-id
               :token token
               :classification (:classification classification)
               :obligation-id obligation-id
               :source-position-id source-position-id
               :eligible-obligation eligible-obligation})))
   owners))

(defn- attach-propagation-application-contract
  [propagation preconditions event-time application-order]
  (let [preconditions-hash (canonical-hash preconditions)
        base (assoc propagation
                    :application/base-propagation propagation
                    :application/preconditions preconditions
                    preconditions-hash-field preconditions-hash
                    :application/time-claims
                    {:schema-version "pro-rata-time-claims.v1"
                     :event-time event-time
                     :execution-order application-order
                     :captured-at nil
                     :signed-at nil
                     :timestamped-at nil
                     :signature-status :unsigned
                     :claim-note
                     :protocol-time-is-not-proof-of-signing-time})
        content-hash (canonical-hash
                      (dissoc base propagation-content-hash-field))]
    (assoc base propagation-content-hash-field content-hash)))

(defn- validate-base-propagation-binding! [propagation]
  (let [base (:application/base-propagation propagation)]
    (when-not (map? base)
      (fail! "Application extension is missing its validated propagation base"
             :missing-base-propagation))
    (let [core-fields [:propagation/id
                       :calculation-ref
                       :outcome-ref
                       :propagation-policy
                       :propagation
                       :participants
                       :summary
                       :residual
                       :accounting-entry-set-hash
                       :module/id
                       :allocation/invocation-context
                       :propagation/hash]]
      (when-not (= (select-keys propagation core-fields)
                   (select-keys base core-fields))
        (fail! "Application extension changes the validated propagation core"
               :application-extension-core-mismatch)))))

(defn- validate-propagation-content-hash! [propagation]
  (let [declared (get propagation propagation-content-hash-field)
        calculated (canonical-hash
                    (dissoc propagation propagation-content-hash-field))]
    (when-not (and declared (= declared calculated))
      (fail! "Propagation content hash mismatch"
             :propagation-content-hash-mismatch
             {:declared declared
              :calculated calculated}))))

(defn- propagation-token! [propagation]
  (let [declared-token (:token propagation)
        domain-token (get-in propagation [:allocation/domain :token])]
    (when-not (or declared-token domain-token)
      (fail! "Propagation does not commit a token"
             :missing-propagation-token))
    (when (and declared-token
               domain-token
               (not (token= declared-token domain-token)))
      (fail! "Propagation token contradicts allocation domain"
             :propagation-token-mismatch
             {:propagation-token declared-token
              :allocation-domain-token domain-token}))
    (normalize-token (or declared-token domain-token))))

(defn- preconditions-by-participant [propagation]
  (let [preconditions (:application/preconditions propagation)
        declared-hash (get propagation preconditions-hash-field)
        calculated-hash (canonical-hash preconditions)]
    (when-not (vector? preconditions)
      (fail! "Propagation application preconditions are missing"
             :missing-application-preconditions))
    (when-not (= declared-hash calculated-hash)
      (fail! "Application precondition hash mismatch"
             :application-preconditions-hash-mismatch
             {:declared declared-hash
              :calculated calculated-hash}))
    (let [indexed (into {} (map (juxt :participant-id identity) preconditions))]
      (when-not (= (count indexed) (count preconditions))
        (fail! "Application preconditions contain duplicate participants"
               :duplicate-application-precondition-participant))
      indexed)))

(defn- participant-obligation-id [participant]
  (get-in participant [:origin :obligation-id]))

(defn- validate-participant-precondition!
  [world module-id token participant precondition]
  (let [participant-id (:participant-id participant)
        position (get-in world [:yield/positions participant-id])
        current-commitment
        (when position
          (position-state-commitment participant-id position))
        participant-obligation (participant-obligation-id participant)
        participant-source-position-id
        (or (:source-position-id participant)
            (get-in participant [:origin :source-position-id])
            (get-in participant [:origin :position-id]))
        precondition-obligation (:obligation-id precondition)
        current-obligation (:root-obligation-id current-commitment)
        eligible-obligation (:eligible-obligation participant)
        expected-eligible (:eligible-obligation precondition)
        current-deferred (:deferred-current-amount current-commitment)
        classification (:classification precondition)]
    (when-not position
      (fail! "Propagation participant position is missing"
             :missing-propagation-participant-position
             {:participant-id participant-id}))
    (when-not (= module-id (:module/id position))
      (fail! "Propagation participant module mismatch"
             :propagation-participant-module-mismatch
             {:participant-id participant-id
              :expected module-id
              :actual (:module/id position)}))
    (when-not (token= token (:token position))
      (fail! "Propagation participant token mismatch"
             :propagation-participant-token-mismatch
             {:participant-id participant-id
              :expected token
              :actual (:token position)}))
    (when-not (= (:position-hash precondition)
                 (:position-hash current-commitment))
      (fail! "Propagation was calculated from a different position state"
             :stale-propagation-position-state
             {:participant-id participant-id
              :expected-position-hash (:position-hash precondition)
              :actual-position-hash (:position-hash current-commitment)}))
    (when-not (= (:original-priority precondition)
                 (:original-priority current-commitment))
      (fail! "Original priority changed after propagation calculation"
             :original-priority-precondition-mismatch
             {:participant-id participant-id
              :expected (:original-priority precondition)
              :actual (:original-priority current-commitment)}))
    (when-not (and participant-obligation
                   (= participant-obligation precondition-obligation)
                   (= participant-obligation current-obligation))
      (fail! "Withdrawal obligation lineage mismatch"
             :withdrawal-obligation-lineage-mismatch
             {:participant-id participant-id
              :participant-obligation-id participant-obligation
              :precondition-obligation-id precondition-obligation
              :current-obligation-id current-obligation}))
    (when (and participant-source-position-id
               (not= participant-source-position-id
                     (:source-position-id precondition)))
      (fail! "Propagation source position differs from committed precondition"
             :source-position-precondition-mismatch
             {:participant-id participant-id
              :participant-source-position-id participant-source-position-id
              :precondition-source-position-id
              (:source-position-id precondition)}))
    (when-not (= eligible-obligation expected-eligible)
      (fail! "Eligible obligation differs from committed precondition"
             :eligible-obligation-precondition-mismatch
             {:participant-id participant-id
              :participant-eligible-obligation eligible-obligation
              :precondition-eligible-obligation expected-eligible}))
    (when (and (= classification :eligible-deferred-request)
               (not= eligible-obligation current-deferred))
      (fail! "Deferred position amount differs from eligible obligation"
             :deferred-amount-precondition-mismatch
             {:participant-id participant-id
              :eligible-obligation eligible-obligation
              :deferred-current-amount current-deferred}))
    (let [fulfilled (long (:fulfilled participant 0))
          deferred (long (:deferred participant 0))
          unmet (long (:unmet participant 0))
          waived (long (:waived participant 0))]
      (when-not (= eligible-obligation
                   (+ fulfilled deferred unmet waived))
        (fail! "Participant obligation does not reconcile"
               :participant-obligation-does-not-reconcile
               {:participant-id participant-id
                :eligible-obligation eligible-obligation
                :fulfilled fulfilled
                :deferred deferred
                :unmet unmet
                :waived waived})))
    current-commitment))

(defn- validate-application-preconditions!
  [world propagation module-id token]
  (validate-propagation-content-hash! propagation)
  (let [preconditions (preconditions-by-participant propagation)
        participants (:participants propagation)
        participant-ids (mapv :participant-id participants)]
    (when-not (and (vector? participants) (seq participants))
      (fail! "Propagation participants are missing"
             :missing-propagation-participants))
    (when-not (= (count participant-ids) (count (distinct participant-ids)))
      (fail! "Propagation participants are not unique"
             :duplicate-propagation-participant
             {:participant-ids participant-ids}))
    (when-not (= (set participant-ids) (set (keys preconditions)))
      (fail! "Propagation participants and preconditions differ"
             :propagation-precondition-participant-set-mismatch
             {:participant-ids participant-ids
              :precondition-participant-ids (vec (keys preconditions))}))
    (mapv (fn [participant]
            (let [participant-id (:participant-id participant)]
              (validate-participant-precondition!
               world
               module-id
               token
               participant
               (get preconditions participant-id))))
          participants)))

(defn- valid-application-record? [record]
  (and (= (:application/hash record)
          (canonical-hash (dissoc record :application/hash)))
       (= (:propagation-content-hash record)
          (get-in record
                  [:propagation/reference :propagation/content-hash]))))

(defn- prior-application-match?
  [existing propagation application-key]
  (and (valid-application-record? existing)
       (= (select-keys existing
                       [:propagation-id
                        :calculation-id
                        :outcome-hash
                        :policy-hash
                        :application-key
                        :propagation-content-hash])
          {:propagation-id (:propagation/id propagation)
           :calculation-id (:calculation-ref propagation)
           :outcome-hash (:outcome-ref propagation)
           :policy-hash (get-in propagation
                                [:propagation-policy :policy/hash])
           :application-key application-key
           :propagation-content-hash
           (get propagation propagation-content-hash-field)})))

(defn- transition-commitment
  [propagation participant precondition application-order event-time]
  (canonical-hash
   {:schema-version "deferred-lineage-transition.v1"
    :propagation-content-hash
    (get propagation propagation-content-hash-field)
    :participant-id (:participant-id participant)
    :obligation-id (:obligation-id precondition)
    :source-position-hash (:position-hash precondition)
    :application-order application-order
    :event-time event-time
    :fulfilled (:fulfilled participant 0)
    :deferred (:deferred participant 0)}))

(defn apply-pro-rata-propagation
  "Apply one validated shared-withdrawal propagation exactly once.

   The persisted artifact is authoritative only when it still matches the exact
   token, participant position snapshots, obligation roots, residual amounts,
   original priorities, and execution identity committed at calculation time."
  [world propagation]
  (let [validated-propagation
        (or (:application/base-propagation propagation) propagation)
        validation
        (partial-fill/validate-pro-rata-propagation validated-propagation)
        propagation-id (:propagation/id propagation)
        application-key (get-in propagation
                                [:propagation :idempotency-key])]
    (when-not propagation-id
      (fail! "Propagation ID is missing" :missing-propagation-id))
    (when-not application-key
      (fail! "Propagation idempotency key is missing"
             :missing-propagation-idempotency-key))
    (validate-base-propagation-binding! propagation)
    (when-not (:valid? validation)
      (fail! "Invalid pro-rata propagation"
             :invalid-pro-rata-propagation
             {:stage :policy-validation
              :validation validation}))
    (if-let [existing (get-in world
                              [:yield/applied-pro-rata-propagations
                               propagation-id])]
      (if (prior-application-match? existing propagation application-key)
        {:status :already-applied
         :world world
         :propagation-id propagation-id}
        {:status :failed
         :reason :applied-propagation-record-mismatch
         :world world
         :propagation-id propagation-id})
      (let [token (propagation-token! propagation)
            module-id (or (get-in propagation
                                  [:allocation/domain :module/id])
                          (:module/id propagation))
            _ (when-not module-id
                (fail! "Propagation does not commit a module"
                       :missing-propagation-module))
            application-order (current-application-order world)
            committed-order (get-in propagation
                                    [:application/time-claims
                                     :execution-order])
            event-time (get-in propagation
                               [:application/time-claims :event-time])
            _ (when-not (= committed-order application-order)
                (fail! "Propagation execution order does not match world"
                       :application-order-precondition-mismatch
                       {:committed committed-order
                        :current application-order}))
            _ (when-not (= event-time (resolve-now world))
                (fail! "Propagation event time does not match world"
                       :event-time-precondition-mismatch
                       {:committed event-time
                        :current (resolve-now world)}))
            current-commitments
            (validate-application-preconditions!
             world propagation module-id token)
            commitment-by-participant
            (into {} (map (juxt :owner-id identity) current-commitments))
            allocated (long (get-in propagation [:summary :allocated] 0))
            source-before-raw (source-liquidity-balance world token)
            _ (when (and (pos? allocated) (nil? source-before-raw))
                (fail! "Missing shared liquidity balance"
                       :missing-shared-liquidity-balance
                       {:token token
                        :allocated allocated}))
            _ (when (and (some? source-before-raw)
                         (or (not (integer? source-before-raw))
                             (neg? source-before-raw)))
                (fail! "Invalid shared liquidity balance"
                       :invalid-shared-liquidity-balance
                       {:token token
                        :balance source-before-raw}))
            _ (when (and (integer? source-before-raw)
                         (< source-before-raw allocated))
                (fail! "Insufficient shared liquidity"
                       :insufficient-shared-liquidity
                       {:token token
                        :balance source-before-raw
                        :allocated allocated}))
            source-before (long (or source-before-raw 0))
            preconditions (preconditions-by-participant propagation)
            participants (:participants propagation)
            next-world
            (reduce
             (fn [next-world participant]
               (let [participant-id (:participant-id participant)
                     position (get-in next-world
                                      [:yield/positions participant-id])
                     current-deferred (:deferred-position position)
                     precondition (get preconditions participant-id)
                     current-commitment
                     (get commitment-by-participant participant-id)
                     obligation-id (:obligation-id precondition)
                     deferred (long (:deferred participant 0))
                     fulfilled (long (:fulfilled participant 0))
                     original-priority (:original-priority precondition)
                     round (next-lineage-round position)
                     _ (propagation-policy/check-max-lineage-round!
                        round
                        (get-in propagation [:propagation-policy :policy/snapshot]))
                     transition-hash
                     (transition-commitment
                      propagation
                      participant
                      precondition
                      application-order
                      event-time)
                     successor-id
                     (str participant-id
                          "/deferred/"
                          round
                          "/via/"
                          propagation-id)
                     successor
                     (let [m {:position/id successor-id
                              :position/type :deferred-withdrawal
                              :position/token token
                              :position/participant-id participant-id
                              :position/root-obligation-id
                              (or (:position/root-obligation-id current-deferred)
                                  obligation-id)
                              :position/parent-id
                              (or (:position/id current-deferred)
                                  (:position-id current-commitment))
                              :position/parent-hash
                              (or (:deferred-position-hash current-commitment)
                                  (:position-hash current-commitment))
                              :position/origin-propagation-id propagation-id
                              :position/created-by-transition-hash transition-hash
                              :position/created-order application-order
                              :position/created-event-time event-time
                              :position/deadline-ts
                              (let [dur (get-in propagation
                                                [:propagation-policy
                                                 :policy/snapshot
                                                 :shortfall
                                                 :deferral-duration-seconds]
                                                0)]
                                (when (pos? dur)
                                  (dl/deadline event-time dur)))
                              :position/round round
                              :position/original-priority
                              (or (:position/original-priority current-deferred)
                                  original-priority)
                              :position/original-priority-source
                              (if current-deferred :inherited-from-prior-lineage :from-precondition)
                              :position/original-obligation
                              (or (:position/original-obligation current-deferred)
                                  (:eligible-obligation participant))
                              :position/current-amount deferred
                              :position/cumulative-fulfilled
                              (+ (long (:cumulative-fulfilled position 0)) fulfilled)
                              :position/eligibility :later-liquidity
                              :position/status :active
                              :deferred/class (when (pos? deferred)
                                                (propagation-policy/derive-deferred-class
                                                 {:reason :liquidity-shortfall
                                                  :basis-amount (+ fulfilled deferred)
                                                  :fulfilled-amount fulfilled
                                                  :deferred-amount deferred
                                                  :haircut-amount 0}))
                              :deferred/original-position
                              (or (:deferred/original-position current-deferred)
                                  (lineage-origin-reference participant-id position))
                              :deferred/lineage-root
                              (lineage-root participant-id position current-deferred)
                              :deferred/predecessor-hash (predecessor-hash position current-deferred)}]
                       (when (pos? deferred)
                         (propagation-policy/validate-deferred-position-schema m))
                       m)
                     closed-prior
                     (when current-deferred
                       (assoc current-deferred
                              :schema-version "deferred-position-closure.v1"
                              :position/status :closed
                              :position/closed-from-amount
                              (:position/current-amount current-deferred)
                              :position/current-amount 0
                              :position/closed-by-propagation-id propagation-id
                              :position/closed-by-transition-hash transition-hash
                              :position/closed-order application-order
                              :position/closed-event-time event-time
                              :position/successor-id
                              (when (pos? deferred) successor-id)
                              ;; Immutable, pre-closure predecessor commitment.
                              ;; The child commits this same value as
                              ;; :deferred/predecessor-hash, so archival cannot
                              ;; silently mutate the ancestor out of agreement.
                              :position/pre-closure-hash
                              (canonical-hash-safe current-deferred)
                              :position/pre-closure-snapshot current-deferred))
                     shortfall
                     (when (pos? deferred)
                       (let [settlement-unrealized (long (:unrealized-yield position 0))
                             fold (min 0 settlement-unrealized)]
                         {:reason :liquidity-shortfall
                          :basis-amount (+ fulfilled deferred fold)
                          ;; Negative unrealized (mark-to-market loss) is folded
                          ;; into basis exactly like the single-withdraw path, so
                          ;; the splits reconcile (f + d + h + fold == basis) and
                          ;; basis never exceeds the settlement value.
                          :basis-negative-unrealized fold
                          :settlement-value (+ (long (:principal position 0))
                                               (long (:realized-yield position 0))
                                               settlement-unrealized)
                          :fulfilled-amount fulfilled
                          :deferred-amount deferred
                          :haircut-amount 0}))
                     updated-position
                     (cond->
                      (assoc position
                             :status (if (pos? deferred)
                                       :unwinding
                                       :withdrawn)
                             :shortfall shortfall
                             :partial-fill-affected? (pos? deferred)
                             :cumulative-fulfilled
                             (+ (long (:cumulative-fulfilled position 0))
                                fulfilled))
                       closed-prior
                       (update :deferred-position-history
                               record-closed-deferred-position
                               closed-prior)

                       (pos? deferred)
                       (assoc :deferred-position successor)

                       (zero? deferred)
                       (dissoc :deferred-position))]
                 (-> next-world
                     (update-in [:yield/withdrawn token participant-id]
                                (fnil + 0)
                                fulfilled)
                     (assoc-in [:yield/positions participant-id]
                               updated-position))))
             world
             participants)
            next-world (-> next-world
                           ;; Pro-rata propagation allocates liquidity by assoc-ing
                           ;; :total-held directly (not via Sew acct/sub-held), so the
                           ;; held-adjustment ledger no longer reconstructs to total-held.
                           ;; Clear the completeness declaration so strong replay stays
                           ;; honestly :not-evaluated rather than falsely passing.
                           (assoc-in [:total-held token] (- source-before allocated))
                           (assoc-in [:params :held-adjustments/complete?] false))
            application-base
            {:schema-version "pro-rata-propagation-application.v3"
             :propagation-id propagation-id
             :propagation/reference
             {:propagation/id propagation-id
              :propagation/hash (:propagation/hash propagation)
              :propagation/content-hash
              (get propagation propagation-content-hash-field)}
             :propagation-content-hash
             (get propagation propagation-content-hash-field)
             :calculation-id (:calculation-ref propagation)
             :outcome-hash (:outcome-ref propagation)
             :policy-hash (get-in propagation
                                  [:propagation-policy :policy/hash])
             :application-key application-key
             :allocation/invocation-context
             (:allocation/invocation-context propagation)
             :application-order application-order
             :time-claims
             {:event-time event-time
              :captured-at nil
              :signed-at nil
              :timestamped-at nil
              :signature-status :unsigned
              :claim-note :protocol-time-is-not-proof-of-signing-time}
             :accounting-entry-set-hash
             (:accounting-entry-set-hash propagation)
             :source-account
               ;; Accounting intent record, not a state-mutation log.
               ;; The enclosing protocol custody path adjusts :total-held;
               ;; :before/:after here document the expected balance transition
               ;; for downstream reconciliation.
             {:account :shared-liquidity
              :token token
              :before source-before
              :delta (- allocated)
              :after (- source-before allocated)}
             :participants
             (mapv
              (fn [participant]
                (let [participant-id (:participant-id participant)
                      before (long (get-in world
                                           [:yield/withdrawn
                                            token
                                            participant-id]
                                           0))
                      delta (long (:fulfilled participant 0))
                      precondition (get preconditions participant-id)]
                  {:participant-id participant-id
                   :position-id participant-id
                   :obligation-id (:obligation-id precondition)
                   :original-priority (:original-priority precondition)
                   :source-position-id (:source-position-id precondition)
                   :position-before
                   (get-in world [:yield/positions participant-id])
                   :position-before-hash (:position-hash precondition)
                   :position-after
                   (get-in next-world [:yield/positions participant-id])
                   :position-after-hash
                   (canonical-hash-safe
                    (get-in next-world [:yield/positions participant-id]))
                   :withdrawn
                   {:account :withdrawn
                    :token token
                    :participant-id participant-id
                    :before before
                    :delta delta
                    :after (+ before delta)}
                   :obligation
                   {:before (:eligible-obligation participant)
                    :fulfilled delta
                    :deferred (:deferred participant)
                    :unmet (:unmet participant 0)
                    :waived (:waived participant 0)
                    :after (:obligation-after participant)}
                   :cumulative-fulfilled
                   {:before
                    (long (get-in world
                                  [:yield/positions
                                   participant-id
                                   :cumulative-fulfilled]
                                  0))
                    :delta delta
                    :after
                    (+ (long (get-in world
                                     [:yield/positions
                                      participant-id
                                      :cumulative-fulfilled]
                                     0))
                       delta)}}))
              participants)
             :residual
             {:token token
              :available (get-in propagation [:summary :available])
              :allocated allocated
              :amount (get-in propagation
                              [:summary :unallocated-residual])
              :destination (get-in propagation
                                   [:residual :destination])}
             :status :committed}
            application
            (let [outcome {:schema-version "partial-fill-decision.v1"
                           :artifact/id (:calculation-id application-base)
                           :artifact/hash (:outcome-hash application-base)}
                  application-base (assoc application-base :application/outcome outcome)
                  app-hash (canonical-hash application-base)
                  app-with-hash (assoc application-base :application/hash app-hash)
                  output-hash {:schema-version "pro-rata-application-output.v1"
                               :hash-algorithm "sha256"
                               :hash (partial-fill/pro-rata-application-output-hash app-with-hash propagation)}]
              (assoc app-with-hash
                     :application/output output-hash))]
        {:status :applied
         :propagation-id propagation-id
         :world
         (assoc-in next-world
                   [:yield/applied-pro-rata-propagations propagation-id]
                   application)}))))

(defn withdraw-shared
  "Atomically settle declared active or eligible-deferred positions against one
   module/token/time liquidity pool with one canonical pro-rata decision."
  [world module
   {:keys [owner-ids
           token
           allocation-mode
           effective-caps
           effective-cap-source
           propagation-policy-id]
    :as op}]
  (let [requested-policy-id propagation-policy-id
        propagation-policy*
        (propagation-policy/resolve-policy
         (or requested-policy-id :shared-withdrawal-propagation))
        policy-selection
        {:requested-policy-id requested-policy-id
         :resolved-policy-id (:policy/id propagation-policy*)
         :selection-source (if requested-policy-id
                             :operation
                             :runtime-default)}
        module-id (:module/id module)
        token (normalize-token token)
        owners-unsorted (vec (or owner-ids []))]
    (when-not (seq owners-unsorted)
      (fail! "Shared withdrawal requires at least one owner"
             :missing-shared-withdrawal-owners
             {:op op}))
    (when-not (= (count owners-unsorted)
                 (count (distinct owners-unsorted)))
      (fail! "Shared withdrawal owner IDs must be unique"
             :duplicate-shared-withdrawal-owner
             {:owner-ids owner-ids}))
    (when-not (= :pro-rata (keyword (or allocation-mode :pro-rata)))
      (fail! "Shared withdrawal only supports pro-rata allocation"
             :unsupported-shared-withdrawal-allocation-mode
             {:allocation-mode allocation-mode}))
    (when (and effective-caps (not (map? effective-caps)))
      (fail! "Shared withdrawal effective caps must be a map"
             :invalid-effective-caps
             {:effective-caps effective-caps}))
    (when (some (fn [[owner-id cap]]
                  (or (not (some #{owner-id} owners-unsorted))
                      (not (integer? cap))
                      (neg? cap)))
                effective-caps)
      (fail! "Effective caps must be non-negative integers for declared owners"
             :invalid-effective-cap-entry
             {:owner-ids owners-unsorted
              :effective-caps effective-caps}))
    (doseq [owner-id owners-unsorted]
      (when-not (get-in world [:yield/positions owner-id])
        (fail! "Shared withdrawal position is missing"
               :missing-shared-withdrawal-position
               {:owner-id owner-id})))
    (let [owners
          (vec
           (sort-by
            (fn [owner-id]
              [(authoritative-original-priority
                owner-id
                (get-in world [:yield/positions owner-id]))
               (secondary-position-id
                (get-in world [:yield/positions owner-id]))])
            owners-unsorted))
          positions (mapv #(get-in world [:yield/positions %]) owners)
          classifications
          (mapv classify-shared-withdrawal-position positions)
          valid-classifications
          #{:ordinary-base-request :eligible-deferred-request}
          invalid
          (->> (map vector owners positions classifications)
               (remove
                (fn [[_ position classification]]
                  (and position
                       (= module-id (:module/id position))
                       (token= token (:token position))
                       (valid-classifications
                        (:classification classification)))))
               vec)]
      (when (seq invalid)
        (let [[owner-id _ classification] (first invalid)]
          (fail! "Shared withdrawal position is invalid"
                 (or (:reason classification)
                     :invalid-shared-withdrawal-position)
                 {:module-id module-id
                  :token token
                  :owner-id owner-id
                  :classification (:classification classification)
                  :invalid-owner-ids (mapv first invalid)})))
      (attr/with-attribution
        {:withdraw/module-id module-id
         :withdraw/token token
         :withdraw/position-ids owners
         :withdraw/mode :shared-pro-rata}
        (let [now (resolve-now world)
              accrual-decisions
              (mapv
               (fn [owner-id]
                 (let [position (get-in world [:yield/positions owner-id])]
                   [owner-id
                    (when (= :active (:status position))
                      (accrual/accrual-decision
                       world
                       {:module-id module-id
                        :token token
                        :position-id owner-id
                        :now now
                        :dt 0}))]))
               owners)
              accrued-world
              (reduce (fn [next-world [_ decision]]
                        (if decision
                          (accrual/apply-accrual-decision
                           next-world
                           decision)
                          next-world))
                      world
                      accrual-decisions)
              accrued-positions
              (mapv #(get-in accrued-world [:yield/positions %]) owners)
              requested-by-owner
              (into {}
                    (map
                     (fn [owner-id position]
                       (let [{:keys [classification]}
                             (classify-shared-withdrawal-position position)]
                         [owner-id
                          (case classification
                            :eligible-deferred-request
                            (long (get-in position
                                          [:deferred-position
                                           :position/current-amount]))

                            :ordinary-base-request
                            (long (position-request-amount position))

                            (fail!
                             "Position became invalid during crystallization"
                             :shared-withdrawal-position-invalid-after-accrual
                             {:owner-id owner-id
                              :classification classification}))]))
                     owners
                     accrued-positions))
              rows
              (mapv
               (fn [owner-id]
                 (let [position (get-in accrued-world
                                        [:yield/positions owner-id])
                       deferred (:deferred-position position)
                       owed (long (get requested-by-owner owner-id 0))
                       supplied-cap (get effective-caps owner-id owed)
                       priority
                       (authoritative-original-priority owner-id position)
                       obligation-id
                       (or (:position/root-obligation-id deferred)
                           (base-position-id owner-id position))
                       source-position-id
                       (or (:position/id deferred)
                           (base-position-id owner-id position))]
                   {:key owner-id
                    :participant-id owner-id
                    :obligation-id obligation-id
                    :source-position-id source-position-id
                    :requested owed
                    :owed owed
                    :weight owed
                    :cap (long supplied-cap)
                    :original-priority priority
                    :priority-source
                    {:position-id (base-position-id owner-id position)
                     :field (if deferred
                              :position/original-priority
                              :original-priority)}}))
               owners)
              base-held (or (source-liquidity-balance accrued-world token) 0)
              market
              (market-state/get-market-state accrued-world module-id token now)
              available
              (max 0
                   (long (* (long base-held)
                            (:available-ratio market 1.0))))
              policy
              (merge partial-fill/default-partial-fill-policy
                     {:mode :pro-rata
                      :rounding-policy :largest-remainder
                      :allocation-ordering :original-priority-ascending
                      :rounding-tie-break :original-priority-ascending})
              settlement
              (-> (partial-fill/calculate-fulfillment-pro-rata
                   available
                   {}
                   policy
                   {:rows rows})
                  ((fn [result]
                     (if (and (every? zero? (vals (:deferred result)))
                              (every? zero? (vals (:haircut result))))
                       (assoc result :settlement-mode :full-fill)
                       result)))
                  (update-in
                   [:evidence :allocation-rows]
                   (fn [allocation-rows]
                     (mapv
                      (fn [row]
                        (let [owed (long (:owed row 0))
                              filled (long (:filled row 0))]
                          (assoc row
                                 :fill-ratio
                                 {:numerator filled
                                  :denominator owed})))
                      allocation-rows))))
              application-order (current-application-order accrued-world)
              invocation-context
              {:schema-version "pro-rata-invocation-context.v2"
               :run/id (:run/id accrued-world)
               :execution/id (:execution/id accrued-world)
               :scenario/id (get-in accrued-world [:params :scenario-id])
               :step (:step application-order)
               :event/id (:event-id application-order)
               :event-time now
               :time-claims
               {:event-time now
                :signed-at nil
                :timestamped-at nil
                :signature-status :unsigned
                :claim-note :protocol-time-is-not-proof-of-signing-time}}
              decision
              (partial-fill/decision-artifact
               {:owner/id "shared-pool"
                :position/id "shared-pool"
                :module/id module-id
                :token token}
               settlement
               {:decision-source :yield-withdraw-shared
                :position-id "shared-pool"
                :extra
                {:participants owners
                 :allocation/effective-caps
                 (into {} (map (juxt :key :cap) rows))
                 :allocation/effective-cap-source
                 (or effective-cap-source :scenario-fixture)
                 :allocation/scope :shared-liquidity-pool
                 :allocation/domain
                 {:module/id module-id
                  :token token
                  :block-time now}
                 :allocation/ordering
                 :original-priority-ascending
                 :allocation/rounding-tie-break
                 :original-priority-ascending
                 :allocation/priority-witness
                 (mapv (fn [row]
                         (merge
                          (select-keys row
                                       [:key
                                        :obligation-id
                                        :source-position-id
                                        :original-priority
                                        :priority-source])
                          {:queue/domain (queue-domain module-id token)
                           :queue/key
                           {:original-priority (:original-priority row)
                            :secondary-position-id
                            (secondary-position-id
                             (get-in accrued-world [:yield/positions (:key row)]))}}))
                       rows)
                 :allocation/invocation-context invocation-context}})
              preconditions
              (build-application-preconditions
               accrued-world
               module-id
               token
               owners
               requested-by-owner)
              propagation-base
              (partial-fill/pro-rata-propagation-artifact
               decision
               propagation-policy*
               policy-selection)
              propagation
              (attach-propagation-application-contract
               propagation-base
               preconditions
               now
               application-order)
              binding-violations
              (partial-fill/propagation-allocation-binding-violations
               decision
               propagation-base)
              _ (when (seq binding-violations)
                  (fail! "Propagation allocation binding failed"
                         :propagation-allocation-binding-failed
                         {:violations binding-violations}))
              application
              (apply-pro-rata-propagation accrued-world propagation)
              final-world
              (-> (:world application)
                  (partial-fill/attach-decision-artifact decision)
                  (partial-fill/attach-pro-rata-propagation propagation))]
          (evidence/capture-event-evidence!
           :yield-withdraw-shared
           {:withdraw/before-positions (:yield/positions world)}
           {:withdraw/after-positions (:yield/positions final-world)}
           {:withdraw/params
            {:owner-ids owners
             :module/id module-id
             :token token
             :event-time now
             :application-order application-order
             :available-liquidity available
             :allocation-mode :pro-rata
             :effective-caps (into {} (map (juxt :key :cap) rows))
             :effective-cap-source
             (or effective-cap-source :scenario-fixture)}
            :withdraw/partial-fill-decision decision
            :withdraw/pro-rata-propagation propagation
            :withdraw/pro-rata-propagation-policy
            (:propagation-policy propagation)}
           nil
           {:world-before world
            :world-after final-world})
          final-world)))))

;; ---------------------------------------------------------------------------
;; batch withdrawal
;; ---------------------------------------------------------------------------

(defn- compute-withdrawal-result
  "Pure computation for one batch withdrawal against the current world.

   Fulfilment is bounded by `available-liquidity` — the portion of the shared
   pool remaining to this row (first-come, first-served in owner order). It is
   calculated from the crystallized position produced by the accrual decision,
   not from the stale pre-accrual position."
  [world module-id now op available-liquidity]
  (let [owner-id (:owner/id op)
        position (get-in world [:yield/positions owner-id])]
    (when (and position
               (= (:status position) :active)
               (= (:module/id position) module-id))
      (let [token (normalize-token (:token position))
            accrual-decision
            (accrual/accrual-decision
             world
             {:module-id module-id
              :token token
              :position-id owner-id
              :now now
              :dt 0})
            crystallized-world
            (accrual/apply-accrual-decision world accrual-decision)
            crystallized-position
            (get-in crystallized-world [:yield/positions owner-id])
            market
            (market-state/get-market-state
             crystallized-world module-id token now)
            shortfall-model (:shortfall-model market)
            recoverable (max 0 (long available-liquidity))
            gross-amount (+ (:principal crystallized-position 0)
                            (:unrealized-yield crystallized-position 0))
            settlement
            (partial-fill/calculate-fulfillment
             (max 0 recoverable)
             crystallized-position)
            decision-artifact
            (when (partial-fill/partial-fill? settlement)
              (partial-fill/decision-artifact
               crystallized-position
               settlement
               {:decision-source :yield-withdraw}))
            filled (:filled settlement {})
            deferred-map (:deferred settlement {})
            haircut-map (:haircut settlement {})
            fulfilled-total (reduce + 0 (vals filled))
            deferred-total (reduce + 0 (vals deferred-map))
            haircut-total (reduce + 0 (vals haircut-map))
            basis-total (reduce + 0 (vals (:requested settlement {})))
            unrealized (:unrealized-yield crystallized-position 0)
            negative-unrealized (min 0 unrealized)
            adjusted-basis (+ basis-total negative-unrealized)
            shortfall
            (when (pos? (- adjusted-basis fulfilled-total))
              (let [shortfall-reason
                    (or (:type shortfall-model) :liquidity-shortfall)
                    recoverable? (:recoverable shortfall-model true)
                    extra-deferred
                    (if (and recoverable? (pos? unrealized))
                      unrealized
                      0)]
                {:reason shortfall-reason
                 :basis-amount (+ adjusted-basis
                                  (if (pos? unrealized) unrealized 0))
                 :basis-negative-unrealized negative-unrealized
                 :settlement-value (+ (long (:principal crystallized-position 0))
                                      (long (:realized-yield crystallized-position 0))
                                      (long (:unrealized-yield crystallized-position 0)))
                 :available-ratio
                 (if (pos? gross-amount)
                   (/ (rationalize fulfilled-total)
                      (rationalize gross-amount))
                   1)
                 :fulfilled-amount fulfilled-total
                 :deferred-amount
                 (+ (if recoverable? deferred-total 0) extra-deferred)
                 :haircut-amount
                 (if recoverable?
                   haircut-total
                   (+ deferred-total haircut-total extra-deferred))
                 :as-of-index (:current-index crystallized-position)
                 :started-at now}))
            realized-yield
            (if shortfall
              (max 0
                   (min unrealized
                        (- fulfilled-total
                           (:principal crystallized-position 0))))
              unrealized)
            updated-position
            (-> crystallized-position
                (assoc :partial-fill-affected? (boolean shortfall))
                (assoc :status (if shortfall :unwinding :withdrawn))
                (assoc :realized-yield realized-yield)
                (assoc :unrealized-yield 0)
                (assoc :shortfall shortfall))]
        {:owner-id owner-id
         :token token
         :accrual-decision accrual-decision
         :updated-position updated-position
         :decision-artifact decision-artifact
         :shortfall shortfall
         :fulfilled-total fulfilled-total
         :deferred-total deferred-total
         :haircut-total haircut-total
         :basis-total basis-total}))))

(defn withdraw-many
  "Batch withdraw multiple active positions against one shared liquidity pool.

   The batch is settled first-come, first-served in deterministic owner order:
   each position draws at most the pool remaining after earlier rows in the
   batch. This remains distinct from shared-pool pro-rata settlement
   (`withdraw-shared`), which splits the pool proportionally.

   A single coordinated pool prevents the double-spend that independent
   per-position fulfillment would cause — each row otherwise draws the full
   pool (`base × available-ratio`), over-crediting the batch whenever the pool
   cannot cover the sum of the requests. Each batch also records a
   content-addressed ledger entry (`:yield/withdrawal-ledger`) asserting the
   allocation bound, which `:yield/withdrawal-ledger-conservation` re-checks."
  [world module ops]
  (let [owner-ids (mapv :owner/id ops)
        _ (when-not (= (count owner-ids) (count (distinct owner-ids)))
            (fail! "Batch withdrawal owner IDs must be unique"
                   :duplicate-batch-withdrawal-owner
                   {:owner-ids owner-ids}))
        module-id (:module/id module)
        now (resolve-now world)
        tokens (distinct
                (keep (fn [op]
                        (some-> (get-in world [:yield/positions (:owner/id op)])
                                :token
                                normalize-token))
                      ops))
        _ (when (> (count tokens) 1)
            (fail! "Batch withdrawal spans multiple tokens"
                   :batch-withdrawal-token-mismatch
                   {:tokens tokens}))
        token (first tokens)
        base-recoverable (if token
                           (or (source-liquidity-balance world token) 0)
                           0)
        market (market-state/get-market-state world module-id token now)
        batch-available (long (* (long base-recoverable)
                                 (double (:available-ratio market 1.0))))
        application-order (current-application-order world)
        ledger-id [:withdrawal-batch module-id token
                   (:step application-order) (:event-id application-order)]
        {:keys [w filled requested deferred haircut rows]}
        (reduce
         (fn [{:keys [w remaining filled requested deferred haircut rows]} op]
           (let [result (compute-withdrawal-result
                         w module-id now op remaining)]
             (if (nil? result)
               {:w w :remaining remaining
                :filled filled :requested requested
                :deferred deferred :haircut haircut :rows rows}
               (let [owner-id (:owner/id op)
                     accrual-decision (:accrual-decision result)
                     updated-position (:updated-position result)
                     decision-artifact (:decision-artifact result)
                     shortfall (:shortfall result)
                     world-after-accrue
                     (accrual/apply-accrual-decision-with-attribution
                      w accrual-decision)
                     world-with-position
                     (assoc-in world-after-accrue
                               [:yield/positions owner-id]
                               updated-position)
                     world-with-artifact
                     (if decision-artifact
                       (partial-fill/attach-decision-artifact
                        world-with-position decision-artifact)
                       world-with-position)
                     world'
                     (if shortfall
                       (ye/emit-shortfall-event
                        world-with-artifact
                        :yield.shortfall/deferred-created
                        owner-id
                        {:deferred-amount (:deferred-total result)
                         :haircut-amount (:haircut-total result)
                         :fulfilled-amount (:fulfilled-total result)
                         :basis-amount (:basis-total result)
                         :available-ratio (:available-ratio shortfall 1.0)
                         :shortfall-kind
                         (name (or (:reason shortfall) :unknown))})
                       world-with-artifact)]
                 {:w world'
                  :remaining (- remaining (:fulfilled-total result 0))
                  :filled (+ filled (:fulfilled-total result 0))
                  :requested (+ requested (:basis-total result 0))
                  :deferred (+ deferred (:deferred-total result 0))
                  :haircut (+ haircut (:haircut-total result 0))
                  :rows (conj rows
                              {:owner-id owner-id
                               :requested (:basis-total result)
                               :filled (:fulfilled-total result)
                               :deferred (:deferred-total result)
                               :haircut (:haircut-total result)})}))))
         {:w world :remaining batch-available
          :filled 0 :requested 0 :deferred 0 :haircut 0 :rows []}
         ops)]
    (when (> filled batch-available)
      (fail! "Batch withdrawal filled exceeds the shared liquidity pool"
             :batch-withdrawal-pool-overcommit
             {:filled filled :available batch-available}))
    (update-in w [:yield/withdrawal-ledger]
               (fnil conj [])
               (content-address-ledger
                {:ledger/kind :yield/withdrawal-batch
                 :ledger/id ledger-id
                 :ledger/domain "withdrawal-ledger.v1"
                 :ledger/module-id module-id
                 :ledger/token token
                 :ledger/owner-ids owner-ids
                 ;; Per-run root binding: commit the content-addressed execution
                 ;; root (run/execution/scenario/params) plus the withdrawal
                 ;; subject root (distinct principals + requested amounts), so
                 ;; the certificate says "these withdrawals occurred in this
                 ;; exact execution world" and cannot be substituted with a
                 ;; different withdrawal in the same run.
                 :ledger/run-id (:run/id world)
                 :ledger/execution-id (:execution/id world)
                 :ledger/run-root (partial-fill/ledger-run-root world)
                 :ledger/request-set-root
                 (partial-fill/ledger-request-set-root owner-ids rows)
                 :ledger/available batch-available
                 :ledger/requested requested
                 :ledger/filled filled
                 :ledger/deferred deferred
                 :ledger/haircut haircut
                 :ledger/rows rows}))))

;; ---------------------------------------------------------------------------
;; emergency unwind
;; ---------------------------------------------------------------------------

(defn emergency-unwind
  "Mark all active positions for the selected module/token as unwinding."
  [world module op]
  (attr/with-attribution
    {:emergency/module-id (:module/id module)
     :emergency/token (:token op)}
    (let [module-id (:module/id module)
          token (normalize-token (:token op))
          world'
          (reduce
           (fn [next-world [owner-id position]]
             (if (and (= (:module/id position) module-id)
                      (token= (:token position) token)
                      (= (:status position) :active))
               (assoc-in next-world
                         [:yield/positions owner-id :status]
                         :unwinding)
               next-world))
           world
           (:yield/positions world {}))]
      (evidence/capture-event-evidence!
       :yield-emergency-unwind
       {:emergency/before-positions (:yield/positions world)}
       {:emergency/after-positions (:yield/positions world')}
       {:emergency/params {:module-id module-id
                           :token token
                           :event-time (resolve-now world)}}
       nil
       {:world-before world
        :world-after world'})
      world')))

;; ---------------------------------------------------------------------------
;; claim deferred
;; ---------------------------------------------------------------------------

(defn claim-deferred
  "Attempt to reclaim deferred yield from an unwinding position."
  [world module op]
  (let [owner-id (:owner/id op)
        position-path [:yield/positions owner-id]
        position (get-in world position-path)
        module-id (:module/id module)]
    (attr/with-attribution
      {:claim/module-id module-id
       :claim/position-id owner-id
       :claim/token (:token position)}
      (cond
        (nil? position) world
        (not= (:module/id position) module-id) world

        (= (:status position) :unwinding)
        (let [old-position position
              recovered-position (acct/claim-deferred world module-id position)
              reclaimed (:reclaimed-amount recovered-position 0)
              active-deferred (:deferred-position old-position)
              _ (when (and (pos? reclaimed) active-deferred)
                  (let [outstanding (:position/current-amount active-deferred)]
                    (when-not (= reclaimed outstanding)
                      (fail! "Deferred claim amount differs from lineage outstanding amount"
                             :deferred-claim-lineage-amount-mismatch
                             {:owner-id owner-id
                              :reclaimed reclaimed
                              :outstanding outstanding
                              :deferred-position-id (:position/id active-deferred)}))))
              new-position
              (if (and (pos? reclaimed) active-deferred)
                (let [closed-deferred
                      (assoc active-deferred
                             :schema-version "deferred-position-closure.v1"
                             :position/status :closed
                             :position/closed-from-amount
                             (:position/current-amount active-deferred)
                             :position/current-amount 0
                             :position/closed-by :claim-deferred
                             :position/closed-reclaimed-amount reclaimed
                             :position/closed-event-time (resolve-now world)
                             :position/pre-closure-hash
                             (canonical-hash-safe active-deferred)
                             :position/pre-closure-snapshot active-deferred)]
                  (-> recovered-position
                      (dissoc :deferred-position)
                      (update :deferred-position-history
                              record-closed-deferred-position
                              closed-deferred)))
                recovered-position)]
          (if (pos? reclaimed)
            (let [world-with-position
                  (assoc-in world position-path new-position)
                  final-world
                  (ye/emit-shortfall-event
                   world-with-position
                   :yield.shortfall/deferred-reclaimed
                   owner-id
                   {:reclaimed-amount reclaimed
                    :deferred-before
                    (get-in old-position
                            [:shortfall :deferred-amount]
                            0)})]
              (evidence/capture-event-evidence!
               :yield-claim-deferred
               {:claim/before old-position}
               {:claim/after new-position}
               {:claim/reclaimed reclaimed
                :claim/event-time (resolve-now world)}
               nil
               {:world-before world
                :world-after final-world})
              final-world)
            world))

        (= (:status position) :queued)
        (assoc-in world
                  position-path
                  (-> position
                      (assoc :status :withdrawn)
                      (assoc :shortfall nil)
                      (assoc :realized-yield
                             (:unrealized-yield position 0))
                      (assoc :unrealized-yield 0)))

        :else world))))

;; ---------------------------------------------------------------------------
;; keeper: deferred-position deadline expiry
;; ---------------------------------------------------------------------------

(defn expire-overdue-deferred-positions
  "Keeper function: close any active deferred position past its deadline.
   Returns the updated world with expired positions closed."
  [world]
  (let [now-ts (time-ctx/block-ts world)]
    (reduce-kv
     (fn [w pid pos]
       (if-let [dp (:deferred-position pos)]
         (if (and (= :active (:position/status dp))
                  (some? (:position/deadline-ts dp))
                  (dl/deadline-expired? now-ts (:position/deadline-ts dp)))
           (assoc-in w [:yield/positions pid :deferred-position]
                     (-> dp
                         (assoc :position/status :closed)
                         (assoc :position/current-amount 0)
                         (assoc :position/expired-at-ts now-ts)))
           w)
         w))
     world
     (:yield/positions world {}))))

;; ---------------------------------------------------------------------------
;; module constructor
;; ---------------------------------------------------------------------------

(defn make-liquid-lending-module
  "Build a declarative liquid-lending module record."
  ([module-id]
   (make-liquid-lending-module
    module-id
    :yield.provider/liquid-lending))
  ([module-id module-type]
   {:module/id module-id
    :module/type module-type
    :module/capabilities
    #{:deposit
      :withdraw
      :withdraw-many
      :withdraw-shared
      :accrue
      :emergency-unwind
      :claim-deferred}
    :accounting/type :shares
    :ops
    {:yield/deposit deposit
     :yield/withdraw withdraw
     :yield/withdraw-many withdraw-many
     :yield/withdraw-shared withdraw-shared
     :yield/accrue accrue
     :yield/emergency-unwind emergency-unwind
     :yield/claim-deferred claim-deferred}}))

(def liquid-lending-module
  (make-liquid-lending-module :yield.provider/liquid-lending))
