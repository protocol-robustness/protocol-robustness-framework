(ns resolver-sim.protocols.sew.financial.loss
  "Sew-specific classification of financial loss modes.

   A financial loss mode exists when a protocol, escrow, module, vault, or
   settlement path can no longer satisfy, or may no longer be able to
   satisfy, the value obligations implied by prior commitments.

   Loss is NOT merely 'someone lost money'. It is a lifecycle state that may
   be provisional, pending, realized, or irrecoverable.

   Key design invariant: shortfall does NOT imply realized user loss. A
   shortfall during an open recovery window is :loss-risk or
   :loss-pending-finality, not :loss-realized. Loss is only realized when
   financial finality has occurred and the shortfall has not been cured.

   See also:
     resolver-sim.financial.taxonomies — general taxonomy definitions
     (loss statuses, ordinals) used by this namespace.

   This is a Sew reference implementation. Protocols with different
   world state shapes should implement their own classifiers using
   the same taxonomy vocabulary."
  (:require [resolver-sim.financial.taxonomies :as tax]
            [resolver-sim.protocols.sew.financial.finality :as finality]
            [resolver-sim.protocols.sew.types :as t]))

;; ── Loss status lifecycle ───────────────────────────────────────────────────
;; Taxonomy: resolver-sim.financial.taxonomies/loss-statuses

;; ── Shortfall quantification ─────────────────────────────────────────────────

(defn- yield-leg-shortfall?
  "True when shortfall only affects the yield leg, not principal.
   Works for both v1 (basis < principal) and v2 shortfall formats.
   Principal is intact when fulfilled-amount >= principal."
  [position shortfall]
  (and position shortfall
       (let [principal (long (or (:principal position) 0))
             fulfilled (long (or (:fulfilled-amount shortfall) 0))]
         (and (pos? principal) (>= fulfilled principal)))))

(defn shortfall-total
  "Aggregate shortfall across all yield positions for a given token.
   Returns {:fulfilled N :deferred N :haircut N :positions-with-shortfall N
            :yield-leg-shortfall-count N :principal-shortfall-count N}."
  [world token]
  (let [positions (vals (get world :yield/positions {}))
        token-pos (filter #(= token (:token %)) positions)
        with-sf   (filter :shortfall token-pos)
        yield-leg? (fn [p] (yield-leg-shortfall? p (:shortfall p)))
        has-principal-loss? (fn [p] (and (:shortfall p) (not (yield-leg? p))))]
    {:fulfilled-total          (reduce + 0 (map #(get-in % [:shortfall :fulfilled-amount] 0) with-sf))
     :deferred-total           (reduce + 0 (map #(get-in % [:shortfall :deferred-amount] 0) with-sf))
     :haircut-total            (reduce + 0 (map #(get-in % [:shortfall :haircut-amount] 0) with-sf))
     :positions-with-shortfall (count with-sf)
     :yield-leg-shortfall-count (count (filter yield-leg? with-sf))
     :principal-shortfall-count (count (filter has-principal-loss? with-sf))}))

;; ── Loss classifier ──────────────────────────────────────────────────────────

(defn classify-loss
  "Classify the financial loss status for the current world.

   Reads yield positions, escrow states, slashing entries, and
   claimable balances to determine whether obligations are normal,
   at risk, pending finality, realized, or irrecoverable.

   Parameters:
     world        — current world state
     token        — asset token (e.g., :USDC)
     opts         — optional map with:
       :resolve-financial-finality?  — if true, checks financial finality
                                       to distinguish loss-pending from
                                       loss-realized (default true)
       :max-irrecoverable-ratio       — if total held + claimable is below
                                       this fraction of total obligations,
                                       classify as :loss-irrecoverable

   Returns:
     {:loss/status                         :loss-pending-finality
      :loss/scope                          :yield-module
      :loss/token                          :USDC
      :loss/shortfall {:fulfilled N :deferred N :haircut N}
      :loss/user-realized?                 false
      :loss/protocol-realized?             false
      :loss/reason                         :partial-liquidity
      :financial-phase                     :recoverable
      :financially-final?                  false}"
  ([world token] (classify-loss world token {}))
  ([world token {:keys [resolve-financial-finality? max-irrecoverable-ratio]
                 :or   {resolve-financial-finality? true
                        max-irrecoverable-ratio     0.2}}]
   (let [shortfall  (shortfall-total world token)
         has-sf?    (pos? (:positions-with-shortfall shortfall))
         fin        (when resolve-financial-finality?
                      (let [escrows (get world :escrow-transfers {})]
                        (if (seq (keys escrows))
                           ;; Financially final only when ALL workflows are final.
                           ;; Per-workflow evaluation is conservative: a single open
                           ;; gate on any escrow blocks loss realization on all others.
                           ;; This avoids premature loss classification during
                           ;; multi-escrow disputes where one escrow settles early
                           ;; while another is still challengeable.
                          (let [results (map (fn [[wf _]]
                                               (finality/classify-financial-finality world wf))
                                             escrows)
                                phases  (map :financial/phase results)
                                all-final? (every? :financially-final? results)]
                            {:financially-final? all-final?
                             :financial/phase   (if all-final? :financially-final
                                                    (last (sort-by
                                                           #(case %
                                                              :provisional 0
                                                              :challengeable 1
                                                              :recoverable 2
                                                              :finalizing 3
                                                              :financially-final 4)
                                                           phases)))})
                          {:financially-final? true  ;; no escrows = trivially final
                           :financial/phase :financially-final})))
         ff-final?  (if fin (:financially-final? fin) false)
         ff-phase   (if fin (:financial/phase fin) :provisional)]

     (cond
       ;; No shortfall — normal
       (not has-sf?)
       {:loss/status              :normal
        :loss/scope               :yield-module
        :loss/token               token
        :loss/shortfall           shortfall
        :loss/user-realized?      false
        :loss/protocol-realized?  false
        :loss/reason              nil
        :financial-phase          ff-phase
        :financially-final?       ff-final?}

        ;; Shortfall exists but financial finality not yet reached
        ;; and escrow is still in a non-terminal state → risk, not yet impaired
       (and (not ff-final?) (some-> fin :financial/phase #{:provisional :challengeable}))
       {:loss/status              :loss-risk
        :loss/scope               :yield-module
        :loss/token               token
        :loss/shortfall           shortfall
        :loss/user-realized?      false
        :loss/protocol-realized?  false
        :loss/reason              :open-recovery-window
        :financial-phase          ff-phase
        :financially-final?       false}

        ;; Shortfall exists, not yet financially final, and pipeline is past
        ;; challenge window → obligations are impaired pending finality
       (not ff-final?)
       {:loss/status              :loss-pending-finality
        :loss/scope               :yield-module
        :loss/token               token
        :loss/shortfall           shortfall
        :loss/user-realized?      false
        :loss/protocol-realized?  false
        :loss/reason              :awaiting-finality
        :financial-phase          ff-phase
        :financially-final?       false}

        ;; Shortfall + financially final → total obligations (deferred + haircut)
       (and ff-final? (pos? (+ (:deferred-total shortfall) (:haircut-total shortfall))))
       (let [held            (get-in world [:total-held token] 0)
             claim           (reduce + 0
                                     (for [[_ addr-map] (get world :claimable {})
                                           [_ amt] addr-map]
                                       (long amt)))
             total-oblig     (+ (:fulfilled-total shortfall)
                                (:deferred-total shortfall)
                                (:haircut-total shortfall))
             outstanding     (+ (:deferred-total shortfall) (:haircut-total shortfall))
             coverage-ratio  (/ (+ held claim) (max outstanding 1))
             max-loss?       (and max-irrecoverable-ratio
                                  (< coverage-ratio max-irrecoverable-ratio))
             user-loss-ratio (double (/ (:haircut-total shortfall) (max total-oblig 1)))
               ;; For irrecoverable: the full outstanding (deferred + haircut) is lost
               ;; because there's no recovery path, not just the haircut portion
             irr-loss-ratio (double (/ (+ (:deferred-total shortfall) (:haircut-total shortfall))
                                       (max total-oblig 1)))]
         {:loss/status              (if max-loss? :loss-irrecoverable :loss-realized)
          :loss/scope               :yield-module
          :loss/token               token
          :loss/shortfall           shortfall
          :loss/user-realized?      (if (pos? (if max-loss? irr-loss-ratio user-loss-ratio))
                                      (if max-loss? irr-loss-ratio user-loss-ratio)
                                      false)
          :loss/protocol-realized?  (pos? (:haircut-total shortfall))
          :loss/reason              (cond max-loss? :irrecoverable-shortfall
                                          (pos? (:haircut-total shortfall)) :haircut-loss
                                          :else :partial-liquidity)
          :financial-phase          ff-phase
          :financially-final?       true})

        ;; Fallback (shortfall exists but no deferred or haircut — already resolved)
       :else
       {:loss/status              :normal
        :loss/scope               :yield-module
        :loss/token               token
        :loss/shortfall           shortfall
        :loss/user-realized?      false
        :loss/protocol-realized?  false
        :loss/reason              :shortfall-resolved
        :financial-phase          ff-phase
        :financially-final?       ff-final?}))))

;; ── Convenience predicates ───────────────────────────────────────────────────

(defn loss-active?
  "True when the loss status is beyond :normal."
  [loss-classification]
  (not= :normal (:loss/status loss-classification)))

(defn user-loss-realized?
  "True when loss has been realized or is irrecoverable, or user has explicitly realized their claim."
  [loss-classification]
  (boolean (and (loss-active? loss-classification)
                (or (= :loss-realized (:loss/status loss-classification))
                    (= :loss-irrecoverable (:loss/status loss-classification))
                    (:loss/user-realized? loss-classification)))))