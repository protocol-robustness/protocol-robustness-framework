(ns scripts.build-notebooks
  "Build extracted Clerk notebook sources from the pinned notebook package."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [nextjournal.clerk :as clerk])
  (:import [java.nio.file Files LinkOption Paths]
           [java.nio.file.attribute FileAttribute]))

(defn- notebook-root []
  (let [resource (io/resource "notebooks/index.clj")]
    (when-not resource
      (throw (ex-info "Extracted notebook sources are unavailable; select :notebooks." {})))
    (.getParent (.getParent (Paths/get (.toURI resource))))))

(defn- parse-args [args]
  (loop [[arg & more] args opts {:paths []}]
    (if-not arg
      opts
      (case arg
        "--out" (if-let [value (first more)]
                  (recur (rest more) (assoc opts :out value))
                  (throw (ex-info "--out needs a directory" {})))
        "--nb" (if-let [value (first more)]
                 (recur (rest more) (update opts :paths conj value))
                 (throw (ex-info "--nb needs a notebook path" {})))
        (throw (ex-info (str "Unknown argument: " arg) {}))))))

(defn -main [& args]
  (try
    (let [{:keys [out paths]} (parse-args args)
          root (notebook-root)
          paths (if (seq paths) paths ["notebooks/report.clj"])
          paths (mapv (fn [path]
                        (let [path (Paths/get path (make-array String 0))
                              source-path (if (.isAbsolute path) path (.resolve root path))]
                          (when-not (.isFile (.toFile source-path))
                            (throw (ex-info "Notebook path is missing from extracted source" {:path (str source-path)})))
                          (str "notebooks/" (.getFileName source-path))))
                      paths)
          out (or out "target/clerk-build")
          multi? (< 1 (count paths))
          route-root (.toAbsolutePath (Paths/get "notebooks" (make-array String 0)))
          source-root (.toRealPath (.resolve root "notebooks") (make-array LinkOption 0))
          created-route? (if (Files/exists route-root (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
                           (if (and (Files/isSymbolicLink route-root)
                                    (= source-root (.toRealPath route-root (make-array LinkOption 0))))
                             false
                             (throw (ex-info "Cannot expose extracted notebooks at ./notebooks: path already exists"
                                             {:path (str route-root) :source (str source-root)})))
                           (do
                             (Files/createSymbolicLink route-root source-root (make-array FileAttribute 0))
                             true))]
      (try
        ;; Clerk's directory package maps an index notebook to index.html, but
        ;; rejects absolute source paths when deriving routes for multiple docs.
        ;; Build each notebook as its own explicit index through a temporary
        ;; relative route, keeping the managed Git cache path out of artifacts.
        (doseq [path paths]
          (let [filename (.getFileName (Paths/get path (make-array String 0)))
                notebook-name (str/replace (str filename) #"\.(cljc?|md)$" "")
                destination (if multi?
                              (str (Paths/get out (into-array String ["notebooks" notebook-name])))
                              out)]
            (clerk/build! {:paths [path]
                           :index path
                           :out-path destination
                           :package :directory})
            (println (str "Built " notebook-name " to " destination))))
        (println (str "Built " (count paths) " extracted notebook(s) under " out))
        (finally
          (when created-route?
            (Files/deleteIfExists route-root)))))
    (finally (shutdown-agents))))
