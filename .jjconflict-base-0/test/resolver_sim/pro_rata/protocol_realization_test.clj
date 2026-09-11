(ns resolver-sim.pro-rata.protocol-realization-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.pro-rata.canonical-effects :as effects]
            [resolver-sim.pro-rata.protocol-realization :as sut]))

(defn- root [n] (str "sha256:" (apply str (repeat 63 "0")) n))
(def quantity (root "1"))
(def before {:held-credit 100 :owner :alice :status :open :nonce 17})
(def transition (effects/transition {quantity 100} [(effects/delta quantity -40)]))
(def adapter
  (sut/build-adapter {:protocol-id :sew
                      :protocol-state-schema-root (root "2")
                      :projection-semantics-root (root "3")
                      :quantity-mapping-root (root "4")
                      :reconstruction-semantics-root (root "5")
                      :write-set-semantics-root (root "6")}))
(defn project [state] {quantity (:held-credit state)})
(defn protocol-root [state] (hc/domain-hash :world-state state))
(def write-set [[:held-credit]])

(deftest realization-binds-transition-projection-and-frame
  (let [realization (sut/build-realization
                     {:adapter adapter :canonical-transition transition
                      :protocol-before before :write-set write-set
                      :project project
                      :reconstruct (fn [state canonical-after _]
                                     (assoc state :held-credit (get canonical-after quantity)))
                      :protocol-state-root protocol-root
                      :realization-semantics-root (root "7")})]
    (is (= :sew (:protocol/id realization)))
    (is (= (:state-after/root transition) (:canonical-state-after/root realization)))
    (is (string? (:protocol-effect-realization/root realization)))))

(deftest realization-rejects-unrelated-protocol-mutation
  (is (thrown? clojure.lang.ExceptionInfo
               (sut/build-realization
                {:adapter adapter :canonical-transition transition
                 :protocol-before before :write-set write-set
                 :project project
                 :reconstruct (fn [state canonical-after _]
                                (assoc state
                                       :held-credit (get canonical-after quantity)
                                       :owner :mallory
                                       :status :terminated))
                 :protocol-state-root protocol-root
                 :realization-semantics-root (root "7")}))))

(deftest adapter-identity-changes-when-mapping-semantics-change
  (let [changed (sut/build-adapter
                 {:protocol-id :sew
                  :protocol-state-schema-root (root "2")
                  :projection-semantics-root (root "3")
                  :quantity-mapping-root (root "8")
                  :reconstruction-semantics-root (root "5")
                  :write-set-semantics-root (root "6")})]
    (is (not= (:adapter/root adapter) (:adapter/root changed)))))
