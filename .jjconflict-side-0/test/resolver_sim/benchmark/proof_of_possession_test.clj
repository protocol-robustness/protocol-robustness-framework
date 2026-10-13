(ns resolver-sim.benchmark.proof-of-possession-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.benchmark.proof-of-possession :as possession]))

(deftest requirement-and-status-remain-independent
  (is (= :unspecified-and-not-established
         (:proof-of-possession/outcome
          (possession/evaluate :unspecified :not-established))))
  (let [requested (possession/evaluate :requested :not-established)]
    (is (= :requested-but-not-established
           (:proof-of-possession/outcome requested)))
    (is (true? (:proof-of-possession/outstanding-request? requested)))
    (is (true? (:proof-of-possession/required-satisfied? requested))))
  (let [required (possession/evaluate :required :not-established)]
    (is (= :required-but-not-established
           (:proof-of-possession/outcome required)))
    (is (= :not-established (:proof-of-possession/status required)))
    (is (false? (:proof-of-possession/required-satisfied? required))))
  (doseq [requirement [:requested :required]
          status [:established :verified]]
    (is (= :established
           (:proof-of-possession/outcome
            (possession/evaluate requirement status))))))

(deftest possession-enums-are-closed
  (is (thrown? clojure.lang.ExceptionInfo
               (possession/evaluate :mandatory :not-established)))
  (is (thrown? clojure.lang.ExceptionInfo
               (possession/evaluate :required :assumed))))
