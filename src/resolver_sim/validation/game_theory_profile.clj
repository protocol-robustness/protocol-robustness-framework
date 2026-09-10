(ns resolver-sim.validation.game-theory-profile
  "Game-theory profile completeness validation.

   Given an application's declared strategic validation profile (claims,
   validators, deviation contracts, equilibrium concepts, generators), prove
   that every declared reference resolves and the whole profile is
   self-consistent.  This makes self-extension a first-class, checkable
   capability rather than a side effect of Clojure maps.

   Profile shape:

     {:game-theory/profile :my-protocol/game-theory-v1
      :claims              [{:claim/id ... :deviation-contract-ids [...] ...}]
      :validators          [{...validator descriptor...}]
      :deviation-contracts [{...contract...}]
      :deviation-generators [{:generator/id ... :property ... :evaluate fn}]
      :equilibrium-concepts [{:equilibrium/concept ...}]
      :dependencies        [...]}

   validate-game-theory-profile checks:

     - every claim id resolves (unique);
     - every declared deviation-contract id resolves to the registry;
     - every deviation generator referenced by a contract resolves;
     - every validator id resolves and is a well-formed descriptor
       (including a compulsory epistemic contract);
     - every equilibrium concept resolves;
     - all validation classes are framework-recognised;
     - no duplicate ids across any registry;
     - no dependency cycles (validator dependencies and contract generator
       references);
     - the profile/root recomputes from the committed entries.

   The result includes :profile/root, the recomputed registry roots, and a
   per-section violation list so auditors can see exactly what failed.

   This namespace is pure — no I/O, no DB, no side effects."

  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.validation.classes :as classes]
            [resolver-sim.validation.validator-descriptor :as vd]))

(def game-theory-profile-tag
  "String domain tag for the game-theory-profile root."
  "PRF_GAME_THEORY_PROFILE_V1")

(def game-theory-profile-schema
  "Canonical schema identifier for a game-theory profile."
  :prf/game-theory-profile.v1)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- duplicate-id-violations
  "Detect duplicate ids within a seq of entries keyed by `id-key`.
   Returns a vector of violation maps."
  [entries id-key]
  (let [groups (group-by (fn [e] (get e id-key)) entries)]
    (into []
          (keep (fn [[id es]]
                  (when (> (count es) 1)
                    {:violation/id :violation/duplicate-id
                     :id-key id-key
                     :id id
                     :count (count es)})))
          groups)))

(defn- dependency-edges
  "Extract directed dependency edges from a collection of entries.
   Each entry yields zero or more {:from entry-id :to dep-id} maps.
   `dep-ids-fn` extracts the dep-id vector from an entry."
  [entries id-key dep-ids-fn]
  (into []
        (mapcat (fn [e]
                  (let [id (get e id-key)]
                    (map (fn [d] {:from id :to d})
                         (dep-ids-fn e)))))
        entries))

(defn- canonical-cycle
  "Normalise a closed cycle path (which lists the start node twice, e.g.
   [a b a]) to its canonical rotation: strip the duplicated closing node,
   then rotate so the smallest node (by string form) is first.  This makes
   a→b→a and b→a→b dedupe to the same cycle."
  [cycle]
  (let [v (vec cycle)
        ;; strip trailing duplicate of the first node (closed cycle)
        open (if (and (> (count v) 1) (= (first v) (last v)))
               (subvec v 0 (dec (count v)))
               v)
        smallest (first (sort-by str open))
        start-idx (first (keep-indexed (fn [i x] (when (= x smallest) i)) open))]
    (vec (concat (subvec open start-idx) (subvec open 0 start-idx)))))

(defn- detect-cycles
  "Detect cycles in a directed graph of {:from k :to k} edges using a
   three-color (white/gray/black) DFS.  Returns a vector of distinct cycles
   (each a vector of ids), deduplicated by canonical rotation."
  [edges]
  (let [adj (reduce (fn [acc {:keys [from to]}]
                      (update acc from (fnil conj #{}) to))
                    {} edges)
        nodes (into #{} (mapcat (juxt :from :to)) edges)]
    (loop [todo (vec nodes)
           color {}            ;; :gray | :black
           cycles []]
      (if (empty? todo)
        cycles
        (let [start (peek todo)]
          (if (contains? color start)
            ;; already explored (gray or black) — do not start a new DFS
            (recur (pop todo) color cycles)
            (let [result (loop [stack [[start [start]]]
                                color color
                                cycles cycles]
                           (if (empty? stack)
                             [color cycles]
                             (let [[node path] (peek stack)
                                   rest-stack (pop stack)]
                               (cond
                                 (= :gray (color node))
                                 ;; found a cycle: path from first occurrence of node
                                 (let [start-idx (.indexOf path node)
                                       cycle (subvec path start-idx)]
                                   [color (conj cycles (vec cycle))])

                                 (= :black (color node))
                                 (recur rest-stack color cycles)

                                 :else
                                 (let [succs (adj node #{})
                                       color' (assoc color node :gray)
                                       stack' (into rest-stack
                                                    (map (fn [s] [s (conj path s)]))
                                                    succs)]
                                   (recur stack' color' cycles))))))
                  color (first result)
                  cycles (second result)
                  color' (assoc color start :black)]
              (recur (pop todo) color' cycles))))))))

(defn- distinct-cycles
  "Deduplicate cycles by canonical rotation (same cycle reported once)."
  [cycles]
  (vec (distinct (map canonical-cycle cycles))))

(defn- unresolved-references
  "Return [{:violation/id :violation/unresolved-<kind> :id kw :kind kw}] for
   ids referenced but not present in the resolution map."
  [referenced-ids resolve-map kind]
  (into []
        (keep (fn [id]
                (when-not (contains? resolve-map id)
                  {:violation/id (keyword "violation" (str "unresolved-" (name kind)))
                   :kind kind
                   :id id})))
        (sort referenced-ids)))

;; ---------------------------------------------------------------------------
;; Profile root
;; ---------------------------------------------------------------------------

(defn profile-root
  "Deterministic content root of a game-theory profile over its committed
   entry collections (claims, validators, deviation contracts, equilibrium
   concepts).  Generators are identified by their descriptor (id + property);
   executable functions (:evaluate) are never part of the root."
  [profile]
  (hc/domain-hash game-theory-profile-tag
                  {:game-theory/profile (:game-theory/profile profile)
                   :claims (vec (sort-by :claim/id (:claims profile [])))
                   :validators (vec (sort-by :validator/id (:validators profile [])))
                   :deviation-contracts (vec (sort-by :contract/id
                                                      (:deviation-contracts profile [])))
                   :equilibrium-concepts (vec (sort-by :equilibrium/concept
                                                       (:equilibrium-concepts profile [])))
                   :deviation-generators (vec (sort-by :generator/id
                                                       (mapv #(dissoc % :evaluate)
                                                             (:deviation-generators profile []))))}))

;; ---------------------------------------------------------------------------
;; Main validation
;; ---------------------------------------------------------------------------

(defn validate-game-theory-profile
  "Validate a game-theory profile map for completeness and self-consistency.

   Returns {:ok? bool
            :profile/root <sha256>
            :recomputed-root? bool  ;; supplied root matches recomputed root
            :violations [...]}
   where each violation is {:violation/id kw :id kw :kind kw ...}."
  [profile]
  (let [claims (or (:claims profile) [])
        validators (or (:validators profile) [])
        deviation-contracts (or (:deviation-contracts profile) [])
        deviation-generators (or (:deviation-generators profile) [])
        equilibrium-concepts (or (:equilibrium-concepts profile) [])

        validator-ids (set (map :validator/id validators))
        contract-ids (set (map :contract/id deviation-contracts))
        generator-ids (set (map :generator/id deviation-generators))
        concept-ids (set (map :equilibrium/concept equilibrium-concepts))

        ;; validator descriptor well-formedness (incl. compulsory epistemic contract)
        descriptor-violations (into []
                                    (mapcat (fn [v]
                                              (map (fn [msg]
                                                     {:violation/id :violation/invalid-validator-descriptor
                                                      :validator/id (:validator/id v)
                                                      :message msg})
                                                   (vd/validate-descriptor v))))
                                    validators)

        ;; all validation classes framework-recognised
        recognized-classes (set classes/class-order)
        class-violations (into []
                               (keep (fn [v]
                                       (let [c (:validator/validation-class v)]
                                         (when-not (contains? recognized-classes c)
                                           {:violation/id :violation/unrecognized-validation-class
                                            :validator/id (:validator/id v)
                                            :validation-class c}))))
                               validators)

        ;; duplicate ids within each section
        duplicate-violations (into []
                                   (concat
                                    (duplicate-id-violations claims :claim/id)
                                    (duplicate-id-violations validators :validator/id)
                                    (duplicate-id-violations deviation-contracts :contract/id)
                                    (duplicate-id-violations deviation-generators :generator/id)
                                    (duplicate-id-violations equilibrium-concepts :equilibrium/concept)))

        ;; every deviation-contract id referenced by a claim resolves
        referenced-contract-ids (set (mapcat :deviation-contract-ids claims))
        claim-contract-violations (unresolved-references referenced-contract-ids
                                                         contract-ids
                                                         :deviation-contract)

        ;; every generator referenced by a contract's deviation-generators resolves
        referenced-generator-ids (set (mapcat :deviation-generators deviation-contracts))
        contract-generator-violations (unresolved-references referenced-generator-ids
                                                             generator-ids
                                                             :deviation-generator)

        ;; every validator id referenced by a claim resolves
        referenced-validator-ids (set (mapcat :validator-ids claims))
        claim-validator-violations (unresolved-references referenced-validator-ids
                                                          validator-ids
                                                          :validator)

        ;; every equilibrium concept referenced resolves
        referenced-concept-ids (set (mapcat :equilibrium-concept-ids claims))
        claim-concept-violations (unresolved-references referenced-concept-ids
                                                        concept-ids
                                                        :equilibrium-concept)

        ;; dependency cycle detection over validator dependency edges
        validator-dep-edges (dependency-edges validators
                                              :validator/id
                                              (fn [v]
                                                (map :id (:validator/dependencies v []))))
        cycles (distinct-cycles (detect-cycles validator-dep-edges))
        cycle-violations (mapv (fn [c]
                                 {:violation/id :violation/dependency-cycle
                                  :path (vec c)})
                               cycles)

        all-violations (into []
                             (concat
                              descriptor-violations
                              class-violations
                              duplicate-violations
                              claim-contract-violations
                              contract-generator-violations
                              claim-validator-violations
                              claim-concept-violations
                              cycle-violations))

        recomputed-root (profile-root profile)
        supplied-root (:profile/root profile)
        root-violation (when (and supplied-root
                                  (not= supplied-root recomputed-root))
                         [{:violation/id :violation/profile-root-mismatch
                           :supplied supplied-root
                           :recomputed recomputed-root}])]

    {:ok? (and (empty? all-violations) (empty? root-violation))
     :profile/root recomputed-root
     :supplied-root supplied-root
     :recomputed-root? (or (nil? supplied-root)
                           (= supplied-root recomputed-root))
     :violations (vec (concat all-violations root-violation))}))