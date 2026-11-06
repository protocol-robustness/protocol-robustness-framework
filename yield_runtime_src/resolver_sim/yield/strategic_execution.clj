(ns resolver-sim.yield.strategic-execution
  "Production allocation and bounded, public-order recovery replay."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.signed-external-decision :as signed]
            [resolver-sim.yield.partial-fill :as pf]))

(def permission-domain "resolver-sim/partial-fill-state-permission.v1")
(def permission-role :partial-fill-state-permission-authority)

(defn content-root [kind content]
  (hc/domain-hash :projection-artifact
                  {:artifact/kind kind :content (hc/project-committable-content content)}))

(defn- require! [condition reason]
  (when-not condition (throw (ex-info (name reason) {:reason reason}))))

(defn- bounded-units? [n]
  (and (integer? n) (<= 0 n Long/MAX_VALUE)))

(defn- unique? [xs] (= (count xs) (count (distinct xs))))

(defn- guarded [f]
  (try (f)
       (catch Exception e
         {:status :inconclusive :reason (or (:reason (ex-data e)) :invalid-execution-input)})))

(defn allocate-owned-rows
  "Run production allocation, independently verify it, and score the same owners.
   Waterfall rows may share :bucket while keeping unique :key row identities.
   Amounts must fit production longs."
  [{:keys [available policy rows] :as input}]
  (guarded
   (fn []
     (require! (and (bounded-units? available) (vector? rows) (<= (count rows) 1000)
                    (unique? (map :row/id rows)) (unique? (map :key rows)))
               :row-identity-not-supported)
     (doseq [row rows]
       (require! (and (every? #(some? (get row %))
                              [:row/id :owner/id :entitlement/id :key :obligation-id :source-position-id])
                      (every? #(bounded-units? (get row %)) [:owed :weight :cap])
                      (<= (:cap row) (:owed row))) :invalid-owned-row))
     (require! (bounded-units? (reduce +' 0 (map :owed rows))) :allocation-overflow)
     (require! (bounded-units? (reduce +' 0 (map :weight rows))) :allocation-overflow)
     (let [producer (case (:mode policy)
                      :pro-rata pf/calculate-fulfillment-pro-rata
                      :principal-first pf/calculate-fulfillment-principal-first
                      :waterfall pf/calculate-fulfillment-waterfall
                      nil)
           _ (require! producer :unsupported-policy)
           requested (into {} (map (juxt :key :owed) rows))
           authoritative {:available available :requested requested :policy policy :rows rows}
           decision (producer available requested policy {:rows rows})
           verification (pf/verify-semantic-decision authoritative decision)]
       (require! (:valid? verification) :production-decision-invalid)
       {:status :verified :decision decision
        :payout-by-owner (reduce (fn [m row]
                                   (update m (:owner/id row) (fnil +' 0)
                                           (get-in decision [:filled (:key row)] 0))) {} rows)
        :evidence/root (content-root :partial-fill-owned-allocation.v1
                                     {:input input :decision decision})}))))

(defn compare-owned-transformation
  "Compare conserved entitlement rights, then exact aggregate payouts by owner."
  [input transformed-rows]
  (guarded
   (fn []
     (let [mode (get-in input [:policy :mode])
           rights (fn [rows]
                    (reduce (fn [m row]
                              (update m [(:entitlement/id row) (:owner/id row)
                                         (when (= :waterfall mode)
                                           (get row :bucket (:key row)))]
                                      (fnil #(mapv +' % [(:owed row) (:weight row) (:cap row)]) [0 0 0])))
                            {} rows))
           _ (require! (= (rights (:rows input)) (rights transformed-rows)) :rights-not-conserved)
           before (allocate-owned-rows input)
           after (allocate-owned-rows (assoc input :rows transformed-rows))]
       (require! (and (= :verified (:status before)) (= :verified (:status after)))
                 :transformation-allocation-invalid)
       {:status :verified :before before :after after
        :gain-by-owner (into {} (map (fn [[owner amount]]
                                       [owner (- (get-in after [:payout-by-owner owner] 0) amount)])
                                     (:payout-by-owner before)))
        :evidence/root (content-root :partial-fill-owned-comparison.v1
                                     [(:evidence/root before) (:evidence/root after)])}))))

(defn prepare-execution-model
  "Bind an explicit credit schedule and fixed public plans to a prepared model.
   V1 supports claim-now/defer actions, zero costs and fixed submission ordering."
  [prepared execution]
  (guarded
   (fn []
     (let [model (:model prepared)
           players (set (map :player/id (:players model)))
           horizon (get-in model [:terminal :horizon-round])
           branches (:branches execution)
           plans (:plans execution)
           order (:submission-order execution)
           model-root (hc/domain-hash :projection-artifact
                                      {:artifact/kind :partial-fill-recovery-game-model.v1
                                       :model (dissoc model :assumption-attestation :action-permission-attestation)})]
       (require! (= :complete (:parameterization/status prepared)) :recovery-model-incomplete)
       (require! (and (<= 1 (count players) 100) (integer? horizon) (<= 0 horizon 999)
                      (<= (count (:entitlements model)) 1000)
                      (unique? (map :entitlement/id (:entitlements model)))
                      (every? #(and (some? (:entitlement/id %)) (players (:player/id %))
                                    (bounded-units? (:amount %))) (:entitlements model)))
                 :execution-model-invalid)
       (require! (and (= "partial-fill-strategic-execution.v1" (:schema-version execution))
                      (= model-root (:recovery-model/root execution))
                      (= :fixed-public-order (:ordering execution))
                      (= :zero (:cost-schedule execution))
                      (#{:pro-rata :principal-first :waterfall} (get-in execution [:policy :mode]))
                      (#{:floor :floor-and-carry :largest-remainder :floor-with-largest-remainder}
                       (get-in execution [:policy :rounding-policy]))
                      (= :public (get-in model [:information-model :visibility]))
                      (true? (get-in model [:information-model :recovery-known-before-action?]))
                      (bounded-units? (:initial-cash execution))
                      (map? plans) (= players (set (keys plans)))
                      (vector? order) (<= (count order) 1000) (unique? order)
                      (vector? branches) (<= 1 (count branches) 100)
                      (unique? (map :branch/id branches))) :execution-model-invalid)
       (doseq [[player choices] plans]
         (require! (and (map? choices) (<= 1 (count choices) 100)) :plans-invalid)
         (doseq [[_ steps] choices]
           (require! (and (vector? steps) (<= (count steps) 1000)) :plan-budget-exceeded)
           (doseq [step steps]
             (require! (and (integer? (:round/index step)) (<= 0 (:round/index step) horizon)
                            (some #{(:submission/id step)} order)
                            (#{:claim-now :defer} (:action step))
                            (= player (get-in step [:row :owner/id]))) :plan-action-invalid))))
       (require! (<= (reduce +' 0 (for [[_ choices] plans [_ steps] choices] (count steps))) 10000)
                 :plan-budget-exceeded)
       (doseq [branch branches]
         (let [deposits (:deposits branch)]
           (require! (and (some? (:branch/id branch))
                          (bounded-units? (:probability-bps branch))
                          (vector? deposits) (<= (count deposits) 1000)
                          (unique? (map :event/id deposits))
                          (every? #(and (some? (:event/id %)) (bounded-units? (:amount %))
                                        (integer? (:round/index %)) (<= 0 (:round/index %) horizon)) deposits)
                          (bounded-units? (reduce +' (:initial-cash execution) (map :amount deposits))))
                     :branch-invalid)))
       (require! (= 10000 (reduce + (map :probability-bps branches))) :branch-probabilities-invalid)
       (let [process (:recovery-process model)]
         (require! (if (= :explicit-probabilities (:probability-model process))
                     (and (= (into {} (map (juxt :outcome/id :probability-bps) (:outcomes process)))
                             (into {} (map (juxt :branch/id :probability-bps) branches)))
                          (every? (fn [branch]
                                    (= (reduce +' 0 (map :amount (:deposits branch)))
                                       (:recovery-amount (some #(when (= (:outcome/id %) (:branch/id branch)) %)
                                                               (:outcomes process))))) branches))
                     (and (= 1 (count branches)) (= :deterministic (:branch/id (first branches)))))
                   :branch-model-mismatch))
       {:status :verified :model model :execution execution
        :execution/root (content-root :partial-fill-execution-model.v1 execution)}))))

(defn verify-state-permission
  "Resolve a bounded signed grant by exact root and verify its current state binding."
  [context source-root {:keys [permission-sources permission-trust-policy]}]
  (guarded
   (fn []
     (let [grant (get permission-sources source-root)
           _ (require! (and (map? grant) (<= (count (pr-str grant)) 65536)) :permission-unresolved)
           _ (require! (= source-root (content-root :partial-fill-state-permission.v1 grant))
                       :permission-root-mismatch)
           signature (signed/verify-envelope grant permission-domain permission-trust-policy permission-role)]
       (require! (:valid? signature) :permission-signature-invalid)
       (require! (and (= "partial-fill-state-permission.v1" (:schema-version grant))
                      (= :permitted (:permission/status grant))
                      (every? #(= (get context %) (get grant %))
                              [:execution/root :player/id :entitlement/id :action :round/index :pre-state/root :priority])
                      (bounded-units? (:amount-limit grant)) (bounded-units? (:cap-limit grant))
                      (<= (:amount context) (:amount-limit grant))
                      (<= (:cap context) (:cap-limit grant))) :permission-context-mismatch)
       {:status :verified :source/root source-root :key-id (:key-id signature)}))))

(defn initial-state [prepared-execution]
  {:cash (get-in prepared-execution [:execution :initial-cash])
   :balances (into {} (map (juxt :entitlement/id :amount)
                           (get-in prepared-execution [:model :entitlements])))
   :credited #{} :submissions {} :payments {}})

(defn state-root [state] (content-root :partial-fill-execution-state.v1 state))

(defn replay-profile
  "Replay a complete joint plan/branch with signed permissions at every new action.
   Trace steps assert pre/post roots and production decisions; outcomes are derived."
  [prepared-execution choices branch-id trace opts]
  (guarded
   (fn []
     (require! (= :verified (:status prepared-execution)) :execution-model-incomplete)
     (require! (= :verified (:status (prepare-execution-model
                                      {:parameterization/status :complete :model (:model prepared-execution)}
                                      (:execution prepared-execution)))) :execution-model-invalid)
     (require! (= (:execution/root prepared-execution)
                  (content-root :partial-fill-execution-model.v1 (:execution prepared-execution)))
               :execution-root-mismatch)
     (let [{:keys [model execution]} prepared-execution
           branch (some #(when (= branch-id (:branch/id %)) %) (:branches execution))
           _ (require! branch :branch-not-declared)
           _ (require! (= (set (keys choices)) (set (keys (:plans execution)))) :profile-incomplete)
           plans (mapv (fn [[player choice]]
                         (let [steps (get-in execution [:plans player choice])]
                           (require! (vector? steps) :unknown-plan) steps)) choices)
           steps (sort-by (juxt :round/index #(get (zipmap (:submission-order execution) (range))
                                                   (:submission/id %))) (mapcat identity plans))
           _ (require! (every? (fn [[_ group]]
                                 (= 1 (count (set (map #(get-in % [:row :entitlement/id]) group)))))
                               (group-by :submission/id steps)) :ambiguous-submission-order)
           _ (require! (and (vector? (:steps trace)) (<= (count steps) 1000)
                            (= "partial-fill-execution-trace.v1" (:schema-version trace))
                            (= branch-id (:branch/id trace)) (= choices (:actions trace))
                            (= (count steps) (count (:steps trace)))
                            (= (:execution/root prepared-execution) (:execution/root trace))
                            (= (state-root (initial-state prepared-execution)) (:initial-state/root trace)))
                       :trace-binding-invalid)
           owners (into {} (map (juxt :entitlement/id :player/id) (:entitlements model)))
           horizon (get-in model [:terminal :horizon-round])
           replayed
           (reduce
            (fn [{:keys [state cursor delay]} round-index]
              (let [deposits (filter #(= round-index (:round/index %)) (:deposits branch))
                    state (reduce (fn [s event]
                                    (require! (not ((:credited s) (:event/id event))) :duplicate-deposit)
                                    (-> s (update :cash + (:amount event))
                                        (update :credited conj (:event/id event)))) state deposits)
                    round-steps (filter #(= round-index (:round/index %)) steps)
                    result
                    (reduce
                     (fn [{:keys [state cursor]} step]
                       (let [assertion (nth (:steps trace) cursor)
                             row (:row step) entitlement (:entitlement/id row)
                             _ (require! (= (:owner/id row) (get owners entitlement)) :entitlement-owner-invalid)
                             prior (get-in state [:submissions [entitlement (:submission/id step)]])
                             _ (require! (= (state-root state) (:pre-state/root assertion)) :pre-state-mismatch)
                             _ (require! (= (select-keys step [:round/index :submission/id :action])
                                            (select-keys assertion [:round/index :submission/id :action])) :trace-action-mismatch)
                             next-state
                             (if prior
                               (do (require! (= (dissoc step :round/index)
                                                (dissoc (:step prior) :round/index)) :changed-retry)
                                   (require! (= (:decision prior) (:decision assertion)) :retry-result-mismatch)
                                   state)
                               (let [defer? (= :defer (:action step))
                                     _ (require! (and (bounded-units? (:owed row))
                                                      (<= (:owed row) (get-in state [:balances entitlement] -1))
                                                      (or (not defer?) (zero? (:owed row)))) :entitlement-exceeded)
                                     permission (verify-state-permission
                                                 {:execution/root (:execution/root prepared-execution)
                                                  :player/id (:owner/id row) :entitlement/id entitlement
                                                  :action (:action step) :round/index round-index
                                                  :pre-state/root (state-root state) :priority (:key row)
                                                  :amount (:owed row) :cap (:cap row)}
                                                 (:permission/root assertion) opts)
                                     _ (require! (= :verified (:status permission)) (:reason permission))
                                     allocation (allocate-owned-rows {:available (:cash state)
                                                                      :policy (:policy execution) :rows [row]})
                                     _ (require! (= :verified (:status allocation)) :settlement-invalid)
                                     _ (require! (= (:decision allocation) (:decision assertion)) :trace-decision-mismatch)
                                     amount (get-in allocation [:decision :filled (:key row)] 0)]
                                 (-> state
                                     (update :cash - amount)
                                     (update-in [:balances entitlement] - amount)
                                     (update-in [:payments (:owner/id row)] (fnil conj [])
                                                {:amount amount :round/index round-index})
                                     (assoc-in [:submissions [entitlement (:submission/id step)]]
                                               {:step step :decision (:decision allocation)}))))]
                         (require! (= (state-root next-state) (:post-state/root assertion)) :post-state-mismatch)
                         {:state next-state :cursor (inc cursor)}))
                     {:state state :cursor cursor} round-steps)]
                (assoc result :delay
                       (reduce (fn [d player]
                                 (if (and (< round-index horizon)
                                          (some (fn [[id balance]] (and (= player (get owners id)) (pos? balance)))
                                                (get-in result [:state :balances])))
                                   (update d player (fnil inc 0)) d)) delay (keys choices)))))
            {:state (initial-state prepared-execution) :cursor 0 :delay {}} (range (inc horizon)))
           terminal (:state replayed)
           _ (when (contains? trace :terminal-state/root)
               (require! (= (:terminal-state/root trace) (state-root terminal)) :terminal-state-mismatch))]
       {:status :verified :terminal-state terminal
        :outcome {:by-player (into {} (map (fn [player]
                                             [player {:payments (get-in terminal [:payments player] [])
                                                      :delay-rounds (get-in replayed [:delay player] 0)
                                                      :expected-unrecovered (reduce +' 0 (for [[id balance] (:balances terminal)
                                                                                               :when (= player (get owners id))] balance))
                                                      :fees 0 :priority-externality 0 :penalty-count 0}]) (keys choices)))}
        :trace/root (content-root :partial-fill-execution-trace.v1 trace)
        :terminal-state/root (state-root terminal)}))))
