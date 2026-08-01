(ns resolver-sim.assurance.parameter-attribution-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.assurance.parameter-attribution :as pa]
            [resolver-sim.accounting.held-adjustment :as held]))

(def root (str "sha256:" (apply str (repeat 64 "a"))))
(def root-context {:parameter-context/type :protocol-parameters
                   :parameter-context/root root
                   :parameter-context/version 1})
(def interim-context {:parameter-context/type :world-params
                      :parameter-context/id :sew/default-v1})
(def semantic-address {:parameter/id :sew/escrow-principal})
(def path-address {:parameter/path [:escrow :principal]})

(deftest parameter-attribution-accepts-only-canonical-alternatives
  (testing "the two supported context/address forms project unchanged"
    (is (nil? (pa/parameter-attribution-error
               {:parameter/context root-context :parameter/address semantic-address})))
    (is (nil? (pa/parameter-attribution-error
               {:parameter/context interim-context :parameter/address path-address}))))
  (testing "present alternative keys cannot be masked by an otherwise valid branch"
    (is (= :invalid-parameter-context
           (pa/parameter-attribution-error
            {:parameter/context (assoc interim-context :parameter-context/version 1)
             :parameter/address semantic-address})))
    (is (= :invalid-parameter-address
           (pa/parameter-attribution-error
            {:parameter/context root-context
             :parameter/address {:parameter/id :sew/escrow-principal
                                 :parameter/path []}})))
    (is (= :invalid-parameter-address
           (pa/parameter-attribution-error
            {:parameter/context root-context
             :parameter/address {:parameter/id :sew/escrow-principal
                                 :parameter/path [{:runtime "map"}]}})))))

(deftest held-adjustment-projector-fails-closed-and-binds-position
  (let [adjustment {:authorization/id "fa-42"
                    :authorization/type :force-authorisation
                    :held/direction :out
                    :token :USDC
                    :amount 100
                    :held/account :escrow-principal
                    :held/position-id [:held/position :USDC :escrow-principal 42]
                    :owner/address "0xRecipient"
                    :held/reason :force-authorised-release
                    :held/workflow-id 42
                    :parameter/context root-context
                    :parameter/address semantic-address}
        scope (held/project-held-adjustment-scope adjustment)]
    (is (= (:held/position-id adjustment) (:held/position-id scope)))
    (is (= root-context (:parameter/context scope)))
    (is (= semantic-address (:parameter/address scope)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot project invalid"
                          (held/project-held-adjustment-scope
                           (assoc adjustment :parameter/address nil))))))
