(ns resolver-sim.demos.public.provenance-test
  "P0 mutation tests: a valid public artifact cannot contain individually
   correct facts assembled from different executions.

   Each test simulates the two classic projection-bug classes and asserts the
   shared validator rejects them:

   - cross-run splicing: rows/facts from one run combined with totals or
     conservation from another (arithmetic no longer reconciles);
   - provenance swap: a result-root or input-root copied from a different
     execution (the bound identity no longer matches the evidence).

   These are the mechanical guarantee behind 'single-source provenance' and
   'the website never strengthens what PRF proved'."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.demos.public.validate :as v]

            [resolver-sim.demos.public.liquidity-shortfall :as liquidity]
            [resolver-sim.demos.public.reordered-evidence :as reordered]))

(defn- rejects?
  "True if validate-artifact! throws on the given artifact."
  [artifact]
  (try
    (v/validate-artifact! artifact)
    false
    (catch clojure.lang.ExceptionInfo _ true)))

(deftest liquidity-splices-are-rejected
  (let [artifact (liquidity/project)]
    (testing "row allocated changed -> total no longer reconciles"
      (is (rejects?
           (assoc-in artifact ["scenario" "requests" 0 "allocated"] 36))))
    (testing "row shortfall changed -> conservation shortfall no longer reconciles"
      (is (rejects?
           (assoc-in artifact ["scenario" "requests" 2 "shortfall"] 7))))
    (testing "conservation from another run (requested 110, not 100)"
      (is (rejects?
           (assoc-in artifact ["conservation" "requested"] 110))))
    (testing "result-root from another execution"
      (is (rejects?
           (assoc-in artifact ["source" "result-root"] "sha256:deadbeef"))))
    (testing "input-root from another execution"
      (is (rejects?
           (assoc-in artifact ["source" "input-root"] "sha256:deadbeef"))))))

(deftest reordered-evidence-splices-are-rejected
  (let [artifact (reordered/project)]
    (testing "failed-check list removed while outcome stays rejected"
      (is (rejects?
           (assoc artifact "outcome"
                  (assoc (get artifact "outcome")
                         "failed-checks" [])))))
    (testing "outcome flips to admitted while evidence still fails"
      (is (rejects?
           (assoc-in artifact ["outcome" "admitted"] true))))
    (testing "result-root from another execution"
      (is (rejects?
           (assoc-in artifact ["source" "result-root"] "sha256:deadbeef"))))))

(deftest pristine-artifacts-pass
  (testing "the real projected artifacts are self-consistent"

    (is (not (rejects? (liquidity/project))))
    (is (not (rejects? (reordered/project))))))
