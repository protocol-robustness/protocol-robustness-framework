(ns resolver-sim.protocols.sew.io.trace-export
  "Export Clojure simulation traces to Forge-compatible JSON fixtures.

   Converts the output of replay-with-sew-protocol into the canonical trace format
   that TraceEquivalence.t.sol replays and verifies.

   Supports CDRS v0.2: standardized event schema, state buckets, atomic
   reconciliation invariants, and dispute-resolution semantics (resolution
   outcome, escalation level, participation, and timing window booleans)."
  (:require [clojure.data.json                 :as json]
            [clojure.java.io                   :as io]
            [clojure.string                    :as str]
            [resolver-sim.logging              :as log]
            [resolver-sim.protocols.sew          :as sew]
            [resolver-sim.protocols.sew.compat :as compat]
            [resolver-sim.protocols.sew.projection :as sew-proj]
            [resolver-sim.protocols.sew.diff  :as diff]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.protocols.sew.trace-metadata     :as meta])
  (:gen-class))

;; ---------------------------------------------------------------------------
;; EscrowState enum mapping (matches EscrowTypes.sol)
;; ---------------------------------------------------------------------------

(def ^:private escrow-state->int
  {:none     0
   :pending  1
   :released 2
   :refunded 3
   :disputed 4
   :resolved 5})

;; ---------------------------------------------------------------------------
;; Action name mapping: Clojure sim → Forge test
;; ---------------------------------------------------------------------------

(defn- clojure-action->forge-action
  "Map Clojure simulation action names to Forge test action names."
  [action raw-evt]
  (case action
    "execute_resolution"
    (if (get-in raw-evt [:params :is-release] true)
      "release_as_dispute_resolver"
      "cancel_as_dispute_resolver")
    action))

;; ---------------------------------------------------------------------------
;; Internal helpers
;; ---------------------------------------------------------------------------

(defn- kw-val->str [v]
  (if (keyword? v)
    (if-let [ns (namespace v)]
      (str ns "_" (name v))
      (name v))
    v))

(defn- kw-val->str-flat [v]
  (if (keyword? v) (name v) v))

(defn- projection->expected
  "Convert a diff/projection snapshot into the flat 'expected' map for Forge.
   Includes global_total_held as a sum of all token balances."
  [projection wf-id token-sym]
  (let [et           (get-in projection [:escrow-transfers wf-id])
        state-kw     (:escrow-state et)
        afa          (:amount-after-fee et 0)
        held-map     (:total-held projection {})
        fees-map     (:total-fees projection {})
        global-held  (apply + (vals held-map))
        global-fees  (apply + (vals fees-map))
        ps-exists    (get-in projection [:pending-settlements wf-id :exists] false)
        disp-level   (get-in projection [:dispute-levels wf-id] 0)]
    {:escrow_state              (get escrow-state->int state-kw 0)
     :escrow_amount_after_fee   afa
     :global_total_held         global-held
     :global_total_fees         global-fees
     :pending_settlement_exists ps-exists
     :dispute_level             disp-level}))

;; ---------------------------------------------------------------------------
;; CDRS v0.2 semantic helpers
;; ---------------------------------------------------------------------------

(defn- find-entry-by-tag
  "Return the first trace entry that carries the given event-tag keyword."
  [trace tag]
  (first (filter #(contains? (:event-tags %) tag) trace)))

(defn- find-last-entry-by-tag
  "Return the last trace entry that carries the given event-tag keyword."
  [trace tag]
  (last (filter #(contains? (:event-tags %) tag) trace)))

(defn- normalize-resolution-field
  "Lowercase and hyphenate a CDRS v0.1 uppercase string (e.g. 'FULLY_RECONCILED').
   Maps 'NO_OP' → 'unresolved' since v0.2 uses explicit outcome names."
  [s]
  (when s
    (let [lower (-> s str/lower-case (str/replace "_" "-"))]
      (if (= lower "no-op") "unresolved" lower))))

(defn- primary-dispute-wf-id
  "Return the integer wf-id of the primary disputed escrow.
   Prefers the first escrow in a non-terminal (disputed) state; falls back
   to the first id in alias-map when all escrows have already settled."
  [id-alias-map last-world]
  (let [ids (vals id-alias-map)]
    (when (seq ids)
      (or (first (filter #(= :disputed (get-in last-world [:escrow-transfers % :escrow-state])) ids))
          (first ids)))))

;; ---------------------------------------------------------------------------
;; CDRS v0.2 compute functions
;; ---------------------------------------------------------------------------

(defn- compute-trace-kind
  "Return a vector of trace-kind strings from the scenario and trace content.
   Values: lifecycle / dispute-resolution / escalation / pending-settlement /
           timing / authorization / adversarial."
  [scenario trace]
  (let [tags    (into #{} (mapcat :event-tags trace))
        actions (into #{} (map :action trace))
        adv?    (contains? #{:adversarial :stress} (meta/classify-scenario scenario))]
    (cond-> []
      (not (contains? tags :dispute-raised))     (conj "lifecycle")
      (contains? tags :dispute-raised)           (conj "dispute-resolution")
      (contains? actions "escalate_dispute")     (conj "escalation")
      (contains? tags :settlement-executed)      (conj "pending-settlement")
      (contains? actions "auto_cancel_disputed") (conj "timing")
      (contains? tags :invalid-state-transition) (conj "authorization")
      adv?                                       (conj "adversarial"))))

(defn- compute-resolution-section
  "Derive the expected_semantics.resolution block from trace + world state.
   Returns nil when no dispute was raised in the trace."
  [trace last-world primary-wf-id]
  (when primary-wf-id
    (let [res-map          (meta/resolution-semantics last-world primary-wf-id)
          resolution-state (get-in last-world [:escrow-transfers primary-wf-id :resolution])
          trace-decision   (:trace-decision resolution-state)
          outcome          (normalize-resolution-field (:outcome res-map))
          finality         (normalize-resolution-field (:finality res-map))
          integrity        (normalize-resolution-field (:integrity res-map))

          exec-res-entries (filter #(= "execute_resolution" (:action %)) trace)
          exec-res-ok?     (boolean (some #(= :ok (:result %)) exec-res-entries))

          settlement-entry (find-last-entry-by-tag trace :settlement-executed)
          settle-ok?       (boolean settlement-entry)

          pending-in-world (get-in last-world [:pending-settlements primary-wf-id :exists] false)
          ;; pending_settlement_created is true only when a pending-settlement object
          ;; actually exists or was executed — not merely because execute_resolution ran.
          pending-created? (or settle-ok? pending-in-world)

          resolved-entry   (find-last-entry-by-tag trace :dispute-resolved)
          resolver-agent   (:agent resolved-entry)

          state            (get-in last-world [:escrow-transfers primary-wf-id :escrow-state])
          beneficiary      (case state
                             :released "seller"
                             :refunded "buyer"
                             :resolved "settled"
                             nil)]
      (cond-> {:outcome   outcome
               :finality  finality
               :integrity integrity}
        beneficiary            (assoc :beneficiary beneficiary)
        resolver-agent         (assoc :resolver resolver-agent)
        trace-decision         (assoc :trace_decision trace-decision)
        (seq exec-res-entries) (assoc :authorized_resolver       exec-res-ok?
                                      :pending_settlement_created pending-created?
                                      :settlement_executed        settle-ok?)))))

(defn- compute-escalation-section
  "Derive the expected_semantics.escalation block.
   Returns nil when no escalation was attempted in the trace."
  [trace last-world primary-wf-id]
  (when primary-wf-id
    (let [esc-entries (filter #(= "escalate_dispute" (:action %)) trace)]
      (when (seq esc-entries)
        (let [level     (get-in last-world [:dispute-levels primary-wf-id] 0)
              max-level (apply max 0 (keep #(get-in % [:world :dispute-levels primary-wf-id]) trace))
              accepted? (boolean (some #(= :ok       (:result %)) esc-entries))
              rejected? (boolean (some #(= :rejected (:result %)) esc-entries))]
          {:level             level
           :max_level_reached max-level
           :tier              (case level 0 "l0" 1 "l1" 2 "l2" "arbitration")
           :attempted         true
           :accepted          accepted?
           :rejected          rejected?})))))

(defn- compute-participation-section
  "Derive the expected_semantics.participation block.
   Returns nil when no dispute was raised in the trace."
  [trace]
  (let [dispute-entry  (find-entry-by-tag      trace :dispute-raised)
        resolved-entry (find-last-entry-by-tag trace :dispute-resolved)
        settle-entry   (find-last-entry-by-tag trace :settlement-executed)]
    (when dispute-entry
      (cond-> {:dispute_initiator (:agent dispute-entry)}
        resolved-entry (assoc :resolution_actor       (:agent resolved-entry)
                              :authorized_participant (= :ok (:result resolved-entry)))
        settle-entry   (assoc :settlement_actor (:agent settle-entry))))))

(defn- compute-timing-section
  "Derive the expected_semantics.timing block.
   Only includes window-boolean fields when the relevant params are non-zero
   and both boundary timestamps are present in the trace."
  [trace scenario]
  (let [params       (get scenario :protocol-params {})
        max-disp-dur (get params :max-dispute-duration 0)
        appeal-win   (get params :appeal-window-duration 0)

        disp-entry   (find-entry-by-tag      trace :dispute-raised)
        res-entry    (find-last-entry-by-tag trace :dispute-resolved)
        settle-entry (find-last-entry-by-tag trace :settlement-executed)

        disp-time    (:time disp-entry)
        res-time     (:time res-entry)
        settle-time  (:time settle-entry)

        auto-cancel? (boolean (some #(and (= "auto_cancel_disputed" (:action %))
                                          (= :ok (:result %)))
                                    trace))
        pending-delay (when (and res-time settle-time) (- settle-time res-time))

        within-res   (when (and disp-time res-time (pos? max-disp-dur))
                       (<= (- res-time disp-time) max-disp-dur))
        within-set   (when (and res-time settle-time (pos? appeal-win))
                       (<= (- settle-time res-time) appeal-win))]
    (cond-> {:auto_cancel_triggered auto-cancel?}
      (some? within-res) (assoc :within_resolution_window within-res)
      (some? within-set) (assoc :within_settlement_window within-set)
      pending-delay      (assoc :pending_delay_seconds pending-delay))))

(defn- compute-expected-semantics
  "Combine all v0.2 semantic sections into the expected_semantics top-level block.
   Returns an empty map for lifecycle traces (no dispute raised)."
  [trace scenario last-world id-alias-map]
  (let [primary-wf-id (primary-dispute-wf-id id-alias-map last-world)
        has-dispute?  (some #(contains? (:event-tags %) :dispute-raised) trace)
        resolution    (when has-dispute? (compute-resolution-section  trace last-world primary-wf-id))
        escalation    (when has-dispute? (compute-escalation-section  trace last-world primary-wf-id))
        participation (when has-dispute? (compute-participation-section trace))
        timing        (when has-dispute? (compute-timing-section       trace scenario))]
    (cond-> {}
      resolution    (assoc :resolution    resolution)
      escalation    (assoc :escalation    escalation)
      participation (assoc :participation participation)
      timing        (assoc :timing        timing))))

;; ---------------------------------------------------------------------------
;; Trace entry → Forge step
;; ---------------------------------------------------------------------------

(defn- idempotency-summary
  "Summarise replay-boundary dedupe steps for audit metadata."
  [trace]
  (let [deduped (filter #(= :no-op-duplicate (get-in % [:extra :idempotency])) trace)]
    {:dedupe_step_count (count deduped)
     :dedupe_steps      (mapv :seq deduped)}))

(defn- step->forge-step
  "Convert one replay trace entry into a CDRS-compliant Forge trace step."
  [entry prev-proj scenario-events id-alias-map token-sym addr->agent-id]
  (let [seq-n    (:seq entry)
        action   (:action entry)
        result   (:result entry)
        proj     (:projection entry)
        metadata (:trace-metadata entry)

        raw-evt  (->> scenario-events (filter #(= (:seq %) seq-n)) first)
        forge-action (clojure-action->forge-action action raw-evt)
        caller-role (get raw-evt :agent "buyer")

        params   (case action
                   "create_escrow"
                   (let [raw-to (get-in raw-evt [:params :to] "seller")]
                     {:to_role  (get addr->agent-id raw-to raw-to)
                      :amount   (get-in raw-evt [:params :amount] 0)})
                   {})

        wf-id    (cond
                   (= action "create_escrow") (get-in entry [:extra :workflow-id])
                   :else (or (get-in raw-evt [:params :workflow-id])
                             (get-in raw-evt [:params :workflow_id])))

        wf-alias   (or (some (fn [[k v]] (when (= v wf-id) k)) id-alias-map)
                       (when wf-id (str "wf" wf-id)))

        expected-raw (projection->expected proj (or wf-id 0) token-sym)

        expected
        (if (= result :rejected)
          {:reverted true
           :error    (name (:error entry :unknown))
           :escrow_state (get-in expected-raw [:escrow_state] 0)
           :pending_settlement_exists (get-in expected-raw [:pending_settlement_exists] false)}
          expected-raw)

        accepted?     (= result :ok)
        expected-v2   (cond-> (assoc expected
                                     :accepted      accepted?
                                     :state_changed accepted?)
                        (not accepted?) (assoc :rejection_reason
                                               (name (or (:error entry) :unknown))))
        replay-event  (or raw-evt {:params {}})
        idem-kw       (get-in entry [:extra :idempotency])
        ;; The dispatch action carries the resolved direction.  The raw sim
        ;; action (e.g. "execute_resolution") is ambiguous about release vs
        ;; cancel and is rejected by the Forge harness; it is preserved for
        ;; provenance as :raw_action only when it differs (so unchanged traces
        ;; stay byte-identical across this export version).
        attributes    (cond-> {:action forge-action :seq seq-n}
                        (not= action forge-action) (assoc :raw_action action)
                        wf-alias (assoc :wf_alias wf-alias)
                        params   (merge params)
                        (:temporal-rule-id entry)
                        (assoc :temporal_rule_id
                               (kw-val->str-flat (:temporal-rule-id entry)))
                        idem-kw (assoc :idempotency (name idem-kw))
                        (compat/event-id replay-event)
                        (assoc :event_id (compat/event-id replay-event))
                        (compat/hop-id replay-event)
                        (assoc :hop_id (compat/hop-id replay-event))
                        metadata (merge (into {} (map (fn [[k v]] [(kw-val->str-flat k) (kw-val->str-flat v)]) metadata))))]
    (cond-> {:seq           seq-n
             :cdrs_version  "0.2"
             :event_type    (str/upper-case (str/replace forge-action #"_" " "))
             :step_type     (cond
                              (not= result :ok)
                              "protocol-transition"
                              (= :transition/economic (:transition/type metadata))
                              "economic-effect"
                              (#{:transition/timeout :transition/maintenance} (:transition/type metadata))
                              "environmental-change"
                              :else
                              "protocol-transition")
             :context_id    (or wf-alias "global")
             :actor         caller-role
             :timestamp     (:time entry 0)
             :state_bucket  (meta/state-bucket (:world entry) wf-id)
             :attributes    attributes}
      expected-v2 (assoc :expected expected-v2))))

;; ---------------------------------------------------------------------------
;; Terminal projection hash — SHA-256 of the 4-field projection for Solidity
;; cross-verification.  Format matches the Solidity side:
;;   sha256(state|afa|psExists|dispLevel)
;; ---------------------------------------------------------------------------

(defn terminal-projection-hash
  "Compute SHA-256 of the per-escrow projection for Solidity equivalence check.
   Format: sha256(state|afa|psExists|dispLevel)
   Returns nil when wf-id is nil (no escrow in scenario).
   Note: total-held and total-fees are omitted because the token address key
   differs between Clojure (keyword) and Solidity (ERC20 address).  Those
   fields are verified per-step in the Forge equivalence suite."
  [world wf-id]
  (when wf-id
    (let [state-kw (get-in world [:escrow-transfers wf-id :escrow-state])
          state     (get escrow-state->int state-kw 0)
          afa       (get-in world [:escrow-transfers wf-id :amount-after-fee] 0)
          ps-exists (if (get-in world [:pending-settlements wf-id :exists] false) 1 0)
          disp-level (get-in world [:dispute-levels wf-id] 0)
          data      (str state "|" afa "|" ps-exists "|" disp-level)
          digest    (java.security.MessageDigest/getInstance "SHA-256")]
      (format "%064x" (java.math.BigInteger. 1 (.digest digest (.getBytes data "UTF-8")))))))

;; ---------------------------------------------------------------------------
;; Public: export-trace-fixture
;; ---------------------------------------------------------------------------

(defn export-trace-fixture
  "Convert a replay-with-sew-protocol result into a Forge-compatible trace fixture map."
  [result scenario & {:keys [token-sym] :or {token-sym "TOKEN"}}]
  (let [trace       (:trace result)
        events      (:events scenario)
        last-world  (sew-proj/terminal-world-from-result result)
        ;; Reverse map from simulation address → agent ID (for to_role resolution)
        addr->agent-id
        (into {} (map (fn [a] [(:address a) (:id a)]) (:agents scenario [])))
        ;; Build an id-alias-map by scanning trace for entity-create :ok entries.
        ;; Assigns stable labels "wf0", "wf1", ... in creation order.
        id-alias-map
        (let [created-entries (filter #(contains? (:event-tags %) :entity-created) trace)]
          (into {} (map-indexed (fn [i entry]
                                  [(str "wf" i) (get-in entry [:extra :workflow-id])])
                                created-entries)))

        steps (loop [entries   trace
                     prev-proj nil
                     acc       []]
                (if (empty? entries)
                  acc
                  (let [entry (first entries)
                        step  (step->forge-step entry prev-proj events id-alias-map token-sym addr->agent-id)]
                    (recur (rest entries) (:projection entry) (conj acc step)))))
        ;; The Forge harness compares global_total_held / global_total_fees
        ;; against a single ERC20 token (TUSDC).  A multi-token trace would be
        ;; silently mis-verified (export sums across tokens, Solidity reads one),
        ;; so reject it at export time instead of emitting a misleading fixture.
        _multi-token-check
        (let [tokens (into #{} (mapcat (fn [entry]
                                         (concat (keys (:total-held (:projection entry) {}))
                                                 (keys (:total-fees (:projection entry) {}))))
                                       trace))]
          (when (> (count tokens) 1)
            (throw (ex-info (str "Trace uses multiple tokens; the Forge equivalence harness "
                                 "only verifies single-token traces: " (vec (sort tokens)))
                            {:type :multi-token-trace-unsupported
                             :scenario-id (:scenario-id scenario)
                             :tokens (vec (sort tokens))}))))
        ;; Phase 1: the exported fixture must negotiate to a supported replay-spec.
        ;; The exporter always emits cdrs 0.2 / schema 2 / profile 1, so this is
        ;; a fail-closed guard against future drift in the supported registry.
        _replay-spec-check
        (let [spec-id "cdrs-0.2.schema-2.profile-1.harness-1"]
          (when-not (contains? #{"cdrs-0.1.schema-1.profile-none.harness-1"
                                 "cdrs-0.2.schema-2.profile-1.harness-1"} spec-id)
            (throw (ex-info (str "Exported fixture does not negotiate to a supported replay-spec: "
                                 spec-id)
                            {:type :unsupported-replay-spec
                             :scenario-id (:scenario-id scenario)
                             :replay-spec-id spec-id}))))]
    (let [trace-kind         (compute-trace-kind scenario trace)
          expected-semantics (compute-expected-semantics trace scenario last-world id-alias-map)
          idem-summary       (idempotency-summary trace)
          primary-wf-id      (primary-dispute-wf-id id-alias-map last-world)]
      {:cdrs_version      "0.2"
       :schema_version    "2"
       :scenario_id       (:scenario-id result)
       :description       (str "Generated trace: " (:scenario-id result))
       :trace_kind        trace-kind
        :fee_bps           (get-in scenario [:protocol-params :resolver-fee-bps] 100)
        :appeal_window_duration (get-in scenario [:protocol-params :appeal-window-duration] 0)
        :max_dispute_duration   (get-in scenario [:protocol-params :max-dispute-duration] 0)
        :metadata          (cond-> {"scenario_class" (kw-val->str-flat (meta/classify-scenario scenario))
                                    "outcome_type"   (kw-val->str-flat (meta/classify-outcome result scenario))}
                             (pos? (:dedupe_step_count idem-summary))
                             (assoc "idempotency" idem-summary))
        :invariant_profile {"id"      "solidity-equivalence-core-v1"
                            "version" 1
                            "root"    "31d07038dcde86ac6f34b229fded0fce98b679c2bd83130b607f0b9a2a27e19f"}
        :expected_semantics expected-semantics
        :step_count        (count steps)
        :terminal_projection_hash (terminal-projection-hash last-world primary-wf-id)
        :steps             steps
       ;; Resolution summary for all escrows in the trace
       :resolutions       (into {} (for [[alias id] id-alias-map]
                                     [alias (meta/resolution-semantics last-world id)]))})))

;; ---------------------------------------------------------------------------
;; JSON serialisation
;; ---------------------------------------------------------------------------

(defn fixture->json-str
  "Serialize a fixture map to a pretty-printed JSON string."
  [fixture]
  (with-out-str (json/pprint fixture)))

(defn write-fixture-file
  "Write a fixture map to a JSON file at `path`."
  [fixture path]
  (io/make-parents path)
  (spit path (fixture->json-str fixture)))

;; ---------------------------------------------------------------------------
;; CLI entry point
;; ---------------------------------------------------------------------------

(defn -main
  "CLI: replay a scenario file and write the Forge trace fixture."
  [& args]
  (when (not= (count args) 2)
    (log/warn! "trace-export/usage-error" {:args args})
    (println "Usage: trace-export <scenario-json> <output-fixture-json>")
    (System/exit 1))
  (let [[scenario-path output-path] args
        scenario ((requiring-resolve 'resolver-sim.io.scenarios/load-scenario-file) scenario-path)
        result   (sew/replay-with-sew-protocol scenario)]
    (if (= :invalid (:outcome result))
      (do
        (log/error! "trace-export/scenario-invalid"
                    {:reason (:halt-reason result)
                     :scenario scenario-path})
        (println "ERROR: scenario invalid:" (:halt-reason result))
        (System/exit 2))
      (let [fixture (export-trace-fixture result scenario)]
        (write-fixture-file fixture output-path)
        (log/info! "trace-export/written"
                   {:output output-path :steps (count (:steps fixture))})
        (println "Written" (count (:steps fixture)) "steps to" output-path)))))
