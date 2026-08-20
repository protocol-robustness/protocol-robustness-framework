(ns resolver-sim.extensions.resolution
  "Frozen extension resolution: builds the immutable resolution snapshot for a
   set of requested capabilities against an extension-map.

   Per ADR-0005 the snapshot contains the complete transitive dependency graph
   (not only directly requested capabilities), schema roots, effect schema
   roots, and the runtime profile, closed by a content-addressed
   :extensions/resolution-root.

   The resolver fails on: missing capabilities, missing dependencies,
   dependency cycles, ambiguous providers, incompatible contract versions,
   unsealed transitive dependencies in a sealed run, multiple roots for the
   same supposedly exact dependency, and unresolved schema references."
  (:require [resolver-sim.extensions.manifest :as em]
            [resolver-sim.hash.canonical :as hc]))

(def resolution-version 1)

(def resolution-domain-tag
  "EXTENSION_RESOLUTION_V1")

;; ── helpers ───────────────────────────────────────────────────────────────

(defn- normalize-request
  [r]
  (when (and (vector? r)
             (= 2 (count r))
             (keyword? (nth r 0))
             (keyword? (nth r 1)))
    (vec r)))

(defn- dependency-specs
  "Dependency edges of a resolved registry entry:
   [{:to [kind id] :requirement <map-or-nil>} ...]."
  [entry]
  (mapv (fn [d]
          {:to [(get d :capability/kind) (get d :capability/id)]
           :requirement (:requirement d)})
        (:declared-dependencies (:capability entry) [])))

(defn- closure
  "Breadth-first transitive closure over the extension-map.
   Returns {:nodes {key entry} :edges [{:from key :to key :requirement map}]}."
  [extension-map roots]
  (loop [queue (vec roots)
         seen #{}
         nodes {}
         edges []]
    (if-let [k (first queue)]
      (let [rest-q (subvec queue 1)]
        (if (or (nil? (get extension-map k)) (contains? seen k))
          (recur rest-q seen nodes edges)
          (let [deps (dependency-specs (get extension-map k))
                seen' (conj seen k)
                nodes' (assoc nodes k (get extension-map k))
                queue' (into rest-q (remove seen' (map :to deps)))
                edges' (into edges (map (fn [d] (assoc d :from k)) deps))]
            (recur queue' seen' nodes' edges'))))
      {:nodes nodes :edges edges})))

(defn- find-cycle
  "Find a dependency cycle in the node graph; return the cycle path vector
   (e.g. [a b a]) or nil."
  [nodes]
  (let [adj (into {} (map (fn [[k entry]] [k (mapv :to (dependency-specs entry))]))
                  nodes)]
    (letfn [(dfs-from [start]
              (loop [stack [[start]]]
                (when (seq stack)
                  (let [path (peek stack)
                        rest-stack (pop stack)
                        node (peek path)
                        neighbors (adj node)]
                    (if (some #{start} neighbors)
                      (conj path start)
                      (let [nexts (remove (set path) neighbors)]
                        (if (seq nexts)
                          (recur (into rest-stack (map (fn [n] (conj path n))) nexts))
                          (recur rest-stack))))))))]
      (when-let [cycle (some (fn [start] (dfs-from start)) (keys adj))]
        (vec cycle)))))

(defn- requirement-satisfied?
  [requirement capability]
  (let [req-version (:capability/version requirement)
        req-contract (:capability/contract-version requirement)
        req-profile (:capability/profile requirement)]
    (and (or (nil? req-version) (= req-version (:capability/version capability)))
         (or (nil? req-contract) (= req-contract (:capability/contract-version capability)))
         (or (nil? req-profile) (= req-profile (:capability/profile capability))))))

(defn- schema-refs-of
  "Schema ids referenced by a resolved registry entry."
  [entry]
  (keep (fn [k] (get (:capability entry) k))
        [:input-schema :output-schema :verification/contract]))

(defn- provider-roots
  [entry]
  (vec (sort (map :package-root (:providers entry)))))

(defn- capability-providers
  "Map each resolved capability key to its provider package records, enabling
   the composition to prove which package/component root provides each capability
   and each dependency edge's resolved provider identity."
  [entry]
  {:providers (vec (sort-by :package-root
                            (map (fn [p]
                                   {:package/id (:package/id p)
                                    :package/version (:package/version p)
                                    :package-root (:package-root p)
                                    :sealed (:sealed p)})
                                 (:providers entry []))))})

(defn- enriched-dependency
  "Add resolved provider package/component roots to a dependency edge so the
   composition can prove, per edge, the consumer capability identity, declared
   requirement, and resolved provider package root."
  [edge nodes]
  (let [to-entry (get nodes (:to edge))]
    (if (nil? to-entry)
      edge
      (assoc edge :provider-package-roots (provider-roots to-entry)))))

;; ── snapshot builder ──────────────────────────────────────────────────────

(defn- build-snapshot
  [nodes edges runtime-profile schemas effect-schemas]
  (let [packages (reduce (fn [acc [_ entry]]
                           (reduce (fn [acc' p]
                                     (assoc acc' (:package/id p)
                                            {:package/id (:package/id p)
                                             :package/version (:package/version p)
                                             :package-root (:package-root p)
                                             :sealed (:sealed p)}))
                                 acc
                                 (:providers entry)))
                         {}
                         nodes)
         ;; capabilities are committed as their hashable projections
         ;; (entrypoints are symbols in manifests; the projection normalises
         ;; them to strings for the canonical encoder)
         capabilities (into {} (map (fn [[k entry]]
                                      [k (em/capability-projection (:capability entry))])
                                    nodes))
         ;; capability-provider bindings: proves which package provides each
         ;; resolved capability (provider package/component root per capability)
         provider-bindings (into {} (map (fn [[k entry]]
                                          [k (capability-providers entry)])
                                        nodes))
         dependencies (mapv #(enriched-dependency % nodes) edges)
         used-schema-ids (into (sorted-set) (mapcat schema-refs-of) (vals nodes))
         schema-roots (into {}
                            (keep (fn [id]
                                    (when (contains? schemas id)
                                      [id (get schemas id)])))
                            used-schema-ids)
         base {:extensions/resolution-version resolution-version
               :extensions/packages packages
               :extensions/capabilities capabilities
               :extensions/capability-providers provider-bindings
               :extensions/dependencies dependencies
               :extensions/schema-roots schema-roots
               :extensions/effect-schema-roots effect-schemas
               :extensions/runtime-profile runtime-profile}
         root (hc/domain-hash resolution-domain-tag base)]
    (assoc base :extensions/resolution-root root)))

;; ── public API ────────────────────────────────────────────────────────────

(defn resolve-requested
  "Resolve requested capability references against an extension-map.

   Requested is a seq of [capability-kind capability-id] vectors.

   Options:
     :runtime-profile — runtime profile map committed into the snapshot
     :sealed?         — require every resolved provider (transitive) to be
                        sealed; unsealed providers fail the resolution
     :schemas         — map of schema id -> schema root for resolving declared
                        schema references (fail-closed)
     :effect-schemas  — map of effect contract id -> root, committed verbatim

   Returns {:valid? true, :resolution <snapshot>}
        or {:valid? false, :violations [...]}."
  [extension-map requested & [opts]]
  (let [{:keys [runtime-profile sealed? schemas effect-schemas]
         :or {sealed? false schemas {} effect-schemas {}}} opts
        requested-keys (mapv normalize-request requested)]
    (if (some nil? requested-keys)
      {:valid? false
       :violations [{:violation/id :extensions/error-invalid-requested-capability
                     :details {:requested requested}}]}
      (let [{:keys [nodes edges]} (closure extension-map requested-keys)
            missing-requested (vec (remove #(contains? nodes %) requested-keys))
            dep-keys (mapv :to edges)
            missing-deps (vec (remove #(contains? nodes %) dep-keys))
            cycle (find-cycle nodes)
            ;; sealed checks apply to every transitive node
            sealed-violations (into []
                                    (mapcat (fn [[k entry]]
                                              (let [providers (:providers entry)]
                                                (when (and sealed?
                                                           (some #(= :unsealed (:sealed %)) providers))
                                                  [{:violation/id :extensions/error-unsealed-in-sealed-run
                                                    :details {:capability k
                                                              :unsealed-providers
                                                              (mapv :package/id
                                                                    (filter #(= :unsealed (:sealed %)) providers))}}])))
                                            nodes))
            ;; ambiguous providers apply to directly requested capabilities
            ambiguous-violations (into []
                                       (mapcat (fn [k]
                                                 (let [entry (get nodes k)
                                                       roots (provider-roots entry)]
                                                   (when (and entry (> (count roots) 1))
                                                     [{:violation/id :extensions/error-ambiguous-provider
                                                       :details {:capability k
                                                                 :package-roots (vec roots)}}])))
                                               requested-keys))
            req-violations (into []
                                 (mapcat (fn [{:keys [from to requirement]}]
                                           (let [entry (get nodes to)]
                                             (if (or (nil? entry)
                                                     (requirement-satisfied? requirement
                                                                             (:capability entry)))
                                               []
                                               [{:violation/id :extensions/error-incompatible-contract-version
                                                 :details {:from from
                                                           :to to
                                                           :requirement (or requirement {})
                                                           :provided-version (:capability/version (:capability entry))
                                                           :provided-contract-version
                                                           (:capability/contract-version (:capability entry))}}])))
                                         edges))
            ;; multiple roots for the same supposedly exact dependency
            dep-root-violations (into []
                                      (mapcat (fn [{:keys [to]}]
                                                (let [entry (get nodes to)]
                                                  (when (and entry (> (count (provider-roots entry)) 1))
                                                    [{:violation/id :extensions/error-multiple-dependency-roots
                                                      :details {:capability to
                                                                :package-roots (vec (provider-roots entry))}}])))
                                              edges))
            schema-ids (into (sorted-set) (mapcat schema-refs-of) (vals nodes))
            unresolved-schemas (vec (remove #(contains? schemas %) schema-ids))
            all-violations (into []
                                 (concat
                                  (map (fn [k]
                                         {:violation/id :extensions/error-missing-capability
                                          :details {:capability k}})
                                       missing-requested)
                                  (map (fn [k]
                                         {:violation/id :extensions/error-missing-dependency
                                          :details {:capability k}})
                                       missing-deps)
                                  (when cycle
                                    [{:violation/id :extensions/error-dependency-cycle
                                      :details {:cycle cycle}}])
                                  sealed-violations
                                  ambiguous-violations
                                  req-violations
                                  dep-root-violations
                                  (when (seq unresolved-schemas)
                                    [{:violation/id :extensions/error-unresolved-schema-root
                                      :details {:unresolved unresolved-schemas}}])))]
        (if (seq all-violations)
          {:valid? false
           :violations all-violations}
          {:valid? true
           :resolution (build-snapshot nodes edges runtime-profile
                                       schemas effect-schemas)})))))

(defn resolution-root
  "Content-addressed root of a resolution snapshot."
  [resolution]
  (:extensions/resolution-root resolution))
