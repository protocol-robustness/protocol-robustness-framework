(ns resolver-sim.demos.not-admitted.demo-test
  "Tests for the 'not admitted' demonstration (Demo A: tamper with the amount).

   The demonstration's claims are only as good as the verifier they run, so
   these tests pin the exact outcome: baseline admitted, after-action rejected,
   and precisely one failing check (the evidence signature)."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.demos.not-admitted.assertions :as assertions]
            [resolver-sim.demos.not-admitted.demo :as demo]
            [resolver-sim.demos.not-admitted.scenario :as scenario]))

(deftest demo-model-is-complete
  (let [result (demo/run)]
    (is (= :admission/tampered-amount (:demo/id result)))
    (is (seq (:demo/question result)))
    (is (seq (:demo/explanation result)))
    (is (= :admitted (get-in result [:demo/expect :baseline])))
    (is (= :not-admitted (get-in result [:demo/expect :after-action])))))

(deftest baseline-is-admitted
  (let [result (demo/run)]
    (is (get-in result [:demo/baseline :admitted?])
        "the untouched evidence must be admitted")))

(deftest after-change-is-not-admitted
  (let [result (demo/run)]
    (is (not (get-in result [:demo/outcome :admitted?]))
        "the same check must reject the changed evidence")
    (is (= [:held-custody/hash-integrity]
           (:failed-checks (:demo/outcome result)))
        "exactly one thing must fail: the evidence signature")))

(deftest evidence-carries-committed-signature
  (let [result (demo/run)
        evidence (:demo/evidence result)]
    (is (seq (:committed-hash evidence)))
    (is (seq (:lines evidence)))
    (is (= 7 (count (:after/checks evidence)))
        "the full closed-form check battery runs on the changed evidence")))

(deftest change-only-mutates-amount-and-balance
  (let [artifacts (scenario/baseline-artifacts (scenario/baseline-adjustments))
        changed (scenario/change-recorded-amount artifacts 1100)]
    (is (= 1100 (:amount (first changed))))
    (is (= 1100 (:held/after (first changed))))
    (is (= (:artifact/hash (first artifacts)) (:artifact/hash (first changed)))
        "the committed signature is intentionally left untouched")
    (is (= (:held-adjustment/id (first artifacts)) (:held-adjustment/id (first changed))))))

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
