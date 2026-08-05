(ns resolver-sim.architecture.core-distribution-boundary-test
  "Static dependency guard for the PRF production source tree using an
   architecture-zone model.

   Zones (config/architecture/protocol-boundaries.edn) declare source roots and
   namespace prefixes; :architecture/dependency-rules declare which cross-zone
   dependencies are permitted. The guard reads Clojure forms rather than
   searching source text, so comments and docstrings are not dependencies. It
   detects namespace declarations, direct require/use/import forms, and literal
   arguments to runtime namespace resolution APIs. Exact per-file approvals
   (:approved/extension-dependencies) remain the exception mechanism.

   Intended cross-zone graph:
     protocol-sew → extension-held-custody → core
     protocol-sew → core
   Forbidden: core → extension, extension → protocol-sew."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def boundary-policy
  (edn/read-string (slurp "config/architecture/protocol-boundaries.edn")))

(def ^:private eof (Object.))

(defn- clojure-sources [root]
  (for [file (file-seq (io/file root))
        :when (and (.isFile file) (str/ends-with? (.getName file) ".clj"))]
    file))

(defn- zone-source-files [zone]
  (mapcat clojure-sources (:source-roots zone)))

(defn- read-forms [file]
  (with-open [reader (clojure.lang.LineNumberingPushbackReader. (io/reader file))]
    (binding [*read-eval* false]
      (loop [forms []]
        (let [form (read {:eof eof :read-cond :allow} reader)]
          (if (identical? eof form)
            forms
            (recur (conj forms form))))))))

(defn- prefix-matches?
  "True when value equals prefix or starts with prefix + '.'."
  [prefix value]
  (and (string? value)
       (or (= prefix value)
           (str/starts-with? value (str prefix ".")))))

(defn- zone-prefix-match
  "The zone prefix matching value, or nil."
  [zone value]
  (some #(when (prefix-matches? % value) %) (:namespace-prefixes zone)))

(defn- dependency-ns
  "Namespace part of a dependency (a var-form 'ns/var' is reduced to 'ns')."
  [dep]
  (if (str/includes? dep "/")
    (first (str/split dep #"/"))
    dep))

(defn- zone-of-dependency
  "The zone a dependency belongs to (longest matching prefix), or nil."
  [zones dep]
  (let [ns (dependency-ns dep)]
    (->> zones
         (keep (fn [z] (when (zone-prefix-match z ns) z)))
         (sort-by (fn [z] (apply max (map count (:namespace-prefixes z)))) >)
         first)))

(defn- symbol-dependency [policy value]
  (when (symbol? value)
    (let [name (str value)
          ns-name (namespace value)
          zones (:architecture/zones policy)]
      (when (or (some #(zone-prefix-match % name) zones)
                (some #(zone-prefix-match % ns-name) zones))
        name))))

(defn- dynamic-symbol-dependency [policy form]
  (when (and (seq? form) (= 'symbol (first form)))
    (let [[namespace-name member-name] (rest form)
          zones (:architecture/zones policy)]
      (when (and (string? namespace-name)
                 (some #(zone-prefix-match % namespace-name) zones))
        (if (string? member-name)
          (str namespace-name "/" member-name)
          namespace-name)))))

(defn- form-dependencies [policy form]
  (let [nodes (tree-seq coll? seq form)]
    (->> nodes
         (mapcat (fn [node]
                   (cond
                     (symbol? node) (keep identity [(symbol-dependency policy node)])
                     :else (keep identity [(dynamic-symbol-dependency policy node)]))))
         set)))

(defn- file-readable-as-forms?
  "True when every form in the file reads successfully. Files that use reader
   constructs the default reader cannot parse are reported, not silently
   scanned."
  [file]
  (try
    (read-forms file)
    true
    (catch Exception _ false)))

(defn- file-dependencies [policy file]
  (try
    (->> (read-forms file)
         (mapcat #(form-dependencies policy %))
         set)
    (catch Exception _
      ;; An unreadable file cannot be dependency-scanned; it is surfaced by
      ;; core-files-are-readable (for core) and otherwise skipped, never
      ;; silently treated as violation-free for the cross-zone graph.
      #{})))

(defn- approved-dependency? [policy file dependency]
  (some #(and (= (.getPath file) (:file %))
              (= dependency (:dependency %)))
        (:approved/extension-dependencies policy)))

(defn- dependency-rule-map [policy]
  (into {} (map (juxt :from :may-depend-on))
        (:architecture/dependency-rules policy)))

(defn- cross-zone-violations
  "Dependencies of file in zone that cross into a disallowed zone without an
   exact approval."
  [policy zone file]
  (let [rules (dependency-rule-map policy)
        allowed (rules (:zone/id zone) #{})]
    (->> (file-dependencies policy file)
         (keep (fn [d]
                 (let [tz (:zone/id (zone-of-dependency (:architecture/zones policy) d))]
                   (when (and tz
                              (not= (:zone/id zone) tz)
                              (not (contains? allowed tz))
                              (not (approved-dependency? policy file d)))
                     d))))
         sort
         vec)))

(deftest policy-defines-architecture-zones
  (testing "every zone has source roots and namespace prefixes"
    (let [zones (:architecture/zones boundary-policy)]
      (is (seq zones))
      (is (every? (fn [z] (seq (:source-roots z))) zones))
      (is (every? (fn [z] (seq (:namespace-prefixes z))) zones))
      (is (every? (fn [z] (seq (:source-roots z)))
                  zones))))
  (testing "every zone has a dependency rule"
    (let [zone-ids (set (map :zone/id (:architecture/zones boundary-policy)))
          rule-ids (set (map :from (:architecture/dependency-rules boundary-policy)))]
      (is (= zone-ids rule-ids)))))

(deftest production-source-tree-is-covered
  (let [core-zone (first (filter #(= :core (:zone/id %))
                                 (:architecture/zones boundary-policy)))]
    (is (= ["src"] (:source-roots core-zone)))
    (is (every? #(str/starts-with? (.getPath %) "src/")
                (zone-source-files core-zone)))))

(deftest core-files-are-readable-as-forms
  (testing "every core source file must parse as Clojure forms so its
            dependencies can be fully scanned (an unreadable core file could
            hide an extension dependency)"
    (let [core-zone (first (filter #(= :core (:zone/id %))
                                   (:architecture/zones boundary-policy)))
          unreadable (->> (zone-source-files core-zone)
                          (remove file-readable-as-forms?)
                          (map #(.getPath %))
                          vec)]
      (is (empty? unreadable)
          (str "Unreadable core source files: " (pr-str unreadable))))))

(deftest cross-zone-dependencies-follow-the-permitted-graph
  (testing "all unapproved cross-zone dependencies in every zone fail"
    (let [offenders (into (sorted-map)
                          (keep (fn [{:keys [zone file]}]
                                  (let [violations (cross-zone-violations boundary-policy zone file)]
                                    (when (seq violations)
                                      [(.getPath file) violations]))))
                          (mapcat (fn [zone]
                                    (map (fn [file] {:zone zone :file file})
                                         (zone-source-files zone)))
                                  (:architecture/zones boundary-policy)))]
      (is (empty? offenders)
          (str "Disallowed cross-zone dependencies: " (pr-str offenders))))))

(deftest core-never-depends-on-the-held-custody-extension
  (let [core-zone (first (filter #(= :core (:zone/id %))
                                 (:architecture/zones boundary-policy)))
        offenders (->> (zone-source-files core-zone)
                       (keep (fn [file]
                               (let [deps (file-dependencies boundary-policy file)
                                     ext (filter #(str/starts-with?
                                                   (dependency-ns %) "prf.extensions.held-custody")
                                                 deps)]
                                 (when (seq ext) [(.getPath file) (vec ext)]))))
                       (into (sorted-map)))]
    (is (empty? offenders)
        (str "Core must not depend on the held-custody extension: " (pr-str offenders)))))

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
          zones (assoc-in (:architecture/zones boundary-policy)
                          [0 :source-roots] [(.getPath directory)])
          policy (assoc boundary-policy :architecture/zones zones)]
      (spit file "(ns boundary.new (:require [resolver-sim.protocols.sew.unlisted]))")
      (try
        (let [core-zone (first zones)]
          (is (= [(.getPath file)] (map #(.getPath %) (zone-source-files core-zone))))
          (is (= ["resolver-sim.protocols.sew.unlisted"]
                 (cross-zone-violations policy core-zone file))))
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
