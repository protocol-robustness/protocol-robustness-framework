(ns resolver-sim.protocols.sew.economics
  "Sew-specific economic adapters.

   This namespace maps Sew protocol state and policy into generic economics
   functions. Pure arithmetic is delegated to resolver-sim.economics.calculations
   in PRF core. Generic resolver-sim.economics namespaces must not depend on Sew.

   Architecture note:
   Projection artifact creation is owned by the evidence/projection layer.
   This namespace should expose pure allocation helpers that consume
   already-derived allocation input. Do not add world-reading allocation
   wrappers unless/until the projection artifact API is explicitly promoted
   to the primary execution path."
  (:require [resolver-sim.economics.payoffs :as payoffs]
            [resolver-sim.economics.calculations :as core-econ]
            [resolver-sim.economics.slash-distribution :as sd]
            [resolver-sim.pro-rata.allocation :as pro-rata]
            [resolver-sim.pro-rata.evidence :as pro-rata-evidence]))

(def ECONOMIC-POLICIES
  "Recommended Sew parameter bands for governance.
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
  "Calculate the escrow creation fee (delegates to core)."
  [amount fee-bps]
  (core-econ/calculate-bps-amount amount fee-bps))

(defn calculate-appeal-bond-fee
  "Calculate the protocol fee deducted from an appeal bond (delegates to core)."
  [amount fee-bps]
  (core-econ/calculate-bps-fee amount fee-bps))

(defn calculate-challenge-bond-amount
  "Calculate the required Sew challenge bond amount.

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
  "Calculate the challenge bounty from a slash amount (delegates to core)."
  [slash-amount bounty-bps]
  (core-econ/calculate-bounty slash-amount bounty-bps))

(defn calculate-slashing-distribution
  "Calculate distribution for slashed funds (delegates to core with Sew defaults)."
  ([amount bounty]
   (calculate-slashing-distribution amount bounty nil))
  ([amount bounty {:keys [insurance-cut-bps protocol-retained-bps]
                    :or {insurance-cut-bps 5000 protocol-retained-bps 3000}}]
   (core-econ/distribute-slashing-amount
     amount
     {:bounty (or bounty 0)
      :insurance-cut-bps insurance-cut-bps
      :protocol-retained-bps protocol-retained-bps})))

;; ── Sew default slash distribution policy (Phase 2) ─────────────────────

(def sew-default-slash-distribution-policy
  "Sew default slash-distribution-policy.v1: 50/30/20 base split,
   50/50 bounty funding from insurance and protocol, remainder to protocol.

   Canonical artifact: protocols/sew/policies/default-slash-distribution-v1.edn"
  {:schema-version "slash-distribution-policy.v1"
   :policy/id      :sew.policy/default-slash-distribution
   :policy/version 1
   :allocation
   {:method       :weighted
    :scale        10000
    :weights      {:sew.allocation/insurance 5000
                   :sew.allocation/protocol  3000
                   :sew.allocation/retained  2000}
    :remainder-to :sew.allocation/retained}
   :awards
   [{:award/id :sew.award/challenge-bounty
     :amount
     {:method        :rate-of-gross
      :parameter-key :sew.parameter/challenge-bounty-bps
      :scale         10000
      :rounding      :floor}
     :eligibility
     {:trigger                    :sew.trigger/successful-challenge
      :beneficiary-role           :sew.participant/challenger
      :requires-evidence-reference? true}
     :funding
     {:method       :weighted-deduction
      :scale        10000
      :weights      {:sew.allocation/insurance 5000
                     :sew.allocation/protocol  5000}
      :remainder-to :sew.allocation/protocol}
     :settlement
     {:allocation-id   :sew.allocation/challenge-bounty
      :obligation-kind :sew.obligation/challenge-bounty}}]})

(def sew-default-slash-distribution-policy-hash
  "Precomputed hash of sew-default-slash-distribution-policy."
  (sd/policy-hash sew-default-slash-distribution-policy))

(defn build-sew-slash-distribution
  "Build a slash-distribution.v1 artifact using the Sew default policy.

   Arguments:
     slash-amount  — gross slash amount (non-negative integer)
     bounty-bps    — challenge bounty rate in basis points
     & :keys
       :challenger         — participant address (optional, required for non-zero bounty)
       :evidence-reference — eligibility evidence reference (optional)
       :workflow-reference — Sew workflow identifier (optional, for context)
       :insurance-cut-bps  — override base allocation insurance weight (default 5000)
       :protocol-retained-bps  — override base allocation protocol weight (default 3000)

   Returns the raw result from sd/build-slash-distribution:
     {:status :valid, :distribution <artifact>}
     | {:status :invalid, :violations [...]}"
  [slash-amount bounty-bps & {:keys [challenger evidence-reference workflow-reference
                                     insurance-cut-bps protocol-retained-bps]
                               :or {challenger nil evidence-reference nil
                                    insurance-cut-bps 5000 protocol-retained-bps 3000}}]
  (let [retained-bps (- 10000 insurance-cut-bps protocol-retained-bps)
        policy (if (and (= insurance-cut-bps 5000) (= protocol-retained-bps 3000))
                 sew-default-slash-distribution-policy
                 (-> sew-default-slash-distribution-policy
                     (assoc-in [:allocation :weights]
                               {:sew.allocation/insurance insurance-cut-bps
                                :sew.allocation/protocol  protocol-retained-bps
                                :sew.allocation/retained  retained-bps})
                     (assoc :policy/id :sew.policy/dynamic-override)))
        bounty (core-econ/calculate-bounty slash-amount bounty-bps)
        resolved-awards (when (and challenger (pos? bounty))
                          [{:award/id :sew.award/challenge-bounty
                            :eligibility {:trigger :sew.trigger/successful-challenge
                                          :evidence-reference
                                          (or evidence-reference
                                              (str "sew:challenge:" (or workflow-reference "unknown")))}
                            :beneficiary {:participant/id challenger
                                          :participant/role :sew.participant/challenger}}])
        param-ctx {:source-root "sew:live-snapshot"
                   :values {:sew.parameter/challenge-bounty-bps bounty-bps}}]
    (sd/build-slash-distribution
      {:gross-amount slash-amount
       :policy policy
       :parameter-context param-ctx
       :resolved-awards (vec (or resolved-awards []))
       :context {:source-reference (str "slash:" (or workflow-reference "unknown"))}})))

(defn extract-sew-legacy-distribution
  "Extract the legacy {:insurance N :protocol N :retained N} shape
   from a slash-distribution.v1 artifact for parity comparison.

   The bounty amount is the award amount in :distribution/awards.
   It is not returned here — use :distribution/awards directly for
   the full picture."
  [distribution]
  (let [final (:distribution/final-allocations distribution)]
    {:insurance (get final :sew.allocation/insurance 0)
     :protocol  (get final :sew.allocation/protocol 0)
     :retained  (get final :sew.allocation/retained 0)}))

(defn calculate-slashing-distribution-v2
  "Parity-compatible replacement for calculate-slashing-distribution
   using the generic distribution engine and Sew default policy.

   Accepts the same arguments as calculate-slashing-distribution:
     (calculate-slashing-distribution-v2 amount bounty)
     (calculate-slashing-distribution-v2 amount bounty opts)

   Returns the same {:insurance N :protocol N :retained N} shape
   when the distribution is valid. Throws on invalid inputs that
   would be silently accepted by the old function.

   Uses :resolved-amount internally to accept pre-computed bounty
   amounts exactly as the old function did."
  ([amount bounty]
   (calculate-slashing-distribution-v2 amount bounty nil))
  ([amount bounty {:keys [insurance-cut-bps protocol-retained-bps]
                    :or {insurance-cut-bps 5000 protocol-retained-bps 3000}}]
   (let [amount-spec
         {:method        :resolved-amount
          :scale         10000}
         funding-spec
         {:method       :weighted-deduction
          :scale        10000
          :weights      {:sew.allocation/insurance 5000
                         :sew.allocation/protocol  5000}
          :remainder-to :sew.allocation/protocol}
         policy
         {:schema-version "slash-distribution-policy.v1"
          :policy/id      :sew.policy/parity-policy
          :policy/version 1
          :allocation
          {:method       :weighted
           :scale        10000
           :weights      {:sew.allocation/insurance insurance-cut-bps
                          :sew.allocation/protocol  protocol-retained-bps
                          :sew.allocation/retained  (- 10000 insurance-cut-bps protocol-retained-bps)}
           :remainder-to :sew.allocation/retained}
          :awards
          [{:award/id :sew.award/challenge-bounty
            :amount   amount-spec
            :eligibility
            {:trigger                    :sew.trigger/successful-challenge
             :beneficiary-role           :sew.participant/challenger
             :requires-evidence-reference? false}
            :funding  funding-spec
            :settlement
            {:allocation-id   :sew.allocation/challenge-bounty
             :obligation-kind :sew.obligation/challenge-bounty}}]}
         param-ctx {:source-root "sew:parity"
                    :values {}}
         resolved-awards (when (pos? bounty)
                           [{:award/id :sew.award/challenge-bounty
                             :award/amount bounty
                             :eligibility {:trigger :sew.trigger/successful-challenge
                                           :evidence-reference "sew:parity"}
                             :beneficiary {:participant/id :sew/parity-challenger
                                           :participant/role :sew.participant/challenger}}])
         result (sd/build-slash-distribution
                  {:gross-amount amount
                   :policy policy
                   :parameter-context param-ctx
                   :resolved-awards (vec resolved-awards)
                   :context {:source-reference "sew:parity"}})]
     (if (= :valid (:status result))
       (extract-sew-legacy-distribution (:distribution result))
       (throw (ex-info "calculate-slashing-distribution-v2: invalid distribution"
                       {:violations (:violations result)
                        :input {:amount amount :bounty bounty
                                :insurance-cut-bps insurance-cut-bps
                                :protocol-retained-bps protocol-retained-bps}}))))))

(defn calculate-slash-amount-from-basis
  "Calculate a slash amount from slashable stake and bps (delegates to core)."
  [slashable-stake slash-bps]
  (core-econ/calculate-slash-amount slashable-stake slash-bps))

(defn calculate-reversal-slash
  "Calculate a stake-basis reversal slash (delegates to core)."
  [slashable-stake slash-bps]
  (core-econ/calculate-slash-amount slashable-stake slash-bps))

(defn calculate-escrow-cap
  "Compute the maximum escrow amount from stake (delegates to core)."
  ([stake] (core-econ/calculate-capacity-limit stake))
  ([stake multiplier]
   (core-econ/calculate-capacity-limit stake multiplier)))

(defn calculate-sew-slash-allocation
  "Allocate a Sew slash amount across liable parties.

   Sew defaults:
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
         ;; Presentation order remains Sew's supplied liable-party order. The
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

