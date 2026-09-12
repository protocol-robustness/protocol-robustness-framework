(ns resolver-sim.sim.strategy-adaptation-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.sim.defection :as d]
            [resolver-sim.sim.stochastic-equilibrium :as se]
            [resolver-sim.stochastic.rng :as rng]
            [resolver-sim.stochastic.evidence-costs :as ec]))

(deftest load-optimal-selector-can-produce-lazy
  (testing "under heavy load, load-optimal selector can move resolvers to :lazy"
    (let [resolver-count 8
          histories (into {}
                          (map (fn [i]
                                 [(str "resolver-" i)
                                  {:strategy :honest
                                   :epoch-history {:epoch-1 {:trials 8 :profit 10.0}}}])
                               (range resolver-count)))
          {:keys [updated-histories defection-events diagnostics]}
          (d/apply-strategy-defection
           (rng/make-rng 7)
           histories
           1
           {:n-trials-per-epoch 2000
            :escrow-size 10000
            :resolver-fee-bps 150
            :slashing-detection-probability 0.1
            :slash-multiplier 2.0
            :strategy-adaptation {:enabled true
                                  :rate 1.0
                                  :selector :load-optimal
                                  :allowed-targets #{:honest :lazy :malicious}}
            :strategy-space #{:honest :lazy :malicious}})
          final-strategies (set (map :strategy (vals updated-histories)))]
      (is (empty? diagnostics))
      (is (contains? final-strategies :lazy))
      (is (some #(= :lazy (:to %)) defection-events))
      (is (every? #(= :load-optimal (:selector %)) defection-events)))))

(deftest load-optimal-lazy-persists-in-final-mix
  (testing "when lazy is allowed, lazy remains countable in final strategy mix"
    (let [histories {"resolver-1" {:strategy :honest :epoch-history {:epoch-1 {:trials 5 :profit 5.0}}}
                     "resolver-2" {:strategy :malicious :epoch-history {:epoch-1 {:trials 5 :profit 5.0}}}}
          {:keys [updated-histories]}
          (d/apply-strategy-defection
           (rng/make-rng 13)
           histories
           1
           {:n-trials-per-epoch 2000
            :strategy-adaptation {:enabled true
                                  :rate 1.0
                                  :selector :load-optimal
                                  :allowed-targets #{:honest :lazy :malicious}}
            :strategy-space #{:honest :lazy :malicious}})
          final-mix (frequencies (map :strategy (vals updated-histories)))]
      (is (pos? (get final-mix :lazy 0))))))

(deftest difficulty-weighted-accuracy
  (testing "expected-accuracy uses difficulty distribution correctly"
    (let [dist (ec/default-difficulty-distribution)
          h-acc (ec/expected-accuracy :honest dist)
          l-acc (ec/expected-accuracy :lazy dist)
          m-acc (ec/expected-accuracy :malicious dist)]
      (is (<= 0 h-acc 1))
      (is (< l-acc h-acc) "lazy accuracy below honest")
      (is (< m-acc l-acc) "malicious accuracy below lazy"))))

(deftest optimal-strategy-changes-under-load
  (testing "light load selects honest, heavy load selects lazy in evidence model"
    (let [light-decision (ec/optimal-strategy-under-load (rng/make-rng 1) 10 100 0.10 2.0 150.0)
          heavy-decision (ec/optimal-strategy-under-load (rng/make-rng 1) 500 100 0.10 2.0 150.0)]
      (is (= :honest (:optimal-strategy light-decision)))
      (is (= :lazy (:optimal-strategy heavy-decision)))
      (is (contains? light-decision :strategy-payoffs))
      (is (contains? heavy-decision :strategy-payoffs))
      (is (= :light (:load-level light-decision)))
      (is (= :extreme (:load-level heavy-decision))))))

(deftest unsupported-optimal-strategy-is-diagnostic
  (testing "if optimal strategy is lazy but lazy is disallowed, adaptation emits diagnostics"
    (let [histories {"resolver-07" {:strategy :honest
                                    :epoch-history {:epoch-3 {:trials 12 :profit 9.0}}}}
          {:keys [updated-histories defection-events diagnostics]}
          (d/apply-strategy-defection
           (rng/make-rng 11)
           histories
           3
           {:n-trials-per-epoch 2000
            :strategy-adaptation {:enabled true
                                  :rate 1.0
                                  :selector :load-optimal
                                  :allowed-targets #{:honest :malicious}}
            :strategy-space #{:honest :malicious}})]
      (is (empty? defection-events))
      (is (= :honest (get-in updated-histories ["resolver-07" :strategy])))
      (is (= :target-outside-strategy-space (-> diagnostics first :reason))))))

(deftest transition-summary-is-strategy-general
  (testing "summary emits transition matrix and initial/final strategy mix"
    (let [events [{:resolver-id "r1" :from :honest :to :lazy}
                  {:resolver-id "r2" :from :lazy :to :honest}
                  {:resolver-id "r3" :from :honest :to :malicious}]
          before {"r1" {:strategy :honest}
                  "r2" {:strategy :lazy}
                  "r3" {:strategy :honest}}
          after  {"r1" {:strategy :lazy}
                  "r2" {:strategy :honest}
                  "r3" {:strategy :malicious}}
          summary (d/defection-summary events before after [] {:selector :load-optimal :defaults-used #{}})]
      (is (= 1 (get-in summary [:strategy-transitions [:honest :lazy]])))
      (is (= 1 (get-in summary [:strategy-transitions [:lazy :honest]])))
      (is (= 1 (get-in summary [:strategy-transitions [:honest :malicious]])))
      (is (= {:honest 2 :lazy 1} (get-in summary [:strategy-mix :initial])))
      (is (= {:lazy 1 :honest 1 :malicious 1} (get-in summary [:strategy-mix :final]))))))

(deftest rng-propagation-is-reproducible-and-seed-sensitive
  (testing "same seed is reproducible, different seed can change adaptation outcomes"
    (let [histories (into {}
                          (map (fn [i]
                                 [(str "resolver-" i)
                                  {:strategy :honest
                                   :epoch-history {:epoch-1 {:trials 5 :profit 10.0}}}])
                               (range 50)))
          params {:n-trials-per-epoch 2000
                  :strategy-adaptation {:enabled true
                                        :rate 0.5
                                        :selector :load-optimal
                                        :allowed-targets #{:honest :lazy :malicious}}
                  :strategy-space #{:honest :lazy :malicious}}
          run-a1 (d/apply-strategy-defection (rng/make-rng 101) histories 1 params)
          run-a2 (d/apply-strategy-defection (rng/make-rng 101) histories 1 params)
          run-b  (d/apply-strategy-defection (rng/make-rng 202) histories 1 params)
          mix-a1 (frequencies (map :strategy (vals (:updated-histories run-a1))))
          mix-b  (frequencies (map :strategy (vals (:updated-histories run-b))))]
      (is (= run-a1 run-a2))
      (is (not= mix-a1 mix-b)))))

(deftest max-switch-probability-cap-override
  (testing "binary selector respects max-switch-probability cap"
    (let [histories {"h1" {:strategy :honest :epoch-history {:epoch-1 {:trials 10 :profit 1.0}}}
                     "m1" {:strategy :malicious :epoch-history {:epoch-1 {:trials 10 :profit 10000.0}}}}
          no-switch (d/apply-strategy-defection
                     (rng/make-rng 1) histories 1
                     {:defection-rate 1.0
                      :max-switch-probability 0.0})
          switch-ok (d/apply-strategy-defection
                     (rng/make-rng 1) histories 1
                     {:defection-rate 1.0
                      :max-switch-probability 1.0})]
      (is (empty? (:defection-events no-switch)))
      (is (= 1 (count (:defection-events switch-ok)))))))

(deftest defaults-are-surfaced-in-resolved-config
  (testing "resolved config includes defaults and defaults-used provenance"
    (let [result (d/apply-strategy-defection
                  (rng/make-rng 1)
                  {"h1" {:strategy :honest :epoch-history {:epoch-1 {:trials 1 :profit 5.0}}}
                   "m1" {:strategy :malicious :epoch-history {:epoch-1 {:trials 1 :profit 10.0}}}}
                  1
                  {:defection-rate 0.1})
          cfg (:resolved-config result)]
      (is (= 0.8 (:max-switch-probability cfg)))
      (is (= :inconclusive (:blocked-target-policy cfg)))
      (is (contains? (:defaults-used cfg) :max-switch-probability))
      (is (contains? (:defaults-used cfg) :blocked-target-policy)))))

(deftest validation-rejects-invalid-params
  (testing "parameter validation throws ex-info for out-of-range inputs"
    (is (thrown? clojure.lang.ExceptionInfo
                 (ec/optimal-strategy-under-load (rng/make-rng 1) 0 100 0.1 2.0 150.0)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (ec/optimal-strategy-under-load (rng/make-rng 1) 10 0 0.1 2.0 150.0)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (ec/optimal-strategy-under-load (rng/make-rng 1) 10 100 -0.1 2.0 150.0)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (ec/optimal-strategy-under-load (rng/make-rng 1) 10 100 0.1 0.0 150.0)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (ec/optimal-strategy-under-load (rng/make-rng 1) 10 100 0.1 2.0 0.0)))))

(deftest load-reduces-honest-accuracy-monotonically
  (testing "as dispute count increases, honest expected accuracy does not increase"
    (let [base-cost  (ec/optimal-strategy-under-load (rng/make-rng 1) 10 100 0.1 2.0 150.0)
          heavy-cost (ec/optimal-strategy-under-load (rng/make-rng 1) 200 100 0.1 2.0 150.0)]
      (is (>= (get-in base-cost [:strategy-payoffs :honest :expected-accuracy])
              (get-in heavy-cost [:strategy-payoffs :honest :expected-accuracy]))
          "honest accuracy should not increase as load increases"))))

(deftest detection-reduces-malicious-profit
  (testing "as detection probability increases, malicious expected profit does not increase"
    (let [low-det  (ec/optimal-strategy-under-load (rng/make-rng 1) 50 100 0.05 2.0 150.0)
          high-det (ec/optimal-strategy-under-load (rng/make-rng 1) 50 100 0.50 2.0 150.0)]
      (is (>= (get-in low-det [:strategy-payoffs :malicious :expected-profit])
              (get-in high-det [:strategy-payoffs :malicious :expected-profit]))
          "malicious profit should not increase as detection probability increases"))))

(deftest load-level-by-effort
  (testing "load-level responds to effort per dispute across all levels"
    (is (= :light   (ec/load-level 10 100)) "10 disputes, budget 100 → 10 effort/unit → light")
    (is (= :medium  (ec/load-level 40 100)) "40 disputes, budget 100 → 2.5 effort/unit → medium")
    (is (= :heavy   (ec/load-level 100 100)) "100 disputes, budget 100 → 1 effort/unit → heavy")
    (is (= :extreme (ec/load-level 500 100)) "500 disputes, budget 100 → 0.2 effort/unit → extreme")
    (is (= :light   (ec/load-level 10 200)) "10 disputes, budget 200 → 20 effort/unit → light")))

(deftest expected-detection-prob-is-weighted
  (testing "expected detection is base * ~0.86 for default distribution"
    (let [dist (ec/default-difficulty-distribution)
          p1   (ec/expected-detection-prob 0.10 dist)
          p2   (ec/expected-detection-prob 0.50 dist)]
      (is (< 0.0 p1 0.10) "weighted detection below base for 10%")
      (is (< 0.0 p2 0.50) "weighted detection below base for 50%")
      (is (< (Math/abs (- p1 (* 0.10 0.86))) 0.01) "10% base → ~8.6% expected")
      (is (< (Math/abs (- p2 (* 0.50 0.86))) 0.01) "50% base → ~43% expected"))))

(deftest high-detection-clamps-malice-profit
  (testing "malicious profit never goes negative even at extreme detection"
    (let [extreme-det (ec/optimal-strategy-under-load (rng/make-rng 1) 50 100 0.80 5.0 150.0)
          malice-prof (get-in extreme-det [:strategy-payoffs :malicious :expected-profit])]
      (is (>= malice-prof 0) "malicious profit must be >= 0 even at 80% detection × 5x slash"))))

(deftest strategy-output-is-valid
  (testing "optimal strategy is always one of the known strategies"
    (doseq [[disputes budget] [[10 100] [50 100] [200 100] [500 100] [10 200] [100 50]]]
      (let [decision (ec/optimal-strategy-under-load (rng/make-rng 1) disputes budget 0.1 2.0 150.0)
            optimal  (:optimal-strategy decision)]
        (is (contains? #{:honest :lazy :malicious} optimal)
            (str "unexpected optimal strategy " optimal " at disputes=" disputes " budget=" budget))
        (is (contains? #{:light :medium :heavy :extreme} (:load-level decision)))
        (is (contains? decision :strategy-payoffs))
        (is (contains? decision :assumptions))))))

(deftest blocked-target-policy-modes
  (testing "blocked-target-policy controls compatibility classification"
    (let [base-result {:epoch-results [{:defection {:diagnostics [{:reason :target-outside-strategy-space}]
                                                    :adaptation/resolved-config {:blocked-target-policy :inconclusive}}}]}
          warn-result (assoc-in base-result [:epoch-results 0 :defection :adaptation/resolved-config :blocked-target-policy] :warn)
          fail-result (assoc-in base-result [:epoch-results 0 :defection :adaptation/resolved-config :blocked-target-policy] :fail)
          inc-claim (se/evaluate-strategy-adaptation-compatibility base-result)
          warn-claim (se/evaluate-strategy-adaptation-compatibility warn-result)
          fail-claim (se/evaluate-strategy-adaptation-compatibility fail-result)]
      (is (= :inconclusive (:status inc-claim)))
      (is (= :pass (:status warn-claim)))
      (is (= :fail (:status fail-claim))))))
