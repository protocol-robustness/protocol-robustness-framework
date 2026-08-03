(ns resolver-sim.economics.terminal-payoff
  "Canonical terminal-payoff projection for all participant types.

   Provides a single source of truth for resolver, claimant, protocol, and
   insurance-pool payoffs. All economic-expected-value formulas elsewhere in
   the framework should reconcile against this function.

   The function decomposes terminal wealth into explicit components so that
   assumptions about fee retention, bond treatment, appeal costs, fraud gain,
   and capital costs are transparent and auditable.

   Payoff components are returned as integers (wei) where possible and
   qualified with `:type` and `:note` keys to explain their derivation."
  (:require [resolver-sim.stochastic.economics :as econ]))

;; ---------------------------------------------------------------------------
;; Payoff component helpers
;; ---------------------------------------------------------------------------

(defn fee-component
  "Return a fee component map.
   `amount-wei` — the fee in wei
   `retained?` — true if the fee is retained by the resolver after reversal"
  [amount-wei retained?]
  {:type :fee
   :amount (long (or amount-wei 0))
   :retained? (boolean retained?)
   :note (if retained? "fee retained after reversal" "fee forfeited on reversal")})

(defn bond-component
  "Return a bond component map.
   `amount-wei` — bond principal
   `returned?` — true if bond is returned
   `slashed?` — true if bond is slashed (transfer to protocol/insurance)"
  [amount-wei returned? slashed?]
  {:type :bond
   :amount (long (or amount-wei 0))
   :returned? (boolean returned?)
   :slashed? (boolean slashed?)
   :note (cond
           slashed? "bond slashed — transferred to protocol/insurance pool"
           returned? "bond returned"
           :else "bond status unknown")})

(defn appeal-cost-component
  "Return an appeal cost component map.
   `amount-wei` — cost incurred
   `paid-by` — :resolver | :claimant | :protocol"
  [amount-wei paid-by]
  {:type :appeal-cost
   :amount (long (or amount-wei 0))
   :paid-by paid-by
   :note (str "appeal cost paid by " (name paid-by))})

(defn capital-cost-component
  "Return a capital opportunity-cost component.
   `amount-wei` — imputed cost of locked capital
   `locked-duration-seconds` — how long capital was locked"
  [amount-wei locked-duration-seconds]
  {:type :capital-cost
   :amount (long (or amount-wei 0))
   :locked-duration-seconds (long (or locked-duration-seconds 0))
   :note "imputed opportunity cost of locked capital"})

(defn fraud-gain-component
  "Return a fraud-gain component map.
   `amount-wei` — gross value captured
   `capturable?` — true if the resolver can actually extract this value"
  [amount-wei capturable?]
  {:type :fraud-gain
   :amount (long (or amount-wei 0))
   :capturable? (boolean capturable?)
   :note (if capturable?
           "gross value capturable by resolver"
           "gross value controlled but not directly capturable")})

(defn slash-component
  "Return a slash-transfer component map.
   `amount-wei` — value transferred
   `from` — :resolver | :claimant
   `to` — :protocol | :insurance-pool | :other-claimants"
  [amount-wei from to]
  {:type :slash-transfer
   :amount (long (or amount-wei 0))
   :from from
   :to to
   :note (str "slash transfer from " (name from) " to " (name to))})

(defn effort-cost-component
  "Return an effort-cost component map.
   `amount-wei` — imputed cost of resolver effort"
  [amount-wei]
  {:type :effort-cost
   :amount (long (or amount-wei 0))
   :note "imputed cost of resolver effort (investigation, gas, time)"})

;; ---------------------------------------------------------------------------
;; Net payoff calculation
;; ---------------------------------------------------------------------------

(defn net-payoff
  "Compute net payoff from a sequence of component maps.
   Positive components are gains; negative components are losses.
   Returns the net integer amount in wei."
  [components]
  (reduce + 0 (map (fn [c]
                     (let [amt (:amount c 0)]
                       (case (:type c)
                         (:fee :fraud-gain) amt
                         (:bond :slash-transfer) amt
                         (:appeal-cost :capital-cost :effort-cost) (- amt)
                         amt)))
                   components)))

;; ---------------------------------------------------------------------------
;; Canonical terminal-payoff projection
;; ---------------------------------------------------------------------------

(def default-assumptions
  "Default economic assumptions for the payoff projection.
   :fee-retained-on-reversal? — resolver retains fee even if verdict is overturned
   :bond-returned-on-correct? — bond returned if resolver was correct
   :bond-slashed-on-wrong? — bond slashed if resolver was wrong
   :appeal-bond-returned? — appeal bond returned to prevailing party
   :include-capital-cost? — impute opportunity cost of locked capital
   :include-effort-cost? — impute resolver effort cost
   :capital-cost-rate-bps — annual opportunity cost rate in basis points
   :effort-cost-wei — fixed imputed effort cost per dispute
   :fraud-capturable? — resolver can actually extract fraud gain (not just control it)"
  {:fee-retained-on-reversal? false
   :bond-returned-on-correct? true
   :bond-slashed-on-wrong? true
   :appeal-bond-returned? true
   :include-capital-cost? false
   :include-effort-cost? false
   :capital-cost-rate-bps 500
   :effort-cost-wei 0
   :fraud-capturable? false})

(defn resolver-payoff
  "Compute the canonical payoff decomposition for a resolver.
   Returns a map with `:components` (vector of component maps) and `:net` (integer wei).

   Parameters:
   - `resolver-id` — keyword identifying the resolver
   - `fee-wei` — fee earned
   - `bond-wei` — bond posted
   - `slash-multiplier` — slash multiplier applied to bond on detection
   - `appeal-cost-wei` — cost incurred if appealed
   - `verdict-correct?` — was the resolver's verdict correct
   - `appeal-filed?` — was an appeal filed
   - `appeal-upheld?` — did the appeal uphold the original verdict
   - `fraud-upside-wei` — gross value resolver could capture via fraud
   - `fraud-success-rate` — probability fraud succeeds (0.0 to 1.0)
   - `detection-prob` — probability fraud is detected (0.0 to 1.0)
   - `locked-duration-seconds` — how long capital was locked
   - `assumptions` — map of economic assumptions (defaults from `default-assumptions`)"
  [resolver-id fee-wei bond-wei slash-multiplier appeal-cost-wei
   verdict-correct? appeal-filed? appeal-upheld?
   fraud-upside-wei fraud-success-rate detection-prob
   locked-duration-seconds
   & {:keys [assumptions]
      :or {assumptions default-assumptions}}]
  (let [fee-retained? (if verdict-correct?
                        true
                        (:fee-retained-on-reversal? assumptions))
        bond-returned? (and verdict-correct?
                            (:bond-returned-on-correct? assumptions))
        bond-slashed? (and (not verdict-correct?)
                           (:bond-slashed-on-wrong? assumptions))
        slash-amount (if bond-slashed?
                       (econ/calculate-slashing-loss bond-wei slash-multiplier)
                       0)
        capturable? (:fraud-capturable? assumptions)
        include-capital? (:include-capital-cost? assumptions)
        include-effort? (:include-effort-cost? assumptions)
        _components [(fee-component fee-wei fee-retained?)
                     (bond-component bond-wei bond-returned? bond-slashed?)
                     (when (and bond-slashed? (pos? slash-amount))
                       (slash-component slash-amount :resolver :protocol))
                     (when appeal-filed?
                       (appeal-cost-component appeal-cost-wei :resolver))
                     (when (and (pos? fraud-upside-wei) (pos? fraud-success-rate))
                       (fraud-gain-component
                        (long (* fraud-upside-wei fraud-success-rate))
                        capturable?))
                     (when (and (not verdict-correct?) (pos? detection-prob))
                       (slash-component
                        (long (* slash-amount detection-prob))
                        :resolver :protocol))
                     (when include-capital?
                       (capital-cost-component
                        (econ/calculate-fee (* bond-wei (:capital-cost-rate-bps assumptions))
                                            10000)
                        locked-duration-seconds))
                     (when include-effort?
                       (effort-cost-component (:effort-cost-wei assumptions)))]
        components (vec (remove nil? _components))]
    {:resolver-id resolver-id
     :components components
     :net (net-payoff components)}))

;; ---------------------------------------------------------------------------
;; Reconciliation with existing EV formulas
;; ---------------------------------------------------------------------------

(defn honest-ev-from-payoff
  "Compute honest expected value from the canonical payoff model.
   Delegates to `resolver-payoff` with honest-appropriate assumptions."
  [fee-wei appeal-prob-if-correct]
  (let [payoff (resolver-payoff :honest-resolver fee-wei 0 1 0
                                true false false
                                0 0.0 0.0 0)]
    (:net payoff)))

(defn malicious-ev-from-payoff
  "Compute malicious expected value from the canonical payoff model.
   Delegates to `resolver-payoff` with malicious-appropriate assumptions."
  [fee-wei slashing-loss detection-prob
   & {:keys [fraud-upside fraud-success-rate]
      :or {fraud-upside 0 fraud-success-rate 0.0}}]
  (let [payoff (resolver-payoff :malicious-resolver fee-wei 0 1 0
                                false false false
                                fraud-upside fraud-success-rate detection-prob
                                0)]
    (:net payoff)))

(defn lazy-ev-from-payoff
  "Compute lazy expected value from the canonical payoff model.
   Lazy resolver: judges by random signal, fee retained if verdict survives.
   `correct-prob` — probability the lazy resolver happens to be correct.
   `appeal-prob-correct` — probability a correct verdict is appealed.
   `appeal-prob-wrong` — probability an incorrect verdict is appealed."
  [fee-wei correct-prob appeal-prob-correct appeal-prob-wrong]
  (let [survival-prob (+ (* correct-prob (- 1 appeal-prob-correct))
                         (* (- 1 correct-prob) (- 1 appeal-prob-wrong)))
        ;; Lazy resolver: verdict may be correct or incorrect by chance.
        ;; Fee is retained in proportion to survival probability.
        ;; No bond is posted (default), so no slashing risk.
        payoff (resolver-payoff :lazy-resolver
                                (long (* fee-wei survival-prob))
                                0 1 0 true false false 0 0.0 0.0 0)]
    (:net payoff)))

(defn collusive-ev-from-payoff
  "Compute collusive expected value from the canonical payoff model.
   Collusive resolver coordinates with other resolvers to increase fraud
   success probability. `coalition-size` affects detection risk.
   `colluder-gain-rate` — multiplier on fee from coordinated wrong verdicts."
  [fee-wei coalition-size detection-prob-collusion
   & {:keys [colluder-gain-rate]
      :or {colluder-gain-rate nil}}]
  (let [gain-rate (or colluder-gain-rate
                      (/ 1.2 (Math/log (+ 2 coalition-size))))
        effective-detection (min 0.5 detection-prob-collusion)
        fraud-success-rate 0.5  ;; collusion increases fraud success
        fraud-upside (* fee-wei gain-rate)
        payoff (resolver-payoff :collusive-resolver fee-wei 0 1 0
                                false false false
                                fraud-upside fraud-success-rate effective-detection
                                0)]
    (:net payoff)))

;; ---------------------------------------------------------------------------
;; Coalition payoff from canonical model
;; ---------------------------------------------------------------------------

(defn coalition-ev-from-payoff
  "Compute coalition expected value from the canonical payoff model.
   Sums individual resolver payoffs and subtracts coordination costs.

   `member-payoffs` — sequence of {:resolver-id kw :net-payoff long}
   `coordination-cost` — fixed cost deducted from coalition total
   `side-payments` — optional map of {:from kw :to kw :amount long} for
                     internal redistribution

   Returns {:coalition-total long :net-of-costs long :side-payment-adjusted long
            :member-payoffs [{:resolver-id kw :net long :after-side-payment long}]}"
  [member-payoffs & {:keys [coordination-cost side-payments]
                     :or {coordination-cost 0, side-payments []}}]
  (let [gross (reduce + 0 (map :net-payoff member-payoffs))
        net (- gross (long coordination-cost))
        ;; Apply side payments
        initial-map (into {} (map (fn [m] [(:resolver-id m) (:net-payoff m)]) member-payoffs))
        adjusted-map (reduce (fn [acc sp]
                               (let [from-id (:from sp)
                                     to-id (:to sp)
                                     amt (long (:amount sp))
                                     from-bal (- (get acc from-id 0) amt)
                                     to-bal (+ (get acc to-id 0) amt)]
                                 (-> acc
                                     (assoc from-id from-bal)
                                     (assoc to-id to-bal))))
                             initial-map
                             side-payments)
        adjusted-members (mapv (fn [m]
                                 (let [after-sp (get adjusted-map (:resolver-id m) (:net-payoff m))]
                                   {:resolver-id (:resolver-id m)
                                    :net (:net-payoff m)
                                    :after-side-payment after-sp}))
                               member-payoffs)
        side-pay-adjustment (- net (reduce + 0 (map :after-side-payment adjusted-members)))]
    {:coalition-total gross
     :net-of-costs net
     :side-payment-adjusted side-pay-adjustment
     :member-payoffs adjusted-members}))

;; ---------------------------------------------------------------------------
;; Incentive margin (replaces dominance ratio)
;; ---------------------------------------------------------------------------

(defn incentive-margin
  "Compute the incentive margin: U_honest - max(U_deviation).
   Positive margin means honest strategy is strictly preferred.
   Returns {:margin N :honest N :best-deviation N :deviation-type kw :verdict kw}.

   Verdicts:
     :pass       — margin >= 0 (honest is at least as good as any deviation)
     :fail       — margin < 0 (a deviation is strictly better)"
  [& {:keys [honest-ev malicious-ev lazy-ev collusive-ev]
      :or {honest-ev 0, malicious-ev 0, lazy-ev 0, collusive-ev 0}}]
  (let [deviations (cond-> []
                     (some? malicious-ev) (conj {:type :malicious :value malicious-ev})
                     (some? lazy-ev)      (conj {:type :lazy :value lazy-ev})
                     (some? collusive-ev) (conj {:type :collusive :value collusive-ev}))
        best-dev (apply max-key :value deviations)
        margin (- honest-ev (:value best-dev))]
    {:margin margin
     :honest-ev honest-ev
     :best-deviation (:value best-dev)
     :deviation-type (:type best-dev)
     :verdict (if (>= margin 0) :pass :fail)}))

;; ---------------------------------------------------------------------------
;; Secure-region boundaries
;; ---------------------------------------------------------------------------

(defn secure-bond-boundary
  "Minimum bond required for honest EV >= malicious EV, holding other
   parameters fixed. Returns {:min-bond N :parameters {...}}.
   Uses binary search over bond values [0, escrow-wei]."
  [fee-wei escrow-wei detection-prob
   & {:keys [fraud-upside fraud-success-rate precision]
      :or {fraud-upside 0, fraud-success-rate 0.0, precision 1.0}}]
  (let [bond-search (fn [f]
                      (loop [low 0.0 high (double escrow-wei)]
                        (if (< (- high low) precision)
                          (long high)
                          (let [mid (/ (+ low high) 2.0)
                                malicious (malicious-ev-from-payoff
                                           fee-wei (long mid) detection-prob
                                           :fraud-upside fraud-upside
                                           :fraud-success-rate fraud-success-rate)
                                honest (honest-ev-from-payoff fee-wei 0.0)]
                            (if (>= (- honest malicious) 0)
                              (recur low mid)
                              (recur mid high))))))]
    {:min-bond (bond-search nil)
     :parameters {:fee-wei fee-wei :escrow-wei escrow-wei
                  :detection-prob detection-prob
                  :fraud-upside fraud-upside
                  :fraud-success-rate fraud-success-rate}}))

(defn secure-detection-boundary
  "Minimum detection probability required for honest EV >= malicious EV.
   Returns {:min-detection N :parameters {...}}.
   Uses binary search over [0.0, 1.0]."
  [fee-wei bond-wei & {:keys [fraud-upside fraud-success-rate precision]
                       :or {fraud-upside 0, fraud-success-rate 0.0, precision 0.001}}]
  (let [det-search (fn []
                     (loop [low 0.0 high 1.0]
                       (if (< (- high low) precision)
                         (double high)
                         (let [mid (/ (+ low high) 2.0)
                               malicious (malicious-ev-from-payoff
                                          fee-wei bond-wei mid
                                          :fraud-upside fraud-upside
                                          :fraud-success-rate fraud-success-rate)
                               honest (honest-ev-from-payoff fee-wei 0.0)]
                           (if (>= (- honest malicious) 0)
                             (recur low mid)
                             (recur mid high))))))]
    {:min-detection (det-search)
     :parameters {:fee-wei fee-wei :bond-wei bond-wei
                  :fraud-upside fraud-upside
                  :fraud-success-rate fraud-success-rate}}))

;; ---------------------------------------------------------------------------
;; Claimant and Protocol payoff projections
;; ---------------------------------------------------------------------------

(defn claimant-payoff
  "Compute the canonical payoff decomposition for a claimant.
   Claimant receives escrow minus fees; may incur appeal costs.
   Returns {:claimant-id kw :components [...] :net int}."
  [claimant-id escrow-wei fee-wei appeal-cost-wei appeal-filed?]
  (let [net-escrow (- escrow-wei fee-wei)
        components (cond-> [(fee-component fee-wei true)]
                     appeal-filed?
                     (conj (appeal-cost-component appeal-cost-wei :claimant)))]
    {:claimant-id claimant-id
     :components components
     :net net-escrow}))

(defn protocol-payoff
  "Compute the canonical payoff decomposition for the protocol treasury.
   Protocol receives fees, slashing revenue, and appeal bond forfeitures;
   may pay appeal costs.
   Returns {:components [...] :net int}."
  [fee-wei slash-revenue-wei appeal-bond-revenue-wei appeal-cost-wei]
  (let [net (+ fee-wei slash-revenue-wei appeal-bond-revenue-wei (- (or appeal-cost-wei 0)))
        components [(fee-component fee-wei true)
                    (when (pos? (or slash-revenue-wei 0))
                      (slash-component slash-revenue-wei :resolver :protocol))
                    (when (pos? (or appeal-bond-revenue-wei 0))
                      {:type :appeal-bond-revenue
                       :amount appeal-bond-revenue-wei
                       :note "appeal bond forfeited to protocol"})]]
    {:components (vec (remove nil? components))
     :net net}))

;; ---------------------------------------------------------------------------
;; Budget balance and individual rationality checks
;; ---------------------------------------------------------------------------

(defn budget-balance-check
  "Verify that the sum of net payoffs across all participant types equals
   zero (strong budget balance) or is within epsilon of zero.
   If non-zero, reports the imbalance and which participant types contribute.

   `participant-payoffs` — sequence of {:role kw :net int} maps.
   `epsilon` — allowed deviation (default 0, use 1 for integer rounding).

   Returns {:balanced? bool :sum N :imbalance N :participants [...]}"
  [participant-payoffs & {:keys [epsilon] :or {epsilon 0}}]
  (let [total (reduce + 0 (map :net participant-payoffs))
        abs-imbalance (Math/abs (long total))]
    {:balanced? (<= abs-imbalance epsilon)
     :sum (long total)
     :imbalance abs-imbalance
     :participants (vec participant-payoffs)}))

(defn ir-check
  "Individual rationality check: verify that a participant's net payoff
   meets or exceeds the outside-option utility.

   `net-payoff` — participant's net payoff (integer wei).
   `outside-option` — outside option utility (default 0).
   `definition-root` — optional content root binding the exact
   outside-option definition used, for re-verification.

   Returns {:rational? bool :net N :outside-option N :deficit N
            :definition-root (when provided)}"
  [net-payoff & {:keys [outside-option definition-root] :or {outside-option 0}}]
  (let [deficit (- outside-option net-payoff)]
    (cond-> {:rational? (>= net-payoff outside-option)
             :net net-payoff
             :outside-option outside-option
             :deficit (max 0 deficit)}
      definition-root (assoc :definition-root definition-root))))

;; ---------------------------------------------------------------------------
;; Verification
;; ---------------------------------------------------------------------------

(defn verify-payoff-consistency
  "Verify that the canonical payoff model and the existing EV formulas
   produce the same results for a set of test vectors.
   Returns a vector of result maps with :formula, :canonical, :match?, and :delta."
  []
  (let [test-vectors [{:label "honest base"
                       :fn #(econ/honest-expected-value 100 0.3)
                       :canonical-fn #(honest-ev-from-payoff 100 0.3)}
                      {:label "malicious no fraud"
                       :fn #(econ/malicious-expected-value 100 50 0.2)
                       :canonical-fn #(malicious-ev-from-payoff 100 50 0.2)}
                      {:label "malicious with fraud"
                       :fn #(econ/malicious-expected-value 100 50 0.2 500 0.3)
                       :canonical-fn #(malicious-ev-from-payoff 100 50 0.2
                                                                :fraud-upside 500
                                                                :fraud-success-rate 0.3)}
                      {:label "lazy base"
                       :fn #(econ/lazy-expected-value 100 0.8 0.3 0.5)
                       :canonical-fn #(lazy-ev-from-payoff 100 0.8 0.3 0.5)}
                      {:label "collusive base"
                       :fn #(econ/collusive-expected-value 100 2 0.3)
                       :canonical-fn #(collusive-ev-from-payoff 100 2 0.3)}]]
    (mapv (fn [{:keys [label fn canonical-fn]}]
            (let [ev (fn)
                  canonical (canonical-fn)
                  match? (= ev canonical)]
              {:label label
               :existing-ev ev
               :canonical-ev canonical
               :match? match?
               :delta (when (and (number? ev) (number? canonical))
                        (- ev canonical))}))
          test-vectors)))
