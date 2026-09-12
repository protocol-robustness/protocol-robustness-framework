(ns resolver-sim.research.sew.adversarial.escalation-trap
  "Phase AI: Escalation Trap — sybil ring forces honest resolver displacement.

   Models the interaction between whale-delegated capital and forced L2 escalation.
   A sybil ring escalates every dispute it handles to L2, forcing opposing honest
   resolvers to post appeal bonds. Honest resolvers whose cumulative bond costs
   exceed their starting capital exit the system. The ring gains market share as
   honest capital drains.

   Hypothesis:
     A sybil ring of size ≥ ring-size can displace > 50% of honest resolvers
     within 200 epochs by forcing repeated appeal-bond costs.

   Two measurable outputs:
     1. displacement-trajectory  — honest-share per epoch (gradual vs sudden)
     2. displacement-rate        — final honest-active / initial-honest-count

   Phase metadata:
     {:phase/id :phase-ai
      :trajectory/classes [:displacement :equity-divergence]}

   Parameters: data/params/phase-ai-escalation-trap.edn
   Entry point: (run-phase-ai params)"
  (:require [resolver-sim.sim.trajectory :as trajectory]
            [resolver-sim.sim.adversarial.ring-model :as ring-model]
            [resolver-sim.stochastic.rng :as rng]))

(def phase-metadata
  {:phase/id           :phase-ai
   :trajectory/classes [:displacement :equity-divergence]
   :hypothesis         "Sybil ring ≥ ring-size displaces >50% of honest resolvers within 200 epochs."})

;; ---------------------------------------------------------------------------
;; Escalation trap epoch model
;; ---------------------------------------------------------------------------

(defn- run-escalation-epoch-ring-model
  "Run one epoch using the per-member ring model (`:use-ring-model? true`).

   Delegates ring evolution to `ring-model/evolve-ring-epoch` for per-member
   stochastic detection, cascade signalling, and entry dynamics.

   Returns {:honest-capitals updated-map
            :honest-active   int
            :ring-active     int
            :ring            updated-ring
            :ring-events     {:exits [...] :entries [...] :lay-low? bool}
            :epoch           int}"
  [rng epoch honest-capitals ring params]
  (let [{:keys [ring exits entries lay-low? active-count]}
        (ring-model/evolve-ring-epoch rng ring params epoch)

        ;; Track last escalation and total count for bond scaling
        last-esc-epoch  (or (:last-esc-epoch ring) 0)
        total-escalations (or (:total-escalations ring) 0)
        delay-epochs    (:escalation-delay-epochs params 0)
        bond-multiplier (:bond-multiplier params 1.0) ;; Multiplier for exponential bond scaling

        ;; Allow escalation only if delay passed
        can-escalate?   (>= (- epoch last-esc-epoch) delay-epochs)

        ring-active     active-count
        n-trials        (:n-trials-per-epoch params 100)
        escrow-size     (:escrow-size params 10000)
        fee-bps         (:resolver-fee-bps params 150)

        ;; If blocked, effective escalation rate drops to 0
        effective-rate  (if can-escalate? (:escalation-rate params 1.0) 0.0)

        ;; Dynamic bond cost: escalate exponentially
        base-bond-cost  (:escalation-cost-per-dispute params 1000)
        bond-cost       (* base-bond-cost (Math/pow bond-multiplier total-escalations))

        total-resolvers (+ (count honest-capitals) ring-active)
        ring-fraction   (if (pos? total-resolvers)
                          (double (/ ring-active total-resolvers))
                          0.0)

        ;; Total escalations forced by the ring
        total-escalations-forced (* ring-fraction n-trials effective-rate)

        ;; Drain per honest resolver: total cost / number of honest resolvers
        capital-drain       (if (pos? (count honest-capitals))
                              (/ (* total-escalations-forced bond-cost) (count honest-capitals))
                              0.0)

        fee-per-dispute     (* escrow-size (/ fee-bps 10000.0))
        honest-income       (* (- 1.0 ring-fraction) n-trials fee-per-dispute
                               (/ 1.0 (max 1 (count honest-capitals))))

        updated-caps
        (reduce-kv (fn [m id cap]
                     (let [new-cap (+ cap honest-income (- capital-drain))]
                       (if (> new-cap 0) (assoc m id new-cap) m)))
                   {}
                   honest-capitals)

        ;; Update last escalation epoch and total escalation count if successful
        new-ring (if (and can-escalate? (pos? total-escalations-forced))
                   (-> ring
                       (assoc :last-esc-epoch epoch)
                       (assoc :total-escalations (inc total-escalations)))
                   ring)]

    {:honest-capitals updated-caps
     :honest-active   (count updated-caps)
     :ring-active     ring-active
     :ring            new-ring
     :ring-events     {:exits exits :entries entries :lay-low? lay-low? :blocked? (not can-escalate?) :bond-cost bond-cost}
     :epoch           epoch}))

(defn- run-escalation-epoch
  "Run one epoch of the escalation trap model (aggregate path).

   honest-capitals — {resolver-id → remaining-capital}
   ring-active     — current number of ring members still active

   Returns {:honest-capitals updated-map
            :honest-active   int
            :ring-active     int
            :epoch           int}"
  [epoch honest-capitals ring-active params]
  (let [n-trials       (:n-trials-per-epoch params 100)
        escrow-size    (:escrow-size params 10000)
        fee-bps        (:resolver-fee-bps params 150)
        escalation-rate (:escalation-rate params 1.0)
        bond-cost      (:escalation-cost-per-dispute params 1000)
        ring-size      (:ring-size params 5)

        ; Each honest resolver faces a bond cost whenever assigned to a ring-handled dispute.
        total-resolvers (+ (count honest-capitals) ring-active)
        ring-fraction   (if (pos? total-resolvers)
                          (double (/ ring-active total-resolvers))
                          0.0)

        ;; Total escalations forced by the ring
        total-escalations-forced (* ring-fraction n-trials escalation-rate)

        ;; Drain per honest resolver
        capital-drain       (if (pos? (count honest-capitals))
                              (/ (* total-escalations-forced bond-cost) (count honest-capitals))
                              0.0)

        ; Honest fee income = (1 - ring-fraction) * n-trials * fee per dispute.
        fee-per-dispute   (* escrow-size (/ fee-bps 10000.0))
        honest-income     (* (- 1.0 ring-fraction) n-trials fee-per-dispute
                             (/ 1.0 (max 1 (count honest-capitals))))

        ; Update each honest resolver's capital: subtract bond costs, add fee income.
        updated-caps
        (reduce-kv (fn [m id cap]
                     (let [new-cap (+ cap honest-income (- capital-drain))]
                       (if (> new-cap 0)
                         (assoc m id new-cap)
                         ; Resolver exits (capital exhausted).
                         m)))
                   {}
                   honest-capitals)

        ; Ring loses members each epoch based on effective detection probability.
        ; Each ring member faces base-detection / ring-size per epoch.
        ; Expected exits = ring-active * effective-detection-rate.
        base-detection  (:fraud-detection-probability params 0.05)
        eff-det         (if (pos? ring-size) (/ base-detection ring-size) base-detection)
        expected-exits  (* ring-active eff-det)
        ; Use a Bernoulli approximation: exit if stochastic draw < expected-exits.
        ; Deterministic floor ensures at least one member exits once eff-det is
        ; high enough to drive expected-exits ≥ 1.
        new-ring-active (max 0 (long (- ring-active (Math/floor expected-exits))))]

    {:honest-capitals updated-caps
     :honest-active   (count updated-caps)
     :ring-active     new-ring-active
     :epoch           epoch}))

;; ---------------------------------------------------------------------------
;; Phase AI simulation
;; ---------------------------------------------------------------------------

(defn run-phase-ai
  "Run Phase AI: Escalation Trap simulation.

   params — loaded from data/params/phase-ai-escalation-trap.edn.
   Returns a result map with :pass?, :displacement-trajectory, and phase metadata."
  [params]
  (println "\n🪤  Running Phase AI: Escalation Trap")
  (println (str "   Hypothesis: " (:hypothesis phase-metadata)))

  (let [n-epochs              (:n-epochs params 200)
        n-resolvers           (:n-resolvers params 60)
        ring-size             (:ring-size params 5)
        honest-capital        (:honest-capital params 5000)
        displacement-threshold (:displacement-threshold params 0.50)
        use-ring-model?       (:use-ring-model? params false)
        ring-detection-prob   (:ring-detection-prob params 0.0) ;; New detection parameter
        seed                  (:seed params 42)

        ; Initialise honest resolver capitals.
        honest-ids     (map #(str "honest-" %) (range n-resolvers))
        init-capitals  (zipmap honest-ids (repeat (double honest-capital)))

        _ (println (format "   Honest resolvers: %d (capital=%d each)" n-resolvers honest-capital))
        _ (println (format "   Ring size: %d  Epochs: %d  Threshold: %.0f%%  Mode: %s  Ring detection: %.1f%%\n"
                           ring-size n-epochs (* 100 displacement-threshold)
                           (if use-ring-model? "per-member" "aggregate")
                           (* 100 ring-detection-prob)))

        ; Run epoch loop.
        {:keys [epoch-counts final-capitals]}
        (if use-ring-model?
          ;; ── Per-member ring model path ───────────────────────────────────
          (let [init-ring (ring-model/make-ring ring-size 1)
                rng       (java.util.SplittableRandom. (long seed))]
            (loop [epoch    1
                   capitals init-capitals
                   ring     init-ring
                   counts   []]
              (if (> epoch n-epochs)
                {:epoch-counts (vec counts) :final-capitals capitals}
                (let [[rng-e _] (rng/split-rng rng)
                      ;; Apply ring detection modifier
                      params-e (assoc params :fraud-detection-probability
                                      (+ (:fraud-detection-probability params 0.05) ring-detection-prob))
                      {:keys [honest-capitals honest-active ring-active ring ring-events]}
                      (run-escalation-epoch-ring-model rng-e epoch capitals ring params-e)
                      entry {:epoch epoch :honest-active honest-active :ring-active ring-active
                             :ring-events ring-events}]
                  (when (zero? (mod epoch 20))
                    (println (format "   Epoch %3d: honest-active=%d  ring-active=%d  exits=%d  entries=%d  honest-share=%.0f%%"
                                     epoch honest-active ring-active
                                     (count (:exits ring-events))
                                     (count (or (:entries ring-events) []))
                                     (* 100.0 (/ honest-active (max 1 (+ honest-active ring-active)))))))
                  (recur (inc epoch) honest-capitals ring (conj counts entry))))))
          ;; ── Aggregate path (original behaviour) ──────────────────────────
          (loop [epoch    1
                 capitals init-capitals
                 ring-act ring-size
                 counts   []]
            (if (> epoch n-epochs)
              {:epoch-counts (vec counts) :final-capitals capitals}
              (let [{:keys [honest-capitals honest-active ring-active]}
                    (run-escalation-epoch epoch capitals ring-act params)
                    entry {:epoch epoch :honest-active honest-active :ring-active ring-active}]
                (when (zero? (mod epoch 20))
                  (println (format "   Epoch %3d: honest-active=%d  ring-active=%d  honest-share=%.0f%%"
                                   epoch honest-active ring-active
                                   (* 100.0 (/ honest-active (+ honest-active ring-active))))))
                (recur (inc epoch) honest-capitals ring-active (conj counts entry))))))

        disp-traj       (trajectory/displacement-trajectory epoch-counts)
        final-honest    (:honest-active (last epoch-counts) n-resolvers)
        disp-rate       (double (- 1.0 (/ final-honest n-resolvers)))
        pass?           (< disp-rate displacement-threshold)

        ; Classify displacement pattern (gradual, sudden, threshold-based, stable).
        half-idx        (int (/ (count disp-traj) 2))
        share-at-half   (:honest-share (nth disp-traj half-idx {:honest-share 1.0}))
        share-at-end    (:honest-share (last disp-traj) 1.0)
        pattern         (cond
                          (< share-at-end 0.1)             :collapse
                          (< (- 1.0 share-at-half) 0.05)  :sudden   ; most loss in 2nd half
                          (< disp-rate 0.10)               :stable
                          :else                            :gradual)]

    (println (format "\n%s  displacement-rate=%.0f%%  pattern=%s"
                     (if pass? "✓ PASS" "✗ FAIL")
                     (* 100 disp-rate)
                     (name pattern)))

    ; Add interpretation and recommendations
    (if pass?
      (do (println "")
          (println (format "   Status: ✅ PASS (%.0f%% displacement < %.0f%% threshold)"
                           (* 100 disp-rate)
                           (* 100 displacement-threshold)))
          (println "   Interpretation: Sybil ring attack is contained. Honest resolvers maintain market share.")
          (println "   Confidence delta: +5% (escalation trap well-defended)"))
      (do (println "")
          (println (format "   Status: ❌ FAIL (%.0f%% displacement ≥ %.0f%% threshold — pattern: %s)"
                           (* 100 disp-rate)
                           (* 100 displacement-threshold)
                           (name pattern)))
          (println "   Interpretation: ❌ CRITICAL — Sybil ring attack is EFFECTIVE")
          (println (format "   Impact: %s displacement pattern (reached %.0f%% honest exit)"
                           (name pattern)
                           (* 100.0 (- 1.0 (/ final-honest n-resolvers)))))
          (println "")
          (println "   Recommendations (Priority Order):")
          (println "   1. [CRITICAL] Implement ring detection via correlation analysis")
          (println "      - Flag resolvers with suspiciously high coordination (escalation timing)")
          (println "      - Reduce escalation vote weight for detected coalitions")
          (println "   2. [HIGH] Increase escalation bond requirements")
          (println "      - Higher cost per escalation attempt reduces sybil viability")
          (println "      - Test with 2–5× increase to escalation bond")
          (println "   3. [HIGH] Add escalation delays between coordinated calls")
          (println "      - Prevent same-block escalation spam")
          (println "      - Require 1+ epoch delay between escalations from same cohort")))

    {:phase/id           :phase-ai
     :trajectory/classes (:trajectory/classes phase-metadata)
     :hypothesis         (:hypothesis phase-metadata)
     :pass?              pass?
     :displacement-rate  disp-rate
     :displacement-pattern pattern
     :displacement-threshold displacement-threshold
     :displacement-trajectory disp-traj
     :final-honest-active final-honest
     :initial-honest-count n-resolvers
     :ring-size          ring-size
     :n-epochs           n-epochs
     :ring-model         (if use-ring-model? :per-member :aggregate)}))
