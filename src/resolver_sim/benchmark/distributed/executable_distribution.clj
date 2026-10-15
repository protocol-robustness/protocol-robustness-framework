(ns resolver-sim.benchmark.distributed.executable-distribution
  "Compatibility facade for the shared execution distribution contract."
  (:require [resolver-sim.execution.executable-distribution :as contract]))

;; Preserve the established benchmark API while keeping implementation and
;; authoritative verification in the shared execution-contract namespace.
(def schema contract/schema)
(def semantic-claimant-option-fields contract/semantic-claimant-option-fields)
(def semantic-claimant-options-root contract/semantic-claimant-options-root)
(def normalized-semantic-claimant-options contract/normalized-semantic-claimant-options)
(def distribution-body contract/distribution-body)
(def distribution-root contract/distribution-root)
(def build-distribution contract/build-distribution)
(def verify-distribution contract/verify-distribution)
(def expected-shape contract/expected-shape)
(def observed-shape contract/observed-shape)
(def distribution-preflight contract/distribution-preflight)
(def verify-expected-observed contract/verify-expected-observed)
