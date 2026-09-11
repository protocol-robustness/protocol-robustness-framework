(ns resolver-sim.evidence.attestation-dag
  "Attestation evidence DAG nodes: full execution evidence nodes that record
   attestation creation events as DAG-verifiable nodes with canonical references.

   Unlike the lightweight attestation-node (Phase 6) which creates simple
   content-hash records, this namespace produces full execution evidence nodes
   using build-execution-node from node.clj, supporting:
   - DAG parent/child linking via :parent-hashes
   - Bootstrap root references
   - Execution registry validation
   - Policy-based evidence capture
   - Node registry integration
   - Chain registry persistence

   Usage:
     (require '[resolver-sim.evidence.attestation-dag :as adag])

     ;; Build a DAG node from an attestation
     (adag/build-attestation-dag-node attestation opts)

     ;; Full pipeline: build, persist, register
     (adag/emit-attestation-dag-node! attestation opts)

     ;; Chain multiple attestations
     (adag/build-attestation-dag-node a2 {:parent-hashes [(:node-hash node1)]})"
  (:require [resolver-sim.evidence.attestation :as att]
            [resolver-sim.evidence.node :as node]))

;; ── Constants ────────────────────────────────────────────────────────────────

(def ^:const attestation-execution-id
  "Execution registry ID for attestation evidence nodes."
  :execution/attestation)

(def ^:const default-policy-id
  "Default evidence policy for attestation DAG nodes — delegates to node/default-policy-id
  to ensure single-source consistency (the value is committed to evidence-node hashes)."
  node/default-policy-id)

;; ── Validation ───────────────────────────────────────────────────────────────

(defn- validate-or-throw
  "Validate an attestation before building a DAG node.
   Throws if nil or structurally invalid."
  [attestation]
  (when (nil? attestation)
    (throw (ex-info "Cannot build attestation DAG node: attestation is nil"
                    {:error :attestation/nil})))
  (let [{:keys [valid? errors]} (att/validate-attestation-shape attestation)]
    (when-not valid?
      (throw (ex-info "Cannot build attestation DAG node: attestation failed shape validation"
                      {:error :attestation/invalid-shape
                       :errors errors})))))

;; ── DAG Node Builder ─────────────────────────────────────────────────────────

(defn- attestation-inputs
  "Extract the canonical input map from an attestation record for the
   evidence node's :inputs-hash computation."
  [attestation]
  {:schema-version (:schema-version attestation)
   :attestation/id (:attestation/id attestation)
   :attestation/attestor-id (:attestation/attestor-id attestation)
   :attestation/subject-kind (:attestation/subject-kind attestation)
   :attestation/subject-hash (:attestation/subject-hash attestation)
   :attestation/claim-id (:attestation/claim-id attestation)
   :attestation/claim-result (:attestation/claim-result attestation)
   :attestation/signed-at (:attestation/signed-at attestation)
   :attestation/provenance (:attestation/provenance attestation)})

(defn- attestation-outputs
  "Extract the output summary from an attestation record."
  [attestation]
  {:attestation/hash (:attestation/hash attestation)
   :attestation/id (:attestation/id attestation)
   :signed? (some? (:attestation/signature attestation))
   :attestation/claim-result (:attestation/claim-result attestation)})

(defn build-attestation-dag-node
  "Build a full DAG evidence node from an attestation record.

   Uses build-execution-node to create a registry-validated evidence node
   with proper DAG linking support.

   Arguments:
     attestation — attestation record
     opts        — optional map with keys:
                    :parent-hashes    — vector of parent node hashes (DAG linking)
                    :bootstrap-roots  — vector of bootstrap root hashes
                    :policy-id        — evidence policy id (default: :evidence-policy/computed)
                    :timestamp        — ISO-8601 timestamp (default: now)
                    :extensions       — extra data to include in the node

   Returns the full evidence node map as built by build-execution-node.
   Throws if attestation is nil or fails shape validation."
  [attestation & [{:keys [parent-hashes bootstrap-roots policy-id timestamp extensions]
                   :or {policy-id default-policy-id}}]]
  (validate-or-throw attestation)
  (node/build-execution-node
   {:execution-id attestation-execution-id
    :policy-id policy-id
    :parent-hashes (vec (or parent-hashes []))
    :bootstrap-roots (vec (or bootstrap-roots []))
    :timestamp (or timestamp (str (java.time.Instant/now)))
    :status :pass
    :inputs (attestation-inputs attestation)
    :outputs (attestation-outputs attestation)
    :attestations [(str "attestation:sha256:" (:attestation/id attestation))]
    :extensions (merge {:attestation/type :attestation-dag}
                       (or extensions {}))
    :execution-kind :attestation
    :runner :attestation-emitter}))

;; ── Full Pipeline ────────────────────────────────────────────────────────────

(defn emit-attestation-dag-node!
  "Build, persist, and register an attestation DAG evidence node in one call.

   This is the single entry point for recording an attestation as a
   DAG-verifiable evidence node. It:
     1. Builds a full execution evidence node via build-execution-node
     2. Persists it to disk
     3. Registers it in the node registry and chain registry

   Arguments:
     attestation — attestation record
     opts        — same opts as build-attestation-dag-node

   Returns the node result from emit-execution-node!"
  [attestation & [{:keys [parent-hashes bootstrap-roots policy-id timestamp extensions]
                   :or {policy-id default-policy-id}}]]
  (validate-or-throw attestation)
  (node/emit-execution-node!
   {:execution-id attestation-execution-id
    :policy-id policy-id
    :parent-hashes (vec (or parent-hashes []))
    :bootstrap-roots (vec (or bootstrap-roots []))
    :timestamp (or timestamp (str (java.time.Instant/now)))
    :status :pass
    :inputs (attestation-inputs attestation)
    :outputs (attestation-outputs attestation)
    :attestations [(str "attestation:sha256:" (:attestation/id attestation))]
    :extensions (merge {:attestation/type :attestation-dag}
                       (or extensions {}))
    :execution-kind :attestation
    :runner :attestation-emitter}))

;; ── Convenience: DAG chain builders ─────────────────────────────────────────

(defn chain-attestation-dag-nodes
  "Build a chain of DAG evidence nodes from a sequence of attestations,
   where each node references the previous as a parent.

   Arguments:
     attestations — seq of attestation records (ordered)
     opts         — base opts applied to all nodes
                    (overridable :parent-hashes will be extended)

   Returns a vector of evidence node maps."
  [attestations & [base-opts]]
  (let [opts (or base-opts {})]
    (loop [remaining (seq attestations)
           prev-hash nil
           results []]
      (if remaining
        (let [a (first remaining)
              node (build-attestation-dag-node
                    a (assoc opts :parent-hashes
                             (cond-> (vec (or (:parent-hashes opts) []))
                               prev-hash (conj prev-hash))))]
          (recur (next remaining) (:node-hash node) (conj results node)))
        results))))
