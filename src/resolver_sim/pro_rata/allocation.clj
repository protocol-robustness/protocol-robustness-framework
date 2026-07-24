(ns resolver-sim.pro-rata.allocation
  "Domain-neutral deterministic integer pro-rata allocation.

   This namespace owns row normalization and mathematical allocation evidence.
   It deliberately does not inspect world state, select accounts, classify
   shortfall, or apply a state transition. Domain adapters supply canonical
   rows and interpret the resulting allocated and unmet quantities."
  (:require [resolver-sim.economics.payoffs :as payoffs]
            [resolver-sim.hash.canonical :as hc]))

(defn- non-negative-integer?
  [value]
  (and (integer? value) (not (neg? value))))

(defn- invalid!
  [reason data]
  (throw (ex-info "Invalid pro-rata allocation request" (assoc data :reason reason))))

(defn canonical-id-key
  "A typed, byte-stable ordering key for identities.  `pr-str` is deliberately
   not used here: printed representation is not an allocation contract."
  [identity]
  (try
    (hc/validate-canonical-value! identity)
    (letfn [(key* [value]
              (cond
                (keyword? value) [:keyword (or (namespace value) "") (name value)]
                (string? value) [:string value]
                (vector? value) [:vector (mapv key* value)]
                ;; Map ordering is supplied solely by the canonical encoding.
                ;; It remains typed and deterministic without relying on map
                ;; iteration or a printed representation.
                (map? value) [:map (mapv #(bit-and (int %) 0xff) (hc/canonical-bytes value))]
                :else [:scalar (mapv #(bit-and (int %) 0xff) (hc/canonical-bytes value))]))]
      (key* identity))
    (catch clojure.lang.ExceptionInfo error
      (invalid! :unsupported-allocation-row-id
                {:row/id identity :cause (ex-data error)}))))

(defn canonical-rows
  "Validate and canonically order domain-neutral allocation rows.

   `:row/id` is an allocation identity, not a participant identity. It must be
   unique so duplicate economic rows remain visible rather than being collapsed.
   Repeated obligation IDs or identical requested/weight/cap values are valid."
  [rows]
  (let [rows (vec (or rows []))
        ids (mapv :row/id rows)]
    (when (some nil? ids)
      (invalid! :missing-allocation-row-id {:rows rows}))
    (when-not (= (count ids) (count (distinct ids)))
      (invalid! :duplicate-allocation-row-id {:row-ids ids}))
    (doseq [row rows]
      (canonical-id-key (:row/id row)))
    (doseq [row rows
            field [:requested :weight :cap]
            :let [value (get row field)]
            :when (and (some? value) (not (non-negative-integer? value)))]
      (invalid! :invalid-allocation-row-amount
                {:row/id (:row/id row) :field field :value value}))
    (->> rows
         (mapv (fn [row]
                 (let [declared-cap (:cap row)
                       requested (:requested row)]
                   {:row/id (:row/id row)
                    :obligation/id (:obligation/id row)
                        ;; All persisted allocation quantities use BigInt so
                        ;; 3 and 3N are semantically and hash-equivalent.
                    :requested (bigint requested)
                    :weight (bigint (:weight row))
                    :declared-cap (some-> declared-cap bigint)
                    :effective-cap (bigint (if (some? declared-cap)
                                             (min requested declared-cap)
                                             requested))})))
         (sort-by (comp canonical-id-key :row/id))
         vec)))

(defn- rounding->payoffs
  [rounding-policy]
  (case rounding-policy
    :largest-remainder :floor-with-largest-remainder
    :floor :floor
    (invalid! :unsupported-rounding-policy {:rounding-policy rounding-policy})))

(defn- compare-fractions-desc
  [left right]
  (let [comparison (compare (* (:remainder-numerator right) (:remainder-denominator left))
                            (* (:remainder-numerator left) (:remainder-denominator right)))]
    (if (zero? comparison)
      (compare (canonical-id-key (:row/id left)) (canonical-id-key (:row/id right)))
      comparison)))

(defn- witness-round
  [round-index available active]
  (let [weight-total (reduce + 0 (map :weight active))]
    {:round/index round-index
     :available-at-start available
     :active-row-ids (mapv :row/id active)
     :active-weight-total weight-total
     :quotas (mapv (fn [row]
                     {:row/id (:row/id row)
                      :quota/numerator (* available (:weight row))
                      :quota/denominator weight-total})
                   active)}))

(defn- redistribution-witness
  "Construct a mathematical cap-redistribution witness. Rounding is performed
   once over the final active group; cap-constrained rows are committed before
   that final rounding group is formed."
  [available rows rounding-policy redistribution-policy]
  (loop [round-index 0
         remaining available
         active rows
         committed {}
         rounds []]
    (let [weight-total (reduce + 0 (map :weight active))]
      (cond
        (empty? active)
        {:rounds rounds :committed committed :active []
         :remaining remaining
         :residual-reason (if (seq rows) :all-participants-capped :no-remaining-capacity)}

        (zero? weight-total)
        {:rounds rounds :committed committed :active active
         :remaining remaining :residual-reason :no-active-weight}

        :else
        (let [round (witness-round round-index remaining active)
              cap-constrained
              (filterv (fn [row]
                         (>= (* remaining (:weight row))
                             (* (:effective-cap row) weight-total)))
                       active)
              can-redistribute? (and (= redistribution-policy :redistribute-cap-excess)
                                     (seq cap-constrained))]
          (if can-redistribute?
            (let [committed-amount (reduce + 0 (map :effective-cap cap-constrained))
                  constrained-ids (set (map :row/id cap-constrained))
                  round (assoc round
                               :newly-cap-constrained-row-ids (mapv :row/id cap-constrained)
                               :committed-by-cap committed-amount
                               :available-after-caps (- remaining committed-amount))]
              (recur (inc round-index)
                     (- remaining committed-amount)
                     (vec (remove #(contains? constrained-ids (:row/id %)) active))
                     (merge committed (into {} (map (fn [row]
                                                      [(:row/id row) (:effective-cap row)])
                                                    cap-constrained)))
                     (conj rounds round)))
            {:rounds (conj rounds (assoc round
                                         :newly-cap-constrained-row-ids []
                                         :committed-by-cap 0
                                         :available-after-caps remaining))
             :committed committed
             :active active
             :remaining remaining
             :residual-reason (if (= rounding-policy :floor) :floor-rounding :none)}))))))

(defn- witness-rows
  [available rows rounding-policy redistribution-policy]
  (let [{:keys [rounds committed active remaining residual-reason]}
        (redistribution-witness available rows rounding-policy redistribution-policy)
        weight-total (reduce + 0 (map :weight active))
        final-quota (fn [row]
                      {:quota/numerator (* remaining (:weight row))
                       :quota/denominator (if (pos? weight-total) weight-total 1)})
        floors (into {}
                     (map (fn [row]
                            [(:row/id row) (if (pos? weight-total)
                                             (quot (* remaining (:weight row)) weight-total)
                                             0)])
                          active))
        remainder-rows (mapv (fn [row]
                               (let [numerator (* remaining (:weight row))
                                     denominator (if (pos? weight-total) weight-total 1)]
                                 {:row/id (:row/id row)
                                  :remainder-numerator (mod numerator denominator)
                                  :remainder-denominator denominator}))
                             active)
        remainder-units (if (= rounding-policy :largest-remainder)
                          (- remaining (reduce + 0 (vals floors)))
                          0)
        awarded-ids (set (map :row/id (take remainder-units
                                            (sort compare-fractions-desc remainder-rows))))
        ranks (zipmap (map :row/id (sort compare-fractions-desc remainder-rows)) (range))]
    {:allocation/rounds rounds
     :residual-reason residual-reason
     :rows (mapv (fn [row]
                   (let [id (:row/id row)
                         committed? (contains? committed id)
                         quota (if committed?
                                 (let [round (first (filter #(some #{id} (:newly-cap-constrained-row-ids %)) rounds))
                                       entry (first (filter #(= id (:row/id %)) (:quotas round)))]
                                   (select-keys entry [:quota/numerator :quota/denominator]))
                                 (final-quota row))
                         floor-allocation (if committed? (:effective-cap row) (get floors id 0))
                         awarded? (and (not committed?) (contains? awarded-ids id))]
                     (assoc row
                            :initial-quota {:quota/numerator (* available (:weight row))
                                            :quota/denominator (max 1 (reduce + 0 (map :weight rows)))}
                            :effective-quota quota
                            :floor-allocation floor-allocation
                            :fractional-remainder {:remainder-numerator (if committed? 0 (mod (:quota/numerator quota) (:quota/denominator quota)))
                                                   :remainder-denominator (:quota/denominator quota)}
                            :remainder-rank (when-not committed? (get ranks id))
                            :remainder-unit-awarded? awarded?)))
                 rows)}))

(defn allocate
  "Allocate a constrained integer quantity across canonical allocation rows.

   Input:
   {:schema-version pro-rata-allocation-request.v1
    :mechanism/version 1
    :allocation/id ... :available N :rows [...] :rounding-policy :largest-remainder
    :tie-break-policy :canonical-row-id
    :redistribution-policy :unallocated | :redistribute-cap-excess}

   The result is a hash-committed mathematical evidence envelope. It contains
   no account, token, participant, or transition semantics."
  [{:keys [available rows rounding-policy tie-break-policy redistribution-policy progress-atom]
    :or {rounding-policy :largest-remainder
         tie-break-policy :canonical-row-id
         redistribution-policy :unallocated}
    :as request}]
  (let [allocation-id (:allocation/id request)
        schema-version (:schema-version request "pro-rata-allocation-request.v1")
        mechanism-version (:mechanism/version request 1)
        available (when (integer? available) (bigint available))]
    (when-not (= "pro-rata-allocation-request.v1" schema-version)
      (invalid! :unsupported-allocation-request-schema {:schema-version schema-version}))
    (when-not (= 1 mechanism-version)
      (invalid! :unsupported-allocation-mechanism-version {:mechanism/version mechanism-version}))
    (when-not allocation-id
      (invalid! :missing-allocation-id {}))
    (try
      (hc/validate-canonical-value! allocation-id)
      (catch clojure.lang.ExceptionInfo error
        (invalid! :unsupported-allocation-id
                  {:allocation/id allocation-id :cause (ex-data error)})))
    (when-not (non-negative-integer? available)
      (invalid! :invalid-available {:available available}))
    (when-not (= :canonical-row-id tie-break-policy)
      (invalid! :unsupported-tie-break-policy {:tie-break-policy tie-break-policy}))
    (when-not (#{:unallocated :redistribute-cap-excess} redistribution-policy)
      (invalid! :unsupported-redistribution-policy
                {:redistribution-policy redistribution-policy}))
    (let [rows (canonical-rows rows)
        ;; Persist request-shaped rows, rather than internal normalized rows:
        ;; replay must feed precisely the same public contract back into
        ;; `allocate`.
          canonical-request-rows (mapv (fn [row]
                                         {:row/id (:row/id row)
                                          :obligation/id (:obligation/id row)
                                          :requested (:requested row)
                                          :weight (:weight row)
                                          :cap (:declared-cap row)})
                                       rows)
          canonical-request {:schema-version schema-version
                             :mechanism/version mechanism-version
                             :allocation/id allocation-id
                             :available available
                             :rows canonical-request-rows
                             :rounding-policy rounding-policy
                             :tie-break-policy tie-break-policy
                             :redistribution-policy redistribution-policy}
          request-hash (hc/hash-with-intent {:hash/intent :projection-artifact}
                                            canonical-request)
          total-weight (reduce + 0 (map :weight rows))
          allocation-request {:amount available
                              :items rows
                              :id-fn :row/id
                              :weight-fn :weight
                              :cap-fn :effective-cap
                              :rounding (rounding->payoffs rounding-policy)
                              :remainder-policy :unallocated
                              :ordering-policy :canonical-id
                              :progress-atom progress-atom}
          allocation (case redistribution-policy
                       :unallocated
                       (payoffs/allocate-pro-rata allocation-request)
                       :redistribute-cap-excess
                       (payoffs/allocate-pro-rata-with-redistribution
                        (assoc allocation-request :ordering-policy :canonical-id)))
          by-id (into {} (map (juxt :id identity) (:allocations allocation)))
          witness (witness-rows available rows rounding-policy redistribution-policy)
          witness-by-id (into {} (map (juxt :row/id identity) (:rows witness)))
          result-base
          {:schema-version "pro-rata-allocation-result.v1"
           :mechanism {:id :mechanism/pro-rata-allocation :version 1}
           :allocation/id allocation-id
           :canonical-request canonical-request
           :request/hash request-hash
           :available available
           :allocated-total (bigint (:total-allocated allocation))
           :unallocated-residual (bigint (:remainder allocation))
           :rounding-policy rounding-policy
           :tie-break-policy tie-break-policy
           :redistribution-policy redistribution-policy
           :redistribution (:redistribution allocation)
           :allocation/rounds (:allocation/rounds witness)
           :residual-reason (if (pos? (:remainder allocation))
                              (:residual-reason witness)
                              :none)
           :rows (mapv (fn [row]
                         (let [{:keys [allocated unmet]} (get by-id (:row/id row))]
                           (assoc (get witness-by-id (:row/id row))
                                  :allocated allocated
                                  :unmet (or unmet 0))))
                       rows)}
          allocation-hash (hc/hash-with-intent {:hash/intent :projection-artifact}
                                               result-base)]
      (assoc result-base :allocation/hash allocation-hash))))

(defn allocation-hash-valid?
  "Return true only when the persisted result hash commits to every witness
   field and final allocation row."
  [result]
  (= (:allocation/hash result)
     (hc/hash-with-intent {:hash/intent :projection-artifact}
                          (dissoc result :allocation/hash))))
