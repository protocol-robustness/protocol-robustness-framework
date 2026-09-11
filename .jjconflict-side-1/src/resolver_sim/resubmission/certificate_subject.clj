(ns resolver-sim.resubmission.certificate-subject
  "Canonical typed subject for certificate verification in an attempt."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]))

(def domain :prf-results-binding-v1)

(def schema "attempt-certificate-verification-subject.v1")

(defn root [subject]
  (ref/sha256-ref (hc/domain-hash domain (dissoc subject :subject/root))))

(defn build [certificate-root results-root submitted-bundle-root verification-profile]
  (let [subject {:subject/schema schema
                 :subject/certificate-root certificate-root
                 :subject/results-root results-root
                 :subject/submitted-bundle-root submitted-bundle-root
                 :subject/verification-profile verification-profile}]
    (assoc subject :subject/root (root subject))))

(defn verifier-selection-subject [subject]
  {:capability/kind :prf.resubmission/certificate-verification
   :capability/id :prf.resubmission/certificate-v1
   :capability/contract-version 1
   :subject/root (:subject/root subject)})

(defn binds-artifacts? [subject certificate-root results-root submitted-bundle-root]
  (and (= #{:subject/schema :subject/certificate-root :subject/results-root
            :subject/submitted-bundle-root :subject/verification-profile :subject/root}
          (set (keys subject)))
       (= schema (:subject/schema subject))
       (ref/valid-sha256-ref? (:subject/root subject))
       (= (:subject/root subject) (root subject))
       (= certificate-root (:subject/certificate-root subject))
       (= results-root (:subject/results-root subject))
       (= submitted-bundle-root (:subject/submitted-bundle-root subject))))
