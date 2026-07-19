(ns resolver-sim.run.distribution-provenance
  "Release-distribution identity for verdict provenance."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
                        [clojure.string :as str]
                        [resolver-sim.commands.run-lifecycle :as lifecycle]))

(def schema-version "prf-distribution-provenance.v1")

(defn- jar-entry []
  (some (fn [entry]
          (let [file (io/file entry)]
            (when (and (.isFile file)
                       (.endsWith (.getName file) ".jar"))
              (try
                (with-open [jar (java.util.jar.JarFile. file)]
                  (when (.getJarEntry jar "META-INF/prf-runner.edn") file))
                (catch Exception _ nil)))))
        (str/split (System/getProperty "java.class.path" "")
                              (re-pattern (java.util.regex.Pattern/quote java.io.File/pathSeparator)))))

(defn distribution-identity []
  (if-let [jar (jar-entry)]
    (let [sidecar (io/file (str (.getPath jar) ".provenance.json"))]
      (if (.isFile sidecar)
        (let [manifest (json/read-str (slurp sidecar))
                      jar-sha (str "sha256:" (lifecycle/sha256-file jar))]
                  {"mode" (if (= jar-sha (get manifest "jar_sha256")) "release-distribution" "unverified-distribution")
                   "jar_path" (.getName jar)
                   "jar_sha256" jar-sha
                   "build_manifest" manifest
                   "reason" (when-not (= jar-sha (get manifest "jar_sha256")) "build-manifest-jar-hash-mismatch")})
        {"mode" "unverified-distribution"
         "jar_path" (.getName jar)
         "jar_sha256" (str "sha256:" (lifecycle/sha256-file jar))
         "reason" "missing-build-provenance-sidecar"}))
    {"mode" "source-classpath"
     "reason" "no-prf-distribution-jar-on-classpath"}))

(defn release-verified? [identity]
  (= "release-distribution" (get identity "mode")))
