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
            [resolver-sim.util.evidence :as util-evidence]
            [resolver-sim.io.event-evidence :as evidence]))

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
   historical allocator result shape consumed by propagation evidence."
  [available-liquidity rows rounding-policy & [progress-atom]]
  (let [row-id (fn [row]
                 [:shared-withdrawal-row
                  (:obligation-id row)
                  (:source-position-id row)
                  (:key row)])
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
          :rounding-policy (if (= :floor rounding-policy) :floor :largest-remainder)
          :tie-break-policy :canonical-row-id
          :redistribution-policy :redistribute-cap-excess
          :progress-atom progress-atom})
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

(defn calculate-fulfillment-pro-rata
  "Pro-rata fill: each claim bucket receives a proportional share of the available
   liquidity. Exact ratios computed, then quantized via configured rounding policy.

   Optional opts:
     :rows — vector of {:key k :owed v :weight w :cap c} for decoupled
             weight/cap allocation. When absent, weight and cap are both
             derived from the requested amount (existing behavior)."
  [available-liquidity requested policy & [opts]]
  (let [rows (:rows opts)
        progress-atom (:progress-atom opts)
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
              alloc (allocate-shared-withdrawal-rows available-liquidity rows rounding-policy progress-atom)
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
                           (seq requested))
              rounding-policy (:rounding-policy policy :floor-and-carry)
              alloc (case rounding-policy
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
                                                          (select-keys opts [:rows :progress-atom]))
         :principal-first (calculate-fulfillment-principal-first available requested policy
                                                                 (select-keys opts [:rows :progress-atom]))
         :waterfall       (calculate-fulfillment-waterfall available requested policy
                                                           (select-keys opts [:rows :progress-atom])))))))

(defn partial-fill?
  "True if the settlement decision represents a partial fill."
  [decision]
  (= :partial-fill (:settlement-mode decision)))

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
         decision-hash (str "sha256:"
                            (hc/hash-with-intent {:hash/intent :evidence-record}
                                                 base))]
     (assoc base
            :decision/id (str "partial-fill-" (subs decision-hash 7 (min (count decision-hash) 23)))
            :decision/hash decision-hash
            ;; JSON replay output cannot distinguish string claim keys from
            ;; keyword claim buckets. Preserve the exact typed hash preimage
            ;; for independent post-persistence verification.
            :decision/preimage (pr-str base)))))

(defn attach-decision-artifact
  "Attach a partial-fill decision artifact to world state under a stable map."
  [world artifact]
  (assoc-in world [:yield/partial-fill-decisions (:decision/id artifact)] artifact))

(defn canonical-accounting-entries
  "Return a deterministically ordered vector of entries. This is a list
   canonicalization: duplicate entries are retained deliberately."
  [entries]
  (->> entries (sort-by pr-str) vec))

(defn accounting-entry-set-hash
  "Hash the duplicate-preserving canonical accounting-entry list."
  [entries]
  (str "sha256:" (hc/hash-with-intent {:hash/intent :evidence-record}
                                       (canonical-accounting-entries entries))))

(defn pro-rata-propagation-artifact
  "Build the authoritative application record for a shared pro-rata decision."
  [decision policy policy-selection]
  (let [policy (propagation-policy/normalize-and-validate policy)
        policy-ref (propagation-policy/policy-reference policy)
        rows (get-in decision [:evidence :allocation-rows] [])
        participants
        (mapv (fn [{:keys [key obligation-id source-position-id owed effective-cap filled deferred]}]
                (let [fulfilled (long (or filled 0))
                      deferred (long (or deferred 0))
                      owed (long (or owed 0))]
                  {:participant-id key
                   :obligation-before owed
                   :eligible-obligation owed
                   :effective-cap (long (or effective-cap owed))
                   :initial-allocation fulfilled
                   :redistributed-in 0
                   :final-allocation fulfilled
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
        base {:schema-version "pro-rata-propagation.v2"
              :calculation-ref (:decision/id decision)
              :outcome-ref (:decision/hash decision)
              :allocation-kind :shared-withdrawal-shortfall
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
                            :propagation-policy policy-ref
                                          :policy-selection policy-selection
                                          :participants participants
              :summary {:obligation-before (sum-field :obligation-before)
                        :eligible-obligation (sum-field :eligible-obligation)
                        :available available :allocated allocated :fulfilled allocated
                        :deferred (sum-field :deferred) :unmet 0 :waived 0
                        :obligation-after (sum-field :obligation-after)
                        :unallocated-residual residual
                        :residual-reason (get-in decision [:evidence :residual-reason])}
              :propagation {:policy :defer-shortfall :next-state :withdrawal-claims
                            :reallocation-eligible? true
                            :rounding-propagation-policy (get-in policy [:rounding :propagation-policy])
                            :idempotency-key [:pro-rata-propagation (:decision/id decision)
                                              (:decision/hash decision) (:policy/hash policy)]}
              :applications (mapv (fn [p]
                                    {:participant-id (:participant-id p)
                                     :fulfilled (:fulfilled p)
                                     :shortfall {:amount (:deferred p)
                                                 :classification (get-in policy [:shortfall :classification])
                                                 :next-position-ref (some-> p :next-position :position/id)}
                                     :accounting-entry {:account [:participant (:participant-id p) :withdrawn]
                                                        :delta (:fulfilled p)}})
                                  participants)
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
              :reconciliation {:allocation-applied? true :shortfalls-preserved? true
                               :capacity-reconciled? true :accounting-reconciled? true
                               :residual-reconciled? true}
              :status :committed}
        entry-hash (accounting-entry-set-hash (:accounting-entries base))
        base (assoc base :accounting-entry-set-hash entry-hash)
        artifact-hash (str "sha256:" (hc/hash-with-intent {:hash/intent :evidence-record} base))]
    (assoc base
           :propagation/id (str "pro-rata-propagation-" (subs artifact-hash 7 (min (count artifact-hash) 23)))
           :propagation/hash artifact-hash)))

(defn validate-pro-rata-propagation
  "Validate propagation-policy binding separately from allocation arithmetic.
   Returns structured reasons so callers can distinguish policy violations."
  [artifact]
  (try
    (let [ref (:propagation-policy artifact)
          snapshot (:policy/snapshot ref)
          policy (propagation-policy/normalize-and-validate snapshot)
          allocation-ref (:allocation/reference artifact)
          reference-errors (cond-> []
                             (not= "pro-rata-allocation-reference.v1" (:schema-version allocation-ref)) (conj :allocation-reference-schema-mismatch)
                             (nil? (:allocation/id allocation-ref)) (conj :allocation-reference-id-missing)
                             (nil? (:allocation/hash allocation-ref)) (conj :allocation-reference-hash-missing)
                             (not= {:id :mechanism/pro-rata-allocation :version 1}
                                   (:mechanism allocation-ref)) (conj :allocation-reference-mechanism-mismatch)
                             (not= (:calculation-ref artifact)
                                   (get-in allocation-ref [:source-evidence :artifact/id])) (conj :allocation-reference-source-id-mismatch)
                             (not= (:outcome-ref artifact)
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
                  (:participants artifact []))]
      {:valid? (empty? (concat reference-errors policy-errors participant-errors))
             :calculation-errors []
             :policy-errors (vec (concat reference-errors policy-errors participant-errors))})
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
                           (not= (:source-evidence expected-ref) (:source-evidence actual-ref)) (conj {:reason :propagation-decision-reference-mismatch}))
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
    (vec (concat mechanism-evidence-errors
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

(defn- rounding-fairness-violations
  "Check rounding fairness for integer allocation.
   Returns a vector of violation maps (empty = pass).
   For each claim, verifies that |actual_fill - ideal| <= max-rounding-error.
   For largest-remainder, also verifies remainder-ranking correctness.

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
                    max-error (case rounding-policy
                                :largest-remainder 1
                                0)]
                (when (> error max-error)
                  (swap! violations conj
                         {:claim k :requested requested :ideal-floor ideal-floor
                          :actual actual :error error :max-error max-error
                          :kind :ideal-floor-violation}))))
          ;; Largest-remainder: verify remainder ranking
          ranking-violations (when (= :largest-remainder rounding-policy)
                               (let [remainders (->> ideals
                                                     (sort-by (fn [[k v]] [(- (:fraction-remainder v)) (str k)]))
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
   :partial-fill/pro-rata-cross-product  :validation.class/allocation-property
   :partial-fill/rounding-fairness-ideal :validation.class/allocation-property
   :partial-fill/rounding-fairness-remainder-ranking :validation.class/allocation-property
   :partial-fill/principal-first-priority :validation.class/allocation-property
   :partial-fill/waterfall-priority      :validation.class/allocation-property
   :partial-fill/rounding-residual-bounded :validation.class/allocation-property})

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
   - :partial-fill/pro-rata-cross-product
   - :partial-fill/principal-first-priority
   - :partial-fill/waterfall-priority
   - :partial-fill/rounding-residual-bounded

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
        ;; Strict equal fill ratios are only meaningful when every ideal share
        ;; is representable in whole units. Largest-remainder allocations may
        ;; legitimately award one deterministic dust unit to one claimant.
        strict-pro-rata? (and (= :pro-rata mode)
                              (not cap-constrained?)
                              (or (not= :largest-remainder rounding-policy)
                                  (every? (fn [[_ claim]]
                                            (zero? (mod (* (long claim) available)
                                                        (max 1 total-requested))))
                                          positive-claims)))
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
        rounding-applicable? (and (not cap-constrained?)
                                  (#{:floor-and-carry :floor :largest-remainder :principal-protective-floor} rounding-policy))
        residual-ok? (if cap-constrained?
                       (and (= residual (long (get-in decision [:evidence :unallocated-residual] 0)))
                            (or (zero? residual)
                                (= :all-participants-cap-constrained
                                   (get-in decision [:evidence :residual-reason]))))
                       (case rounding-policy
                         (:floor-and-carry :floor :principal-protective-floor)
                         (and (<= 0 residual)
                              (< residual (max 1 eligible-claim-count)))
                         :largest-remainder
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
          cross-product-ch (future
                             (if strict-pro-rata?
                               (check-result :partial-fill/pro-rata-cross-product
                                             (if (empty? pro-rata-violations) :pass :fail)
                                             {:violations pro-rata-violations})
                               (check-result :partial-fill/pro-rata-cross-product
                                             :not-applicable
                                             {:mode mode
                                              :rounding-policy rounding-policy
                                              :reason (if cap-constrained?
                                                        "effective caps require constrained redistribution rather than one global ratio"
                                                        "indivisible pro-rata allocation is checked by rounding fairness")})))
          rounding-fairness-ideal-ch (future
                                       (if (and (= :pro-rata mode) (not cap-constrained?))
                                         (let [violations (rounding-fairness-violations
                                                           positive-claims filled available
                                                           total-requested rounding-policy)]
                                           (check-result :partial-fill/rounding-fairness-ideal
                                                         (if (empty? violations) :pass :fail)
                                                         {:violations violations
                                                          :max-allowed-error (case rounding-policy
                                                                               :largest-remainder 1
                                                                               0)
                                                          :ideal-fills (compute-ideal-fills
                                                                        positive-claims total-requested available)}))
                                         (check-result :partial-fill/rounding-fairness-ideal
                                                       :not-applicable {:mode mode
                                                                        :reason (when cap-constrained?
                                                                                  "effective caps require constrained redistribution")})))
          rounding-remainder-ch (future
                                  (if (and (= :largest-remainder rounding-policy) (not cap-constrained?))
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
                                                                           (sort-by (fn [[k v]] [(- (:fraction-remainder v)) (str k)]))
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
                                             {:violations decision-artifact-violations}))]
      (mapv deref [conservation-ch capacity-ch per-claim-ch per-claim-conservation-ch
                   claim-key-ch non-negative-ch settlement-mode-ch settlement-mode-valid-ch
                   mode-valid-ch overlap-ch deferred-haircut-sum-ch evidence-ch unrealized-ch artifact-format-ch
                   cross-product-ch rounding-fairness-ideal-ch rounding-remainder-ch
                   principal-first-ch waterfall-ch
                   residual-ch]))))

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
   - :partial-fill-affected? set to true
   - :status set to :unwinding (unless already terminal)
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
        (assoc :partial-fill-affected? true)
        (assoc :status :unwinding)
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
        decisions (util-evidence/contextual-pmap
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
  (let [checks (mapv partial-fill-closed-form-checks decisions)
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
