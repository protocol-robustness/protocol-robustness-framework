(ns resolver-sim.research.runner-forensics
  "Causal reconstruction for the research runner transport.

   This is deliberately a composition over `evidence.forensic-graph`: runner
   persistence retains ordinary typed evidence nodes alongside recovery state;
   it does not define a runner-specific audit-log schema or alternate DAG."
  (:require [clojure.set :as set]
            [resolver-sim.evidence.forensic-graph :as forensic]
            [resolver-sim.hash.reference :as hash-ref]))

(def event-keys
  #{:runner.forensic/delivery-accepted
    :runner.forensic/execution-started
    :runner.forensic/execution-completed
    :runner.forensic/result-posted
    :runner.forensic/result-redelivered
    :runner.forensic/coordinator-acknowledged
    :runner.forensic/cancellation-observed
    :runner.forensic/restart-interrupted
    :runner.forensic/reproduction-result-posted
    :runner.forensic/reproduction-result-redelivered})

(defn- root? [x] (hash-ref/valid-sha256-ref? x))
(defn- node-ref [node] (str "sha256:" (:node-hash node)))

(defn build-event-node
  "Build one ordinary typed forensic evidence node. `event-key` describes the
   factual role; causal parents and generic evidence-node parents establish
   order without using wall-clock timestamps."
  [{:keys [event-key subject-root subject-kind actor-root causal-parent-roots
           parent-nodes observed-root consequence?]}]
  (when-not (and (contains? event-keys event-key) (root? subject-root)
                 (qualified-keyword? subject-kind) (root? actor-root)
                 (every? root? causal-parent-roots)
                 (or (nil? observed-root) (root? observed-root)))
    (throw (ex-info "Invalid runner forensic event" {:event-key event-key})))
  (forensic/build-forensic-evidence-node
   {:subject {:subject/root subject-root :subject/kind subject-kind}
    :role :observed :actor {:actor/root actor-root :actor/role :runner}
    :visibility :commitment-only :observation-key event-key
    :observed-root observed-root :consequence? (boolean consequence?)
    :causal-parent-roots (vec (sort (set causal-parent-roots)))
    :parent-nodes (vec (sort (or parent-nodes [])))}))

(defn record-node
  "Add an existing generic evidence node to durable runner state. The map is a
   cache/store for independently valid nodes, not an authoritative event log."
  [state node]
  (assoc-in state [:forensic-evidence-nodes (:node-hash node)] node))

(defn nodes [state]
  (vec (vals (:forensic-evidence-nodes state))))

(defn- nodes-by-event [nodes event-key]
  (filter #(= event-key (get-in % [:extensions :forensic/observation-key])) nodes))

(defn- parent-hashes [node] (set (:parent-hashes node)))

(defn- rooted-values [value]
  (cond
    (map? value) (concat (keep (fn [[k v]]
                                 (when (and (keyword? k)
                                            (re-find #"/root$" (str k))
                                            (root? v))
                                   v)) value)
                         (mapcat rooted-values (vals value)))
    (sequential? value) (mapcat rooted-values value)
    :else []))

(defn validate-runner-reconstruction
  "Validate the generic forensic DAG plus runner-specific causal obligations.
   This consumes retained canonical artifacts/evidence, never HTTP logs or
   timestamps. It reports falsifiable gaps instead of manufacturing history."
  ([state] (validate-runner-reconstruction state nil))
  ([state coordinator-state]
   (let [nodes (nodes state)
         artifacts (set (rooted-values (dissoc state :forensic-evidence-nodes)))
         graph (forensic/validate-forensic-evidence-graph
                {:nodes nodes :artifact-roots artifacts})
         delivery (nodes-by-event nodes :runner.forensic/delivery-accepted)
         started (nodes-by-event nodes :runner.forensic/execution-started)
         completed (nodes-by-event nodes :runner.forensic/execution-completed)
         posted (concat (nodes-by-event nodes :runner.forensic/result-posted)
                        (nodes-by-event nodes :runner.forensic/result-redelivered))
         acknowledged (nodes-by-event nodes :runner.forensic/coordinator-acknowledged)
         cancellation (nodes-by-event nodes :runner.forensic/cancellation-observed)
         delivery-hashes (set (map :node-hash delivery))
         started-hashes (set (map :node-hash started))
         completed-hashes (set (map :node-hash completed))
         posted-hashes (set (map :node-hash posted))
         errors
         (vec (concat
              ;; No execution may appear without causally depending on accepted
              ;; assignment delivery, and no result may omit completed execution.
               (for [n started :when (empty? (set/intersection delivery-hashes (parent-hashes n)))]
                 {:error :runner-forensic/execution-without-accepted-assignment
                  :node-hash (:node-hash n)})
               (for [n completed :when (empty? (set/intersection started-hashes (parent-hashes n)))]
                 {:error :runner-forensic/result-without-execution-lineage
                  :node-hash (:node-hash n)})
               (for [n posted :when (empty? (set/intersection completed-hashes (parent-hashes n)))]
                 {:error :runner-forensic/result-post-without-completion
                  :node-hash (:node-hash n)})
               (for [n acknowledged :when (empty? (set/intersection posted-hashes (parent-hashes n)))]
                 {:error :runner-forensic/acknowledgement-without-result-post
                  :node-hash (:node-hash n)})
              ;; A redelivery must cause from an earlier delivery attempt.
               (for [n (nodes-by-event nodes :runner.forensic/result-redelivered)
                     :when (empty? (set/intersection posted-hashes (parent-hashes n)))]
                 {:error :runner-forensic/retry-without-prior-delivery-attempt
                  :node-hash (:node-hash n)})
              ;; One exclusive work item cannot have two success nodes.
               (for [[subject entries] (group-by #(get-in % [:extensions :forensic/subject :subject/root]) completed)
                     :when (> (count entries) 1)]
                 {:error :runner-forensic/two-successful-executions-for-exclusive-work
                  :subject-root subject :node-hashes (vec (sort (map :node-hash entries)))})
               (for [[work-root work-state] (:work-states state)
                     :let [events (:work/history work-state)
                           cancellation-index (first (keep-indexed
                                                      (fn [idx event]
                                                        (when (= :cancellation-observed
                                                                 (:work/event event)) idx)) events))]
                     :when (and cancellation-index
                                (some #(= :execution-started (:work/event %))
                                      (drop (inc cancellation-index) events)))]
                 {:error :runner-forensic/execution-after-terminal-cancellation
                  :work-root work-root})
               (for [[work-root completed-work] (:completed-work state)
                     :let [deliveries (:result-deliveries completed-work)]
                     :when (and (> (count deliveries) 1)
                                (not-every? #(= :unreachable (:delivery/status %))
                                            (butlast deliveries)))]
                 {:error :runner-forensic/retry-without-prior-unacknowledged-attempt
                  :work-root work-root})
               (for [[work-root completed-work] (:completed-work state)
                     :when (not (:reproduction-execution completed-work))
                     :let [observed (:observed-run-evidence completed-work)
                           assurance (:run-assurance-evidence completed-work)
                           assignment (get-in observed [:proof/source-artifacts :assignment])]
                     :when (not (and (= assignment
                                        (get-in state [:assignments
                                                       (:research-run-assignment/root assignment)]))
                                     (= (:research-observed-run-evidence/root observed)
                                        (:research-run-evidence/root assurance))))]
                 {:error :runner-forensic/coordinator-ingestion-assignment-or-result-substitution
                  :work-root work-root})
              ;; A stored completed artifact must have all causal stages.
               (for [[work-root completed-work] (:completed-work state)
                     :when (not (:reproduction-execution completed-work))
                     :let [observed (:observed-run-evidence completed-work)
                           observed-root (:research-observed-run-evidence/root observed)
                           delivery-status (get-in completed-work [:result-delivery :delivery/status])
                           result-nodes (filter #(= observed-root
                                                    (get-in % [:extensions :forensic/subject :subject/root]))
                                                posted)]
                     :when (and (not= :cancelled-before-delivery delivery-status)
                                (empty? result-nodes))]
                 {:error :runner-forensic/missing-result-delivery-attempt
                  :work-root work-root :observed-run-root observed-root})
               (when coordinator-state
                 (for [n acknowledged
                       :when (not= :research-runner/reproduction-result-acknowledgement
                                   (get-in n [:extensions :forensic/subject :subject/kind]))
                       :let [observed-root (get-in n [:extensions :forensic/subject :subject/root])
                             completed (some (fn [[_ entry]]
                                               (when (= observed-root
                                                        (get-in entry [:observed-run-evidence
                                                                       :research-observed-run-evidence/root]))
                                                 entry))
                                             (:completed-work state))
                             coordinator-entry (get-in coordinator-state
                                                       [:observed-results observed-root])]
                       :when (not (and completed coordinator-entry
                                       (= (:assignment coordinator-entry)
                                          (get-in completed [:observed-run-evidence
                                                             :proof/source-artifacts :assignment]))
                                       (= (get-in coordinator-entry [:observed-run-evidence
                                                                     :research-observed-run-evidence/root])
                                          observed-root)
                                       (= (get-in coordinator-entry [:run-assurance-evidence
                                                                     :research-run-assurance-evidence/root])
                                          (get-in completed [:run-assurance-evidence
                                                             :research-run-assurance-evidence/root]))))]
                   {:error :runner-forensic/coordinator-acknowledgement-substitution-or-missing
                    :observed-run-root observed-root}))
              ;; Persisted cancellation is terminal for its job: an execution
              ;; start carrying that job as a causal root is a contradiction.
               (for [c cancellation
                     s started
                     :when (contains? (set (get-in s [:extensions :forensic/causal-parent-roots]))
                                      (get-in c [:extensions :forensic/subject :subject/root]))]
                 {:error :runner-forensic/execution-after-terminal-cancellation
                  :execution-node (:node-hash s) :cancellation-node (:node-hash c)})))]
     {:valid? (and (:valid? graph) (empty? errors))
      :graph graph :errors errors
      :summary {:node-count (count nodes)
                :delivery-count (count delivery)
                :execution-count (count started)
                :completion-count (count completed)
                :result-post-count (count posted)
                :acknowledgement-count (count acknowledged)
                :cancellation-count (count cancellation)}})))

(defn latest-node-hash
  [state event-key subject-root]
  (some->> (nodes-by-event (nodes state) event-key)
           (filter #(= subject-root (get-in % [:extensions :forensic/subject :subject/root])))
           (sort-by :node-hash)
           last
           :node-hash))

(defn node-root [node] (node-ref node))
