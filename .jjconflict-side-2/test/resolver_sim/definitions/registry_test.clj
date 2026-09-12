(ns resolver-sim.definitions.registry-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.definitions.registry :as defs]))

(deftest stable-definitions-hash
  (is (string? (defs/definitions-hash)))
  (is (= (defs/definitions-hash) (defs/definitions-hash))))
