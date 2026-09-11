(ns scripts.generate-scenario-index-docs
  (:require [clojure.string :as str]
            [resolver-sim.protocols.sew.invariant-scenarios :as scenarios]
            [resolver-sim.protocols.sew.invariant-scenarios.doc-summaries :as doc]))

(def ^:private s01-s23-display-order
  "Derived dynamically from the first 23 entries (S01–S23) in the canonical
   all-scenarios list. Paired entries (S12) combine both scenario IDs."
  (let [extract-label (fn [display-name]
                        (re-find #"S\d+[a-z]?" display-name))
        extract-sid   (fn [scenario-or-pair]
                        (if (vector? scenario-or-pair)
                          (str/join " / " (map :scenario-id scenario-or-pair))
                          (:scenario-id scenario-or-pair)))]
    (mapv (fn [[display-name scenario-or-pair]]
            [(extract-label display-name) (extract-sid scenario-or-pair)])
          (take 23 scenarios/all-scenarios))))

(defn- short-scenario-name [sid]
  (when-let [[_ rest] (re-matches #"^s\d+[a-z]?-(.+)$" sid)]
    rest))

(defn- generated-s01-s23-rows []
  (let [sb (StringBuilder.)]
    (doseq [[label sid] s01-s23-display-order]
      (let [summary (if (str/includes? sid " / ")
                      (str (doc/require-s01-s23-summary! "s12a-snapshot-isolation-fee-zero")
                           " / "
                           (doc/require-s01-s23-summary! "s12b-snapshot-isolation-fee-500"))
                      (doc/require-s01-s23-summary! sid))
            name    (if (str/includes? sid " / ")
                      "governance-snapshot-isolation (s12a+s12b)"
                      (short-scenario-name sid))]
        (.append sb (format "| %s | %s | %s |\n" label name summary))))
    (.toString sb)))

(defn- replace-between-markers [content start-marker end-marker replacement]
  (let [start (.indexOf content start-marker)
        end   (.indexOf content end-marker)]
    (if (and (neg? start) (neg? end))
      content
      (str (subs content 0 (+ start (count start-marker)))
           "\n"
           replacement
           "\n"
           (subs content end)))))

(defn -main [& _]
  (let [table     (generated-s01-s23-rows)
        scenarios (slurp "docs/scenarios.md")
        robust    (slurp "docs/ROBUSTNESS_FRAMEWORK.md")
        edge-rows (str/join "\n"
                            (map (fn [[sid summary]]
                                   (format "| `%s` | %s |" sid summary))
                                 (sort-by key doc/robustness-edge-case-summaries)))
        scenarios* (replace-between-markers scenarios
                                            "<!-- GENERATED-S01-S23-START -->"
                                            "<!-- GENERATED-S01-S23-END -->"
                                            table)
        robust*    (replace-between-markers robust
                                              "<!-- GENERATED-EDGE-CASE-SUMMARIES-START -->"
                                              "<!-- GENERATED-EDGE-CASE-SUMMARIES-END -->"
                                              edge-rows)]
    (when (= scenarios scenarios*)
      (println "WARN: docs/scenarios.md missing GENERATED-S01-S23 markers — patch manually")
      (spit "docs/scenarios-S01-S23.generated.md" table))
    (when (= robust robust*)
      (println "WARN: docs/ROBUSTNESS_FRAMEWORK.md missing GENERATED-EDGE-CASE markers")
      (spit "docs/robustness-edge-case.generated.md" edge-rows))
    (when (not= scenarios scenarios*)
      (spit "docs/scenarios.md" scenarios*)
      (println "Updated docs/scenarios.md S01–S23 table"))
    (when (not= robust robust*)
      (spit "docs/ROBUSTNESS_FRAMEWORK.md" robust*)
      (println "Updated docs/ROBUSTNESS_FRAMEWORK.md edge-case summaries"))
    (println "Done.")))
