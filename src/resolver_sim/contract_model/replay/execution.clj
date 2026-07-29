(ns resolver-sim.contract-model.replay.execution
  (:require [clojure.set :as set]
            [clojure.stacktrace :as st]
            [clojure.string :as str]
            [resolver-sim.contract-model.replay.metrics :as metrics]
            [resolver-sim.contract-model.replay.analysis :as analysis]
            [resolver-sim.contract-model.replay.temporal :as temporal]
            [resolver-sim.contract-model.replay.flags :as replay-flags]
            [resolver-sim.contract-model.replay.checkpoints :as replay-checkpoints]
            [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.evidence.node :as evidence-node]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.util.evidence :as ev]
            [resolver-sim.util.attribution :as attr]
            [resolver-sim.time.context :as time-ctx]
            [resolver-sim.logging :as log]
            [resolver-sim.yield.risk-monitor :as risk]))

(defn- yield-accounting-action?
  [action]
  (or (= action "trigger-accrue")
      (= action "claim-deferred-yield")
      (str/includes? (or action "") "yield")))

(defn- numeric-map-delta [before after]
  (into {}
        (for [k (into (set (keys (or before {}))) (keys (or after {})))
              :let [delta (- (long (get (or after {}) k 0))
                             (long (get (or before {}) k 0)))]
              :when (not (zero? delta))]
          [k delta])))

(defn- claimable-total [world]
  (reduce + 0
          (for [[_ domains] (:claimable-v2 world {})
                [_ recipients] domains
                [_ amount] recipients]
            (long amount))))

(defn- yield-accounting-delta [world-before world-after]
  {:held-by-token (numeric-map-delta (:total-held world-before)
                                     (:total-held world-after))
   :fees-by-token (numeric-map-delta (:total-fees world-before)
                                     (:total-fees world-after))
   :claimable-delta (- (claimable-total world-after) (claimable-total world-before))
   :held-adjustment-ids (mapv :held-adjustment/id
                              (drop (count (:held-adjustments world-before))
                                    (:held-adjustments world-after)))})

(defn- emit-yield-execution-node! [event accounting-delta]
  (try
    (evidence-node/emit-execution-node!
     {:execution-id :execution/yield-accounting
      :status :pass
      :inputs {:event/seq (:seq event)
               :event/action (:action event)
               :event/params (:params event)}
      :outputs accounting-delta
      :extensions {:yield/accounting-delta accounting-delta}
      :execution-kind :yield-accounting
      :runner :replay-kernel})
    (catch Exception e
      (log/error! :yield-execution-node-failed
                  {:seq (:seq event) :action (:action event) :error (.getMessage e)})
      nil)))

;; ---------------------------------------------------------------------------
;; Action Dispatch with Evidence
;; ---------------------------------------------------------------------------

(defn- evidence-mode-allows?
  "Check whether `evidence-type` is allowed under the given replay flags'
   `:evidence-mode`.  `:all` permits everything; `:essential` permits only
   `:transition` and `:invariant-failure`; `:none` permits nothing."
  [flags evidence-type]
  (case (:evidence-mode flags :all)
    :all       true
    :essential (#{:transition :invariant-failure} evidence-type)
    :none      false))

(defn apply-action-with-evidence
  "Dispatch an action through the protocol layer and emit a content-hashed
   evidence record for the transition.

   Call shape mirrors proto/dispatch-action:
     (apply-action-with-evidence protocol context world event)
     => {:ok bool? :world world' :error kw? :extra map? :evidence map?}

   The :evidence key in the result contains the evidence record (or nil if
   attribution context was insufficient for evidence emission). The dispatch
   itself always proceeds — evidence is best-effort at this layer.

   Evidence emission is conditional on the `:evidence-mode` replay flag."
  [protocol context world event]
  (let [action (:action event)
        pre-world world
        result (proto/dispatch-action protocol context world event)
        post-world (:world result)
        flags  (or (:replay-flags context) replay-flags/default-replay-flags)
        evidence (when (and (map? post-world)
                            (evidence-mode-allows? flags :transition))
                   (try
                     (attr/with-attribution
                       {:ctx/step (:seq event)
                        :ctx/event-id (str (:seq event))}
                       (ev/emit-evidence!
                        {:artifact-kind (if (:error result) :transition-error :transition)
                         :block-time (:block-time pre-world)
                         :step (:seq event)
                         :before pre-world
                         :after post-world
                         :action action
                         :result (dissoc result :world)}))
                     (catch Exception e
                       (log/error! :evidence-emission-failed
                                   {:action action :step (:seq event) :error (.getMessage e)})
                       nil)))
        _ (when evidence (chain/register-evidence! evidence))]
    (assoc result :evidence evidence)))

;; ──────────────────────────────────────────────────────────────────────────────
;; Invariant Attestation Evidence (Evidence Layer 2)
;; ──────────────────────────────────────────────────────────────────────────────

(defn build-invariant-attestation
  "Build an invariant attestation map from check-all results.
   Returns nil if no invariants were checked."
  [step inv-single inv-trans check-inv?]
  (when check-inv?
    (let [extract-results (fn [result-map]
                            (when-let [results (:results result-map)]
                              (mapv (fn [[id r]]
                                      {:id (if (keyword? id) id (keyword (str id)))
                                       :result (if (:holds? r) :pass :fail)})
                                    (sort-by key results))))
          single-results (when (map? inv-single) (extract-results inv-single))
          trans-results  (when (map? inv-trans) (extract-results inv-trans))
          all-results    (remove nil? (concat single-results trans-results))
          passed (count (filter #(= :pass (:result %)) all-results))
          failed (count (filter #(= :fail (:result %)) all-results))]
      {:step step
       :invariants all-results
       :passed passed
       :failed failed})))

(defn emit-invariant-attestation!
  "Build and chain an invariant attestation evidence record.
   Best-effort — failures are logged but do not halt execution.
   The replay-ctx provides :ctx/event-index and :ctx/evidence-hash."
  [replay-ctx inv-single inv-trans check-inv?]
  (let [step (:ctx/event-index replay-ctx)
        evidence-hash (:ctx/evidence-hash replay-ctx)]
    (when-let [attestation (build-invariant-attestation step inv-single inv-trans check-inv?)]
      (try
        (let [safe-map {:step step
                        :passed (:passed attestation)
                        :failed (:failed attestation)
                        :invariants (mapv (fn [i] [(name (:id i)) (name (:result i))])
                                          (:invariants attestation))}
              h (hc/hash-with-intent {:hash/intent :invariant-attestation} safe-map)
              evidence {:artifact-kind :invariant-attestation
                        :evidence-hash h
                        :attestation/step step
                        :attestation/passed (:passed attestation)
                        :attestation/failed (:failed attestation)}
              evidence (if evidence-hash
                         (assoc evidence :attestation/evidence-hash evidence-hash)
                         evidence)]
          (chain/register-evidence! evidence))
        (catch Exception e
          (log/error! :invariant-attestation-failed
                      {:step step :error (.getMessage e)}))))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Projection Evidence (Evidence Layer 8)
;; ──────────────────────────────────────────────────────────────────────────────

(defn emit-projection-evidence!
  "Build and chain a projection evidence record.
   Best-effort — failures are logged but do not halt execution.
   The replay-ctx must contain :ctx/event-index, :ctx/world-hash,
   and :ctx/projection-hash."
  [replay-ctx]
  (let [step (:ctx/event-index replay-ctx)
        world-hash (:ctx/world-hash replay-ctx)
        projection-hash (:ctx/projection-hash replay-ctx)]
    (when projection-hash
      (try
        (let [data {:step step
                    :world-hash world-hash
                    :projection-hash projection-hash
                    :projection-version 1}
              h (hc/hash-with-intent {:hash/intent :projection-evidence} data)
              evidence {:artifact-kind :projection-evidence
                        :evidence-hash h
                        :projection/step step
                        :projection/world-hash world-hash
                        :projection/projection-hash projection-hash}]
          (chain/register-evidence! evidence))
        (catch Exception e
          (log/error! :projection-evidence-failed
                      {:step step :error (.getMessage e)}))))))

;; ---------------------------------------------------------------------------
;; Invariant Failure Evidence (Evidence Layer 6)
;; ---------------------------------------------------------------------------

(defn emit-invariant-failure-evidence!
  "Build and chain an invariant failure evidence record when a scenario halts.
   Best-effort — failures are logged but do not halt execution."
  [step scenario-id violations]
  (when (seq violations)
    (try
      (let [inv-ids (vec (map (fn [[k _]] (if (keyword? k) (name k) (str k))) violations))
            safe-details (into {} (map (fn [[k v]] [(name k) (str v)]) (take 5 violations)))
            data {:step step
                  :scenario-id scenario-id
                  :invariant-ids inv-ids
                  :details safe-details
                  :halt-reason :invariant-violation}
            h (hc/hash-with-intent {:hash/intent :invariant-failure} data)
            evidence {:artifact-kind :invariant-failure
                      :evidence-hash h
                      :failure/step step
                      :failure/invariant-ids inv-ids
                      :failure/scenario-id scenario-id}]
        (chain/register-evidence! evidence))
      (catch Exception e
        (log/error! :invariant-failure-evidence-failed
                    {:step step :error (.getMessage e)})))))

;; ---------------------------------------------------------------------------
;; Execution Mode & Batch Helpers
;; ---------------------------------------------------------------------------

(defn- execution-mode
  [scenario]
  (keyword (or (:execution-mode scenario) :sequential)))

(def ^:private batch-commit-policy :deterministic-first-wins)

(defn- event-conflict-domains*
  [protocol world event agent-index]
  (let [domains (when (satisfies? proto/BatchConflictModel protocol)
                  (proto/event-conflict-domains protocol world event agent-index))]
    (if (seq domains)
      (set domains)
      #{[:global :unknown]})))

(defn- group-same-time-bucket
  [events]
  (let [t (:time (first events))]
    (split-with #(= t (:time %)) events)))

(defn- conflict-rejection-entry
  [protocol world-before event preflight-status conflict-domain conflict-with-seq flags]
  (let [[proj ph] (if (and (satisfies? proto/AnalysisModule protocol)
                           (or (= (:projection-mode flags) :full)
                               (= (:op/type event) :scenario/end)))
                    (proto/compute-projection protocol world-before)
                    [nil nil])
        tags      (if (satisfies? proto/EconomicModel protocol)
                    (proto/classify-event protocol event :rejected :batch-conflict)
                    #{})]
    {:seq               (:seq event)
     :time              (:time event)
     :time-before       {:block-ts (:block-time world-before)}
     :time-after        {:block-ts (:time event)}
     :agent             (:agent event)
     :action            (:action event)
     :params            (:params event)
     :transition/id     (analysis/action->transition-id (:action event))
     :result            :rejected
     :error             :batch-conflict
     :reject-phase      :batch-commit
     :reject-class      :batch-conflict
     :commit-policy     batch-commit-policy
     :preflight-status  preflight-status
     :commit-status     :rejected
     :conflict-domain   conflict-domain
     :conflict-with-seq conflict-with-seq
     :event-tags        tags
     :invariants-ok?    true
     :violations        nil
     :world             (proto/world-snapshot protocol world-before)
     :projection        proj
     :projection-hash   ph}))

;; ---------------------------------------------------------------------------
;; Step Processing (Kernel)
;; ---------------------------------------------------------------------------

(defn process-step
  "Apply one scenario event using tiered Protocol implementations.
   Wraps dispatch in with-attribution so downstream yield accrual, invariant
   checks, and logging automatically carry event-level context."
  [protocol context world event]
  (let [flags        (or (:replay-flags context) replay-flags/default-replay-flags)
        temporal-on? (let [v (:temporal-enabled? flags)] (if (nil? v) true (boolean v)))
        check-inv?   (:check-invariants? flags true)
        event-time   (:time event)
        now          (time-ctx/block-ts world)
        time-before  {:block-ts now}
        run-id       (:run-id context)
        rules        (temporal/effective-temporal-rules context)
        temporal-failure (when temporal-on?
                           (temporal/evaluate-temporal-rules rules
                                                             {:event-time event-time
                                                              :now now
                                                              :world world
                                                              :event event
                                                              :context context
                                                              :protocol protocol}))]
    (if temporal-failure
      (let [[proj ph] (if (satisfies? proto/AnalysisModule protocol)
                        (proto/compute-projection protocol world)
                        [nil nil])
            tags      (if (satisfies? proto/EconomicModel protocol)
                        (proto/classify-event protocol event :rejected (:error temporal-failure))
                        #{})]
        {:ok?    true
         :world  world
         :trace-entry {:seq             (:seq event)
                       :time            event-time
                       :time-before     time-before
                       :time-after      {:block-ts now}
                       :agent           (:agent event)
                       :action          (:action event)
                       :params          (:params event)
                       :save-id-as      (:save-id-as event)
                       :transition/id   (analysis/action->transition-id (:action event))
                       :result          :rejected
                       :error           (:error temporal-failure)
                       :temporal-rule-id (:rule-id temporal-failure)
                       :extra           nil
                       :guard-context   (:guard-context temporal-failure)
                       :event-tags      tags
                       :invariant-phase :temporal-rule
                       :invariants-ok?  true
                       :violations      nil
                       :world           (proto/world-snapshot protocol world)
                       :projection      proj
                       :projection-hash ph}
         :halted? false})

      (let [{world-t :world} (temporal/advance-world-time world event-time)
            time-after       {:block-ts (time-ctx/block-ts world-t)}
            result     (attr/with-attribution
                         {:ctx/scenario-id (get-in world [:params :scenario-id])
                          :ctx/run-id      run-id
                          :ctx/event-index (:seq event)
                          :ctx/event-type  (:action event)}
                         (try
                           (apply-action-with-evidence protocol context world-t event)
                           (catch Exception e
                             (attr/log-with-attr :error "dispatch exception"
                                                 {:error (.getMessage e)
                                                  :scenario-step (:seq event)
                                                  :action (:action event)})
                             (.printStackTrace e)
                             {:ok false :error :dispatch-exception :evidence nil
                              :detail {:message (.getMessage e)
                                       :stack   (with-out-str (st/print-stack-trace e))}})))
            ok?        (:ok result)
            world-next (if (and ok? (:world result)) (:world result) world-t)

            inv-single (when (and ok? check-inv?)
                         (proto/check-invariants-single protocol world-next))
            inv-trans  (when (and ok? check-inv?)
                         (proto/check-invariants-transition protocol world-t world-next))
            violated?  (and ok? check-inv?
                            (not (and (:ok? inv-single) (:ok? inv-trans))))
            all-violations (when violated?
                             (merge (when-not (:ok? inv-single) (:violations inv-single))
                                    (when-not (:ok? inv-trans)  (:violations inv-trans))))]

        ;; Assemble base runtime context from values available before projection.
        ;; Invariant attestation uses this; projection fields are enriched below.
        (let [replay-ctx {:ctx/event-index (:seq event)
                          :ctx/evidence-hash (:evidence-hash (:evidence result))}]
          ;; Emit invariant attestation evidence (best-effort, :all evidence-mode only)
          ;; Links back to the transition evidence from apply-action-with-evidence
          ;; Preserved at original position — before final-world / ph computation.
          (when (evidence-mode-allows? flags :invariant-attestation)
            (emit-invariant-attestation! replay-ctx inv-single inv-trans
                                         (and ok? check-inv?)))

          (let [result-kw    (cond violated? :invariant-violated ok? :ok :else :rejected)
                error-kw     (when-not ok? (:error result))
                event-tags   (if (satisfies? proto/EconomicModel protocol)
                               (proto/classify-event protocol event result-kw error-kw)
                               #{})
                final-world  (if violated? world-t world-next)
                [proj ph]    (if (satisfies? proto/AnalysisModule protocol)
                               (proto/compute-projection protocol final-world)
                               [nil nil])
                metadata     (if (satisfies? proto/AnalysisModule protocol)
                               (proto/classify-transition protocol (:action event) result-kw)
                               nil)
                yield-delta  (when (and ok? (yield-accounting-action? (:action event)))
                               (yield-accounting-delta world-t final-world))
                yield-node   (when (and yield-delta
                                        (evidence-mode-allows? flags :execution-node))
                               (emit-yield-execution-node! event yield-delta))]
            ;; Enrich context with projection fields once available
            (let [projection-ctx (assoc replay-ctx
                                        :ctx/projection-hash ph
                                        :ctx/world-hash
                                        (hc/hash-with-intent
                                         {:hash/intent :world-structure}
                                         final-world))]
              ;; Emit projection evidence (best-effort, :all evidence-mode only)
              (when (and ph (evidence-mode-allows? flags :projection))
                (emit-projection-evidence! projection-ctx))
              {:ok?    (and ok? (not violated?))
               :world  final-world
               :trace-entry
               {:seq             (:seq event)
                :time            event-time
                :time-before     time-before
                :time-after      time-after
                :agent           (:agent event)
                :action          (:action event)
                :params          (:params event)
                :save-id-as      (:save-id-as event)
                :transition/id   (analysis/action->transition-id (:action event))
                :transition/hash (:ctx/evidence-hash replay-ctx)
                :result          result-kw
                :error           error-kw
                :extra           (:extra result)
                :detail          (:detail result)
                :event-tags      event-tags
                :invariant-phase :post-event
                :invariants-ok?  (if (and ok? check-inv?)
                                   (and (:ok? inv-single) (:ok? inv-trans))
                                   true)
                :violations      all-violations
                :trace-metadata  metadata
                :yield/accounting-delta yield-delta
                :yield/execution-node-hash (:node-hash yield-node)
                :world           (proto/world-snapshot protocol final-world)
                :projection      proj
                :projection-hash (:ctx/projection-hash projection-ctx)
                :guard-context   (:guard-context result)}
               :halted? violated?})))))))

;; ---------------------------------------------------------------------------
;; Trace utilities
;; ---------------------------------------------------------------------------

(defn trace-entry->replay-event
  "Strip trace metadata; return the minimal replay event shape.
   Used by counterfactual fork replay so continuation steps do not carry
   stale :world / :result fields from the main-line trace."
  [entry]
  (select-keys entry [:seq :time :agent :action :params :save-id-as]))

;; ---------------------------------------------------------------------------
;; Simulation Loop
;; ---------------------------------------------------------------------------

(defn run-simulation-loop
  "INTERNAL KERNEL — execute the core simulation loop from a given world state
   and event sequence.

   This is an internal function. Prefer `replay-events` or `replay-with-protocol`
   in `resolver-sim.contract-model.replay` for the public API.  `resume-from-snapshot`
   and this kernel form the low-level entry points for counterfactual and forked replays."
  [protocol context scenario-id events world trace metrics options]
  (let [{:keys [expected-errors-set strict-expected-errors?
                allow-open-entities? allow-open-disputes?
                agents temporal-cfg temporal-enabled? agent-index
                scenario replay-flags run-id]} options
        context (assoc context :run-id run-id)
        check-inv? (:check-invariants? (or replay-flags replay-flags/default-replay-flags) true)
        metrics-profile (:metrics-profile replay-flags :sew-integrated)
        supports-alias? (satisfies? proto/SimulationAdapter protocol)]
    (loop [world world
           events events
           trace trace
           metrics metrics
           states {(:seq (first events) 0) (proto/world-snapshot protocol world)}
           world-checkpoints {}
           checkpoint-log []
           diagnostics {}
           id-alias-map {}]
      (if (empty? events)
        (let [open (when-not (or allow-open-entities? allow-open-disputes?)
                     (seq (proto/open-entities protocol world)))]
          (if open
            {:outcome :fail :scenario-id scenario-id :events-processed (count trace) :halt-reason :open-entities-at-end :detail {:open-entities (vec open)} :trace trace :metrics metrics :agents agents :protocol protocol :last-valid-world world}
            (do
              (temporal/maybe-record-temporal! temporal-cfg temporal-enabled? scenario-id :pass world metrics trace)
              (let [expected-error-analysis (analysis/analyze-expected-errors scenario trace)
                    expected-errors-mismatch? (and strict-expected-errors?
                                                   (not (:ok? expected-error-analysis)))
                    outcome (if expected-errors-mismatch? :fail :pass)
                    halt-reason (when expected-errors-mismatch? :expected-error-mismatch)]
                (attr/with-attribution
                  {:ctx/scenario-id scenario-id
                   :ctx/run-id run-id}
                  (attr/log-with-attr :info "scenario/end" {:id scenario-id :outcome outcome}))
                {:context/version "1.0"
                 :context/source {:scenario-id scenario-id :run-id run-id}
                 :execution {:mode (execution-mode scenario)
                             :batch-policy (when (= :deterministic-batch (execution-mode scenario))
                                             batch-commit-policy)}
                 :outcome outcome
                 :events-processed (count trace)
                 :halt-reason halt-reason
                 :expected-error-analysis expected-error-analysis
                 :trace trace
                 :metrics metrics
                 :states states
                 :agents agents
                 :protocol protocol
                 :world world
                 :world-checkpoints world-checkpoints
                 :checkpoint-log checkpoint-log
                 :diagnostics diagnostics
                 :id-alias-map id-alias-map}))))
        (if (= :deterministic-batch (execution-mode scenario))
          (let [[bucket rest-events] (group-same-time-bucket events)
                base-world world
                resolved-bucket (mapv (fn [raw]
                                        (if (and supports-alias? (seq id-alias-map))
                                          (let [res (proto/resolve-id-alias protocol raw id-alias-map)]
                                            (if (:ok res) (:event res) raw))
                                          raw))
                                      bucket)
                batch-time (:time (first resolved-bucket))
                metrics' (-> metrics
                             (update :batch-buckets inc)
                             (update :batch-events + (count resolved-bucket)))
                preflight (into {}
                                (map (fn [ev]
                                       (let [s (process-step protocol context base-world ev)]
                                         [(:seq ev)
                                          (if (= :ok (get-in s [:trace-entry :result])) :eligible :ineligible)])))
                                bucket)
                batch-result (reduce
                              (fn [acc raw-event]
                                (if (:halted? acc)
                                  acc
                                  (let [event (if (and supports-alias? (seq (:id-alias-map acc)))
                                                (let [res (proto/resolve-id-alias protocol raw-event (:id-alias-map acc))]
                                                  (if (:ok res) (:event res) raw-event))
                                                raw-event)
                                        working-world (:world acc)
                                        domains (event-conflict-domains* protocol working-world event agent-index)
                                        conflict-domain (some #(when (contains? (:claimed-domains acc) %) %) domains)
                                        winner-seq (get (:claimed-domains acc) conflict-domain)
                                        pre-status (get preflight (:seq event) :ineligible)]
                                    (if conflict-domain
                                      (let [entry (-> (conflict-rejection-entry protocol working-world event pre-status conflict-domain winner-seq replay-flags)
                                                      (assoc :expected-failure?
                                                             (contains? expected-errors-set
                                                                        [(:seq event) (:action event) :batch-conflict])))]
                                        (-> acc
                                            (update :trace conj entry)
                                            (assoc :metrics (metrics/accum-metrics protocol (:metrics acc) event entry agent-index working-world metrics-profile))
                                            (assoc :states (assoc (:states acc) (:seq event) (proto/world-snapshot protocol working-world)))
                                            (assoc :world-checkpoints (assoc (:world-checkpoints acc) (:seq event) working-world))))

                                      (if (= :ineligible pre-status)
                                        (let [step (process-step protocol context working-world event)
                                              entry0 (:trace-entry step)
                                              entry (assoc entry0 :commit-status :rejected)]
                                          (-> acc
                                              (update :trace conj entry)
                                              (assoc :metrics (metrics/accum-metrics protocol (:metrics acc) event entry agent-index working-world metrics-profile))))

                                        (let [step (process-step protocol context working-world event)
                                              entry0 (:trace-entry step)
                                              expected-failure? (and (= :rejected (:result entry0))
                                                                     (contains? expected-errors-set
                                                                                [(:seq entry0) (:action entry0) (:error entry0)]))
                                              reject-phase (when (= :rejected (:result entry0))
                                                             (if (:temporal-rule-id entry0) :temporal-rule :batch-commit))
                                              entry (cond-> (assoc entry0
                                                                   :preflight-status pre-status
                                                                   :commit-policy batch-commit-policy
                                                                   :commit-status (if (= :ok (:result entry0)) :accepted :rejected))
                                                      (= :ok (:result entry0)) (assoc :invariant-phase :post-event)
                                                      (= :rejected (:result entry0))
                                                      (assoc :reject-class (:error entry0)
                                                             :reject-phase reject-phase
                                                             :expected-failure? expected-failure?))
                                              new-world (if (= :ok (:result entry)) (:world step) working-world)
                                              alias-key (:save-id-as event)
                                              agent-alias-key (:save-agent-as event)
                                              new-id (when (and alias-key supports-alias? (= :ok (:result entry)))
                                                       (proto/created-id protocol (:action event) (:extra entry)))
                                              new-agent-id (when (and agent-alias-key (= :ok (:result entry)))
                                                             (:agent event))
                                              claimed' (if (= :ok (:result entry))
                                                         (reduce (fn [m d] (assoc m d (:seq event))) (:claimed-domains acc) domains)
                                                         (:claimed-domains acc))]
                                          (-> acc
                                              (assoc :world new-world
                                                     :claimed-domains claimed'
                                                     :halted? (:halted? step)
                                                     :id-alias-map (let [m (:id-alias-map acc)]
                                                                     (cond-> m
                                                                       (and alias-key new-id) (assoc alias-key new-id)
                                                                       (and agent-alias-key new-agent-id) (assoc agent-alias-key new-agent-id))))
                                              (update :trace conj entry)
                                              (assoc :metrics (metrics/accum-metrics protocol (:metrics acc) event entry agent-index working-world metrics-profile))
                                              (assoc :states (assoc (:states acc) (:seq event) (proto/world-snapshot protocol new-world)))
                                              (assoc :world-checkpoints (assoc (:world-checkpoints acc) (:seq event) working-world)))))))))
                              {:world base-world
                               :trace trace
                               :metrics metrics'
                               :states states
                               :world-checkpoints world-checkpoints
                               :claimed-domains {}
                               :halted? false
                               :id-alias-map id-alias-map}
                              bucket)]
            (if (:halted? batch-result)
              (do
                (temporal/maybe-record-temporal! temporal-cfg temporal-enabled? scenario-id :fail (:world batch-result) (:metrics batch-result) (:trace batch-result))
                (let [failed-entry (some (fn [e] (when (seq (:violations e)) e))
                                         (reverse (:trace batch-result)))]
                  (when (evidence-mode-allows? replay-flags :invariant-failure)
                    (emit-invariant-failure-evidence!
                     (or (:seq failed-entry) 0) scenario-id
                     (:violations failed-entry))))
                {:outcome :fail :scenario-id scenario-id :events-processed (count (:trace batch-result)) :halt-reason :invariant-violation :trace (:trace batch-result) :metrics (:metrics batch-result) :execution {:mode :deterministic-batch :batch-policy batch-commit-policy} :protocol protocol :world-checkpoints (:world-checkpoints batch-result) :last-valid-world (:world batch-result)})
              (let [post-single (when check-inv?
                                  (proto/check-invariants-single protocol (:world batch-result)))
                    post-trans  (when check-inv?
                                  (proto/check-invariants-transition protocol base-world (:world batch-result)))
                    post-ok?    (if check-inv?
                                  (and (:ok? post-single) (:ok? post-trans))
                                  true)
                    post-entry  {:seq             (str "batch-" batch-time)
                                 :time            batch-time
                                 :result          (if post-ok? :ok :invariant-violated)
                                 :invariant-phase :post-batch
                                 :invariants-ok?  post-ok?
                                 :violations      (when-not post-ok?
                                                    (merge (when-not (:ok? post-single) (:violations post-single))
                                                           (when-not (:ok? post-trans) (:violations post-trans))))
                                 :world           (proto/world-snapshot protocol (:world batch-result))}
                    trace' (conj (:trace batch-result) post-entry)
                    metrics'' (if post-ok?
                                (:metrics batch-result)
                                (update (:metrics batch-result) :invariant-violations inc))]
                (if post-ok?
                  (recur (:world batch-result)
                         rest-events
                         trace'
                         metrics''
                         (:states batch-result)
                         (:world-checkpoints batch-result)
                         (:checkpoint-log batch-result)
                         (:diagnostics batch-result)
                         (:id-alias-map batch-result))
                  (do
                    (temporal/maybe-record-temporal! temporal-cfg temporal-enabled? scenario-id :fail (:world batch-result) metrics'' trace')
                    {:outcome :fail
                     :scenario-id scenario-id
                     :events-processed (count trace')
                     :halt-reason :invariant-violation
                     :trace trace'
                     :metrics metrics''
                     :execution {:mode :deterministic-batch :batch-policy batch-commit-policy}
                     :protocol protocol
                     :world-checkpoints (:world-checkpoints batch-result)
                     :last-valid-world (:world batch-result)})))))
          (let [raw-event (first events)
                event (if (and supports-alias? (seq id-alias-map))
                        (let [res (proto/resolve-id-alias protocol raw-event id-alias-map)]
                          (if (:ok res) (:event res) raw-event))
                        raw-event)
                cp-result (replay-checkpoints/secure-checkpoint-update
                           {:world-checkpoints world-checkpoints :checkpoint-log checkpoint-log :diagnostics diagnostics}
                           :world-checkpoints event world)
                checkpoints' (:world-checkpoints cp-result)
                log-after-wc (:checkpoint-log cp-result)
                diag-after-wc (:diagnostics cp-result)
                step (process-step protocol context world event)
                entry0 (:trace-entry step)
                alias-key (:save-id-as raw-event)
                new-id (when (and alias-key supports-alias? (= :ok (:result entry0)))
                         (proto/created-id protocol (:action raw-event) (:extra entry0)))
                new-alias-map (if (and alias-key new-id)
                                (assoc id-alias-map alias-key new-id)
                                id-alias-map)
                expected-failure? (and (= :rejected (:result entry0))
                                       (contains? expected-errors-set
                                                  [(:seq entry0) (:action entry0) (:error entry0)]))
                reject-phase (when (= :rejected (:result entry0))
                               (if (:temporal-rule-id entry0) :temporal-rule :dispatch))
                entry (cond-> entry0
                        (= :rejected (:result entry0))
                        (assoc :reject-class (:error entry0)
                               :reject-phase reject-phase
                               :expected-failure? expected-failure?
                               :event-tags (conj (:event-tags entry0)
                                                 (if expected-failure?
                                                   :expected-revert
                                                   :unexpected-revert))))
                new-trace (conj trace entry)
                new-metrics (metrics/accum-metrics protocol metrics event entry agent-index world metrics-profile)
                new-world (:world step)
                states-result (replay-checkpoints/secure-checkpoint-update
                               {:states states :checkpoint-log log-after-wc :diagnostics diag-after-wc}
                               :states event (proto/world-snapshot protocol new-world))
                new-states (:states states-result)
                checkpoint-log' (:checkpoint-log states-result)
                diagnostics' (:diagnostics states-result)]
            ;; Emit checkpoint evidence at strategic decision points (best-effort, :all evidence-mode only)
            (when (evidence-mode-allows? replay-flags :checkpoint)
              (replay-checkpoints/emit-checkpoint-evidence-at-strategic-point!
               checkpoint-log' event))
            (let [forbidden (set (:fail-on-short-circuits replay-flags))
                  matched (->> (risk/events)
                               (mapcat :short-circuits)
                               set
                               (set/intersection forbidden)
                               sort
                               vec)]
              (cond
                (seq matched)
                (do
                  (temporal/maybe-record-temporal! temporal-cfg temporal-enabled? scenario-id :fail new-world new-metrics new-trace)
                  {:outcome :fail
                   :scenario-id scenario-id
                   :events-processed (count new-trace)
                   :halted-at-seq (:seq event)
                   :halt-reason :short-circuit-policy
                   :short-circuit-violations matched
                   :trace new-trace
                   :metrics new-metrics
                   :execution {:mode :sequential}
                   :protocol protocol
                   :last-valid-world new-world
                   :world-checkpoints checkpoints'})

                (:halted? step)
                (do
                  (temporal/maybe-record-temporal! temporal-cfg temporal-enabled? scenario-id :fail (:world step) new-metrics new-trace)
                  (attr/with-attribution
                    {:ctx/scenario-id scenario-id
                     :ctx/run-id run-id}
                    (attr/log-with-attr :error "scenario/halt" {:id scenario-id :seq (:seq event) :reason :invariant-violation}))
                  (when (evidence-mode-allows? replay-flags :invariant-failure)
                    (emit-invariant-failure-evidence!
                     (:seq event) scenario-id
                     (get-in step [:trace-entry :violations])))
                  {:outcome :fail
                   :scenario-id scenario-id
                   :events-processed (count new-trace)
                   :halted-at-seq (:seq event)
                   :halt-reason :invariant-violation
                   :trace new-trace
                   :metrics new-metrics
                   :execution {:mode :sequential}
                   :protocol protocol
                   :last-valid-world world
                   :world-checkpoints checkpoints'})
                :else
                (recur new-world (rest events) new-trace new-metrics new-states checkpoints' checkpoint-log' diagnostics' new-alias-map)))))))))