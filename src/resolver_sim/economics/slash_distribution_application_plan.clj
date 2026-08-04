(ns resolver-sim.economics.slash-distribution-application-plan
  "Pure, content-addressed application plan for slash-distribution.v1 artifacts.

   The plan is built from a verified distribution artifact and describes the
   required state effects without performing any mutation. The plan is itself
   verified through full recomputation against the policy before any effects
   are derived.

   A narrower commit function (protocol adapter) applies the already-verified
   plan atomically to a concrete world state.

   The plan commits to:
   - distribution root and policy root
   - gross amount and idempotency key
   - expected allocation credits
   - funding deductions
   - payable creations
   - backing/restricted classifications
   - beneficiaries and evidence references
   - conservation equations"
  (:require [resolver-sim.economics.slash-distribution :as sd]
            [resolver-sim.hash.canonical :as hc]))

(def schema-version "slash-distribution-application-plan.v1")

;; ── hash projection ─────────────────────────────────────────────────────────

(defn plan-hash-projection
  [plan]
  (select-keys plan
               [:schema-version
                :plan/distribution-root
                :plan/policy-root
                :plan/gross-amount
                :plan/idempotency-key
                :plan/allocation-credits
                :plan/funding-deductions
                :plan/payables
                :plan/backing-records
                :plan/beneficiaries
                :plan/evidence-references
                :plan/preconditions
                :plan/context]))

(defn plan-hash
  [plan]
  (hc/domain-hash :slash-distribution-application-plan-v1
                  (plan-hash-projection plan)))

;; ── plan builder ────────────────────────────────────────────────────────────

(defn- award->payable
  "Derive a payable record from a distribution award."
  [award]
  {:payable/id (str "payable-" (:award/id award))
   :payable/award-id (:award/id award)
   :payable/beneficiary (get-in award [:beneficiary :participant/id])
   :payable/amount (:award/amount award)
   :payable/kind (get-in award [:settlement :obligation-kind])
   :payable/lifecycle :pending-backing})

(defn- award->backing
  "Derive a backing record from award funding deductions.
   The backing classifies the already-deducted amount as restricted.
   It does not create a new economic credit or debit."
  [award]
  {:backing/id (str "backing-" (:award/id award))
   :backing/award-id (:award/id award)
   :backing/amount (:award/amount award)
   :backing/source-allocations (:funding award)
   :backing/kind :funding-deduction-restricted})

(defn build-application-plan
  "Build a slash-distribution-application-plan.v1 from a verified distribution
   and application context.

   Args:
     :distribution      — slash-distribution.v1 artifact (must be verified)
     :policy            — policy map (required for full recomputation verification)
     :policy-root       — optional policy root override
     :idempotency-key   — application idempotency key vector
     :context           — any additional execution context map

   Performs full recomputation verification via sd/verify-distribution
   before deriving any plan effects. Returns {:status :valid, :plan <plan>}
   or {:status :invalid, :violations [...]}."
  [{:keys [distribution policy policy-root idempotency-key context]}]
  (let [;; Full recomputation verification
        verification-ctx (when policy
                           {:policy policy
                            :parameter-context
                            (:distribution/parameter-context distribution)})
        verification (sd/verify-distribution distribution verification-ctx)]
    (if-not (:valid? verification)
      {:status :invalid
       :violations (:violations verification)}
      (let [gross (:distribution/gross-amount distribution)
            awards (:distribution/awards distribution [])
            final (:distribution/final-allocations distribution)
            awards-sum (reduce + 0 (map :award/amount awards))
            final-sum (reduce + 0 (vals final))
            payables (mapv award->payable awards)
            backing-records (mapv award->backing awards)
            funding-deductions (reduce (fn [acc a]
                                         (merge-with + acc (:funding a)))
                                       {} awards)
            base-plan {:schema-version schema-version
                       :plan/distribution-root (:distribution/hash distribution)
                       :plan/policy-root (or policy-root
                                             (:distribution/policy-root distribution))
                       :plan/gross-amount gross
                       :plan/idempotency-key idempotency-key
                       :plan/allocation-credits final
                       :plan/funding-deductions funding-deductions
                       :plan/payables payables
                       :plan/backing-records backing-records
                       :plan/beneficiaries (vec (keep :beneficiary awards))
                       :plan/evidence-references (vec (keep (fn [a]
                                                              (get-in a [:eligibility :evidence-reference]))
                                                            awards))
                       :plan/preconditions {:final-conservation (= gross final-sum)
                                             :funding-conservation (= (reduce + 0 (vals funding-deductions))
                                                                      awards-sum)
                                             :award-count (count awards)
                                             :non-negative-finals (every? #(not (neg? %)) (vals final))}
                       :plan/context (or context {})}
            plan (assoc base-plan :plan/hash (plan-hash base-plan))
            ;; Hardening: preconditions are RECORDED for consumers, but a false
            ;; boolean precondition fails the plan closed instead of producing a
            ;; plan whose preconditions already contradict its effects.
            precondition-failures
            (into []
                  (keep (fn [[k ok]]
                          (when (and (boolean? ok) (not ok))
                            {:violation/id :violation/precondition-failed
                             :details {:precondition k}})))
                  (:plan/preconditions base-plan))]
        (if (seq precondition-failures)
          {:status :invalid :violations precondition-failures}
          {:status :valid :plan plan})))))

;; ── plan validator ──────────────────────────────────────────────────────────

(defn validate-application-plan
  "Validate an application-plan artifact structurally."
  [plan]
  (let [errors (cond-> []
                 (not= schema-version (:schema-version plan))
                 (conj :unsupported-schema-version)
                 (nil? (:plan/distribution-root plan))
                 (conj :missing-distribution-root)
                 (nil? (:plan/idempotency-key plan))
                 (conj :missing-idempotency-key)
                 (not (and (integer? (:plan/gross-amount plan))
                           (not (neg? (:plan/gross-amount plan)))))
                 (conj :invalid-gross-amount))]
    (if (seq errors)
      {:valid? false :errors errors}
      {:valid? true})))

(defn verify-application-plan
  "Verify a persisted plan's hash matches its committed fields."
  [plan]
  (let [validation (validate-application-plan plan)]
    (if-not (:valid? validation)
      validation
      (let [computed (plan-hash plan)
            stored (:plan/hash plan)]
        (if (= computed stored)
          {:valid? true}
          {:valid? false :errors [:hash-mismatch]
           :computed computed :stored stored})))))

(defn plan-hash-projection-matches
  "Convenience: check if a distribution root matches the plan's committed root."
  [plan distribution]
  (= (:plan/distribution-root plan) (:distribution/hash distribution)))
