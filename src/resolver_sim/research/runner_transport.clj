(ns resolver-sim.research.runner-transport
  "Compatibility facade for the research-only runner/coordinator lab adapter.

   It composes state ownership and recovery with focused internal namespaces
   for wire authentication, journal mechanics, work schemas, ingress,
   execution, lifecycle, proof, results, and dispatch. This remains a research
   lab adapter, not a production worker network. Runtime transport data never
   defines a job or result root."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.research.runner-transport.http :as transport-http]
            [resolver-sim.research.runner-transport.journal :as transport-journal]
            [resolver-sim.research.runner-transport.dispatch :as transport-dispatch]
            [resolver-sim.research.runner-transport.execution :as transport-execution]
            [resolver-sim.research.runner-transport.handlers :as transport-handlers]
            [resolver-sim.research.runner-transport.lifecycle :as transport-lifecycle]
            [resolver-sim.research.runner-transport.proof :as transport-proof]
            [resolver-sim.research.runner-transport.results :as transport-results]
            [resolver-sim.research.runner-transport.work-types :as transport-work]
            [resolver-sim.research.remote-execution :as remote]
            [resolver-sim.research.runner-forensics :as runner-forensics]))

(def ^:private runner-state-write-lock (Object.))
(def ^:private coordinator-state-write-lock (Object.))
(declare deliver-observed-result! deliver-reproduction-result! execute-delivered-work!
         retry-undelivered-results! retry-undelivered-reproduction-results!
         retry-undelivered-accounting-proofs!
         admit-native-accounting-proof!
         post-edn!
         handler-runtime
         lifecycle-services
         runner-state
         coordinator-state)

(def ^:private hex transport-http/hex)
(def ^:private post-edn! transport-http/post-edn!)
(def ^:private persist-proof-file! transport-proof/persist-proof-file!)
(def ^:private claim-observation-from-delivery
  transport-handlers/claim-observation-from-delivery)
(def ^:private valid-accounting-result-delivery?
  transport-handlers/valid-accounting-result-delivery?)
(def ^:private computed-execution-root transport-handlers/computed-execution-root)

(def ^:private observed-work-for transport-work/observed-work-for)
(def ^:private work-root transport-work/work-root)
(def ^:private work-root-value transport-work/work-root-value)
(def ^:private native-accounting-work-shape? transport-work/native-accounting-work-shape?)
(def ^:private accounting-cache-from-state transport-work/accounting-cache-from-state)

(def ^:private runner-journal-schema "research-runner-runtime-journal.v2")
(def ^:private legacy-runner-journal-schema "research-runner-runtime-journal.v1")
(def ^:private initial-runner-state
  {:jobs {} :assignments {} :work-items {} :work-states {}
   :cancellations {} :completed-work {} :forensic-evidence-nodes {}})

;; This is deliberately the same small recovery boundary as the runner
;; journal: canonical job/assignment/work constituents plus transport history.
;; It is not a coordinator-owned semantic result store or a second evidence
;; architecture.
(def ^:private coordinator-journal-schema "research-coordinator-runtime-journal.v2")
(def ^:private legacy-coordinator-journal-schema "research-coordinator-runtime-journal.v1")
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

(def ^:private journal-checkpoint-interval transport-journal/checkpoint-interval)
(def ^:private state-delta transport-journal/state-delta)
(def ^:private apply-state-delta transport-journal/apply-state-delta)
(def ^:private checkpoint-record transport-journal/checkpoint-record)
(def ^:private recover-journal transport-journal/recover-journal)
(def ^:private append-journal-record! transport-journal/append-record!)
(def ^:private replace-journal-with-checkpoint! transport-journal/replace-with-checkpoint!)

(defn- runner-owner-fields [runner]
  {:runner-participation/root
   (:runner-participation/root (:runner-participation runner))
   :coordinator/root (:transport/coordinator-root runner)})

(defn- read-runtime-journal [path runner]
  (recover-journal path runner-journal-schema legacy-runner-journal-schema
                   (runner-owner-fields runner) :runner-runtime/state
                   initial-runner-state valid-runtime-state? true))

(defn read-runtime-journal-file
  "Decode a runner/coordinator runtime journal for diagnostics and recovery
   tests. The returned state is reconstructed from checkpoint and delta records;
   this is not a semantic artifact or a trust decision."
  [path]
  (let [first-line (first (str/split (slurp path) #"\n"))
        header (edn/read-string first-line)]
    (case (:artifact/schema header)
      "research-runner-runtime-journal.v2"
      (let [recovered (recover-journal path runner-journal-schema
                                       legacy-runner-journal-schema
                                       (select-keys header [:runner-participation/root :coordinator/root])
                                       :runner-runtime/state initial-runner-state
                                       valid-runtime-state? false)]
        (assoc header :runner-runtime/state (:state recovered)
               :journal/sequence (:sequence recovered)))
      "research-coordinator-runtime-journal.v2"
      (let [recovered (recover-journal path coordinator-journal-schema
                                       legacy-coordinator-journal-schema
                                       (select-keys header [:coordinator/root])
                                       :coordinator-runtime/state initial-coordinator-state
                                       valid-coordinator-runtime-state? false)]
        (assoc header :coordinator-runtime/state (:state recovered)
               :journal/sequence (:sequence recovered)))
      "research-runner-runtime-journal.v1" header
      "research-coordinator-runtime-journal.v1" header
      (throw (ex-info "Unsupported runtime journal schema"
                      {:state-path path :schema (:artifact/schema header)})))))

(defn- write-runtime-state! [runner before state]
  (when-let [path (:transport/state-path runner)]
    (let [journal (:transport/journal runner)
          {:keys [sequence format]} @journal
          owner-fields (runner-owner-fields runner)
          next-sequence (inc sequence)
          checkpoint? (or (#{:new :legacy} format)
                          (zero? (mod next-sequence journal-checkpoint-interval)))
          record (if checkpoint?
                   (checkpoint-record runner-journal-schema owner-fields state
                                      (if (#{:new :legacy} format) 0 next-sequence))
                   {:artifact/schema runner-journal-schema :journal/record :delta
                    :journal/sequence next-sequence :journal/changes (state-delta before state)})]
      (when (or (not= before state) (#{:new :legacy} format))
        (if checkpoint? (replace-journal-with-checkpoint! path record)
            (append-journal-record! path record))
        (reset! journal {:sequence (:journal/sequence record) :format :v2})))))

(defn- read-coordinator-runtime-state [path coordinator assignments]
  (let [fresh (assoc initial-coordinator-state :assignments
                     (into {} (map (juxt :research-run-assignment/root identity) assignments)))
        recovered (recover-journal path coordinator-journal-schema
                                   legacy-coordinator-journal-schema
                                   {:coordinator/root (:coordinator/root coordinator)}
                                   :coordinator-runtime/state initial-coordinator-state
                                   valid-coordinator-runtime-state? true)
        state (:state recovered)]
    ;; A recovery may repeat declared assignments, but may never swap a
    ;; persisted assignment under an existing root.
    (doseq [assignment assignments]
      (let [root (:research-run-assignment/root assignment)
            old (get-in state [:assignments root])]
        (when (and old (not= old assignment))
          (throw (ex-info "Coordinator assignment substitution" {:assignment/root root})))))
    {:state (-> state
                (update :assignments merge (:assignments fresh))
                (update :reproduction-results #(or % {}))
                (update :native-accounting-results #(or % {}))
                (update :accounting-proof-admissions #(or % {})))
     :persisted-state state
     :journal (select-keys recovered [:sequence :format])}))

(defn- coordinator-checkpoint-record [coordinator state sequence]
  {:artifact/schema coordinator-journal-schema :journal/record :checkpoint
   :journal/sequence sequence :coordinator/root (:coordinator/root coordinator)
   :coordinator-runtime/state state})

(defn- write-coordinator-runtime-state! [coordinator before state]
  (when-let [path (:transport/state-path coordinator)]
    (let [journal (:transport/journal coordinator)
          {:keys [sequence format]} @journal
          next-sequence (inc sequence)
          checkpoint? (or (#{:new :legacy} format)
                          (zero? (mod next-sequence journal-checkpoint-interval)))
          record (if checkpoint?
                   (coordinator-checkpoint-record coordinator state
                                                  (if (#{:new :legacy} format) 0 next-sequence))
                   {:artifact/schema coordinator-journal-schema :journal/record :delta
                    :journal/sequence next-sequence :journal/changes (state-delta before state)})]
      (when (or (not= before state) (#{:new :legacy} format))
        (if checkpoint? (replace-journal-with-checkpoint! path record)
            (append-journal-record! path record))
        (reset! journal {:sequence (:journal/sequence record) :format :v2})))))

(defn- update-runtime-state!
  "Persist an all-or-nothing runtime transition. This journal is not a new
   semantic artifact: it preserves exact already-rooted inputs/evidence across
   a runner restart."
  [runner f & args]
  (locking runner-state-write-lock
    (let [old @(:transport/state runner)
          state (apply f old args)]
      (when-not (= old state)
        (reset! (:transport/state runner) state)
        (write-runtime-state! runner old state))
      state)))

(defn- update-coordinator-state! [coordinator f & args]
  (locking coordinator-state-write-lock
    (let [old @(:transport/state coordinator)
          state (apply f old args)]
      (when-not (= old state)
        (reset! (:transport/state coordinator) state)
        (write-coordinator-runtime-state! coordinator old state))
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

(defn start-runner! [options]
  (transport-lifecycle/start-runner! (lifecycle-services) options))

(defn stop-runner! [runner]
  (transport-lifecycle/stop-runner! runner))

(defn runner-state [runner]
  (transport-lifecycle/runner-state runner))

(defn- persist-dispatch-intent! [coordinator {:keys [job assignment execution-work execute?]}]
  (let [bound-work (observed-work-for execution-work)
        execution-work-root (when bound-work (work-root-value execution-work))]
    (when-not (and (remote/valid-job? job) (remote/valid-assignment? assignment)
                   (= (:research-execution-job/root job)
                      (:research-execution-job/root assignment))
                   bound-work
                   (= job (:work/job bound-work))
                   (= assignment (:work/assignment bound-work))
                   (hash-ref/valid-sha256-ref? execution-work-root))
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
      assignment-root)))

(defn start-coordinator! [options]
  (transport-lifecycle/start-coordinator! (lifecycle-services) options))

(defn stop-coordinator! [coordinator]
  (transport-lifecycle/stop-coordinator! coordinator))

(defn coordinator-state [coordinator]
  (transport-lifecycle/coordinator-state coordinator))

(defn- with-proof-runtime [f]
  (transport-proof/with-runtime
   {:coordinator-state coordinator-state
    :update-coordinator-state! update-coordinator-state!
    :runner-state runner-state
    :update-runtime-state! update-runtime-state!
    :post-edn! post-edn!}
   f))

(defn admit-native-accounting-proof! [coordinator request]
  (with-proof-runtime
    #(transport-proof/admit-native-accounting-proof! coordinator request)))

(defn deliver-native-accounting-proof! [request]
  (with-proof-runtime
    #(transport-proof/deliver-native-accounting-proof! request)))

(defn retry-undelivered-accounting-proofs! [runner endpoint logical-cutpoint]
  (with-proof-runtime
    #(transport-proof/retry-undelivered-accounting-proofs!
      runner endpoint logical-cutpoint)))

(defn coordinator-endpoint
  "Return runtime-only connection data for a started runner or coordinator."
  ([server auth-secret]
   (transport-http/coordinator-endpoint server auth-secret))
  ([server auth-secret request-timeout-ms]
   (transport-http/coordinator-endpoint server auth-secret request-timeout-ms)))

(defn- handler-runtime []
  {:update-runtime-state! update-runtime-state!
   :append-work-event append-work-event
   :record-forensic-event record-forensic-event
   :retry-undelivered-reproduction-results! retry-undelivered-reproduction-results!
   :execute-delivered-work! execute-delivered-work!
   :runner-state runner-state
   :update-coordinator-state! update-coordinator-state!
   :coordinator-state coordinator-state
   :admit-native-accounting-proof! admit-native-accounting-proof!})

(defn- lifecycle-services []
  {:read-runner-journal read-runtime-journal
   :runner-state runner-state
   :accounting-cache-from-state accounting-cache-from-state
   :handler-runtime handler-runtime
   :recover-interrupted-work! recover-interrupted-work!
   :retry-undelivered-results! retry-undelivered-results!
   :retry-undelivered-reproduction-results! retry-undelivered-reproduction-results!
   :retry-undelivered-accounting-proofs! retry-undelivered-accounting-proofs!
   :write-runtime-state! write-runtime-state!
   :read-coordinator-runtime-state read-coordinator-runtime-state
   :coordinator-state coordinator-state
   :write-coordinator-runtime-state! write-coordinator-runtime-state!
   :persist-dispatch-intent! persist-dispatch-intent!})

(defn- with-dispatch-runtime [f]
  (transport-dispatch/with-runtime
   {:post-edn! post-edn!
    :persist-dispatch-intent! persist-dispatch-intent!
    :update-coordinator-state! update-coordinator-state!
    :coordinator-state coordinator-state
    :native-accounting-work-shape? native-accounting-work-shape?
    :observed-work-for observed-work-for
    :claim-observation-from-delivery claim-observation-from-delivery
    :valid-accounting-result-delivery? valid-accounting-result-delivery?}
   f))

(defn deliver-job!
  "Deliver one rooted job/assignment through the authenticated runner transport."
  [dispatch]
  (with-dispatch-runtime #(transport-dispatch/deliver-job! dispatch)))

(defn deliver-claim-evidence-coordinate!
  "Deliver a rooted claim-evidence coordinate to an opted-in runner."
  [dispatch]
  (with-dispatch-runtime
    #(transport-dispatch/deliver-claim-evidence-coordinate! dispatch)))

(defn dispatch-job!
  "Persist dispatch intent, deliver the immutable bundle, and record its outcome."
  [coordinator dispatch]
  (with-dispatch-runtime #(transport-dispatch/dispatch-job! coordinator dispatch)))

(defn dispatch-reproduction!
  "Dispatch a reproduction input and optionally compare its result."
  [coordinator dispatch]
  (with-dispatch-runtime #(transport-dispatch/dispatch-reproduction! coordinator dispatch)))

(defn dispatch-native-accounting!
  "Dispatch native accounting work and return its admitted partial, if present."
  [coordinator dispatch]
  (with-dispatch-runtime
    #(transport-dispatch/dispatch-native-accounting! coordinator dispatch)))

(defn dispatch-native-accounting-batch!
  "Deliver a bounded batch of native accounting work to one runner."
  [coordinator endpoint items]
  (with-dispatch-runtime
    #(transport-dispatch/dispatch-native-accounting-batch! coordinator endpoint items)))

(defn retry-pending-dispatches!
  "Retry pending persisted dispatch intents without changing their work bundles."
  [coordinator endpoint-for-assignment logical-cutpoint]
  (with-dispatch-runtime
    #(transport-dispatch/retry-pending-dispatches!
      coordinator endpoint-for-assignment logical-cutpoint)))

(defn deliver-observed-result!
  "Deliver already-validated observed evidence to its coordinator."
  [delivery]
  (with-dispatch-runtime #(transport-dispatch/deliver-observed-result! delivery)))

(defn deliver-cancellation!
  "Deliver a rooted cancellation and return observed control evidence."
  [delivery]
  (with-dispatch-runtime #(transport-dispatch/deliver-cancellation! delivery)))

(defn- with-results-runtime [f]
  (transport-results/with-runtime
   {:post-edn! post-edn!
    :computed-execution-root computed-execution-root
    :runner-state runner-state
    :update-runtime-state! update-runtime-state!
    :append-work-event append-work-event
    :acknowledge-result? acknowledge-result?
    :deliver-observed-result! deliver-observed-result!
    :record-forensic-event record-forensic-event}
   f))

(defn- deliver-computation-result! [message]
  (with-results-runtime
    #(transport-results/deliver-computation-result! message)))

(defn deliver-reproduction-result! [message]
  (with-results-runtime
    #(transport-results/deliver-reproduction-result! message)))

(defn deliver-native-accounting-result! [message]
  (with-results-runtime
    #(transport-results/deliver-native-accounting-result! message)))

(defn retry-undelivered-results!
  ([runner logical-cutpoint]
   (with-results-runtime
     #(transport-results/retry-undelivered-results! runner logical-cutpoint)))
  ([runner endpoint logical-cutpoint]
   (with-results-runtime
     #(transport-results/retry-undelivered-results! runner endpoint logical-cutpoint))))

(defn retry-undelivered-reproduction-results! [runner endpoint logical-cutpoint]
  (with-results-runtime
    #(transport-results/retry-undelivered-reproduction-results!
      runner endpoint logical-cutpoint)))

(defn execute-delivered-work! [runner work logical-cutpoint]
  (transport-execution/with-runtime
   {:work-root work-root
    :runner-state runner-state
    :update-runtime-state! update-runtime-state!
    :append-work-event append-work-event
    :record-forensic-event record-forensic-event
    :computed-execution-root computed-execution-root
    :acknowledge-result? acknowledge-result?
    :deliver-computation-result! deliver-computation-result!
    :deliver-observed-result! deliver-observed-result!
    :observed-work-for observed-work-for}
   #(transport-execution/execute-delivered-work! runner work logical-cutpoint)))

