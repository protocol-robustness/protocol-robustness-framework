(ns resolver-sim.protocols.sew.appeal-bond-projection
  "Authoritative semantic projection for ordinary DRM appeal bonds.

   This namespace deliberately does not infer identity from aggregate ledgers.
   Missing, duplicate, or inconsistent lifecycle records fail closed."
  (:require [clojure.string :as str]
            [resolver-sim.hash.canonical :as hc]))

(def projection-version "appeal-bond-projection.v1")

(defn- non-negative-int? [x]
  (and (integer? x) (<= 0 x Long/MAX_VALUE)))
(defn- positive-int? [x] (and (non-negative-int? x) (pos? x)))

(defn- fail [code data]
  {:valid? false :errors [(merge {:code code} data)]})

(defn- nonblank-string? [x]
  (and (string? x) (not (str/blank? x))))

(defn- account-id? [x]
  (or (nonblank-string? x) (symbol? x)))

(defn- workflow-id? [x]
  (or (nonblank-string? x)
      (non-negative-int? x)
      (and (keyword? x) (re-matches #"\d+" (name x)))
      (and (record? x) (non-negative-int? (:id x)))))

(defn- asset-id? [x]
  (or (keyword? x) (nonblank-string? x)))

(defn- cause-type? [x]
  (or (keyword? x) (nonblank-string? x)))

(defn- stable-entries [m]
  (sort-by (comp pr-str key) m))

(defn- sum-amounts [amounts]
  (reduce +' 0 amounts))

(defn- validate-record [record]
  (if-not (map? record)
    {:code :invalid-bond-record}
  (let [required [:bond/id :workflow/id :dispute/id :appeal/ordinal
                  :escalation/from-level :escalation/to-level :asset
                  :bond/posted-amount :bond/posting-fee :bond/custodied-amount
                  :bond/remaining-custody :bond/payer :bond/funder
                  :posting-operation/id :posting-cause/type :posting-cause/root
                  :bond/status]
        missing (vec (remove #(contains? record %) required))
        gross (:bond/posted-amount record)
        fee (:bond/posting-fee record)
        custody (:bond/custodied-amount record)]
    (cond
      (seq missing) {:code :missing-bond-fields :bond/id (:bond/id record) :fields missing}
      (not (and (nonblank-string? (:bond/id record))
                (workflow-id? (:workflow/id record))
                (nonblank-string? (:dispute/id record))
                (asset-id? (:asset record))
                (account-id? (:bond/payer record))
                (account-id? (:bond/funder record))
                (nonblank-string? (:posting-operation/id record))
                (cause-type? (:posting-cause/type record))
                (nonblank-string? (:posting-cause/root record))
                (non-negative-int? (:appeal/ordinal record))
                (non-negative-int? (:escalation/from-level record))
                (positive-int? (:escalation/to-level record))))
      {:code :invalid-bond-identity :bond/id (:bond/id record)}
      (not= (:bond/id record)
            (str "appeal-bond:" (:workflow/id record) ":"
                 (:bond/funder record) ":" (:appeal/ordinal record)))
      {:code :bond-id-identity-mismatch :bond/id (:bond/id record)}
      (or (not= (:dispute/id record) (str "dispute:" (:workflow/id record)))
          (not= (:escalation/to-level record) (+' (:escalation/from-level record) 1)))
      {:code :bond-case-or-round-mismatch :bond/id (:bond/id record)}
      (not (contains? #{:open :disposed} (:bond/status record)))
      {:code :invalid-bond-lifecycle :bond/id (:bond/id record) :status (:bond/status record)}
      (not (and (positive-int? gross) (non-negative-int? fee)
                (non-negative-int? custody)
                (non-negative-int? (:bond/remaining-custody record))))
      {:code :invalid-bond-amounts :bond/id (:bond/id record)}
      (> fee gross)
      {:code :invalid-bond-amount-bounds :bond/id (:bond/id record)}
      (not= gross (+' fee custody))
      {:code :posted-fee-custody-mismatch :bond/id (:bond/id record)
       :posted gross :fee fee :custodied custody}
      (not= (:bond/remaining-custody record)
            (if (= :open (:bond/status record)) custody 0))
      {:code :remaining-custody-lifecycle-mismatch :bond/id (:bond/id record)
       :status (:bond/status record) :remaining (:bond/remaining-custody record)}
      :else nil))))

(defn- validate-disposition [record disposition]
  (if-not (map? disposition)
    {:code :invalid-disposition-record :bond/id (:bond/id record)}
  (let [alloc (:allocations disposition)
        total (:total disposition)
        custody (:bond/custodied-amount record)]
    (cond
      (nil? disposition) nil
      (or (not= (:bond/id record) (:bond/id disposition))
          (and (contains? disposition :workflow/id)
               (not= (:workflow/id record) (:workflow/id disposition)))
          (and (contains? disposition :dispute/id)
               (not= (:dispute/id record) (:dispute/id disposition)))
          (and (contains? disposition :appeal/ordinal)
               (not= (:appeal/ordinal record) (:appeal/ordinal disposition)))
          (and (contains? disposition :escalation/from-level)
               (not= (:escalation/from-level record)
                     (:escalation/from-level disposition)))
          (and (contains? disposition :escalation/to-level)
               (not= (:escalation/to-level record)
                     (:escalation/to-level disposition)))
          (and (contains? disposition :asset)
               (not= (:asset record) (:asset disposition))))
      {:code :disposition-identity-mismatch :bond/id (:bond/id record)}
      (or (not (contains? #{:refund :distribution :forfeiture} (:kind disposition)))
          (not= (:disposition/id disposition)
                (str "disposition:" (:bond/id record) ":" (name (:kind disposition)))))
      {:code :disposition-id-identity-mismatch :bond/id (:bond/id record)}
      (or (not (cause-type? (:cause/type disposition)))
          (not (nonblank-string? (:cause/root disposition))))
      {:code :missing-disposition-lineage :bond/id (:bond/id record)}
      (not (and (map? alloc) (positive-int? total)))
      {:code :invalid-disposition :bond/id (:bond/id record)}
      (not-every? (fn [[recipient amount]]
                    (and (account-id? recipient) (non-negative-int? amount)))
                  alloc)
      {:code :invalid-disposition-allocation :bond/id (:bond/id record)}
      (not= total (sum-amounts (vals alloc)))
      {:code :allocation-total-mismatch :bond/id (:bond/id record)}
      (not= custody total)
      {:code :bond-conservation-failure :bond/id (:bond/id record)
       :custodied custody :allocated total}
      :else nil))))


(defn- validate-claims [record disposition claims]
  (let [bond-id (:bond/id record)
        allocations (:allocations disposition)]
    (cond
      (not (map? claims)) {:code :missing-bond-claims :bond/id bond-id}
      (not= (set (keys allocations)) (set (keys claims)))
      {:code :claim-recipient-mismatch :bond/id bond-id}
      :else
          (some (fn [[recipient allocation]]
              (let [entitlement (get claims recipient)
                    {:keys [allocated claimable claimed]} entitlement]
                (cond
                  (and (map? entitlement)
                       (or (and (contains? entitlement :bond/id)
                                (not= bond-id (:bond/id entitlement)))
                           (and (contains? entitlement :workflow/id)
                                (not= (:workflow/id record) (:workflow/id entitlement)))
                           (and (contains? entitlement :dispute/id)
                                (not= (:dispute/id record) (:dispute/id entitlement)))
                           (and (contains? entitlement :appeal/ordinal)
                                (not= (:appeal/ordinal record) (:appeal/ordinal entitlement)))
                           (and (contains? entitlement :escalation/from-level)
                                (not= (:escalation/from-level record)
                                      (:escalation/from-level entitlement)))
                           (and (contains? entitlement :escalation/to-level)
                                (not= (:escalation/to-level record)
                                      (:escalation/to-level entitlement)))))
                  {:code :claim-identity-mismatch :bond/id bond-id :recipient recipient}
                  (not (and (non-negative-int? allocated)
                            (non-negative-int? claimable)
                            (non-negative-int? claimed)))
                  {:code :invalid-claim-entitlement :bond/id bond-id :recipient recipient}
                  (not (and (map? (get claims recipient))
                            (contains? (get claims recipient) :asset)
                            (= (:asset record) (:asset (get claims recipient)))))
                  {:code :claim-asset-mismatch :bond/id bond-id :recipient recipient}
                  (not= allocated allocation)
                  {:code :claim-allocation-mismatch :bond/id bond-id :recipient recipient}
                  (not= allocated (+' claimable claimed))
                  {:code :claim-entitlement-conservation-failure :bond/id bond-id :recipient recipient})))
            (stable-entries allocations)))))

(defn project-appeal-bonds
  "Return the normalized ordinary DRM bond projection from authoritative world state." 
  [world]
  (let [raw-records (get world :appeal-bond/records {})
        raw-dispositions (get world :appeal-bond/dispositions {})
        raw-claims (get world :appeal-bond/claims {})
        shape-errors (cond-> []
                       (not (map? world)) (conj {:code :invalid-bond-world})
                       (not (map? raw-records)) (conj {:code :invalid-bond-records})
                       (not (map? raw-dispositions)) (conj {:code :invalid-bond-dispositions})
                       (not (map? raw-claims)) (conj {:code :invalid-bond-claims}))
        records (if (map? raw-records) raw-records {})
        dispositions (if (map? raw-dispositions) raw-dispositions {})
        claims (if (map? raw-claims) raw-claims {})
        by-bond (group-by :bond/id (vals dispositions))
        identity-errors (concat
                         (keep (fn [[record-key record]]
                                 (when (not= record-key (:bond/id record))
                                   {:code :bond-record-id-mismatch :record/key record-key
                                    :bond/id (:bond/id record)}))
                               (stable-entries records))
                         (keep (fn [[disposition-key disposition]]
                                 (cond
                                   (not (map? disposition))
                                   {:code :invalid-disposition-record
                                    :disposition/id disposition-key}
                                   (or (not= disposition-key (:disposition/id disposition))
                                       (not (contains? records (:bond/id disposition))))
                                   {:code (if (contains? records (:bond/id disposition))
                                            :disposition-id-mismatch :orphan-disposition)
                                    :disposition/id disposition-key
                                    :bond/id (:bond/id disposition)}))
                               (stable-entries dispositions))
                         (keep (fn [[bond-id _]]
                                 (when-not (contains? records bond-id)
                                   {:code :orphan-claims :bond/id bond-id}))
                               (stable-entries claims)))
        record-errors (mapcat (fn [[_ record]]
                                (let [ds (sort-by (comp pr-str :disposition/id)
                                                  (get by-bond (:bond/id record) []))
                                      count-ds (count ds)
                                      lifecycle-error (cond
                                                        (and (= :open (:bond/status record)) (pos? count-ds))
                                                        {:code :open-bond-has-disposition :bond/id (:bond/id record)}
                                                        (and (= :disposed (:bond/status record)) (not= 1 count-ds))
                                                        {:code :disposed-bond-disposition-count :bond/id (:bond/id record) :count count-ds}
                                                        :else nil)]
                                  (concat
                                   (keep identity [lifecycle-error (validate-record record)])
                                   (keep identity (map #(validate-disposition record %) ds))
                                   (when (and (= 1 count-ds) (= :disposed (:bond/status record)))
                                     (keep identity [(validate-claims record (first ds)
                                                                      (get claims (:bond/id record)))]))
                                   (when (and (= :open (:bond/status record))
                                              (contains? claims (:bond/id record)))
                                     [{:code :claims-without-disposition :bond/id (:bond/id record)}]))))
                              (sort-by (comp pr-str key) records))
        orphan-dispositions (mapcat (fn [[bond-id ds]]
                                      (when-not (contains? records bond-id)
                                        (map (fn [d] {:code :orphan-disposition
                                                      :disposition/id (:disposition/id d)
                                                      :bond/id bond-id})
                                             (sort-by (comp pr-str :disposition/id) ds))))
                                    (stable-entries by-bond))
        errors (vec (concat shape-errors identity-errors record-errors orphan-dispositions))]
    (if (seq errors)
      (fail :invalid-appeal-bond-projection {:errors errors :projection/version projection-version})
      {:valid? true
       :projection/version projection-version
       :appeal-bond/records records
       :appeal-bond/dispositions dispositions
       :appeal-bond/claims claims
       :appeal-bond/conservation
       {:posted (sum-amounts (map :bond/posted-amount (vals records)))
        :fees (sum-amounts (map :bond/posting-fee (vals records)))
        :custodied (sum-amounts (map :bond/custodied-amount (vals records)))
        :remaining (sum-amounts (map :bond/remaining-custody (vals records)))}})))

(defn validate-appeal-bond-projection [projection]
  (if-not (:valid? projection)
    (throw (ex-info "invalid appeal bond projection" projection))
    (let [checked (project-appeal-bonds projection)]
      (if (:valid? checked)
        checked
        (throw (ex-info "invalid appeal bond projection" checked))))))

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
       :selector selector :bond-ids (vec (sort (map :bond/id candidates)))}

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
         :bond-conservation-holds (= posted (+' fee custody))
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

(defn query-bond-claim-attribution
  "Classify how authoritative a recipient's claim consumption is within one
   workflow, reflecting the **canonical Sew module** semantic boundary.

   In the deployed `ResolverIncentiveModule`, claim consumption is aggregate per
   `(escrowContract, workflowId, recipient)`: `claimableBondRefunds` and
   `claimablePayments` are single workflow-level pools credited via `+=` and
   deleted whole on claim. A per-round/bond `claimable` is never consumed
   independently, so a recipient's entitlement across multiple bonds of one
   workflow collapses into a single pool.

   Consequently, per-bond `claimable`/`claimed` attribution is authoritative only
   when exactly one bond in the workflow carries **pending claimable** entitlement
   for that recipient. When two or more bonds carry pending claimable, an
   aggregate withdrawal cannot be attributed, consumption is aggregate-only and
   PRF must NOT infer FIFO/LIFO/latest/map-order per-bond attribution. Fully
   consumed bonds (`claimable = 0`) carry no pending claimable and do not
   contribute to the ambiguous pool.

   Returns (always `:valid? true` for a validated projection):
     {:attribution :single-bond        :bond/id <id>}
     {:attribution :aggregate-multi-bond :bond/ids [...]}   (sorted)
     {:attribution :none               :bond/ids []}
   Throws on an invalid/unvalidated projection (fail closed)."
  [projection workflow-id recipient]
  (let [p (validate-appeal-bond-projection projection)
        carrying (fn [record]
                   (some (fn [[r ent]]
                           (and (= recipient r)
                                (pos? (long (or (:claimable ent) 0)))))
                         (get-in p [:appeal-bond/claims (:bond/id record)] {})))
        bond-ids (->> (vals (:appeal-bond/records p))
                      (filter #(= workflow-id (:workflow/id %)))
                      (filter carrying)
                      (map :bond/id)
                      (sort)
                      vec)]
    (cond
      (empty? bond-ids) {:valid? true :attribution :none :bond/ids []}
      (= 1 (count bond-ids)) {:valid? true :attribution :single-bond
                              :bond/id (first bond-ids)}
      :else {:valid? true :attribution :aggregate-multi-bond :bond/ids bond-ids})))

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
     :refund_amount (sum-amounts (map :total refund))
     :bond_distributed (boolean (seq ds))
     :bond_distributed_amount (sum-amounts (map :total distributed))
     ;; Legacy scenario adapter: DR-C-004 has one prior-round resolver
     ;; recipient; the normalized projection remains recipient-scoped.
     :l0resolver_payment (sum-amounts (map :total distributed))
     :forfeited_reserve (sum-amounts (map :total forfeited))
     :resolver_bond_refunded (sum-amounts (map :total refund))}))
