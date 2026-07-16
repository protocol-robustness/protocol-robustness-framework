(ns resolver-sim.benchmark.corpus-validation-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.benchmark.corpus-validation :as corpus-validation]))

(deftest registry-reachable-benchmark-corpus-is-classpath-loadable
  (is (= {:packs 2 :benchmarks 11 :status :passed}
         (corpus-validation/validate-corpus!))))
