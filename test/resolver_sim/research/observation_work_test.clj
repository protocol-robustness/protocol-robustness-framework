(ns resolver-sim.research.observation-work-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.benchmark.research-definition :as research]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.research.analysis-compiler :as compiler]
            [resolver-sim.research.execution-cell-runner :as cell-runner]
            [resolver-sim.research.execution-manifest :as execution-manifest]
            [resolver-sim.research.observation-work :as observations]
            [resolver-sim.research.parallel-executor :as executor]
            [resolver-sim.research.parallel-executor-test :as fixture]
            [resolver-sim.research.result-store :as store]
            [resolver-sim.research.runner-participation :as participation]
            [resolver-sim.research.scheduler-harness :as harness]))

(def repeat-fixture {:execution/repeat-id 0 :execution/fixture-id :fixture/parallel})
(defn- root [n] (str "sha256:" (format "%064x" n)))

(defn- participant [n opted-in?]
  (participation/build-participation
   {:runner-reference (root n) :disclosure-mode :pseudonymous
    :site-id (keyword "site" (str n))
    :opted-in? opted-in? :available? true
    :capabilities [:capability/research-observation]
    :job-classes [:job/research-observation]
    :environment-kinds [:execution-environment/local]
    :qualification-references []}))

(defn- populated-store [scope execution-manifest]
  (let [result-store (store/new-store)
        report (executor/execute-manifest scope execution-manifest)]
    (doseq [entry (:execution/entries report)]
      (store/put-result! result-store (:result entry)))
    {:store result-store :report report}))

(defn- execute-all [frozen execution-manifest plan runners result-store report]
  (loop [assignments [] ingestion {} n 0]
    (let [runner (nth runners (mod n (count runners)))
          assignment (observations/steal-next frozen execution-manifest plan
                                              runner assignments)]
      (if assignment
        (recur (conj assignments assignment)
               (observations/ingest
                ingestion
                (observations/observe frozen execution-manifest plan assignment
                                      runner result-store report))
               (inc n))
        {:assignments assignments :ingestion ingestion}))))

(defn- two-measure-fixture []
  (let [{:keys [manifest scope]} (fixture/fixture [:static])
        source (update fixture/source :research/measures
                       conj {:measure/id :fixture/steals
                             :measure/kind research/requirement-margin-kind
                             :measure/domain {:kind :integer :unit :unit/count}
                             :measure/observation {:observation/id :fixture/steals}
                             :measure/requirement {:value 0 :unit :unit/count}
                             :measure/satisfying-direction :at-least})
        frozen (research/freeze-research source)
        next-manifest
        (execution-manifest/build-manifest
         frozen {:policy-subject-roots (:dispatch/policy-subject-roots manifest)
                 :case-workloads
                 (into {} (map (juxt :case/id #(dissoc % :case/id))
                               (:dispatch/case-workloads manifest)))
                 :repeats (:dispatch/repeats manifest)})]
    {:frozen frozen :manifest next-manifest :scope scope}))

(deftest stolen-coordinate-observations-preserve-analysis-identity
  (let [{:keys [frozen manifest scope]} (fixture/fixture [:static])
        {:keys [store report]} (populated-store scope manifest)
        policy-root (harness/policy-subject-root :static false)
        plan (observations/build-plan frozen manifest policy-root repeat-fixture)
        a (participant 1 true) b (participant 2 true)
        ab (execute-all frozen manifest plan [a b] store report)
        ba (execute-all frozen manifest plan [b a] store report)
        serial (compiler/compile-policy-analysis frozen manifest store policy-root
                                                 repeat-fixture report)
        result-ab (observations/reconcile frozen manifest plan (:assignments ab)
                                          [a b] (:ingestion ab) store report)
        result-ba (observations/reconcile frozen manifest plan (:assignments ba)
                                          [a b] (:ingestion ba) store report)
        reverse-completion
        (observations/reconcile
         frozen manifest plan (:assignments ab) [a b]
         (reduce observations/ingest {} (reverse (vals (:ingestion ab))))
         store report)]
    (is (= 2 (count (:observation/work-units plan))))
    (is (= serial (:analysis result-ab) (:analysis result-ba)))
    (is (= result-ab reverse-completion))
    (is (not= (get-in result-ab [:forensic-reconciliation
                                 :research-observation-reconciliation/root])
              (get-in result-ba [:forensic-reconciliation
                                 :research-observation-reconciliation/root])))
    (is (= (:ingestion ab)
           (reduce observations/ingest (:ingestion ab) (vals (:ingestion ab)))))
    (is (nil? (observations/steal-next frozen manifest plan
                                       (participant 3 false) [])))
    (is (nil? (observations/steal-next frozen manifest plan a
                                       (:assignments ab))))))

(deftest missing-conflicting-and-substituted-observations-fail
  (let [{:keys [frozen manifest scope]} (fixture/fixture [:static])
        {:keys [store report]} (populated-store scope manifest)
        policy-root (harness/policy-subject-root :static false)
        plan (observations/build-plan frozen manifest policy-root repeat-fixture)
        a (participant 1 true)
        {:keys [assignments ingestion]} (execute-all frozen manifest plan [a]
                                                     store report)
        [unit-root observation] (first ingestion)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (observations/reconcile frozen manifest plan assignments [a]
                                         (dissoc ingestion unit-root) store report)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (observations/ingest ingestion
                                      (assoc observation :observation/value 999))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (observations/reconcile frozen manifest plan assignments [a]
                                         (assoc ingestion unit-root
                                                (assoc observation :observation/value 999))
                                         store report)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (observations/reconcile frozen manifest plan
                                         (conj assignments (first assignments)) [a]
                                         ingestion store report)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (observations/reconcile frozen manifest plan assignments
                                         [(participant 4 true)] ingestion store report)))))

(deftest missing-failed-and-conflicting-cell-statuses-match-serial-compiler
  (let [{:keys [frozen manifest scope]} (fixture/fixture [:static])
        policy-root (harness/policy-subject-root :static false)
        plan (observations/build-plan frozen manifest policy-root repeat-fixture)
        a (participant 1 true)
        result-store (store/new-store)
        missing-work (execute-all frozen manifest plan [a] result-store nil)
        missing-analysis (:analysis (observations/reconcile
                                     frozen manifest plan (:assignments missing-work)
                                     [a] (:ingestion missing-work) result-store nil))
        failed-report (executor/execute-manifest
                       (update scope :policy-by-subject-root dissoc policy-root)
                       manifest)
        failed-work (execute-all frozen manifest plan [a] result-store failed-report)
        failed-analysis (:analysis (observations/reconcile
                                    frozen manifest plan (:assignments failed-work)
                                    [a] (:ingestion failed-work) result-store failed-report))
        good (:result (first (:execution/entries
                              (executor/execute-manifest scope manifest))))
        conflict (-> good (assoc-in [:metrics :makespan-ms]
                                    (inc (get-in good [:metrics :makespan-ms])))
                     (dissoc :research-execution-cell-result/root))
        conflict (assoc conflict :research-execution-cell-result/root
                        (cell-runner/result-root conflict))]
    (is (= (compiler/compile-policy-analysis frozen manifest result-store
                                             policy-root repeat-fixture)
           missing-analysis))
    (is (= (compiler/compile-policy-analysis frozen manifest result-store
                                             policy-root repeat-fixture failed-report)
           failed-analysis))
    (store/put-result! result-store good)
    (store/put-result! result-store conflict)
    (let [work (execute-all frozen manifest plan [a] result-store nil)
          compiled (:analysis (observations/reconcile
                               frozen manifest plan (:assignments work)
                               [a] (:ingestion work) result-store nil))]
      (is (= (compiler/compile-policy-analysis frozen manifest result-store
                                               policy-root repeat-fixture)
             compiled))
      (is (some #{:not-comparable} (vals (:analysis/coordinates compiled)))))))

(deftest multiple-measures-share-cell-without-duplicate-execution
  (let [{:keys [frozen manifest scope]} (two-measure-fixture)
        {:keys [store report]} (populated-store scope manifest)
        policy-root (harness/policy-subject-root :static false)
        plan (observations/build-plan frozen manifest policy-root repeat-fixture)
        a (participant 1 true) b (participant 2 true)
        work (execute-all frozen manifest plan [a b] store report)
        analysis (:analysis (observations/reconcile
                             frozen manifest plan (:assignments work) [a b]
                             (:ingestion work) store report))]
    (is (= 4 (count (:observation/work-units plan))))
    (is (= 2 (count (set (map :research-execution-cell/root
                              (:observation/work-units plan))))))
    (is (= (compiler/compile-policy-analysis frozen manifest store policy-root
                                             repeat-fixture report)
           analysis))
    (is (thrown? clojure.lang.ExceptionInfo
                 (compiler/assemble-policy-analysis
                  frozen manifest policy-root repeat-fixture
                  (:analysis/cell-statuses analysis)
                  (assoc (:analysis/coordinates analysis)
                         (first (keys (:analysis/coordinates analysis)))
                         :not-comparable))))))

(deftest observation-becomes-stale-when-source-cell-conflicts
  (let [{:keys [frozen manifest scope]} (fixture/fixture [:static])
        {:keys [store report]} (populated-store scope manifest)
        policy-root (harness/policy-subject-root :static false)
        plan (observations/build-plan frozen manifest policy-root repeat-fixture)
        a (participant 1 true)
        work (execute-all frozen manifest plan [a] store report)
        source (store/result-by-cell
                store (:research-execution-cell/root
                       (first (:observation/work-units plan))))
        conflicting (-> source
                        (assoc-in [:metrics :makespan-ms]
                                  (inc (get-in source [:metrics :makespan-ms])))
                        (dissoc :research-execution-cell-result/root))
        conflicting (assoc conflicting :research-execution-cell-result/root
                           (cell-runner/result-root conflicting))]
    (store/put-result! store conflicting)
    (is (thrown? clojure.lang.ExceptionInfo
                 (observations/reconcile frozen manifest plan (:assignments work)
                                         [a] (:ingestion work) store report)))))

(deftest duplicate-claim-and-conflicting-observation-never-replace-admitted-unit
  (let [{:keys [frozen manifest scope]} (fixture/fixture [:static])
        {:keys [store report]} (populated-store scope manifest)
        policy-root (harness/policy-subject-root :static false)
        plan (observations/build-plan frozen manifest policy-root repeat-fixture)
        a (participant 1 true)
        work (execute-all frozen manifest plan [a] store report)
        [unit-root admitted] (first (:ingestion work))
        duplicate (observations/ingest (:ingestion work) admitted)
        conflict (assoc admitted :observation/value 999)
        error (try (observations/ingest duplicate conflict)
                   nil (catch clojure.lang.ExceptionInfo e e))]
    (is (= 1 (count (filter #(= unit-root
                                (:research-observation-work-unit/root %))
                            (vals duplicate)))))
    (is (= admitted (get duplicate unit-root)))
    (is (= unit-root (:research-observation-work-unit/root (ex-data error))))
    (is (= (:analysis (observations/reconcile
                       frozen manifest plan (:assignments work) [a]
                       (:ingestion work) store report))
           (:analysis (observations/reconcile
                       frozen manifest plan (:assignments work) [a]
                       duplicate store report))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (observations/reconcile frozen manifest plan
                                         (conj (:assignments work)
                                               (first (:assignments work)))
                                         [a] duplicate store report)))))

(deftest correct-cell-observation-with-wrong-analysis-parent-is-rejected
  (let [{:keys [frozen manifest scope]} (fixture/fixture [:static :work-stealing])
        {:keys [store report]} (populated-store scope manifest)
        static-root (harness/policy-subject-root :static false)
        other-root (harness/policy-subject-root :work-stealing false)
        plan (observations/build-plan frozen manifest static-root repeat-fixture)
        other-plan (observations/build-plan frozen manifest other-root repeat-fixture)
        a (participant 1 true)
        work (execute-all frozen manifest plan [a] store report)
        [unit-root observed] (first (:ingestion work))
        substituted (assoc observed :research-observation-work-plan/root
                           (:research-observation-work-plan/root other-plan))
        substituted (assoc substituted :research-coordinate-observation/root
                           (hash-ref/sha256-ref
                            (hc/domain-hash
                             :research-coordinate-observation-v1
                             (dissoc substituted :research-coordinate-observation/root))))]
    (is (= (:research-execution-cell/root observed)
           (:research-execution-cell/root substituted)))
    (is (not= (:research-coordinate-observation/root observed)
              (:research-coordinate-observation/root substituted)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (observations/reconcile frozen manifest plan (:assignments work)
                                         [a] (assoc (:ingestion work) unit-root substituted)
                                         store report)))))

(deftest abandoned-unit-cannot-manufacture-complete-analysis
  (let [{:keys [frozen manifest]} (fixture/fixture [:static])
        policy-root (harness/policy-subject-root :static false)
        plan (observations/build-plan frozen manifest policy-root repeat-fixture)
        a (participant 1 true)
        result-store (store/new-store)
        assignment (observations/steal-next frozen manifest plan a [])
        observation (observations/observe frozen manifest plan assignment a
                                          result-store nil)
        partial-ingestion (observations/ingest {} observation)
        serial (compiler/compile-policy-analysis frozen manifest result-store
                                                 policy-root repeat-fixture)]
    (is (= :incomplete (:analysis/status serial)))
    (is (= #{:missing} (set (vals (:analysis/cell-statuses serial)))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (observations/reconcile frozen manifest plan [assignment] [a]
                                         partial-ingestion result-store nil)))))
