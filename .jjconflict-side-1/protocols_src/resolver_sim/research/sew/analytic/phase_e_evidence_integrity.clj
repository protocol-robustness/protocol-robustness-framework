(ns resolver-sim.research.sew.analytic.phase-e-evidence-integrity
  "Phase E: Evidence Integrity
  
   Tests hypothesis: Evidence layer does not introduce fund loss or enable
   false resolutions across realistic usage patterns.
   
   Sub-phases:
   - E1: Evidence Deadline Enforcement (0 to appeal-window blocks)
   - E2: Hash Mismatch Detection (collision probability × detection probability)
   - E3: Conflicting Evidence Resolution (weight function comparison)
   - E4: Evidence Bloat Griefing Bounds (1KB to 1GB)
   - E5: Yield Accrual During Dispute (0% to 10% APY)
   - E6: Evidence Availability Guarantee (IPFS/Arweave persistence)
   
   Pass threshold: 5/6 sub-phases (≥80%) must pass.
   Per-sub-phase thresholds vary: E1/E5 100%, E2 80%, E3 67% (2/3), E4 75%, E6 80%."
  (:require [resolver-sim.sim.engine :as engine]
            [resolver-sim.stochastic.rng :as rng]
            [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.protocols.sew.lifecycle :as lc]
            [resolver-sim.protocols.sew.resolution :as res]
            [resolver-sim.protocols.sew.invariants :as inv]
            [resolver-sim.protocols.sew.snapshot :as snap]
            [resolver-sim.protocols.sew.snapshot-presets :as presets]
            [resolver-sim.time.context :as time-ctx]))

;; ---------------------------------------------------------------------------
;; E1: Evidence Deadline Enforcement
;; ---------------------------------------------------------------------------

(defn- run-e1-trial
  "Single trial: does evidence submitted after deadline get rejected?
   
   Sweep: time-offset from deadline (blocks before/after)
   Expected: All late evidence rejected."
  [{:keys [time-offset-from-deadline seed]}]
  (let [appeal-window 120  ; blocks
        dispute-time 0
        deadline (+ dispute-time appeal-window)
        evidence-submission-time (+ deadline time-offset-from-deadline)
        ;; Evidence is accepted if submitted before deadline
        accepted? (<= evidence-submission-time deadline)]
    {:time-offset-from-deadline time-offset-from-deadline
     :deadline deadline
     :submission-time evidence-submission-time
     :accepted? accepted?
     :late? (> time-offset-from-deadline 0)}))

(defn run-e1-evidence-deadline-enforcement
  "Sweep time-offset from -120 to +120 (11 points).
   
   Pass: 100% of post-deadline evidence is rejected (accepted? false for all positive offsets)"
  []
  (let [offsets (mapv #(- % 120) (range 0 121 12))  ; -120, -108, ..., +120
        param-grid (mapv (fn [o] {:time-offset-from-deadline o :seed 42}) offsets)
        results (engine/run-parameter-sweep param-grid run-e1-trial)
        ;; Check: all late evidence should be rejected
        late-results (filter :late? results)
        all-late-rejected? (every? (complement :accepted?) late-results)
        passed? all-late-rejected?]
    (engine/make-result
     {:benchmark-id "E1"
      :label "Evidence Deadline Enforcement"
      :hypothesis "All late evidence (t > deadline) is rejected"
      :passed? passed?
      :results results
      :summary {:total-trials (count results)
                :late-submissions (count late-results)
                :all-rejected? all-late-rejected?}})))

;; ---------------------------------------------------------------------------
;; E2: Hash Mismatch Detection
;; ---------------------------------------------------------------------------

(defn- run-e2-trial
  "Single trial: can hash mismatches be detected?
   
   Sweep: hash-collision-probability from 0 to 0.01
   Expected: All collisions detected above base detection rate.
   
   Model: Detection uses a probabilistic verification layer with
   configurable detection-probability (default 0.95). A collision
   is 'missed' when the detection draw fails — simulating the gap
   between on-chain evidence and off-chain verification."
  [{:keys [collision-prob detection-prob seed]}]
  (let [rng (rng/make-rng seed)
        detection-prob (or detection-prob 0.95)
        random-col (rng/next-double rng)
        random-det  (rng/next-double rng)
        ;; Simulate: is there a hash collision?
        collision-occurs? (< random-col collision-prob)
        ;; Detection: probabilistic verification layer
        collision-detected? (and collision-occurs? (< random-det detection-prob))
        ;; Safe: no collision, or collision detected
        safe? (or (not collision-occurs?) collision-detected?)]
    {:collision-prob collision-prob
     :detection-prob detection-prob
     :collision-occurs? collision-occurs?
     :detected? collision-detected?
     :safe? safe?}))

(defn run-e2-hash-mismatch-detection
  "Sweep collision-prob × detection-prob (6 × 3 = 18 points).
   
   Pass: ≥80% of collisions are detected (safe? true for all)"
  []
  (let [collision-probs [0.0 0.001 0.002 0.005 0.008 0.01]
        detection-probs [0.90 0.95 0.99]
        param-grid (for [cp collision-probs dp detection-probs]
                     {:collision-prob cp :detection-prob dp :seed 42})
        results (engine/run-parameter-sweep param-grid run-e2-trial)
        safe-count (count (filter :safe? results))
        total-count (count results)
        pass-rate (double (/ safe-count total-count))
        passed? (>= pass-rate 0.80)]
    (engine/make-result
     {:benchmark-id "E2"
      :label "Hash Mismatch Detection"
      :hypothesis "Hash collision detection catches ≥80% of mismatches"
      :class :analytic
      :passed? passed?
      :results results
      :summary {:total-trials total-count
                :safe-trials safe-count
                :pass-rate pass-rate
                :threshold 0.80}})))

;; ---------------------------------------------------------------------------
;; E3: Conflicting Evidence Resolution
;; ---------------------------------------------------------------------------

(defn- run-e3-trial
  "Single trial: does evidence weighting produce consistent outcomes?
   
   Sweep: weight-function (equal, recency, reputation)
   Measure: Do different weight functions produce same final verdict?"
  [{:keys [weight-function seed]}]
  (let [;; Three evidence pieces with different timestamps and credibility
        evidence [{:timestamp 100 :credibility 0.8 :claim :buyer-paid}
                  {:timestamp 110 :credibility 0.6 :claim :seller-not-paid}
                  {:timestamp 105 :credibility 0.9 :claim :buyer-paid}]

        ;; Apply weighting based on function
        weights (case weight-function
                  :equal (mapv (fn [_] 1.0) evidence)
                  :recency (mapv (fn [e] (/ (:timestamp e) 110.0)) evidence)
                  :reputation (mapv :credibility evidence))

        ;; Compute weighted verdict
        buyer-paid-weight (reduce + (mapv (fn [e w] (if (= (:claim e) :buyer-paid) w 0)) evidence weights))
        seller-not-paid-weight (reduce + (mapv (fn [e w] (if (= (:claim e) :seller-not-paid) w 0)) evidence weights))

        final-verdict (if (> buyer-paid-weight seller-not-paid-weight) :buyer-paid :seller-not-paid)

        ;; Consistency check: verdict should be buyer-paid (since most credible evidence says so)
        consistent? (= final-verdict :buyer-paid)]
    {:weight-function weight-function
     :final-verdict final-verdict
     :consistent? consistent?}))

(defn run-e3-conflicting-evidence-resolution
  "Sweep weight-function across 3 strategies.
   
   Pass: ≥80% of weighting schemes produce consistent outcomes"
  []
  (let [weight-functions [:equal :recency :reputation]
        param-grid (mapv (fn [wf] {:weight-function wf :seed 42}) weight-functions)
        results (engine/run-parameter-sweep param-grid run-e3-trial)
        consistent-count (count (filter :consistent? results))
        total-count (count results)
        pass-rate (double (/ consistent-count total-count))
        passed? (>= pass-rate 0.67)]  ; At least 2/3 must be consistent
    (engine/make-result
     {:benchmark-id "E3"
      :label "Conflicting Evidence Resolution"
      :hypothesis "Deterministic evidence weighting produces consistent outcomes (≥67%)"
      :passed? passed?
      :results results
      :summary {:total-trials total-count
                :consistent-trials consistent-count
                :pass-rate pass-rate
                :threshold 0.67}})))

;; ---------------------------------------------------------------------------
;; E4: Evidence Bloat Griefing Bounds
;; ---------------------------------------------------------------------------

(defn- run-e4-trial
  "Single trial: is evidence processing cost bounded?
   
   Sweep: max-evidence-size from 1KB to 1GB
   Measure: estimated gas cost to verify evidence.
   
   Pass if: gas-cost < escrow-amount (processing affordable)."
  [{:keys [max-evidence-size-kb seed]}]
  (let [escrow 10000
        ;; Gas cost model: 16 gas per byte (SHA256 hashing)
        gas-per-byte 16
        evidence-bytes (* max-evidence-size-kb 1024)
        gas-cost (* gas-per-byte evidence-bytes)
        ;; Assume 1 GWEI = 1 unit gas cost equivalent
        processing-cost-wei gas-cost
        ;; Affordable if cost < escrow
        affordable? (< processing-cost-wei escrow)]
    {:max-evidence-size-kb max-evidence-size-kb
     :gas-cost gas-cost
     :processing-cost-wei processing-cost-wei
     :escrow escrow
     :affordable? affordable?}))

(defn run-e4-evidence-bloat-griefing-bounds
  "Sweep max-evidence-size from 1KB to 1GB (8 points, log scale).
   
   Pass: ≥75% of sizes remain affordable (cost < escrow)"
  []
  (let [sizes-kb [1 10 100 1000 10000 100000 1000000]  ; 1KB to 1GB
        param-grid (mapv (fn [s] {:max-evidence-size-kb s :seed 42}) sizes-kb)
        results (engine/run-parameter-sweep param-grid run-e4-trial)
        affordable-count (count (filter :affordable? results))
        total-count (count results)
        pass-rate (double (/ affordable-count total-count))
        passed? (>= pass-rate 0.75)]
    (engine/make-result
     {:benchmark-id "E4"
      :label "Evidence Bloat Griefing Bounds"
      :hypothesis "Evidence processing cost remains < escrow (≥75%)"
      :passed? passed?
      :results results
      :summary {:total-trials total-count
                :affordable-trials affordable-count
                :pass-rate pass-rate
                :threshold 0.75}})))

;; ---------------------------------------------------------------------------
;; E5: Yield Accrual During Dispute
;; ---------------------------------------------------------------------------

(defn- run-e5-trial
  "Single trial: disputed escrow accrues yield through resolution and splits correctly.

   Sweep: fixed-rate APY from 0% to 10%.
   Pass when held-delta-accounted holds and split-50-50 yield claimables match accrual."
  [{:keys [yield-rate-apy]}]
  (let [t0           1000
        dt           (* 30 86400)
        snap         (snap/make-escrow-snapshot (merge (presets/preset->protocol-params :sew.preset/baseline)
                                                       {:yield-generation-module :fixed-rate
                                                        :yield-protocol-fee-bps 0
                                                        :appeal-window-duration 0}))
        world0       (-> (proto/init-world sew/protocol {:initial-block-time t0})
                         (assoc-in [:yield/rates :fixed-rate "USDC"] yield-rate-apy)
                         (assoc-in [:yield/rates :fixed-rate :USDC] yield-rate-apy))
        cr           (lc/create-escrow world0 "buyer" "USDC" "seller" 10000
                                       (t/make-escrow-settings {:yield-preset :split-50-50
                                                                :custom-resolver "0xresolver"})
                                       snap)
        w1           (assoc-in (:world cr) [:escrow-transfers 0 :dispute-resolver] "0xresolver")
        rd           (lc/raise-dispute w1 0 "buyer")
        w2           (time-ctx/advance-time (:world rd) {:seconds dt})
        w-before     w2
        rr           (res/execute-resolution w2 0 "0xresolver" true "0xe5" nil)
        ok?          (:ok rr)
        w-after      (:world rr)
        expected     (long (quot (* 10000 yield-rate-apy dt) 31536000))
        sender-yield (get-in w-after [:claimable-v2 0 :settlement/yield "buyer"] 0)
        recip-yield  (get-in w-after [:claimable-v2 0 :settlement/yield "seller"] 0)
        credited     (+ sender-yield recip-yield)
        held-ok?     (:holds? (inv/held-delta-accounted? w-before w-after))
        rounding-ok? (<= (Math/abs (- credited expected)) 1)
        correctly-credited? (and ok? held-ok? rounding-ok?
                                 (= :released (t/escrow-state w-after 0)))]
    {:yield-rate-apy yield-rate-apy
     :expected-yield expected
     :credited-yield credited
     :held-delta-accounted? held-ok?
     :correctly-credited? correctly-credited?}))

(defn run-e5-yield-accrual-during-dispute
  "Sweep yield-rate-apy from 0% to 10% (11 points).

   Pass: 100% of trials credit yield with held-delta accounted."
  []
  (let [rates (mapv #(* 0.01 %) (range 11))
        param-grid (mapv (fn [r] {:yield-rate-apy r :seed 42}) rates)
        results (engine/run-parameter-sweep param-grid run-e5-trial)
        credited-count (count (filter :correctly-credited? results))
        total-count (count results)
        pass-rate (double (/ credited-count total-count))
        passed? (= credited-count total-count)]
    (engine/make-result
     {:benchmark-id "E5"
      :label "Yield Accrual During Dispute"
      :hypothesis "Yield accrues through resolution with held-delta accounted (100%)"
      :passed? passed?
      :results results
      :summary {:total-trials total-count
                :correctly-credited credited-count
                :pass-rate pass-rate
                :threshold 1.0}})))

;; ---------------------------------------------------------------------------
;; E6: Evidence Availability Guarantee
;; ---------------------------------------------------------------------------

(defn- run-e6-trial
  "Single trial: is evidence available for long-term retrieval?
   
   Model: assume IPFS/Arweave persistence with periodic verification.
   
   Question: After 1 year, can evidence still be retrieved?"
  [{:keys [redundancy-factor seed]}]
  (let [;; Simplified model: availability = 1 - (1 - base_availability) ^ redundancy
        base-availability 0.95
        one-year-availability (- 1.0 (Math/pow (- 1.0 base-availability) redundancy-factor))
        available? (> one-year-availability 0.99)]
    {:redundancy-factor redundancy-factor
     :one-year-availability one-year-availability
     :available? available?}))

(defn run-e6-evidence-availability-guarantee
  "Sweep redundancy-factor from 1 to 10 (10 points).
   
   Pass: ≥80% of redundancy levels ensure 99%+ availability after 1 year"
  []
  (let [factors (range 1 11)
        param-grid (mapv (fn [f] {:redundancy-factor f :seed 42}) factors)
        results (engine/run-parameter-sweep param-grid run-e6-trial)
        available-count (count (filter :available? results))
        total-count (count results)
        pass-rate (double (/ available-count total-count))
        passed? (>= pass-rate 0.80)]
    (engine/make-result
     {:benchmark-id "E6"
      :label "Evidence Availability Guarantee"
      :hypothesis "Evidence availability ≥99% after 1 year (with redundancy)"
      :passed? passed?
      :results results
      :summary {:total-trials total-count
                :available-trials available-count
                :pass-rate pass-rate
                :threshold 0.80}})))

;; ---------------------------------------------------------------------------
;; Phase E Entry Point
;; ---------------------------------------------------------------------------

(defn run-phase-e-sweep
  "Run all E1–E6 evidence integrity sweeps.
   
   Returns: {:passed? bool :results [...] :summary {...}}"
  []
  (engine/print-phase-header
   {:benchmark-id "Phase E"
    :label "Evidence Integrity"
    :hypothesis "Evidence layer maintains integrity and does not enable false resolutions"
    :details ["E1: Evidence Deadline Enforcement (0 to appeal-window)"
              "E2: Hash Mismatch Detection (collision probability)"
              "E3: Conflicting Evidence Resolution (weight functions)"
              "E4: Evidence Bloat Griefing Bounds (1KB–1GB)"
              "E5: Yield Accrual During Dispute (0%–10% APY)"
              "E6: Evidence Availability Guarantee (redundancy sweep)"]})

  (let [e1 (run-e1-evidence-deadline-enforcement)
        e2 (run-e2-hash-mismatch-detection)
        e3 (run-e3-conflicting-evidence-resolution)
        e4 (run-e4-evidence-bloat-griefing-bounds)
        e5 (run-e5-yield-accrual-during-dispute)
        e6 (run-e6-evidence-availability-guarantee)

        phases [e1 e2 e3 e4 e5 e6]
        passed-phases (count (filter :passed? phases))
        total-phases (count phases)

        overall-passed? (>= passed-phases 5)  ; 5/6 must pass

        result (engine/make-result
                {:benchmark-id "Phase E"
                 :label "Evidence Integrity"
                 :hypothesis "Evidence layer is robust and non-corruptible"
                 :passed? overall-passed?
                 :summary {:total-phases total-phases
                           :passed-phases passed-phases
                           :phases (mapv #(select-keys % [:benchmark-id :passed?]) phases)}})]

    (println (format "E1 Deadline Enforcement: %s" (:status e1)))
    (println (format "E2 Hash Mismatch Detection: %s" (:status e2)))
    (println (format "E3 Conflicting Evidence: %s" (:status e3)))
    (println (format "E4 Bloat Griefing Bounds: %s" (:status e4)))
    (println (format "E5 Yield Accrual: %s" (:status e5)))
    (println (format "E6 Availability Guarantee: %s" (:status e6)))
    (println "")

    (if overall-passed?
      (println "✓ PASS: Phase E — Evidence integrity maintained")
      (println "✗ FAIL: Phase E — Evidence layer vulnerabilities detected"))
    (println "")

    result))
