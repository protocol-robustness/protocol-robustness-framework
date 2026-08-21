(ns resolver-sim.yield.partial-fill
  "First-class partial-fill settlement decision model.

   `calculate-fulfillment` returns a structured settlement decision map
   rather than a simple scalar balance update. Supports pro-rata,
   principal-first, and waterfall fill policies with exact ratio arithmetic
   and configurable quantization.

   Default policy:
     {:mode :waterfall
      :fill-order [:principal :realized-yield :deferred-yield]
      :unrealized-yield-treatment :not-claimable
      :residual-treatment :defer
      :post-partial-fill-accrual :accrue-residual-as-unrealized
      :rounding-policy :floor-and-carry}"
  (:require [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.economics.payoffs :as payoffs]
            [resolver-sim.pro-rata.allocation :as pro-rata]
            [resolver-sim.pro-rata.evidence :as pro-rata-evidence]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.yield.exact-math :as m]
            [resolver-sim.yield.position :as pos]
            [resolver-sim.yield.pro-rata-propagation-policy :as propagation-policy]
            [resolver-sim.yield.token :as tok]
            [resolver-sim.util.attribution :as attr]
            [resolver-sim.execution.context :as execution-context]
            [resolver-sim.execution.parallel :as parallel]
            [resolver-sim.io.event-evidence :as evidence]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:private schema-version (evcfg/schema :partial-fill-decision))

(def default-partial-fill-policy
  {:mode :waterfall
   :fill-order [:principal :realized-yield :deferred-yield]
   :unrealized-yield-treatment :not-claimable
   :residual-treatment :defer
   :post-partial-fill-accrual :accrue-residual-as-unrealized
   :rounding-policy :floor-and-carry})

(def default-settlement-decision
  {:settlement-mode :full-fill
   :requested {}
   :filled {}
   :deferred {}
   :haircut {}
   :unrealized {}
   :policy default-partial-fill-policy
   :evidence {:schema-version schema-version}})

(defn- fill-order-set
  "Convert fill-order vector to a set for membership checks."
  [fill-order]
  (set fill-order))

(defn- position-bucket
  "Get a position's value for a given bucket key."
  [pos bucket]
  (case bucket
    :principal        (long (:principal pos 0))
    :realized-yield   (long (:realized-yield pos 0))
    :unrealized-yield (max 0 (long (:unrealized-yield pos 0)))
    :deferred-yield   (long (:deferred-yield pos 0))
    0))

(defn- position-bucket-name
  [bucket]
  (case bucket
    :principal "principal"
    :realized-yield "realized_yield"
    :unrealized-yield "unrealized_yield"
    :deferred-yield "deferred_yield"
    "unknown"))

(defn- normalize-token [token]
  (tok/normalize token))

(defn- sum-requested
  [requested]
  (reduce + 0 (map long (vals requested))))

(defn- items-from-rows
  "Convert rows vector into payoffs-compatible items with weight and cap."
  [rows]
  (mapv (fn [r]
          {:id (:key r)
           :weight (or (:weight r) (long (:owed r)))
           :cap (let [c (:cap r)]
                  (if (some? c) (min (long (:owed r)) c) (long (:owed r))))})
        rows))

(defn- allocate-shared-withdrawal-rows
  "Adapt shared-withdrawal rows to the mechanism API while retaining the
   historical allocator result shape consumed by propagation evidence.

   The per-row cap is clamped to `min(owed, cap)` BEFORE allocation, so the
   mechanism's effective demand for a row is `min(requested_i, cap_i)` and the
   aggregate filled total is `min(available, Σ effective-demand)` (see
   calculate-fulfillment-pro-rata)."
  [available-liquidity rows rounding-policy & [progress-atom parallelism on-progress quiescence-timeout-seconds]]
  (let [row-id (fn [row]
                 [:shared-withdrawal-row
                  (str (:obligation-id row))
                  (str (:source-position-id row))
                  (str (:key row))])
        mechanism-result
        (pro-rata/allocate
         {:schema-version "pro-rata-allocation-request.v1"
          :mechanism/version 1
          :allocation/id [:shared-withdrawal-allocation
                          (mapv row-id rows)]
          :available available-liquidity
          :rows (mapv (fn [row]
                        {:row/id (row-id row)
                         :obligation/id (:obligation-id row)
                         :requested (long (:owed row))
                         :weight (long (or (:weight row) (:owed row)))
                         :cap (let [cap (:cap row)]
                                (if (some? cap)
                                  (min (long (:owed row)) (long cap))
                                  (long (:owed row))))})
                      rows)
          ;; P1 (recorded): the rows mechanism normalizes every non-:floor policy
          ;; (including :floor-and-carry and :principal-protective-floor) to the
          ;; :largest-remainder allocator. Both are :bounded-carry semantics, so
          ;; the closed-form verifier's rounding-semantics classification stays
          ;; consistent (rounding-fairness max-error 1, residual 0). But the
          ;; artifact must eventually distinguish the REQUESTED policy keyword
          ;; from the EFFECTIVE allocation algorithm (e.g. an
          ;; :effective-rounding field), so future verifier code does not infer
          ;; behavior from the policy keyword alone.
          :rounding-policy (if (= :floor rounding-policy) :floor :largest-remainder)
          :tie-break-policy :canonical-row-id
          :redistribution-policy :redistribute-cap-excess
          :progress-atom progress-atom
          :on-progress on-progress
          ;; Runtime-only execution setting; excluded by pro-rata/allocation
          ;; from the canonical request, evidence, and roots.
          :parallelism parallelism
          :execution/quiescence-timeout-seconds quiescence-timeout-seconds})
        by-id (into {} (map (juxt :row/id identity) (:rows mechanism-result)))
        allocations (mapv (fn [row]
                            (let [allocated-row (get by-id (row-id row))]
                              {:id (:key row)
                               :allocated (:allocated allocated-row)
                               :unmet (:unmet allocated-row)
                               :weight (:weight allocated-row)
                               :cap (:cap allocated-row)}))
                          rows)]
    {:allocations allocations
     :total-allocated (:allocated-total mechanism-result)
     :total-unmet (reduce + 0 (map :unmet allocations))
     :remainder (:unallocated-residual mechanism-result)
     :redistribution (:redistribution mechanism-result)
     :mechanism-result mechanism-result}))

(defn- make-evidence
  [policy available-liquidity total-requested shortage fill-mode & [extra]]
  (merge {:schema-version schema-version
          :available-liquidity available-liquidity
          :total-requested total-requested
          :shortage shortage
          :fill-mode fill-mode
          :rounding-policy (:rounding-policy policy)
          :fill-order (:fill-order policy)}
         (when extra (assoc extra :allocation-rows (:rows extra)))))

(defn- row-evidence
  "Build a row-evidence entry from a row map and a filled-amount lookup.
   Includes fill-ratio (filled/owed as a double) for verifiability."
  [row filled]
  (let [k (:key row)
        owed (long (:owed row))
        f (long (get filled k 0))
        ;; Reject malformed rows rather than masking an over-allocation: a row
        ;; can never legitimately fill more than its obligation. Surfacing the
        ;; overshoot as an integrity error beats silent `max 0` clamping.
        _ (when (> f owed)
            (throw (ex-info "Allocation overshoot: filled exceeds obligation"
                            {:obligation owed :filled f})))
        d (max 0 (- owed f))
        cap (:cap row)
        effective-cap (if (some? cap) (min owed cap) owed)]
    {:key k
     :obligation-id (:obligation-id row)
     :source-position-id (:source-position-id row)
     :owed owed
     :weight (or (:weight row) owed)
     :cap (when cap (long cap))
     :effective-cap (long effective-cap)
     :filled f
     :final-allocation f
     :deferred d
     :fill-ratio (if (pos? owed) (double (/ f owed)) 0.0)
     :cap-hit? (if (some? cap) (>= f effective-cap) false)}))

(defn- residual-reason
  [unallocated allocation]
  (let [passes (get-in allocation [:redistribution :passes] [])]
    (when (pos? unallocated)
      (cond
        (get-in allocation [:redistribution :iteration-limit-reached?]) :iteration-limit-reached
        (every? (fn [{:keys [allocated cap]}]
                  (and (some? cap) (>= allocated cap)))
                (:allocations allocation)) :all-participants-cap-constrained
        (some #(seq (:capped-ids %)) passes) :all-participants-cap-constrained
        :else :rounding-residual-unallocated))))

(defn- rounding-tie-key
  "Ascending identity ordering used to break rounding-rank TIES (equidistant
   fractional remainders where the largest-remainder carry must choose among
   several recipients). Reuses the pro-rata mechanism's :tie-break-policy
   :canonical-row-id contract (ascending `canonical-id-key`), so the rows
   mechanism, the non-rows path, and this verifier all resolve a tie to the SAME
   carry recipient — the carry lands on the single lowest canonical id among the
   tied claimants."
  [k]
  (pro-rata/canonical-id-key k))

(defn- rounding-rank-key
  "Comparator key for descending largest-remainder ranking: larger fractional
   remainder first; equidistant ties resolved by ascending canonical id. The
   producer orders claims by the same `rounding-tie-key`, so taking leading
   carries from this ranking yields the identical recipients."
  [[k v]]
  [(- (long (:fraction-remainder v))) (rounding-tie-key k)])

(defn calculate-fulfillment-pro-rata
  "Pro-rata fill: each claim bucket receives a proportional share of the available
   liquidity. Exact ratios computed, then quantized via configured rounding policy.

   Locked aggregate theorem (with :rows / caps): when per-row effective caps
   (effective-cap_i = min(requested_i, cap_i)) are present, the filled total is
     Σ filled = min(available, Σ effective-demand_i)
   NOT min(available, Σ requested) — if the caps bind below both the requests
   and the pool, the surplus is left unallocated/residual and rows never exceed
   their caps.  The universal safety bound Σ filled <= available holds in all
   cases.  `check-shared-withdrawal-conservation` asserts the exact theorem.

   Optional opts:
     :rows — vector of {:key k :owed v :weight w :cap c} for decoupled
             weight/cap allocation. When absent, weight and cap are both
             derived from the requested amount (existing behavior)."
  [available-liquidity requested policy & [opts]]
  (let [rows (:rows opts)
        progress-atom (:progress-atom opts)
        on-progress (:on-progress opts)
         ;; Runtime-only; never copied into the decision policy or evidence.
        parallelism (:execution/claimant-parallelism opts)
        quiescence-timeout-seconds (:execution/quiescence-timeout-seconds opts)
        total (if rows
                (reduce + 0 (map #(long (:owed %)) rows))
                (sum-requested requested))
        shortage (max 0 (- total available-liquidity))]
    (if (and (zero? shortage) (not rows))
      ;; Full-fill: only when no rows (backward compat) or shortage is truly zero.
      ;; When rows are present with caps, always go through the capped allocator
      ;; to respect per-row caps even when total liquidity is sufficient.
      {:settlement-mode :full-fill
       :requested requested
       :filled requested
       :deferred {}
       :haircut {}
       :unrealized {}
       :policy policy
       :evidence (make-evidence policy available-liquidity total 0 :pro-rata)}
      (if rows
        ;; Canonical shared-withdrawal rows use the public mechanism boundary.
        ;; The compatibility view below preserves existing propagation evidence.
        (let [rounding-policy (:rounding-policy policy :floor-and-carry)
              alloc (allocate-shared-withdrawal-rows available-liquidity rows rounding-policy progress-atom parallelism on-progress quiescence-timeout-seconds)
              filled (into {} (map (fn [a] [(:id a) (:allocated a)]) (:allocations alloc)))
              row-evidence (mapv #(row-evidence % filled) rows)
              deferred (into {} (map (fn [r] [(:key r) (:deferred r)]) row-evidence))
              unallocated (max 0 (- available-liquidity (:total-allocated alloc 0)))
              mechanism-evidence (pro-rata-evidence/mechanism-evidence-artifact
                                  (:mechanism-result alloc))]
          {:settlement-mode :partial-fill
           :requested (into {} (map (fn [r] [(:key r) (long (:owed r))]) rows))
           :filled filled
           :deferred deferred
           :haircut {}
           :unrealized {}
           :policy policy
           :evidence (assoc (make-evidence policy available-liquidity total shortage :pro-rata)
                            :allocation-detail (select-keys alloc [:total-allocated :total-unmet :remainder])
                            :allocation-mechanism (select-keys (:mechanism-result alloc)
                                                               [:schema-version :mechanism
                                                                :allocation/id :allocation/hash])
                            :allocation-mechanism-evidence mechanism-evidence
                            :allocation-rows row-evidence
                            :allocation-passes (get-in alloc [:redistribution :passes] [])
                            :unallocated-residual unallocated
                            :residual-reason (residual-reason unallocated alloc)
                            :redistribution (:redistribution alloc))})
        ;; Backward compatible path: derive weight/cap from requested
        (let [claims (mapv (fn [[k v]] {:key k :amount (long v)})
                           (sort-by (comp rounding-tie-key first) (seq requested)))
              rounding-policy (:rounding-policy policy :floor-and-carry)
              alloc (case rounding-policy
                      :floor (m/floor-alloc available-liquidity claims)
                      :largest-remainder (m/largest-remainder-alloc available-liquidity claims)
                      :principal-protective-floor (m/principal-protective-floor-alloc
                                                   available-liquidity claims
                                                   (fn [c] (= :principal (:key c))))
                      :adversarial-rounding (m/adversarial-rounding available-liquidity claims)
                      (m/floor-and-carry-alloc available-liquidity claims))
              filled (into {} (map (fn [a] [(:key a) (:filled a)]) (:allocations alloc)))
              deferred (into {} (map (fn [[k v]] [k (max 0 (- (long v) (long (get filled k 0))))])
                                     requested))]
          {:settlement-mode :partial-fill
           :requested requested
           :filled filled
           :deferred deferred
           :haircut {}
           :unrealized {}
           :policy policy
           :evidence (assoc (make-evidence policy available-liquidity total shortage :pro-rata)
                            :allocation-detail (select-keys alloc [:total-available-units
                                                                   :total-allocated-units
                                                                   :shortage-units
                                                                   :carry]))})))))

(defn calculate-fulfillment-principal-first
  "Principal-first fill: principal claims are satisfied in full before any
   yield claims are filled.

   Optional opts:
     :rows — vector of {:key k :owed v :weight w :cap c} for decoupled
             weight/cap allocation. When absent, weight and cap are both
             derived from the requested amount (existing behavior)."
  [available-liquidity requested policy & [opts]]
  (let [rows (:rows opts)
        progress-atom (:progress-atom opts)]
    (if rows
      ;; Decoupled rows path
      (let [principal-row (first (filter #(= :principal (:key %)) rows))
            yield-rows (remove #(= :principal (:key %)) rows)
            principal-owed (long (if principal-row (:owed principal-row) 0))
            principal-cap (:cap principal-row)
            principal-effective-cap (if (some? principal-cap)
                                      (min principal-owed principal-cap)
                                      principal-owed)
            principal-filled (min principal-effective-cap available-liquidity)
            principal-deferred (max 0 (- principal-owed principal-filled))
            remaining (- available-liquidity principal-filled)
            yield-total (reduce + 0 (map #(long (:owed %)) yield-rows))
            total (+ principal-owed yield-total)
            shortage (max 0 (- total available-liquidity))]
        ;; When rows are present, always use the capped path
        ;; (full-fill shortcut would ignore per-row caps)
        (let [yield-items (items-from-rows yield-rows)
              rounding-policy (:rounding-policy policy :floor-and-carry)
              yield-alloc (when (and (pos? remaining) (seq yield-items))
                            (payoffs/allocate-pro-rata-with-redistribution
                             {:amount remaining
                              :items yield-items
                              :id-fn :id :weight-fn :weight :cap-fn :cap
                              :rounding (if (#{:floor :floor-with-largest-remainder} rounding-policy)
                                          rounding-policy
                                          :floor-with-largest-remainder)
                              :progress-atom progress-atom}))
              yield-filled (when yield-alloc
                             (into {} (map (fn [a] [(:id a) (:allocated a)]) (:allocations yield-alloc))))
              filled (cond-> {}
                       (pos? principal-filled)
                       (assoc :principal principal-filled)
                       yield-filled
                       (merge yield-filled))
              row-evidence (mapv #(row-evidence % filled) rows)
              deferred (into {} (map (fn [r] [(:key r) (:deferred r)]) row-evidence))]
          {:settlement-mode :partial-fill
           :requested (into {} (map (fn [r] [(:key r) (long (:owed r))]) rows))
           :filled filled
           :deferred deferred
           :haircut {}
           :unrealized {}
           :policy policy
           :evidence (assoc (make-evidence policy available-liquidity total shortage :principal-first)
                            :allocation-rows row-evidence
                            :allocation-detail (when yield-alloc
                                                 (select-keys yield-alloc [:total-allocated :total-unmet :remainder]))
                            :redistribution (:redistribution yield-alloc))}))
      ;; Backward compatible path (no explicit rows)
      (let [principal-requested (long (get requested :principal 0))
            principal-filled (min principal-requested available-liquidity)
            remaining (- available-liquidity principal-filled)
            yield-requested (dissoc requested :principal)
            yield-total (sum-requested yield-requested)
            shortage (max 0 (- (+ principal-requested yield-total) available-liquidity))]
        (if (zero? shortage)
          {:settlement-mode :full-fill
           :requested requested
           :filled requested
           :deferred {}
           :haircut {}
           :unrealized {}
           :policy policy
           :evidence (make-evidence policy available-liquidity (+ principal-requested yield-total) 0 :principal-first)}
          (let [principal-deferred (max 0 (- principal-requested principal-filled))
                filled (cond-> {:principal principal-filled}
                         (pos? remaining)
                         (merge (let [claims (mapv (fn [[k v]] {:key k :amount (long v)})
                                                   (seq yield-requested))
                                      rounding-policy (:rounding-policy policy :floor-and-carry)
                                      alloc (case rounding-policy
                                              :largest-remainder (m/largest-remainder-alloc remaining claims)
                                              :principal-protective-floor (m/principal-protective-floor-alloc
                                                                           remaining claims
                                                                           (fn [c] (= :principal (:key c))))
                                              :adversarial-rounding (m/adversarial-rounding remaining claims)
                                              :floor (m/floor-alloc remaining claims)
                                              (m/floor-and-carry-alloc remaining claims))]
                                  (into {} (map (fn [a] [(:key a) (:filled a)]) (:allocations alloc))))))
                deferred (merge (when (pos? principal-deferred) {:principal principal-deferred})
                                (into {} (map (fn [[k v]] [k (max 0 (- (long v) (long (get filled k 0))))])
                                              yield-requested)))]
            {:settlement-mode :partial-fill
             :requested requested
             :filled filled
             :deferred deferred
             :haircut {}
             :unrealized {}
             :policy policy
             :evidence (make-evidence policy available-liquidity (+ principal-requested yield-total) shortage :principal-first)}))))))

(defn- waterfall-allocate-rows
  "Core waterfall-with-rows allocation.
   Processes buckets in fill-order, allocating to each bucket's rows
   respecting per-row caps, with remaining liquidity flowing to the next bucket.
   Returns {:filled {} :row-evidence [] :redistributions [{:bucket k :redistribution m}]}."
  [available-liquidity rows fill-order policy progress-atom]
  (let [rounding-policy (:rounding-policy policy :floor-and-carry)
        bucket->rows (group-by :key rows)]
    (loop [remaining available-liquidity
           filled {}
           all-row-evidence []
           all-redistributions []
           buckets fill-order]
      (if (empty? buckets)
        {:filled filled
         :row-evidence (vec all-row-evidence)
         :redistributions all-redistributions}
        (if (zero? remaining)
           ;; Remaining buckets' rows included as zero-filled evidence
          (let [remaining-rows (mapcat #(get bucket->rows % []) buckets)
                zero-evidence (mapv (fn [r] (row-evidence r {})) remaining-rows)]
            {:filled filled
             :row-evidence (vec (into all-row-evidence zero-evidence))
             :redistributions all-redistributions})
           ;; Else branch: process the current bucket
          (let [bucket (first buckets)
                bucket-rows (get bucket->rows bucket [])
                bucket-total (reduce + 0
                                     (map (fn [r]
                                            (let [c (:cap r)]
                                              (if (some? c)
                                                (min (long (:owed r)) c)
                                                (long (:owed r)))))
                                          bucket-rows))]
            (if (zero? bucket-total)
              (recur remaining filled all-row-evidence all-redistributions (rest buckets))
              (let [bucket-items (items-from-rows bucket-rows)
                    bucket-alloc (payoffs/allocate-pro-rata-with-redistribution
                                  {:amount (min remaining bucket-total)
                                   :items bucket-items
                                   :id-fn :id :weight-fn :weight :cap-fn :cap
                                   :rounding (if (#{:floor :floor-with-largest-remainder} rounding-policy)
                                               rounding-policy
                                               :floor-with-largest-remainder)
                                   :progress-atom progress-atom})
                    bucket-filled (into {} (map (fn [a] [(:id a) (:allocated a)]) (:allocations bucket-alloc)))
                    bucket-filled-total (reduce + 0 (vals bucket-filled))
                    bucket-row-evidence (mapv #(row-evidence % bucket-filled) bucket-rows)]
                (recur (- remaining bucket-filled-total)
                       (merge filled bucket-filled)
                       (into all-row-evidence bucket-row-evidence)
                       (conj all-redistributions {:bucket bucket
                                                  :redistribution (:redistribution bucket-alloc)})
                       (rest buckets))))))))))

(defn calculate-fulfillment-waterfall
  "Waterfall fill: claims are satisfied in strict fill-order priority.
   Each bucket in :fill-order is filled to exhaustion before moving to the next.
   Exact amounts are quantized via configured rounding policy.

   Optional opts:
     :rows — vector of {:key k :owed v :weight w :cap c} for decoupled
             weight/cap allocation per bucket. When absent, weight and cap are
             derived from the requested amount (existing behavior)."
  [available-liquidity requested policy & [opts]]
  (let [fill-order (:fill-order policy [:principal :realized-yield :deferred-yield])
        rows (:rows opts)
        progress-atom (:progress-atom opts)
        total (if rows
                (reduce + 0 (map #(long (:owed %)) rows))
                (sum-requested requested))
        shortage (max 0 (- total available-liquidity))]
    (if rows
      ;; Rows-with-caps path: always go through the capped allocator
      ;; to respect per-row caps even when total liquidity is sufficient
      (let [result (waterfall-allocate-rows available-liquidity rows fill-order policy progress-atom)
            row-evidence (:row-evidence result)
            filled (:filled result)
            deferred (into {} (map (fn [r] [(:key r) (:deferred r)]) row-evidence))
            processed-keys (set (keys filled))
            all-row-keys (set (map :key rows))
            unprocessed (clojure.set/difference all-row-keys processed-keys)
            deferred (reduce (fn [acc r]
                               (assoc acc (:key r) (long (:owed r))))
                             deferred
                             (filter #(contains? unprocessed (:key %)) rows))
            settlement-mode (if (zero? shortage) :full-fill :partial-fill)]
        {:settlement-mode settlement-mode
         :requested (into {} (map (fn [r] [(:key r) (long (:owed r))]) rows))
         :filled filled
         :deferred deferred
         :haircut {}
         :unrealized {}
         :policy policy
         :evidence (merge (make-evidence policy available-liquidity total shortage :waterfall)
                          {:allocation-rows row-evidence
                           :bucket-redistributions (:redistributions result)})})
      ;; Backward compatible path (no explicit rows)
      (if (zero? shortage)
        {:settlement-mode :full-fill
         :requested requested
         :filled requested
         :deferred {}
         :haircut {}
         :unrealized {}
         :policy policy
         :evidence (make-evidence policy available-liquidity total 0 :waterfall)}
        (loop [remaining available-liquidity
               filled {}
               deferred {}
               buckets fill-order]
          (if (or (zero? remaining) (empty? buckets))
            (let [all-keys (keys requested)
                  all-deferred (merge deferred
                                      (into {} (for [k all-keys
                                                     :when (not (contains? filled k))]
                                                 [k (long (get requested k 0))])))]
              {:settlement-mode :partial-fill
               :requested requested
               :filled filled
               :deferred all-deferred
               :haircut {}
               :unrealized {}
               :policy policy
               :evidence (make-evidence policy available-liquidity total shortage :waterfall)})
            (let [bucket (first buckets)
                  bucket-amount (long (get requested bucket 0))
                  filled-amount (min bucket-amount remaining)
                  new-remaining (- remaining filled-amount)
                  deferred-amount (max 0 (- bucket-amount filled-amount))]
              (recur new-remaining
                     (if (pos? filled-amount) (assoc filled bucket filled-amount) filled)
                     (if (pos? deferred-amount) (assoc deferred bucket deferred-amount) deferred)
                     (rest buckets)))))))))

(defn calculate-fulfillment
  "Calculate the structured settlement decision for a withdrawal against
   available liquidity.

   Args:
     available-liquidity - total base units available for withdrawal
     position            - the yield position map
     policy              - (optional) partial-fill policy map, merged with defaults
     opts                - (optional) additional options {:available-ratio ...}

   Returns a structured settlement decision:
     {:settlement-mode :partial-fill | :full-fill
      :requested        {bucket amount ...}
      :filled           {bucket amount ...}
      :deferred         {bucket amount ...}
      :haircut           {bucket amount ...}
      :unrealized        {bucket amount ...}
      :policy            {...}
      :evidence          {...}}

   Settlement modes:
     :pro-rata       — proportional allocation across all buckets
     :principal-first — principal first, then pro-rata on yield
     :waterfall      — strict priority order (:fill-order)

   The :unrealized-yield-treatment policy controls whether unrealized yield
   is included in requested (:claimable) or excluded (:not-claimable)."
  ([available-liquidity position]
   (calculate-fulfillment available-liquidity position default-partial-fill-policy))
  ([available-liquidity position policy]
   (calculate-fulfillment available-liquidity position policy {}))
  ([available-liquidity position policy opts]
   (let [policy (merge default-partial-fill-policy policy)
         available (max 0 (long available-liquidity))
         include-unrealized? (= :claimable (:unrealized-yield-treatment policy))
         requested (cond-> {:principal (pos/claimable-principal position)
                            :realized-yield (pos/claimable-realized-yield position)
                            :deferred-yield (long (:deferred-yield position 0))}
                     include-unrealized?
                     (assoc :unrealized-yield (pos/claimable-unrealized-yield position)))
         requested (into {} (remove (fn [[_ v]] (zero? v)) requested))
         mode (:mode policy :waterfall)]
     (if (empty? requested)
       {:settlement-mode :full-fill
        :requested {}
        :filled {}
        :deferred {}
        :haircut {}
        :unrealized {}
        :policy policy
        :evidence {:schema-version schema-version
                   :available-liquidity available
                   :total-requested 0
                   :shortage 0
                   :fill-mode mode}}
       (case mode
         :pro-rata        (calculate-fulfillment-pro-rata available requested policy
                                                          (select-keys opts [:rows :progress-atom :execution/claimant-parallelism :execution/quiescence-timeout-seconds]))
         :principal-first (calculate-fulfillment-principal-first available requested policy
                                                                 (select-keys opts [:rows :progress-atom]))
         :waterfall       (calculate-fulfillment-waterfall available requested policy
                                                           (select-keys opts [:rows :progress-atom])))))))

(defn partial-fill?
  "True if the settlement decision represents a partial fill."
  [decision]
  (= :partial-fill (:settlement-mode decision)))

(defn partial-fill-outstanding?
  "True if a settlement decision leaves unresolved deferred/haircut consequences
   that keep the position unwinding (its full entitlement was not paid out).
   Derived from the authoritative decision buckets: zero-fill (deferred-all),
   deferred-only, haircut-only, and mixed outcomes are outstanding; a full-fill
   is not. Distinct from the sticky `partial-fill?` EVENT — a position that was
   once partially filled then later fully resolved has outstanding? false but
   its historical event never clears."
  [decision]
  (boolean (or (some pos? (vals (:deferred decision)))
               (some pos? (vals (:haircut decision))))))

(defn decision-artifact
  "Build a stable first-class artifact for a partial-fill settlement decision.
   The artifact is content-addressed so downstream consumers can link the same
   decision across world state, snapshots, and evidence."
  ([position decision]
   (decision-artifact position decision {}))
  ([position decision {:keys [decision-source position-id extra]
                       :or {decision-source :yield-withdraw}}]
   (let [owner-id (or position-id (:owner/id position) (-> (pos/position-identity position) second))
         token (normalize-token (or (:token position) (get-in position [:position/id 3])))
         base (merge {:schema-version schema-version
                      :artifact/kind :yield/partial-fill-decision
                      :decision/source decision-source
                      :position/id owner-id
                      :module/id (:module/id position)
                      :token token
                      :settlement-mode (:settlement-mode decision)
                      :requested (:requested decision)
                      :filled (:filled decision)
                      :deferred (:deferred decision)
                      :haircut (:haircut decision)
                      :unrealized (:unrealized decision)
                      :policy (:policy decision)
                      :evidence (:evidence decision)}
                     extra)
         proj-base (hc/project-committable-content base)
         commitment (hc/canonical-commitment :evidence-record proj-base)
         decision-hash (:canonical/hash commitment)]
     (assoc base
            :decision/id (str "partial-fill-" (subs decision-hash 7 (min (count decision-hash) 23)))
            :decision/hash decision-hash
            ;; JSON replay output cannot distinguish string claim keys from
            ;; keyword claim buckets. Preserve the exact typed hash preimage
            ;; for independent post-persistence verification.
            :decision/preimage (pr-str base)
            ;; Portable canonical commitment: hex of canonical-bytes(projected
            ;; base), so a cross-language verifier recomputes
            ;; sha256(EVIDENCE_RECORD_V1 || hex-decode(bytes)) == hash without
            ;; needing the Clojure printer preimage.
            :decision/canonical-bytes (:canonical/bytes commitment)
            :decision/canonical-hash decision-hash))))

(defn decision-hash-valid?
  "Verify the exact decision artifact preimage that downstream propagation
   references. This does not depend on caller-provided position state."
  [decision]
  (let [body (dissoc decision :decision/id :decision/hash :decision/preimage
                     :decision/canonical-bytes :decision/canonical-hash)
        proj (hc/project-committable-content body)
        recomputed (:canonical/hash (hc/canonical-commitment :evidence-record proj))]
    (and (= recomputed (:decision/hash decision))
         (hc/canonical-commitment-valid?
          :evidence-record proj
          {:canonical/bytes (:decision/canonical-bytes decision)
           :canonical/hash (:decision/canonical-hash decision)}))))

;; ---------------------------------------------------------------------------
;; Withdrawal budget provenance (L1) — the budget is a computed function of
;; committed world inputs, never a bare attested scalar.
;;
;; A producer that commits `available = 100, allocated = 80, residual = 20` when
;; the real pool held 60 satisfies every allocation-level equation.  Closing
;; that requires the committed budget to be re-derivable from committed source
;; custody, the committed available-ratio, and the committed evaluation point:
;;     B = canonical-liquidity-available(source-custody, available-ratio)
;; with both inputs bound by content-addressed roots over the same evaluation
;; context.  All three withdrawal modes commit this provenance so budget
;; derivation is one provenance chain, not three unrelated checks.
;; ---------------------------------------------------------------------------

(defn canonical-liquidity-available
  "Canonical mapping from committed source custody + available-ratio to the
   liquidity budget B a withdrawal may draw on.  Single definition shared by the
   :single-position / :fcfs-sequential / :pro-rata modes."
  [source-custody available-ratio]
  (max 0 (long (* (long source-custody) (double available-ratio)))))

(defn liquidity-budget-provenance
  "Commit the derivation of a withdrawal's liquidity budget from world state:
     B = canonical-liquidity-available(source-custody, available-ratio)
   `source-custody` is the pre-withdrawal custody the mode draws on (escrow
   custody slice for single, the token pool for batch/shared); `available-ratio`
   is the market ratio used; `evaluation-context` pins the world state at which
   the withdrawal was authorized ({:module/id :token :at :application-order}).
   Returns a map of :liquidity/* keys to merge into the committing artifact
   (decision extra / ledger record)."
  [{:keys [module-id token source-custody available-ratio evaluation-context]}]
  (let [custody (long source-custody)
        ratio (double available-ratio)]
    (assoc {:liquidity/schema-version "liquidity-budget-provenance.v1"
            :token token
            :module/id module-id
            :liquidity/source-custody custody
            :liquidity/available-ratio ratio
            :liquidity/available (canonical-liquidity-available custody ratio)
            :liquidity/evaluation-context evaluation-context}
           :liquidity/source-state-root
           (hc/hash-with-intent {:hash/intent :projection-artifact}
                                {:kind :liquidity/source-state
                                 :token token :source-custody custody
                                 :evaluation-context evaluation-context})
           :liquidity/market-state-root
           (hc/hash-with-intent {:hash/intent :projection-artifact}
                                {:kind :liquidity/market-state
                                 :token token :available-ratio ratio
                                 :evaluation-context evaluation-context}))))

(defn liquidity-budget-provenance-valid?
  "Recompute the budget from the committed :liquidity/source-custody and
   :liquidity/available-ratio and confirm the committed :liquidity/available and
   both content-addressed roots reconcile.  Returns {:holds? bool :violations [...]}."
  [provenance]
  (let [token (:token provenance)
        custody (long (or (:liquidity/source-custody provenance) -1))
        ratio (double (or (:liquidity/available-ratio provenance) -1.0))
        available (long (or (:liquidity/available provenance) -1))
        ctx (:liquidity/evaluation-context provenance)
        expected (canonical-liquidity-available custody ratio)
        expected-source-root (hc/hash-with-intent {:hash/intent :projection-artifact}
                                                  {:kind :liquidity/source-state
                                                   :token token :source-custody custody
                                                   :evaluation-context ctx})
        expected-market-root (hc/hash-with-intent {:hash/intent :projection-artifact}
                                                  {:kind :liquidity/market-state
                                                   :token token :available-ratio ratio
                                                   :evaluation-context ctx})
        violations (cond-> []
                     (not= expected available)
                     (conj {:kind ::budget-recompute-mismatch
                            :expected expected :observed available
                            :source-custody custody :available-ratio ratio})
                     (not= expected-source-root (:liquidity/source-state-root provenance))
                     (conj {:kind ::source-state-root-mismatch
                            :expected expected-source-root
                            :observed (:liquidity/source-state-root provenance)})
                     (not= expected-market-root (:liquidity/market-state-root provenance))
                     (conj {:kind ::market-state-root-mismatch
                            :expected expected-market-root
                            :observed (:liquidity/market-state-root provenance)}))]
    {:holds? (empty? violations) :violations (vec violations)}))

(defn residual-policy-root
  "Content-addressed root over a committed residual disposition policy."
  [{:keys [destination] :as policy}]
  (hc/hash-with-intent {:hash/intent :projection-artifact}
                       (assoc policy :kind :liquidity/residual-policy
                              :schema-version "liquidity-residual-policy.v1")))

(defn attach-decision-artifact
  "Attach a partial-fill decision artifact to world state under a stable map."
  [world artifact]
  (assoc-in world [:yield/partial-fill-decisions (:decision/id artifact)] artifact))

(defn application-hash-preimage
  "Canonical application projection. Position snapshots may contain exact
   ratios, which the canonical hash ABI intentionally rejects; the applied
   accounting, propagation binding, and participant deltas remain committed
   while snapshots are reconciled by the state invariants.
   
   Excludes both :application/hash and :application/output, which are
   meta-artifacts about the application record itself."
  [application]
  (-> application
      (dissoc :application/hash :application/output)
      (update :participants
              (fn [participants]
                (mapv #(dissoc % :position-before :position-after)
                      (or participants []))))))

(defn application-hash
  [application]
  (hash-ref/sha256-ref (hc/hash-with-intent {:hash/intent :evidence-record}
                                            (application-hash-preimage application))))

(defn ledger-run-root
  "Content-addressed root of the exact execution context a withdrawal ledger is
   bound to: run id, execution id, scenario id, and a root over the world
   parameters (policy/config context).

   Committing this root (rather than bare identifiers) turns the ledger claim
   from 'these withdrawals belong to run X / execution Y' into 'these
   withdrawals occurred in this exact execution world' — the world-level analog
   of a benchmark run-root."
  [world]
  (application-hash
   {:schema-version "withdrawal-run-root.v1"
    :run/id (:run/id world)
    :execution/id (:execution/id world)
    :scenario/id (get-in world [:params :scenario-id])
    :params-root (application-hash (or (:params world) {}))}))

(defn ledger-request-set-root
  "Content-addressed root of the withdrawal subject: the principals and their
   requested amounts.

   Membership semantics: the root is order-independent (sorted) because request
   SET identity does not depend on processing order. Multiplicity is PRESERVED,
   not collapsed — `sort` keeps duplicates, so a malformed population
   [(a,10),(a,10)] commits differently from [(a,10)] and cannot alias a
   legitimate one at the input-commitment boundary. Uniqueness is an invariant
   (see :withdrawal-duplicate-owner), never a preprocessing step here.

   It distinguishes one withdrawal from another within the same run/execution,
   so a ledger from a different withdrawal cannot be substituted in merely
   because the enclosing run matches."
  [owner-ids rows]
  (application-hash
   {:schema-version "withdrawal-request-set.v1"
    :owner-ids (vec (sort owner-ids))
    :requests (vec (sort-by (juxt :owner-id :requested)
                            (map #(select-keys % [:owner-id :requested]) rows)))}))

(defn ledger-request-order-root
  "Content-addressed root of the request ORDER the allocator was obligated to use.

   For FCFS the input ordering is economically material — who is served first
   changes the allocation — so the order is committed as an input, not merely
   reflected in the output rows. Order-preserving (NOT sorted): a different
   request order must commit differently, and the certificate proves 'this was
   the order of requests the allocator was obligated to use'."
  [owner-ids]
  (application-hash
   {:schema-version "withdrawal-request-order.v1"
    :owner-ids (vec owner-ids)}))

(defn ledger-allocation-policy-root
  "Content-addressed root of the allocation policy semantics that turned the
   committed request population into the committed result.

   This closes the loop toward `result = F(request-set, order, capacity,
   policy, params, state-cutpoint)`: the certificate commits not only the
   inputs and the output but the allocator contract that maps one to the
   other."
  [policy]
  (application-hash
   {:schema-version "withdrawal-allocation-policy.v1"
    :policy policy}))

(defn ledger-params-root
  "Content root over the world parameters (policy/config context) at the
   cutpoint."
  [world]
  (application-hash (or (:params world) {})))

(defn ledger-state-cutpoint-root
  "Content-addressed reference to the allocation-relevant world state at the
   cutpoint: positions, indices, and risk/market state.

   This is a state reference, not a timestamp/block/run identifier — two
   withdrawals at the same run and block but different state commit
   differently, and the ledger cannot be composed from state fragments taken
   at different cutpoints.

   Capacity (`:yield/held-balances` / `:total-held`) is intentionally EXCLUDED:
   it is committed separately as the capacity root, and it is recomputed by
   protocol custody sync after a withdrawal (so committing it here would make
   the recompute diverge from the settlement-time reference)."
  [world]
  (let [proj (hc/project-committable-content
              {:yield/positions (:yield/positions world)
               :yield/indices (:yield/indices world)
               :yield/risk (:yield/risk world)
               :yield/shortfall-models (:yield/shortfall-models world)
               :yield/withdrawal-policies (:yield/withdrawal-policies world)})]
    (:canonical/hash (hc/canonical-commitment :evidence-record proj))))

(defn ledger-basis-root
  "Compositional identity of ONE allocation basis: ties the state cutpoint,
   admissible population, request order, capacity, policy, and parameters to a
   single cutpoint, so the ledger binds to one basis rather than independently
   carrying a growing list of roots.

   This prevents cross-state substitution — universe from X, capacity from Y,
   ordering from X, policy from Z — where every individual artifact is valid
   but the composition is temporally incoherent. Constituent roots remain
   committed separately for inspection."
  [m]
  (application-hash
   (merge {:schema-version "withdrawal-allocation-basis.v1"}
          (select-keys m [:state-cutpoint-root :request-set-root
                          :request-order-root :capacity-root
                          :allocation-policy-root :params-root]))))

(defn- normalize-entry
  "Normalize an accounting entry to a sorted map for deterministic
   pr-str representation regardless of key insertion order."
  [entry]
  (into (sorted-map) entry))

(defn canonical-accounting-entries
  "Return a deterministically ordered vector of entries. This is a list
   canonicalization: duplicate entries are retained deliberately.
   
   Entries are sorted by their normalized (key-sorted) pr-str representation
   so that logically identical entries with different key insertion orders
   produce the same canonical ordering and hash."
  [entries]
  (->> entries (map normalize-entry) (sort-by pr-str) vec))

(defn accounting-entry-set-hash
  "Hash the duplicate-preserving canonical accounting-entry list."
  [entries]
  (hash-ref/sha256-ref (hc/hash-with-intent {:hash/intent :evidence-record}
                                            (canonical-accounting-entries entries))))

(defn- canonical-output-participants
  "Project participant credit records with token identity, sorted deterministically.
   Each row commits to the token, participant id, obligation id, and balances."
  [participants]
  (->> (or participants [])
       (mapv (fn [p]
               {:token (get-in p [:withdrawn :token])
                :participant-id (:participant-id p)
                :obligation-id (:obligation-id p)
                :balance-before (get-in p [:withdrawn :before] 0)
                :credit (get-in p [:withdrawn :delta] 0)
                :balance-after (get-in p [:withdrawn :after] 0)}))
       (sort-by (fn [row]
                  [(:token row) (:participant-id row) (:obligation-id row)]))
       vec))

(defn- canonical-output-obligations
  "Project obligation fulfillment records with token identity, sorted deterministically.
   Each obligation row commits to token, participant id, obligation id, and fulfillment breakdown."
  [participants source-token]
  (->> (or participants [])
       (mapv (fn [p]
               {:token source-token
                :participant-id (:participant-id p)
                :obligation-id (:obligation-id p)
                :amount-before (get-in p [:obligation :before] 0)
                :fulfilled (get-in p [:obligation :fulfilled] 0)
                :deferred (get-in p [:obligation :deferred] 0)
                :unmet (get-in p [:obligation :unmet] 0)
                :waived (get-in p [:obligation :waived] 0)
                :amount-after (get-in p [:obligation :after] 0)}))
       (sort-by (fn [row]
                  [(:token row) (:participant-id row) (:obligation-id row)]))
       vec))

(defn- canonical-output-accounting-entries
  "Project accounting entries with explicit token identity, sorted deterministically.
   Preserves entry ids and token binding for each entry."
  [propagation]
  (let [entries (:accounting-entries propagation [])
        entries-with-ids (into [] (map-indexed
                                   (fn [idx entry]
                                     (assoc entry :entry-index idx))
                                   entries))]
    (->> entries-with-ids
         (mapv (fn [entry]
                 {:token (:token entry)
                  :account (:account entry)
                  :participant-id (:participant-id entry)
                  :obligation-id (:obligation-id entry)
                  :entry-index (:entry-index entry)
                  :direction (case (:entry/type entry)
                               :debit :debit
                               :credit :credit
                               :unknown)
                  :amount (:delta entry)}))
         (sort-by (fn [entry]
                    [(:token entry) (:account entry) (:participant-id entry)
                     (:obligation-id entry) (:entry-index entry)]))
         vec)))

(defn- ensure-token!
  "Fail closed when token is missing from a row that requires it."
  [token row reason-code reason-data]
  (when-not token
    (throw (ex-info
            (str "Token missing in output projection: " reason-code)
            (assoc reason-data :reason reason-code))))
  token)

(defn pro-rata-application-output-projection
  "Create the canonical output projection that commits to authoritative application results.
   
   The projection includes:
   - source account with token-dimensioned balance deltas
   - participants with token-dimensioned credit deltas and balances
   - obligations with token-dimensioned fulfillment breakdown
   - accounting entries with explicit token binding
   
   This projection is deterministically ordered and suitable for independent verification."
  [application propagation]
  (let [source (:source-account application)
        source-token (ensure-token! (:token source) source
                                    :application-output-token-missing
                                    {:context :source-account})
        participants (:participants application [])
        accounting-entries (:accounting-entries propagation [])]

    ;; Verify all participants have explicit tokens in their withdrawn records
    (doseq [p participants]
      (ensure-token! (get-in p [:withdrawn :token]) p
                     :application-output-token-missing
                     {:context :participant-withdrawn :participant-id (:participant-id p)}))

    ;; Verify all accounting entries have tokens
    (doseq [entry accounting-entries]
      (ensure-token! (:token entry) entry
                     :application-output-token-missing
                     {:context :accounting-entry :entry-index (.indexOf accounting-entries entry)}))

    {:schema-version "pro-rata-application-output.v1"
     :application
     {:application-key (:application-key application)
      :propagation-hash (get-in application [:propagation/reference :propagation/hash])}
     :source
     {:account (:account source)
      :token source-token
      :balance-before (:before source 0)
      :debit (- (long (:delta source 0)))
      :balance-after (:after source 0)}
     :participants (canonical-output-participants participants)
     :obligations (canonical-output-obligations participants source-token)
     :accounting
     {:entry-count (count accounting-entries)
      :entries (canonical-output-accounting-entries propagation)}}))

(defn pro-rata-application-output-hash
  "Compute the output hash that commits to authoritative application results.
   
   This hash is independent of the application/hash and verifies the semantic
   correctness of the mutation's outputs rather than the application record itself."
  [application propagation]
  (hash-ref/sha256-ref (hc/hash-with-intent
                        {:hash/intent :evidence-record}
                        (pro-rata-application-output-projection application propagation))))

(defn- capped-keys-from-passes
  "Extract participant keys that were cap-constrained in redistribution passes.
   Mechanism row-ids use [:shared-withdrawal-row <obligation-id> <source-position-id> <key>]."
  [passes]
  (set (mapcat (fn [pass]
                 (map #(nth % 3) (:newly-capped-ids pass)))
               passes)))

(defn pro-rata-propagation-artifact
  "Build the authoritative application record for a shared pro-rata decision."
  [decision policy policy-selection]
  (let [policy (propagation-policy/normalize-and-validate policy)
        policy-ref (propagation-policy/policy-reference policy)
        rows (get-in decision [:evidence :allocation-rows] [])
        passes (get-in decision [:evidence :allocation-passes])
        capped-keys (capped-keys-from-passes passes)
        step (get-in decision [:allocation/invocation-context :step])
        idempotency-key (let [components (get-in policy [:idempotency :identity-components])
                              component-values {:calculation-id (:decision/id decision)
                                                :outcome-hash (:decision/hash decision)
                                                :policy-hash (:policy/hash policy)}]
                          (into [:pro-rata-propagation]
                                (map component-values components)))
        participants
        (mapv (fn [{:keys [key obligation-id source-position-id owed effective-cap filled deferred]}]
                (let [fulfilled (long (or filled 0))
                      deferred (long (or deferred 0))
                      owed (long (or owed 0))
                      cap (long (or effective-cap owed))
                      capped? (contains? capped-keys key)
                      initial (if capped? cap 0)
                      redistributed (- fulfilled initial)]
                  {:participant-id key
                   :obligation-before owed
                   :eligible-obligation owed
                   :effective-cap cap
                   :initial-allocation initial
                   :redistributed-in redistributed
                   :final-allocation fulfilled
                   :allocation-applied (zero? deferred)
                   :fulfilled fulfilled
                   :deferred deferred
                   :unmet 0
                   :waived 0
                   :obligation-after deferred
                   :position-status (if (pos? deferred) :partially-deferred :fulfilled)
                   :origin {:obligation-id obligation-id
                            :source-position-id source-position-id
                            :calculation-id (:decision/id decision)
                            :participant-id key
                            :sequence 1}
                   :next-position (when (pos? deferred)
                                    {:position/type (get-in policy [:shortfall :next-position/type])
                                     :position/id key
                                     :position/root-obligation-id obligation-id
                                     :amount deferred
                                     :priority-policy (get-in policy [:priority :propagation-policy])
                                     :next-round-weight-policy (get-in policy [:shortfall :next-round-weight-policy])
                                     :reallocation-policy (get-in policy [:shortfall :next-position/eligibility])})}))
              rows)
        sum-field (fn [field] (reduce + 0 (map #(long (get % field 0)) participants)))
        available (long (get-in decision [:evidence :available-liquidity] 0))
        allocated (sum-field :fulfilled)
        residual (max 0 (- available allocated))
        application-entries (mapv (fn [p]
                                    (let [deferred (:deferred p 0)
                                          fulfilled (:fulfilled p)
                                          delta fulfilled]
                                      {:participant-id (:participant-id p)
                                       :allocation-applied (zero? deferred)
                                       :apparent-application (cond-> {:application-key idempotency-key
                                                                      :accounting-delta delta}
                                                               step (assoc :applied-at-step step))
                                       :fulfilled fulfilled
                                       :shortfall {:amount deferred
                                                   :classification (get-in policy [:shortfall :classification])
                                                   :next-position-ref (some-> p :next-position :position/id)}
                                       :accounting-entry {:account [:participant (:participant-id p) :withdrawn]
                                                          :delta delta}}))
                                  participants)
        accounting-reconciled? (every? (fn [app]
                                         (= (get-in app [:accounting-entry :delta])
                                            (:fulfilled app)))
                                       application-entries)
        base {:schema-version "pro-rata-propagation.v2"
              :calculation-ref (:decision/id decision)
              :outcome-ref (:decision/hash decision)
              :allocation-kind :shared-withdrawal-shortfall
              :allocation/invocation-context (:allocation/invocation-context decision)
              :allocation/reference
              (let [mechanism (get-in decision [:evidence :allocation-mechanism])
                    mechanism-evidence (get-in decision [:evidence :allocation-mechanism-evidence])]
                {:schema-version "pro-rata-allocation-reference.v1"
                 :allocation/id (:allocation/id mechanism)
                 :allocation/hash (:allocation/hash mechanism)
                 :mechanism (:mechanism mechanism)
                 :mechanism-evidence (pro-rata-evidence/evidence-reference mechanism-evidence)
                 :source-evidence {:artifact/id (:decision/id decision)
                                   :artifact/hash (:decision/hash decision)}})
              :token (:token decision)
              :module/id (:module/id decision)
              :propagation-policy policy-ref
              :policy-selection policy-selection
              :participants participants
              :summary {:obligation-before (sum-field :obligation-before)
                        :eligible-obligation (sum-field :eligible-obligation)
                        :available available :allocated allocated :fulfilled allocated
                        :all-allocations-applied (every? #(zero? (:deferred % 0)) participants)
                        :deferred (sum-field :deferred) :unmet 0 :waived 0
                        :obligation-after (sum-field :obligation-after)
                        :unallocated-residual residual
                        :residual-reason (get-in decision [:evidence :residual-reason])}
              :propagation {:policy :defer-shortfall :next-state :withdrawal-claims
                            :reallocation-eligible? true
                            :rounding-propagation-policy (get-in policy [:rounding :propagation-policy])
                            :idempotency-key idempotency-key}
              :applications application-entries
              :accounting-entries (vec (concat (when (pos? allocated)
                                                 [{:entry/type :debit :account :shared-liquidity
                                                   :token (:token decision) :delta (- allocated)}])
                                               (keep (fn [p]
                                                       (when (pos? (:fulfilled p))
                                                         {:entry/type :credit :account :withdrawn
                                                          :token (:token decision)
                                                          :participant-id (:participant-id p)
                                                          :obligation-id (get-in p [:origin :obligation-id])
                                                          :delta (:fulfilled p)})) participants)))
              :accounting-entry-set-hash nil
              :residual {:amount residual
                         :reason (get-in decision [:evidence :residual-reason])
                         :destination (get-in policy [:residual-liquidity :destination])}
              :reconciliation {:all-allocations-applied (every? #(zero? (:deferred % 0)) participants)
                               :allocation-applied? (every? #(zero? (:deferred % 0)) participants)
                               :shortfalls-preserved? true
                               :capacity-reconciled? true :accounting-reconciled? accounting-reconciled?

                               :residual-reconciled? true}
              :status :committed}
        entry-hash (accounting-entry-set-hash (:accounting-entries base))
        base (assoc base :accounting-entry-set-hash entry-hash)
        artifact-hash (hash-ref/sha256-ref (hc/hash-with-intent {:hash/intent :evidence-record} base))]
    (assoc base
           :propagation/id (str "pro-rata-propagation-" (subs artifact-hash 7 (min (count artifact-hash) 23)))
           :propagation/hash artifact-hash)))

(defn validate-pro-rata-propagation
  "Validate propagation-policy binding separately from allocation arithmetic.
    Returns structured reasons so callers can distinguish policy violations."
  [artifact]
  (try
    (let [validated-artifact
          (or (:application/base-propagation artifact) artifact)
          expected-propagation-hash
          (hash-ref/sha256-ref (hc/hash-with-intent {:hash/intent :evidence-record}
                                                    (dissoc validated-artifact :propagation/id :propagation/hash)))
          hash-errors (cond-> []
                        (nil? (:propagation/id validated-artifact)) (conj :propagation-id-missing)
                        (nil? (:propagation/hash validated-artifact)) (conj :propagation-hash-missing)
                        (not= (:propagation/hash validated-artifact) expected-propagation-hash)
                        (conj :propagation-hash-mismatch))
          ref (:propagation-policy validated-artifact)
          snapshot (:policy/snapshot ref)
          policy (propagation-policy/normalize-and-validate snapshot)
          allocation-ref (:allocation/reference validated-artifact)
          reference-errors (cond-> []
                             (not= "pro-rata-allocation-reference.v1" (:schema-version allocation-ref)) (conj :allocation-reference-schema-mismatch)
                             (nil? (:allocation/id allocation-ref)) (conj :allocation-reference-id-missing)
                             (nil? (:allocation/hash allocation-ref)) (conj :allocation-reference-hash-missing)
                             (not= {:id :mechanism/pro-rata-allocation :version 1}
                                   (:mechanism allocation-ref)) (conj :allocation-reference-mechanism-mismatch)
                             (not= (:calculation-ref validated-artifact)
                                   (get-in allocation-ref [:source-evidence :artifact/id])) (conj :allocation-reference-source-id-mismatch)
                             (not= (:outcome-ref validated-artifact)
                                   (get-in allocation-ref [:source-evidence :artifact/hash])) (conj :allocation-reference-source-hash-mismatch))
          policy-errors (cond-> []
                          (not= (:policy/hash ref) (:policy/hash policy)) (conj :policy-hash-mismatch)
                          (not= (:policy/id ref) (:policy/id policy)) (conj :policy-id-mismatch)
                          (not= (:policy/version ref) (:policy/version policy)) (conj :policy-version-mismatch))
          participant-errors
          (mapcat (fn [p]
                    (let [d (long (:deferred p 0))]
                      (cond-> []
                        (and (pos? d) (not= (get-in policy [:shortfall :classification]) :deferred)) (conj :shortfall-classification-mismatch)
                        (and (pos? d) (not= (get-in p [:next-position :next-round-weight-policy])
                                            (get-in policy [:shortfall :next-round-weight-policy]))) (conj :next-round-weight-policy-mismatch)
                        (and (zero? d) (not= :fulfilled (:position-status p))) (conj :fulfilled-position-not-closed))))
                  (:participants validated-artifact []))]
      {:valid? (empty? (concat hash-errors reference-errors policy-errors participant-errors))
       :calculation-errors []
       :policy-errors (vec (concat hash-errors reference-errors policy-errors participant-errors))})
    (catch clojure.lang.ExceptionInfo e
      {:valid? false :calculation-errors [] :policy-errors [(:reason (ex-data e))]})))

(defn propagation-allocation-binding-violations
  "Verify that a v2 propagation is an exact domain translation of the committed
   partial-fill decision and its mechanism allocation reference."
  [decision propagation]
  (let [expected-ref {:schema-version "pro-rata-allocation-reference.v1"
                      :allocation/id (get-in decision [:evidence :allocation-mechanism :allocation/id])
                      :allocation/hash (get-in decision [:evidence :allocation-mechanism :allocation/hash])
                      :mechanism (get-in decision [:evidence :allocation-mechanism :mechanism])
                      :mechanism-evidence (pro-rata-evidence/evidence-reference
                                           (get-in decision [:evidence :allocation-mechanism-evidence]))
                      :source-evidence {:artifact/id (:decision/id decision)
                                        :artifact/hash (:decision/hash decision)}}
        actual-ref (:allocation/reference propagation)
        decision-hash-errors
        (cond-> []
          (nil? (:decision/id decision)) (conj {:reason :decision-id-missing})
          (nil? (:decision/hash decision)) (conj {:reason :decision-hash-missing})
          (and (:decision/hash decision) (not (decision-hash-valid? decision)))
          (conj {:reason :decision-hash-mismatch}))
        mechanism-evidence-errors
        (mapv #(assoc % :reason :decision-mechanism-evidence-invalid)
              (pro-rata-evidence/evidence-violations
               (get-in decision [:evidence :allocation-mechanism-evidence])))
        row-key (fn [row]
                  [(:obligation-id row) (:key row) (:source-position-id row)])
        participant-key (fn [participant]
                          [(get-in participant [:origin :obligation-id])
                           (:participant-id participant)
                           (get-in participant [:origin :source-position-id])])
        decision-rows (vec (get-in decision [:evidence :allocation-rows]))
        propagation-participants (vec (:participants propagation))
        decision-by-key (group-by row-key decision-rows)
        propagation-by-key (group-by participant-key propagation-participants)
        decision-keys (set (keys decision-by-key))
        propagation-keys (set (keys propagation-by-key))
        reference-errors (cond-> []
                           (not= (:allocation/id expected-ref) (:allocation/id actual-ref)) (conj {:reason :propagation-allocation-id-mismatch})
                           (not= (:allocation/hash expected-ref) (:allocation/hash actual-ref)) (conj {:reason :propagation-allocation-hash-mismatch})
                           (not= (:mechanism expected-ref) (:mechanism actual-ref)) (conj {:reason :propagation-mechanism-reference-mismatch})
                           (not= (:mechanism-evidence expected-ref) (:mechanism-evidence actual-ref))
                           (conj {:reason :propagation-mechanism-evidence-reference-mismatch})
                           (not= (:source-evidence expected-ref) (:source-evidence actual-ref)) (conj {:reason :propagation-decision-reference-mismatch})
                           (not= (:allocation/invocation-context decision)
                                 (:allocation/invocation-context propagation))
                           (conj {:reason :propagation-invocation-context-mismatch}))
        missing (clojure.set/difference decision-keys propagation-keys)
        extra (clojure.set/difference propagation-keys decision-keys)
        duplicate-propagation-keys (filter #(> (count (get propagation-by-key %)) 1) propagation-keys)
        duplicate-decision-keys (filter #(> (count (get decision-by-key %)) 1) decision-keys)
        matched-keys (clojure.set/intersection decision-keys propagation-keys)
        row-errors (mapcat (fn [key]
                             (let [decision-row (first (get decision-by-key key))
                                   participant (first (get propagation-by-key key))]
                               (cond-> []
                                 (and (= 1 (count (get decision-by-key key)))
                                      (= 1 (count (get propagation-by-key key)))
                                      (not= (:filled decision-row) (:fulfilled participant)))
                                 (conj {:reason :propagated-fulfilled-mismatch
                                        :key key
                                        :expected (:filled decision-row)
                                        :observed (:fulfilled participant)})
                                 (and (= 1 (count (get decision-by-key key)))
                                      (= 1 (count (get propagation-by-key key)))
                                      (not= (:deferred decision-row) (:deferred participant)))
                                 (conj {:reason :propagated-unmet-mismatch
                                        :key key
                                        :expected (:deferred decision-row)
                                        :observed (:deferred participant)}))))
                           matched-keys)
        expected-fulfilled (reduce + 0 (map #(long (:filled % 0)) decision-rows))
        observed-fulfilled (reduce + 0 (map #(long (:fulfilled % 0)) propagation-participants))
        expected-unmet (reduce + 0 (map #(long (:deferred % 0)) decision-rows))
        observed-unmet (reduce + 0 (map #(long (:deferred % 0)) propagation-participants))]
    (vec (concat decision-hash-errors
                 mechanism-evidence-errors
                 reference-errors
                 (map #(hash-map :reason :missing-propagation-participant :key %) missing)
                 (map #(hash-map :reason :extra-propagation-participant :key %) extra)
                 (map #(hash-map :reason :duplicate-propagation-participant :key %
                                 :count (count (get propagation-by-key %))) duplicate-propagation-keys)
                 (map #(hash-map :reason :duplicate-decision-allocation-row :key %
                                 :count (count (get decision-by-key %))) duplicate-decision-keys)
                 row-errors
                 (when (not= expected-fulfilled observed-fulfilled)
                   [{:reason :propagation-fulfilled-total-mismatch
                     :expected expected-fulfilled :observed observed-fulfilled}])
                 (when (not= expected-unmet observed-unmet)
                   [{:reason :propagation-unmet-total-mismatch
                     :expected expected-unmet :observed observed-unmet}])))))

(defn attach-pro-rata-propagation
  "Persist a committed pro-rata propagation artifact by its stable identity."
  [world artifact]
  (assoc-in world [:yield/pro-rata-propagations (:propagation/id artifact)] artifact))

(defn- sum-long-values
  [m]
  (reduce + 0 (map long (vals (or m {})))))

(defn- positive-requested-claims
  [decision]
  (->> (:requested decision)
       (filter (fn [[_ v]] (pos? (long v))))
       (into {})))

(defn- compute-ideal-fills
  "Compute exact integer allocation quotients and remainders.

   Each ideal share is `(requested * available) / total-requested`; its
   numerator, denominator, floor, and remainder are retained as integers. This
   deliberately avoids binary floating point because base-unit values and
   largest-remainder rankings must remain correct above 2^53."
  [positive-claims total-requested available]
  (let [total (bigint (or total-requested 0))
        avail (bigint (or available 0))]
    (into {}
          (map (fn [[key claim]]
                 (let [requested (bigint claim)
                       numerator (*' requested avail)
                       floor (if (zero? total) 0 (quot numerator total))
                       remainder (if (zero? total) 0 (mod numerator total))]
                   [key {:requested requested
                         :ideal-numerator numerator
                         :ideal-denominator total
                         :ideal-floor floor
                         :fraction-remainder remainder}]))
               positive-claims))))

(defn rounding-semantics
  "Classify a rounding policy by the discrete rounding model its allocation
   rule guarantees. Central single source so the producer and the closed-form
   verifier derive expectations from the SAME model (previously each encoded
   the taxonomy independently, which is how default :floor-and-carry output came
   to be rejected by its own verifier).

     :exact-floor          — strict floor, no upward carry; unit residual is
                             left unallocated (see floor-alloc). Per-claimant
                             deviation from the ideal is 0.
     :bounded-carry        — floor everyone, then distribute the unit residual
                             by a deterministic carry ordering (largest
                             remainder). Per-claimant deviation from the ideal
                             is bounded by +1 (see floor-and-carry-alloc /
                             largest-remainder-alloc).
     :principal-protective — protects principal before yield; principal uses
                             floor-and-carry and yield uses largest-remainder,
                             each bounded by +1.

   Returns nil for policies with no defined rounding model (e.g.
   :adversarial-rounding), which then opt out of rounding-derived checks."
  [rounding-policy]
  (case rounding-policy
    :floor                      :exact-floor
    :floor-and-carry            :bounded-carry
    :largest-remainder          :bounded-carry
    :principal-protective-floor :principal-protective
    nil))

(defn rounding-max-error
  "Maximum permitted per-claimant deviation from the ideal allocation, derived
   from `rounding-semantics`. :bounded-carry/:principal-protective may award one
   +1 dust unit; :exact-floor never deviates above the ideal floor."
  [rounding-policy]
  (case (rounding-semantics rounding-policy)
    :exact-floor 0
    (:bounded-carry :principal-protective) 1
    0))

(defn pro-rata-rounding?
  "True if the rounding policy is a genuine pro-rata rounding (as distinct from
   principal-protective, which concentrates principal beyond a pro-rata share).
   Only genuine pro-rata policies may be measured against the ideal pro-rata
   allocation in rounding-fairness."
  [rounding-policy]
  (boolean (#{:floor :floor-and-carry :largest-remainder} rounding-policy)))

(defn carry-order-rounding?
  "True if the policy distributes its unit residual by the largest-remainder
   carry ordering, making the remainder ranking check meaningful (a carry unit
   must land on a top-remainder claimant). :floor-and-carry uses the same
   largest-remainder carry ordering as :largest-remainder."
  [rounding-policy]
  (boolean (#{:largest-remainder :floor-and-carry} rounding-policy)))

(defn complement-rounding-max-error
  "Rounding bound for the deferred/haircut complement of a fill. The complement
   of ANY integer-rounded fill is the ceiling of the proportional shortfall
   share (deferred_i = requested_i - filled_i), so it necessarily inherits an
   upward rounding effect of +1 regardless of the fill policy's own
   floor/carry model. This keeps the fill and its complement each bounded by
   their inherent rounding semantics — the fill by `rounding-max-error`, the
   complement by this universal ceiling bound."
  [_rounding-policy]
  1)

(defn execution-shape
  "Classify the execution path that produced a decision from the artifact alone.
     :rows   — shared-withdrawal rows path routed through the pro-rata mechanism
               (records :allocation-mechanism-evidence).
     :single — backward-compatible non-rows allocation.
   Future shapes (waterfall-rows, etc.) extend this as needed."
  [decision]
  (if (get-in decision [:evidence :allocation-mechanism-evidence]) :rows :single))

(defn normalize-rounding-algorithm
  "Map a REQUESTED rounding policy to the EFFECTIVE allocation algorithm a given
   execution shape actually runs. Single authoritative derivation so the
   verifier computes the permitted effective algorithm from the requested policy
   plus shape, rather than trusting an independently-declared field.

   :floor-and-carry and :largest-remainder are ONE integer algorithm (identical
   largest-remainder carry ordering; floor-and-carry only adds fractional
   cross-round :carry bookkeeping that never touches filled/deferred), so both
   normalize to :largest-remainder. The :rows mechanism additionally coerces
   every non-:floor policy (incl. :principal-protective-floor) to
   :largest-remainder. :floor is a distinct exact-floor algorithm in both shapes."
  [rounding-policy execution-shape]
  (case execution-shape
    :rows   (if (= :floor rounding-policy) :floor :largest-remainder)
    :single (case rounding-policy
              :floor                      :floor
              :largest-remainder          :largest-remainder
              :floor-and-carry            :largest-remainder
              :principal-protective-floor :principal-protective-floor
              :adversarial-rounding       :adversarial-rounding
              :largest-remainder)
    (if (= :floor rounding-policy) :floor :largest-remainder)))

(defn- rounding-fairness-violations
  "Check rounding fairness for integer allocation.
   Returns a vector of violation maps (empty = pass).
   For each claim, verifies that |actual_fill - ideal| <= max-rounding-error.
   For carry-order policies, also verifies remainder-ranking correctness.

   `positive-claims` — map of claim-key -> requested amount
   `filled` — map of claim-key -> filled amount
   `available` — available liquidity
   `total-requested` — sum of all requested amounts
   `rounding-policy` — :floor-and-carry | :floor | :largest-remainder | :principal-protective-floor"
  [positive-claims filled available total-requested rounding-policy]
  (when (seq positive-claims)
    (let [ideals (compute-ideal-fills positive-claims total-requested available)
          violations (atom [])
          _ (doseq [[k {:keys [requested ideal-exact ideal-floor]}]
                    (sort-by key ideals)]
              (let [actual (long (get filled k 0))
                    error (- actual ideal-floor)
                    max-error (rounding-max-error rounding-policy)]
                (when (> error max-error)
                  (swap! violations conj
                         {:claim k :requested requested :ideal-floor ideal-floor
                          :actual actual :error error :max-error max-error
                          :kind :ideal-floor-violation}))))
          ;; Carry-order policies: verify remainder ranking (a +1 unit must land
          ;; on a top-remainder claimant). Shared by largest-remainder and
          ;; floor-and-carry.
          ranking-violations (when (carry-order-rounding? rounding-policy)
                               (let [remainders (->> ideals
                                                     (sort-by rounding-rank-key)
                                                     (mapv (fn [[k v]]
                                                             [k (:fraction-remainder v)])))
                                     extra-count (- available (reduce + 0 (map :ideal-floor (vals ideals))))
                                     top-n (take (max 0 extra-count) remainders)
                                     top-ids (set (map first top-n))]
                                 (->> (keep (fn [[k _]]
                                              (let [actual-extra (- (long (get filled k 0))
                                                                    (get-in ideals [k :ideal-floor] 0))]
                                                (when (and (pos? actual-extra) (not (contains? top-ids k)))
                                                  {:claim k :kind :unexpected-extra-unit
                                                   :fraction-remainder (get-in ideals [k :fraction-remainder])
                                                   :rank (some (fn [[i [ck _]]] (when (= ck k) i))
                                                               (map-indexed vector remainders))})))
                                            remainders)
                                      vec)))]
      (vec (concat @violations ranking-violations)))))

(def ^:private check-class
  "Maps each partial-fill check-id to its validation class.
   Allocation-property checks verify the allocation rule itself;
   algebraic-integrity checks verify arithmetic/structural consistency."
  {:partial-fill/conservation            :validation.class/algebraic-integrity
   :partial-fill/capacity-bound          :validation.class/algebraic-integrity
   :partial-fill/per-claim-bound         :validation.class/algebraic-integrity
   :partial-fill/per-claim-conservation  :validation.class/algebraic-integrity
   :partial-fill/claim-key-consistency   :validation.class/algebraic-integrity
   :partial-fill/non-negative-amounts    :validation.class/algebraic-integrity
   :partial-fill/deferred-haircut-overlap :validation.class/algebraic-integrity
   :partial-fill/deferred-haircut-sum-bound :validation.class/algebraic-integrity
   :partial-fill/evidence-self-consistency :validation.class/algebraic-integrity
   :partial-fill/unrealized-bucket-valid :validation.class/algebraic-integrity
   :partial-fill/decision-artifact-format :validation.class/algebraic-integrity
   :partial-fill/settlement-mode-consistency :validation.class/algebraic-integrity
   :partial-fill/settlement-mode-valid   :validation.class/algebraic-integrity
   :partial-fill/mode-valid              :validation.class/algebraic-integrity
   :partial-fill/exact-pro-rata          :validation.class/allocation-property
   :partial-fill/rounding-fairness       :validation.class/allocation-property
   :partial-fill/fail-action-fairness    :validation.class/allocation-property
   :partial-fill/rounding-fairness-ideal :validation.class/allocation-property
   :partial-fill/rounding-fairness-remainder-ranking :validation.class/allocation-property
   :partial-fill/principal-first-priority :validation.class/allocation-property
   :partial-fill/waterfall-priority      :validation.class/allocation-property
   :partial-fill/rounding-residual-bounded :validation.class/allocation-property
   :partial-fill/effective-rounding-consistency :validation.class/allocation-property})

(defn- check-result
  ([check-id status details]
   (check-result check-id status details (get check-class check-id)))
  ([check-id status details validation-class]
   (cond-> {:check/id check-id
            :status status
            :details details}
     validation-class (assoc :validation-class validation-class))))

(defn partial-fill-closed-form-checks
  "Research-grade closed-form criteria for a partial-fill decision.

   Checks:
   - :partial-fill/conservation
   - :partial-fill/capacity-bound
   - :partial-fill/per-claim-bound
   - :partial-fill/per-claim-conservation
   - :partial-fill/claim-key-consistency
   - :partial-fill/non-negative-amounts
   - :partial-fill/settlement-mode-consistency
   - :partial-fill/settlement-mode-valid
   - :partial-fill/mode-valid
   - :partial-fill/deferred-haircut-overlap
   - :partial-fill/deferred-haircut-sum-bound
   - :partial-fill/evidence-self-consistency
   - :partial-fill/unrealized-bucket-valid
   - :partial-fill/decision-artifact-format
   - :partial-fill/exact-pro-rata
   - :partial-fill/rounding-fairness
   - :partial-fill/fail-action-fairness
   - :partial-fill/rounding-fairness-ideal
   - :partial-fill/rounding-fairness-remainder-ranking
   - :partial-fill/principal-first-priority
   - :partial-fill/waterfall-priority
    - :partial-fill/rounding-residual-bounded
    - :partial-fill/effective-rounding-consistency

   These checks operate on the structured decision returned by
   calculate-fulfillment*. They intentionally stay local to the decision
   map and do not infer broader replay semantics."
  [decision]
  (let [requested (:requested decision)
        filled (:filled decision)
        deferred (:deferred decision)
        haircut (:haircut decision)
        policy (:policy decision)
        mode (:mode policy)
        available (long (get-in decision [:evidence :available-liquidity] 0))
        total-requested (sum-long-values requested)
        total-filled (sum-long-values filled)
        total-deferred (sum-long-values deferred)
        total-haircut (sum-long-values haircut)
        positive-claims (positive-requested-claims decision)
        allocation-rows (get-in decision [:evidence :allocation-rows] [])
        effective-caps (into {}
                             (keep (fn [{:keys [key effective-cap]}]
                                     (when (some? effective-cap) [key (long effective-cap)]))
                                   allocation-rows))
        cap-constrained? (some (fn [[claim cap]]
                                 (< cap (long (get positive-claims claim cap))))
                               effective-caps)
        eligible-claim-count (count positive-claims)
        residual (- available total-filled)
        conservation-ok? (= total-requested (+ total-filled total-deferred total-haircut))
        capacity-ok? (<= total-filled available)
        per-claim-violations
        (->> positive-claims
             (keep (fn [[k claim]]
                     (let [r (long claim)
                           f (long (get filled k 0))
                           d (long (get deferred k 0))
                           h (long (get haircut k 0))
                           cap (get effective-caps k r)
                           fv (when (> f r) {:claim k :kind :filled-exceeds-requested :requested r :filled f})
                           cv (when (> f cap) {:claim k :kind :filled-exceeds-effective-cap :effective-cap cap :filled f})
                           dv (when (> d r) {:claim k :kind :deferred-exceeds-requested :requested r :deferred d})
                           hv (when (> h r) {:claim k :kind :haircut-exceeds-requested :requested r :haircut h})]
                       (seq (remove nil? [fv cv dv hv])))))
             (apply concat)
             vec)
        per-claim-conservation-violations
        (->> (set (concat (keys requested) (keys filled) (keys deferred) (keys haircut)))
             (keep (fn [k]
                     (let [r (long (get requested k 0))
                           f (long (get filled k 0))
                           d (long (get deferred k 0))
                           h (long (get haircut k 0))
                           total (+ f d h)]
                       (when (not= r total)
                         {:claim k
                          :requested r
                          :filled f
                          :deferred d
                          :haircut h
                          :recovered-sum total}))))
             vec)
        rounding-policy (:rounding-policy policy :floor-and-carry)
        ;; Exact ratio equality is a contractual property ONLY when every ideal
        ;; share is representable in whole units (the allocation is evenly
        ;; divisible), so no rounding deviation is possible under ANY policy.
        ;; When indivisibility exists, rounding-fairness (bounded by the policy's
        ;; rounding semantics) is the operative guarantee — a carry policy must
        ;; not enter the strict cross-product exact-pro-rata check, and neither
        ;; does a principal-protective policy (which concentrates principal).
        strict-pro-rata? (and (= :pro-rata mode)
                              (not cap-constrained?)
                              (pro-rata-rounding? rounding-policy)
                              (every? (fn [[_ claim]]
                                        (zero? (mod (* (long claim) available)
                                                    (max 1 total-requested))))
                                      positive-claims))
        pro-rata-pairs
        (when strict-pro-rata?
          (->> positive-claims
               keys
               sort
               vec))
        pro-rata-violations
        (if strict-pro-rata?
          (->> (for [i (range (count pro-rata-pairs))
                     j (range (inc i) (count pro-rata-pairs))]
                 (let [ki (nth pro-rata-pairs i)
                       kj (nth pro-rata-pairs j)
                       claim-i (long (get positive-claims ki 0))
                       claim-j (long (get positive-claims kj 0))
                       filled-i (long (get filled ki 0))
                       filled-j (long (get filled kj 0))]
                   (when (and (pos? claim-i)
                              (pos? claim-j)
                              (not= (* filled-i claim-j)
                                    (* filled-j claim-i)))
                     {:left ki
                      :right kj
                      :left-cross (* filled-i claim-j)
                      :right-cross (* filled-j claim-i)})))
               (remove nil?)
               vec)
          [])
        total-unfilled (long (+ total-deferred total-haircut))
        unfilled (into {}
                       (merge-with +
                                   (or deferred {})
                                   (or haircut {})))
        ;; The fail-action theorem is policy-bound: the execution must conform
        ;; to its declared pro-rata fail-action policy, not to an unconditional
        ;; definition of fairness. Each bucket declares how its shortfall is
        ;; treated; :same-ratio buckets must be pro-rata within rounding
        ;; tolerance. The effective policy and its committed root are included
        ;; so the theorem is replayable and provable.
        fail-action-policy (or (get-in decision [:policy :fail-action-policy])
                               {:mode :pro-rata-treatment
                                :deferred-policy :same-ratio
                                :haircut-policy :same-ratio})
        fail-action-policy-root (hc/hash-with-intent {:hash/intent :fail-action-policy}
                                                     fail-action-policy)
        bucket-policy (fn [bucket] (get fail-action-policy
                                        (if (= :deferred bucket) :deferred-policy :haircut-policy)
                                        :same-ratio))
        same-ratio-bucket? (fn [bucket] (= :same-ratio (bucket-policy bucket)))
        ;; The deferred/haircut complement of a fill is a ceiling of the
        ;; proportional shortfall share, so it inherits an inherent +1 rounding
        ;; effect for every policy (including strict :floor). Enforced against
        ;; each same-ratio bucket's own totals.
        fail-action-max-error (complement-rounding-max-error rounding-policy)
        ;; A :same-ratio bucket must give every claimant the same shortfall
        ;; ratio within the permitted rounding advantage. Deferred/haircut are
        ;; the complement of the fill, so their remainder pattern is the inverse
        ;; of the fill's; a fresh largest-remainder ordering must NOT be applied
        ;; here. Only the ideal-floor bound (no claimant receives more than the
        ;; permitted rounding advantage) is enforced, against the bucket's own
        ;; totals.
        fail-action-bucket-violations
        (fn [amounts bucket-total bucket]
          (if (and (pos? bucket-total) (same-ratio-bucket? bucket))
            (let [amounts (or amounts {})
                  ideals (compute-ideal-fills positive-claims total-requested bucket-total)]
              (->> ideals
                   (keep (fn [[k {:keys [requested ideal-floor]}]]
                           (let [actual (long (get amounts k 0))
                                 error (- actual ideal-floor)]
                             (when (or (neg? error)
                                       (> error fail-action-max-error))
                               {:claim k
                                :requested requested
                                :ideal-floor ideal-floor
                                :actual actual
                                :error error
                                :max-error fail-action-max-error}))))
                   vec))
            []))
        fail-action-violations
        (cond-> []
          (same-ratio-bucket? :deferred)
          (into (map #(assoc % :bucket :deferred)
                     (fail-action-bucket-violations deferred total-deferred :deferred)))
          (same-ratio-bucket? :haircut)
          (into (map #(assoc % :bucket :haircut)
                     (fail-action-bucket-violations haircut total-haircut :haircut)))
          (and (same-ratio-bucket? :deferred) (same-ratio-bucket? :haircut))
          (into (map #(assoc % :bucket :unfilled)
                     (fail-action-bucket-violations unfilled total-unfilled :unfilled))))
        rounding-applicable? (and (not cap-constrained?)
                                  (boolean (rounding-semantics rounding-policy)))
        residual-ok? (if cap-constrained?
                       (and (= residual (long (get-in decision [:evidence :unallocated-residual] 0)))
                            (or (zero? residual)
                                (= :all-participants-cap-constrained
                                   (get-in decision [:evidence :residual-reason]))))
                       (case (rounding-semantics rounding-policy)
                         :exact-floor
                         (and (<= 0 residual)
                              (< residual (max 1 eligible-claim-count)))
                         (:bounded-carry :principal-protective)
                         (zero? residual)
                         false))
        claim-key-consistency-violations
        (->> (set (concat (keys filled) (keys deferred) (keys haircut)))
             (keep (fn [k]
                     (when (not (contains? requested k))
                       {:key k
                        :source (cond (contains? filled k) :filled
                                      (contains? deferred k) :deferred
                                      :else :haircut)})))
             vec)
        principal-first-violations
        (when (= :principal-first mode)
          (let [principal-requested (long (get requested :principal 0))
                principal-filled (long (get filled :principal 0))
                yield-keys (remove #{:principal} (keys filled))]
            (->> yield-keys
                 (keep (fn [k]
                         (let [yf (long (get filled k 0))]
                           (when (and (pos? principal-requested)
                                      (pos? yf)
                                      (< principal-filled principal-requested))
                             {:claim k
                              :yield-filled yf
                              :principal-requested principal-requested
                              :principal-filled principal-filled}))))
                 vec)))
        waterfall-violations
        (when (= :waterfall mode)
          (let [fill-order (:fill-order policy [:principal :realized-yield :deferred-yield])]
            (->> (for [i (range (count fill-order))
                       j (range (inc i) (count fill-order))]
                   [i j])
                 (keep (fn [[i j]]
                         (let [higher (nth fill-order i)
                               lower (nth fill-order j)
                               higher-requested (long (get requested higher 0))
                               higher-filled (long (get filled higher 0))
                               lower-filled (long (get filled lower 0))]
                           (when (and (pos? higher-requested)
                                      (< higher-filled higher-requested)
                                      (pos? lower-filled))
                             {:higher-bucket higher
                              :higher-requested higher-requested
                              :higher-filled higher-filled
                              :lower-bucket lower
                              :lower-filled lower-filled}))))
                 vec)))
        valid-modes #{:pro-rata :principal-first :waterfall}
        mode-violations
        (when (not (contains? valid-modes mode))
          [{:mode mode :valid-modes (vec valid-modes)}])
        settlement-mode (:settlement-mode decision)
        valid-settlement-modes #{:full-fill :partial-fill}
        settlement-mode-valid-violations
        (when (not (contains? valid-settlement-modes settlement-mode))
          [{:settlement-mode settlement-mode :valid-settlement-modes (vec valid-settlement-modes)}])
        settlement-mode-violations
        (if (= :full-fill settlement-mode)
          (cond-> []
            (some (fn [[_ v]] (pos? (long v))) deferred)
            (conj {:reason "deferred non-empty during full-fill" :deferred (into {} (filter (fn [[_ v]] (pos? (long v))) deferred))})
            (some (fn [[_ v]] (pos? (long v))) haircut)
            (conj {:reason "haircut non-empty during full-fill" :haircut (into {} (filter (fn [[_ v]] (pos? (long v))) haircut))})
            (not= total-filled total-requested)
            (conj {:reason "total-filled != total-requested during full-fill" :total-requested total-requested :total-filled total-filled}))
          [])
        unrealized (:unrealized decision)
        negative-amount-violations
        (->> (concat
              (map (fn [[k v]] {:kind :requested :key k :value (long v)}) requested)
              (map (fn [[k v]] {:kind :filled :key k :value (long v)}) filled)
              (map (fn [[k v]] {:kind :deferred :key k :value (long v)}) deferred)
              (map (fn [[k v]] {:kind :haircut :key k :value (long v)}) haircut)
              (map (fn [[k v]] {:kind :unrealized :key k :value (long v)}) unrealized))
             (keep (fn [entry]
                     (when (neg? (:value entry))
                       entry)))
             vec)
        deferred-haircut-overlap-violations
        (->> (keys deferred)
             (filter (fn [k] (and (contains? haircut k)
                                  (pos? (long (get deferred k 0)))
                                  (pos? (long (get haircut k 0))))))
             (mapv (fn [k] {:claim k :deferred (long (get deferred k 0)) :haircut (long (get haircut k 0))})))
        deferred-haircut-sum-violations
        (->> positive-claims
             (keep (fn [[k claim]]
                     (let [r (long claim)
                           d (long (get deferred k 0))
                           h (long (get haircut k 0))
                           sum (+ d h)]
                       (when (< r sum)
                         {:claim k :requested r :deferred d :haircut h :combined-sum sum :excess (- sum r)}))))
             vec)
        evidence (:evidence decision)
        evidence-violations
        (let [computed-shortage (max 0 (- total-requested available))]
          (cond-> []
            (and (contains? evidence :shortage)
                 (not= (long (:shortage evidence 0)) computed-shortage))
            (conj {:kind :shortage-mismatch :evidence-value (long (:shortage evidence 0)) :computed computed-shortage})
            (and (contains? evidence :fill-mode)
                 (not= (:fill-mode evidence) mode))
            (conj {:kind :fill-mode-mismatch :evidence-value (:fill-mode evidence) :computed mode})
            (and (contains? evidence :total-requested)
                 (not= (long (:total-requested evidence 0)) total-requested))
            (conj {:kind :total-requested-mismatch :evidence-value (long (:total-requested evidence 0)) :computed total-requested})))
        unrealized-violations
        (->> (keys unrealized)
             (keep (fn [k]
                     (when (not (contains? requested k))
                       {:key k :value (long (get unrealized k 0))})))
             vec)
        decision-artifact-violations
        (let [dhash (:decision/hash decision)
              did (:decision/id decision)]
          (cond-> []
            (and (some? dhash) (not (re-matches #"sha256:[0-9a-f]{64}" dhash)))
            (conj {:kind :invalid-hash-format :hash dhash})
            (and (some? did) (not (re-matches #"partial-fill-[0-9a-f]{1,16}" did)))
            (conj {:kind :invalid-id-format :id did})))]
    (let [conservation-ch (future
                            (check-result :partial-fill/conservation
                                          (if conservation-ok? :pass :fail)
                                          {:total-requested total-requested
                                           :total-filled total-filled
                                           :total-deferred total-deferred
                                           :total-haircut total-haircut}))
          capacity-ch (future
                        (check-result :partial-fill/capacity-bound
                                      (if capacity-ok? :pass :fail)
                                      {:available-liquidity available
                                       :total-filled total-filled}))
          per-claim-ch (future
                         (check-result :partial-fill/per-claim-bound
                                       (if (empty? per-claim-violations) :pass :fail)
                                       {:violations per-claim-violations}))
          exact-pro-rata-ch (future
                              (if strict-pro-rata?
                                (check-result :partial-fill/exact-pro-rata
                                              (if (empty? pro-rata-violations) :pass :fail)
                                              {:violations pro-rata-violations})
                                (check-result :partial-fill/exact-pro-rata
                                              :not-applicable
                                              {:mode mode
                                               :rounding-policy rounding-policy
                                               :reason (if cap-constrained?
                                                         "effective caps require constrained redistribution rather than one global ratio"
                                                         "indivisible pro-rata allocation is checked by rounding-fairness")})))
          rounding-fairness-ch (future
                                 (if (and (= :pro-rata mode) (not cap-constrained?)
                                          (pro-rata-rounding? rounding-policy))
                                   (let [violations (rounding-fairness-violations
                                                     positive-claims filled available
                                                     total-requested rounding-policy)
                                         ranking-violations (filter #(= :unexpected-extra-unit (:kind %))
                                                                    violations)]
                                     (check-result :partial-fill/rounding-fairness
                                                   (if (and (empty? violations)
                                                            (empty? ranking-violations)
                                                            residual-ok?)
                                                     :pass :fail)
                                                   {:violations violations
                                                    :max-allowed-error (rounding-max-error rounding-policy)
                                                    :ideal-fills (compute-ideal-fills
                                                                  positive-claims total-requested available)
                                                    :reconciles? residual-ok?}))
                                   (check-result :partial-fill/rounding-fairness
                                                 :not-applicable
                                                 {:mode mode
                                                  :rounding-policy rounding-policy
                                                  :reason (if cap-constrained?
                                                            "effective caps require constrained redistribution"
                                                            "pro-rata mode not exercised")})))
          fail-action-ch (future
                           (if (pos? total-unfilled)
                             (check-result :partial-fill/fail-action-fairness
                                           (if (empty? fail-action-violations) :pass :fail)
                                           {:violations fail-action-violations
                                            :fail-action-policy fail-action-policy
                                            :fail-action-policy-root fail-action-policy-root
                                            :total-deferred total-deferred
                                            :total-haircut total-haircut
                                            :total-unfilled total-unfilled})
                             (check-result :partial-fill/fail-action-fairness
                                           :not-applicable
                                           {:reason "no deferred or haircut amounts (no fail action exercised)"})))
          rounding-fairness-ideal-ch (future
                                       (if (and (= :pro-rata mode) (not cap-constrained?)
                                                (pro-rata-rounding? rounding-policy))
                                         (let [violations (rounding-fairness-violations
                                                           positive-claims filled available
                                                           total-requested rounding-policy)]
                                           (check-result :partial-fill/rounding-fairness-ideal
                                                         (if (empty? violations) :pass :fail)
                                                         {:violations violations
                                                          :max-allowed-error (rounding-max-error rounding-policy)
                                                          :ideal-fills (compute-ideal-fills
                                                                        positive-claims total-requested available)}))
                                         (check-result :partial-fill/rounding-fairness-ideal
                                                       :not-applicable {:mode mode
                                                                        :reason (when cap-constrained?
                                                                                  "effective caps require constrained redistribution")})))
          rounding-remainder-ch (future
                                  (if (and (carry-order-rounding? rounding-policy) (not cap-constrained?))
                                    (let [violations (rounding-fairness-violations
                                                      positive-claims filled available
                                                      total-requested rounding-policy)
                                          ranking-violations (filter #(= :unexpected-extra-unit (:kind %))
                                                                     violations)]
                                      (check-result :partial-fill/rounding-fairness-remainder-ranking
                                                    (if (empty? ranking-violations) :pass :fail)
                                                    {:violations ranking-violations
                                                     :remainder-order (->> (compute-ideal-fills
                                                                            positive-claims total-requested available)
                                                                           (sort-by rounding-rank-key)
                                                                           (mapv (fn [[k v]] [k (:fraction-remainder v)])))}))
                                    (check-result :partial-fill/rounding-fairness-remainder-ranking
                                                  :not-applicable {:rounding-policy rounding-policy
                                                                   :reason (when cap-constrained?
                                                                             "effective caps require constrained redistribution")})))
          residual-ch (future
                        (if rounding-applicable?
                          (check-result :partial-fill/rounding-residual-bounded
                                        (if residual-ok? :pass :fail)
                                        {:available-liquidity available
                                         :total-filled total-filled
                                         :residual residual
                                         :eligible-claim-count eligible-claim-count
                                         :rounding-policy rounding-policy
                                         :cap-constrained? cap-constrained?})
                          (check-result :partial-fill/rounding-residual-bounded
                                        :not-applicable
                                        {:mode mode
                                         :rounding-policy rounding-policy
                                         :reason "no defined residual bound for this rounding policy"})))
          per-claim-conservation-ch (future
                                      (check-result :partial-fill/per-claim-conservation
                                                    (if (empty? per-claim-conservation-violations) :pass :fail)
                                                    {:violations per-claim-conservation-violations}))
          claim-key-ch (future
                         (check-result :partial-fill/claim-key-consistency
                                       (if (empty? claim-key-consistency-violations) :pass :fail)
                                       {:violations claim-key-consistency-violations}))
          principal-first-ch (future
                               (if (= :principal-first mode)
                                 (check-result :partial-fill/principal-first-priority
                                               (if (empty? principal-first-violations) :pass :fail)
                                               {:violations principal-first-violations})
                                 (check-result :partial-fill/principal-first-priority
                                               :not-applicable
                                               {:mode mode})))
          waterfall-ch (future
                         (if (= :waterfall mode)
                           (check-result :partial-fill/waterfall-priority
                                         (if (empty? waterfall-violations) :pass :fail)
                                         {:violations waterfall-violations})
                           (check-result :partial-fill/waterfall-priority
                                         :not-applicable
                                         {:mode mode})))
          non-negative-ch (future
                            (check-result :partial-fill/non-negative-amounts
                                          (if (empty? negative-amount-violations) :pass :fail)
                                          {:violations negative-amount-violations}))
          settlement-mode-ch (future
                               (check-result :partial-fill/settlement-mode-consistency
                                             (if (empty? settlement-mode-violations) :pass :fail)
                                             {:violations settlement-mode-violations
                                              :settlement-mode settlement-mode}))
          mode-valid-ch (future
                          (check-result :partial-fill/mode-valid
                                        (if (empty? mode-violations) :pass :fail)
                                        {:violations mode-violations}))
          settlement-mode-valid-ch (future
                                     (check-result :partial-fill/settlement-mode-valid
                                                   (if (empty? settlement-mode-valid-violations) :pass :fail)
                                                   {:violations settlement-mode-valid-violations}))
          overlap-ch (future
                       (check-result :partial-fill/deferred-haircut-overlap
                                     (if (empty? deferred-haircut-overlap-violations) :pass :fail)
                                     {:violations deferred-haircut-overlap-violations}))
          deferred-haircut-sum-ch (future
                                    (check-result :partial-fill/deferred-haircut-sum-bound
                                                  (if (empty? deferred-haircut-sum-violations) :pass :fail)
                                                  {:violations deferred-haircut-sum-violations}))
          evidence-ch (future
                        (check-result :partial-fill/evidence-self-consistency
                                      (if (empty? evidence-violations) :pass :fail)
                                      {:violations evidence-violations}))
          unrealized-ch (future
                          (check-result :partial-fill/unrealized-bucket-valid
                                        (if (empty? unrealized-violations) :pass :fail)
                                        {:violations unrealized-violations}))
          artifact-format-ch (future
                               (check-result :partial-fill/decision-artifact-format
                                             (if (empty? decision-artifact-violations) :pass :fail)
                                             {:violations decision-artifact-violations}))
          effective-rounding-ch
          (let [shape (execution-shape decision)
                requested-policy rounding-policy
                derived-effective (normalize-rounding-algorithm requested-policy shape)
                declared-effective (some-> (get-in decision
                                                   [:evidence :allocation-mechanism-evidence
                                                    :mechanism/result :rounding-policy])
                                           keyword)]
            (future
              (if (some? declared-effective)
                (check-result :partial-fill/effective-rounding-consistency
                              (if (= declared-effective derived-effective) :pass :fail)
                              {:requested-policy requested-policy
                               :execution-shape shape
                               :derived-effective derived-effective
                               :declared-effective declared-effective})
                (check-result :partial-fill/effective-rounding-consistency
                              :not-applicable
                              {:requested-policy requested-policy
                               :execution-shape shape
                               :reason "no recorded effective algorithm (non-mechanism path)"}))))]
      (let [results (mapv deref [conservation-ch capacity-ch per-claim-ch per-claim-conservation-ch
                                 claim-key-ch non-negative-ch settlement-mode-ch settlement-mode-valid-ch
                                 mode-valid-ch overlap-ch deferred-haircut-sum-ch evidence-ch unrealized-ch artifact-format-ch
                                 exact-pro-rata-ch rounding-fairness-ch rounding-fairness-ideal-ch rounding-remainder-ch
                                 principal-first-ch waterfall-ch
                                 residual-ch fail-action-ch effective-rounding-ch])
            failed (filterv #(= :fail (:status %)) results)]
        (when (seq failed)
          (throw (ex-info "Partial-fill closed-form checks failed"
                          {:type :closed-form-failure
                           :check-results results
                           :failed-checks failed})))
        results))))

(defn partial-fill-application-deltas

  "Return the source-bucket deductions implied by a partial-fill decision."
  [decision]
  {:principal (+ (long (get-in decision [:filled :principal] 0))
                 (long (get-in decision [:deferred :principal] 0))
                 (long (get-in decision [:haircut :principal] 0)))
   :realized-yield (+ (long (get-in decision [:filled :realized-yield] 0))
                      (long (get-in decision [:deferred :realized-yield] 0))
                      (long (get-in decision [:haircut :realized-yield] 0)))
   :deferred-yield (+ (long (get-in decision [:filled :deferred-yield] 0))
                      (long (get-in decision [:deferred :deferred-yield] 0))
                      (long (get-in decision [:haircut :deferred-yield] 0)))})

(defn validate-partial-fill-application!
  "Reject a decision that would debit more from a position bucket than exists.
   This validates the mutation boundary independently of decision generation,
   so stale, forged, or cross-position decisions cannot create negative state."
  [position decision]
  (let [deltas (partial-fill-application-deltas decision)
        violations (vec
                    (for [[bucket delta] deltas
                          :let [available (long (get position bucket 0))]
                          :when (or (neg? delta) (> delta available))]
                      {:bucket bucket :available available :debit delta}))]
    (when (seq violations)
      (throw (ex-info "partial-fill decision would underflow position"
                      {:type :partial-fill-position-underflow
                       :violations violations
                       :decision decision})))
    deltas))

(defn filled-total
  "Return the immediate custody outflow represented by a decision."
  [decision]
  (reduce + 0 (vals (:filled decision {}))))

(defn post-partial-fill-position
  "Update a position after a partial-fill settlement decision has been applied.

   Returns an updated position map with:
   - :partial-fill-affected? sticky-historical: ORed across the prior position
     and the current decision, so a GENUINE partial fill (:partial-fill
     settlement-mode) sets it true on first occurrence and a full-fill or later
     full resolution never clears it.
   - :status :unwinding while any deferred/haircut consequence remains
     outstanding; fully-resolved (full-fill) settles to :withdrawn.
   - Claimed buckets subtracted from respective fields
   - Residual entitlement preserved as deferred/haircut

   Fix: Ensures that deferred amounts are moved out of their source buckets
   to avoid double-counting when :accrue-residual-as-unrealized is active."
  [position decision]
  (let [filled (:filled decision)
        deferred (:deferred decision)
        haircut (:haircut decision)
        policy (:policy decision)
        post-accrual (get policy :post-partial-fill-accrual :accrue-residual-as-unrealized)
        {:keys [principal realized-yield deferred-yield] :as deltas}
        (validate-partial-fill-application! position decision)
        p-delta principal
        r-delta realized-yield
        d-delta deferred-yield]
    (-> position
        (pos/normalize-position)
        (update :principal - p-delta)
        (update :realized-yield - r-delta)
        (update :deferred-yield - d-delta)
        (assoc :partial-fill-affected?
               (boolean (or (:partial-fill-affected? position)
                            (partial-fill? decision))))
        (assoc :status (if (partial-fill-outstanding? decision)
                         :unwinding
                         :withdrawn))
        (cond->
         (= post-accrual :accrue-residual-as-unrealized)
          (update :unrealized-yield + (long (get deferred :principal 0))
                  (long (get deferred :realized-yield 0))
                  (long (get deferred :deferred-yield 0)))

          (not= post-accrual :accrue-residual-as-unrealized)
          (-> (update :principal-impairment + (long (get deferred :principal 0)))
              (update :deferred-yield + (long (get deferred :realized-yield 0))
                      (long (get deferred :deferred-yield 0))))

          (some pos? (vals haircut))
          (update :haircut-yield + (reduce + 0 (vals haircut)))))))

(defn apply-partial-fill
  "Apply only the validated position mutation for a partial-fill decision.

   This generic yield-layer function intentionally does not mutate
   :total-held. Protocol custody movement must be applied by the protocol's
   settlement adapter (Sew uses lifecycle/apply-partial-fill-settlement), which
   records a canonical held adjustment and enforces custody partition bounds."
  [world position decision]
  (let [owner-id (or (:owner/id position) (-> (pos/position-identity position) second))
        updated-pos (post-partial-fill-position position decision)]
    (assoc-in world [:yield/positions owner-id] updated-pos)))

(defn apply-partial-fill-with-attribution
  "Apply a partial-fill settlement to world state, wrapping the mutation in
   `with-attribution` so that downstream logging and risk monitoring can see
   the settlement details.

   Attribution context keys:
     :settlement/mode        — :full-fill or :partial-fill
     :settlement/filled      — total filled base units
     :settlement/deferred    — total deferred base units
     :settlement/haircut     — total haircut base units
     :settlement/shortage    — shortfall amount
     :settlement/module-id   — module-id
     :settlement/token       — token
     :settlement/position-id — owner-id"
  [world position decision]
  (let [ctx {:settlement/mode (:settlement-mode decision)
             :settlement/filled (filled-total decision)
             :settlement/deferred (reduce + 0 (vals (:deferred decision)))
             :settlement/haircut (reduce + 0 (vals (:haircut decision)))
             :settlement/shortage (get-in decision [:evidence :shortage] 0)
             :settlement/module-id (:module/id position)
             :settlement/token (:token position)
             :settlement/position-id (:owner/id position)}]
    (attr/with-attribution ctx
      (let [world' (apply-partial-fill world position decision)]
        (evidence/capture-event-evidence!
         :settlement-fill
         {:settlement/before (select-keys world [:total-held :yield/positions])}
         {:settlement/after (select-keys world' [:total-held :yield/positions])}
         {:settlement/decision decision}
         nil
         {:world-before world
          :world-after world'})
        world'))))

(defn batch-partial-fill
  "Process multiple partial-fill settlements in parallel compute, serial apply.

   Args:
     world   — current world state
     inputs  — collection of
               {:available-liquidity <long>
                :position            <position map>
                :policy              <optional policy>
                :opts                <optional opts>
                :liquidity-domain    <shared-liquidity identifier for batches>}

   Every input in a multi-input batch must declare :liquidity-domain, and each
   domain must occur only once. A shared domain requires a centralized allocator;
   computing multiple fills from the same snapshot is not safe. A single-input
   batch remains compatible with callers that do not need to name a domain.

   Returns updated world after all settlements applied.

   Parallel pattern:
   1. snapshot world
   2. parallel pure compute — calculate-fulfillment per input
   3. collect deterministic ordered decisions
   4. serial apply — apply-partial-fill-with-attribution per decision
   5. serial evidence capture (inside apply step)"
  [world inputs]
  (let [inputs (vec inputs)
        missing-domain-inputs (when (> (count inputs) 1)
                                (->> inputs
                                     (map-indexed vector)
                                     (keep (fn [[index input]]
                                             (when (nil? (:liquidity-domain input)) index)))
                                     vec))
        _ (when (seq missing-domain-inputs)
            (throw (ex-info "batch partial-fill requires liquidity domain"
                            {:type :batch-partial-fill-missing-liquidity-domain
                             :input-indexes missing-domain-inputs})))
        declared-domains (keep :liquidity-domain inputs)
        duplicate-domains (->> declared-domains frequencies (keep (fn [[domain n]]
                                                                    (when (> n 1) domain))) vec)
        _ (when (seq duplicate-domains)
            (throw (ex-info "batch partial-fill has shared liquidity domain"
                            {:type :batch-partial-fill-shared-liquidity
                             :liquidity-domains duplicate-domains})))
        ;; 1: snapshot world (implicit — world is captured by closure)
        ;; 2: parallel pure compute — each fulfillment is independent
        decisions (parallel/ordered-bounded-mapv
                   (or (-> execution-context/*context* :execution/claimant-parallelism) 1)
                   (fn [{:keys [available-liquidity position policy opts]}]
                     (let [policy' (if (some? policy)
                                     (merge default-partial-fill-policy policy)
                                     default-partial-fill-policy)]
                       (calculate-fulfillment available-liquidity position policy' opts)))
                   inputs)
        ;; 3-4: collect deterministic ordered, serial apply to world
        pairs (map vector inputs decisions)]
    (reduce (fn [w [input decision]]
              (apply-partial-fill-with-attribution
               w (:position input) decision))
            world
            pairs)))

(defn validate-batch-decisions
  "Run partial-fill-closed-form-checks on each decision in a batch.
   Returns aggregated validation results without modifying the decisions.

   Returns:
     {:batch/valid?        bool
      :batch/summary      {:total-decisions n :passed n :failed [...]}
      :batch/checks       [[decision check-results] ...]
      :batch/witnesses    [{:decision/index n
                            :settlement-mode kw
                            :fill-mode kw
                            :check-count n
                            :passed? bool} ...]}"
  [decisions]
  (let [checks (mapv (fn [d]
                       (try
                         (partial-fill-closed-form-checks d)
                         (catch clojure.lang.ExceptionInfo e
                           (:check-results (ex-data e)))))
                     decisions)
        indexed (map-indexed (fn [i cs]
                               {:decision-index i
                                :pass? (every? #(#{:pass :not-applicable} (:status %)) cs)
                                :failing-checks (filterv #(= :fail (:status %)) cs)})
                             checks)
        all-pass? (every? :pass? indexed)
        witnesses (mapv (fn [i decision]
                          {:decision/index i
                           :settlement-mode (:settlement-mode decision)
                           :fill-mode (get-in decision [:policy :mode])
                           :check-count (count (nth checks i))
                           :passed? (:pass? (nth indexed i))})
                        (range)
                        decisions)]
    {:batch/valid? all-pass?
     :batch/summary {:total-decisions (count decisions)
                     :passed-count (count (filter :pass? indexed))
                     :failed-decisions (vec (remove :pass? indexed))}
     :batch/witnesses witnesses
     :batch/checks (mapv vector decisions checks)}))

(defn validate-decision-artifact
  "Verify a single decision artifact's content-addressed hash integrity.
   Recomputes the artifact hash using decision-artifact and compares it
   to the :decision/hash embedded in the decision.

   Returns a check result map:
     {:check/id :artifact/hash-integrity
      :status   :pass | :fail | :not-applicable
      :details  {...}}"
  [position decision]
  (let [expected (:decision/hash (decision-artifact position decision))
        actual (:decision/hash decision)]
    (if (nil? actual)
      {:check/id :artifact/hash-integrity
       :status :not-applicable
       :details {:reason "no decision/hash present in decision"}}
      (if (= actual expected)
        {:check/id :artifact/hash-integrity
         :status :pass
         :details {:decision/hash actual}}
        {:check/id :artifact/hash-integrity
         :status :fail
         :details {:expected expected :actual actual}}))))
