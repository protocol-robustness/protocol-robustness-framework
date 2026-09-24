(ns resolver-sim.research.distributed-scheduler
  "Deterministic, locally simulated distributed DAG execution for research.

   This is semantic distributed execution, not a physical remote-worker
   runtime. All placement, topology, transfer, retry, and failure decisions are
   derived from rooted workload/profile/policy inputs."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.research.distributed-failure :as failure]
            [resolver-sim.research.distributed-policy :as policy]
            [resolver-sim.research.distributed-profile :as profile]
            [resolver-sim.research.scheduler-harness :as harness]))

(def ^:const attempt-schema "distributed-execution-attempt.v1")
(def ^:const run-schema "distributed-scheduler-run.v1")

(defn- root [domain value]
  (hash-ref/sha256-ref (hc/domain-hash domain value)))

(defn run-root [run]
  (root :distributed-scheduler-run-v1
        (dissoc run :distributed-scheduler-run/root)))

(defn valid-run?
  "Validate a successful distributed run as a closed canonical artifact."
  [run]
  (and (map? run)
       (= run-schema (:artifact/schema run))
       (= :complete (:execution/status run))
       (= (:committed/root run) (:expected/root run))
       (hash-ref/valid-sha256-ref? (:committed/root run))
       (= (:distributed-scheduler-run/root run)
          (try (run-root run) (catch Exception _ nil)))))

(defn- task-result [task parents]
  (harness/rooted :scheduler-task-result.v1
                  {:task/id (:id task) :parents parents :pieces (:pieces task)}))

(defn- dependency-set [state task]
  (let [dependencies
        (mapv (fn [parent-id]
                {:dependency/task-id parent-id
                 :dependency/root (get-in state [:done parent-id])
                 :dependency/source-sites
                 (vec (sort (conj (set (get-in state [:profile :dependency/replicas parent-id] []))
                                  (get-in state [:placements parent-id :site-id]))))})
              (:deps task))
        body {:task/id (:id task) :dependencies dependencies}]
    (assoc body :distributed-dependency-set/root
           (root :distributed-dependency-set-v1 body))))

(defn- worker-kind [task worker]
  (first (sort-by (juxt #(get-in task [:costs %]) str)
                  (filter #(contains? (:costs task) %)
                          (:worker/resource-kinds worker)))))

(defn- eligible-workers [state task]
  (filterv #(worker-kind task %) (get-in state [:profile :topology/workers])))

(defn- source-transfer [state dependency target-site]
  (let [profile (:profile state)
        candidates
        (for [source (:dependency/source-sites dependency)
              :let [source-site (profile/site profile source)
                    transfer (profile/transfer profile source target-site
                                               (get-in state [:tasks (:task-id state) :bytes] 0))]
              :when (and (:site/available? source-site) transfer)]
          (assoc transfer :transfer/from-site source :transfer/to-site target-site))]
    (first (sort-by (juxt #(if (:transfer/available? %) 0 1)
                          :transfer/time-ms (comp str :transfer/from-site))
                    candidates))))

(defn- dependency-transfers [state task worker]
  (let [dependency-set (dependency-set state task)
        target-site (:worker/site-id worker)
        transfers (mapv #(source-transfer (assoc state :task-id (:id task)) % target-site)
                        (:dependencies dependency-set))]
    {:dependency-set dependency-set
     :transfers transfers
     :all-obtainable? (every? #(and % (:transfer/available? %)) transfers)
     :transfer-ms (reduce + 0 (map #(or (:transfer/time-ms %) 0) transfers))
     :transfer-bytes (reduce + 0 (map #(or (:transfer/bytes %) 0) transfers))}))

(defn- worker-score [state task worker]
  (let [kind (worker-kind task worker)
        dep (dependency-transfers state task worker)
        ready (apply max 0 (map #(get-in state [:finish-times %] 0) (:deps task)))
        queue-ready (max ready (get-in state [:worker-free (:worker/id worker)] 0))
        execution (get-in task [:costs kind])]
    (case (get-in state [:policy :policy/kind])
      :local-only [(if (= (:worker/id worker) (:home task)) 0 1)
                   (str (:worker/id worker))]
      :distributed-work-stealing [(get-in state [:worker-free (:worker/id worker)] 0)
                                  (str (:worker/id worker))]
      :distributed-distance-aware [(if (:all-obtainable? dep) 0 1)
                                   (+ queue-ready execution (:transfer-ms dep))
                                   (:transfer-ms dep)
                                   (str (:worker/id worker))])))

(defn- select-worker [state task]
  (let [workers (eligible-workers state task)
        workers (if (= :local-only (get-in state [:policy :policy/kind]))
                  (filterv #(= (:worker/id %) (:home task)) workers)
                  workers)]
    (first (sort-by #(worker-score state task %) workers))))

(defn- placement-projection [state task worker dependency-info attempt-id]
  (let [body (merge (:evidence-context state)
             {:distributed-experiment-profile/root
              (get-in state [:profile :distributed-experiment-profile/root])
              :distributed-scheduler-policy/root
              (get-in state [:policy :distributed-scheduler-policy/root])
              :workload/root (get-in state [:workload :workload/root])
              :task/id (:id task)
              :execution/attempt-id attempt-id
              :worker/id (:worker/id worker)
              :site/id (:worker/site-id worker)
              :distributed-dependency-set/root
              (get-in dependency-info [:dependency-set :distributed-dependency-set/root])
              :transfer/paths (mapv #(when % (select-keys % [:transfer/path-id
                                                              :transfer/from-site
                                                              :transfer/to-site
                                                              :transfer/distance
                                                              :transfer/time-ms
                                                              :transfer/bytes]))
                                    (:transfers dependency-info))})]
    (assoc body :distributed-placement-projection/root
           (root :distributed-placement-projection-v1 body))))

(defn- attempt-id [state task-id ordinal]
  (root :distributed-execution-attempt-v1
        (merge (:evidence-context state)
        {:distributed-experiment-profile/root
         (get-in state [:profile :distributed-experiment-profile/root])
         :distributed-scheduler-policy/root
         (get-in state [:policy :distributed-scheduler-policy/root])
         :workload/root (get-in state [:workload :workload/root])
         :task/id task-id :attempt/ordinal ordinal})))

(defn- failure-cause [state worker dependency-info]
  (let [site (profile/site (:profile state) (:worker/site-id worker))]
    (cond
      (or (not (:worker/available? worker)) (not (:site/available? site)))
      :remote-worker-unreachable

      (not (:all-obtainable? dependency-info))
      :distribution-dependency-failure

      (> (:transfer-ms dependency-info)
         (get-in state [:policy :policy/transfer-timeout-ms]))
      :transfer-timeout

      :else nil)))

(defn- failure-receipt [state task worker dependency-info placement attempt cause ordinal]
  (let [failed-dependency
        (some (fn [[dependency transfer]]
                (when (or (nil? transfer) (not (:transfer/available? transfer)))
                  dependency))
              (map vector (get-in dependency-info [:dependency-set :dependencies])
                   (:transfers dependency-info)))
        failed-transfer
        (some #(when (or (nil? %) (not (:transfer/available? %))) %)
              (:transfers dependency-info))]
    (failure/build-receipt
     (cond->
      (merge (:evidence-context state)
      {:distributed-experiment-profile/root
       (get-in state [:profile :distributed-experiment-profile/root])
       :workload/root (get-in state [:workload :workload/root])
       :distributed-scheduler-policy/root
       (get-in state [:policy :distributed-scheduler-policy/root])
       :execution/attempt-id attempt
       :attempt/ordinal ordinal
       :task/id (:id task)
       :worker/id (:worker/id worker)
       :site/id (:worker/site-id worker)
       :distributed-dependency-set/root
       (get-in dependency-info [:dependency-set :distributed-dependency-set/root])
       :distributed-placement-projection/root
       (:distributed-placement-projection/root placement)
       :failure/cause cause})
       failed-dependency
       (assoc :dependency/task-id (:dependency/task-id failed-dependency)
              :dependency/root (:dependency/root failed-dependency)
              :dependency/source-sites (:dependency/source-sites failed-dependency))
       failed-transfer
       (assoc :transfer/path-id (:transfer/path-id failed-transfer)
              :transfer/from-site (:transfer/from-site failed-transfer)
              :transfer/to-site (:transfer/to-site failed-transfer)
              :transfer/distance (:transfer/distance failed-transfer)
              :transfer/time-ms (:transfer/time-ms failed-transfer))))))

(defn- successful-attempt [state task worker dependency-info placement attempt ordinal
                           start finish result-root]
  (let [body (merge (:evidence-context state)
             {:artifact/schema attempt-schema
              :distributed-experiment-profile/root
              (get-in state [:profile :distributed-experiment-profile/root])
              :workload/root (get-in state [:workload :workload/root])
              :distributed-scheduler-policy/root
              (get-in state [:policy :distributed-scheduler-policy/root])
              :execution/attempt-id attempt :attempt/ordinal ordinal
              :task/id (:id task) :worker/id (:worker/id worker)
              :site/id (:worker/site-id worker)
              :distributed-dependency-set/root
              (get-in dependency-info [:dependency-set :distributed-dependency-set/root])
              :distributed-placement-projection/root
              (:distributed-placement-projection/root placement)
              :execution/start-ms start :execution/finish-ms finish
              :execution/outcome :complete
              :task-result/root result-root})]
    (assoc body :distributed-execution-attempt/root
           (root :distributed-execution-attempt-v1 body))))

(defn- try-task [state task worker ordinal]
  (let [attempt (attempt-id state (:id task) ordinal)
        dependency-info (dependency-transfers state task worker)
        placement (placement-projection state task worker dependency-info attempt)
        cause (failure-cause state worker dependency-info)]
    (if cause
      {:failure (failure-receipt state task worker dependency-info placement
                                 attempt cause ordinal)
       :placement placement}
      (let [ready (apply max 0 (map #(get-in state [:finish-times %] 0) (:deps task)))
            start (max ready (get-in state [:worker-free (:worker/id worker)] 0))
            kind (worker-kind task worker)
            finish (+ start (:transfer-ms dependency-info) (get-in task [:costs kind]))
            result-root (task-result task (mapv (:done state) (:deps task)))
            attempt-artifact (successful-attempt state task worker dependency-info placement
                                                 attempt ordinal start finish result-root)]
        {:success {:worker worker :start start :finish finish :result-root result-root
                   :dependency-info dependency-info :placement placement
                   :attempt attempt-artifact}}))))

(defn- fallback-worker [state task failed-worker]
  (some #(when (and (= (:worker/id %) (:home task))
                     (not= (:worker/id %) (:worker/id failed-worker))) %)
        (eligible-workers state task)))

(defn- execute-task [state task]
  (let [selected (select-worker state task)]
    (when-not selected
      (throw (ex-info "No eligible distributed worker" {:task/id (:id task)})))
    (loop [ordinal 0 worker selected state state]
      (let [{:keys [success failure placement]} (try-task state task worker ordinal)]
        (if success
          (let [worker-id (get-in success [:worker :worker/id])
                site-id (get-in success [:worker :worker/site-id])]
            (-> state
                (assoc-in [:done (:id task)] (:result-root success))
                (assoc-in [:finish-times (:id task)] (:finish success))
                (assoc-in [:worker-free worker-id] (:finish success))
                (assoc-in [:placements (:id task)] {:worker-id worker-id :site-id site-id})
                (update :transfer-ms + (get-in success [:dependency-info :transfer-ms]))
                (update :transfer-bytes + (get-in success [:dependency-info :transfer-bytes]))
                (update :attempts conj (:attempt success))
                (update :placement-projections conj (:placement success))))
          (let [state (-> state
                          (update :failure-receipts conj failure)
                          (update :placement-projections conj placement))
                retry? (< ordinal (get-in state [:policy :policy/retry-limit]))
                fallback (when retry? (fallback-worker state task worker))]
            (if fallback
              (recur (inc ordinal) fallback state)
              (assoc state :terminal-failure failure))))))))

(defn- preflight-failure [workload profile policy observed-profile-root evidence-context]
  (let [attempt (root :distributed-execution-attempt-v1
                      (merge evidence-context
                      {:workload/root (:workload/root workload)
                       :distributed-experiment-profile/root
                       (:distributed-experiment-profile/root profile)
                       :observed-profile/root observed-profile-root
                       :attempt/kind :preflight}))]
    (failure/build-receipt
     (merge evidence-context
     {:distributed-experiment-profile/root
      (:distributed-experiment-profile/root profile)
      :workload/root (:workload/root workload)
      :distributed-scheduler-policy/root
      (:distributed-scheduler-policy/root policy)
      :execution/attempt-id attempt
      :failure/cause :distribution-preflight-mismatch
      :expected-profile/root (:distributed-experiment-profile/root profile)
      :observed-profile/root observed-profile-root}))))

(defn run
  "Run a rooted workload under an explicit profile and policy.

   `observed-profile-root` models a deterministic preflight observation. A
   mismatch produces a rooted non-result receipt."
  ([workload distributed-profile distributed-policy]
   (run workload distributed-profile distributed-policy
        (:distributed-experiment-profile/root distributed-profile) {}))
  ([workload distributed-profile distributed-policy observed-profile-root]
   (run workload distributed-profile distributed-policy observed-profile-root {}))
  ([workload distributed-profile distributed-policy observed-profile-root evidence-context]
   (harness/validate-workload! workload)
   (when-not (:valid? (profile/validate-profile distributed-profile))
     (throw (ex-info "Invalid distributed profile" (profile/validate-profile distributed-profile))))
   (when-not (= (:distributed-scheduler-policy/root distributed-policy)
                (policy/policy-root distributed-policy))
     (throw (ex-info "Invalid distributed policy root" {})))
   (if-not (= observed-profile-root
              (:distributed-experiment-profile/root distributed-profile))
     {:artifact/schema run-schema :execution/status :failed
      :workload/root (:workload/root workload)
      :distributed-experiment-profile/root
      (:distributed-experiment-profile/root distributed-profile)
      :distributed-scheduler-policy/root
      (:distributed-scheduler-policy/root distributed-policy)
      :failure-receipts [(preflight-failure workload distributed-profile
                                           distributed-policy observed-profile-root
                                           evidence-context)]}
     (let [initial {:workload workload :profile distributed-profile
                    :policy distributed-policy
                    :evidence-context evidence-context
                    :tasks (into {} (map (juxt :id identity) (:tasks workload)))
                    :worker-free (zipmap (map :worker/id (:topology/workers distributed-profile))
                                         (repeat 0))
                    :done {} :finish-times {} :placements {}
                    :attempts [] :failure-receipts [] :placement-projections []
                    :transfer-ms 0 :transfer-bytes 0}
           final (reduce (fn [state task]
                           (if (:terminal-failure state)
                             (reduced state)
                             (execute-task state task)))
                         initial (:tasks workload))]
       (if (:terminal-failure final)
         {:artifact/schema run-schema :execution/status :failed
          :workload/root (:workload/root workload)
          :distributed-experiment-profile/root
          (:distributed-experiment-profile/root distributed-profile)
          :distributed-scheduler-policy/root
          (:distributed-scheduler-policy/root distributed-policy)
          :execution/attempts (:attempts final)
          :placement/projections (:placement-projections final)
          :failure-receipts (:failure-receipts final)}
         (let [expected (harness/canonical-root workload)
               committed (get-in final [:done (dec (count (:tasks workload)))])
               makespan (apply max 0 (vals (:finish-times final)))
               body {:artifact/schema run-schema :execution/status :complete
                     :workload/root (:workload/root workload)
                     :distributed-experiment-profile/root
                     (:distributed-experiment-profile/root distributed-profile)
                     :distributed-scheduler-policy/root
                     (:distributed-scheduler-policy/root distributed-policy)
                     :execution/attempts (:attempts final)
                     :placement/projections (:placement-projections final)
                     :failure-receipts (:failure-receipts final)
                     :task/placements (:placements final)
                     :metrics {:makespan-ms makespan
                               :data-movement-ms (:transfer-ms final)
                               :data-movement-bytes (:transfer-bytes final)
                               :distributed-failure-count (count (:failure-receipts final))
                               :committed-root-agreement (if (= expected committed) 1 0)}
                     :committed/root committed :expected/root expected}
               rooted (assoc body :distributed-scheduler-run/root
                             (run-root body))]
           (when-not (= expected committed)
             (throw (ex-info "Distributed execution changed canonical task semantics" rooted)))
           rooted))))))

(defn distance-sweep
  "Run an exact integer sensitivity sweep by replacing every declared
   cross-site link distance. Each substitution creates a new rooted profile.
   Threshold interpretation is intentionally left to downstream analysis."
  [workload base-profile distributed-policy distances]
  (mapv
   (fn [distance]
     (when-not (and (integer? distance) (not (neg? distance)))
       (throw (ex-info "Sweep distances must be nonnegative integers"
                       {:distance distance})))
     (let [profile'
           (profile/build-profile
            {:sites (:topology/sites base-profile)
             :workers (:topology/workers base-profile)
             :links (mapv #(assoc % :link/distance distance)
                          (:topology/links base-profile))
             :dependency-replicas (:dependency/replicas base-profile)})
           result (run workload profile' distributed-policy)]
       {:distance distance
        :distributed-experiment-profile/root
        (:distributed-experiment-profile/root profile')
        :execution/status (:execution/status result)
        :makespan-ms (get-in result [:metrics :makespan-ms])
        :data-movement-ms (get-in result [:metrics :data-movement-ms])
        :committed/root (:committed/root result)
        :distributed-scheduler-run/root (:distributed-scheduler-run/root result)}))
   distances))
