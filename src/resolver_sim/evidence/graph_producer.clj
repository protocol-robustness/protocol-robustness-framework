(ns resolver-sim.evidence.graph-producer
  "Deterministically compose domain-adapted canonical evidence nodes.

   This is a producer boundary, not a second evidence identity or trust
   system. Node construction and source validation remain with the existing
   PRF node and domain-adapter implementations. The returned graph is a
   structural forensic view; it does not authenticate roles or source claims."
  (:require [resolver-sim.evidence.forensic-graph :as forensic]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const producer-version 1)

(defn produce
  "Build a deterministic forensic view from a domain adapter's canonical nodes.

   Required inputs are a qualified adapter id/version, canonical sorted unique
   artifact roots, and canonical PRF evidence nodes. Nodes are ordered by their
   existing node hash before invoking the existing forensic/DAG validator.
   Adapter identity is reconstruction metadata, not part of node identity.

   Conflicts and profile gaps are preserved in the result exactly as reported
   by `validate-forensic-evidence-graph`; callers must not interpret a valid
   graph as cryptographic, semantic, deployment, or source authentication."
  [{:keys [adapter-id adapter-version artifact-roots nodes profile]}]
  (let [profile (or profile {:forensic-profile/requirements []})
        roots (vec artifact-roots)
        nodes (vec nodes)]
    (when-not (and (qualified-keyword? adapter-id)
                   (integer? adapter-version) (pos? adapter-version))
      (throw (ex-info "Forensic graph producer identity is missing or invalid"
                      {:adapter-id adapter-id :adapter-version adapter-version})))
    (when-not (and (every? hash-ref/valid-sha256-ref? roots)
                   (= roots (vec (sort roots)))
                   (= (count roots) (count (distinct roots))))
      (throw (ex-info "Forensic graph artifact roots must be sorted, unique SHA-256 references"
                      {:artifact-roots roots})))
    (when-not (and (every? map? nodes)
                   (every? #(and (string? (:node-hash %))
                                 (re-matches #"[0-9a-f]{64}" (:node-hash %))) nodes)
                   (= (count nodes) (count (distinct (map :node-hash nodes)))))
      (throw (ex-info "Forensic graph inputs must contain uniquely identified canonical nodes"
                      {:node-count (count nodes)})))
    (let [ordered-nodes (vec (sort-by :node-hash nodes))
          graph (forensic/validate-forensic-evidence-graph
                 {:nodes ordered-nodes :artifact-roots roots :profile profile})]
      {:producer/version producer-version
       :adapter/id adapter-id
       :adapter/version adapter-version
       :artifact-roots roots
       :nodes ordered-nodes
       :graph graph
       :provenance {:node-identity-owner 'resolver-sim.evidence.node
                    :graph-validator 'resolver-sim.evidence.forensic-graph/validate-forensic-evidence-graph
                    :source-authenticated? false}})))
