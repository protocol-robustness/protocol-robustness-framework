(ns resolver-sim.allocation.realized-statement
  "Canonical cross-runtime statement for a realized allocation:
   `realized-allocation-statement.v1`.

   This is the semantic object that agent-c produces and commits, that an
   independent Rust kernel reproduces, and that the SP1 guest eventually
   proves. It deliberately does NOT expose agent-c's internal evidence
   ontology: the statement binds a fixed set of roots so a verifier in any
   runtime agrees on the same object without sharing evidence structures.

   The statement binds six roots:

     :allocation-context-root    — committed allocation context (round inputs)
     :request-set-root           — committed requested set per claim
     :allocation-policy-root     — committed allocation/fill policy
     :realized-results-root      — committed realized fills (filled/deferred/haircut)
     :fail-action-policy-root    — committed declared fail-action policy
     :round-lifecycle-root       — committed round-lifecycle projection

   and commits the resulting map under REALIZED_ALLOCATION_STATEMENT_V1.

   All-active no-churn property (mirrors conclusion-collective-hash): when the
   allocation is all-active — no rejection and no deferred/haircut fail action —
   the realized-results root is the unfiltered commitment over the filled set;
   the rejection/fail-action filter is a no-op, so the statement root is
   identical whether or not fail-action filtering was applied.

   Scenario-evidence binding: agent-c binds the statement root into its richer
   scenario evidence via :scenario-evidence-binding, so SP1 proves the canonical
   statement while the simulator's richer evidence model stays simulator-side."
  (:require [resolver-sim.allocation.context :as context]
            [resolver-sim.hash.canonical :as hc]
            [prf.pro-rata.realized-statement :as package-statement]))

(def ^:const schema-version package-statement/schema-version)

(defn request-set-root
  "Commit the requested set (claim -> amount) under REALIZED_REQUEST_SET_V1."
  [requested]
  (package-statement/request-set-root requested))

(defn allocation-policy-root
  "Commit the allocation/fill policy under ALLOCATION_POLICY_V1.
   The policy is the effective fill policy (mode, rounding policy, fill
   order, residual treatment); the declared fail-action policy is committed
   separately so a policy change cannot silently change the verifier's meaning
   without changing evidence identity."
  [policy]
  (package-statement/allocation-policy-root policy))

(defn fail-action-policy-root
  "Commit the declared fail-action policy under FAIL_ACTION_POLICY_V1.
   When no fail-action policy is declared, commits the canonical conservative
   default so the root is always defined and comparable."
  [policy]
  (package-statement/fail-action-policy-root policy))

(defn disposition-of
  "Classify one participant's realized disposition from its realized amounts.

   Order of precedence: deferred, then haircut, then full/partial/zero fill.
   A participant present in :requested always gets an explicit disposition, so
   'inactive/zero-filled' is distinguishable from 'producer omitted'.

   Public so the Rust mirror can implement the identical classifier and golden
   vectors can pin it."
  [realized-row]
  (package-statement/disposition-of realized-row))

(defn realized-results-root
  "Commit the realized results under REALIZED_RESULTS_V1 as an explicit
   per-participant disposition vector.

   The committed projection is a sorted vector of rows over the union of
   requested keys (every participant that had a request), each row carrying:

     {:claim/id k
      :requested r  :filled f  :deferred d  :haircut h  :unrealized u
      :disposition :full-fill | :partial-fill | :deferred | :haircut
                   | :zero-filled | :deferred-and-haircut}

   This is the participant/request → realized-disposition model: no participant
   is silently dropped. 'absent because inactive' (row present, :zero-filled)
   is distinguishable from 'absent because the producer omitted it' (row
   missing entirely), which changes the root.

   All-active no-churn: an all-active allocation is exactly the projection
   where every row is :full-fill with no deferred/haircut — the fail-action
   filter is a no-op, so the all-active root is byte-identical whether or not
   fail-action filtering was applied."
  [decision]
  (package-statement/realized-results-root decision))

(defn round-lifecycle-root
  "Commit the round-lifecycle projection under ROUND_LIFECYCLE_V1."
  [round-lifecycle]
  (package-statement/round-lifecycle-root round-lifecycle))

(defn allocation-context-root
  "Commit the allocation context (round inputs) under ALLOCATION_CONTEXT_V1."
  [ctx]
  (context/context-hash ctx))

(defn all-active?
  "True when the realized allocation is all-active: no positive amount is
   deferred or haircut, and every requested amount is fully filled. Zero-valued
   bookkeeping entries do not turn a full-fill allocation into a shortfall."
  [decision]
  (package-statement/all-active? decision))

(defn statement-preimage
  "The canonical statement value tree committed by the statement root."
  [{:keys [allocation-context-root request-set-root allocation-policy-root
           realized-results-root fail-action-policy-root round-lifecycle-root]}]
  (package-statement/statement-preimage
   {:allocation-context-root allocation-context-root
    :request-set-root request-set-root
    :allocation-policy-root allocation-policy-root
    :realized-results-root realized-results-root
    :fail-action-policy-root fail-action-policy-root
    :round-lifecycle-root round-lifecycle-root}))

(defn statement-root
  "Commit the realized-allocation-statement.v1 under
   REALIZED_ALLOCATION_STATEMENT_V1."
  [statement]
  (package-statement/statement-root statement))

(defn build-statement
  "Build a realized-allocation-statement.v1 from a decision and its context.

   Inputs:
     :ctx            — allocation context map (context/build-context output)
     :decision       — partial-fill decision (requested/filled/deferred/haircut
                       + :policy with optional :fail-action-policy)
     :round-lifecycle— round-state/round-lifecycle projection

   Returns the full statement map including :statement/root,
   :statement/all-active? and :statement/verification-equalities."
  [{:keys [ctx decision round-lifecycle]}]
  (package-statement/build-statement-from-roots
   {:allocation-context-root (allocation-context-root ctx)
    :round-lifecycle-root (round-lifecycle-root round-lifecycle)
    :decision decision}))

(defn scenario-evidence-root
  "Bind the realized-allocation-statement root into agent-c's scenario
   evidence via SCENARIO_EVIDENCE_BINDING_V1.

   SP1 proves the canonical statement; the simulator's richer evidence model
   stays simulator-side but is cryptographically linked to the statement, so
   a verifier can confirm the scenario evidence corresponds to the proven
   allocation statement."
  [statement scenario-evidence]
  (hc/domain-hash :scenario-evidence-binding
                  {:realized-allocation-statement-root (:statement/root statement)
                   :scenario-evidence scenario-evidence}))
