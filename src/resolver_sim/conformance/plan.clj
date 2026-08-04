(ns resolver-sim.conformance.plan
  "Generic conformance execution plan.

   The profile describes WHAT is required; the plan derives, deterministically,
   WHAT MUST RUN and in what order.  A plan is:

     - derived solely from the committed profile and subject classification;
     - content-addressed (:plan/root);
     - topologically validated (each step's :requires must be produced earlier);
     - complete against the profile's required boundaries and capabilities;
     - bound into the final claim.

   This is NOT a workflow engine: the step vocabulary is closed."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.conformance.profile :as profile]))

(def conformance-step-kinds
  "Closed vocabulary of conformance step kinds."
  #{:schema-validation :semantic-validation :sync-integrity
    :capability-check :replay :reconciliation :attestation})

(def boundary->step-kind
  "Derivation boundary -> the step kind that verifies that boundary."
  {:export            :schema-validation
   :generated-fixture :semantic-validation
   :sync              :sync-integrity
   :solidity-fixture  :capability-check
   :replay            :replay})

(defn- receipt-id [kind]
  (keyword (str (name kind) "-receipt")))

(defn- step-requirements
  "Declared :requires receipts for a step kind (deterministic DAG edges).
   Requirements reference the receipt id produced by the prerequisite step:
   (receipt-id :capability-check) = :capability-check-receipt."
  [kind]
  (case kind
    :schema-validation   []
    :semantic-validation [:schema-validation-receipt]
    :sync-integrity      [:semantic-validation-receipt]
    :capability-check    [:sync-integrity-receipt]
    :replay              [:capability-check-receipt]
    :reconciliation      [:replay-receipt]
    :attestation         [:reconciliation-receipt]
    []))

(defn build-plan-steps
  "Derive the ordered step sequence from a profile's derivation boundaries and
   verdict policy.  Reconciliation and attestation are always appended so a
   plan answers 'were any required checks omitted?'."
  [profile]
  (let [boundaries (get-in profile [:profile/verdict-policy :derivation-boundaries] [])
        kinds (keep boundary->step-kind boundaries)
        steps (mapv (fn [kind]
                      {:step/id kind
                       :requires (step-requirements kind)
                       :produces [(receipt-id kind)]})
                    kinds)
        steps (conj steps
                    {:step/id :reconciliation
                     :requires [:replay-receipt]
                     :produces [:reconciliation-receipt]
                     :skippable? true}
                    {:step/id :attestation
                     :requires [:reconciliation-receipt]
                     :produces [:attestation-receipt]
                     :skippable? true})]
    steps))

(defn plan-topologically-valid?
  "True when every step's :requires is produced by an earlier step."
  [steps]
  (let [produced (atom #{})]
    (every? (fn [{:keys [requires produces]}]
              (let [ok? (every? #(contains? @produced %) requires)]
                (swap! produced into produces)
                ok?))
            steps)))

(defn plan-complete?
  "True when the plan's step kinds cover the profile's derived boundaries,
   capability-check, reconciliation and attestation."
  [profile steps]
  (let [boundaries (get-in profile [:profile/verdict-policy :derivation-boundaries] [])
        required-kinds (into #{} (concat (keep boundary->step-kind boundaries)
                                         [:capability-check :reconciliation :attestation]))
        present (set (map :step/id steps))]
    (every? present required-kinds)))

(defn plan-root
  "Content root of a plan (deterministic, canonical)."
  [plan]
  (hc/domain-hash "conformance.plan.v1"
                  {:profile/root (:profile/root plan)
                   :subject-set/root (:subject-set/root plan)
                   :steps (mapv #(select-keys % [:step/id :requires :produces])
                                (:steps plan))}))

(defn build-plan
  "Build the conformance execution plan for a profile and subject classification.

   subject-set: {:subject-set/root <sha256>
                 :subjects [<id>...]
                 :classification {:included [...] :excluded [...]}}

   Returns {:plan/id :plan/root :profile/root :subject-set/root :steps [...]}."
  [profile subject-set]
  (let [steps (build-plan-steps profile)]
    (when-not (plan-topologically-valid? steps)
      (throw (ex-info "conformance plan is not topologically valid" {:steps steps})))
    (when-not (plan-complete? profile steps)
      (throw (ex-info "conformance plan is incomplete against the profile"
                      {:steps steps})))
    (let [plan {:plan/id (keyword (str (name (:profile/id profile)) "-plan"))
                :plan/root nil
                :profile/root (profile/profile-root profile)
                :subject-set/root (:subject-set/root subject-set)
                :steps steps}]
      (assoc plan :plan/root (plan-root plan)))))
