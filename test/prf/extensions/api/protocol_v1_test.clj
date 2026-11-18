(ns prf.extensions.api.protocol-v1-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.extensions.api.protocol.v1 :as api]))

(deftest protocol-capability-api-registers-and-resolves-values
  (let [protocol-id (str "protocol-api-v1-test-" (random-uuid))
        capability-id :sample/value]
    (is (= capability-id
           (api/register-capability! protocol-id capability-id :available)))
    (is (= :available (api/get-capability protocol-id capability-id)))))
