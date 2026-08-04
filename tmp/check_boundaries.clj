(ns check-boundaries
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def eof (Object.))

(defn clojure-sources [root]
  (for [file (file-seq (io/file root))
        :when (and (.isFile file) (str/ends-with? (.getName file) ".clj"))]
    file))

(defn read-forms [file]
  (with-open [reader (clojure.lang.LineNumberingPushbackReader. (io/reader file))]
    (binding [*read-eval* false]
      (loop [forms []]
        (let [form (read {:eof eof :read-cond :allow} reader)]
          (if (identical? eof form)
            forms
            (recur (conj forms form))))))))

(defn extension-prefix? [policy value]
  (and (string? value)
       (some #(or (= value %)
                  (str/starts-with? value (str % ".")))
             (:extension/namespace-prefixes policy))))

(defn symbol-dependency [policy value]
  (when (symbol? value)
    (let [name (str value)
          ns-name (namespace value)]
      (cond
        (extension-prefix? policy name) name
        (extension-prefix? policy ns-name) name))))

(defn dynamic-symbol-dependency [policy form]
  (when (and (seq? form) (= 'symbol (first form)))
    (let [[namespace-name member-name] (rest form)]
      (when (and (string? namespace-name)
                 (extension-prefix? policy namespace-name))
        (if (string? member-name)
          (str namespace-name "/" member-name)
          namespace-name)))))

(defn form-dependencies [policy form]
  (let [nodes (tree-seq coll? seq form)]
    (->> nodes
         (mapcat (fn [node]
                   (cond
                     (symbol? node) (keep identity [(symbol-dependency policy node)])
                     :else (keep identity [(dynamic-symbol-dependency policy node)]))))
         set)))

(defn file-dependencies [policy file]
  (try
    (->> (read-forms file)
         (mapcat #(form-dependencies policy %))
         set)
    (catch Exception e
      (println "Error reading" (.getPath file) "=>" (.getMessage e))
      #{})))

(defn approved-dependency? [policy file dependency]
  (some #(and (= (.getPath file) (:file %))
              (= dependency (:dependency %)))
        (:approved/extension-dependencies policy)))

(defn unapproved-dependencies [policy file]
  (->> (file-dependencies policy file)
       (remove #(approved-dependency? policy file %))
       sort
       vec))

(defn core-source-files [policy]
  (mapcat clojure-sources (:core/source-roots policy)))

;; Main
(let [policy (edn/read-string (slurp "config/architecture/protocol-boundaries.edn"))
      offenders (->> (core-source-files policy)
                     (keep (fn [file]
                             (let [deps (unapproved-dependencies policy file)]
                               (when (seq deps)
                                 [(.getPath file) deps]))))
                     (into (sorted-map)))]
  (if (empty? offenders)
    (println "No unapproved extension dependencies found.")
    (do (println "Unapproved extension dependencies:")
        (doseq [[f deps] offenders]
          (println "-" f)
          (doseq [d deps] (println "   " d))))))
