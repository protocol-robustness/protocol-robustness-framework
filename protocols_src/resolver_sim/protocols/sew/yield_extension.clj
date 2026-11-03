(ns resolver-sim.protocols.sew.yield-extension
  "Sew settlement and invariant adapters for the shared yield runtime."
  (:require [resolver-sim.protocols.registry :as registry]
            [resolver-sim.protocols.sew.yield.invariants :as sew-invariants]
            [resolver-sim.protocols.sew.yield.policy :as sew-policy]
            [resolver-sim.yield.protocols :as yield-protocols]
            [resolver-sim.yield.module :as yield-module]
            [resolver-sim.yield.risk :as yield-risk]
            [resolver-sim.yield.expectations :as yield-expectations]
            [resolver-sim.yield.ops :as yield-ops]
            [resolver-sim.yield.partial-fill :as yield-partial-fill]
            [resolver-sim.yield.accounting :as yield-accounting]
            [resolver-sim.yield.position :as yield-position]))

(defn register-capabilities! []
  (registry/register-capability!
   "sew-v1" :yield/apply-settlement-policy sew-policy/apply-yield-policy)
  (registry/register-capability!
   "sew-v1" :yield/check-sew-invariants sew-invariants/check-all)
  (registry/register-capability! "sew-v1" :yield/init-world yield-protocols/init-world)
  (registry/register-capability! "sew-v1" :yield/apply-op yield-protocols/apply-op)
  (registry/register-capability! "sew-v1" :yield/resolve-profile yield-protocols/resolve-yield-profile)
  (registry/register-capability! "sew-v1" :yield/resolve-module-id yield-module/resolve-module-id)
  (registry/register-capability! "sew-v1" :yield/apply-market-shock yield-risk/apply-market-shock)
  (registry/register-capability! "sew-v1" :yield/check-expectations yield-expectations/check-expectations)
  (registry/register-capability! "sew-v1" :yield/liquidity-modes yield-accounting/liquidity-modes)
  (registry/register-capability! "sew-v1" :yield/partial-yield-shortfall? yield-accounting/partial-yield-shortfall?)
  (registry/register-capability! "sew-v1" :yield/apply-yield-op yield-ops/apply-yield-op)
  (registry/register-capability! "sew-v1" :yield/position-identity yield-position/position-identity)
  (registry/register-capability! "sew-v1" :yield/filled-total yield-partial-fill/filled-total)
  (registry/register-capability! "sew-v1" :yield/validate-partial-fill-application!
                                 yield-partial-fill/validate-partial-fill-application!)
  (registry/register-capability! "sew-v1" :yield/apply-partial-fill yield-partial-fill/apply-partial-fill))

(registry/register-capability-provider! "sew-v1" :yield register-capabilities!)
(register-capabilities!)

(def installed? true)
