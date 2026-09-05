(ns resolver-sim.benchmark.fixtures.artifact-contracts
  "Reusable V2 game-theoretic-validation artifact fixtures for contract,
   migration, and serialization tests."
  (:require [resolver-sim.hash.canonical :as hc]))

(def ^:private artifact-version "game-theoretic-validation.artifact.v2")

;; ---------------------------------------------------------------------------
;; Valid artifact fixtures
;; ---------------------------------------------------------------------------

(def valid-diagnostic-only-artifact
  "Valid V2 artifact with only diagnostic observations (no declared strategic properties)."
  {:artifact/kind :game-theoretic-validation
   :artifact/version artifact-version
   :claim/id :claim/pro-rata-shortfall-conservation
   :claim/title "Pro-rata shortfall conservation"
   :claim/description "Test claim"
   :claim/interpretation "Pass means matched scenario evidence satisfied the declared non-strategic checks."
   :claim/validation-classes [:validation.class/algebraic-integrity]
   :benchmark/id :benchmark/test
   :benchmark/scenario-suite :suite/test
   :benchmark/manifest-path "test.edn"
   :matched-scenarios []
   :level-verdicts [{:mechanism-level :allocation/partial-fill
                     :verdict :pass
                     :check-results []}]
   :coverage-gaps []
   :strategic-property-results [{:property :strategy/split-invariance
                                 :status :pass
                                 :reason nil
                                 :property-role :diagnostic-observation
                                 :evidence/kind :bounded-deviation-search
                                 :evaluation/status :no-counterexample-found
                                 :claim/status :unestablished}]
   :strategic-declared-property-results []
   :strategic-model {:mechanism :yield/partial-fill
                     :payoff-model :allocated-amount-only
                     :scope-kind :bounded-enumeration}
   :strategic-epistemic-scope {:scope/kind :bounded-exhaustive
                               :scope/universal-claim? false
                               :scope/falsification? true
                               :scope/limitations [:bounded-domain]}
   :strategic-deviation-scope nil
   :gates {:integrity {:gate :integrity :verdict :pass}
           :economic-model {:gate :economic-model :verdict :pass}
           :strategic {:gate :strategic :verdict :verified}}
   :gates-summary :all-pass
   :summary {:matched-scenario-count 0
             :passed-level-count 1
             :failed-level-count 0
             :uncovered-level-count 0
             :strategic-property-count 0
             :strategic-property-violations 0
             :gates-blocked? false
             :valid? true}})

(def valid-declared-property-artifact
  "Valid V2 artifact with one declared strategic property."
  {:artifact/kind :game-theoretic-validation
   :artifact/version artifact-version
   :claim/id :claim/pro-rata-shortfall-conservation
   :claim/title "Pro-rata shortfall conservation"
   :claim/description "Test claim"
   :claim/interpretation "Pass means the explicitly declared strategic properties were not falsified."
   :claim/validation-classes [:validation.class/algebraic-integrity
                              :validation.class/deviation-resistance]
   :benchmark/id :benchmark/test
   :benchmark/scenario-suite :suite/test
   :benchmark/manifest-path "test.edn"
   :matched-scenarios []
   :level-verdicts [{:mechanism-level :allocation/partial-fill
                     :verdict :pass
                     :check-results []}]
   :coverage-gaps []
   :strategic-property-results [{:property :strategy/split-invariance
                                 :status :pass
                                 :reason nil
                                 :property-role :declared-property
                                 :diagnostic-transform {:id :split
                                                        :role :diagnostic-transform
                                                        :semantic-purpose :bounded-counterexample-search}
                                 :evidence/kind :bounded-deviation-search
                                 :evaluation/status :no-counterexample-found
                                 :claim/status :bounded-empirical-evidence}]
   :strategic-declared-property-results [{:property :strategy/split-invariance
                                          :status :pass
                                          :reason nil
                                          :property-role :declared-property
                                          :diagnostic-transform {:id :split
                                                                 :role :diagnostic-transform
                                                                 :semantic-purpose :bounded-counterexample-search}
                                          :evidence/kind :bounded-deviation-search
                                          :evaluation/status :no-counterexample-found
                                          :claim/status :bounded-empirical-evidence}]
   :strategic-model {:mechanism :yield/partial-fill
                     :allocation-mode :pro-rata
                     :payoff-model :allocated-amount-only
                     :scope-kind :bounded-enumeration
                     :actions [:honest-request :split :merge :permute :sybil-split :inflate-request]
                     :rounding-policies [{:mode :pro-rata :rounding-policy :floor}]
                     :claims-unmodeled [:fees :timing]}
   :strategic-epistemic-scope {:scope/kind :bounded-exhaustive
                               :scope/universal-claim? false
                               :scope/falsification? true
                               :scope/coverage :validation-scope
                               :scope/limitations [:bounded-domain :bounded-policy-set
                                                   :no-equilibrium-proof]}
   :strategic-deviation-scope {:deviation-set-ids #{:partial-fill/claimant-monotonicity}
                               :contract-ids [:partial-fill/claimant-monotonicity]
                               :deviations [:inflate]
                               :declared-property-ids [:strategy/split-invariance]}
   :gates {:integrity {:gate :integrity :verdict :pass}
           :economic-model {:gate :economic-model :verdict :pass}
           :strategic {:gate :strategic :verdict :verified}}
   :gates-summary :all-pass
   :summary {:matched-scenario-count 0
             :passed-level-count 1
             :failed-level-count 0
             :uncovered-level-count 0
             :strategic-property-count 1
             :strategic-property-violations 0
             :gates-blocked? false
             :valid? true}})

;; ---------------------------------------------------------------------------
;; Invalid artifact fixtures
;; ---------------------------------------------------------------------------

(def invalid-unknown-top-level-key
  "Artifact with an unknown top-level key."
  (assoc valid-diagnostic-only-artifact
         :unknown/surprise-field "should not be here"))

(def invalid-universal-claim
  "Artifact with :scope/universal-claim? = true."
  (assoc-in valid-diagnostic-only-artifact
            [:strategic-epistemic-scope :scope/universal-claim?] true))

(def invalid-non-declared-in-gate-projection
  "Artifact with a diagnostic-observation in strategic-declared-property-results."
  (assoc valid-diagnostic-only-artifact
         :strategic-declared-property-results
         [{:property :allocation/exact-merge-invariance
           :status :fail
           :reason :property-violated
           :property-role :diagnostic-observation}]))

(def invalid-missing-epistemic-scope
  "Artifact missing :strategic-epistemic-scope."
  (dissoc valid-diagnostic-only-artifact :strategic-epistemic-scope))

(def invalid-inconsistent-declared-property-scope
  "Artifact where strategic-declared-property-results contains entries
   not in strategic-deviation-scope :declared-property-ids."
  (assoc valid-declared-property-artifact
         :strategic-declared-property-results
         [{:property :strategy/sybil-invariance
           :status :pass
           :reason nil
           :property-role :declared-property
           :evidence/kind :bounded-deviation-search
           :evaluation/status :no-counterexample-found
           :claim/status :bounded-empirical-evidence}]
         :strategic-deviation-scope
         {:deviation-set-ids #{:partial-fill/claimant-monotonicity}
          :contract-ids [:partial-fill/claimant-monotonicity]
          :deviations [:inflate]
          :declared-property-ids [:strategy/split-invariance]}))

(def invalid-v1-version
  "Artifact with V1 version string."
  (assoc valid-diagnostic-only-artifact
         :artifact/version "game-theoretic-validation.artifact.v1"))

(def invalid-missing-required-key
  "Artifact missing :claim/id."
  (dissoc valid-diagnostic-only-artifact :claim/id))
