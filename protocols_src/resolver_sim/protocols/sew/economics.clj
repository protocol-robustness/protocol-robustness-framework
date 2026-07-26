(ns resolver-sim.protocols.sew.economics
  "Sew-specific economic adapters.

   This namespace maps SEW protocol state and policy into generic economics
   functions. Generic resolver-sim.economics namespaces must not depend on SEW.

   Architecture note:
   Projection artifact creation is owned by the evidence/projection layer.
   This namespace should expose pure allocation helpers that consume
   already-derived allocation input. Do not add world-reading allocation
   wrappers unless/until the projection artifact API is explicitly promoted
   to the primary execution path."
  (:require [resolver-sim.economics.payoffs :as payoffs]
            [resolver-sim.pro-rata.allocation :as pro-rata]
            [resolver-sim.pro-rata.evidence :as pro-rata-evidence]))

(def ECONOMIC-POLICIES
  "Recommended SEW parameter bands for governance.
   Conservative: launch-ready, fully/mostly bond-backed.
   Balanced: growth phase, partially bond-backed.
   Aggressive: research/testing."
  {:conservative {:capacity-multiplier 1.0
                  :insurance-cut-bps  8000
                  :alpha-bps          500}
   :balanced     {:capacity-multiplier 1.5
                  :insurance-cut-bps  5000
                  :alpha-bps          1000}
   :aggressive   {:capacity-multiplier 4.0
                  :insurance-cut-bps  2000
                  :alpha-bps          3000}})

(defn calculate-escrow-fee
  "Calculate the SEW escrow creation fee."
  [amount fee-bps]
  (payoffs/calculate-bps-amount amount fee-bps))

(defn calculate-appeal-bond-fee
  "Calculate the SEW protocol fee deducted from an appeal bond."
  [amount fee-bps]
  (payoffs/calculate-net-after-bps-fee amount fee-bps))

(defn calculate-challenge-bond-amount
  "Calculate the required SEW challenge bond amount.

   Priority:
   1. :challenge-bond-bps > 0 => bps of amount-after-fee
   2. :appeal-bond-amount > 0 => absolute value
   3. otherwise => 0"
  [amount-after-fee snap]
  (cond
    (pos? (:challenge-bond-bps snap 0))
    (payoffs/calculate-bps-amount amount-after-fee (:challenge-bond-bps snap))

    (pos? (:appeal-bond-amount snap 0))
    (:appeal-bond-amount snap)

    :else 0))

(defn calculate-appeal-bond-amount
  "Calculate the required Sew appeal bond amount."
  [amount-after-fee snap]
  (cond
    (pos? (:appeal-bond-amount snap 0))
    (:appeal-bond-amount snap)

    (and (number? amount-after-fee)
         (pos? amount-after-fee)
         (pos? (:appeal-bond-bps snap 0)))
    (payoffs/calculate-bps-amount amount-after-fee (:appeal-bond-bps snap 0))

    :else 0))

(defn calculate-bounty
  "Calculate the SEW challenge bounty from a slash amount."
  [slash-amount bounty-bps]
  (if (pos? bounty-bps)
    (payoffs/calculate-bps-amount slash-amount bounty-bps)
    0))

(defn calculate-slashing-distribution
  "Calculate SEW distribution for slashed funds with optional governance overrides."
  ([amount bounty]
   (calculate-slashing-distribution amount bounty nil))
  ([amount bounty {:keys [insurance-cut-bps protocol-retained-bps]
                   :or {insurance-cut-bps 5000
                        protocol-retained-bps 3000}}]
   (let [insurance (payoffs/calculate-bps-amount amount insurance-cut-bps)
         protocol (payoffs/calculate-bps-amount amount protocol-retained-bps)
         retained (- amount insurance protocol)
         bounty-from-insurance (quot bounty 2)
         bounty-from-protocol (- bounty bounty-from-insurance)]
     {:insurance (- insurance bounty-from-insurance)
      :protocol (- protocol bounty-from-protocol)
      :retained retained})))

(defn calculate-slash-amount-from-basis
  "Calculate a Sew slash amount from slashable stake and bps."
  [slashable-stake slash-bps]
  (payoffs/calculate-bps-amount slashable-stake slash-bps))

(defn calculate-reversal-slash
  "Calculate a Sew stake-basis reversal slash."
  [slashable-stake slash-bps]
  (calculate-slash-amount-from-basis slashable-stake slash-bps))

(defn calculate-escrow-cap
  "Compute the maximum escrow amount a Sew resolver can handle from stake."
  ([stake] (calculate-escrow-cap stake 1.0))
  ([stake multiplier]
   (payoffs/calculate-capacity-limit stake multiplier)))

(defn calculate-sew-slash-allocation
  "Allocate a Sew slash amount across liable parties.

   SEW defaults:
   - weight/basis: :slashable-stake
   - cap: :available-slashable
   - unmet policy: :record-only

   Returns the historical Sew-shaped allocation map for compatibility with
   evidence builders and call sites."
  [{:keys [slash-amount slash-obligation liable-parties slash-policy basis cap-field unmet-policy]
    :or {basis :slashable-stake
         cap-field :available-slashable
         unmet-policy :record-only}}]
  (let [amount (or slash-amount slash-obligation 0)
        total-basis (reduce + 0 (map #(max 0 (long (or (basis %) 0))) liable-parties))]
    (if (zero? total-basis)
      {:status :no-liable-basis
       :basis basis
       :cap-field cap-field
       :unmet-policy unmet-policy
       :slash-policy slash-policy
       :slash-obligation amount
       :total-basis 0
       :recovered-total 0
       :unmet-total amount
       :allocations []}
      (let [rows (mapv (fn [party]
                         (let [basis-amount (max 0 (long (or (basis party) 0)))
                               cap-raw (cap-field party)
                               cap (when (some? cap-raw) (max 0 (long cap-raw)))]
                           {:row/id [:sew-slash-row (:id party)]
                            :obligation/id (:id party)
                            :requested amount
                            :weight basis-amount
                            :cap cap}))
                       liable-parties)
            generic (pro-rata/allocate
                     {:schema-version "pro-rata-allocation-request.v1"
                      :mechanism/version 1
                      :allocation/id [:sew-slash-allocation amount]
                      :available amount
                      :rows rows
                      :rounding-policy :largest-remainder
                      :tie-break-policy :canonical-row-id
                      :redistribution-policy :unallocated})
            mechanism-evidence (pro-rata-evidence/mechanism-evidence-artifact generic)
            by-row-id (into {} (map (juxt :row/id identity) (:rows generic)))
            allocations (mapv (fn [party]
                                (let [basis-amount (max 0 (long (or (basis party) 0)))
                                      cap-raw (cap-field party)
                                      cap (when (some? cap-raw) (max 0 (long cap-raw)))
                                      allocation (get by-row-id [:sew-slash-row (:id party)])]
                                  {:id (:id party)
                                   :basis-amount basis-amount
                                   :share (if (pos? total-basis)
                                            (/ basis-amount total-basis)
                                            0)
                                   :owed (+ (:allocated allocation) (:unmet allocation))
                                   :paid (:allocated allocation)
                                   :unmet (:unmet allocation)
                                   :cap cap
                                   :ended-at (:ended-at party)}))
                              liable-parties)]
        {:basis basis
         :cap-field cap-field
         :unmet-policy unmet-policy
         :slash-policy slash-policy
         :slash-obligation amount
         :total-basis total-basis
         :recovered-total (:allocated-total generic)
         :unmet-total (reduce + 0 (map :unmet allocations))
         ;; Presentation order remains SEW's supplied liable-party order. The
         ;; complete canonical mechanism witness is retained separately.
         :mechanism/evidence mechanism-evidence
         :mechanism/evidence-reference (pro-rata-evidence/evidence-reference mechanism-evidence)
         :allocations allocations}))))

(defn build-sew-slash-projection-artifact
  "Build a passive projection artifact from the same Sew slash allocation input.
   This is additive and does not change calculate-sew-slash-allocation.

   Optional world-state provenance keys (:world-before-hash, :action-hash-at)
   are included in the source when provided."
  [{:keys [slash-amount slash-obligation liable-parties slash-policy
           basis cap-field unmet-policy ended-at
           world-before-hash action-hash-at
           source metadata]
    :or {basis :slashable-stake
         cap-field :available-slashable
         unmet-policy :record-only}}]
  (let [amount (or slash-amount slash-obligation 0)]
    (payoffs/build-projection-artifact
     {:amount amount
      :items liable-parties
      :id-fn :id
      :weight-fn basis
      :cap-fn cap-field
      :rounding :floor-with-largest-remainder
      :remainder-policy :unallocated
      :ordering-policy :canonical-id}
           {:source (merge {:type :allocation-input
                      :basis basis
                      :cap-field cap-field
                      :unmet-policy unmet-policy
                      :slash-policy slash-policy}
                     (when ended-at
                       {:ended-at ended-at})
                     (when world-before-hash
                       {:world-before-hash world-before-hash})
                     (when action-hash-at
                       {:action-hash-at action-hash-at})
                     (or source {}))
      :metadata metadata})))

(defn calculate-sew-slash-allocation-from-projection
  "Return the historical Sew allocation shape from a projection artifact.
   This is a shadow path for comparing against calculate-sew-slash-allocation;
   call sites should continue using the current function until replacement is explicit."
  [artifact]
  (let [generic (payoffs/calculate-prorata-from-projection artifact)
        total-basis (get-in artifact [:summary :total-weight] 0)
        amount (:total-requested generic)
        basis (get-in artifact [:source :basis] :slashable-stake)
        cap-field (get-in artifact [:source :cap-field] :available-slashable)
        unmet-policy (get-in artifact [:source :unmet-policy] :record-only)
        slash-policy (get-in artifact [:source :slash-policy])
        ended-at (get-in artifact [:source :ended-at])]
    (if (zero? total-basis)
      {:status :no-liable-basis
       :basis basis
       :cap-field cap-field
       :unmet-policy unmet-policy
       :slash-policy slash-policy
       :slash-obligation amount
       :total-basis 0
       :recovered-total 0
       :unmet-total amount
       :allocations []}
      {:basis basis
       :cap-field cap-field
       :unmet-policy unmet-policy
       :slash-policy slash-policy
       :slash-obligation amount
       :total-basis total-basis
       :recovered-total (:total-allocated generic)
       :unmet-total (:total-unmet generic)
       :allocations (mapv (fn [{:keys [id weight allocated unmet cap]}]
                            {:id id
                             :basis-amount weight
                             :share (if (pos? total-basis)
                                      (/ weight total-basis)
                                      0)
                             :owed (+ allocated unmet)
                             :paid allocated
                             :unmet unmet
                             :cap cap
                             :ended-at ended-at})
                          (:allocations generic))})))

