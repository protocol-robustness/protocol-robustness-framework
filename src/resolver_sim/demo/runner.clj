(ns resolver-sim.demo.runner
  "Demo runner: executes a demo spec, captures outputs, and writes demo-run.json.

   Usage:
     (run-demo :yield-shortfall-partial-fill)

   This loads demos/<id>/demo.edn, executes each section command sequentially,
   runs the scenario, captures terminal output, collects artifacts, and writes
   demos/<id>/generated/demo-run.json."
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.java.shell :as shell]
            [resolver-sim.demo.spec :as demo-spec]
            [resolver-sim.vcs :as vcs]
            [resolver-sim.demo.screen :as screen]
            [resolver-sim.evidence.config :as evcfg]))

;; ── Helpers ─────────────────────────────────────────────────────────────────

(defn- timestamp []
  (str (java.time.Instant/now)))

(defn- git-commit []
  (vcs/commit-sha))

(defn- ensure-dir!
  "Create directory if it doesn't exist, return the File."
  [path]
  (let [f (io/file path)]
    (.mkdirs f)
    f))

(defn- write-string!
  "Write a string to a file, creating parent directories."
  [path s]
  (let [f (io/file path)]
    (.mkdirs (.getParentFile f))
    (spit f s))
  path)

;; ── Command execution ───────────────────────────────────────────────────────

(defn run-command
  "Execute a shell command, capture output, return {:exit :stdout :stderr}.
   Runs through bash -c so shell redirection and chaining work."
  [cmd]
  (try
    (let [{:keys [exit out err]} (shell/sh "bash" "-c" cmd)]
      {:exit exit :stdout (or out "") :stderr (or err "")})
    (catch Exception e
      {:exit -1 :stdout "" :stderr (.getMessage e)})))

;; ── Artifact collection ─────────────────────────────────────────────────────

(def canonical-artifacts
  "Canonical artifact file names copied to demo output.
   Only files registered in evidence config (not transient .target-* logs).
   :test-artifacts is handled separately (not in evidence config)."
  #{:test-summary :claimable-classification :test-run
    :validation-root :coverage})

(defn collect-artifacts
  "Copy canonical artifact files from results/test-artifacts/ into output-dir/artifacts/.
   Only files registered in evidence config (not transient .target-* logs) are copied.
   Returns a list of artifact filenames copied."
  [output-dir]
  (let [dst (ensure-dir! (str output-dir "/artifacts"))
        adir (evcfg/artifact-dir)
        names (into []
                    (keep (fn [aid]
                            (let [fname (evcfg/artifact-file aid)
                                  src (io/file adir fname)]
                              (when (and fname (.exists src))
                                (io/copy src (io/file dst fname))
                                fname))))
                    canonical-artifacts)
        ta (io/file adir "test-artifacts.json")]
    (when (.exists ta)
      (io/copy ta (io/file dst "test-artifacts.json")))
    (if (.exists ta)
      (conj names "test-artifacts.json")
      names)))

;; ── Demo runner ─────────────────────────────────────────────────────────────

(defn run-demo
  "Execute a demo spec and write generated outputs.
   spec is a parsed demo spec map.
   Output goes to <demo-dir>/generated/."
  [spec]
  (let [id-str (name (demo-spec/demo-id spec))
        demo-dir (str "demos/" id-str)
        out-dir (str demo-dir "/generated")
        _ (ensure-dir! out-dir)
        run-id (timestamp)
        cmd-results (atom [])
        ;; 1. Run scenario command first (produces artifacts)
        scenario-cmd (demo-spec/scenario-command spec)
        scenario-result (when scenario-cmd
                          (println (str "  Running scenario: " scenario-cmd))
                          (run-command scenario-cmd))
        ;; Load replay output to get real scenario outcome (process exit may be 0 even on failure)
        replay-file (str out-dir "/replay-output.json")
        replay-data (try (json/read-str (slurp replay-file) :key-fn keyword)
                         (catch Exception _ nil))
        scenario-outcome (get replay-data :outcome "unknown")
        scenario-halt (get replay-data :halt-reason nil)]
    (println (str "  Scenario outcome: " scenario-outcome
                  (when scenario-halt (str " (" scenario-halt ")"))))
    ;; Generate scenario screenshot
    (let [sc-text (or (:stdout scenario-result) "")
          sc-shot (screen/render-screenshot!
                   out-dir "scenario" 0 sc-text
                   {:title (str "Scenario: " (demo-spec/demo-id spec))
                    :command scenario-cmd})]
      (println (str "  Wrote screenshot: " (:svg-path sc-shot))))

    ;; 2. Run section commands (exploratory commands over results). Each section
    ;;    carries a single :command (see demo-spec/section-keys).
    (doseq [section (demo-spec/sections spec)
            :let [cmd (:command section)]]
      (when cmd
        (println (str "  Running [" (:id section) "] " cmd))
        (let [cmd-idx 0
              result (run-command cmd)
              stdout-file (str out-dir "/outputs/" (:id section) "-stdout.txt")
              stderr-file (str out-dir "/outputs/" (:id section) "-stderr.txt")
              ;; Render terminal screenshot
              screenshot (screen/render-screenshot!
                          out-dir (name (:id section)) cmd-idx (:stdout result)
                          {:title (str (name (:id section)) " — " cmd)
                           :command cmd})]
          (ensure-dir! (str out-dir "/outputs"))
          (write-string! stdout-file (:stdout result))
          (write-string! stderr-file (:stderr result))
          (swap! cmd-results conj
                 {:section (:id section)
                  :command cmd
                  :exit-code (:exit result)
                  :stdout-path stdout-file
                  :stderr-path stderr-file
                  :screenshot (:svg-path screenshot)
                  :asciicast (:asciicast-path screenshot)}))))
    ;; 3. Collect artifacts
    (let [artifacts (collect-artifacts out-dir)
          run-data {:demo/id id-str
                    :run-id run-id
                    :git-commit (git-commit)
                    :scenario {:exit-code (:exit scenario-result)
                               :outcome scenario-outcome
                               :halt-reason scenario-halt
                               :stdout (:stdout scenario-result)
                               :stderr (:stderr scenario-result)}
                    :commands @cmd-results
                    :artifacts artifacts
                    :demo-spec spec}
          json-key (fn [k] (if (keyword? k) (if-let [ns (namespace k)] (str ns "/" (name k)) (name k)) (str k)))
          run-json (json/write-str run-data {:key-fn json-key :indent true})]
      (write-string! (str out-dir "/demo-run.json") run-json)
      (println (str "Wrote " out-dir "/demo-run.json"))
      run-data)))

(defn -main
  "CLI entry point: bb demo:run <id> | bb demo:validate <id>
   Loads demos/<id>/demo.edn, validates, runs, and writes generated outputs.
   Pass --validate as first arg to only validate without running."
  [& args]
  (let [validate-only? (= (first args) "--validate")
        id (if validate-only? (second args) (first args))]
    (when-not id
      (println "Usage: bb demo:run <id> | bb demo:validate <id>")
      (System/exit 1))
    (let [spec (demo-spec/find-spec id)]
      (if-not spec
        (do (println (str "Demo spec not found: " id))
            (System/exit 1))
        (let [validation (demo-spec/validate spec)]
          (if-not (:valid validation)
            (do (println "Demo spec validation failed:")
                (doseq [e (:errors validation)]
                  (println (str "  - " e)))
                (System/exit 1))
            (if validate-only?
              (println "Demo spec is valid.")
              (let [result (run-demo spec)
                    scenario-outcome (get-in result [:scenario :outcome] "unknown")
                    scenario-ok? (= scenario-outcome "pass")]
                (println "Demo run complete."
                         (if scenario-ok? " (passed)" " (failed)"))
                (System/exit (if scenario-ok? 0 1))))))))))
