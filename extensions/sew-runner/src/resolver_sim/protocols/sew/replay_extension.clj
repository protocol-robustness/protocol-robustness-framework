(ns resolver-sim.protocols.sew.replay-extension
  "Runner-owned Sew replay evidence capabilities."
  (:require [resolver-sim.protocols.registry :as registry]
            [resolver-sim.protocols.sew.replay.appeal-bond :as appeal-bond-replay]))

(defn register-capabilities! []
  (registry/register-capability!
   "sew-v1" :replay/appeal-bond-transition-receipt
   appeal-bond-replay/transition-receipt)
  (registry/register-capability!
   "sew-v1" :replay/finalize-appeal-bond
   appeal-bond-replay/finalize-evidence))

(registry/register-capability-provider! "sew-v1" :replay register-capabilities!)
(register-capabilities!)

(def installed? true)
