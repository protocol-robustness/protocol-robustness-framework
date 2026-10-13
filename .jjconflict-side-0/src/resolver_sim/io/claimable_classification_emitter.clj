(ns resolver-sim.io.claimable-classification-emitter
  "Write claimable-classification.v2 evidence artifact (I/O shell)."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.io.scenario-runner :as scenario-runner]
            [resolver-sim.vcs :as vcs]))

(def producer-id (evcfg/producer :claimable-classification))

(defn- classifier-var [name]
  (requiring-resolve (symbol "resolver-sim.protocols.sew.claimable-classification" name)))

(defn- write-json! [path doc]
  (let [f (io/file path)
        parent (.getParentFile f)]
    (when parent (.mkdirs parent))
    (spit f (json/write-str doc {:indent true}))))

(defn- current-vcs-sha []
  (vcs/commit-sha))

(defn- sha256-hex
  [path]
  (try
    (let [digest (java.security.MessageDigest/getInstance "SHA-256")
          bytes  (.digest digest (.getBytes (slurp path)))]
      (apply str (map #(format "%02x" %) bytes)))
    (catch Exception _ nil)))

(defn- provenance-block
  [& {:keys [run-id git-sha override-sha input-result-path]}]
  (let [sha (or override-sha git-sha (current-vcs-sha))]
    (cond-> {:producer producer-id
             :classifier_version @(classifier-var "classifier-version")
             :produced_at (str (java.time.Instant/now))}
      run-id (assoc :run_id run-id)
      sha (assoc :git_sha sha)
      input-result-path
      (assoc :input_result_path (str input-result-path)
             :input_result_sha256 (sha256-hex input-result-path)))))

(defn- emit-terminal-document!
  [output-path {:keys [worlds contexts scope scenarios-passed observations-status
                       run-id git-sha aggregation aggregation-note input-result-path]}]
  (write-json! output-path
               ((classifier-var "build-document")
                :worlds worlds
                :contexts contexts
                :scope scope
                :scenarios-passed scenarios-passed
                :observations-status observations-status
                :aggregation aggregation
                :aggregation-note aggregation-note
                :provenance (provenance-block :run-id run-id
                                              :git-sha git-sha
                                              :input-result-path input-result-path))))

(defn emit-taxonomy-only!
  "Write taxonomy without replaying scenarios (scenario-manifest path)."
  [output-path & {:keys [run-id git-sha]}]
  (write-json! output-path
               ((classifier-var "build-document")
                :observations-status "taxonomy-only"
                :provenance (provenance-block :run-id run-id :git-sha git-sha))))

(defn emit-from-registry-replay!
  "Replay Sew invariant registry and write v2 with terminal observations."
  [output-path & {:keys [run-id suite-id git-sha]}]
  (let [summary  (scenario-runner/run-registry-suite {:suite-id (or suite-id :sew-invariants)})
        contexts ((classifier-var "terminal-contexts-from-summary") summary)
        worlds   (mapv :world contexts)
        passed   (count (filter :pass? (or (:results summary) (:entries summary))))]
    (emit-terminal-document! output-path
                             {:worlds worlds
                              :contexts contexts
                              :scope (str "sew-invariants-registry"
                                          (when run-id (str "/" run-id)))
                              :scenarios-passed passed
                              :observations-status "terminal-aggregated"
                              :run-id run-id
                              :git-sha git-sha})
    output-path))

(defn emit-from-scenario-file!
  "Replay one scenario JSON and write v2 with terminal observations."
  [output-path scenario-path & {:keys [run-id git-sha scenarios-passed]}]
  (let [summary  (scenario-runner/run-scenario-file scenario-path {:suite-id :bb-scenario-run})
        contexts ((classifier-var "terminal-contexts-from-summary") summary)
        worlds   (mapv :world contexts)
        passed   (or scenarios-passed (count (filter :pass? (:entries summary))))
        sid      (or (:scenario-id (first contexts)) scenario-path)]
    (emit-terminal-document! output-path
                             {:worlds worlds
                              :contexts contexts
                              :scope (str "scenario/" sid)
                              :scenarios-passed passed
                              :observations-status "single-scenario"
                              :aggregation "single-terminal-world"
                              :aggregation-note "One scenario replay; workflows list per-escrow claimable breakdown."
                              :run-id run-id
                              :git-sha git-sha})
    output-path))

(defn emit-from-result-file!
  "Load scenario-result JSON (evidence:build output) without replay."
  [output-path result-path & {:keys [run-id git-sha scenarios-passed]}]
  (let [result  (json/read-str (slurp result-path) :key-fn keyword)
        context ((classifier-var "terminal-context-from-replay-result") result :result-path result-path)]
    (when-not context
      (throw (ex-info "scenario result has no terminal world in trace"
                      {:path result-path})))
    (emit-terminal-document! output-path
                             {:worlds [(:world context)]
                              :contexts [context]
                              :scope (str "scenario-result/" (:scenario-id context))
                              :scenarios-passed (or scenarios-passed (if (= :pass (:outcome context)) 1 0))
                              :observations-status "single-scenario"
                              :aggregation "single-terminal-world"
                              :aggregation-note (str "Terminal world from " result-path "; no replay.")
                              :run-id run-id
                              :git-sha git-sha
                              :input-result-path result-path})
    output-path))

(defn- parse-int-or-nil [s]
  (when (seq (str s))
    (Integer/parseInt (str s))))

(defn -main
  [& args]
  (let [output-path (or (first args) (evcfg/artifact-path :claimable-classification))
        mode        (second args)]
    (case mode
      "taxonomy-only"
      (emit-taxonomy-only! output-path
                           :run-id (nth args 2 nil)
                           :git-sha (nth args 3 nil))

      "aggregated"
      (emit-from-registry-replay! output-path
                                  :run-id (nth args 2 nil)
                                  :git-sha (nth args 3 nil))

      "single-scenario"
      (emit-from-scenario-file! output-path (nth args 2 nil)
                                :run-id (nth args 3 nil)
                                :git-sha (nth args 4 nil)
                                :scenarios-passed (parse-int-or-nil (nth args 5 nil)))

      "from-result"
      (emit-from-result-file! output-path (nth args 2 nil)
                              :run-id (nth args 3 nil)
                              :git-sha (nth args 4 nil)
                              :scenarios-passed (parse-int-or-nil (nth args 5 nil)))

      ;; legacy: second arg was run-id
      (emit-from-registry-replay! output-path :run-id mode :git-sha (nth args 2 nil)))
    (println "Wrote claimable classification:" output-path)))