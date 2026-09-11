(ns resolver-sim.validation.deviation-contract
  "Deviation-contract abstraction for strategic validation.
   A deviation contract declares which actor, prescribed action, deviation set,
   utility model, and parameter domain a strategic claim covers.  Every
   `:validation.class/deviation-resistance` result should reference a contract.
   Contracts are stored in `registered-contracts` and referenced by claims
   in `strategic-claim-catalog` via `:deviation-set-ids`."
  (:require [clojure.set :as set]
            [resolver-sim.validation.strategic-registry :as sr]))

;; ---------------------------------------------------------------------------
;; Contract schema
;; ---------------------------------------------------------------------------

(def deviation-contract-keys
  "Complete set of keys a deviation contract may include."
  #{:contract/id
    :contract/version
    :mechanism
    :actor/type
    :reference-action
    :deviation-generators
    :utility-model
    :parameter-scope
    :epsilon
    :exclusions
    :description})

(defn validate-contract
  "Validate a deviation contract map.  Returns nil if valid, or
   a string describing the first missing required key."
  [contract]
  (cond
    (not (:contract/id contract))
    "missing :contract/id"
    (not (:deviation-generators contract))
    (str "contract " (:contract/id contract) " missing :deviation-generators")
    (not (sequential? (:deviation-generators contract)))
    (str "contract " (:contract/id contract) " :deviation-generators must be sequential")
    :else nil))

;; ---------------------------------------------------------------------------
;; Registered contracts
;; ---------------------------------------------------------------------------

(def partial-fill-split-merge-sybil
  "Covers claim splitting, merging, permutation, and sybil identity
   deviations under pro-rata partial-fill allocation with token-linear
   utility."
  {:contract/id :partial-fill/claimant-split-merge-sybil
   :contract/version 1
   :mechanism :yield/partial-fill
   :actor/type :claimant
   :reference-action :submit-single-claim
   :deviation-generators [:split :merge :permute :sybil :inflate]
   :utility-model :utility/token-linear-v1
   :parameter-scope {:claim-count-max 5 :request-max 20 :liquidity-max 20}
   :epsilon 0
   :exclusions [:cross-workflow-coordination :timing-manipulation]
   :description "Claimant cannot improve allocation by splitting, merging, reordering, or sybilling claims under pro-rata fill."})

(def partial-fill-monotonicity
  "Covers request-monotonicity: increasing a valid claim request does not
   decrease the claimant's own allocation."
  {:contract/id :partial-fill/claimant-monotonicity
   :contract/version 1
   :mechanism :yield/partial-fill
   :actor/type :claimant
   :reference-action :submit-claim-amount
   :deviation-generators [:inflate]
   :utility-model :utility/token-linear-v1
   :parameter-scope {:claim-count-max 5 :request-max 20 :liquidity-max 20}
   :epsilon 0
   :exclusions [:deflation :priority-reclassification]
   :description "Increasing a valid claim request does not decrease the claimant's fill allocation."})

(def registered-contracts
  "Map of contract-id -> contract definition.  This is the framework-builtin
   contract source.  Extension contracts should be composed via
   build-deviation-contract-registry (pure, rooted) rather than by mutating
   this map."
  {:partial-fill/claimant-split-merge-sybil partial-fill-split-merge-sybil
   :partial-fill/claimant-monotonicity partial-fill-monotonicity})

;; ---------------------------------------------------------------------------
;; Deviation-contract registry (rooted, explicit)
;; ---------------------------------------------------------------------------

(def deviation-contract-registry-tag
  "String domain tag for deviation-contract-registry rooting."
  "PRF_DEVIATION_CONTRACT_REGISTRY_V1")

(defn build-deviation-contract-registry
  "Build a rooted deviation-contract registry from sources.

   Each source is either:
     - {:origin kw :entries [contract ...]} (origin defaults to :framework), OR
     - a plain vector of contracts.

   Returns {:entries [...] :by-id {...} :executables {} :root <sha256>}.
   The registry root is what the game-theoretic artifact should commit to —
   not just the resolved deviation IDs — so that 'same claim ID' cannot
   silently resolve against different strategic semantics."
  [& {:keys [sources]
      :or {sources []}}]
  (sr/build-entry-registry :contract/id deviation-contract-registry-tag
                           :sources (or sources [])))

(def default-deviation-contract-registry
  "The framework-builtin deviation-contract registry.  Extension contracts
   compose on top of this via build-deviation-contract-registry."
  (sr/build-entry-registry :contract/id deviation-contract-registry-tag
                           :sources [{:origin :framework
                                      :entries (vals registered-contracts)}]))

(defn resolve-deviation-contract
  "Resolve a deviation contract by id from an explicit registry.
   registry — the map returned by build-deviation-contract-registry, or
   the builtin default-deviation-contract-registry.
   Returns the contract definition, or nil."
  ([registry contract-id]
   (sr/resolve-entry registry contract-id))
  ([contract-id]
   (resolve-deviation-contract default-deviation-contract-registry contract-id)))

;; ---------------------------------------------------------------------------
;; Contract lookup
;; ---------------------------------------------------------------------------

(defn get-contract
  "Look up a deviation contract by id against an explicit registry (or the
   builtin default registry when no registry is supplied).
   Returns nil if not found."
  ([contract-id]
   (get-contract default-deviation-contract-registry contract-id))
  ([registry contract-id]
   (resolve-deviation-contract registry contract-id)))

(defn contracts-for-deviations
  "Return all contracts (from an explicit registry, or the builtin default)
   that cover at least one of the given deviations."
  ([deviations]
   (contracts-for-deviations default-deviation-contract-registry deviations))
  ([registry deviations]
   (let [dev-set (set deviations)]
     (filter (fn [c] (seq (clojure.set/intersection dev-set
                                                    (set (:deviation-generators c)))))
             (vals (:by-id registry))))))

(defn deviations-in-contract
  "Return the set of deviation generators covered by a contract."
  ([contract-id]
   (set (:deviation-generators (get-contract contract-id))))
  ([registry contract-id]
   (set (:deviation-generators (get-contract registry contract-id)))))

(defn contract-generates-deviation?
  "True if the named contract covers `deviation`."
  ([contract-id deviation]
   (contains? (deviations-in-contract contract-id) deviation))
  ([registry contract-id deviation]
   (contains? (deviations-in-contract registry contract-id) deviation)))
