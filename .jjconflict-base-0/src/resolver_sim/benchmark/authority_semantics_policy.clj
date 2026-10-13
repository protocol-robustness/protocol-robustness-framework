(ns resolver-sim.benchmark.authority-semantics-policy
  "C4b configuration policy selecting one exact closed C4a semantics descriptor."
  (:require [resolver-sim.benchmark.governed-authority-semantics :as semantics]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]))

(def ^:const schema "authority-semantics-policy.v1")
(def ^:const domain :authority-semantics-policy-v1)
(def fields #{:artifact/schema :authority-semantics/root})

(defn policy-root [policy]
  (ref/sha256-ref
   (hc/domain-hash domain
                   (hc/project-canonical-safe
                    (dissoc policy :authority-semantics-policy/root)))))

(defn validate-policy [policy]
  (let [errors (cond-> []
                 (not (map? policy)) (conj "policy must be a map")
                 (not= schema (:artifact/schema policy)) (conj "invalid policy schema")
                 (not= fields (set (keys (dissoc policy :authority-semantics-policy/root))))
                 (conj "policy has missing or unknown keys")
                 (not (ref/valid-sha256-ref? (:authority-semantics/root policy)))
                 (conj "authority-semantics/root is invalid")
                 (and (contains? policy :authority-semantics-policy/root)
                      (not= (:authority-semantics-policy/root policy) (policy-root policy)))
                 (conj "policy root mismatch"))]
    {:valid? (empty? errors) :errors (vec errors)}))

(defn build-policy [policy]
  (let [base (assoc policy :artifact/schema schema)
        validation (validate-policy base)]
    (when-not (:valid? validation)
      (throw (ex-info "authority semantics policy is invalid" validation)))
    (assoc base :authority-semantics-policy/root (policy-root base))))

(defn verify-policy-selection [policy descriptor]
  (let [validation (validate-policy policy)
        semantics-validation (semantics/validate-semantics descriptor)]
    {:valid? (and (:valid? validation)
                  (:valid? semantics-validation)
                  (contains? descriptor :governed-authority-semantics/root)
                  (= (:authority-semantics/root policy)
                     (:governed-authority-semantics/root descriptor)))
     :policy-root (:authority-semantics-policy/root policy)
     :semantics-root (:governed-authority-semantics/root descriptor)}))
