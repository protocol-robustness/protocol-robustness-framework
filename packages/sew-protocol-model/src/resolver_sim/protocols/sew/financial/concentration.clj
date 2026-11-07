(ns resolver-sim.protocols.sew.financial.concentration
  "Concentration (structural risk) analysis in relation to insolvency.

   Economic solvency answers \"can realizable assets cover liabilities\"; the
   headroom delta answers \"by how much\". Concentration asks HOW that exposure
   is distributed. A portfolio whose obligations, assets, or custody are
   concentrated in one token, one workflow/counterparty, or one custody contract
   is structurally brittle: a single failure can tip it into insolvency even
   when the aggregate ratio is comfortable.

   All measures are Herfindahl-Hirschman indices over shares, normalized to
   [0,1] (a portfolio of n equal parts → 0; a single part → 1) so they are
   comparable across portfolios and cutpoints. NO synthetic cross-token values
   are introduced — this is purely a distributional view of the canonical
   liability set and custody assets, so it is reproducible from the same
   committed sources as economic solvency.

   Concentration is reported as a structural DIMENSION of the assessment, not an
   enforced world invariant: it does not by itself declare solvency, it
   qualifies how brittle a given solvency/headroom conclusion is."
  (:require [resolver-sim.protocols.sew.financial.liabilities :as liab]
            [resolver-sim.protocols.sew.types :as t]))

(defn hhi
  "Herfindahl-Hirschman index over a collection of amounts: Σ (shareᵢ)² ∈ [1/n,1].
   Returns 0.0 for an empty or all-zero portfolio."
  [amounts]
  (let [total (reduce + 0 (map long amounts))]
    (if (pos? total)
      (let [shares (map #(/ (double %) (double total)) (map long amounts))]
        (reduce + 0 (map #(* % %) shares)))
      0.0)))

(defn normalized-hhi
  "HHI normalized to [0,1]: 0 = perfectly diversified (n equal parts), 1 = fully
   concentrated (single part)."
  [amounts]
  (let [n (count amounts)
        total (reduce + 0 (map long amounts))]
    (cond
      (not (pos? total)) 0.0
      (<= n 1) 1.0
      :else (let [h (hhi amounts)]
              (/ (- h (/ 1.0 n)) (- 1.0 (/ 1.0 n)))))))

(defn classify
  "Coarse concentration band for a normalized HHI:
     < 0.15 → :diversified
     < 0.25 → :moderately-concentrated
     ≥ 0.25 → :highly-concentrated"
  [nh]
  (cond
    (< nh 0.15) :diversified
    (< nh 0.25) :moderately-concentrated
    :else :highly-concentrated))

(defn- profile
  [label amounts]
  (let [nh (normalized-hhi amounts)]
    {:measure label
     :status :evaluated
     :count (count amounts)
     :total (reduce + 0 (map long amounts))
     :normalized-hhi nh
     :band (classify nh)}))

(defn liability-concentration
  "Concentration of canonical obligations across tokens."
  [world]
  (let [{:keys [per-token]} (liab/economic-liability-set world)]
    (profile :token (vals per-token))))

(defn asset-concentration
  "Concentration of realizable assets across tokens."
  [world]
  (profile :token (vals (liab/custody-assets world))))

(defn obligation-concentration
  "Concentration of obligations across WORKFLOWS/obligation holders: every live
   escrow AFA and every claimable-v2 obligation is one obligor position, so a
   single workflow dominating the book is visible even when spread across
   tokens."
  [world]
  (let [escrows (:escrow-transfers world {})
        claimable (:claimable-v2 world {})
        amounts (concat
                 (for [[_ et] escrows
                       :when (contains? t/live-states (:escrow-state et))]
                   (long (:amount-after-fee et 0)))
                 (for [[_ domains] claimable
                       :let [amt (reduce + 0 (for [[_ recipients] domains
                                                  [_ amount] recipients]
                                               (long amount)))]
                       :when (pos? amt)]
                   amt))]
    (profile :workflow amounts)))

(defn- per-contract-totals
  "Flatten an external balance snapshot ({[:contract token] amount} or
   {contract {token amount}}) into {contract total}."
  [balances]
  (reduce-kv (fn [acc k v]
               (cond
                 (and (vector? k) (= 2 (count k)))
                 (update acc (first k) (fnil + 0) (long v))

                 (map? v)
                 (reduce-kv (fn [acc2 _tok amt] (update acc2 k (fnil + 0) (long amt)))
                            acc v)

                 :else acc))
             {}
             balances))

(defn custodian-concentration
  "Concentration of observed custody balances across contracts. Requires the
   external :solvency/contract-balances snapshot; when none is attached the
   measure is {:status :unavailable} — fail-closed, like external coverage
   (absence of evidence is not evidence of diversification)."
  [world]
  (let [balances (:solvency/contract-balances world)]
    (if (nil? balances)
      {:status :unavailable}
      (-> (profile :contract (vals (per-contract-totals balances)))
          (assoc :status :evaluated)))))

(defn concentration-profile
  "Full structural concentration profile for a world, reported as an assessment
   dimension:
     {:liabilities  {...token concentration...}
      :assets       {...}
      :obligations  {...workflow concentration...}
      :custodians   {:status :evaluated|:unavailable ...}}"
  [world]
  {:liabilities (liability-concentration world)
   :assets (asset-concentration world)
   :obligations (obligation-concentration world)
   :custodians (custodian-concentration world)})

(defn concentration-risk?
  "True when any evaluated concentration measure is :highly-concentrated (a
   structural brittleness signal — not a solvency verdict)."
  [profile]
  (some #(= :highly-concentrated (:band %))
        (keep (fn [m] (when (= :evaluated (:status m)) m))
              (vals profile))))
