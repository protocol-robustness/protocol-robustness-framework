(ns scripts.search_docs_sentinel.run
  "Search-docs sentinel: documentation integrity monitor.
   Usage: clojure -M scripts/search-docs-sentinel/run.clj"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.edn :as edn]))

;; ---
;; Configuration
;; ---

(def scan-paths
  "Directories to scan for term occurrences."
  ["docs" "notebooks" "protocols_src" "src" "scenarios" "scripts" "schemas" "data" "suites"])

(def queries-path
  "scripts/search_docs_sentinel/queries.edn")

;; ---
;; Helpers
;; ---

(defn- file-exists? [path]
  (.exists (io/file path)))

(defn- scan-file
  "Search a single text file for any of the given terms.
   Returns set of terms found."
  [file-path terms]
  (try
    (let [text (slurp file-path :encoding "UTF-8")]
      (set (filter #(str/includes? text %) terms)))
    (catch Exception _ #{})))

(defn- file-ext [f]
  (let [n (.getName f)
        dot-idx (.lastIndexOf n (int \.))]
    (keyword (str/lower-case (if (neg? dot-idx) "" (subs n (inc dot-idx)))))))

(defn- find-files
  "Recursively list files under root matching ext-filter (e.g. #{:clj :md :json})."
  [root-dir ext-filter]
  (let [d (io/file root-dir)]
    (if (.isDirectory d)
      (->> (file-seq d)
           (filter #(.isFile %))
           (filter #(contains? ext-filter (file-ext %))))
      [(io/file root-dir)])))

(def scan-extensions
  "All file extensions to scan."
  #{:clj :md :json :edn :yaml :py :sh})

;; ---
;; Query execution
;; ---

(defn- run-query
  "Execute a single query: scan files for terms, check expected paths, report findings."
  [query root-dir]
  (let [terms            (:terms query [])
        expected         (:expected-paths query [])
        deprecated-refs  (:deprecated-paths query [])

        ;; 1. Scan all relevant files for search terms
        all-files         (mapcat #(find-files (str root-dir "/" %) scan-extensions) scan-paths)
        term-hits         (mapcat (fn [f]
                                    (let [found (scan-file (.getPath f) terms)]
                                      (for [t found] [t (.getPath f)])))
                                  all-files)
        terms-found-set   (set (map first term-hits))
        terms-not-found   (remove terms-found-set terms)

        ;; 2. Check expected paths exist
        expected-found    (filter #(file-exists? (str root-dir "/" %)) expected)
        expected-missing  (remove #(file-exists? (str root-dir "/" %)) expected)

        ;; 3. Check deprecated paths
        deprecated-hits   (filter #(file-exists? (str root-dir "/" %)) deprecated-refs)

        ;; 4. Derive status
        all-terms-found?  (empty? terms-not-found)
        all-paths-found?  (empty? expected-missing)
        has-deprecated?   (seq deprecated-hits)
        status            (cond
                           (and all-terms-found? all-paths-found? (not has-deprecated?)) :pass
                           (and all-terms-found? all-paths-found?)                         :warning
                           :else                                                           :fail)]

    {:query-id       (:id query)
     :label          (:label query)
     :status         status
     :terms          terms
     :terms-found    (into [] term-hits)
     :terms-not-found (vec terms-not-found)
     :expected-total (count expected)
     :expected-found (count expected-found)
     :expected-missing (vec expected-missing)
     :expected-found-paths (vec expected-found)
     :deprecated-hits (vec deprecated-hits)
     :tags           (:tags query)
     :note           (:note query)}))

;; ---
;; Report generation
;; ---

(defn- print-line [& parts]
  (println (str/join " " (remove nil? parts))))

(defn- status-char [status]
  (case status
    :pass "PASS"
    :warning "WARN"
    :fail "FAIL"
    "----"))

(defn print-report
  "Print a human-readable report to stdout."
  [findings]
  (let [pass-count  (count (filter #(= :pass (:status %)) findings))
        warn-count  (count (filter #(= :warning (:status %)) findings))
        fail-count  (count (filter #(= :fail (:status %)) findings))
        total       (count findings)]
    (println "=== Search-Docs Sentinel Report ===")
    (println (str (java.time.LocalDateTime/now)))
    (println)
    (println (str "Queries: " total "  |  PASS: " pass-count "  WARN: " warn-count "  FAIL: " fail-count))
    (println)
    (doseq [f findings]
      (let [sc (status-char (:status f))]
        (println (str "[" sc "] " (:label f)))
        (when (seq (:terms-not-found f))
          (println (str "       Terms NOT found: " (pr-str (:terms-not-found f)))))
        (when (seq (:expected-missing f))
          (doseq [m (:expected-missing f)]
            (println (str "       MISSING: " m))))
        (when (seq (:deprecated-hits f))
          (println (str "       Deprecated refs present: " (pr-str (:deprecated-hits f)))))))
    (println)
    (println "=== End Report ===")
    {:total total :pass pass-count :warn warn-count :fail fail-count}))

(defn print-json-report
  "Print findings as JSON for programmatic consumption."
  [findings]
  (println "{")
  (println "  \"timestamp\": \"" (str (java.time.Instant/now)) "\",")
  (println "  \"findings\": [")
  (doseq [f findings]
    (println "    {")
    (println "      \"query-id\": " (pr-str (:query-id f)) ",")
    (println "      \"status\": " (pr-str (:status f)) ",")
    (println "      \"terms-not-found\": " (pr-str (:terms-not-found f)) ",")
    (println "      \"expected-missing\": " (pr-str (:expected-missing f)) ",")
    (println "      \"deprecated-hits\": " (pr-str (:deprecated-hits f)))
    (println "    },"))
  (println "  ]")
  (println "}"))

;; ---
;; Phase 2: Structural cross-reference checks
;; ---

(defn- normalize-name
  "Normalize scenario/golden names for comparison.
   S01_baseline-happy-path -> s01-baseline-happy-path
   s01-baseline-happy-path -> s01-baseline-happy-path"
  [n]
  (-> n str/lower-case (str/replace "_" "-")))

(defn- numbered-scenario? [n]
  (re-matches #"s\d+[a-z]?(-.*)?" n))

(defn- fixture-golden? [n]
  (not (numbered-scenario? n)))

(defn- check-scenario-golden-parity
  "Verify every numbered scenario JSON has a corresponding golden report.edn.
   Fixture-based goldens (eq-, spe-, governance-, etc.) without standalone
   scenario JSONs are expected — they come from fixture suites, not scenario files."
  [root-dir]
  (let [scenarios-dir (io/file root-dir "scenarios")
        golden-dir    (io/file root-dir "data/fixtures/golden")
        scenario-names (->> (.listFiles scenarios-dir)
                            (filter #(str/ends-with? (.getName %) ".json"))
                            (remove #(.startsWith (.getName %) "debug-"))
                            (remove #(.startsWith (.getName %) "dynamic-"))
                            (map #(-> (.getName %) (str/replace #"\.json$" "") normalize-name))
                            set)
        golden-names   (->> (.listFiles golden-dir)
                            (filter #(str/ends-with? (.getName %) ".report.edn"))
                            (map #(-> (.getName %) (str/replace #"\.report\.edn$" "") normalize-name))
                            set)
        ;; Only check numbered S## scenarios against golden reports
        numbered-scenarios (filter numbered-scenario? scenario-names)
        numbered-goldens   (filter numbered-scenario? golden-names)
        missing-golden (sort (remove (set numbered-goldens) numbered-scenarios))
        orphan-golden  (sort (remove (set numbered-scenarios) numbered-goldens))
        fixture-count  (count (filter fixture-golden? golden-names))]
    {:query-id :scenario-golden-parity
     :label "Scenario <-> golden report parity"
     :status (if (and (empty? missing-golden) (empty? orphan-golden)) :pass :fail)
     :scenario-count (count numbered-scenarios)
     :golden-count (count numbered-goldens)
     :fixture-golden-count fixture-count
     :missing-golden (vec missing-golden)
     :orphan-golden (vec orphan-golden)}))

(defn- check-notebook-scenario-refs
  "Verify notebooks reference only scenarios that actually exist.
   Checks both scenario JSON files and golden reports (fixture-based scenarios).
   Matches on normalized names (case-insensitive, underscore/hyphen agnostic)."
  [root-dir]
  (let [scenarios-dir (io/file root-dir "scenarios")
        golden-dir    (io/file root-dir "data/fixtures/golden")
        scenario-ids (set
                      (concat
                       (->> (.listFiles scenarios-dir)
                            (filter #(str/ends-with? (.getName %) ".json"))
                            (map #(-> (.getName %) (str/replace #"\.json$" "") normalize-name)))
                       (->> (.listFiles golden-dir)
                            (filter #(str/ends-with? (.getName %) ".report.edn"))
                            (map #(-> (.getName %) (str/replace #"\.report\.edn$" "") normalize-name)))))
        notebooks-dir (io/file root-dir "notebooks")
        notebook-files (filter #(str/ends-with? (.getName %) ".clj") (.listFiles notebooks-dir))
        notebook-refs (mapcat
                       (fn [nf]
                         (let [txt (slurp nf)]
                           (->> (re-seq #"S\d+" txt)
                                (map normalize-name)
                                (keep (fn [sid]
                                        (let [matched (some #(when (str/starts-with? % sid) %) scenario-ids)]
                                          (when-not matched
                                            {:notebook (.getName nf) :scenario-ref sid})))))))
                       notebook-files)]
    {:query-id :notebook-scenario-refs
     :label "Notebook scenario references"
     :status (if (empty? notebook-refs) :pass :fail)
     :broken-refs (vec notebook-refs)}))

(defn- check-suite-scenario-refs
  "Verify suite manifest scenarios all exist."
  [root-dir]
  (let [suites-dir (io/file root-dir "data/fixtures/suites")
        suite-files (filter #(str/ends-with? (.getName %) ".edn") (.listFiles suites-dir))
        scenario-names (->> (io/file root-dir "scenarios")
                            (.listFiles)
                            (filter #(str/ends-with? (.getName %) ".json"))
                            (map #(-> (.getName %) normalize-name))
                            (into #{}))
        all-missing (mapcat
                     (fn [sf]
                       (try
                         (let [suite (edn/read-string (slurp sf))
                               refs (keep :scenario (:scenarios suite))]
                           (keep (fn [r]
                                   (let [fname (normalize-name (name r))]
                                     (when-not (contains? scenario-names fname)
                                       {:suite (.getName sf) :ref r})))
                                 refs))
                         (catch Exception _ [])))
                     suite-files)]
    {:query-id :suite-scenario-refs
     :label "Suite <-> scenario file references"
     :status (if (seq all-missing) :fail :pass)
     :missing-refs (vec all-missing)}))

(defn run-structural-checks
  "Run all structural cross-reference checks."
  [root-dir]
  [(check-scenario-golden-parity root-dir)
   (check-notebook-scenario-refs root-dir)
   (check-suite-scenario-refs root-dir)])

;; ---
;; Report generation
;; ---

(defn- structural-status-char [status]
  (case status
    :pass "PASS"
    :fail "FAIL"
    "----"))

(defn print-structural-report
  "Print structural check results."
  [findings]
  (println "--- Structural Cross-References ---")
  (doseq [f findings]
    (let [sc (structural-status-char (:status f))]
      (println (str "[" sc "] " (:label f)))
      (case (:query-id f)
        :scenario-golden-parity
        (do (println (str "       Numbered S## scenarios: " (:scenario-count f) "  Golden reports: " (:golden-count f) "  Fixture goldens: " (:fixture-golden-count f)))
            (when (seq (:missing-golden f))
              (println (str "       Scenarios without golden reports: " (pr-str (:missing-golden f)))))
            (when (seq (:orphan-golden f))
              (println (str "       Orphan golden reports (no scenario JSON): " (pr-str (:orphan-golden f))))))
        :notebook-scenario-refs
        (when (seq (:broken-refs f))
          (doseq [br (:broken-refs f)]
            (println (str "       Broken ref: " (:notebook br) " -> " (:scenario-ref br)))))
        :suite-scenario-refs
        (when (seq (:missing-refs f))
          (doseq [mr (:missing-refs f)]
            (println (str "       Missing ref: " (:suite mr) " -> " (:ref mr))))))))
  (println))

;; ---
;; Entry point
;; ---

(defn -main
  ([] (-main "."))
  ([root-dir]
   (println "Search-docs sentinel starting... (scanning" (count scan-paths) "directories)\n")
   (let [queries (edn/read-string (slurp queries-path))
         query-findings (mapv #(run-query % root-dir) queries)
         structural-checks (run-structural-checks root-dir)
         all-findings (concat query-findings structural-checks)
         summary (print-report query-findings)]
     (println)
     (print-structural-report structural-checks)
     (println (str "Scan paths: " (pr-str scan-paths)))
     (println (str "Queries file: " queries-path))
     summary)))

;; When run via clojure -M:search-docs, -main is called by -m
;; When run directly via clojure -M scripts/search_docs_sentinel/run.clj
(when (= *file* (System/getProperty "cli.script"))
  (-main))
