(ns resolver-sim.commands.scenario-orchestration
  (:require [clojure.data.json :as json] [clojure.java.io :as io] [clojure.java.shell :as shell]
            [resolver-sim.commands.scenario-registry :as registry]
                        [resolver-sim.commands.scenario-manifest :as manifest]
                                    [resolver-sim.commands.scenario-safety :as safety]))
(def ^:private phases [:check-runtime :execute :write-manifest :extract-artifacts :scan-sensitivity :finalize-registry :validate-registry])
(defn- p [x] (str x))
(defn- checked [phase command result] (if (zero? (:exit result)) result (throw (ex-info "Required scenario finalization phase failed" {:phase phase :command command :exit-code (:exit result) :out (:out result) :err (:err result)}))))
(defn- layout! [c] (doseq [x [(:run/root c) (:manifest/dir c) (:scenario/root c) (:execution/dir c) (:forensic/dir c) (:summaries/dir c)]] (.mkdirs (io/file (p x)))) (spit (io/file (p (:run/root c)) ".run-state") (pr-str {:run/id (:run/id c) :state :running})) c)
(defn default-check-runtime! [c] (doseq [f ["scripts/evidence/extract_scenario_artifacts.py"]] (when-not (.isFile (io/file (p (:project/root c)) f)) (throw (ex-info "Required scenario finalization script is missing" {:script f})))) (doseq [cmd [["python3" "--version"] ["clojure" "-Sdescribe"]]] (checked :check-runtime cmd (apply shell/sh cmd))) {})
(defn default-execute! [c] ((requiring-resolve 'resolver-sim.io.scenario-runner/run-and-report) {:scenario (:scenario/ref c) :run-id (:run/id c) :run-root (p (:run/root c)) :scenario-slug (:scenario/slug c) :scenario-root (p (:scenario/root c)) :execution-dir (p (:execution/dir c)) :artifact-dir (p (:forensic/dir c)) :summary-dir (p (:summaries/dir c)) :manifest-dir (p (:manifest/dir c)) :output-file (p (:replay/file c))} {:report-format (:report-format c)}))
(defn- process! [phase command] (checked phase command (apply shell/sh command)))
(defn default-write-manifest! [c e] (manifest/write! c e))
(defn default-extract-artifacts! [c _] (process! :extract-artifacts ["python3" "scripts/evidence/extract_scenario_artifacts.py" "--replay" (p (:replay/file c)) "--run-dir" (p (:scenario/root c)) "--run-root" (p (:run/root c))]))
(defn default-scan-sensitivity! [c _] (if (= :public (:sensitivity/profile c)) (safety/scan-public-bundle! (:run/root c)) {:profile :internal :findings []}))
(defn default-finalize-registry! [c _] (registry/finalize! (:run/root c)))
(defn default-validate-registry! [c _] (process! :validate-registry ["clojure" "-M:with-sew" "-m" "resolver-sim.validation.integration.artifact-registry" (str (p (:manifest/dir c)) "/artifacts.json")]))
(defn default-complete! [c e] (let [root (io/file (p (:run/root c))) out (io/file root "completion.json")] (spit out (json/write-str {:run/id (:run/id c) :command/status "completed" :scenario/outcome (if (zero? (:exit-code e)) "pass" "fail") :exit-code (:exit-code e) :sensitivity/profile (name (:sensitivity/profile c))})) (.delete (io/file root ".run-state")) {}))
(def ^:private defaults {:check-runtime default-check-runtime! :execute default-execute! :write-manifest default-write-manifest! :extract-artifacts default-extract-artifacts! :scan-sensitivity default-scan-sensitivity! :finalize-registry default-finalize-registry! :validate-registry default-validate-registry! :complete default-complete!})
(defn run-scenario!
  ([context] (run-scenario! context {}))
  ([context overrides]
   (let [lock (safety/acquire-lock! (:run/root context))]
     (try
       (layout! context)
       (let [phase-fns (merge defaults overrides)
             records (atom [])
             run-phase (fn [phase execution]
                         (try
                           (let [result (if (#{:check-runtime :execute} phase)
                                          ((phase-fns phase) context)
                                          ((phase-fns phase) context execution))]
                             (swap! records conj {:phase phase :status :completed})
                             result)
                           (catch Throwable error
                             (swap! records conj {:phase phase :status :failed :error (.getMessage error)})
                             (throw error))))]
         (try
           (run-phase :check-runtime nil)
           (let [execution (assoc (run-phase :execute nil) :duration-ms 0)]
             (doseq [phase (drop 2 phases)] (run-phase phase execution))
             (run-phase :complete execution)
             {:command/status :completed :scenario/outcome (if (zero? (:exit-code execution)) :pass :fail)
              :exit-code (:exit-code execution) :run/id (:run/id context) :run/root (p (:run/root context)) :phases @records})
           (catch Throwable error
             {:command/status :failed :scenario/outcome :unknown :exit-code 1
              :run/id (:run/id context) :run/root (p (:run/root context)) :phases @records :error (.getMessage error)})))
       (finally (safety/release-lock! lock))))))
