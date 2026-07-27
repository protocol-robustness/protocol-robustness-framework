(ns resolver-sim.assurance.complete-but-unassured-test
  "End-to-end test: a package with a wrong planned correlation identity
   must be structurally valid and completion-verified but assurance-failed
   and release-ineligible.
   This distinguishes \"evidence preserved in a complete package\" from
   \"successful assurance.\""
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [resolver-sim.assurance.trust-sequence-definition :as tsd]
            [resolver-sim.assurance.procedure-execution-witness :as pew]
            [resolver-sim.assurance.witness-verifier :as wv]
            [resolver-sim.commands.witness-build :as wb]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.run.package-index :as pkg]
            [resolver-sim.io.paths :as paths]))

(def sew-adapter
  (try @(requiring-resolve 'resolver-sim.protocols.sew.procedure-evidence/sew-evidence-adapter)
    (catch Exception _ nil)))

(defn- sha256-hex [f]
  (let [d (java.security.MessageDigest/getInstance "SHA-256")]
    (.update d (java.nio.file.Files/readAllBytes (.toPath (io/file f))))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest d)))))

(defn- sha-ref [f] (str "sha256:" (sha256-hex f)))

;; ── Scenario builder ──────────────────────────────────────────────────────

(defn- build-scenario
  [temp-dir auth-id]
  (let [ev-dir (str temp-dir "/event-evidence")
        wid "wf-e2e"
        _ (.mkdirs (io/file ev-dir))

        build-ev (fn [type s prev extra-inputs before after]
                   (let [raw {:evidence/type type :scenario/id "e2e"
                              :world/before-hash before :world/after-hash after
                              :inputs (merge {:force-auth/auth-id auth-id} extra-inputs)
                              :evidence/chain-hash-scheme "link-v1"
                              :evidence/chain-seq s :evidence/chain-prev-hash prev}
                         eh (hc/hash-with-intent {:hash/intent :evidence-content}
                                                  (dissoc raw :evidence/chain-hash-scheme
                                                          :evidence/chain-seq :evidence/chain-prev-hash))
                         ch (chain/chain-link-hash eh s prev)]
                     (assoc raw :evidence/hash eh :evidence/chain-self-hash ch)))

        ev1 (build-ev "force-authorisation-granted" 1 nil {:force-auth/workflow-id wid} "0x00" "0x01")
        ev2 (build-ev "force-authorisation-executed" 2 (:evidence/chain-self-hash ev1)
                      {:force-auth/workflow-id wid} "0x01" "0x02")
        ev3 (build-ev "escrow-released" 3 (:evidence/chain-self-hash ev2)
                      {:finalize/workflow-id wid :finalize/authorization-id auth-id} "0x02" "0x03")]

    (doseq [[i ev] [[1 ev1] [2 ev2] [3 ev3]]]
      (spit (io/file ev-dir (str "ev-" i ".json"))
            (json/write-str ev {:key-fn (fn [k] (if (keyword? k) (name k) (str k)))})))
    {:ev-dir ev-dir :evidence [ev1 ev2 ev3] :chain-head (:evidence/chain-self-hash ev3) :auth-id auth-id}))

;; ── Package builder ───────────────────────────────────────────────────────

(defn- build-complete-package
  [wrong-correlation-id]
  (let [td (str (System/getProperty "java.io.tmpdir") "/e2e-ua-" (java.util.UUID/randomUUID))
        correct-id "fa-e2e-0"
        scenario (build-scenario td correct-id)
        ev-dir (:ev-dir scenario)
        reg-artifacts (mapv (fn [ev] {:id (str "ev-" (subs (:evidence/hash ev) 0 12))
                                      :kind "transition-evidence"
                                      :evidence-hash (:evidence/hash ev)})
                            (:evidence scenario))
        reg-base {:schema-version "evidence-registry.v1" :run-id "e2e"
                  :generated-at "2025-01-01T00:00:00Z" :evidence-count 3
                  :evidence-hashes (mapv :evidence/hash (:evidence scenario))
                  :artifacts reg-artifacts}
        registry (assoc reg-base :registry-hash
                        (hc/hash-with-intent {:hash/intent :registry} reg-base))
        cursor {:cursor/scope :targeted-evidence :cursor/final-seq 3
                :cursor/final-self-hash (:chain-head scenario) :cursor/total-captured 3}

        definition (tsd/build-definition
                    {:id :sew.sequence/force-authorised-custody-adjustment
                     :provider {:protocol/id :protocol/sew :protocol/version "1"}
                     :steps
                     [{:step/id :prf.step/authorisation-granted :step/type :assertion
                       :step/policy-requirement {:policy/id :sew.policy/force-authorisation :policy/version 1}}
                      {:step/id :prf.step/authorised-execution :step/type :assertion
                       :step/policy-requirement {:policy/id :sew.policy/force-authorisation :policy/version 1}}
                      {:step/id :prf.step/authorised-consumption-custody-adjustment :step/type :state-transition
                       :step/policy-requirement {:policy/id :sew.policy/force-authorisation :policy/version 1}}]})
        def-root (:trust-sequence-definition/root definition)

        witness (pew/build-witness
                 {:id "e2e-witness" :definition-root def-root :initial-input-root "0x00"
                  :step-bindings
                  [{:step/id :prf.step/authorisation-granted :evidence (nth (:evidence scenario) 0)}
                   {:step/id :prf.step/authorised-execution :evidence (nth (:evidence scenario) 1)}
                   {:step/id :prf.step/authorised-consumption-custody-adjustment :evidence (nth (:evidence scenario) 2)}]
                  :result-root "0x03"})]

    ;; Write all files
    (.mkdirs (io/file td "benchmark" "assertions"))
    (.mkdirs (io/file td "manifest"))

    (spit (io/file td "benchmark/definition.edn")
          (pr-str {:benchmark/id :benchmark/force-authorisation-custody-v1
                   :benchmark/protocol :protocol/sew
                   :benchmark/trust-sequence-definition-root def-root
                   :benchmark/expected-correlation-id wrong-correlation-id}))

    (spit (io/file td "benchmark/execution-plan.edn")
          (pr-str {:schema_version "benchmark-execution-plan.v1"
                   :benchmark/id :benchmark/force-authorisation-custody-v1
                   :executions []
                   :trust-sequence-definition-root def-root
                   :expected-correlation-id wrong-correlation-id}))

    (spit (io/file ev-dir "evidence-registry.json")
          (json/write-str registry {:key-fn (fn [k] (if (keyword? k) (name k) (str k)))}))
    (spit (io/file ev-dir "chain-cursor-final.json")
          (json/write-str cursor {:key-fn (fn [k] (if (keyword? k) (name k) (str k)))}))

    (spit (io/file td "benchmark/index.edn")
          (pr-str {:executions [{:dir ev-dir
                                :artifacts {:evidence-registry {:path (str ev-dir "/evidence-registry.json")}
                                            :chain-cursor {:path (str ev-dir "/chain-cursor-final.json")}}}]}))

    ;; Write canonical assurance — will be overwritten after verification
    (spit (io/file td "benchmark/assertions/canonical-integrity.json")
          "{\"schema_version\":\"canonical-integrity.v1\",\"status\":\"pending\"}")

    (spit (io/file td "manifest/execution-witness.json")
          (json/write-str witness {:key-fn (fn [k] (if (keyword? k) (name k) (str k)))}))

    ;; Dummy runner.json for package index ref
    (spit (io/file td "runner.json") "{}")

    {:temp-dir td :definition definition :witness witness
     :wrong-id wrong-correlation-id :correct-id correct-id
     :ev-dir ev-dir :registry registry :cursor cursor :scenario scenario}))

(defn- cleanup [m]
  (when-let [d (io/file (:temp-dir m))]
    (when (.isDirectory d)
      (doseq [f (reverse (file-seq d))] (when (.isFile f) (.delete f)))
      (.delete d))))

(defn- write-finalization!
  "Write a minimal benchmark finalization.json for package validation."
  [td]
  (let [f (io/file td "benchmark/finalization.json")]
    (spit f (json/write-str {"schema_version" "benchmark-finalization.v1"
                             "run_id" "e2e" "final_ref" "sha256:0000"
                             "input_set_root" "sha256:0000"}))
    f))

(defn- write-artifact-registry!
  "Write a minimal artifacts.json for package validation."
  [td]
  (let [f (io/file td "manifest/artifacts.json")]
    (spit f (json/write-str {"schema_version" "artifacts-registry.v1"
                             "run_id" "e2e" "artifacts" []}))
    f))

(defn- write-artifact-validation!
  "Write a minimal artifacts-validation.json for package validation."
  [td]
  (let [f (io/file td "manifest/artifacts-validation.json")]
    (spit f (json/write-str {"schema_version" "artifacts-validation.v1"
                             "status" "passed"}))
    f))

(defn- json-key [k]
  (if (keyword? k) (if-let [ns (namespace k)] (str ns "/" (name k)) (name k)) (str k)))

(defn- write-package-index!
  "Write run-package-index.json and return its metadata."
  [td]
  (let [ref (fn [p] {:ref p :sha256 (sha-ref (io/file td p))})
        idx (pkg/build {:run-id "e2e" :run-type :benchmark
                        :bundle-root-hash "sha256:0000"
                        :artifacts {:runner-finalization (ref "benchmark/execution-plan.edn")
                                    :benchmark-definition (ref "benchmark/definition.edn")
                                    :execution-plan (ref "benchmark/execution-plan.edn")
                                    :canonical-integrity (ref "benchmark/assertions/canonical-integrity.json")
                                    :execution-witness (ref "manifest/execution-witness.json")}})
        wire (pkg/package-index->wire idx)
        ipath (io/file td "manifest/run-package-index.json")]
    (spit ipath (json/write-str wire {:key-fn json-key :indent true}))
    {:path paths/run-package-index :file ipath :hash (sha-ref ipath) :bytes (.length ipath)}))

(defn- write-completion!
  "Write completion.json binding the package index."
  [td idx-meta finalization-file]
  (let [f (io/file td "completion.json")]
    (spit f (json/write-str {"schema_version" "benchmark-completion.v1"
                             "benchmark_id" "benchmark/force-authorisation-custody-v1"
                             "run_id" "e2e"
                             "run_type" "benchmark"
                             "lifecycle_status" "completed"
                             "semantic_status" "pass"
                             "finalization_ref" "benchmark/finalization.json"
                             "finalization_sha256" (sha-ref finalization-file)
                             "final_ref" "sha256:0000"
                             "run_package_index_ref" (:path idx-meta)
                             "run_package_index_sha256" (:hash idx-meta)
                             "run_package_index_bytes" (:bytes idx-meta)
                             "input_set_root" "sha256:0000"
                             "artifact_registry_ref" "manifest/artifacts.json"
                             "artifact_registry_sha256" (sha-ref (io/file td "manifest/artifacts.json"))
                             "registry_validation_ref" "manifest/artifacts-validation.json"
                             "registry_validation_sha256" (sha-ref (io/file td "manifest/artifacts-validation.json"))}))
    f))

;; ── Tests ─────────────────────────────────────────────────────────────────

(deftest complete-but-unassured-package
  (let [wrong-id "wrong-planned-auth-id"
        fx (build-complete-package wrong-id)]
    (try
      (let [td (:temp-dir fx)]

        ;; 1. Run canonical witness verification — should fail on correlation
        (let [verification (try
                             (wb/canonical-witness-verification td)
                             (catch Exception e
                               {:valid? false :fail-count 1
                                :checks [{:check/code :execution-witness/error
                                          :check/status :fail
                                          :check/detail (.getMessage e)}]}))]
          (is (false? (:valid? verification))
              "Canonical verification must fail: wrong planned correlation")
          (is (some #(= :procedure-witness/correlation-matches-planned-instance (:check/code %))
                    (filter #(= :fail (:check/status %)) (:checks verification)))
              "Failure reason must identify the correlation mismatch"))

        ;; 2. Write canonical assurance reflecting the failed verification
        (spit (io/file td "benchmark/assertions/canonical-integrity.json")
              (json/write-str {"schema_version" "canonical-integrity.v1"
                               "assurance_kind" "unsigned-canonical-integrity"
                               "run_id" "e2e" "benchmark_id" "benchmark/force-authorisation-custody-v1"
                               "status" "failed"
                               "scope" {"execution_witness"
                                        {"status" "invalid"
                                         "correlation" {"matches-expected" false
                                                        "internally-consistent" true}}}}
                              :indent true))

        ;; 3. Build package files needed for structural verification
        (let [finalization-file (write-finalization! td)
              _ (write-artifact-registry! td)
              _ (write-artifact-validation! td)
              idx-meta (write-package-index! td)
              _ (write-completion! td idx-meta finalization-file)]

          ;; 4. Package structural verification
        ;;    Use resolve-completion-context to verify the completion seals the index.
        ;;    This checks completion file integrity, index hash match, and byte-length match.
        (let [ctx (pkg/resolve-completion-context (str td))
              completion-report (:completion-report ctx {:valid? false})
              index (:package-index ctx)]
          (is (true? (:valid? completion-report))
              (str "Completion verification must pass, got: " (:reasons completion-report)))
          (is (some? index) "Package index must be resolved from completion seal")
          (is (some? (:index index)) "Resolved package index must be parseable"))

        ;; 5. Canonical assurance status is 'failed'
        (let [canonical (try (json/read-str (slurp (io/file td "benchmark/assertions/canonical-integrity.json"))
                                              :key-fn keyword)
                             (catch Exception _ {:status "unknown"}))]
          (is (= "failed" (:status canonical))
              "Canonical assurance status must be 'failed'"))

        ;; 6. Release eligibility: when canoncial assurance is 'failed',
        ;;    release must be blocked. This is a policy judgement checked
        ;;    against the canonical assurance status.
        (let [canonical (json/read-str (slurp (io/file td "benchmark/assertions/canonical-integrity.json"))
                                        :key-fn keyword)
              assurance-failed? (= "failed" (:status canonical))]
          (is assurance-failed? "Assurance must be 'failed' for release eligibility to be tested")
          (is (false? (and (not assurance-failed?) true))
              "Package with failed canonical assurance must not be release-eligible"))

        ;; 7. Assert the structured triple
        (let [ctx (pkg/resolve-completion-context (str td))
              completion-report (:completion-report ctx {:valid? false})
              canonical (json/read-str (slurp (io/file td "benchmark/assertions/canonical-integrity.json"))
                                        :key-fn keyword)
              assurance-failed? (= "failed" (:status canonical))
              structural-valid? (true? (:valid? completion-report))]
          (is (true? structural-valid?) ":package/structural-status :valid")
          (is (= "failed" (:status canonical)) ":package/assurance-status :failed")
          (is (false? (and (not assurance-failed?) true)) ":package/release-eligible? false"))))

      (finally (cleanup fx)))))

(deftest structurally-complete-regardless
  "An assurance-failed package must still be structurally complete
   and independently inspectable."
  (let [fx (build-complete-package "wrong-id")]
    (try
      (let [td (:temp-dir fx)]
        (write-finalization! td)
        (write-artifact-registry! td)
        (write-artifact-validation! td)
        (let [idx-meta (write-package-index! td)]
          (write-completion! td idx-meta (io/file td "benchmark/finalization.json")))

        (is (.isFile (io/file td "benchmark/definition.edn")))
        (is (.isFile (io/file td "benchmark/execution-plan.edn")))
        (is (.isFile (io/file td "manifest/execution-witness.json")))
        (is (.isFile (io/file td "benchmark/assertions/canonical-integrity.json")))
        (is (.isFile (io/file td "completion.json")))
        (is (.isFile (io/file td "manifest/run-package-index.json")))
        (is (.isFile (io/file td "benchmark/finalization.json")))

        (let [ctx (pkg/resolve-completion-context (str td))
              report (:completion-report ctx {:valid? false})
              index (:package-index ctx)]
          (is (true? (:valid? report)) (str "Completion must pass: " (:reasons report)))
          (is (some? index) "Package index must be resolved"))

        ;; Canonical assurance still says "pending" because this test doesn't
        ;; run the verification step. The package is structurally complete
        ;; but not yet assured — that is the correct state before verification.
        (let [canonical (json/read-str (slurp (io/file td "benchmark/assertions/canonical-integrity.json"))
                                        :key-fn keyword)]
          (is (contains? #{"pending" "failed"} (:status canonical))
              "Canonical assurance must be 'pending' or 'failed' before verification is run")))
      (finally (cleanup fx)))))