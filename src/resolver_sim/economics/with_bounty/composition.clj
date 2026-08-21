(ns resolver-sim.economics.with-bounty.composition
  "Structural composition receipt for a with-bounty evaluation.

   Stage A commits only what the pure evaluation can prove: the policy root,
   the committed base-operation root, the eligibility and amount evidence, the
   deterministic obligation identity, and the frozen resolution root.

   The :bounty/effect-root, :bounty/application-plan-root, and
   :bounty/transition-evidence-root fields are deliberately nil in Stage A and
   the receipt is explicitly marked :composition/stage :stage-a. Those roots
   are committed in Stage B after adapter support validation and plan
   construction; committing them here would overclaim support that does not
   exist yet.

   An ineligible bounty is recorded as :skipped with its eligibility evidence
   and nil effect/plan roots — omission is not acceptable (design note §11)."
  (:require [resolver-sim.economics.with-bounty.policy :as policy]))

(def verification-profile
  "Re-running the sealed implementations is implementation replay, never
   independent verification (ADR-0006 D6)."
  :implementation-replay)

(defn- base-receipt
  [{:keys [policy-root base-operation-root resolution-root bounty-id stage]}]
  {:composition/type policy/composition-type
    :composition/version policy/composition-version
   :composition/stage (or stage :stage-b)
   :composition/policy-root policy-root
   :composition/base-operation-root base-operation-root
   :bounty/id bounty-id
   :extensions/resolution-root resolution-root
   :verification/profile verification-profile})

(defn applied-receipt
  "Structural receipt for an eligible bounty. Commits the bounty effect root
   and the composition application plan root. The transition-evidence root is
   committed by the protocol adapter after application; until then it is nil."
  [{:keys [policy-root base-operation-root resolution-root
           bounty-id eligibility-evidence amount-evidence
           obligation-id effect-root application-plan-root
           transition-evidence-root stage]}]
  (merge
   (base-receipt {:policy-root policy-root
                  :base-operation-root base-operation-root
                  :resolution-root resolution-root
                  :bounty-id bounty-id
                  :stage stage})
   {:composition/status :applied
    :bounty/eligibility eligibility-evidence
    :bounty/amount amount-evidence
    :bounty/obligation-id obligation-id
    :bounty/effect-root effect-root
    :bounty/application-plan-root application-plan-root
    :bounty/transition-evidence-root transition-evidence-root}))

(defn skipped-receipt
  "Structural receipt for an ineligible bounty: the declared step existed, its
   eligibility evidence is committed, and it produced no effect."
  [{:keys [policy-root base-operation-root resolution-root
           bounty-id eligibility-evidence]}]
  (merge
   (base-receipt {:policy-root policy-root
                  :base-operation-root base-operation-root
                  :resolution-root resolution-root
                  :bounty-id bounty-id})
   {:composition/status :skipped
    :composition/reason :bounty-ineligible
    :bounty/eligibility eligibility-evidence
    :bounty/effect-root nil
    :bounty/application-plan-root nil
    :bounty/transition-evidence-root nil}))
