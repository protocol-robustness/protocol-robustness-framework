(ns resolver-sim.benchmark.integer-coordinate-distance-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.benchmark.integer-coordinate-distance :as distance]))

(def metric-space
  {:coordinate-space/id :research/margin-vector
   :coordinate-space/distance-capable? true})

(deftest exact-manhattan-distance-laws
  (is (= {:distance/status :computed :distance/value 0N}
         (distance/manhattan-distance metric-space [0N 0N] [0N 0N])))
  (is (= 7N (:distance/value
             (distance/manhattan-distance metric-space [1N 2N] [4N 6N]))))
  (is (= 8N (:distance/value
             (distance/manhattan-distance metric-space [-1N 2N] [3N -2N]))))
  (let [ab (distance/manhattan-distance metric-space [5N -2N] [-3N 8N])
        ba (distance/manhattan-distance metric-space [-3N 8N] [5N -2N])]
    (is (= ab ba))
    (is (not (neg? (:distance/value ab))))))

(deftest distance-rejects-invalid-metric-inputs
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"dimensionality"
                        (distance/manhattan-distance metric-space [1N] [1N 2N])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"canonical integers"
                        (distance/manhattan-distance metric-space [1.0] [2N])))
  (is (= {:distance/status :missing}
         (distance/manhattan-distance metric-space :missing [0N])))
  (is (= {:distance/status :not-comparable}
         (distance/manhattan-distance metric-space :not-comparable [0N])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nominal"
                        (distance/manhattan-distance
                         {:coordinate-space/axis-kind :researcher
                          :coordinate-space/distance-capable? true}
                         [0N] [1N]))))
