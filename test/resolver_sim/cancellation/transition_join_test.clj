(ns resolver-sim.cancellation.transition-join-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.cancellation.transition-join :as join]))

(def roots {:transition-definition/root "sha256:t"
            :state-before/root "sha256:s"
            :authorization/root "sha256:a"
            :preconditions/root "sha256:p"})

(deftest subject-and-result-are-derived
  (let [subject (join/subject roots)
        result (join/result subject {:effects/root "sha256:e"
                                     :state-after/root "sha256:n"
                                     :receipt/root "sha256:r"})]
    (is (= "cancellation-transition-join.v1" (:schema-version subject)))
    (is (string? (join/subject-root subject)))
    (is (string? (:result/root result)))
    (is (not= (join/subject-root subject)
              (join/subject-root (assoc roots :authorization/root "sha256:x"))))))

(deftest result-binds-all-derived-roots
  (let [subject (join/subject roots)
        output {:effects/root "sha256:e"
                :state-after/root "sha256:n"
                :receipt/root "sha256:r"}
        result (join/result subject output)]
    (doseq [field (keys output)]
      (is (not= (:result/root result)
                (:result/root (join/result subject
                                           (assoc output field "sha256:x"))))))))
