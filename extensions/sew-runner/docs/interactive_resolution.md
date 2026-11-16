# Interactive Resolution REPL Driver

## Start a REPL

```bash
clojure -M:dev:nrepl:with-sew
```

Or run a one-shot REPL command:

```bash
clojure -M:with-sew
```

## Load the Namespace

```clojure
(require '[resolver-sim.repl.interactive-resolution :as ir])
(require '[resolver-sim.protocols.sew.types :as t])
```

## Quick Start: Dispute Lifecycle Walkthrough

Define a fixture with a buyer, seller, and a two-level resolver setup (DR3 +
Kleros-style escalation):

```clojure
(def fixture
  {:initial-block-time 1000
   :agents             [{:id "buyer"       :address "0xbuyer"       :strategy "honest"}
                        {:id "seller"      :address "0xseller"      :strategy "honest"}
                        {:id "resolver"    :address "0xresolver"    :role "resolver"}
                        {:id "l1-resolver" :address "0xl1-resolver" :role "resolver"}]
   :protocol-params
   {:resolver-fee-bps 150
    :appeal-window-duration 120
    :max-dispute-duration 2592000
    :dispute-resolver "0xresolver"
    :escalation-resolvers {"0" "0xresolver" "1" "0xl1-resolver"}}})
```

### 1. Start a Session

Create an escrow and raise a dispute to get to the `:disputed` state:

```clojure
(def initial-events
  [{:seq 0 :time 1000 :agent "buyer"  :action "create-escrow"
    :params {:token "USDC" :to "0xseller" :amount 5000}}
   {:seq 1 :time 1060 :agent "buyer"  :action "raise-dispute"
    :params {:workflow-id 0}}])

(def session (ir/start-session fixture initial-events))

;; Inspect world state
(:world session)
(t/escrow-state (:world session) 0)
;; => :disputed
```

### 2. See Available Choices

```clojure
(ir/available-choices session)
;; =>
;; ({:action "execute-resolution", :params {:workflow-id 0, :is-release true, :resolution-hash "0xrelease"},
;;   :actor "resolver", :summary "resolver: execute-resolution {:workflow-id 0, :is-release true, ...}", :type :agent, :n 0}
;;  {:action "execute-resolution", :params {:workflow-id 0, :is-release false, :resolution-hash "0xrefund"},
;;   :actor "resolver", :summary "resolver: execute-resolution {:workflow-id 0, :is-release false, ...}", :type :agent, :n 1})
```

### 3. Pick a Choice

```clojure
(def session (ir/pick session 0))
;; Step 2: execute-resolution (resolver) -> ok
;;   wf-0: :disputed -> :disputed/pending
```

### 4. Escalate (Appeal)

```clojure
(ir/available-choices session)
;; n=0 for buyer's escalate-dispute (first agent choice after keeper actions)

(def session (ir/pick session 0))
;; Step 3: escalate-dispute (buyer) -> ok
;;   wf-0: :disputed/pending -> :disputed (level 0 -> 1)
```

### 5. Second Resolver Decides (Direct apply)

Some paths aren't exposed by `available-actions` (e.g. resolver resolution
while a pending exists). Use `ir/apply-event` for those:

```clojure
(def session (ir/apply-event session
               {:action "execute-resolution"
                :params {:workflow-id 0 :is-release true
                         :resolution-hash "0xl1release"}
                :agent "l1-resolver"}))
;; Step 4: execute-resolution (l1-resolver) -> ok
;;   wf-0: :disputed -> :disputed/pending
```

### 6. Advance Time

```clojure
(def session (ir/advance-time session 1500))
;; time: 1000 -> 1500
```

### 7. Auto-Process Keeper Actions

```clojure
(def session (ir/auto-until-decision session))
;; auto: keeper: execute-pending on wf-0
;; Step 5: automate-timed-actions -> ok
;;   wf-0: :disputed/pending -> :released
;; auto: no keeper actions pending

;; Verify terminal state
(t/terminal-state? (:world session) 0)
;; => true
```

### 8. Export as Scenario File

```clojure
(ir/export-session session "results/my-exploration.edn")
;; Exported 6 steps to results/my-exploration.edn
```

Replay the exported file through the normal runner:

```bash
clojure -M:with-sew -m resolver-sim.io.scenario-runner \\
  --scenario results/my-exploration.edn
```

## Replay an Existing Scenario Interactively

```clojure
;; Load an existing scenario
(def scenario (clojure.edn/read-string (slurp "scenarios/edn/S01_baseline-happy-path.edn")))

;; Start session — initial events auto-apply from the scenario's :events
(def session (ir/start-session scenario))

;; Available choices (if any) will be after the last event applied
(ir/available-choices session)
```

## Start from a Specific World State

To start from a custom world (e.g. one produced by a previous replay), pass
a fixture without events and use `apply-event` manually:

```clojure
(def session (ir/start-session fixture))  ;; no initial events
```

## Session Data Structure

```clojure
{:world         <current world state>
 :context       <execution context>
 :protocol      <SewProtocol instance>
 :agents        [{:id "..." :address "..." :strategy "..."}]
 :protocol-params {...}
 :steps         [{:seq N :action "..." :params {...} :actor "..." :time int :result :applied}]
 :trace         [{:ok? bool :world <world'> :error kw ...}]}
```

## Liveness Analysis

```clojure
(ir/liveness-summary session)
;; Workflow 0 -- disputed / pending exists
;;   Paths to termination:
;;     resolve         yes (0xresolver)
;;     resolution-mod   no
;;     timeout         yes at t=2593000
;;     pending-settle  yes (appeal deadline t=1120)
;;   Next keeper action: execute-pending at t=1120 (in 120s)
```

The `liveness-summary` function checks every workflow and reports its termination
paths, the next keeper deadline, and whether the escrow is stuck (no termination
mechanism). The `available-choices` output also shows a `!` suffix when a workflow
is stuck, and a deadline preview line when keeper actions are pending but not yet
due.

## Governance Override (Liveness Safety Valve)

### Problem

When the designated resolver is unavailable — over capacity, frozen, or opted out
— a `:disputed` escrow can become stuck with no actor authorized to resolve it.
The timeout path (`auto-cancel-disputed-escrow`) eventually fires, but only after
`max-dispute-duration` has elapsed (default 30 days). For liveness-critical
disputes, this is too long.

### Mechanism

A governance agent can override resolver authorization through `apply-event`
or the explicit `force-authorized` helper:

```clojure
(ir/apply-event session
  {:action "execute-resolution"
   :params {:workflow-id 0 :is-release true}
   :agent "buyer"}
  {:governed-by "resolver"})
;; [GOV] resolver overrides resolver authorization
;; Step 2: execute-resolution (buyer) [gov: 0xresolver] -> ok
```

The override works by patching the resolution-module slot in the workflow's
module snapshot and providing a resolution-module function that always returns
`{:authorized? true}`. The current implementation records a forced-authorization
envelope and executes the resolution transition directly so the replay trace
shows an exceptional path instead of looking like a normal resolver decision.
The normal `authorized-resolver?` priority chain is:
1. `custom-resolver` in escrow settings (exclusive)
2. resolution-module from snapshot (delegates to module function)
3. `et.dispute-resolver` on the escrow transfer

The override intercepts at priority 2 by injecting a module that authorizes
any caller, regardless of priority 1 or 3.

### Governance controls

| Control | Implementation |
|---|---|
| **Agent must exist** | `resolve-gov-agent` checks the named agent is in the session's agent list. Unknown agents are rejected. |
| **Agent is recorded** | The exported step stores `:forensic/governed-by` plus `:forensic/authorization` with machine-readable provenance. |
| **Console audit trail** | `[GOV] resolver overrides resolver authorization` and `[gov: 0xresolver]` in the step line. |
| **No accidental use** | Only available through `apply-event`, not `pick`/`available-choices`. The researcher must explicitly construct the event and name the governance agent. |
| **Resolver availability is advisory** | The `resolver-unavailable-reason` check runs and prints why the override was needed (overcapacity, frozen, opted out, circuit breaker), but does not gate the override. The governance agent's approval *is* the authorization. |

### Solidity considerations

The model mirrors what a production `ResolutionModule` needs on-chain. A Solidity
implementation should:

1. **Governance role** — a `TIMELOCK` or `DAO` address stored in the module's
   constructor or an upgradable config. Only this address can call the override
   function.

2. **Override function** — `governanceExecuteResolution(workflowId, isRelease, resolutionHash)`.
   Bypasses `_isAuthorizedDisputeResolver` for the governance caller by checking
   `msg.sender == governance` before the normal authorization chain.

3. **Module snapshot routing** — the override does NOT modify the escrow's
   `disputeResolver` or `customResolver`. Instead it follows the existing
   resolution-module slot: if `moduleSnapshot.resolutionModule` is set, the
   module function is called. The governance override can either:
   - Set a temporary module that authorizes the governance address, or
   - Add a `governance-resolution-module` parallel slot that takes priority
     over the normal module when set.

4. **Event** — emit `GovernanceOverride(workflowId, governance, resolver, timestamp)`
   so off-chain indexers can detect and flag overrides.

5. **Frequency limit** — optional: a per-workflow or per-epoch cap on override
   calls (e.g., at most 1 per workflow) to prevent governance from permanently
   replacing the resolver system.

## Function Reference

| Function | Args | Description |
|---|---|---|
| `start-session` | fixture, [initial-events] | Create session, optionally apply initial events |
| `available-choices` | session | Numbered list of available actions |
| `pick` | session, n | Apply a numbered choice, print state diff |
| `apply-event` | session, event | Direct action application (bypasses choice menu) |
| `auto-until-decision` | session | Run keeper/timed actions until none remain |
| `advance-time` | session, target-ts | Advance world block time |
| `export-session` | session, path | Write session steps as replayable EDN scenario |
