(ns resolver-sim.protocols.sew.registry-immutability-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.protocols.sew.registry :as reg]
            [resolver-sim.protocols.sew.accounting :as acct]))

(deftest register-stake-rejects-non-positive-amounts
  (doseq [amount [nil -1 0]]
    (let [error (try
                  (reg/register-stake (t/empty-world 1000) "0xRes" amount)
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= :invalid-stake-amount (:type (ex-data error)))
          (str "amount " amount " must be rejected")))))

(deftest slash-resolver-stake-immutability
  (testing "slash-resolver-stake does not mutate the input world map"
    (let [world (-> (t/empty-world 1000)
                    (reg/register-stake "0xRes" 1000))
          ;; Capture state before
          before-stake (reg/get-stake world "0xRes")
          before-world (assoc-in world [:meta :test-marker] :before-state)

          ;; Perform slash
          result (reg/slash-resolver-stake before-world "0xRes" 500)
          after-world (:world result)]

      (is (= 1000 before-stake) "Original world stake remains unchanged")
      (is (= :before-state (get-in before-world [:meta :test-marker])) "Original world marker remains unchanged")
      (is (= 500 (reg/get-stake after-world "0xRes")) "New world stake reflects slash")
      (is (= :before-state (get-in after-world [:meta :test-marker])) "New world retains unrelated state")
      (is (not= before-world after-world) "New world map is distinct from original"))))
