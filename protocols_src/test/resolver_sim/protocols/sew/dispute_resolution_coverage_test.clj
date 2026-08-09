(ns resolver-sim.protocols.sew.dispute-resolution-coverage-test
  "Deterministic test suite for dispute resolution coverage scenarios (S-DR-*).
   Each scenario is loaded from scenarios/edn/S-DR-*.edn, normalized, replayed
   with the Sew protocol, and verified for:
     - Deterministic pass/fail outcome
     - Zero invariant violations (unless expected-fail?)
     - Expected error codes (when :expected-errors is declared)
   
   Researcher-readable artifacts are written to results/test-artifacts/.
   After all scenarios run, an evidence-summary.json artifact is produced
   that surfaces world-before/world-after hashes for every evidence record."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as cstr]
            [clojure.set :as cset]
            [clojure.data.json :as json]
            [resolver-sim.io.scenarios :as sc]
            [resolver-sim.scenario.normalize :as norm]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.scenario.runner :as runner]
            [resolver-sim.scenario.equilibrium :as equilibrium]
            [resolver-sim.scenario.dispute-coverage :as dc]
            [resolver-sim.evidence.summary :as ev-sum]
            [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.io.event-evidence :as ee])
  (:import [java.util.regex Pattern]))

;; ---------------------------------------------------------------------------
;; Evidence artifact fixture
;; ---------------------------------------------------------------------------

;; ── Replay cache ───────────────────────────────────────────────────────────
;; Many tests replay the same scenarios independently.  A shared cache cuts
;; ~335 replays to ~123 — scenario replay is deterministic by construction
;; (fixed seed, sequential processing), so caching is safe and preserves all
;; evidence side effects from the first replay.

(defonce replay-cache
  (atom {}))

(defn- replay-scenario
  "Replay a scenario with caching.  The first call per scenario-id runs
   the full replay (including evidence capture).  Subsequent calls return
   the cached result — scenario replay is deterministic by construction.
   Skips per-scenario chain finalization (the :once fixture handles it)."
  [scenario]
  (let [sid (:scenario-id scenario)]
    (or (when sid (get @replay-cache sid))
        (let [r (sew/replay-with-sew-protocol scenario
                  {:allow-dirty? true :skip-finalize true})]
          (when sid (swap! replay-cache assoc sid r))
          r))))

(defn clean-evidence-dir!
  "Remove all evidence files from the event-evidence directory."
  []
  (let [dir (str (evcfg/artifact-dir) "/event-evidence")
        f (io/file dir)]
    (when (.isDirectory f)
      (doseq [file (.listFiles f)]
        (.delete file)))
    (println "Cleaned evidence directory:" dir)))

(defn prepare-evidence-summary!
  "Build and persist the evidence-summary.json artifact from accumulated
   evidence files. Call after all scenario replays have completed."
  []
  (ev-sum/write-evidence-summary!)
  (println "Evidence summary artifact emitted"))

(defn evidence-lifecycle-fixture
  "Fixture: clean evidence before, emit summary after."
  [f]
  (clean-evidence-dir!)
  (f)
  (prepare-evidence-summary!))

(declare prepare-all-artifacts!)

;; Register the full evidence pipeline: reset cache + clean → tests → full artifacts + reconciliation
(use-fixtures :once (fn [f]
                      (reset! replay-cache {})
                      (clean-evidence-dir!)
                      (f)
                      (prepare-all-artifacts!)))

;; ---------------------------------------------------------------------------
;; Scenario file paths
;; ---------------------------------------------------------------------------

(def dr-scenario-paths
  "All S-DR-* dispute resolution scenario files, sorted."
  (sort (filter #(.contains % "S-DR-")
                (map #(str "scenarios/edn/" %)
                     (.list (io/file "scenarios/edn"))))))

(def reversal-scenario-ids
  "DR-N/O/P/Q/R reversal-reviewer slashing scenario IDs."
  ["dr-n-001-reversal-slash-appeal-lifecycle"
   "dr-n-002-reversal-slash-appeal-rejected"
   "dr-n-003-reversal-slash-appeal-window-expired"
   "dr-n-004-reversal-slash-appeal-wrong-party"
   "dr-o-001-vindication-4-level"
   "dr-o-002-vindication-minimum-stake"
   "dr-o-003-vindication-zero-stake"
   "dr-p-001-force-reversal-slash"
   "dr-p-002-force-reversal-slash-idempotent"
   "dr-q-001-challenge-bounty-reversal"
   "dr-q-002-challenge-bounty-no-challenger"
   "dr-r-001-reversal-slash-insufficient-stake"])

(defn- load-scenario [path]
  (-> path sc/load-scenario-file norm/normalize-scenario))

;; ---------------------------------------------------------------------------
;; Smoke test: all JSON files load without error
;; ---------------------------------------------------------------------------

(deftest test-all-dr-scenario-files-load
  (testing "All dispute resolution scenario files load without parse error"
    (let [results (for [path dr-scenario-paths]
                    (try {:path path :ok true :scenario (load-scenario path)}
                         (catch Exception e
                           {:path path :ok false :error (.getMessage e)})))]
      (doseq [r results]
        (is (:ok r) (str "Failed to load: " (:path r))))
      (is (pos? (count results)) "At least one S-DR-* scenario must exist"))))

;; ---------------------------------------------------------------------------
;; Smoke test: each scenario replays deterministically
;; ---------------------------------------------------------------------------

(deftest test-all-dr-scenarios-replay-deterministically
  (testing "Each S-DR scenario replays without error"
    (let [results (for [path dr-scenario-paths]
                    (try (let [s (load-scenario path)
                               r (replay-scenario s)]
                           {:scenario-id (:scenario-id s)
                            :ok true
                            :outcome (:outcome r)
                            :halt-reason (:halt-reason r)
                            :violations (get-in r [:metrics :invariant-violations] 0)})
                         (catch Exception e
                           {:scenario-id path :ok false :error (.getMessage e)})))]
      (doseq [r results]
        (is (:ok r) (str "Replay failed for " (:scenario-id r) ": " (:error r)))))))

;; ---------------------------------------------------------------------------
;; Test: expected-errors match actual reverts (via expected-reverts metric)
;; ---------------------------------------------------------------------------

(deftest test-dr-expected-errors
  (testing "Scenarios with :expected-errors have expected-reverts count > 0"
    (doseq [path dr-scenario-paths]
      (let [scenario (try (load-scenario path) (catch Exception _ nil))]
        (when (and scenario (seq (:expected-errors scenario)))
          (let [replay (replay-scenario scenario)
                expected-count (count (:expected-errors scenario))
                actual-expected (get-in replay [:metrics :expected-reverts] 0)
                actual-unexpected (get-in replay [:metrics :unexpected-reverts] 0)]
            (testing (str (:scenario-id scenario) " expected-errors")
              (is (pos? actual-expected)
                  (str (:scenario-id scenario) " expected " expected-count
                       " reverts but expected-reverts=" actual-expected
                       " unexpected-reverts=" actual-unexpected)))))))))

;; ---------------------------------------------------------------------------
;; Test: invariant violations are zero
;; ---------------------------------------------------------------------------

(deftest test-dr-no-invariant-violations
  (testing "All scenarios have zero invariant violations"
    (doseq [path dr-scenario-paths]
      (let [scenario (try (load-scenario path) (catch Exception _ nil))]
        (when scenario
          (let [replay (replay-scenario scenario)
                violations (get-in replay [:metrics :invariant-violations] 0)]
            (testing (str (:scenario-id scenario) " invariant violations")
              (is (zero? violations)
                  (str (:scenario-id scenario) " has " violations
                       " invariant violation(s)")))))))))

;; ---------------------------------------------------------------------------
;; Test: scenario tags exist
;; ---------------------------------------------------------------------------

(deftest test-dr-scenario-tags
  (testing "Each S-DR scenario has :suite/dispute-resolution and :coverage/* tags"
    (doseq [path dr-scenario-paths]
      (let [scenario (try (load-scenario path) (catch Exception _ nil))]
        (when scenario
          (let [tags (:tags scenario [])]
            (testing (str (:scenario-id scenario) " tags")
              (is (some #(= "suite/dispute-resolution" %) tags)
                  (str (:scenario-id scenario) " missing suite/dispute-resolution tag"))
              (is (some #(clojure.string/starts-with? % "coverage/") tags)
                  (str (:scenario-id scenario) " missing coverage/* tag")))))))))

;; ---------------------------------------------------------------------------
;; Test: deterministic outcome (same on two consecutive runs)
;; ---------------------------------------------------------------------------

(deftest test-dr-deterministic-outcome
  (testing "A representative S-DR scenario produces the same outcome on two consecutive runs"
    (let [path (first dr-scenario-paths)
          scenario (load-scenario path)
          run1 (sew/replay-with-sew-protocol scenario {:allow-dirty? true :skip-finalize true})
          run2 (sew/replay-with-sew-protocol scenario {:allow-dirty? true :skip-finalize true})
          outcome1 (:outcome run1)
          outcome2 (:outcome run2)]
      (is (= outcome1 outcome2)
          (str (:scenario-id scenario) " outcome differs between runs: "
               outcome1 " vs " outcome2)))))

;; ---------------------------------------------------------------------------
;; Test: Build entry result (runner compatibility)
;; ---------------------------------------------------------------------------

(deftest test-dr-build-entry-result
  (testing "Each S-DR scenario can produce a runner entry result"
    (doseq [path dr-scenario-paths]
      (let [scenario (try (load-scenario path) (catch Exception _ nil))]
        (when scenario
          (let [replay (replay-scenario scenario)
                entry (runner/build-entry-result
                       {:name (:scenario-id scenario)
                        :replay-result replay
                        :scenario scenario})]
            (testing (str (:scenario-id scenario) " entry shape")
              (is (contains? entry :pass?))
              (is (contains? entry :outcome))
              (is (contains? entry :steps))
              (is (contains? entry :violations)))))))))

;; ---------------------------------------------------------------------------
;; Coverage: minimum threshold per category
;; ---------------------------------------------------------------------------

(deftest test-reversal-slashing-coverage
  (testing "Reversal-slashing scenarios exist and have required structure"
    (let [scenarios (for [sid reversal-scenario-ids]
                      (try (load-scenario (str "scenarios/edn/" sid ".edn"))
                           (catch java.io.FileNotFoundException _
                             (println "MISSING:" sid) nil)))]
      (is (pos? (count (remove nil? scenarios)))
          (str "At least one reversal-slashing scenario should exist, found "
               (count (remove nil? scenarios)) " of " (count reversal-scenario-ids)))
      (doseq [s scenarios]
        (when s
          (testing (str (:scenario-id s) " structure")
            (is (string? (:scenario-id s)))
            (is (map? (:claim s)))
            (is (string? (:title s)))
            (is (or (nil? (:expected/events s)) (vector? (:expected/events s)))
                "expected/events should be a vector or nil")
            (is (or (nil? (:expected/invariants s)) (vector? (:expected/invariants s)))
                "expected/invariants should be a vector or nil")
            (is (map? (:threat s)))
            (is (map? (:accounting s)))
            (is (pos? (count (:events s)))))))
      (is (>= (count (remove nil? scenarios)) 8)
          "At least 8 of 12 reversal-slashing scenarios must load successfully"))))

(deftest test-dr-coverage-minimums
  (testing "Dispute resolution coverage meets minimum thresholds"
    (let [scenarios (for [path dr-scenario-paths]
                      (try (load-scenario path) (catch Exception _ nil)))
          valid (remove nil? scenarios)
          tags (mapcat :tags valid)
          coverage-tags (filter #(clojure.string/starts-with? % "coverage/") tags)
          by-coverage (frequencies coverage-tags)]
      (is (>= (get by-coverage "coverage/basic-lifecycle" 0) 3)
          "At least 3 basic-lifecycle scenarios")
      (is (>= (get by-coverage "coverage/evidence" 0) 1)
          "At least 1 evidence scenario")
      (is (>= (get by-coverage "coverage/strategic" 0) 1)
          "At least 1 strategic scenario")
      (is (>= (get by-coverage "coverage/resolver-integrity" 0) 1)
          "At least 1 resolver-integrity scenario")
      (is (>= (get by-coverage "coverage/finality" 0) 1)
          "At least 1 finality scenario"))))

;; ---------------------------------------------------------------------------
;; Appeal expected-value equilibrium scenarios
;; ---------------------------------------------------------------------------

(def appeal-ev-scenario-ids
  "Appeal EV model boundary scenarios (S-DR-097/098/099)."
  ["S-DR-097-appeal-ev-safe-calibrated"
   "S-DR-098-appeal-ev-under-deterred"
   "S-DR-099-appeal-ev-correct-blocked"])

(deftest test-appeal-ev-scenarios-exist-and-replay
  (testing "Appeal EV boundary scenarios load, replay, and declare the equilibrium concept"
    (doseq [sid appeal-ev-scenario-ids]
      (let [s (try (load-scenario (str "scenarios/edn/" sid ".edn"))
                   (catch Exception e (println "MISSING:" sid (.getMessage e)) nil))]
        (is (some? s) (str sid " must exist"))
        (when s
          (is (= :pass (:outcome (replay-scenario s)))
              (str sid " must replay to :pass"))
          (is (= [:appeal-decision-rationality] (get-in s [:theory :equilibrium-concept]))
              (str sid " must declare :appeal-decision-rationality equilibrium concept")))))))

(deftest test-appeal-ev-equilibrium-verdicts
  (testing "Appeal EV equilibrium check returns expected verdicts across boundary regimes"
    (let [expected {"S-DR-097-appeal-ev-safe-calibrated" :pass
                    "S-DR-098-appeal-ev-under-deterred" :fail
                    "S-DR-099-appeal-ev-correct-blocked" :fail}]
      (doseq [[sid kw] expected]
        (let [s (load-scenario (str "scenarios/edn/" sid ".edn"))
              replay (replay-scenario s)
              eq-result (equilibrium/evaluate-equilibrium (:theory s) replay)
              concept-result (get (:equilibrium-results eq-result) :appeal-decision-rationality)]
          (testing (str sid " appeal-decision-rationality")
            (is (= kw (:status concept-result))
                (str sid " expected equilibrium " kw " but got "
                     (:status concept-result)))))))))

;; ---------------------------------------------------------------------------
;; Report: coverage report function works
;; ---------------------------------------------------------------------------

(deftest test-dr-coverage-report-shape
  (testing "The coverage report function returns the expected shape"
    (let [report (dc/dispute-resolution-coverage-report)
          readiness (:researcher-readiness report)]
      (is (= :dispute-resolution (:suite report)))
      (is (contains? report :total-scenarios))
      (is (contains? report :by-coverage))
      (is (contains? report :gaps))
      (is (pos? (:total-scenarios report)))
      (is (contains? report :researcher-readiness))
      (testing "researcher-readiness flags are booleans reflecting artifact existence")
      (doseq [flag [:trace-summary? :evidence-summary? :evidence-world-hashes?
                    :financial-outcome? :linked-evidence-group?
                    :invariant-results? :dispute-summary?]]
        (is (contains? readiness flag)
            (str "researcher-readiness must contain " flag))
        (is (instance? Boolean (get readiness flag))
            (str flag " must be a boolean"))))))

;; ---------------------------------------------------------------------------
;; JSON helpers (mirror summary.clj; kept private to avoid namespace coupling)
;; ---------------------------------------------------------------------------

(defn- kw->json-key
  [k]
  (if (keyword? k)
    (let [ns (namespace k) n (name k)]
      (if ns (str ns "/" n) n))
    (name k)))

(defn- read-evidence-file
  [f]
  (try (with-open [r (io/reader f)]
         (json/read r))
       (catch Exception _ nil)))

(defn- safe-str [v]
  (cond (string? v) v (keyword? v) (name v) (nil? v) "" :else (str v)))

(defn- keywordize-keys-for-json
  [x]
  (cond
    (nil? x) nil
    (map? x) (into {} (for [[k v] x]
                        [(kw->json-key k) (keywordize-keys-for-json v)]))
    (vector? x) (mapv keywordize-keys-for-json x)
    (keyword? x) (name x)
    :else x))

;; ---------------------------------------------------------------------------
;; Evidence summary artifact
;; ---------------------------------------------------------------------------

(defn clean-evidence-dir!
  "Remove all evidence files from the event-evidence directory."
  []
  (let [dir (str (evcfg/artifact-dir) "/event-evidence")
        f (io/file dir)]
    (when (.isDirectory f)
      (doseq [file (.listFiles f)]
        (.delete file)))
    (println "Cleaned evidence directory:" dir)))

(defn prepare-evidence-summary!
  "Build and persist the evidence-summary.json artifact from accumulated
   evidence files. Call after all scenario replays have completed."
  []
  (ev-sum/write-evidence-summary!)
  (println "Evidence summary artifact emitted"))

;; ---------------------------------------------------------------------------
;; Researcher-readiness artifact emitters
;; ---------------------------------------------------------------------------

(defn- write-json-artifact!
  "Write data as pretty-printed JSON to the artifact directory."
  [filename data]
  (let [f (io/file (evcfg/artifact-dir) filename)]
    (.mkdirs (io/file (evcfg/artifact-dir)))
    (spit f (json/write-str data {:indent true}))
    (println (str "Wrote " filename))
    (.getPath f)))

(defn build-trace-summary
  "Produce a trace-summary.json from accumulated evidence files."
  []
  (let [dir (str (evcfg/artifact-dir) "/event-evidence")
        dir-f (io/file dir)]
    (if-not (.isDirectory dir-f)
      {:trace-count 0 :error "no event-evidence directory"}
      (let [files (sort (filter #(.endsWith (.getName %) ".json")
                                (map #(io/file dir %) (.list dir-f))))
            records (keep read-evidence-file files)
            entries (mapv (fn [r]
                            (let [ctx (get r "evidence/context" {})]
                              {:seq (get r "evidence/chain-seq" 0)
                               :type (safe-str (or (get r "evidence/type") ""))
                               :hash (safe-str (or (get r "evidence/hash") ""))
                               :subject/type (safe-str (or (get ctx "subject/type") ""))
                               :subject/id (safe-str (or (get ctx "subject/id") ""))
                               :action/type (safe-str (or (get ctx "action/type") ""))
                               :evidence/reason (safe-str (or (get ctx "evidence/reason") ""))
                               :before-hash (safe-str (or (get r "world/before-hash")
                                                          (get r "world/before-full-hash") ""))
                               :after-hash (safe-str (or (get r "world/after-hash")
                                                         (get r "world/after-full-hash") ""))}))
                          records)]
        {:trace-count (count entries) :entries entries}))))

(defn write-trace-summary! []
  (write-json-artifact! "trace-summary.json"
                        (keywordize-keys-for-json (build-trace-summary))))

(defn build-financial-outcome
  "Produce a financial-outcome.json from evidence files."
  []
  (let [dir (str (evcfg/artifact-dir) "/event-evidence")
        dir-f (io/file dir)]
    (if-not (.isDirectory dir-f)
      {:financial-count 0 :error "no event-evidence directory"}
      (let [records (keep read-evidence-file
                          (sort (filter #(.endsWith (.getName %) ".json")
                                        (map #(io/file dir %) (.list dir-f)))))]
        {:financial-count (count records)
         :resolution-events (count (filter #(cstr/includes?
                                             (or (get % "evidence/type") (get % "type") "")
                                             "resolution") records))
         :slash-events (count (filter #(cstr/includes?
                                        (or (get % "evidence/type") (get % "type") "")
                                        "slash") records))
         :settlement-events (count (filter #(cstr/includes?
                                             (or (get % "evidence/type") (get % "type") "")
                                             "settlement") records))}))))

(defn write-financial-outcome! []
  (write-json-artifact! "financial-outcome.json"
                        (keywordize-keys-for-json (build-financial-outcome))))

(defn build-invariant-results
  "Produce an invariant-results.json from accumulated evidence and chain registry.
   Counts evidence records by type and reports chain integrity status."
  []
  (let [dir (str (evcfg/artifact-dir) "/event-evidence")
        dir-f (io/file dir)]
    (if-not (.isDirectory dir-f)
      {:evidence-count 0 :note "no event-evidence directory" :chain-status "empty"}
      (let [records (keep read-evidence-file
                          (sort (filter #(.endsWith (.getName %) ".json")
                                        (map #(io/file dir %) (.list dir-f)))))
            registry (try (chain/build-registry) (catch Exception _ nil))
            chain-valid? (when registry
                           (:chain-intact (chain/evidence-chain-integrity registry)))]
        {:evidence-count (count records)
         :resolution-events (count (filter #(cstr/includes?
                                             (or (get % "evidence/type") (get % "type") "")
                                             "resolution") records))
         :slash-events (count (filter #(cstr/includes?
                                        (or (get % "evidence/type") (get % "type") "")
                                        "slash") records))
         :settlement-events (count (filter #(cstr/includes?
                                             (or (get % "evidence/type") (get % "type") "")
                                             "settlement") records))
         :chain-intact? chain-valid?
         :registry-present? (some? registry)}))))

(defn write-invariant-results-summary! []
  (write-json-artifact! "invariant-results.json"
                        (keywordize-keys-for-json (build-invariant-results))))

(defn build-dispute-summary
  "Produce a dispute-summary.json from evidence files."
  []
  (let [dir (str (evcfg/artifact-dir) "/event-evidence")
        dir-f (io/file dir)]
    (if-not (.isDirectory dir-f)
      {:dispute-count 0 :error "no event-evidence directory"}
      (let [records (keep read-evidence-file
                          (sort (filter #(.endsWith (.getName %) ".json")
                                        (map #(io/file dir %) (.list dir-f)))))
            disputes (filter #(cstr/includes?
                               (or (get % "evidence/type") (get % "type") "") "dispute")
                             records)
            enriched (mapv (fn [r]
                             (let [ctx (get r "evidence/context" {})]
                               {:seq (get r "evidence/chain-seq" 0)
                                :type (safe-str (or (get r "evidence/type") ""))
                                :hash (safe-str (or (get r "evidence/hash") ""))
                                :workflow-id (get-in r ["inputs" "dispute/workflow-id"]
                                                     (get ctx "subject/id" ""))
                                :caller (get-in r ["inputs" "dispute/caller"] "")
                                :resolver (get-in r ["inputs" "dispute/resolver"] "")
                                :dispute-level (get-in r ["inputs" "dispute/level"] 0)
                                :evidence-reason (safe-str (or (get ctx "evidence/reason") ""))
                                :before-hash (safe-str (or (get r "world/before-hash")
                                                           (get r "world/before-full-hash") ""))
                                :after-hash (safe-str (or (get r "world/after-hash")
                                                          (get r "world/after-full-hash") ""))}))
                           disputes)]
        {:dispute-count (count enriched) :disputes enriched}))))

(defn write-dispute-summary! []
  (write-json-artifact! "dispute-summary.json"
                        (keywordize-keys-for-json (build-dispute-summary))))

(defn prepare-all-artifacts!
  "Emit all researcher-readiness artifacts after a test run."
  []
  (prepare-evidence-summary!)
  (write-trace-summary!)
  (write-financial-outcome!)
  (write-invariant-results-summary!)
  (write-dispute-summary!)
  ;; Build and persist evidence links index (produced after all evidence is captured)
  (try (ee/write-evidence-links-index-v1!)
       (catch Exception e
         (println (str "WARN: evidence links index failed: " (.getMessage e)))))
  ;; Phase 3: Aggregate scenario-local evidence into the top-level registry
  (let [aggregated-count (chain/accumulate-scenario-evidence!)]
    (println (str "Aggregated " aggregated-count " scenario-local evidence entries")))
  ;; Finalize the evidence chain: persist registry with aggregated evidence
  (try (chain/finalize-and-write!)
       (catch Exception e
         (println (str "WARN: chain finalize failed: " (.getMessage e)))))
  ;; Phase 1: Reconcile evidence files on disk against the registry and cursor
  ;; NOTE: This reconciliation run is INTENTIONAL — evidence files are written during
  ;; scenario replay but the in-memory cursor is not updated to match (the scenario
  ;; operates in a fresh with-fresh-chain-cursor scope that starts at seq 0).
  ;; The "Cursor behind disk" / "EVIDENCE RECONCILIATION ERROR" messages are EXPECTED
  ;; and confirm the reconciliation function detects this condition correctly.
  (try (let [result (chain/reconcile-evidence! :throw-on-error false)]
         (println (str "Evidence reconciliation: " (if (:reconciled? result) "PASS" "ISSUES FOUND")
                       " disk=" (:disk-count result)
                       " registry=" (:registry-count result)
                       " cursor-seq=" (:cursor-seq result)
                       " max-disk-seq=" (:max-disk-seq result)))
         (doseq [e (:errors result)]
           (println (str "  RECONCILIATION ISSUE: " e))))
       (catch Exception e
         (println (str "WARN: evidence reconciliation failed: " (.getMessage e)))))
  ;; Phase 4: Write aggregate cursor referencing all scenario chain heads
  (try (let [snapshots (chain/scenario-evidence-snapshots)
             registry (chain/build-registry)
             agg-cursor (chain/build-aggregate-cursor snapshots (:registry-hash registry))]
         (when agg-cursor
           (let [cursor-file (io/file (str (evcfg/artifact-dir)) "aggregate-cursor.json")]
             (spit cursor-file (json/write-str agg-cursor {:indent true}))
             (println (str "Wrote aggregate-cursor.json: "
                           (:cursor/scenario-count agg-cursor) " scenarios, "
                           (:cursor/total-evidence agg-cursor) " total evidence")))))
       (catch Exception e
         (println (str "WARN: aggregate cursor write failed: " (.getMessage e))))))

;; Use :once fixture to clean evidence before and emit summary after
(defn evidence-fixture
  [f]
  (clean-evidence-dir!)
  (f)
  (prepare-all-artifacts!))

;; Register fixture once for the namespace
(defn setup-evidence-fixture
  []
  (use-fixtures :once evidence-fixture))

;; ---------------------------------------------------------------------------
;; Evidence summary: verify world hashes are present in dispute evidence
;; ---------------------------------------------------------------------------

(deftest test-evidence-summary-world-hashes
  (testing "Evidence summary shows world-before/world-after hashes for dispute events"
    (let [summary (ev-sum/build-evidence-summary)]
      (if (pos? (:evidence-count summary))
        (let [dispute-records (:dispute-records summary)]
          (when (seq dispute-records)
            (doseq [r dispute-records]
              (testing (str "evidence " (:evidence/type r))
                (is (not (cstr/blank? (:world/before-hash r)))
                    (str (:evidence/type r) " must have world/before-hash"))
                (is (not (cstr/blank? (:world/after-hash r)))
                    (str (:evidence/type r) " must have world/after-hash"))))))
        (println "  (no evidence files — under --noop-capture, this is expected)"))
      (println (str "Evidence summary has " (:evidence-count summary) " records"))
      (doseq [r (take 5 (:records summary))]
        (println (str "  " (:evidence/type r)
                      " before=" (when (seq (:world/before-hash r))
                                   (str (subs (:world/before-hash r) 0 30) "..."))
                      " after=" (when (seq (:world/after-hash r))
                                  (str (subs (:world/after-hash r) 0 30) "..."))))))))

;; ---------------------------------------------------------------------------
;; New invariants: execute on basic dispute world
;; ---------------------------------------------------------------------------

(deftest test-new-dispute-invariants-on-sample
  (testing "New dispute invariants execute without error on a basic dispute world"
    (let [scenario (load-scenario "scenarios/edn/S-DR-001-basic-release-ruling.edn")
          replay (replay-scenario scenario)
          world (:world replay)]
      (testing "evidence-on-state-change"
        (let [result ((resolve 'resolver-sim.protocols.sew.invariants.dispute/evidence-on-state-change?) world)]
          (is (contains? result :holds?))
          (is (contains? result :violations))))
      (testing "no-duplicate-dispute"
        (let [result ((resolve 'resolver-sim.protocols.sew.invariants.dispute/no-duplicate-dispute?) world)]
          (is (true? (:holds? result)))))
      (testing "resolver-decision-attributable"
        (let [result ((resolve 'resolver-sim.protocols.sew.invariants.dispute/resolver-decision-attributable?) world)]
          (is (contains? result :holds?))
          (is (contains? result :violations))))
      (testing "appeal-reversal-detectable"
        (let [result ((resolve 'resolver-sim.protocols.sew.invariants.dispute/appeal-reversal-detectable?) world)]
          (is (contains? result :holds?))
          (is (contains? result :reversals)))))
    (testing "S-DR-030 biased-resolver world triggers reversal detection"
      (let [scenario (load-scenario "scenarios/edn/S-DR-030-biased-resolver-appealed.edn")
            replay (replay-scenario scenario)
            world (:world replay)
            result ((resolve 'resolver-sim.protocols.sew.invariants.dispute/appeal-reversal-detectable?) world)]
        (is (contains? result :reversals))
        (is (pos? (count (:reversals result)))
            "Expected at least one detectable reversal in appealed scenario")))))

;; ---------------------------------------------------------------------------
;; Structural: governance dispatch audit
;;
;; Every action that modifies protocol-level state must reach the governance
;; gate (with-governance-actor), either inline, through run-governance-action,
;; or through a delegated helper that itself reaches the gate. The audit reads
;; the source file and follows one level of helper delegation, so actions that
;; gate via a `*` helper (e.g. grant-force-authorisation -> grant-force-
;; authorisation*) or via a consensus preflight that calls with-governance-actor
;; directly (grant-consensus-force-authorisation) are recognised as gated.
;; ---------------------------------------------------------------------------

(defn- action-defmethod-body
  "Extract the defmethod body text for an action, or nil when absent."
  [source action]
  (some (fn [section]
          (let [[a body] (cstr/split section #"\"" 2)]
            (when (= a action) body)))
        (rest (cstr/split source #"\(defmethod apply-action \""))))

(defn- helper-source
  "Source text of a top-level (defn / defn-) definition, or nil. Uses a
   balanced-paren scan so the whole form is captured."
  [source fn-name]
  (let [pat (re-pattern (str "\\(defn-?\\s+" (java.util.regex.Pattern/quote fn-name)
                             "(?=[\\s(])"))
        m (re-matcher pat source)]
    (when (.find m)
      (let [s (subs source (.start m))]
        (loop [depth 0 i 0 in-str? false esc? false]
          (when (< i (count s))
            (let [ch (.charAt s i)]
              (cond
                (and in-str? esc?) (recur depth (inc i) in-str? false)
                (and in-str? (= ch \\)) (recur depth (inc i) in-str? true)
                in-str? (recur depth (inc i) (not= ch \") false)
                (= ch \") (recur depth (inc i) true false)
                (= ch \() (recur (inc depth) (inc i) false false)
                (= ch \)) (if (= depth 1)
                            (subs s 0 (inc i))
                            (recur (dec depth) (inc i) false false))
                :else (recur depth (inc i) false false)))))))))

(defn- reaches-governance-gate?
  "True when source text reaches the governance gate, following helper
   delegation up to a depth limit."
  ([source text] (reaches-governance-gate? source text 0))
  ([source text depth]
   (or (re-find #"run-governance-action\s+context\s+world\s+event" text)
       (re-find #"with-governance-actor" text)
       (and (< depth 4)
            (some (fn [[_ helper]]
                    (when-let [helper-src (helper-source source helper)]
                      (reaches-governance-gate? source helper-src (inc depth))))
                  (re-seq #"\(([a-z][a-z0-9-]*\*?)\s+context" text))))))

(deftest test-governance-dispatch-audit
  (testing "Governance-sensitive actions must reach the governance gate"
    (let [source (slurp "protocols_src/resolver_sim/protocols/sew.clj")
          sections (rest (cstr/split source #"\(defmethod apply-action \""))
          wrapped-actions (into #{}
                                (keep (fn [section]
                                        (let [[action body] (cstr/split section #"\"" 2)]
                                          (when (and action
                                                     (reaches-governance-gate? source body))
                                            action))))
                                sections)
          must-be-gated sew/governance-sensitive-actions
          missing-wrapper (for [action must-be-gated
                           :let [body (action-defmethod-body source action)]
                           :when (not (reaches-governance-gate? source body))]
                       action)
          non-sensitive-wrapped (cset/difference wrapped-actions must-be-gated)]
      (doseq [v missing-wrapper]
        (println (str "  GOVERNANCE GAP: " v)))
      (doseq [v non-sensitive-wrapped]
        (println (str "  NON-SENSITIVE USING GOVERNANCE WRAPPER: " v)))
      (is (empty? missing-wrapper)
          (str "Governance gates missing: " (pr-str missing-wrapper)))
      (is (empty? non-sensitive-wrapped)
          (str "Non-sensitive actions must not reach the governance gate: " (pr-str non-sensitive-wrapped)))
      (is (re-find #"defn- run-governance-action[\s\S]*with-governance-actor" source)
          "run-governance-action must wrap with-governance-actor"))))

;; ---------------------------------------------------------------------------
;; Theory-falsification scenarios
;;
;; Scenarios with purpose=theory-falsification intentionally demonstrate a
;; protocol vulnerability.  They should PASS (the replay executes correctly)
;; but be displayed as XFAIL (expected failure) — the scenario successfully
;; falsifies the theory that the protocol is robust against this attack.
;;
;; The economic security finding (S-DR-075) is the canonical example:
;; the protocol state machine correctly permits a malicious resolver to
;; profit from fraud at current bond/detection parameters.  The scenario
;; passes at the replay level but FAILS at the economic security level.
;; ---------------------------------------------------------------------------

(deftest test-theory-falsification-scenarios
  (testing "Theory-falsification scenarios demonstrate known vulnerabilities"
    (let [paths (filter #(.contains % "S-DR-")
                        (map #(str "scenarios/edn/" %) (.list (io/file "scenarios/edn"))))
          tf-scenarios (for [path paths]
                         (try (load-scenario path) (catch Exception _ nil)))
          tf-valid (filter (fn [s]
                             (and (= "theory-falsification" (:purpose s))
                                  (not (some #(= "status/todo-stub" %) (:tags s [])))))
                           tf-scenarios)]
      (println "\n=== THEORY-FALSIFICATION SCENARIOS (XFAIL) ===")
      (is (pos? (count tf-valid)) "At least one theory-falsification scenario must exist")
      (doseq [s tf-valid]
        (let [r (replay-scenario s)
              outcome (:outcome r)
              violated-any? (pos? (get-in r [:metrics :invariant-violations] 0))
              ok? (= :pass outcome)]
          (println (str "  " (:scenario-id s)
                        "  replay=" (name outcome)
                        "  invariants-violated=" violated-any?
                        "  interpretation=XFAIL"
                        "  (proves vulnerability exists)"))
          (is ok? (str (:scenario-id s) " must replay successfully to demonstrate the vulnerability")))))))

;; ---------------------------------------------------------------------------
;; Structural: error code coverage audit
;;
;; Every error code used in (t/fail :) across the sew protocol must be
;; in either sew-state-error-codes or sew-guard-error-codes.  Otherwise
;; the metrics system silently ignores expected reverts for that code.
;;
;; The known-sets variable below must be kept in sync with the actual
;; defs in sew.clj.  If you add an error code to one of those sets,
;; update this list too.
;; ---------------------------------------------------------------------------

(def ^:private known-error-codes
  "Every error code that SHOULD be classified in sew-state-error-codes
   or sew-guard-error-codes.  Cross-referenced against actual t/fail
   calls below."
  #{:transfer-not-pending :transfer-not-in-dispute
    :invalid-state-for-release :invalid-state-for-refund
    :resolution-without-settlement :invalid-resolver :invalid-workflow-id
    :transfer-not-finalized :has-pending-settlement
    :dispute-timeout-not-exceeded :invalid-token :amount-zero
    :invalid-amount :invalid-recipient :cannot-set-both-auto-times
    :insufficient-module-liquidity :token-liquidity-crunch
    :circuit-breaker-active :resolver-at-capacity :resolver-frozen
    :insufficient-resolver-stake :active-disputes-block-withdrawal
    :freeze-blocked-active-dispute
    :pending-slash-blocks-withdrawal :missing-fee-bps
    :no-fees-to-withdraw :liquidity-insufficient :no-claimable-balance
    :no-bond-to-slash :no-bond-to-return :senior-not-registered
    :senior-coverage-exceeded :insufficient-stake :protocol-paused
    :invalid-coverage-amount
    ;; Guard codes
    :no-resolution-to-appeal :appeal-window-expired
    :appeal-window-not-expired :escalation-not-allowed
    :escalation-not-configured :resolution-already-pending
    :resolver-capacity-exceeded :not-participant
    :not-authorized-resolver :not-governance :not-resolver
    :not-sender :not-recipient :no-pending-slash :invalid-slash-state
    :slash-not-pending :slash-already-pending :invalid-slash-amount
    :invalid-resolver-addr :slash-resolver-mismatch
    :slash-exceeds-max-per-offense :slash-epoch-cap-exceeded
    :timelock-not-expired :workflow-not-slashable
    :missing-caller-context :invalid-new-resolver
    :force-authorisation-already-executed})

(deftest test-error-code-coverage-audit
  (testing "All t/fail error codes are classified in the error code sets"
    (let [clj-files (filter #(.endsWith (.getName %) ".clj")
                            (remove #(.isDirectory %)
                                    (file-seq (java.io.File. "src"))))
          all-codes (into #{}
                          (comp
                           (mapcat #(clojure.string/split-lines (slurp %)))
                           (keep (fn [line]
                                   (when-let [m (re-find #"t/fail\s+:([a-z0-9-]+)" line)]
                                     (second m))))
                           (map keyword))
                          clj-files)
          unclassified (cset/difference all-codes known-error-codes)]
      (doseq [c (sort unclassified)]
        (println (str "  UNCLASSIFIED: :" c)))
      (is (empty? unclassified)
          (str (count unclassified) " t/fail code(s) not in known-error-codes")))))

;; ── Slash-Obligation Allocation Result Artifact ──────────────────────────

(deftest test-slash-obligation-allocation-artifact
  (testing "Slash-obligation pro-rata allocation result artifacts are produced and registered"
    (let [dir (str (evcfg/artifact-dir))
          _ (println "Checking artifacts in:" dir)
          artifact-files (sort (filter #(.contains (.getName %) "allocation-result-")
                                       (.listFiles (io/file dir))))
          artifact-count (count artifact-files)]
      (println (str "Found " artifact-count " allocation result artifact(s)"))
      (doseq [f artifact-files]
        (println (str "  " (.getName f) " (" (.length f) " bytes)")))
      (is (pos? artifact-count)
          (str "At least one allocation result artifact must be produced during DR scenarios "
               "(found " artifact-count ")"))
      ;; Verify each artifact is valid JSON with expected fields
      (doseq [f artifact-files]
        (let [content (json/read-str (slurp f) :key-fn keyword)
              result-id (:allocation-result-id content)
              provenance (:provenance content)]
          (is (string? result-id) (str "File " (.getName f) " must have :allocation-result-id"))
          (is (map? provenance) (str "File " (.getName f) " must have :provenance"))
          (when (map? provenance)
            (let [scenario-id (:scenario-id provenance)
                  run-id (:run-id provenance)]
              (is (or (nil? scenario-id) (string? scenario-id))
                  (str "File " (.getName f) " :scenario-id must be string or nil"))
              (is (or (nil? run-id) (string? run-id))
                  (str "File " (.getName f) " :run-id must be string or nil")))))))))
