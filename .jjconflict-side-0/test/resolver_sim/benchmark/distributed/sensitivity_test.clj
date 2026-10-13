(ns resolver-sim.benchmark.distributed.sensitivity-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.benchmark.distributed.sensitivity :as sut]))

(deftest default-sensitivity-is-deterministic-and-explicit
  (is (= (sut/default-root) (sut/default-root)))
  (is (= :unclassified (:sensitivity/class sut/default-basis)))
  (is (= :not-yet-assessed (:sensitivity/assessment sut/default-basis)))
  (is (not= (sut/default-root)
            (sut/root (assoc sut/default-basis :sensitivity/class :restricted)))))
