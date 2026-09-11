(ns resolver-sim.sim.audit-test
  "Tests for Steps 3-5:
   3. Full trajectory shape — equity, reputation, trial-count, slash-count, loss-events
   4. on-epoch-complete callback — called once per epoch with correct epoch number
   5. analyze-multi-epoch — composite hypothesis checks and distributional audit"
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.sim.multi-epoch :as me]
            [resolver-sim.sim.audit       :as audit]
            [resolver-sim.sim.stochastic-equilibrium :as stoch-eq]
            [resolver-sim.stochastic.rng  :as rng]))

(def ^:private base-params
  {:scenario-id                    "audit-test"
   :n-resolvers                    12
   :strategy-mix                   {:honest 0.50 :lazy 0.10 :malicious 0.30 :collusive 0.10}
   :escrow-size                    10000
   :resolver-fee-bps               150
   :appeal-bond-bps                500
   :slash-multiplier               2.0
   :appeal-probability-if-correct  0.05
   :appeal-probability-if-wrong    0.30
   :slashing-detection-probability 0.10
   :fraud-detection-probability    0.05
   :fraud-slash-bps                5000
   :reversal-detection-probability 0.03
   :reversal-slash-bps             0
   :timeout-slash-bps              200
   :unstaking-delay-days           14
   :freeze-on-detection?           true
   :freeze-duration-days           3
   :appeal-window-days             7
   :allow-slashing?                true
   :resolver-bond-bps              1000})

(defn- run [& {:keys [epochs trials seed] :or {epochs 6 trials 60 seed 42}}]
  (me/run-multi-epoch (rng/make-rng seed) epochs trials base-params))

;; ---------------------------------------------------------------------------
;; Step 3 — Full trajectory shape
;; ---------------------------------------------------------------------------

(deftest full-trajectories-present-in-result
  (testing ":full-trajectories key present with correct structure"
    (let [result (run)
          ft     (:full-trajectories result)]
      (is (map? ft))
      (is (pos? (count ft))))))

(deftest full-trajectory-has-all-required-fields
  (testing "each resolver trajectory has all expected dimension fields"
    (let [result (run :epochs 4 :trials 40)
          ft     (:full-trajectories result)]
      (doseq [[_id traj] ft]
        (is (vector? (:equity traj))          ":equity must be a vector")
        (is (vector? (:reputation traj))      ":reputation must be a vector")
        (is (vector? (:trial-count traj))     ":trial-count must be a vector")
        (is (vector? (:verdict-count traj))   ":verdict-count must be a vector")
        (is (vector? (:slash-count traj))     ":slash-count must be a vector")
        (is (vector? (:appeal-count traj))    ":appeal-count must be a vector")
        (is (vector? (:escalated-count traj)) ":escalated-count must be a vector")
        (is (vector? (:loss-events traj))     ":loss-events must be a vector")))))

(deftest trajectory-length-equals-epoch-count
  (testing "each trajectory dimension has length == n-epochs"
    (let [n-epochs 5
          result   (me/run-multi-epoch (rng/make-rng 99) n-epochs 40 base-params)
          ft       (:full-trajectories result)]
      (doseq [[_id traj] ft
              dim [:equity :reputation :trial-count :verdict-count
                   :slash-count :appeal-count :escalated-count]]
        (is (= n-epochs (count (get traj dim)))
            (str "dimension " dim " length must equal n-epochs"))))))

(deftest equity-is-backward-compatible-with-equity-trajectories
  (testing ":equity in full-trajectories matches :equity-trajectories values"
    (let [result (run)
          ft     (:full-trajectories result)
          et     (:equity-trajectories result)]
      (doseq [[id old-traj] et]
        (let [new-equity (:equity (get ft id))]
          (when new-equity
            (is (= (mapv double old-traj) (mapv double new-equity))
                (str "equity mismatch for " id))))))))

(deftest reputation-values-between-zero-and-one
  (testing "all reputation values are in [0, 1]"
    (let [result (run :epochs 4 :trials 40)
          ft     (:full-trajectories result)]
      (doseq [[_id traj] ft
              v (:reputation traj)]
        (is (<= 0.0 v 1.0) (str "reputation value out of range: " v))))))

(deftest trial-count-is-non-negative
  (testing "trial-count values are all non-negative"
    (let [result (run :epochs 4 :trials 40)
          ft     (:full-trajectories result)]
      (doseq [[_id traj] ft
              v (:trial-count traj)]
        (is (>= v 0.0))))))

;; ---------------------------------------------------------------------------
;; Step 4 — on-epoch-complete callback
;; ---------------------------------------------------------------------------

(deftest on-epoch-complete-called-for-each-epoch
  (testing "callback fires exactly once per epoch with correct epoch number"
    (let [n-epochs  5
          seen      (atom [])
          callback  (fn [n _summary] (swap! seen conj n))
          _result   (me/run-multi-epoch (rng/make-rng 7) n-epochs 30 base-params callback)]
      (is (= (range 1 (inc n-epochs)) @seen)
          "callback must be called with epoch numbers 1..n-epochs in order"))))

(deftest on-epoch-complete-receives-epoch-summary
  (testing "callback receives a map with :epoch key matching n"
    (let [summaries (atom [])
          callback  (fn [_n summary] (swap! summaries conj summary))
          _result   (me/run-multi-epoch (rng/make-rng 8) 4 30 base-params callback)]
      (is (= 4 (count @summaries)))
      (doseq [s @summaries]
        (is (contains? s :epoch))
        (is (contains? s :honest-mean-profit))
        (is (contains? s :dominance-ratio))))))

(deftest default-callback-is-noop
  (testing "run-multi-epoch without callback returns same result as with noop callback"
    (let [r1 (me/run-multi-epoch (rng/make-rng 5) 3 30 base-params)
          r2 (me/run-multi-epoch (rng/make-rng 5) 3 30 base-params (fn [_ _] nil))]
      (is (= (:epoch-results r1) (:epoch-results r2))
          "explicit noop callback must produce identical epoch-results"))))

;; ---------------------------------------------------------------------------
;; Step 5 — analyze-multi-epoch
;; ---------------------------------------------------------------------------

(deftest analyze-returns-expected-shape
  (testing "analyze-multi-epoch returns required keys"
    (let [result (run :epochs 8 :trials 80)
          audit  (audit/analyze-multi-epoch result)]
      (is (= :malice-cannot-dominate-over-time (:hypothesis audit)))
      (is (#{:pass :fail} (:result audit)))
      (is (= 8 (:epochs-checked audit)))
      (is (map? (:checks audit)))
      (is (map? (:summary audit)))
      (is (vector? (:violations audit))))))

(deftest checks-map-has-all-required-keys
  (testing "all hypothesis check keys are present"
    (let [result (run :epochs 6 :trials 60)
          checks (:checks (audit/analyze-multi-epoch result))]
      (is (contains? checks :honest-mean-positive?))
      (is (contains? checks :malice-mean-nonpositive?))
      (is (contains? checks :dominance-above-threshold?))
      (is (contains? checks :honest-p10-above-malice-p90?))
      (is (contains? checks :malice-equity-share-below-limit?))
      (is (contains? checks :malice-slope-not-improving?))
      (is (contains? checks :malice-survival-rate-low?)))))

(deftest summary-has-required-metrics
  (testing "summary map contains all distributional metrics"
    (let [result  (run :epochs 6 :trials 60)
          summary (:summary (audit/analyze-multi-epoch result))]
      (is (contains? summary :min-dominance-ratio))
      (is (contains? summary :dominance-slope))
      (is (contains? summary :final-honest-p10))
      (is (contains? summary :final-malice-p90))
      (is (contains? summary :malice-equity-share))
      (is (contains? summary :malice-survival-rate)))))

(deftest honest-mean-positive-when-honest-earns-fees
  (testing ":honest-mean-positive? true when honest resolvers have positive cumulative profit"
    (let [result (run :epochs 6 :trials 60)
          audit  (audit/analyze-multi-epoch result)]
      ;; With the test params, honest always earns the fee
      (is (true? (get-in audit [:checks :honest-mean-positive?]))))))

(deftest manifest-contains-required-fields
  (testing "make-manifest produces a map with all required fields"
    (let [result   (run :epochs 4 :trials 40)
          manifest (audit/make-manifest result base-params "data/params/test.edn" 42
                                        :git-commit "abc123"
                                        :sim-version "0.1.0")]
      (is (= "data/params/test.edn" (:params-file manifest)))
      (is (= 42 (:seed manifest)))
      (is (= 4 (:epochs manifest)))
      (is (string? (:completed-at manifest)))
      (is (= "abc123" (:git-commit manifest))))))

(deftest stochastic-equilibrium-uses-explicit-initial-composition
  (testing "honest-survival-rate and collusion-resistance use initial-composition fields"
    (let [synthetic {:aggregated-stats {:honest-final-count 6
                                        :malice-final-count 5
                                        :honest-cumulative-profit 1000.0
                                        :malice-cumulative-profit -10.0
                                        :honest-avg-win-rate 0.70
                                        :malice-avg-win-rate 0.40
                                        :total-resolver-exits 1}
                     :epoch-results [{:dominance-ratio 1.3 :honest-mean-profit 10 :malice-mean-profit 1}]
                     :initial-resolver-count 10
                     :initial-composition {:honest-count 7 :malice-count 3 :total-count 10
                                           :honest-share 0.7 :malice-share 0.3}}
          report (stoch-eq/evaluate-stochastic-equilibrium synthetic)
          survival (some #(when (= :honest-survival-rate (:claim-id %)) %) (:claim-results report))
          collusion (get-in report [:mechanism-proxy-results :collusion-resistance])]
      (is (= :fail (:status survival))
          "6/7 honest survival is lower than 5/3 malice survival; should fail")
      (is (= :fail (:status collusion))
          "malice final 5 vs initial 3 implies growth ratio > 1.1; should fail")
      (is (= 3 (get-in collusion [:evidence :initial-malice-count]))))))
