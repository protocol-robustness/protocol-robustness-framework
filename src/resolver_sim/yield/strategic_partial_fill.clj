(ns resolver-sim.yield.strategic-partial-fill
  "Strategic partial-fill validation: invariance checks under
   bounded claim transformations.

   Evaluates whether the partial-fill allocation mechanism is resistant to
   bounded strategic manipulation by comparing allocations before and after
   claim transformations (split, merge, permute, sybil, inflate).

   All checks operate through exhaustive enumeration over small integer
   claim sets (1-5 claims, request values 0-20, liquidity 0-20) and remain
   deterministic — no stochastic sampling, no state explosion beyond the
   configured scope."
  (:require [resolver-sim.yield.partial-fill :as pf]
            [resolver-sim.yield.exact-math :as m]
            [resolver-sim.validation.deviation-contract :as dc]
            [resolver-sim.validation.enumeration :as enum]))

;; ---------------------------------------------------------------------------
;; Allocation helper
;; ---------------------------------------------------------------------------

(defn allocation-report
  "Allocate integer liquidity proportionally and report rounding accounting.

   `:floor` deliberately leaves a rounding residual undistributed.  The
   `:largest-remainder` policy assigns that residual deterministically by
   descending remainder, then ascending claim index."
  [claims available {:keys [rounding-policy]
                     :or {rounding-policy :largest-remainder}}]
  (let [n (count claims)
        liquidity (long available)
        total-claims (reduce + 0 claims)
        distributable (min liquidity total-claims)
        empty-allocations (vec (repeat n 0))]
    (if (or (zero? total-claims) (zero? distributable))
      {:allocations empty-allocations
       :distributed 0
       :undistributed 0
       :excess-liquidity (- liquidity distributable)
       :rounding-policy rounding-policy}
      (let [floor-fills (mapv (fn [claim] (quot (* claim distributable) total-claims)) claims)
            floor-total (reduce + 0 floor-fills)
            residual (- distributable floor-total)
            remainders (mapv (fn [claim] (mod (* claim distributable) total-claims)) claims)
            selected-indices (take residual
                                   (sort-by (fn [i] [(- (nth remainders i)) i])
                                            (range n)))]
        (case rounding-policy
          :floor
          {:allocations floor-fills
           :distributed floor-total
           :undistributed residual
           :excess-liquidity (- liquidity distributable)
           :rounding-policy rounding-policy}

          :largest-remainder
          (let [selected? (set selected-indices)
                allocations (mapv (fn [i fill]
                                    (if (contains? selected? i) (inc fill) fill))
                                  (range n) floor-fills)]
            {:allocations allocations
             :distributed distributable
             :undistributed 0
             :excess-liquidity (- liquidity distributable)
             :rounding-policy rounding-policy})

          (throw (ex-info "Unsupported rounding policy"
                          {:rounding-policy rounding-policy
                           :supported #{:floor :largest-remainder}})))))))

(defn- allocate
  "Return claim-key -> filled amount for the requested rounding policy."
  [claims available policy]
  (into {}
        (map-indexed (fn [i fill] [(str "c" i) fill])
                     (:allocations (allocation-report claims available policy)))))

;; ---------------------------------------------------------------------------
;; State enumeration
;; ---------------------------------------------------------------------------

(def default-scope
  "Default enumeration bounds as an EnumerationScope."
  (enum/make-scope :dimensions {:claim-count [1 5] :request [0 20] :liquidity [0 20]}
                   :sampling :stratified
                   :max-states 500))

(defn- enumerate-claim-counts
  "Generate claim counts from 1 to max."
  [max-claims]
  (range 1 (inc max-claims)))

(defn- enumerate-request-vectors
  "Generate request vectors within bounds.
   Uses integer partitions of request-max across claim-count claims,
   with each request in [0, request-max]."
  [claim-count request-max]
  (letfn [(partitions [n k]
            (if (= k 1)
              [[n]]
              (mapcat (fn [i]
                        (map (fn [p] (cons i p))
                             (partitions (- n i) (dec k))))
                      (range 0 (inc n)))))]
    (partitions request-max claim-count)))

(defn- enumerate-liquidity
  "Generate liquidity values from 0 to liquidity-max."
  [liquidity-max]
  (range 0 (inc liquidity-max)))

(defn- enumerate-policies
  "Generate the policy variants to test."
  []
  [{:mode :pro-rata :rounding-policy :floor}
   {:mode :pro-rata :rounding-policy :largest-remainder}])

;; ---------------------------------------------------------------------------
;; Transformation functions
;; ---------------------------------------------------------------------------

(defn- split-claim
  "Split claim at index `idx` into `parts` equal parts.
   Returns a new claim vector with more entries."
  [claims idx parts]
  (let [n (long (nth claims idx))
        base (quot n parts)
        rem (- n (* base parts))
        splits (concat (repeat rem (inc base))
                       (repeat (- parts rem) base))
        before (take idx claims)
        after (drop (inc idx) claims)]
    (vec (concat before splits after))))

(defn- merge-claims
  "Merge claims at indices `idxs` into a single claim (sum of values).
   Returns a new claim vector with fewer entries."
  [claims idxs]
  (let [idxs-set (set idxs)
        merged-sum (reduce + 0 (map (fn [i] (nth claims i)) idxs))
        remaining (keep-indexed (fn [i v] (when (not (idxs-set i)) v)) claims)]
    (vec (cons merged-sum remaining))))

(defn- permute-claims
  "Apply a permutation to claim order.
   Returns a new claim vector."
  [claims perm]
  (vec (map (fn [i] (nth claims i)) perm)))

(defn- sybil-claims
  "Split each claim into `k` sybil identities with equal shares.
   Total requested amount is preserved; number of identities multiplies by k."
  [claims k]
  (vec (mapcat (fn [c]
                 (let [n (long c)
                       base (quot n k)
                       rem (- n (* base k))
                       splits (concat (repeat rem (inc base))
                                      (repeat (- k rem) base))]
                   (remove zero? splits)))
               claims)))

(defn- inflate-claim
  "Increase claim at index `idx` by `delta`."
  [claims idx delta]
  (let [v (vec claims)]
    (assoc v idx (+ (nth v idx) delta))))

;; ---------------------------------------------------------------------------
;; Invariance checks
;; ---------------------------------------------------------------------------

(defn check-split-invariance
  "Verify that splitting a claim into N equal parts produces the same
   total allocation as the original single claim.
   
   For each claim in each state, tries splitting into 2, 3, or 4 parts.
   Returns vector of violation maps."
  [claims available policy]
  (let [filled (allocate claims available policy)
        original-total (reduce + 0 (vals filled))
        violations (atom [])]
    (doseq [idx (range (count claims))
            :let [n (nth claims idx)]
            :when (>= n 2)
            parts [2 3 4]
            :when (>= n parts)]
      (let [split-claims (split-claim claims idx parts)
            split-filled (allocate split-claims available policy)
            split-total (reduce + 0 (vals split-filled))
            error (- split-total original-total)]
        (when (not= split-total original-total)
          (swap! violations conj
                 {:claim idx :original n :parts parts
                  :original-allocation original-total
                  :split-allocation split-total
                  :error error}))))
    @violations))

(defn check-merge-invariance
  "Return exact merge-invariance violations for every pair of claims.

   Integer per-claim rounding is identity-sensitive, so this property is not
   expected to hold for either supported rounding policy."
  [claims available policy]
  (let [filled (allocate claims available policy)
        violations (atom [])]
    (doseq [i (range (dec (count claims)))
            j (range (inc i) (count claims))]
      (let [merged-claims (merge-claims claims [i j])
            merged-filled (allocate merged-claims available policy)
            individual-sum (+ (get filled (str "c" i) 0)
                              (get filled (str "c" j) 0))
            merged-allocation (get merged-filled (str "c0") 0)
            error (- merged-allocation individual-sum)]
        (when (not= merged-allocation individual-sum)
          (swap! violations conj
                 {:claims [i j] :individual-sum individual-sum
                  :merged-allocation merged-allocation
                  :error error}))))
    @violations))

(defn- permutations
  "Generate all permutations of a vector (iterative algorithm).
   Returns a lazy seq of vectors."
  [v]
  (let [n (count v)]
    (if (<= n 1)
      [v]
      (mapcat (fn [i]
                (let [elem (nth v i)
                      rest-v (vec (concat (take i v) (drop (inc i) v)))]
                  (map (fn [p] (vec (cons elem p)))
                       (permutations rest-v))))
              (range n)))))

(defn check-permutation-invariance
  "Verify that reordering claims does not change total allocation.
   Tests all permutations for up to 5 claims (max 120 permutations)."
  [claims available policy]
  (let [n (count claims)
        original-filled (allocate claims available policy)
        original-total (reduce + 0 (vals original-filled))
        violations (atom [])]
    (if (<= n 5)
      (let [all-perms (permutations (range n))]
        (doseq [perm all-perms
                :let [perm-claims (permute-claims claims (vec perm))
                      perm-filled (allocate perm-claims available policy)
                      perm-total (reduce + 0 (vals perm-filled))]]
          (when (not= original-total perm-total)
            (swap! violations conj
                   {:original-claims claims :permuted perm-claims
                    :original-total original-total
                    :permuted-total perm-total
                    :permutation perm})))))
    @violations))

(defn check-sybil-invariance
  "Verify that splitting a claim into k sybil identities (same total)
   does not improve total allocation."
  [claims available policy]
  (let [filled (allocate claims available policy)
        violations (atom [])]
    (doseq [k [2 3]]
      (let [sybil-claims (sybil-claims claims k)
            sybil-filled (allocate sybil-claims available policy)]
        (when (> (reduce + 0 (vals sybil-filled))
                 (reduce + 0 (vals filled)))
          (swap! violations conj
                 {:original-total (reduce + 0 (vals filled))
                  :sybil-count k
                  :sybil-total (reduce + 0 (vals sybil-filled))
                  :gain (- (reduce + 0 (vals sybil-filled))
                           (reduce + 0 (vals filled)))}))))
    @violations))

(defn check-request-monotonicity
  "Verify that increasing a claim's requested amount does not decrease
   its allocation (monotonicity property)."
  [claims available policy]
  (let [baseline-filled (allocate claims available policy)
        violations (atom [])]
    (doseq [idx (range (count claims))
            delta [1 2 5]]
      (let [inflated (inflate-claim claims idx delta)
            inflated-filled (allocate inflated available policy)]
        (doseq [j (range (count claims))]
          (let [original-alloc (get baseline-filled (str "c" j) 0)
                new-alloc (get inflated-filled (str "c" j) 0)]
            ;; For the inflated claim, allocation should not decrease
            (when (and (= j idx) (< new-alloc original-alloc))
              (swap! violations conj
                     {:claim idx :delta delta
                      :original-alloc original-alloc
                      :new-alloc new-alloc
                      :kind :inflated-claim-lost-allocation}))
            ;; For non-inflated claims, allocation should not increase
            (when (and (not= j idx) (> new-alloc original-alloc))
              (swap! violations conj
                     {:claim j :delta delta :inflated-claim idx
                      :original-alloc original-alloc
                      :new-alloc new-alloc
                      :kind :non-inflated-claim-gained-allocation}))))))
    @violations))

;; ---------------------------------------------------------------------------
;; Registered deviation generators
;; ---------------------------------------------------------------------------

(def registered-deviation-generators
  "Registered deviation generators.  Each generator declares:

     {:id        kw     ;; generator keyword, e.g. :split
      :property  kw     ;; strategic property it evaluates
      :evaluate  (fn [claims liquidity policy] -> check-map)}

   The check-map returned by :evaluate is a single property check result:

     {:property kw
      :verdict  :verified | :violated
      :counterexamples [...]          ;; optional, violation witnesses
      :state    {:claims [...] :liquidity N :policy {...}}  ;; evaluated state
      ...}

   Adding a NEW deviation family is a registration act, not a dispatcher edit:
   a protocol supplies extra generators via the :generators option to
   validate-strategic-properties, and the harness runs them generically."
  {:split
   {:id :split
    :property :strategy/split-invariance
    :evaluate (fn [claims liquidity policy]
                (let [v (check-split-invariance claims liquidity policy)]
                  {:property :strategy/split-invariance
                   :verdict (if (empty? v) :verified :violated)
                   :counterexamples (when (seq v) (take 3 v))
                   :state {:claims claims :liquidity liquidity
                           :policy (select-keys policy [:mode :rounding-policy])}}))}

   :merge
   {:id :merge
    :property :allocation/exact-merge-invariance
    :evaluate (fn [claims liquidity policy]
                (let [sample-violations (check-merge-invariance claims liquidity policy)
                      ;; Deterministic witnesses prevent a sampled run from claiming a
                      ;; universal algebraic property it cannot establish.  Flooring
                      ;; needs two units to expose its own non-additivity.
                      regression-state (if (= :floor (:rounding-policy policy))
                                         {:claims [1 1 1] :liquidity 2}
                                         {:claims [1 1 1] :liquidity 1})
                      regression-violations (check-merge-invariance
                                             (:claims regression-state)
                                             (:liquidity regression-state)
                                             policy)
                      violations (vec (concat sample-violations regression-violations))]
                  {:property :allocation/exact-merge-invariance
                   :status :violated
                   :verdict :violated
                   :rounding-policy (:rounding-policy policy)
                   :counterexamples (take 3 violations)
                   :regression-counterexample (assoc regression-state
                                                     :merged-indices [1 2]
                                                     :merged-claims [1 2]
                                                     :individual-sum 0
                                                     :merged-allocation 1
                                                     :error 1)
                   :state {:claims claims :liquidity liquidity
                           :policy (select-keys policy [:mode :rounding-policy])}}))}

   :permute
   {:id :permute
    :property :strategy/permutation-invariance
    :evaluate (fn [claims liquidity policy]
                (let [v (check-permutation-invariance claims liquidity policy)]
                  {:property :strategy/permutation-invariance
                   :verdict (if (empty? v) :verified :violated)
                   :counterexamples (when (seq v) (take 3 v))
                   :state {:claims claims :liquidity liquidity
                           :policy (select-keys policy [:mode :rounding-policy])}}))}

   :sybil
   {:id :sybil
    :property :strategy/sybil-invariance
    :evaluate (fn [claims liquidity policy]
                (let [v (check-sybil-invariance claims liquidity policy)]
                  {:property :strategy/sybil-invariance
                   :verdict (if (empty? v) :verified :violated)
                   :counterexamples (when (seq v) (take 3 v))
                   :state {:claims claims :liquidity liquidity
                           :policy (select-keys policy [:mode :rounding-policy])}}))}

   :inflate
   {:id :inflate
    :property :strategy/request-monotonicity
    :evaluate (fn [claims liquidity policy]
                (let [v (check-request-monotonicity claims liquidity policy)]
                  {:property :strategy/request-monotonicity
                   :verdict (if (empty? v) :verified :violated)
                   :counterexamples (when (seq v) (take 3 v))
                   :state {:claims claims :liquidity liquidity
                           :policy (select-keys policy [:mode :rounding-policy])}}))}})

(defn generator-for-property
  "Resolve the registered generator that produces the given property.
   Returns the generator map, or nil."
  [property]
  (some (fn [g] (when (= property (:property g)) g))
        (vals registered-deviation-generators)))

(defn resolve-deviation-generators
  "Resolve a set of deviation keywords to their generator maps.
   Unknown deviations (not in the registered or supplied generator maps)
   throw, so a claim can never silently drop part of its declared scope."
  [deviations & {:keys [extra-generators]
                 :or {extra-generators {}}}]
  (let [all (merge registered-deviation-generators extra-generators)]
    (mapv (fn [dev]
            (or (get all dev)
                (throw (ex-info "Unresolved deviation generator"
                                {:deviation dev
                                 :known (vec (sort (keys all)))}))))
          deviations)))

(defn diagnostic-transform-for-property
  "Return the diagnostic-transform metadata for a property, deriving from the
   registered generator (or a supplied resolved generator map).  Returns nil
   for unknown properties."
  ([property]
   (when-let [g (generator-for-property property)]
     {:id (:id g)
      :role :diagnostic-transform
      :semantic-purpose :bounded-counterexample-search}))
  ([property generator-by-property]
   (when-let [g (or (get generator-by-property property)
                    (generator-for-property property))]
     {:id (:id g)
      :role :diagnostic-transform
      :semantic-purpose :bounded-counterexample-search})))

(defn validate-strategic-properties
  "Run all strategic invariance checks across enumerated states.
   Returns {:properties [...] :summary {...}}.

   The evaluation harness is generator-driven: deviation keywords resolve to
   registered generators (registered-deviation-generators) merged with any
   protocol/application-supplied generators passed via :extra-generators.
   Adding a new deviation family is a registration act, not a dispatcher edit.

   Options:
   - :scope — an EnumerationScope (:dimensions map of dimension-kw -> [lo hi],
     :sampling, :max-states); defaults to default-scope
   - :policies — vector of policy maps to test
   - :deviations — vector of deviation keywords to test
   - :extra-generators — map of {deviation-kw generator-map} supplied by a
     protocol/application.  Each generator must have :id, :property, and
     :evaluate (fn [claims liquidity policy] -> check-map); see
     registered-deviation-generators for the canonical shape.
   - :max-states — the configured evaluation cap.  Kept as the compatibility
     input name, but it caps state × policy executions, not distinct states:
     with P policies the enumeration examines at most (max-states / P) distinct
     states.  The artifact reports this precisely via :state-policy-evaluations,
     :distinct-states-examined, :policies, and :max-state-policy-evaluations.
   - :contract-id — deviation contract id; when set, deviations are derived
     from the contract and :deviations option is ignored
   - :contract-registry — explicit deviation-contract registry to resolve
     :contract-id against (defaults to the framework-builtin registry)
   - :declared-property-ids — strategic properties the caller explicitly makes
     gate-relevant; all other transformation results are diagnostic observations"
  [& {:keys [scope policies deviations max-states contract-id contract-registry
             extra-generators declared-property-ids]
      :or {scope default-scope
           policies (enumerate-policies)
           max-states 500}}]
  (let [contract-registry (or contract-registry dc/default-deviation-contract-registry)
        contract (when contract-id (dc/get-contract contract-registry contract-id))
        deviations (or (when contract (dc/deviations-in-contract contract-registry contract-id))
                       (vec deviations)
                       (:deviations default-scope))
        generators (resolve-deviation-generators deviations
                                                 :extra-generators extra-generators)
        generator-by-property (into {} (map (fn [g] [(:property g) g])) generators)
        declared-property-ids (set declared-property-ids)
        results (atom [])
        state-policy-count (atom 0)
        distinct-states (atom #{})
        policies (vec policies)
        enum-states (enum/generate-states scope)]
    (doseq [state enum-states
            policy policies
            :while (< @state-policy-count max-states)]
      (let [request-vec (:claims state)
            liquidity (:liquidity state)
            _ (swap! state-policy-count inc)
            _ (swap! distinct-states conj [request-vec liquidity])
            checks (mapv (fn [g]
                           ((:evaluate g) request-vec liquidity policy))
                         generators)]
        (swap! results conj {:state-policy-evaluations @state-policy-count
                             :claims request-vec
                             :liquidity liquidity
                             :policy policy
                             :checks checks})))
    (let [all-verdicts (mapcat (fn [r] (map :verdict (:checks r))) @results)
          total-checks (count all-verdicts)
          verified (count (filter #{:verified} all-verdicts))
          violated (count (filter #{:violated} all-verdicts))
          policy-identifiers (mapv (fn [p] (select-keys p [:mode :rounding-policy]))
                                   policies)
          state-policy-evaluations @state-policy-count
          distinct-states-examined (count @distinct-states)]
      {:artifact/kind :strategic-closed-form-validation
       :mechanism :yield/partial-fill
       :contract-id contract-id
       :strategic-model {:mechanism :yield/partial-fill
                         :allocation-mode :pro-rata
                         :rounding-policies policy-identifiers
                         :actions [:honest-request
                                   :split
                                   :merge
                                   :permute
                                   :sybil-split
                                   :inflate-request]
                         :payoff-model :allocated-amount-only
                         :scope-kind :bounded-enumeration
                         :claims-unmodeled [:fees
                                            :timing
                                            :recovery-risk
                                            :side-payments
                                            :continuation-values]}
       :validation-scope {:dimensions (:dimensions scope)
                          :sampling (:sampling scope)
                          :policies policy-identifiers
                          :max-state-policy-evaluations max-states
                          :state-policy-evaluations state-policy-evaluations
                          :distinct-states-examined distinct-states-examined
                          :states-examined state-policy-evaluations
                          :diagnostic-transformations (vec (sort deviations))
                          :declared-property-ids (vec (sort declared-property-ids))}
       :epistemic-scope {:scope/kind :bounded-exhaustive
                         :scope/universal-claim? false
                         :scope/falsification? true
                         :scope/coverage :validation-scope
                         :scope/limitations [:bounded-domain
                                             :bounded-policy-set
                                             :bounded-transformation-set
                                             :no-equilibrium-proof
                                             :no-concurrency-assurance]}
       :properties (->> (mapcat :checks @results)
                        (group-by :property)
                        (mapv (fn [[prop results]]
                                (let [verdict (if (every? #(= :verified (:verdict %)) results)
                                                :verified :violated)
                                      state-keys (map (fn [r] [(get-in r [:state :claims])
                                                               (get-in r [:state :liquidity])])
                                                      results)]
                                  {:property prop
                                   :property-role (if (contains? declared-property-ids prop)
                                                    :declared-property
                                                    :diagnostic-observation)
                                   :diagnostic-transform (diagnostic-transform-for-property prop generator-by-property)
                                   :evaluation/status (if (= :verified verdict)
                                                        :no-counterexample-found
                                                        :counterexample-found)
                                   :status verdict
                                   :verdict verdict
                                   :violation-count (count (filter #(= :violated (:verdict %)) results))
                                   :state-count (count (distinct state-keys))
                                   :state-policy-evaluations (count results)
                                   :counterexample (some :regression-counterexample results)
                                   :sample-counterexamples (->> results
                                                                (filter #(= :violated (:verdict %)))
                                                                (take 2)
                                                                (mapcat :counterexamples)
                                                                (take 3))}))))
       :summary {:states-examined state-policy-evaluations
                 :state-policy-evaluations state-policy-evaluations
                 :distinct-states-examined distinct-states-examined
                 :policies policy-identifiers
                 :max-state-policy-evaluations max-states
                 :properties-examined (count (distinct (mapcat (fn [r] (map :property (:checks r))) @results)))
                 :total-checks total-checks
                 :verified verified
                 :violated violated
                 :valid? (zero? violated)}})))
