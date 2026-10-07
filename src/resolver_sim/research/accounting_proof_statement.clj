(ns resolver-sim.research.accounting-proof-statement
  "Arithmetic statement foundation, NOT proof generation or admission.
   V1's checked-long subdomain is explicit; existing accounting remains wider.
   Witnesses derive only from existing validated accounting constituents."
  (:require [resolver-sim.research.accelerated-accounting :as accounting]))

(def schema "research-accounting-arithmetic-statement.v1")
(def public-value-order
  [:accounting-request/root :accounting-partition-plan/root
   :accounting-work-unit/root :accounting-reduction-contract/root
   :canonical-accounting-partial/root])

(defn derive-statement [request contract plan partial]
  (let [unit (some #(when (= (:accounting-work-unit/root partial)
                            (:accounting-work-unit/root %)) %)
                   (:partition/work-units plan))]
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
                   unit)
      (throw (ex-info "Invalid accounting arithmetic statement lineage" {})))
    ;; Preserve a truthful comparability boundary rather than narrowing V1.
    (when-not (every? #(instance? Long (:accounting/value %)) (:accounting/sources request))
      (throw (ex-info "Arithmetic proof domain is checked signed 64-bit integers"
                      {:status :not-comparable :reason :unsupported-integer-domain})))
    (try
      (accounting/serial-values request)
      (when-not (accounting/valid-partial-result? request unit partial)
        (throw (ex-info "Invalid accounting arithmetic partial" {})))
      (catch ArithmeticException e
        (throw (ex-info "Accounting arithmetic exceeds checked-long domain"
                        {:status :not-comparable :reason :checked-long-overflow} e))))
    (assoc (select-keys (merge request plan unit contract) (butlast public-value-order))
           :artifact/schema schema
           :proof/arithmetic-domain :checked-i64
           :canonical-accounting-partial/root (accounting/partial-semantic-root partial))))

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
