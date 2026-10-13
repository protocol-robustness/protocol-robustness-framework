(ns resolver-sim.evidence.revocation
  "ATTESTATION_SPEC_V1 §7 and ATTESTOR_REGISTRY_SPEC_V1 §8 revocation support.

   Revocations are separate records — they do not modify the original attestation.
   Immutability of attestations is preserved.

   Usage:
     (require '[resolver-sim.evidence.revocation :as rev])

     (def r (rev/build-revocation \"attestation-uuid\" :key-compromised
              {:revoked-by {:type :auditor :id \"audit-team\"}}))
     (rev/register-revocation! r)
     (rev/attestation-revoked? \"attestation-uuid\") ; => true"
  (:require [clojure.set :as set])
  (:import [java.util UUID]))

;; ── Constants ────────────────────────────────────────────────────────────────

(def ^:const schema-version 1)

;; ── Revocation Registry ─────────────────────────────────────────────────────

(def ^:dynamic revocation-registry
  "In-memory revocation registry.
   Maps attestation-id to vector of revocation records.
   Use register-revocation! to add entries; use with-fresh-registry for tests."
  (atom {} :meta {:doc "ATTESTATION_SPEC_V1 §7 revocation registry"}))

(defn clear-revocations!
  "Clear all revocation records. Useful between test runs."
  []
  (reset! revocation-registry {}))

(defmacro with-fresh-registry
  "Run body with a fresh empty revocation registry.
   Restores the previous state after body completes.
   Uses dynamic binding for thread-safe test isolation."
  [& body]
  `(let [fresh-atom# (atom {})]
     (binding [revocation-registry fresh-atom#]
       ~@body)))

;; ── Revocation Builder ──────────────────────────────────────────────────────

(defn- default-timestamp
  []
  (str (java.time.Instant/now)))

(defn build-revocation
  "Build a revocation record for an attestation.

   Arguments:
     revoked-attestation-id  — the :attestation-id of the attestation being revoked
     reason                  — keyword or string describing why (e.g. :key-compromised)
     opts                    — optional map:
       :revoked-by    — map {:type ... :id ...} identifying who issued the revocation
       :timestamp     — ISO-8601 UTC string (default: now)
       :signing-fn    — (fn [data]) returning {:algorithm :public-key-id :signature-bytes}

   Returns a revocation record map.
   The original attestation is NOT modified."
  [revoked-attestation-id reason & [{:keys [revoked-by timestamp signing-fn]}]]
  (let [revocation {:schema-version schema-version
                    :revocation-id (str (UUID/randomUUID))
                    :revokes-attestation-id revoked-attestation-id
                    :reason reason
                    :timestamp (or timestamp (default-timestamp))}
        with-revoked-by (if revoked-by
                          (assoc revocation :revoked-by revoked-by)
                          revocation)
        to-sign (dissoc with-revoked-by :signature)]
    (if signing-fn
      (assoc with-revoked-by :signature (signing-fn to-sign))
      with-revoked-by)))

;; ── Registry Operations ─────────────────────────────────────────────────────

(defn register-revocation!
  "Register a revocation record in the revocation registry.
   The original attestation is not modified — this is an append-only operation.
   Returns the revocation record."
  [revocation]
  (let [target-id (:revokes-attestation-id revocation)]
    (swap! revocation-registry update target-id
           (fn [existing]
             (conj (or existing []) revocation)))
    revocation))

(defn find-revocations
  "Find all revocation records for a given attestation-id.
   Returns a vector (possibly empty). Never returns nil."
  [attestation-id]
  (get @revocation-registry attestation-id []))

(defn attestation-revoked?
  "Check if an attestation has been revoked.
   Returns true if at least one revocation record exists for the attestation-id."
  [attestation-id]
  (boolean (seq (find-revocations attestation-id))))

(defn all-revocations
  "Return a flat vector of all revocation records across all attestations."
  []
  (into [] cat (vals @revocation-registry)))

;; ── Shape Validation ────────────────────────────────────────────────────────

(defn- missing-field-error
  [field]
  {:type :revocation/missing-field
   :field field
   :message (str "Missing required field: " (name field))})

(defn validate-revocation-shape
  "Validate that a revocation record conforms to ATTESTATION_SPEC_V1 §7.
   Returns {:valid? true} or {:valid? false :errors [...]}.

   Checks:
   - Required fields: :revokes-attestation-id, :reason, :timestamp
   - :revokes-attestation-id is a non-empty string"
  [revocation]
  (let [required #{:revokes-attestation-id :reason :timestamp}
        missing (clojure.set/difference required (set (keys revocation)))
        missing-errors (mapv missing-field-error missing)
        id-errors (when-let [id (:revokes-attestation-id revocation)]
                    (when-not (and (string? id) (seq id))
                      [{:type :revocation/invalid-attestation-id
                        :message "revokes-attestation-id must be a non-empty string"
                        :value id}]))
        all-errors (vec (concat missing-errors id-errors))]
    (if (seq all-errors)
      {:valid? false :errors all-errors}
      {:valid? true})))
