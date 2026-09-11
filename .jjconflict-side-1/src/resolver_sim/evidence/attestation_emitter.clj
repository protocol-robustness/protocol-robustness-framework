(ns resolver-sim.evidence.attestation-emitter
  "Attestation pipeline: single-entry-point orchestration for building,
   registering, and creating evidence nodes for attestations.

   Composes the attestation builder (attestation.clj), registry
   (attestation-registry.clj), evidence node builder (attestation-node.clj),
   and provenance (attestation-provenance.clj) into a single coherent
   workflow.

   Usage:
     (require '[resolver-sim.evidence.attestation-emitter :as ae])

     ;; Full pipeline: build from attestor + subject + claim
     (ae/emit-attestation! attestor subject :verified {:run-id \"run-1\"})

     ;; Full pipeline for a claim result
     (ae/emit-claim-result-attestation! attestor claim-result
       {:run-id \"run-1\" :scenario-id \"S01\"})"
  (:require [resolver-sim.evidence.attestation :as att]
            [resolver-sim.evidence.attestation-node :as an]
            [resolver-sim.evidence.attestation-provenance :as ap]
            [resolver-sim.evidence.attestation-registry :as ar]))

;; ── Low-level: register + emit node from pre-built attestation ──────────────

(defn- register-and-emit-node
  "Register an attestation in the attestation registry (with chain registration)
   and emit an evidence node for it.

   Returns {:attestation <attestation>
            :node-result <result from emit-attestation-node!>}"
  [attestation]
  (let [registered (ar/register-attestation! attestation
                                             {:register-in-chain? true})
        node-result (an/emit-attestation-node! attestation)]
    {:attestation registered
     :node-result node-result}))

;; ── emit-attestation! ────────────────────────────────────────────────────────

(defn emit-attestation!
  "Full attestation pipeline: build, register, and create evidence node.

   Arguments:
     attestor     — map {:type ... :id ...}
     subject      — map {:type :evidence-node|:claim :hash ...|:claim-id ...}
     claim        — keyword claim-result (:verified, :reproduced, etc.)
     opts         — optional map with keys:
                     :run-id, :scenario-id, :step, :vcs-sha
                     :signed-at, :signing-key-id, :signing-fn
                     :claim-id, :provenance, :metadata, :trigger

   Returns {:attestation <attestation>
            :node-result <result from emit-attestation-node!>}"
  [attestor subject claim & [{:keys [signed-at signing-key-id signing-fn
                                     claim-id provenance metadata
                                     run-id scenario-id step vcs-sha trigger]
                              :as opts}]]
  (let [provenance (or provenance
                       (when (or run-id scenario-id trigger)
                         (ap/provenance-entry
                          (or trigger :pipeline-step)
                          :run-id run-id
                          :scenario-id scenario-id
                          :step step
                          :vcs-sha vcs-sha)))
        attestation (att/build-attestation
                     attestor subject claim
                     (cond-> {}
                       signed-at (assoc :signed-at signed-at)
                       claim-id (assoc :claim-id claim-id)
                       signing-key-id (assoc :signing-key-id signing-key-id)
                       signing-fn (assoc :signing-fn signing-fn)
                       provenance (assoc :provenance provenance)
                       metadata (assoc :metadata metadata)))]
    (register-and-emit-node attestation)))

;; ── emit-claim-result-attestation! ──────────────────────────────────────────

(defn emit-claim-result-attestation!
  "Full claim-result attestation pipeline: build, register, and create
   evidence node in one call.

   Arguments:
     attestor     — map {:type ... :id ...}
     claim-result — claim result map with :claim-id, :claim-result-hash,
                    :claim-definition-hash, etc.
     opts         — optional map with keys:
                     :run-id, :scenario-id, :step, :vcs-sha
                     :claim       — claim result for this attestation
                                    (default: :verified)
                     :signed-at, :signing-key-id, :signing-fn
                     :metadata, :trigger

   Returns {:attestation <attestation>
            :node-result <result from emit-attestation-node!>}"
  [attestor claim-result & [{:keys [signed-at signing-key-id signing-fn
                                    metadata claim run-id scenario-id step
                                    vcs-sha trigger]
                             :or {claim :verified}
                             :as opts}]]
  (let [provenance (ap/provenance-for-claim
                    (or trigger :claim-evaluation)
                    claim-result
                    :run-id run-id
                    :scenario-id scenario-id
                    :step step
                    :vcs-sha vcs-sha)
        attestation (att/build-claim-result-attestation
                     attestor claim-result
                     (cond-> {:claim claim}
                       signed-at (assoc :signed-at signed-at)
                       signing-key-id (assoc :signing-key-id signing-key-id)
                       signing-fn (assoc :signing-fn signing-fn)
                       provenance (assoc :provenance provenance)
                       metadata (assoc :metadata metadata)))]
    (register-and-emit-node attestation)))
