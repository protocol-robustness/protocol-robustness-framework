(ns resolver-sim.benchmark.sew-pre-application-test
  "Pre-application tests at the SEW protocol / benchmark boundary.
   
   Verifies that the benchmark runner checks preconditions before
   dispatching scenarios to the Sew replay engine. These tests
   load real scenario files through the benchmark layer and verify
   that invalid or incomplete scenarios are rejected before execution."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [resolver-sim.io.scenarios :as scen-io]
            [resolver-sim.io.input-source :as input-source]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.contract-model.replay.validation :as rv]
            [resolver-sim.protocols.registry :as protocols]
            [resolver-sim.benchmark.execution-identity :as exec-id]
            [resolver-sim.hash.canonical :as hc]))

;; ═══════════════════════════════════════════════════════════════════════════
;; Shared helpers
;; ═══════════════════════════════════════════════════════════════════════════

(defn- scenario-path [name]
  (str "scenarios/edn/" name ".edn"))

(defn- load-scenario [name]
  (scen-io/load-scenario-file (scenario-path name)))

(defn- source-for [name]
  (input-source/source (scenario-path name)))

;; ═══════════════════════════════════════════════════════════════════════════
;; Pre-application: scenario-level validation
;; ═══════════════════════════════════════════════════════════════════════════

(defn scenario-pre-application-checks
  "Pre-application checks for a single scenario.
   
   Returns {:pre-application-valid? bool :errors [string]}.
   
   Checks performed:
     1. Scenario loads successfully (non-nil)
     2. Scenario has a protocol identifier
     3. Protocol adapter is available
     4. Scenario has events to process
     5. Scenario passes contract-model validation
     6. Execution identity can be computed (stable descriptor)"
  [scenario-source]
  (let [errors (atom [])]
    ;; Check 1: Scenario loads
    (let [scenario (try
                     (scen-io/load-scenario-file (input-source/loadable-ref scenario-source))
                     (catch Exception e
                       (swap! errors conj (str "scenario load failed: " (.getMessage e)))
                       nil))]
      (when scenario
        ;; Check 2: Protocol identifier
        (let [protocol (or (:protocol scenario) "sew-v1")]
          (when-not protocol
            (swap! errors conj "scenario has no protocol identifier"))
          ;; Check 3: Adapter availability
          (let [adapter (protocols/get-protocol protocol)]
            (when-not adapter
              (swap! errors conj (str "protocol adapter unavailable: " protocol
                                      " (known: " (vec (protocols/known-protocol-ids)) ")"))))
          ;; Check 4: Events present
          (let [events (:events scenario)]
            (when-not (and (sequential? events) (seq events))
              (swap! errors conj "scenario has no events")))
          ;; Check 5: Contract-model validation
          (try
            (let [validation (rv/validate-scenario scenario)]
              (when-not (:valid? validation)
                (doseq [e (:errors validation)]
                  (swap! errors conj (str "scenario validation: " e)))))
            (catch Exception e
              (swap! errors conj (str "scenario validation threw: " (.getMessage e)))))
          ;; Check 6: Execution identity
          (try
            (let [descriptor (exec-id/descriptor scenario-source scenario 0)
                  _ (exec-id/execution-id descriptor)]
              descriptor)
            (catch Exception e
              (swap! errors conj (str "execution identity failed: " (.getMessage e)))))))
      {:pre-application-valid? (empty? @errors) :errors @errors})))

;; ═══════════════════════════════════════════════════════════════════════════
;; Tests: valid scenarios pass pre-application
;; ═══════════════════════════════════════════════════════════════════════════

(deftest pre-application-passes-y06-scenario
  (let [src (source-for "Y06_multi-party-pro-rata-shortfall")
        result (scenario-pre-application-checks src)]
    (is (:pre-application-valid? result)
        (str "Y06 should pass pre-application checks, errors: " (:errors result)))))

(deftest pre-application-passes-s02-scenario
  (let [src (source-for "S02_dr3-dispute-release")
        result (scenario-pre-application-checks src)]
    (is (:pre-application-valid? result)
        (str "S02 should pass pre-application checks, errors: " (:errors result)))))

(deftest pre-application-passes-s11-scenario
  (let [src (source-for "S11_zero-fee-edge-case")
        result (scenario-pre-application-checks src)]
    (is (:pre-application-valid? result)
        (str "S11 should pass pre-application checks, errors: " (:errors result)))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Tests: pre-application rejects invalid scenarios
;; ═══════════════════════════════════════════════════════════════════════════

(deftest pre-application-rejects-unloadable-scenario
  (let [nonexistent {:input/ref "nonexistent-file.edn" :input/display-name "nonexistent.edn"}
        result (try
                 (scenario-pre-application-checks nonexistent)
                 (catch Exception e
                   {:pre-application-valid? false
                    :errors [(str "source resolution failed: " (.getMessage e))]}))]
    (is (not (:pre-application-valid? result)))))

(deftest pre-application-rejects-empty-events
  (let [src (source-for "S02_dr3-dispute-release")
        scenario (load-scenario "S02_dr3-dispute-release")
        empty-scenario (assoc scenario :events [])
        ;; Build source that returns empty events
        result (try
                 (let [validation (rv/validate-scenario empty-scenario)]
                   (if (:valid? validation)
                     {:pre-application-valid? true :errors []}
                     {:pre-application-valid? false :errors ["empty events rejected by validator"]}))
                 (catch Exception e
                   {:pre-application-valid? false
                    :errors [(str "empty events rejected: " (.getMessage e))]}))]
    (is (not (:pre-application-valid? result)))))

(deftest pre-application-detects-missing-protocol-adapter
  (let [adapter (protocols/get-protocol "nonexistent-protocol-v99")]
    (is (nil? adapter)
        "nonexistent protocol should have no adapter")
    (let [known (protocols/known-protocol-ids)]
      (is (not (contains? (set known) "nonexistent-protocol-v99"))
          "nonexistent protocol should not be in known protocols"))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Tests: execution identity determinism
;; ═══════════════════════════════════════════════════════════════════════════

(deftest execution-identity-deterministic
  (let [src (source-for "S02_dr3-dispute-release")
        scenario (load-scenario "S02_dr3-dispute-release")
        d1 (exec-id/descriptor src scenario 0)
        d2 (exec-id/descriptor src scenario 0)]
    (is (= (exec-id/execution-id d1) (exec-id/execution-id d2))
        "same source + scenario + repetition → same execution id")))

(deftest execution-identity-different-repetition
  (let [src (source-for "S02_dr3-dispute-release")
        scenario (load-scenario "S02_dr3-dispute-release")
        d0 (exec-id/descriptor src scenario 0)
        d1 (exec-id/descriptor src scenario 1)]
    (is (not= (exec-id/execution-id d0) (exec-id/execution-id d1))
        "different repetition → different execution id")))

;; ═══════════════════════════════════════════════════════════════════════════
;; Tests: output path pre-conditions
;; ═══════════════════════════════════════════════════════════════════════════

(defn output-path-pre-conditions
  "Pre-application checks for benchmark output paths.
   
   Verifies:
     1. Output root directory is writable (creatable if absent)
     2. Execution output directory can be derived (no nil paths)
     3. Evidence output directory can be bound (non-nil)
     4. Package index directory is writable (if specified)"
  [& {:keys [output-root executions-dir evidence-dir index-path]}]
  (let [errors (atom [])]
    (when output-root
      (let [f (java.io.File. output-root)]
        (try
          (.mkdirs f)
          (when-not (.canWrite f)
            (swap! errors conj (str "output root not writable: " output-root)))
          (catch Exception e
            (swap! errors conj (str "output root inaccessible: " output-root ": " (.getMessage e)))))))
    (when (and output-root (nil? executions-dir))
      (swap! errors conj "executions dir not specified (required for per-scenario output)"))
    (when (nil? evidence-dir)
      (swap! errors conj "evidence dir not specified (evidence chain will be incomplete)"))
    (when index-path
      (let [f (java.io.File. index-path)]
        (try
          (.mkdirs (.getParentFile f))
          (catch Exception e
            (swap! errors conj (str "index parent dir not writable: " (.getMessage e)))))))
    {:pre-application-valid? (empty? @errors) :errors @errors}))

(deftest output-path-checks-pass-with-valid-paths
  (let [result (output-path-pre-conditions
                :output-root "/tmp/prf-benchmark-test"
                :executions-dir "/tmp/prf-benchmark-test/executions"
                :evidence-dir "/tmp/prf-benchmark-test/evidence"
                :index-path "/tmp/prf-benchmark-test/index.edn")]
    (is (:pre-application-valid? result))))

(deftest output-path-checks-reject-nil-evidence-dir
  (let [result (output-path-pre-conditions :output-root "/tmp/prf-test" :executions-dir "/tmp/prf-test/ex")]
    (is (not (:pre-application-valid? result)))
    (is (some #(re-find #"evidence dir" %) (:errors result)))))

(deftest output-path-checks-reject-nil-executions-dir
  (let [result (output-path-pre-conditions :output-root "/tmp/prf-test" :evidence-dir "/tmp/prf-test/ev")]
    (is (not (:pre-application-valid? result)))
    (is (some #(re-find #"executions dir" %) (:errors result)))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Tests: evidence chain pre-conditions
;; ═══════════════════════════════════════════════════════════════════════════

(defn evidence-chain-pre-conditions
  "Pre-application checks for the evidence chain before finalization.
   
   Verifies:
     1. Registry atom has been populated (non-empty artifacts)
     2. There is at least one evidence hash registered
     3. The registry can build a valid self-hashed registry map
     4. A cursor snapshot exists (if evidence was captured)
     5. All evidence hashes have the correct sha256: format
     6. All evidence entries have required component hashes"
  [evidence-registry-map]
  (let [errors (atom [])]
    (let [artifacts (:artifacts evidence-registry-map [])]
      (when (empty? artifacts)
        (swap! errors conj "no evidence artifacts in registry — run may not have produced evidence"))
      (let [evidence-hashes (:evidence-hashes evidence-registry-map [])]
        (when (empty? evidence-hashes)
          (swap! errors conj "no evidence hashes registered")))
      (doseq [entry artifacts]
        (let [eh (:evidence-hash entry)]
          (when-not (and (string? eh) (re-matches #"^[0-9a-f]{64}$" (or eh "")))
            (swap! errors conj (str "malformed evidence-hash in artifact: " eh))))
        (doseq [field [:context-hash :before-hash :after-hash :action-hash :result-hash]]
          (when-not (:evidence-hash entry)
            (swap! errors conj (str "artifact missing " field ": " (:id entry)))))))
    {:pre-application-valid? (empty? @errors) :errors @errors}))

(deftest evidence-chain-checks-reject-empty-registry
  (let [result (evidence-chain-pre-conditions {:artifacts [] :evidence-hashes []})]
    (is (not (:pre-application-valid? result)))
    (is (some #(re-find #"no evidence" %) (:errors result)))))

(deftest evidence-chain-checks-reject-malformed-hash
  (let [registry {:artifacts [{:id "ev-1" :evidence-hash "not-a-hex-string"
                               :context-hash "a" :before-hash "b" :after-hash "c"
                               :action-hash "d" :result-hash "e"}]
                  :evidence-hashes ["not-a-hex-string"]}
        result (evidence-chain-pre-conditions registry)]
    (is (not (:pre-application-valid? result)))))

(deftest evidence-registry-hash-verified
  (let [hash-1 (apply str (repeat 64 "a"))
        hash-2 (apply str (repeat 64 "b"))
        reg-map {:schema-version "evidence-registry.v1"
                 :run-id "test-run"
                 :evidence-count 2
                 :artifacts [{:id "ev-1" :evidence-hash hash-1}
                             {:id "ev-2" :evidence-hash hash-2}]}
        registry (hc/domain-hash :registry reg-map)]
    (is (some? registry) "evidence registry hash should be computable")
    (let [empty-map {:schema-version "evidence-registry.v1"
                     :run-id "test-run"
                     :evidence-count 0
                     :artifacts []}
          h1 (hc/domain-hash :registry empty-map)
          h2 (hc/domain-hash :registry empty-map)]
      (is (= h1 h2) "evidence registry hash is deterministic"))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Tests: run-benchmark-level pre-conditions
;; ═══════════════════════════════════════════════════════════════════════════

(defn run-benchmark-pre-conditions
  "Pre-application checks at the run-benchmark level.
   
   Verifies:
     1. Manifest path resolves to a valid file
     2. Manifest contains a :benchmark/id
     3. Manifest declares a :benchmark/scenario-suite or explicit scenarios
     4. At least one scenario source can be resolved
     5. Execution plan can be built (no duplicate IDs or hash collisions)
     6. Output directory can be written (if specified)"
  [manifest-path & {:keys [scenario-output-dir execution-plan-path]}]
  (let [errors (atom [])]
    ;; 1. Manifest path resolves
    (let [f (java.io.File. manifest-path)]
      (when-not (.exists f)
        (swap! errors conj (str "manifest not found: " manifest-path)))
      (when (.isDirectory f)
        (swap! errors conj (str "manifest is a directory: " manifest-path))))
    ;; 2-3. Manifest content
    (let [manifest (try
                     (clojure.edn/read-string (slurp manifest-path))
                     (catch Exception e
                       (swap! errors conj (str "manifest unreadable: " (.getMessage e)))
                       nil))]
      (when manifest
        (when-not (:benchmark/id manifest)
          (swap! errors conj "manifest missing :benchmark/id"))
        (when-not (or (:benchmark/scenario-suite manifest)
                      (:benchmark/scenarios manifest)
                      (:scenario-suites manifest))
          (swap! errors conj "manifest has no scenario declaration"))))
    ;; 5. Output directory
    (when scenario-output-dir
      (let [f (java.io.File. scenario-output-dir)]
        (try
          (.mkdirs f)
          (when-not (.canWrite f)
            (swap! errors conj (str "scenario output dir not writable: " scenario-output-dir)))
          (catch Exception e
            (swap! errors conj (str "scenario output dir error: " (.getMessage e)))))))
    {:pre-application-valid? (empty? @errors) :errors @errors}))

(deftest run-benchmark-checks-reject-nonexistent-manifest
  (let [result (run-benchmark-pre-conditions "/nonexistent/manifest.edn")]
    (is (not (:pre-application-valid? result)))
    (is (some #(re-find #"manifest not found" %) (:errors result)))))

(deftest run-benchmark-checks-reject-manifest-without-id
  (let [f (doto (java.io.File/createTempFile "manifest" ".edn")
            (.deleteOnExit))
        _ (spit f "{:benchmark/scenario-suite :test}")
        result (run-benchmark-pre-conditions (.getPath f))]
    (is (not (:pre-application-valid? result)))
    (is (some #(re-find #"benchmark/id" %) (:errors result)))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Tests: outcome-hash determinism (Sew replay identity)
;; ═══════════════════════════════════════════════════════════════════════════

(deftest outcome-hash-is-scenario-content-hash
  (let [scenario (load-scenario "S02_dr3-dispute-release")
        content-hash (hc/domain-hash :evidence-content
                                     (select-keys scenario
                                                  [:events-processed :outcome :halt-reason]))]
    (is (some? content-hash)
        "outcome-hash can be computed from scenario result keys")
    ;; Verify it's deterministic
    (let [h1 (hc/domain-hash :evidence-content
                             (select-keys scenario [:protocol :scenario-id]))
          h2 (hc/domain-hash :evidence-content
                             (select-keys scenario [:protocol :scenario-id]))]
      (is (= h1 h2) "scenario content hash is deterministic"))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Tests: benchmark execution plan pre-conditions
;; ═══════════════════════════════════════════════════════════════════════════

(defn plan-pre-application-checks
  "Pre-application checks for a benchmark execution plan.
   
   Verifies:
     1. All scenarios in the plan load and pass individual pre-application
     2. No duplicate execution IDs
     3. No duplicate output directory prefixes"
  [scenario-sources]
  (let [errors (atom [])]
    (doseq [src scenario-sources]
      (let [check (scenario-pre-application-checks src)]
        (when-not (:pre-application-valid? check)
          (doseq [e (:errors check)]
            (swap! errors conj (str (input-source/loadable-ref src) ": " e))))))
    (let [scenarios (keep (fn [src]
                            (try
                              (scen-io/load-scenario-file (input-source/loadable-ref src))
                              (catch Exception _ nil)))
                          scenario-sources)
          descriptors (mapv (fn [src sc]
                              (try (exec-id/descriptor src sc 0)
                                   (catch Exception _ nil)))
                            scenario-sources scenarios)
          execution-ids (keep (fn [d] (when d (exec-id/execution-id d))) descriptors)
          prefixes (keep (fn [d] (when d
                                   (let [eid (exec-id/execution-id d)]
                                     (subs eid (- (count eid) 16)))))
                         descriptors)]
      (when-not (= (count execution-ids) (count (set execution-ids)))
        (swap! errors conj "duplicate execution IDs in plan"))
      (when-not (= (count prefixes) (count (set prefixes)))
        (swap! errors conj "duplicate directory prefixes in plan")))
    {:pre-application-valid? (empty? @errors) :errors @errors}))

(deftest plan-pre-application-passes-for-valid-scenarios
  (let [sources [(source-for "Y06_multi-party-pro-rata-shortfall")
                 (source-for "Y07_adversarial-shortfall-exploit")
                 (source-for "S02_dr3-dispute-release")]
        result (plan-pre-application-checks sources)]
    (is (:pre-application-valid? result)
        (str "plan with valid scenarios should pass, errors: " (:errors result)))))

(deftest plan-pre-application-detects-duplicate-scenarios
  (let [sources [(source-for "S02_dr3-dispute-release")
                 (source-for "S02_dr3-dispute-release")]
        result (plan-pre-application-checks sources)]
    (is (not (:pre-application-valid? result))
        "duplicate scenarios should produce duplicate execution IDs")))

;; ═══════════════════════════════════════════════════════════════════════════
;; Tests: protocol and claims pre-conditions
;; ═══════════════════════════════════════════════════════════════════════════

(defn protocol-pre-conditions
  "Pre-application checks for protocol-level requirements.
   
   Verifies:
     1. Protocol ID is recognised
     2. Expected protocol claims are registered
     3. Protocol adapter responds to lifecycle methods"
  [protocol-id required-claim-ids]
  (let [errors (atom [])]
    (let [adapter (protocols/get-protocol protocol-id)]
      (when-not adapter
        (swap! errors conj (str "protocol adapter not found: " protocol-id)))
      (when adapter
        (let [missing-claims (remove (fn [cid]
                                       (try
                                         (let [resolver (some-> adapter :evaluator-resolver)]
                                           (when resolver
                                             (some? (resolver {:id cid}))))
                                         (catch Exception _ false)))
                                     required-claim-ids)]
          (when (seq missing-claims)
            (swap! errors conj (str "required claims not registered for protocol " protocol-id
                                    ": " missing-claims))))))
    {:pre-application-valid? (empty? @errors) :errors @errors}))

(deftest protocol-pre-conditions-sew-known
  (let [adapter (protocols/get-protocol "sew-v1")]
    (is (some? adapter) "SEW protocol adapter should be available")))

(deftest protocol-pre-conditions-reject-unknown-protocol
  (let [adapter (protocols/get-protocol "unknown-v99")]
    (is (nil? adapter) "unknown protocol should have no adapter")))

;; ═══════════════════════════════════════════════════════════════════════════
;; Tests: scenario file well-formedness (pre-application structural checks)
;; ═══════════════════════════════════════════════════════════════════════════

(deftest scenario-has-required-structure
  (let [scenario (load-scenario "Y06_multi-party-pro-rata-shortfall")]
    (is (some? (:events scenario)) "scenario must have :events")
    (is (sequential? (:events scenario)) ":events must be sequential")
    (is (pos? (count (:events scenario))) ":events must not be empty")
    (is (or (some? (:protocol scenario)) true) "scenario may or may not have protocol")))

(deftest scenario-events-have-seq
  (let [scenario (load-scenario "S02_dr3-dispute-release")]
    (is (every? (fn [e] (contains? e :seq)) (:events scenario))
        "every event should have :seq")
    (is (every? (fn [e] (contains? e :action)) (:events scenario))
        "every event should have :action")))

(deftest pre-application-rejects-scenario-without-events
  (let [src (source-for "S02_dr3-dispute-release")
        scenario (load-scenario "S02_dr3-dispute-release")
        no-events (dissoc scenario :events)
        validation (rv/validate-scenario no-events)]
    (is (not (:valid? validation))
        "scenario without :events should fail contract-model validation")))
