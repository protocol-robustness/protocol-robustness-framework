(ns resolver-sim.protocols.sew.dispute-context.disagree-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [resolver-sim.benchmark.researcher-force-authorisation :as decisions]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as ref]
            [resolver-sim.protocols.sew.dispute-context.disagree :as disagree]
            [resolver-sim.research.ratification :as ratification]
            [resolver-sim.use-cases.registry :as use-cases]))

(def ^:private test-private-key "test-keys/test-researcher-signing-key")
(def ^:private test-public-key "test-keys/test-researcher-signing-key.pub")

(defn- test-root [domain value]
  (ref/sha256-ref (canonical/domain-hash :claim-outcome-v1
                                         {:test/domain domain :test/value value})))

(defn- signed-dissent-inputs [case]
  (let [basis-root (test-root :test/sew-case-basis (:basis/root case))
        case (assoc case :basis/root basis-root)
        subject-root (ref/sha256-ref
                      (canonical/domain-hash
                       :prf-dispute-resolution-closure-v1
                       (select-keys case [:case/id :workflow/id :round/id :basis/root])))
        round-root (test-root :test/sew-case-round
                              (select-keys case [:case/id :workflow/id :round/id]))
        evidence-root (test-root :test/sew-case-evidence (:case/id case))
        authority-root (test-root :test/sew-case-policy-authority (:case/id case))
        policy (ratification/build-policy
                {:id :sew/research-policy
                 :authority-root authority-root
                 :eligible-principals #{:researcher/d}
                 :threshold 1
                 :allow-dissent? true
                 :challenges-block? true})
        target (ratification/build-target
                {:domain :prf/sew-dispute
                 :subject-root subject-root
                 :evidence-root evidence-root
                 :round-root round-root
                 :policy-root (:policy/root policy)
                 :schema "sew-dispute-resolution-basis.v1"})
        case (-> case
                 (assoc-in [:research :basis/root] basis-root)
                 (assoc-in [:research :subject/root] subject-root)
                 (assoc-in [:research :round/root] round-root)
                 (assoc-in [:research :policy/root] (:policy/root policy))
                 (assoc-in [:research :target/root] (:target/root target)))
        outcome-root (ratification/contribution-subject-root
                      {:target-root (:target/root target)
                       :principal-id :researcher/d
                       :kind :dissent
                       :evidence-root nil})
        decision (decisions/build-signed-decision-v2
                  "researcher-d" :sew/research-review
                  (:target/root target) round-root outcome-root :dissent
                  test-private-key
                  :signing-key-id "test-researcher-key"
                  :dissent-reason "Reproduction evidence is incomplete")
        contribution (ratification/build-contribution
                      {:target target :principal-id :researcher/d
                       :decision decision :kind :dissent})]
    {:case case :target target :policy policy :contributions [contribution]
     :key-bindings {"researcher-d" {:researcher/id "researcher-d"
                                    :principal/id :researcher/d
                                    :public-key-path test-public-key}}}))

(deftest dispute-context-disagree-fixtures-are-case-bound
  (let [fixture (disagree/load-fixtures)
        validation (disagree/validate-fixtures fixture)
        [upheld overturned procedural unauthorized invalid dissent] (:cases fixture)]
    (is (:valid? validation) (pr-str (:errors validation)))
    (is (every? #(.isFile (io/file %)) (:fixture/source-scenarios fixture)))
    (is (= :upheld-on-merits (get-in upheld [:appeal :decision])))
    (is (= [:prior-resolver-per-policy 95]
           ((juxt :disposition :amount) (:bond upheld))))
    (is (= :overturned-on-merits (get-in overturned [:appeal :decision])))
    (is (= [:appellant-refund-per-policy 95]
           ((juxt :disposition :amount) (:bond overturned))))
    (is (= :procedurally-rejected (get-in procedural [:appeal :status])))
    (is (nil? (get-in procedural [:appeal :decision])))
    (is (= :unauthorized (get-in unauthorized [:appeal :reason])))
    (is (= :invalid (get-in invalid [:appeal :reason])))
    (is (= (get-in dissent [:settlement :before-root])
           (get-in dissent [:settlement :after-root])))
    (is (false? (:valid? (disagree/validate-case-binding
                          upheld (assoc upheld :case/id (:case/id overturned))))))
    (is (false? (:valid? (disagree/validate-fixtures
                          (assoc-in fixture [:cases 0 :bond]
                                    (:bond overturned))))))
    (is (false? (:valid? (disagree/validate-fixtures
                          (assoc-in fixture [:cases 5 :research :target/root]
                                    "another-basis")))))))

(deftest dispute-appeal-use-case-description-loads
  (let [registry (use-cases/load-use-case-registry
                  "examples/use-cases/ecommerce/registry.edn")
        appeal (some #(when (= :dispute/appeal-escalation (:concept/id %)) %)
                     (:use-cases registry))
        summary (:concept/summary appeal)]
    (is (some? appeal))
    (is (re-find #"procedural" summary))
    (is (re-find #"researcher disagreement" summary))))

(deftest signed-dissent-is-verified-on-its-exact-case-basis
  (let [fixture (disagree/load-fixtures)
        dissent (last (:cases fixture))
        inputs (signed-dissent-inputs dissent)
        result (disagree/verify-research-dissent inputs)
        tampered (assoc-in inputs
                           [:contributions 0 :contribution/decision :dissent/reason]
                           "different dissent")
        transplanted (assoc-in inputs [:case :workflow/id] "another-workflow")]
    (is (:valid? result) (pr-str result))
    (is (= :verified (:signature/status result)))
    (is (= :rejected (:ratification/status result))
        "A dissent alone is valid evidence but cannot meet an approval threshold")
    (is (false? (:operational-authority? result)))
    (is (= 1 (:ratification/dissent-count
              (ratification/reconstruct-ratification
               (select-keys inputs [:target :policy :contributions :key-bindings])))))
    (is (false? (:valid? (disagree/verify-research-dissent tampered))))
    (is (false? (:valid? (disagree/verify-research-dissent transplanted))))
    (is (= (get-in inputs [:case :settlement :before-root])
           (get-in inputs [:case :settlement :after-root])))))
