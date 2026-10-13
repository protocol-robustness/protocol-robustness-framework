(ns resolver-sim.shortfall.context
  "Canonical Shortfall V1 context and the thin handoff into the existing
   realized-allocation statement pipeline. This namespace establishes the
   candidate universe, deterministic eligibility, and authoritative available
   capital; it neither implements payout execution nor new allocation math."
  (:require [clojure.set :as set]
            [resolver-sim.allocation.context :as allocation-context]
            [resolver-sim.allocation.realized-statement :as statement]
            [resolver-sim.allocation.round-state :as round-state]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.yield.partial-fill :as partial-fill]))

(def ^:const schema-version "prf/shortfall-context.v1")
(def ^:const profile :shortfall-pro-rata.v1)
(def ^:private supported-policy
  {:mode :pro-rata
   :rounding-policy :largest-remainder
   :fail-action-policy {:mode :pro-rata-treatment
                        :deferred-policy :same-ratio
                        :haircut-policy :same-ratio}})

(defn- exact-non-negative! [field value]
  (when-not (and (integer? value) (not (neg? value)))
    (throw (ex-info "shortfall amount must be a non-negative exact integer"
                    {:field field :value value})))
  value)

(defn- reference! [field value]
  (when-not (and (string? value) (re-matches #"sha256:[0-9a-f]{64}" value))
    (throw (ex-info "shortfall reference must be a canonical sha256 reference"
                    {:field field :value value})))
  value)

(defn- canonical-candidates! [candidates]
  (when-not (vector? candidates)
    (throw (ex-info "shortfall candidates must be a vector" {:value candidates})))
  (let [allowed #{:claim/id :requested :eligibility :ineligibility/reason}
        required #{:claim/id :requested :eligibility}
        normalized
        (mapv (fn [candidate]
                (when-not (map? candidate)
                  (throw (ex-info "shortfall candidate must be a map" {:value candidate})))
                (let [keys' (set (keys candidate))]
                  (when (seq (set/difference keys' allowed))
                    (throw (ex-info "unknown shortfall candidate field" {:candidate candidate})))
                  (when (seq (set/difference required keys'))
                    (throw (ex-info "missing shortfall candidate field" {:candidate candidate}))))
                (let [{:claim/keys [id]
                       :ineligibility/keys [reason]
                       :keys [requested eligibility]} candidate]
                  (when-not (and (string? id) (not (empty? id)))
                    (throw (ex-info "shortfall claim id must be non-empty" {:candidate candidate})))
                  (exact-non-negative! :requested requested)
                  (when-not (#{:eligible :ineligible} eligibility)
                    (throw (ex-info "unsupported shortfall eligibility" {:candidate candidate})))
                  (when (and (= eligibility :eligible) (contains? candidate :ineligibility/reason))
                    (throw (ex-info "eligible candidate cannot carry exclusion reason" {:candidate candidate})))
                  (when (and (= eligibility :ineligible)
                             (not (and (keyword? reason) (namespace reason))))
                    (throw (ex-info "ineligible candidate requires namespaced reason" {:candidate candidate})))
                  candidate))
              candidates)
        sorted (vec (sort-by :claim/id normalized))]
    (when (empty? sorted)
      (throw (ex-info "shortfall candidate universe must not be empty" {})))
    (when-not (= sorted normalized)
      (throw (ex-info "shortfall candidates must be in canonical claim-id order" {})))
    (when-not (= (count sorted) (count (set (map :claim/id sorted))))
      (throw (ex-info "duplicate shortfall claim id" {})))
    sorted))

(defn context-preimage
  "The only fields committed by `prf/shortfall-context.v1`.

   `:shortfall/eligible-requested`, demand, and shortage are deterministic
   projections and intentionally not independently committed. Availability is
   an authority-bound input, not a runtime ERC-20 balance observation."
  [context]
  (let [profile (:shortfall/profile context)
        asset-id (:asset/id context)
        unit (:asset/unit context)
        allocation-context-root (:allocation-context-root context)
        candidates (:candidates context)
        availability-source (:availability/source context)
        availability-root (:availability/root context)
        available-amount (:available-amount context)
        allocation-policy (:allocation-policy context)
        temporal-basis-root (:temporal-basis-root context)]
    (cond-> {:schema-version schema-version
             :shortfall/profile profile
             :asset/id asset-id
             :asset/unit unit
             :allocation-context-root allocation-context-root
             :candidates candidates
             :availability/source availability-source
             :availability/root availability-root
             :available-amount available-amount
             :allocation-policy allocation-policy}
      temporal-basis-root (assoc :temporal-basis-root temporal-basis-root))))

(defn context-root [context]
  (hc/domain-hash :shortfall-context (context-preimage context)))

(defn build-context
  "Build a closed Shortfall V1 factual context.

   `:availability/source` declares the authority kind (for example
   `:pool-availability.v2`); `:availability/root` identifies that authority's
   immutable artifact. The generic context intentionally does not reinterpret
   source-specific balance derivation."
  [input]
  (let [profile (:shortfall/profile input)
        asset-id (:asset/id input)
        unit (:asset/unit input)
        allocation-context-root (:allocation-context-root input)
        candidates (:candidates input)
        availability-source (:availability/source input)
        availability-root (:availability/root input)
        available-amount (:available-amount input)
        allocation-policy (:allocation-policy input)
        temporal-basis-root (:temporal-basis-root input)
        allowed #{:shortfall/profile :asset/id :asset/unit :allocation-context-root
                  :candidates :availability/source :availability/root :available-amount
                  :allocation-policy :temporal-basis-root}
        extra (set/difference (set (keys input)) allowed)]
    (when (seq extra)
      (throw (ex-info "unknown shortfall context field" {:extra extra})))
    (when-not (= profile :shortfall-pro-rata.v1)
      (throw (ex-info "unsupported shortfall profile" {:profile profile})))
    (when-not (and (string? asset-id) (not (empty? asset-id)) (= unit :erc20-base-unit))
      (throw (ex-info "Shortfall V1 requires a named ERC-20 base-unit asset" {:asset-id asset-id :unit unit})))
    (reference! :allocation-context-root allocation-context-root)
    (reference! :availability/root availability-root)
    (when-not (keyword? availability-source)
      (throw (ex-info "shortfall availability source must be a keyword" {:value availability-source})))
    (exact-non-negative! :available-amount available-amount)
    (when-not (= allocation-policy supported-policy)
      (throw (ex-info "unsupported Shortfall V1 allocation policy" {:policy allocation-policy})))
    (when temporal-basis-root (reference! :temporal-basis-root temporal-basis-root))
    (let [context {:schema-version schema-version
                   :shortfall/profile profile
                   :asset/id asset-id
                   :asset/unit unit
                   :allocation-context-root allocation-context-root
                   :candidates (canonical-candidates! candidates)
                   :availability/source availability-source
                   :availability/root availability-root
                   :available-amount available-amount
                   :allocation-policy allocation-policy
                   :temporal-basis-root temporal-basis-root}
          context (cond-> context (nil? temporal-basis-root) (dissoc :temporal-basis-root))]
      (assoc context :shortfall/context-root (context-root context)))))

(defn eligible-requested
  "Deterministic eligible request set. Ineligible candidates remain committed in
   the context but cannot enter the realized allocation request map."
  [context]
  (into (sorted-map)
        (keep (fn [{:claim/keys [id] :keys [requested eligibility]}]
                (when (= eligibility :eligible) [id requested])))
        (:candidates context)))

(defn total-eligible-requested [context]
  (reduce + 0 (vals (eligible-requested context))))

(defn shortfall-amount [context]
  (max 0 (- (total-eligible-requested context) (:available-amount context))))

(defn allocation-input
  "Validate the existing allocation context against the complete Shortfall
   universe and return existing realized-allocation inputs. No Shortfall-specific
   allocator or proof branch is created.

   V1 explicitly rejects available capital above eligible demand at handoff:
   that is a no-shortfall/excess-capital case, while the frozen execution profile
   requires `totalAllocated == availableAmount` and zero allocator residual."
  [context ctx]
  (let [requested (eligible-requested context)
        candidate-ids (set (map :claim/id (:candidates context)))
        claimant-ids (set (map :claim/id (:claimants ctx)))
        available (:available-amount context)
        demand (total-eligible-requested context)]
    (when-not (= (:allocation-context-root context) (str "sha256:" (allocation-context/context-hash ctx)))
      (throw (ex-info "shortfall allocation context root mismatch" {})))
    (when-not (= candidate-ids claimant-ids)
      (throw (ex-info "shortfall candidate universe must exactly match allocation claimants"
                      {:candidates candidate-ids :claimants claimant-ids})))
    (when-not (= (set (keys requested))
                 (set (map :claim/id (filter #(= :eligible (:eligibility %)) (:candidates context)))))
      (throw (ex-info "shortfall eligible request set is incomplete" {})))
    (when (> available demand)
      (throw (ex-info "Shortfall V1 does not hand excess capital to the zero-residual allocator profile"
                      {:reason :no-shortfall-excess-capital :available available :eligible-demand demand})))
    {:available available
     :requested requested
     :policy (:allocation-policy context)
     :round-state :result-accepted}))

(defn realize
  "Run the existing deterministic partial-fill and statement builder from a
   validated Shortfall handoff. The returned statement is the unchanged
   `realized-allocation-statement.v1` artifact."
  [context ctx]
  (let [{:keys [available requested policy round-state]} (allocation-input context ctx)
        decision (partial-fill/calculate-fulfillment-pro-rata available requested policy)
        lifecycle (round-state/round-lifecycle {} round-state)
        realized (statement/build-statement {:ctx ctx :decision decision :round-lifecycle lifecycle})]
    {:shortfall/context context
     :shortfall/eligible-requested requested
     :shortfall/total-eligible-requested (total-eligible-requested context)
     :shortfall/unfilled-demand (reduce + 0 (vals (:deferred decision)))
     :allocation/decision decision
     :realized-allocation-statement realized}))
