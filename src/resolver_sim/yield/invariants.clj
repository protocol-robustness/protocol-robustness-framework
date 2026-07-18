(ns resolver-sim.yield.invariants
  "Generic accounting invariants for yield mechanism (provider + Sew)."
  (:require [clojure.set]
              [resolver-sim.yield.risk :as risk]
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

(defn- application-order-key [order]
  [(:run-id order) (:execution-id order) (:scenario-id order)
   (:event-id order) (:step order)])

(defn chain-violations
  "Test-facing validation of canonical application ordering and account chains."
  [world applications]
  (let [order-valid? (fn [a] (some? (get-in a [:application-order :step])))
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
                                                       :expected (get-in latest [:source-account :after]) :observed current})))) source-groups)
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
                                                                                                 :expected (get-in latest [:withdrawn :after]) :observed current}))))) participant-groups)]
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
  (mapcat (fn [application]
            (mapcat (fn [participant]
                      (let [before (:position-before participant)
                            after (:position-after participant)
                            prior (:deferred-position before)
                            history (:deferred-position-history after)
                            record (get history (:position/id prior))]
                        (cond-> []
                          (and prior (nil? record))
                          (conj {:propagation-id (:propagation-id application) :participant-id (:participant-id participant)
                                 :reason :closed-position-history-missing})
                          (and record (not= :closed (:position/status record)))
                          (conj {:propagation-id (:propagation-id application) :participant-id (:participant-id participant)
                                 :reason :closed-position-history-closure-mismatch})
                          (and record (not= (:position/root-obligation-id prior) (:position/root-obligation-id record)))
                          (conj {:propagation-id (:propagation-id application) :participant-id (:participant-id participant)
                                 :reason :closed-position-history-identity-mismatch})
                          (and record (not= (:propagation-id application) (:position/closed-by-propagation-id record)))
                                                    (conj {:propagation-id (:propagation-id application) :participant-id (:participant-id participant)
                                                           :reason :closed-position-history-closure-mismatch})
                                                    (and record (not= (:token before) (:position/token record)))
                                                    (conj {:propagation-id (:propagation-id application) :participant-id (:participant-id participant)
                                                           :reason :closed-position-history-identity-mismatch})
                                                    (and record (not= (:participant-id participant) (:position/participant-id record)))
                                                    (conj {:propagation-id (:propagation-id application) :participant-id (:participant-id participant)
                                                           :reason :closed-position-history-identity-mismatch})
                                                    (and record (not= (:position/current-amount prior) (:position/current-amount record)))
                                                    (conj {:propagation-id (:propagation-id application) :participant-id (:participant-id participant)
                                                           :reason :closed-position-history-amount-mismatch}))))
                    (:participants application))) applications))

(defn application-obligation-violations
  "Match propagation participants to application-v2 participant snapshots exactly."
  [propagations applications]
  (mapcat (fn [p]
            (let [app (some #(when (= (:propagation-id %) (:propagation/id p)) %) applications)
                  token (:token p)]
              (mapcat (fn [participant]
                        (let [id (:participant-id participant)
                              obligation (get-in participant [:origin :obligation-id])
                              matches (filter #(= [token id obligation]
                                                  [(get-in % [:withdrawn :token]) (:participant-id %) (:obligation-id %)])
                                              (:participants app))]
                          (cond-> []
                            (nil? app) (conj {:propagation-id (:propagation/id p) :participant-id id :reason :application-participant-record-missing})
                            (and app (empty? matches)) (conj {:propagation-id (:propagation/id p) :participant-id id :reason :application-obligation-id-mismatch})
                            (and app (> (count matches) 1)) (conj {:propagation-id (:propagation/id p) :participant-id id :reason :application-participant-record-duplicate}))))
                      (:participants p)))) propagations))

(defn cumulative-fulfilment-violations
  "Validate immutable application cumulative-fulfilment snapshots."
  [applications]
  (mapcat (fn [application]
            (mapcat (fn [participant]
                      (let [c (:cumulative-fulfilled participant)
                            obligation-before (long (get-in participant [:obligation :before] 0))
                            before (:before c) delta (:delta c) after (:after c)]
                        (cond-> []
                          (not (and (integer? before) (integer? delta) (integer? after)))
                          (conj {:propagation-id (:propagation-id application) :participant-id (:participant-id participant)
                                 :reason :cumulative-fulfilment-arithmetic-failed})
                          (and (integer? before) (integer? delta) (integer? after) (not= (+ before delta) after))
                          (conj {:propagation-id (:propagation-id application) :participant-id (:participant-id participant)
                                 :reason :cumulative-fulfilment-arithmetic-failed})
                          (and (integer? after) (> after obligation-before))
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
                            active (:deferred-position position)]
                        (cond-> []
                          (and (pos? deferred) (nil? active))
                          (conj {:propagation-id (:propagation/id p) :participant-id id :reason :deferred-position-missing})
                          (and (pos? deferred) active (not= deferred (:position/current-amount active)))
                          (conj {:propagation-id (:propagation/id p) :participant-id id :reason :deferred-position-amount-mismatch})
                          (and (pos? deferred) active (not= (:token p) (:token position)))
                                                    (conj {:propagation-id (:propagation/id p) :participant-id id :reason :deferred-position-token-mismatch})
                                                    (and (pos? deferred) active (not= (get-in participant [:origin :obligation-id]) (:position/root-obligation-id active)))
                                                    (conj {:propagation-id (:propagation/id p) :participant-id id :reason :deferred-position-root-obligation-mismatch})
                                                    (and (pos? deferred) active (not= :deferred-withdrawal (:position/type active)))
                                                    (conj {:propagation-id (:propagation/id p) :participant-id id :reason :deferred-position-type-mismatch})
                                                    (and (pos? deferred) active (not= :later-liquidity (:position/eligibility active)))
                                                    (conj {:propagation-id (:propagation/id p) :participant-id id :reason :deferred-position-eligibility-mismatch})
                                                    (and (pos? deferred) active (not= (:propagation/id p) (:position/origin-propagation-id active)))
                                                    (conj {:propagation-id (:propagation/id p) :participant-id id :reason :deferred-position-origin-mismatch})
                                                    (and (zero? deferred) active)
                          (conj {:propagation-id (:propagation/id p) :participant-id id :reason :fulfilled-position-still-active}))))
                    (:participants p))) propagations))

(defn check-pro-rata-accounting-reconciles
  "Reconcile each persisted propagation with its committed application snapshot."
  [world]
  (let [props (vals (:yield/pro-rata-propagations world {}))
        applications (vals (:yield/applied-pro-rata-propagations world {}))
        failures (concat (mapcat (fn [p]
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
                                                                  credits (filter #(and (= :credit (:entry/type %)) (= :withdrawn (:account %))) entries)
                                                                                                   credit-errors (mapcat (fn [participant]
                                                                                                                           (let [fulfilled (long (:fulfilled participant 0))
                                                                                                                                 key [(:token p) (:participant-id participant) (get-in participant [:origin :obligation-id])]
                                                                                                                                 matching (filter #(= key [(:token %) (:participant-id %) (:obligation-id %)]) credits)]
                                                                                                                             (cond-> []
                                                                                                                               (and (pos? fulfilled) (empty? matching)) (conj {:propagation-id id :participant-id (:participant-id participant) :reason :participant-credit-missing})
                                                                                                                               (and (pos? fulfilled) (> (count matching) 1)) (conj {:propagation-id id :participant-id (:participant-id participant) :reason :participant-credit-duplicate})
                                                                                                                               (and (pos? fulfilled) (= 1 (count matching)) (not= fulfilled (long (:delta (first matching) 0)))) (conj {:propagation-id id :participant-id (:participant-id participant) :reason :participant-credit-mismatch})
                                                                                                                               (and (zero? fulfilled) (seq matching)) (conj {:propagation-id id :participant-id (:participant-id participant) :reason :unexpected-zero-fulfilment-credit})))) participants)]
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
                         props)
                         (chain-violations world applications)
                                                  (exact-credit-violations props)
                                                                           (deferred-state-violations world props)
                                                                                                    (obligation-violations props)
                                                                                                                             (cumulative-fulfilment-violations applications)
                                                                                                                                                      (application-obligation-violations props applications)
                                                                                                                                                                               (closed-history-violations applications))]
    (let [failures (vec failures)
          reasons (set (map :reason failures))
          pass? (fn [reason-set] (if (empty? (clojure.set/intersection reasons reason-set)) :pass :fail))]
      {:holds? (empty? failures)
       :checks {:application-record-present (pass? #{:missing-propagation-application})
                :application-order-valid (pass? #{:application-order-missing :application-order-duplicate})
                :source-balance-chain-valid (pass? #{:source-balance-chain-broken :latest-source-balance-mismatch})
                :participant-balance-chain-valid (pass? #{:participant-balance-chain-broken :latest-authoritative-withdrawn-balance-mismatch})
                :participant-credit-keys-complete (pass? #{:participant-obligation-id-missing :credit-obligation-id-missing :duplicate-propagation-participant})
                :participant-credits-match-individually (pass? #{:participant-credit-missing :participant-credit-duplicate :participant-credit-mismatch :participant-credit-token-mismatch :participant-credit-owner-mismatch :participant-credit-obligation-mismatch})
                :participant-credit-set-exact (pass? #{:orphan-participant-credit :unexpected-zero-fulfilment-credit})
                                :deferred-position-presence-valid (pass? #{:deferred-position-missing :fulfilled-position-still-active})
                                                :deferred-position-amounts-valid (pass? #{:deferred-position-amount-mismatch})
                                                :deferred-position-identities-valid (pass? #{:deferred-position-token-mismatch :deferred-position-root-obligation-mismatch :deferred-position-origin-mismatch})
                                                :deferred-position-policy-valid (pass? #{:deferred-position-type-mismatch :deferred-position-eligibility-mismatch})
                                                :obligation-identities-valid (pass? #{:participant-obligation-id-missing})
                                                                :application-obligation-identities-valid (pass? #{:application-participant-record-missing :application-participant-record-duplicate :application-obligation-id-mismatch})
                                                                :obligation-conservation (pass? #{:obligation-conservation-failed :invalid-obligation-amount})
                                                                :unsupported-obligation-outcomes-absent (pass? #{:unsupported-unmet-withdrawal :unsupported-waived-withdrawal})
                                                                :obligation-after-valid (pass? #{:obligation-after-mismatch})
                                                                                :cumulative-fulfilment-valid (pass? #{:cumulative-fulfilment-arithmetic-failed :cumulative-fulfilment-exceeded})
                                                                                                :closed-position-history-valid (pass? #{:closed-position-history-missing :closed-position-history-identity-mismatch :closed-position-history-closure-mismatch})
                                                                :entry-set-balanced (pass? #{:accounting-entry-set-unbalanced})}
       :violations failures})))

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
