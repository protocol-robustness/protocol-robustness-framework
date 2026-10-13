(ns resolver-sim.workflow-group-test
  "Tests for the framework-neutral immutable workflow-group membership primitives."
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [resolver-sim.workflow-group :as wg]
            [resolver-sim.hash.canonical :as hash]))

(defn -main [& _]
  (run-tests 'resolver-sim.workflow-group-test))

(deftest canonical-member-representation
  (testing "workflow-group-member produces a deterministic canonical member map"
    (is (= {:workflow-group/member-kind :sew/workflow
            :workflow-group/workflow-id 0}
           (wg/workflow-group-member :sew/workflow 0)))
    (is (= {:workflow-group/member-kind :sew/workflow
            :workflow-group/workflow-id 42}
           (wg/workflow-group-member :sew/workflow 42)))))

(deftest workflow-id-normalization
  (testing "normalizes integers, numeric strings, and keyword-like values to plain integers"
    (is (= 0 (wg/normalize-workflow-id 0)))
    (is (= 7 (wg/normalize-workflow-id "7")))
    (is (= 7 (wg/normalize-workflow-id " 7 ")))
    (is (= 7 (wg/normalize-workflow-id ":7")))
    (is (= 7 (wg/normalize-workflow-id :7))))
  (testing "non-parseable values pass through unchanged"
    (is (= "not-an-id" (wg/normalize-workflow-id "not-an-id")))))

(deftest membership-positive-and-negative
  (let [group [(wg/workflow-group-member :sew/workflow 0)
               (wg/workflow-group-member :sew/workflow 1)]]
    (testing "member present"
      (is (true? (wg/workflow-group-member? group (wg/workflow-group-member :sew/workflow 0))))
      (is (true? (wg/workflow-group-member? group (wg/workflow-group-member :sew/workflow "1"))))
      (is (true? (wg/workflow-group-member? group (wg/workflow-group-member :sew/workflow ":1")))))
    (testing "member absent"
      (is (false? (wg/workflow-group-member? group (wg/workflow-group-member :sew/workflow 2))))
      (is (false? (wg/workflow-group-member? group (wg/workflow-group-member :sew/workflow 3))))))
  (testing "kind is part of identity — same workflow-id, different kind is NOT a member"
    (let [group [(wg/workflow-group-member :sew/workflow 0)]]
      (is (false? (wg/workflow-group-member? group (wg/workflow-group-member :other/kind 0)))))))

(deftest member-hash-stability
  (let [a (wg/workflow-group-member-hash (wg/workflow-group-member :sew/workflow 0))
        b (wg/workflow-group-member-hash (wg/workflow-group-member :sew/workflow 0))]
    (is (= a b) "member hash is deterministic")
    (is (string? a))
    (is (= 64 (count a)) "sha256 hex digest")))

(deftest member-hash-changes-with-identity
  (let [base (wg/workflow-group-member :sew/workflow 0)]
    (testing "changes when workflow-id changes"
      (is (not= (wg/workflow-group-member-hash base)
                (wg/workflow-group-member-hash (wg/workflow-group-member :sew/workflow 1)))))
    (testing "changes when kind changes"
      (is (not= (wg/workflow-group-member-hash base)
                (wg/workflow-group-member-hash (wg/workflow-group-member :other/kind 0)))))))

(deftest member-hash-domain-separation
  (testing "WORKFLOW_GROUP_MEMBER_V1 is distinct from adjacent domains"
    (let [member (wg/workflow-group-member :sew/workflow 0)
          wg-hash (wg/workflow-group-member-hash member)
          claim-set-hash (hash/domain-hash "claim-set" {:workflow-group/member-kind :sew/workflow :workflow-group/workflow-id 0})
          check-set-hash (hash/domain-hash "check-set" {:workflow-group/member-kind :sew/workflow :workflow-group/workflow-id 0})
          auth-scope-hash (hash/domain-hash "force-authorisation-scope" {:workflow-group/member-kind :sew/workflow :workflow-group/workflow-id 0})
          source-tree-hash (hash/domain-hash "source-tree" {:workflow-group/member-kind :sew/workflow :workflow-group/workflow-id 0})
          related-member-hash (hash/domain-hash "related-claims-member" {:workflow-group/member-kind :sew/workflow :workflow-group/workflow-id 0})]
      (is (not= wg-hash claim-set-hash))
      (is (not= wg-hash check-set-hash))
      (is (not= wg-hash auth-scope-hash))
      (is (not= wg-hash source-tree-hash))
      (is (not= wg-hash related-member-hash)))))

(deftest member-hash-commits-algorithm
  (testing "commits :sha256 and rejects unsupported algorithms"
    (is (string? (wg/workflow-group-member-hash
                  (assoc (wg/workflow-group-member :sew/workflow 0)
                         :workflow-group/member-hash-algorithm :sha256))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"unsupported hash algorithm"
                          (wg/workflow-group-member-hash
                           (assoc (wg/workflow-group-member :sew/workflow 0)
                                  :workflow-group/member-hash-algorithm :md5))))))

(deftest valid-members-predicate
  (testing "empty group is invalid"
    (is (false? (wg/valid-workflow-group-members? []))))
  (testing "single-member group is valid"
    (is (true? (wg/valid-workflow-group-members? [(wg/workflow-group-member :sew/workflow 0)]))))
  (testing "duplicate member identity is invalid"
    (is (false? (wg/valid-workflow-group-members?
                 [(wg/workflow-group-member :sew/workflow 0)
                  (wg/workflow-group-member :sew/workflow "0")]))))
  (testing "distinct members are valid, order-independent"
    (is (true? (wg/valid-workflow-group-members?
                [(wg/workflow-group-member :sew/workflow 0)
                 (wg/workflow-group-member :sew/workflow 1)])))))

(deftest membership-ordering-determinism
  (testing "membership is order-independent"
    (let [a [(wg/workflow-group-member :sew/workflow 0)
             (wg/workflow-group-member :sew/workflow 1)]
          b [(wg/workflow-group-member :sew/workflow 1)
             (wg/workflow-group-member :sew/workflow 0)]]
      (is (true? (wg/workflow-group-member? a (wg/workflow-group-member :sew/workflow 0))))
      (is (true? (wg/workflow-group-member? b (wg/workflow-group-member :sew/workflow 0)))))))

;; ---------------------------------------------------------------------------
;; workflow-group.v1 first-class hashed artifact
;; ---------------------------------------------------------------------------

(deftest workflow-group-artifact-schema
  (let [g (wg/workflow-group [(wg/workflow-group-member :sew/workflow 0)
                              (wg/workflow-group-member :sew/workflow 1)]
                             #{:audit-only})]
    (is (= "workflow-group.v1" (:workflow-group/schema-version g)))
    (is (= 2 (:workflow-group/member-count g)))
    (is (= #{:audit-only} (:workflow-group/semantics g)))
    (is (= :sha256 (:workflow-group/hash-algorithm g)))
    (is (some? (:workflow-group/hash g)))
    (is (= 64 (count (:workflow-group/hash g))))))

(deftest workflow-group-hash-determinism-and-ordering
  (let [members [(wg/workflow-group-member :sew/workflow 0)
                 (wg/workflow-group-member :sew/workflow 1)]
        a (wg/workflow-group-hash members #{:audit-only})
        b (wg/workflow-group-hash (reverse members) #{:audit-only})]
    (is (= a b) "group hash is order-independent")
    (is (= a (wg/workflow-group-hash members #{:audit-only})) "group hash is deterministic")))

(deftest workflow-group-hash-changes-with-content
  (let [base [(wg/workflow-group-member :sew/workflow 0)
              (wg/workflow-group-member :sew/workflow 1)]
        base-hash (wg/workflow-group-hash base #{:audit-only})]
    (testing "changes when a member is added"
      (is (not= base-hash
                (wg/workflow-group-hash (conj base (wg/workflow-group-member :sew/workflow 2))
                                        #{:audit-only}))))
    (testing "changes when semantics change"
      (is (not= base-hash
                (wg/workflow-group-hash base #{:audit-only :batch-force-authorisation}))))))

(deftest workflow-group-hash-domain-separation
  (testing "WORKFLOW_GROUP_V1 is distinct from the member hash domain"
    (let [members [(wg/workflow-group-member :sew/workflow 0)]
          group-hash (wg/workflow-group-hash members #{:audit-only})
          member-hash (wg/workflow-group-member-hash (first members))]
      (is (not= group-hash member-hash))))
  (testing "artifact stores canonical members and its hash commits their identity hashes"
    (let [members [(wg/workflow-group-member :sew/workflow 0)]
          artifact (wg/workflow-group members #{:audit-only})]
      (is (= members (:workflow-group/members artifact)))
      (is (= (wg/workflow-group-hash members #{:audit-only})
             (:workflow-group/hash artifact))))))

(deftest workflow-group-artifact-rejects-unsupported-algorithm
  (testing "group hash rejects unsupported hash algorithms"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"unsupported hash algorithm"
                          (wg/workflow-group-hash [(wg/workflow-group-member :sew/workflow 0)]
                                                  #{:audit-only}
                                                  :md5))))
  (testing "default algorithm is accepted"
    (is (string? (wg/workflow-group-hash [(wg/workflow-group-member :sew/workflow 0)]
                                         #{:audit-only}
                                         :sha256)))))
