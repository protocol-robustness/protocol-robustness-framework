(ns resolver-sim.allocation.reconciliation
  "Capacity reconciliation for the allocation kernel.

   ``:allocation.assertion/result-capacity-reconciles`` must not pass merely
   because the global totals happen to balance.  This namespace establishes
   BOTH obligations:

   1. Global reconciliation:
      - total allocated equals the committed capacity;
      - residual capacity is zero where full exhaustion is required.

   2. Per-award reconciliation:
      - every final allocation corresponds to the committed selected-outcome
        allocation for that claim (no claim over/under-allocated while another
        compensates in the global sum);
      - every allocation is an admissible entitlement (zero or the committed
        claim amount) under the all-or-nothing contract;
      - the committed rounding policy is supported and honored (all awards are
        non-negative integers);
      - the verified leaf set recomputes the committed result root.

   Allocation is strictly per-claim under the all-or-nothing contract: a claim is
   either fully allocated or not, and a claimant absent from the selected outcome's
   allocation list is not an error — it maps to zero.  :beneficiary is economic
   attribution only; it does not change per-claim entitlement.

   Reasons:
     :result-total-capacity-mismatch  total allocated != committed capacity
     :result-nonzero-residual         residual capacity != 0
     :result-leaf-set-incomplete      leaves do not cover the claimant set
                                      exactly once
     :result-leaf-order-mismatch      leaves are not in canonical claimant order
     :result-award-mismatch           a leaf allocation != selected-outcome
                                      allocation for that claim
     :result-negative-award           a leaf allocation is negative
     :result-entitlement-mismatch     a leaf allocation is neither zero nor the
                                      committed claim amount
     :result-status-mismatch          a leaf :result-status contradicts its
                                      allocation (allocated iff positive)
     :result-rounding-rule-mismatch   unsupported/declared rounding policy
     :result-root-mismatch            verified leaves recompute a different
                                      result root than committed

   All arithmetic is arbitrary-precision integer arithmetic."
  (:require [clojure.string :as str]
            [resolver-sim.allocation.roots :as roots]))

(def ^:const supported-rounding-policies
  "Rounding/distribution rules the reconciliation can verify are honored."
  #{"floor-to-asset-decimals.v1"})

(defn- normalize-root
  "Strip a 0x prefix for comparison against bare-hex Merkle roots."
  [s]
  (if (and (string? s) (clojure.string/starts-with? s "0x"))
    (subs s 2)
    s))

(defn reconcile
  "Reconcile a result against its committed context.

   opts:
     :context              allocation context
     :selected-outcome     the selected outcome map
     :leaves               the result leaves (canonical claimant ordering)
     :total-allocated      observed total allocated
     :residual-capacity    observed residual capacity
     :committed-result-root  committed result root (0x-hex or nil)
     :rounding-policy      declared rounding policy (string or nil)

   Returns {:ok? bool :reason kw :detail map}.  Fail-closed: the first violated
   obligation in check order is the reported reason."
  [{:keys [context selected-outcome leaves total-allocated residual-capacity
           committed-result-root rounding-policy]}]
  (let [capacity (bigint (:capacity context))
        leaf-ids (mapv :claim/id leaves)
        claimant-ids (mapv :claim/id (:claimants context))
        amount-by-claim (into {} (map (juxt :claim/id :amount)) (:claimants context))
        outcome-alloc (into {} (map (juxt :claim/id :allocated))
                            (:allocations selected-outcome))
        award-mismatch
        (first (keep (fn [leaf]
                       (let [cid (:claim/id leaf)
                             expected (bigint (get outcome-alloc cid 0))
                             final (bigint (:final-allocation leaf))]
                         (when (not= final expected)
                           {:claim/id cid :final-allocation final :expected expected})))
                     leaves))
        negative-award
        (first (keep (fn [leaf]
                       (when (neg? (bigint (:final-allocation leaf)))
                         {:claim/id (:claim/id leaf)
                          :final-allocation (:final-allocation leaf)}))
                     leaves))
        entitlement-mismatch
        (first (keep (fn [leaf]
                       (let [cid (:claim/id leaf)
                             final (bigint (:final-allocation leaf))
                             amount (bigint (get amount-by-claim cid 0))]
                         (when-not (or (zero? final) (= final amount))
                           {:claim/id cid :final-allocation final :amount amount})))
                     leaves))
        status-mismatch
        (first (keep (fn [leaf]
                       (let [final (bigint (:final-allocation leaf))
                             expected (if (pos? final) "allocated" "not-allocated")
                             status (:result-status leaf)]
                         (when (and (some? status) (not= status expected))
                           {:claim/id (:claim/id leaf) :status status :expected expected})))
                     leaves))]
    (cond
      (not= (bigint total-allocated) capacity)
      {:ok? false :reason :result-total-capacity-mismatch
       :detail {:total-allocated (bigint total-allocated) :capacity capacity}}

      (not (zero? (bigint residual-capacity)))
      {:ok? false :reason :result-nonzero-residual
       :detail {:residual-capacity (bigint residual-capacity)}}

      (or (not= (count leaves) (count claimant-ids))
          (not= (count leaf-ids) (count (distinct leaf-ids))))
      {:ok? false :reason :result-leaf-set-incomplete
       :detail {:leaf-count (count leaves) :claimant-count (count claimant-ids)}}

      (not= (set leaf-ids) (set claimant-ids))
      {:ok? false :reason :result-leaf-set-incomplete
       :detail {:leaves leaf-ids :claimants claimant-ids}}

      (not= leaf-ids claimant-ids)
      {:ok? false :reason :result-leaf-order-mismatch
       :detail {:leaf-order leaf-ids :canonical-order claimant-ids}}

      negative-award
      {:ok? false :reason :result-negative-award
       :detail {:negative-leaf negative-award}}

      award-mismatch
      {:ok? false :reason :result-award-mismatch :detail award-mismatch}

      entitlement-mismatch
      {:ok? false :reason :result-entitlement-mismatch :detail entitlement-mismatch}

      status-mismatch
      {:ok? false :reason :result-status-mismatch :detail status-mismatch}

      (and rounding-policy (not (contains? supported-rounding-policies rounding-policy)))
      {:ok? false :reason :result-rounding-rule-mismatch
       :detail {:rounding-policy rounding-policy
                :supported (vec (sort supported-rounding-policies))}}

      (and committed-result-root
           (not= (normalize-root (roots/result-merkle-root leaves))
                 (normalize-root committed-result-root)))
      {:ok? false :reason :result-root-mismatch
       :detail {:recomputed (roots/result-merkle-root leaves)
                :committed committed-result-root}}

      :else
      {:ok? true :reason :ok
       :detail {:leaf-count (count leaves)}})))
