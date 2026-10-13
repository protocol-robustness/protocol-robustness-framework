(ns prf.capital-lifecycle.transition-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [prf.capital-lifecycle.transition :as sut]))

(defn- fixture-cases []
  (with-open [reader (io/reader (io/resource "capital-lifecycle-transitions-v1.jsonl"))]
    (mapv #(json/read-str % :key-fn keyword) (line-seq reader))))

(deftest shared-transition-vectors
  (doseq [{:keys [id state command expected]} (fixture-cases)]
    (testing id
      (let [result (sut/transition state command)]
        (is (= (:accepted? expected) (:accepted? result)))
        (if (:accepted? expected)
          (is (= (:state expected) (select-keys (:state result) (keys (:state expected)))))
          (is (= (:reason expected) (:reason result))))))))

(deftest command-shapes-are-closed
  (is (= "invalid-command-shape"
         (:reason (sut/transition sut/initial-state
                                  {:type "close" :operation-id "op-a" :extra true}))))
  (is (= "unknown-command"
         (:reason (sut/transition sut/initial-state {:type "future-command"})))))
