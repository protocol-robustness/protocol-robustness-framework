(ns resolver-sim.research.distributed-scheduler-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.research-definition :as research]
            [resolver-sim.research.analysis-compiler :as analysis]
            [resolver-sim.research.distributed-failure :as failure]
            [resolver-sim.research.distributed-policy :as policy]
            [resolver-sim.research.distributed-profile :as profile]
            [resolver-sim.research.distributed-scheduler :as distributed]
            [resolver-sim.research.execution-cell-runner :as cell-runner]
            [resolver-sim.research.execution-manifest :as manifest]
            [resolver-sim.research.parallel-executor :as executor]
            [resolver-sim.research.result-store :as result-store]
            [resolver-sim.research.scheduler-harness :as harness]))

(defn link [from to distance available?]
  {:link/from-site from :link/to-site to :link/distance distance
   :link/base-latency-ms 0 :link/bandwidth-bytes-per-ms 12
   :link/distance-cost-ms-per-unit 1 :link/available? available?})

(defn topology
  ([] (topology {}))
  ([{:keys [distance link-available? remote-worker-available? replicas]
     :or {distance 1 link-available? true remote-worker-available? true replicas {}}}]
   (profile/build-profile
    {:sites [{:site/id :site/a :site/available? true}
             {:site/id :site/b :site/available? true}]
     :workers [{:worker/id :cpu-0 :worker/site-id :site/a
                :worker/available? true :worker/resource-kinds [:cpu]}
               {:worker/id :cpu-1 :worker/site-id :site/b
                :worker/available? remote-worker-available?
                :worker/resource-kinds [:cpu]}
               {:worker/id :gpu-0 :worker/site-id :site/a
                :worker/available? true :worker/resource-kinds [:gpu]}
               {:worker/id :tool-0 :worker/site-id :site/a
                :worker/available? true :worker/resource-kinds [:tool]}]
     :links [(link :site/a :site/b distance link-available?)
             (link :site/b :site/a distance link-available?)]
     :dependency-replicas replicas})))

(defn local-topology []
  (profile/build-profile
   {:sites [{:site/id :site/a :site/available? true}]
    :workers (mapv (fn [{:keys [id kind]}]
                     {:worker/id id :worker/site-id :site/a
                      :worker/available? true :worker/resource-kinds [kind]})
                   harness/workers)
    :links [] :dependency-replicas {}}))

(def workload (harness/workload {:id :distributed-fixture :transfer-bytes 12}))
(def local-policy (policy/build-policy {:kind :local-only}))
(def ordinary-policy (policy/build-policy {:kind :distributed-work-stealing}))
(def aware-policy (policy/build-policy {:kind :distributed-distance-aware}))

(deftest profile-validates-explicit-distance-without-claiming-a-metric
  (let [p (topology)]
    (is (:valid? (profile/validate-profile p)))
    (is (= {:nonnegative? true :identity-zero? true
            :symmetric? true :triangle-inequality? false}
           (:distance/properties p)))
    (is (= 2 (:transfer/time-ms (profile/transfer p :site/a :site/b 12))))
    (is (= 0 (:transfer/time-ms (profile/transfer p :site/a :site/a 12)))))
  (is (thrown? clojure.lang.ExceptionInfo
               (profile/build-profile
                {:sites [{:site/id :site/a :site/available? true}
                         {:site/id :site/b :site/available? true}]
                 :workers [{:worker/id :cpu-0 :worker/site-id :site/a
                            :worker/available? true :worker/resource-kinds [:cpu]}]
                 :links [(link :site/a :site/b 1 true)]
                 :dependency-replicas {}}))))

(deftest every-outcome-affecting-topology-substitution-changes-profile-root
  (let [base (topology)
        mutations [(topology {:distance 2})
                   (topology {:link-available? false})
                   (topology {:remote-worker-available? false})
                   (topology {:replicas {1 [:site/a]}})
                   (profile/build-profile
                    {:sites (mapv #(if (= :site/b (:site/id %))
                                    (assoc % :site/available? false) %) (:topology/sites base))
                     :workers (:topology/workers base)
                     :links (:topology/links base)
                     :dependency-replicas {}})
                   (profile/build-profile
                    {:sites (:topology/sites base)
                     :workers (:topology/workers base)
                     :links (mapv #(assoc % :link/base-latency-ms 3) (:topology/links base))
                     :dependency-replicas {}})
                   (profile/build-profile
                    {:sites (:topology/sites base)
                     :workers (:topology/workers base)
                     :links (mapv #(assoc % :link/bandwidth-bytes-per-ms 6) (:topology/links base))
                     :dependency-replicas {}})
                   (profile/build-profile
                    {:sites (:topology/sites base)
                     :workers (mapv #(if (= :cpu-1 (:worker/id %))
                                      (assoc % :worker/site-id :site/a) %) (:topology/workers base))
                     :links (:topology/links base)
                     :dependency-replicas {}})
                   (profile/build-profile
                    {:sites (:topology/sites base)
                     :workers (mapv #(if (= :cpu-1 (:worker/id %))
                                      (assoc % :worker/resource-kinds [:gpu]) %) (:topology/workers base))
                     :links (:topology/links base)
                     :dependency-replicas {}})]]
    (is (every? #(not= (:distributed-experiment-profile/root base)
                       (:distributed-experiment-profile/root %)) mutations))))

(deftest explicit-policy-semantics-have-distinct-roots
  (is (not= (:distributed-scheduler-policy/root ordinary-policy)
            (:distributed-scheduler-policy/root aware-policy)))
  (is (not= (:distributed-scheduler-policy/root ordinary-policy)
             (:distributed-scheduler-policy/root
             (policy/build-policy {:kind :distributed-work-stealing
                                   :retry-limit 1})))))

(deftest local-and-distributed-runs-preserve-canonical-semantics
  (let [local (distributed/run workload (local-topology) local-policy)
        remote (distributed/run workload (topology) ordinary-policy)]
    (is (= :complete (:execution/status local) (:execution/status remote)))
    (is (= (harness/canonical-root workload)
           (:committed/root local) (:committed/root remote)))
    (is (= 1 (get-in local [:metrics :committed-root-agreement])
           (get-in remote [:metrics :committed-root-agreement])))
    (is (< (get-in remote [:metrics :makespan-ms])
           (get-in local [:metrics :makespan-ms])))))

(deftest distance-can-make-ordinary-remote-stealing-harmful
  (let [local (distributed/run workload (topology {:distance 16}) local-policy)
        remote (distributed/run workload (topology {:distance 16}) ordinary-policy)
        aware (distributed/run workload (topology {:distance 16}) aware-policy)]
    (is (> (get-in remote [:metrics :makespan-ms])
           (get-in local [:metrics :makespan-ms])))
    (is (< (get-in aware [:metrics :makespan-ms])
           (get-in remote [:metrics :makespan-ms])))
    (is (= (:committed/root local) (:committed/root remote) (:committed/root aware)))))

(deftest distance-sweep-records-exact-transition-data
  (let [rows (distributed/distance-sweep workload (topology) ordinary-policy [0 1 2 4 8])]
    (is (= [0 1 2 4 8] (mapv :distance rows)))
    (is (= [63 67 71 80 86] (mapv :makespan-ms rows)))
    (is (= 5 (count (set (map :distributed-experiment-profile/root rows)))))
    (is (= #{(harness/canonical-root workload)} (set (map :committed/root rows))))))

(deftest distributed-failure-taxonomy-is-specific-and-rooted
  (testing "dependency path unavailable"
    (let [run (distributed/run workload (topology {:link-available? false}) ordinary-policy)
          receipt (first (:failure-receipts run))]
      (is (= :failed (:execution/status run)))
      (is (= :distribution-dependency-failure (:failure/cause receipt)))
      (is (= :failed-non-result (:execution/outcome receipt)))
      (is (nil? (:committed/root run)))
      (is (= (:distributed-execution-failure-receipt/root receipt)
             (failure/receipt-root receipt)))))
  (testing "selected worker unavailable"
    (is (= :remote-worker-unreachable
           (-> (distributed/run workload
                                (topology {:remote-worker-available? false}) ordinary-policy)
               :failure-receipts first :failure/cause))))
  (testing "declared transfer deadline exceeded"
    (is (= :transfer-timeout
           (-> (distributed/run workload (topology)
                                (policy/build-policy
                                 {:kind :distributed-work-stealing
                                  :transfer-timeout-ms 1}))
               :failure-receipts first :failure/cause))))
  (testing "preflight profile mismatch"
    (is (= :distribution-preflight-mismatch
           (-> (distributed/run workload (topology) ordinary-policy
                                "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")
               :failure-receipts first :failure/cause)))))

(deftest retry-preserves-failure-history-and-commits-canonical-result
  (let [run (distributed/run workload
                             (topology {:remote-worker-available? false})
                             (policy/build-policy {:kind :distributed-work-stealing
                                                   :retry-limit 1}))
        receipt (first (:failure-receipts run))]
    (is (= :complete (:execution/status run)))
    (is (= :remote-worker-unreachable (:failure/cause receipt)))
    (is (= (harness/canonical-root workload) (:committed/root run)))
    (is (pos? (get-in run [:metrics :distributed-failure-count])))
    (is (= (count (:failure-receipts run))
           (get-in run [:metrics :distributed-failure-count])))
    (is (some #(= 1 (:attempt/ordinal %)) (:execution/attempts run)))))

(deftest canonical-artifacts-reconstruct-forensic-lineage
  (let [run (distributed/run workload (topology) aware-policy)
        attempt (last (:execution/attempts run))
        placement-root (:distributed-placement-projection/root attempt)
        placement (some #(when (= placement-root (:distributed-placement-projection/root %)) %)
                        (:placement/projections run))]
    (is (= (:workload/root workload) (:workload/root run) (:workload/root attempt)))
    (is (= (:distributed-experiment-profile/root (topology))
           (:distributed-experiment-profile/root run)
           (:distributed-experiment-profile/root attempt)))
    (is (= (:distributed-scheduler-policy/root aware-policy)
           (:distributed-scheduler-policy/root run)
           (:distributed-scheduler-policy/root placement)))
    (is (= (:worker/id attempt) (:worker/id placement)))
    (is (= (:site/id attempt) (:site/id placement)))
    (is (= (:committed/root run) (:task-result/root attempt)))))

(deftest provenance-substitution-is-visible-even-when-output-bytes-match
  (let [other-workload (harness/workload {:id :same-dag-different-source
                                          :transfer-bytes 12})
        base (distributed/run workload (topology) ordinary-policy)
        workload-substitution (distributed/run other-workload (topology) ordinary-policy)
        topology-substitution (distributed/run workload (topology {:distance 2}) ordinary-policy)
        policy-substitution (distributed/run workload (topology) aware-policy)]
    ;; The task-result function deliberately gives these workloads the same
    ;; semantic output; provenance must still prevent evidence substitution.
    (is (= (:committed/root base) (:committed/root workload-substitution)))
    (is (not= (:workload/root base) (:workload/root workload-substitution)))
    (is (not= (:distributed-scheduler-run/root base)
              (:distributed-scheduler-run/root workload-substitution)))
    (is (not= (:distributed-scheduler-run/root base)
              (:distributed-scheduler-run/root topology-substitution)))
    (is (not= (:distributed-scheduler-run/root base)
              (:distributed-scheduler-run/root policy-substitution)))
    (is (not= (-> base :placement/projections first
                  :distributed-placement-projection/root)
              (-> topology-substitution :placement/projections first
                  :distributed-placement-projection/root)))))

(def distributed-source
  {:artifact/schema research/source-schema
   :research/id :distributed/executor
   :research/title "Distributed executor fixture"
   :research/question "Does rooted distribution preserve the workload result?"
   :research/vary {:fixture/value [1]}
   :research/measures [{:measure/id :fixture/makespan-ms
                        :measure/kind research/requirement-margin-kind
                        :measure/domain {:kind :integer :unit :unit/ms}
                        :measure/observation {:observation/id :fixture/makespan-ms}
                        :measure/requirement {:value 0 :unit :unit/ms}
                        :measure/satisfying-direction :at-least}]})

(defn executor-fixture [distributed-profile distributed-policy]
  (let [frozen (research/freeze-research distributed-source)
        research-case (first (get-in frozen [:research-definition :research/cases]))
        policy-root (:distributed-scheduler-policy/root distributed-policy)
        manifest (manifest/build-manifest
                  frozen {:policy-subject-roots [policy-root]
                          :case-workloads
                          {(:case/id research-case)
                           {:workload/root (:workload/root workload)
                            :expected/root (harness/canonical-root workload)
                            :distributed-experiment-profile/root
                            (:distributed-experiment-profile/root distributed-profile)}}
                          :repeats [{:execution/repeat-id 0
                                     :execution/fixture-id :fixture/distributed}]})]
    {:frozen frozen
     :manifest manifest
     :scope {:workload-by-root {(:workload/root workload) workload}
             :distributed-profile-by-root
             {(:distributed-experiment-profile/root distributed-profile)
              distributed-profile}
             :distributed-policy-by-root {policy-root distributed-policy}}}))

(deftest distributed-executor-separates-results-from-failure-receipts
  (let [{:keys [manifest scope]} (executor-fixture (topology) ordinary-policy)
        report (executor/execute-manifest scope manifest :distributed {})
        entry (first (:execution/entries report))
        store (result-store/new-store)]
    (is (= :complete (:execution/status entry)))
    (is (empty? (:distributed-execution-failure-receipts entry)))
    (is (= :stored (:store/status (result-store/put-result! store (:result entry)))))
    (is (= (harness/canonical-root workload) (get-in entry [:result :committed/root])))
    (is (= 13 (count (get-in entry [:result :execution/attempts]))))
    (is (every? :distributed-placement-projection/root
                (get-in entry [:result :execution/placement-projections]))))
  (let [{:keys [manifest scope]} (executor-fixture (topology {:link-available? false})
                                                   ordinary-policy)
        entry (first (:execution/entries
                      (executor/execute-manifest scope manifest :distributed {})))
        failure-store (failure/new-store)
        receipt (first (:distributed-execution-failure-receipts entry))]
    (is (= :failed (:execution/status entry)))
    (is (nil? (:result entry)))
    (is (= (:research-execution-manifest/root manifest)
           (:research-execution-manifest/root receipt)))
    (is (= (-> manifest :dispatch/cells first :research-execution-cell/root)
           (:research-execution-cell/root receipt)))
    (is (= (:distributed-execution-failure-receipt/root receipt)
           (failure/put-receipt! failure-store receipt)))
    (is (= receipt (failure/receipt-by-root
                    failure-store (:distributed-execution-failure-receipt/root receipt))))))

(deftest executor-retry-retains-failure-evidence-inside-successful-result
  (let [retry-policy (policy/build-policy {:kind :distributed-work-stealing
                                           :retry-limit 1})
        {:keys [manifest scope]} (executor-fixture
                                  (topology {:remote-worker-available? false})
                                  retry-policy)
        entry (-> (executor/execute-manifest scope manifest :distributed {})
                  :execution/entries first)
        result (:result entry)]
    (is (= :complete (:execution/status entry)))
    (is (seq (:execution/failure-receipts result)))
    (is (= (mapv :distributed-execution-failure-receipt/root
                 (:execution/failure-receipts result))
           (:execution/failure-receipt-roots result)))
    (is (= (harness/canonical-root workload) (:committed/root result)))))

(deftest cell-and-manifest-roots-bind-profile-and-policy-provenance
  (let [{a :manifest} (executor-fixture (topology) ordinary-policy)
        {b :manifest} (executor-fixture (topology {:distance 2}) ordinary-policy)
        {c :manifest} (executor-fixture (topology) aware-policy)]
    (is (not= (:research-execution-manifest/root a)
              (:research-execution-manifest/root b)))
    (is (not= (:research-execution-manifest/root a)
              (:research-execution-manifest/root c)))
    (is (not= (-> a :dispatch/cells first :research-execution-cell/root)
              (-> b :dispatch/cells first :research-execution-cell/root)))))

(deftest result-from-one-profile-cannot-satisfy-another-cell
  (let [{manifest-a :manifest scope-a :scope} (executor-fixture (topology) ordinary-policy)
        {manifest-b :manifest} (executor-fixture (topology {:distance 2}) ordinary-policy)
        result-a (-> (executor/execute-manifest scope-a manifest-a :distributed {})
                     :execution/entries first :result)
        cell-a (first (:dispatch/cells manifest-a))
        cell-b (first (:dispatch/cells manifest-b))]
    (is (cell-runner/result-matches-cell? cell-a result-a))
    (is (false? (cell-runner/result-matches-cell? cell-b result-a)))
    (is (false? (:valid? (cell-runner/validate-result
                          (assoc result-a :committed/root
                                 "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")))))))

(deftest executor-runtime-diagnostics-do-not-change-semantic-roots
  (let [{:keys [manifest scope]} (executor-fixture (topology) ordinary-policy)
        first-report (executor/execute-manifest scope manifest :distributed {})
        second-report (executor/execute-manifest
                       (assoc scope :runtime/diagnostic-verbosity :verbose)
                       manifest :distributed {})]
    (is (= (executor/result-set first-report) (executor/result-set second-report)))))

(deftest distributed-result-compiles-into-existing-rooted-analysis
  (let [{:keys [frozen manifest scope]} (executor-fixture (topology) aware-policy)
        entry (-> (executor/execute-manifest scope manifest :distributed {})
                  :execution/entries first)
        store (result-store/new-store)
        policy-root (:distributed-scheduler-policy/root aware-policy)]
    (result-store/put-result! store (:result entry))
    (let [compiled (analysis/compile-policy-analysis
                    frozen manifest store policy-root
                    {:execution/repeat-id 0
                     :execution/fixture-id :fixture/distributed})]
      (is (= :complete (:analysis/status compiled)))
      (is (= #{(get-in entry [:result :metrics :makespan-ms])}
             (set (vals (:analysis/coordinates compiled)))))
      (is (string? (:research-analysis/root compiled))))))
