(ns resolver-sim.generators.fixtures-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.generators.fixtures :as gf]))

(deftest promote-batch-dry-run-returns-files-without-writing
  (let [res (gf/promote-batch! {:start-seed 1 :n 3 :max-steps 6 :profile :timeout-boundary :dry-run? true})]
    (is (true? (:dry-run? res)))
    (is (integer? (:written res)))
    (is (vector? (:files res)))))
