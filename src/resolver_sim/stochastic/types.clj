(ns resolver-sim.stochastic.types
  "Parameter schemas and types for the Sew Protocol dispute resolution simulation."
  (:require [resolver-sim.stochastic.detection :as detection]
            [resolver-sim.stochastic.economics :as econ]
            [malli.core :as m]
            [malli.error :as me]))

;; Schedule configuration schema
(def schedule-schema
  [:or
   [:map [:type [:= :constant]] [:value number?]]
   [:map [:type [:= :steps]] [:values [:vector [:map [:time number?] [:value number?]]]]]])

;; Scenario configuration schema using Malli
(def scenario-schema
  [:map {:closed true}
   [:description string?]
   [:scenario-id string?]
   [:rng-seed integer?]
   [:escrow-distribution map?]
   [:strategy-mix [:map
                   [:honest [:>= 0]]
                   [:lazy [:>= 0]]
                   [:malicious [:>= 0]]
                   [:collusive [:>= 0]]]]
   [:resolver-fee-bps [:and number? [:>= 0] [:<= 10000]]]
   [:appeal-bond-bps [:and number? [:>= 0] [:<= 10000]]]
   [:resolver-bond-bps [:and number? [:>= 0] [:<= 10000]]]
   [:slash-multiplier [:>= 0]]
   [:appeal-probability-if-correct [:and number? [:>= 0] [:<= 1]]]
   [:appeal-probability-if-wrong [:and number? [:>= 0] [:<= 1]]]
   [:slashing-detection-probability [:and number? [:>= 0] [:<= 1]]]
   [:fraud-detection-probability [:and number? [:>= 0] [:<= 1]]]
   [:fraud-slash-bps [:and number? [:>= 0] [:<= 10000]]]
   [:reversal-detection-probability [:and number? [:>= 0] [:<= 1]]]
   [:reversal-slash-bps [:and number? [:>= 0] [:<= 10000]]]
   [:timeout-slash-bps [:and number? [:>= 0] [:<= 10000]]]
   [:l1-honest-detection-probability [:and number? [:>= 0] [:<= 1]]]
   [:l1-lazy-detection-probability [:and number? [:>= 0] [:<= 1]]]
   [:l1-collusive-detection-probability [:and number? [:>= 0] [:<= 1]]]
   [:l1-unknown-strategy-detection-probability [:and number? [:>= 0] [:<= 1]]]
   [:oracle-fixture {:optional true} map?]
   [:oracle-mode {:optional true} [:enum :stochastic :static-no-slash :static-always-detect :fixed-roll-sequence :fixed-or]]
   [:fixed-or {:optional true} [:or vector? map?]]
   [:oracle-roll-sequence {:optional true} vector?]
   [:oracle-roll-on-exhaustion {:optional true} [:enum :throw :repeat-last :cycle]]
   [:oracle-roll-trace-enabled? {:optional true} boolean?]
   [:evidence-quality? {:optional true} boolean?]
   [:fraud-model {:optional true} [:enum :single-stage-ev :sequential-escalation :strict-all-tiers]]
   [:escalation-assumption-band {:optional true} [:enum :conservative :base :optimistic]]
   [:p-appeal-wrong {:optional true} [:and number? [:>= 0] [:<= 1]]]
   [:p-l1-reversal {:optional true} [:and number? [:>= 0] [:<= 1]]]
   [:p-l2-escalation {:optional true} [:and number? [:>= 0] [:<= 1]]]
   [:p-l2-reversal {:optional true} [:and number? [:>= 0] [:<= 1]]]
   [:n-trials {:optional true} [:and integer? [:> 0]]]
   [:n-seeds {:optional true} [:and integer? [:> 0]]]
   [:min-trials {:optional true} [:and integer? [:> 0]]]
   [:parallelism {:optional true} [:or keyword? integer?]]
   [:new-evidence-probability {:optional true} [:and number? [:>= 0] [:<= 1]]]
   [:l2-detection-prob {:optional true} [:and number? [:>= 0] [:<= 1]]]
   [:detection-type {:optional true} [:enum :fraud :timeout :reversal]]
   [:timeout-detection-probability {:optional true} [:and number? [:>= 0] [:<= 1]]]
   [:yield-config {:optional true} map?]
   [:save-samples? {:optional true} boolean?]
   [:save-sweep? {:optional true} boolean?]
   [:has-kleros? {:optional true} boolean?]
   [:escrow-size {:optional true} [:>= 0]]
   [:fraud-success-rate {:optional true} [:and number? [:>= 0] [:<= 1]]]
   [:l2-slash-bps {:optional true} [:and number? [:>= 0] [:<= 10000]]]

   ;; Optional keys
   [:panel-size {:optional true} [:>= 0]]
   [:majority-ratio {:optional true} [:and number? [:>= 0] [:<= 1]]]
   [:appeal-threshold {:optional true} [:and number? [:>= 0] [:<= 1]]]
   [:sweep-params {:optional true} map?]
   [:attacker-extra-capital-multiplier {:optional true} [:>= 0]]
   [:slashing-detection-delay-weeks {:optional true} [:>= 0]]
   [:force-strategy {:optional true} [:maybe keyword?]]
   [:allow-slashing? {:optional true} boolean?]
   [:unstaking-delay-days {:optional true} [:>= 0]]
   [:freeze-on-detection? {:optional true} boolean?]
   [:freeze-duration-days {:optional true} [:>= 0]]
   [:appeal-window-days {:optional true} [:>= 0]]
   [:escalation-assumptions {:optional true} map?]
   [:resolver-stake-wei {:optional true} [:>= 0]]
   [:ring-spec {:optional true} map?]
   [:senior-resolver-skill {:optional true} [:>= 0]]
   [:author {:optional true} string?]
   [:author-id {:optional true} string?]
   [:circuit-breaker-threshold-bps {:optional true} [:>= 0]]
   [:circuit-breaker-cooldown {:optional true} [:>= 0]]
   [:max-slash-per-offense-bps {:optional true} [:>= 0]]
   [:slash-epoch-cap-bps {:optional true} [:>= 0]]
   [:bond-mix-min-stable-bps {:optional true} [:>= 0]]
   [:escalation-bond-bps {:optional true} [:>= 0]]
   [:minimum-challenge-bond {:optional true} [:>= 0]]
   [:max-dispute-level {:optional true} [:>= 0]]

    ;; Sweep / analysis configuration keys (not protocol params but present in param files)
   [:scenario-name {:optional true} string?]
   [:difficulty-distribution {:optional true} map?]
   [:correlation-parameter {:optional true} number?]
   [:effort-budget-per-epoch {:optional true} number?]
   [:load-level {:optional true} keyword?]
   [:attacker-budget-fraction {:optional true} number?]
   [:seed {:optional true} integer?]
   [:num-epochs {:optional true} integer?]
   [:num-trials-per-epoch {:optional true} integer?]
   [:n-epochs {:optional true} integer?]
   [:n-trials-per-epoch {:optional true} integer?]
   [:n-resolvers {:optional true} integer?]
   [:max-op-win-rate-threshold {:optional true} number?]
   [:detection-decay-rate {:optional true} number?]
   [:governance-capacity {:optional true} integer?]
   [:disputes-per-epoch {:optional true} integer?]
   [:floor-threshold {:optional true} number?]
   [:junior-counts {:optional true} vector?]
   [:detection-probs {:optional true} vector?]
   [:senior-bond-sizes {:optional true} vector?]
   [:junior-bond {:optional true} number?]
   [:deterrence-threshold {:optional true} number?]
   [:pass-fraction {:optional true} number?]
   [:spike-ratio-threshold {:optional true} number?]])

;; Trial outcome record
(defrecord TrialOutcome
           [resolver-strategy
            dispute-correct?
            appeal-triggered?
            slashed?
            honest-profit
            malice-profit
            fee-earned
            slashing-loss])

;; Batch aggregation record
(defrecord BatchSummary
           [n-trials
            mean-honest
            mean-malice
            std-honest
            std-malice
            appeal-rate
            slash-rate
            honest-wins-fraction
            collusion-success-rate])

;; Run metadata record
(defrecord RunMetadata
           [scenario-id
            git-commit
            git-dirty?
            jvm-version
            timestamp
            seed
            params])

;; Defaults
;; Slash-bps values reference detection/default-slash-bps (canonical fallback).
;; Escalation values reference econ/default-escalation-assumptions :base band.
(def default-params
  (let [base-band (:base econ/default-escalation-assumptions)]
    {:resolver-bond-bps 1000         ; DR3: 10% bond (DR1=0, DR2=500)
     :panel-size 3
     :majority-ratio (/ 2.0 3.0)
     :appeal-threshold 0.6
     :fraud-detection-probability 0.0              ; Phase I: fraud detection disabled by default
     :fraud-slash-bps (:fraud detection/default-slash-bps)         ; Phase I: fraud slashing disabled
     :reversal-detection-probability 1.0          ; Keep historical deterministic reversal behavior
     :reversal-slash-bps (:reversal detection/default-slash-bps)   ; Phase I: reversal slashing disabled
     :timeout-slash-bps (:timeout detection/default-slash-bps)     ; Phase I: timeout penalty (2% = 200 bps)
     :l1-honest-detection-probability 0.01        ; L1 false-positive catch rate for honest resolvers
     :l1-lazy-detection-probability 0.02          ; L1 catch rate for lazy resolvers
     :l1-collusive-detection-probability 0.05     ; L1 catch rate for collusive resolvers
     :l1-unknown-strategy-detection-probability 0.0 ; L1 catch rate for unrecognized strategies
     :fraud-model :single-stage-ev                ; legacy default for backward compatibility
     :escalation-assumption-band :base            ; for :sequential-escalation mode
     :p-appeal-wrong (:p-appeal-wrong base-band)
     :p-l1-reversal (:p-l1-reversal base-band)
     :p-l2-escalation (:p-l2-escalation base-band)
     :p-l2-reversal (:p-l2-reversal base-band)
     :n-trials 1000
     :n-seeds 1
     :parallelism :auto
     :slashing-detection-delay-weeks 0      ; Phase G: delay before slashing hits
     :force-strategy nil                    ; Phase G: override strategy-mix (for control baselines)
     :allow-slashing? true                  ; Phase G: if false, never slash (control baseline)
     :unstaking-delay-days 14               ; Phase H: days to unstake (RESOLVER_UNBOND_DELAY)
     :freeze-on-detection? true             ; Phase H: immediate freeze when detected?
     :freeze-duration-days 3                ; Phase H: 72 hours freeze duration
     :appeal-window-days 7                  ; Phase H: days before slash executes
     :detection-type :fraud                 ; Phase H: :fraud (explicit), :timeout (automatic), :reversal (on appeal)
     :timeout-detection-probability 0.0     ; Phase H: detection on appeal (separate from fraud)
     :oracle-fixture {:mode :stochastic}    ; Stochastic default oracle behavior
     :oracle-roll-trace-enabled? false
     :evidence-quality? false
     :circuit-breaker-threshold-bps 3000
     :circuit-breaker-cooldown 3600
     :max-slash-per-offense-bps 5000
     :slash-epoch-cap-bps 2000
     :bond-mix-min-stable-bps 8000
     :escalation-bond-bps 1000
     :minimum-challenge-bond 100
     :max-dispute-level 2
     :resolver-fee-bps 150
     :appeal-bond-bps 700
     :slash-multiplier 2.5
     :appeal-probability-if-correct 0.05
     :appeal-probability-if-wrong 0.40
     :escrow-size 10000
     :slashing-detection-probability 0.10
     :min-trials 10}))

(defn validate-scenario
  "Validate scenario params against schema. Throws if invalid."
  [scenario]
  (let [validator (m/validator scenario-schema)
        explanation (m/explain scenario-schema scenario)]
    (when-not (validator scenario)
      (throw (ex-info (format "Invalid scenario parameters: %s" (me/humanize explanation))
                      {:explanation (me/humanize explanation)})))

    ;; Mitigation for repeat-last
    (when (and (= (:oracle-roll-on-exhaustion scenario) :repeat-last)
               (:evidence-quality? scenario))
      (throw (ex-info "Invalid scenario: :repeat-last is incompatible with :evidence-quality? true. Use :throw instead."
                      {:scenario-id (:scenario-id scenario)})))

    (detection/validate-oracle-params! scenario)))
