(ns resolver-sim.resubmission.attempt-subject
  "Canonical identity of an evaluated submission attempt.

   Admission reservations and fences are execution coordination, not attempt
   identity; they are deliberately excluded from this projection."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]))

(def schema "acceptance-attempt-subject.v1")
(def domain :prf-acceptance-attempt-subject-v1)
(def target-fields #{:target/type :target/id})

(defn projection [subject]
  (select-keys subject [:artifact/schema :attempt/evaluation-root
                        :attempt/submitted-bundle-root :attempt/target]))

(defn root [subject]
  (ref/sha256-ref (hc/domain-hash domain (projection subject))))

(defn valid-target? [target]
  (and (map? target)
       (= target-fields (set (keys target)))
       (qualified-keyword? (:target/type target))
       (string? (:target/id target))
       (not-empty (:target/id target))))

(defn valid? [subject]
  (and (map? subject)
       (= #{:artifact/schema :attempt/evaluation-root
            :attempt/submitted-bundle-root :attempt/target :attempt/subject-root}
          (set (keys subject)))
       (= schema (:artifact/schema subject))
       (ref/valid-sha256-ref? (:attempt/evaluation-root subject))
       (ref/valid-sha256-ref? (:attempt/submitted-bundle-root subject))
       (valid-target? (:attempt/target subject))
       (= (:attempt/subject-root subject) (root subject))))

(defn build [evaluation-root submitted-bundle-root target]
  (let [subject {:artifact/schema schema
                 :attempt/evaluation-root evaluation-root
                 :attempt/submitted-bundle-root submitted-bundle-root
                 :attempt/target target}]
    (when-not (and (ref/valid-sha256-ref? evaluation-root)
                   (ref/valid-sha256-ref? submitted-bundle-root)
                   (valid-target? target))
      (throw (ex-info "invalid acceptance attempt subject"
                      {:reason :invalid-attempt-subject})))
    (assoc subject :attempt/subject-root (root subject))))
