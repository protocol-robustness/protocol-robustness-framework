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
