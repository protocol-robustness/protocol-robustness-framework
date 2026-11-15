(ns resolver-sim.protocols.sew.replay-extension
  "Runner-owned Sew replay evidence capabilities."
  (:require [resolver-sim.protocols.registry :as registry]
            [resolver-sim.protocols.sew.replay.appeal-bond :as appeal-bond-replay]))

(defn register-capabilities! []
  (registry/register-capability!
   "sew-v1" :replay/transition-evidence
   (fn [event result operation-id operation-type pre-world post-world]
     (when-let [receipt (appeal-bond-replay/transition-receipt
                         event result operation-id operation-type pre-world post-world)]
       {:appeal-bond/transition-receipt receipt})))
  (registry/register-capability!
   "sew-v1" :replay/finalize-result
   (fn [scenario result]
     (when (some :appeal-bond/transition-receipt (:trace result))
       (let [evidence (appeal-bond-replay/finalize-evidence scenario result)]
         (cond-> evidence
           (false? (:projection-ok? evidence))
           (assoc :halt-reason :invalid-appeal-bond-projection)))))))

(registry/register-capability-provider! "sew-v1" :replay register-capabilities!)
(register-capabilities!)

(def installed? true)
