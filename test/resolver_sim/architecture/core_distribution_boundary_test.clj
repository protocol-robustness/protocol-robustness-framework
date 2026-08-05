(ns resolver-sim.architecture.core-distribution-boundary-test
  "Static dependency guard for the complete PRF production source tree.

  The guard reads Clojure forms rather than searching source text, so comments
  and docstrings are not dependencies. It detects namespace declarations,
  direct require/use/import forms, and literal arguments to runtime namespace
  resolution APIs. Dynamic bridges must be exact entries in the policy."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def boundary-policy
  (edn/read-string (slurp "config/architecture/protocol-boundaries.edn")))

(def ^:private eof (Object.))
(def ^:private namespace-resolution-forms
  '#{require use import requiring-resolve resolve find-ns the-ns ns-resolve intern})

(defn- clojure-sources [root]
  (for [file (file-seq (io/file root))
        :when (and (.isFile file) (str/ends-with? (.getName file) ".clj"))]
    file))

(defn- read-forms [file]
  (with-open [reader (clojure.lang.LineNumberingPushbackReader. (io/reader file))]
    (binding [*read-eval* false]
      (loop [forms []]
        (let [res (try
                    (let [form (read {:eof eof :read-cond :allow} reader)]
                      {:ok true :form form})
                    (catch Exception e
                      {:ok false :err e}))]
          (if (not (:ok res))
            (do (println "Warning: failed to read" (.getPath file) "->" (str (:err res))) forms)
            (let [form (:form res)]
              (if (identical? eof form)
                forms
                (recur (conj forms form))))))))))

(defn- extension-prefix? [policy value]
  (and (string? value)
       (some #(or (= value %)
                  (str/starts-with? value (str % ".")))
             (:extension/namespace-prefixes policy))))

(defn- symbol-dependency [policy value]
  (when (symbol? value)
    (let [name (str value)
          ns-name (namespace value)]
      (cond
        (extension-prefix? policy name) name
        (extension-prefix? policy ns-name) name))))

(defn- dynamic-symbol-dependency [policy form]
  (when (and (seq? form) (= 'symbol (first form)))
    (let [[namespace-name member-name] (rest form)]
      (when (and (string? namespace-name)
                 (extension-prefix? policy namespace-name))
        (if (string? member-name)
          (str namespace-name "/" member-name)
          namespace-name)))))

(defn- form-dependencies [policy form]
  (let [nodes (tree-seq coll? seq form)]
    (->> nodes
         (mapcat (fn [node]
                   (cond
                     ;; Simple symbol occurrences anywhere in the AST
                     (symbol? node) (keep identity [(symbol-dependency policy node)])

                     ;; Recognize explicit runtime-resolution forms such as
                     ;; (requiring-resolve 'ns/sym), (require 'ns), (ns-resolve ns sym),
                     ;; and handle their literal symbol/string arguments.
                     (and (seq? node)
                          (symbol? (first node))
                          (contains? namespace-resolution-forms (first node)))
                     (let [args (rest node)]
                       (keep identity
                             (map (fn [a]
                                    (cond
                                      (symbol? a) (symbol-dependency policy a)
                                      (string? a) (when (extension-prefix? policy a) a)
                                      (and (seq? a) (= 'symbol (first a))) (dynamic-symbol-dependency policy a)
                                      :else nil))
                                  args)))

                     ;; Fallback: detect explicit (symbol "ns" "var") forms
                     :else (keep identity [(dynamic-symbol-dependency policy node)]))))
         set)))

(defn- file-dependencies [policy file]
  (->> (read-forms file)
       (mapcat #(form-dependencies policy %))
       set))

(defn- approved-dependency? [policy file dependency]
  (let [canonical-file-path (try (.getCanonicalPath file) (catch Exception _ (.getPath file)))]
    (some (fn [entry]
            (try
              (let [entry-path (-> (:file entry) io/file .getCanonicalPath)]
                (and (= entry-path canonical-file-path)
                     (= dependency (:dependency entry))))
              (catch Exception _ nil)))
          (:approved/extension-dependencies policy))))

(defn- unapproved-dependencies [policy file]
  (->> (file-dependencies policy file)
       (remove #(approved-dependency? policy file %))
       sort
       vec))

(defn- core-source-files [policy]
  (mapcat clojure-sources (:core/source-roots policy)))

(deftest policy-covers-the-complete-production-source-tree
  (testing "the production scope is derived from the PRF src directory, not a selected file list"
    (is (= ["src"] (:core/source-roots boundary-policy)))
    (is (nil? (:core/source-files boundary-policy)))
    (is (every? #(str/starts-with? (.getPath %) "src/")
                (core-source-files boundary-policy)))))

(deftest core-distribution-namespaces-do-not-import-extensions
  (testing "all unapproved extension namespace dependencies in production source fail"
    (let [offenders (->> (core-source-files boundary-policy)
                         (keep (fn [file]
                                 (let [dependencies (unapproved-dependencies boundary-policy file)]
                                   (when (seq dependencies)
                                     [(.getPath file) dependencies]))))
                         (into (sorted-map)))]
      (is (empty? offenders)
          (str "Core production source has unapproved protocol-extension dependencies: "
               (pr-str offenders))))))

(deftest detects-extension-dependencies-through-supported-resolution-mechanisms
  (testing "direct and runtime namespace resolution forms are all detected"
    (let [file (doto (java.io.File/createTempFile "protocol-boundary" ".clj") .deleteOnExit)
          source "(ns boundary.fixture (:require [resolver-sim.protocols.sew.direct]))\n(require 'resolver-sim.protocols.sew.required)\n(requiring-resolve 'resolver-sim.protocols.sew.runtime/entry)\n(resolve 'resolver-sim.protocols.sew.resolved/entry)\n(find-ns 'resolver-sim.protocols.sew.found)\n(the-ns 'resolver-sim.protocols.sew.present)\n(requiring-resolve (symbol \"resolver-sim.protocols.sew.dynamic\" \"entry\"))"]
      (spit file source)
      (is (= #{"resolver-sim.protocols.sew.direct"
               "resolver-sim.protocols.sew.required"
               "resolver-sim.protocols.sew.runtime/entry"
               "resolver-sim.protocols.sew.resolved/entry"
               "resolver-sim.protocols.sew.found"
               "resolver-sim.protocols.sew.present"
               "resolver-sim.protocols.sew.dynamic/entry"}
             (file-dependencies boundary-policy file))))))

(deftest scans-a-new-unlisted-production-file
  (testing "a file added below a configured source root cannot bypass the guard"
    (let [directory (.toFile (java.nio.file.Files/createTempDirectory
                              "protocol-boundary-root"
                              (make-array java.nio.file.attribute.FileAttribute 0)))
          file (io/file directory "new_core_file.clj")
          policy (assoc boundary-policy :core/source-roots [(.getPath directory)])]
      (spit file "(ns boundary.new (:require [resolver-sim.protocols.sew.unlisted]))")
      (try
        (is (= [(.getPath file)] (map #(.getPath %) (core-source-files policy))))
        (is (= ["resolver-sim.protocols.sew.unlisted"]
               (unapproved-dependencies policy file)))
        (finally
          (.delete file)
          (.delete directory))))))

(deftest policy-exceptions-are-exact
  (testing "an approved bridge cannot approve another dependency in the same file"
    (let [file (io/file "src/resolver_sim/benchmark/runner.clj")]
      (is (approved-dependency? boundary-policy file
                                "resolver-sim.protocols.sew/replay-with-sew-protocol"))
      (is (not (approved-dependency? boundary-policy file
                                     "resolver-sim.protocols.sew/another-entry"))))))
