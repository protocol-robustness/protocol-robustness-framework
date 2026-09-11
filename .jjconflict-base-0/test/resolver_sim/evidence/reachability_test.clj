(ns resolver-sim.evidence.reachability-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.evidence.reachability :as r]))

;; ── Evidence chain reachability ──────────────────────────────────────────────

(def sample-chain
  {:chain/reachable-hashes ["a" "b" "c" "d" "e"]})

(deftest chain-reachable-same-hash
  (testing "same hash is trivially reachable"
    (is (true? (r/chain-reachable? "c" sample-chain)))
    (is (true? (r/chain-reachable? "a" sample-chain)))))

(deftest chain-reachable-nil-inputs
  (testing "nil hash returns false"
    (is (false? (r/chain-reachable? nil sample-chain)))
    (is (false? (r/chain-reachable? "x" sample-chain)))
    (is (false? (r/chain-reachable? "a" nil)))))

(deftest chain-reachable-empty-chain
  (testing "empty chain returns false unless allow-empty? is set"
    (is (false? (r/chain-reachable? "a" [])))
    (is (false? (r/chain-reachable? "a" #{})))
    (is (true? (r/chain-reachable? "a" nil {:allow-empty? true})))))

(deftest chain-reachable-plain-vector
  (testing "plain vector is accepted as reachable hashes"
    (let [chain ["h1" "h2" "h3"]]
      (is (true? (r/chain-reachable? "h2" chain)))
      (is (false? (r/chain-reachable? "missing" chain))))))

(deftest chain-reachable-plain-set
  (testing "plain set is accepted as reachable hashes"
    (let [chain #{"h1" "h2"}]
      (is (true? (r/chain-reachable? "h1" chain)))
      (is (false? (r/chain-reachable? "missing" chain))))))

(deftest chain-ancestors-between
  (testing "chain-ancestors returns the subrange between two hashes"
    (let [ancestors (r/chain-ancestors sample-chain "b" "d")]
      (is (= #{"b" "c" "d"} ancestors))))

  (testing "returns nil when from is not before to"
    (is (nil? (r/chain-ancestors sample-chain "d" "b"))))

  (testing "returns nil for unknown hashes"
    (is (nil? (r/chain-ancestors sample-chain "x" "y")))))

;; ── DAG reachability ─────────────────────────────────────────────────────────

(def sample-dag
  {:dag/nodes [{:node/id :a} {:node/id :b} {:node/id :c}
               {:node/id :d} {:node/id :e} {:node/id :f}]
   :dag/edges [{:edge/from :a :edge/to :b}
               {:edge/from :b :edge/to :c}
               {:edge/from :a :edge/to :d}
               {:edge/from :d :edge/to :e}
               {:edge/from :c :edge/to :f}]})

(deftest dag-reachable-same-node
  (testing "same declared node is trivially reachable"
    (is (true? (r/dag-reachable? sample-dag :a :a)))
    (is (true? (r/dag-reachable? sample-dag :f :f))))
  (testing "equal undeclared IDs in a map-form DAG are not reachable"
    (is (false? (r/dag-reachable? sample-dag :undeclared :undeclared)))))

(deftest dag-reachable-nil-inputs
  (testing "nil inputs return false"
    (is (false? (r/dag-reachable? sample-dag nil :a)))
    (is (false? (r/dag-reachable? sample-dag :a nil)))))

(deftest dag-reachable-direct-edge
  (testing "direct edge short-circuits"
    (is (true? (r/dag-reachable? sample-dag :a :b)))
    (is (true? (r/dag-reachable? sample-dag :d :e)))))

(deftest dag-reachable-multi-hop
  (testing "multi-hop path is detected"
    (is (true? (r/dag-reachable? sample-dag :a :f)))
    (is (true? (r/dag-reachable? sample-dag :a :e)))
    (is (true? (r/dag-reachable? sample-dag :b :f)))))

(deftest dag-reachable-not-reachable
  (testing "unreachable node returns false"
    (is (false? (r/dag-reachable? sample-dag :f :a)))
    (is (false? (r/dag-reachable? sample-dag :e :b)))
    (is (false? (r/dag-reachable? sample-dag :c :a)))))

(deftest dag-reachability-ignores-ghost-endpoint-injection
  (let [mutated {:dag/nodes [{:node/id :a} {:node/id :f}]
                 :dag/edges [{:edge/from :a :edge/to :ghost}
                             {:edge/from :ghost :edge/to :f}]}]
    (testing "undeclared endpoints remain structural errors"
      (is (some #{:dag/edge-endpoint-undeclared}
                (r/dag-structural-errors mutated))))
    (testing "ghost edges cannot create a path through a declared DAG"
      (is (false? (r/dag-reachable? mutated :a :ghost)))
      (is (false? (r/dag-reachable? mutated :ghost :f)))
      (is (false? (r/dag-reachable? mutated :a :f))))))

(deftest raw-edge-vector-reachability-is-unvalidated-utility
  (testing "raw edge vectors retain traversal behavior without node declaration"
    (is (true? (r/dag-reachable? [{:edge/from :a :edge/to :ghost}
                                  {:edge/from :ghost :edge/to :f}]
                                 :a
                                 :f)))))

(deftest dag-ancestors
  (testing "dag-ancestors returns nodes that can reach from-id (inclusive)"
    (is (= #{:a :b :c :f} (r/dag-ancestors sample-dag :f)))
    (is (= #{:a :b :c} (r/dag-ancestors sample-dag :c)))
    (is (= #{:a :d :e} (r/dag-ancestors sample-dag :e)))))

(deftest dag-descendants
  (testing "dag-descendants returns nodes reachable from from-id"
    (is (= #{:a :b :c :d :e :f} (r/dag-descendants sample-dag :a)))
    (is (= #{:b :c :f} (r/dag-descendants sample-dag :b)))
    (is (= #{:d :e} (r/dag-descendants sample-dag :d)))))

(deftest dag-shortest-path
  (testing "shortest path between two nodes"
    (is (= [:a :b :c :f] (r/dag-shortest-path sample-dag :a :f)))
    (is (= [:a :d :e] (r/dag-shortest-path sample-dag :a :e)))))

(deftest dag-shortest-path-unreachable
  (testing "nil when no path exists"
    (is (nil? (r/dag-shortest-path sample-dag :f :a)))))

;; ── Unified reachable? dispatch ─────────────────────────────────────────────

(deftest unified-chain-dispatch
  (testing "reachable? auto-dispatches chain"
    (is (true? (r/reachable? sample-chain "c")))
    (is (false? (r/reachable? sample-chain "x")))))

(deftest unified-dag-dispatch
  (testing "reachable? auto-dispatches DAG"
    (is (true? (r/reachable? sample-dag :a :f)))
    (is (false? (r/reachable? sample-dag :f :a)))))

(deftest unified-plain-vector
  (testing "reachable? treats plain vector as chain"
    (is (true? (r/reachable? ["h1" "h2" "h3"] "h2")))
    (is (false? (r/reachable? ["a" "b"] "missing")))))

;; ── Reporting ────────────────────────────────────────────────────────────────

(deftest report-chain
  (testing "reachability-report for chains"
    (let [report (r/reachability-report sample-chain)]
      (is (= :chain (:type report)))
      (is (= 5 (:hash-count report)))
      (is (false? (:ordered? report)))
      (is (nil? (:head-hash report)))
      (is (nil? (:tail-hash report))))))

(deftest report-chain-with-gaps
  (testing "reachability-report detects gaps"
    (let [report (r/reachability-report sample-chain :check-set ["a" "b" "c" "missing"])]
      (is (true? (:gaps? report)))
      (is (= ["missing"] (:chain-gaps report))))))

(deftest report-dag
  (testing "reachability-report for DAGs"
    (let [report (r/reachability-report sample-dag)]
      (is (= :dag (:type report)))
      (is (= 6 (:node-count report)))
      (is (= 5 (:edge-count report)))
      (is (not (contains? report :disconnected)))
      (is (true? (:weakly-connected? report))))))

(deftest report-reports-dag-structural-invalidity
  (let [malformed {:dag/nodes [{:node/id :a} {:node/id :a}]
                   :dag/edges [{:edge/from :a :edge/to :missing}
                               {:edge/from :a :edge/to :a}]}
        report (r/reachability-report malformed)]
    (is (false? (:valid? report)))
    (is (some #{:dag/duplicate-node-id} (:errors report)))
    (is (some #{:dag/edge-endpoint-undeclared} (:errors report)))
    (is (some #{:dag/self-loop} (:errors report)))))

(deftest report-chain-set-is-explicitly-unordered
  (let [report (r/reachability-report #{"b" "a"})]
    (is (true? (:valid? report)))
    (is (false? (:ordered? report)))
    (is (nil? (:head-hash report)))
    (is (nil? (:tail-hash report)))))

(deftest report-dag-connectivity-does-not-depend-on-node-sort-order
  (let [root-after-child {:dag/nodes [{:node/id :a} {:node/id :z}]
                          :dag/edges [{:edge/from :z :edge/to :a}]}
        multiple-roots {:dag/nodes [{:node/id :a} {:node/id :b} {:node/id :z}]
                        :dag/edges [{:edge/from :z :edge/to :a}]}
        root-after-child-report (r/reachability-report root-after-child)
        multiple-roots-report (r/reachability-report multiple-roots)]
    (testing "a root that sorts after its child still covers its child"
      (is (= [:z] (:roots root-after-child-report)))
      (is (empty? (:unreachable-from-roots root-after-child-report)))
      (is (true? (:weakly-connected? root-after-child-report))))
    (testing "multiple roots are reported without selecting an arbitrary root"
      (is (= [:b :z] (:roots multiple-roots-report)))
      (is (empty? (:unreachable-from-roots multiple-roots-report)))
      (is (false? (:weakly-connected? multiple-roots-report)))
      (is (not (contains? multiple-roots-report :disconnected))))))

;; ── Edge cases ───────────────────────────────────────────────────────────────

(deftest empty-dag
  (testing "empty DAG"
    (let [dag {:dag/nodes [] :dag/edges []}]
      (is (false? (r/dag-reachable? dag :a :b)))
      (is (= #{:a} (r/dag-descendants dag :a))))))

(deftest self-loop-in-dag
  (testing "only declared DAG nodes are self-reachable"
    (is (true? (r/dag-reachable? sample-dag :a :a)))
    (is (false? (r/dag-reachable? sample-dag :nonexistent :nonexistent)))))
