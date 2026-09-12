(ns resolver-sim.evidence.attestation-dag-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.evidence.attestation :as att]
            [resolver-sim.evidence.attestation-dag :as adag]
            [resolver-sim.evidence.node :as node]
            [resolver-sim.hash.canonical :as hc]))

(defn- attestor [] {:type :ci-runner :id :ci-validation})
(defn- subject [] {:type :evidence-node :hash "sha256:abc"})

(defn- build-a
  [& {:keys [signed-at claim claim-id]
      :or {signed-at "2025-01-01T00:00:00Z" claim :verified}}]
  (att/build-attestation (attestor) (subject) claim
                         (cond-> {:signed-at signed-at}
                           claim-id (assoc :claim-id claim-id))))

;; ── build-attestation-dag-node ───────────────────────────────────────────────

(deftest dag-node-has-required-top-level-fields
  (let [node (adag/build-attestation-dag-node (build-a))]
    (is (some? (:schema-version node)))
    (is (some? (:node-id node)))
    (is (some? (:node-hash node)))
    (is (vector? (:parent-hashes node)))
    (is (map? (:execution node)))
    (is (map? (:result node)))
    (is (map? (:evidence node)))))

(deftest dag-node-execution-is-attestation
  (let [node (adag/build-attestation-dag-node (build-a))]
    (is (= :execution/attestation (get-in node [:execution :execution-id])))
    (is (= :attestation (get-in node [:execution :execution-kind])))
    (is (= :attestation-emitter (get-in node [:execution :runner])))))

(deftest dag-node-result-status-is-pass
  (let [node (adag/build-attestation-dag-node (build-a))]
    (is (= :pass (get-in node [:result :status])))))

(deftest dag-node-captures-attestation-id
  (let [a (build-a)
        node (adag/build-attestation-dag-node a)]
    (is (= (str "attestation:sha256:" (:attestation/id a))
           (first (:attestations node))))
    (is (string? (get-in node [:evidence :inputs-hash])))
    (is (= 64 (count (get-in node [:evidence :inputs-hash]))))))

(deftest dag-node-inputs-are-hashed
  (let [a (build-a :claim :approved :claim-id :claim/consistency)
        node (adag/build-attestation-dag-node a)]
    (is (string? (get-in node [:evidence :inputs-hash])))
    (is (= 64 (count (get-in node [:evidence :inputs-hash]))))
    (is (string? (get-in node [:evidence :outputs-hash])))))

(deftest dag-node-hash-is-valid
  (let [a (build-a)
        node (adag/build-attestation-dag-node a)
        recomputed (hc/hash-with-intent {:hash/intent :evidence-node} (dissoc node :node-id :node-hash))]
    (is (= recomputed (:node-hash node)))))

(deftest dag-node-deterministic
  (let [a (build-a)
        n1 (adag/build-attestation-dag-node a)
        n2 (adag/build-attestation-dag-node a)]
    (is (= (:node-hash n1) (:node-hash n2)))))

(deftest dag-node-parent-hashes
  (let [a1 (build-a :signed-at "2025-01-01T00:00:00Z")
        a2 (build-a :signed-at "2025-01-02T00:00:00Z")
        n1 (adag/build-attestation-dag-node a1)
        n2 (adag/build-attestation-dag-node a2 {:parent-hashes [(:node-hash n1)]})]
    (is (= [] (:parent-hashes n1)))
    (is (= [(:node-hash n1)] (:parent-hashes n2)))))

(deftest dag-node-validates-through-node-registry
  (node/with-fresh-registry
    (let [a (build-a)
          node (adag/build-attestation-dag-node a)
          validation (node/validate-node node)]
      (is (:valid? validation)))))

;; ── emit-attestation-dag-node! ───────────────────────────────────────────────

(deftest dag-node-has-attestation-runner
  (let [node (adag/build-attestation-dag-node (build-a))]
    (is (= :attestation-emitter (get-in node [:execution :runner])))))

(deftest dag-node-rejects-nil-attestation
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"attestation is nil"
                        (adag/build-attestation-dag-node nil))))

(deftest dag-node-rejects-malformed-attestation
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"failed shape validation"
                        (adag/build-attestation-dag-node {:some :map
                                                          :without :required
                                                          :attestation/fields nil}))))

;; ── chain-attestation-dag-nodes ──────────────────────────────────────────────

(deftest chain-links-consecutive-nodes
  (let [a1 (build-a :signed-at "2025-01-01T00:00:00Z")
        a2 (build-a :signed-at "2025-01-02T00:00:00Z")
        a3 (build-a :signed-at "2025-01-03T00:00:00Z")
        nodes (adag/chain-attestation-dag-nodes [a1 a2 a3])]
    (is (= 3 (count nodes)))
    (is (= [] (:parent-hashes (nth nodes 0))))
    (is (= [(:node-hash (nth nodes 0))] (:parent-hashes (nth nodes 1))))
    (is (= [(:node-hash (nth nodes 1))] (:parent-hashes (nth nodes 2))))))

(deftest chain-empty-returns-empty
  (is (= [] (adag/chain-attestation-dag-nodes []))))

(deftest chain-single-returns-one-node
  (let [nodes (adag/chain-attestation-dag-nodes [(build-a)])]
    (is (= 1 (count nodes)))
    (is (= [] (:parent-hashes (first nodes))))))

(deftest chain-preserves-external-parent-hashes
  (let [external "sha256:abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
        a1 (build-a :signed-at "2025-01-01T00:00:00Z")
        a2 (build-a :signed-at "2025-01-02T00:00:00Z")
        nodes (adag/chain-attestation-dag-nodes [a1 a2]
                                                {:parent-hashes [external]})]
    (is (= 2 (count nodes)))
    (is (= [external] (:parent-hashes (nth nodes 0)))
        "First node must preserve external parent from opts")
    (is (= [external (:node-hash (nth nodes 0))] (:parent-hashes (nth nodes 1)))
        "Second node must have external parent + chain link")))
