(ns resolver-sim.demos.reorder-chain.demo-test
  "Tests for the 'reorder the evidence' demonstration (Demo B).

   The demonstration's claims are pinned to the real chain verifier: the
   baseline order is admitted, the reordered order is not, and exactly the
   position-commitment checks fail."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.demos.reorder-chain.assertions :as assertions]
            [resolver-sim.demos.reorder-chain.demo :as demo]
            [resolver-sim.demos.reorder-chain.scenario :as scenario]))

(deftest demo-model-is-complete
  (let [result (demo/run)]
    (is (= :admission/reordered-chain (:demo/id result)))
    (is (seq (:demo/question result)))
    (is (seq (:demo/explanation result)))
    (is (= :admitted (get-in result [:demo/expect :baseline])))
    (is (= :not-admitted (get-in result [:demo/expect :after-action])))))

(deftest baseline-is-admitted
  (let [result (demo/run)]
    (is (get-in result [:demo/baseline :admitted?])
        "the evidence in its verified order must be admitted")))

(deftest reordered-is-not-admitted
  (let [result (demo/run)]
    (is (not (get-in result [:demo/outcome :admitted?]))
        "the same evidence in a different order must not be admitted")
    (is (= [:chain-link-hash-mismatch :chain-link-hash-mismatch]
           (:failed-checks (:demo/outcome result)))
        "only the position-commitment checks fail, and both do")))

(deftest reorder-only-moves-content
  (let [hash->item (fn [h]
                     (some (fn [[item content]] (when (= content h) item))
                           scenario/evidence-content))
        records (scenario/baseline-records)
        reordered (scenario/reorder-records records)
        baseline-hashes (mapv :evidence/hash records)
        reordered-hashes (mapv :evidence/hash reordered)]
    (is (= #{:deposit :dispute :resolve}
           (set (mapv hash->item baseline-hashes)))
        "baseline uses exactly the three evidence items")
    (is (= #{:deposit :dispute :resolve}
           (set (mapv hash->item reordered-hashes)))
        "reorder keeps exactly the same three evidence items")
    (is (= (mapv :evidence/chain-seq records)
           (mapv :evidence/chain-seq reordered))
        "positions are unchanged")
    (is (= (mapv :evidence/chain-self-hash records)
           (mapv :evidence/chain-self-hash reordered))
        "committed position bindings are intentionally left untouched")))

(deftest evidence-carries-commitment
  (let [result (demo/run)
        evidence (:demo/evidence result)]
    (is (seq (:committed-hash evidence)))
    (is (seq (:lines evidence)))
    (is (seq (:after/checks evidence)))))

(deftest demo-is-deterministic
  (let [r1 (demo/run)
        r2 (demo/run)]
    (is (= (get-in r1 [:demo/baseline :admitted?])
           (get-in r2 [:demo/baseline :admitted?])))
    (is (= (get-in r1 [:demo/outcome :admitted?])
           (get-in r2 [:demo/outcome :admitted?])))
    (is (= (get-in r1 [:demo/evidence :committed-hash])
           (get-in r2 [:demo/evidence :committed-hash])))
    (is (= (:demo/explanation r1) (:demo/explanation r2)))))

(deftest assertions-hold
  (testing "the committed expectations pass"
    (let [{:keys [pass? failures]} (assertions/check)]
      (is pass? failures))))
