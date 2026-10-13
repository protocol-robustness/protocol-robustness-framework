(ns resolver-sim.notebook-support.manifest.hash
  "Canonical deterministic hashing for run manifests.

  The canonical hash is computed over a stable subset of the manifest —
  excluding volatile fields that change between runs for the same scenario
  (created-at, mtime_utc, run_id, environment/os).

  This lets you assert that two runs of the same scenario under the same
  commit produced structurally identical results."
  (:require [clojure.walk :as walk]
            [resolver-sim.hash.canonical :as hc]))

;; Fields excluded from canonical hash — volatile across runs for the same scenario
(def ^:private volatile-keys
  #{:created_at :mtime_utc :run_id :generated_at :os :python :java
    :environment :triggered_by :duration_ms})

(defn- strip-volatile
  "Recursively remove volatile keys from nested maps."
  [data]
  (walk/postwalk
   (fn [x]
     (if (map? x)
       (apply dissoc x volatile-keys)
       x))
   data))

(defn hash-with-intent
  "Strip volatiles and hash with the given intent keyword.
   Like canonical-hash but lets the caller specify the intent (e.g. :registry, :provenance)."
  [intent data]
  (->> data strip-volatile (hc/hash-with-intent {:hash/intent intent})))

(defn canonical-hash
  "Compute a stable SHA-256 over the manifest content, excluding volatile fields.
   Uses domain-separated typed binary encoding via resolver-sim.hash.canonical."
  [manifest]
  (->> manifest strip-volatile (hc/hash-with-intent {:hash/intent :manifest})))

(defn hash-matches?
  "Return true if two manifests have the same canonical hash."
  [m1 m2]
  (hc/intent-hash= (canonical-hash m1) (canonical-hash m2)))

(defn suite-hash
  "Hash only the suite block — useful for verifying scenario identity
  across different run timestamps."
  [manifest]
  (->> manifest :suite (hc/hash-with-intent {:hash/intent :manifest})))

(defn artifact-hashes
  "Return a map of artifact-key → sha256 as recorded in the manifest's artifacts block.
  These are the actual file hashes from the registry, not recomputed."
  [registry]
  (->> (get registry :artifacts [])
       (map (fn [entry]
              [(keyword (get entry :id "unknown"))
               (get entry :sha256)]))
       (into {})))
