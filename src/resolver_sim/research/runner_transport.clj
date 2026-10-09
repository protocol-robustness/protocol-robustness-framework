(ns resolver-sim.research.runner-transport
  "Research-only authenticated HTTP transport for independent runner agents.

   This is deliberately a small lab adapter, not a production worker network:
   it provides authenticated message delivery, real local/TCP reachability, and
   canonical evidence through the existing execution-control artifacts. The
   shared transport secret, socket address, process identity, and HTTP logs are
   runtime-only. They never define a job or result root."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.research.accelerated-accounting :as accounting]
            [resolver-sim.research.accounting-proof-admission :as accounting-proof-admission]
            [resolver-sim.research.accounting-execution-work :as accounting-work]
            [resolver-sim.research.native-accounting-work :as native-accounting]
            [resolver-sim.research.claim-evidence-work :as claim-evidence]
            [resolver-sim.research.execution-control :as control]
            [resolver-sim.research.execution-assurance :as assurance]
            [resolver-sim.research.observed-assurance :as observed]
            [resolver-sim.research.observed-execution-work :as observed-work]
            [resolver-sim.research.remote-execution :as remote]
            [resolver-sim.research.reproduction-execution-work :as reproduction-work]
            [resolver-sim.research.interaction-reproduction :as reproduction]
            [resolver-sim.research.runner-forensics :as runner-forensics]
            [resolver-sim.research.runner-participation :as participation])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.nio.file AtomicMoveNotSupportedException Files Path StandardCopyOption]
           [java.security MessageDigest]
           [java.time Duration]
           [java.util Base64]
           [java.util.concurrent Executors ExecutorService]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

(def ^:private mac-header "X-PRF-Research-MAC")
(def ^:private content-type "application/edn; charset=utf-8")
(def ^:private client (HttpClient/newHttpClient))
(def ^:private runner-state-write-lock (Object.))
(def ^:private coordinator-state-write-lock (Object.))
(declare deliver-observed-result! deliver-reproduction-result! execute-delivered-work!
         retry-undelivered-results! retry-undelivered-reproduction-results!
         retry-undelivered-accounting-proofs!
         admit-native-accounting-proof!
         runner-state
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
    (native-accounting/valid-work? work) work
    (reproduction-work/valid-work? work) work
    (observed-work/valid-work? work) work
    (accounting-work/valid-work? work) (:accounting/observed-execution-work work)
    :else nil))

(defn- work-root [work]
  (cond
    (native-accounting/valid-work? work) (:research-native-accounting-work/root work)
    (reproduction-work/valid-work? work) (:research-reproduction-execution-work/root work)
    (observed-work/valid-work? work) (:research-observed-execution-work/root work)
    (accounting-work/valid-work? work) (:research-accounting-execution-work/root work)))

(defn- native-accounting-work-shape? [work]
  ;; Runtime dispatch/cache discriminator only, never an admission check.
  ;; Work is still fully validated at each trust boundary before acceptance.
  (= "research-native-accounting-work.v1" (:artifact/schema work)))

(defn- accounting-cache-from-state [state]
  ;; The journal already retains accepted, full work bodies. Rebuild this
  ;; runtime index after restart; the index itself is never authoritative.
  (reduce (fn [cache work]
            (if (= "research-native-accounting-work.v1" (:artifact/schema work))
              (let [request (:accounting/request work)
                    plan (:accounting/partition-plan work)]
                (-> cache
                    (assoc-in [:requests (:accounting-request/root request)] request)
                    (assoc-in [:plans (:accounting-partition-plan/root plan)] plan)))
              cache))
          {:requests {} :plans {}} (vals (:work-items state))))

(defn- accounting-reference? [value root-key]
  (and (map? value) (= #{root-key} (set (keys value)))
       (hash-ref/valid-sha256-ref? (get value root-key))))

(defn- resolve-accounting-inputs [runner work]
  (if-not (= "research-native-accounting-work.v1" (:artifact/schema work))
    {:work work}
    (let [request (:accounting/request work)
          plan (:accounting/partition-plan work)
          request-ref? (accounting-reference? request :accounting-request/root)
          plan-ref? (accounting-reference? plan :accounting-partition-plan/root)
          cache @(:transport/accounting-input-cache runner)
          resolved-request (if request-ref?
                             (get-in cache [:requests (:accounting-request/root request)]) request)
          resolved-plan (if plan-ref?
                          (get-in cache [:plans (:accounting-partition-plan/root plan)]) plan)]
      (if (or (and request-ref? (nil? resolved-request))
              (and plan-ref? (nil? resolved-plan)))
        {:cache-miss? true
         :missing-roots (vec (remove nil?
                                     [(when (nil? resolved-request) (:accounting-request/root request))
                                      (when (nil? resolved-plan) (:accounting-partition-plan/root plan))]))}
        {:work (assoc work :accounting/request resolved-request
                     :accounting/partition-plan resolved-plan)}))))

(defn- remember-accounting-inputs! [runner work]
  (when (= "research-native-accounting-work.v1" (:artifact/schema work))
    (swap! (:transport/accounting-input-cache runner)
           (fn [cache]
             (-> cache
                 (assoc-in [:requests (get-in work [:accounting/request :accounting-request/root])]
                           (:accounting/request work))
                 (assoc-in [:plans (get-in work [:accounting/partition-plan
                                                :accounting-partition-plan/root])]
                           (:accounting/partition-plan work)))))))

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
   :observed-results {} :accounting-contexts {} :accounting-partials {}
   :reproduction-results {} :native-accounting-results {}
   :accounting-proof-admissions {}})

(defn- valid-runtime-state? [state]
  (and (map? state)
       (= (set (keys initial-runner-state)) (set (keys state)))
       (every? map? (vals state))))

(defn- valid-coordinator-runtime-state? [state]
  (and (map? state)
       (= (disj (set (keys initial-coordinator-state)) :reproduction-results
                :native-accounting-results :accounting-proof-admissions)
          (disj (set (keys state)) :reproduction-results
                :native-accounting-results :accounting-proof-admissions))
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
                        (-> state
                            (update :assignments merge (:assignments fresh))
                            (update :reproduction-results #(or % {}))
                            (update :native-accounting-results #(or % {}))
                            (update :accounting-proof-admissions #(or % {})))))))))

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
          (let [reproduction? (contains? #{"research-reproduction-execution-work.v1" "research-native-accounting-work.v1"}
                                        (:artifact/schema (get-in state [:work-items work-root])))
                recovered-status (if reproduction? :outcome-unknown :interrupted)
                event {:work/event (if reproduction?
                                     :execution-outcome-unknown-after-restart
                                     :interrupted-by-restart)
                       :work/status recovered-status
                       :work/logical-cutpoint logical-cutpoint
                       :work/attempt-sequence (:work/attempt-sequence work-state)
                       :work/recovery-rule (if reproduction?
                                             :new-attempt-required-after-unknown-outcome
                                             :fresh-isolated-retry-on-redelivery)}]
            (let [state (assoc-in state [:work-states work-root]
                                  (-> work-state
                                      (assoc :work/status recovered-status)
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

(defn- claim-observation-from-delivery [runner {:keys [job assignment claim-evidence-work]}]
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
              (response! exchange 422 {:transport/status :invalid-delivery}))))))))

(defn- claim-evidence-handler [runner]
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
                         {:transport/status :invalid-claim-evidence-delivery}))))))))

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

(defn- computed-execution-root [execution]
  (or (:reproduction-execution/root execution) (native-accounting/execution-root execution)))

(defn- valid-native-accounting-delivery? [state {:keys [job assignment native-accounting-execution]}]
  (try
    (let [ar (:research-run-assignment/root assignment)
          work (get-in state [:dispatch-intents ar :execution-work])]
      (and (= assignment (get-in state [:assignments ar]))
           (= job (:work/job work)) (= assignment (:work/assignment work))
           (native-accounting/validate-execution work native-accounting-execution)))
    (catch Exception _ false)))

(defn- coordinator-reproduction-handler [coordinator]
  (reify HttpHandler
    (handle [_ exchange]
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
              (response! exchange 422 {:transport/status :invalid-reproduction-result}))))))))

(defn- coordinator-accounting-proof-handler [coordinator]
  (reify HttpHandler
    (handle [_ exchange]
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
                          :diagnostic (or (:diagnostic (ex-data e)) (.getMessage e))}))))))))

(defn start-runner!
  "Start one independent, authenticated lab-runner endpoint.

   `:runner-participation` is canonical; `:auth-secret` and binding address are
   runtime configuration. Use a non-loopback bind address on separate hosts.
   The returned endpoint URL is intentionally not part of the runner's root."
  [{:keys [runner-participation auth-secret bind-address port advertise-url
           coordinator-root coordinator-endpoint state-path recovery-logical-cutpoint
           before-safe-cutpoint before-result-delivery before-reproduction-compute test-event]
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
                   :transport/before-reproduction-compute before-reproduction-compute
                   :transport/test-event test-event
                   :transport/state-path state-path}
        state (atom (read-runtime-state state-path bootstrap))
        server (HttpServer/create (InetSocketAddress. bind-address (int port)) 0)
        executor (Executors/newCachedThreadPool)
        runner (assoc bootstrap :transport/state state :transport/server server
                      :transport/accounting-input-cache (atom (accounting-cache-from-state @state))
                      :transport/executor executor)]
    (.createContext server "/v1/jobs" (job-handler runner))
    (.createContext server "/v1/claim-evidence" (claim-evidence-handler runner))
    (.createContext server "/v1/cancellations" (cancellation-handler runner))
    (.setExecutor server executor)
    (.start server)
    (let [runner (assoc runner :transport/base-url
                        (or advertise-url
                            (str "http://" bind-address ":" (.getPort (.getAddress server)))))]
      (recover-interrupted-work! runner recovery-logical-cutpoint)
      ;; A completed local run is terminal computation. Recovery only retries
      ;; delivery of its exact retained evidence; it never calls execute-work!.
      (when coordinator-endpoint
        (retry-undelivered-results! runner coordinator-endpoint recovery-logical-cutpoint)
        (retry-undelivered-reproduction-results! runner coordinator-endpoint
                                                 recovery-logical-cutpoint)
        (retry-undelivered-accounting-proofs! runner coordinator-endpoint
                                              recovery-logical-cutpoint))
      (write-runtime-state! runner (runner-state runner))
      runner)))

(defn stop-runner! [runner]
  (when-let [server (:transport/server runner)] (.stop ^HttpServer server 0))
  (when-let [executor (:transport/executor runner)]
    (.shutdownNow ^ExecutorService executor))
  nil)

(defn runner-state [runner] @(:transport/state runner))

(defn- persist-dispatch-intent! [coordinator {:keys [job assignment execution-work execute?]}]
  (when-not (and (remote/valid-job? job) (remote/valid-assignment? assignment)
                 (= (:research-execution-job/root job)
                    (:research-execution-job/root assignment))
                 (work-root execution-work))
    (throw (ex-info "Invalid coordinator dispatch intent" {})))
  (let [assignment-root (:research-run-assignment/root assignment)
        intent {:job job :assignment assignment :execution-work execution-work
                :execute? (if (nil? execute?) true execute?)}]
    (update-coordinator-state!
     coordinator
     (fn [state]
       (let [old (get-in state [:dispatch-intents assignment-root])]
         (when (and old (not= (select-keys old [:job :assignment :execution-work :execute?]) intent))
           (throw (ex-info "Coordinator dispatch substitution"
                           {:assignment/root assignment-root})))
         (-> state
             (assoc-in [:assignments assignment-root] assignment)
             (assoc-in [:dispatch-intents assignment-root]
                       (or old (assoc intent :dispatch/status :pending :dispatch/history [])))))))
    assignment-root))

(defn start-coordinator!
  "Start the research-only coordinator result sink. `assignments` are already
   canonical scheduler decisions; the server only records observed evidence
   bound to one of them. Its address and secret are runtime-only."
  [{:keys [coordinator-root assignments auth-secret bind-address port advertise-url state-path
           after-reproduction-persisted test-event initial-dispatches
           accounting-proof-verification]
    :or {bind-address "127.0.0.1" port 0}}]
  (when-not (and (hash-ref/valid-sha256-ref? coordinator-root)
                 (vector? assignments) (every? remote/valid-assignment? assignments)
                 (= (count assignments) (count (set (map :research-run-assignment/root assignments))))
                 (string? auth-secret) (not (empty? auth-secret))
                 (string? bind-address) (integer? port) (<= 0 port 65535)
                 (or (nil? advertise-url) (string? advertise-url))
                 (or (nil? state-path) (string? state-path))
                 (or (nil? initial-dispatches) (vector? initial-dispatches)))
    (throw (ex-info "Invalid research coordinator transport configuration" {})))
  (let [bootstrap {:coordinator/root coordinator-root :transport/state-path state-path}
        state (atom (read-coordinator-runtime-state state-path bootstrap assignments))
        server (HttpServer/create (InetSocketAddress. bind-address (int port)) 0)
        coordinator {:coordinator/root coordinator-root
                     :transport/auth-secret auth-secret
                     :transport/state-path state-path
                     :transport/state state
                     :transport/accounting-input-known (atom #{})
                     :transport/accounting-proof-verification accounting-proof-verification
                     :transport/after-reproduction-persisted after-reproduction-persisted
                     :transport/test-event test-event
                     :transport/server server}]
    ;; Test/recovery fixtures can restore already-canonical dispatch intents
    ;; before the listener is advertised as ready. This does not bypass result
    ;; validation: it seeds the same intent dispatch-job! persists normally.
    (doseq [dispatch initial-dispatches]
      (persist-dispatch-intent! coordinator dispatch))
    (.createContext server "/v1/results" (coordinator-result-handler coordinator))
    (.createContext server "/v1/reproductions" (coordinator-reproduction-handler coordinator))
    (.createContext server "/v1/accounting" (coordinator-reproduction-handler coordinator))
    (.createContext server "/v1/accounting-proof" (coordinator-accounting-proof-handler coordinator))
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

(defn admit-native-accounting-proof!
  "Coordinator-side alternative admission for a completed native accounting
   assignment. The coordinator structurally validates execution lineage and
   runs the real SP1 verifier before ingesting the partial. The existing V1
   delivery path and its arithmetic recomputation remain unchanged."
  [coordinator {:keys [assignment execution proof-file metadata-file
                       generation-receipt verifier-participation verifier-reference
                       verifier-registry-extension-map verification-cutpoint]}]
  (let [assignment-root (:research-run-assignment/root assignment)
        state (coordinator-state coordinator)
        intent (get-in state [:dispatch-intents assignment-root])
        work (:execution-work intent)]
    (when-not (and (remote/valid-assignment? assignment)
                   (= assignment (get-in state [:assignments assignment-root]))
                   (= assignment (:work/assignment work))
                   (:execute? intent)
                   (native-accounting/valid-work? work))
      (throw (ex-info "Proof-backed accounting admission has no matching assignment"
                      {:cause :accounting-assignment-mismatch})))
    (let [admission (accounting-proof-admission/verify-and-build!
                     {:work work :execution execution :proof-file proof-file
                      :metadata-file metadata-file :generation-receipt generation-receipt
                      :verifier-participation verifier-participation
                      :verifier-reference verifier-reference
                      :verifier-registry-extension-map verifier-registry-extension-map
                      :verification-cutpoint verification-cutpoint})
          plan-root (get-in work [:accounting/partition-plan :accounting-partition-plan/root])
          plan-context (select-keys work [:accounting/request :accounting/partition-plan])
          partial (:accounting-partial execution)
          admission-root (:accounting-proof-backed-admission/root admission)
          execution-root (native-accounting/execution-root execution)
          result-key [assignment-root execution-root]
          previous (get-in state [:native-accounting-results result-key])]
      (when (and previous
                 (not= (:native-accounting-execution previous) execution))
        (throw (ex-info "Accounting assignment already has another execution"
                        {:cause :accounting-execution-conflict})))
      (update-coordinator-state!
       coordinator
       (fn [current]
         (let [current-intent (get-in current [:dispatch-intents assignment-root])
               _ (when-not (and (= assignment (get-in current [:assignments assignment-root]))
                                (= work (:execution-work current-intent))
                                (:execute? current-intent))
                   (throw (ex-info "Proof admission assignment changed during verification"
                                   {:cause :accounting-assignment-mismatch})))
               prior-context (get-in current [:accounting-contexts plan-root])
               existing (get-in current [:accounting-proof-admissions admission-root])]
           (when (and prior-context (not= prior-context plan-context))
             (throw (ex-info "Accounting request/plan context substitution" {})))
           (when (and existing (not= existing admission))
             (throw (ex-info "Proof admission root collision" {})))
           (-> current
               (assoc-in [:accounting-contexts plan-root] plan-context)
               (assoc-in [:native-accounting-results result-key]
                         {:job (:work/job work) :assignment assignment
                          :native-accounting-execution execution})
               (assoc-in [:accounting-proof-admissions admission-root] admission)
               (update-in [:accounting-partials plan-root]
                          (fnil accounting/ingest-partial {}) partial)))))
      {:transport/status (if (and previous
                                  (get-in state [:accounting-proof-admissions admission-root]))
                           :already-admitted :accepted)
       :accounting-proof-backed-admission/root admission-root
       :accounting-partial-result/root (:accounting-partial-result/root partial)
       :canonical-accounting-partial/root
       (:canonical-accounting-partial/root admission)
       :research-proof-verification-evidence/root
       (:research-proof-verification-evidence/root admission)})))

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

(defn- persisted-proof-path [runner content-root]
  (when-not (:transport/state-path runner)
    (throw (ex-info "Durable proof delivery requires a runner journal" {})))
  (let [directory (.resolveSibling (.toPath (io/file (:transport/state-path runner)))
                                   (str (.getFileName (.toPath (io/file (:transport/state-path runner))))
                                        ".proof-artifacts"))]
    (.resolve (Files/createDirectories directory (make-array java.nio.file.attribute.FileAttribute 0))
              (subs content-root 7))))

(defn- path-of [value]
  (if (instance? Path value) value (.toPath (io/file value))))

(defn- persist-proof-file! [runner proof-file]
  (let [bytes (Files/readAllBytes (path-of proof-file))]
    (when (or (zero? (alength ^bytes bytes)) (> (alength ^bytes bytes) (* 24 1024 1024)))
      (throw (ex-info "SP1 proof size is outside the accounting transport limit" {})))
    (let [content-root (str "sha256:" (hex (.digest (MessageDigest/getInstance "SHA-256") bytes)))
          target (persisted-proof-path runner content-root)]
      (when-not (Files/exists target (make-array java.nio.file.LinkOption 0))
        (let [temporary (Files/createTempFile (.getParent target) ".proof-" ".tmp"
                                              (make-array java.nio.file.attribute.FileAttribute 0))]
          (try
            (Files/write temporary bytes (make-array java.nio.file.OpenOption 0))
            (try
              (Files/move temporary target
                          (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE
                                                          StandardCopyOption/REPLACE_EXISTING]))
              (catch AtomicMoveNotSupportedException _
                (Files/move temporary target
                            (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))))
            (finally (Files/deleteIfExists temporary)))))
      {:proof/content-root content-root :proof/file (str target)})))

(defn- proof-delivery-attempt! [runner endpoint job assignment execution delivery-bundle logical-cutpoint]
  (if (contains? (:cancellations (runner-state runner))
                 (:research-execution-job/root job))
    {:delivery/status :cancelled-before-delivery}
  (let [bytes (Files/readAllBytes (path-of (:proof/file delivery-bundle)))
          observed-content-root
          (str "sha256:" (hex (.digest (MessageDigest/getInstance "SHA-256") bytes)))
          _ (when-not (= (:proof/content-root delivery-bundle) observed-content-root)
              (throw (ex-info "Persisted proof artifact content changed" {})))
          response (post-edn! endpoint "/v1/accounting-proof"
                              {:transport/kind :research/native-accounting-proof-delivery
                               :job job :assignment assignment
                               :native-accounting-execution execution
                               :proof/base64 (.encodeToString (Base64/getEncoder) bytes)
                               :proof/metadata (:proof/metadata delivery-bundle)
                               :research-proof-generation-receipt
                               (:research-proof-generation-receipt delivery-bundle)
                               :logical-cutpoint logical-cutpoint})
          body (:transport/response response)]
      (cond
        (nil? (:http/status response))
        {:delivery/status :unreachable
         ;; Preserve only runtime transport diagnostics here. They are useful
         ;; for distinguishing connection/setup failures from coordinator
         ;; rejection and are deliberately excluded from canonical artifacts.
         :transport/error (:transport/error response)
         :transport/exception-class (:transport/exception-class response)}
        (and (= 200 (:http/status response))
             (contains? #{:accepted :already-admitted} (:transport/status body)))
        {:delivery/status (:transport/status body)
         :accounting-proof-backed-admission/root
         (:accounting-proof-backed-admission/root body)
         :reachability
         (control/build-reachability
          {:job-root (:research-execution-job/root job) :route-kind :proof-job-delivery
           :subject-root (:proof/content-root delivery-bundle)
           :source-root (:runner/reference (:runner-participation runner))
           :destination-root (:transport/coordinator-root runner)
           :declared-reachable? true :outcome :realized
           :attempt-cutpoint logical-cutpoint})}
        :else {:delivery/status :rejected
               :transport/status (:transport/status body)
               :transport/cause (:cause body)
               :transport/diagnostic (:diagnostic body)
               :transport/http-status (:http/status response)}))))

(defn deliver-native-accounting-proof!
  "Attach a real, already-generated proof to completed native accounting work
   and deliver it. The work/job/assignment/execution are taken from the
   runner's durable journal; only the proof output is supplied. Proof bytes are
   persisted before the pending delivery state, so restart retries delivery
   without rerunning accounting or proving. The coordinator owns verification
   policy."
  [{:keys [runner endpoint work-root proof-file metadata generation-receipt
           logical-cutpoint]}]
  (let [completed (get-in (runner-state runner) [:completed-work work-root])
        work (get-in (runner-state runner) [:work-items work-root])
        job (:job completed) assignment (:assignment completed)
        execution (:native-accounting-execution completed)]
    (when-not (and completed execution (native-accounting/valid-work? work)
                   (= (:work/assignment work) assignment)
                   (= (:research-native-accounting-work/root work) work-root)
                   (map? metadata) (map? generation-receipt)
                   (Files/isRegularFile (path-of proof-file)
                                        (make-array java.nio.file.LinkOption 0))
                   (integer? logical-cutpoint) (not (neg? logical-cutpoint)))
      (throw (ex-info "Proof delivery must target completed journaled accounting work" {})))
    (let [persisted (persist-proof-file! runner proof-file)
          bundle (merge persisted {:proof/metadata metadata
                                   :research-proof-generation-receipt generation-receipt})]
      (update-runtime-state!
       runner
       (fn [state]
         (let [prior (get-in state [:completed-work work-root :proof-delivery])]
           (when (and prior (not= (select-keys prior [:proof/content-root :proof/metadata
                                                       :research-proof-generation-receipt])
                                  (select-keys bundle [:proof/content-root :proof/metadata
                                                       :research-proof-generation-receipt])))
             (throw (ex-info "Conflicting proof bundle for completed accounting work" {})))
           (assoc-in state [:completed-work work-root :proof-delivery]
                     (merge bundle (select-keys prior [:proof-deliveries :delivery]))))))
      (let [delivered (proof-delivery-attempt! runner endpoint job assignment execution
                                                (get-in (runner-state runner)
                                                        [:completed-work work-root :proof-delivery])
                                                logical-cutpoint)]
        (update-runtime-state!
         runner
         (fn [state]
           (-> state
               (assoc-in [:completed-work work-root :proof-delivery :delivery] delivered)
               (update-in [:completed-work work-root :proof-delivery :proof-deliveries]
                          (fnil conj []) delivered))))
        delivered))))

(defn retry-undelivered-accounting-proofs! [runner endpoint logical-cutpoint]
  (when-not (and (map? endpoint) (integer? logical-cutpoint)
                 (not (neg? logical-cutpoint)))
    (throw (ex-info "Invalid proof-delivery retry parameters" {})))
  (let [pending (->> (:completed-work (runner-state runner))
                     (filter (fn [[_ entry]]
                               (contains? #{:pending :unreachable}
                                          (get-in entry [:proof-delivery :delivery :delivery/status]))))
                     (sort-by key))]
    (mapv
     (fn [[work-root completed]]
       (let [delivered (proof-delivery-attempt!
                        runner endpoint (:job completed) (:assignment completed)
                        (:native-accounting-execution completed) (:proof-delivery completed)
                        logical-cutpoint)]
         (update-runtime-state!
          runner
          (fn [state]
            (-> state
                (assoc-in [:completed-work work-root :proof-delivery :delivery] delivered)
                (update-in [:completed-work work-root :proof-delivery :proof-deliveries]
                           (fnil conj []) delivered))))
         {:work-root work-root :delivery delivered}))
     pending)))

(defn- deliver-computation-result!
  "At-least-once delivery of a persisted fresh execution artifact. The expected
   result is deliberately absent; comparison remains a later consumer step."
  [{:keys [coordinator-root endpoint job assignment reproduction-execution native-accounting-execution logical-cutpoint
           test-event]}]
  (when-not (and (remote/valid-job? job) (remote/valid-assignment? assignment)
                 (if native-accounting-execution
                   (and (hash-ref/valid-sha256-ref? (native-accounting/execution-root native-accounting-execution))
                        (= (:research-execution-job/root job)
                           (get-in native-accounting-execution [:run-evidence :research-execution-job/root]))
                        (= (:research-run-assignment/root assignment)
                           (get-in native-accounting-execution [:run-evidence :research-run-assignment/root])))
                   (reproduction/valid-execution? reproduction-execution))
                 (= (:research-execution-job/root job)
                    (:research-execution-job/root assignment))
                 (or native-accounting-execution
                     (= (:reproduction-input/root reproduction-execution) (:workload/root job))))
    (throw (ex-info "Invalid reproduction result delivery" {})))
  (when test-event (test-event :result-post-initiated))
  (let [execution (or native-accounting-execution reproduction-execution)
        result-root (computed-execution-root execution)
        response (post-edn! endpoint (if native-accounting-execution "/v1/accounting" "/v1/reproductions")
                            {:transport/kind (if native-accounting-execution :research/native-accounting-result-delivery
                                                :research/reproduction-result-delivery)
                             :job job :assignment assignment
                             (if native-accounting-execution :native-accounting-execution :reproduction-execution) execution
                             :logical-cutpoint logical-cutpoint})
        body (:transport/response response) status (:transport/status body)]
    (when test-event
      (test-event (if (:http/status response) :result-post-response-received
                      :result-post-transport-failed)))
    (cond
      (= :authentication-rejected status)
      (throw (ex-info "Coordinator rejected reproduction result authentication" {}))
      (nil? (:http/status response))
      {:delivery/status :unreachable
       :reachability (control/build-reachability
                      {:job-root (:research-execution-job/root job)
                       :route-kind :result-delivery
                       :subject-root result-root
                       :source-root (:runner/reference assignment)
                       :destination-root coordinator-root
                       :declared-reachable? true :outcome :unreachable
                       :attempt-cutpoint logical-cutpoint})}
      (and (= 200 (:http/status response))
           (contains? #{:accepted :already-recorded} status)
           (= result-root (get body (if native-accounting-execution :research-native-accounting-run/root :reproduction-execution/root)))
           (= (:research-run-assignment/root assignment)
              (:research-run-assignment/root body)))
      {:delivery/status status
       :reachability (control/build-reachability
                      {:job-root (:research-execution-job/root job)
                       :route-kind :result-delivery
                       :subject-root result-root
                       :source-root (:runner/reference assignment)
                       :destination-root coordinator-root
                       :declared-reachable? true :outcome :realized
                       :attempt-cutpoint logical-cutpoint})}
      :else (throw (ex-info "Coordinator rejected reproduction result" {:response response})))))

(defn deliver-reproduction-result! [message]
  (when (or (nil? (:reproduction-execution message)) (:native-accounting-execution message))
    (throw (ex-info "Expected reproduction result" {})))
  (deliver-computation-result! message))

(defn deliver-native-accounting-result! [message]
  (when (or (nil? (:native-accounting-execution message)) (:reproduction-execution message))
    (throw (ex-info "Expected native accounting result" {})))
  (deliver-computation-result! message))

(defn- execute-reproduction-delivered-work! [runner work logical-cutpoint]
  (let [root (work-root work)
        native? (native-accounting/valid-work? work)
        job (:work/job work)
        assignment (:work/assignment work)
        job-root (:research-execution-job/root job)
        _ (when-let [hook (:transport/before-safe-cutpoint runner)]
            (hook runner work logical-cutpoint))]
    (when (contains? (:cancellations (runner-state runner)) job-root)
      (update-runtime-state! runner #(append-work-event % root :cancelled logical-cutpoint
                                                        :cancellation-observed))
      (throw (ex-info "Cancellation observed before reproduction execution"
                      {:cause :cancelled-at-safe-cutpoint})))
    (update-runtime-state!
     runner
     (fn [state]
       (let [state (append-work-event state root :executing logical-cutpoint :execution-started)
             delivery-node (runner-forensics/latest-node-hash
                            state :runner.forensic/delivery-accepted
                            (:research-run-assignment/root assignment))]
         (record-forensic-event
          state runner
          {:event-key :runner.forensic/execution-started :subject-root root
           :subject-kind (if native? :research-runner/native-accounting-work :research-runner/reproduction-work)
           :causal-parent-roots [job-root (:research-run-assignment/root assignment) root]
           :parent-nodes (cond-> [] delivery-node (conj delivery-node))
           :observed-root (if native? (:accounting-work-unit/root (:accounting/work-unit work))
                              (:reproduction-input/root (:work/reproduction-input work)))}))))
    ;; Runtime-only qualification hook at the durable executing boundary.
    ;; A process death here is recovered as outcome-unknown, never complete.
    (when-let [hook (:transport/before-reproduction-compute runner)]
      (hook runner work logical-cutpoint))
    (let [{:keys [execution forensic-evidence-nodes]}
          (if native?
            (native-accounting/execute-work-with-evidence! work)
            {:execution (reproduction-work/execute-work! work)})
          ;; Persist the completed deterministic computation before attempting
          ;; network delivery. Restart can only replay this exact value.
          initial {:job job :assignment assignment
                   (if native? :native-accounting-execution :reproduction-execution) execution
                   :result-delivery {:delivery/status :pending}
                   :result-deliveries []}]
      (update-runtime-state!
       runner
       (fn [state]
         (let [complete-node (runner-forensics/build-event-node
                              {:event-key :runner.forensic/execution-completed
                               :subject-root root :subject-kind (if native? :research-runner/native-accounting-work :research-runner/reproduction-work)
                               :actor-root (:runner/reference (:runner-participation runner))
                               :causal-parent-roots [job-root
                                                     (:research-run-assignment/root assignment)
                                                     (computed-execution-root execution)]
                               :parent-nodes (cond-> []
                                               (runner-forensics/latest-node-hash
                                                state :runner.forensic/execution-started root)
                                               (conj (runner-forensics/latest-node-hash
                                                      state :runner.forensic/execution-started root)))
                               :observed-root (computed-execution-root execution)
                               :consequence? true})]
           (let [state (-> state
                           (assoc-in [:completed-work root] initial)
                           (append-work-event root :completed logical-cutpoint :execution-completed)
                           (runner-forensics/record-node complete-node))]
             (if native?
               (reduce runner-forensics/record-node state forensic-evidence-nodes)
               state)))))
      (when-let [hook (:transport/before-result-delivery runner)]
        (hook runner work logical-cutpoint))
      (let [cancelled? (contains? (:cancellations (runner-state runner)) job-root)
            delivered (if cancelled?
                        {:delivery/status :cancelled-before-delivery}
                        (deliver-computation-result!
                         {:coordinator-root (:transport/coordinator-root runner)
                          :endpoint (:transport/coordinator-endpoint runner)
                          :job job :assignment assignment
                          (if native? :native-accounting-execution :reproduction-execution) execution
                          :logical-cutpoint logical-cutpoint
                          :test-event (:transport/test-event runner)}))]
        (update-runtime-state!
         runner
         (fn [state]
           (let [state (-> state
                           (assoc-in [:completed-work root :result-delivery] delivered)
                           (update-in [:completed-work root :result-deliveries] conj delivered)
                           (append-work-event root (if (acknowledge-result? delivered)
                                                     :result-acknowledged :completed)
                                              logical-cutpoint
                                              (if (= :cancelled-before-delivery
                                                     (:delivery/status delivered))
                                                :result-delivery-suppressed
                                                :result-posted)))
                 posted (when (not= :cancelled-before-delivery (:delivery/status delivered))
                          (record-forensic-event
                           state runner
                           {:event-key :runner.forensic/result-posted
                            :subject-root (computed-execution-root execution)
                            :subject-kind (if native? :research-runner/native-accounting-result-delivery
                                              :research-runner/reproduction-result-delivery)
                            :causal-parent-roots (cond-> [(:research-run-assignment/root assignment)
                                                          (computed-execution-root execution)]
                                                   (get-in delivered
                                                           [:reachability
                                                            :research-realized-reachability/root])
                                                   (conj (get-in delivered
                                                                 [:reachability
                                                                  :research-realized-reachability/root])))
                            :parent-nodes [(runner-forensics/latest-node-hash
                                            state :runner.forensic/execution-completed root)]
                            :observed-root (computed-execution-root execution)}))]
             (if (and posted (acknowledge-result? delivered))
               (record-forensic-event
                posted runner
                {:event-key :runner.forensic/coordinator-acknowledged
                 :subject-root (computed-execution-root execution)
                 :subject-kind (if native? :research-runner/native-accounting-result-acknowledgement
                                   :research-runner/reproduction-result-acknowledgement)
                 :causal-parent-roots
                 (cond-> [(computed-execution-root execution)]
                   (get-in delivered
                           [:reachability :research-realized-reachability/root])
                   (conj (get-in delivered
                                 [:reachability :research-realized-reachability/root])))
                 :parent-nodes [(runner-forensics/latest-node-hash
                                 posted :runner.forensic/result-posted
                                 (computed-execution-root execution))]
                 :observed-root (computed-execution-root execution)
                 :consequence? true})
               (or posted state)))))
        {:execution execution :result-delivery delivered}))))

(defn- execute-delivered-work-existing!
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

(defn execute-delivered-work! [runner work logical-cutpoint]
  (if (or (reproduction-work/valid-work? work) (native-accounting/valid-work? work))
    (execute-reproduction-delivered-work! runner work logical-cutpoint)
    (execute-delivered-work-existing! runner work logical-cutpoint)))

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
                                (and (not (:reproduction-execution completed)) (not (:native-accounting-execution completed))
                                     (= :unreachable
                                        (get-in completed [:result-delivery :delivery/status])))))
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

(defn retry-undelivered-reproduction-results! [runner endpoint logical-cutpoint]
  (let [pending (->> (:completed-work (runner-state runner))
                     (filter (fn [[_ entry]]
                               (and (or (:reproduction-execution entry) (:native-accounting-execution entry))
                                    (contains? #{:pending :unreachable}
                                               (get-in entry [:result-delivery :delivery/status])))))
                     (sort-by key))]
    (mapv
     (fn [[root completed]]
       (let [native? (some? (:native-accounting-execution completed))
             execution (or (:native-accounting-execution completed) (:reproduction-execution completed))
             assignment (:assignment completed)
             job (:job completed)
             test-event (:transport/test-event runner)
             _ (when test-event (test-event :runner-retry-initiated))
             delivered (deliver-computation-result!
                        {:coordinator-root (:transport/coordinator-root runner)
                         :endpoint endpoint :job job :assignment assignment
                         (if native? :native-accounting-execution :reproduction-execution) execution :logical-cutpoint logical-cutpoint
                         :test-event test-event})]
         (update-runtime-state!
          runner
          (fn [state]
            (let [state (-> state
                            (assoc-in [:completed-work root :result-delivery] delivered)
                            (update-in [:completed-work root :result-deliveries] conj delivered)
                            (append-work-event root (if (acknowledge-result? delivered)
                                                      :result-acknowledged :completed)
                                               logical-cutpoint :result-redelivery))
                  previous (runner-forensics/latest-node-hash
                            state :runner.forensic/result-posted
                            (computed-execution-root execution))
                  redelivery (record-forensic-event
                              state runner
                              {:event-key (if previous
                                            :runner.forensic/result-redelivered
                                            :runner.forensic/result-posted)
                               :subject-root (computed-execution-root execution)
                               :subject-kind (if native? :research-runner/native-accounting-result-delivery
                                                 :research-runner/reproduction-result-delivery)
                               :causal-parent-roots
                               (cond-> [(:research-run-assignment/root assignment)
                                        (computed-execution-root execution)]
                                 (get-in delivered
                                         [:reachability :research-realized-reachability/root])
                                 (conj (get-in delivered
                                               [:reachability
                                                :research-realized-reachability/root])))
                               :parent-nodes (cond-> [(runner-forensics/latest-node-hash
                                                       state :runner.forensic/execution-completed root)]
                                               previous (conj previous))
                               :observed-root (computed-execution-root execution)})]
              (if (and redelivery (acknowledge-result? delivered))
                (record-forensic-event
                 redelivery runner
                 {:event-key :runner.forensic/coordinator-acknowledged
                  :subject-root (computed-execution-root execution)
                  :subject-kind (if native? :research-runner/native-accounting-result-acknowledgement
                                    :research-runner/reproduction-result-acknowledgement)
                  :causal-parent-roots
                  (cond-> [(computed-execution-root execution)]
                    (get-in delivered
                            [:reachability :research-realized-reachability/root])
                    (conj (get-in delivered
                                  [:reachability :research-realized-reachability/root])))
                  :parent-nodes [(runner-forensics/latest-node-hash
                                  redelivery (if previous
                                               :runner.forensic/result-redelivered
                                               :runner.forensic/result-posted)
                                  (computed-execution-root execution))]
                  :observed-root (computed-execution-root execution)
                  :consequence? true})
                (or redelivery state)))))
         (when test-event
           (test-event (if (acknowledge-result? delivered)
                         :runner-acknowledgement-recorded
                         :runner-redelivery-not-acknowledged)))
         {(if native? :research-native-accounting-run/root :reproduction-execution/root) (computed-execution-root execution)
          :delivery delivered}))
     pending)))

(defn- accounting-input-refs [work]
  (assoc work
         :accounting/request {:accounting-request/root
                              (get-in work [:accounting/request :accounting-request/root])}
         :accounting/partition-plan {:accounting-partition-plan/root
                                     (get-in work [:accounting/partition-plan
                                                   :accounting-partition-plan/root])}))

(defn- interpret-job-delivery
  [coordinator-root job assignment logical-cutpoint response cache-status]
  (let [body (:transport/response response)
        status (:transport/status body)]
    (cond
      (= :authentication-rejected status)
      (throw (ex-info "Runner rejected transport authentication"
                      {:transport/error :authentication-rejected}))
      (nil? (:http/status response))
      {:delivery/status :unreachable :delivery/cache-status cache-status
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
      {:delivery/status status :delivery/cache-status cache-status
       :reachability (control/build-reachability
                      {:job-root (:research-execution-job/root job)
                       :route-kind :job-delivery
                       :subject-root (:research-run-assignment/root assignment)
                       :source-root coordinator-root
                       :destination-root (:runner/reference assignment)
                       :declared-reachable? true :outcome :realized
                       :attempt-cutpoint logical-cutpoint})}
      :else (throw (ex-info "Runner rejected job delivery" {:response response})))))

(defn deliver-job!
  "Deliver a rooted job/assignment over HTTP and produce existing canonical
   realized-reachability evidence. Authentication rejection is a transport
   boundary failure, not a false claim that the route was unreachable."
  [{:keys [coordinator-root endpoint job assignment execution-work logical-cutpoint execute?
           use-cached-accounting-input?]
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
  (let [message {:transport/kind :research/job-delivery
                 :job job :assignment assignment :execution-work execution-work
                 :execute? execute? :logical-cutpoint logical-cutpoint}
        referenced? (and use-cached-accounting-input?
                         (native-accounting-work-shape? execution-work))
        first-response (post-edn! endpoint "/v1/jobs"
                                  (if referenced?
                                    (assoc message :execution-work (accounting-input-refs execution-work))
                                    message))
        cache-miss? (and referenced?
                         (= :accounting-input-cache-miss
                            (get-in first-response [:transport/response :transport/status])))
        response (if cache-miss? (post-edn! endpoint "/v1/jobs" message) first-response)
        cache-status (cond cache-miss? :miss-resent-full
                           referenced? :referenced
                           (native-accounting-work-shape? execution-work) :full
                           :else :not-applicable)]
    (interpret-job-delivery coordinator-root job assignment logical-cutpoint
                            response cache-status)))

(defn deliver-claim-evidence-coordinate!
  "Transport one existing rooted claim-evidence coordinate to an opted-in
   runner. The plan, source artifacts, coordinate assignment, and resulting
   observation retain their existing identities; this HTTP envelope adds no
   research-analysis schema or semantics."
  [{:keys [coordinator-root endpoint job assignment claim-evidence-work
           logical-cutpoint]
    :or {logical-cutpoint 0}}]
  (let [participant (:runner-participation claim-evidence-work)
        message {:job job :assignment assignment
                 :claim-evidence-work (dissoc claim-evidence-work
                                              :runner-participation)}
        observation (claim-observation-from-delivery
                     {:runner-participation participant} message)]
    (when-not (and (hash-ref/valid-sha256-ref? coordinator-root)
                   observation
                   (integer? logical-cutpoint) (not (neg? logical-cutpoint)))
      (throw (ex-info "Invalid claim-evidence coordinate dispatch" {})))
    (let [response (post-edn! endpoint "/v1/claim-evidence"
                              (assoc message :logical-cutpoint logical-cutpoint))
          body (:transport/response response)
          returned (:research-claim-evidence-observation body)
          status (:transport/status body)]
      (cond
        (= :authentication-rejected status)
        (throw (ex-info "Runner rejected transport authentication"
                        {:transport/error :authentication-rejected}))

        (nil? (:http/status response))
        {:delivery/status :unreachable
         :reachability
         (control/build-reachability
          {:job-root (:research-execution-job/root job)
           :route-kind :job-delivery
           :subject-root (:research-claim-evidence-coordinate/root
                          (:coordinate-assignment claim-evidence-work))
           :source-root coordinator-root
           :destination-root (:runner/reference assignment)
           :declared-reachable? true :outcome :unreachable
           :attempt-cutpoint logical-cutpoint})}

        (and (= 200 (:http/status response)) (= :accepted status)
             (= (:research-execution-job/root job)
                (:research-execution-job/root body))
             (= (:research-run-assignment/root assignment)
                (:research-run-assignment/root body))
             (= (:runner/reference assignment) (:runner/reference body))
             (= observation returned))
        {:delivery/status :accepted
         :research-claim-evidence-observation returned
         :reachability
         (control/build-reachability
          {:job-root (:research-execution-job/root job)
           :route-kind :job-delivery
           :subject-root (:research-claim-evidence-coordinate/root
                          (:coordinate-assignment claim-evidence-work))
           :source-root coordinator-root
           :destination-root (:runner/reference assignment)
           :declared-reachable? true :outcome :realized
           :attempt-cutpoint logical-cutpoint})}

        :else
        (throw (ex-info "Runner rejected claim-evidence coordinate delivery"
                        {:response response}))))))

(defn- note-dispatch-delivery!
  [coordinator endpoint assignment execution-work logical-cutpoint delivery]
  (let [assignment-root (:research-run-assignment/root assignment)
        cache-key (when (native-accounting-work-shape? execution-work)
                    [(:transport/base-url endpoint) (:runner/reference assignment)
                     (get-in execution-work [:accounting/request :accounting-request/root])
                     (get-in execution-work [:accounting/partition-plan
                                             :accounting-partition-plan/root])])]
    (when (and cache-key (contains? #{:accepted :already-completed}
                                    (:delivery/status delivery)))
      (swap! (:transport/accounting-input-known coordinator) conj cache-key))
    (update-coordinator-state!
     coordinator
     (fn [state]
       (-> state
           (assoc-in [:dispatch-intents assignment-root :dispatch/status]
                     (if (= :unreachable (:delivery/status delivery)) :pending :delivered))
           (update-in [:dispatch-intents assignment-root :dispatch/history]
                      (fnil conj []) {:dispatch/logical-cutpoint logical-cutpoint
                                      :dispatch/delivery-status (:delivery/status delivery)
                                      :dispatch/cache-status (:delivery/cache-status delivery)
                                      :dispatch/reachability-root
                                      (get-in delivery [:reachability
                                                        :research-realized-reachability/root])}))))))

(defn dispatch-job!
  "Persist coordinator dispatch intent before attempting delivery. Repeated
   transport attempts are at-least-once; the immutable assignment/work bundle
   makes later runner application exactly-once semantically. This is the
   qualification seam for coordinator restart/recovery, not a job queue."
  [coordinator {:keys [endpoint job assignment execution-work logical-cutpoint execute?]
                :or {logical-cutpoint 0 execute? true} :as dispatch}]
  (when-not (and (remote/valid-job? job) (remote/valid-assignment? assignment))
    (throw (ex-info "Invalid coordinator dispatch" {})))
  (let [_ (persist-dispatch-intent! coordinator dispatch)
        cache-key (when (native-accounting-work-shape? execution-work)
                    [(:transport/base-url endpoint) (:runner/reference assignment)
                     (get-in execution-work [:accounting/request :accounting-request/root])
                     (get-in execution-work [:accounting/partition-plan
                                             :accounting-partition-plan/root])])]
    (let [delivery (deliver-job! {:coordinator-root (:coordinator/root coordinator)
                                  :endpoint endpoint :job job :assignment assignment
                                  :execution-work execution-work :logical-cutpoint logical-cutpoint
                                  :execute? execute?
                                  :use-cached-accounting-input?
                                  (and cache-key
                                       (contains? @(:transport/accounting-input-known coordinator)
                                                  cache-key))})]
      (note-dispatch-delivery! coordinator endpoint assignment execution-work
                               logical-cutpoint delivery)
      delivery)))

(defn dispatch-reproduction!
  "Dispatch canonical input plus implementation to a runner. The expected
   result is used only after execution and is never in dispatch intent, HTTP
   job delivery, or runner state."
  [coordinator {:keys [endpoint job assignment reproduction-work authoritative-result
                       logical-cutpoint]
                :or {logical-cutpoint 0}}]
  (when-not (and (reproduction-work/valid-work? reproduction-work)
                 (= job (:work/job reproduction-work))
                 (= assignment (:work/assignment reproduction-work)))
    (throw (ex-info "Invalid reproduction dispatch bundle" {})))
  (let [delivery (dispatch-job! coordinator
                                {:endpoint endpoint :job job :assignment assignment
                                 :execution-work reproduction-work
                                 :logical-cutpoint logical-cutpoint})
        entry (some (fn [[_ result]]
                      (when (= (:research-run-assignment/root assignment)
                               (:research-run-assignment/root (:assignment result))) result))
                    (:reproduction-results (coordinator-state coordinator)))
        execution (:reproduction-execution entry)]
    (cond-> {:delivery delivery :reproduction-execution execution}
      (and execution authoritative-result)
      (assoc :reproduction-evidence
             (reproduction/compare-execution execution authoritative-result)))))

(defn dispatch-native-accounting!
  "Dispatch the rooted account partition and admit its directly assured partial.
   Job and assignment are derived from the bundle; expected values are absent."
  [coordinator {:keys [endpoint accounting-work logical-cutpoint] :or {logical-cutpoint 0}}]
  (when-not (native-accounting/valid-work? accounting-work)
    (throw (ex-info "Invalid native accounting dispatch bundle" {})))
  (let [job (:work/job accounting-work) assignment (:work/assignment accounting-work)
        delivery (dispatch-job! coordinator {:endpoint endpoint :job job :assignment assignment
                                              :execution-work accounting-work :logical-cutpoint logical-cutpoint})
        entry (some (fn [[_ result]]
                      (when (= (:research-run-assignment/root assignment)
                               (:research-run-assignment/root (:assignment result))) result))
                    (:native-accounting-results (coordinator-state coordinator)))]
    {:delivery delivery :native-accounting-execution (:native-accounting-execution entry)}))

(defn- accounting-batch-messages [items]
  (second
   (reduce
    (fn [[seen messages] item]
      (let [work (:accounting-work item)
            input-key [(get-in work [:accounting/request :accounting-request/root])
                       (get-in work [:accounting/partition-plan
                                     :accounting-partition-plan/root])]
            repeated? (contains? seen input-key)]
        [(conj seen input-key)
         (conj messages
               {:transport/kind :research/job-delivery
                :job (:work/job work) :assignment (:work/assignment work)
                :execution-work (if repeated? (accounting-input-refs work) work)
                :execute? true :logical-cutpoint (:logical-cutpoint item 0)})]))
    [#{} []] items)))

(defn dispatch-native-accounting-batch!
  "Send several already-rooted native work units to one runner in one HTTP
   envelope. Repeated account inputs within that envelope are sent by root
   after their first full occurrence. Every assignment keeps its own intent,
   result admission and retry history. A lost batch response leaves its
   assignments pending; redelivery uses the ordinary idempotent path."
  [coordinator endpoint items]
  (let [works (mapv :accounting-work items)
        assignments (mapv :work/assignment works)
        runner-roots (set (map :runner/reference assignments))]
    (when-not (and (vector? items) (<= 1 (count items) 32)
                   (map? endpoint) (= 1 (count runner-roots))
                   (= (count assignments)
                      (count (set (map :research-run-assignment/root assignments))))
                   (every? native-accounting/valid-work? works)
                   (every? #(and (integer? (:logical-cutpoint % 0))
                                 (not (neg? (:logical-cutpoint % 0)))) items))
      (throw (ex-info "Invalid native accounting transport batch" {})))
    (doseq [work works]
      (persist-dispatch-intent! coordinator
                                {:job (:work/job work)
                                 :assignment (:work/assignment work)
                                 :execution-work work :execute? true}))
    (let [messages (accounting-batch-messages items)
          response (post-edn! endpoint "/v1/jobs"
                              {:transport/kind :research/job-delivery-batch
                               :transport/items messages})
          replies (get-in response [:transport/response :transport/results])]
      (when (and (:http/status response)
                 (not (and (= 200 (:http/status response))
                           (= :batch-processed
                              (get-in response [:transport/response :transport/status]))
                           (vector? replies) (= (count replies) (count items)))))
        (throw (ex-info "Runner rejected native accounting batch" {:response response})))
      (mapv
       (fn [index item reply]
         (let [work (:accounting-work item)
               assignment (:work/assignment work)
               cutpoint (:logical-cutpoint item 0)
               unit-root (get-in work [:accounting/work-unit :accounting-work-unit/root])]
           (try
             (let [referenced? (accounting-reference?
                                (get-in (nth messages index)
                                        [:execution-work :accounting/request])
                                :accounting-request/root)
                   cache-miss? (and referenced?
                                    (= :accounting-input-cache-miss
                                       (get-in reply [:transport/response :transport/status])))
                   delivery (if cache-miss?
                              (deliver-job! {:coordinator-root (:coordinator/root coordinator)
                                             :endpoint endpoint :job (:work/job work)
                                             :assignment assignment :execution-work work
                                             :logical-cutpoint cutpoint})
                              (interpret-job-delivery
                               (:coordinator/root coordinator) (:work/job work)
                               assignment cutpoint (or reply {:http/status nil})
                               (if referenced? :referenced-batch :full-batch)))
                   delivery (if cache-miss?
                              (assoc delivery :delivery/cache-status :miss-resent-full-batch)
                              delivery)
                   _ (note-dispatch-delivery! coordinator endpoint assignment work
                                              cutpoint delivery)
                   entry (some (fn [[_ result]]
                                 (when (= (:research-run-assignment/root assignment)
                                          (:research-run-assignment/root (:assignment result)))
                                   result))
                               (:native-accounting-results (coordinator-state coordinator)))]
               {:accounting-work-unit/root unit-root
                :dispatch {:delivery delivery
                           :native-accounting-execution (:native-accounting-execution entry)}})
             (catch Exception e
               {:accounting-work-unit/root unit-root
                :dispatch/status :failed :failure/class (.getName (class e))}))))
       (range (count items)) items (or replies (repeat nil))))))

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
