(ns resolver-sim.io.paths
  "Convenience path constants and non-semantic path conventions.

   For canonical package artifacts (verifier-facing outputs with stable
   schemas), consumers should prefer resolver-sim.io.artifacts and its
   semantic ID functions.

   This namespace provides:
     • ergonomic filename aliases (delegating to resolver-sim.io.artifacts)
     • mutable run-state and lock filenames (non-semantic)
     • file extension strings
     • directory location strings for fixtures, scenarios, results"
  (:require [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.io.artifacts :as arts]
            [resolver-sim.hash.reference :as hash-ref]))

;; ── Registry-backed filename aliases for canonical artifacts ──────────
;; Resolved from config/evidence.json at runtime with code-level fallback.

(defn- registry-file [id]
  (or (evcfg/artifact-file id) (arts/artifact-file id)))

(def completion           (registry-file :run/completion))
(def artifacts-registry   (registry-file :artifacts/registry))
(def artifacts-validation (registry-file :artifacts/validation))
(def run-package-index    (registry-file :run/package-index))
(def sensitivity-report   (registry-file :sensitivity/report))

;; ── Non-semantic path conventions ──────────────────────────────────────
;; Mutable run state, locks, extensions, directories, test resources.
;; These have no verifier-facing schema or package-contract semantics.

(def run-state     ".run-state")
(def run-lock      ".run.lock")

(def edn-ext   ".edn")
(def json-ext  ".json")
(def clj-ext   ".clj")

(def fixtures-dir   hash-ref/fixtures-dir)
(def traces-dir     hash-ref/traces-dir)
(def golden-dir     hash-ref/golden-dir)
(def scenarios-edn-dir hash-ref/scenarios-edn-dir)
(def artf-dir       hash-ref/test-artifacts-dir)
(def runs-root      hash-ref/results-runs-dir)
