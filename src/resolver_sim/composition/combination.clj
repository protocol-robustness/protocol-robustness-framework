(ns resolver-sim.composition.combination
  "The requested combination: a concrete, declarative set of capability
   instances and edges. It is INPUT to the composition compiler and may be
   invalid. It is deliberately distinct from the compiled executable plan.

   v1 supports a strict sequential pipeline: :combination/nodes is the declared
   order, and :combination/edges (when present) must equal exactly the
   consecutive chain n0→n1→…→nN. Fan-in, fan-out, and any other shape are
   rejected explicitly rather than left undefined."
  (:require [resolver-sim.hash.canonical :as hc]))

(def ^:const combination-version 1)

(def ^:const combination-domain-tag
  "COMPOSITION_COMBINATION_V1")

(def combination-address-fields
  "Held-custody address bindings a combination may carry. :owner/address and
   :parameter/address are required together; :parameter/context is optional.
   These bind the addresses that custody-affecting effects produced by the
   composition must carry (mirroring the held-adjustment attribution model)."
  [:owner/address :parameter/address :parameter/context])

(def combination-verification-fields
  "Permitted keys of a :combination/verification map. Unknown keys are rejected
   so typos do not disappear silently. :obligations is a vector of typed
   assurance obligations resolved and committed by the compiler."
  #{:intermediate-output-committed? :evidence-contract-ref :obligations})

(defn- valid-addresses?
  [addresses]
  (and (map? addresses)
       (every? (fn [k] (contains? addresses k))
               [:owner/address :parameter/address])
       (every? #(or (string? %) (keyword? %)) (vals addresses))))

(defn- address-violations
  "Structural violations for an address map (combination-level or per-node)."
  [addresses]
  (let [unknown (if (map? addresses)
                  (vec (remove (set combination-address-fields) (keys addresses)))
                  [])]
    (cond-> []
      (not (valid-addresses? addresses))
      (conj {:violation/id (if (map? addresses)
                             :violation/missing-or-invalid-combination-address
                             :violation/invalid-combination-addresses)
             :details {:addresses addresses
                       :required [:owner/address :parameter/address]}})
      (seq unknown)
      (conj {:violation/id :violation/unknown-combination-address-key
             :details {:unknown unknown
                       :supported combination-address-fields}}))))

(defn consecutive-edges
  "The canonical consecutive edge chain for an ordered node list."
  [node-ids]
  (vec (map (fn [i]
              {:edge/id (keyword (str "edge-" i))
               :from (nth node-ids i)
               :to (nth node-ids (inc i))
               :value-semantic nil})
            (range (dec (count node-ids))))))

(defn effective-edges
  "The edges of a combination: the declared consecutive chain, or the derived
   consecutive chain when edges are omitted. Explicit edges are normalised to
   the canonical chain for hashing, so declaring the identical chain and
   deriving it produce the same combination root."
  [combination]
  (or (:combination/edges combination)
      (consecutive-edges (mapv :node/id (:combination/nodes combination [])))))

(def node-projection-fields
  "Semantic node fields committed to the combination root. Irrelevant source
   metadata (e.g. :source-metadata) is intentionally excluded so semantic
   roots do not change when it does. :node/addresses is an optional per-node
   held-custody address override."
  [:node/id :capability/ref :capability/version :spec :basis :node/addresses])

(defn- project-node
  [node]
  (select-keys node node-projection-fields))

(defn combination-projection
  "Committed fields of a combination. Node order is preserved (semantically
   significant); map key ordering inside nodes is canonicalised by hashing.
   Edges are normalised to the canonical chain (explicit ≡ derived). v1 does
   NOT commit :combination/adapters: adapters are categorically unsupported in
   v1 (the compiler rejects any adapter-bearing combination), so the field is
   not part of the v1 combination root. The combination-level
   :combination/verification is a semantic compiler input and is committed."
  [combination]
  (-> (select-keys combination
                   [:combination/id
                    :combination/version
                    :combination/nodes
                    :combination/input
                    :combination/expected-output
                    :combination/effect-merge-strategy
                    :combination/verification
                    :combination/addresses])
      (assoc :combination/edges (effective-edges combination))
      (update :combination/nodes (fn [ns] (mapv project-node ns)))))

(defn combination-root
  "Content-addressed root of the requested combination."
  [combination]
  (hc/domain-hash combination-domain-tag (combination-projection combination)))

(defn- valid-value-contract?
  [value]
  (and (map? value)
       (keyword? (:schema-ref value))
       (keyword? (:semantic-type value))))

(defn- verification-violations
  "Structural validation for a :combination/verification value before it is
   interpreted by the compiler. nil is allowed (verification obligations are
   optional at the combination level); a map is validated for permitted keys
   and value types; anything else is rejected so an absent flag can never be
   conflated with a nonsensical value."
  [verification]
  (cond
    (nil? verification)
    []

    (not (map? verification))
    [{:violation/id :violation/invalid-combination-verification
      :details {:verification verification}}]

    :else
    (let [unknown (vec (remove combination-verification-fields (keys verification)))
          intermediate (:intermediate-output-committed? verification)
          evidence-ref (:evidence-contract-ref verification)
          obligations (:obligations verification)]
      (cond-> []
        (seq unknown)
        (conj {:violation/id :violation/unknown-combination-verification-key
               :details {:unknown unknown
                         :supported (vec (sort combination-verification-fields))}})

        (and (contains? verification :intermediate-output-committed?)
             (not (boolean? intermediate)))
        (conj {:violation/id :violation/invalid-combination-verification-flag
               :details {:intermediate-output-committed? intermediate}})

        (and (contains? verification :evidence-contract-ref)
             (not (or (nil? evidence-ref) (keyword? evidence-ref))))
        (conj {:violation/id :violation/invalid-combination-verification-evidence-ref
               :details {:evidence-contract-ref evidence-ref}})

        (and (contains? verification :obligations)
             (not (vector? obligations)))
        (conj {:violation/id :violation/invalid-combination-obligations
               :details {:obligations obligations}})

        (and (vector? obligations)
             (not (every? map? obligations)))
        (conj {:violation/id :violation/invalid-combination-obligation-entry
               :details {:obligations obligations}})))))

(defn validate-combination
  "Validate a requested combination structurally.
   Returns {:valid? bool, :violations [violation-maps]}."
  [combination]
  (let [id (:combination/id combination)
        version (:combination/version combination)
        nodes (:combination/nodes combination [])
        node-ids (mapv :node/id nodes)
        edges (:combination/edges combination)
        input (:combination/input combination)
        expected-output (:combination/expected-output combination)
        addresses (:combination/addresses combination)
        verification (:combination/verification combination)
        chain (consecutive-edges node-ids)
        v (cond-> []
            (not (map? combination))
            (conj {:violation/id :violation/non-map-combination
                   :details {:combination combination}})
            (nil? id)
            (conj {:violation/id :violation/missing-combination-id
                   :details {}})
            (not= combination-version version)
            (conj {:violation/id :violation/invalid-combination-version
                   :details {:version version :supported combination-version}})
            (empty? nodes)
            (conj {:violation/id :violation/empty-combination
                   :details {}})
            (not (every? :node/id nodes))
            (conj {:violation/id :violation/missing-node-id
                   :details {}})
            (not= (count node-ids) (count (set node-ids)))
            (conj {:violation/id :violation/duplicate-node-id
                   :details {:node-ids node-ids}})
            (not (every? (fn [n]
                           (and (vector? (:capability/ref n))
                                (= 2 (count (:capability/ref n)))
                                (every? keyword? (:capability/ref n))))
                         nodes))
            (conj {:violation/id :violation/invalid-capability-reference
                   :details {}})
            (some (fn [n]
                    (let [v (:capability/version n)]
                      (and (not= :any v) (not (pos? (or v 0))))))
                  nodes)
            (conj {:violation/id :violation/invalid-capability-version
                   :details {}})
            (nil? input)
            (conj {:violation/id :violation/missing-combination-input
                   :details {}})
            (and (some? input) (not (valid-value-contract? input)))
            (conj {:violation/id :violation/invalid-combination-input-contract
                   :details {:input input}})
            (nil? expected-output)
            (conj {:violation/id :violation/missing-combination-expected-output
                   :details {}})
            (and (some? expected-output)
                 (not (valid-value-contract? expected-output)))
            (conj {:violation/id :violation/invalid-combination-output-contract
                   :details {:expected-output expected-output}})
            (and edges (not (vector? edges)))
            (conj {:violation/id :violation/invalid-combination-edges
                   :details {:edges edges}})
            (and (vector? edges)
                 (not= (mapv (juxt :from :to) chain)
                       (mapv (juxt :from :to) edges)))
            (conj {:violation/id :violation/invalid-combination-edges
                   :details {:edges edges
                             :expected-chain (mapv (juxt :from :to) chain)}}))
        v (if (some? addresses)
            (into v (address-violations addresses))
            v)
        v (into v (verification-violations verification))
        v (into v (mapcat (fn [n]
                            (if-let [na (:node/addresses n)]
                              (address-violations na)
                              []))
                          nodes))]
    {:valid? (empty? v)
     :violations (vec v)}))
