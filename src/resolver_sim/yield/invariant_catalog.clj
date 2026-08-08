(ns resolver-sim.yield.invariant-catalog
  "Canonical yield-provider invariant ids, descriptions, and suite defaults.")

(def catalog
  "Invariant id → metadata for docs, notebooks, and PRF pilot mapping."
  {:yield/position-consistency
   {:description "Principal, shares, and realized yield are non-negative; unrealized ≥ 0 unless :mark-to-market."
    :prf-tags [:yield-accounting :accrual]}

   :yield/exposure
   {:description "Custody ledger (:yield/held-balances) covers all active position economic value."
    :prf-tags [:solvency :liquidity]}

   :yield/shortfall-splits
   {:description "When :shortfall is present, fulfilled + deferred = basis-amount."
    :prf-tags [:liquidity-shortfall :partial-withdrawal]}

   :yield/status-fsm
   {:description "Position status is one of :active, :unwinding, :withdrawn."
    :prf-tags [:state-machine]}

   :yield/realized-non-negative
   {:description "Crystallized :realized-yield is never negative."
    :prf-tags [:yield-accounting]}

   :yield/partial-liquidity-principal
   {:description "Under :partial-liquidity stress, unwinding positions keep principal intact; shortfall applies to yield leg only."
    :prf-tags [:partial-liquidity :shortfall-affected]}

   :yield/value-conservation
   {:description "Total position value = claimable + deferred + loss within rounding tolerance (1 base unit)."
    :prf-tags [:yield-accounting :conservation :shortfall-affected]}
   :yield/deferred-reclaim
   {:description "Withdrawn positions have no open shortfall; reclaimed amounts are non-negative."
    :prf-tags [:recovery :shortfall-affected]}

   :yield/shortfall-detected
   {:description "Shortfall is correctly detected: basis-amount does not exceed position value (no over-detection), and unwinding positions in shortfall mode have shortfall data (no under-detection)."
    :prf-tags [:liquidity-shortfall :detection :shortfall-affected]}

   :yield/aggregate-shortfall-cap
   {:description "Aggregate shortfall per (module-id, token) pair does not exceed the sum of position values. Prevents systemic over-counting."
    :prf-tags [:liquidity-shortfall :conservation :aggregate]}

   :yield/aggregate-shortfall
   {:description "Compatibility alias of :yield/aggregate-shortfall-cap: the summed shortfall basis of each (module-id, token) pair does not exceed the summed position value of that pair."
    :prf-tags [:liquidity-shortfall :conservation :aggregate]}

   :yield/aggregate
   {:description "Aggregate yield position values and shortfall balances are consistent across positions per (module-id, token) pair: shortfall splits reconcile to basis and shortfall does not exceed available value."
    :prf-tags [:liquidity-shortfall :conservation :aggregate]}

   :yield/token-key-consistency
   {:description "Rejects simultaneous string and keyword keys for one normalized token in accounting maps; mixed representations would split balances across independent map entries and make custody reconciliation ambiguous."
    :prf-tags [:accounting :consistency]}

   :yield/pro-rata-propagation-complete
   {:description "The committed allocation decision was bound to the propagation; entitlement and capacity constraints held; each committed amount was applied exactly once to a policy-authorised account class; and the accounting entries and resulting state changes reconciled."
    :prf-tags [:pro-rata :propagation :accounting :conservation]}

   :yield/pro-rata-accounting-reconciles
   {:description "The application record binds to its propagation and allocation decision; accounting entries cover all propagated movements; source, participant, and deferred-position state changes reconcile and form a continuous chain; and no active deferred position is past its policy deadline."
    :prf-tags [:pro-rata :accounting :conservation :deadline]}

   :yield/shared-withdrawal-conservation
   {:description "Every :yield-withdraw-shared decision artifact is replay-verifiable: the decision hash reconciles; the committed allocation scope/mode/rounding policy match the shared pro-rata contract; the participant/request set is bijective to the allocation rows; per-row filled ≤ effective-cap ≤ requested, deferred = requested - filled, no haircut; Σ filled ≤ committed available (the shared-liquidity non-overallocation bound); and the locked equality Σ filled = min(available, Σ effective-demand) holds, so caps binding below both requests and liquidity are honoured."
    :prf-tags [:pro-rata :liquidity :solvency :conservation :aggregate :withdrawal]},

   :yield/withdrawal-ledger-conservation
   {:description "Every recorded withdrawal (single or batch) settles at most the liquidity pool available to it, bound to its exact execution world and withdrawal subject. Per record: the fixed-point certificate is valid; the committed run-root matches the recomputed execution context (run/execution/scenario/params); the request-set root matches the ledger's own rows; declared principals are unique and biject to row owners; filled ≤ available; per-row and aggregate economic conservation (filled + deferred + haircut = requested, totals reconcile to rows); FCFS prefix pool bound holds."
    :prf-tags [:liquidity :solvency :conservation :aggregate :withdrawal :provenance]}})

(def default-runtime-invariant-ids
  "Checked on every successful replay step (yield-v1 adapter)."
  [:yield/position-consistency
   :yield/exposure
   :yield/shortfall-splits
   :yield/shortfall-detected
   :yield/status-fsm
   :yield/realized-non-negative
   :yield/token-key-consistency
   :yield/value-conservation
   :yield/partial-liquidity-principal
   :yield/deferred-reclaim
   :yield/aggregate-shortfall-cap
   :yield/aggregate-shortfall
   :yield/aggregate
   :yield/pro-rata-propagation-complete
   :yield/pro-rata-accounting-reconciles
   :yield/shared-withdrawal-conservation
   :yield/withdrawal-ledger-conservation])

(def default-transition-invariant-ids
  "Checked on each successful transition (yield-v1 adapter)."
  [:yield/index-monotone])

(def default-expectation-invariant-ids
  "Merged into :expectations :invariants for :yield-provider-scenarios."
  (vec (distinct (concat default-runtime-invariant-ids
                         default-transition-invariant-ids))))

(def scenario-extra-invariants
  "Per scenario-id additions (unioned with defaults at load time)."
  {"y03-partial-liquidity-shortfall-affected" [:yield/partial-liquidity-principal]
   "y04-liquidity-shortfall-withdraw"         [:yield/shortfall-splits]
   "y05-shortfall-affected-recovery"          [:yield/deferred-reclaim :yield/partial-liquidity-principal]
   "y06-liquidity-shortage-deposit-blocked"   []
   "y07-monthly-accrual-one-year"             [:yield/index-monotone]})

(defn catalog-entry [inv-id]
  (get catalog inv-id))

(defn expectation-invariants-for-scenario
  [scenario-id]
  (let [extra (get scenario-extra-invariants scenario-id [])]
    (vec (distinct (concat default-expectation-invariant-ids extra)))))

(defn enrich-expectations
  "Attach default :invariants to scenario :expectations (pure)."
  [scenario]
  (let [sid (:scenario-id scenario)
        exp (or (:expectations scenario) {})
        merged (vec (sort (into (set (expectation-invariants-for-scenario sid))
                                (map #(if (keyword? %) % (keyword %))
                                     (:invariants exp [])))))]
    (assoc scenario :expectations (assoc exp :invariants merged))))