(ns resolver-sim.concepts.bundles-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [resolver-sim.concepts.bundles :as bundles])
  (:import [java.nio.file Files]))

(defn- with-temp-dir [f]
  (let [directory (Files/createTempDirectory "concept-bundles-test-"
                                             (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (f (.toFile directory))
      (finally
        (doseq [file (reverse (file-seq (.toFile directory)))]
          (.delete file))))))

(defn- write-edn! [directory name value]
  (let [file (io/file directory name)]
    (.mkdirs (.getParentFile file))
    (spit file (pr-str value))
    (.getName file)))

(defn- concept-bundle
  [{:keys [id owner version dependencies concepts mapping-files]}]
  {:bundle/schema "prf.concept-bundle.v1"
   :bundle/id id
   :bundle/version (or version "0.1.0")
   :bundle/catalogue-schema-version 1
   :bundle/status :incubating
   :bundle/owner owner
   :bundle/dependencies (or dependencies [])
   :bundle/concepts (or concepts [])
   :bundle/mapping-files (or mapping-files [])})

(defn- write-catalogue! [directory bundle-specs]
  (let [entries
        (mapv (fn [{:keys [id owner] :as spec}]
                (let [path (write-edn! directory (str (name id) ".edn")
                                       (concept-bundle spec))]
                  {:bundle/id id :bundle/owner owner :bundle/manifest path}))
              (sort-by (comp str :id) bundle-specs))
        path (write-edn! directory "registry.edn"
                         {:concept-bundle-registry/version 1
                          :concept-bundle-registry/bundles entries})]
    path))

(defn- minimal-bundle-spec [directory id owner]
  (let [concept-id (keyword (name id) "example")
        concept-file (write-edn! directory (str (name id) "-concept.edn")
                                 {:concept/id concept-id :concept/protocols #{:protocol/prf}})]
    {:id id :owner owner
     :concepts [{:concept/id concept-id :concept/file concept-file}]}))

(deftest bundled-catalogue-roots-are-deterministic-and-byte-sensitive
  (testing "the checked-in logical bundles assemble without publishing them"
    (let [first-load (bundles/load-bundles)
          second-load (bundles/load-bundles)]
      (is (= (:concept-bundle-catalogue/root first-load)
             (:concept-bundle-catalogue/root second-load)))
      (is (= 3 (count (:concept-bundle-catalogue/bundles first-load))))
      (is (= 50 (count (:concept-bundle-catalogue/concepts first-load))))
      (is (= :local-unpublished
             (:concept-bundle-catalogue/publication-status first-load)))))
  (testing "a source-byte change changes the bundle and catalogue roots"
    (with-temp-dir
      (fn [directory]
        (let [{:keys [concepts] :as spec}
              (minimal-bundle-spec directory :test.bundle/a :shared)
              registry-path (write-catalogue! directory [spec])
              before (bundles/load-bundles registry-path directory)]
          (write-edn! directory (.getName (io/file (:concept/file (first concepts))))
                      {:concept/id (:concept/id (first concepts))
                       :concept/protocols #{:protocol/prf}
                       :concept/summary "changed"})
          (let [after (bundles/load-bundles registry-path directory)]
            (is (not= (:concept-bundle-catalogue/root before)
                      (:concept-bundle-catalogue/root after)))
            (is (not= (:bundle/content-root
                       (first (:concept-bundle-catalogue/bundles before)))
                      (:bundle/content-root
                       (first (:concept-bundle-catalogue/bundles after)))))))))))

(deftest bundle-dependencies-are-version-pinned-and-acyclic
  (with-temp-dir
    (fn [directory]
      (let [a (minimal-bundle-spec directory :test.bundle/a :shared)
            b (assoc (minimal-bundle-spec directory :test.bundle/b :protocol/prf)
                     :dependencies [{:bundle/id :test.bundle/a :bundle/version "9.9.9"}])
            registry-path (write-catalogue! directory [a b])]
        (is (= :dependency-version-mismatch
               (:concept-bundles/error
                (ex-data (try (bundles/load-bundles registry-path directory)
                              (catch clojure.lang.ExceptionInfo e e))))))
        (let [cyclic-a (assoc a :dependencies
                              [{:bundle/id :test.bundle/b :bundle/version "0.1.0"}])
              cyclic-b (assoc b :dependencies
                              [{:bundle/id :test.bundle/a :bundle/version "0.1.0"}])
              cyclic-path (write-catalogue! directory [cyclic-a cyclic-b])]
          (is (= :dependency-cycle
                 (:concept-bundles/error
                  (ex-data (try (bundles/load-bundles cyclic-path directory)
                                (catch clojure.lang.ExceptionInfo e e)))))))))))

(deftest bundle-merge-rejects-duplicate-concept-identifiers
  (with-temp-dir
    (fn [directory]
      (let [a (minimal-bundle-spec directory :test.bundle/a :shared)
            b (minimal-bundle-spec directory :test.bundle/b :protocol/prf)
            duplicate-id (get-in a [:concepts 0 :concept/id])
            _ (write-edn! directory "b-concept.edn"
                          {:concept/id duplicate-id :concept/protocols #{:protocol/prf}})
            b (assoc-in b [:concepts 0 :concept/id] duplicate-id)
            registry-path (write-catalogue! directory [a b])
            error (try (bundles/load-bundles registry-path directory)
                       nil
                       (catch clojure.lang.ExceptionInfo e e))]
        (is (= :duplicate-concept-id (:concept-bundles/error (ex-data error))))))))

(deftest bundle-file-paths-cannot-escape-the-explicit-root
  (with-temp-dir
    (fn [directory]
      (let [outside (Files/createTempFile "concept-bundle-outside-" ".edn"
                                          (make-array java.nio.file.attribute.FileAttribute 0))
            _written (spit (.toFile outside) (pr-str {:concept/id :test.bundle/example}))
            symlink (.resolve (.toPath directory) "outside-link.edn")
            _symlink (Files/createSymbolicLink symlink (.toAbsolutePath outside)
                                               (make-array java.nio.file.attribute.FileAttribute 0))
            traversal-path (str "../" (.getFileName outside))
            paths [(str (.toAbsolutePath outside)) traversal-path "outside-link.edn"]]
        (try
          (doseq [[index path] (map-indexed vector paths)]
            (let [spec {:id :test.bundle/a :owner :shared
                        :concepts [{:concept/id :test.bundle/example
                                    :concept/file path}]}
                  registry-path (write-catalogue! directory [spec])
                  error (try (bundles/load-bundles registry-path directory)
                             nil
                             (catch clojure.lang.ExceptionInfo e e))]
              (is (= :path-outside-root (:concept-bundles/error (ex-data error)))
                  (str "path escape variant " index " was rejected"))))
          (finally (Files/deleteIfExists outside)))))))

(deftest bare-bundle-paths-fall-back-to-classpath-resources
  (with-temp-dir
    (fn [directory]
      (let [spec {:id :test.bundle/classpath :owner :shared
                  :concepts [{:concept/id :test.bundle/classpath-concept
                              :concept/file "concept-bundles/classpath-concept.edn"}]}
            registry-path (write-catalogue! directory [spec])
            catalogue (bundles/load-bundles registry-path directory)]
        (is (= [:test.bundle/classpath-concept]
               (mapv :concept/id (:concept-bundle-catalogue/concepts catalogue))))))))
