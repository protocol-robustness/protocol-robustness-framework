(ns resolver-sim.protocols.sew.force-authorisation-extension
  "Bootstrap for the optional Sew researcher force-authorisation adapter."
  (:require [resolver-sim.protocols.registry :as registry]
            [resolver-sim.protocols.sew.force-authorisation :as force-authorisation]))

(defn register-capabilities! []
  (registry/register-capability!
   "sew-v1" :force-authorisation/validate-consensus
   force-authorisation/validate-consensus-authorisation)
  (registry/register-capability!
   "sew-v1" :force-authorisation/build-reservation
   force-authorisation/build-reservation))

(registry/register-capability-provider! "sew-v1" :force-authorisation register-capabilities!)
(register-capabilities!)

(def installed? true)
