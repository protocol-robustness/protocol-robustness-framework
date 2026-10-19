(ns scripts.build-notebooks
  "Build extracted Clerk notebook sources from the pinned notebook package."
  (:require [clojure.java.io :as io]
            [nextjournal.clerk :as clerk])
  (:import [java.nio.file Paths]))

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
                              path (if (.isAbsolute path) path (.resolve root path))]
                          (when-not (.isFile (.toFile path))
                            (throw (ex-info "Notebook path is missing from extracted source" {:path (str path)})))
                          (str (.toAbsolutePath path))))
                      paths)
          out (or out "target/clerk-build")]
      (clerk/build! {:paths paths :out-path out :package :directory})
      (println (str "Built " (count paths) " extracted notebook(s) to " out)))
    (finally (shutdown-agents))))
