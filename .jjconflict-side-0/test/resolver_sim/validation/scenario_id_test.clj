(ns resolver-sim.validation.scenario-id-test
  (:require [clojure.test :refer :all]
            [resolver-sim.validation.scenario-id :refer [validate-scenario-id!]]))

(deftest test-validate-scenario-id
  (testing "valid scenario-ids"
    (is (= "s123-valid" (validate-scenario-id! "s123-valid")))
    (is (= "abc" (validate-scenario-id! "abc"))))

  (testing "invalid scenario-ids"
    (is (thrown? clojure.lang.ExceptionInfo (validate-scenario-id! "-invalid")))    ;; Leading hyphen
    (is (thrown? clojure.lang.ExceptionInfo (validate-scenario-id! "invalid id"))))) ;; Space
