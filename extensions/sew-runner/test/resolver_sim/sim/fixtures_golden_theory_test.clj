(ns resolver-sim.sim.fixtures-golden-theory-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.scenario.theory-result :as theory-result]
            [resolver-sim.sim.fixtures :as fixtures]))

(def base-replay
  {:outcome :pass
   :metrics {:attack-successes 0 :invariant-violations 0}
   :trace [{:projection-hash "abc123"}]})

(def base-theory-decl
  {:claim-id :claims/test :assumptions [] :falsifies-if []})

(deftest golden-theory-snapshot-shape
  (testing "golden-snapshot is stable and omits evidence"
    (let [theory-res (theory-result/attach-three-way-model
                      {:status :not-falsified :reason :no-metric-falsification-claim :diagnostics {}}
                      {:theory-eval-profile :regression}
                      :theory base-theory-decl)
          snap (theory-result/golden-snapshot theory-res base-theory-decl)]
      (is (= :claims/test (:claim-id snap)))
      (is (= "theory-eval-v2" (:evaluator-version snap)))
      (is (not (contains? snap :evidence))))))

(deftest compare-golden-reports-modes
  (let [replay {:suite-id :suites/test
                :trace-id :traces/t1
                :final-state-hash "h1"
                :metrics {:attack-successes 0}
                :outcome :pass}
        golden (merge replay
                      {:golden-schema-version "2.0"
                       :theory {:evaluator-version "theory-eval-v2"
                                :claim-id :claims/test
                                :status :not-falsified
                                :mechanism-status :pass
                                :equilibrium-status :not-checked}})
        actual-same golden
        actual-theory-drift (assoc golden :theory (assoc (:theory golden) :status :falsified))]
    (testing "replay-and-theory passes when identical"
      (is (:ok? (fixtures/compare-golden-reports golden actual-same
                                                 {:golden-verify-mode :replay-and-theory}))))
    (testing "replay-and-theory fails on theory drift"
      (let [cmp (fixtures/compare-golden-reports golden actual-theory-drift
                                                 {:golden-verify-mode :replay-and-theory})]
        (is (not (:ok? cmp)))
        (is (:replay-ok? cmp))
        (is (not (:theory-ok? cmp)))
        (is (= "theory snapshot mismatch" (:summary cmp)))
        (is (pos? (count (:mismatches cmp))))
        (is (= :theory (first (:path (first (:mismatches cmp))))))))
    (testing "replay-only ignores theory drift"
      (is (:ok? (fixtures/compare-golden-reports golden actual-theory-drift
                                                 {:golden-verify-mode :replay-only}))))
    (testing "legacy golden without :theory compares replay only"
      (let [legacy (dissoc golden :theory :golden-schema-version)
            actual (assoc actual-theory-drift :theory {:status :falsified})]
        (is (:ok? (fixtures/compare-golden-reports legacy actual
                                                   {:golden-verify-mode :replay-and-theory})))))))
