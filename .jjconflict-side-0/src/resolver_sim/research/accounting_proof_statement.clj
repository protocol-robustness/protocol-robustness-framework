(ns resolver-sim.research.accounting-proof-statement
  "Arithmetic statement foundation, NOT proof generation or admission.
   V1's checked-long subdomain is explicit; existing accounting remains wider.
   Witnesses derive only from existing validated accounting constituents."
  (:require [resolver-sim.accounting.reductions :as accounting]))

(def schema "research-accounting-arithmetic-statement.v1")
(def public-value-order
  [:accounting-request/root :accounting-partition-plan/root
   :accounting-work-unit/root :accounting-reduction-contract/root
   :canonical-accounting-partial/root])

(defn derive-commitment-projection
  "Derive the five committed roots from the rooted request/plan/partial
   envelope without asserting that its values are the correct arithmetic sum.
   The SP1 guest proves that final obligation."
  [request contract plan partial]
  (let [unit (some #(when (= (:accounting-work-unit/root partial)
                             (:accounting-work-unit/root %)) %)
                   (:partition/work-units plan))]
    (when-not (every? #(instance? Long (:accounting/value %))
                      (:accounting/sources request))
      (throw (ex-info "Arithmetic proof domain is checked signed 64-bit integers"
                      {:status :not-comparable :reason :unsupported-integer-domain})))
    (when-not (and (accounting/valid-reduction-contract? contract)
                   (= contract (accounting/build-reduction-contract))
                   (accounting/valid-partition-plan? request plan)
                   (= :complete-exactly-once (:partition/coverage plan))
                   (every? (fn [unit]
                             (let [sources (:partition/source-roots unit)]
                               (and (vector? sources) (seq sources)
                                    (= sources (vec (sort (distinct sources)))))))
                           (:partition/work-units plan))
                   (= (:accounting-reduction-contract/root contract)
                      (:accounting-reduction-contract/root request))
                   unit
                   (= "accounting-partial-result.v1" (:artifact/schema partial))
                   (= (:accounting-request/root request) (:accounting-request/root partial))
                   (= (:accounting-work-unit/root unit) (:accounting-work-unit/root partial))
                   (= (:partition/index unit) (:partition/index partial))
                   (= (:partition/source-roots unit) (:partition/source-roots partial))
                   (map? (:accounting/values partial))
                   (every? #(instance? Long %) (vals (:accounting/values partial)))
                   (contains? (set (:work-unit/allowed-implementation-roots unit))
                              (:research-implementation-identity/root partial))
                   (= (:work-unit/dependency-set-root unit)
                      (:resolved-research-dependency-set/root partial))
                   (= (:work-unit/execution-environment-root unit)
                      (:research-execution-environment/root partial))
                   (every? #(and (string? %) (re-matches #"^sha256:[0-9a-f]{64}$" %))
                           [(:research-run-assurance-evidence/root partial)
                            (:research-implementation-identity/root partial)
                            (:runner/reference partial)
                            (:resolved-research-dependency-set/root partial)
                            (:research-execution-environment/root partial)])
                   (= (:accounting-partial-result/root partial)
                      (accounting/partial-root partial)))
      (throw (ex-info "Invalid accounting arithmetic commitment lineage" {})))
    (assoc (select-keys (merge request plan unit contract) (butlast public-value-order))
           :artifact/schema schema
           :proof/arithmetic-domain :checked-i64
           :canonical-accounting-partial/root (accounting/partial-semantic-root partial))))

(defn derive-statement [request contract plan partial]
  (let [projection (derive-commitment-projection request contract plan partial)
        unit (some #(when (= (:accounting-work-unit/root partial)
                             (:accounting-work-unit/root %)) %)
                   (:partition/work-units plan))]
    ;; Preserve a truthful comparability boundary rather than narrowing V1.
    (try
      (accounting/serial-values request)
      (when-not (accounting/valid-partial-result? request unit partial)
        (throw (ex-info "Invalid accounting arithmetic partial" {})))
      (catch ArithmeticException e
        (throw (ex-info "Accounting arithmetic exceeds checked-long domain"
                        {:status :not-comparable :reason :checked-long-overflow} e))))
    projection))

(defn public-values-hex [statement]
  (apply str (map #(subs (get statement %) 7) public-value-order)))

(defn- wire [x]
  (cond
    (keyword? x) {"$kw" (subs (str x) 1)}
    (map? x) {"$map" (mapv (fn [[k v]] [(wire k) (wire v)]) x)}
    (vector? x) (mapv wire x)
    (or (string? x) (boolean? x) (nil? x) (instance? Long x)) x
    :else (throw (ex-info "Unsupported arithmetic witness value" {:type (type x)}))))

(defn proof-input
  "No independent values/root tuple supplied. Not a generated proof receipt."
  [request contract plan partial]
  (derive-statement request contract plan partial)
  {"schema" schema
   "request" (wire request) "contract" (wire contract) "plan" (wire plan)
   "partial" (wire {:artifact/schema "canonical-accounting-partial.v1"
                    :accounting-request/root (:accounting-request/root partial)
                    :accounting-work-unit/root (:accounting-work-unit/root partial)
                    :partition/source-roots (:partition/source-roots partial)
                    :accounting/values (:accounting/values partial)
                    :canonical-accounting-partial/root
                    (accounting/partial-semantic-root partial)})})

(defn proof-input-for-verification
  "Build the guest witness from the coordinator's authoritative rooted work and
   claimed execution envelope. Arithmetic is intentionally not evaluated in
   Clojure here; successful guest verification is mandatory before admission."
  [request contract plan partial]
  (derive-commitment-projection request contract plan partial)
  {"schema" schema
   "request" (wire request) "contract" (wire contract) "plan" (wire plan)
   "partial" (wire {:artifact/schema "canonical-accounting-partial.v1"
                    :accounting-request/root (:accounting-request/root partial)
                    :accounting-work-unit/root (:accounting-work-unit/root partial)
                    :partition/source-roots (:partition/source-roots partial)
                    :accounting/values (:accounting/values partial)
                    :canonical-accounting-partial/root
                    (accounting/partial-semantic-root partial)})})
