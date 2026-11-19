(ns prf.extensions.api.protocol-interface-v1-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.protocols.protocol :as protocol]))

(deftest simulation-adapter-interface-has-the-version-one-contract
  (is (= #{:protocol-id :init-world :build-execution-context :dispatch-action
           :check-invariants-single :check-invariants-transition :world-snapshot
           :available-actions :resolve-id-alias :created-id :open-entities :project-state}
         (set (keys (:sigs (var-get #'protocol/SimulationAdapter)))))))
