(ns resolver-sim.resubmission.execution-evidence-subject
  "Typed semantic subject for execution-evidence verification."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]))

(def schema "attempt-execution-evidence-subject.v1")
(def domain :prf-attempt-execution-evidence-subject-v1)

(defn projection [subject]
  (select-keys subject [:subject/schema :subject/execution-evidence-root
                        :subject/results-root :subject/submitted-bundle-root
                        :subject/evidence-kind :subject/verification-profile]))

(defn root [subject]
  (ref/sha256-ref (hc/domain-hash domain (projection subject))))

(defn valid? [subject]
  (and (map? subject)
       (= #{:subject/schema :subject/execution-evidence-root :subject/results-root
            :subject/submitted-bundle-root :subject/evidence-kind
            :subject/verification-profile :subject/root}
          (set (keys subject)))
       (= schema (:subject/schema subject))
       (every? #(ref/valid-sha256-ref? (get subject %))
               [:subject/execution-evidence-root :subject/results-root
                :subject/submitted-bundle-root])
       (qualified-keyword? (:subject/evidence-kind subject))
       (qualified-keyword? (:subject/verification-profile subject))
       (= (:subject/root subject) (root subject))))

(defn build [evidence-root results-root bundle-root evidence-kind profile]
  (let [subject {:subject/schema schema
                 :subject/execution-evidence-root evidence-root
                 :subject/results-root results-root
                 :subject/submitted-bundle-root bundle-root
                 :subject/evidence-kind evidence-kind
                 :subject/verification-profile profile}]
    (assoc subject :subject/root (root subject))))

(defn verifier-selection-subject [subject]
  {:capability/kind :prf.resubmission/execution-evidence-verification
   :capability/id :prf.resubmission/execution-evidence-v1
   :capability/contract-version 1
   :subject/root (:subject/root subject)})

(defn binds-artifacts?
  [subject evidence-root results-root bundle-root]
  (and (valid? subject)
       (= evidence-root (:subject/execution-evidence-root subject))
       (= results-root (:subject/results-root subject))
       (= bundle-root (:subject/submitted-bundle-root subject))))
