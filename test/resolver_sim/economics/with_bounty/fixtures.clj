(ns resolver-sim.economics.with-bounty.fixtures
  "Stage A sealed fixture package for the with-bounty reference (ADR-0006 D7).

   Data-only manifest: entrypoints are symbols implemented in
   resolver-sim.economics.with-bounty.fixture. Lives under the test boundary
   and is never shipped as a built-in. Sealed roots are committed so a sealed
   frozen resolution can be demonstrated (dev and sealed modes)."
  (:require [resolver-sim.extensions.registry :as ext-reg]))

(def sealed-roots
  {:extension/source {:type :git
                      :repository "fixture"
                      :commit "stage-a"
                      :source-root "sha256:with-bounty-source"}
   :extension/artifact {:type :jar :artifact-root "sha256:with-bounty-artifact"}
   :extension/dependencies {:dependency-resolution-root "sha256:with-bounty-deps"}
   :extension/runtime {:prf/version "0.0.0-snapshot"
                       :clojure/version "1.12"
                       :jvm/profile :jvm-21}})

(def eligibility-cap
  {:capability/kind :economics/eligibility
   :capability/id :fixture/review-bounty-eligible
   :capability/version 1
   :capability/contract-version 1
   :entrypoint 'resolver-sim.economics.with-bounty.fixture/eligible?
   :input-schema :prf/eligibility-context.v1
   :output-schema :prf/eligibility-result.v1})

(def amount-cap
  {:capability/kind :economics/award-amount
   :capability/id :fixture/review-bounty-amount
   :capability/version 1
   :capability/contract-version 1
   :entrypoint 'resolver-sim.economics.with-bounty.fixture/calculate
   :input-schema :prf/with-bounty-amount-context.v1
   :output-schema :prf/calculation-result.v1})

(def review-bounties-pack
  "Sealed fixture package providing the eligibility and amount capabilities
   the with-bounty reference policy resolves."
  (merge {:extension/id :fixture/with-bounty-review
          :extension/version "0.1.0"
          :extension/api-version 1
          :extension/manifest-version 1
          :extension/capabilities [eligibility-cap amount-cap]
          :extension/license "MIT"
          :extension/maintainers ["prf-stage-a"]
          :extension/support-policy :unsupported
          :extension/funding-status :unfunded}
         sealed-roots))

(defn extension-map
  "Pure extension-map seeded with the Stage A fixture package."
  []
  (ext-reg/register-package (ext-reg/empty-extension-map) review-bounties-pack))
