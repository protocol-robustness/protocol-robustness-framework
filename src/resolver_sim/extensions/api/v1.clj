(ns resolver-sim.extensions.api.v1
  "Versioned PRF core contracts supported for extension-package consumption.

   This namespace is the compatibility boundary for separately maintained
   extension source. Additions may be made compatibly; changing or removing a
   function or its semantics requires a new API namespace/version. Keep
   implementation namespaces private to PRF core and preserve their behavior
   through this facade.

   This is a source API contract. It does not itself imply that PRF core has
   been published as an independently consumable artifact."
  (:require [resolver-sim.assurance.force-authorisation :as force-auth]
            [resolver-sim.assurance.held-admission :as held-admission]
            [resolver-sim.configuration-head :as configuration-head]
            [resolver-sim.evidence.artifact :as artifact]
            [resolver-sim.hash.canonical :as canonical-hash]
            [resolver-sim.hash.reference :as hash-reference]
            [resolver-sim.hash.sequence :as sequence-hash]
            [resolver-sim.sensitivity.sentinel :as sensitivity]))

;; Force-authorisation and held-admission contracts.
(def normalize-force-authorisation-scope
  force-auth/normalize-force-authorisation-scope)
(def normalize-force-authorisation-record
  force-auth/normalize-force-authorisation-record)
(def force-authorisation-scope-hash force-auth/force-authorisation-scope-hash)
(def scope-hash-missing? force-auth/scope-hash-missing?)
(def scope-hash-mismatch? force-auth/scope-hash-mismatch?)
(def verify-authorisation-usable force-auth/verify-authorisation-usable)
(def semantic-operation-class held-admission/semantic-operation-class)
(def valid-configuration-head-state? configuration-head/valid-head-state?)

;; Content-addressed evidence contracts.
(def artifact-envelope-keys artifact/artifact-envelope-keys)
(def artifact-body artifact/artifact-body)
(def finalize-artifact artifact/finalize-artifact)
(def valid-artifact? artifact/valid-artifact?)
(def preimage-and-hash-valid? artifact/preimage-and-hash-valid?)
(def domain-hash canonical-hash/domain-hash)
(def sha256-ref hash-reference/sha256-ref)
(def canonical-sequence-bytes sequence-hash/canonical-sequence-bytes)
(def sequence-hash sequence-hash/sequence-hash)
(def remote-authority-required-artifact?
  sensitivity/remote-authority-required-artifact?)
