(ns scripts.test-run-root-staging
  "Verify PARALLEL_TEST_RUN_ROOT staging for the parallel runners:

     * per-namespace roots land under the staging dir with _owner.edn +
       _manifest.json,
     * staged roots are left in place (no auto-cleanup),
     * the leak tripwire passes even when staging overlaps the shared artifact
       dir,
     * the artifact collector's container mode discovers the staged roots as
       producers.

   Run: clojure -M:test:with-sew -i scripts/test_run_root_staging.clj"
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]))

(def pass (atom 0))
(def fail (atom 0))

(defn check
  ([label ok] (check label ok ""))
  ([label ok detail]
   (if ok
     (do (swap! pass inc) (println "  PASS:" label))
     (do (swap! fail inc) (println "  FAIL:" label " — " detail)))))

(def base-tmp (str (System/getProperty "java.io.tmpdir")
                   "/run-root-staging-test-" (java.util.UUID/randomUUID)))
(io/make-parents (io/file base-tmp "x"))

(defn run-clj
  "Run a clojure -M:test:with-sew invocation with the given env overrides.
   Returns the clojure.java.shell result map."
  [cmd-args overrides]
  (let [env (merge (into {} (System/getenv)) overrides)]
    (apply sh/sh (conj (vec (concat ["clojure" "-M:test:with-sew"] cmd-args))
                       :env env))))

(defn marked-subdirs
  "Immediate subdirectories of root that carry an ownership marker."
  [root]
  (->> (io/file root) .listFiles
       (filter #(and (.isDirectory %)
                     (.exists (io/file % "_owner.edn"))))
       sort
       vec))

;; ── 1. scripts.parallel-test-runner staging ──────────────────────────────────

(let [stage (str base-tmp "/ptr-stage")
      _ (.mkdirs (io/file stage))
      res (run-clj ["-m" "scripts.parallel-test-runner" "--noop-capture"
                    "resolver-sim.time.context-test"]
                   {"PARALLEL_TEST_RUN_ROOT" stage
                    "PRF_ARTIFACT_DIR" stage
                    "PARALLEL_TEST_LEAK_CHECK" "1"
                    "PARALLEL_TEST_JOBS" "2"})]
  (check "PTR-1: runner exits 0 (leak tripwire passes while staging under the artifact dir)"
         (zero? (:exit res))
         (str (:exit res) "\n" (:out res) "\n" (:err res)))
  (let [roots (marked-subdirs stage)]
    (check "PTR-2: per-namespace roots staged under PARALLEL_TEST_RUN_ROOT"
           (seq roots)
           (str (mapv str roots)))
    (let [root (first roots)]
      (when root
        (check "PTR-3: _owner.edn written" (.exists (io/file root "_owner.edn")))
        (check "PTR-4: _manifest.json written" (.exists (io/file root "_manifest.json"))))))
  (check "PTR-5: staged root left in place (no auto-cleanup)" (.exists (io/file stage))))

;; ── 2. scripts.run-sew-tests staging ─────────────────────────────────────────

(let [stage (str base-tmp "/sew-stage")
      _ (.mkdirs (io/file stage))
      res (run-clj ["-m" "scripts.run-sew-tests" "unit"]
                   {"PARALLEL_TEST_RUN_ROOT" stage
                    "PRF_ARTIFACT_DIR" stage
                    "SEW_TEST_LEAK_CHECK" "1"
                    "SEW_TEST_MODE" "isolated-sequential"
                    "SEW_TEST_NS_LIST" "[resolver-sim.protocols.sew.alias-test]"})]
  (check "SEW-1: runner exits 0 (leak tripwire passes while staging under the artifact dir)"
         (zero? (:exit res))
         (str (:exit res) "\n" (:out res) "\n" (:err res)))
  (let [roots (marked-subdirs stage)]
    (check "SEW-2: per-namespace roots staged under PARALLEL_TEST_RUN_ROOT"
           (seq roots)
           (str (mapv str roots)))
    (let [root (first roots)]
      (when root
        (check "SEW-3: _owner.edn written" (.exists (io/file root "_owner.edn")))
        (check "SEW-4: _manifest.json written" (.exists (io/file root "_manifest.json"))))))
  (check "SEW-5: staged root left in place (no auto-cleanup)" (.exists (io/file stage))))

;; ── 3. collector consumes a runner-shaped staged root ────────────────────────

(let [stage (str base-tmp "/col-stage")
      ns-root (str stage "/000-resolver-sim.e2e-test")
      out-root (str base-tmp "/col-out")
      _ (.mkdirs (io/file ns-root))
      _ (spit (io/file ns-root "_owner.edn")
              (pr-str {:artifact-root-format 1
                       :run-id "staging-test" :namespace 'resolver-sim.e2e-test}))
      _ (spit (io/file ns-root "_manifest.json")
              "{\"scope-status\": \"complete\", \"artifacts\": []}")
      _ (spit (io/file ns-root "test-summary.json")
              "{\"schema_version\": \"test-summary.v2\", \"run_id\": \"staging-test\"}")
      _ (spit (io/file ns-root "test-run.json")
              "{\"schema_version\": \"test-run.v1\", \"run_id\": \"staging-test\"}")
      res (sh/sh "python3"
                 (str (System/getProperty "user.dir")
                      "/scripts/evidence/consolidate_test_artifacts.py")
                 "--run-root" out-root
                 "--producer-roots" stage
                 "--run-id" "staging-test")]
  (check "COL-1: collector discovers staged roots in container mode"
         (zero? (:exit res))
         (str (:exit res) "\n" (:out res) "\n" (:err res)))
  (let [reg (io/file out-root "test-artifacts.json")]
    (check "COL-2: unified registry written" (.exists reg))))

;; ── summary + cleanup ────────────────────────────────────────────────────────

(println)
(println (str "=== run-root-staging: " @pass " passed, " @fail " failed ==="))
(doseq [f (reverse (doall (file-seq (io/file base-tmp))))]
  (.delete f))
(when (pos? @fail)
  (System/exit 1))
