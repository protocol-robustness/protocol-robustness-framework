(ns resolver-sim.benchmark.sharing-test
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.runner :as runner]
            [resolver-sim.benchmark.sharing :as sharing]
            [resolver-sim.hash.canonical :as hc]))

(defn- with-temp-evidence
  "Write `content` to a fresh temp evidence.edn and call f with its path."
  [content f]
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "benchmark-sharing-test-"
                      (make-array java.nio.file.attribute.FileAttribute 0)))
        path (.getPath (io/file dir "evidence.edn"))]
    (try
      (spit path content)
      (f path)
      (finally
        (shell/sh "rm" "-rf" (.getPath dir))))))

(deftest reads-evidence-with-object-tagged-literals
  (testing "Evidence bundles containing #object tagged literals (e.g. yield-module fns) can be read back for reproduction"
    (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                        "benchmark-sharing-test-"
                        (make-array java.nio.file.attribute.FileAttribute 0)))
          evidence-path (.getPath (io/file dir "evidence.edn"))]
      (try
        (spit evidence-path
              "{:evidence/hash \"abc\"
                :repo {:repo {:commit \"deadbeef\"}}
                :benchmark {:benchmark/id :benchmark/sew-yield-shortfall-v1
                            :manifest \"benchmarks/packs/sew/yield-shortfall-v1.edn\"}
                :metrics {:passed 15 :total 15}
                :results [{:scenario/id :s108-negative-yield-mild
                           :module #object[resolver_sim.yield.modules.liquid_lending$accrue 0x1 \"x\"]}]}")
        (let [evidence (sharing/read-evidence-file evidence-path)]
          (is (= "abc" (:evidence/hash evidence)))
          (is (= :benchmark/sew-yield-shortfall-v1
                 (get-in evidence [:benchmark :benchmark/id]))))
        (finally
          (shell/sh "rm" "-rf" (.getPath dir)))))))

(deftest export-creates-a-verified-portable-bundle
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "benchmark-sharing-test-"
                      (make-array java.nio.file.attribute.FileAttribute 0)))
        evidence-path (.getPath (io/file dir "evidence.edn"))
        export-path (.getPath (io/file dir "bundle.tar.gz"))]
    (try
      (spit evidence-path (pr-str {:benchmark {:benchmark/id :benchmark/test}
                                   :repo {:repo :test}
                                   :results [{:scenario/id :test}]
                                   :metrics {:passed 1 :total 1}}))
      (is (true? (sharing/export evidence-path export-path)))
      (let [{:keys [exit out]} (shell/sh "tar" "-tzf" export-path)]
        (is (zero? exit))
        (let [entries (set (str/split-lines out))]
          (is (every? entries #{"./" "./evidence.edn" "./manifest.edn" "./metrics.edn"
                                "./repo.edn" "./results.edn" "./evidence.edn.graph/evidence-graph.svg"}))))
      (finally
        (shell/sh "rm" "-rf" (.getPath dir))))))

(deftest publish-ipfs-handles-a-missing-cli
  (testing "A missing IPFS executable does not crash the benchmark CLI"
    (with-redefs [shell/sh (fn [& _]
                             (throw (java.io.IOException.
                                     "Cannot run program \"ipfs\": error=2")))]
      (is (nil? (sharing/publish-ipfs "bundle.tar.gz"))))))

(deftest publish-ipfs-handles-cli-failures
  (testing "IPFS command failures do not create a publication result"
    (with-redefs [shell/sh (fn [& _]
                             {:exit 1 :out "" :err "daemon is not running\n"})]
      (is (nil? (sharing/publish-ipfs "bundle.tar.gz"))))))

;; ── Object-tagged literal reader contract ─────────────────────────────────────
;; The #object reader is a compatibility measure for bundles serialized by
;; pr-str that embed runtime Clojure objects (e.g. yield-module fns, Instants).
;; It must be inert, predictable, and must never let an opaque object enter the
;; committed evidence hash. These tests pin that trust boundary.

(deftest rejects-malformed-object-tags-predictably
  (testing "A malformed #object tag fails predictably rather than yielding garbage"
    (with-temp-evidence "{:a #object[unterminated"
      (fn [path]
        (is (thrown? Exception (sharing/read-evidence-file path)))))))

(deftest rejects-unrelated-unknown-tags
  (testing "An unrelated unknown tagged literal is still rejected (no reader installed for it)"
    (with-temp-evidence "{:a #foo/bar 1}"
      (fn [path]
        (is (thrown? Exception (sharing/read-evidence-file path)))))))

(deftest object-tags-read-as-inert-legacy-sentinels
  (testing "Reading does not instantiate or execute anything; a #object becomes an unmistakable legacy sentinel map"
    (with-temp-evidence
      "{:module #object[resolver_sim.yield.modules.liquid_lending$accrue 0x1 \"x\"]}"
      (fn [path]
        (let [m (sharing/read-evidence-file path)]
          (is (map? (:module m)) "sentinel must be a map")
          (is (sharing/legacy-object? (:module m)) "sentinel must be detected as a legacy object")
          (is (= true (:legacy/runtime-object (:module m))))
          (is (= "resolver_sim.yield.modules.liquid_lending$accrue"
                 (:legacy/class (:module m))))
          (is (= "x" (:legacy/printed-representation (:module m))))
          (is (not (fn? (:module m))) "sentinel must not be a callable object"))))))

(deftest legacy-sentinel-is-unmistakable
  (testing "legacy-object? only matches the sentinel emitted by the object reader, never ordinary maps/vectors"
    (is (false? (sharing/legacy-object? nil)))
    (is (false? (sharing/legacy-object? [1 2 3])))
    (is (false? (sharing/legacy-object? {:module (fn [x] x)})))
    (is (false? (sharing/legacy-object? {:legacy/runtime-object false})))
    (is (true? (sharing/legacy-object?
                {:legacy/runtime-object true
                 :legacy/class "foo$bar"
                 :legacy/printed-representation "x"})))))

(deftest read-write-cycle-preserves-asserted-hash
  (testing "A read -> write -> read cycle does not silently change the asserted :evidence/hash"
    (let [content "{:evidence/hash \"committed-root\"
                    :results [{:scenario/id :x
                               :module #object[foo$bar 0x1 \"y\"]}]}"]
      (with-temp-evidence content
        (fn [path]
          (let [read1 (sharing/read-evidence-file path)
                                  ;; rewrite with the same writer used at build time
                rewritten (pr-str read1)]
            (with-temp-evidence rewritten
              (fn [path2]
                (let [read2 (sharing/read-evidence-file path2)]
                  (is (= "committed-root" (:evidence/hash read2)))
                  (is (= (:evidence/hash read1)
                         (:evidence/hash read2))))))))))))

(deftest opaque-fns-do-not-alter-committed-bundle-root-hash
  (testing "Runtime fn objects in :results normalize to a deterministic :type :fn marker, so their identity does not affect the bundle-root hash"
    (let [base {:benchmark {:benchmark/id :benchmark/sew-yield-shortfall-v1}
                :metrics {:passed 15 :total 15}
                :results [{:scenario/id :s108-negative-yield-mild :module (fn [x] x)}]}
          different-fn {:benchmark {:benchmark/id :benchmark/sew-yield-shortfall-v1}
                        :metrics {:passed 15 :total 15}
                        :results [{:scenario/id :s108-negative-yield-mild :module (fn [x] (* x 2))}]}]
      (is (= (hc/hash-with-intent {:hash/intent :bundle-root} base)
             (hc/hash-with-intent {:hash/intent :bundle-root} different-fn))))))

(defn- file-sha256 [file]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (with-open [in (java.io.FileInputStream. file)]
      (let [buffer (byte-array 8192)]
        (loop [n (.read in buffer)]
          (when (pos? n)
            (.update digest buffer 0 n)
            (recur (.read in buffer))))))
    (format "%064x" (java.math.BigInteger. 1 (.digest digest)))))

(deftest evidence-hash-is-semantic-not-file-bytes
  (testing "Two executions whose runtime fn objects have different JVM identities still produce the SAME
            semantic :evidence/hash (the reproducible bundle root), even though their raw file SHAs
            are transport checksums that are not the basis of the reproducibility verdict."
    (let [mk (fn [fn-obj]
               {:benchmark {:benchmark/id :benchmark/sew-yield-shortfall-v1}
                :metrics {:passed 15 :total 15}
                :results [{:scenario/id :s108-negative-yield-mild :module fn-obj}]})
          bundle-a (assoc (mk (fn [x] x)) :evidence/hash
                          (hc/hash-with-intent {:hash/intent :bundle-root} (mk (fn [x] x))))
          bundle-b (assoc (mk (fn [x] (* x 2))) :evidence/hash
                          (hc/hash-with-intent {:hash/intent :bundle-root} (mk (fn [x] (* x 2)))))
          a-dir (.toFile (java.nio.file.Files/createTempDirectory "two-commit-a-" (make-array java.nio.file.attribute.FileAttribute 0)))
          b-dir (.toFile (java.nio.file.Files/createTempDirectory "two-commit-b-" (make-array java.nio.file.attribute.FileAttribute 0)))
          a-file (io/file a-dir "evidence.edn")
          b-file (io/file b-dir "evidence.edn")
          cleanup (fn [] (shell/sh "rm" "-rf" (.getPath a-dir)) (shell/sh "rm" "-rf" (.getPath b-dir)))]
      (try
        (spit a-file (pr-str bundle-a))
        (spit b-file (pr-str bundle-b))
        (let [sem-a (:evidence/hash bundle-a)
              sem-b (:evidence/hash bundle-b)
              file-a (file-sha256 a-file)
              file-b (file-sha256 b-file)]
          (is (= sem-a sem-b) "semantic bundle-root hash must be identical across fn identities")
          (is (string? sem-a))
          (is (and (string? file-a) (string? file-b)) "file SHAs are transport checksums")
          (is (= sem-a sem-b)
              "reproducibility is judged by the semantic bundle-root hash, not by matching raw file bytes"))
        (finally
          (cleanup))))))

(deftest evidence-record-validation-rejects-runtime-functions  (testing ":evidence-record admission validation now rejects runtime fn objects
            (:functions is in :evidence-record :intent/excludes), closing the gap that
            previously let opaque functions pass. Finalized evidence schemas must not
            carry runtime values; this is the defense-in-depth complement to the
            writer-boundary descriptor serialization."
                                                                 (is (thrown? Exception
                                                                              (hc/validate-intent-constraints!
                                                                               :evidence-record
                                                                               {:attribution :a :action :deposit :result :ok
                                                                                :context {:module (fn [x] x)}}))
                                                                     "runtime fn in a finalized evidence record is now rejected")
                                                                 (is (nil? (hc/validate-intent-constraints!
                                                                            :evidence-record
                                                                            {:attribution :a :action :deposit :result :ok
                                                                             :context {:module-id :yield/liquid-lending}}))
                                                                     "a runtime-value-free evidence record still passes validation")))

(deftest writer-boundary-persists-no-runtime-functions
  (testing "The evidence writer converts yield-module runtime fns (:ops) into stable descriptors,
            so the persisted evidence.edn contains no #object[...] function tags and reads back
            without needing the legacy object reader."
    (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                        "writer-boundary-test-"
                        (make-array java.nio.file.attribute.FileAttribute 0)))
          evidence-path (.getPath (io/file dir "evidence.edn"))
          module (let [modules {:yield/modules
                                {:aave-v3 {:module/id :aave-v3
                                           :module/type :yield.profile/aave-v3-like
                                           :module/capabilities #{:deposit :withdraw :accrue}
                                           :accounting/type :yield/accounting
                                           :ops {:yield/deposit (fn [& _] :dep)
                                                 :yield/withdraw (fn [& _] :wd)
                                                 :yield/accrue (fn [& _] :ac)}}}}]
                   modules)]
      (try
        (runner/write-evidence
         {:benchmark {:benchmark/id :benchmark/sew-yield-shortfall-v1}
          :metrics {:passed 1 :total 1}
          :results [{:scenario/id :s108-negative-yield-mild
                     :world module}]}
         evidence-path)
        (let [raw (slurp evidence-path)]
          (is (not (re-find #"#object\[" raw))
              "persisted evidence must contain no #object[...] tags")
          (is (not (re-find #"\$accrue|\$deposit" raw))
              "no function class names may leak into persisted evidence")
          ;; The descriptor preserves module identity and op keys.
          (is (re-find #":module/id :aave-v3" raw))
          (is (re-find #":ops \[" raw)))
        (let [evidence (sharing/read-evidence-file evidence-path)
              world (get-in evidence [:results 0 :world])
              module (:aave-v3 (:yield/modules world))]
          (is (map? module))
          (is (= :aave-v3 (:module/id module)))
          (is (every? keyword? (:ops module))
              "ops are stable descriptor keys, not functions")
          (is (not-any? fn? (vals (dissoc module :ops)))
              "no runtime fn may remain in the persisted module descriptor"))
        (finally
          (shell/sh "rm" "-rf" (.getPath dir)))))))
