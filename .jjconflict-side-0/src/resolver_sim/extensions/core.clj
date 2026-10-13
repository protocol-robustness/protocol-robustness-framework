(ns resolver-sim.extensions.core
  "The virtual core package :prf/core-economics.

   Built-in economics methods are declared as extension-backed capabilities of
   a virtual package so that built-in and external execution evidence have the
   same shape, a PRF version change naturally changes capability identity, and
   external verification can determine precisely which built-in implementation
   ran.

   Phase 1 records entrypoints as symbols only; they are resolved to Vars in a
   later phase (dispatch refactor). The entrypoints below reference the public
   capability adapter functions in resolver-sim.economics.slash-distribution
   that implement the uniform invocation contract for each built-in method."
  (:require [resolver-sim.extensions.manifest :as em]))

(def core-package-id
  :prf/core-economics)

(def core-package-version
  "Tracked with PRF releases; snapshot placeholder until a canonical version
   source exists."
  "0.0.0-snapshot")

(def core-composition-contract
  "Typed sequential composition contract shared by the built-in economics
   capabilities. The pipeline value is an :amount that each node consumes and
   reduces; nodes emit effects and are deterministic, fail-closed, and
   non-terminal in their default role."
  {:composition-contract/version 1
   :composition/input {:schema-ref :prf/award-amount-context.v1
                       :semantic-type :amount
                       :cardinality :one}
   :composition/output {:schema-ref :prf/calculation-result.v1
                        :semantic-type :amount
                        :cardinality :one}
   :composition/roles #{:step}
   :composition/modes #{:sequential}
   :composition/effects {:emits #{}
                         :merge-strategy :accumulate
                         :exclusive-effects #{}}
   :composition/control {:terminal? false
                         :may-short-circuit? false
                         :failure-mode :abort}
   :composition/determinism {:required? true
                             :context-reads #{}
                             :external-reads #{}}
   :composition/adapters {:accepted #{}
                          :implicit? false}
   :composition/verification {:intermediate-output-committed? true
                              :evidence-contract-ref :prf/calculation-result.v1}})

(defn- core-capability
  [kind id entrypoint input-schema output-schema]
  {:capability/kind kind
   :capability/id id
   :capability/version 1
   :capability/contract-version 1
   :entrypoint entrypoint
   :input-schema input-schema
   :output-schema output-schema
   :composition-contract core-composition-contract})

(def core-capabilities
  "Built-in economics capabilities (data only; dispatch wires these to the
   public adapter functions in resolver-sim.economics.slash-distribution).
   Each kind declares its explicit input and result schema contract."
  [(core-capability :economics/award-amount :prf/rate-of-gross
                    'resolver-sim.economics.slash-distribution/rate-of-gross-award-amount
                    :prf/award-amount-context.v1 :prf/calculation-result.v1)
   (core-capability :economics/award-amount :prf/resolved-amount
                    'resolver-sim.economics.slash-distribution/resolved-award-amount
                    :prf/award-amount-context.v1 :prf/calculation-result.v1)
   (core-capability :economics/allocation :prf/weighted
                    'resolver-sim.economics.slash-distribution/weighted-base-allocation
                    :prf/allocation-context.v1 :prf/allocation-result.v1)
   (core-capability :economics/funding :prf/weighted-deduction
                    'resolver-sim.economics.slash-distribution/weighted-funding-deduction
                    :prf/funding-context.v1 :prf/funding-result.v1)])

(def core-economics-package
  "Manifest of the virtual core economics package. Its package root tracks the
   PRF build; the logical capability identities remain stable across releases."
  {:extension/id core-package-id
   :extension/version core-package-version
   :extension/api-version 1
   :extension/manifest-version 1
   :extension/capabilities core-capabilities
   :extension/license "Apache-2.0"
   :extension/maintainers ["PRF core"]
   :extension/support-policy :core
   :extension/funding-status :core
   :extension/status {:lifecycle :active
                      :distribution :core
                      :conformance :conformant
                      :reproduction :artifact-replayable
                      :verification :replayed
                      :maintenance :supported
                      :adoption :multi-adapter}})

(def core-capability-keys
  "Set of [capability-kind capability-id] keys provided by the core package."
  (set (map em/capability-key core-capabilities)))
