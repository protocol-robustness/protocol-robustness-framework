(ns resolver-sim.research.research-contract-api-test
  "Research-contract package API conformance: each public entrypoint delegates
   to the existing authoritative V1 implementation."
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.benchmark.incentive-model :as model]
            [resolver-sim.benchmark.research-analysis :as analysis]
            [resolver-sim.benchmark.research-claim-acceptance :as acceptance]
            [resolver-sim.benchmark.research-claim-lifecycle :as lifecycle]
            [resolver-sim.hash.reference :as canonical-reference]
            [resolver-sim.research.api :as research]))

(deftest research-output-and-claim-api-delegates-to-authoritative-v1
  (is (identical? analysis/verify-output research/verify-analysis-output))
  (is (identical? analysis/verification-valid? research/analysis-verification-valid?))
  (is (identical? analysis/derive-claim research/derive-analytical-claim))
  (is (identical? analysis/validate-claim research/validate-analytical-claim))
  (is (identical? acceptance/build-policy research/build-claim-acceptance-policy))
  (is (identical? acceptance/validate-policy research/validate-claim-acceptance-policy))
  (is (identical? acceptance/build-acceptance research/build-claim-acceptance))
  (is (identical? acceptance/validate-acceptance research/validate-claim-acceptance))
  (is (identical? lifecycle/build-transition research/build-claim-lifecycle-transition))
  (is (identical? lifecycle/validate-transition research/validate-claim-lifecycle-transition))
  (is (identical? lifecycle/reconstruct-research-claim-lifecycle
                  research/reconstruct-claim-lifecycle))
  (is (identical? lifecycle/validate-persisted-state-authoritative
                  research/validate-persisted-claim-lifecycle))
  (is (identical? model/validate-model research/validate-incentive-model))
  (is (identical? model/model-valid? research/incentive-model-valid?))
  (is (identical? canonical-reference/valid-sha256-ref?
                  research/valid-canonical-root-reference?)))
