(ns resolver-sim.economics.terminal-payoff-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.economics.terminal-payoff :as tp]))

(deftest coalition-ev-basic
  (testing "coalition-ev-from-payoff sums member payoffs"
    (let [members [{:resolver-id :r1 :net-payoff 100}
                   {:resolver-id :r2 :net-payoff 60}]
          result (tp/coalition-ev-from-payoff members)]
      (is (= 160 (:coalition-total result)))
      (is (= 160 (:net-of-costs result))))))

(deftest coalition-ev-with-coordination-cost
  (testing "coalition-ev-from-payoff deducts coordination cost"
    (let [members [{:resolver-id :r1 :net-payoff 100}
                   {:resolver-id :r2 :net-payoff 60}]
          result (tp/coalition-ev-from-payoff members :coordination-cost 20)]
      (is (= 160 (:coalition-total result)))
      (is (= 140 (:net-of-costs result))))))

(deftest coalition-ev-with-side-payments
  (testing "coalition-ev-from-payoff applies side payments"
    (let [members [{:resolver-id :r1 :net-payoff 100}
                   {:resolver-id :r2 :net-payoff 20}]
          result (tp/coalition-ev-from-payoff members
                                              :coordination-cost 5
                                              :side-payments [{:from :r1 :to :r2 :amount 15}])]
      (is (= 120 (:coalition-total result)))
      (is (= 115 (:net-of-costs result)))
      (is (= 85 (get-in (:member-payoffs result) [0 :after-side-payment])))
      (is (= 35 (get-in (:member-payoffs result) [1 :after-side-payment]))))))

(deftest coalition-ev-empty
  (testing "coalition-ev-from-payoff with empty members"
    (let [result (tp/coalition-ev-from-payoff [])]
      (is (= 0 (:coalition-total result)))
      (is (= 0 (:net-of-costs result))))))

(deftest incentive-margin-honest-preferred
  (testing "incentive-margin returns pass when honest beats malice"
    (let [result (tp/incentive-margin :honest-ev 100 :malicious-ev 80)]
      (is (= :pass (:verdict result)))
      (is (= 20 (:margin result)))
      (is (= :malicious (:deviation-type result))))))

(deftest incentive-margin-malicious-wins
  (testing "incentive-margin returns fail when malice beats honest"
    (let [result (tp/incentive-margin :honest-ev 50 :malicious-ev 80)]
      (is (= :fail (:verdict result)))
      (is (= -30 (:margin result))))))

(deftest incentive-margin-multiple-deviations
  (testing "incentive-margin finds worst deviation among multiple types"
    (let [result (tp/incentive-margin :honest-ev 100 :malicious-ev 120 :lazy-ev 90)]
      (is (= :fail (:verdict result)))
      (is (= -20 (:margin result)))
      (is (= :malicious (:deviation-type result))))))

(deftest ir-check-passes
  (testing "ir-check returns rational? true when net >= outside-option"
    (let [result (tp/ir-check 100)]
      (is (true? (:rational? result)))
      (is (= 100 (:net result))))))

(deftest ir-check-fails
  (testing "ir-check returns rational? false when net < outside-option"
    (let [result (tp/ir-check 5 :outside-option 10)]
      (is (false? (:rational? result)))
      (is (= 5 (:deficit result))))))

(deftest ir-check-binds-definition-root
  (testing "ir-check carries the outside-option definition root when provided"
    (let [result (tp/ir-check 100 :outside-option 5 :definition-root "oo-root-0")]
      (is (true? (:rational? result)))
      (is (= "oo-root-0" (:definition-root result)))))
  (testing "ir-check omits definition root when not provided"
    (let [result (tp/ir-check 100)]
      (is (not (contains? result :definition-root))))))

(deftest budget-balance-check-passes
  (testing "budget-balance-check returns balanced? true when sum = 0"
    (let [result (tp/budget-balance-check [{:role :resolver :net 100}
                                           {:role :protocol :net -100}])]
      (is (true? (:balanced? result)))
      (is (= 0 (:imbalance result))))))

(deftest budget-balance-check-fails
  (testing "budget-balance-check returns balanced? false when sum != 0"
    (let [result (tp/budget-balance-check [{:role :resolver :net 100}
                                           {:role :protocol :net -50}])]
      (is (false? (:balanced? result)))
      (is (= 50 (:imbalance result))))))

(deftest budget-balance-check-with-epsilon
  (testing "budget-balance-check with epsilon tolerance"
    (let [result (tp/budget-balance-check [{:role :resolver :net 100}
                                           {:role :protocol :net -99}]
                                          :epsilon 1)]
      (is (true? (:balanced? result))))))

;; ── Appeal expected-value model ──────────────────────────────────────────────

(deftest appeal-ev-correct-resolver-positive-margin
  (testing "a correct resolver has positive margin when P(uphold) > breakeven"
    ;; slash=1000 bond=500 → breakeven = 500/1500 = 0.333
    ;; accuracy=0.7 > 0.333 → should appeal
    (let [r (tp/appeal-ev 1000 500 true)]
      (is (true? (:should-appeal? r)))
      (is (pos? (:margin r)))
      (is (= 0.3333333333333333 (:breakeven-uphold-prob r))))))

(deftest appeal-ev-wrong-resolver-negative-margin
  (testing "a wrong resolver has negative margin when P(uphold) < breakeven"
    ;; slash=1000 bond=500 → breakeven = 0.333
    ;; error-rate=0.3 < 0.333 → should NOT appeal
    (let [r (tp/appeal-ev 1000 500 false)]
      (is (false? (:should-appeal? r)))
      (is (neg? (:margin r))))))

(deftest appeal-ev-zero-slash-never-appeals
  (testing "with no slash at stake, appealing only risks the bond (expected loss, not full bond)"
    (let [r (tp/appeal-ev 0 500 true)]
      (is (false? (:should-appeal? r)))
      ;; EV = P(reject=0.3) × (-(0+500)) = -150 (bond at risk only when rejected)
      (is (< (Math/abs (- -150.0 (:appeal-ev r))) 1e-9))
      (is (< (Math/abs (:no-appeal-ev r)) 1e-9)))))

(deftest appeal-ev-zero-bond-always-appeals
  (testing "with no bond cost, appealing is free (rational even for a wrong resolver)"
    (let [r (tp/appeal-ev 1000 0 false)]
      (is (true? (:should-appeal? r)))
      (is (zero? (:breakeven-uphold-prob r))))))

(deftest appeal-indifference-threshold-verdicts
  (testing "appeal-indifference-threshold classifies the regimes"
    ;; bond = slash → correct can appeal (0.7>0.5), wrong cannot (0.3<0.5)
    (let [safe (tp/appeal-indifference-threshold 10000 :slash-bps 2500 :appeal-bond-bps 2500)
          ;; bond << slash → both can appeal (breakeven 0.107 < both 0.7 and 0.3)
          under (tp/appeal-indifference-threshold 10000 :slash-bps 2500 :appeal-bond-bps 300)
          ;; bond >> slash → neither can appeal (breakeven 0.737 > 0.7)
          blocked (tp/appeal-indifference-threshold 10000 :slash-bps 2500 :appeal-bond-bps 7000)
          ;; pathological governance: error-rate > accuracy → wrong incentivized while correct blocked
          pathological (tp/appeal-indifference-threshold 10000 :slash-bps 2500 :appeal-bond-bps 3000
                                                         :governance-accuracy 0.3 :governance-error-rate 0.7)]
      (is (= :safe (:verdict safe)))
      (is (= :both (:verdict under)))
      (is (= :correct-blocked (:verdict blocked)))
      (is (= :wrong-incentivized (:verdict pathological))))))

(deftest appeal-calibration-window-default
  (testing "default governance (a=0.7, e=0.3) at slash-bps 2500 gives the safe window"
    (let [r (tp/appeal-calibration-window)]
      (is (< (Math/abs (- 0.4285714285714286 (:min-bond-slash-ratio r))) 1e-9))
      (is (< (Math/abs (- 2.3333333333333335 (:max-bond-slash-ratio r))) 1e-9))
      (is (= 1071 (:min-appeal-bond-bps r)))
      (is (= 5833 (:max-appeal-bond-bps r))))))

(deftest appeal-calibration-window-boundary-verification
  (testing "the three S-DR scenarios land correctly relative to the safe window"
    (let [win (tp/appeal-calibration-window)
          min-ratio (:min-bond-slash-ratio win)
          max-ratio (:max-bond-slash-ratio win)
          check (fn [bond-bps]
                  (let [ratio (/ bond-bps 2500.0)]
                    {:ratio ratio
                     :below-deterrence? (< ratio min-ratio)
                     :above-access? (> ratio max-ratio)}))
          safe (check 2500)     ;; bond = slash → inside window
          under (check 300)     ;; below window → wrong resolvers incentivized
          blocked (check 7000)] ;; above window → correct resolvers blocked
      (is (not (:below-deterrence? safe)))
      (is (not (:above-access? safe)))
      (is (:below-deterrence? under))
      (is (:above-access? blocked)))))

;; ── Slow-resolver griefing cost ─────────────────────────────────────────────

(deftest slow-resolver-griefing-cost-annual-rate
  (testing "a full-year stall costs the annual rate of the escrow"
    (let [r (tp/slow-resolver-griefing-cost
             :escrow-wei 100000 :stall-seconds 31536000
             :response-window-seconds 0)]
      (is (= 5000 (:capital-cost-wei r))
          "100000 * 5% per year = 5000"))
    (let [r (tp/slow-resolver-griefing-cost
             :escrow-wei 100000 :stall-seconds 31536000
             :response-window-seconds 0 :capital-cost-rate-bps 1000)]
      (is (= 10000 (:capital-cost-wei r))
          "100000 * 10% per year = 10000"))))

(deftest slow-resolver-griefing-cost-incremental-beyond-window
  (testing "incremental cost is the delay beyond the agreed response window"
    ;; 1M escrow, 30-day stall, 1-day window → griefing beyond the window
    (let [r (tp/slow-resolver-griefing-cost
             :escrow-wei 1000000 :stall-seconds 2592000
             :response-window-seconds 86400 :max-dispute-duration-seconds 2592000)]
      (is (pos? (:capital-cost-wei r)))
      (is (pos? (:incremental-cost-wei r)))
      (is (< (:incremental-cost-wei r) (:capital-cost-wei r))
          "incremental excludes the agreed window")))
  (testing "stall within the window has no incremental griefing"
    (let [r (tp/slow-resolver-griefing-cost
             :escrow-wei 100000 :stall-seconds 30 :response-window-seconds 60)]
      (is (zero? (:incremental-cost-wei r))))))

(deftest slow-resolver-griefing-cost-disabled-window
  (testing "disabled window (0) means no incremental griefing is attributed"
    (let [r (tp/slow-resolver-griefing-cost
             :escrow-wei 100000 :stall-seconds 3600 :response-window-seconds 0)]
      (is (zero? (:incremental-cost-wei r)))
      (is (zero? (:response-window-seconds r))))))

(deftest slow-resolver-griefing-cost-zero-escrow
  (testing "zero escrow produces zero cost"
    (let [r (tp/slow-resolver-griefing-cost
             :escrow-wei 0 :stall-seconds 3600)]
      (is (zero? (:capital-cost-wei r)))
      (is (zero? (:incremental-cost-wei r))))))
