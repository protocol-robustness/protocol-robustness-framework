(ns resolver-sim.research.runner-transport.handlers
  "Authenticated HTTP ingress handlers for research runner/coordinator messages."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [resolver-sim.accounting.reductions :as accounting]
            [resolver-sim.research.execution-assurance :as assurance]
            [resolver-sim.research.execution-control :as control]
            [resolver-sim.research.claim-evidence-work :as claim-evidence]
            [resolver-sim.research.native-accounting-work :as native-accounting]
            [resolver-sim.research.observed-assurance :as observed]
            [resolver-sim.research.remote-execution :as remote]
            [resolver-sim.research.reproduction-execution-work :as reproduction-work]
            [resolver-sim.research.interaction-reproduction :as reproduction]
            [resolver-sim.research.runner-forensics :as runner-forensics]
            [resolver-sim.research.runner-transport.http :as transport-http]
            [resolver-sim.research.runner-transport.work-types :as work-types])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files]
           [java.util Base64]))

(def ^:dynamic *runtime* nil)
(def ^:private runtime-keys
  #{:update-runtime-state! :append-work-event :record-forensic-event
    :retry-undelivered-reproduction-results! :execute-delivered-work!
    :runner-state :update-coordinator-state! :coordinator-state
    :admit-native-accounting-proof!})

(defn with-runtime [runtime f]
  (when-not (and (map? runtime) (= runtime-keys (set (keys runtime))) (fn? f))
    (throw (ex-info "Invalid runner transport handler runtime" {})))
  (binding [*runtime* runtime] (f)))

(defn- runtime-fn [key]
  (or (get *runtime* key)
      (throw (ex-info "Runner transport handler called without runtime services"
                      {:missing-runtime-key key}))))
(defn- update-runtime-state! [& args]
  (apply (runtime-fn :update-runtime-state!) args))
(defn- append-work-event [& args]
  (apply (runtime-fn :append-work-event) args))
(defn- record-forensic-event [& args]
  (apply (runtime-fn :record-forensic-event) args))
(defn- retry-undelivered-reproduction-results! [& args]
  (apply (runtime-fn :retry-undelivered-reproduction-results!) args))
(defn- execute-delivered-work! [& args]
  (apply (runtime-fn :execute-delivered-work!) args))
(defn- runner-state [& args] (apply (runtime-fn :runner-state) args))
(defn- update-coordinator-state! [& args]
  (apply (runtime-fn :update-coordinator-state!) args))
(defn- coordinator-state [& args]
  (apply (runtime-fn :coordinator-state) args))
(defn- admit-native-accounting-proof! [& args]
  (apply (runtime-fn :admit-native-accounting-proof!) args))
(defn- request-body [exchange] (transport-http/request-body exchange))
(defn- response! [exchange status body]
  (transport-http/response! exchange status body))
(defn- authenticated? [secret payload supplied]
  (transport-http/authenticated? secret payload supplied))
(def ^:private mac-header transport-http/mac-header)
(def ^:private work-root work-types/work-root)
(def ^:private observed-work-for work-types/observed-work-for)
(def ^:private resolve-accounting-inputs work-types/resolve-accounting-inputs)
(def ^:private remember-accounting-inputs! work-types/remember-accounting-inputs!)

(defn- valid-delivery? [runner {:keys [job assignment]}]
  (and (remote/valid-job? job)
       (remote/valid-assignment? assignment)
       (= (:research-execution-job/root job)
          (:research-execution-job/root assignment))
       (= (:runner-participation/root (:runner-participation runner))
          (:runner-participation/root assignment))
       (= (:runner/reference (:runner-participation runner))
          (:runner/reference assignment))))

(defn- assignment-for-job [state job-root]
  (some (fn [[_ assignment]]
          (when (= job-root (:research-execution-job/root assignment)) assignment))
        (:assignments state)))

(defn- valid-work-delivery? [runner job assignment work]
  (let [observed-work (observed-work-for work)]
    (and observed-work
         (= job (:work/job observed-work))
         (= assignment (:work/assignment observed-work))
         (= (:runner-participation runner) (:work/runner-participation observed-work)))))

(defn claim-observation-from-delivery [runner {:keys [job assignment claim-evidence-work]}]
  (when (and (remote/valid-job? job)
             (remote/valid-assignment? assignment)
             (= :job/research-claim-evidence (:job/class job))
             (= (:research-execution-job/root job)
                (:research-execution-job/root assignment))
             (= (:runner-participation/root (:runner-participation runner))
                (:runner-participation/root assignment))
             (= (:runner/reference (:runner-participation runner))
                (:runner/reference assignment))
             (map? claim-evidence-work))
    (let [{:keys [context proposition-id descriptor result plan coordinate-assignment]}
          claim-evidence-work
          coordinate-root
          (:research-claim-evidence-coordinate/root coordinate-assignment)]
      (when (= coordinate-root (:workload/root job))
        (claim-evidence/observe context proposition-id descriptor result plan
                                coordinate-assignment
                                (:runner-participation runner))))))

(defn valid-accounting-result-delivery?
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

(defn- apply-job-message! [runner message]
  (try
    (let [resolved (resolve-accounting-inputs runner (:execution-work message))
          message (assoc message :execution-work (:work resolved))]
      (cond
        (:cache-miss? resolved)
        [409 {:transport/status :accounting-input-cache-miss
              :transport/missing-roots (:missing-roots resolved)}]

        (not (and (= :research/job-delivery (:transport/kind message))
                  (valid-delivery? runner message)))
        [422 {:transport/status :invalid-delivery}]

        :else
        (let [{:keys [job assignment logical-cutpoint execution-work execute?]} message
              job-root (:research-execution-job/root job)
              cancellation (get-in @(:transport/state runner) [:cancellations job-root])]
          (if cancellation
            [200 {:transport/status :cancelled-before-start
                  :research-execution-job/root job-root
                  :research-run-assignment/root (:research-run-assignment/root assignment)
                  :runner/reference (:runner/reference (:runner-participation runner))
                  :transport/logical-cutpoint logical-cutpoint}]
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
                                 (let [known (get-in state [:work-states root])
                                       state (if known state
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
                                     :parent-nodes [] :observed-root root}))))))
                    _ (when (and execution-work (native-accounting/valid-work? execution-work))
                        (remember-accounting-inputs! runner execution-work))
                    work-status (get-in state [:work-states root :work/status])
                    _ (when (and (= :completed work-status)
                                 (or (reproduction-work/valid-work? execution-work)
                                     (native-accounting/valid-work? execution-work))
                                 (contains? #{:pending :unreachable}
                                            (get-in state [:completed-work root
                                                           :result-delivery :delivery/status])))
                        (retry-undelivered-reproduction-results!
                         runner (:transport/coordinator-endpoint runner) logical-cutpoint))
                    execution-result (when (and (not= false execute?) execution-work
                                                (contains? #{:accepted :interrupted
                                                             :outcome-unknown} work-status))
                                       (execute-delivered-work! runner execution-work logical-cutpoint))]
                [200 {:transport/status (if (and execution-work
                                                 (contains? #{:completed :result-acknowledged}
                                                            work-status))
                                          :already-completed :accepted)
                      :research-execution-job/root job-root
                      :research-run-assignment/root (:research-run-assignment/root assignment)
                      :runner/reference (:runner/reference (:runner-participation runner))
                      :transport/execution-status (cond execution-result :complete
                                                        (and execution-work (= false execute?)) :deferred
                                                        :else nil)
                      :transport/logical-cutpoint logical-cutpoint}]))))))
    (catch clojure.lang.ExceptionInfo e
      [422 {:transport/status :execution-failed
            :execution/failure-cause (or (:cause (ex-data e)) :task-execution-failed)}])
    (catch Exception _ [422 {:transport/status :invalid-delivery}])))

(defn job-handler [runtime runner]
  (reify HttpHandler
    (handle [_ exchange]
      (with-runtime runtime #(
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
              (if (= :research/job-delivery-batch (:transport/kind message))
                (let [items (:transport/items message)]
                  (if-not (and (vector? items) (<= 1 (count items) 32))
                    (response! exchange 422 {:transport/status :invalid-delivery})
                    (response! exchange 200
                               {:transport/status :batch-processed
                                :transport/results (mapv (fn [item]
                                                           (let [[status body] (apply-job-message! runner item)]
                                                             {:http/status status :transport/response body}))
                                                         items)})))
                (let [[status body] (apply-job-message! runner message)]
                  (response! exchange status body))))
            (catch Exception _
              (response! exchange 422 {:transport/status :invalid-delivery}))))))))))

(defn claim-evidence-handler [runtime runner]
  (reify HttpHandler
    (handle [_ exchange]
      (with-runtime runtime #(
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
            (let [message (edn/read-string (String. payload StandardCharsets/UTF_8))
                  observation (claim-observation-from-delivery runner message)
                  assignment (:assignment message)
                  job (:job message)]
              (if-not observation
                (response! exchange 422 {:transport/status :invalid-claim-evidence-delivery})
                (response! exchange 200
                           {:transport/status :accepted
                            :research-execution-job/root
                            (:research-execution-job/root job)
                            :research-run-assignment/root
                            (:research-run-assignment/root assignment)
                            :runner/reference
                            (:runner/reference (:runner-participation runner))
                            :research-claim-evidence-observation observation})))
            (catch Exception _
              (response! exchange 422
                         {:transport/status :invalid-claim-evidence-delivery}))))))))))

(defn cancellation-handler [runtime runner]
  (reify HttpHandler
    (handle [_ exchange]
      (with-runtime runtime #(
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
                        (cond
                          (and (= job-root work-job)
                               (get-in state [:completed-work work-root :native-accounting-execution])
                               (contains? #{:pending :unreachable}
                                          (get-in state [:completed-work work-root :result-delivery :delivery/status])))
                          (-> state
                              (assoc-in [:completed-work work-root :result-delivery]
                                        {:delivery/status :cancelled-before-delivery})
                              (append-work-event work-root :completed logical-cutpoint :result-delivery-suppressed))
                          (and (= job-root work-job)
                               (not (contains? #{:completed :result-acknowledged} status)))
                          (append-work-event state work-root :cancelled logical-cutpoint :cancellation-observed)
                          :else state)))
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
              (response! exchange 422 {:transport/status :invalid-cancellation}))))))))))

(defn coordinator-result-handler [runtime coordinator]
  (reify HttpHandler
    (handle [_ exchange]
      (with-runtime runtime #(
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
                                       :transport/failure-cause :result-ingestion-failed}))))))))))

(defn- valid-reproduction-result-delivery? [state message]
  (let [{:keys [job assignment reproduction-execution]} message
        ar (:research-run-assignment/root assignment)
        intent (get-in state [:dispatch-intents ar])
        work (:execution-work intent)]
    (and (remote/valid-job? job) (remote/valid-assignment? assignment)
         (= assignment (get-in state [:assignments ar]))
         (= job (:work/job work))
         (= assignment (:work/assignment work))
         (reproduction-work/valid-work? work)
         (= (:research-reproduction-execution-work/root work)
            (:research-reproduction-execution-work/root
             (assoc work :work/job job :work/assignment assignment)))
         (reproduction/valid-execution? reproduction-execution)
         (= (:reproduction-input/root (:work/reproduction-input work))
            (:reproduction-input/root reproduction-execution))
         (= (:research-implementation-identity/root (:work/implementation work))
            (:research-implementation-identity/root reproduction-execution)))))

(defn computed-execution-root [execution]
  (or (:reproduction-execution/root execution) (native-accounting/execution-root execution)))

(defn- valid-native-accounting-delivery? [state {:keys [job assignment native-accounting-execution]}]
  (try
    (let [ar (:research-run-assignment/root assignment)
          work (get-in state [:dispatch-intents ar :execution-work])]
      (and (= assignment (get-in state [:assignments ar]))
           (= job (:work/job work)) (= assignment (:work/assignment work))
           (native-accounting/validate-execution work native-accounting-execution)))
    (catch Exception _ false)))

(defn coordinator-reproduction-handler [runtime coordinator]
  (reify HttpHandler
    (handle [_ exchange]
      (with-runtime runtime #(
      (when-let [event! (:transport/test-event coordinator)]
        (event! :handler-entered))
      (let [payload (request-body exchange)
            secret (:transport/auth-secret coordinator)
            supplied (some-> exchange .getRequestHeaders (.getFirst mac-header))]
        (when-let [event! (:transport/test-event coordinator)]
          (event! :request-body-received))
        (cond
          (not= "POST" (.getRequestMethod exchange))
          (response! exchange 405 {:transport/status :method-not-allowed})
          (not (authenticated? secret payload supplied))
          (response! exchange 401 {:transport/status :authentication-rejected})
          :else
          (try
            (let [message (edn/read-string (String. payload StandardCharsets/UTF_8))
                  state (coordinator-state coordinator)]
              (if-not (case (:transport/kind message)
                        :research/reproduction-result-delivery (valid-reproduction-result-delivery? state message)
                        :research/native-accounting-result-delivery (valid-native-accounting-delivery? state message)
                        false)
                (response! exchange 422 {:transport/status :invalid-reproduction-result})
                (let [{:keys [job assignment reproduction-execution native-accounting-execution]} message
                      native? (some? native-accounting-execution)
                      execution (or native-accounting-execution reproduction-execution)
                      eroot (computed-execution-root execution)
                      result-key [(:research-run-assignment/root assignment) eroot]
                      result-map (if native? :native-accounting-results :reproduction-results)
                      previous (get-in state [result-map result-key])
                      entry (select-keys message (if native? [:job :assignment :native-accounting-execution]
                                                     [:job :assignment :reproduction-execution]))]
                  (when (and previous (not= previous entry))
                    (throw (ex-info "Reproduction result substitution" {})))
                  (when-let [event! (:transport/test-event coordinator)]
                    (event! (if previous :duplicate-result-recognized
                                :new-result-recognized)))
                  (update-coordinator-state!
                   coordinator
                   (fn [state]
                     (if previous state
                         (let [state (assoc-in state [result-map result-key] entry)]
                           (if-not native? state
                                   (let [work (get-in state [:dispatch-intents (:research-run-assignment/root assignment) :execution-work])
                                         plan-root (get-in work [:accounting/partition-plan :accounting-partition-plan/root])
                                         context {:request (:accounting/request work) :partition-plan (:accounting/partition-plan work)}
                                         prior (get-in state [:accounting-contexts plan-root])]
                                     (when (and prior (not= prior context))
                                       (throw (ex-info "Native accounting context substitution" {})))
                                     (-> state
                                         (assoc-in [:accounting-contexts plan-root] context)
                                         (update-in [:accounting-partials plan-root] (fnil accounting/ingest-partial {})
                                                    (:accounting-partial native-accounting-execution)))))))))
                  ;; Runtime-only fault-injection seam. It runs after the
                  ;; atomic journal write and before the HTTP acknowledgement;
                  ;; no canonical scheduler or result identity depends on it.
                  (when-let [hook (:transport/after-reproduction-persisted coordinator)]
                    (hook coordinator message))
                  (response! exchange 200
                             {:transport/status (if previous :already-recorded :accepted)
                              :research-execution-job/root (:research-execution-job/root job)
                              :research-run-assignment/root (:research-run-assignment/root assignment)
                              (if native? :research-native-accounting-run/root :reproduction-execution/root) eroot
                              :coordinator/root (:coordinator/root coordinator)})
                  (when-let [event! (:transport/test-event coordinator)]
                    (event! :acknowledgement-emitted)))))
            (catch Exception _
              (response! exchange 422 {:transport/status :invalid-reproduction-result}))))))))))

(defn coordinator-accounting-proof-handler [runtime coordinator]
  (reify HttpHandler
    (handle [_ exchange]
      (with-runtime runtime #(
      (let [payload (request-body exchange)
            supplied (some-> exchange .getRequestHeaders (.getFirst mac-header))]
        (cond
          (not= "POST" (.getRequestMethod exchange))
          (response! exchange 405 {:transport/status :method-not-allowed})
          (not (authenticated? (:transport/auth-secret coordinator) payload supplied))
          (response! exchange 401 {:transport/status :authentication-rejected})
          (> (alength ^bytes payload) (* 32 1024 1024))
          (response! exchange 413 {:transport/status :proof-bundle-too-large})
          :else
          (try
            (let [message (edn/read-string (String. payload StandardCharsets/UTF_8))
                  job (:job message)
                  assignment (:assignment message)
                  execution (:native-accounting-execution message)
                  proof-bytes (.decode (Base64/getDecoder) ^String (:proof/base64 message))
                  metadata (:proof/metadata message)
                  generation-receipt (:research-proof-generation-receipt message)
                  verification-config (:transport/accounting-proof-verification coordinator)]
              (when-not (and (= :research/native-accounting-proof-delivery
                                (:transport/kind message))
                             (remote/valid-job? job)
                             (= job (get-in (coordinator-state coordinator)
                                            [:dispatch-intents
                                             (:research-run-assignment/root assignment)
                                             :execution-work :work/job]))
                             (map? metadata) (map? generation-receipt)
                             (map? verification-config))
                (throw (ex-info "Incomplete proof-backed accounting delivery" {})))
              (let [temp (Files/createTempDirectory "prf-accounting-proof-delivery-"
                                                    (make-array java.nio.file.attribute.FileAttribute 0))
                    proof-path (.resolve temp "proof.bin")
                    metadata-path (.resolve temp "metadata.json")]
                (try
                  (Files/write proof-path proof-bytes (make-array java.nio.file.OpenOption 0))
                  (Files/write metadata-path
                               (.getBytes (json/write-str metadata) StandardCharsets/UTF_8)
                               (make-array java.nio.file.OpenOption 0))
                  (response! exchange 200
                             (admit-native-accounting-proof!
                              coordinator
                              (merge verification-config
                                     {:assignment assignment :execution execution
                                      :proof-file (str proof-path)
                                      :metadata-file (str metadata-path)
                                      :generation-receipt generation-receipt})))
                  (finally
                    (Files/deleteIfExists proof-path)
                    (Files/deleteIfExists metadata-path)
                    (Files/deleteIfExists temp)))))
            (catch Exception e
              (response! exchange 422
                         {:transport/status :proof-admission-rejected
                          :cause (or (:cause (ex-data e)) :invalid-proof-backed-delivery)
                          ;; Runtime diagnostic only. Never included in a canonical
                          ;; artifact/root; it lets the sender distinguish lineage
                          ;; rejection from a local verifier/toolchain failure.
                          :diagnostic (or (:diagnostic (ex-data e)) (.getMessage e))}))))))))))
