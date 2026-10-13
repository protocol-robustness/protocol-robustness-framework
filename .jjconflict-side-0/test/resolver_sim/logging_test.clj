(ns resolver-sim.logging-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.logging :as log]
            [resolver-sim.protocols.sew :as sew]))

(deftest logging-never-throws
  (testing "log! is resilient when logger function throws"
    (is (nil?
         (binding [log/*logger* (fn [_] (throw (RuntimeException. "boom")))]
           (log/log! :info "safe" {:k 1}))))))

(deftest missing-logger-is-safe
  (testing "non-function logger falls back safely"
    (is (nil?
         (binding [log/*logger* :invalid]
           (log/info! "message" {:path "x"}))))))

(deftest context-merging-works
  (testing "dynamic context is merged with call-site context"
    (let [events (atom [])]
      (binding [log/*logger* #(swap! events conj %)]
        (log/with-log-context {:run-id "abc" :phase :replay}
          (log/info! "ctx" {:scenario-id "S01"})))
      (is (= {:run-id "abc" :phase :replay :scenario-id "S01"}
             (get-in (first @events) [:context]))))))

(deftest with-timing-emits-duration
  (testing "with-timing emits duration-ms and returns expression value"
    (let [events (atom [])
          result (binding [log/*logger* #(swap! events conj %)]
                   (log/with-timing "unit-test"
                     (+ 20 22)))
          timing-event (last @events)]
      (is (= 42 result))
      (is (= :info (:level timing-event)))
      (is (number? (get-in timing-event [:context :duration-ms]))))))

(deftest logging-does-not-alter-replay-output
  (testing "replay output is deterministic regardless of logger binding"
    (let [scenario {:scenario-id "log-determinism"
                    :schema-version "1.1"
                    :title "Log determinism"
                    :purpose :adversarial-robustness
                    :agents [{:id "alice" :type "honest" :address "0xAlice"}
                             {:id "bob" :type "honest" :address "0xBob"}]
                    :events [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                              :params {:token "0xUSDC" :to "0xBob" :amount 1000}}
                             {:seq 1 :time 1010 :agent "alice" :action "release_escrow"
                              :params {:workflow-id 0}}]}
          r1 (binding [log/*logger* (fn [_] nil)]
               (sew/replay-with-sew-protocol scenario))
          r2 (binding [log/*logger* (fn [_] (throw (RuntimeException. "logger fail")))]
               (sew/replay-with-sew-protocol scenario))]
      (is (= (dissoc r1 :protocol)
             (dissoc r2 :protocol))))))
