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
            [resolver-sim.hash.canonical :as hc]))

(def schema-version "realized-allocation-statement.v1")

(defn- sort-by-claim-id
  "Canonical order for claim-keyed maps: sort by claim id key."
  [m]
  (into (sorted-map) m))

(defn request-set-root
  "Commit the requested set (claim -> amount) under REALIZED_REQUEST_SET_V1."
  [requested]
  (hc/domain-hash :realized-request-set (sort-by-claim-id requested)))

(defn allocation-policy-root
  "Commit the allocation/fill policy under ALLOCATION_POLICY_V1.
   The policy is the effective fill policy (mode, rounding policy, fill
   order, residual treatment); the declared fail-action policy is committed
   separately so a policy change cannot silently change the verifier's meaning
   without changing evidence identity."
  [policy]
  (hc/domain-hash :allocation-policy (dissoc policy :fail-action-policy)))

(defn fail-action-policy-root
  "Commit the declared fail-action policy under FAIL_ACTION_POLICY_V1.
   When no fail-action policy is declared, commits the canonical conservative
   default so the root is always defined and comparable."
  [policy]
  (let [effective (or (:fail-action-policy policy)
                      {:mode :pro-rata-treatment
                       :deferred-policy :same-ratio
                       :haircut-policy :same-ratio})]
    (hc/hash-with-intent {:hash/intent :fail-action-policy} effective)))

(defn disposition-of
  "Classify one participant's realized disposition from its realized amounts.

   Order of precedence: deferred, then haircut, then full/partial/zero fill.
   A participant present in :requested always gets an explicit disposition, so
   'inactive/zero-filled' is distinguishable from 'producer omitted'.

   Public so the Rust mirror can implement the identical classifier and golden
   vectors can pin it."
  [{:keys [requested filled deferred haircut]}]
  (cond
    (and (pos? (long deferred)) (pos? (long haircut))) :deferred-and-haircut
    (pos? (long deferred)) :deferred
    (pos? (long haircut)) :haircut
    (= (long requested) (long filled)) :full-fill
    (zero? (long filled)) :zero-filled
    :else :partial-fill))

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
  [{:keys [requested filled deferred haircut unrealized]}]
  (let [claims (->> (concat (keys requested) (keys filled)
                            (keys deferred) (keys haircut) (keys unrealized))
                    distinct
                    sort)]
    (hc/domain-hash :realized-results
                    (mapv (fn [k]
                            (let [row {:claim/id k
                                       :requested (long (get requested k 0))
                                       :filled (long (get filled k 0))
                                       :deferred (long (get deferred k 0))
                                       :haircut (long (get haircut k 0))
                                       :unrealized (long (get unrealized k 0))}]
                              (assoc row :disposition (disposition-of row))))
                          claims))))

(defn round-lifecycle-root
  "Commit the round-lifecycle projection under ROUND_LIFECYCLE_V1."
  [round-lifecycle]
  (hc/domain-hash :round-lifecycle round-lifecycle))

(defn allocation-context-root
  "Commit the allocation context (round inputs) under ALLOCATION_CONTEXT_V1."
  [ctx]
  (context/context-hash ctx))

(defn all-active?
  "True when the realized allocation is all-active: nothing deferred, nothing
   haircut, and every requested amount is fully filled."
  [{:keys [requested filled deferred haircut]}]
  (and (empty? deferred)
       (empty? haircut)
       (= (sort-by-claim-id requested)
          (sort-by-claim-id filled))))

(defn statement-preimage
  "The canonical statement value tree committed by the statement root."
  [{:keys [allocation-context-root request-set-root allocation-policy-root
           realized-results-root fail-action-policy-root round-lifecycle-root]}]
  {:schema-version schema-version
   :allocation-context-root allocation-context-root
   :request-set-root request-set-root
   :allocation-policy-root allocation-policy-root
   :realized-results-root realized-results-root
   :fail-action-policy-root fail-action-policy-root
   :round-lifecycle-root round-lifecycle-root})

(defn statement-root
  "Commit the realized-allocation-statement.v1 under
   REALIZED_ALLOCATION_STATEMENT_V1."
  [statement]
  (hc/domain-hash :realized-allocation-statement (statement-preimage statement)))

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
  (let [statement {:schema-version schema-version
                   :allocation-context-root (allocation-context-root ctx)
                   :request-set-root (request-set-root (:requested decision))
                   :allocation-policy-root (allocation-policy-root (:policy decision))
                   :realized-results-root (realized-results-root decision)
                   :fail-action-policy-root (fail-action-policy-root (:policy decision))
                   :round-lifecycle-root (round-lifecycle-root round-lifecycle)}]
    (assoc statement
           :statement/root (statement-root statement)
           :statement/all-active? (all-active? decision)
           :statement/verification-equalities
           {:all-active-all-full-fill
            (let [active? (all-active? decision)
                  disposition (fn [k]
                                (disposition-of
                                 {:requested (long (get-in decision [:requested k] 0))
                                  :filled (long (get-in decision [:filled k] 0))
                                  :deferred (long (get-in decision [:deferred k] 0))
                                  :haircut (long (get-in decision [:haircut k] 0))
                                  :unrealized (long (get-in decision [:unrealized k] 0))}))]
              (and active?
                   (every? #(= :full-fill (disposition %))
                           (keys (:requested decision)))))})))

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
