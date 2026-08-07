(ns resolver-sim.lab.http
  "Assurance Lab public HTTP API.

   A minimal, dependency-light HTTP server (JDK com.sun.net.httpserver) that
   exposes the allowlisted experiment catalogue and executes runs through the
   isolated subprocess boundary in resolver-sim.lab.exec.

   Security model:
     - GET-only for the experiment catalogue and run lookup;
     - POST /api/lab/runs accepts ONLY {experiment, parameters} and the
       registry/validator reject everything else (no command, no symbols);
     - request body size limit, execution timeout, concurrency cap, and a
       simple global rate limit;
     - sanitized errors: no stack traces, every failure carries a
       LAB-RUN-ID reference an operator can trace in the logs.";
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.lab.exec :as exec]
            [resolver-sim.lab.json :as lab-json]
            [resolver-sim.lab.registry :as registry]
            [resolver-sim.lab.runner :as lab-runner])
  (:import [com.sun.net.httpserver HttpExchange HttpServer]
           [java.io ByteArrayOutputStream InputStream]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]))

(def ^:private max-body-bytes (* 64 1024))
(def ^:private default-timeout-ms 90000)
(def ^:private max-concurrent-runs 2)
(def ^:private rate-limit-window-ms 60000)
(def ^:private rate-limit-max-requests 60)

(defn- now-ms [] (System/currentTimeMillis))

(defn- config
  "Read a setting from environment then system property, with a default."
  [env prop default]
  (or (System/getenv env)
      (System/getProperty prop)
      default))

(defn- new-limiter
  [max-concurrent]
  (let [sem (java.util.concurrent.Semaphore. (long max-concurrent))]
    {:semaphore sem :request-times (atom (java.util.ArrayDeque.))}))

(defonce ^:private state (atom nil))

(defn- init-state!
  []
  (swap! state (fn [s] (or s {:limiter (new-limiter max-concurrent-runs)
                              :started-at (now-ms)}))))

(defn- acquire!
  "Try to acquire a concurrency slot. Returns true or false (non-blocking)."
  []
  (let [sem (:semaphore (:limiter @state))]
    (.tryAcquire ^java.util.concurrent.Semaphore sem)))

(defn- release!
  []
  (.release ^java.util.concurrent.Semaphore (:semaphore (:limiter @state))))

(defn- rate-limited?
  "Simple global sliding-window rate limit. Returns true when the request
   should be rejected (429)."
  []
  (let [queue (:request-times (:limiter @state))
        now (now-ms)
        cutoff (- now rate-limit-window-ms)
        window (swap! queue (fn [q]
                              (->> q (drop-while #(< % cutoff)) vec)))]
    (if (>= (count window) rate-limit-max-requests)
      true
      (do (swap! queue conj now)
          false))))

(defn- log
  [& parts]
  (let [line (str "lab " (str/join " " parts))]
    (binding [*out* *out*]
      (println line))))

(defn- json-response
  [exchange status m]
  (let [body (lab-json/write-str m)
        bytes (.getBytes body StandardCharsets/UTF_8)
        headers (.getResponseHeaders exchange)]
    (.set headers "Content-Type" "application/json; charset=utf-8")
    (.set headers "Access-Control-Allow-Origin" "*")
    (.sendResponseHeaders exchange (long status) (long (alength bytes)))
    (with-open [os (.getResponseBody exchange)]
      (.write os bytes))))

(defn- error-response
  [exchange status message reference]
  (json-response exchange status
                 {:lab/status :error
                  :lab/error message
                  :lab/reference reference}))

(defn- read-body
  "Read request body up to max-body-bytes. Returns {:ok? true :body str} or
   {:ok? false :too-large? bool}."
  [exchange]
  (let [in (.getRequestBody ^HttpExchange exchange)
        buffer (ByteArrayOutputStream. 1024)
        chunk (byte-array 4096)]
    (loop [total 0]
      (let [n (.read ^InputStream in chunk)]
        (cond
          (neg? n) {:ok? true :body (String. (.toByteArray buffer) StandardCharsets/UTF_8)}
          (> (+ total n) max-body-bytes) {:ok? false :too-large? true}
          :else
          (do (.write buffer chunk 0 n)
              (recur (+ total n))))))))

(defn- parse-request
  [body]
  (try
    (let [m (lab-json/read-str body)]
      (if (map? m) {:ok? true :request m} {:ok? false :error "request must be a JSON object"}))
    (catch Throwable _
      {:ok? false :error "malformed JSON body"})))

(defn- handle-experiments
  [exchange]
  (json-response exchange 200
                 {:lab/status :ok
                  :experiments (mapv registry/experiment->public registry/experiments)}))

(defn- handle-experiment
  [exchange id]
  (if-let [experiment (registry/find-experiment id)]
    (json-response exchange 200
                   {:lab/status :ok
                    :experiment (registry/experiment->public experiment)})
    (json-response exchange 404
                   {:lab/status :error :lab/error (str "unknown experiment: " id)})))

(defn- handle-run
  [exchange]
  (if (rate-limited?)
    (error-response exchange 429 "rate limit exceeded" nil)
    (let [{:keys [ok? body too-large?]} (read-body exchange)]
      (cond
        too-large?
        (error-response exchange 413 "request too large" nil)

        (not ok?)
        (error-response exchange 400 "could not read request body" nil)

        :else
        (let [{:keys [ok? request error]} (parse-request body)]
          (cond
            (not ok?)
            (error-response exchange 400 error nil)

            :else
            (if-not (acquire!)
              (error-response exchange 429 "too many concurrent runs" nil)
              (try
                (let [run-id (lab-runner/generate-run-id)
                      runs-dir (config "LAB_RUNS_DIR" "lab.runs.dir" "/var/lib/lab/runs")
                      timeout-ms (long (or (parse-long (config "LAB_RUN_TIMEOUT_MS" "lab.run.timeout.ms" ""))
                                           default-timeout-ms))
                      publish-bucket (config "LAB_PUBLISH_BUCKET" "lab.publish.bucket" "")
                      region (or (config "LAB_REGION" "lab.region" "") "eu-north-1")
                      result (exec/run-experiment!
                              request run-id
                              {:runs-dir runs-dir
                               :timeout-ms timeout-ms
                               :publish-bucket publish-bucket
                               :region region})
                      status (:lab-run/status result)]
                  (log "run" run-id (name status)
                       (str "took-" (get-in result [:execution :duration-ms]) "ms"))
                  (cond
                    (= status :validation-error)
                    (json-response exchange 400 result)

                    (= status :execution-error)
                    (json-response exchange 200 result)

                    :else
                    (json-response exchange 200 result)))
                (finally
                  (release!))))))))))

(defn- handle-run-lookup
  [exchange run-id]
  (let [runs-dir (config "LAB_RUNS_DIR" "lab.runs.dir" "/var/lib/lab/runs")
        file (io/file runs-dir run-id "request.json.result.json")]
    (if (.exists file)
      (json-response exchange 200 (lab-json/read-str (slurp file)))
      (error-response exchange 404 (str "unknown lab run: " run-id) run-id))))

(defn- route
  [exchange]
  (let [method (.getRequestMethod ^HttpExchange exchange)
        uri (.getRequestURI ^HttpExchange exchange)
        path (.getPath uri)
        segments (vec (remove str/blank? (str/split path #"/")))]
    (cond
      (and (= method "GET") (= ["api" "lab" "experiments"] segments))
      (handle-experiments exchange)

      (and (= method "GET") (= ["api" "lab" "experiments"] (subvec segments 0 3)) (= 4 (count segments)))
      (handle-experiment exchange (nth segments 3))

      (and (= method "GET") (= ["api" "lab" "runs"] (subvec segments 0 3)) (= 4 (count segments)))
      (handle-run-lookup exchange (nth segments 3))

      (and (= method "POST") (= ["api" "lab" "runs"] segments))
      (handle-run exchange)

      (and (= method "GET") (= ["api" "lab" "health"] segments))
      (json-response exchange 200 {:lab/status :ok :service :assurance-lab})

      :else
      (error-response exchange 404 (str "not found: " method " " path) nil))))

(defn- handler
  [exchange]
  (try
    (route exchange)
    (catch Throwable t
      (log "error" (str t))
      (doseq [el (take 8 (.getStackTrace t))]
        (println "    at" (str el)))
      (try
        (error-response exchange 500 "internal error" nil)
        (catch Throwable _ nil)))))

(defn start!
  "Start the lab HTTP server. options:
     :port (default 8082), :bind (default \"127.0.0.1\"),
     :max-concurrent (default 2)"
  [& [{:keys [port bind max-concurrent]
       :or {port 8082 bind "127.0.0.1" max-concurrent max-concurrent-runs}}]]
  (init-state!)
  (swap! state assoc :limiter (new-limiter max-concurrent))
  (let [server (HttpServer/create (InetSocketAddress. ^String bind (int port)) 0)]
    (.createContext server "/" (reify com.sun.net.httpserver.HttpHandler
                                 (handle [_ exchange] (handler exchange))))
    (.setExecutor server (java.util.concurrent.Executors/newFixedThreadPool 4))
    (.start server)
    (log "listening" (str bind ":" port))
    server))

(defn -main
  "Standalone entry: java -jar ... -m resolver-sim.lab.http [port]"
  [& args]
  (let [port (if (seq args) (parse-long (first args)) 8082)]
    (let [server (start! {:port port})]
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. (fn [] (.stop server)))))))
