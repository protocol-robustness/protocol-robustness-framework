(ns resolver-sim.stochastic.liveness-failures-test
  "reveal-* tests for the liveness aggregate checks.  Each test proves a check
   surfaces a specific, machine-readable violation — the same discipline as the
   benchmark review-aggregate-check tests."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.stochastic.liveness-failures :as liveness]
            [resolver-sim.stochastic.rng :as rng]))

(defn- bored [difficulty limit cases interesting]
  (liveness/boredom-threshold difficulty limit cases interesting (rng/make-rng 1)))

;; ─────────────────────────────────────────────────────────────────────────────
;; check-latency-sensitivity
;; ─────────────────────────────────────────────────────────────────────────────

(deftest reveal-latency-spiral-risk
  (testing "low user retention surfaces ::spiral-risk (the SPIRAL_RISK signal)"
    (let [r (liveness/latency-sensitivity 1000 10 8 3)
          result (liveness/check-latency-sensitivity r)]
      (is (not (:holds? result)))
      (is (some #(= ::liveness/spiral-risk (:kind %)) (:violations result)))
      (is (true? (:liveness/spiral-risk? r))))))

(deftest latency-within-tolerance-holds
  (let [r (liveness/latency-sensitivity 100 100 1 30)
        result (liveness/check-latency-sensitivity r)]
    (is (:holds? result))
    (is (empty? (:violations result)))))

(deftest reveal-latency-system-saturated
  (testing "queue wait > 30 days surfaces ::system-saturated"
    (let [r (liveness/latency-sensitivity 100 1 5 1)
          result (liveness/check-latency-sensitivity r)]
      (is (some #(= ::liveness/system-saturated (:kind %)) (:violations result))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; check-participation-spiral
;; ─────────────────────────────────────────────────────────────────────────────

(deftest reveal-spiral-critical-pool
  (testing "a week where the resolver pool drops below 3 is surfaced"
    (let [entry {:week 5 :resolvers 4 :volume 100 :utilization 1.2
                 :wait-days 30 :new-resolvers 2 :new-volume 40}
          result (liveness/check-participation-spiral [entry])]
      (is (some #(= ::liveness/critical-pool-too-small (:kind %))
                (:violations result))))))

(deftest reveal-spiral-declining-volume
  (testing "volume below half the initial level surfaces ::declining-volume"
    (let [entry {:week 2 :resolvers 10 :volume 200 :utilization 0.4
                 :wait-days 3 :new-resolvers 9 :new-volume 60}
          result (liveness/check-participation-spiral [entry])]
      (is (some #(= ::liveness/declining-volume (:kind %)) (:violations result))))))

(deftest reveal-spiral-saturated-week
  (testing "a saturated week (utilization > 1.0) is surfaced"
    (let [entry {:week 0 :resolvers 10 :volume 100 :utilization 1.5
                 :wait-days 30 :new-resolvers 9 :new-volume 50}
          result (liveness/check-participation-spiral [entry])]
      (is (some #(= ::liveness/saturated-week (:kind %)) (:violations result))))))

(deftest spiral-without-failure-holds
  (let [healthy {:week 0 :resolvers 10 :volume 100 :utilization 0.5
                 :wait-days 3 :new-resolvers 10 :new-volume 100}]
    (is (:holds? (liveness/check-participation-spiral [healthy])))))

;; ─────────────────────────────────────────────────────────────────────────────
;; check-critical-mass
;; ─────────────────────────────────────────────────────────────────────────────

(deftest reveal-critical-mass-below-minimum
  (let [r (liveness/critical-mass-threshold 10 3 5)
        result (liveness/check-critical-mass r)]
    (is (= :liveness/below-minimum-viable (:liveness/risk r)))
    (is (some #(= ::liveness/below-minimum-viable (:kind %)) (:violations result)))
    (is (= 5 (:current (first (filter #(= ::liveness/below-minimum-viable (:kind %))
                                      (:violations result))))))))

(deftest reveal-critical-mass-danger-margin
  (let [r (liveness/critical-mass-threshold 20 3 21)
        result (liveness/check-critical-mass r)]
    (is (some #(= ::liveness/danger-low-margin (:kind %)) (:violations result)))))

(deftest critical-mass-healthy-holds
  (let [r (liveness/critical-mass-threshold 10 3 100)
        result (liveness/check-critical-mass r)]
    (is (:holds? result))
    (is (= :liveness/safe-healthy-margin (:liveness/risk r)))))

;; ─────────────────────────────────────────────────────────────────────────────
;; check-juror-participation
;; ─────────────────────────────────────────────────────────────────────────────

(deftest reveal-juror-strong-exit
  (let [r (liveness/juror-opportunity-cost 0.10 1.0 1.0 10)
        result (liveness/check-juror-participation r)]
    (is (some #(= ::liveness/strong-exit (:kind %)) (:violations result)))
    (is (false? (:willing? r)))))

(deftest juror-participation-holds
  (let [r (liveness/juror-opportunity-cost 0.05 10.0 1.0 1)
        result (liveness/check-juror-participation r)]
    (is (:holds? result))))

;; ─────────────────────────────────────────────────────────────────────────────
;; check-boredom-exit
;; ─────────────────────────────────────────────────────────────────────────────

(deftest reveal-boredom-critical-exit
  (let [r (bored 0.1 5 100 0.1)
        result (liveness/check-boredom-exit r)]
    (is (some #(= ::liveness/critical-exit-risk (:kind %)) (:violations result)))))

(deftest reveal-boredom-caution
  (let [r (bored 0.5 10 9 0.8)
        result (liveness/check-boredom-exit r)]
    (is (some #(= ::liveness/caution-dropout (:kind %)) (:violations result)))))

(deftest boredom-low-dropout-holds
  (let [r (bored 0.9 500 10 0.9)
        result (liveness/check-boredom-exit r)]
    (is (:holds? result))
    (is (= :liveness/low-dropout (:liveness/risk r)))))

;; ─────────────────────────────────────────────────────────────────────────────
;; check-adverse-selection
;; ─────────────────────────────────────────────────────────────────────────────

(deftest reveal-adverse-selection-pool-too-small
  (let [r (liveness/adverse-selection-effect 2 0.9 0.5)
        result (liveness/check-adverse-selection r)]
    (is (some #(= ::liveness/pool-too-small (:kind %)) (:violations result)))))

(deftest reveal-adverse-selection-high-bias
  (let [r (liveness/adverse-selection-effect 100 0.9 0.1)
        result (liveness/check-adverse-selection r)]
    (is (some #(= ::liveness/high-aggressive-bias (:kind %)) (:violations result)))))

(deftest adverse-selection-balanced-holds
  (testing "a pool with no dropout stays balanced (no bias) — check holds"
    (let [r (liveness/adverse-selection-effect 100 0.0 0.5)
          result (liveness/check-adverse-selection r)]
      (is (:holds? result)))))

;; ─────────────────────────────────────────────────────────────────────────────
;; all model results expose machine-readable :liveness/risk
;; ─────────────────────────────────────────────────────────────────────────────

(deftest model-results-expose-structured-risk
  (testing "every liveness model fn now emits a namespaced keyword verdict"
    (is (keyword? (:liveness/risk (liveness/juror-opportunity-cost 0.1 1 1 10))))
    (is (keyword? (:liveness/risk (bored 0.5 20 100 0.5))))
    (is (keyword? (:liveness/risk (liveness/adverse-selection-effect 10 0.9 0.5))))
    (is (keyword? (:liveness/risk (liveness/latency-sensitivity 1000 10 8 3))))
    (is (keyword? (:liveness/risk (liveness/critical-mass-threshold 10 3 5))))
    (is (keyword? (:liveness/risk (first (liveness/participation-spiral 20 100 0.6 0.5 1)))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Coverage property: every human verdict string has a deterministic structured
;; counterpart, and every non-benign state surfaces as a check violation.
;;
;; A verdict string -> :liveness/risk table is maintained per model fn.  For
;; each branch-reachable input, the produced verdict string must be present in
;; the table, its risk must equal the table's deterministic mapping, and any
;; non-benign string must yield at least one violation from the corresponding
;; check-* function.  This catches future additions that add a warning string
;; without wiring it into machine-readable evidence.
;; ─────────────────────────────────────────────────────────────────────────────

(def ^:private benign-risks
  "Risk keywords that represent benign states (no check violation expected)."
  #{:liveness/strong-participation
    :liveness/low-dropout
    :liveness/stable-balanced-pool
    :liveness/within-tolerance
    :liveness/safe-healthy-margin
    :liveness/normal})

(defn- run-coverage!
  "Run a coverage sweep: for each produced result assert the verdict string is
   table-covered, risk matches, and non-benign states surface as violations."
  [label result-fn verdict-key table check-fn arg-vectors]
  (doseq [args arg-vectors]
    (let [r (apply result-fn args)
          v (verdict-key r)
          risk (:liveness/risk r)]
      (testing (str label " " (pr-str args))
        (is (contains? table v)
            (str "verdict string has no structured mapping: " (pr-str v)))
        (is (= (get table v) risk)
            (str "risk does not match verdict string: " v " -> " risk))
        (when-not (contains? benign-risks risk)
          (is (not (:holds? (check-fn r)))
              (str "non-benign state must surface a violation: " (pr-str v))))))))

(deftest structured-verdicts-cover-juror-opportunity-cost
  (run-coverage!
   :juror liveness/juror-opportunity-cost :reason
   {"STRONG_EXIT: Severe opportunity cost" :liveness/strong-exit
    "MARGINAL: Barely not worth it" :liveness/marginal
    "MARGINAL: Weak incentive" :liveness/marginal-incentive
    "STRONG_PARTICIPATION: Good incentive" :liveness/strong-participation}
   liveness/check-juror-participation
   [[0.1 1 1 10] [0.1 1 1 1] [4 1 0 1] [0 10 0 1]]))

(deftest structured-verdicts-cover-boredom
  (run-coverage!
   :boredom #(liveness/boredom-threshold %1 %2 %3 %4 (rng/make-rng 1)) :verdict
   {"CRITICAL: Likely exit" :liveness/critical-exit-risk
    "SERIOUS: Significant exit risk" :liveness/serious-exit-risk
    "CAUTION: Some dropout expected" :liveness/caution-dropout
    "STABLE: Low dropout" :liveness/low-dropout}
   liveness/check-boredom-exit
   [[0.1 5 100 0.1] [0.5 20 16 0.5] [0.5 20 13 0.5] [0.9 20 10 0.9]]))

(deftest structured-verdicts-cover-adverse-selection
  (run-coverage!
   :adverse liveness/adverse-selection-effect :risk-verdict
   {"CRITICAL: Pool too small" :liveness/critical-pool-too-small
    "HIGH: Biased toward aggressive" :liveness/high-aggressive-bias
    "MODERATE: Some bias" :liveness/moderate-bias
    "STABLE: Balanced pool" :liveness/stable-balanced-pool}
   liveness/check-adverse-selection
   [[2 0.9 0.5] [100 0.9 0.1] [100 0.4 0.5] [100 0.0 0.5]]))

(deftest structured-verdicts-cover-latency
  (run-coverage!
   :latency liveness/latency-sensitivity :verdict
   {"OK: Within tolerance" :liveness/within-tolerance
    "SERIOUS: Latency problem" :liveness/serious-latency
    "SEVERE: Users leaving" :liveness/severe-users-leaving
    "CRITICAL: System broken" :liveness/system-saturated}
   liveness/check-latency-sensitivity
   [[84 1 1 30] [168 1 1 30] [224 1 1 30] [266 1 1 30] [336 1 1 30]]))

(deftest structured-verdicts-cover-critical-mass
  (run-coverage!
   :critical-mass liveness/critical-mass-threshold :status
   {"CRITICAL: Below minimum viable" :liveness/below-minimum-viable
    "DANGER: Low safety margin" :liveness/danger-low-safety-margin
    "CAUTION: Moderate safety margin" :liveness/caution-moderate-margin
    "SAFE: Healthy margin" :liveness/safe-healthy-margin}
   liveness/check-critical-mass
   [[10 3 5] [20 3 21] [20 3 28] [20 3 40]]))

(deftest structured-verdicts-cover-participation-spiral
  (testing "every entry status has a deterministic structured risk"
    (let [table {"NORMAL" :liveness/normal
                 "DECLINING" :liveness/declining
                 "SATURATED" :liveness/saturated
                 "CRITICAL: Pool too small" :liveness/critical-pool-too-small}
          history (into [] cat
                        (for [args [[40 200 0.4 1.0 30]
                                    [5 500 0.3 1.0 40]]]
                          (apply liveness/participation-spiral args)))]
      (is (seq history))
      (is (= (set (keys table)) (set (map :status history)))
          "the sweep must reach every status branch")
      (doseq [entry history]
        (let [v (:status entry)
              risk (:liveness/risk entry)]
          (is (contains? table v) (str "unmapped status: " (pr-str v)))
          (is (= (get table v) risk) (str v " -> " risk)))))))
