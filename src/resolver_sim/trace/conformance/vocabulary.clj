(ns resolver-sim.trace.conformance.vocabulary
  "Trace-domain vocabulary and validator implementation identities.

   Domain adapter constants (NOT part of the generic conformance package):
   the supported action/role vocabulary for CDRS v0.2 fixtures and the
   content roots identifying the concrete validator implementations.
   Content roots are stable byte-committed identities so a validation result
   always identifies which implementation produced it."
  (:require [resolver-sim.hash.canonical :as hc]))

(def supported-fixture-contracts
  #{:trace-fixture.v2 :trace-fixture.v1})

(def actions
  "Actions the trace harness can replay (CDRS v0.2)."
  #{:create-escrow :release :sender-cancel :recipient-cancel
    :raise-dispute :release-as-dispute-resolver :cancel-as-dispute-resolver
    :execute-pending-settlement :auto-cancel-disputed :escalate-dispute
    :register-stake :withdraw-stake})

(def roles
  "Roles the trace harness resolves to explicit addresses (mirrors
   _roleToAddressV2 in TraceEquivalence.t.sol).  resolver1 is intentionally
   absent — the harness cannot resolve it — so traces using it are correctly
   classified as unsupported."
  #{:buyer :seller :resolver :l0resolver :l1resolver :l2resolver
    :keeper :executor :governance :legacyresolver
    :flood-buyer :flood-buyers :0xAlice :0xBob :0xseller0 :resolver0})

(defn- identity-root
  "Deterministic content root for a validator implementation identity."
  [canonical]
  (hc/domain-hash :conformance-validator-implementation-v1 canonical))

(def ^:const validator-version 1)

(def trace-fixture-v2-schema-root
  "Content root of the structural (schema) validator implementation."
  (identity-root
   {:validator/id :trace-fixture-v2-schema
    :kind :schema
    :version validator-version
    :canonicalizer :trace-fixture.v2}))

(def trace-fixture-v2-semantics-root
  "Content root of the semantic validator implementation."
  (identity-root
   {:validator/id :trace-fixture-v2-semantics
    :kind :semantic
    :version validator-version
    :canonicalizer :trace-fixture.v2}))
