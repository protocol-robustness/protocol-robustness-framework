(ns scripts.test-smart
  "Run-tools for local development — failed, changed, and smart test reruns.

   Usage:
     clojure -M -m scripts.test-smart failed
     clojure -M -m scripts.test-smart changed [<git-ref>]
     clojure -M -m scripts.test-smart smart [<git-ref>]

   These are local-only helpers, not release gates."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [clojure.test :as t]
            [scripts.test-state :as ts]))

;; ── Helpers ───────────────────────────────────────────────────────────────────

(defn- namespace-for-file
  "Map a source file path to its probable test namespace.
   src/resolver_sim/foo/bar.clj → resolver-sim.foo.bar-test
   handles src/, test/, and .clj/.cljc extensions."
  [path]
  (let [no-ext (str/replace path #"\.[^.]*$" "")
        no-src (str/replace no-ext #"^(src|test)/" "")
        ns-sym (symbol (str/replace no-src "/" "-"))]
    ;; Check if a corresponding test namespace exists
    (let [test-ns (symbol (str ns-sym "-test"))
          test-file (str "test/" (str/replace (name test-ns) "-" "_") ".clj")]
      (when (.exists (io/file test-file))
        test-ns))))

(defn- changed-files
  "Return list of files changed relative to a git ref (default: origin/main)."
  ([]
   (changed-files "origin/main"))
  ([git-ref]
   (let [result (sh "git" "diff" "--name-only" git-ref "--" "src/" "test/")]
     (if (zero? (:exit result))
       (str/split-lines (str/trim (:out result)))
       (let [result2 (sh "git" "diff" "--name-only" "HEAD~1" "--" "src/" "test/")]
         (if (zero? (:exit result2))
           (str/split-lines (str/trim (:out result2)))
           []))))))

(defn- changed-test-namespaces
  "Return test namespaces affected by changed files."
  ([]
   (changed-test-namespaces "origin/main"))
  ([git-ref]
   (->> (changed-files git-ref)
        (map namespace-for-file)
        (remove nil?)
        distinct
        sort
        vec)))

;; ── Commands ──────────────────────────────────────────────────────────────────

(defn run-failed
  "Rerun namespaces that failed in the last completed test run."
  []
  (if-let [state (ts/read-state)]
    (let [failed (:failed-test-namespaces state)]
      (if (seq failed)
        (do
          (println "Rerunning" (count failed) "previously failed namespace(s):")
          (doseq [ns failed] (println (str "  " ns)))
          (println)
          (flush)
          (apply require failed)
          (let [result (apply t/run-tests failed)]
            (when (pos? (+ (:fail result) (:error result)))
              (System/exit 1))))
        (println "No failed tests to rerun.")))
    (do
      (println "No test state found. Run a full test suite first.")
      (println)
      (println "Available test commands:")
      (println "  bb test            — all targets")
      (println "  bb test:unit       — unit tests (framework + Sew)")
      (println "  bb test:framework  — framework-only")
      (println "  bb test:sew        — Sew unit tests")
      (println "  bb test:invariants — invariant scenarios"))))

(defn run-changed
  "Run tests for namespaces affected by changed files."
  ([]
   (run-changed "origin/main"))
  ([git-ref]
   (let [nses (changed-test-namespaces git-ref)]
     (if (seq nses)
       (do
         (println (str "Running " (count nses) " namespace(s) affected by changes relative to " git-ref ":"))
         (doseq [ns nses] (println (str "  " ns)))
         (println)
         (flush)
         (apply require nses)
          (let [result (apply t/run-tests nses)]
           (when (pos? (+ (:fail result) (:error result)))
             (System/exit 1))))
       (println "No test namespaces affected by changed files.")))))

(defn run-smart
  "Prefer previous failures; fall back to changed-file tests."
  ([]
   (run-smart "origin/main"))
  ([git-ref]
   (if-let [state (ts/read-state)]
     (let [failed (:failed-test-namespaces state)]
       (if (seq failed)
         (do (println "Running" (count failed) "previously failed namespace(s)...")
             (run-failed))
         (do (println "No previous failures. Running changed-file tests...")
             (run-changed git-ref))))
     (do (println "No previous test state. Running changed-file tests...")
         (run-changed git-ref)))))

(defn -main
  [& args]
  (let [mode (first args)]
    (case mode
      "failed" (run-failed)
      "changed" (run-changed (or (second args) "origin/main"))
      "smart" (run-smart (or (second args) "origin/main"))
      (println "Usage: clojure -M -m scripts.test-smart <failed|changed|smart> [git-ref]"))))
