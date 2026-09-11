(ns resolver-sim.notebook-support.manifest.dag
  "Builds DAG data structures for manifest dependency graph rendering.

  Returns pure data ({:nodes [...] :edges [...]}) — SVG rendering is
  the notebook's responsibility via hiccup."
  (:require [clojure.string :as str]
            [resolver-sim.notebook-support.manifest.hash :as mhash]))

;; ── node definitions ──────────────────────────────────────────────────────────

(def ^:private node-order
  [:benchmark :scenario-suite :simulation-run :event-traces
   :findings :evidence-artifacts :published-bundle])

(def ^:private base-nodes
  {:benchmark          {:label "Benchmark"         :tier 0 :color "#004D59"}
   :scenario-suite     {:label "Scenario Suite"    :tier 1 :color "#0e4f6b"}
   :simulation-run     {:label "Simulation Run"    :tier 2 :color "#155e75"}
   :event-traces       {:label "Event Traces"      :tier 3 :color "#1a6b80"}
   :findings           {:label "Findings / Issues" :tier 4 :color "#0369a1"}
   :evidence-artifacts {:label "Evidence Artifacts" :tier 5 :color "#1d4ed8"}
   :published-bundle   {:label "Published Bundle"  :tier 6 :color "#4338ca"}})

(def ^:private edge-pairs
  [[:benchmark          :scenario-suite]
   [:scenario-suite     :simulation-run]
   [:simulation-run     :event-traces]
   [:event-traces       :findings]
   [:findings           :evidence-artifacts]
   [:evidence-artifacts :published-bundle]])

;; ── annotation from live manifest ────────────────────────────────────────────

(defn- annotate-nodes [manifest registry]
  (let [git     (get-in manifest [:framework :git_commit])
        git-s   (when git (subs git 0 (min 8 (count git))))
        run-id  (get manifest :run_id "—")
        suite   (get-in manifest [:suite :id] "—")
        sc      (get-in manifest [:suite :scenario] (get-in manifest [:suite :selector] "—"))
        sc-s    (when sc (last (str/split sc #"/")))
        ch      (when manifest (subs (mhash/canonical-hash manifest) 0 12))
        art-ct  (count (get registry :artifacts []))]
    {:benchmark          {:note (str "commit: " (or git-s "unknown"))}
     :scenario-suite     {:note (str "suite: " suite)}
     :simulation-run     {:note (str "run: " run-id)}
     :event-traces       {:note (str "scenario: " (or sc-s "—"))}
     :findings           {:note (str "artifacts: " art-ct)}
     :evidence-artifacts {:note (str "hash: " (or ch "—"))}
     :published-bundle   {:note "pending signature"}}))

;; ── public API ────────────────────────────────────────────────────────────────

(defn build
  "Build the manifest DAG for a loaded run.
  run-data is {:manifest ... :registry ...}.
  Returns {:nodes [...] :edges [...]}."
  [{:keys [manifest registry] :as _run-data}]
  (let [annotations (annotate-nodes manifest registry)
        nodes       (mapv (fn [id]
                            (merge {:id id}
                                   (get base-nodes id)
                                   (get annotations id)))
                          node-order)
        edges       (mapv (fn [[from to]] {:from from :to to}) edge-pairs)]
    {:nodes nodes :edges edges}))

(defn build-latest
  "Build the manifest DAG using results/test-artifacts/ content.
  Convenience wrapper — no I/O performed here; caller supplies loaded data."
  [latest-run-data]
  (build latest-run-data))
