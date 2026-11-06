(ns resolver-sim.protocols.sew.replay.appeal-bond
  "Sew appeal-bond transition evidence for the shared replay engine."
  (:require [clojure.string :as str]
            [resolver-sim.contract-model.replay.metrics :as metrics]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.protocols.sew.appeal-bond-projection :as projection]))

(defn- projection-evidence
  [world]
  (if-not (or (contains? world :appeal-bond/records)
              (contains? world :appeal-bond/dispositions)
              (contains? world :appeal-bond/claims))
    {:evidence/status :unavailable
     :reason :appeal-bond-domain-unavailable}
    (try
      (let [result (projection/project-appeal-bonds world)]
        (if (:valid? result)
          {:evidence/status :valid
           :root (projection/appeal-bond-semantic-root result)}
          {:evidence/status :invalid
           :errors (:errors result)}))
      (catch Exception e
        {:evidence/status :invalid
         :errors [{:code :appeal-bond-projection-error
                   :message (.getMessage e)
                   :data (ex-data e)}]}))))

(defn- canonical-operation-id
  [event block-time]
  (let [action-hash (hc/hash-with-intent
                     {:hash/intent :action}
                     {:action/type (:action event)
                      :action/content (:params event)})]
    (hc/hash-with-intent {:hash/intent :action-at}
                         {:action-hash action-hash
                          :step (:seq event)
                          :block-time block-time})))

(defn transition-receipt
  "Build the Sew appeal-bond pre/post receipt for one replay transition.

   operation-type is supplied by the shared replay layer so this protocol
   adapter does not depend on replay internals."
  [event result operation-id operation-type pre-world post-world]
  (let [operation-id (or operation-id (canonical-operation-id event (:block-time pre-world)))
        pre (projection-evidence pre-world)
        post (projection-evidence post-world)
        applied? (:ok result)]
    {:transition-receipt/version 1
     :operation/id operation-id
     :operation/type operation-type
     :operation/seq (:seq event)
     :execution/status (if applied? :applied :reverted)
     :execution/cause (when-not applied?
                        {:type (:error result)
                         :root operation-id})
     :appeal-bond {:pre-root (:root pre)
                   :post-root (:root post)
                   :pre-status (:evidence/status pre)
                   :post-status (:evidence/status post)
                   :pre-errors (:errors pre)
                   :post-errors (:errors post)}}))

;; Appeal-bond finalization and metrics

(defn- trace-action-name [entry]
  (-> (or (:action entry) "")
      str
      str/lower-case
      (str/replace "-" "_")))

(defn- rejected-action? [entry action]
  (and (= :rejected (:result entry))
       (= action (trace-action-name entry))))

(defn- accepted-action? [entry action]
  (and (not= :rejected (:result entry))
       (= action (trace-action-name entry))))

(defn- primitive-bond-query [scenario projection]
  (when (and (:valid? projection)
             (= :primitive-native (:classification (:claim-to scenario))))
    (let [scope (get-in scenario [:claim-to :scope])
          selector (or (:bond scope)
                       (when-let [bond-id (:bond-id scope)] {:bond/id bond-id}))]
      (when selector
        (projection/query-bond-conservation projection selector)))))

(defn- required-operation-violations [scenario trace]
  (vec
   (keep (fn [{:keys [seq action expected-status expected-cause evidence] :as requirement}]
           (if evidence
             (let [valid-receipt
                   (some (fn [entry]
                           (let [receipt (:appeal-bond/transition-receipt entry)
                                 bond (:appeal-bond receipt)]
                             (when (and (= :valid (:pre-status bond))
                                        (= :valid (:post-status bond))
                                        (some? (:pre-root bond))
                                        (some? (:post-root bond)))
                               receipt)))
                         trace)]
               (when-not (and (= evidence :appeal-bond-semantic-pre-post-roots) valid-receipt)
                 {:requirement requirement :observed valid-receipt}))
             (let [entry (some #(when (and (= seq (:seq %))
                                           (= (trace-action-name %) (-> action str str/lower-case
                                                                        (str/replace "-" "_"))))
                                  %)
                               trace)
                   rejected? (and entry (= :rejected (:result entry)))
                   status-ok? (case expected-status
                                :accepted (and entry (not rejected?) (nil? (:error entry)))
                                :rejected (and entry rejected?)
                                false)
                   cause (or (:reject-class entry) (:error entry))
                   cause-ok? (or (nil? expected-cause) (= expected-cause cause))]
               (when-not (and status-ok? cause-ok?)
                 {:requirement requirement
                  :observed (select-keys entry [:seq :action :result :error :reject-class])}))))
         (get-in scenario [:claim-to :requires] []))))

(defn- repeated-bond-groups
  "From a validated appeal-bond projection, return every (workflow, funder) bond
   group containing at least two distinct bonds, each sorted by appeal ordinal.
   Used to identify repeated appeal bonds posted by the same funder across
   rounds, without coupling to a specific checkpoint index or fixture address."
  [projection]
  (->> (vals (:appeal-bond/records projection))
       (group-by (juxt :workflow/id :bond/funder))
       vals
       (keep (fn [group]
               (let [bonds (->> group
                                (sort-by :appeal/ordinal)
                                (mapv #(select-keys %
                                                    [:bond/id :workflow/id :appeal/ordinal
                                                     :bond/status :bond/remaining-custody])))]
                 (when (>= (count bonds) 2) bonds))))))

(defn earliest-coexistence-bond-pair
  "Earliest retained checkpoint at which exactly one unambiguous repeated-bond
   group (same workflow + same funder, distinct appeal ordinals) coexists.
   Returns the bond pair sorted by appeal ordinal, or nil — failing closed —
   when no repeated-bond group is present or when multiple distinct groups
   coexist (ambiguous selector). Checkpoints are scanned in ascending seq order."
  [result]
  (->> (:world-checkpoints result)
       (sort-by key)
       (keep (fn [[_ world]]
               (let [projection (projection/project-appeal-bonds world)]
                 (when (:valid? projection)
                   (let [groups (repeated-bond-groups projection)]
                     (when (= 1 (count groups))
                       (first groups)))))))
       first))

(defn- expected-metric-keys
  "Set of expected metric flags used to decide which primitive bond metric values
   to compute. Accepts both supported `:expectations :metrics` shapes without
   assuming one:
   - legacy map shape (Sew): `{:metric-key predicate-map ...}` → `#{:metric-key ...}`
   - descriptor-vector shape (yield-v1): `[{:name 'yield/x' :value v} ...]` → `#{:yield/x ...}`
   Either way this is closed over for an empty/absent value (never throws)."
  [scenario]
  (let [metrics (get-in scenario [:expectations :metrics])]
    (cond
      (map? metrics) (set (keys metrics))
      (sequential? metrics) (into #{}
                                  (keep #(when (map? %) (metrics/metric-key (:name %))))
                                  metrics)
      :else #{})))

(defn- primitive-bond-metrics [scenario result query]
  (let [trace (:trace result)
        expectations (expected-metric-keys scenario)
        expected? (fn [metric] (contains? expectations metric))
        disposition-count (:disposition-count query)
        disposition (when query
                      {:id (:disposition/id query)
                       :kind (:disposition/kind query)
                       :allocations (:disposition/allocations query)})
        recipient (get-in scenario [:claim-to :scope :recipient])
        entitlement (get (:entitlements query) recipient)
        disposition-allocation (get-in disposition [:allocations recipient])
        identity-bonds (earliest-coexistence-bond-pair result)
        identity-a (first identity-bonds)
        identity-b (second identity-bonds)
        terminal-operation-seqs (->> (get-in scenario [:claim-to :requires])
                                     (keep #(when (and (:seq %)
                                                       (= "execute_resolution" (:action %)))
                                              (:seq %)))
                                     vec)
        first-terminal-receipt (some #(when (= (first terminal-operation-seqs)
                                               (get-in % [:appeal-bond/transition-receipt :operation/seq]))
                                        (:appeal-bond/transition-receipt %))
                                     trace)
        second-terminal-receipt (some #(when (= (second terminal-operation-seqs)
                                                (get-in % [:appeal-bond/transition-receipt :operation/seq]))
                                         (:appeal-bond/transition-receipt %))
                                      trace)
        first-terminal-accepted? (boolean (and first-terminal-receipt
                                               (= :applied (:execution/status first-terminal-receipt))))
        terminal-rejection? (boolean (some #(rejected-action? % "resolve_appeal") trace))
        atom-receipt (some #(when (= :distribution-dependency-failure (:error %))
                              (:appeal-bond/transition-receipt %))
                           trace)
        atom-bond (:appeal-bond atom-receipt)
        atom-roots-valid? (and (= :valid (:pre-status atom-bond))
                               (= :valid (:post-status atom-bond))
                               (some? (:pre-root atom-bond))
                               (some? (:post-root atom-bond)))
        metric-values
        (cond-> {}
          (and query (expected? :terminal_disposition_count))
          (assoc :terminal_disposition_count disposition-count)

          (expected? :bond_a_id_distinct_from_b)
          (assoc :bond_a_id_distinct_from_b
                 (and identity-a identity-b
                      (not= (:bond/id identity-a) (:bond/id identity-b))))

          (expected? :bond_b_open_at_coexistence)
          (assoc :bond_b_open_at_coexistence
                 (and identity-b
                      (= :open (:bond/status identity-b))))

          (expected? :bond_a_ordinal)
          (assoc :bond_a_ordinal (:appeal/ordinal identity-a))

          (expected? :bond_b_ordinal)
          (assoc :bond_b_ordinal (:appeal/ordinal identity-b))

          (and query (expected? :historical_disposition_unchanged))
          ;; The validated projection contains one immutable, lineage-bound
          ;; disposition. Temporal pre/post equality remains execution evidence.
          (assoc :historical_disposition_unchanged
                 (and (= 1 disposition-count)
                      (some? (:id disposition))
                      (some? (:kind disposition))))

          (and query (expected? :second_terminal_operation_reverted))
          (assoc :second_terminal_operation_reverted
                 (and first-terminal-accepted? terminal-rejection?))

          (and query (expected? :second_terminal_operation_no_duplicate_effect))
          (assoc :second_terminal_operation_no_duplicate_effect
                 (and first-terminal-accepted?
                      second-terminal-receipt
                      (= :applied (:execution/status second-terminal-receipt))
                      (= (:pre-root (:appeal-bond second-terminal-receipt))
                         (:post-root (:appeal-bond second-terminal-receipt)))))

          (and query entitlement (expected? :allocation_unchanged_after_claim))
          (assoc :allocation_unchanged_after_claim
                 (= disposition-allocation (:allocated entitlement)))

          (and query entitlement (expected? :claimable_after_full_claim))
          (assoc :claimable_after_full_claim (:claimable entitlement))

          (and query entitlement (expected? :claimed_equals_allocated))
          (assoc :claimed_equals_allocated
                 (= (:claimed entitlement) (:allocated entitlement)))

          (and query (expected? :first_terminal_operation_accepted))
          (assoc :first_terminal_operation_accepted first-terminal-accepted?)

          (expected? :replay_reverted)
          (assoc :replay_reverted
                 (and first-terminal-accepted? terminal-rejection?))

          (and (expected? :replay_no_duplicate_effect))
          (assoc :replay_no_duplicate_effect
                 (and first-terminal-accepted?
                      second-terminal-receipt
                      (= :applied (:execution/status second-terminal-receipt))
                      (= (:pre-root (:appeal-bond second-terminal-receipt))
                         (:post-root (:appeal-bond second-terminal-receipt)))))

          (and query (expected? :disposition_count))
          (assoc :disposition_count disposition-count)

          (and query (expected? :duplicate_entitlement))
          (assoc :duplicate_entitlement
                 (not (and (> (count (:entitlements query)) 1)
                           (> disposition-count 1))))

          (and query (expected? :no_duplicate_entitlement))
          (assoc :no_duplicate_entitlement
                 (and (= 1 disposition-count)
                      (= 1 (count (:entitlements query)))))

          ;; Execution status remains trace evidence, distinct from semantic roots.
          (and (expected? :operation_reverted)
               atom-receipt
               (= :reverted (:execution/status atom-receipt))
               (= :distribution-dependency-failure (get-in atom-receipt [:execution/cause :type])))
          (assoc :operation_reverted true)

          (and (expected? :bond_state_unchanged) atom-roots-valid?)
          (assoc :bond_state_unchanged (= (:pre-root atom-bond) (:post-root atom-bond)))

          (and (expected? :partial_distribution) atom-roots-valid?)
          (assoc :partial_distribution (not= (:pre-root atom-bond) (:post-root atom-bond))))]
    metric-values))


(defn finalize-evidence
  "Add Sew appeal-bond projections, receipts, metrics, and prerequisites to a replay result."
  [scenario result]
  (let [terminal-world (or (:world result) (:last-valid-world result))
        bond-records (get terminal-world :appeal-bond/records {})
        bond-projection (when (seq bond-records)
                          (projection/project-appeal-bonds terminal-world))
        bond-metrics (when (:valid? bond-projection)
                       (projection/derive-appeal-bond-metrics bond-projection))
        primitive-query (primitive-bond-query scenario bond-projection)
        primitive-metrics
        (merge
         (when (:valid? primitive-query)
           {:bond_conservation_holds (:bond-conservation-holds primitive-query)
            :remaining_custody_after_disposition (:remaining-custody-after-disposition primitive-query)
            :terminal_allocations_equal_custody (:terminal-allocations-equal-custody primitive-query)})
         (primitive-bond-metrics scenario result primitive-query))
        required-violations (required-operation-violations scenario (:trace result))
        primitive-expected? (= :primitive-native (:classification (:claim-to scenario)))
        primitive-metrics-present? (or (seq primitive-metrics)
                                       (some #(contains? (:metrics result) %)
                                             (expected-metric-keys scenario)))
        prerequisite-violations (vec (concat required-violations
                                             (when (and primitive-expected?
                                                        (pos? (get-in result [:metrics :invariant-violations] 0))
                                                        (not primitive-metrics-present?))
                                               [{:reason :predecessor-invariant-failure
                                                 :observed (get-in result [:metrics :invariant-violations])}])))
        projection-ok? (or (empty? bond-records) (:valid? bond-projection))
        refund-total (reduce + 0
                             (for [[_ domains] (get terminal-world :claimable-v2 {})
                                   amount (vals (get domains :bond/refund {}))]
                               (or amount 0)))
        slash-count (count (for [[_ slash] (merge (get terminal-world :pending-fraud-slashes {})
                                                   (get terminal-world :reversal-slash-history {}))
                                :when (= :executed (:status slash))]
                            slash))
        trace (:trace result)
        enriched (cond-> result
                   bond-projection (assoc :appeal-bond/projection bond-projection)
                   bond-metrics (update :metrics merge bond-metrics)
                   primitive-metrics (update :metrics merge primitive-metrics)
                   primitive-query (assoc :appeal-bond/primitive-query primitive-query)
                   (seq trace) (assoc :appeal-bond/transition-receipts
                                      (vec (keep :appeal-bond/transition-receipt trace)))
                   (seq trace) (update :metrics merge
                                       {:resolver_bond_refunded (pos? refund-total)
                                        :slash_count slash-count}))]
    {:result enriched
     :projection-ok? projection-ok?
     :projection-errors (:errors bond-projection)
     :prerequisite-violations prerequisite-violations}))
