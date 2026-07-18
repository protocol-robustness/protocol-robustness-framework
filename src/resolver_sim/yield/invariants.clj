(ns resolver-sim.yield.invariants
  "Generic accounting invariants for yield mechanism (provider + Sew)."
  (:require [resolver-sim.yield.risk :as risk]
            [resolver-sim.yield.invariant-catalog :as cat]
                        [resolver-sim.yield.partial-fill :as partial-fill]
                        [resolver-sim.logging :as log]))

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
    {:holds? (empty? violations) :violations (vec violations)}))

(defn check-realized-non-negative
  [world]
  (every? #(>= (:realized-yield % 0) 0) (vals (:yield/positions world {}))))

(defn check-status-fsm
  [world]
  (let [allowed #{:active :unwinding :withdrawn :settled}]
    (every? #(contains? allowed (:status %)) (vals (:yield/positions world {})))))

(defn check-shortfall-splits
  "When :shortfall exists, fulfilled + deferred + haircut = basis."
  [world]
  (every? (fn [pos]
            (if-let [sf (:shortfall pos)]
              (let [f (long (or (:fulfilled-amount sf) 0))
                    d (long (or (:deferred-amount sf) 0))
                    h (long (or (:haircut-amount sf) 0))
                    b (long (or (:basis-amount sf) 0))]
                (= (+ f d h) b))
              true))
          (vals (:yield/positions world {}))))

(defn check-partial-liquidity-principal
  "Under :partial-liquidity, unwinding positions must not haircut principal on the shortfall map."
  [world]
  (every? (fn [pos]
            (let [risk (get-in world [:yield/risk (:module/id pos) (:token pos)] {})
                  failures (risk/normalize-failure-modes (:failure-modes risk))
                  partial? (contains? failures :partial-liquidity)]
              (if (and partial? (= (:status pos) :unwinding) (:shortfall pos))
                (let [sf (:shortfall pos)
                      principal (:principal pos 0)
                      f (long (or (:fulfilled-amount sf) 0))
                      d (long (or (:deferred-amount sf) 0))
                      b (long (or (:basis-amount sf) 0))]
                  (and (pos? principal)
                       (zero? (long (or (:haircut-amount sf) 0)))
                       (= (+ f d) b)))
                true)))
          (vals (:yield/positions world {}))))

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
  (every? (fn [pos]
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
          (vals (:yield/positions world {}))))

(defn check-aggregate-shortfall-cap
  "Aggregate shortfall per (module-id, token) pair must not exceed
   the sum of position values (principal + realized-yield + max(0, unrealized-yield))
   in that pair. This prevents systemic over-counting where the total
   recorded shortfall across all positions exceeds available value.

   Returns {:holds? bool :violations [{:module-id mid :token tok
                                       :total-basis n :total-value n
                                       :imbalance n}]}."
  [world]
  (let [positions (vals (:yield/positions world {}))
        by-key (group-by (fn [p] [(:module/id p) (:token p)]) positions)
        violations (into []
                         (keep (fn [[[mid tok] pos-group]]
                                 (let [total-basis (reduce + 0 (map (comp (fn [v] (long (or v 0))) :basis-amount :shortfall) pos-group))
                                       total-value (reduce + 0 (map (fn [p]
                                                                      (+ (long (:principal p 0))
                                                                         (long (:realized-yield p 0))
                                                                         (max 0 (long (:unrealized-yield p 0)))))
                                                                    pos-group))]
                                   (when (> total-basis total-value)
                                     {:module-id mid :token tok
                                      :total-basis total-basis
                                      :total-value total-value
                                      :imbalance (- total-basis total-value)}))))
                         by-key)]
    {:holds? (empty? violations)
     :violations (vec violations)}))

(defn check-deferred-reclaim
  "Withdrawn positions: no shortfall; reclaimed ≥ 0."
  [world]
  (every? (fn [pos]
            (if (= (:status pos) :withdrawn)
              (and (nil? (:shortfall pos))
                   (>= (long (or (:reclaimed-amount pos) 0)) 0))
              true))
          (vals (:yield/positions world {}))))

(defn check-shortfall-detected
  "Verify shortfall detection correctness:

   1. Over-detection: no position's shortfall basis-amount exceeds its
      total economic value (principal + realized-yield + max(0, unrealized-yield)).
      A basis larger than the position means the shortfall was over-counted.

   2. Under-detection: when a module/token is in shortfall liquidity mode
      with available-ratio < 1.0, any position in :unwinding status that
      has not yet withdrawn must have :shortfall data. If the system is
      processing a withdrawal during shortfall but failed to record it,
      this check catches the gap."
  [world]
  (let [positions (:yield/positions world {})]
    (every? (fn [[oid pos]]
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
                    total-value (+ principal realized (max 0 unrealized))
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
            positions)))

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
        tokens    (into #{} (map :token (vals positions)))]
    (every? (fn [token]
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
            tokens)))

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
    {:holds? (empty? violations) :violations violations}))

(defn check-provider-exposure
  "Yield exposure invariant: total-held must cover active yield positions.
   Excludes resolver-owned positions since those are backed by resolver-stakes
   (a separate economic layer outside total-held)."
  [world]
  (check-yield-exposure world
                        (fn [_ pos] (and (= (:status pos) :active)
                                         (not (resolver-owned-position? pos))))
                        #(held-for-token world %)))

(defn check-pro-rata-accounting-reconciles
  "Reconcile each persisted propagation with its committed application snapshot."
  [world]
  (let [props (vals (:yield/pro-rata-propagations world {}))
        failures (mapcat (fn [p]
                           (let [id (:propagation/id p)
                                 a (get-in world [:yield/applied-pro-rata-propagations id])
                                 allocated (long (get-in p [:summary :allocated] 0))
                                 source (:source-account a)
                                 participants (:participants p)
                                 apps (:participants a)
                                 entries (:accounting-entries p)
                                                                  entry-hash (partial-fill/accounting-entry-set-hash entries)
                                                                  expected-key [:pro-rata-propagation (:calculation-ref p) (:outcome-ref p)
                                                                                (get-in p [:propagation-policy :policy/hash])]
                                                                  debit (filter #(and (= :debit (:entry/type %)) (= :shared-liquidity (:account %))) entries)
                                                                  credits (filter #(and (= :credit (:entry/type %)) (= :withdrawn (:account %))) entries)]
                             (cond-> []
                               (nil? a) (conj {:propagation-id id :reason :missing-propagation-application})
                                                              (and a (not= "pro-rata-propagation-application.v2" (:schema-version a))) (conj {:propagation-id id :reason :unsupported-application-schema})
                                                              (and a (not= expected-key (:application-key a))) (conj {:propagation-id id :reason :application-key-mismatch :expected expected-key :observed (:application-key a)})
                                                              (and a (not= (:calculation-ref p) (:calculation-id a))) (conj {:propagation-id id :reason :application-calculation-id-mismatch})
                                                              (and a (not= (:outcome-ref p) (:outcome-hash a))) (conj {:propagation-id id :reason :application-outcome-hash-mismatch})
                                                              (and a (not= (get-in p [:propagation-policy :policy/hash]) (:policy-hash a))) (conj {:propagation-id id :reason :application-policy-hash-mismatch})
                                                              (not= entry-hash (:accounting-entry-set-hash p)) (conj {:propagation-id id :reason :propagation-accounting-entry-hash-mismatch})
                                                              (and a (not= entry-hash (:accounting-entry-set-hash a))) (conj {:propagation-id id :reason :application-accounting-entry-hash-mismatch})
                               (and a (not= allocated (- (long (:before source 0)) (long (:after source 0))))) (conj {:propagation-id id :reason :source-account-arithmetic-failed})
                               (and a (not= (- allocated) (long (:delta source 0)))) (conj {:propagation-id id :reason :source-debit-mismatch})
                               (not= 1 (count debit)) (conj {:propagation-id id :reason :source-account-entry-missing})
                               (not= 0 (reduce + 0 (map #(long (:delta % 0)) entries))) (conj {:propagation-id id :reason :accounting-entry-set-unbalanced})
                               (and a (not= (set (map :participant-id participants)) (set (map :participant-id apps)))) (conj {:propagation-id id :reason :application-participant-set-mismatch})
                               (and a (not= allocated (reduce + 0 (map #(long (get-in % [:withdrawn :delta] 0)) apps)))) (conj {:propagation-id id :reason :participant-credit-total-mismatch})
                               (and a (not= allocated (reduce + 0 (map #(long (:delta % 0)) credits)))) (conj {:propagation-id id :reason :participant-credit-total-mismatch}))))
                         props)]
    {:holds? (empty? failures) :checks {:application-record-present (if (empty? failures) :pass :fail)
                                        :entry-set-balanced (if (empty? failures) :pass :fail)}
     :violations (vec failures)}))

(defn check-pro-rata-propagation-complete
  "Every persisted shared pro-rata outcome must have been applied exactly once.
   Verifies allocation and entitlement conservation, state-position residuals,
   capacity bounds, accounting deltas, and an explicit residual destination."
  [world]
  (let [propagations (vals (:yield/pro-rata-propagations world {}))
        violations
        (vec
         (mapcat
          (fn [artifact]
            (let [participants (:participants artifact [])
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
                           position-deferred (long (get-in position [:shortfall :deferred-amount] 0))]
                       (cond-> []
                         (not= eligible (+ fulfilled deferred unmet waived)) (conj {:participant-id id :error :entitlement-not-conserved})
                         (> fulfilled cap) (conj {:participant-id id :error :capacity-exceeded})
                         (and (pos? deferred) (not= deferred position-deferred)) (conj {:participant-id id :error :deferred-position-not-applied})
                         (and (zero? deferred) (not= :withdrawn (:status position))) (conj {:participant-id id :error :fulfilled-position-not-closed}))))
                   participants)
                  allocated (long (:allocated summary 0))
                  available (long (:available summary 0))
                  residual (long (:unallocated-residual summary 0))
                  participant-accounting-total (reduce + 0 (map #(long (get-in % [:accounting-entry :delta] 0)) (:applications artifact [])))
                  ledger-net (reduce + 0 (map #(long (:delta % 0)) (:accounting-entries artifact [])))
                  artifact-errors (cond-> []
                                    (not= allocated (sum-field :fulfilled)) (conj {:error :allocation-not-applied})
                                    (not= allocated participant-accounting-total) (conj {:error :participant-accounting-not-reconciled})
                                    (not= 0 ledger-net) (conj {:error :ledger-not-balanced})
                                    (not= available (+ allocated residual)) (conj {:error :liquidity-not-conserved})
                                    (not= (sum-field :eligible-obligation)
                                          (+ (sum-field :fulfilled) (sum-field :deferred)
                                             (sum-field :unmet) (sum-field :waived))) (conj {:error :aggregate-entitlement-not-conserved})
                                    (and (pos? residual) (nil? (get-in artifact [:residual :destination]))) (conj {:error :residual-without-destination}))]
              (concat participant-errors artifact-errors)))
          propagations))]
    {:holds? (empty? violations) :violations violations}))

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
   :yield/pro-rata-propagation-complete check-pro-rata-propagation-complete
      :yield/pro-rata-accounting-reconciles check-pro-rata-accounting-reconciles})

(defn registered-ids []
  (vec (keys check-fns)))

(defn- normalize-world-for-check
  "Replay trace snapshots use :yield-positions / :yield-held; expand to world paths."
  [world]
  (cond-> world
    (map? world)
    (cond-> (:yield-positions world)
      (assoc :yield/positions (:yield-positions world))
      (:yield-held world)
      (assoc :yield/held-balances (:yield-held world))
      (:yield-indices world)
      (assoc :yield/indices (:yield-indices world)))))

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
                   :violations (when (and structured? (not expected-fail?)) (:violations raw))}])))))

(defn check-all
  "Default runtime set for yield-v1 (see `invariant-catalog/default-runtime-invariant-ids`)."
  [world]
  (run-invariants world cat/default-runtime-invariant-ids))
