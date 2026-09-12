(ns resolver-sim.notebook-support.speds.issues
  "Issue-contract generation for SPEDS.
   Converts trial artifacts into deterministic issue bundles used by story rendering."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [resolver-sim.logging :as log]
            [resolver-sim.definitions.registry :as defs]
            [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.notebook-support.common :as common]
            [resolver-sim.notebook-support.speds.data :as data]
            [resolver-sim.notebook-support.speds.findings :as findings]
            [resolver-sim.notebook-support.speds.config :as config]))

(def issues-path (evcfg/artifact-path :issues))
(def comparator-shadow-path (str (evcfg/artifact-dir) "/comparator-shadow.json"))

(def required-issue-keys
  #{:issue/id :scenario/id :kind :severity :status-kind :title
    :story/family :priority :evidence/refs :provenance :one_line_description})

(defn- priority [sev]
  (case sev :high 90 :medium 60 :low 30 10))

(defn- truncate-safe [s n]
  (let [v (str (or s ""))]
    (subs v 0 (min n (count v)))))

(def default-issue-policy
  {:open-issue-when [{:status-kinds #{"observed"} :min-severity :medium}
                     {:kinds #{"missing_evidence" "invariant_failure" "inconclusive_result"}}]
   :do-not-open-for #{"robustness_confirmation" "expected_negative"}})

(defn- severity-rank [s]
  (let [k (keyword (str/lower-case (name s)))]
    (or (get-in (defs/severity-def k) [:rank])
        (case k
          :critical 4
          :high 3
          :medium 2
          :low 1
          0))))

(defn- norm-token [v]
  (-> (cond
        (keyword? v) (name v)
        (string? v) v
        (nil? v) ""
        :else (str v))
      str/lower-case))

(defn- norm-token-set [xs]
  (set (map norm-token (or xs #{}))))

(defn- should-open-issue? [finding policy]
  (let [kind (norm-token (:kind finding))
        status-kind (norm-token (:status_kind finding))
        sev (keyword (str/lower-case (str (:severity finding))))
        blocked-kinds (norm-token-set (:do-not-open-for policy))
        blocked? (contains? blocked-kinds kind)]
    (and (not blocked?)
         (or (some (fn [{:keys [kinds]}]
                     (and kinds (contains? (norm-token-set kinds) kind)))
                   (:open-issue-when policy))
             (some (fn [{:keys [status-kinds min-severity]}]
                     (and (seq status-kinds)
                          (contains? (norm-token-set status-kinds) status-kind)
                          (>= (severity-rank sev) (severity-rank (or min-severity :low)))))
                   (:open-issue-when policy))))))

(defn finding->issue [finding run-id]
  (let [one-line (or (:one_line_description finding)
                     (:summary finding)
                     (:title finding)
                     "Issue derived from finding")]
    {:issue/id (str "ISSUE-" (or run-id (:run-id config/protocol-defaults)) "-" (truncate-safe (Math/abs (hash (:finding_id finding))) 6))
     :source-finding-ids [(:finding_id finding)]
     :scenario/id (:scenario_id finding)
     :kind :policy-projected
     :status-kind :open
     :type :protocol_risk
     :status :open
     :severity (keyword (str/lower-case (str (:severity finding))))
     :priority (or (get-in finding [:story :priority]) 50)
     :title (str "Investigate: " (:title finding))
     :one_line_description one-line
     :summary one-line
     :story/family (keyword (str/lower-case (str (get-in finding [:story :family] "scenario_deep_dive"))))
     :evidence/refs (vec (map (fn [r] {:artifact (:artifact r) :path (:path r)}) (or (:evidence_refs finding) [])))
     :provenance {:run-id run-id
                  :git-sha (get-in finding [:provenance :git_sha])
                  :trace-digest (get-in finding [:provenance :trace_digest])}}))

(defn generate-issues-bundle
  "Builds actionable issues from findings + issue policy."
  ([artifacts] (generate-issues-bundle artifacts default-issue-policy {}))
  ([artifacts policy] (generate-issues-bundle artifacts policy {}))
  ([artifacts policy {:keys [comparator-config]}]
   (let [findings-bundle (let [cached (findings/load-findings)]
                           (if (and cached (findings/findings-fresh?))
                             cached
                             (findings/generate-findings-bundle
                              artifacts
                              {:comparator-config (or comparator-config findings/default-comparator-config)})))
         run-id (get-in findings-bundle [:run :run_id])
         comparator-config (get findings-bundle :comparator_config findings/default-comparator-config)
         findings-list (or (:findings findings-bundle) [])
         issues (->> findings-list
                     (filter #(should-open-issue? % policy))
                     (map #(finding->issue % run-id))
                     (sort-by (juxt (comp - :priority) :scenario/id))
                     vec)]
     {:schema_version "speds-issues-v1"
      :run_id run-id
      :definitions_hash (defs/definitions-hash)
      :comparator_config comparator-config
      :generated-at (str (java.time.Instant/now))
      :issue-count (count issues)
      :issues issues
      :policy policy})))

;; ──────────────────────────────────────────────────────────────────────────
;; Schema validation
;; ──────────────────────────────────────────────────────────────────────────

(def issues-bundle-schema
  "Malli schema for speds-issues-v1 bundle."
  (m/schema
   [:map {:closed true}
    [:schema_version [:enum "speds-issues-v1"]]
    [:run_id [:maybe :string]]
    [:definitions_hash :string]
    [:comparator_config :map]
    [:generated-at :string]
    [:issue-count :int]
    [:issues [:vector :map]]
    [:policy :map]]))

(defn- validate-issues-bundle!
  "Validate issues bundle against schema. Logs and returns bundle."
  [bundle]
  (if-let [errors (m/explain issues-bundle-schema bundle)]
    (let [msg (str "Issues bundle schema validation failed: " (me/humanize errors))]
      (log/warn! msg {:errors errors})
      bundle)
    bundle))

(defn save-issues!
  "Writes issues bundle to results/test-artifacts/issues.json"
  ([] (save-issues! (data/load-run-artifacts)))
  ([artifacts]
   (let [bundle (-> artifacts generate-issues-bundle validate-issues-bundle!)]
     (.mkdirs (java.io.File. (evcfg/artifact-dir)))
     (with-open [w (clojure.java.io/writer issues-path)]
       (json/write bundle w :indent true))
     bundle)))

(defn load-issues []
  (common/read-json issues-path))

(defn generate-comparator-shadow-report
  "Runs findings/issues generation under multiple comparator strategies and
   returns a side-by-side summary for safe rollout evaluation.

   opts:
   {:strategies [:nearest-baseline-by-id :matched-by-purpose :matched-by-tags]
    :enabled? true
    :policy default-issue-policy}"
  ([artifacts] (generate-comparator-shadow-report artifacts {}))
  ([artifacts {:keys [strategies enabled? policy]
               :or {strategies [:nearest-baseline-by-id :matched-by-purpose :matched-by-tags]
                    enabled? true
                    policy default-issue-policy}}]
   (let [runs (mapv
               (fn [strategy]
                 (let [bundle (generate-issues-bundle
                               artifacts
                               policy
                               {:comparator-config {:strategy strategy :enabled? enabled?}})
                       findings-bundle (findings/generate-findings-bundle
                                        artifacts
                                        {:comparator-config {:strategy strategy :enabled? enabled?}})]
                   {:strategy strategy
                    :enabled? enabled?
                    :finding-count (count (or (:findings findings-bundle) []))
                    :issue-count (:issue-count bundle)
                    :run-id (:run_id bundle)
                    :comparator-config (:comparator_config bundle)}))
               strategies)]
     {:schema_version "speds-comparator-shadow-v1"
      :definitions_hash (defs/definitions-hash)
      :generated-at (str (java.time.Instant/now))
      :strategies strategies
      :runs runs})))

(defn save-comparator-shadow-report!
  "Writes comparator shadow report to results/test-artifacts/comparator-shadow.json"
  ([artifacts] (save-comparator-shadow-report! artifacts {}))
  ([artifacts opts]
   (let [report (generate-comparator-shadow-report artifacts opts)]
     (.mkdirs (java.io.File. (evcfg/artifact-dir)))
     (with-open [w (clojure.java.io/writer comparator-shadow-path)]
       (json/write report w :indent true))
     report)))

(defn load-comparator-shadow-report []
  (common/read-json comparator-shadow-path))
