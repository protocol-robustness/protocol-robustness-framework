(ns resolver-sim.economics.calculations-test
  "Portability test: economic calculations work without Sew protocol code.
   Verifies that the core economic functions accept amounts and return
   results with no protocol state required."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.economics.calculations :as calc]))

(deftest bps-amount-calculates-correctly
  (is (= 50 (calc/calculate-bps-amount 1000 500)))
  (is (= 0 (calc/calculate-bps-amount 0 500)))
  (is (= 150 (calc/calculate-bps-amount 1000 1500))))

(deftest bps-fee-deducts-correctly
  ;; Returns map with :fee and :net keys
  (is (= {:fee 10, :net 990} (calc/calculate-bps-fee 1000 100)))
  (is (= {:fee 0, :net 1000} (calc/calculate-bps-fee 1000 0))))

(deftest bounty-returns-zero-for-non-positive-rate
  (is (= 20 (calc/calculate-bounty 1000 200)))
  (is (= 0 (calc/calculate-bounty 1000 0)))
  (is (= 0 (calc/calculate-bounty 1000 -50))))

(deftest slash-amount-calculates-correctly
  (is (= 25 (calc/calculate-slash-amount 1000 250)))
  (is (= 0 (calc/calculate-slash-amount 0 250)))
  (is (= 500 (calc/calculate-slash-amount 10000 500))))

(deftest capacity-limit-calculates-correctly
  (is (= 1000.0 (calc/calculate-capacity-limit 1000)))
  (is (= 1500.0 (calc/calculate-capacity-limit 1000 1.5)))
  (is (= 4000.0 (calc/calculate-capacity-limit 1000 4.0))))

(deftest no-sew-dependency
  (testing "calculations namespace does not depend on Sew protocol code"
    (let [ns-requires (keys (ns-imports 'resolver-sim.economics.calculations))]
      (is (not-any? #(re-find #"protocols\.sew" (str %)) ns-requires))
      (is (not-any? #(re-find #"sew" (str %)) ns-requires)))))

;; ── distribute-slashing-amount ────────────────────────────────────────────

(deftest distribute-basic
  (let [result (calc/distribute-slashing-amount 1000 {:bounty 0 :insurance-cut-bps 5000 :protocol-retained-bps 3000})]
    (is (= {:insurance 500 :protocol 300 :retained 200} result))))

(deftest distribute-with-bounty
  (let [result (calc/distribute-slashing-amount 1000 {:bounty 100 :insurance-cut-bps 5000 :protocol-retained-bps 3000})]
    (is (= {:insurance 450 :protocol 250 :retained 200} result))))

(deftest distribute-zero-amount
  (let [result (calc/distribute-slashing-amount 0 {:bounty 0 :insurance-cut-bps 5000 :protocol-retained-bps 3000})]
    (is (= {:insurance 0 :protocol 0 :retained 0} result))))

(deftest distribute-max-bps
  (let [result (calc/distribute-slashing-amount 1000 {:bounty 0 :insurance-cut-bps 10000 :protocol-retained-bps 0})]
    (is (= {:insurance 1000 :protocol 0 :retained 0} result))))

(deftest distribute-invalid-negative-amount
  (is (thrown? AssertionError (calc/distribute-slashing-amount -100 {:bounty 0 :insurance-cut-bps 5000 :protocol-retained-bps 3000}))))

(deftest distribute-invalid-negative-bounty
  (is (thrown? AssertionError (calc/distribute-slashing-amount 1000 {:bounty -1 :insurance-cut-bps 5000 :protocol-retained-bps 3000}))))

(deftest distribute-invalid-negative-bps
  (is (thrown? AssertionError (calc/distribute-slashing-amount 1000 {:bounty 0 :insurance-cut-bps -500 :protocol-retained-bps 3000}))))

(deftest distribute-invalid-bps-over-10000
  (is (thrown? AssertionError (calc/distribute-slashing-amount 1000 {:bounty 0 :insurance-cut-bps 15000 :protocol-retained-bps 3000}))))

(deftest distribute-exceeds-max-total-bps
  (is (thrown? AssertionError (calc/distribute-slashing-amount 1000 {:bounty 0 :insurance-cut-bps 8000 :protocol-retained-bps 3000}))
      "insurance-cut-bps + protocol-retained-bps must not exceed 10000"))

(deftest distribute-conservation
  (let [result (calc/distribute-slashing-amount 10000 {:bounty 200 :insurance-cut-bps 4000 :protocol-retained-bps 3000})
        total (+ (:insurance result) (:protocol result) (:retained result))
        bounty-redistributed (* 2 (quot 200 2))]
    (is (= total (- 10000 bounty-redistributed)) "insurance+protocol+retained = amount - redistributed bounty")))

(deftest sew-equivalence
  (testing "core function with Sew default parameters matches Sew implementation output"
    (let [sew-output {:insurance 500 :protocol 300 :retained 200}
          core-output (calc/distribute-slashing-amount 1000 {:bounty 0 :insurance-cut-bps 5000 :protocol-retained-bps 3000})]
      (is (= sew-output core-output)))
    (let [sew-output {:insurance 450 :protocol 250 :retained 200}
          core-output (calc/distribute-slashing-amount 1000 {:bounty 100 :insurance-cut-bps 5000 :protocol-retained-bps 3000})]
      (is (= sew-output core-output)))
    (let [sew-output {:insurance 0 :protocol 0 :retained 0}
          core-output (calc/distribute-slashing-amount 0 {:bounty 0 :insurance-cut-bps 5000 :protocol-retained-bps 3000})]
      (is (= sew-output core-output)))))
