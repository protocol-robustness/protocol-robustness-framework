(ns resolver-sim.lab.runner
  "Assurance Lab execution boundary.

   This namespace is the standalone, subprocess entry point for lab runs. The
   HTTP server never executes experiments in-process: it spawns a fresh JVM
   running `resolver-sim.lab.runner` with a server-generated request file.
   That preserves the repository's clean-execution-context requirement and
   guarantees a crashed run cannot destabilize the web server.

   The runner reads a validated request file, re-validates it defensively,
   dispatches through the FIXED allowlist below, and writes a normalized
   lab result. Dispatch is a plain map from registry runner keys to concrete
   functions — visitor strings can never name an arbitrary namespace, symbol,
   shell command, or filesystem path."
  (:require [clojure.string :as str]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.lab.json :as lab-json]
            [resolver-sim.lab.registry :as registry]
            [resolver-sim.lab.validation :as validation]
            [resolver-sim.lab.experiments.withdrawal :as withdrawal]
            [resolver-sim.lab.experiments.insolvency :as insolvency]
            [resolver-sim.lab.experiments.pro-rata :as pro-rata]
            [resolver-sim.vcs :as vcs])
  (:import [java.time Instant]))

(def runner-dispatch
  "Allowlisted runner functions. Registry :runner keys resolve here and only
   here. Adding an experiment requires wiring a concrete function here."
  {:withdrawal/constrained-liquidity withdrawal/run
   :insolvency/impairment insolvency/run
   :pro-rata/allocation pro-rata/run})

(defn- safe-git-sha
  []
  (try (or (vcs/commit-sha) "unknown")
       (catch Throwable _ "unknown")))

(defn- inputs-root
  "Canonical parameter-root over the validated inputs."
  [parameters]
  (hc/hash-with-intent {:hash/intent :lab-parameter-root}
                       (into (sorted-map)
                             (map (fn [[k v]]
                                    [(if (keyword? k) k (keyword (str k))) v]))
                             parameters)))

(defn normalize-result
  [experiment parameters run-output {:keys [lab-run-id started-at duration-ms]}]
  {:lab-run/id lab-run-id
   :lab-run/schema-version "lab-run.v1"
   :lab-run/status :completed
   :experiment {:id (lab-json/keyword->string (:experiment/id experiment))
                :version (:experiment/version experiment)
                :title (:experiment/title experiment)
                :slug (:experiment/slug experiment)
                :ref (str (:experiment/slug experiment) ".v"
                          (:experiment/version experiment))}
   :inputs parameters
   :inputs/hash (inputs-root parameters)
   :outcome (:outcome run-output)
   :assessment (:assessment run-output)
   :findings (:findings run-output)
   :evidence (:evidence run-output)
   :execution {:lab-run/id lab-run-id
               :run/id lab-run-id
               :git-sha (safe-git-sha)
               :implementation "resolver-sim.lab/0.1.0 (sew)"
               :runner :anonymous-lab
               :visitor :anonymous-visitor
               :started-at started-at
               :duration-ms duration-ms}})

(defn execute
  "Full pipeline: validate -> dispatch -> execute -> normalize.

   Always returns a normalized result map; never throws for visitor input.
   Returns {:lab-run/status :validation-error :lab-run/errors [...]} or
   {:lab-run/status :execution-error ...} on failure."
  [request lab-run-id]
  (let [{:keys [ok? experiment parameters errors]}
        (validation/validate-request request)
        started-at (str (Instant/now))
        started-ms (System/currentTimeMillis)]
    (if-not ok?
      {:lab-run/id lab-run-id
       :lab-run/schema-version "lab-run.v1"
       :lab-run/status :validation-error
       :lab-run/errors errors
       :experiment nil
       :inputs nil
       :inputs/hash nil
       :outcome nil
       :assessment nil
       :findings nil
       :evidence nil
       :execution {:lab-run/id lab-run-id
                   :git-sha (safe-git-sha)
                   :implementation "resolver-sim.lab/0.1.0 (sew)"
                   :runner :anonymous-lab
                   :visitor :anonymous-visitor
                   :started-at started-at
                   :duration-ms (- (System/currentTimeMillis) started-ms)}}
      (try
        (let [runner (get runner-dispatch (:runner experiment))]
          (when-not runner
            (throw (ex-info "experiment runner not registered"
                            {:experiment/id (:experiment/id experiment)})))
          (let [run-output (runner parameters)
                duration-ms (- (System/currentTimeMillis) started-ms)]
            (normalize-result experiment parameters run-output
                              {:lab-run-id lab-run-id
                               :started-at started-at
                               :duration-ms duration-ms})))
        (catch Throwable t
          {:lab-run/id lab-run-id
           :lab-run/schema-version "lab-run.v1"
           :lab-run/status :execution-error
           :lab-run/error {:type (.getSimpleName (class t))
                           :message (or (.getMessage t) "execution failed")}
           :lab-run/reference lab-run-id
           :execution {:lab-run/id lab-run-id
                       :git-sha (safe-git-sha)
                       :implementation "resolver-sim.lab/0.1.0 (sew)"
                       :runner :anonymous-lab
                       :visitor :anonymous-visitor
                       :started-at started-at
                       :duration-ms (- (System/currentTimeMillis) started-ms)}})))))

(defn generate-run-id
  []
  (let [stamp (str/replace (str (Instant/now)) #"[:\-]" "")
        suffix (subs (str (java.util.UUID/randomUUID)) 0 8)]
    (str "LAB-" stamp "-" suffix)))

(defn -main
  "Subprocess entry point. Usage:
     java -cp <sew-classpath> clojure.main -m resolver-sim.lab.runner <request-file> <run-id> <output-file>
   Reads the request file, executes, and writes the normalized result JSON to
   <output-file>. Exits 0 on success, 2 on validation failure, 1 on error."
  [& args]
  (let [request-file (first args)
        run-id (or (second args) (generate-run-id))
        output-file (or (nth args 2 nil) (str request-file ".result.json"))]
    (try
      (let [request (lab-json/read-str (slurp request-file))
            result (execute request run-id)]
        (spit output-file (lab-json/write-str result))
        (println (str "lab-run " run-id " " (:lab-run/status result)))
        (System/exit (if (= :validation-error (:lab-run/status result)) 2 0)))
      (catch Throwable t
        (try
          (spit output-file
                (lab-json/write-str
                 {:lab-run/id run-id
                  :lab-run/status :execution-error
                  :lab-run/error {:type (.getSimpleName (class t))
                                  :message (or (.getMessage t) "runner failure")}
                  :lab-run/reference run-id}))
          (catch Throwable _ nil))
        (System/exit 1)))))
