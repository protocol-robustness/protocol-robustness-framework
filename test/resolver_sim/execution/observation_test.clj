(ns resolver-sim.execution.observation-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.execution.observation :as observation]
            [resolver-sim.execution.realization :as realization]
            [resolver-sim.execution.runtime-profile :as runtime-profile]))

(def runtime-profile
  (runtime-profile/build {:execution/claimant-parallelism 4
                          :execution/claimant-parallel-threshold 1
                          :execution/quiescence-timeout-seconds 30}))

(def effective {:execution/path :parallel
                :execution/reason :parallel
                :execution/parallel-work-observed? true
                :execution/max-claimant-parallelism 4
                :execution/budget-limited? false})

(deftest observation-is-canonical-and-bound-to-policy
  (let [o (observation/build {:runtime-profile-root (:runtime-profile/root runtime-profile)
                              :effective effective
                              :completion {:status :completed}})]
    (is (observation/valid? o))
    (is (:valid? (observation/verify-against-profile runtime-profile o)))
    (is (re-matches #"sha256:[0-9a-f]{64}" (:execution-observation/root o)))
    (is (= (:execution-observation/root o)
           (:execution-observation/root
            (observation/build {:runtime-profile-root (:runtime-profile/root runtime-profile)
                                :effective effective
                                :completion {:status :completed}}))))))

(deftest observation-rejects-incoherent-realization
  (let [o (observation/build {:runtime-profile-root (:runtime-profile/root runtime-profile)
                              :effective (assoc effective :execution/max-claimant-parallelism 5)
                              :completion {:status :completed}})]
    (is (not (:valid? (observation/verify-against-profile runtime-profile o))))))

(deftest realization-lifecycle-freezes-finalizes-and-emits-once
  (let [state (realization/open)
        emitted (atom [])]
    (binding [realization/*claimant-execution-runtime-profile-root* (:runtime-profile/root runtime-profile)
              realization/*claimant-execution-observation-sink* #(swap! emitted conj %)
              realization/*claimant-execution-realization* state]
      (realization/record! {:candidate-parallelism 1 :reason :requested-serial})
      (realization/freeze! state)
      (is (false? (boolean (realization/record! {:budget-limited? true})))
          "late facts are ignored after freeze")
      (let [result (realization/finalize! state)]
        (is (observation/valid? result))
        (is (= :finalized (:lifecycle @state)))
        (is (empty? @emitted) "finalization does not emit")
        (realization/emit! state)
        (realization/emit! state)
        (is (= [result] @emitted) "a finalized realization is emitted once")
        (is (thrown? clojure.lang.ExceptionInfo (realization/finalize! state)))))))

(deftest realization-sink-failure-propagates-after-claiming-single-emission
  (let [state (realization/open)
        emitted (atom 0)
        sink (fn [_] (swap! emitted inc) (throw (ex-info "sink failed" {})))]
    (binding [realization/*claimant-execution-runtime-profile-root* (:runtime-profile/root runtime-profile)
              realization/*claimant-execution-observation-sink* sink
              realization/*claimant-execution-realization* state]
      (realization/record-executor-dispatch! 2)
      (realization/record-quiesced!)
      (realization/freeze! state)
      (realization/finalize! state)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"sink failed"
                            (realization/emit! state)))
      (is (= 1 @emitted))
      (is (true? (:emitted? @state)))
      (is (nil? (realization/emit! state)))
      (is (= 1 @emitted)))))

(deftest realization-scopes-are-fresh-and-noninterfering
  (let [first-state (realization/open)
        second-state (realization/open)]
    (binding [realization/*claimant-execution-realization* first-state]
      (realization/record! {:phase :first}))
    (binding [realization/*claimant-execution-realization* second-state]
      (realization/record! {:phase :second}))
    (is (= [{:phase :first}] (:phases @first-state)))
    (is (= [{:phase :second}] (:phases @second-state)))
    (is (not (identical? first-state second-state)))))

(deftest realization-fails-closed-before-quiescence-and-without-observation-configuration
  (let [state (realization/open)
        emitted (atom [])]
    (binding [realization/*claimant-execution-observation-sink* #(swap! emitted conj %)]
      (swap! state assoc :executor-backed-phases 1)
      (realization/freeze! state)
      (let [failure (try
                      (realization/finalize! state)
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
        (is (re-find #"before executor quiescence" (.getMessage failure))))
      (is (empty? @emitted)))
    (let [closed-root (realization/open)]
      (realization/freeze! closed-root)
      (is (nil? (realization/finalize! closed-root)))
      (is (nil? (realization/emit! closed-root)))
      (is (= :finalized (:lifecycle @closed-root))))
    (let [closed-sink (realization/open)]
      (binding [realization/*claimant-execution-runtime-profile-root* (:runtime-profile/root runtime-profile)]
        (realization/freeze! closed-sink)
        (is (observation/valid? (realization/finalize! closed-sink)))
        (is (nil? (realization/emit! closed-sink)))
        (is (false? (:emitted? @closed-sink)))))))
