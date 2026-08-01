(ns resolver-sim.evidence.reachability
  "Evidence chain and execution DAG reachability queries with short-circuit wiring.

   Two graph types are supported:

   **Evidence chains** (linear hash chains):
     A chain has a vector of `:reachable-hashes` — the hashes reachable from
     the chain head.  A hash is reachable if it appears in that vector.

   **Execution DAGs** (directed acyclic graphs):
     A DAG has `:dag/nodes` and `:dag/edges`.  A node is reachable from
     another if there is a directed path through the edges.

   Both types share the same `reachable?` entry point — dispatch on the
   data shape is automatic.

   Short-circuit optimisations:
     1. Same-node/hash → true (no traversal)
     2. Nil/empty inputs → false
     3. Direct edge (DAG) → true
     4. Hash in reachable-set (chain) → true
     5. Otherwise → full traversal (BFS for DAG, set membership for chain)"
  (:require [clojure.set :as set]))

;; ── Chain reachability ──────────────────────────────────────────────────────

(defn- build-reachable-set
  "Build a set of reachable hashes from a chain.
   Accepts any of:
     - a chain map with :chain/reachable-hashes key
     - a finalization map with :chain/reachable-hashes key
     - a plain vector of hash strings
   Returns a set, or nil if no reachable data is found."
  [chain-or-hashes]
  (cond
    (nil? chain-or-hashes) nil
    (set? chain-or-hashes) chain-or-hashes
    (vector? chain-or-hashes) (set chain-or-hashes)
    (:chain/reachable-hashes chain-or-hashes)
    (set (:chain/reachable-hashes chain-or-hashes))
    :else nil))

(defn chain-reachable?
  "True when hash is reachable in the chain (exists in reachable-hashes).

   Short-circuits:
     - same-hash is trivially true
     - nil/empty hash or chain → false
     - hash in reachable-set → true
     - otherwise → false (chain is a set, not a graph for traversal)"
  ([hash chain]
   (chain-reachable? hash chain nil))
  ([hash chain {:keys [allow-empty?]}]
   (let [hash   (when (string? hash) hash)
         rset   (build-reachable-set chain)]
     (cond
       (nil? hash) false
       (nil? rset) (boolean allow-empty?)
       (contains? rset hash) true
       :else false))))

(defn chain-ancestors
  "Return the set of hashes between from-hash and to-hash inclusive.
   Both hashes must be in the chain's reachable set.
   Returns nil if either hash is not reachable, or if from-hash is not an
   ancestor of to-hash (i.e. appears earlier in the reachable-hashes vector)."
  [chain from-hash to-hash]
  (let [hashes (:chain/reachable-hashes chain)
        from-idx (when hashes (first (keep-indexed #(when (= %2 from-hash) %1) hashes)))
        to-idx   (when hashes (first (keep-indexed #(when (= %2 to-hash) %1) hashes)))]
    (when (and from-idx to-idx (<= from-idx to-idx))
      (set (subvec hashes from-idx (inc to-idx))))))

;; ── DAG reachability ─────────────────────────────────────────────────────────

(defn- build-adjacency
  "Build {node-id #{child-ids}} from DAG edges.
   Accepts a DAG map or a raw edge vector.
   Returns a map (possibly empty)."
  [dag-or-edges]
  (let [edges (cond
                (nil? dag-or-edges) []
                (:dag/edges dag-or-edges) (:dag/edges dag-or-edges)
                (sequential? dag-or-edges) dag-or-edges
                :else [])]
    (reduce (fn [m {:edge/keys [from to]}]
              (-> m
                  (update from (fnil conj #{}) to)))
            {} edges)))

(defn- build-adjacency-bidi
  "Like build-adjacency but also adds reverse edges (undirected traversal)."
  [dag-or-edges]
  (let [edges (cond
                (nil? dag-or-edges) []
                (:dag/edges dag-or-edges) (:dag/edges dag-or-edges)
                (sequential? dag-or-edges) dag-or-edges
                :else [])]
    (reduce (fn [m {:edge/keys [from to]}]
              (-> m
                  (update from (fnil conj #{}) to)
                  (update to (fnil conj #{}) from)))
            {} edges)))

(defn- bfs-reachable
  "BFS from start, return set of all reachable node ids.
   adjacency is a {node-id #{child-ids}} map."
  [adjacency start]
  (loop [seen #{start} queue [start]]
    (if-let [node (first queue)]
      (let [children (get adjacency node #{})
            unseen (set/difference children seen)]
        (recur (into seen unseen)
               (into (vec (rest queue)) unseen)))
      seen)))

(defn- dag-cycle?
  [adjacency node-ids]
  (let [state (atom {})]
    (letfn [(visit [node]
              (case (get @state node)
                :visiting true
                :visited false
                (do (swap! state assoc node :visiting)
                    (let [cyclic? (boolean (some visit (get adjacency node #{})))]
                      (swap! state assoc node :visited)
                      cyclic?))))]
      (boolean (some visit node-ids)))))

(defn dag-structural-errors
  "Return deterministic structural errors for a declared execution DAG.
   Reachability queries remain permissive utilities; callers that need to admit
   a DAG as evidence can require this result to be empty."
  [dag]
  (let [nodes (:dag/nodes dag [])
        edges (:dag/edges dag [])
        ids (map :node/id nodes)
        id-set (set ids)
        adjacency (build-adjacency edges)
        base-errors (cond-> []
                      (not (vector? nodes)) (conj :dag/nodes-not-vector)
                      (not (vector? edges)) (conj :dag/edges-not-vector)
                      (some #(or (not (map? %)) (nil? (:node/id %))) nodes)
                      (conj :dag/invalid-node)
                      (not= (count ids) (count id-set))
                      (conj :dag/duplicate-node-id)
                      (some #(or (not (map? %))
                                 (nil? (:edge/from %))
                                 (nil? (:edge/to %))) edges)
                      (conj :dag/invalid-edge)
                      (some #(or (not (contains? id-set (:edge/from %)))
                                 (not (contains? id-set (:edge/to %)))) edges)
                      (conj :dag/edge-endpoint-undeclared)
                      (some #(= (:edge/from %) (:edge/to %)) edges)
                      (conj :dag/self-loop))]
    (cond-> base-errors
      (and (empty? base-errors) (dag-cycle? adjacency id-set))
      (conj :dag/cycle))))

(defn dag-reachable?
  "True when to-id is reachable from from-id in the DAG.

   Short-circuits:
     - same node → true
     - nil inputs → false
     - direct edge (from adjacency) → true
     - otherwise → BFS traversal"
  ([dag from-id to-id]
   (dag-reachable? dag from-id to-id nil))
  ([dag from-id to-id _opts]
   (let [adj (build-adjacency dag)]
     (cond
       (or (nil? from-id) (nil? to-id)) false
       (= from-id to-id) true
       (contains? (get adj from-id #{}) to-id) true
       :else (contains? (bfs-reachable adj from-id) to-id)))))

(defn dag-ancestors
  "Return the set of all node IDs that can reach from-id (inclusive).
   Uses reverse-edge traversal."
  [dag from-id]
  (let [forward-adj (build-adjacency dag)
        ;; Ancestors = nodes from which from-id is reachable via forward edges
        ;; We find this by reversing the graph and BFS from from-id
        reverse-adj (reduce-kv (fn [m from tos]
                                 (reduce #(update %1 %2 (fnil conj #{}) from) m tos))
                               {} forward-adj)]
    (bfs-reachable reverse-adj from-id)))

(defn dag-descendants
  "Return the set of all node IDs reachable from from-id (inclusive)."
  [dag from-id]
  (let [adj (build-adjacency dag)]
    (bfs-reachable adj from-id)))

(defn dag-shortest-path
  "Return the shortest directed path from from-id to to-id as a vector of node IDs.
   Returns nil if no path exists.
   Uses BFS which guarantees shortest path in unweighted graphs."
  [dag from-id to-id]
  (let [adj (build-adjacency dag)]
    (when (and from-id to-id (not= from-id to-id))
      (loop [seen #{from-id} queue [[from-id]]]
        (if-let [path (first queue)]
          (let [current (last path)]
            (if (= current to-id)
              path
              (let [children (get adj current #{})
                    unseen (set/difference children seen)]
                (recur (into seen unseen)
                       (into (vec (rest queue))
                             (map #(conj path %) (sort unseen)))))))
          nil)))))

;; ── Unified dispatch ─────────────────────────────────────────────────────────

(defn reachable?
  "True when from is reachable to to in the given structure.

   Automatic dispatch:
     - If the structure has :chain/reachable-hashes, treat as evidence chain
     - If the structure has :dag/nodes or :dag/edges, treat as execution DAG
     - Otherwise, treat as a plain vector/set (chain of hashes)

   For chains: from is ignored (order is implicit in the reachable set);
              pass nil for from, and hash as to.
   For DAGs:  from and to are node IDs.

   Short-circuit applies to both types:
     - same-node/trivial → immediate return"
  ([structure to]
   (reachable? structure nil to nil))
  ([structure from to]
   (reachable? structure from to nil))
  ([structure from to opts]
   (cond
     ;; DAG dispatch
     (or (:dag/nodes structure) (:dag/edges structure))
     (dag-reachable? structure from to opts)

     ;; Chain dispatch (from is ignored)
     (or (:chain/reachable-hashes structure)
         (vector? structure)
         (set? structure))
     (chain-reachable? to structure opts)

     ;; Unknown
     :else
     (throw (ex-info "Cannot determine reachability structure type"
                     {:structure (type structure)
                      :keys (when (map? structure) (keys structure))})))))

;; ── Reporting ────────────────────────────────────────────────────────────────

(defn reachability-report
  "Return a structured report for a DAG or chain.

   For DAGs:
     :node-count        — total nodes
     :edge-count        — total edges
     :disconnected      — nodes not reachable from the first node
     :weakly-connected? — all nodes reachable when edges are treated as undirected
     :components        — weakly connected component sizes

   For chains:
     :hash-count        — number of reachable hashes
     :chain-gaps        — hashes referenced but not in reachable set (when check-set provided)
     :head-hash         — first hash in reachable set
     :tail-hash         — last hash in reachable set"
  [structure & {:keys [check-set]}]
  (cond
    (or (:dag/nodes structure) (:dag/edges structure))
    (let [nodes (:dag/nodes structure [])
          edges (:dag/edges structure [])
          ids (set (map :node/id nodes))
          adj (build-adjacency edges)
          rev-adj (build-adjacency-bidi edges)
          start-id (first (sort ids))
          reachable (if start-id (bfs-reachable adj start-id) #{})
          undirected-reachable (if start-id (bfs-reachable rev-adj start-id) #{})
          roots (set/difference ids (set (map :edge/to edges)))
          root-reachable (reduce set/union #{}
                                 (map #(bfs-reachable adj %) roots))
          errors (dag-structural-errors structure)
          components (let [visited (volatile! #{})
                           component-sizes (volatile! [])]
                       (doseq [nid (sort ids)]
                         (when-not (contains? @visited nid)
                           (let [comp (bfs-reachable rev-adj nid)]
                             (vswap! visited into comp)
                             (vswap! component-sizes conj (count comp)))))
                       (sort > @component-sizes))]
      {:type :dag
       :valid? (empty? errors)
       :errors errors
       :node-count (count nodes)
       :edge-count (count edges)
       :roots (vec (sort roots))
       :disconnected (vec (sort (set/difference ids reachable)))
       :unreachable-from-roots (vec (sort (set/difference ids root-reachable)))
       :weakly-connected? (= (count ids) (count undirected-reachable))
       :components (vec components)})

    (or (:chain/reachable-hashes structure)
        (vector? structure)
        (set? structure))
    (let [ordered? (or (vector? (:chain/reachable-hashes structure))
                       (vector? structure))
          hashes (if (vector? (:chain/reachable-hashes structure))
                   (:chain/reachable-hashes structure)
                   (if (vector? structure) structure (sort structure)))
          rset (set hashes)
          gaps (when check-set
                 (vec (sort (set/difference (set check-set) rset))))]
      {:type :chain
       :valid? true
       :ordered? ordered?
       :hash-count (count hashes)
       :head-hash (when ordered? (first hashes))
       :tail-hash (when ordered? (last hashes))
       :chain-gaps gaps
       :gaps? (boolean (seq gaps))})

    :else
    {:type :unknown
     :error "Cannot determine structure type"
     :keys (when (map? structure) (keys structure))}))
