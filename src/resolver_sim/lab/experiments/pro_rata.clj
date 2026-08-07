(ns resolver-sim.lab.experiments.pro-rata
  "Pro-rata fractional allocation experiment.

   Executes the canonical deterministic pro-rata allocation engine
   (resolver-sim.pro-rata.allocation/allocate) over three claims and reports
   the mechanism's own committed witness: rounds, quotas, fractional
   remainders, residual, and allocation hash. Assurance findings come from the
   engine's own invariant validators (resolver-sim.pro-rata.invariants), not
   from lab-recomputed checks."
  (:require [clojure.string :as str]
            [resolver-sim.pro-rata.allocation :as allocation]
            [resolver-sim.pro-rata.invariants :as invariants]
            [resolver-sim.hash.canonical :as hc]))

(def ^:private parties
  [{:party/id :alice :parameter/id :alice-requested}
   {:party/id :bob :parameter/id :bob-requested}
   {:party/id :carol :parameter/id :carol-requested}])

(defn- rows-from-parameters
  [parameters]
  (mapv (fn [party]
          (let [pid (:parameter/id party)]
            {:row/id (:party/id party)
             :obligation/id :claim
             :requested (long (get parameters pid))
             :weight (long (get parameters pid))}))
        parties))

(defn- apply-cap
  [rows cap-alice]
  (if (nil? cap-alice)
    rows
    (mapv (fn [row]
            (if (= :alice (:row/id row))
              (assoc row :cap (long cap-alice))
              row))
          rows)))

(defn- allocate-id
  [parameters]
  [:lab/pro-rata
   (:available parameters)
   (long (:alice-requested parameters))
   (long (:bob-requested parameters))
   (long (:carol-requested parameters))])

(defn- mechanism-findings
  "Assurance findings derived directly from the pro-rata engine's own
   invariant validators."
  [result]
  (let [violations (invariants/result-violations result)
        groups
        [{:id :pro-rata/request-hash :label "Request-set binding"
          :detail (when (not= (:request/hash result)
                              (hc/hash-with-intent {:hash/intent :projection-artifact}
                                                   (:canonical-request result)))
                    "request hash does not recompute")}
         {:id :pro-rata/allocation-hash :label "Allocation root binding"
          :detail (when-not (allocation/allocation-hash-valid? result)
                    "allocation hash does not recompute")}
         {:id :pro-rata/cap-respecting :label "Cap respecting"}
         {:id :pro-rata/quota-bounded :label "Quota bounded"}
         {:id :pro-rata/residual-reason :label "Residual declared"
          :detail (:residual-reason result)}
         {:id :pro-rata/round-trace :label "Round trace conservation"}
         {:id :pro-rata/fractional-remainder :label "Fractional remainder total"}
         {:id :pro-rata/canonical-remainder-assignment :label "Canonical remainder assignment"
          :detail (:rounding-policy result)}]]
    (mapv (fn [{:keys [id label detail]}]
            (let [bad (->> violations
                           (filter #(str/starts-with? (name (:reason %)) (name id)))
                           first)]
              {:findings/id id
               :findings/status (if bad :fail :pass)
               :findings/label label
               :findings/origin :prf
               :findings/detail (or (some-> bad pr-str) detail)}))
          groups)))

(defn run
  "Run the pro-rata allocation experiment.
   parameters := validated map from the lab registry."
  [parameters]
  (let [rows (-> (rows-from-parameters parameters)
                 (apply-cap (:cap-alice parameters)))
        request {:schema-version "pro-rata-allocation-request.v1"
                 :mechanism/version 1
                 :allocation/id (allocate-id parameters)
                 :available (long (:available parameters))
                 :rows rows
                 :rounding-policy (keyword (:rounding-policy parameters))
                 :tie-break-policy :canonical-row-id
                 :redistribution-policy (keyword (:redistribution-policy parameters))}
        result (allocation/allocate request)
        findings (mechanism-findings result)
        by-id (into {} (map (juxt :row/id identity)) (:rows result))]
    {:outcome
     {:mechanism {:id :mechanism/pro-rata-allocation :version 1}
      :available (long (:available result))
      :allocated-total (long (:allocated-total result))
      :unallocated-residual (long (:unallocated-residual result))
      :residual-reason (:residual-reason result)
      :rounding-policy (:rounding-policy result)
      :allocation/rounds (:allocation/rounds result)
      :participants (mapv (fn [party]
                            (let [id (:party/id party)
                                  row (get by-id id)]
                              {:participant/id id
                               :requested (long (:requested row))
                               :weight (long (:weight row))
                               :effective-cap (long (:effective-cap row))
                               :allocated (long (:allocated row))
                               :unmet (long (:unmet row))
                               :fractional-remainder (:fractional-remainder row)
                               :remainder-unit-awarded? (boolean (:remainder-unit-awarded? row))}))
                          parties)}
     :assessment
     {:allocation/complete (= (long (:allocated-total result))
                              (long (:available result)))
      :allocation/hash-bound (boolean (allocation/allocation-hash-valid? result))
      :fractional-remainder-total (:allocation/fractional-remainder-total result)}
     :findings findings
     :evidence
     {:roots
      {:allocation-hash (:allocation/hash result)
       :request-hash (:request/hash result)}
      :artifacts
      [{:artifact/id :pro-rata-allocation-result
        :artifact/ref (hc/hash-with-intent {:hash/intent :projection-artifact} result)}]}}))
