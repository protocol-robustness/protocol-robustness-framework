(ns resolver-sim.notebooks.speds.core-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.notebook-support.common :as common]
            [resolver-sim.notebook-support.speds.config :as config]
            [resolver-sim.notebook-support.speds.core :as core]
            [resolver-sim.notebook-support.speds.data :as data]
            [resolver-sim.notebook-support.speds.semantics :as sem]
            [resolver-sim.notebook-support.speds.story :as story]
            [resolver-sim.notebook-support.speds.validation :as validation]
            [resolver-sim.notebook-support.speds.findings :as findings]
            [resolver-sim.notebook-support.speds.issues :as issues]
            [resolver-sim.definitions.registry :as defs]))

;; ──────────────────────────────────────────────────────────────────────────
;; speds.config
;; ──────────────────────────────────────────────────────────────────────────

(deftest config-has-artifact-paths
  (testing "artifact-paths map is populated"
    (is (map? config/artifact-paths))
    (is (string? (:test-summary config/artifact-paths)))
    (is (string? (:coverage config/artifact-paths)))))

(deftest config-profile-is-lazy
  (testing "profile is a delay, resolved lazily"
    (is (instance? clojure.lang.Delay config/profile))
    (is (map? @config/profile))))

(deftest config-profile-has-required-keys
  (testing "active profile has required structure"
    (let [p @config/profile]
      (is (string? (:protocol-label p)))
      (is (keyword? (get-in p [:severity-rules :default-severity])))
      (is (map? (:story-family-rules p))))))

(deftest config-selected-profile-key-defaults-to-generic
  (testing "selected-profile-key returns :generic without SPEDS_PROFILE"
    (is (= :generic (config/selected-profile-key)))))

;; ──────────────────────────────────────────────────────────────────────────
;; speds.core — rendering primitives (smoke tests)
;; ──────────────────────────────────────────────────────────────────────────

(deftest core-v-act-renders-hiccup
  (testing "v-act returns a Hiccup vector"
    (is (vector? (core/v-act "buyer")))
    (is (vector? (core/v-act "attacker" :adversarial)))
    (is (vector? (core/v-act nil)))
    (is (vector? (core/v-act "" :backstop)))))

(deftest core-v-flo-renders-hiccup
  (testing "v-flo returns a Hiccup vector for all flow types"
    (is (vector? (core/v-flo :principal)))
    (is (vector? (core/v-flo :yield)))
    (is (vector? (core/v-flo :adversarial)))))

(deftest core-v-inv-renders-hiccup
  (testing "v-inv returns a Hiccup vector for ok/fail status"
    (is (vector? (core/v-inv :solvency :ok)))
    (is (vector? (core/v-inv :budget :fail)))
    (is (vector? (core/v-inv nil nil))))

  (deftest core-v-res-renders-hiccup
    (testing "v-res returns a Hiccup vector"
      (is (vector? (core/v-res "G04")))
      (is (vector? (core/v-res nil)))))

  (deftest core-v-rpy-renders-hiccup
    (testing "v-rpy returns a Hiccup vector"
      (is (vector? (core/v-rpy "abc123")))
      (is (vector? (core/v-rpy nil)))))

  (deftest core-v-frame-renders-hiccup
    (testing "v-frame returns a Hiccup vector with header/content"
      (let [frame (core/v-frame
                   {:header "TEST"
                    :footer-left "left"
                    :footer-right "right"}
                   [:div "content"])]
        (is (vector? frame))
        (is (some #(re-find #"(?i)TEST" (str %)) (flatten frame))))))

  (deftest core-render-carousel-supports-layouts
    (testing "render-carousel handles all layout modes without throwing"
      (let [specs [{:header "A" :footer-left "L" :footer-right "R" :content [:div "ok"]}]
            content-fn (fn [_ _ s] (core/v-frame s (:content s)))]
        (is (vector? (core/render-carousel content-fn specs {:layout :grid})))
        (is (vector? (core/render-carousel content-fn specs {:layout :single})))
        (is (vector? (core/render-carousel content-fn specs {:layout :row}))))
      (testing "render-carousel handles empty specs"
        (is (vector? (core/render-carousel (fn [_ _ _] [:div]) [] {:layout :grid}))))))

;; ──────────────────────────────────────────────────────────────────────────
;; speds.semantics
;; ──────────────────────────────────────────────────────────────────────────

  (deftest semantics-purpose->kind-returns-known-kinds
    (testing "purpose->kind returns expected strings for known purposes"
      (is (= "liveness_risk" (sem/purpose->kind :adversarial-robustness)))
      (is (= "expected_negative" (sem/purpose->kind :theory-falsification)))
      (is (= "regression" (sem/purpose->kind :regression))))
    (testing "purpose->kind falls back to default for unknown purposes"
      (is (= "inconclusive_result" (sem/purpose->kind :unknown-purpose)))))

  (deftest semantics-classification-for-purpose-returns-map
    (testing "classification-for-purpose returns a map with expected keys"
      (let [c (sem/classification-for-purpose :theory-falsification)]
        (is (map? c))
        (is (:label c))
        (is (:status c))
        (is (:confidence c))
        (is (:rationale c))))
    (testing "classification-for-purpose falls back to default for unknown"
      (let [c (sem/classification-for-purpose :nonexistent)]
        (is (= "operational_signal" (:label c))))))

  (deftest semantics-purpose->story-family-str-returns-string
    (testing "purpose->story-family-str returns a string"
      (is (string? (sem/purpose->story-family-str :theory-falsification)))
      (is (string? (sem/purpose->story-family-str nil)))))

  (deftest semantics-purpose->story-family-kw-returns-keyword
    (testing "purpose->story-family-kw returns a keyword"
      (is (keyword? (sem/purpose->story-family-kw :theory-falsification)))
      (is (keyword? (sem/purpose->story-family-kw nil)))))

;; ──────────────────────────────────────────────────────────────────────────
;; speds.data — projection helpers (no I/O)
;; ──────────────────────────────────────────────────────────────────────────

  (deftest data-project-actor-type
    (testing "project-actor-type maps agent IDs to types"
      (is (= :adversarial (data/project-actor-type "malice-1")))
      (is (= :adversarial (data/project-actor-type "attacker-bot")))
      (is (= :backstop (data/project-actor-type "kleros-01")))
      (is (= :honest (data/project-actor-type "buyer")))
      (is (= :honest (data/project-actor-type nil))))
    (testing "project-actor-type is case-insensitive"
      (is (= :adversarial (data/project-actor-type "MALICE-001")))))

  (deftest data-project-event-to-flow
    (testing "project-event-to-flow maps events to flow types"
      (is (= :yield (data/project-event-to-flow {:action "yield_deposit"})))
      (is (= :adversarial (data/project-event-to-flow {:action "raise_dispute"})))
      (is (= :principal (data/project-event-to-flow {:action "create_escrow"})))
      (is (= :principal (data/project-event-to-flow nil)))
      (is (= :principal (data/project-event-to-flow {})))))

  (deftest data-canonical-summary-normalizes
    (testing "canonical-summary normalizes various input shapes"
      (let [s (data/canonical-summary {:run_id "R1" :overall_status "pass" :failure_count 0})]
        (is (= "R1" (:run-id s)))
        (is (= "pass" (:overall-status s)))
        (is (= 0 (:failure-count s))))
      (testing "handles nil summary"
        (let [s (data/canonical-summary nil)]
          (is (string? (:run-id s)))
          (is (string? (:overall-status s)))))))

  (deftest data-find-scenario-by-id
    (testing "find-scenario-by-id finds scenarios in coverage"
      (let [coverage {:scenarios [{:id "S01"} {:id "S02"}]}]
        (is (= {:id "S01"} (data/find-scenario-by-id coverage "S01")))
        (is (nil? (data/find-scenario-by-id coverage "S99")))
        (is (nil? (data/find-scenario-by-id nil "S01")))
        (is (nil? (data/find-scenario-by-id coverage nil))))))

  (deftest data-narrative-metrics-handles-partial-data
    (testing "narrative-metrics handles nil coverage"
      (let [m (data/narrative-metrics {:summary nil :coverage nil})]
        (is (integer? (:scenario-count m)))
        (is (= 0 (:scenario-count m)))
        (is (string? (:replay-match-label m))))))

;; ──────────────────────────────────────────────────────────────────────────
;; speds.validation
;; ──────────────────────────────────────────────────────────────────────────

  (deftest validation-required-keys-are-sets
    (testing "validation schema constants are non-empty sets"
      (is (set? validation/required-frame-keys))
      (is (set? validation/required-claim-keys))
      (is (set? validation/required-issue-keys))
      (is (set? validation/required-finding-keys))
      (is (pos? (count validation/required-frame-keys)))
      (is (pos? (count validation/required-claim-keys)))))

  (deftest validation-validate-frame-schema
    (testing "validate-frame-schema reports missing keys"
      (let [result (validation/validate-frame-schema {:header "h"})]
        (is (vector? result))
        (is (seq result))  ;; missing footer keys
        (is (re-find #"(?i)footer" (first result)))))
    (testing "validate-frame-schema returns empty for valid frame"
      (let [result (validation/validate-frame-schema
                    {:header "h" :footer-left "l" :footer-right "r" :content [:div "c"]})]
        (is (empty? result))))
    (testing "validate-frame-schema handles nil"
      (let [result (validation/validate-frame-schema nil)]
        (is (vector? result))
        (is (seq result)))))

  (deftest validation-scan-hardcoded-success-claims
    (testing "scan-hardcoded-success-claims returns a vector"
      (let [result (validation/scan-hardcoded-success-claims [])]
        (is (vector? result))
        (is (empty? result)))))

;; ──────────────────────────────────────────────────────────────────────────
;; speds.findings — nil/edge-case paths
;; ──────────────────────────────────────────────────────────────────────────

  (deftest findings-generate-bundle-handles-nil
    (testing "generate-findings-bundle returns valid shape with nil artifacts"
      (let [bundle (findings/generate-findings-bundle nil)]
        (is (map? bundle))
        (is (= "speds.findings.v1" (:schema_version bundle)))
        (is (vector? (:findings bundle)))
        (is (zero? (count (:findings bundle)))))))

  (deftest findings-schema-validates
    (testing "findings bundle conforms to Malli schema"
      (let [bundle (findings/generate-findings-bundle nil)]
        (is (some? (:schema_version bundle)))
        (is (map? (:overall_status bundle))))))

;; ──────────────────────────────────────────────────────────────────────────
;; speds.issues — nil/edge-case paths
;; ──────────────────────────────────────────────────────────────────────────

  (deftest issues-generate-bundle-handles-nil
    (testing "generate-issues-bundle returns valid shape with nil artifacts"
      (let [bundle (issues/generate-issues-bundle nil)]
        (is (map? bundle))
        (is (= "speds-issues-v1" (:schema_version bundle)))
        (is (vector? (:issues bundle))))))

;; ──────────────────────────────────────────────────────────────────────────
;; speds.definitions.registry — dynamic definitions
;; ──────────────────────────────────────────────────────────────────────────

  (deftest registry-speds-definitions-loaded
    (testing "speds-purpose-kind loads from data file"
      (is (map? defs/speds-purpose-kind))
      (is (contains? defs/speds-purpose-kind :default))
      (is (contains? defs/speds-purpose-kind :adversarial-robustness)))
    (testing "speds-purpose-classification loads from data file"
      (is (map? defs/speds-purpose-classification))
      (is (contains? defs/speds-purpose-classification :adversarial-robustness))
      (let [c (:adversarial-robustness defs/speds-purpose-classification)]
        (is (= "liveness_risk" (:label c)))
        (is (= "attack_detected" (:status c)))))))

;; ──────────────────────────────────────────────────────────────────────────
;; P2#16 — Round-trip tests (generate → write → read → verify)
;; ──────────────────────────────────────────────────────────────────────────

(deftest round-trip-findings-bundle
  (testing "findings bundle can be written to temp dir and read back"
    (let [tmp-dir (str (System/getProperty "java.io.tmpdir") "/speds-test-findings-" (java.util.UUID/randomUUID))
          tmp-path (str tmp-dir "/findings.json")
          artifacts {:summary {:run_id "R1" :overall_status "pass"}
                     :coverage {:scenarios [{:id "S01" :purpose :theory-falsification}]}}]
      (.mkdirs (io/file tmp-dir))
      (let [bundle (findings/generate-findings-bundle artifacts)]
        (with-open [w (io/writer tmp-path)]
          (json/write bundle w :indent true))
        (let [loaded (common/read-json tmp-path)]
          (is (= "speds.findings.v1" (:schema_version loaded)))
          (is (vector? (:findings loaded)))
          (is (string? (get-in loaded [:run :run_id])))))
      (doseq [f (file-seq (io/file tmp-dir))]
        (when (.isFile f) (.delete f)))
      (.delete (io/file tmp-dir)))))

(deftest round-trip-issues-bundle
  (testing "issues bundle can be written to temp dir and read back"
    (let [tmp-dir (str (System/getProperty "java.io.tmpdir") "/speds-test-issues-" (java.util.UUID/randomUUID))
          tmp-path (str tmp-dir "/issues.json")
          artifacts {:summary {:run_id "R1" :overall_status "pass"}
                     :coverage {:scenarios [{:id "S01" :purpose :theory-falsification}]}}]
      (.mkdirs (io/file tmp-dir))
      (let [bundle (issues/generate-issues-bundle artifacts)]
        (with-open [w (io/writer tmp-path)]
          (json/write bundle w :indent true))
        (let [loaded (common/read-json tmp-path)]
          (is (= "speds-issues-v1" (:schema_version loaded)))
          (is (vector? (:issues loaded)))
          (is (number? (:issue-count loaded)))))
      (doseq [f (file-seq (io/file tmp-dir))]
        (when (.isFile f) (.delete f)))
      (.delete (io/file tmp-dir)))))

;; ──────────────────────────────────────────────────────────────────────────
;; P2#12 — Error-path tests (missing/corrupt/empty/nil)
;; ──────────────────────────────────────────────────────────────────────────

(deftest error-path-load-summary-missing-file
  (testing "load-summary returns nil for missing file"
    (with-redefs [config/artifact-paths (assoc config/artifact-paths :test-summary "/nonexistent/path.json")]
      (let [result (data/load-summary)]
        (is (nil? result))))))

(deftest error-path-load-coverage-missing-file
  (testing "load-coverage returns nil for missing file"
    (with-redefs [config/artifact-paths (assoc config/artifact-paths :coverage "/nonexistent/path.json")]
      (let [result (data/load-coverage)]
        (is (nil? result))))))

(deftest error-path-load-all-traces-missing-dir
  (testing "load-all-traces returns [] for missing dir"
    (with-redefs [config/artifact-paths (assoc config/artifact-paths :traces-dir "/nonexistent/dir")]
      (let [result (data/load-all-traces)]
        (is (vector? result))
        (is (empty? result))))))

(deftest error-path-load-all-golden-reports-missing-dir
  (testing "load-all-golden-reports returns {} for missing dir"
    (with-redefs [config/artifact-paths (assoc config/artifact-paths :golden-dir "/nonexistent/dir")]
      (let [result (data/load-all-golden-reports)]
        (is (map? result))
        (is (empty? result))))))

(deftest error-path-load-run-artifacts-returns-map
  (testing "load-run-artifacts always returns a map"
    (let [result (data/load-run-artifacts)]
      (is (map? result))
      (is (contains? result :summary))
      (is (contains? result :coverage)))))

(deftest error-path-narrative-metrics-empty-coverage
  (testing "narrative-metrics handles empty coverage scenarios"
    (let [m (data/narrative-metrics {:summary {:run_id "R1" :overall_status "pass"} :coverage {:scenarios []}})]
      (is (= 0 (:scenario-count m)))
      (is (string? (:replay-match-label m))))))

(deftest error-path-narrative-metrics-partial-summary
  (testing "narrative-metrics handles summary with missing fields"
    (let [m (data/narrative-metrics {:summary nil :coverage nil})]
      (is (integer? (:scenario-count m)))
      (is (string? (:replay-match-label m))))))

(deftest error-path-findings-nil-artifacts
  (testing "generate-findings-bundle handles nil artifacts"
    (let [bundle (findings/generate-findings-bundle nil)]
      (is (= "speds.findings.v1" (:schema_version bundle)))
      (is (empty? (:findings bundle))))))

(deftest error-path-issues-nil-artifacts
  (testing "generate-issues-bundle handles nil artifacts"
    (let [bundle (issues/generate-issues-bundle nil)]
      (is (= "speds-issues-v1" (:schema_version bundle)))
      (is (empty? (:issues bundle))))))

;; ──────────────────────────────────────────────────────────────────────────
;; P2#18 — Story engine edge-case tests
;; ──────────────────────────────────────────────────────────────────────────

(deftest story-scenario-deep-dive-nil-args
  (testing "generate-scenario-deep-dive with nil args returns hiccup"
    (let [result (story/generate-scenario-deep-dive nil nil)]
      (is (vector? result)))))

(deftest story-scenario-deep-dive-missing-artifacts
  (testing "generate-scenario-deep-dive with missing artifacts returns hiccup"
    (let [result (story/generate-scenario-deep-dive "S01" nil)]
      (is (vector? result)))))

(deftest story-run-overview-nil-artifacts
  (testing "generate-run-overview with nil artifacts returns hiccup"
    (let [result (story/generate-run-overview nil)]
      (is (vector? result)))))

(deftest story-run-overview-empty-artifacts
  (testing "generate-run-overview with empty coverage returns hiccup"
    (let [result (story/generate-run-overview {:summary nil :coverage {:scenarios []}})]
      (is (vector? result)))))

(deftest story-issue-gallery-nil-artifacts
  (testing "generate-issue-gallery with nil artifacts returns hiccup"
    (let [result (story/generate-issue-gallery nil)]
      (is (vector? result)))))

(deftest story-atlas-view-nil-artifacts
  (testing "generate-atlas-view with nil artifacts returns hiccup"
    (let [result (story/generate-atlas-view nil)]
      (is (vector? result)))))

(deftest story-generate-story-unknown-mode
  (testing "generate-story with unknown mode throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown story mode"
                          (story/generate-story nil {:mode :nonexistent :input {}})))))

(deftest story-generate-story-nil-artifacts
  (testing "generate-story with nil artifacts returns hiccup"
    (let [result (story/generate-story nil {:mode :run-overview :input {}})]
      (is (vector? result)))))

;; ──────────────────────────────────────────────────────────────────────────
;; P0 — Truth boundary: narrative may simplify evidence, never strengthen it.
;; An invariant with no recorded result must NOT become :ok.
;; ──────────────────────────────────────────────────────────────────────────

(deftest truth-boundary-normalize-invariant-status
  (testing "passing result values map to :ok"
    (is (= :ok (story/normalize-invariant-status :ok)))
    (is (= :ok (story/normalize-invariant-status :pass)))
    (is (= :ok (story/normalize-invariant-status :passed)))
    (is (= :ok (story/normalize-invariant-status :true)))
    (is (= :ok (story/normalize-invariant-status "pass"))))
  (testing "failing result values map to :failed"
    (is (= :failed (story/normalize-invariant-status :fail)))
    (is (= :failed (story/normalize-invariant-status :failed)))
    (is (= :failed (story/normalize-invariant-status :false)))
    (is (= :failed (story/normalize-invariant-status "FAIL"))))
  (testing "present-but-unrecognized values map to :unknown, never :ok"
    (is (= :unknown (story/normalize-invariant-status :bogus)))
    (is (= :unknown (story/normalize-invariant-status "maybe")))
    (is (= :unknown (story/normalize-invariant-status nil)))))

(deftest truth-boundary-inv-status-never-promotes-absent-to-ok
  (testing "missing invariant result is reported :not-measured, not :ok"
    (is (= :not-measured (story/inv-status {} :solvency)))
    (is (= :not-measured (story/inv-status {:invariant-results {}} :solvency)))
    (is (= :not-measured (story/inv-status {:invariant-results {:invariant/yield :ok}} :solvency))))
  (testing "recorded results are honored"
    (is (= :ok (story/inv-status {:invariant-results {:invariant/solvency :pass}} :solvency)))
    (is (= :ok (story/inv-status {:invariant-results {:solvency :ok}} :solvency)))
    (is (= :failed (story/inv-status {:invariant-results {:invariant/solvency :fail}} :solvency)))
    (is (= :failed (story/inv-status {:invariant-results {:invariant/solvency "failed"}} :solvency)))
    (is (= :unknown (story/inv-status {:invariant-results {:invariant/solvency :bogus}} :solvency)))))

(deftest truth-boundary-v-inv-never-renders-ok-for-unmeasured
  (testing "v-inv renders the exact status label, and never claims OK when not measured"
    (let [render-label (fn [status]
                         (-> (core/v-inv :solvency status)
                             flatten
                             (->> (map str))
                             (->> (apply str))))]
      (is (re-find #"NOT MEASURED" (render-label :not-measured)))
      (is (re-find #"UNKNOWN" (render-label :unknown)))
      (is (re-find #"OK" (render-label :ok)))
      (is (re-find #"FAIL" (render-label :failed)))
      (is (re-find #"N/A" (render-label :not-applicable)))
      (is (not (re-find #"OK" (render-label :not-measured))))
      (is (not (re-find #"OK" (render-label :bogus)))))))
