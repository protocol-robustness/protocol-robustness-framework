(ns resolver-sim.protocols.sew.analysis-extension
  "Optional Sew trace, property-validation, and reporting integrations."
  (:require [resolver-sim.protocols.registry :as registry]
            [resolver-sim.protocols.sew.advisory :as advisory]
            [resolver-sim.protocols.sew.equilibrium :as equilibrium]
            [resolver-sim.protocols.sew.projection :as projection]
            [resolver-sim.protocols.sew.validator-registry :as validator-registry]
            [resolver-sim.yield.evidence :as yield-evidence]))

(defn register-capabilities! []
  (registry/register-capability!
   "sew-v1" :analysis/advisory
   {:suggest-actions advisory/suggest-actions
    :session-signals advisory/session-signals
    :evaluate-payoff advisory/evaluate-payoff
    :evaluate-attack-objective advisory/evaluate-attack-objective})
  (registry/register-capability! "sew-v1" :analysis/trace-projection projection/trace-end-projection)
  (registry/register-capability! "sew-v1" :analysis/yield-evidence yield-evidence/get-evidence)
  (registry/register-capability! "sew-v1" :analysis/funds-ledger-view projection/funds-ledger-view)
  (registry/register-capability! "sew-v1" :analysis/accounting-projection projection/accounting-projection-v1)
  (registry/register-capability! "sew-v1" :analysis/mechanism-property-validators
                                 equilibrium/mechanism-property-validators)
  (registry/register-capability! "sew-v1" :analysis/equilibrium-concept-validators
                                 equilibrium/equilibrium-concept-validators)
  (registry/register-capability! "sew-v1" :analysis/validator-descriptors
                                 validator-registry/validator-descriptors))

(registry/register-capability-provider! "sew-v1" :analysis register-capabilities!)
(register-capabilities!)

(def installed? true)
