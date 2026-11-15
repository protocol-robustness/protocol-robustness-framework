(ns resolver-sim.concepts.bundles
  "Deterministic loading and content-rooting for declarative concept bundles.

   This is an opt-in bundle API. The legacy concepts.registry remains the
   compatibility catalogue until callers deliberately migrate to bundles."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as reference]))

(def bundle-registry-schema 1)
(def bundle-schema "prf.concept-bundle.v1")
(def bundle-domain "PRF_CONCEPT_BUNDLE_ROOT_V1")
(def catalogue-domain "PRF_CONCEPT_BUNDLE_CATALOGUE_ROOT_V1")
(def default-bundle-registry "data/concept-bundles/registry.edn")

(def ^:private registry-fields
  #{:concept-bundle-registry/version :concept-bundle-registry/bundles})
(def ^:private registry-entry-fields #{:bundle/id :bundle/owner :bundle/manifest})
(def ^:private manifest-fields
  #{:bundle/schema :bundle/id :bundle/version
    :bundle/catalogue-schema-version :bundle/status :bundle/owner
    :bundle/dependencies :bundle/concepts :bundle/mapping-files})
(def ^:private concept-entry-fields #{:concept/id :concept/file})
(def ^:private mapping-entry-fields #{:mapping/protocol :mapping/file})
(def ^:private dependency-fields #{:bundle/id :bundle/version})
(def ^:dynamic ^:private *bundle-root* ".")

(defn- fail [reason message data]
  (throw (ex-info message (assoc data :concept-bundles/error reason))))

(defn- classpath-path [path]
  (let [candidate (java.nio.file.Paths/get path (make-array String 0))]
    (when (or (.isAbsolute candidate)
              (some #(= ".." (str %)) candidate)
              (str/includes? path "\\"))
      (fail :path-outside-root "Concept bundle resource path is not confined"
            {:path path}))
    (str candidate)))

(defn- confined-file [path]
  (let [candidate (java.nio.file.Paths/get path (make-array String 0))
        root (.toRealPath (java.nio.file.Paths/get *bundle-root* (make-array String 0))
                          (make-array java.nio.file.LinkOption 0))]
    (when (or (.isAbsolute candidate)
              (some #(= ".." (str %)) candidate)
              (str/includes? path "\\"))
      (fail :path-outside-root "Concept bundle file path is not confined"
            {:path path :root (str root)}))
    (let [resolved (.normalize (.resolve root candidate))]
      (if (java.nio.file.Files/exists resolved
                                      (make-array java.nio.file.LinkOption 0))
        (let [real-path (.toRealPath resolved (make-array java.nio.file.LinkOption 0))]
          (when-not (.startsWith real-path root)
            (fail :path-outside-root "Concept bundle file resolves outside its root"
                  {:path path :root (str root) :resolved (str real-path)}))
          real-path)
        resolved))))

(defn- read-edn-file [path]
  (let [resource-path (when (str/starts-with? path "resource:")
                        (classpath-path (subs path (count "resource:"))))
        file (when-not resource-path (confined-file path))]
    (cond
      resource-path
      (if-let [resource (io/resource resource-path)]
        (edn/read-string (slurp resource))
        (fail :missing-file "Concept bundle classpath resource is missing"
              {:path path}))
      (java.nio.file.Files/isRegularFile file (make-array java.nio.file.LinkOption 0))
      (edn/read-string (slurp (.toFile file)))
      (io/resource (classpath-path path))
      (edn/read-string (slurp (io/resource (classpath-path path))))
      :else (fail :missing-file "Concept bundle file is missing" {:path path}))))

(defn- file-content-root [path]
  (let [resource-path (when (str/starts-with? path "resource:")
                        (classpath-path (subs path (count "resource:"))))
        file (when-not resource-path (confined-file path))
        bytes (cond
                resource-path
                (if-let [resource (io/resource resource-path)]
                  (with-open [input (io/input-stream resource)] (.readAllBytes input))
                  (fail :missing-file "Concept bundle classpath resource is missing"
                        {:path path}))
                (java.nio.file.Files/isRegularFile file (make-array java.nio.file.LinkOption 0))
                (java.nio.file.Files/readAllBytes file)
                (io/resource (classpath-path path))
                (with-open [input (io/input-stream (io/resource (classpath-path path)))]
                  (.readAllBytes input))
                :else (fail :missing-file "Concept bundle content file is missing" {:path path}))
        digest (.digest (doto (java.security.MessageDigest/getInstance "SHA-256")
                          (.update bytes)))
        hex (apply str (map #(format "%02x" (bit-and % 0xff)) digest))]
    (reference/sha256-ref hex)))

(defn- require-exact-fields! [value fields path]
  (when-not (and (map? value) (= fields (set (keys value))))
    (fail :invalid-shape "Concept bundle has missing or unsupported fields"
          {:path path :expected fields :actual (when (map? value) (set (keys value)))})))

(defn- validate-manifest! [manifest path]
  (require-exact-fields! manifest manifest-fields path)
  (when-not (and (= bundle-schema (:bundle/schema manifest))
                 (keyword? (:bundle/id manifest))
                 (string? (:bundle/version manifest))
                 (= 1 (:bundle/catalogue-schema-version manifest))
                 (keyword? (:bundle/status manifest))
                 (keyword? (:bundle/owner manifest))
                 (vector? (:bundle/dependencies manifest))
                 (vector? (:bundle/concepts manifest))
                 (vector? (:bundle/mapping-files manifest)))
    (fail :invalid-manifest "Concept bundle manifest metadata is invalid"
          {:path path :bundle/id (:bundle/id manifest)}))
  (doseq [dependency (:bundle/dependencies manifest)]
    (require-exact-fields! dependency dependency-fields path)
    (when-not (and (keyword? (:bundle/id dependency))
                   (string? (:bundle/version dependency)))
      (fail :invalid-dependency "Concept bundle dependency must pin an ID and version"
            {:path path :dependency dependency})))
  (doseq [entry (:bundle/concepts manifest)]
    (require-exact-fields! entry concept-entry-fields path)
    (when-not (and (keyword? (:concept/id entry)) (string? (:concept/file entry)))
      (fail :invalid-concept-entry "Concept bundle entry is malformed"
            {:path path :entry entry})))
  (doseq [entry (:bundle/mapping-files manifest)]
    (require-exact-fields! entry mapping-entry-fields path)
    (when-not (and (keyword? (:mapping/protocol entry))
                   (string? (:mapping/file entry)))
      (fail :invalid-mapping-entry "Concept bundle mapping entry is malformed"
            {:path path :entry entry})))
  (when-not (= (mapv :concept/id (:bundle/concepts manifest))
               (vec (sort-by str (map :concept/id (:bundle/concepts manifest)))))
    (fail :noncanonical-order "Concept bundle concepts must be sorted by ID"
          {:path path :bundle/id (:bundle/id manifest)}))
  (when-not (= (mapv :mapping/protocol (:bundle/mapping-files manifest))
               (vec (sort-by str (map :mapping/protocol (:bundle/mapping-files manifest)))))
    (fail :noncanonical-order "Concept bundle mappings must be sorted by protocol"
          {:path path :bundle/id (:bundle/id manifest)}))
  (when-not (= (mapv :bundle/id (:bundle/dependencies manifest))
               (vec (sort-by str (map :bundle/id (:bundle/dependencies manifest)))))
    (fail :noncanonical-order "Concept bundle dependencies must be sorted by ID"
          {:path path :bundle/id (:bundle/id manifest)}))
  (when-not (= (count (:bundle/dependencies manifest))
               (count (distinct (map :bundle/id (:bundle/dependencies manifest)))))
    (fail :duplicate-dependency "Concept bundle repeats a dependency ID"
          {:path path :bundle/id (:bundle/id manifest)}))
  (doseq [entries [(:bundle/concepts manifest) (:bundle/mapping-files manifest)]
          :let [paths (map #(or (:concept/file %) (:mapping/file %)) entries)]
          :when (not= (count paths) (count (distinct paths)))]
    (fail :duplicate-file "Concept bundle repeats a source file"
          {:path path :bundle/id (:bundle/id manifest)}))
  manifest)

(defn- manifest-content [manifest]
  (let [concepts (mapv (fn [{:keys [concept/id concept/file]}]
                         {:concept/id id :concept/file file
                          :concept/content-root (file-content-root file)})
                       (:bundle/concepts manifest))
        mappings (mapv (fn [{:keys [mapping/protocol mapping/file]}]
                         {:mapping/protocol protocol
                          :mapping/file file
                          :mapping/content-root (file-content-root file)})
                       (:bundle/mapping-files manifest))]
    {:bundle/schema (:bundle/schema manifest)
     :bundle/id (:bundle/id manifest)
     :bundle/version (:bundle/version manifest)
     :bundle/catalogue-schema-version (:bundle/catalogue-schema-version manifest)
     :bundle/status (:bundle/status manifest)
     :bundle/owner (:bundle/owner manifest)
     :bundle/dependencies (:bundle/dependencies manifest)
     :bundle/concepts concepts
     :bundle/mapping-files mappings}))

(defn bundle-root [manifest]
  (reference/sha256-ref
   (canonical/domain-hash bundle-domain (manifest-content manifest))))

(defn- topo-sort [bundles]
  (let [by-id (into {} (map (juxt :bundle/id identity) bundles))]
    (doseq [bundle bundles
            {:keys [bundle/id bundle/version]} (:bundle/dependencies bundle)]
      (let [dependency (get by-id id)]
        (when-not dependency
          (fail :missing-dependency "Concept bundle dependency is not present"
                {:bundle/id (:bundle/id bundle) :dependency id}))
        (when-not (= version (:bundle/version dependency))
          (fail :dependency-version-mismatch "Concept bundle dependency version does not match"
                {:bundle/id (:bundle/id bundle) :dependency id
                 :required version :available (:bundle/version dependency)}))))
    (loop [pending (set (keys by-id)) completed #{} ordered []]
      (if (empty? pending)
        ordered
        (let [ready (->> pending
                         (filter (fn [id]
                                   (every? #(contains? completed (:bundle/id %))
                                           (:bundle/dependencies (get by-id id)))))
                         (sort-by str)
                         vec)]
          (when (empty? ready)
            (fail :dependency-cycle "Concept bundle dependencies contain a cycle"
                  {:remaining (vec (sort-by str pending))}))
          (recur (apply disj pending ready) (into completed ready)
                 (into ordered (map by-id ready))))))))

(defn load-bundles
  "Load an explicit concept bundle registry, validate pinned dependencies,
   compute each bundle root and return a deterministic assembled catalogue.

   File paths in the registry and manifests are resolved under `root` (the
   current directory by default); absolute paths, traversal, and symlink
   escapes are rejected. Use the two-argument form when loading a registry
   rooted outside the current directory.

   This API does not replace the legacy catalogue loader and does not imply
   that any bundle is published, trusted, or authoritative for execution."
  ([] (load-bundles default-bundle-registry))
  ([registry-path]
   (load-bundles registry-path "."))
  ([registry-path root]
   (binding [*bundle-root* (str root)]
     (let [registry (read-edn-file registry-path)]
       (require-exact-fields! registry registry-fields registry-path)
       (when-not (and (= bundle-registry-schema (:concept-bundle-registry/version registry))
                      (vector? (:concept-bundle-registry/bundles registry)))
         (fail :invalid-registry "Concept bundle registry metadata is invalid"
               {:path registry-path}))
       (let [entries (:concept-bundle-registry/bundles registry)]
         (doseq [entry entries]
           (require-exact-fields! entry registry-entry-fields registry-path))
         (when-not (= (mapv :bundle/id entries)
                      (vec (sort-by str (map :bundle/id entries))))
           (fail :noncanonical-order "Concept bundle registry must be sorted by ID"
                 {:path registry-path}))
         (let [bundles
               (mapv (fn [{:keys [bundle/id bundle/owner bundle/manifest]}]
                       (let [manifest (validate-manifest! (read-edn-file manifest) manifest)]
                         (when-not (and (= id (:bundle/id manifest))
                                        (= owner (:bundle/owner manifest)))
                           (fail :registry-manifest-mismatch
                                 "Bundle registry entry does not match its manifest"
                                 {:manifest manifest :registry-entry {:bundle/id id
                                                                      :bundle/owner owner}}))
                         (let [content (manifest-content manifest)]
                           (assoc manifest :bundle/content-root
                                  (reference/sha256-ref
                                   (canonical/domain-hash bundle-domain content))))))
                     entries)
               ids (mapv :bundle/id bundles)]
           (when-not (= (count ids) (count (distinct ids)))
             (fail :duplicate-bundle-id "Concept bundle registry contains duplicate IDs"
                   {:bundle/ids ids}))
           (let [ordered (topo-sort bundles)
                 all-concepts (vec
                               (mapcat (fn [bundle]
                                         (map (fn [{:keys [concept/id concept/file]}]
                                                (let [concept (read-edn-file file)]
                                                  (when-not (= id (:concept/id concept))
                                                    (fail :concept-id-mismatch
                                                          "Concept file ID does not match its bundle entry"
                                                          {:bundle/id (:bundle/id bundle)
                                                           :declared id
                                                           :actual (:concept/id concept)}))
                                                  (assoc concept :concept/owner (:bundle/owner bundle))))
                                              (:bundle/concepts bundle)))
                                       ordered))
                 all-mapping-files (vec
                                    (mapcat (fn [bundle]
                                              (map (fn [{:keys [mapping/protocol mapping/file]}]
                                                     (let [mapping-set (read-edn-file file)]
                                                       (when-not (= protocol (:mapping-set/protocol mapping-set))
                                                         (fail :mapping-protocol-mismatch
                                                               "Concept mapping set protocol does not match its bundle manifest"
                                                               {:bundle/id (:bundle/id bundle)
                                                                :declared protocol
                                                                :actual (:mapping-set/protocol mapping-set)}))
                                                       mapping-set))
                                                   (:bundle/mapping-files bundle)))
                                            ordered))
                 concept-ids (mapv :concept/id all-concepts)
                 mapping-protocols (mapv :mapping-set/protocol all-mapping-files)]
             (when-not (= (count concept-ids) (count (distinct concept-ids)))
               (fail :duplicate-concept-id "Concept IDs collide across bundles"
                     {:concept/ids concept-ids}))
             (when-not (= (count mapping-protocols) (count (distinct mapping-protocols)))
               (fail :duplicate-mapping-owner "Mapping protocols are owned by multiple bundles"
                     {:mapping/protocols mapping-protocols}))
             {:concept-bundle-catalogue/root
              (reference/sha256-ref
               (canonical/domain-hash catalogue-domain
                                      {:bundles (mapv (juxt :bundle/id :bundle/version
                                                            :bundle/content-root)
                                                      ordered)}))
              :concept-bundle-catalogue/publication-status :local-unpublished
              :concept-bundle-catalogue/bundles ordered
              :concept-bundle-catalogue/concepts all-concepts
              :concept-bundle-catalogue/mapping-files all-mapping-files})))))))
