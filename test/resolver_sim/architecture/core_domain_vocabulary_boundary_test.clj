(ns resolver-sim.architecture.core-domain-vocabulary-boundary-test
  "Policy-driven vocabulary guard for force-authorisation held-custody
   vocabulary in core.

   Two vocabulary families are guarded, each with its own exact per-file
   approval policy:

   :approved/core-domain-literals      — artifact/schema family: artifact kinds,
                                         schema/verifier strings, and contract
                                         symbols matching
                                         ^force-auth-(add-held|held-custody).
   :approved/core-operation-literals   — operation family: exact :add-held /
                                         :sub-held / :finalize-released /
                                         :refund-held keywords and strings.

   The guard reads parsed Clojure forms (comments are not forms; docstring
   slots of def/defn/defmacro are excluded), so English prose and unrelated
   keywords (e.g. :finalize/sub-held-amount, :held-custody-position-isolation
   claim ids) are out of scope. It permits only exact frozen-legacy entries;
   Phase 6 acceptance: both approval lists are empty for held-custody
   vocabulary."
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

(defn- clojure-sources [root]
  (for [file (file-seq (io/file root))
        :when (and (.isFile file) (str/ends-with? (.getName file) ".clj"))]
    file))

(defn- core-source-files []
  (let [core-zone (first (filter #(= :core (:zone/id %))
                                 (:architecture/zones boundary-policy)))]
    (mapcat clojure-sources (:source-roots core-zone))))

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

(defn- relevant-literal? [x]
  (or (artifact-vocab? x) (operation-vocab? x)))

(defn- collect-literals
  "Collect relevant literals from a form, excluding docstring slots of
   def/defn/defmacro. Returns {:artifact <set> :operation <set>}."
  [form]
  (letfn [(walk [acc f]
            (cond
              (relevant-literal? f) (update acc
                                            (if (operation-vocab? f) :operation :artifact)
                                            conj f)
              (seq? f)
              (let [head (first f)]
                (if (contains? '#{def defn defmacro} head)
                  (if (string? (nth f 2 nil))
                    (reduce walk acc (drop 3 f))
                    (reduce walk acc (drop 2 f)))
                  (reduce walk acc (rest f))))
              (coll? f) (reduce walk acc (seq f))
              :else acc))]
    (walk {:artifact #{} :operation #{}} form)))

(defn- file-literals [file]
  (reduce (fn [acc m]
            (-> acc
                (update :artifact into (:artifact m))
                (update :operation into (:operation m))))
          {:artifact #{} :operation #{}}
          (map collect-literals (read-forms file))))

(defn- approvals-by-file [approvals]
  (into {} (map (juxt :file :literals)) approvals))

(deftest core-domain-vocabulary-boundaries
  (doseq [[approvals-key family-key vocab-name allowed-statuses]
          [[:approved/core-domain-literals :artifact "artifact/schema" #{:legacy}]
           [:approved/core-operation-literals :operation "operation"
            #{:legacy :protocol-neutral}]]]
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
      (testing (str "an approved " vocab-name " literal appearing in another core file fails")
        ;; Artifact-kind/schema vocabulary is unique to a single frozen core file.
        ;; Operation vocabulary may legitimately appear in multiple approved core
        ;; files (e.g. protocol-neutral custody effects + the frozen legacy
        ;; builder); cross-file operation usage is governed per-file by the
        ;; unapproved check above.
        (when (= :artifact family-key)
          (let [by-literal (reduce (fn [m [path lits]]
                                     (reduce (fn [m lit]
                                               (update m lit (fnil conj []) path))
                                             m lits))
                                   {}
                                   found)
                spread (into (sorted-map)
                             (keep (fn [[lit paths]]
                                     (when (> (count paths) 1)
                                       [lit (sort paths)])))
                             by-literal)]
            (is (empty? spread)
                (str vocab-name " vocabulary spread across core files: " (pr-str spread))))))
      (testing (str vocab-name " approvals carry an allowed status; legacy entries require a replacement")
        (doseq [a approvals]
          (is (contains? allowed-statuses (:status a))
              (str "approval status not allowed (" allowed-statuses "): " (:file a)))
          (when (= :legacy (:status a))
            (is (some? (:replacement a)) (str "legacy approval without replacement: " (:file a))))
          (when (= :protocol-neutral (:status a))
            (is (empty? (filter artifact-vocab? (:literals a)))
                (str "protocol-neutral operation approval must not carry artifact vocabulary: " (:file a))))))
      (testing (str vocab-name " approval entries are unique per file")
        (is (= (count approvals) (count (distinct (map :file approvals))))))
      (testing (str "a stale " vocab-name " approval (file no longer contains a listed literal) fails")
        (doseq [a approvals]
          (let [actual (set (get found (:file a)))]
            (is (every? #(contains? actual %) (:literals a))
                (str "stale " vocab-name " approval literal in " (:file a)))))))))
