(ns resolver-sim.benchmark.research-analysis-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.benchmark.incentive-deviation-domain :as domain]
            [resolver-sim.benchmark.incentive-model :as model]
            [resolver-sim.benchmark.outcome-manifest :as outcome]
            [resolver-sim.benchmark.research-analysis :as analysis]
            [resolver-sim.benchmark.research-analysis-closure :as closure]
            [resolver-sim.benchmark.research-assignment :as assignment]
            [resolver-sim.benchmark.research-command :as command]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.research.claim-evidence-work :as claim-work]
            [resolver-sim.research.runner-participation :as participation]
            [resolver-sim.research.remote-execution :as remote]
            [resolver-sim.research.remote-execution-test :as remote-fixture]
            [resolver-sim.research.runner-transport :as transport]))

(defn- root [label]
  (hash-ref/sha256-ref (hc/domain-hash :evidence-record {:label label})))

(defn- fixture []
  (let [subject (root :subject)
        incentive-model (model/build-model
                         {:incentive-model/id :model/sew-incentives
                          :incentive-model/subject-root subject
                          :incentive-model/participant-roles [:actor/challenger]
                          :incentive-model/payoff-interpretation :net-payoff
                          :incentive-model/rewards {:challenge-bounty :bounded}
                          :incentive-model/penalties {}
                          :incentive-model/costs {}
                          :incentive-model/evaluator-semantics-root (root :evaluator)
                          :incentive-model/policy-roots [(root :policy)]})
        deviation-domain (domain/build-domain
                          {:deviation-domain/id :domain/observed-challenge
                           :deviation-domain/subject-root subject
                           :deviation-domain/incentive-model-root (:incentive-model/root incentive-model)
                           :deviation-domain/baseline-strategy :strategy/honest
                           :deviation-domain/participants [:actor/challenger]
                           :deviation-domain/deviations [:strategy/frivolous-challenge]
                           :deviation-domain/coalition-scope :none
                           :deviation-domain/constraints {:trace-count 1}
                           :deviation-domain/evaluation-method :observed-single-trace})
        research-command (command/build-command
                          {:schema-version command/schema-version-v2
                           :command/id :command/research-ic
                           :command/type :benchmark-evaluation
                           :command/argv ["prf" "benchmark" "run"]
                           :command/includes [{:kind :research-scope/analysis :ref :research-analysis/incentive-compatibility}]
                           :command/environment-root (root :environment)
                           :command/runner-root (root :runner)
                           :command/input-root subject
                           :command/output-root (root :output)})
        research-assignment (assignment/build-assignment
                             {:research-assignment/id :assignment/research-ic
                              :research-assignment/environment-hash (root :environment)
                              :research-assignment/policy-hash (root :assignment-policy)
                              :research-assignment/review-round-hash (root :round)
                              :research-assignment/request-root (root :request)
                              :research-assignment/target {:target/kind :governance-mandated
                                                           :target/public-force-authorisation-scope-hash (root :scope)
                                                           :target/workflow-id 0
                                                           :target/reason :research}
                              :research-assignment/command-root (:command/hash research-command)
                              :research-assignment/plan-root (root :plan)})
        outcome-manifest (outcome/build-manifest
                          {:benchmark/content-root subject
                           :benchmark/model-root (:incentive-model/root incentive-model)
                           :execution/status :completed
                           :execution/command-root (:command/hash research-command)
                           :outcomes/operational-root (root :operational)
                           :outcomes/incentive-compatibility-root (root :ic)})]
    {:research-command research-command
     :incentive-model incentive-model
     :deviation-domain deviation-domain
     :research-assignment research-assignment
     :outcome-manifest outcome-manifest}))

(defn- output [context classification]
  (let [descriptor (analysis/build-result-descriptor
                    {:measure/id :metric/net-payoff-delta
                     :measure/value-domain :integer
                     :measure/unit :unit/abstract
                     :measure/direction :lower-is-better
                     :measure/comparison-semantics :exact-integer})
        closure-report (closure/verify-closure context)]
    (analysis/build-output
     (assoc context
            :analysis-output/closure-root (:research-analysis/root closure-report)
            :analysis-output/result-descriptor descriptor
            :analysis-engine/id :engine/observed-trace
            :analysis-engine/version "1"
            :analysis-output/proposition-id :claim/incentive-compatibility
            :analysis-output/metric-id :metric/net-payoff-delta
            :analysis-output/result {:result/classification classification
                                     :result/value -1
                                     :result/case-root (root :case)
                                     :result/witness-root (when (= :counterexample-found classification)
                                                            (root :witness))
                                     :schema-version analysis/result-schema-version}
            :analysis-output/evidence-root (root classification)))))

(defn- claim-descriptor []
  (analysis/build-result-descriptor
   {:measure/id :metric/net-payoff-delta
    :measure/value-domain :integer
    :measure/unit :unit/abstract
    :measure/direction :lower-is-better
    :measure/comparison-semantics :exact-integer}))

(defn- claim-runner [n]
  (participation/build-participation
   {:runner-reference (root (keyword (str "claim-runner-" n)))
    :disclosure-mode :pseudonymous
    :site-id (keyword "claim-site" (str n))
    :opted-in? true :available? true
    :capabilities [:capability/claim-evidence]
    :job-classes [:job/research-claim-evidence]
    :environment-kinds [:execution-environment/local]
    :qualification-references []}))

(defn- collect-claim-evidence [context descriptor result plan runners]
  (loop [assignments [] ingestion {} n 0]
    (let [runner (nth runners (mod n (count runners)))
          assignment (claim-work/steal-next plan runner assignments)]
      (if assignment
        (recur (conj assignments assignment)
               (claim-work/ingest
                ingestion (claim-work/observe
                           context :claim/incentive-compatibility descriptor result
                           plan assignment runner))
               (inc n))
        {:assignments assignments :ingestion ingestion}))))

(defn- claim-from-evidence [context descriptor result plan work participants]
  (let [analysis-output
        (:analysis-output
         (claim-work/build-output-from-evidence
          context :claim/incentive-compatibility descriptor result plan
          (:assignments work) participants (:ingestion work)
          :engine/observed-trace "1"))
        verification (analysis/verify-output
                      (assoc context :analysis-output analysis-output))]
    {:output analysis-output :verification verification
     :claim (analysis/derive-claim analysis-output verification
                                   (:incentive-model context))}))

(deftest generic-verification-preserves-theorem-boundary
  (let [context (fixture)
        analysis-output (output context :supported)
        verification (analysis/verify-output (assoc context :analysis-output analysis-output))
        claim (analysis/derive-claim analysis-output verification (:incentive-model context))]
    (is (:valid? (analysis/validate-output analysis-output)))
    (is (analysis/verification-valid? verification))
    (is (= :not-established (:research-analysis-verification/claim-status verification)))
    (is (= :not-established (:research-claim/status claim)))
    (is (= (:incentive-model/subject-root (:incentive-model context))
           (:research-claim/subject-root claim)))
    (is (= (:incentive-model/root (:incentive-model context))
           (:research-claim/model-root claim)))
    (is (not= (:research-claim/subject-root claim) (:research-claim/model-root claim)))
    (is (:valid? (analysis/validate-claim claim)))))

(deftest claim-root-binds-both-subject-and-model-roots
  (let [context (fixture)
        analysis-output (output context :supported)
        verification (analysis/verify-output (assoc context :analysis-output analysis-output))
        claim (analysis/derive-claim analysis-output verification (:incentive-model context))
        changed-subject (assoc claim :research-claim/subject-root (root :other-subject))
        changed-model (assoc claim :research-claim/model-root (root :other-model))]
    (is (not= (:research-claim/root claim) (analysis/claim-root changed-subject)))
    (is (not= (:research-claim/root claim) (analysis/claim-root changed-model)))
    (is (false? (:valid? (analysis/validate-claim changed-subject))))
    (is (false? (:valid? (analysis/validate-claim changed-model))))))

(deftest counterexamples-are-comparable-without-being-upgraded-to-a-proof
  (let [context (fixture)
        analysis-output (output context :counterexample-found)
        verification (analysis/verify-output (assoc context :analysis-output analysis-output))
        claim (analysis/derive-claim analysis-output verification (:incentive-model context))]
    (is (= :counterexample-found (:research-analysis-verification/claim-status verification)))
    (is (= :counterexample-found (:research-claim/status claim)))
    (is (not (:valid? (analysis/validate-claim
                       (assoc claim :research-claim/status :verified-general)))))))

(deftest legacy-claim-schema-is-rejected
  (let [context (fixture)
        analysis-output (output context :supported)
        verification (analysis/verify-output (assoc context :analysis-output analysis-output))
        claim (analysis/derive-claim analysis-output verification (:incentive-model context))]
    (is (false? (:valid? (analysis/validate-claim
                          (assoc claim :schema-version "research-analytical-claim.v1")))))))

(deftest verifier-rejects-substituted-analysis-output
  (let [context (fixture)
        analysis-output (output context :supported)
        verification (analysis/verify-output
                      (assoc context :analysis-output
                             (assoc analysis-output :analysis-output/domain-root (root :other-domain))))]
    (is (= :invalid (:research-analysis-verification/status verification)))
    (is (some #{:output-root-mismatch :output-domain-mismatch}
              (:errors verification)))))

(deftest claim-rejects-verification-for-another-output
  (let [context (fixture)
        output-a (output context :supported)
        output-b (output context :counterexample-found)
        verification-b (analysis/verify-output (assoc context :analysis-output output-b))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (analysis/derive-claim output-a verification-b (:incentive-model context))))))

(deftest claim-rejects-self-rooted-verification-substitution
  (let [context (fixture)
        analysis-output (output context :supported)
        verification (analysis/verify-output (assoc context :analysis-output analysis-output))]
    (doseq [field [:research-analysis-verification/command-root
                   :research-analysis-verification/model-root
                   :research-analysis-verification/domain-root
                   :research-analysis-verification/assignment-root
                   :research-analysis-verification/outcome-root]]
      (let [forged (-> verification
                       (assoc field (root :substituted))
                       (assoc :research-analysis-verification/root
                              (analysis/verification-root
                               (assoc verification field (root :substituted)))))]
        (is (thrown? clojure.lang.ExceptionInfo
                     (analysis/derive-claim analysis-output forged
                                            (:incentive-model context))))))
    (let [forged (-> verification
                     (assoc :research-analysis-verification/claim-status :verified-general)
                     (assoc :research-analysis-verification/root
                            (analysis/verification-root
                             (assoc verification
                                    :research-analysis-verification/claim-status
                                    :verified-general))))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (analysis/derive-claim analysis-output forged
                                          (:incentive-model context)))))))

(deftest verifier-rejects-relationship-valid-but-invalid-outcome-manifest
  (let [context (fixture)
        analysis-output (output context :supported)
        invalid-context (assoc context
                               :outcome-manifest
                               (assoc (:outcome-manifest context)
                                      :benchmark-outcome/hash (root :forged-outcome)))
        verification (analysis/verify-output
                      (assoc invalid-context :analysis-output analysis-output))]
    (is (= :invalid (:research-analysis-verification/status verification)))
    (is (some #{:invalid-outcome-manifest}
              (:errors verification)))))

(deftest derive-claim-rejects-verification-for-another-output
  (let [context (fixture)
        output-a (output context :supported)
        output-b (assoc (output context :supported)
                        :analysis-output/evidence-root (root :other-evidence))
        output-b (assoc output-b
                        :research-analysis-output/root (analysis/output-root output-b))
        verification (analysis/verify-output (assoc context :analysis-output output-a))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (analysis/derive-claim output-b verification (:incentive-model context))))))

(deftest verifier-rejects-fabricated-outcome-manifest
  (let [context (fixture)
        analysis-output (output context :supported)
        fabricated (assoc (:outcome-manifest context) :benchmark-outcome/hash (root :fabricated))
        verification (analysis/verify-output (assoc context
                                                    :outcome-manifest fabricated
                                                    :analysis-output analysis-output))]
    (is (= :invalid (:research-analysis-verification/status verification)))
    (is (some #{:invalid-outcome-manifest} (:errors verification)))))

(deftest verifier-rejects-substituted-closure
  (let [context (fixture)
        analysis-output (output context :supported)
        verification (analysis/verify-output
                      (assoc context
                             :analysis-output
                             (assoc analysis-output
                                    :analysis-output/closure-root (root :other-closure))))]
    (is (= :invalid (:research-analysis-verification/status verification)))
    (is (some #{:output-root-mismatch :output-closure-mismatch}
              (:errors verification)))))

(deftest closed-claim-evidence-preserves-output-and-claim-across-stealing
  (let [context (fixture)
        descriptor (claim-descriptor)
        result (:analysis-output/result (output context :supported))
        plan (claim-work/build-plan context :claim/incentive-compatibility
                                    descriptor result)
        a (claim-runner 1) b (claim-runner 2)
        serial-work (collect-claim-evidence context descriptor result plan [a])
        stolen-work (collect-claim-evidence context descriptor result plan [b a])
        serial (claim-work/reconcile
                context :claim/incentive-compatibility descriptor result plan
                (:assignments serial-work) [a] (:ingestion serial-work))
        reverse-completion (claim-work/reconcile
                            context :claim/incentive-compatibility descriptor result
                            plan (:assignments serial-work) [a]
                            (reduce claim-work/ingest {}
                                    (reverse (vals (:ingestion serial-work)))))
        stolen (claim-work/reconcile
                context :claim/incentive-compatibility descriptor result plan
                (:assignments stolen-work) [a b] (:ingestion stolen-work))
        serial-claim (claim-from-evidence context descriptor result plan
                                          serial-work [a])
        stolen-claim (claim-from-evidence context descriptor result plan
                                          stolen-work [a b])]
    (is (= 3 (count (claim-work/coordinates plan))))
    (is (= serial reverse-completion))
    (is (= (:semantic-evidence serial) (:semantic-evidence stolen)))
    (is (not= (:forensic-reconciliation serial)
              (:forensic-reconciliation stolen)))
    (is (= (:output serial-claim) (:output stolen-claim)))
    (is (= (:research-claim-evidence-manifest/root (:semantic-evidence serial))
           (:analysis-output/evidence-root (:output serial-claim))))
    (is (= (:verification serial-claim) (:verification stolen-claim)))
    (is (= (:claim serial-claim) (:claim stolen-claim)))
    (is (= :not-established
           (get-in serial-claim [:claim :research-claim/status])))
    (is (analysis/verification-valid? (:verification serial-claim)))
    (is (= (:ingestion serial-work)
           (reduce claim-work/ingest (:ingestion serial-work)
                   (vals (:ingestion serial-work)))))))

(deftest claim-evidence-rejects-gaps-substitution-and-conflicting-delivery
  (let [context (fixture)
        descriptor (claim-descriptor)
        result (:analysis-output/result (output context :supported))
        plan (claim-work/build-plan context :claim/incentive-compatibility
                                    descriptor result)
        a (claim-runner 1)
        work (collect-claim-evidence context descriptor result plan [a])
        [coordinate-root observed] (first (:ingestion work))
        wrong-parent (assoc observed :research-claim-evidence-plan/root
                            (root :other-evidence-plan))
        wrong-parent (assoc wrong-parent :research-claim-evidence-observation/root
                            (hash-ref/sha256-ref
                             (hc/domain-hash
                              :research-claim-evidence-observation-v1
                              (dissoc wrong-parent
                                      :research-claim-evidence-observation/root))))
        conflict (assoc observed :evidence/semantic-result
                        (assoc (:evidence/semantic-result observed)
                               :evidence/status :contradicting))
        changed-context (assoc context :outcome-manifest
                               (outcome/build-manifest
                                {:benchmark/content-root
                                 (get-in context [:incentive-model
                                                  :incentive-model/subject-root])
                                 :benchmark/model-root
                                 (get-in context [:incentive-model
                                                  :incentive-model/root])
                                 :execution/status :completed
                                 :execution/command-root
                                 (get-in context [:research-command :command/hash])
                                 :outcomes/operational-root (root :operational)
                                 :outcomes/incentive-compatibility-root
                                 (root :other-ic)}))
        conflict-error (try (claim-work/ingest (:ingestion work) conflict)
                            nil (catch clojure.lang.ExceptionInfo e e))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (claim-work/reconcile
                  context :claim/incentive-compatibility descriptor result plan
                  (:assignments work) [a]
                  (dissoc (:ingestion work) coordinate-root))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (claim-work/build-output-from-evidence
                  context :claim/incentive-compatibility descriptor result plan
                  (:assignments work) [a]
                  (dissoc (:ingestion work) coordinate-root)
                  :engine/observed-trace "1")))
    (is (thrown? clojure.lang.ExceptionInfo
                 (claim-work/reconcile
                  context :claim/incentive-compatibility descriptor result plan
                  (conj (:assignments work) (first (:assignments work))) [a]
                  (:ingestion work))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (claim-work/reconcile
                  context :claim/incentive-compatibility descriptor result plan
                  (:assignments work) [a]
                  (assoc (:ingestion work) coordinate-root wrong-parent))))
    (is (= :claim-evidence-coordinate-conflict
           (:conflict/type (ex-data conflict-error))))
    (is (= observed (get (:ingestion work) coordinate-root)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (claim-work/observe
                  context :claim/another-proposition descriptor result plan
                  (first (:assignments work)) a)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (claim-work/observe
                  context :claim/incentive-compatibility descriptor
                  (assoc result :result/value 99) plan
                  (first (:assignments work)) a)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (claim-work/observe
                  changed-context :claim/incentive-compatibility descriptor result plan
                  (first (:assignments work)) a)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (claim-work/reconcile
                  context :claim/incentive-compatibility descriptor
                  (assoc result :result/value 99) plan
                  (:assignments work) [a] (:ingestion work))))))

(deftest claim-evidence-counterexample-is-preserved-not-outvoted
  (let [context (fixture)
        descriptor (claim-descriptor)
        result (:analysis-output/result (output context :counterexample-found))
        plan (claim-work/build-plan context :claim/incentive-compatibility
                                    descriptor result)
        a (claim-runner 1) b (claim-runner 2)
        work (collect-claim-evidence context descriptor result plan [a b])
        reconciled (claim-work/reconcile
                    context :claim/incentive-compatibility descriptor result plan
                    (:assignments work) [a b] (:ingestion work))
        claim (claim-from-evidence context descriptor result plan work [a b])
        classification (some #(when (= :contradicting (:evidence/status %)) %)
                             (get-in reconciled [:semantic-evidence
                                                 :evidence/coordinate-results]))]
    (is classification)
    (is (= (:result/witness-root result)
           (get-in classification [:evidence/value :result/witness-root])))
    (is (= :counterexample-found
           (get-in claim [:claim :research-claim/status])))))

(deftest claim-evidence-rejects-complete-but-mixed-source-coordinates
  (let [context (fixture)
        descriptor (claim-descriptor)
        supported (:analysis-output/result (output context :supported))
        counterexample (:analysis-output/result
                        (output context :counterexample-found))
        proposition :claim/incentive-compatibility
        supported-plan (claim-work/build-plan context proposition descriptor supported)
        counterexample-plan (claim-work/build-plan context proposition descriptor
                                                   counterexample)
        runner (claim-runner 1)
        supported-work (collect-claim-evidence context descriptor supported
                                               supported-plan [runner])
        counterexample-work (collect-claim-evidence context descriptor counterexample
                                                    counterexample-plan [runner])
        classification-coordinate
        (first (filter #(= :result-classification (:evidence/role %))
                       (claim-work/coordinates supported-plan)))
        foreign-coordinate
        (first (filter #(= :result-classification (:evidence/role %))
                       (claim-work/coordinates counterexample-plan)))
        foreign-observation
        (get (:ingestion counterexample-work)
             (:research-claim-evidence-coordinate/root foreign-coordinate))
        target-coordinate-root (:research-claim-evidence-coordinate/root
                                classification-coordinate)
        foreign-semantic
        (assoc (:evidence/semantic-result foreign-observation)
               :research-claim-evidence-coordinate/root target-coordinate-root)
        foreign-semantic
        (assoc foreign-semantic
               :research-claim-evidence-coordinate-result/root
               (hash-ref/sha256-ref
                (hc/domain-hash
                 :research-claim-evidence-coordinate-result-v1
                 (dissoc foreign-semantic
                         :research-claim-evidence-coordinate-result/root))))
        mixed-observation
        (assoc foreign-observation
               :research-claim-evidence-plan/root
               (:research-claim-evidence-plan/root supported-plan)
               :research-claim-evidence-coordinate/root target-coordinate-root
               :evidence/semantic-result foreign-semantic)
        mixed-observation
        (assoc mixed-observation
               :research-claim-evidence-observation/root
               (hash-ref/sha256-ref
                (hc/domain-hash
                 :research-claim-evidence-observation-v1
                 (dissoc mixed-observation
                         :research-claim-evidence-observation/root))))
        mixed-ingestion (assoc (:ingestion supported-work)
                               target-coordinate-root mixed-observation)]
    ;; Every coordinate is present and each submitted observation is rooted,
    ;; but a genuine counterexample classification from another result cannot
    ;; be relabelled as part of the supported result's evidence set.
    (is (= 3 (count mixed-ingestion)))
    (is (= :contradicting
           (get-in mixed-observation
                   [:evidence/semantic-result :evidence/status])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (claim-work/reconcile
                  context proposition descriptor supported supported-plan
                  (:assignments supported-work) [runner] mixed-ingestion)))))

(deftest rooted-claim-coordinate-transports-through-runner-http
  (let [context (fixture)
        descriptor (claim-descriptor)
        result (:analysis-output/result (output context :supported))
        proposition :claim/incentive-compatibility
        plan (claim-work/build-plan context proposition descriptor result)
        participant
        (participation/build-participation
         {:runner-reference (root :http-claim-runner)
          :disclosure-mode :pseudonymous :site-id :site/claim-http
          :opted-in? true :available? true
          :capabilities [:capability/claim-evidence :capability/anvil]
          :job-classes [:job/research-claim-evidence]
          :environment-kinds [:execution-environment/local :anvil]
          :qualification-references [remote-fixture/qualification-root]})
        coordinate-assignment (claim-work/steal-next plan participant [])
        coordinate-root (:research-claim-evidence-coordinate/root
                         coordinate-assignment)
        environment (remote-fixture/anvil-environment)
        execution-policy
        (remote-fixture/execution-policy
         {:objective :fastest :required-runs 1 :required-matching-runs 1
          :distinct-by [] :repeat-runner-policy :permitted})
        job (remote/build-job
             {:job-class :job/research-claim-evidence
              :workload-root coordinate-root
              :distributed-profile-root
              (:distributed-experiment-profile/root
               (remote-fixture/local-profile))
              :distributed-policy-root
              (:distributed-scheduler-policy/root
               remote-fixture/distributed-policy-artifact)
              :execution-environment-root
              (:research-execution-environment/root environment)
              :execution-policy-root
              (:research-execution-policy/root execution-policy)})
        job-assignment (remote/steal-next job environment execution-policy
                                          participant [])
        wrong-workload-job
        (let [changed (assoc job :workload/root (root :different-coordinate))]
          (assoc changed :research-execution-job/root (remote/job-root changed)))
        wrong-workload-assignment
        (remote/steal-next wrong-workload-job environment execution-policy
                           participant [])
        claim-payload {:context context :proposition-id proposition
                       :descriptor descriptor :result result :plan plan
                       :coordinate-assignment coordinate-assignment
                       :runner-participation participant}
        local-observation (claim-work/observe context proposition descriptor result
                                              plan coordinate-assignment participant)
        remote-url (System/getenv "PRF_CLAIM_EVIDENCE_RUNNER_URL")
        runner (when-not remote-url
                 (transport/start-runner!
                  {:runner-participation participant
                   :auth-secret "claim-evidence-test"}))
        runner-url (or remote-url (:transport/base-url runner))]
    (try
      (let [delivered (transport/deliver-claim-evidence-coordinate!
                       {:coordinator-root (root :claim-coordinator)
                        :endpoint {:transport/base-url runner-url
                                   :transport/auth-secret "claim-evidence-test"}
                        :job job :assignment job-assignment
                        :claim-evidence-work claim-payload :logical-cutpoint 11})
            observation (:research-claim-evidence-observation delivered)]
        (is (= :accepted (:delivery/status delivered)))
        (is (= local-observation observation))
        (is (= coordinate-root
               (:research-claim-evidence-coordinate/root observation)))
        (is (= :realized
               (get-in delivered [:reachability
                                  :reachability/outcome])))
        (is (thrown? clojure.lang.ExceptionInfo
                     (transport/deliver-claim-evidence-coordinate!
                      {:coordinator-root (root :claim-coordinator)
                       :endpoint {:transport/base-url runner-url
                                  :transport/auth-secret "claim-evidence-test"}
                       :job wrong-workload-job :assignment wrong-workload-assignment
                       :claim-evidence-work claim-payload :logical-cutpoint 12}))))
      (finally (when runner (transport/stop-runner! runner))))))

(deftest local-and-http-coordinate-schedules-preserve-semantic-roots
  (let [context (fixture)
        descriptor (claim-descriptor)
        result (:analysis-output/result (output context :supported))
        proposition :claim/incentive-compatibility
        plan (claim-work/build-plan context proposition descriptor result)
        participant
        (fn [label site]
          (participation/build-participation
           {:runner-reference (root label) :disclosure-mode :pseudonymous
            :site-id site :opted-in? true :available? true
            :capabilities [:capability/claim-evidence :capability/anvil]
            :job-classes [:job/research-claim-evidence]
            :environment-kinds [:execution-environment/local :anvil]
            :qualification-references [remote-fixture/qualification-root]}))
        runner-a (participant :equivalence-runner-a :site/equivalence-a)
        runner-b (participant :equivalence-runner-b :site/equivalence-b)
        participants [runner-a runner-b]
        local-work (collect-claim-evidence context descriptor result plan participants)
        local-reconciled (claim-work/reconcile
                          context proposition descriptor result plan
                          (:assignments local-work) participants (:ingestion local-work))
        local-claim (claim-from-evidence context descriptor result plan local-work participants)
        remote-runners
        (mapv #(transport/start-runner!
                {:runner-participation % :auth-secret "claim-equivalence-test"})
              participants)
        runner-by-root (into {} (map (juxt #(get-in % [:runner-participation
                                                       :runner-participation/root])
                                           identity)
                                     remote-runners))
        remote-coordinate-assignments
        (loop [coordinates (claim-work/coordinates plan) assignments [] result []]
          (if (seq coordinates)
            (let [target (nth [runner-b runner-a runner-b] (count assignments))
                  assigned (claim-work/steal-next plan target assignments)]
              (recur (next coordinates) (conj assignments assigned)
                     (conj result assigned)))
            result))
        environment (remote-fixture/anvil-environment)
        execution-policy
        (remote-fixture/execution-policy
         {:objective :fastest :required-runs 1 :required-matching-runs 1
          :distinct-by [] :repeat-runner-policy :permitted})
        profile (remote-fixture/local-profile)
        distributed-policy-root
        (:distributed-scheduler-policy/root remote-fixture/distributed-policy-artifact)]
    (try
      (let [{remote-assignments :assignments remote-ingestion :ingestion}
            (reduce
             (fn [{:keys [assignments ingestion]} coordinate-assignment]
               (let [target-participant
                     (first (filter #(= (:runner-participation/root %)
                                        (:runner-participation/root coordinate-assignment))
                                    participants))
                     coordinate-root
                     (:research-claim-evidence-coordinate/root coordinate-assignment)
                     job (remote/build-job
                          {:job-class :job/research-claim-evidence
                           :workload-root coordinate-root
                           :distributed-profile-root
                           (:distributed-experiment-profile/root profile)
                           :distributed-policy-root distributed-policy-root
                           :execution-environment-root
                           (:research-execution-environment/root environment)
                           :execution-policy-root
                           (:research-execution-policy/root execution-policy)})
                     job-assignment
                     (remote/steal-next job environment execution-policy
                                        target-participant [])
                     participant-root (:runner-participation/root target-participant)
                     runner (get runner-by-root participant-root)
                     returned
                     (transport/deliver-claim-evidence-coordinate!
                      {:coordinator-root (root :claim-equivalence-coordinator)
                       :endpoint {:transport/base-url (:transport/base-url runner)
                                  :transport/auth-secret "claim-equivalence-test"}
                       :job job :assignment job-assignment
                       :claim-evidence-work
                       {:context context :proposition-id proposition
                        :descriptor descriptor :result result :plan plan
                        :coordinate-assignment coordinate-assignment
                        :runner-participation target-participant}
                       :logical-cutpoint (inc (count assignments))})]
                 (is (= :accepted (:delivery/status returned)))
                 {:assignments (conj assignments coordinate-assignment)
                  :ingestion (claim-work/ingest
                              ingestion
                              (:research-claim-evidence-observation returned))}))
             {:assignments [] :ingestion {}}
             (reverse remote-coordinate-assignments))
            remote-reconciled (claim-work/reconcile
                               context proposition descriptor result plan
                               remote-assignments participants remote-ingestion)
            remote-work {:assignments remote-assignments :ingestion remote-ingestion}
            remote-claim (claim-from-evidence context descriptor result plan
                                              remote-work participants)]
        (is (= (get-in local-reconciled
                       [:semantic-evidence :research-claim-evidence-manifest/root])
               (get-in remote-reconciled
                       [:semantic-evidence :research-claim-evidence-manifest/root])))
        (is (not= (:forensic-reconciliation local-reconciled)
                  (:forensic-reconciliation remote-reconciled)))
        (is (= (:output local-claim) (:output remote-claim)))
        (is (= (:verification local-claim) (:verification remote-claim)))
        (is (= (:claim local-claim) (:claim remote-claim))))
      (finally
        (doseq [runner remote-runners]
          (transport/stop-runner! runner))))))
