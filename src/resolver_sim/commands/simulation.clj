(ns resolver-sim.commands.simulation
  "Explicit reduced-fidelity Monte Carlo simulation entry points."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.core.phases :as phases]
            [resolver-sim.io.params :as params]
            [resolver-sim.forensic.source-hash :as source-hash])
  (:import [java.io File]
           [java.nio.file Files StandardCopyOption]
           [java.security MessageDigest]
           [java.time Instant]
           [java.util UUID]))

(defn- sha256-bytes ^String [^bytes bytes]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") bytes)]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) digest))))

(defn- sha256-file [^java.io.File file]
  (sha256-bytes (Files/readAllBytes (.toPath file))))

(defn- write-edn-atomically! [^File target value]
  (let [parent (.toPath (.getParentFile target))
        tmp (.resolve parent (str "." (.getName target) "." (UUID/randomUUID) ".tmp"))]
    (spit (.toFile tmp) (pr-str value))
    (try
      (Files/move tmp (.toPath target)
                  (into-array java.nio.file.CopyOption
                              [StandardCopyOption/ATOMIC_MOVE
                               StandardCopyOption/REPLACE_EXISTING]))
      (catch java.nio.file.AtomicMoveNotSupportedException _
        (Files/move tmp (.toPath target)
                    (into-array java.nio.file.CopyOption
                                [StandardCopyOption/REPLACE_EXISTING]))))))

(defn- output-digests [^File root]
  (->> (file-seq root)
       (filter #(.isFile ^File %))
       (remove #(= "selection.edn" (.getName ^File %)))
       (sort-by #(.getPath ^File %))
       (map (fn [^File file]
              [(subs (.getPath file) (inc (count (.getPath root))))
               (sha256-file file)]))
       (into (sorted-map))))

(defn- sha256-string [^String value]
  (sha256-bytes (.getBytes value "UTF-8")))

(defn- path-content-sha256 [^File file]
  (cond
    (.isFile file) (sha256-file file)
    (.isDirectory file)
    (->> (file-seq file)
         (filter #(.isFile ^File %))
         (sort-by #(.getPath ^File %))
         (map (fn [^File child]
                (str (.relativize (.toPath file) (.toPath child)) ":" (sha256-file child))))
         (str/join "\n")
         sha256-string)
    :else nil))

(defn- runtime-provenance [source]
  (let [repo-root (io/file (:vcs/root source))
        config-files (->> ["deps.edn" "bb.edn" "bb.lock" "deps-lock.edn"]
                          (map (fn [path] [path (io/file repo-root path)]))
                          (filter (fn [[_ file]] (.isFile ^File file)))
                          (map (fn [[path file]] [path (sha256-file file)]))
                          (into (sorted-map)))
        classpath (or (System/getProperty "java.class.path") "")
        classpath-entries (->> (str/split classpath
                                          (re-pattern (java.util.regex.Pattern/quote File/pathSeparator)))
                               (remove str/blank?)
                               vec)
        classpath-identities (mapv (fn [entry]
                                     (let [file (.getCanonicalFile (io/file entry))]
                                       {:path (.getPath file)
                                        :kind (cond (.isFile file) :file
                                                    (.isDirectory file) :directory
                                                    :else :missing)
                                        :content/sha256 (path-content-sha256 file)}))
                                   classpath-entries)]
    {:runtime/java {:version (System/getProperty "java.version")
                    :vendor (System/getProperty "java.vendor")
                    :vm (System/getProperty "java.vm.name")}
     :runtime/clojure {:version (clojure-version)}
     :runtime/os {:name (System/getProperty "os.name")
                  :version (System/getProperty "os.version")
                  :architecture (System/getProperty "os.arch")}
     :runtime/processor-count (.availableProcessors (Runtime/getRuntime))
     :runtime/classpath {:entries classpath-entries
                         :sha256 (sha256-string classpath)
                         :entry-identities classpath-identities}
     :runtime/dependency-config-sha256 config-files}))

(defn- run-simplified!
  "Run the closed-form Monte Carlo engine with a frozen parameter file and an
   adjacent selection record. This records what was selected and the files
   emitted; it does not claim replay, invariant, signature, or forensic-release
   qualification."
  [{:keys [params output]}]
  (when-not (and (string? params) (seq params))
    (throw (ex-info "Specify --params FILE" {:exit-code 2})))
  (when-not (and (string? output) (seq output))
    (throw (ex-info "Specify --output DIR" {:exit-code 2})))
  (let [input (.getCanonicalFile (io/file params))
        root (.getCanonicalFile (io/file output))
        root-path (.toPath root)
        inputs (io/file root "inputs")
        frozen-input (io/file inputs "params.edn")
        evidence (io/file root "selection.edn")
        results-dir (io/file root "simulation-results")
        run-id (str "simplified-mc-" (UUID/randomUUID))
        source (source-hash/source-hash)]
    (when-not (.isFile input)
      (throw (ex-info (str "Parameter file does not exist: " input) {:exit-code 2})))
    (when-not source
      (throw (ex-info "Cannot record simulation source provenance: no jj or git checkout found"
                      {:exit-code 2})))
    (let [parent (.getParentFile root)]
      (when parent (.mkdirs parent)))
    (try
      (Files/createDirectory root-path (make-array java.nio.file.attribute.FileAttribute 0))
      (catch java.nio.file.FileAlreadyExistsException _
        (throw (ex-info "Output directory must not already exist" {:exit-code 2}))))
    (.mkdirs inputs)
    (Files/copy (.toPath input) (.toPath frozen-input)
                (into-array java.nio.file.CopyOption []))
    (let [raw-hash (sha256-file frozen-input)
          base {:schema/version "simulation-selection.v2"
                :run/id run-id
                :selection/status :selected
                :command/path ["simulation" "simplified"]
                :command/arguments ["simulation" "simplified"
                                    "--params" params "--output" output]
                :command/resolved-paths {:params (.getPath input)
                                         :output (.getPath root)}
                :simulation/mode :simplified-monte-carlo
                :simulation/implementation 'resolver-sim.core.phases/run-simulation
                :simulation/scope "Closed-form payoff Monte Carlo; no step-by-step replay, invariant checks, or evidence chain."
                :input/path "inputs/params.edn"
                :input/sha256 raw-hash
                :source/provenance source
                :runtime/provenance (runtime-provenance source)
                :selected-at (str (Instant/now))}]
      (write-edn-atomically! evidence base)
      (try
        (let [effective-params (params/validate-and-merge (.getPath frozen-input))
              result (phases/run-simulation effective-params (.getPath results-dir))
              complete (assoc base
                              :selection/status :completed
                              :parameters/effective effective-params
                              :simulation/result result
                              :artifacts/sha256 (output-digests root)
                              :completed-at (str (Instant/now)))]
          (write-edn-atomically! evidence complete)
          (prn {:run/id run-id :simulation/mode :simplified-monte-carlo
                :selection/evidence (.getPath evidence)
                :input/sha256 raw-hash
                :result result})
          {:exit-code 0})
        (catch Exception e
          (write-edn-atomically! evidence
                                 (assoc base :selection/status :failed
                                        :failure/class (.getName (class e))
                                        :failure/message (.getMessage e)
                                        :artifacts/sha256 (output-digests root)
                                        :failed-at (str (Instant/now))))
          (throw e))))))

(defn simplified [opts]
  (try
    (run-simplified! opts)
    (catch Exception e
      (binding [*out* *err*]
        (println "Simplified simulation failed:" (.getMessage e)))
      {:exit-code (or (:exit-code (ex-data e)) 1)})))
