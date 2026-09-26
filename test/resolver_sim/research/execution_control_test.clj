(ns resolver-sim.research.execution-control-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.research.execution-assurance :as assurance]
            [resolver-sim.research.execution-control :as control]
            [resolver-sim.research.remote-execution :as remote]
            [resolver-sim.research.remote-execution-test :as fixture]))

(defn fake-root [ch] (str "sha256:" (apply str (repeat 64 ch))))

(defn context []
  (let [environment (fixture/anvil-environment)
        execution-policy (fixture/execution-policy)
        profile (fixture/local-profile)
        job (fixture/job environment execution-policy profile)
        runner (fixture/runner \a :identified :site/a)
        assignment (remote/steal-next job environment execution-policy runner [])]
    {:environment environment :execution-policy execution-policy :job job
     :runner runner :assignment assignment}))

(defn cancellation [job]
  (control/build-cancellation
   {:job-root (:research-execution-job/root job) :control-epoch 2
    :reason :user-requested :requested-cutpoint 10 :scope :entire-job
    :target-roots []}))

(defn reachability [job runner cancellation outcome]
  (control/build-reachability
   {:job-root (:research-execution-job/root job)
    :route-kind :cancellation-delivery
    :subject-root (:research-job-cancellation/root cancellation)
    :source-root (fake-root \1) :destination-root (:runner/reference runner)
    :declared-reachable? true :outcome outcome :attempt-cutpoint 12}))

(defn observation [job runner assignment cancellation reachability status]
  (control/build-control-observation
   {:job-root (:research-execution-job/root job)
    :assignment-root (some-> assignment :research-run-assignment/root)
    :runner-participation-root (:runner-participation/root runner)
    :runner-reference (:runner/reference runner)
    :highest-observed-epoch (:control/epoch cancellation)
    :cancellation-root (:research-job-cancellation/root cancellation)
    :observation-cutpoint 12 :last-work-unit-started-root nil
    :last-work-unit-completed-root nil :status status :reachability reachability}))

(deftest cancellation-publication-is-idempotent-but-not-observation
  (let [{:keys [job runner assignment]} (context)
        event (cancellation job)
        same (cancellation job)
        route (reachability job runner event :realized)
        observed (observation job runner assignment event route :observed)
        stopped (observation job runner assignment event route :stopped-acknowledged)
        once (control/ingest-cancellation {} event)]
    (is (= event same))
    (is (= once (control/ingest-cancellation once same)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (control/ingest-cancellation
                  once (control/build-cancellation
                        {:job-root (:research-execution-job/root job)
                         :control-epoch 2 :reason :superseded
                         :requested-cutpoint 10 :scope :entire-job
                         :target-roots []}))))
    (is (nil? (:research-runner-control-observation/root event)))
    (is (control/cancellation-observed? event observed))
    (is (= :stopped-acknowledged (:control-observation/status stopped)))
    (is (not= (:research-runner-control-observation/root observed)
              (:research-runner-control-observation/root stopped)))
    (is (not= (:research-job-cancellation/root event)
              (:research-runner-control-observation/root observed)))))

(deftest declared-and-realized-reachability-are-distinct
  (let [{:keys [job runner]} (context)
        event (cancellation job)
        unreachable (reachability job runner event :unreachable)]
    (is (true? (:reachability/declared? unreachable)))
    (is (false? (:reachability/realized? unreachable)))
    (is (= :unreachable (:reachability/outcome unreachable)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (observation job runner nil event unreachable :observed)))))

(deftest observed-cancellation-blocks-assignment-start-steal-retry-and-proof-work
  (let [{:keys [job runner environment execution-policy]} (context)
        event (cancellation job)
        route (reachability job runner event :realized)
        observed (observation job runner nil event route :observed)]
    (is (nil? (control/controlled-steal-next
               job environment execution-policy runner [] event observed)))
    (doseq [action [:assignment :start :work-steal :retry :proof-generation
                    :verification :corroboration]]
      (let [result (control/scheduling-eligibility
                    (:research-execution-job/root job) event observed action)]
        (is (= :cancelled (:selection/outcome result)))
        (is (false? (:execution/failure? result)))))))

(deftest unpublished-or-unobserved-cancellation-does-not-block-placement
  (let [{:keys [job runner environment execution-policy]} (context)]
    (is (some? (control/controlled-steal-next
                job environment execution-policy runner [] nil nil)))))

(def operations
  [{:operation/root (fake-root \1) :start 0 :end 4 :safe-cutpoint? false}
   {:operation/root (fake-root \2) :start 4 :end 8 :safe-cutpoint? true}
   {:operation/root (fake-root \3) :start 8 :end 12 :safe-cutpoint? true}])

(deftest cancellation-modes-stop-only-at-declared-boundaries
  (let [immediate (control/cancellation-stop-decision :immediate operations 6)
        cooperative (control/cancellation-stop-decision :cooperative operations 6)
        atomic (control/cancellation-stop-decision :atomic operations 6)]
    (is (= [(fake-root \1)] (:work/completed-operation-roots immediate)))
    (is (:work/discarded-incomplete? immediate))
    (is (= [(fake-root \1) (fake-root \2)]
           (:work/completed-operation-roots cooperative)))
    (is (= [(fake-root \1) (fake-root \2)]
           (:work/completed-operation-roots atomic)))
    (is (every? :work/no-next-operation-started?
                [immediate cooperative atomic]))
    (is (every? #(= :cancelled (:execution/status %))
                [immediate cooperative atomic]))))

(defn assured-run [runner site implementation result digit]
  (let [body {:artifact/schema assurance/run-assurance-schema
              :research-execution-job-assurance/root (fake-root \1)
              :research-run-evidence/root (fake-root digit)
              :resolved-research-dependency-set/root (fake-root \2)
              :research-implementation-identity/root implementation
              :research-fixed-validation-receipt/root (fake-root digit)
              :runner/reference runner :runner/site-id site
              :semantic-result/root result
              :semantic-result/status :execution-result-only
              :application-authority/status :not-established}]
    (assoc body :research-run-assurance-evidence/root
           (assurance/run-assurance-root body))))

(defn sufficiency-policy [execution-policy]
  (control/build-sufficiency-policy
   {:execution-policy-root (:research-execution-policy/root execution-policy)
    :assurance-policy-root (fake-root \a)
    :assured-job-root (fake-root \1)
    :required-valid-runs 2 :required-matching-runs 2
    :minimum-distinct-runners 2 :minimum-distinct-sites 1
    :minimum-distinct-implementations 2
    :proof-required? false :verified-proof-required? false
    :verifier-registry-root nil :cancel-surplus? true}))

(deftest sufficiency-counts-unique-runs-and-independent-dimensions
  (let [{:keys [job execution-policy]} (context)
        policy (sufficiency-policy execution-policy)
        result (fake-root \9)
        run-a (assured-run (fake-root \3) :site/a (fake-root \5) result \6)
        run-b-same-implementation
        (assured-run (fake-root \4) :site/b (fake-root \5) result \7)
        not-enough (control/evaluate-sufficiency
                    (:research-execution-job/root job) policy
                    [run-a run-a run-b-same-implementation] [] [] 20)
        run-b (assured-run (fake-root \4) :site/b (fake-root \8) result \7)
        ready (control/evaluate-sufficiency
               (:research-execution-job/root job) policy [run-a run-a run-b] [] [] 21)]
    (is (= 2 (get-in not-enough [:sufficiency/counts :valid-runs])))
    (is (= 2 (get-in not-enough [:sufficiency/counts :distinct-runners])))
    (is (= 1 (get-in not-enough [:sufficiency/counts :distinct-implementations])))
    (is (= :not-ready (:sufficiency/status not-enough)))
    (is (= :result-ready (:sufficiency/status ready)))
    (is (= :not-established (:primitive-validation/status ready)))
    (is (= :not-evaluated (:claim-acceptance/status ready)))))

(deftest sufficiency-can-cancel-only-surplus-outstanding-work
  (let [{:keys [job execution-policy]} (context)
        policy (sufficiency-policy execution-policy)
        result (fake-root \9)
        runs [(assured-run (fake-root \3) :site/a (fake-root \5) result \6)
              (assured-run (fake-root \4) :site/b (fake-root \8) result \7)]
        evaluation (control/evaluate-sufficiency
                    (:research-execution-job/root job) policy runs [] [] 21)
        surplus [(fake-root \a)]
        event (control/cancellation-on-sufficiency
               (:research-execution-job/root job) policy evaluation 3 21 surplus)]
    (is (= :execution-sufficient (:cancellation/reason event)))
    (is (= :surplus-run-slots (:cancellation/scope event)))
    (is (= surplus (:cancellation/target-roots event)))
    (is (control/cancellation-applies? event (first surplus)))
    (is (false? (control/cancellation-applies? event (fake-root \b))))))

(deftest proof-dimension-cannot-be-waived-by-run-count
  (let [{:keys [job execution-policy]} (context)
        policy (control/build-sufficiency-policy
                {:execution-policy-root (:research-execution-policy/root execution-policy)
                 :assurance-policy-root (fake-root \a)
                 :assured-job-root (fake-root \1)
                 :required-valid-runs 1 :required-matching-runs 1
                 :minimum-distinct-runners 1 :minimum-distinct-sites 1
                 :minimum-distinct-implementations 1
                 :proof-required? true :verified-proof-required? true
                 :verifier-registry-root (fake-root \b) :cancel-surplus? true})
        run (assured-run (fake-root \3) :site/a (fake-root \5) (fake-root \9) \6)
        evaluation (control/evaluate-sufficiency
                    (:research-execution-job/root job) policy [run run] [] [] 20)]
    (is (= :not-ready (:sufficiency/status evaluation)))
    (is (false? (get-in evaluation [:sufficiency/checks :proof-generated])))
    (is (false? (get-in evaluation [:sufficiency/checks :proof-verified])))))

(deftest lifecycle-provenance-distinguishes-post-observation-work
  (let [{:keys [job runner assignment]} (context)
        event (cancellation job)
        before (control/build-lifecycle-event
                {:job-root (:research-execution-job/root job)
                 :assignment-root (:research-run-assignment/root assignment)
                 :work-unit-root (fake-root \5) :runner-reference (:runner/reference runner)
                 :kind :completed :cutpoint 9 :highest-observed-control-epoch 0
                 :observed-cancellation-root nil})
        after (control/build-lifecycle-event
               {:job-root (:research-execution-job/root job)
                :assignment-root (:research-run-assignment/root assignment)
                :work-unit-root (fake-root \6) :runner-reference (:runner/reference runner)
                :kind :started :cutpoint 13 :highest-observed-control-epoch 2
                :observed-cancellation-root (:research-job-cancellation/root event)})]
    (is (= :completed (:work/event-kind before)))
    (is (nil? (:research-job-cancellation/root before)))
    (is (= (:research-job-cancellation/root event)
           (:research-job-cancellation/root after)))
    (is (> (:work/event-cutpoint after) (:cancellation/requested-cutpoint event)))))

(deftest control-analysis-is-deterministic-and-preserves-terminal-categories
  (let [{:keys [job runner assignment execution-policy]} (context)
        policy (sufficiency-policy execution-policy)
        result (fake-root \9)
        runs [(assured-run (:runner/reference runner) :site/a
                           (fake-root \5) result \6)
              (assured-run (fake-root \4) :site/b (fake-root \8) result \7)]
        sufficiency (control/evaluate-sufficiency
                     (:research-execution-job/root job) policy runs [] [] 21)
        completed (control/build-lifecycle-event
                   {:job-root (:research-execution-job/root job)
                    :assignment-root (:research-run-assignment/root assignment)
                    :work-unit-root (fake-root \5)
                    :runner-reference (:runner/reference runner)
                    :kind :completed :cutpoint 9
                    :highest-observed-control-epoch 0
                    :observed-cancellation-root nil
                    :consequence-root
                    (:research-run-assurance-evidence/root (first runs))})
        input {:job-root (:research-execution-job/root job)
               :eligible-runner-roots [(:runner/reference runner)]
               :assignments [assignment] :reachability-events []
               :lifecycle-events [completed] :cancellation nil
               :control-observations [] :sufficiency-evaluation sufficiency
               :proof-receipts [] :proof-verifications []
               :full-corroboration-cutpoint nil}
        a (control/build-control-analysis input)
        b (control/build-control-analysis input)
        failed (control/build-control-analysis
                (assoc input :sufficiency-evaluation nil :terminal-status :failed))]
    (is (= a b))
    (is (= :deterministic-model-cutpoints (:analysis/time-semantics a)))
    (is (= 9 (get-in a [:analysis/model-time :time-to-first-valid-result])))
    (is (= :none (get-in a [:analysis/first-stage-observations
                            :first-complete-runner :semantic-authority])))
    (is (= :failed (:analysis/status failed)))
    (is (not= :cancelled (:analysis/status failed)))
    (is (= :missing (get-in a [:analysis/model-time :time-to-first-valid-proof])))))
