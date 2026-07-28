(ns resolver-sim.evidence.qol-test
  "Tests for researcher-quality-of-life additions: Query API, mechanism index,
   coverage report, and static coverage verification.

   These tests model researcher questions, not just implementation details:
   - \"Find all evidence for workflow 42\"
   - \"Which mechanisms have evidence artifacts?\"
   - \"Does this run have missing coverage?\"
   - \"Did we forget to add evidence to a new function?\""
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.evidence.adapters.example :as example-adapter]
            [resolver-sim.evidence.diff :as diff]
            [resolver-sim.evidence.forensic-adapter :as forensic]
            [resolver-sim.evidence.registry-validation :as reg-val]
            [resolver-sim.io.event-evidence :as evidence]
            [resolver-sim.io.scenarios :as io-sc]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.sim.fixtures :as fix]
            [resolver-sim.scripts.evidence-coverage :as coverage]))

;; ── Test Helpers ──────────────────────────────────────────────────────────────
;;
;; Each test uses a fresh temp directory to avoid cross-test contamination.
;; setup-artifacts! writes sample evidence files into the given dir.

(defn- write-sample-evidence
  "Write a sample evidence artifact to disk for query/index testing.
   Uses the same qualified-key writer as capture-event-evidence!."
  [dir m]
  (let [f (io/file dir (str (:evidence/type m) "-" (:event/seq m 0) ".json"))]
    (.mkdirs (io/file dir))
    (spit f (json/write-str m {:key-fn evidence/qualified-key :indent true}))
    (.getPath f)))

(defn- setup-artifacts!
  "Write a known set of sample evidence artifacts into dir."
  [dir]
  (let [base {:evidence/schema-version "event-evidence.v1"
              :run/id "qol-test"
              :scenario/id "qol-scenario"}
        escrow-created (assoc base :evidence/type "escrow-created"
                              :evidence/chain-seq 1
                              :evidence/group-id "qol-test:0:create_escrow"
                              :evidence/layer :targeted-protocol
                              :evidence/hash "abc111"
                              :escrow/workflow-id 0
                              :escrow/amount 1000
                              :event/seq 0)
        escrow-released (assoc base :evidence/type "escrow-released"
                               :evidence/chain-seq 2
                               :evidence/group-id "qol-test:1:release"
                               :evidence/layer :targeted-protocol
                               :evidence/hash "abc222"
                               :finalize/workflow-id 0
                               :event/seq 1)
        stake-registered (assoc base :evidence/type "stake-registered"
                                :evidence/chain-seq 3
                                :evidence/group-id "qol-test:2:stake/register"
                                :evidence/layer :targeted-protocol
                                :evidence/hash "abc333"
                                :stake/resolver "0xRes1"
                                :stake/amount 5000
                                :event/seq 2)
        dispute-raised (assoc base :evidence/type "dispute-raised"
                              :evidence/chain-seq 4
                              :evidence/group-id "qol-test:3:raise_dispute"
                              :evidence/layer :targeted-protocol
                              :evidence/hash "abc444"
                              :dispute/workflow-id 0
                              :event/seq 3)
        generic-trace (assoc base :evidence/type "transition"
                             :artifact-kind :transition
                             :evidence/hash "gen001"
                             :evidence/group-id "qol-test:0:create_escrow"
                             :evidence/layer :generic-trace
                             :event/seq 0)]
    (dorun (map #(write-sample-evidence dir %)
                [escrow-created escrow-released stake-registered
                 dispute-raised generic-trace]))))

;; ── Researcher Query: \"Find all evidence for workflow 0\" ─────────────────

(deftest find-evidence-by-workflow
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-test-" (java.util.UUID/randomUUID))
        _ (setup-artifacts! dir)
        results (evidence/find-evidence :by-workflow 0 :artifact-dir dir)]
    (is (= 3 (count results)) "Three artifacts reference workflow 0")
    (is (some #(= "escrow-created" (:evidence/type %)) results))
    (is (some #(= "dispute-raised" (:evidence/type %)) results))))

(deftest find-evidence-by-type
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-test-" (java.util.UUID/randomUUID))
        _ (setup-artifacts! dir)
        results (evidence/find-evidence :by-type :escrow-created :artifact-dir dir)]
    (is (= 1 (count results)))
    (is (= "escrow-created" (:evidence/type (first results))))
    (is (:evidence/chain-seq (first results)) "Summary includes chain-seq")
    (is (nil? (:escrow/amount (first results))) "Summary excludes body by default")))

(deftest find-evidence-by-type-include-body
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-test-" (java.util.UUID/randomUUID))
        _ (setup-artifacts! dir)
        results (evidence/find-evidence :by-type :escrow-created :include-body? true
                                        :artifact-dir dir)]
    (is (= 1 (count results)))
    (is (= 1000 (:escrow/amount (first results))) "include-body? reveals full fields")))

(deftest find-evidence-by-mechanism
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-test-" (java.util.UUID/randomUUID))
        _ (setup-artifacts! dir)
        results (evidence/find-evidence :by-mechanism :escrow-lifecycle :artifact-dir dir)]
    (is (= 2 (count results)) "Two artifacts in escrow-lifecycle")
    (is (some #(= "escrow-created" (:evidence/type %)) results))
    (is (some #(= "escrow-released" (:evidence/type %)) results))))

(deftest find-evidence-by-chain-seq-range
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-test-" (java.util.UUID/randomUUID))
        _ (setup-artifacts! dir)
        results (evidence/find-evidence :by-chain-seq {:from 1 :to 2} :artifact-dir dir)
        chain-seqs (set (map :evidence/chain-seq results))]
    (is (= 2 (count results)) "Two artifacts in chain-seq 1-2")
    (is (chain-seqs 1) "Chain-seq 1 is included")
    (is (chain-seqs 2) "Chain-seq 2 is included")))

(deftest find-evidence-by-group-id
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-test-" (java.util.UUID/randomUUID))
        _ (setup-artifacts! dir)
        results (evidence/find-evidence :by-group-id "qol-test:0:create_escrow" :artifact-dir dir)]
    (is (= 2 (count results)) "Two artifacts share this group-id")
    (is (some #(= "generic-trace" (:evidence/layer %)) results))
    (is (some #(= "targeted-protocol" (:evidence/layer %)) results))))

(deftest find-evidence-by-run-id
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-test-" (java.util.UUID/randomUUID))
        _ (setup-artifacts! dir)
        results (evidence/find-evidence :by-run-id "qol-test" :artifact-dir dir)]
    (is (seq results))))

(deftest find-evidence-no-results
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-test-" (java.util.UUID/randomUUID))
        _ (setup-artifacts! dir)
        results (evidence/find-evidence :by-type :nonexistent-type :artifact-dir dir)]
    (is (empty? results))))

(deftest find-evidence-missing-directory
  (let [results (evidence/find-evidence :artifact-dir "/nonexistent-path-qol-test")]
    (is (empty? results))))

(deftest find-evidence-and-combined-filters
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-test-" (java.util.UUID/randomUUID))
        _ (setup-artifacts! dir)
        results (evidence/find-evidence :by-type :dispute-raised :by-workflow 0 :artifact-dir dir)]
    (is (= 1 (count results)) "AND-combined filters narrow results")))

;; ── Researcher Query: Mechanism Index ─────────────────────────────────

(deftest mechanism-index-groups-correctly
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-test-" (java.util.UUID/randomUUID))
        _ (setup-artifacts! dir)
        idx (evidence/build-mechanism-index dir)]
    (is (contains? idx :escrow-lifecycle))
    (is (contains? idx :staking))
    (is (contains? idx :dispute-resolution))
    (is (= 2 (:count (get idx :escrow-lifecycle))) "Two escrow lifecycle artifacts")))

(deftest mechanism-index-summaries
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-test-" (java.util.UUID/randomUUID))
        _ (setup-artifacts! dir)
        idx (evidence/build-mechanism-index dir)]
    (is (seq (:artifacts (get idx :staking)))
        "Staking mechanism has artifact summaries")
    (is (:evidence/chain-seq (first (:artifacts (get idx :staking))))
        "Summary has chain-seq")
    (is (:path (first (:artifacts (get idx :staking))))
        "Summary has path")
    (is (:evidence/hash (first (:artifacts (get idx :staking))))
        "Summary has hash")))

;; ── Researcher Query: Coverage Report ──────────────────────────────────

(deftest coverage-report-basic-structure
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-test-" (java.util.UUID/randomUUID))
        ev-dir (str dir "/event-evidence")
        _ (setup-artifacts! ev-dir)
        report (evidence/build-evidence-coverage-report dir)]
    (is (contains? report :generic-trace-event-count))
    (is (contains? report :targeted-evidence-count))
    (is (contains? report :mechanism-counts))
    (is (contains? report :type-counts))
    (is (vector? (:warnings report)))))

(deftest coverage-report-empty-directory
  (let [report (evidence/build-evidence-coverage-report
                (str (System/getProperty "java.io.tmpdir") "/qol-empty-" (java.util.UUID/randomUUID)))]
    (is (zero? (:generic-trace-event-count report)))
    (is (zero? (:targeted-evidence-count report)))))

;; ── Static Coverage: \"Did we forget to add evidence to a new function?\" ──

(deftest static-coverage-finds-missing-evidence
  (let [report (coverage/check-evidence-coverage
                "protocols_src/resolver_sim/protocols/sew/resolution.clj"
                :allowed-missing #{'rotate-dispute-resolver
                                   'cleanup-orphaned-slashes
                                   'update-unavailability
                                   'withdraw-stake})]
    (is (number? (:total-fns report)))
    (is (number? (:functions-with-evidence report)))
    ;; Report should include warnings for read-only/helper fns
    (is (vector? (:warnings report)))
    ;; CI-failures should be fns that modify state without evidence
    (is (every? #(not (re-find #"\?$" (:name %))) (:ci-failures report))
        "Predicate/query functions should be warnings, not CI failures")))

(deftest static-coverage-allows-missing
  (let [report (coverage/check-evidence-coverage
                "protocols_src/resolver_sim/protocols/sew/registry.clj"
                :allowed-missing #{'get-stake 'get-resolver-yield-profile
                                   'resolver-in-escrow?})]
    (is (number? (:total-fns report)))
    (is (nil? (some #(re-find #"\?$" (:name %)) (:ci-failures report)))
        "Predicate functions are warnings, not CI failures")))

(deftest static-coverage-directory-scan
  (let [reports (coverage/check-directory-coverage
                 "protocols_src/resolver_sim/protocols/sew/")]
    (is (seq reports))
    (is (some #(.endsWith (:file %) "resolution.clj") reports))
    (is (some #(.endsWith (:file %) "registry.clj") reports))
    (is (some #(.endsWith (:file %) "lifecycle.clj") reports))))

;; ── Write Verification ──────────────────────────────────────────────────

(deftest write-mechanism-index-creates-file
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-test-" (java.util.UUID/randomUUID))
        ev-dir (str dir "/event-evidence")
        _ (setup-artifacts! ev-dir)
        path (evidence/write-mechanism-index! dir)]
    (is (string? path))
    (is (.exists (io/file path)))
    (let [idx (json/read-str (slurp path) :key-fn keyword)]
      (is (seq idx)))))

;; ── Cross-Layer Linking: \"What happened in this replay event?\" ──────────

(defn- setup-linked-group
  "Write artifacts for linked-evidence-group tests into dir.
   Returns the group-id the artifacts share."
  [dir]
  (.mkdirs (java.io.File. dir))
  (let [gid "test:0:create_escrow"
        base {:evidence/schema-version "event-evidence.v1"}
        generic (assoc base :evidence/type "transition"
                       :evidence/chain-seq nil
                       :evidence/group-id gid
                       :evidence/layer "generic-trace"
                       :evidence/hash "gen001"
                       :evidence/importance :trace
                       :scenario/id "scenario-1"
                       :run/id "run-1"
                       :trial/id "trial-1"
                       :event/seq 0
                       :action {:type :create_escrow})
        escrow (assoc base :evidence/type "escrow-created"
                      :evidence/chain-seq 1
                      :evidence/chain-prev-hash "gen001"
                      :evidence/chain-self-hash "abc111"
                      :evidence/group-id gid
                      :evidence/layer "targeted-protocol"
                      :evidence/importance :core
                      :evidence/mechanism :escrow-lifecycle
                      :evidence/hash "abc111"
                      :scenario/id "scenario-1"
                      :run/id "run-1"
                      :trial/id "trial-1"
                      :event/seq 0
                      :subject/type :escrow
                      :subject/id 0
                      :action/type :escrow/create
                      :evidence/reason :escrow-created
                      :escrow/workflow-id 0)
        stake (assoc base :evidence/type "stake-registered"
                     :evidence/chain-seq 3
                     :evidence/chain-prev-hash "abc222"
                     :evidence/chain-self-hash "abc333"
                     :evidence/group-id gid
                     :evidence/layer "targeted-protocol"
                     :evidence/mechanism :staking
                     :evidence/hash "abc333"
                     :stake/resolver "0xRes")
        dispute (assoc base :evidence/type "dispute-raised"
                       :evidence/chain-seq 2
                       :evidence/chain-prev-hash "abc111"
                       :evidence/chain-self-hash "abc222"
                       :evidence/group-id gid
                       :evidence/layer "targeted-protocol"
                       :evidence/mechanism :dispute-resolution
                       :evidence/hash "abc222"
                       :dispute/workflow-id 0)]
    (doseq [m [generic escrow dispute stake]]
      (spit (io/file dir (str "ev-" (:evidence/hash m) ".json"))
            (json/write-str m {:key-fn evidence/qualified-key :indent true})))
    gid))

(deftest linked-group-returns-nil-for-missing-group
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-link-" (java.util.UUID/randomUUID))
        result (evidence/linked-evidence-group "nonexistent-group" dir)]
    (is (nil? result) "Missing group-id returns nil")))

(deftest linked-group-returns-generic-and-targeted
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-link-" (java.util.UUID/randomUUID))
        gid (setup-linked-group dir)
        result (evidence/linked-evidence-group gid dir)]
    (is (some? result) "Result is not nil")
    (is (= gid (:group-id result)) "Group-id matches")
    (is (some? (:generic-trace result)) "Generic trace is present")
    (is (= "transition" (:evidence/type (:generic-trace result))) "Generic trace type is transition")
    (is (= 3 (:targeted-count (:diagnostics result)))
        "Three targeted artifacts found")
    (is (= 4 (:artifact-count (:diagnostics result)))
        "Four total artifacts found")))

(deftest linked-group-sorts-targeted-by-chain-seq
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-link-" (java.util.UUID/randomUUID))
        gid (setup-linked-group dir)
        result (evidence/linked-evidence-group gid dir)
        chain-seqs (map :evidence/chain-seq (:targeted result))]
    (is (= [1 2 3] chain-seqs) "Targeted artifacts sorted by chain-seq")))

(deftest linked-group-tolerates-malformed-files
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-link-" (java.util.UUID/randomUUID))
        gid (setup-linked-group dir)]
    ;; Write a malformed JSON file into the directory
    (spit (io/file dir "corrupt.json") "{not valid json}")
    (let [result (evidence/linked-evidence-group gid dir)]
      (is (some? result) "Malformed files do not prevent valid results")
      (is (= 4 (:artifact-count (:diagnostics result)))
          "Malformed file is silently skipped"))))

(deftest linked-group-accepts-keyword-layer
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-link-" (java.util.UUID/randomUUID))
        gid "test:keyword-layer"
        base {:evidence/schema-version "event-evidence.v1"}
        generic (assoc base :evidence/type "transition"
                       :evidence/group-id gid
                       :evidence/layer :generic-trace
                       :evidence/hash "gen001")
        escrow (assoc base :evidence/type "escrow-created"
                      :evidence/chain-seq 1
                      :evidence/group-id gid
                      :evidence/layer :targeted-protocol
                      :evidence/hash "abc111")]
    (.mkdirs (java.io.File. dir))
    (doseq [m [generic escrow]]
      (spit (io/file dir (str "ev-" (:evidence/hash m) ".json"))
            (json/write-str m {:key-fn evidence/qualified-key :indent true})))
    (let [result (evidence/linked-evidence-group gid dir)]
      (is (some? result) "Keyword :evidence/layer is accepted")
      (is (:generic-trace-found? (:diagnostics result)) "Generic trace found with keyword layer"))))

(deftest linked-group-targeted-only-no-generic
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-link-" (java.util.UUID/randomUUID))
        gid "test:targeted-only"
        base {:evidence/schema-version "event-evidence.v1"}
        escrow (assoc base :evidence/type "escrow-created"
                      :evidence/chain-seq 1
                      :evidence/group-id gid
                      :evidence/layer "targeted-protocol"
                      :evidence/hash "abc111")]
    (.mkdirs (java.io.File. dir))
    (spit (io/file dir "ev-abc111.json")
          (json/write-str escrow {:key-fn evidence/qualified-key :indent true}))
    (let [result (evidence/linked-evidence-group gid dir)]
      (is (some? result) "Result is not nil")
      (is (nil? (:generic-trace result)) "No generic trace when none exists")
      (is (false? (:generic-trace-found? (:diagnostics result))) "diagnostics reflects missing trace")
      (is (= 1 (:targeted-count (:diagnostics result))) "One targeted artifact"))))

(deftest linked-group-empty-directory
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-link-" (java.util.UUID/randomUUID))]
    (.mkdirs (java.io.File. dir))
    (let [result (evidence/linked-evidence-group "any-id" dir)]
      (is (nil? result) "Empty directory returns nil"))))

;; ── Evidence Links Index: persistable cross-layer index for VCS tracking ──

(deftest write-links-index-creates-file
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-lidx-" (java.util.UUID/randomUUID))
        _ (setup-linked-group dir)
        path (evidence/write-evidence-links-index! dir)]
    (is (string? path))
    (is (.exists (io/file path)))
    (let [idx (json/read-str (slurp path) :key-fn keyword)]
      (is (seq idx) "Links index has entries")
      (is (some #(-> % val :artifacts seq) idx) "Entries have artifact lists"))))

(deftest links-index-groups-by-group-id
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-lidx-" (java.util.UUID/randomUUID))
        _ (setup-linked-group dir)
        idx (evidence/build-evidence-links-index dir)]
    (is (= 1 (count idx)) "All artifacts share one group-id")
    (let [gid (first (keys idx))
          group (get idx gid)]
      (is (= 4 (count (:artifacts group))) "Group has 4 artifacts")
      (is (some #(= "generic-trace" (:layer %)) (:artifacts group)) "Has generic-trace")
      (is (some #(= "targeted-protocol" (:layer %)) (:artifacts group)) "Has targeted-protocol"))))

(deftest links-index-v1-reports-read-errors
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-lidx-" (java.util.UUID/randomUUID))
        _ (setup-linked-group dir)
        _ (spit (io/file dir "corrupt.json") "{not valid json}")
        idx (evidence/build-evidence-links-index-v1 dir)]
    (is (true? (:degraded idx)))
    (is (= 1 (:read-errors idx)))
    (is (= ["corrupt.json"] (:read-error-paths idx)))
    (is (= 1 (:group-count idx)))
    (is (= 4 (:artifact-count idx)))))

(deftest links-index-v1-includes-envelope-and-group-diagnostics
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-lidx-" (java.util.UUID/randomUUID))
        gid (setup-linked-group dir)
        idx (evidence/build-evidence-links-index-v1 dir)
        group (get-in idx [:groups gid])
        diagnostics (:diagnostics group)]
    (is (= "evidence-links-index.v1" (:schema-version idx)))
    (is (= dir (:artifact-root idx)))
    (is (= 1 (:group-count idx)))
    (is (= 4 (:artifact-count idx)))
    (is (= :linked (:status diagnostics)))
    (is (= 1 (:generic-trace-count diagnostics)))
    (is (= 3 (:targeted-count diagnostics)))
    (is (true? (:has-generic-trace? diagnostics)))
    (is (true? (:has-targeted-evidence? diagnostics)))
    (is (true? (:hashes-present? diagnostics)))
    (is (true? (:chain-fields-present? diagnostics)))))

(deftest links-index-v1-enriches-artifact-fields
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-lidx-" (java.util.UUID/randomUUID))
        gid (setup-linked-group dir)
        idx (evidence/build-evidence-links-index-v1 dir)
        artifacts (get-in idx [:groups gid :artifacts])
        escrow (some #(when (= "escrow-created" (:evidence/type %)) %) artifacts)]
    (is (= "ev-abc111.json" (:relative-path escrow)))
    (is (= "abc111" (:hash escrow)))
    (is (= "targeted-protocol" (:evidence/layer escrow)))
    (is (= "core" (:evidence/importance escrow)))
    (is (= "escrow-lifecycle" (:evidence/mechanism escrow)))
    (is (= 1 (:evidence/chain-seq escrow)))
    (is (= "gen001" (:evidence/chain-prev-hash escrow)))
    (is (= "abc111" (:evidence/chain-self-hash escrow)))
    (is (= "scenario-1" (:scenario/id escrow)))
    (is (= "run-1" (:run/id escrow)))
    (is (= "trial-1" (:trial/id escrow)))
    (is (= 0 (:event/seq escrow)))
    (is (= "escrow" (:subject/type escrow)))
    (is (= 0 (:subject/id escrow)))
    (is (= "create" (:action/type escrow)))
    (is (= "escrow-created" (:evidence/reason escrow)))))

(deftest write-links-index-v1-creates-envelope-file
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-lidx-" (java.util.UUID/randomUUID))
        _ (setup-linked-group dir)
        path (evidence/write-evidence-links-index-v1! dir)]
    (is (.exists (io/file path)))
    (let [idx (json/read-str (slurp path) :key-fn keyword)]
      (is (= "evidence-links-index.v1" (:schema-version idx)))
      (is (map? (:groups idx))))))

(deftest forensic-adapter-output-is-written-and-registered
  (let [root (str (System/getProperty "java.io.tmpdir") "/qol-forensic-" (java.util.UUID/randomUUID))
        input (io/file root "input.json")]
    (.mkdirs (io/file root))
    (spit input (json/write-str {:evidence/hash "input-hash"}))
    (chain/with-fresh-registry
      (let [result (forensic/write-forensic-adapter-output!
                    {:adapter-id :test/example
                     :adapter-version "0.1.0"
                     :adapter-kind :derived-index
                     :artifact-root root
                     :output-path "forensics/example.json"
                     :input-artifacts [{:relative-path "input.json" :hash "input-hash"}]
                     :output {:finding/count 1}})
            written (json/read-str (slurp (:path result)) :key-fn keyword)
            status (chain/registry-status)]
        (is (.exists (io/file (:path result))))
        (is (= "forensic-adapter-output.v1" (:schema-version written)))
        (is (= "input-hash" (get-in written [:input-artifacts 0 :hash])))
        (is (string? (:output-hash written)))
        (is (= 1 (:evidence-count status)))
        (is (= "derived-diagnostic-artifact" (get-in result [:artifact-entry :kind])))))))

(deftest example-forensic-adapter-writes-mechanism-report
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-adapter-" (java.util.UUID/randomUUID))
        _ (setup-linked-group dir)]
    (chain/with-fresh-registry
      (let [result (example-adapter/write-example-mechanism-report!
                    :mechanism :escrow-lifecycle
                    :artifact-root dir
                    :event-evidence-dir dir
                    :output-path "forensics/escrow-report.json")
            written (json/read-str (slurp (:path result)) :key-fn keyword)]
        (is (.exists (io/file (:path result))))
        (is (= "escrow-lifecycle" (get-in written [:output :mechanism])))
        (is (= 1 (get-in written [:output :selected-count])))
        (is (= "abc111" (get-in written [:input-artifacts 0 :hash])))))))

(deftest write-coverage-report-creates-file
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-test-" (java.util.UUID/randomUUID))
        ev-dir (str dir "/event-evidence")
        _ (setup-artifacts! ev-dir)
        path (evidence/write-evidence-coverage-report! dir)]
    (is (string? path))
    (is (.exists (io/file path)))
    (let [report (json/read-str (slurp path) :key-fn keyword)]
      (is (contains? report :targeted-evidence-count)))))

;; ── Chain Integrity: \"Can I verify the evidence chain from artifacts alone?\" ─

(defn- compute-content-hash
  "Compute the content hash the way finalize-evidence does:
   dissoc chain fields, canonicalize, hash with :evidence-content intent."
  [m]
  (let [content (dissoc m :evidence/hash :evidence/chain-self-hash :evidence/chain-prev-hash)]
    (hc/hash-with-intent {:hash/intent :evidence-content} content)))

(defn- setup-chain-artifacts
  "Write a valid 3-artifact chain into dir."
  [dir]
  (.mkdirs (java.io.File. dir))
  (let [base {:evidence/schema-version "event-evidence.v1"}
        a1 (assoc base :evidence/type "escrow-created"
                  :evidence/group-id "g1")
        a2 (assoc base :evidence/type "escrow-released"
                  :evidence/group-id "g1")
        a3 (assoc base :evidence/type "dispute-raised"
                  :evidence/group-id "g1")
        ;; Compute content hashes from base content only (before adding chain fields)
        h1 (compute-content-hash a1)
        h2 (compute-content-hash a2)
        h3 (compute-content-hash a3)
        ;; Add chain fields, matching the real pipeline order:
        ;; finalize-evidence adds :evidence/hash, then inject-chain-fields adds chain fields
        a1 (assoc a1 :evidence/hash h1
                  :evidence/chain-hash-scheme chain/chain-hash-scheme
                  :evidence/chain-seq 1
                  :evidence/chain-prev-hash nil
                  :evidence/chain-self-hash h1)
        a2 (assoc a2 :evidence/hash h2
                  :evidence/chain-hash-scheme chain/chain-hash-scheme
                  :evidence/chain-seq 2
                  :evidence/chain-prev-hash h1
                  :evidence/chain-self-hash h2)
        a3 (assoc a3 :evidence/hash h3
                  :evidence/chain-hash-scheme chain/chain-hash-scheme
                  :evidence/chain-seq 3
                  :evidence/chain-prev-hash h2
                  :evidence/chain-self-hash h3)]
    (doseq [m [a1 a2 a3]]
      (spit (io/file dir (str "ev-" (:evidence/hash m) ".json"))
            (json/write-str m {:key-fn evidence/qualified-key :indent true})))
    dir))

(deftest chain-integrity-valid-chain
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-ci-" (java.util.UUID/randomUUID))
        _ (setup-chain-artifacts dir)
        result (evidence/verify-chain-integrity dir)]
    (is (:valid result) "Valid chain passes")
    (is (= 3 (:artifact-count result)))
    (is (empty? (:violations result)))))

(deftest chain-integrity-gap
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-ci-" (java.util.UUID/randomUUID))
        _ (setup-chain-artifacts dir)
        ;; Find the file with chain-seq 2 to delete
        files (.listFiles (java.io.File. dir))]
    (doseq [f files]
      (try
        (let [data (json/read-str (slurp f) :key-fn keyword)]
          (when (= 2 (:evidence/chain-seq data))
            (.delete f)))
        (catch Exception _ nil)))
    (let [result (evidence/verify-chain-integrity dir)]
      (is (not (:valid result)) "Chain with gap is invalid")
      (is (some #(= 2 %) (:gaps result)) "Seq 2 reported as gap"))))

(deftest chain-integrity-self-hash-mismatch
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-ci-" (java.util.UUID/randomUUID))
        _ (setup-chain-artifacts dir)
        ;; Find the file with chain-seq 1 and change its evidence/type
        files (.listFiles (java.io.File. dir))
        f (some (fn [f]
                  (try (let [d (json/read-str (slurp f) :key-fn keyword)]
                         (when (= 1 (:evidence/chain-seq d)) f))
                       (catch Exception _ nil)))
                files)
        data (json/read-str (slurp f) :key-fn keyword)
        tampered (assoc data :evidence/type "tampered-type")]
    (spit f (json/write-str tampered {:key-fn evidence/qualified-key :indent true}))
    (let [result (evidence/verify-chain-integrity dir)]
      (is (not (:valid result)) "Tampered chain is invalid")
      (is (= 1 (:content-hash-failed result)) "Content hash mismatch detected"))))

;; ── Scenario Evidence Verification: \"Did this scenario produce expected evidence?\" ─

(deftest scenario-evidence-verification-matches-all
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-ev-" (java.util.UUID/randomUUID))
        _ (setup-artifacts! dir)
        scenario {:expected-evidence
                  [{:type "escrow-created" :importance :core}
                   {:type "escrow-released" :importance :core}
                   {:type "stake-registered" :importance :diagnostic}
                   {:type "dispute-raised" :importance :core}]}
        result (evidence/verify-scenario-evidence scenario dir)]
    (is (some? result))
    (is (= 4 (count (:matched result))) "All 4 expected types matched")
    (is (empty? (:missing result)) "No missing entries")
    (is (seq (:unexpected result)) "Has unexpected types (transition generic trace)")))

(deftest scenario-evidence-verification-detects-missing
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-ev-" (java.util.UUID/randomUUID))
        _ (setup-artifacts! dir)
        scenario {:expected-evidence
                  [{:type "nonexistent-type" :importance :core}
                   {:type "another-missing" :importance :diagnostic}]}
        result (evidence/verify-scenario-evidence scenario dir)]
    (is (some? result))
    (is (empty? (:matched result)) "No matched entries")
    (is (= 2 (count (:missing result))) "Both expected types missing")))

(deftest scenario-evidence-verification-no-expected-evidence
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-ev-" (java.util.UUID/randomUUID))
        _ (setup-artifacts! dir)
        scenario {}  ;; No :expected-evidence key
        result (evidence/verify-scenario-evidence scenario dir)]
    (is (nil? result) "No :expected-evidence returns nil")))

;; ── Cross-Run Evidence Diff: \"What changed between two runs?\" ───────────

(defn- setup-diff-dirs
  "Create two artifact directories with overlapping and unique artifacts."
  [dir-a dir-b]
  (let [base {:evidence/schema-version "event-evidence.v1"}
        common (assoc base :evidence/type "escrow-created"
                      :evidence/chain-seq 1
                      :evidence/group-id "g1"
                      :evidence/hash "abc111")
        only-a (assoc base :evidence/type "stake-registered"
                      :evidence/chain-seq 2
                      :evidence/group-id "g1"
                      :evidence/hash "abc222")
        only-b (assoc base :evidence/type "dispute-raised"
                      :evidence/chain-seq 3
                      :evidence/group-id "g1"
                      :evidence/hash "abc333")]
    (doseq [[dir entries] [[dir-a [common only-a]] [dir-b [common only-b]]]]
      (.mkdirs (java.io.File. dir))
      (doseq [m entries]
        (spit (io/file dir (str (:evidence/hash m) ".json"))
              (json/write-str m {:key-fn evidence/qualified-key :indent true}))))))

(deftest diff-finds-added-missing-and-common
  (let [dir-a (str (System/getProperty "java.io.tmpdir") "/qol-da-" (java.util.UUID/randomUUID))
        dir-b (str (System/getProperty "java.io.tmpdir") "/qol-db-" (java.util.UUID/randomUUID))
        _ (setup-diff-dirs dir-a dir-b)
        result (evidence/diff-evidence-directories dir-a dir-b)]
    (is (some? result))
    (is (= 1 (count (:added result))) "One artifact added in B")
    (is (= 1 (count (:missing result))) "One artifact missing from B")
    (is (= 1 (count (:unchanged result))) "One artifact unchanged")
    (is (= 1 (:b-only (:summary result))))))

(deftest diff-identical-directories
  (let [dir-a (str (System/getProperty "java.io.tmpdir") "/qol-da-" (java.util.UUID/randomUUID))
        dir-b (str (System/getProperty "java.io.tmpdir") "/qol-db-" (java.util.UUID/randomUUID))
        base {:evidence/schema-version "event-evidence.v1"}
        art (assoc base :evidence/type "escrow-created"
                   :evidence/chain-seq 1
                   :evidence/group-id "g1"
                   :evidence/hash "abc111")]
    (.mkdirs (java.io.File. dir-a))
    (.mkdirs (java.io.File. dir-b))
    (doseq [dir [dir-a dir-b]]
      (spit (io/file dir "a.json")
            (json/write-str art {:key-fn evidence/qualified-key :indent true})))
    (let [result (evidence/diff-evidence-directories dir-a dir-b)]
      (is (zero? (count (:added result))) "No added")
      (is (zero? (count (:missing result))) "No missing")
      (is (= 1 (count (:unchanged result))) "One unchanged"))))

(deftest diff-empty-directories
  (let [dir-a (str (System/getProperty "java.io.tmpdir") "/qol-da-" (java.util.UUID/randomUUID))
        dir-b (str (System/getProperty "java.io.tmpdir") "/qol-db-" (java.util.UUID/randomUUID))]
    (.mkdirs (java.io.File. dir-a))
    (.mkdirs (java.io.File. dir-b))
    (let [result (evidence/diff-evidence-directories dir-a dir-b)]
      (is (zero? (count (:added result))))
      (is (zero? (count (:missing result))))
      (is (zero? (count (:unchanged result)))))))

;; ── Evidence Bundle Export: \"Share this event's evidence with a collaborator\" ─

(deftest export-bundle-creates-manifest
  (let [src (str (System/getProperty "java.io.tmpdir") "/qol-eb-" (java.util.UUID/randomUUID))
        out (str (System/getProperty "java.io.tmpdir") "/qol-eb-out-" (java.util.UUID/randomUUID))
        gid (setup-linked-group src)
        result (evidence/export-evidence-bundle gid out src)]
    (is (= out result) "Returns output directory path")
    (is (.exists (io/file out "manifest.json")) "Manifest file exists")
    (let [manifest (json/read-str (slurp (io/file out "manifest.json")) :key-fn keyword)]
      (is (= 4 (:artifact-count manifest)) "All 4 artifacts exported")
      (is (= gid (first (:group-ids manifest))) "Group-id in manifest"))))

(deftest export-bundle-multiple-group-ids
  (let [src (str (System/getProperty "java.io.tmpdir") "/qol-eb-" (java.util.UUID/randomUUID))
        out (str (System/getProperty "java.io.tmpdir") "/qol-eb-out-" (java.util.UUID/randomUUID))
        _ (setup-linked-group src)
        result (evidence/export-evidence-bundle ["g1" "nonexistent"] out src)]
    (is (= out result))
    (let [files (.listFiles (java.io.File. out))]
      (is (some #(= "manifest.json" (.getName %)) files)))))

(deftest export-bundle-missing-group-id
  (let [src (str (System/getProperty "java.io.tmpdir") "/qol-eb-" (java.util.UUID/randomUUID))
        out (str (System/getProperty "java.io.tmpdir") "/qol-eb-out-" (java.util.UUID/randomUUID))
        _ (.mkdirs (java.io.File. src))
        result (evidence/export-evidence-bundle "nonexistent-group" out src)]
    (is (= out result))
    (let [files (.listFiles (java.io.File. out))]
      (is (= 1 (count files)) "Only manifest.json written")
      (is (= "manifest.json" (.getName (first files)))))))

;; ── Diff Evidence: "What changed because of this event?" ────────────────

(deftest diff-evidence-builds-from-trace
  (let [s (io-sc/load-scenario-file "scenarios/edn/S01_baseline-happy-path.edn")
        r (sew/replay-with-sew-protocol (fix/normalize-scenario s))
        diffs (diff/build-diff-evidence (:trace r) :scenario-id "S01" :run-id "test")]
    (is (seq diffs) "At least one diff artifact produced")
    (is (every? :evidence/id diffs) "Every diff has an id")
    (is (every? :diff/summary diffs) "Every diff has a summary")
    (is (every? #(= :state-diff (:evidence/type %)) diffs) "All are state-diff type")
    (is (every? #(= :diff (:evidence/layer %)) diffs) "All have :diff layer")
    (is (every? #(= :diagnostic (:evidence/role %)) diffs) "All are diagnostic")))

(deftest diff-evidence-events-linked
  (let [s (io-sc/load-scenario-file "scenarios/edn/S01_baseline-happy-path.edn")
        r (sew/replay-with-sew-protocol (fix/normalize-scenario s))
        diffs (diff/build-diff-evidence (:trace r) :scenario-id "S01" :run-id "test")]
    (doseq [d diffs]
      (is (contains? d :event/index) (str "Event index present for " (:evidence/id d)))
      (is (contains? d :event/type) (str "Event type present for " (:evidence/id d))))
    ;; Verify the first state transition produces a diff linked to an event
    (is (some? (:event/type (first diffs))) "First diff has a non-nil event type")))

(deftest diff-evidence-structural-integrity
  (let [a {:a 1 :b 2 :c 3}
        b {:a 1 :b 99 :d 4}
        changes (diff/structural-diff a b)
        ;; :c removed, :d added, :b changed, :a unchanged
        changed (:op (first (filter #(= :b (first (:path %))) changes)))
        added (:op (first (filter #(= :d (first (:path %))) changes)))
        removed (:op (first (filter #(= :c (first (:path %))) changes)))]
    (is (= :changed changed) ":b changed")
    (is (= :added added) ":d added")
    (is (= :removed removed) ":c removed")
    (is (= 3 (count changes)) "3 changes total")))

(deftest diff-evidence-empty-diff
  (let [changes (diff/structural-diff {:a 1} {:a 1})]
    (is (empty? changes) "Identical maps produce no changes")))

(deftest diff-evidence-write-and-index
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-diff-" (java.util.UUID/randomUUID))
        s (io-sc/load-scenario-file "scenarios/edn/S01_baseline-happy-path.edn")
        r (sew/replay-with-sew-protocol (fix/normalize-scenario s))
        result (diff/write-diff-evidence! (:trace r) dir :scenario-id "S01" :run-id "test")]
    (is (pos? (:diff-count result)) "Diffs were written")
    (is (seq (:paths result)) "Paths are returned")
    (is (.exists (io/file dir "diff-evidence")) "Diff-evidence directory created")))

(deftest diff-evidence-role-diagnostic-not-core
  (let [a {:x 10} b {:x 20}
        changes (diff/structural-diff a b)
        artifact (diff/build-diff-artifact {:action :test :time 100} nil nil 0 "g" changes)]
    (is (= :diagnostic (:evidence/role artifact)) "Diff evidence is always diagnostic")
    (is (= :diff (:evidence/layer artifact)) "Diff evidence layer is :diff")))

;; ── Invariant Linking: "Which invariants were checked for this event?" ──

(deftest invariant-linking-empty-trace
  (let [links (diff/build-invariant-links [])]
    (is (zero? (count (:by-event-index links))) "Empty trace produces no links")
    (is (zero? (count (:by-invariant-id links))) "Empty trace produces no invariant IDs")))

(deftest invariant-linking-no-violations
  (let [links (diff/build-invariant-links [{:violations nil :invariants-ok? true}])]
    (is (zero? (count (:by-event-index links))) "No violations → no event links")
    (is (zero? (count (:by-invariant-id links))) "No violations → no invariant links")))

(deftest invariant-linking-with-violations
  (let [trace [{:action :create_escrow :seq 0
                :violations {:solvency {:holds? false :violations [{:type :underflow}]}
                             :fee-cap {:holds? true :violations []}}
                :invariants-ok? false}]
        links (diff/build-invariant-links trace)]
    (is (= 1 (count (:by-event-index links))) "One event with violations")
    (is (= 2 (count (:by-invariant-id links))) "Two invariants referenced")
    (let [ev (get (:by-event-index links) 0)]
      (is (false? (:invariants-ok? ev)) "invariants-ok? reflects failure")
      (is (= 2 (count (:invariant-ids ev))) "Two IDs listed"))
    (let [sol (get (:by-invariant-id links) :solvency)]
      (is (false? (:invariant/holds? (first sol))) "solvency holds? is false"))))

(deftest invariant-linking-merges-into-registry
  (let [registry {:entries [] :indexes {:by-event-index {}}}
        trace [{:action :test :seq 0
                :violations {:solvency {:holds? false :violations []}}
                :invariants-ok? false}]
        merged (diff/merge-invariant-links registry trace)]
    (is (get-in merged [:indexes :by-invariant]) "Invariant links added to registry")))

(deftest invariant-linking-unchanged-registry
  (let [registry {:entries [] :indexes {:by-event-index {}}}
        trace [{:violations nil :invariants-ok? true}]
        merged (diff/merge-invariant-links registry trace)]
    (is (= registry merged) "No-violation trace leaves registry unchanged")))

;; ── Semantic Classification: classify-change and classify-diff-changes-semantic ─

(deftest classify-change-expected-for-known-path-and-event
  (let [c {:path [:total-held :USDC] :op :changed}
        result (diff/classify-change c :create_escrow)]
    (is (= :expected result) ":total-held changed during create_escrow is expected")))

(deftest classify-change-unexpected-for-unknown-combo
  (let [c {:path [:escrow-transfers 0] :op :changed}
        result (diff/classify-change c :register_stake)]
    (is (= :unexpected result) ":escrow-transfers changed during register_stake is unexpected")))

(deftest classify-change-diagnostic-for-internal
  (let [c {:path [:block-time nil] :op :changed}
        result (diff/classify-change c :any_event)]
    (is (= :diagnostic-only result) "Internal paths are diagnostic-only")))

(deftest classify-change-financial-boundary
  (let [c {:path [:total-held :USDC] :op :changed}
        result (diff/classify-change c :unknown_event)]
    (is (= :financial-boundary result) "Financial path + unknown event = financial-boundary")))

(deftest classify-diff-changes-semantic-maps-all
  (let [changes [{:path [:total-held :USDC] :op :changed}
                 {:path [:block-time nil] :op :changed}
                 {:path [:escrow-transfers 0] :op :changed}]
        classified (diff/classify-diff-changes-semantic changes :create_escrow)]
    (is (= 3 (count classified)) "All changes classified")
    (is (every? :classification classified) "Every change has classification")
    (is (= :expected (:classification (first classified))) ":total-held → expected")
    (is (= :diagnostic-only (:classification (second classified))) ":block-time → diagnostic")))

;; ── Domain Summary Enhancement ─────────────────────────────────────────────

(deftest build-enhanced-domain-summary-adds-classification
  (let [changes [{:path [:total-held :USDC] :domain :financial :label "Held" :op :changed :classification :expected}
                 {:path [:block-time nil] :domain :internal :label "Clock" :op :changed :classification :diagnostic-only}]
        summary (diff/build-enhanced-domain-summary changes)
        bcs (:by-classification summary)]
    (is (contains? summary :by-classification) "Has :by-classification")
    (is (= 1 (get bcs :expected 0)) "1 :expected change")
    (is (= 1 (get bcs :diagnostic-only 0)) "1 :diagnostic change")))

;; ── Diff Index ──────────────────────────────────────────────────────────────

(deftest build-diff-index-creates-by-event-index
  (let [diffs [{:event/index 0 :evidence/id "diff-a" :event/type "test"
                :diff/summary {:changed-paths 1 :added-paths 0 :removed-paths 0}}
               {:event/index 1 :evidence/id "diff-b" :event/type "test2"
                :diff/summary {:changed-paths 2 :added-paths 1 :removed-paths 0}}]
        idx (diff/build-diff-index diffs)]
    (is (contains? (:by-event-index idx) 0) "Event 0 in index")
    (is (contains? (:by-event-index idx) 1) "Event 1 in index")
    (is (= "diff-a" (:diff-id (get (:by-event-index idx) 0))) "Correct diff ID")))

;; ── Strict Mode Validation ─────────────────────────────────────────────────

(deftest strict-mode-promotes-warnings
  (let [reg {:entries [{:evidence/id "e1" :evidence/type :test :hash/content "a"
                        :file/path "test.json" :evidence/layer :generic-trace}
                       {:evidence/id "e2" :evidence/type :test :hash/content "b"
                        :file/path "test2.json" :evidence/layer :targeted-protocol
                        :subject/type :resolver :subject/id "r1" :action/type :slash
                        :scenario/id "s1" :run/id "r1" :event/index 0}]
             :indexes {:by-group-id {"g1" ["e1"]}}}
        strict (reg-val/validate-evidence-registry reg :strict true :artifact-dir "/tmp")]
    (is (= :failed (:status strict)) "Strict mode fails on ancillary warnings")
    (let [failed-strict (filter #(= :failed (:status %)) (:checks strict))
          promoted (filter #(.contains (:id %) "reason") failed-strict)]
      (is (some #(= "targeted-entries-have-reason" (:id %)) promoted)
          "Missing evidence/reason promoted to failure by strict mode"))))

(deftest strict-mode-excludes-diff-layer
  (let [reg {:entries [{:evidence/id "d1" :evidence/type :state-diff
                        :hash/content "a" :file/path "d.json"
                        :evidence/layer :diff}]
             :indexes {}}
        strict (reg-val/validate-evidence-registry reg :strict true :artifact-dir "/tmp")]
    (is (= :passed (:status strict)) "Diff-only registry passes strict mode")))

;; ── Merge Invariant Links ──────────────────────────────────────────────────

(deftest merge-invariant-links-adds-section
  (let [registry {:entries [] :indexes {:by-event-index {}}}
        trace [{:action :test :violations {:solvency {:holds? true :violations []}}
                :invariants-ok? true}]
        merged (diff/merge-invariant-links registry trace)]
    (is (get-in merged [:indexes :by-invariant :links]) "Links section added to registry")))

(deftest merge-invariant-links-no-registry-mutation
  (let [registry {:entries [] :indexes {:by-event-index {}}}
        trace [{:action :ok :violations nil :invariants-ok? true}]
        merged (diff/merge-invariant-links registry trace)]
    (is (= registry merged) "No-violation trace leaves registry unchanged")))
