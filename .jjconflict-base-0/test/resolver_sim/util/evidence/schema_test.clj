(ns resolver-sim.util.evidence.schema-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.util.evidence.schema :as schema]
            [resolver-sim.hash.canonical :as hc]))

;; ── Structural Stability: Evidence Payload Key Schema ───────────────────

(defn- schema-structural-paths
  "Extract flattened structural paths from known-evidence-payload-keys.
   Produces paths like \"yield.target-type\" for each documented key,
   grouped by domain — stable across description changes."
  [payload-keys]
  (sort (map (fn [[k v]] (str (name (:domain v)) "." (name k))) payload-keys)))

(def expected-schema-shape-hash
  "Expected stable hash of evidence payload key schema structural paths.
   Update when intentionally adding or removing documented keys."
  "5e060d10e539d423c5dd345ec128337bd59741957b370ace4e4ae8eaf0343bf6")

(deftest evidence-payload-key-schema-structural-stability
  (let [paths (into [] (schema-structural-paths schema/known-evidence-payload-keys))
        actual-hash (hc/hash-with-intent {:hash/intent :state-diff} {:paths paths})]
    (is (= expected-schema-shape-hash actual-hash)
        (str "Evidence payload key schema shape changed.\n"
             "If intentional, update expected-schema-shape-hash to: " actual-hash))))
