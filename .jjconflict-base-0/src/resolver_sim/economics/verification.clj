(ns resolver-sim.economics.verification
  "Generic independent verification of weighted-proportionality allocations.

   Provides the core reconstruction and verification logic shared between
   resolver-sim.economics.payoffs and resolver-sim.pro-rata.exact-verifier.
   This namespace is protocol-agnostic: it reconstructs the mathematically
   expected allocation from a canonical request and compares a claimed result.

   The allocator (payoffs / pro-rata.allocation) computes shares; this
   namespace MUST NOT call either. It reconstructs using its own decomposition:

     canonical request
       -> single-pass expectation          Hare floors + largest-remainder, cap clamp
       -> redistribution-round expectation cap-commitment active set to fixed point
       -> fixed-point expected result      final slices + committed, totals
       -> compare claimed result           independent of producer output")

(def supported-policies
  "The exact policy domain this verifier implements. Coverage :complete is only
   truthful over this domain; a request outside it returns :unsupported rather
   than a silently narrower verdict."
  {:rounding #{:floor :floor-with-largest-remainder}
   :cap-treatment #{:unallocated :redistribute}
   :ordering #{:input-order :canonical-id}})

(defn- unsupported-policy-dims
  "Map of [policy-dim value] entries outside `supported-policies`, or {} when the
   request is fully supported."
  [{:keys [rounding ordering-policy cap-treatment]}]
  (into {}
        (keep (fn [[dim value]]
                (when-not (contains? (get supported-policies dim) value)
                  [dim value])))
        {:rounding (or rounding :floor-with-largest-remainder)
         :ordering (or ordering-policy :input-order)
         :cap-treatment (or cap-treatment :unallocated)}))

;; ---------------------------------------------------------------------------
;; single-pass expectation
;; ---------------------------------------------------------------------------

(defn- normalize-items
  "Tag canonical participants ({:id :weight :cap}) with :idx in input order."
  [items]
  (mapv (fn [idx item]
          {:idx idx
           :id (:id item)
           :weight (long (or (:weight item) 0))
           :cap (some-> (:cap item) long)})
        (range) (vec items)))

(defn- tie-key-for
  [ordering-policy]
  (if (= ordering-policy :canonical-id) :id :idx))

(defn- single-pass-requested
  "Expected pre-cap integer request per item index (Hare floors, then
   largest-remainder units by descending remainder, tie-broken per ordering).
   Returns {idx -> integer}."
  [{:keys [amount items rounding ordering-policy]}]
  (let [total-weight (reduce + 0 (map :weight items))
        denominator (max 1 total-weight)
        rows (mapv (fn [{:keys [idx id weight]}]
                     {:idx idx :id id
                      :floor (quot (* amount weight) denominator)
                      :remainder (mod (* amount weight) denominator)})
                   items)
        floor-sum (reduce + 0 (map :floor rows))
        shortage (if (= rounding :floor-with-largest-remainder)
                   (- amount floor-sum)
                   0)
        awarded (->> rows
                     (sort-by (juxt (comp - :remainder) (tie-key-for ordering-policy)))
                     (take shortage)
                     (map :idx)
                     (set))]
    (into {}
          (map (fn [{:keys [idx floor]}]
                 [idx (if (contains? awarded idx) (inc floor) floor)]))
          rows)))

(defn- apply-caps
  "Clamp each requested amount by its declared cap.
   Returns rows of {:id :weight :cap :allocated :unmet}."
  [requested-by-idx items]
  (mapv (fn [{:keys [idx id weight cap]}]
          (let [req (get requested-by-idx idx 0)
                allocated (if (some? cap) (min req cap) req)]
            {:id id :weight weight :cap cap
             :allocated allocated :unmet (- req allocated)}))
        items))

;; ---------------------------------------------------------------------------
;; redistribution-round expectation (independent active-set chain to fixed point)
;; ---------------------------------------------------------------------------

(defn- redistribution-chain
  "Cap-commitment active set iterated to a fixed point (independent of the
   allocator). Returns {:committed {id row} :final [rows] :passes [...]}."
  [{:keys [amount ordering-policy] :as request}]
  (letfn [(chain [round remaining active committed passes]
            (let [weight-total (reduce + 0 (map :weight active))]
              (cond
                (empty? active)
                (let [committed-rows (vec (vals committed))
                      share-rows (mapv (fn [i r] (assoc r :idx i)) (range) committed-rows)
                      share-idx (single-pass-requested
                                 {:amount remaining :items share-rows
                                  :rounding :floor-with-largest-remainder
                                  :ordering-policy ordering-policy})
                      share-alloc (apply-caps share-idx
                                              (mapv #(assoc % :cap nil) share-rows))
                      share-by-id (into {} (map (juxt :id :allocated) share-alloc))
                      committed (into {}
                                      (map (fn [[id entry]]
                                             [id (assoc entry :unmet (get share-by-id id 0))]))
                                      committed)]
                  {:committed committed :final [] :passes passes
                   :residual-reason :no-remaining-capacity})

                (zero? weight-total)
                {:committed committed
                 :final (mapv (fn [{:keys [id cap]}]
                                {:id id :weight 0 :cap cap
                                 :allocated 0 :unmet 0})
                              active)
                 :passes passes :residual-reason :no-active-weight}

                :else
                (let [constrained (filterv (fn [{:keys [cap weight]}]
                                             (and (some? cap)
                                                  (>= (* remaining weight)
                                                      (* cap weight-total))))
                                           active)
                      constrained-ids (set (map :id constrained))]
                  (if (seq constrained)
                    (let [committed-amount (reduce + 0 (map :cap constrained))]
                      (chain (inc round)
                             (- remaining committed-amount)
                             (vec (remove #(contains? constrained-ids (:id %)) active))
                             (into committed
                                   (map (fn [{:keys [id weight cap]}]
                                          [id {:id id :weight weight :cap cap
                                               :allocated cap :unmet 0}]))
                                   constrained)
                             (conj passes {:pass round
                                           :available-at-start remaining
                                           :active-ids (mapv :id active)
                                           :newly-capped-ids (mapv :id constrained)
                                           :committed-by-cap committed-amount
                                           :available-after-caps (- remaining committed-amount)})))
                    (let [final-idx (single-pass-requested
                                     (assoc request :amount remaining :items active))
                          final-rows (apply-caps final-idx active)]
                      {:committed committed :final final-rows
                       :passes (conj passes {:pass round
                                             :available-at-start remaining
                                             :active-ids (mapv :id active)
                                             :newly-capped-ids []
                                             :committed-by-cap 0
                                             :available-after-caps remaining})
                       :residual-reason :none}))))))]
    (chain 0 amount (normalize-items (:items request)) {} [])))

;; ---------------------------------------------------------------------------
;; fixed-point expected result
;; ---------------------------------------------------------------------------

(defn reconstruct
  "Independently derive the mathematically expected allocation for a canonical
   pro-rata request. Returns {:rows {id {:allocated :unmet ...}}
                               :total-allocated :total-unmet :remainder
                               :redistribution {:passes [...]} | nil}.
   Never calls the allocator."
  [{:keys [amount items cap-treatment] :as request}]
  (when-let [unsupported (seq (unsupported-policy-dims request))]
    (throw (ex-info "Unsupported exact pro-rata policy (no silent narrowing)"
                    {:unsupported-dims unsupported
                     :supported-domain supported-policies})))
  (let [amount (long amount)
        items (normalize-items items)
        request (assoc request :amount amount :items items)
        cap-treatment (or cap-treatment :unallocated)]
    (case cap-treatment
      :unallocated
      (let [rows (apply-caps (single-pass-requested request) items)
            by-id (into {} (map (juxt :id identity) rows))
            total-allocated (reduce + 0 (map :allocated rows))
            total-unmet (reduce + 0 (map :unmet rows))]
        {:rows by-id
         :total-allocated total-allocated
         :total-unmet total-unmet
         :remainder (- amount total-allocated total-unmet)
         :redistribution nil
         :cap-treatment :unallocated})

      :redistribute
      (let [{:keys [committed final passes]} (redistribution-chain request)
            all (merge committed (into {} (map (juxt :id identity) (or final []))))
            rows (mapv (fn [{:keys [id]}] (get all id)) items)
            total-allocated (reduce + 0 (map :allocated rows))
            total-unmet (reduce + 0 (map :unmet rows))]
        {:rows (into {} (map (juxt :id identity) rows))
         :total-allocated total-allocated
         :total-unmet total-unmet
         :remainder (- amount total-allocated total-unmet)
         :redistribution {:passes passes}
         :cap-treatment :redistribute})

      (throw (ex-info "Unsupported cap-treatment in exact reconstruction"
                      {:cap-treatment cap-treatment})))))

;; ---------------------------------------------------------------------------
;; verification decision (compare claimed result against reconstruction)
;; ---------------------------------------------------------------------------

(defn verify-weighted-proportionality
  "Verify a claimed allocator result against the independent fixed-point
   reconstruction. The claimed result is only ever COMPARED — it never decides
   what should have happened. Returns {:status :passed|:failed :details ...} and
   never throws."
  [request claimed]
  (try
    (if-let [unsupported (seq (unsupported-policy-dims request))]
      {:status :unsupported
       :details {:request-policy (select-keys request [:rounding :ordering-policy :cap-treatment])
                 :unsupported-dims (into {} unsupported)
                 :supported-domain supported-policies}}
      (let [expected (reconstruct request)
            exp-by-id (:rows expected)
            claimed-by-id (into {} (map (juxt :id identity) (:allocations claimed)))
            expected-ids (set (keys exp-by-id))
            claimed-ids (set (keys claimed-by-id))
            missing-expected (vec (remove claimed-ids expected-ids))
            extra-claimed (vec (remove expected-ids claimed-ids))
            value-mismatches (keep (fn [[id erow]]
                                     (let [crow (get claimed-by-id id)]
                                       (when (and crow
                                                  (not= (:allocated erow) (:allocated crow)))
                                         {:id id
                                          :expected-allocated (:allocated erow)
                                          :actual-allocated (:allocated crow)})))
                                   exp-by-id)
            totals-match? (and (= (:total-allocated expected) (:total-allocated claimed))
                               (= (:total-unmet expected) (:total-unmet claimed))
                               (= (:remainder expected) (:remainder claimed)))
            pass? (and (empty? missing-expected)
                       (empty? extra-claimed)
                       (empty? value-mismatches)
                       totals-match?)]
        {:status (if pass? :passed :failed)
         :details {:method :independent-fixed-point-reconstruction
                   :expected-rows (vec (keys exp-by-id))
                   :expected-totals (select-keys expected [:total-allocated :total-unmet :remainder])
                   :claimed-totals (select-keys claimed [:total-allocated :total-unmet :remainder])
                   :missing-expected-rows missing-expected
                   :extra-claimed-rows extra-claimed
                   :allocated-mismatches (vec value-mismatches)
                   :redistribution-passes (get-in expected [:redistribution :passes])}}))
    (catch Exception error
      {:status :failed
       :details {:error-class (.getName (class error))
                 :error-message (.getMessage error)}})))
