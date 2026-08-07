(ns resolver-sim.lab.exec
  "Execution boundary: spawns an isolated subprocess per lab run.

   The web server never runs PRF experiment code in its own JVM. Each lab run
   is a fresh `java` process running `resolver-sim.lab.runner`, which is the
   only code that touches PRF mechanism functions. The subprocess command is
   built entirely from fixed parts (the JVM, the lab runner namespace, and a
   server-generated request/run-id/output path). No visitor input ever appears
   in argv."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.lab.json :as lab-json])
  (:import [java.io File]
           [java.nio.file Files]
           [java.util.concurrent TimeUnit]))

(defn current-classpath
  "Resolve the full runtime classpath. java.class.path is populated by both the
   clojure CLI and the deployed java -jar service."
  []
  (System/getProperty "java.class.path"))

(defn runner-argv
  "Fixed command vector for the lab runner subprocess."
  [request-file run-id output-file]
  (let [cp (current-classpath)
        explicit-jar (or (System/getenv "LAB_RUNNER_JAR")
                         (System/getProperty "lab.runner.jar"))
        path-sep (System/getProperty "path.separator")
        single-jar? (and cp
                         (not (str/includes? cp path-sep))
                         (.endsWith ^String cp ".jar"))]
    (cond
      explicit-jar
      ["java" "-jar" explicit-jar "-m" "resolver-sim.lab.runner"
       request-file run-id output-file]

      single-jar?
      ["java" "-jar" cp "-m" "resolver-sim.lab.runner"
       request-file run-id output-file]

      :else
      ["java" "-cp" cp "clojure.main" "-m" "resolver-sim.lab.runner"
       request-file run-id output-file])))

(defn- run-process
  "Start argv, wait up to timeout-ms, capture output. Returns
   {:code int :output str :timed-out? bool}."
  [argv timeout-ms]
  (let [pb (doto (ProcessBuilder. ^java.util.List argv)
             (.redirectErrorStream true))
        proc (.start pb)
        read-future (future (try (slurp (.getInputStream proc))
                                 (catch Throwable _ "")))
        finished (promise)]
    (future
      (try
        (let [code (.waitFor proc)]
          (deliver finished {:code code :output @read-future}))
        (catch Throwable _
          (deliver finished {:code nil :output ""}))))
    (let [result (deref finished timeout-ms ::timeout)]
      (if (= result ::timeout)
        (do (.destroyForcibly proc)
            {:code nil :output (str "lab run timed out after " timeout-ms " ms")
             :timed-out? true})
        result))))

(defn- publish-to-s3
  "Best-effort copy of the result file to the lab runs prefix in S3, using the
   instance's AWS CLI + IAM role. Never blocks the run outcome."
  [result-file bucket region run-id]
  (when (and bucket (seq bucket))
    (future
      (try
        (let [pb (doto (ProcessBuilder. ^java.util.List
                        ["aws" "s3" "cp" result-file
                         (str "s3://" bucket "/lab/runs/" run-id "/result.json")
                         "--region" region])
                   (.redirectErrorStream true))
              proc (.start pb)]
          (.waitFor proc (long 30) TimeUnit/SECONDS)
          (.destroyForcibly proc))
        (catch Throwable _ nil)))))

(defn write-request-file!
  "Persist the JSON request to a server-generated path under runs-dir."
  [runs-dir run-id request]
  (let [dir (io/file runs-dir run-id)]
    (Files/createDirectories (.toPath dir) (make-array java.nio.file.attribute.FileAttribute 0))
    (let [f (io/file dir "request.json")]
      (spit f (lab-json/write-str request))
      (str f))))

(defn run-experiment!
  "Execute a lab run in an isolated subprocess.

   opts := {:runs-dir <dir> :timeout-ms <int> :publish-bucket <str|nil>
            :region <str> :runner-output <fn>}

   Returns the normalized lab result map (possibly
   {:lab-run/status :execution-error ...}) parsed from the runner's output
   file. Throws only on infrastructure failures (unable to write/spawn)."
  [request run-id {:keys [runs-dir timeout-ms publish-bucket region]
                   :or {runs-dir "/tmp/lab-runs" timeout-ms 90000}}]
  (let [request-file (write-request-file! runs-dir run-id request)
        output-file (str request-file ".result.json")
        argv (runner-argv request-file run-id output-file)
        {:keys [code output timed-out?]} (run-process argv timeout-ms)
        result (if (and output-file (.exists (io/file output-file)))
                 (lab-json/read-str (slurp output-file))
                 {:lab-run/id run-id
                  :lab-run/status :execution-error
                  :lab-run/error {:type :runner-no-output
                                  :message (if timed-out?
                                             (str "lab run timed out after " timeout-ms " ms")
                                             (str "lab runner produced no result (exit " code ")"))}
                  :lab-run/reference run-id})]
    (when (and (= :completed (:lab-run/status result))
               output-file)
      (publish-to-s3 output-file publish-bucket region run-id))
    result))
