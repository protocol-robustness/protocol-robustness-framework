^{:nextjournal.clerk/dark-mode true}
(ns notebooks.evidence-graph-viewer
  "Interactive evidence graph viewer for Clerk.
   Loads graph evidence artifacts from the focused run and renders
   an interactive D3 force-directed graph with node inspection."
  (:require [nextjournal.clerk :as clerk]
            [clojure.java.io :as io]
            [resolver-sim.graph.export :as gex]
            [resolver-sim.notebook-support.common :as common]))

;; # Evidence Graph Viewer
;; ## Interactive D3 Force-Directed Graph

;; Load graph evidence artifact from the focused run or latest results.
;; Uses the same conventions as the production workbench.

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(defonce !graph-data (atom nil))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(defonce !selected-node (atom nil))

(defn- find-graph-artifacts
  "Search for graph evidence artifacts on disk.
   Checks: focused run manifest, results/runs/*/, results/test-artifacts/"
  []
  (or (let [focus-file (io/file "results" ".notebook-focus")]
        (when (.exists focus-file)
          (let [focus (common/read-json "results/.notebook-focus")
                run-dir (:run-dir focus)
                graph-file (when run-dir (io/file run-dir "graph-evidence.json"))]
            (when (and graph-file (.exists graph-file))
              (common/read-json (.getPath graph-file))))))
      (let [f (io/file "results" "test-artifacts" "graph-evidence.json")]
        (when (.exists f) (common/read-json (.getPath f))))
      (let [runs-dir (io/file "results" "runs")]
        (when (.exists runs-dir)
          (->> (.listFiles runs-dir)
               (sort-by #(.lastModified %) >)
               (some (fn [d]
                       (let [f (io/file d "graph-evidence.json")]
                         (when (.exists f)
                           (common/read-json (.getPath f)))))))))))

(defn- try-load-graph
  []
  (or (find-graph-artifacts)
      {:artifact/type :artifact/evidence-graph
       :graph/schema "evidence-graph.v1"
       :graph/title "Demo evidence graph"
       :graph/nodes [{:node/id "sha256:task0001abcd" :node/label "Research Task"}
                     {:node/id "sha256:exec0002efgh" :node/label "Execution Evidence"}
                     {:node/id "sha256:attest003ijkl" :node/label "Attestation: :reproduced"}
                     {:node/id "sha256:mailbox004mnop" :node/label "Mailbox: :RUNNER_RESULT"}]
       :graph/edges [{:edge/from "sha256:task0001abcd" :edge/to "sha256:exec0002efgh" :edge/label "produced"}
                     {:edge/from "sha256:attest003ijkl" :edge/to "sha256:task0001abcd" :edge/label "attests"}
                     {:edge/from "sha256:mailbox004mnop" :edge/to "sha256:task0001abcd" :edge/label "messages"}]
       :graph/summary {:node-count 4 :edge-count 3 :task-status :executed}}))

;; Load on page init
^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(defonce _init-loader
  (reset! !graph-data (try-load-graph)))

^{:nextjournal.clerk/visibility {:code :hide :result :show}
  :nextjournal.clerk/width :full}

(clerk/html
(let [graph @!graph-data
       nodes (or (:graph/nodes graph) (:nodes graph) [])
       node-count (count nodes)
       edge-count (get-in graph [:graph/summary :edge-count]
                          (count (or (:graph/edges graph) (:edges graph) [])))
       root-hash (get-in graph [:graph/summary :root-hash]
                          (:artifact/hash graph "unknown"))]
   [:div.evidence-graph-container
    [:style "
.evidence-graph-container {
  font-family: 'JetBrains Mono', 'Inter', sans-serif;
  background: #020617;
  color: #E2E8F0;
  padding: 24px;
  max-width: none !important;
  width: 100% !important;
}
.graph-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
  padding-bottom: 16px;
  border-bottom: 1px solid #1E293B;
}
.graph-header h1 {
  font-size: 1.4rem;
  font-weight: 700;
  color: #7ADDDC;
  margin: 0;
}
.graph-metrics {
  display: flex;
  gap: 24px;
}
.metric {
  text-align: right;
}
.metric-label {
  font-size: 0.7rem;
  text-transform: uppercase;
  letter-spacing: 0.1em;
  color: #64748B;
}
.metric-value {
  font-size: 1rem;
  font-weight: 600;
  color: #E2E8F0;
}
.graph-layout {
  display: grid;
  grid-template-columns: 1fr 320px;
  gap: 20px;
  min-height: 600px;
}
.graph-canvas {
  background: #0F172A;
  border: 1px solid #1E293B;
  border-radius: 6px;
  min-height: 600px;
  position: relative;
  overflow: hidden;
}
.graph-sidebar {
  background: #0F172A;
  border: 1px solid #1E293B;
  border-radius: 6px;
  padding: 16px;
  font-size: 12px;
}
.sidebar-section {
  margin-bottom: 16px;
}
.sidebar-title {
  font-size: 0.7rem;
  text-transform: uppercase;
  letter-spacing: 0.1em;
  color: #7ADDDC;
  margin-bottom: 8px;
}
.sidebar-key {
  color: #64748B;
}
.sidebar-value {
  color: #E2E8F0;
  word-break: break-all;
}
.export-bar {
  display: flex;
  gap: 8px;
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px solid #1E293B;
}
.export-btn {
  background: #1E293B;
  border: 1px solid #334155;
  color: #CBD5E1;
  padding: 6px 14px;
  border-radius: 4px;
  font-size: 11px;
  cursor: pointer;
  font-family: inherit;
}
.export-btn:hover {
  background: #334155;
  border-color: #475569;
}
.graph-legend {
  display: flex;
  gap: 16px;
  margin-top: 12px;
  padding: 8px 12px;
  background: #0F172A;
  border: 1px solid #1E293B;
  border-radius: 4px;
}
.legend-item {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 10px;
  color: #94A3B8;
}
.legend-swatch {
  width: 10px;
  height: 10px;
  border-radius: 2px;
}
"
    "
/* Force layout fixes */
.clerk-view, .viewer-notebook, .prose, .max-w-prose, .max-w-5xl, .mx-auto {
  max-width: none !important;
  width: 100% !important;
  margin-left: 0 !important;
  margin-right: 0 !important;
}
"]
    ;; Header
    [:div.graph-header
     [:h1 (or (:graph/title graph) "Evidence Graph")]
     [:div.graph-metrics
      [:div.metric
       [:div.metric-label "Nodes"]
       [:div.metric-value (str node-count)]]
      [:div.metric
       [:div.metric-label "Edges"]
       [:div.metric-value (str edge-count)]]
      [:div.metric
       [:div.metric-label "Root Hash"]
       [:div.metric-value {:style {:fontSize "11px" :color "#7ADDDC"}}
        (gex/truncate-label root-hash 16)]]]]
    ;; Main layout
    [:div.graph-layout
     ;; SVG rendering (Clerk-safe static display)
     [:div.graph-canvas
      [:div {:dangerouslySetInnerHTML {:__html (gex/graph->svg-html graph)}}]]
     ;; Sidebar
     [:div.graph-sidebar
      [:div.sidebar-section
       [:div.sidebar-title "Evidence Artifact"]
       [:div {:style {:marginBottom "6px"}}
        [:span.sidebar-key "Schema: "]
        [:span.sidebar-value (:graph/schema graph "unknown")]]
       [:div {:style {:marginBottom "6px"}}
        [:span.sidebar-key "Type: "]
        [:span.sidebar-value (name (:artifact/type graph ""))]]
       [:div
        [:span.sidebar-key "Hash: "]
        [:span.sidebar-value {:style {:fontSize "10px"}}
         (:artifact/hash graph "")]]]
      [:div.sidebar-section
       [:div.sidebar-title "Summary"]
       [:div {:style {:marginBottom "6px"}}
        [:span.sidebar-key "Status: "]
        [:span.sidebar-value (name (get-in graph [:graph/summary :task-status] "unknown"))]]
       [:div
        [:span.sidebar-key "Generated: "]
        [:span.sidebar-value (gex/truncate-label (:artifact/generated-at graph "") 28)]]]
      ;; Legend
      [:div.sidebar-section
       [:div.sidebar-title "Layer Legend"]
       (for [[layer name] (sort (vec gex/layer-names))
             :let [color (gex/layer-color layer)]]
         [:div {:key (str "legend-" layer)
                :style {:display "flex" :alignItems "center" :gap "8px" :marginBottom "4px"}}
          [:div {:style {:width "10px" :height "10px" :borderRadius "2px"
                         :background color :opacity "0.3"
                         :border (str "1px solid " color)}}]
          [:span {:style {:color "#CBD5E1" :fontSize "11px"}} name]])]
      ;; Export buttons
      [:div.sidebar-section
       [:div.sidebar-title "Export"]
       [:div.export-bar
        [:a.export-btn {:href "data:text/plain;charset=utf-8,"
                        :download "evidence-graph.svg"
                        :on-click "alert('SVG download: copy from the rendered SVG above, or use the evidence pack CLI.')"}
         "Download SVG"]
        [:a.export-btn {:href "data:text/plain;charset=utf-8,"
                        :download "graph-evidence.json"
                        :on-click "alert('Evidence artifact JSON: use `bb evidence:graph:export` from the CLI.')"}
         "Download JSON"]]]]]
    ;; Node list (expandable)
    [:details {:style {:marginTop "16px" :background "#0F172A" :border "1px solid #1E293B"
                       :borderRadius "6px" :padding "12px"}}
     [:summary {:style {:cursor "pointer" :color "#7ADDDC" :fontSize "12px"
                        :fontWeight "600" :marginBottom "8px"}}
      (str "All Nodes (" (count nodes) ")")]
     (into [:div {:style {:maxHeight "400px" :overflowY "auto"}}]
           (for [node nodes]
             [:div {:key (:node/id node)
                    :style {:padding "6px 8px" :borderBottom "1px solid #1E293B"
                            :fontSize "11px" :display "flex" :gap "12px" :alignItems "center"}}
              [:code {:style {:color "#7ADDDC" :fontSize "10px" :minWidth "80px"}}
               (gex/shorten-id (:node/id node))]
              [:span {:style {:color "#CBD5E1"}} (:node/label node)]]))]]))

;; ## Static SVG Preview
;; For inclusion in evidence packs and reports, use the static SVG renderer
;; which produces a standalone SVG file with deterministic layout.

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(clerk/md "## CLI Usage

```bash
# Export graph evidence from a task
bb community:graph:export --task <task-ref> > graph.graphml
```

```bash
# Export all formats (SVG + JSON + evidence artifact)
bb evidence:graph:export <run-id>
```

## Evidence Pack Integration

Graph artifacts are automatically included in evidence packs via `bb evidence:pack`.
The evidence pack will contain:

```text
evidence-pack-<run-id>/
  evidence/
    graph-evidence.json    ← evidence artifact
  visualizations/
    evidence-graph.svg     ← static SVG rendering
    evidence-graph.json    ← D3 force-directed data
```")
