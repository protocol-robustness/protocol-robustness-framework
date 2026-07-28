(ns resolver-sim.notebook-support.speds.findings
  "Findings-envelope generation for SPEDS.
   Canonical evidence artifact from trial outputs."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [resolver-sim.logging :as log]
            [resolver-sim.notebook-support.common :as common]
            [resolver-sim.definitions.registry :as defs]
            [resolver-sim.notebook-support.speds.data :as data]
            [resolver-sim.notebook-support.speds.semantics :as sem]
            [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.evidence.confidence :as confidence]
            [resolver-sim.scenario.outcome-semantics :as ose]
            [resolver-sim.notebook-support.speds.config :as config]))

(def findings-path
  (or (:findings config/artifact-paths) "results/test-artifacts/findings.json"))

(def required-finding-keys
  #{:finding_id :scenario_id :kind :severity :status_kind :title
    :summary :story :evidence_refs :provenance})

(def default-comparator-config
  {:strategy :nearest-baseline-by-id
   :enabled? true})

(defn- purpose->kind [purpose]
  (sem/purpose->kind purpose))

(defn- purpose->family [purpose]
  (sem/purpose->story-family-str purpose))

(defn- purpose-label [purpose]
  (or (get-in (defs/purpose-def (ose/normalize-purpose purpose)) [:label])
      (some-> purpose name)
      "Unclassified"))

(defn- transition-label [tr]
  (or (get-in (defs/transition-def tr) [:label])
      (some-> tr name (str/replace "_" " "))
      "unknown transition"))

(defn- one-line-description [scenario]
  (or (:description scenario)
      (:summary scenario)
      (let [sid (or (:id scenario) "unknown-scenario")
            p-label (purpose-label (:purpose scenario))
            tr (first (or (:transitions scenario) []))
            tr-label (when tr (transition-label tr))]
        (str p-label " scenario " sid
             (when tr-label (str " exercises " tr-label))
             "."))))

(defn- classification-for [scenario]
  (sem/classification-for-purpose (:purpose scenario)))

(defn- scenario-id-num [sid]
  (some->> sid (re-find #"S(\d+)") second parse-long))

(defn- nearest-baseline-scenario [scenarios scenario]
  (let [sid-num  (scenario-id-num (:id scenario))
        baselines (filter #(= :baseline (ose/normalize-purpose (:purpose %))) scenarios)]
    (or (first (sort-by #(Math/abs ^long (- (long (or (scenario-id-num (:id %)) 0))
                                            (long (or sid-num 0)))) baselines))
        (first baselines))))

(defn- baseline-by-purpose [scenarios scenario]
  (let [purpose (ose/normalize-purpose (:purpose scenario))]
    (or (first (filter #(= purpose (ose/normalize-purpose (:purpose %))) scenarios))
        (nearest-baseline-scenario scenarios scenario))))

(defn- baseline-by-tags [scenarios scenario]
  (let [scenario-tags (set (or (:threat-tags scenario) []))
        scored (->> scenarios
                    (map (fn [s]
                           (let [tags (set (or (:threat-tags s) []))
                                 overlap (count (set/intersection scenario-tags tags))]
                             [overlap s])))
                    (sort-by (fn [[overlap _]] (- overlap))))]
    (or (some (fn [[overlap s]] (when (pos? overlap) s)) scored)
        (nearest-baseline-scenario scenarios scenario))))

(defn- choose-baseline [scenarios scenario strategy]
  (case strategy
    :matched-by-purpose (baseline-by-purpose scenarios scenario)
    :matched-by-tags    (baseline-by-tags scenarios scenario)
    :nearest-baseline-by-id (nearest-baseline-scenario scenarios scenario)
    ;; Safe fallback to maintain behavior for unknown strategies.
    (nearest-baseline-scenario scenarios scenario)))

(defn- metric-long [v]
  (cond
    (number? v) (long v)
    (string? v) (some->> v str/trim parse-long)
    :else nil))

(defn- numeric-metric-deltas
  "Return {metric {:scenario n :baseline n :delta n}} for differing numeric metrics."
  [scenario-report baseline-report]
  (when (and scenario-report baseline-report)
    (let [sm (:metrics scenario-report {})
          bm (:metrics baseline-report {})]
      (->> (set/union (set (keys sm)) (set (keys bm)))
           (keep (fn [k]
                   (let [sv (metric-long (get sm k))
                         bv (metric-long (get bm k))]
                     (when (and (some? sv) (some? bv) (not= sv bv))
                       [k {:scenario sv :baseline bv :delta (- sv bv)}]))))
           (sort-by (fn [[_ {:keys [delta]}]] (- (Math/abs (long delta)))))
           (into {})))))

(defn- top-metric-deltas [metric-deltas n]
  (->> metric-deltas
       (sort-by (fn [[_ v]] (- (Math/abs (long (:delta v))))))
       (take n)
       (map (fn [[k v]] (assoc v :metric (name k))))
       vec))

(defn- replay-delta-summary [scenario-report baseline-report]
  (if (and scenario-report baseline-report)
    (let [metric-deltas (numeric-metric-deltas scenario-report baseline-report)
          outcome-match (= (:outcome scenario-report) (:outcome baseline-report))
          hash-match (= (:final-state-hash scenario-report)
                        (:final-state-hash baseline-report))]
      {:replay_available true
       :outcome_delta {:scenario (some-> (:outcome scenario-report) name)
                       :baseline (some-> (:outcome baseline-report) name)
                       :match outcome-match}
       :final_state_hash_match hash-match
       :metric_deltas metric-deltas
       :top_metric_deltas (top-metric-deltas metric-deltas 5)})
    {:replay_available false}))

(defn- comparison-narrative
  [{:keys [baseline-id tag-delta overlap replay-delta]}]
  (let [base (str "Compared against baseline " baseline-id
                  ": tag delta=" tag-delta ", overlap=" overlap ".")
        replay (when (:replay_available replay-delta)
                 (str " Replay outcome "
                      (get-in replay-delta [:outcome_delta :scenario])
                      " vs "
                      (get-in replay-delta [:outcome_delta :baseline])
                      (when (false? (:final_state_hash_match replay-delta))
                        ", final-state-hash differs")
                      (when-let [top (seq (:top_metric_deltas replay-delta))]
                        (str ", metric deltas: "
                             (str/join ", "
                                       (map (fn [{:keys [metric delta]}]
                                              (str metric " Δ=" (if (pos? delta) "+" "") delta))
                                            top))))
                      "."))]
    (str base (or replay ""))))

(defn- baseline-comparison [artifacts scenario comparator-config]
  (let [scenarios (or (get-in artifacts [:coverage :scenarios]) [])
        golden-reports (or (:golden-reports artifacts) (data/load-all-golden-reports))
        strategy (or (:strategy comparator-config) :nearest-baseline-by-id)
        baseline (choose-baseline scenarios scenario strategy)
        scenario-tags (set (or (:threat-tags scenario) []))
        baseline-tags (set (or (:threat-tags baseline) []))
        scenario-tag-count (count scenario-tags)
        baseline-tag-count (count baseline-tags)
        overlap-count (count (set/intersection scenario-tags baseline-tags))
        enabled? (not= false (:enabled? comparator-config))
        scenario-report (data/find-golden-report golden-reports (:id scenario))
        baseline-report (when baseline (data/find-golden-report golden-reports (:id baseline)))
        replay-delta (replay-delta-summary scenario-report baseline-report)
        tag-delta (- scenario-tag-count baseline-tag-count)]
    {:baseline_scenario_id (or (:id baseline) "unavailable")
     :comparator_kind (-> strategy name (str/replace "-" "_"))
     :enabled? enabled?
     :replay_delta replay-delta
     :delta_summary (if (and enabled? baseline)
                      (merge {:threat_tag_count_delta tag-delta
                              :threat_tag_overlap_count overlap-count
                              :purpose_delta {:scenario (name (ose/normalize-purpose (:purpose scenario)))
                                              :baseline (name (ose/normalize-purpose (:purpose baseline)))}}
                             replay-delta
                             {:narrative (comparison-narrative
                                          {:baseline-id (:id baseline)
                                           :tag-delta tag-delta
                                           :overlap overlap-count
                                           :replay-delta replay-delta})})
                      {:narrative "No baseline scenario available in coverage artifact for automatic delta extraction."})
     :available? (boolean baseline)}))

(defn- evidence-confidence
  [artifacts scenario trace-digest-status]
  (let [metrics (data/narrative-metrics artifacts)
        replay-pct (:replay-match-pct metrics)
        golden (data/find-golden-report (:golden-reports artifacts) (:id scenario))
        trace-st (keyword trace-digest-status)
        signals {:replay-match-percentage replay-pct
                 :trace-digest-status trace-st
                 :golden-report-present? (some? golden)}
        policy (or (evcfg/confidence-policy) {})
        derived (confidence/derive-confidence policy signals)
        reasons (:reasons derived)
        basis (str/join ", "
                        (cond-> ["single-run-artifact-derived"]
                          (some #(= :trace-digest-computed %) reasons)
                          (conj "trace-verified")
                          (some #(= :golden-report-present %) reasons)
                          (conj "golden-report-matched")
                          (some #(= :replay-complete %) reasons)
                          (conj "deterministic-replay-confirmed")))]
    {:level (name (:level derived))
     :basis basis}))

(defn- narrative-artifact-spec [artifacts scenario sid sev st-kind comparator-config trace-info]
  (let [canon (data/canonical-summary (:summary artifacts))
        classification (classification-for scenario)
        replay-label (:replay-match-label (data/narrative-metrics artifacts))
        confidence (evidence-confidence artifacts scenario (:trace_digest_status trace-info))]
    {:schema_version "speds.story-artifact.v1"
     :scenario_id sid
     :classification classification
     :confidence confidence
     :provenance {:run_id (:run-id canon)
                  :git_sha (:git-sha canon)
                  :trace_digest_status (:trace_digest_status trace-info)
                  :trace_digest (:trace_digest trace-info)
                  :definitions/hash (defs/definitions-hash)}
     :claims [{:claim_id "scenario-kind"
               :value st-kind
               :severity sev
               :source_artifact "coverage"
               :source_path [:coverage :scenarios sid]}
              {:claim_id "replay-alignment"
               :value replay-label
               :source_artifact "summary"
               :source_path [:summary :replay_match_pct]}]
     :baseline_comparison (baseline-comparison artifacts scenario comparator-config)
     :visual_blocks [{:block "headline" :text (or (:title scenario) sid)}
                     {:block "what_happened" :text (str "Scenario " sid " produced status kind " st-kind ".")}
                     {:block "why_it_matters" :text (:rationale classification)}
                     {:block "action" :text "Classify as research finding vs regression before publication."}]}))

(defn- ->outcome-class [classification]
  (case (:label classification)
    "research_finding" :research-finding
    "regression" :regression
    "operational_signal" :operational-signal
    :inconclusive))

(defn- ->outcome-status [classification]
  (case (:status classification)
    "assumption_falsified" :assumption-falsified
    "unexpected_behavior" :unexpected-behavior
    "requires_triage" :requires-triage
    :unknown))

(defn- ->outcome-severity [sev]
  (case sev
    "critical" :critical
    "high" :high
    "medium" :medium
    "low" :low
    :low))

(defn- actionability
  [classification sev]
  (let [cls-label (:label classification)
        sev-kw (keyword sev)]
    {:owner (case cls-label
              "regression" :engineering
              "research_finding" :research
              :triage)
     :release-gate-impact (case sev-kw
                            :critical :blocking
                            :high :blocking
                            :medium :review-required
                            :informational)
     :next-step (str "Owner: " (case cls-label
                                 "regression" "engineering"
                                 "research_finding" "research"
                                 "triage")
                     ", impact: " (case sev-kw
                                    :critical "blocking"
                                    :high "blocking"
                                    :medium "review required"
                                    :low "informational"
                                    "review required"))}))

(defn- canonical-outcome [artifacts scenario sid sev st-kind classification story-spec]
  {:outcome-model/version "v0.1"
   :outcome
   {:class (->outcome-class classification)
    :status (->outcome-status classification)
    :severity (->outcome-severity sev)
     :confidence (merge (confidence/normalize-confidence
                         (or (:confidence classification) "medium"))
                        {:basis :single-run-artifact-derived
                         :rationale (:rationale classification)})
    :execution {:result (keyword (or st-kind "observed"))
                :halt-reason nil
                :scenario-id sid
                :scenario-purpose (ose/normalize-purpose (:purpose scenario))}
    :evidence {:claims (get story-spec :claims [])
               :provenance (get story-spec :provenance {})}
    :comparison {:comparator-id (get-in story-spec [:baseline_comparison :baseline_scenario_id])
                 :strategy (keyword (or (get-in story-spec [:baseline_comparison :comparator_kind])
                                        "nearest-baseline-by-id"))
                 :deltas (get-in story-spec [:baseline_comparison :delta_summary])
                 :narrative (get-in story-spec [:baseline_comparison :delta_summary :narrative])}
    :actionability (actionability classification sev)
    :visual {:blocks (mapv (fn [b] {:block (keyword (str/replace (or (:block b) "") "_" "-"))
                                    :text (:text b)})
                           (get story-spec :visual_blocks []))}}})

(defn- severity [scenario]
  (let [tags (set (map #(-> % name str/lower-case) (or (:threat-tags scenario) [])))
        {:keys [invariant-severity-order tag-severity-map default-severity]}
        (or (:severity-rules @config/profile) {})
        invariant-severities (->> (or (:invariant-results scenario) {})
                                  (keep (fn [[inv status]]
                                          (when (= status :fail)
                                            (get-in (defs/invariant-def inv) [:default-severity]))))
                                  set)
        matched-tag-severity
        (some (fn [[tag sev]]
                (when (contains? tags (str/lower-case (name tag)))
                  (name sev)))
              (or tag-severity-map {}))
        invariant-severity
        (some (fn [sev]
                (when (contains? invariant-severities sev)
                  (name sev)))
              (or invariant-severity-order [:high :medium]))]
    (cond
      invariant-severity invariant-severity
      matched-tag-severity matched-tag-severity
      :else (name (or default-severity :low)))))

(defn- status-kind [scenario]
  (if (ose/negative-test-purpose? (:purpose scenario))
    "expected_negative"
    "observed"))

(defn- priority [severity]
  (case severity "high" 90 "medium" 60 "low" 30 10))

(defn- truncate-safe [s n]
  (let [v (str (or s ""))]
    (subs v 0 (min n (count v)))))

(defn- trace-digest
  [trace]
  (when trace
    (data/sha256-hex (pr-str (dissoc trace :_filename)))))

(defn- find-trace
  [artifacts scenario-id]
  (let [traces (or (:all-traces artifacts) (data/load-all-traces))]
    (first (filter #(= (:scenario-id %) scenario-id) traces))))

(defn- compute-trace-digest
  [artifacts scenario-id]
  (if-let [trace (find-trace artifacts scenario-id)]
    {:trace_digest (trace-digest trace)
     :trace_digest_status "computed"}
    {:trace_digest nil
     :trace_digest_status "unavailable"}))

(defn- finding-id [run-id scenario-id]
  (str "FINDING-" run-id "-" (subs (data/sha256-hex scenario-id) 0 6)))

(defn scenario->finding [artifacts scenario comparator-config]
  (let [summary (:summary artifacts)
        canon (data/canonical-summary summary)
        sid (or (:id scenario) "unknown-scenario")
        sev (severity scenario)
        st-kind (status-kind scenario)
        classif (classification-for scenario)
        trace-info (compute-trace-digest artifacts sid)
        story-spec (narrative-artifact-spec artifacts scenario sid sev st-kind comparator-config trace-info)
        confidence-level (:level (confidence/normalize-confidence (:confidence story-spec)))]
    {:finding_id (finding-id (or (:run-id canon) (:run-id config/protocol-defaults)) sid)
     :scenario_id sid
     :kind (purpose->kind (:purpose scenario))
     :category (:finding-category @config/profile)
     :severity sev
     :status_kind st-kind
     :one_line_description (one-line-description scenario)
     :confidence confidence-level
     :classification classif
     :title (or (:title scenario) sid)
     :summary (one-line-description scenario)
     :metrics {:replay_success_pct (:replay-match-pct (data/narrative-metrics artifacts))}
     :evidence_refs [{:artifact "coverage"
                      :path [:scenarios sid]
                      :digest (:trace_digest trace-info)
                      :digest_status (:trace_digest_status trace-info)}]
     :story {:eligible true
             :priority (priority sev)
             :family (purpose->family (:purpose scenario))}
     :story_artifact_spec story-spec
     :outcome (canonical-outcome artifacts scenario sid sev st-kind classif story-spec)
     :provenance {:run_id (:run-id canon)
                  :git_sha (:git-sha canon)
                  :trace_digest (:trace_digest trace-info)
                  :trace_digest_status (:trace_digest_status trace-info)
                  :definitions/hash (defs/definitions-hash)}}))

(defn- counts [findings]
  (let [by-sev (frequencies (map :severity findings))
        by-kind (frequencies (map :kind findings))]
    {:critical_findings (get by-sev "critical" 0)
     :high_findings (get by-sev "high" 0)
     :medium_findings (get by-sev "medium" 0)
     :low_findings (get by-sev "low" 0)
     :missing_evidence (count (filter #(not= "computed" (get-in % [:provenance :trace_digest_status])) findings))
     :inconclusive (get by-kind "inconclusive_result" 0)
     :robustness_confirmations (get by-kind "robustness_confirmation" 0)}))

(defn generate-findings-bundle
  ([artifacts] (generate-findings-bundle artifacts {:comparator-config default-comparator-config}))
  ([artifacts {:keys [comparator-config] :or {comparator-config default-comparator-config}}]
   (let [scenarios (or (get-in artifacts [:coverage :scenarios]) [])
         summary (:summary artifacts)
         canon (data/canonical-summary summary)
         findings (->> scenarios
                       (map #(scenario->finding artifacts % comparator-config))
                       (sort-by (juxt (comp - :priority :story) :scenario_id))
                       vec)
         cnt (counts findings)]
     {:schema_version "speds.findings.v1"
      :comparator_config comparator-config
      :run {:run_id (:run-id canon)
            :generated_at (str (java.time.Instant/now))
            :git_sha (:git-sha canon)
            :suite_id (:suite-id @config/profile)
            :artifact_root (evcfg/artifact-dir)}
      :overall_status {:status (if (= "pass" (:overall-status canon)) "passed" "warning")
                       :status_kind (if (empty? findings) "no_findings_detected" "validated_with_gaps")
                       :confidence "medium"
                       :summary (if (empty? findings)
                                  "No negative findings were detected in this run, but this does not prove protocol safety."
                                  "Findings observed; review evidence and action queue for triage.")
                       :counts cnt}
      :findings findings
      :story_candidates (if (empty? findings)
                          [{:story_id "RUN-OVERVIEW-001"
                            :kind "run_overview"
                            :priority 50
                            :headline "No negative findings detected in this validation run"
                            :caveat "Absence of findings is not proof of absence."}]
                          [])
      :provenance {:source_artifacts [{:kind "summary" :path (:test-summary config/artifact-paths) :digest nil}
                                      {:kind "coverage" :path (:coverage config/artifact-paths) :digest nil}]
                   :definitions/hash (defs/definitions-hash)
                   :claim_map_path (str (evcfg/artifact-dir) "/claim-map.json")}})))

;; ──────────────────────────────────────────────────────────────────────────
;; Schema validation
;; ──────────────────────────────────────────────────────────────────────────

(def findings-bundle-schema
  "Malli schema for speds.findings.v1 bundle."
  (m/schema
   [:map {:closed true}
    [:schema_version [:enum "speds.findings.v1"]]
    [:comparator_config :map]
    [:run [:map {:closed true}
           [:run_id :string]
           [:generated_at :string]
           [:git_sha :string]
           [:suite_id :string]
           [:artifact_root :string]]]
    [:overall_status [:map {:closed true}
                      [:status [:enum "passed" "warning"]]
                      [:status_kind :string]
                      [:confidence :string]
                      [:summary :string]
                      [:counts :map]]]
    [:findings [:vector :map]]
    [:story_candidates [:vector :map]]
    [:provenance :map]]))

(defn- validate-findings-bundle!
  "Validate findings bundle against schema. Logs and returns bundle."
  [bundle]
  (if-let [errors (m/explain findings-bundle-schema bundle)]
    (let [msg (str "Findings bundle schema validation failed: " (me/humanize errors))]
      (log/warn! msg {:errors errors})
      bundle)
    bundle))

(defn save-findings!
  ([] (save-findings! (data/load-run-artifacts)))
  ([artifacts]
   (let [bundle (-> artifacts generate-findings-bundle validate-findings-bundle!)]
     (.mkdirs (java.io.File. (evcfg/artifact-dir)))
     (with-open [w (io/writer findings-path)]
       (json/write bundle w :indent true))
     bundle)))

(defn findings-fresh?
  "Returns true if the on-disk findings file is newer than the summary artifact."
  []
  (try
    (let [f (io/file findings-path)
          s (io/file (:test-summary config/artifact-paths))]
      (and (.exists f)
           (or (not (.exists s))
               (>= (.lastModified f) (.lastModified s)))))
    (catch Exception _ false)))

(defn load-findings []
  (common/read-json findings-path))
