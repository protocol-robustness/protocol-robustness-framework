(ns resolver-sim.stochastic.params-test
  (:require [clojure.test :refer :all]
            [resolver-sim.stochastic.params :as params]))

;; ── from-snap tests (unchanged) ──────────────────────────────────────────────

(deftest from-snap-preserves-zero-and-false
  (testing "from-snap preserves zero and false values when keys are present"
    (let [snap {:escrow-fee-bps 0
                :reversal-slash-bps 0
                :reversal-detection-probability 0.0}
          out (params/from-snap snap)]
      (is (= 0 (:resolver-fee-bps out)))
      (is (= 0 (:reversal-slash-bps out)))
      (is (zero? (:reversal-detection-probability out))))))

;; ── protocol-params->mc-overrides ────────────────────────────────────────────

(deftest protocol-params-overrides-passthrough
  (testing "protocol-params->mc-overrides maps 7 fields 1:1, skips absent keys"
    (let [pp   {:resolver-fee-bps 200 :fraud-slash-bps 5000}
          over (params/protocol-params->mc-overrides pp)]
      (is (= 200 (:resolver-fee-bps over)))
      (is (= 5000 (:fraud-slash-bps over)))
      (is (nil? (:timeout-slash-bps over)) "absent key not invented"))))

(deftest empty-protocol-params-yields-empty-override
  (testing "nil or empty protocol-params produces no overrides"
    (is (= {} (params/protocol-params->mc-overrides nil)))
    (is (= {} (params/protocol-params->mc-overrides {})))))

;; ── scenario->mc-params: default fallback ─────────────────────────────────────

(deftest scenario-mc-params-default-fallback
  (testing "scenario with only protocol-params gets full default-params baseline"
    (let [scenario {:protocol-params {:resolver-fee-bps 150}}
          out      (params/scenario->mc-params scenario)]
      (is (= 150 (:resolver-fee-bps out))   "pp overrides default")
      (is (= 2.5 (:slash-multiplier out))    "falls back to default-params")
      (is (= 700 (:appeal-bond-bps out))     "falls back to default-params")
      (is (= 1000 (:n-trials out))           "falls back to default-params")
      (is (= :single-stage-ev (:fraud-model out)) "falls back to default-params"))))

;; ── scenario->mc-params: mc-params override ───────────────────────────────────

(deftest scenario-mc-params-override-wins
  (testing ":mc-params overrides protocol-params-derived value"
    (let [scenario {:protocol-params {:resolver-fee-bps 150}
                    :mc-params {:resolver-fee-bps 250}}
          out      (params/scenario->mc-params scenario)]
      (is (= 250 (:resolver-fee-bps out)) ":mc-params wins over pp"))))

;; ── scenario->mc-params: mc-only fields ───────────────────────────────────────

(deftest scenario-mc-params-mc-only-fields
  (testing ":mc-params can contain MC-only fields absent from protocol-params"
    (let [scenario {:mc-params {:strategy-mix {:honest 0.8 :malicious 0.2}
                                :fraud-detection-probability 0.25
                                :p-l1-reversal 0.75
                                :slash-multiplier 2.5
                                :oracle-roll-on-exhaustion :repeat-last}}
          out      (params/scenario->mc-params scenario)]
      (is (= {:honest 0.8 :malicious 0.2} (:strategy-mix out)))
      (is (= 0.25 (:fraud-detection-probability out)))
      (is (= 0.75 (:p-l1-reversal out)))
      (is (= 2.5 (:slash-multiplier out)))
      (is (= :repeat-last (:oracle-roll-on-exhaustion out))))))

;; ── Runtime override chain ───────────────────────────────────────────────────

(deftest runtime-override-wins
  (testing "runtime opts override scenario-derived params"
    (let [scenario  {:protocol-params {:resolver-fee-bps 150}}
          mc-params (params/scenario->mc-params scenario)
          ;; runtime layer (rightmost in merge, wins)
          params    (merge mc-params {:n-trials 500 :rng-seed 99 :resolver-fee-bps 300})]
      (is (= 500 (:n-trials params))           "runtime n-trials wins")
      (is (= 99 (:rng-seed params))            "runtime seed wins")
      (is (= 300 (:resolver-fee-bps params))   "runtime overrides pp + default"))))
