(ns resolver-sim.community.core-test
  (:require [clojure.test :refer [deftest is testing thrown-with-msg? use-fixtures]]
            [resolver-sim.community.task :as task]
            [resolver-sim.community.finding :as finding]
            [resolver-sim.community.attestation :as att]
            [resolver-sim.community.mailbox :as mailbox]
            [resolver-sim.community.graph :as graph]
            [resolver-sim.community.report :as report]
            [resolver-sim.community.cli :as cli]))

(defn temp-dir-fixture [f]
  (let [d (str (java.nio.file.Files/createTempDirectory "community-test" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (binding [mailbox/*mailbox-dir* (str d "/mailbox")]
      (f))))

(use-fixtures :each temp-dir-fixture)

(deftest community-task-requires-registered-benchmark
  (testing "unknown or missing benchmark identity fails closed"
    (with-redefs [cli/resolve-benchmark-manifest (constantly nil)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"requires a registered benchmark manifest"
                            (#'cli/required-benchmark-manifest :unknown/benchmark)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"requires a registered benchmark manifest"
                            (#'cli/required-benchmark-manifest nil)))))
  (testing "a registered benchmark resolves without a built-in fallback"
    (with-redefs [cli/resolve-benchmark-manifest (constantly "registered.edn")]
      (is (= "registered.edn"
             (#'cli/required-benchmark-manifest :registered/benchmark))))))

(defn- valid-exec-spec [& {:keys [task-ref runner-id exec-hash result-hash code-hash env-hash bundle registry issued-at]
                           :or {task-ref "research-task:sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                                runner-id "runner-1"
                                exec-hash "evidence-node:sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                                result-hash "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
                                code-hash "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
                                env-hash "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
                                bundle "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
                                registry "1111111111111111111111111111111111111111111111111111111111111111"
                                issued-at "2026-07-01T00:00:00Z"}}]
  {:task/ref task-ref :runner/id runner-id
   :code-hash code-hash :env-hash env-hash :bundle-root bundle
   :execution-node-hash exec-hash :result-projection-hash result-hash
   :registry-snapshot-hash registry :issued-at issued-at})

(defn- valid-repro-spec [& {:keys [task-ref orig-att-ref orig-result runner-id repro-exec repro-result comp-policy comp-status
                                   code-hash env-hash issued-at]
                            :or {task-ref "research-task:sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                                 orig-att-ref "attestation:sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                                 orig-result "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
                                 runner-id "runner-2"
                                 repro-exec "evidence-node:sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
                                 repro-result "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
                                 comp-policy :stable-projection-v0
                                 comp-status :matched
                                 code-hash "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
                                 env-hash "1111111111111111111111111111111111111111111111111111111111111111"
                                 issued-at "2026-07-01T00:00:00Z"}}]
  {:task/ref task-ref :original-attestation-ref orig-att-ref
   :original-result-projection-hash orig-result
   :runner/id runner-id :code-hash code-hash :env-hash env-hash
   :reproduction-execution-node-hash repro-exec
   :reproduction-result-projection-hash repro-result
   :comparison-policy comp-policy :comparison-status comp-status
   :issued-at issued-at})

;; ── Canonical identity ──────────────────────────────────

(deftest equivalent-task-hashes-match
  (let [spec {:title "Test" :benchmark/id :test}
        t1 (task/build-task spec)
        t2 (task/build-task spec)]
    (is (= (:task/hash t1) (:task/hash t2)))))

(deftest equivalent-attestation-hashes-match
  (let [a1 (att/build-execution-attestation (valid-exec-spec))
        a2 (att/build-execution-attestation (valid-exec-spec))]
    (is (= (:attestation/hash a1) (:attestation/hash a2)))))

(deftest changing-subject-changes-attestation-hash
  (let [a1 (att/build-execution-attestation (valid-exec-spec))
        a2 (att/build-execution-attestation (valid-exec-spec
                                             :task-ref "research-task:sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"))]
    (is (not= (:attestation/hash a1) (:attestation/hash a2)))))

(deftest changing-predicate-changes-attestation-hash
  (let [a1 (att/build-execution-attestation (valid-exec-spec))
        a2 (att/build-reproduction-attestation (valid-repro-spec))]
    (is (not= (:attestation/hash a1) (:attestation/hash a2)))))

(deftest tampered-message-is-detected
  (let [m (mailbox/build-message
           {:message/type :TASK_ANNOUNCEMENT :subject-task "research-task:sha256:abc123" :sender "runner-1"})
        tampered (assoc m :sender "different-runner")]
    (is (not (:hash-valid? (mailbox/verify-message tampered))))))

;; ── External resolution ─────────────────────────────────

(deftest clean-process-resolves-task
  (let [t (task/build-task {:title "Test Task" :benchmark/id :test})]
    (is (task/valid-task? t))
    (is (task/task-ref? (:task/ref t)))))

(deftest missing-references-fail-explicitly
  (is (thrown? AssertionError (task/build-task {:title nil})))
  (is (thrown? AssertionError (finding/build-finding {:task/reference "invalid-ref"}))))

(deftest bare-references-are-rejected
  (is (not (task/task-ref? "bare-id")))
  (is (not (task/task-ref? "sha256:abc")))
  (is (task/task-ref? "research-task:sha256:abc123def456abc123def456abc123def456abc123def456abc123def456abc1")))

;; ── Mailbox ─────────────────────────────────────────────

(deftest message-hashes-are-deterministic
  (let [m1 (mailbox/build-message
            {:message/type :TASK_ANNOUNCEMENT :subject-task "research-task:sha256:abc123" :sender "runner-1"})
        m2 (mailbox/build-message
            {:message/type :TASK_ANNOUNCEMENT :subject-task "research-task:sha256:abc123" :sender "runner-1"})]
    (is (= (:message/hash m1) (:message/hash m2)))))

(deftest duplicate-messages-are-detected
  (let [m (mailbox/build-message
           {:message/type :TASK_ANNOUNCEMENT :subject-task "research-task:sha256:abc123" :sender "runner-1"})]
    (is (= :published (mailbox/publish! m)))
    (is (= :duplicate (mailbox/publish! m)))))

(deftest task-correlation-is-deterministic
  (let [task-ref "research-task:sha256:abc123"
        m1 (mailbox/build-message {:message/type :TASK_ANNOUNCEMENT :subject-task task-ref :sender "r1"})
        m2 (mailbox/build-message {:message/type :RUNNER_RESULT :subject-task task-ref :sender "r2"})
        m3 (mailbox/build-message {:message/type :CHALLENGE :subject-task task-ref :sender "r3"})]
    (mailbox/publish! m1)
    (mailbox/publish! m2)
    (mailbox/publish! m3)
    (let [for-task (mailbox/messages-for-task task-ref)]
      (is (= 3 (count for-task)))
      (is (= :challenged (mailbox/task-status task-ref))))))

;; ── Graph and report ────────────────────────────────────

(deftest graph-traversal-reaches-all-records
  (let [t (task/build-task {:title "Graph Test" :benchmark/id :test})
        m (mailbox/build-message
           {:message/type :TASK_ANNOUNCEMENT :subject-task (:task/ref t) :sender "runner-1"})
        a (att/build-execution-attestation (valid-exec-spec :task-ref (:task/ref t)))
        g (graph/build-task-graph-projection {:task t :messages [m] :attestations [a]})]
    (is (= 3 (:node-count (:summary g))))
    (is (= 2 (:edge-count (:summary g))))
    (is (= :announced (:task-status (:summary g))))))

(deftest report-is-side-effect-free
  (let [t (task/build-task {:title "Report Test" :benchmark/id :test})
        a (att/build-execution-attestation (valid-exec-spec :task-ref (:task/ref t)))
        r (report/build-report {:task t :attestations [a]})]
    (is (= (:schema-version r) "community-report.v0"))
    (is (= (:task-status r) :unknown))
    (is (string? (get-in r [:original-run :attestation-hash])))))

;; ── Attestation validation ──────────────────────────────

(deftest valid-signed-execution-attestation-passes
  (let [a (att/build-execution-attestation (valid-exec-spec))]
    (is (att/valid-attestation? a))))

(deftest wrong-subject-hash-fails
  (let [a (att/build-execution-attestation (valid-exec-spec))
        tampered (assoc-in a [:subject :hash] "wrong-hash")]
    (is (not (att/valid-attestation? tampered)))))

;; ── Challenge semantics ─────────────────────────────────

(deftest challenge-does-not-mutate-original
  (let [orig (att/build-execution-attestation (valid-exec-spec))
        challenge (att/build-challenge-attestation
                   {:task/ref "research-task:sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                    :challenged-attestation-ref (:attestation/ref orig)
                    :runner/id "runner-2" :reason "Evidence cannot be resolved"
                    :challenge-type :evidence-unresolvable :issued-at "2026-07-01T00:00:00Z"})]
    (is (att/valid-attestation? orig))
    (is (att/valid-attestation? challenge))
    (is (not= (:attestation/hash orig) (:attestation/hash challenge)))))

(deftest unresolved-challenge-produces-inconclusive-status
  (let [task-ref "research-task:sha256:challenge-test"
        m (mailbox/build-message
           {:message/type :CHALLENGE :subject-task task-ref :sender "challenger"})]
    (mailbox/publish! m)
    (is (= :challenged (mailbox/task-status task-ref)))))

;; ── Nil-required-field detection per predicate ────────────

(deftest execution-attestation-rejects-nil-code-hash
  (let [a (att/build-execution-attestation (valid-exec-spec))
        tampered (assoc-in a [:assertion :code-hash] nil)]
    (is (not (att/valid-attestation? tampered)))))

(deftest execution-attestation-rejects-nil-env-hash
  (let [a (att/build-execution-attestation (valid-exec-spec))
        tampered (assoc-in a [:assertion :env-hash] nil)]
    (is (not (att/valid-attestation? tampered)))))

(deftest execution-attestation-rejects-nil-bundle-root
  (let [a (att/build-execution-attestation (valid-exec-spec))
        tampered (assoc-in a [:assertion :bundle-root] nil)]
    (is (not (att/valid-attestation? tampered)))))

(deftest execution-attestation-rejects-nil-execution-node-hash
  (let [a (att/build-execution-attestation (valid-exec-spec))
        tampered (assoc-in a [:assertion :execution-node-hash] nil)]
    (is (not (att/valid-attestation? tampered)))))

(deftest execution-attestation-rejects-nil-result-projection
  (let [a (att/build-execution-attestation (valid-exec-spec))
        tampered (assoc-in a [:assertion :result-projection-hash] nil)]
    (is (not (att/valid-attestation? tampered)))))

(deftest execution-attestation-rejects-nil-registry-snapshot
  (let [a (att/build-execution-attestation (valid-exec-spec))
        tampered (assoc-in a [:context :registry-snapshot/hash] nil)]
    (is (not (att/valid-attestation? tampered)))))

(deftest reproduction-attestation-rejects-nil-original-att-ref
  (let [a (att/build-reproduction-attestation (valid-repro-spec))
        tampered (assoc-in a [:assertion :original-attestation-ref] nil)]
    (is (not (att/valid-attestation? tampered)))))

(deftest reproduction-attestation-rejects-nil-original-result-proj
  (let [a (att/build-reproduction-attestation (valid-repro-spec))
        tampered (assoc-in a [:assertion :original-result-projection-hash] nil)]
    (is (not (att/valid-attestation? tampered)))))

(deftest reproduction-attestation-rejects-nil-repro-exec-node-hash
  (let [a (att/build-reproduction-attestation (valid-repro-spec))
        tampered (assoc-in a [:assertion :reproduction-execution-node-hash] nil)]
    (is (not (att/valid-attestation? tampered)))))

(deftest reproduction-attestation-rejects-nil-repro-result-proj
  (let [a (att/build-reproduction-attestation (valid-repro-spec))
        tampered (assoc-in a [:assertion :reproduction-result-projection-hash] nil)]
    (is (not (att/valid-attestation? tampered)))))

(deftest reproduction-attestation-rejects-nil-comparison-status
  (let [a (att/build-reproduction-attestation (valid-repro-spec))
        tampered (assoc-in a [:assertion :comparison-status] nil)]
    (is (not (att/valid-attestation? tampered)))))

(deftest challenge-attestation-rejects-nil-challenged-att-ref
  (let [task-ref "research-task:sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        a (att/build-challenge-attestation
           {:task/ref task-ref
            :challenged-attestation-ref "attestation:sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
            :runner/id "runner-2" :reason "Evidence cannot be resolved"})
        tampered (assoc-in a [:assertion :challenged-attestation-ref] nil)]
    (is (not (att/valid-attestation? tampered)))))

(deftest challenge-attestation-rejects-nil-reason
  (let [task-ref "research-task:sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        a (att/build-challenge-attestation
           {:task/ref task-ref
            :challenged-attestation-ref "attestation:sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
            :runner/id "runner-2" :reason "Evidence cannot be resolved"})
        tampered (assoc-in a [:assertion :reason] nil)]
    (is (not (att/valid-attestation? tampered)))))
