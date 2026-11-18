(ns resolver-sim.protocols.sew.actions-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.protocols.sew.actions :as actions]))

(deftest action-vocabulary-maps-known-implementation-actions
  (is (= :transfer/create-protected (actions/to-canonical "create_escrow")))
  (is (= :case/dispute-raised (actions/to-canonical "raise_dispute")))
  (is (= :transfer/partially-released (actions/to-canonical "partial_release"))))

(deftest action-vocabulary-fails-closed-for-unknown-actions
  (is (= :unknown/action (actions/to-canonical "unrecognized-action"))))
