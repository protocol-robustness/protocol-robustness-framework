(ns resolver-sim.protocols.sew.pro-rata.economics-extension
  "Runtime bootstrap for optional Sew pro-rata slash economics."
  (:require [resolver-sim.extensions.api.v1 :as prf-api]
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
    (prf-api/register-protocol-capability! "sew-v1" capability implementation)))

(prf-api/register-protocol-capability-provider!
 "sew-v1" :sew-pro-rata-economics register-capabilities!)
(register-capabilities!)
