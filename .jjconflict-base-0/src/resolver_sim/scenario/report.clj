(ns resolver-sim.scenario.report
  "Canonical table report for deterministic scenario collection summaries.

   Reporting contract:
   - Pass/fail status comes only from each entry's `:pass?` (set by `scenario.runner`).
   - `:checks` and legacy fields explain failures; this namespace never re-derives pass.
   - For detailed fixture sections (theory/yield columns), use `sim.result-display`.

   Prints only — no replay, no judgement."
  (:require [clojure.string :as str]
            [resolver-sim.scenario.theory-result :as theory-result]))

(defn- status-label
  [{:keys [pass? expected-fail? attributes]}]
  (let [status (cond
                 (and pass? expected-fail?) "✓ XFAIL"
                 pass? "✓ PASS"
                 :else "✗ FAIL")]
    (if-let [al (when (seq attributes)
                  (str/join " " (map (fn [[k v]] (str (name k) "=" v)) attributes)))]
      (str status " " al)
      status)))

(defn- format-violation
  [v]
  (case (:type v)
    :metric-violation
    (format "metric %s %s %s (actual %s)"
            (:name v) (:op v) (:expected v) (:actual v))

    :terminal-mismatch
    (format "terminal %s expected %s (actual %s)" (:path v) (:expected v) (:actual v))

    :expected-outcome-mismatch
    (format "seq %s %s expected %s (actual %s)" (:seq v) (:action v) (:expected v) (:actual v))

    :invariant-failed
    (format "invariant %s (%s)" (:invariant v) (:note v))

    (str v)))

(defn- report-detail
  [opts]
  (cond
    (:report-detail opts) (keyword (:report-detail opts))
    (:verbose? opts) :verbose
    :else :compact))

(defn- format-golden-check-lines
  "Render `:checks :golden` failure detail (compact vs verbose/audit)."
  [g opts]
  (let [summary    (or (:summary g) "mismatch")
        mismatches (or (:mismatches g) [])
        expanded?  (#{:verbose :audit} (report-detail opts))
        max-inline (if expanded? (count mismatches) 2)]
    (cond
      (= :missing-golden (:error g))
      (cond-> [(str "  golden: " summary)]
        (:golden-path g) (conj (str "  file: " (:golden-path g))))

      (empty? mismatches)
      [(str "  golden: " summary)]

      (= 1 (count mismatches))
      (let [{:keys [path expected actual]} (first mismatches)]
        [(str "  golden: " summary)
         (str "  path: " (pr-str path))
         (str "  expected: " (pr-str expected))
         (str "  actual:   " (pr-str actual))])

      :else
      (into [(str "  golden: " summary)]
            (map (fn [{:keys [path expected actual]}]
                   (format "  %s expected %s, actual %s"
                           (pr-str path) (pr-str expected) (pr-str actual)))
                 (take max-inline mismatches))))))

(defn- format-golden-pass-note
  "Optional note when golden matched — verbose/audit only."
  [checks opts]
  (when-let [g (:golden checks)]
    (when (and (:ok? g true) (#{:verbose :audit} (report-detail opts)))
      ["  golden: match"])))

(defn format-check-failures
  "Explain why an entry failed — does not determine pass/fail.

   Reads `:checks` (and `:halt-reason` when no structured halt check exists).
   Callers must gate on `:pass?`.

   opts:
     :report-detail — :compact (default) | :verbose | :audit
     :verbose?      — alias for :report-detail :verbose"
  ([entry] (format-check-failures entry {}))
  ([entry opts]
   (let [checks (:checks entry)]
     (vec
      (concat
       (when-let [fo (:fixture-outcome checks)]
         (when-not (:ok? fo true)
           [(format "  outcome: expected %s (got %s)" (:expected fo) (:actual fo))]))
       (when-let [h (:halt checks)]
         (when-not (:ok? h true)
           [(format "  halt: expected %s (got %s)" (:expected h) (:actual h))]))
       (when (and (nil? (:halt checks)) (:halt-reason entry))
         [(str "  halt: " (:halt-reason entry))])
       (when-let [eo (:expected-outcomes checks)]
         (when-not (:ok? eo true)
           (map #(str "  expected-outcome: " (format-violation %)) (:violations eo))))
       (when-let [exp (:expectations checks)]
         (when-not (:ok? exp true)
           (map #(str "  expectation: " (format-violation %)) (:violations exp))))
       (when-let [th (:theory checks)]
         (when-not (:ok? th true)
           (if-let [res (:result th)]
             [(str "  theory: " (theory-result/result-display-label res))]
             ["  theory: check failed"])))
       (when-let [th (:thresholds checks)]
         (when-not (:ok? th true)
           (map #(str "  threshold: " (or (:detail %) (str (:type %) " " (:violations th)))) (:violations th))))
       (when-let [g (:golden checks)]
         (when-not (:ok? g true)
           (format-golden-check-lines g opts)))
       (format-golden-pass-note checks opts))))))

(defn print-report
  "Print the canonical deterministic scenario table from a collection summary.

   Status column uses each entry's `:pass?` only. Failure detail uses
   `format-check-failures` (`:checks` + `:halt-reason`).

   Returns exit code 0 when `:ok?` is true or `:passed` equals `:total`, else 1.

   Options:
     :title          — header title string
     :verbose?       — print step outline for every scenario
     :show-failures? — print outline for failing scenarios (default true)
     :show-checks?   — print failure one-liners (default true)
     :report-detail  — :compact | :verbose | :audit (golden diff detail)
     :outline-printer — (fn [display-name replay-result]) for failure/verbose outlines"
  ([summary] (print-report summary {}))
  ([summary opts]
   (let [{:keys [passed total elapsed-ms suite-id ok? results]} summary
         verbose?       (:verbose? opts false)
         show-failures? (:show-failures? opts true)
         show-checks?   (:show-checks? opts true)
         title          (or (:title opts)
                            (if suite-id
                              (format "Scenario suite: %s" (name suite-id))
                              "Deterministic scenario collection"))
         w              72]
     (println (apply str (repeat w "═")))
     (println (format "  %s" title))
     (println (apply str (repeat w "═")))
     (println (format "  %-47s %5s  %7s  %s" "Scenario" "steps" "reverts" "status"))
     (println (str "  " (apply str (repeat (- w 2) "─"))))
     (doseq [entry results]
       (let [status (status-label entry)
             extra  (when (pos? (:violations entry 0))
                      (format "  violations=%d" (:violations entry)))]
         (println (format "  %s  %-45s %5d  %7d%s"
                          status (:name entry) (:steps entry) (:reverts entry) (or extra "")))
         (when (and (not (:pass? entry)) show-checks?)
           (when-let [sid (or (:scenario-id entry) (:trace-id entry))]
             (println (format "  id: %s" sid)))
           (doseq [line (format-check-failures entry opts)]
             (println line)))
         (when (and (:pass? entry) show-checks?)
           (doseq [line (format-golden-pass-note (:checks entry) opts)]
             (println line)))))
     (println (apply str (repeat w "─")))
     (println (format "  %d/%d passed  (%.1f s)" passed total (/ elapsed-ms 1000.0)))
     (println (apply str (repeat w "═")))

     (when-let [outline (:outline-printer opts)]
       (when (or verbose? show-failures?)
         (doseq [{:keys [name pass? details]} results
                 :when (or verbose? (not pass?))
                 detail details]
           (outline name detail))))

     (if (or (true? ok?) (= passed total)) 0 1))))
