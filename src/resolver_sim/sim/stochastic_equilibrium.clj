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

    sim/* may import sim/* and the leaf hash layer per project rules (see
     sim/audit.clj). All inputs are plain Clojure maps (no DB, no I/O)."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.tools.participation-stability :as ps]))

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

(def ^:private default-discount-factor 0.95)

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
                         :or {discount-factor default-discount-factor}}]
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

;; The model-specific grim-trigger deterrence claim. This is deliberately NOT a
;; general Folk-theorem result: it verifies one-shot-deviation deterrence for a
;; single repeated-game strategy profile under an explicit assumption set, not
;; the enforceability of an arbitrary payoff vector.
;;
;;   Cooperation forever:   R + δR + δ²R + ...  = R / (1 - δ)
;;   One-shot deviation:    T + δP + δ²P + ...  = T + δP / (1 - δ)
;;   Deterrence condition:  R / (1 - δ)  >=  T + δP / (1 - δ)
;;   Normalized form:       δ (T - P)  >=  T - R
;;   Threshold:             δ* = (T - R) / (T - P)
;;
;; where R is the cooperative per-period payoff, T is the one-shot deviation
;; payoff, and P is the per-period punishment continuation payoff.

(def ^:private repeated-game-assumptions
  "Assumptions required for the model-specific grim-trigger deterrence claim.
   A result is only meaningful under these; they are reported verbatim so
   reviewers can confirm the algebra does not carry more semantics than the
   evidence supports."
  [{:assumption :infinite-horizon
    :statement "Players interact over an infinite horizon with per-period discount factor δ."}
   {:assumption :common-discount
    :statement "A common per-period discount factor δ applies to all players."}
   {:assumption :stationary-payoffs
    :statement "Per-period payoffs R, T, P are stationary across periods."}
   {:assumption :perfect-monitoring
    :statement "Deviations are detected under perfect or sufficiently informative monitoring."}
   {:assumption :punishment-transition
    :statement "A deviation triggers a credible transition to the punishment path."}
   {:assumption :punishment-continuation
    :statement "P is the per-period punishment-phase continuation payoff."}
   {:assumption :one-shot-deviation
    :statement "The one-shot deviation principle applies to the repeated game."}
   {:assumption :payoff-ordering
    :statement "The deterrence interpretation assumes the prisoner's-dilemma ordering T > R > P; S plays no role in the one-shot-deviation model."}
   {:assumption :punishment-phase-stability
    :statement "No player has a profitable deviation during the punishment phase (assumed, not proven)."}
   {:assumption :deviation-coverage
    :statement "Coverage is limited to the stated deviation domain (single actor, single epoch, from the cooperative path, no coalitions)."}])

(defn- finite-number?
  [x]
  (and (number? x) (Double/isFinite (double x))))

(def ^:private grim-trigger-theorem-domain-tag
  "Domain-separation tag for the grim-trigger theorem-input hash commitment."
  "REPEATED_GAME_GRIM_TRIGGER_DETERRENCE_V1")

(def ^:private perfect-public-monitoring
  {:type :perfect-public
   :deviation-detected? true
   :detection-delay 0})

(def ^:private default-deviation-domain
  {:actors :single
   :duration-epochs 1
   :timing :cooperative-path
   :actions #{:specified-deviation}
   :coalitions? false})

(def ^:private required-scenario-evidence-keys
  "Keys that must be present in :scenario-evidence for the scenario-backed and
   trace-observed attestation tiers, structurally enforcing multi-epoch scope:
   the epoch sequence, a cooperative history, the deviation event, and the
   punishment activation/persistence with a branch-payoff projection."
  [:epoch-sequence :cooperative-history :deviation-event
   :punishment-activation :punishment-persistence
   :branch-payoff-projection])

(def ^:private grim-trigger-theorem-type
  :repeated-game/grim-trigger-deterrence)

(def ^:private grim-trigger-threshold-formula-id
  :repeated-game/grim-trigger-deterrence.threshold.v1)

(def ^:private grim-trigger-theorem-claim
  "One-shot deviation from cooperation is not profitable: R/(1-δ) >= T + δP/(1-δ), equivalently δ(T-P) >= T-R.")

(def ^:private supported-evidence-tiers
  #{:parameter-level-theorem :scenario-backed :trace-observed-attestation})

(def ^:private default-punishment-payoff 0.0)
(def ^:private default-horizon :infinite)
(def ^:private default-strategy-profile :grim-trigger)
(def ^:private default-deviation-model :single-period-unilateral)
(def ^:private default-payoff-model :stationary)
(def ^:private default-evidence-tier :parameter-level-theorem)
(def ^:private default-assume-punishment-credible? true)

(defn- perfect-public-monitoring?
  [m]
  (and (map? m)
       (= :perfect-public (:type m))
       (true? (:deviation-detected? m))
       (= 0 (:detection-delay m))))

(defn- normalize-monitoring-model
  [m]
  (if (= :perfect-public m) perfect-public-monitoring m))

(defn- validate-theorem-context
  "Fail-closed validation of the theorem context. Horizon, monitoring, and
   evidence tier are enforced before any payoff algebra runs, so an unsupported
   model can never produce a misleading :pass."
  [ctx]
  (let [horizon (:repeated-game/horizon ctx)
        monitoring-model (:monitoring/model ctx)
        evidence-tier (:evidence-tier ctx)
        scenario-evidence (:scenario-evidence ctx)
        missing-evidence (when (map? scenario-evidence)
                           (vec (remove #(contains? scenario-evidence %)
                                        required-scenario-evidence-keys)))]
    (cond
      (not (or (= :infinite horizon)
               (and (map? horizon) (= :finite (:type horizon)))))
      {:ok? false
       :status :invalid-input
       :claim/conclusion :assumptions-unsatisfied
       :reason :unsupported-horizon-model
       :detail (format "horizon must be :infinite or {:type :finite ...}; got %s"
                       (pr-str horizon))}

      (and (map? horizon) (= :finite (:type horizon)))
      {:ok? false
       :status :invalid-input
       :claim/conclusion :assumptions-unsatisfied
       :reason :finite-horizon-unsupported
       :detail "finite-horizon backward induction is not implemented; the infinite-horizon geometric-series threshold δ* = (T-R)/(T-P) does not apply"}

      (not (perfect-public-monitoring? monitoring-model))
      {:ok? false
       :status :invalid-input
       :claim/conclusion :assumptions-unsatisfied
       :reason :unsupported-monitoring-model
       :detail "imperfect or delayed monitoring is unsupported; require {:type :perfect-public :deviation-detected? true :detection-delay 0}"}

      (not (contains? supported-evidence-tiers evidence-tier))
      {:ok? false
       :status :inconclusive
       :claim/conclusion :inconclusive
       :reason :unsupported-evidence-tier
       :detail (format "evidence-tier must be :parameter-level-theorem | :scenario-backed | :trace-observed-attestation; got %s"
                       (pr-str evidence-tier))}

      (and (not= :parameter-level-theorem evidence-tier)
           (not (map? scenario-evidence)))
      {:ok? false
       :status :inconclusive
       :claim/conclusion :inconclusive
       :reason :evidence-tier-unmet
       :detail (format "evidence-tier %s requires a scenario-evidence map; got %s"
                       (name evidence-tier) (pr-str scenario-evidence))}

      (and (not= :parameter-level-theorem evidence-tier)
           (seq missing-evidence))
      {:ok? false
       :status :inconclusive
       :claim/conclusion :inconclusive
       :reason :scenario-evidence-incomplete
       :detail (format "evidence-tier %s requires scenario-evidence keys %s; missing %s"
                       (name evidence-tier)
                       (pr-str required-scenario-evidence-keys)
                       (pr-str missing-evidence))}

      :else {:ok? true})))

(defn- committed-theorem-inputs
  "The full set of theorem inputs committed to by the certificate. Every
   evaluation — including interval and context-failure results — records the
   exact stage-game payoffs, discount factor, horizon, strategy profile,
   deviation model, monitoring model, and payoff model that were (or would be)
   used, so the claim is recomputable and mutation-detectable."
  [t r p delta ctx]
  {:stage-game/payoffs {:cooperate r
                        :unilateral-deviation t
                        :punishment p}
   :repeated-game/discount-factor (if (finite-number? delta) (double delta) delta)
   :repeated-game/horizon (:repeated-game/horizon ctx)
   :strategy/profile (:strategy/profile ctx)
   :deviation/model (:deviation/model ctx)
   :monitoring/model (:monitoring/model ctx)
   :payoff/model (:payoff/model ctx)})

(defn- project-for-theorem-hash
  "Project a theorem-input value into a canonical-bytes-encodable form.
   The canonical encoder rejects Double/Float (to avoid int/float aliasing),
   so they are tagged with their exact IEEE-754 hex, mirroring
   project-for-content-hash. Sets are sorted so element order cannot affect
   the commitment."
  [v]
  (cond
    (instance? Double v)
    {:type :float64 :hex (Double/toHexString v)}
    (instance? Float v)
    {:type :float32 :hex (Float/toHexString v)}
    (instance? clojure.lang.IPersistentMap v)
    (into {} (map (fn [[k val]] [(project-for-theorem-hash k)
                                 (project-for-theorem-hash val)]))
          v)
    (instance? clojure.lang.IPersistentVector v)
    (mapv project-for-theorem-hash v)
    (instance? clojure.lang.ISeq v)
    (mapv project-for-theorem-hash (vec v))
    (instance? clojure.lang.IPersistentSet v)
    (sort-by pr-str (map project-for-theorem-hash v))
    :else v))

(defn- base-theorem-certificate
  "Common scaffolding carried on every outcome of the deterrence evaluator.
   Includes a domain-separated hash commitment over the full theorem inputs and
   the deviation domain so the exact claim being made is recomputable."
  [t r p delta ctx]
  (let [inputs (committed-theorem-inputs t r p delta ctx)
        domain (:deviation-domain ctx)
        root-hash (hc/domain-hash
                   grim-trigger-theorem-domain-tag
                   (project-for-theorem-hash
                    {:formula-id grim-trigger-threshold-formula-id
                     :inputs inputs
                     :deviation-domain domain}))]
    {:theorem/type grim-trigger-theorem-type
     :theorem/claim grim-trigger-theorem-claim
     :theorem/assumptions repeated-game-assumptions
     :theorem/inputs inputs
     :theorem/root-hash root-hash
     :deviation-domain domain
     :payoffs {:R r :T t :P p}
     :repeated-game/horizon (:repeated-game/horizon ctx)
     :monitoring/model (:monitoring/model ctx)
     :payoff/model (:payoff/model ctx)
     :evidence-tier (:evidence-tier ctx)
     :punishment/credibility (if (boolean (:assume-punishment-credible? ctx))
                               :assumed :unverified)
     :coalition-resistance? false
     :claim/conclusion :inconclusive
     :deterrence? false
     :cooperation-region? false
     :discount-factor (if (finite-number? delta) (double delta) delta)}))

(defn- max-supportable-deviation-payoff
  "Largest one-shot deviation payoff T for which deterrence still holds at the
   given discount factor (holding R, P fixed): the unique T at the threshold
   δ = (T-R)/(T-P). Returns nil when no finite T solves the boundary."
  [df reward punishment]
  (let [den (- df 1.0)]
    (when-not (zero? den)
      (/ (- (* df punishment) reward) den))))

(defn- minimum-punishment-severity
  "Smallest per-period punishment severity R - P (i.e. largest supportable P)
   needed so that deterrence holds at discount factor df with deviation payoff
   `temptation`. Returns nil when no finite severity suffices (δ = 0)."
  [df reward temptation]
  (when (pos? df)
    (/ (* (- 1.0 df) (- temptation reward)) df)))

(defn- evaluated-assumptions
  "Per-outcome checklist of which applicability preconditions were satisfied in
   the scalar branch (all are satisfied by construction there, since the branch
   is only reached after the fail-closed contract gates)."
  [df reward temptation punishment]
  [{:assumption :finite-payoffs :satisfied? true}
   {:assumption :positive-cooperation-payoff
    :condition "R > 0" :satisfied? (pos? reward)}
   {:assumption :discount-domain
    :condition "0 <= δ < 1" :satisfied? (and (<= 0.0 df) (< df 1.0))}
   {:assumption :tempting-deviation
    :condition "T > R" :satisfied? (> temptation reward)}
   {:assumption :punishment-worse-than-cooperation
    :condition "R > P" :satisfied? (> reward punishment)}
   {:assumption :positive-threshold-denominator
    :condition "T - P > 0" :satisfied? (> temptation punishment)}])

(defn- scalar-grim-trigger-deterrence
  "Evaluate the grim-trigger deterrence certificate for scalar payoffs T, R, P
   and discount factor `delta` under a validated theorem context `ctx`.

   Returns a result map whose :status is
   :pass | :fail | :not-applicable | :invalid-input | :inconclusive, together
   with a :claim/conclusion drawn from the deviation-deterrence taxonomy:
   :deviation-deterred | :deviation-profitable | :threshold-inapplicable |
   :assumptions-unsatisfied | :inconclusive. Results never assert generic
   :cooperation-supported; the strongest conclusion is :deviation-deterred
   under the stated assumptions.

   Applicability contract (fail-closed):
   - T > R  else :not-applicable / :threshold-inapplicable
   - R > P  else :inconclusive / :assumptions-unsatisfied
   - T > P  else :invalid-input / :assumptions-unsatisfied
   - 0 <= δ < 1 else :inconclusive/:invalid-input (δ=1 diverges)"
  [t r p delta ctx]
  (let [base (base-theorem-certificate t r p delta ctx)
        credible? (boolean (:assume-punishment-credible? ctx))]
    (cond
      (not (finite-number? delta))
      (assoc base
             :status :inconclusive
             :claim/conclusion :inconclusive
             :basis :single-simulation-evidence
             :reason :missing-or-invalid-discount
             :cooperation-incentive-compatible? false
             :punishment-credible? (boolean credible?)
             :strategy-profile-equilibrium? false
             :binding-constraint :discount-factor
             :detail "discount-factor must be a finite number in [0, 1)")

      (not (<= 0.0 (double delta) 1.0))
      (assoc base
             :status :inconclusive
             :claim/conclusion :inconclusive
             :basis :single-simulation-evidence
             :reason :discount-factor-out-of-range
             :cooperation-incentive-compatible? false
             :punishment-credible? (boolean credible?)
             :strategy-profile-equilibrium? false
             :binding-constraint :discount-factor
             :detail "discount-factor must be in [0, 1)")

      (= 1.0 (double delta))
      (assoc base
             :status :invalid-input
             :claim/conclusion :assumptions-unsatisfied
             :basis :single-simulation-evidence
             :reason :discount-at-horizon-boundary
             :cooperation-incentive-compatible? false
             :punishment-credible? (boolean credible?)
             :strategy-profile-equilibrium? false
             :binding-constraint :discount-factor
             :detail "discount-factor=1 makes the geometric payoff sum diverge; require δ < 1")

      (not (finite-number? t))
      (assoc base
             :status :inconclusive
             :claim/conclusion :inconclusive
             :basis :single-simulation-evidence
             :reason :missing-or-invalid-malice-utility
             :cooperation-incentive-compatible? false
             :punishment-credible? (boolean credible?)
             :strategy-profile-equilibrium? false
             :binding-constraint :malice-mean-profit
             :detail "T (malice-mean-profit) must be a finite utility")

      (not (finite-number? r))
      (assoc base
             :status :inconclusive
             :claim/conclusion :inconclusive
             :basis :single-simulation-evidence
             :reason :missing-or-invalid-honest-utility
             :cooperation-incentive-compatible? false
             :punishment-credible? (boolean credible?)
             :strategy-profile-equilibrium? false
             :binding-constraint :honest-mean-profit
             :detail "R (honest-mean-profit) must be a finite utility")

      (not (pos? (double r)))
      (assoc base
             :status :inconclusive
             :claim/conclusion :assumptions-unsatisfied
             :basis :single-simulation-evidence
             :reason :non-positive-honest-utility
             :cooperation-incentive-compatible? false
             :punishment-credible? (boolean credible?)
             :strategy-profile-equilibrium? false
             :binding-constraint :honest-mean-profit
             :detail "R (honest-mean-profit) must be strictly positive for the cooperative baseline")

      (not (finite-number? p))
      (assoc base
             :status :inconclusive
             :claim/conclusion :inconclusive
             :basis :single-simulation-evidence
             :reason :missing-or-invalid-punishment-payoff
             :cooperation-incentive-compatible? false
             :punishment-credible? (boolean credible?)
             :strategy-profile-equilibrium? false
             :binding-constraint :punishment-payoff
             :detail "P (punishment-payoff) must be a finite utility")

      (<= (double t) (double r))
      (assoc base
             :status :not-applicable
             :claim/conclusion :threshold-inapplicable
             :basis :single-simulation-evidence
             :reason :deviation-not-profitable
             :cooperation-incentive-compatible? false
             :punishment-credible? (boolean credible?)
             :strategy-profile-equilibrium? false
             :detail "unilateral deviation is not profitable (T <= R); the deterrence condition is vacuous")

      (>= (double p) (double r))
      (assoc base
             :status :inconclusive
             :claim/conclusion :assumptions-unsatisfied
             :basis :single-simulation-evidence
             :reason :punishment-not-deterrent
             :cooperation-incentive-compatible? false
             :punishment-credible? (boolean credible?)
             :strategy-profile-equilibrium? false
             :binding-constraint :punishment-payoff
             :detail "punishment is not worse than cooperation (P >= R); it cannot be a deterrent")

      (<= (double t) (double p))
      (assoc base
             :status :invalid-input
             :claim/conclusion :assumptions-unsatisfied
             :basis :single-simulation-evidence
             :reason :non-positive-threshold-denominator
             :cooperation-incentive-compatible? false
             :punishment-credible? (boolean credible?)
             :strategy-profile-equilibrium? false
             :binding-constraint :punishment-payoff
             :detail "threshold denominator T - P must be positive")

      :else
      (let [df (double delta)
            reward (double r)
            temptation (double t)
            punishment (double p)
            threshold (/ (- temptation reward) (- temptation punishment))
            coop-value (/ reward (- 1.0 df))
            dev-value (+ temptation (* df punishment (/ 1.0 (- 1.0 df))))
            margin (- coop-value dev-value)
            discount-margin (- df threshold)
            ;; holds? is based on the discount margin (δ - δ*), which avoids the
            ;; 1/(1-δ) amplification of the present-value difference and so is
            ;; numerically stable at the equality boundary.
            holds? (>= discount-margin 0.0)
            status (cond (not holds?) :fail
                         credible? :pass
                         :else :inconclusive)
            boundary (cond (> discount-margin 0.0) :strict-pass
                           (zero? discount-margin) :boundary-pass
                           :else :fail)
            base-map (merge base
                            {:status status
                             :basis :single-simulation-evidence
                             :discount-factor df
                             :discount-factor/value df
                             :honest-mean-profit reward
                             :malice-mean-profit temptation
                             :punishment-payoff punishment
                             :threshold threshold
                             :threshold/value threshold
                             :threshold/formula-id grim-trigger-threshold-formula-id
                             :distance-to-boundary discount-margin
                             :discount-margin discount-margin
                             :normalized-deterrence-margin (- (* df (- temptation punishment))
                                                              (- temptation reward))
                             :nearest-failing-discount threshold
                             :boundary-classification boundary
                             :deterrence/margin discount-margin
                             :deterrence/slack-classification boundary
                             :cooperation-incentive-compatible? holds?
                             :punishment-credible? (boolean credible?)
                             :strategy-profile-equilibrium? (and holds? credible?)
                             :deterrence? holds?
                             :cooperation-region? holds?
                             :binding-constraint (when (not holds?) :discount-factor)
                             :theorem/inequality-left coop-value
                             :theorem/inequality-right dev-value
                             :theorem/threshold threshold
                             :theorem/margin margin
                             :theorem/holds? holds?
                             :inequality/evaluated {:present-value {:cooperation coop-value
                                                                     :deviation dev-value}
                                                    :normalized {:left (* df (- temptation punishment))
                                                                 :right (- temptation reward)}}
                             :inequality/holds? holds?
                             :assumptions/evaluated
                             (evaluated-assumptions df reward temptation punishment)
                             :sensitivity/required-minimum-discount threshold
                             :sensitivity/maximum-deviation-payoff
                             (max-supportable-deviation-payoff df reward punishment)
                             :sensitivity/minimum-punishment-severity
                             (minimum-punishment-severity df reward temptation)
                             :sensitivity/payoff-uncertainty false})]
        (cond
          (not holds?)
          (assoc base-map
                 :claim/conclusion :deviation-profitable
                 :reason :deterrence-not-established
                 :deviation/payoff dev-value
                 :cooperation/value coop-value
                 :deviation/value dev-value
                 :deviation/gain (- dev-value coop-value)
                 :minimum-discount-required threshold
                 :detail (format (str "one-shot deviation is profitable: "
                                      "cooperation=%.4f < deviation=%.4f (gain=%.4f); need δ >= %.4f")
                                 coop-value dev-value (- dev-value coop-value) threshold))
          (not credible?)
          (assoc base-map
                 :claim/conclusion :inconclusive
                 :reason :punishment-credibility-not-established
                 :detail "cooperation is incentive-compatible but punishment credibility is not established; overall claim not proven")
          :else
          (assoc base-map
                 :claim/conclusion :deviation-deterred
                 :detail (format (str "grim-trigger deterrence holds: δ=%.4f >= δ*=%.4f "
                                      "(discount-margin=%.4f, boundary=%s)")
                                 df threshold (- df threshold) (name boundary))))))))

(defn- cartesian
  [seqs]
  (reduce (fn [acc xs]
            (for [a acc x xs]
              (conj a x)))
          [[]]
          seqs))

(defn- evaluate-interval-deterrence
  "Uncertainty-aware evaluation: each of T, R, P may be a scalar or an
   interval {:min ... :max ...}. Classifies deterrence over all admissible
   payoff corners under a validated theorem context `ctx`."
  [T R P delta ctx]
  (let [corners (cartesian
                 (map (fn [v] (if (map? v) [(:min v) (:max v)] [v v]))
                      [T R P]))
        evals (map (fn [[t r p]]
                     (scalar-grim-trigger-deterrence t r p delta ctx))
                   corners)
        statuses (map :status evals)
        thresholds (keep :theorem/threshold evals)
        best (when (seq thresholds) (reduce min thresholds))
        worst (when (seq thresholds) (reduce max thresholds))
        classification (cond
                         (every? #(= :pass %) statuses) :robustly-deterrent
                         (every? #(= :fail %) statuses) :robustly-not-deterrent
                         :else :possibly-deterrent)
        status (case classification
                 :robustly-deterrent :pass
                 :robustly-not-deterrent :fail
                 :possibly-deterrent :inconclusive)
        claim-conclusion (case classification
                           :robustly-deterrent :deviation-deterred
                           :robustly-not-deterrent :deviation-profitable
                           :possibly-deterrent :inconclusive)
        base (base-theorem-certificate T R P delta ctx)]
    (merge base
           {:status status
            :claim/conclusion claim-conclusion
            :classification classification
            :basis :interval-evidence
            :payoffs {:T T :R R :P P}
            :discount-factor (double delta)
            :discount-factor/value (double delta)
            :threshold/value {:best best :worst worst}
            :threshold/formula-id grim-trigger-threshold-formula-id
            :deterrence/margin (- (double delta) worst)
            :deterrence/slack-classification (case classification
                                               :robustly-deterrent :strict-pass
                                               :robustly-not-deterrent :fail
                                               :possibly-deterrent :indeterminate)
            :best-case-threshold best
            :worst-case-threshold worst
            :corner-count (count corners)
            :corner-statuses statuses
            :cooperation-incentive-compatible? (= :robustly-deterrent classification)
            :punishment-credible? (boolean (:assume-punishment-credible? ctx))
            :strategy-profile-equilibrium? (= :robustly-deterrent classification)
            :sensitivity/payoff-uncertainty true
            :sensitivity/required-minimum-discount worst
            :detail (format "deterrence over admissible payoff intervals: %s (best δ*=%.4f, worst δ*=%.4f)"
                            (name classification) best worst)})))

(defn evaluate-grim-trigger-deterrence
  "Evaluate the model-specific grim-trigger deterrence claim and emit a
   recomputable theorem certificate.

   Inputs (from `multi-epoch-result` or explicit overrides):
   - `R` (:honest-mean-profit) cooperative per-period payoff
   - `T` (:malice-mean-profit) one-shot deviation payoff
   - `P` (`punishment-payoff`, default 0) per-period punishment payoff
   - `delta` (`discount-factor`, default 0.95) per-period discount factor

   Options:
   - `:payoffs`        optional map {:R v :T v :P v} overriding the derived
                       payoffs; any value may be an interval {:min .. :max ..}
                       to enable uncertainty-aware evaluation.
   - `:assume-punishment-credible?` (default true) whether to treat punishment
                       credibility as a trusted assumption rather than requiring
                       punishment-state deviation evidence. The result always
                       records `:punishment/credibility :assumed` in that case
                       (or :unverified otherwise) so the boundary is explicit.
   - `:horizon`        (default :infinite) the repeated-game horizon. Finite
                       horizons ({:type :finite ...}) fail closed because the
                       infinite-horizon geometric-series threshold does not apply;
                       an unknown horizon is also rejected.
   - `:strategy-profile` (default :grim-trigger)
   - `:deviation-model`  (default :single-period-unilateral)
   - `:monitoring-model` (default {:type :perfect-public :deviation-detected? true
                       :detection-delay 0}); imperfect or delayed monitoring
                       fails closed (:assumptions-unsatisfied).
   - `:payoff-model`     (default :stationary)
   - `:deviation-domain` (default {:actors :single :duration-epochs 1
                       :timing :cooperative-path :actions #{:specified-deviation}
                       :coalitions? false}) the exact deviation coverage claimed.
   - `:evidence-tier`    (default :parameter-level-theorem) one of
                       :parameter-level-theorem | :scenario-backed |
                       :trace-observed-attestation. Scenario-backed tiers require
                       a `:scenario-evidence` map with the multi-epoch keys
                       (:epoch-sequence :cooperative-history :deviation-event
                       :punishment-activation :punishment-persistence
                       :branch-payoff-projection).
   - `:scenario-evidence` map backing the evidence tier (see above).

   This is NOT a general Folk-theorem claim. The result carries a full theorem
   certificate (:theorem/type, :theorem/claim, :theorem/assumptions,
   :theorem/inputs, :theorem/root-hash, :theorem/inequality-left,
   :theorem/inequality-right, :theorem/threshold, :theorem/margin,
   :theorem/holds?) plus a :claim/conclusion from the deviation-deterrence
   taxonomy (:deviation-deterred | :deviation-profitable |
   :threshold-inapplicable | :assumptions-unsatisfied | :inconclusive).
   :theorem/root-hash is a domain-separated hash commitment over the committed
   theorem inputs and the deviation domain, so the exact claim is recomputable
   and mutation-detectable. Results never claim generic :cooperation-supported.
   On failure the result carries a concrete profitable-deviation witness."
  [multi-epoch-result & {:keys [discount-factor punishment-payoff payoffs
                                assume-punishment-credible?
                                horizon strategy-profile deviation-model
                                monitoring-model payoff-model deviation-domain
                                evidence-tier scenario-evidence]
                         :or {discount-factor default-discount-factor
                              punishment-payoff default-punishment-payoff
                              assume-punishment-credible? default-assume-punishment-credible?
                              horizon default-horizon
                              strategy-profile default-strategy-profile
                              deviation-model default-deviation-model
                              monitoring-model perfect-public-monitoring
                              payoff-model default-payoff-model
                              deviation-domain default-deviation-domain
                              evidence-tier default-evidence-tier}}]
  (let [stats (:aggregated-stats multi-epoch-result {})
        default-R (:honest-mean-profit stats)
        default-T (:malice-mean-profit stats)
        R (or (:R payoffs) default-R)
        T (or (:T payoffs) default-T)
        P (or (:P payoffs) punishment-payoff)
        ctx {:repeated-game/horizon horizon
             :strategy/profile strategy-profile
             :deviation/model deviation-model
             :monitoring/model (normalize-monitoring-model monitoring-model)
             :payoff/model payoff-model
             :deviation-domain deviation-domain
             :evidence-tier evidence-tier
             :scenario-evidence scenario-evidence
             :assume-punishment-credible? assume-punishment-credible?}
        ctx-check (validate-theorem-context ctx)]
    (if-not (:ok? ctx-check)
      (assoc (base-theorem-certificate T R P discount-factor ctx)
             :status (:status ctx-check)
             :claim/conclusion (:claim/conclusion ctx-check)
             :basis :single-simulation-evidence
             :reason (:reason ctx-check)
             :cooperation-incentive-compatible? false
             :punishment-credible? (boolean assume-punishment-credible?)
             :strategy-profile-equilibrium? false
             :deterrence? false
             :cooperation-region? false
             :detail (:detail ctx-check))
      (if (some #(map? %) [R T P])
        (evaluate-interval-deterrence T R P discount-factor ctx)
        (scalar-grim-trigger-deterrence T R P discount-factor ctx)))))

(defn evaluate-repeated-game-deterrence-threshold
  "Backward-compatible alias for `evaluate-grim-trigger-deterrence`.

   Note: unlike the legacy implementation, this now applies the full
   applicability contract — a deviation that is not profitable (T <= R) is
   reported :not-applicable rather than :pass, and δ = 1 is :invalid-input."
  [multi-epoch-result & {:as opts}]
  (apply evaluate-grim-trigger-deterrence multi-epoch-result
         (mapcat identity opts)))

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
      :grim-trigger-deterrence        result-map (canonical alias of :repeated-game-deterrence)
      :overall-status          :pass | :fail | :inconclusive
      :pass-count              int
      :fail-count              int
      :inconclusive-count      int
      :coverage                double-or-nil
      :coverage-basis          :claim-evaluators
      :summary                 string}

   :coverage is computed over the six claim evaluators only. The :grim-trigger
   and :repeated-game-deterrence results are model-specific and reported
   separately; they are not part of claim coverage. :repeated-game-deterrence
   (aliased as :grim-trigger-deterrence) carries a full theorem certificate and
   a :scope of :repeated-game/grim-trigger-deterrence to make explicit that it
   is a model-specific grim-trigger deterrence claim, not a general Folk-theorem
   result."
  [multi-epoch-result]
  (let [claim-results    (mapv #(% multi-epoch-result) evaluators)
        mech-proxies     (evaluate-mechanism-proxies multi-epoch-result)
        grim-trigger     (evaluate-grim-trigger-stability multi-epoch-result)
        repeated-game-deterrence (assoc
                                  (evaluate-grim-trigger-deterrence multi-epoch-result)
                                  :scope grim-trigger-theorem-type)
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
      :grim-trigger-deterrence     repeated-game-deterrence
      :overall-status      overall
      :pass-count          pass-count
      :fail-count          fail-count
      :inconclusive-count  inc-count
      :summary             summary
      :coverage-basis      :claim-evaluators
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
