(ns resolver-sim.monitoring.dashboard
  "Minimal HTTP dashboard serving run metrics.

   Serves JSON metrics at /monitoring via a lightweight thread-per-request
   server built on java.net.ServerSocket.  No external dependencies."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [resolver-sim.monitoring :as monitoring]))

(def ^:private server (atom nil))
(def ^:private running (atom false))

(defn- json-response
  "Format a complete HTTP/1.1 200 JSON response as a string."
  [data]
  (let [body (json/write-str data :key-fn name)]
    (str "HTTP/1.1 200 OK\r\n"
         "Content-Type: application/json\r\n"
         "Content-Length: " (.length body) "\r\n"
         "\r\n"
         body)))

(defn- not-found-response
  []
  (str "HTTP/1.1 404 Not Found\r\n"
       "Content-Length: 0\r\n"
       "\r\n"))

(defn- handle-client
  "Read the HTTP request line, dispatch by path, write response, close."
  [socket]
  (try
    (with-open [rdr (io/reader socket)
                wtr (io/writer socket)]
      (let [request-line (.readLine rdr)
            path (when request-line
                   (second (re-find #"GET\s+(\S+)" request-line)))]
        (if (= "/monitoring" path)
          (let [data (monitoring/counters-snapshot)
                resp (json-response
                      {:status (if (:running data) "ok" "stopped")
                       :counters {:scenarios-run (:scenarios-run data)
                                  :scenarios-passed (:scenarios-passed data)
                                  :scenarios-failed (:scenarios-failed data)
                                  :evidence-captured (:evidence-captured data)}
                       :uptime-seconds (long (/ (- (System/currentTimeMillis) (:start-ms data)) 1000))})]
            (.write wtr resp))
          (.write wtr (not-found-response)))
        (.flush wtr)))
    (catch Exception _)
    (finally
      (try (.close socket) (catch Exception _)))))

(defn- accept-loop
  "Blocking loop: accept connections and dispatch each in a future."
  [server-socket]
  (while @running
    (try
      (let [client (.accept server-socket)]
        (future (handle-client client)))
      (catch java.net.SocketException _
        ;; server socket closed during accept — normal shutdown
        nil))))

(defn start-dashboard
  "Start the monitoring dashboard HTTP server on port 8090.
   Serves JSON metrics at http://localhost:8090/monitoring."
  []
  (when (nil? @server)
    (let [s (java.net.ServerSocket. 8090)]
      (reset! server s)
      (reset! running true)
      (doto (Thread. #(accept-loop s))
        (.setDaemon true)
        (.start))))
  nil)

(defn shutdown-dashboard
  "Stop the monitoring dashboard HTTP server."
  []
  (reset! running false)
  (when-let [s @server]
    (try (.close s) (catch Exception _))
    (reset! server nil))
  nil)

(defn dashboard-enabled?
  "Return true when the dashboard server is running."
  []
  (boolean (and @server @running)))
