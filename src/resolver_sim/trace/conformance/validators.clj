(ns resolver-sim.trace.conformance.validators
  "Trace-domain conformance adapters.

   Registers the first concrete validators into the generic validation
   registry (resolver-sim.conformance.validation):
     :trace-fixture-v2-schema      — structural (JSON Schema contract)
     :trace-fixture-v2-semantics   — semantic (action/role/version/alias rules)

   This namespace is OUTSIDE the generic conformance package: it depends on
   trace fixture shapes, actions and roles, but the generic package does not
   depend on this namespace."
  (:require [clojure.string :as str]
            [resolver-sim.conformance.validation :as validation]
            [resolver-sim.conformance.profile :as profile]
            [resolver-sim.trace.conformance.vocabulary :as vocab]))

(def ^:const validator-version 1)

(defn schema-validate
  "Structural validation of a CDRS v0.2 trace fixture.  Mirrors the committed
   JSON Schema at etc/conformance/schemas/trace-fixture-v2.schema.json."
  [fixture]
  (let [issues (cond-> []
                 (not= "0.2" (:cdrs_version fixture))
                 (conj (validation/validation-issue :unsupported-cdrs-version
                                                    {:declared (:cdrs_version fixture)
                                                     :supported "0.2"}))
                 (not= "2" (some-> fixture :schema_version str))
                 (conj (validation/validation-issue :unsupported-schema-version
                                                    {:declared (:schema_version fixture)
                                                     :supported "2"}))
                 (nil? (:scenario_id fixture))
                 (conj (validation/validation-issue :missing-scenario-id))
                 (nil? (:fee_bps fixture))
                 (conj (validation/validation-issue :missing-fee-bps))
                 (and (some? (:fee_bps fixture))
                      (not (integer? (:fee_bps fixture))))
                 (conj (validation/validation-issue :fee-bps-not-integer
                                                    {:fee-bps (:fee_bps fixture)}))
                 (and (integer? (:fee_bps fixture))
                      (neg? (:fee_bps fixture)))
                 (conj (validation/validation-issue :fee-bps-negative
                                                    {:fee-bps (:fee_bps fixture)}))
                 (not (integer? (:step_count fixture)))
                 (conj (validation/validation-issue :missing-step-count))
                 (nil? (:invariant_profile fixture))
                 (conj (validation/validation-issue :missing-invariant-profile))
                 (not (vector? (:steps fixture)))
                 (conj (validation/validation-issue :missing-steps))
                 (and (vector? (:steps fixture))
                      (not= (:step_count fixture) (count (:steps fixture))))
                 (conj (validation/validation-issue
                        :step-count-mismatch
                        {:declared (:step_count fixture)
                         :actual (count (:steps fixture))})))]
    (if (empty? issues)
      {:valid? true :issues []}
      {:valid? false :issues issues})))

(defn- ->vocab-keyword
  "Normalise a fixture string identifier (snake_case) to the vocabulary
   keyword form (kebab-case)."
  [s]
  (when s (keyword (str/replace s "_" "-"))))

(defn semantic-validate
  "Semantic validation of a CDRS v0.2 trace fixture.

   Checks:
     - every step's action is in the supported trace vocabulary;
     - every actor is a known role;
     - step seqs are unique and sequential from 0;
     - a wf_alias is defined (create_escrow) before it is referenced;
     - accepted steps declare the expected projection fields;
     - bare execute_resolution is rejected as ambiguous."
  [fixture]
  (let [steps (vec (:steps fixture []))
        issues (atom [])
        add-issue! (fn [issue] (swap! issues conj issue))
        seen-aliases (atom #{})
        seqs (mapv :seq steps)]
    (when (not= seqs (vec (range (count steps))))
      (add-issue! (validation/validation-issue :step-seq-not-sequential
                                               {:seqs seqs})))
    (when (not= (count (distinct seqs)) (count seqs))
      (add-issue! (validation/validation-issue :duplicate-step-seq)))
    (doseq [step steps]
      (let [attrs (or (:attributes step) {})
            action (:action attrs)
            actor (:actor step)
            alias (:wf_alias attrs)]
        (when-not (contains? vocab/actions (->vocab-keyword action))
          (add-issue! (validation/validation-issue :unknown-action
                                                   {:action action
                                                    :seq (:seq step)})))
        (when-not (contains? vocab/roles (->vocab-keyword actor))
          (add-issue! (validation/validation-issue :unknown-role
                                                   {:role actor
                                                    :seq (:seq step)})))
        (when (= "execute_resolution" action)
          (add-issue! (validation/validation-issue :ambiguous-execute-resolution
                                                   {:seq (:seq step)
                                                    :reason "export rewrites it to release/cancel"})))
        (when (and alias
                   (not= "create_escrow" action)
                   (not (contains? @seen-aliases alias)))
          (add-issue! (validation/validation-issue :undefined-wf-alias
                                                   {:wf-alias alias
                                                    :seq (:seq step)})))
        (when (and (= "create_escrow" action) alias)
          (swap! seen-aliases conj alias))
        (let [expected (:expected step)]
          (when (and (:accepted expected true)
                     (nil? (:escrow_state expected)))
            (add-issue! (validation/validation-issue :accepted-step-missing-projection
                                                     {:seq (:seq step)}))))))
    (if (empty? @issues)
      {:valid? true :issues []}
      {:valid? false :issues @issues})))

(defn- register-check-validator!
  "Register a validator whose :run adapts a {:valid? bool :issues [...]} check
   into the standard validation-result shape (binding the subject root and the
   validator implementation root)."
  [validator-id kind implementation-root check-fn]
  (validation/register-validator!
   {:validator/id validator-id
    :validator/kind kind
    :validator/input-contract :trace-fixture.v2
     :validator/version validator-version
    :validator/implementation-root implementation-root
    :validator/run
    (fn [subject]
      (let [{:keys [valid? issues]} (check-fn subject)]
        (if valid?
          (validation/pass-result
           {:validator/id validator-id
            :validator/kind kind
            :validator/version vocab/validator-version
            :validator/implementation-root implementation-root}
           subject)
          (validation/reject-result
           {:validator/id validator-id
            :validator/kind kind
            :validator/version vocab/validator-version
            :validator/implementation-root implementation-root}
           subject
           issues))))}))

(register-check-validator!
 :trace-fixture-v2-schema :schema
 vocab/trace-fixture-v2-schema-root schema-validate)

(register-check-validator!
 :trace-fixture-v2-semantics :semantic
 vocab/trace-fixture-v2-semantics-root semantic-validate)

;; Trace-equivalence profile-kind domain validator (two-stage validation).
(profile/register-profile-domain-validator!
 :trace-equivalence
 (fn [profile]
   (let [issues (cond-> []
                  (not (contains? vocab/supported-fixture-contracts
                                  (:profile/fixture-contract profile)))
                  (conj (validation/validation-issue :unsupported-fixture-contract
                                                     {:fixture-contract (:profile/fixture-contract profile)}))
                  (not= :trace-equivalence-profile.v1 (:profile/domain-contract profile))
                  (conj (validation/validation-issue :invalid-domain-contract
                                                     {:domain-contract (:profile/domain-contract profile)})))]
     (if (empty? issues) {:valid? true :violations []} {:valid? false :violations issues}))))

(defn validate-fixture
  "Run the schema and semantic layers over a trace fixture (both required).
   Returns {:results [...] :valid? bool :issues [...]}."
  [fixture]
  (validation/validate-layers
   [:trace-fixture-v2-schema :trace-fixture-v2-semantics]
   [:schema :semantic]
   fixture))
