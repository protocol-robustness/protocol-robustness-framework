#!/usr/bin/env clojure
;; Derived, non-authoritative diagnostic projection. It deliberately has no
;; dependency on resolver-sim namespaces and never writes into a run artifact.
;;
;; Usage (from the repository root; uses the existing data.json dependency):
;;   clojure -M scripts/render_scenario_diagnostic.clj results/runs/<run-root>
;;   clojure -M scripts/render_scenario_diagnostic.clj replay-output.json --focus workflow
;;   clojure -M scripts/render_scenario_diagnostic.clj <run-root> --output-dir /tmp/diagnostic

(require '[clojure.data.json :as json]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(defn usage []
  (str "Usage: clojure -M scripts/render_scenario_diagnostic.clj <run-path>\n"
       "       [--focus first-failure|workflow|property] [--format mermaid|markdown]\n"
       "       [--output-dir DIR] [--include-expected-errors]\n\n"
       "<run-path> may be a replay JSON/EDN file or a structured run directory.\n"
       "Outputs diagnostic.mmd and diagnostic.md next to the input (or in --output-dir)."))

(defn parse-args [args]
  (loop [opts {:focus :first-failure :format :mermaid} args args]
    (if-let [arg (first args)]
      (cond
        (contains? #{"--help" "-h"} arg) (assoc opts :help? true)
        (= "--focus" arg) (recur (assoc opts :focus (keyword (second args))) (nnext args))
        (= "--format" arg) (recur (assoc opts :format (keyword (second args))) (nnext args))
        (= "--output-dir" arg) (recur (assoc opts :output-dir (second args)) (nnext args))
        (= "--include-expected-errors" arg) (recur (assoc opts :include-expected-errors? true) (next args))
        (:input opts) (throw (ex-info "Only one run path may be supplied" {:args args}))
        :else (recur (assoc opts :input arg) (next args)))
      opts)))

(defn read-document [file]
  (let [path (.getPath (io/file file))]
    (try
      (cond
        (str/ends-with? path ".edn") (edn/read-string (slurp file))
        :else (json/read-str (slurp file) :key-fn keyword))
      (catch Exception e
        (throw (ex-info "Could not parse diagnostic input" {:path path} e))))))

(defn existing-file [dir relative]
  (let [f (io/file dir relative)] (when (.isFile f) f)))

(defn find-first-file [dir names]
  (let [files (->> (file-seq (io/file dir))
                   (filter #(.isFile ^java.io.File %))
                   (sort-by #(.getPath ^java.io.File %)))]
    (some (fn [name] (some #(when (= name (.getName ^java.io.File %)) %) files)) names)))

(defn load-run [input]
  (let [f (io/file input)]
    (when-not (.exists f)
      (throw (ex-info "Run path does not exist" {:input input})))
    (if (.isFile f)
      {:replay (read-document f) :replay-file f :run-dir (.getParentFile f)}
      (let [replay-file (or (existing-file f "execution/replay-output.json")
                            (existing-file f "raw/replay-output.json")
                            (existing-file f "replay-output.json")
                            (find-first-file f ["replay-output.json"]))
            trace-file (or (existing-file f "summaries/trace-summary.json")
                           (find-first-file f ["trace-summary.json"]))
            manifest-file (or (existing-file f "manifest/run.json")
                              (existing-file f "run.json")
                              (find-first-file f ["run.json"]))
            summary-file (or (existing-file f "manifest/summary.json")
                             (existing-file f "summary.json")
                             (find-first-file f ["summary.json"]))]
        (when-not (or replay-file trace-file)
          (throw (ex-info "Directory contains neither replay output nor trace summary" {:input input})))
        {:replay (when replay-file (read-document replay-file))
         :trace-summary (when trace-file (read-document trace-file))
         :manifest (when manifest-file (read-document manifest-file))
         :summary (when summary-file (read-document summary-file))
         :replay-file replay-file :run-dir f}))))

(defn vget [m & keys]
  (some #(when (contains? m %) (get m %)) keys))

(defn value->text [v]
  (cond
    (nil? v) nil
    (keyword? v) (name v)
    (string? v) v
    :else (str v)))

(defn failed? [v]
  (contains? #{"fail" "failed" "error" "failure" "invalid" "rejected"
               "violated" "invariant-violated" false} v))
(defn passed? [v]
  (contains? #{"pass" "passed" "ok" "success" "succeeded" true} v))

(defn error-code [event]
  (let [error (vget event :error :error-code :reason :failure-reason)]
    (value->text (if (map? error) (vget error :code :type :message) error))))

(defn event-status [event expected-errors]
  (let [seq-no (or (:seq event) (:sequence event))
        action (value->text (vget event :action :event-type :operation))
        declared (some #(when (and (= seq-no (:seq %))
                                   (= action (value->text (:action %)))) %) expected-errors)
        result (vget event :result :outcome :status :pass? :success?)
        error (error-code event)
        rejected? (or error (failed? result))]
    (cond
      declared :expected-error
      rejected? :unexpected-error
      (passed? result) :passed
      ;; Trace summaries often omit execution status. Do not invent a failure.
      :else :passed)))

(defn workflow-id [event]
  (let [params (or (:params event) (:parameters event) {})]
    (or (vget event :workflow-id :workflow/id :escrow-id :transfer-id)
        (vget params :workflow-id :workflow/id :escrow-id :transfer-id))))

(defn normalize-bundle [replay]
  (if (= "bundle-root.v1" (:bundle/schema-version replay))
    (let [overview (first (get-in replay [:overview :results]))
          raw (first (:run/scenario-results replay))]
      (merge replay raw {:scenario-id (:scenario-id overview)
                          :outcome (:outcome overview)
                          :pass? (:pass? overview)}))
    replay))

(defn invariant-failures [replay summary]
  (let [all (concat (or (:invariant-results replay) [])
                    (or (:invariant-results summary) [])
                    (or (:checks summary) [])
                    (or (:claim-results summary) []))]
    (->> all
         (filter map?)
         (filter (fn [x] (or (failed? (vget x :status :outcome :pass? :success?))
                             (true? (:failed? x)))))
         (map-indexed (fn [i x]
                        {:id (str "invariant-" i)
                         :kind :invariant-failure
                         :label (or (value->text (vget x :id :invariant-id :claim-id :check-id :name))
                                    "Invariant or claim failed")
                         :detail (or (value->text (vget x :message :error :actual))
                                     (when (contains? x :expected)
                                       (str "expected " (:expected x) ", actual " (:actual x))))}))
         vec)))

(defn collapse-passed [events focus include-expected?]
  (let [first-failure (first (keep-indexed #(when (= :unexpected-error (:kind %2)) %1) events))
        keep? (fn [i event]
                (or (not= :passed (:kind event))
                    (and include-expected? (= :expected-error (:kind event)))
                    (nil? first-failure)
                    (case focus
                      :workflow true
                      :property true
                      :first-failure (<= (max 0 (- first-failure 2)) i (min (dec (count events)) (inc first-failure)))
                      true)))]
    (loop [remaining (map-indexed vector events) output []]
      (if-let [[i event] (first remaining)]
        (if (keep? i event)
          (recur (next remaining) (conj output event))
          (let [[run tail] (split-with (fn [[j e]] (not (keep? j e))) remaining)
                start (ffirst run) end (first (last run))]
            (recur tail (conj output {:id (str "collapsed-" start "-" end)
                                      :kind :collapsed-success
                                      :label (str "[" (count run) " successful events]")}))))
        output))))

(defn diagnostic-model [{:keys [replay trace-summary manifest summary]} opts]
  (let [replay (normalize-bundle (or replay {}))
        source (or (:source replay) {})
        expected-errors (or (:expected-errors replay) (:expected-errors source) [])
        trace (or (:trace replay) (:steps trace-summary) [])
        events (->> trace
                    (map-indexed
                     (fn [i e]
                       (let [seq-no (or (:seq e) (:sequence e) i)
                             kind (event-status e expected-errors)
                             action (or (value->text (vget e :action :event-type :operation)) "event")]
                         {:id (str "event-" seq-no)
                          :kind kind :seq seq-no :action action
                          :label (str "Event " seq-no ": " action)
                          :error (error-code e)
                          :workflow-id (workflow-id e)
                          :agent (value->text (vget e :agent :actor :caller))
                          :evidence (or (first (:evidence-refs e)) (:evidence-ref e))})))
                    vec)
        events (collapse-passed events (:focus opts) (:include-expected-errors? opts))
        invariants (invariant-failures replay summary)
        scenario-id (or (:scenario-id replay) (:scenario-id trace-summary)
                        (:scenario-id manifest) "unknown scenario")
        outcome (or (:outcome replay) (:outcome trace-summary) (:status manifest)
                    (when (false? (:pass? replay)) "failed") "unknown")
        run-failed? (failed? outcome)]
    {:scenario-id (value->text scenario-id)
     :run-id (value->text (or (:run-id manifest) (:id manifest)))
     :outcome (value->text outcome)
     :focus (:focus opts)
     :nodes (vec (concat [{:id "scenario" :kind :scenario :label (str "Scenario: " scenario-id)}]
                          events invariants
                          [{:id "outcome" :kind (if run-failed? :run-failed :run-passed)
                            :label (str "Run " (if run-failed? "failed" "completed") ": " outcome)}]))
     :events events :invariants invariants}))

(defn mermaid-escape [s]
  (-> (str s) (str/replace "\"" "'") (str/replace "[" "(") (str/replace "]" ")")
      (str/replace "\n" " ")))
(defn node-label [{:keys [kind label error workflow-id agent evidence detail]}]
  (str (case kind
         :scenario "▶ " :passed "✓ " :expected-error "✓ Expected rejection: "
         :unexpected-error "✗ " :invariant-failure "✗ " :run-failed "✗ "
         :run-passed "✓ " :collapsed-success "" "")
       label
       (when error (str "<br/>error: " error))
       (when workflow-id (str "<br/>workflow: " workflow-id))
       (when agent (str "<br/>actor: " agent))
       (when detail (str "<br/>" detail))
       (when evidence (str "<br/>evidence: " evidence))))

(defn render-mermaid [{:keys [nodes events invariants focus]}]
  (let [property-focus? (and (= :property focus) (seq invariants))
        spine (if property-focus?
                (vec (concat ["scenario"] (map :id invariants) ["outcome"]))
                (vec (concat ["scenario"] (map :id events) ["outcome"])))
        first-error (first (filter #(= :unexpected-error (:kind %)) events))
        inv-source (or (:id first-error) (last (map :id events)) "scenario")
        workflow-groups (when (= :workflow focus)
                          (->> events (filter :workflow-id) (group-by :workflow-id)))]
    (str "flowchart LR\n"
         "  classDef scenario fill:#e8f0fe,stroke:#4c78a8,color:#111;\n"
         "  classDef passed fill:#edf7ed,stroke:#4f8a5b,color:#111;\n"
         "  classDef expected fill:#fff7df,stroke:#b58105,color:#111;\n"
         "  classDef failure fill:#fdecec,stroke:#b22222,color:#111;\n"
         "  classDef neutral fill:#f4f4f4,stroke:#777,color:#111;\n"
         (apply str (for [{:keys [id] :as node} nodes]
                      (str "  " id "[\"" (mermaid-escape (node-label node)) "\"]\n")))
         (apply str (map (fn [[a b]] (str "  " a " --> " b "\n")) (partition 2 1 spine)))
         (when property-focus?
           (apply str (for [event events
                            invariant invariants]
                        (str "  " (:id event) " -. reported-by .-> " (:id invariant) "\n"))))
         (apply str (for [{:keys [id]} invariants]
                      (str "  " inv-source " -. occurred-before .-> " id "\n"
                           "  " id " -. contributes-to-run-outcome .-> outcome\n")))
         (apply str (for [[workflow workflow-events] workflow-groups]
                      (str "  subgraph workflow_" (mermaid-escape workflow) "[\"Workflow " (mermaid-escape workflow) "\"]\n"
                           (apply str (for [{:keys [id]} workflow-events] (str "    " id "\n")))
                           "  end\n")))
         (apply str (for [{:keys [id kind]} nodes]
                      (str "  class " id " " (case kind
                                                   :scenario "scenario"
                                                   :passed "passed"
                                                   :expected-error "expected"
                                                   :unexpected-error "failure"
                                                   :invariant-failure "failure"
                                                   :run-failed "failure"
                                                   "neutral") ";\n"))))))

(defn markdown [model mermaid]
  (str "# Scenario diagnostic — " (:scenario-id model) "\n\n"
       "**Outcome:** `" (:outcome model) "`"
       (when-let [run-id (:run-id model)] (str "  \n**Run:** `" run-id "`"))
       "\n\nThis is a derived, non-authoritative diagnostic projection. Dashed `occurred-before` edges indicate temporal proximity, not proven causality.\n\n"
       "```mermaid\n" mermaid "```\n"))

(defn output-dir [{:keys [run-dir]} opts]
  (io/file (or (:output-dir opts) (if (.isDirectory ^java.io.File run-dir) run-dir (.getParentFile ^java.io.File run-dir)))))

(defn -main [& args]
  (let [opts (parse-args args)]
    (when (:help? opts) (println (usage)) (System/exit 0))
    (when-not (:input opts) (throw (ex-info "Missing run path" {})))
    (when-not (contains? #{:first-failure :workflow :property} (:focus opts))
      (throw (ex-info "Unsupported focus" {:focus (:focus opts)})))
    (let [run (load-run (:input opts)) model (diagnostic-model run opts)
          mmd (render-mermaid model) md (markdown model mmd) dir (output-dir run opts)]
      (.mkdirs dir)
      (spit (io/file dir "diagnostic.mmd") mmd)
      (spit (io/file dir "diagnostic.md") md)
      (if (= :markdown (:format opts)) (print md) (print mmd)))))

(apply -main *command-line-args*)
