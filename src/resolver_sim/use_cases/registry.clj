(ns resolver-sim.use-cases.registry
  "Explicit, fail-closed loading for externally supplied use-case registries.

   Use-case content is never discovered from the classpath, current directory,
   or a default location. Callers must provide a registry path explicitly."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.concepts.registry :as concepts]))

(def registry-schema :prf/use-case-registry.v1)

(defn- fail [message data]
  (throw (ex-info message (assoc data :error :use-cases/invalid-registry))))

(defn- canonical-file [path]
  (.getCanonicalFile (io/file path)))

(defn- read-edn-file! [file kind]
  (when-not (.isFile file)
    (fail (str (name kind) " file not found") {:kind kind :path (.getPath file)}))
  (try
    (edn/read-string (slurp file))
    (catch Exception e
      (fail (str "Unable to read " (name kind) " EDN")
            {:kind kind :path (.getPath file) :cause (.getMessage e)}))))

(defn- contained-file! [registry-file ref]
  (when-not (and (string? ref) (not (.isAbsolute (io/file ref))))
    (fail "Use-case :definition/ref must be a relative string"
          {:registry/path (.getPath registry-file) :definition/ref ref}))
  (let [base (.getCanonicalFile (.getParentFile registry-file))
        definition (canonical-file (io/file base ref))
        base-path (.getPath base)
        definition-path (.getPath definition)]
    (when-not (or (= base-path definition-path)
                  (.startsWith definition-path (str base-path java.io.File/separator)))
      (fail "Use-case definition escapes its registry directory"
            {:registry/path (.getPath registry-file) :definition/ref ref}))
    definition))

(defn- canonical-root-value
  "Project EDN collection types into the canonical hash domain without changing
   the loaded definition. Sets are unordered in EDN, so their members become a
   deterministically ordered vector in the root preimage."
  [value]
  (cond
    (set? value) (->> value (map canonical-root-value) (sort-by pr-str) vec)
    (map? value) (into {} (map (fn [[k v]] [k (canonical-root-value v)])) value)
    (sequential? value) (mapv canonical-root-value value)
    :else value))

(defn- validate-registry! [registry registry-file]
  (when-not (= registry-schema (:schema/id registry))
    (fail "Unsupported use-case registry schema"
          {:registry/path (.getPath registry-file) :expected registry-schema :actual (:schema/id registry)}))
  (doseq [key [:registry/id :registry/version :use-cases]]
    (when-not (contains? registry key)
      (fail "Use-case registry is missing required key" {:registry/path (.getPath registry-file) :missing key})))
  (when-not (and (string? (:registry/id registry)) (not-empty (:registry/id registry)))
    (fail "Use-case registry :registry/id must be a non-empty string" {:registry/path (.getPath registry-file)}))
  (when-not (and (string? (:registry/version registry)) (not-empty (:registry/version registry)))
    (fail "Use-case registry :registry/version must be a non-empty string" {:registry/path (.getPath registry-file)}))
  (when-not (vector? (:use-cases registry))
    (fail "Use-case registry :use-cases must be a vector" {:registry/path (.getPath registry-file)})))

(defn load-use-case-registry
  "Load one externally supplied :prf/use-case-registry.v1 registry.

   `registry-path` is required and must identify an existing filesystem file.
   Definitions resolve only relative to that file and must remain inside its
   directory. The returned root commits registry metadata and definitions."
  [registry-path]
  (when-not (and (string? registry-path) (not-empty registry-path))
    (fail "An explicit use-case registry path is required" {:registry/path registry-path}))
  (let [registry-file (canonical-file registry-path)
        registry (read-edn-file! registry-file :registry)
        _ (validate-registry! registry registry-file)
        loaded (mapv (fn [{:keys [use-case/id definition/ref] :as entry}]
                       (when-not (keyword? id)
                         (fail "Use-case entry :use-case/id must be a keyword" {:registry/path (.getPath registry-file) :entry entry}))
                       (let [definition-file (contained-file! registry-file ref)
                             definition (read-edn-file! definition-file :definition)]
                         (when-not (= id (:concept/id definition))
                           (fail "Use-case entry ID does not match definition" {:registry/path (.getPath registry-file) :use-case/id id :concept/id (:concept/id definition)}))
                         (when-not (= :use-case (:concept/type definition))
                           (fail "Use-case definition must declare :concept/type :use-case" {:registry/path (.getPath registry-file) :use-case/id id :concept/type (:concept/type definition)}))
                         {:use-case/id id :definition/ref ref :definition definition}))
                     (:use-cases registry))
        ids (mapv :use-case/id loaded)
        integrity (concepts/registry-integrity-violations
                   (mapv (fn [{:keys [use-case/id definition/ref]}]
                           {:concept/id id :concept/file ref})
                         loaded)
                   (mapv :definition loaded))]
    (when-not (= (count ids) (count (set ids)))
      (fail "Duplicate :use-case/id in registry" {:registry/path (.getPath registry-file) :ids ids}))
    (when (seq integrity)
      (fail "Use-case definitions failed schema validation"
            {:registry/path (.getPath registry-file) :violations integrity}))
    (let [root-input {:schema/id (:schema/id registry) :registry/id (:registry/id registry)
                      :registry/version (:registry/version registry)
                      :use-cases (mapv #(select-keys % [:use-case/id :definition/ref :definition]) loaded)}
          root-input (canonical-root-value root-input)]
      {:use-case-registry/id (:registry/id registry)
       :use-case-registry/version (:registry/version registry)
       :use-case-registry/schema (:schema/id registry)
       :use-case-registry/source :external
       :use-case-registry/path (.getPath registry-file)
       :use-case-registry/root (hash-ref/sha256-ref (canonical/domain-hash "USE_CASE_REGISTRY_V1" root-input))
       :use-case-registry/count (count loaded)
       :use-cases (mapv :definition loaded)})))

(defn use-case-index [loaded]
  (into {} (map (juxt :concept/id identity) (:use-cases loaded))))
