(ns resolver-sim.resubmission.attempt-subject-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.resubmission.attempt-subject :as subject]))

(def evaluation-root "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
(def bundle-root "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
(def target {:target/type :prf/resubmission :target/id "destination-1"})

(deftest subject-is-closed-and-rooted
  (let [s (subject/build evaluation-root bundle-root target)]
    (is (subject/valid? s))
    (is (= (:attempt/subject-root s) (subject/root s)))
    (is (not (subject/valid? (assoc s :reservation/id "transient"))))
    (is (not (subject/valid? (assoc s :attempt/subject-root "sha256:bad"))))))

(deftest subject-root-sensitivity-and-retry-invariance
  (let [s (subject/build evaluation-root bundle-root target)]
    (is (not= (:attempt/subject-root s)
              (:attempt/subject-root
               (subject/build "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
                              bundle-root target))))
    (is (not= (:attempt/subject-root s)
              (:attempt/subject-root
               (subject/build evaluation-root
                              "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
                              target))))
    (is (not= (:attempt/subject-root s)
              (:attempt/subject-root
               (subject/build evaluation-root bundle-root
                              (assoc target :target/id "destination-2")))))))

(deftest subject-rejects-malformed-target
  (testing "target is not an unconstrained map"
    (is (thrown? clojure.lang.ExceptionInfo
                 (subject/build evaluation-root bundle-root {:target/id "x"})))
    (is (not (subject/valid-target? {:target/type "not-a-keyword"
                                     :target/id "x"})))))
