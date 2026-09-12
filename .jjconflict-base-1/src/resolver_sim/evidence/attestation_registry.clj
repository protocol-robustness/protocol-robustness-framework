(ns resolver-sim.evidence.attestation-registry
  "In-memory attestation registry: stores, indexes, and queries attestation records
   by hash, attestor, subject, and claim result.

   Follows the registry pattern established by node.clj (*node-registry*) and
   revocation.clj (revocation-registry): a dynamic atom keyed by content hash,
   with a with-fresh-registry fixture for test isolation.

   Integration:
     register-attestation! stores the attestation in-memory and optionally
     registers it as a chain artifact via chain/register-additional-artifact!
     when called with :register-in-chain? true."
  (:require [resolver-sim.evidence.chain :as chain]
            [resolver-sim.hash.canonical :as hc]))

;; ── Registry Atom ────────────────────────────────────────────────────────────

(def ^:dynamic *attestation-registry*
  "In-memory registry of attestation records keyed by :attestation/id."
  (atom {}))

;; ── Test Fixture ─────────────────────────────────────────────────────────────

(defmacro with-fresh-registry
  "Execute body with a fresh attestation registry.
   The outer registry is restored when body exits.
   Use for test isolation to prevent cross-test contamination.
   Uses dynamic binding for thread-safe test isolation."
  [& body]
  `(let [fresh-atom# (atom {})]
     (binding [*attestation-registry* fresh-atom#]
       ~@body)))

(defn with-fresh-registry*
  "Thunk-based version of with-fresh-registry for use in higher-order
   contexts (parallel test runner, use-fixtures). Calls (f) inside a
   fresh binding of *attestation-registry*."
  [f]
  (let [fresh (atom {})]
    (binding [*attestation-registry* fresh]
      (f))))

(defn clear-attestations!
  "Reset the registry to empty.
   Useful in test fixtures that don't need isolation."
  []
  (reset! *attestation-registry* {})
  nil)

;; ── Registration ─────────────────────────────────────────────────────────────

(defn- attestation->artifact-entry
  "Build a chain-compatible artifact entry for an attestation record.
   Follows the pattern of node.clj's node-artifact-entry."
  [attestation]
  {:id (str "attestation-" (subs (:attestation/id attestation) 0 (min 12 (count (:attestation/id attestation)))))
   :kind :attestation
   :artifact/type :attestation
   :artifact/hash (:attestation/id attestation)
   :attestation/schema-version (:schema-version attestation)
   :attestation/attestor-id (:attestation/attestor-id attestation)
   :attestation/claim-id (:attestation/claim-id attestation)
   :attestation/claim-result (:attestation/claim-result attestation)
   :attestation/subject-hash (:attestation/subject-hash attestation)
   :attestation/subject-kind (:attestation/subject-kind attestation)
   :attestation/signed-at (:attestation/signed-at attestation)
   :attestation/hash (:attestation/hash attestation)})

(defn register-attestation!
  "Register an attestation record in the in-memory registry.
   The attestation is stored keyed by :attestation/id (the content hash).

   Rejects nil :attestation/id. Rejects registration when an existing entry
   with the same :attestation/id has different canonical content.
   When canonical content is identical, returns the existing record without
   mutation — avoids unnecessary writes preserves non-canonical metadata.

   When opts includes :register-in-chain? true, also registers the attestation
   as a chain artifact via chain/register-additional-artifact!.  Registry
   mutation is authoritative and happens first; chain registration is auxiliary
   and best-effort.  The chain recovery contract guarantees:
     (1) Failure to register the chain artifact does not invalidate the
         primary attestation — the registry is authoritative.
     (2) Verification does not assume every registered attestation has a
         chain artifact.
     (3) The chain artifact is deterministically derivable from the
         attestation record — it can be rebuilt without loss.
     (4) Failure is silently surfaced (chain/register-additional-artifact!
         returns nil); the caller may verify via chain/registry-snapshot.
     (5) Retrying appends a duplicate chain artifact — deduplication is
         the caller's responsibility (see chain/register-additional-artifact! doc).

   Returns the attestation (for threading)."
  [attestation & [{:keys [register-in-chain?]}]]
  (let [id (:attestation/id attestation)]
    (when (nil? id)
      (throw (ex-info "Cannot register attestation without :attestation/id"
                      {:attestation (dissoc attestation :attestation/signature)
                       :reason :nil-attestation-id})))
    (let [existing (get @*attestation-registry* id)]
      (if existing
        (let [canon-existing (hc/project-attestation-record existing :attestation-record)
              canon-new (hc/project-attestation-record attestation :attestation-record)]
          (if (not= canon-existing canon-new)
            (throw (ex-info "Attestation ID conflict: existing entry has different canonical content"
                            {:attestation/id id
                             :existing (dissoc existing :attestation/signature)
                             :conflicting (dissoc attestation :attestation/signature)
                             :reason :attestation-id-conflict}))
            existing))
        ;; No existing entry: authoritative mutation (registry) first,
        ;; auxiliary mutation (chain) second.
        (do
          (swap! *attestation-registry* assoc id attestation)
          (when register-in-chain?
            (chain/register-additional-artifact! (attestation->artifact-entry attestation)))
          attestation)))))

;; ── Lookup ───────────────────────────────────────────────────────────────────

(defn find-attestation
  "Look up an attestation by its content hash (:attestation/id).
   Returns the attestation record or nil if not found."
  [attestation-id]
  (get @*attestation-registry* attestation-id))

(defn all-attestations
  "Return all registered attestations as a vector, sorted by signed-at."
  []
  (->> @*attestation-registry*
       vals
       (sort-by :attestation/signed-at)
       vec))

;; ── Query ────────────────────────────────────────────────────────────────────

(defn find-attestations-by-attestor
  "Find all attestations issued by a given attestor-id.
   Returns a vector sorted by signed-at."
  [attestor-id]
  (->> @*attestation-registry*
       vals
       (filter #(= (:attestation/attestor-id %) attestor-id))
       (sort-by :attestation/signed-at)
       vec))

(defn find-attestations-by-subject
  "Find all attestations about a given subject-hash.
   Returns a vector sorted by signed-at."
  [subject-hash]
  (->> @*attestation-registry*
       vals
       (filter #(= (:attestation/subject-hash %) subject-hash))
       (sort-by :attestation/signed-at)
       vec))

(defn find-attestations-by-claim-result
  "Find all attestations with a given claim result (:verified, :reproduced, etc.).
   Returns a vector sorted by signed-at."
  [claim-result]
  (->> @*attestation-registry*
       vals
       (filter #(= (:attestation/claim-result %) claim-result))
       (sort-by :attestation/signed-at)
       vec))

(defn find-attestations-by-claim-id
  "Find all attestations for a given claim definition id.
   Returns a vector sorted by signed-at."
  [claim-id]
  (->> @*attestation-registry*
       vals
       (filter #(= (:attestation/claim-id %) claim-id))
       (sort-by :attestation/signed-at)
       vec))

;; ── Status ───────────────────────────────────────────────────────────────────

(defn registry-status
  "Return summary information about the current registry state."
  []
  (let [r @*attestation-registry*]
    {:count (count r)
     :attestors (->> r vals (map :attestation/attestor-id) distinct sort vec)
     :claim-results (->> r vals (map :attestation/claim-result) frequencies)
     :subject-kinds (->> r vals (map :attestation/subject-kind) frequencies)
     :empty? (empty? r)}))
