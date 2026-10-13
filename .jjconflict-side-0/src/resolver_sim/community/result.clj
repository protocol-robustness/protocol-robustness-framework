(ns resolver-sim.community.result
  "Stable result projection and comparison for community task execution.
   Pure functions only — no side effects."
  (:require [resolver-sim.hash.canonical :as hc]))

(def ^:const domain-tag "COMMUNITY_STABLE_RESULT_V0")
(def ^:const schema-version "stable-result-projection.v0")
(def ^:const supported-comparison-policies #{:stable-projection-v0})

(defn- normalize-for-hash [x]
  (cond
    (nil? x) nil
    (boolean? x) x
    (integer? x) x
    (string? x) x
    (keyword? x) x
    (instance? java.time.Instant x) (str x)
    (vector? x) (mapv normalize-for-hash x)
    (map? x) (persistent!
              (reduce-kv (fn [m k v] (assoc! m (normalize-for-hash k) (normalize-for-hash v)))
                         (transient {}) x))
    (set? x) (vec (sort (map normalize-for-hash x)))
    (sequential? x) (mapv normalize-for-hash x)
    :else (str x)))

(defn- project-scenario-result
  "Strip volatile fields from one scenario result entry.
   Preserves only deterministic semantic fields."
  [r]
  (cond-> {}
    (:scenario/id r) (assoc :scenario/id (:scenario/id r))
    (:outcome r) (assoc :outcome (:outcome r))
    (:halt-reason r) (assoc :halt-reason (:halt-reason r))
    (:benchmark/run-index r) (assoc :benchmark/run-index (:benchmark/run-index r))
    (:benchmark/run-count r) (assoc :benchmark/run-count (:benchmark/run-count r))
    (:invariant-results r) (assoc :invariant-results (:invariant-results r))
    (:scenario/evidence-root r) (assoc :scenario/evidence-root (:scenario/evidence-root r))))

(defn project-stable-result
  "Project an evidence bundle into a stable, comparable form.
   
   Strips volatile fields that vary between environments or runs:
   - :repo — git metadata, dirty state
   - :environment — OS name, OS version, Java version
   - :results[*]:file, :simulator/scenario-path — absolute filesystem paths
   - :run/manifest — absolute paths in run description
   - :reproduce — hardcoded command string
   - :concept/section, :concept/coverage — enrichment artifacts
   - :evidence/hash, :benchmark-certification — self-reference hashes
   
   Preserves deterministic semantic fields:
   - :benchmark — the benchmark definition (deterministic)
   - :results[*]:scenario/id, :outcome, :halt-reason, :invariant-results
   - :results[*]:scenario/evidence-root — content hash of replay result
   - :metrics — execution counts (deterministic)
   - :claim-results — claim evaluation outcomes
   - :invariant-summary — invariant check aggregate
   
   Returns {:stable/hash <hex> :stable/projection {<projected-fields>}
            :schema-version <str>}"
  [evidence]
  (let [projected {:schema-version schema-version
                   :projected-at (str (java.time.Instant/now))
                   :comparison-policy :stable-projection-v0
                   :benchmark (:benchmark evidence)
                   :results (mapv project-scenario-result (:results evidence))
                   :metrics (:metrics evidence)
                   :claim-results (:claim-results evidence)
                   :invariant-summary (:invariant-summary evidence)}
        hashable (dissoc projected :projected-at)
        hash (hc/domain-hash domain-tag (normalize-for-hash hashable))]
    {:stable/hash hash
     :stable/projection (assoc projected :stable/hash hash)}))

(defn stable-result-hash
  "Convenience: return only the stable hash for an evidence bundle.
   Idempotent: if the input already has a :stable/hash, returns it."
  [evidence]
  (or (:stable/hash evidence)
      (:stable/hash (project-stable-result evidence))))

(defn compare-stable-results
  "Compare two evidence bundles using stable projections.
   
   Both bundles are projected independently using project-stable-result.
   The comparison is derived from the stable hashes, not the raw bundles.
   
   Returns {:comparison-status <kw>
            :original-projection <hex>
            :reproduction-projection <hex>
            :comparison-policy :stable-projection-v0
            :matched? <bool>}"
  [original-bundle reproduction-bundle]
  (let [original-proj (project-stable-result original-bundle)
        repro-proj (project-stable-result reproduction-bundle)
        original-hash (:stable/hash original-proj)
        repro-hash (:stable/hash repro-proj)
        matched? (= original-hash repro-hash)]
    {:comparison-status (if matched? :matched :mismatched)
     :original-projection original-hash
     :reproduction-projection repro-hash
     :comparison-policy :stable-projection-v0
     :matched? matched?}))
