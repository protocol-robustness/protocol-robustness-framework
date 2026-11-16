(ns resolver-sim.protocols.sew.yield-reorg-race-test
  (:require [clojure.test :refer :all]
            [resolver-sim.protocols.sew.types :as t]))

(deftest test-yield-preset-normalizes-json-strings
  (is (= :to-recipient (:yield-preset (t/make-escrow-settings {:yield-preset "to-recipient"}))))
  (is (= :to-recipient (:yield-preset (t/make-escrow-settings {:yield_preset "to-recipient"}))))
  (is (= :off (:yield-preset (t/make-escrow-settings {}))))
  (is (t/yield-preset-yield-enabled? "to-recipient"))
  (is (not (t/yield-preset-yield-enabled? "off"))))
