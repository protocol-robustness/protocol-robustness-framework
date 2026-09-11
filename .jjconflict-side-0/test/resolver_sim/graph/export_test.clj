(ns resolver-sim.graph.export-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.graph.export :as gex]
            [clojure.string :as str]
            [clojure.java.io :as io]))

(def sample-graph-projection
  {:task {:task/hash "sha256:task0001abcd" :task/ref "task-ref-1" :title "Test task"}
   :nodes [{:node/id "sha256:task0001abcd" :node/label "Research Task"
            :node/data {:task/ref "task-ref-1" :title "Test task"}}
           {:node/id "sha256:exec0002efgh" :node/label "Execution Evidence"
            :node/data {:execution-id :execution/replay}}
           {:node/id "sha256:attest003ijkl" :node/label "Attestation: :reproduced"
            :node/data {:attestation/predicate :reproduced}}
           {:node/id "sha256:mailbox004mnop" :node/label "Mailbox: :RUNNER_RESULT"
            :node/data {:message/type :RUNNER_RESULT}}]
   :edges [{:edge/from "sha256:task0001abcd" :edge/to "sha256:exec0002efgh" :edge/label "produced"}
           {:edge/from "sha256:attest003ijkl" :edge/to "sha256:task0001abcd" :edge/label "attests"}
           {:edge/from "sha256:mailbox004mnop" :edge/to "sha256:task0001abcd" :edge/label "messages"}]
   :summary {:node-count 4 :edge-count 3 :task-status :executed}})

(def sample-metadata
  {:task/ref "task-ref-1"
   :title "Test evidence graph"})

(deftest build-graph-evidence-artifact-test
  (testing "builds artifact with correct schema and type"
    (let [artifact (gex/build-graph-evidence-artifact sample-graph-projection sample-metadata)]
      (is (= :artifact/evidence-graph (:artifact/type artifact)))
      (is (= "evidence-graph.v1" (:graph/schema artifact)))
      (is (= "task-ref-1" (:graph/task-ref artifact)))
      (is (= "Test evidence graph" (:graph/title artifact)))))
  (testing "includes all nodes and edges"
    (let [artifact (gex/build-graph-evidence-artifact sample-graph-projection)]
      (is (= 4 (count (:graph/nodes artifact))))
      (is (= 3 (count (:graph/edges artifact))))))
  (testing "computes deterministic root hash"
    (let [a1 (gex/build-graph-evidence-artifact sample-graph-projection)
          a2 (gex/build-graph-evidence-artifact sample-graph-projection)]
      (is (= (:artifact/hash a1) (:artifact/hash a2)))))
  (testing "handles empty graph"
    (let [artifact (gex/build-graph-evidence-artifact {:nodes [] :edges [] :summary {}})]
      (is (string? (:artifact/hash artifact)))
      (is (empty? (:graph/nodes artifact)))
      (is (empty? (:graph/edges artifact)))))
  (testing "artifact hash changes when data changes"
    (let [a1 (gex/build-graph-evidence-artifact sample-graph-projection)
          modified (update sample-graph-projection :nodes #(conj % {:node/id "sha256:new005" :node/label "New Node"}))
          a2 (gex/build-graph-evidence-artifact modified)]
      (is (not= (:artifact/hash a1) (:artifact/hash a2))))))

(deftest graph->svg-test
  (testing "produces valid SVG XML"
    (let [artifact (gex/build-graph-evidence-artifact sample-graph-projection)
          svg (gex/graph->svg artifact)]
      (is (str/starts-with? svg "<?xml"))
      (is (str/includes? svg "<svg"))
      (is (str/includes? svg "</svg>"))))
  (testing "includes node and edge data"
    (let [artifact (gex/build-graph-evidence-artifact sample-graph-projection)
          svg (gex/graph->svg artifact)]
      (is (str/includes? svg "Research Task"))
      (is (str/includes? svg "Execution Evidence"))
      (is (str/includes? svg "produced"))))
  (testing "includes layer legend"
    (let [artifact (gex/build-graph-evidence-artifact sample-graph-projection)
          svg (gex/graph->svg artifact)]
      (is (str/includes? svg "Layer legend"))
      (is (str/includes? svg "Task"))
      (is (str/includes? svg "Execution / Attestation"))))
  (testing "handles empty graph"
    (let [svg (gex/graph->svg {:graph/nodes [] :graph/edges []})]
      (is (str/includes? svg "<svg"))
      (is (str/includes? svg "</svg>"))))
  (testing "works with graph-projection format (no :graph/ prefix)"
    (let [svg (gex/graph->svg sample-graph-projection)]
      (is (str/includes? svg "<svg")))))

(deftest graph->d3-data-test
  (testing "produces D3-compatible format"
    (let [artifact (gex/build-graph-evidence-artifact sample-graph-projection)
          d3 (gex/graph->d3-data artifact)]
      (is (contains? d3 :nodes))
      (is (contains? d3 :links))
      (is (= 4 (count (:nodes d3))))
      (is (= 3 (count (:links d3))))))
  (testing "each node has required D3 fields"
    (let [artifact (gex/build-graph-evidence-artifact sample-graph-projection)
          d3 (gex/graph->d3-data artifact)]
      (doseq [n (:nodes d3)]
        (is (contains? n :id))
        (is (contains? n :label))
        (is (contains? n :group))
        (is (contains? n :fx))
        (is (contains? n :fy)))))
  (testing "each link has source/target/label"
    (let [artifact (gex/build-graph-evidence-artifact sample-graph-projection)
          d3 (gex/graph->d3-data artifact)]
      (doseq [l (:links d3)]
        (is (contains? l :source))
        (is (contains? l :target))
        (is (contains? l :label)))))
  (testing "works with graph-projection format (no :graph/ prefix)"
    (let [d3 (gex/graph->d3-data sample-graph-projection)]
      (is (= 4 (count (:nodes d3)))))))

(deftest write-graph-artifacts-test
  (testing "writes all files to output directory"
    (let [tmp-dir (str (System/getProperty "java.io.tmpdir") "/gex-test-" (java.util.UUID/randomUUID))
          result (gex/write-graph-artifacts! sample-graph-projection sample-metadata tmp-dir)]
      (is (contains? result :svg-path))
      (is (contains? result :d3-path))
      (is (contains? result :artifact-path))
      (is (.exists (io/file (:svg-path result))))
      (is (.exists (io/file (:d3-path result))))
      (is (.exists (io/file (:artifact-path result))))
      ;; Clean up
      (doseq [f [(io/file (:svg-path result))
                 (io/file (:d3-path result))
                 (io/file (:artifact-path result))]]
        (.delete f))
      (.delete (io/file tmp-dir)))))

(deftest validate-graph-artifact-test
  (testing "valid artifact passes validation"
    (let [artifact (gex/build-graph-evidence-artifact sample-graph-projection)
          result (gex/validate-graph-artifact artifact)]
      (is (:valid? result))
      (is (empty? (:errors result)))))
  (testing "incorrect schema version fails"
    (let [artifact (-> (gex/build-graph-evidence-artifact sample-graph-projection)
                       (assoc :graph/schema "wrong-schema"))
          result (gex/validate-graph-artifact artifact)]
      (is (not (:valid? result)))))
  (testing "tampered hash fails"
    (let [artifact (-> (gex/build-graph-evidence-artifact sample-graph-projection)
                       (assoc :artifact/hash "0000000000000000000000000000000000000000000000000000000000000000"))
          result (gex/validate-graph-artifact artifact)]
      (is (not (:valid? result)))))
  (testing "empty graph fails"
    (let [artifact (gex/build-graph-evidence-artifact {:nodes [] :edges [] :summary {}})
          result (gex/validate-graph-artifact artifact)]
      (is (not (:valid? result))))))

(deftest helper-functions-test
  (testing "node-layer assigns correct layers"
    (is (= 0 (gex/node-layer "Research Task")))
    (is (= 1 (gex/node-layer "Execution Evidence")))
    (is (= 1 (gex/node-layer "Attestation: :reproduced")))
    (is (= 2 (gex/node-layer "Mailbox: :RUNNER_RESULT")))
    (is (= 2 (gex/node-layer "Finding: :anomaly"))))
  (testing "layer-color returns valid colors"
    (doseq [layer [0 1 2 3]]
      (is (str/starts-with? (gex/layer-color layer) "#"))))
  (testing "truncate-label handles short and long labels"
    (is (= "short" (gex/truncate-label "short" 10)))
    (is (str/includes? (gex/truncate-label "a very long label that should be truncated" 20) "…")))
  (testing "shorten-id returns first 12 chars"
    (is (= 12 (count (gex/shorten-id "sha256:abcdef1234567890abcdef1234567890"))))
    (is (= "sha256:abcde" (gex/shorten-id "sha256:abcde")))))
