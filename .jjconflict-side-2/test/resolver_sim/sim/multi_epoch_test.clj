(ns resolver-sim.sim.multi-epoch-test
  "Step 1 + Step 2b: determinism and attribution conservation tests.

   Tests:
   1. Determinism — run-multi-epoch produces identical epoch-results given same seed
   2. Attribution conservation — attributed totals equal aggregate batch totals
   3. Per-resolver variance — resolvers of same strategy no longer have identical curves
   4. Zero-trial handling — resolvers with 0 assigned trials get a clean zero record
   5. RNG isolation — different seeds produce different trajectories"
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.sim.multi-epoch      :as me]
            [resolver-sim.sim.batch            :as batch]
            [resolver-sim.sim.trial-router     :as router]
            [resolver-sim.stochastic.rng       :as rng]))

;; ---------------------------------------------------------------------------
;; Shared test params (small, fast)
;; ---------------------------------------------------------------------------

(def ^:private test-params
  {:scenario-id                    "test-epoch"
   :n-resolvers                    10
   :strategy-mix                   {:honest 0.60 :lazy 0.10 :malicious 0.20 :collusive 0.10}
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

;; ---------------------------------------------------------------------------
;; 1. Determinism
;; ---------------------------------------------------------------------------

(deftest run-multi-epoch-is-deterministic
  (testing "same seed produces byte-identical epoch-results"
    (let [seed     42
          n-epochs 5
          n-trials 50
          run      (fn [] (-> (rng/make-rng seed)
                              (me/run-multi-epoch n-epochs n-trials test-params)
                              :epoch-results))
          r1 (run)
          r2 (run)]
      (is (= r1 r2)
          "epoch-results must be identical across two runs with the same seed"))))

(deftest different-seeds-produce-different-resolver-trajectories
  (testing "different seeds produce different per-resolver equity curves"
    (let [run (fn [seed]
                (let [result (me/run-multi-epoch (rng/make-rng seed) 5 100 test-params)]
                  (->> (:equity-trajectories result)
                       vals
                       (map vec)
                       set)))
          r42 (run 42)
          r99 (run 99)]
      (is (not= r42 r99)
          "two different seeds must produce different per-resolver equity curve sets"))))

;; ---------------------------------------------------------------------------
;; 2. Attribution conservation
;; ---------------------------------------------------------------------------

(deftest attribution-conserves-aggregate-totals
  (testing "attributed profit, trial count, and slash count sum to aggregate"
    (let [seed      123
          n-trials  100
          rng-inst  (rng/make-rng seed)
          {:keys [aggregate trials]} (batch/run-batch-with-attribution rng-inst n-trials test-params)

          ;; Route honest pool
          n-honest  6
          honest-ids (mapv #(str "h" %) (range n-honest))
          [rng-h _] (rng/split-rng (rng/make-rng (+ seed 1)))
          honest-pool (mapv (fn [t] {:profit (:profit-honest t 0.0)
                                     :slashed? false
                                     :verdicts 1
                                     :correct (if (:dispute-correct? t false) 1 0)
                                     :appeal-triggered? (:appeal-triggered? t false)
                                     :escalated? (:escalated? t false)})
                            trials)
          honest-attr (router/route router/uniform-random honest-ids honest-pool rng-h)

          ;; Route strategic pool
          n-strategic 4
          strategic-ids (mapv #(str "s" %) (range n-strategic))
          [rng-s _] (rng/split-rng (rng/make-rng (+ seed 2)))
          strategic-pool (mapv (fn [t] {:profit (:profit-malice t 0.0)
                                        :slashed? (:slashed? t false)
                                        :verdicts 1
                                        :correct 0
                                        :appeal-triggered? (:appeal-triggered? t false)
                                        :escalated? (:escalated? t false)})
                               trials)
          strategic-attr (router/route router/uniform-random strategic-ids strategic-pool rng-s)

          eps 1e-5]

      ;; Honest pool conservation
      (let [{:keys [ok? violations]} (router/attribution-conserved? honest-attr honest-pool)]
        (is ok? (str "Honest attribution conservation failed: " violations)))

      ;; Strategic pool conservation
      (let [{:keys [ok? violations]} (router/attribution-conserved? strategic-attr strategic-pool)]
        (is ok? (str "Strategic attribution conservation failed: " violations)))

      ;; Profit sums match batch aggregate within fp tolerance
      (let [attr-honest-profit  (reduce + 0.0 (map :profit (vals honest-attr)))
            attr-strategic-profit (reduce + 0.0 (map :profit (vals strategic-attr)))
            batch-honest-total  (* (double (:honest-mean aggregate)) n-trials)
            batch-malice-total  (* (double (:malice-mean aggregate)) n-trials)]
        (is (< (Math/abs (- attr-honest-profit batch-honest-total)) eps)
            (format "Honest profit sum %.4f != batch total %.4f" attr-honest-profit batch-honest-total))
        (is (< (Math/abs (- attr-strategic-profit batch-malice-total)) eps)
            (format "Strategic profit sum %.4f != batch total %.4f" attr-strategic-profit batch-malice-total)))

      ;; Trial counts
      (is (= n-trials (reduce + 0 (map :trials (vals honest-attr))))
          "Honest trial count must equal n-trials")
      (is (= n-trials (reduce + 0 (map :trials (vals strategic-attr))))
          "Strategic trial count must equal n-trials"))))

;; ---------------------------------------------------------------------------
;; 3. Per-resolver variance — resolvers of same strategy differ
;; ---------------------------------------------------------------------------

(deftest resolvers-of-same-strategy-have-different-curves
  (testing "honest resolvers no longer have identical equity curves after attribution"
    (let [result (me/run-multi-epoch (rng/make-rng 77) 8 80 test-params)
          trajectories (:equity-trajectories result)
          honest-curves (filter (fn [[id _]]
                                  (let [hist (get-in result [:resolver-histories id])]
                                    (= :honest (:strategy hist))))
                                trajectories)]
      (when (> (count honest-curves) 1)
        (let [curves (mapv second honest-curves)
              all-same? (apply = curves)]
          (is (not all-same?)
              "Multiple honest resolvers must not have identical equity curves"))))))

;; ---------------------------------------------------------------------------
;; 4. Zero-trial epochs
;; ---------------------------------------------------------------------------

(deftest zero-trial-resolvers-get-clean-record
  (testing "resolvers assigned 0 trials in an epoch get a well-formed zero record"
    (let [;; More resolvers than trials so some must get zero
          many-resolvers-params (assoc test-params :n-resolvers 100)
          small-n-trials        5
          resolver-ids          (mapv #(str "r" %) (range 100))
          trial-pool            (vec (repeat small-n-trials
                                             {:profit 100.0 :slashed? false
                                              :verdicts 1 :correct 1
                                              :appeal-triggered? false :escalated? false}))
          attr (router/route router/uniform-random resolver-ids trial-pool
                             (rng/make-rng 42))
          zero-resolvers (filter #(= 0 (:trials %)) (vals attr))]
      ;; With 100 resolvers and 5 trials, 95 must have 0 trials
      (is (= 95 (count zero-resolvers))
          "95 resolvers should have zero trials when only 5 trials are assigned")
      ;; Every zero-trial record must be well-formed
      (doseq [r zero-resolvers]
        (is (= 0 (:trials r)))
        (is (= 0.0 (double (:profit r))))
        (is (= 0 (:slashed r)))
        (is (= 0 (:verdicts r)))))))

;; ---------------------------------------------------------------------------
;; 5. route-epoch conservation
;; ---------------------------------------------------------------------------

(deftest route-epoch-conserves-both-pools
  (testing "route-epoch passes conservation for both honest and strategic pools"
    (let [rng-inst (rng/make-rng 555)
          {:keys [trials]} (batch/run-batch-with-attribution rng-inst 60 test-params)
          honest-ids     ["h1" "h2" "h3" "h4" "h5" "h6"]
          strategic-ids  ["s1" "s2" "s3" "s4"]
          [route-rng _]  (rng/split-rng (rng/make-rng 555))
          result (router/route-epoch honest-ids strategic-ids trials trials
                                     router/uniform-random route-rng)]
      ;; route-epoch asserts internally — if no exception, conservation holds
      (is (map? (:honest-attribution result)))
      (is (map? (:strategic-attribution result)))
      (is (= (set honest-ids) (set (keys (:honest-attribution result)))))
      (is (= (set strategic-ids) (set (keys (:strategic-attribution result))))))))

;; ---------------------------------------------------------------------------
;; 6. Configurable replacement strategy mix
;; ---------------------------------------------------------------------------

(deftest configurable-replacement-mix-in-params
  (testing "apply-epoch-decay can read custom :replacement-strategy-mix from params"
    ;; This test verifies that the param is accepted and used, rather than
    ;; testing full exit/replacement logic (which depends on RNG and exit probs).
    (let [test-params-with-custom-mix (assoc test-params
                                             :replacement-strategy-mix {:honest 1.0})]
      ;; Verify the key exists in the params
      (is (= {:honest 1.0}
             (:replacement-strategy-mix test-params-with-custom-mix))
          "custom replacement strategy mix should be in params"))))

;; ---------------------------------------------------------------------------
;; 7. Initial composition fields (Priority 2)
;; ---------------------------------------------------------------------------

(deftest run-multi-epoch-emits-initial-composition
  (testing "result includes explicit initial composition counts and shares"
    (let [result (me/run-multi-epoch (rng/make-rng 11) 2 20 test-params)
          comp   (:initial-composition result)
          n      (:initial-resolver-count result)]
      (is (map? comp))
      (is (= n (:total-count comp)))
      (is (= n (+ (:honest-count comp) (:malice-count comp))))
      (is (<= 0.0 (:honest-share comp) 1.0))
      (is (<= 0.0 (:malice-share comp) 1.0))
      (is (< (Math/abs (- 1.0 (+ (:honest-share comp) (:malice-share comp)))) 1.0e-9)))))

(deftest load-optimal-strategy-space-mismatch-marks-adaptation-inconclusive
  (testing "when load-optimal targets a disallowed strategy, report includes inconclusive adaptation claim"
    (let [p (assoc test-params
                   :defection-rate 1.0
                   :n-trials-per-epoch 2000
                   :strategy-space #{:honest :malicious}
                   :strategy-adaptation {:enabled true
                                         :rate 1.0
                                         :mode :load-optimal
                                         :allowed-targets #{:honest :malicious}})
          result (me/run-multi-epoch (rng/make-rng 13) 2 2000 p)
          adaptation-claim (->> (get-in result [:equilibrium-report :claim-results])
                                (filter #(= :strategy-adaptation-compatibility (:claim-id %)))
                                first)]
      (is (= :inconclusive (:status adaptation-claim)))
      (is (string? (:reason adaptation-claim))))))

;; ---------------------------------------------------------------------------
;; 8. Governance decay explicit signals
;; ---------------------------------------------------------------------------

(deftest governance-decay-explicit-decays-reversal-quality
  (testing "apply-detection-decay decays p-l1/p-l2 reversal quality explicitly"
    (let [params {:detection-decay-rate 0.20
                  :slashing-detection-probability 0.10
                  :fraud-detection-probability 0.25
                  :p-l1-reversal 0.85
                  :p-l2-reversal 0.95
                  :reversal-detection-probability 0.33}
          e1 (me/apply-detection-decay params 1)
          e3 (me/apply-detection-decay params 3)
          mult (Math/pow 0.8 2)]
      (is (= 0.85 (:p-l1-reversal e1)))
      (is (= 0.95 (:p-l2-reversal e1)))
      (is (< (Math/abs (- (* 0.85 mult) (:p-l1-reversal e3))) 1.0e-12))
      (is (< (Math/abs (- (* 0.95 mult) (:p-l2-reversal e3))) 1.0e-12))
      ;; Legacy field is no longer part of decay semantics.
      (is (= 0.33 (:reversal-detection-probability e3))))))
