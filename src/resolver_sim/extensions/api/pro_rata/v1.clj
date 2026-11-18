(ns resolver-sim.extensions.api.pro-rata.v1
  "Versioned public source API for portable pro-rata allocation and evidence.

   Consumers use the canonical request/result and projection artifacts through
   this facade. Allocation, verification, and evaluation implementations remain
   PRF-owned and may change internally without changing this API contract."
  (:require [resolver-sim.evidence.node]
            [resolver-sim.pro-rata.allocation :as allocation]
            [resolver-sim.pro-rata.evidence :as evidence]
            [resolver-sim.pro-rata.evaluation :as evaluation]))

(def allocate allocation/allocate)
(def mechanism-evidence-artifact evidence/mechanism-evidence-artifact)
(def evidence-reference evidence/evidence-reference)
(def evidence-valid? evidence/evidence-valid?)
(def build-projection-artifact evaluation/build-projection-artifact)
(def calculate-from-projection evaluation/calculate-prorata-from-projection)
