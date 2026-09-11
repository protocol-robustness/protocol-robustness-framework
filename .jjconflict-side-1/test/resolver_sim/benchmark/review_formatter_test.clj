(ns resolver-sim.benchmark.review-formatter-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.review-formatter :as formatter]
            [resolver-sim.benchmark.reversal-audit :as reversal-audit]))

(def sample-bundle
  {:benchmark {:benchmark/id :benchmark/example-v1
               :benchmark/status :experimental
               :benchmark/protocol :protocol/sew
               :benchmark/scenario-suite :suite/example
               :benchmark/purpose "Exercise a small declared workload."
               :benchmark/experimental-reason "Closed-form allocation coverage is incomplete."}
   :metrics {:total 2
             :passed 2
             :execution-count 2
             :unique-scenario-count 1
             :declared-run-count 2}
   :results [{:scenario/id "example-scenario"
              :simulator/scenario-path "scenarios/edn/S01_example.edn"
              :benchmark/run-index 1
              :outcome :pass
              :metrics {:events-processed 3}
              :scenario/evidence-root "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
             {:scenario/id "example-scenario"
              :simulator/scenario-path "scenarios/edn/S01_example.edn"
              :benchmark/run-index 2
              :outcome :pass
              :metrics {:events-processed 3}
              :scenario/evidence-root "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]
   :claim-results [{:claim/id :claim/example
                    :claim/scope :benchmark
                    :claim/outcome :pass
                    :claim/severity :low
                    :claim/evidence [:paired-results]}]
   :repo {:repo {:commit "abc123" :dirty? false}}
   :run/manifest {:manifest/at "2026-07-13T12:00:00Z"}
   :evidence/hash "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"})

(deftest renders-a-scope-aware-summary
  (let [summary (formatter/render-summary sample-bundle)]
    (is (.contains summary "# Benchmark Summary — :benchmark/example-v1"))
    (is (.contains summary "This benchmark is marked **experimental**"))
    (is (.contains summary "2/2 passed"))
    (is (.contains summary "does not by itself establish comprehensive protocol assurance"))))

(deftest renders-executions-and-claims-without-inventing-families
  (let [scenarios (formatter/render-scenario-results sample-bundle)
        claims (formatter/render-claim-results sample-bundle)]
    (is (.contains scenarios "Each row is one **execution**"))
    (is (.contains scenarios "Source directory: `scenarios/edn`"))
    (is (= 2 (count (re-seq #"\| example-scenario \|" scenarios))))
    (is (.contains claims "Claim outcomes are independent from scenario outcomes"))
    (is (.contains claims "`:not-exercised`, `:not-implemented`, and `:inconclusive` are not passes"))))

(deftest writes-the-three-review-files
  (let [dir (doto (java.io.File/createTempFile "benchmark-review-" "")
              (.delete)
              (.mkdirs))
        paths (formatter/write-review! sample-bundle (.getPath dir))]
    (try
      (testing "all reviewer files are emitted"
        (is (.exists (java.io.File. (:summary paths))))
        (is (.exists (java.io.File. (:scenarios paths))))
        (is (.exists (java.io.File. (:claims paths))))
        (is (.exists (java.io.File. (:reversals paths)))))
      (finally
        (doseq [file (reverse (file-seq dir))]
          (.delete file))))))

(deftest projects-active-and-cleaned-up-reversal-slashes
  (let [world {:previous-decisions {0 {0 {:resolver "0xl0" :is-release true}
                                       1 {:resolver "0xl1" :is-release false}}}
               :dispute-levels {0 1}
               :pending-fraud-slashes {0
                                       {:slash/id 0
                                        :slash/workflow-id 0
                                        :slash/kind :reversal
                                        :slash/level 0
                                        :reason :reversal
                                        :workflow-id 0
                                        :level 0
                                        :resolver "0xl0"
                                        :status :appealed
                                        :amount 2500
                                        :appeal-deadline 1200
                                        :appeal-bond-held 100}}
               :reversal-slash-history {1
                                        {:slash/id 1
                                         :slash/workflow-id 1
                                         :slash/kind :reversal
                                         :slash/level 0
                                         :reason :reversal
                                         :workflow-id 1
                                         :level 0
                                         :resolver "0xl0"
                                         :status :expired-cleaned-up
                                         :amount 2500
                                         :cleanup-at 1300
                                         :cleanup-reason :appeal-window-expired}}}
        entries (reversal-audit/reversal-entries world)
        markdown (reversal-audit/render-markdown world)]
    (is (= 2 (count entries)))
    (is (= :new-evidence-pending (:track (first entries))))
    (is (= :expired-cleaned-up (:status (second entries))))
    (is (:archived? (second entries)))
    (is (.contains markdown "Reversal Slash Audit"))
    (is (.contains markdown "expired-cleaned-up"))))
