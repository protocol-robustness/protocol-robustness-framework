(ns resolver-sim.research.accounting-basis-comparison
  "Exact, non-authoritative comparison of additive accounting bases. This
   does not compare or combine pro-rata allocations."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.accounting.reductions :as accounting]))

(def ^:const schema "research-accounting-basis-comparison.v1")

(defn- root [body]
  (hash-ref/sha256-ref
   (hc/domain-hash :research-accounting-basis-comparison-v1 body)))

(defn partitioned-values
  "An exact research projection over a complete partition plan. It is not a
   canonical accounting result: that still requires validated partials and
   `accelerated-accounting/reconcile`."
  [request plan]
  (when-not (and (accounting/valid-partition-plan? request plan)
                 (= (:accounting-reduction-contract/root
                     (accounting/build-reduction-contract))
                    (:accounting-reduction-contract/root request)))
    (throw (ex-info "Invalid accounting partition plan" {})))
  (let [values (reduce (fn [totals unit]
                         (merge-with + totals (accounting/partial-values request unit)))
                       (sorted-map) (:partition/work-units plan))]
    (when-not (= values (accounting/serial-values request))
      (throw (ex-info "Partitioned accounting differs from serial accounting" {})))
    values))

(defn compare-bases
  "Compare two complete, policy-compatible accounting bases. The exact
   integer delta is right minus left. Incompatible bases have no numeric
   outcome; the comparison does not establish economic or research authority."
  [left-request left-plan right-request right-plan]
  (let [left-values (partitioned-values left-request left-plan)
        right-values (partitioned-values right-request right-plan)
        dimensions [:accounting/domain :accounting/cutpoint-root
                    :accounting-definition/root :accounting-policy/root
                    :accounting-reduction-contract/root]
        mismatches (vec (filter #(not= (get left-request %)
                                       (get right-request %)) dimensions))
        comparable? (empty? mismatches)
        categories (sort (into (set (keys left-values)) (keys right-values)))
        deltas (when comparable?
                 (into (sorted-map)
                       (map (fn [category]
                              [category (- (get right-values category 0)
                                           (get left-values category 0))])
                            categories)))
        body {:artifact/schema schema
              :comparison/left-request-root (:accounting-request/root left-request)
              :comparison/right-request-root (:accounting-request/root right-request)
              :comparison/left-plan-root (:accounting-partition-plan/root left-plan)
              :comparison/right-plan-root (:accounting-partition-plan/root right-plan)
              :comparison/status (cond (not comparable?) :not-comparable
                                       (every? zero? (vals deltas)) :equal
                                       :else :different)
              :comparison/not-comparable-dimensions mismatches
              :comparison/left-values left-values
              :comparison/right-values right-values
              :comparison/right-minus-left deltas
              :comparison/meaning :derived-accounting-basis-not-allocation}]
    (assoc body :research-accounting-basis-comparison/root (root body))))

(defn valid-comparison?
  [left-request left-plan right-request right-plan comparison]
  (= comparison
     (try (compare-bases left-request left-plan right-request right-plan)
          (catch Exception _ nil))))
