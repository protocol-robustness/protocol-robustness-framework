(ns prf.extensions.force-authorisation.scope-verification
  "Protocol-neutral force-authorisation permit/scope normalisation and exact
   scope verification. This is the capability consumed by held-custody; it has
   no dependency on a Sew adapter or any other extension."
  (:require [resolver-sim.extensions.api.v1 :as api]))

(defn normalize-scope [scope] (api/normalize-force-authorisation-scope scope))
(defn normalize-permit [permit] (api/normalize-force-authorisation-record permit))
(defn scope-hash [scope] (api/force-authorisation-scope-hash scope))
(defn verify-scope [permit scope]
  {:valid? (and (not (api/scope-hash-missing? permit))
                (not (api/scope-hash-mismatch? permit scope)))})
(defn verify-usability [permit consumption scope now]
  (api/verify-authorisation-usable permit consumption scope now))
