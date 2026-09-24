(ns resolver-sim.research.scheduler-harness
  "Deterministic discrete-event experiment for local, rooted agent DAGs.
   Time is simulated in integer milliseconds; no hardware speedup is claimed."
  (:require [resolver-sim.benchmark.research-definition :as research]
            [resolver-sim.benchmark.research-analysis-comparison :as analysis-comparison]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def policies [:static :work-stealing :locality-stealing :resource-aware-stealing
               :speculative-stealing])
(def workers [{:id :cpu-0 :kind :cpu} {:id :cpu-1 :kind :cpu}
              {:id :gpu-0 :kind :gpu} {:id :tool-0 :kind :tool}])
(def worker-by-id (into {} (map (juxt :id identity) workers)))
(def metric-ids [:makespan-ms :critical-path-latency-ms :critical-path-lower-bound-ms
                 :utilization-permille :cpu-utilization-permille :gpu-utilization-permille
                 :tool-utilization-permille :steals :failed-steals
                 :speculative-tasks-executed :speculative-tasks-accepted
                 :speculative-tasks-discarded :speculative-work-accepted-ms
                 :speculative-work-discarded-ms :speculative-predictions-correct
                 :speculative-predictions-failed
                 :speculative-discard-verification-failed :verification-operations
                 :verifier-cost-ms :data-movement-bytes :data-movement-ms
                 :resource-interference-ms :committed-root-agreement])

(defn rooted [domain value]
  (hash-ref/sha256-ref
   (hc/domain-hash (str "research/scheduler/" (name domain) "\u0000") value)))

(defn workload
  "A single sink DAG. `false-predictions` is explicit injected failure, not
   information exposed to the scheduler. All variants receive the same root."
  [{:keys [id cpu-scale gpu-scale tool-scale transfer-bytes false-predictions]
    :or {id :balanced cpu-scale 1 gpu-scale 1 tool-scale 1 transfer-bytes 4
         false-predictions #{4}}}]
  (let [tasks [{:id 0 :deps [] :costs {:cpu 10} :home :cpu-0 :pieces 2 :heavy? true}
               {:id 1 :deps [] :costs {:cpu 18} :home :cpu-0 :pieces 2 :heavy? true}
               {:id 2 :deps [] :costs {:tool 13} :home :tool-0}
               {:id 3 :deps [0] :costs {:cpu 13 :gpu 5} :home :gpu-0 :heavy? true}
               {:id 4 :deps [1] :costs {:cpu 15 :gpu 7} :home :gpu-0 :heavy? true}
               {:id 5 :deps [2] :costs {:cpu 7} :home :cpu-0}
               {:id 6 :deps [1] :costs {:cpu 22 :gpu 10} :home :gpu-0 :heavy? true}
               {:id 7 :deps [0 2] :costs {:cpu 11} :home :cpu-0 :pieces 2}
               {:id 8 :deps [3 4] :costs {:gpu 12} :home :gpu-0 :heavy? true}
               {:id 9 :deps [5 6] :costs {:cpu 12} :home :cpu-0 :pieces 2}
               {:id 10 :deps [7] :costs {:cpu 17 :gpu 8} :home :cpu-0 :pieces 2 :heavy? true}
               {:id 11 :deps [8 9] :costs {:tool 8} :home :tool-0}
               {:id 12 :deps [10 11] :costs {:cpu 6} :home :cpu-0}]
        tasks (mapv (fn [task]
                      (-> task
                          (update :costs (fn [costs]
                                           (into {} (map (fn [[kind cost]]
                                                           [kind (* cost (case kind
                                                                           :gpu gpu-scale
                                                                           :tool tool-scale
                                                                           cpu-scale))])
                                                         costs))))
                          (assoc :bytes transfer-bytes :pieces (or (:pieces task) 1))))
                    tasks)
        by-id (into {} (map (juxt :id identity) tasks))
        truth (reduce (fn [m task]
                        (assoc m (:id task)
                               (rooted :scheduler-task-result.v1
                                       {:task/id (:id task)
                                        :parents (mapv m (:deps task))
                                        :pieces (:pieces task)})))
                      {} tasks)
        predictions (into {}
                          (for [[child parent] {3 0, 4 1, 6 1}]
                            [child {:parent parent
                                    :injection/success? (not (contains? false-predictions child))
                                    :predicted-root (if (contains? false-predictions child)
                                                      (rooted :scheduler-wrong-prediction.v1
                                                              {:case id :child child})
                                                      (truth parent))}]))
        base {:schema-version "scheduler-workload.v1"
              :case/id id :tasks tasks :predictions predictions
              :resources workers :transfer-bytes transfer-bytes}]
    (when-not (= (set (range (count tasks))) (set (keys by-id)))
      (throw (ex-info "Task IDs must be dense and topological" {})))
    (assoc base :workload/root (rooted :scheduler-workload.v1 base))))

(defn- task-result [task parents]
  (rooted :scheduler-task-result.v1
          {:task/id (:id task) :parents parents :pieces (:pieces task)}))

(declare eligible? movement)

(defn canonical-root [workload]
  (let [truth (reduce (fn [m task]
                        (assoc m (:id task)
                               (task-result task (mapv m (:deps task)))))
                      {} (:tasks workload))]
    (truth (dec (count (:tasks workload))))))

(defn validate-workload!
  "Validate the rooted experimental fixture before any policy sees it."
  [workload]
  (let [tasks (:tasks workload)
        ids (mapv :id tasks)
        truth (reduce (fn [m task]
                        (assoc m (:id task)
                               (task-result task (mapv m (:deps task)))))
                      {} tasks)]
    (when-not (and (= "scheduler-workload.v1" (:schema-version workload))
                   (= (:workload/root workload)
                      (rooted :scheduler-workload.v1 (dissoc workload :workload/root)))
                   (= ids (vec (range (count tasks))))
                   (= (mapv :id workers) (mapv :id (:resources workload)))
                   (every? (fn [task]
                             (and (every? #(< % (:id task)) (:deps task))
                                  (every? integer? (:deps task))
                                  (pos-int? (:pieces task))
                                  (every? pos-int? (vals (:costs task)))
                                  (eligible? task (:home task)))) tasks)
                   (every? (fn [[child {:keys [parent predicted-root injection/success?]}]]
                             (and (contains? (set ids) child)
                                  (some #{parent} (:deps (nth tasks child)))
                                  (boolean? success?)
                                  (= success? (= predicted-root (truth parent)))))
                           (:predictions workload)))
      (throw (ex-info "Invalid or inconsistent scheduler workload" {:workload workload})))
    workload))

(defn- eligible? [task worker-id]
  (contains? (:costs task) (:kind (worker-by-id worker-id))))

(defn- queue-job [state worker-id job]
  (update-in state [:queues worker-id] (fnil conj []) job))

(defn- estimated-duration
  "Integer placement estimate used only by the resource-aware baseline.
   It models dispatch, eligible execution cost, and known parent movement."
  [state task worker-id]
  (+ 1
     (get-in task [:costs (:kind (worker-by-id worker-id))])
     (long (Math/ceil (/ (movement state task worker-id) 4.0)))))

(defn- estimated-queue-delay [state worker-id]
  (+ (get-in state [:running worker-id :remaining] 0)
     (reduce + 0
             (for [job (get-in state [:queues worker-id])
                   :when (= :task (:type job))]
               (let [task (get-in state [:tasks (:task job)])]
                 (get-in task [:costs (:kind (worker-by-id worker-id))] 0))))))

(defn- resource-aware-worker [state task]
  (first
   (sort-by (fn [worker-id]
              (let [duration (estimated-duration state task worker-id)]
                [(+ (estimated-queue-delay state worker-id) duration)
                 duration
                 (movement state task worker-id)
                 (str worker-id)]))
            (map :id (filter #(eligible? task (:id %)) workers)))))

(defn- remove-jobs [state task-id]
  (update state :queues
          (fn [queues]
            (into {} (map (fn [[worker-id jobs]]
                            [worker-id (vec (remove #(= task-id (:task %)) jobs))]) queues)))))

(defn- issue-task [state task mode]
  (let [available (count (filter #(and (eligible? task (:id %))
                                      (not (contains? (:running state) (:id %)))) workers))
        chunks (if (and (:adaptive? state) (= mode :normal)
                        (>= (:pieces task) 2) (>= available 2)
                        (>= (apply max (vals (:costs task))) 12))
                 2 1)
        task-id (:id task)
        target-worker (if (and (= :resource-aware-stealing (:policy state))
                               (= :normal mode))
                        (resource-aware-worker state task)
                        (:home task))
        state (assoc-in state [:issued task-id]
                        {:mode mode :chunks chunks :finished 0 :started? false
                         :work-ms 0 :finish-workers [] :verified? false})]
    (reduce (fn [s part]
              (queue-job s target-worker {:type :task :task task-id
                                         :part part :chunks chunks :mode mode}))
            state (range chunks))))

(defn- any-running-task? [state task-id]
  (some #(= task-id (:task %)) (vals (:running state))))

(defn- issue-ready [state]
  (reduce (fn [s task]
            (let [id (:id task)
                  deps (:deps task)
                  prediction (get-in s [:workload :predictions id])
                  parent (:parent prediction)
                  other-deps (remove #{parent} deps)]
              (cond
                (or (contains? (:done s) id) (contains? (:issued s) id)) s
                (every? #(contains? (:done s) %) deps) (issue-task s task :normal)
                (and (= :speculative-stealing (:policy s)) prediction
                     (not (contains? (:done s) parent))
                     (every? #(contains? (:done s) %) other-deps)
                     (any-running-task? s parent)) (issue-task s task :spec)
                :else s)))
          state (get-in state [:workload :tasks])))

(defn- movement [state task worker-id]
  (reduce (fn [n parent-id]
            (let [source (or (get-in state [:placement parent-id])
                             (some (fn [[id job]] (when (= parent-id (:task job)) id))
                                   (:running state)))]
              (+ n (if (and source (not= source worker-id)) (:bytes task) 0))))
          0 (:deps task)))

(defn- compatible-jobs [state worker-id donor-id]
  (for [[index job] (map-indexed vector (get-in state [:queues donor-id]))
        :when (or (and (= :verify (:type job))
                       (= :cpu (:kind (worker-by-id worker-id))))
                  (and (= :task (:type job))
                       (eligible? (get-in state [:tasks (:task job)]) worker-id)))]
    {:donor donor-id :index index :job job}))

(defn- select-job [state worker-id]
  (let [own (seq (compatible-jobs state worker-id worker-id))
        donors (remove #{worker-id} (map :id workers))
        normal? #(not= :spec (get-in % [:job :mode]))
        own-normal (first (filter normal? own))
        donor-candidates (mapcat #(compatible-jobs state worker-id %) donors)
        donor-normal (filter normal? donor-candidates)
        pick-local (fn [candidates]
                     (first (sort-by
                             (fn [{:keys [donor job]}]
                               [(if (= :verify (:type job)) 0
                                    (movement state (get-in state [:tasks (:task job)]) worker-id))
                                (get-in state [:tasks (:task job) :costs
                                               (:kind (worker-by-id worker-id))] 0)
                                (str donor) (:task job 0)])
                             candidates)))]
    (cond
      own-normal [own-normal 0]
      (= :static (:policy state)) [(first own) 0]
      (seq donor-normal)
      [(if (= :work-stealing (:policy state)) (first donor-normal)
           (pick-local donor-normal)) 0]
      (seq own) [(first own) 0]
      (seq donor-candidates)
      [(if (= :work-stealing (:policy state)) (first donor-candidates)
           (pick-local donor-candidates)) 0]
      :else [nil 1])))

(defn- take-queue-job [state {:keys [donor index]}]
  (update-in state [:queues donor]
             (fn [jobs] (vec (concat (subvec jobs 0 index)
                                     (subvec jobs (inc index)))))))

(defn- start-job [state worker-id {:keys [donor job] :as selection}]
  (let [state (take-queue-job state selection)
        stolen? (not= worker-id donor)
        state (cond-> state stolen? (update-in [:metrics :steals] inc))]
    (if (= :verify (:type job))
      (-> state
          (assoc-in [:running worker-id] (assoc job :remaining 2 :elapsed 0))
          (update-in [:metrics :verification-operations] inc)
          (update-in [:metrics :verifier-cost-ms] + 2))
      (let [task (get-in state [:tasks (:task job)])
            kind (:kind (worker-by-id worker-id))
            bytes (movement state task worker-id)
            transfer-ms (long (Math/ceil (/ bytes 4.0)))
            base-cost (get-in task [:costs kind])
            chunks (:chunks job)
            work (long (Math/ceil (/ base-cost (double chunks))))
            duration (+ 1 work transfer-ms)
            spec? (= :spec (:mode job))]
        (-> state
            (assoc-in [:running worker-id]
                      (assoc job :remaining duration :elapsed 0 :worker worker-id))
            (assoc-in [:issued (:task job) :started?] true)
            (update-in [:metrics :data-movement-bytes] + bytes)
            (update-in [:metrics :data-movement-ms] + transfer-ms)
            (cond-> spec? (update-in [:metrics :speculative-tasks-executed] inc)))))))

(defn- dispatch [state]
  (reduce (fn [s {:keys [id]}]
            (if (contains? (:running s) id)
              s
              (let [queue-signature (:queues s)]
                (if (= queue-signature (get-in s [:last-miss id]))
                  s
                  (let [[selection failed] (select-job s id)
                        s (update-in s [:metrics :failed-steals] + failed)]
                    (if selection
                      (-> s (update :last-miss dissoc id)
                          (start-job id selection))
                      (assoc-in s [:last-miss id] queue-signature)))))))
          state workers))

(defn- drop-spec [state task-id reason]
  (let [issued (get-in state [:issued task-id])
        state (remove-jobs state task-id)
        state (update state :running
                      (fn [running]
                        (into {} (remove (fn [[_ job]] (= task-id (:task job))) running))))]
    (-> state
        (update :issued dissoc task-id)
        (update-in [:metrics :speculative-tasks-discarded] inc)
        (update-in [:metrics :speculative-work-discarded-ms] + (:work-ms issued))
        (cond-> (= reason :verification-failed)
          (update-in [:metrics :speculative-discard-verification-failed] inc)))))

(declare commit-task)

(defn- after-parent-commit [state parent-id]
  (reduce (fn [s [child prediction]]
            (let [issued (get-in s [:issued child])]
              (if (and (= parent-id (:parent prediction))
                       (= :spec (:mode issued)))
                (if (:started? issued)
                  (-> s
                      (assoc-in [:issued child :verification-issued?] true)
                      (queue-job :cpu-0 {:type :verify :task child}))
                  (-> s (remove-jobs child) (update :issued dissoc child)))
                s)))
          state (get-in state [:workload :predictions])))

(defn- commit-task [state task-id worker-id root spec?]
  (when (contains? (:done state) task-id)
    (throw (ex-info "Task committed twice" {:task task-id})))
  (let [task (get-in state [:tasks task-id])
        expected (task-result task (mapv (:done state) (:deps task)))]
    (when-not (= expected root)
      (throw (ex-info "Deterministic commit root mismatch"
                      {:task task-id :expected expected :actual root})))
    (-> state
        (assoc-in [:done task-id] root)
        (assoc-in [:placement task-id] worker-id)
        (assoc-in [:finish-times task-id] (:time state))
        (update :issued dissoc task-id)
        (cond-> spec?
          (update-in [:metrics :speculative-tasks-accepted] inc)
          spec? (update-in [:metrics :speculative-work-accepted-ms]
                           + (get-in state [:issued task-id :work-ms])))
        (after-parent-commit task-id))))

(defn- finish-job [state worker-id job]
  (let [task-id (:task job)
        state (update state :running dissoc worker-id)]
    (if (= :verify (:type job))
      (let [prediction (get-in state [:workload :predictions task-id])
            accepted? (= (:predicted-root prediction)
                         (get-in state [:done (:parent prediction)]))]
        (if accepted?
          (let [state (-> state
                          (update-in [:metrics :speculative-predictions-correct] inc)
                          (assoc-in [:issued task-id :verified?] true))
                candidate (get-in state [:issued task-id :candidate])]
            (if candidate
              (commit-task state task-id (:worker candidate) (:root candidate) true)
              state))
          (-> state
              (update-in [:metrics :speculative-predictions-failed] inc)
              (drop-spec task-id :verification-failed))))
      (let [state (-> state
                      (update-in [:issued task-id :finished] inc)
                      (update-in [:issued task-id :finish-workers] conj worker-id))
            issued (get-in state [:issued task-id])]
        (if (= (:finished issued) (:chunks issued))
          (let [task (get-in state [:tasks task-id])
                prediction (get-in state [:workload :predictions task-id])
                parents (mapv (fn [parent]
                                (if (and (= :spec (:mode issued))
                                         (= parent (:parent prediction)))
                                  (:predicted-root prediction)
                                  (get-in state [:done parent])))
                              (:deps task))
                root (task-result task parents)
                state (assoc-in state [:issued task-id :candidate]
                                {:root root :worker worker-id})]
            (if (and (= :spec (:mode issued)) (not (:verified? issued)))
              state
              (commit-task state task-id worker-id root (= :spec (:mode issued)))))
          state)))))

(defn- tick [state]
  (let [running (:running state)
        cpu-heavy? (some (fn [[worker-id job]]
                           (and (= :cpu (:kind (worker-by-id worker-id)))
                                (= :task (:type job))
                                (get-in state [:tasks (:task job) :heavy?])))
                         running)]
    (-> (reduce (fn [s [worker-id job]]
                  (let [stall? (and cpu-heavy?
                                    (= :gpu (:kind (worker-by-id worker-id)))
                                    (get-in s [:tasks (:task job) :heavy?])
                                    (odd? (:time s)))
                        s (-> s
                              (update-in [:utilization worker-id] (fnil inc 0))
                              (update-in [:running worker-id :elapsed] inc)
                              (cond-> stall?
                                (update-in [:metrics :resource-interference-ms] inc))
                              (cond-> (not stall?)
                                (update-in [:running worker-id :remaining] dec)))]
                    (if (= :spec (:mode job))
                      (update-in s [:issued (:task job) :work-ms] inc)
                      s)))
                state running)
        (update :time inc))))

(defn- finish-completed [state]
  (reduce (fn [s [worker-id job]]
            (if (and (contains? (:running s) worker-id)
                     (zero? (get-in s [:running worker-id :remaining])))
              (finish-job s worker-id job)
              s))
          state (sort-by (comp str first) (:running state))))

(defn- critical-path-lower-bound [workload]
  (let [scores (reduce (fn [m task]
                         (assoc m (:id task)
                                (+ 1 (long (Math/ceil
                                            (/ (apply min (vals (:costs task)))
                                               (double (:pieces task)))))
                                   (apply max 0 (map m (:deps task))))))
                       {} (:tasks workload))]
    (scores (dec (count (:tasks workload))))))

(defn run-policy
  "Simulate one policy on the exact supplied rooted workload. A common
   canonical task-result function is checked independently at each commit."
  [workload policy adaptive?]
  (validate-workload! workload)
  (when-not ((set policies) policy)
    (throw (ex-info "Unsupported scheduler policy" {:policy policy})))
  (let [tasks (into {} (map (juxt :id identity) (:tasks workload)))
        initial {:workload workload :tasks tasks :policy policy :adaptive? adaptive?
                 :time 0 :queues (zipmap (map :id workers) (repeat []))
                 :running {} :issued {} :done {} :placement {} :finish-times {}
                 :last-miss {}
                 :utilization (zipmap (map :id workers) (repeat 0))
                 :metrics (zipmap (remove #{:makespan-ms :critical-path-latency-ms
                                            :critical-path-lower-bound-ms
                                            :utilization-permille :cpu-utilization-permille
                                            :gpu-utilization-permille :tool-utilization-permille
                                            :committed-root-agreement} metric-ids)
                                  (repeat 0))}
        final (loop [s initial]
                (when (> (:time s) 10000)
                  (throw (ex-info "Scheduler failed to finish" {:policy policy :state s})))
                (if (= (count (:done s)) (count tasks)) s
                    (let [next (-> s finish-completed issue-ready dispatch)]
                      (if (= (count (:done next)) (count tasks))
                        (recur next)
                        (do
                          (when (empty? (:running next))
                            (throw (ex-info "Scheduler deadlocked" {:policy policy :state next})))
                          (recur (tick next)))))))
        elapsed (:time final)
        busy (reduce + (vals (:utilization final)))
        cpu-busy (+ (get-in final [:utilization :cpu-0])
                    (get-in final [:utilization :cpu-1]))
        expected (canonical-root workload)
        actual (get-in final [:done (dec (count tasks))])
        metrics (merge (:metrics final)
                       {:makespan-ms elapsed
                        :critical-path-latency-ms (get-in final [:finish-times (dec (count tasks))])
                        :critical-path-lower-bound-ms (critical-path-lower-bound workload)
                        :utilization-permille (quot (* 1000 busy) (* elapsed (count workers)))
                        :cpu-utilization-permille (quot (* 1000 cpu-busy) (* elapsed 2))
                        :gpu-utilization-permille (quot (* 1000 (get-in final [:utilization :gpu-0])) elapsed)
                        :tool-utilization-permille (quot (* 1000 (get-in final [:utilization :tool-0])) elapsed)
                        :committed-root-agreement (if (= expected actual) 1 0)})
        result {:schema-version "scheduler-run.v1"
                :workload/root (:workload/root workload)
                :scheduler/policy policy :scheduler/adaptive-granularity? adaptive?
                :scheduler/implementation-root
                (rooted :scheduler-implementation.v1
                        {:policy policy :adaptive? adaptive? :model-version 1})
                :committed/root actual :expected/root expected
                :metrics metrics :worker-busy-ms (:utilization final)
                :speculation/accounting
                {:accepted-work-ms (:speculative-work-accepted-ms metrics)
                 :discarded-work-ms (:speculative-work-discarded-ms metrics)
                 :total-executed-work-ms (+ (:speculative-work-accepted-ms metrics)
                                             (:speculative-work-discarded-ms metrics))
                 :accepted-tasks (:speculative-tasks-accepted metrics)
                 :discarded-tasks (:speculative-tasks-discarded metrics)
                 :prediction-correct (:speculative-predictions-correct metrics)
                 :prediction-failed (:speculative-predictions-failed metrics)
                 :discard-verification-failed
                 (:speculative-discard-verification-failed metrics)
                 :verification-work-ms (:verifier-cost-ms metrics)}}]
    (when-not (= expected actual)
      (throw (ex-info "Canonical committed root differs from reference" result)))
    (assoc result :scheduler-run/root (rooted :scheduler-run.v1 result))))

(defn research-source [regimes]
  {:artifact/schema research/source-schema
   :research/id :scheduler/local-agent-dag
   :research/title "Local agent DAG scheduler experiment"
   :research/question "How do dispatch, locality, speculation, and granularity affect a rooted result?"
   :research/hypotheses [{:hypothesis/id :scheduler/controlled-fixtures
                          :workload-roots (mapv :workload/root regimes)}]
   :research/vary {:regime/index (vec (range (count regimes)))
                   :granularity/adaptive [0 1]}
   :research/measures
   (mapv (fn [id]
           (let [unit (cond
                        (#{:makespan-ms :critical-path-latency-ms
                           :critical-path-lower-bound-ms
                           :speculative-work-accepted-ms :speculative-work-discarded-ms
                           :verifier-cost-ms :data-movement-ms
                           :resource-interference-ms} id) :unit/ms
                        (= id :data-movement-bytes) :unit/byte
                        (#{:utilization-permille :cpu-utilization-permille
                           :gpu-utilization-permille :tool-utilization-permille} id)
                        :unit/permille
                        :else :unit/count)]
             {:measure/id (keyword "scheduler" (name id))
              :measure/kind research/requirement-margin-kind
              :measure/domain {:kind :integer :unit unit}
              :measure/observation {:observation/id (keyword "scheduler" (name id))}
              :measure/requirement {:value (if (= id :committed-root-agreement) 1 0)
                                    :unit unit}
              :measure/satisfying-direction :at-least}))
         metric-ids)})

(defn experiment
  "Run every policy on every regime with fixed and adaptive granularity. The
   matrices are research-analysis data, not scheduler-correctness proof claims."
  [regime-params]
  (let [workloads (mapv workload regime-params)
        frozen (research/freeze-research (research-source workloads))
        cases (get-in frozen [:research-definition :research/cases])
        runs (into {}
                   (for [policy policies]
                     [policy (into {}
                                   (for [case cases
                                         :let [index (get-in case [:case/parameters :regime/index])
                                               adaptive? (pos? (get-in case [:case/parameters
                                                                             :granularity/adaptive]))]]
                                     [(:case/id case)
                                      (run-policy (workloads index) policy adaptive?)]))]))
        matrices (into {}
                       (for [policy policies]
                         [policy
                          (research/compile-result-matrix
                           frozen
                           (into {}
                                 (for [[case-id run] (runs policy)]
                                   [case-id
                                    (into {}
                                          (for [[metric value] (:metrics run)]
                                            [(keyword "scheduler" (name metric)) value]))]))
                           (rooted :scheduler-policy-subject.v1 {:policy policy}))]))
        comparisons (into {}
                          (for [policy (rest policies)]
                            [policy (research/compare-reproductions
                                     [(matrices :static) (matrices policy)])]))
        equality (every? true?
                         (for [case cases
                               :let [roots (map #(get-in runs [% (:case/id case) :committed/root]) policies)]]
                           (apply = roots)))
        result {:schema-version "scheduler-experiment.v1"
                :research-definition/root (:research-definition/root frozen)
                :case-axis/root (get-in frozen [:derived :case-axis/root])
                :measure-axis/root (get-in frozen [:derived :measure-axis/root])
                :workload-roots (mapv :workload/root workloads)
                :runs runs :matrices matrices :comparisons-to-static comparisons
                :all-committed-roots-identical? equality}]
    (when-not equality
      (throw (ex-info "Scheduler variants disagree on committed root" result)))
    (assoc result :scheduler-experiment/root (rooted :scheduler-experiment.v1 result))))

(def default-regimes
  [{:id :balanced}
   {:id :transfer-heavy :transfer-bytes 16 :false-predictions #{3 4 6}}
   {:id :gpu-slow :gpu-scale 3 :false-predictions #{}}])

(def default-sweep-axes
  "The accuracy axis is realized over three predictable children, so 333 and
   667 mean one and two successful predictions respectively."
  {:prediction/success-permille [0 333 667 1000]
   :gpu/bottleneck-scale [1 2 4]
   :transfer/bytes [0 12 24]})

(def ^:private predicted-children [3 4 6])

(defn- validate-sweep-axes! [axes]
  (let [required (set (keys default-sweep-axes))]
    (when-not (= required (set (keys axes)))
      (throw (ex-info "Sweep axes must declare accuracy, GPU, and transfer"
                      {:expected required :actual (set (keys axes))})))
    (doseq [[axis values] axes]
      (when-not (and (vector? values) (seq values) (every? integer? values)
                     (= (count values) (count (distinct values))))
        (throw (ex-info "Sweep axis must have distinct integer values"
                        {:axis axis :values values}))))
    (when-not (every? #{0 333 667 1000} (:prediction/success-permille axes))
      (throw (ex-info "Prediction accuracy must be realizable over three predictions"
                      {:values (:prediction/success-permille axes)})))
    (when-not (every? pos? (:gpu/bottleneck-scale axes))
      (throw (ex-info "GPU bottleneck scale must be positive" {:axes axes})))
    (when-not (every? #(<= 0 %) (:transfer/bytes axes))
      (throw (ex-info "Transfer bytes must be nonnegative" {:axes axes})))
    axes))

(defn sweep-workload-params
  "Generate one immutable workload descriptor for every axis coordinate.
   Accuracy is an injected fixture outcome, never scheduler-visible state."
  [axes]
  (validate-sweep-axes! axes)
  (mapv (fn [[accuracy gpu-scale transfer-bytes]]
          (let [success-count ({0 0 333 1 667 2 1000 3} accuracy)
                successes (set (take success-count predicted-children))]
            {:id (keyword "sweep"
                          (str "p" accuracy "-g" gpu-scale "-t" transfer-bytes))
             :prediction/success-permille accuracy
             :prediction/success-count success-count
             :gpu/bottleneck-scale gpu-scale
             :transfer/bytes transfer-bytes
             :gpu-scale gpu-scale
             :transfer-bytes transfer-bytes
             :false-predictions (set (remove successes predicted-children))}))
        (for [accuracy (:prediction/success-permille axes)
              gpu-scale (:gpu/bottleneck-scale axes)
              transfer-bytes (:transfer/bytes axes)]
          [accuracy gpu-scale transfer-bytes])))

(defn- sweep-research-source [axes workloads]
  {:artifact/schema research/source-schema
   :research/id :scheduler/parameter-sweep
   :research/title "Local DAG scheduler parameter sweep"
   :research/question "Which fixed scheduler has the lowest simulated makespan at each prediction accuracy, GPU bottleneck, and transfer-cost coordinate?"
   :research/hypotheses [{:hypothesis/id :scheduler/sweep-fixture-roots
                          :workload-roots (mapv :workload/root workloads)}]
   :research/vary axes
   :research/measures
   [{:measure/id :scheduler/makespan-ms
     :measure/kind research/requirement-margin-kind
     :measure/domain {:kind :integer :unit :unit/ms}
     :measure/observation {:observation/id :scheduler/makespan-ms}
     :measure/requirement {:value 0 :unit :unit/ms}
     :measure/satisfying-direction :at-least}]})

(defn- policy-analysis [frozen policy runs sweep-root]
  (let [case-axis (get-in frozen [:derived :case-axis/root])
        measure-axis (get-in frozen [:derived :measure-axis/root])
        researcher-axis (rooted :scheduler-sweep-researcher-axis.v1
                                {:researchers policies})]
    ;; Cases are already dense in the frozen definition. Build the coordinate
    ;; map from their order, avoiding unscoped caller-supplied integers.
    (let [coordinates (into {}
                            (map-indexed (fn [case-key case]
                                           [[case-key 0]
                                            (get-in runs [(:case/id case) :metrics :makespan-ms])])
                                         (get-in frozen [:research-definition :research/cases])))
          body {:research-definition/root (:research-definition/root frozen)
                :subject-roots [sweep-root]
                :case-axis/root case-axis
                :measure-axis/root measure-axis
                :researcher-axis/root researcher-axis
                :researcher {:researcher/id (str "scheduler/" (name policy))}
                :analysis/coordinates coordinates
                :analysis/policy-root (rooted :scheduler-policy-subject.v1
                                               {:policy policy :fixed-granularity true})
                :proof-of-possession nil}]
      (assoc body :research-analysis/root
             (rooted :scheduler-policy-analysis.v1 body)))))

(def default-margin-thresholds
  "Declared integer boundaries for derived margin labels. Raw deltas remain
   canonical; changing these labels never changes a scheduler observation."
  {:large-ms 10})

(defn- margin-category [thresholds delta]
  (let [large (:large-ms thresholds)]
    (cond (<= delta (- large)) :large-win
          (neg? delta) :small-win
          (zero? delta) :tie
          (< delta large) :small-loss
          :else :large-loss)))

(defn- pairwise-deltas [frozen comparisons thresholds]
  (let [cases (get-in frozen [:research-definition :research/cases])]
    (into (sorted-map)
          (for [[[left-policy right-policy] comparison]
                (sort-by (comp pr-str first) comparisons)]
            [[left-policy right-policy]
             (mapv (fn [entry]
                     (let [coordinate (:coordinate entry)
                           [case-key _] coordinate
                           left-value (:left/value entry)
                           right-value (:right/value entry)
                           delta (- left-value right-value)]
                       {:coordinate coordinate
                        :case/parameters (:case/parameters (nth cases case-key))
                        :left/policy left-policy
                        :right/policy right-policy
                        :left/makespan-ms left-value
                        :right/makespan-ms right-value
                        :pairwise/delta-ms delta
                        :pairwise/outcome (cond (neg? delta) :left-wins
                                                (pos? delta) :right-wins
                                                :else :tie)
                        :derived/margin-category (margin-category thresholds delta)}))
                   (:comparison/output comparison))]))))

(defn- global-regions-from-deltas [deltas thresholds]
  (let [by-coordinate
        (reduce (fn [acc entries]
                  (reduce (fn [a entry]
                            (let [coordinate (:coordinate entry)
                                  left-policy (:left/policy entry)
                                  right-policy (:right/policy entry)
                                  left-makespan (:left/makespan-ms entry)
                                  right-makespan (:right/makespan-ms entry)]
                              (assoc a coordinate
                                   {:case/parameters (:case/parameters entry)
                                    :policy/makespan-ms
                                    (assoc (:policy/makespan-ms (get a coordinate {}))
                                           left-policy left-makespan
                                           right-policy right-makespan)})))
                          acc entries))
                {} (vals deltas))]
    (mapv (fn [[coordinate {:keys [case/parameters policy/makespan-ms]}]]
            (let [minimum (apply min (vals makespan-ms))
                  winners (vec (filter #(= minimum (makespan-ms %)) policies))]
              {:coordinate coordinate
               :case/parameters parameters
               :policy/makespan-ms makespan-ms
               :global-region/winners winners
               :global-region/minimum-makespan-ms minimum
               :global-region/policy-outcomes
               (into (sorted-map)
                     (for [policy policies
                           :let [best-competitor (apply min
                                                        (vals (dissoc makespan-ms policy)))
                                 delta (- (makespan-ms policy) best-competitor)
                                 outcome (cond (neg? delta) :win
                                               (zero? delta) :tie
                                               :else :loss)]]
                       [policy {:global-region/outcome outcome
                                :global-region/delta-to-best-competitor-ms delta
                                :derived/margin-category
                                (margin-category thresholds delta)}]))}))
          (sort-by (comp pr-str first) by-coordinate))))

(defn- axis-constraints [coordinates]
  (into (sorted-map)
        (for [axis (keys default-sweep-axes)
              :let [values (map #(get-in % [:case/parameters axis]) coordinates)]]
          [axis {:min (apply min values) :max (apply max values)}])))

(defn- policy-regions [global-regions]
  (vec
   (mapcat (fn [policy]
             (for [outcome [:win :tie :loss]
                   :let [coordinates (vec (filter #(= outcome
                                                     (get-in % [:global-region/policy-outcomes
                                                                policy :global-region/outcome]))
                                                  global-regions))]
                   :when (seq coordinates)]
               {:policy-region {:policy policy
                                :global-region/outcome outcome
                                :constraints (axis-constraints coordinates)
                                :coordinates (mapv :case/parameters coordinates)
                                :count (count coordinates)}}))
           policies)))

(defn- human-region-summary [{:keys [policy global-region/outcome constraints count]}]
  (str (name policy) " " (name outcome) " in " count " coordinate"
       (when-not (= 1 count) "s")
       "; prediction=" (get-in constraints [:prediction/success-permille :min])
       ".." (get-in constraints [:prediction/success-permille :max])
       ", gpu=" (get-in constraints [:gpu/bottleneck-scale :min])
       ".." (get-in constraints [:gpu/bottleneck-scale :max])
       ", transfer=" (get-in constraints [:transfer/bytes :min])
       ".." (get-in constraints [:transfer/bytes :max])
       ". Exact coordinates are retained because bounds need not be contiguous."))

(defn- region-shape [axes policy-region]
  (let [{:keys [constraints coordinates]} policy-region
        coordinate-count (:count policy-region)
        axis-ids (keys axes)
        bounded-count (reduce * 1
                              (map (fn [axis]
                                     (count (filter #(<= (get-in constraints [axis :min]) %
                                                        (get-in constraints [axis :max]))
                                                    (get axes axis))))
                                   axis-ids))]
    {:policy (:policy policy-region)
     :global-region/outcome (:global-region/outcome policy-region)
     :region/shape (if (= coordinate-count bounded-count) :rectangular-tested-grid :irregular)
     :region/constraints constraints
     :region/coordinate-count coordinate-count
     :region/tested-grid-only? true
     :region/exact-coordinates coordinates}))

(defn- region-derivation [frozen comparisons thresholds axes]
  (let [deltas (pairwise-deltas frozen comparisons thresholds)
        global-regions (global-regions-from-deltas deltas thresholds)
        policy-regions (policy-regions global-regions)
        body {:schema-version "scheduler-sweep-derived-regions.v1"
              :derivation/source-comparison-roots
              (into (sorted-map)
                    (map (fn [[pair comparison]]
                           [pair (:research-analysis-comparison/root comparison)]) comparisons))
              :margin/thresholds thresholds
              :pairwise/deltas deltas
              :global/winner-regions global-regions
              :policy-regions policy-regions
              :decision-boundaries (mapv #(region-shape axes (:policy-region %)) policy-regions)
              :human-readable-region-summaries
              (mapv (comp human-region-summary :policy-region) policy-regions)}]
    (assoc body :scheduler-sweep-derived-regions/root
           (rooted :scheduler-sweep-derived-regions.v1 body))))

(defn- policy-region-summary [global-regions]
  (into (sorted-map)
        (for [policy policies]
                          [policy (reduce (fn [summary region]
                            (let [outcome (get-in region [:global-region/policy-outcomes
                                                          policy :global-region/outcome])]
                              (update summary ({:win :wins :tie :ties :loss :losses} outcome)
                                      conj (:case/parameters region))))
                          {:wins [] :ties [] :losses []}
                          global-regions)])))

(defn parameter-sweep
  "Run a fixed-granularity factorial sweep without modifying any scheduler.

   The emitted policy analyses are rooted subjects on one frozen sweep scope.
   `research-analysis-comparison` provides exact coordinate comparisons;
   `:regions` derives an explicit minimum/tie classification from makespan and
   does not promote it to a general performance claim."
  ([] (parameter-sweep default-sweep-axes))
  ([axes]
   (let [params (sweep-workload-params axes)
         workloads (mapv workload params)
         frozen (research/freeze-research (sweep-research-source axes workloads))
         cases (get-in frozen [:research-definition :research/cases])
         params-by-coordinate (into {}
                                    (map (fn [params]
                                           [(select-keys params [:prediction/success-permille
                                                                :gpu/bottleneck-scale
                                                                :transfer/bytes])
                                            params])
                                         params))
         workload-for-case (into {}
                                 (for [case cases
                                       :let [params (params-by-coordinate (:case/parameters case))]]
                                   (do
                                     (when-not params
                                       (throw (ex-info "Frozen case lacks sweep workload"
                                                       {:case case})))
                                     [(:case/id case)
                                      (workload (select-keys params [:id :gpu-scale
                                                                     :transfer-bytes
                                                                     :false-predictions]))])))
         sweep-base {:schema-version "scheduler-parameter-sweep.v1"
                     :sweep/axes axes
                     :research-definition/root (:research-definition/root frozen)
                     :case-axis/root (get-in frozen [:derived :case-axis/root])
                     :measure-axis/root (get-in frozen [:derived :measure-axis/root])
                     :workload-roots (mapv :workload/root workloads)}
         sweep-root (rooted :scheduler-parameter-sweep-subject.v1 sweep-base)
         runs (into {}
                    (for [policy policies]
                      [policy (into {}
                                    (for [[case-id workload] workload-for-case]
                                      [case-id (run-policy workload policy false)]))]))
         matrices (into {}
                        (for [policy policies]
                          [policy (research/compile-result-matrix
                                   frozen
                                   (into {}
                                         (for [[case-id run] (runs policy)]
                                           [case-id {:scheduler/makespan-ms
                                                     (get-in run [:metrics :makespan-ms])}]))
                                   (rooted :scheduler-sweep-policy-subject.v1
                                           {:sweep-root sweep-root :policy policy}))]))
         analyses (into {}
                        (for [policy policies]
                          [policy (policy-analysis frozen policy (runs policy) sweep-root)]))
         comparisons (into {}
                           (for [[left right] (for [left policies right policies
                                                    :when (neg? (compare (str left) (str right)))]
                                                [left right])]
                             [[left right]
                              (analysis-comparison/compare-analyses
                               (analyses left) (analyses right))]))
         derived-regions (region-derivation frozen comparisons default-margin-thresholds axes)
         regions (:global/winner-regions derived-regions)
         roots-identical? (every? true?
                                 (for [case cases]
                                   (apply = (map #(get-in runs [% (:case/id case) :committed/root])
                                                 policies))))
         result (assoc sweep-base
                       :sweep/subject-root sweep-root
                       :runs runs :matrices matrices :analyses analyses
                       :analysis-comparisons comparisons :regions regions
                       :derived-region-analysis derived-regions
                       :policy-region-summary (policy-region-summary regions)
                       :all-committed-roots-identical? roots-identical?)]
     (when-not roots-identical?
       (throw (ex-info "Scheduler variants disagree in parameter sweep" result)))
     (assoc result :scheduler-parameter-sweep/root
            (rooted :scheduler-parameter-sweep.v1 result)))))

(defn sweep-summary [result]
  {:scheduler-parameter-sweep/root (:scheduler-parameter-sweep/root result)
   :sweep/subject-root (:sweep/subject-root result)
   :research-definition/root (:research-definition/root result)
   :sweep/axes (:sweep/axes result)
   :all-committed-roots-identical? (:all-committed-roots-identical? result)
   :policy-region-summary (:policy-region-summary result)
   :derived-region-analysis/root
   (get-in result [:derived-region-analysis :scheduler-sweep-derived-regions/root])
   :margin/thresholds (get-in result [:derived-region-analysis :margin/thresholds])
   :policy-regions (get-in result [:derived-region-analysis :policy-regions])
   :decision-boundaries (get-in result [:derived-region-analysis :decision-boundaries])
   :human-readable-region-summaries
   (get-in result [:derived-region-analysis :human-readable-region-summaries])
   :regions (mapv #(select-keys % [:case/parameters :global-region/winners
                                    :global-region/minimum-makespan-ms])
                  (:regions result))
   :analysis-comparison-roots
   (into (sorted-map) (map (fn [[pair comparison]]
                             [pair (:research-analysis-comparison/root comparison)])
                           (:analysis-comparisons result)))})

(defn summary [result]
  {:scheduler-experiment/root (:scheduler-experiment/root result)
   :research-definition/root (:research-definition/root result)
   :all-committed-roots-identical? (:all-committed-roots-identical? result)
   :cases (into (sorted-map)
                (for [[policy runs] (:runs result)]
                  [policy (into (sorted-map)
                                (for [[case-id run] runs]
                                  [case-id {:workload/root (:workload/root run)
                                            :committed/root (:committed/root run)
                                            :metrics (:metrics run)}]))]))})

(defn -main [& args]
  (when-not (and (every? #{"--edn" "--sweep"} args)
                 (= (count args) (count (distinct args))))
    (throw (ex-info "Usage: clojure -M -m resolver-sim.research.scheduler-harness [--edn] [--sweep]"
                    {:args args})))
  (let [sweep? (some #{"--sweep"} args)
        full? (some #{"--edn"} args)
        result (if sweep? (parameter-sweep) (experiment default-regimes))]
    (prn (if full? result (if sweep? (sweep-summary result) (summary result))))))
