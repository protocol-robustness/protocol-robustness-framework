(ns resolver-sim.contract-model.replay.temporal
  "Temporal instrumentation and time management for replay engine.

   Decomposed from contract-model/replay to improve kernel modularity."
  (:require [resolver-sim.time.context :as time-ctx]
            [resolver-sim.protocols.protocol :as proto]))

(defn- epoch-second
  "Canonicalize a supported replay event time to Unix seconds.

   Replay clocks and protocol deadlines have second precision. `Instant` values
   therefore compare by their epoch second; sub-second precision is deliberately
   floored, not represented in the world clock, and a number is truncated via
   long (also toward zero). Unsupported types raise a structured
   :invalid-event-time ex-info instead of a ClassCastException so the replay
   boundary can reject them as a trace-level :rejected entry."
  [event-time]
  (cond
    (number? event-time)                  (long event-time)
    (instance? java.time.Instant event-time)
    (.getEpochSecond ^java.time.Instant event-time)
    :else
    (throw (ex-info "unsupported event-time type"
                    {:type :invalid-event-time
                     :event-time event-time
                     :actual-type (str (class event-time))
                     :expected #{:number :java.time.Instant}}))))

(defn advance-world-time
  "Advance :block-ts and scenario-step counter atomically.
   Returns {:world w' :delta-ms n :advanced? bool}.

   Same-timestamp events (event-time == block-time) still increment
   the logical step and event sequence, but do not move block-ts.

   Rejects regressive event times with a structured :time-regression ex-info.
   Scenario validation (:non-monotonic-event-time, :event-time-before-initial)
   prevents this through the public replay API; this guard is the second line of
   defence for internal kernels (resume-from-snapshot / run-simulation-loop) that
   bypass validation, and for replay profiles with temporal rules disabled.

   The free primitive time-ctx/advance-time intentionally stays permissive so
   exploratory tooling can probe arbitrary timestamps; monotonicity is enforced
   here at the replay boundary."
  [world event-time]
  (let [now-ts       (or (time-ctx/block-ts world) 0)
        event-ts     (epoch-second event-time)
        delta-seconds (- event-ts now-ts)
        _            (when (neg? delta-seconds)
                       (throw (ex-info "advance-world-time: event time regresses simulation time"
                                       {:type :time-regression
                                        :now-ts now-ts
                                        :event-ts event-ts
                                        :event-time event-time})))
        world'       (time-ctx/advance-time world {:to event-ts})]
    {:world     world'
     :delta-ms  (max 0 (* delta-seconds 1000))
     :advanced? (pos? delta-seconds)}))

;; Action → deadline config for the generic :deadline-enforcement temporal rule.
;;   :kind      — deadline-kind passed to TemporalDeadlines/deadline-for
;;   :boundary  — :before     → action allowed only when event-time is strictly before deadline
;;                :at-or-after → action allowed only when event-time is at or after deadline
;;   :subject   — fn from event to subject identifier (e.g. workflow-id)
;;   :on-expired — error keyword when action is blocked at the boundary
(def ^:private deadline-action-config
  {"submit_evidence"            {:kind :evidence-submission :boundary :before
                                 :subject #(get-in % [:params :workflow-id])
                                 :on-expired :evidence-deadline-exceeded}
   "execute_pending_settlement" {:kind :settlement :boundary :at-or-after
                                 :subject #(get-in % [:params :workflow-id])
                                 :on-expired :appeal-window-not-expired
                                 :rule-id :sew/appeal-window-open}
   "escalate_dispute"           {:kind :appeal :boundary :before
                                 :subject #(get-in % [:params :workflow-id])
                                 :on-expired :appeal-window-expired}
   "challenge_resolution"       {:kind :appeal :boundary :before
                                 :subject #(get-in % [:params :workflow-id])
                                 :on-expired :appeal-window-expired}
   "execute_fraud_slash"        {:kind :earliest-execution :boundary :at-or-after
                                 :subject #(or (get-in % [:params :slash-id])
                                               (get-in % [:params :workflow-id]))
                                 :on-expired :timelock-not-expired}
   "execute_fraud_group_slash"  {:kind :earliest-execution :boundary :at-or-after
                                 :subject #(or (get-in % [:params :slash-id])
                                               (get-in % [:params :workflow-id]))
                                 :on-expired :timelock-not-expired}})

(def ^:private temporal-rules
  [{:id :missing-event-time
    :check (fn [{:keys [event-time]}]
             (if (or (number? event-time) (instance? java.time.Instant event-time))
               {:ok? true}
               {:ok? false :error :invalid-event-time}))}
   {:id :non-regressive-time
    :check (fn [{:keys [event-time now]}]
             (let [event-ts (epoch-second event-time)]
               (if (< event-ts now)
                 {:ok? false :error :time-regression}
                 {:ok? true})))}
   {:id :deadline-enforcement
    :check (fn [{:keys [event context protocol world event-time]}]
             (if-let [cfg (get deadline-action-config (:action event))]
               (let [subject  ((:subject cfg) event)
                     deadline (when (satisfies? proto/TemporalDeadlines protocol)
                                (proto/deadline-for protocol world (:kind cfg) subject context))]
                 (if (nil? deadline)
                   {:ok? true}  ;; no deadline configured for this workflow
                   (let [boundary  (:boundary cfg)
                         event-ts  (epoch-second event-time)
                         expired? (case boundary
                                    :before     (>= event-ts (long deadline))
                                    :at-or-after (< event-ts (long deadline)))]
                     (if expired?
                       (cond-> {:ok? false
                                :error (:on-expired cfg)
                                :guard-context {:temporal/rule :deadline-enforcement
                                                :temporal/deadline-kind (:kind cfg)
                                                :temporal/event-time event-ts
                                                :temporal/deadline deadline
                                                :temporal/boundary-policy boundary
                                                :temporal/subject-id subject
                                                :temporal/decision :reject}}
                         (:rule-id cfg) (assoc :rule-id (:rule-id cfg)))
                       {:ok? true}))))
               {:ok? true}))}])

(defn effective-temporal-rules
  "Base temporal rules + optional protocol/context-provided rules.
   Extra rules must be maps with keys {:id kw :check (fn [ctx] -> {:ok? bool ...})}."
  [context]
  (let [extra (:temporal-rules context)
        extra' (if (sequential? extra) extra [])]
    (into temporal-rules extra')))

(defn evaluate-temporal-rules
  [rules ctx]
  (reduce (fn [_ {:keys [id check]}]
            (let [r (check ctx)]
              (if (:ok? r)
                nil
                (reduced (assoc r :rule-id (or (:rule-id r) id))))))
          nil
          rules))

(defn maybe-record-temporal!
  "Invoke optional :recorder from :temporal-evidence when collection is enabled."
  [temporal-cfg temporal-enabled? scenario-id outcome world metrics trace]
  (when (and temporal-enabled? (:recorder temporal-cfg))
    ((:recorder temporal-cfg)
     (:datasource temporal-cfg)
     temporal-cfg
     scenario-id
     outcome
     world
     metrics
     trace)))
