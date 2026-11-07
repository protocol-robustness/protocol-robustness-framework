(ns resolver-sim.protocols.sew.pro-rata.economics
  "Sew-specific adapter between slash economics and canonical pro-rata evaluation.

   Kept separate from the general Sew economic helpers so the pro-rata
   integration can be composed and qualified independently."
  (:require [resolver-sim.pro-rata.evaluation :as pro-rata-evaluation]))

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
             allocate (requiring-resolve 'resolver-sim.pro-rata.allocation/allocate)
             mechanism-evidence-artifact
             (requiring-resolve 'resolver-sim.pro-rata.evidence/mechanism-evidence-artifact)
             evidence-reference
             (requiring-resolve 'resolver-sim.pro-rata.evidence/evidence-reference)
             generic (allocate
                     {:schema-version "pro-rata-allocation-request.v1"
                      :mechanism/version 1
                      :allocation/id [:sew-slash-allocation amount]
                      :available amount
                      :rows rows
                      :rounding-policy :largest-remainder
                      :tie-break-policy :canonical-row-id
                      :redistribution-policy :unallocated})
             mechanism-evidence (mechanism-evidence-artifact generic)
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
          :mechanism/evidence-reference (evidence-reference mechanism-evidence)
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
    (pro-rata-evaluation/build-projection-artifact
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
  (let [generic (pro-rata-evaluation/calculate-prorata-from-projection artifact)
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
