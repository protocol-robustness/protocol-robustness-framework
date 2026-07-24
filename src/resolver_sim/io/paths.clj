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
  (:require [resolver-sim.io.artifacts :as arts]))

;; ── Filename aliases for canonical artifacts (semantics in io.artifacts) ──

(def completion           (arts/artifact-file :run/completion))
(def artifacts-suffix     (arts/artifact-file :artifacts/registry))
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

(def fixtures-dir   "data/fixtures")
(def traces-dir     "data/fixtures/traces")
(def golden-dir     "data/fixtures/golden")
(def scenarios-edn-dir "scenarios/edn")
(def artf-dir       "results/test-artifacts")
(def runs-root      "results/runs")
