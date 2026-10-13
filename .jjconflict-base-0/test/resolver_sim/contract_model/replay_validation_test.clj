(ns resolver-sim.contract-model.replay-validation-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.scenario.outcome-semantics :as ose]
            [resolver-sim.scenario.theory :as theory]))

(def minimal-scenario
  {:schema-version "1.1"
   :id "metric-validation-test"
   :title "Metric validation"
   :purpose :regression
   :scenario-author "@test-author"
   :agents [{:id "buyer" :address "0xB" :role "buyer"}
            {:id "seller" :address "0xS" :role "seller"}]
   :events [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
             :params {:workflow-id "0" :amount 100 :token "USDC"}}]
   :theory {:claim-id :test/metric
            :assumptions []
            :falsifies-if [{:metric :attack-successes :op :> :value 0}]}})

(defn- validate [scenario]
  (let [protocol (sew/->SewProtocol)
        metrics  (into replay/base-metrics (proto/metric-vocabulary protocol))]
    (replay/validate-scenario scenario metrics)))

(deftest rejects-unknown-theory-metric
  (let [v (validate (assoc-in minimal-scenario [:theory :falsifies-if]
                              [{:metric :not-a-real-metric :op :> :value 0}]))]
    (is (= false (:ok v)))
    (is (= :unknown-theory-metric (:error v)))))

(deftest rejects-population-metric-in-trace-theory
  (let [v (validate (assoc-in minimal-scenario [:theory :falsifies-if]
                              [{:metric :coalition/net-profit :op :> :value 0}]))]
    (is (= false (:ok v)))
    (is (= :population-metric-in-trace-theory (:error v))
        "slash-form population metrics must not appear in single-trace theory")))

(deftest requires-scenario-author-in-v1-1
  (let [v (validate (dissoc minimal-scenario :scenario-author))]
    (is (= false (:ok v)))
    (is (= :missing-scenario-author (:error v)))))

(deftest rejects-malformed-event-time-type
  (testing "a non-numeric / non-Instant event :time fails as :invalid-event-time, not a ClassCastException"
    (doseq [bad ["yesterday" :yesterday (java.util.Date.) nil]]
      (let [v (validate (assoc minimal-scenario :events
                               [{:seq 0 :time bad :agent "buyer"
                                 :action "create_escrow" :params {}}]))]
        (is (= false (:ok v)) (str "time " (pr-str bad)))
        (is (= :invalid-event-time (:error v)) (str "time " (pr-str bad)))))))

(deftest accepts-mixed-number-and-instant-event-times
  (testing "number and java.time.Instant event times may coexist when ordered"
    (let [v (validate (assoc minimal-scenario :events
                             [{:seq 0 :time 1000 :agent "buyer"
                               :action "create_escrow" :params {}}
                              {:seq 1 :time (java.time.Instant/ofEpochSecond 2000)
                               :agent "buyer" :action "create_escrow" :params {}}]))]
      (is (:ok v)))))

(deftest detects-regression-across-mixed-event-time-types
  (testing "monotonicity is enforced on epoch-seconds even when representation mixes"
    (let [v (validate (assoc minimal-scenario :events
                             [{:seq 0 :time (java.time.Instant/ofEpochSecond 1500)
                               :agent "buyer" :action "create_escrow" :params {}}
                              {:seq 1 :time 1400 :agent "buyer"
                               :action "create_escrow" :params {}}]))]
      (is (= false (:ok v)))
      (is (= :non-monotonic-event-time (:error v))))))

(deftest allows-population-metric-when-scope-declared
  (let [v (validate (-> minimal-scenario
                        (assoc-in [:theory :falsifies-if]
                                  [{:metric :coalition/net-profit :op :> :value 0}])
                        (assoc-in [:theory :metric-scope] :population)))]
    (is (:ok v)
        "population-scoped theory may reference multi-epoch metrics at validate time")))

(deftest strict-missing-telemetry-semantics
  (testing "documented contract: strict missing → inconclusive, not falsified"
    (let [res (theory/evaluate-theory
               {:metrics {:attack-successes 0}
                :protocol (sew/->SewProtocol)
                :terminal-world {}
                :trace []
                :events []
                :states {}}
               {:falsifies-if [{:metric :attack-successes :op :> :value 0}
                               {:metric :coalition/net-profit :op :> :value 0}]}
               {:theory-eval-profile :strict})]
      (is (= :inconclusive (:status res)))
      (is (= :strict-missing-metrics (:reason res)))
      (is (false? (:falsified? res)))
      (is (false? (ose/theory-result-ok? res :regression {:require-conclusive? true}))))))
