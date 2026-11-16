(ns resolver-sim.research.sew.adversarial.trust-floor
  "Phase AC: Trust Floor & Emergency Onboarding.

   Safeguard for the Phase Z vulnerability: legitimacy collapse caused by the
   reflexive participation spiral (experienced resolvers exit → quality drops →
   user confidence drops → fewer disputes → fewer fees → more exits).

   This phase tests whether enforcing a minimum trust floor (≥ min-resolvers
   experienced resolvers must remain active) combined with emergency onboarding
   (new resolvers are fast-tracked when below floor) prevents legitimacy from
   collapsing below the critical threshold of 0.50.

   Model:
     - Trust score: weighted average of resolver experience levels (0–1)
     - Legitimacy: function of trust score and dispute backlog ratio
     - Exit probability: resolver exits when legitimacy × expected-income < threshold
     - Emergency onboarding: when active resolvers < min-resolvers, N new resolvers
       are added each epoch with base trust = onboarding-trust-level

   Hypothesis to confirm:
     With trust floor active, legitimacy never drops below 0.50 across 50 epochs
     even under adverse initial conditions (high-exit-pressure scenario).

   Phase AC Capacity Expansion (run-phase-ac-capacity-expansion):
     Structural constraint confirmed April 2026 — at 100 disputes/epoch with
     pool×capacity=150, pressure=0.667 and the trust floor fails regardless of
     onboarding params (0/36 viable in threshold search).

     Analytical prediction: resolver-capacity × pool-size ≥ 10× disputes/epoch
     makes the trust floor viable (pressure ≤ 0.10 leaves enough headroom).

     This sweep tests that prediction empirically by sweeping (n-resolvers ×
     resolver-capacity) and finding the actual phase-transition multiplier."
  (:require [resolver-sim.stochastic.rng :as rng]
            [resolver-sim.sim.engine      :as proto]
            [resolver-sim.protocols.sew.config :as config]))

;; ---------------------------------------------------------------------------
;; Trust and legitimacy model
;; ---------------------------------------------------------------------------

(defn trust-score
  "Weighted average experience of active resolvers.
   experience = 0.0 (new) → 1.0 (fully seasoned)"
  [resolvers]
  (if (empty? resolvers)
    0.0
    (/ (reduce + (map :experience resolvers)) (count resolvers))))

(defn compute-pressure
  "Instantaneous backlog pressure = disputes / (capacity × resolvers), capped at 1.0."
  [disputes-per-epoch resolver-capacity n-resolvers]
  (min 1.0 (/ disputes-per-epoch (max 1.0 (* resolver-capacity (double n-resolvers))))))

(defn rolling-avg-pressure
  "Average of the last rolling-window pressure samples.
   Uses all available history when fewer than rolling-window samples have been recorded."
  [pressure-history current-pressure rolling-window]
  (let [window (take-last rolling-window (conj pressure-history current-pressure))]
    (/ (reduce + window) (count window))))

(defn legitimacy
  "Legitimacy = trust-score × (1 − effective-pressure).
   effective-pressure is the rolling-window average of backlog pressure,
   smoothing transient spikes to prevent premature collapse detection."
  [resolvers effective-pressure]
  (* (trust-score resolvers) (- 1.0 effective-pressure)))

(defn exit-probability
  "Probability a resolver exits this epoch.
   High legitimacy and high fee-income reduce exit probability.
   Baseline exit noise = 5% per epoch."
  [leg fee-per-dispute]
  (let [income-factor (min 1.0 (/ fee-per-dispute 100.0))   ; normalised to 100
        retention     (* leg income-factor)
        raw           (- 0.90 (* retention 0.85))]          ; range [0.05, 0.90]
    (max 0.05 (min 0.90 raw))))

;; ---------------------------------------------------------------------------
;; Single epoch step
;; ---------------------------------------------------------------------------

(defn step-epoch
  "Advance the resolver pool by one epoch.

   Uses a rolling-window average of backlog pressure (rather than a single-epoch
   snapshot) to compute legitimacy, smoothing transient spikes.

   Returns updated resolver list with exits removed, experiences incremented,
   and new emergency onboarding entrants added if below floor.
   Also returns :pressure-history (updated window) for threading."
  [resolvers disputes-per-epoch resolver-capacity fee-per-dispute
   min-resolvers onboarding-trust emergency-per-epoch d-rng floor-active?
   pressure-history rolling-window]
  (let [instant-p    (compute-pressure disputes-per-epoch resolver-capacity (count resolvers))
        eff-p        (rolling-avg-pressure pressure-history instant-p rolling-window)
        leg          (legitimacy resolvers eff-p)
        exit-p       (exit-probability leg fee-per-dispute)
        ;; Apply exits
        surviving    (filter (fn [r] (>= (rng/next-double d-rng) exit-p)) resolvers)
        ;; Increment experience (capped at 1.0)
        matured      (mapv (fn [r] (update r :experience #(min 1.0 (+ % 0.05)))) surviving)
        ;; Emergency onboarding when below floor (only if floor is active)
        below-floor? (and floor-active? (< (count matured) min-resolvers))
        n-to-onboard (if below-floor? emergency-per-epoch 0)
        new-batch    (repeatedly n-to-onboard #(hash-map :experience onboarding-trust))
        final        (into matured new-batch)
        new-history  (vec (take-last rolling-window (conj pressure-history instant-p)))]
    {:resolvers        final
     :legitimacy       leg
     :instant-pressure instant-p
     :eff-pressure     eff-p
     :exit-count       (- (count resolvers) (count surviving))
     :onboarded        n-to-onboard
     :below-floor      below-floor?
     :pressure-history new-history}))

;; ---------------------------------------------------------------------------
;; Single scenario simulation
;; ---------------------------------------------------------------------------

(defn run-trust-floor-scenario
  "Simulate n-epochs with the given trust floor configuration.

   Uses a 5-epoch rolling average for backlog pressure (see step-epoch).
   Returns {:epoch-results [...] :min-legitimacy f :legitimacy-collapsed? bool}.

   Optional kw args:
     :initial-experience-lo / :initial-experience-hi — override the uniform
       initial experience range (default 0.3–0.7).  Use 0.8/1.0 to simulate
       a pre-seasoned resolver pool."
  [n-epochs n-resolvers-initial disputes-per-epoch resolver-capacity fee-per-dispute
   min-resolvers onboarding-trust emergency-per-epoch floor-active? seed
   & {:keys [initial-experience-lo initial-experience-hi]
      :or   {initial-experience-lo 0.3
             initial-experience-hi 0.7}}]
  (let [d-rng             (rng/make-rng seed)
        rolling-window    5
        exp-range         (- initial-experience-hi initial-experience-lo)
        initial-resolvers (vec (repeatedly n-resolvers-initial
                                           #(hash-map :experience
                                                      (+ initial-experience-lo
                                                         (* exp-range (rng/next-double d-rng))))))
        epoch-results
        (loop [resolvers        initial-resolvers
               epoch            1
               results          []
               pressure-history []]
          (if (> epoch n-epochs)
            results
            (let [step (step-epoch resolvers disputes-per-epoch resolver-capacity
                                   fee-per-dispute min-resolvers onboarding-trust
                                   emergency-per-epoch d-rng floor-active?
                                   pressure-history rolling-window)]
              (recur (:resolvers step)
                     (inc epoch)
                     (conj results (assoc step :epoch epoch
                                          :resolver-count (count (:resolvers step))))
                     (:pressure-history step)))))]
    {:epoch-results        epoch-results
     :min-legitimacy       (apply min (map :legitimacy epoch-results))
     :final-resolver-count (:resolver-count (last epoch-results))
     :legitimacy-collapsed? (some #(< (:legitimacy %) 0.50) epoch-results)}))

;; ---------------------------------------------------------------------------
;; Full Phase AC sweep
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Threshold search: find minimum viable (emergency-per-epoch, onboarding-trust,
;; fee-per-dispute) that prevents legitimacy collapse.
;;
;; The exit-probability function is parameterised by legitimacy and fee income:
;;   exit-p = 0.90 - 0.85 × (legitimacy × min(1, fee/100))
;; This bottoms out at 0.05 (irreducible noise) only when leg = 1 and fee ≥ 100.
;; Below fee = 100, income-factor < 1, capping retention and raising exit-p.
;;
;; The search sweeps three levers:
;;   1. fee-per-dispute ∈ {50, 100, 150}       — income normalisation break-even
;;   2. emergency-per-epoch ∈ {2, 4, 6, 8}    — onboarding throughput
;;   3. onboarding-trust ∈ {0.2, 0.5, 0.8}    — quality of emergency batch
;;
;; For each cell it reports min-legitimacy and whether collapse occurred.
;; ---------------------------------------------------------------------------

(defn run-phase-ac-threshold-sweep
  "3-axis threshold search for minimum viable trust-floor configuration.
   Sweeps fee-per-dispute × emergency-per-epoch × onboarding-trust.
   Returns a grid result map and the minimum viable configuration found."
  [params]
  (let [seed               (get params :rng-seed 42)
        n-epochs           (get params :n-epochs 50)
        n-resolvers        (get params :n-resolvers 10)
        disputes-per-epoch (get params :disputes-per-epoch 100)
        resolver-capacity  (get params :resolver-capacity 15)
        min-resolvers      (get params :min-resolvers 7)   ; ceil(100/15) = 7
        fees               [50 100 150]
        emergencies        [2 4 6 8]
        trusts             [0.2 0.5 0.8]]

    (println "\n📊 PHASE AC THRESHOLD SEARCH")
    (println "   Sweeping: fee-per-dispute × emergency-per-epoch × onboarding-trust")
    (println (format "   Dispute load: %d/epoch, resolver capacity: %d/resolver"
                     disputes-per-epoch resolver-capacity))
    (println (format "   Min viable pool: %d resolvers (ceil(%d/%d))"
                     (int (Math/ceil (/ disputes-per-epoch resolver-capacity)))
                     disputes-per-epoch resolver-capacity))
    (println "")

    (let [grid-results
          (for [fee fees
                emer emergencies
                trust trusts
                :let [r (run-trust-floor-scenario
                         n-epochs n-resolvers disputes-per-epoch resolver-capacity
                         fee min-resolvers trust emer true seed)]]
            {:fee         fee
             :emergency   emer
             :trust       trust
             :min-leg     (:min-legitimacy r)
             :final-pool  (:final-resolver-count r)
             :collapsed?  (:legitimacy-collapsed? r)
             :passes?     (not (:legitimacy-collapsed? r))})

          viable (filter :passes? grid-results)

          ;; Minimum viable: smallest (fee, emergency, trust) that passes
          ;; Prioritise lowest emergency (cheapest safeguard), then lowest fee
          min-viable (->> viable
                          (sort-by (juxt :emergency :fee :trust))
                          first)]

      ;; Print summary table
      (println "   fee/disp  emer/epoch  trust  min-leg  pool  result")
      (println "   ────────  ──────────  ─────  ───────  ────  ──────")
      (doseq [{:keys [fee emergency trust min-leg final-pool passes?]} grid-results]
        (println (format "   %-9d %-11d %-6.1f %-8.3f %-5d %s"
                         fee emergency trust min-leg final-pool
                         (if passes? "✅ STABLE" "❌ SPIRAL"))))
      (println "")

      (println "═══════════════════════════════════════════════════")
      (println "📋 PHASE AC THRESHOLD SEARCH SUMMARY")
      (println "═══════════════════════════════════════════════════")
      (println (format "   Viable configurations found: %d / %d"
                       (count viable) (count grid-results)))
      (if min-viable
        (do
          (println (format "   Minimum viable config:"))
          (println (format "     fee-per-dispute    = %d" (:fee min-viable)))
          (println (format "     emergency-per-epoch = %d" (:emergency min-viable)))
          (println (format "     onboarding-trust   = %.1f" (:trust min-viable)))
          (println (format "     Min legitimacy     = %.3f" (:min-leg min-viable)))
          (println "")
          (println "   Recommendation: Implement trust floor with above minimum config")
          (println "   Confidence impact: +5% (Phase Z vulnerability mitigated)"))
        (do
          (println "   ❌ NO viable configuration found in search space")
          (println "   Structural constraint: exit-probability floor too high for")
          (println (format "     this dispute load (%d/epoch) — reduce load or redesign" disputes-per-epoch))
          (println "     exit-probability formula before deploying trust floor")))
      (println "")

      (proto/make-result
       {:benchmark-id "AC-threshold"
        :label        "Trust Floor Threshold Search"
        :hypothesis   "A minimum viable (floor, capacity) configuration exists"
        :passed?      (some? min-viable)
        :results      (vec grid-results)
        :summary      {:viable-count (count viable) :total-count (count grid-results)
                       :min-viable min-viable}})))

  (defn run-phase-ac-sweep
    "Test trust floor configurations against legitimacy collapse scenarios."
    [params]
    (let [seed               (get params :rng-seed 42)
          n-epochs           (get params :n-epochs 50)
          n-resolvers        (get params :n-resolvers 10)
          disputes-per-epoch (get params :disputes-per-epoch 100)
          resolver-capacity  (get params :resolver-capacity 15)
          fee-per-dispute    (get params :fee-per-dispute 50)
          min-resolvers      (get params :min-resolvers 3)
          onboarding-trust   (get params :onboarding-trust 0.20)
          emergency-per-ep   (get params :emergency-per-epoch 2)]

      (println "\n📊 PHASE AC: TRUST FLOOR & EMERGENCY ONBOARDING")
      (println "   Hypothesis: trust floor keeps legitimacy ≥ 0.50 across 50 epochs")
      (println "")

      (let [scenarios
            [{:label "No floor (baseline)"           :floor-active? false}
             {:label "Trust floor active"            :floor-active? true}
             {:label "Floor + aggressive onboarding" :floor-active? true
              :emergency-override (* 2 emergency-per-ep)}]

            results
            (mapv (fn [{:keys [label floor-active? emergency-override]}]
                    (println (format "   Testing: %s" label))
                    (let [emer (or emergency-override emergency-per-ep)
                          r    (run-trust-floor-scenario
                                n-epochs n-resolvers disputes-per-epoch resolver-capacity
                                fee-per-dispute min-resolvers onboarding-trust emer
                                floor-active? seed)]
                      (println (format "     Min legitimacy: %.3f  Collapsed? %s"
                                       (:min-legitimacy r)
                                       (if (:legitimacy-collapsed? r) "❌ YES" "✅ NO")))
                      (println (format "     Final resolvers: %d" (:final-resolver-count r)))
                      {:label              label
                       :floor-active?      floor-active?
                       :min-legitimacy     (:min-legitimacy r)
                       :final-resolvers    (:final-resolver-count r)
                       :collapsed?         (:legitimacy-collapsed? r)
                       :passes?            (not (:legitimacy-collapsed? r))}))
                  scenarios)

            class-a           (count (filter :passes? results))
            class-c           (count (remove :passes? results))
            hypothesis-holds? (some #(and (:floor-active? %) (:passes? %)) results)]

        (println "\n═══════════════════════════════════════════════════")
        (println "📋 PHASE AC SUMMARY")
        (println "═══════════════════════════════════════════════════")
        (println (format "   Safe (A): %d  Collapsed (C): %d" class-a class-c))
        (println (format "   Hypothesis holds? %s"
                         (if hypothesis-holds?
                           "✅ YES — trust floor prevents legitimacy collapse"
                           "❌ NO — trust floor insufficient; spiral still occurs")))
        (println "")
        (if hypothesis-holds?
          (do (println "   Recommendation: Implement trust floor with min-resolvers ≥ 3")
              (println "   Confidence impact: +5% (Phase Z vulnerability mitigated)"))
          (do (println "   Recommendation: Raise emergency onboarding rate or onboarding trust")
              (println "   Confidence impact: 0%")))
        (println "")

        (proto/make-result
         {:benchmark-id "AC"
          :label        "Trust Floor & Emergency Onboarding"
          :hypothesis   "Trust floor keeps attacker win rate < 20% and stabilises participation"
          :passed?      hypothesis-holds?
          :results      results
          :summary      {:class-a class-a :class-c class-c}})))

;; ---------------------------------------------------------------------------
;; Phase AC Capacity Expansion
;; ---------------------------------------------------------------------------
;;
;; Structural constraint (confirmed April 2026): at 100 disputes/epoch with
;; pool×capacity=150 (1.5× multiplier), pressure=0.667 → legitimacy ceiling
;; is trust-score × 0.333 — never reaches 0.50 even with a fully experienced
;; pool. The trust floor fails regardless of onboarding params (0/36 viable).
;;
;; Analytical prediction: resolver-capacity × pool-size ≥ 10× disputes/epoch
;; (pressure ≤ 0.10) leaves sufficient headroom for the trust floor to hold.
;;
;; This sweep tests that prediction empirically. Two sub-sweeps:
;;   1. Recruit sweep  — fix resolver-capacity=15, vary n-resolvers (1× → 10.5×)
;;   2. Upgrade sweep  — fix n-resolvers=10,      vary resolver-capacity (1× → 10×)
;; Each cell is tested at three effort levels (baseline / medium / best) across
;; three seeds, so each cell reports "fraction of seeds that pass".
;; ---------------------------------------------------------------------------

    (def ^:private effort-levels config/EFFORT_LEVELS)

    (defn- test-capacity-config
      "Run one (n-resolvers × resolver-capacity) cell at three effort levels and
   multiple seeds.  Returns a summary map including the phase-transition signal.

   Optional kw args:
     :initial-experience-lo / :hi — override initial resolver experience range
       (default 0.3–0.7).  Pass 0.8/1.0 to simulate a pre-seasoned pool.
     :min-resolvers-override — override the floor threshold (default: ceil(disputes/cap))."
      [n-resolvers resolver-capacity disputes-per-epoch n-epochs seeds
       & {:keys [initial-exp-lo initial-exp-hi min-resolvers-override]
          :or   {initial-exp-lo 0.3
                 initial-exp-hi 0.7}}]
      (let [total-cap  (* n-resolvers resolver-capacity)
            multiplier (double (/ total-cap disputes-per-epoch))
            pressure   (double (/ disputes-per-epoch (double total-cap)))
            min-res    (or min-resolvers-override
                           (max 3 (int (Math/ceil (/ disputes-per-epoch (double resolver-capacity))))))

        ;; For each (effort-level × seed), run the scenario
            cell-results
            (for [{:keys [label fee emer trust]} effort-levels
                  seed seeds
                  :let [r (run-trust-floor-scenario
                           n-epochs n-resolvers disputes-per-epoch resolver-capacity
                           fee min-res trust emer true seed
                           :initial-experience-lo initial-exp-lo
                           :initial-experience-hi initial-exp-hi)]]
              {:level      label
               :seed       seed
               :min-leg    (:min-legitimacy r)
               :passes?    (not (:legitimacy-collapsed? r))})

        ;; Pass rate at each effort level (fraction of seeds passing)
            pass-rate-by-level
            (into {}
                  (for [{:keys [label]} effort-levels
                        :let [rows (filter #(= label (:level %)) cell-results)
                              n    (count rows)
                              p    (count (filter :passes? rows))]]
                    [label (if (pos? n) (double (/ p n)) 0.0)]))

            best-pass-rate (apply max (vals pass-rate-by-level))
            best-min-leg   (apply max (map :min-leg cell-results))
        ;; A cell "passes" if the best effort level passes on ≥ 2/3 seeds
            passes?        (>= (get pass-rate-by-level :best 0.0) (/ 2.0 3.0))]

        {:n-resolvers   n-resolvers
         :resolver-cap  resolver-capacity
         :total-cap     total-cap
         :multiplier    multiplier
         :pressure      pressure
         :min-resolvers min-res
         :passes?       passes?
         :best-pass-rate best-pass-rate
         :best-min-leg  best-min-leg
         :pass-rate     pass-rate-by-level
         :cell-results  (vec cell-results)}))

    (defn- print-capacity-table
      [title rows disputes-per-epoch]
      (println (format "\n  %s" title))
      (println (format "  %-12s %-12s %-8s %-8s %-8s  base  med  best  verdict"
                       "n-resolvers" "cap/resolver" "total" "mult" "pressure"))
      (println (str "  " (apply str (repeat 75 "─"))))
      (doseq [{:keys [n-resolvers resolver-cap total-cap multiplier pressure
                      passes? pass-rate best-min-leg]} rows]
        (println (format "  %-12d %-12d %-8d %-8.1f %-8.3f %4.0f%% %4.0f%% %4.0f%%  %s"
                         n-resolvers resolver-cap total-cap multiplier pressure
                         (* 100 (get pass-rate :baseline 0.0))
                         (* 100 (get pass-rate :medium   0.0))
                         (* 100 (get pass-rate :best     0.0))
                         (if passes? "✅ VIABLE" "❌ FAILS")))))

    (defn run-phase-ac-capacity-expansion
      "Phase AC Capacity Expansion: empirically validate the 10× capacity rule,
   then run root-cause diagnostics when the rule is insufficient.

   Two primary sweeps:
     1. Recruit sweep  — fix resolver-capacity=15, grow pool (1.5× → 10.5×)
     2. Upgrade sweep  — fix pool-size=10,          grow capacity (1.5× → 10×)

   If the primary sweeps find no viable configs, three diagnostic variants are
   tested at the highest capacity point (10×) to identify the root cause:
     A. Mature pool      — initial experience 0.8–1.0 instead of 0.3–0.7
     B. High floor       — min-resolvers = 50% of initial pool (early trigger)
     C. Mature + high floor — both changes combined

   Hypothesis: viability first appears at ≥ 10× multiplier (total-cap = 1000
   at 100 disputes/epoch)."
      [params]
      (let [seed               (get params :rng-seed 42)
            n-epochs           (get params :n-epochs 50)
            disputes-per-epoch (get params :disputes-per-epoch 100)
            seeds              [seed (+ seed 1) (+ seed 2)]

            predicted-threshold (* 10 disputes-per-epoch)   ; 1000

        ;; Sweep 1: recruit more resolvers (resolver-cap fixed at baseline 15)
            _ (println "\n── SWEEP 1: Recruit more resolvers (resolver-capacity=15 fixed) ──────────")
            recruit-configs
            (doall
             (for [n [10 15 20 25 30 40 50 70]]
               (do (print (format "   [recruit] n-resolvers=%-3d cap=15 ... " n))
                   (flush)
                   (let [r (test-capacity-config n 15 disputes-per-epoch n-epochs seeds)]
                     (println (if (:passes? r) "✅" "❌"))
                     r))))

        ;; Sweep 2: upgrade per-resolver capacity (pool fixed at baseline 10)
            _ (println "\n── SWEEP 2: Upgrade per-resolver capacity (pool-size=10 fixed) ────────────")
            upgrade-configs
            (doall
             (for [cap [15 20 30 40 60 80 100]]
               (do (print (format "   [upgrade] n-resolvers=10  cap=%-3d ... " cap))
                   (flush)
                   (let [r (test-capacity-config 10 cap disputes-per-epoch n-epochs seeds)]
                     (println (if (:passes? r) "✅" "❌"))
                     r))))

            all-configs    (concat recruit-configs upgrade-configs)
            viable         (filter :passes? all-configs)
            failed         (remove :passes? all-configs)]

        (println "\n╔══════════════════════════════════════════════════════════════════════════╗")
        (println   "║  PHASE AC CAPACITY EXPANSION                                             ║")
        (println   "╚══════════════════════════════════════════════════════════════════════════╝")
        (println (format "   Structural failure load: %d disputes/epoch" disputes-per-epoch))
        (println (format "   Predicted minimum viable total-capacity: %d× (%d)"
                         10 predicted-threshold))
        (println "   Testing: 3 effort levels × 3 seeds per cell\n")

        (print-capacity-table "Recruit sweep (cap=15 fixed)" recruit-configs disputes-per-epoch)
        (print-capacity-table "Upgrade sweep (pool=10 fixed)" upgrade-configs disputes-per-epoch)

        (let [min-viable-mult (->> viable (map :multiplier) (apply min Double/MAX_VALUE))
              min-viable-cap  (->> viable (map :total-cap)  (apply min Integer/MAX_VALUE))
              max-failed-mult (->> failed (map :multiplier) (apply max 0.0))
              ten-x-all-pass? (every? :passes?
                                      (filter #(>= (:multiplier %) 10.0) all-configs))
              hypothesis-holds? (and (seq viable) ten-x-all-pass?)

          ;; ── Root-cause diagnostics (run when primary sweep fails) ────────
          ;; Pick the 10× point: n=70,cap=15 (recruit sweep) or n=10,cap=100 (upgrade)
          ;; Use upgrade 10× point (n=10, cap=100) as the canonical diagnostic target.
              diag-results
              (when (empty? viable)
                (println "\n── ROOT-CAUSE DIAGNOSTICS at 10× (n=10, cap=100) ─────────────────────────")
                (println "   Testing which design change (alone or combined) makes trust floor viable\n")
                (let [n-diag  10
                      cap-diag 100
                      high-floor (* n-diag 50/100)  ; 50% of initial pool
                      diag-cases
                      [{:label "A. Mature pool (exp 0.8–1.0)"
                        :exp-lo 0.8 :exp-hi 1.0 :min-res nil}
                       {:label "B. High floor (min-res=50% pool)"
                        :exp-lo 0.3 :exp-hi 0.7 :min-res (int high-floor)}
                       {:label "C. Mature pool + high floor"
                        :exp-lo 0.8 :exp-hi 1.0 :min-res (int high-floor)}]]
                  (doall
                   (for [{:keys [label exp-lo exp-hi min-res]} diag-cases]
                     (do (print (format "   %-38s ... " label))
                         (flush)
                         (let [r (test-capacity-config n-diag cap-diag disputes-per-epoch n-epochs seeds
                                                       :initial-exp-lo exp-lo
                                                       :initial-exp-hi exp-hi
                                                       :min-resolvers-override min-res)]
                           (println (if (:passes? r) "✅ VIABLE" "❌ FAILS"))
                           (assoc r :diag-label label
                                  :exp-lo exp-lo :exp-hi exp-hi
                                  :min-res-override min-res)))))))]

      ;; Print diagnostics table if we ran it
          (when diag-results
            (println "")
            (println "  Label                                  total    mult    pressure  base  med  best  verdict")
            (println (str "  " (apply str (repeat 88 "─"))))
            (doseq [{:keys [diag-label total-cap multiplier pressure passes? pass-rate]} diag-results]
              (println (format "  %-38s %-8d %-7.1f %-9.3f %4.0f%% %4.0f%% %4.0f%%  %s"
                               diag-label total-cap multiplier pressure
                               (* 100 (get pass-rate :baseline 0.0))
                               (* 100 (get pass-rate :medium   0.0))
                               (* 100 (get pass-rate :best     0.0))
                               (if passes? "✅ VIABLE" "❌ FAILS")))))

          (println "\n═══════════════════════════════════════════════════════════════════════════")
          (println "📋 PHASE AC CAPACITY EXPANSION — SUMMARY")
          (println "═══════════════════════════════════════════════════════════════════════════")
          (println (format "   Viable configs (primary): %d / %d" (count viable) (count all-configs)))
          (if (seq viable)
            (do
              (println (format "   Phase transition: viability first appears at %.1f× (total-cap=%d)"
                               min-viable-mult min-viable-cap))
              (println (format "   All configs at ≥10×: %s" (if ten-x-all-pass? "✅ ALL PASS" "❌ SOME FAIL"))))
            (println "   ❌ No viable config in primary sweep — capacity alone is insufficient"))

          (when diag-results
            (let [diag-viable (filter :passes? diag-results)]
              (println (format "   Viable diagnostic variants: %d / %d" (count diag-viable) (count diag-results)))
              (doseq [{:keys [diag-label]} diag-viable]
                (println (format "     ✅ %s" diag-label)))))
          (println "")

          (println (format "   10× rule confirmed? %s"
                           (if hypothesis-holds?
                             (format "✅ YES — trust floor viable at total-cap ≥ %d" predicted-threshold)
                             "❌ NO — capacity expansion alone is insufficient")))
          (println "")

      ;; Conclusions
          (if hypothesis-holds?
            (do
              (println "   Design recommendation:")
              (println (format "     Enforce resolver-capacity × pool-size ≥ %d in protocol params" predicted-threshold))
              (println      "   Confidence impact: +5% (Phase AC structural constraint resolved)")
              (println      "   Phase AC status: ✅ CONFIRMED WITH CAPACITY EXPANSION"))
            (let [diag-viable (when diag-results (filter :passes? diag-results))]
              (println "   Root cause: exit-probability cold-start spiral")
              (println "     At 10× (pressure=0.10), initial pool (exp≈0.5) still faces exit-p≈0.52/epoch")
              (println "     Pool collapses before resolvers can accumulate experience")
              (println "")
              (println "   Design recommendations:")
              (if (seq diag-viable)
                (do
                  (println "     ONE OR MORE of the following changes makes the trust floor viable:")
                  (doseq [{:keys [diag-label]} diag-viable]
                    (println (format "       → %s" diag-label)))
                  (println "     Minimum viable protocol change: implement whichever is cheapest above"))
                (do
                  (println "     None of the tested variants succeeded — exit model requires deeper redesign")
                  (println "     Consider: reducing the exit-probability floor (currently 0.05) or")
                  (println "       adding a resolver lock-up period during the bootstrapping phase")))
              (println "")
              (println "   Phase AC status: ❌ CAPACITY EXPANSION INSUFFICIENT — exit model redesign needed")))
          (println "")

          (proto/make-result
           {:benchmark-id "AC-cap"
            :label        "Trust Floor Capacity Expansion"
            :hypothesis   "10x capacity rule resolves exit cascade"
            :passed?      hypothesis-holds?
            :results      (vec (concat recruit-configs upgrade-configs))
            :summary      {:viable-count (count viable) :total-count (count all-configs)
                           :ten-x-all-pass? ten-x-all-pass?}}))))))
