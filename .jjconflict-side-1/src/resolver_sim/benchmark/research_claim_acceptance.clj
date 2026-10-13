(ns resolver-sim.benchmark.research-claim-acceptance
  "Authority-issued acceptance assertions for exact analytical claims.

   The decision is an assertion made by the designated authority. V1 validates
   its canonical bindings and requires opaque decision evidence, but does not
   interpret or independently verify that evidence."
  (:require [resolver-sim.benchmark.research-analysis :as analysis]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]))

(def ^:const policy-schema-version "research-claim-acceptance-policy.v1")
(def ^:const acceptance-schema-version "research-claim-acceptance.v1")
(def ^:const decisions #{:accepted :rejected})

(defn- root? [value]
  (ref/valid-sha256-ref? value))

(defn policy-root [policy]
  (ref/sha256-ref
   (hc/domain-hash :research-claim-acceptance-policy
                   (-> policy
                       (dissoc :research-claim-acceptance-policy/root)
                       (update :acceptance-policy/decisions #(vec (sort %)))))))

(defn validate-policy [policy]
  (let [errors (cond-> []
                 (not (map? policy)) (conj :policy-not-a-map)
                 (not= policy-schema-version (:schema/version policy))
                 (conj :unsupported-policy-schema)
                 (not (keyword? (:acceptance-policy/id policy)))
                 (conj :invalid-policy-id)
                 (not (root? (:acceptance-policy/authority-root policy)))
                 (conj :invalid-policy-authority-root)
                 (not= decisions (:acceptance-policy/decisions policy))
                 (conj :invalid-policy-decisions)
                 (and (:research-claim-acceptance-policy/root policy)
                      (not= (:research-claim-acceptance-policy/root policy)
                            (policy-root policy)))
                 (conj :policy-root-mismatch))]
    {:valid? (empty? errors) :errors errors}))

(defn build-policy [{:keys [acceptance-policy/id acceptance-policy/authority-root]
                     :as _fields}]
  (let [policy {:schema/version policy-schema-version
                :acceptance-policy/id id
                :acceptance-policy/authority-root authority-root
                :acceptance-policy/decisions decisions}
        validation (validate-policy policy)]
    (when-not (:valid? validation)
      (throw (ex-info "Research claim acceptance policy is invalid" validation)))
    (assoc policy :research-claim-acceptance-policy/root (policy-root policy))))

(defn acceptance-root [acceptance]
  (ref/sha256-ref
   (hc/domain-hash :research-claim-acceptance
                   (dissoc acceptance :research-claim-acceptance/root))))

(defn- valid-verification-for-claim? [claim verification]
  (and (map? verification)
       (analysis/verification-valid? verification)
       (= (:research-claim/verification-root claim)
          (:research-analysis-verification/root verification))))

(defn validate-acceptance
  "Validate an authority-issued acceptance assertion against its dependencies.

   `claim` and `verification` are supplied resolved artifacts. The acceptance
   authority is derived from `policy`; the repeated authority root in the
   acceptance record is only an explicit binding check."
  [acceptance {:keys [claim verification policy]}]
  (let [errors (cond-> []
                 (not= acceptance-schema-version (:schema/version acceptance))
                 (conj :unsupported-acceptance-schema)
                 (not (:valid? (analysis/validate-claim claim)))
                 (conj :invalid-claim)
                 (not (valid-verification-for-claim? claim verification))
                 (conj :invalid-verification)
                 (not (:valid? (validate-policy policy)))
                 (conj :invalid-policy)
                 (not= (:research-claim/root claim)
                       (:research-claim-acceptance/claim-root acceptance))
                 (conj :claim-root-mismatch)
                 (not= (:research-analysis-verification/root verification)
                       (:research-claim-acceptance/verification-root acceptance))
                 (conj :verification-root-mismatch)
                 (not= (:research-claim-acceptance-policy/root policy)
                       (:research-claim-acceptance/policy-root acceptance))
                 (conj :policy-root-mismatch)
                 (not= (:acceptance-policy/authority-root policy)
                       (:research-claim-acceptance/authority-root acceptance))
                 (conj :authority-root-mismatch)
                 (not (contains? decisions (:research-claim-acceptance/decision acceptance)))
                 (conj :invalid-decision)
                 (not (root? (:research-claim-acceptance/decision-evidence-root acceptance)))
                 (conj :invalid-decision-evidence-root)
                 (not (string? (:research-claim-acceptance/accepted-at acceptance)))
                 (conj :invalid-accepted-at)
                 (not (and (integer? (:research-claim-acceptance/acceptance-sequence acceptance))
                           (not (neg? (:research-claim-acceptance/acceptance-sequence acceptance)))))
                 (conj :invalid-acceptance-sequence)
                 (and (:research-claim-acceptance/root acceptance)
                      (not= (:research-claim-acceptance/root acceptance)
                            (acceptance-root acceptance)))
                 (conj :acceptance-root-mismatch))]
    {:valid? (empty? errors) :errors errors}))

(defn build-acceptance
  "Build an authority-issued acceptance or rejection assertion.

   The caller supplies the decision as the authority's assertion. Validation
   derives the authoritative authority root from the frozen policy and checks
   every dependency and canonical root before returning the artifact."
  [{:keys [claim verification policy decision decision-evidence-root accepted-at
           acceptance-sequence]}]
  (let [base {:schema/version acceptance-schema-version
              :research-claim-acceptance/claim-root (:research-claim/root claim)
              :research-claim-acceptance/verification-root
              (:research-analysis-verification/root verification)
              :research-claim-acceptance/policy-root
              (:research-claim-acceptance-policy/root policy)
              :research-claim-acceptance/authority-root
              (:acceptance-policy/authority-root policy)
              :research-claim-acceptance/decision decision
              :research-claim-acceptance/decision-evidence-root decision-evidence-root
              :research-claim-acceptance/accepted-at accepted-at
              :research-claim-acceptance/acceptance-sequence acceptance-sequence}
        validation (validate-acceptance base {:claim claim
                                              :verification verification
                                              :policy policy})]
    (when-not (:valid? validation)
      (throw (ex-info "Research claim acceptance is invalid" validation)))
    (assoc base :research-claim-acceptance/root (acceptance-root base))))
