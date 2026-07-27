(ns resolver-sim.notebook-support.trust-boundary-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [resolver-sim.notebook-support.trust-boundary :as tb]
            [resolver-sim.assurance.witness-verifier :as wv]))

(def valid-root "test/fixtures/trust-boundary/valid")
(def unassured-root "test/fixtures/trust-boundary/complete-but-unassured")

;; ── Load helpers ────────────────────────────────────────────────────────────

(deftest load-trust-sequence-definition-succeeds
  (let [result (tb/load-trust-sequence-definition
                "data/sequences/force-authorised-custody-adjustment.edn")]
    (is (= :trust-sequence-definition (:artifact/type result)))
    (is (some? (:artifact/value result)))
    (is (true? (get-in result [:artifact/validation :valid?])))))

(deftest load-benchmark-definition-succeeds
  (let [result (tb/load-benchmark-definition (str valid-root "/benchmark/definition.edn"))]
    (is (= :benchmark-definition (:artifact/type result)))
    (is (some? (:artifact/value result)))
    (is (some? (:benchmark/trust-sequence-definition-root (:artifact/value result))))))

(deftest load-execution-plan-succeeds
  (let [result (tb/load-execution-plan (str valid-root "/benchmark/execution-plan.edn"))]
    (is (= :execution-plan (:artifact/type result)))
    (is (some? (:artifact/value result)))
    (is (some? (:trust-sequence-definition-root (:artifact/value result))))))

(deftest load-execution-witness-succeeds
  (let [result (tb/load-execution-witness (str valid-root "/manifest/execution-witness.json"))]
    (is (= :execution-witness (:artifact/type result)))
    (is (some? (:procedure-execution-witness/root (:artifact/value result))))))

(deftest load-canonical-assurance-succeeds
  (let [result (tb/load-canonical-assurance
                (str valid-root "/benchmark/assertions/canonical-integrity.json"))]
    (is (= :canonical-assurance (:artifact/type result)))
    (is (some? (:artifact/value result)))))

(deftest load-completion-succeeds
  (let [result (tb/load-completion (str valid-root "/completion.json"))]
    (is (= :completion (:artifact/type result)))
    (is (some? (:artifact/value result)))))

;; ── Missing artifact throws ─────────────────────────────────────────────────

(deftest missing-artifact-throws
  (is (thrown-with-msg? Exception #"not found"
                        (tb/load-trust-sequence-definition "/nonexistent/file.edn")))
  (is (thrown-with-msg? Exception #"not found"
                        (tb/load-execution-witness "/nonexistent/file.json"))))

;; ── Evidence index ──────────────────────────────────────────────────────────

(deftest build-evidence-index-from-valid-fixture
  (let [ev-idx (tb/build-evidence-index (str valid-root "/event-evidence"))]
    (is (map? ev-idx))
    (is (pos? (count (:evidence-index/by-content-hash ev-idx))))
    (is (pos? (count (:evidence-index/all-chain-self-hashes ev-idx))))
    (is (= :unverified (:evidence-index/status ev-idx)))))

;; ── Pre-execution commitment ────────────────────────────────────────────────

(deftest check-pre-execution-commitment-matches-for-valid
  (let [bdef (get (tb/load-benchmark-definition (str valid-root "/benchmark/definition.edn")) :artifact/value)
        plan (get (tb/load-execution-plan (str valid-root "/benchmark/execution-plan.edn")) :artifact/value)
        result (tb/check-pre-execution-commitment bdef plan)]
    (is (:match? result))
    (is (some? (:source-definition-root result)))
    (is (some? (:execution-plan-root result)))))

(deftest check-pre-execution-commitment-has-expected-correlation
  (let [bdef (get (tb/load-benchmark-definition (str valid-root "/benchmark/definition.edn")) :artifact/value)
        plan (get (tb/load-execution-plan (str valid-root "/benchmark/execution-plan.edn")) :artifact/value)
        result (tb/check-pre-execution-commitment bdef plan)]
    (is (some? (:expected-correlation-id result)))))

;; ── Boundary cards ──────────────────────────────────────────────────────────

(deftest project-boundary-cards-valid
  (let [ts-defn (get (tb/load-trust-sequence-definition
                      "data/sequences/force-authorised-custody-adjustment.edn") :artifact/value)
        witness (get (tb/load-execution-witness (str valid-root "/manifest/execution-witness.json")) :artifact/value)
        ev-idx (tb/build-evidence-index (str valid-root "/event-evidence"))
        adapter (try @(requiring-resolve
                       'resolver-sim.protocols.sew.procedure-evidence/sew-evidence-adapter)
                     (catch Exception _ nil))
        result (wv/verify-witness witness ts-defn ev-idx {:evidence-adapter adapter})
        {:keys [cards resolved-count]} (tb/project-boundary-cards ts-defn witness result ev-idx nil)]
    (is (pos? (count cards)))
    (is (pos? resolved-count))
    (is (every? :step/id cards))
    (is (every? :policy-requirement cards))))

(deftest boundary-cards-use-witness-content-hashes
  (let [ts-defn (get (tb/load-trust-sequence-definition
                      "data/sequences/force-authorised-custody-adjustment.edn") :artifact/value)
        witness (get (tb/load-execution-witness (str valid-root "/manifest/execution-witness.json")) :artifact/value)
        ev-idx (tb/build-evidence-index (str valid-root "/event-evidence"))
        adapter (try @(requiring-resolve
                       'resolver-sim.protocols.sew.procedure-evidence/sew-evidence-adapter)
                     (catch Exception _ nil))
        result (wv/verify-witness witness ts-defn ev-idx {:evidence-adapter adapter})
        {:keys [cards]} (tb/project-boundary-cards ts-defn witness result ev-idx nil)
        witness-hashes (set (keep :step/evidence-content-hash
                                  (:procedure-execution-witness/steps witness)))]
    (is (every? #(contains? witness-hashes (:evidence-content-hash %))
                (filter :evidence-content-hash cards))
        "Cards with evidence must reference witness content hashes")))

;; ── Verification groups ─────────────────────────────────────────────────────

(deftest project-verification-groups-valid
  (let [ts-defn (get (tb/load-trust-sequence-definition
                      "data/sequences/force-authorised-custody-adjustment.edn") :artifact/value)
        witness (get (tb/load-execution-witness (str valid-root "/manifest/execution-witness.json")) :artifact/value)
        ev-idx (tb/build-evidence-index (str valid-root "/event-evidence"))
        adapter (try @(requiring-resolve
                       'resolver-sim.protocols.sew.procedure-evidence/sew-evidence-adapter)
                     (catch Exception _ nil))
        result (wv/verify-witness witness ts-defn ev-idx {:evidence-adapter adapter})
        groups (tb/project-verification-groups (:checks result))]
    (is (= 6 (count groups)))
    (is (every? :group/id groups))
    (is (every? :group/label groups))
    (is (every? :checks groups))))

;; ── Chain provenance ────────────────────────────────────────────────────────

(deftest project-chain-provenance-valid
  (let [ev-idx (tb/build-evidence-index (str valid-root "/event-evidence"))
        result (tb/project-chain-provenance ev-idx)]
    (is (pos? (:evidence-count result)))
    (is (contains? #{"link-v1" nil} (:hash-scheme result)))
    (is (true? (:scheme-uniform? result)))))

;; ── Workbench case loading ──────────────────────────────────────────────────

(deftest load-workbench-case-valid
  (let [result (tb/load-workbench-case valid-root)]
    (is (some? (:benchmark-definition result)))
    (is (some? (:execution-plan result)))
    (is (some? (:trust-sequence-definition result)))
    (is (some? (:execution-witness result)))
    (is (some? (:canonical-assurance result)))))

(deftest load-workbench-case-unassured
  (let [result (tb/load-workbench-case unassured-root)]
    (is (some? (:benchmark-definition result)))
    (is (some? (:execution-plan result)))
    (is (some? (:trust-sequence-definition result)))
    (is (some? (:execution-witness result)))
    (is (some? (:canonical-assurance result)))))

;; ── Run layout validation ───────────────────────────────────────────────────

(deftest validate-workbench-run-layout-valid
  (let [result (tb/validate-workbench-run-layout valid-root)]
    ;; At minimum, the fixture must have definition.edn and execution-plan.edn
    (is (<= 2 (:existing result)) (str "Expected >=2 files, missing: " (:missing result)))))

(defn- make-full-fixture-dir [target-root]
  (doseq [f ["benchmark/assertions/canonical-integrity.json" "manifest/run-package-index.json" "completion.json"
             "benchmark/execution-plan.edn" "benchmark/definition.edn" "manifest/execution-witness.json"]]
    (let [p (io/file target-root f)]
      (.mkdirs (.getParentFile p))
      (when-not (.isFile p) (spit p "{}")))))

(deftest validate-workbench-run-layout-accepts-full-fixture
  (let [td (str (System/getProperty "java.io.tmpdir") "/tb-layout-" (java.util.UUID/randomUUID))]
    (try
      (make-full-fixture-dir td)
      (let [result (tb/validate-workbench-run-layout td)]
        (is (true? (:valid? result))))
      (finally
        (doseq [f (file-seq (io/file td))] (when (.isFile f) (.delete f)))
        (.delete (io/file td))))))

;; ── Resolve workbench run ───────────────────────────────────────────────────

(deftest resolve-workbench-run-keywords
  (is (= "test/fixtures/trust-boundary/valid" (tb/resolve-workbench-run :valid)))
  (is (= "test/fixtures/trust-boundary/complete-but-unassured"
         (tb/resolve-workbench-run :unassured))))

;; ── Artifact provenance ─────────────────────────────────────────────────────

(deftest collect-artifact-provenance-valid
  (let [result (tb/collect-artifact-provenance valid-root)]
    (is (some? (:run-root result)))
    (is (some? (:benchmark-definition result)) "Benchmark definition must exist")
    (is (some? (:execution-plan result)) "Execution plan must exist")
    (is (some? (:execution-witness result)) "Witness must exist")))

;; ── Repeated projections identical ──────────────────────────────────────────

(deftest repeated-projections-identical
  (let [ts-defn (get (tb/load-trust-sequence-definition
                      "data/sequences/force-authorised-custody-adjustment.edn") :artifact/value)
        witness (get (tb/load-execution-witness (str valid-root "/manifest/execution-witness.json")) :artifact/value)
        ev-idx (tb/build-evidence-index (str valid-root "/event-evidence"))
        adapter (try @(requiring-resolve 'resolver-sim.protocols.sew.procedure-evidence/sew-evidence-adapter)
                     (catch Exception _ nil))
        result-a (wv/verify-witness witness ts-defn ev-idx {:evidence-adapter adapter})
        result-b (wv/verify-witness witness ts-defn ev-idx {:evidence-adapter adapter})
        cards-a (:cards (tb/project-boundary-cards ts-defn witness result-a ev-idx nil))
        cards-b (:cards (tb/project-boundary-cards ts-defn witness result-b ev-idx nil))]
    (is (= cards-a cards-b))))

;; ── Fixture roots are read-only ─────────────────────────────────────────────

(deftest fixture-roots-are-readable-directories
  (is (.isDirectory (io/file valid-root)))
  (is (.isDirectory (io/file unassured-root))))

;; ── No Clerk dependency in notebook_support ─────────────────────────────────

(deftest no-clerk-dependency
  (let [ns-decl (slurp "src/resolver_sim/notebook_support/trust_boundary.clj")]
    (is (not (re-find #"nextjournal.clerk" ns-decl))
        "notebook_support must not depend on Clerk")))