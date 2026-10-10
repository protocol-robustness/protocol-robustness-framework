(ns prf.pro-rata.conformance-runner
  "Standalone runner for frozen portable allocation roots and canonical bytes."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.test :as test :refer [deftest]]
            [prf.canonical.codec :as codec]
            [prf.canonical.hash :as canonical-hash]
            [prf.pro-rata.api :as api]
            [prf.pro-rata.identity :as identity]
            [prf.pro-rata.result :as result]))

(def ^:dynamic *fixture-root* nil)

(defn- sha256-hex [^bytes bytes]
  (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-256") bytes)]
    (apply str (map #(format "%02x" (bit-and 0xff %)) digest))))

(defn- fixture-edn [root relative-path]
  (edn/read-string (slurp (io/file root relative-path))))

(deftest portable-release-manifest-is-intact
  (let [manifest (fixture-edn *fixture-root* "manifest.edn")]
    (doseq [{:keys [path sha256]} (:files manifest)]
      (let [bytes (java.nio.file.Files/readAllBytes
                   (.toPath (io/file *fixture-root* path)))]
        (test/is (= sha256 (sha256-hex bytes)) (str "file digest: " path))))
    (test/is (= (:portable-contract/release-root manifest)
                (canonical-hash/hash-with-domain
                 "PORTABLE_PRO_RATA_CONTRACT_RELEASE_V1"
                 (dissoc manifest :portable-contract/release-root)))
             "portable release root")))

(deftest allocation-vectors-match-frozen-roots-and-bytes
  (let [vector-dir (io/file *fixture-root* "vectors")
        vector-files (->> (.listFiles vector-dir)
                          (filter #(and (.isFile %) (.endsWith (.getName %) ".edn")))
                          (sort-by #(.getName %)))]
    (doseq [file vector-files
            :let [{:keys [request expected]} (edn/read-string (slurp file))]
            :when (:canonical-bytes/file expected)]
      (let [allocation (api/allocate request)
            bytes-file (io/file *fixture-root* (:canonical-bytes/file expected))
            expected-bytes (str/trim (slurp bytes-file))]
        (test/is (= (:allocation/hash expected) (:allocation/hash allocation))
                 (str "allocation root: " (.getName file)))
        (test/is (= expected-bytes
                    (codec/canonical-bytes-hex (result/root-input allocation)))
                 (str "canonical result bytes: " (.getName file)))
        (when-let [expected-request-root (:request/hash expected)]
          (test/is (= expected-request-root
                      (identity/request-root (:canonical-request allocation)))
                   (str "request root: " (.getName file))))))))

(defn -main
  [& [fixture-root]]
  (let [fixture-root (io/file (or fixture-root "../../../portable-pro-rata-contract"))]
    (when-not (.isDirectory fixture-root)
      (throw (ex-info "Portable contract fixture directory not found"
                      {:fixture-root (.getPath fixture-root)})))
    (binding [*fixture-root* fixture-root]
      (let [summary (test/run-tests 'prf.pro-rata.conformance-runner)]
        (shutdown-agents)
        (System/exit (if (zero? (+ (:fail summary) (:error summary))) 0 1))))))
