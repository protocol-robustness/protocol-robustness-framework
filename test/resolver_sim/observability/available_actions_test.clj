(ns resolver-sim.observability.available-actions-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.observability.available-actions :as actions]
            [resolver-sim.hash.reference :as refs]))

(def state-root (str "sha256:" (apply str (repeat 64 "a"))))

(deftest available-actions-are-deterministic-and-rooted
  (let [a (actions/build {:protocol/id "example-v1"
                          :state/root state-root
                          :actor/id :alice
                          :actions [{:action "withdraw" :params {:amount 2N}}
                                    {:action "cancel" :params {}}]})
        b (actions/build {:protocol/id "example-v1"
                          :state/root state-root
                          :actor/id :alice
                          :actions [{:action "cancel" :params {}}
                                    {:action "withdraw" :params {:amount 2N}}]})]
    (is (= (:observation/root a) (:observation/root b)))
    (is (= (:available-actions a) (:available-actions b)))
    (is (not= (:observation/root a)
              (:observation/root
               (actions/build (assoc a :actions
                                     [{:action "withdraw" :params {:amount 3N}}])))))
    (is (= {:artifact/schema "available-actions-acknowledgement.v1"
            :observation/root (:observation/root a)
            :actor/id :alice
            :acknowledged? true}
           (actions/acknowledge a :alice)))))
