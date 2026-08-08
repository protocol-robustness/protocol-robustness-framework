(ns resolver-sim.architecture.core-domain-vocabulary-boundary-test
  "Policy-driven vocabulary guard for force-authorisation held-custody
   vocabulary in core.

   Three vocabulary families are guarded, each with its own exact per-file
   approval policy:

   :approved/core-domain-literals      — artifact/schema family: artifact kinds,
                                         schema/verifier strings, and contract
                                         symbols matching
                                         ^force-auth-(add-held|held-custody).
   :approved/core-operation-literals   — operation family: exact :add-held /
                                         :sub-held / :finalize-released /
                                         :refund-held keywords and strings.
   :approved/core-status-literals     — status family: the exact status terms
                                         in :architecture/status-vocabulary
                                         (:state-after, :state-after-root,
                                         :stablecoin, :add-held,
                                         :add-held-kind), matched by name like
                                         the artifact family.

   The guard reads parsed Clojure forms (comments are not forms; docstring
   slots of def/defn/defmacro are excluded), so English prose and unrelated
   keywords (e.g. :finalize/sub-held-amount, :held-custody-position-isolation
   claim ids) are out of scope. After Phase 3B all held-custody approvals are
   :protocol-neutral: they are the MINIMAL historical-recognition vocabulary
   core keeps so it can classify/verify the extension-owned historical read
   contract (sensitivity sentinel, canonical reconciliation, and the neutral
   artifact hashing layer the extension's legacy validators call into).
   Semantic ownership of held-custody vocabulary lives in the extension
   manifest (:extension/historical-read), cross-checked by a conformance test."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def boundary-policy
  (edn/read-string (slurp "config/architecture/protocol-boundaries.edn")))

(def ^:private eof (Object.))

(def artifact-vocab-re
  "Artifact/schema vocabulary matcher, derived from the
   :architecture/artifact-vocabulary-prefixes policy — never hardcoded."
  (let [prefixes (sort (get boundary-policy
                            :architecture/artifact-vocabulary-prefixes #{}))
        alternation (str/join "|" (map #(java.util.regex.Pattern/quote %) prefixes))]
    (re-pattern (str "(?i)^(?:" alternation ")"))))

(def operation-vocab-set
  "Exact operation vocabulary (keywords and string spellings), derived from the
   :architecture/operation-vocabulary policy — never hardcoded."
  (let [ops (get boundary-policy :architecture/operation-vocabulary #{})]
    (into ops (map name ops))))

(def status-vocab-names
  "Status vocabulary names (keyword names + string/symbol spellings), derived
   from the :architecture/status-vocabulary policy — never hardcoded."
  (let [sv (get boundary-policy :architecture/status-vocabulary #{})]
    (into #{} (map name) sv)))

(defn- clojure-sources [root]
  (for [file (file-seq (io/file root))
        :when (and (.isFile file) (str/ends-with? (.getName file) ".clj"))]
    file))

(defn- core-zone []
  (let [root-ids (set (map :from
                           (filter #(empty? (:may-depend-on %))
                                   (:architecture/dependency-rules boundary-policy))))]
    (first (filter #(contains? root-ids (:zone/id %))
                   (:architecture/zones boundary-policy)))))

(defn- core-source-files []
  (mapcat clojure-sources (:source-roots (core-zone))))

(defn- read-forms [file]
  (with-open [reader (clojure.lang.LineNumberingPushbackReader. (io/reader file))]
    (binding [*read-eval* false]
      (loop [forms []]
        (let [form (read {:eof eof :read-cond :allow} reader)]
          (if (identical? eof form)
            forms
            (recur (conj forms form))))))))

(defn- literal-name [x]
  (cond
    (keyword? x) (name x)
    (symbol? x) (str x)
    (string? x) x))

(defn- artifact-vocab? [x]
  (and (or (keyword? x) (symbol? x) (string? x))
       (boolean (re-find artifact-vocab-re (literal-name x)))))

(defn- operation-vocab? [x]
  (contains? operation-vocab-set x))

(defn- status-vocab? [x]
  (and (or (keyword? x) (symbol? x) (string? x))
       (contains? status-vocab-names (literal-name x))))

(defn- relevant-literal? [x]
  (or (artifact-vocab? x) (operation-vocab? x) (status-vocab? x)))

(defn- collect-literals
  "Collect relevant literals from a form, excluding docstring slots of
   def/defn/defmacro. Each literal is classified into every family it belongs
   to. Returns {:artifact <set> :operation <set> :status <set>}."
  [form]
  (letfn [(walk [acc f]
            (cond
              (relevant-literal? f) (cond-> acc
                                      (artifact-vocab? f) (update :artifact conj f)
                                      (operation-vocab? f) (update :operation conj f)
                                      (status-vocab? f) (update :status conj f))
              (seq? f)
              (let [head (first f)]
                (if (contains? '#{def defn defmacro} head)
                  (if (string? (nth f 2 nil))
                    (reduce walk acc (drop 3 f))
                    (reduce walk acc (drop 2 f)))
                  (reduce walk acc (rest f))))
              (coll? f) (reduce walk acc (seq f))
              :else acc))]
    (walk {:artifact #{} :operation #{} :status #{}} form)))

(defn- file-literals [file]
  (reduce (fn [acc m]
            (-> acc
                (update :artifact into (:artifact m))
                (update :operation into (:operation m))
                (update :status into (:status m))))
          {:artifact #{} :operation #{} :status #{}}
          (map collect-literals (read-forms file))))

(defn- approvals-by-file [approvals]
  (into {} (map (juxt :file :literals)) approvals))

(def historical-recognition-files
  "Core files that legitimately recognize historical held-custody artifact
   vocabulary for classification/verification of the extension-owned
   historical read contract. Artifact vocabulary may be :protocol-neutral
   ONLY in these files; the conformance test cross-checks their tables against
   the extension manifest."
  #{"src/resolver_sim/sensitivity/sentinel.clj"
    "src/resolver_sim/assurance/canonical_force_authorisation.clj"
    "src/resolver_sim/evidence/artifact.clj"})

(deftest core-domain-vocabulary-boundaries
  (doseq [[approvals-key family-key vocab-name allowed-statuses]
          [[:approved/core-domain-literals :artifact "artifact/schema" #{:protocol-neutral}]
           [:approved/core-operation-literals :operation "operation"
            #{:protocol-neutral}]
           [:approved/core-status-literals :status "status"
            #{:protocol-neutral}]]]
    (let [approvals (get boundary-policy approvals-key [])
          approved-by-file (approvals-by-file approvals)
          found (into {}
                      (keep (fn [file]
                              (let [lits (get (file-literals file) family-key)]
                                (when (seq lits)
                                  [(.getPath file) lits]))))
                      (core-source-files))]
      (testing (str "unapproved " vocab-name " vocabulary in core fails")
        (let [violations (into (sorted-map)
                               (keep (fn [[path lits]]
                                       (let [unapproved (remove #(contains? (approved-by-file path #{}) %)
                                                                lits)]
                                         (when (seq unapproved)
                                           [path (sort-by str unapproved)]))))
                               found)]
          (is (empty? violations)
              (str "Unapproved " vocab-name " vocabulary in core: " (pr-str violations)))))
      (testing (str "cross-file " vocab-name " usage must be approved in every file it appears")
        ;; Artifact-kind/schema vocabulary may legitimately be referenced by more
        ;; than one core file (e.g. a reconciliation namespace classifying the
        ;; frozen legacy world). A literal appearing in N files must be approved
        ;; in ALL of them — otherwise the per-file unapproved check above fails.
        (let [by-literal (reduce (fn [m [path lits]]
                                   (reduce (fn [m lit]
                                             (update m lit (fnil conj []) path))
                                           m lits))
                                 {}
                                 found)]
          (is (every? (fn [[lit files]]
                        (every? #(contains? (approved-by-file % #{}) lit) files))
                      by-literal)
              (str vocab-name " vocabulary used without approval in every file: "
                   (pr-str (into (sorted-map)
                                 (keep (fn [[lit files]]
                                         (when-not (every? #(contains? (approved-by-file % #{}) lit)
                                                           files)
                                           [lit files])))
                                 by-literal))))))
      (testing (str vocab-name " approvals carry an allowed status; legacy entries require a replacement")
        (doseq [a approvals]
          (is (contains? allowed-statuses (:status a))
              (str "approval status not allowed (" allowed-statuses "): " (:file a)))
          (when (= :legacy (:status a))
            (is (some? (:replacement a)) (str "legacy approval without replacement: " (:file a))))
          (when (= :protocol-neutral (:status a))
            (when (seq (filter artifact-vocab? (:literals a)))
              (is (contains? historical-recognition-files (:file a))
                  (str "protocol-neutral artifact vocabulary allowed only in "
                       "historical-recognition files: " (:file a)))))))
      (testing (str vocab-name " approval entries are unique per file")
        (is (= (count approvals) (count (distinct (map :file approvals))))))
      (testing (str "a stale " vocab-name " approval (file no longer contains a listed literal) fails")
        (doseq [a approvals]
          (let [actual (set (get found (:file a)))]
            (is (every? #(contains? actual %) (:literals a))
                (str "stale " vocab-name " approval literal in " (:file a)))))))))
