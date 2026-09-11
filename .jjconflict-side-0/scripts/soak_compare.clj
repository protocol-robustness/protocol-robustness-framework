(ns scripts.soak-compare
  "Compare run-sew-tests results files for the S/F/P soak.

   Each results file is an EDN map written by scripts.run-sew-tests when
   SEW_TEST_RESULTS_FILE is set.  Comparison is semantic, not byte-based:
   only explicitly volatile data (elapsed-ms, output text, temp-root prefixes
   in artifact names) is excluded.  See review §9/§10.

   Usage:
     clojure -M:test:with-sew -m scripts.soak-compare <run-dir>

   <run-dir> contains results-<mode>-<i>.edn files plus run metadata.  Prints a
   comparison verdict and exits non-zero on any inconsistency."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- load-results
  [f]
  (edn/read-string (slurp f)))

(defn- normalized-fingerprint
  "Per-namespace semantic fingerprint with volatile data removed.  Derives
   artifact identity from the declared manifest (not a directory walk), and
   includes completeness and undeclared-write findings."
  [entry]
  {:namespace (:namespace entry)
   :tests (:tests entry)
   :assertions (:assertions entry)
   :failures (:failures entry)
   :errors (:errors entry)
   :failing-vars (vec (sort (:failing-vars entry)))
   :scope-status (:scope-status entry)
   :declared-artifacts (vec (sort-by :relative-path (:declared-artifacts entry)))
   :undeclared-files (vec (sort (:undeclared-files entry)))
   :scope-problems (vec (sort-by :type (:scope-problems entry)))
   :timed-out? (:timed-out? entry)})

(defn- run-fingerprint
  [results-file]
  (let [m (load-results results-file)]
    {:mode (:mode m)
     :seed (:seed m)
     :namespaces (vec (:namespaces m))
     :fingerprints (mapv normalized-fingerprint (:results m))}))

(defn- same-fingerprint?
  [a b]
  (and (= (:namespaces a) (:namespaces b))
       (= (:fingerprints a) (:fingerprints b))))

(defn- list-results
  [dir]
  (->> (file-seq (io/file dir))
       (filter #(and (.isFile %)
                     (str/ends-with? (.getName %) ".edn")
                     (str/starts-with? (.getName %) "results-")))
       (sort-by #(.getName %))
       vec))

(defn compare-runs
  "dir: directory with results-<mode>-<i>.edn files.  Returns {:ok? bool :report [str]}."
  [dir]
  (let [files (list-results dir)]
    (if (empty? files)
      {:ok? false :report [(str "no results-*.edn files in " dir)]}
      (let [by-mode (group-by (fn [f] (or (second (re-matches #"results-(.+)-\d+\.edn" (.getName f)))
                                          "unknown"))
                              files)
            groups (sort-by key (vec by-mode))
            fingerprints (into {} (map (fn [[mode fs]]
                                         [mode (mapv #(run-fingerprint %) (sort-by #(.getName %) fs))])
                                       groups))
            ;; internal consistency within each mode
            internal (mapcat (fn [[mode fps]]
                               (let [ok? (every? #(same-fingerprint? % (first fps)) fps)]
                                 (if ok?
                                   []
                                   [{:level :fail
                                     :msg (str mode " not internally consistent across "
                                               (count fps) " runs")}])))
                             fingerprints)
            ;; F == P equivalence
            f-p (let [f (get fingerprints "isolated-sequential")
                      p (get fingerprints "isolated-parallel")]
                  (cond
                    (or (empty? f) (empty? p))
                    [{:level :info :msg "missing F or P runs; skipping F==P check"}]

                    (every? (fn [pf] (some #(same-fingerprint? % pf) f)) p)
                    []

                    :else
                    [{:level :fail
                      :msg "isolated-parallel fingerprints differ from isolated-sequential"}]
                    ))
            ;; S baseline reference (not a hard gate; flags coupling)
            s-flag (let [s (get fingerprints "shared-sequential")
                         f (get fingerprints "isolated-sequential")]
                     (cond
                       (or (empty? s) (empty? f))
                       [{:level :info :msg "missing S runs; skipping S vs F coupling check"}]

                       (every? (fn [ff] (some #(same-fingerprint? % ff) s)) f)
                       []

                       :else
                       [{:level :warn
                         :msg "S vs F differ (possible hidden cross-namespace coupling — triage)"}])
                     )
            findings (concat internal f-p s-flag)
            ok? (not-any? #(= :fail (:level %)) findings)]
        {:ok? ok?
         :report (concat [(str "compared " (count files) " result files: "
                               (str/join ", " (map #(str (key %) "=" (count (val %))) groups)))
                          (str "groups: " (pr-str (into {} (map (fn [[k v]] [k (count v)]) fingerprints))))]
                         (map #(str "[" (:level %) "] " (:msg %)) findings))}))))

(defn -main
  [& args]
  (let [dir (or (first args)
                (do (println "Usage: -m scripts.soak-compare <run-dir>")
                    (System/exit 1)))
        {:keys [ok? report]} (compare-runs dir)]
    (doseq [l report] (println l))
    (when-not ok?
      (println "\nSOAK COMPARISON: FAILED — diagnostics retained in" dir)
      (System/exit 1))
    (println "\nSOAK COMPARISON: OK")))
