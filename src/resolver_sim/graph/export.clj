(ns resolver-sim.graph.export
  "Evidence graph export: wraps graph projections in evidence artifacts and
   produces SVG static renderings and D3-compatible JSON for interactive
   visualization in Clerk notebooks and evidence packs.

   Usage:
     (require '[resolver-sim.graph.export :as gex])

     ;; Build evidence artifact from graph projection
     (gex/build-graph-evidence-artifact graph-projection {:task/ref \"task-1\"})

     ;; Render as SVG string
     (gex/graph->svg graph-artifact)

     ;; D3 data for interactive force-directed graph
     (gex/graph->d3-data graph-artifact)

     ;; Write all formats to disk
     (gex/write-graph-artifacts! graph-projection {:task/ref \"task-1\"} \"output-dir/\")"
  (:require [clojure.java.io :as io]
            [clojure.string :as cstr]
            [clojure.edn :as edn]
            [clojure.data.json :as json]
            [resolver-sim.graph.layout :as layout]
            [resolver-sim.hash.canonical :as hc]))

(def ^:const schema-version "evidence-graph.v1")

(def ^:const graph-artifact-type :artifact/evidence-graph)

(declare graph->svg-body-impl)

;; ── Layer assignments (shared with community.graph via graph.layout) ─────────

(defn node-layer
  "Assign display layer based on node label prefix.
   Delegates to the shared resolver-sim.graph.layout module."
  [label]
  (layout/node-layer label))

(def layer-colors
  "Node fill colors per layer index (shared layout module)."
  layout/layer-colors)

(def layer-names
  "Display names per layer index (shared layout module)."
  layout/layer-names)

(defn layer-color
  "Resolve a layer index to its fill color (shared layout module)."
  [layer]
  (layout/layer-color layer))

;; ── Evidence artifact builder ────────────────────────────────────────────────

(defn- compute-graph-root-hash
  "Deterministic SHA-256 of the graph's nodes and edges.
   Uses canonical encoding for cross-platform stability."
  [nodes edges]
  (hc/domain-hash :evidence-graph-v1
                  {:nodes (vec (sort-by :node/id (map #(select-keys % [:node/id :node/label]) nodes)))
                   :edges (vec (sort-by (juxt :edge/from :edge/to) edges))}))

(defn build-graph-evidence-artifact
  "Wrap a graph projection (from community.graph/build-task-graph-projection)
   in an evidence artifact envelope.

   Accepts:
     graph-projection — map with :nodes, :edges, :task, :summary
     metadata         — optional map with :task/ref, :title

   Returns evidence artifact map:
     {:artifact/type   :artifact/evidence-graph
      :graph/schema    \"evidence-graph.v1\"
      :graph/title     ...
      :graph/nodes     [...]
      :graph/edges     [...]
      :graph/summary   {...}
      :artifact/hash   sha256 hex}"
  [graph-projection & [metadata]]
  (let [metadata (or metadata {})
        nodes (:nodes graph-projection [])
        edges (:edges graph-projection [])
        summary (:summary graph-projection {})
        root-hash (compute-graph-root-hash nodes edges)]
    {:artifact/type graph-artifact-type
     :graph/schema schema-version
     :graph/title (or (:title metadata)
                      (str "Evidence graph for " (:task/ref metadata "")))
     :graph/task-ref (:task/ref metadata)
     :graph/nodes nodes
     :graph/edges edges
     :graph/summary (assoc summary :root-hash root-hash)
     :artifact/hash root-hash
     :artifact/generated-at (or (:generated-at metadata) (str (java.time.Instant/now)))}))

;; ── SVG rendering ────────────────────────────────────────────────────────────
;; Layout/layer logic is shared via resolver-sim.graph.layout.

(defn- esc
  "Escape XML special characters for SVG attribute safety."
  [s]
  (-> s str (.replace "&" "&amp;") (.replace "\"" "&quot;") (.replace "<" "&lt;") (.replace ">" "&gt;")))

(defn truncate-label
  "Truncate long labels for display."
  [label max-len]
  (if (<= (count label) max-len)
    label
    (str (subs label 0 max-len) "…")))

(defn shorten-id
  "Shorten a content hash or long ID for display."
  [id-str]
  (let [s (str id-str)]
    (subs s 0 (min 12 (count s)))))

(defn graph->svg
  "Render a graph evidence artifact (or graph projection with :nodes, :edges)
   as a static SVG string.

   The SVG uses layered layout, color-coded nodes, directed arrow edges,
   and a legend. Canvas dimensions scale dynamically with node count.
   Returns an SVG string suitable for:
   - Writing to .svg files for publication
   - Embedding via clerk/html (use graph->svg-html for embedding; this
     variant includes the XML declaration for standalone files)"
  [graph-artifact]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
       (graph->svg-body-impl graph-artifact)))

(defn graph->svg-html
  "Render a graph as an SVG string without the XML declaration,
   suitable for embedding in HTML via innerHTML or Hiccup.
   Same structure as graph->svg but omits the <?xml?> processing
   instruction which HTML parsers reject."
  [graph-artifact]
  (graph->svg-body-impl graph-artifact))

(defn- graph->svg-body-impl
  "Shared SVG body used by both graph->svg and graph->svg-html."
  [graph-artifact]
  (let [nodes (or (:graph/nodes graph-artifact)
                  (:nodes graph-artifact) [])
        edges (or (:graph/edges graph-artifact)
                  (:edges graph-artifact) [])
        coords (layout/layout-coordinates nodes)
        dims (layout/svg-dimensions coords)
        svg-width (:width dims)
        svg-height (:height dims)
        node-count (count nodes)
        edge-count (count edges)
        _arrow-id "arrow-evidence"
        _arrow-id-dash "arrow-evidence-dash"]
    (str
     "<svg xmlns=\"http://www.w3.org/2000/svg\""
     " width=\"" svg-width "\" height=\"" svg-height "\""
     " viewBox=\"0 0 " svg-width " " svg-height "\""
     " style=\"background:#0A0F1E;font-family:system-ui,-apple-system,sans-serif\">\n"
     ;; Arrow markers
     "<defs>\n"
     "  <marker id=\"" _arrow-id "\" markerWidth=\"8\" markerHeight=\"6\""
     " refX=\"8\" refY=\"3\" orient=\"auto\">\n"
     "    <polygon points=\"0 0, 8 3, 0 6\" fill=\"#475569\"/>\n"
     "  </marker>\n"
     "  <marker id=\"" _arrow-id-dash "\" markerWidth=\"8\" markerHeight=\"6\""
     " refX=\"8\" refY=\"3\" orient=\"auto\">\n"
     "    <polygon points=\"0 0, 8 3, 0 6\" fill=\"#374151\"/>\n"
     "  </marker>\n"
     "</defs>\n"
     ;; Background grid
     "<rect width=\"" svg-width "\" height=\"" svg-height
     "\" fill=\"#0A0F1E\" rx=\"4\"/>\n"
     ;; Title
     "<text x=\"20\" y=\"28\" fill=\"#E2E8F0\" font-size=\"14\" font-weight=\"700\">"
     (esc (or (:graph/title graph-artifact) "Evidence Graph")) "</text>\n"
     "<text x=\"20\" y=\"44\" fill=\"#64748B\" font-size=\"11\">"
     node-count " nodes, " edge-count " edges"
     " \u00B7 schema: " schema-version "</text>\n"
     ;; Edges
     (apply str
            (for [edge edges
                  :let [from (:edge/from edge)
                        to (:edge/to edge)
                        from-coord (get coords from)
                        to-coord (get coords to)]
                  :when (and from-coord to-coord)
                  :let [x1 (+ (:x from-coord) (/ (:w from-coord) 2))
                        y1 (+ (:y from-coord) (:h from-coord))
                        x2 (+ (:x to-coord) (/ (:w to-coord) 2))
                        y2 (:y to-coord)
                        mx (/ (+ x1 x2) 2)
                        my (- (/ (+ y1 y2) 2) 8)
                        label (esc (or (:edge/label edge) ""))]]
              (str "  <g>\n"
                   "    <path d=\"M " x1 " " y1 " L " mx " " my " L " x2 " " y2 "\""
                   " fill=\"none\" stroke=\"#475569\" stroke-width=\"1.2\""
                   " marker-end=\"url(#" _arrow-id ")\"/>\n"
                   (when (seq label)
                     (str "    <text x=\"" mx "\" y=\"" (- my 6) "\""
                          " fill=\"#64748B\" font-size=\"9\" text-anchor=\"middle\""
                          " font-family=\"JetBrains Mono,monospace\">" label "</text>\n"))
                   "  </g>\n")))
     ;; Nodes
     (apply str
            (for [node nodes
                  :let [nid (esc (shorten-id (:node/id node)))
                        label (esc (truncate-label (:node/label node) 28))
                        layer (node-layer (:node/label node))
                        color (layer-color layer)
                        c (get coords (:node/id node))]
                  :when c]
              (str "  <g>\n"
                   "    <rect x=\"" (:x c) "\" y=\"" (:y c) "\""
                   " width=\"" (:w c) "\" height=\"" (:h c) "\""
                   " rx=\"6\" ry=\"6\""
                   " fill=\"" color "\" fill-opacity=\"0.12\""
                   " stroke=\"" color "\" stroke-width=\"1.2\"/>\n"
                   "    <text x=\"" (+ (:x c) 12) "\" y=\"" (+ (:y c) 18) "\""
                   " fill=\"" color "\" font-size=\"11\" font-weight=\"600\""
                   " font-family=\"system-ui,-apple-system,sans-serif\">"
                   label "</text>\n"
                   "    <text x=\"" (+ (:x c) 12) "\" y=\"" (+ (:y c) 33) "\""
                   " fill=\"#64748B\" font-size=\"9\""
                   " font-family=\"JetBrains Mono,monospace\">"
                   nid "</text>\n"
                   "  </g>\n")))
     ;; Legend
     "<g transform=\"translate(20, " (- svg-height 80) ")\">\n"
     "<rect x=\"0\" y=\"0\" width=\"" (- svg-width 40) "\" height=\"60\""
     " fill=\"#0F172A\" stroke=\"#1E293B\" stroke-width=\"1\" rx=\"4\"/>\n"
     "<text x=\"12\" y=\"18\" fill=\"#94A3B8\" font-size=\"10\" font-weight=\"600\">"
     "Layer legend</text>\n"
     (apply str
            (for [[layer name] (sort layer-names)]
              (str "<rect x=\"" (+ 12 (* (dec layer) 200)) "\" y=\"28\""
                   " width=\"10\" height=\"10\" rx=\"2\""
                   " fill=\"" (layer-color layer) "\" fill-opacity=\"0.3\""
                   " stroke=\"" (layer-color layer) "\" stroke-width=\"1\"/>\n"
                   "<text x=\"" (+ 28 (* (dec layer) 200)) "\" y=\"37\""
                   " fill=\"#CBD5E1\" font-size=\"10\">" name "</text>\n")))
     "</g>\n"
     "</svg>\n")))

;; ── D3 force-directed data format ────────────────────────────────────────────

(defn graph->d3-data
  "Convert a graph evidence artifact to D3 force-directed graph data.
   Returns {:nodes [...], :links [...]}.

   Each node:
     :id        — unique identifier
     :label     — display label
     :group     — layer number (for color mapping)
     :fx, :fy   — fixed position from deterministic layout (optional in D3)
     :shortId   — shortened hash for hover display

   Each link:
     :source   — source node id
     :target   — target node id
     :label    — relationship label

   This format is compatible with D3 force simulation via
   nextjournal.clerk.render/with-d3-require."
  [graph-artifact]
  (let [nodes (or (:graph/nodes graph-artifact)
                  (:nodes graph-artifact) [])
        edges (or (:graph/edges graph-artifact)
                  (:edges graph-artifact) [])
        coords (layout/layout-coordinates nodes)]
    {:nodes (mapv (fn [n]
                    (let [layer (node-layer (:node/label n))
                          c (get coords (:node/id n))]
                      {:id (str (:node/id n))
                       :label (:node/label n)
                       :group layer
                       :shortId (shorten-id (:node/id n))
                       :fx (:x c)
                       :fy (:y c)}))
                  nodes)
     :links (mapv (fn [e]
                    {:source (str (:edge/from e))
                     :target (str (:edge/to e))
                     :label (or (:edge/label e) "")})
                  edges)}))

;; ── Self-contained D3 HTML viewer ───────────────────────────────────────────

(defn graph->d3-html
  "Generate a self-contained HTML page with D3 force-directed graph.
   Includes pan, zoom, hover labels, and node dragging.
   No external dependencies — loads D3 from CDN.
   Suitable for any browser, no Clerk required."
  [graph-artifact]
  (let [d3-data (graph->d3-data graph-artifact)
        json-str (json/write-str d3-data)
        group-colors ["#1A73E8" "#34A853" "#FBBC04" "#EA4335" "#9CA3AF"]
        colors-js (str "[" (cstr/join "," (map (fn [c] (str "\"" c "\"")) group-colors)) "]")]
    (str
     "<!DOCTYPE html>\n"
     "<html lang=\"en\">\n"
     "<head>\n"
     "<meta charset=\"UTF-8\">\n"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
     "<title>Evidence DAG — Force-Directed Graph</title>\n"
     "<script src=\"https://d3js.org/d3.v7.min.js\"></script>\n"
     "<style>\n"
     "body { margin: 0; overflow: hidden; font-family: system-ui, -apple-system, sans-serif; background: #0A0F1E; color: #E2E8F0; }\n"
     "#container { width: 100vw; height: 100vh; position: relative; }\n"
     "svg { width: 100%; height: 100%; display: block; }\n"
     ".tooltip { position: absolute; background: #0F172A; border: 1px solid #1E293B; border-radius: 6px; padding: 8px 12px; font-size: 12px; pointer-events: none; opacity: 0; transition: opacity 0.15s; max-width: 360px; z-index: 100; }\n"
     ".tooltip.show { opacity: 1; }\n"
     ".tooltip .tt-label { color: #E2E8F0; font-weight: 600; }\n"
     ".tooltip .tt-id { color: #64748B; font-size: 10px; font-family: 'JetBrains Mono', monospace; margin-top: 2px; }\n"
     ".tooltip .tt-detail { color: #94A3B8; font-size: 10px; margin-top: 2px; }\n"
     "#stats { position: absolute; top: 16px; left: 16px; font-size: 13px; color: #7ADDDC; font-weight: 600; background: #0F172A; padding: 8px 14px; border-radius: 6px; border: 1px solid #1E293B; pointer-events: none; z-index: 50; }\n"
     "#stats span { color: #64748B; font-weight: 400; font-size: 11px; }\n"
     ".legend { position: absolute; bottom: 20px; left: 20px; display: flex; gap: 14px; background: #0F172A; padding: 8px 14px; border-radius: 6px; border: 1px solid #1E293B; font-size: 11px; z-index: 50; }\n"
     ".legend-item { display: flex; align-items: center; gap: 6px; color: #94A3B8; }\n"
     ".legend-swatch { width: 10px; height: 10px; border-radius: 2px; }\n"
     "text { font-family: system-ui, -apple-system, sans-serif; }\n"
     ".node-label { font-size: 8px; fill: #CBD5E1; text-anchor: middle; pointer-events: none; }\n"
     ".edge-label { font-size: 7px; fill: #64748B; text-anchor: middle; }\n"
     "</style>\n"
     "</head>\n"
     "<body>\n"
     "<div id=\"container\">\n"
     "<div id=\"stats\">" (count (:nodes d3-data)) " nodes <span>|</span> " (count (:links d3-data)) " edges</div>\n"
     "<div class=\"legend\">\n"
     (apply str
            (for [[layer name] (sort layer-names)
                  :let [color (get group-colors layer "#9CA3AF")]]
              (str "<div class=\"legend-item\"><div class=\"legend-swatch\" style=\"background:" color ";opacity:0.3;border:1px solid " color "\"></div>" name "</div>\n")))
     "</div>\n"
     "<div class=\"tooltip\" id=\"tooltip\"></div>\n"
     "</div>\n"
     "<script>\n"
     "var data = " json-str ";\n"
     "var colors = " colors-js ";\n"
     "\n"
     "var width = window.innerWidth, height = window.innerHeight;\n"
     "\n"
     "var svg = d3.select('#container')\n"
     "  .append('svg')\n"
     "  .attr('width', width)\n"
     "  .attr('height', height);\n"
     "\n"
     "var g = svg.append('g');\n"
     "\n"
     "// Zoom behavior\n"
     "var zoom = d3.zoom()\n"
     "  .scaleExtent([0.1, 8])\n"
     "  .on('zoom', function(event) { g.attr('transform', event.transform); });\n"
     "svg.call(zoom);\n"
     "\n"
     "// Arrow marker\n"
     "svg.append('defs').append('marker')\n"
     "  .attr('id', 'arrow')\n"
     "  .attr('viewBox', '0 -5 10 10')\n"
     "  .attr('refX', 20)\n"
     "  .attr('refY', 0)\n"
     "  .attr('markerWidth', 6)\n"
     "  .attr('markerHeight', 6)\n"
     "  .attr('orient', 'auto')\n"
     "  .append('path')\n"
     "  .attr('d', 'M0,-5L10,0L0,5')\n"
     "  .attr('fill', '#475569');\n"
     "\n"
     "// Links\n"
     "var link = g.append('g')\n"
     "  .selectAll('line')\n"
     "  .data(data.links)\n"
     "  .join('line')\n"
     "  .attr('stroke', '#475569')\n"
     "  .attr('stroke-width', 1.2)\n"
     "  .attr('stroke-opacity', 0.6)\n"
     "  .attr('marker-end', 'url(#arrow)');\n"
     "\n"
     "// Nodes\n"
     "var nodeGroup = g.append('g').selectAll('g')\n"
     "  .data(data.nodes)\n"
     "  .join('g')\n"
     "  .call(d3.drag()\n"
     "    .on('start', function(event, d) { if (!event.active) sim.alphaTarget(0.3).restart(); d.fx = d.x; d.fy = d.y; })\n"
     "    .on('drag', function(event, d) { d.fx = event.x; d.fy = event.y; })\n"
     "    .on('end', function(event, d) { if (!event.active) sim.alphaTarget(0); d.fx = event.x; d.fy = event.y; }));\n"
     "\n"
     "nodeGroup.append('rect')\n"
     "  .attr('width', function(d) { return Math.max(80, d.label.length * 6.5); })\n"
     "  .attr('height', 26)\n"
     "  .attr('x', function(d) { return -Math.max(80, d.label.length * 6.5) / 2; })\n"
     "  .attr('y', -13)\n"
     "  .attr('rx', 4)\n"
     "  .attr('ry', 4)\n"
     "  .attr('fill', function(d) { return colors[d.group] || colors[4]; })\n"
     "  .attr('fill-opacity', 0.15)\n"
     "  .attr('stroke', function(d) { return colors[d.group] || colors[4]; })\n"
     "  .attr('stroke-width', 1.2);\n"
     "\n"
     "nodeGroup.append('text')\n"
     "  .text(function(d) { return d.label; })\n"
     "  .attr('text-anchor', 'middle')\n"
     "  .attr('dy', 4)\n"
     "  .attr('fill', function(d) { return colors[d.group] || colors[4]; })\n"
     "  .attr('font-size', 10)\n"
     "  .attr('font-weight', 600)\n"
     "  .attr('font-family', 'system-ui, -apple-system, sans-serif');\n"
     "\n"
     "// Tooltip\n"
     "var tooltip = d3.select('#tooltip');\n"
     "\n"
     "nodeGroup\n"
     "  .on('mouseenter', function(event, d) {\n"
     "    tooltip\n"
     "      .classed('show', true)\n"
     "      .html('<div class=\"tt-label\">' + d.label + '</div><div class=\"tt-id\">' + d.shortId + '</div><div class=\"tt-detail\">' + d.id + '</div>')\n"
     "      .style('left', (event.offsetX + 12) + 'px')\n"
     "      .style('top', (event.offsetY - 10) + 'px');\n"
     "  })\n"
     "  .on('mousemove', function(event, d) {\n"
     "    tooltip\n"
     "      .style('left', (event.offsetX + 12) + 'px')\n"
     "      .style('top', (event.offsetY - 10) + 'px');\n"
     "  })\n"
     "  .on('mouseleave', function() { tooltip.classed('show', false); });\n"
     "\n"
     "// Force simulation\n"
     "var sim = d3.forceSimulation(data.nodes)\n"
     "  .force('link', d3.forceLink(data.links).id(function(d) { return d.id; }).distance(120))\n"
     "  .force('charge', d3.forceManyBody().strength(-300))\n"
     "  .force('center', d3.forceCenter(width / 2, height / 2))\n"
     "  .force('collision', d3.forceCollide().radius(40))\n"
     "  .on('tick', function() {\n"
     "    link\n"
     "      .attr('x1', function(d) { return d.source.x; })\n"
     "      .attr('y1', function(d) { return d.source.y; })\n"
     "      .attr('x2', function(d) { return d.target.x; })\n"
     "      .attr('y2', function(d) { return d.target.y; });\n"
     "    nodeGroup.attr('transform', function(d) { return 'translate(' + d.x + ',' + d.y + ')'; });\n"
     "  });\n"
     "\n"
     "// Handle window resize\n"
     "window.addEventListener('resize', function() {\n"
     "  width = window.innerWidth;\n"
     "  height = window.innerHeight;\n"
     "  svg.attr('width', width).attr('height', height);\n"
     "  sim.force('center', d3.forceCenter(width / 2, height / 2));\n"
     "  sim.alpha(0.3).restart();\n"
     "});\n"
     "</script>\n"
     "</body>\n"
     "</html>\n")))

;; ── React Flow data format ──────────────────────────────────────────────────

(defn graph->react-flow-data
  "Convert a graph evidence artifact to React Flow format.
   Returns {:nodes [...], :edges [...]}.

   Each node:
     :id        — unique identifier
     :type      — \"evidenceNode\" (custom component type)
     :position  — {:x N :y N} from deterministic layout
     :data      — map with :label, :shortId, :status, :layer,
                  :executionKind, :executionId, :runner,
                  :timestamp, :parentHashes, :evidence, :extensions

   Each edge:
     :id        — unique edge id
     :source    — source node id
     :target    — target node id
     :label     — relationship label
     :type      — \"smoothstep\" for curved edges
     :animated  — false (can be toggled on selection)

   This format feeds the React Flow self-contained viewer
   (graph->react-flow-html). Any future DAG bridge that
   produces {:nodes [{:node/id :node/label :node/data ...}]
             :edges [{:edge/from :edge/to :edge/label ...}]}
   works without changes to this converter."
  [graph-artifact]
  (let [nodes (or (:graph/nodes graph-artifact)
                  (:nodes graph-artifact) [])
        edges (or (:graph/edges graph-artifact)
                  (:edges graph-artifact) [])
        coords (layout/layout-coordinates nodes)]
    {:nodes (mapv (fn [n]
                    (let [node-id (str (:node/id n))
                          layer (node-layer (:node/label n))
                          c (get coords node-id)
                          node-data (merge
                                     {:label (:node/label n)
                                      :shortId (shorten-id node-id)
                                      :status (get-in n [:node/data :status]
                                                      (get-in n [:node/status] "unknown"))
                                      :layer layer
                                      :executionKind (name (get-in n [:node/data :execution-id]
                                                                   (get-in n [:node/kind] "unknown")))
                                      :runner (name (get-in n [:node/data :runner] "unknown"))
                                      :timestamp (get-in n [:node/data :timestamp] "")
                                      :parentHashes (vec (mapv str (get-in n [:node/data :parent-hashes]
                                                                           (:parent-hashes n []))))}
                                     (dissoc (:node/data n) :status :parent-hashes))]
                      {:id node-id
                       :type "evidenceNode"
                       :position {:x (double (:x c 0))
                                  :y (double (:y c 0))}
                       :data node-data}))
                  nodes)
     :edges (mapv (fn [e]
                    (let [from (str (:edge/from e))
                          to (str (:edge/to e))]
                      {:id (str "e-" (shorten-id from) "-" (shorten-id to))
                       :source from
                       :target to
                       :label (or (:edge/label e) "")
                       :type "smoothstep"
                       :animated false}))
                  edges)}))

;; ── Self-contained React Flow HTML viewer ───────────────────────────────────

(defn graph->react-flow-html
  "Generate a self-contained HTML page with React Flow interactive graph.
   Uses React 18 + React Flow 11 via importmap (ES modules from CDN).
   No build step required — opens in any modern browser.

   Features:
   - Custom evidence node component (label, status badge, hash)
   - Click-to-inspect side panel with full node data
   - Animated edges when a connected node is selected
   - MiniMap + Controls + Background
   - Multi-layer legend
   - Dark theme matching the SVG viewer

   Any future DAG bridge producing the common intermediate format
   {:nodes [{:node/id :node/label :node/data ...}]
    :edges [{:edge/from :edge/to :edge/label ...}]}
   works without changes to this viewer."
  [graph-artifact]
  (let [rf-data (graph->react-flow-data graph-artifact)
        json-str (json/write-str rf-data)]
    (str
     "<!DOCTYPE html>\n"
     "<html lang=\"en\">\n"
     "<head>\n"
     "<meta charset=\"UTF-8\">\n"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
     "<title>Evidence DAG — React Flow</title>\n"
     "<link rel=\"stylesheet\" href=\"https://unpkg.com/reactflow@11.11.4/dist/style.css\">\n"
     "<style>\n"
     "* { margin: 0; padding: 0; box-sizing: border-box; }\n"
     "html, body, #root { width: 100%; height: 100%; overflow: hidden; font-family: system-ui, -apple-system, sans-serif; background: #0A0F1E; color: #E2E8F0; }\n"
     ".app { display: flex; flex-direction: column; height: 100vh; }\n"
     ".topbar { display: flex; align-items: center; gap: 16px; padding: 8px 16px; background: #0F172A; border-bottom: 1px solid #1E293B; font-size: 13px; flex-shrink: 0; }\n"
     ".topbar h1 { font-size: 14px; font-weight: 700; color: #7ADDDC; }\n"
     ".topbar .stat { color: #64748B; }\n"
     ".topbar .stat span { color: #E2E8F0; font-weight: 600; }\n"
     ".flow-wrap { display: flex; flex: 1; overflow: hidden; }\n"
     ".flow-canvas { flex: 1; position: relative; }\n"
     ".side-panel { width: 340px; background: #0F172A; border-left: 1px solid #1E293B; overflow-y: auto; padding: 16px; flex-shrink: 0; display: none; }\n"
     ".side-panel.open { display: block; }\n"
     ".side-panel h3 { font-size: 13px; font-weight: 700; color: #7ADDDC; margin-bottom: 4px; }\n"
     ".side-panel .sub { font-size: 10px; color: #64748B; font-family: 'JetBrains Mono', monospace; margin-bottom: 16px; word-break: break-all; }\n"
     ".side-panel .field { margin-bottom: 10px; }\n"
     ".side-panel .field .key { font-size: 10px; text-transform: uppercase; letter-spacing: 0.05em; color: #64748B; margin-bottom: 2px; }\n"
     ".side-panel .field .val { font-size: 12px; color: #E2E8F0; word-break: break-all; font-family: 'JetBrains Mono', monospace; }\n"
     ".side-panel .close-btn { float: right; background: none; border: 1px solid #334155; color: #94A3B8; padding: 2px 10px; border-radius: 4px; cursor: pointer; font-size: 11px; }\n"
     ".side-panel .close-btn:hover { background: #1E293B; color: #E2E8F0; }\n"
     ".legend-bar { display: flex; align-items: center; gap: 14px; padding: 6px 16px; background: #0F172A; border-top: 1px solid #1E293B; font-size: 11px; flex-shrink: 0; flex-wrap: wrap; }\n"
     ".legend-item { display: flex; align-items: center; gap: 5px; color: #94A3B8; }\n"
     ".legend-dot { width: 8px; height: 8px; border-radius: 2px; }\n"
     ".legend-sep { width: 1px; height: 14px; background: #1E293B; margin: 0 4px; }\n"
     ".status-badge { display: inline-block; width: 16px; height: 16px; line-height: 16px; text-align: center; border-radius: 3px; font-size: 10px; font-weight: 700; margin-right: 6px; }\n"
     "/* React Flow overrides */\n"
     ".react-flow__background { background: #0A0F1E !important; }\n"
     ".react-flow__controls { background: #0F172A !important; border: 1px solid #1E293B !important; border-radius: 6px !important; overflow: hidden; }\n"
     ".react-flow__controls-button { background: #0F172A !important; border-bottom: 1px solid #1E293B !important; fill: #94A3B8 !important; }\n"
     ".react-flow__controls-button:hover { background: #1E293B !important; }\n"
     ".react-flow__minimap { border: 1px solid #1E293B !important; border-radius: 6px !important; overflow: hidden; }\n"
     ".react-flow__edge-path { stroke: #334155 !important; stroke-width: 2 !important; }\n"
     ".react-flow__edge.selected .react-flow__edge-path { stroke: #7ADDDC !important; stroke-width: 2.5 !important; }\n"
     ".react-flow__edge.animated .react-flow__edge-path { stroke-dasharray: 6 4; stroke: #7ADDDC !important; stroke-width: 2.5 !important; }\n"
     ".react-flow__edge-text { font-size: 9px !important; fill: #64748B !important; font-family: 'JetBrains Mono', monospace !important; }\n"
     ".react-flow__node { cursor: pointer !important; }\n"
     "</style>\n"
     "</head>\n"
     "<body>\n"
     "<div id=\"root\"></div>\n"
     "<script crossorigin src=\"https://unpkg.com/react@18.3.1/umd/react.production.min.js\"></script>\n"
     "<script crossorigin src=\"https://unpkg.com/react-dom@18.3.1/umd/react-dom.production.min.js\"></script>\n"
     "<script crossorigin src=\"https://unpkg.com/reactflow@11.11.4/dist/umd/index.js\"></script>\n"
     "<script>\n"
     "// React Flow UMD globals\n"
     "var RF = window.ReactFlow;\n"
     "var RFReactFlow = RF.ReactFlow, Background = RF.Background, Controls = RF.Controls, MiniMap = RF.MiniMap, useNodesState = RF.useNodesState, useEdgesState = RF.useEdgesState, MarkerType = RF.MarkerType;\n"
     "\n"
     "// Data\n"
     "var initialData = " json-str ";\n"
     "var layerColors = ['#1A73E8','#34A853','#FBBC04','#8B5CF6','#9CA3AF'];\n"
     "var statusStyles = {'pass':'#34A853','fail':'#EA4335','error':'#FBBC04'};\n"
     "\n"
     "// Custom node component\n"
     "function EvidenceNode({ data, selected }) {\n"
     "  var color = layerColors[data.layer] || layerColors[4];\n"
     "  var badgeBg = statusStyles[data.status] || '#9CA3AF';\n"
     "  var badgeChar = data.status === 'pass' ? '\\u2713' : data.status === 'fail' ? '\\u2717' : '\\u25B3';\n"
     "  var selStyle = selected ? { outline: '2px solid ' + color, outlineOffset: 2, boxShadow: '0 0 14px ' + color + '66' } : {};\n"
     "  return React.createElement('div', {\n"
     "    style: Object.assign({\n"
     "      background: color + '1A', border: '1.5px solid ' + (selected ? color : color + '88'),\n"
     "      borderRadius: 6, padding: '6px 10px', minWidth: 120,\n"
     "      fontFamily: 'system-ui,-apple-system,sans-serif', transition: 'box-shadow 0.15s, border-color 0.15s'\n"
     "    }, selStyle)\n"
     "  },\n"
     "    React.createElement('div', { style: { display: 'flex', alignItems: 'center', gap: 6, marginBottom: 2 } },\n"
     "      React.createElement('span', { style: { display: 'inline-block', width: 14, height: 14, lineHeight: '14px', textAlign: 'center', borderRadius: 3, fontSize: 10, fontWeight: 700, background: badgeBg, color: '#fff' } }, badgeChar),\n"
     "      React.createElement('span', { style: { color: color, fontSize: 12, fontWeight: 600, lineHeight: 1.2 } }, data.label)\n"
     "    ),\n"
     "    React.createElement('div', { style: { color: '#64748B', fontSize: 9, fontFamily: 'JetBrains Mono, monospace' } }, data.shortId)\n"
     "  );\n"
     "}\n"
     "\n"
     "var nodeTypes = { evidenceNode: EvidenceNode };\n"
     "\n"
     "function App() {\n"
     "  var _a = useNodesState(initialData.nodes.map(function(n) { return { id: n.id, type: n.type, position: n.position, data: n.data }; }));\n"
     "  var nodes = _a[0], setNodes = _a[1], onNodesChange = _a[2];\n"
     "  var _b = useEdgesState(initialData.edges);\n"
     "  var edges = _b[0], setEdges = _b[1], onEdgesChange = _b[2];\n"
     "  var _c = React.useState(null);\n"
     "  var selectedNode = _c[0], setSelectedNode = _c[1];\n"
     "\n"
     "  function onNodeClick(evt, node) {\n"
     "    setSelectedNode(node);\n"
     "    setEdges(function(eds) {\n"
     "      var connectedIds = new Set();\n"
     "      connectedIds.add(node.id);\n"
     "      eds.forEach(function(e) { if (e.source === node.id || e.target === node.id) { connectedIds.add(e.source); connectedIds.add(e.target); } });\n"
     "      setNodes(function(nds) { return nds.map(function(n) { return Object.assign({}, n, { selected: connectedIds.has(n.id) }); }); });\n"
     "      return eds.map(function(e) { return Object.assign({}, e, { animated: e.source === node.id || e.target === node.id }); });\n"
     "    });\n"
     "  }\n"
     "\n"
     "  function onPaneClick() {\n"
     "    setSelectedNode(null);\n"
     "    setNodes(function(nds) { return nds.map(function(n) { return Object.assign({}, n, { selected: false }); }); });\n"
     "    setEdges(function(eds) { return eds.map(function(e) { return Object.assign({}, e, { animated: false }); }); });\n"
     "  }\n"
     "\n"
     "  var detailRows = [];\n"
     "  if (selectedNode) {\n"
     "    var ignoreKeys = { label: true, shortId: true, layer: true, status: true, executionKind: true };\n"
     "    var fieldOrder = ['executionKind', 'runner', 'status', 'timestamp', 'parentHashes', 'extensions'];\n"
     "    var seen = {};\n"
     "    fieldOrder.forEach(function(k) {\n"
     "      var v = selectedNode.data[k];\n"
     "      if (v !== undefined && v !== null && v !== '') {\n"
     "        seen[k] = true;\n"
     "        var display = typeof v === 'object' ? JSON.stringify(v, null, 2) : String(v);\n"
     "        detailRows.push(React.createElement('div', { className: 'field', key: k },\n"
     "          React.createElement('div', { className: 'key' }, k.replace(/([A-Z])/g, ' $1').toLowerCase()),\n"
     "          React.createElement('div', { className: 'val' }, display)\n"
     "        ));\n"
     "      }\n"
     "    });\n"
     "    Object.keys(selectedNode.data).forEach(function(k) {\n"
     "      if (!seen[k] && !ignoreKeys[k]) {\n"
     "        var v = selectedNode.data[k];\n"
     "        if (v !== undefined && v !== null && v !== '') {\n"
     "          var display = typeof v === 'object' ? JSON.stringify(v, null, 2) : String(v);\n"
     "          detailRows.push(React.createElement('div', { className: 'field', key: k },\n"
     "            React.createElement('div', { className: 'key' }, k.replace(/([A-Z])/g, ' $1').toLowerCase()),\n"
     "            React.createElement('div', { className: 'val' }, display)\n"
     "          ));\n"
     "        }\n"
     "      }\n"
     "    });\n"
     "  }\n"
     "\n"
     "  return React.createElement('div', { className: 'app' },\n"
     "    React.createElement('div', { className: 'topbar' },\n"
     "      React.createElement('h1', null, 'Evidence DAG'),\n"
     "      React.createElement('span', { className: 'stat' }, React.createElement('span', null, initialData.nodes.length), ' nodes'),\n"
     "      React.createElement('span', { className: 'stat' }, React.createElement('span', null, initialData.edges.length), ' edges'),\n"
     "      selectedNode ? React.createElement('span', { style: { color: '#7ADDDC', fontSize: 11, fontFamily: 'JetBrains Mono,monospace' } }, selectedNode.data.shortId) : null\n"
     "    ),\n"
     "    React.createElement('div', { className: 'flow-wrap' },\n"
     "      React.createElement('div', { className: 'flow-canvas' },\n"
     "        React.createElement(RFReactFlow, {\n"
     "          nodes: nodes, edges: edges,\n"
     "          onNodesChange: onNodesChange, onEdgesChange: onEdgesChange,\n"
     "          nodeTypes: nodeTypes,\n"
     "          onNodeClick: onNodeClick,\n"
     "          onPaneClick: onPaneClick,\n"
     "          fitView: true, attributionPosition: 'bottom-left',\n"
     "          defaultEdgeOptions: { markerEnd: { type: MarkerType.ArrowClosed, color: '#475569' } },\n"
     "          style: { background: '#0A0F1E' }\n"
     "        },\n"
     "          React.createElement(Background, { color: '#1E293B', gap: 20 }),\n"
     "          React.createElement(Controls, { showInteractive: false }),\n"
     "          React.createElement(MiniMap, {\n"
     "            nodeColor: function(n) { return layerColors[n.data.layer] || layerColors[4]; },\n"
     "            maskColor: '#0A0F1E', style: { background: '#0F172A' }\n"
     "          })\n"
     "        )\n"
     "      ),\n"
     "      React.createElement('div', { className: 'side-panel' + (selectedNode ? ' open' : '') },\n"
     "        selectedNode ? React.createElement(React.Fragment, null,\n"
     "          React.createElement('button', { className: 'close-btn', onClick: onPaneClick }, 'Close'),\n"
     "          React.createElement('h3', null, selectedNode.data.label),\n"
     "          React.createElement('div', { className: 'sub' }, selectedNode.data.shortId),\n"
     "          detailRows\n"
     "        ) : null\n"
     "      )\n"
     "    ),\n"
     "    React.createElement('div', { className: 'legend-bar' },\n"
     "      (function() {\n"
     "        var layerNames = { 0: 'Task', 1: 'Exec/Attest', 2: 'Mailbox/Finding', 3: 'Other' };\n"
     "        var items = [];\n"
     "        Object.keys(layerNames).forEach(function(k) {\n"
     "          var i = parseInt(k);\n"
     "          if (initialData.nodes.some(function(n) { return n.data.layer === i; })) {\n"
     "            if (items.length) items.push(React.createElement('div', { className: 'legend-sep' }));\n"
     "            items.push(React.createElement('div', { className: 'legend-item' },\n"
     "              React.createElement('div', { className: 'legend-dot', style: { background: layerColors[i] + '44', border: '1px solid ' + layerColors[i] } }),\n"
     "              layerNames[k]\n"
     "            ));\n"
     "          }\n"
     "        });\n"
     "        items.push(React.createElement('div', { className: 'legend-sep' }));\n"
     "        items.push(React.createElement('div', { className: 'legend-item' },\n"
     "          React.createElement('span', { style: { color: '#34A853', fontSize: 12 } }, '\\u2713'), ' pass'\n"
     "        ));\n"
     "        items.push(React.createElement('div', { className: 'legend-item' },\n"
     "          React.createElement('span', { style: { color: '#EA4335', fontSize: 12 } }, '\\u2717'), ' fail'\n"
     "        ));\n"
     "        items.push(React.createElement('div', { className: 'legend-item' },\n"
     "          React.createElement('span', { style: { color: '#FBBC04', fontSize: 12 } }, '\\u25B3'), ' error'\n"
     "        ));\n"
     "        return items;\n"
     "      })()\n"
     "    )\n"
     "  );\n"
     "}\n"
     "\n"
     "ReactDOM.createRoot(document.getElementById('root')).render(React.createElement(App));\n"
     "</script>\n"
     "</body>\n"
     "</html>\n")))

(defn write-graph-artifacts!
  "Build evidence artifacts from a graph projection and write all formats.

   Writes to <out-dir>/:
     graph-evidence.json      — evidence artifact JSON
     evidence-graph.svg       — static SVG rendering
     evidence-graph.json      — D3 force-directed data JSON
     evidence-graph.html      — self-contained D3 interactive viewer
     evidence-graph-rf.html   — self-contained React Flow interactive viewer

   Returns {:svg-path ..., :d3-html-path ..., :rf-html-path ...,
            :d3-path ..., :artifact-path ..., :artifact ...}"
  ([graph-projection metadata out-dir]
   (write-graph-artifacts! graph-projection metadata out-dir nil))
  ([graph-projection metadata out-dir {:keys [pretty-print?]
                                       :or {pretty-print? true}}]
   (let [artifact (build-graph-evidence-artifact graph-projection metadata)
         svg (graph->svg artifact)
         d3-data (graph->d3-data artifact)
         d3-html (graph->d3-html artifact)
         rf-html (graph->react-flow-html artifact)
         out (io/file out-dir)
         _ (.mkdirs out)
         indent (if pretty-print? true false)
         write-json (fn [filename data]
                      (let [f (io/file out filename)]
                        (spit f (clojure.data.json/write-str data {:indent indent}))
                        (.getPath f)))]
     {:svg-path (let [f (io/file out "evidence-graph.svg")]
                  (spit f svg)
                  (.getPath f))
      :d3-html-path (let [f (io/file out "evidence-graph.html")]
                      (spit f d3-html)
                      (.getPath f))
      :rf-html-path (let [f (io/file out "evidence-graph-rf.html")]
                      (spit f rf-html)
                      (.getPath f))
      :d3-path (write-json "evidence-graph.json" d3-data)
      :artifact-path (write-json "graph-evidence.json" artifact)
      :artifact artifact})))

(defn write-graphml
  "Write a graph projection as GraphML XML for yEd.
   Delegates to community.graph/export-graphml and writes to file.
   Returns the output path."
  [graph-projection out-dir]
  (let [graphml (requiring-resolve 'resolver-sim.community.graph/export-graphml)]
    (when graphml
      (let [xml (graphml graph-projection)
            f (io/file out-dir "evidence-graph.graphml")]
        (.mkdirs (io/file out-dir))
        (spit f xml)
        (.getPath f)))))

;; ── Artifact registry validation ─────────────────────────────────────────────

(defn validate-graph-artifact
  "Validate a graph evidence artifact.
   Checks:
   - Required keys present
   - Nodes and edges are present
   - Artifact hash matches computed root hash
   Returns {:valid? bool, :errors [str], :checks [...]}"
  [artifact]
  (let [errors (atom [])
        checks (atom [])]
    ;; Schema version check
    (let [sv (:graph/schema artifact)]
      (if (= sv schema-version)
        (swap! checks conj {:check :schema-version :pass? true})
        (do (swap! errors conj (str "Schema version mismatch: expected " schema-version " got " sv))
            (swap! checks conj {:check :schema-version :pass? false}))))
    ;; Artifact type check
    (let [at (:artifact/type artifact)]
      (if (= at graph-artifact-type)
        (swap! checks conj {:check :artifact-type :pass? true})
        (do (swap! errors conj (str "Artifact type mismatch: expected " graph-artifact-type " got " at))
            (swap! checks conj {:check :artifact-type :pass? false}))))
    ;; Nodes present
    (let [nodes (:graph/nodes artifact [])]
      (if (seq nodes)
        (swap! checks conj {:check :nodes-present :pass? true :count (count nodes)})
        (do (swap! errors conj "Graph artifact has no nodes")
            (swap! checks conj {:check :nodes-present :pass? false}))))
    ;; Hash check
    (let [recorded (:artifact/hash artifact)
          computed (compute-graph-root-hash (:graph/nodes artifact []) (:graph/edges artifact []))]
      (if (= recorded computed)
        (swap! checks conj {:check :artifact-hash :pass? true})
        (do (swap! errors conj (str "Artifact hash mismatch: recorded " recorded " computed " computed))
            (swap! checks conj {:check :artifact-hash :pass? false :recorded recorded :computed computed}))))
    {:valid? (empty? @errors)
     :errors @errors
     :checks @checks}))

;; ── Evidence node DAG bridge ────────────────────────────────────────────────

(defn- shorten-hash
  "Shorten a 64-char hex hash to 12 chars for node IDs."
  [h]
  (subs (str h) 0 (min 12 (count h))))

(defn build-evidence-node-graph
  "Read a directory of evidence node EDN files and build a graph projection.

   Each EDN file should be a map with:
     :node-hash, :parent-hashes, :execution {:execution-kind ...}
     :result {:status ...}, :extensions {...}

   Returns a graph projection compatible with
   build-graph-evidence-artifact and write-graph-artifacts!:
     {:nodes  [{:node/id str :node/label str :node/data map} ...]
      :edges  [{:edge/from str :edge/to str :edge/label str} ...]
      :summary {:node-count N :edge-count M}}"
  [dir-path]
  (let [dir (io/file dir-path)]
    (if-not (.isDirectory dir)
      (throw (java.io.FileNotFoundException. (str "Evidence node directory not found: " dir-path)))
      (let [files (->> (.listFiles dir)
                       (filter #(.isFile %))
                       (filter #(.endsWith (.getName %) ".edn"))
                       (sort-by #(.getName %)))
            nodes (atom [])
            edges (atom [])]
        (doseq [f files]
          (try
            (let [node (edn/read-string (slurp f))
                  node-id (str (:node-hash node))
                  short-id (shorten-hash node-id)
                  kind (get-in node [:execution :execution-kind] :unknown)
                  status (get-in node [:result :status] :unknown)
                  label (str (name kind) " [" (name status) "]")
                  parent-hashes (vec (:parent-hashes node []))
                  data {:execution-id (get-in node [:execution :execution-id])
                        :runner (get-in node [:execution :runner])
                        :status status
                        :timestamp (get-in node [:timestamp] "")
                        :parent-hashes parent-hashes
                        :extensions (get node :extensions {})}]
              (swap! nodes conj {:node/id node-id
                                 :node/label label
                                 :node/short-id short-id
                                 :node/kind kind
                                 :node/status status
                                 :node/data data})
              (doseq [ph parent-hashes]
                (let [parent-id (str ph)]
                  (swap! edges conj {:edge/from parent-id
                                     :edge/to node-id
                                     :edge/label "depends-on"}))))
            (catch Exception e
              (println (str "  WARN: skipping " (.getName f) " — " (.getMessage e))))))
        (let [all-nodes @nodes
              all-edges @edges]
          {:nodes all-nodes
           :edges all-edges
           :summary {:node-count (count all-nodes)
                     :edge-count (count all-edges)
                     :task-status (if (some #(= :fail (:node/status %)) all-nodes)
                                    :has-failures
                                    :all-pass)}})))))

(defn render-evidence-node-dag!
  "Read evidence node EDN files from a directory and write graph artifacts.
   Convenience wrapper around build-evidence-node-graph + write-graph-artifacts!.

   Args:
     dir-path   — path to directory of .edn evidence node files
     out-dir    — output directory for graph artifacts

   Returns result map from write-graph-artifacts!."
  [dir-path out-dir]
  (let [graph (build-evidence-node-graph dir-path)
        short (shorten-hash (.getName (io/file dir-path)))]
    (write-graph-artifacts! graph {:title (str "Evidence node DAG: " dir-path)
                                   :task/ref (str "evidence-nodes/" short)}
                            out-dir)))

;; ── Execution DAG bridge (execution-dag.json) ───────────────────────────────

(defn build-execution-dag-graph
  "Read an execution-dag.json file and build a graph projection.

   The execution DAG format (from forensic.execution-dag/build-dag):
     {:dag/nodes [{:node/id str :node/type str :node/status str
                   :node/input-hashes map :node/output-hashes map} ...]
      :dag/edges [{:edge/from str :edge/to str :edge/type str} ...]
      :dag/node-count N :dag/edge-count M :dag/root-hash str}

   Returns a graph projection compatible with write-graph-artifacts!."
  [json-path]
  (let [f (io/file json-path)]
    (if-not (.isFile f)
      (throw (java.io.FileNotFoundException. (str "Execution DAG file not found: " json-path)))
      (let [dag (json/read-str (slurp f) :key-fn keyword)
            raw-nodes (or (:dag/nodes dag) [])
            raw-edges (or (:dag/edges dag) [])
            nodes (mapv (fn [n]
                          (let [nid (str (:node/id n))
                                ntype (name (:node/type n))
                                status (name (or (:node/status n) "pending"))
                                label (str ntype " [" status "]")]
                            {:node/id nid
                             :node/label label
                             :node/short-id (shorten-hash nid)
                             :node/data {:type (:node/type n)
                                         :status (:node/status n)
                                         :input-hashes (:node/input-hashes n)
                                         :output-hashes (:node/output-hashes n)}}))
                        raw-nodes)
            edges (mapv (fn [e]
                          {:edge/from (str (:edge/from e))
                           :edge/to (str (:edge/to e))
                           :edge/label (name (or (:edge/type e) "dependency"))})
                        raw-edges)]
        {:nodes nodes
         :edges edges
         :summary {:node-count (count nodes)
                   :edge-count (count edges)
                   :root-hash (:dag/root-hash dag)
                   :task-status (if (some #(= "fail" (or (:node/status %) "unknown")) raw-nodes)
                                  :has-failures
                                  :all-pass)}}))))

(defn render-execution-dag!
  "Read an execution-dag.json file and write graph artifacts.
   Convenience wrapper around build-execution-dag-graph + write-graph-artifacts!.

   Args:
     json-path — path to execution-dag.json
     out-dir   — output directory for graph artifacts

   Returns result map from write-graph-artifacts!."
  [json-path out-dir]
  (let [graph (build-execution-dag-graph json-path)
        base (.getName (io/file json-path))]
    (write-graph-artifacts! graph {:title (str "Execution DAG: " json-path)
                                   :task/ref (str "exec-dag/" (subs base 0 (min 12 (count base))))}
                            out-dir)))
