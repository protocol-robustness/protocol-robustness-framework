(ns dev.pro-rata
  (:require [clojure.pprint :as pp]
            [clojure.data]
            [resolver-sim.claims.engine :as engine]
            [resolver-sim.yield.pro-rata-claims :as pro-rata-claims]))

;; ── Old / Direct Path ─────────────────────────────────────────────────────
;; These are the original allocation functions. Compare their output
;; with the projection-based equivalents below.

(defn explain-generic-allocation
  "Direct allocation via allocate-pro-rata (no projection, no evidence).
   Input shape: {:amount N :items [{:id kw :weight N :cap N}] ...}"
  [input]
  (let [f (requiring-resolve
           'resolver-sim.economics.payoffs/allocate-pro-rata)
        result (f input)]
    (tap> {:type :pro-rata/generic-allocation
           :input input
           :result result})
    result))

(defn explain-sew-slash-allocation
  "Direct Sew slash allocation (the historical path).
   Input shape: {:slash-amount N :liable-parties [{:id kw :slashable-stake N :available-slashable N}]}"
  [input]
  (let [f (requiring-resolve
           'resolver-sim.protocols.sew.economics/calculate-sew-slash-allocation)
        result (f input)]
    (tap> {:type :pro-rata/sew-slash-allocation
           :input input
           :result result})
    result))

;; ── New / Projection Path ─────────────────────────────────────────────────
;; These follow PROJECTION_PRORATA_SPEC_V1:
;;   world → registered intent → projection definition → projection artifact
;;   → allocation → claims → evidence node

(defn explain-projection-artifact
  "Build a passive projection artifact from a Sew slash allocation input.
   The artifact carries projection-hash, projection-definition-hash, claims,
   summary, and source — all canonical-safe.
   Input shape matches explain-sew-slash-allocation."
  [sew-slash-input]
  (let [f (requiring-resolve
           'resolver-sim.protocols.sew.economics/build-sew-slash-projection-artifact)
        artifact (f sew-slash-input)]
    (tap> {:type :pro-rata/projection-artifact
           :artifact artifact})
    artifact))

(defn explain-projection-vs-direct
  "Run both direct allocation and projection-based allocation on the same
   Sew slash input. Returns {:direct ..., :projection ..., :equivalent? ...}.
   Equivalent means :total-allocated, :total-unmet, :remainder match and
   every allocation pair id/paid/unmet matches."
  [sew-slash-input]
  (let [sew-alloc (requiring-resolve
                   'resolver-sim.protocols.sew.economics/calculate-sew-slash-allocation)
        proj-artifact (requiring-resolve
                       'resolver-sim.protocols.sew.economics/build-sew-slash-projection-artifact)
        from-proj (requiring-resolve
                   'resolver-sim.protocols.sew.economics/calculate-sew-slash-allocation-from-projection)
        direct (sew-alloc sew-slash-input)
        artifact (proj-artifact sew-slash-input)
        projection (from-proj artifact)
        match? (= direct projection)]
    (tap> {:type :pro-rata/projection-vs-direct
           :direct direct
           :projection projection
           :equivalent? match?
           :diff (when-not match?
                   {:direct-only (clojure.data/diff direct projection)})})
    (pp/pprint {:projection-hash (:projection-hash artifact)
                :equivalent? match?
                :artifact-summary (:summary artifact)
                :direct {:total-allocated (:recovered-total direct)
                         :total-unmet (:unmet-total direct)
                         :allocations (count (:allocations direct))}
                :projection {:total-allocated (:recovered-total projection)
                             :total-unmet (:unmet-total projection)
                             :allocations (count (:allocations projection))}})
    {:direct direct :projection projection :equivalent? match?}))

(defn explain-claims
  "Evaluate all 7 pro-rata claims on a Sew slash input.
   Uses claims.engine/evaluate-claims with evidence-node references.
   Returns {claim-id {:holds? bool :violations [...]}}.
   See explain-sew-slash-allocation for input shape."
  [sew-slash-input]
  (let [alloc (requiring-resolve 'resolver-sim.protocols.sew.economics/calculate-sew-slash-allocation)
        proj (requiring-resolve 'resolver-sim.protocols.sew.economics/build-sew-slash-projection-artifact)
        from-proj (requiring-resolve 'resolver-sim.protocols.sew.economics/calculate-sew-slash-allocation-from-projection)
        build-node (requiring-resolve 'resolver-sim.protocols.sew.evidence.slashing/build-claim-evaluation-node)
        direct-result (alloc sew-slash-input)
        projection-artifact (proj sew-slash-input)
        projection-artifact-again (proj sew-slash-input)
        projection-result (from-proj projection-artifact)
        node (build-node sew-slash-input projection-artifact direct-result
              projection-artifact-again projection-result)
        requests (mapv (fn [claim-id]
                         {:claim-id claim-id
                          :evidence-references [(:node-hash node)]})
                       (pro-rata-claims/registered-claim-ids))
        {:keys [claim-results]}
        (engine/evaluate-claims
         requests [node]
         {:evaluator-resolver pro-rata-claims/evaluator-resolver})
        results (into {} (map (juxt :claim-id identity) claim-results))]
    (tap> {:type :pro-rata/claims :results results})
    (doseq [[k v] (sort results)]
      (println (name k) (if (:holds? v) "✓" "✗")
               (when (:violations v) (pr-str (:violations v)))))
    results))

(defn explain-evidence
  "Build the full evidence node for a Sew slash allocation (the
   build-prorata-slash-evidence aggregation). Returns the evidence record
   with :evidence/hash and nested :projection / :pro-rata / :allocation.
   
   world — full Sew world state passed through to agg/build-evidence-aggregate.
   The remaining args match build-prorata-slash-evidence."
  [& args]
  (let [f (requiring-resolve 'resolver-sim.protocols.sew.evidence.slashing/build-prorata-slash-evidence)
        {:keys [evidence]} (apply f args)]
    (tap> {:type :pro-rata/evidence :result evidence})
    evidence))

(defn explain-evidence-from-input
  "One-shot: given a Sew slash input and a world, build projection artifact,
   evaluate claims, build evidence node, and print the hash chain.
   
   Input:
     sew-slash-input — as in explain-sew-slash-allocation
     world           — Sew world state map
     ctx             — optional map of {:slash-id :workflow-id :epoch :trigger :transition-dependencies :attribution}"
  [sew-slash-input world & [ctx]]
  (let [build-evid (requiring-resolve
                    'resolver-sim.protocols.sew.evidence.slashing/build-prorata-slash-evidence)
        {:keys [slash-id workflow-id epoch trigger
                transition-dependencies attribution]}
        (or ctx {})
        allocation-input sew-slash-input
        allocation (explain-sew-slash-allocation sew-slash-input)
        {:keys [evidence]} (build-evid
                            {:world world
                             :slash-id (or slash-id :dev-test)
                             :workflow-id (or workflow-id 0)
                             :epoch (or epoch 0)
                             :trigger (or trigger :dev)
                             :allocation-input allocation-input
                             :allocation-result allocation
                             :transition-dependencies (or transition-dependencies [])
                             :attribution (or attribution {})})]
    (println "\n── Evidence hash chain ──")
    (println ":evidence/hash" (:evidence/hash evidence))
    (println ":projection-hash" (get-in evidence [:result :projection :projection-hash]))
    (println ":projection-definition-hash" (get-in evidence [:result :projection :projection-definition-hash]))
    (println ":allocation-hash" (get-in evidence [:result :pro-rata :allocation-hash]))
    (println ":claim-count" (get-in evidence [:result :pro-rata :summary :claim-count]))
    (println ":holds?" (get-in evidence [:result :pro-rata :summary :holds?]))
    (println ":subject" (:subject evidence))
    (tap> {:type :pro-rata/evidence-from-input
           :evidence evidence
           :allocation allocation})
    evidence))

;; ── Quick Demos ────────────────────────────────────────────────────────────

(def sample-sew-input
  {:slash-amount 100
   :liable-parties [{:id :resolver-a :slashable-stake 100 :available-slashable 50}
                    {:id :resolver-b :slashable-stake 100 :available-slashable 100}]})

(def sample-generic-input
  {:amount 100
   :items [{:id :a :weight 100 :cap 50}
           {:id :b :weight 100 :cap 100}]
   :rounding :floor-with-largest-remainder
   :remainder-policy :unallocated
   :ordering-policy :input-order})

(defn demo-generic
  []
  (println "=== Generic allocation ===")
  (pp/pprint (explain-generic-allocation sample-generic-input)))

(defn demo-sew
  []
  (println "=== Sew slash allocation ===")
  (pp/pprint (explain-sew-slash-allocation sample-sew-input)))

(defn demo-projection
  []
  (println "=== Projection artifact ===")
  (let [a (explain-projection-artifact sample-sew-input)]
    (pp/pprint a)))

(defn demo-vs
  []
  (println "=== Direct vs Projection ===")
  (explain-projection-vs-direct sample-sew-input))

(defn demo-claims
  []
  (println "=== Claims ===")
  (explain-claims sample-sew-input))

(defn demo-all
  []
  (demo-generic)
  (println)
  (demo-sew)
  (println)
  (demo-projection)
  (println)
  (demo-vs)
  (println)
  (demo-claims))
