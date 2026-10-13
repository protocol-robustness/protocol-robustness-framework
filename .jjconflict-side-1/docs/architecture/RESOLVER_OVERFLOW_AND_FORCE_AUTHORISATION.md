# Over-Capacity Capability Lifecycle and Force-Authorisation

> **Scope note — implementation status.** This document is the **target contract** for
> the over-capacity failover path: a coherent *capability lifecycle*, not a set of
> isolated checks. The current Sew code still exposes the earlier record-based overflow
> (`authorized-overflow-resolver?` in
> `protocols_src/resolver_sim/protocols/sew/authority.clj`, `activate-resolver-overflow` /
> `execute-overflow-resolution` in `protocols_src/resolver_sim/protocols/sew.clj`).
> Migration to the lifecycle described here is in progress. Where the code differs, this
> document describes the intended design and the worked example uses the target model.

---

## 0. The central model

```
verified force-authorisation ──▶ durable overflow capability ──▶ bounded executions
                                          │
                                          ▼
                          exhaustion │ expiry │ revocation
```

Over-capacity mode is **not** "a governance action that flips a flag". It is a
content-addressed, integrity-protected **capability** that is *minted from a
consumed force-authorisation* and then *spent within hard bounds*:

1. **Verified force-authorisation** — governance grants a `:capacity-failover`
   class force-authorisation binding the primary resolver, the exact failover set,
   the requested workflow cap, the requested window, and the policy identity.
2. **Durable overflow capability** — governance activates the overflow by
   referencing that authorisation. Activation *verifies* the grant through the same
   shared machinery as other force-authorised actions, *derives* the capacity
   context from live world state, *atomically consumes* the grant, and mints the
   capability record. The consumed grant is a **one-time** act; the capability is the
   durable, bounded, multi-use authorization.
3. **Bounded executions** — a listed failover resolver resolves a workflow *only if*
   the capability verifies end-to-end (integrity, binding, scope, window, cap,
   policy, state).
4. **Exhaustion, expiry, or revocation** — the capability terminates when its
   workflow cap is spent, its time window closes, or governance revokes it. There is
   no transition out of a terminal state.

---

## 1. How force-authorisation authorises over-capacity

The force-authorisation machinery is the **source of authority**; the overflow
capability is the **delegated, policy-bounded artifact** it produces.

### 1.1 A `:capacity-failover` grant

A normal force-authorisation is workflow-scoped and amount-bound (a single held
custody movement). An over-capacity grant is a different *scope-kind*:

```clojure
{:authorization/id          "fa-0"
 :authorization/type        :force-authorisation
 :authorization/class       :capacity-failover        ; ← delegation class
 :authorization/scope-kind  :capacity-failover         ; ← not a held-adjustment scope
 :authorization/status      :active
 :reason                    :resolver-overcapacity
 :allowed-action            "activate-resolver-overflow"
 :authorization/scope       {:authorization/id          "fa-0"
                             :authorization/type        :force-authorisation
                             :authorization/class       :capacity-failover
                             :authorization/scope-kind  :capacity-failover
                             :overflow/resolver         "0xResolver"
                             :overflow/failover-resolvers #{"0xFailover"}
                             :overflow/max-workflows    2
                             :overflow/expires-at       4685
                             :overflow/capacity-policy-id "overflow-policy.v1"}
 :authorization/scope-hash  <domain-hash over scope>
 :starts-at                 1085
 :expires-at                4685
 :created-by                "0xGov"
 :nonce                     "fa-0"
 :consumed?                 false
 :authorization/provenance  {governance envelope,
                             :authorization/class :capacity-failover,
                             :authorization/assurance :address-bound}}
```

The grant commits the **request** — who is authorized (failover set), over which
primary, how many workflows, for how long, under which policy — via a scope-hash.
Because it is `:capacity-failover` (not `:interactive-override`), it is never a
single specific intervention: it is a template that activation converts into a
bounded capability.

### 1.2 Grant → activate → consume

Activation does **not** trust a caller-supplied reason or capacity claim:

| Step | Check |
|---|---|
| Policy gate | Overflow policy `:enabled?` must be `true`; reason must be in `:allowed-reasons` (`:resolver-overcapacity`); failover set must be non-empty. |
| Grant existence & class | Grant record exists, `:authorization/class` is `:capacity-failover`, scope-kind is `:capacity-failover`. |
| Grant status | `:active`, `:consumed? false`, not present in `:force-authorisations/consumed`. |
| Grant window | `now` within `[starts-at, expires-at)`. |
| Scope binding | Recomputed `:authorization/scope-hash` equals the stored hash; `:authorization/scope` matches the grant. |
| Policy identity | The grant's committed `:overflow/capacity-policy-id` equals the policy currently committed in the execution context. |
| Governance authority | The grant carries a `:with-governance-actor` / `:governance` provenance envelope. |
| Capacity, derived | Capacity context is **derived from live world state** (`current-active`, `max-concurrent`, disputed-count, committed threshold) — the resolver must be demonstrably at capacity. |
| Policy caps | Requested duration ≤ `:max-duration`; requested workflow count ≤ `:max-workflows`. Excessive values are **rejected**, not clamped. |

On success, activation **atomically**:

1. mints the capability record (Section 2) and persists it under
   `world[:resolver-overflows overflow-id]`, and
2. consumes the grant — writing a `:force-authorisations/consumed` entry with
   `:consumption/kind :overflow-activation` and the new capability id, and setting
   the grant `:consumed? true`, `:authorization/status :consumed`.

The grant is consumed **once, at activation** — never once per overflow execution.
This is what turns a single authorization into a bounded multi-use capability.

### 1.3 Two distinct authorization classes

| Class | Meaning |
|---|---|
| `:capacity-failover` | Creates a policy-bounded, **delegated** capability (this mechanism). |
| `:interactive-override` | Authorises a **specific interactive intervention** (the REPL `force-authorised` path for `execute-resolution`). |

Both may carry the textual reason `:resolver-overcapacity`, but they are different
authorization classes with different provenance and different consumption. They are
**not** relabeled into each other.

---

## 2. Overflow capability schema

The capability is a **content-addressed record**: a canonical preimage (the whole
record minus its self-hash) and an independently recomputable domain hash. Any
direct injection or mutation of a persisted record breaks the hash and is rejected
at the verifier.

```clojure
{:overflow-id                      n                 ; world map key / short id
 :overflow-capability/version      "overflow-capability.v1"
 :overflow-capability/id           "oc-<n>"          ; stable capability id
 :overflow-capability/hash         <hex>             ; SHA-256 domain-hash over preimage

 ;; originating authorisation binding
 :authorization/id                 "fa-0"            ; consumed grant id
 :authorization/class              :capacity-failover
 :authorization/hash               <hex>             ; content hash of the consumed grant scope

 ;; delegation surface
 :resolver                         "0xResolver"      ; primary resolver
 :failover-resolvers               #{"0xFailover"}   ; exact failover set (committed)

 ;; committed workflow scope
 :workflow-scope                   #{0 1}            ; workflows disputed+assigned to primary at activation
 :workflow-scope-root              <hex>             ; committed predicate root (scope semantics + set)

 ;; derived capacity context
 :capacity-context                 {:resolver "0xResolver"
                                    :capacity-threshold :at-max-concurrent
                                    :current-active 2
                                    :max-concurrent 2
                                    :disputed-count 2
                                    :at-capacity? true}
 :capacity-context-hash            <hex>             ; domain-hash over the context above

 ;; committed policy identity
 :capacity-policy-id               "overflow-policy.v1"
 :capacity-policy-version          "v1"

 ;; bounds
 :created-at                       1090
 :issued-at                        1090
 :starts-at                        1090
 :expires-at                       4685              ; must be non-nil, numeric
 :max-workflows                    2

 ;; lifecycle state (mutable, recomputed + re-hashed on every transition)
 :used-workflows                   #{0}
 :execution-count                  1
 :status                           :active           ; :active | :exhausted | :revoked
 :reason                           :resolver-overcapacity

 ;; provenance + evidence
 :authorized-by                    "0xGov"
 :authorization/provenance         {...}             ; activation governance envelope
 :revocation                       nil               ; nil | {:reason .. :by .. :at .. :provenance ..}
 :overflow-capability/executions   [{...execution evidence...}]}
```

Every field is a canonical-safe value, so `:overflow-capability/hash` is a
deterministic `SHA-256(domain || canonical-bytes(record minus self-hash))`.
Mutation of **any** committed field — body or lifecycle state — changes the
recomputed hash and fails verification.

---

## 3. Activation — verification rules

`apply-action "activate-resolver-overflow"` (governance-gated via
`run-governance-action`):

1. **Policy authority**
   - `:resolver-overflow-policy :enabled?` must be `true`, else `:overflow-disabled`.
   - `:reason` must be in `:allowed-reasons`, else `:unauthorized-overflow-reason`.
   - failover set (params or policy) must be non-empty, else `:no-failover-resolvers`.
2. **Verified grant** — the event references `:authorization-id`; the grant must
   satisfy every check in Section 1.2 (class, status, window, scope-hash, policy
   identity, governance provenance).
3. **Derived capacity** — capacity context is computed from
   `world[:resolver-capacities resolver]` and the disputed escrows assigned to the
   resolver, under the policy's committed `:capacity-threshold`. The resolver must
   be demonstrably at capacity, else `:resolver-not-over-capacity`. A caller-forged
   capacity context cannot pass: the context is re-derived, hashed, and committed.
4. **Policy caps** — requested `max-workflows` ≤ `:max-workflows`, requested
   duration ≤ `:max-duration`; both enforced at activation (defaults may supply the
   requested values but never bypass the caps).
5. **Atomic mint + consume** — the capability is constructed through the validated
   constructor (integrity hash computed), persisted, and the grant is consumed in
   the same transition.

Emergency override when capacity *cannot* be derived is **not** a weakened
`:capacity-failover`: it must be modeled as a separate explicit authorization class
or reason. `:capacity-failover` always requires demonstrable over-capacity.

---

## 4. Execution — verification rules

`apply-action "execute-overflow-resolution"` (any resolved actor) delegates to a
detailed verifier. The checks run in order; the first failure wins and returns a
**structured failure reason**, never a blanket `:not-authorized-resolver`:

| # | Check | Failure code |
|---|---|---|
| 1 | Record has the capability schema (version, id, hash) | `:invalid-overflow-capability` |
| 2 | Recomputed content hash equals `:overflow-capability/hash` (detects direct injection and any mutation) | `:invalid-overflow-capability` |
| 3 | `:authorization/class` is `:capacity-failover` | `:wrong-authorization-class` |
| 4 | Originating grant exists, was consumed **for this capability** (consumption entry references this capability id; grant's content hash matches) | `:invalid-overflow-capability` |
| 5 | Status is `:active` | `:revoked-overflow-capability` / `:exhausted-overflow-capability` |
| 6 | `:expires-at` and `:starts-at` are present, numeric, non-nil; `now` within window (fails closed, never throws) | `:expired-overflow-capability` / `:invalid-overflow-capability` |
| 7 | Workflow's `:dispute-resolver` equals the capability's primary | `:primary-resolver-mismatch` |
| 8 | Caller is in `:failover-resolvers` | `:resolver-not-authorized` |
| 9 | Workflow is in the committed `:workflow-scope` | `:workflow-out-of-scope` |
| 10 | Workflow is `:disputed` and assigned to the primary | `:workflow-not-disputed` |
| 11 | Workflow not already in `:used-workflows` | `:workflow-already-consumed` |
| 12 | `execution-count < max-workflows` and state consistent with the committed scope | `:exhausted-overflow-capability` |
| 13 | `:capacity-policy-id` is still the policy committed at activation | `:overflow-policy-mismatch` |

A malformed numeric field (e.g. missing `:expires-at`) **fails closed** with
`:invalid-overflow-capability` rather than throwing a comparison exception.

On success, execution atomically updates `:used-workflows`, increments
`:execution-count`, transitions status to `:exhausted` when the cap is reached,
appends execution evidence, and **recomputes the content hash** before delegating to
`apply-resolution-transition` with `:resolution-source :resolver-overflow`.

Execution does **not** re-prove that the resolver is still over capacity at that
later time — it proves that the capability was **validly created from derived
capacity state** and remained active for this execution.

---

## 5. Lifecycle transition table

| From | To | Trigger | Notes |
|---|---|---|---|
| `:active` | `:exhausted` | execution reaches `max-workflows` | automatic, atomic with the spending execution |
| `:active` | `:revoked` | governance `revoke-resolver-overflow` | explicit, evidence-linked |
| `:active` | *effectively expired* | time reaches `:expires-at` | derived, not a stored transition |
| `:revoked` | — | — | terminal, no transition |
| `:exhausted` | — | — | terminal, no transition |

Transitions are validated centrally by a transition validator; any attempt to move
out of a terminal state is rejected with a precise result (`:revoked-overflow-capability` /
`:exhausted-overflow-capability`), and revocation is idempotent or returns an exact
already-terminal result.

---

## 6. Revocation

`revoke-resolver-overflow` is a governance action (via `run-governance-action`):

- validates the capability exists and is `:active`;
- records `:revocation {:reason .. :by <governance addr> :at <ts> :provenance ..}` and
  sets status `:revoked` (transition validator enforced);
- recomputes the content hash;
- emits evidence linking the governance action, capability id, prior status, new
  status, and revocation reason.

Revoking does not un-consume the originating grant — the grant was already consumed
at activation. It simply terminates the delegated capability early.

---

## 7. Replay / idempotency design

Activation, execution, and revocation are **replay-sensitive** actions with
deterministic idempotency identities:

| Action | Idempotency identity |
|---|---|
| `activate-resolver-overflow` | originating authorisation id + primary resolver |
| `execute-overflow-resolution` | capability id + workflow id |
| `revoke-resolver-overflow` | capability id + governance action identity |

A replayed **activation** returns the existing result — it does **not** mint a second
capability and does **not** advance `:next-overflow-id`. A replayed **execution**
does not consume another unit of capacity (it never relies solely on the workflow
becoming terminal). A replayed **revocation** is a no-op returning the prior result.

---

## 8. Interactive override stays distinct

The REPL `force-authorised` path (`execute-resolution` with reason
`:resolver-overcapacity`) remains an `:interactive-override`: a specific,
single-use interactive intervention, never a delegated capability. The capacity
predicate (Section 3, step 3) is extracted and shared so that both paths agree on
whether a resolver is over capacity.

---

## 9. Worked example — over-capacity failover via force-auth

### 9.1 Setup

- Governance `0xGov` (restricted mode, `:governance/identity "0xGov"`).
- Primary resolver `0xResolver` with `:max-concurrent 2`.
- Failover resolver `0xFailover`.
- Protocol params:

```clojure
{:resolver-overflow-policy {:policy/id           "overflow-policy.v1"
                            :enabled?            true
                            :allowed-reasons     #{:resolver-overcapacity}
                            :capacity-threshold  :at-max-concurrent
                            :default-duration    3600
                            :max-duration        86400
                            :default-max-workflows 2
                            :max-workflows       500
                            :failover-resolvers  #{"0xFailover"}}
 :force-authorisation-policy {:enabled?          true
                              :default-duration  3600
                              :max-duration      86400
                              :allowed-reasons   #{:resolver-overcapacity}}}
```

### 9.2 Capacity deadlock builds

| t | Event | Result |
|---|---|---|
| 1000 | buyer creates escrow `wf-0` (USDC 5000, custom resolver `0xResolver`) | pending |
| 1060 | buyer raises dispute on `wf-0` | disputed, assigned to `0xResolver`; `current-active` 0→1 |
| 1062 | buyer creates escrow `wf-1` (USDC 4000, custom resolver `0xResolver`) | pending |
| 1070 | buyer raises dispute on `wf-1` | disputed, assigned to `0xResolver`; `current-active` 1→2 (at capacity) |
| 1080 | buyer raises dispute on `wf-2` | rejected `:resolver-capacity-exceeded` — liveness deadlock on `wf-0`, `wf-1` |

### 9.3 Grant — the force-auth that authorises over-capacity

Governance grants a `:capacity-failover` force-authorisation (Section 1.1):

```clojure
{:action "grant-force-authorisation" :agent "0xGov"
 :params {:reason :resolver-overcapacity
          :authorization/class :capacity-failover
          :allowed-action "activate-resolver-overflow"
          :resolver "0xResolver"
          :failover-resolvers #{"0xFailover"}
          :max-workflows 2
          :expires-at 4685}}
```

Result: grant `fa-0` persisted as `:active` with a committed `:capacity-failover`
scope-hash and a `:with-governance-actor` / `:address-bound` provenance envelope.
`fa-0` is the **authorization** for over-capacity mode.

### 9.4 Activate — verify, derive, mint, consume

```clojure
{:action "activate-resolver-overflow" :agent "0xGov"
 :params {:authorization-id "fa-0" :resolver "0xResolver" :reason :resolver-overcapacity}}
```

Activation verifies `fa-0` (class, status, window, scope-hash, policy identity,
governance provenance), derives capacity from the live world
(`current-active 2 ≥ max-concurrent 2`, threshold `:at-max-concurrent` →
`at-capacity? true`), checks policy caps (2 ≤ 500; 3600s ≤ 86400s), commits the
workflow scope `#{0 1}`, mints capability `oc-0`, and **consumes** `fa-0` in the
same transition.

```clojure
;; world[:resolver-overflows 0]
{:overflow-id                  0
 :overflow-capability/id       "oc-0"
 :overflow-capability/hash     <hex>                    ; integrity hash
 :authorization/id             "fa-0"
 :authorization/class          :capacity-failover
 :authorization/hash           <grant scope-hash hex>
 :resolver                     "0xResolver"
 :failover-resolvers           #{"0xFailover"}
 :workflow-scope               #{0 1}
 :capacity-context             {:resolver "0xResolver" :capacity-threshold :at-max-concurrent
                                :current-active 2 :max-concurrent 2
                                :disputed-count 2 :at-capacity? true}
 :capacity-context-hash        <hex>
 :capacity-policy-id           "overflow-policy.v1"
 :capacity-policy-version      "v1"
 :created-at                   1090
 :issued-at                    1090
 :starts-at                    1090
 :expires-at                   4685
 :max-workflows                2
 :used-workflows               #{}
 :execution-count              0
 :status                       :active
 :authorized-by                "0xGov"
 :revocation                   nil
 :overflow-capability/executions []}

;; fa-0 is now consumed (once, at activation)
;;   :authorization/status :consumed, :consumed? true
;;   :force-authorisations/consumed "fa-0"
;;     {:consumption/kind :overflow-activation
;;      :overflow-capability/id "oc-0" :overflow-id 0 ...}
```

### 9.5 Execute — bounded spend

```clojure
{:action "execute-overflow-resolution" :agent "0xFailover"
 :params {:workflow-id 0 :overflow-id 0 :is-release true}}
```

The verifier (Section 4) passes all 13 checks; `used-workflows #{0}`,
`execution-count 1`, status stays `:active`; the resolution proceeds under
`:resolution-source :resolver-overflow` with a full execution-provenance block that
commits capability id/hash, grant id/hash, primary, executing failover, workflow,
committed scope, capacity-context-hash, policy id/version, status and count before
and after, reason, timestamp, and the resulting settlement/evidence root.

Replaying the same event is a `:no-op-duplicate` — no second capacity unit is
consumed. Resolving `wf-1` (also in scope) reaches `execution-count 2 = max`,
transitioning status to `:exhausted`. A third execution attempt is rejected with
`:exhausted-overflow-capability`; a `wf-2`-style workflow outside the committed
scope is rejected with `:workflow-out-of-scope`.

### 9.6 Alternative termination — revoke

```clojure
{:action "revoke-resolver-overflow" :agent "0xGov"
 :params {:overflow-id 0 :reason "primary restored"}}
```

Transition `:active → :revoked`, `:revocation` populated with reason/actor/timestamp/
provenance, evidence emitted, hash recomputed. All later executions are rejected
with `:revoked-overflow-capability`.

---

## 10. Adversarial / security properties

| Attack | Outcome |
|---|---|
| Directly inject a capability record | Fails schema check → `:invalid-overflow-capability` |
| Copy a valid record but change the class | Hash mismatch and class check → `:wrong-authorization-class` |
| Forge the authorization hash / mutate the capability body | Recomputed hash differs → `:invalid-overflow-capability` |
| Activate while the resolver is not over capacity | Derived context says `at-capacity? false` → `:resolver-not-over-capacity` |
| Forge a capacity context | Context is re-derived from world state and re-hashed → mismatch |
| Disable the policy | `:overflow-disabled` |
| Request duration/count above caps | `:overflow-duration-exceeds-max` / `:overflow-max-workflows-exceeds-max` |
| Workflow outside committed scope | `:workflow-out-of-scope` |
| Wrong primary resolver | `:primary-resolver-mismatch` |
| Actor not in failover set | `:resolver-not-authorized` |
| Nil / malformed expiry | Fails closed → `:invalid-overflow-capability` (no exception) |
| Replay activation / execution / revocation | `:no-op-duplicate`, no second record, no extra capacity spend |
| Mutate capability after one execution | Hash recomputed ≠ stored → `:invalid-overflow-capability` |

Capability records are only constructible through the validated constructor
(activation) or an explicitly named test/migration escape hatch — enforced by an
architecture/boundary test.

---

## 11. Authorization sources — comparison

| Aspect | `with-governance-actor` | Overflow capability verifier | `:capacity-failover` force-auth | `:interactive-override` |
|---|---|---|---|---|
| **Gates** | governance actions (`activate-resolver-overflow`, `revoke-resolver-overflow`, grants, …) | `execute-overflow-resolution` | `grant-force-authorisation` (scope-kind `:capacity-failover`) | REPL `force-authorised` |
| **Who is authorized** | governance actor per mode | listed failover resolver | governance grant | any resolved actor, governance-approved |
| **What it authorizes** | creating/altering governance state | one bounded resolution under a capability | the creation of a bounded delegated capability | one specific resolution intervention |
| **Provenance** | `governance-authorization.v1` | `execution-provenance.v1` (`:forced-capacity-failover`) | `force-authorisation.v2` + `:authorization/class :capacity-failover` | `:interactive-override` |
| **Consumption** | N/A | per-workflow `used-workflows`, cap `max-workflows`, self-hash re-commit | **once at activation** (`:consumption/kind :overflow-activation`) | single-use via grant/consumption registry |
| **Time bounds** | N/A | `[starts-at, expires-at)` | `[starts-at, expires-at)` | `[starts-at, expires-at)` |
| **Use case** | day-to-day governance | resolver overcapacity failover | authorising that failover | frozen/circuit-breaker/interactive override |

---

## 12. Known gaps / non-goals

- **No automatic overflow trigger.** Activation is governance-only; nothing
  auto-activates when a resolver crosses its capacity threshold.
- **No overflow queue.** Disputes arriving while at capacity are rejected; the
  capability handles only workflows already disputed and in its committed scope at
  activation time.
- **Snapshot workflow scope.** The committed scope is the disputed-and-assigned set
  at activation; a workflow disputed later requires a fresh capability.
- **No continuous over-capacity proof at execution time.** Execution proves the
  capability was validly derived from capacity state and remained active — not that
  the resolver is still over capacity at execution time (that is an intentional
  semantic choice documented in Section 4).
