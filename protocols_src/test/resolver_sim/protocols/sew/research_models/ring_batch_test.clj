(ns resolver-sim.protocols.sew.research-models.ring-batch-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.protocols.sew.runner-extension]
            [resolver-sim.sim.batch :as batch]
            [resolver-sim.stochastic.rng :as rng]))

(deftest runner-installs-sew-ring-batch-adapter
  (let [result (batch/run-ring-batch
                (rng/make-rng 42)
                1
                {:escrow-size 1000}
                {:senior {:name "senior" :bond 1000}
                 :juniors [{:name "junior" :bond 250}]})]
    (is (= 1 (:n-trials result)))
    (is (= "1-junior-ring" (:ring-type result)))
    (is (number? (:ring-total-profit result)))
    (is (contains? result :ring-profitable?))))
