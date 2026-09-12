(ns resolver-sim.evidence.attestation-node
  "Attestation evidence nodes: canonical full execution evidence nodes that
   record attestation creation events in the evidence DAG.

   This namespace is a thin front-end over resolver-sim.evidence.attestation-dag
   (the canonical attestation recorder). Both produce the same full evidence-node
   shape via resolver-sim.evidence.node, so there is a single attestation node
   shape across the codebase (execution-id :execution/attestation, runner
   :attestation-emitter).

   The node is content-addressed and references the original attestation via its
   typed :attestations reference (attestation:sha256:<id>) without duplicating
   the attestation's full content.

   Usage:
     (require '[resolver-sim.evidence.attestation-node :as an])

     ;; Build a canonical evidence node from an attestation
     (an/build-attestation-node attestation)

     ;; Build, persist, and register in the node + chain registries
     (an/emit-attestation-node! attestation)"
  (:require [resolver-sim.evidence.attestation-dag :as adag]
            [resolver-sim.evidence.node :as node]))

;; ── Node Builder ─────────────────────────────────────────────────────────────

(defn build-attestation-node
  "Build a canonical execution evidence node from an attestation record.

   Delegates to build-attestation-dag-node, producing the single canonical
   attestation evidence-node shape. Content-addressed: :node-id == :node-hash.

   Arguments:
     attestation — an attestation record as returned by build-attestation
     opts        — optional map with keys :parent-hashes, :bootstrap-roots,
                   :policy-id, :timestamp, :extensions (see attestation-dag)

   Returns the full evidence node map."
  [attestation & [opts]]
  (adag/build-attestation-dag-node attestation opts))

;; ── Full Pipeline ────────────────────────────────────────────────────────────

(defn emit-attestation-node!
  "Build, persist, and register a canonical attestation evidence node in one call.

   This is the single entry point for recording an attestation creation event in
   the evidence chain and node registry. It:
     1. Builds the canonical evidence node
     2. Persists it to disk (evidence-nodes/) and registers it in the chain
     3. Registers it in the node registry

   Arguments:
     attestation — an attestation record
     opts        — optional map (same keys as build-attestation-node)

   Returns {:node node :artifact-entry entry :path path}."
  [attestation & [opts]]
  (let [node (build-attestation-node attestation opts)
        result (node/persist-execution-node! node)]
    (node/register-node! node)
    result))
