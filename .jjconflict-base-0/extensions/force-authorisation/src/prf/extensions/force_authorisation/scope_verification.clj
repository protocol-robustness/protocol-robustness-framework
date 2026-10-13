(ns prf.extensions.force-authorisation.scope-verification
  "Protocol-neutral force-authorisation permit/scope normalisation and exact
   scope verification. This is the capability consumed by held-custody; it has
   no dependency on a Sew adapter or any other extension."
  (:require [resolver-sim.assurance.force-authorisation :as force-auth]))

(defn normalize-scope [scope] (force-auth/normalize-force-authorisation-scope scope))
(defn normalize-permit [permit] (force-auth/normalize-force-authorisation-record permit))
(defn scope-hash [scope] (force-auth/force-authorisation-scope-hash scope))
(defn verify-scope [permit scope]
  {:valid? (and (not (force-auth/scope-hash-missing? permit))
                (not (force-auth/scope-hash-mismatch? permit scope)))})
(defn verify-usability [permit consumption scope now]
  (force-auth/verify-authorisation-usable permit consumption scope now))
