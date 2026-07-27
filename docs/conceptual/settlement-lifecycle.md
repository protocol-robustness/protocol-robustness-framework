# Settlement Lifecycle

*Both PRF and Sew perspectives on how a payment moves from creation to finality.*

## 1. Overview

The settlement lifecycle describes the end-to-end path an escrow follows from
creation through to an irreversible terminal state.  Two layers interact:

- **PRF (Protocol Robustness Framework)** — the protocol-agnostic replay engine
  that drives simulation, checks invariants, and records evidence for any
  lifecycle stage.
- **Sew** — the concrete escrow + dispute-resolution protocol whose lifecycle
  the PRF models and tests.

| Layer | Role |
|-------|------|
| PRF | Simulation adapter, invariant checking, trace minimization, evidence capture |
| Sew | State machine, pending-settlement mechanic, appeal windows, keeper dispatch |

---

## 2. PRF Lifecycle (Framework Level)

The PRF treats settlement as a generic sequence of events dispatched through a
`SimulationAdapter`:

```
Scenario Definition
  │
  ▼
World Initialization (init-world)
  │
  ▼
Event Loop: for each event in scenario
  │
  ├─► dispatch-action  →  world'  (protocol-specific: create, dispute, resolve, settle)
  ├─► check-invariants-single    on world'
  ├─► check-invariants-transition on (world, world')
  ├─► capture-event-evidence!    (decision evidence, action evidence)
  └─► record trace snapshot
        │
        ▼
Minimization (on failure) → 1-minimal failing trace
  │
  ▼
Completion (completion.json)
```

### 2.1 PRF Interfaces for Settlement

| Interface | Purpose | Settlement-relevant methods |
|-----------|---------|---------------------------|
| `SimulationAdapter` | Mandatory: defines how events mutate world state | `dispatch-action`, `check-invariants-*`, `available-actions` |
| `TemporalDeadlines` | Optional: deadline enforcement for time-gated actions | `deadline-for` with kinds `:settlement`, `:evidence-submission`, `:appeal` |
| `EconomicModel` | Optional: adversarial metrics and payoff analysis | `adversarial-event?`, `advisory` |
| `AnalysisModule` | Optional: formal projections and equilibrium analysis | `project-state`, `equilibrium-projection` |

### 2.2 PRF Trace Structure

Each step in a settlement lifecycle produces:

- **World snapshot**: full state after event application
- **Event evidence**: captured via `capture-event-evidence!` (action metadata,
  world-before/world-after)
- **Decision evidence**: resolver reasoning, alternatives, selected outcome
- **Invariant results**: pass/fail for all registered invariants

---

## 3. Sew Settlement Lifecycle (Protocol Level)

### 3.1 Escrow State Machine

Six states with the following allowed transitions:

```
                         ┌──────────────────────────────┐
                         │                              │
                         ▼                              │
   none ──► pending ──► disputed ──► released           │
                   │        │          │                 │
                   │        │          ├── refunded      │
                   │        │          │                 │
                   │        │          └── resolved      │
                   │        │                            │
                   │        └──► released                │
                   │             refunded                │
                   │             resolved                │
                   │                                     │
                   └──► released                         │
                        refunded                         │
                        resolved ────────────────────────┘
```

| State | Meaning | Terminal? |
|-------|---------|-----------|
| `:none` | Pre-creation | No |
| `:pending` | Escrow created, funds held | No |
| `:disputed` | Dispute raised by a participant | No |
| `:released` | Funds released to recipient | Yes |
| `:refunded` | Funds returned to sender | Yes |
| `:resolved` | Mutual split settlement | Yes |

### 3.2 Full Settlement Lifecycle Phases

#### Phase 0 — Escrow Creation

```
Action: create_escrow
  From:  none
  To:    pending
  Effects:
    - workflow-id allocated
    - funds debited from sender (added to :total-held)
    - escrow settings frozen (ModuleSnapshot)
    - auto-release-time / auto-cancel-time set
    - dispute-resolver assigned
```

#### Phase 1 — Dispute

```
Action: raise_dispute
  From:  pending
  To:    disputed
  Effects:
    - dispute-timestamp recorded (block time)
    - dispute-level set to 0
    - both participants may submit evidence
    - escrow enters live state
```

#### Phase 2 — Resolution

```
Action: execute_resolution
  From:  disputed
  To:    disputed (with pending settlement)
         OR released / refunded (immediate finalization)
  Effects:
    - resolver submits outcome (is-release or refund)
    - decision evidence captured (reasoning, alternatives)
    - two paths:

    Path A — Immediate (appeal-window = 0 or final-round):
      └─► finalize → state transitions to :released or :refunded

    Path B — Deferred (appeal-window > 0, not final):
      └─► PendingSettlement created:
            { :exists true
              :is-release <bool>
              :appeal-deadline <now + window-duration>
              :resolution-hash <bytes32> }
      └─► state remains :disputed
```

#### Phase 3 — Appeal Window (Debate Period)

```
While block-time < appeal-deadline:

  Action: escalate_dispute
    Guards:  pending exists, within window, participant caller, not final round
    Effects:
      - archives active pending → :superseded-pending-settlements
      - clears :pending-settlements[workflow-id]
      - increments dispute-level
      - rotates resolver to next level
      - new resolver may submit a fresh resolution

  Action: challenge_resolution
    Guards:  pending exists, within window, any caller, not final round
    Effects:
      - same as escalate_dispute, but open to third parties
      - caller posts challenge bond (bond scales 1.1x per escalation count)

  Action: execute_pending_settlement
    Rejected with :appeal-window-not-expired
    (block-time < appeal-deadline)

  Action: rotate_dispute_resolver
    Rejected with :resolution-already-pending

  Action: submit_evidence
    Allowed (escrow remains :disputed)
```

#### Phase 4 — Settlement Execution

```
Action: execute_pending_settlement
  When:  block-time >= appeal-deadline
  From:  disputed
  To:    released (if pending.is-release)
         OR refunded (if not)
  Guards:
    1. workflow-id exists
    2. pending settlement exists (active or eligible superseded)
    3. state == :disputed
    4. block-time >= appeal-deadline
  Effects:
    - yield accrued and withdrawn
    - principal released/refunded (deducted from :total-held)
    - claimable entries recorded
    - pending-settlement dissociated
    - state transition to :released or :refunded
    - orphaned slashes cleaned up
```

#### Phase 5 — Terminal (Post-Settlement)

```
State: :released or :refunded
  - No further actions allowed
  - execute_pending_settlement    → :no-pending-settlement
  - raise_dispute                 → :transfer-not-pending
  - execute_resolution            → :transfer-not-in-dispute
  - escalate_dispute              → :transfer-not-in-dispute
```

### 3.3 Pending Settlement Sub-states

Within the `:disputed` state, the pending-settlement mechanic adds fine-grained
temporal states:

```
:disputed
  ├── No pending settlement (awaiting first resolution)
  │
  ├── Active pending settlement (appeal window open)
  │     ├── block-time < deadline: can escalate/challenge; cannot execute
  │     └── block-time >= deadline: can execute; cannot escalate/challenge
  │
  ├── Superseded pending (archived on escalation or challenge)
  │     └── Eligible for execution if:
  │           - no active pending exists
  │           - block-time >= superseded appeal-deadline
  │           (fallback path when escalation produces no replacement)
  │
  └── Multiple superseded pendings (capped at 5 per workflow)
```

### 3.4 Keeper Timed Action Dispatch

The automated keeper function (`automate-timed-actions`) dispatches with
strict priority:

| Priority | Condition | Action | Escrow State |
|----------|-----------|--------|--------------|
| 1 | `pending-settlement-executable?` | `execute-pending-settlement` | :disputed → terminal |
| 2 | `auto-cancel-due-on-disputed?` | `auto-cancel-disputed-on-auto-time` | :disputed → :refunded (+ slash) |
| 3 | `dispute-timeout-exceeded?` | `auto-cancel-disputed-escrow` | :disputed → :refunded (+ slash resolver) |
| 4 | `auto-release-due?` | `finalize-escrow-accounting` (release) | :pending → :released |
| 5 | `auto-cancel-due?` | `finalize-escrow-accounting` (refund) | :pending → :refunded |
| 6 | (none) | `:none` | unchanged |

Priority 2 is a Sew extension (ported to Solidity in Session 10 as
`ACTION_AUTO_CANCEL_DISPUTED` via `SettlementOps.computeTimedActions`) — griefing
protection that prevents a frivolous dispute from blocking an auto-cancel deadline.

### 3.5 Force-Authorisation: Alternative Authorization Path

Force-authorisation is a governance-authorized override that bypasses the
normal resolver path.  It grants a scoped, single-use, expiring authorization
to settle a dispute when the usual resolver mechanism is unavailable (frozen
resolver, circuit breaker active, resolver overcapacity, governance
correction).

The system spans four layers:

| Layer | File | Role |
|-------|------|------|
| Protocol action dispatch | `Sew.clj:95-876` | Grant/revoke/execute action handlers, policy definition, scope-hash verification |
| Resolution integration | `resolution.clj:652-794` | `apply-resolution-transition` provenance plumbing, `finalize` forwarding |
| Escrow lifecycle | `lifecycle.clj:165-296` | `finalize` held-reason selection (`:force-authorised-release`/`:force-authorised-refund`) |
| Custody accounting | `accounting.clj:104-527` | `ensure-force-authorisation-usable!`, `mark-force-authorisation-consumed`, `adjust-held` enforcement |
| Invariants | `invariants.clj:344-408` | `force-authorisations-lifecycle-consistent?` |
| Evidence contracts | `evidence/force_authorisation.clj` | Protocol-independent scope schema, envelope validation, temporal ordering |
| Assurance | `assurance/force_authorisation.clj` | Protocol-independent authorization validation, normalization, lifecycle consistency |
| Threat model | `docs/research/FORCE_AUTHORISATION_AND_CUSTODY_THREAT_MODEL.md` | Assets, actors, attacker model, safety properties, non-claims |
| Evidence layer | `scripts/forensic/validate.py` | Forensic validator for force-authorisation evidence in sealed bundles |

#### 3.5.1 Forced-Authorisation Policy

The policy (`Sew.clj:95-131`) is a static `def ^:private` map that allowlists
four actions with their permissible reasons, authorization class, authorization
path, allowed checks, allowed sources, and capacity-context requirement:

```clojure
(def ^:private forced-authorisation-policy
  {   "execute-resolution"
   {:reasons #{:missing-resolver :resolver-overcapacity :resolver-frozen
               :circuit-breaker-active :resolver-unavailable :manual-override}
    :authorization/class :interactive-override
    :authorization/path :exceptional
    :checks #{:force-authorised :force-authorisation-record}
    :sources #{:repl-interactive-session :force-authorisation-record}
    :capacity-context-required? true}

   "activate-resolver-overflow"
   {:reasons #{:resolver-overcapacity}
    :authorization/class :capacity-failover
    :authorization/path :capacity-failover
    :checks #{:with-governance-actor}
    :sources #{:replay-context/agent-index}
    :capacity-context-required? true}

   "appeal-slash"
   {:reasons #{:appeal-bond-custody}
    :authorization/class :governance-intervention
    :authorization/path :exceptional
    :checks #{:with-governance-actor}
    :sources #{:replay-context/agent-index}
    :capacity-context-required? true}

   "force-reversal-slash"
   {:reasons #{:governance-force-reversal-slash}
    :authorization/class :governance-intervention
    :authorization/path :exceptional
    :checks #{:with-governance-actor}
    :sources #{:replay-context/agent-index}
    :capacity-context-required? true}})
```

| Action | Class | Path | Reasons | Entry via |
|--------|-------|------|---------|-----------|
| `execute-resolution` | `:interactive-override` | `:exceptional` | 6 reasons | Grant+Execute action sequence |
| `activate-resolver-overflow` | `:capacity-failover` | `:capacity-failover` | 1 reason | Direct governance action |
| `appeal-slash` | `:governance-intervention` | `:exceptional` | 1 reason | Direct governance action |
| `force-reversal-slash` | `:governance-intervention` | `:exceptional` | 1 reason | Direct governance action |

The policy is enforced at grant time and also at the `build-force-authorisation-provenance`
function (`Sew.clj:133-182`), which performs five validations:

1. Action must be in `forced-authorisation-policy` keys (throws `:invalid-force-authorisation`)
2. Reason must be in `(:reasons policy)` (throws with `:allowed-reasons`)
3. Check must be in `(:checks policy)` (throws with `:allowed-checks`)
4. Source must be in `(:sources policy)` (throws with `:allowed-sources`)
5. If `:capacity-context-required?`, capacity-context must be non-nil

This double enforcement (both at the policy map level and the
`build-force-authorisation-provenance` function) ensures that unknown actions
cannot silently acquire forced-authorisation provenance.

#### 3.5.2 Grant Action Handler (`grant-force-authorisation`)

Defined at `Sew.clj:586-699`.  Gated as a governance action via
`run-governance-action`:

```clojure
(defmethod apply-action "grant-force-authorisation"
  [context world event]
  (run-governance-action context world event
    (fn [addr _agent _provenance]
      (let [pp               (:params event)
            fa-policy        (:force-authorisation-policy context)
            now              (time-ctx/block-ts world)
            workflow-id      (:workflow-id pp)
            escrow           (t/get-transfer world workflow-id)
            reason           (:reason pp)
            allowed          (:allowed-reasons fa-policy)
            starts-at        (or (:starts-at pp) now)
            duration         (:duration pp)
            expires-at-param (:expires-at pp)
            def-dur          (:default-duration fa-policy)
            expires-at       (or expires-at-param
                                 (when (and (number? starts-at) (number? duration))
                                   (+ starts-at duration))
                                 (when (and (number? starts-at) (number? def-dur))
                                   (+ starts-at def-dur))
                                 nil)
            max-dur          (:max-duration fa-policy)
            allowed-action   (:allowed-action pp "execute-resolution")
            is-release       (get pp :is-release true)]
        ;; ── 14 pre-condition gates ──
        (cond
          (nil? escrow) (t/fail :force-authorisation-workflow-not-found)
          (not= :disputed (:escrow-state escrow)) (t/fail :force-authorisation-workflow-not-disputed)
          (not (keyword? reason)) (t/fail :force-authorisation-invalid-reason)
          (and allowed (not (contains? allowed reason))) (t/fail :force-authorisation-reason-not-allowed)
          (not= "execute-resolution" allowed-action) (t/fail :force-authorisation-action-not-allowed)
          (not (boolean? is-release)) (t/fail :force-authorisation-invalid-settlement-direction)
          (not (number? starts-at)) (t/fail :force-authorisation-invalid-start-time)
          (and duration (or (not (number? duration)) (neg? duration)))
          (t/fail :force-authorisation-invalid-duration)
          (and expires-at-param (not (number? expires-at-param)))
          (t/fail :force-authorisation-invalid-expiry)
          (and expires-at (<= expires-at starts-at))
          (t/fail :force-authorisation-invalid-time-window)
          (and expires-at-param duration) (t/fail :force-authorisation-conflicting-timing)
          (and expires-at max-dur (> (- expires-at starts-at) max-dur))
          (t/fail :force-authorisation-duration-exceeds-max)
          :else
          ;; ── Build scope, hash, record, persist ──
          (let [auth-id         (str "fa-" (get world :next-force-authorisation-id 0))
                recipient       (if is-release (:to escrow) (:from escrow))
                reason-for-scope (if is-release :force-authorised-release :force-authorised-refund)
                scope           {:authorization/id auth-id
                                 :authorization/type :force-authorisation
                                 :held/direction :out
                                 :token (:token escrow)
                                 :amount (:amount-after-fee escrow)
                                 :held/account :escrow-principal
                                 :owner/address recipient
                                 :held/reason reason-for-scope
                                 :held/workflow-id workflow-id}
                scope-hash      (hash/domain-hash acct/force-authorisation-scope-domain scope)
                grant-prov      (merge (governance-authorization-provenance context event addr)
                                       {:authorization/type :force-authorisation
                                        :authorization/id auth-id
                                        :authorization/source :governance
                                        :authorization/check :with-governance-actor
                                        :authorization/scope-hash scope-hash})
                record          {:authorization/id auth-id
                                 :authorization/version "force-authorisation.v2"
                                 :authorization/type :force-authorisation
                                 :authorization/source :governance
                                 :authorization/status :active
                                 :workflow-id workflow-id
                                 :allowed-action allowed-action
                                 :authorization/scope scope
                                 :authorization/scope-hash scope-hash
                                 :nonce auth-id
                                 :starts-at starts-at
                                 :expires-at expires-at
                                 :created-at now
                                 :created-by addr
                                 :reason reason
                                 :consumed? false
                                 :authorization/provenance grant-prov
                                 :authorization/last-provenance grant-prov
                                 :authorization/last-action "grant-force-authorisation"
                                 :authorization/history [...]}
                world' (-> world
                           (assoc-in [:force-authorisations auth-id] record)
                           (update :next-force-authorisation-id inc))]
            ;; ── Emit evidence ──
            ...))))
```

**14 pre-condition gates** in evaluation order:

| # | Guard | Error Code | Condition |
|---|-------|-----------|-----------|
| 1 | `workflow-id` exists | `:force-authorisation-workflow-not-found` | `(nil? escrow)` |
| 2 | Escrow is `:disputed` | `:force-authorisation-workflow-not-disputed` | `(:escrow-state escrow)` != `:disputed` |
| 3 | Reason is a keyword | `:force-authorisation-invalid-reason` | `(not (keyword? reason))` |
| 4 | Reason is in policy allowlist | `:force-authorisation-reason-not-allowed` | `(not (contains? allowed reason))` |
| 5 | Allowed action is `"execute-resolution"` | `:force-authorisation-action-not-allowed` | `(not= "execute-resolution" allowed-action)` |
| 6 | Settlement direction is boolean | `:force-authorisation-invalid-settlement-direction` | `(not (boolean? is-release))` |
| 7 | Start time is numeric | `:force-authorisation-invalid-start-time` | `(not (number? starts-at))` |
| 8 | Duration is valid | `:force-authorisation-invalid-duration` | duration present but not numeric or negative |
| 9 | Expiry is numeric if present | `:force-authorisation-invalid-expiry` | `expires-at-param` present but not numeric |
| 10 | Window is valid | `:force-authorisation-invalid-time-window` | `expires-at <= starts-at` |
| 11 | No conflicting timing | `:force-authorisation-conflicting-timing` | Both `expires-at-param` and `duration` provided |
| 12 | Duration within max | `:force-authorisation-duration-exceeds-max` | `expires-at - starts-at > max-dur` |
| 13 | (Via policy) Scope direction computed | (n/a — computed internally) | recipient = `:to` if release, `:from` if refund |
| 14 | (Via policy) Action in policy | (n/a — `allowed-action` hardcoded) | Only `"execute-resolution"` accepted |

**Expiry resolution chain (line 601-606):**
1. Use explicit `:expires-at` parameter if provided
2. Else compute as `starts-at + duration` if both provided
3. Else compute as `starts-at + default-duration` from context policy
4. Else `nil` (no expiry — permanent until consumed or revoked)

**Scope-map construction (line 631-639):**
```clojure
{:authorization/id auth-id
 :authorization/type :force-authorisation
 :held/direction :out
 :token (:token escrow)
 :amount (:amount-after-fee escrow)
 :held/account :escrow-principal
 :owner/address recipient        ;; :to if release, :from if refund
 :held/reason reason-for-scope   ;; :force-authorised-release or :force-authorised-refund
 :held/workflow-id workflow-id}
```

**Evidence emitted** (`Sew.clj:672-697`):
```clojure
;; Captures before/after world state for forensic audit:
:force-authorisation-granted
{:force-auth/before {:next-force-authorisation-id <before>}
 :force-auth/after  {:next-force-authorisation-id <after>
                     :created-auth-id auth-id
                     :status :active
                     :starts-at starts-at
                     :expires-at expires-at
                     :scope-hash scope-hash}}
;; Plus full event-level context:
{:force-auth/auth-id auth-id
 :force-auth/workflow-id workflow-id
 :force-auth/allowed-action allowed-action
 :force-auth/reason reason
 :force-auth/created-by addr
 :force-auth/starts-at starts-at
 :force-auth/expires-at expires-at
 :force-auth/scope scope
 :force-auth/scope-hash scope-hash
 :force-auth/nonce auth-id}
```

#### 3.5.3 Revoke Action Handler (`revoke-force-authorisation`)

Defined at `Sew.clj:705-744`.  Also governance-gated:

```clojure
(defmethod apply-action "revoke-force-authorisation"
  [context world event]
  (run-governance-action context world event
    (fn [addr _agent _provenance]
      (let [pp      (:params event)
            auth-id (:authorization-id pp)
            record  (get-in world [:force-authorisations auth-id])]
        (if (nil? record)
          (t/fail :force-authorisation-not-found)
          (let [now (time-ctx/block-ts world)
                revoke-prov (merge (governance-authorization-provenance context event addr)
                                   {:authorization/type :force-authorisation
                                    :authorization/id auth-id
                                    :authorization/source :governance
                                    :authorization/check :with-governance-actor
                                    :authorization/action "revoke-force-authorisation"})
                world' (-> world
                           (assoc-in [:force-authorisations auth-id :authorization/status] :revoked)
                           (assoc-in [:force-authorisations auth-id :authorization/last-provenance] revoke-prov)
                           (assoc-in [:force-authorisations auth-id :authorization/last-action] "revoke-force-authorisation")
                           (update-in [:force-authorisations auth-id :authorization/history]
                                      (fnil conj [])
                                      {:authorization/action "revoke-force-authorisation"
                                       :authorization/provenance revoke-prov}))]
            ;; Emit :force-authorisation-revoked evidence
            ...)))))
```

**Backward-compatibility alias** (`Sew.clj:746-748`):
```clojure
(defmethod apply-action "revoke-force-authorization"
  ;; US-spelling alias
  ((get-method apply-action "revoke-force-authorisation") context world event))
```

#### 3.5.4 Execute Action Handler (`execute-force-authorised-action`)

Defined at `Sew.clj:750-876`.  Gated as a resolved-actor action (not governance):

```clojure
(defmethod apply-action "execute-force-authorised-action"
  [{:keys [agent-index]} world event]
  (actx/with-resolved-actor
    agent-index event
    (fn [addr]
      (let [pp              (:params event)
            workflow-id     (:workflow-id pp)
            auth-id         (:authorization-id pp)
            is-release      (get pp :is-release true)
            resolution-hash (get pp :resolution-hash "0xf-authorized")
            record          (get-in world [:force-authorisations auth-id])
            now             (time-ctx/block-ts world)]
        ;; ── 8 pre-condition gates ──
        (cond
          (nil? record)                              (t/fail :force-authorisation-not-found)
          (not= :active (:authorization/status record)) (t/fail :force-authorisation-not-active)
          (:consumed? record)                        (t/fail :force-authorisation-already-consumed)
          (not= workflow-id (:workflow-id record))    (t/fail :force-authorisation-workflow-mismatch)
          (not= "execute-resolution" (:allowed-action record)) (t/fail :force-authorisation-action-mismatch)
          (< now (:starts-at record))                 (t/fail :force-authorisation-not-yet-started)
          (and (:expires-at record) (>= now (:expires-at record))) (t/fail :force-authorisation-expired)
          (get-in world [:force-authorisations/consumed auth-id]) (t/fail :force-authorisation-already-consumed)
          :else
          ;; ── Rebuild scope from current world state and verify hash ──
          (let [et        (t/get-transfer world workflow-id)
                token     (:token et)
                amount    (:amount-after-fee et)
                recipient (if is-release (:to et) (:from et))
                direction :out
                fa-reason (if is-release :force-authorised-release :force-authorised-refund)
                scope-map {:authorization/id auth-id
                           :authorization/type :force-authorisation
                           :held/direction direction :token token :amount amount
                           :held/account :escrow-principal
                           :owner/address recipient
                           :held/reason fa-reason
                           :held/workflow-id workflow-id}
                scope-hash (hash/domain-hash acct/force-authorisation-scope-domain scope-map)]
            (if (or (not= scope-map (:authorization/scope record))
                    (not= scope-hash (:authorization/scope-hash record)))
              (t/fail :force-authorisation-grant-scope-mismatch)
              ;; ── Build execution provenance ──
              (let [execution-prov
                    {:authorization/schema-version "force-authorisation.v2"
                     :authorization/type :force-authorisation
                     :authorization/id auth-id
                     :authorization/scope-hash scope-hash
                     :authorization/source :governance
                     :authorization/check :force-authorisation-record
                     :authorization/workflow-id workflow-id
                     :authorization/allowed-action "execute-resolution"
                     :authorization/executed-by addr
                     :authorization/executed-at now
                     :authorization/governance-provenance (:authorization/provenance record)}
                    result (res/apply-resolution-transition
                            world workflow-id addr is-release resolution-hash nil
                            :resolution-source :force-authorised
                            :authorization-provenance execution-prov)]
                (if (:ok result)
                  (let [world' (-> (:world result)
                                   ;; Record execution but DO NOT consume yet.
                                   ;; Consumption happens at sub-held in finalize.
                                   (assoc-in [:force-authorisations auth-id :executed-by] addr)
                                   (assoc-in [:force-authorisations auth-id :executed-at] now)
                                   (assoc-in [:force-authorisations auth-id :execution/is-release] is-release)
                                   (assoc-in [:force-authorisations auth-id :execution/provenance] execution-prov)
                                   ...)]
                    ;; Emit :force-authorisation-executed evidence
                    ...)
                  result)))))))))
```

**8 pre-condition gates in evaluation order:**

| # | Guard | Error Code | Condition |
|---|-------|-----------|-----------|
| 1 | Grant record exists | `:force-authorisation-not-found` | `(nil? record)` |
| 2 | Grant status is `:active` | `:force-authorisation-not-active` | `(:authorization/status record)` != `:active` |
| 3 | Grant not consumed (flag) | `:force-authorisation-already-consumed` | `(:consumed? record)` is true |
| 4 | Workflow-id matches grant | `:force-authorisation-workflow-mismatch` | `(:workflow-id record)` != execution workflow-id |
| 5 | Action matches grant | `:force-authorisation-action-mismatch` | `(:allowed-action record)` != `"execute-resolution"` |
| 6 | Within start window | `:force-authorisation-not-yet-started` | `now < (:starts-at record)` |
| 7 | Not expired | `:force-authorisation-expired` | `now >= (:expires-at record)` |
| 8 | Grant not consumed (registry) | `:force-authorisation-already-consumed` | `world[:force-authorisations/consumed][auth-id]` exists |

**Gate 8** is an additional check beyond the `:consumed?` flag on the record:
it checks the accounting-level consumption registry.  This provides defence
in depth: even if the grant record's `:consumed?` flag were not set, the
accounting layer's registry would still block re-execution.

**Scope-hash verification** (line 805-806):
```clojure
(or (not= scope-map (:authorization/scope record))
    (not= scope-hash (:authorization/scope-hash record)))
```
Both the full scope map and the hash are compared.  The scope-map comparison
catches structural drift; the scope-hash comparison catches hash-level
tampering.  Either mismatch produces `:force-authorisation-grant-scope-mismatch`.

**Evidence emitted** (`Sew.clj:841-866`):
```clojure
:force-authorisation-executed
{:force-auth/before {:status (:authorization/status record)
                     :consumed? (:consumed? record)}
 :force-auth/after  {:status (:authorization/status record)
                     :consumed? (:consumed? record)
                     :execution-recorded? true
                     :executed-by addr
                     :executed-at now
                     :is-release is-release}}
;; Plus full event context:
{:force-auth/auth-id auth-id
 :force-auth/workflow-id workflow-id
 :force-auth/executed-by addr
 :force-auth/executed-at now
 :force-auth/is-release is-release
 :force-auth/token token
 :force-auth/amount amount
 :force-auth/recipient recipient
 :force-auth/scope-hash scope-hash}
```

#### 3.5.5 Provenance Plumbing Chain

The authorization-provenance envelope built during execution flows through
five functions before it reaches the consumption enforcement point:

##### Step 1: `apply-resolution-transition` (`resolution.clj:652-721`)

Accepts keyword arguments `:resolution-source` and `:authorization-provenance`:

```clojure
(defn apply-resolution-transition
  [world workflow-id caller is-release resolution-hash resolution-module-fn
   & {:keys [resolution-source authorization-provenance]}]
  ...
  ;; Store provenance on the resolution record
  (assoc-in world''' [:escrow-transfers workflow-id :resolution]
            (cond-> {:resolved-by caller :is-release is-release
                     :resolution-hash resolution-hash
                     :resolution-source (or resolution-source :normal)}
              authorization-provenance
              (assoc :authorization/provenance authorization-provenance)))
  ;; Immediate path (window=0 or final round):
  (if (or final-round? (not (pos? window-dur)))
    (t/ok (finalize world''' workflow-id (if is-release :released :refunded)
                    :authorization-provenance authorization-provenance))
    ;; Deferred path — provenance lives on the resolution until execute-pending-settlement
    (let [pending (t/make-pending-settlement {...})]
      (assoc-in world''' [:pending-settlements workflow-id] pending))))
```

The provenance is stored at `world[:escrow-transfers][wf-id][:resolution][:authorization/provenance]`.
In the deferred path (pending settlement created), it persists there until
`execute-pending-settlement` reads it back.

##### Step 2: `execute-pending-settlement` (`resolution.clj:758-794`)

Reads the provenance back and forwards it to `finalize`:

```clojure
:else
(let [auth-prov (get-in world [:escrow-transfers workflow-id :resolution
                                :authorization/provenance])
      world' (if (:is-release pending)
               (finalize world workflow-id :released
                         :authorization-provenance auth-prov)
               (finalize world workflow-id :refunded
                         :authorization-provenance auth-prov))]
  (t/ok world'))
```

##### Step 3: `finalize` (resolution-internal, `resolution.clj:2145-2163`)

Forwards to the lifecycle layer:

```clojure
(defn- finalize [world workflow-id direction & {:keys [authorization-provenance]}]
  (-> world
      (lc/finalize-escrow-accounting workflow-id direction
        :authorization-provenance authorization-provenance)
      (t/decrement-resolver-capacity resolver)))
```

##### Step 4: `finalize-escrow-accounting` (`lifecycle.clj:289-296`)

Public entry point that delegates to internal `finalize`:

```clojure
(defn finalize-escrow-accounting
  [world workflow-id direction & {:keys [authorization-provenance]}]
  (finalize world workflow-id direction
            :authorization-provenance authorization-provenance))
```

##### Step 5: `finalize` (lifecycle internal, `lifecycle.clj:165-287`)

This is where the held-reason is selected based on provenance presence:

```clojure
(defn- finalize
  [world workflow-id direction & {:keys [authorization-provenance]}]
  (let [...
        held-reason (if authorization-provenance
                      (if (= direction :released)
                        :force-authorised-release
                        :force-authorised-refund)
                      (if (= direction :released)
                        :escrow-settlement-released
                        :escrow-settlement-refunded))]
    ...
    (-> world-after-policy
        (acct/sub-held token sub-held-amt
                       {:action (str "finalize-" (name direction))
                        :reason held-reason
                        :authorization-provenance authorization-provenance
                        :extra {...}})
        ...)))
```

**Held-reason selection logic:**

| authorization-provenance present? | direction | held-reason |
|-----------------------------------|-----------|-------------|
| No | `:released` | `:escrow-settlement-released` |
| No | `:refunded` | `:escrow-settlement-refunded` |
| Yes | `:released` | `:force-authorised-release` |
| Yes | `:refunded` | `:force-authorised-refund` |

The `:force-authorised-*` reasons are listed in `exceptional-held-reasons`
(`accounting.clj:28-34`), which triggers the provenance-required check in
`adjust-held`.

#### 3.5.6 Accounting-Level Enforcement

The accounting layer in `accounting.clj` enforces force-authorisation at
the point of custody mutation.

##### 3.5.6.1 Exceptional Held Reasons

Defined at `accounting.clj:28-48`:

```clojure
(def exceptional-held-reasons
  #{:force-authorised-release :force-authorised-refund})

(def address-scoped-held-reasons
  #{:deposit :release :refund :escrow-settlement-released
    :escrow-settlement-refunded
    :force-authorised-release :force-authorised-refund
    ...})
```

These serve two purposes:
1. `exceptional-held-reasons` — triggers the mandatory provenance check in
   `adjust-held`: if the reason is in this set and `authorization-provenance`
   is nil, the adjustment is rejected.
2. `address-scoped-held-reasons` — ensures position isolation by requiring
   an `:owner/address` scope component.

##### 3.5.6.2 Held-Position Policy

At `accounting.clj:50-60`, both force-authorised reasons map to the `:escrow-principal`
account with `:held/workflow-id` as the scope key:

```clojure
(def held-position-policy
  {:force-authorised-release  {:held/account :escrow-principal
                               :scope-keys [:held/workflow-id]}
   :force-authorised-refund   {:held/account :escrow-principal
                               :scope-keys [:held/workflow-id]}
   :escrow-settlement-released {:held/account :escrow-principal
                                :scope-keys [:held/workflow-id]}
   :escrow-settlement-refunded {:held/account :escrow-principal
                                :scope-keys [:held/workflow-id]}})
```

This means force-authorised and normal settlement share the same custody
position.  The distinction is only in the reason and provenance — not in
the accounting position structure.

##### 3.5.6.3 `adjust-held` (`accounting.clj:481-527`)

The core custody mutation function that enforces force-authorisation:

```clojure
(defn- adjust-held
  [world token amount direction {:keys [action reason authorization-provenance extra]
                                 :or {action "adjust-held"}}]
  ;; 1. Validate basic inputs
  (validate-held-inputs! token amount)

  ;; 2. Check that exceptional reasons require provenance
  (when (and (contains? exceptional-held-reasons reason)
             (nil? authorization-provenance))
    (throw (ex-info "exceptional held adjustment requires authorization provenance"
                    {:type :invalid-held-adjustment
                     :reason :missing-authorization-provenance
                     :held/reason reason})))

  ;; 3. If force-authorisation, check usability + scope match
  (let [is-force-auth? (= :force-authorisation (:authorization/type authorization-provenance))]
    (when is-force-auth?
      (let [components (held-position-components token reason (or extra {}))
            scope-map (merge {:authorization/id (:authorization/id authorization-provenance)
                              :authorization/type :force-authorisation
                              :held/direction direction
                              :token token :amount amount
                              :held/account (:held/account components)
                              :owner/address (:owner/address components)
                              :held/reason reason}
                             (select-keys (or extra {})
                                          [:held/workflow-id :shortfall/started-at]))]
        (ensure-force-authorisation-usable! world authorization-provenance scope-map)))

    ;; 4. Check aggregate underflow
    (let [current (get-in world [:total-held token] 0)]
      (when (and (= direction :out) (< current amount))
        (throw (ex-info "sub-held underflow" ...)))
      (validate-held-position! world token amount direction reason extra)

      ;; 5. Build and persist the adjustment
      (let [adjustment (build-held-adjustment ...)
            artifact   (custody-core/build-held-custody-artifact adjustment)
            world'     (-> world
                           (append-held-adjustment adjustment)
                           (append-held-custody-artifact artifact))]
        ;; 6. If force-authorisation, mark consumed
        (if is-force-auth?
          (mark-force-authorisation-consumed world' authorization-provenance adjustment)
          world')))))
```

**Enforcement sequence:**
1. Validate token/amount are non-nil and non-negative
2. If reason is in `exceptional-held-reasons` and no provenance → reject
3. If provenance type is `:force-authorisation`:
   a. Build scope-map from actual position components (token, amount, direction, workflow)
   b. Call `ensure-force-authorisation-usable!` with the persisted record
4. Check aggregate underflow (`total-held[token] >= amount`)
5. Validate position-level underflow
6. Build and persist the held adjustment + custody artifact
7. If force-authorisation, call `mark-force-authorisation-consumed`

##### 3.5.6.4 `ensure-force-authorisation-usable!` (`accounting.clj:120-245`)

Full validation function called at the point of custody mutation:

```clojure
(defn- ensure-force-authorisation-usable!
  [world auth-provenance scope-map]
  (let [auth-id    (:authorization/id auth-provenance)
        scope-kind (:authorization/scope-kind auth-provenance :single-claim)
        record     (get-in world [:force-authorisations auth-id])
        now        (time-ctx/block-ts world)]
    ;; Guard 1: Record must exist
    (when-not record
      (throw (ex-info "force-authorisation record not found"
                      {:type :authorization/not-found :authorization/id auth-id})))
    ;; Guard 2: Status must be :active
    (when-not (= :active (:authorization/status record))
      (throw (ex-info "force-authorisation record is not active"
                      {:type (if (= :consumed (:authorization/status record))
                               :authorization/already-consumed
                               :authorization/not-active)
                       :authorization/id auth-id :status (:authorization/status record)})))
    ;; Guard 3: Not consumed (flag)
    (when (:consumed? record)
      (throw (ex-info "force-authorisation record already consumed"
                      {:type :authorization/already-consumed :authorization/id auth-id})))
    ;; Guard 4: Not before start
    (when (< now (:starts-at record))
      (throw (ex-info "force-authorisation record not yet active"
                      {:type :authorization/not-yet-started ...})))
    ;; Guard 5: Not expired
    (when (and (:expires-at record) (>= now (:expires-at record)))
      (throw (ex-info "force-authorisation record expired"
                      {:type :authorization/expired ...})))
    ;; Guard 6: Related-claims specific checks
    (when (= :related-claims scope-kind)
      ;; 6a. Record must also be related-claims
      (when-not (= :related-claims (:authorization/scope-kind record))
        (throw (ex-info "force-authorisation record is not a related-claims grant"
                        {:type :authorization/related-claims-scope-kind-mismatch ...})))
      ;; 6b. Relationship must match grant
      (when-not (and (= (:relationship/id record) (:relationship/id auth-provenance))
                     (= (:relationship/hash record) (:relationship/hash auth-provenance))
                     (= (set (:member-scope-hashes record))
                        (set (:member-scope-hashes auth-provenance))))
        (throw (ex-info "related-claims authorization provenance differs from grant"
                        {:type :authorization/related-claims-grant-mismatch ...})))
      ;; 6c. Check related-claims is active
      (let [rel (rc/get-related-claims world (:relationship/id auth-provenance))]
        (when-not (and rel (rc/related-claims-active? ...))
          (throw (ex-info "related-claims relationship not active" ...))))
      ;; 6d. Check member scope hash is in authorized set
      (let [member-hash (force-authorisation-scope-hash scope-map)
            member-hashes (:member-scope-hashes auth-provenance [])]
        (when-not (contains? (set member-hashes) member-hash)
          (throw (ex-info "member scope not in authorized set" ...)))
        ;; 6e. Check member not already consumed
        (let [consumed-members (get-in world [:force-authorisations/consumed auth-id :consumed-members] #{})]
          (when (contains? consumed-members member-hash)
            (throw (ex-info "member scope already consumed" ...))))))
    ;; Guard 7: Single-claim specific checks
    (when (= :single-claim scope-kind)
      ;; 7a. Record must have immutable scope
      (when-not (and (:authorization/scope record) (:authorization/scope-hash record))
        (throw (ex-info "record lacks immutable scope" ...)))
      ;; 7b. Scope map must match grant
      (when-not (= (:authorization/scope record) scope-map)
        (throw (ex-info "scope differs from grant" ...)))
      ;; 7c. Scope hash must match recomputed hash
      (when-not (= (:authorization/scope-hash record) (force-authorisation-scope-hash scope-map))
        (throw (ex-info "scope hash mismatch" ...)))
      ;; 7d. Provenance hash must match grant hash
      (when-not (= (:authorization/scope-hash record)
                    (:authorization/scope-hash auth-provenance))
        (throw (ex-info "provenance does not match grant" ...)))
      ;; 7e. Not in consumption registry
      (when-let [consumed (get-in world [:force-authorisations/consumed auth-id])]
        (throw (ex-info "already consumed" ...))))))
```

**7 guard groups (14 individual checks):**

| Group | Guards | Scope-Kind | Error Types |
|-------|--------|------------|-------------|
| 1-3 | Record exists, status active, not consumed | Both | `:authorization/not-found`, `:authorization/not-active`, `:authorization/already-consumed` |
| 4-5 | Time window: not before start, not expired | Both | `:authorization/not-yet-started`, `:authorization/expired` |
| 6a-b | Record & provenance scope-kind match, relationship match | Related-claims only | `:authorization/related-claims-scope-kind-mismatch`, `:authorization/related-claims-grant-mismatch` |
| 6c | Relationship is active | Related-claims only | `:authorization/relationship-inactive` |
| 6d-e | Member hash authorized, member not consumed | Related-claims only | `:authorization/member-scope-not-authorized`, `:authorization/member-already-consumed` |
| 7a-b | Scope exists, scope matches grant | Single-claim only | `:authorization/missing-scope`, `:authorization/grant-scope-mismatch` |
| 7c-e | Hash matches recomputed, provenance matches, not in registry | Single-claim only | `:authorization/grant-scope-hash-mismatch`, `:authorization/provenance-scope-mismatch`, `:authorization/already-consumed` |

##### 3.5.6.5 `mark-force-authorisation-consumed` (`accounting.clj:262-313`)

Atomic consumption that creates the audit trail and transitions the grant:

```clojure
(defn- mark-force-authorisation-consumed
  [world auth-provenance adjustment]
  (let [auth-id    (:authorization/id auth-provenance)
        scope-kind (:authorization/scope-kind auth-provenance :single-claim)
        base {:consumed? true
              :authorization/id auth-id
              :authorization/type (:authorization/type auth-provenance)
              :authorization/scope-hash (:authorization/scope-hash auth-provenance)
              :held-adjustment/id (:held-adjustment/id adjustment)
              :token (:token adjustment)
              :amount (:amount adjustment)
              :owner/address (:owner/address adjustment)
              :workflow-id (:held/workflow-id adjustment)
              :held/reason (:held/reason adjustment)
              :consumed/action (:held/action adjustment)}]
    (if (= :related-claims scope-kind)
      ;; Per-member consumption: grant stays active until all members consumed
      (let [member-hash (member-scope-hash-from-adjustment auth-provenance adjustment)
            existing    (or (get-in world [:force-authorisations/consumed auth-id])
                            {:consumed? false
                             :authorization/scope-kind :related-claims
                             :relationship/id (:relationship/id auth-provenance)
                             :relationship/hash (:relationship/hash auth-provenance)
                             :member-scope-hashes (:member-scope-hashes auth-provenance [])
                             :consumed-members #{} :member-count 0})
            updated     (-> existing
                            (assoc :consumed? true
                                   :last-consumed-at (:held-adjustment/id adjustment))
                            (update :consumed-members conj member-hash)
                            (update :member-count inc)
                            (assoc :last-consumed-adjustment-id (:held-adjustment/id adjustment)
                                   :last-consumed-workflow-id (:held/workflow-id adjustment)))
            committed   (set (get-in world [:force-authorisations auth-id :member-scope-hashes] []))
            all-done?   (= committed (:consumed-members updated))]
        (cond-> (assoc-in world [:force-authorisations/consumed auth-id] updated)
          all-done?
          (assoc-in [:force-authorisations auth-id]
                    (assoc (get-in world [:force-authorisations auth-id])
                           :authorization/status :consumed :consumed? true))))
      ;; Single-claim: consume entirely in one step
      (let [consumed-entry (merge base (when-let [d (:held/direction adjustment)]
                                         {:held/direction d}))]
        (-> world
            (assoc-in [:force-authorisations/consumed auth-id] consumed-entry)
            (assoc-in [:force-authorisations auth-id :consumed?] true)
            (assoc-in [:force-authorisations auth-id :authorization/status] :consumed))))))
```

**Two consumption models:**

| Aspect | Single-Claim | Related-Claims |
|--------|-------------|----------------|
| Consumption trigger | One `sub-held` call | One `sub-held` per member |
| Grant status after first | `:consumed` (terminal) | `:active` (partial) |
| Grant status after all | — | `:consumed` (terminal) |
| Consumption entry | Single `{:consumed? true ...}` | Accumulates `:consumed-members` set |
| Member tracking | N/A | `member-scope-hash`, `member-count` |

##### 3.5.6.6 Scope-Hash Domain

From `accounting.clj:104-109` and `assurance/force_authorisation.clj:16-26`:

```clojure
(def ^:const force-authorisation-scope-domain
  "force-authorisation-scope")

(defn force-authorisation-scope-hash [scope-map]
  (let [h (requiring-resolve 'resolver-sim.hash.canonical/domain-hash)]
    (h force-authorisation-scope-domain scope-map)))
```

The scope-hash is computed using the canonical `domain-hash` function with
the domain string `"force-authorisation-scope"`.  This domain separation
prevents hash collisions with other protocol features that use the same
`domain-hash` primitive.

#### 3.5.7 Protocol-Independent Assurance Layer

The `assurance/force_authorisation.clj` namespace (`src/resolver_sim/assurance/`)
provides protocol-independent validation that operates on plain data maps
instead of a Sew world state.

**Boundary guard** (file header): `MUST NOT import` any Sew namespaces.
Enforced by a portability test at `test/resolver_sim/assurance/force_authorisation_portability_test.clj`.

##### 3.5.7.1 Normalization Functions

Three functions normalize force-authorisation data from JSON (string keys),
EDN fixtures, or protocol-state maps:

```clojure
(normalize-force-authorisation-scope scope-map)
  ;; → canonical scope with keyword keys, coerced types
(normalize-force-authorisation-record record)
  ;; → canonical record with defaults (:consumed? false, :status :active, :type :force-authorisation)
(normalize-force-authorisation-records records)
  ;; → map of {auth-id normalized-record}
(normalize-force-authorisation-consumption-registry registry)
  ;; → map of {auth-id normalized-consumption-entry}
```

##### 3.5.7.2 `verify-authorisation-usable` (`assurance.clj:159-210`)

Protocol-independent equivalent of `ensure-force-authorisation-usable!`:

```clojure
(defn verify-authorisation-usable
  [record consumption-registry scope-map now-ts]
  ;; Returns {:valid? true} or {:valid? false :errors [...]}
  ;; Checks:
  ;;   1. record exists
  ;;   2. status is :active
  ;;   3. not consumed (record flag)
  ;;   4. not in consumption registry
  ;;   5. time window (starts-at < now-ts < expires-at)
  ;;   6. scope-hash matches recomputed
  ;;   7. scope map matches grant
  )
```

##### 3.5.7.3 `verify-authorisation-lifecycle-consistency` (`assurance.clj:212-246`)

Protocol-independent equivalent of `force-authorisations-lifecycle-consistent?`:

```clojure
(defn verify-authorisation-lifecycle-consistency
  [authorisations consumption-registry]
  ;; Returns {:holds? true} or {:holds? false :violations [...]}
  ;; Checks:
  ;;   1. Every consumed auth has a corresponding grant record
  ;;   2. Every grant record with a consumption entry has status :consumed
  ;;   3. No grant has scope-hash without scope map
  )
```

#### 3.5.8 Evidence Contracts

The `evidence/force_authorisation.clj` namespace (`src/resolver_sim/evidence/`)
defines protocol-independent schemas and temporal-ordering validators:

```clojure
(def scope-schema
  #{:authorization/id :authorization/type :held/direction
    :token :amount :held/account :owner/address
    :held/reason :held/workflow-id})

(def evidence-envelope-schema
  #{:evidence/kind :evidence/auth-id :evidence/grant-time
    :evidence/scope-hash :evidence/execution-time
    :evidence/consumption-time :evidence/held-adjustment-id})
```

| Function | Purpose |
|----------|---------|
| `valid-scope?` | True when scope-map contains all 9 required keys |
| `scope-matches?` | True when evidence auth-id and scope-hash match authorization record |
| `valid-envelope?` | True when evidence envelope has all 7 required keys |
| `grant-before-execution?` | True when `grant-time <= execution-time` |
| `execution-before-consumption?` | True when `execution-time <= consumption-time` |

These provide the temporal ordering properties:
- **Grant before execute**: A force-authorisation must be granted before it
  can be executed.  `grant-before-execution?` enforces `grant-time <= exec-time`.
- **Execute before consume**: Execution must precede (or be simultaneous with)
  consumption.  `execution-before-consumption?` enforces `exec-time <= consume-time`.

#### 3.5.9 Force-Authorisation Record Structure

Created at grant time (`Sew.clj:647`), the record carries both grant-time
and execution-time data:

```clojure
;; Full record after both grant and execute:
{:authorization/id           "fa-<n>"           ;; unique auto-incrementing ID
 :authorization/version      "force-authorisation.v2"
 :authorization/type         :force-authorisation
 :authorization/source       :governance
 :authorization/status       :active | :consumed | :revoked
 :workflow-id                <integer>
 :allowed-action             "execute-resolution"
 :nonce                      "fa-<n>"            ;; = auth-id (replay protection)

 ;; ── Grant-time immutable scope ──
 :authorization/scope        {:authorization/id auth-id
                               :authorization/type :force-authorisation
                               :held/direction :out
                               :token <token> :amount <amount>
                               :held/account :escrow-principal
                               :owner/address <addr>
                               :held/reason :force-authorised-release|:force-authorised-refund
                               :held/workflow-id <wf-id>}
 :authorization/scope-hash   <domain-hash of scope>

 ;; ── Timing window ──
 :starts-at                  <integer>           ;; Unix timestamp
 :expires-at                 <integer | nil>     ;; nil = no expiry

 ;; ── Grant metadata ──
 :created-at                 <integer>
 :created-by                 <address>
 :reason                     <keyword>           ;; from forced-authorisation-policy
 :consumed?                  false

 ;; ── Provenance tracking ──
 :authorization/provenance   <grant-provenance-map>
 :authorization/last-provenance <grant-provenance-map>
 :authorization/last-action  "grant-force-authorisation"
 :authorization/history      [{:authorization/action "grant-force-authorisation"
                                :authorization/provenance <grant-prov>}]

 ;; ── Execution metadata (added by execute, NOT consumed yet) ──
 :executed-by                <address>
 :executed-at                <integer>
 :execution/is-release       true | false
 :execution/provenance       <execution-prov-map>
 :execution/last-provenance  <execution-prov-map>
 :execution/last-action      "execute-force-authorised-action"
 :execution/history          [{:execution/action "execute-force-authorised-action"
                                :execution/provenance <execution-prov>}]}
```

**World-state keys** (`types.clj:282-283`):
```clojure
:force-authorisations        {}   ;; {auth-id -> force-authorisation-record}
:next-force-authorisation-id 0    ;; counter for auth-id generation
:force-authorisations/consumed {} ;; {auth-id -> consumption-entry} (runtime, in accounting)
```

#### 3.5.10 Scope Map and Scope-Hash Enforcement

The scope is recomputed at three points, each with a different purpose:

| Point | Scope Source | Purpose |
|-------|-------------|---------|
| Grant (`Sew.clj:631`) | EscrowTransfer at grant time | Immutable original: records the permitted scope |
| Execute (`Sew.clj:795`) | EscrowTransfer at execution time | Verification: must match grant scope exactly |
| Consume (`accounting.clj:493`) | Held adjustment position components | Enforcement: must match grant scope and hash |

**Hash recomputation chain:**

```
GRANT time:
  scope (from escrow) → domain-hash → scope-hash  → stored on record

EXECUTE time:
  scope (from current escrow) → domain-hash → scope-hash
  compare: computed == stored?  If no → reject (:force-authorisation-grant-scope-mismatch)

CONSUME time (adjust-held):
  scope (from position components) → domain-hash → scope-hash
  compare: computed == :authorization/scope-hash record?  If no → reject
  compare: computed == :authorization/scope-hash provenance?  If no → reject
  compare: full scope map == :authorization/scope record?  If no → reject
```

**Required scope keys:**
```clojure
#{:authorization/id :authorization/type :held/direction
  :token :amount :held/account :owner/address
  :held/reason :held/workflow-id}
```

All 9 keys must match exactly at both execute and consume time.

#### 3.5.11 Authorization-Provenance Envelope

Two provenance envelopes exist, flowing through different stages:

**Execution provenance** (built at `Sew.clj:808`):
```clojure
{:authorization/schema-version      "force-authorisation.v2"
 :authorization/type                :force-authorisation
 :authorization/id                  auth-id
 :authorization/scope-hash          <hash>
 :authorization/source              :governance
 :authorization/check               :force-authorisation-record
 :authorization/workflow-id         workflow-id
 :authorization/allowed-action      "execute-resolution"
 :authorization/executed-by         addr
 :authorization/executed-at         now
 :authorization/governance-provenance  <original grant provenance>}
```

**Consumption provenance** (embedded in held adjustment, built at `accounting.clj:491`):
- Derived from the execution provenance carried through the settlement chain
- Same `:authorization/type` and `:authorization/id`
- Carried as `:authorization/provenance` on the held-adjustment record

**Provenance wire format through the chain:**

```
execute-force-authorised-action
  │ execution-prov
  ▼
apply-resolution-transition (stored on resolution record)
  │ authorization-provenance = execution-prov
  ▼
finalize (resolution.clj:710 or resolution.clj:789)
  │ authorization-provenance = execution-prov
  ▼
finalize-escrow-accounting (lifecycle.clj:294)
  │ authorization-provenance = execution-prov
  ▼
finalize (lifecycle.clj:174)
  │ authorization-provenance = execution-prov
  │ held-reason = :force-authorised-release or :force-authorised-refund
  ▼
sub-held (lifecycle.clj:235)
  │ {:reason held-reason :authorization-provenance execution-prov :extra {...}}
  ▼
adjust-held (accounting.clj:482)
  │ Detects is-force-auth? from (:authorization/type execution-prov)
  │ Rebuilds scope-map from position components
  │ Calls ensure-force-authorisation-usable! → mark-force-authorisation-consumed
  ▼
held-adjustment record
  {:authorization/provenance execution-prov
   :authorization/id auth-id
   :held/reason :force-authorised-release|:force-authorised-refund
   :token <token> :amount <amount> :owner/address <addr> ...}
```

#### 3.5.12 How Force-Authorisation Interleaves with the Standard Lifecycle

Force-authorisation replaces the normal resolver decision at Phase 2
(Resolution) but thereafter follows the same settlement path:

```
Standard path:                              Force-authorisation path:

disputed                                    disputed
    │                                           │
    ▼                                           ▼
execute-resolution (resolver)               execute-force-authorised-action
    │                                           │
    ▼                                           ▼
apply-resolution-transition                 apply-resolution-transition
    │                                           │  (with :resolution-source
    │                                           │   :force-authorised
    │                                           │   + authorization-provenance)
    ▼                                           ▼
┌───┴───────────────────┐                 ┌────┴──────────────────┐
│ window=0    window>0  │                 │ window=0    window>0  │
│   │           │       │                 │   │           │       │
│   ▼           ▼       │                 │   ▼           ▼       │
│ finalize   Pending    │                 │ finalize   Pending    │
│ (terminal) Settlement │                 │ (terminal) Settlement │
│            (disputed) │                 │ (disputed)            │
│                       │                 │   │                   │
│                       │                 │   │ (auth-prov       │
│                       │                 │   │  stored in       │
│                       │                 │   │  resolution)     │
└───────────────────────┘                 └───┴──────────────────┘
                                                │
                                                ▼
                                         execute-pending-settlement
                                                │ (reads auth-prov
                                                │  from resolution)
                                                ▼
                                            finalize
                                                │
                                                ▼
                                            sub-held → ensure-force-authorisation-usable!
                                                     → mark-force-authorisation-consumed
                                                │
                                                ▼
                                          terminal state
                                                │
                                                ▼
                                     (held-adjustment created
                                      with authorization-provenance)
```

**Key difference:** In the standard path, `finalize` uses held reasons
`:escrow-settlement-released` or `:escrow-settlement-refunded`.  In the
force-authorisation path, it uses `:force-authorised-release` or
`:force-authorised-refund`.  This distinction triggers the mandatory
provenance check in `adjust-held`.

**Gap 1 guard** (`resolution.clj:683-685`):
```clojure
(assoc-in world' [:previous-decisions workflow-id (t/dispute-level world workflow-id)]
          {:resolver caller :is-release is-release
           :resolution-source (or resolution-source :normal)})
```
If a force-authorised resolution is attempted at the same dispute level where
a previous force-authorised resolution was already recorded, the
`archive-pending-on-escalation` call at line 670 will archive the old
pending.  The `clear-pending-settlement` at line 673 clears the pending.
But the `:resolution-source :force-authorised` stored in
`[:previous-decisions]` provides the audit trail.  The guard exists at the
scenario level — DR-FA-001 tests that a second execute at the same level
is rejected.

#### 3.5.13 Consumption Model: Dual Bookkeeping

Force-authorisation consumption is enforced at two independent levels:

**Grant record level** (`Sew.clj`):
- `:consumed?` flag on the grant record (boolean, set to `true` at consumption)
- Execution metadata recorded at stage 2 (execute) does NOT set `:consumed?`
- Transition `:active → :consumed` is final and checked by `ensure-force-authorisation-usable!`
- Status `:revoked` is set by governance action and also blocks execution

**Accounting layer level** (`accounting.clj`):
- `:force-authorisations/consumed` registry entries created atomically with
  the held adjustment via `mark-force-authorisation-consumed`
- Each entry links: `auth-id → held-adjustment-id → {token, amount, owner, workflow}`
- The single-claim entry is consumed in one `sub-held` call
- The related-claims entry accumulates per-member and becomes terminal when
  all members are consumed
- The invariant `force-authorisations-lifecycle-consistent?` validates
  cross-references between the grant records and the consumption registry

**Why deferred consumption (not at execute time):**

The execute action records provenance on the grant but does NOT consume it.
Consumption is deferred to `sub-held` during finalization.  This design
ensures:

1. **Atomicity with custody movement**: consumption is recorded in the same
   atomic operation that debits `:total-held`.  If finalization never happens
   (e.g., due to a pending settlement appeal), the grant remains active.
2. **Forensic chain**: the consumption entry is linked to the specific held
   adjustment (by `held-adjustment/id`), which is linked to the custody
   artifact, which is linked to the forensic bundle witness.
3. **Scope verification at the point of custody change**: the scope is
   rebuilt from the *actual* position components at `sub-held` time, not
   from the execution parameters.  This catches any mismatch between what
   was authorized and what was actually moved.

**Status transition diagram:**

```
  GRANT                    REVOKE
  ─────                    ──────
  :active ───────────────► :revoked
    │                          │
    │ EXECUTE                  │
    │ (provenance recorded,    │
    │  NOT consumed)           │
    │                          │
    ▼                          │
  :active                 :revoked
  (pending settlement)     (terminal - no execution)
    │
    │ FINALIZE (sub-held)
    │
    ▼
  :consumed
  (terminal - no further use)
    │
    ├── Single-claim: one sub-held → consumed immediately
    └── Related-claims: N sub-held → consumed on last member
```

#### 3.5.14 Lifecycle Consistency Invariant

`force-authorisations-lifecycle-consistent?` at `invariants.clj:344-408`:

```clojure
(defn force-authorisations-lifecycle-consistent?
  [world]
  (let [records (:force-authorisations world {})
        consumed (:force-authorisations/consumed world {})
        adjustments-by-auth (group-by #(get-in % [:authorization/provenance :authorization/id])
                                      (:held-adjustments world []))
        valid-statuses #{:active :consumed :revoked}
        ...
        record-violations
        (for [[auth-id record] records
              :let [status (:authorization/status record)
                    entry (get consumed auth-id)
                    linked (get adjustments-by-auth auth-id [])
                    related? (= :related-claims (:authorization/scope-kind record))
                    valid? (if related?
                             (valid-related? auth-id record entry linked)
                             (and (= auth-id (:authorization/id record))
                                  (contains? valid-statuses status)
                                  (or (not= :consumed status) (:consumed? record))
                                  (or (not= :active status) (not (:consumed? record)))
                                  (or (not= :active status) (nil? entry))
                                  (or (not= :consumed status)
                                      (and entry
                                           (= 1 (count linked))
                                           (= (:held-adjustment/id entry)
                                              (:held-adjustment/id (first linked)))
                                           (= (:authorization/scope-hash record)
                                              (get-in (first linked)
                                                     [:authorization/provenance :authorization/scope-hash]))))
                                  (or (not= :force-authorisation (:authorization/type record))
                                      (and (:authorization/scope record)
                                           (:authorization/scope-hash record))))))]
              :when (not valid?)]
          {:authorization/id auth-id :status status :type :invalid-authorisation-lifecycle
           :linked-adjustment-count (count linked)})
        orphan-consumption-violations
        (for [[auth-id entry] consumed
              :let [record (get records auth-id)
                    linked (get adjustments-by-auth auth-id [])]
              :when (or (nil? record)
                        (if (= :related-claims (:authorization/scope-kind record))
                          (not (valid-related? auth-id record entry linked))
                          (or (not= :consumed (:authorization/status record))
                              (not (:consumed? record))
                              (not= 1 (count linked))
                              (not= (:held-adjustment/id entry)
                                    (:held-adjustment/id (first linked))))))]
          {:authorization/id auth-id :type :orphan-or-inconsistent-consumption
           :record-status (:authorization/status record)
           :linked-adjustment-count (count linked)})
        violations (vec (concat record-violations orphan-consumption-violations))]
    {:holds? (empty? violations) :violations violations}))
```

**What the invariant enforces:**

| Check | Condition | Violation Type |
|-------|-----------|----------------|
| Valid status | `status in #{:active :consumed :revoked}` | `:invalid-authorisation-lifecycle` |
| Consumed records have `:consumed?` flag | If `:consumed`, then `(:consumed? record)` | `:invalid-authorisation-lifecycle` |
| Active records are not flagged consumed | If `:active`, then `(not (:consumed? record))` | `:invalid-authorisation-lifecycle` |
| Active records have no registry entry | If `:active`, then `(nil? entry)` | `:invalid-authorisation-lifecycle` |
| Consumed records have exactly 1 linked adjustment | `(= 1 (count linked))` | `:invalid-authorisation-lifecycle` |
| Consumed registry entry matches adjustment ID | `(:held-adjustment/id entry) == (:held-adjustment/id (first linked))` | `:invalid-authorisation-lifecycle` |
| Consumed adjustment scope-hash matches record | `(:authorization/scope-hash record)` matches provenance hash in adjustment | `:invalid-authorisation-lifecycle` |
| Record has scope + scope-hash | Both present for `:force-authorisation` type | `:invalid-authorisation-lifecycle` |
| No orphan consumption | Every consumed entry references an existing record | `:orphan-or-inconsistent-consumption` |
| Consumed status for consumed entries | Record status must be `:consumed` | `:orphan-or-inconsistent-consumption` |

**Related-claims specific sub-invariant** (`valid-related?`, line 354-368):
```clojure
(let [committed (set (:member-scope-hashes record []))
      linked-hashes (set (map #(related-member-scope-hash auth-id %) linked))
      consumed-hashes (set (:consumed-members entry #{}))
      complete? (= committed consumed-hashes linked-hashes)]
  (and (= :related-claims (:authorization/scope-kind record))
       (:relationship/id record) (:relationship/hash record)
       (seq committed)
       (= (count linked) (count linked-hashes))
       (= consumed-hashes linked-hashes)
       (case (:authorization/status record)
         :active   (and (not (:consumed? record)) (not complete?))
         :consumed (and (:consumed? record) complete?)
         :revoked  (and (not (:consumed? record)) (nil? entry) (empty? linked))
         false)))
```

A related-claims grant is valid when:
- **Active**: consumer has `:consumed-members` < committed members, partial consumption OK
- **Consumed**: exactly all committed members consumed, `:consumed?` flag set
- **Revoked**: no consumption entries, no linked adjustments

#### 3.5.15 Protocol State Hashes in Bundle Root

Force-authorisation state is included in the protocol-state witness for
forensic verification.  From `src/resolver_sim/run/bundle_root.clj:197-234`:

```clojure
;; In build-bundle-root:
:force-authorisations/hash        (hash/canonical-hash (:force-authorisations protocol-state))
:force-authorisations/consumed-hash (hash/canonical-hash
                                      (:force-authorisations/consumed protocol-state {}))

;; Conditionally included when present:
(when (seq (:force-authorisations protocol-state))
  (assoc :protocol/force-authorisations
         (:force-authorisations protocol-state)))
```

From `src/resolver_sim/hash/canonical.clj:1099-1106`:
```clojure
;; The :protocol-state hash intent includes:
:force-authorisations
:force-authorisations-consumed
```

This means:
- Every force-authorisation grant and consumption entry is committed into
  the bundle root hash
- An independent validator can recompute the hashes from the protocol state
  and compare with the bundle root
- Omitting or tampering with force-authorisation data will cause a hash
  mismatch detectable by `scripts/forensic/validate.py`

From `src/resolver_sim/io/scenario_runner.clj:894-927`:
```clojure
;; extract-protocol-state merges force-authorisations from scenario worlds:
(defn extract-protocol-state [scenario-root]
  (let [worlds (load-worlds scenario-root)
        ...
        force-auths (apply merge (map #(:force-authorisations %) worlds))
        consumed    (apply merge (map #(:force-authorisations/consumed %) worlds))]
    (cond-> {}
      (seq force-auths) (assoc :protocol/force-authorisations force-auths)
      (seq consumed)    (assoc :protocol/force-authorisations-consumed consumed))))
```

#### 3.5.16 Forensic Validation

The Python forensic validator (`scripts/forensic/validate.py:1-460`) checks:

1. **State hash presence**: When force-authorisation evidence events exist,
   the protocol state must include `force-authorisations/hash` and
   `force-authorisations/consumed-hash`.

2. **Evidence lifecycle**:
   - Grant evidence event must precede the corresponding execute event
   - Execute evidence event must precede the corresponding consumption
   - No double-execute event for the same auth-id
   - No double-consumption event for the same auth-id

3. **Orphan detection**: No consumption entry without a matching grant record;
   no grant record with `:consumed?` flag without a consumption entry.

#### 3.5.17 Related-Claims Support

Force-authorisation supports batch (related-claims) scope via the
`related-claims` registry (`related_claims.clj`).  When the scope kind
is `:related-claims`, a single force-authorisation can cover multiple
members of a relationship (e.g., a resolver group).  Consumption tracks
per-member:

```clojure
;; Related-claims consumption entry (per-member tracked):
{:consumed? true
 :authorization/id auth-id
 :authorization/type :force-authorisation
 :authorization/scope-hash <grant-scope-hash>
 :authorization/scope-kind :related-claims
 :relationship/id <rel-id>
 :relationship/hash <rel-hash>
 :member-scope-hashes [<member1-hash> <member2-hash> <member3-hash>]
 :consumed-members #{<member1-hash>}              ;; grows per execution
 :member-count 1                                   ;; increments per execution
 :last-consumed-adjustment-id <adj-id>
 :last-consumed-workflow-id <wf-id>}
```

A force-authorisation for a 3-member resolver group goes through 3 executions:
1. First member resolution → `:consumed-members = #{hash-A}`, status stays `:active`
2. Second member resolution → `:consumed-members = #{hash-A, hash-B}`, status stays `:active`
3. Third (last) member resolution → `:consumed-members = #{hash-A, hash-B, hash-C}`, status transitions to `:consumed`

The `valid-related?` sub-invariant ensures that at `:active` the consumed set
is a proper subset, and at `:consumed` the consumed set equals the committed
set exactly.

#### 3.5.18 Interactive REPL Function

The interactive REPL (`interactive_resolution.clj:248-365`) provides a
`force-authorised` helper for manual scenario testing:

```clojure
(defn force-authorised
  "Create and execute a force-authorised resolution in one interactive call.
   Builds provenance, computes auth-id, applies Gap 1 guard,
   creates persisted grant record, calls apply-resolution-transition."
  [session workflow-id is-release reason]
  ...)
```

This function:
1. Builds `build-force-authorisation-provenance` with reason and capacity context
2. Computes auth-id as `"fa-{wf}-{release|refund}-{scope-hash-prefix}"`
3. Checks Gap 1: rejects if `:previous-decisions` has a `:force-authorised` resolution at same level
4. Creates the persisted grant record in `world[:force-authorisations]`
5. Calls `apply-resolution-transition` with `:resolution-source :force-authorised`
   and the authorization-provenance

It is documented at `interactive_resolution.md:207` as `the explicit force-authorized helper`.

#### 3.5.19 Threat Model Properties

From `docs/research/FORCE_AUTHORISATION_AND_CUSTODY_THREAT_MODEL.md`:

**Safety properties:**

| ID | Property | Falsified By |
|----|----------|-------------|
| `:force-authorisation/exact-scope-single-use` | Executes at most once for its grant-time scope | Trace with reused auth-id or derived scope differs from grant scope |
| `:held-custody/position-isolation` | Outflow cannot drive position negative | Negative `:by-position`, `:by-account`, or `:by-workflow` balance |
| `:held-custody/terminal-principal-closure` | Terminal escrows retain no principal | Terminal workflow with non-zero principal position |
| `:forensic/authorisation-custody-linkage` | Forensic chain: grant → execution → consumption → adjustment | Validator accepts absent/inconsistent witness |

**Attacker capabilities:**
- Submit any action with arbitrary parameters and a resolved actor
- Reuse an authorization ID
- Choose release/refund direction inconsistent with grant
- Attempt outflow from different workflow or position
- Present caller-supplied provenance without a persisted grant
- Tamper with exported evidence

**In-model guarantees:**
- Force-authorisation accounting reloads the persisted record from world state;
  caller-supplied provenance is evidence, not authority
- Active records valid only in their time window
- Consumed record must have exactly one matching held adjustment
- Held ledger reconstructs materialized balances (artifact hash, predecessor
  continuity, local delta, non-negative, replay checks)
- Forensic validation requires protocol-state witness when force-authorisation
  evidence is present

#### 3.5.20 Benchmark and Claims

The `force-authorisation-custody-v1` benchmark (`benchmarks/packs/prf-core/force-authorisation-custody-v1.edn`)
registers the following claims:

| Claim ID | Evaluator |
|----------|-----------|
| `:force-authorisation-exact-scope-single-use` | `evaluate-force-authorisation-exact-scope` |
| `:held-custody-position-isolation` | (standard custody evaluator) |
| `:force-authorisation/scope-enforced` | (scope enforcement assertion) |
| `:force-authorisation/single-use` | (single-use assertion) |
| `:force-authorisation/custody-isolation` | (custody isolation assertion) |
| `:forensic-authorisation-custody-linkage` | `evaluate-forensic-linkage` |
| `:force-authorisation/evidence-linkage` | (evidence linkage assertion) |
| `:force-authorisation/expiry-enforced` | (expiry enforcement assertion) |

The capability definition at `src/resolver_sim/benchmark/capabilities/force_authorisation.clj`:

```clojure
(def capability-definition
  {:capability/id :capability/force-authorisation
   :capability/type :protocol
   :capability/version "1.0"
   :capability/description "Force-authorisation of exceptional settlement actions"})
```

#### 3.5.21 Complete Error Code Reference

| Keyword | Stage | Source Line | Meaning |
|---------|-------|-------------|---------|
| `:force-authorisation-workflow-not-found` | Grant | `Sew.clj:611` | Workflow-id does not exist in escrow-transfers |
| `:force-authorisation-workflow-not-disputed` | Grant | `Sew.clj:612` | Escrow is not in `:disputed` state |
| `:force-authorisation-invalid-reason` | Grant | `Sew.clj:613` | Reason parameter is not a keyword |
| `:force-authorisation-reason-not-allowed` | Grant | `Sew.clj:614` | Reason not in policy's `:allowed-reasons` set |
| `:force-authorisation-action-not-allowed` | Grant | `Sew.clj:615` | Allowed action is not `"execute-resolution"` |
| `:force-authorisation-invalid-settlement-direction` | Grant | `Sew.clj:616` | `:is-release` is not a boolean |
| `:force-authorisation-invalid-start-time` | Grant | `Sew.clj:617` | `:starts-at` is not a number |
| `:force-authorisation-invalid-duration` | Grant | `Sew.clj:618-619` | `:duration` present but not numeric or negative |
| `:force-authorisation-invalid-expiry` | Grant | `Sew.clj:620-621` | `:expires-at` present but not numeric |
| `:force-authorisation-invalid-time-window` | Grant | `Sew.clj:622-623` | `expires-at <= starts-at` (window inverted) |
| `:force-authorisation-conflicting-timing` | Grant | `Sew.clj:624` | Both `:expires-at` and `:duration` specified |
| `:force-authorisation-duration-exceeds-max` | Grant | `Sew.clj:625-626` | `expires-at - starts-at > max-duration` |
| `:force-authorisation-not-found` | Revoke/Execute | `Sew.clj:713, 764` | Auth-id not found in `:force-authorisations` |
| `:force-authorisation-not-active` | Execute | `Sew.clj:767` | Grant status is not `:active` |
| `:force-authorisation-already-consumed` | Execute | `Sew.clj:770, 786` | Grant `:consumed?` flag true OR in consumption registry |
| `:force-authorisation-workflow-mismatch` | Execute | `Sew.clj:773` | Execution workflow-id differs from grant |
| `:force-authorisation-action-mismatch` | Execute | `Sew.clj:776` | Execution action differs from grant's `:allowed-action` |
| `:force-authorisation-not-yet-started` | Execute/Consume | `Sew.clj:779`, `accounting.clj:157` | Current time before `:starts-at` |
| `:force-authorisation-expired` | Execute/Consume | `Sew.clj:783`, `accounting.clj:163` | Current time at/after `:expires-at` |
| `:force-authorisation-grant-scope-mismatch` | Execute | `Sew.clj:807` | Rebuilt scope/hash does not match grant's scope/hash |
| `:authorization/not-found` | Consume | `accounting.clj:136` | Record not found at `ensure-force-authorisation-usable!` |
| `:authorization/not-active` | Consume | `accounting.clj:139` | Record status not `:active` |
| `:authorization/already-consumed` | Consume | `accounting.clj:153, 233` | Record already consumed at accounting level |
| `:authorization/not-yet-started` | Consume | `accounting.clj:157` | Time before `:starts-at` |
| `:authorization/expired` | Consume | `accounting.clj:163` | Time at/after `:expires-at` |
| `:authorization/missing-scope` | Consume | `accounting.clj:186` | Record lacks immutable scope |
| `:authorization/grant-scope-mismatch` | Consume | `accounting.clj:190` | Scope map differs from grant |
| `:authorization/grant-scope-hash-mismatch` | Consume | `accounting.clj:196` | Recomputed hash differs from grant hash |
| `:authorization/provenance-scope-mismatch` | Consume | `accounting.clj:202` | Provenance hash differs from grant hash |
| `:authorization/related-claims-scope-kind-mismatch` | Consume | `accounting.clj:171` | Record is not related-claims but provenance type is |
| `:authorization/related-claims-grant-mismatch` | Consume | `accounting.clj:178` | Relationship ID/hash differs from grant |
| `:authorization/relationship-inactive` | Consume | `accounting.clj:212` | Related-claims relationship not active |
| `:authorization/member-scope-not-authorized` | Consume | `accounting.clj:219` | Member scope hash not in authorized set |
| `:authorization/member-already-consumed` | Consume | `accounting.clj:227` | Member scope hash already consumed |
| `:invalid-held-adjustment` | Consume | `accounting.clj:488` | Exceptional reason without authorization-provenance |

#### 3.5.22 Scenarios

Two canonical DR-FA scenarios plus one additional scenario:

| Scenario ID | File | Tests |
|-------------|------|-------|
| **DR-FA-001-force-authorisation-basic** | `data/fixtures/traces/dr-fa-001-...` | Full lifecycle: grant → execute → consume. Verifies scope enforcement, single-use, evidence linkage, held-custody isolation |
| **DR-FA-002-force-authorisation-expired** | `data/fixtures/traces/dr-fa-002-...` | Grant with short expiry → wait past expiry → execute rejected. Verifies `:force-authorisation-expired` |
| **S64-force-refund-then-illegal-release-attempt** | `scenarios/edn/S64-...` | Force-authorised refund executed, then illegal release attempt rejected. Tests scope isolation after consumption |

Suite registration at `src/resolver_sim/scenario/suites.clj:169-173`:
```clojure
(def force-authorisation-scenario-ids
  ["DR-FA-001-force-authorisation-basic"
   "DR-FA-002-force-authorisation-expired"])
```

Available trace and golden files:
- `data/fixtures/traces/dr-fa-001-force-authorisation-basic.trace.json`
- `data/fixtures/traces/dr-fa-002-force-authorisation-expired.trace.json`
- `data/fixtures/golden/dr-fa-001-force-authorisation-basic.report.edn`
- `data/fixtures/golden/dr-fa-002-force-authorisation-expired.report.edn`

#### 3.5.23 Source Map

| File | Lines | Content |
|------|-------|---------|
| `protocols_src/.../Sew.clj` | 95-131 | `forced-authorisation-policy` definition |
| `protocols_src/.../Sew.clj` | 133-182 | `build-force-authorisation-provenance` |
| `protocols_src/.../Sew.clj` | 586-699 | `grant-force-authorisation` action handler |
| `protocols_src/.../Sew.clj` | 701-703 | `grant-force-authorization` (US-spelling alias) |
| `protocols_src/.../Sew.clj` | 705-744 | `revoke-force-authorisation` action handler |
| `protocols_src/.../Sew.clj` | 750-876 | `execute-force-authorised-action` action handler |
| `protocols_src/.../Sew/types.clj` | 282-283 | World state keys (`:force-authorisations`, `:next-force-authorisation-id`) |
| `protocols_src/.../Sew/resolution.clj` | 652-721 | `apply-resolution-transition` provenance plumbing |
| `protocols_src/.../Sew/resolution.clj` | 784-794 | `execute-pending-settlement` provenance read-back |
| `protocols_src/.../Sew/resolution.clj` | 2145-2163 | `finalize` (resolution internal) provenance forwarding |
| `protocols_src/.../Sew/lifecycle.clj` | 165-287 | `finalize` held-reason selection (`:force-authorised-release`/`:force-authorised-refund`) |
| `protocols_src/.../Sew/accounting.clj` | 28-48 | `exceptional-held-reasons`, `address-scoped-held-reasons` |
| `protocols_src/.../Sew/accounting.clj` | 50-60 | `held-position-policy` for force-authorised reasons |
| `protocols_src/.../Sew/accounting.clj` | 104-109 | `force-authorisation-scope-domain`, `force-authorisation-scope-hash` |
| `protocols_src/.../Sew/accounting.clj` | 111-118 | `scope-hash-mismatch?` |
| `protocols_src/.../Sew/accounting.clj` | 120-245 | `ensure-force-authorisation-usable!` (7 guard groups, 14 checks) |
| `protocols_src/.../Sew/accounting.clj` | 247-260 | `member-scope-hash-from-adjustment` |
| `protocols_src/.../Sew/accounting.clj` | 262-313 | `mark-force-authorisation-consumed` (single-claim + related-claims) |
| `protocols_src/.../Sew/accounting.clj` | 481-527 | `adjust-held` force-authorisation enforcement entry point |
| `protocols_src/.../Sew/invariants.clj` | 332-342 | `related-member-scope-hash` |
| `protocols_src/.../Sew/invariants.clj` | 344-408 | `force-authorisations-lifecycle-consistent?` |
| `src/resolver_sim/evidence/force_authorisation.clj` | 1-62 | Scope schema, envelope schema, temporal ordering validators |
| `src/resolver_sim/assurance/force_authorisation.clj` | 1-246 | Normalization, `verify-authorisation-usable`, `verify-authorisation-lifecycle-consistency` |
| `src/resolver_sim/run/bundle_root.clj` | 197-234 | Protocol state hashes for force-authorisations |
| `src/resolver_sim/hash/canonical.clj` | 1099-1106 | `:force-authorisations` in `:protocol-state` hash intent |
| `src/resolver_sim/io/scenario_runner.clj` | 894-927 | `extract-protocol-state` force-authorisation merging |
| `src/resolver_sim/benchmark/capabilities/force_authorisation.clj` | 1-73 | Capability definition, claim evaluators |
| `src/resolver_sim/scenario/suites.clj` | 169-173 | `force-authorisation-scenario-ids` |
| `scripts/forensic/validate.py` | 1-460 | State hash presence, evidence lifecycle, orphan detection |
| `docs/research/FORCE_AUTHORISATION_AND_CUSTODY_THREAT_MODEL.md` | 1-119 | Assets, actors, attacker model, safety properties |

---

### 3.6 Lineage-Original Priority in Yield-Backed Settlement

The `lineage-original` system governs how yield positions maintain their
deposit-time priority across multiple partial-fill cycles during escrow
settlement.  When an escrow with yield backing is finalized, a liquidity
shortfall may cause the yield module to defer part of the settlement —
creating a deferred position that inherits the original deposit priority.
This ensures earlier depositors maintain their place in line across repeated
shortfall rounds.

#### 3.6.1 Core Data Structure: original-priority

Defined in `src/resolver_sim/yield/position.clj:34`:

```clojure
;; On every yield position:
{:position/id          [:yield/position owner-id module-id token]
 :owner/id             owner-id
 :module/id            module-id
 :token                token-kw
 :principal            <deposited amount>
 :original-priority    <Long/MAX_VALUE>     ;; ← THE LINEAGE FIELD
 :status               :active
 ;; ... yield buckets, accrual, flags ...
}
```

`:original-priority` is an auto-incrementing sequence number per
`[module-id, token]`, assigned at deposit time.  Lower values = older
positions that get priority in shared withdrawals.  The default value
`Long/MAX_VALUE` is used for legacy positions that predate the field.

#### 3.6.2 Priority Assignment at Deposit Time

From `liquid_lending.clj:147-203`, the `deposit` function:

```clojure
(defn deposit [world module op]
  ...
  (let [sequence-number (get-in world
                                [:yield/deposit-seq module-id token]
                                0)
        world' (-> world
                   (assoc-in [:yield/deposit-seq module-id token]
                             (inc sequence-number))
                   (assoc-in [:yield/positions owner-id]
                             (pos/make-position
                              {:owner/id owner-id
                               :original-priority sequence-number
                               ...})))]
    ...))
```

Each deposit atomically:
1. Reads the current sequence number from `[:yield/deposit-seq][module-id][token]`
2. Writes it as the position's `:original-priority`
3. Increments the sequence counter

The sequence counter is module-token scoped, so priority ordering is
independent across different yield modules and tokens.

#### 3.6.3 Deferred Position Creation During Shortfall

When `finalize` encounters a liquidity shortfall (insufficient total-held
for the requested withdrawal), the yield module creates a deferred position
via `withdraw-shared` (`liquid_lending.clj:893-998`).  The key inheritance
logic:

```clojure
original-priority       (:original-priority precondition)     ;; from propagation
...
deferred                (long (:deferred participant 0))
fulfilled               (long (:fulfilled participant 0))
current-deferred        (:deferred-position position)         ;; prior deferred, if any

;; Successor deferred position:
{:position/id                  successor-id
 :position/type                :deferred-withdrawal
 :position/token               token
 :position/participant-id      participant-id
 :position/root-obligation-id  (or (:position/root-obligation-id current-deferred)
                                   obligation-id)
 :position/parent-id           (or (:position/id current-deferred)
                                   (:position-id current-commitment))
 :position/parent-hash         (or (:deferred-position-hash current-commitment)
                                   (:position-hash current-commitment))
 :position/origin-propagation-id propagation-id
 :position/created-by-transition-hash transition-hash
 :position/created-order       application-order
 :position/created-event-time  event-time
 :position/round               (next-lineage-round position)
 :position/original-priority   (or (:position/original-priority current-deferred)
                                   original-priority)
 :position/original-priority-source
                               (if current-deferred
                                 :inherited-from-prior-lineage
                                 :from-precondition)
 :position/original-obligation (or (:position/original-obligation current-deferred)
                                   (:eligible-obligation participant))
 :position/current-amount      deferred
 :position/cumulative-fulfilled (+ (long (:cumulative-fulfilled position 0))
                                   fulfilled)
 :position/eligibility         :later-liquidity
 :position/status              :active}
```

**Priority inheritance rules:**

| Scenario | `original-priority` value | `:original-priority-source` |
|----------|--------------------------|----------------------------|
| First deposit | `sequence-number` (auto-increment) | (not set — base position) |
| First deferral (no prior deferred) | Copied from propagation precondition's `:original-priority` | `:from-precondition` |
| Subsequent deferral (has prior deferred) | Copied from `current-deferred.position/original-priority` | `:inherited-from-prior-lineage` |

This ensures that across *N* shortfall rounds, the original deposit sequence
number is preserved — never incremented, never reset.

#### 3.6.4 Deferred Position Chain (Round Numbers)

Each deferral increments the round number via `next-lineage-round`
(`liquid_lending.clj:502-507`):

```clojure
(defn- next-lineage-round [position]
  (let [active-round (get-in position [:deferred-position :position/round] 0)
        maximum-history-round (reduce max 0 (lineage-history-rounds position))]
    (inc (max (long active-round) maximum-history-round))))
```

The function considers both the currently active deferred position's round
and all closed (historical) rounds.  This prevents round-number collisions
if a deferred position is superseded and a new one is created at the same
time.

**Deferred position chain structure:**

```
Base position (round 0)
  :original-priority = 5
  :deferred-position = nil
    │
    ├── Shortfall round 1 → deferred position (round 1)
    │     :position/original-priority = 5           (:from-precondition)
    │     :position/parent-id = <base-position-id>
    │     :position/parent-hash = <base-position-hash>
    │     :position/origin-propagation-id = prop-1
    │     :deferred-position = successor (active)
    │
    ├── Shortfall round 2 → superseded + new (round 2)
    │     Prior closed: :position/status = :closed
    │                  :position/closed-by-propagation-id = prop-2
    │                  :position/successor-id = <round-2-id>
    │     New deferred: :position/original-priority = 5  (:inherited-from-prior-lineage)
    │                   :position/parent-id = <round-1-id>
    │                   :position/parent-hash = <round-1-hash>
    │                   :position/round = 2
```

#### 3.6.5 Deferred Position History (Immutable Closure)

When a deferred position is superseded (a new deferral replaces it), the
old one is closed and archived immutably in `:deferred-position-history`
(`liquid_lending.clj:437-455`):

```clojure
(defn record-closed-deferred-position [history record]
  (let [history (or history {})
        position-id (:position/id record)
        existing (get history position-id)]
    (cond
      (nil? existing) (assoc history position-id record)
      (= existing record) history         ;; idempotent
      :else (fail! "Deferred position history conflict"
             :deferred-position-history-conflict
             {:position-id position-id :existing existing :replacement record}))))
```

The closed record includes:
```clojure
{:position/status                   :closed
 :position/closed-from-amount       <prior-current-amount>
 :position/current-amount           0
 :position/closed-by-propagation-id <propagation-id>
 :position/closed-by-transition-hash <hash>
 :position/closed-order             <application-order>
 :position/closed-event-time        <event-time>
 :position/successor-id             <successor-id>  ;; if deferred > 0
 :position/root-obligation-id       <obligation-id>
 :position/participant-id           <id>
 :position/token                    <token>
 :position/round                    <round>
 :position/original-priority        <priority>       ;; preserved
 :position/original-priority-source :inherited-from-prior-lineage
 ;; ... all other fields preserved from active state}
```

The history map is keyed by `:position/id` and is write-once: attempting
to store a different record under an existing key raises
`:deferred-position-history-conflict`.

#### 3.6.6 Propagation Precondition Validation

Before a pro-rata propagation is applied, the system validates that the
committed preconditions still match the current world state.  The
`original-priority` check at `liquid_lending.clj:682-688`:

```clojure
(when-not (= (:original-priority precondition)
             (:original-priority current-commitment))
  (fail! "Original priority changed after propagation calculation"
         :original-priority-precondition-mismatch
         {:participant-id participant-id
          :expected (:original-priority precondition)
          :actual (:original-priority current-commitment)}))
```

This ensures that the propagation was calculated against the same priority
ordering that exists at application time.  If priorities have shifted
(e.g., due to an intervening mutation), the propagation is rejected.

**Complete precondition validation sequence** (all checks at lines 660-733):

| # | Check | Error Code |
|---|-------|-----------|
| 1 | Participant position exists | `:propagation-participant-position-missing` |
| 2 | Token matches propagation | `:propagation-participant-token-mismatch` |
| 3 | Position hash unchanged | `:stale-propagation-position-state` |
| 4 | **Original priority unchanged** | **`:original-priority-precondition-mismatch`** |
| 5 | Obligation lineage consistent | `:withdrawal-obligation-lineage-mismatch` |
| 6 | Source position ID matches precondition | `:source-position-precondition-mismatch` |
| 7 | Eligible obligation matches precondition | `:eligible-obligation-precondition-mismatch` |
| 8 | Deferred amount matches precondition | `:deferred-amount-precondition-mismatch` |
| 9 | Participant obligation reconciles | `:participant-obligation-does-not-reconcile` |

#### 3.6.7 Authoritative Priority Resolution

The `authoritative-original-priority` function (`liquid_lending.clj:62-81`)
resolves the definitive priority for any position, handling both base and
deferred values:

```clojure
(defn- authoritative-original-priority [owner-id position]
  (let [base-priority (:original-priority position)
        deferred-priority (deferred-original-priority position)]
    (when (and (some? base-priority)
               (some? deferred-priority)
               (not= base-priority deferred-priority))
      (fail! "Position priority contradicts deferred lineage"
             :original-priority-lineage-mismatch
             {:owner-id owner-id
              :base-priority base-priority
              :deferred-priority deferred-priority}))
    (non-negative-integer!
     (or deferred-priority base-priority Long/MAX_VALUE)
     :missing-or-invalid-original-priority
     {:owner-id owner-id})))
```

**Resolution rules:**

| Base Priority | Deferred Priority | Result |
|---------------|-------------------|--------|
| Present | Absent (nil) | `base-priority` |
| Absent (nil) | Present | `deferred-priority` |
| Both present, matching | `base-priority` (or `deferred-priority` — they are equal) |
| Both present, mismatching | **Fail**: `:original-priority-lineage-mismatch` |
| Both nil | `Long/MAX_VALUE` (legacy fallback) |

The mismatch check at line 72-77 prevents a scenario where the base
position's priority and the deferred position's priority diverge —
which would indicate a data integrity failure in the lineage chain.

#### 3.6.8 Propagation Policy: preserve-original

The canonical pro-rata propagation policy (`pro_rata_propagation_policy.clj:13-28`)
declares the priority semantics:

```clojure
(def shared-withdrawal-policy
  {:schema-version "pro-rata-propagation-policy.v1"
   :policy/id :shared-withdrawal-propagation
   :policy/version 1
   :policy/domain :shared-withdrawal
   :shortfall {:classification :deferred
               :next-position/type :deferred-withdrawal
               :next-position/eligibility :later-liquidity
               :next-round-weight-policy :residual-entitlement}
   :priority {:propagation-policy :preserve-original}    ;; ← HERE
   :rounding {:propagation-policy :independent-rounds}
   :fulfilled-position {:terminal-state :closed}
   :residual-liquidity {:destination :remain-in-shared-liquidity}
   ...})
```

The `:preserve-original` propagation policy means:
- Shared withdrawal allocation order is determined by each position's
  `:original-priority` (deposit sequence number).
- Deferred positions inherit the original priority from their prior lineage.
- Earlier depositors maintain priority across partial-fill cycles.

#### 3.6.9 Transition Commitment (Cryptographic Sealing)

Each lineage transition is cryptographically sealed with a deterministic hash
(`liquid_lending.clj:790-802`):

```clojure
(defn- transition-commitment
  [propagation participant precondition application-order event-time]
  (canonical-hash
   {:schema-version "deferred-lineage-transition.v1"
    :propagation-content-hash (get propagation propagation-content-hash-field)
    :participant-id (:participant-id participant)
    :obligation-id (:obligation-id precondition)
    :source-position-hash (:position-hash precondition)
    :application-order application-order
    :event-time event-time
    :fulfilled (:fulfilled participant 0)
    :deferred (:deferred participant 0)}))
```

Fields committed:
- `propagation-content-hash` — links back to the propagation decision
- `participant-id` — identifies the position owner
- `obligation-id` — links to the withdrawal obligation (root of the
  obligation chain)
- `source-position-hash` — the exact position state before deferral
- `application-order` — the canonical execution context (run, step, event)
- `event-time` — simulation timestamp
- `fulfilled` / `deferred` — the exact amounts

This hash is stored as `:position/created-by-transition-hash` on the
deferred position and as `:position/closed-by-transition-hash` on the
closed predecessor, creating an unbroken chain of cryptographic evidence.

#### 3.6.10 Verdict Policy Integration

The verdict policy (`src/resolver_sim/run/verdict_policy.clj:177`) validates
lineage fields during artifact checking:

- Optional lineage fields in held-custody artifacts are validated when present
- The `original-priority` must be consistent across the deferral chain
- Parent-child linkage (`:position/parent-id`, `:position/parent-hash`)
  must form a valid directed acyclic graph

#### 3.6.11 Evidence Capture for Deferred Positions

The partial-fill benchmark's evidence collector
(`benchmark/packs/partial_fill/evidence.clj:43-72`) captures deferred
position state for forensic verification:

```clojure
{:deferred-position
 {:prior-closed?           (and prior-deferred (= :closed (:position/status prior-deferred)))
  :prior-current-amount    (:position/current-amount prior-deferred)
  :successor-current-amount (:position/current-amount deferred)
  :final-world-current-amount (get-in final-world-position [:deferred-position :position/current-amount])
  :verified?               (and deferred
                                (= (:position/current-amount deferred)
                                   (get-in final-world-position
                                           [:deferred-position :position/current-amount])))}}
```

This enables an independent reviewer to verify that deferred position
amounts in the evidence match the final world state.

#### 3.6.12 Invariants for Lineage Consistency

The yield invariants (`src/resolver_sim/yield/invariants.clj`) enforce
lineage correctness at multiple levels:

**Closed position history** (`closed-history-violations`, line 390-420):
```clojure
:closed-position-history-valid
  Checks: Every consumed deferred position has an immutable history record
          with matching ID, status (:closed), root-obligation-id, token,
          participant-id, and closed-by-propagation-id.
  Error types: :closed-position-history-missing
               :closed-position-history-identity-mismatch
               :closed-position-history-closure-mismatch
```

**Deferred position state** (`deferred-state-violations`, line 548-580):
```clojure
:deferred-position-presence-valid
  Checks: A position with deferred > 0 must have an active deferred-position.
  Errors: :deferred-position-missing, :fulfilled-position-still-active

:deferred-position-amounts-valid
  Checks: Active deferred position amount matches propagation's deferred value.
  Errors: :deferred-position-amount-mismatch

:deferred-position-identities-valid
  Checks: Token, root-obligation-id, and origin-propagation-id match.
  Errors: :deferred-position-token-mismatch
          :deferred-position-root-obligation-mismatch
          :deferred-position-origin-mismatch
```

**Policy compliance** (`deferred-position-policy-valid`, line 855-856):
```clojure
:deferred-position-policy-valid
  Checks: The deferred position's type and eligibility match the
          committed propagation policy.
  Errors: :deferred-position-policy-mismatch
```

#### 3.6.13 Error Code Reference

| Error Code | Source | Stage | Meaning |
|-----------|--------|-------|---------|
| `:original-priority-lineage-mismatch` | `liquid_lending.clj:74` | Priority resolution | Base priority and deferred priority contradict each other |
| `:missing-or-invalid-original-priority` | `liquid_lending.clj:80` | Priority resolution | No valid priority found (both base and deferred nil) |
| `:original-priority-precondition-mismatch` | `liquid_lending.clj:685` | Propagation application | Priority changed between calculation and application |
| `:withdrawal-obligation-lineage-mismatch` | `liquid_lending.clj:693` | Propagation application | Obligation chain inconsistent across precondition/participant/current state |
| `:source-position-precondition-mismatch` | `liquid_lending.clj:701` | Propagation application | Source position ID differs from committed precondition |
| `:eligible-obligation-precondition-mismatch` | `liquid_lending.clj:709` | Propagation application | Eligible obligation differs from committed precondition |
| `:deferred-amount-precondition-mismatch` | `liquid_lending.clj:716` | Propagation application | Deferred position amount differs from eligible obligation |
| `:participant-obligation-does-not-reconcile` | `liquid_lending.clj:728` | Propagation application | Fulfilled+deferred+unmet+waived != eligible-obligation |
| `:yield-position-id-conflict` | `liquid_lending.clj:165` | Deposit | Position ID already exists (prevents overwriting lineage) |
| `:deferred-position-history-conflict` | `liquid_lending.clj:452` | Deferred closure | Attempted to close an already-closed deferred position with different data |
| `:deferred-position-missing` | `invariants.clj:569` | Invariant | Propagation deferred > 0 but no active deferred position in world |
| `:fulfilled-position-still-active` | `invariants.clj:579` | Invariant | Propagation deferred = 0 but position still has active deferred |
| `:deferred-position-amount-mismatch` | `invariants.clj:571` | Invariant | Deferred position amount doesn't match propagation |

#### 3.6.14 Interaction with Escrow Settlement Finalization

The lineage-original system is invoked during the `finalize` step of escrow
settlement (`lifecycle.clj:165-287`).  When an escrow with yield backing
is finalized:

```
finalize (lifecycle.clj)
  │
  ├── accrue-yield                    ← accrues yield to current time
  ├── yield-ops/apply-yield-op        ← withdraws yield from module
  ├── yield-policy/apply-yield-policy ← applies yield distribution
  │
  ├── acct/sub-held                   ← debits total-held
  │     └── adjust-held               ← force-auth? → provenance check
  │                                      normal → plain settlement
  │
  └── Settlement may trigger:
        └── yield-module/withdraw-shared  ← if shared-liquidity pool
              │
              ├── Pro-rata allocation using :original-priority FIFO ordering
              ├── If shortfall → deferred position created:
              │     :position/original-priority = <inherited from base>
              │     :position/original-priority-source = :from-precondition
              │     :position/round = next-lineage-round
              │     :position/parent-id = <prior deferred or base>
              │     :position/parent-hash = <cryptographic commitment>
              │     :position/created-by-transition-hash = transition-commitment
              │
              └── If no shortfall → full settlement, position withdrawn
```

The FIFO ordering by `:original-priority` during `withdraw-shared` means
that when multiple escrows share a yield module's liquidity and a shortfall
occurs, the oldest depositor (lowest `:original-priority`) gets served
first.  If their withdrawal cannot be fully satisfied, the deferred
portion carries their priority forward to the next liquidity event.

#### 3.6.15 Scenario Coverage

| Scenario ID | Test File | What It Tests |
|-------------|-----------|---------------|
| (priority tests) | `test/.../priority_by_original_time_test.clj` | Original-priority assignment, deferred inheritance, FIFO ordering in shared withdrawals |
| (pro-rata accounting) | `test/.../pro_rata_accounting_test.clj` | Deferred position lifecycle, lineage classification, closed-history invariants |
| (canonical hash) | `test/.../canonical_test.clj` | Golden hash test for provenance-lineage intent |

#### 3.6.16 Source Map

| File | Lines | Content |
|------|-------|---------|
| `src/resolver_sim/yield/position.clj` | 34-41, 58, 101 | `:original-priority` field definition and default |
| `src/resolver_sim/yield/modules/liquid_lending.clj` | 62-81 | `authoritative-original-priority` resolution function |
| `src/resolver_sim/yield/modules/liquid_lending.clj` | 147-203 | `deposit` function — priority assignment at deposit |
| `src/resolver_sim/yield/modules/liquid_lending.clj` | 437-455 | `record-closed-deferred-position` — immutable history |
| `src/resolver_sim/yield/modules/liquid_lending.clj` | 495-507 | `next-lineage-round` — round number computation |
| `src/resolver_sim/yield/modules/liquid_lending.clj` | 660-733 | Propagation precondition validation (9 checks) |
| `src/resolver_sim/yield/modules/liquid_lending.clj` | 790-802 | `transition-commitment` — cryptographic seal |
| `src/resolver_sim/yield/modules/liquid_lending.clj` | 893-998 | Deferred position creation with lineage inheritance |
| `src/resolver_sim/yield/pro_rata_propagation_policy.clj` | 1-28 | `:preserve-original` policy declaration |
| `src/resolver_sim/yield/invariants.clj` | 390-420 | `closed-history-violations` invariant |
| `src/resolver_sim/yield/invariants.clj` | 548-580 | `deferred-state-violations` invariant |
| `src/resolver_sim/yield/invariants.clj` | 852-856 | Deferred position invariant registrations |
| `src/resolver_sim/benchmark/packs/partial_fill/evidence.clj` | 43-72 | Deferred position evidence capture |
| `src/resolver_sim/run/verdict_policy.clj` | 177 | Lineage field validation in artifacts |
| `src/resolver_sim/hash/canonical.clj` | 1128-1135 | `:provenance` intent with `provenance-lineage` |

---

## 4. Settlement Deadline Enforcement

Settlement deadline enforcement operates at two layers.  The PRF replay engine
evaluates temporal rules *before* dispatching any action — these are generic,
protocol-agnostic checks.  The Sew protocol logic enforces deadline guards
*within* each action handler, providing protocol-specific error semantics.
Together they form a defence-in-depth: the PRF layer catches timing violations
at the event boundary, and the Sew layer catches any remaining violations
during state-machine transition.

### 4.1 PRF Layer: TemporalDeadlines Protocol

The PRF defines a protocol for deadline lookup in
`src/resolver_sim/protocols/protocol.clj:66`:

```clojure
(defprotocol TemporalDeadlines
  "Optional interface for deadline-driven temporal rule enforcement."
  (deadline-for [model world deadline-kind subject context]
    "Return the deadline timestamp (integer) for deadline-kind and subject,
     or nil if no deadline applies (action allowed).
     deadline-kind is a keyword such as :evidence-submission, :settlement,
     or :appeal. subject is protocol-specific (e.g., a workflow-id).
     context is the opaque execution context from build-execution-context."))
```

The replay engine does not know about specific deadline semantics — it simply
asks the protocol adapter for a deadline timestamp via `deadline-for`, then
evaluates the boundary policy.  This keeps the PRF engine protocol-agnostic
and the deadline logic in the Sew adapter.

Three invariants are always checked before any deadline enforcement:
1. **`:missing-event-time`** — every event must carry a `:time` field (numeric
   or `java.time.Instant`).  Without it, no temporal enforcement is possible.
2. **`:non-regressive-time`** — `event-time` must not be less than
   `block-time` (no time travel).  Enforced as `(< event-ts now)`.
3. **`:deadline-enforcement`** — the action-level deadline check.

All three are evaluated in order.  The first failure short-circuits.

### 4.2 Sew Implementation: deadline-for

The Sew adapter (`protocols_src/resolver_sim/protocols/Sew.clj:1748`) implements
`deadline-for` with four deadline kinds:

```clojure
(deadline-for [_ world deadline-kind subject _context]
  (let [wf-id subject]
    (case deadline-kind
      :evidence-submission
      (let [snap       (t/get-snapshot world wf-id)
            window-dur (:evidence-window-duration snap 0)]
        (when (pos? window-dur)
          (let [dispute-ts (get-in world [:dispute-timestamps wf-id] 0)]
            (+ dispute-ts window-dur))))
      :settlement
      (let [pending (t/get-pending world wf-id)]
        (if (:exists pending)
          (:appeal-deadline pending)
          (let [current-level (t/dispute-level world wf-id)]
            (some (fn [entry]
                    (when (= (:level entry) current-level)
                      (:appeal-deadline (:pending entry))))
                  (get-in world [:superseded-pending-settlements wf-id] [])))))
      :appeal
      (let [pending (t/get-pending world wf-id)]
        (when (:exists pending)
          (:appeal-deadline pending)))
      :earliest-execution
      (let [pending (get-in world [:pending-fraud-slashes subject])]
        (when pending
          (:appeal-deadline pending)))
      nil)))
```

| Deadline Kind | Subject | Returns | Used By | Nil Means |
|---------------|---------|---------|---------|----------|
| `:evidence-submission` | workflow-id | `dispute-timestamp + evidence-window-duration` | `submit_evidence` | No deadline (window=0) |
| `:settlement` | workflow-id | Active pending `:appeal-deadline`, or superseded pending at current level | `execute_pending_settlement` | No pending at all |
| `:appeal` | workflow-id | Active pending `:appeal-deadline` | `escalate_dispute`, `challenge_resolution` | No pending exists |
| `:earliest-execution` | slash-id / workflow-id | `world[:pending-fraud-slashes][subject][:appeal-deadline]` | `execute_fraud_slash`, `execute_fraud_group_slash` | No slash found |

Key nuance for `:settlement` fallback: the superseded pending lookup via
`some` stops at the *first* entry at the current dispute level.  Because
`superseded-pending-settlements` is a list ordered by superseding time
(most recent last), and `some` iterates from oldest to newest, an older
superseded pending could be returned.  However, the `pick-eligible-superseded-pending`
function in the action handler (`resolution.clj:827`) sorts by
`:appeal-deadline` and picks the *latest* deadline, ensuring the most
permissive (latest-expiring) settlement window is used.

### 4.3 PRF Replay Engine: Temporal Rules

The replay engine (`src/resolver_sim/contract_model/replay/temporal.clj:32`) defines a
`deadline-action-config` mapping each time-gated action to its deadline kind
and boundary policy:

```clojure
(def ^:private deadline-action-config
  {"submit_evidence"            {:kind :evidence-submission :boundary :before
                                 :subject #(get-in % [:params :workflow-id])
                                 :on-expired :evidence-deadline-exceeded}
   "execute_pending_settlement" {:kind :settlement :boundary :at-or-after
                                 :subject #(get-in % [:params :workflow-id])
                                 :on-expired :appeal-window-not-expired}
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
```

The `:deadline-enforcement` rule evaluates each action:

```clojure
{:id :deadline-enforcement
 :check (fn [{:keys [event context protocol world event-time]}]
          (if-let [cfg (get deadline-action-config (:action event))]
            (let [subject  ((:subject cfg) event)
                  deadline (when (satisfies? proto/TemporalDeadlines protocol)
                             (proto/deadline-for protocol world (:kind cfg) subject context))]
              (if (nil? deadline)
                {:ok? true}  ;; no deadline configured → allowed
                (let [boundary  (:boundary cfg)
                      expired? (case boundary
                                 :before     (>= (long event-time) (long deadline))
                                 :at-or-after (< (long event-time) (long deadline)))]
                  (if expired?
                    {:ok? false
                     :error    (:on-expired cfg)
                     :guard-context {:temporal/rule :deadline-enforcement
                                     :temporal/deadline-kind (:kind cfg)
                                     :temporal/event-time event-time
                                     :temporal/deadline deadline
                                     :temporal/boundary-policy boundary
                                     :temporal/subject-id subject
                                     :temporal/decision :reject}}
                    {:ok? true}))))
            {:ok? true}))}
```

| Action | Deadline Kind | Boundary | Condition for Allow | On Violation |
|--------|---------------|----------|---------------------|-------------|
| `submit_evidence` | `:evidence-submission` | `:before` | `event-time < deadline` | `:evidence-deadline-exceeded` |
| `execute_pending_settlement` | `:settlement` | `:at-or-after` | `event-time >= deadline` | `:appeal-window-not-expired` |
| `escalate_dispute` | `:appeal` | `:before` | `event-time < deadline` | `:appeal-window-expired` |
| `challenge_resolution` | `:appeal` | `:before` | `event-time < deadline` | `:appeal-window-expired` |
| `execute_fraud_slash` | `:earliest-execution` | `:at-or-after` | `event-time >= deadline` | `:timelock-not-expired` |
| `execute_fraud_group_slash` | `:earliest-execution` | `:at-or-after` | `event-time >= deadline` | `:timelock-not-expired` |

**Boundary policies in detail:**
- `:before` — the predicate tests `expired? = (event-time >= deadline)`.
  The action is allowed *only* when the event timestamp is strictly less
  than the deadline.  At exactly the deadline, the action is rejected.
- `:at-or-after` — the predicate tests `expired? = (event-time < deadline)`.
  The action is allowed *only* when the event timestamp is at or past the
  deadline.  Before the deadline, the action is rejected with
  `:appeal-window-not-expired` / `:timelock-not-expired`.

These two boundary policies are duals: `:before` blocks at-and-after,
`:at-or-after` blocks before.  Together they create a sharp cutoff at the
deadline timestamp where *neither* side can act at exactly the wrong
moment — but one side always can.

### 4.4 Complete Enforcement Flow in process-step

The full processing pipeline for a single event is defined in
`src/resolver_sim/contract_model/replay/execution.clj:288`:

```
event = {:seq n, :time <ts>, :agent <id>, :action <str>, :params <map>}

process-step(protocol, context, world, event):
│
├── 1. Validate event structure
│     └── flags ← (:replay-flags context), temporal-on? ← defaults true
│
├── 2. Evaluate temporal rules (ALL before time advance)
│     │
│     ├── rules ← effective-temporal-rules(context)
│     │     = [:missing-event-time, :non-regressive-time, :deadline-enforcement]
│     │
│     ├── evaluate-temporal-rules(rules, {event-time, now, world, event, protocol})
│     │     │
│     │     ├── Rule 1: :missing-event-time
│     │     │     Checks: event-time is number? or Instant?
│     │     │     Fail → {:ok? false :error :invalid-event-time}
│     │     │
│     │     ├── Rule 2: :non-regressive-time
│     │     │     Checks: (< event-time block-time)?
│     │     │     Fail → {:ok? false :error :time-regression}
│     │     │
│     │     └── Rule 3: :deadline-enforcement
│     │           (only for actions in deadline-action-config)
│     │           Checks: deadline-for + boundary policy
│     │           Fail → {:ok? false :error :appeal-window-not-expired
│     │                    (or :appeal-window-expired
│     │                     :evidence-deadline-exceeded
│     │                     :timelock-not-expired)}
│     │
│     └── IF any rule fails → SHORT CIRCUIT
│           Result: {:ok? true                    ← engine did not throw
│                    :trace-entry {:result :rejected
│                                  :error  <temporal-failure error>
│                                  :temporal-rule-id :deadline-enforcement
│                                  :guard-context {...}
│                                  :invariant-phase :temporal-rule}}
│           World: UNCHANGED (no time advance, no dispatch)
│
├── 3. IF all rules pass → advance time
│     │
│     ├── advance-world-time(world, event-time)
│     │     Reads: block-ts from world
│     │     Computes: delta-seconds = event-ts - block-ts
│     │     Updates: :context/time {:block-ts event-ts, :step +1,
│     │               :event-seq (0 if time-advanced?, otherwise event-seq+1)}
│     │     Returns: {:world world' :delta-ms <ms> :advanced? bool}
│     │
│     └── world-t ← world'
│
├── 4. Dispatch action
│     │
│     ├── apply-action-with-evidence(protocol, context, world-t, event)
│     │     │
│     │     ├── proto/dispatch-action(protocol, context, world-t, event)
│     │     │     └── → Sew adapter → resolution.clj / lifecycle.clj
│     │     │           (includes protocol-level deadline guards)
│     │     │
│     │     ├── IF invariants-on? → check-invariants-single + transition
│     │     │
│     │     └── Captures: decision evidence, event evidence, projections
│     │
│     └── Result: {:ok? bool, :world world-next, :trace-entry {...}}
│
└── 5. Return complete step result
      └── {:ok? <final>, :world <final>, :trace-entry <full metadata>}
```

This ordering is critical:
- **Temporal rules run on the current world state** before time is advanced.
  They use the current `block-time` for `:non-regressive-time` and the
  current world for `deadline-for`.
- **If any temporal rule fails, the world is untouched.**  The trace entry
  records the rejection with the temporal rule ID and guard context.
- **Only if all temporal rules pass does time advance.**  `advance-world-time`
  sets `:block-ts` to `event-time` before dispatch, so the action handler
  sees the event's time as "now".
- **Protocol guards run after time advance** under the new `block-ts`.

This means the temporal rule layer acts as a gatekeeper: it rejects actions
that are clearly outside their time window without ever touching the protocol
state machine.  The protocol guards provide a second layer of defence for
race conditions and internal keeper operations.

### 4.5 Sew Layer: Time Model and Clock Semantics

The Protocol Robustness Framework uses a discrete-step time model defined in
`src/resolver_sim/time/context.clj`:

```clojure
;; Temporal context stored in world[:context/time]:
{:schema-version "temporal-context.v2"
 :step        <integer>    ; monotonic scenario step counter
 :event-seq   <integer>    ; events within the same block (resets on time advance)
 :block-ts    <integer>    ; current simulation time (Unix seconds)
 :instant     <Instant>    ; java.time.Instant derived from block-ts
 :clock/source :discrete-step
 :tick-seconds <integer>}  ; seconds-per-tick (default 86400 = 1 day)
```

**Time advance rules:**
- Same-timestamp events (where `event-time == block-time`) advance the
  logical step and event-sequence counters without moving `block-ts`:
  ```clojure
  (defn advance-world-time [world event-time]
    (let [now-ts       (time-ctx/block-ts world)
          event-ts     (long event-time)
          delta-seconds (- event-ts now-ts)
          world'       (time-ctx/advance-time world {:to event-ts})]
      {:world     world'
       :delta-ms  (max 0 (* delta-seconds 1000))
       :advanced? (pos? delta-seconds)}))
  ```
- When time advances (`delta-seconds > 0`), `:event-seq` resets to 0.
- When time does not advance (`delta-seconds == 0`), `:event-seq`
  increments — allowing multiple actions in the same block.
- The `:step` counter always increments regardless of time advance.

**Canonical constants:**
- `seconds-per-day` = 86400 (used for day-based duration computation)
- `seconds-per-year` = 31536000
- `tick-seconds` = 86400 (the default tick rate for keeper-interval parameters)

### 4.6 Deadline Helper Functions

All deadline arithmetic goes through `src/resolver_sim/time/deadlines.clj`:

```clojure
(defn deadline
  "Compute absolute deadline from start-ts + duration-seconds."
  [start-ts duration-seconds]
  (+ (long start-ts) (long duration-seconds)))

(defn before-deadline?
  "True when now-ts is strictly before deadline-ts (window still open)."
  [now-ts deadline-ts]
  (< (long now-ts) (long deadline-ts)))

(defn deadline-expired?
  "True when now-ts >= deadline-ts (window closed; action is executable).
   This is the standard protocol predicate: at-or-after means expired."
  [now-ts deadline-ts]
  (>= (long now-ts) (long deadline-ts)))

(defn at-deadline?
  "True when now-ts is exactly at deadline-ts."
  [now-ts deadline-ts]
  (= (long now-ts) (long deadline-ts)))

(defn deadline-passed?
  "True when now-ts is strictly after deadline-ts.
   Prefer deadline-expired? for protocol enforcement (uses >=, not >)."
  [now-ts deadline-ts]
  (> (long now-ts) (long deadline-ts)))

(defn boundary-times
  "Return canonical boundary probes around a deadline: t-1, t, t+1."
  [deadline-ts]
  {:t-1 (dec (long deadline-ts))
   :t   (long deadline-ts)
   :t+1 (inc (long deadline-ts))})
```

**Function selection by use case:**

| Function | Relation | Used Where |
|----------|----------|------------|
| `deadline` | `start + duration` | Computing absolute deadlines from durations |
| `before-deadline?` | `<` | Escalation/challenge window checks (window still open) |
| `deadline-expired?` | `>=` | Settlement execution, auto-release/auto-cancel, dispute timeout (standard protocol predicate) |
| `deadline-passed?` | `>` | Evidence window enforcement (strict exclusivity) |
| `at-deadline?` | `==` | Boundary probing in scenario generation |
| `boundary-times` | `{t-1 t t+1}` | Generating probe timestamps for deadline edge cases |

The `boundary-times` function is used by the scenario generation tooling to
create scenario variants that test each side of every deadline boundary:
- At `t-1`: the window is open, action succeeds (if `:before` boundary)
- At `t`: the window is at the exact cutoff — one side succeeds, the other fails
- At `t+1`: the window is closed, the other action succeeds

### 4.7 State Machine Deadline Predicates

The Sew state machine (`protocols_src/resolver_sim/protocols/Sew/state_machine.clj:399`)
defines five deadline guard predicates with precise short-circuit ordering
to prevent nil-pointer crashes on invalid workflow-ids:

```clojure
;; Shared helper — short-circuits on state check before timestamp access
(defn- deadline-due?
  [world workflow-id field]
  (let [et  (t/get-transfer world workflow-id)
        ts  (get et field)]
    (and (= :pending (:escrow-state et))    ;; ← checked first: nil-safe
         (pos? ts)
         (dl/deadline-expired? (time-ctx/block-ts world) ts))))
```

| Predicate | Lines | Pseudocode | Semantics |
|-----------|-------|------------|-----------|
| `auto-release-due?` | 421-424 | `state=pending AND auto-release-time>0 AND now >= auto-release-time` | Escrow auto-releasable at deadline |
| `auto-cancel-due?` | 426-429 | `state=pending AND auto-cancel-time>0 AND now >= auto-cancel-time` | Escrow auto-cancellable at deadline |
| `auto-cancel-due-on-disputed?` | 431-452 | `state=disputed AND auto-cancel-time>0 AND now >= auto-cancel-time AND no pending-settlement` | Griefing protection: auto-cancel a disputed escrow that has past its cancel deadline |
| `dispute-timeout-exceeded?` | 454-467 | `state=disputed AND no pending AND dispute-ts>0 AND max-dur>0 AND now >= dispute-ts+max-dur` | Dispute liveness timeout |
| `pending-settlement-executable?` | 469-487 | `state=disputed AND (pending exists AND now >= appeal-deadline OR eligible superseded at current level)` | Settlement ready to execute |

**Internal guard-ordering rationale:**

In `deadline-due?`, the `(= :pending (:escrow-state et))` check comes before
`(pos? ts)`.  This is intentional: if the workflow-id is invalid and
`t/get-transfer` returns `nil`, then `(= nil :pending)` is `false` and the
`(pos? nil)` call is never reached.  Same for `dispute-timeout-exceeded?`:
`(pos? ts)` and `(pos? max-dur)` prevent division-by-zero or nil-pointer
on uninitialized timestamp fields.

**`pending-settlement-executable?` in full:**

```clojure
(defn pending-settlement-executable?
  [world workflow-id]
  (let [pending       (t/get-pending world workflow-id)
        state         (t/escrow-state world workflow-id)
        now-ts        (time-ctx/block-ts world)
        current-level (t/dispute-level world workflow-id)]
    (and (= :disputed state)
         (if (:exists pending)
           (dl/deadline-expired? now-ts (:appeal-deadline pending))
           (some #(and (= (:level %) current-level)
                       (dl/deadline-expired? now-ts (:appeal-deadline (:pending %))))
                 (get-in world [:superseded-pending-settlements workflow-id] []))))))
```

The `if` branch covers the common case: active pending settlement whose
appeal deadline has passed.  The `else` branch (via `some`) iterates over
superseded pendings and returns true if *any* entry at the current dispute
level has an expired appeal deadline.  This enables the fallback execution
path (see 4.11).

### 4.8 Two-Layer Enforcement Interaction

The PRF temporal rules and Sew guards are redundant by design, but they
differ in important ways:

| Aspect | PRF Temporal Rule Layer | Sew Action Guard Layer |
|--------|------------------------|------------------------|
| **When evaluated** | Before time advance, on current world state | After time advance, on world-t (world with event-time as block-ts) |
| **Evaluated by** | `evaluate-temporal-rules` in `temporal.clj` | `dispatch-action` → action handler in `resolution.clj` / `lifecycle.clj` |
| **Protocol awareness** | Generic — only knows `deadline-action-config` | Full protocol state, pending settlements, dispute levels |
| **Deadline source** | `proto/deadline-for` (generic adapter method) | Direct world-state reads (`t/get-pending`, `t/get-snapshot`, etc.) |
| **Scope** | Only 6 settlement/fraud actions | All actions including keeper internal checks |
| **Traced as** | `{:result :rejected :invariant-phase :temporal-rule}` | `{:result :rejected :error <kw>}` (from action handler) |
| **World mutation** | None — world is read-only | None for rejections — returns original world |
| **Error keyword** | Configurable per action in `deadline-action-config` | Hard-coded in each action handler |

**Example: premature `execute_pending_settlement` at `block-time < deadline`:**

```
PRF layer catches it:
  - event-time < deadline
  - boundary = :at-or-after
  - expired? = true (event-time < deadline)
  → {:ok? false :error :appeal-window-not-expired}
  → trace: {:result :rejected, :invariant-phase :temporal-rule}
  → world unchanged, action never reaches handler

Sew layer would catch it if PRF layer is disabled:
  - Guards: state = :disputed ✓, pending exists ✓
  - (< now-ts (:appeal-deadline pending)) → true
  → (guard-fail :appeal-window-not-expired)
  → trace: {:result :rejected, :error :appeal-window-not-expired}
```

**Example: `execute_pending_settlement` on a terminal escrow:**

```
PRF layer cannot catch this:
  - deadline-for returns a deadline (pending may still exist in state)
  - boundary passes (event-time >= deadline)
  → {:ok? true} → passes through

Sew layer catches it:
  - Guards: (not= :disputed (t/escrow-state world workflow-id))
  → (guard-fail :transfer-not-in-dispute)
```

### 4.9 Superseded Pending Fallback Path

When a pending settlement is archived on escalation or challenge, it is
preserved in `world[:superseded-pending-settlements][workflow-id]` with
metadata.  This creates a fallback execution path:

**Archival** (`resolution.clj:807`):
```clojure
(defn- archive-pending-on-escalation
  [world workflow-id]
  (let [pending (t/get-pending world workflow-id)]
    (if (:exists pending)
      (-> world
          ;; Clear stale principal from claimable
          (clear-stale-settlement-principal workflow-id)
          ;; Append to superseded list (capped at 5)
          (update-in [:superseded-pending-settlements workflow-id]
                     (fnil conj [])
                     {:pending pending
                      :superseded-at (time-ctx/block-ts world)
                      :level (t/dispute-level world workflow-id)})
          ;; Cap retained entries
          (update-in [:superseded-pending-settlements workflow-id]
                     (fn [v] (take-last max-superseded-pending-per-workflow v)))
          ;; Clear active pending
          (update :pending-settlements dissoc workflow-id))
      world)))
```

**Selection** (`resolution.clj:827`):
```clojure
(defn- pick-eligible-superseded-pending
  "Select the latest superseded pending that is executable at now-ts."
  [world workflow-id now-ts]
  (->> (get-in world [:superseded-pending-settlements workflow-id] [])
       (map :pending)
       (filter :exists)
       (filter #(<= (:appeal-deadline %) now-ts))
       (sort-by :appeal-deadline)
       last))
```

The selection pipeline:
1. Get all superseded entries for the workflow
2. Extract the `:pending` maps
3. Filter out entries with `:exists false` (should not occur, but defensive)
4. Filter to entries whose `appeal-deadline` has passed (`<= now-ts`)
5. Sort by `appeal-deadline` ascending
6. Take the `last` (latest deadline — largest timestamp)

This ensures the superseded pending with the *longest* (latest-expiring)
appeal window is selected, giving the most permissive execution window.
If multiple superseded pendings exist (from repeated escalation cycles),
the latest-expiring one governs.

**Level filtering in `pending-settlement-executable?`:**
The state machine predicate adds an extra level check that
`pick-eligible-superseded-pending` does not: it filters by *current*
dispute level.  This means a superseded pending from level 0 is *not*
eligible for execution when the escrow is at level 1.  The rationale:
an escalation to level 1 intentionally overrode the level-0 decision,
so the level-0 decision should not be executable without a replacement.

**Combined eligibility logic:**

```
execute_pending_settlement eligibility:
  1. Active pending exists AND now >= appeal-deadline
     → EXECUTE (active path)
  2. Active pending exists AND now < appeal-deadline
     → REJECT :appeal-window-not-expired
  3. No active pending, superseded pending at current level exists AND now >= appeal-deadline
     → EXECUTE (superseded fallback path)
  4. No active pending, superseded pendings exist but at DIFFERENT level
     → REJECT :no-pending-settlement
  5. No pending at all (active or superseded)
     → REJECT :no-pending-settlement
```

### 4.10 Deadline Enforcement for Keeper Actions

The `automate-timed-actions` keeper (`resolution.clj:960`) does not go through
the PRF temporal rule layer — it is a protocol-internal action that replays
the keeper function.  Instead, it evaluates the deadline predicates directly:

```clojure
(defn automate-timed-actions
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
        ;; Solidity shadow: ACTION_AUTO_CANCEL_DISPUTED (SettlementOps.sol computeTimedActions)
        (sm/auto-cancel-due-on-disputed? world workflow-id)
        (let [r (lc/auto-cancel-disputed-on-auto-time world workflow-id)]
          (if (:ok r)
            (assoc r :action :auto-cancel-disputed-auto-time)
            r))

        ;; Priority 3: dispute liveness timeout
        (sm/dispute-timeout-exceeded? world workflow-id)
        (let [r (lc/auto-cancel-disputed-escrow world workflow-id)]
          (if (:ok r)
            (assoc r :action :auto-cancel-disputed)
            r))

        ;; Priority 4: auto-release
        (sm/auto-release-due? world workflow-id)
        (let [r (t/ok (lc/finalize-escrow-accounting world workflow-id :released))]
          (assoc r :action :auto-release))

        ;; Priority 5: auto-cancel
        (sm/auto-cancel-due? world workflow-id)
        (let [r (t/ok (lc/finalize-escrow-accounting world workflow-id :refunded))]
          (assoc r :action :auto-cancel))

        :else
        (assoc (t/ok world) :action :none)))))
```

**Dispatch priority rationale:**

| Priority | Action | Why This Order |
|----------|--------|----------------|
| 1 | `execute-pending-settlement` | A resolver's decision is the most authoritative action — it must be honoured before any auto-mechanism overrides it. |
| 2 | `auto-cancel-disputed-on-auto-time` | Griefing protection: a dispute raised just before auto-cancel-time should not orphan the deadline. This is the simulation-side fix for a known Solidity gap. |
| 3 | `auto-cancel-disputed-escrow` | Dispute liveness: if no resolution arrives within `max-dispute-duration`, the system auto-refunds and slashes the resolver. |
| 4 | `auto-release` | Non-disputed timed release (simplest case). |
| 5 | `auto-cancel` | Non-disputed timed cancel (simplest case). |

**Griefing vector prevented by priority 2:**

Without `auto-cancel-due-on-disputed?`, consider this attack:
1. Escrow created with `auto-release-time = T+7` and `auto-cancel-time = T+14`.
2. At `T+6`, adversary raises a frivolous dispute.
3. State transitions from `:pending` to `:disputed`.
4. At `T+7`, `auto-release-due?` returns false (state is `:disputed`, not `:pending`).
5. The legitimate auto-release at `T+7` never fires.
6. Escrow is locked until `max-dispute-duration` (which may be 30+ days).

Priority 2 closes this gap: it checks `auto-cancel-time` even when the
escrow is `:disputed`, as long as no pending settlement exists.

### 4.11 Evidence Window Deadline Enforcement

The evidence window enforces that evidence cannot be submitted indefinitely
after a dispute is raised.

**PRF temporal rule** (`temporal.clj:33`):
```clojure
"submit_evidence" {:kind :evidence-submission :boundary :before
                    :subject #(get-in % [:params :workflow-id])
                    :on-expired :evidence-deadline-exceeded}
```
The `:before` boundary rejects when `event-time >= deadline`.

**Sew action handler** (`resolution.clj:418`):
```clojure
(defn submit-evidence
  [world workflow-id _caller & [{:keys [evidence-hash]}]]
  (cond
    (not (t/valid-workflow-id? world workflow-id)) (t/fail :invalid-workflow-id)
    (not= :disputed (t/escrow-state world workflow-id)) (t/fail :transfer-not-in-dispute)
    :else
    (let [snap       (t/get-snapshot world workflow-id)
          window-dur (:evidence-window-duration snap 0)
          now        (time-ctx/block-ts world)
          dispute-ts (get-in world [:dispute-timestamps workflow-id] 0)]
      (if (and (pos? window-dur) (> now (+ dispute-ts window-dur)))
        (t/fail :evidence-deadline-exceeded)
        (let [world' (... record evidence ...)]
          (t/ok world'))))))
```

**Deadline computation:**
```
evidence-deadline = dispute-timestamp + evidence-window-duration
```

The `(> now deadline)` check uses strict greater-than — the deadline
boundary is exclusive.  At exactly the deadline, evidence is still
accepted.

**Why strict `>` vs `>=`:** The PRF temporal rule uses `:before` boundary
which rejects at `event-time >= deadline`.  If the PRF layer is enabled,
the PRF catches the `==` case and `submit-evidence` never reaches the Sew
handler.  If the PRF layer is disabled (e.g., direct `dispatch-action`
call from within another action), the Sew handler uses `>` as a
second-line defence, accepting evidence exactly at the deadline.

**Evidence window invariant** (`invariants/dispute.clj:198`):
```clojure
(defn evidence-deadline-enforced?
  [world]
  (let [deadline-duration (get-in world [:params :evidence-window-duration] nil)]
    (if (nil? deadline-duration)
      {:holds? true :violations [] :note "No evidence deadline configured"}
      (let [violations
            (for [[wf et] (:escrow-transfers world)
                  :when (= :disputed (:escrow-state et))
                  :let [dispute-ts (get-in world [:dispute-timestamps wf] 0)
                        deadline (when (pos? dispute-ts)
                                   (+ dispute-ts deadline-duration))
                        evidence-events (filter ...)
                        late-submissions (filter #(> (:time %) deadline) evidence-events)]
                  :when (seq late-submissions)]
              {:workflow-id wf :deadline deadline
               :late-submissions (mapv #(select-keys % [:seq :time :agent]) late-submissions)
               :violation :late-evidence-submission})]
        {:holds? (empty? violations)
         :violations (vec violations)}))))
```

This invariant:
- Reads `:evidence-window-duration` from `world[:params]` (set in scenario definition).
- Skips when nil (no evidence deadline configured).
- For each `:disputed` escrow, gathers all `submit-evidence` events from the trace and checks if any have `:time > deadline`.
- Reports each late submission with its seq, time, and agent.

### 4.12 Fraud Slash Timelock Enforcement

Fraud slash proposals have their own appeal window, independent of the
escrow's settlement appeal window.  This creates a separate timelock that
must expire before the slash can be executed.

**Deadline creation** (`resolution.clj:1389` and `resolution.clj:1537`):

In `propose-fraud-slash`:
```clojure
snap              (t/get-snapshot world wf-id)
appeal-days       (get-in world [:params :appeal-window-days] 7)
gov-delay         (or (:appeal-window-duration snap)
                       (* appeal-days (time-ctx/tick-seconds world)))
...
world' (handle-fraud-slashing world slash-id wf-id resolver-addr amount
                               gov-delay reversal-prob ...)
```

In `handle-fraud-slashing` (`resolution.clj:529`):
```clojure
:appeal-deadline (+ now appeal-window)
```

In `propose-fraud-group-slash`:
```clojure
appeal-window (or (:appeal-window-duration snap)
                  (* (get-in world [:params :appeal-window-days] 7)
                     (time-ctx/tick-seconds world)))
...
:appeal-deadline (+ now appeal-window)
```

The window resolution chain:
1. Use `:appeal-window-duration` from ModuleSnapshot (per-workflow setting)
2. If nil/zero, fall back to `:appeal-window-days` from `world[:params]`
   times `tick-seconds` (default 7 days * 86400 = 604800 seconds)
3. If still nil, default to 0 (immediate execution)

**PRF temporal rule** (`temporal.clj:45`):
```clojure
"execute_fraud_slash"       {:kind :earliest-execution :boundary :at-or-after
                              :subject #(or (get-in % [:params :slash-id])
                                            (get-in % [:params :workflow-id]))
                              :on-expired :timelock-not-expired}
"execute_fraud_group_slash" {:kind :earliest-execution :boundary :at-or-after
                              :subject #(or (get-in % [:params :slash-id])
                                            (get-in % [:params :workflow-id]))
                              :on-expired :timelock-not-expired}
```

**Sew action handler guard** (`resolution.clj:1193`, `resolution.clj:1692`):

For `execute-fraud-slash`:
```clojure
;; The deadline belongs to the resolver's appeal window.  Execution is
;; permitted strictly after it so same-timestamp appeal and execution
;; cannot be ordered to defeat a timely appeal.
(<= (time-ctx/block-ts world) (:appeal-deadline pending))
(t/fail :timelock-not-expired)
```

For `execute-fraud-group-slash`:
```clojure
(<= (time-ctx/block-ts world) (:appeal-deadline pending))
(t/fail :timelock-not-expired)
```

**Asymmetry with settlement deadlines:**

| Mechanism | Deadline Guard | At `time == deadline` |
|-----------|---------------|----------------------|
| `execute-pending-settlement` | `<` → reject | **Allowed** (not blocked) |
| `execute-fraud-slash` | `<=` → reject | **Blocked** (must be strictly after) |
| `execute-fraud-group-slash` | `<=` → reject | **Blocked** (must be strictly after) |

Rationale for the asymmetry:
- **Settlement**: A resolver's settlement decision should execute as soon as
  the window closes.  At `block-time == deadline`, the window is closed and
  the settlement is allowed.
- **Fraud slash**: The slash target (a resolver) should have the full appeal
  window to contest the slash.  At `block-time == deadline`, the window is
  considered still open for the resolver's appeal.  Execution is only
  allowed strictly after the deadline (`>`, not `>=`).
- **PRF temporal layer**: uses the `:at-or-after` boundary which rejects
  at `event-time < deadline` — meaning at `event-time == deadline` it
  passes.  The fraud slash handler then applies the stricter `<=` check
  as a second guard.

### 4.13 Escalation and Challenge Window Guards

Both `escalate-dispute` and `challenge-resolution` require a pending
settlement to exist and the appeal window to be open:

**Action-level guard** (`resolution.clj:1077` for escalation):
```clojure
(>= (time-ctx/block-ts world) (:appeal-deadline (t/get-pending world workflow-id)))
(guard-fail :appeal-window-expired)
```

**Action-level guard** (`resolution.clj:871` for challenge):
```clojure
(>= (time-ctx/block-ts world) (:appeal-deadline (t/get-pending world workflow-id)))
(guard-fail :appeal-window-expired)
```

**Combined guard logic for escalates/challenge:**

| Condition | `escalate-dispute` | `challenge-resolution` | `execute-pending-settlement` |
|-----------|-------------------|----------------------|------------------------------|
| No pending exists | `:no-resolution-to-appeal` | `:no-resolution-to-challenge` | `:no-pending-settlement` |
| `now < deadline` | Allowed | Allowed | `:appeal-window-not-expired` |
| `now == deadline` | `:appeal-window-expired` | `:appeal-window-expired` | Allowed |
| `now > deadline` | `:appeal-window-expired` | `:appeal-window-expired` | Allowed |

**`available-actions` filtering** (`Sew.clj:1656`):

The `available-actions` function (used by reinforcement-learning agents and
trace analysis) pre-filters actions based on deadline state:

```clojure
;; Escalation/Challenge only offered while appeal window open
(and (:exists pending) (< (time-ctx/block-ts world) (:appeal-deadline pending)))
(into (cond->
        (or (= actor (:from et)) (= actor (:to et)))
        (conj {:action "escalate-dispute" :params {:workflow-id wf}})
        true
        (conj {:action "challenge-resolution" :params {:workflow-id wf}})))

;; Resolver actions suppressed when resolver is frozen
(not (and (= actor (:dispute-resolver et))
          (> (get-in world [:resolver-frozen-until actor] 0) (time-ctx/block-ts world))))
```

This filtering is advisory (for agents and tooling) — the action handlers
still enforce their own guards.  But it prevents agents from wasting
simulation steps on actions that would be rejected.

### 4.14 Per-Address Escalation Cooldown and Sybil Mitigation

Challenge resolution implements two layers of Sybil mitigation in
`resolution.clj:844`:

**Layer A — Cooldown tracking:**
```clojure
;; Track last escalation timestamp per address (used by
;; challenge-resolution cooldown for open challengers).
(assoc-in [:last-escalation-block-time-per-addr caller]
          (time-ctx/block-ts world))
```

This records each address's last escalation time.  The invariant
`time-lock-integrity?` (`invariants.clj:1006`) then checks:

```clojure
(defn time-lock-integrity?
  [world-before world-after]
  (let [bt-after (or (time-ctx/block-ts world-after) 0)]
    (if (zero? bt-after)
      {:holds? true :violations []}
      (let [violations
            (for [[wf level-after] (:dispute-levels world-after)
                  :let [level-before (t/dispute-level world-before wf)]
                  :when (> level-after level-before)
                  :let [prev-esc-bt (get-in world-before [:last-escalation-block-time wf])]
                  :when (and (some? prev-esc-bt) (= prev-esc-bt bt-after))]
              {:workflow-id wf :level-before level-before
               :level-after level-after :block-time bt-after})]
        {:holds?     (empty? violations)
         :violations (vec violations)}))))
```

This checks that no workflow experiences two escalations in the same block.
The transition invariant compares `world-before`'s
`:last-escalation-block-time` with `world-after`'s `block-ts`.  If they
match, a second escalation occurred in the same block.

Note: this is a *transition* invariant (checked on `world-before → world-after`
pairs), not a single-world invariant.  It detects the specific pattern where
two events in the same block both call `escalate-dispute` or
`challenge-resolution` on the same workflow.

**Layer B — Bond cost scaling:**
```clojure
esc-count    (get-in world [:escalation-counts-per-addr caller] 0)
base-bond    (Sew-econ/calculate-challenge-bond-amount (:amount-after-fee et) snap)
bond-amt     (quot (* base-bond (+ 10000 (* esc-count 1000))) 10000)
```

The bond cost increases by 10% for each previous escalation by the same
address.  After 10 escalations, the bond cost is 200% of base.  After 100
escalations, it is 1100% of base — making repeated attacks economically
prohibitive.

**Additional escalation guards:**

The `escalate-dispute` handler also enforces (`resolution.clj:1051`):
- Caller must be `:from` or `:to` (participant-only escalation).
- Not in final round (`t/final-round?`).
- A pending settlement must exist (escalation is an *appeal of a decision*,
  not a pre-emptive level-skip without a ruling).
- The appeal window must be open (`now < appeal-deadline`).

The `challenge-resolution` handler (`resolution.clj:844`) differs:
- Any address can challenge (not just participants).
- Same pending-existence and window-open requirements.
- Plus a challenge bond is posted and bonded.

### 4.15 Deadline-Related Invariants

Five invariants enforce deadline correctness at the simulation level:

| Invariant | File:Line | Type | Checks |
|-----------|-----------|------|--------|
| `evidence-deadline-enforced?` | `dispute.clj:198` | Single-world | No evidence after `dispute-ts + window` |
| `finality-blocked-during-appeal?` | `dispute.clj:237` | Single-world | No settlement before window closed |
| `no-stale-automatable-escrows?` | `invariants.clj:496` | Single-world | No escrow with unexecuted deadline |
| `pending-settlement-consistent?` | `settlement.clj:5` | Single-world | Pending only on `:disputed` escrows |
| `time-lock-integrity?` | `invariants.clj:1006` | Transition | No double-escalation in same block |

**`finality-blocked-during-appeal?`** (`dispute.clj:237`):
```clojure
(defn finality-blocked-during-appeal?
  [world]
  (let [violations
        (for [[wf et] (:escrow-transfers world)
              :when (contains? t/terminal-states (:escrow-state et))
              :let [res (:resolution et)
                    snap (t/get-snapshot world wf)
                    appeal-window (get snap :appeal-window-duration 0)
                    resolution-time (when res (:time res))
                    dispute-time (...)
                    window-close (when (and resolution-time appeal-window (pos? appeal-window))
                                   (+ resolution-time appeal-window))
                    actual-settle-time (:time et)
                    premature? (and window-close actual-settle-time
                                    (< actual-settle-time window-close))]
              :when premature?]
          {:workflow-id wf :window-close window-close
           :settle-time actual-settle-time :appeal-window appeal-window})]
    {:holds? (empty? violations) :violations (vec violations)}))
```

This checks every terminal escrow's `:settle-time` against the
expected `window-close` (`resolution-time + appeal-window-duration`).
If `settle-time < window-close`, the settlement was premature — a
violation of the finality-during-appeal invariant.

**`no-stale-automatable-escrows?`** (`invariants.clj:496`):
```clojure
(defn no-stale-automatable-escrows?
  [world]
  (let [violations
        (for [[wf _et] (:escrow-transfers world)
              :when (or (sm/auto-release-due?             world wf)
                        (sm/auto-cancel-due?              world wf)
                        (sm/pending-settlement-executable? world wf))]
          {:workflow-id wf
           :reasons (cond-> []
                      (sm/auto-release-due? world wf)              (conj :auto-release-due)
                      (sm/auto-cancel-due? world wf)               (conj :auto-cancel-due)
                      (sm/pending-settlement-executable? world wf) (conj :pending-executable))})]
    {:holds? (empty? violations) :violations (vec violations)}))
```

This invariant is designed to be checked on a world snapshot *after*
`automate-timed-actions` has been called for every active escrow.
A violation means the caller (or scenario) failed to invoke the keeper
when a timed action was due.  It combines all three deadline predicates:

- `auto-release-due?` — release deadline passed on `:pending` escrow
- `auto-cancel-due?` — cancel deadline passed on `:pending` escrow
- `pending-settlement-executable?` — settlement deadline on `:disputed` escrow

### 4.16 Resolution Transition Window Selection

When a resolver calls `execute-resolution`, the window duration for the
pending settlement is determined by (`resolution.clj:676`):

```clojure
window-dur (max (:appeal-window-duration snap 0)
                 (:challenge-window-duration snap 0))
```

The full decision tree in `apply-resolution-transition`:

```
execute-resolution:
  │
  ├── Compute window-dur = max(appeal-window-duration, challenge-window-duration)
  │
  ├── If final-round? OR (not (pos? window-dur)):
  │     └── IMMEDIATE PATH:
  │           finalize(world, workflow-id, :released or :refunded)
  │           → state transitions to terminal
  │           → no pending settlement created
  │           → no appeal window
  │
  └── Else (window-dur > 0 AND not final-round):
        └── DEFERRED PATH:
              Create PendingSettlement:
                {:exists          true
                 :is-release      <resolver's decision>
                 :appeal-deadline (+ now window-dur)
                 :resolution-hash <resolver-provided hash>}
              → state remains :disputed
              → appeal window opens for window-dur seconds
```

Two code paths lead to finalization:

**Path A — Immediate (window=0 or final round):**
```clojure
(if is-release
  (finalize world''' workflow-id :released
            :authorization-provenance authorization-provenance)
  (finalize world''' workflow-id :refunded
            :authorization-provenance authorization-provenance))
```

**Path B — Deferred (window>0 and not final):**
```clojure
(let [pending (t/make-pending-settlement
               {:exists          true
                :is-release      is-release
                :appeal-deadline (+ now window-dur)
                :resolution-hash resolution-hash})
      world'''' (assoc-in world''' [:pending-settlements workflow-id] pending)]
  (t/ok world''''))
```

The `finalize` function (`lifecycle.clj:165`) performs:
1. Yield accrual and withdrawal
2. Debit from `:total-held` (`sub-held`)
3. Credit to `:claimable-v2` ledger
4. State machine transition to terminal state
5. Cleanup orphaned slashes

### 4.17 ModuleSnapshot Deadline Parameter Configuration

All deadline parameters are frozen per-workflow at `create_escrow` time in
the ModuleSnapshot (`snapshot.clj:55`):

```clojure
(defn make-escrow-snapshot
  [{:keys [resolution-module release-strategy cancellation-strategy
           yield-generation-module yield-distribution-module incentive-module
           yield-module-id yield-profile yield-archetype escrow-modules
           yield-protocol-fee-bps appeal-bond-protocol-fee-bps escrow-fee-bps
           default-auto-release-delay default-auto-cancel-delay
           max-dispute-duration appeal-window-duration dispute-resolver
           appeal-bond-bps resolver-bond-bps appeal-bond-amount
           reversal-slash-bps reversal-detection-probability
           challenge-window-duration challenge-bond-bps challenge-bounty-bps
           evidence-window-duration]}]
  {... ;; deadline fields:
   :default-auto-release-delay       (or default-auto-release-delay 0)
   :default-auto-cancel-delay        (or default-auto-cancel-delay 0)
   :max-dispute-duration             (or max-dispute-duration 0)
   :appeal-window-duration           (or appeal-window-duration 0)
   :evidence-window-duration         (or evidence-window-duration 0)
   :challenge-window-duration        (or challenge-window-duration 0)
   ...})
```

| Parameter | Default | Computation | Affects |
|-----------|---------|-------------|---------|
| `:appeal-window-duration` | 0 (immediate) | Set directly in seconds | Length of appeal window after `execute-resolution` |
| `:challenge-window-duration` | 0 | Aliased to appeal window via `max()` | Window for third-party challenges (Phase L) |
| `:evidence-window-duration` | 0 (no deadline) | Added to `dispute-timestamp` | Cutoff for `submit-evidence` |
| `:max-dispute-duration` | 0 (no timeout) | Added to `dispute-timestamp` | Keeper auto-cancel if no resolution in time |
| `:default-auto-release-delay` | 0 | Added to creation-time for `:auto-release-time` | Timed release for non-disputed escrows |
| `:default-auto-cancel-delay` | 0 | Added to creation-time for `:auto-cancel-time` | Timed cancel for non-disputed escrows |

A value of 0 for any duration parameter disables the corresponding deadline
mechanism (except `challenge-window-duration`, where 0 falls through to
`appeal-window-duration` via `max()`).

### 4.18 Scenario Coverage for Deadline Enforcement

Each deadline mechanism is exercised by specific scenarios:

| Deadline Mechanism | Scenario(s) | What It Tests |
|-------------------|-------------|---------------|
| Settlement at deadline edge | s47a, S57 | `execute-pending-settlement` at exactly `appeal-deadline` |
| Escalation before settlement | s46a | Escalate just before settlement window opens |
| Settlement before escalation | s46b | Settle just before escalation window closes |
| Premature settlement rejected | S32 | `:appeal-window-not-expired` during open window |
| Pending with Kleros escalation | S21 | Pending cleared on escalation, superseded fallback |
| Fraud slash timelock | (via fraud slash scenarios) | `:timelock-not-expired` before appeal window |
| Appeal window expiry race | DR-B-001 | Settlement and escalation at same deadline boundary |
| Finality blocked during appeal | S-DR-040, S-DR-041 | Settlement rejected/approved relative to appeal window |
| Superseded pending regression | S116 | Superseded pending eligibility after repeated keepers |
| Cooldown boundary | s66 | Same-block double-escalation rejected |
| Forced overflow + pending | S118, S119 | Overflow resolution interaction with pending settlement |
| Evidence after settlement | S-DR-095 | Evidence rejected after premature settlement attempt |

---



## 5. Deadline Semantics

| Parameter | Source | Semantics |
|-----------|--------|-----------|
| `appeal-window-duration` | ModuleSnapshot | Seconds after resolution during which window is open |
| `appeal-deadline` | PendingSettlement | `resolution-time + window-duration`; settlement executable at `block-time >= deadline` |
| `max-dispute-duration` | ModuleSnapshot | Max seconds after `raise_dispute` before keeper auto-cancels |
| `evidence-window-duration` | ModuleSnapshot | Window for evidence submission after dispute raised |
| `auto-release-time` | EscrowTransfer | Absolute timestamp for keeper auto-release |
| `auto-cancel-time` | EscrowTransfer | Absolute timestamp for keeper auto-cancel |
| `challenge-window-duration` | ModuleSnapshot | Alternative to `appeal-window-duration` for Phase L |

**Asymmetry** (by design):
- Settlement at deadline uses `>=`: at `block-time == deadline`, settlement
  succeeds and escalation/challenge fails.
- Escalation/challenge use strict `<`: at `block-time == deadline`, the window
  has closed for appeals but opened for settlement.

```
Timeline:

  raise_dispute     execute_resolution         appeal-deadline
       |                   |                       |
       ▼                   ▼                       ▼
       ├───────────────────├───────────────────────├──────────►
       :disputed           ├── appeal window ──────┤
                           │  (escalate/challenge)  │
                           │  (no settlement exec)  │
                                                    ├── settlement executable ──►
                                                    │  (execute-pending-settlement)
```

---

## 5. Key Data Structures

### 5.1 PendingSettlement

```
{:exists           boolean    ; true when a deferred decision exists
 :is-release       boolean    ; true → release to recipient, false → refund to sender
 :appeal-deadline  integer    ; block timestamp after which settlement may execute
 :resolution-hash  string     ; bytes32 hex (opaque in model)}
```

Zero value: `{:exists false :is-release false :appeal-deadline 0 :resolution-hash nil}`

### 5.2 Superseded Pending Entry

```
{:pending    PendingSettlement   ; the archived settlement
 :superseded-at  integer         ; block time of escalation/challenge
 :level          integer         ; dispute level at time of superseding}
```

### 5.3 EscrowTransfer (Settlement-relevant subset)

```
{:token             keyword   ; token identifier
 :from              string    ; sender address
 :to                string    ; recipient address
 :amount-after-fee  integer   ; net amount held
 :dispute-resolver  string    ; current resolver address (or nil)
 :escrow-state      keyword   ; one of the six states
 :auto-release-time integer   ; 0 = disabled
 :auto-cancel-time  integer   ; 0 = disabled
 :last-accrual-time integer   ; for yield accounting
 :resolution        map       ; resolution metadata (resolved-by, is-release, etc.)}
```

### 5.4 World State (Settlement-relevant fields)

```
{:escrow-transfers              {workflow-id EscrowTransfer}
 :pending-settlements           {workflow-id PendingSettlement}
 :superseded-pending-settlements {workflow-id [SupersededPendingEntry]}
 :total-held                    {token integer}
 :claimable-v2                  {workflow-id {:settlement/principal {addr amount}
                                              :settlement/yield {addr amount}}}
 :amount-released               {workflow-id integer}
 :dispute-timestamps            {workflow-id integer}
 :dispute-levels                {workflow-id integer}   ; 0–2
 :module-snapshots              {workflow-id ModuleSnapshot}
 :escrow-settings               {workflow-id EscrowSettings}}
```

---

## 6. Settlement Invariants

Invariants checked at every PRF simulation step relevant to settlement:

| ID | Description | Severity |
|----|-------------|----------|
| `:pending-settlement-consistent` | Pending exists only when escrow is `:disputed` | Error |
| `:settlement-principal-boundary` | Principal claimable amounts respect accounting boundaries | Error |
| `:settlement-yield-boundary` | Yield claimable amounts respect accounting boundaries | Error |
| `:no-stale-pending-settlements` | No pending settlement past deadline without execution | Warning |
| `:no-double-settlement` | Each workflow finalizes at most once | Error |
| `:terminal-states-unchanged` | Terminal states are absorbing (no transitions out) | Error |
| `:conservation-of-funds` | Funds in = funds out + funds held | Error |
| `:solvency` | `total-held[t]` = sum of live escrow AFAs | Error |
| `:escalation-clears-pending` | Escalation always archives the pending settlement | Error |

---

## 7. Scenario Coverage

The lifecycle is tested across scenarios in the following categories:

| Category | Scenarios | What they cover |
|----------|-----------|-----------------|
| Baseline settlement | S05, S13 | Honest resolution, pending settlement execute |
| Deadline edge cases | s46a, s46b, s47a, S57 | Settlement/escalation race at window boundary |
| Kleros pending | S21 | Pending cleared on escalation, fallback settlement |
| Premature rejection | S32 | Settlement during open window rejected |
| Fraud/races | S34 | Pending window raced by fraudulent resolver |
| DR (dispute resolution) | DR-B-001, DR-E-001, DR-M-002 | Expiry races, rotation blocked during pending, no double settle |
| Finality | S-DR-040, S-DR-041 | Settlement rejected/approved relative to appeal window |
| Superseded pending | S116 | Superseded pending regression, repeated keeper calls |
| Evidence | S-DR-095 | Evidence rejected after settlement attempt |

---

## 8. Source Map

| File | Content |
|------|---------|
| `protocols_src/.../Sew/types.clj` | PendingSettlement, EscrowTransfer, world state, state machine graph |
| `protocols_src/.../Sew/state_machine.clj` | State transition functions, deadline predicates (`pending-settlement-executable?`, `auto-release-due?`, `auto-cancel-due?`, `dispute-timeout-exceeded?`, `auto-cancel-due-on-disputed?`) |
| `protocols_src/.../Sew/resolution.clj` | `execute-resolution`, `execute-pending-settlement`, `escalate-dispute`, `challenge-resolution`, `automate-timed-actions` |
| `protocols_src/.../Sew/lifecycle.clj` | `create-escrow`, `release`, `cancel`, `finalize`, `auto-cancel-disputed` |
| `protocols_src/.../Sew/invariants.clj` | All Sew invariants including settlement-specific |
| `protocols_src/.../Sew/invariants/settlement.clj` | Settlement-specific invariant predicates |
| `protocols_src/.../Sew.clj` | Sew adapter `TemporalDeadlines` implementation (`deadline-for` with `:evidence-submission`, `:settlement`, `:appeal`, `:earliest-execution`) |
| `src/resolver_sim/protocols/protocol.clj` | PRF interfaces (`SimulationAdapter`, `TemporalDeadlines`, `EconomicModel`, `AnalysisModule`) |
| `src/resolver_sim/time/deadlines.clj` | Deadline arithmetic helpers (`deadline-expired?`, `before-deadline?`, `deadline`, `boundary-times`) |
| `src/resolver_sim/contract_model/replay/temporal.clj` | `:deadline-enforcement` temporal rule, `deadline-action-config` mapping, boundary policy evaluation |
| `src/resolver_sim/contract_model/replay/execution.clj` | `process-step` — temporal rule evaluation before action dispatch |
| `scenarios/` | EDN and JSON scenario definitions for all lifecycle paths |
