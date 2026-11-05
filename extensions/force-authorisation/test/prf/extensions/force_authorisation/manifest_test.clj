(ns prf.extensions.force-authorisation.manifest-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.extensions.manifest :as extension-manifest]
            [resolver-sim.extensions.registry :as registry]
            [prf.extensions.force-authorisation.scope-verification :as scope-verification]
            [prf.extensions.force-authorisation.manifest :as manifest]))

(def test-scope
  {:authorization/id "permit-1"
   :authorization/type :force-authorisation
   :held/direction :out :token :USDC :amount 5000
   :held/account :escrow-principal :owner/address "0xrecipient"
   :held/reason :force-authorised-release :held/workflow-id 1})

(deftest package-advertises-only-implemented-scope-capability
  (let [validation (extension-manifest/validate-package manifest/package)
        caps (:extension/capabilities manifest/package)]
    (is (:valid? validation) (pr-str (:violations validation)))
    (is (= 1 (count caps)))
    (is (= :force-authorisation/scope-verification (:capability/id (first caps))))
    (is (= :incubating (get-in manifest/package [:extension/status :lifecycle])))))

(deftest scope-capability-entrypoint-runs-and-fails-closed
  (let [cap (first (:extension/capabilities manifest/package))
        extension-map (registry/register-package (registry/empty-extension-map) manifest/package)
        entry (registry/lookup-capability extension-map (:capability/kind cap) (:capability/id cap))
        good-permit {:authorization/id "permit-1"
                     :authorization/scope-hash
                     (scope-verification/scope-hash test-scope)}
        result ((requiring-resolve (:entrypoint cap))
                {:permit good-permit :scope-map test-scope})]
    (is (some? entry))
    (is (true? (:valid? result)))
    (is (= :invalid-scope-verification-input
           (:reason (manifest/scope-verification {}))))))
