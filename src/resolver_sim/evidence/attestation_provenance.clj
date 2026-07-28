(ns resolver-sim.evidence.attestation-provenance
  "Attestation provenance schemas, builders, and validators.

   Attestations carry a :attestation/provenance map that records the context
   in which an attestation was created — run, scenario, trigger, VCS state,
   and optional claim context. This namespace standardises that map.

   The provenance builder returns a map suitable for passing as :provenance
   to build-attestation (or build-claim-result-attestation). The validator
   checks that a provenance map conforms to the schema.

   Schema version: attestation-provenance.v1

   Standard triggers (for the :trigger field):
     :claim-evaluation   — attestation was created as part of claim evaluation
     :replay-complete    — attestation was created when a replay finished
     :startup-validation — attestation was created during system startup
     :manual             — attestation was created by a manual/human process
     :scenario-result    — attestation was created from a scenario result
     :pipeline-step      — attestation was created as part of a pipeline step"

  (:require [clojure.set :as set]
            [resolver-sim.hash.reference :as hash-ref])
  (:import [java.time Instant]))

;; ── Schema ───────────────────────────────────────────────────────────────────

(def ^:const schema-version "attestation-provenance.v1")

(def ^:const required-keys
  "Provenance keys that must always be present."
  #{:provenance/schema-version
    :provenance/trigger
    :provenance/generated-at})

(def ^:const claims-context-required-keys
  "Provenance sub-keys that must be present in :provenance/claims-context
   when :provenance/trigger is :claim-evaluation."
  #{:provenance/claim-id
    :provenance/claim-definition-hash
    :provenance/claim-result-hash
    :provenance/claim-holds?
    :provenance/claim-status})

(def ^:const optional-keys
  "Provenance keys that may be present."
  #{:provenance/run-id
    :provenance/scenario-id
    :provenance/step
    :provenance/vcs-sha
    :provenance/claims-context
    :provenance/producer})

(def ^:const valid-triggers
  "Recognised attestation trigger keywords."
  #{:claim-evaluation
    :replay-complete
    :startup-validation
    :manual
    :scenario-result
    :pipeline-step})

(def ^:const all-known-keys
  "Union of required and optional keys."
  (clojure.set/union required-keys optional-keys))

;; ── Builder ──────────────────────────────────────────────────────────────────

(defn- now-iso
  []
  (str (Instant/now)))

(defn provenance-entry
  "Build a standardised attestation provenance map.

   Required argument:
     trigger — keyword identifying why the attestation was created.
               Must be one of valid-triggers.

   Optional keyword arguments:
     :run-id       — string identifier for the run
     :scenario-id  — string or keyword identifying the scenario
     :step         — integer simulation step
     :vcs-sha      — VCS commit SHA (string)
     :claims-context — map with claim evaluation context
     :producer     — string identifying the producer/emitter
     :generated-at — ISO-8601 timestamp (defaults to now)

   Returns a map suitable for passing as :provenance to build-attestation."
  [trigger & {:keys [run-id scenario-id step vcs-sha claims-context producer generated-at]}]
  (cond-> {:provenance/schema-version schema-version
           :provenance/trigger trigger
           :provenance/generated-at (or generated-at (now-iso))}
    run-id (assoc :provenance/run-id run-id)
    scenario-id (assoc :provenance/scenario-id scenario-id)
    step (assoc :provenance/step step)
    vcs-sha (assoc :provenance/vcs-sha vcs-sha)
    claims-context (assoc :provenance/claims-context claims-context)
    producer (assoc :provenance/producer producer)))

(defn provenance-for-claim
  "Build provenance specifically for a claim-result attestation.

   Combines the general provenance-entry with a claims-context map
   that includes the claim-id, claim-definition-hash, and claim-result-hash
   from the claim result.

   Arguments:
     trigger      — keyword trigger (e.g. :claim-evaluation)
     claim-result — claim result map with :claim-id, :claim-definition-hash,
                    :claim-result-hash keys
     opts         — same opts as provenance-entry (:run-id, :scenario-id, etc.)"
  [trigger claim-result & opts]
  (let [ctx {:provenance/claim-id (:claim-id claim-result)
             :provenance/claim-definition-hash (:claim-definition-hash claim-result)
             :provenance/claim-result-hash (:claim-result-hash claim-result)
             :provenance/claim-holds? (:holds? claim-result)
             :provenance/claim-status (:status claim-result)}]
    (apply provenance-entry trigger :claims-context ctx opts)))

;; ── Validation ───────────────────────────────────────────────────────────────

(defn validate-provenance
  "Validate the structure of an attestation provenance map.

   Returns {:valid? true} or {:valid? false :errors [...]}.

   Checks:
   - Required keys are present
   - Only known keys are present (no unexpected keys)
   - :trigger is a recognised keyword
   - :schema-version matches the current version
   - :generated-at is a non-empty string"
  [provenance]
  (let [errors (volatile! [])]
    ;; Required keys
    (doseq [k required-keys]
      (when-not (contains? provenance k)
        (vswap! errors conj (str "Missing required key: " k))))
    ;; Unknown keys
    (doseq [k (keys provenance)]
      (when-not (contains? all-known-keys k)
        (vswap! errors conj (str "Unknown key: " k))))
    ;; Trigger validation
    (when-let [t (:provenance/trigger provenance)]
      (when-not (contains? valid-triggers t)
        (vswap! errors conj (str "Invalid trigger: " t " (valid: " valid-triggers ")"))))
    ;; Schema version
    (when-let [v (:provenance/schema-version provenance)]
      (when (and (string? v) (not= v schema-version))
        (vswap! errors conj (str "Schema version mismatch: " v " (expected " schema-version ")"))))
    ;; generated-at
    (when-let [g (:provenance/generated-at provenance)]
      (when (not (string? g))
        (vswap! errors conj (str ":provenance/generated-at must be a string, got " (type g))))
      (when (= g "")
        (vswap! errors conj ":provenance/generated-at must not be empty")))
    ;; claims-context — recursive validation when trigger is claim-evaluation
    (when (= (:provenance/trigger provenance) :claim-evaluation)
      (let [ctx (:provenance/claims-context provenance)]
        (if (nil? ctx)
          (vswap! errors conj ":provenance/claims-context is required when :provenance/trigger is :claim-evaluation")
          (do
            (doseq [k claims-context-required-keys]
              (when-not (contains? ctx k)
                (vswap! errors conj (str "Missing required claims-context key: " k))))
            ;; Field-level type validation
            (let [claim-id (:provenance/claim-id ctx)]
              (when (and (contains? ctx :provenance/claim-id) (not (keyword? claim-id)))
                (vswap! errors conj (str ":provenance/claim-id must be a keyword, got " (type claim-id)))))
            (let [def-hash (:provenance/claim-definition-hash ctx)]
              (when (and (contains? ctx :provenance/claim-definition-hash) (not (hash-ref/valid-sha256-ref? def-hash)))
                (vswap! errors conj (str ":provenance/claim-definition-hash is not a valid sha256 ref: " (pr-str def-hash)))))
            (let [result-hash (:provenance/claim-result-hash ctx)]
              (when (and (contains? ctx :provenance/claim-result-hash) (not (hash-ref/valid-sha256-ref? result-hash)))
                (vswap! errors conj (str ":provenance/claim-result-hash is not a valid sha256 ref: " (pr-str result-hash)))))
            (let [holds (:provenance/claim-holds? ctx)]
              (when (and (contains? ctx :provenance/claim-holds?) (not (instance? Boolean holds)))
                (vswap! errors conj (str ":provenance/claim-holds? must be a boolean, got " (type holds)))))
            (let [status (:provenance/claim-status ctx)]
              (when (and (contains? ctx :provenance/claim-status) (not (keyword? status)))
                (vswap! errors conj (str ":provenance/claim-status must be a keyword, got " (type status)))))))))
    (if (seq @errors)
      {:valid? false :errors @errors}
      {:valid? true})))
