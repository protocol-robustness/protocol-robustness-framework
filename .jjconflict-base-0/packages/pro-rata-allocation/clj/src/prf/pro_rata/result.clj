(ns prf.pro-rata.result
  "Pure assembly of the canonical pro-rata allocation result value.")

(defn- fractional-remainder-total
  [rows]
  (let [total-ratio (reduce (fn [total {:keys [remainder-numerator remainder-denominator]}]
                              (if (zero? remainder-denominator)
                                total
                                (+ total (/ remainder-numerator remainder-denominator))))
                            0
                            (keep :fractional-remainder rows))]
    (cond
      (zero? total-ratio)
      {:rational/numerator 0 :rational/denominator 1}

      (instance? clojure.lang.Ratio total-ratio)
      {:rational/numerator (numerator total-ratio)
       :rational/denominator (denominator total-ratio)}

      (integer? total-ratio)
      {:rational/numerator total-ratio :rational/denominator 1}

      :else
      (throw (ex-info "Unexpected fractional-remainder-total type"
                      {:type (type total-ratio) :value total-ratio})))))

(defn root-input
  "Project a result to the fields committed by its allocation root.

   Excludes the self hash and the diagnostic fractional-remainder total."
  [result]
  (dissoc result :allocation/hash :allocation/fractional-remainder-total))

(defn assemble
  "Assemble result fields from normalized input, allocator output, and witness.

   The returned value includes the diagnostic fractional-remainder total;
   canonical root derivation excludes that diagnostic field as before."
  [normalized request-hash allocation witness]
  (let [{:keys [allocation/id mechanism/version available rows rounding-policy
                tie-break-policy redistribution-policy canonical-request]}
        normalized
        allocations-by-id (into {} (map (juxt :id identity) (:allocations allocation)))
        witness-by-id (into {} (map (juxt :row/id identity) (:rows witness)))
        result-rows (mapv (fn [row]
                            (let [{:keys [allocated unmet]} (get allocations-by-id (:row/id row))]
                              (assoc (get witness-by-id (:row/id row))
                                     :allocated allocated
                                     :unmet (or unmet 0))))
                          rows)]
    {:schema-version "pro-rata-allocation-result.v1"
     :mechanism {:id :mechanism/pro-rata-allocation :version version}
     :allocation/id id
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
     :rows result-rows
     :allocation/fractional-remainder-total (fractional-remainder-total result-rows)}))
