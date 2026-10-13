(ns resolver-sim.run.overview-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.run.overview :as overview]))

(def sample-run-result
  "A representative :scenario-run/result map."
  {:status :pass
   :suite/key :sew-invariants
   :evidence/profile :standard
   :output/profile :full
   :runner-selection {:mode :pinned
                      :runner-id :runner/local-bb
                      :description "Default pinned local Babashka runner"}
   :totals {:passed 3 :failed 0 :total 3}
   :results [{:scenario-id "S01"
              :name "S01 [inline]"
              :pass? true
              :outcome :pass
              :halt-reason nil
              :checks [{:check/id :some-check :check/status :pass}]
              :violations {}
              :dispatcher-id :protocol/sew-v1
              :expected-fail? false
              :scenario-path nil
              :scenario-hash "abc123def456hash"
              :replay-result {:outcome :pass :world-hash "abc"}
              :execution/raw {:outcome :pass :world-hash "abc"}
              :runner {:backend :local-current :protocol-id "sew-v1"}
              :scenario {:scenario-id "S01"}}
             {:scenario-id "S02"
              :name "S02 [inline]"
              :pass? true
              :outcome :pass
              :halt-reason nil
              :checks []
              :violations {}
              :dispatcher-id :protocol/sew-v1
              :expected-fail? false
              :scenario-path nil
              :scenario-hash "456ghi789jklhash"
              :replay-result {:outcome :pass}
              :execution/raw {:outcome :pass}
              :runner {:backend :local-current :protocol-id "sew-v1"}
              :scenario {:scenario-id "S02"}}
             {:scenario-id "S03"
              :name "S03 [inline]"
              :pass? false
              :outcome :fail
              :halt-reason "theory violation"
              :checks [{:check/id :theory-check :check/status :fail}]
              :violations {:theory-mismatch {:expected :pass :actual :fail}}
              :dispatcher-id :protocol/sew-v1
              :expected-fail? true
              :scenario-path nil
              :scenario-hash "mno012pqr345hash"
              :replay-result {:outcome :fail :halt-reason "theory violation"}
              :execution/raw {:outcome :fail :halt-reason "theory violation"}
              :runner {:backend :local-current :protocol-id "sew-v1"}
              :scenario {:scenario-id "S03"}}]
   :summary {:passed 3 :total 3 :elapsed-ms 150 :ok? true}
   :diagnostics {:elapsed-ms 150 :suite-id :sew-invariants}})

(deftest build-overview-keeps-only-stable-keys
  (let [overview (overview/build-overview sample-run-result)
        first-result (first (:results overview))]
    (is (= "run-overview.v1" (:overview/schema-version overview)))
    (is (= 3 (:scenario-count (:suite overview))))
    (is (= 3 (count (:results overview))))
    ;; Volatile fields stripped from per-scenario results
    (is (nil? (:replay-result first-result)))
    (is (nil? (:execution/raw first-result)))
    (is (nil? (:runner first-result)))
    (is (nil? (:scenario first-result)))
    (is (nil? (:scenario-path first-result)))
    ;; Stable fields preserved
    (is (= "S01" (:scenario-id first-result)))
    (is (= "abc123def456hash" (:scenario-hash first-result)))
    (is (true? (:pass? first-result)))
    (is (= :protocol/sew-v1 (:dispatcher-id first-result)))
    ;; Suite does not contain runner-selection (runner-agnostic)
    (is (nil? (:runner-selection (:suite overview))))))

(deftest build-overview-computes-correct-totals
  (let [overview (overview/build-overview sample-run-result)]
    (is (= {:passed 2 :failed 1 :total 3
            :expected-failed 1 :unexpected-failed 0}
           (:totals overview)))))

(deftest build-overview-expected-failed-in-unexpected-failed
  (let [run-result (-> sample-run-result
                       (assoc-in [:results 0 :pass?] false)
                       (assoc-in [:results 0 :expected-fail?] true))
        overview (overview/build-overview run-result)]
    (is (= {:passed 1 :failed 2 :total 3
            :expected-failed 2 :unexpected-failed 0}
           (:totals overview)))))

(deftest build-overview-unexpected-failure-counted
  (let [run-result (-> sample-run-result
                       (assoc-in [:results 2 :expected-fail?] false))
        overview (overview/build-overview run-result)]
    (is (= {:passed 2 :failed 1 :total 3
            :expected-failed 0 :unexpected-failed 1}
           (:totals overview)))))

(deftest overview-hash-is-deterministic
  (let [o1 (overview/build-overview sample-run-result)
        o2 (overview/build-overview sample-run-result)
        h1 (overview/overview-hash o1)
        h2 (overview/overview-hash o2)]
    (is (string? h1))
    (is (= 64 (count h1)))
    (is (= h1 h2))))

(deftest overview-hash-changes-when-results-differ
  (let [o1 (overview/build-overview sample-run-result)
        different-results (assoc-in sample-run-result [:results 0 :pass?] false)
        o2 (overview/build-overview different-results)
        h1 (overview/overview-hash o1)
        h2 (overview/overview-hash o2)]
    (is (not= h1 h2))))

(deftest overview-hash-strips-volatile-fields
  (let [o1 (overview/build-overview sample-run-result)
        ;; Different timing/runner metadata should not affect hash
        variant (assoc sample-run-result
                       :diagnostics {:elapsed-ms 999}
                       :summary (assoc (:summary sample-run-result) :elapsed-ms 999))
        o2 (overview/build-overview variant)]
    (is (= (overview/overview-hash o1)
           (overview/overview-hash o2)))))

(deftest overview-hash-is-runner-agnostic
  (let [o1 (overview/build-overview sample-run-result)
        ;; Different runner-selection should not affect hash
        variant (assoc sample-run-result
                       :runner-selection {:mode :pinned
                                          :runner-id :runner/local-clojure})
        o2 (overview/build-overview variant)]
    (is (= (overview/overview-hash o1)
           (overview/overview-hash o2)))))

(deftest overview-hash-detects-suite-key-change
  (let [o1 (overview/build-overview sample-run-result)
        variant (assoc sample-run-result :suite/key :yield-provider-scenarios)
        o2 (overview/build-overview variant)]
    (is (not= (overview/overview-hash o1)
              (overview/overview-hash o2)))))


