(ns resolver-sim.benchmark.reproducibility
  "Reproducibility projection for benchmark final evidence.

  Distinct question from integrity (does this bundle hash to its committed
  root?) and from producer attestation (did an identified producer sign that
  root?). Reproducibility asks: would re-running the same admitted benchmark
  input under the same execution semantics produce the same SEMANTIC content?

  Therefore everything observational or materialization-specific must not
  participate:

    excluded upstream by integrity/hashable-evidence
      :timestamp, :evidence/{hash,signature,public-key-path},
      :benchmark/artifact-index, :repo, :creation/provenance,
      :source/creation, :run/manifest/:manifest/at,
      :results/:scenario/artifacts

    additionally excluded here
      :environment        host OS/JVM versions describe one machine, not the
                          benchmark's semantics
      :run/manifest       execution-context record; contains wall-clock and
                          filesystem-location data. Its semantic content is
                          already represented by :results/:metrics.

  Everything else in the bundle-root projection is treated as semantic:
  manifest metadata, scenario outcomes, metrics, invariant summary,
  certification claims, concept enrichment, execution-closure counts,
  reproduce command, commitment version."
  (:require [resolver-sim.benchmark.integrity :as integrity]
            [resolver-sim.hash.canonical :as hc]))

(def ^:const schema-version "reproducibility-projection.v1")

(def ^:private non-reproducible-keys
  #{:environment :run/manifest})

(def ^:private observational-keys
  "Removed recursively; defensive depth for keys that may appear nested in
  future result shapes. Top-level exclusions above carry the policy."
  #{:timestamp :manifest/at :scenario/artifacts})

(defn- strip-observational
  [x]
  (cond
    (map? x) (into {} (comp (remove #(contains? observational-keys (key %)))
                            (map (fn [[k v]] [k (strip-observational v)])))
                   x)
    (vector? x) (mapv strip-observational x)
    (seq? x) (doall (map strip-observational x))
    :else x))

(defn reproducibility-projection
  "The semantic content of a final-evidence bundle for re-execution identity.
   Starts from the canonical bundle-root projection (inheriting all of its
   documented exclusions), then removes non-reproducible top-level fields and
   any nested observational keys."
  [bundle]
  (-> bundle
      integrity/canonical-projection
      (as-> $ (apply dissoc $ non-reproducible-keys))
      strip-observational))

(defn reproducibility-root
  "Domain-separated hash of the reproducibility projection under the
  :reproducibility intent (BENCHMARK_REPRODUCIBILITY_V1). Two bundles with
  equal roots are claimed to be re-derivable from the same inputs; wall-clock,
  host, VCS-state and signing differences do not participate."
  [bundle]
  (hc/hash-with-intent {:hash/intent :reproducibility}
                       (into (sorted-map) (reproducibility-projection bundle))))
