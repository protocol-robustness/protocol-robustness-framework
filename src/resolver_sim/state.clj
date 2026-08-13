(ns resolver-sim.state
  "Protocol-neutral inspection of state-shaped values.

   This namespace describes values *around* existing state machines. It never
   mutates a state value, defines transition semantics, or assigns a root where
   an owning representation has not defined one. Model declarations are passive
   data; callers select a model explicitly when structural recognition would be
   ambiguous."
  (:require [clojure.set :as set]
            [resolver-sim.accounting.held-ledger-index :as held-index]
            [resolver-sim.assurance.custody :as custody]
            [resolver-sim.time.context :as time]))

(def descriptor-version 1)

(def ^:private sew-custody-paths
  [[:held-adjustments] [:held-ledger/index] [:total-held]
   [:held/positions] [:held-artifacts]])

(def ^:private catalogue-data
  [{:state/model-id :sew/runtime
    :state/label "Sew runtime world"
    :state/class :protocol-world
    :state/authority :operational
    :representation/completeness :complete
    :representation/purposes #{:runtime :checkpoint :counterfactual :generation}
    :state/owner :sew
    :state/regions
    [{:region/id :temporal :paths [[:block-time] [:context/time]]
      :region/authority :temporal}
     {:region/id :custody :paths sew-custody-paths
      :region/authority :materialized
      :region/path-authority {[:held-adjustments] :canonical
                              [:held-ledger/index] :materialized
                              [:total-held] :materialized
                              [:held/positions] :materialized
                              [:held-artifacts] :materialized}}
     {:region/id :escrow :paths [[:escrow-transfers] [:escrow-settings]
                                 [:pending-settlements] [:dispute-levels]]
      :region/authority :operational}
     {:region/id :claims :paths [[:claimable] [:claimable-v2]]
      :region/authority :operational}
     {:region/id :fees :paths [[:total-fees] [:total-fees-withdrawn]
                               [:fee-payouts] [:fee-recipients]]
      :region/authority :operational}
     {:region/id :derived-state :paths [[:live-states]]
      :region/authority :diagnostic}
     {:region/id :authority :paths [[:force-authorisations] [:resolver-stakes]
                                    [:pending-fraud-slashes]]
      :region/authority :operational}]
    :state/relationships
    [{:relationship/id :sew/held-ledger-materialization
      :relationship/type :materialized-from
      :source {:path [:held-adjustments] :authority :canonical}
      :targets (mapv (fn [path] {:path path :authority :materialized})
                     (rest sew-custody-paths))
      :verification :state/held-custody-reconciliation}
     {:relationship/id :sew/terminal-custody-snapshot
      :relationship/type :projection-of
      :target-model :sew/terminal-custody-snapshot}]}
   {:state/model-id :resubmission/chain
    :state/label "Resubmission chain"
    :state/class :state-machine
    :state/authority :operational
    :representation/completeness :complete
    :representation/purposes #{:runtime}
    :state/owner :resubmission
    :state/regions [{:region/id :chain
                     :paths [[:chain/head] [:chain/version] [:chain/content-index]
                             [:chain/idempotency-index]]
                     :region/authority :operational}]}
   {:state/model-id :sew/financial-lifecycle
    :state/label "Sew financial-health lifecycle"
    :state/class :state-machine
    :state/authority :derived-operational
    :representation/completeness :complete
    :representation/purposes #{:runtime :lifecycle}
    :state/owner :sew-financial-lifecycle
    :state/regions [{:region/id :episode
                     :paths [[:lifecycle/state] [:episode/events] [:episode/id]]
                     :region/authority :derived}]}
   {:state/model-id :assurance/held-ledger-reconstruction
    :state/label "Held-ledger reconstruction"
    :state/class :reconstruction
    :state/authority :derived
    :representation/completeness :constrained
    :representation/purposes #{:assurance-reconstruction}
    :state/owner :assurance-custody
    :state/regions [{:region/id :custody
                     :paths [[:held-ledger/index] [:total-held] [:held/positions]]
                     :region/authority :independent-assurance}]
    :state/relationships [{:relationship/id :assurance/held-reconstruction
                           :relationship/type :reconstruction-of
                           :source-model :sew/runtime
                           :source {:path [:held-adjustments]}
                           :independent? true}]}
   {:state/model-id :sew/terminal-custody-snapshot
    :state/label "Sew terminal custody snapshot"
    :state/class :projection
    :state/authority :derived
    :representation/completeness :projection
    :representation/purposes #{:terminal-commitment}
    :state/owner :sew-terminal-state-snapshot}
   {:state/model-id :sew/evm-comparison
    :state/label "Sew EVM-comparable projection"
    :state/class :projection
    :state/authority :derived
    :representation/completeness :projection
    :representation/purposes #{:cross-implementation-comparison}
    :state/owner :sew-diff}
   {:state/model-id :lab/solvency-fixture
    :state/label "Solvency lab world"
    :state/class :fixture
    :state/authority :fixture
    :representation/completeness :fixture
    :representation/purposes #{:research}
    :state/owner :lab-insolvency}
   {:state/model-id :sim/adversarial-ring
    :state/label "Adversarial resolver-ring model"
    :state/class :analytical-model
    :state/authority :analytical
    :representation/completeness :constrained
    :representation/purposes #{:research}
    :state/owner :adversarial-ring}])

(defn catalogue [] catalogue-data)
(defn model-descriptor [model-id]
  (some #(when (= model-id (:state/model-id %)) %) catalogue-data))

(defn- recognisable-model [value]
  (cond
    (and (map? value) (contains? value :chain/head)) :resubmission/chain
    (and (map? value) (contains? value :lifecycle/state)
         (contains? value :episode/events)) :sew/financial-lifecycle
    (and (map? value) (contains? value :artifact/type)
         (= :sew-terminal-state-snapshot (:artifact/type value))) :sew/terminal-custody-snapshot
    (and (map? value) (contains? value :held-ledger/index)
         (contains? value :total-held) (contains? value :held/positions)
         (not (contains? value :escrow-transfers))) :assurance/held-ledger-reconstruction
    (and (map? value) (contains? value :members) (contains? value :active-count)) :sim/adversarial-ring
    (and (map? value) (= "lab-wf" (first (keys (:escrow-transfers value))))
         (contains? value :lab/require-external-coverage)) :lab/solvency-fixture
    (and (map? value) (contains? value :escrow-transfers)) :sew/runtime
    :else nil))

(defn state-time
  "Extract only repository-supported temporal coordinates."
  [value context]
  (when (map? value)
    (let [temporal (when (or (contains? value :block-time) (contains? value :context/time))
                     (time/temporal-context value))
          sequence (or (:sequence context) (:seq context) (:event-seq temporal))]
      (cond-> {}
        temporal (assoc :block-time (:block-ts temporal)
                        :temporal-context (select-keys temporal [:step :event-seq :clock/mode :clock/source]))
        (:event-at context) (assoc :event-at (:event-at context))
        (some? sequence) (assoc :sequence sequence)
        (:elapsed-from-parent context) (assoc :elapsed-from-parent (:elapsed-from-parent context))))))

(defn describe-state
  "Return an external descriptor for `value`. `context` may select :state/model-id
   and supply instance/origin/lineage information captured by the caller."
  ([value] (describe-state value {}))
  ([value context]
   (let [model-id (or (:state/model-id context) (recognisable-model value))
         model (model-descriptor model-id)]
     {:state/descriptor-version descriptor-version
      :state/model (or model {:state/model-id :unknown
                              :state/class :unknown
                              :state/authority :unknown
                              :representation/completeness :constrained})
      :state/instance (merge {:kind (or (:state/instance-kind context) :observed)}
                             (select-keys context [:root :sequence :parent-sequence :branch-id]))
      :state/origin (select-keys context [:scenario-id :run-id :execution-id])
      :state/time (state-time value context)
      :state/lineage (select-keys context [:transition :representation])
      :state/relationships (:state/relationships model)
      :state/regions (:state/regions model)})))

(defn- path-prefix?
  [prefix path]
  (and (<= (count prefix) (count path))
       (= prefix (vec (take (count prefix) path)))))

(defn state-region [descriptor path]
  (some (fn [region]
          (when (some #(path-prefix? % path) (:paths region)) region))
        (:state/regions descriptor)))

(defn- authority-at-path [region path]
  (or (some (fn [[registered-path authority]]
              (when (path-prefix? registered-path path) authority))
            (:region/path-authority region))
      (:region/authority region)
      :unknown))

(defn explain-path
  "Explain a registered path without inferring semantics from its name."
  ([value path] (explain-path value path {}))
  ([value path context]
   (let [descriptor (describe-state value context)
         region (state-region descriptor path)
         relationship (some (fn [r]
                              (when (or (= path (get-in r [:source :path]))
                                        (some #(= path (:path %)) (:targets r))) r))
                            (:state/relationships descriptor))]
     {:path path :value (get-in value path) :region (:region/id region)
      :authority (authority-at-path region path)
      :relationship (:relationship/id relationship)
      :source (get-in relationship [:source :path])
      :verification (:verification relationship)})))

(defn state-summary
  ([value] (state-summary value {}))
  ([value context]
   (let [descriptor (describe-state value context)]
     {:descriptor descriptor
      :top-level-key-count (if (map? value) (count value) 0)
      :regions (mapv (fn [region]
                       (assoc (select-keys region [:region/id :region/authority])
                              :paths (mapv (fn [path] {:path path :present? (contains? (if (= 1 (count path)) value (get-in value (butlast path) {})) (last path))})
                                           (:paths region))))
                     (:state/regions descriptor))})))

(declare state-assurance)

(defn inspect-state
  "Public inspection entry point. Returns a descriptor, compact structural
   summary, and only registered assurance checks."
  ([value] (inspect-state value {}))
  ([value context]
   {:state/value value
    :state/descriptor (describe-state value context)
    :state/summary (state-summary value context)
    :state/assurance (state-assurance value context)}))

(defn state-assurance
  "Run only registered, existing assurance checks. Absence is not success."
  ([value] (state-assurance value {}))
  ([value context]
   (let [model-id (get-in (describe-state value context) [:state/model :state/model-id])]
     (if (and (= model-id :sew/runtime) (seq (:held-adjustments value)))
       (try
         (let [reconstructed (custody/replay-held-adjustment-state (:held-adjustments value))
               targets [:held-ledger/index :total-held :held/positions]
               matches? (every? #(= (get value %) (get reconstructed %)) targets)
               aliases? (held-index/reconcile? value)]
           [{:assurance/id :state/held-custody-reconstruction
             :status (if matches? :ok :failed)
             :independent? true
             :checks (into {} (map (fn [k] [k (= (get value k) (get reconstructed k))]) targets))}
            {:assurance/id :state/held-ledger-aliases
             :status (if aliases? :ok :failed)}])
         (catch Exception e
           [{:assurance/id :state/held-custody-reconstruction :status :failed
             :reason :reconstruction-error :detail (.getMessage e)}]))
       [{:assurance/id :state/held-custody-reconstruction
         :status :not-applicable
         :reason :no-complete-held-adjustment-history}]))))

(defn- diff-values [before after path]
  (cond
    (= before after) []
    (and (vector? before) (vector? after)
         (<= (count before) (count after))
         (= before (subvec after 0 (count before))))
    [{:path path :change :append :count-before (count before) :count-after (count after)
      :appended (subvec after (count before))}]
    (and (map? before) (map? after))
    (mapcat (fn [key]
              (cond
                (not (contains? before key)) [{:path (conj path key) :change :added :after (get after key)}]
                (not (contains? after key)) [{:path (conj path key) :change :removed :before (get before key)}]
                :else (diff-values (get before key) (get after key) (conj path key))))
            (sort-by pr-str (set/union (set (keys before)) (set (keys after)))))
    :else [{:path path :change :changed :before before :after after
            :delta (when (and (number? before) (number? after)) (- after before))}]))

(defn diff-state
  "Pure semantic structural diff. Vector extensions are reported as :append."
  ([before after] (diff-state before after {}))
  ([before after context]
   (let [descriptor (describe-state after context)
         changes (vec (diff-values before after []))
         classified (mapv (fn [change]
                            (let [region (state-region descriptor (:path change))]
                              (assoc change :region (:region/id region)
                                     :authority (authority-at-path region (:path change)))))
                          changes)]
     {:changes classified
      :changed-regions (->> classified (map :region) (remove nil?) set)
      :unclassified-paths (->> classified (filter #(nil? (:region %))) (mapv :path))})))

(defn explain-transition
  "Describe a transition without executing or interpreting protocol semantics."
  [{:keys [before after event context]}]
  (let [before-time (state-time before context)
        after-time (state-time after (merge context {:event-at (:time event)}))
        elapsed (when (and (number? (:block-time before-time)) (number? (:block-time after-time)))
                  (- (:block-time after-time) (:block-time before-time)))]
    {:event event :time {:before before-time :after after-time :elapsed elapsed}
     :diff (diff-state before after context)
     :assurance (state-assurance after context)}))

(defn transition-lineage
  "Project replay trace entries to temporal lineage; snapshots remain values owned by replay."
  [trace]
  (mapv (fn [entry]
          {:lineage/kind :trace
           :sequence (:seq entry)
           :event {:action (:action entry) :agent (:agent entry)}
           :time {:before (:time-before entry) :after (:time-after entry)}
           :result (:result entry)}) trace))

(defn representation-lineage
  "Declared representations available from a model; this is deliberately not
   temporal succession."
  [value context]
  (let [descriptor (describe-state value context)]
    (mapv #(select-keys % [:relationship/id :relationship/type :target-model :source :targets :verification])
          (:state/relationships descriptor))))
