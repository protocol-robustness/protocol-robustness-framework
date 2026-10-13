(ns resolver-sim.registry.observation
  "Block-pinned observations over immutable registry releases.

   This namespace is deliberately separate from registry admission. It resolves
   the selected candidate through a release binding, binds an exact block
   identity, and commits only the canonical projection result."
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as canonical]))

(def ^:const observation-schema :prf/registry-chain-observation.v1)
(def ^:private observation-fields #{:type :registry :chain :projection :result})

(defn- fail! [kind message data]
  (throw (ex-info message (merge {:kind kind} data))))

(defn- root? [x]
  (and (string? x) (boolean (re-matches #"[0-9a-f]{64}" x))))

(defn result-root [value]
  (canonical/domain-hash "prf.registry-chain-observation-result.v1" value))

(defn observation-root [observation]
  (canonical/domain-hash
   "prf.registry-chain-observation.v1"
   (dissoc observation :observation-root)))

(defn- resolve-binding [release binding-id scope-root]
  (let [matches (filter #(and (= binding-id (:binding-id %))
                              (= scope-root (:scope-root %)))
                        (:bindings release))]
    (when-not (= 1 (count matches))
      (fail! :registry-binding-not-found
             "release must resolve exactly one binding for binding-id and scope-root"
             {:binding-id binding-id :scope-root scope-root}))
    (first matches)))

(defn build-observation
  "Derive a block-pinned observation from a release-selected candidate.

   `candidates` is an in-memory map keyed by candidate root for the focused
   slice; production loading must resolve the same roots from content-addressed
   storage. The candidate root is always taken from the release binding."
  [{:keys [release release-root binding-id scope-root candidates chain projection-definition-root result]}]
  (when-not (root? release-root)
    (fail! :release-root-invalid "release root must be a canonical root" {}))
  (let [binding (resolve-binding release binding-id scope-root)
        candidate (get candidates (:candidate-root binding))
        block-hash (:block-hash chain)]
    (when-not candidate
      (fail! :candidate-missing "release binding references a missing candidate" {:candidate-root (:candidate-root binding)}))
    (when-not (and (integer? (:chain-id chain))
                   (integer? (:block-number chain))
                   (root? block-hash))
      (fail! :chain-identity-invalid "observation requires chain-id, block-number, and block-hash" {:chain chain}))
    (when-not (= (:chain-id chain) (get-in candidate [:contract :chain-id]))
      (fail! :chain-candidate-mismatch "observation chain differs from selected candidate deployment chain" {}))
    (when-not (root? projection-definition-root)
      (fail! :projection-root-invalid "projection definition root must be canonical" {}))
    (let [observation {:type observation-schema
                       :registry {:release-root release-root
                                  :registry/id (:registry/id release)
                                  :binding-id binding-id
                                  :scope-root scope-root
                                  :candidate-root (:candidate-root binding)}
                       :chain chain
                       :projection {:definition-root projection-definition-root}
                       :result {:value result
                                :result-root (result-root result)}}]
      (assoc observation :observation-root (observation-root observation)))))

(defn validate-observation [observation]
  (when-not (= observation-schema (:type observation))
    (fail! :observation-schema-unsupported "unsupported observation schema" {:type (:type observation)}))
  (let [unknown (set/difference (set (keys observation)) observation-fields #{:observation-root})]
    (when (seq unknown) (fail! :observation-unknown-fields "observation contains unknown fields" {:unknown unknown})))
  (when-not (= (:observation-root observation) (observation-root observation))
    (fail! :observation-root-invalid "observation root does not recompute" {}))
  (when-not (= (get-in observation [:result :result-root])
               (result-root (get-in observation [:result :value])))
    (fail! :result-root-invalid "observation result root does not recompute" {}))
  true)
