(ns resolver-sim.benchmark.sharing-test
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.sharing :as sharing]))

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
