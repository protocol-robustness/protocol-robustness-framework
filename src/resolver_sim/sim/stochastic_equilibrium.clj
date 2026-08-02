(ns resolver-sim.sim.stochastic-equilibrium
  "Stochastic equilibrium bridge.

   Translates the output of run-multi-epoch into falsifiable population-level
   equilibrium claims. Unlike scenario/equilibrium (which evaluates a single
   deterministic replay trace), this namespace evaluates emergent properties
   across many epochs of a stochastic agent population.

   ## Why this exists

   Multi-epoch simulation (Phase J) produces aggregated stats and equity
   trajectories but never runs them through any theory evaluator. The claim
   'malice cannot dominate over time' was checked by printing numbers, not by
   a formal pass/fail falsification. This namespace closes that gap.

   ## Claim-strength taxonomy

   All checks are labelled :single-simulation-evidence — stronger than a single
   trace but weaker than an analytic proof or ensemble of independent runs.
   The :basis field in every result declares this explicitly.

   Mechanism-proxy checks (see evaluate-mechanism-proxies) use the basis
   :multi-epoch-population-proxy, which is weaker than :single-trace-terminal-proxy
   (the basis used by scenario/equilibrium for single-trace replay) but provides
   convergent evidence across many epochs that mechanism properties hold at the
   population level.

   ## Claim-strength correspondence with scenario/equilibrium

   | scenario/equilibrium basis         | stochastic-equilibrium basis          |
   |------------------------------------|---------------------------------------|
   | :single-trace-terminal-proxy       | :single-simulation-evidence           |
   | :single-trace-metric-proxy         | :multi-epoch-population-proxy         |
   | :multi-trace-required              | (fulfilled by multi-epoch evidence)   |
   | :multi-epoch-required              | :single-simulation-evidence           |

   The mechanism-proxy evaluators mirror the mechanism-property vocabulary from
   scenario/equilibrium.clj so the two evaluators can be cross-referenced.

   ## Layering

    sim/* may import sim/* per project rules. This namespace imports nothing
     outside sim/. All inputs are plain Clojure maps (no DB, no I/O)."
  (:require [resolver-sim.tools.participation-stability :as ps]))

;; ---------------------------------------------------------------------------
;; Shared result helpers
;; ---------------------------------------------------------------------------

(defn- pass [claim-id evidence detail]
  {:claim-id  claim-id
   :status    :pass
   :basis     :single-simulation-evidence
   :evidence  evidence
   :detail    detail})

(defn- fail [claim-id evidence detail]
  {:claim-id  claim-id
   :status    :fail
   :basis     :single-simulation-evidence
   :evidence  evidence
   :detail    detail})

(defn- inconclusive [claim-id reason]
  {:claim-id  claim-id
   :status    :inconclusive
   :basis     :single-simulation-evidence
   :reason    reason})

;; ---------------------------------------------------------------------------
;; Individual claim evaluators
;;
;; Each takes the multi-epoch result map from run-multi-epoch.
;; Inputs used:
;;   :aggregated-stats    — {:malice-cumulative-profit :honest-cumulative-profit
;;                            :malice-avg-win-rate :honest-avg-win-rate
;;                            :final-resolver-count :total-resolver-exits}
;;   :epoch-results       — [{:dominance-ratio :honest-mean-profit :malice-mean-profit ...}]
;;   :initial-resolver-count — from top-level result key
;; ---------------------------------------------------------------------------

(defn evaluate-malice-net-profit-negative
  "Claim: malicious resolvers end up with negative cumulative profit.

   Rationale: slashing penalties should exceed any gains from fraudulent
   resolutions over the course of the simulation.

   Metric: aggregated-stats :malice-cumulative-profit < 0.

   Note: this is aggregate across all malicious resolvers. A subset may be
   profitable if others absorb large slashes. A stronger check would be
   per-resolver profit distribution — not yet available here."
  [result]
  (let [stats  (:aggregated-stats result)
        profit (:malice-cumulative-profit stats)]
    (if (nil? profit)
      (inconclusive :malice-net-profit-negative "aggregated-stats missing from result")
      (if (neg? profit)
        (pass :malice-net-profit-negative
              {:malice-cumulative-profit profit}
              (format "Malice cumulative profit = %.0f < 0: slashing deters net profit" profit))
        (fail :malice-net-profit-negative
              {:malice-cumulative-profit profit}
              (format "Malice cumulative profit = %.0f ≥ 0: malice is net-profitable" profit))))))

(defn evaluate-honest-dominates
  "Claim: honest strategy dominates malicious in the final epoch.

   Uses the :dominance-ratio metric from the last epoch result.
   dominance-ratio > 1.2 means honest profit is ≥ 1.2× the mean resolver profit,
   which is a strong dominance signal.

   Falls back to comparing final-epoch honest-mean-profit vs malice-mean-profit
   if dominance-ratio is absent."
  [result]
  (let [epochs     (:epoch-results result)
        final-ep   (last epochs)
        dom-ratio  (:dominance-ratio final-ep)
        h-profit   (:honest-mean-profit final-ep)
        m-profit   (:malice-mean-profit final-ep)]
    (cond
      (nil? final-ep)
      (inconclusive :honest-dominates "no epoch-results in multi-epoch output")

      ;; Primary: dominance-ratio
      (and dom-ratio (>= dom-ratio 1.2))
      (pass :honest-dominates
            {:dominance-ratio dom-ratio :epoch (count epochs)}
            (format "dominance-ratio=%.2f ≥ 1.2 at final epoch %d" dom-ratio (count epochs)))

      (and dom-ratio (< dom-ratio 1.2))
      (fail :honest-dominates
            {:dominance-ratio dom-ratio :epoch (count epochs)}
            (format "dominance-ratio=%.2f < 1.2 at final epoch %d: malice is competitive" dom-ratio (count epochs)))

      ;; Fallback: direct profit comparison
      (and h-profit m-profit (> h-profit m-profit))
      (pass :honest-dominates
            {:honest-mean-profit h-profit :malice-mean-profit m-profit}
            (format "honest-mean=%.0f > malice-mean=%.0f at final epoch" h-profit m-profit))

      (and h-profit m-profit)
      (fail :honest-dominates
            {:honest-mean-profit h-profit :malice-mean-profit m-profit}
            (format "honest-mean=%.0f ≤ malice-mean=%.0f at final epoch" h-profit m-profit))

      :else
      (inconclusive :honest-dominates "missing dominance-ratio and mean-profit data in final epoch"))))

(defn evaluate-slashing-deters
  "Claim: malicious resolvers have a lower win rate than honest resolvers.

   Win rate is the fraction of trials that produce a positive verdict for the
   resolver. Slashing should cause malicious resolvers to lose significantly
   more trials (via detection + slash) than honest ones.

   Metric: aggregated-stats :malice-avg-win-rate < :honest-avg-win-rate.

   This is a weaker claim than malice-net-profit-negative — even if malice has
   a lower win rate, a higher fee-per-win could still make malice profitable.
   Both claims together form a coherent deterrence case."
  [result]
  (let [stats    (:aggregated-stats result)
        m-wr     (:malice-avg-win-rate stats)
        h-wr     (:honest-avg-win-rate stats)]
    (cond
      (nil? m-wr)
      (inconclusive :slashing-deters "win-rate data missing from aggregated-stats")

      (< m-wr h-wr)
      (pass :slashing-deters
            {:malice-avg-win-rate m-wr :honest-avg-win-rate h-wr}
            (format "malice win-rate=%.1f%% < honest=%.1f%%: slashing reduces win frequency"
                    (* 100 m-wr) (* 100 h-wr)))

      :else
      (fail :slashing-deters
            {:malice-avg-win-rate m-wr :honest-avg-win-rate h-wr}
            (format "malice win-rate=%.1f%% ≥ honest=%.1f%%: slashing insufficient deterrent"
                    (* 100 m-wr) (* 100 h-wr))))))

(defn evaluate-participation-stable
  "Claim: the productive resolver pool (honest + lazy) is stable.

   Delegates to resolver-sim.tools.participation-stability/check-participation-stability
   which implements the three-layer architecture:

   Layer 1 — Passthrough: always emit aggregate and per-strategy diagnostics.
   Layer 2 — Classified: honest ≤ 10% and productive ≤ 20% exit rates.
   Layer 3 — Fallback: aggregate exit rate < 40% (strict).

   Evidence retains backward-compatible flat keys (:total-exits,
   :initial-count, :aggregate-exit-rate, :productive-exit-rate, etc.)
   alongside richer structured data."
  [result]
  (let [check (ps/check-participation-stability result)]
    {:claim-id  :participation-stable
     :status    (:status check)
     :basis     :single-simulation-evidence
     :evidence  (:evidence check)
     :detail    (:reason check)}))

(defn evaluate-honest-survival-rate
  "Claim: honest resolvers survive at a higher rate than malicious resolvers.

   The slashing mechanism should preferentially drive out malicious resolvers
   while retaining honest ones. Final counts should reflect this.

   Metric: (honest-final-count / initial-honest-count) > (malice-final-count / initial-malice-count)
   where initial counts are approximated from the initial strategy-mix and n-resolvers."
  [result]
  (let [stats      (:aggregated-stats result)
        h-final    (:honest-final-count stats)
        m-final    (:malice-final-count stats)
        init-comp  (:initial-composition result)
        h-init     (:honest-count init-comp)
        m-init     (:malice-count init-comp)]
    (cond
      (nil? h-final)
      (inconclusive :honest-survival-rate "final resolver counts missing from aggregated-stats")

      (or (nil? h-init) (nil? m-init))
      (inconclusive :honest-survival-rate "initial-composition missing honest/malice counts")

      (or (zero? h-init) (zero? m-init))
      (inconclusive :honest-survival-rate "initial-composition has zero honest or malice cohort")

      :else
      (let [h-survival (/ (double h-final) (double h-init))
            m-survival (/ (double m-final) (double m-init))
            margin (- h-survival m-survival)
            healthy? (> margin 0.0)]
        (if healthy?
          (pass :honest-survival-rate
                {:honest-final h-final :malice-final m-final
                 :honest-initial h-init :malice-initial m-init
                 :honest-survival-rate h-survival :malice-survival-rate m-survival
                 :survival-margin margin}
                (format "honest survival=%.1f%% > malice survival=%.1f%%"
                        (* 100 h-survival) (* 100 m-survival)))
          (fail :honest-survival-rate
                {:honest-final h-final :malice-final m-final
                 :honest-initial h-init :malice-initial m-init
                 :honest-survival-rate h-survival :malice-survival-rate m-survival
                 :survival-margin margin}
                (format "honest survival=%.1f%% ≤ malice survival=%.1f%%"
                        (* 100 h-survival) (* 100 m-survival))))))))

(defn evaluate-strategy-adaptation-compatibility
  "Claim: adaptation targets are compatible with scenario strategy space.

   If any epoch reports :resolver.strategy/blocked with
   :target-outside-strategy-space, load-adaptation evidence is marked
   inconclusive at scenario level."
  [result]
  (let [epoch-results (:epoch-results result)
        policy (or (some-> epoch-results first (get-in [:defection :adaptation/resolved-config :blocked-target-policy]))
                   :inconclusive)
        blocked (->> epoch-results
                     (mapcat (fn [ep] (get-in ep [:defection :diagnostics] [])))
                     (filter #(= :target-outside-strategy-space (:reason %)))
                     vec)]
    (if (seq blocked)
      (case policy
        :fail
        (fail :strategy-adaptation-compatibility
              {:blocked-events (count blocked)
               :blocked-target-policy policy}
              (format "load-optimal target outside strategy space in %d event(s); policy=%s"
                      (count blocked) (name policy)))
        :warn
        (pass :strategy-adaptation-compatibility
              {:blocked-events (count blocked)
               :blocked-target-policy policy}
              (format "load-optimal target outside strategy space in %d event(s); policy=%s"
                      (count blocked) (name policy)))
        {:claim-id :strategy-adaptation-compatibility
         :status   :inconclusive
         :basis    :single-simulation-evidence
         :reason   (format "load-optimal target outside strategy space in %d event(s); policy=%s"
                           (count blocked) (name policy))
         :evidence {:blocked-events (count blocked)
                    :blocked-target-policy policy}})
      (pass :strategy-adaptation-compatibility
            {:blocked-events 0
             :blocked-target-policy policy}
            "no strategy-space mismatch detected for adaptation targets"))))

;; ---------------------------------------------------------------------------
;; Registry
;; ---------------------------------------------------------------------------

(def ^:private evaluators
  [evaluate-strategy-adaptation-compatibility
   evaluate-malice-net-profit-negative
   evaluate-honest-dominates
   evaluate-slashing-deters
   evaluate-participation-stable
   evaluate-honest-survival-rate])

;; ---------------------------------------------------------------------------
;; Mechanism-property proxy evaluators
;;
;; These mirror the mechanism-property vocabulary from scenario/equilibrium.clj
;; but operate on multi-epoch aggregate statistics rather than single-trace
;; terminal projections. The :basis is :multi-epoch-population-proxy throughout.
;;
;; Properties evaluated:
;;   :budget-balance          — net value flow sums near zero (no leakage)
;;   :incentive-compatibility — honest strategy yields better outcomes than malicious
;;   :individual-rationality  — honest resolvers earn positive cumulative profit
;;   :collusion-resistance    — malicious share does not grow relative to initial
;; ---------------------------------------------------------------------------

(defn- mech-pass [property evidence detail]
  {:property property
   :status   :pass
   :basis    :multi-epoch-population-proxy
   :evidence evidence
   :detail   detail})

(defn- mech-fail [property evidence detail]
  {:property property
   :status   :fail
   :basis    :multi-epoch-population-proxy
   :evidence evidence
   :detail   detail})

(defn- mech-inconclusive [property reason & [evidence]]
  {:property property
   :status   :inconclusive
   :basis    :multi-epoch-population-proxy
   :reason   reason
   :evidence (or evidence {})})

(defn- add-surplus-diagnostics
  "Attach diagnostic-only surplus metrics (net-sum, honest-profit, profit-ratio)
   to a budget-balance evidence map. These are informative but not used as the
   authoritative pass/fail criterion."
  [evidence h-prof m-prof]
  (let [resolver-net (when (and h-prof m-prof) (+ h-prof m-prof))
        ratio (when (and h-prof (not (zero? (double (or resolver-net 0)))))
                (/ (double h-prof) (double resolver-net)))]
    (assoc evidence
           :honest-cumulative-profit  h-prof
           :malice-cumulative-profit  m-prof
           :resolver-profit-net-sum   resolver-net
           :profit-ratio              ratio)))

(defn- evaluate-mech-budget-balance
  "Proxy for :budget-balance — flow-conservation reconciliation.

   Verifies that the sum of payer debits (fees collected) equals resolver
   payouts plus protocol revenue. The reconciliation equation:

     fees_collected = resolver_net_profit + (bond_loss - fraud_upside)

   Rearranged:
     residual = fees_collected - resolver_net - bond_loss + fraud_upside

   The residual should be approximately 0 (within 1 wei rounding tolerance).
   A negative residual means value was unaccountably created — a simulation bug.

   Surplus metrics (honest-cumulative-profit, resolver-profit-net-sum,
   profit-ratio) are always included as diagnostics but are not the
   authoritative criterion — the flow-conservation residual is.

   When complete reconciliation inputs are unavailable, returns :inconclusive
   (never :fail or :pass) with whatever surplus metrics are available."
  [result]
  (let [stats     (:aggregated-stats result)
        h-prof    (:honest-cumulative-profit stats)
        m-prof    (:malice-cumulative-profit stats)
        fees-col  (:flow-total-fees-collected stats)
        bond-loss (:flow-total-bond-loss stats)
        fraud-up  (:flow-total-fraud-upside stats)
        flow-keys {:flow-total-fees-collected fees-col
                   :flow-total-bond-loss     bond-loss
                   :flow-total-fraud-upside  fraud-up}]
    (if (ps/complete-finite-numbers? stats [:honest-cumulative-profit
                                            :malice-cumulative-profit
                                            :flow-total-fees-collected
                                            :flow-total-bond-loss
                                            :flow-total-fraud-upside])
      (let [resolver-net (+ h-prof m-prof)
            residual (+ (- fees-col resolver-net bond-loss) fraud-up)
            balanced? (<= (Math/abs (double residual)) 1.0)
            base-evidence {:total-fees-collected  fees-col
                           :resolver-profit-net-sum resolver-net
                           :total-bond-loss      bond-loss
                           :total-fraud-upside   fraud-up
                           :residual             residual}]
        (if balanced?
          (mech-pass :budget-balance
                     (add-surplus-diagnostics base-evidence h-prof m-prof)
                     (format "flow conserved: fees=%.0f, resolver-net=%.0f, bond=%.0f, fraud=%.0f, residual=%.0f"
                             (double fees-col) (double resolver-net) (double bond-loss) (double fraud-up) (double residual)))
          (mech-fail :budget-balance
                     (add-surplus-diagnostics base-evidence h-prof m-prof)
                     (format "flow leak: fees=%.0f, resolver-net=%.0f, bond=%.0f, fraud=%.0f, residual=%.0f ≠ 0"
                             (double fees-col) (double resolver-net) (double bond-loss) (double fraud-up) (double residual)))))
      ;; Incomplete reconciliation inputs → inconclusive with surplus diagnostics
      (mech-inconclusive :budget-balance
                         "flow-conservation reconciliation inputs incomplete; surplus metrics attached as diagnostics"
                         (add-surplus-diagnostics
                          {:missing-flow-keys (vec (for [[k v] flow-keys :when (nil? v)] k))}
                          h-prof m-prof)))))

(defn- evaluate-mech-incentive-compatibility
  "Proxy for :incentive-compatibility.

   Incentive compatibility requires that honest play is at least as good as
   deviation. Population proxy: honest resolvers earn higher cumulative profit
   AND higher win rate than malicious resolvers over the full simulation."
  [result]
  (let [stats  (:aggregated-stats result)
        h-prof (:honest-cumulative-profit stats)
        m-prof (:malice-cumulative-profit stats)
        h-wr   (:honest-avg-win-rate stats)
        m-wr   (:malice-avg-win-rate stats)]
    (if (some nil? [h-prof m-prof h-wr m-wr])
      (mech-inconclusive :incentive-compatibility "profit or win-rate data missing")
      (let [profit-ok? (>= h-prof m-prof)
            winrate-ok? (>= h-wr m-wr)]
        (cond
          (and profit-ok? winrate-ok?)
          (mech-pass :incentive-compatibility
                     {:honest-profit h-prof :malice-profit m-prof :honest-wr h-wr :malice-wr m-wr}
                     (format "honest profit=%.0f≥malice=%.0f and win-rate=%.1f%%≥%.1f%%"
                             h-prof m-prof (* 100 h-wr) (* 100 m-wr)))

          profit-ok?
          (mech-fail :incentive-compatibility
                     {:honest-profit h-prof :malice-profit m-prof :honest-wr h-wr :malice-wr m-wr}
                     (format "honest win-rate=%.1f%% < malice=%.1f%% — deviation rewarded in win rate"
                             (* 100 h-wr) (* 100 m-wr)))

          :else
          (mech-fail :incentive-compatibility
                     {:honest-profit h-prof :malice-profit m-prof :honest-wr h-wr :malice-wr m-wr}
                     (format "honest profit=%.0f < malice=%.0f — deviation is profitable"
                             h-prof m-prof)))))))

(defn- evaluate-mech-individual-rationality
  "Proxy for :individual-rationality.

   Individual rationality requires that no required honest participant ends up
   with a negative payoff. Population proxy: honest-cumulative-profit > 0."
  [result]
  (let [stats  (:aggregated-stats result)
        h-prof (:honest-cumulative-profit stats)]
    (if (nil? h-prof)
      (mech-inconclusive :individual-rationality "honest-cumulative-profit missing")
      (if (pos? h-prof)
        (mech-pass :individual-rationality
                   {:honest-cumulative-profit h-prof}
                   (format "honest cumulative profit=%.0f > 0: participation individually rational" h-prof))
        (mech-fail :individual-rationality
                   {:honest-cumulative-profit h-prof}
                   (format "honest cumulative profit=%.0f ≤ 0: honest participation not individually rational" h-prof))))))

(defn- evaluate-mech-collusion-resistance
  "Proxy for :collusion-resistance.

   Collusion resistance requires that a coalition of malicious resolvers cannot
   profitably deviate. Population proxy: malicious resolver share does not grow
   — i.e., malice-final-count / initial-malice-approx ≤ 1.0 (no net growth).

   Uses aggregated stats and assumes ~35% initial malice share (the default
   strategy mix: 25% malicious + 10% collusive)."
  [result]
  (let [stats        (:aggregated-stats result)
        m-final      (:malice-final-count stats)
        init-comp    (:initial-composition result)
        m-initial    (:malice-count init-comp)]
    (if (some nil? [m-final m-initial])
      (mech-inconclusive :collusion-resistance "malice final count or initial-composition.malice-count missing")
      (let [growth-ratio (/ (double m-final) (max 1 (double m-initial)))
            grew?        (> growth-ratio 1.10)]  ; >10% growth = coalition expanded
        (if grew?
          (mech-fail :collusion-resistance
                     {:malice-final m-final :initial-malice-count m-initial :growth-ratio growth-ratio}
                     (format "malice pool grew ×%.2f from explicit initial cohort: collusion may be attracting new actors"
                             growth-ratio))
          (mech-pass :collusion-resistance
                     {:malice-final m-final :initial-malice-count m-initial :growth-ratio growth-ratio}
                     (format "malice pool ×%.2f of initial (≤1.1): no coalition growth detected"
                             growth-ratio)))))))

;; ---------------------------------------------------------------------------
;; Grim-trigger stability condition
;; ---------------------------------------------------------------------------

(defn evaluate-grim-trigger-stability
  "Evaluate whether the grim-trigger strategy is stable under the current
   economic parameters.

   Grim-trigger stability condition:
     discount-factor >= deviation-gain / (deviation-gain + punishment-loss)

   Where:
     deviation-gain = U_malicious - U_honest  (one-time gain from defecting)
     punishment-loss = U_honest - U_honest-under-punishment
                      (per-period loss during permanent punishment)

   `multi-epoch-result` — result from run-multi-epoch with aggregated stats
   `discount-factor` — per-period discount factor (default 0.95)

   Returns {:status :pass | :fail | :inconclusive
            :basis :single-simulation-evidence
            :discount-factor double
            :deviation-gain double
            :punishment-loss double
            :threshold double
            :stable? bool
            :detail string}"
  [multi-epoch-result & {:keys [discount-factor]
                         :or {discount-factor 0.95}}]
  (let [agg (:aggregated-stats multi-epoch-result {})
        honest-profit (double (get agg :honest-mean-profit 0))
        malice-profit (double (get agg :malice-mean-profit 0))
        ;; Deviation gain: one-time benefit of switching to malice
        deviation-gain (max 0.0 (- malice-profit honest-profit))
        ;; Punishment loss: per-period cost of being in punishment phase
        ;; (modeled as earning 0 during punishment — worst case)
        punishment-loss (max 0.0 honest-profit)
        threshold (if (pos? (+ deviation-gain punishment-loss))
                    (/ deviation-gain (+ deviation-gain punishment-loss))
                    0.0)
        stable? (>= discount-factor threshold)]
    (if (and (zero? honest-profit) (zero? malice-profit))
      {:status :inconclusive
       :basis :single-simulation-evidence
       :discount-factor discount-factor
       :deviation-gain 0.0
       :punishment-loss 0.0
       :threshold 0.0
       :stable? false
       :detail "insufficient profit data to evaluate grim-trigger stability"}
      {:status (if stable? :pass :fail)
       :basis :single-simulation-evidence
       :discount-factor discount-factor
       :deviation-gain deviation-gain
       :punishment-loss punishment-loss
       :threshold threshold
       :stable? stable?
       :detail (format (str "grim-trigger %s: discount=%.3f, "
                            "threshold=%.3f, deviation-gain=%.1f, "
                            "punishment-loss=%.1f")
                       (if stable? "stable" "unstable")
                       discount-factor threshold
                       deviation-gain punishment-loss)})))

;; ---------------------------------------------------------------------------
;; Repeated-game deterrence threshold
;; ---------------------------------------------------------------------------

(defn evaluate-repeated-game-deterrence-threshold
  "Evaluate this model's repeated-game deterrence threshold.

   This is not a general Folk-theorem result. It applies the model-specific
   normalization

     discount-factor >= (U_malicious - U_honest) / U_honest

   under two explicit assumptions: `U_honest` is the per-period cooperative
   baseline, and the permanent-punishment payoff is normalized to zero. Both
   utilities must be finite and `U_honest` must be strictly positive. The
   discount factor is a per-period discount and must be in [0, 1]. Equality
   satisfies the threshold. A threshold above 1 is explicitly infeasible,
   because no valid discount factor can meet it.

   `multi-epoch-result` — result from run-multi-epoch
   `discount-factor` — per-period discount factor (default 0.95)

   Returns {:status :pass | :fail | :inconclusive
            :basis :single-simulation-evidence
            :deterrence? bool
            :distance-to-boundary double
            :binding-constraint kw | nil
            :detail string}"
  [multi-epoch-result & {:keys [discount-factor]
                         :or {discount-factor 0.95}}]
  (let [stats (:aggregated-stats multi-epoch-result {})
        honest-profit (:honest-mean-profit stats)
        malice-profit (:malice-mean-profit stats)
        finite-number? #(and (number? %) (Double/isFinite (double %)))]
    (cond
      (not (finite-number? discount-factor))
      {:status :inconclusive
       :basis :single-simulation-evidence
       :deterrence? false
       :cooperation-region? false
       :binding-constraint :discount-factor
       :reason :invalid-discount-factor
       :detail "discount-factor must be a finite number in [0, 1]"}

      (not (<= 0.0 (double discount-factor) 1.0))
      {:status :inconclusive
       :basis :single-simulation-evidence
       :discount-factor (double discount-factor)
       :deterrence? false
       :cooperation-region? false
       :binding-constraint :discount-factor
       :reason :discount-factor-out-of-range
       :detail "discount-factor must be in [0, 1]"}

      (not (finite-number? honest-profit))
      {:status :inconclusive
       :basis :single-simulation-evidence
       :deterrence? false
       :cooperation-region? false
       :binding-constraint :honest-mean-profit
       :reason :missing-or-invalid-honest-utility
       :detail "honest-mean-profit must be a finite positive utility"}

      (not (finite-number? malice-profit))
      {:status :inconclusive
       :basis :single-simulation-evidence
       :deterrence? false
       :cooperation-region? false
       :binding-constraint :malice-mean-profit
       :reason :missing-or-invalid-malice-utility
       :detail "malice-mean-profit must be a finite utility"}

      (not (pos? (double honest-profit)))
      {:status :inconclusive
       :basis :single-simulation-evidence
       :deterrence? false
       :cooperation-region? false
       :binding-constraint :honest-mean-profit
       :reason :non-positive-honest-utility
       :detail "honest-mean-profit must be positive for the normalized cooperative baseline"}

      :else
      (let [df (double discount-factor)
            threshold (/ (- (double malice-profit) (double honest-profit))
                         (double honest-profit))
            distance (- df threshold)
            deterrence? (>= distance 0.0)]
        (if (> threshold 1.0)
          {:status :fail
           :basis :single-simulation-evidence
           :discount-factor df
           :honest-mean-profit (double honest-profit)
           :malice-mean-profit (double malice-profit)
           :threshold threshold
           :deterrence? false
           :cooperation-region? false
           :distance-to-boundary distance
           :binding-constraint :threshold
           :reason :infeasible-threshold
           :detail (format (str "repeated-game deterrence threshold is infeasible "
                                "(discount=%.3f, threshold=%.3f > 1)")
                           df threshold)}
          {:status (if deterrence? :pass :fail)
           :basis :single-simulation-evidence
           :discount-factor df
           :honest-mean-profit (double honest-profit)
           :malice-mean-profit (double malice-profit)
           :threshold threshold
           :deterrence? deterrence?
           ;; Retained while callers migrate from the former folk-theorem name.
           :cooperation-region? deterrence?
           :distance-to-boundary distance
           :binding-constraint (when (not deterrence?) :discount-factor)
           :detail (format (str "repeated-game deterrence threshold: %s "
                                "(discount=%.3f, threshold=%.3f, distance=%.4f)")
                           (if deterrence? "met" "not met")
                           df threshold distance)})))))

(defn evaluate-folk-theorem-region
  "Deprecated compatibility alias for `evaluate-repeated-game-deterrence-threshold`.
   The calculation is a model-specific repeated-game deterrence threshold, not a
   general Folk-theorem condition."
  [multi-epoch-result & {:as options}]
  (apply evaluate-repeated-game-deterrence-threshold multi-epoch-result
         (mapcat identity options)))

(def ^:private mechanism-proxy-evaluators
  [evaluate-mech-budget-balance
   evaluate-mech-incentive-compatibility
   evaluate-mech-individual-rationality
   evaluate-mech-collusion-resistance])

(defn evaluate-mechanism-proxies
  "Evaluate mechanism-property proxies against a multi-epoch result map.

   These are population-level analogues of the mechanism properties checked by
   scenario/equilibrium.clj on single traces. The :basis is
   :multi-epoch-population-proxy throughout — weaker than :single-trace-terminal-proxy
   but provides convergent multi-epoch evidence for the same claims.

   Properties checked: :budget-balance, :incentive-compatibility,
                       :individual-rationality, :collusion-resistance.

   Returns:
     {:mechanism-proxy-results  {property-kw → result-map}
      :mechanism-proxy-status   :pass | :fail | :inconclusive}"
  [multi-epoch-result]
  (let [results  (into {} (map (fn [f]
                                 (let [r (f multi-epoch-result)]
                                   [(:property r) r]))
                               mechanism-proxy-evaluators))
        statuses (map :status (vals results))
        overall  (cond
                   (some #(= :fail %) statuses)         :fail
                   (every? #(= :pass %) statuses)       :pass
                   :else                                 :inconclusive)]
    {:mechanism-proxy-results results
     :mechanism-proxy-status  overall}))

;; ---------------------------------------------------------------------------
;; Registry
;; ---------------------------------------------------------------------------

(def ^:private evaluators
  [evaluate-strategy-adaptation-compatibility
   evaluate-malice-net-profit-negative
   evaluate-honest-dominates
   evaluate-slashing-deters
   evaluate-participation-stable
   evaluate-honest-survival-rate])

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn evaluate-stochastic-equilibrium
  "Evaluate all stochastic equilibrium claims, mechanism-property proxies,
   grim-trigger stability, and the model-specific repeated-game deterrence
   threshold against a multi-epoch result map.

   The result map is the return value of resolver-sim.sim.multi-epoch/run-multi-epoch.

   Returns:
     {:claim-results           [{:claim-id :status :basis :evidence :detail} ...]
      :mechanism-proxy-results {property-kw → result-map}
      :mechanism-proxy-status  :pass | :fail | :inconclusive
      :grim-trigger                  result-map
      :repeated-game-deterrence       result-map
      :folk-theorem                   result-map (deprecated compatibility alias)
      :overall-status          :pass | :fail | :inconclusive
      :pass-count              int
      :fail-count              int
      :inconclusive-count      int
      :coverage                double-or-nil
      :summary                 string}"
  [multi-epoch-result]
  (let [claim-results    (mapv #(% multi-epoch-result) evaluators)
        mech-proxies     (evaluate-mechanism-proxies multi-epoch-result)
        grim-trigger     (evaluate-grim-trigger-stability multi-epoch-result)
        repeated-game-deterrence (evaluate-repeated-game-deterrence-threshold multi-epoch-result)
        pass-count       (count (filter #(= :pass (:status %)) claim-results))
        fail-count       (count (filter #(= :fail (:status %)) claim-results))
        inc-count        (count (filter #(= :inconclusive (:status %)) claim-results))
        overall          (cond
                           (pos? fail-count)    :fail
                           (pos? inc-count)     :inconclusive
                           :else                :pass)
        summary          (format "%d/%d claims pass (%d fail, %d inconclusive)"
                                 pass-count (count claim-results) fail-count inc-count)]
    (merge
     {:claim-results       claim-results
      :grim-trigger               grim-trigger
      :repeated-game-deterrence    repeated-game-deterrence
      ;; Retained while callers migrate from the former folk-theorem key.
      :folk-theorem                repeated-game-deterrence
      :overall-status      overall
      :pass-count          pass-count
      :fail-count          fail-count
      :inconclusive-count  inc-count
      :summary             summary
      :coverage            (/ (+ pass-count fail-count) (double (count claim-results)))}
     mech-proxies)))

(defn print-equilibrium-report
  "Print a human-readable summary of a stochastic equilibrium report.
   Takes the return value of evaluate-stochastic-equilibrium."
  [report]
  (println "\n── Stochastic Equilibrium Claims ─────────────────────────────────────────")
  (doseq [r (:claim-results report)]
    (let [icon (case (:status r) :pass "✅" :fail "❌" "⚠️")]
      (println (format "  %s %-40s %s" icon (name (:claim-id r)) (:detail r "")))))
  (println (format "\n  Overall: %s  (%s)" (name (:overall-status report)) (:summary report)))
  (when-let [proxies (:mechanism-proxy-results report)]
    (println "\n── Mechanism-Property Proxies (multi-epoch population) ────────────────────")
    (doseq [[_prop r] (sort-by key proxies)]
      (let [icon (case (:status r) :pass "✅" :fail "❌" "⚠️")]
        (println (format "  %s %-40s %s" icon (name (:property r)) (:detail r (:reason r ""))))))
    (println (format "\n  Mechanism proxies: %s" (name (:mechanism-proxy-status report)))))
  (println "───────────────────────────────────────────────────────────────────────────"))
