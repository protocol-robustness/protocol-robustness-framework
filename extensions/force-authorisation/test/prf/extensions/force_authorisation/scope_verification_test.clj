(ns prf.extensions.force-authorisation.scope-verification-test
  (:require [clojure.test :refer [deftest is]]
            [prf.extensions.force-authorisation.scope-verification :as scope]
            [resolver-sim.assurance.force-authorisation :as core]))

(def valid-scope
  {:authorization/id "permit-1"
   :authorization/type :force-authorisation
   :held/direction :out
   :token :USDC
   :amount 5000
   :held/account :escrow-principal
   :owner/address "0xrecipient"
   :held/reason :force-authorised-release
   :held/workflow-id 1})

(deftest scope-capability-is-an-exact-protocol-neutral-forwarder
  (let [normalized (scope/normalize-scope valid-scope)
        permit {:authorization/id "permit-1"
                :authorization/scope-hash (scope/scope-hash valid-scope)}]
    (is (= (core/normalize-force-authorisation-scope valid-scope) normalized))
    (is (= (core/force-authorisation-scope-hash valid-scope)
           (scope/scope-hash valid-scope)))
    (is (= {:valid? true} (scope/verify-scope permit valid-scope)))
    (is (= {:valid? false}
           (scope/verify-scope permit (assoc valid-scope :amount 5001))))))
