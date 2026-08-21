(ns resolver-sim.benchmark.conformance.reproduction
  "Benchmark-domain conformance adapters (G3a spike).

   Registers the first NON-trace validators into the generic validation
   registry and provides an exact-outcome-root reproduction comparator for the
   research-benchmark-reproduction.v1 profile.  This namespace depends on the
   benchmark domain and the generic conformance package, and imports NO
   trace-domain namespace — proving the framework is conformance-oriented, not
   a generalised trace harness."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.conformance.validation :as validation]
            [resolver-sim.conformance.profile :as profile]
            [resolver-sim.benchmark.outcome-manifest :as om]))

(def ^:const validator-version 1)

(def implementation-root
  "Content root of the benchmark reproduction comparator implementation."
  (hc/domain-hash
   :conformance-validator-implementation-v1
   {:validator/id :research-scenario-semantics :kind :semantic :version validator-version
    :comparison-policy :exact-outcome-reproduction.v1}))

;; ---------------------------------------------------------------------------
;; Exact outcome-root reproduction
;; ---------------------------------------------------------------------------

(defn exact-outcome-reproduction
  "Recompute the committed outcome root deterministically and compare.

   Returns {:exact-reproduction? bool
            :subject/root <committed outcome hash>
            :recomputed/root <recomputed outcome hash>
            :comparison-policy :exact-outcome-reproduction.v1
            :issues [...]}."
  [manifest]
  (let [committed (om/outcome-hash manifest)
        recomputed (om/recompute-outcome-hash manifest)
        match? (= committed recomputed)]
    {:exact-reproduction? match?
     :subject/root committed
     :recomputed/root recomputed
     :comparison-policy :exact-outcome-reproduction.v1
     :issues (if match? [] [{:issue/code :outcome-root-mismatch
                             :issue/details {:committed committed
                                             :recomputed recomputed}}])}))

;; ---------------------------------------------------------------------------
;; Validators (registered into the CLOSED generic registry)
;; ---------------------------------------------------------------------------

(defn research-scenario-schema
  "Structural validation of a benchmark outcome manifest subject."
  [manifest]
  (let [issues (cond-> []
                 (nil? (:schema-version manifest))
                 (conj (validation/validation-issue :missing-schema-version))
                 (nil? (:benchmark/content-root manifest))
                 (conj (validation/validation-issue :missing-content-root))
                 (nil? (:benchmark/model-root manifest))
                 (conj (validation/validation-issue :missing-model-root))
                 (nil? (:benchmark-outcome/hash manifest))
                 (conj (validation/validation-issue :missing-outcome-hash)))]
    (if (empty? issues) {:valid? true :issues []} {:valid? false :issues issues})))

(defn research-scenario-semantics
  "Semantic validation of a benchmark outcome manifest subject: the committed
   outcome root must recompute exactly (manifest-valid?)."
  [manifest]
  (if (om/manifest-valid? manifest)
    {:valid? true :issues []}
    {:valid? false
     :issues [(validation/validation-issue :outcome-root-not-reproducible)]}))

(defn- register-check-validator!
  [validator-id kind check-fn]
  (validation/register-validator!
   {:validator/id validator-id
    :validator/kind kind
    :validator/input-contract :research-scenario.v1
    :validator/version 1
    :validator/implementation-root implementation-root
    :validator/run (fn [subject]
                     (let [{:keys [valid? issues]} (check-fn subject)]
                       (if valid?
                         (validation/pass-result
                          {:validator/id validator-id
                           :validator/kind kind
                           :validator/input-contract :research-scenario.v1
                           :validator/version validator-version
                           :validator/implementation-root implementation-root}
                          subject)
                         (validation/reject-result
                          {:validator/id validator-id
                           :validator/kind kind
                           :validator/input-contract :research-scenario.v1
                           :validator/version validator-version
                           :validator/implementation-root implementation-root}
                          subject
                          issues))))}))

(register-check-validator! :research-scenario-schema :schema research-scenario-schema)
(register-check-validator! :research-scenario-semantics :semantic research-scenario-semantics)

;; Research-benchmark-reproduction profile-kind domain validator (two-stage).
(profile/register-profile-domain-validator!
 :research-benchmark-reproduction
 (fn [profile]
   (let [issues (cond-> []
                  (not= :research-scenario.v1 (:profile/fixture-contract profile))
                  (conj (validation/validation-issue :unsupported-fixture-contract
                                                     {:fixture-contract (:profile/fixture-contract profile)}))
                  (not= :research-benchmark-profile.v1 (:profile/domain-contract profile))
                  (conj (validation/validation-issue :invalid-domain-contract
                                                     {:domain-contract (:profile/domain-contract profile)}))
                  (not= :exact-outcome-reproduction.v1 (:profile/comparison-policy profile))
                  (conj (validation/validation-issue :unsupported-comparison-policy
                                                     {:comparison-policy (:profile/comparison-policy profile)})))]
     (if (empty? issues) {:valid? true :violations []} {:valid? false :violations issues}))))

(defn validate-subject
  "Run the benchmark profile's validators over an outcome manifest subject."
  [manifest]
  (validation/validate-layers
   [:research-scenario-schema :research-scenario-semantics]
   [:schema :semantic]
   manifest))

(defn reproduction-receipt
  "A receipt exercising the :outcome-root-recomputation capability for a
   subject (used by observed-capability satisfaction)."
  [manifest]
  (let [result (exact-outcome-reproduction manifest)]
    {:capability/id :outcome-root-recomputation
     :status (if (:exact-reproduction? result) :pass :fail)
     :subject/root (:subject/root result)
     :recomputed/root (:recomputed/root result)}))

;; ---------------------------------------------------------------------------
;; Reproduction lineage receipt (G3b.2)
;;
;; Distinguishes ARTIFACT DERIVATION (recomputing an outcome hash — capability
;; :outcome-root-recomputation) from EXECUTION REPRODUCTION (independently
;; running the benchmark — capability :benchmark-execution /
;; :independent-run-production).  A lineage receipt binds baseline and
;; reproduced lineages; for exact reproduction the profile states which lineage
;; fields must match and which may legitimately differ.
;; ---------------------------------------------------------------------------

(def reproduction-capabilities
  "Capabilities relevant to benchmark reproduction (never conflated)."
  {:artifact-integrity      :outcome-root-recomputation
   :run-derivation          :case-set-reconstruction
   :execution-reproduction  :independent-run-production
   :benchmark-execution     :benchmark-execution
   :outcome-comparison      :outcome-comparison})

(def ^:const reproduction-lineage-schema-version "conformance.reproduction-lineage/v1")

(defn reproduction-lineage
  "Bind a baseline and reproduced lineage.

   baseline/reproduced each: {:scenario-root :implementation-root :run-root
                              :case-set-root :outcome-root}
   must-match — lineage fields that must be equal (default: scenario,
                implementation, case-set, outcome).
   may-differ — lineage fields expected to differ (e.g. :run-root for a fresh
                independent run).

   Returns {:schema-version ... :reproduction/id ... :baseline ... :reproduced ...
            :comparison-policy ... :comparison-result :equal|:diverged|:not-evaluated
            :diverged-fields [...] :must-match [...] :may-differ [...]}."
  [m]
  (let [policy (or (:comparison-policy m) :exact-outcome-reproduction.v1)
        must (or (:must-match m) #{:scenario-root :implementation-root
                                   :case-set-root :outcome-root})
        baseline (:baseline m)
        reproduced (:reproduced m)
        diverged (filterv (fn [f] (not= (get baseline f) (get reproduced f))) must)
        result (if (and baseline reproduced)
                 (if (seq diverged) :diverged :equal)
                 :not-evaluated)]
    {:schema-version reproduction-lineage-schema-version
     :baseline baseline
     :reproduced reproduced
     :comparison-policy policy
     :comparison-result result
     :diverged-fields diverged
     :must-match (vec must)
     :may-differ (vec (or (:may-differ m) []))}))

(defn lineage-root
  "Deterministic content root of a reproduction lineage receipt."
  [lineage]
  (hc/domain-hash :conformance-reproduction-lineage-v1
                  (select-keys lineage
                               [:schema-version :reproduction/id
                                :baseline :reproduced :comparison-policy
                                :comparison-result :diverged-fields
                                :must-match :may-differ])))

(defn reproduction-claimable?
  "A reproduction claim is permitted only when the reproduction compared equal
   AND the underlying conclusion is :established (conclusions that are
   qualified/tentative/inconclusive cannot inherit 'reproduced' wording)."
  [lineage conclusion]
  (and (= :equal (:comparison-result lineage))
       (= :established (:conclusion/status conclusion))))

(defn reproduction-claim
  "Emit a reproduction claim (evidence-bound): binds the lineage root and both
   run identities.  Returns nil when not claimable."
  [lineage conclusion]
  (when (reproduction-claimable? lineage conclusion)
    {:claim/class :reproduced
     :claim/status :pass
     :reproduction/root (lineage-root lineage)
     :baseline/run-root (get-in lineage [:baseline :run-root])
     :reproduced/run-root (get-in lineage [:reproduced :run-root])}))
