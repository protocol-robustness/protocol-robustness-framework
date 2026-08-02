(ns resolver-sim.run.bundle-root-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.run.bundle-root :as br]))

(def sample-request
  "A representative :scenario-run/request map."
  {:runner/backend :local-current
   :runner-selection {:mode :pinned
                      :runner-id :runner/local-bb}
   :suite/key :sew-invariants
   :protocol/default-id "sew-v1"
   :evidence/profile :standard
   :output/profile :full
   :entries []})

(def sample-result
  "A representative :scenario-run/result map."
  {:status :pass
   :suite/key :sew-invariants
   :totals {:passed 3 :failed 0 :total 3}
   :results [{:scenario-id "S01" :pass? true :outcome :pass
              :checks [] :violations {} :dispatcher-id :protocol/sew-v1
              :expected-fail? false :scenario-path nil}
             {:scenario-id "S02" :pass? true :outcome :pass
              :checks [] :violations {} :dispatcher-id :protocol/sew-v1
              :expected-fail? false :scenario-path nil}
             {:scenario-id "S03" :pass? false :outcome :fail
              :checks [] :violations {} :dispatcher-id :protocol/sew-v1
              :expected-fail? true :scenario-path nil}]
   :diagnostics {:elapsed-ms 150 :suite-id :sew-invariants}})

(deftest build-bundle-root-has-required-top-level-keys
  (let [bundle (br/build-bundle-root sample-request sample-result)]
    (is (= "bundle-root.v2" (:bundle/schema-version bundle)))
    (is (some? (:bundle/id bundle)))
    (is (some? (:bundle/hash bundle)))
    (is (= (:bundle/id bundle) (:bundle/hash bundle)))
    (is (map? (:run/request bundle)))
    (is (map? (:registry/snapshot bundle)))
    (is (map? (:run/environment bundle)))
    (is (map? (:execution/summary bundle)))
    (is (string? (:overview/hash bundle)))
    (is (map? (:overview bundle)))))

(deftest build-bundle-root-run-request-keys
  (let [bundle (br/build-bundle-root sample-request sample-result)
        req (:run/request bundle)]
    (is (= :pinned (get-in req [:runner-selection :mode])))
    (is (= :runner/local-bb (get-in req [:runner-selection :runner-id])))
    (is (= :sew-invariants (:suite/key req)))
    (is (= "sew-v1" (:protocol/default-id req)))
    (is (= :orchestrator/run-and-report-v1 (:orchestrator/id req)))
    (is (= :orchestrator/run-and-report-v1 (:orchestrator/id bundle)))))

(deftest build-bundle-root-execution-summary
  (let [bundle (br/build-bundle-root sample-request sample-result)]
    (is (= {:passed 3 :failed 0 :total 3} (:totals (:execution/summary bundle))))
    (is (= :pass (:status (:execution/summary bundle))))))

(deftest build-bundle-root-registry-snapshot-contains-expected-keys
  (let [snap (:registry/snapshot (br/build-bundle-root sample-request sample-result))]
    (is (string? (:attestor-registry-hash snap)))
    (is (string? (:scenario-suite-hash snap)))
    (is (string? (:dispatcher-registry-hash snap)))
    (is (string? (:evidence-policy-hash snap)))
    (is (string? (:execution-registry-hash snap)))
    (is (string? (:claim-definition-registry-hash snap)))))

(deftest build-bundle-root-overview-hash-is-stable
  (let [b1 (br/build-bundle-root sample-request sample-result)
        b2 (br/build-bundle-root sample-request sample-result)]
    (is (= (:overview/hash b1) (:overview/hash b2)))
    (is (= (:bundle/hash b1) (:bundle/hash b2)))))

(deftest build-bundle-root-overview-hash-changes-when-results-differ
  (let [different-result (assoc-in sample-result [:results 0 :pass?] false)
        b1 (br/build-bundle-root sample-request sample-result)
        b2 (br/build-bundle-root sample-request different-result)]
    (is (not= (:overview/hash b1) (:overview/hash b2)))
    (is (not= (:bundle/hash b1) (:bundle/hash b2)))))

(def sample-result-with-protocol-state
  "Like sample-result but includes force-authorisation protocol state."
  (assoc sample-result
         :protocol/force-authorisations
         {"fa-0-release-abc"
          {:authorization/id "fa-0-release-abc"
           :authorization/type :force-authorisation
           :authorization/status :consumed
           :workflow-id 0
           :allowed-action "execute-resolution"
           :consumed? true
           :starts-at 1000
           :expires-at nil
           :created-at 1000
           :created-by "0xGov"
           :reason :resolver-overcapacity}
          "fa-0-refund-def"
          {:authorization/id "fa-0-refund-def"
           :authorization/type :force-authorisation
           :authorization/status :revoked
           :workflow-id 0
           :allowed-action "execute-resolution"
           :consumed? false
           :starts-at 1000
           :expires-at nil
           :created-at 1000
           :created-by "0xGov"
           :reason :resolver-overcapacity}}
         :protocol/force-authorisations-consumed
         {"fa-0-release-abc"
          {:consumed? true
           :authorization/id "fa-0-release-abc"
           :authorization/type :force-authorisation
           :held/adjustment-id "held-0"
           :token "USDC"
           :amount 5000}}))

(deftest build-bundle-root-omits-protocol-state-hashes-when-not-present
  (let [bundle (br/build-bundle-root sample-request sample-result)]
    (is (nil? (:protocol/state-hashes bundle))
        "protocol/state-hashes should be absent when no protocol state provided")))

(deftest build-bundle-root-includes-protocol-state-hashes
  (let [bundle (br/build-bundle-root sample-request sample-result-with-protocol-state)
        ph (:protocol/state-hashes bundle)]
    (is (map? ph) "protocol/state-hashes should be a map")
    (is (string? (:force-authorisations/hash ph))
        "force-authorisations/hash should be a string")
    (is (string? (:force-authorisations/consumed-hash ph))
        "force-authorisations/consumed-hash should be a string")))

(deftest build-bundle-root-protocol-state-hashes-are-stable
  (let [b1 (br/build-bundle-root sample-request sample-result-with-protocol-state)
        b2 (br/build-bundle-root sample-request sample-result-with-protocol-state)
        h1 (:protocol/state-hashes b1)
        h2 (:protocol/state-hashes b2)]
    (is (= h1 h2) "protocol/state-hashes should be identical across calls")
    (is (= (:bundle/hash b1) (:bundle/hash b2))
        "bundle/hash should be stable when protocol state is identical")))

(deftest build-bundle-root-protocol-state-hashes-change-when-state-differs
  (let [diff-state (assoc-in sample-result-with-protocol-state
                             [:protocol/force-authorisations "fa-0-release-abc" :reason]
                             :circuit-breaker-active)
        b1 (br/build-bundle-root sample-request sample-result-with-protocol-state)
        b2 (br/build-bundle-root sample-request diff-state)]
    (is (not= (:protocol/state-hashes b1) (:protocol/state-hashes b2))
        "protocol/state-hashes should differ when force-auth state differs")
    (is (not= (:bundle/hash b1) (:bundle/hash b2))
        "bundle/hash should differ when protocol state differs")))

(deftest bundle-root-is-runnable
  (let [bundle (br/build-bundle-root sample-request sample-result)
        check (br/runnable? bundle)]
    (is (= (:bundle/hash bundle) (br/compute-json-hash bundle)))
    (is (:runnable? check))))

(deftest bundle-root-v1-is-identified-as-legacy
  (let [v2 (br/build-bundle-root sample-request sample-result)
        v1-base (assoc v2 :bundle/schema-version "bundle-root.v1")
        v1-hash (br/compute-v1-hash v1-base)
        legacy (assoc v1-base :bundle/id v1-hash :bundle/hash v1-hash)
        check (br/runnable? legacy)]
    (is (= :legacy-not-content-verified (:status check)))
    (is (not (:runnable? check)))
    (is (some #(= :legacy-not-content-verified (:code %)) (:errors check)))))

(deftest bundle-root-runnable-rejects-content-tampering
  (let [bundle (br/build-bundle-root sample-request sample-result)
        tampered (assoc-in bundle [:execution/summary :status] :fail)
        check (br/runnable? tampered)]
    (is (not (:runnable? check)))
    (is (some #(= :bundle-content-hash-mismatch (:code %)) (:errors check)))))

(deftest bundle-root-runnable-rejects-tampered-run-request
  (let [bundle (br/build-bundle-root sample-request sample-result)
        tampered (assoc-in bundle [:run/request :suite/key] :different-suite)
        check (br/runnable? tampered)]
    (is (not (:runnable? check)))
    (is (some #(= :bundle-content-hash-mismatch (:code %)) (:errors check)))))

(deftest bundle-root-runnable-fails-without-run-request
  (let [bundle (dissoc (br/build-bundle-root sample-request sample-result) :run/request)
        check (br/runnable? bundle)]
    (is (not (:runnable? check)))
    (is (some #(= :missing-run-request (:code %)) (:errors check)))))
