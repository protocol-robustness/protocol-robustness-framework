(ns resolver-sim.forensic.execution-dag
  "Formal execution DAG: typed nodes and edges with input/output hashes.
   Plan DAG is created before execution; Evidence DAG is populated during run.
   Callers may provide an explicit execution directory; the legacy arity writes
   to results/runs/<run-id>/execution-dag.json."
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [resolver-sim.evidence.reachability :as reach])
  (:import [java.security MessageDigest]
           [java.time Instant]))

(defn sha256 [s]
  (let [d (MessageDigest/getInstance "SHA-256")]
    (.update d (.getBytes (str s) "UTF-8"))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest d)))))

(defn- node-hash [node]
  (sha256 (pr-str (dissoc node :node/hash))))

(defn- edge-hash [edge]
  (sha256 (pr-str edge)))

(defn make-plan-node
  "Create a plan DAG node (no output hashes yet)."
  [{:keys [id type input-hashes] :or {type :scenario-run}}]
  (let [base {:node/id id :node/type type :node/input-hashes input-hashes}
        h (node-hash base)]
    (assoc base :node/hash h)))

(defn make-plan-edge
  "Create a DAG edge."
  [{:keys [from to type] :or {type :dependency}}]
  (let [base {:edge/from from :edge/to to :edge/type type}
        h (edge-hash base)]
    (assoc base :edge/hash h)))

(defn record-output
  "Add output hashes to a plan node, producing an evidence node."
  [plan-node {:keys [trace-hash world-final-hash invariant-results-hash evidence-hash]
              :or {trace-hash "" world-final-hash "" invariant-results-hash "" evidence-hash ""}}]
  (let [base (assoc plan-node
                    :node/output-hashes {:trace-hash trace-hash
                                         :world-final-hash world-final-hash
                                         :invariant-results-hash invariant-results-hash
                                         :evidence-hash evidence-hash}
                    :node/status :completed
                    :node/completed-at (str (Instant/now)))
        h (node-hash base)]
    (assoc base :node/hash h)))

(defn record-invariant-check
  "Add an invariant check result to a node."
  [node {:keys [invariant-id result world-before-hash world-after-hash evidence-hash]
         :or {world-before-hash "" world-after-hash "" evidence-hash ""}}]
  (let [check {:invariant/id invariant-id
               :invariant/hash (sha256 (str invariant-id))
               :result result
               :world-before-hash world-before-hash
               :world-after-hash world-after-hash
               :evidence-hash evidence-hash}
        existing (:node/invariant-checks node [])
        updated (assoc node :node/invariant-checks (conj existing check))]
    (assoc updated :node/hash (node-hash updated))))

(def ^:const dag-schema-version "execution-dag.v1")

(defn build-dag
  "Assemble a full DAG from plan nodes and edges. The optional identity map is
   additive within execution-dag.v1 for legacy read compatibility; finalized
   single-scenario package validation requires all three identity fields."
  ([nodes edges] (build-dag nodes edges nil))
  ([nodes edges {:keys [run-id scenario-id execution-id]}]
   (let [root-str (pr-str (sort-by :node/id nodes) (sort-by :edge/from edges))]
     (cond-> {:dag/schema-version dag-schema-version
              :dag/generated-at (str (Instant/now))
              :dag/nodes nodes
              :dag/edges edges
              :dag/node-count (count nodes)
              :dag/edge-count (count edges)
              :dag/root-hash (sha256 root-str)}
       run-id (assoc :run/id run-id)
       scenario-id (assoc :scenario/id scenario-id)
       execution-id (assoc :execution/id execution-id)))))

(defn- normalize-node [n]
  (cond-> n (:id n) (assoc :node/id (:id n)) (:type n) (assoc :node/type (:type n))
          (string? (:node/type n)) (update :node/type keyword)
          (:hash n) (assoc :node/hash (:hash n)) (:input-hashes n) (assoc :node/input-hashes (:input-hashes n))))
(defn- normalize-edge [e]
  (cond-> e (:from e) (assoc :edge/from (:from e)) (:to e) (assoc :edge/to (:to e))
          (:type e) (assoc :edge/type (:type e)) (:hash e) (assoc :edge/hash (:hash e))))

(defn validate-persisted-dag
  "Validate an execution-dag.v1 persisted object. With :require-identities?
   true, require explicit :run/id, :scenario/id, and :execution/id; generic
   legacy DAG validation accepts their absence for read compatibility."
  ([dag] (validate-persisted-dag dag {}))
  ([dag {:keys [require-identities?]}]
   (let [nodes (mapv normalize-node (:dag/nodes dag (:nodes dag [])))
         edges (mapv normalize-edge (:dag/edges dag (:edges dag [])))
         ids (map :node/id nodes) id-set (set ids)
         expected-root (sha256 (pr-str (sort-by :node/id nodes) (sort-by :edge/from edges)))
         edge-identities (map (fn [e] [(:edge/from e) (:edge/to e) (:edge/type e)]) edges)
         adjacency (reduce (fn [m {:edge/keys [from to]}] (update m from (fnil conj #{}) to)) {} edges)
         reachable (if-let [start (first ids)]
                     (loop [seen #{start} todo [start]]
                       (if-let [n (first todo)]
                         (let [nexts (set (concat (get adjacency n #{})
                                                  (for [{:edge/keys [from to]} edges :when (= to n)] from)))
                               unseen (remove seen nexts)]
                           (recur (into seen unseen) (into (vec (rest todo)) unseen)))
                         seen))
                     #{})
         reasons (vec (concat
                       (when (empty? nodes) [{:code :execution-dag/empty-graph}])
                       (when require-identities?
                         (mapcat (fn [[field value]]
                                   (cond
                                     (nil? value) [{:code :execution-dag/missing-identity :field field}]
                                     (not (and (string? value) (seq value))) [{:code :execution-dag/malformed-identity :field field :value value}]
                                     :else []))
                                 [[:run/id (:run/id dag)]
                                  [:scenario/id (:scenario/id dag)]
                                  [:execution/id (:execution/id dag)]]))
                        (when-not (= dag-schema-version (or (:dag/schema-version dag) (:schema-version dag))) [{:code :execution-dag/unsupported-schema}])
                       (when-not (vector? nodes) [{:code :execution-dag/nodes-not-vector}])
                       (when-not (= (count ids) (count id-set)) [{:code :execution-dag/duplicate-node-id}])
                       (when (some nil? ids) [{:code :execution-dag/missing-node-id}])
                       (when-not (= (count edge-identities) (count (set edge-identities))) [{:code :execution-dag/duplicate-edge}])
                       (when (and (seq ids) (reach/dag-cycle-path adjacency)) [{:code :execution-dag/cycle-detected}])
                       (for [n nodes :when (not= (:node/hash n) (node-hash (dissoc n :node/hash)))] {:code :execution-dag/node-hash-mismatch :node-id (:node/id n)})
                       (for [e edges :when (or (not (contains? id-set (:edge/from e))) (not (contains? id-set (:edge/to e))))] {:code :execution-dag/missing-edge-node :edge e})
                       (when-not (= (or (:dag/root-hash dag) (:root-hash dag)) expected-root) [{:code :execution-dag/root-hash-mismatch}])
                       (when (and (seq ids) (not= id-set reachable)) [{:code :execution-dag/disconnected-node}])))]
     {:valid? (empty? reasons) :status (if (empty? reasons) :valid :invalid)
      :root-node-hash (:dag/root-hash dag) :node-hashes (set (map :node/hash nodes))
      :node-ids id-set :nodes nodes
      :run-id (:run/id dag) :scenario-id (:scenario/id dag) :execution-id (:execution/id dag)
      :reasons reasons})))

(defn valid-persisted-dag? [dag] (:valid? (validate-persisted-dag dag)))

(defn- json-key [k]
  (if (keyword? k)
    (if-let [ns (namespace k)] (str ns "/" (name k)) (name k))
    (str k)))

(defn write-dag!
  "Write DAG to disk. Returns the path written.

   The two-argument arity preserves the legacy results/runs location. Supplying
   execution-dir keeps a structured run self-contained."
  ([dag run-id]
   (write-dag! dag run-id nil))
  ([dag run-id execution-dir]
   (let [dir (or execution-dir
                 (str (io/file "results" "runs" (or run-id "unknown"))))
         f (io/file dir "execution-dag.json")]
     (.mkdirs (io/file dir))
     (spit f (json/write-str dag {:key-fn json-key :indent true}))
     (.getPath f))))
