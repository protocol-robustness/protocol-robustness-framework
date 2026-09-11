(ns resolver-sim.benchmark.review-formatter
  "Render a reviewer-oriented Markdown view of a benchmark evidence bundle.

   The original EDN bundle remains authoritative. This formatter only presents
   fields already present in the bundle; it does not infer coverage or claims."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.benchmark.reversal-audit :as reversal-audit]))

(defn- display
  [value]
  (cond
    (nil? value) "—"
    (keyword? value) (str "`" value "`")
    :else (str value)))

(defn- markdown-cell
  [value]
  (-> (display value)
      (str/replace "|" "\\|")
      (str/replace "\n" "<br>")))

(defn- short-hash
  [hash-value]
  (if (and (string? hash-value) (> (count hash-value) 12))
    (str (subs hash-value 0 12) "…")
    (display hash-value)))

(defn- scenario-label
  [result]
  (or (:scenario/id result)
      (:simulator/scenario-path result)
      (:file result)
      "unidentified scenario"))

(defn- scenario-source-directory
  [result]
  (let [path (or (:simulator/scenario-path result) (:file result))]
    (if (seq path)
      (or (.getParent (io/file path)) ".")
      "unspecified")))

(defn- claim-status-summary
  [claim-results]
  (frequencies (map :claim/outcome claim-results)))

(defn- count-status
  [results status]
  (count (filter #(= status (:outcome %)) results)))

(defn- title
  [bundle]
  (or (get-in bundle [:benchmark :benchmark/id]) :benchmark/unknown))

(defn render-summary
  "Render BENCHMARK_SUMMARY.md from an evidence-bundle map."
  [bundle]
  (let [benchmark (:benchmark bundle)
        metrics (:metrics bundle)
        results (vec (:results bundle))
        claims (vec (:claim-results bundle))
        status (:benchmark/status benchmark)
        experimental? (= :experimental status)
        repo-meta (get-in bundle [:repo :repo])
        run-manifest (:run/manifest bundle)
        artifact-index (:benchmark/artifact-index bundle)
        passed (or (:passed metrics) (count-status results :pass))
        total (or (:total metrics) (count results))]
    (str "# Benchmark Summary — " (title bundle) "\n\n"
         "> Generated from `evidence-bundle.edn`. The original EDN bundle is the authoritative machine-readable record.\n\n"
         "## Result at a glance\n\n"
         "| Field | Value |\n|---|---|\n"
         "| Lifecycle status | " (markdown-cell status) " |\n"
         "| Protocol | " (markdown-cell (:benchmark/protocol benchmark)) " |\n"
         "| Scenario suite | " (markdown-cell (:benchmark/scenario-suite benchmark)) " |\n"
         "| Unique scenarios | " (markdown-cell (:unique-scenario-count metrics)) " |\n"
         "| Executions | " (markdown-cell (or (:execution-count metrics) total)) " |\n"
         "| Declared runs per scenario | " (markdown-cell (:declared-run-count metrics)) " |\n"
         "| Replay outcomes | " passed "/" total " passed |\n"
         "| Evaluated claims | " (count claims) " |\n"
         "| Bundle SHA-256 root hash | `" (display (:evidence/hash bundle)) "` |\n"
         "| Repository commit | " (markdown-cell (:commit repo-meta)) " |\n"
         "| Repository dirty state | " (markdown-cell (:dirty? repo-meta)) " |\n"
         "| Run timestamp | " (markdown-cell (:manifest/at run-manifest)) " |\n\n"
         (when experimental?
           (str "## Experimental status\n\n"
                "This benchmark is marked **experimental**. Its scenario outcomes and claim results must not be interpreted as an active or comprehensive assurance result.\n\n"
                (when-let [reason (:benchmark/experimental-reason benchmark)]
                  (str "**Declared reason:** " reason "\n\n"))))
         "## What was evaluated\n\n"
         (or (:benchmark/purpose benchmark) (:benchmark/description benchmark) "No purpose was recorded in the bundle.") "\n\n"
         (when artifact-index
           (str "## Scenario evidence package\n\n"
                "| Field | Value |\n|---|---|\n"
                "| Artifact index | `" (:path artifact-index) "` |\n"
                "| Artifact-index SHA-256 | `" (:sha256 artifact-index) "` |\n"
                "| Per-execution packages | " (count results) " |\n\n"))
         "## Claim outcome summary\n\n"
         "| Outcome | Count |\n|---|---:|\n"
         (if (seq claims)
           (apply str (for [[outcome n] (sort-by (comp str key) (claim-status-summary claims))]
                        (str "| " (markdown-cell outcome) " | " n " |\n")))
           "| No evaluated claims | 0 |\n")
         "\n## Scope limits\n\n"
         "- Scenario outcomes apply only to the declared workload and runner configuration.\n"
         "- A passing scenario outcome does not by itself establish that all declared claims passed.\n"
         "- This bundle does not by itself establish comprehensive protocol assurance or Solidity/EVM equivalence.\n"
         "- `scenario-results.md` lists executions; a deterministic replay benchmark may intentionally run each scenario more than once.\n\n"
         "## Included review files\n\n"
         "- `scenario-results.md` — one row per benchmark execution, grouped only by source directory.\n"
         "- `claim-results.md` — one row per evaluated claim result.\n"
         "- `reversal-review.md` — per-reversal slash lifecycle projection, when result worlds are available.\n")))

(defn render-scenario-results
  "Render scenario-results.md. Results are intentionally shown per execution."
  [bundle]
  (let [results (vec (:results bundle))]
    (str "# Scenario Results\n\n"
         "> Each row is one **execution**, not necessarily one unique scenario. Grouping below is by scenario source directory, not an independently evaluated scenario-family taxonomy.\n\n"
         (if (seq results)
           (apply str
                  (for [[directory grouped] (sort-by key (group-by scenario-source-directory results))]
                    (str "## Source directory: `" directory "`\n\n"
                         "| Scenario | Run | Outcome | Events | Halt reason | Evidence root | Artifact package |\n"
                         "|---|---:|---|---:|---|---|---|\n"
                         (apply str
                                (for [result (sort-by (juxt scenario-label :benchmark/run-index) grouped)]
                                  (str "| " (markdown-cell (scenario-label result))
                                       " | " (markdown-cell (:benchmark/run-index result))
                                       " | " (markdown-cell (:outcome result))
                                       " | " (markdown-cell (get-in result [:metrics :events-processed]))
                                       " | " (markdown-cell (:halt-reason result))
                                       " | `" (short-hash (:scenario/evidence-root result)) "`"
                                       " | " (markdown-cell (get-in result [:scenario/artifacts :scenario/artifact-dir])) " |\n")))
                         "\n")))
           "_No scenario results were recorded in this bundle._\n"))))

(defn render-claim-results
  "Render claim-results.md."
  [bundle]
  (let [claims (vec (:claim-results bundle))]
    (str "# Claim Results\n\n"
         "> Claim outcomes are independent from scenario outcomes. `:not-exercised`, `:not-implemented`, and `:inconclusive` are not passes.\n\n"
         (if (seq claims)
           (str "| Claim | Scope | Scenario | Outcome | Definition provenance | Definition hash | Evidence / error |\n"
                "|---|---|---|---|---|---|---|\n"
                (apply str
                       (for [claim (sort-by (juxt (comp str :claim/id) (comp str :scenario/id)) claims)]
                         (let [detail (or (seq (:claim/evidence claim)) (:claim/error claim))]
                           (str "| " (markdown-cell (:claim/id claim))
                                " | " (markdown-cell (:claim/scope claim))
                                " | " (markdown-cell (or (:scenario/id claim)
                                                         (:simulator/scenario-path claim)
                                                         (:scenario/file claim)))
                                " | " (markdown-cell (:claim/outcome claim))
                                " | " (markdown-cell (:claim/definition-source claim))
                                " | `" (short-hash (:claim/definition-hash claim)) "`"
                                " | " (markdown-cell detail) " |\n"))))
                "_No evaluated claim results were recorded in this bundle._\n")))))

(defn write-review!
  "Write reviewer Markdown files for an evidence bundle.

   Returns a map of output file names to paths."
  [bundle output-dir]
  (let [out (io/file output-dir)
        write! (fn [filename content]
                 (let [file (io/file out filename)]
                   (.mkdirs (.getParentFile file))
                   (spit file content)
                   (.getPath file)))]
    {:summary (write! "BENCHMARK_SUMMARY.md" (render-summary bundle))
     :scenarios (write! "scenario-results.md" (render-scenario-results bundle))
     :claims (write! "claim-results.md" (render-claim-results bundle))
     :reversals (write! "reversal-review.md" (reversal-audit/render-markdown bundle))}))

(defn review-bundle!
  "Read an EDN evidence bundle and write its reviewer Markdown package."
  [bundle-path output-dir]
  (let [bundle (edn/read-string (slurp bundle-path))]
    (write-review! bundle output-dir)))

(defn -main
  [& args]
  (let [[bundle-path output-dir & extra] args]
    (cond
      (or (nil? bundle-path) (seq extra))
      (do (binding [*out* *err*]
            (println "Usage: bb benchmark:review <evidence-bundle.edn> [output-dir]"))
          (System/exit 2))

      :else
      (let [bundle-file (io/file bundle-path)
            parent-dir (or (.getParent bundle-file) ".")
            output-dir (or output-dir (str (io/file parent-dir "review")))
            paths (review-bundle! bundle-path output-dir)]
        (println "Wrote benchmark review package:")
        (doseq [[kind path] paths]
          (println " " (name kind) "→" path))))))
