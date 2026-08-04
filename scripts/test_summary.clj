(ns scripts.test-summary
  "Shared rendering for every test runner so PASS/FAIL, totals, and failure
   attribution look identical regardless of which runner produced them.

   The contract for all runners:
     1. per-item output (PASS/FAIL per namespace or suite),
     2. one border box with totals + timing,
     3. one machine-readable RESULT: line,
     4. a FAILURES section naming the exact failing tests/suites (when any)."
  (:require [clojure.string :as str]))

(defn render-box
  "Print a border box sized to its content (no hardcoded widths)."
  [title lines]
  (let [content (cons title lines)
        width (apply max (map count content))
        edge (str "+" (apply str (repeat (+ width 4) "-")) "+")]
    (println edge)
    (doseq [line content]
      (println (str "|  " line (apply str (repeat (- width (count line)) " ")) "  |")))
    (println edge)))

(defn result-line
  "Print the canonical machine-readable result line, e.g.
   RESULT: FAIL  120 tests, 118 assertions, 2 failures, 0 errors  3.4s"
  [totals elapsed-ms]
  (let [{:keys [test pass fail error]} totals
        outcome (if (pos? (+ fail error)) "FAIL" "PASS")]
    (println (format "RESULT: %s  %d tests, %d assertions, %d failures, %d errors  %.2fs"
                     outcome test pass fail error (/ (double elapsed-ms) 1000.0)))))

(defn first-failing-tests
  "Extract the failing/erroring deftests from a clojure.test output buffer.

   Parses lines of the form:
     FAIL in (ns/test-name) (file.clj:12)
     ERROR in (ns/test-name) (file.clj:12)"
  [output]
  (->> (str/split-lines (str output))
       (mapcat #(re-seq #"(?m)^(?:FAIL|ERROR) in \(([^)]*)\) \(([^)]*)\)" %))
       (map (fn [[_ test loc]] (str test "  @" loc)))
       (distinct)
       (take 8)))

(defn print-failures
  "Print a standardized FAILURES section naming exactly which items failed and
   their first failing tests.  items: seq of {:label s :failures [s ...]}."
  [items]
  (let [failing (filter (fn [it] (seq (:failures it))) items)]
    (when (seq failing)
      (println)
      (println "FAILURES:")
      (doseq [{:keys [label failures]} failing]
        (println (str "  " label))
        (doseq [f failures]
          (println (str "    " f))))
      (println "  (hint: bb test:rerun to rerun failed namespaces)"))))
