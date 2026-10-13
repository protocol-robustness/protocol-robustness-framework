(ns dev.explore
  (:require
   [clojure.string :as str]
   [clojure.tools.namespace.find :as ns-find]
   [clojure.java.io :as io]))

(def source-dirs
  ["src" "test" "dev" "notebooks"])

(defn all-project-ns
  []
  (->> source-dirs
       (map io/file)
       (filter #(.exists %))
       (mapcat ns-find/find-namespaces-in-dir)
       sort
       vec))

(defn find-project-ns
  [needle]
  (let [needle (str/lower-case (str needle))]
    (->> (all-project-ns)
         (filter #(str/includes? (str/lower-case (str %)) needle))
         vec)))

(defn public-vars
  [ns-sym]
  (require ns-sym)
  (->> (ns-publics ns-sym)
       keys
       sort
       vec))

(defn find-project-var
  [needle]
  (let [needle (str/lower-case (str needle))]
    (->> (all-project-ns)
         (mapcat
          (fn [ns-sym]
            (try
              (require ns-sym)
              (for [v (keys (ns-publics ns-sym))
                    :let [fq (str ns-sym "/" v)]
                    :when (str/includes? (str/lower-case fq) needle)]
                fq)
              (catch Throwable _
                []))))
         sort
         vec)))

(defn apropos+
  [needle]
  {:namespaces (find-project-ns needle)
   :vars       (find-project-var needle)})
