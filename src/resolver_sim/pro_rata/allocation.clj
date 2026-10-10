(ns resolver-sim.pro-rata.allocation
  "Clojure realization of deterministic integer pro-rata allocation.

   portable-pro-rata-contract.v1 owns the canonical request, normalization,
   arithmetic, evidence, and allocation-root semantics. This namespace exposes
   the production Clojure API and validates/realizes that contract. The
   single-pass claimant engine and detached parallel machinery live in
   resolver-sim.pro-rata.engine; cap redistribution lives in
   resolver-sim.pro-rata.redistribution.

   This realization deliberately does not inspect world state, select accounts,
   classify application shortfall, or apply a state transition. Domain adapters
   supply canonical rows and interpret allocated and unmet quantities. Production
   pro-rata namespaces must never depend on protocol, runner, research,
   application, or generic-economics allocator namespaces."
  (:require [resolver-sim.execution.realization :as realization]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.pro-rata.engine :as engine]
            [resolver-sim.pro-rata.redistribution :as redistribution]
            [resolver-sim.pro-rata-contract.contract :as portable-contract]
            [prf.pro-rata.rows :as allocation-rows]
            [prf.pro-rata.witness :as witness]
            [prf.pro-rata.request :as allocation-request]))

(defn allocate-pro-rata [request]
  (let [state (realization/open)]
    (binding [realization/*claimant-execution-realization* state]
      (let [result (engine/allocate-pro-rata* request)]
        (realization/complete! state)
        result))))

(def ^:const mechanism-version 1)

(def ^:const allocation-policy-schema-version "pro-rata-allocation-policy.v1")

(defn allocation-policy-root
  "Derive the canonical domain-neutral policy identity from an authoritative
  pro-rata allocation result. This is a read-only lineage projection: it does
  not alter the existing allocation result artifact or its root."
  [result]
  (when-not (and (= "pro-rata-allocation-result.v1" (:schema-version result))
                 (map? (:mechanism result))
                 (contains? result :rounding-policy)
                 (contains? result :tie-break-policy)
                 (contains? result :redistribution-policy))
    (throw (ex-info "Cannot derive pro-rata policy root from allocation result"
                    {:reason :primitive-result-invalid})))
  (hc/hash-with-intent
   {:hash/intent :pro-rata-allocation-policy}
   {:schema/version allocation-policy-schema-version
    :mechanism/id (get-in result [:mechanism :id])
    :mechanism/version (get-in result [:mechanism :version])
    :rounding-policy (:rounding-policy result)
    :tie-break-policy (:tie-break-policy result)
    :redistribution-policy (:redistribution-policy result)}))

(defn canonical-id-key
  "A typed, byte-stable ordering key for identities.  `pr-str` is deliberately
   not used here: printed representation is not an allocation contract."
  [identity]
  (allocation-rows/canonical-id-key identity))

(defn canonical-rows
  "Validate and canonically order domain-neutral allocation rows.

   `:row/id` is an allocation identity, not a participant identity. It must be
   unique so duplicate economic rows remain visible rather than being collapsed.
   Repeated obligation IDs or identical requested/weight/cap values are valid."
  [rows]
  (allocation-rows/canonical-rows rows))

(defn- rounding->payoffs
  [rounding-policy]
  (case rounding-policy
    :largest-remainder :floor-with-largest-remainder
    :floor :floor
    (throw (ex-info "Invalid pro-rata allocation request"
                    {:reason :unsupported-rounding-policy
                     :rounding-policy rounding-policy}))))

(defn- witness-rows
  [available rows rounding-policy redistribution-policy]
  (witness/witness-rows available rows rounding-policy redistribution-policy))

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
  [request]
  (let [normalized (allocation-request/normalize request)
        allocation-id (:allocation/id normalized)
        req-mechanism-version (:mechanism/version normalized)
        available (:available normalized)
        rows (:rows normalized)
        rounding-policy (:rounding-policy normalized)
        tie-break-policy (:tie-break-policy normalized)
        redistribution-policy (:redistribution-policy normalized)
        on-progress (:on-progress request)
        progress-atom (:progress-atom request)
        parallelism (:parallelism request)
        quiescence-timeout-seconds (:execution/quiescence-timeout-seconds request)]
    (let [canonical-request (:canonical-request normalized)
          request-hash (hc/hash-with-intent {:hash/intent :projection-artifact}
                                            canonical-request)
          _total-weight (reduce + 0 (map :weight rows))
          allocation-request {:amount available
                              :items rows
                              :id-fn :row/id
                              :weight-fn :weight
                              :cap-fn :effective-cap
                              :rounding (rounding->payoffs rounding-policy)
                              :remainder-policy :unallocated
                              :ordering-policy :canonical-id
                              :on-progress on-progress
                              :progress-atom progress-atom
                              ;; Operational only: omitted from canonical-request.
                              :parallelism parallelism
                              :execution/quiescence-timeout-seconds quiescence-timeout-seconds}
          allocation (case redistribution-policy
                       :unallocated
                       (allocate-pro-rata allocation-request)
                       :redistribute-cap-excess
                       (redistribution/allocate-pro-rata-with-redistribution
                        (assoc allocation-request :ordering-policy :canonical-id)))
          by-id (into {} (map (juxt :id identity) (:allocations allocation)))
          witness (witness-rows available rows rounding-policy redistribution-policy)
          witness-by-id (into {} (map (juxt :row/id identity) (:rows witness)))
          fractional-total (let [remainder-rows (keep :fractional-remainder (vals witness-by-id))
                                 total-ratio (reduce (fn [r {:keys [remainder-numerator remainder-denominator]}]
                                                       (if (zero? remainder-denominator)
                                                         r
                                                         (+ r (/ remainder-numerator remainder-denominator))))
                                                     0
                                                     remainder-rows)]
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
                                               {:type (type total-ratio) :value total-ratio}))))
          result-base
          {:schema-version "pro-rata-allocation-result.v1"
           :mechanism {:id :mechanism/pro-rata-allocation :version req-mechanism-version}
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
          allocation-hash (portable-contract/allocation-result-root result-base)]
      (assoc result-base :allocation/hash allocation-hash
             :allocation/fractional-remainder-total fractional-total))))

(defn allocation-hash-valid?
  "Return true only when the persisted result hash commits to every witness
   field and final allocation row.  Non-canonical diagnostic fields
   (:allocation/fractional-remainder-total) are excluded from recomputation."
  [result]
  (= (:allocation/hash result)
     (portable-contract/allocation-result-root result)))
