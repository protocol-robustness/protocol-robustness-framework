(ns resolver-sim.sim.reference-validation-evidence
  "Maps reference-validation evidence invariant IDs to simulator canonical IDs.

   Evidence IDs (kebab-case strings in manifest.edn) are claim-layer names.
   Canonical IDs (keywords in protocols.sew.invariants) are executable checks."
  (:require [clojure.set :as set]))

(def evidence-invariant->canonical
  "Reference-validation evidence ID → set of simulator invariant IDs that must hold."
  {"active-escrow-module-snapshot-immutable"
   #{:module-snapshot-immutable}

   "governance-forward-only"
   #{:module-snapshot-immutable :escalation-level-monotonic}

   "slashable-liability-preserved"
   #{:solvency :bond-slash-bounded :liability-slash-boundary :conservation-of-funds}

   "bounded-progress-under-load"
   #{:no-stale-automatable-escrows :resolver-capacity}

   "liability-gated-withdrawal"
   #{:no-withdrawal-during-dispute :bond-liquidity :held-delta-accounted}

   "no-double-settlement"
   #{:single-resolution-payout-consistent :cancellation-mutex
     :pending-settlement-consistent :terminal-states-unchanged}

   "pull-first-value-flow"
   #{:settlement-principal-boundary :settlement-yield-boundary
     :liability-slash-boundary :bond-boundary :fee-boundary
     :claimable-classification}

   "escalation-layer-protection"
   #{:escalation-level-monotonic :dispute-level-bounded :dispute-resolution-path}

   "yield-accrual-efficiency"
   #{:held-delta-accounted :settlement-yield-boundary :conservation-of-funds}

   "no-stale-pending-settlements"
   #{:pending-settlement-consistent :no-stale-automatable-escrows}

   "terminal-states-unchanged"
   #{:terminal-states-unchanged}

   "single-resolution-payout-consistent"
   #{:single-resolution-payout-consistent}

   "pending-settlement-consistent"
   #{:pending-settlement-consistent}

   "escalation-level-monotonic"
   #{:escalation-level-monotonic}

   ;; Yield evidence invariants
   "yield-position-consistency"
   #{:yield/position-consistency}

   "yield-value-conservation"
   #{:yield/value-conservation}

   "yield-exposure"
   #{:yield/exposure}

   "yield-shortfall-splits"
   #{:yield/shortfall-splits}

   "yield-deferred-reclaim"
   #{:yield/deferred-reclaim}})

(def all-evidence-ids
  (set (keys evidence-invariant->canonical)))

(defn canonical-ids-for-evidence
  [evidence-ids]
  (into #{}
        (mapcat #(get evidence-invariant->canonical % #{}) evidence-ids)))

(defn unmapped-evidence-ids
  [evidence-ids]
  (remove evidence-invariant->canonical evidence-ids))

(defn- sew-world-invariant-ids []
  @(requiring-resolve 'resolver-sim.protocols.sew.invariants/world-invariant-ids))

(defn- sew-check-all [world]
  ((requiring-resolve 'resolver-sim.protocols.sew.invariants/check-all) world))

(defn verify-evidence-invariants!
  "After a successful replay, assert mapped world-level canonical invariants hold.

   Accepts optional :world-invariant-ids and :check-all-fn for protocol-specific
   invariant checking. Defaults to Sew protocol invariants.

   Transition-level mapped IDs are implied by a :pass replay outcome (replay runs
   check-transition on every successful step). Returns a summary map."
  [replay-result evidence-ids & {:keys [world-invariant-ids check-all-fn]
                                 :or {world-invariant-ids nil
                                                                       check-all-fn nil}}]
                                   (let [world-invariant-ids (or world-invariant-ids (sew-world-invariant-ids))
                                         check-all-fn (or check-all-fn sew-check-all) ]
    (let [outcome (:outcome replay-result)]
    (when-not (= :pass outcome)
      (throw (ex-info "replay did not pass; cannot verify evidence invariants"
                      {:outcome outcome
                       :halt-reason (:halt-reason replay-result)
                       :scenario-id (:scenario-id replay-result)})))
    (when-let [unmapped (seq (unmapped-evidence-ids evidence-ids))]
      (throw (ex-info "unmapped reference-validation evidence invariant IDs"
                      {:unmapped (vec unmapped)})))
    (let [required   (canonical-ids-for-evidence evidence-ids)
          world      (:world replay-result)
          _          (when (nil? world)
                       (throw (ex-info "replay pass result missing :world"
                                       {:scenario-id (:scenario-id replay-result)})))
          world-req  (set/intersection required world-invariant-ids)
          check      (check-all-fn world)
          failures   (for [id world-req
                           :let [r (get-in check [:results id])]
                           :when (not (:holds? r))]
                       {:invariant-id id :violations (:violations r)})]
      (when (seq failures)
        (throw (ex-info "evidence-mapped world invariants failed on final world"
                        {:failures failures
                         :evidence-ids evidence-ids})))
        {:evidence-ids evidence-ids
         :canonical-verified (vec (sort required))
         :world-checked (vec (sort world-req))}))))

