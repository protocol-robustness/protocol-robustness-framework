(ns resolver-sim.extensions.fixtures
  "Shared Phase-1 fixture extension packages and capabilities.

   These are data manifests (entrypoints are symbols, never resolved in
   Phase 1). They exercise sealed/unsealed classifications, transitive
   dependencies, provider ambiguity, and version requirements."
  (:require [resolver-sim.extensions.registry :as reg]))

;; ── sealed arithmetic provider ────────────────────────────────────────────

(def scaled-share-cap
  {:capability/kind :arithmetic/profile
   :capability/id :prf/scaled-share-v1
   :capability/version 1
   :capability/contract-version 1
   :entrypoint 'fixture.scaled-share/calculate
   :input-schema :prf/scaled-share-input.v1
   :output-schema :prf/calculation-result.v1})

(def sealed-roots
  {:extension/source {:type :git
                      :repository "fixture"
                      :commit "abc123"
                      :source-root "sha256:fixture-source"}
   :extension/artifact {:type :jar :artifact-root "sha256:fixture-artifact"}
   :extension/dependencies {:dependency-resolution-root "sha256:fixture-deps"}
   :extension/runtime {:prf/version "0.0.0-snapshot"
                       :clojure/version "1.12"
                       :jvm/profile :jvm-21}})

(def scaled-share-pack
  (merge {:extension/id :fixture/scaled-share-pack
          :extension/version "1.0.0"
          :extension/api-version 1
          :extension/manifest-version 1
          :extension/capabilities [scaled-share-cap]
          :extension/license "MIT"
          :extension/maintainers ["fixture-team"]
          :extension/support-policy :unsupported
          :extension/funding-status :unfunded
          :extension/status {:lifecycle :experimental
                             :distribution :external
                             :conformance :unknown
                             :reproduction :artifact-replayable
                             :verification :structural
                             :maintenance :unmaintained
                             :adoption :untested}}
         sealed-roots))

;; A second package providing the *identical* scaled-share capability
;; (identical descriptor) — used to exercise ambiguous-provider and
;; multiple-dependency-roots.
(def alt-scaled-share-pack
  (assoc scaled-share-pack
         :extension/id :fixture/alt-scaled-share-pack
         :extension/version "9.9.9"))

;; ── sealed consumer with a transitive dependency ──────────────────────────

(def rate-with-cap-cap
  {:capability/kind :economics/award-amount
   :capability/id :fixture/rate-with-cap
   :capability/version 1
   :capability/contract-version 1
   :entrypoint 'fixture.rate/calculate
   :input-schema :prf/award-amount-context.v1
   :output-schema :prf/calculation-result.v1
   :verification/contract :prf/award-amount-verification.v1
   :composition-contract {:composition-contract/version 1
                          :composition/input {:schema-ref :prf/award-amount-context.v1
                                              :semantic-type :amount
                                              :cardinality :one}
                          :composition/output {:schema-ref :prf/calculation-result.v1
                                               :semantic-type :amount
                                               :cardinality :one}
                          :composition/roles #{:step}
                          :composition/modes #{:sequential}}
   :declared-dependencies
   [{:capability/kind :arithmetic/profile
     :capability/id :prf/scaled-share-v1
     :requirement {:capability/version 1}}]})

(def rate-with-cap-pack
  (merge {:extension/id :fixture/rate-with-cap-pack
          :extension/version "0.2.0"
          :extension/api-version 1
          :extension/manifest-version 1
          :extension/capabilities [rate-with-cap-cap]
          :extension/license "MIT"}
         sealed-roots))

;; ── unsealed provider (development mode) ──────────────────────────────────

(def unsealed-cap
  {:capability/kind :economics/funding
   :capability/id :fixture/weighted-remainder
   :capability/version 1
   :capability/contract-version 1
   :entrypoint 'fixture.funding/allocate
   :input-schema :prf/award-amount-context.v1
   :output-schema :prf/calculation-result.v1})

(def unsealed-pack
  {:extension/id :fixture/unsealed-pack
   :extension/version "0.0.1"
   :extension/api-version 1
   :extension/manifest-version 1
   :extension/capabilities [unsealed-cap]})

;; ── incompatible version requirement ──────────────────────────────────────

(def rate-with-cap-v2-cap
  (assoc rate-with-cap-cap
         :capability/version 2
         :declared-dependencies
         [{:capability/kind :arithmetic/profile
           :capability/id :prf/scaled-share-v1
           :requirement {:capability/version 2}}]))

(def rate-with-cap-v2-pack
  (assoc rate-with-cap-pack
         :extension/id :fixture/rate-with-cap-v2-pack
         :extension/version "0.3.0"
         :extension/capabilities [rate-with-cap-v2-cap]))

;; ── dependency cycle (a -> b -> a) ────────────────────────────────────────

(def cycle-a-cap
  {:capability/kind :economics/award-amount
   :capability/id :fixture/cycle-a
   :capability/version 1
   :capability/contract-version 1
   :entrypoint 'fixture.cycle-a/calculate
   :declared-dependencies
   [{:capability/kind :economics/funding
     :capability/id :fixture/cycle-b}]})

(def cycle-b-cap
  {:capability/kind :economics/funding
   :capability/id :fixture/cycle-b
   :capability/version 1
   :capability/contract-version 1
   :entrypoint 'fixture.cycle-b/allocate
   :declared-dependencies
   [{:capability/kind :economics/award-amount
     :capability/id :fixture/cycle-a}]})

(def cycle-pack
  {:extension/id :fixture/cycle-pack
   :extension/version "1.0.0"
   :extension/api-version 1
   :extension/manifest-version 1
   :extension/capabilities [cycle-a-cap cycle-b-cap]})

;; ── missing dependency ────────────────────────────────────────────────────

(def missing-dep-cap
  {:capability/kind :economics/allocation
   :capability/id :fixture/priority-order
   :capability/version 1
   :capability/contract-version 1
   :entrypoint 'fixture.priority/allocate
   :declared-dependencies
   [{:capability/kind :arithmetic/profile
     :capability/id :prf/does-not-exist}]})

(def missing-dep-pack
  {:extension/id :fixture/missing-dep-pack
   :extension/version "1.0.0"
   :extension/api-version 1
   :extension/manifest-version 1
   :extension/capabilities [missing-dep-cap]})

;; ── source-pinned provider (source identity, no artifact) ─────────────────

(def source-pinned-pack
  "Only the source sealing root is committed: sealed-classification returns
   :source-pinned. Used to prove a sealed run rejects it."
  {:extension/id :fixture/source-pinned-pack
   :extension/version "0.1.0"
   :extension/api-version 1
   :extension/manifest-version 1
   :extension/capabilities [unsealed-cap]
   :extension/source {:type :git
                      :repository "fixture"
                      :commit "def456"
                      :source-root "sha256:fixture-source-only"}})

;; ── self-loop cycle (a -> a) ──────────────────────────────────────────────

(def self-cycle-cap
  {:capability/kind :economics/award-amount
   :capability/id :fixture/self-cycle
   :capability/version 1
   :capability/contract-version 1
   :entrypoint 'fixture.self-cycle/calculate
   :declared-dependencies
   [{:capability/kind :economics/award-amount
     :capability/id :fixture/self-cycle}]})

(def self-cycle-pack
  {:extension/id :fixture/self-cycle-pack
   :extension/version "1.0.0"
   :extension/api-version 1
   :extension/manifest-version 1
   :extension/capabilities [self-cycle-cap]})

;; ── transitive-only cycle (requested -> b -> c -> b) ──────────────────────

(def transitive-cycle-b-cap
  {:capability/kind :economics/funding
   :capability/id :fixture/tc-b
   :capability/version 1
   :capability/contract-version 1
   :entrypoint 'fixture.tc-b/allocate
   :declared-dependencies
   [{:capability/kind :economics/allocation
     :capability/id :fixture/tc-c}]})

(def transitive-cycle-c-cap
  {:capability/kind :economics/allocation
   :capability/id :fixture/tc-c
   :capability/version 1
   :capability/contract-version 1
   :entrypoint 'fixture.tc-c/allocate
   :declared-dependencies
   [{:capability/kind :economics/funding
     :capability/id :fixture/tc-b}]})

(def transitive-cycle-root-cap
  {:capability/kind :economics/award-amount
   :capability/id :fixture/tc-root
   :capability/version 1
   :capability/contract-version 1
   :entrypoint 'fixture.tc-root/calculate
   :declared-dependencies
   [{:capability/kind :economics/funding
     :capability/id :fixture/tc-b}]})

(def transitive-cycle-pack
  {:extension/id :fixture/transitive-cycle-pack
   :extension/version "1.0.0"
   :extension/api-version 1
   :extension/manifest-version 1
   :extension/capabilities [transitive-cycle-root-cap
                            transitive-cycle-b-cap
                            transitive-cycle-c-cap]})

;; ── diamond dependency (root -> left/right -> shared bottom) ──────────────
;; The shared bottom capability is registered by two packages with identical
;; descriptors (two providers), so every edge into it trips the multiple-
;; roots check; the diamond proves the violation is deduplicated per
;; capability.

(def diamond-bottom-cap
  {:capability/kind :arithmetic/profile
   :capability/id :prf/diamond-bottom-v1
   :capability/version 1
   :capability/contract-version 1
   :entrypoint 'fixture.diamond/bottom
   :input-schema :prf/scaled-share-input.v1
   :output-schema :prf/calculation-result.v1})

(def diamond-bottom-pack
  (merge {:extension/id :fixture/diamond-bottom-pack
          :extension/version "1.0.0"
          :extension/api-version 1
          :extension/manifest-version 1
          :extension/capabilities [diamond-bottom-cap]
          :extension/license "MIT"}
         sealed-roots))

(def alt-diamond-bottom-pack
  (assoc diamond-bottom-pack :extension/id :fixture/alt-diamond-bottom-pack))

(defn- diamond-arm-cap
  [id ep]
  {:capability/kind :economics/award-amount
   :capability/id id
   :capability/version 1
   :capability/contract-version 1
   :entrypoint ep
   :input-schema :prf/scaled-share-input.v1
   :output-schema :prf/calculation-result.v1
   :declared-dependencies
   [{:capability/kind :arithmetic/profile
     :capability/id :prf/diamond-bottom-v1}]})

(def diamond-left-cap
  (diamond-arm-cap :fixture/diamond-left 'fixture.diamond/left))

(def diamond-right-cap
  (diamond-arm-cap :fixture/diamond-right 'fixture.diamond/right))

(def diamond-root-cap
  {:capability/kind :economics/allocation
   :capability/id :fixture/diamond-root
   :capability/version 1
   :capability/contract-version 1
   :entrypoint 'fixture.diamond/root
   :input-schema :prf/allocation-context.v1
   :output-schema :prf/allocation-result.v1
   :declared-dependencies
   [{:capability/kind :economics/award-amount
     :capability/id :fixture/diamond-left}
    {:capability/kind :economics/award-amount
     :capability/id :fixture/diamond-right}]})

(def diamond-top-pack
  {:extension/id :fixture/diamond-top-pack
   :extension/version "1.0.0"
   :extension/api-version 1
   :extension/manifest-version 1
   :extension/capabilities [diamond-left-cap diamond-right-cap diamond-root-cap]})

;; ── optional dependencies ─────────────────────────────────────────────────

(def optional-dep-absent-cap
  "Declares an optional dependency that is NOT in the registry: resolution
   must succeed without it."
  {:capability/kind :economics/allocation
   :capability/id :fixture/opt-dep-absent
   :capability/version 1
   :capability/contract-version 1
   :entrypoint 'fixture.optdep/run
   :input-schema :prf/allocation-context.v1
   :output-schema :prf/allocation-result.v1
   :declared-dependencies
   [{:capability/kind :arithmetic/profile
     :capability/id :prf/does-not-exist
     :optional true}]})

(def optional-dep-absent-pack
  {:extension/id :fixture/optional-dep-absent-pack
   :extension/version "1.0.0"
   :extension/api-version 1
   :extension/manifest-version 1
   :extension/capabilities [optional-dep-absent-cap]})

(def optional-dep-present-consumer-cap
  "Declares an optional dependency that IS present in the registry:
   it must be resolved into the closure."
  {:capability/kind :economics/allocation
   :capability/id :fixture/opt-dep-present
   :capability/version 1
   :capability/contract-version 1
   :entrypoint 'fixture.optdep/run2
   :input-schema :prf/allocation-context.v1
   :output-schema :prf/allocation-result.v1
   :declared-dependencies
   [{:capability/kind :arithmetic/profile
     :capability/id :prf/scaled-share-v1
     :optional true}]})

(def optional-dep-present-pack
  {:extension/id :fixture/optional-dep-present-pack
   :extension/version "1.0.0"
   :extension/api-version 1
   :extension/manifest-version 1
   :extension/capabilities [optional-dep-present-consumer-cap]})

;; ── profile-mismatch requirement ──────────────────────────────────────────

(def profiled-scaled-share-cap
  "The scaled-share capability with an explicit runtime profile."
  (assoc scaled-share-cap :capability/profile :jvm-21))

(def profiled-scaled-share-pack
  (assoc scaled-share-pack
         :extension/id :fixture/profiled-scaled-share-pack
         :extension/capabilities [profiled-scaled-share-cap]))

(def profile-mismatch-consumer-cap
  "Requires scaled-share under a different profile than provided."
  {:capability/kind :economics/award-amount
   :capability/id :fixture/profile-mismatch-consumer
   :capability/version 1
   :capability/contract-version 1
   :entrypoint 'fixture.profilemismatch/consumer
   :input-schema :prf/award-amount-context.v1
   :output-schema :prf/calculation-result.v1
   :declared-dependencies
   [{:capability/kind :arithmetic/profile
     :capability/id :prf/scaled-share-v1
     :requirement {:capability/profile :jvm-17}}]})

(def profile-mismatch-consumer-pack
  {:extension/id :fixture/profile-mismatch-consumer-pack
   :extension/version "1.0.0"
   :extension/api-version 1
   :extension/manifest-version 1
   :extension/capabilities [profile-mismatch-consumer-cap]})

;; ── schema registry used by resolution tests ──────────────────────────────

(def schemas
  {:prf/award-amount-context.v1 "sha256:award-amount-context"
   :prf/calculation-result.v1 "sha256:calculation-result"
   :prf/allocation-context.v1 "sha256:allocation-context"
   :prf/allocation-result.v1 "sha256:allocation-result"
   :prf/funding-context.v1 "sha256:funding-context"
   :prf/funding-result.v1 "sha256:funding-result"
   :prf/scaled-share-input.v1 "sha256:scaled-share-input"
   :prf/award-amount-verification.v1 "sha256:award-amount-verification"})

(defn entry-map
  "Build a pure extension-map from fixture packages."
  [& packages]
  (reduce (fn [emap pkg]
            (reg/register-package emap pkg))
          {}
          packages))
