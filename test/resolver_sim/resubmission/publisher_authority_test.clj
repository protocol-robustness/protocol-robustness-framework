(ns resolver-sim.resubmission.publisher-authority-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.resubmission.publisher-authority :as authority]
            [resolver-sim.resubmission.publisher-statement :as statement]
            [resolver-sim.resubmission.execution-evidence-subject :as evidence]
            [resolver-sim.resubmission.acceptance-evaluation :as evaluation]
            [resolver-sim.support.ed25519 :as ed]))

(def entry
  {:principal/id "principal-1"
   :key/id "key-1"
   :key/public "public-key-1"
   :authorized-actions [:prf.resubmission/publish-attempt]})

(deftest publisher-authority-is-closed-and-rooted
  (let [artifact (authority/build {:publisher-authority/entries [entry]})]
    (is (authority/valid? artifact))
    (is (= (:attempt-publisher-authority/root artifact)
           (authority/root artifact)))
    (is (= entry (authority/authorized-key artifact "key-1")))
    (is (nil? (authority/authorized-key artifact "attacker")))))

(deftest signed-and-verified-subjects-bind-their-content
  (let [bundle "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        statement-artifact (statement/build-statement "principal-1" "key-1" bundle)
        envelope (statement/build-envelope (:publisher/statement-root statement-artifact)
                                           "key-1" {:algorithm :ed25519 :bytes "sig"})
        subject (evidence/build
                 "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                 "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
                 bundle :prf/execution :prf/profile)]
    (is (statement/statement-binds-bundle? statement-artifact bundle))
    (is (statement/envelope-binds-statement? envelope statement-artifact))
    (is (not (statement/statement-binds-bundle? statement-artifact
                                                "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd")))
    (is (evidence/binds-artifacts? subject
                                   (:subject/execution-evidence-root subject)
                                   (:subject/results-root subject)
                                   bundle))
    (is (not (evidence/binds-artifacts? subject
                                        (:subject/execution-evidence-root subject)
                                        "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
                                        bundle)))))

(deftest evaluator-subject-bindings-fail-closed
  (let [bundle "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        keypair (ed/keypair :publisher-test)
        authority-entry (assoc entry :key/public (:public-hex keypair))
        unsigned (statement/build-statement "principal-1" "key-1" bundle)
        statement-artifact (statement/sign-statement unsigned (:private-key keypair))
        signature (:publisher/signature statement-artifact)
        envelope (statement/build-envelope (:publisher/statement-root statement-artifact)
                                           "key-1" signature)
        authority-artifact (authority/build {:publisher-authority/entries [authority-entry]})
        subject (evidence/build
                 "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                 "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
                 bundle :prf/execution :prf/profile)]
    (is (:valid? (evaluation/validate-publisher-binding
                  statement-artifact envelope authority-artifact bundle)))
    (is (= :publisher-bundle-mismatch
           (:reason (evaluation/validate-publisher-binding
                     statement-artifact envelope authority-artifact
                     "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"))))
    (is (:valid? (evaluation/validate-execution-subject-binding
                  subject (:subject/execution-evidence-root subject)
                  (:subject/results-root subject) bundle)))
    (is (= :execution-subject-binding-mismatch
           (:reason (evaluation/validate-execution-subject-binding
                     subject (:subject/execution-evidence-root subject)
                     "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
                     bundle))))))

(deftest publisher-authority-rejects-ambiguous-or-extra-content
  (let [artifact (authority/build {:publisher-authority/entries [entry]})]
    (testing "unknown keys fail closed"
      (is (not (authority/valid? (assoc artifact :publisher-authority/policy :caller-selected)))))
    (testing "duplicate key identities fail closed"
      (is (not (authority/valid?
                (authority/build {:publisher-authority/entries [entry entry]})))))
    (testing "wrong action is not publication authority"
      (is (not (authority/valid?
                (authority/build
                 {:publisher-authority/entries
                  [(assoc entry :authorized-actions [:prf.resubmission/apply-disposition])]})))))))
