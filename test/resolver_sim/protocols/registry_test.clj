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

(deftest extension-capabilities-follow-extension-lifecycle
  (let [protocol-id "test-capability-v1"
        capability :test/contract
        value {:answer 42}]
    (try
      (registry/register-extension! protocol-id 'resolver-sim.protocols.dummy/protocol)
      (is (= capability (registry/register-capability! protocol-id capability value)))
      (is (= value (registry/get-capability protocol-id capability)))
      (finally
        (registry/unregister-extension! protocol-id)))
    (is (nil? (registry/get-capability protocol-id capability)))))

(deftest loaded-sew-yield-extension-restores-capabilities-after-unregister
  (let [protocol-id "sew-v1"
        capability :yield/apply-op]
    (try
      (is (fn? (registry/get-capability protocol-id capability)))
      (registry/unregister-extension! protocol-id)
      (is (fn? (registry/get-capability protocol-id capability)))
      (is (some? (registry/get-protocol protocol-id)))
      (finally
        ;; Leave the shared process with its lazy core adapter reloaded.
        (registry/get-protocol protocol-id)))))
