(ns resolver-sim.benchmark.reversal-audit
  "Pure projection and Markdown rendering for reversal-slash audit records.

   This namespace intentionally depends only on data present in a world or
   benchmark result. It neither mutates worlds nor evaluates protocol rules."
  (:require [clojure.string :as str]))

(defn- workflow-id
  [slash]
  (or (:slash/workflow-id slash) (:workflow-id slash)))

(defn- slash-level
  [slash]
  (or (:slash/level slash) (:level slash)))

(defn- decision-summary
  [decision]
  (when decision
    (cond-> (select-keys decision [:resolver :is-release :resolution :resolved-at
                                   :decision-id :evidence-hash])
      (contains? decision :is-release)
      (assoc :outcome (if (:is-release decision) :release :refund)))))

(defn- current-decision
  [world workflow-id level]
  (let [next-decision (get-in world [:previous-decisions workflow-id (inc level)])
        transfer (get-in world [:escrow-transfers workflow-id])
        world-level (get-in world [:dispute-levels workflow-id])]
    (or next-decision
        ;; The transfer holds the current resolution only when it is the level
        ;; that created this reversal; otherwise it could describe a later appeal.
        (when (= world-level (inc level)) (:resolution transfer)))))

(defn- infer-track
  [world workflow-id slash]
  (if (or (get-in world [:evidence-updated? workflow-id])
          (#{:pending :appealed} (:status slash)))
    :new-evidence-pending
    :immediate))

(defn- warning-flags
  [world slash workflow-id level prior current track]
  (let [status (:status slash)
        amount (:amount slash)
        held (:appeal-bond-held slash 0)
        custody (get-in world [:appeal-bond-custody (:slash/id slash)])]
    (vec (remove nil?
                 [(when (nil? workflow-id) :missing-workflow-id)
                  (when (nil? level) :missing-level)
                  (when (nil? (:resolver slash)) :missing-resolver)
                  (when-not (number? amount) :invalid-amount)
                  (when (and (number? amount) (neg? amount)) :negative-amount)
                  (when-not prior :missing-prior-decision)
                  (when-not current :missing-current-decision)
                  (when (and prior current
                             (= (:is-release prior) (:is-release current)))
                    :decisions-do-not-show-reversal)
                  (when (and (= track :immediate)
                             (#{:pending :appealed} status))
                    :immediate-track-has-pending-status)
                  (when (and (pos? (or held 0))
                             (not= :appealed status))
                    :appeal-bond-held-outside-appeal)
                  (when (and (pos? (or held 0)) (nil? custody))
                    :missing-appeal-bond-custody)
                  (when (and (#{:pending :appealed} status)
                             (not (pos? (or (:appeal-deadline slash) 0))))
                    :pending-status-without-appeal-deadline)]))))

(defn reversal-entries
  "Return normalized audit entries for `:reason :reversal` slash records in world.

   Entries are stable-sorted by workflow, reversal level, and slash id. `:track`
   is inferred as `:new-evidence-pending` when the workflow evidence flag is set
   or the slash remains pending/appealed; otherwise it is `:immediate`. Missing
   historical data is represented by nil fields and `:warning-flags`."
  [world]
  (->> (concat (map (fn [[id slash]] [id slash false])
                    (:pending-fraud-slashes world {}))
               (map (fn [[id slash]] [id slash true])
                    (:reversal-slash-history world {})))
       (keep (fn [[entry-id slash archived?]]
               (when (= :reversal (:reason slash))
                 (let [id (or (:slash/id slash) entry-id)
                       workflow (workflow-id slash)
                       level (slash-level slash)
                       prior (when (and (some? workflow) (number? level))
                               (get-in world [:previous-decisions workflow level]))
                       current (when (and (some? workflow) (number? level))
                                 (current-decision world workflow level))
                       track (infer-track world workflow slash)
                       appeal-custody (get-in world [:appeal-bond-custody id])]
                   {:slash-id id
                    :workflow-id workflow
                    :level level
                    :prior-decision (decision-summary prior)
                    :current-decision (decision-summary current)
                    :resolver (:resolver slash)
                    :track track
                    :new-evidence? (boolean (get-in world [:evidence-updated? workflow]))
                    :status (:status slash)
                    :amount (:amount slash)
                    :token (:token slash)
                    :basis-amount (:basis-amount slash)
                    :basis-kind (:basis-kind slash)
                    :slash-bps (:slash-bps slash)
                    :appeal {:deadline (:appeal-deadline slash)
                             :bond-held (:appeal-bond-held slash)
                             :contest-deadline (:contest-deadline slash)
                             :custody appeal-custody}
                    :proposed-at (:proposed-at slash)
                    :reversed-at (:reversed-at slash)
                    :reversed-by-level (:reversed-by-level slash)
                    :archived? archived?
                    :cleanup-at (:cleanup-at slash)
                    :cleanup-reason (:cleanup-reason slash)
                    :warning-flags (warning-flags world slash workflow level prior current track)
                    :slash slash}))))
       (sort-by (juxt (comp str :workflow-id)
                      #(or (:level %) -1)
                      (comp str :slash-id)))
       vec))

(defn project-world
  "Project one world into a reversal audit result suitable for review formatting."
  [world]
  (let [entries (reversal-entries world)]
    {:reversal-audit/entries entries
     :reversal-audit/count (count entries)
     :reversal-audit/warning-count (reduce + (map #(count (:warning-flags %)) entries))}))

(defn- result-world
  [result]
  (or (:world result) (:final-world result) (:scenario/world result)))

(defn worlds
  "Derive named worlds from a world, a benchmark bundle, result maps, or a
   collection of any of those shapes. Results without an embedded world are
   omitted because an audit cannot be derived from their summary fields alone."
  [input]
  (cond
    (nil? input) []
    (contains? input :pending-fraud-slashes) [{:label (:scenario/id input) :world input}]
    (contains? input :results)
    (mapcat worlds (:results input))
    (result-world input)
    [{:label (or (:scenario/id input) (:simulator/scenario-path input) (:file input))
      :world (result-world input)}]
    (sequential? input) (mapcat worlds input)
    :else []))

(defn project
  "Project a world, benchmark bundle, result, or collection into named audits."
  [input]
  (mapv (fn [{:keys [label world]}]
          (assoc (project-world world) :reversal-audit/label label))
        (worlds input)))

(defn- display
  [value]
  (cond
    (nil? value) "—"
    (keyword? value) (str "`" value "`")
    (map? value) (pr-str value)
    (sequential? value) (str/join ", " (map display value))
    :else (str value)))

(defn- cell
  [value]
  (-> (display value)
      (str/replace "|" "\\|")
      (str/replace "\n" "<br>")))

(defn- decision-cell
  [decision]
  (if decision
    (str (display (:outcome decision)) " via " (display (:resolver decision)))
    "—"))

(defn render-markdown
  "Render reversal audit Markdown for any input accepted by `project`.

   The renderer emits a useful empty-state document when no embedded worlds can
   be derived (as is common for summary-only benchmark bundles)."
  [input]
  (let [audits (project input)
        entries (mapcat :reversal-audit/entries audits)]
    (str "# Reversal Slash Audit\n\n"
         "> Derived read-only from active reversal slashes and archived terminal-cleanup records. Track is inferred from the workflow evidence flag and slash status.\n\n"
         (if (seq entries)
           (str "| Scenario | Slash | Workflow | Level | Prior decision | Current decision | Resolver | Track | Status | Amount | Cleanup | Appeal | Warnings |\n"
                "|---|---|---|---:|---|---|---|---|---|---:|---|---|---|\n"
                (apply str
                       (for [audit audits
                             entry (:reversal-audit/entries audit)]
                         (str "| " (cell (:reversal-audit/label audit))
                              " | " (cell (:slash-id entry))
                              " | " (cell (:workflow-id entry))
                              " | " (cell (:level entry))
                              " | " (cell (decision-cell (:prior-decision entry)))
                              " | " (cell (decision-cell (:current-decision entry)))
                              " | " (cell (:resolver entry))
                              " | " (cell (:track entry))
                              " | " (cell (:status entry))
                              " | " (cell (:amount entry))
                              " | " (cell (when (:archived? entry)
                                            {:at (:cleanup-at entry)
                                             :reason (:cleanup-reason entry)}))
                              " | " (cell (:appeal entry))
                              " | " (cell (:warning-flags entry)) " |\n"))))
           "_No reversal slash entries could be derived from the supplied world or results._\n"))))

(def render-worlds-markdown render-markdown)
