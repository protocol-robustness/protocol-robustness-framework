(ns resolver-sim.research.accelerated-accounting
  "Canonical decomposition and deterministic reconciliation for additive
   integer accounting bases. This accelerates preparation of a pro-rata input;
   it neither allocates nor moves capital.

   V1 intentionally supports one operation, sum-by-category, whose reduction
   properties are part of the closed contract. Unsupported decompositions fail
   closed rather than acquiring algebraic properties from their data types."
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const reduction-contract-schema "accounting-reduction-contract.v1")
(def ^:const request-schema "accounting-request.v1")
(def ^:const work-unit-schema "accounting-work-unit.v1")
(def ^:const partition-plan-schema "accounting-partition-plan.v1")
(def ^:const partial-schema "accounting-partial-result.v1")
(def ^:const reconciliation-schema "accounting-reconciliation.v1")
(def ^:const supported-operation :accounting.operation/sum-by-category.v1)

(def supported-properties
  {:associative true :commutative true :idempotent false
   :order-sensitive false :partition-independent true
   :requires-global-constraint false})

(defn- root [domain value]
  (hash-ref/sha256-ref (hc/domain-hash domain value)))
(defn- root? [value] (hash-ref/valid-sha256-ref? value))
(defn- integer-map? [m]
  (and (map? m) (every? qualified-keyword? (keys m))
       (every? integer? (vals m))))

(defn reduction-contract-root [contract]
  (root :accounting-reduction-contract-v1
        (dissoc contract :accounting-reduction-contract/root)))

(defn build-reduction-contract []
  (let [body {:artifact/schema reduction-contract-schema
              :accounting/operation supported-operation
              :algebraic/properties supported-properties
              :reduction/rule :integer-sum-by-qualified-category
              :reduction/proof-obligation
              :serial-equivalence-conservation-and-exactly-once-source-coverage}]
    (assoc body :accounting-reduction-contract/root
           (reduction-contract-root body))))

(defn valid-reduction-contract? [contract]
  (and (= reduction-contract-schema (:artifact/schema contract))
       (= supported-operation (:accounting/operation contract))
       (= supported-properties (:algebraic/properties contract))
       (= :integer-sum-by-qualified-category (:reduction/rule contract))
       (= (:accounting-reduction-contract/root contract)
          (try (reduction-contract-root contract) (catch Exception _ nil)))))

(defn- valid-source? [source]
  (and (= #{:source/root :accounting/category :accounting/value
            :accounting/subject-root :claim-to/root}
          (set (keys source)))
       (every? root? [(:source/root source) (:accounting/subject-root source)
                      (:claim-to/root source)])
       (qualified-keyword? (:accounting/category source))
       (integer? (:accounting/value source))))

(defn request-root [request]
  (root :accounting-request-v1 (dissoc request :accounting-request/root)))

(defn build-request
  [{:keys [accounting-domain cutpoint-root accounting-definition-root
           accounting-policy-root reduction-contract sources]}]
  (let [sources (vec (sort-by :source/root sources))]
    (when-not (and (qualified-keyword? accounting-domain)
                   (every? root? [cutpoint-root accounting-definition-root
                                  accounting-policy-root])
                   (valid-reduction-contract? reduction-contract)
                   (seq sources) (every? valid-source? sources)
                   (= (count sources) (count (set (map :source/root sources)))))
      (throw (ex-info "Invalid accounting request" {})))
    (let [body {:artifact/schema request-schema
                :accounting/domain accounting-domain
                :accounting/cutpoint-root cutpoint-root
                :accounting-definition/root accounting-definition-root
                :accounting-policy/root accounting-policy-root
                :accounting-reduction-contract/root
                (:accounting-reduction-contract/root reduction-contract)
                :accounting/sources sources}]
      (assoc body :accounting-request/root (request-root body)))))

(defn valid-request?
  "Validate the immutable source basis without treating a symbolic accounting
   version as sufficient provenance."
  [request]
  (and (= request-schema (:artifact/schema request))
       (qualified-keyword? (:accounting/domain request))
       (every? root? [(:accounting/cutpoint-root request)
                      (:accounting-definition/root request)
                      (:accounting-policy/root request)
                      (:accounting-reduction-contract/root request)])
       (vector? (:accounting/sources request))
       (seq (:accounting/sources request))
       (= (:accounting/sources request)
          (vec (sort-by :source/root (:accounting/sources request))))
       (every? valid-source? (:accounting/sources request))
       (= (count (:accounting/sources request))
          (count (set (map :source/root (:accounting/sources request)))))
       (= (:accounting-request/root request)
          (try (request-root request) (catch Exception _ nil)))))

(defn serial-values
  "Authoritative serial fold for the one supported V1 operation."
  [request]
  (reduce (fn [totals source]
            (update totals (:accounting/category source) (fnil + 0)
                    (:accounting/value source)))
          (sorted-map) (:accounting/sources request)))

(defn work-unit-root [unit]
  (root :accounting-work-unit-v1 (dissoc unit :accounting-work-unit/root)))

(defn- build-work-unit
  [request partition-index source-roots requirements]
  (let [body {:artifact/schema work-unit-schema
              :work-unit/class :accounting-partition
              :accounting-request/root (:accounting-request/root request)
              :partition/index partition-index
              :partition/source-roots (vec (sort source-roots))
              :work-unit/dependency-set-root (:dependency-set-root requirements)
              :work-unit/allowed-implementation-roots
              (vec (sort (:allowed-implementation-roots requirements)))
              :work-unit/required-capabilities
              (vec (sort (:required-capabilities requirements)))
              :work-unit/privacy-requirement (:privacy-requirement requirements)
              :work-unit/execution-environment-root (:execution-environment-root requirements)}]
    (assoc body :accounting-work-unit/root (work-unit-root body))))

(defn partition-plan-root [plan]
  (root :accounting-partition-plan-v1
        (dissoc plan :accounting-partition-plan/root)))

(defn build-partition-plan
  "Build an explicit partition plan. `partitions` is a collection of source-root
   collections. Exact coverage and uniqueness are checked before rooting."
  [request partitions requirements]
  (let [expected (set (map :source/root (:accounting/sources request)))
        partitions (mapv #(vec (sort %)) partitions)
        flattened (vec (mapcat identity partitions))]
    (when-not (and (seq partitions) (every? seq partitions)
                   (= expected (set flattened))
                   (= (count flattened) (count (set flattened)))
                   (root? (:dependency-set-root requirements))
                   (root? (:execution-environment-root requirements))
                   (seq (:allowed-implementation-roots requirements))
                   (every? root? (:allowed-implementation-roots requirements))
                   (every? keyword? (:required-capabilities requirements))
                   (contains? #{:public :runner-readable-private
                                :sealed-to-execution-environment}
                              (:privacy-requirement requirements)))
      (throw (ex-info "Invalid accounting partition plan"
                      {:expected expected :actual (set flattened)
                       :duplicate-source-roots
                       (->> flattened frequencies (keep (fn [[r n]] (when (> n 1) r))) vec)})))
    (let [units (mapv #(build-work-unit request %1 %2 requirements)
                      (range) partitions)
          body {:artifact/schema partition-plan-schema
                :accounting-request/root (:accounting-request/root request)
                :accounting-reduction-contract/root
                (:accounting-reduction-contract/root request)
                :partition/work-units units
                :partition/coverage :complete-exactly-once}]
      (assoc body :accounting-partition-plan/root (partition-plan-root body)))))

(defn valid-partition-plan?
  [request plan]
  (and (valid-request? request)
       (= partition-plan-schema (:artifact/schema plan))
       (= (:accounting-request/root request) (:accounting-request/root plan))
       (= (:accounting-reduction-contract/root request)
          (:accounting-reduction-contract/root plan))
       (vector? (:partition/work-units plan))
       (seq (:partition/work-units plan))
       (= (:accounting-partition-plan/root plan)
          (try (partition-plan-root plan) (catch Exception _ nil)))
       (let [units (:partition/work-units plan)
             sources (mapcat :partition/source-roots units)]
         (and (every? #(and (= work-unit-schema (:artifact/schema %))
                            (= (:accounting-request/root request)
                               (:accounting-request/root %))
                            (= (:accounting-work-unit/root %)
                               (try (work-unit-root %) (catch Exception _ nil)))
                            (integer? (:partition/index %))
                            (not (neg? (:partition/index %)))) units)
              (= (count units) (count (set (map :partition/index units))))
              (= (set (map :source/root (:accounting/sources request)))
                 (set sources))
              (= (count sources) (count (set sources)))))))

(defn partial-root [partial]
  (root :accounting-partial-result-v1
        (dissoc partial :accounting-partial-result/root)))

(defn partial-semantic-root
  "The canonical accounting consequence of one partition, excluding execution
   provenance. Independent runners may produce the same semantic partial while
   retaining different `accounting-partial-result.v1` roots."
  [partial]
  (root :canonical-accounting-partial-v1
        {:artifact/schema "canonical-accounting-partial.v1"
         :accounting-request/root (:accounting-request/root partial)
         :accounting-work-unit/root (:accounting-work-unit/root partial)
         :partition/source-roots (:partition/source-roots partial)
         :accounting/values (:accounting/values partial)}))

(defn partial-values
  "Canonical values for one declared partition. This is intentionally the
   closed V1 sum-by-category operation, not an inferred generic reduction."
  [request work-unit]
  (let [source-map (into {} (map (juxt :source/root identity)
                                 (:accounting/sources request)))]
    (reduce (fn [m source-root]
              (let [{:accounting/keys [category value]} (get source-map source-root)]
                (update m category (fnil + 0) value)))
            (sorted-map) (:partition/source-roots work-unit))))

(defn valid-partial-result?
  [request work-unit partial]
  (and (= partial-schema (:artifact/schema partial))
       (= (:accounting-request/root request) (:accounting-request/root work-unit)
          (:accounting-request/root partial))
       (= (:accounting-work-unit/root work-unit)
          (:accounting-work-unit/root partial))
       (= (:partition/index work-unit) (:partition/index partial))
       (= (:partition/source-roots work-unit) (:partition/source-roots partial))
       ;; The result must preserve the execution constraints declared by its
       ;; exact partition. Merely supplying some valid dependency/implementation
       ;; roots would otherwise admit stale-source or unauthorized code output.
       (contains? (set (:work-unit/allowed-implementation-roots work-unit))
                  (:research-implementation-identity/root partial))
       (= (:work-unit/dependency-set-root work-unit)
          (:resolved-research-dependency-set/root partial))
       (= (:work-unit/execution-environment-root work-unit)
          (:research-execution-environment/root partial))
       (= (partial-values request work-unit) (:accounting/values partial))
       (integer-map? (:accounting/values partial))
       (every? root? [(:research-run-assurance-evidence/root partial)
                      (:research-implementation-identity/root partial)
                      (:runner/reference partial)
                      (:resolved-research-dependency-set/root partial)
                      (:research-execution-environment/root partial)])
       (= (:accounting-partial-result/root partial)
          (try (partial-root partial) (catch Exception _ nil)))))

(defn build-partial-result
  [{:keys [request work-unit values run-assurance-root implementation-root
           runner-reference resolved-dependency-set-root execution-environment-root]}]
  (let [expected-values (partial-values request work-unit)]
    (when-not (and (= (:accounting-request/root request)
                      (:accounting-request/root work-unit))
                   (= values expected-values) (integer-map? values)
                   (every? root? [run-assurance-root implementation-root
                                  runner-reference resolved-dependency-set-root
                                  execution-environment-root]))
      (throw (ex-info "Invalid accounting partial result"
                      {:expected-values expected-values :actual-values values})))
    (let [body {:artifact/schema partial-schema
                :accounting-request/root (:accounting-request/root request)
                :accounting-work-unit/root (:accounting-work-unit/root work-unit)
                :partition/index (:partition/index work-unit)
                :partition/source-roots (:partition/source-roots work-unit)
                :accounting/values (into (sorted-map) values)
                :research-run-assurance-evidence/root run-assurance-root
                :research-implementation-identity/root implementation-root
                :runner/reference runner-reference
                :resolved-research-dependency-set/root resolved-dependency-set-root
                :research-execution-environment/root execution-environment-root}]
      (assoc body :accounting-partial-result/root (partial-root body)))))

(defn ingest-partial
  "Ingest by work-unit identity. Repeating the identical rooted partial is an
   idempotent no-op; a different result for that unit is a hard conflict."
  [ingestion partial]
  (let [unit-root (:accounting-work-unit/root partial)
        existing (get ingestion unit-root)]
    (cond
      (nil? existing) (assoc ingestion unit-root partial)
      (= (:accounting-partial-result/root existing)
         (:accounting-partial-result/root partial)) ingestion
      :else (throw (ex-info "Conflicting accounting partial result"
                            {:conflict/type :accounting-partial-conflict
                             :accounting-work-unit/root unit-root
                             :existing/root (:accounting-partial-result/root existing)
                             :incoming/root (:accounting-partial-result/root partial)})))))

(defn semantic-partial-equivalent?
  "True where two valid forensic partials represent the same rooted accounting
   consequence. This does not make them the same execution attempt."
  [left right]
  (= (partial-semantic-root left) (partial-semantic-root right)))

(defn accounting-completeness
  "Derived, non-mutating completeness view for a frozen request and plan.
   It is deliberately independent of corroboration/proof thresholds."
  [request plan ingestion]
  (when-not (and (= (:accounting-request/root request)
                    (:accounting-request/root plan))
                 (= (:accounting-partition-plan/root plan)
                    (try (partition-plan-root plan) (catch Exception _ nil))))
    (throw (ex-info "Invalid accounting completeness request or partition plan" {})))
  (let [units (:partition/work-units plan)
        unit-by-root (into {} (map (juxt :accounting-work-unit/root identity) units))
        required (set (keys unit-by-root))
        supplied (set (keys ingestion))
        missing (set/difference required supplied)
        extra (set/difference supplied required)
        invalid (->> ingestion
                     (keep (fn [[unit-root partial]]
                             (let [unit (unit-by-root unit-root)]
                               (when-not (and unit (valid-partial-result? request unit partial))
                                 unit-root)))) set)]
    {:accounting-request/root (:accounting-request/root request)
     :accounting-partition-plan/root (:accounting-partition-plan/root plan)
     :accounting/required-work-unit-roots (vec (sort required))
     :accounting/complete-work-unit-roots
     (vec (sort (set/difference (set (keys ingestion)) invalid extra)))
     :accounting/missing-work-unit-roots (vec (sort missing))
     :accounting/unexpected-work-unit-roots (vec (sort extra))
     :accounting/invalid-work-unit-roots (vec (sort invalid))
     :accounting/completeness
     (if (and (empty? missing) (empty? extra) (empty? invalid)) :complete :incomplete)}))

(defn reconciliation-root [artifact]
  (root :accounting-reconciliation-v1
        (dissoc artifact :accounting-reconciliation/root)))

(defn reconcile
  [request contract plan ingestion]
  (when-not (valid-reduction-contract? contract)
    (throw (ex-info "Unsupported accounting reduction contract" {})))
  (when-not (and (= (:accounting-request/root request)
                    (:accounting-request/root plan))
                 (= (:accounting-reduction-contract/root contract)
                    (:accounting-reduction-contract/root request)
                    (:accounting-reduction-contract/root plan))
                 (= (:accounting-partition-plan/root plan)
                    (try (partition-plan-root plan) (catch Exception _ nil))))
    (throw (ex-info "Accounting request, contract, and partition plan mismatch" {})))
  (let [units (:partition/work-units plan)
        required-unit-roots (set (map :accounting-work-unit/root units))
        supplied-unit-roots (set (keys ingestion))
        missing (set/difference required-unit-roots supplied-unit-roots)
        extra (set/difference supplied-unit-roots required-unit-roots)]
    (when (or (seq missing) (seq extra))
      (throw (ex-info "Accounting reconciliation is incomplete"
                      {:missing-work-unit-roots (vec (sort missing))
                       :unexpected-work-unit-roots (vec (sort extra))})))
    (let [unit-by-root (into {} (map (juxt :accounting-work-unit/root identity) units))
          partials (mapv ingestion (sort required-unit-roots))
          invalid-partials (keep (fn [partial]
                                   (let [unit (unit-by-root
                                               (:accounting-work-unit/root partial))]
                                     (when-not (and unit
                                                    (valid-partial-result?
                                                     request unit partial))
                                       (:accounting-partial-result/root partial))))
                                 partials)
          source-roots (vec (mapcat :partition/source-roots partials))
          expected-source-roots (set (map :source/root (:accounting/sources request)))
          duplicates (->> source-roots frequencies
                          (keep (fn [[source-root n]] (when (> n 1) source-root))) set)
          actual-source-roots (set source-roots)
          omitted (set/difference expected-source-roots actual-source-roots)
          unexpected (set/difference actual-source-roots expected-source-roots)]
      (when (seq invalid-partials)
        (throw (ex-info "Invalid accounting partial provenance or value"
                        {:invalid-partial-roots (vec invalid-partials)})))
      (when (or (seq duplicates) (seq omitted) (seq unexpected))
        (throw (ex-info "Accounting source coverage violation"
                        {:duplicate-source-roots (vec (sort duplicates))
                         :omitted-source-roots (vec (sort omitted))
                         :unexpected-source-roots (vec (sort unexpected))})))
      (let [values (reduce (fn [result partial]
                             (merge-with + result (:accounting/values partial)))
                           (sorted-map) partials)
            serial (serial-values request)]
        (when-not (= serial values)
          (throw (ex-info "Partitioned accounting differs from serial accounting"
                          {:serial serial :partitioned values})))
        (let [semantic-result-root
              (root :canonical-accounting-result-v1
                    {:artifact/schema "canonical-accounting-result.v1"
                     :accounting-request/root (:accounting-request/root request)
                     :accounting/values values})
              body {:artifact/schema reconciliation-schema
                    :accounting-request/root (:accounting-request/root request)
                    :accounting-partition-plan/root
                    (:accounting-partition-plan/root plan)
                    :accounting-reduction-contract/root
                    (:accounting-reduction-contract/root contract)
                    :accounting-partial-result/roots
                    (vec (sort (map :accounting-partial-result/root partials)))
                    :accounting/source-roots (vec (sort source-roots))
                    :accounting/values values
                    :canonical-accounting-result/root semantic-result-root
                    :reconciliation/invariants
                    {:serial-equivalent true :conserved true
                     :partition-complete true :no-duplicate-contribution true
                     :no-omitted-contribution true :deterministic true
                     :forensic-provenance-complete true}
                    :result/meaning :accounting-basis-not-allocation-or-fund-movement}]
          (assoc body :accounting-reconciliation/root
                 (reconciliation-root body)))))))
