(ns resolver-sim.benchmark.research-claim-acceptance-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.benchmark.research-analysis :as analysis]
            [resolver-sim.benchmark.research-claim-acceptance :as acceptance]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]))

(defn- root [label]
  (ref/sha256-ref (hc/domain-hash :evidence-record {:label label})))

(defn- claim-and-verification []
  (let [output-root (root :output)
        verification-base {:schema-version "research-analysis-verification.v1"
                           :research-analysis-verification/output-root output-root
                           :research-analysis-verification/command-root (root :command)
                           :research-analysis-verification/model-root (root :model)
                           :research-analysis-verification/domain-root (root :domain)
                           :research-analysis-verification/assignment-root (root :assignment)
                           :research-analysis-verification/outcome-root (root :outcome)
                           :research-analysis-verification/status :verified
                           :research-analysis-verification/claim-status :not-established
                           :errors []}
        verification (assoc verification-base
                            :research-analysis-verification/root
                            (analysis/verification-root verification-base))
        claim-base {:schema-version "research-analytical-claim.v2"
                    :research-claim/analysis-output-root output-root
                    :research-claim/subject-root (root :subject)
                    :research-claim/model-root (root :model)
                    :research-claim/proposition-id :claim/example
                    :research-claim/domain-root (root :domain)
                    :research-claim/metric-id :metric/example
                    :research-claim/result {:result/value 1}
                    :research-claim/evidence-root (root :evidence)
                    :research-claim/verification-root
                    (:research-analysis-verification/root verification)
                    :research-claim/status :not-established}]
    [(assoc claim-base :research-claim/root (analysis/claim-root claim-base)) verification]))

(defn- fixture []
  (let [[claim verification] (claim-and-verification)
        policy (acceptance/build-policy
                {:acceptance-policy/id :policy/direct
                 :acceptance-policy/authority-root (root :authority)})]
    {:claim claim :verification verification :policy policy}))

(defn- build [context]
  (acceptance/build-acceptance
   (assoc context
          :decision :accepted
          :decision-evidence-root (root :decision-evidence)
          :accepted-at "2026-09-20T00:00:00Z"
          :acceptance-sequence 0)))

(deftest builds-policy-derived-authority-acceptance
  (let [context (fixture)
        artifact (build context)]
    (is (:valid? (acceptance/validate-policy (:policy context))))
    (is (= (:acceptance-policy/authority-root (:policy context))
           (:research-claim-acceptance/authority-root artifact)))
    (is (:valid? (acceptance/validate-acceptance artifact context)))
    (is (= (:research-claim/root (:claim context))
           (:research-claim-acceptance/claim-root artifact)))
    (is (= (:research-claim-acceptance/root artifact)
           (acceptance/acceptance-root artifact)))))

(deftest authority-cannot-be-substituted-by-acceptance-caller
  (let [context (fixture)
        artifact (build context)
        forged (assoc artifact :research-claim-acceptance/authority-root (root :other-authority))]
    (is (not (:valid? (acceptance/validate-acceptance forged context))))
    (is (some #{:authority-root-mismatch}
              (:errors (acceptance/validate-acceptance forged context))))))

(deftest acceptance-rejects-lineage-substitution
  (let [context (fixture)
        artifact (build context)]
    (doseq [[field value error]
            [[:research-claim-acceptance/claim-root (root :other-claim) :claim-root-mismatch]
             [:research-claim-acceptance/verification-root (root :other-verification)
              :verification-root-mismatch]
             [:research-claim-acceptance/policy-root (root :other-policy)
              :policy-root-mismatch]]]
      (let [result (acceptance/validate-acceptance
                    (assoc artifact field value) context)]
        (is (not (:valid? result)))
        (is (some #{error} (:errors result)))))))

(deftest decision-evidence-is-required-but-opaque
  (let [context (fixture)
        artifact (build context)
        missing (dissoc artifact :research-claim-acceptance/decision-evidence-root)
        malformed (assoc artifact :research-claim-acceptance/decision-evidence-root :opaque)]
    (is (not (:valid? (acceptance/validate-acceptance missing context))))
    (is (not (:valid? (acceptance/validate-acceptance malformed context))))
    (is (:valid? (acceptance/validate-acceptance artifact context)))))

(deftest acceptance-rejects-invalid-decision-and-sequence
  (let [context (fixture)
        artifact (build context)]
    (is (not (:valid? (acceptance/validate-acceptance
                       (assoc artifact :research-claim-acceptance/decision :maybe)
                       context))))
    (is (not (:valid? (acceptance/validate-acceptance
                       (assoc artifact :research-claim-acceptance/acceptance-sequence -1)
                       context))))))
