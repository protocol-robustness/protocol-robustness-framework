(ns resolver-sim.conformance.envelope
  "Envelope schema versioning (G5a-lite).

   The generic envelopes are versioned now, even while domain payloads remain
   experimental.  Version is included in every canonical preimage; unknown or
   unsupported versions fail closed.  Migration utilities are deferred until an
   actual v2 exists.")

(def known-schema-versions
  "Envelope schema versions currently supported."
  #{"conformance.validation-receipt/v1"
    "conformance.subject-identity/v1"
    "conformance.execution-plan/v1"
    "conformance.reconciliation/v1"
    "conformance.coverage/v1"
    "conformance.reproduction-lineage/v1"
    "conformance.claim/v1"
    "conformance.implementation-registry/v1"})

(defn known-schema-version?
  [v]
  (contains? known-schema-versions v))

(defn assert-known-schema-version!
  "Reject an unknown/unsupported envelope schema version (fail closed)."
  [v]
  (when-not (known-schema-version? v)
    (throw (ex-info "unknown conformance envelope schema version"
                    {:schema-version v
                     :known (vec (sort known-schema-versions))})))
  v)
