(ns resolver-sim.accounting.held-adjustment-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.accounting.held-adjustment :as adjustment]))

(deftest settlement-projections-preserve-large-integers-and-reject-floats
  (let [large (bigint "922337203685477580812345")
        identity {:workflow-id "workflow-1"
                  :token :USDC
                  :direction :withdrawal
                  :filled large
                  :recipient "recipient-1"}
        adjustment-row {:held-adjustment/id "adjustment-1"
                        :amount large
                        :held/direction :withdrawal}]
    (is (string? (adjustment/settlement-identity identity)))
    (is (string? (adjustment/settlement-held-adjustment-set-root [adjustment-row])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exact integer"
                          (adjustment/settlement-identity
                           (assoc identity :filled 1.5))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exact integer"
                          (adjustment/settlement-held-adjustment-set-root
                           [(assoc adjustment-row :amount 1.5)])))))
