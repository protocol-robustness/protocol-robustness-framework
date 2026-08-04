(ns resolver-sim.evidence.attestation-node
  "Attestation evidence nodes: lightweight evidence records that document
   attestation creation events in the evidence chain.

   When an attestation is created, an attestation evidence node records:
   - the attestation content hash
   - the attestor, subject, claim, and signature status
   - the signed-at timestamp and provenance

   This follows the pattern established by build-claim-evaluation-node in
   slashing.clj: a focused, content-addressed node that references the
   original attestation without duplicating its full content.

   Usage:
     (require '[resolver-sim.evidence.attestation-node :as an])

     ;; Build a lightweight evidence node from an attestation
     (an/build-attestation-node attestation)

     ;; Build, persist to disk, and register in the node + chain registries
     (an/emit-attestation-node! attestation)"
  (:require [resolver-sim.evidence.chain :as chain]
            [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.io.edn :as ppedn]))

;; ── Constants ────────────────────────────────────────────────────────────────

(def ^:const node-schema-version
  "Schema version for attestation evidence nodes."
  "attestation-node.v1")

;; ── Node Builder ─────────────────────────────────────────────────────────────

(defn build-attestation-node
  "Build a lightweight evidence node from an attestation record.

   The node is content-addressed via :evidence-record hash intent and
   captures the attestation's identity, attestor, subject, claim result,
   signature status, and provenance. It does NOT duplicate the attestation's
   full content — the attestation/id serves as the reference.

   Arguments:
     attestation — an attestation record as returned by build-attestation

   Returns a map with:
     :node-hash     — content-addressed hash of the node content
     :result        — attestation summary fields
     :attestations/reference — marker indicating this is an attestation node"
  [attestation]
  (let [content {:attestation-node/schema-version node-schema-version
                 :attestation-node/attestation-id (:attestation/id attestation)
                 :attestation-node/attestor-id (:attestation/attestor-id attestation)
                 :attestation-node/subject-kind (:attestation/subject-kind attestation)
                 :attestation-node/subject-hash (:attestation/subject-hash attestation)
                 :attestation-node/claim-id (:attestation/claim-id attestation)
                 :attestation-node/claim-result (:attestation/claim-result attestation)
                 :attestation-node/signed-at (:attestation/signed-at attestation)
                 :attestation-node/signed? (some? (:attestation/signature attestation))
                 :attestation-node/provenance (:attestation/provenance attestation)}
        node-hash (hc/hash-with-intent {:hash/intent :evidence-record} content)]
    {:node-hash node-hash
     :result content
     :attestations/reference true}))

;; ── Persistence ──────────────────────────────────────────────────────────────

(defn- attestation-node-short-id
  [node]
  (subs (:node-hash node) 0 (min 12 (count (:node-hash node)))))

(defn- attestation-node-filename
  [node]
  (str "attestation-node-" (attestation-node-short-id node) ".edn"))

(defn- attestation-node-artifact-entry
  [node path]
  (let [result (:result node)
        f (java.io.File. path)]
    {:id (str "attestation-node-" (attestation-node-short-id node))
     :kind :attestation-node
     :artifact/type :attestation-node
     :artifact/hash (:node-hash node)
     :artifact/path path
     :attestation/id (:attestation-node/attestation-id result)
     :attestation/attestor-id (:attestation-node/attestor-id result)
     :attestation/claim-id (:attestation-node/claim-id result)
     :attestation/claim-result (:attestation-node/claim-result result)
     :attestation/subject-hash (:attestation-node/subject-hash result)
     :attestation/subject-kind (:attestation-node/subject-kind result)
     :attestation/signed-at (:attestation-node/signed-at result)
     :path path
     :sha256 (chain/compute-file-sha256 path)
     :bytes (.length f)
     :mtime-utc (str (java.time.Instant/ofEpochMilli (.lastModified f)))}))

(defn persist-attestation-node!
  "Persist an attestation evidence node to disk and register it in the
   chain registry. Returns {:node node :artifact-entry entry :path path}.

   Arguments:
     node — an attestation evidence node as returned by build-attestation-node
     dir  — optional output directory (default: evcfg/artifact-dir + '/evidence-nodes')"
  [node & [{:keys [dir]}]]
  (let [out-dir (or dir (str (evcfg/artifact-dir) "/evidence-nodes"))
        f (java.io.File. out-dir (attestation-node-filename node))
        path (.getPath f)]
    (.mkdirs (.getParentFile f))
    (spit path (ppedn/ppr-str node))
    (let [entry (attestation-node-artifact-entry node path)]
      (chain/register-additional-artifact! entry)
      {:node node
       :artifact-entry entry
       :path path})))

;; ── Full Pipeline ────────────────────────────────────────────────────────────

(defn emit-attestation-node!
  "Build, persist, and register an attestation evidence node in one call.

   This is the single entry point for recording an attestation creation event
   in the evidence chain. It:
     1. Builds the evidence node from the attestation
     2. Persists it to disk as EDN
     3. Registers it in the chain registry

   Arguments:
     attestation — an attestation record
     opts        — optional keys:
                   :dir — output directory (default: artifact-dir/evidence-nodes)

   Returns {:node node :artifact-entry entry :path path}"
  [attestation & opts]
  (let [node (build-attestation-node attestation)]
    (persist-attestation-node! node (apply hash-map opts))))
