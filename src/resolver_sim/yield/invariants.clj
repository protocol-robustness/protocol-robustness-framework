(ns resolver-sim.yield.invariants
  "Generic accounting invariants for yield mechanism (provider + Sew)."
  (:require [clojure.set]
            [resolver-sim.yield.risk :as risk]
            [resolver-sim.yield.invariant-catalog :as cat]
            [resolver-sim.yield.partial-fill :as partial-fill]
            [resolver-sim.yield.pro-rata-propagation-policy :as propagation-policy]
            [resolver-sim.pro-rata.allocation :as pro-rata-allocation]
            [resolver-sim.time.context :as time-ctx]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.time.deadlines :as dl]
            [resolver-sim.logging :as log]))

(declare check-pro-rata-accounting-reconciles)
(declare mode-over-allocation-violations)

(defn- inv-result [holds?]
  {:holds? (boolean holds?)})

(defn- token-name [token]
  (if (keyword? token) (name token) (str token)))

(defn- held-for-token [world token]
  (let [t (token-name token)]
    (long (or (get-in world [:yield/held-balances t])
              (get-in world [:yield/held-balances (keyword t)])
              0))))

(defn check-position-consistency
  "Principal/shares/realized >= 0; unrealized >= 0 unless :mark-to-market.
   Returns {:holds? bool :violations [{:owner-id :issues [...]}]}."
  [world]
  (let [violations
        (into []
              (keep
               (fn [[oid pos]]
                 (let [mid (:module/id pos)
                       tok (:token pos)
                       risk (get-in world [:yield/risk mid tok] {})
                       sf   (:shortfall pos)
                       sf-model (get-in world [:yield/shortfall-models mid tok])
                       mtm? (= :mark-to-market (risk/effective-loss-mode risk))

                    ;; If principal-loss model and recoverable=false, 
                    ;; we expect negative unrealized/principal.
                       authorized-impairment? (and (= (:type sf-model) :principal-loss)
                                                   (not (:recoverable sf-model true)))

                       issues (cond-> []
                                (and (not authorized-impairment?) (neg? (:principal pos 0))) (conj :negative-principal)
                                (neg? (:shares pos 0)) (conj :negative-shares)
                                (neg? (:realized-yield pos 0)) (conj :negative-realized-yield)
                                (and (not mtm?) (not authorized-impairment?) (neg? (:unrealized-yield pos 0))) (conj :negative-unrealized-yield))]
                   (when (seq issues)
                     {:owner-id oid :issues issues})))
               (:yield/positions world {})))]
    {:holds? (empty? violations)
     :violations (vec violations)
     :checks {:principal-non-negative (if (some #(contains? (set (:issues %)) :negative-principal) violations) :fail :pass)
              :shares-non-negative (if (some #(contains? (set (:issues %)) :negative-shares) violations) :fail :pass)
              :realized-yield-non-negative (if (some #(contains? (set (:issues %)) :negative-realized-yield) violations) :fail :pass)
              :unrealized-yield-non-negative (if (some #(contains? (set (:issues %)) :negative-unrealized-yield) violations) :fail :pass)}}))

(defn check-realized-non-negative
  [world]
  (let [holds? (every? #(>= (:realized-yield % 0) 0) (vals (:yield/positions world {})))]
    {:holds? holds? :violations [] :checks {:realized-yield-non-negative (if holds? :pass :fail)}}))

(defn check-status-fsm
  [world]
  (let [allowed #{:active :unwinding :withdrawn :settled}
        holds? (every? #(contains? allowed (:status %)) (vals (:yield/positions world {})))]
    {:holds? holds? :violations [] :checks {:status-fsm-valid (if holds? :pass :fail)}}))

(defn check-shortfall-splits
  "When :shortfall exists, fulfilled + deferred + haircut (+ basis fold) = basis.

   The single-position withdraw path folds a negative unrealized-yield into
   :basis-amount and records it on the shortfall as
   :basis-negative-unrealized; the splits reconcile once that term is restored.
   Shared-withdrawal shortfalls never fold (basis = fulfilled + deferred) and
   record 0, so this reduces to fulfilled + deferred + haircut = basis there."
  [world]
  (let [holds? (every? (fn [pos]
                         (if-let [sf (:shortfall pos)]
                           (let [f (long (or (:fulfilled-amount sf) 0))
                                 d (long (or (:deferred-amount sf) 0))
                                 h (long (or (:haircut-amount sf) 0))
                                 b (long (or (:basis-amount sf) 0))
                                 fold (long (or (:basis-negative-unrealized sf) 0))]
                             (= (+ f d h fold) b))
                           true))
                       (vals (:yield/positions world {})))]
    {:holds? holds? :violations [] :checks {:shortfall-splits-balanced (if holds? :pass :fail)}}))

(defn check-partial-liquidity-principal
  "Under :partial-liquidity, unwinding positions must not haircut principal on the shortfall map."
  [world]
  (let [holds? (every? (fn [pos]
                         (let [risk (get-in world [:yield/risk (:module/id pos) (:token pos)] {})
                               failures (risk/normalize-failure-modes (:failure-modes risk))
                               partial? (contains? failures :partial-liquidity)]
                           (if (and partial? (= (:status pos) :unwinding) (:shortfall pos))
                             (let [sf (:shortfall pos)
                                   principal (:principal pos 0)
                                   f (long (or (:fulfilled-amount sf) 0))
                                   d (long (or (:deferred-amount sf) 0))
                                   b (long (or (:basis-amount sf) 0))
                                   fold (long (or (:basis-negative-unrealized sf) 0))]
                               (and (pos? principal)
                                    (zero? (long (or (:haircut-amount sf) 0)))
                                    (= (+ f d fold) b)))
                             true)))
                       (vals (:yield/positions world {})))]
    {:holds? holds? :violations [] :checks {:partial-liquidity-principal-intact (if holds? :pass :fail)}}))

(defn check-value-conservation
  "Conservation invariant: shortfall components are non-negative and
   deferred-amount (when present) does not exceed the position expected
   residual value (principal + unrealized-yield).

   This is a simplified check until the full principal/yield split
   accounting (Phase 3) is complete — at which point this invariant
   will verify: total-value = claimable + deferred + loss.

   For now, verifies: deferred-amount + haircut-amount >= 0
   and (deferred-amount + haircut-amount) <= principal + unrealized-yield
   when shortfall exists."
  [world]
  (let [holds? (every? (fn [pos]
                         (let [principal (long (:principal pos 0))
                               unrealized (long (:unrealized-yield pos 0))
                               sf (:shortfall pos)
                               deferred  (long (or (:deferred-amount sf) 0))
                               haircut   (long (or (:haircut-amount sf) 0))
                               fulfilled (long (or (:fulfilled-amount sf) 0))]
                           (and (>= deferred 0) (>= haircut 0) (>= fulfilled 0)
                                (if sf
                                  (<= (+ deferred haircut) (+ principal (max 0 unrealized)))
                                  true))))
                       (vals (:yield/positions world {})))]
    {:holds? holds? :violations [] :checks {:value-conservation-valid (if holds? :pass :fail)}}))

(defn- position-shortfall-value
  "Total value backing a position's shortfall basis.

   When the shortfall records :settlement-value (the exact economic value —
   principal + realized + unrealized — at crystallization), use it: this is
   precise and prevents the aggregate over-count check from being masked. The
   legacy reconstruction (principal + realized + max(0, unrealized) +
   deferred-amount) is retained only for shortfalls that predate the field; it
   double-counts deferred principal and is therefore lenient (never false
   positives, but cannot detect basis > value)."
  [p]
  (if-let [sv (get-in p [:shortfall :settlement-value])]
    (long sv)
    (+ (long (:principal p 0))
       (long (:realized-yield p 0))
       (max 0 (long (:unrealized-yield p 0)))
       (long (or (get-in p [:shortfall :deferred-amount]) 0)))))

(defn- position-shortfall-basis
  "Shortfall basis-amount for a position (0 when no shortfall or nil basis)."
  [p]
  (long (or (get-in p [:shortfall :basis-amount]) 0)))

(defn- shortfall-amounts
  "All amounts that participate in the aggregate shortfall cap for a position:
   basis/settlement/deferred shortfall amounts and the position value terms."
  [p]
  (let [sf (:shortfall p)]
    (remove nil? [(:basis-amount sf)
                  (:settlement-value sf)
                  (:deferred-amount sf)
                  (:principal p)
                  (:realized-yield p)
                  (:unrealized-yield p)])))

(defn check-aggregate-shortfall-cap
  "Aggregate shortfall per (module-id, token) pair must not exceed
   the sum of position values (principal + realized-yield + max(0, unrealized-yield)
   + deferred-amount) in that pair. This prevents systemic over-counting where the
   total recorded shortfall across all positions exceeds available value.

   Fails closed on non-integral amounts: a fractional basis-amount or value term
   is never silently truncated to long (which could mask a genuine overage); it
   is reported as a :non-integral-amount violation instead.

   Returns {:holds? bool :violations [{:module-id mid :token tok
                                       :total-basis n :total-value n
                                       :imbalance n} | {:code :non-integral-amount ...}]}."
  [world]
  (let [positions (vals (:yield/positions world {}))
        by-key (group-by (fn [p] [(:module/id p) (:token p)]) positions)
        non-integral
        (into []
              (keep (fn [p]
                      (let [amounts (shortfall-amounts p)
                            non-int (filter (fn [v] (not (integer? v))) amounts)]
                        (when (seq non-int)
                          {:code :non-integral-amount
                           :module-id (:module/id p)
                           :token (:token p)
                           :amounts (vec non-int)}))))
              positions)
        violations
        (if (seq non-integral)
          non-integral
          (into []
                (keep (fn [[[mid tok] pos-group]]
                        (let [total-basis (reduce + 0 (map position-shortfall-basis pos-group))
                              total-value (reduce + 0 (map position-shortfall-value pos-group))]
                          (when (> total-basis total-value)
                            {:module-id mid :token tok
                             :total-basis total-basis
                             :total-value total-value
                             :imbalance (- total-basis total-value)})))
                      by-key)))]
    {:holds? (empty? violations)
     :violations (vec violations)
     :checks {:aggregate-shortfall-within-value (if (seq violations) :fail :pass)}}))

(defn check-aggregate-shortfall
  "Compatibility alias for check-aggregate-shortfall-cap.

   Both assert that the summed shortfall basis of each (module-id, token) pair
   does not exceed the summed position value of that pair, preventing systemic
   over-counting. Retained as a distinct registered id for callers that
   referenced it before consolidation; it performs the identical check, so the
   two are never in disagreement.

   Returns {:holds? bool
            :violations [{:module-id mid :token tok
                          :total-basis n :total-value n
                          :imbalance n} ...]}."
  [world]
  (check-aggregate-shortfall-cap world))

(defn check-aggregate
  "Verify aggregate yield position values and shortfall balances are consistent
   across positions for each (module-id, token) pair.

   For each pair the sum of shortfall splits (fulfilled + deferred + haircut)
   must equal the sum of shortfall basis-amounts, and shortfall balances must
   not exceed the pair's total available value.

   FAILS CLOSED ON NON-INTEGRAL AMOUNTS (consistent with
   check-aggregate-shortfall-cap): a fractional split/basis/deferred/haircut/
   value term is never silently truncated to long (which could mask a genuine
   imbalance); it is reported as a :non-integral-amount violation instead.

   Returns {:holds? bool
            :violations [{:module-id mid :token tok :issues [kw ...]} ...]
                         | {:code :non-integral-amount ...}}."
  [world]
  (let [positions (vals (:yield/positions world {}))
        by-key (group-by (fn [p] [(:module/id p) (:token p)]) positions)
        non-integral
        (into []
              (keep (fn [p]
                      (let [sf (:shortfall p)
                            amounts (remove nil?
                                            (concat (shortfall-amounts p)
                                                    [(:fulfilled-amount sf)
                                                     (:haircut-amount sf)
                                                     (:basis-negative-unrealized sf)]))
                            non-int (filter (fn [v] (not (integer? v))) amounts)]
                        (when (seq non-int)
                          {:code :non-integral-amount
                           :module-id (:module/id p)
                           :token (:token p)
                           :amounts (vec non-int)}))))
              positions)
        violations
        (if (seq non-integral)
          non-integral
          (into []
                (keep (fn [[[mid tok] pos-group]]
                        (let [splits-ok? (every? (fn [p]
                                                   (let [sf (:shortfall p)]
                                                     (if sf
                                                       (let [f (long (or (:fulfilled-amount sf) 0))
                                                             d (long (or (:deferred-amount sf) 0))
                                                             h (long (or (:haircut-amount sf) 0))
                                                             b (long (or (:basis-amount sf) 0))
                               ;; The single-position and shared-withdrawal paths both
                               ;; fold a negative unrealized-yield into basis-amount via
                               ;; :basis-negative-unrealized, so the splits reconcile once
                               ;; that term is restored: f + d + h + fold == b.
                               ;; The position's own :unrealized-yield is zeroed on
                               ;; settle (single-position path); shared-withdrawal positions
                               ;; retain it, but the fold is already in the shortfall, so
                               ;; the first disjunct is authoritative and the pos-neg
                               ;; fallback must NOT compensate for a present (but zeroed
                               ;; or tampered) :basis-negative-unrealized — that would
                               ;; mask tampering of the fold term for related-claims
                               ;; (shared-withdrawal) shortfalls.
                               ;; Legacy/hand-authored shortfalls that lack
                               ;; :basis-negative-unrealized retain a negative
                               ;; :unrealized-yield on the position instead; the second
                               ;; disjunct covers that case only.
                                                             fold (long (or (:basis-negative-unrealized sf) 0))
                                                             pos-neg (min 0 (long (:unrealized-yield p 0)))
                                                             has-fold? (contains? sf :basis-negative-unrealized)]
                                                         (or (= (+ f d h fold) b)
                                                             (and (not has-fold?)
                                                                  (= (+ f d h pos-neg) b))))
                                                       true)))
                                                 pos-group)
                              total-basis (reduce + 0 (map position-shortfall-basis pos-group))
                              total-value (reduce + 0 (map position-shortfall-value pos-group))
                              issues (cond-> []
                                       (not splits-ok?) (conj :shortfall-splits-unbalanced)
                                       (> total-basis total-value) (conj :aggregate-shortfall-over-value))]
                          (when (seq issues)
                            {:module-id mid :token tok
                             :total-basis total-basis
                             :total-value total-value
                             :issues issues})))
                      by-key)))]
    {:holds? (empty? violations)
     :violations (vec violations)
     :checks {:aggregate-shortfall-consistent (if (seq violations) :fail :pass)}}))

(defn check-withdrawal-ledger-conservation
  "Withdrawal-ledger conservation: every recorded withdrawal (single or batch)
   must not have settled more than the liquidity pool available to it.

   Each module withdrawal writes a ledger record under `:yield/withdrawal-ledger`
   carrying the pool available to that withdrawal (`:ledger/available`), the
   requested settlement value, and the settlement split (filled/deferred/haircut).
   The invariant asserts per record:
     - ledger certificate valid  — :ledger/hash reconciles to the canonical
       :ledger/preimage fixed point (tamper-evident, like the decision artifacts)
     - filled ≤ available  — the pool bound (catches over-allocation / double-spend)
     - filled ≤ requested  — never settle more than was requested
     - filled + deferred + haircut ≤ requested + rounding slack
     - deferred, haircut ≥ 0

   The `withdraw-many` batch coordinator allocates one shared pool first-come,
   first-served and enforces `filled ≤ available` by construction; this invariant
   re-checks that contract on persisted state so a regression (independent
   per-position fulfillment, each drawing the full pool) fails loudly instead of
   silently over-crediting a shared pool. A world with no ledger records passes
   vacuously (pre-ledger worlds are unaffected); records without a hash (legacy)
   skip the certificate check but keep the arithmetic bounds.

   RECORD-LOCAL BOUNDARY: this check re-derives each record's committed
   state-cutpoint-root against the world it is called with, so a record is
   validated only against the world at which it was the most recent withdrawal
   for that module/token.  It does NOT by itself prove that two individually
   valid records did not consume the same source state; that cross-invocation
   guarantee rests on monotonic source-state consumption between invocations
   and/or on the custody-scoped (disjoint-slice) recoverable bases enforced by
   the single-withdraw path (see `withdraw` docstring) and on the
   :yield/exposure slice-conservation invariant.

   Returns {:holds? bool
            :violations [{:ledger/id [...] :issues [kw ...]
                          :available n :requested n :filled n :deferred n :haircut n} ...]}."
  [world]
  (let [records (get world :yield/withdrawal-ledger [])
        violations
        (into []
              (keep
               (fn [r]
                 (let [available (long (or (:ledger/available r) 0))
                       requested (long (or (:ledger/requested r) 0))
                       filled    (long (or (:ledger/filled r) 0))
                       deferred  (long (or (:ledger/deferred r) 0))
                       haircut   (long (or (:ledger/haircut r) 0))
                       rows      (vec (:ledger/rows r))
                       owner-ids (vec (:ledger/owner-ids r))
                       row-owner-ids (mapv :owner-id rows)
                         ;; conservation tolerance is a committed policy field
                         ;; (numerical contract), not an implicit constant
                       slack (long (or (get-in r [:ledger/allocation-policy :conservation :tolerance])
                                       (get-in r [:ledger/conservation :tolerance])
                                       2))
                       cert-valid? (if (contains? r :ledger/hash)
                                     (let [body (dissoc r :ledger/hash :ledger/preimage
                                                        :ledger/canonical-bytes
                                                        :ledger/canonical-hash)
                                           proj (hc/project-committable-content body)]
                                       (and (or (nil? (:ledger/preimage r))
                                                (= (:ledger/preimage r) (pr-str body)))
                                            (= (:ledger/hash r)
                                               (:canonical/hash
                                                (hc/canonical-commitment
                                                 :evidence-record proj)))
                                            (hc/canonical-commitment-valid?
                                             :evidence-record proj
                                             {:canonical/bytes (:ledger/canonical-bytes r)
                                              :canonical/hash (:ledger/canonical-hash r)})))
                                     true)
                       row-total-filled (reduce + 0 (map #(long (or (:filled %) 0)) rows))
                       row-total-requested (reduce + 0 (map #(long (or (:requested %) 0)) rows))
                       row-total-deferred (reduce + 0 (map #(long (or (:deferred %) 0)) rows))
                       row-total-haircut (reduce + 0 (map #(long (or (:haircut %) 0)) rows))
                       row-negative-filled (some #(neg? (long (or (:filled %) 0))) rows)
                       duplicate-owner? (not= (count owner-ids) (count (distinct owner-ids)))
                       duplicate-row-owner? (not= (count row-owner-ids)
                                                  (count (distinct row-owner-ids)))
                       row-conservation-broken?
                       (some (fn [{:keys [requested filled deferred haircut]}]
                               (> (abs (- (+ (long (or filled 0))
                                             (long (or deferred 0))
                                             (long (or haircut 0)))
                                          (long (or requested 0))))
                                  slack))
                             rows)
                       row-over-request
                       (some (fn [{:keys [requested filled deferred haircut]}]
                               (> (+ (long (or filled 0))
                                     (long (or deferred 0))
                                     (long (or haircut 0)))
                                  (+ (long (or requested 0)) slack)))
                             rows)
                       run-root-mismatch?
                       (and (some? (:ledger/run-root r))
                            (not= (:ledger/run-root r)
                                  (partial-fill/ledger-run-root world)))
                       params-root-mismatch?
                       (and (some? (:ledger/params-root r))
                            (not= (:ledger/params-root r)
                                  (partial-fill/ledger-params-root world)))
                       state-cutpoint-mismatch?
                       (and (some? (:ledger/state-cutpoint-root r))
                            (not= (:ledger/state-cutpoint-root r)
                                  (partial-fill/ledger-state-cutpoint-root world)))
                       basis-root-mismatch?
                       (and (some? (:ledger/basis-root r))
                            (not= (:ledger/basis-root r)
                                  (partial-fill/ledger-basis-root
                                   {:state-cutpoint-root (:ledger/state-cutpoint-root r)
                                    :request-set-root (:ledger/request-set-root r)
                                    :request-order-root (:ledger/request-order-root r)
                                    :capacity-root (partial-fill/application-hash
                                                    {:available (long (:ledger/available r 0))})
                                    :allocation-policy-root (:ledger/allocation-policy-root r)
                                    :params-root (:ledger/params-root r)})))
                       request-set-root-mismatch?
                       (and (some? (:ledger/request-set-root r))
                            (not= (:ledger/request-set-root r)
                                  (partial-fill/ledger-request-set-root
                                   owner-ids rows)))
                       request-order-root-mismatch?
                       (and (some? (:ledger/request-order-root r))
                            (not= (:ledger/request-order-root r)
                                  (partial-fill/ledger-request-order-root owner-ids)))
                       allocation-policy-root-mismatch?
                       (and (some? (:ledger/allocation-policy r))
                            (some? (:ledger/allocation-policy-root r))
                            (not= (:ledger/allocation-policy-root r)
                                  (partial-fill/ledger-allocation-policy-root
                                   (:ledger/allocation-policy r))))
                       fcfs-over-commit?
                       (boolean
                        (:over?
                         (reduce (fn [{:keys [remaining over?]} row]
                                   (let [row-filled (long (or (:filled row) 0))
                                         remaining' (- remaining row-filled)]
                                     {:remaining remaining'
                                      :over? (or over? (neg? remaining'))}))
                                 {:remaining available :over? false}
                                 rows)))
                       issues (cond-> []
                                (not cert-valid?)
                                (conj :withdrawal-ledger-certificate-invalid)
                                (neg? filled)
                                (conj :withdrawal-negative-filled)
                                (> filled available)
                                (conj :withdrawal-exceeds-available-pool)
                                (> filled requested)
                                (conj :withdrawal-exceeds-requested)
                                (> (+ filled deferred haircut) (+ requested slack))
                                (conj :withdrawal-settlement-exceeds-requested)
                                (neg? deferred)
                                (conj :withdrawal-negative-deferred)
                                (neg? haircut)
                                (conj :withdrawal-negative-haircut)
                                 ;; Per-run root binding: a certificate committed
                                 ;; to an execution root must not appear under a
                                 ;; different execution world (recomputed).
                                run-root-mismatch?
                                (conj :withdrawal-run-root-mismatch)
                                 ;; State-cutpoint binding: the committed state
                                 ;; reference must reconcile to the current state
                                 ;; (content-addressed, not a timestamp label).
                                state-cutpoint-mismatch?
                                (conj :withdrawal-state-cutpoint-mismatch)
                                 ;; Params binding at the cutpoint.
                                params-root-mismatch?
                                (conj :withdrawal-params-root-mismatch)
                                 ;; Compositional basis: the basis root must
                                 ;; reconcile to its committed constituents.
                                basis-root-mismatch?
                                (conj :withdrawal-basis-root-mismatch)
                                 ;; Withdrawal-subject binding: the committed
                                 ;; request-set root must reconcile to this
                                 ;; ledger's own rows (no substitution between
                                 ;; withdrawals in the same run).
                                request-set-root-mismatch?
                                (conj :withdrawal-request-set-root-mismatch)
                                 ;; Input-order binding (FCFS): the committed
                                 ;; request order must reconcile to the declared
                                 ;; principals in order.
                                request-order-root-mismatch?
                                (conj :withdrawal-request-order-root-mismatch)
                                 ;; Allocator-policy binding: the committed policy
                                 ;; root must reconcile to the committed policy.
                                allocation-policy-root-mismatch?
                                (conj :withdrawal-allocation-policy-root-mismatch)
                                 ;; Per-principal uniqueness + exact bijection:
                                 ;; declared owners unique, row owners unique,
                                 ;; and the two sets identical.
                                (and (seq owner-ids) duplicate-owner?)
                                (conj :withdrawal-duplicate-owner)
                                (and (seq rows) duplicate-row-owner?)
                                (conj :withdrawal-duplicate-row-owner)
                                (and (seq rows)
                                     (not= (set row-owner-ids) (set owner-ids)))
                                (conj :withdrawal-address-coverage-mismatch)
                                 ;; Per-row economic conservation: each row's
                                 ;; settlement splits reconcile to its request.
                                (and (seq rows) row-conservation-broken?)
                                (conj :withdrawal-row-conservation)
                                 ;; Per-row reconstruction: totals reconcile to
                                 ;; rows, each row stays within its request, and
                                 ;; the FCFS pool bound holds for every prefix.
                                (and (seq rows) (not= row-total-filled filled))
                                (conj :withdrawal-row-total-mismatch)
                                (and (seq rows) (not= row-total-requested requested))
                                (conj :withdrawal-row-total-mismatch)
                                (and (seq rows) (not= row-total-deferred deferred))
                                (conj :withdrawal-row-total-mismatch)
                                (and (seq rows) (not= row-total-haircut haircut))
                                (conj :withdrawal-row-total-mismatch)
                                (and (seq rows) row-negative-filled)
                                (conj :withdrawal-negative-filled)
                                (and (seq rows) row-over-request)
                                (conj :withdrawal-row-exceeds-requested)
                                (and (seq rows) fcfs-over-commit?)
                                (conj :withdrawal-fcfs-over-commit))
                       mode-issues
                       (vec (map :kind (mode-over-allocation-violations r)))]
                   (when (or (seq issues) (seq mode-issues))
                     (merge (select-keys r [:ledger/id :ledger/module-id :ledger/token])
                            {:issues issues
                             :mode-issues mode-issues
                             :available available
                             :requested requested
                             :filled filled
                             :deferred deferred
                             :haircut haircut}))))
               records))]
    {:holds? (empty? violations)
     :violations (vec violations)
     :checks {:withdrawal-ledger-conserved (if (seq violations) :fail :pass)}}))

;; ---------------------------------------------------------------------------
;; Shared (pro-rata) withdrawal decision-artifact conservation
;; ---------------------------------------------------------------------------
;;
;; The universal withdrawal-domain contract the three modes share:
;;   every withdrawal allocation commits exactly one liquidity budget B; for the
;;   complete allocation scope governed by B,  Σ allocated-filled <= B.
;;   :single-position  →  B = the position's committed recoverable slice
;;   :fcfs-sequential  →  B = the batch's committed available pool
;;   :pro-rata         →  B = the shared pool's committed available liquidity
;;
;; This checker makes the :pro-rata mode independently replay-verifiable from
;; its committed decision artifact alone (the analog of the batch path's
;; :yield/withdrawal-ledger-conservation), so pro-rata's aggregate safety does
;; not rest on trusting the producer implementation.

(defn- shared-withdrawal-artifact?
  [artifact]
  (= :yield-withdraw-shared (:decision/source artifact)))

(defn- shared-withdrawal-row-violations
  "Per-row evidence checks for a :yield-withdraw-shared decision artifact.

   In addition to the row's own cap arithmetic, each row is cross-checked
   against the DECLARED :allocation/effective-caps: a producer that commits a
   self-consistent decision whose per-row caps exceed the declared per-owner
   capacity is over-allocating past the declared capability, and must be
   rejected (::declared-capacity-violated)."
  [artifact]
  (let [rows (vec (or (get-in artifact [:evidence :allocation-rows]) []))
        requested-map (or (:requested artifact) {})
        filled-map (or (:filled artifact) {})
        deferred-map (or (:deferred artifact) {})
        haircut-map (or (:haircut artifact) {})
        declared-caps (or (:allocation/effective-caps artifact) {})
        row-set (set (map :key rows))]
    (into
     []
     (concat
      (cond-> []
        (not= row-set (set (keys requested-map)))
        (conj {:kind ::row-request-set-mismatch
               :rows (vec (sort row-set))
               :requested (vec (sort (keys requested-map)))})
        (not= row-set (set (keys filled-map)))
        (conj {:kind ::row-filled-set-mismatch
               :rows (vec (sort row-set))
               :filled (vec (sort (keys filled-map)))})
        (not= row-set (set (keys deferred-map)))
        (conj {:kind ::row-deferred-set-mismatch
               :rows (vec (sort row-set))
               :deferred (vec (sort (keys deferred-map)))})
        ;; Declared caps for owners absent from the rows are a capacity-scope
        ;; mismatch (the declaration does not govern any allocation).
        (not (clojure.set/subset? (set (keys declared-caps)) row-set))
        (conj {:kind ::declared-capacity-unknown-owner
               :declared (vec (sort (remove row-set (keys declared-caps))))}))
      (mapcat
       (fn [row]
         (let [k (:key row)
               owed (long (or (:owed row) 0))
               raw-cap (:cap row)
               eff-cap (long (or (:effective-cap row) -1))
               expected-eff-cap (long (if (some? raw-cap)
                                        (min owed (long raw-cap))
                                        owed))
               ;; Declared per-owner capacity (nil = uncapped).  The row's :cap
               ;; field carries the DECLARED value (unclamped; e.g. a declared
               ;; cap above the request appears as-is), while :effective-cap is
               ;; the clamped min(owed, declared).
               declared-cap (get declared-caps k)
               expected-declared-eff-cap (long (if (some? declared-cap)
                                                 (min owed (long declared-cap))
                                                 owed))
               expected-raw-cap (when (some? declared-cap)
                                  (long declared-cap))
               filled (long (or (:filled row) -1))
               deferred (long (or (:deferred row) -1))
               f-map (long (get filled-map k 0))
               d-map (long (get deferred-map k 0))
               h-map (long (get haircut-map k 0))
               requested (long (get requested-map k 0))
               expected-deferred (max 0 (- owed filled))]
           (cond-> []
             (not= eff-cap expected-eff-cap)
             (conj {:kind ::effective-cap-mismatch :key k
                    :expected expected-eff-cap :observed eff-cap})
             (not= eff-cap expected-declared-eff-cap)
             (conj {:kind ::declared-capacity-violated :key k
                    :declared-cap declared-cap
                    :expected-effective-cap expected-declared-eff-cap
                    :observed-effective-cap eff-cap})
             (not= raw-cap expected-raw-cap)
             (conj {:kind ::declared-capacity-violated :key k
                    :declared-cap declared-cap
                    :expected-row-cap expected-raw-cap
                    :observed-row-cap raw-cap})
             (and (some? declared-cap) (> filled (long declared-cap)))
             (conj {:kind ::declared-capacity-exceeded :key k
                    :declared-cap (long declared-cap) :filled filled})
             (neg? filled)
             (conj {:kind ::negative-filled :key k :filled filled})
             (> filled eff-cap)
             (conj {:kind ::filled-exceeds-effective-cap :key k
                    :filled filled :effective-cap eff-cap})
             (> filled owed)
             (conj {:kind ::filled-exceeds-request :key k
                    :filled filled :owed owed})
             (not= f-map filled)
             (conj {:kind ::filled-map-mismatch :key k :expected filled :observed f-map})
             (neg? deferred)
             (conj {:kind ::negative-deferred :key k :deferred deferred})
             (not= deferred expected-deferred)
             (conj {:kind ::deferred-mismatch :key k
                    :expected expected-deferred :observed deferred})
             (not= d-map deferred)
             (conj {:kind ::deferred-map-mismatch :key k :expected deferred :observed d-map})
             (pos? h-map)
             (conj {:kind ::unexpected-haircut :key k :haircut h-map})
             (not= owed requested)
             (conj {:kind ::request-amount-mismatch :key k :expected owed :observed requested}))))
       rows)))))

(defn check-shared-withdrawal-conservation
  "Independently re-prove the shared-liquidity non-overallocation guarantee for a
   `:yield-withdraw-shared` decision artifact, from the committed evidence alone
   (no producer state or code path trusted beyond the artifact).

   The universal withdrawal-domain contract applied to the shared mode:
     B = :evidence :available-liquidity, and  Σ allocated-filled <= B.

   The capped pro-rata equality (locked theorem):
     Σ filled = min(B, Σ effective-demand_i)
     effective-demand_i = min(requested_i, effective-cap_i)
   i.e. when per-participant effective caps bind below both the requests and the
   pool, the filled total is demand-capped — it is NOT min(B, Σ requested).
   The safety bound Σ filled <= B holds regardless of that distinction.

   Violations (namespaced :kind):
     ::artifact-hash-invalid        — decision/hash does not reconcile.
     ::scope-mismatch               — :allocation/scope != :shared-liquidity-pool.
     ::mode-mismatch                — policy/fill-mode != :pro-rata.
     ::rounding-policy-mismatch     — policy rounding-policy != :largest-remainder.
     ::invalid-available            — :evidence :available-liquidity missing or not a non-negative integer.
     ::request-total-mismatch       — Σ requested != :evidence :total-requested.
     ::shortage-mismatch            — :evidence :shortage != max(0, total-requested - available).
    ::row-*-set-mismatch           — :requested/:filled/:deferred key sets disagree with allocation-rows.
    ::effective-cap-mismatch       — row effective-cap != min(owed, cap).
    ::declared-capacity-violated   — row effective-cap/cap disagrees with the DECLARED
                                     :allocation/effective-caps (over-allocation past the
                                     declared per-owner capability capacity).
    ::declared-capacity-exceeded   — filled > declared effective-cap.
    ::declared-capacity-unknown-owner — a declared cap governs no allocation row.
    ::negative-filled / ::negative-deferred — negative amounts.
     ::filled-exceeds-effective-cap / ::filled-exceeds-request — per-row upper bounds.
     ::filled-map-mismatch / ::deferred-map-mismatch — row vs artifact map disagreement.
     ::deferred-mismatch            — deferred != max(0, owed - filled).
     ::unexpected-haircut           — non-zero haircut under shared pro-rata.
     ::budget-exceeded              — Σ filled > available (the over-allocation bound).
     ::demand-exceeded              — Σ filled > Σ effective-cap.
     ::equality-violated            — Σ filled != min(available, Σ effective-cap) (locked theorem).
     ::deferred-total-mismatch      — Σ filled + Σ deferred != Σ requested.
     ::allocation-detail-mismatch   — Σ filled != :allocation-detail :total-allocated.
     ::residual-reconciliation-failed — Σ filled + :unallocated-residual != available
                                         (overflow not conserved at decision level).
     ::negative-residual            — :unallocated-residual < 0.
     ::mechanism-hash-invalid       — embedded :mechanism/result hash does not reconcile.
     ::mechanism-aggregate-mismatch — mechanism result aggregate disagrees with decision rows.

   Returns {:holds? bool :violations [...]}."
  [artifact]
  (let [rows (vec (or (get-in artifact [:evidence :allocation-rows]) []))
        requested-map (or (:requested artifact) {})
        A (get-in artifact [:evidence :available-liquidity])
        total-requested (get-in artifact [:evidence :total-requested])
        shortage (get-in artifact [:evidence :shortage])
        scope (:allocation/scope artifact)
        policy (:policy artifact)
        allocation-detail (get-in artifact [:evidence :allocation-detail])
        mechanism-evidence (get-in artifact [:evidence :allocation-mechanism-evidence])
        mechanism-result (:mechanism/result mechanism-evidence)
        residual (get-in artifact [:evidence :unallocated-residual])
        sum-requested (reduce + 0 (map #(long (:owed %)) rows))
        sum-effective-cap (reduce + 0 (map #(long (:effective-cap %)) rows))
        sum-filled (reduce + 0 (map #(long (:filled %)) rows))
        sum-deferred (reduce + 0 (map #(long (:deferred %)) rows))
        A-valid? (and (integer? A) (not (neg? A)))
        violations
        (cond-> []
          (not (partial-fill/decision-hash-valid? artifact))
          (conj {:kind ::artifact-hash-invalid})

          (not= :shared-liquidity-pool scope)
          (conj {:kind ::scope-mismatch :observed scope})

          (not= :pro-rata (:mode policy))
          (conj {:kind ::mode-mismatch :observed (:mode policy)})
          (not= :pro-rata (get-in artifact [:evidence :fill-mode]))
          (conj {:kind ::mode-mismatch :observed (get-in artifact [:evidence :fill-mode])})

          (not= :largest-remainder (:rounding-policy policy))
          (conj {:kind ::rounding-policy-mismatch :observed (:rounding-policy policy)})

          (not A-valid?)
          (conj {:kind ::invalid-available :observed A})

          (and A-valid?
               (not= sum-requested (long (or total-requested -1))))
          (conj {:kind ::request-total-mismatch
                 :expected sum-requested :observed total-requested})

          (and A-valid?
               (not= (long (or shortage -1)) (max 0 (- sum-requested (long A)))))
          (conj {:kind ::shortage-mismatch
                 :expected (max 0 (- sum-requested (long A))) :observed shortage})

          (and A-valid? (> sum-filled (long A)))
          (conj {:kind ::budget-exceeded :filled sum-filled :available (long A)})

          (> sum-filled sum-effective-cap)
          (conj {:kind ::demand-exceeded :filled sum-filled :effective-cap-total sum-effective-cap})

          (and A-valid? (not= sum-filled (min (long A) sum-effective-cap)))
          (conj {:kind ::equality-violated
                 :filled sum-filled
                 :available (long A)
                 :effective-cap-total sum-effective-cap
                 :expected-min (min (long A) sum-effective-cap)})

          (not= sum-requested (+ sum-filled sum-deferred))
          (conj {:kind ::deferred-total-mismatch
                 :requested sum-requested :filled sum-filled :deferred sum-deferred})

          (not= sum-filled (long (or (:total-allocated allocation-detail) -1)))
          (conj {:kind ::allocation-detail-mismatch
                 :filled sum-filled :allocation-detail allocation-detail})

          (and A-valid? (some? residual)
               (not= (long A) (+ sum-filled (long residual))))
          (conj {:kind ::residual-reconciliation-failed
                 :available (long A) :filled sum-filled :residual (long residual)})

          (and (some? residual) (neg? (long residual)))
          (conj {:kind ::negative-residual :residual (long residual)})

          (and mechanism-result
               (not (pro-rata-allocation/allocation-hash-valid? mechanism-result)))
          (conj {:kind ::mechanism-hash-invalid})

          (and mechanism-result
               (not= sum-filled (long (or (:allocated-total mechanism-result) -1))))
          (conj {:kind ::mechanism-aggregate-mismatch
                 :filled sum-filled
                 :mechanism-allocated-total (:allocated-total mechanism-result)})

          (and A-valid? mechanism-result
               (not= (long A) (long (or (:available mechanism-result) -1))))
          (conj {:kind ::mechanism-aggregate-mismatch
                 :available (long A)
                 :mechanism-available (:available mechanism-result)}))]
    (let [all-violations (vec (concat violations (shared-withdrawal-row-violations artifact)))]
      {:holds? (empty? all-violations)
       :violations all-violations})))

(defn check-shared-withdrawal-conservation-world
  "Run check-shared-withdrawal-conservation over every persisted
   :yield-withdraw-shared decision artifact in `:yield/partial-fill-decisions`.

   Returns {:holds? bool :violations [{:decision/id ... :kind ...}]}."
  [world]
  (let [shared (filter shared-withdrawal-artifact?
                       (vals (:yield/partial-fill-decisions world {})))
        violations
        (vec
         (mapcat (fn [artifact]
                   (concat
                    (map #(assoc % :decision/id (:decision/id artifact))
                         (:violations (check-shared-withdrawal-conservation artifact)))
                    ;; Mode-model dispatch boundary: a :yield-withdraw-shared
                    ;; decision must carry its :pro-rata mode tag, else it cannot
                    ;; be model-verified and the registered invariant fails.
                    (map #(assoc % :decision/id (:decision/id artifact))
                         (mode-over-allocation-violations artifact))))
                 shared))]
    {:holds? (empty? violations)
     :violations violations}))

;; ---------------------------------------------------------------------------
;; Withdrawal lineage conservation — cross-round (distinct from round-local)
;;
;; The round-local theorem (check-shared-withdrawal-conservation) proves each
;; round individually satisfies its committed liquidity budget and allocation
;; policy.  Once deferral produces descendants, the protocol also needs a
;; lineage-level theorem: two individually valid rounds can be globally invalid
;; if a descendant re-enters with the wrong residual, or if cumulative fill
;; exceeds the participant's original entitlement.
;;
;; Per participant lineage (all rounds of shared withdrawal for one owner):
;;
;;   original request
;;      │
;;      ├── round-1 realization (filled_1)
;;      └── deferred descendant
;;              │
;;              ├── round-2 realization (filled_2)
;;              └── deferred descendant ...
;;
;; The lineage invariant reconstructs each participant's round records from the
;; committed :yield-withdraw-shared decision artifacts (ordered by committed
;; application step) and proves:
;;
;;   Σ realized-fill across lineage + terminal outstanding = original requested
;;
;; where "original requested" is the uncapped round-1 request (the entitlement
;; deferral preserves; shared pro-rata applies no haircut).  Caps are re-enforced
;; per round by the round-local invariant, so cumulative fill may legitimately
;; exceed the round-1 effective demand (min(request, cap)) but never the original
;; requested amount.  Round-chain continuity additionally requires each
;; re-entered request to equal the prior round's deferred residual.
;;
;; This is deliberately a SEPARATE invariant from
;; check-shared-withdrawal-conservation: round-local verification must not be
;; stretched across both responsibilities.
;; ---------------------------------------------------------------------------

(defn- shared-withdrawal-decisions-in-order
  "All :yield-withdraw-shared decision artifacts in application order.
   Application order is committed per decision; the sort is only for
   deterministic violation output — the lineage chain reconstruction below is
   order-independent (it peels deferred residuals, it does not trust ordering)."
  [world]
  (->> (vals (:yield/partial-fill-decisions world {}))
       (filter shared-withdrawal-artifact?)
       (sort-by (fn [d] [(get-in d [:allocation/invocation-context :step]
                                 Long/MAX_VALUE)
                         (:decision/id d)]))))

(defn- deferred-position-id?
  "Does a :source-position-id look like a deferred-position id rather than a
   base position?  Deferred ids are `<owner>/deferred/<round>/via/<prop-id>`;
   base ids (owner-id for deposits, or a stringified legacy position id) are not."
  [source-position-id]
  (boolean (re-find #"/deferred/" (str source-position-id))))

(defn- shared-round-one-record
  "Identify the round-1 record for a participant's lineage.

   Primary: `:source-position-id = participant` (deposit positions use the
   owner as their base position id).  Fallback for legacy base positions whose
   :position/id is a namespaced vector (e.g. make-position): their base
   source-position-id is a non-owner string, but it is never a deferred-position
   id, so the base record is the one whose source-position-id is not a deferred
   id.  Exactly one base record can exist per participant (deferral re-admits
   later rounds under deferred ids)."
  [participant records]
  (or (first (filter #(= participant (:source-position-id %)) records))
      (first (remove #(deferred-position-id? (:source-position-id %)) records))))

(defn- shared-lineage-chain
  "Peel a participant's deferred-residual chain from the round-1 record.

   Round-1 is identified by `:source-position-id = participant` (the base
   position id; deferred descendants carry a deferred position id instead).
   Each descendant's `:requested` must equal the prior round's `:deferred`
   residual, and every record must be consumed exactly once. Order-independent:
   a descendant that is replayed in two rounds, duplicated, or given an altered
   request breaks the peel instead of passing.

   Returns {:ok? bool :rounds n :terminal-deferred amount :reason kw}."
  [participant round1 records]
  (loop [remaining (remove #(identical? % round1) records)
         current (:deferred round1)
         rounds 1]
    (if (empty? remaining)
      {:ok? true :rounds rounds :terminal-deferred (long current)}
      (let [matches (filter #(= (long current) (long (:requested %))) remaining)]
        (if (= 1 (count matches))
          (recur (remove #(identical? (first matches) %) remaining)
                 (:deferred (first matches))
                 (inc rounds))
          {:ok? false
           :rounds rounds
           :reason (if (empty? matches) :missing-descendant :ambiguous-descendant)
           :expected-requested (long current)
           :matches (count matches)})))))

(defn check-withdrawal-lineage-conservation
  "Cross-round lineage conservation for shared-liquidity withdrawals.

   Reconstructs each participant's round records from the committed shared
   decisions and proves, per lineage:
     Σ realized-fill across rounds + terminal outstanding = original requested
   where `original requested` is the uncapped round-1 request (the entitlement
   deferral preserves; shared pro-rata applies no haircut).  Cumulative fill
   never exceeds that original requested, the round chain is a single
   unambiguous deferred-residual path (each re-entered request equals the prior
   round's deferred residual; no replay, duplication, or altered request), and
   the position's :cumulative-fulfilled and active deferred residual reconcile
   to the decision-derived values.

   Vacuous-safe: a world with no shared-withdrawal decisions holds.

   Returns {:holds? bool :violations [{:kind ...}]}."
  [world]
  (let [positions (:yield/positions world {})
        by-participant
        (reduce
         (fn [acc d]
           (reduce
            (fn [acc row]
              (let [k (:key row)]
                (update acc k (fnil conj [])
                        {:requested (long (:owed row 0))
                         :filled (long (:filled row 0))
                         :deferred (long (:deferred row 0))
                         :source-position-id (:source-position-id row)})))
            acc
            (get-in d [:evidence :allocation-rows] [])))
         {}
         (shared-withdrawal-decisions-in-order world))
        violations
        (vec
         (mapcat
          (fn [[participant records]]
            (let [round1 (shared-round-one-record participant records)
                  original-requested (when round1 (:requested round1))
                  cumulative-filled (reduce + 0 (map :filled records))
                  pos (get positions participant)
                  terminal-outstanding
                  (long (get-in pos [:deferred-position :position/current-amount] 0))
                  chain (when round1
                          (shared-lineage-chain participant round1 records))]
              (let [base (cond-> []
                           (nil? round1)
                           (conj {:kind ::lineage-missing-round-one
                                  :participant participant
                                  :records (count records)}))
                    round1-violations (cond-> base
                                        (and (some? round1)
                                             (not (:ok? chain)))
                                        (conj {:kind ::round-request-chain-mismatch
                                               :participant participant
                                               :reason (:reason chain)
                                               :rounds (:rounds chain)
                                               :expected-requested (:expected-requested chain)
                                               :matches (:matches chain)})

                                        (and (some? round1)
                                             (> cumulative-filled original-requested))
                                        (conj {:kind ::lineage-overfill
                                               :participant participant
                                               :cumulative-filled cumulative-filled
                                               :original-requested original-requested})

                                        (and (some? round1)
                                             (not= (+ cumulative-filled terminal-outstanding)
                                                   original-requested))
                                        (conj {:kind ::lineage-conservation-failed
                                               :participant participant
                                               :cumulative-filled cumulative-filled
                                               :terminal-outstanding terminal-outstanding
                                               :original-requested original-requested})

                                        (and (some? round1)
                                             (:ok? chain)
                                             (not= (:terminal-deferred chain) terminal-outstanding))
                                        (conj {:kind ::lineage-terminal-mismatch
                                               :participant participant
                                               :chain-terminal-deferred (:terminal-deferred chain)
                                               :position-terminal-deferred terminal-outstanding})

                                        (and (some? round1)
                                             pos
                                             (not= (long (:cumulative-fulfilled pos 0))
                                                   cumulative-filled))
                                        (conj {:kind ::position-cumulative-mismatch
                                               :participant participant
                                               :position-cumulative (long (:cumulative-fulfilled pos 0))
                                               :decision-cumulative cumulative-filled}))]
                round1-violations)))
          by-participant))]
    {:holds? (empty? violations)
     :violations violations}))

;; ---------------------------------------------------------------------------
;; Withdrawal settlement — four independent layers
;;
;;   L1 budget provenance       — is the committed liquidity budget a computed
;;                                function of committed world inputs (and of the
;;                                world itself where reconstructable)?
;;   L2 allocation conservation — does the allocator stay within that budget?
;;                                (mode-specific: ledger certificate for
;;                                single/batch, decision artifact for pro-rata)
;;   L3 residual disposition    — is unused budget assigned according to the
;;                                committed decision policy and actually realized?
;;   L4 custody execution       — do custody/propagation mutations realize the
;;                                allocation?  (P2: settlement-scoped held
;;                                adjustment attribution is designed, not yet
;;                                implemented at the Sew layer.)
;;
;; This framing keeps the layers separable: a system can satisfy L2 while
;; completely failing L1 (the budget being conserved may not be real), so the
;; checks must not be collapsed into one "withdrawal conservation" claim.
;; ---------------------------------------------------------------------------

(defn- withdrawal-provenance-artifacts
  "All provenance-bearing withdrawal artifacts in a world: shared decisions and
   single/batch ledger records."
  [world]
  (concat
   (filter #(some? (:liquidity/source-custody %))
           (vals (:yield/partial-fill-decisions world {})))
   (filter #(some? (:liquidity/source-custody %))
           (:yield/withdrawal-ledger world []))))

(defn- world-custody
  "Current custody for a token as the withdrawal modes see it."
  [world token]
  (long (or (get-in world [:total-held token])
            (get-in world [:yield/held-balances token])
            (get-in world [:yield/held-balances
                           (if (keyword? token) (name token) token)])
            0)))

(defn- artifact-filled-total
  "Total filled by the withdrawal an artifact represents (shared decision filled
   map, or ledger rows)."
  [artifact]
  (if (:decision/id artifact)
    (reduce + 0 (vals (:filled artifact {})))
    (reduce + 0 (map #(long (:filled % 0)) (:ledger/rows artifact [])))))

(defn check-withdrawal-budget-provenance
  "L1 budget provenance.  For every provenance-bearing withdrawal artifact
   (shared decision or single/batch ledger record):

     - deterministic recomputation: the committed :liquidity/available must equal
       canonical-available(committed :liquidity/source-custody, committed
       :liquidity/available-ratio), and both committed roots must reconcile
       (::budget-recompute-mismatch / ::source-state-root-mismatch /
       ::market-state-root-mismatch).  The budget is therefore never a bare
       attested scalar — it is a computed function of committed world inputs.

     - world cross-check (shared decisions and batch records, and any record
       whose pre-withdrawal custody reconstructs exactly): reconstruct the
       pre-withdrawal custody as `current custody + filled`; when that equals
       the committed :liquidity/source-custody (i.e. this world is the record's
       evaluation point), the committed :liquidity/available must equal
       canonical-available(reconstructed custody, committed ratio)
       (::world-custody-mismatch).  Records whose reconstruction does not match
       the committed custody are :not-evaluated rather than failed (historical
       worlds), so no false positives.

   Single withdrawals whose base was a custody-scoped slice (not the token pool)
   are covered by the deterministic recomputation layer; their world cross-check
   requires the custody slice at the evaluation point, which the current world
   does not independently expose (documented boundary).

   Returns {:holds? bool :violations [...]}."
  [world]
  (let [violations
        (vec
         (mapcat
          (fn [a]
            (let [artifact-violations (:violations (partial-fill/liquidity-budget-provenance-valid? a))
                  token (:token a)
                  committed-custody (long (:liquidity/source-custody a))
                  committed-available (long (:liquidity/available a))
                  ratio (double (:liquidity/available-ratio a))
                  filled (artifact-filled-total a)
                  reconstructed-pre (+ (world-custody world token) filled)
                  world-violations
                  (when (= committed-custody reconstructed-pre)
                    (let [recomputed (partial-fill/canonical-liquidity-available
                                      reconstructed-pre ratio)]
                      (when (not= committed-available recomputed)
                        [{:kind ::world-custody-mismatch
                          :token token
                          :committed-available committed-available
                          :reconstructed-available recomputed
                          :reconstructed-source-custody reconstructed-pre
                          :available-ratio ratio}])))]
              (into (vec artifact-violations) world-violations)))
          (withdrawal-provenance-artifacts world)))]
    {:holds? (empty? violations)
     :violations violations}))

(defn check-withdrawal-residual-disposition
  "L3 residual disposition.  A shared decision commits its INTENDED residual
   disposition (:residual/destination + a reconciling :residual/policy-root);
   the bound propagation/application proves the ACTUAL disposition (destination
   and amount) against the decision root.

   Violations (namespaced :kind):
     ::residual-destination-missing  — decision lacks :residual/destination.
     ::residual-policy-root-mismatch — committed :residual/policy-root does not
                                       reconcile with the committed destination.
     ::disposition-not-realized      — the application/propagation residual
                                       destination or amount disagrees with the
                                       decision's committed disposition.

   Returns {:holds? bool :violations [...]}."
  [world]
  (let [decisions (filter shared-withdrawal-artifact?
                          (vals (:yield/partial-fill-decisions world {})))
        propagations (get world :yield/pro-rata-propagations {})
        applications (get world :yield/applied-pro-rata-propagations {})
        violations
        (vec
         (mapcat
          (fn [d]
            (let [destination (:residual/destination d)
                  policy-root (:residual/policy-root d)
                  residual (get-in d [:evidence :unallocated-residual])
                  prop (some #(when (= (:decision/id d) (:calculation-ref %)) %)
                             (vals propagations))
                  app (when prop (get applications (:propagation/id prop)))]
              (cond-> []
                (nil? destination)
                (conj {:kind ::residual-destination-missing :decision/id (:decision/id d)})
                (and (some? destination) (some? policy-root)
                     (not= policy-root (partial-fill/residual-policy-root
                                        {:destination destination})))
                (conj {:kind ::residual-policy-root-mismatch :decision/id (:decision/id d)})
                (and prop (not= destination (get-in prop [:residual :destination])))
                (conj {:kind ::disposition-not-realized :decision/id (:decision/id d)
                       :expected destination :observed (get-in prop [:residual :destination])})
                (and prop (some? residual)
                     (not= (long residual) (long (get-in prop [:summary :unallocated-residual] 0))))
                (conj {:kind ::disposition-not-realized :decision/id (:decision/id d)
                       :expected-amount (long residual)
                       :observed-amount (get-in prop [:summary :unallocated-residual])})
                (and app (not= destination (get-in app [:residual :destination])))
                (conj {:kind ::disposition-not-realized :decision/id (:decision/id d)
                       :application-destination (get-in app [:residual :destination])})
                (and app (some? residual)
                     (not= (long residual) (long (get-in app [:residual :amount] -1))))
                (conj {:kind ::disposition-not-realized :decision/id (:decision/id d)
                       :expected-amount (long residual)
                       :application-amount (get-in app [:residual :amount])}))))
          decisions))]
    {:holds? (empty? violations)
     :violations violations}))

(defn- merge-withdrawal-layers
  [results]
  {:holds? (every? :holds? results)
   :violations (vec (mapcat :violations results))})

(defn withdrawal-artifact-mode
  "Committed withdrawal-ALLOCATION mode of an artifact.  Only artifacts that
   govern an allocation carry one:
     - a withdrawal-ledger record  → :ledger/allocation-policy :mode
                                    (:single-position | :fcfs-sequential);
     - a :yield-withdraw-shared decision → :policy :mode (:pro-rata).
   Other partial-fill decisions (e.g. a single withdraw's fill decision with a
   :mode :waterfall FILL policy) are not allocation artifacts and return nil —
   they are not model-verified here.  An allocation artifact missing its tag
   returns ::unknown-mode so it cannot escape model verification."
  [artifact]
  (cond
    (:ledger/kind artifact)
    (or (get-in artifact [:ledger/allocation-policy :mode]) ::unknown-mode)

    (= :yield-withdraw-shared (:decision/source artifact))
    (or (get-in artifact [:policy :mode]) ::unknown-mode)

    :else
    nil))

(defn mode-over-allocation-violations
  "Over-allocation violations for one withdrawal artifact under ITS committed
   mode model.  Each mode's capacity is explicit and verified separately:

     :single-position  — the position's slice: filled ≤ :ledger/available (the
                          committed recoverable slice) and each row's filled ≤
                          requested.
     :fcfs-sequential  — the sequential remaining-pool model: every PREFIX of
                          rows never exceeds the committed pool, and each row's
                          filled ≤ requested.
     :pro-rata         — the proportional + declared-caps model: Σ filled ≤
                          committed available and per-row filled ≤ the declared
                          :allocation/effective-caps.

   Unknown or missing mode tags on a withdrawal-allocation artifact are
   themselves a violation (the artifact cannot be model-verified).  Returns a
   vector of {:kind ::mode-over-allocated ...}."
  [artifact]
  (let [mode (withdrawal-artifact-mode artifact)
        known-mode? (#{:single-position :fcfs-sequential :pro-rata} mode)]
    (if (nil? mode)
      []
      (case (if known-mode? mode ::unknown-mode)
        :single-position
        (let [available (long (:ledger/available artifact 0))
              filled (long (:ledger/filled artifact 0))
              rows (vec (:ledger/rows artifact []))]
          (cond-> []
            (> filled available)
            (conj {:kind ::mode-over-allocated :mode :single-position
                   :filled filled :capacity available})
            (some #(> (long (:filled % 0)) (long (:requested % 0))) rows)
            (conj {:kind ::mode-over-allocated :mode :single-position
                   :reason :row-exceeds-requested})))
        :fcfs-sequential
        (let [available (long (:ledger/available artifact 0))
              rows (vec (:ledger/rows artifact []))
              prefix-over? (boolean (:over? (reduce (fn [{:keys [remaining over?]} row]
                                                      (let [row-filled (long (:filled row 0))
                                                            remaining' (- remaining row-filled)]
                                                        {:remaining remaining'
                                                         :over? (or over? (neg? remaining'))}))
                                                    {:remaining available :over? false}
                                                    rows)))]
          (cond-> []
            prefix-over?
            (conj {:kind ::mode-over-allocated :mode :fcfs-sequential
                   :reason :prefix-exceeds-pool :available available})
            (some #(> (long (:filled % 0)) (long (:requested % 0))) rows)
            (conj {:kind ::mode-over-allocated :mode :fcfs-sequential
                   :reason :row-exceeds-requested})))
        :pro-rata
        (let [rows (vec (get-in artifact [:evidence :allocation-rows] []))
              filled-map (or (:filled artifact) {})
              declared-caps (or (:allocation/effective-caps artifact) {})
              available (long (get-in artifact [:evidence :available-liquidity] 0))]
          (cond-> []
            (> (reduce + 0 (vals filled-map)) available)
            (conj {:kind ::mode-over-allocated :mode :pro-rata
                   :reason :sum-exceeds-available})
            (some (fn [row]
                    (let [k (:key row)
                          cap (get declared-caps k (:effective-cap row))
                          cap (if (some? cap) (long cap) (long (:effective-cap row 0)))]
                      (> (long (:filled row 0)) cap)))
                  rows)
            (conj {:kind ::mode-over-allocated :mode :pro-rata
                   :reason :row-exceeds-declared-cap})))
        ::unknown-mode
        [{:kind ::mode-over-allocated :mode ::unknown-mode
          :reason :missing-mode-tag}]))))

(defn check-withdrawal-allocation-conservation
  "L2 allocation conservation, dispatched by each artifact's committed mode model
   (withdrawal-artifact-mode / mode-over-allocation-violations).  A record tagged
   :fcfs-sequential is verified by the sequential remaining-pool model, a
   :single-position record by the slice model, a :pro-rata decision by the
   proportional + declared-caps model.  The deep arithmetic/certificate layer
   (ledger certificate, shared decision artifact) remains, and every artifact is
   additionally checked against its tagged mode's explicit over-allocation model
   so a missing/mismatched mode tag cannot escape model verification."
  [world]
  (let [artifacts (concat (vals (:yield/partial-fill-decisions world {}))
                          (:yield/withdrawal-ledger world []))
        mode-violations (vec (mapcat mode-over-allocation-violations artifacts))
        baseline (merge-withdrawal-layers
                  [(check-withdrawal-ledger-conservation world)
                   (check-shared-withdrawal-conservation-world world)])]
    {:holds? (and (:holds? baseline) (empty? mode-violations))
     :violations (vec (concat (:violations baseline) mode-violations))}))

(defn check-withdrawal-custody-realization
  "L4 custody execution: the applied shared-withdrawal propagation's source
   debit, participant credits, and balanced ledger realize the committed
   allocation.  This is the yield-layer L4 component.

   The full L4 stack composes at the Sew boundary (a protocol layer that
   depends on yield, not vice versa):
     L4a — global custody correctness: :held-adjustments-reconstruct-total-held
           and :held-adjustments-cover-total-held-delta (Sew world/transition
           invariants).
     L4b — settlement → custody attribution: :settlement-custody-attribution
           (Sew world invariant) proves each withdrawal settlement's held
           adjustments are a committed bijection (existence, binding, no
           double-attribution, exact debit, completeness).
   The Sew check-all runs L4a ∧ L4b alongside this yield-layer component."
  [world]
  (check-pro-rata-accounting-reconciles world))

(defn check-withdrawal-settlement
  "Four-layer withdrawal settlement verification, aggregated:

     L1 check-withdrawal-budget-provenance
     L2 check-withdrawal-allocation-conservation
     L3 check-withdrawal-residual-disposition
     L4 check-withdrawal-custody-realization (yield custody) ∧ Sew L4a (global
        held-ledger) ∧ Sew L4b (:settlement-custody-attribution)

   Returns {:holds? bool :violations [...]} with violations tagged by the
   contributing layer's :kind."
  [world]
  (merge-withdrawal-layers
   [(check-withdrawal-budget-provenance world)
    (check-withdrawal-allocation-conservation world)
    (check-withdrawal-residual-disposition world)
    (check-withdrawal-custody-realization world)]))

(defn check-deferred-reclaim
  "Withdrawn positions: no shortfall; reclaimed ≥ 0."
  [world]
  (let [holds? (every? (fn [pos]
                         (if (= (:status pos) :withdrawn)
                           (and (nil? (:shortfall pos))
                                (>= (long (or (:reclaimed-amount pos) 0)) 0))
                           true))
                       (vals (:yield/positions world {})))]
    {:holds? holds? :violations [] :checks {:deferred-reclaim-valid (if holds? :pass :fail)}}))

(defn check-shortfall-detected
  "Verify shortfall detection correctness:

   1. Over-detection: no position's shortfall basis-amount exceeds its
      total economic value (principal + realized-yield + max(0, unrealized-yield)
      + deferred-amount).
      A basis larger than the position means the shortfall was over-counted.

   2. Under-detection: when a module/token is in shortfall liquidity mode
      with available-ratio < 1.0, any position in :unwinding status that
      has not yet withdrawn must have :shortfall data. If the system is
      processing a withdrawal during shortfall but failed to record it,
      this check catches the gap."
  [world]
  (let [positions (:yield/positions world {})
        holds? (every? (fn [[oid pos]]
                         (let [mid (:module/id pos)
                               tok (:token pos)
                               status (:status pos)
                               sf (:shortfall pos)
                               risk (get-in world [:yield/risk mid tok] {})
                               liquidity-mode (risk/effective-liquidity-mode risk)
                               market-state (get-in world [:yield/market-state mid tok])
                               available-ratio (double (or (:available-ratio market-state) 1.0))
                               principal (long (:principal pos 0))
                               realized (long (:realized-yield pos 0))
                               unrealized (long (:unrealized-yield pos 0))
                               deferred (long (or (get-in pos [:shortfall :deferred-amount]) 0))
                               total-value (+ principal realized (max 0 unrealized) deferred)
                               shortfall-mode? (and (= liquidity-mode :shortfall)
                                                    (< available-ratio 1.0))]
                           (cond
                             ;; Over-detection: shortfall basis must not exceed position value
                             (and sf (pos? (:basis-amount sf 0))
                                  (> (long (:basis-amount sf 0)) total-value))
                             false

                             ;; Under-detection: unwinding during shortfall must have :shortfall
                             (and shortfall-mode?
                                  (#{:unwinding} status)
                                  (nil? sf))
                             false

                             :else true)))
                       positions)]
    {:holds? holds? :violations [] :checks {:shortfall-detected-valid (if holds? :pass :fail)}}))

(defn position-custody-need
  [world pos]
  (let [risk (get-in world [:yield/risk (:module/id pos) (:token pos)] {})
        mtm? (or (= :mark-to-market (risk/effective-loss-mode risk))
                 (neg? (:unrealized-yield pos 0)))]
    (if mtm?
      (max 0 (+ (:principal pos 0) (:unrealized-yield pos 0) (:realized-yield pos 0)))
      (+ (:principal pos 0) (:realized-yield pos 0)))))

(defn check-yield-exposure
  [world live-position-pred held-balance-fn]
  (let [positions (get world :yield/positions {})
        tokens    (into #{} (map :token (vals positions)))
        holds? (every? (fn [token]
                         (let [held (held-balance-fn token)
                               total-needed (reduce (fn [acc [oid pos]]
                                                      (if (and (= (:token pos) token)
                                                               (= (:status pos) :active)
                                                               (live-position-pred oid pos))
                                                        (+ acc (position-custody-need world pos))
                                                        acc))
                                                    0
                                                    positions)]
                           (>= held total-needed)))
                       tokens)]
    {:holds? holds? :violations [] :checks {:exposure-covered (if holds? :pass :fail)}}))

(defn- resolver-owned-position?
  "True when the position belongs to a resolver (backed by resolver-stakes, not total-held)."
  [pos]
  (let [oid (:owner/id pos)]
    (and (vector? oid) (= (first oid) :sew/resolver))))

(defn check-token-key-consistency
  "Reject simultaneous string and keyword keys for one normalized token in
   accounting maps. Mixed representations split balances across independent
   map entries and make custody reconciliation ambiguous."
  [world]
  (let [paths [[:total-held]
               [:total-yield-generated]
               [:yield/held-balances]
               [:token/decimals]
               [:yield/token-decimals]]
        violations
        (vec
         (mapcat
          (fn [path]
            (let [m (get-in world path {})
                  grouped (group-by token-name (keys (if (map? m) m {})))]
              (for [[normalized keys] grouped
                    :when (> (count keys) 1)]
                {:path path :token normalized :keys (vec (sort-by str keys))})))
          paths))]
    {:holds? (empty? violations)
     :violations violations
     :checks {:token-key-representation-consistent (if (seq violations) :fail :pass)}}))

(defn check-provider-exposure
  "Yield exposure invariant: total-held must cover active yield positions.
   Excludes resolver-owned positions since those are backed by resolver-stakes
   (a separate economic layer outside total-held)."
  [world]
  (check-yield-exposure world
                        (fn [_ pos] (and (= (:status pos) :active)
                                         (not (resolver-owned-position? pos))))
                        #(held-for-token world %)))

(defn- application-order-key [order]
  ;; Scenario step is globally monotonic; event sequence breaks ties for
  ;; same-block execution. Optional run/execution/scenario identities scope
  ;; independent replays without determining their internal ordering.
  [(:run-id order) (:execution-id order) (:scenario-id order)
   (:step order) (:event-id order)])

(defn- position-hash
  "Return the canonical position hash used by pro-rata application records.
   Position data may contain ratios, which are normalized at this boundary to
   match the committed application hash format."
  [position]
  (letfn [(walk [value]
            (cond
              (instance? clojure.lang.Ratio value) (long (Math/round (double value)))
              (instance? Double value) (double value)
              (instance? Float value) (double value)
              (map? value) (persistent! (reduce-kv (fn [m k v]
                                                     (assoc! m (walk k) (walk v)))
                                                   (transient {})
                                                   value))
              (vector? value) (mapv walk value)
              (set? value) (set (map walk value))
              :else value))]
    (partial-fill/application-hash
     (if (map? position) (walk position) {:value (walk position)}))))

(defn- position-after-hash-violations
  "Reconcile every committed post-application position hash with its snapshot.
   Only the latest application for a participant/position can additionally
   reconcile to live authoritative state; earlier records are historical."
  [world applications]
  (let [records (mapcat (fn [application]
                          (for [participant (:participants application)
                                :when (:position-after-hash participant)]
                            {:application application :participant participant}))
                        applications)
        position-key (fn [{:keys [participant]}]
                       [(:participant-id participant)
                        (or (:position-id participant) (:participant-id participant))])
        latest-by-position
        (into {}
              (map (fn [[key position-records]]
                     [key
                      (last (sort-by #(application-order-key
                                       (get-in % [:application :application-order]))
                                     position-records))]))
              (group-by position-key records))
        reconcile-record
        (fn [{:keys [application participant] :as record}]
          (let [participant-id (:participant-id participant)
                position-id (or (:position-id participant) participant-id)
                expected (:position-after-hash participant)
                snapshot-hash (position-hash (:position-after participant))
                latest? (= record (get latest-by-position (position-key record)))
                authoritative-position (get-in world [:yield/positions participant-id])
                authoritative-hash (when latest? (position-hash authoritative-position))
                base {:propagation-id (:propagation-id application)
                      :participant-id participant-id
                      :position-id position-id
                      :expected expected}
                classification (assoc base :classification
                                      (if latest?
                                        :live-authoritative
                                        :historical-not-live-reconcilable))
                violations (cond-> []
                             (not= expected snapshot-hash)
                             (conj (assoc base
                                          :reason :position-after-hash-mismatch
                                          :observed snapshot-hash
                                          :source :application-snapshot
                                          :classification :tampered))
                             (and latest? (not= expected authoritative-hash))
                             (conj (assoc base
                                          :reason :position-after-hash-mismatch
                                          :observed authoritative-hash
                                          :source :authoritative-position
                                          :classification :tampered)))]
            {:classification classification :violations violations}))
        reconciliations (mapv reconcile-record records)]
    {:classifications (mapv :classification reconciliations)
     :violations (vec (mapcat :violations reconciliations))}))

(defn chain-violations
  "Test-facing validation of canonical application ordering and account chains."
  [world applications]
  (let [order-valid? (fn [a]
                       (let [order (:application-order a)]
                         (and (#{"pro-rata-application-order.v1" "pro-rata-application-order.v2"} (:schema-version order))
                              (integer? (:step order))
                              (not (neg? (:step order)))
                              (integer? (:event-id order))
                              (not (neg? (:event-id order))))))
        order-violations (for [a applications :when (not (order-valid? a))]
                           {:propagation-id (:propagation-id a) :reason :application-order-missing})
        duplicate-orders (->> applications (group-by #(application-order-key (:application-order %)))
                              vals (filter #(> (count %) 1)))
        duplicate-violations (mapcat (fn [group]
                                       (for [a group] {:propagation-id (:propagation-id a)
                                                       :reason :application-order-duplicate})) duplicate-orders)
        ordered (sort-by #(application-order-key (:application-order %)) applications)
        source-groups (group-by #(get-in % [:source-account :token]) ordered)
        source-violations (mapcat (fn [[token apps]]
                                    (let [links (mapcat (fn [[a b]]
                                                          (when (not= (get-in a [:source-account :after])
                                                                      (get-in b [:source-account :before]))
                                                            [{:propagation-id (:propagation-id b) :token token
                                                              :reason :source-balance-chain-broken}]))
                                                        (partition 2 1 apps))
                                          latest (last apps)
                                          current (get-in world [:total-held token])]
                                      (cond-> (vec links)
                                        (and latest (not= (get-in latest [:source-account :after]) current))
                                        (conj {:propagation-id (:propagation-id latest) :token token
                                               :reason :latest-source-balance-mismatch
                                               :expected (get-in latest [:source-account :after]) :observed current}))))
                                  source-groups)
        participant-records (mapcat (fn [a]
                                      (for [p (:participants a)
                                            :when (pos? (long (get-in p [:withdrawn :delta] 0)))]
                                        (assoc p :application a))) applications)
        participant-groups (group-by (fn [p] [(get-in p [:withdrawn :token]) (:participant-id p)]) participant-records)
        participant-violations (mapcat (fn [[[token participant] ps]]
                                         (let [ps (sort-by #(application-order-key (get-in % [:application :application-order])) ps)]
                                           (let [links (mapcat (fn [[a b]]
                                                                 (when (not= (get-in a [:withdrawn :after])
                                                                             (get-in b [:withdrawn :before]))
                                                                   [{:propagation-id (get-in b [:application :propagation-id])
                                                                     :token token :participant-id participant
                                                                     :reason :participant-balance-chain-broken}]))
                                                               (partition 2 1 ps))
                                                 latest (last ps)
                                                 current (get-in world [:yield/withdrawn token participant])]
                                             (cond-> (vec links)
                                               (and latest (not= (get-in latest [:withdrawn :after]) current))
                                               (conj {:propagation-id (get-in latest [:application :propagation-id])
                                                      :token token :participant-id participant
                                                      :reason :latest-authoritative-withdrawn-balance-mismatch
                                                      :expected (get-in latest [:withdrawn :after]) :observed current})))))
                                       participant-groups)]
    (vec (concat order-violations duplicate-violations source-violations participant-violations))))

(defn exact-credit-violations
  "Test-facing, duplicate-preserving exact reconciliation of propagated
   fulfilments and token-scoped withdrawn accounting credits."
  [propagations]
  (mapcat (fn [p]
            (let [id (:propagation/id p) token (:token p)
                  participants (:participants p)
                  credits (vec (filter #(and (= :credit (:entry/type %)) (= :withdrawn (:account %)))
                                       (:accounting-entries p)))
                  amount? #(and (integer? %) (not (neg? %)))
                  key-of #(vector token (:participant-id %) (get-in % [:origin :obligation-id]))
                  expected-keys (map key-of participants)
                  duplicate-expected (for [[k rows] (group-by identity expected-keys) :when (> (count rows) 1)]
                                       {:propagation-id id :token token :reason :duplicate-propagation-participant :key k})
                  participant-errors (mapcat (fn [participant]
                                               (let [amount (:fulfilled participant)
                                                     obligation (get-in participant [:origin :obligation-id])
                                                     key (key-of participant)
                                                     matches (filter #(= key [(:token %) (:participant-id %) (:obligation-id %)]) credits)
                                                     near-token (filter #(= [(:participant-id participant) obligation]
                                                                            [(:participant-id %) (:obligation-id %)]) credits)
                                                     near-owner (filter #(= [token obligation] [(:token %) (:obligation-id %)]) credits)
                                                     near-obligation (filter #(= [token (:participant-id participant)]
                                                                                 [(:token %) (:participant-id %)]) credits)]
                                                 (cond-> []
                                                   (nil? obligation) (conj {:propagation-id id :participant-id (:participant-id participant) :reason :participant-obligation-id-missing})
                                                   (not (amount? amount)) (conj {:propagation-id id :participant-id (:participant-id participant) :reason :invalid-participant-fulfilled-amount})
                                                   (and (amount? amount) (pos? amount) (empty? matches) (seq near-token)) (conj {:propagation-id id :participant-id (:participant-id participant) :reason :participant-credit-token-mismatch})
                                                   (and (amount? amount) (pos? amount) (empty? matches) (seq near-owner)) (conj {:propagation-id id :participant-id (:participant-id participant) :reason :participant-credit-owner-mismatch})
                                                   (and (amount? amount) (pos? amount) (empty? matches) (seq near-obligation)) (conj {:propagation-id id :participant-id (:participant-id participant) :reason :participant-credit-obligation-mismatch})
                                                   (and (amount? amount) (pos? amount) (empty? matches) (empty? near-token) (empty? near-owner) (empty? near-obligation)) (conj {:propagation-id id :participant-id (:participant-id participant) :reason :participant-credit-missing})
                                                   (and (amount? amount) (pos? amount) (> (count matches) 1)) (conj {:propagation-id id :participant-id (:participant-id participant) :reason :participant-credit-duplicate})
                                                   (and (amount? amount) (pos? amount) (= 1 (count matches)) (not (amount? (:delta (first matches))))) (conj {:propagation-id id :participant-id (:participant-id participant) :reason :invalid-participant-credit-amount})
                                                   (and (amount? amount) (pos? amount) (= 1 (count matches)) (amount? (:delta (first matches))) (not= amount (:delta (first matches)))) (conj {:propagation-id id :participant-id (:participant-id participant) :reason :participant-credit-mismatch})
                                                   (and (amount? amount) (zero? amount) (seq matches)) (conj {:propagation-id id :participant-id (:participant-id participant) :reason :unexpected-zero-fulfilment-credit})))) participants)
                  orphan-errors (for [credit credits
                                      :when (or (nil? (:obligation-id credit))
                                                (not (some #(= [(:token credit) (:participant-id credit) (:obligation-id credit)] %) expected-keys)))]
                                  {:propagation-id id :participant-id (:participant-id credit) :token (:token credit)
                                   :reason (if (nil? (:obligation-id credit)) :credit-obligation-id-missing :orphan-participant-credit)})]
              (concat duplicate-expected participant-errors orphan-errors))) propagations))

(defn closed-history-violations
  "Require immutable history when an application consumes an active deferred position."
  [applications]
  (mapcat
   (fn [application]
     (mapcat
      (fn [participant]
        (let [before (:position-before participant)
              after (:position-after participant)
              prior (:deferred-position before)
              record (get (:deferred-position-history after) (:position/id prior))
              base {:propagation-id (:propagation-id application)
                    :participant-id (:participant-id participant)}]
          (cond-> []
            (and prior (nil? record))
            (conj (assoc base :reason :closed-position-history-missing))
            (and record (not= :closed (:position/status record)))
            (conj (assoc base :reason :closed-position-history-closure-mismatch))
            (and record (not= (:position/root-obligation-id prior)
                              (:position/root-obligation-id record)))
            (conj (assoc base :reason :closed-position-history-identity-mismatch))
            (and record (not= (:propagation-id application)
                              (:position/closed-by-propagation-id record)))
            (conj (assoc base :reason :closed-position-history-closure-mismatch))
            (and record (not= (:token before) (:position/token record)))
            (conj (assoc base :reason :closed-position-history-identity-mismatch))
            (and record (not= (:participant-id participant)
                              (:position/participant-id record)))
            (conj (assoc base :reason :closed-position-history-identity-mismatch)))))
      (:participants application)))
   applications))

(defn application-obligation-violations
  "Match propagation participants to application-v2 participant snapshots exactly."
  [propagations applications]
  (mapcat
   (fn [propagation]
     (let [application (some #(when (= (:propagation-id %) (:propagation/id propagation)) %) applications)
           token (:token propagation)]
       (mapcat
        (fn [participant]
          (let [participant-id (:participant-id participant)
                obligation-id (get-in participant [:origin :obligation-id])
                application-participants (:participants application)
                matches (filter #(= [token participant-id obligation-id]
                                    [(get-in % [:withdrawn :token]) (:participant-id %) (:obligation-id %)])
                                application-participants)
                token-near (filter #(= [participant-id obligation-id]
                                       [(:participant-id %) (:obligation-id %)])
                                   application-participants)
                participant-near (filter #(= [token obligation-id]
                                             [(get-in % [:withdrawn :token]) (:obligation-id %)])
                                         application-participants)
                obligation-near (filter #(= [token participant-id]
                                            [(get-in % [:withdrawn :token]) (:participant-id %)])
                                        application-participants)
                base {:propagation-id (:propagation/id propagation)
                      :participant-id participant-id
                      :token token
                      :obligation-id obligation-id}]
            (cond
              (nil? application)
              [(assoc base :reason :application-participant-record-missing)]

              (> (count matches) 1)
              [(assoc base :reason :application-participant-record-duplicate)]

              (seq matches)
              (let [snapshot (first matches)
                    withdrawn (:withdrawn snapshot)
                    before (:before withdrawn)
                    delta (:delta withdrawn)
                    after (:after withdrawn)
                    fulfilled (:fulfilled participant)]
                (cond
                  (not (and (integer? before) (integer? delta) (integer? after)))
                  [(assoc base :reason :participant-withdrawn-arithmetic-failed
                          :expected :non-negative-integer-snapshot
                          :observed withdrawn)]

                  (not= (+ before delta) after)
                  [(assoc base :reason :participant-withdrawn-arithmetic-failed
                          :expected (+ before delta)
                          :observed after)]

                  (not= fulfilled delta)
                  [(assoc base :reason :application-withdrawn-delta-mismatch
                          :expected fulfilled
                          :observed delta)]

                  :else []))

              (seq token-near)
              [(assoc base :reason :application-obligation-token-mismatch
                      :expected token
                      :observed (get-in (first token-near) [:withdrawn :token]))]

              (seq participant-near)
              [(assoc base :reason :application-obligation-participant-mismatch
                      :expected participant-id
                      :observed (:participant-id (first participant-near)))]

              (seq obligation-near)
              [(assoc base :reason :application-obligation-id-mismatch
                      :expected obligation-id
                      :observed (:obligation-id (first obligation-near)))]

              :else
              [(assoc base :reason :application-participant-record-missing)])))
        (:participants propagation))))
   propagations))

(defn cumulative-fulfilment-violations
  "Validate immutable application cumulative-fulfilment snapshots."
  [applications]
  (mapcat (fn [application]
            (mapcat (fn [participant]
                      (let [c (:cumulative-fulfilled participant)
                            obligation-before (long (get-in participant [:obligation :before] 0))
                            original-obligation (long (or (get-in participant [:position-before :deferred-position :position/original-obligation])
                                                          obligation-before))
                            before (:before c) delta (:delta c) after (:after c)]
                        (cond-> []
                          (not (and (integer? before) (integer? delta) (integer? after)))
                          (conj {:propagation-id (:propagation-id application) :participant-id (:participant-id participant)
                                 :reason :cumulative-fulfilment-arithmetic-failed})
                          (and (integer? before) (integer? delta) (integer? after) (not= (+ before delta) after))
                          (conj {:propagation-id (:propagation-id application) :participant-id (:participant-id participant)
                                 :reason :cumulative-fulfilment-arithmetic-failed})
                          (and (integer? after) (> after original-obligation))
                          (conj {:propagation-id (:propagation-id application) :participant-id (:participant-id participant)
                                 :reason :cumulative-fulfilment-exceeded})))) (:participants application))) applications))

(defn obligation-violations
  "Test-facing participant obligation conservation for shared withdrawals."
  [propagations]
  (mapcat (fn [p]
            (mapcat (fn [participant]
                      (let [fields [:eligible-obligation :fulfilled :deferred :unmet :waived :obligation-after]
                            valid? #(and (integer? (get participant %)) (not (neg? (get participant %))))
                            malformed (seq (remove valid? fields))
                            before (:eligible-obligation participant)
                            fulfilled (:fulfilled participant)
                            deferred (:deferred participant)
                            unmet (:unmet participant)
                            waived (:waived participant)
                            after (:obligation-after participant)
                            base {:propagation-id (:propagation/id p) :participant-id (:participant-id participant)
                                  :token (:token p) :obligation-id (get-in participant [:origin :obligation-id])}]
                        (cond-> []
                          (nil? (:obligation-id base)) (conj (assoc base :reason :participant-obligation-id-missing))
                          malformed (conj (assoc base :reason :invalid-obligation-amount :fields (vec malformed)))
                          (and (empty? malformed) (not= before (+ fulfilled deferred unmet waived))) (conj (assoc base :reason :obligation-conservation-failed))
                          (and (empty? malformed) (pos? unmet)) (conj (assoc base :reason :unsupported-unmet-withdrawal))
                          (and (empty? malformed) (pos? waived)) (conj (assoc base :reason :unsupported-waived-withdrawal))
                          (and (empty? malformed) (not= deferred after)) (conj (assoc base :reason :obligation-after-mismatch)))))
                    (:participants p))) propagations))

(defn deferred-state-violations
  "Test-facing reconciliation of propagated residuals and active deferred state."
  [world propagations]
  (mapcat (fn [p]
            (mapcat (fn [participant]
                      (let [id (:participant-id participant)
                            deferred (long (:deferred participant 0))
                            position (get-in world [:yield/positions id])
                            active (:deferred-position position)
                            superseded? (or
                                         (and active
                                              (> (long (:position/round active 0)) 1)
                                              (= (get-in participant [:origin :obligation-id])
                                                 (:position/root-obligation-id active))
                                              (not= (:propagation/id p)
                                                    (:position/origin-propagation-id active)))
                                         (some #(= (get-in participant [:origin :obligation-id])
                                                   (:position/root-obligation-id %))
                                               (vals (:deferred-position-history position))))]
                        (cond-> []
                          (and (pos? deferred) (not superseded?) (nil? active))
                          (conj {:propagation-id (:propagation/id p) :participant-id id :reason :deferred-position-missing})
                          (and (pos? deferred) (not superseded?) active (not= deferred (:position/current-amount active)))
                          (conj {:propagation-id (:propagation/id p) :participant-id id :reason :deferred-position-amount-mismatch})
                          (and (pos? deferred) (not superseded?) active (not= (:token p) (:token position)))
                          (conj {:propagation-id (:propagation/id p) :participant-id id :reason :deferred-position-token-mismatch})
                          (and (pos? deferred) (not superseded?) active (not= (get-in participant [:origin :obligation-id]) (:position/root-obligation-id active)))
                          (conj {:propagation-id (:propagation/id p) :participant-id id :reason :deferred-position-root-obligation-mismatch})
                          (and (pos? deferred) (not superseded?) active (not= (:propagation/id p) (:position/origin-propagation-id active)))
                          (conj {:propagation-id (:propagation/id p) :participant-id id :reason :deferred-position-origin-mismatch})
                          (and (zero? deferred) active)
                          (conj {:propagation-id (:propagation/id p) :participant-id id :reason :fulfilled-position-still-active}))))
                    (:participants p))) propagations))

(defn- committed-propagation-policy
  "Return the propagation's verified policy snapshot, never a registry fallback."
  [propagation]
  (let [reference (:propagation-policy propagation)
        snapshot (:policy/snapshot reference)]
    (cond
      (not (map? reference)) {:violations [{:propagation-id (:propagation/id propagation)
                                            :reason :propagation-policy-missing}]}
      (not (map? snapshot)) {:violations [{:propagation-id (:propagation/id propagation)
                                           :reason :propagation-policy-missing}]}
      (not= "pro-rata-propagation-policy.v1" (:schema-version snapshot))
      {:violations [{:propagation-id (:propagation/id propagation)
                     :reason :unsupported-propagation-policy-schema}]}
      (not= (select-keys reference [:schema-version :policy/id :policy/version])
            (select-keys snapshot [:schema-version :policy/id :policy/version]))
      {:violations [{:propagation-id (:propagation/id propagation)
                     :reason :propagation-policy-reference-mismatch}]}
      :else
      (let [policy (assoc snapshot :policy/hash (:policy/hash reference))]
        (try
          (propagation-policy/verify-policy-hash policy)
          {:policy policy}
          (catch clojure.lang.ExceptionInfo error
            {:violations [{:propagation-id (:propagation/id propagation)
                           :reason (case (:reason (ex-data error))
                                     :policy-hash-mismatch :propagation-policy-hash-mismatch
                                     :unsupported-policy-schema :unsupported-propagation-policy-schema
                                     :unsupported-propagation-policy)}]})
          (catch Exception _
            {:violations [{:propagation-id (:propagation/id propagation)
                           :reason :unsupported-propagation-policy}]}))))))

(defn policy-accounting-violations
  "Pure accounting-policy compliance check using only the policy snapshot
   committed by `propagation`. Invalid policy evidence fails closed."
  [propagation application]
  (let [{:keys [policy violations]} (committed-propagation-policy propagation)]
    (if (seq violations)
      violations
      (let [entries (:accounting-entries propagation)
            token (:token propagation)
            allocated (long (get-in propagation [:summary :allocated] 0))
            available (long (get-in propagation [:summary :available] 0))
            residual (long (get-in propagation [:summary :unallocated-residual] 0))
            contract (:accounting-contract policy)
            source-account (:source-account contract)
            participant-account (:participant-credit-account contract)
            residual-destination (get-in policy [:residual-liquidity :destination])
            shortfall-classification (get-in policy [:shortfall :classification])
            components (get-in policy [:idempotency :identity-components])
            component-values {:calculation-id (:calculation-ref propagation)
                              :outcome-hash (:outcome-ref propagation)
                              :policy-hash (:policy/hash policy)}
            missing-components (seq (filter #(nil? (get component-values %)) components))
            expected-key (into [:pro-rata-propagation] (map component-values components))
            source (:source-account application)
            credits (filter #(= :credit (:entry/type %)) entries)
            debits (filter #(= :debit (:entry/type %)) entries)
            app-residual (:residual application)
            base {:propagation-id (:propagation/id propagation) :token token}]
        (cond-> []
          (not= [:calculation-id :outcome-hash :policy-hash] components)
          (conj (assoc base :reason :unsupported-policy-idempotency-contract))
          missing-components
          (conj (assoc base :reason :policy-idempotency-component-missing
                       :observed (vec missing-components)))
          (and application (empty? missing-components) (= [:calculation-id :outcome-hash :policy-hash] components)
               (not= expected-key (:application-key application)))
          (conj (assoc base :reason :application-key-policy-mismatch
                       :expected expected-key :observed (:application-key application)))
          (and application (not= source-account (:account source)))
          (conj (assoc base :reason :application-source-account-policy-mismatch
                       :expected source-account :observed (:account source)))
          (some #(not= source-account (:account %)) debits)
          (conj (assoc base :reason :source-entry-account-policy-mismatch))
          (some #(not= participant-account (:account %)) credits)
          (conj (assoc base :reason :participant-credit-account-policy-mismatch))
          (some #(not= token (:token %)) (concat debits credits))
          (conj (assoc base :reason :participant-token-mismatch))
          (and application (not= token (:token source)))
          (conj (assoc base :reason :source-token-mismatch))
          (and application (not= token (:token app-residual)))
          (conj (assoc base :reason :residual-token-mismatch))
          (not= available (+ allocated residual))
          (conj (assoc base :reason :available-allocation-residual-mismatch))
          (and (= :deferred shortfall-classification)
               (some #(or (pos? (long (:unmet % 0)))
                          (pos? (long (:waived % 0)))
                          (not= (:deferred %) (:obligation-after %)))
                     (:participants propagation)))
          (conj (assoc base :reason :shortfall-classification-policy-mismatch))
          (and application (not= residual-destination (:destination app-residual)))
          (conj (assoc base :reason :residual-destination-policy-mismatch
                       :expected residual-destination :observed (:destination app-residual)))
          (and application (not= (- allocated) (:delta source)))
          (conj (assoc base :reason :source-debit-violates-residual-policy))
          (some #(and (= :credit (:entry/type %))
                      (not= participant-account (:account %))) entries)
          (conj (assoc base :reason :unexpected-residual-refund)))))))

(defn policy-deferred-state-violations
  "Compare authoritative active deferred positions to each propagation's
   verified policy snapshot."
  [world propagation]
  (let [{:keys [policy violations]} (committed-propagation-policy propagation)]
    (if (seq violations)
      []
      (let [expected-type (get-in policy [:accounting-contract :deferred-position-account])
            expected-eligibility (get-in policy [:shortfall :next-position/eligibility])]
        (mapcat (fn [participant]
                  (let [deferred (long (:deferred participant 0))
                        active (get-in world [:yield/positions (:participant-id participant) :deferred-position])
                        base {:propagation-id (:propagation/id propagation)
                              :participant-id (:participant-id participant)
                              :token (:token propagation)
                              :obligation-id (get-in participant [:origin :obligation-id])}]
                    (cond-> []
                      (and (pos? deferred) active (not= expected-type (:position/type active)))
                      (conj (assoc base :reason :deferred-position-policy-mismatch
                                   :expected expected-type :observed (:position/type active)))
                      (and (pos? deferred) active (not= expected-eligibility (:position/eligibility active)))
                      (conj (assoc base :reason :deferred-position-policy-mismatch
                                   :expected expected-eligibility :observed (:position/eligibility active))))))
                (:participants propagation))))))

(defn- deferred-deadline-violations
  "Check that no active deferred position is past its policy deadline."
  [world]
  (let [now-ts (or (some-> (get-in world [:context/time :block-ts]) long) (some-> (get-in world [:block-time]) long) 0)]

    (into []
          (keep (fn [[pid pos]]
                  (when-let [dp (:deferred-position pos)]
                    (when (and (= :active (:position/status dp))
                               (some? (:position/deadline-ts dp))
                               (dl/deadline-expired? now-ts (:position/deadline-ts dp)))
                      {:participant-id pid
                       :reason :deferred-position-deadline-expired
                       :deadline-ts (:position/deadline-ts dp)
                       :current-ts now-ts}))))
          (:yield/positions world {}))))

(def ^:private accounting-category-checks
  "Mapping from categories to the set of detailed check keys they summarise."
  {:account-classes-valid
   [:deferred-position-policy-valid
    :policy-accounting-contract-supported
    :source-account-policy-compliant
    :participant-account-policy-compliant
    :shortfall-policy-compliant
    :token-policy-compliant
    :account-classes-valid
    :source-token-consistent]

   :application-binding-valid
   [:application-record-present
    :allocation-decision-binding-valid
    :allocation-row-translation-valid
    :application-binding-valid
    :application-participant-set-valid
    :application-obligation-identities-valid]

   :accounting-entries-complete
   [:participant-credit-total-valid
    :participant-credit-keys-complete
    :participant-credits-match-individually
    :participant-credit-set-exact
    :entry-set-balanced]

   :accounting-state-reconciles
   [:source-account-arithmetic-valid
    :participant-withdrawn-arithmetic
    :deferred-position-presence-valid
    :deferred-position-amounts-valid
    :deferred-position-identities-valid
    :deferred-position-deadline-valid
    :obligation-identities-valid
    :obligation-conservation
    :unsupported-obligation-outcomes-absent
    :obligation-after-valid
    :residual-policy-compliant
    :available-allocation-residual
    :residual-retained-in-pool]

   :chain-continuity-valid
   [:application-order-valid
    :source-balance-chain-valid
    :participant-balance-chain-valid
    :cumulative-fulfilment-valid
    :closed-position-history-valid]

   :artifact-integrity-valid
   [:accounting-entry-set-hash-consistent
    :policy-reference-valid
    :idempotency-policy-compliant
    :application-output-schema-valid
    :application-output-hash-valid
    :position-after-hash-valid]})

(defn check-pro-rata-accounting-reconciles
  "Reconcile each persisted propagation with its committed application snapshot."
  [world]
  (let [props (vals (:yield/pro-rata-propagations world {}))
        applications (vals (:yield/applied-pro-rata-propagations world {}))
        position-after-reconciliation (position-after-hash-violations world applications)
        failures (concat (mapcat (fn [p]
                                   (let [id (:propagation/id p)
                                         a (get-in world [:yield/applied-pro-rata-propagations id])
                                         allocated (long (get-in p [:summary :allocated] 0))
                                         available (long (get-in p [:summary :available] 0))
                                         residual (long (get-in p [:summary :unallocated-residual] 0))
                                         source (:source-account a)
                                         application-residual (:residual a)
                                         accounting-contract (get-in p [:propagation-policy :policy/snapshot :accounting-contract])
                                         participants (:participants p)
                                         apps (:participants a)
                                         entries (:accounting-entries p)
                                         entry-hash (partial-fill/accounting-entry-set-hash entries)
                                         debit (filter #(and (= :debit (:entry/type %)) (= (:source-account accounting-contract) (:account %))) entries)
                                         participant-credit-entries (filter #(= :credit (:entry/type %)) entries)
                                         credits (filter #(and (= :credit (:entry/type %)) (= (:participant-credit-account accounting-contract) (:account %))) entries)
                                         credit-errors (mapcat (fn [participant]
                                                                 (let [fulfilled (long (:fulfilled participant 0))
                                                                       key [(:token p) (:participant-id participant) (get-in participant [:origin :obligation-id])]
                                                                       matching (filter #(= key [(:token %) (:participant-id %) (:obligation-id %)]) credits)]
                                                                   (cond-> []
                                                                     (and (pos? fulfilled) (empty? matching)) (conj {:propagation-id id :participant-id (:participant-id participant) :reason :participant-credit-missing})
                                                                     (and (pos? fulfilled) (> (count matching) 1)) (conj {:propagation-id id :participant-id (:participant-id participant) :reason :participant-credit-duplicate})
                                                                     (and (pos? fulfilled) (= 1 (count matching)) (not= fulfilled (long (:delta (first matching) 0)))) (conj {:propagation-id id :participant-id (:participant-id participant) :reason :participant-credit-mismatch})
                                                                     (and (zero? fulfilled) (seq matching)) (conj {:propagation-id id :participant-id (:participant-id participant) :reason :unexpected-zero-fulfilment-credit}))))
                                                               participants)]
                                     (cond-> []
                                       (nil? a) (conj {:propagation-id id :reason :missing-propagation-application})
                                       (and a (not (#{"pro-rata-propagation-application.v2" "pro-rata-propagation-application.v3"} (:schema-version a)))) (conj {:propagation-id id :reason :unsupported-application-schema})
                                       (and a (nil? (:application/hash a))) (conj {:propagation-id id :reason :application-hash-missing})
                                       (and a (:application/hash a)
                                            (not= (:application/hash a)
                                                  (partial-fill/application-hash a)))
                                       (conj {:propagation-id id :reason :application-hash-mismatch})
                                       (and a (:application/output a)
                                            (not= "pro-rata-application-output.v1" (get-in a [:application/output :schema-version])))
                                       (conj {:propagation-id id :reason :application-output-schema-invalid})
                                       (and a (:application/output a) (:hash (:application/output a))
                                            (not= (:hash (:application/output a))
                                                  (partial-fill/pro-rata-application-output-hash a p)))
                                       (conj {:propagation-id id :reason :application-output-hash-mismatch})
                                       (and a (let [expected-ref {:propagation/id id :propagation/hash (:propagation/hash p) :propagation/content-hash (:propagation/content-hash p)}
                                                    actual-ref (:propagation/reference a)]
                                                (not= expected-ref actual-ref))) (conj {:propagation-id id :reason :application-propagation-reference-mismatch})
                                       (and a (not= (:allocation/invocation-context p)
                                                    (:allocation/invocation-context a)))
                                       (conj {:propagation-id id :reason :application-invocation-context-mismatch})
                                       (and a (not= (:calculation-ref p) (:calculation-id a))) (conj {:propagation-id id :reason :application-calculation-id-mismatch})
                                       (and a (not= (:outcome-ref p) (:outcome-hash a))) (conj {:propagation-id id :reason :application-outcome-hash-mismatch})
                                       (and a (not= (get-in p [:propagation-policy :policy/hash]) (:policy-hash a))) (conj {:propagation-id id :reason :application-policy-hash-mismatch})
                                       (not= entry-hash (:accounting-entry-set-hash p)) (conj {:propagation-id id :reason :propagation-accounting-entry-hash-mismatch})
                                       (and a (not= entry-hash (:accounting-entry-set-hash a))) (conj {:propagation-id id :reason :application-accounting-entry-hash-mismatch})
                                       (and a (not= allocated (- (long (:before source 0)) (long (:after source 0))))) (conj {:propagation-id id :reason :source-account-arithmetic-failed})
                                       (and a (not= (- allocated) (long (:delta source 0)))) (conj {:propagation-id id :reason :source-debit-mismatch})
                                       (and a (not= (:source-account accounting-contract) (:account source)))
                                       (conj {:propagation-id id :reason :source-account-policy-mismatch
                                              :expected (:source-account accounting-contract) :observed (:account source)})
                                       (and a (not= (:token p) (:token source)))
                                       (conj {:propagation-id id :reason :source-token-mismatch
                                              :expected (:token p) :observed (:token source)})
                                       (some #(not= (:source-account accounting-contract) (:account %)) debit)
                                       (conj {:propagation-id id :reason :source-account-policy-mismatch})
                                       (some #(not= (:token p) (:token %)) debit)
                                       (conj {:propagation-id id :reason :source-token-mismatch})
                                       (some #(not= (:participant-credit-account accounting-contract) (:account %)) participant-credit-entries)
                                       (conj {:propagation-id id :reason :participant-account-policy-mismatch})
                                       (not= available (+ allocated residual)) (conj {:propagation-id id :reason :available-allocation-residual-mismatch
                                                                                      :expected available :observed (+ allocated residual)})
                                       (and a (not= (:token p) (:token application-residual))) (conj {:propagation-id id :reason :residual-token-mismatch
                                                                                                      :expected (:token p) :observed (:token application-residual)})
                                       (and a (not= {:token (:token p) :available available :allocated allocated :amount residual
                                                     :destination (get-in p [:residual :destination])}
                                                    (select-keys application-residual [:token :available :allocated :amount :destination])))
                                       (conj {:propagation-id id :reason :residual-record-mismatch})
                                ;; Zero-allocation propagations have no material financial
                               ;; source debit; canonical entry normalization omits it.
                                       (and (pos? allocated) (not= 1 (count debit))) (conj {:propagation-id id :reason :source-account-entry-missing})
                                       (not= 0 (reduce + 0 (map #(long (:delta % 0)) entries))) (conj {:propagation-id id :reason :accounting-entry-set-unbalanced})
                                       (and a (not= (set (map :participant-id participants)) (set (map :participant-id apps)))) (conj {:propagation-id id :reason :application-participant-set-mismatch})
                                       (and a (not= allocated (reduce + 0 (map #(long (get-in % [:withdrawn :delta] 0)) apps)))) (conj {:propagation-id id :reason :participant-credit-total-mismatch})
                                       (and a (not= allocated (reduce + 0 (map #(long (:delta % 0)) credits)))) (conj {:propagation-id id :reason :participant-credit-total-mismatch}))))
                                 props)
                         (mapcat #(policy-accounting-violations % (get-in world [:yield/applied-pro-rata-propagations (:propagation/id %)])) props)
                         ;; Application/accounting evidence is only meaningful when the
                         ;; committed v2 propagation still faithfully binds its decision.
                         (mapcat (fn [p]
                                   (if (= "pro-rata-propagation.v2" (:schema-version p))
                                     (if-let [decision (get-in world [:yield/partial-fill-decisions (:calculation-ref p)])]
                                       (partial-fill/propagation-allocation-binding-violations decision p)
                                       [{:reason :propagation-decision-reference-mismatch
                                         :propagation-id (:propagation/id p)}])
                                     []))
                                 props)
                         (mapcat #(policy-deferred-state-violations world %) props)
                         (chain-violations world applications)
                         (exact-credit-violations props)
                         (deferred-state-violations world props)
                         (obligation-violations props)
                         (cumulative-fulfilment-violations applications)
                         (application-obligation-violations props applications)
                         (closed-history-violations applications)
                         (:violations position-after-reconciliation)
                         (deferred-deadline-violations world))]

    (let [failures (vec failures)
          reasons (set (map :reason failures))
          pass? (fn [reason-set] (if (empty? (clojure.set/intersection reasons reason-set)) :pass :fail))

          checks
          {:application-record-present (pass? #{:missing-propagation-application})
           :allocation-decision-binding-valid (pass? #{:propagation-allocation-id-mismatch
                                                       :propagation-allocation-hash-mismatch
                                                       :propagation-mechanism-reference-mismatch
                                                       :propagation-mechanism-evidence-reference-mismatch
                                                       :decision-mechanism-evidence-invalid
                                                       :propagation-decision-reference-mismatch
                                                       :decision-hash-mismatch})
           :allocation-row-translation-valid
           (pass? #{:missing-propagation-participant
                    :extra-propagation-participant
                    :duplicate-propagation-participant
                    :duplicate-decision-allocation-row
                    :propagated-fulfilled-mismatch
                    :propagated-unmet-mismatch
                    :propagation-fulfilled-total-mismatch
                    :propagation-unmet-total-mismatch})
           :application-order-valid (pass? #{:application-order-missing :application-order-duplicate})
           :application-binding-valid
           (pass? #{:unsupported-application-schema
                    :application-hash-missing
                    :application-hash-mismatch
                    :application-propagation-reference-mismatch
                    :application-invocation-context-mismatch
                    :application-calculation-id-mismatch
                    :application-outcome-hash-mismatch
                    :application-policy-hash-mismatch})
           :application-participant-set-valid (pass? #{:application-participant-set-mismatch})
           :participant-credit-total-valid (pass? #{:participant-credit-total-mismatch})
           :source-account-arithmetic-valid
           (pass? #{:source-account-arithmetic-failed
                    :source-debit-mismatch
                    :source-account-entry-missing})
           :accounting-entry-set-hash-consistent
           (pass? #{:propagation-accounting-entry-hash-mismatch
                    :application-accounting-entry-hash-mismatch})
           :source-balance-chain-valid (pass? #{:source-balance-chain-broken :latest-source-balance-mismatch})
           :participant-withdrawn-arithmetic (pass? #{:participant-withdrawn-arithmetic-failed :application-withdrawn-delta-mismatch})
           :participant-balance-chain-valid (pass? #{:participant-balance-chain-broken :latest-authoritative-withdrawn-balance-mismatch})
           :participant-credit-keys-complete (pass? #{:participant-obligation-id-missing :credit-obligation-id-missing :duplicate-propagation-participant})
           :participant-credits-match-individually (pass? #{:participant-credit-missing :participant-credit-duplicate :participant-credit-mismatch :participant-credit-token-mismatch :participant-credit-owner-mismatch :participant-credit-obligation-mismatch})
           :participant-credit-set-exact (pass? #{:orphan-participant-credit :unexpected-zero-fulfilment-credit})
           :deferred-position-presence-valid (pass? #{:deferred-position-missing :fulfilled-position-still-active})
           :deferred-position-amounts-valid (pass? #{:deferred-position-amount-mismatch})
           :deferred-position-identities-valid (pass? #{:deferred-position-token-mismatch :deferred-position-root-obligation-mismatch :deferred-position-origin-mismatch})
           :deferred-position-policy-valid (pass? #{:deferred-position-policy-mismatch})
           :obligation-identities-valid (pass? #{:participant-obligation-id-missing})
           :application-obligation-identities-valid (pass? #{:application-participant-record-missing :application-participant-record-duplicate :application-obligation-id-mismatch :application-obligation-token-mismatch :application-obligation-participant-mismatch})
           :obligation-conservation (pass? #{:obligation-conservation-failed :invalid-obligation-amount})
           :unsupported-obligation-outcomes-absent (pass? #{:unsupported-unmet-withdrawal :unsupported-waived-withdrawal})
           :obligation-after-valid (pass? #{:obligation-after-mismatch})
           :cumulative-fulfilment-valid (pass? #{:cumulative-fulfilment-arithmetic-failed :cumulative-fulfilment-exceeded})
           :closed-position-history-valid (pass? #{:closed-position-history-missing :closed-position-history-identity-mismatch :closed-position-history-closure-mismatch})
           :policy-reference-valid (pass? #{:propagation-policy-missing :unsupported-propagation-policy-schema :propagation-policy-hash-mismatch :propagation-policy-reference-mismatch :unsupported-propagation-policy})
           :policy-accounting-contract-supported (pass? #{:unsupported-propagation-policy})
           :source-account-policy-compliant (pass? #{:application-source-account-policy-mismatch :source-entry-account-policy-mismatch})
           :participant-account-policy-compliant (pass? #{:participant-credit-account-policy-mismatch})
           :shortfall-policy-compliant (pass? #{:shortfall-classification-policy-mismatch :unsupported-policy-shortfall-classification})
           :residual-policy-compliant (pass? #{:residual-destination-policy-mismatch :source-debit-violates-residual-policy :unexpected-residual-refund})
           :idempotency-policy-compliant (pass? #{:unsupported-policy-idempotency-contract :policy-idempotency-component-missing :application-key-policy-mismatch})
           :token-policy-compliant (pass? #{:source-token-mismatch :participant-token-mismatch :residual-token-mismatch})
           :account-classes-valid (pass? #{:source-account-policy-mismatch :participant-account-policy-mismatch :application-source-account-policy-mismatch :source-entry-account-policy-mismatch :participant-credit-account-policy-mismatch :deferred-position-policy-mismatch})
           :source-token-consistent (pass? #{:source-token-mismatch})
           :available-allocation-residual (pass? #{:available-allocation-residual-mismatch :residual-record-mismatch :residual-token-mismatch})
           :residual-retained-in-pool (pass? #{:residual-destination-policy-mismatch})
           :entry-set-balanced (pass? #{:accounting-entry-set-unbalanced})
           :application-output-schema-valid (pass? #{:application-output-schema-invalid})
           :application-output-hash-valid (pass? #{:application-output-hash-mismatch})
           :position-after-hash-valid (pass? #{:position-after-hash-mismatch})
           :deferred-position-deadline-valid (pass? #{:deferred-position-deadline-expired})}

          checks-pass?
          (fn [check-ids]
            (every? #(= :pass (get checks %)) check-ids))

          categories
          (into {}
                (map (fn [[category check-ids]]
                       [category (checks-pass? check-ids)]))
                accounting-category-checks)

          holds? (empty? failures)]
      {:check/id :yield/pro-rata-accounting-reconciles
       :checks checks
       :categories categories
       :valid? holds?
       :holds? holds?
       :position-after-hash-reconciliation (:classifications position-after-reconciliation)
       :violations failures})))

(defn check-pro-rata-propagation-complete
  "The committed allocation decision was bound to the propagation;
   entitlement and capacity constraints held; each committed amount
   was applied exactly once to a policy-authorised account class;
   and the accounting entries and resulting state changes reconciled."
  [world]
  (let [propagations (vals (:yield/pro-rata-propagations world {}))
        decisions (:yield/partial-fill-decisions world {})
        ;; Multi-round awareness: a participant's deferred state is superseded by
        ;; each later shared-withdrawal round, so the position-state checks
        ;; (deferred-position-applied / fulfilled-position-closed) only apply to
        ;; that participant's LATEST propagation.  Earlier rounds' deferred
        ;; residuals are validated by the round-chain/lineage invariants and the
        ;; per-propagation accounting reconciliation instead.  A propagation
        ;; without a committed step is treated as Long/MAX_VALUE (the safe
        ;; single-round default).
        latest-step-by-participant
        (reduce (fn [m p]
                  (let [step (long (get-in p [:allocation/invocation-context :step]
                                           Long/MAX_VALUE))]
                    (reduce (fn [m participant]
                              (let [id (:participant-id participant)]
                                (update m id (fnil max Long/MIN_VALUE) step)))
                            m
                            (:participants p []))))
                {}
                propagations)
        violations
        (vec
         (mapcat
          (fn [artifact]
            (let [pid (:propagation/id artifact)
                  participants (:participants artifact [])
                  this-step (long (get-in artifact [:allocation/invocation-context :step]
                                          Long/MAX_VALUE))
                  decision (get decisions (:calculation-ref artifact))
                  binding-errors (if (= "pro-rata-propagation.v2" (:schema-version artifact))
                                   (if decision
                                     (partial-fill/propagation-allocation-binding-violations decision artifact)
                                     [{:reason :propagation-decision-reference-mismatch
                                       :propagation/id pid}])
                                   [])
                  app (get-in world [:yield/applied-pro-rata-propagations pid])
                  account-class-errors (if app
                                         (policy-accounting-violations artifact app)
                                         [])
                  summary (:summary artifact {})
                  positions (:yield/positions world {})
                  sum-field (fn [field] (reduce + 0 (map #(long (get % field 0)) participants)))
                  participant-errors
                  (mapcat
                   (fn [p]
                     (let [id (:participant-id p)
                           fulfilled (long (:fulfilled p 0))
                           deferred (long (:deferred p 0))
                           unmet (long (:unmet p 0))
                           waived (long (:waived p 0))
                           eligible (long (:eligible-obligation p 0))
                           cap (long (:effective-cap p eligible))
                           position (get positions id)
                           position-deferred (long (get-in position [:shortfall :deferred-amount] 0))
                           latest? (= this-step (long (get latest-step-by-participant id -1)))]
                       (cond-> []
                         (not= eligible (+ fulfilled deferred unmet waived))
                         (conj {:propagation/id pid :participant-id id
                                :reason :entitlement-not-conserved})
                         (> fulfilled cap)
                         (conj {:propagation/id pid :participant-id id
                                :reason :capacity-exceeded})
                         (and latest? (pos? deferred) (not= deferred position-deferred))
                         (conj {:propagation/id pid :participant-id id
                                :reason :deferred-position-not-applied})
                         (and latest? (zero? deferred) (not= :withdrawn (:status position)))
                         (conj {:propagation/id pid :participant-id id
                                :reason :fulfilled-position-not-closed}))))
                   participants)
                  allocated (long (:allocated summary 0))
                  available (long (:available summary 0))
                  residual (long (:unallocated-residual summary 0))
                  participant-accounting-total (reduce + 0 (map #(long (get-in % [:accounting-entry :delta] 0)) (:applications artifact [])))
                  ledger-net (reduce + 0 (map #(long (:delta % 0)) (:accounting-entries artifact [])))
                  apparent-accounting-errors
                  (mapcat (fn [app-entry]
                            (let [apparent-delta (get-in app-entry [:apparent-application :accounting-delta])
                                  entry-delta (get-in app-entry [:accounting-entry :delta])]
                              (cond-> []
                                (and (some? apparent-delta) (not= apparent-delta entry-delta))
                                (conj {:propagation/id pid
                                       :participant-id (:participant-id app-entry)
                                       :reason :apparent-application-accounting-mismatch}))))
                          (:applications artifact []))
                  artifact-errors (cond-> []
                                    (not= allocated (sum-field :fulfilled))
                                    (conj {:propagation/id pid :reason :allocation-not-applied})
                                    (not= allocated participant-accounting-total)
                                    (conj {:propagation/id pid :reason :participant-accounting-not-reconciled})
                                    (not= 0 ledger-net)
                                    (conj {:propagation/id pid :reason :ledger-not-balanced})
                                    (not= available (+ allocated residual))
                                    (conj {:propagation/id pid :reason :liquidity-not-conserved})
                                    (not= (sum-field :eligible-obligation)
                                          (+ (sum-field :fulfilled) (sum-field :deferred)
                                             (sum-field :unmet) (sum-field :waived)))
                                    (conj {:propagation/id pid :reason :aggregate-entitlement-not-conserved})
                                    (and (pos? residual) (nil? (get-in artifact [:residual :destination])))
                                    (conj {:propagation/id pid :reason :residual-without-destination}))]
              (concat binding-errors account-class-errors
                      participant-errors apparent-accounting-errors artifact-errors)))
          propagations))
        reasons (set (map :reason violations))

        pass?
        (fn [reason-set]
          (if (empty? (clojure.set/intersection reasons reason-set))
            :pass :fail))

        checks
        {:allocation-decision-binding-valid
         (pass? #{:propagation-allocation-id-mismatch
                  :propagation-allocation-hash-mismatch
                  :propagation-mechanism-reference-mismatch
                  :propagation-mechanism-evidence-reference-mismatch
                  :decision-mechanism-evidence-invalid
                  :propagation-decision-reference-mismatch
                  :decision-hash-mismatch})
         :allocation-row-translation-valid
         (pass? #{:missing-propagation-participant
                  :extra-propagation-participant
                  :duplicate-propagation-participant
                  :duplicate-decision-allocation-row
                  :propagated-fulfilled-mismatch
                  :propagated-unmet-mismatch
                  :propagation-fulfilled-total-mismatch
                  :propagation-unmet-total-mismatch})
         :entitlement-conserved
         (pass? #{:entitlement-not-conserved
                  :aggregate-entitlement-not-conserved})
         :capacity-within-bounds
         (pass? #{:capacity-exceeded})
         :deferred-position-applied
         (pass? #{:deferred-position-not-applied})
         :fulfilled-position-closed
         (pass? #{:fulfilled-position-not-closed})
         :allocation-applied
         (pass? #{:allocation-not-applied})
         :account-classes-valid
         (pass? #{:application-source-account-policy-mismatch
                  :source-entry-account-policy-mismatch
                  :participant-credit-account-policy-mismatch})
         :ledger-balanced
         (pass? #{:ledger-not-balanced})
         :liquidity-conserved
         (pass? #{:liquidity-not-conserved})
         :participant-accounting-reconciled
         (pass? #{:participant-accounting-not-reconciled})
         :apparent-application-accounting-reconciled
         (pass? #{:apparent-application-accounting-mismatch})
         :residual-has-destination
         (pass? #{:residual-without-destination})}

        checks-pass?
        (fn [check-ids]
          (every? #(= :pass (get checks %)) check-ids))

        categories
        {:account-classes-valid
         (checks-pass? [:account-classes-valid])
         :allocation-constraints-valid
         (checks-pass? [:entitlement-conserved
                        :capacity-within-bounds])
         :allocation-propagated-exactly
         (checks-pass? [:allocation-decision-binding-valid
                        :allocation-row-translation-valid
                        :allocation-applied
                        :deferred-position-applied
                        :fulfilled-position-closed])
         :accounting-entries-complete
         (checks-pass? [:participant-accounting-reconciled])
         :accounting-reconciles
         (checks-pass? [:ledger-balanced
                        :liquidity-conserved
                        :apparent-application-accounting-reconciled
                        :residual-has-destination])}

        holds? (empty? violations)]
    {:check/id :yield/pro-rata-propagation-complete
     :checks checks
     :categories categories
     :valid? holds?
     :holds? holds?
     :violations (vec violations)}))

(def ^:private check-fns
  {:yield/position-consistency check-position-consistency
   :yield/exposure             check-provider-exposure
   :yield/token-key-consistency check-token-key-consistency
   :yield/shortfall-splits     check-shortfall-splits
   :yield/shortfall-detected   check-shortfall-detected
   :yield/status-fsm           check-status-fsm
   :yield/realized-non-negative check-realized-non-negative
   :yield/partial-liquidity-principal check-partial-liquidity-principal
   :yield/value-conservation   check-value-conservation
   :yield/deferred-reclaim     check-deferred-reclaim
   :yield/aggregate-shortfall-cap check-aggregate-shortfall-cap
   :yield/aggregate-shortfall     check-aggregate-shortfall
   :yield/aggregate               check-aggregate
   :yield/pro-rata-propagation-complete check-pro-rata-propagation-complete
   :yield/pro-rata-accounting-reconciles check-pro-rata-accounting-reconciles
   :yield/shared-withdrawal-conservation check-shared-withdrawal-conservation-world
   :yield/withdrawal-lineage-conservation check-withdrawal-lineage-conservation
   :yield/withdrawal-budget-provenance check-withdrawal-budget-provenance
   :yield/withdrawal-ledger-conservation check-withdrawal-ledger-conservation})

(defn registered-ids []
  (vec (keys check-fns)))

(defn- normalize-world-for-check
  "Replay trace snapshots use :yield-positions / :yield-held; expand to world paths."
  [world]
  (cond-> world
    (and (map? world) (:yield-positions world))
    (assoc :yield/positions (:yield-positions world))
    (and (map? world) (:yield-held world))
    (assoc :yield/held-balances (:yield-held world))
    (and (map? world) (:yield-indices world))
    (assoc :yield/indices (:yield-indices world))))

(defn holds?
  "Run a single invariant check; returns boolean.
   Handles functions that return {:holds? bool :violations [...]} as well as
   raw boolean (backward compatible)."
  [inv-id world]
  (if-let [f (get check-fns inv-id)]
    (let [result (f (normalize-world-for-check world))]
      (boolean (if (map? result) (:holds? result) result)))
    (throw (ex-info "Unknown yield invariant" {:invariant inv-id :known (registered-ids)}))))

(defn run-invariants
  "Run invariant checks; returns {inv-id {:holds? bool :violations [...]}}.
   If the invariant function returns structured {:holds? ... :violations ...},
   those violations are preserved; otherwise they default to nil.

   Supports :expected-failures from world[:params :expected-failures <scenario-id>]
   (same mechanism as Sew invariants)."
  [world inv-ids]
  (let [world* (normalize-world-for-check world)
        scenario-id (get-in world [:params :scenario-id])
        expected-failures (set (map keyword
                                    (get-in world [:params :expected-failures scenario-id] [])))]
    (into {}
          (for [id inv-ids]
            (let [f (get check-fns id)
                  _ (when-not f (log/warn! "Unknown yield invariant in run-invariants" {:invariant-id id :known (registered-ids)}))
                  raw (when f (f world*))
                  structured? (map? raw)
                  holds? (boolean (if structured? (:holds? raw) raw))
                  expected-fail? (contains? expected-failures id)]
              [id {:holds? (or holds? expected-fail?)
                   :expected-failure? expected-fail?
                   :unused-expected-failure? (and expected-fail? holds?)
                   :checks (when structured? (:checks raw))
                   :violations (when (and structured? (not expected-fail?)) (:violations raw))}])))))

(defn check-all
  "Default runtime set for yield-v1 (see `invariant-catalog/default-runtime-invariant-ids`)."
  [world]
  (run-invariants world cat/default-runtime-invariant-ids))
