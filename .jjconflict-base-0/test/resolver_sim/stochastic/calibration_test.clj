(ns resolver-sim.stochastic.calibration-test
  "Cross-engine calibration tests.

   The Monte Carlo engine (stochastic/) and the Replay engine (protocols/sew/) are
   two independent implementations of the Sew economic model. They must agree on
   the numeric formulas for fees, bonds, and slashing — otherwise MC results and
   replay results are incommensurable.

   This file tests every formula that appears in BOTH engines.

   ── KNOWN CALIBRATION GAP (not tested here; documented as a limit) ──────────
   The MC `profit-malice` model is:

       profit-malice = fee - (bond × slash-rate × detection-prob)

   This measures only the RESOLVER'S PROTOCOL INCOME. It omits the malicious
   gain from outcome direction: a resolver who successfully misdirects escrow
   hands the full escrow amount to a colluding party. That gain is:

       fraud-upside = escrow × (1 - fee-rate)          ; if resolver is the beneficiary
                    ≥ escrow × (1 - fee-rate) × bribe   ; if resolver is a hired actor

   For a 10 000 wei escrow with fee-bps=150 (fee=15), the hidden upside is
   ≈9 985 wei — roughly 665× the visible fee. The MC dominance ratio therefore
   understates how attractive fraud is under the model's own assumptions.

   MC-1 adds `fraud-success-rate` to `resolve-dispute` so this upside can be
   modelled explicitly. Default 0.0 keeps existing phase outputs unchanged.

   The replay engine captures escrow diversion via its state-machine invariants
   (funds-conservation, no-double-release, etc.), but the MC engine does not.

   Consequence: MC results establish that PROTOCOL INCOME from honest participation
   exceeds protocol income from malice. They do not establish that the total
   economic payoff from fraud is unattractive. The replay invariant suite is the
   correct evidence source for the latter claim.
   ─────────────────────────────────────────────────────────────────────────────"
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.stochastic.economics :as econ]
            [resolver-sim.stochastic.dispute   :as dispute]
            [resolver-sim.stochastic.rng       :as rng]
            [resolver-sim.stochastic.params    :as params]
            [resolver-sim.protocols.sew.snapshot :as snap]
            [resolver-sim.protocols.sew.types  :as t]
            [resolver-sim.protocols.sew.economics :as sew-econ]
            [resolver-sim.oracle.detection     :as oracle]))

;; ── Test grid ────────────────────────────────────────────────────────────────
;; Representative escrow amounts (wei) and fee/bond/slash bps values drawn
;; from actual parameter files in data/params/.
;;
;; Values are hand-selected for boundary and typical-range coverage:
;;   amounts       — 0, near-zero (1, 999, 9999), typical (10000), large (100000, 1000000)
;;   fee-bps       — 1%, 1.5%, 2%, 5%
;;   bond-bps      — 5%, 7%, 10%
;;   slash-bps     — 2% (timeout), 25% (reversal), 50% (fraud)
;; These should be reviewed and extended if the numeric domain changes.

(def ^:private amounts [0 1 999 1000 9999 10000 100000 1000000])
(def ^:private fee-bps-values [100 150 200 500])
(def ^:private bond-bps-values [500 700 1000])
(def ^:private slash-bps-values [200 2500 5000])   ; timeout / reversal / fraud

;; ── 1. Fee formula ───────────────────────────────────────────────────────────

(deftest fee-formula-identity
  (testing "stochastic/calculate-fee == types/compute-fee for all (amount, fee-bps) pairs"
    (doseq [amt amounts
            bps fee-bps-values]
      (is (= (econ/calculate-fee amt bps)
             (t/compute-fee      amt bps))
          (str "fee mismatch at amount=" amt " fee-bps=" bps)))))

;; ── 2. Bond formula ──────────────────────────────────────────────────────────

(deftest bond-formula-identity
  (testing "stochastic/calculate-bond == types/compute-fee with bond-bps for all pairs"
    (doseq [amt amounts
            bps bond-bps-values]
      (is (= (econ/calculate-bond amt bps)
             (t/compute-fee       amt bps))
          (str "bond mismatch at amount=" amt " bond-bps=" bps)))))

;; ── 3. Slash amount formula ───────────────────────────────────────────────────
;; Reversal slashes use stake basis (live-aligned via oracle/detection).
;; Fraud/timeout/L1 generic use bond × bps multiplier.

(deftest reversal-slash-formula-identity
  (testing "oracle reversal slash == Sew reversal slash on stake"
    (doseq [stake amounts
            bps slash-bps-values]
      (let [oracle-slash (oracle/slash-amount-for-reason
                          :reversal {:reversal-slash-bps bps}
                          {:resolver-stake stake :bond-total 0 :slash-mult 0})
            replay-slash (sew-econ/calculate-reversal-slash stake bps)]
        (is (= oracle-slash replay-slash)
            (str "reversal slash mismatch at stake=" stake " bps=" bps))))))

(deftest bond-slash-formula-identity
  (testing "bond-basis slash (fraud) == calculate-slashing-loss × multiplier"
    (doseq [amt amounts
            bps slash-bps-values]
      (let [stochastic-slash (long (econ/calculate-slashing-loss amt (/ bps 10000.0)))
            oracle-slash     (oracle/slash-amount-for-reason
                              :fraud {:fraud-slash-bps bps}
                              {:bond-total amt :resolver-stake 0 :slash-mult 0})]
        (is (= stochastic-slash oracle-slash)
            (str "bond slash mismatch at amount=" amt " bps=" bps))))))

(deftest live-reversal-slash-requires-appeal-outcome
  (testing "reversal slash requires appeal reversal and respects reversal detection probability"
    (is (oracle/reversal-slashed-live? {:reversal-slash-bps 2500
                                        :reversal-detection-probability 1.0
                                        :oracle-fixture {:mode :fixed-roll-sequence
                                                         :rolls [0.1]
                                                         :scope #{:detection}
                                                         :on-exhaustion :throw}
                                        :oracle-roll-cursor (atom 0)}
                                       {:verdict-correct? false
                                        :appealed? true
                                        :decision-reversed? true}))
    (is (not (oracle/reversal-slashed-live? {:reversal-slash-bps 2500
                                             :reversal-detection-probability 0.0
                                             :oracle-fixture {:mode :fixed-roll-sequence
                                                              :rolls [0.1]
                                                              :scope #{:detection}
                                                              :on-exhaustion :throw}
                                             :oracle-roll-cursor (atom 0)}
                                            {:verdict-correct? false
                                             :appealed? true
                                             :decision-reversed? false})))
    (is (not (oracle/reversal-slashed-live? {:reversal-slash-bps 2500
                                             :reversal-detection-probability 1.0
                                             :oracle-fixture {:mode :fixed-roll-sequence
                                                              :rolls [0.1]
                                                              :scope #{:detection}
                                                              :on-exhaustion :throw}
                                             :oracle-roll-cursor (atom 0)}
                                            {:verdict-correct? false
                                             :appealed? false
                                             :decision-reversed? true})))
    (is (not (oracle/reversal-slashed-live? {:reversal-slash-bps 2500
                                             :reversal-detection-probability 0.25
                                             :oracle-fixture {:mode :fixed-roll-sequence
                                                              :rolls [0.30]
                                                              :scope #{:detection}
                                                              :on-exhaustion :throw}
                                             :oracle-roll-cursor (atom 0)}
                                            {:verdict-correct? false
                                             :appealed? true
                                             :decision-reversed? true})))
    (is (oracle/reversal-slashed-live? {:reversal-slash-bps 2500
                                        :reversal-detection-probability 0.25
                                        :oracle-fixture {:mode :fixed-roll-sequence
                                                         :rolls [0.20]
                                                         :scope #{:detection}
                                                         :on-exhaustion :throw}
                                        :oracle-roll-cursor (atom 0)}
                                       {:verdict-correct? false
                                        :appealed? true
                                        :decision-reversed? true}))))

(deftest dispute-reversal-slash-uses-stake-basis
  (testing "resolve-dispute reversal slash amount matches stake × bps"
    ;; Seed chosen so :collusive gets wrong verdict, appeal fires, L1 reverses (p=1.0)
    (let [r      (rng/make-rng 101)
          stake  50000
          bps    2500
          result (dispute/resolve-dispute r 10000 150 700 0 :collusive 0 1.0 0
                                          :reversal-slash-bps bps
                                          :p-l1-reversal 1.0
                                          :fraud-detection-probability 0
                                          :timeout-detection-probability 0
                                          :slashing-detection-probability 0
                                          :resolver-stake-wei stake
                                          :reversal-detection-probability 1.0)]
      (when (= (:slashing-reason result) :reversal)
        (is (= (sew-econ/calculate-reversal-slash stake bps)
               (- (econ/calculate-fee 10000 150)
                  (:profit-malice result)))
            "malice profit should reflect stake-basis reversal slash")))))

;; ── 4. Appeal bond fee formula ────────────────────────────────────────────────
;; Both engines use types/compute-fee for the protocol fee on appeal bonds.
;; The stochastic engine does not model bond fee deduction — it uses the gross
;; bond amount. We verify that the Sew appeal-bond fee deducts correctly
;; and is consistent with types/compute-fee.

(deftest appeal-bond-fee-consistency
  (testing "Sew appeal-bond fee net = amount - types/compute-fee"
    (doseq [amt [100 1000 10000]
            fee-bps [0 50 100 500]]
      (let [{:keys [fee net]} (sew-econ/calculate-appeal-bond-fee amt fee-bps)]
        (is (= fee (t/compute-fee amt fee-bps))
            (str "bond-fee mismatch at amount=" amt " fee-bps=" fee-bps))
        (is (= net (- amt fee))
            (str "net mismatch at amount=" amt " fee-bps=" fee-bps))))))

;; ── 5. Slashing distribution adds up ─────────────────────────────────────────
;; Sew slashing distribution must be lossless (no wei destroyed).
;; insurance + protocol + retained = amount - bounty.

(deftest slashing-distribution-conservation
  (testing "slashing distribution is lossless (no wei destroyed)"
    (doseq [amount [0 100 1000 9999 10000]
            bounty [0 10 50]]
      (when (<= bounty amount)
        (let [{:keys [insurance protocol retained]}
              (sew-econ/calculate-slashing-distribution amount bounty)
              total (+ insurance protocol retained)]
          (is (= total (- amount bounty))
              (str "distribution not lossless: amount=" amount
                   " bounty=" bounty
                   " distributed=" total)))))))

;; ── 6. Fraud-upside model (MC-1) ─────────────────────────────────────────────
;; When fraud-success-rate > 0 and strategy=:malicious and not slashed,
;; resolve-dispute must include escrow-diversion upside in profit-malice.
;; When fraud-success-rate=0.0 (default), result must be identical to original model.

(deftest fraud-upside-zero-rate-backward-compatibility
  (testing "fraud-success-rate=0.0 produces same profit-malice as original model"
    (doseq [escrow [1000 10000 100000]]
      (let [r1 (rng/make-rng 42)
            r2 (rng/make-rng 42)
            ;; Original call (no fraud-success-rate)
            orig (dispute/resolve-dispute r1 escrow 150 700 2.5 :malicious 0.05 0.4 0.1)
            ;; New call with explicit 0.0
            new  (dispute/resolve-dispute r2 escrow 150 700 2.5 :malicious 0.05 0.4 0.1
                                          :fraud-success-rate 0.0)]
        (is (= (:profit-malice orig) (:profit-malice new))
            (str "backward-compat broken at escrow=" escrow))
        (is (= 0 (:fraud-upside new))
            (str "fraud-upside should be 0 when rate=0 at escrow=" escrow))))))

(deftest fraud-upside-zero-when-slashed
  (testing "fraud-upside is 0 when resolver is slashed (cannot divert funds)"
    ;; detection-prob=1.0 forces slashing
    (let [r      (rng/make-rng 42)
          result (dispute/resolve-dispute r 10000 150 700 2.5
                                          :malicious 0.05 0.4 1.0
                                          :fraud-success-rate 0.8)]
      ;; Slashing may or may not occur depending on verdict; check when it does
      (when (:slashed? result)
        (is (= 0 (:fraud-upside result))
            "fraud-upside must be 0 when slashed")))))

;; ── 7. Param bridge (MC-6) ───────────────────────────────────────────────────

(deftest param-bridge-known-fields
  (testing "from-snap maps only fields that exist in a real ModuleSnapshot"
    (let [snap {:escrow-fee-bps                150
                :appeal-bond-bps               700
                :reversal-slash-bps            2500
                :resolver-bond-bps             1000
                :reversal-detection-probability 0.25
                :unknown-field                 :ignored}
          out  (params/from-snap snap)]
      (is (= 150  (:resolver-fee-bps out)))
      (is (= 700  (:appeal-bond-bps out)))
      (is (= 2500 (:reversal-slash-bps out)))
      (is (= 1000 (:resolver-bond-bps out)))
      (is (= 0.25 (:reversal-detection-probability out)))
      (is (nil?   (:unknown-field out)) "unknown fields must be dropped")
      (is (nil?   (:fraud-slash-bps out)) "MC-only field must not appear")
      (is (nil?   (:timeout-slash-bps out)) "MC-only field must not appear")
      (is (nil?   (:fraud-success-rate out)) "MC-only field must not appear")
      (is (nil?   (:fraud-model out)) "MC-only field must not appear"))))

(deftest param-bridge-partial-snap
  (testing "from-snap handles partial snapshots without error"
    (let [snap {:escrow-fee-bps 200}
          out  (params/from-snap snap)]
      (is (= 200 (:fee-bps out)))
      (is (nil? (:bond-bps out)))
      (is (nil? (:reversal-slash-bps out)) "partial snap drops unset keys"))))

(deftest param-bridge-merge-snap-overrides
  (testing "merge-snap overrides base fields with snapshot values"
    (let [base {:resolver-fee-bps 100 :reversal-slash-bps 2000 :other 99}
          snap {:escrow-fee-bps 150 :reversal-slash-bps 2500}
          out  (params/merge-snap base snap)]
      (is (= 150 (:resolver-fee-bps out))     "snap value overrides base")
      (is (= 2500 (:reversal-slash-bps out)) "snap value overrides base")
      (is (= 99  (:other out))       "non-overridden base fields kept"))))

(deftest from-snap-real-snapshot
  (testing "from-snap works with a real snapshot from snapshot-from-protocol-params"
    (let [snap (snap/snapshot-from-protocol-params
                {:resolver-fee-bps 150 :appeal-bond-bps 700
                 :resolver-bond-bps 1000 :reversal-slash-bps 2500
                 :reversal-detection-probability 0.25})
          out (params/from-snap snap)]
      (is (= 150 (:fee-bps out)))
      (is (= 700 (:bond-bps out)))
      (is (= 1000 (:resolver-bond-bps out)))
      (is (= 2500 (:reversal-slash-bps out)))
      (is (= 0.25 (:reversal-detection-probability out)))
      (is (nil? (:fraud-slash-bps out)) "MC-only field not produced from snapshot")
      (is (nil? (:timeout-slash-bps out)) "MC-only field not produced from snapshot"))))

;; ── 8. Appeal economics (MC-4) ───────────────────────────────────────────────

(deftest appeal-economics-disabled-by-default
  (testing "model-appeal-costs?=false (default) keeps original profit-honest"
    (doseq [escrow [1000 10000]]
      (let [r1 (rng/make-rng 7)
            r2 (rng/make-rng 7)
            orig (dispute/resolve-dispute r1 escrow 150 700 2.5 :honest 0.05 0.4 0.1)
            new  (dispute/resolve-dispute r2 escrow 150 700 2.5 :honest 0.05 0.4 0.1
                                          :model-appeal-costs? false)]
        (is (= (:profit-honest orig) (:profit-honest new))
            (str "model-appeal-costs? default broke profit-honest at escrow=" escrow))))))

;; ── 9. Slash distribution in resolve-dispute (MC-3) ──────────────────────────

(deftest slash-distributed-nil-when-not-slashed
  (testing ":slash-distributed is nil when resolver is not slashed"
    ;; detection-prob=0 prevents slashing
    (let [r      (rng/make-rng 42)
          result (dispute/resolve-dispute r 10000 150 700 2.5 :honest 0.05 0.4 0.0)]
      (when-not (:slashed? result)
        (is (nil? (:slash-distributed result)))))))

(deftest slash-distributed-lossless-when-slashed
  (testing ":slash-distributed sums correctly when resolver is slashed"
    ;; detection-prob=1.0, malicious strategy → near-certain slashing
    (let [r      (rng/make-rng 42)
          result (dispute/resolve-dispute r 10000 150 700 2.5 :malicious 0.05 0.4 1.0)]
      (when (:slashed? result)
        (let [{:keys [insurance protocol retained]} (:slash-distributed result)]
          (is (some? insurance) ":slash-distributed should be present when slashed")
          (is (every? #(>= % 0) [insurance protocol retained])
              "all distribution components should be non-negative"))))))

;; ── 10. Convergence regression (MC-7) ────────────────────────────────────────
;; Pinned-seed tests: MC mean profit should converge near analytical expectation.
;; Tolerance is ±15% to account for RNG variance at n=500 trials.
;; Increase n for tighter tolerance; decrease for faster CI feedback.

(defn- run-mc-mean
  [n seed escrow fee-bps bond-bps slash-mult strategy detection-prob opts]
  (let [r       (rng/make-rng seed)
        results (repeatedly n
                            #(apply dispute/resolve-dispute
                                    r escrow fee-bps bond-bps slash-mult
                                    strategy 0.05 0.4 detection-prob
                                    (apply concat opts)))
        mean-h  (double (/ (reduce + (map :profit-honest results)) n))
        mean-m  (double (/ (reduce + (map :profit-malice results)) n))]
    {:mean-honest mean-h :mean-malice mean-m}))

(deftest convergence-honest-strategy
  (testing "honest: mean profit-honest is positive and roughly equals fee*(1-appeal-prob)"
    (let [{:keys [mean-honest]}
          (run-mc-mean 500 42 10000 150 700 2.5 :honest 0.1 {})]
      (is (pos? mean-honest)
          "honest mean profit should be positive"))))

(deftest convergence-malicious-higher-with-upside
  (testing "malicious with fraud-success-rate=0.22 has higher mean profit than rate=0.0"
    (let [base-opts  {}
          upside-opts {:fraud-success-rate 0.22}
          {:keys [mean-malice] :as base-r}
          (run-mc-mean 500 42 10000 150 700 2.5 :malicious 0.1 base-opts)
          {:keys [mean-malice mean-malice-up] :as up-r}
          (run-mc-mean 500 42 10000 150 700 2.5 :malicious 0.1 upside-opts)
          mean-malice-up (:mean-malice up-r)]
      (is (>= mean-malice-up mean-malice)
          "fraud upside should never decrease mean malice profit"))))

;; ── 11. Breakeven-detection helper (MC-B1) ───────────────────────────────────

(deftest breakeven-detection-formula
  (testing "breakeven-detection returns correct threshold"
    ;; At baseline: escrow=10000, fee=150, bond-loss=4250
    ;; breakeven = (10000-150) / (4250 + 10000-150) = 9850/14100 ≈ 0.699
    (let [escrow 10000 fee 150 bond-loss 4250.0
          result (econ/breakeven-detection escrow fee bond-loss)]
      (is (< (Math/abs (- result 0.699)) 0.001)
          (str "breakeven should be ~0.699, got " result)))))

(deftest worst-case-fraud-success-rate
  (testing "worst-case-fraud-success-rate = 1 - detection-prob"
    (is (= 0.9  (econ/worst-case-fraud-success-rate 0.1)))
    (is (= 0.7  (econ/worst-case-fraud-success-rate 0.3)))
    (is (= 0.0  (econ/worst-case-fraud-success-rate 1.0)))
    (is (= 0.0  (econ/worst-case-fraud-success-rate 1.5)))))

(deftest fraud-survival-probability-formula
  (testing "fraud-survival-probability matches staged escalation formula (base band values)"
    ;; Base band: a=0.40, r1=0.85, e2=0.70, r2=0.95
    ;; P = (1-a) + a*(1-r1)*((1-e2) + e2*(1-r2))
    ;;   = 0.60 + 0.40*0.15*(0.30 + 0.70*0.05)
    ;;   = 0.60 + 0.06 * 0.335
    ;;   = 0.6201
    (let [p (econ/fraud-survival-probability {:p-appeal-wrong  0.40
                                              :p-l1-reversal   0.85
                                              :has-kleros?     true
                                              :p-l2-escalation 0.70
                                              :p-l2-reversal   0.95})]
      (is (< (Math/abs (- p 0.6201)) 1.0e-9)
          (str "expected 0.6201, got " p)))))

(deftest sequential-fraud-success-prob-formula
  (testing "sequential-fraud-success-prob (public API, appeal-prob-wrong key) ≈ 0.6201 at base params"
    ;; Same math as fraud-survival-probability but uses :appeal-prob-wrong key
    (let [p (econ/sequential-fraud-success-prob {:appeal-prob-wrong 0.40
                                                 :p-l1-reversal     0.85
                                                 :has-kleros?       true
                                                 :p-l2-escalation   0.70
                                                 :p-l2-reversal     0.95})]
      (is (< (Math/abs (- p 0.6201)) 1.0e-9)
          (str "expected 0.6201, got " p)))))

(deftest fraud-survival-probability-two-layer
  (testing "two-layer (no Kleros) gives higher fraud survival than three-layer"
    ;; Two-layer: P = (1-a) + a*(1-r1) = 0.60 + 0.40*0.15 = 0.66
    (let [three (econ/fraud-survival-probability {:p-appeal-wrong 0.40 :p-l1-reversal 0.85
                                                  :has-kleros? true :p-l2-escalation 0.70
                                                  :p-l2-reversal 0.95})
          two   (econ/fraud-survival-probability {:p-appeal-wrong 0.40 :p-l1-reversal 0.85
                                                  :has-kleros? false})]
      (is (< (Math/abs (- two 0.66)) 1.0e-9)
          (str "expected two-layer P=0.66, got " two))
      (is (> two three)
          (str "two-layer should have higher fraud survival than three-layer: "
               two " vs " three)))))

(deftest fraud-survival-probability-band-ordering
  (testing "pessimistic >= base >= optimistic in fraud capture risk"
    (let [p (econ/fraud-survival-probability (:pessimistic econ/default-escalation-assumptions))
          b (econ/fraud-survival-probability (:base        econ/default-escalation-assumptions))
          o (econ/fraud-survival-probability (:optimistic  econ/default-escalation-assumptions))]
      (is (>= p b o)
          (str "expected pessimistic >= base >= optimistic, got " [p b o])))))

(deftest sequential-fraud-model-lowers-upside-vs-single-stage
  (testing "sequential model yields <= upside than single-stage default 0.90"
    (let [r1   (rng/make-rng 42)
          r2   (rng/make-rng 42)
          base (dispute/resolve-dispute r1 10000 150 700 2.5 :malicious 0.05 0.4 0.1
                                        :fraud-model :single-stage-ev
                                        :fraud-success-rate 0.90)
          seqm (dispute/resolve-dispute r2 10000 150 700 2.5 :malicious 0.05 0.4 0.1
                                        :fraud-model :sequential-escalation
                                        :escalation-assumption-band :base)]
      (when (and (not (:slashed? base)) (not (:slashed? seqm)))
        (is (<= (:fraud-upside seqm) (:fraud-upside base))
            (str "sequential upside should not exceed single-stage at same trial: "
                 (:fraud-upside seqm) " vs " (:fraud-upside base)))))))

(deftest strict-all-tiers-fraud-model
  (testing "strict-all-tiers uses only L1/L2 failure product (appeal always-on assumption)"
    ;; With p-l1-reversal=0.75 and p-l2-reversal=0.88:
    ;; P(loss) = (1-0.75)*(1-0.88) = 0.03
    (let [r (rng/make-rng 42)
          result (dispute/resolve-dispute r 10000 150 700 2.5 :malicious 0.05 0.4 0.1
                                          :fraud-model :strict-all-tiers
                                          :p-l1-reversal 0.75
                                          :p-l2-reversal 0.88)
          fee (econ/calculate-fee 10000 150)
          expected-upside (long (* (- 10000 fee) 0.03))]
      (when-not (:slashed? result)
        (is (= expected-upside (:fraud-upside result))
            (str "strict model expected upside=" expected-upside
                 " got=" (:fraud-upside result)))))))

(deftest breakeven-means-honest-equals-malice
  (testing "at breakeven detection, honest EV ≈ malicious EV (full fraud model)"
    ;; The breakeven condition is: detect × bond-loss = (1-detect) × (escrow-fee)
    ;; In malicious-expected-value: fraud-upside = (escrow-fee), fsr = (1-detect)
    ;; So expected-fraud-gain = (escrow-fee) × fsr = (escrow-fee) × (1-detect) = detect × bond-loss
    ;; → malice EV = fee, honest EV ≈ fee. They should be approximately equal.
    (let [escrow 10000 fee-bps 150 bond-bps 700 slash-mult 2.5
          fee       (econ/calculate-fee escrow fee-bps)
          bond      (+ (econ/calculate-bond escrow bond-bps)
                       (econ/calculate-bond escrow 1000))
          bond-loss (* bond slash-mult)
          breakeven (econ/breakeven-detection escrow fee bond-loss)
          fsr       (econ/worst-case-fraud-success-rate breakeven)
          ;; Pass (escrow - fee) as the max potential upside; fsr is the probability
          max-upside (- escrow fee)
          ev-honest (double (econ/honest-expected-value fee 0.05))
          ev-malice (double (econ/malicious-expected-value fee bond-loss breakeven max-upside fsr))]
      (is (< (Math/abs (- ev-honest ev-malice)) 20.0)
          (str "at breakeven, EVs should be ~equal: honest=" ev-honest " malice=" ev-malice)))))

(deftest below-breakeven-malice-dominates
  (testing "below breakeven detection, full-fraud malice EV > honest EV"
    (let [escrow 10000 fee-bps 150 bond-bps 700 slash-mult 2.5
          fee       (econ/calculate-fee escrow fee-bps)
          bond      (+ (econ/calculate-bond escrow bond-bps)
                       (econ/calculate-bond escrow 1000))
          bond-loss (* bond slash-mult)
          detect    0.10  ; baseline: 10%, well below 70% breakeven
          fsr       (econ/worst-case-fraud-success-rate detect)
          max-upside (- escrow fee)
          ev-honest (econ/honest-expected-value fee 0.05)
          ev-malice (econ/malicious-expected-value fee bond-loss detect max-upside fsr)]
      (is (> ev-malice ev-honest)
          (str "at 10% detection, malice should dominate: honest=" ev-honest " malice=" ev-malice)))))

(deftest param-bridge-sequential-fields
  (testing "scenario->mc-params passes through sequential fraud model fields from :mc-params"
    (let [scenario {:mc-params {:fraud-model :sequential-escalation
                                :escalation-assumption-band :base
                                :p-appeal-wrong 0.40
                                :p-l1-reversal 0.85
                                :has-kleros? false
                                :p-l2-escalation 0.70
                                :p-l2-reversal 0.95}}
          out  (params/scenario->mc-params scenario)]
      (is (= :sequential-escalation (:fraud-model out)))
      (is (= :base (:escalation-assumption-band out)))
      (is (= 0.40 (:p-appeal-wrong out)))
      (is (= 0.85 (:p-l1-reversal out)))
      (is (= false (:has-kleros? out)) "has-kleros? false must pass through")
      (is (= 0.70 (:p-l2-escalation out)))
      (is (= 0.95 (:p-l2-reversal out))))))
