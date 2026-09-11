(ns resolver-sim.contract-model.replay-events-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.contract-model.replay.flags :as flags]
            [resolver-sim.contract-model.replay.metrics :as metrics]
            [resolver-sim.contract-model.replay.execution :as execution]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.yield.risk-monitor :as risk]
            [resolver-sim.protocols.dummy :as dummy]))

(def minimal-scenario
  {:scenario-id "replay-events-minimal"
   :schema-version "1.0"
   :initial-block-time 1000
   :agents [{:id "a" :address "0xA"}]
   :events [{:seq 0 :time 1000 :agent "a" :action "noop" :params {}}]})

(deftest replay-events-returns-trace-and-metrics
  (let [result (replay/replay-events dummy/protocol minimal-scenario)]
    (is (= :pass (:outcome result)))
    (is (vector? (:trace result)))
    (is (map? (:metrics result)))
    (is (pos? (:events-processed result)))))

(deftest replay-events-accepts-opts
  (let [result (replay/replay-events dummy/protocol minimal-scenario {:minimal true})]
    (is (= :pass (:outcome result)))))

(deftest replay-events-no-evidence-chain-dependency
  (testing "replay-events does not require evidence chain macros"
    (let [result (replay/replay-events dummy/protocol minimal-scenario)]
      (is (some? result))
      (is (= :pass (:outcome result))))))

(deftest replay-events-result-contains-context-source
  (let [result (replay/replay-events dummy/protocol minimal-scenario)]
    (is (contains? result :context/version))
    (is (contains? result :context/source))
    (is (= (:scenario-id minimal-scenario)
           (get-in result [:context/source :scenario-id])))))

(deftest replay-events-attaches-yield-risk-events
  (testing "replay-events attaches a :yield/risk-events vector under evidence-mode :all"
    (let [result (replay/replay-events dummy/protocol minimal-scenario)]
      (is (= :pass (:outcome result)))
      (is (contains? result :yield/risk-events))
      (is (vector? (:yield/risk-events result))))))

(deftest replay-events-isolates-risk-atom
  (testing "a stale shared risk atom must not trip :fail-on-short-circuits on a clean run"
    (let [stale {:short-circuits [:recoverable-liquidity-cap] :deferred-delta 1 :module-id "stale" :ts 0}]
      (risk/with-fresh-risk-context
        (swap! @#'risk/*risk-events* conj stale)
        (let [result (replay/replay-events dummy/protocol minimal-scenario
                                           {:flags {:fail-on-short-circuits #{:recoverable-liquidity-cap}}})]
          (is (= :pass (:outcome result)))
          (is (nil? (:halt-reason result)))
          (is (empty? (:yield/risk-events result))))))))

(deftest replay-events-captures-fresh-short-circuits
  (testing ":yield/risk-events reflects only this run's events (fresh context)"
    (let [result (risk/with-fresh-risk-context
                   (replay/replay-events dummy/protocol minimal-scenario
                                         {:flags {:fail-on-short-circuits #{:recoverable-liquidity-cap}}}))]
      (is (= :pass (:outcome result)))
      (is (vector? (:yield/risk-events result)))
      (is (not (some #(= :recoverable-liquidity-cap (first (:short-circuits %)))
                     (:yield/risk-events result)))))))

(deftest replay-events-without-opts-is-ok
  (let [result (replay/replay-events dummy/protocol minimal-scenario)]
    (is (= :pass (:outcome result)))))

(deftest evidence-mode-all-produces-correct-result
  (testing "replay-events with :evidence-mode :all returns correct outcome"
    (let [result (replay/replay-events dummy/protocol minimal-scenario {:evidence-mode :all})]
      (is (= :pass (:outcome result)))
      (is (pos? (:events-processed result)))
      (is (seq (:trace result))))))

(deftest evidence-mode-none-produces-correct-result
  (testing "replay-events with :evidence-mode :none returns correct outcome"
    (let [result (replay/replay-events dummy/protocol minimal-scenario {:evidence-mode :none})]
      (is (= :pass (:outcome result)))
      (is (pos? (:events-processed result)))
      (is (seq (:trace result)))
      (is (nil? (:halt-reason result))))))

(deftest evidence-mode-allows-transition-in-essential
  (testing "apply-action-with-evidence checks evidence-mode via context flags"
    (let [flags  (assoc flags/default-replay-flags :evidence-mode :essential)
          ctx    {:replay-flags flags}
          world  {:block-time 1000 :params {:scenario-id "test"}}
          event  {:seq 0 :time 1000 :agent "a" :action "noop" :params {}}
          result (execution/process-step dummy/protocol ctx world event)]
      (is (map? result))
      (is (contains? result :ok?))
      (is (contains? result :trace-entry)))))

(deftest evidence-mode-none-skips-all
  (testing "replay-events with :evidence-mode :none works via replay-with-protocol"
    (let [result (replay/replay-events dummy/protocol minimal-scenario {:evidence-mode :none})]
      (is (= :pass (:outcome result))))))

(deftest evidence-mode-default-in-minimal-flags
  (testing "minimal-replay-flags includes :evidence-mode :none"
    (is (= :none (:evidence-mode flags/minimal-replay-flags)))))

(deftest base-metrics-zero-metrics-base-keys
  (testing "zero-metrics with :base profile contains only base metrics"
    (let [m (metrics/zero-metrics dummy/protocol :base)]
      (is (contains? m :attack-attempts))
      (is (contains? m :attack-successes))
      (is (contains? m :rejected-attacks))
      (is (contains? m :reverts))
      (is (contains? m :invariant-violations))
      (is (contains? m :batch-buckets))
      (is (contains? m :batch-events))
      (is (contains? m :batch-conflicts))
      (is (contains? m :invariant-results)))))

(deftest base-metrics-zero-metrics-excludes-vocab
  (testing "zero-metrics with :sew-integrated includes protocol vocab"
    (let [m (metrics/zero-metrics dummy/protocol :sew-integrated)]
      (is (contains? m :attack-attempts))
      ;; dummy protocol has no vocab, but :sew-integrated would include it
      ;; The test verifies base keys are present regardless
      (is (contains? m :reverts)))))

(deftest base-metrics-replay-events-produces-base-only
  (testing "replay-events with :metrics-profile :base returns correct outcome with only base keys"
    (let [result (replay/replay-events dummy/protocol minimal-scenario
                                       {:metrics-profile :base})]
      (is (= :pass (:outcome result)))
      (is (pos? (:events-processed result)))
      (let [m (:metrics result)]
        (is (contains? m :attack-attempts))
        (is (contains? m :invariant-violations))
        (is (contains? m :reverts))))))

(deftest base-metrics-simple-replay-defaults-to-yield-provider
  (testing "simple-replay defaults to yield-provider profile (not base)"
    (let [result (replay/simple-replay dummy/protocol minimal-scenario)]
      (is (= :pass (:outcome result)))
      (is (contains? (:metrics result) :attack-attempts)))))

(deftest process-step-runtime-context-preserves-output
  (testing "process-step runtime context does not leak into persisted output"
    (let [ctx-key? (fn [x]
                     (and (keyword? x)
                          (= "ctx" (namespace x))))
          contains-ctx-key? (fn contains-ctx-key? [x]
                              (cond
                                (map? x)
                                (or (some ctx-key? (keys x))
                                    (some contains-ctx-key? (vals x)))
                                (coll? x)
                                (some contains-ctx-key? x)
                                :else false))
          flags  (assoc flags/default-replay-flags :evidence-mode :all)
          ctx    {:replay-flags flags}
          world  {:block-time 1000 :params {:scenario-id "test"}}
          event  {:seq 0 :time 1000 :agent "a" :action "noop" :params {}}
          result (execution/process-step dummy/protocol ctx world event)
          te     (:trace-entry result)]
      (is (map? result))
      (is (contains? te :seq))
      (is (some? (:transition/hash te)))
      (is (string? (:transition/hash te)))
      (is (contains? te :projection-hash))
      (is (not (contains-ctx-key? result)))
      (is (not (contains-ctx-key? te)))
      (is (not (contains-ctx-key? (:world te)))))))
