(ns resolver-sim.io.artifacts
  "Code-level artifact catalogue for canonical run-bundle artifacts.

   Each entry declares the artifact's filename, semantic kind, schema,
   durability class, and whether it appears on the verifier review surface.

   This is the code-level source of truth.  config/evidence.json may
   enrich or validate these entries but must not silently redefine them.")


(def artifacts
  {:run/completion
   {:file "completion.json"
    :kind :run-completion
    :schema "run-completion.v1"
    :durability :canonical
    :review-surface? true}

   :run/package-index
   {:file "manifest/run-package-index.json"
    :kind :run-package-index
    :schema "run-package-index.v1"
    :durability :canonical
    :review-surface? true}

   :artifacts/registry
   {:file "manifest/artifacts.json"
    :kind :artifacts-registry
    :schema "artifacts-registry.v1"
    :durability :canonical
    :review-surface? true}

   :artifacts/validation
   {:file "manifest/artifacts-validation.json"
    :kind :artifacts-validation
    :schema "artifacts-validation.v1"
    :durability :canonical
    :review-surface? true}

   :sensitivity/report
   {:file "manifest/sensitivity-report.json"
    :kind :sensitivity-report
    :schema "sensitivity-report.v2"
    :durability :canonical
    :review-surface? true}})


(def noncanonical-artifacts
  {:run/state
   {:file ".run-state"
    :kind :operational-state
    :durability :transient
    :review-surface? false}

   :run/lock
   {:file ".run.lock"
    :kind :operational-lock
    :durability :transient
    :review-surface? false}})


(def all-artifact-ids
  "Every registered artifact ID, canonical and transient, for validation."
  (vec (concat (keys artifacts) (keys noncanonical-artifacts))))


(defn artifact
  "Look up an artifact by its semantic ID.
   Returns the artifact map or throws for unknown IDs."
  [artifact-id]
  (or (get artifacts artifact-id)
      (get noncanonical-artifacts artifact-id)
      (throw (ex-info "Unknown artifact ID"
                      {:artifact/id artifact-id
                       :known-ids all-artifact-ids}))))


(defn artifact-file
  "Return the filename string for an artifact, e.g.
   (artifact-file :run/completion) → \"completion.json\"."
  [artifact-id]
  (:file (artifact artifact-id)))


(defn artifact-path
  "Resolve an artifact to a Path relative to run-root.
   (artifact-path run-root :run/completion) → (Path \".../completion.json\")."
  [^java.nio.file.Path run-root artifact-id]
  (.resolve run-root (artifact-file artifact-id)))


;; ── Convenience functions for very common paths ────────────────────────

(defn completion-path
  "Resolve the canonical completion file for a run root.
   (completion-path root) ≡ (artifact-path root :run/completion)."
  [^java.nio.file.Path run-root]
  (artifact-path run-root :run/completion))

(defn package-index-path
  "Resolve the run package index for a run root."
  [^java.nio.file.Path run-root]
  (artifact-path run-root :run/package-index))

(defn registry-path
  "Resolve the artifact registry for a run root."
  [^java.nio.file.Path run-root]
  (artifact-path run-root :artifacts/registry))

(defn registry-validation-path
  "Resolve the artifact registry validation for a run root."
  [^java.nio.file.Path run-root]
  (artifact-path run-root :artifacts/validation))
