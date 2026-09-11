(ns resolver-sim.validation.suite-result-test
  (:require [clojure.test :refer :all]
            [resolver-sim.validation.suite-result :as sut]))

(deftest test-check-reducer
  (let [checks [{:check/id :c1 :status :passed :status-key :s1}
                {:check/id :c2 :status :failed :error-key :e1 :message "Fail 1"}
                {:check/id :c3 :status :warning :warning-key :w1 :message "Warn 1"}]
        result (sut/suite-result :test-suite :test-type checks)]
    (is (= :failed (:status result)))
    (is (= #{:s1} (:status-keys result)))
    (is (= #{:e1} (:error-keys result)))
    (is (= #{:w1} (:warning-keys result)))
    (is (= 3 (get-in result [:metrics :checks])))
    (is (= 1 (get-in result [:metrics :passed])))
    (is (= 1 (get-in result [:metrics :failed])))
    (is (= 1 (get-in result [:metrics :warnings])))
    (is (= 1 (count (:errors result))))))

(deftest test-derive-suite-status
  (is (= :passed (sut/derive-suite-status [{:status :passed}])))
  (is (= :warning (sut/derive-suite-status [{:status :passed} {:status :warning}])))
  (is (= :failed (sut/derive-suite-status [{:status :passed} {:status :failed} {:status :warning}]))))

(deftest test-checks-passed-failed
  (is (true? (sut/checks-passed? [{:status :passed}])))
  (is (false? (sut/checks-passed? [{:status :failed}])))
  (is (true? (sut/checks-failed? [{:status :failed}])))
  (is (false? (sut/checks-failed? [{:status :passed}]))))

(deftest test-checks-by-severity
  (let [checks [{:check/id :c1 :severity :info}
                {:check/id :c2 :severity :warning}
                {:check/id :c3 :severity :critical}]]
    (is (= 3 (count (sut/checks-by-severity checks :info))))
    (is (= 2 (count (sut/checks-by-severity checks :warning))))
    (is (= 1 (count (sut/checks-by-severity checks :critical))))))
