(ns resolver-sim.related-claims-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.related-claims :as related-claims]))

(deftest core-loads-without-sew-related-claims
  (is (some? related-claims/claim-acceptance))
  (is (not (contains? (loaded-libs) 'resolver-sim.protocols.sew.related-claims))))
