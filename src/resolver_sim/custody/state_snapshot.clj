(ns resolver-sim.custody.state-snapshot
  "Read-only authoritative-state interface for the K4 custody daemon.

   The authoritative authority state lives in the PRF process's in-memory
   AuthorityStateStore. The custody daemon is a separate process; to establish
   authoritative state independently of a caller-supplied proof, the PRF
   process exports a read-only snapshot (head + retained material) that the
   daemon reads and re-verifies by recomputing every committed root from the
   material bodies.

   NOTE (AUTH-K4 state boundary): the snapshot file is written by the PRF
   process, so a compromised PRF process could write a forged snapshot. True
   cross-process independence (daemon learns the current head without trusting
   the PRF process) requires a persisted/on-chain authoritative state source,
   which does not exist in this repo (the store is in-memory). This namespace
   provides the smallest read-only interface that exists today and documents
   that trust boundary rather than replacing it with unauthenticated caller
   material per request."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [resolver-sim.benchmark.governed-authority-state :as state]
            [resolver-sim.benchmark.review-governance :as governance]))

(def snapshot-schema "authority-state-snapshot-export.v1")

(defn export-snapshot!
  "Write the authoritative state snapshot (head + retained material) to `path`.
   `store-state` is the deref of the AuthorityStateStore's state atom
   ({:head ... :material {state-root material}})."
  [store-state path]
  (let [snapshot {:artifact/schema snapshot-schema
                  :head (:head store-state)
                  :material (:material store-state)}]
    (.mkdirs (.getParentFile (io/file path)))
    (spit path (pr-str snapshot))
    {:path path :head (:head snapshot) :material-count (count (:material snapshot))}))

(defn- recompute-roots [material]
  (let [gov (:authority-material/review-governance material)
        ks  (:authority-material/signer-key-set material)
        round (:authority-material/review-round material)]
    {:review-governance/root (governance/governance-root gov)
     :signer-key-set/root (state/signer-key-set-root ks)
     :review-round/root (state/review-round-material-root round)}))

(defn verify-snapshot
  "Recompute every committed material root from its bodies and require it to
   match the material's committed root. Returns {:valid? bool :errors [...]}.

   This is the daemon's independent consistency check over the state it reads;
   it does not trust the exported roots without recomputation."
  [snapshot]
  (let [materials (vals (:material snapshot))
        errors (vec
                (for [material materials
                      :let [recomputed (recompute-roots material)]
                      [k v] recomputed
                      :when (not= v (get material k))]
                  {:material-root k :committed (get material k) :recomputed v}))]
    {:valid? (empty? errors)
     :errors errors
     :head (:head snapshot)}))

(defn read-snapshot [path]
  (let [snapshot (edn/read-string (slurp path))]
    (when (= snapshot-schema (:artifact/schema snapshot))
      snapshot)))

(defn material-at [snapshot state-root]
  (get-in snapshot [:material state-root]))

(defn current-head [snapshot]
  (:head snapshot))