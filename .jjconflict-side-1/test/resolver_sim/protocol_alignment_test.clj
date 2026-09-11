(ns resolver-sim.protocol-alignment-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocol-alignment :as pa]))

(deftest valid-protocol-status-test
  (testing "known protocol statuses validate"
    (is (pa/valid-protocol-status? :protocol/current))
    (is (pa/valid-protocol-status? :protocol/known-gap))
    (is (pa/valid-protocol-status? :protocol/proposed))
    (is (pa/valid-protocol-status? :protocol/experimental))
    (is (pa/valid-protocol-status? :protocol/deprecated)))
  (testing "unknown protocol statuses do not validate"
    (is (not (pa/valid-protocol-status? :protocol/nonexistent)))
    (is (not (pa/valid-protocol-status? :protocol/foo)))
    (is (not (pa/valid-protocol-status? :solidity/implemented)))))

(deftest valid-solidity-status-test
  (testing "known solidity statuses validate"
    (is (pa/valid-solidity-status? :solidity/implemented))
    (is (pa/valid-solidity-status? :solidity/current-behaviour))
    (is (pa/valid-solidity-status? :solidity/not-implemented))
    (is (pa/valid-solidity-status? :solidity/not-applicable)))
  (testing "unknown solidity statuses do not validate"
    (is (not (pa/valid-solidity-status? :solidity/nonexistent)))
    (is (not (pa/valid-solidity-status? :solidity/foo)))
    (is (not (pa/valid-solidity-status? :protocol/current)))))

(deftest proposed-predicate-test
  (testing "proposed? returns true for :protocol/proposed"
    (is (pa/proposed? {:protocol/status :protocol/proposed})))
  (testing "proposed? returns false for other statuses"
    (is (not (pa/proposed? {:protocol/status :protocol/current})))
    (is (not (pa/proposed? {:protocol/status :protocol/known-gap})))
    (is (not (pa/proposed? {})))
    (is (not (pa/proposed? {:protocol/status :protocol/experimental})))))

(deftest current-predicate-test
  (testing "current? returns true for :protocol/current"
    (is (pa/current? {:protocol/status :protocol/current})))
  (testing "current? returns false for other statuses"
    (is (not (pa/current? {:protocol/status :protocol/proposed})))
    (is (not (pa/current? {:protocol/status :protocol/known-gap})))
    (is (not (pa/current? {})))))

(deftest known-gap-predicate-test
  (testing "known-gap? returns true for :protocol/known-gap"
    (is (pa/known-gap? {:protocol/status :protocol/known-gap})))
  (testing "known-gap? returns false for other statuses"
    (is (not (pa/known-gap? {:protocol/status :protocol/current})))
    (is (not (pa/known-gap? {:protocol/status :protocol/proposed})))
    (is (not (pa/known-gap? {})))))

(deftest scenario-kinds-set-test
  (testing "scenario-kinds set is present and non-empty"
    (is (seq pa/scenario-kinds))
    (is (contains? pa/scenario-kinds :finding-reproduction))
    (is (contains? pa/scenario-kinds :mitigation-validation))
    (is (contains? pa/scenario-kinds :regression))
    (is (contains? pa/scenario-kinds :exploration))))
