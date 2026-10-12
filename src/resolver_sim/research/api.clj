(ns resolver-sim.research.api
  "Stable in-tree PRF research contract surface for downstream applications.

   These are direct aliases to the authoritative V1 builders and validators;
   this namespace owns no schemas, hashing rules, or lifecycle semantics. It is
   a package boundary candidate, not yet an independently released package."
  (:require [resolver-sim.benchmark.incentive-model :as incentive-model]
            [resolver-sim.benchmark.research-analysis :as analysis]
            [resolver-sim.benchmark.research-claim-acceptance :as acceptance]
            [resolver-sim.benchmark.research-claim-lifecycle :as lifecycle]
            [resolver-sim.hash.reference :as canonical-reference]))

;; Research-result verification and analytical-claim derivation.
(def verify-analysis-output analysis/verify-output)
(def analysis-verification-valid? analysis/verification-valid?)
(def derive-analytical-claim analysis/derive-claim)
(def validate-analytical-claim analysis/validate-claim)

;; Authoritative acceptance and lifecycle operations.
(def build-claim-acceptance-policy acceptance/build-policy)
(def validate-claim-acceptance-policy acceptance/validate-policy)
(def build-claim-acceptance acceptance/build-acceptance)
(def validate-claim-acceptance acceptance/validate-acceptance)
(def build-claim-lifecycle-transition lifecycle/build-transition)
(def validate-claim-lifecycle-transition lifecycle/validate-transition)
(def reconstruct-claim-lifecycle lifecycle/reconstruct-research-claim-lifecycle)
(def validate-persisted-claim-lifecycle lifecycle/validate-persisted-state-authoritative)

;; Canonical input contract used by analysis verification.
(def validate-incentive-model incentive-model/validate-model)
(def incentive-model-valid? incentive-model/model-valid?)

;; Compatibility bridge until C2 publishes the canonical reference API.
(def valid-canonical-root-reference? canonical-reference/valid-sha256-ref?)
