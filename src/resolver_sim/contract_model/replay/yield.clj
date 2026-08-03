(ns resolver-sim.contract-model.replay.yield
  "Sequential replay for yield-v1 — no batch/alias/temporal/theory branches.

   Validates that each `yield_accrue` event's `:dt` matches the `:time` delta
   from the previous event. Provider scenarios should use `simple-replay`
   (which routes yield-v1 through the canonical `replay-events` loop).

   Legacy entry point: `replay-yield-scenario` for callers that need
   risk-monitor side effects."
  (:require [resolver-sim.logging :as log]
            [resolver-sim.contract-model.replay.analysis :as analysis]
            [resolver-sim.contract-model.replay.execution :as execution]
            [resolver-sim.contract-model.replay.flags :as replay-flags]
            [resolver-sim.contract-model.replay.metrics :as metrics]
            [resolver-sim.contract-model.replay.validation :as validation]
            [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.protocols.yield :as yp]
            [resolver-sim.yield.risk-monitor :as risk]))

(def ^:private yield-replay-flags replay-flags/minimal-replay-flags)

(defn- action-name [event]
  (let [a (:action event)]
    (if (keyword? a) (name a) (str a))))

(defn- yield-accrue-event? [event]
  (= "yield_accrue" (action-name event)))

(defn validate-dt-time-alignment
  "For yield_accrue events, require params :dt to equal (:time - prev-time)."
  [events]
  (let [sorted (sort-by :seq events)
        violations
        (loop [prev nil events sorted acc []]
          (if (empty? events)
            acc
            (let [e    (first events)
                  acc' (if (and prev (yield-accrue-event? e))
                         (let [dt         (get-in e [:params :dt])
                               time-delta (- (:time e) (:time prev))]
                           (if (and (number? dt) (not= (long dt) (long time-delta)))
                             (conj acc {:seq (:seq e)
                                        :dt dt
                                        :time-delta time-delta
                                        :prev-seq (:seq prev)
                                        :prev-time (:time prev)
                                        :time (:time e)})
                             acc))
                         acc)]
              (recur e (rest events) acc'))))]
    (if (seq violations)
      {:ok false
       :error :dt-time-mismatch
       :detail {:hint "yield_accrue :dt must match the event :time delta from the prior event"
                :violations violations}}
      {:ok true})))

(defn validate-yield-scenario
  "Structural checks for provider-only scenarios (relaxed schema, strict time/dt)."
  [scenario]
  (let [effective (into metrics/base-metrics (or (metrics/expectation-metric-keys scenario) #{}))
        base      (validation/validate-scenario scenario effective {:strict-validation? false})
        align     (validate-dt-time-alignment (:events scenario []))]
    (cond
      (not (:ok base)) base
      (not (:ok align)) align
      :else {:ok true})))

(defn- run-yield-loop
  "INTERNAL — sequential loop for yield replay.
   Used only by the legacy `replay-yield-scenario` wrapper. The profile-adapter
   path (simple-replay) now uses `execution/run-simulation-loop` via replay-events."
  [protocol context scenario-id events world0]
  (loop [world world0
         events (sort-by :seq events)
         trace  []
         metrics (metrics/zero-metrics protocol)]
    (if (empty? events)
      {:outcome :pass
       :scenario-id scenario-id
       :events-processed (count trace)
       :trace trace
       :metrics metrics
       :execution {:mode :yield-sequential}
       :protocol protocol
       :world world}
      (let [event (first events)
            step   (execution/process-step protocol context world event)
            {:keys [ok? world trace-entry halted?]} step
            metrics' (metrics/accum-metrics protocol metrics event trace-entry
                                            (:agent-index context) world)
            trace'   (conj trace trace-entry)]
        (if halted?
          {:outcome :fail
           :scenario-id scenario-id
           :events-processed (count trace')
           :halted-at-seq (:seq event)
           :halt-reason :invariant-violation
           :trace trace'
           :metrics metrics'
           :execution {:mode :yield-sequential}
           :protocol protocol
           :world world}
          (recur world (rest events) trace' metrics'))))))

(defn replay-yield-scenario
  "Legacy wrapper: replay a yield-provider scenario with risk-monitor instrumentation.

   Calls `run-yield-loop` (the thin sequential loop) and adds risk-monitor side
   effects (`risk/clear!`, `risk/events`) around it.  `protocol` defaults to
   `yield-v1`.

   Result shape matches `replay-with-protocol` enough for `scenario.runner`
   and expectation finalization.

   NOTE: This is the legacy path. New callers should use `simple-replay` or
   `replay-events` with `{:flags {:yield-dt-validation? true
                                  :metrics-profile :yield-provider}}`."
  ([scenario] (replay-yield-scenario yp/protocol scenario))
  ([protocol scenario]
   (risk/clear!)
   (log/info! "yield-replay/start" {:id (:scenario-id scenario)})
   (let [validation (validate-yield-scenario scenario)]
     (if-not (:ok validation)
       (let [result {:outcome :invalid
                     :scenario-id (:scenario-id scenario)
                     :events-processed 0
                     :trace []
                     :metrics (metrics/zero-metrics protocol)
                     :halt-reason (:error validation)
                     :detail (:detail validation)
                     :protocol protocol}]
         (log/info! "yield-replay/end" {:id (:scenario-id scenario) :outcome :invalid})
         (assoc result :risk-events (risk/events)))
       (let [agents      (:agents scenario)
             p-params    (get scenario :protocol-params {})
             scenario-id (:scenario-id scenario)
             run-id (or (:run-id scenario) (str scenario-id "-run"))
             context     (-> (proto/build-execution-context protocol agents p-params)
                             (assoc :replay-flags yield-replay-flags
                                    :run-id run-id))
             world0      (-> (proto/init-world protocol scenario)
                             (assoc-in [:params :scenario-id] scenario-id))
             events      (:events scenario)
             raw (run-yield-loop protocol context scenario-id events world0)
             result (analysis/finalize-scenario-result scenario raw yield-replay-flags)]
         (log/info! "yield-replay/end" {:id scenario-id :outcome (:outcome result)})
         (assoc result :risk-events (risk/events)))))))
