(ns resolver-sim.benchmark.research-ratification-artifact-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [resolver-sim.benchmark.incentive-deviation-domain :as domain]
            [resolver-sim.benchmark.incentive-model :as model]
            [resolver-sim.benchmark.outcome-manifest :as outcome]
            [resolver-sim.benchmark.research-analysis :as analysis]
            [resolver-sim.benchmark.research-analysis-closure :as closure]
            [resolver-sim.benchmark.research-assignment :as assignment]
            [resolver-sim.benchmark.research-command :as command]
            [resolver-sim.benchmark.research-ratification-artifact :as artifact]
            [resolver-sim.benchmark.researcher-force-authorisation :as decisions]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as ref]
            [resolver-sim.io.content-addressed-store :as cas]
            [resolver-sim.research.ratification :as ratification]))

(def ^:private private-key "test/fixtures/signing-keys/test-researcher-signing-key")
(def ^:private public-key "test/fixtures/signing-keys/test-researcher-signing-key.pub")
(def ^:private round-root (ref/sha256-ref (canonical/domain-hash :evidence-record {:round :one})))
(def ^:private evidence-root (ref/sha256-ref (canonical/domain-hash :evidence-record {:evidence :claim})))
(def ^:private authority-root (ref/sha256-ref (canonical/domain-hash :evidence-record {:authority :local-test})))

(defn- root [label]
  (ref/sha256-ref (canonical/domain-hash :evidence-record {:label label})))

(defn- analysis-inputs []
  (let [subject (root :model-subject)
        incentive-model (model/build-model
                         {:incentive-model/id :model/claim
                          :incentive-model/subject-root subject
                          :incentive-model/participant-roles [:actor/researcher]
                          :incentive-model/payoff-interpretation :net-payoff
                          :incentive-model/rewards {} :incentive-model/penalties {}
                          :incentive-model/costs {}
                          :incentive-model/evaluator-semantics-root (root :evaluator)
                          :incentive-model/policy-roots [(root :model-policy)]})
        research-command (command/build-command
                          {:schema-version command/schema-version-v2
                           :command/id :command/example
                           :command/type :benchmark-evaluation
                           :command/argv ["prf" "benchmark" "run"]
                           :command/includes [{:kind :research-scope/analysis
                                               :ref :research-analysis/incentive-compatibility}]
                           :command/environment-root (root :environment)
                           :command/runner-root (root :runner)
                           :command/input-root subject
                           :command/output-root (root :output)})
        deviation-domain (domain/build-domain
                          {:deviation-domain/id :domain/example
                           :deviation-domain/subject-root subject
                           :deviation-domain/incentive-model-root (:incentive-model/root incentive-model)
                           :deviation-domain/baseline-strategy :strategy/base
                           :deviation-domain/participants [:actor/researcher]
                           :deviation-domain/deviations [:strategy/alternate]
                           :deviation-domain/coalition-scope :none
                           :deviation-domain/constraints {:cases 1}
                           :deviation-domain/evaluation-method :observed-single-trace})
        research-assignment (assignment/build-assignment
                             {:research-assignment/id :assignment/example
                              :research-assignment/environment-hash (root :environment)
                              :research-assignment/policy-hash (root :assignment-policy)
                              :research-assignment/review-round-hash round-root
                              :research-assignment/request-root (root :request)
                              :research-assignment/target
                              {:target/kind :governance-mandated
                               :target/public-force-authorisation-scope-hash (root :scope)
                               :target/workflow-id 0 :target/reason :research}
                              :research-assignment/command-root (:command/hash research-command)
                              :research-assignment/plan-root (root :plan)})
        outcome-manifest (outcome/build-manifest
                          {:benchmark/content-root subject
                           :benchmark/model-root (:incentive-model/root incentive-model)
                           :execution/status :completed
                           :execution/command-root (:command/hash research-command)
                           :outcomes/operational-root (root :operational)
                           :outcomes/incentive-compatibility-root (root :result)})]
    {:research-command research-command :incentive-model incentive-model
     :deviation-domain deviation-domain :research-assignment research-assignment
     :outcome-manifest outcome-manifest}))

(defn- claim-fixture []
  (let [context (analysis-inputs)
        descriptor (analysis/build-result-descriptor
                    {:measure/id :metric/value :measure/value-domain :integer
                     :measure/unit :unit/abstract :measure/direction :unordered
                     :measure/comparison-semantics :exact-integer})
        closure-root (:research-analysis/root (closure/verify-closure context))
        output (analysis/build-output
                (assoc context :analysis-output/closure-root closure-root
                       :analysis-output/result-descriptor descriptor
                       :analysis-engine/id :engine/test :analysis-engine/version "1"
                       :analysis-output/proposition-id :claim/example
                       :analysis-output/metric-id :metric/value
                       :analysis-output/result {:schema-version analysis/result-schema-version
                                                :result/classification :supported
                                                :result/value 1 :result/case-root (root :case)}
                       :analysis-output/evidence-root evidence-root))
        context (assoc context :analysis-output output)
        verification (analysis/verify-output context)
        claim (analysis/derive-claim output verification (:incentive-model context))]
    {:claim claim :analysis-context context}))

(defn- signed-contribution [target researcher principal decision-kind contribution-kind]
  (let [subject-root (ratification/contribution-subject-root
                      {:target-root (:target/root target) :principal-id principal
                       :kind contribution-kind :evidence-root nil})
        decision (decisions/build-signed-decision-v2
                  researcher (:target/root target) (:target/root target) round-root
                  subject-root decision-kind private-key
                  :signing-key-id (str "key/" researcher)
                  :dissent-reason (when (= decision-kind :dissent) "disagreement"))]
    (ratification/build-contribution
     {:target target :principal-id principal :kind contribution-kind :decision decision})))

(def ^:private key-bindings
  {"r-a" {:researcher/id "r-a" :principal/id :principal/a :public-key-path public-key}
   "r-b" {:researcher/id "r-b" :principal/id :principal/b :public-key-path public-key}
   "r-c" {:researcher/id "r-c" :principal/id :principal/c :public-key-path public-key}})

(defn- bundle []
  (let [{:keys [claim analysis-context]} (claim-fixture)
        policy (ratification/build-policy
                {:id :policy/claim-review :authority-root authority-root
                 :eligible-principals #{:principal/a :principal/b :principal/c}
                 :threshold 2 :allow-dissent? true :challenges-block? true})
        target (ratification/build-target
                {:domain :prf/analytical-claim
                 :subject-root (:research-claim/root claim)
                 :evidence-root (:research-claim/evidence-root claim)
                 :round-root round-root :policy-root (:policy/root policy)
                 :schema "research-analytical-claim.v2"})
        contributions [(signed-contribution target "r-a" :principal/a :approve :approve)
                       (signed-contribution target "r-b" :principal/b :dissent :dissent)]
        trust-roots [authority-root (root :key-registry)]
        key-registry-root (root :key-registry)
        incentive-links {:incentive/policy-root (root :incentive-policy)
                         :incentive/eligibility-root (root :eligibility)
                         :incentive/obligation-root (root :obligation)}]
    {:claim claim :analysis-context analysis-context :target target :policy policy
     :contributions contributions :trust-roots trust-roots
     :key-registry-root key-registry-root
     :incentive-links incentive-links}))

(deftest retained-artifact-reconstructs-claim-and-contested-ratification
  (let [inputs (bundle)
        saved (artifact/build-artifact (assoc inputs :key-bindings key-bindings))
        verification (artifact/verify-artifact saved key-bindings)]
    (is (:valid? verification))
    (is (= :verified (:verification/claim verification)))
    (is (= :verified (:verification/ratification verification)))
    (is (= :contested-unresolved
           (get-in saved [:artifact/ratification :ratification/status])))
    (is (false? (:artifact/authority-issued? saved)))
    (is (false? (:artifact/rewards-created? saved)))
    (is (= :externally-anchored (:verification/trust-roots verification)))))

(deftest tampering-and-incentive-links-do-not-create-authority-or-payment
  (let [inputs (bundle)
        saved (artifact/build-artifact (assoc inputs :key-bindings key-bindings))
        tampered (-> saved
                     (assoc :artifact/rewards-created? true)
                     (assoc :artifact/root (artifact/artifact-root
                                            (dissoc (assoc saved :artifact/rewards-created? true)
                                                    :artifact/root))))]
    (is (not (:valid? (artifact/verify-artifact tampered key-bindings))))
    (is (some #{:research-ratification-cannot-create-rewards}
              (:errors (artifact/verify-artifact tampered key-bindings))))
    (is (not (:valid? (artifact/verify-artifact
                       (assoc saved :artifact/trust-roots []) key-bindings))))))

(deftest artifact-persists-and-resolves-through-immutable-cas
  (let [inputs (bundle)
        saved (artifact/build-artifact (assoc inputs :key-bindings key-bindings))
        directory (doto (java.io.File/createTempFile "ratification-cas" "")
                    (.delete)
                    (.mkdir))
        store (cas/create-store (.getPath directory))]
    (try
      (let [put-result (artifact/persist-artifact! store saved key-bindings)
            resolved (artifact/resolve-artifact store (:artifact/root saved) key-bindings)]
        (is (contains? #{:created :exists} (:status put-result)))
        (is (:valid? resolved))
        (is (= saved (:artifact resolved))))
      (finally
        (io/delete-file directory true)))))
