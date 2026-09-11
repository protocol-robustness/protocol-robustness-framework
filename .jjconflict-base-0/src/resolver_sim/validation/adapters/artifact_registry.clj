(ns resolver-sim.validation.adapters.artifact-registry
  "Adapter: artifact registry diagnostic → validation-root.v1.

   Translates a registry diagnostic/result map into a finalized validation
   root using resolver-sim.validation.state and resolver-sim.validation.root.

   This namespace does NOT call the utility state monad directly.

   ── input shape (registry-result) ──

     {:checks    [(check-map ...)]       ;; optional, vector of individual check results
      :errors    [(error-map ...)]       ;; optional, errors not already covered by checks
      :warnings  [(warning-map ...)]     ;; optional, warnings not already covered by checks
      :metadata  metadata-map}           ;; optional, stored under :extra

   ── check-map shape ──

     Required:
       :check/id   keyword       ;; stable identifier, e.g. :registry/required-files
       :status     :passed | :failed | :warning

     For :failed checks (both default to :check/id):
       :error-key   keyword      ;; stable error-key for :error-keys set
       :severity    :critical | :warning | :info  (default :warning)

     For :warning checks (both default to :check/id):
       :warning-key keyword      ;; stable warning-key for :warning-keys set
       :severity    :critical | :warning | :info  (default :warning)

     Optional (any status):
       :message      string      ;; human-readable explanation
       :evidence-ref map|nil     ;; {:artifact-id kw :path [...]}
       :expected     any         ;; expected value
       :actual       any         ;; actual value

     Extra arbitrary keys on the check map are preserved verbatim into
     the final root's :checks vector.  Use this for artifact identity fields:

       {:check/id :registry/missing-artifact
        :status :failed
        :error-key :artifact/hash-mismatch
        :artifact-id \"coverage\"
        :expected-path \"results/test-artifacts/coverage.json\"}

   ── error/warning map shape (for :errors / :warnings lists) ──

     {:key           keyword       ;; stable identifier, e.g. :registry/structural-issue
      :severity      keyword       ;; :critical | :warning | :info  (default :warning)
      :message       string        ;; human-readable explanation
      :evidence-ref  map|nil}      ;; {:artifact-id kw :path [...]}

   ── metadata-map shape ──

     Stored verbatim under the final root's :extra key.
     Common keys:

       :run-id          string  ;; e.g. \"20260614-172333\"
       :scenario-id     string  ;; originating scenario identifier
       :artifact-count  integer ;; number of artifacts examined
       :registry-level  string  ;; \"CORE\" | \"DIAGNOSTIC\" | \"TRACE\"

   ── status precedence ──

     Uses resolver-sim.validation.root/status-precedence:
       :failed  — any :error-keys present (highest severity, dominates)
       :warning — any :warning-keys present, no errors
       :passed  — no error or warning keys (lowest severity)

   ── key stability ──

     All issue keys (:error-key, :warning-key, :key) MUST be keywords.
     They are used as-is for set membership in :error-keys and :warning-keys.
     No text derivation, string hashing, or auto-naming is applied.
     Fallback values (:unclassified-error, :unclassified-warning) are used
     only when no keyword is provided.

   ── output ──

     A finalized validation root in validation-root.v1 format returned by
     registry-result->validation-root."

  (:require
   [resolver-sim.validation.root :as root]
   [resolver-sim.validation.state :as vs]))

;; ── private helpers ──────────────────────────────────────────────────────────

(def ^:private unknown-status-warning-key
  "Warning key used when a check has an unrecognized :status."
  :adapter/unknown-check-status)

(defn- check->computation
  "Convert a single check map into a state computation.
   Dispatches based on :status:
     :failed  → record-check + record-error
     :warning → record-check + record-warning
     :passed  → record-check + record-pass
     else     → record-check + warning (unknown status, treated as warning)"
  [check]
  (case (:status check)
    :failed
    (vs/bind (vs/record-check check)
             (fn [_]
               (vs/record-error
                {:key         (or (:error-key check) (:check/id check) :unclassified-error)
                 :severity    (:severity check :warning)
                 :message     (:message check "")
                 :evidence-ref (:evidence-ref check)})))
    :warning
    (vs/bind (vs/record-check check)
             (fn [_]
               (vs/record-warning
                {:key         (or (:warning-key check) (:check/id check) :unclassified-warning)
                 :severity    (:severity check :warning)
                 :message     (:message check "")
                 :evidence-ref (:evidence-ref check)})))
    :passed
    (vs/bind (vs/record-check check)
             (fn [_]
               (vs/record-pass)))
    ;; unknown status — record the check with a structured warning
    (vs/bind (vs/record-check check)
             (fn [_]
               (vs/record-warning
                {:key         unknown-status-warning-key
                 :severity    :warning
                 :message     (str "Unknown check status "
                                   (pr-str (:status check))
                                   " for check.id "
                                   (pr-str (:check/id check)))})))))

(defn- checks->computation
  "Convert a vector of check maps into a single state computation."
  [checks]
  (vs/sequence-state (mapv check->computation checks)))

(defn- errors->computation
  "Convert a vector of error maps into a state computation that records each."
  [errors]
  (vs/sequence-state
   (mapv (fn [err]
           (vs/record-error
            {:key         (:key err :unclassified-error)
             :severity    (:severity err :warning)
             :message     (:message err "")
             :evidence-ref (:evidence-ref err)}))
         errors)))

(defn- warnings->computation
  "Convert a vector of warning maps into a state computation that records each."
  [warnings]
  (vs/sequence-state
   (mapv (fn [warn]
           (vs/record-warning
            {:key         (:key warn :unclassified-warning)
             :severity    (:severity warn :warning)
             :message     (:message warn "")
             :evidence-ref (:evidence-ref warn)}))
         warnings)))

(defn- metadata->computation
  "Set suite metadata and merge extra metadata into state."
  [metadata]
  (let [m (or metadata {})]
    (vs/bind (vs/set-suite-id :artifact-registry)
             (fn [_]
               (vs/bind (vs/set-suite-type :evidence)
                        (fn [_]
                          (vs/merge-extra m)))))))

;; ── public API ───────────────────────────────────────────────────────────────

(defn registry-result->state-computation
  "Translate a registry-result map into a state computation.

   The computation records checks, errors, warnings, sets suite metadata,
   and merges extra metadata — ready for build-root."
  [registry-result]
  (let [checks   (:checks   registry-result [])
        errors   (:errors   registry-result [])
        warnings (:warnings registry-result [])
        metadata (:metadata registry-result {})]
    (vs/bind (checks->computation checks)
             (fn [_]
               (vs/bind (errors->computation errors)
                        (fn [_]
                          (vs/bind (warnings->computation warnings)
                                   (fn [_]
                                     (metadata->computation metadata)))))))))

(defn registry-result->validation-root
  "Translate a registry-result map into a finalized validation-root.v1 map.

   This is the primary entry point. Calls build-root with the
   state computation derived from the registry result.

   Usage:
     (registry-result->validation-root
       {:checks [{:check/id :registry/required-files
                  :status :passed}]
        :metadata {:run-id \"20260614\" :artifact-count 5}})"
  [registry-result]
  (root/build-root (registry-result->state-computation registry-result)))
