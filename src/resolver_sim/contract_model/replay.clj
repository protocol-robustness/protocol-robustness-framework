(ns resolver-sim.contract-model.replay
  "Open-world scenario replay proto. (Protocol Simulation Kernel)

   Provides the deterministic harness for executing scenarios. This engine
   is designed as a protocol-agnostic template. Implementation details
   (actions, invariants, snapshots) are protocol-specific and provided by
   implementations of the DisputeProtocol interface.

   Replay invariants (after every successful transition):
     1. protocol/check-invariants-single
     2. protocol/check-invariants-transition"
  (:require [clojure.set                       :as set]
            [clojure.data.json                 :as json]
            [clojure.java.io                   :as io]
            [resolver-sim.evidence.config      :as evcfg]
            [resolver-sim.evidence.capture    :as evcapture]
            [resolver-sim.contract-model.replay.metrics :as metrics]
            [resolver-sim.contract-model.replay.validation :as validation]
            [resolver-sim.contract-model.replay.analysis :as analysis]
            [resolver-sim.contract-model.replay.temporal :as temporal]
            [resolver-sim.contract-model.replay.flags :as replay-flags]
            [resolver-sim.contract-model.replay.checkpoints :as replay-checkpoints]
            [resolver-sim.contract-model.replay.execution :as execution]
            [resolver-sim.contract-model.replay.profile-adapter :as profile-adapter]
            [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.protocols.registry :as preg]
            [resolver-sim.util.attribution :as attr]
            [resolver-sim.yield.risk-monitor :as risk]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.evidence.timestamping :as ts]
            [resolver-sim.logging :as log]))

;; ---------------------------------------------------------------------------
;; JSON serialisation helpers (Generic)
;; ---------------------------------------------------------------------------

(defn- kw->json-key [k] (if (keyword? k) (name k) (str k)))
(defn- kw-val->str [_k v] (if (keyword? v) (name v) v))

;; ---------------------------------------------------------------------------
;; Agent Validation (Generic)
;; ---------------------------------------------------------------------------

(defn validate-agents [agents] (validation/validate-agents agents))

;; ---------------------------------------------------------------------------
;; Bridge functions (Legacy Sew support)
;; ---------------------------------------------------------------------------

(defn build-context
  "Build an execution context for a protocol.

   Protocols must implement DisputeProtocol (build-execution-context).

   One-arg and two-arg arities default to Sew for backward compatibility.
   Prefer the three-arg arity: (build-context protocol agents params)."
  ([agents params]
   (build-context (preg/get-protocol "sew-v1") agents params))
  ([protocol agents params]
   (proto/build-execution-context protocol agents params)))

(defn sew-dispatch-action
  "Deprecated: call proto/dispatch-action with a protocol instance directly.
   Bridge to proto/dispatch-action using SewProtocol."
  [context world event]
  (proto/dispatch-action (preg/get-protocol "sew-v1") context world event))

(defn sew-check-invariants-single
  "Deprecated: call proto/check-invariants-single with a protocol instance directly.
   Bridge to proto/check-invariants-single using SewProtocol."
  [world]
  (proto/check-invariants-single (preg/get-protocol "sew-v1") world))

(defn sew-check-invariants-transition
  "Deprecated: call proto/check-invariants-transition with a protocol instance directly.
   Bridge to proto/check-invariants-transition using SewProtocol."
  [world-before world-after]
  (proto/check-invariants-transition (preg/get-protocol "sew-v1") world-before world-after))

;; ---------------------------------------------------------------------------
;; Analysis & Result Interpretation
;; ---------------------------------------------------------------------------

(defn- normalize-error-value [error] (analysis/normalize-error-value error))
(defn- expected-error-key [x] (analysis/expected-error-key x))
(defn- rejected-entry-key [x] (analysis/rejected-entry-key x))
(defn- analyze-expected-errors [scenario trace] (analysis/analyze-expected-errors scenario trace))

(defn finalize-scenario-result
  ([scenario result] (analysis/finalize-scenario-result scenario result {}))
  ([scenario result flags] (analysis/finalize-scenario-result scenario result flags)))

;; ---------------------------------------------------------------------------
;; Temporal Instrumentation
;; ---------------------------------------------------------------------------

(defn- advance-world-time [world event-time] (temporal/advance-world-time world event-time))
(defn- effective-temporal-rules [context] (temporal/effective-temporal-rules context))
(defn- evaluate-temporal-rules [rules ctx] (temporal/evaluate-temporal-rules rules ctx))
(defn- maybe-record-temporal! [cfg enabled? id outcome world metrics trace] (temporal/maybe-record-temporal! cfg enabled? id outcome world metrics trace))

;; ---------------------------------------------------------------------------
;; Metrics — registry (must precede validate-scenario which references it)
;; ---------------------------------------------------------------------------

(def population-metrics metrics/population-metrics)
(def base-metrics metrics/base-metrics)
(defn- metric-key [x] (metrics/metric-key x))
(defn- falsifies-if-metric-refs [falsifies-if] (metrics/falsifies-if-metric-refs falsifies-if))
(defn- theory-metric-scope [scenario] (metrics/theory-metric-scope scenario))
(defn- action->transition-id [action] (analysis/action->transition-id action))

;; ---------------------------------------------------------------------------
;; Input validation (Generic scenario structure)
;; ---------------------------------------------------------------------------

(defn validate-scenario
  ([scenario] (validation/validate-scenario scenario metrics/base-metrics {}))
  ([scenario effective-metrics] (validation/validate-scenario scenario effective-metrics {}))
  ([scenario effective-metrics opts]
   (validation/validate-scenario scenario effective-metrics opts)))

;; ---------------------------------------------------------------------------
;; Metrics — accumulation
;; ---------------------------------------------------------------------------

(defn- zero-metrics [protocol] (metrics/zero-metrics protocol))
(defn- accum-metrics [protocol metrics event trace-entry agent-index world-before]
  (metrics/accum-metrics protocol metrics event trace-entry agent-index world-before))

(def process-step
  "Forwarding reference — moved to replay.execution."
  execution/process-step)

;; ---------------------------------------------------------------------------
;; Public API (Generic)
;; ---------------------------------------------------------------------------

(defn- expectation-metric-keys [scenario] (metrics/expectation-metric-keys scenario))

(defn trace-entry->replay-event
  "Strip trace metadata; return the minimal replay event shape.
   Delegates to execution/trace-entry->replay-event."
  [entry]
  (execution/trace-entry->replay-event entry))

(defn replay-events
  "Replay a scenario and return trace + metrics.

   Returns trace + metrics without evidence chain I/O or signing.
   Under default flags (:evidence-mode :all), protocol-level evidence
   capture still runs in memory; set :evidence-mode :none to suppress
   all evidence capture including protocol-level calls.

   Accepts optional opts map — passed through to `resolve-replay-flags`:
   - :flags   — replay flag overrides (see `replay.flags`)
   - :minimal — use minimal replay flags
   - :run-id  — identifier for the replay run

   Callers (e.g. `replay-with-protocol`) add evidence chain I/O, signing,
   and risk monitoring layers externally."
  [protocol scenario & [opts]]
  (let [flags              (replay-flags/resolve-replay-flags scenario opts)
        vocab              (if (satisfies? proto/EconomicModel protocol)
                             (proto/metric-vocabulary protocol)
                             #{})
        effective-metrics  (into (into metrics/base-metrics vocab)
                                 (or (metrics/expectation-metric-keys scenario) #{}))
        validation         (validate-scenario scenario effective-metrics
                                              {:strict-validation? (:strict-validation? flags)})
        temporal-cfg       (:temporal-evidence scenario)
        temporal-enabled?  (:temporal-enabled? flags)
        validation         (if (and (:ok validation) (:yield-dt-validation? flags))
                             (let [yield-val (requiring-resolve 'resolver-sim.contract-model.replay.yield/validate-dt-time-alignment)
                                   dt-check (yield-val (:events scenario []))]
                               (if (:ok dt-check) validation dt-check))
                             validation)]
    (if-not (:ok validation)
      {:outcome :invalid :scenario-id (:scenario-id scenario) :events-processed 0 :trace [] :metrics (metrics/zero-metrics protocol (:metrics-profile flags)) :halt-reason (:error validation) :protocol protocol}
      (binding [evcapture/*capture-event-evidence!* (if (= :none (:evidence-mode flags))
                                                      evcapture/noop-capture
                                                      evcapture/*capture-event-evidence!*)]
        (let [agents   (:agents scenario)
              p-params (get scenario :protocol-params {})
              context  (-> (proto/build-execution-context protocol agents p-params)
                           (assoc :replay-flags flags))
              agent-index (:agent-index context)
              scenario-id (:scenario-id scenario)
            ;; The execution loop derives per-event evidence attribution from
            ;; world parameters. Preserve the explicit input identity there
            ;; for every protocol before processing its first transition.
              world0  (assoc-in (proto/init-world protocol scenario)
                                [:params :scenario-id]
                                scenario-id)
              events  (sort-by :seq (:events scenario))
              expected-errors-set (set (map expected-error-key (:expected-errors scenario [])))
              strict-expected-errors? (boolean (:strict-expected-errors? scenario false))
              run-id  (or (:run-id opts) (:run-id scenario) (str scenario-id "-run"))
              options {:expected-errors-set expected-errors-set
                       :strict-expected-errors? strict-expected-errors?
                       :allow-open-entities? (:allow-open-entities? scenario)
                       :allow-open-disputes? (:allow-open-disputes? scenario)
                       :agents agents
                       :temporal-cfg temporal-cfg
                       :temporal-enabled? temporal-enabled?
                       :agent-index agent-index
                       :scenario scenario
                       :run-id run-id
                       :replay-flags flags}
              run-loop #(execution/run-simulation-loop protocol context scenario-id events world0 [] (metrics/zero-metrics protocol (:metrics-profile flags)) options)
              ;; Risk-event isolation: always run under a fresh risk context so
              ;; that :fail-on-short-circuits and the per-step loop check read only
              ;; events from this run, never stale events left in the shared atom
              ;; by an earlier direct replay-events call. The captured events are
              ;; attached to the result for the post-hoc policy check below.
              raw-result (risk/with-fresh-risk-context
                           (let [result (run-loop)]
                             (assoc result :yield/risk-events (risk/events))))
              trimmed-result (replay-checkpoints/apply-checkpoint-policy-to-result
                              (:world-checkpoint-policy flags)
                              raw-result)
              triggered (set (mapcat :short-circuits (:yield/risk-events trimmed-result)))
              forbidden (set (:fail-on-short-circuits flags))
              policy-result (if-let [matched (seq (set/intersection triggered forbidden))]
                              (assoc trimmed-result :outcome :fail
                                     :halt-reason :short-circuit-policy
                                     :short-circuit-violations (vec (sort matched)))
                              trimmed-result)
              finalized-result (if (:evaluate-expectations? flags true)
                                 (finalize-scenario-result scenario policy-result flags)
                                 policy-result)]
        ;; The simulation kernel may not retain this source-level identity in
        ;; its accumulator. Every replay result nevertheless has the explicit
        ;; scenario input available at this boundary, so preserve it before
        ;; protocol-neutral consumers construct entries or finalizations.
          (cond-> finalized-result
            (nil? (:scenario-id finalized-result))
            (assoc :scenario-id (:scenario-id scenario))))))))

(defn replay-with-protocol
  "Full replay plus evidence-chain, persistence, signing, timestamping and
   risk-monitor integration.

   Layers evidence chain, I/O, and risk monitoring on top of `replay-events`.
   Optional third argument `replay-opts` may include `:flags` (see `replay.flags`).
   Scenario `:options {:minimal true}` or `:options {:flags {...}}` merge the same way."
  ([protocol scenario] (replay-with-protocol protocol scenario {}))
  ([protocol scenario replay-opts]
   (chain/with-fresh-registry
     (chain/with-fresh-chain-cursor
       (risk/with-fresh-risk-context
         (let [scenario-id (:scenario-id scenario)
               run-id (or (:run-id replay-opts) (:run-id scenario) (str scenario-id "-run"))
               ;; Event evidence is emitted inside replay-events. Bind the
               ;; explicit input identity before entering that kernel so every
               ;; persisted record is attributable to its scenario and run.
               result (attr/with-attribution
                        {:ctx/scenario-id scenario-id
                         :ctx/run-id run-id}
                        (replay-events protocol scenario (assoc replay-opts :run-id run-id)))]
           (if (= :invalid (:outcome result))
             result
             (let [run-id (get-in result [:context/source :run-id])
                   scenario-id (or (:scenario-id result)
                                   (get-in result [:context/source :scenario-id]))]
               (attr/with-attribution
                 {:ctx/scenario-id scenario-id
                  :ctx/run-id run-id}
                 (attr/log-with-attr :info "scenario/start" {:id scenario-id}))
               (when-let [theory (:diagnostics result)]
                 (try
                   (let [f (io/file (evcfg/artifact-path :theory-eval))]
                     (.mkdirs (.getParentFile f))
                     (spit f (json/write-str theory {:indent true})))
                   (catch Exception e
                     (log/warn! :theory-diagnostics-write-failed
                                {:path (evcfg/artifact-path :theory-eval)
                                 :error (.getMessage e)}))))
               (when-not (:skip-finalize replay-opts)
                 (let [signing-key (or (:signing-key replay-opts)
                                       chain/*signing-key*
                                       (System/getenv "PRF_SIGNING_KEY"))
                       signing-pw (or (:signing-password replay-opts)
                                      chain/*signing-password*
                                      (System/getenv "PRF_SIGNING_PASSWORD"))
                       tsa-url (or (:tsa-url replay-opts)
                                   ts/*tsa-url*
                                   (System/getenv "PRF_TSA_URL"))
                       ;; Inner replay is permitted to preserve diagnostic evidence from
                       ;; a dirty checkout. Canonicality is decided separately from the
                       ;; resolved source provenance at the package boundary.
                       allow-dirty? (if (contains? replay-opts :allow-dirty?)
                                      (:allow-dirty? replay-opts)
                                      (if (nil? chain/*allow-dirty*)
                                        true
                                        chain/*allow-dirty*))]
                   (chain/finalize-and-attest!
                    :run-id run-id
                    :private-key-path signing-key
                    :password signing-pw
                    :tsa-url tsa-url
                    :allow-dirty? allow-dirty?)))
               (chain/register-scenario-snapshot!)
               ;; replay-events now captures risk events under its own fresh
               ;; context, so source :risk-events from the result rather than the
               ;; (now outer) live atom, which is empty again after the inner run.
               (assoc result :risk-events (:yield/risk-events result))))))))))

(defn replay-yield-scenario
  "INTERNAL COMPATIBILITY ADAPTER — delegates to replay.yield/replay-yield-scenario.

   This is a thin bridge for existing callers that imported
   `resolver-sim.contract-model.replay/replay-yield-scenario`.
   Prefer `simple-replay` for new code.

   Removal condition: all callers migrated to simple-replay."
  ([scenario] ((requiring-resolve 'resolver-sim.contract-model.replay.yield/replay-yield-scenario) scenario))
  ([protocol scenario] ((requiring-resolve 'resolver-sim.contract-model.replay.yield/replay-yield-scenario) protocol scenario)))

(defn prepare-simple-scenario
  "Prepare a scenario for simple replay by applying defaults.

   Currently defaults missing :schema-version to \"1.0\".
   Returns {:scenario <prepared-map> :normalizations [<map>...]}.

   Normalization entries have the shape:
     {:field :schema-version :value \"1.0\" :reason :simple-replay-default}

   This is a pure, deterministic function. The input is not mutated."
  [scenario]
  (when-not (map? scenario)
    (throw (ex-info "Simple replay scenario must be a map"
                    {:type :invalid-simple-replay-scenario
                     :replay-profile :simple
                     :expected :map
                     :actual-type (str (class scenario))})))
  (let [has-version? (contains? scenario :schema-version)]
    (if has-version?
      {:scenario scenario
       :normalizations []}
      {:scenario (assoc scenario :schema-version "1.0")
       :normalizations [{:field :schema-version
                         :value "1.0"
                         :reason :simple-replay-default}]})))

(defn normalize-simple-result
  "Add common simple-replay result metadata to a raw result.

   Applies to all outcomes (pass, fail, invalid):
   - :replay-profile :simple
   - :protocol-id (string)
   - :execution descriptor (profile + engine)
   - :context/version and :context/source (if not present)
   - :scenario-normalizations vector

   Does not overwrite existing :outcome, :trace, :metrics or :halt-reason."
  [result protocol scenario-normalizations execution-descriptor & [run-id]]
  (let [protocol-id (proto/protocol-id protocol)
        scenario-id (:scenario-id result)
        effective-run-id (or run-id
                             (get-in result [:context/source :run-id])
                             (str scenario-id "-simple-run"))
        base {:replay-profile :simple
              :protocol-id protocol-id
              :execution execution-descriptor}
        base (if (seq scenario-normalizations)
               (assoc base :scenario-normalizations scenario-normalizations)
               base)
        base (if (or (not (:context/version result))
                     (not (:context/source result)))
               (assoc base :context/version "1.0"
                      :context/source {:scenario-id scenario-id
                                       :run-id effective-run-id})
               base)]
    (merge result base)))

(defn simple-replay
  "Replay a scenario under the lightweight (:simple) replay profile.

   Two explicit arities:
     (simple-replay protocol scenario)
     (simple-replay protocol scenario replay-opts)

   The simple profile:
   - Disables temporal enforcement and theory DSL evaluation by default
   - Uses relaxed structural validation by default
   - Keeps invariant checks and expected-error / scenario-expectation processing
     enabled unless a safe caller flag changes them
   - Skips evidence, persistence, signing, timestamping, and checkpoints
   - Suppresses protocol-level capture-event-evidence! calls via the no-op
     capture binding
   - Skips risk-monitor side effects

   Dispatches protocol-specific execution through a single execution plan that
   supplies both the runner and its published execution descriptor. The default
   plan uses replay-events; protocol-specific plans (such as yield-v1) may
   enforce additional safe execution flags.

   Replay-opts currently supports:
   - :run-id — identifier for the replay run
   - :flags  — replay flag overrides (merged with minimal defaults)

   Prohibited opts (throws ex-info): :evidence-mode, :signing-key,
   :signing-password, :tsa-url, :skip-finalize, :allow-dirty?"
  ([protocol scenario]
   (simple-replay protocol scenario nil))
  ([protocol scenario replay-opts]
   (let [prep         (prepare-simple-scenario scenario)
         prepared     (:scenario prep)
         normalizations (:normalizations prep)
         simple-opts  (profile-adapter/extract-simple-opts replay-opts :simple-replay)
         execution-plan (profile-adapter/simple-execution-plan :simple protocol)
         raw-result   ((:run execution-plan) protocol prepared simple-opts)
         validated-result (profile-adapter/validate-simple-adapter-result!
                           raw-result
                           (get-in execution-plan [:execution :adapter/id] :canonical))
         execution-descriptor (:execution execution-plan)]
     (normalize-simple-result validated-result protocol normalizations execution-descriptor (:run-id simple-opts)))))
(defn resume-from-snapshot
  "Resume a simulation from a world snapshot and a sequence of events.
   Useful for exploring counterfactual subgames."
  [protocol agents p-params scenario-id world events trace metrics options]
  (let [context  (proto/build-execution-context protocol agents p-params)
        agent-index (:agent-index context)
        metrics' (if (seq metrics) metrics (metrics/zero-metrics protocol))
        expected-errors-set (set (map expected-error-key (:expected-errors (:scenario options) [])))
        strict-expected-errors? (boolean (:strict-expected-errors? (:scenario options) false))
        temporal-cfg (:temporal-evidence (:scenario options))
        temporal-enabled? (boolean (:enabled? temporal-cfg))
        run-id (or (:run-id options) (str scenario-id "-resume"))]
    (execution/run-simulation-loop protocol context scenario-id events world trace metrics'
                                   (merge {:expected-errors-set expected-errors-set
                                           :strict-expected-errors? strict-expected-errors?
                                           :allow-open-entities? true
                                           :allow-open-disputes? true
                                           :agents agents
                                           :temporal-cfg temporal-cfg
                                           :temporal-enabled? temporal-enabled?
                                           :agent-index agent-index
                                           :run-id run-id}
                                          options))))

(defn result->json-str
  "Serialize a replay result to a JSON string."
  [result]
  ((requiring-resolve 'resolver-sim.io.serialization/serialize-artifact) (dissoc result :protocol)))

;; ---------------------------------------------------------------------------
;; Verification & Determinism
;; ---------------------------------------------------------------------------

(defn replay-idempotent-same-trace?
  "Run the same scenario twice and check deterministic equivalence of key outputs.
   Returns:
     {:idempotent? bool
      :first result
      :second result}

   Equivalence checks:
   - :outcome
   - :halt-reason
   - :events-processed
   - trace result/error sequence
   - final world snapshot in trace tail"
  [protocol scenario]
  (let [r1 (replay-with-protocol protocol scenario)
        r2 (replay-with-protocol protocol scenario)
        trace-shape (fn [r] (mapv (juxt :seq :result :error) (:trace r)))
        last-world  (fn [r] (:world (last (:trace r))))
        eq? (and (= (:outcome r1) (:outcome r2))
                 (= (:halt-reason r1) (:halt-reason r2))
                 (= (:events-processed r1) (:events-processed r2))
                 (= (trace-shape r1) (trace-shape r2))
                 (= (last-world r1) (last-world r2)))]
    {:idempotent? eq?
     :first r1
     :second r2}))
