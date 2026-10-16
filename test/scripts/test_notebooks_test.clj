(ns scripts.test-notebooks-test
  (:require [clojure.test :refer [deftest is testing]]
            [scripts.test-notebooks :as sut]))

(deftest inventory-is-pinned-to-the-expected-load-set
  (testing "the checked-out tree exactly matches the explicit load set"
    (is (= {:missing [] :unexpected []}
           (sut/notebook-inventory-differences (sut/notebook-files)))))
  (testing "missing files cannot be mistaken for complete coverage"
    (is (= 53 (count (:missing (sut/notebook-inventory-differences []))))))
  (testing "a timeout remains a failing result"
    (is (= "timed-out" (sut/result-status {:ok? false :exit :timeout})))
    (is (not (:ok? {:ok? false :exit :timeout})))))
