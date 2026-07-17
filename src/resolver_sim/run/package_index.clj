(ns resolver-sim.run.package-index
  "Immutable package index for a completed structured scenario run.

   This is the runnable package boundary. It references independently hashed
   artifacts and is never merged back into the content-addressed bundle root."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [resolver-sim.commands.run-lifecycle :as lifecycle]
            [resolver-sim.hash.canonical :as hc]))

(def schema-version "run-package-index.v1")

(defn- sha-ref [file]
  (str "sha256:" (lifecycle/sha256-file file)))

(defn build
  [{:keys [run-id bundle-root-hash runner-finalization run-finalization
           canonical-assurance execution-dag artifacts]}]
  (let [base {:run-package/schema-version schema-version
              :run/id run-id
              :bundle/root-hash bundle-root-hash
              :artifacts (or artifacts
                             {:runner-finalization runner-finalization
                              :run-finalization run-finalization
                              :canonical-assurance canonical-assurance
                              :execution-dag execution-dag})}
        hash (hc/hash-with-intent {:hash/intent :run-package-index} base)]
    (assoc base :run-package/hash hash)))

(defn write! [path input]
  (let [index (build input)]
    (io/make-parents path)
    (spit path (json/write-str index :key-fn (fn [k] (if (keyword? k)
                                                       (if-let [ns (namespace k)] (str ns "/" (name k)) (name k))
                                                       (str k)))
                               :indent true))
    {:path path :index index}))

(defn runnable?
  "Verify index identity and every referenced local artifact hash.
   A missing DAG makes a package non-runnable rather than silently partial."
  [run-root index]
  (let [base (dissoc index :run-package/hash)
        expected (hc/hash-with-intent {:hash/intent :run-package-index} base)
        artifacts (:artifacts index)
        errors (vec (concat
                     (when (not= schema-version (:run-package/schema-version index)) [:unsupported-schema-version])
                     (when (not= expected (:run-package/hash index)) [:package-hash-mismatch])
                     (mapcat (fn [[kind {:keys [ref sha256]}]]
                               (let [file (io/file run-root ref)]
                                 (cond-> []
                                   (not (.isFile file)) (conj [:missing-artifact kind ref])
                                   (and (.isFile file) (not= sha256 (sha-ref file)))
                                   (conj [:artifact-hash-mismatch kind ref]))))
                             artifacts)))]
    {:runnable? (empty? errors) :errors errors}))
