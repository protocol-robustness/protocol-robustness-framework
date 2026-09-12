(ns resolver-sim.community.end-to-end-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.community.task :as task]
            [resolver-sim.community.attestation :as att]
            [resolver-sim.community.mailbox :as mailbox]
            [resolver-sim.community.graph :as graph]
            [resolver-sim.community.report :as report]))

(defn- with-temp-dirs [f]
  (let [artifact-dir (str (java.nio.file.Files/createTempDirectory "e2e-artifacts" (make-array java.nio.file.attribute.FileAttribute 0)))
        mailbox-dir (str (java.nio.file.Files/createTempDirectory "e2e-mailbox" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (binding [mailbox/*mailbox-dir* mailbox-dir]
      (f artifact-dir mailbox-dir))))

(def H1 "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
(def H2 "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
(def H3 "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc")
(def H4 "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd")
(def H5 "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee")

(def task-spec
  {:title "PRF-DR3 Deterministic Replay"
   :task/type :benchmark-execution
   :benchmark/id :benchmark/prf-deterministic-replay-v1
   :suite/id :suite/prf-replay-v1
   :claim-ids [:claim/replay-identical-results :claim/hash-consistency-across-runs :claim/no-nondeterminism]
   :acceptance-criteria ["All runs produce identical bundle roots" "Evidence hashes are consistent across runs"]})

(deftest positive-fixture-complete-workflow
  (with-temp-dirs
    (fn [artifact-dir mailbox-dir]
      (let [task-ref (:task/ref (task/build-task task-spec))]
        (testing "Register task"
          (let [t (task/build-task task-spec)]
            (is (task/valid-task? t))
            (mailbox/publish! (mailbox/build-message
                               {:message/type :TASK_ANNOUNCEMENT :subject-task (:task/ref t) :sender "researcher-1"}))))
        (testing "Runner executes"
          (let [a (att/build-execution-attestation
                   {:task/ref task-ref :runner/id "runner-alpha"
                    :execution-node-hash (str "evidence-node:sha256:" H1)
                    :result-projection-hash H2 :code-hash H3 :env-hash H4
                    :bundle-root H2 :registry-snapshot-hash H5})]
            (att/persist-attestation! a artifact-dir)
            (mailbox/publish! (mailbox/build-message
                               {:message/type :RUNNER_RESULT :subject-task task-ref :sender "runner-alpha"
                                :attestation-ref (:attestation/ref a)}))))
        (testing "Reproducer agrees"
          (let [a (att/build-reproduction-attestation
                   {:task/ref task-ref :original-attestation-ref (str "attestation:sha256:" H1)
                    :original-result-projection-hash H2
                    :runner/id "runner-beta" :code-hash H3 :env-hash H4
                    :reproduction-execution-node-hash (str "evidence-node:sha256:" H3)
                    :reproduction-result-projection-hash H2
                    :comparison-policy :stable-projection-v0 :comparison-status :matched})]
            (att/persist-attestation! a artifact-dir)
            (mailbox/publish! (mailbox/build-message
                               {:message/type :AGREEMENT :subject-task task-ref :sender "runner-beta"
                                :attestation-ref (:attestation/ref a)}))))
        (testing "Mailbox integration"
          (let [msgs (mailbox/messages-for-task task-ref)]
            (is (= 3 (count msgs)))
            (is (= :agreed (mailbox/task-status task-ref)))))
        (testing "Evidence graph"
          (let [msgs (mailbox/messages-for-task task-ref)
                attestations (keep (fn [ref] (att/resolve-attestation artifact-dir ref)) (keep :attestation-ref msgs))
                g (graph/build-task-graph-projection {:task (task/build-task task-spec) :messages msgs :attestations attestations})]
            (is (= 6 (get-in g [:summary :node-count])))
            (is (= :agreed (get-in g [:summary :task-status])))))
        (testing "Report generation"
          (let [msgs (mailbox/messages-for-task task-ref)
                attestations (keep (fn [ref] (att/resolve-attestation artifact-dir ref)) (keep :attestation-ref msgs))
                r (report/build-report {:task (task/build-task task-spec) :attestations attestations :messages msgs})]
            (is (string? (get-in r [:task :id])))
            (is (some? (:original-run r)))
            (is (some? (:reproduction-run r)))
            (is (= :agreed (:task-status r)))))))))

(deftest negative-fixture-challenge-on-mismatch
  (with-temp-dirs
    (fn [artifact-dir mailbox-dir]
      (let [task-ref (:task/ref (task/build-task task-spec))]
        (testing "Original runner executes"
          (let [a (att/build-execution-attestation
                   {:task/ref task-ref :runner/id "runner-alpha"
                    :execution-node-hash (str "evidence-node:sha256:" H1)
                    :result-projection-hash H2 :code-hash H3 :env-hash H4
                    :bundle-root H2 :registry-snapshot-hash H5})]
            (att/persist-attestation! a artifact-dir)
            (mailbox/publish! (mailbox/build-message
                               {:message/type :RUNNER_RESULT :subject-task task-ref :sender "runner-alpha"
                                :attestation-ref (:attestation/ref a)}))))
        (testing "Challenger detects mismatch"
          (let [challenge (att/build-challenge-attestation
                           {:task/ref task-ref :challenged-attestation-ref (str "attestation:sha256:" H1)
                            :runner/id "runner-beta" :reason "Stable result differs from claimed output"
                            :challenge-type :result-mismatch})]
            (att/persist-attestation! challenge artifact-dir)
            (mailbox/publish! (mailbox/build-message
                               {:message/type :CHALLENGE :subject-task task-ref :sender "runner-beta"
                                :attestation-ref (:attestation/ref challenge)}))))
        (testing "Original result preserved, status challenged"
          (let [msgs (mailbox/messages-for-task task-ref)]
            (is (some #(= :RUNNER_RESULT (:message/type %)) msgs))
            (is (some #(= :CHALLENGE (:message/type %)) msgs))
            (is (= :challenged (mailbox/task-status task-ref)))))
        (testing "Report shows unresolved challenge"
          (let [msgs (mailbox/messages-for-task task-ref)
                attestations (keep (fn [ref] (att/resolve-attestation artifact-dir ref)) (keep :attestation-ref msgs))
                r (report/build-report {:task (task/build-task task-spec) :messages msgs :attestations attestations})]
            (is (= :challenged (:task-status r)))
            (is (seq (:challenges r)))
            (is (seq (:unresolved-references r)))))))))
