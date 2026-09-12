(ns resolver-sim.community.design-scrutiny-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.community.attestation :as att]
            [resolver-sim.community.mailbox :as mailbox]
            [resolver-sim.community.task :as task]))

(def H1 "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
(def H2 "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
(def H3 "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc")
(def H4 "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd")
(def H5 "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee")

(defn- exec-att [& {:keys [task-ref exec-hash] :or {task-ref (str "research-task:sha256:" H1) exec-hash (str "evidence-node:sha256:" H2)}}]
  (att/build-execution-attestation
   {:task/ref task-ref :runner/id "r1" :execution-node-hash exec-hash
    :result-projection-hash H3 :code-hash H4 :env-hash H5
    :bundle-root H1 :registry-snapshot-hash H2}))

(deftest change-task-ref-changes-attestation-hash
  (let [h1 (:attestation/hash (exec-att))
        h2 (:attestation/hash (exec-att :task-ref (str "research-task:sha256:" H2)))]
    (is (not= h1 h2))))

(deftest change-challenged-ref-changes-challenge-hash
  (let [h1 (:attestation/hash
            (att/build-challenge-attestation
             {:task/ref (str "research-task:sha256:" H1)
              :challenged-attestation-ref (str "attestation:sha256:" H2)
              :runner/id "r2" :reason "test"}))
        h2 (:attestation/hash
            (att/build-challenge-attestation
             {:task/ref (str "research-task:sha256:" H1)
              :challenged-attestation-ref (str "attestation:sha256:" H3)
              :runner/id "r2" :reason "test"}))]
    (is (not= h1 h2))))

(deftest change-original-attestation-ref-changes-reproduction-hash
  (let [h1 (:attestation/hash
            (att/build-reproduction-attestation
             {:task/ref (str "research-task:sha256:" H1)
              :original-attestation-ref (str "attestation:sha256:" H2)
              :original-result-projection-hash H3
              :runner/id "r2" :code-hash H4 :env-hash H5
              :reproduction-execution-node-hash (str "evidence-node:sha256:" H4)
              :reproduction-result-projection-hash H3
              :comparison-policy :stable-projection-v0 :comparison-status :matched}))
        h2 (:attestation/hash
            (att/build-reproduction-attestation
             {:task/ref (str "research-task:sha256:" H1)
              :original-attestation-ref (str "attestation:sha256:" H3)
              :original-result-projection-hash H3
              :runner/id "r2" :code-hash H4 :env-hash H5
              :reproduction-execution-node-hash (str "evidence-node:sha256:" H4)
              :reproduction-result-projection-hash H3
              :comparison-policy :stable-projection-v0 :comparison-status :matched}))]
    (is (not= h1 h2))))

(deftest change-evidence-node-ref-changes-execution-hash
  (let [h1 (:attestation/hash (exec-att :exec-hash (str "evidence-node:sha256:" H2)))
        h2 (:attestation/hash (exec-att :exec-hash (str "evidence-node:sha256:" H3)))]
    (is (not= h1 h2))))

(deftest sender-identity-is-committed-in-hash
  (let [h1 (:message/hash
            (mailbox/build-message
             {:message/type :RUNNER_RESULT :subject-task (str "research-task:sha256:" H1) :sender "runner-alpha"}))
        h2 (:message/hash
            (mailbox/build-message
             {:message/type :RUNNER_RESULT :subject-task (str "research-task:sha256:" H1) :sender "runner-beta"}))]
    (is (not= h1 h2))))

(deftest runner-identity-is-committed-in-attestation-hash
  (let [h1 (:attestation/hash (exec-att))
        h2 (:attestation/hash (att/build-execution-attestation
                               {:task/ref (str "research-task:sha256:" H1) :runner/id "runner-beta"
                                :execution-node-hash (str "evidence-node:sha256:" H2)
                                :result-projection-hash H3 :code-hash H4 :env-hash H5
                                :bundle-root H1 :registry-snapshot-hash H2}))]
    (is (not= h1 h2))))

(deftest issued-at-is-included-in-attestation-hash
  (let [base {:task/ref (str "research-task:sha256:" H1) :runner/id "r1"
              :execution-node-hash (str "evidence-node:sha256:" H2)
              :result-projection-hash H3 :code-hash H4 :env-hash H5
              :bundle-root H1 :registry-snapshot-hash H2}]
    (is (not= (:attestation/hash (att/build-execution-attestation (assoc base :issued-at "2026-07-01T00:00:00Z")))
              (:attestation/hash (att/build-execution-attestation (assoc base :issued-at "2026-07-02T00:00:00Z")))))))

(deftest mailbox-timestamp-is-excluded-from-hash
  (is (= (:message/hash (mailbox/build-message {:message/type :TASK_ANNOUNCEMENT :subject-task (str "research-task:sha256:" H1) :sender "r1" :timestamp "2026-07-01T00:00:00Z"}))
         (:message/hash (mailbox/build-message {:message/type :TASK_ANNOUNCEMENT :subject-task (str "research-task:sha256:" H1) :sender "r1" :timestamp "2026-07-02T00:00:00Z"})))))
