(ns resolver-sim.protocols.sew.resolution
  "Pure Clojure port of BaseEscrow resolution and appeal-window logic.

   Covers:
     execute-resolution          — _executeResolution (resolver submits outcome)
     execute-pending-settlement  — executePendingSettlement (after appeal deadline)
     automate-timed-actions      — automateTimedActions (keeper dispatch)
     escalate-dispute            — escalateDispute (appeal to next round)

   All functions return {:ok bool :world world' :error keyword}."
  (:require [resolver-sim.protocols.sew.types         :as t]
            [resolver-sim.protocols.sew.state-machine :as sm]
            [resolver-sim.protocols.sew.authority     :as auth]
            [resolver-sim.protocols.sew.accounting    :as acct]
            [resolver-sim.protocols.sew.registry      :as reg]
            [resolver-sim.protocols.sew.lifecycle     :as lc]
            [resolver-sim.protocols.sew.yield.policy  :as yield-policy]
            [resolver-sim.protocols.sew.economics     :as sew-econ]
            [resolver-sim.yield.ops                   :as yield-ops]
            [resolver-sim.util.attribution            :as attr]
            [resolver-sim.util.attributed-monad      :as am]
            [resolver-sim.time.context               :as time-ctx]
            [resolver-sim.evidence.capture             :as cap]
            [resolver-sim.evidence.chain              :as chain]
            [resolver-sim.hash.canonical              :as hc]
            [resolver-sim.protocols.sew.evidence.slashing :as slashing-ev]
            [resolver-sim.logging                     :as log]))

(declare finalize handle-reversal-slashing handle-fraud-slashing update-unavailability)

;; ── Decision Evidence (Evidence Layer 4) ──────────────────────────────────

(defn- build-decision-evidence
  [{:keys [decision-id step alternatives selected reasoning caller workflow-id]}]
  (let [data {:decision-id decision-id
              :step step
              :alternatives (vec alternatives)
              :selected selected
              :reasoning (str reasoning)
              :caller caller
              :workflow-id workflow-id}
        evidence-hash (hc/hash-with-intent {:hash/intent :decision-evidence} data)]
    {:data data
     :evidence-hash evidence-hash
     :evidence {:artifact-kind :decision-evidence
                :evidence-hash evidence-hash
                :decision/id decision-id
                :decision/step step
                :decision/selected selected
                :decision/reasoning (str reasoning)}}))

(defn- transfer-trace-decision-summary
  [{:keys [decision-id step alternatives selected reasoning caller]} evidence-hash]
  {:decision-id decision-id
   :step step
   :alternatives (vec alternatives)
   :selected selected
   :reasoning (str reasoning)
   :caller caller
   :decision-evidence-hash evidence-hash})

(defn emit-decision-evidence!
  "Build and chain a decision evidence record.
   Best-effort — failures are logged but do not halt execution.
   Returns {:evidence-hash ...} on success when available."
  [decision-info]
  (try
    (let [{:keys [evidence-hash evidence]} (build-decision-evidence decision-info)]
      (chain/register-evidence! evidence)
      {:evidence-hash evidence-hash})
    (catch Exception e
      (log/warn! :decision-evidence-failed
                 {:decision-id (:decision-id decision-info)
                  :error (.getMessage e)})
      nil)))

(defn rotate-dispute-resolver
  "Governance-triggered resolver rotation for an in-flight dispute.
   Records the rotation so invariants and scenarios can detect governance attacks.
   Adjusts capacity counters: decrements old resolver, increments new resolver.
   No-op on idempotent rotations (same resolver or identical previous rotation)."
  [world workflow-id new-resolver]
  (cond
    (not (t/valid-workflow-id? world workflow-id))
    (t/fail :invalid-workflow-id)

    (not= :disputed (t/escrow-state world workflow-id))
    (t/fail :transfer-not-in-dispute)

    (:exists (t/get-pending world workflow-id))
    (t/fail :resolution-already-pending)

    (or (nil? new-resolver) (= "" new-resolver))
    (t/fail :invalid-new-resolver)

    :else
    (let [old-resolver (get-in world [:escrow-transfers workflow-id :dispute-resolver])
          same-resolver? (= old-resolver new-resolver)
          last-rotation (last (get-in world [:resolver-rotations workflow-id]))
          same-rotation? (and (not same-resolver?)
                              (some? last-rotation)
                              (= (:from last-rotation) old-resolver)
                              (= (:to last-rotation) new-resolver))]
      (if (or same-resolver? same-rotation?)
        (do
          (attr/with-attribution {:subject/type :dispute
                                  :subject/id workflow-id
                                  :action/type :resolver/rotate-idempotent
                                  :evidence/reason :resolver-rotation}
            (cap/capture-event-evidence!
             :resolver-rotation
             {:rotation/before {:resolver old-resolver}}
             {:rotation/after {:resolver new-resolver}}
             {:rotation/workflow-id workflow-id
              :rotation/from old-resolver
              :rotation/to new-resolver
              :rotation/idempotent? true}))
          (assoc (t/ok world)
                 :old-resolver old-resolver
                 :new-resolver new-resolver
                 :idempotent? true))
          (let [rotation {:from old-resolver :to new-resolver :at (time-ctx/block-ts world)}
                world'   (-> world
                            (assoc-in [:escrow-transfers workflow-id :dispute-resolver]
                                      new-resolver)
                            (update-in [:escrow-settings workflow-id] dissoc :custom-resolver)
                            (update-in [:resolver-rotations workflow-id]
                                      (fnil conj []) rotation)
                           (t/decrement-resolver-capacity old-resolver)
                            (t/increment-resolver-capacity new-resolver))]
          (attr/with-attribution {:subject/type :dispute
                                  :subject/id workflow-id
                                  :action/type :resolver/rotate
                                  :evidence/reason :resolver-rotation}
            (cap/capture-event-evidence!
             :resolver-rotation
             {:rotation/before {:resolver old-resolver}}
             {:rotation/after {:resolver new-resolver}}
             {:rotation/workflow-id workflow-id
              :rotation/from old-resolver
              :rotation/to new-resolver
              :rotation/idempotent? false
              :rotation/at (:at rotation)}))
          (assoc (t/ok world') :old-resolver old-resolver :new-resolver new-resolver))))))

;; ---------------------------------------------------------------------------
;; Internal: slashing helpers
;; ---------------------------------------------------------------------------

(defn- make-reversal-slash-entry
  [slash-id slash-kind slash-level prev-resolver prev-stake slash-bps slash-amt token workflow-id status now appeal-deadline reversal-prob
   & {:keys [authorization-provenance]}]
  (let [entry {:slash/id                       slash-id
               :slash/workflow-id              workflow-id
               :slash/kind                     slash-kind
               :slash/level                    slash-level
               ;; Legacy field names remain during the transition so existing
               ;; accounting and invariant readers continue to work.
               :resolver                       prev-resolver
               :basis-amount                   prev-stake
               :basis-kind                     :stake
               :slash-bps                      slash-bps
               :amount                         slash-amt
               :token                          token
               :workflow-id                    workflow-id
               :reason                         :reversal
               :status                         status
               :proposed-at                    now
               :appeal-deadline                appeal-deadline
               :appeal-bond-held               0
               :contest-deadline               0
               :reversal-detection-probability reversal-prob}]
    (if authorization-provenance
      (-> entry
          (assoc :authorization/provenance authorization-provenance
                 :authorization/last-provenance authorization-provenance
                 :authorization/last-action "force-reversal-slash")
          (update :authorization/history
                  (fnil conj [])
                  {:authorization/action "force-reversal-slash"
                   :authorization/provenance authorization-provenance}))
      entry)))

(defn- append-authorization-provenance
  [entry action authorization-provenance]
  (if authorization-provenance
    (-> entry
        (cond-> (nil? (:authorization/provenance entry))
          (assoc :authorization/provenance authorization-provenance))
        (assoc :authorization/last-provenance authorization-provenance
               :authorization/last-action action)
        (update :authorization/history
                (fnil conj [])
                {:authorization/action action
                 :authorization/provenance authorization-provenance}))
    entry))

(defn- append-execution-provenance
  [entry action execution-provenance]
  (if execution-provenance
    (-> entry
        (cond-> (nil? (:execution/provenance entry))
          (assoc :execution/provenance execution-provenance))
        (assoc :execution/last-provenance execution-provenance
               :execution/last-action action)
        (update :execution/history
                (fnil conj [])
                {:execution/action action
                 :execution/provenance execution-provenance}))
    entry))

;; ---------------------------------------------------------------------------
;; Guard logging helper — returns (t/fail kw) with :guard-context attached
;; so process-step can capture rejection context in trace entries.
;; ---------------------------------------------------------------------------

(defn- guard-fail [error-kw & {:as ctx}]
  (attr/log-with-attr :debug "guard/rejected" (assoc ctx :error error-kw))
  (let [result (assoc (t/fail error-kw) :guard-context ctx)
        subject-type (or (:subject-type ctx) :guard)
        subject-id (or (:subject-id ctx) (:workflow-id ctx) (:resolver ctx) "unknown")]
    (attr/with-attribution {:subject/type subject-type
                            :subject/id subject-id
                            :action/type :guard/reject
                            :evidence/reason error-kw}
      (cap/capture-event-evidence!
       :guard-rejected
       {:guard/before {:error error-kw :context (dissoc ctx :world)}}
       {:guard/after {:error error-kw}}
       {:guard/error error-kw
        :guard/context (dissoc ctx :world)}
       nil
       (when (:world ctx)
         {:world-before (:world ctx)
          :world-after (:world ctx)})))
    result))

(defn- handle-reversal-slashing
  "Handles the outcome of a reversed decision.

   Matches Solidity's two-track slashing system:

   1. Automated Track (slashForReversal):
      Same evidence as prior level — deterministic immediate slash (not appealable).

   2. Manual Track (proposeSlash):
       New evidence submitted — slash is :pending with appeal window (governance path).

   Limitation: Track 2 :pending reversal slashes are NOT automatically resolved
   when the escrow finalizes.  If a pending reversal slash outlives the escrow
   (appeal window expires without resolution), the entry remains in
   :pending-fraud-slashes indefinitely.  This is a state inconsistency — the
   slash cannot execute because the escrow is no longer :disputed, but the entry
   is never garbage-collected."
  [world workflow-id current-is-release]
  (let [level (t/dispute-level world workflow-id)]
    (if-not (pos? level)
      world
      (let [prev-decision (get-in world [:previous-decisions workflow-id (dec level)])]
        (if-not (and (some? prev-decision)
                     (not= (:is-release prev-decision) current-is-release))
          world
          (let [slash-level     (dec level)
                prev-resolver   (:resolver prev-decision)]
            ;; Idempotency: if a reversal entry already exists for this level, skip.
            ;; Also skip if a pending/appealed slash targets the same resolver on this
            ;; workflow (cross-type collision guard — prevents double-penalty from the
            ;; same dispute).  Different levels target different resolvers (L0 vs L1),
            ;; so the scan checks resolver + workflow-id, not just workflow-id alone.
            (if (or (get-in world [:slash-by-context [workflow-id :reversal slash-level]])
                    (some (fn [[_id entry]]
                            (and (= (:workflow-id entry) workflow-id)
                                 (= (:resolver entry) prev-resolver)
                                 (#{:pending :appealed} (:status entry))))
                          (get world :pending-fraud-slashes {})))
              world
              (let [snap            (t/get-snapshot world workflow-id)
                    et              (t/get-transfer world workflow-id)
                    token           (:token et "USDC")
                    new-evidence?   (get-in world [:evidence-updated? workflow-id] false)
                    slash-bps       (:reversal-slash-bps snap 0)
                    prev-stake      (reg/get-stake world prev-resolver)
                    slash-amt       (sew-econ/calculate-slash-amount-from-basis prev-stake slash-bps)
                    now             (time-ctx/block-ts world)
                    appeal-window   (:appeal-window-duration snap 0)
                    reversal-prob   (or (:reversal-detection-probability snap) 0.0)
                    challenger      (get-in world [:challengers workflow-id (dec level)])
                    bounty-bps      (:challenge-bounty-bps snap 0)]
            (if-not (pos? slash-amt)
              world
              (let [slash-id (t/allocate-slash-id world)
                    entry    (make-reversal-slash-entry slash-id :reversal slash-level
                                                        prev-resolver prev-stake slash-bps
                                                        slash-amt token workflow-id
                                                        (if new-evidence? :pending :executed)
                                                        now (if new-evidence? (+ now appeal-window) 0)
                                                        reversal-prob)
                    world'   (if new-evidence?
                               world
                               (:world (reg/slash-resolver-stake world prev-resolver slash-amt challenger bounty-bps workflow-id true)))]
                (t/insert-slash world' entry)))))))))))

(defn force-reversal-slash
  "Force a reversal slash on a workflow without going through the full resolution
   pipeline.  Allows isolated testing of reversal-slash accounting, stake
   deduction, and bond distribution.

   When optional `:slash-bps` is provided, it overrides the snapshot's
   `:reversal-slash-bps`.  When `:track` is `:immediate`, the slash executes
   immediately (Track 1).  When `:track` is `:pending`, a pending slash with
   appeal window is created (Track 2, default).

   Idempotent: if a force-reversal entry already exists for this workflow-id,
   returns the world unchanged to prevent double-slashing.

   Returns the updated world.

   Short-circuit analogue of `handle-reversal-slashing` — same slash-entry
   schema, same `slash-resolver-stake` call, same invariants apply."
  [world workflow-id & {:keys [slash-bps track authorization-provenance]
                        :or   {track :pending}}]
  ;; Guard uses :slash-by-context rather than :pending-fraud-slashes because
  ;; immediate-track slashes never enter :pending-fraud-slashes — they execute
  ;; directly via slash-resolver-stake.  :slash-by-context captures both tracks
  ;; uniformly and avoids creating a separate dedup map.
  (let [slash-level 0]
    (if (get-in world [:slash-by-context [workflow-id :force-reversal slash-level]])
      world
      (let [level       (t/dispute-level world workflow-id)
            prev-decision (when (pos? level)
                            (get-in world [:previous-decisions workflow-id (dec level)]))
            prev-resolver (or (:resolver prev-decision) (get-in world [:escrow-transfers workflow-id :sender]))
            snap          (t/get-snapshot world workflow-id)
            et            (t/get-transfer world workflow-id)
            token         (:token et "USDC")
            bps           (long (or slash-bps (:reversal-slash-bps snap 0)))
            prev-stake    (reg/get-stake world prev-resolver)
            slash-amt     (sew-econ/calculate-slash-amount-from-basis (or prev-stake 0) bps)
            now           (time-ctx/block-ts world)
            appeal-window (:appeal-window-duration snap 0)
            reversal-prob (or (:reversal-detection-probability snap) 0.0)]
        (if (or (nil? prev-resolver) (not (pos? slash-amt)))
          world
          (let [slash-id (t/allocate-slash-id world)
                entry    (make-reversal-slash-entry slash-id :force-reversal slash-level
                                                    prev-resolver prev-stake bps
                                                    slash-amt token workflow-id
                                                    (if (= :immediate track) :executed :pending)
                                                    now (if (= :immediate track) 0 (+ now appeal-window))
                                                    reversal-prob
                                                    :authorization-provenance authorization-provenance)
                world'   (if (= :immediate track)
                           (:world (reg/slash-resolver-stake world prev-resolver slash-amt nil 0 workflow-id))
                           world)]
            (t/insert-slash world' entry)))))))

(defn- reverse-reversal-slash-on-vindication
  "When a higher-level resolution agrees with a lower-level decision that was
   previously reversed (creating an auto-slash on that lower resolver), restore
   the slashed stake and mark the slash as reversed-with-credit.

   Only applies to Track 1 (auto) slashes — Track 2 (pending) slashes have their
   own appeal path via the governance slash pipeline.

   The slashed funds have already been distributed to insurance/protocol/burned
   pools — this does NOT claw them back. Instead, the resolver's stake balance
   is credited, representing a protocol-backed liability to restore the resolver's
   economic capacity for future disputes.

   Audit trail: reversed slashes are recorded as :reversed-with-credit with the
   resolving level and timestamp preserved."
  [world workflow-id current-is-release]
  (let [current-level (t/dispute-level world workflow-id)
        token (:token (t/get-transfer world workflow-id))]
    (if (< current-level 2)
      world
      (let [possibly-vindicated (range 0 (dec current-level))]
        (reduce (fn [w rev-level]
                  (let [original-decision (get-in w [:previous-decisions workflow-id rev-level])
                        next-decision     (get-in w [:previous-decisions workflow-id (inc rev-level)])
                        was-reversed?     (and original-decision next-decision
                                               (not= (:is-release original-decision) (:is-release next-decision)))
                        vindicated?       (and original-decision
                                               (= (:is-release original-decision) current-is-release))]
                    (if (and was-reversed? vindicated?)
                      (let [slash-id    (get-in w [:slash-by-context [workflow-id :reversal rev-level]])
                            slash-entry (get-in w [:pending-fraud-slashes slash-id])]
                        (if (and slash-entry (= :executed (:status slash-entry)) (= :reversal (:reason slash-entry)))
                          (let [resolver (:resolver slash-entry)
                                amount   (:amount slash-entry)
                                now      (time-ctx/block-ts w)]
                            (if (pos? amount)
                              (let [current-slash-total (get-in w [:resolver-slash-total resolver] 0)]
                                (if (>= current-slash-total amount)
                                  (-> w
                                      (update-in [:resolver-stakes resolver] (fnil + 0) amount)
                                      (update-in [:resolver-slash-total resolver] (fnil - 0) amount)
                                      (update-in [:slash-credit-liabilities resolver] (fnil + 0) amount)
                                      (assoc-in [:pending-fraud-slashes slash-id :status] :reversed-with-credit)
                                      (assoc-in [:pending-fraud-slashes slash-id :reversed-by-level] current-level)
                                      (assoc-in [:pending-fraud-slashes slash-id :reversed-at] now))
                                  (do (log/warn! :slash-reversal-underflow
                                                 {:slash-id slash-id :resolver resolver
                                                  :amount amount :current current-slash-total})
                                      w)))
                              (do (log/warn! :invalid-slash-reversal
                                             {:slash-id slash-id :resolver resolver
                                              :amount amount})
                                  w)))
                          w))
                      w)))
                world
                possibly-vindicated)))))

(defn submit-evidence
  "Record that new evidence was submitted for workflow-id (Track 2 reversal slashing).
   May be called while :disputed before the reversing resolution is executed.
   When the snapshot has a positive :evidence-window-duration, evidence submitted
   after the deadline (dispute-raise-time + window) is rejected with
   :evidence-deadline-exceeded."
  [world workflow-id _caller & [{:keys [evidence-hash]}]]
  (cond
    (not (t/valid-workflow-id? world workflow-id))
    (t/fail :invalid-workflow-id)
    (not= :disputed (t/escrow-state world workflow-id))
    (t/fail :transfer-not-in-dispute)
    :else
    (let [snap       (t/get-snapshot world workflow-id)
          window-dur (:evidence-window-duration snap 0)
          now        (time-ctx/block-ts world)
          dispute-ts (get-in world [:dispute-timestamps workflow-id] 0)]
      (if (and (pos? window-dur) (> now (+ dispute-ts window-dur)))
        (t/fail :evidence-deadline-exceeded)
        (let [world' (cond-> (assoc-in world [:evidence-updated? workflow-id] true)
                       evidence-hash (update-in [:evidence-hashes workflow-id]
                                                 (fnil conj []) evidence-hash))]
          (attr/with-attribution {:subject/type :dispute
                                  :subject/id workflow-id
                                  :action/type :dispute/submit-evidence
                                  :evidence/reason :evidence-submitted}
            (cap/capture-event-evidence!
             :evidence-submitted
             {:evidence/before {:evidence-updated? (get-in world [:evidence-updated? workflow-id] false)}}
             {:evidence/after {:evidence-updated? true :evidence-hash evidence-hash}}
             {:evidence/workflow-id workflow-id
              :evidence/hash evidence-hash}))
          (t/ok world'))))))

(defn- fraud-slash-workflow-eligible?
  "Manual fraud slash requires the workflow to have entered the dispute path:
   in-flight dispute, pending settlement after resolution, or a recorded decision."
  [world workflow-id]
  (let [wf-id (t/normalize-workflow-id workflow-id)]
    (or (= :disputed (t/escrow-state world wf-id))
        (:exists (t/get-pending world wf-id))
        (seq (vals (get-in world [:previous-decisions wf-id] {}))))))

(defn- active-manual-fraud-slash?
  "True when there is any pending or appealed slash for the given slash-id or workflow.

   Checks both the exact key path AND scans all pending-fraud-slash entries for any
   that reference the same workflow-id.  This catches cross-type collisions where
   a reversal slash stored under a different integer ID would be missed by
   an exact integer-key lookup."
  [world slash-id]
  (let [wf-id           (when (integer? slash-id) slash-id)
        exact-existing  (get-in world [:pending-fraud-slashes slash-id])
        ;; Scan all entries for any pending/appealed entry referencing the same workflow
        cross-existing  (when wf-id
                          (some (fn [[_id entry]]
                                  (and (= (:workflow-id entry) wf-id)
                                       (#{:pending :appealed} (:status entry))))
                                (get world :pending-fraud-slashes {})))]
    (or (and exact-existing (#{:pending :appealed} (:status exact-existing)))
        cross-existing)))

(defn- handle-fraud-slashing
  "Create a PENDING fraud slash for a resolver.

   Mirrors the corrected slashForFraud (Fix A): fraud slashes start as PENDING
   with an appeal window, not immediately EXECUTED.

   Captures the reversal-detection-probability from the module snapshot to track
   the likelihood that an appeal will succeed.

   Emits :fraud-slash-proposed evidence and stores the evidence hash on the
   pending entry for causal linking by execute-fraud-slash."
  [world slash-id workflow-id resolver slash-amt appeal-window reversal-prob
   & {:keys [authorization-provenance]}]
  (let [et  (t/get-transfer world workflow-id)
        token (:token et "USDC")
        now (time-ctx/block-ts world)
        evidence-map (attr/with-attribution
                       {:subject/type :slash
                        :subject/id slash-id
                        :action/type :slash/propose
                        :evidence/reason :fraud-slash-proposed}
                       (cap/capture-event-evidence!
                        :fraud-slash-proposed
                        {:proposal/world-before (select-keys world [:resolver-stakes :total-held])}
                        {:proposal/world-after  (select-keys world [:resolver-stakes :total-held])}
                        {:proposal/resolver resolver
                         :proposal/amount slash-amt
                         :proposal/status :pending
                         :proposal/deadline (+ now appeal-window)
                         :proposal/workflow-id workflow-id}
                        nil
                        {:world-before world
                         :world-after world}))
        evidence-hash (:evidence/hash evidence-map)]
    (attr/log-with-attr :debug "handle-fraud-slashing" {:now now :appeal-window appeal-window})
    (let [entry (append-authorization-provenance
                 {:slash/id                      slash-id
                  :slash/workflow-id             workflow-id
                  :slash/kind                    :fraud
                  :slash/level                   0
                  ;; Legacy field names remain during the transition so existing
                  ;; accounting and invariant readers continue to work.
                  :resolver                       resolver
                  :amount                         slash-amt
                  :token                          token
                  :workflow-id                    workflow-id
                  :reason                         :fraud
                  :status                         :pending
                  :proposed-at                    now
                  :appeal-deadline                (+ now appeal-window)
                  :appeal-bond-held               0
                  :contest-deadline               0
                  :reversal-detection-probability reversal-prob
                  :proposal-evidence-hash         evidence-hash}
                 "propose-fraud-slash"
                 authorization-provenance)]
      ;; The workflow-ID alias preserves legacy scenario ingestion only; the
      ;; Canonical registry is keyed solely by the allocated slash ID.
      (t/insert-slash world entry))))

(defn update-unavailability
  "Idempotent resolver unavailability accounting + circuit breaker trigger.
   Mirrors Solidity behavior at a model level."
  [world resolver unavailable?]
  (let [prev-unavailable? (contains? (:resolver-unavailable world #{}) resolver)
        changed? (not= prev-unavailable? (boolean unavailable?))
        world' (cond
                 (and unavailable? (not prev-unavailable?))
                 (-> world
                     (update :resolver-unavailable (fnil conj #{}) resolver)
                     (update-in [:unavailability-stats :unavailable-count] (fnil inc 0)))

                 (and (not unavailable?) prev-unavailable?)
                 (-> world
                     (update :resolver-unavailable disj resolver)
                     (update-in [:unavailability-stats :unavailable-count] (fnil #(max 0 (dec %)) 0)))

                 :else world)
        world'' (assoc-in world' [:unavailability-stats :last-update] (time-ctx/block-ts world))
        total (get-in world'' [:unavailability-stats :total-resolvers] 0)
        unavailable (get-in world'' [:unavailability-stats :unavailable-count] 0)
        threshold (get-in world'' [:circuit-breaker :threshold-bps] 3000)
        pct-bps (if (pos? total) (quot (* unavailable 10000) total) 0)
        breaker-was-active? (get-in world'' [:circuit-breaker :active?] false)
        world''' (cond
                   ;; Threshold exceeded — activate (or keep) the circuit breaker
                   (and (pos? total) (>= pct-bps threshold))
                   (-> world''
                       (assoc-in [:circuit-breaker :active?] true)
                       (cond-> (not breaker-was-active?)
                         (assoc-in [:circuit-breaker :last-trigger] (time-ctx/block-ts world))))

                   ;; Below threshold but breaker still active — deactivate after cooldown
                   (and breaker-was-active?
                        (let [cooldown (get-in world'' [:circuit-breaker :cooldown] 3600)
                              elapsed (- (time-ctx/block-ts world)
                                         (get-in world'' [:circuit-breaker :last-trigger] 0))]
                          (>= elapsed cooldown)))
                   (assoc-in world'' [:circuit-breaker :active?] false)

                   :else world'')
        breaker-active? (get-in world''' [:circuit-breaker :active?] false)]
    (when (or changed? (not= breaker-was-active? breaker-active?))
      (attr/with-attribution {:subject/type :resolver
                              :subject/id resolver
                              :action/type (if unavailable? :resolver/unavailable :resolver/available)
                              :evidence/reason :resolver-unavailability-changed}
        (cap/capture-event-evidence!
         :resolver-unavailability-changed
         {:unavailability/before {:unavailable-count (get-in world [:unavailability-stats :unavailable-count] 0)
                                  :circuit-breaker-active? (get-in world [:circuit-breaker :active?] false)}}
         {:unavailability/after  {:unavailable-count unavailable
                                  :circuit-breaker-active? breaker-active?}}
         {:unavailability/resolver resolver
          :unavailability/unavailable? unavailable?
          :unavailability/circuit-breaker-triggered? (and (not breaker-was-active?) breaker-active?)
          :unavailability/circuit-breaker-cleared? (and breaker-was-active? (not breaker-active?))
          :unavailability/pct-bps pct-bps
          :unavailability/threshold-bps threshold}
         nil
         {:world-before world
          :world-after world'''})))
    world'''))

(defn circuit-breaker-active?
  "True when the circuit breaker is active (blocking new escrows/disputes).
   Returns {:ok true} or {:ok false :error :circuit-breaker-active}."
  [world]
  (if (get-in world [:circuit-breaker :active?] false)
    (t/fail :circuit-breaker-active)
    (t/ok true)))

(declare pick-eligible-superseded-pending)

;; ---------------------------------------------------------------------------
;; execute-resolution
;;
;; Mirrors: BaseEscrow._executeResolution
;;
;; Guards:
;;   1. workflow-id must exist
;;   2. caller must be an authorized dispute resolver
;;   3. state must be :disputed
;;
;; Appeal window logic (SettlementOps.computeResolutionExecution):
;;   If snap.appeal-window-duration > 0:
;;     → store PendingSettlement, do NOT finalize immediately
;;   Else:
;;     → finalize immediately (release or refund)
;;
;; When escalation occurs (dispute is escalated to a higher level):
;;   Any existing pending-settlement is cancelled (see _validateAndPrepareEscalation).
;;   This function models the normal case (no concurrent escalation).
;; ---------------------------------------------------------------------------

(defn- clear-stale-settlement-principal
  "Remove abnormal :settlement/principal claimables while escrow is still :disputed.
   Normal flow records principal only at finalize; stale entries can appear from
   superseded pending write-sets or legacy regressions. Does not clear :settlement/yield."
  [world workflow-id]
  (acct/clear-claimable-v2-kind world workflow-id :settlement/principal))

(defn- clear-pending-settlement [world workflow-id]
  (let [pending (t/get-pending world workflow-id)]
    (if (:exists pending)
      (-> world
          (clear-stale-settlement-principal workflow-id)
          (assoc-in [:pending-settlements workflow-id] t/empty-pending-settlement))
      world)))

(declare archive-current-pending-settlement)

(defn apply-resolution-transition
  "Core resolution state transition once authorization is confirmed.
   Skips the authorized-resolver? check — caller must gate it separately.
   resolution-source is a keyword like :resolver-overflow for provenance."
   [world workflow-id caller is-release resolution-hash resolution-module-fn
    & {:keys [resolution-source authorization-provenance]}]
  (let [ctx (attr/make-context {:workflow-id workflow-id})]
    (attr/log-annotated! :debug "Submitting resolution" ctx {:caller caller})
    (cond
      (not (t/valid-workflow-id? world workflow-id))
      (guard-fail :invalid-workflow-id :workflow-id workflow-id)

      (not= :disputed (t/escrow-state world workflow-id))
      (guard-fail :transfer-not-in-dispute
                  :escrow-state (t/escrow-state world workflow-id)
                  :workflow-id workflow-id)

      :else
      (let [world          (if (:exists (t/get-pending world workflow-id))
                              (archive-current-pending-settlement world workflow-id)
                              world)
            world          (clear-pending-settlement world workflow-id)
            world          (lc/accrue-yield world workflow-id)
            snap           (t/get-snapshot world workflow-id)
            window-dur     (max (:appeal-window-duration snap 0)
                                (:challenge-window-duration snap 0))
            now            (time-ctx/block-ts world)
            final-round?   (t/final-round? world workflow-id)
            world' (-> world
                        (handle-reversal-slashing workflow-id is-release)
                        (reverse-reversal-slash-on-vindication workflow-id is-release))
            world''        (assoc-in world' [:previous-decisions workflow-id (t/dispute-level world workflow-id)]
                                     {:resolver caller :is-release is-release
                                      :resolution-source (or resolution-source :normal)})
            decision-info {:decision-id (str "resolve-" workflow-id "-" (t/dispute-level world workflow-id))
                           :step (time-ctx/block-ts world)
                           :alternatives [:release :refund]
                           :selected (if is-release :release :refund)
                           :reasoning (str "Resolver " caller " " (if is-release "releases" "refunds")
                                           " escrow " workflow-id)
                           :caller caller
                           :workflow-id workflow-id}
            decision-evidence (emit-decision-evidence! decision-info)
            world'''       (assoc-in world'' [:escrow-transfers workflow-id :resolution]
                                     (cond-> {:resolved-by caller
                                              :is-release is-release
                                              :resolution-hash resolution-hash
                                              :resolution-source (or resolution-source :normal)}
                                       true
                                       (assoc :trace-decision
                                              (transfer-trace-decision-summary
                                               decision-info
                                               (:evidence-hash decision-evidence)))
                                       (:evidence-hash decision-evidence)
                                       (assoc :decision-evidence-hash (:evidence-hash decision-evidence))
                                       authorization-provenance
                                       (assoc :authorization/provenance authorization-provenance)))]
        (if (or final-round? (not (pos? window-dur)))
          (let [finalized (if is-release
                            (finalize world''' workflow-id :released
                                      :authorization-provenance authorization-provenance)
                            (finalize world''' workflow-id :refunded
                                      :authorization-provenance authorization-provenance))]
            (if (:ok finalized)
              (t/ok (lc/cleanup-orphaned-slashes (:world finalized) workflow-id))
              finalized))
          (let [pending (t/make-pending-settlement
                         {:exists          true
                          :is-release      is-release
                          :appeal-deadline (+ now window-dur)
                          :resolution-hash resolution-hash})
                world'''' (assoc-in world''' [:pending-settlements workflow-id] pending)]
            (t/ok world'''')))))))

(defn execute-resolution
  "Submit a resolution decision for a :disputed escrow.
   Gates on authorized-resolver? then delegates to apply-resolution-transition."
  [world workflow-id caller is-release resolution-hash resolution-module-fn]
  (cond
    (not (t/valid-workflow-id? world workflow-id))
    (guard-fail :invalid-workflow-id :workflow-id workflow-id)

    (not= :disputed (t/escrow-state world workflow-id))
    (guard-fail :transfer-not-in-dispute
                :escrow-state (t/escrow-state world workflow-id)
                :workflow-id workflow-id)

    (not (auth/authorized-resolver? world workflow-id caller resolution-module-fn))
    (guard-fail :not-authorized-resolver
                :caller caller
                :dispute-level (t/dispute-level world workflow-id)
                :workflow-id workflow-id)

    :else
    (apply-resolution-transition world workflow-id caller is-release
                                 resolution-hash resolution-module-fn)))

;; ---------------------------------------------------------------------------
;; execute-resolution-refused (Kleros ruling 0 — refuse to arbitrate)
;;
;; When Kleros returns ruling 0, the dispute cannot be resolved through
;; the normal release/refund path.  The escrow stays :disputed and must
;; rely on the max-dispute-duration timeout for settlement.
;;
;; Guards: same as execute-resolution
;; ---------------------------------------------------------------------------

(defn execute-resolution-refused
  "Record that the arbitrator refused to rule (Kleros ruling 0).
   Unlike normal resolutions, this does NOT finalize the escrow or create
   a pending settlement.  The escrow remains :disputed until the
   max-dispute-duration timeout triggers auto-cancel.
   The refusal is recorded on the escrow's :resolution map as {:refused true}."
  [world workflow-id caller resolution-hash resolution-module-fn]
  (cond
    (not (t/valid-workflow-id? world workflow-id))
    (guard-fail :invalid-workflow-id :workflow-id workflow-id)

    (not= :disputed (t/escrow-state world workflow-id))
    (guard-fail :transfer-not-in-dispute
                :escrow-state (t/escrow-state world workflow-id)
                :workflow-id workflow-id)

    (not (auth/authorized-resolver? world workflow-id caller resolution-module-fn))
    (guard-fail :not-authorized-resolver
                :caller caller
                :dispute-level (t/dispute-level world workflow-id)
                :workflow-id workflow-id)

    :else
    (let [world (lc/accrue-yield world workflow-id)
          now   (time-ctx/block-ts world)
          world' (-> world
                     (assoc-in [:escrow-transfers workflow-id :resolution/refused] true)
                     (assoc-in [:escrow-transfers workflow-id :resolution/refused-by] caller)
                     (assoc-in [:escrow-transfers workflow-id :resolution/refused-at] now)
                     (assoc-in [:escrow-transfers workflow-id :resolution/refused-hash] resolution-hash))]
      (t/ok world'))))

;; ---------------------------------------------------------------------------
;; execute-pending-settlement
;;
;; Mirrors: BaseEscrow.executePendingSettlement
;;
;; Guards:
;;   1. workflow-id must exist
;;   2. pending-settlement must exist (pending.exists = true)
;;   3. state must be :disputed
;;   4. block-time >= appeal-deadline
;; ---------------------------------------------------------------------------

(defn execute-pending-settlement
  "Execute a deferred settlement after the appeal window has closed."
  [world workflow-id]
  (if-not (t/valid-workflow-id? world workflow-id)
    (guard-fail :invalid-workflow-id :workflow-id workflow-id)
    (let [active-pending (t/get-pending world workflow-id)
          now-ts         (time-ctx/block-ts world)
          pending        (if (:exists active-pending)
                           active-pending
                           (pick-eligible-superseded-pending world workflow-id now-ts))]
      (cond
        (not (:exists pending))
        (guard-fail :no-pending-settlement :workflow-id workflow-id)

        (not= :disputed (t/escrow-state world workflow-id))
        (guard-fail :transfer-not-in-dispute
                    :escrow-state (t/escrow-state world workflow-id)
                    :workflow-id workflow-id)

        (< now-ts (:appeal-deadline pending))
        (guard-fail :appeal-window-not-expired
                    :block-time now-ts
                    :appeal-deadline (:appeal-deadline pending)
                    :workflow-id workflow-id)

        ;; Yield readiness guard: block settlement if yield position has
        ;; positive yield but last-accrual-time is set and behind current
        ;; block time. If last-accrual-time is nil (never accrued), allow
        ;; settlement — the yield state is empty or not yet initialized.
        (let [owner-id (t/escrow-yield-owner-id workflow-id)
              pos (get-in world [:yield/positions owner-id])
              last-accrual (:last-accrual-time pos)]
          (and pos
               (pos? (+ (:unrealized-yield pos 0) (:realized-yield pos 0)))
               (some? last-accrual)
               (< last-accrual now-ts)))
        (guard-fail :yield-position-unsettled
                    :workflow-id workflow-id
                    :block-time now-ts
                    :last-accrual-time (get-in world [:yield/positions (t/escrow-yield-owner-id workflow-id) :last-accrual-time])
                    :unrealized-yield (get-in world [:yield/positions (t/escrow-yield-owner-id workflow-id) :unrealized-yield])
                    :realized-yield (get-in world [:yield/positions (t/escrow-yield-owner-id workflow-id) :realized-yield]))

        :else
        (let [;; Read any force-authorisation provenance stored on the resolution
              ;; record so it flows through to the escrow settlement held adjustment.
              auth-prov (get-in world [:escrow-transfers workflow-id :resolution
                                       :authorization/provenance])
              finalized (if (:is-release pending)
                          (finalize world workflow-id :released
                                    :authorization-provenance auth-prov)
                          (finalize world workflow-id :refunded
                                    :authorization-provenance auth-prov))]
          (if (:ok finalized)
            (t/ok (lc/cleanup-orphaned-slashes (:world finalized) workflow-id))
            finalized))))))

;; ---------------------------------------------------------------------------
;; Internal building block: _validateAndPrepareEscalation deletes
;; pendingSettlements[workflowId] before escalation proceeds.
;; ---------------------------------------------------------------------------

(def ^:private max-superseded-pending-per-workflow
  "Maximum number of superseded pending entries retained per workflow.
   Older entries beyond this cap are discarded to prevent unbounded growth
   in repeated escalation/challenge cycles."
  5)

(defn- archive-current-pending-settlement
  "Archive the current pending settlement as superseded and clear active pending.
   Called both during normal resolution submission (when a previous pending
   settlement exists) and during escalation/challenge.  The archived entry
   preserves a fallback execution path for edge-cases where the replacement
   decision is not produced in time.
   Caps retained entries to max-superseded-pending-per-workflow."
  [world workflow-id]
  (let [pending (t/get-pending world workflow-id)]
    (if (:exists pending)
      (-> world
          (clear-stale-settlement-principal workflow-id)
          (update-in [:superseded-pending-settlements workflow-id]
                     (fnil conj [])
                     {:pending pending
                      :superseded-at (time-ctx/block-ts world)
                      :level (t/dispute-level world workflow-id)})
          (update-in [:superseded-pending-settlements workflow-id]
                     (fn [v] (take-last max-superseded-pending-per-workflow v)))
          (update :pending-settlements dissoc workflow-id))
      world)))

(defn- pick-eligible-superseded-pending
  "Select the superseded pending that remains the authoritative executable
   decision for the CURRENT escrow state. Returns a pending-settlement map or nil.

   A superseded decision is authoritative only while the dispute remains at the
   level at which it was superseded: escalation/challenge cancel the pending and
   advance the dispute level, so an entry archived at a lower level is stale and
   must never be recovered. Among same-level entries only the most recently
   superseded decision is authoritative (older ones were cancelled by it); an
   older decision must not execute just because its deadline has already passed.
   The caller's deadline guard then rejects the selected decision until its own
   appeal deadline has passed."
  [world workflow-id _now-ts]
  (let [current-level (t/dispute-level world workflow-id)
        same-level    (filter #(= current-level (:level %))
                              (get-in world [:superseded-pending-settlements workflow-id] []))]
    (when (seq same-level)
      (:pending (last (sort-by (fn [e] [(:superseded-at e 0)
                                        (:appeal-deadline (:pending e) 0)])
                               same-level))))))

;; ---------------------------------------------------------------------------
;; challenge-resolution (Phase L)
;;
;; Allows any third party to force an escalation by posting a challenge bond.
;; ---------------------------------------------------------------------------

(defn challenge-resolution
  "Challenge a provisional resolution decision (Phase L).
   Similar to escalate-dispute but can be called by anyone.

   caller — address of the challenger (third party or participant)
   escalation-fn — (fn [world workflow-id caller level] → {:ok bool :new-resolver addr})"
  [world workflow-id caller escalation-fn]
  (cond
    (not (t/valid-workflow-id? world workflow-id))
    (guard-fail :invalid-workflow-id :workflow-id workflow-id)

    (not= :disputed (t/escrow-state world workflow-id))
    (guard-fail :transfer-not-in-dispute
                :escrow-state (t/escrow-state world workflow-id)
                :workflow-id workflow-id)

    (t/final-round? world workflow-id)
    (guard-fail :escalation-not-allowed
                :dispute-level (t/dispute-level world workflow-id)
                :workflow-id workflow-id)

    (not (:exists (t/get-pending world workflow-id)))
    (guard-fail :no-resolution-to-challenge
                :pending-exists false
                :dispute-level (t/dispute-level world workflow-id)
                :workflow-id workflow-id)

    ;; Appeal window has closed — the pending settlement is now executable.
    (>= (time-ctx/block-ts world) (:appeal-deadline (t/get-pending world workflow-id)))
    (guard-fail :appeal-window-expired
                :block-time (time-ctx/block-ts world)
                :appeal-deadline (:appeal-deadline (t/get-pending world workflow-id))
                :workflow-id workflow-id)

    (nil? escalation-fn)
    (guard-fail :escalation-not-configured :workflow-id workflow-id)

    :else
    (let [current-level (t/dispute-level world workflow-id)
          esc-result    (escalation-fn world workflow-id caller current-level)]
      (if-not (:ok esc-result)
        (t/fail (or (:error esc-result) :escalation-not-allowed))
        (let [new-level    (inc current-level)
              new-resolver (:new-resolver esc-result)
              old-resolver (get-in world [:escrow-transfers workflow-id :dispute-resolver])
              snap         (t/get-snapshot world workflow-id)
              et           (t/get-transfer world workflow-id)

               ;; Sybil Mitigation Layer B: Linear Bond Scaling (1.1x per escalation)
              esc-count    (get-in world [:escalation-counts-per-addr caller] 0)

               ;; Challenge bond amount
              base-bond    (sew-econ/calculate-challenge-bond-amount (:amount-after-fee et) snap)
              bond-amt     (quot (* base-bond (+ 10000 (* esc-count 1000))) 10000)

               world'       (-> world
                                (cond-> (pos? bond-amt)
                                  (acct/post-appeal-bond workflow-id caller snap (:token et) bond-amt))
                                (assoc-in [:challengers workflow-id current-level] caller)
                                (archive-current-pending-settlement workflow-id)
                                (assoc-in [:dispute-levels workflow-id] new-level)
                                (assoc-in [:escrow-transfers workflow-id :dispute-resolver]
                                          new-resolver)
                                (t/decrement-resolver-capacity old-resolver)
                                (t/increment-resolver-capacity new-resolver)
                                 ;; Track last escalation timestamp per address (used by
                                ;; challenge-resolution cooldown for open challengers).
                               (assoc-in [:last-escalation-block-time-per-addr caller]
                                         (time-ctx/block-ts world))
                               ;; Increment escalation count for this address (Layer B)
                               (update-in [:escalation-counts-per-addr caller] (fnil inc 0))
                               (assoc-in [:last-escalation-block-time workflow-id]
                                         (time-ctx/block-ts world)))]
          (attr/with-attribution {:subject/type :dispute
                                  :subject/id workflow-id
                                  :action/type :dispute/challenge
                                  :evidence/reason :resolution-challenged}
            (cap/capture-event-evidence!
             :resolution-challenged
             {:challenge/before {:dispute-level current-level
                                 :resolver (get-in world [:escrow-transfers workflow-id :dispute-resolver])}}
             {:challenge/after  {:dispute-level new-level
                                 :resolver new-resolver}}
             {:challenge/workflow-id workflow-id
              :challenge/caller caller
              :challenge/from-level current-level
              :challenge/to-level new-level
              :challenge/new-resolver new-resolver
              :challenge/bond-amount bond-amt
              :challenge/escalation-count esc-count}
             nil
             {:world-before world
              :world-after world'}))
          (assoc (t/ok world')
                 :new-level    new-level
                 :new-resolver new-resolver))))))

;; ---------------------------------------------------------------------------
;; automate-timed-actions
;;
;; Mirrors: BaseEscrow.automateTimedActions
;;
;; Dispatch order:
;;   1. ACTION_EXECUTE_PENDING       — pending-settlement executable?
;;   2. ACTION_AUTO_CANCEL_DISPUTED  — auto-cancel-time passed on DISPUTED?
;;                                     (Solidity shadow: SettlementOps.sol computeTimedActions)
;;   3. ACTION_DISPUTE_TIMEOUT       — max-dispute-duration elapsed? (auto-cancel-disputed)
;;   4. ACTION_AUTO_RELEASE          — auto-release-time passed?
;;   5. ACTION_AUTO_CANCEL           — auto-cancel-time passed?
;;   6. ACTION_NONE                  — no action
;;
;; Returns {:ok bool :world world' :action kw} where action is one of:
;;   :execute-pending :auto-cancel-disputed-auto-time :auto-cancel-disputed
;;   :auto-release :auto-cancel :none
;; ---------------------------------------------------------------------------

(defn automate-timed-actions
  "Dispatch timed keeper actions for workflow-id.

   Returns {:ok true :world world' :action kw} — even when action is :none,
   to simplify caller logic."
  [world workflow-id]
  (if (not (t/valid-workflow-id? world workflow-id))
    (t/fail :invalid-workflow-id)
    (let [world (lc/accrue-yield world workflow-id)]
      (cond
        ;; Priority 1: pending settlement ready to execute
        (sm/pending-settlement-executable? world workflow-id)
        (let [r (execute-pending-settlement world workflow-id)]
          (if (:ok r)
            (assoc r :action :execute-pending)
            r))

        ;; Priority 2: auto-cancel-time passed on DISPUTED escrow
        ;; Solidity shadow: ACTION_AUTO_CANCEL_DISPUTED (SettlementOps.sol
        ;; computeTimedActions).  Without this check a frivolous dispute
        ;; raised before auto-cancel-time orphans the deadline, forcing the
        ;; escrow into the longer max-dispute-duration path.
        (sm/auto-cancel-due-on-disputed? world workflow-id)
        (let [r (lc/auto-cancel-disputed-on-auto-time world workflow-id)]
          (if (:ok r)
            (assoc r :action :auto-cancel-disputed-auto-time)
            r))

        ;; Priority 2.5: arbitrator refused to rule (Kleros ruling 0)
        ;; The escrow is stuck in :disputed with no pending settlement.
        ;; After max-dispute-duration, the keeper can auto-cancel.
        (sm/refused-resolution-timeout? world workflow-id)
        (let [r (lc/auto-cancel-disputed-escrow world workflow-id)]
          (if (:ok r)
            (assoc r :action :auto-cancel-refused-resolution)
            r))

        ;; Priority 3: dispute liveness timeout
        ;; Mirrors: BaseEscrow.autoCancelDisputedEscrow
        ;; When max-dispute-duration has elapsed since raiseDispute and no
        ;; resolution has been submitted, the keeper can auto-cancel to
        ;; refund the escrow and slash the resolver for the full amount.
        (sm/dispute-timeout-exceeded? world workflow-id)
        (let [r (lc/auto-cancel-disputed-escrow world workflow-id)]
          (if (:ok r)
            (assoc r :action :auto-cancel-disputed)
            r))

        ;; Priority 4: auto-release
        (sm/auto-release-due? world workflow-id)
        (let [r (lc/finalize-escrow-accounting world workflow-id :released)]
          (assoc r :action :auto-release))

        ;; Priority 5: auto-cancel
        (sm/auto-cancel-due? world workflow-id)
        (let [r (lc/finalize-escrow-accounting world workflow-id :refunded)]
          (assoc r :action :auto-cancel))

        :else
        (assoc (t/ok world) :action :none)))))

;; ---------------------------------------------------------------------------
;; escalate-dispute
;;
;; Mirrors: BaseEscrow.escalateDispute
;;          + DisputeOps.computeEscalation
;;          + DecentralizedResolutionModule.executeEscalation
;;
;; Guards:
;;   1. workflow-id must exist
;;   2. state must be :disputed
;;   3. caller must be :from or :to (participant only can appeal)
;;   4. current level must be < max-dispute-level (cannot escalate beyond MAX_ROUND)
;;   5. a pending settlement must exist — escalation is an appeal of a resolver's
;;      decision, not a pre-emptive jump to the next level.  Without this guard
;;      a malicious party can bypass all lower-level resolvers immediately.
;;   6. escalation-fn must return {:ok true :new-resolver addr}
;;
;; Effects (in order, matching Solidity):
;;   a. Clear pending-settlement (_validateAndPrepareEscalation)
;;   b. Increment dispute-level
;;   c. Update et.dispute-resolver to the new resolver
;;
;; escalation-fn — (fn [world workflow-id caller level] → {:ok bool :new-resolver addr})
;;   Models the combined DisputeOps.computeEscalation + DRM.executeEscalation call.
;;   Pass nil to simulate "no resolution module / escalation not allowed".
;;
;; Returns {:ok true :world world' :new-level n :new-resolver addr}
;;         or {:ok false :error kw}
;; ---------------------------------------------------------------------------

(defn escalate-dispute
  "Escalate a :disputed escrow to the next resolution round.

   Requires a pending settlement to exist: escalation is an appeal of an
   existing resolver decision, not a unilateral level-skip.

   escalation-fn — (fn [world workflow-id caller level] → {:ok bool :new-resolver addr})
                    Pass nil to simulate 'escalation not configured'."
  [world workflow-id caller escalation-fn]
  (cond
    (not (t/valid-workflow-id? world workflow-id))
    (guard-fail :invalid-workflow-id :workflow-id workflow-id)

    (not= :disputed (t/escrow-state world workflow-id))
    (guard-fail :transfer-not-in-dispute
                :escrow-state (t/escrow-state world workflow-id)
                :workflow-id workflow-id)

    (let [et (t/get-transfer world workflow-id)]
      (and (not= caller (:from et)) (not= caller (:to et))))
    (guard-fail :not-participant :caller caller :workflow-id workflow-id)

    (t/final-round? world workflow-id)
    (guard-fail :escalation-not-allowed
                :dispute-level (t/dispute-level world workflow-id)
                :workflow-id workflow-id)

    ;; Escalation is an appeal: a resolver must have already submitted a
    ;; resolution (creating a pending settlement) before a party may escalate.
    (not (:exists (t/get-pending world workflow-id)))
    (guard-fail :no-resolution-to-appeal
                :pending-exists false
                :dispute-level (t/dispute-level world workflow-id)
                :workflow-id workflow-id)

    ;; Appeal window has closed — the pending settlement is now executable.
    (>= (time-ctx/block-ts world) (:appeal-deadline (t/get-pending world workflow-id)))
    (guard-fail :appeal-window-expired
                :block-time (time-ctx/block-ts world)
                :appeal-deadline (:appeal-deadline (t/get-pending world workflow-id))
                :workflow-id workflow-id)

    (nil? escalation-fn)
    (guard-fail :escalation-not-configured :workflow-id workflow-id)

    :else
    (let [current-level (t/dispute-level world workflow-id)
          esc-result    (escalation-fn world workflow-id caller current-level)]
      (if-not (:ok esc-result)
        (t/fail (or (:error esc-result) :escalation-not-allowed))
        (let [new-level    (inc current-level)
              new-resolver (:new-resolver esc-result)
              old-resolver (get-in world [:escrow-transfers workflow-id :dispute-resolver])

               ;; Sybil Mitigation Layer B: Linear Bond Scaling (1.1x per escalation)
              esc-count    (get-in world [:escalation-counts-per-addr caller] 0)

               ;; DR3 Sync: handle appeal bond posting
              snap         (t/get-snapshot world workflow-id)
              et           (t/get-transfer world workflow-id)
              base-bond    (sew-econ/calculate-appeal-bond-amount (:amount-after-fee et) snap)
               bond-amt     (quot (* base-bond (+ 10000 (* esc-count 1000))) 10000)

                ;; Ensure workflow exists in bond-balances before updating
               world-prepared (if (get-in world [:bond-balances workflow-id])
                                world
                                (assoc-in world [:bond-balances workflow-id] {}))

               ;; post-appeal-bond is skipped for zero bond (harmless no-op)
               world'       (-> world-prepared
                                (cond-> (pos? bond-amt)
                                  (acct/post-appeal-bond workflow-id caller snap (:token et) bond-amt))
                                (archive-current-pending-settlement workflow-id)
                                (assoc-in [:challengers workflow-id current-level] caller)
                                (assoc-in [:dispute-levels workflow-id] new-level)
                                (assoc-in [:escrow-transfers workflow-id :dispute-resolver]
                                          new-resolver)
                                (t/decrement-resolver-capacity old-resolver)
                                (t/increment-resolver-capacity new-resolver)
                                ;; Track when this escalation occurred for this address (Layer A)
                                (assoc-in [:last-escalation-block-time-per-addr caller]
                                          (time-ctx/block-ts world))
                                ;; Increment escalation count for this address (Layer B)
                                (update-in [:escalation-counts-per-addr caller] (fnil inc 0))
                ;; Track when this escalation occurred for this workflow (Invariant check)
                                (assoc-in [:last-escalation-block-time workflow-id]
                                          (time-ctx/block-ts world)))]
          (attr/with-attribution {:subject/type :dispute
                                  :subject/id workflow-id
                                  :action/type :dispute/escalate
                                  :evidence/reason :dispute-escalated}
            (cap/capture-event-evidence!
             :dispute-escalated
             {:escalation/before {:dispute-level current-level
                                  :resolver (get-in world [:escrow-transfers workflow-id :dispute-resolver])}}
             {:escalation/after  {:dispute-level new-level
                                  :resolver new-resolver}}
             {:escalation/workflow-id workflow-id
              :escalation/caller caller
              :escalation/from-level current-level
              :escalation/to-level new-level
              :escalation/new-resolver new-resolver
              :escalation/bond-amount bond-amt
              :escalation/escalation-count esc-count}
             nil
             {:world-before world
              :world-after world'}))
          (emit-decision-evidence!
           {:decision-id (str "escalate-" workflow-id "-" current-level)
            :step (time-ctx/block-ts world)
            :alternatives [:accept-resolution :escalate]
            :selected :escalate
            :reasoning (str "Participant " caller " escalated dispute " workflow-id
                            " from level " current-level " to " new-level)
            :caller caller
            :workflow-id workflow-id})
          (assoc (t/ok world')
                 :new-level    new-level
                 :new-resolver new-resolver))))))

(defn- resolver-has-other-active-disputes?
  "True when resolver-addr is assigned to at least one :disputed escrow on a
   workflow OTHER than exclude-wf-id.  Used to prevent freezing a resolver
   who has unrelated active disputes that governance has not rotated away."
  [world resolver-addr exclude-wf-id]
  (boolean
   (some (fn [[wf-id et]]
           (and (= :disputed (:escrow-state et))
                (= resolver-addr (:dispute-resolver et))
                (not= wf-id exclude-wf-id)))
         (:escrow-transfers world))))

(defn execute-fraud-slash
  "Execute a previously proposed fraud slash after the timelock/appeal window.
   Guards against freezing a resolver with active disputed escrows to avoid
   resolver-not-frozen-on-assign invariant violations.
   `slash-id` must be a canonical protocol entity ID belonging to `workflow-id`.
   Mirrors: ResolverSlashingModuleV1.executeSlash"
  ([world workflow-id] (execute-fraud-slash world workflow-id workflow-id))
  ([world workflow-id slash-id & {:keys [execution-provenance]}]
   (let [pending (get-in world [:pending-fraud-slashes slash-id])]
     (cond
       (not (t/valid-entity-id? slash-id))
       (t/fail :invalid-slash-id)

       (nil? pending)
       (t/fail :slash-not-found)

       (not= (:slash/workflow-id pending) workflow-id)
       (t/fail :slash-workflow-mismatch)

       (not= :pending (:status pending))
        (t/fail (case (:status pending)
                  :appealed :appeal-in-progress
                  :reversed :slash-already-reversed
                  :reversed-with-credit :slash-already-reversed
                  :executed :already-executed
                   :unknown-status))

       ;; The deadline belongs to the resolver's appeal window.  Execution is
       ;; permitted strictly after it so same-timestamp appeal and execution
       ;; cannot be ordered to defeat a timely appeal.
       (<= (time-ctx/block-ts world) (:appeal-deadline pending))
       (t/fail :timelock-not-expired)

       ;; Prevent freeze-on-active-dispute: the resolver must not be frozen
       ;; while assigned to a :disputed escrow on a DIFFERENT workflow.
       ;; The same-workflow dispute is allowed because keeper settles it
       ;; independently.  For other-workflow disputes, governance must rotate
       ;; the resolver away first.
       (resolver-has-other-active-disputes? world (:resolver pending) workflow-id)
       (t/fail :freeze-blocked-active-dispute)

       :else
       (let [resolver        (:resolver pending)
             amount          (:amount pending)
             current-stake   (reg/get-stake world resolver)
             epoch-cap-bps   (get-in world [:params :slash-epoch-cap-bps] 2000)
             epoch-slashed   (get-in world [:resolver-epoch-slashed resolver :amount] 0)
             total-epoch     (+ epoch-slashed amount)]
         (cond
           (and (pos? current-stake)
                (> (* total-epoch 10000) (* current-stake epoch-cap-bps)))
           (t/fail :slash-epoch-cap-exceeded)

           :else
           (let [freeze-duration (* (get-in world [:params :freeze-duration-days] 3) (time-ctx/tick-seconds world))
                  block-time      (time-ctx/block-ts world)
                 freeze-until    (+ block-time freeze-duration)
                 wf-for-token    (or (:workflow-id pending) workflow-id)
                 slashing-result (-> world
                                     (update-in [:pending-fraud-slashes slash-id]
                                                append-execution-provenance
                                                "execute-fraud-slash"
                                                execution-provenance)
                                     (assoc-in [:pending-fraud-slashes slash-id :status] :executed)
                                     (reg/slash-resolver-stake resolver amount nil 0 wf-for-token))
                  world-slashed   (:world slashing-result)
                  stake-evidence-hash (:stake-evidence-hash slashing-result)
                  actual-debited (or (:slashed-from-stake slashing-result) amount)
                  epoch-after    (+ epoch-slashed actual-debited)
                  world'          (-> world-slashed
                                      (assoc-in [:resolver-frozen-until resolver] freeze-until)
                                      (assoc-in [:resolver-epoch-slashed resolver :amount] epoch-after)
                                     (update-unavailability resolver true)
                                      (lc/cleanup-orphaned-slashes workflow-id))
                 allocation-input {:slash-obligation amount
                                   :ended-at freeze-until
                                   :slash-policy {:type :fraud-slash
                                                  :single-resolver true
                                                  :per-offense-cap-bps (get-in world
                                                                               [:params :max-slash-per-offense-bps]
                                                                               5000)}
                                   :basis :slashable-stake
                                   :cap-field :available-slashable
                                   :liable-parties [{:id resolver
                                                     :ended-at freeze-until
                                                     :slashable-stake current-stake
                                                     :available-slashable current-stake}]}
                 world-before-hash (hc/hash-with-intent {:hash/intent :world-structure} world)
                 action-map      {:action/type :slash/execute
                                  :slash-id slash-id
                                  :workflow-id workflow-id
                                  :resolver resolver
                                  :amount amount
                                  :reason :fraud-slash}
                 action-hash     (hc/hash-with-intent {:hash/intent :action} action-map)
                 step            (:ctx/event-index (attr/current-attribution) 0)
action-hash-at  (hc/hash-with-intent {:hash/intent :action-at}
                                                        {:action-hash action-hash
                                                         :step step
                                                         :block-time block-time})
                 projection      (sew-econ/build-sew-slash-projection-artifact
                                  (assoc allocation-input
                                         :world-before-hash world-before-hash
                                         :action-hash-at action-hash-at))
                 allocation      (sew-econ/calculate-sew-slash-allocation-from-projection projection)]
             (let [slash-attribution {:subject/type :slash
                                      :subject/id slash-id
                                      :action/type :slash/execute
                                      :evidence/reason :fraud-slash-executed}]
               (attr/with-attribution
                 slash-attribution
                 (let [{:keys [evidence artifact]}
                       (slashing-ev/build-prorata-slash-evidence
                        {:world world-slashed
                         :slash-id slash-id
                         :workflow-id workflow-id
                         :resolver resolver
                         :epoch (get-in world-slashed [:resolver-epoch-slashed resolver :epoch-start] 0)
                         :trigger :fraud-slash
                         :allocation-input allocation-input
                         :projection-artifact projection
                         :allocation-result allocation
                         :transition-dependencies (filterv some?
                                                           [(:proposal-evidence-hash pending)
                                                            stake-evidence-hash])
                         :world-before-hash world-before-hash
                         :action-hash action-hash
                         :action-hash-at action-hash-at
                         :attribution (attr/current-attribution)})
                       evidence (assoc evidence
                                       :world/before-full-hash world-before-hash
                                       :world/after-full-hash (hc/hash-with-intent {:hash/intent :world-structure} world-slashed))]
                    (cap/capture-event-evidence! evidence)
                    (slashing-ev/write-allocation-result-artifact! artifact)))
               (cap/capture-event-evidence!
                :resolver-frozen
                {:freeze/before {:frozen-until (get-in world-slashed [:resolver-frozen-until resolver])}}
                {:freeze/after  {:frozen-until freeze-until}}
                {:freeze/resolver resolver
                 :freeze/duration freeze-duration
                 :freeze/trigger :execute-fraud-slash}
                nil
                {:world-before world-slashed
                 :world-after world'})
               (t/ok world')))))))))

(defn propose-fraud-slash
  "Governance (TIMELOCK) proposes a manual fraud slash for a resolver (Phase M).
   Mirrors: ResolverSlashingModuleV1.proposeSlash.

   Preconditions: workflow exists, entered dispute/resolution path, resolver matches
   escrow dispute-resolver, positive amount, no other pending/appealed slash on workflow-id.
   Timelock length uses :appeal-window-duration from the escrow module snapshot."
  [world workflow-id caller resolver-addr amount & {:keys [authorization-provenance]}]
  (let [wf-id (t/normalize-workflow-id workflow-id)
        reject!
        (fn [error-kw & extra-ctx]
          (attr/with-attribution {:subject/type :resolver
                                  :subject/id resolver-addr
                                  :action/type :slash/propose-rejected
                                  :evidence/reason error-kw}
            (cap/capture-event-evidence!
             :fraud-slash-rejected
             {:slash/proposal {:workflow-id wf-id :caller caller :resolver resolver-addr :amount amount}}
             {:slash/error error-kw}
             (merge {:slash/error error-kw :slash/workflow-id wf-id
                     :slash/resolver resolver-addr :slash/amount amount}
                    (apply hash-map extra-ctx))
             nil
             {:world-before world :world-after world}))
          (t/fail error-kw))]
    (cond
      (or (nil? caller) (= "" caller))
      (reject! :missing-caller-context)

      (not (t/valid-workflow-id? world wf-id))
      (reject! :invalid-workflow-id)

      (not (fraud-slash-workflow-eligible? world wf-id))
      (reject! :workflow-not-slashable)

      (active-manual-fraud-slash? world wf-id)
      (reject! :slash-already-pending)

      (or (nil? amount) (not (number? amount)) (<= amount 0))
      (reject! :invalid-slash-amount)

      :else
      (let [dispute-resolver (get-in world [:escrow-transfers wf-id :dispute-resolver])
            resolved-by      (get-in world [:escrow-transfers wf-id :resolution :resolved-by])]
        (cond
          (or (nil? dispute-resolver) (= "" dispute-resolver))
          (reject! :invalid-resolver-addr :dispute-resolver dispute-resolver)

          ;; Accept if the address matches either the current dispute-resolver
          ;; (normal resolution path) or the resolution's resolved-by field
          ;; (overflow/failover path).  The failover resolver is whoever
          ;; actually executed the resolution, regardless of who the current
          ;; dispute-resolver is.
          (not (or (= dispute-resolver resolver-addr)
                   (= resolved-by resolver-addr)))
          (reject! :slash-resolver-mismatch :dispute-resolver dispute-resolver :expected resolver-addr)

          :else
          (let [current-stake (reg/get-stake world resolver-addr)
                max-bps       (get-in world [:params :max-slash-per-offense-bps] 5000)]
            (cond
              (<= current-stake 0)
              (reject! :insufficient-resolver-stake :current-stake current-stake)

              ;; Per-offense cap: governance slash may not exceed 50% of current stake
              (> (* amount 10000) (* current-stake max-bps))
              (reject! :slash-exceeds-max-per-offense :current-stake current-stake :max-bps max-bps)

              :else
              (let [snap              (t/get-snapshot world wf-id)
                    appeal-days       (get-in world [:params :appeal-window-days] 7)
                     gov-delay         (or (:appeal-window-duration snap) (* appeal-days (time-ctx/tick-seconds world)))
                    reversal-prob     (or (:reversal-detection-probability snap) 0.0)]
                (let [slash-id (t/allocate-slash-id world)
                      world'   (handle-fraud-slashing world slash-id wf-id resolver-addr amount gov-delay reversal-prob
                                                      :authorization-provenance authorization-provenance)]
                  (assoc (t/ok world') :slash-id slash-id))))))))))

(defn- canonical-resolver-order [resolver-id]
  ;; Resolver IDs are protocol identifiers, so their stable textual form is the
  ;; canonical ordering used by the pro-rata allocator's input-order policy.
  (str resolver-id))

(def ^:private fraud-incident-schema-version "fraud-incident.v1")
(def ^:private fraud-incident-ref-schema-version "fraud-incident-ref.v1")
(def ^:private fraud-group-incident-kind :governance-declared-group-fraud)

(defn- fraud-incident-content
  "The immutable, hash-bound declaration fields. Authorization and provenance
   describe the declaration action but are not part of the incident identity."
  [world caller incident]
  {:incident/schema-version fraud-incident-schema-version
   :incident/id (:incident/id incident)
   :incident/kind (:incident/kind incident)
   :incident/status :declared
   :incident/declared-by caller
   :incident/declared-at (select-keys (time-ctx/temporal-context world)
                                      [:schema-version :step :event-seq :block-ts :clock/mode])
   :incident/affected-workflows (vec (:incident/affected-workflows incident))
   :incident/related-claims (vec (:incident/related-claims incident))
   :incident/evidence-refs (vec (:incident/evidence-refs incident))
   :incident/rationale (:incident/rationale incident)})

(defn declare-fraud-incident
  "Declare an immutable, independently addressable fraud incident.

   Incidents describe the alleged common event; they do not authorize or derive
   liability. A later fraud-group slash must bind this exact declaration by ID
   and canonical hash."
  [world caller incident authorization provenance]
  (let [incident-id (:incident/id incident)
        kind (:incident/kind incident)
        workflows (vec (:incident/affected-workflows incident))
        normalized (fraud-incident-content world caller incident)]
    (cond
      (or (not (string? incident-id)) (not (re-matches #"[a-z0-9]+(?:-[a-z0-9]+)*" incident-id)))
      (t/fail :invalid-fraud-incident-id)
      (not= kind fraud-group-incident-kind) (t/fail :unsupported-fraud-incident-kind)
      (empty? workflows) (t/fail :empty-incident-affected-workflows)
      (some #(not (t/valid-workflow-id? world (:workflow-id %))) workflows)
      (t/fail :invalid-incident-workflow)
      (contains? (:fraud-incidents world {}) incident-id) (t/fail :fraud-incident-already-declared)
      :else
      (let [incident-hash (hc/hash-with-intent {:hash/intent :provenance} normalized)
            record (assoc normalized :incident/hash incident-hash
                                     :incident/authorization authorization
                                     :incident/provenance provenance)
            world' (assoc-in world [:fraud-incidents incident-id] record)]
        (attr/with-attribution {:subject/type :fraud-incident :subject/id incident-id
                                :action/type :fraud-incident/declare
                                :evidence/reason :fraud-incident-declared}
          (cap/capture-event-evidence!
           :fraud-incident-declared
           {:fraud-incident/id incident-id :fraud-incident/hash incident-hash}
           {:fraud-incident/record record}
           {:fraud-incident/id incident-id :fraud-incident/hash incident-hash}))
        (assoc (t/ok world') :incident-id incident-id :incident-hash incident-hash
                               :incident record)))))

(defn- resolve-fraud-incident-ref [world workflow-id ref]
  (let [incident-id (:incident-id ref)
        incident-hash (:incident-hash ref)
        incident (get-in world [:fraud-incidents incident-id])]
    (cond
      (not= fraud-incident-ref-schema-version (:schema-version ref)) {:error :unsupported-fraud-incident-ref-schema}
      (or (not (string? incident-id)) (not (re-matches #"[a-z0-9]+(?:-[a-z0-9]+)*" incident-id))) {:error :invalid-fraud-incident-id}
      (nil? incident) {:error :fraud-incident-not-found}
      (not= incident-id (:incident/id incident)) {:error :fraud-incident-id-mismatch}
      (not= incident-hash (:incident/hash incident)) {:error :fraud-incident-hash-mismatch}
      (not= (:incident/hash incident)
            (hc/hash-with-intent
             {:hash/intent :provenance}
             (fraud-incident-content world (:incident/declared-by incident) incident)))
      {:error :fraud-incident-hash-mismatch}
      (not= fraud-group-incident-kind (:incident/kind incident)) {:error :incompatible-fraud-incident-kind}
      (not= :declared (:incident/status incident)) {:error :fraud-incident-not-eligible}
      (not (some #(= workflow-id (:workflow-id %)) (:incident/affected-workflows incident))) {:error :fraud-incident-workflow-mismatch}
      :else {:incident-ref {:schema-version fraud-incident-ref-schema-version
                            :incident-id incident-id :incident-hash incident-hash}})))

(defn propose-fraud-group-slash
  "Propose an appealable, pro-rata fraud slash against a liable resolver group.

   The member stakes are snapshotted at proposal time.  Execution uses that
   immutable, canonically ordered snapshot, preventing intermediate mutations
   from changing liability shares.  `authorization` and `provenance` are
   retained verbatim for the governance/audit layer."
  [world workflow-id caller liable-resolvers amount liability-justification authorization provenance]
  (let [wf-id (t/normalize-workflow-id workflow-id)
        incident-result (resolve-fraud-incident-ref world wf-id (:incident-ref liability-justification))
        members (vec liable-resolvers)
        member-ids (mapv #(if (map? %) (:id %) %) members)
        reject (fn [error] (t/fail error))]
    (cond
      (or (nil? caller) (= "" caller))
      (reject :missing-caller-context)

      (not (t/valid-workflow-id? world wf-id))
      (reject :invalid-workflow-id)

      (not (fraud-slash-workflow-eligible? world wf-id))
      (reject :workflow-not-slashable)

      (:error incident-result)
      (reject (:error incident-result))

      (or (nil? liable-resolvers) (empty? members))
      (reject :empty-liable-resolvers)

      (not= (count member-ids) (count (distinct member-ids)))
      (reject :duplicate-liable-resolver)

      (or (nil? amount) (not (number? amount)) (<= amount 0))
      (reject :invalid-slash-amount)

      :else
      (let [dispute-resolver (get-in world [:escrow-transfers wf-id :dispute-resolver])
            resolved-by (get-in world [:escrow-transfers wf-id :resolution :resolved-by])]
        (cond
          (some #(or (nil? %) (= "" %)) member-ids)
          (reject :invalid-resolver-addr)

          ;; A group fraud slash must remain tied to the dispute decision.
          (not (some #(or (= dispute-resolver %) (= resolved-by %)) member-ids))
          (reject :slash-resolver-mismatch)

          (some #(not (contains? (:resolver-stakes world {}) %)) member-ids)
          (reject :unregistered-liable-resolver)

          (some #(not (pos? (reg/get-stake world %))) member-ids)
          (reject :insufficient-resolver-stake)

          (active-manual-fraud-slash? world wf-id)
          (reject :slash-already-pending)

          :else
          (let [now (time-ctx/block-ts world)
                snap (t/get-snapshot world wf-id)
                appeal-window (or (:appeal-window-duration snap)
                                  (* (get-in world [:params :appeal-window-days] 7)
                                     (time-ctx/tick-seconds world)))
                slash-id (t/allocate-slash-id world)
                snapshot-members
                (->> member-ids
                     (map (fn [id]
                            (let [stake (reg/get-stake world id)]
                              {:id id
                               :slashable-stake stake
                               :available-slashable stake})))
                     (sort-by (comp canonical-resolver-order :id))
                     vec)
                ;; The group is incident-scoped, not a separately mutable
                ;; committee entity.  The slash ID is its canonical identity;
                ;; this hash commits to the immutable membership and liability
                ;; basis used by every later appeal and execution step.
                member-snapshot-hash
                (hc/hash-with-intent {:hash/intent :provenance}
                                                     {:liable-group/member-snapshot snapshot-members})
                entry (append-authorization-provenance
                       {:slash/id slash-id
                        :slash/workflow-id wf-id
                        :slash/kind :fraud-group
                        :slash/level 0
                        :workflow-id wf-id
                        :amount amount
                        :reason :fraud-group
                        :status :pending
                        :proposed-at now
                        :appeal-deadline (+ now appeal-window)
                        :liable-group/id slash-id
                        :liable-group/member-snapshot-hash member-snapshot-hash
                        :liable-group/ordering :canonical-resolver-id-ascending
                        :members snapshot-members
                        :appeals {}
                        :fraud-incident-ref (:incident-ref incident-result)
                        :liability-justification {:kind fraud-group-incident-kind
                                                  :incident-ref (:incident-ref incident-result)}
                        :authorization authorization
                        :provenance provenance
                        :policy-ordering :canonical-resolver-id-ascending}
                       "propose-fraud-group-slash"
                       provenance)
                world' (t/insert-slash world entry)]
            (assoc (t/ok world') :slash-id slash-id)))))))

(defn appeal-fraud-group-slash
  "Appeal one member's immutable allocation in a pending fraud-group slash."
  [world workflow-id caller slash-id & {:keys [authorization-provenance]}]
  (let [pending (get-in world [:pending-fraud-slashes slash-id])]
    (cond
      (not (t/valid-entity-id? slash-id)) (t/fail :invalid-slash-id)
      (nil? pending) (t/fail :slash-not-found)
      (not= (:slash/workflow-id pending) workflow-id) (t/fail :slash-workflow-mismatch)
      (not= :fraud-group (:slash/kind pending)) (t/fail :not-fraud-group-slash)
      (not= :pending (:status pending)) (t/fail :slash-not-pending)
      (> (time-ctx/block-ts world) (:appeal-deadline pending)) (t/fail :appeal-window-expired)
      (not (some #(= caller (:id %)) (:members pending))) (t/fail :not-liable-member)
      (contains? (:appeals pending) caller) (t/fail :member-appeal-already-pending)
      :else
      (let [allocation (sew-econ/calculate-sew-slash-allocation
                        {:slash-obligation (:amount pending)
                         :liable-parties (:members pending)
                         :basis :slashable-stake
                         :cap-field :available-slashable
                         :slash-policy {:type :fraud-group
                                        :ordering :canonical-resolver-id-ascending}})
            allocated-debit (or (some #(when (= caller (:id %)) (:paid %))
                                      (:allocations allocation))
                                0)
            token (:token (t/get-transfer world workflow-id))
            snap (t/get-snapshot world workflow-id)
            bond-amount (sew-econ/calculate-appeal-bond-amount
                         (:amount-after-fee (t/get-transfer world workflow-id)) snap)
            custody {:resolver caller :workflow-id workflow-id :amount bond-amount :token token}
            world' (cond-> (-> world
                                (assoc-in [:pending-fraud-slashes slash-id :appeals caller]
                                          {:status :appealed :allocated-debit allocated-debit
                                                                                     :appeal-bond-held bond-amount
                                                                                     :appealed-at (time-ctx/block-ts world)})
                                (assoc-in [:appeal-bond-custody slash-id caller] custody))
                     (pos? bond-amount)
                     (-> (acct/add-held token bond-amount
                                         {:action "appeal-fraud-group-slash"
                                          :reason :appeal-bond-posted
                                          :authorization-provenance authorization-provenance
                                          :extra {:held/action "appeal-fraud-group-slash"
                                                  :held/workflow-id workflow-id
                                                  :held/slash-id slash-id
                                                  :held/actor caller}})
                         (update-in [:total-bonds-posted token] (fnil + 0) bond-amount)
                         (update-in [:bond-posted-by-workflow workflow-id] (fnil + 0) bond-amount)))]
        (t/ok world')))))

(defn resolve-fraud-group-appeal
  "Governance resolves one member's fraud-group appeal.  An upheld appeal
   permanently stays only that member's original allocation."
  [world workflow-id caller member appeal-upheld? slash-id & {:keys [authorization-provenance]}]
  (let [pending (get-in world [:pending-fraud-slashes slash-id])
        appeal (get-in pending [:appeals member])]
    (cond
      (not (t/valid-entity-id? slash-id)) (t/fail :invalid-slash-id)
      (nil? pending) (t/fail :slash-not-found)
      (not= (:slash/workflow-id pending) workflow-id) (t/fail :slash-workflow-mismatch)
      (not= :fraud-group (:slash/kind pending)) (t/fail :not-fraud-group-slash)
      (nil? authorization-provenance) (t/fail :missing-authorization-provenance)
      (or (nil? caller) (= "" caller)) (t/fail :missing-caller-context)
      (not= :appealed (:status appeal)) (t/fail :no-active-member-appeal)
      :else
      (let [{:keys [amount token]} (get-in world [:appeal-bond-custody slash-id member])
            amount (or amount 0)
            world-base (update-in world [:appeal-bond-custody slash-id] dissoc member)
            world' (if appeal-upheld?
                     (cond-> world-base
                       (pos? amount) (-> (acct/sub-held token amount
                                                           {:action "resolve-fraud-group-appeal"
                                                            :reason :appeal-bond-returned
                                                            :authorization-provenance authorization-provenance
                                                            :extra {:held/action "resolve-fraud-group-appeal"
                                                                    :held/workflow-id workflow-id
                                                                    :held/slash-id slash-id
                                                                    :held/actor member}})
                                         (acct/record-claimable-v2 workflow-id :bond/refund member amount))
                        :always (assoc-in [:pending-fraud-slashes slash-id :appeals member :status] :upheld)
                        :always (assoc-in [:pending-fraud-slashes slash-id :appeals member :appeal-bond-held] 0))
                       (let [rejected-base (-> world-base
                                                              (assoc-in [:pending-fraud-slashes slash-id :appeals member :status] :rejected)
                                                              (assoc-in [:pending-fraud-slashes slash-id :appeals member :appeal-bond-held] 0))
                                   after-sub-held (if (pos? amount)
                                                    (acct/sub-held rejected-base token amount
                                                                   {:action "resolve-fraud-group-appeal"
                                                                    :reason :appeal-bond-forfeited
                                                                    :authorization-provenance authorization-provenance
                                                                    :extra {:held/action "resolve-fraud-group-appeal"
                                                                            :held/workflow-id workflow-id
                                                                            :held/slash-id slash-id
                                                                            :held/actor member}})
                                                    rejected-base)
                                   after-distrib (if (pos? amount)
                                                   (let [current (get-in after-sub-held [:appeal-bond-distributions-by-token token] 0)]
                                                     (assoc-in after-sub-held [:appeal-bond-distributions-by-token token] (+ current amount)))
                                                   after-sub-held)
                                    result (if (pos? amount)
                                              (update after-distrib :appeal-bonds-forfeited-insurance (fnil + 0) amount)
                                              after-distrib)]
                                result))]
         (t/ok world')))))

(defn execute-fraud-group-slash
  "Execute a mature `:fraud-group` slash from its proposal-time member snapshot.
   All allocations are calculated before any stake mutation; reductions are then
   applied in canonical member order and recorded with the executed slash."
  ([world workflow-id slash-id]
   (execute-fraud-group-slash world workflow-id slash-id nil))
  ([world workflow-id slash-id execution-provenance]
   (let [pending (get-in world [:pending-fraud-slashes slash-id])]
     (cond
       (not (t/valid-entity-id? slash-id)) (t/fail :invalid-slash-id)
       (nil? pending) (t/fail :slash-not-found)
       (not= (:slash/workflow-id pending) workflow-id) (t/fail :slash-workflow-mismatch)
       (not= :fraud-group (:slash/kind pending)) (t/fail :not-fraud-group-slash)
       (not= :pending (:status pending))
       (t/fail (if (= :executed (:status pending)) :already-executed :slash-not-pending))
       (<= (time-ctx/block-ts world) (:appeal-deadline pending)) (t/fail :timelock-not-expired)
       :else
       (let [members (:members pending)
             allocation-input {:slash-obligation (:amount pending)
                               :fraud-incident/ref (:fraud-incident-ref pending)
                               :liable-parties members
                               :basis :slashable-stake
                               :cap-field :available-slashable
                               :slash-policy {:type :fraud-group
                                              :ordering :canonical-resolver-id-ascending}}
             allocation (sew-econ/calculate-sew-slash-allocation allocation-input)
             ;; Preflight every executable row before mutating any stake.  The
             ;; epoch basis is reconstructed as live stake plus debits already
             ;; recorded in this epoch, matching slash-epoch-cap-respected?.
             ;; An upheld appeal stays its immutable row and consumes neither
             ;; stake nor epoch capacity.
             epoch-cap-bps (get-in world [:params :slash-epoch-cap-bps] 2000)
              preflight-violations
              (vec
               (for [{:keys [id paid]} (:allocations allocation)
                     :let [appeal (get-in pending [:appeals id])
                           stayed? (= :upheld (:status appeal))
                           current-stake (reg/get-stake world id)
                           epoch-slashed (get-in world [:resolver-epoch-slashed id :amount] 0)
                           projected (+ epoch-slashed (if stayed? 0 paid))
                           epoch-basis (+ current-stake epoch-slashed)
                           active-dispute? (resolver-has-other-active-disputes? world id workflow-id)]
                     :when (and (not stayed?)
                                (or active-dispute?
                                    (< current-stake paid)
                                    (> (* projected 10000)
                                       (* epoch-basis epoch-cap-bps))))]
                 {:member id
                  :planned-debit paid
                  :current-stake current-stake
                  :epoch-slashed epoch-slashed
                  :projected-epoch-slashed projected
                  :epoch-basis epoch-basis
                  :epoch-cap-bps epoch-cap-bps
                  :reason (cond
                            active-dispute? :freeze-blocked-active-dispute
                            (< current-stake paid) :insufficient-current-stake
                            :else :slash-epoch-cap-exceeded)}))
             freeze-duration (* (get-in world [:params :freeze-duration-days] 3)
                                (time-ctx/tick-seconds world))
             freeze-until (+ (time-ctx/block-ts world) freeze-duration)
             ;; The allocation is fully determined from the immutable snapshot.
             ;; The reducer merely applies those payments in the same order.
             {:keys [world stake-evidence-hashes payments]}
             (reduce
                (fn [{:keys [world stake-evidence-hashes payments] :as acc}
                     {:keys [id paid] :as allocation-row}]
                  (let [appeal (get-in pending [:appeals id])]
                    (cond
                      (= :upheld (:status appeal))
                      ;; Do not reallocate a stayed debit: the proposal-time allocation is final.
                      (update acc :payments conj (assoc allocation-row :actual-paid 0
                                                        :execution-status :stayed
                                                        :appeal-status :upheld))

                       (pos? paid)
                       (let [r (reg/slash-resolver-stake world id paid nil 0 workflow-id)
                             w (-> (:world r)
                                   (assoc-in [:resolver-frozen-until id] freeze-until)
                                   (update-in [:resolver-epoch-slashed id :amount] (fnil + 0) paid)
                                   (update-unavailability id true))]
                         (cap/capture-event-evidence!
                          :resolver-frozen
                          {:freeze/before {:frozen-until (get-in world [:resolver-frozen-until id])}}
                          {:freeze/after  {:frozen-until freeze-until}}
                          {:freeze/resolver id
                           :freeze/duration freeze-duration
                           :freeze/trigger :execute-fraud-group-slash}
                          nil
                          {:world-before world
                           :world-after w})
                         (assoc acc :world w
                                :stake-evidence-hashes (conj stake-evidence-hashes (:stake-evidence-hash r))
                                :payments (conj payments (assoc allocation-row :actual-paid paid
                                                                :execution-status :paid
                                                                :appeal-status (:status appeal)))))

                      :else
                      (update acc :payments conj (assoc allocation-row :actual-paid 0
                                                   :execution-status :unpaid
                                                   :appeal-status (:status appeal))))))
                {:world world :stake-evidence-hashes [] :payments []}
                (if (seq preflight-violations) [] (:allocations allocation)))
             world' (-> world
                        (update-in [:pending-fraud-slashes slash-id]
                                   append-execution-provenance
                                   "execute-fraud-group-slash"
                                   execution-provenance)
                        (assoc-in [:pending-fraud-slashes slash-id :proposal-allocation]
                                  allocation)
                        (assoc-in [:pending-fraud-slashes slash-id :allocation]
                                  (assoc allocation :allocations payments))
                        (assoc-in [:pending-fraud-slashes slash-id :status] :executed)
                        (lc/cleanup-orphaned-slashes workflow-id))
             world-before-hash (hc/hash-with-intent {:hash/intent :world-structure} world)
             projection (sew-econ/build-sew-slash-projection-artifact
                         (assoc allocation-input :world-before-hash world-before-hash))]
          (if (seq preflight-violations)
            (assoc (t/fail :slash-epoch-cap-exceeded)
                   :detail {:slash-id slash-id
                            :workflow-id workflow-id
                            :violations preflight-violations})
            (attr/with-attribution {:subject/type :slash :subject/id slash-id
                                    :action/type :slash/execute
                                    :evidence/reason :fraud-group-slash-executed}
              (let [{:keys [evidence artifact]}
                    (slashing-ev/build-prorata-slash-evidence
                     {:world world'
                      :slash-id slash-id :workflow-id workflow-id :trigger :fraud-group-slash
                      :allocation-input allocation-input :projection-artifact projection
                      :allocation-result allocation
                      :execution-result (assoc allocation :allocations payments)
                      :transition-dependencies (vec (filter some? stake-evidence-hashes))
                      :world-before-hash world-before-hash
                      :attribution (attr/current-attribution)})]
                (cap/capture-event-evidence! evidence)
                (slashing-ev/write-allocation-result-artifact! artifact))
              (t/ok world'))))))))

(defn resolve-appeal
  "Governance (TIMELOCK) resolves a slashing appeal.
   Naming note: `appeal-upheld?` means the APPEAL is upheld (accepted),
   not that the slash is upheld.
   - appeal-upheld? = true  -> slash is reversed and cannot be executed.
   - appeal-upheld? = false -> appeal is rejected; slash executes immediately.
   Mirrors: ResolverSlashingModuleV1.resolveAppeal

   `authorization-provenance` is REQUIRED for all calls — it enables forensic
   tracing of who authorized the resolution.  Passing nil will fail with
   `:missing-authorization-provenance`.

   `slash-id` must be a canonical protocol entity ID belonging to
   `_workflow-id`."
  [world _workflow-id caller appeal-upheld? slash-id & {:keys [authorization-provenance]}]
  (let [ctx (attr/make-context {:workflow-id _workflow-id :slash-id slash-id})
        pending (get-in world [:pending-fraud-slashes slash-id])]
    (attr/log-annotated! :debug "Resolving appeal" ctx {:appeal-upheld? appeal-upheld?})
    (cond
      (not (t/valid-entity-id? slash-id))
      (t/fail :invalid-slash-id)

      (nil? pending)
      (t/fail :slash-not-found)

      (not= (:slash/workflow-id pending) _workflow-id)
      (t/fail :slash-workflow-mismatch)

      (nil? authorization-provenance)
      (t/fail :missing-authorization-provenance)

      (or (nil? caller) (= "" caller))
      (t/fail :missing-caller-context)

      (not= :appealed (:status pending))
      (t/fail (case (:status pending)
                :executed :cannot-reverse-executed-slash
                :reversed :no-active-appeal
                 :reversed-with-credit :no-active-appeal
                 :no-active-appeal))

         :else
        (let [bond-held   (get-in world [:pending-fraud-slashes slash-id :appeal-bond-held] 0)
              custody     (get-in world [:appeal-bond-custody slash-id])
              resolver    (or (:resolver custody) (:resolver pending))
              wf-id       (or (:workflow-id custody) (:workflow-id pending) _workflow-id)
               bond-token  (or (:token custody) (:token pending))
              world-base  (-> world
                              (assoc-in [:pending-fraud-slashes slash-id :appeal-bond-held] 0)
                              (update :appeal-bond-custody dissoc slash-id))
              ;; Determine actual slash-status for evidence, distinct from appeal outcome.
              ;; For rejected appeals the status in the world is set to :pending
              ;; (deferred execution via execute-fraud-slash), not (:status pending)
              ;; which would still read :appealed from the pre-resolution state.
              actual-slash-status
              (fn [outcome]
                (case outcome
                  :reversed :reversed
                  (:rejected :rejected-no-bond :executed)
                  :pending))
              emit-appeal-resolution!
              (fn [world' outcome]
                (let [slash-status (actual-slash-status outcome)]
                  (attr/with-attribution {:subject/type :slash
                                          :subject/id slash-id
                                          :action/type :slash/resolve-appeal
                                          :evidence/reason (keyword "slash-appeal" (name outcome))}
                    (cap/capture-event-evidence!
                     (keyword "slash-appeal" (name outcome))
                     {:appeal-resolution/before {:slash-status (:status pending)
                                                 :appeal-bond-held bond-held}}
                     {:appeal-resolution/after  {:appeal-status outcome
                                                 :slash-status slash-status
                                                 :appeal-bond-held 0}}
                     {:appeal-resolution/slash-id slash-id
                       :appeal-resolution/workflow-id wf-id
                       :appeal-resolution/resolver resolver
                       :appeal-resolution/outcome outcome
                       :appeal-resolution/slash-status slash-status
                       :appeal-resolution/bond-forfeited? (and (pos? bond-held)
                                                               (not= :reversed outcome))
                       :appeal-resolution/bond-amount bond-held
                       :appeal-resolution/bond-token bond-token
                       :appeal-resolution/authorization-provenance authorization-provenance}
                       nil
                      {:world-before world
                       :world-after world'}))
                  (t/ok world')))]
          (when-not bond-token
            (throw (ex-info "slash appeal bond lacks token provenance"
                            {:type :missing-bond-token
                             :slash-id slash-id
                             :workflow-id wf-id
                             :custody custody
                             :pending pending})))
          (cond
            appeal-upheld?
            (let [world' (cond-> world-base
                           (pos? bond-held)
                           (-> (acct/sub-held bond-token
                                              bond-held
                                              {:action "resolve-appeal"
                                               :reason :appeal-bond-returned
                                               :authorization-provenance authorization-provenance
                                               :extra {:held/action "resolve-appeal"
                                                        :held/workflow-id wf-id
                                                        :held/slash-id slash-id
                                                        :held/actor resolver
                                                        :held/appeal-outcome :upheld}})
                               (acct/record-claimable-v2 wf-id :bond/refund resolver bond-held))
                           :always
                           (update-in [:pending-fraud-slashes slash-id]
                                      append-authorization-provenance
                                      "resolve-appeal"
                                      authorization-provenance)
                           :always
                           (assoc-in [:pending-fraud-slashes slash-id :status] :reversed))]
              (emit-decision-evidence!
               {:decision-id (str "resolve-appeal-" slash-id)
                :step (time-ctx/block-ts world)
                :alternatives [:uphold-appeal :reject-appeal]
                :selected :uphold-appeal
                :reasoning (str "Governance " caller " upheld the appeal for slash " slash-id)
                :caller caller
                :workflow-id wf-id})
              (emit-appeal-resolution! world' :reversed))

            (pos? bond-held)
            (let [world' (-> world-base
                             (acct/sub-held bond-token
                                            bond-held
                                            {:action "resolve-appeal"
                                             :reason :appeal-bond-forfeited
                                             :authorization-provenance authorization-provenance
                                              :extra {:held/action "resolve-appeal"
                                                      :held/workflow-id wf-id
                                                      :held/slash-id slash-id
                                                      :held/actor resolver
                                                      :held/appeal-outcome :rejected}})
                             (update-in [:pending-fraud-slashes slash-id]
                                        append-authorization-provenance
                                        "resolve-appeal"
                                        authorization-provenance)
                             (assoc-in [:pending-fraud-slashes slash-id :status] :pending)
                             (update-in [:appeal-bond-distributions-by-token bond-token] (fnil + 0) bond-held)
                             (update :appeal-bonds-forfeited-insurance (fnil + 0) bond-held))]
              (emit-decision-evidence!
               {:decision-id (str "resolve-appeal-" slash-id)
                :step (time-ctx/block-ts world)
                :alternatives [:uphold-appeal :reject-appeal]
                :selected :reject-appeal
                :reasoning (str "Governance " caller " rejected the appeal for slash " slash-id
                                "; bond " bond-held " " bond-token " forfeited")
                :caller caller
                :workflow-id wf-id})
              (emit-appeal-resolution! world' :rejected))

            :else
            (let [world' (-> world-base
                             (update-in [:pending-fraud-slashes slash-id]
                                        append-authorization-provenance
                                        "resolve-appeal"
                                        authorization-provenance)
                             (assoc-in [:pending-fraud-slashes slash-id :status] :pending))]
              (emit-decision-evidence!
               {:decision-id (str "resolve-appeal-" slash-id)
                :step (time-ctx/block-ts world)
                :alternatives [:uphold-appeal :reject-appeal]
                :selected :reject-appeal
                :reasoning (str "Governance " caller " rejected the appeal for slash " slash-id "; no bond to forfeit")
                :caller caller
                :workflow-id wf-id})
               (emit-appeal-resolution! world' :rejected-no-bond)))))))


(defn appeal-slash
  "Resolver appeals a PENDING manual slash (Phase M).
   `slash-id` must be a canonical protocol entity ID belonging to `workflow-id`.
   Mirrors: ResolverSlashingModuleV1.appealSlash"
  ([world workflow-id caller] (appeal-slash world workflow-id caller workflow-id))
  ([world workflow-id caller slash-id & {:keys [authorization-provenance]}]
   (let [pending (get-in world [:pending-fraud-slashes slash-id])]
     (cond
       (not (t/valid-entity-id? slash-id))
       (t/fail :invalid-slash-id)

       (nil? pending)
       (t/fail :slash-not-found)

       (not= (:slash/workflow-id pending) workflow-id)
       (t/fail :slash-workflow-mismatch)

       (nil? (:status pending))
       (t/fail :invalid-slash-state)

       (not= :pending (:status pending))
       (t/fail :slash-not-pending)

       (> (time-ctx/block-ts world) (:appeal-deadline pending))
       (t/fail :appeal-window-expired)

       (not= caller (:resolver pending))
       (t/fail :not-resolver)

       :else
       (let [snap        (t/get-snapshot world workflow-id)
             et          (t/get-transfer world workflow-id)
             token       (:token et)
             bond-amount (sew-econ/calculate-appeal-bond-amount (:amount-after-fee et) snap)
             world'      (if (pos? bond-amount)
                           (cond-> (-> world
                                       (assoc-in [:pending-fraud-slashes slash-id :appeal-bond-held] bond-amount)
                                       (assoc-in [:appeal-bond-custody slash-id]
                                                 (cond-> {:resolver caller
                                                          :workflow-id workflow-id
                                                          :amount bond-amount
                                                          :token token}
                                                   authorization-provenance
                                                   (assoc :authorization/provenance authorization-provenance
                                                          :authorization/last-provenance authorization-provenance
                                                          :authorization/last-action "appeal-slash"
                                                          :authorization/history
                                                          [{:authorization/action "appeal-slash"
                                                            :authorization/provenance authorization-provenance}]))))
                             authorization-provenance
                             (acct/add-held token
                                            bond-amount
                                            {:action "appeal-slash"
                                             :authorization-provenance authorization-provenance
                                             :reason :appeal-bond-posted
                                             :extra {:held/action "appeal-slash"
                                                     :held/workflow-id workflow-id
                                                     :held/slash-id slash-id
                                                     :held/actor caller}})

                             (not authorization-provenance)
                             (acct/add-held token
                                            bond-amount
                                            {:action "appeal-slash"
                                             :reason :appeal-bond-posted
                                             :extra {:held/action "appeal-slash"
                                                     :held/workflow-id workflow-id
                                                     :held/slash-id slash-id
                                                     :held/actor caller}})

                             true
                             (update-in [:total-bonds-posted token] (fnil + 0) bond-amount)

                             true
                             (update-in [:bond-posted-by-workflow workflow-id] (fnil + 0) bond-amount))
                           world)
             world''     (-> world'
                             (assoc-in [:pending-fraud-slashes slash-id :status] :appealed)
                             (cond-> authorization-provenance
                               (update-in [:pending-fraud-slashes slash-id]
                                          append-authorization-provenance
                                          "appeal-slash"
                                          authorization-provenance)))]
         (attr/with-attribution {:subject/type :resolver
                                 :subject/id caller
                                 :action/type :slash/appeal
                                 :evidence/reason :fraud-slash-appealed}
           (cap/capture-event-evidence!
            :fraud-slash-appealed
            {:appeal/before {:slash-status (:status pending)
                             :slash-amount (:amount pending)}}
            {:appeal/after  {:slash-status :appealed
                             :appeal-bond-held bond-amount}}
            {:appeal/slash-id slash-id
             :appeal/workflow-id workflow-id
             :appeal/resolver caller
             :appeal/bond-amount bond-amount
             :appeal/bond-token token}
            nil
            {:world-before world
             :world-after world''}))
         (t/ok world''))))))

(defn compute-prorata-slash-allocation
  "Non-governance query action: compute pro-rata allocation for a slash obligation.

   Accepts {:slash-obligation N :liable-parties [...]} with optional
   :basis and :cap-field overrides.

   Pure read — no world mutation.
   Returns {:ok true :allocation <result-map>} with the full allocation
   including :allocations, :recovered-total, :unmet-total."
  [world {:keys [slash-obligation basis cap-field liable-parties]}]
  (let [allocation (sew-econ/calculate-sew-slash-allocation
                    (cond-> {:slash-obligation slash-obligation
                             :liable-parties liable-parties}
                      basis (assoc :basis basis)
                      cap-field (assoc :cap-field cap-field)))]
    {:ok true :world world :extra {:allocation allocation}}))

(defn unfreeze-resolver
  "Governance unfreezes resolver and idempotently clears unavailability mark."
  [world resolver]
  (let [was-frozen? (pos? (get-in world [:resolver-frozen-until resolver] 0))
        world' (-> world
                   (assoc-in [:resolver-frozen-until resolver] 0)
                   (update-unavailability resolver false))]
    (when was-frozen?
      (attr/with-attribution {:subject/type :resolver
                              :subject/id resolver
                              :action/type :resolver/unfreeze
                              :evidence/reason :resolver-unfrozen}
        (cap/capture-event-evidence!
         :resolver-unfrozen
         {:unfreeze/before {:frozen-until (get-in world [:resolver-frozen-until resolver])}}
         {:unfreeze/after  {:frozen-until 0}}
         {:unfreeze/resolver resolver}
         nil
         {:world-before world
          :world-after world'})))
    (t/ok world')))

;; ---------------------------------------------------------------------------
;; rotate-dispute-resolver
;;
;; Models a governance-triggered resolver rotation on an in-flight dispute.
;; Guards: workflow must exist + be in :disputed state; new-resolver non-nil.
;; Effects:
;;   - Updates et.dispute-resolver to new-resolver
;;   - Appends to world :resolver-rotations {workflow-id [{:from :to :at}]}
;; ---------------------------------------------------------------------------

(defn- finalize
  "Internal: transition escrow to terminal state, release accounting.
   direction — :released (to recipient) or :refunded (to sender).

   Optional opts:
   - authorization-provenance — forwarded to finalize-escrow-accounting for
     force-authorised escrow settlement.

   NOTE: cleanup-orphaned-slashes (now in lifecycle.clj) is deliberately NOT called here.
   It runs in execute-pending-settlement (which calls finalize then cleanup).
   Calling it in finalize would remove pending Track 2 reversal slashes
   that were just created by handle-reversal-slashing in the same
   execute-resolution call (final-round path)."
  [world workflow-id direction & {:keys [authorization-provenance]}]
  (let [resolver (:dispute-resolver (t/get-transfer world workflow-id))
        result   (lc/finalize-escrow-accounting world workflow-id direction
                                                :authorization-provenance authorization-provenance)]
    (if (:ok result)
      (t/ok (t/decrement-resolver-capacity (:world result) resolver))
      result)))

;; ── Monadic Transitions ──────────────────────────────────────────────────────

(defn execute-resolution-m
  "Monadic version of execute-resolution."
  [workflow-id caller is-release resolution-hash resolution-module-fn]
  (am/update-with-result execute-resolution workflow-id caller is-release resolution-hash resolution-module-fn))

(defn execute-pending-settlement-m
  "Monadic version of execute-pending-settlement."
  [workflow-id]
  (am/update-with-result execute-pending-settlement workflow-id))

(defn escalate-dispute-m
  "Monadic version of escalate-dispute."
  [workflow-id caller escalation-fn]
  (am/update-with-result escalate-dispute workflow-id caller escalation-fn))

(comment
  ;; Debug: add a temp version that prints
  )
