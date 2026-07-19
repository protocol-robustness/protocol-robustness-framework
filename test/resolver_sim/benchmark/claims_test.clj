(ns resolver-sim.benchmark.claims-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.claims :as claims]))

(def deterministic-replay-manifest
  (edn/read-string (slurp "benchmarks/packs/prf-core/deterministic-replay-v1.edn")))

(deftest benchmark-claim-provenance-is-explicit
  (testing "unmapped benchmark claims do not imply passive-registry provenance"
    (is (= {:claim/definition-source :benchmark-catalogue-only
            :claim/registry-version 1
            :claim/evidence-binding :benchmark-result}
           (claims/claim-provenance :claim/replay-identical-results))))
  (testing "matching passive definitions carry their canonical identity"
    (let [provenance (with-redefs [resolver-sim.claims.engine/claim-definition
                                   (fn [_] {:canonical-hash "definition-hash"
                                            :concept-hash "concept-hash"})]
                       (claims/claim-provenance :claim/example))]
      (is (= :passive-registry (:claim/definition-source provenance)))
      (is (= "definition-hash" (:claim/definition-hash provenance)))
      (is (= "concept-hash" (:claim/concept-hash provenance))))))

(deftest prf-replay-claims-have-registered-evaluators
  (testing "all declared PRF replay claims resolve to benchmark evaluators"
    (doseq [claim-id [:claim/replay-identical-results
                      :claim/hash-consistency-across-runs
                      :claim/no-nondeterminism]]
      (is (= :benchmark (:scope (claims/evaluator-resolver claim-id)))
          (str "missing benchmark evaluator for " claim-id)))))

(deftest prf-replay-claims-pass-for-consistent-results
  (let [results [{:scenario/id "scenario-a"
                  :simulator/scenario-path "scenarios/a.edn"
                  :benchmark/run-index 1
                  :benchmark/run-count 2
                  :outcome :pass
                  :halt-reason nil
                  :invariant-results [{:id :inv/a :result :pass}]
                  :scenario/evidence-root (apply str (repeat 64 "a"))}
                 {:scenario/id "scenario-a"
                  :simulator/scenario-path "scenarios/a.edn"
                  :benchmark/run-index 2
                  :benchmark/run-count 2
                  :outcome :pass
                  :halt-reason nil
                  :invariant-results [{:id :inv/a :result :pass}]
                  :scenario/evidence-root (apply str (repeat 64 "a"))}
                 {:scenario/id "scenario-b"
                  :simulator/scenario-path "scenarios/b.edn"
                  :benchmark/run-index 1
                  :benchmark/run-count 2
                  :outcome :fail
                  :halt-reason :halted
                  :invariant-results [{:id :inv/b :result :fail}]
                  :scenario/evidence-root (apply str (repeat 64 "b"))}
                 {:scenario/id "scenario-b"
                  :simulator/scenario-path "scenarios/b.edn"
                  :benchmark/run-index 2
                  :benchmark/run-count 2
                  :outcome :fail
                  :halt-reason :halted
                  :invariant-results [{:id :inv/b :result :fail}]
                  :scenario/evidence-root (apply str (repeat 64 "b"))}]
        claim-results (claims/evaluate-manifest-claims deterministic-replay-manifest results)]
    (testing "all three replay claims pass when duplicate scenario entries agree"
      (is (= {:claim/replay-identical-results :pass
              :claim/hash-consistency-across-runs :pass
              :claim/no-nondeterminism :pass}
             (into {}
                   (map (juxt :claim/id :claim/outcome))
                   claim-results))))))

(deftest prf-replay-claims-fail-for-conflicting-results
  (let [results [{:scenario/id "scenario-a"
                  :simulator/scenario-path "scenarios/a.edn"
                  :benchmark/run-index 1
                  :benchmark/run-count 2
                  :outcome :pass
                  :halt-reason nil
                  :invariant-results [{:id :inv/a :result :pass}]
                  :scenario/evidence-root (apply str (repeat 64 "a"))}
                 {:scenario/id "scenario-a"
                  :simulator/scenario-path "scenarios/a.edn"
                  :benchmark/run-index 2
                  :benchmark/run-count 2
                  :outcome :fail
                  :halt-reason :halted
                  :invariant-results [{:id :inv/a :result :fail}]
                  :scenario/evidence-root (apply str (repeat 64 "b"))}]
        claim-results (claims/evaluate-manifest-claims deterministic-replay-manifest results)
        by-id (into {} (map (juxt :claim/id identity)) claim-results)]
    (testing "conflicting duplicate scenario entries fail the replay claims"
      (is (= :fail (get-in by-id [:claim/replay-identical-results :claim/outcome])))
      (is (= :fail (get-in by-id [:claim/hash-consistency-across-runs :claim/outcome])))
      (is (= :fail (get-in by-id [:claim/no-nondeterminism :claim/outcome])))
      (is (= [:replay-results-mismatch]
             (:claim/evidence (get by-id :claim/replay-identical-results))))
      (is (= [:evidence-root-mismatch]
             (:claim/evidence (get by-id :claim/hash-consistency-across-runs))))
      (is (= [:nondeterministic-replay]
             (:claim/evidence (get by-id :claim/no-nondeterminism)))))))

(deftest prf-replay-claims-fail-for-incomplete-run-pairing
  (let [results [{:scenario/id "scenario-a"
                  :simulator/scenario-path "scenarios/a.edn"
                  :benchmark/run-index 1
                  :benchmark/run-count 2
                  :outcome :pass
                  :halt-reason nil
                  :invariant-results [{:id :inv/a :result :pass}]
                  :scenario/evidence-root (apply str (repeat 64 "a"))}]
        claim-results (claims/evaluate-manifest-claims deterministic-replay-manifest results)
        by-id (into {} (map (juxt :claim/id identity)) claim-results)]
    (testing "single-run evidence is not enough for replay claims"
      (is (= :fail (get-in by-id [:claim/replay-identical-results :claim/outcome])))
      (is (= :fail (get-in by-id [:claim/hash-consistency-across-runs :claim/outcome])))
      (is (= :fail (get-in by-id [:claim/no-nondeterminism :claim/outcome])))
      (is (= [:insufficient-replay-runs]
             (:claim/evidence (get by-id :claim/replay-identical-results)))))))

(def partial-fill-manifest
  {:benchmark/claims [{:claim/id :claim/partial-fill-decision-integrity}
                      {:claim/id :claim/cap-adherence}]})

(def valid-partial-fill-decision
  {:decision/id "partial-fill-0123456789abcdef"
   :decision/hash "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
   :requested {:principal 100}
   :filled {:principal 60}
   :deferred {:principal 40}
   :haircut {}
   :unrealized {}
   :settlement-mode :partial-fill
   :policy {:mode :waterfall
            :rounding-policy :floor-and-carry
            :fill-order [:principal]}
   :evidence {:available-liquidity 60
              :shortage 40
              :total-requested 100
              :fill-mode :waterfall}})

(def force-authorisation-manifest
  {:benchmark/claims [{:claim/id :force-authorisation-exact-scope-single-use}
                      {:claim/id :held-custody-position-isolation}
                      {:claim/id :forensic-authorisation-custody-linkage}]})

(deftest bounded-custody-claims-are-not-exercised-without-mechanism-state
  (let [outcomes (into {}
                       (map (juxt :claim/id :claim/outcome))
                       (claims/evaluate-manifest-claims force-authorisation-manifest [{}]))]
    (is (= {:force-authorisation-exact-scope-single-use :not-exercised
            :held-custody-position-isolation :not-exercised
            :forensic-authorisation-custody-linkage :not-exercised}
           outcomes))))

(deftest bounded-custody-claims-pass-with-required-invariants
  (let [invariant-ids [:force-authorisations-lifecycle-consistent
                       :held-adjustments-reconstruct-total-held
                       :held-custody-closed-form
                       :held-partitions-non-negative
                       :terminal-workflow-custody-closed
                       :held-artifacts-derived-from-adjustments]
        result {:world {:force-authorisations {"fa-0" {:authorization/id "fa-0"}}
                        :force-authorisations/consumed {"fa-0" {:consumed? true}}
                        :held-adjustments [{:held-adjustment/id "held-adjustment-0"}]}
                :invariant-results (mapv (fn [id] {:id id :result :pass}) invariant-ids)}
        outcomes (into {}
                       (map (juxt :claim/id :claim/outcome))
                       (claims/evaluate-manifest-claims force-authorisation-manifest [result]))]
    (is (= {:force-authorisation-exact-scope-single-use :pass
            :held-custody-position-isolation :pass
            :forensic-authorisation-custody-linkage :pass}
           outcomes))))

(deftest partial-fill-claims-run-closed-form-checks
  (let [results [{:partial-fill-decisions [valid-partial-fill-decision]}]
        outcomes (into {}
                       (map (juxt :claim/id :claim/outcome))
                       (claims/evaluate-manifest-claims partial-fill-manifest results))]
    (is (= {:claim/partial-fill-decision-integrity :pass
            :claim/cap-adherence :pass}
           outcomes))))

(deftest partial-fill-claims-require-an-emitted-decision
  (let [outcomes (into {}
                       (map (juxt :claim/id :claim/outcome))
                       (claims/evaluate-manifest-claims partial-fill-manifest [{}]))]
    (is (= {:claim/partial-fill-decision-integrity :not-exercised
            :claim/cap-adherence :not-exercised}
           outcomes))))

(defn- reversal-result
  [scenario-id invariant-overrides]
  {:scenario/id scenario-id
   :outcome :pass
   :checks {:expectations {:ok? true}}
   :invariant-results (mapv (fn [id]
                              {:id id
                               :result (get invariant-overrides id :pass)})
                            [:slash-distribution-consistent
                             :resolver/balances-conserved
                             :conservation-of-funds])})

(deftest reversal-claims-verify-declared-coverage-and-conservation
  (let [scenario-ids ["DR-N-001-reversal-slash-appeal-lifecycle"
                      "DR-N-002-reversal-slash-appeal-rejected"
                      "DR-N-003-reversal-slash-appeal-window-expired"
                      "DR-N-004-reversal-slash-appeal-wrong-party"]
        results (mapv #(reversal-result % {}) scenario-ids)
        pass-result (claims/evaluate-claim
                     :claim/reversal-reviewer-due-process
                     {:benchmark/results results})
        conservation-failure (claims/evaluate-claim
                              :claim/reversal-reviewer-due-process
                              {:benchmark/results
                               (assoc results 0
                                      (reversal-result (first scenario-ids)
                                                       {:conservation-of-funds :fail}))})]
    (testing "a claim passes only when every registered scenario supplies the required checks"
      (is (= :pass (:claim/outcome pass-result)))
      (is (= 4 (count (:claim/assertions pass-result))))
      (is (every? :holds? (:claim/assertions pass-result))))
    (testing "conservation of funds is an explicit required invariant"
      (is (= :fail (:claim/outcome conservation-failure)))
      (is (= [:reversal-claim-scenario-failed]
             (:claim/evidence conservation-failure)))
      (is (= [:conservation-of-funds]
             (get-in conservation-failure [:claim/assertions 0 :invariants/failed]))))))
