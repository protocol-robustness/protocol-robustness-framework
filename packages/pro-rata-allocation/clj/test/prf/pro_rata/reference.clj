(ns prf.pro-rata.reference
  "Allocation-only independent oracle extracted from the frozen PRF contract."
  (:require [prf.pro-rata.reference-canonical :as canonical]))

(def schemas {:allocation-request "pro-rata-allocation-request.v1"
              :allocation-result "pro-rata-allocation-result.v1"})
(def domains {:projection-artifact "PROJECTION_ARTIFACT_V1"})

(defn projection-artifact-root [body]
  (canonical/domain-hash (:projection-artifact domains) body))

(defn allocation-result-root [result]
  (projection-artifact-root
   (dissoc result :allocation/hash :allocation/fractional-remainder-total)))

(defn- allocation-error [reason data]
  (throw (ex-info "Invalid portable pro-rata allocation" (assoc data :reason reason))))

(defn- non-negative-integer? [value]
  (and (integer? value) (not (neg? value))))

(defn- row-order-key [row]
  (canonical/canonical-bytes (:row/id row)))

(defn- compare-bytes [left right]
  (loop [index 0]
    (cond
      (= index (min (alength left) (alength right))) (compare (alength left) (alength right))
      (= (aget left index) (aget right index)) (recur (inc index))
      :else (compare (bit-and 255 (aget left index)) (bit-and 255 (aget right index))))))

(defn- normalize-request [request]
  (let [required #{:schema-version :mechanism/version :allocation/id :available :rows
                   :rounding-policy :tie-break-policy :redistribution-policy}
        rows (vec (:rows request))]
    (when-not (= required (set (keys request)))
      (allocation-error :portable-contract/malformed-input {:field :request}))
    (when-not (= "pro-rata-allocation-request.v1" (:schema-version request))
      (allocation-error :portable-contract/unknown-version {:schema-version (:schema-version request)}))
    (when-not (= 1 (:mechanism/version request))
      (allocation-error :portable-contract/unknown-version {:mechanism/version (:mechanism/version request)}))
    (when-not (non-negative-integer? (:available request))
      (allocation-error :portable-contract/arithmetic-domain-violation {:field :available}))
    (when-not (= :canonical-row-id (:tie-break-policy request))
      (allocation-error :portable-contract/unknown-policy {:field :tie-break-policy}))
    (when-not (#{:floor :largest-remainder} (:rounding-policy request))
      (allocation-error :portable-contract/unknown-policy {:field :rounding-policy}))
    (when-not (#{:unallocated :redistribute-cap-excess} (:redistribution-policy request))
      (allocation-error :portable-contract/unknown-policy {:field :redistribution-policy}))
    (doseq [row rows]
      (when-not (= #{:row/id :obligation/id :requested :weight :cap} (set (keys row)))
        (allocation-error :portable-contract/malformed-input {:field :row :row row}))
      (try (canonical/canonical-bytes (:row/id row))
           (catch clojure.lang.ExceptionInfo _
             (allocation-error :portable-contract/noncanonical-input {:field :row/id})))
      (doseq [field [:requested :weight :cap]
              :let [value (get row field)]
              :when (and (some? value) (not (non-negative-integer? value)))]
        (allocation-error :portable-contract/arithmetic-domain-violation {:field field :row/id (:row/id row)})))
    (let [ids (mapv :row/id rows)]
      (when-not (= (count ids) (count (distinct ids)))
        (allocation-error :portable-contract/malformed-input {:field :row/id :detail :duplicate})))
    (assoc request :available (bigint (:available request))
           :rows (->> rows
                      (map (fn [row]
                             (let [requested (bigint (:requested row))
                                   cap (some-> (:cap row) bigint)]
                               (assoc row :requested requested :weight (bigint (:weight row))
                                      :cap cap :effective-cap (if cap (min requested cap) requested)))))
                      (sort #(compare-bytes (row-order-key %1) (row-order-key %2)))
                      vec))))

(defn- compare-remainders [left right]
  (let [comparison (compare (* (:remainder-numerator right) (:remainder-denominator left))
                            (* (:remainder-numerator left) (:remainder-denominator right)))]
    (if (zero? comparison)
      (compare-bytes (row-order-key left) (row-order-key right))
      comparison)))

(defn- quota [available weight total-weight]
  {:quota/numerator (*' available weight) :quota/denominator total-weight})

(defn- round-witness [index available active constrained]
  (let [weight-total (reduce +' 0N (map :weight active))
        committed (reduce +' 0N (map :effective-cap constrained))]
    {:round/index index :available-at-start available
     :active-row-ids (mapv :row/id active) :active-weight-total weight-total
     :quotas (mapv #(merge {:row/id (:row/id %)} (quota available (:weight %) weight-total)) active)
     :newly-cap-constrained-row-ids (mapv :row/id constrained)
     :committed-by-cap committed :available-after-caps (- available committed)}))

(defn- allocation-witness [request]
  (let [{:keys [available rows rounding-policy redistribution-policy]} request]
    (if (= redistribution-policy :unallocated)
      (let [weight-total (reduce +' 0N (map :weight rows))]
        (if (zero? weight-total)
          {:rounds [] :active rows :committed {} :remaining available :residual-reason :no-active-weight}
          (let [constrained (filterv #(>= (*' available (:weight %)) (*' (:effective-cap %) weight-total)) rows)]
            {:rounds [(round-witness 0 available rows constrained)]
             :active rows :committed {} :remaining available
             :residual-reason (if (= rounding-policy :floor) :floor-rounding :none)})))
      (loop [index 0 remaining available active rows committed {} rounds []]
        (let [weight-total (reduce +' 0N (map :weight active))]
          (cond
            (empty? active) {:rounds rounds :active [] :committed committed :remaining remaining
                             :residual-reason :no-remaining-capacity}
            (zero? weight-total) {:rounds rounds :active active :committed committed :remaining remaining
                                  :residual-reason :no-active-weight}
            :else (let [constrained (filterv #(>= (*' remaining (:weight %)) (*' (:effective-cap %) weight-total)) active)]
                    (if (seq constrained)
                      (let [constrained-ids (set (map :row/id constrained))]
                        (recur (inc index) (- remaining (reduce +' 0N (map :effective-cap constrained)))
                               (vec (remove #(contains? constrained-ids (:row/id %)) active))
                               (into committed (map (juxt :row/id :effective-cap) constrained))
                               (conj rounds (round-witness index remaining active constrained))))
                      {:rounds (conj rounds (round-witness index remaining active []))
                       :active active :committed committed :remaining remaining
                       :residual-reason (if (= rounding-policy :floor) :floor-rounding :none)}))))))))

(defn- final-awards [remaining active rounding-policy]
  (let [weight-total (reduce +' 0N (map :weight active))
        floors (into {} (map (fn [row] [(:row/id row) (if (pos? weight-total) (quot (*' remaining (:weight row)) weight-total) 0N)]) active))
        remainders (mapv (fn [row]
                           {:row/id (:row/id row)
                            :remainder-numerator (if (pos? weight-total) (mod (*' remaining (:weight row)) weight-total) 0N)
                            :remainder-denominator (max 1N weight-total)}) active)
        units (if (= rounding-policy :largest-remainder) (- remaining (reduce +' 0N (vals floors))) 0N)
        ranked (sort compare-remainders remainders)
        awarded (set (map :row/id (take units ranked)))]
    {:floors floors :remainders (into {} (map (juxt :row/id identity) remainders))
     :ranks (zipmap (map :row/id ranked) (range)) :awarded awarded}))

(defn allocate [request]
  (let [{:keys [available rows rounding-policy redistribution-policy] :as normalized} (normalize-request request)
        canonical-request {:schema-version (:schema-version normalized) :mechanism/version (:mechanism/version normalized)
                           :allocation/id (:allocation/id normalized) :available available
                           :rows (mapv #(select-keys % [:row/id :obligation/id :requested :weight :cap]) rows)
                           :rounding-policy rounding-policy :tie-break-policy (:tie-break-policy normalized)
                           :redistribution-policy redistribution-policy}
        {:keys [rounds active committed remaining residual-reason]} (allocation-witness normalized)
        total-weight (reduce +' 0N (map :weight rows))
        constrained (if (and (= redistribution-policy :unallocated) (pos? total-weight))
                      (filterv #(>= (*' available (:weight %)) (*' (:effective-cap %) total-weight)) rows) [])
        constrained-ids (set (map :row/id constrained))
        allocation-active (if (= redistribution-policy :unallocated) (vec (remove #(contains? constrained-ids (:row/id %)) rows)) active)
        allocation-weight (if (= redistribution-policy :unallocated) total-weight (reduce +' 0N (map :weight active)))
        floor-rows (if (= redistribution-policy :unallocated) rows allocation-active)
        amount-for-final (if (= redistribution-policy :unallocated) available remaining)
        floors (into {} (map (fn [row] [(:row/id row) (if (pos? allocation-weight)
                                                        (quot (*' amount-for-final (:weight row)) allocation-weight) 0N)]) floor-rows))
        remainder-rows (mapv (fn [row] {:row/id (:row/id row)
                                        :remainder-numerator (if (pos? allocation-weight) (mod (*' amount-for-final (:weight row)) allocation-weight) 0N)
                                        :remainder-denominator (max 1N allocation-weight)}) allocation-active)
        ranked (sort compare-remainders remainder-rows)
        units (if (and (= rounding-policy :largest-remainder) (pos? allocation-weight))
                (- amount-for-final (reduce +' 0N (vals floors))) 0N)
        awarded (set (map :row/id (take units ranked)))
        ranks (if (pos? allocation-weight) (zipmap (map :row/id ranked) (range)) {})
        remainder-by-id (into {} (map (juxt :row/id identity) remainder-rows))
        result-base
        {:schema-version (:allocation-result schemas) :mechanism {:id :mechanism/pro-rata-allocation :version 1}
         :allocation/id (:allocation/id normalized) :canonical-request canonical-request
         :request/hash (projection-artifact-root {:intent :projection-artifact :artifact canonical-request})
         :available available :rounding-policy rounding-policy :tie-break-policy :canonical-row-id
         :redistribution-policy redistribution-policy
         :redistribution (when (= redistribution-policy :redistribute-cap-excess)
                           {:passes (mapv (fn [round]
                                            {:pass (:round/index round)
                                             :available-at-start (:available-at-start round)
                                             :active-ids (:active-row-ids round)
                                             :active-weight-total (:active-weight-total round)
                                             :capped-ids (:newly-cap-constrained-row-ids round)
                                             :excess (:available-at-start round)
                                             :newly-capped-ids (:newly-cap-constrained-row-ids round)
                                             :committed-by-cap (:committed-by-cap round)
                                             :available-after-caps (:available-after-caps round)}) rounds)
                            :total-passes (count rounds) :residual-reason residual-reason})
         :allocation/rounds rounds
         :rows (mapv (fn [row]
                       (let [id (:row/id row) committed? (contains? committed id) constrained? (contains? constrained-ids id)
                             active? (boolean (some #{id} (map :row/id allocation-active)))
                             q (if committed?
                                 (let [round (first (filter #(some #{id} (:newly-cap-constrained-row-ids %)) rounds))]
                                   (select-keys (first (filter #(= id (:row/id %)) (:quotas round))) [:quota/numerator :quota/denominator]))
                                 (quota amount-for-final (:weight row) (max 1N allocation-weight)))
                             uncapped-award (+ (get floors id 0N) (if (contains? awarded id) 1N 0N))
                             allocated (cond committed? (get committed id) constrained? (:effective-cap row) active? uncapped-award :else 0N)]
                         {:row/id id :obligation/id (:obligation/id row) :requested (:requested row) :weight (:weight row)
                          :declared-cap (:cap row) :effective-cap (:effective-cap row)
                          :initial-quota (quota available (:weight row) (max 1N total-weight)) :effective-quota q
                          :floor-allocation (if (or committed? constrained?) (:effective-cap row) (get floors id 0N))
                          :fractional-remainder (if committed?
                                                  {:remainder-numerator 0N
                                                   :remainder-denominator (:quota/denominator q)}
                                                  (select-keys (get remainder-by-id id {:remainder-numerator 0N :remainder-denominator (max 1N allocation-weight)}) [:remainder-numerator :remainder-denominator]))
                          :allocated allocated :unmet (if (= redistribution-policy :unallocated) (max 0N (- (get floors id 0N) allocated)) 0N)
                          :remainder-rank (when active? (get ranks id)) :remainder-unit-awarded? (boolean (and active? (contains? awarded id)))})) rows)}
        allocated-total (reduce +' 0N (map :allocated (:rows result-base)))
        residual (if (= redistribution-policy :redistribute-cap-excess) (- remaining (reduce +' 0N (map #(get floors (:row/id %) 0N) allocation-active)))
                     (- available allocated-total (reduce +' 0N (map :unmet (:rows result-base)))))
        result-base (assoc result-base :allocated-total allocated-total :unallocated-residual residual
                           :residual-reason (if (pos? residual) residual-reason :none))
        fractional-total (reduce (fn [total {:keys [fractional-remainder]}]
                                   (+ total (/ (:remainder-numerator fractional-remainder) (:remainder-denominator fractional-remainder)))) 0 (:rows result-base))]
    (assoc result-base :allocation/hash (allocation-result-root result-base)
           :allocation/fractional-remainder-total
           (if (ratio? fractional-total)
             {:rational/numerator (numerator fractional-total) :rational/denominator (denominator fractional-total)}
             {:rational/numerator fractional-total :rational/denominator 1N}))))

(defn verify-allocation-result [request result]
  (let [expected (allocate request)]
    (when-not (= expected result)
      (allocation-error :portable-contract/projection-mismatch {:expected expected :actual result}))
    (when-not (= (:allocation/hash result) (allocation-result-root result))
      (allocation-error :portable-contract/root-mismatch {}))
    true))

