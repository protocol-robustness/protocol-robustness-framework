(ns resolver-sim.scenario.theory-result
  "Theory evaluation result shaping.

  Canonical contract (human + fixture gate):
    :status, :reason, :falsified?, :evidence, :diagnostics

  Derived interpretation (nested under :diagnostics):
    :claim-status — whether the METRIC falsification track ran (not whether
                     the :claim-id hypothesis is true; see Claim boundaries)
    :falsification-status, :evidence-completeness, :grounded?,
    :assumption-status, :declared-assumptions, :theory-eval-profile

   (Legacy flat copies removed June 2026 — use :diagnostics only.)

   See docs/CDRS-v1.1-THEORY-SCHEMA.md — Claim boundaries & Theory result contract."
  (:require [resolver-sim.definitions.registry :as defs]
            [resolver-sim.scenario.equilibrium-result :as eq-result]
            [resolver-sim.scenario.theory-eval :as theory-eval]))

(def theory-result-schema-version "1.2")

(def evaluator-version "theory-eval-v2")

(def canonical-keys
  "Primary fields every consumer should read."
  [:status :reason :falsified? :evidence :diagnostics])

(def derived-diagnostic-keys
  [:claim-status
   :falsification-status
   :evidence-completeness
   :grounded?
   :assumption-status
   :declared-assumptions
   :theory-eval-profile
   :metric-status])

(defn result-status
  "Canonical gate status for fixtures and outcome-semantics."
  [result]
  (:status result))

(defn status-display-label
  "Short human label for a metric-track :status keyword (registry default)."
  [status]
  (or (:label (defs/status-def status))
      (:label (defs/status-def :not-evaluated))
      "Not evaluated"))

(defn result-display-label
  "Human label for a full theory result, with profile/grounding nuance when useful.

  Machine contract unchanged (:status keywords). Prefer this over raw status in
  notebooks and reports. See docs/CDRS-v1.1-THEORY-SCHEMA.md — Falsifiability glossary."
  [result]
  (let [status      (result-status result)
        diagnostics (or (:diagnostics result) {})
        profile     (:theory-eval-profile diagnostics)
        grounded?   (:grounded? diagnostics)
        completeness (:evidence-completeness diagnostics)]
    (case status
      :falsified     (status-display-label :falsified)
      :not-evaluated (status-display-label :not-evaluated)
      :inconclusive  (status-display-label :inconclusive)
      :not-falsified
      (cond
        (and (= profile :optimistic) (false? grounded?))
        "Not falsified under optimistic evaluation; not audit-grade because telemetry was incomplete"

        (and (= completeness :complete) (true? grounded?))
        "Not falsified in this replay; all referenced telemetry was available"

        :else
        (status-display-label :not-falsified))

      (status-display-label status))))

(defn- resolved-policy [opts]
  (:missing-metric-policy (theory-eval/resolve-theory-eval-opts opts)))

(defn metric-track-applicability-label
  "Human label for `:diagnostics :claim-status` — not 'claim status' (avoids accounting confusion)."
  [claim-status]
  (case claim-status
    :evaluated       "Metric track evaluated"
    :not-applicable  "Metric track not applicable (no metric disconfirmers)"
    :not-evaluated   "Metric track not evaluated"
    (str "Metric track: " (name (or claim-status :unknown)))))

(defn derive-claim-status
  [result]
  (cond
    (= (:reason result) :theory-missing) :not-evaluated
    (= (:reason result) :no-metric-falsification-claim) :not-applicable
    :else :evaluated))

(defn derive-evidence-completeness
  [result]
  (cond
    (= (:reason result) :theory-missing)     :absent
    (= (:reason result) :no-metric-falsification-claim) :not-required
    (= (:reason result) :none)               :not-required
    (= (:reason result) :empty-logical-operator) :invalid
    (= (:reason result) :metrics-missing-in-trace) :absent
    (#{:partial-metrics-missing :strict-missing-metrics} (:reason result)) :partial
    (seq (get-in result [:diagnostics :missing-metrics])) :partial
    :else :complete))

(defn derive-falsification-status
  [claim-status evidence-completeness metric-status]
  (cond
    (= claim-status :not-evaluated)           :not-applicable
    (= claim-status :not-applicable)          :not-applicable
    (= evidence-completeness :not-required)   :not-applicable
    (= metric-status :falsified)              :falsified
    (#{:absent :invalid} evidence-completeness) :not-applicable
    :else                                     :not-falsified))

(defn derive-suite-status
  [claim-status falsification-status evidence-completeness opts]
  (let [policy (resolved-policy opts)]
    (cond
      (= claim-status :not-evaluated) :not-evaluated
      (= evidence-completeness :not-required) :not-falsified
      (= falsification-status :falsified) :falsified
      (= evidence-completeness :invalid) :inconclusive
      (= evidence-completeness :absent) :inconclusive
      (and (= evidence-completeness :partial)
           (= policy :any-missing-inconclusive)) :inconclusive
      (and (= evidence-completeness :partial)
           (= policy :any-missing-fail)) :inconclusive
      (and (= evidence-completeness :partial)
           (= policy :all-missing-only)) :not-falsified
      (= falsification-status :not-falsified) :not-falsified
      (= falsification-status :not-applicable) :inconclusive
      :else :inconclusive)))

(defn derive-grounded?
  [status evidence-completeness diagnostics]
  (let [missing (get-in diagnostics [:missing-metrics])]
    (cond
      (= status :not-evaluated) false
      (= status :inconclusive) false
      (= evidence-completeness :absent) false
      (= evidence-completeness :invalid) false
      (and (= status :not-falsified)
           (or (= evidence-completeness :partial) (seq missing))) false
      :else true)))

(defn derive-assumption-status
  [theory]
  (cond
    (nil? theory) :not-declared
    (seq (:assumptions theory)) :unchecked
    :else :not-declared))

(defn- ungrounded-optimistic-warning
  [suite-status evidence-completeness profile-kw missing-metrics]
  (when (and (= profile-kw :optimistic)
             (= suite-status :not-falsified)
             (= evidence-completeness :partial))
    {:kind :ungrounded-optimistic-result
     :message "Some referenced telemetry was missing; this is not audit-grade evidence."
     :missing-metrics (vec (sort missing-metrics))}))

(defn derive-interpretation
  "Internal derived fields for diagnostics and optional legacy top-level copy."
  [result opts theory]
  (let [opts'                 (theory-eval/resolve-theory-eval-opts opts)
        profile-kw            (:theory-eval-profile opts')
        claim-status          (derive-claim-status result)
        evidence-completeness (derive-evidence-completeness result)
        metric-status         (or (:metric-status result) (:status result))
        falsification-status  (derive-falsification-status claim-status
                                                           evidence-completeness
                                                           metric-status)
        suite-status          (derive-suite-status claim-status
                                                   falsification-status
                                                   evidence-completeness
                                                   opts')
        diagnostics0        (:diagnostics result {})
        grounded?             (derive-grounded? suite-status evidence-completeness diagnostics0)
        assumption-status     (derive-assumption-status theory)
        declared-assumptions  (vec (or (:assumptions theory) []))
        missing-metrics       (get-in diagnostics0 [:missing-metrics] [])
        optimistic-warning    (ungrounded-optimistic-warning suite-status
                                                             evidence-completeness
                                                             profile-kw
                                                             missing-metrics)]
    {:status                suite-status
     :claim-status          claim-status
     :falsification-status  falsification-status
     :evidence-completeness evidence-completeness
     :grounded?             grounded?
     :assumption-status     assumption-status
     :declared-assumptions  declared-assumptions
     :theory-eval-profile   profile-kw
     :falsified?            (= falsification-status :falsified)
     :optimistic-warning    optimistic-warning}))

(defn attach-derived-diagnostics
  "Merge derived interpretation into :diagnostics (canonical placement)."
  [result derived]
  (update result :diagnostics merge
          (select-keys derived derived-diagnostic-keys)))

(defn attach-three-way-model
  "Finalize metric-track result: canonical :status + nested :diagnostics.

  (Legacy flat copies removed June 2026 — use :diagnostics only.)

  opts:
    :theory — theory block for assumption fields"
  [result opts & {:keys [theory]}]
  (let [opts'     (theory-eval/resolve-theory-eval-opts opts)
        derived   (derive-interpretation result opts' theory)
        warnings  (cond-> (vec (or (get-in result [:diagnostics :warnings]) []))
                    (:optimistic-warning derived)
                    (conj (:optimistic-warning derived)))]
    (cond-> (-> result
                (attach-derived-diagnostics derived)
                (assoc :theory-result-schema-version theory-result-schema-version
                       :status (:status derived)
                       :falsified? (:falsified? derived)))
      (seq warnings)
      (assoc-in [:diagnostics :warnings] warnings))))

(defn summarize
  "Compact map for reports, goldens, and notebooks.

  opts:
    :include-derived-statuses? — when true, include legacy flat derived fields"
  ([result]
   (summarize result {}))
  ([result {:keys [include-derived-statuses?]}]
   (let [base (merge (select-keys result [:theory-result-schema-version :status :reason
                                          :falsified? :diagnostics])
                     {:display-label (result-display-label result)})]
     (if include-derived-statuses?
       (merge base (select-keys (or (:diagnostics result) {})
                                [:claim-status :falsification-status :evidence-completeness
                                 :grounded? :assumption-status :theory-eval-profile
                                 :declared-assumptions]))
       base))))

(defn- normalize-claim-id
  [id]
  (cond
    (keyword? id) id
    (string? id)  (keyword id)
    :else id))

(defn golden-snapshot
  "Stable thin theory slice for golden report.edn (canonical + mechanism roll-ups)."
  [theory-res theory-decl]
  (when theory-res
    (merge {:evaluator-version evaluator-version
            :claim-id (normalize-claim-id (:claim-id theory-decl))}
           (summarize theory-res)
           {:mechanism-status (or (:mechanism-status theory-res) :not-checked)
            :equilibrium-status (or (:equilibrium-status theory-res) :not-checked)
            :mechanism-summary (eq-result/summarize-domain-results (:mechanism-results theory-res {}))
            :equilibrium-summary (eq-result/summarize-domain-results (:equilibrium-results theory-res {}))
            :mechanism-reasons (eq-result/domain-status-reasons (:mechanism-results theory-res {}))
            :equilibrium-reasons (eq-result/domain-status-reasons (:equilibrium-results theory-res {}))})))
