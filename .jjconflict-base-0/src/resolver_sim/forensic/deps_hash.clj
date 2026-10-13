(ns resolver-sim.forensic.deps-hash
  "Dependency classpath hashing for forensic integrity.
   Resolves the full classpath, hashes each JAR, and produces a deps root hash.
   Used by bb hash:deps and by pre-run commitment generation."
  (:require [clojure.string :as str])
  (:import [java.security MessageDigest]))

(defn- sha256-file
  "Compute SHA-256 hex of a file's content."
  [path]
  (let [f (java.io.File. path)]
    (when (.isFile f)
      (let [digest (MessageDigest/getInstance "SHA-256")]
        (.update digest (java.nio.file.Files/readAllBytes (.toPath f)))
        (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest digest)))))))

(defn resolve-classpath
  "Resolve the classpath from the current JVM.
   Returns a sorted sequence of JAR file paths."
  []
  (->> (str/split (System/getProperty "java.class.path") #":")
       (filter #(.endsWith % ".jar"))
       (map #(.getAbsolutePath (java.io.File. %)))
       (sort)))

(defn deps-hash
  "Hash all dependency JARs on the classpath.
   Returns {:deps/deps-edn-hash, :deps/jars [{:jar, :size, :sha256}...], :deps/root-hash}."
  []
  (let [deps-edn (java.io.File. "deps.edn")
        deps-edn-hash (when (.exists deps-edn) (sha256-file (.getPath deps-edn)))
        jars (or (resolve-classpath) [])
        jar-entries (mapv (fn [j]
                            (let [f (java.io.File. j)]
                              {:jar (.getName f)
                               :size (.length f)
                               :sha256 (sha256-file j)}))
                          (take 500 jars))
        ;; Root hash: SHA-256 of sorted canonical JSON of all entries
        root-str (pr-str (sort-by :jar jar-entries))
        root-hash (let [digest (MessageDigest/getInstance "SHA-256")]
                    (.update digest (.getBytes root-str "UTF-8"))
                    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest digest))))]
    {:deps/deps-edn-hash deps-edn-hash
     :deps/jars jar-entries
     :deps/jar-count (count jar-entries)
     :deps/root-hash root-hash}))

(defn hash-deps
  "CLI entry point: print dependency hashes as EDN.  Returns exit code."
  [& _]
  (let [result (deps-hash)]
    (println (pr-str result))
    0))
