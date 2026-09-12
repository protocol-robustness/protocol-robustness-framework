(ns resolver-sim.sim.result-display
  "Pure display formatting for legacy fixture suite reports.

   Not the canonical pass/fail source — reads `:pass?` from each entry.
   For failure lines, delegates to `scenario.report/format-check-failures`.
   For the standard deterministic table, use `scenario.report/print-report`."
  (:require [clojure.string :as str]
            [resolver-sim.scenario.report :as scenario-report]
            [resolver-sim.scenario.theory-result :as theory-result]
            [resolver-sim.scenario.yield-metrics :as yield-metrics]))

(def display-levels
  #{:summary :failures :standard :verbose :audit})

(defn effective-display-level
  "`:audit` currently mirrors `:verbose` until golden-diff UX exists."
  [level]
  (if (= level :audit) :verbose level))

(defn resolve-display-level
  "Normalize explicit level and legacy reporter opts.

   Priority: `:result-display-level` > `:verbose?` > `:show-failures?` > default.

   Throws `ex-info` with `:valid-levels` when `:result-display-level` is unknown
   (e.g. typos like `:verbsoe`) — no silent fallback."
  [opts & {:keys [default] :or {default :summary}}]
  (let [level (:result-display-level opts)]
    (cond
      level (let [kw (keyword level)]
              (when-not (contains? display-levels kw)
                (throw (ex-info "Unknown result-display-level"
                                {:level level
                                 :valid-levels (sort display-levels)})))
              kw)
      (:verbose? opts) :verbose
      (false? (:show-failures? opts)) :summary
      (:show-failures? opts) :failures
      :else default)))

(defn scenario-entry-ok?
  "Display gating only — not a second source of pass/fail truth.

   Reads the canonical `:pass?` set by `scenario.runner/scenario-pass?` via
   `build-entry-result` / `finalize-fixture-entry`. Do not re-derive pass
   semantics here; use `scenario.runner/scenario-pass?` in the judgement layer."
  [r]
  (boolean (:pass? r)))

(defn- expectations-decl-for
  [trace-id opts]
  (get-in opts [:expectations-by-trace-id trace-id]))

(defn- yield-expectation-failed? [r]
  (some #(yield-metrics/yield-metric-key? (:name %))
        (:violations (:expectations r))))

(defn- scenario-references-yield?
  [r opts]
  (let [decl (expectations-decl-for (:trace-id r) opts)]
    (or (yield-expectation-failed? r)
        (some #(yield-metrics/yield-metric-key? (:name %))
              (:metrics decl)))))

(defn- expectations-status-label [expectations]
  (cond
    (nil? expectations) "n/a"
    (:ok? expectations) "pass"
    :else "fail"))

(defn- theory-status-label [r]
  (if-let [theory (:theory r)]
    (theory-result/result-display-label theory)
    "n/a"))

(defn- format-violation [v]
  (case (:type v)
    :metric-violation
    (format "%s %s %s (actual %s)"
            (:name v) (:op v) (:expected v) (:actual v))

    :terminal-mismatch
    (format "terminal %s expected %s (actual %s)" (:path v) (:expected v) (:actual v))

    :step-terminal-mismatch
    (format "seq %s terminal %s expected %s (actual %s)"
            (:seq v) (:path v) (:expected v) (:actual v))

    :expected-outcome-mismatch
    (format "seq %s %s expected %s (actual %s)" (:seq v) (:action v) (:expected v) (:actual v))

    :invariant-failed
    (format "invariant %s (%s)" (:invariant v) (:note v))

    (str v)))

(defn- compact-failure-reasons
  "Human-readable failure lines — uses `:checks` via `scenario.report`."
  [r opts]
  (mapv #(if (and (string? %) (.startsWith ^String % "  "))
           (.substring ^String % 2)
           (str %))
        (scenario-report/format-check-failures r opts)))

(defn- format-yield-metrics-line [metrics]
  (let [subset (yield-metrics/yield-metrics-for-display metrics)]
    (when (seq subset)
      (str "yield: "
           (str/join ", "
                     (for [[k v] (sort-by (comp str key) subset)]
                       (str (yield-metrics/yield-metric-label k) "=" v)))))))

(defn- scenario-standard-row [r opts]
  (let [status (if (scenario-entry-ok? r) "pass" "FAIL")
        base   (format "  [%s] %s  outcome=%s  theory=%s  expectations=%s"
                       status (:trace-id r) (:outcome r)
                       (theory-status-label r)
                       (expectations-status-label (:expectations r)))]
    (if (scenario-references-yield? r opts)
      (if-let [yield-line (format-yield-metrics-line (:metrics r))]
        (str base "  " yield-line)
        base)
      base)))

(defn- scenario-verbose-detail [r opts]
  (let [lines (vec
               (concat
                (when-let [attrs (:attributes r)]
                  [(str "    attributes: "
                        (str/join ", " (map (fn [[k v]] (str (name k) " " v)) attrs)))])
                (when (:halt-reason r)
                  [(str "    halt: " (:halt-reason r))])
                (when (and (:expectations r) (not (:ok? (:expectations r))))
                  (map #(str "    expectation: " (format-violation %))
                       (:violations (:expectations r))))
                (when-let [theory (:theory r)]
                  (when (and (= (:status theory) :falsified) (seq (:evidence theory)))
                    (map (fn [e]
                           (str "    theory evidence: "
                                (:metric e) " " (:op e) " " (:value e)
                                " → actual " (:actual e)))
                         (:evidence theory))))
                (when-let [yield-line (format-yield-metrics-line (:metrics r))]
                  [(str "    " yield-line)])))]
    (when (seq lines)
      (into [(str "  " (:trace-id r) ":")] lines))))

(defn- summary-lines [suite-result opts]
  (let [results   (:results suite-result)
        total     (count results)
        passed    (count (filter scenario-entry-ok? results))
        elapsed   (:elapsed-ms opts 0)
        failed-ids (->> results (remove scenario-entry-ok?) (map :trace-id) vec)]
    (cond-> [(str "Suite: " (:suite-id suite-result))
             (str "Status: " (if (:ok? suite-result) "PASS" "FAIL"))
             (str "Scenarios: " passed "/" total
                  (when (pos? elapsed)
                    (format "  (%.1f s)" (/ elapsed 1000.0))))]
      (seq failed-ids) (conj (str "Failed: " (str/join ", " failed-ids))))))

(defn- failures-lines [suite-result opts]
  (let [failed (remove scenario-entry-ok? (:results suite-result))]
    (into (summary-lines suite-result opts)
          (mapcat (fn [r]
                    (into [(str "  " (:trace-id r) ":")]
                          (map #(str "    " %) (compact-failure-reasons r opts))))
                  failed))))

(defn- standard-lines [suite-result opts]
  (into (summary-lines suite-result opts)
        (concat ["----------------------------------------------------------------------"
                 "  scenario                          outcome  expectations  theory"]
                (map #(scenario-standard-row % opts) (:results suite-result)))))

(defn- verbose-lines [suite-result opts]
  (let [failed-ids (set (map :trace-id (remove scenario-entry-ok? (:results suite-result))))
        base       (standard-lines suite-result opts)]
    (into base
          (mapcat (fn [r]
                    (let [detail (scenario-verbose-detail r opts)]
                      (if (contains? failed-ids (:trace-id r))
                        detail
                        ;; Passing entries: show attributes line when present
                        (when-let [attrs (:attributes r)]
                          [(str "  " (:trace-id r) ":")
                           (str "    attributes: "
                                (str/join ", " (map (fn [[k v]] (str (name k) " " v)) attrs)))]))))
                  (:results suite-result)))))

(defn suite-report-lines
  "Return a vector of report lines for a fixture suite result.

   opts:
     :result-display-level — :summary | :failures | :standard | :verbose | :audit
     :verbose? / :show-failures? — legacy aliases (see resolve-display-level)
     :elapsed-ms — optional wall time for summary header
     :expectations-by-trace-id — display-only map of trace-id → :expectations decl"
  [suite-result opts]
  (case (effective-display-level (resolve-display-level opts))
    :summary  (summary-lines suite-result opts)
    :failures (failures-lines suite-result opts)
    :standard (standard-lines suite-result opts)
    :verbose  (verbose-lines suite-result opts)))
