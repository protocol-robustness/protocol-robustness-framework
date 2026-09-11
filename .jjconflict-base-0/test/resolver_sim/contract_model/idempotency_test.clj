(ns resolver-sim.contract-model.idempotency-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.contract-model.idempotency :as idem]))

(deftest apply-once-applies-first-time-noops-on-duplicate
  (let [w0 {:counter 0}
        op-key [:demo :op 1]
        apply-fn (fn [w] {:ok true :world (update w :counter inc)})
        r1 (idem/apply-once w0 op-key apply-fn)
        r2 (idem/apply-once (:world r1) op-key apply-fn)]
    (is (:ok r1))
    (is (= 1 (get-in r1 [:world :counter])))
    (is (= :applied-once (get-in r1 [:extra :idempotency])))
    (is (:ok r2))
    (is (= 1 (get-in r2 [:world :counter]))
        "duplicate operation should be no-op")
    (is (= :no-op-duplicate (get-in r2 [:extra :idempotency])))))

(deftest apply-once-does-not-mark-failed-transition
  (let [w0 {:counter 0}
        op-key [:demo :fail 1]
        bad-fn (fn [_] {:ok false :error :boom})
        good-fn (fn [w] {:ok true :world (update w :counter inc)})
        r1 (idem/apply-once w0 op-key bad-fn)
        r2 (idem/apply-once w0 op-key good-fn)]
    (is (false? (:ok r1)))
    (is (:ok r2))
    (is (= 1 (get-in r2 [:world :counter]))
        "failed attempt should not consume idempotency key")))

(deftest ensure-not-duplicate-guard
  (let [op-key [:demo :guard 1]
        w0 {:idempotency/applied #{}}
        w1 (idem/mark-applied w0 op-key)
        g0 (idem/ensure-not-duplicate w0 op-key)
        g1 (idem/ensure-not-duplicate w1 op-key)]
    (is (:ok g0))
    (is (false? (:ok g1)))
    (is (= :duplicate-operation (:error g1)))))

(deftest apply-once-rejects-non-map-transition-result
  (let [op-key [:demo :bad-shape 1]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must return a transition result map"
                          (idem/apply-once {} op-key (fn [_] :not-a-map))))))

(deftest apply-once-rejects-transition-result-missing-ok
  (let [op-key [:demo :no-ok 1]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must carry an :ok field"
                          (idem/apply-once {} op-key (fn [_] {:world {}}))))))
