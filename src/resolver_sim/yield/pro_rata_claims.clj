(ns resolver-sim.yield.pro-rata-claims
  "Deprecated compatibility forwarding namespace.

   Pro-rata allocation claims are mechanism-level and now live in
   `resolver-sim.pro-rata.claims`. Yield propagation and accounting remain
   domain-specific; callers should migrate to the mechanism namespace."
  (:require [resolver-sim.pro-rata.claims :as claims]))

(def evaluator-registry claims/evaluator-registry)
(def evaluator-resolver claims/evaluator-resolver)
(def registered-claim-ids claims/registered-claim-ids)

(def ^:private legacy-claim-id->mechanism-id
  {:allocation-complete :pro-rata/allocation-complete
   :non-negative :pro-rata/non-negative
   :conservation :pro-rata/conservation
   :rounding-bounded :pro-rata/quota-bounded
   :ordering-independent :pro-rata/permutation-invariant
   :partial-fill-fairness :pro-rata/partial-fill-quota-bounded})

(defn evaluate-claim
  "Compatibility adapter for legacy unqualified claim IDs. New callers must
   use the registered `:pro-rata/*` identifiers."
  [claim-id context]
  (if (= claim-id :pro-rata-fairness)
    (claims/check-pro-rata-fairness context)
    (claims/evaluate-claim (get legacy-claim-id->mechanism-id claim-id claim-id)
                           context)))
(def check-projection-deterministic claims/check-projection-deterministic)
(def check-projection-canonical-safe claims/check-projection-canonical-safe)
(def check-allocation-complete claims/check-allocation-complete)
(def check-non-negative claims/check-non-negative)
(def check-conservation claims/check-conservation)
(def check-rounding-bounded claims/check-rounding-bounded)
(def check-ordering-independent claims/check-ordering-independent)
(def check-pro-rata-fairness claims/check-pro-rata-fairness)
(def check-partial-fill-fairness claims/check-partial-fill-fairness)
