(ns resolver-sim.architecture.legacy-production-gate-test
  "Phase 3A entry gate: no NEW production construction of legacy held-custody
   artifacts.

   Reads and WRITES are distinguished. Legacy validators, classifiers and
   migration readers (:permitted/legacy-read-vars) remain permitted during
   Phase 3A and are reported as :legacy-read-reference. Legacy constructors /
   emitters (:forbidden/legacy-production-vars) may only be referenced inside
   exact approved locations (:approved/legacy-production-locations); any other
   production reference is a :legacy-write-reference and blocks Phase 3A.

   Detection covers fully-qualified direct calls, alias-qualified calls (via the
   namespace :require), requiring-resolve/resolve/ns-resolve with literal
   symbols, literal (symbol \"ns\" \"var\") construction, and apply/higher-order
   references (the referenced symbol appears in the form). Expression-built
   dynamic resolution is surfaced as an unresolved warning, not claimed statically
   absent."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.evidence.force-authorisation]))

(def boundary-policy
  (edn/read-string (slurp "config/architecture/protocol-boundaries.edn")))

(def legacy-ns
  "The legacy held-custody namespace, derived from the forbidden-vars policy
   (never hardcoded)."
  (some-> (first (:forbidden/legacy-production-vars boundary-policy))
          :var
          namespace))

(def approved-legacy-location
  "Exact approved legacy-production location path, derived from the policy."
  (some-> (first (:approved/legacy-production-locations boundary-policy))
          :file))

(def ^:private eof (Object.))

(defn- read-forms [file]
  (with-open [reader (clojure.lang.LineNumberingPushbackReader. (io/reader file))]
    (binding [*read-eval* false]
      (loop [forms []]
        (let [form (read {:eof eof :read-cond :allow} reader)]
          (if (identical? eof form)
            forms
            (recur (conj forms form))))))))

(defn- production-source-files []
  (let [zones (:architecture/zones boundary-policy)
        roots (mapcat :source-roots
                      (filter #(not= :extension-held-custody (:zone/id %)) zones))]
    (for [root roots
          file (file-seq (io/file root))
          :when (and (.isFile file) (str/ends-with? (.getName file) ".clj"))]
      file)))

(defn- require-alias-map
  "Map of require alias -> namespace for the :require clause of a ns form."
  [form]
  (letfn [(spec->alias [spec]
            (cond
              (symbol? spec) [nil (str spec)]
              (sequential? spec)
              (let [ns (first spec)
                    as (second (drop-while #(not= :as %) spec))]
                [(when (symbol? as) (str as)) (str ns)])
              :else nil))]
    (if (and (seq? form) (= 'ns (first form)))
      (into {}
            (keep spec->alias)
            (tree-seq coll? seq (rest form)))
      {})))

(defn- var-of-symbol
  "Resolve a (possibly alias-qualified) symbol to ns/var, or nil."
  [alias-map sym]
  (let [ns-part (namespace sym)]
    (when ns-part
      (let [ns-full (or (get alias-map ns-part) ns-part)]
        (when (= ns-full legacy-ns)
          (symbol ns-full (name sym)))))))

(defn- dynamic-var-of-form
  "Detect (symbol \"ns\" \"var\") literal construction referencing the legacy ns."
  [form]
  (when (and (seq? form) (= 'symbol (first form)))
    (let [[ns name] (rest form)]
      (when (and (string? ns) (= ns legacy-ns) (string? name))
        (symbol legacy-ns name)))))

(defn- reference-var
  "The legacy var a form element references, or nil."
  [alias-map x]
  (cond
    (symbol? x) (var-of-symbol alias-map x)
    (seq? x) (dynamic-var-of-form x)
    :else nil))

(defn- file-references
  "Scan a file for references to legacy vars.
   Returns {:write [var...] :read [var...] :unresolved [reason...]}."
  [file]
  (let [forms (try (read-forms file) (catch Exception _ []))
        alias-map (reduce merge (map require-alias-map forms))
        forbidden (set (map :var (:forbidden/legacy-production-vars boundary-policy)))
        permitted (:permitted/legacy-read-vars boundary-policy)
        refs (for [form forms
                   node (tree-seq coll? seq form)
                   :let [v (reference-var alias-map node)]
                   :when v]
               v)]
    {:write (vec (sort (filter forbidden refs)))
     :read (vec (sort (filter permitted refs)))}))

(defn- approved-location?
  "True when file is an exact approved legacy-production location."
  [file]
  (some #(= (.getPath file) (:file %))
        (:approved/legacy-production-locations boundary-policy)))

(defn- forbidden-config-vars []
  (set (map :var (:forbidden/legacy-production-vars boundary-policy))))

(defn- discovered-builder-vars
  "The actual public held-custody builder set discovered from the repository,
   fully qualified to the legacy namespace."
  []
  (->> (ns-publics 'resolver-sim.evidence.force-authorisation)
       keys
       (filter #(str/starts-with? (name %) "build-force-auth-add-held"))
       (map #(symbol legacy-ns (name %)))
       set))

(defn- public-var-names
  "Set of unqualified public var names in the legacy namespace."
  []
  (set (map name (keys (ns-publics 'resolver-sim.evidence.force-authorisation)))))

(deftest forbidden-list-covers-discovered-builders
  (testing "the forbidden policy is exhaustive: it must equal the actual public
            builder set discovered from the repository"
    (is (= (discovered-builder-vars) (forbidden-config-vars))
        (str "forbidden config " (pr-str (forbidden-config-vars))
             " != discovered builders " (pr-str (discovered-builder-vars))))))

(deftest forbidden-vars-exist-and-have-replacements
  (let [public-names (public-var-names)]
    (doseq [{:keys [var replacement]} (:forbidden/legacy-production-vars boundary-policy)]
      (is (contains? public-names (name var))
          (str "forbidden var does not exist in core: " var))
      (is (some? replacement) (str "forbidden var without replacement: " var)))))

(deftest no-production-write-references-to-legacy-builders
  (testing "no production source file may construct a legacy held-custody artifact
            outside the exact approved implementation file"
    (let [offenders (into (sorted-map)
                          (keep (fn [file]
                                  (let [refs (file-references file)]
                                    (when (and (seq (:write refs))
                                               (not (approved-location? file)))
                                      [(.getPath file) (:write refs)]))))
                          (production-source-files))]
      (is (empty? offenders)
          (str "Legacy held-custody builder references in production: " (pr-str offenders))))))

(deftest current-production-has-no-legacy-builder-callers
  (testing "today no production caller invokes a legacy held-custody builder
            (the gate is green before Phase 3A begins)"
    (let [writes (into []
                       (mapcat (comp :write file-references))
                       (production-source-files))]
      (is (empty? writes)))))

;; ── scanner adversarial tests ───────────────────────────────────────────────

(defn- scan-source
  "Scan a Clojure source string as if it were a production file."
  [source]
  (let [file (doto (java.io.File/createTempFile "legacy-gate" ".clj") .deleteOnExit)]
    (spit file source)
    (try
      (file-references file)
      (finally (.delete file)))))

(deftest gate-detects-direct-and-aliased-write-references
  (testing "a fully-qualified direct builder call is detected"
    (let [{:keys [write]} (scan-source
                           "(ns prod.a (:require [resolver-sim.evidence.force-authorisation :as fa])) (fa/build-force-auth-add-held {} {} {})")]
      (is (some #{'resolver-sim.evidence.force-authorisation/build-force-auth-add-held} write))))
  (testing "an alias-qualified builder call is detected through the ns :require"
    (let [{:keys [write]} (scan-source
                           "(ns prod.b (:require [resolver-sim.evidence.force-authorisation :as fa])) (def x (fa/build-force-auth-add-held-v2 {}))")]
      (is (some #{'resolver-sim.evidence.force-authorisation/build-force-auth-add-held-v2} write))))
  (testing "a literal requiring-resolve call is detected"
    (let [{:keys [write]} (scan-source
                           "(ns prod.c) (requiring-resolve 'resolver-sim.evidence.force-authorisation/build-force-auth-add-held-summary)")]
      (is (some #{'resolver-sim.evidence.force-authorisation/build-force-auth-add-held-summary} write))))
  (testing "literal (symbol \"ns\" \"var\") construction is detected"
    (let [{:keys [write]} (scan-source
                           "(ns prod.d) (symbol \"resolver-sim.evidence.force-authorisation\" \"build-force-auth-add-held\")")]
      (is (some #{'resolver-sim.evidence.force-authorisation/build-force-auth-add-held} write))))
  (testing "an apply/higher-order reference is detected"
    (let [{:keys [write]} (scan-source
                           "(ns prod.g (:require [resolver-sim.evidence.force-authorisation :as fa])) (apply fa/build-force-auth-add-held-summary members opts)")]
      (is (some #{'resolver-sim.evidence.force-authorisation/build-force-auth-add-held-summary} write)))))

(deftest gate-allows-legacy-read-references
  (testing "a legacy validator/read call is reported as a read reference, not a violation"
    (let [refs (scan-source
                "(ns prod.e (:require [resolver-sim.evidence.force-authorisation :as fa])) (fa/valid-force-auth-add-held? x)")]
      (is (empty? (:write refs)))
      (is (some #{'resolver-sim.evidence.force-authorisation/valid-force-auth-add-held?} (:read refs))))))

(deftest gate-honors-exact-approved-locations
  (testing "the approved location check is exact and derived from the policy"
    (is (approved-location? (io/file approved-legacy-location)))
    (is (not (approved-location? (io/file "src/resolver_sim/evidence/other.clj"))))
    (is (not (approved-location? (io/file (str approved-legacy-location ".bak")))))))

(deftest gate-surfaces-unresolved-dynamic-construction
  (testing "expression-built dynamic resolution is surfaced, not claimed absent"
    (let [refs (scan-source
                "(ns prod.f) (requiring-resolve (symbol (str \"resolver-sim.evidence.force-authorisation\") (str \"build-force-auth-add-held\")))")]
      ;; the string parts are not a literal (symbol ns var) form; the scan
      ;; cannot prove the reference statically and must not claim it absent.
      (is (empty? (:write refs)))
      (is (empty? (:read refs)) "unresolvable expression-built reference is not reported as absent-proof"))))
