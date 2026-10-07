(ns resolver-sim.research.native-accounting-work-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.research.native-accounting-work :as native]
            [resolver-sim.research.accounting-implementation-comparison :as impl-comparison]
            [resolver-sim.research.native-accounting-batch :as batch]
            [resolver-sim.research.accelerated-accounting :as accounting]
            [resolver-sim.research.execution-assurance :as assurance]
            [resolver-sim.research.execution-environment :as environment]
            [resolver-sim.research.dependency-assurance :as dependency]
            [resolver-sim.research.runner-participation :as participation]
            [resolver-sim.research.remote-execution :as remote]
            [resolver-sim.research.remote-execution-test :as fixture]
            [resolver-sim.research.runner-transport :as transport]
            [resolver-sim.research.runner-forensics :as runner-forensics]
            [resolver-sim.research.execution-control :as control]
            [resolver-sim.evidence.forensic-graph :as forensic]))

(defn root [n] (str "sha256:" (format "%064x" n)))
(def secret "native-accounting-test-secret")
(def coordinator-root (root 900))
(defn source [id account category value]
  {:source/root (root id) :accounting/subject-root (root account)
   :accounting/category category :accounting/value value :claim-to/root (root (+ id 1000))})
(defn basis
  ([] (basis nil nil nil))
  ([account-count sources-per-account cutpoint-id]
  (let [contract (accounting/build-reduction-contract)
        sources (if account-count
                  (for [account-index (range account-count)
                        row (range sources-per-account)
                        :let [account-id (+ 100 account-index)
                              source-id (+ 10000 (* account-index sources-per-account) row)]]
                    (source source-id account-id :accounting/bond
                            (- (mod (* 37 (inc source-id)) 101) 50)))
                  [(source 11 100 :accounting/bond 30)
                   (source 12 100 :accounting/bond -5)
                   (source 13 200 :accounting/bond 9)])
        request (accounting/build-request
                 {:accounting-domain :accounting-domain/native-reference
                  :cutpoint-root (root (or cutpoint-id 1))
                  :accounting-definition-root (root 2) :accounting-policy-root (root 3)
                 :reduction-contract contract :sources sources})]
    {:contract contract :request request :accounts (native/account-requests request contract)})))

(defn work-fixture
  ([] (work-fixture 100 301))
  ([account runner-id] (work-fixture account runner-id {}))
  ([account runner-id {:keys [runtime privacy partitions partition-count partition-index
                              account-count sources-per-account cutpoint-id account-basis
                              implementation-kind implementation-kinds required-runs run-index
                              minimum-distinct-implementations]
                      :or {privacy :public partition-index 0
                           implementation-kind :primary implementation-kinds [:primary]
                           required-runs 1 run-index 0 minimum-distinct-implementations 1}}]
   (let [{:keys [accounts contract]} (or account-basis (if account-count
                                       (basis account-count sources-per-account cutpoint-id)
                                       (basis)))
         request (get accounts (root account))
         env (if runtime (environment/build-native-environment runtime) (environment/build-native-environment))
         manifest (dependency/build-manifest
                   (mapv (fn [[id class artifact-root]]
                           {:dependency/id id :dependency/class class :dependency/artifact-root artifact-root
                            :dependency/visibility :public :dependency/acquisition-mode :public-by-root
                            :dependency/provenance-roots [] :dependency/metadata {}})
                         (concat
                          [[:accounting/code :clojure-model (native/implementation-artifact-root)]]
                          (when (some #{:independent} implementation-kinds)
                            [[:accounting/independent-code :clojure-model
                              (native/independent-implementation-artifact-root)]])
                          [[:accounting/runtime :runtime-toolchain (:research-execution-environment/root env)]
                           [:accounting/sources :dataset-fixture (:accounting-request/root request)]])))
         dependency-policy (dependency/build-policy
                            {:required-classes [:clojure-model :runtime-toolchain :dataset-fixture]
                             :allowed-visibility [:public] :allowed-acquisition-modes [:public-by-root]})
         resolved (dependency/resolve-dependencies
                   manifest dependency-policy
                   (mapv (fn [d] {:dependency/id (:dependency/id d) :dependency/artifact-root (:dependency/artifact-root d)
                                  :resolution/acquisition-mode :public-by-root :resolution/status :resolved
                                  :resolution/evidence-root (root 7) :resolution/environment-root nil}) (:dependencies manifest)))
         impls {:primary (native/implementation (:resolved-research-dependency-set/root resolved))
                :independent (native/independent-implementation
                              (:resolved-research-dependency-set/root resolved))}
         impl (get impls implementation-kind)
         source-roots (mapv :source/root (:accounting/sources request))
         generated-partitions
         (when partition-count
           (when-not (and (integer? partition-count) (pos? partition-count)
                          (<= partition-count (count source-roots)))
             (throw (ex-info "Partition count must fit nonempty accounting source subsets"
                             {:partition-count partition-count :source-count (count source-roots)})))
           (mapv #(vec (take-nth partition-count (drop % source-roots)))
                 (range partition-count)))
         plan (accounting/build-partition-plan
               request (or partitions generated-partitions [source-roots])
               {:dependency-set-root (:resolved-research-dependency-set/root resolved)
                :allowed-implementation-roots (mapv #(get-in impls [% :research-implementation-identity/root])
                                                     implementation-kinds)
                :required-capabilities [:capability/accounting] :privacy-requirement privacy
                :execution-environment-root (:research-execution-environment/root env)})
         unit (nth (:partition/work-units plan) partition-index)
         policy (fixture/execution-policy {:objective :fastest :required-runs required-runs
                                            :required-matching-runs 1
                                            :distinct-by [] :repeat-runner-policy :permitted
                                            :qualification-references [] :required-capabilities [:capability/accounting]
                                            :environment-kinds [:clojure-jvm]})
         job (remote/build-job
              {:job-class :job/research-calculation :workload-root (:accounting-work-unit/root unit)
               :distributed-profile-root (root 8) :distributed-policy-root (root 9)
               :execution-environment-root (:research-execution-environment/root env)
               :execution-policy-root (:research-execution-policy/root policy)})
         participant (participation/build-participation
                      {:runner-reference (root runner-id) :disclosure-mode :anonymous :site-id :site/local
                       :opted-in? true :available? true :capabilities [:capability/accounting]
                       :job-classes [:job/research-calculation] :environment-kinds [:clojure-jvm] :qualification-references []})
         prior (when (pos? run-index)
                 [(remote/steal-next job env policy
                                     (participation/build-participation
                                      {:runner-reference (root 301) :disclosure-mode :anonymous :site-id :site/local
                                       :opted-in? true :available? true :capabilities [:capability/accounting]
                                       :job-classes [:job/research-calculation] :environment-kinds [:clojure-jvm]
                                       :qualification-references []}) [])])
         assignment (remote/steal-next job env policy participant (or prior []))
         assurance-policy (assurance/build-assurance-policy
                           {:execution-policy-root (:research-execution-policy/root policy)
                            :dependency-policy-root (:research-dependency-policy/root dependency-policy)
                            :required-runs required-runs :minimum-distinct-runners 1 :minimum-distinct-sites 1
                            :minimum-distinct-implementations minimum-distinct-implementations :additional-validation-roots []
                            :proof-generation-required? false})
         assured (assurance/build-assured-job
                  {:job-root (:research-execution-job/root job)
                   :dependency-manifest-root (:research-dependency-manifest/root manifest)
                   :dependency-policy-root (:research-dependency-policy/root dependency-policy)
                   :resolved-dependency-set-root (:resolved-research-dependency-set/root resolved)
                   :implementation-roots (mapv #(get-in impls [% :research-implementation-identity/root])
                                               implementation-kinds)
                   :assurance-policy-root (:research-execution-assurance-policy/root assurance-policy)})
         work (native/build-work
               {:work/job job :work/assignment assignment :work/runner-participation participant
                :work/execution-environment env :work/dependency-manifest manifest :work/dependency-policy dependency-policy
                :work/resolved-dependencies resolved :work/implementation impl :work/assured-job assured
                :work/execution-policy policy :work/assurance-policy assurance-policy
                :accounting/request request :accounting/partition-plan plan :accounting/work-unit unit})]
     {:work work :contract contract :job job :participant participant :assignment assignment :request request :plan plan})))

(deftest direct-computation-assures-the-accounting-partial
  (let [{:keys [work request plan contract]} (work-fixture)
        result (native/execute-work! work)
        partial (:accounting-partial result)
        assured (:run-assurance-evidence result)
        nodes (native/evidence-nodes work result)
        graph (forensic/validate-forensic-evidence-graph
               {:nodes nodes :artifact-roots (vec (distinct (mapcat #(get-in % [:extensions :forensic/causal-parent-roots]) nodes)))})]
    (is (= {:accounting/bond 25} (:accounting/values partial)))
    (is (= (root 100) (get-in result [:run-evidence :accounting/account-root])))
    (is (= (accounting/partial-semantic-root partial) (:semantic-result/root assured)))
    (is (= (native/execution-root result) (:research-run-evidence/root assured)))
    (is (= :observed-native-accounting (get-in result [:run-evidence :execution/assurance])))
    (is (= result (native/execute-work! work)))
    (is (:valid? graph))
    (is (accounting/reconcile request contract plan
                              (accounting/ingest-partial {} partial)))
    (is (not (contains? work :accounting/expected-values)))))

(deftest account-level-identity-survives-runner-variation
  (let [left (native/execute-work! (:work (work-fixture 100 301)))
        replica (native/execute-work! (:work (work-fixture 100 302)))
        other (native/execute-work! (:work (work-fixture 200 303)))]
    (is (= (accounting/partial-semantic-root (:accounting-partial left))
           (accounting/partial-semantic-root (:accounting-partial replica))))
    (is (not= (native/execution-root left) (native/execution-root replica)))
    (is (not= (get-in left [:run-assurance-evidence :research-run-assurance-evidence/root])
              (get-in replica [:run-assurance-evidence :research-run-assurance-evidence/root])))
    (is (= {:accounting/bond 9} (get-in other [:accounting-partial :accounting/values])))
    (is (not= (accounting/partial-semantic-root (:accounting-partial left))
              (accounting/partial-semantic-root (:accounting-partial other))))))

(deftest different-accounting-implementations-preserve-semantic-result-and-distinct-provenance
  (let [partitions [[(root 11)] [(root 12)]]
        shared-implementations [:primary :independent]
        primary0 (work-fixture 100 301 {:partitions partitions :partition-index 0
                                        :implementation-kinds shared-implementations
                                        :implementation-kind :primary})
        primary1 (work-fixture 100 302 {:partitions partitions :partition-index 1
                                        :implementation-kinds shared-implementations
                                        :implementation-kind :primary})
        alternate1 (work-fixture 100 302 {:partitions partitions :partition-index 1
                                          :implementation-kinds shared-implementations
                                          :implementation-kind :independent
                                          :required-runs 2 :run-index 1
                                          :minimum-distinct-implementations 2})
        primary-run0 (native/execute-work! (:work primary0))
        primary-run1 (native/execute-work! (:work primary1))
        alternate-run1 (native/execute-work! (:work alternate1))
        full-primary (accounting/reconcile (:request primary0) (:contract primary0) (:plan primary0)
                                           (-> {} (accounting/ingest-partial (:accounting-partial primary-run0))
                                               (accounting/ingest-partial (:accounting-partial primary-run1))))
        mixed (accounting/reconcile (:request primary0) (:contract primary0) (:plan primary0)
                                    (-> {} (accounting/ingest-partial (:accounting-partial primary-run0))
                                        (accounting/ingest-partial (:accounting-partial alternate-run1))))]
    (is (not= (:research-implementation-identity/root (:work/implementation (:work primary1)))
              (:research-implementation-identity/root (:work/implementation (:work alternate1)))))
    (is (= (:accounting/values (:accounting-partial primary-run1))
           (:accounting/values (:accounting-partial alternate-run1))))
    (is (= (accounting/partial-semantic-root (:accounting-partial primary-run1))
           (accounting/partial-semantic-root (:accounting-partial alternate-run1))))
    (is (not= (native/execution-root primary-run1) (native/execution-root alternate-run1)))
    (is (= (:canonical-accounting-result/root full-primary)
           (:canonical-accounting-result/root mixed)))
    (is (= (accounting/serial-values (:request primary0)) (:accounting/values mixed)))
    (is (= 2 (get-in alternate1 [:work :work/assurance-policy
                                 :assurance/minimum-distinct-implementations])))))

(deftest independent-accounting-comparison-binds-two-fresh-executions
  (let [shared-implementations [:primary :independent]
        left-fixture (work-fixture 100 301
                                   {:implementation-kind :primary
                                    :implementation-kinds shared-implementations})
        right-fixture (work-fixture 100 302
                                    {:implementation-kind :independent
                                     :implementation-kinds shared-implementations})
        left-work (:work left-fixture)
        right-work (:work right-fixture)
        left-result (native/execute-work! left-work)
        right-result (native/execute-work! right-work)
        comparison (impl-comparison/compare-implementations left-work left-result
                                                            right-work right-result)
        left-aggregate (accounting/reconcile
                        (:request left-fixture) (:contract left-fixture) (:plan left-fixture)
                        (accounting/ingest-partial {} (:accounting-partial left-result)))
        right-aggregate (accounting/reconcile
                         (:request right-fixture) (:contract right-fixture) (:plan right-fixture)
                         (accounting/ingest-partial {} (:accounting-partial right-result)))
        node (impl-comparison/evidence-node
              left-work left-result right-work right-result comparison)]
    (is (= :equal (:comparison/status comparison)))
    (is (= :artifact-level-independent-implementation
           (:comparison/assurance-classification comparison)))
    (is (true? (:comparison/implementation-distinct? comparison)))
    (is (= (:comparison/left-semantic-partial-root comparison)
           (:comparison/right-semantic-partial-root comparison)))
    (is (not= (:comparison/left-execution-root comparison)
              (:comparison/right-execution-root comparison)))
    (is (not= (:comparison/left-assurance-root comparison)
              (:comparison/right-assurance-root comparison)))
    (is (= (:canonical-accounting-result/root left-aggregate)
           (:canonical-accounting-result/root right-aggregate)))
    (is (impl-comparison/valid-comparison?
         left-work left-result right-work right-result comparison))
    (is (= 2 (count (:parent-hashes node))))
    (is (= #{(:node-hash (last (native/evidence-nodes left-work left-result)))
             (:node-hash (last (native/evidence-nodes right-work right-result)))}
           (set (:parent-hashes node))))
    (is (= :accounting/implementation-comparison
           (get-in node [:extensions :forensic/subject :subject/kind])))))

(deftest implementation-comparison-keeps-domain-mismatches-not-comparable
  (let [shared-implementations [:primary :independent]
        left-fixture (work-fixture 100 301
                                   {:implementation-kind :primary
                                    :implementation-kinds shared-implementations})
        right-fixture (work-fixture 200 302
                                    {:implementation-kind :independent
                                     :implementation-kinds shared-implementations})
        left-work (:work left-fixture)
        right-work (:work right-fixture)
        left-result (native/execute-work! left-work)
        right-result (native/execute-work! right-work)
        comparison (impl-comparison/compare-implementations left-work left-result
                                                            right-work right-result)]
    (is (= :not-comparable (:comparison/status comparison)))
    (is (= [:different-accounting-request :different-accounting-partition
            :different-dependency-closure]
           (:comparison/not-comparable-reasons comparison)))
    (is (nil? (:comparison/values comparison)))
    (is (nil? (:comparison/mismatch-witness comparison)))
    (is (impl-comparison/valid-comparison?
         left-work left-result right-work right-result comparison))))

(deftest implementation-comparison-rejects-result-or-provenance-substitution
  (let [shared-implementations [:primary :independent]
        left-fixture (work-fixture 100 301
                                   {:implementation-kind :primary
                                    :implementation-kinds shared-implementations})
        right-fixture (work-fixture 100 302
                                    {:implementation-kind :independent
                                     :implementation-kinds shared-implementations})
        left-work (:work left-fixture)
        right-work (:work right-fixture)
        left-result (native/execute-work! left-work)
        right-result (native/execute-work! right-work)
        comparison (impl-comparison/compare-implementations left-work left-result
                                                            right-work right-result)
        wrong-run (assoc right-result :run-evidence (:run-evidence left-result))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (impl-comparison/compare-implementations left-work left-result right-work wrong-run)))
    (is (false? (impl-comparison/valid-comparison?
                 left-work left-result right-work right-result
                 (assoc comparison :comparison/right-execution-root
                        (:comparison/left-execution-root comparison)))))))

(deftest native-work-and-results-reject-substitution
  (let [{:keys [work]} (work-fixture)
        result (native/execute-work! work)]
    (doseq [bad [(assoc-in result [:accounting-partial :accounting/values :accounting/bond] 999)
                 (assoc-in result [:run-assurance-evidence :semantic-result/root] (root 99))
                 (assoc-in result [:fixed-validation :validation/status] :failed)
                 (assoc-in result [:run-evidence :research-run-assignment/root] (root 99))]]
      (is (thrown? clojure.lang.ExceptionInfo (native/validate-execution work bad))))
    (doseq [bad [(assoc work :accounting/expected-values {:accounting/bond 25})
                 (assoc-in work [:work/implementation :implementation/artifact-root] (root 99))
                 (assoc-in work [:accounting/work-unit :work-unit/privacy-requirement] :sealed-to-execution-environment)
                 (assoc-in work [:work/assignment :runner/reference] (root 99))]]
      (is (false? (native/valid-work? bad))))
    (let [{:keys [request]} (basis)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (native/account-root request {:partition/source-roots (mapv :source/root (:accounting/sources request))}))))
    (is (thrown? clojure.lang.ExceptionInfo (work-fixture 100 301 {:privacy :sealed-to-execution-environment})))
    (let [mismatch (:work (work-fixture 100 301 {:runtime {:clojure-version "0.0.0" :jvm-version "fake"}}))]
      (is (native/valid-work? mismatch))
      (is (thrown? clojure.lang.ExceptionInfo (native/execute-work! mismatch))))))

(deftest account-partitions-reconcile-exactly-once-in-either-order
  (let [partitions [[(root 11)] [(root 12)]]
        a (work-fixture 100 301 {:partitions partitions})
        b (work-fixture 100 302 {:partitions partitions :partition-index 1})
        pa (:accounting-partial (native/execute-work! (:work a)))
        pb (:accounting-partial (native/execute-work! (:work b)))
        forward (reduce accounting/ingest-partial {} [pa pb pa])
        reverse (reduce accounting/ingest-partial {} [pb pa pb])
        reconciled (accounting/reconcile (:request a) (:contract a) (:plan a) forward)]
    (is (= 2 (count forward)))
    (is (= reconciled (accounting/reconcile (:request a) (:contract a) (:plan a) reverse)))
    (is (= (accounting/serial-values (:request a)) (:accounting/values reconciled)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (accounting/reconcile (:request a) (:contract a) (:plan a)
                                       (accounting/ingest-partial {} pa))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (native/validate-execution (:work a) (native/execute-work! (:work b)))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (accounting/reconcile (:request a) (:contract a) (:plan a)
                                       (accounting/ingest-partial forward
                                        (:accounting-partial (native/execute-work! (:work (work-fixture 200 303))))))))))

(deftest multi-runner-batch-reconciles-exact-plan-and-is-order-independent
  (let [partitions [[(root 11)] [(root 12)]]
        left (work-fixture 100 301 {:partitions partitions :partition-index 0})
        right (work-fixture 100 302 {:partitions partitions :partition-index 1})
        works [(:work left) (:work right)]
        coordinator (transport/start-coordinator!
                    {:coordinator-root coordinator-root
                     :assignments (mapv :work/assignment works) :auth-secret secret})
        runners (mapv (fn [fixture]
                        (transport/start-runner!
                         {:runner-participation (:participant fixture)
                          :auth-secret secret :coordinator-root coordinator-root
                          :coordinator-endpoint
                          (transport/coordinator-endpoint coordinator secret 30000)}))
                      [left right])
        dispatches (mapv (fn [work runner cutpoint]
                           {:work work
                            :endpoint (transport/coordinator-endpoint runner secret 30000)
                            :logical-cutpoint cutpoint})
                         works runners [1 2])]
    (try
      (let [result (batch/dispatch-and-reconcile! coordinator (:contract left) dispatches)
            replay (batch/dispatch-and-reconcile! coordinator (:contract left)
                                                  (vec (reverse dispatches)))
            aggregate (get-in result [:accounting/reconciliation
                                       :canonical-accounting-result/root])]
        (is (= :complete (:accounting/batch-status result)))
        (is (= (:accounting/required-work-unit-roots (:accounting/completeness result))
               (vec (sort (map :accounting-work-unit/root
                               (:partition/work-units (:plan left)))))))
        (is (= (accounting/serial-values (:request left))
               (get-in result [:accounting/reconciliation :accounting/values])))
        (is (= aggregate (get-in replay [:accounting/reconciliation
                                         :canonical-accounting-result/root])))
        (is (= (mapv #(native/execution-root
                       (get-in % [:dispatch :native-accounting-execution]))
                     (:accounting/dispatch-outcomes result))
               (mapv #(native/execution-root
                       (get-in % [:dispatch :native-accounting-execution]))
                     (:accounting/dispatch-outcomes replay))))
        (is (= 2 (count (get-in (transport/coordinator-state coordinator)
                                [:accounting-partials
                                 (:accounting-partition-plan/root (:plan left))])))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"every partition exactly once"
                            (batch/dispatch-and-reconcile!
                             coordinator (:contract left) (take 1 dispatches))))
      (finally
        (doseq [runner runners] (transport/stop-runner! runner))
        (transport/stop-coordinator! coordinator)))))

(defn start-runner [participant coordinator & [state-path hook]]
  (transport/start-runner!
   {:runner-participation participant :auth-secret secret :coordinator-root coordinator-root
    :coordinator-endpoint (transport/coordinator-endpoint coordinator secret)
    :state-path state-path :before-result-delivery hook :bind-address "127.0.0.1" :port 0}))

(deftest durable-runner-executes-and-admits-direct-accounting
  (let [{:keys [work assignment job participant request plan contract]} (work-fixture)
        coordinator (transport/start-coordinator! {:coordinator-root coordinator-root :assignments [assignment]
                                                   :auth-secret secret :bind-address "127.0.0.1" :port 0})
        runner (start-runner participant coordinator)]
    (try
      (let [dispatch {:endpoint (transport/coordinator-endpoint runner secret 30000)
                      :job job :assignment assignment :execution-work work :logical-cutpoint 1}]
        (let [returned (transport/dispatch-native-accounting!
                        coordinator {:endpoint (:endpoint dispatch) :accounting-work work :logical-cutpoint 1})]
          (is (= (:native-accounting-execution returned) (native/execute-work! work))))
        (transport/dispatch-job! coordinator dispatch)
        (let [state (transport/coordinator-state coordinator)
              returned (:native-accounting-execution (first (vals (:native-accounting-results state))))
              partials (get-in state [:accounting-partials (:accounting-partition-plan/root plan)])]
          (is (= 1 (count (:native-accounting-results state))))
          (is (= returned (native/execute-work! work)))
          (is (= 1 (count partials)))
          (is (accounting/reconcile request contract plan partials))
          (let [reconstruction (runner-forensics/validate-runner-reconstruction
                                (transport/runner-state runner) state)]
            (is (:valid? reconstruction) (pr-str reconstruction)))
          (is (false? (:valid? (runner-forensics/validate-runner-reconstruction
                               (transport/runner-state runner) (assoc state :native-accounting-results {})))))
          (is (= :result-acknowledged
                 (get-in (transport/runner-state runner) [:work-states (:research-native-accounting-work/root work) :work/status])))))
      (finally (transport/stop-runner! runner) (transport/stop-coordinator! coordinator)))))

(deftest persisted-native-completion-redelivers-without-reexecution
  (let [{:keys [work assignment job participant]} (work-fixture)
        coordinator-file (java.io.File/createTempFile "native-coordinator-" ".edn")
        runner-file (java.io.File/createTempFile "native-runner-" ".edn")
        _remove-empty-files (do (.delete coordinator-file) (.delete runner-file))
        start-coordinator (fn [] (transport/start-coordinator!
                                 {:coordinator-root coordinator-root :assignments [assignment] :auth-secret secret
                                  :bind-address "127.0.0.1" :port 0 :state-path (.getAbsolutePath coordinator-file)}))
        coordinator (start-coordinator)
        runner (start-runner participant coordinator (.getAbsolutePath runner-file)
                             (fn [& _] (transport/stop-coordinator! coordinator)))]
    (try
      (transport/dispatch-job! coordinator {:endpoint (transport/coordinator-endpoint runner secret 30000)
                                            :job job :assignment assignment :execution-work work})
      (let [cached (get-in (transport/runner-state runner) [:completed-work (:research-native-accounting-work/root work)])]
        (is (= :unreachable (get-in cached [:result-delivery :delivery/status])))
        (transport/stop-runner! runner)
        (let [recovered (start-coordinator)
              restarted (with-redefs [native/execute-work! (fn [& _] (throw (ex-info "Must not reexecute" {})))]
                          (start-runner participant recovered (.getAbsolutePath runner-file)))]
          (try
            (let [entries (vals (:native-accounting-results (transport/coordinator-state recovered)))
                  completed (get-in (transport/runner-state restarted) [:completed-work (:research-native-accounting-work/root work)])]
              (is (= 1 (count entries)))
              (is (= (:native-accounting-execution cached) (:native-accounting-execution (first entries))))
              (is (= 2 (count (:result-deliveries completed))))
              (is (= :result-acknowledged
                     (get-in (transport/runner-state restarted) [:work-states (:research-native-accounting-work/root work) :work/status]))))
            (finally (transport/stop-runner! restarted) (transport/stop-coordinator! recovered)))))
      (finally (transport/stop-runner! runner) (transport/stop-coordinator! coordinator)
               (.delete coordinator-file) (.delete runner-file)))))

(deftest native-cancellation-preserves-completed-accounting
  (let [{:keys [work assignment job participant]} (work-fixture)
        coordinator (transport/start-coordinator! {:coordinator-root coordinator-root :assignments [assignment]
                                                   :auth-secret secret :bind-address "127.0.0.1" :port 0})
        cancellation (control/build-cancellation
                      {:job-root (:research-execution-job/root job) :control-epoch 0 :reason :user-requested
                       :requested-cutpoint 2 :scope :entire-job :target-roots []})
        runner-endpoint (atom nil)
        runner (start-runner participant coordinator nil
                             (fn [_runner _ _]
                               (transport/deliver-cancellation!
                                {:coordinator-root coordinator-root :endpoint @runner-endpoint
                                 :cancellation cancellation :runner-participation participant
                                 :assignment assignment :logical-cutpoint 2})))]
    (try
      (reset! runner-endpoint (transport/coordinator-endpoint runner secret 30000))
      (transport/dispatch-native-accounting! coordinator
                                            {:endpoint (transport/coordinator-endpoint runner secret 30000) :accounting-work work})
      (let [completed (get-in (transport/runner-state runner) [:completed-work (:research-native-accounting-work/root work)])]
        (is (= :cancelled-before-delivery (get-in completed [:result-delivery :delivery/status])))
        (is (= (:native-accounting-execution completed)
               (native/validate-execution work (:native-accounting-execution completed))))
        (is (empty? (:native-accounting-results (transport/coordinator-state coordinator))))
        (is (= :cancelled-before-start
               (get-in (transport/dispatch-native-accounting!
                        coordinator {:endpoint (transport/coordinator-endpoint runner secret) :accounting-work work})
                       [:delivery :delivery/status]))))
      (finally (transport/stop-runner! runner) (transport/stop-coordinator! coordinator)))))

(deftest native-cancellation-before-dispatch-never-computes
  (let [{:keys [work assignment job participant]} (work-fixture)
        coordinator (transport/start-coordinator! {:coordinator-root coordinator-root :assignments [assignment]
                                                   :auth-secret secret :bind-address "127.0.0.1" :port 0})
        runner (start-runner participant coordinator)
        cancellation (control/build-cancellation
                      {:job-root (:research-execution-job/root job) :control-epoch 0 :reason :user-requested
                       :requested-cutpoint 0 :scope :entire-job :target-roots []})]
    (try
      (is (= :observed
             (:delivery/status (transport/deliver-cancellation!
                                {:coordinator-root coordinator-root :endpoint (transport/coordinator-endpoint runner secret)
                                 :cancellation cancellation :runner-participation participant :assignment assignment}))))
      (with-redefs [native/execute-work! (fn [& _] (throw (ex-info "Cancelled work must not compute" {})))]
        (is (= :cancelled-before-start
               (get-in (transport/dispatch-native-accounting!
                        coordinator {:endpoint (transport/coordinator-endpoint runner secret) :accounting-work work})
                       [:delivery :delivery/status]))))
      (is (empty? (:completed-work (transport/runner-state runner))))
      (is (empty? (:native-accounting-results (transport/coordinator-state coordinator))))
      (finally (transport/stop-runner! runner) (transport/stop-coordinator! coordinator)))))

(deftest cancellation-suppresses-retry-of-an-undelivered-native-result
  (let [{:keys [work assignment job participant]} (work-fixture)
        coordinator (transport/start-coordinator! {:coordinator-root coordinator-root :assignments [assignment]
                                                   :auth-secret secret :bind-address "127.0.0.1" :port 0})
        runner (start-runner participant coordinator nil (fn [& _] (transport/stop-coordinator! coordinator)))
        cancellation (control/build-cancellation
                      {:job-root (:research-execution-job/root job) :control-epoch 0 :reason :user-requested
                       :requested-cutpoint 2 :scope :entire-job :target-roots []})]
    (try
      (transport/dispatch-native-accounting! coordinator
                                            {:endpoint (transport/coordinator-endpoint runner secret 30000) :accounting-work work})
      (let [saved (get-in (transport/runner-state runner)
                         [:completed-work (:research-native-accounting-work/root work) :native-accounting-execution])]
        (is saved)
        (transport/deliver-cancellation!
         {:coordinator-root coordinator-root :endpoint (transport/coordinator-endpoint runner secret)
          :cancellation cancellation :runner-participation participant :assignment assignment :logical-cutpoint 2})
        (is (empty? (transport/retry-undelivered-reproduction-results!
                     runner (transport/coordinator-endpoint coordinator secret) 3)))
        (is (= saved (get-in (transport/runner-state runner)
                            [:completed-work (:research-native-accounting-work/root work) :native-accounting-execution])))
        (is (= :cancelled-before-delivery
               (get-in (transport/runner-state runner)
                       [:completed-work (:research-native-accounting-work/root work) :result-delivery :delivery/status]))))
      (finally (transport/stop-runner! runner) (transport/stop-coordinator! coordinator)))))
