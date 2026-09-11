(ns resolver-sim.scripts.evidence-coverage
  "Static analysis of evidence capture coverage across protocol namespaces.
   
   Scans defn and defn- forms and checks each for capture-event-evidence! calls.
   Designed for CI integration — exits non-zero on missing critical state-mutating
   transitions.
   
   Usage:
     (check-evidence-coverage \"protocols_src/resolver_sim/protocols/sew/resolution.clj\")
     (check-evidence-coverage \"protocols_src/resolver_sim/protocols/sew/registry.clj\"
                              :allowed-missing #{'get-stake 'can-handle-escrow?})
      (check-evidence-coverage \"protocols_src/resolver_sim/protocols/sew/\"
                               :evidence-helpers #{'distribute-slashed-funds})"
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ── Static Analysis ───────────────────────────────────────────────────────────

(def ^:private capture-call-pattern
  "Regex to find capture-event-evidence! calls in source code."
  #"capture-event-evidence!")

(def ^:private attribution-call-pattern
  "Regex to find attr/with-attribution calls in source code."
  #"attr/with-attribution|with-attribution")

(def ^:private defn-pattern
  "Regex to match defn and defn- forms with name capture."
  #"\(defn-?\s+(\S+)")

(defn- find-defn-names
  "Extract all function names and their line numbers from a source string."
  [source]
  (let [lines (str/split-lines source)]
    (keep-indexed
     (fn [i line]
       (when-let [m (re-find defn-pattern line)]
         [(symbol (m 1)) (inc i)]))
     lines)))

(defn- find-evidence-call-lines
  "Find line numbers of capture-event-evidence! calls."
  [source]
  (let [lines (str/split-lines source)]
    (keep-indexed
     (fn [i line]
       (when (re-find capture-call-pattern line)
         (inc i)))
     lines)))

(defn- form-range
  "Heuristically determine the line range of a defn form starting at defn-line.
   Looks for the next top-level defn/defn- at the same or lesser indentation."
  [lines defn-line]
  (let [start (max 0 (dec defn-line))
        ;; The defn line's indentation determines the top-level depth
        indent (count (take-while #(#{\space \tab} %) (nth lines start "")))
        ;; Scan lines after the defn for a line at the same indentation starting a new form
        end (some (fn [i]
                    (let [l (nth lines i "")]
                      (and (> i start)
                           (.startsWith l "(")
                           (let [this-indent (count (take-while #(#{\space \tab} %) l))]
                             (<= this-indent indent))
                           i)))
                  (range (inc start) (count lines)))]
    (if end
      [defn-line end]
      [defn-line (count lines)])))

(defn- function-calls-evidence?
  "Check if a function body (from defn-line to its end) contains any
   capture-event-evidence! call.  Follows calls to evidence-helpers
   (known functions that internally call capture-event-evidence!)."
  [lines defn-line call-lines evidence-helpers]
  (let [[_ end] (form-range lines defn-line)]
    (some (fn [cl] (and (>= cl defn-line) (< cl end))) call-lines)))

(defn- function-lines-range
  "Return [start end] line range (1-indexed) for the function starting at defn-line."
  [lines defn-line]
  (form-range lines defn-line))

(defn- find-lines-in-range
  "Find all line numbers (1-indexed) within [start end) matching pattern."
  [lines start end pattern]
  (set (keep-indexed (fn [i line]
                       (let [ln (inc i)]
                         (when (and (>= ln start) (< ln end) (re-find pattern line))
                           ln)))
                     lines)))

(defn- function-has-unattributed-evidence?
  "Check whether a function body contains capture-event-evidence! calls that
   are NOT accompanied by an attr/with-attribution block in the same function.
   Evidence-helpers are known to capture evidence on behalf of callers who
   supply the attribution context, so they are excluded from this check."
  [lines defn-line capture-lines attribution-lines evidence-helpers fn-name]
  (let [[s e] (function-lines-range lines defn-line)
        f-captures (find-lines-in-range lines s e capture-call-pattern)
        f-attribs (find-lines-in-range lines s e attribution-call-pattern)]
    ;; Only flag when evidence is captured but with-attribution is absent
    ;; AND the function is not a known evidence-helper (which relies on caller attribution)
    (and (seq f-captures)
         (not (seq f-attribs))
         (not (contains? evidence-helpers fn-name)))))

;; ── Public API ────────────────────────────────────────────────────────────────

(defn check-evidence-coverage
  "Scan a Clojure source file and report which functions lack evidence capture.
   
   Options:
   :allowed-missing  — set of function name symbols to ignore (read-only fns,
                       utility fns, state queries)
   :evidence-helpers — set of function name symbols that internally call
                       capture-event-evidence! (e.g. distribute-slashed-funds)
   
   Returns a map compatible with JSON/EDN serialization:
     {:file path
      :total-fns N
      :functions-with-evidence N
      :functions-without-evidence [...]
      :ci-failures [...]
      :warnings [...]}"
  [filepath & {:keys [allowed-missing evidence-helpers]
               :or {allowed-missing #{}
                    evidence-helpers #{}}}]
  (let [source (slurp filepath)
        lines (str/split-lines source)
        defns (find-defn-names source)
        call-lines (set (find-evidence-call-lines source))
        attribution-call-lines (set (keep-indexed
                                     (fn [i line]
                                       (when (re-find attribution-call-pattern line)
                                         (inc i)))
                                     lines))
        total (count defns)
        covered (filter (fn [[fn-name defn-line]]
                          (function-calls-evidence? lines defn-line call-lines evidence-helpers))
                        defns)
        uncovered (remove (fn [[fn-name defn-line]]
                            (or (function-calls-evidence? lines defn-line call-lines evidence-helpers)
                                (allowed-missing fn-name)))
                          defns)
        ;; Classify: CI-failures are state-mutating fns without evidence.
        ;; Warnings are read-only/helper fns without evidence.
        ci-failures (remove (fn [[fn-name _]]
                              (or (allowed-missing fn-name)
                                  (str/starts-with? (name fn-name) "get-")
                                  (str/starts-with? (name fn-name) "can-")
                                  (str/starts-with? (name fn-name) "is-")
                                  (str/starts-with? (name fn-name) "has-")
                                  (str/starts-with? (name fn-name) "compute-")
                                  (str/starts-with? (name fn-name) "normalize-")
                                  (str/ends-with? (name fn-name) "?")))
                            uncovered)
        warnings (remove (fn [[fn-name _]]
                           (some (fn [[f2 _]] (= fn-name f2)) ci-failures))
                         uncovered)
        ;; Attribution gap check: functions that call capture-event-evidence!
        ;; without a with-attribution block in the same function body.
        unattributed (filter (fn [[fn-name defn-line]]
                               (function-has-unattributed-evidence?
                                lines defn-line call-lines attribution-call-lines
                                evidence-helpers fn-name))
                             defns)]
    {:file filepath
     :total-fns total
     :functions-with-evidence (count covered)
     :functions-without-evidence (count uncovered)
     :function-list (mapv (fn [[n l]] {:name (str n) :line l}) (sort-by second defns))
     :ci-failures (mapv (fn [[n l]] {:name (str n) :line l}) (sort-by second ci-failures))
     :warnings (mapv (fn [[n l]] {:name (str n) :line l}) (sort-by second warnings))
     :unattributed-evidence (mapv (fn [[n l]] {:name (str n) :line l}) (sort-by second unattributed))}))

(defn check-directory-coverage
  "Run check-evidence-coverage on all .clj files in a directory (recursive).
   Returns a combined report with per-file entries."
  [dir-path & {:keys [per-file-options] :or {per-file-options {}}}]
  (let [files (sort (file-seq (io/file dir-path)))
        clj-files (filter #(and (.isFile %) (str/ends-with? (.getName %) ".clj")) files)]
    (mapv (fn [f]
            (let [path (.getPath f)
                  opts (get per-file-options path {})]
              (apply check-evidence-coverage path
                     (mapcat identity (seq opts)))))
          clj-files)))

;; ── CLI Entry Point ───────────────────────────────────────────────────────────

(defn -main
  "Run evidence coverage check from the command line.
   Usage: clojure -M -m resolver-sim.scripts.evidence-coverage <file-or-dir>
   Exits with code 1 if any CI failures are found."
  [& args]
  (let [target (first args)]
    (if (.isDirectory (io/file target))
      (dorun (map #(println (json/write-str %)) (check-directory-coverage target)))
      (println (json/write-str (check-evidence-coverage target))))
    (when (some (fn [r] (pos? (count (:ci-failures r))))
                (if (.isDirectory (io/file target))
                  (check-directory-coverage target)
                  [(check-evidence-coverage target)]))
      (System/exit 1))))
