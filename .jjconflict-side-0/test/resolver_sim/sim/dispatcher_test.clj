(ns resolver-sim.sim.dispatcher-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.protocols.dummy :as dummy]
            [resolver-sim.sim.dispatcher :as dispatcher]
            [resolver-sim.util.attribution :as attr]))

(def ^:private test-world
  {:block-time 1000
   :params {:scenario-id "test-scenario"}})

(def ^:private test-context
  {:agent-index {"buyer" {:id "buyer" :address "0xbuyer"}}})

(def ^:private test-event
  {:seq 1
   :time 1000
   :agent "buyer"
   :action :create_escrow
   :params {:amount 1000}})

(def ^:private test-attribution
  {:ctx/run-id "test-run"
   :ctx/scenario-id "test-scenario"
   :ctx/event-index 1
   :ctx/event-type :create_escrow})

(deftest apply-action-with-evidence-delegates-to-dispatch-action
  (binding [attr/*attribution* test-attribution]
    (let [result (dispatcher/apply-action-with-evidence
                  dummy/protocol test-context test-world test-event)]
      (is (:ok result))
      (is (map? (:world result)))
      (is (= (:block-time test-world) (:block-time (:world result)))))))

(deftest apply-action-with-evidence-returns-evidence-key
  (binding [attr/*attribution* test-attribution]
    (let [result (dispatcher/apply-action-with-evidence
                  dummy/protocol test-context test-world test-event)]
      (is (contains? result :evidence)))))

(deftest apply-action-with-evidence-produces-evidence-record
  (binding [attr/*attribution* test-attribution]
    (let [result (dispatcher/apply-action-with-evidence
                  dummy/protocol test-context test-world test-event)
          evidence (:evidence result)]
      (is (map? evidence))
      (is (= "evidence-record.v1" (:schema-version evidence)))
      (is (= :transition (:artifact-kind evidence)))
      (is (string? (:evidence-hash evidence)))
      (is (string? (:before-hash evidence)))
      (is (string? (:after-hash evidence)))
      (is (string? (:action-hash evidence)))
      (is (string? (:result-hash evidence))))))

(deftest apply-action-with-evidence-attribution-in-evidence
  (binding [attr/*attribution* test-attribution]
    (let [result (dispatcher/apply-action-with-evidence
                  dummy/protocol test-context test-world test-event)
          evidence (:evidence result)]
      (is (:ctx/run-id (:attribution evidence)))
      (is (:ctx/scenario-id (:attribution evidence)))
      (is (:ctx/step (:attribution evidence)))
      (is (:ctx/event-id (:attribution evidence))))))

(deftest apply-action-with-evidence-hash-changes-with-world
  (binding [attr/*attribution* test-attribution]
    (let [result1 (dispatcher/apply-action-with-evidence
                   dummy/protocol test-context test-world test-event)
          modified-world (assoc test-world :extra-key "modified")
          result2 (dispatcher/apply-action-with-evidence
                   dummy/protocol test-context modified-world test-event)]
      (is (not= (:evidence-hash (:evidence result1))
                (:evidence-hash (:evidence result2)))))))

(deftest apply-action-with-evidence-returns-nil-evidence-without-attribution
  (binding [attr/*attribution* {}]
    (let [result (dispatcher/apply-action-with-evidence
                  dummy/protocol test-context test-world test-event)]
      (is (:ok result))
      (is (nil? (:evidence result))))))

(deftest apply-action-with-evidence-still-dispatches-without-attribution
  (binding [attr/*attribution* {}]
    (let [result (dispatcher/apply-action-with-evidence
                  dummy/protocol test-context test-world test-event)]
      (is (:ok result))
      (is (map? (:world result))))))

(deftest apply-action-with-evidence-error-artifact-kind
  (binding [attr/*attribution* test-attribution]
    (let [error-event (assoc test-event :action :nonexistent)
          result (dispatcher/apply-action-with-evidence
                  dummy/protocol test-context test-world error-event)]
      (is (:ok result))
      (is (= :transition (:artifact-kind (:evidence result)))))))

(deftest apply-action-with-evidence-preserves-extra-keys
  (binding [attr/*attribution* test-attribution]
    (let [result (dispatcher/apply-action-with-evidence
                  dummy/protocol test-context test-world test-event)]
      (is (contains? result :ok))
      (is (contains? result :world))
      (is (contains? result :evidence)))))
