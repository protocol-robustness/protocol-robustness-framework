(ns resolver-sim.forensic.provenance
  "Environment-variable bridge for source provenance attributes.

   The forensic runner sets PRF_* env vars before invoking the Clojure
   pipeline.  This namespace reads them at call time and returns structured
   maps that can be merged into execution node metadata, bundle roots, or
   attribution context.

   All values are strings or nil.  Consumers decide default/fallback
   semantics."
  (:require [clojure.string :as str]))

(def env-keys
  "Keyword → env-var mapping for forensic provenance attributes."
  {:source/hash               "PRF_SOURCE_TREE_HASH"
   :source/hash-algorithm     "PRF_SOURCE_TREE_HASH_ALGORITHM"
   :source/hash-roots         "PRF_SOURCE_TREE_HASH_ROOTS"
   :source/tree-hash          "PRF_SOURCE_TREE_HASH"
   :source/tree-hash-algorithm "PRF_SOURCE_TREE_HASH_ALGORITHM"
   :source/commit              "PRF_SOURCE_COMMIT"
   :source/dirty?              "PRF_SOURCE_DIRTY"
   :runner/orchestration-id    "PRF_ORCHESTRATION_RUNNER_ID"
   :bundle/id                  "PRF_BUNDLE_ID"
   :tsa/url                    "PRF_TSA_URL"})

(defn provenance-map
  "Read all PRF_* env vars and return a map keyword → value-or-nil."
  []
  (into {}
        (map (fn [[k v]]
               [k (let [raw (System/getenv v)]
                    (if (= k :source/hash-roots)
                      (when raw
                        (->> (.split raw ",")
                             (map str/trim)
                             (remove str/blank?)
                             vec))
                      raw))])
             env-keys)))

(defn source-provenance
  "Return only source-related provenance attributes.
   Consumers merge this into execution node :inputs or attribution context."
  []
  (select-keys (provenance-map)
               [:source/hash :source/hash-algorithm :source/hash-roots
                :source/tree-hash :source/tree-hash-algorithm
                :source/commit :source/dirty?]))
