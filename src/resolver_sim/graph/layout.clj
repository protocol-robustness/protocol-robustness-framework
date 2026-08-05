(ns resolver-sim.graph.layout
  "Canonical graph layout and layer assignment, shared by graph producers
   (community.graph) and graph exporters (graph.export).

   `node-layer` assigns a display layer from a node label prefix. `layer-colors`
   and `layer-names` map layers to display styling, and `layer-color` resolves a
   single layer to its fill color. `layout-coordinates` assigns deterministic
   x/y positions (wrapping into multiple rows per layer for dense graphs), and
   `svg-dimensions` derives the canvas size from those coordinates.")

(defn node-layer
  "Assign display layer based on node label prefix."
  [label]
  (cond
    (.startsWith label "Research Task")      0
    (.startsWith label "Execution Evidence")  1
    (.startsWith label "Attestation:")        1
    (.startsWith label "Mailbox:")            2
    (.startsWith label "Finding:")            2
    :else                                     3))

(def layer-colors
  "Node fill colors per layer index."
  {0 "#1A73E8"
   1 "#34A853"
   2 "#FBBC04"
   3 "#8B5CF6"})

(def layer-names
  "Display names per layer index."
  {0 "Task"
   1 "Execution / Attestation"
   2 "Mailbox / Finding"
   3 "Other"})

(defn layer-color
  "Resolve a layer index to its fill color."
  [layer]
  (get layer-colors layer "#9CA3AF"))

;; ── Layout geometry ──────────────────────────────────────────────────────────

(def ^:private node-width 200.0)
(def ^:private node-height 44.0)
(def ^:private node-h-gap 28.0)
(def ^:private row-v-gap 28.0)
(def ^:private layer-h-gap 60.0)
(def ^:private margin-x 60.0)
(def ^:private margin-y-top 50.0)
(def ^:private max-row-width 1100.0)

(defn- floatify
  "Ensure a value is a double for SVG coordinate output."
  [x]
  (double x))

(defn- nodes-per-row
  "Maximum nodes that fit in one row within max-row-width."
  [total-nodes]
  (if (zero? total-nodes)
    0
    (let [per-row (max 1 (int (/ (- max-row-width margin-x)
                                 (+ node-width node-h-gap))))]
      (min total-nodes per-row))))

(defn layout-coordinates
  "Assign deterministic x,y positions to graph nodes based on type layer.
   Nodes wrap into multiple rows within each layer to keep width compact.
   All coordinates are doubles for valid SVG output.
   Returns a map of node-id -> {:x N :y N :w N :h N}."
  [nodes]
  (let [by-layer (group-by (fn [n] (node-layer (:node/label n))) nodes)
        per-layer (fn [layer layer-nodes]
                    (if (empty? layer-nodes)
                      []
                      (let [n (count layer-nodes)
                            npr (nodes-per-row n)
                            n-rows (max 1 (int (Math/ceil (/ (double n) (double npr)))))
                            row-fn (fn [row-idx]
                                     (let [row-nodes (subvec (vec layer-nodes)
                                                             (* row-idx npr)
                                                             (min n (* (inc row-idx) npr)))
                                           rn (count row-nodes)
                                           spacing (if (> rn 1)
                                                     (/ (- max-row-width (* (double rn) node-width))
                                                        (double (dec rn)))
                                                     node-h-gap)
                                           total-w (+ (* (double rn) node-width)
                                                      (* (double (dec rn)) spacing))
                                           start-x (+ margin-x
                                                      (/ (- max-row-width total-w) 2.0))]
                                       (map-indexed
                                        (fn [i node]
                                          [(:node/id node)
                                           {:x (floatify (+ start-x (* (double i) (+ node-width spacing))))
                                            :y (floatify (+ margin-y-top
                                                            (* (double layer) layer-h-gap)
                                                            (* (double row-idx) (+ node-height row-v-gap))))
                                            :w node-width :h node-height}])
                                        row-nodes)))]
                        (mapcat row-fn (range n-rows)))))
        entries (mapcat (fn [layer] (per-layer layer (get by-layer layer [])))
                        (sort (keys by-layer)))
        coords (into {} entries)]
    coords))

(defn svg-dimensions
  "Compute SVG width and height from layout coordinates.
   Uses actual node positions to determine canvas extent,
   handling sparse layer assignments (e.g. all nodes in layer 3)."
  [coords]
  (let [max-x (if (seq coords)
                (apply max (map (fn [c] (+ (:x c) (:w c))) (vals coords)))
                0.0)
        max-y (if (seq coords)
                (apply max (map (fn [c] (+ (:y c) (:h c))) (vals coords)))
                0.0)
        w (max 820.0 (+ max-x margin-x))
        h (max 314.0 (+ max-y 100.0))]
    {:width (int w) :height (int h)}))
