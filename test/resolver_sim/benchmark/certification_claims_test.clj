(ns resolver-sim.benchmark.certification-claims-test
  "Adversarial tests for P0.0: benchmark certification must bind the evaluated
   claims so that claims can no longer change without changing the certification
   hash.

   The certification built by build-certification commits the required claim-set
   root, the evaluated claim-outcome root, and a fail-closed required-claims-covered?
   flag. A change to any required claim, evaluated outcome, claim scope, or
   scenario therefore MUST change :certification-hash. These tests prove that
   mutation is detected (not just that an aggregate passes)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [resolver-sim.benchmark.runner :as runner]
            [resolver-sim.benchmark.repo :as repo]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.invariants :as sew-inv]
            [resolver-sim.vcs :as vcs]))

(defn- manifest-with-claims [claims]
  {:benchmark/id :benchmark/test :benchmark/claims claims})

(defn- summary [& [overrides]]
  (merge {:scenario-count 2 :all-invariants-pass true :invariant-summary {}}
         overrides))

(defn- cert-of
  ([manifest claim-results] (cert-of manifest (summary) claim-results))
  ([manifest summary claim-results]
   (runner/build-certification manifest summary claim-results)))

(deftest certification-commits-claim-fields
  (testing "certification now binds the required claim-set, claim-outcomes, and coverage"
    (let [cert (cert-of (manifest-with-claims [:claim/a :claim/b])
                        [{:claim/id :claim/a :claim/outcome :pass}
                         {:claim/id :claim/b :claim/outcome :pass}])]
      (is (true? (:required-claims-covered? cert)))
      (is (string? (:claim-set/root cert)) ":claim-set/root is a committed root")
      (is (= 64 (count (:claim-set/root cert))))
      (is (string? (:claim-outcome/root cert)) ":claim-outcome/root is a committed root")
      (is (= 64 (count (:claim-outcome/root cert)))))))

(deftest changing-a-claim-outcome-changes-the-certification
  (testing "flipping a claim outcome alters :claim-outcome/root and :certification-hash"
    (let [manifest (manifest-with-claims [:claim/a :claim/b])
          passing [{:claim/id :claim/a :claim/outcome :pass}
                   {:claim/id :claim/b :claim/outcome :pass}]
          failing [{:claim/id :claim/a :claim/outcome :pass}
                   {:claim/id :claim/b :claim/outcome :fail}]
          cert-pass (cert-of manifest passing)
          cert-fail (cert-of manifest failing)]
      (is (not= (:claim-outcome/root cert-pass) (:claim-outcome/root cert-fail)))
      (is (not= (:certification-hash cert-pass) (:certification-hash cert-fail))
          "a :fail claim must not certify identically to a :pass claim"))))

(deftest missing-required-claim-changes-the-certification
  (testing "a required claim that vanishes from the results must change the certification"
    (let [manifest (manifest-with-claims [:claim/a :claim/b])
          complete [{:claim/id :claim/a :claim/outcome :pass}
                    {:claim/id :claim/b :claim/outcome :pass}]
          missing  [{:claim/id :claim/a :claim/outcome :pass}]
          cert-complete (cert-of manifest complete)
          cert-missing  (cert-of manifest missing)]
      (is (true? (:required-claims-covered? cert-complete)))
      (is (false? (:required-claims-covered? cert-missing)))
      (is (not= (:certification-hash cert-complete) (:certification-hash cert-missing))
          "dropping a required claim must not be masked by an unchanged count"))))

(deftest extra-unexpected-claim-changes-the-certification
  (testing "an unexpected evaluated claim must fail coverage and change the certification"
    (let [manifest (manifest-with-claims [:claim/a :claim/b])
          expected [{:claim/id :claim/a :claim/outcome :pass}
                    {:claim/id :claim/b :claim/outcome :pass}]
          extra    (conj expected {:claim/id :claim/extra :claim/outcome :pass})
          cert-expected (cert-of manifest expected)
          cert-extra    (cert-of manifest extra)]
      (is (true? (:required-claims-covered? cert-expected)))
      (is (false? (:required-claims-covered? cert-extra)))
      (is (not= (:certification-hash cert-expected) (:certification-hash cert-extra))))))

(deftest changing-the-required-claim-set-changes-the-certification
  (testing "declaring a different required claim set must alter :claim-set/root and the hash"
    (let [results [{:claim/id :claim/a :claim/outcome :pass}
                   {:claim/id :claim/b :claim/outcome :pass}]
          cert-ab (cert-of (manifest-with-claims [:claim/a :claim/b]) results)
          cert-ac (cert-of (manifest-with-claims [:claim/a :claim/c]) results)]
      (is (not= (:claim-set/root cert-ab) (:claim-set/root cert-ac)))
      (is (not= (:certification-hash cert-ab) (:certification-hash cert-ac))))))

(deftest scenario-scoped-claim-mutation-changes-the-certification
  (testing "adding a scenario-scoped claim result must change the certification"
    (let [manifest (manifest-with-claims [:claim/a])
          base [{:claim/id :claim/a :claim/outcome :pass}]
          scenario (conj base {:claim/id :claim/a :claim/outcome :pass
                               :claim/scope :scenario :scenario/id "S1"})
          cert-base (cert-of manifest base)
          cert-scenario (cert-of manifest scenario)]
      (is (not= (:claim-outcome/root cert-base) (:claim-outcome/root cert-scenario)))
      (is (not= (:certification-hash cert-base) (:certification-hash cert-scenario))))))

(deftest empty-claim-selection-is-fail-closed
  (testing "an empty required set with results fails coverage; empty with no results covers vacuously"
    (let [no-claims-manifest (manifest-with-claims nil)]
      (is (true? (:required-claims-covered?
                  (cert-of no-claims-manifest []))))
      (is (false? (:required-claims-covered?
                   (cert-of no-claims-manifest
                            [{:claim/id :claim/a :claim/outcome :pass}])))))))

(deftest identical-claims-produce-identical-certification
  (testing "same claims/summary deterministically reproduce the same certification hash"
    (let [manifest (manifest-with-claims [:claim/a :claim/b])
          results  [{:claim/id :claim/a :claim/outcome :pass}
                    {:claim/id :claim/b :claim/outcome :pass}]]
      (is (= (:certification-hash (cert-of manifest results))
             (:certification-hash (cert-of manifest results)))))))

(deftest run-benchmark-binds-claims-into-certification
  (testing "a produced evidence bundle certifies its evaluated claims; mutating any claim outcome must change the certification hash"
    (with-redefs [repo/metadata (fn [] {:repo {:commit "test-commit" :dirty? false}})
                  vcs/source-provenance (fn [] {:git-commit-sha "sha256:test-commit"
                                                :source/hash "sha256:test-source-hash"
                                                :source/hash-algorithm "source-tree-hash-v1"
                                                :source/hash-roots []
                                                :code-hash "sha256:test-code-hash"
                                                :deps-hash "sha256:test-deps-hash"
                                                :input-hash "sha256:test-input-hash"
                                                :dirty? false})
                  sew/replay-with-sew-protocol (fn [_scenario _opts]
                                                 {:events-processed 3
                                                  :outcome :pass
                                                  :halt-reason nil
                                                  :metrics {:invariant-results {}}
                                                  :world {:status :ok}})
                  sew-inv/check-all (fn [_world] {:results {}})]
      (let [evidence (runner/run-benchmark "benchmarks/packs/prf-core/deterministic-replay-v1.edn")
            stored (:benchmark-certification evidence)
            manifest (:benchmark evidence)
            claim-results (:claim-results evidence)
            rebuild (fn [results]
                      (runner/build-certification
                       manifest
                       {:scenario-count (:scenario-count stored)
                        :all-invariants-pass (:all-invariants-pass stored)
                        :invariant-summary (:invariant-summary stored)}
                       results))]
        (is (seq claim-results) "benchmark evaluates its declared claims")
        (is (true? (:required-claims-covered? stored)))
        (is (string? (:claim-outcome/root stored)))
        ;; Rebuild from the persisted evidence with identical inputs reproduces the
        ;; stored certification hash (run-benchmark uses build-certification).
        (is (= (:certification-hash stored)
               (:certification-hash (rebuild claim-results)))
            "certification is a deterministic pure function of the evidence")
        ;; Mutating a single evaluated claim outcome MUST change the certification hash.
        (let [flip-first (fn [results]
                           (let [flipped (update (first results) :claim/outcome
                                                 #(if (= :pass %) :fail :pass))]
                             (cons flipped (rest results))))
              mutated (flip-first claim-results)]
          (is (not= (:certification-hash stored)
                    (:certification-hash (rebuild mutated)))
              "a mutated claim outcome must change the certification hash"))))))