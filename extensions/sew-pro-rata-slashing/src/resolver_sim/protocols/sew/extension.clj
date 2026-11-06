(ns resolver-sim.protocols.sew.extension
  "Explicit Sew extension bootstrap. Load this namespace only on a :with-sew
   classpath to register Sew and install the optional pro-rata slash-evidence
   adapter. The protocol registry loads this bootstrap when resolving Sew."
  (:require [resolver-sim.protocols.registry :as registry]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.invariants :as invariants]
            [resolver-sim.protocols.sew.pro-rata.evidence]
            [resolver-sim.protocols.sew.replay.appeal-bond :as appeal-bond-replay]))

(defn register-capabilities! []
  (registry/register-extension! "sew-v1" 'resolver-sim.protocols.sew/protocol)
  (registry/register-capability!
   "sew-v1" :reference-validation/replay
   (fn [scenario opts] (sew/replay-with-sew-protocol scenario opts)))
  (registry/register-capability!
   "sew-v1" :reference-validation/invariant-checker
   {:world-invariant-ids invariants/world-invariant-ids
    :check-all-fn invariants/check-all})
  (registry/register-capability!
   "sew-v1" :replay/appeal-bond-transition-receipt
   appeal-bond-replay/transition-receipt)
  (registry/register-capability!
   "sew-v1" :replay/finalize-appeal-bond
   appeal-bond-replay/finalize-evidence))

(registry/register-capability-provider! "sew-v1" :core register-capabilities!)
(def protocol-id (do (register-capabilities!) "sew-v1"))
