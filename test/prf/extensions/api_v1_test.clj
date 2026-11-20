(ns prf.extensions.api-v1-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.extensions.api.v1 :as api]))

(deftest protocol-capability-api-registers-and-resolves-values
  (let [protocol-id (str "api-v1-test-" (random-uuid))
        capability-id :sample/value]
    (is (= capability-id
           (api/register-protocol-capability! protocol-id capability-id 42)))
    (is (= 42 (api/get-protocol-capability protocol-id capability-id)))))

(deftest protocol-capability-api-restores-registered-provider
  (let [protocol-id "sew-v1"
        capability-id (keyword "sew-core" (str "test-value-" (random-uuid)))]
    (is (= :sew-core
           (api/register-protocol-capability-provider!
            protocol-id :sew-core
            #(api/register-protocol-capability! protocol-id capability-id :restored))))
    (is (= :restored (api/get-protocol-capability protocol-id capability-id)))))
