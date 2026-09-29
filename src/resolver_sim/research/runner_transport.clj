(ns resolver-sim.research.runner-transport
  "Research-only authenticated HTTP transport for independent runner agents.

   This is deliberately a small lab adapter, not a production worker network:
   it provides authenticated message delivery, real local/TCP reachability, and
   canonical evidence through the existing execution-control artifacts. The
   shared transport secret, socket address, process identity, and HTTP logs are
   runtime-only. They never define a job or result root."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.research.accelerated-accounting :as accounting]
            [resolver-sim.research.accounting-execution-work :as accounting-work]
            [resolver-sim.research.execution-control :as control]
            [resolver-sim.research.execution-assurance :as assurance]
            [resolver-sim.research.observed-assurance :as observed]
            [resolver-sim.research.observed-execution-work :as observed-work]
            [resolver-sim.research.remote-execution :as remote]
            [resolver-sim.research.runner-forensics :as runner-forensics]
            [resolver-sim.research.runner-participation :as participation])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.nio.file AtomicMoveNotSupportedException Files StandardCopyOption]
           [java.security MessageDigest]
           [java.time Duration]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

(def ^:private mac-header "X-PRF-Research-MAC")
(def ^:private content-type "application/edn; charset=utf-8")
(def ^:private client (HttpClient/newHttpClient))
(def ^:private runner-state-write-lock (Object.))
(def ^:private coordinator-state-write-lock (Object.))
(declare deliver-observed-result! execute-delivered-work! retry-undelivered-results! runner-state
         coordinator-state)

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

(defn- observed-work-for [work]
  (cond
    (observed-work/valid-work? work) work
    (accounting-work/valid-work? work) (:accounting/observed-execution-work work)
    :else nil))

(defn- work-root [work]
  (cond
    (observed-work/valid-work? work) (:research-observed-execution-work/root work)
    (accounting-work/valid-work? work) (:research-accounting-execution-work/root work)))

(def ^:private runner-journal-schema "research-runner-runtime-journal.v1")
(def ^:private initial-runner-state
  {:jobs {} :assignments {} :work-items {} :work-states {}
   :cancellations {} :completed-work {} :forensic-evidence-nodes {}})

;; This is deliberately the same small recovery boundary as the runner
;; journal: canonical job/assignment/work constituents plus transport history.
;; It is not a coordinator-owned semantic result store or a second evidence
;; architecture.
(def ^:private coordinator-journal-schema "research-coordinator-runtime-journal.v1")
(def ^:private initial-coordinator-state
  {:assignments {} :dispatch-intents {} :dispatch-history {}
   :observed-results {} :accounting-contexts {} :accounting-partials {}})

(defn- valid-runtime-state? [state]
  (and (map? state)
       (= (set (keys initial-runner-state)) (set (keys state)))
       (every? map? (vals state))))

(defn- valid-coordinator-runtime-state? [state]
  (and (map? state)
       (= (set (keys initial-coordinator-state)) (set (keys state)))
       (every? map? (vals state))))

(defn- journal-body [runner state]
  {:artifact/schema runner-journal-schema
   :runner-participation/root
   (:runner-participation/root (:runner-participation runner))
   :coordinator/root (:transport/coordinator-root runner)
   :runner-runtime/state state})

(defn- read-runtime-state [path runner]
  (if-not path
    initial-runner-state
    (let [file (io/file path)]
      (if-not (.exists file)
      initial-runner-state
      (let [journal (edn/read-string (slurp file))]
        (when-not (and (= runner-journal-schema (:artifact/schema journal))
                       (= (:runner-participation/root (:runner-participation runner))
                          (:runner-participation/root journal))
                       (= (:transport/coordinator-root runner) (:coordinator/root journal))
                       (valid-runtime-state? (:runner-runtime/state journal)))
          (throw (ex-info "Invalid or foreign persisted runner state"
                          {:state-path path})))
        (:runner-runtime/state journal))))))

(defn- write-runtime-state! [runner state]
  (when-let [path (:transport/state-path runner)]
    (let [target (.toPath (io/file path))
          parent (or (.getParent target) (.toPath (io/file ".")))]
      (Files/createDirectories parent (make-array java.nio.file.attribute.FileAttribute 0))
      (let [temporary (Files/createTempFile parent ".research-runner-state-" ".edn"
                                             (make-array java.nio.file.attribute.FileAttribute 0))]
        (try
          (Files/writeString temporary (pr-str (journal-body runner state)) StandardCharsets/UTF_8
                             (make-array java.nio.file.OpenOption 0))
          (try
            (Files/move temporary target
                        (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE
                                                         StandardCopyOption/REPLACE_EXISTING]))
            (catch AtomicMoveNotSupportedException _
              (Files/move temporary target
                          (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))))
          (finally
            (Files/deleteIfExists temporary)))))))

(defn- coordinator-journal-body [coordinator state]
  {:artifact/schema coordinator-journal-schema
   :coordinator/root (:coordinator/root coordinator)
   :coordinator-runtime/state state})

(defn- read-coordinator-runtime-state [path coordinator assignments]
  (let [fresh (assoc initial-coordinator-state :assignments
                     (into {} (map (juxt :research-run-assignment/root identity) assignments)))]
    (if-not path fresh
      (let [file (io/file path)]
        (if-not (.exists file) fresh
          (let [journal (edn/read-string (slurp file))
                state (:coordinator-runtime/state journal)]
            (when-not (and (= coordinator-journal-schema (:artifact/schema journal))
                           (= (:coordinator/root coordinator) (:coordinator/root journal))
                           (valid-coordinator-runtime-state? state))
              (throw (ex-info "Invalid or foreign persisted coordinator state"
                              {:state-path path})))
            ;; A recovery may repeat declared assignments, but may never swap a
            ;; persisted assignment under an existing root.
            (doseq [assignment assignments]
              (let [root (:research-run-assignment/root assignment)
                    old (get-in state [:assignments root])]
                (when (and old (not= old assignment))
                  (throw (ex-info "Coordinator assignment substitution" {:assignment/root root})))))
            (update state :assignments merge (:assignments fresh))))))))

(defn- write-coordinator-runtime-state! [coordinator state]
  (when-let [path (:transport/state-path coordinator)]
    (let [target (.toPath (io/file path))
          parent (or (.getParent target) (.toPath (io/file ".")))]
      (Files/createDirectories parent (make-array java.nio.file.attribute.FileAttribute 0))
      (let [temporary (Files/createTempFile parent ".research-coordinator-state-" ".edn"
                                             (make-array java.nio.file.attribute.FileAttribute 0))]
        (try
          (Files/writeString temporary (pr-str (coordinator-journal-body coordinator state))
                             StandardCharsets/UTF_8 (make-array java.nio.file.OpenOption 0))
          (try
            (Files/move temporary target (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE
                                                                           StandardCopyOption/REPLACE_EXISTING]))
            (catch AtomicMoveNotSupportedException _
              (Files/move temporary target (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))))
          (finally (Files/deleteIfExists temporary)))))))

(defn- update-runtime-state!
  "Persist an all-or-nothing runtime transition. This journal is not a new
   semantic artifact: it preserves exact already-rooted inputs/evidence across
   a runner restart."
  [runner f & args]
  (locking runner-state-write-lock
    (let [state (apply swap! (:transport/state runner) f args)]
      (write-runtime-state! runner state)
      state)))

(defn- update-coordinator-state! [coordinator f & args]
  (locking coordinator-state-write-lock
    (let [state (apply swap! (:transport/state coordinator) f args)]
      (write-coordinator-runtime-state! coordinator state)
      state)))

(defn- append-work-event [state work-root status cutpoint event]
  (let [old (get-in state [:work-states work-root] {:work/attempt-sequence 0
                                                     :work/history []})
        attempt (if (= event :execution-started)
                  (inc (:work/attempt-sequence old))
                  (:work/attempt-sequence old))
        event-record {:work/event event
                      :work/status status
                      :work/logical-cutpoint cutpoint
                      :work/attempt-sequence attempt}
        next (-> old
                 (assoc :work/status status :work/attempt-sequence attempt)
                 (update :work/history conj event-record))]
    (assoc-in state [:work-states work-root] next)))

(defn- acknowledge-result? [delivery]
  (contains? #{:accepted :already-recorded} (:delivery/status delivery)))

(defn- record-forensic-event
  [state runner {:keys [event-key subject-root subject-kind causal-parent-roots
                         parent-nodes observed-root consequence?]}]
  (runner-forensics/record-node
   state
   (runner-forensics/build-event-node
    {:event-key event-key :subject-root subject-root :subject-kind subject-kind
     :actor-root (:runner/reference (:runner-participation runner))
     :causal-parent-roots causal-parent-roots :parent-nodes parent-nodes
     :observed-root observed-root :consequence? consequence?})))

(defn forensic-reconstruction
  "Validate the persisted generic evidence-node DAG and runner causal rules.
   The recovery journal remains a storage mechanism; this is the authoritative
   forensic interpretation path."
  ([runner]
   (runner-forensics/validate-runner-reconstruction (runner-state runner)))
  ([runner coordinator]
   (runner-forensics/validate-runner-reconstruction
    (runner-state runner) (coordinator-state coordinator))))

(defn- recover-interrupted-work!
  "A process lost while marked executing has no persisted completed evidence.
   The recovery rule is deliberately conservative: mark it interrupted and
   permit a *fresh isolated* retry only after a subsequent valid delivery.
   Completed evidence is never retried as execution."
  [runner logical-cutpoint]
  (update-runtime-state!
   runner
   (fn [state]
     (reduce-kv
      (fn [state work-root work-state]
        (if (= :executing (:work/status work-state))
          (let [event {:work/event :interrupted-by-restart
                       :work/status :interrupted
                       :work/logical-cutpoint logical-cutpoint
                       :work/attempt-sequence (:work/attempt-sequence work-state)
                       :work/recovery-rule :fresh-isolated-retry-on-redelivery}]
            (let [state (assoc-in state [:work-states work-root]
                                  (-> work-state
                                      (assoc :work/status :interrupted)
                                      (update :work/history conj event)))
                  work (get-in state [:work-items work-root])
                  observed-work (observed-work-for work)
                  assignment (:work/assignment observed-work)
                  start-node (runner-forensics/latest-node-hash
                              state :runner.forensic/execution-started work-root)]
              (record-forensic-event
               state runner
               {:event-key :runner.forensic/restart-interrupted
                :subject-root work-root :subject-kind :research-runner/work
                :causal-parent-roots [(:research-execution-job/root (:work/job observed-work))
                                       (:research-run-assignment/root assignment) work-root]
                :parent-nodes (cond-> [] start-node (conj start-node))
                :observed-root work-root})))
          state))
      state (:work-states state)))))

(defn- valid-work-delivery? [runner job assignment work]
  (let [observed-work (observed-work-for work)]
    (and observed-work
         (= job (:work/job observed-work))
         (= assignment (:work/assignment observed-work))
         (= (:runner-participation runner) (:work/runner-participation observed-work)))))

(defn- valid-accounting-result-delivery?
  [context partial run-assurance]
  (when context
    (let [{:keys [request partition-plan work-unit]} context]
      (and (map? context) (accounting/valid-request? request)
           (accounting/valid-partition-plan? request partition-plan)
           (some #(= (:accounting-work-unit/root work-unit)
                     (:accounting-work-unit/root %))
                 (:partition/work-units partition-plan))
           (accounting/valid-partial-result? request work-unit partial)
           (= (:research-run-assurance-evidence/root partial)
              (:research-run-assurance-evidence/root run-assurance))))))

(defn- valid-observed-result-delivery?
  "A result return is accepted only as observed, already-assured execution
   evidence.  The predecessor declaration/simulation run-evidence V1 is not a
   substitute for an observed run here."
  [state {:keys [assignment observed-run-evidence run-assurance-evidence
                 accounting-context accounting-partial]}]
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
             (:runner/reference run-assurance-evidence))
          (if (or accounting-context accounting-partial)
            (and accounting-context accounting-partial
                 (valid-accounting-result-delivery? accounting-context accounting-partial
                                                   run-assurance-evidence))
            true))))

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
                (let [{:keys [job assignment logical-cutpoint execution-work execute?]} message
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
                      (let [root (when execution-work (work-root execution-work))
                            state (update-runtime-state!
                                   runner
                                   (fn [state]
                                     (let [state (-> state
                                                     (assoc-in [:jobs job-root] job)
                                                     (assoc-in [:assignments
                                                                (:research-run-assignment/root assignment)]
                                                               assignment))]
                                       (if-not execution-work
                                         state
                                         (let [known (get-in state [:work-states root])]
                                           (let [state (if known
                                                         state
                                                         (-> state
                                                             (assoc-in [:work-items root] execution-work)
                                                             (append-work-event root :accepted logical-cutpoint
                                                                                :delivery-accepted)))]
                                             (record-forensic-event
                                              state runner
                                              {:event-key :runner.forensic/delivery-accepted
                                               :subject-root (:research-run-assignment/root assignment)
                                               :subject-kind :research-runner/assignment-delivery
                                               :causal-parent-roots [job-root
                                                                      (:research-run-assignment/root assignment)
                                                                      root]
                                               :parent-nodes [] :observed-root root})))))))
                            work-status (get-in state [:work-states root :work/status])
                            execution-result (when (and (not= false execute?) execution-work
                                                       (contains? #{:accepted :interrupted} work-status))
                                               (execute-delivered-work! runner execution-work logical-cutpoint))]
                        (response! exchange 200
                                 {:transport/status (if (and execution-work
                                                         (contains? #{:completed :result-acknowledged}
                                                                    work-status))
                                                      :already-completed :accepted)
                                  :research-execution-job/root job-root
                                  :research-run-assignment/root
                                  (:research-run-assignment/root assignment)
                                  :runner/reference
                                  (:runner/reference (:runner-participation runner))
                                  :transport/execution-status (cond
                                                                execution-result :complete
                                                                (and execution-work (= false execute?)) :deferred
                                                                :else nil)
                                  :transport/logical-cutpoint logical-cutpoint})))))))
            (catch clojure.lang.ExceptionInfo e
              (let [cause (or (:cause (ex-data e)) :task-execution-failed)]
                (response! exchange 422
                           {:transport/status :execution-failed
                            :execution/failure-cause cause})))
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
              (update-runtime-state!
               runner
               (fn [state]
                 (let [assignment (assignment-for-job state job-root)
                       state (record-forensic-event
                              state runner
                              {:event-key :runner.forensic/cancellation-observed
                               :subject-root (:research-job-cancellation/root cancellation)
                               :subject-kind :research-runner/cancellation
                               :causal-parent-roots (cond-> [job-root
                                                              (:research-job-cancellation/root cancellation)]
                                                       assignment
                                                       (conj (:research-run-assignment/root assignment)))
                               :parent-nodes []
                               :observed-root (:research-job-cancellation/root cancellation)})]
                   (reduce-kv
                  (fn [state work-root work]
                    (let [work-job (:research-execution-job/root
                                    (:work/job (observed-work-for work)))
                          status (get-in state [:work-states work-root :work/status])]
                      (if (and (= job-root work-job)
                               (not (contains? #{:completed :result-acknowledged} status)))
                        (append-work-event state work-root :cancelled logical-cutpoint
                                           :cancellation-observed)
                        state)))
                  (assoc-in state [:cancellations job-root] cancellation)
                  (:work-items state)))))
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
                               accounting-context accounting-partial logical-cutpoint]} message
                      observed-root (:research-observed-run-evidence/root observed-run-evidence)
                      existing (get-in @(:transport/state coordinator)
                                       [:observed-results observed-root])]
                  (update-coordinator-state! coordinator
                         (fn [state]
                           (let [state (if existing state
                                           (assoc-in state [:observed-results observed-root]
                                                     {:assignment assignment
                                                      :observed-run-evidence observed-run-evidence
                                                      :run-assurance-evidence run-assurance-evidence}))]
                             (if accounting-context
                               (let [plan-root (get-in accounting-context
                                                       [:partition-plan :accounting-partition-plan/root])
                                     plan-context (select-keys accounting-context
                                                               [:request :partition-plan])
                                     prior-context (get-in state [:accounting-contexts plan-root])]
                                 (when (and prior-context (not= prior-context plan-context))
                                   (throw (ex-info "Accounting context substitution" {})))
                                 (-> state
                                     (assoc-in [:accounting-contexts plan-root] plan-context)
                                     (update-in [:accounting-partials plan-root]
                                                (fnil accounting/ingest-partial {}) accounting-partial)))
                               state))))
                  (response! exchange 200
                             {:transport/status (if existing :already-recorded :accepted)
                              :research-execution-job/root
                              (:research-execution-job/root observed-run-evidence)
                              :research-run-assignment/root
                              (:research-run-assignment/root assignment)
                              :research-observed-run-evidence/root observed-root
                               :research-run-assurance-evidence/root
                               (:research-run-assurance-evidence/root run-assurance-evidence)
                               :accounting-partial-result/root
                               (:accounting-partial-result/root accounting-partial)
                              :coordinator/root (:coordinator/root coordinator)
                              :transport/logical-cutpoint logical-cutpoint}))))
            (catch clojure.lang.ExceptionInfo e
              (response! exchange 422
                         {:transport/status :invalid-observed-result
                          :transport/failure-cause
                          (or (:cause (ex-data e)) :result-ingestion-failed)}))
            (catch Exception _
              (response! exchange 422 {:transport/status :invalid-observed-result
                                        :transport/failure-cause :result-ingestion-failed}))))))))

(defn start-runner!
  "Start one independent, authenticated lab-runner endpoint.

   `:runner-participation` is canonical; `:auth-secret` and binding address are
   runtime configuration. Use a non-loopback bind address on separate hosts.
   The returned endpoint URL is intentionally not part of the runner's root."
  [{:keys [runner-participation auth-secret bind-address port advertise-url
           coordinator-root coordinator-endpoint state-path recovery-logical-cutpoint
           before-safe-cutpoint before-result-delivery]
    :or {bind-address "127.0.0.1" port 0 recovery-logical-cutpoint 0}}]
  (when-not (and (:valid? (participation/validate-participation runner-participation))
                 (string? auth-secret) (not (empty? auth-secret))
                 (string? bind-address) (integer? port) (<= 0 port 65535)
                 (or (nil? advertise-url) (string? advertise-url))
                 (or (nil? state-path) (string? state-path))
                 (integer? recovery-logical-cutpoint) (not (neg? recovery-logical-cutpoint))
                 (or (and (nil? coordinator-root) (nil? coordinator-endpoint))
                     (and (hash-ref/valid-sha256-ref? coordinator-root)
                          (map? coordinator-endpoint))))
    (throw (ex-info "Invalid research runner transport configuration" {})))
  (let [bootstrap {:runner-participation runner-participation
                   :transport/auth-secret auth-secret
                   :transport/coordinator-root coordinator-root
                   :transport/coordinator-endpoint coordinator-endpoint
                   :transport/before-safe-cutpoint before-safe-cutpoint
                   :transport/before-result-delivery before-result-delivery
                   :transport/state-path state-path}
        state (atom (read-runtime-state state-path bootstrap))
        server (HttpServer/create (InetSocketAddress. bind-address (int port)) 0)
        runner (assoc bootstrap :transport/state state :transport/server server)]
    (.createContext server "/v1/jobs" (job-handler runner))
    (.createContext server "/v1/cancellations" (cancellation-handler runner))
    (.setExecutor server nil)
    (.start server)
    (let [runner (assoc runner :transport/base-url
                        (or advertise-url
                            (str "http://" bind-address ":" (.getPort (.getAddress server)))))]
      (recover-interrupted-work! runner recovery-logical-cutpoint)
      ;; A completed local run is terminal computation. Recovery only retries
      ;; delivery of its exact retained evidence; it never calls execute-work!.
      (when coordinator-endpoint
        (retry-undelivered-results! runner coordinator-endpoint recovery-logical-cutpoint))
      (write-runtime-state! runner (runner-state runner))
      runner)))

(defn stop-runner! [runner]
  (when-let [server (:transport/server runner)] (.stop ^HttpServer server 0))
  nil)

(defn runner-state [runner] @(:transport/state runner))

(defn start-coordinator!
  "Start the research-only coordinator result sink. `assignments` are already
   canonical scheduler decisions; the server only records observed evidence
   bound to one of them. Its address and secret are runtime-only."
  [{:keys [coordinator-root assignments auth-secret bind-address port advertise-url state-path]
    :or {bind-address "127.0.0.1" port 0}}]
  (when-not (and (hash-ref/valid-sha256-ref? coordinator-root)
                 (vector? assignments) (every? remote/valid-assignment? assignments)
                 (= (count assignments) (count (set (map :research-run-assignment/root assignments))))
                 (string? auth-secret) (not (empty? auth-secret))
                 (string? bind-address) (integer? port) (<= 0 port 65535)
                 (or (nil? advertise-url) (string? advertise-url))
                 (or (nil? state-path) (string? state-path)))
    (throw (ex-info "Invalid research coordinator transport configuration" {})))
  (let [bootstrap {:coordinator/root coordinator-root :transport/state-path state-path}
        state (atom (read-coordinator-runtime-state state-path bootstrap assignments))
        server (HttpServer/create (InetSocketAddress. bind-address (int port)) 0)
        coordinator {:coordinator/root coordinator-root
                     :transport/auth-secret auth-secret
                     :transport/state-path state-path
                     :transport/state state
                     :transport/server server}]
    (.createContext server "/v1/results" (coordinator-result-handler coordinator))
    (.setExecutor server nil)
    (.start server)
    (let [coordinator (assoc coordinator :transport/base-url
                             (or advertise-url
                                 (str "http://" bind-address ":" (.getPort (.getAddress server)))))]
      (write-coordinator-runtime-state! coordinator (coordinator-state coordinator))
      coordinator)))

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
  "Execute an already accepted rooted observed or accounting-partition bundle and return its
   existing observed evidence to the configured coordinator. This is a
   cooperative safe cutpoint: callers invoke it only before starting another
   work unit; no asynchronous process interruption is attempted."
  [runner work logical-cutpoint]
  (when-not (or (observed-work/valid-work? work) (accounting-work/valid-work? work))
    (throw (ex-info "Invalid dispatched execution work" {})))
  (let [root (work-root work)
        observed-work (observed-work-for work)
        assignment (:work/assignment observed-work)
        job (:work/job observed-work)
        job-root (:research-execution-job/root job)
        ;; Runtime-only test/adapter hook. It models a cooperative observation
        ;; point; it is intentionally absent from every rooted work artifact.
        before-safe-cutpoint (:transport/before-safe-cutpoint runner)
        _ (when before-safe-cutpoint (before-safe-cutpoint runner work logical-cutpoint))
        cancelled? (contains? (:cancellations (runner-state runner)) job-root)
        _ (when cancelled?
            (update-runtime-state! runner
                                   #(append-work-event % root :cancelled logical-cutpoint
                                                       :cancellation-observed)))
        _ (when cancelled?
            (throw (ex-info "Cancellation observed at cooperative safe cutpoint"
                            {:cause :cancelled-at-safe-cutpoint
                             :research-execution-job/root job-root})))
        _ (update-runtime-state!
           runner
           (fn [state]
             (let [state (append-work-event state root :executing logical-cutpoint
                                            :execution-started)
                   delivery-node (runner-forensics/latest-node-hash
                                  state :runner.forensic/delivery-accepted
                                  (:research-run-assignment/root assignment))]
               (record-forensic-event
                state runner
                {:event-key :runner.forensic/execution-started
                 :subject-root root :subject-kind :research-runner/work
                 :causal-parent-roots [(:research-execution-job/root job)
                                        (:research-run-assignment/root assignment) root]
                 :parent-nodes (cond-> [] delivery-node (conj delivery-node))
                 :observed-root root}))))
        coordinator-root (:transport/coordinator-root runner)
        endpoint (:transport/coordinator-endpoint runner)]
    (when-not (and (hash-ref/valid-sha256-ref? coordinator-root)
                   (map? endpoint))
      (throw (ex-info "Runner lacks runtime coordinator return endpoint" {})))
    (let [accounting? (accounting-work/valid-work? work)
          executed (if accounting?
                     (accounting-work/execute-work! work)
                     (observed-work/execute-work! work))
          observed-run-evidence (:observed-run-evidence executed)
          run-assurance-evidence (:run-assurance-evidence executed)
          accounting-context (when accounting?
                               {:request (:accounting/request work)
                                :partition-plan (:accounting/partition-plan work)
                                :work-unit (:accounting/work-unit work)})
          accounting-partial (:accounting-partial executed)
          ;; An Anvil action is atomic in this adapter. A cancellation observed
          ;; after it completes cannot erase the observed result, but it may
          ;; stop the next stage (coordinator delivery).
          _ (when-let [hook (:transport/before-result-delivery runner)]
              (hook runner work logical-cutpoint))
          cancelled-after-execution? (contains? (:cancellations (runner-state runner)) job-root)
          returned (if cancelled-after-execution?
                     {:delivery/status :cancelled-before-delivery
                      :delivery/reason :cancellation-observed-after-atomic-execution}
                     (deliver-observed-result!
                    {:coordinator-root coordinator-root :endpoint endpoint
                     :assignment assignment
                     :observed-run-evidence observed-run-evidence
                     :run-assurance-evidence run-assurance-evidence
                     :accounting-context accounting-context
                     :accounting-partial accounting-partial
                     :logical-cutpoint logical-cutpoint}))]
      (update-runtime-state!
       runner
       (fn [state]
         (let [status (if (acknowledge-result? returned)
                        :result-acknowledged :completed)
               state (assoc-in state [:completed-work root]
                               {:observed-run-evidence observed-run-evidence
                                :run-assurance-evidence run-assurance-evidence
                                :accounting-context accounting-context
                                :accounting-partial accounting-partial
                                :result-delivery returned
                                :result-deliveries [returned]})]
           (let [state (append-work-event state root status logical-cutpoint :execution-completed)
                 start-node (runner-forensics/latest-node-hash
                             state :runner.forensic/execution-started root)
                 complete-node (runner-forensics/build-event-node
                                {:event-key :runner.forensic/execution-completed
                                 :subject-root (:research-observed-run-evidence/root observed-run-evidence)
                                 :subject-kind :research-runner/observed-run
                                 :actor-root (:runner/reference (:runner-participation runner))
                                 :causal-parent-roots [root
                                                        (:research-observed-run-evidence/root observed-run-evidence)
                                                        (:research-run-assurance-evidence/root run-assurance-evidence)]
                                 :parent-nodes (cond-> [] start-node (conj start-node))
                                 :observed-root (:semantic-result/root observed-run-evidence)})]
             (let [state (runner-forensics/record-node state complete-node)
                   post-node (when-let [reachability-root
                                        (get-in returned [:reachability
                                                          :research-realized-reachability/root])]
                               (runner-forensics/build-event-node
                              {:event-key :runner.forensic/result-posted
                               :subject-root (:research-observed-run-evidence/root observed-run-evidence)
                               :subject-kind :research-runner/result-delivery
                               :actor-root (:runner/reference (:runner-participation runner))
                               :causal-parent-roots [(:research-run-assignment/root assignment)
                                                      (:research-run-assurance-evidence/root run-assurance-evidence)
                                                      reachability-root]
                               :parent-nodes [(:node-hash complete-node)]
                               :observed-root (:research-run-assurance-evidence/root run-assurance-evidence)}))
                   state (cond-> state post-node (runner-forensics/record-node post-node))]
               (if (and post-node (acknowledge-result? returned))
                 (runner-forensics/record-node
                  state
                  (runner-forensics/build-event-node
                   {:event-key :runner.forensic/coordinator-acknowledged
                    :subject-root (:research-observed-run-evidence/root observed-run-evidence)
                    :subject-kind :research-runner/coordinator-acknowledgement
                    :actor-root (:runner/reference (:runner-participation runner))
                    :causal-parent-roots [(:research-run-assurance-evidence/root run-assurance-evidence)
                                           (get-in returned [:reachability
                                                            :research-realized-reachability/root])]
                    :parent-nodes [(:node-hash post-node)]
                    :observed-root (:research-run-assurance-evidence/root run-assurance-evidence)
                    :consequence? true}))
                 state))))))
      {:execution executed :accounting-partial accounting-partial
       :result-delivery returned})))

(defn retry-undelivered-results!
  "Retry coordinator delivery for completed work whose earlier result post was
   unreachable. The original semantic and forensic artifacts are reused; the
   coordinator's content-addressed ingestion is idempotent. Every delivery
   attempt is retained in order, while `:result-delivery` remains the latest
   attempt for compatibility with existing callers."
  ([runner logical-cutpoint]
   (retry-undelivered-results! runner (:transport/coordinator-endpoint runner)
                               logical-cutpoint))
  ([runner endpoint logical-cutpoint]
   (when-not (and (map? endpoint)
                  (integer? logical-cutpoint) (not (neg? logical-cutpoint)))
     (throw (ex-info "Invalid result retry parameters" {})))
   (let [pending (->> (:completed-work (runner-state runner))
                      (filter (fn [[_ completed]]
                                (= :unreachable
                                   (get-in completed [:result-delivery :delivery/status]))))
                      (sort-by key))]
     (mapv
      (fn [[work-root completed]]
        (let [assignment (get-in completed [:observed-run-evidence
                                            :proof/source-artifacts :assignment])
              delivered (deliver-observed-result!
                         {:coordinator-root (:transport/coordinator-root runner)
                          :endpoint endpoint :assignment assignment
                          :observed-run-evidence (:observed-run-evidence completed)
                          :run-assurance-evidence (:run-assurance-evidence completed)
                          :accounting-context (:accounting-context completed)
                          :accounting-partial (:accounting-partial completed)
                          :logical-cutpoint logical-cutpoint})]
          (update-runtime-state!
           runner
           (fn [state]
             (let [status (if (acknowledge-result? delivered)
                            :result-acknowledged :completed)
                   state (-> state
                             (assoc-in [:completed-work work-root :result-delivery] delivered)
                             (update-in [:completed-work work-root :result-deliveries]
                                        (fnil conj []) delivered))
                   state (append-work-event state work-root status logical-cutpoint
                                            :result-redelivery)
                   observed-root (:research-observed-run-evidence/root
                                  (:observed-run-evidence completed))
                   assurance-root (:research-run-assurance-evidence/root
                                   (:run-assurance-evidence completed))
                   previous-post (or (runner-forensics/latest-node-hash
                                      state :runner.forensic/result-redelivered observed-root)
                                     (runner-forensics/latest-node-hash
                                      state :runner.forensic/result-posted observed-root))
                   completion-node (runner-forensics/latest-node-hash
                                    state :runner.forensic/execution-completed observed-root)
                   post-node (runner-forensics/build-event-node
                              {:event-key :runner.forensic/result-redelivered
                               :subject-root observed-root
                               :subject-kind :research-runner/result-delivery
                               :actor-root (:runner/reference (:runner-participation runner))
                               :causal-parent-roots [(:research-run-assignment/root assignment)
                                                      assurance-root
                                                      (get-in delivered [:reachability
                                                                         :research-realized-reachability/root])]
                               :parent-nodes (cond-> [] completion-node (conj completion-node)
                                                     previous-post (conj previous-post))
                               :observed-root assurance-root})
                   state (runner-forensics/record-node state post-node)]
               (if (acknowledge-result? delivered)
                 (runner-forensics/record-node
                  state
                  (runner-forensics/build-event-node
                   {:event-key :runner.forensic/coordinator-acknowledged
                    :subject-root observed-root
                    :subject-kind :research-runner/coordinator-acknowledgement
                    :actor-root (:runner/reference (:runner-participation runner))
                    :causal-parent-roots [assurance-root
                                           (get-in delivered [:reachability
                                                              :research-realized-reachability/root])]
                    :parent-nodes [(:node-hash post-node)]
                    :observed-root assurance-root :consequence? true}))
                 state))))
          {:research-observed-execution-work/root work-root :delivery delivered}))
      pending))))

(defn deliver-job!
  "Deliver a rooted job/assignment over HTTP and produce existing canonical
   realized-reachability evidence. Authentication rejection is a transport
   boundary failure, not a false claim that the route was unreachable."
  [{:keys [coordinator-root endpoint job assignment execution-work logical-cutpoint execute?]
    :or {logical-cutpoint 0 execute? true}}]
  (when-not (and (remote/valid-job? job) (remote/valid-assignment? assignment)
                 (= (:research-execution-job/root job)
                    (:research-execution-job/root assignment))
                 (or (nil? execution-work)
                     (valid-work-delivery? {:runner-participation
                                            (:work/runner-participation
                                             (observed-work-for execution-work))}
                                           job assignment execution-work)))
    (throw (ex-info "Invalid job delivery lineage" {})))
  (let [response (post-edn! endpoint "/v1/jobs"
                            {:transport/kind :research/job-delivery
                             :job job :assignment assignment
                             :execution-work execution-work
                             :execute? execute?
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
           (contains? #{:accepted :cancelled-before-start :already-completed} status)
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

(defn dispatch-job!
  "Persist coordinator dispatch intent before attempting delivery. Repeated
   transport attempts are at-least-once; the immutable assignment/work bundle
   makes later runner application exactly-once semantically. This is the
   qualification seam for coordinator restart/recovery, not a job queue."
  [coordinator {:keys [endpoint job assignment execution-work logical-cutpoint execute?]
                :or {logical-cutpoint 0 execute? true} :as dispatch}]
  (when-not (and (remote/valid-job? job) (remote/valid-assignment? assignment))
    (throw (ex-info "Invalid coordinator dispatch" {})))
  (let [assignment-root (:research-run-assignment/root assignment)
        intent (assoc (select-keys dispatch [:job :assignment :execution-work])
                      :execute? execute?)]
    (update-coordinator-state!
     coordinator
     (fn [state]
       (let [old (get-in state [:dispatch-intents assignment-root])]
         (when (and old (not= (select-keys old [:job :assignment :execution-work :execute?]) intent))
           (throw (ex-info "Coordinator dispatch substitution" {:assignment/root assignment-root})))
         (-> state
             (assoc-in [:assignments assignment-root] assignment)
             (assoc-in [:dispatch-intents assignment-root]
                       (or old (assoc intent :dispatch/status :pending :dispatch/history [])))))))
    (let [delivery (deliver-job! {:coordinator-root (:coordinator/root coordinator)
                                  :endpoint endpoint :job job :assignment assignment
                                  :execution-work execution-work :logical-cutpoint logical-cutpoint
                                  :execute? execute?})]
      (update-coordinator-state!
       coordinator
       (fn [state]
         (-> state
             (assoc-in [:dispatch-intents assignment-root :dispatch/status]
                       (if (= :unreachable (:delivery/status delivery)) :pending :delivered))
             (update-in [:dispatch-intents assignment-root :dispatch/history]
                        (fnil conj []) {:dispatch/logical-cutpoint logical-cutpoint
                                        :dispatch/delivery-status (:delivery/status delivery)
                                        :dispatch/reachability-root
                                        (get-in delivery [:reachability
                                                          :research-realized-reachability/root])}))))
      delivery)))

(defn retry-pending-dispatches!
  "Re-attempt persisted dispatch intents after a runner returns. It never
   changes the assignment or work bundle, and terminal runner state makes a
   replay `:already-completed` rather than a second semantic execution."
  [coordinator endpoint-for-assignment logical-cutpoint]
  (mapv (fn [[_ intent]]
          (dispatch-job! coordinator
                         (assoc intent :endpoint (endpoint-for-assignment (:assignment intent))
                                :logical-cutpoint logical-cutpoint)))
        (->> (:dispatch-intents (coordinator-state coordinator))
             (filter (fn [[_ intent]] (= :pending (:dispatch/status intent))))
             (sort-by key))))

(defn deliver-observed-result!
  "Return an already validated observed run plus its V1 assurance consequence.
   This does not accept a primitive, authorize consumption, or choose among
   competing results. It records a real transport delivery for the exact
   assignment and keeps repeated identical evidence idempotent."
  [{:keys [coordinator-root endpoint assignment observed-run-evidence
           run-assurance-evidence accounting-context accounting-partial logical-cutpoint]
    :or {logical-cutpoint 0}}]
  (when-not (and (remote/valid-assignment? assignment)
                 (observed/valid-observed-run-evidence? observed-run-evidence)
                 (assurance/valid-run-assurance-evidence? run-assurance-evidence)
                 (= (:research-execution-job/root assignment)
                    (:research-execution-job/root observed-run-evidence))
                 (= (:research-run-assignment/root assignment)
                    (:research-run-assignment/root observed-run-evidence))
                 (= (:research-observed-run-evidence/root observed-run-evidence)
                    (:research-run-evidence/root run-assurance-evidence))
                 (if (or accounting-context accounting-partial)
                   (and accounting-context accounting-partial
                        (valid-accounting-result-delivery? accounting-context accounting-partial
                                                          run-assurance-evidence))
                   true))
    (throw (ex-info "Invalid observed result delivery lineage" {})))
  (let [response (post-edn! endpoint "/v1/results"
                            {:transport/kind :research/observed-result-delivery
                             :assignment assignment
                             :observed-run-evidence observed-run-evidence
                             :run-assurance-evidence run-assurance-evidence
                             :accounting-context accounting-context
                             :accounting-partial accounting-partial
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
      (throw (ex-info "Coordinator rejected observed result delivery"
                      {:response response
                       :cause (get-in response [:transport/response :transport/failure-cause])})))))

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
