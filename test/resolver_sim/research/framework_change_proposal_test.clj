(ns resolver-sim.research.framework-change-proposal-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.research.framework-change-proposal :as fcp]))

(def sample-target
  {:repository-id "resolver-sim"
   :base-commit "abc123"
   :component-kind :comparison-predicate
   :component-id :exact-replication-scope?})

(def sample-provenance
  {:proposed-by "researcher-a"
   :created-at "2026-07-26T00:00:00Z"})

(defn valid-proposal-params []
  {:proposal/title "exact-replication-scope? should include model-root"
   :proposal/change-class :research-semantic
   :proposal/research-question "Does exact-replication-scope? prevent cross-model comparison?"
   :proposal/target sample-target
   :proposal/current-contract {:summary "9-field comparison, no model-root"}
   :proposal/proposed-contract {:summary "10-field comparison including model-root"}
   :proposal/implementation {:status :implemented :commit "def456"}
   :proposal/provenance sample-provenance})

(deftest valid-proposal-builds
  (let [proposal (fcp/build-proposal (valid-proposal-params))]
    (is (= "research-framework-change-proposal.v1" (:schema-version proposal)))
    (is (some? (:proposal/id proposal)))
    (is (some? (:proposal/hash proposal)))
    (is (= :research-semantic (:proposal/change-class proposal)))
    (is (= :draft (:proposal/status proposal)))))

(deftest valid-proposal-validates
  (let [proposal (fcp/build-proposal (valid-proposal-params))
        result (fcp/validate-proposal proposal)]
    (is (:valid? result))))

(deftest proposal-hash-changes-on-contract-change
  (let [a (fcp/build-proposal (valid-proposal-params))
        params (valid-proposal-params)
        params (assoc params :proposal/proposed-contract {:summary "different contract"})
        b (fcp/build-proposal params)]
    (is (not= (:proposal/hash a) (:proposal/hash b))
        "changing proposed contract should change the proposal hash")))

(deftest proposal-hash-changes-on-impl-change
  (let [a (fcp/build-proposal (valid-proposal-params))
        params (valid-proposal-params)
        params (assoc params :proposal/implementation {:status :implemented :commit "xyz789"})
        b (fcp/build-proposal params)]
    (is (not= (:proposal/hash a) (:proposal/hash b))
        "changing implementation commit should change the proposal hash")
    (is (= (:proposal/proposed-contract a) (:proposal/proposed-contract b))
        "proposed contract should remain unchanged when implementation changes")))

(deftest proposal-and-impl-remain-distinct
  (let [proposal (fcp/build-proposal (valid-proposal-params))]
    (is (some? (:proposal/hash proposal)))
    (is (some? (:proposal/implementation proposal)))
    (is (not= (:proposal/hash proposal) (get-in proposal [:proposal/implementation :commit]))
        "proposal hash and implementation commit must be different identifiers")))

(deftest unknown-change-class-rejected
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid change class"
                        (fcp/build-proposal (assoc (valid-proposal-params)
                                                   :proposal/change-class :invalid-class)))))

(deftest malformed-target-rejected
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires :component-id"
                        (fcp/build-proposal (assoc (valid-proposal-params)
                                                   :proposal/target {:repository-id "r"})))))

(deftest missing-title-rejected
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires a title"
                        (fcp/build-proposal (dissoc (valid-proposal-params) :proposal/title)))))

(deftest supersession-preserves-original
  (let [original (fcp/build-proposal (valid-proposal-params))
        original-hash (:proposal/hash original)
        successor-params (valid-proposal-params)
        successor-params (assoc successor-params :proposal/title "Updated proposal")
        successor (fcp/supersede original successor-params)]
    (is (= original-hash (:proposal/supersedes successor)))
    (is (some? (:proposal/hash successor)))
    (is (not= original-hash (:proposal/hash successor))
        "successor hash must differ from original")))

(deftest validate-detects-missing-research-question
  (let [proposal (fcp/build-proposal (valid-proposal-params))
        stripped (dissoc proposal :proposal/research-question)
        result (fcp/validate-proposal stripped)]
    (is (not (:valid? result)))
    (is (some #(re-find #"research-question" %) (:errors result)))))

(deftest valid-change-class-predicate
  (is (fcp/valid-change-class? :research-semantic))
  (is (fcp/valid-change-class? :implementation-only))
  (is (not (fcp/valid-change-class? :nonexistent-class))))

(deftest valid-proposal-status-predicate
  (is (fcp/valid-proposal-status? :draft))
  (is (fcp/valid-proposal-status? :open-for-review))
  (is (not (fcp/valid-proposal-status? :bogus-status))))

(deftest validate-detects-unknown-change-class
  (let [proposal (fcp/build-proposal (assoc (valid-proposal-params)
                                            :proposal/change-class :research-semantic))
        bad (assoc proposal :proposal/change-class :nonexistent)
        result (fcp/validate-proposal bad)]
    (is (not (:valid? result)))))
