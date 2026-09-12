(ns resolver-sim.benchmark.allocation-entitlement-policy
  "Closed, rooted configuration policy for allocation-entitlement authority.

   This policy selects an allocation-policy commitment and its fixed authority
(ns resolver-sim.benchmark.allocation-entitlement-policy)   domain. It deliberately does not commit native realization or an allocation
   population."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]))

(def ^:const schema "allocation-entitlement-policy.v1")
(def ^:const domain :allocation-entitlement-policy-v1)
(def fields
  #{:artifact/schema
    :allocation-policy/root
    :asset/root
    :protocol-instance/root
    :custody-subject/root
    :custody-scope/root
    :allocation-entitlement/profile})
(def profiles #{:allocation-entitlement/fixed-domain-v1})

(defn policy-root [policy]
  (ref/sha256-ref
   (hc/domain-hash domain
                   (hc/project-canonical-safe
                    (dissoc policy :allocation-entitlement-policy/root)))))

(defn validate-policy [policy]
  (let [errors (cond-> []
                 (not (map? policy)) (conj "policy must be a map")
                 (not= schema (:artifact/schema policy)) (conj "invalid policy schema")
                 (not= fields (set (keys (dissoc policy :allocation-entitlement-policy/root))))
                 (conj "policy has missing or unknown keys")
                 (not (every? #(ref/valid-sha256-ref? (get policy %))
                              [:allocation-policy/root
                               :asset/root
                               :protocol-instance/root
                               :custody-subject/root
                               :custody-scope/root]))
                 (conj "policy roots must be valid sha256 references")
                 (not (contains? profiles (:allocation-entitlement/profile policy)))
                 (conj "allocation-entitlement/profile is unsupported")
                 (and (contains? policy :allocation-entitlement-policy/root)
                      (not= (:allocation-entitlement-policy/root policy) (policy-root policy)))
                 (conj "policy root mismatch"))]
    {:valid? (empty? errors) :errors (vec errors)}))

(defn build-policy [policy]
  (let [base (assoc policy :artifact/schema schema)
        validation (validate-policy base)]
    (when-not (:valid? validation)
      (throw (ex-info "allocation entitlement policy is invalid" validation)))
    (assoc base :allocation-entitlement-policy/root (policy-root base))))
