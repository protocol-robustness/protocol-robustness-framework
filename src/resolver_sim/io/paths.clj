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
  (:require [resolver-sim.io.artifacts :as arts]
            [resolver-sim.hash.reference :as hash-ref]))

;; ── Filename aliases for canonical artifacts (semantics in io.artifacts) ──

(def completion           (arts/artifact-file :run/completion))
(def artifacts-registry   (arts/artifact-file :artifacts/registry))
(def artifacts-validation (arts/artifact-file :artifacts/validation))
(def run-package-index    (arts/artifact-file :run/package-index))
(def sensitivity-report   (arts/artifact-file :sensitivity/report))

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
