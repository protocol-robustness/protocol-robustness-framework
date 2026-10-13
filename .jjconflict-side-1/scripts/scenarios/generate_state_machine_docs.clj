(ns scripts.scenarios.generate-state-machine-docs
  (:require [clojure.data.json :as json]
            [clojure.edn      :as edn]
            [clojure.java.io  :as io]
            [clojure.string   :as str]
            [resolver-sim.definitions.registry :as defs]))

(defn- protocol-state-machine-doc-path [protocol-id]
  (str "docs/protocols/" protocol-id "/STATE_MACHINE_GENERATED.md"))

(defn- protocol-coverage-doc-path [protocol-id]
  (str "docs/protocols/" protocol-id "/TRANSITION_COVERAGE_GENERATED.md"))

(defn- parse-args [args]
  (loop [xs (seq args)
         out {:check? false :protocol-id "sew"}]
    (if (empty? xs)
      out
      (let [[x & more] xs]
        (cond
          (= x "--check")
          (recur more (assoc out :check? true))

          (= x "--protocol")
          (let [p (first more)]
            (if (and p (not (str/blank? p)))
              (recur (rest more) (assoc out :protocol-id p))
              (throw (ex-info "--protocol requires a non-empty value" {:args args}))))

          :else
          (throw (ex-info (str "Unknown arg: " x) {:args args})))))))

(defn- normalize-action [a]
  (-> (if (keyword? a) (name a) (str a))
      str/lower-case
      (str/replace "-" "_")
      keyword))

(defn- scenario-id-from-file [f]
  (-> (.getName ^java.io.File f)
      (str/replace #"\.(edn|json)$" "")))

(defn- load-scenario [f]
  (try
    (if (str/ends-with? (.getName ^java.io.File f) ".edn")
      (edn/read-string (slurp f))
      (json/read-str (slurp f) :key-fn keyword))
    (catch Exception e
      (println "WARN: skipping unreadable scenario" (.getPath ^java.io.File f) "-" (.getMessage e))
      nil)))

(defn- scenario-files []
  (->> (file-seq (io/file "scenarios/edn"))
       (filter #(.isFile ^java.io.File %))
       (filter #(str/ends-with? (.getName ^java.io.File %) ".edn"))
       (sort-by #(.getName ^java.io.File %))))

(defn- scenario-actions [scenario]
  (->> (:events scenario)
       (map :action)
       (map normalize-action)
       set))

(defn- transition-coverage [scenarios]
  (let [all-transitions (sort (defs/canonical-transition-ids))]
    (into (sorted-map)
          (for [tr all-transitions]
            [tr (->> scenarios
                     (filter (fn [{:keys [actions]}] (contains? actions tr)))
                     (map :id)
                     distinct
                     sort
                     vec)]))))

(defn- state-machine-markdown [protocol-id]
  (let [rows (sort-by key defs/transitions)]
    (str "# State Machine (Generated)\n\n"
         "Protocol: `" protocol-id "`\n\n"
         "Source of truth: `src/resolver_sim/definitions/registry.clj` (`transitions`).\n\n"
         "| Transition ID | Label |\n"
         "|---|---|\n"
         (apply str
                (for [[tr meta] rows]
                  (str "| `" (name tr) "` | " (:label meta) " |\n")))
         "\n"
         "Definitions hash: `" (defs/definitions-hash) "`\n")))

(defn- transition-coverage-markdown [coverage]
  (str "# Transition Coverage (Generated)\n\n"
       "Derived from scenario events in `scenarios/edn/*.edn` and canonical transition IDs in registry.\n\n"
       "| Transition ID | Label | Covered by scenarios | Count |\n"
       "|---|---|---|---:|\n"
       (apply str
              (for [[tr scenario-ids] coverage]
                (str "| `" (name tr) "` | "
                     (or (get-in (defs/transition-def tr) [:label]) "")
                     " | "
                     (if (seq scenario-ids)
                       (str/join ", " scenario-ids)
                       "_none_")
                     " | " (count scenario-ids) " |\n")))
       "\n"
       "Definitions hash: `" (defs/definitions-hash) "`\n"))

(defn- write-if-changed! [path content]
  (let [f (io/file path)
        current (when (.exists f) (slurp f))]
    (when-not (= current content)
      (.mkdirs (.getParentFile f))
      (spit f content))
    {:path path :changed? (not= current content)}))

(defn- generate! [{:keys [protocol-id]}]
  (let [scenarios (->> (scenario-files)
                       (keep (fn [f]
                               (when-let [sc (load-scenario f)]
                                 {:id (or (:id sc) (:scenario-id sc) (scenario-id-from-file f))
                                  :actions (scenario-actions sc)}))))
        coverage (transition-coverage scenarios)
        sm-doc (state-machine-markdown protocol-id)
        cov-doc (transition-coverage-markdown coverage)
        sm-path (protocol-state-machine-doc-path protocol-id)
        coverage-path (protocol-coverage-doc-path protocol-id)
        r1 (write-if-changed! sm-path sm-doc)
        r2 (write-if-changed! coverage-path cov-doc)]
    (println "Generated state machine docs:")
    (println "-" (:path r1) "changed?" (:changed? r1))
    (println "-" (:path r2) "changed?" (:changed? r2))))

(defn- check! [{:keys [protocol-id]}]
  (let [scenarios (->> (scenario-files)
                       (map (fn [f]
                              (let [sc (load-scenario f)]
                                {:id (or (:id sc) (:scenario-id sc) (scenario-id-from-file f))
                                 :actions (scenario-actions sc)}))))
        coverage (transition-coverage scenarios)
        expected-sm (state-machine-markdown protocol-id)
        expected-cov (transition-coverage-markdown coverage)
        sm-path (protocol-state-machine-doc-path protocol-id)
        coverage-path (protocol-coverage-doc-path protocol-id)
        current-sm (when (.exists (io/file sm-path)) (slurp sm-path))
        current-cov (when (.exists (io/file coverage-path)) (slurp coverage-path))]
    (when (not= expected-sm current-sm)
      (binding [*out* *err*]
        (println "State machine generated doc is stale:" sm-path)
        (println "Run: clojure scripts/scenarios/generate_state_machine_docs.clj"))
      (System/exit 1))
    (when (not= expected-cov current-cov)
      (binding [*out* *err*]
        (println "Transition coverage generated doc is stale:" coverage-path)
        (println "Run: clojure scripts/scenarios/generate_state_machine_docs.clj"))
      (System/exit 1))
    (println "State machine docs are up to date.")))

(defn -main [& args]
  (let [{:keys [check?] :as opts} (parse-args args)]
    (if check?
      (check! opts)
      (generate! opts))))

(apply -main *command-line-args*)
