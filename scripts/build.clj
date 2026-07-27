(ns build
  "Build the two supported PRF distributions.

   Variants:
     :prf — framework and unified CLI, with no Sew implementation or corpus
     :sew — Sew-enabled runner (corpus packaging migrates separately)

   Usage:
     clojure -T:build uberjar :variant prf
     clojure -T:build uberjar :variant sew"
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.tools.build.api :as b])
  (:import [java.nio.file Files StandardCopyOption]))

(def version "0.1.0")

(def ^:private sew-corpus-spec "resources/prf/sew-release-corpus.edn")

(defn- safe-relative-path! [path]
  (when (or (str/blank? path)
            (.isAbsolute (io/file path))
            (some #{".."} (str/split path #"[\\\\/]+")))
    (throw (ex-info "Unsafe Sew release corpus path" {:path path})))
  path)

(defn- sha256-file [file]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (with-open [in (io/input-stream file)]
      (let [buffer (byte-array 8192)]
        (loop [read (.read in buffer)]
          (when (pos? read)
            (.update digest buffer 0 read)
            (recur (.read in buffer))))))
    (format "%064x" (java.math.BigInteger. 1 (.digest digest)))))

(defn- write-distribution-provenance! [jar-file variant main-class]
  (let [jar (io/file jar-file)
        target (io/file (str jar-file ".provenance.json"))
        value {"schema_version" "prf-distribution-provenance.v1"
               "variant" (name variant)
               "version" version
               "main_class" (str main-class)
               "jar_file" (.getName jar)
               "jar_sha256" (str "sha256:" (sha256-file jar))
               "built_at" (str (java.time.Instant/now))}]
    (spit target (json/write-str value))
    (println "  Wrote distribution provenance:" (.getName target))
    value))

(defn- corpus-entry-files [entry]
  (let [paths (:paths entry)
        files (:files entry)
        root (:root entry)]
    (if (or paths files)
      (mapv (fn [{:keys [source path]}]
              (safe-relative-path! source)
              (safe-relative-path! path)
              (let [file (io/file source)]
                (when-not (.isFile file)
                  (throw (ex-info "Declared Sew release corpus file is missing" {:path source})))
                [(:kind entry) path file]))
            (or files (mapv (fn [path] {:source path :path path}) paths)))
      (let [root (safe-relative-path! root)
            directory (io/file root)
            extensions (set (:extensions entry))
            filenames (set (:filenames entry))]
        (when-not (.isDirectory directory)
          (throw (ex-info "Declared Sew release corpus directory is missing" {:root root})))
        (for [file (file-seq directory)
              :when (and (.isFile file)
                         (let [name (.getName file)]
                           (or (filenames name)
                               (some #(.endsWith name ^String %) extensions))))]
          [(:kind entry) (str file) file])))))

(defn- package-sew-corpus! [class-dir]
  (let [spec (edn/read-string (slurp sew-corpus-spec))
        files (->> (:entries spec)
                   (mapcat corpus-entry-files)
                   (sort-by second)
                   vec)
        paths (map second files)]
    (when-not (= (count paths) (count (set paths)))
      (throw (ex-info "Sew release corpus selected duplicate paths" {:paths paths})))
    (doseq [[_ path file] files]
      (let [target (io/file class-dir path)]
        (.mkdirs (.getParentFile target))
        (Files/copy (.toPath file) (.toPath target)
                    (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))))
    (let [manifest {:schema_version "sew-corpus-manifest.v1"
                    :source_spec "prf/sew-release-corpus.edn"
                    :artifacts (mapv (fn [[kind path file]]
                                       {:path path :kind kind
                                        :sha256 (sha256-file file) :bytes (.length file)})
                                     files)}
          output (io/file class-dir "META-INF/prf/sew-corpus-manifest.json")]
      (.mkdirs (.getParentFile output))
      (spit output (json/write-str manifest))
      (println "  Packaged Sew release corpus:" (count files) "files")
      manifest)))

(defn- validate-source-sew-corpus! []
  (let [expression "(require 'resolver-sim.benchmark.corpus-validation) (println (resolver-sim.benchmark.corpus-validation/validate-corpus!))"
        result (shell/sh "clojure" "-M:with-sew" "-e" expression)]
    (when-not (zero? (:exit result))
      (throw (ex-info "Source Sew corpus validation failed"
                      {:exit (:exit result) :out (:out result) :err (:err result)})))
    (println "  Source Sew corpus validation:" (clojure.string/trim (:out result)))
    result))

(defn- validate-built-sew-jar! [uber-file]
  (let [expression "(require 'resolver-sim.benchmark.corpus-validation) (println (resolver-sim.benchmark.corpus-validation/validate-corpus!))"
        result (shell/sh "java" "-cp" uber-file "clojure.main" "-e" expression)]
    (when-not (zero? (:exit result))
      (throw (ex-info "Built Sew JAR corpus validation failed"
                      {:jar uber-file :exit (:exit result) :out (:out result) :err (:err result)})))
    (println "  Built Sew JAR corpus validation:" (clojure.string/trim (:out result)))
    result))

(defn uberjar
  [{:keys [variant main]
    :or   {variant "sew"
           main   nil}}]
  (let [variant (cond
                  (keyword? variant) variant
                  (string? variant) (keyword variant)
                  (instance? clojure.lang.Symbol variant) (keyword (name variant))
                  :else (throw (ex-info "Unsupported variant type"
                                        {:variant variant :type (type variant)
                                         :supported [:prf :sew]})))
        _ (when-not (#{:prf :sew} variant)
            (throw (ex-info "Unknown JAR build variant" {:variant variant :supported [:prf :sew]})))
        vname (name variant)
        is-prf (= variant :prf)
        is-sew (= variant :sew)
        main-cls (or main "resolver-sim.cli.main")
        lib (symbol (str "resolver-sim/prf-runner-" vname))

        ;; Build deps file for clean classpath
core-deps-str (pr-str
                       '{:deps {org.clojure/clojure {:mvn/version "1.12.0"}
                                org.clojure/tools.logging {:mvn/version "1.2.4"}
                                org.slf4j/slf4j-api {:mvn/version "1.7.36"}
                                org.slf4j/slf4j-simple {:mvn/version "1.7.36"}
                                org.clojure/tools.cli {:mvn/version "1.0.219"}
                                org.clojure/data.json {:mvn/version "2.4.0"}
                                buddy/buddy-core {:mvn/version "1.12.0-430"}
                                com.github.seancorfield/next.jdbc {:mvn/version "1.3.939"}
                                org.postgresql/postgresql {:mvn/version "42.7.2"}
                                metosin/malli {:mvn/version "0.17.0"}
                                org.clojure/core.async {:mvn/version "1.9.865"}
                                io.grpc/grpc-netty-shaded {:mvn/version "1.64.0"}
                                io.grpc/grpc-stub {:mvn/version "1.64.0"}
                                scicloj/tablecloth {:mvn/version "7.029.2"}}
                        :paths ["src" "resources"]})
        sew-deps-str (pr-str
                      '{:deps {org.clojure/clojure {:mvn/version "1.12.0"}
                               org.clojure/tools.logging {:mvn/version "1.2.4"}
                               org.slf4j/slf4j-api {:mvn/version "1.7.36"}
                               org.slf4j/slf4j-simple {:mvn/version "1.7.36"}
                               org.clojure/tools.cli {:mvn/version "1.0.219"}
                               org.clojure/data.json {:mvn/version "2.4.0"}
                               buddy/buddy-core {:mvn/version "1.12.0-430"}
                               com.github.seancorfield/next.jdbc {:mvn/version "1.3.939"}
                               org.postgresql/postgresql {:mvn/version "42.7.2"}
                               metosin/malli {:mvn/version "0.17.0"}}
                        :paths ["src" "protocols_src" "resources"]})
        deps-str (if is-prf core-deps-str sew-deps-str)
        deps-path (str (System/getProperty "java.io.tmpdir")
                       "/prf-build-deps-" (System/nanoTime) ".edn")
        _ (spit deps-path deps-str)
        basis (b/create-basis {:project deps-path})
        src-dirs (if is-prf ["src" "resources" "scenarios"] ["src" "protocols_src" "resources" "scenarios"])
        class-dir (str (System/getProperty "java.io.tmpdir")
                       "/prf-build-" (System/nanoTime))
        jar-file (if is-prf "target/prf.jar" (str "target/prf-runner-" vname "-" version ".jar"))
        uber-file (if is-prf "target/prf-uber.jar" (str "target/prf-runner-" vname "-" version "-uber.jar"))]

    (println "\n=== Build: prf-runner-" vname " ===")
    (printf "  Main class: %s\n" main-cls)
    (printf "  Source dirs: %s\n" (pr-str src-dirs))

    ;; Copy source + resources to class dir
    (println "\n  Copying source...")
    (doseq [sd src-dirs]
      (let [d (java.io.File. sd)]
        (when (.exists d)
          (printf "    %s\n" sd)
          (b/copy-dir {:src-dirs [sd] :target-dir class-dir}))))
    ;; Remove test directories from class-dir to prevent AOT from picking them up
    (doseq [test-dir ["test" "protocols_src/test"]]
      (let [td (java.io.File. class-dir test-dir)]
        (when (.exists td)
          (printf "  Removing test dir: %s\n" test-dir)
          (b/delete {:path (str td)}))))
    ;; Copy data dirs preserving directory name (b/copy-dir flattens contents,
    ;; so copy each dir into a subdirectory of class-dir).
    ;; data/ and config/ are classpath resources accessed via io/resource
    ;; (not listed in the build :paths since they live at repository root).
    (doseq [extra-dir ["data" "config" "resources/prf"]]
      (let [d (java.io.File. extra-dir)]
        (when (.exists d)
          (printf "    %s/ -> class-dir/%s/\n" extra-dir extra-dir)
          (.mkdirs (java.io.File. class-dir extra-dir))
          (b/copy-dir {:src-dirs [extra-dir]
                       :target-dir (str class-dir "/" extra-dir)}))))

    ;; The Sew archive packages an explicit, publishable runtime corpus. This
    ;; intentionally excludes source-tree miscellany such as notebooks, docs,
    ;; archived benchmarks, local configs, and unlisted data roots.
    (when is-sew
      (println "  Validating source Sew benchmark corpus...")
      (validate-source-sew-corpus!)
      (package-sew-corpus! class-dir))

    ;; Build manifest for the JAR
    ;; Add a marker file to indicate this is a source-only JAR
    (.mkdirs (java.io.File. (str class-dir "/META-INF")))
    (spit (str class-dir "/META-INF/prf-runner.edn")
          (pr-str {:variant vname :main main-cls :version version :source-only true
                   :built-at (str (java.time.Instant/now))}))

    ;; PRF variant: AOT compile the unified CLI bootstrapper (no protocol deps),
    ;; then build standalone uberjar with Main-Class pointing at it.
    ;; Sew variant: source-only build using clojure.main as Main-Class.
    (let [prf-build? (= variant :prf)]
    (if prf-build?
      ;; PRF variant: AOT compile the unified CLI bootstrapper (no protocol deps),
      ;; then build standalone uberjar with Main-Class pointing at it.
      (let [main-sym 'resolver-sim.cli-bootstrap
            bs-deps (pr-str '{:deps {org.clojure/clojure {:mvn/version "1.12.0"}}
                           :paths ["scripts/cli-bootstrap"]})
            bs-deps-path (str (System/getProperty "java.io.tmpdir")
                              "/prf-bs-deps-" (System/nanoTime) ".edn")]
        (spit bs-deps-path bs-deps)
        (let [bs-basis (b/create-basis {:project bs-deps-path})]
          (b/compile-clj {:basis bs-basis
                          :src-dirs ["scripts/cli-bootstrap"]
                          :class-dir class-dir
                          :ns-compile-command ['resolver-sim.cli-bootstrap]}))
        (io/delete-file bs-deps-path)
        (println "  Building CLI uberjar (Main-Class:" main-sym ")...")
        (b/uber {:class-dir class-dir
                 :uber-file "target/prf.jar"
                 :basis basis
                 :main main-sym}))
      ;; Sew variant: source-only build using clojure.main as Main-Class.
      ;; This allows running via: java -jar ... -m resolver-sim.minimal-runner
      (let [main-sym 'clojure.main]
        (println "  Building source JAR (Main-Class:" main-sym ")...")
        (b/jar {:class-dir class-dir
                :jar-file jar-file
                :lib lib
                :version version
                :main main-sym})

        (println "  Building source uberjar (Main-Class:" main-sym ")...")
        (b/uber {:class-dir class-dir
                 :uber-file uber-file
                 :basis basis
                 :main main-sym}))))

    (when is-sew
          (validate-built-sew-jar! uber-file))

        (let [actual-main (if is-sew 'clojure.main main-cls)]
          (doseq [file (if is-prf ["target/prf.jar"] [jar-file uber-file])]
            (write-distribution-provenance! file variant actual-main)))

        ;; Cleanup
    (b/delete {:path class-dir})
    (io/delete-file deps-path)

    ;; Report
    (println "\n=== Results ===")
    (doseq [f (if is-prf ["target/prf.jar"] [jar-file uber-file])]
      (let [jf (java.io.File. f)]
        (when (.exists jf)
          (printf "  %-50s %d KB\n" (.getName jf) (quot (.length jf) 1024)))))
    (if is-prf
      (printf "\n  AOT bootstrapper compiled for JAR Main-Class.\n")
      (printf "\n  Source-only JAR with clojure.main as Main-Class.\n"))
    (println "  Done.\n")
    (flush)))

(defn aot-sew
  "AOT-compile Sew protocol source dirs only (protocols_src, then src)
   for faster test startup.  Writes .class files to target/classes.
   
   Uses separate passes so a failure in src (deeper deps) doesn't
   block the protocol layer compilation.
   
   Usage: clojure -T:build aot-sew"
  [_]
  (let [basis    (b/create-basis {:project "deps.edn"
                                  :aliases [:test :with-sew]})
        class-dir "target/classes"]
    (.mkdirs (java.io.File. class-dir))
    (println "\n=== AOT compile Sew protocol sources ===")
    (doseq [src-dir ["protocols_src" "src"]]
      (let [d (java.io.File. src-dir)]
        (when (.exists d)
          (try
            (println (str "  Compiling " src-dir "..."))
            (b/compile-clj {:basis basis
                            :src-dirs [src-dir]
                            :class-dir class-dir})
            (println (str "    OK: " src-dir))
            (catch Exception e
              (println (str "    WARN: " src-dir " — " (.getMessage e))))))))
    (println "\n  Done.\n")))
