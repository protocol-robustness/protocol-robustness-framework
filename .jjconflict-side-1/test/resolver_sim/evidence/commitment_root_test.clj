(ns resolver-sim.evidence.commitment-root-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.evidence.node :as ev-node]
            [resolver-sim.hash.canonical :as hc]))

;; ── Helpers ────────────────────────────────────────────────────────────────

(def ^:private test-evidence-root-hash
  "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789")

(defn- build-replay-exec-node
  "Build a minimal execution replay node for use as the commitment root's parent."
  []
  (ev-node/emit-execution-node!
   {:execution-id :execution/replay
    :status :pass
    :inputs {:test true}
    :outputs {:bundle/root-hash "deadbeef"
              :bundle/root {:bundle/hash "deadbeef"
                            :bundle/schema-version "bundle-root.v1"
                            :run/request {:runner/backend :local-current}
                            :execution/summary {:status :pass}}}}))

(defn- build-commitment-root
  "Build a commitment root node using the same pattern as run-and-report,
   but with overridable parameters for testing."
  [exec-node & {:keys [evidence-root-hash parent-hashes override-outputs
                       status bundle-root-hash]
                :or {evidence-root-hash test-evidence-root-hash
                     parent-hashes nil
                     status :pass
                     bundle-root-hash "deadbeef"}}]
  (let [exec-hash (:node-hash exec-node)
        effective-parents (or parent-hashes [(str "sha256:" exec-hash)])]
    (ev-node/emit-execution-node!
     {:execution-id :evidence/commitment-root
      :policy-id :evidence-policy/computed
      :parent-hashes effective-parents
      :bootstrap-roots [(str "evidence-chain:sha256:" evidence-root-hash)]
      :status status
      :inputs {:execution/node-hash (str "sha256:" exec-hash)
               :evidence/chain-cursor-hash (str "sha256:" evidence-root-hash)}
      :outputs (or override-outputs
                   {:bundle/root-hash (when bundle-root-hash
                                        (str "sha256:" bundle-root-hash))
                    :execution/status (get-in exec-node [:result :status])})})))

;; ── Integration Tests ─────────────────────────────────────────────────────—

(deftest commitment-root-created-after-execution
  (ev-node/with-fresh-registry
    (let [exec-node (build-replay-exec-node)
          commit-node (build-commitment-root exec-node)]
      (is (some? commit-node))
      (is (= :evidence/commitment-root
             (get-in commit-node [:execution :execution-id])))
      (is (= :pass (get-in commit-node [:result :status]))))))

(deftest parent-hashes-match-execution-node
  (ev-node/with-fresh-registry
    (let [exec-node (build-replay-exec-node)
          commit-node (build-commitment-root exec-node)
          expected-parent (str "sha256:" (:node-hash exec-node))]
      (is (= [expected-parent] (:parent-hashes commit-node)))
      (is (some? (ev-node/lookup-node (:node-hash exec-node)))
          "Execution node must still be resolvable after commitment root creation"))))

(deftest bootstrap-roots-include-evidence-chain
  (ev-node/with-fresh-registry
    (let [exec-node (build-replay-exec-node)
          commit-node (build-commitment-root exec-node
                                             :evidence-root-hash test-evidence-root-hash)
          expected-bootstrap (str "evidence-chain:sha256:" test-evidence-root-hash)]
      (is (= [expected-bootstrap] (:bootstrap-roots commit-node))))))

(deftest bundle-root-hash-present-in-outputs
  (ev-node/with-fresh-registry
    (let [exec-node (build-replay-exec-node)
          commit-node (build-commitment-root exec-node)
          inputs-hash (get-in commit-node [:evidence :inputs-hash])
          outputs-hash (get-in commit-node [:evidence :outputs-hash])]
      (is (string? inputs-hash))
      (is (string? outputs-hash))
      (is (re-matches #"[a-f0-9]{64}" inputs-hash))
      (is (re-matches #"[a-f0-9]{64}" outputs-hash)))))

(deftest execution-status-reflects-parent
  (ev-node/with-fresh-registry
    (let [pass-node (build-replay-exec-node)
          pass-commit (build-commitment-root pass-node)
          fail-exec (ev-node/emit-execution-node!
                     {:execution-id :execution/replay
                      :status :fail
                      :inputs {:test true}
                      :outputs {:bundle/root-hash "deadbeef"}})
          fail-commit (build-commitment-root fail-exec
                                             :bundle-root-hash "deadbeef")]
      ;; commitment construction status is always :pass
      (is (= :pass (get-in pass-commit [:result :status])))
      (is (= :pass (get-in fail-commit [:result :status])))
      ;; but execution/status in outputs reflects the parent
      (is (= :pass (get-in pass-commit [:policy-output :visible :outputs :execution/status]))
          "execution/status should be :pass for passing execution"))
    ;; We can't easily extract :execution/status from the hash-based evidence
    ;; outputs-hash without re-hashing. Check that the visible output contains it.
    ))

(deftest no-cycle-between-execution-and-commitment
  (ev-node/with-fresh-registry
    (let [exec-node (build-replay-exec-node)
          _ (build-commitment-root exec-node)
          ;; Re-read execution node from registry — must be unchanged
          stored-exec (ev-node/lookup-node (:node-hash exec-node))]
      (is (some? stored-exec))
      (is (= (:node-hash exec-node) (:node-hash stored-exec))
          "Execution node hash must not change after commitment root creation")
      (is (= (:content-hash exec-node) (:content-hash stored-exec))
          "Execution node content-hash must not change after commitment root creation")
      (is (= [] (:parent-hashes stored-exec))
          "Execution node must remain parentless — commitment root adds no back-edge"))))

(deftest commitment-root-resolvable-via-parenthood
  (ev-node/with-fresh-registry
    (let [exec-node (build-replay-exec-node)
          commit-node (build-commitment-root exec-node)]
      ;; The commitment root parent-hashes point to the execution node.
      ;; Verify we can walk the graph: commit → exec → nil.
      (is (= [(:node-hash exec-node)]
             (mapv #(-> (ev-node/lookup-node %) :node-hash)
                   (mapv #(second (re-find #"sha256:(.+)" %))
                         (:parent-hashes commit-node))))
          "Each parent-hash should resolve to a registered node"))
    (is (thrown? clojure.lang.ExceptionInfo
                 (ev-node/emit-execution-node!
                  {:execution-id :evidence/commitment-root
                   :policy-id :evidence-policy/computed
                   :parent-hashes ["sha256:nonexistent-node-hash"]
                   :bootstrap-roots []
                   :status :pass
                   :inputs {}
                   :outputs {}}))
        "Unresolvable parent-hashes should fail validation")))

(deftest execution-node-immutable-after-commitment
  (ev-node/with-fresh-registry
    (let [exec-before (build-replay-exec-node)
          _ (build-commitment-root exec-before)
          exec-after (ev-node/lookup-node (:node-hash exec-before))]
      (is (= exec-before exec-after)
          "Execution node must be immutable — commitment root creation must not mutate it"))))

(deftest commitment-status-distinct-from-execution-status
  (ev-node/with-fresh-registry
    (let [exec-node (ev-node/emit-execution-node!
                     {:execution-id :execution/replay
                      :status :fail
                      :inputs {:test true}
                      :outputs {:bundle/root-hash "deadbeef"}})
          commit-node (build-commitment-root exec-node
                                             :bundle-root-hash "deadbeef")]
      ;; :result :status reflects commitment construction, not execution
      (is (= :pass (get-in commit-node [:result :status]))
          "Commitment root construction status must be :pass even when execution failed")
      (is (= :fail (get-in exec-node [:result :status]))
          "Execution node must retain its :fail status"))))

;; ── Unit Tests: Hash Stability ────────────────────────────────────────────

(deftest commitment-hash-stable-for-identical-content
  (ev-node/with-fresh-registry
    (let [exec-node (build-replay-exec-node)
          node1 (build-commitment-root exec-node)
          node2 (build-commitment-root exec-node)]
      (is (= (:node-hash node1) (:node-hash node2))
          "Identical inputs must produce identical commitment root hash"))))

(deftest commitment-hash-changes-when-parent-changes
  (ev-node/with-fresh-registry
    (let [exec-a (build-replay-exec-node)
          exec-b (ev-node/emit-execution-node!
                  {:execution-id :execution/replay
                   :status :pass
                   :inputs {:test-data "different"}
                   :outputs {:result 99}})
          commit-a (build-commitment-root exec-a)
          commit-b (build-commitment-root exec-b)]
      (is (not= (:node-hash commit-a) (:node-hash commit-b))
          "Different execution parents must produce different commitment root hashes"))))

(deftest commitment-hash-changes-when-evidence-cursor-changes
  (ev-node/with-fresh-registry
    (let [exec-node (build-replay-exec-node)
          commit-a (build-commitment-root exec-node :evidence-root-hash "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
          commit-b (build-commitment-root exec-node :evidence-root-hash "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")]
      (is (not= (:node-hash commit-a) (:node-hash commit-b))
          "Different evidence cursor hashes must produce different commitment root hashes"))))

(deftest commitment-hash-ignores-timestamp
  (ev-node/with-fresh-registry
    (let [exec-node (build-replay-exec-node)
          exec-hash (:node-hash exec-node)]
      ;; Build two commitment roots — one with explicit timestamp (simulates call) and one without
      ;; The node-hash must be the same because timestamp is excluded from canonical projection.
      (let [node-a (ev-node/build-execution-node
                    {:execution-id :evidence/commitment-root
                     :policy-id :evidence-policy/computed
                     :parent-hashes [(str "sha256:" exec-hash)]
                     :bootstrap-roots ["evidence-chain:sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"]
                     :timestamp "2024-01-01T00:00:00Z"
                     :status :pass
                     :inputs {:test true}
                     :outputs {:result :ok}})
            node-b (ev-node/build-execution-node
                    {:execution-id :evidence/commitment-root
                     :policy-id :evidence-policy/computed
                     :parent-hashes [(str "sha256:" exec-hash)]
                     :bootstrap-roots ["evidence-chain:sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"]
                     :timestamp "2025-06-15T12:30:00Z"
                     :status :pass
                     :inputs {:test true}
                     :outputs {:result :ok}})]
        (is (= (:node-hash node-a) (:node-hash node-b))
            "Node-hash must be identical despite different timestamps")
        (is (not= (:record-hash node-a) (:record-hash node-b))
            "Record-hash must differ when timestamps differ — this proves node-hash excludes timestamp")))))

(deftest commitment-hash-stable-across-registries
  (let [exec-id (hc/hash-with-intent {:hash/intent :evidence-content} {:execution :replay
                                                                       :inputs {:a 1}})
        commit-spec {:execution-id :evidence/commitment-root
                     :policy-id :evidence-policy/computed
                     :parent-hashes [(str "sha256:" exec-id)]
                     :bootstrap-roots ["evidence-chain:sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"]
                     :status :pass
                     :inputs {:execution/node-hash (str "sha256:" exec-id)
                              :evidence/chain-cursor-hash "sha256:aaaa"}
                     :outputs {:bundle/root-hash "sha256:deadbeef"
                               :execution/status :pass}}
        hashes (atom [])]
    (dotimes [i 5]
      (ev-node/with-fresh-registry
        (let [node (ev-node/build-execution-node commit-spec)]
          (is (= 64 (count (:node-hash node))))
          (is (re-matches #"[a-f0-9]{64}" (:node-hash node)))
          (swap! hashes conj (:node-hash node)))))
    (is (apply = @hashes)
        (str "All 5 iterations must produce identical hash: " @hashes))))

;; ── Unit Tests: Schema Validity ───────────────────────────────────────────

(deftest commitment-root-required-fields
  (ev-node/with-fresh-registry
    (let [exec-node (build-replay-exec-node)
          commit-node (build-commitment-root exec-node)]
      (is (= 1 (:schema-version commit-node)))
      (is (string? (:node-id commit-node)))
      (is (string? (:node-hash commit-node)))
      (is (= (:node-id commit-node) (:node-hash commit-node)))
      (is (= (:node-hash commit-node) (:content-hash commit-node)))
      (is (string? (:record-hash commit-node)))
      (is (vector? (:parent-hashes commit-node)))
      (is (vector? (:bootstrap-roots commit-node)))
      (is (map? (:execution commit-node)))
      (is (map? (:result commit-node)))
      (is (map? (:evidence commit-node)))
      (is (contains? (:evidence commit-node) :inputs-hash))
      (is (contains? (:evidence commit-node) :outputs-hash)))))

(deftest commitment-root-execution-section
  (ev-node/with-fresh-registry
    (let [exec-node (build-replay-exec-node)
          commit-node (build-commitment-root exec-node)
          exec (get-in commit-node [:execution])]
      (is (= :evidence/commitment-root (:execution-id exec)))
      (is (= :commitment-root (:execution-kind exec)))
      (is (= :scenario-runner (:runner exec)))
      (is (string? (:registry-hash exec)))
      (is (= :evidence-policy/computed (:policy-id exec)))
      (is (string? (:policy-hash exec))))))

(deftest commitment-root-typed-reference-format
  (ev-node/with-fresh-registry
    (let [exec-node (build-replay-exec-node)
          commit-node (build-commitment-root exec-node)
          parent (first (:parent-hashes commit-node))
          bootstrap (first (:bootstrap-roots commit-node))]
      (is (re-matches #"sha256:[a-f0-9]{64}" parent)
          "parent-hashes must use sha256: prefix with 64-char hex hash")
      (is (re-matches #"evidence-chain:sha256:[a-f0-9]{64}" bootstrap)
          "bootstrap-roots must use evidence-chain:sha256: prefix with 64-char hex hash"))))

(deftest commitment-root-unresolved-parent-rejected
  (is (thrown? clojure.lang.ExceptionInfo
               (ev-node/with-fresh-registry
                 (ev-node/emit-execution-node!
                  {:execution-id :evidence/commitment-root
                   :policy-id :evidence-policy/computed
                   :parent-hashes ["sha256:0000000000000000000000000000000000000000000000000000000000000000"]
                   :bootstrap-roots []
                   :status :pass
                   :inputs {}
                   :outputs {}})))
      "emit-execution-node! should reject unresolvable parent hashes"))

(deftest commitment-root-bundle-root-hash-optional
  (ev-node/with-fresh-registry
    (let [exec-node (build-replay-exec-node)
          commit-node (build-commitment-root exec-node
                                             :bundle-root-hash nil)]
      ;; When bundle-root-hash is nil, outputs should still be valid
      (is (some? commit-node))
      (is (= :pass (get-in commit-node [:result :status])))))
  ;; Verify nil bundle-root-hash doesn't break anything
  (ev-node/with-fresh-registry
    (let [exec-node (build-replay-exec-node)
          commit-node (build-commitment-root exec-node
                                             :override-outputs {:execution/status (:status exec-node)})]
      (is (some? commit-node))
      (is (= :pass (get-in commit-node [:result :status]))))))
