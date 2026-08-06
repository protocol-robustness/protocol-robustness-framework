(ns resolver-sim.sensitivity.sentinel-client-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [resolver-sim.sensitivity.sentinel-client :as client]
            [resolver-sim.sensitivity.contract :as contract]
            [resolver-sim.sensitivity.sentinel :as sentinel]
            [resolver-sim.signed-external-decision :as sed]
            [resolver-sim.commands.sentinel :as cmd]
            [resolver-sim.support.ed25519 :as fx]))

(defn- fake-runner
  "In-process authority runner for tests: runs the real authority decision core
   against the request and returns it as the subprocess would."
  [kp]
  (fn [_argv input _timeout]
    (let [request (edn/read-string input)
          resp (cmd/decide {:private-key (:private-key kp) :key-id (:key/id kp)} request)]
      {:exit 0 :stdout (pr-str resp) :stderr ""})))

(defn- config [kp]
  {:command ["java" "-jar" "/dev/null/prf.jar" "sentinel" "check"]
   :trust-policy (fx/trust-policy kp)
   :runner (fake-runner kp)})

(defn- signed-allow-response
  "Build a response carrying a signed :allow decision for the request."
  [kp req]
  (let [report {:sentinel/version "sensitivity-sentinel.v1"
                :sentinel/decision :allow
                :sentinel/level :sensitivity/public
                :sentinel/structural-level :sensitivity/public
                :sentinel/reasons []
                :sentinel/allowed-sinks [:ipfs]
                :sentinel/redaction-required? false
                :sentinel/override-required? {:required? false :mode :single}}
        decision (contract/build-decision {:request req :report report :sink (:sink req)
                                           :artifact-hash (:artifact/declared-hash req)
                                           :authority-key-id (:key/id kp)
                                           :authority-assurance :process-isolated
                                           :issued-at "now"})
        signed (sed/sign-envelope decision contract/decision-domain (:private-key kp) (:key/id kp))]
    (contract/build-response req signed)))

(deftest verify-accepts-signed-allowing-decision
  (let [kp (fx/keypair)
        req (contract/build-request {:artifact-id "a" :content {:scenario-id "s"}
                                     :sink :ipfs :policy-hash-str (sentinel/policy-hash)})
        resp (signed-allow-response kp req)
        vr (client/verify-decision resp req (fx/trust-policy kp))]
    (is (true? (:valid? vr)))
    (is (true? (:allowed? vr)))))

(deftest verify-rejects-benign-findings-with-sensitive-artifact
  (testing "an authority that scanned nothing still blocks a sensitive artifact"
    (let [kp (fx/keypair)
          artifact {:sensitivity/findings
                    [{:finding/id "f" :rule/id :secret-scanner/private-key :rule/version "v2"}]}]
      (is (thrown-with-msg? Exception #"verification failed|decision-not-permitting|blocked"
                            (client/request-decision artifact :ipfs (config kp)))))))

(deftest verify-rejects-sink-substitution-after-signing
  (let [kp (fx/keypair)
        req (contract/build-request {:artifact-id "a" :content {:scenario-id "s"}
                                     :sink :ipfs :policy-hash-str (sentinel/policy-hash)})
        resp (signed-allow-response kp req)
        swapped (assoc-in resp [:decision :sentinel/sink] :local)
        vr (client/verify-decision swapped req (fx/trust-policy kp))]
    (is (false? (:valid? vr)))
    (is (= :sink-mismatch (:reason vr)))))

(deftest verify-rejects-decision-flipped-to-allow
  (let [kp (fx/keypair)
        req (contract/build-request {:artifact-id "a" :content {:scenario-id "s"}
                                     :sink :ipfs :policy-hash-str (sentinel/policy-hash)})
        resp (signed-allow-response kp req)
        flipped (assoc-in resp [:decision :sentinel/decision] :block) ; signature no longer matches
        vr (client/verify-decision flipped req (fx/trust-policy kp))]
    (is (false? (:valid? vr)))
    (is (= :signed-hash-mismatch (:reason vr)))))

(deftest verify-rejects-artifact-hash-substitution
  (let [kp (fx/keypair)
        req (contract/build-request {:artifact-id "a" :content {:scenario-id "s"}
                                     :sink :ipfs :policy-hash-str (sentinel/policy-hash)})
        resp (signed-allow-response kp req)
        sub (assoc-in resp [:decision :sentinel/artifact-hash] "sha256:other")
        vr (client/verify-decision sub req (fx/trust-policy kp))]
    (is (false? (:valid? vr)))
    (is (= :artifact-hash-mismatch (:reason vr)))))

(deftest verify-rejects-replay-under-different-policy-hash
  (let [kp (fx/keypair)
        req (contract/build-request {:artifact-id "a" :content {:scenario-id "s"}
                                     :sink :ipfs :policy-hash-str (sentinel/policy-hash)})
        resp (signed-allow-response kp req)
        other-req (assoc req :policy/hash "sha256:different")
        vr (client/verify-decision resp other-req (fx/trust-policy kp))]
    (is (false? (:valid? vr)))
    (is (= :policy-hash-mismatch (:reason vr)))))

(deftest verify-rejects-replay-for-different-artifact
  (let [kp (fx/keypair)
        req (contract/build-request {:artifact-id "a" :content {:scenario-id "s"}
                                     :sink :ipfs :policy-hash-str (sentinel/policy-hash)})
        resp (signed-allow-response kp req)
        other-req (assoc req :artifact/declared-hash "sha256:different")
        vr (client/verify-decision resp other-req (fx/trust-policy kp))]
    (is (false? (:valid? vr)))
    (is (= :artifact-hash-mismatch (:reason vr)))))

(deftest verify-rejects-request-id-mismatch
  (let [kp (fx/keypair)
        req (contract/build-request {:artifact-id "a" :content {:scenario-id "s"}
                                     :sink :ipfs :policy-hash-str (sentinel/policy-hash)})
        resp (signed-allow-response kp req)
        vr (client/verify-decision resp (assoc req :request/id "b") (fx/trust-policy kp))]
    (is (false? (:valid? vr)))
    (is (= :request-id-mismatch (:reason vr)))))

(deftest verify-rejects-untrusted-key
  (let [kp (fx/keypair)
        attacker (fx/keypair :attacker)
        req (contract/build-request {:artifact-id "a" :content {:scenario-id "s"}
                                     :sink :ipfs :policy-hash-str (sentinel/policy-hash)})
        resp (signed-allow-response attacker req)
        vr (client/verify-decision resp req (fx/trust-policy kp))]
    (is (false? (:valid? vr)))
    (is (= :untrusted-key (:reason vr)))))

(deftest verify-rejects-wrong-key-role
  (let [kp (fx/keypair)
        req (contract/build-request {:artifact-id "a" :content {:scenario-id "s"}
                                     :sink :ipfs :policy-hash-str (sentinel/policy-hash)})
        resp (signed-allow-response kp req)
        policy (fx/trust-policy kp :release :active)
        vr (client/verify-decision resp req policy)]
    (is (false? (:valid? vr)))
    (is (= :wrong-key-role (:reason vr)))))

(deftest verify-rejects-inactive-key
  (let [kp (fx/keypair)
        req (contract/build-request {:artifact-id "a" :content {:scenario-id "s"}
                                     :sink :ipfs :policy-hash-str (sentinel/policy-hash)})
        resp (signed-allow-response kp req)
        policy (fx/trust-policy kp :sensitivity-sentinel :revoked)
        vr (client/verify-decision resp req policy)]
    (is (false? (:valid? vr)))
    (is (= :inactive-key (:reason vr)))))

(deftest verify-rejects-override-required-as-approval
  (let [kp (fx/keypair)
        req (contract/build-request {:artifact-id "a" :content {:scenario-id "s"}
                                     :sink :ipfs :policy-hash-str (sentinel/policy-hash)})
        report {:sentinel/version "sensitivity-sentinel.v1"
                :sentinel/decision :allow
                :sentinel/level :sensitivity/private
                :sentinel/structural-level :sensitivity/private
                :sentinel/reasons []
                :sentinel/allowed-sinks []
                :sentinel/redaction-required? true
                :sentinel/override-required? {:required? true :mode :multi-party-approval}}
        decision (contract/build-decision {:request req :report report :sink (:sink req)
                                           :artifact-hash (:artifact/declared-hash req)
                                           :authority-key-id (:key/id kp)
                                           :authority-assurance :process-isolated
                                           :issued-at "now"})
        signed (sed/sign-envelope decision contract/decision-domain (:private-key kp) (:key/id kp))
        resp (contract/build-response req signed)
        vr (client/verify-decision resp req (fx/trust-policy kp))]
    (is (false? (:valid? vr)))
    (is (= :decision-not-permitting (:reason vr)))
    (is (true? (:override-required? vr)))))

(deftest client-rejects-trailing-garbage-on-stdout
  (let [kp (fx/keypair)
        valid (pr-str {:response/kind :sensitivity-sentinel-decision-response
                       :response/version 1 :request/id "x" :request/hash "h" :decision {}})
        runner (fn [_ _ _] {:exit 0 :stdout (str valid "\n:garbage") :stderr ""})]
    (is (thrown-with-msg? Exception #"trailing content"
                          (client/request-decision {:scenario-id "s"} :ipfs
                                                   {:command ["x"] :trust-policy (fx/trust-policy kp)
                                                    :runner runner})))))

(deftest client-rejects-multiple-forms-on-stdout
  (let [kp (fx/keypair)
        form (pr-str {:response/kind :sensitivity-sentinel-decision-response
                      :response/version 1 :request/id "x" :request/hash "h" :decision {}})
        runner (fn [_ _ _] {:exit 0 :stdout (str form form) :stderr ""})]
    (is (thrown? Exception (client/request-decision {:scenario-id "s"} :ipfs
                                                    {:command ["x"] :trust-policy (fx/trust-policy kp)
                                                     :runner runner})))))

(deftest client-fails-closed-on-nonzero-exit
  (let [kp (fx/keypair)
        runner (fn [_ _ _] {:exit 1 :stdout "" :stderr "boom"})]
    (is (thrown-with-msg? Exception #"non-zero"
                          (client/request-decision {:scenario-id "s"} :ipfs
                                                   {:command ["x"] :trust-policy (fx/trust-policy kp)
                                                    :runner runner})))))

(deftest client-fails-closed-on-authority-error-response
  (let [kp (fx/keypair)
        runner (fn [_ _ _] {:exit 0 :stdout (pr-str {:response/kind :sensitivity-sentinel-error
                                                     :response/version 1 :request/id nil
                                                     :error/reason :policy-hash-mismatch :error/detail "x"})
                            :stderr ""})]
    (is (thrown-with-msg? Exception #"^x$"
                          (client/request-decision {:scenario-id "s"} :ipfs
                                                   {:command ["x"] :trust-policy (fx/trust-policy kp)
                                                    :runner runner})))))

(deftest client-fails-closed-without-command
  (is (thrown-with-msg? Exception #"no sentinel authority command"
                        (client/request-decision {:scenario-id "s"} :ipfs
                                                 {:command nil :trust-policy {}
                                                  :runner (fn [_ _ _] {:exit 0 :stdout "" :stderr ""})}))))

(deftest client-propates-timeout-to-runner
  (let [kp (fx/keypair)
        seen (atom nil)
        runner (fn [_ _ t] (reset! seen t) {:exit 0 :stdout "" :stderr ""})]
    (is (thrown? Exception
                 (client/request-decision {:scenario-id "s"} :ipfs
                                          {:command ["x"] :trust-policy (fx/trust-policy kp)
                                           :timeout-ms 1234 :runner runner})))
    (is (= 1234 @seen))))

(deftest out-of-process-gate-requires-remote-for-public-sink
  (testing "a public sink always goes through the out-of-process authority"
    (let [kp (fx/keypair)
          calls (atom 0)
          runner (fn [_ _ _] (swap! calls inc)
                   {:exit 0 :stdout (pr-str {:response/kind :sensitivity-sentinel-error
                                             :response/version 1 :request/id nil
                                             :error/reason :blocked :error/detail "blocked"})
                    :stderr ""})]
      (is (thrown? Exception
                   (client/out-of-process-gate! {:scenario-id "s"} :ipfs
                                                {:command ["x"] :trust-policy (fx/trust-policy kp)
                                                 :runner runner})))
      (is (= 1 @calls) "remote-required sink must consult the out-of-process authority"))))

(deftest out-of-process-gate-uses-in-process-for-safe-sink
  (let [kp (fx/keypair)
        calls (atom 0)
        runner (fn [_ _ _] (swap! calls inc) {:exit 0 :stdout "" :stderr ""})]
    (is (map? (client/out-of-process-gate! {:scenario-id "s"} :local
                                           {:command ["x"] :trust-policy (fx/trust-policy kp)
                                            :runner runner})))
    (is (zero? @calls) "in-process sink must not consult the out-of-process authority")))

(deftest default-command-returns-argv-vector-with-shell-metacharacters
  (testing "even hostile config yields an argv vector, never a shell string"
    (with-redefs [client/env (fn [k] (case k
                                       "PRF_SENTINEL_JAR" "/tmp/a;rm -rf / --x.jar"
                                       "PRF_JAVA" "/weird path/java"
                                       nil))]
      (let [cmd (client/default-command)]
        (is (vector? cmd))
        (is (= ["/weird path/java" "-jar" "/tmp/a;rm -rf / --x.jar" "sentinel" "check"] cmd))
        (is (every? string? cmd))))))

;; ── Real subprocess plumbing (integration, POSIX) ───────────────────────────

(deftest process-runner-captures-stdout-and-exit
  (let [{:keys [exit stdout stderr]} (client/process-runner
                                      ["/bin/sh" "-c" "printf '{:response/kind :ok}'"]
                                      "" 10000)]
    (is (zero? exit))
    (is (= "{:response/kind :ok}" stdout))
    (is (string? stderr))))

(deftest process-runner-times-out-and-reaps
  (testing "a timed-out child is destroyed"
    (let [thrown (try
                   (client/process-runner ["/bin/sh" "-c" "sleep 5"] "" 100)
                   nil
                   (catch Exception e e))]
      (is (some? thrown))
      (is (= :sentinel-timeout (:reason (ex-data thrown)))))))

(deftest process-runner-reports-nonzero-exit
  (let [{:keys [exit]} (client/process-runner ["/bin/sh" "-c" "exit 3"] "" 10000)]
    (is (= 3 exit))))

(deftest gate-forbids-local-authorization-for-add-held-artifacts
  (testing "a force-auth add-held artifact to a safe sink must still go through
            the out-of-process authority — local in-process authorization is
            forbidden for it"
    (let [kp (fx/keypair)
          called (atom 0)
          runner (fn [_argv input _timeout]
                   (swap! called inc)
                   (let [request (edn/read-string input)
                         resp (cmd/decide {:private-key (:private-key kp)
                                           :key-id (:key/id kp)}
                                          request)]
                     {:exit 0 :stdout (pr-str resp) :stderr ""}))
          cfg (assoc (config kp) :runner runner)
          artifact {:artifact/kind :force-auth-add-held
                    :held/action :add-held
                    :sensitivity/level :sensitivity/public}]
      (client/out-of-process-gate! artifact :local cfg)
      (is (pos? @called)
          "the remote authority runner was invoked, proving the remote path,
           not the local sentinel assertion"))))

(deftest gate-uses-local-path-for-ordinary-artifacts-to-safe-sinks
  (let [kp (fx/keypair)
        called (atom 0)
        runner (fn [& _] (swap! called inc) {:exit 0 :stdout "" :stderr ""})
        cfg (assoc (config kp) :runner runner)
        artifact {:artifact/kind :evidence-node :result {:status :pass}}]
    (client/out-of-process-gate! artifact :local cfg)
    (is (zero? @called)
        "an ordinary artifact to a safe sink uses the local assertion, not the
         remote authority")))

(deftest real-authority-allowed-decision-verifies
  (testing "regression: a genuinely allowed decision produced by the real
            authority core verifies — the envelope decision value is normalized
            to :allow/:block even though the report uses :allowed/:blocked"
    (let [kp (fx/keypair)
          artifact {:artifact/kind :evidence-node :result {:status :pass}}
          req (contract/build-request {:artifact-id "a" :content artifact
                                       :sink :local
                                       :declared-level (:sensitivity/level artifact)
                                       :policy-hash-str (sentinel/policy-hash)})
          resp (cmd/decide {:private-key (:private-key kp) :key-id (:key/id kp)} req)
          vr (client/verify-decision resp req (fx/trust-policy kp))]
      (is (= :allow (get-in resp [:decision :sentinel/decision])))
      (is (true? (:valid? vr)))
      (is (true? (:allowed? vr))))))
