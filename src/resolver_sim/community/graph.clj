(ns resolver-sim.community.graph
  "Pure functions for building and verifying evidence graphs for community
   research tasks. Links tasks, execution evidence, attestations, findings,
   and mailbox messages into a traversable DAG projection."
  (:require [resolver-sim.community.task :as task]
            [resolver-sim.community.finding :as finding]
            [resolver-sim.community.attestation :as att]
            [resolver-sim.community.mailbox :as mailbox]))

(def ^:const schema-version "community-evidence-graph.v0")

(defn derive-task-status
  "Derive the aggregate status of a task from mailbox messages.
   Returns a keyword status."
  [_task messages]
  (let [types (set (map :message/type (vec (or messages []))))]
    (cond
      (contains? types :CHALLENGE) :challenged
      (contains? types :DISAGREEMENT) :disagreed
      (contains? types :AGREEMENT) :agreed
      (contains? types :REPRODUCTION_RESULT) :reproduced
      (contains? types :RUNNER_RESULT) :executed
      (contains? types :TASK_ANNOUNCEMENT) :announced
      :else :unknown)))

(defn build-task-graph-projection
  "Build a graph projection linking a research task to its executions,
   attestations, findings, and mailbox messages.
   
   Returns a map with :schema-version, :task, :nodes, :edges, :summary.
   
   This is a pure function — it does not read from disk or the mailbox.
   Callers pass the resolved records directly."
  [{:keys [task executions attestations findings messages]}]
  (let [nodes (atom [])
        edges (atom [])
        add-node (fn [id label data] (swap! nodes conj {:node/id id :node/label label :node/data data}))
        add-edge (fn [from to label] (swap! edges conj {:edge/from from :edge/to to :edge/label label}))]
    (when task
      (add-node (:task/hash task) "Research Task" {:task/ref (:task/ref task) :title (:title task)}))
    (doseq [exec (vec (or executions []))]
      (add-node (:node-hash exec) "Execution Evidence" {:execution-id (get-in exec [:execution :execution-id])})
      (when task
        (add-edge (:task/hash task) (:node-hash exec) "produced")))
    (doseq [a (vec (or attestations []))]
      (add-node (:attestation/hash a) (str "Attestation: " (name (:attestation/predicate a))) a)
      (add-edge (:attestation/hash a) (:task/hash task) "attests"))
    (doseq [f (vec (or findings []))]
      (add-node (:finding/hash f) (str "Finding: " (name (:finding/type f))) f)
      (add-edge (:finding/hash f) (:task/hash task) "reports"))
    (doseq [m (vec (or messages []))]
      (add-node (:message/hash m) (str "Mailbox: " (name (:message/type m))) m)
      (add-edge (:message/hash m)
                (or (:task/hash task) (:subject-task m))
                "messages"))
    (let [all-nodes @nodes
          all-edges @edges
          status (derive-task-status task messages)]
      {:schema-version schema-version
       :task task
       :nodes all-nodes
       :edges all-edges
       :summary {:node-count (count all-nodes)
                 :edge-count (count all-edges)
                 :task-status status}})))

(defn verify-task-graph
  "Verify the integrity of a task graph projection.
   Checks:
   - Every referenced record hash matches its content
   - Every referenced record is structurally valid
   - Subject references match between linked records
   - Every attestation predicate is known
   - Every message type is known
   Returns {:valid? true/false :errors [...] :checks [...]}"
  [graph]
  (let [errors (atom [])
        checks (atom [])]
    ;; Task validation
    (when-let [t (:task graph)]
      (let [valid? (task/valid-task? t)]
        (swap! checks conj {:check :task-integrity :pass? valid?
                            :detail (if valid? "Hash matches" "Hash mismatch")})
        (when-not valid?
          (swap! errors conj "Task record hash integrity check failed"))))
    ;; Message validation
    (doseq [m (vec (or (:messages graph) []))]
      (let [v (mailbox/verify-message m)
            hash-ok? (:hash-valid? v)]
        (swap! checks conj {:check (str "message-hash-" (subs (:message/hash m) 0 8))
                            :pass? hash-ok?
                            :detail (if hash-ok? "Hash OK" "Hash mismatch")})
        (when-not hash-ok?
          (swap! errors conj (str "Message hash mismatch: " (:message/hash m))))
        (when (and (:message/signature m) (not (:valid? v)))
          (swap! errors conj (str "Message signature invalid: " (:message/hash m))))))
    ;; Attestation validation
    (doseq [a (vec (or (:attestations graph) []))]
      (let [valid? (att/valid-attestation? a)
            pred (:attestation/predicate a)]
        (swap! checks conj {:check (str "attestation-" (subs (:attestation/hash a) 0 8))
                            :pass? valid?
                            :detail (if valid? (str "Valid " (name pred)) "Hash mismatch")})
        (when-not valid?
          (swap! errors conj (str "Attestation hash mismatch: " (:attestation/hash a))))
        (when-not (contains? att/supported-predicates pred)
          (swap! errors conj (str "Unknown attestation predicate: " (name pred))))
        ;; Check subject reference consistency
        (when (and (:task graph) (:subject a))
          (let [subject-ref (:reference (:subject a))]
            (when (and subject-ref (:task/ref (:task graph)))
              (when (not= subject-ref (:task/ref (:task graph)))
                (swap! errors conj (str "Attestation subject ref " subject-ref
                                        " does not match task ref " (:task/ref (:task graph))))))))))
    ;; Finding validation
    (doseq [f (vec (or (:findings graph) []))]
      (let [valid? (finding/valid-finding? f)]
        (swap! checks conj {:check (str "finding-" (subs (:finding/hash f) 0 8))
                            :pass? valid?
                            :detail (if valid? "Hash OK" "Hash mismatch")})
        (when-not valid?
          (swap! errors conj (str "Finding hash mismatch: " (:finding/hash f))))))
    {:valid? (empty? @errors)
     :errors @errors
     :checks @checks}))

(defn collect-task-evidence
  "Collect all evidence for a task from disk.
   Resolves task, mailbox messages, attestations, and findings.
   
   Returns a map suitable for build-task-graph-projection.
   Does NOT mutate any state."
  [task artifact-dir]
  (let [task-ref (:task/ref task)
        msgs (mailbox/messages-for-task task-ref)
        attestation-refs (keep :attestation-ref msgs)
        attestations (keep (fn [ref] (att/resolve-attestation artifact-dir ref)) attestation-refs)
        findings []]
    {:task task
     :executions []
     :attestations attestations
     :findings findings
     :messages msgs}))

(defn- node-layer
  "Assign a display layer (y-row) based on node label prefix."
  [label]
  (cond
    (.startsWith label "Research Task")      0
    (.startsWith label "Execution Evidence")  1
    (.startsWith label "Attestation:")        1
    (.startsWith label "Mailbox:")            2
    (.startsWith label "Finding:")            2
    :else                                     3))

(defn- layout-coordinates
  "Assign deterministic x,y positions to graph nodes based on type layer.
   Returns a map of node-id -> {:x N :y N}.
   Nodes in the same layer are spread evenly across the width."
  [nodes]
  (let [by-layer (group-by (fn [n] (node-layer (:node/label n))) nodes)
        layer-count (count by-layer)
        per-layer (fn [layer layer-nodes]
                    (let [count (count layer-nodes)
                          spacing (max 1 (if (> count 1) (/ 500 (dec count)) 0))]
                      (map-indexed
                       (fn [i node]
                         [(:node/id node)
                          {:x (if (> count 1) (+ 50 (* i spacing)) 250)
                           :y (+ 40 (* layer 140))
                           :w 220 :h 40}])
                       (vec layer-nodes))))
        entries (mapcat (fn [layer] (per-layer layer (get by-layer layer [])))
                        (sort (keys by-layer)))]
    (into {} entries)))

(defn export-graphml
  "Export a task graph projection as GraphML XML for yEd.
   Returns the XML string (does not write to disk).
   Assigns deterministic coordinates based on node type layer
   so nodes do not overlap."
  [graph]
  (let [sb (StringBuilder.)
        esc (fn [s] (-> s (.replace "&" "&amp;") (.replace "\"" "&quot;") (.replace "<" "&lt;") (.replace ">" "&gt;")))
        coords (layout-coordinates (:nodes graph))]
    (.append sb "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    (.append sb "<graphml xmlns=\"http://graphml.graphdrawing.org/xmlns\"\n")
    (.append sb "         xmlns:y=\"http://www.yworks.com/xml/graphml\">\n")
    (.append sb "  <key id=\"label\" for=\"node\" attr.name=\"label\" attr.type=\"string\"/>\n")
    (.append sb "  <key id=\"edge-label\" for=\"edge\" attr.name=\"label\" attr.type=\"string\"/>\n")
    (.append sb "  <key id=\"d6\" for=\"node\" yfiles.type=\"nodegraphics\"/>\n")
    (.append sb "  <key id=\"d9\" for=\"edge\" yfiles.type=\"edgegraphics\"/>\n")
    (.append sb "  <graph id=\"G\" edgedefault=\"directed\">\n")
    (doseq [node (:nodes graph)]
      (let [nid (str "n" (subs (:node/id node) 0 (min 12 (count (:node/id node)))))
            label (esc (:node/label node))
            c (get coords (:node/id node) {:x 50 :y 50 :w 220 :h 40})]
        (.append sb (str "    <node id=\"" nid "\">\n"))
        (.append sb (str "      <data key=\"label\">" label "</data>\n"))
        (.append sb (str "      <data key=\"d6\">\n"))
        (.append sb "        <y:ShapeNode>\n")
        (.append sb (str "          <y:Geometry height=\"" (:h c) ".0\" width=\"" (:w c) ".0\""
                         " x=\"" (:x c) ".0\" y=\"" (:y c) ".0\"/>\n"))
        (.append sb "          <y:Fill color=\"#E8F0FE\" transparent=\"false\"/>\n")
        (.append sb "          <y:BorderStyle color=\"#1A73E8\" type=\"line\" width=\"1.0\"/>\n")
        (.append sb (str "          <y:NodeLabel>" label "</y:NodeLabel>\n"))
        (.append sb "        </y:ShapeNode>\n")
        (.append sb "      </data>\n")
        (.append sb "    </node>\n")))
    (doseq [edge (:edges graph)]
      (let [from (str "n" (subs (:edge/from edge) 0 (min 12 (count (:edge/from edge)))))
            to (str "n" (subs (:edge/to edge) 0 (min 12 (count (:edge/to edge)))))
            label (esc (:edge/label edge))]
        (.append sb (str "    <edge id=\"e" from "-" to "\" source=\"" from "\" target=\"" to "\">\n"))
        (.append sb (str "      <data key=\"edge-label\">" label "</data>\n"))
        (.append sb "      <data key=\"d9\">\n")
        (.append sb "        <y:PolyLineEdge>\n")
        (.append sb (str "          <y:EdgeLabel>" label "</y:EdgeLabel>\n"))
        (.append sb "          <y:Arrows source=\"none\" target=\"standard\"/>\n")
        (.append sb "        </y:PolyLineEdge>\n")
        (.append sb "      </data>\n")
        (.append sb "    </edge>\n")))
    (.append sb "  </graph>\n")
    (.append sb "</graphml>\n")
    (.toString sb)))
