(ns resolver-sim.scenario.golden-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.scenario.golden :as golden]
            [resolver-sim.scenario.runner :as runner]
            [resolver-sim.sim.fixtures :as fixtures]))

(def base-replay
  {:suite-id :suites/test
   :trace-id :traces/t1
   :final-state-hash "h1"
   :metrics {:attack-successes 0 :yield/escrow-principal 10000}
   :outcome :pass
   :golden-schema-version "2.0"})

(deftest compare-reports-pass
  (let [cmp (golden/compare-reports base-replay base-replay {:golden-verify-mode :replay-and-theory})]
    (is (:ok? cmp))
    (is (= "match" (:summary cmp)))
    (is (empty? (:mismatches cmp)))))

(deftest compare-reports-single-replay-mismatch
  (let [actual (assoc-in base-replay [:metrics :yield/escrow-principal] 9950)
        cmp    (golden/compare-reports base-replay actual {:golden-verify-mode :replay-only})]
    (is (not (:ok? cmp)))
    (is (= "replay snapshot mismatch" (:summary cmp)))
    (is (= 1 (count (:mismatches cmp))))
    (is (= [:metrics :yield/escrow-principal]
           (:path (first (:mismatches cmp)))))
    (is (= 10000 (:expected (first (:mismatches cmp)))))
    (is (= 9950 (:actual (first (:mismatches cmp)))))))

(deftest compare-reports-multiple-mismatches
  (let [actual (-> base-replay
                   (assoc :outcome :fail)
                   (assoc-in [:metrics :yield/deferred-claims] 25))
        cmp    (golden/compare-reports base-replay actual {:golden-verify-mode :replay-only})]
    (is (not (:ok? cmp)))
    (is (= "2 mismatches" (:summary cmp)))
    (is (= 2 (count (:mismatches cmp))))))

(deftest golden-pass-affects-scenario-pass
  (let [cmp (golden/compare-reports base-replay base-replay {:golden-verify-mode :replay-and-theory})
        entry {:outcome :pass
               :expected-fail? false
               :halt-reason nil
               :checks {:golden cmp}
               :replay-result {}}]
    (is (true? (runner/scenario-pass? entry {})))))

(deftest golden-mismatch-affects-scenario-pass
  (let [actual (assoc-in base-replay [:metrics :yield/escrow-principal] 9950)
        cmp    (golden/compare-reports base-replay actual {:golden-verify-mode :replay-only})
        entry  {:outcome :pass
                :expected-fail? false
                :checks {:golden cmp}
                :replay-result {}}]
    (is (false? (runner/scenario-pass? entry {})))))

(deftest golden-failure-invariant-before-render
  "Failed golden comparison must be reflected in :pass? at judgement time."
  (let [actual (assoc base-replay :outcome :fail)
        cmp    (golden/compare-reports base-replay actual {:golden-verify-mode :replay-only})
        base   (runner/build-entry-result
                {:name "t1"
                 :replay-result {:outcome :pass :scenario-id "t1" :events-processed 1
                                 :metrics {} :trace []}
                 :scenario {:scenario-id "t1"}}
                {})
        entry  (runner/finalize-fixture-entry
                base
                {:expected-outcome :pass
                 :threshold-validation {:ok? true :violations []}
                 :golden-comparison cmp
                 :golden-report actual
                 :metrics {}
                 :trace-id "t1"}
                {})]
    (is (false? (get-in cmp [:ok?])))
    (is (false? (:pass? entry)))
    (is (= :pass (:outcome entry))
        "outcome may still be :pass; :pass? must not trust it over golden")))

(deftest golden-disabled-leaves-check-absent
  (is (true? (runner/scenario-pass?
              {:outcome :pass :expected-fail? false :checks {} :replay-result {}}
              {}))))

(deftest missing-golden-check-distinct-from-mismatch
  (let [missing (golden/missing-golden-check :traces/missing "data/fixtures/golden/missing.report.edn"
                                             :replay-and-theory)]
    (is (false? (:ok? missing)))
    (is (= :missing-golden (:error missing)))
    (is (empty? (:mismatches missing)))
    (is (= "golden snapshot missing" (:summary missing)))
    (is (false? (runner/scenario-pass?
                 {:outcome :pass :checks {:golden missing} :replay-result {}}
                 {})))))

(deftest mismatch-ordering-is-deterministic
  (let [actual (-> base-replay
                   (assoc :outcome :fail)
                   (assoc-in [:metrics :yield/deferred-claims] 25)
                   (assoc-in [:metrics :attack-successes] 1))
        cmp1   (golden/compare-reports base-replay actual {:golden-verify-mode :replay-only})
        cmp2   (golden/compare-reports base-replay actual {:golden-verify-mode :replay-only})]
    (is (= (:mismatches cmp1) (:mismatches cmp2)))
    (is (= [:outcome] (:path (first (:mismatches cmp1)))))))

(deftest fixtures-delegates-to-golden
  (let [actual (assoc base-replay :outcome :fail)
        cmp    (fixtures/compare-golden-reports base-replay actual
                                                {:golden-verify-mode :replay-only})]
    (is (contains? cmp :summary))
    (is (seq (:mismatches cmp)))
    (is (contains? cmp :expected))
    (is (contains? cmp :actual))))
