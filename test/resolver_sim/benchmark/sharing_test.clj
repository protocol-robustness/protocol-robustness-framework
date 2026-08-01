(ns resolver-sim.benchmark.sharing-test
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
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

(deftest object-tags-read-as-inert-placeholders
  (testing "Reading does not instantiate or execute anything; a #object becomes an inert vector"
    (with-temp-evidence
      "{:module #object[resolver_sim.yield.modules.liquid_lending$accrue 0x1 \"x\"]}"
      (fn [path]
        (let [m (sharing/read-evidence-file path)]
          (is (vector? (:module m)) "placeholder must be a plain vector")
          (is (not (fn? (:module m))) "placeholder must not be a callable object")
          (is (= 'resolver_sim.yield.modules.liquid_lending$accrue
                 (first (:module m)))))))))

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

(deftest admission-validation-does-not-currently-reject-opaque-objects
  (testing "Documented current behaviour: :evidence-record admission validation does not flag a runtime fn
            (functions are not in :evidence-record :intent/excludes). This is the gap the durable
            writer-boundary serialization should close."
    (let [record {:attribution :a :action :deposit :result :ok
                  :context {:module (fn [x] x)}}]
      (is (nil? (hc/validate-intent-constraints! :evidence-record record))
          "surfaces the current gap: opaque fn objects pass :evidence-record validation"))))
