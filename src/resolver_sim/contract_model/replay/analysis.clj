(ns resolver-sim.contract-model.replay.analysis
  "Error analysis and result finalization logic for replay engine.

   Decomposed from contract-model/replay to improve kernel modularity."
  (:require [clojure.set                    :as set]
            [clojure.string                 :as str]
            [resolver-sim.definitions.registry :as defs]
            [resolver-sim.scenario.expectations :as expectations]
            [resolver-sim.scenario.theory-result :as theory-result]
            [resolver-sim.scenario.yield-metrics :as yield-metrics]
            [resolver-sim.scenario.yield-provider-metrics :as yield-provider-metrics]
            [resolver-sim.protocols.sew.appeal-bond-projection :as appeal-bond-projection]
            [resolver-sim.time.context :as time-ctx]))

(defn action->transition-id
  "Map an event action string/keyword to canonical transition semantic id.
   Keeps backward compatibility by tolerating hyphen/underscore forms."
  [action]
  (let [s (-> (if (keyword? action) (name action) (str action))
              str/lower-case
              (str/replace "-" "_"))
        k (keyword s)]
    (if (defs/transition-def k)
      (keyword "scenario.transition" s)
      (keyword "scenario.transition" "unknown"))))

(defn normalize-error-value
  [error]
  (cond
    (keyword? error) error
    (string? error)  (let [s (if (.startsWith ^String error ":")
                               (subs error 1)
                               error)]
                       (keyword s))
    :else error))

(defn expected-error-key
  [{:keys [seq action error]}]
  [seq action (normalize-error-value error)])

(defn rejected-entry-key
  [{:keys [seq action error]}]
  [seq action (normalize-error-value error)])

(defn analyze-expected-errors
  "Compare rejected trace entries against scenario :expected-errors.

   Returns {:ok? bool :matched [...] :missing [...] :unexpected [...]}.
   Matching is exact on [:seq :action :error]."
  [scenario trace]
  (let [expected        (vec (:expected-errors scenario []))
        expected-set    (set (map expected-error-key expected))
        rejected        (->> trace
                             (filter #(= :rejected (:result %)))
                             (map #(select-keys % [:seq :action :error]))
                             vec)
        rejected-set    (set (map rejected-entry-key rejected))
        matched-set     (set/intersection expected-set rejected-set)
        missing-set     (set/difference expected-set rejected-set)
        unexpected-set  (set/difference rejected-set expected-set)]
    {:ok?        (and (empty? missing-set) (empty? unexpected-set))
     :matched    (vec (sort-by (juxt :seq :action)
                               (filter #(contains? matched-set (expected-error-key %)) expected)))
     :missing    (vec (sort-by (juxt :seq :action)
                               (filter #(contains? missing-set (expected-error-key %)) expected)))
     :unexpected (vec (sort-by (juxt :seq :action)
                               (filter #(contains? unexpected-set (rejected-entry-key %)) rejected)))}))

(defn- merge-metrics-for-profile
  [result scenario flags]
  (case (:metrics-profile flags :sew-integrated)  ; Default is Sew-integrated
    :base result
    :yield-provider (-> result
                        (yield-metrics/merge-yield-metrics)
                        (yield-provider-metrics/merge-provider-metrics scenario))
    (yield-metrics/merge-yield-metrics result)))

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
        (appeal-bond-projection/query-bond-conservation projection selector)))))

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

(defn- earliest-coexistence-bond-pair
  "Earliest retained checkpoint at which exactly one unambiguous repeated-bond
   group (same workflow + same funder, distinct appeal ordinals) coexists.
   Returns the bond pair sorted by appeal ordinal, or nil — failing closed —
   when no repeated-bond group is present or when multiple distinct groups
   coexist (ambiguous selector). Checkpoints are scanned in ascending seq order."
  [result]
  (->> (:world-checkpoints result)
       (sort-by key)
       (keep (fn [[_ world]]
               (let [projection (appeal-bond-projection/project-appeal-bonds world)]
                 (when (:valid? projection)
                   (let [groups (repeated-bond-groups projection)]
                     (when (= 1 (count groups))
                       (first groups)))))))
       first))

(defn- primitive-bond-metrics [scenario result query]
  (let [trace (:trace result)
        expectations (set (keys (get-in scenario [:expectations :metrics] {})))
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

(defn finalize-scenario-result
  "Apply yield metrics, expected-outcomes, and :expectations checks."
  ([scenario result] (finalize-scenario-result scenario result {}))
  ([scenario result flags]
   (let [result'      (merge-metrics-for-profile result scenario flags)
         terminal-world (or (:world result') (:last-valid-world result'))
         bond-records (get terminal-world :appeal-bond/records {})
         bond-projection (when (seq bond-records)
                           (appeal-bond-projection/project-appeal-bonds terminal-world))
         bond-metrics (when (:valid? bond-projection)
                        (appeal-bond-projection/derive-appeal-bond-metrics bond-projection))
         primitive-bond-query (primitive-bond-query scenario bond-projection)
         primitive-bond-metrics
         (merge
          (when (:valid? primitive-bond-query)
            {:bond_conservation_holds (:bond-conservation-holds primitive-bond-query)
             :remaining_custody_after_disposition (:remaining-custody-after-disposition primitive-bond-query)
             :terminal_allocations_equal_custody (:terminal-allocations-equal-custody primitive-bond-query)})
          (primitive-bond-metrics scenario result' primitive-bond-query))
         required-violations (required-operation-violations scenario (:trace result'))
         primitive-expected? (= :primitive-native (:classification (:claim-to scenario)))
         primitive-metrics-present? (or (seq primitive-bond-metrics)
                                        (some #(contains? (:metrics result') %)
                                              (keys (get-in scenario [:expectations :metrics] {}))))
         prerequisite-violations (vec (concat required-violations
                                              (when (and primitive-expected?
                                                         (pos? (get-in result' [:metrics :invariant-violations] 0))
                                                         (not primitive-metrics-present?))
                                                [{:reason :predecessor-invariant-failure
                                                  :observed (get-in result' [:metrics :invariant-violations])}])))
         projection-ok?  (or (empty? bond-records) (:valid? bond-projection))
         legacy-bond-refund-total
         (reduce + 0
                 (for [[_ domains] (get terminal-world :claimable-v2 {})
                       amount (vals (get domains :bond/refund {}))]
                   (or amount 0)))
         legacy-slash-count
         (count (for [[_ slash] (merge (get terminal-world :pending-fraud-slashes {})
                                       (get terminal-world :reversal-slash-history {}))
                      :when (= :executed (:status slash))]
                  slash))
         resolution-reverted-on-failure?
         (boolean (some #(and (= :rejected (:result %))
                              (= "execute_resolution" (:action %)))
                        (:trace result')))
         result' (cond-> result'
                   bond-projection (assoc :appeal-bond/projection bond-projection)
                   bond-metrics (update :metrics merge bond-metrics)
                   primitive-bond-metrics (update :metrics merge primitive-bond-metrics)
                   primitive-bond-query (assoc :appeal-bond/primitive-query primitive-bond-query)
                   (seq (:trace result')) (assoc :appeal-bond/transition-receipts
                                                 (vec (keep :appeal-bond/transition-receipt (:trace result'))))
                   (seq (:trace result')) (update :metrics merge
                                                  {:resolver_bond_refunded (pos? legacy-bond-refund-total)
                                                   :slash_count legacy-slash-count})
                   (seq (:trace result')) (assoc-in [:metrics :resolution_reverted_on_failure]
                                                    resolution-reverted-on-failure?)
                   (seq (:trace result')) (assoc-in [:metrics :resolution_attempt_reverted]
                                                    resolution-reverted-on-failure?))
         outcomes     (expectations/analyze-expected-outcomes scenario (:trace result'))
         expect       (when (:expectations scenario)
                        (expectations/evaluate-expectations result' (:expectations scenario)))

         ;; BRIDGE: Apply theory evaluation
         result''     (theory-result/attach-three-way-model result'
                                                            (:theory-eval-opts scenario {})
                                                            :theory (:theory scenario))

         ;; Step 7: Add temporal evidence envelope
         time-ctx      (when terminal-world (time-ctx/temporal-context terminal-world))
         result-ev     (cond-> result''
                         time-ctx (assoc :time-evidence {:schema-version (or (:schema-version time-ctx) "temporal-context.v2")
                                                         :terminal-time time-ctx}))

         outcomes-ok? (:ok? outcomes true)
         expect-ok?   (or (nil? expect) (:ok? expect))
         theory-ok?   (or (nil? (:falsified? result-ev)) (not (:falsified? result-ev)))
         checks-ok?   (and projection-ok? outcomes-ok? expect-ok? theory-ok?)
         blocked?     (seq prerequisite-violations)]
     (let [base-result (assoc result-ev
                              :assurance/status (if blocked? :blocked (if checks-ok? :pass :failed))
                              :assurance/prerequisite-violations prerequisite-violations
                              :expected-outcomes outcomes
                              :expectations expect)]
       (cond
         blocked?
         (assoc base-result
                :outcome :blocked
                :halt-reason :assurance-prerequisite-unmet
                :expectation-violations
                (vec (concat prerequisite-violations (:violations outcomes []) (:violations expect []))))

         (and (= (:outcome result-ev) :pass) (not checks-ok?))
         (assoc base-result
                :outcome :fail
                :halt-reason (cond (not projection-ok?) :invalid-appeal-bond-projection
                                   (not outcomes-ok?) :expected-outcome-mismatch
                                   (not expect-ok?) :expectation-mismatch
                                   (not theory-ok?) :theory-falsified)
                :expectation-violations
                (vec (concat (when-not projection-ok? (:errors bond-projection))
                             (:violations outcomes [])
                             (:violations expect []))))

         :else base-result)))))
