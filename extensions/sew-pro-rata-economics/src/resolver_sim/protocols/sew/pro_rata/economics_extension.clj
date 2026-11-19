(ns resolver-sim.protocols.sew.pro-rata.economics-extension
  "Runtime bootstrap for optional Sew pro-rata slash economics."
  (:require [resolver-sim.extensions.api.protocol.v1 :as protocol-api]
            [resolver-sim.protocols.sew.pro-rata.economics :as economics]))

(def capability-implementations
  {:sew-pro-rata-economics/calculate-slash-allocation
   economics/calculate-sew-slash-allocation
   :sew-pro-rata-economics/build-slash-projection-artifact
   economics/build-sew-slash-projection-artifact
   :sew-pro-rata-economics/calculate-slash-allocation-from-projection
   economics/calculate-sew-slash-allocation-from-projection})

(defn register-capabilities! []
  (doseq [[capability implementation] capability-implementations]
    (protocol-api/register-capability! "sew-v1" capability implementation)))

(protocol-api/register-capability-provider!
 "sew-v1" :sew-pro-rata-economics register-capabilities!)
(register-capabilities!)
