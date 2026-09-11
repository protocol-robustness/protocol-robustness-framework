(ns resolver-sim.sim.reporter
  "Legacy fixture stdout reporter — detailed sections via `sim.result-display`.

   For the canonical deterministic table, use `scenario.report/print-report`.
   This namespace is not used for pass/fail judgement."
  (:require [clojure.string :as str]
            [resolver-sim.definitions.registry :as defs]
            [resolver-sim.scenario.schema-profile :as schema-profile]
            [resolver-sim.scenario.outcome-semantics :as ose]
            [resolver-sim.sim.result-display :as display]))

(defn print-suite-results
  "Print human-readable fixture suite output.

   opts:
     :result-display-level — see `resolver-sim.sim.result-display/display-levels`
     :verbose? / :show-failures? — legacy aliases
     :elapsed-ms — optional wall time (not stored on suite-result)
     :expectations-by-trace-id — display-only expectations decls by trace-id"
  ([suite-result] (print-suite-results suite-result {}))
  ([suite-result opts]
   (doseq [line (display/suite-report-lines suite-result opts)]
     (println line))
   nil))

;; ---------------------------------------------------------------------------
;; Coverage reporting (unchanged — separate concern from fixture display levels)
;; ---------------------------------------------------------------------------

(defn- purpose-label [p]
  (ose/purpose-label p))

(defn- transition-label [tr]
  (or (get-in (defs/transition-def tr) [:label])
      (name tr)))

(defn print-coverage
  "Print a human-readable coverage report from a map returned by
   resolver-sim.scenario.coverage/coverage-report."
  [report]
  (let [total     (:total report 0)
        versions  (:schema-versions report {})
        by-purpose (:by-purpose report {})
        tag-freq  (:threat-tag-freq report {})
        transition-freq (:transition-hit-freq report {})
        transition-by-purpose (:transition-by-purpose-hit-freq report {})
        guard-freq (:guard-hit-freq report {})
        guard-by-purpose (:guard-by-purpose-hit-freq report {})
        unhit-transitions (:unhit-transitions report [])
        uncl      (:unclassified-count report 0)
        enriched-version (or (:enriched-version report) (schema-profile/enriched-version))
        enriched-count (get versions enriched-version 0)]
    (println "\n╔══════════════════════════════════════════════════════════════════╗")
    (println "║  Scenario Coverage Report                                        ║")
    (println "╚══════════════════════════════════════════════════════════════════╝")
    (println (str "\n  Scanned: " (:scanned-dir report)))
    (println (str "  Total scenarios: " total))
    (println)

    ;; Schema version breakdown
    (println "  Schema versions:")
    (doseq [[v cnt] (sort-by key versions)]
      (let [bar (str/join "" (repeat cnt "█"))]
        (println (str "    v" v "  " (format "%3d" cnt) "  " bar))))
    (println)

    ;; By purpose
    (println "  By purpose:")
    (let [purpose-order [:regression :adversarial-robustness :theory-falsification :unclassified]
          all-purposes  (distinct (concat purpose-order (keys by-purpose)))]
      (doseq [p all-purposes
              :let [ids (get by-purpose p [])]
              :when (seq ids)]
        (println (str "    " (format "%-28s" (purpose-label p)) (count ids) " scenario(s)"))
        (doseq [id ids]
          (println (str "      · " (if (keyword? id) (name id) id))))))
    (println)

    ;; Threat tags
    (if (seq tag-freq)
      (do
        (println "  Threat tags (by frequency):")
        (doseq [[tag cnt] (sort-by (comp - val) tag-freq)]
          (println (str "    " (format "%-32s" (name tag)) cnt " scenario(s)"))))
      (println "  Threat tags: none tagged"))
    (println)

    ;; Transition coverage
    (if (seq transition-freq)
      (do
        (println "  Transition hits (all scenarios):")
        (doseq [[tr cnt] (sort-by (comp - val) transition-freq)]
          (println (str "    " (format "%-32s" (transition-label tr)) cnt " hit(s)"))))
      (println "  Transition hits: none"))
    (println)

    ;; Transition coverage by purpose
    (when (seq transition-by-purpose)
      (println "  Transition hits by purpose:")
      (doseq [[p tf] (sort-by (comp str key) transition-by-purpose)
              :when (seq tf)]
        (println (str "    " (format "%-28s" (purpose-label p))))
        (doseq [[tr cnt] (sort-by (comp - val) tf)]
          (println (str "      · " (format "%-28s" (transition-label tr)) cnt)))))
    (println)

    ;; Guard coverage
    (if (seq guard-freq)
      (do
        (println "  Guard hits (all scenarios):")
        (doseq [[g cnt] (sort-by (comp - val) guard-freq)]
          (println (str "    " (format "%-32s" (name g)) cnt " hit(s)"))))
      (println "  Guard hits: none"))
    (println)

    (when (seq guard-by-purpose)
      (println "  Guard hits by purpose:")
      (doseq [[p gf] (sort-by (comp str key) guard-by-purpose)
              :when (seq gf)]
        (println (str "    " (format "%-28s" (purpose-label p))))
        (doseq [[g cnt] (sort-by (comp - val) gf)]
          (println (str "      · " (format "%-28s" (name g)) cnt)))))
    (println)

    ;; Explicit release backlog
    (println "  Unhit transitions backlog (release gate):")
    (if (seq unhit-transitions)
      (doseq [tr unhit-transitions]
        (println (str "    · " (transition-label tr))))
      (println "    none (all canonical transitions covered)"))
    (println)

    ;; Summary
    (println (str "  v" enriched-version " enriched:      " enriched-count " / " total " scenarios"))
    (println (str "  Unclassified (pre-v" enriched-version "): " uncl " scenarios without :purpose or :threat-tags"))
    (when (pos? uncl)
      (println (str "  → " uncl " scenarios could be enriched with :id, :title, :purpose, :threat-tags")))
    (println)))
