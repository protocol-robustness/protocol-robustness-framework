(ns resolver-sim.protocols.sew.appeal-bond-projection
  "Authoritative semantic projection for ordinary DRM appeal bonds.

   This namespace deliberately does not infer identity from aggregate ledgers.
   Missing, duplicate, or inconsistent lifecycle records fail closed."
  (:require [clojure.string :as str]
            [resolver-sim.hash.canonical :as hc]))

(def projection-version "appeal-bond-projection.v1")

(defn- positive-int? [x] (and (integer? x) (pos? x)))
(defn- non-negative-int? [x] (and (integer? x) (<= 0 x)))

(defn- fail [code data]
  {:valid? false :errors [(merge {:code code} data)]})

(defn- validate-record [record]
  (let [required [:bond/id :workflow/id :appeal/ordinal :asset
                  :bond/posted-amount :bond/posting-fee :bond/custodied-amount
                  :bond/payer :bond/funder :posting-operation/id :bond/status]
        missing (vec (remove #(contains? record %) required))
        gross (:bond/posted-amount record)
        fee (:bond/posting-fee record)
        custody (:bond/custodied-amount record)]
    (cond
      (seq missing) {:code :missing-bond-fields :bond/id (:bond/id record) :fields missing}
      (not (and (positive-int? gross) (non-negative-int? fee)
                (non-negative-int? custody)))
      {:code :invalid-bond-amounts :bond/id (:bond/id record)}
      (not= gross (+ fee custody))
      {:code :posted-fee-custody-mismatch :bond/id (:bond/id record)
       :posted gross :fee fee :custodied custody}
      :else nil)))

(defn- validate-disposition [record disposition]
  (let [alloc (:allocations disposition)
        total (:total disposition)
        custody (:bond/custodied-amount record)]
    (cond
      (nil? disposition) nil
      (or (nil? (:cause/type disposition)) (nil? (:cause/root disposition))
          (= "" (str (:cause/root disposition))))
      {:code :missing-disposition-lineage :bond/id (:bond/id record)}
      (not (and (map? alloc) (positive-int? total)))
      {:code :invalid-disposition :bond/id (:bond/id record)}
      (not= total (reduce + 0 (vals alloc)))
      {:code :allocation-total-mismatch :bond/id (:bond/id record)}
      (not= custody total)
      {:code :bond-conservation-failure :bond/id (:bond/id record)
       :custodied custody :allocated total}
      :else nil)))


(defn- validate-claims [bond-id disposition claims]
  (let [allocations (:allocations disposition)]
    (cond
      (not (map? claims)) {:code :missing-bond-claims :bond/id bond-id}
      (not= (set (keys allocations)) (set (keys claims)))
      {:code :claim-recipient-mismatch :bond/id bond-id}
      :else
      (some (fn [[recipient allocation]]
              (let [{:keys [allocated claimable claimed]} (get claims recipient)]
                (cond
                  (not (and (non-negative-int? allocated)
                            (non-negative-int? claimable)
                            (non-negative-int? claimed)))
                  {:code :invalid-claim-entitlement :bond/id bond-id :recipient recipient}
                  (not= allocated allocation)
                  {:code :claim-allocation-mismatch :bond/id bond-id :recipient recipient}
                  (not= allocated (+ claimable claimed))
                  {:code :claim-entitlement-conservation-failure :bond/id bond-id :recipient recipient})))
            allocations))))

(defn project-appeal-bonds
  "Return the normalized ordinary DRM bond projection from authoritative world state." 
  [world]
  (let [records (get world :appeal-bond/records {})
        dispositions (get world :appeal-bond/dispositions {})
        claims (get world :appeal-bond/claims {})
        by-bond (group-by :bond/id (vals dispositions))
        errors (vec (concat
                     (keep validate-record (vals records))
                     (mapcat (fn [record]
                               (let [ds (get by-bond (:bond/id record) [])
                                     count-ds (count ds)
                                     lifecycle-error (cond
                                                       (and (= :open (:bond/status record)) (pos? count-ds))
                                                       {:code :open-bond-has-disposition :bond/id (:bond/id record)}
                                                       (and (= :disposed (:bond/status record)) (not= 1 count-ds))
                                                       {:code :disposed-bond-disposition-count :bond/id (:bond/id record) :count count-ds}
                                                       :else nil)]
                                 (keep identity (cons lifecycle-error
                                                       (concat (map #(validate-disposition record %) ds)
                                                               (when (= 1 count-ds)
                                                                 [(validate-claims (:bond/id record) (first ds)
                                                                                   (get claims (:bond/id record)))]))))))
                             (vals records))))]
    (if (seq errors)
      (fail :invalid-appeal-bond-projection {:errors errors :projection/version projection-version})
      {:valid? true
       :projection/version projection-version
       :appeal-bond/records records
       :appeal-bond/dispositions dispositions
       :appeal-bond/claims claims
       :appeal-bond/conservation
       {:posted (reduce + 0 (map :bond/posted-amount (vals records)))
        :fees (reduce + 0 (map :bond/posting-fee (vals records)))
        :custodied (reduce + 0 (map :bond/custodied-amount (vals records)))
        :remaining (reduce + 0 (map :bond/remaining-custody (vals records)))}})))

(defn validate-appeal-bond-projection [projection]
  (if (:valid? projection) projection (throw (ex-info "invalid appeal bond projection" projection))))

(defn query-bond-conservation
  "Read-only query for exactly one authoritative bond in a validated projection.
   Select by canonical bond ID, or by the complete workflow/funder/ordinal identity.
   Missing or ambiguous selectors fail closed."
  [projection selector]
  (let [p (validate-appeal-bond-projection projection)
        records (vals (:appeal-bond/records p))
        identity-keys [:bond/id :workflow/id :dispute/id :bond/funder :appeal/ordinal]
        supplied-identity (select-keys selector identity-keys)
        candidates (filter (fn [record]
                            (every? (fn [[key value]] (= value (get record key)))
                                    supplied-identity))
                          records)]
    (cond
      (empty? candidates)
      {:valid? false :error :bond-not-found :selector selector}

      (not= 1 (count candidates))
      {:valid? false :error :ambiguous-bond-selector
       :selector selector :bond-ids (mapv :bond/id candidates)}

      :else
      (let [record (first candidates)
            disposition (first (filter #(= (:bond/id record) (:bond/id %))
                                       (vals (:appeal-bond/dispositions p))))
            terminal-total (or (:total disposition) 0)
            allocation-map (or (:allocations disposition) {})
            claims (get-in p [:appeal-bond/claims (:bond/id record)] {})
            posted (:bond/posted-amount record)
            fee (:bond/posting-fee record)
            custody (:bond/custodied-amount record)
            remaining (:bond/remaining-custody record)]
        {:valid? true
         :bond/id (:bond/id record)
         :workflow/id (:workflow/id record)
         :dispute/id (:dispute/id record)
         :bond/funder (:bond/funder record)
         :appeal/ordinal (:appeal/ordinal record)
         :bond/status (:bond/status record)
         :bond/posted-amount posted
         :bond/posting-fee fee
         :bond/custodied-amount custody
         :bond/remaining-custody remaining
         :terminal-allocation-total terminal-total
         :disposition-count (if disposition 1 0)
         :disposition/id (:disposition/id disposition)
         :disposition/kind (:kind disposition)
         :disposition/allocations allocation-map
         :entitlements claims
         :bond-conservation-holds (= posted (+ fee custody))
         :remaining-custody-after-disposition remaining
         :remaining-custody-invariant (if (= :disposed (:bond/status record))
                                        (zero? remaining)
                                        (= remaining custody))
         :terminal-allocations-equal-custody (if (= :disposed (:bond/status record))
                                               (= terminal-total custody)
                                               (zero? terminal-total))}))))

(defn query-bonds-by-workflow-funder
  "Read-only collection query for identity analysis. This does not select a
   mutation target; callers must still use query-bond-conservation with a
   canonical bond ID before mutating any bond."
  [projection workflow-id funder]
  (let [p (validate-appeal-bond-projection projection)]
    (->> (vals (:appeal-bond/records p))
         (filter #(and (= workflow-id (:workflow/id %))
                       (= funder (:bond/funder %))))
         (sort-by :appeal/ordinal)
         (mapv #(select-keys % [:bond/id :workflow/id :appeal/ordinal :bond/status
                                :bond/remaining-custody])))))

(defn appeal-bond-semantic-state
  "Canonical semantic state committed by appeal-bond transition receipts.
   It deliberately excludes aggregate reconciliation ledgers and unrelated
   protocol world state; every included field is validated by this projection."
  [projection]
  (let [p (validate-appeal-bond-projection projection)]
    {:projection/version (:projection/version p)
     :appeal-bond/records (:appeal-bond/records p)
     :appeal-bond/dispositions (:appeal-bond/dispositions p)
     :appeal-bond/claims (:appeal-bond/claims p)}))

(defn appeal-bond-semantic-root
  "Domain-separated root for validated ordinary appeal-bond semantic state."
  [projection]
  (hc/hash-with-intent {:hash/intent :appeal-bond-semantic-state}
                       (appeal-bond-semantic-state projection)))

(defn derive-appeal-bond-metrics
  "Scenario metrics are queries over the validated semantic projection." 
  [projection]
  (let [p (validate-appeal-bond-projection projection)
        ds (vals (:appeal-bond/dispositions p))
        refund (filter #(= :refund (:kind %)) ds)
        distributed (filter #(= :distribution (:kind %)) ds)
        forfeited (filter #(= :forfeiture (:kind %)) ds)]
    {:appeal_bond_posted (count (:appeal-bond/records p))
     :refund_amount (reduce + 0 (map :total refund))
     :bond_distributed (boolean (seq ds))
     :bond_distributed_amount (reduce + 0 (map :total distributed))
     ;; Legacy scenario adapter: DR-C-004 has one prior-round resolver
     ;; recipient; the normalized projection remains recipient-scoped.
     :l0resolver_payment (reduce + 0 (map :total distributed))
     :forfeited_reserve (reduce + 0 (map :total forfeited))
     :resolver_bond_refunded (reduce + 0 (map :total refund))}))
