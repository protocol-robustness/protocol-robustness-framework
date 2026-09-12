(ns resolver-sim.claim-outcome-test
  "Tests for the claim-outcome.v1 first-class evaluated-claim artifact."
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [resolver-sim.claim-outcome :as co]
            [resolver-sim.hash.canonical :as hash]))

(defn -main [& _]
  (run-tests 'resolver-sim.claim-outcome-test))

(defn- sample-artifact
  [& {:keys [outcome subject evidence] :or {outcome :pass subject "run-root-0" evidence ["ev-1" "ev-2"]}}]
  (co/claim-outcome
   {:definition-root "claim-def-root-0"
    :subject-root subject
    :evidence-roots evidence
    :outcome outcome
    :severity :high
    :basis :scenario-replay}))

(deftest claim-outcome-artifact-schema
  (let [a (sample-artifact)]
    (is (= "claim-outcome.v1" (:claim/schema-version a)))
    (is (= "claim-def-root-0" (:claim/definition-root a)))
    (is (= "run-root-0" (:claim/subject-root a)))
    (is (= ["ev-1" "ev-2"] (:claim/evidence-roots a)))
    (is (= :pass (:claim/outcome a)))
    (is (= :high (:claim/severity a)))
    (is (= :scenario-replay (:claim/basis a)))
    (is (= :sha256 (:claim/hash-algorithm a)))
    (is (some? (:claim/hash a)))
    (is (= 64 (count (:claim/hash a))))))

(deftest claim-outcome-hash-determinism-and-sensitivity
  (let [a (sample-artifact)
        b (sample-artifact)]
    (is (= (:claim/hash a) (:claim/hash b)) "deterministic")
    (is (not= (:claim/hash a)
              (:claim/hash (sample-artifact :outcome :fail)))
        "changes when outcome changes")
    (is (not= (:claim/hash a)
              (:claim/hash (sample-artifact :subject "other-root")))
        "changes when subject root changes")
    (is (not= (:claim/hash a)
              (:claim/hash (sample-artifact :evidence ["ev-1"])))
        "changes when evidence roots change")))

(deftest claim-outcome-evidence-ordering-independent
  (testing "evidence-root order is not significant"
    (let [a (sample-artifact :evidence ["ev-1" "ev-2"])
          b (sample-artifact :evidence ["ev-2" "ev-1"])]
      (is (= (:claim/hash a) (:claim/hash b))))))

(deftest claim-outcome-domain-separation
  (testing "CLAIM_OUTCOME_V1 is distinct from adjacent hash domains"
    (let [a (sample-artifact)
          dh (hash/domain-hash "DEFERRAL_V1" {:claim/outcome :pass :claim/subject-root "run-root-0"})
          wg (hash/domain-hash "WORKFLOW_GROUP_V1" {:claim/outcome :pass :claim/subject-root "run-root-0"})]
      (is (not= (:claim/hash a) dh))
      (is (not= (:claim/hash a) wg)))))

(deftest claim-outcome-rejects-unsupported-algorithm
  (let [a (sample-artifact)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"unsupported hash algorithm"
                          (co/claim-outcome-hash a :md5)))
    (is (string? (co/claim-outcome-hash a :sha256)))))

(deftest claim-outcome-validates-outcome-vocabulary
  (testing "unknown outcomes are rejected, not silently accepted"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"unsupported claim outcome"
                          (co/claim-outcome {:definition-root "d" :subject-root "s"
                                             :evidence-roots [] :outcome :bogus
                                             :severity :high}))))
  (testing "supported outcomes are accepted"
    (doseq [outcome [:pass :fail :inconclusive :not-implemented :not-exercised]]
      (is (some? (co/claim-outcome {:definition-root "d" :subject-root "s"
                                    :evidence-roots [] :outcome outcome
                                    :severity :low}))))))
