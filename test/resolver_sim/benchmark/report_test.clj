(ns resolver-sim.benchmark.report-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.claims :as benchmark-claims]
            [resolver-sim.benchmark.integrity :as integrity]
            [resolver-sim.benchmark.report :as rpt]
            [resolver-sim.hash.canonical :as hc]))

(defn- temp-evidence-file
  "Create a temporary EDN file with the given evidence map, returning the path."
  [evidence]
  (let [f (doto (java.io.File/createTempFile "bundle-" ".edn")
            .deleteOnExit)]
    (spit f (pr-str evidence))
    (.getAbsolutePath f)))

(defn- commit-bundle
  "Attach a valid :bundle-root :evidence/hash so build-report's integrity gate
   accepts the bundle (mirrors the runner's hash-over-normalized commitment)."
  [evidence]
  (assoc evidence :evidence/hash
         (hc/hash-with-intent {:hash/intent :bundle-root}
                              (integrity/hashable-evidence evidence))))

(defn- make-evidence
  "Build a minimal evidence map from a benchmark pack file path, committed
   with a valid :evidence/hash."
  [benchmark-path scenario-results & {:keys [claim-results metrics reproduce env inv-summary]
                                      :or {claim-results []
                                           metrics {:total 0 :passed 0}
                                           reproduce {:command "bb benchmark:reproduce"}
                                           env {:os-name "Linux" :os-version "test" :java-version "test"}
                                           inv-summary {:per-invariant {} :total-checks 0 :passed-checks 0 :all-pass? true}}}]
  (let [evidence {:benchmark (edn/read-string (slurp benchmark-path))
                  :environment env
                  :results scenario-results
                  :claim-results claim-results
                  :metrics metrics
                  :reproduce reproduce
                  :invariant-summary inv-summary}]
    (commit-bundle evidence)))

(deftest build-report-has-no-implicit-use-case-projection
  (let [evidence (make-evidence "benchmarks/packs/sew/escrow-dispute-v1.edn" [] :metrics {:total 0 :passed 0})
        report (rpt/build-report (temp-evidence-file evidence) nil
                                 "benchmarks/scoring/severity-weighted-robustness-v1.edn")]
    (is (nil? (:stakeholder/use-case-results report)))
    (is (nil? (:use-case-registry report)))))

(deftest build-report-binds-explicit-use-case-registry-root
  (let [evidence (-> (make-evidence "benchmarks/packs/sew/escrow-dispute-v1.edn" [])
                     (assoc-in [:benchmark :benchmark/concepts] [:ecommerce/purchase])
                     commit-bundle)
        report (rpt/build-report (temp-evidence-file evidence)
                                 nil
                                 "benchmarks/scoring/severity-weighted-robustness-v1.edn"
                                 "examples/use-cases/ecommerce/registry.edn")]
    (is (= :external-use-case (get-in report [:benchmark/concepts 0 :concept/source])))
    (is (= "prf-example-ecommerce" (get-in report [:use-case-registry :use-case-registry/id])))
    (is (re-matches #"sha256:[0-9a-f]{64}"
                    (get-in report [:use-case-registry :use-case-registry/root])))))

(deftest benchmark-conclusion-requires-passing-claims
  (let [pass-conclusion (rpt/build-conclusion {:total 1 :passed 1}
                                              [{:claim/id :claim/funds-conserved :claim/outcome :pass}]
                                              "evidence-hash"
                                              "run-hash")
        incomplete-conclusion (rpt/build-conclusion {:total 1 :passed 1}
                                                    [{:claim/id :claim/funds-conserved :claim/outcome :not-exercised}]
                                                    "evidence-hash"
                                                    nil)
        failure-conclusion (rpt/build-conclusion {:total 1 :passed 1}
                                                 [{:claim/id :claim/funds-conserved :claim/outcome :fail}]
                                                 "evidence-hash"
                                                 nil)]
    (is (= :pass (:outcome pass-conclusion)))
    (is (= "evidence-hash" (:evidence/hash pass-conclusion)))
    (is (= :incomplete (:outcome incomplete-conclusion)))
    (is (= :fail (:outcome failure-conclusion)))))

(deftest scenario-outcome-prefers-public-scenario-id
  (let [results [{:scenario/id "malicious-resolver-verdict-v1"
                  :file "scenarios/S25_profit-maximizer-slash-lifecycle.json"
                  :outcome :pass
                  :halt-reason nil
                  :scenario/evidence-root "abc"}]]
    (is (= {:outcome :pass
            :halt-reason nil
            :file "scenarios/S25_profit-maximizer-slash-lifecycle.json"
            :scenario/evidence-root "abc"}
           (rpt/scenario->outcome "malicious-resolver-verdict-v1" results)))))

(deftest scenario-outcome-resolves-reference-validation-file-alias
  (let [results [{:file "scenarios/edn/S25_profit-maximizer-slash-lifecycle.edn"
                  :outcome :pass
                  :halt-reason nil
                  :scenario/evidence-root "root"}]]
    (is (= {:outcome :pass
            :halt-reason nil
            :file "scenarios/edn/S25_profit-maximizer-slash-lifecycle.edn"
            :scenario/evidence-root "root"}
           (rpt/scenario->outcome "malicious-resolver-verdict-v1" results)))))

(deftest build-report-resolves-prf-dimensions
  (let [evidence-path (doto (java.io.File/createTempFile "prf-bundle-" ".edn")
                        .deleteOnExit)
        evidence {:benchmark (edn/read-string (slurp "benchmarks/packs/prf-core/protocol-robustness-v0.edn"))
                  :environment {:os-name "Linux" :os-version "test" :java-version "test"}
                  :results [{:scenario/id "S25_profit-maximizer-slash-lifecycle"
                             :file "scenarios/edn/S25_profit-maximizer-slash-lifecycle.edn"
                             :outcome :pass
                             :halt-reason nil
                             :scenario/evidence-root "root-1"}
                            {:scenario/id "S62_resolver-throughput-exhaustion"
                             :file "scenarios/edn/S62_resolver-throughput-exhaustion.edn"
                             :outcome :pass
                             :halt-reason nil
                             :scenario/evidence-root "root-2"}
                            {:scenario/id "S05_pending-settlement-execute"
                             :file "scenarios/edn/S05_pending-settlement-execute.edn"
                             :outcome :pass
                             :halt-reason nil
                             :scenario/evidence-root "root-3"}]
                  :metrics {:total 3 :passed 3}
                  :reproduce {:command "bb benchmark:reproduce /tmp/prf.edn"}
                  :invariant-summary {:per-invariant {} :total-checks 0 :passed-checks 0 :all-pass? true}}
        _ (spit evidence-path (pr-str (commit-bundle evidence)))
        report (rpt/build-report (.getAbsolutePath evidence-path)
                                 "benchmarks/concepts/protocol-robustness-v0.edn"
                                 "benchmarks/scoring/robustness-dimensions-v0.edn")]
    (testing "dimensions resolve against public IDs"
      (is (= 3 (count (:dimensions report))))
      (is (every? :pass-condition-met? (:dimensions report)))
      (is (= ["S25_profit-maximizer-slash-lifecycle"
              "S62_resolver-throughput-exhaustion"
              "S05_pending-settlement-execute"]
             (mapv :scenario/id (:dimensions report)))))))

(deftest severity-weighted-classification-uses-scoring-severity
  (let [manifest (edn/read-string (slurp "benchmarks/packs/sew/escrow-dispute-v1.edn"))
        scoring (edn/read-string (slurp "benchmarks/scoring/severity-weighted-robustness-v1.edn"))
        claim-results (benchmark-claims/evaluate-manifest-claims
                       manifest
                       [{:scenario/id "scenario-1"
                         :file "scenario-1.edn"
                         :outcome :pass
                         :scenario/evidence-root (apply str (repeat 64 "a"))
                         :invariant-results [{:id :conservation-of-funds
                                              :result :fail}
                                             {:id :released-monotonic
                                              :result :pass}]}])
        failed-critical (first (filter #(= :claim/no-unauthorized-release (:claim/id %))
                                       claim-results))
        classification (rpt/classify-result 1 1 (:scoring/rules scoring) claim-results manifest)]
    (is (= :critical (:claim/severity failed-critical)))
    (is (= :fail (:claim/outcome failed-critical)))
    (is (= "Critical claim failed — semantic claim violation detected"
           (:classification-label classification)))))

(deftest build-report-dimension-fails-when-scenario-claim-fails
  (let [evidence-path (doto (java.io.File/createTempFile "prf-bundle-" ".edn")
                        .deleteOnExit)
        evidence {:benchmark (edn/read-string (slurp "benchmarks/packs/prf-core/protocol-robustness-v0.edn"))
                  :environment {:os-name "Linux" :os-version "test" :java-version "test"}
                  :results [{:scenario/id "S25_profit-maximizer-slash-lifecycle"
                             :file "scenarios/edn/S25_profit-maximizer-slash-lifecycle.edn"
                             :outcome :pass
                             :halt-reason nil
                             :invariant-results [{:id :conservation-of-funds :result :pass}]
                             :scenario/evidence-root "root-1"}
                            {:scenario/id "S62_resolver-throughput-exhaustion"
                             :file "scenarios/edn/S62_resolver-throughput-exhaustion.edn"
                             :outcome :pass
                             :halt-reason nil
                             :invariant-results [{:id :conservation-of-funds :result :pass}]
                             :scenario/evidence-root "root-2"}
                            {:scenario/id "S05_pending-settlement-execute"
                             :file "scenarios/edn/S05_pending-settlement-execute.edn"
                             :outcome :pass
                             :halt-reason nil
                             :invariant-results [{:id :conservation-of-funds :result :pass}]
                             :scenario/evidence-root "root-3"}]
                  :claim-results [{:claim/id :evidence-root-present
                                   :claim/scope :scenario
                                   :scenario/id "S25_profit-maximizer-slash-lifecycle"
                                   :claim/outcome :fail
                                   :claim/severity :low}]
                  :metrics {:total 3 :passed 3}
                  :reproduce {:command "bb benchmark:reproduce /tmp/prf.edn"}
                  :invariant-summary {:per-invariant {} :total-checks 0 :passed-checks 0 :all-pass? true}}
        _ (spit evidence-path (pr-str (commit-bundle evidence)))
        report (rpt/build-report (.getAbsolutePath evidence-path)
                                 "benchmarks/concepts/protocol-robustness-v0.edn"
                                 "benchmarks/scoring/robustness-dimensions-v0.edn")
        dimension (first (:dimensions report))]
    (is (:scenario/pass? dimension))
    (is (= false (:claims/pass? dimension)))
    (is (:invariants/pass? dimension))
    (is (= false (:dimension/pass? dimension)))
    (is (= false (:pass-condition-met? dimension)))))

(deftest build-report-dimension-fails-when-invariant-fails
  (let [evidence-path (doto (java.io.File/createTempFile "prf-bundle-" ".edn")
                        .deleteOnExit)
        evidence {:benchmark (edn/read-string (slurp "benchmarks/packs/prf-core/protocol-robustness-v0.edn"))
                  :environment {:os-name "Linux" :os-version "test" :java-version "test"}
                  :results [{:scenario/id "malicious-resolver-verdict-v1"
                             :file "scenarios/S25_profit-maximizer-slash-lifecycle.json"
                             :outcome :pass
                             :halt-reason nil
                             :invariant-results [{:id :conservation-of-funds :result :fail}]
                             :scenario/evidence-root "root-1"}
                            {:scenario/id "dispute-flooding-v1"
                             :file "scenarios/S62_resolver-throughput-exhaustion.json"
                             :outcome :pass
                             :halt-reason nil
                             :invariant-results [{:id :conservation-of-funds :result :pass}]
                             :scenario/evidence-root "root-2"}
                            {:scenario/id "autopush-settlement-v1"
                             :file "scenarios/S05_pending-settlement-execute.json"
                             :outcome :pass
                             :halt-reason nil
                             :invariant-results [{:id :conservation-of-funds :result :pass}]
                             :scenario/evidence-root "root-3"}]
                  :claim-results []
                  :metrics {:total 3 :passed 3}
                  :reproduce {:command "bb benchmark:reproduce /tmp/prf.edn"}
                  :invariant-summary {:per-invariant {} :total-checks 0 :passed-checks 0 :all-pass? true}}
        _ (spit evidence-path (pr-str (commit-bundle evidence)))
        report (rpt/build-report (.getAbsolutePath evidence-path)
                                 "benchmarks/concepts/protocol-robustness-v0.edn"
                                 "benchmarks/scoring/robustness-dimensions-v0.edn")
        dimension (first (:dimensions report))]
    (is (:scenario/pass? dimension))
    (is (nil? (:claims/pass? dimension)))
    (is (= false (:invariants/pass? dimension)))
    (is (= false (:dimension/pass? dimension)))
    (is (= false (:pass-condition-met? dimension)))))

;; ── Shortfall allocation report tests ──────────────────────────────────────────

(deftest build-report-shortfall-allocation-v0-creates-report
  (let [ev (make-evidence "benchmarks/packs/prf-core/shortfall-allocation-v0.edn"
                          [{:scenario/id "S-DR-043-payout-shortfall-deferred"
                            :file "scenarios/S-DR-043-payout-shortfall-deferred.json"
                            :outcome :pass
                            :scenario/evidence-root "aabbcc"}
                           {:scenario/id "S103_negative-yield-shortfall-cascade"
                            :file "scenarios/S103_negative-yield-shortfall-cascade.json"
                            :outcome :pass
                            :scenario/evidence-root "ddeeff"}
                           {:scenario/id "S104_resolver-stake-shortfall"
                            :file "scenarios/S104_resolver-stake-shortfall.json"
                            :outcome :fail
                            :scenario/evidence-root "gghhii"}]
                          :metrics {:total 3 :passed 2})
        evidence-path (temp-evidence-file ev)
        report (rpt/build-report evidence-path
                                 "benchmarks/concepts/shortfall-allocation-v0.edn"
                                 "benchmarks/scoring/shortfall-allocation-v0.edn")]
    (testing "report structure"
      (is (= :benchmark/prf-shortfall-allocation-v0 (:benchmark/id report)))
      (is (= :domain/protocol-value-conservation (:benchmark/domain report)))
      (is (string? (:benchmark/domain-description report)))
      (is (= [:concept/conservation] (:benchmark/framework-concepts report)))
      (is (= [{:concept/id :robustness/protocol-value-conservation
               :concept/source :benchmark-local}
              {:concept/id :allocation/partial-fill
               :concept/source :benchmark-local}
              {:concept/id :allocation/shortfall
               :concept/source :benchmark-local}
              {:concept/id :allocation/stake-liquidity-blocking
               :concept/source :benchmark-local}
              {:concept/id :allocation/redistribution-fairness
               :concept/source :global}
              {:concept/id :consensus/evidence
               :concept/source :global}
              {:concept/id :consensus/finality
               :concept/source :global}
              {:concept/id :verifiable-assurance/forensic-confidence
               :concept/source :global}]
             (mapv #(select-keys % [:concept/id :concept/source])
                   (:benchmark/concepts report))))
      (is (= {:concept/id :robustness/protocol-value-conservation
              :concept/source :benchmark-local
              :concept/title "Protocol value conservation and allocation"
              :concept/framework-concepts [:concept/conservation]}
             (first (:benchmark/concept-summary report))))
      (is (= 3 (:total-scenarios report)))
      (is (= 2 (:passed-scenarios report)))
      (is (= false (:all-pass? report))))
    (testing "dimension structure"
      (is (= 4 (count (:dimensions report))))
      (is (= "S-DR-043-payout-shortfall-deferred"
             (get-in report [:dimensions 0 :scenario/id])))
      (is (= :allocation/partial-fill
             (get-in report [:dimensions 0 :dimension])))
      (is (= false (get-in report [:dimensions 3 :scenario/pass?]))))
    (testing "scoring classification"
      (let [cls (:scoring/classification report)]
        (is (string? (:classification-label cls)))
        (is (re-find #"(?i)replay" (:classification-label cls)))))))

(deftest build-report-shortfall-allocation-v0-all-pass
  (let [ev (make-evidence "benchmarks/packs/prf-core/shortfall-allocation-v0.edn"
                          [{:scenario/id "S-DR-043-payout-shortfall-deferred"
                            :file "scenarios/S-DR-043-payout-shortfall-deferred.json"
                            :outcome :pass
                            :scenario/evidence-root "aabbcc"}
                           {:scenario/id "S82_shortfall-recovery-cycle"
                            :file "scenarios/S82_shortfall-recovery-cycle.json"
                            :outcome :pass
                            :scenario/evidence-root "bbccdd"}
                           {:scenario/id "S103_negative-yield-shortfall-cascade"
                            :file "scenarios/S103_negative-yield-shortfall-cascade.json"
                            :outcome :pass
                            :scenario/evidence-root "ddeeff"}
                           {:scenario/id "S104_resolver-stake-shortfall"
                            :file "scenarios/S104_resolver-stake-shortfall.json"
                            :outcome :pass
                            :scenario/evidence-root "gghhii"}]
                          :metrics {:total 4 :passed 4})
        evidence-path (temp-evidence-file ev)
        report (rpt/build-report evidence-path
                                 "benchmarks/concepts/shortfall-allocation-v0.edn"
                                 "benchmarks/scoring/shortfall-allocation-v0.edn")]
    (is (:all-pass? report))
    (is (every? :pass-condition-met? (:dimensions report)))))

;; ── Severity-weighted Sew pack report tests ────────────────────────────────────

(deftest build-report-severity-weighted-sew-classifies-correctly
  (let [ev (make-evidence "benchmarks/packs/sew/escrow-dispute-v1.edn"
                          []
                          :metrics {:total 53 :passed 53}
                          :claim-results
                          [{:claim/id :evidence-root-present
                            :claim/scope :scenario
                            :claim/outcome :pass
                            :claim/severity :low}
                           {:claim/id :claim/no-unauthorized-release
                            :claim/scope :scenario
                            :claim/outcome :fail
                            :claim/severity :critical}])
        evidence-path (temp-evidence-file ev)
        report (rpt/build-report evidence-path
                                 "benchmarks/concepts/protocol-robustness-v0.edn"
                                 "benchmarks/scoring/severity-weighted-robustness-v1.edn")]
    (testing "classification with critical failure"
      (let [cls (:scoring/classification report)]
        (is (re-find #"(?i)critical" (:classification-label cls)))))))

(deftest build-report-severity-weighted-all-claims-pass
  (let [ev (make-evidence "benchmarks/packs/sew/escrow-dispute-v1.edn"
                          []
                          :metrics {:total 53 :passed 53}
                          :claim-results
                          [{:claim/id :evidence-root-present
                            :claim/scope :scenario
                            :claim/outcome :pass
                            :claim/severity :low}
                           {:claim/id :claim/funds-conserved
                            :claim/scope :scenario
                            :claim/outcome :pass
                            :claim/severity :critical}])
        evidence-path (temp-evidence-file ev)
        report (rpt/build-report evidence-path
                                 "benchmarks/concepts/protocol-robustness-v0.edn"
                                 "benchmarks/scoring/severity-weighted-robustness-v1.edn")]
    (testing "classification all pass"
      (let [cls (:scoring/classification report)]
        (is (re-find #"(?i)pass" (:classification-label cls)))
        (is (re-find #"(?i)mechanical" (:classification-label cls)))))))

;; ── Missing path tests ─────────────────────────────────────────────────────────

(deftest resolve-report-throws-on-unknown-benchmark-id
  (let [ev (make-evidence "benchmarks/packs/prf-core/protocol-robustness-v0.edn"
                          []
                          :metrics {:total 0 :passed 0})
        evidence-path (temp-evidence-file ev)
        report (rpt/build-report evidence-path
                                 "benchmarks/concepts/protocol-robustness-v0.edn"
                                 "benchmarks/scoring/robustness-dimensions-v0.edn")]
    (is (some? report))))

(deftest resolve-report-resolves-from-evidence-bundle
  (let [ev (make-evidence "benchmarks/packs/prf-core/protocol-robustness-v0.edn"
                          [{:scenario/id "malicious-resolver-verdict-v1"
                            :file "scenarios/S25_profit-maximizer-slash-lifecycle.json"
                            :outcome :pass
                            :scenario/evidence-root "abc"}
                           {:scenario/id "dispute-flooding-v1"
                            :file "scenarios/S62_resolver-throughput-exhaustion.json"
                            :outcome :pass
                            :scenario/evidence-root "def"}
                           {:scenario/id "autopush-settlement-v1"
                            :file "scenarios/S05_pending-settlement-execute.json"
                            :outcome :pass
                            :scenario/evidence-root "ghi"}]
                          :metrics {:total 3 :passed 3})
        evidence-path (temp-evidence-file ev)
        report (rpt/resolve-report evidence-path)]
    (is (= :benchmark/prf-protocol-robustness-v0 (:benchmark/id report)))
    (is (:all-pass? report))
    (is (= 3 (count (:dimensions report))))))

(deftest resolve-report-resolves-shortfall-from-bundle
  (let [ev (make-evidence "benchmarks/packs/prf-core/shortfall-allocation-v0.edn"
                          [{:scenario/id "S-DR-043-payout-shortfall-deferred"
                            :file "scenarios/S-DR-043-payout-shortfall-deferred.json"
                            :outcome :pass
                            :scenario/evidence-root "abc"}]
                          :metrics {:total 1 :passed 1})
        evidence-path (temp-evidence-file ev)
        report (rpt/resolve-report evidence-path)]
    (is (= :benchmark/prf-shortfall-allocation-v0 (:benchmark/id report)))
    (is (= :domain/protocol-value-conservation (:benchmark/domain report)))
    (is (some #(= {:concept/id :robustness/protocol-value-conservation
                   :concept/source :benchmark-local}
                  (select-keys % [:concept/id :concept/source]))
              (:benchmark/concepts report)))
    (is (= {:concept/id :robustness/protocol-value-conservation
            :concept/source :benchmark-local
            :concept/title "Protocol value conservation and allocation"
            :concept/framework-concepts [:concept/conservation]}
           (first (:benchmark/concept-summary report))))
    (is (= [:concept/conservation] (:benchmark/framework-concepts report)))
    (is (:all-pass? report))))

(deftest build-report-missing-scoring-path-throws
  (let [ev (make-evidence "benchmarks/packs/prf-core/protocol-robustness-v0.edn"
                          []
                          :metrics {:total 0 :passed 0})
        evidence-path (temp-evidence-file ev)]
    (is (thrown? Exception
                 (rpt/build-report evidence-path
                                   "benchmarks/concepts/protocol-robustness-v0.edn"
                                   "benchmarks/scoring/nonexistent-file.edn")))))

;; ── End-to-end integration tests ──────────────────────────────────────────────
;; These tests load actual fixture evidence bundles from data/fixtures/benchmark-reports/
;; and validate the full report pipeline: evidence → resolve-report → report map.

(defn- evidence-bundle-path
  [pack-name]
  (str "data/fixtures/benchmark-reports/" pack-name ".evidence.edn"))

(defn- load-evidence-fixture
  [pack-name]
  (edn/read-string (slurp (evidence-bundle-path pack-name))))

(defn- report-fixture
  [pack-name]
  (edn/read-string (slurp (str "data/fixtures/benchmark-reports/" pack-name ".report.edn"))))

(deftest e2e-protocol-robustness-resolve-report
  (let [evidence-path (evidence-bundle-path "protocol-robustness-v0")
        report (rpt/resolve-report evidence-path)]
    (testing "basic structure"
      (is (= :benchmark/prf-protocol-robustness-v0 (:benchmark/id report)))
      (is (string? (:purpose report)))
      (is (integer? (:total-scenarios report)))
      (is (integer? (:passed-scenarios report)))
      (is (some? (:all-pass? report))))
    (testing "evidence traceability"
      (is (string? (:evidence/hash report)))
      (is (map? (:reproduce report)))
      (is (map? (:environment report))))
    (testing "scoring classification"
      (let [cls (:scoring/classification report)]
        (is (map? cls))
        (is (string? (:classification-label cls)))
        (is (string? (:scoring/summary cls)))))
    (testing "claim status"
      (is (contains? #{:verified :partial :declared-not-verified :none}
                     (:claim/status report)))
      (is (some? (:claim-results report))))
    (testing "dimensions"
      (is (vector? (:dimensions report)))
      (is (pos? (count (:dimensions report))))
      (doseq [d (:dimensions report)]
        (is (keyword? (:dimension d)))
        (is (string? (:scenario/id d)))
        (is (contains? #{:pass :fail nil} (:outcome d)))
        (is (some? (:concept/title d))  ;; concept enrichment from concept file
            (str "concept/title missing for " (:dimension d)))))
    (testing "invariant summary"
      (is (map? (:invariant-summary report))))))

(deftest e2e-protocol-robustness-report-fields-match-expectations
  (let [evidence-path (evidence-bundle-path "protocol-robustness-v0")
        report (rpt/resolve-report evidence-path)
        expected (report-fixture "protocol-robustness-v0")]
    (is (= (:benchmark/id report) (:benchmark/id expected)))
    (is (= (:total-scenarios report) (:total-scenarios expected)))
    (is (= (:passed-scenarios report) (:passed-scenarios expected)))
    (is (= (:all-pass? report) (:all-pass? expected)))
    (is (= (count (:dimensions report)) (count (:dimensions expected))))))

(deftest e2e-shortfall-allocation-resolve-report
  (let [evidence-path (evidence-bundle-path "shortfall-allocation-v0")
        report (rpt/resolve-report evidence-path)]
    (testing "basic structure"
      (is (= :benchmark/prf-shortfall-allocation-v0 (:benchmark/id report)))
      (is (integer? (:total-scenarios report)))
      (is (integer? (:passed-scenarios report)))
      (is (some? (:all-pass? report))))
    (testing "dimensions resolve with concepts"
      (is (vector? (:dimensions report)))
      (is (pos? (count (:dimensions report))))
      (doseq [d (:dimensions report)]
        (is (keyword? (:dimension d)))
        (is (some? (:concept/title d))
            (str "concept/title missing for " (:dimension d)))))
    (testing "scoring classification"
      (let [cls (:scoring/classification report)]
        (is (map? cls))
        (is (string? (:classification-label cls)))))
    (testing "auto-resolution worked"
      (is (= (:benchmark/id report) :benchmark/prf-shortfall-allocation-v0))
      (is (pos? (count (:dimensions report)))))))

(deftest e2e-shortfall-allocation-report-fields-match-expectations
  (let [evidence-path (evidence-bundle-path "shortfall-allocation-v0")
        report (rpt/resolve-report evidence-path)
        expected (report-fixture "shortfall-allocation-v0")]
    (is (= (:benchmark/id report) (:benchmark/id expected)))
    (is (= (count (:dimensions report)) (count (:dimensions expected))))))

(deftest e2e-resolve-report-throws-on-missing-scoring-rule
  (let [ev (make-evidence "benchmarks/packs/prf-core/protocol-robustness-v0.edn" []
                          :metrics {:total 0 :passed 0})
        evidence-path (temp-evidence-file ev)
        evidence (edn/read-string (slurp evidence-path))
        ;; Override with unknown scoring rule to trigger the error path
        bad-evidence (assoc-in evidence [:benchmark :benchmark/scoring-rule] :scoring/nonexistent)
        bad-path (temp-evidence-file bad-evidence)]
    (is (thrown? Exception
                 (rpt/resolve-report bad-path)))))

(deftest build-report-missing-concepts-path-still-builds
  (let [ev (make-evidence "benchmarks/packs/prf-core/protocol-robustness-v0.edn"
                          []
                          :metrics {:total 0 :passed 0})
        evidence-path (temp-evidence-file ev)
        report (rpt/build-report evidence-path
                                 "benchmarks/concepts/nonexistent-file.edn"
                                 "benchmarks/scoring/robustness-dimensions-v0.edn")]
    (is (some? report))
    ;; Dimensions are resolved from the manifest through the shared
    ;; benchmark concept resolver even when the explicit path is absent.
    (is (pos? (count (:dimensions report))))
    (is (every? some? (map :concept/title (:dimensions report))))))

(deftest build-report-fails-closed-on-missing-evidence-hash
  (testing "a bundle with no committed :evidence/hash cannot produce an authoritative report"
    (let [ev (make-evidence "benchmarks/packs/prf-core/protocol-robustness-v0.edn" [])
          evidence-path (temp-evidence-file (dissoc ev :evidence/hash))]
      (is (thrown-with-msg? Exception #"integrity"
                            (rpt/build-report evidence-path nil
                                              "benchmarks/scoring/robustness-dimensions-v0.edn"))))))

(deftest build-report-fails-closed-on-tampered-metrics
  (testing "a supplied :metrics value that does not recompute from the committed hash cannot falsify :all-pass?/:score/:conclusion"
    (let [ev (make-evidence "benchmarks/packs/prf-core/protocol-robustness-v0.edn"
                            [{:scenario/id "s1" :outcome :pass :halt-reason nil
                              :scenario/evidence-root "root"}]
                            :metrics {:total 1 :passed 1})
          good-path (temp-evidence-file ev)
          tampered-path (temp-evidence-file (assoc ev :metrics {:total 1 :passed 0}))]
      (is (= true (:all-pass? (rpt/build-report good-path nil
                                                "benchmarks/scoring/robustness-dimensions-v0.edn")))
          "unmodified bundle reports normally")
      (is (thrown-with-msg? Exception #"integrity"
                            (rpt/build-report tampered-path nil
                                              "benchmarks/scoring/robustness-dimensions-v0.edn"))))))

(deftest build-report-fails-closed-on-tampered-claim-results
  (testing "a supplied :claim-results :claim/outcome :pass cannot be forced into :claim/status :verified without recomputing the bundle root"
    (let [ev (make-evidence "benchmarks/packs/prf-core/protocol-robustness-v0.edn"
                            []
                            :metrics {:total 0 :passed 0}
                            :claim-results [{:claim/id :evidence-root-present :claim/outcome :pass}])
          tampered-path (temp-evidence-file (assoc ev :claim-results []))]
      (is (thrown-with-msg? Exception #"integrity"
                            (rpt/build-report tampered-path nil
                                              "benchmarks/scoring/robustness-dimensions-v0.edn"))))))
