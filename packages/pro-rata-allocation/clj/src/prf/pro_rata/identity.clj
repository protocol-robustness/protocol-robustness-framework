(ns prf.pro-rata.identity
  "Canonical identities owned by the pro-rata allocation artifact family."
  (:require [prf.canonical.hash :as canonical-hash]
            [prf.pro-rata.policy :as policy]
            [prf.pro-rata.result :as result]))

(def projection-artifact-domain "PROJECTION_ARTIFACT_V1")
(def allocation-policy-domain "PRO_RATA_ALLOCATION_POLICY_V1")

(defn request-root
  "Derive the frozen projection-artifact root of a normalized request."
  [canonical-request]
  (canonical-hash/hash-with-domain
   projection-artifact-domain
   {:intent :projection-artifact :artifact canonical-request}))

(defn allocation-result-root
  "Derive the frozen allocation-result root from its root input or result map."
  [allocation-result]
  (canonical-hash/hash-with-domain
   projection-artifact-domain
   (result/root-input allocation-result)))

(defn allocation-policy-root
  "Derive the distinct canonical identity for the policy fields of a result."
  [allocation-result]
  (canonical-hash/hash-with-domain
   allocation-policy-domain
   {:intent :pro-rata-allocation-policy
    :artifact (policy/identity-basis allocation-result)}))
