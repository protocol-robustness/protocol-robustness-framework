(ns resolver-sim.evidence.attestation-node-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.evidence.attestation :as att]
            [resolver-sim.evidence.attestation-node :as an]
            [resolver-sim.evidence.node :as node]
            [resolver-sim.hash.canonical :as hc]))

(defn- attestor [] {:type :ci-runner :id :ci-validation})
(defn- subject [] {:type :evidence-node :hash "sha256:abc"})

(defn- build-attestation
  [& {:keys [signed-at claim-id signing-key-id provenance metadata claim]
      :or {claim :verified}}]
  (att/build-attestation (attestor) (subject) claim
                         (cond-> {:signed-at (or signed-at "2025-01-01T00:00:00Z")}
                           claim-id (assoc :claim-id claim-id)
                           signing-key-id (assoc :signing-key-id signing-key-id)
                           signing-key-id (assoc :signing-fn (fn [_]
                                                               {:algorithm :ed25519
                                                                :public-key-id signing-key-id
                                                                :signature-bytes "deadbeef"}))
                           provenance (assoc :provenance provenance)
                           metadata (assoc :metadata metadata))))

;; ── build-attestation-node ───────────────────────────────────────────────────

(deftest build-node-produces-required-fields
  (let [a (build-attestation)
        node (an/build-attestation-node a)]
    (is (some? (:schema-version node)))
    (is (some? (:node-id node)))
    (is (some? (:node-hash node)))
    (is (vector? (:parent-hashes node)))
    (is (map? (:execution node)))
    (is (map? (:result node)))
    (is (map? (:evidence node)))))

(deftest build-node-is-attestation-execution
  (let [node (an/build-attestation-node (build-attestation))]
    (is (= :execution/attestation (get-in node [:execution :execution-id])))
    (is (= :attestation (get-in node [:execution :execution-kind])))
    (is (= :attestation-emitter (get-in node [:execution :runner])))))

(deftest build-node-references-attestation
  (let [a (build-attestation)
        node (an/build-attestation-node a)]
    (is (= (str "attestation:sha256:" (:attestation/id a))
           (first (:attestations node))))))

(deftest build-node-has-hashed-inputs
  (let [node (an/build-attestation-node (build-attestation))]
    (is (string? (get-in node [:evidence :inputs-hash])))
    (is (= 64 (count (get-in node [:evidence :inputs-hash]))))
    (is (string? (get-in node [:evidence :outputs-hash])))))

(deftest build-node-hash-is-content-addressed
  (let [node (an/build-attestation-node (build-attestation))]
    (is (string? (:node-hash node)))
    (is (= 64 (count (:node-hash node)))
        "sha256 hex strings are 64 characters")))

(deftest build-node-validates-through-node-registry
  (node/with-fresh-registry
    (let [node (an/build-attestation-node (build-attestation))]
      (is (:valid? (node/validate-node node))))))

;; ── Determinism / content-addressing ─────────────────────────────────────────

(deftest build-node-deterministic-hash
  (let [a1 (build-attestation :claim-id :claim/consistency :signing-key-id "key-001")
        a2 (build-attestation :claim-id :claim/consistency :signing-key-id "key-001")
        n1 (an/build-attestation-node a1)
        n2 (an/build-attestation-node a2)]
    (is (= (:node-hash n1) (:node-hash n2)))))

(deftest build-node-different-claims-different-hash
  (testing "different claim results produce different node hashes"
    (let [n1 (an/build-attestation-node (build-attestation :claim :verified))
          n2 (an/build-attestation-node (build-attestation :claim :approved))]
      (is (not= (:node-hash n1) (:node-hash n2))
          "different attestations produce different node hashes"))))

(deftest build-node-different-subjects-different-hash
  (testing "different subject hashes produce different node hashes"
    (let [subject-a {:type :evidence-node :hash "sha256:aaa"}
          subject-b {:type :evidence-node :hash "sha256:bbb"}
          a1 (att/build-attestation (attestor) subject-a :verified
                                    {:signed-at "2025-01-01T00:00:00Z"})
          a2 (att/build-attestation (attestor) subject-b :verified
                                    {:signed-at "2025-01-01T00:00:00Z"})
          n1 (an/build-attestation-node a1)
          n2 (an/build-attestation-node a2)]
      (is (not= (:node-hash n1) (:node-hash n2))
          "different subjects produce different node hashes"))))

(deftest build-node-metadata-excluded-from-hash
  (testing "metadata is excluded from the attestation hash by design"
    (let [a1 (build-attestation :metadata {:env "test"})
          a2 (build-attestation :metadata {:env "prod"})
          n1 (an/build-attestation-node a1)
          n2 (an/build-attestation-node a2)]
      ;; Metadata is excluded from attestation body before hashing, so attestation IDs match
      (is (= (:attestation/id a1) (:attestation/id a2))
          "metadata must not change attestation hash")
      ;; Same attestation ID -> same node hash
      (is (= (:node-hash n1) (:node-hash n2))
          "same attestation -> same node hash"))))

;; ── Hash verification ───────────────────────────────────────────────────────

(deftest build-node-hash-matches-recomputed
  (let [node (an/build-attestation-node (build-attestation))
        recomputed (hc/hash-with-intent {:hash/intent :evidence-node}
                                        (dissoc node :node-id :node-hash))]
    (is (= recomputed (:node-hash node)))))

;; ── Full pipeline ────────────────────────────────────────────────────────────

(deftest emit-node-returns-persist-result
  (node/with-fresh-registry
    (let [result (an/emit-attestation-node! (build-attestation))]
      (is (map? result))
      (is (some? (get-in result [:node :node-hash])))
      (is (map? (:artifact-entry result)))
      (is (some? (:path result)))
      (is (some? (node/lookup-node (:node-hash (:node result))))))))
