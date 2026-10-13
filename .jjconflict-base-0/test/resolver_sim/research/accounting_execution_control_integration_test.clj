(ns resolver-sim.research.accounting-execution-control-integration-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.accounting.reductions :as accounting]
            [resolver-sim.research.execution-assurance :as assurance]
            [resolver-sim.research.execution-control :as control]
            [resolver-sim.research.proof-generation :as proof]
            [resolver-sim.research.remote-execution :as remote]
            [resolver-sim.research.remote-execution-test :as fixture]
            [resolver-sim.research.semantic-accounting-test :as accounting-fixture]))

(defn fake-root [n] (str "sha256:" (format "%064x" n)))

(defn accounting-job [unit]
  (let [env (fixture/anvil-environment)
        execution-policy (fixture/execution-policy)
        profile (fixture/local-profile)]
    {:environment env :execution-policy execution-policy
     :job (remote/build-job
           {:job-class :job/research-calculation
            :workload-root (:accounting-work-unit/root unit)
            :distributed-profile-root (:distributed-experiment-profile/root profile)
            :distributed-policy-root
            (:distributed-scheduler-policy/root fixture/distributed-policy-artifact)
            :execution-environment-root (:research-execution-environment/root env)
            :execution-policy-root (:research-execution-policy/root execution-policy)})}))

(defn policy [execution-policy]
  (control/build-sufficiency-policy
   {:execution-policy-root (:research-execution-policy/root execution-policy)
    :assurance-policy-root (fake-root 900)
    :assured-job-root (fake-root 901)
    :required-valid-runs 2 :required-matching-runs 2
    :minimum-distinct-runners 2 :minimum-distinct-sites 1
    :minimum-distinct-implementations 2
    :proof-required? false :verified-proof-required? false
    :verifier-registry-root nil :cancel-surplus? true}))

(defn assured-run [semantic-root runner site implementation evidence-n]
  (let [body {:artifact/schema assurance/run-assurance-schema
              :research-execution-job-assurance/root (fake-root 901)
              :research-run-evidence/root (fake-root evidence-n)
              :resolved-research-dependency-set/root (fake-root 902)
              :research-implementation-identity/root implementation
              :research-fixed-validation-receipt/root (fake-root (+ 1000 evidence-n))
              :runner/reference runner :runner/site-id site
              :semantic-result/root semantic-root
              :semantic-result/status :execution-result-only
              :application-authority/status :not-established}]
    (assoc body :research-run-assurance-evidence/root
           (assurance/run-assurance-root body))))

(defn bound-execution [request unit implementation runner site evidence-n]
  (let [provisional (accounting-fixture/accounting-partial
                     request unit implementation runner (fake-root (+ 1200 evidence-n)))
        semantic-root (accounting/partial-semantic-root provisional)
        run (assured-run semantic-root runner site implementation evidence-n)
        partial (accounting-fixture/accounting-partial
                 request unit implementation runner
                 (:research-run-assurance-evidence/root run))]
    {:partial partial :run-assurance run :comparison/status :equal}))

(defn complete-p1-executions []
  (let [{:keys [request plan]} (accounting-fixture/accounting-fixture)
        [p1] (:partition/work-units plan)
        a (bound-execution request p1 (:implementation-a accounting-fixture/roots)
                           (fake-root 11) :site/a 31)
        b (bound-execution request p1 (:implementation-b accounting-fixture/roots)
                           (fake-root 12) :site/b 32)]
    {:request request :plan plan :p1 p1 :p1-a (:partial a) :p1-b (:partial b)
     :execution-a a :execution-b b}))

(deftest three-agreeing-p1-executions-cannot-complete-missing-p2
  (let [{:keys [request plan p1 p1-a execution-a execution-b]} (complete-p1-executions)
        p2 (second (:partition/work-units plan))
        {:keys [job execution-policy]} (accounting-job p1)
        p (policy execution-policy)
        p1-evaluation (control/evaluate-accounting-obligation-sufficiency
                       job request plan p1 p
                       [execution-a execution-b
                        (bound-execution request p1 (fake-root 23) (fake-root 13) :site/c 33)] [] [])
        ingestion (accounting/ingest-partial {} p1-a)
        completeness (accounting/accounting-completeness request plan ingestion)
        p1-classification (control/classify-accounting-work-unit
                           request plan ingestion p1 p1-evaluation)
        p2-classification (control/classify-accounting-work-unit
                           request plan ingestion p2 nil)]
    (is (= :result-ready (:sufficiency/status p1-evaluation)))
    (is (= :incomplete (:accounting/completeness completeness)))
    (is (= [(:accounting-work-unit/root p2)]
           (:accounting/missing-work-unit-roots completeness)))
    (is (= :assurance-redundant (:work/classification p1-classification)))
    (is (= :semantic-required (:work/classification p2-classification)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"incomplete"
                          (accounting/reconcile request
                                                (accounting/build-reduction-contract)
                                                plan ingestion)))))

(deftest scoped-p1-sufficiency-does-not-cancel-or-block-p2
  (let [{:keys [request plan p1 p1-a execution-a execution-b]} (complete-p1-executions)
        p2 (second (:partition/work-units plan))
        {:keys [job execution-policy]} (accounting-job p1)
        p (policy execution-policy)
        evaluation (control/evaluate-accounting-obligation-sufficiency
                    job request plan p1 p
                    [execution-a execution-b] [] [])
        cancel (control/cancellation-on-accounting-obligation-sufficiency
                (:research-execution-job/root job) p evaluation 7 50
                [(:accounting-work-unit/root p1)])
        route (control/build-reachability
               {:job-root (:research-execution-job/root job)
                :route-kind :cancellation-delivery
                :subject-root (:research-job-cancellation/root cancel)
                :source-root (fake-root 40) :destination-root (fake-root 41)
                :declared-reachable? true :outcome :realized :attempt-cutpoint 51})
        observed (control/build-control-observation
                  {:job-root (:research-execution-job/root job)
                   :assignment-root nil :runner-participation-root (fake-root 42)
                   :runner-reference (fake-root 41) :highest-observed-epoch 7
                   :cancellation-root (:research-job-cancellation/root cancel)
                   :observation-cutpoint 51 :last-work-unit-started-root nil
                   :last-work-unit-completed-root nil :status :observed
                   :reachability route})
        ingestion (accounting/ingest-partial {} p1-a)
        p1-classification (control/classify-accounting-work-unit request plan ingestion p1 evaluation)
        p2-classification (control/classify-accounting-work-unit request plan ingestion p2 nil)
        p1-gate (control/accounting-work-eligibility
                 (:research-execution-job/root job) cancel observed p1-classification)
        p2-gate (control/accounting-work-eligibility
                 (:research-execution-job/root job) cancel observed p2-classification)]
    (is (= :cancelled (:selection/outcome p1-gate)))
    (is (false? (:eligible? p1-gate)))
    (is (= :eligible (:selection/outcome p2-gate)))
    (is (:eligible? p2-gate))
    (is (= :semantic-required (:work/classification p2-classification)))
    (is (false? (control/cancellation-applies?
                 cancel (:accounting-work-unit/root p2))))
    (is (= (:accounting-work-unit/root p2)
           (:accounting-work-unit/root
            (control/select-accounting-work-unit
             (:research-execution-job/root job) cancel observed
             [p1-classification p2-classification]))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (control/cancellation-on-accounting-obligation-sufficiency
                  (:research-execution-job/root job) p evaluation 8 51
                  [(:accounting-work-unit/root p2)])))))

(defn hundred-partition-fixture []
  (let [contract (accounting/build-reduction-contract)
        sources (mapv (fn [n]
                        {:source/root (fake-root n)
                         :accounting/category :accounting/unit
                         :accounting/value 1
                         :accounting/subject-root (fake-root 500)
                         :claim-to/root (fake-root 501)})
                      (range 100 200))
        request (accounting/build-request
                 {:accounting-domain :accounting-domain/large
                  :cutpoint-root (fake-root 502)
                  :accounting-definition-root (fake-root 503)
                  :accounting-policy-root (fake-root 504)
                  :reduction-contract contract :sources sources})
        plan (accounting/build-partition-plan
              request (mapv (comp vector :source/root) sources)
              {:dependency-set-root (fake-root 505)
               :execution-environment-root (fake-root 506)
               :allowed-implementation-roots [(fake-root 507)]
               :required-capabilities [:capability/accounting]
               :privacy-requirement :public})]
    {:contract contract :request request :plan plan}))

(deftest ninety-nine-of-one-hundred-cannot-reconcile-and-zero-is-never-invented
  (let [{:keys [contract request plan]} (hundred-partition-fixture)
        [complete missing] (split-at 99 (:partition/work-units plan))
        ingestion (reduce (fn [acc unit]
                            (accounting/ingest-partial
                             acc
                             (accounting/build-partial-result
                              {:request request :work-unit unit
                               :values {:accounting/unit 1}
                               :run-assurance-root (fake-root (+ 700 (:partition/index unit)))
                               :implementation-root (fake-root 507)
                               :runner-reference (fake-root (+ 900 (:partition/index unit)))
                               :resolved-dependency-set-root (fake-root 505)
                               :execution-environment-root (fake-root 506)})))
                          {} complete)
        status (accounting/accounting-completeness request plan ingestion)]
    (is (= :incomplete (:accounting/completeness status)))
    (is (= [(:accounting-work-unit/root (first missing))]
           (:accounting/missing-work-unit-roots status)))
    (is (nil? (get ingestion (:accounting-work-unit/root (first missing)))))
    (is (not (contains? (accounting/serial-values request) :accounting/cancelled-zero)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (accounting/reconcile request contract plan ingestion)))))

(deftest retries-and-duplicate-attempts-preserve-one-semantic-contribution
  (let [{:keys [request plan p1 p1-a p1-b execution-a execution-b]} (complete-p1-executions)
        {:keys [job execution-policy]} (accounting-job p1)
        p (policy execution-policy)
        ingestion (accounting/ingest-partial {} p1-a)
        one (control/evaluate-accounting-obligation-sufficiency
             job request plan p1 p
             [execution-a] [] [])
        two (control/evaluate-accounting-obligation-sufficiency
             job request plan p1 p
             [execution-a execution-a execution-b] [] [])
        retry-before (control/accounting-retry-eligibility
                      (:research-execution-job/root job) nil nil
                      (control/classify-accounting-work-unit request plan {} p1 nil))
        retry-after (control/accounting-retry-eligibility
                     (:research-execution-job/root job) nil nil
                     (control/classify-accounting-work-unit request plan ingestion p1 two))]
    (is (= 1 (get-in one [:sufficiency/counts :valid-runs])))
    (is (= 2 (get-in two [:sufficiency/counts :valid-runs])))
    (is (= :result-ready (:sufficiency/status two)))
    (is (= :eligible (:selection/outcome retry-before)))
    (is (= :surplus (:selection/outcome retry-after)))
    (is (false? (:eligible? retry-after)))
    (is (= p1-a (get ingestion (:accounting-work-unit/root p1))))
    (is (accounting/semantic-partial-equivalent? p1-a p1-b))))

(deftest worker-process-variation-and-noncomplete-statuses-do-not-create-assurance-progress
  (let [{:keys [request plan p1 execution-a]} (complete-p1-executions)
        {:keys [job execution-policy]} (accounting-job p1)
        p (policy execution-policy)
        same-runner-other-process
        (bound-execution request p1 (:implementation-a accounting-fixture/roots)
                         (fake-root 11) :site/a 34)
        same-runner (control/evaluate-accounting-obligation-sufficiency
                     job request plan p1 p [execution-a same-runner-other-process] [] [])
        incomplete (control/evaluate-accounting-obligation-sufficiency
                    job request plan p1 p
                    [{:partial nil :run-assurance (:run-assurance execution-a)
                      :comparison/status :missing}
                     {:partial nil :run-assurance (:run-assurance same-runner-other-process)
                      :comparison/status :failed}
                     {:partial nil :run-assurance (:run-assurance same-runner-other-process)
                      :comparison/status :cancelled}] [] [])]
    (is (= 1 (get-in same-runner [:sufficiency/counts :distinct-runners])))
    (is (= :not-ready (:sufficiency/status same-runner)))
    (is (= 0 (get-in incomplete [:sufficiency/counts :valid-runs])))
    (is (= :not-ready (:sufficiency/status incomplete)))
    (is (not (contains? (:sufficiency/checks incomplete) :zero-substitution)))))

(deftest same-implementation-or-incomparable-output-cannot-trigger-surplus
  (let [{:keys [request plan p1 execution-a execution-b]} (complete-p1-executions)
        {:keys [job execution-policy]} (accounting-job p1)
        p (policy execution-policy)
        same-implementation (control/evaluate-accounting-obligation-sufficiency
                             job request plan p1 p
                             [execution-a
                              (bound-execution request p1 (:implementation-a accounting-fixture/roots)
                                               (fake-root 12) :site/b 32)] [] [])
        incomparable (control/evaluate-accounting-obligation-sufficiency
                      job request plan p1 p
                      [execution-a
                       {:partial nil
                        :run-assurance (:run-assurance execution-b)
                        :comparison/status :not-comparable}] [] [])]
    (is (= 1 (get-in same-implementation [:sufficiency/counts :distinct-implementations])))
    (is (= :not-ready (:sufficiency/status same-implementation)))
    (is (false? (get-in incomparable [:sufficiency/checks :comparable-results])))
    (is (= :not-ready (:sufficiency/status incomparable)))
    (is (some #{:not-comparable} (:sufficiency/nonmatching-statuses incomparable)))))

(deftest completed-partials-survive-user-cancellation-and-unverified-proof-is-insufficient
  (let [{:keys [request plan p1 p1-a execution-a]} (complete-p1-executions)
        {:keys [job execution-policy]} (accounting-job p1)
        proof-policy (control/build-sufficiency-policy
                      {:execution-policy-root (:research-execution-policy/root execution-policy)
                       :assurance-policy-root (fake-root 900)
                       :assured-job-root (fake-root 901)
                       :required-valid-runs 1 :required-matching-runs 1
                       :minimum-distinct-runners 1 :minimum-distinct-sites 1
                       :minimum-distinct-implementations 1
                       :proof-required? true :verified-proof-required? true
                       :verifier-registry-root (fake-root 950) :cancel-surplus? true})
        semantic-root (accounting/partial-semantic-root p1-a)
        proof-body {:artifact/schema proof/schema
                    :research-execution-job-assurance/root (fake-root 901)
                    :resolved-research-dependency-set/root (fake-root 902)
                    :research-run-assurance-evidence/root
                    (get-in execution-a [:run-assurance :research-run-assurance-evidence/root])
                    :semantic-result/root semantic-root
                    :proof-system/identity-root (fake-root 951)
                    :proof-program/identity-root (fake-root 952)
                    :public-values/input-root (fake-root 953)
                    :proof-artifact/root (fake-root 954)
                    :prover-participation/root (fake-root 955)
                    :research-execution-environment/root (fake-root 956)
                    :verifier-registry/root (fake-root 950)
                    :proof-generation/status :generated-unverified
                    :proof-verification/status :not-verified
                    :primitive-validation/status :not-established
                    :application-authority/status :not-established}
        generated-proof (assoc proof-body :research-proof-generation-receipt/root
                               (proof/receipt-root proof-body))
        evaluation (control/evaluate-accounting-obligation-sufficiency
                    job request plan p1 proof-policy [execution-a]
                    [generated-proof] [])
        ingestion (accounting/ingest-partial {} p1-a)
        cancel (control/build-cancellation
                {:job-root (:research-execution-job/root job) :control-epoch 9
                 :reason :user-requested :requested-cutpoint 55 :scope :entire-job
                 :target-roots []})]
    (is (proof/valid-receipt? generated-proof))
    (is (true? (get-in evaluation [:sufficiency/checks :proof-generated])))
    (is (false? (get-in evaluation [:sufficiency/checks :proof-verified])))
    (is (= :not-ready (:sufficiency/status evaluation)))
    (is (= p1-a (get ingestion (:accounting-work-unit/root p1))))
    (is (= :incomplete (:accounting/completeness
                        (accounting/accounting-completeness request plan ingestion))))
    (is (= :user-requested (:cancellation/reason cancel)))))

(deftest conflicting-observed-output-remains-diagnostic-and-prevents-early-cancellation
  (let [{:keys [request plan p1 execution-a]} (complete-p1-executions)
        {:keys [job execution-policy]} (accounting-job p1)
        p (policy execution-policy)
        conflicting-run (assured-run (fake-root 999) (fake-root 12) :site/b
                                     (fake-root 22) 32)
        evaluation (control/evaluate-accounting-obligation-sufficiency
                    job request plan p1 p
                    [execution-a {:partial nil :run-assurance conflicting-run
                                  :comparison/status :different}] [] [])]
    (is (false? (get-in evaluation [:sufficiency/checks :comparable-results])))
    (is (= :not-ready (:sufficiency/status evaluation)))
    (is (some #{:different} (:sufficiency/nonmatching-statuses evaluation)))
    (is (nil? (control/cancellation-on-accounting-obligation-sufficiency
               (:research-execution-job/root job) p evaluation 10 60
               [(:accounting-work-unit/root p1)])))))
