(ns resolver-sim.benchmark.research-assignment-target-binding-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.benchmark.incentive-deviation-domain :as domain]
            [resolver-sim.benchmark.incentive-model :as model]
            [resolver-sim.benchmark.outcome-manifest :as outcome]
            [resolver-sim.benchmark.research-analysis :as analysis]
            [resolver-sim.benchmark.research-analysis-closure :as closure]
            [resolver-sim.benchmark.research-assignment :as assignment]
            [resolver-sim.benchmark.research-assignment-target-binding :as binding]
            [resolver-sim.benchmark.research-command :as command]
            [resolver-sim.benchmark.researcher-reward :as reward]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(defn- root [label]
  (hash-ref/sha256-ref (hc/domain-hash :evidence-record label)))

(defn- legacy-root [label]
  (subs (root [:legacy label]) 7))

(defn- target [assignment-root suffix]
  (binding/build-binding
   {:research-assignment-target-binding/assignment-root assignment-root
    :solidity-target/deployment-root (legacy-root [:deployment suffix])
    :solidity-target/release-root (legacy-root [:release suffix])
    :solidity-target/configuration-root (root [:configuration suffix])}))

(defn- fixture []
  (let [command (command/build-command
                 {:schema-version command/schema-version-v2
                  :command/id :command/solidity-adversarial
                  :command/type :benchmark-evaluation
                  :command/argv ["prf" "benchmark" "run"]
                  :command/includes [{:kind :research-scope/analysis
                                      :ref :research-analysis/incentive-compatibility}]
                  :command/environment-root (root :environment)
                  :command/runner-root (root :runner)
                  :command/input-root (root :pending-subject)
                  :command/output-root (root :output)})
        assignment (assignment/build-assignment
                    {:research-assignment/id :assignment/solidity-adversarial
                     :research-assignment/environment-hash (root :environment)
                     :research-assignment/policy-hash (root :assignment-policy)
                     :research-assignment/review-round-hash (root :round)
                     :research-assignment/request-root (root :request)
                     :research-assignment/target
                     {:target/kind :governance-mandated
                      :target/public-force-authorisation-scope-hash (root :scope)
                      :target/workflow-id 0
                      :target/reason :solidity-assurance}
                     :research-assignment/command-root (:command/hash command)
                     :research-assignment/plan-root (root :plan)})
        target-binding (target (:research-assignment/hash assignment) :shortfall-v2)
        subject (:research-assignment-target-binding/subject-root target-binding)
        incentive-model (model/build-model
                         {:incentive-model/id :model/solidity-shortfall
                          :incentive-model/subject-root subject
                          :incentive-model/participant-roles [:actor/researcher]
                          :incentive-model/payoff-interpretation :net-payoff
                          :incentive-model/rewards {} :incentive-model/penalties {}
                          :incentive-model/costs {}
                          :incentive-model/evaluator-semantics-root (root :evaluator)
                          :incentive-model/policy-roots [(root :policy)]})
        deviation-domain (domain/build-domain
                          {:deviation-domain/id :domain/solidity-adversarial
                           :deviation-domain/subject-root subject
                           :deviation-domain/incentive-model-root (:incentive-model/root incentive-model)
                           :deviation-domain/baseline-strategy :strategy/honest
                           :deviation-domain/participants [:actor/researcher]
                           :deviation-domain/deviations [:strategy/exploit]
                           :deviation-domain/coalition-scope :none
                           :deviation-domain/constraints {:trace-count 1}
                           :deviation-domain/evaluation-method :observed-single-trace})
        outcome-manifest (outcome/build-manifest
                          {:benchmark/content-root subject
                           :benchmark/model-root (:incentive-model/root incentive-model)
                           :execution/status :completed
                           :execution/command-root (:command/hash command)
                           :outcomes/operational-root (root :operational)
                           :outcomes/incentive-compatibility-root (root :counterexample)})
        context {:research-command command :incentive-model incentive-model
                 :deviation-domain deviation-domain :research-assignment assignment
                 :outcome-manifest outcome-manifest}
        descriptor (analysis/build-result-descriptor
                    {:measure/id :metric/shortfall-violation
                     :measure/value-domain :integer :measure/unit :unit/wei
                     :measure/direction :lower-is-better
                     :measure/comparison-semantics :exact-integer
                     :measure/witness-schema-root (root :shortfall-witness-schema)})
        closure-report (closure/verify-closure context)
        analysis-output (analysis/build-output
                         (assoc context
                                :analysis-output/closure-root (:research-analysis/root closure-report)
                                :analysis-output/result-descriptor descriptor
                                :analysis-engine/id :engine/solidity-adversarial
                                :analysis-engine/version "1"
                                :analysis-output/proposition-id :claim/shortfall-safety
                                :analysis-output/result
                                {:schema-version analysis/result-schema-version
                                 :result/classification :counterexample-found
                                 :result/value -1 :result/case-root (root :case)
                                 :result/witness-root (root :witness)}
                                :analysis-output/evidence-root (root :evidence)))
        verification (analysis/verify-output (assoc context :analysis-output analysis-output))
        claim (analysis/derive-claim analysis-output verification incentive-model)
        policy (reward/build-policy
                {:accepted-contribution-types #{:adversarial-finding}
                 :required-verification #{:verified :claimable}
                 :required-claim-statuses #{:counterexample-found}
                 :reward-amount 100 :reward-currency "USD"
                 :split-rules {:default :full} :duplicate-rule {:mode :reject}
                 :conflict-of-interest-rule {:mode :reject}})
        eligibility (reward/evaluate
                     {:policy policy :researcher "independent-researcher"
                      :assignment-root (:research-assignment/hash assignment)
                      :contribution-root (:research-claim/root claim)
                      :contribution-type :adversarial-finding
                      :verification {:verified? true
                                     :research-analysis-verification/root
                                     (:research-analysis-verification/root verification)}
                      :claim {:claimable? true :research-claim/status (:research-claim/status claim)
                              :research-claim/root (:research-claim/root claim)}
                      :duplicate? false :conflict-of-interest? false})
        proposal (reward/build-proposal {:eligibility eligibility :policy policy})
        obligation (reward/build-obligation
                    {:proposal proposal :eligibility eligibility
                     :approval-authority {:authority/id "assurance-committee"
                                          :authority/root (root :authority)
                                          :authority/authorized? true}})]
    (assoc context :target-binding target-binding :analysis-output analysis-output
           :verification verification :claim claim :policy policy :eligibility eligibility
           :proposal proposal :obligation obligation)))

(deftest reference-solidity-adversarial-finding-produces-an-obligation
  (let [context (fixture)
        result (reward/verify-adversarial-finding-lineage context)]
    (is (:valid? result))
    (is (= (get-in context [:target-binding :solidity-target/root])
           (get-in context [:claim :research-claim/subject-root])))
    (is (= :adversarial-finding (get-in context [:obligation :contribution/type])))
    (is (hash-ref/valid-sha256-ref?
         (get-in context [:obligation :researcher-reward-obligation/root])))))

(deftest binding-and-lineage-reject-substitution
  (let [context (fixture)
        assignment-root (get-in context [:research-assignment :research-assignment/hash])
        target-b (target assignment-root :other-target)
        other-assignment (root :other-assignment)
        binding-other-assignment (target other-assignment :shortfall-v2)
        substituted-obligation (assoc (:obligation context)
                                      :research-assignment/root other-assignment)
        substituted-obligation
        (assoc substituted-obligation :researcher-reward-obligation/root
               (reward/obligation-root substituted-obligation))]
    (is (not= (:solidity-target/root (:target-binding context))
              (:solidity-target/root target-b)))
    (is (some #{:claim-subject-mismatch}
              (:errors (reward/verify-adversarial-finding-lineage
                        (assoc context :target-binding target-b)))))
    (is (some #{:target-binding-assignment-mismatch}
              (:errors (reward/verify-adversarial-finding-lineage
                        (assoc context :target-binding binding-other-assignment)))))
    (is (some #{:obligation-assignment-mismatch}
              (:errors (reward/verify-adversarial-finding-lineage
                        (assoc context :obligation substituted-obligation)))))))

(deftest target-root-commits-each-existing-solidity-identity
  (let [context (fixture)
        binding (:target-binding context)]
    (doseq [field [:solidity-target/deployment-root
                   :solidity-target/release-root
                   :solidity-target/configuration-root]]
      (let [changed (assoc binding field (if (= field :solidity-target/configuration-root)
                                           (root [field :other])
                                           (legacy-root [field :other])))
            changed (assoc changed :solidity-target/root (binding/target-root changed))
            changed (assoc changed :research-assignment-target-binding/subject-root
                           (:solidity-target/root changed))
            changed (assoc changed :research-assignment-target-binding/root
                           (binding/binding-root changed))]
        (is (:valid? (binding/validate-binding changed)))
        (is (not= (:solidity-target/root binding) (:solidity-target/root changed)))
        (is (some #{:claim-subject-mismatch}
                  (:errors (reward/verify-adversarial-finding-lineage
                            (assoc context :target-binding changed)))))))))
