(ns resolver-sim.protocols.registry-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.protocols.registry :as registry]))

(deftest optional-extension-registration-is-lazy
  (let [protocol-id "test-extension-v1"
        adapter-symbol 'resolver-sim.protocols.dummy/protocol]
    (try
      (is (= protocol-id (registry/register-extension! protocol-id adapter-symbol)))
      (is (some #{protocol-id} (registry/known-protocol-ids)))
      (is (some? (registry/get-protocol protocol-id)))
      (finally
        (registry/unregister-extension! protocol-id)))
    (is (not (some #{protocol-id} (registry/known-protocol-ids))))))
