(ns resolver-sim.research.research-api-boundary-test
  "The application-facing API is a direct view of existing authorities."
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.benchmark.incentive-model :as model]
            [resolver-sim.benchmark.research-analysis :as analysis]
            [resolver-sim.benchmark.research-claim-acceptance :as acceptance]
            [resolver-sim.benchmark.research-claim-lifecycle :as lifecycle]
            [resolver-sim.benchmark.researcher-reward :as reward]
            [resolver-sim.hash.reference :as canonical-reference]
            [resolver-sim.incentives.api :as incentives]
            [resolver-sim.research.api :as research]))

(deftest research-api-delegates-to-authoritative-v1-contracts
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

(deftest incentive-api-delegates-to-authoritative-reward-contracts
  (is (identical? reward/build-policy incentives/build-reward-policy))
  (is (identical? reward/evaluate incentives/evaluate-reward-eligibility))
  (is (identical? reward/valid-eligibility? incentives/valid-reward-eligibility?))
  (is (identical? reward/build-proposal incentives/build-reward-proposal))
  (is (identical? reward/valid-proposal? incentives/valid-reward-proposal?))
  (is (identical? reward/build-obligation incentives/build-reward-obligation))
  (is (identical? reward/valid-obligation? incentives/valid-reward-obligation?))
  (is (identical? reward/verify-adversarial-finding-lineage
                  incentives/verify-adversarial-finding-lineage)))
