(ns scripts.test-manifest-export
  "Verify artifact-scope/finalize-scope!/mark-incomplete! write the
   machine-readable ``_manifest.json`` JSON export consumed by
   scripts/evidence/consolidate_test_artifacts.py.

   Run: clojure -M:test:with-sew -i scripts/test_manifest_export.clj"
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [scripts.artifact-scope :as scope]))

(def pass (atom 0))
(def fail (atom 0))

(defn check
  ([label ok] (check label ok ""))
  ([label ok detail]
   (if ok
     (do (swap! pass inc) (println "  PASS:" label))
     (do (swap! fail inc) (println "  FAIL:" label " — " detail)))))

(def tmp-root (str (System/getProperty "java.io.tmpdir")
                   "/manifest-export-test-" (java.util.UUID/randomUUID)))

;; ── 1. success path: complete manifest with published artifacts ─────────────

(let [ns-root (str tmp-root "/ns-a")
      scope-cfg {:run-id "run-1" :namespace 'resolver-sim.example-test
                 :namespace-root ns-root :scope-id "run-1-0"}
      [result scope]
      (scope/with-scope scope-cfg
        (fn []
          (let [h1 (:hash (scope/write! {:logical-id :test-summary
                                         :relative-path "results/test-artifacts/test-summary.json"
                                         :kind :summary
                                         :content "{\"schema_version\":\"test-summary.v2\"}"}))
                h2 (:hash (scope/write! {:logical-id :theory-eval
                                         :relative-path "results/test-artifacts/theory-eval.json"
                                         :kind :theory-eval
                                         :content "{\"schema_version\":\"theory-eval.v1\"}"}))]
            {:h1 h1 :h2 h2})))
      manifest (scope/finalize-scope! scope)]
  (check "1a: finalize returns complete"
         (= :complete (:scope-status manifest))
         (pr-str (:scope-status manifest)))
  (let [f (io/file ns-root "_manifest.json")]
    (check "1b: _manifest.json written" (.exists f))
    (when (.exists f)
      (let [m (json/read-str (slurp f))
            arts (get m "artifacts")
            ts (first (filter #(= "test-summary" (get % "logical-id")) arts))]
        (check "1c: run-id" (= "run-1" (get m "run-id")) (pr-str (get m "run-id")))
        (check "1d: namespace" (= "resolver-sim.example-test" (get m "namespace"))
               (pr-str (get m "namespace")))
        (check "1e: scope-status" (= "complete" (get m "scope-status"))
               (pr-str (get m "scope-status")))
        (check "1f: two artifacts" (= 2 (count arts)) (pr-str (count arts)))
        (check "1g: test-summary entry present" (some? ts))
        (when ts
          (check "1h: relative-path"
                 (= "results/test-artifacts/test-summary.json" (get ts "relative-path"))
                 (pr-str (get ts "relative-path")))
          (check "1i: content-hash matches write! hash"
                 (= (:h1 result) (get ts "content-hash"))
                 (str (:h1 result) " vs " (get ts "content-hash")))
          (check "1j: kind stringified" (= "summary" (get ts "kind"))
                 (pr-str (get ts "kind")))
          (check "1k: size matches on-disk file"
                 (= (.length (io/file ns-root "results/test-artifacts/test-summary.json"))
                    (get ts "size"))
                 (pr-str (get ts "size"))))))))

;; ── 2. hard-problem path: strict finalize with an undeclared file ───────────

(let [ns-root (str tmp-root "/ns-b")
      scope-cfg {:run-id "run-2" :namespace 'resolver-sim.example-2
                 :namespace-root ns-root :scope-id "run-2-0"}
      [_ scope]
      (scope/with-scope scope-cfg
        (fn []
          (scope/write! {:logical-id :test-summary
                         :relative-path "test-summary.json"
                         :content "x"})
          (spit (io/file ns-root "stray.json") "{\"undeclared\":true}")))
      threw (try (scope/finalize-scope! scope true)
                 false
                 (catch clojure.lang.ExceptionInfo e
                   (check "2a: strict finalize rejects undeclared file"
                          (= :incomplete (:scope-status (:manifest (ex-data e))))
                          (pr-str (select-keys (:manifest (ex-data e))
                                               [:scope-status :undeclared-files])))
                   true))]
  (check "2b: strict finalize throws on undeclared file" threw "did not throw")
  (let [f (io/file ns-root "_manifest.json")]
    (check "2c: incomplete _manifest.json written" (.exists f))
    (when (.exists f)
      (check "2d: incomplete status recorded"
             (= "incomplete" (get (json/read-str (slurp f)) "scope-status"))
             (pr-str (get (json/read-str (slurp f)) "scope-status"))))))

;; ── 3. mark-incomplete! path ────────────────────────────────────────────────

(let [ns-root (str tmp-root "/ns-c")
      scope-cfg {:run-id "run-3" :namespace 'resolver-sim.example-3
                 :namespace-root ns-root :scope-id "run-3-0"}
      [_ scope]
      (scope/with-scope scope-cfg
        (fn [] (scope/write! {:logical-id :test-summary
                              :relative-path "test-summary.json"
                              :content "y"})))
      manifest (scope/mark-incomplete! scope)]
  (check "3a: mark-incomplete! returns incomplete"
         (= :incomplete (:scope-status manifest))
         (pr-str (:scope-status manifest)))
  (let [f (io/file ns-root "_manifest.json")]
    (check "3b: mark-incomplete! writes _manifest.json" (.exists f))
    (when (.exists f)
      (let [m (json/read-str (slurp f))]
        (check "3c: incomplete status" (= "incomplete" (get m "scope-status"))
               (pr-str (get m "scope-status")))
        (check "3d: published artifact preserved" (= 1 (count (get m "artifacts")))
               (pr-str (get m "artifacts")))))))

;; ── 4. empty scope (runners that never call write!) ─────────────────────────

(let [ns-root (str tmp-root "/ns-d")
      scope-cfg {:run-id "run-4" :namespace 'resolver-sim.example-4
                 :namespace-root ns-root :scope-id "run-4-0"}
      [_ scope] (scope/with-scope scope-cfg (fn [] :done))
      manifest (scope/finalize-scope! scope)]
  (check "4a: complete scope" (= :complete (:scope-status manifest))
         (pr-str (:scope-status manifest)))
  (let [m (json/read-str (slurp (io/file ns-root "_manifest.json")))]
    (check "4b: empty artifacts array" (= [] (get m "artifacts"))
           (pr-str (get m "artifacts")))))

;; ── summary + cleanup ────────────────────────────────────────────────────────

(println)
(println (str "=== manifest-export: " @pass " passed, " @fail " failed ==="))
(doseq [f (reverse (doall (file-seq (io/file tmp-root))))]
  (.delete f))
(when (pos? @fail)
  (System/exit 1))
