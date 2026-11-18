(ns resolver-sim.research.researcher-challenges-devnet
  "Loopback-only HTTP transport for a local researcher-challenge devnet.

   Signed challenge actions remain the protocol. This adapter supplies bounded
   local transport and durable file-backed admission; it is not a public or
   testnet service and does not hold participant, reviewer, or operator keys."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.network.researcher-challenges :as challenges]
            [resolver-sim.network.shortfall-pilot-adapter :as pilot-adapter]
            [resolver-sim.research.researcher-challenges-cli :as challenge-cli])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.io ByteArrayInputStream ByteArrayOutputStream PushbackReader InputStreamReader]
           [java.net InetAddress InetSocketAddress]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files]
           [java.util.concurrent Executors ExecutorService]))

(def ^:private max-request-bytes (* 1024 1024))
(def ^:private requests-per-minute 120)

(defn- read-edn-file [path]
  (edn/read-string (slurp path)))

(defn- require-loopback! [bind-address]
  (when-not (and (string? bind-address)
                 (contains? #{"127.0.0.1" "::1"} bind-address)
                 (.isLoopbackAddress (InetAddress/getByName bind-address)))
    (throw (ex-info "Local challenge devnet may bind only to a loopback address"
                    {:reason :non-loopback-bind-rejected}))))

(defn- url-host [bind-address]
  ;; IPv6 literals must be bracketed in a URI authority.
  (if (str/includes? bind-address ":")
    (str "[" bind-address "]")
    bind-address))

(defn- require-test-configuration! [config]
  (let [network-id (:network/id config)
        currency (get-in config [:reward/policy :reward/currency])]
    (when-not (and (contains? #{"devnet" "local-devnet" "testnet"}
                              (namespace network-id))
                   (string? currency)
                   (str/starts-with? currency "TEST-"))
      (throw (ex-info "Local devnet requires a devnet/testnet scope and TEST-* reward currency"
                      {:reason :non-test-configuration-rejected})))
    config))

(defn- body-bytes [^HttpExchange exchange]
  (let [declared (some-> (.getFirst (.getRequestHeaders exchange) "Content-Length") Long/parseLong)]
    (when (and declared (> declared max-request-bytes))
      (throw (ex-info "Request body exceeds the local devnet limit"
                      {:http/status 413 :reason :request-too-large})))
    (with-open [body (.getRequestBody exchange)
                out (ByteArrayOutputStream.)]
      (let [buffer (byte-array 8192)]
        (loop [total 0]
          (let [read (.read body buffer)]
            (if (neg? read)
              (.toByteArray out)
              (let [next-total (+ total read)]
                (when (> next-total max-request-bytes)
                  (throw (ex-info "Request body exceeds the local devnet limit"
                                  {:http/status 413 :reason :request-too-large})))
                (.write out buffer 0 read)
                (recur next-total)))))))))

(defn- parse-action [^HttpExchange exchange]
  (let [content-type (some-> (.getFirst (.getRequestHeaders exchange) "Content-Type")
                             (str/split #";" 2) first str/lower-case str/trim)]
    (when-not (= "application/edn" content-type)
      (throw (ex-info "Signed actions must use application/edn"
                      {:http/status 415 :reason :unsupported-content-type})))
    (let [bytes (body-bytes exchange)
          reader (PushbackReader. (InputStreamReader. (ByteArrayInputStream. bytes)
                                                      StandardCharsets/UTF_8))
          eof (Object.)
          value (edn/read {:eof eof} reader)
          trailing (edn/read {:eof eof} reader)]
      (when (or (identical? eof value) (not (identical? eof trailing)) (not (map? value)))
        (throw (ex-info "Request must contain exactly one EDN action map"
                        {:http/status 400 :reason :invalid-action-body})))
      value)))

(defn- respond! [^HttpExchange exchange status value]
  (let [bytes (.getBytes (str (pr-str value) "\n") StandardCharsets/UTF_8)
        headers (.getResponseHeaders exchange)]
    (.set headers "Content-Type" "application/edn; charset=utf-8")
    (.set headers "Cache-Control" "no-store")
    (.set headers "X-Content-Type-Options" "nosniff")
    (.sendResponseHeaders exchange (int status) (long (alength bytes)))
    (with-open [out (.getResponseBody exchange)] (.write out bytes))))

(defn- request-address [^HttpExchange exchange]
  (some-> exchange .getRemoteAddress .getAddress .getHostAddress))

(defn- allow-request! [limits address now]
  (locking limits
    (let [[window-start count] (get @limits address [now 0])
          [window-start count] (if (>= (- now window-start) 60000) [now 0]
                                   [window-start count])]
      (when (>= count requests-per-minute)
        (throw (ex-info "Local devnet request limit exceeded"
                        {:http/status 429 :reason :request-rate-limited})))
      (swap! limits assoc address [window-start (inc count)]))))

(defn- publish-action! [{:keys [config-path history-path control-policy-path
                                control-policy profile evidence policy-lock]} action]
  (locking policy-lock
    (let [action-file (Files/createTempFile "researcher-challenge-action-" ".edn"
                                            (make-array java.nio.file.attribute.FileAttribute 0))]
      (try
        (spit (.toFile action-file) (pr-str action))
        (let [check (fn [current-config next-history]
                      (if control-policy-path
                        (pilot-adapter/replay-controlled current-config profile
                                                         control-policy
                                                         next-history evidence)
                        (pilot-adapter/replay current-config profile next-history evidence)))]
          (if control-policy-path
            (challenge-cli/apply-controlled-checked-file!
             config-path control-policy-path history-path (str action-file) nil check)
            (challenge-cli/apply-checked-file!
             config-path history-path (str action-file) nil check)))
        (finally (Files/deleteIfExists action-file))))))

(defn- route! [{:keys [config history-path profile evidence control-policy
                       config-path control-policy-path history-lock limits]} ^HttpExchange exchange]
  (let [method (.getRequestMethod exchange)
        path (.getPath (.getRequestURI exchange))]
    (cond
      (and (= "GET" method) (= "/healthz" path))
      (let [history (if (.isFile (io/file history-path)) (read-edn-file history-path) [])
            state (if control-policy
                    (challenges/replay-controlled config (:control-policy control-policy) history)
                    (challenges/replay config history))]
        (respond! exchange 200 {:service/status :ready
                                :service/environment :local-devnet
                                :network/id (:network/id config)
                                :configuration/root (:artifact/root config)
                                :network/root (:network/root config)
                                :history/root (:history/root state)
                                :history/action-count (count history)}))

      (and (= "GET" method) (= "/v1/configuration" path))
      (respond! exchange 200 config)

      (and (= "GET" method) (= "/v1/history" path))
      (let [history (if (.isFile (io/file history-path)) (read-edn-file history-path) [])]
        (respond! exchange 200 history))

      (and (= "GET" method) (= "/v1/checkpoint" path))
      (let [history (if (.isFile (io/file history-path)) (read-edn-file history-path) [])]
        (respond! exchange 200
                  (if control-policy
                    (challenges/controlled-history-checkpoint config
                                                              (:control-policy control-policy) history)
                    (challenges/history-checkpoint config history))))

      (and (= "POST" method) (= "/v1/actions" path))
      (do
        (allow-request! limits (or (request-address exchange) "unknown")
                        (System/currentTimeMillis))
        (let [action (parse-action exchange)
              result (publish-action! {:config-path config-path
                                       :history-path history-path
                                       :control-policy-path control-policy-path
                                       :profile profile :evidence evidence
                                       :control-policy (:control-policy control-policy)
                                       :policy-lock history-lock}
                                      action)]
          (respond! exchange 201 result)))

      :else
      (respond! exchange 404 {:error/code :not-found}))))

(defn start!
  "Start a local devnet API from already-built public artifacts and a local
   history path. Refuses non-loopback exposure and retains all action checks in
   the existing locked file-admission path."
  [{:keys [config-path history-path profile-path evidence-path control-policy-path
           bind-address port]
    :or {bind-address "127.0.0.1" port 0}}]
  (require-loopback! bind-address)
  (when-not (and (string? config-path) (string? history-path)
                 (string? profile-path) (string? evidence-path)
                 (integer? port) (<= 0 port 65535))
    (throw (ex-info "Local devnet requires config, history, profile, evidence and a valid port"
                    {:reason :invalid-devnet-options})))
  (let [config (read-edn-file config-path)
        profile (read-edn-file profile-path)
        evidence (read-edn-file evidence-path)
        control-policy (when control-policy-path (read-edn-file control-policy-path))
        history (if (.isFile (io/file history-path)) (read-edn-file history-path) [])
        _ (require-test-configuration! config)
        _ (if control-policy
            (pilot-adapter/replay-controlled config profile control-policy history evidence)
            (pilot-adapter/replay config profile history evidence))
        server (HttpServer/create (InetSocketAddress. bind-address (int port)) 0)
        executor (Executors/newFixedThreadPool 4)
        service {:config config :config-path config-path :history-path history-path
                 :profile profile :evidence evidence
                 :control-policy (when control-policy {:control-policy control-policy})
                 :control-policy-path control-policy-path
                 :history-lock (Object.) :limits (atom {})}
        context (reify HttpHandler
                  (handle [_ exchange]
                    (try
                      (route! service exchange)
                      (catch clojure.lang.ExceptionInfo error
                        (respond! exchange (int (or (:http/status (ex-data error)) 422))
                                  {:error/code (or (:reason (ex-data error)) :invalid-action)
                                   :error/message (.getMessage error)}))
                      (catch Exception _
                        (respond! exchange 500
                                  {:error/code :internal-error
                                   :error/message "Request failed; no internal details are exposed"})))))]
    (.createContext server "/" context)
    (.setExecutor server executor)
    (try
      (.start server)
      {:server server :executor executor :config config :history-path history-path
       :host bind-address :port (.getPort (.getAddress server))
       :base-url (str "http://" (url-host bind-address) ":" (.getPort (.getAddress server)))
       :stop (fn [] (.stop server 0) (.shutdownNow ^ExecutorService executor))}
      (catch Exception error
        (.shutdownNow ^ExecutorService executor)
        (throw error)))))

(defn -main [& args]
  (let [[config history profile evidence & rest] args
        port (if-let [value (first rest)] (Long/parseLong value) 0)
        control-policy (second rest)]
    (when-not (and config history profile evidence (<= (count rest) 2))
      (throw (ex-info "Usage: clojure -M -m resolver-sim.research.researcher-challenges-devnet CONFIG HISTORY PROFILE EVIDENCE [PORT] [CONTROL_POLICY]"
                      {:reason :invalid-arguments})))
    (let [{:keys [base-url stop config]} (start! {:config-path config
                                                  :history-path history
                                                  :profile-path profile
                                                  :evidence-path evidence
                                                  :control-policy-path control-policy
                                                  :port port})]
      (println (str "Local researcher-challenge devnet listening on " base-url))
      (println (str "Configuration root: " (:artifact/root config)))
      (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable stop))
      @(promise))))
