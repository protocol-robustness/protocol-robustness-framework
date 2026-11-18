(ns resolver-sim.protocols.sew.runner
  "Contract-model trial runner and divergence detector.

   Provides a full-lifecycle escrow runner that drives state through the
   contract model (lifecycle → resolution → timed-actions) and exposes
   the same profit fields as the idealised dispute.clj model.

   The divergence detector compares idealized outcomes (from dispute/resolve-dispute)
   against contract-model outcomes and emits a :divergence map when they differ.

   Key design:
     - run-trial returns the same keys as dispute/resolve-dispute so it can be
       dropped into run-batch without changing the aggregation code.
     - divergence fields are extra — existing stats ignore them.
     - run-trial is pure: takes an explicit rng-fn for random decisions.
     - Escalation is modelled with Priority-3 authority (no custom-resolver):
       et.dispute-resolver tracks the current-round resolver through escalations."
  (:require [resolver-sim.time.model :as tm]
            [resolver-sim.time.context :as time-ctx]
            [resolver-sim.protocols.sew.snapshot  :as snap]
            [resolver-sim.protocols.sew.types      :as t]
            [resolver-sim.protocols.sew.lifecycle  :as lc]
            [resolver-sim.protocols.sew.resolution :as res]
            [resolver-sim.protocols.sew.invariants :as inv]
            [resolver-sim.util.attribution        :as attr]
            [resolver-sim.util.state-monad        :as sm]
            [resolver-sim.util.attributed-monad   :as am]))

;; ---------------------------------------------------------------------------
;; Canonical snapshot for simulation trials
;;
;; Mirrors the parameter knobs exposed by the batch runner.
;; ---------------------------------------------------------------------------

(defn make-trial-snapshot
  "Build a ModuleSnapshot from standard batch params.

   Params:
     :resolver-fee-bps             — protocol fee (default 50 = 0.5%)
     :appeal-window-duration       — seconds (default 0 = no appeal window)
     :max-dispute-duration         — seconds (default 2592000 = 30 days)
     :appeal-bond-protocol-fee-bps — protocol cut of appeal bond (default 0)"
  [{:keys [resolver-fee-bps appeal-window-duration max-dispute-duration
           appeal-bond-protocol-fee-bps]
   :or   {resolver-fee-bps             50
            appeal-window-duration       0
            max-dispute-duration         (* 30 time-ctx/seconds-per-day)
            appeal-bond-protocol-fee-bps 0}}]
  (snap/make-escrow-snapshot
   {:escrow-fee-bps               resolver-fee-bps
    :appeal-window-duration       appeal-window-duration
    :max-dispute-duration         max-dispute-duration
    :appeal-bond-protocol-fee-bps appeal-bond-protocol-fee-bps}))

;; ---------------------------------------------------------------------------
;; Resolver chain: Priority-3 path through escalation rounds
;;
;; Level 0 = initial resolver, 1 = senior resolver, 2 = Kleros (final round).
;; ---------------------------------------------------------------------------

(defn- resolver-chain [base-resolver]
  [base-resolver "0xSeniorResolver" "0xKleros"])

;; ---------------------------------------------------------------------------
;; Internal: single-trial lifecycle with multi-round escalation
;; ---------------------------------------------------------------------------

;; run-lifecycle (deprecated) removed — only monadic path remains.
;; ---------------------------------------------------------------------------
;; Internal: resolution loop (shared logic, imperative, not monadic)
;; ---------------------------------------------------------------------------

(defn- resolution-loop
  "Run multi-round resolution with escalation.

   Intentionally imperative (loop/recur) rather than monadic:
   Clojure's recur only targets the closest enclosing loop or fn,
   and cannot be used from within a monadic bind (sm/bind ... (fn [...]))
   because bind introduces an intervening fn boundary.  Embedding
   multi-round escalation inside the bind chain would force deeply
   nested closures or a CPS transform — both worse than a plain loop.

   Returns {:world-after world :verdict-correct? bool
            :escalated? bool :escalation-level int}"
  [world wf-id from resolvers rng-fn strategy
   escalation-prob-correct escalation-prob-wrong]
  (loop [w world
         level 0]
    (let [current-resolver (get-in w [:escrow-transfers wf-id :dispute-resolver])
          verdict-correct? (case strategy
                             :honest    true
                             :lazy      (< (rng-fn) 0.5)
                             :malicious (< (rng-fn) 0.3)
                             :collusive (< (rng-fn) 0.8))
          is-release verdict-correct?
          rr           (res/execute-resolution w wf-id current-resolver is-release "0xhash" nil)
          w-resolved   (if (:ok rr) (:world rr) w)
          has-pending? (and (:ok rr) (:exists (t/get-pending w-resolved wf-id)))
          esc-prob    (if verdict-correct? escalation-prob-correct escalation-prob-wrong)
          escalate?   (and has-pending? (< (rng-fn) esc-prob))]
      (if escalate?
        (let [next-resolver (get resolvers (inc level) "0xKleros")
              er            (res/escalate-dispute w-resolved wf-id from
                                                  (fn [_ _ _ _]
                                                    {:ok true :new-resolver next-resolver}))]
          (if (:ok er)
            (recur (:world er) (inc level))
            (let [w-fin (if has-pending?
                          (let [dl  (:appeal-deadline (t/get-pending w-resolved wf-id))
                                w-t (tm/advance w-resolved {:seconds (+ dl 1)})
                                er2 (res/execute-pending-settlement w-t wf-id)]
                            (if (:ok er2) (:world er2) w-t))
                          w-resolved)]
              {:world-after w-fin
               :verdict-correct? verdict-correct?
               :escalated?       (> level 0)
               :escalation-level level})))
        (let [w-fin (if has-pending?
                      (let [dl  (:appeal-deadline (t/get-pending w-resolved wf-id))
                            w-t (tm/advance w-resolved {:seconds (+ dl 1)})
                            er  (res/execute-pending-settlement w-t wf-id)]
                        (if (:ok er) (:world er) w-t))
                      w-resolved)]
          {:world-after w-fin
           :verdict-correct? verdict-correct?
           :escalated?       (> level 0)
           :escalation-level level})))))

;; ---------------------------------------------------------------------------
;; Internal: monadic path — attribution-threaded version of run-lifecycle
;; ---------------------------------------------------------------------------

;; Phase 2 boundary:
;;
;; The monadic runner carries attribution explicitly in AttributedState through
;; setup transitions. Before entering the legacy-compatible resolution loop, the
;; explicit attribution context is rebound via with-attribution so existing
;; evidence/logging calls inside execute-resolution, escalate-dispute, and
;; execute-pending-settlement observe the same context.
;;
;; This is intentional: Phase 2 guarantees runtime attribution propagation and
;; parity. Phase 3 may replace dynamic fallback inside individual resolution
;; evidence emitters with explicit context, but should do so incrementally with
;; artifact-level parity tests.

(defn- run-lifecycle-monadic
  "Monadic version of run-lifecycle threading AttributedState.
   Attribution is threaded through protocol transitions via the monadic chain.
   The resolution loop is imperative (shared with the legacy path) since
   loop/recur cannot be embedded inside monadic bind chains."
  [rng-fn escrow-amount from to resolver-addr token snap appeal-bond-bps strategy
   escalation-prob-correct escalation-prob-wrong detection-prob]
  (let [wf-id 0
        resolvers (resolver-chain resolver-addr)
        initial-world (t/empty-world 1000)
        initial-attr  {:ctx/run-id "monadic-trial"
                       :ctx/strategy strategy
                       :ctx/escrow-amount escrow-amount}
        result
        (am/eval-attributed
         (sm/bind (lc/create-escrow-m from token to escrow-amount (t/make-escrow-settings {}) snap)
                  (fn [create-result]
                    (if-not (:ok create-result)
                      (throw (ex-info "create-escrow failed" create-result))
                      (sm/bind (am/update-attributed
                                #(assoc-in % [:escrow-transfers wf-id :dispute-resolver] resolver-addr))
                               (fn [_]
                                 (sm/bind (lc/raise-dispute-m wf-id from)
                                          (fn [raise-result]
                                            (if-not (:ok raise-result)
                                              (throw (ex-info "raise-dispute failed" raise-result))
                                              (sm/bind (sm/get-state)
                                                       (fn [attributed]
                                                         (let [w2 (attr/unwrap-state attributed)]
                                                           (attr/with-resolved-attribution attributed
                                                             (let [tk        (keyword token)
                                                                   fee       (get-in w2 [:total-fees tk] 0)
                                                                   afa       (get-in w2 [:escrow-transfers wf-id :amount-after-fee] 0)
                                                                   {:keys [world-after verdict-correct? escalated? escalation-level]}
                                                                   (resolution-loop w2 wf-id from resolvers
                                                                                    rng-fn strategy
                                                                                    escalation-prob-correct escalation-prob-wrong)
                                                                   detected?     (and (not verdict-correct?) (< (rng-fn) detection-prob))
                                                                   bond-amt      (long (/ (* afa appeal-bond-bps) 10000))
                                                                   profit-honest (long fee)
                                                                   profit-malice (if detected? (long (- fee bond-amt)) (long fee))
                                                                   final-state   (t/escrow-state world-after wf-id)
                                                                   inv-result    (inv/check-all world-after)]
                                                               (sm/return
                                                                {:dispute-correct?      verdict-correct?
                                                                 :appeal-triggered?     escalated?
                                                                 :escalated?            escalated?
                                                                 :escalation-level      escalation-level
                                                                 :slashed?              detected?
                                                                 :frozen?               detected?
                                                                 :escaped?              false
                                                                 :slashing-pending?     false
                                                                 :slashing-delay-weeks  0
                                                                 :slashing-reason       (when detected? :fraud)
                                                                 :profit-honest         profit-honest
                                                                 :profit-malice         profit-malice
                                                                 :strategy              strategy
                                                                 :cm/final-state        final-state
                                                                 :cm/fee                fee
                                                                 :cm/afa                afa
                                                                 :cm/invariants-ok?     (:all-hold? inv-result)
                                                                 :cm/inv-violations     (when-not (:all-hold? inv-result) (:results inv-result))}))))))))))))))
         initial-world
         initial-attr)]
    result))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn run-trial
  "Run one contract-model trial. Signature mirrors dispute/resolve-dispute.

   params keys (same as batch runner params map):
     :escrow-size                      — gross wei (default 10000)
     :resolver-fee-bps                 — protocol fee bps (default 50)
     :appeal-bond-bps                  — appeal bond bps (default 0)
     :appeal-window-duration           — seconds (default 0)
     :max-dispute-duration             — seconds (default 2592000)
     :slashing-detection-probability
     :escalation-probability-if-correct — prob party escalates correct verdict (default 0.05)
     :escalation-probability-if-wrong   — prob party escalates wrong verdict (default 0.60)
     :strategy                         — :honest | :lazy | :malicious | :collusive
     :attributed?                      — if true (default), use the monadic parallel path

   rng-fn — (fn [] → double in [0,1))"
  [rng-fn params]
  (let [snap    (make-trial-snapshot params)
        strategy (get params :strategy :honest)]
    (run-lifecycle-monadic
     rng-fn
     (get params :escrow-size 10000)
     "0xAlice"
     "0xBob"
     "0xResolver"
     "0xUSDC"
     snap
     (get params :appeal-bond-bps 0)
     strategy
     (get params :escalation-probability-if-correct 0.05)
     (get params :escalation-probability-if-wrong   0.60)
     (get params :slashing-detection-probability    0.1))))

;; ---------------------------------------------------------------------------
;; Divergence detector
;; ---------------------------------------------------------------------------

(defn compare-outcomes
  "Compare an idealised trial result with a contract-model trial result.

   Both maps must have :profit-honest, :profit-malice, :slashed?,
   :dispute-correct?, :appeal-triggered?, :escalation-level.

   Returns {:divergence? bool :diffs [...]} where each diff is a map:
     {:field kw :idealized val :contract-model val}"
  [idealized cm-result]
  (let [fields [:profit-honest :profit-malice :slashed? :dispute-correct? :appeal-triggered? :escalation-level]
        diffs  (for [f fields
                     :let [iv (get idealized f)
                           cv (get cm-result f)]
                     :when (not= iv cv)]
                 {:field f :idealized iv :contract-model cv})]
    {:divergence? (seq diffs)
     :diffs       (vec diffs)}))

(defn run-with-divergence-check
  "Run both the idealised model (via dispute-fn) and the contract model,
   then return both results plus a divergence report.

   dispute-fn — (fn [params] → idealized-result) e.g. (partial dispute/resolve-dispute rng ...)
   rng-fn     — (fn [] → double) for the contract model

   Returns:
     {:idealized  idealized-result
      :contract   cm-result
      :divergence compare-outcomes-result}"
  [dispute-fn rng-fn params]
  (let [ideal  (dispute-fn params)
        cm     (run-trial rng-fn params)
        diff   (compare-outcomes ideal cm)]
    {:idealized  ideal
     :contract   cm
     :divergence diff}))
