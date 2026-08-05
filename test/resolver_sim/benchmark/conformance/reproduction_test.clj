(ns resolver-sim.benchmark.conformance.reproduction-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.conformance.validation :as validation]
            [resolver-sim.benchmark.outcome-manifest :as om]
            [resolver-sim.benchmark.research-conclusion :as rc]
            [resolver-sim.benchmark.conformance.reproduction :as repro]))

(def ^:private committed-inputs
  "Committed inputs for a small deterministic benchmark outcome manifest."
  {:benchmark/content-root "sha256:benchmark-model"
   :benchmark/model-root (str "sha256:" (hash "research-benchmark-model.v1"))
   :benchmark/evaluation-policy-root "sha256:policy"
   :execution/status :completed
   :execution/parameter-domain-root "sha256:domain"
   :execution/sampling-policy-root "sha256:sampling"
   :execution/realised-parameter-set-root "sha256:realised"
   :execution/generated-case-set-root "sha256:cases"
   :outcomes/conclusions
   [(rc/build-conclusion {:conclusion/id :benchmark.conclusion/stable
                          :conclusion/premise {:x "committed inputs"}
                          :conclusion/result {:y "deterministic outcome"}})]
   :results/claims []})

(deftest exact-outcome-root-reproduction
  (testing "the committed outcome root recomputes exactly (deterministic)"
    (let [manifest (om/build-manifest committed-inputs)
          result (repro/exact-outcome-reproduction manifest)]
      (is (:exact-reproduction? result))
      (is (= (:subject/root result) (:recomputed/root result)))
      (is (empty? (:issues result))))))

(deftest benchmark-validators-registered-in-closed-registry
  (testing "the benchmark profile registers into the SAME closed registry"
    (is (= :research-scenario-schema
           (:validator/id (validation/resolve-validator :research-scenario-schema))))
    (is (= :research-scenario-semantics
           (:validator/id (validation/resolve-validator :research-scenario-semantics))))
    (is (= :schema (:validator/kind (validation/resolve-validator :research-scenario-schema))))
    (is (= :semantic (:validator/kind (validation/resolve-validator :research-scenario-semantics))))))

(deftest benchmark-subject-passes-both-layers
  (let [manifest (om/build-manifest committed-inputs)
        {:keys [valid? results]} (repro/validate-subject manifest)]
    (is valid?)
    (is (= 2 (count results)))
    (doseq [r results]
      (is (= :pass (:validation/status r))))))

(deftest tampered-outcome-root-rejected
  (let [manifest (-> (om/build-manifest committed-inputs)
                     (update :benchmark-outcome/hash (fn [_] "sha256:tampered")))
        {:keys [valid? results]} (repro/validate-subject manifest)]
    (is (not valid?))
    (is (= :rejected (get-in results [1 :validation/status])))
    (is (some #(= :outcome-root-not-reproducible (:issue/code %))
              (get-in results [1 :validation/issues])))))

(deftest reproduction-receipt-exercises-capability
  (let [manifest (om/build-manifest committed-inputs)
        receipt (repro/reproduction-receipt manifest)]
    (is (= :outcome-root-recomputation (:capability/id receipt)))
    (is (= :pass (:status receipt)))
    (is (= (om/outcome-hash manifest) (:subject/root receipt)))))

;; ═════════════════════════════════════════════════════════════════════════
;; G3b.2 — reproduction lineage + complete benchmark chain
;; ═════════════════════════════════════════════════════════════════════════

(defn- lineage-inputs [outcome-root]
  {:reproduction/id :benchmark.reproduction/reversal-slashing-v1
   :baseline {:scenario-root "sha256:scenario"
              :implementation-root "sha256:runner"
              :run-root "sha256:baseline-run"
              :case-set-root "sha256:cases"
              :outcome-root outcome-root}
   :reproduced {:scenario-root "sha256:scenario"
                :implementation-root "sha256:runner"
                :run-root "sha256:reproduced-run"
                :case-set-root "sha256:cases"
                :outcome-root outcome-root}})

(deftest reproduction-lineage-equal-permits-claim
  (let [outcome (om/outcome-hash (om/build-manifest committed-inputs))
        lineage (repro/reproduction-lineage (lineage-inputs outcome))
        conclusion (rc/build-conclusion {:conclusion/id :benchmark.conclusion/stable
                                         :conclusion/premise {:x "baseline"}
                                         :conclusion/result {:y "reproduced"}})]
    (is (= :equal (:comparison-result lineage)))
    (is (empty? (:diverged-fields lineage)))
    (is (string? (repro/lineage-root lineage)))
    (is (repro/reproduction-claimable? lineage conclusion))
    (let [claim (repro/reproduction-claim lineage conclusion)]
      (is (= :reproduced (:claim/class claim)))
      (is (= "sha256:baseline-run" (:baseline/run-root claim)))
      (is (= "sha256:reproduced-run" (:reproduced/run-root claim)))
      (is (= (repro/lineage-root lineage) (:reproduction/root claim))))))

(deftest reproduction-lineage-diverged-suppresses-claim
  (let [lineage (repro/reproduction-lineage
                 (assoc-in (lineage-inputs "sha256:baseline-outcome")
                           [:reproduced :outcome-root] "sha256:other-outcome"))
        conclusion (rc/build-conclusion {:conclusion/id :benchmark.conclusion/stable
                                         :conclusion/premise {:x "b"}
                                         :conclusion/result {:y "r"}})]
    (is (= :diverged (:comparison-result lineage)))
    (is (some #{:outcome-root} (:diverged-fields lineage)))
    (is (not (repro/reproduction-claimable? lineage conclusion)))
    (is (nil? (repro/reproduction-claim lineage conclusion)))))

(deftest reproduction-claim-not-when-inconclusive
  (let [outcome (om/outcome-hash (om/build-manifest committed-inputs))
        lineage (repro/reproduction-lineage (lineage-inputs outcome))
        tentative (rc/build-conclusion {:conclusion/id :benchmark.conclusion/tent
                                        :conclusion/premise {:x "b"}
                                        :conclusion/result {:y "r"}
                                        :conclusion/status :tentative})]
    (is (not (repro/reproduction-claimable? lineage tentative)))
    (is (nil? (repro/reproduction-claim lineage tentative)))))

(deftest artifact-derivation-vs-execution-capabilities
  (testing "recomputing an outcome hash is NOT the same capability as executing the run"
    (is (not= (:artifact-integrity repro/reproduction-capabilities)
              (:execution-reproduction repro/reproduction-capabilities)))
    (is (= :outcome-root-recomputation (:artifact-integrity repro/reproduction-capabilities)))
    (is (= :independent-run-production (:execution-reproduction repro/reproduction-capabilities)))))

(deftest conclusion-claims-only-manifest-outcomes
  (let [manifest (om/build-manifest committed-inputs)
        conclusion-ids (set (map :conclusion/id (:outcomes/conclusions committed-inputs)))
        referenced (set (map :conclusion/id (get-in manifest [:outcomes/conclusions])))]
    (is (= conclusion-ids referenced))
    (is (om/manifest-valid? manifest))))

;; ═════════════════════════════════════════════════════════════════════════
;; G3b.3 — honest failure matrix (typed outcomes, claim suppression)
;; ═════════════════════════════════════════════════════════════════════════

(deftest malformed-scenario-rejected
  (let [{:keys [valid? results]} (repro/validate-subject
                                  {:schema-version "benchmark-outcome.v1"})] ; missing content-root
    (is (not valid?))
    (is (= :rejected (get-in results [0 :validation/status])))
    (is (some #(= :missing-content-root (:issue/code %))
              (get-in results [0 :validation/issues])))))

(deftest mismatched-outcome-root-rejected
  (let [manifest (-> (om/build-manifest committed-inputs)
                     (update :benchmark-outcome/hash (fn [_] "sha256:tampered")))
        {:keys [valid?]} (repro/validate-subject manifest)]
    (is (not valid?))))

(deftest incomplete-case-set-not-claimable
  (let [outcome (om/outcome-hash (om/build-manifest committed-inputs))
        lineage (repro/reproduction-lineage
                 (assoc-in (lineage-inputs outcome)
                           [:reproduced :case-set-root] "sha256:partial-cases"))
        conclusion (rc/build-conclusion {:conclusion/id :benchmark.conclusion/stable
                                         :conclusion/premise {:x "b"}
                                         :conclusion/result {:y "r"}})]
    (testing "case-set divergence (must-match field) suppresses the claim"
      (is (= :diverged (:comparison-result lineage)))
      (is (nil? (repro/reproduction-claim lineage conclusion))))))
