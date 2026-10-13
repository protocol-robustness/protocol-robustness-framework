(ns scripts.scenarios.generate-core-docs
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.definitions.registry :as defs]
            [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.scenario.schema-profile :as schema-profile]))

(def evidence-semantics-path "docs/generated/evidence-semantics.md")
(def scenario-contract-path "docs/generated/scenario-contract.md")
(def invariant-catalog-path "docs/generated/invariant-catalog.md")
(def claim-registry-path "docs/generated/claim-registry.md")
(def transition-guard-catalog-path "docs/generated/transition-guard-catalog.md")
(def evidence-artifact-contract-path "docs/generated/evidence-artifact-contract.md")
(def benchmark-authoring-guide-path "docs/generated/benchmark-authoring-guide.md")

(defn- write-if-changed! [path content]
  (let [f (io/file path)
        current (when (.exists f) (slurp f))]
    (when-not (= current content)
      (.mkdirs (.getParentFile f))
      (spit f content))
    {:path path :changed? (not= current content)}))

(defn- required-fields-table []
  (let [byv (:required-fields-by-version schema-profile/default-profile)]
    (apply str
           (for [[v fields] (sort-by key byv)]
             (str "| `" v "` | "
                  (if (seq fields)
                    (str/join ", " (map #(str "`" (name %) "`") fields))
                    "_none_")
                  " |\n")))))

(defn- purpose-reqs-table []
  (let [reqs (:purpose-requirements schema-profile/default-profile)]
    (apply str
           (for [[p cfg] (sort-by key reqs)]
             (str "| `" (name p) "` | "
                  (if (:requires-theory? cfg) "yes" "no") " | "
                  (if (:requires-theory-or-expectations? cfg) "yes" "no") " |\n")))))

(defn- evidence-semantics-md []
  (str "# Evidence Semantics Reference (Generated)\n\n"
       "Source of truth: `src/resolver_sim/definitions/registry.clj`, `src/resolver_sim/scenario/outcome_semantics.clj`.\n\n"
       "Definitions hash: `" (defs/definitions-hash) "`\n\n"
       "## Statuses\n\n"
       "| Status ID | Label | Story Family Mapping |\n"
       "|---|---|---|\n"
       (apply str
              (for [[k v] (sort-by key defs/statuses)]
                (str "| `" (name k) "` | " (:label v) " | `"
                     (name (defs/status->story-family* k)) "` |\n")))
       "\n## Severities\n\n"
       "| Severity | Rank |\n|---|---:|\n"
       (apply str
              (for [[k v] (sort-by key defs/severities)]
                (str "| `" (name k) "` | " (:rank v) " |\n")))
       "\n## Purposes\n\n"
       "| Purpose | Label | Default Story Family | SPEDS Kind |\n"
       "|---|---|---|---|\n"
       (apply str
              (for [[k v] (sort-by key defs/purposes)]
                (str "| `" (name k) "` | " (:label v) " | `"
                     (name (:default-story-family v)) "` | "
                     (defs/purpose->kind k) " |\n")))
       "\n## Confidence Levels\n\n"
       "| Level | Score |\n|---|---:|\n"
       (apply str
              (for [[k v] (sort-by key defs/confidence-levels)]
                (str "| `" (name k) "` | " (:score v) " |\n")))
       "\n## Story Families\n\n"
       "| Story Family | Label |\n|---|---|\n"
       (apply str
              (for [[k v] (sort-by key defs/story-families)]
                (str "| `" (name k) "` | " (:label v) " |\n")))))

(defn- load-scenario-schema []
  (json/read-str (slurp "schemas/scenario-v1.json") :key-fn keyword))

(defn- load-scenario-examples []
  (->> (file-seq (io/file "scenarios"))
       (filter #(.isFile ^java.io.File %))
       (filter #(str/ends-with? (.getName ^java.io.File %) ".json"))
       (take 5)
       (keep (fn [f]
               (try
                 (let [sc (json/read-str (slurp f) :key-fn keyword)]
                   {:file (.getName ^java.io.File f)
                    :id (or (:scenario-id sc) (:id sc) "unknown")
                    :schema-version (or (:schema-version sc) "unknown")
                    :actions (->> (:events sc)
                                  (map :action)
                                  (filter some?)
                                  (map name)
                                  distinct
                                  sort)})
                 (catch Exception _ nil))))))

(defn- load-coverage []
  (json/read-str (slurp (evcfg/artifact-path :coverage)) :key-fn keyword))

(defn- read-json-file [path]
  (when (.exists (io/file path))
    (json/read-str (slurp path) :key-fn keyword)))

(defn- keys-table [m]
  (if (map? m)
    (apply str (for [k (sort (keys m))] (str "| `" (name k) "` |\n")))
    "| _missing_ |\n"))

(defn- coverage-status [hit-count]
  (if (pos? (long (or hit-count 0))) "covered" "missing"))

(defn- scenario-contract-md []
  (let [schema (load-scenario-schema)
        req (get schema :required)
        props (-> schema :properties keys sort)
        supported (sort (:supported-versions schema-profile/default-profile))]
    (str "# Scenario Contract Reference (Generated)\n\n"
         "Source of truth: `src/resolver_sim/scenario/schema_profile.clj`, `schemas/scenario-v1.json`, replay validation rules.\n\n"
         "## Supported Schema Versions\n\n"
         "- " (str/join "\n- " (map #(str "`" % "`") supported)) "\n\n"
         "Enriched/default reporting version: `" (schema-profile/enriched-version) "`\n\n"
         "## Required Fields by Version Profile\n\n"
         "| Version | Required fields |\n|---|---|\n"
         (required-fields-table)
         "\n## Purpose-specific Validation Constraints\n\n"
         "| Purpose | Requires theory block | Requires theory OR expectations |\n"
         "|---|---|---|\n"
         (purpose-reqs-table)
         "\n## JSON Scenario v1 Required Top-level Fields\n\n"
         (if (seq req)
           (str "- " (str/join "\n- " (map #(str "`" %) req)) "\n\n")
           "_none_\n\n")
         "## JSON Scenario v1 Top-level Properties\n\n"
         "| Property |\n|---|\n"
         (apply str (for [p props] (str "| `" (name p) "` |\n")))
         "\n## Notes\n\n"
         "- Event sequence must be contiguous (`0..n-1`) and monotonic in time.\n"
         "- Event agents must exist in the scenario `agents` array.\n"
         "- Unknown metric references in expectations/theory are rejected by replay validation.\n"
         "- `theory-falsification` requires `:theory`; `adversarial-robustness` requires theory or non-trivial expectations.\n")))

(defn- invariant-catalog-md []
  (let [invs (sort-by key defs/invariants)]
    (str "# Invariant Catalog (Generated)\n\n"
         "Source of truth: `src/resolver_sim/definitions/registry.clj` (`invariants`).\n\n"
         "Definitions hash: `" (defs/definitions-hash) "`\n\n"
         "| Invariant ID | Label | Default Severity | Class | Related Transitions | Related Scenario Families | Artifact Field(s) |\n"
         "|---|---|---|---|---|---|---|\n"
         (apply str
                 (for [[inv meta] invs]
                  (let [imeta (defs/invariant-meta inv)
                         inv-id (or (:invariant/id meta) inv)
                        rel-trans (or (:related-transitions imeta) [])
                        rel-fams (or (:related-scenario-families imeta) [])]
                  (str "| `" (name inv-id) "` | "
                       (:label meta) " | `" (name (:default-severity meta)) "` | `"
                       (name (:class meta)) "` | "
                       (if (seq rel-trans)
                         (str/join ", " (map #(str "`" (name %) "`") rel-trans))
                         "_none_")
                       " | "
                       (if (seq rel-fams)
                         (str/join ", " (map #(str "`" (name %) "`") rel-fams))
                         "_none_")
                       " | `metrics.invariant-results`, `metrics.invariant-violations` |\n"))))
         "\n## Interpretation\n\n"
         "- **Failure meaning:** a failed invariant indicates a protocol property violation in simulation outputs.\n"
         "- **Related transitions/scenario families:** sourced from `definitions.registry/invariant-metadata`.\n"
         "- **Artifact fields:** current replay/test artifacts expose aggregate and per-invariant outcome fields under metrics.\n")))

(defn- claim-registry-md []
  (let [claims (sort-by key defs/claims)]
    (str "# Claim Registry (Generated)\n\n"
         "Source of truth: `src/resolver_sim/definitions/registry.clj` (`claims`, `claim-scenario-map`).\n\n"
         "Definitions hash: `" (defs/definitions-hash) "`\n\n"
         "| Claim ID | Title | Type | Evidence mode | Supporting scenarios | Falsifying scenarios | Related invariants |\n"
         "|---|---|---|---|---|---|---|\n"
         (apply str
                (for [[cid cmeta] claims]
                  (let [sc (defs/claim-scenarios cid)]
                    (str "| `" (name (or (:claim/id cmeta) cid)) "` | "
                         (:claim/title cmeta) " | `" (name (:claim/type cmeta)) "` | `"
                         (name (:claim/evidence-mode cmeta)) "` | "
                         (if (seq (:supporting sc))
                           (str/join ", " (map #(str "`" % "`") (:supporting sc)))
                           "_none_")
                         " | "
                         (if (seq (:falsifying sc))
                           (str/join ", " (map #(str "`" % "`") (:falsifying sc)))
                           "_none_")
                         " | "
                         (if (seq (:claim/related-invariants cmeta))
                           (str/join ", " (map #(str "`" (name %) "`") (:claim/related-invariants cmeta)))
                           "_none_")
                         " |\n")))))))

(defn- transition-guard-catalog-md []
  (let [coverage (load-coverage)
        hit-freq (or (:transition-hit-freq coverage) {})
        transitions (sort-by key defs/transitions)]
    (str "# Transition & Guard Catalog (Generated)\n\n"
         "Source of truth: `definitions.registry/transitions`, `definitions.registry/transition-metadata`, `results/test-artifacts/coverage.json`.\n\n"
         "Definitions hash: `" (defs/definitions-hash) "`\n\n"
         "| Transition ID | Label | Allowed sources | Allowed targets | Guards | Actor permissions | Pause effect | Coverage status | Hit count |\n"
         "|---|---|---|---|---|---|---|---|---:|\n"
         (apply str
                (for [[tr tdef] transitions]
                  (let [m (defs/transition-meta tr)
                        hc (get hit-freq (name tr) 0)]
                    (str "| `" (name tr) "` | " (:label tdef) " | "
                         (if (seq (:allowed-sources m))
                           (str/join ", " (map #(str "`" (name %) "`") (:allowed-sources m)))
                           "_none_")
                         " | "
                         (if (seq (:allowed-targets m))
                           (str/join ", " (map #(str "`" (name %) "`") (:allowed-targets m)))
                           "_none_")
                         " | "
                         (if (seq (:guards m))
                           (str/join ", " (map #(str "`" (name %) "`") (:guards m)))
                           "_none_")
                         " | "
                         (if (seq (:actor-permissions m))
                           (str/join ", " (map #(str "`" (name %) "`") (:actor-permissions m)))
                           "_none_")
                         " | `" (name (or (:pause-effect m) :unspecified)) "`"
                         " | " (coverage-status hc)
                         " | " hc " |\n")))))))

(defn- evidence-artifact-contract-md []
  (let [summary (read-json-file (evcfg/artifact-path :test-summary))
        coverage (read-json-file (evcfg/artifact-path :coverage))
        findings (read-json-file (evcfg/artifact-path :findings))
        issues (read-json-file (evcfg/artifact-path :issues))
        cdrs-trace-schema (read-json-file "schemas/cdrs-trace-v0.2.schema.json")
        cdrs-event-schema (read-json-file "schemas/cdrs-event-v0.2.schema.json")]
    (str "# Evidence Artifact Contract (Generated)\n\n"
         (str "Source of truth: current emitted artifacts under `" (evcfg/artifact-dir) "`, CDRS schemas under `schemas/`, and semantic registry hash.\n\n")
         "Definitions hash: `" (defs/definitions-hash) "`\n\n"
         "## Canonical Artifact Set\n\n"
         (str "- `" (evcfg/artifact-path :test-summary) "`\n"
              "- `" (evcfg/artifact-path :coverage) "`\n"
              "- `" (evcfg/artifact-path :findings) "`\n"
              "- `" (evcfg/artifact-path :issues) "`\n\n")
         "## Required Provenance Fields (run-level)\n\n"
         "- `run_id` / `run.id`\n"
         "- `git_sha` where available\n"
         "- `definitions/hash` or derived semantic hash alignment\n"
         "- generation timestamp\n\n"
         "## Top-level Contract: test-summary.json\n\n"
         "| Key |\n|---|\n"
         (keys-table summary)
         "\n## Top-level Contract: coverage.json\n\n"
         "| Key |\n|---|\n"
         (keys-table coverage)
         "\n## Top-level Contract: findings.json\n\n"
         "| Key |\n|---|\n"
         (keys-table findings)
         "\n## Top-level Contract: issues.json\n\n"
         "| Key |\n|---|\n"
         (keys-table issues)
         "\n## CDRS Trace/Event Required Fields\n\n"
         "### cdrs-trace-v0.2 required\n\n"
         (if-let [req (:required cdrs-trace-schema)]
           (str "- " (str/join "\n- " (map #(str "`" %) req)) "\n\n")
           "_missing_\n\n")
         "### cdrs-event-v0.2 required\n\n"
         (if-let [req (:required cdrs-event-schema)]
           (str "- " (str/join "\n- " (map #(str "`" %) req)) "\n\n")
           "_missing_\n\n")
         "## Validation Notes\n\n"
         "- Contract is additive: unknown fields may exist, but listed top-level keys are expected for current generated artifacts.\n"
         "- CI drift checks enforce this document is regenerated whenever artifact structures change.\n"
         "- External verifiers should check run id, git sha, and definitions hash coherence before trusting claims.\n")))

(defn- benchmark-authoring-guide-md []
  (let [schema (load-scenario-schema)
        req-top (:required schema)
        event-req (get-in schema [:properties :events :items :required])
        event-actions (get-in schema [:properties :events :items :properties :action :enum])
        examples (load-scenario-examples)]
    (str "# Benchmark Authoring Guide (Generated)\n\n"
         "Source of truth: scenario contract schema, canonical transition registry, and repository scenario examples.\n\n"
         "Definitions hash: `" (defs/definitions-hash) "`\n\n"
         "## 1) Required top-level fields\n\n"
         (if (seq req-top)
           (str "- " (str/join "\n- " (map #(str "`" %) req-top)) "\n\n")
           "_none_\n\n")
         "## 2) Required event fields\n\n"
         (if (seq event-req)
           (str "- " (str/join "\n- " (map #(str "`" %) event-req)) "\n\n")
           "_none_\n\n")
         "## 3) Supported action names\n\n"
         (if (seq event-actions)
           (str "- " (str/join "\n- " (map #(str "`" % "`") event-actions)) "\n\n")
           "_none_\n\n")
         "## 4) Canonical transition vocabulary\n\n"
         "| Transition ID | Label |\n|---|---|\n"
         (apply str
                (for [[k v] (sort-by key defs/transitions)]
                  (str "| `" (name k) "` | " (:label v) " |\n")))
         "\n## 5) Example scenarios (generated from repo)\n\n"
         "| File | Scenario ID | Schema version | Actions present |\n|---|---|---|---|\n"
         (apply str
                (for [{:keys [file id schema-version actions]} examples]
                  (str "| `" file "` | `" id "` | `" schema-version "` | "
                       (if (seq actions) (str/join ", " (map #(str "`" % "`") actions)) "_none_")
                       " |\n")))
         "\n## 6) Authoring checklist\n\n"
         "- Keep `seq` contiguous starting at `0` and monotonic.\n"
         "- Ensure `time` is monotonic non-decreasing.\n"
         "- Ensure every `agent` in events exists in the top-level `agents` array.\n"
         "- Prefer canonical action names from this guide.\n"
         "- Include `purpose`/threat tags where your workflow expects narrative classification.\n"
         "- Run replay + docs checks before publishing benchmark artifacts.\n")))

(defn- generate! []
  (let [r1 (write-if-changed! evidence-semantics-path (evidence-semantics-md))
        r2 (write-if-changed! scenario-contract-path (scenario-contract-md))
        r3 (write-if-changed! invariant-catalog-path (invariant-catalog-md))
        r4 (write-if-changed! transition-guard-catalog-path (transition-guard-catalog-md))
        r5 (write-if-changed! evidence-artifact-contract-path (evidence-artifact-contract-md))
        r6 (write-if-changed! benchmark-authoring-guide-path (benchmark-authoring-guide-md))
        r7 (write-if-changed! claim-registry-path (claim-registry-md))]
    (println "Generated core docs:")
    (println "-" (:path r1) "changed?" (:changed? r1))
    (println "-" (:path r2) "changed?" (:changed? r2))
    (println "-" (:path r3) "changed?" (:changed? r3))
    (println "-" (:path r4) "changed?" (:changed? r4))
    (println "-" (:path r5) "changed?" (:changed? r5))
    (println "-" (:path r6) "changed?" (:changed? r6))
    (println "-" (:path r7) "changed?" (:changed? r7))))

(defn- check! []
  (let [exp1 (evidence-semantics-md)
        exp2 (scenario-contract-md)
        exp3 (invariant-catalog-md)
        exp4 (transition-guard-catalog-md)
        exp5 (evidence-artifact-contract-md)
        exp6 (benchmark-authoring-guide-md)
        exp7 (claim-registry-md)
        cur1 (when (.exists (io/file evidence-semantics-path)) (slurp evidence-semantics-path))
        cur2 (when (.exists (io/file scenario-contract-path)) (slurp scenario-contract-path))
        cur3 (when (.exists (io/file invariant-catalog-path)) (slurp invariant-catalog-path))
        cur4 (when (.exists (io/file transition-guard-catalog-path)) (slurp transition-guard-catalog-path))
        cur5 (when (.exists (io/file evidence-artifact-contract-path)) (slurp evidence-artifact-contract-path))
        cur6 (when (.exists (io/file benchmark-authoring-guide-path)) (slurp benchmark-authoring-guide-path))
        cur7 (when (.exists (io/file claim-registry-path)) (slurp claim-registry-path))]
    (when (not= exp1 cur1)
      (binding [*out* *err*]
        (println "Generated doc stale:" evidence-semantics-path)
        (println "Run: clojure scripts/scenarios/generate_core_docs.clj"))
      (System/exit 1))
    (when (not= exp2 cur2)
      (binding [*out* *err*]
        (println "Generated doc stale:" scenario-contract-path)
        (println "Run: clojure scripts/scenarios/generate_core_docs.clj"))
      (System/exit 1))
    (when (not= exp3 cur3)
      (binding [*out* *err*]
        (println "Generated doc stale:" invariant-catalog-path)
        (println "Run: clojure scripts/scenarios/generate_core_docs.clj"))
      (System/exit 1))
    (when (not= exp4 cur4)
      (binding [*out* *err*]
        (println "Generated doc stale:" transition-guard-catalog-path)
        (println "Run: clojure scripts/scenarios/generate_core_docs.clj"))
      (System/exit 1))
    (when (not= exp5 cur5)
      (binding [*out* *err*]
        (println "Generated doc stale:" evidence-artifact-contract-path)
        (println "Run: clojure scripts/scenarios/generate_core_docs.clj"))
      (System/exit 1))
    (when (not= exp6 cur6)
      (binding [*out* *err*]
        (println "Generated doc stale:" benchmark-authoring-guide-path)
        (println "Run: clojure scripts/scenarios/generate_core_docs.clj"))
      (System/exit 1))
    (when (not= exp7 cur7)
      (binding [*out* *err*]
        (println "Generated doc stale:" claim-registry-path)
        (println "Run: clojure scripts/scenarios/generate_core_docs.clj"))
      (System/exit 1))
    (println "Core generated docs are up to date.")))

(defn -main [& args]
  (if (= "--check" (first args))
    (check!)
    (generate!)))

(apply -main *command-line-args*)
