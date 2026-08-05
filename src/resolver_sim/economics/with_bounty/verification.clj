(ns resolver-sim.economics.with-bounty.verification
  "Structural verification for with-bounty artifacts (ADR-0006 D8).

   Each artifact ships with its structural verifier as it lands: policy root
   (Stage 1), invocation/evaluation envelope and effect (Stage 2), application
   plan (Stage 3), transition evidence (Stage 4). These are structural checks
   — recomputing committed roots and validating shapes. They are NOT
   independent verification and must not be labelled as such (ADR-0006 D6);
   implementation replay and verifier composition are Stage C."
  (:require [resolver-sim.economics.effects :as effects]
            [resolver-sim.economics.with-bounty.application-plan :as wb-plan]
            [resolver-sim.economics.with-bounty.policy :as policy]
            [resolver-sim.economics.with-bounty.transition-evidence :as wb-transition]))

(defn verify-policy-root
  "Structural: the committed policy root recomputes from the policy."
  [policy policy-root]
  (let [computed (policy/with-bounty-policy-root policy)]
    (if (= computed policy-root)
      {:valid? true}
      {:valid? false :violations
       [{:violation/id :violation/policy-root-mismatch
         :details {:committed policy-root :computed computed}}]})))

(defn verify-invocation-evidence
  "Structural: an invocation evidence entry carries a deterministic 64-hex
   invocation id and a capability reference."
  [evidence]
  (let [envelope (get-in evidence [:invocation/evidence-envelope])
        id (get-in envelope [:invocation/id])]
    (cond
      (not (map? envelope))
      {:valid? false :violations
       [{:violation/id :violation/missing-invocation-envelope :details {}}]}

      (not (and (string? id) (= 64 (count id))))
      {:valid? false :violations
       [{:violation/id :violation/invalid-invocation-id :details {:id id}}]}

      (not (vector? (:capability/ref envelope)))
      {:valid? false :violations
       [{:violation/id :violation/missing-capability-ref
         :details {:capability/ref (:capability/ref envelope)}}]}

      :else {:valid? true})))

(defn verify-effect
  "Structural: the effect validates against its versioned contract."
  [effect]
  (effects/validate-effect effect))

(defn verify-effects
  [effects]
  (effects/validate-effects effects))

(defn verify-application-plan
  "Structural: the committed plan hash recomputes."
  [plan]
  (wb-plan/verify-with-bounty-plan plan))

(defn verify-transition-evidence
  "Structural: the committed transition evidence hash recomputes."
  [evidence]
  (wb-transition/verify-transition-evidence evidence))

(defn verify-composition-receipt
  "Structural: a composition receipt has the committed shape for its status."
  [receipt]
  (let [status (:composition/status receipt)
        violations (cond-> []
                     (not= :economics/with-bounty (:composition/type receipt))
                     (conj {:violation/id :violation/invalid-composition-type
                            :details {:type (:composition/type receipt)}})

                     (not (contains? #{:applied :skipped :failed} status))
                     (conj {:violation/id :violation/invalid-composition-status
                            :details {:status status}})

                     (not (string? (:composition/policy-root receipt)))
                     (conj {:violation/id :violation/missing-composition-policy-root
                            :details {}})

                     (not (string? (:composition/base-operation-root receipt)))
                     (conj {:violation/id :violation/missing-base-operation-root
                            :details {}})

                     (not (string? (:extensions/resolution-root receipt)))
                     (conj {:violation/id :violation/missing-resolution-root
                            :details {}})

                     (not (map? (:bounty/eligibility receipt)))
                     (conj {:violation/id :violation/missing-eligibility-evidence
                            :details {}})

                     (= :applied status)
                     (cond-> (not (string? (:bounty/effect-root receipt)))
                       (conj {:violation/id :violation/missing-effect-root :details {}})
                       (not (string? (:bounty/application-plan-root receipt)))
                       (conj {:violation/id :violation/missing-application-plan-root
                              :details {}})))]
    (if (seq violations)
      {:valid? false :violations violations}
      {:valid? true})))

;; ── cross-artifact reconciliation ─────────────────────────────────────────

(defn verify-receipt-with-plan
  "Reconciliation: the receipt's committed roots match the plan it references.
   Guards against a receipt that binds a valid effect root but the wrong
   application-plan root, or a different resolution root."
  [receipt plan]
  (let [violations (cond-> []
                     (not= (:bounty/application-plan-root receipt) (:plan/hash plan))
                     (conj {:violation/id :violation/receipt-plan-root-mismatch
                            :details {:committed (:bounty/application-plan-root receipt)
                                      :expected (:plan/hash plan)}})

                     (not= (:bounty/effect-root receipt) (first (:plan/effect-roots plan)))
                     (conj {:violation/id :violation/receipt-effect-root-mismatch
                            :details {:committed (:bounty/effect-root receipt)
                                      :expected (first (:plan/effect-roots plan))}})

                     (not= (:bounty/obligation-id receipt) (:plan/obligation-id plan))
                     (conj {:violation/id :violation/receipt-obligation-id-mismatch
                            :details {:committed (:bounty/obligation-id receipt)
                                      :expected (:plan/obligation-id plan)}})

                     (not= (:extensions/resolution-root receipt)
                           (:plan/extensions-resolution-root plan))
                     (conj {:violation/id :violation/receipt-resolution-root-mismatch
                            :details {:committed (:extensions/resolution-root receipt)
                                      :expected (:plan/extensions-resolution-root plan)}}))]
    (if (seq violations)
      {:valid? false :violations violations}
      {:valid? true})))

(defn verify-transition-with-plan
  "Reconciliation: the transition evidence binds the plan it references."
  [transition plan]
  (let [violations (cond-> []
                     (not= (:plan/root transition) (:plan/hash plan))
                     (conj {:violation/id :violation/transition-plan-root-mismatch
                            :details {:committed (:plan/root transition)
                                      :expected (:plan/hash plan)}})

                     (not= (:effect-root transition) (first (:plan/effect-roots plan)))
                     (conj {:violation/id :violation/transition-effect-root-mismatch
                            :details {:committed (:effect-root transition)
                                      :expected (first (:plan/effect-roots plan))}})

                     (not= (:combined-effect-root transition)
                           (:plan/combined-effect-root plan))
                     (conj {:violation/id :violation/transition-effect-set-mismatch
                            :details {:committed (:combined-effect-root transition)
                                      :expected (:plan/combined-effect-root plan)}}))]
    (if (seq violations)
      {:valid? false :violations violations}
      {:valid? true})))

(defn verify-application-world
  "Reconciliation of a with-bounty application result world: every payable is
   backed by a backing referencing its root, no backing is orphaned, and each
   payable's claimable amount is present under its obligation id. The claimable
   domain is protocol-specific and supplied by the caller."
  [world & [opts]]
  (let [domain (or (:claimable/domain opts) :liability/bounty-payable)
        payables (vals (:with-bounty/payables world {}))
        backings (vals (:with-bounty/backings world {}))
        payable-roots (set (map :payable/hash payables))
        backing-payable-roots (map :backing/payable-root backings)
        unbacked (into [] (remove #(some #{%} backing-payable-roots)) payable-roots)
        orphan-backings (into []
                              (remove #(contains? payable-roots (:backing/payable-root %)))
                              backings)
        claimable-missing? (not (every? (fn [p]
                                          (= (:payable/amount p)
                                             (get-in world [:claimable-v2 (:payable/id p)
                                                            domain
                                                            (:payable/beneficiary p)])))
                                        payables))
        violations (cond-> []
                     (seq unbacked)
                     (conj {:violation/id :violation/payable-without-backing
                            :details {:payable-roots unbacked}})

                     (seq orphan-backings)
                     (conj {:violation/id :violation/backing-without-payable
                            :details {:backings orphan-backings}})

                     claimable-missing?
                     (conj {:violation/id :violation/claimable-not-derived
                            :details {}}))]
    (if (seq violations)
      {:valid? false :violations violations}
      {:valid? true})))

(defn verify-transition-binds-world
  "Reconciliation: every custody adjustment root in the transition evidence
   resolves to an actual held-custody artifact in the world, and every
   payable/backing root referenced by the transition exists in the world."
  [transition world]
  (let [custody-roots (:custody/adjustment-roots transition [])
        missing-artifacts (into []
                                (keep (fn [c]
                                        (when-not (get-in world
                                                          [:held-artifacts
                                                           (:held-adjustment/id c)])
                                          c)))
                                custody-roots)
        payable-roots (set (:payable/roots transition []))
        backing-roots (set (:backing/roots transition []))
        world-payable-roots (set (map :payable/hash
                                      (vals (:with-bounty/payables world {}))))
        world-backing-roots (set (map :backing/hash
                                      (vals (:with-bounty/backings world {}))))
        missing-payables (seq (remove world-payable-roots payable-roots))
        missing-backings (seq (remove world-backing-roots backing-roots))
        violations (cond-> []
                     (seq missing-artifacts)
                     (conj {:violation/id :violation/custody-artifact-not-bound
                            :details {:missing missing-artifacts}})

                     missing-payables
                     (conj {:violation/id :violation/payable-root-not-in-world
                            :details {:roots (vec missing-payables)}})

                     missing-backings
                     (conj {:violation/id :violation/backing-root-not-in-world
                            :details {:roots (vec missing-backings)}}))]
    (if (seq violations)
      {:valid? false :violations violations}
      {:valid? true})))
