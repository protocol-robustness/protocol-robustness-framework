(ns resolver-sim.research.runner-transport
  "Research-only authenticated HTTP transport for independent runner agents.

   This is deliberately a small lab adapter, not a production worker network:
   it provides authenticated message delivery, real local/TCP reachability, and
   canonical evidence through the existing execution-control artifacts. The
   shared transport secret, socket address, process identity, and HTTP logs are
   runtime-only. They never define a job or result root."
  (:require [clojure.edn :as edn]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.research.execution-control :as control]
            [resolver-sim.research.execution-assurance :as assurance]
            [resolver-sim.research.observed-assurance :as observed]
            [resolver-sim.research.observed-execution-work :as observed-work]
            [resolver-sim.research.remote-execution :as remote]
            [resolver-sim.research.runner-participation :as participation])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.time Duration]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

(def ^:private mac-header "X-PRF-Research-MAC")
(def ^:private content-type "application/edn; charset=utf-8")
(def ^:private client (HttpClient/newHttpClient))
(declare deliver-observed-result! execute-delivered-work!)

(defn- utf8 [value] (.getBytes (str value) StandardCharsets/UTF_8))

(defn- hmac-bytes [secret ^bytes payload]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. (utf8 secret) "HmacSHA256"))
    (.doFinal mac payload)))

(defn- hex [^bytes bytes]
  (.formatHex (java.util.HexFormat/of) bytes))

(defn- authenticated? [secret ^bytes payload supplied]
  (try
    (and (string? supplied)
         (MessageDigest/isEqual (hmac-bytes secret payload)
                                (.parseHex (java.util.HexFormat/of) supplied)))
    (catch IllegalArgumentException _ false)))

(defn- response! [^HttpExchange exchange status body]
  (let [payload (utf8 (pr-str body))]
    (.set (.getResponseHeaders exchange) "Content-Type" content-type)
    (.sendResponseHeaders exchange status (alength payload))
    (with-open [out (.getResponseBody exchange)]
      (.write out payload))))

(defn- request-body [^HttpExchange exchange]
  (.readAllBytes (.getRequestBody exchange)))

(defn- assignment-for-job [state job-root]
  (some (fn [[_ assignment]]
          (when (= job-root (:research-execution-job/root assignment)) assignment))
        (:assignments state)))

(defn- valid-delivery? [runner {:keys [job assignment]}]
  (and (remote/valid-job? job)
       (remote/valid-assignment? assignment)
       (= (:research-execution-job/root job)
          (:research-execution-job/root assignment))
       (= (:runner-participation/root (:runner-participation runner))
          (:runner-participation/root assignment))
       (= (:runner/reference (:runner-participation runner))
          (:runner/reference assignment))))

(defn- valid-work-delivery? [runner job assignment work]
  (and (observed-work/valid-work? work)
       (= job (:work/job work))
       (= assignment (:work/assignment work))
       (= (:runner-participation runner) (:work/runner-participation work))))

(defn- valid-observed-result-delivery?
  "A result return is accepted only as observed, already-assured execution
   evidence.  The predecessor declaration/simulation run-evidence V1 is not a
   substitute for an observed run here."
  [state {:keys [assignment observed-run-evidence run-assurance-evidence]}]
  (let [stored (get-in state
                       [:assignments (:research-run-assignment/root assignment)])]
    (and (remote/valid-assignment? assignment)
         (= assignment stored)
         (observed/valid-observed-run-evidence? observed-run-evidence)
         (assurance/valid-run-assurance-evidence? run-assurance-evidence)
         (= (:research-execution-job/root assignment)
            (:research-execution-job/root observed-run-evidence))
         (= (:research-run-assignment/root assignment)
            (:research-run-assignment/root observed-run-evidence))
         (= (:runner-participation/root assignment)
            (:runner-participation/root observed-run-evidence))
         (= (:runner/reference assignment)
            (:runner/reference observed-run-evidence))
         (= (:research-observed-run-evidence/root observed-run-evidence)
            (:research-run-evidence/root run-assurance-evidence))
         (= (:semantic-result/root observed-run-evidence)
            (:semantic-result/root run-assurance-evidence))
         (= (:runner/reference observed-run-evidence)
            (:runner/reference run-assurance-evidence)))))

(defn- job-handler [runner]
  (reify HttpHandler
    (handle [_ exchange]
      (let [payload (request-body exchange)
            secret (:transport/auth-secret runner)
            supplied (some-> exchange .getRequestHeaders (.getFirst mac-header))]
        (cond
          (not= "POST" (.getRequestMethod exchange))
          (response! exchange 405 {:transport/status :method-not-allowed})

          (not (authenticated? secret payload supplied))
          (response! exchange 401 {:transport/status :authentication-rejected})

          :else
          (try
            (let [message (edn/read-string (String. payload StandardCharsets/UTF_8))]
              (if-not (and (= :research/job-delivery (:transport/kind message))
                           (valid-delivery? runner message))
                (response! exchange 422 {:transport/status :invalid-delivery})
                (let [{:keys [job assignment logical-cutpoint execution-work]} message
                      job-root (:research-execution-job/root job)
                      cancellation (get-in @(:transport/state runner)
                                           [:cancellations job-root])]
                  (if cancellation
                    (response! exchange 200
                               {:transport/status :cancelled-before-start
                                :research-execution-job/root job-root
                                :research-run-assignment/root
                                (:research-run-assignment/root assignment)
                                :runner/reference
                                (:runner/reference (:runner-participation runner))
                                :transport/logical-cutpoint logical-cutpoint})
                    (do
                      (when (and execution-work
                                 (not (valid-work-delivery? runner job assignment execution-work)))
                        (throw (ex-info "Invalid dispatched observed execution work" {})))
                      (swap! (:transport/state runner)
                             (fn [state]
                               (-> state
                                   (assoc-in [:jobs job-root] job)
                                   (assoc-in [:assignments
                                              (:research-run-assignment/root assignment)]
                                             assignment))))
                      (let [execution-result (when execution-work
                                               (execute-delivered-work! runner execution-work logical-cutpoint))]
                        (response! exchange 200
                                 {:transport/status :accepted
                                  :research-execution-job/root job-root
                                  :research-run-assignment/root
                                  (:research-run-assignment/root assignment)
                                  :runner/reference
                                  (:runner/reference (:runner-participation runner))
                                  :transport/execution-status (when execution-result :complete)
                                  :transport/logical-cutpoint logical-cutpoint})))))))
            (catch clojure.lang.ExceptionInfo e
              (let [cause (:cause (ex-data e))]
                (response! exchange 422
                           (cond-> {:transport/status (if cause :execution-failed :invalid-delivery)}
                             cause (assoc :execution/failure-cause cause)))))
            (catch Exception _
              (response! exchange 422 {:transport/status :invalid-delivery}))))))))

(defn- cancellation-handler [runner]
  (reify HttpHandler
    (handle [_ exchange]
      (let [payload (request-body exchange)
            secret (:transport/auth-secret runner)
            supplied (some-> exchange .getRequestHeaders (.getFirst mac-header))]
        (cond
          (not= "POST" (.getRequestMethod exchange))
          (response! exchange 405 {:transport/status :method-not-allowed})

          (not (authenticated? secret payload supplied))
          (response! exchange 401 {:transport/status :authentication-rejected})

          :else
          (try
            (let [{:keys [cancellation logical-cutpoint]} (edn/read-string
                                                          (String. payload StandardCharsets/UTF_8))
                  job-root (:research-execution-job/root cancellation)]
              (control/ingest-cancellation {} cancellation)
              (swap! (:transport/state runner) assoc-in [:cancellations job-root] cancellation)
              (let [assignment (assignment-for-job @(:transport/state runner) job-root)]
                (response! exchange 200
                           {:transport/status :observed
                            :research-execution-job/root job-root
                            :research-job-cancellation/root
                            (:research-job-cancellation/root cancellation)
                            :control/epoch (:control/epoch cancellation)
                            :research-run-assignment/root
                            (:research-run-assignment/root assignment)
                            :runner/reference
                            (:runner/reference (:runner-participation runner))
                            :transport/logical-cutpoint logical-cutpoint})))
            (catch Exception _
              (response! exchange 422 {:transport/status :invalid-cancellation}))))))))

(defn- coordinator-result-handler [coordinator]
  (reify HttpHandler
    (handle [_ exchange]
      (let [payload (request-body exchange)
            secret (:transport/auth-secret coordinator)
            supplied (some-> exchange .getRequestHeaders (.getFirst mac-header))]
        (cond
          (not= "POST" (.getRequestMethod exchange))
          (response! exchange 405 {:transport/status :method-not-allowed})

          (not (authenticated? secret payload supplied))
          (response! exchange 401 {:transport/status :authentication-rejected})

          :else
          (try
            (let [message (edn/read-string (String. payload StandardCharsets/UTF_8))]
              (if-not (and (= :research/observed-result-delivery (:transport/kind message))
                           (valid-observed-result-delivery? @(:transport/state coordinator) message))
                (response! exchange 422 {:transport/status :invalid-observed-result})
                (let [{:keys [assignment observed-run-evidence run-assurance-evidence
                              logical-cutpoint]} message
                      observed-root (:research-observed-run-evidence/root observed-run-evidence)
                      existing (get-in @(:transport/state coordinator)
                                       [:observed-results observed-root])]
                  (when-not existing
                    (swap! (:transport/state coordinator)
                           assoc-in [:observed-results observed-root]
                           {:assignment assignment
                            :observed-run-evidence observed-run-evidence
                            :run-assurance-evidence run-assurance-evidence}))
                  (response! exchange 200
                             {:transport/status (if existing :already-recorded :accepted)
                              :research-execution-job/root
                              (:research-execution-job/root observed-run-evidence)
                              :research-run-assignment/root
                              (:research-run-assignment/root assignment)
                              :research-observed-run-evidence/root observed-root
                              :research-run-assurance-evidence/root
                              (:research-run-assurance-evidence/root run-assurance-evidence)
                              :coordinator/root (:coordinator/root coordinator)
                              :transport/logical-cutpoint logical-cutpoint}))))
            (catch Exception _
              (response! exchange 422 {:transport/status :invalid-observed-result}))))))))

(defn start-runner!
  "Start one independent, authenticated lab-runner endpoint.

   `:runner-participation` is canonical; `:auth-secret` and binding address are
   runtime configuration. Use a non-loopback bind address on separate hosts.
   The returned endpoint URL is intentionally not part of the runner's root."
  [{:keys [runner-participation auth-secret bind-address port advertise-url
           coordinator-root coordinator-endpoint]
    :or {bind-address "127.0.0.1" port 0}}]
  (when-not (and (:valid? (participation/validate-participation runner-participation))
                 (string? auth-secret) (not (empty? auth-secret))
                 (string? bind-address) (integer? port) (<= 0 port 65535)
                 (or (nil? advertise-url) (string? advertise-url))
                 (or (and (nil? coordinator-root) (nil? coordinator-endpoint))
                     (and (hash-ref/valid-sha256-ref? coordinator-root)
                          (map? coordinator-endpoint))))
    (throw (ex-info "Invalid research runner transport configuration" {})))
  (let [state (atom {:jobs {} :assignments {} :cancellations {} :completed-work {}})
        server (HttpServer/create (InetSocketAddress. bind-address (int port)) 0)
        runner {:runner-participation runner-participation
                :transport/auth-secret auth-secret
                :transport/coordinator-root coordinator-root
                :transport/coordinator-endpoint coordinator-endpoint
                :transport/state state
                :transport/server server}]
    (.createContext server "/v1/jobs" (job-handler runner))
    (.createContext server "/v1/cancellations" (cancellation-handler runner))
    (.setExecutor server nil)
    (.start server)
    (assoc runner :transport/base-url
           (or advertise-url
               (str "http://" bind-address ":" (.getPort (.getAddress server)))))))

(defn stop-runner! [runner]
  (when-let [server (:transport/server runner)] (.stop ^HttpServer server 0))
  nil)

(defn runner-state [runner] @(:transport/state runner))

(defn start-coordinator!
  "Start the research-only coordinator result sink. `assignments` are already
   canonical scheduler decisions; the server only records observed evidence
   bound to one of them. Its address and secret are runtime-only."
  [{:keys [coordinator-root assignments auth-secret bind-address port advertise-url]
    :or {bind-address "127.0.0.1" port 0}}]
  (when-not (and (hash-ref/valid-sha256-ref? coordinator-root)
                 (vector? assignments) (every? remote/valid-assignment? assignments)
                 (= (count assignments) (count (set (map :research-run-assignment/root assignments))))
                 (string? auth-secret) (not (empty? auth-secret))
                 (string? bind-address) (integer? port) (<= 0 port 65535)
                 (or (nil? advertise-url) (string? advertise-url)))
    (throw (ex-info "Invalid research coordinator transport configuration" {})))
  (let [state (atom {:assignments (into {} (map (juxt :research-run-assignment/root identity) assignments))
                     :observed-results {}})
        server (HttpServer/create (InetSocketAddress. bind-address (int port)) 0)
        coordinator {:coordinator/root coordinator-root
                     :transport/auth-secret auth-secret
                     :transport/state state
                     :transport/server server}]
    (.createContext server "/v1/results" (coordinator-result-handler coordinator))
    (.setExecutor server nil)
    (.start server)
    (assoc coordinator :transport/base-url
           (or advertise-url
               (str "http://" bind-address ":" (.getPort (.getAddress server)))))))

(defn stop-coordinator! [coordinator]
  (when-let [server (:transport/server coordinator)] (.stop ^HttpServer server 0))
  nil)

(defn coordinator-state [coordinator] @(:transport/state coordinator))

(defn coordinator-endpoint
  "Return a runtime-only transport endpoint from a started runner or coordinator."
  ([server auth-secret] (coordinator-endpoint server auth-secret 5000))
  ([server auth-secret request-timeout-ms]
   (when-not (and (string? (:transport/base-url server))
                  (string? auth-secret) (not (empty? auth-secret))
                  (integer? request-timeout-ms) (pos? request-timeout-ms))
    (throw (ex-info "Invalid research runner endpoint" {})))
   {:transport/base-url (:transport/base-url server)
    :transport/auth-secret auth-secret
    :transport/request-timeout-ms request-timeout-ms}))

(defn- post-edn! [endpoint path message]
  (let [body (pr-str message)
        timeout-ms (or (:transport/request-timeout-ms endpoint) 5000)
        request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create (str (:transport/base-url endpoint) path)))
                    (.timeout (Duration/ofMillis timeout-ms))
                    (.header "Content-Type" content-type)
                    (.header mac-header (hex (hmac-bytes (:transport/auth-secret endpoint)
                                                       (utf8 body))))
                    (.POST (HttpRequest$BodyPublishers/ofString body))
                    (.build))]
    (try
      (let [response (.send client request (HttpResponse$BodyHandlers/ofString))
            body (edn/read-string (.body response))]
        {:http/status (.statusCode response) :transport/response body})
      (catch Exception e
        {:http/status nil :transport/error :unreachable
         :transport/exception-class (.getName (class e))}))))

(defn execute-delivered-work!
  "Execute an already accepted rooted observed-execution bundle and return its
   existing observed evidence to the configured coordinator. This is a
   cooperative safe cutpoint: callers invoke it only before starting another
   work unit; no asynchronous process interruption is attempted."
  [runner work logical-cutpoint]
  (when-not (observed-work/valid-work? work)
    (throw (ex-info "Invalid dispatched observed execution work" {})))
  (let [coordinator-root (:transport/coordinator-root runner)
        endpoint (:transport/coordinator-endpoint runner)]
    (when-not (and (hash-ref/valid-sha256-ref? coordinator-root)
                   (map? endpoint))
      (throw (ex-info "Runner lacks runtime coordinator return endpoint" {})))
    (let [executed (observed-work/execute-work! work)
          observed-run-evidence (:observed-run-evidence executed)
          run-assurance-evidence (:run-assurance-evidence executed)
          returned (deliver-observed-result!
                    {:coordinator-root coordinator-root :endpoint endpoint
                     :assignment (:work/assignment work)
                     :observed-run-evidence observed-run-evidence
                     :run-assurance-evidence run-assurance-evidence
                     :logical-cutpoint logical-cutpoint})]
      (swap! (:transport/state runner) assoc-in
             [:completed-work (:research-observed-execution-work/root work)]
             {:observed-run-evidence observed-run-evidence
              :run-assurance-evidence run-assurance-evidence
              :result-delivery returned})
      {:execution executed :result-delivery returned})))

(defn retry-undelivered-results!
  "Retry coordinator delivery for completed work whose earlier result post was
   unreachable. The original semantic and forensic artifacts are reused; the
   coordinator's content-addressed ingestion is idempotent."
  ([runner logical-cutpoint]
   (retry-undelivered-results! runner (:transport/coordinator-endpoint runner)
                               logical-cutpoint))
  ([runner endpoint logical-cutpoint]
   (when-not (and (map? endpoint)
                  (integer? logical-cutpoint) (not (neg? logical-cutpoint)))
     (throw (ex-info "Invalid result retry parameters" {})))
   (let [pending (filter (fn [[_ completed]]
                           (= :unreachable (get-in completed [:result-delivery :delivery/status])))
                         (:completed-work (runner-state runner)))]
     (mapv (fn [[work-root completed]]
             (let [assignment (get-in completed [:observed-run-evidence
                                                 :proof/source-artifacts :assignment])
                   delivered (deliver-observed-result!
                              {:coordinator-root (:transport/coordinator-root runner)
                               :endpoint endpoint
                               :assignment assignment
                               :observed-run-evidence (:observed-run-evidence completed)
                               :run-assurance-evidence (:run-assurance-evidence completed)
                               :logical-cutpoint logical-cutpoint})]
               (swap! (:transport/state runner) assoc-in
                      [:completed-work work-root :result-delivery] delivered)
               {:research-observed-execution-work/root work-root
                :delivery delivered}))
           pending))))

(defn deliver-job!
  "Deliver a rooted job/assignment over HTTP and produce existing canonical
   realized-reachability evidence. Authentication rejection is a transport
   boundary failure, not a false claim that the route was unreachable."
  [{:keys [coordinator-root endpoint job assignment execution-work logical-cutpoint]
    :or {logical-cutpoint 0}}]
  (when-not (and (remote/valid-job? job) (remote/valid-assignment? assignment)
                 (= (:research-execution-job/root job)
                    (:research-execution-job/root assignment))
                 (or (nil? execution-work)
                     (and (observed-work/valid-work? execution-work)
                          (= job (:work/job execution-work))
                          (= assignment (:work/assignment execution-work)))))
    (throw (ex-info "Invalid job delivery lineage" {})))
  (let [response (post-edn! endpoint "/v1/jobs"
                            {:transport/kind :research/job-delivery
                             :job job :assignment assignment
                             :execution-work execution-work
                             :logical-cutpoint logical-cutpoint})
        body (:transport/response response)
        status (:transport/status body)]
    (cond
      (= :authentication-rejected status)
      (throw (ex-info "Runner rejected transport authentication"
                      {:transport/error :authentication-rejected}))

      (nil? (:http/status response))
      {:delivery/status :unreachable
       :reachability (control/build-reachability
                      {:job-root (:research-execution-job/root job)
                       :route-kind :job-delivery
                       :subject-root (:research-run-assignment/root assignment)
                       :source-root coordinator-root
                       :destination-root (:runner/reference assignment)
                       :declared-reachable? true :outcome :unreachable
                       :attempt-cutpoint logical-cutpoint})}

      (and (= 200 (:http/status response))
           (contains? #{:accepted :cancelled-before-start} status)
           (= (:research-execution-job/root job)
              (:research-execution-job/root body))
           (= (:research-run-assignment/root assignment)
              (:research-run-assignment/root body))
           (= (:runner/reference assignment) (:runner/reference body)))
      {:delivery/status status
       :reachability (control/build-reachability
                      {:job-root (:research-execution-job/root job)
                       :route-kind :job-delivery
                       :subject-root (:research-run-assignment/root assignment)
                       :source-root coordinator-root
                       :destination-root (:runner/reference assignment)
                       :declared-reachable? true :outcome :realized
                       :attempt-cutpoint logical-cutpoint})}

      :else
      (throw (ex-info "Runner rejected job delivery" {:response response})))))

(defn deliver-observed-result!
  "Return an already validated observed run plus its V1 assurance consequence.
   This does not accept a primitive, authorize consumption, or choose among
   competing results. It records a real transport delivery for the exact
   assignment and keeps repeated identical evidence idempotent."
  [{:keys [coordinator-root endpoint assignment observed-run-evidence
           run-assurance-evidence logical-cutpoint]
    :or {logical-cutpoint 0}}]
  (when-not (and (remote/valid-assignment? assignment)
                 (observed/valid-observed-run-evidence? observed-run-evidence)
                 (assurance/valid-run-assurance-evidence? run-assurance-evidence)
                 (= (:research-execution-job/root assignment)
                    (:research-execution-job/root observed-run-evidence))
                 (= (:research-run-assignment/root assignment)
                    (:research-run-assignment/root observed-run-evidence))
                 (= (:research-observed-run-evidence/root observed-run-evidence)
                    (:research-run-evidence/root run-assurance-evidence)))
    (throw (ex-info "Invalid observed result delivery lineage" {})))
  (let [response (post-edn! endpoint "/v1/results"
                            {:transport/kind :research/observed-result-delivery
                             :assignment assignment
                             :observed-run-evidence observed-run-evidence
                             :run-assurance-evidence run-assurance-evidence
                             :logical-cutpoint logical-cutpoint})
        body (:transport/response response)
        status (:transport/status body)]
    (cond
      (= :authentication-rejected status)
      (throw (ex-info "Coordinator rejected transport authentication"
                      {:transport/error :authentication-rejected}))

      (nil? (:http/status response))
      {:delivery/status :unreachable
       :reachability (control/build-reachability
                      {:job-root (:research-execution-job/root assignment)
                       :route-kind :result-delivery
                       :subject-root (:research-observed-run-evidence/root observed-run-evidence)
                       :source-root (:runner/reference observed-run-evidence)
                       :destination-root coordinator-root
                       :declared-reachable? true :outcome :unreachable
                       :attempt-cutpoint logical-cutpoint})}

      (and (= 200 (:http/status response))
           (contains? #{:accepted :already-recorded} status)
           (= (:research-execution-job/root assignment)
              (:research-execution-job/root body))
           (= (:research-run-assignment/root assignment)
              (:research-run-assignment/root body))
           (= (:research-observed-run-evidence/root observed-run-evidence)
              (:research-observed-run-evidence/root body))
           (= (:research-run-assurance-evidence/root run-assurance-evidence)
              (:research-run-assurance-evidence/root body))
           (= coordinator-root (:coordinator/root body)))
      {:delivery/status status
       :reachability (control/build-reachability
                      {:job-root (:research-execution-job/root assignment)
                       :route-kind :result-delivery
                       :subject-root (:research-observed-run-evidence/root observed-run-evidence)
                       :source-root (:runner/reference observed-run-evidence)
                       :destination-root coordinator-root
                       :declared-reachable? true :outcome :realized
                       :attempt-cutpoint logical-cutpoint})}

      :else
      (throw (ex-info "Coordinator rejected observed result delivery" {:response response})))))

(defn deliver-cancellation!
  "Publish a canonical cancellation to a runner and create separate delivery
   and runner-observation artifacts only after the runner actually responds."
  [{:keys [coordinator-root endpoint cancellation runner-participation
           assignment logical-cutpoint last-work-unit-started-root
           last-work-unit-completed-root status]
    :or {logical-cutpoint 0 status :observed}}]
  (when-not (and (:valid? (participation/validate-participation runner-participation))
                 (= (:runner-participation/root runner-participation)
                    (:runner-participation/root assignment))
                 (= (:research-execution-job/root cancellation)
                    (:research-execution-job/root assignment)))
    (throw (ex-info "Invalid cancellation delivery lineage" {})))
  (let [response (post-edn! endpoint "/v1/cancellations"
                            {:transport/kind :research/cancellation-delivery
                             :cancellation cancellation
                             :logical-cutpoint logical-cutpoint})]
    (if (nil? (:http/status response))
      {:delivery/status :unreachable
       :reachability (control/build-reachability
                      {:job-root (:research-execution-job/root cancellation)
                       :route-kind :cancellation-delivery
                       :subject-root (:research-job-cancellation/root cancellation)
                       :source-root coordinator-root
                       :destination-root (:runner/reference runner-participation)
                       :declared-reachable? true :outcome :unreachable
                       :attempt-cutpoint logical-cutpoint})}
      (let [body (:transport/response response)]
        (when (= :authentication-rejected (:transport/status body))
          (throw (ex-info "Runner rejected transport authentication"
                          {:transport/error :authentication-rejected})))
        (when-not (and (= 200 (:http/status response))
                       (= :observed (:transport/status body))
                       (= (:research-execution-job/root cancellation)
                          (:research-execution-job/root body))
                       (= (:research-job-cancellation/root cancellation)
                          (:research-job-cancellation/root body))
                       (= (:runner/reference runner-participation)
                          (:runner/reference body)))
          (throw (ex-info "Runner rejected cancellation delivery" {:response response})))
        (let [reachability (control/build-reachability
                            {:job-root (:research-execution-job/root cancellation)
                             :route-kind :cancellation-delivery
                             :subject-root (:research-job-cancellation/root cancellation)
                             :source-root coordinator-root
                             :destination-root (:runner/reference runner-participation)
                             :declared-reachable? true :outcome :realized
                             :attempt-cutpoint logical-cutpoint})]
          {:delivery/status :observed
           :reachability reachability
           :observation (control/build-control-observation
                         {:job-root (:research-execution-job/root cancellation)
                          :assignment-root (:research-run-assignment/root assignment)
                          :runner-participation-root
                          (:runner-participation/root runner-participation)
                          :runner-reference (:runner/reference runner-participation)
                          :highest-observed-epoch (:control/epoch cancellation)
                          :cancellation-root (:research-job-cancellation/root cancellation)
                          :observation-cutpoint logical-cutpoint
                          :last-work-unit-started-root last-work-unit-started-root
                          :last-work-unit-completed-root last-work-unit-completed-root
                          :status status
                          :reachability reachability})})))))
