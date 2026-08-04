(ns resolver-sim.economics.slash-distribution
  "Implementation-independent slash-to-allocation distribution engine.

   Computes weighted base allocations, calculates protocol-defined awards,
   deducts funding from source allocations, and produces a verifiable
   distribution artifact.

   Core constraints:
   - All identifiers (allocation keys, award ids, triggers, roles) are
     opaque. The engine performs arithmetic over them without interpreting
     their semantics.
   - No protocol-specific defaults, destination names, or liability classes.
   - Policy, allocation, award-amount, and funding each carry independent
     :scale values — none are inferred from each other.

   Scaled-share awards (:rate-of-gross):
   - An award amount is a scaled, non-negative proportion of the gross
     amount computed by the public primitive `calculate-scaled-share`:
         amount = floor(gross-amount * rate / scale)
     where rate is the resolved value of the award's :parameter-key.
   - The scale is an explicit, policy-level constant; it is not assumed to
     be 10,000. Valid rates satisfy 0 <= rate <= scale. Rounding is integer
     floor (:floor). At this layer the calculation base is exactly
     gross-amount — no refund, custody, or net-distributable ordering is
     introduced here.
   - Per-award calculation evidence is committed: each rate-derived award
     carries the parameter key, resolved value, scale, rounding, gross
     amount, numerator, rounding remainder, and a classification
     (:zero-rate, :rounded-to-zero, :positive-award, :full-gross-award).
     All rate-derived calculations — including zero-outcome records that
     produce no transfer — are collected in :distribution/calculations and
     summarized in :distribution/summary. Parameter provenance (source root
     and values) is committed at distribution level via
     :distribution/parameter-context.
   - `calculate-scaled-share` uses unbounded integer arithmetic; it does not
     itself assert Solidity-equivalent checked-width semantics. A checked-
     width or mulDiv-equivalent profile is a separate follow-up.

   Boundaries:
   - This namespace proves arithmetic and structural validity.
   - A protocol adapter proves eligibility (challenge succeeded, beneficiary
     is legitimate, parameter matches governance snapshot).
   - An accounting adapter maps abstract allocations to concrete state effects."

  (:require [resolver-sim.economics.schemas :as schemas]
            [resolver-sim.extensions.core :as ext-core]
            [resolver-sim.extensions.execution :as ext-exec]
            [resolver-sim.extensions.registry :as ext-reg]
            [resolver-sim.extensions.resolution :as ext-res]
            [resolver-sim.hash.canonical :as hc]))

(def ^:private policy-schema-version "slash-distribution-policy.v1")
(def ^:private distribution-schema-version "slash-distribution.v1")

;; ── capability dispatch ───────────────────────────────────────────────────

(defn core-extension-map
  "Default extension-map containing the built-in economics capabilities.
   Used whenever a caller does not supply an explicit frozen registry
   snapshot, so existing behaviour is unchanged."
  []
  (ext-reg/register-package (ext-reg/empty-extension-map)
                            ext-core/core-economics-package))

(defn method->capability-key
  "Map a policy :method keyword to a capability registry key for a capability
   kind. Unqualified legacy methods (:rate-of-gross, :weighted, ...) resolve
   to the core package (:prf/<name>); namespaced methods are capability ids
   directly."
  [kind method]
  (when method
    (let [id (if (namespace method)
               method
               (keyword "prf" (name method)))]
      [kind id])))

(defn resolve-method-capability
  "Look up the resolved capability entry for a policy method in an
   extension-map, or nil when the method is not provided."
  [extension-map kind method]
  (when-let [key (method->capability-key kind method)]
    (get extension-map key)))

(defn policy-capability-keys
  "Capability registry keys required by a policy: the allocation method, each
   award amount method, and each award funding method."
  [policy]
  (into []
        (concat
         (when-let [k (method->capability-key :economics/allocation
                                              (:method (:allocation policy)))]
           [k])
         (keep (fn [a]
                 (method->capability-key :economics/award-amount
                                         (get-in a [:amount :method])))
               (:awards policy []))
         (keep (fn [a]
                 (method->capability-key :economics/funding
                                         (get-in a [:funding :method])))
               (:awards policy [])))))

(defn resolve-policy-extensions
  "Resolve the capabilities required by a policy against an extension-map,
   returning the frozen resolution snapshot (or nil on failure, with the
   resolution violations)."
  ([extension-map policy] (resolve-policy-extensions extension-map policy nil))
  ([extension-map policy {:keys [schema-registry runtime-profile]
                          :or {schema-registry schemas/core-schemas}}]
   (ext-res/resolve-requested
    extension-map
    (policy-capability-keys policy)
    {:schemas schema-registry
     :runtime-profile runtime-profile})))

;; ── hash projections ─────────────────────────────────────────────────────

(defn policy-hash-projection
  "Returns the policy map verbatim as its hash projection."
  [policy]
  policy)

(defn policy-hash
  "Compute the SLASH_DISTRIBUTION_POLICY_V1 domain hash of policy."
  [policy]
  (hc/domain-hash :slash-distribution-policy-v1 policy))

(defn distribution-hash-projection
  "Return only the committed fields of a distribution artifact.
   This projection is the subset used to compute :distribution/hash."
  [distribution]
  (select-keys distribution
               [:schema-version
                :distribution/gross-amount
                :distribution/context
                :distribution/policy-root
                :distribution/parameter-context
                :distribution/extension-resolution-root
                :distribution/extension-packages
                :distribution/base-allocations
                :distribution/awards
                :distribution/calculations
                :distribution/summary
                :distribution/final-allocations]))

(defn distribution-hash
  "Compute the SLASH_DISTRIBUTION_V1 domain hash of the committed
   subset of a distribution artifact."
  [distribution]
  (hc/domain-hash :slash-distribution-v1
                  (distribution-hash-projection distribution)))

;; ── policy validation ────────────────────────────────────────────────────

(defn- supported-award-amount-method?
  [extension-map method]
  (some? (resolve-method-capability extension-map :economics/award-amount method)))

(defn- supported-allocation-method?
  [extension-map method]
  (some? (resolve-method-capability extension-map :economics/allocation method)))

(defn- supported-funding-method?
  [extension-map method]
  (some? (resolve-method-capability extension-map :economics/funding method)))

(defn- supported-rounding?
  [rounding]
  (= :floor rounding))

(defn- bips-sum-to-scale?
  [weights scale]
  (= scale (reduce + 0 (vals weights))))

(defn- validate-policy-award
  [extension-map {:keys [allocation]} award]
  (let [v []
        v (if (:award/id award) v
              (conj v {:violation/id :violation/missing-award-id
                       :details {:award award}}))

        amount-spec (:amount award)
        v (if amount-spec v
              (conj v {:violation/id :violation/missing-amount-spec
                       :details {:award/id (:award/id award)}}))
        v (if amount-spec
            (let [method (:method amount-spec)]
              (if (supported-award-amount-method? extension-map method)
                (let [scale (:scale amount-spec)
                      builtin? (or (= :rate-of-gross method)
                                   (= :resolved-amount method))]
                  (cond-> v
                    (and builtin?
                         (or (nil? scale) (not (integer? scale)) (not (pos? scale))))
                    (conj {:violation/id :violation/invalid-award-scale
                           :details {:award/id (:award/id award)
                                     :scale scale}})
                    (and (= :rate-of-gross method)
                         (not= :floor (:rounding amount-spec)))
                    (conj {:violation/id :violation/unsupported-rounding
                           :details {:award/id (:award/id award)
                                     :rounding (:rounding amount-spec)
                                     :supported [:floor]}})
                    (and (= :rate-of-gross method)
                         (nil? (:parameter-key amount-spec)))
                    (conj {:violation/id :violation/missing-parameter-key
                           :details {:award/id (:award/id award)}})))
                (conj v {:violation/id :violation/unsupported-amount-method
                         :details {:award/id (:award/id award)
                                   :method method
                                   :supported [:rate-of-gross]}})))
            v)

        elig (:eligibility award)
        v (if (nil? elig)
            (conj v {:violation/id :violation/missing-eligibility
                     :details {:award/id (:award/id award)}})
            (let [v (if (nil? (:trigger elig))
                      (conj v {:violation/id :violation/missing-trigger
                               :details {:award/id (:award/id award)}})
                      v)
                  v (if (nil? (:beneficiary-role elig))
                      (conj v {:violation/id :violation/missing-beneficiary-role
                               :details {:award/id (:award/id award)}})
                      v)
                  v (if (nil? (:requires-evidence-reference? elig))
                      (conj v {:violation/id :violation/missing-evidence-requirement
                               :details {:award/id (:award/id award)}})
                      v)]
              v))

        funding-spec (:funding award)
        v (if funding-spec v
              (conj v {:violation/id :violation/missing-funding-spec
                       :details {:award/id (:award/id award)}}))
        v (if funding-spec
            (let [method (:method funding-spec)]
              (if (supported-funding-method? extension-map method)
                (let [f-scale (:scale funding-spec)
                      f-weights (:weights funding-spec)
                      f-remainder (:remainder-to funding-spec)
                      base-keys (set (keys (:weights allocation)))
                      funding-keys (set (keys f-weights))
                      unknown (seq (remove base-keys funding-keys))
                      weighted? (= :weighted-deduction method)]
                  (cond-> v
                    (and weighted?
                         (or (nil? f-scale) (not (integer? f-scale)) (not (pos? f-scale))))
                    (conj {:violation/id :violation/invalid-funding-scale
                           :details {:award/id (:award/id award)
                                     :scale f-scale}})
                    (and weighted?
                         (not (bips-sum-to-scale? f-weights f-scale)))
                    (conj {:violation/id :violation/funding-weights-do-not-sum-to-scale
                           :details {:award/id (:award/id award)
                                     :weights f-weights
                                     :scale f-scale
                                     :sum (reduce + 0 (vals f-weights))}})
                    (and weighted? (nil? f-remainder))
                    (conj {:violation/id :violation/missing-funding-remainder
                           :details {:award/id (:award/id award)}})
                    (and weighted?
                         (some? f-remainder) (not (contains? f-weights f-remainder)))
                    (conj {:violation/id :violation/invalid-funding-remainder
                           :details {:award/id (:award/id award)
                                     :remainder-to f-remainder
                                     :available-keys (vec (keys f-weights))}})
                    (and weighted? (boolean unknown))
                    (conj {:violation/id :violation/unknown-funding-source
                           :details {:award/id (:award/id award)
                                     :unknown-sources (vec unknown)
                                     :base-allocation-sources (vec base-keys)}})))
                (conj v {:violation/id :violation/unsupported-funding-method
                         :details {:award/id (:award/id award)
                                   :method method
                                   :supported [:weighted-deduction]}})))
            v)

        settlement-spec (:settlement award)
        v (if settlement-spec v
              (conj v {:violation/id :violation/missing-settlement-spec
                       :details {:award/id (:award/id award)}}))
        v (if settlement-spec
            (cond-> v
              (nil? (:allocation-id settlement-spec))
              (conj {:violation/id :violation/missing-settlement-allocation
                     :details {:award/id (:award/id award)}})
              (nil? (:obligation-kind settlement-spec))
              (conj {:violation/id :violation/missing-obligation-kind
                     :details {:award/id (:award/id award)}}))
            v)]
    v))

(defn validate-policy
  "Validate a slash-distribution-policy.v1 map structurally.
   Returns {:valid? bool, :violations [violation-maps]}.

   Checks cover schema shape, identifier uniqueness, weight sums,
   and structural consistency. No defaults are filled in — every
   economically meaningful value must be supplied by the policy.

   Two-arity form accepts an extension-map so extension-backed methods are
   validated against the registered capabilities; the one-arity form uses the
   built-in core package."
  ([policy]
   (validate-policy policy (core-extension-map)))
  ([policy extension-map]
   (let [v []
         v (if (= policy-schema-version (:schema-version policy)) v
               (conj v {:violation/id :violation/invalid-policy-schema-version
                        :details {:expected policy-schema-version
                                  :received (:schema-version policy)}}))
         allocation (:allocation policy)
         v (if allocation v
               (conj v {:violation/id :violation/missing-allocation}))
         v (if allocation
             (let [method (:method allocation)]
               (if (supported-allocation-method? extension-map method)
                 (let [scale (:scale allocation)
                       weights (:weights allocation)
                       remainder-to (:remainder-to allocation)
                       weighted? (= :weighted method)]
                   (cond-> v
                     (and weighted?
                          (or (nil? scale) (not (integer? scale)) (not (pos? scale))))
                     (conj {:violation/id :violation/invalid-allocation-scale
                            :details {:scale scale}})
                     (and weighted? (nil? weights))
                     (conj {:violation/id :violation/missing-allocation-weights})
                     (and weighted? weights (not (bips-sum-to-scale? weights scale)))
                     (conj {:violation/id :violation/allocation-weights-sum-mismatch
                            :details {:weights weights
                                      :scale scale
                                      :sum (reduce + 0 (vals weights))}})
                     (and weighted? (nil? remainder-to))
                     (conj {:violation/id :violation/missing-allocation-remainder})
                     (and weighted? remainder-to
                          (not (contains? (set (keys weights)) remainder-to)))
                     (conj {:violation/id :violation/invalid-allocation-remainder
                            :details {:remainder-to remainder-to
                                      :available-keys (vec (keys weights))}})))
                 (conj v {:violation/id :violation/unsupported-allocation-method
                          :details {:method method
                                    :supported [:weighted]}})))
             v)
         award-ids (mapv :award/id (:awards policy))
         v (let [dups (->> award-ids frequencies (keep (fn [[id n]] (when (> n 1) id))) vec)]
             (if (seq dups)
               (conj v {:violation/id :violation/duplicate-award-id
                        :details {:duplicate-ids dups}})
               v))
         v (reduce (fn [vs award]
                     (into vs (validate-policy-award extension-map policy award)))
                   v
                   (:awards policy))]
     {:valid? (empty? v)
      :violations (vec v)})))

;; ── scaled-share primitive ───────────────────────────────────────────────

(defn calculate-scaled-share
  "Compute a scaled, non-negative proportion of a gross amount.

   Given an explicit scale and :floor rounding, computes

     amount   = floor(gross-amount * rate / scale)
     numerator = gross-amount * rate
     rounding-remainder = numerator - amount * scale

   and classifies the outcome:

     :zero-rate        — rate is 0
     :rounded-to-zero  — rate > 0 but the amount rounds down to 0
     :positive-award   — 0 < amount < gross-amount
     :full-gross-award — amount = gross-amount (rate = scale)

   Valid domain: gross-amount is a non-negative integer, rate is a
   non-negative integer with rate <= scale, scale is a positive integer,
   and rounding is :floor. Returns nil outside the valid domain; the
   distribution engine reports specific violations instead of classifying.

   The primitive uses unbounded integer arithmetic and does not itself
   assert Solidity-equivalent checked-width semantics. A checked-width or
   mulDiv-equivalent profile is a separate follow-up."
  [{:keys [gross-amount rate scale rounding]}]
  (when (and (integer? gross-amount)
             (not (neg? gross-amount))
             (integer? rate)
             (not (neg? rate))
             (integer? scale)
             (pos? scale)
             (<= rate scale)
             (= :floor rounding))
    (let [numerator (* gross-amount rate)
          amount (quot numerator scale)
          rounding-remainder (- numerator (* amount scale))
          classification (cond
                           (zero? rate) :zero-rate
                           (zero? amount) :rounded-to-zero
                           (= amount gross-amount) :full-gross-award
                           :else :positive-award)]
      {:gross-amount gross-amount
       :rate rate
       :scale scale
       :rounding rounding
       :numerator numerator
       :amount amount
       :rounding-remainder rounding-remainder
       :classification classification})))

(defn- rate-of-gross-calculation
  "Return the `calculate-scaled-share` record for a :rate-of-gross amount
   spec resolved against the parameter values, or nil when the parameter
   is missing or the resolved value is outside the valid domain."
  [gross-amount amount-spec param-values]
  (let [resolved (get param-values (:parameter-key amount-spec))]
    (when (some? resolved)
      (calculate-scaled-share {:gross-amount gross-amount
                               :rate resolved
                               :scale (:scale amount-spec)
                               :rounding (:rounding amount-spec)}))))

(defn- award-calculation
  "Full calculation record bound to a rate-derived award, or nil when the
   award does not use :rate-of-gross.

   Binds the resolved parameter and the exact arithmetic inputs so an
   auditor can recompute the award locally without re-reading the policy."
  [award-id gross-amount amount-spec param-values]
  (when (= :rate-of-gross (:method amount-spec))
    (when-let [calc (rate-of-gross-calculation gross-amount amount-spec param-values)]
      {:award/id award-id
       :parameter-key (:parameter-key amount-spec)
       :parameter-value (:rate calc)
       :scale (:scale calc)
       :rounding (:rounding calc)
       :gross-amount (:gross-amount calc)
       :numerator (:numerator calc)
       :amount (:amount calc)
       :rounding-remainder (:rounding-remainder calc)
       :calculation-classification (:classification calc)})))

(defn- build-rate-derived-summary
  "Aggregate summary derived exclusively from the rate-derived calculation
   records in :distribution/calculations.

   Effective aggregate rate is a derived ratio
     total-rate-derived-award-amount / total eligible base
   and is intentionally not stored as a rounded value; it is recomputable
   from the recorded totals."
  [calculations]
  (let [classifications (frequencies (map :calculation-classification calculations))
        positive (filter #(pos? (:amount %)) calculations)]
    {:rate-derived-award-count (count calculations)
     :positive-rate-derived-award-count (count positive)
     :zero-rate-count (get classifications :zero-rate 0)
     :rounded-to-zero-count (get classifications :rounded-to-zero 0)
     :full-gross-award-count (get classifications :full-gross-award 0)
     :total-rate-derived-award-amount (reduce + 0 (map :amount calculations))
     :total-rounding-remainder (reduce + 0 (map :rounding-remainder calculations))
     :amount-by-parameter-key (reduce (fn [acc c]
                                        (update acc (:parameter-key c) (fnil + 0) (:amount c)))
                                      {} calculations)}))

;; ── award calculation ────────────────────────────────────────────────────
;; Raw built-in arithmetic. The dispatched computation below resolves the
;; policy method to a registered capability and invokes its entrypoint, so
;; built-ins and extensions go through the same path.

(defn- weighted-allocation
  "Weighted base allocation with floor rounding and a single remainder lump
   assigned to remainder-to."
  [gross-amount {:keys [scale weights remainder-to]}]
  (let [quotients (into {}
                        (map (fn [[id weight]]
                               [id (quot (* gross-amount weight) scale)]))
                        weights)
        allocated (reduce + 0 (vals quotients))
        remainder (- gross-amount allocated)
        base (if (and (pos? remainder) remainder-to)
               (update quotients remainder-to (fnil + 0) remainder)
               quotients)]
    base))

(defn- weighted-funding
  "Weighted per-source funding deductions for a single award."
  [award-amount {:keys [scale weights remainder-to]}]
  (let [quotients (into {}
                        (map (fn [[id weight]]
                               [id (quot (* award-amount weight) scale)]))
                        weights)
        allocated (reduce + 0 (vals quotients))
        remainder (- award-amount allocated)
        funding (if (and (pos? remainder) remainder-to
                         (contains? weights remainder-to))
                  (update quotients remainder-to (fnil + 0) remainder)
                  quotients)]
    funding))

;; ── public capability adapters (core-package entrypoints) ────────────────
;; Each conforms to the uniform invocation contract for its capability kind:
;; a function of a kind-specific input map returning a structured result map.

(defn rate-of-gross-award-amount
  "Capability implementation for [:economics/award-amount :prf/rate-of-gross].
   Context: {:gross-amount N :amount-spec m :param-values {...}}
   Result:  {:amount N|nil :calculation <calc-or-nil>}"
  [{:keys [gross-amount amount-spec param-values]}]
  (let [calculation (rate-of-gross-calculation gross-amount amount-spec param-values)]
    {:amount (:amount calculation)
     :calculation calculation}))

(defn resolved-award-amount
  "Capability implementation for [:economics/award-amount :prf/resolved-amount].
   Context: {:resolved-award m}
   Result:  {:amount N|nil :calculation nil}"
  [{:keys [resolved-award]}]
  (let [resolved (:award/amount resolved-award)]
    {:amount (when (and (integer? resolved) (not (neg? resolved))) resolved)
     :calculation nil}))

(defn weighted-base-allocation
  "Capability implementation for [:economics/allocation :prf/weighted].
   Context: {:gross-amount N :allocation-spec m}
   Result:  {:base-allocations {id amount}}"
  [{:keys [gross-amount allocation-spec]}]
  {:base-allocations (weighted-allocation gross-amount allocation-spec)})

(defn weighted-funding-deduction
  "Capability implementation for [:economics/funding :prf/weighted-deduction].
   Context: {:award-amount N :funding-spec m}
   Result:  {:funding {id amount}}"
  [{:keys [award-amount funding-spec]}]
  {:funding (weighted-funding award-amount funding-spec)})

;; ── dispatched computation (registry-backed) ─────────────────────────────

(defn- compute-allocation
  "Compute base allocations by dispatching the allocation method through the
   extension-map. Returns the base-allocations map."
  [extension-map gross-amount allocation-spec]
  (let [entry (resolve-method-capability extension-map :economics/allocation
                                         (:method allocation-spec))
        result (ext-exec/invoke-capability entry
                                           {:gross-amount gross-amount
                                            :allocation-spec allocation-spec})]
    (:base-allocations result)))

(defn- compute-award-amount
  "Compute a single award amount by dispatching the award-amount method
   through the extension-map. Returns the amount or nil."
  [extension-map gross-amount amount-spec param-values resolved-award]
  (let [entry (resolve-method-capability extension-map :economics/award-amount
                                         (:method amount-spec))
        result (ext-exec/invoke-capability entry
                                           {:gross-amount gross-amount
                                            :amount-spec amount-spec
                                            :param-values param-values
                                            :resolved-award resolved-award})]
    (:amount result)))

(defn- compute-award-funding
  "Compute per-source funding deductions by dispatching the funding method
   through the extension-map. Returns the funding map."
  [extension-map award-amount funding-spec]
  (let [entry (resolve-method-capability extension-map :economics/funding
                                         (:method funding-spec))
        result (ext-exec/invoke-capability entry
                                           {:award-amount award-amount
                                            :funding-spec funding-spec})]
    (:funding result)))

;; ── distribution builder ─────────────────────────────────────────────────

(defn- resolve-award
  "Process a single resolved award against the matching policy award spec
   and gross amount.

   Returns {:award <award-entry> :calculation <calc-or-nil>
            :violations [...]}
   or {:award nil :calculation <calc-or-nil> :violations [...]} on
   validation failure or zero amount. The :calculation record is preserved
   for zero outcomes so they remain auditable without producing transfers."
  [extension-map gross-amount policy-award resolved-award param-values]
  (let [award-id (:award/id resolved-award)
        v []
        amount-spec (:amount policy-award)
        award-amount (compute-award-amount extension-map gross-amount
                                           amount-spec param-values resolved-award)
        calculation (award-calculation award-id gross-amount amount-spec param-values)]
    (cond
      (nil? award-amount)
      {:award nil :calculation calculation
       :violations [{:violation/id :violation/invalid-award-amount
                     :details {:award/id award-id
                               :method (:method amount-spec)}}]}

      (zero? award-amount)
      {:award nil :calculation calculation :violations v}

      :else
      (let [elig (:eligibility resolved-award)
            policy-elig (:eligibility policy-award)
            v (if (= (:trigger policy-elig) (:trigger elig)) v
                  (conj v {:violation/id :violation/trigger-mismatch
                           :details {:award/id award-id
                                     :expected (:trigger policy-elig)
                                     :received (:trigger elig)}}))
            v (if (= (:beneficiary-role policy-elig)
                     (get-in resolved-award [:beneficiary :participant/role])) v
                  (conj v {:violation/id :violation/beneficiary-role-mismatch
                           :details {:award/id award-id
                                     :expected (:beneficiary-role policy-elig)
                                     :received (get-in resolved-award [:beneficiary :participant/role])}}))
            v (if (and (:requires-evidence-reference? policy-elig)
                       (nil? (:evidence-reference elig)))
                (conj v {:violation/id :violation/missing-eligibility-reference
                         :details {:award/id award-id}})
                v)
            v (if (get-in resolved-award [:beneficiary :participant/id]) v
                  (conj v {:violation/id :violation/missing-beneficiary
                           :details {:award/id award-id}}))]
        (if (seq v)
          {:award nil :calculation calculation :violations v}
          (let [funding-spec (:funding policy-award)
                settlement-spec (:settlement policy-award)
                funding (compute-award-funding extension-map award-amount funding-spec)]
            {:award (cond-> {:award/id award-id
                             :award/amount award-amount
                             :eligibility {:trigger (:trigger elig)
                                           :evidence-reference (:evidence-reference elig)}
                             :beneficiary (:beneficiary resolved-award)
                             :funding funding
                             :settlement {:allocation-id (:allocation-id settlement-spec)}}
                      calculation (assoc :calculation calculation))
             :calculation calculation
             :violations []}))))))

(defn build-slash-distribution
  "Build a slash-distribution.v1 artifact from gross amount, policy,
   resolved parameters, and resolved awards.

   Input:
     {:gross-amount   <non-negative-int>
      :policy         <policy-map>
      :policy-root    <optional-string>
      :parameter-context
        {:source-root <optional-string>
         :values      {<param-key> <int>}}
      :resolved-awards [<resolved-award> ...]
      :extension-map  <optional frozen extension-map>
      :context        <any-map>}

   The optional :extension-map supplies the capability registry used for
   method dispatch and validation; it defaults to the built-in core package.
   Pass a frozen snapshot (e.g. from resolver-sim.extensions.registry/freeze!)
   so the resolution set is committed and reproducible.

   Returns:
     {:status :valid
      :distribution <artifact-map>}
     | {:status :invalid
        :violations [<violation-maps>]}

   Where <artifact-map> conforms to slash-distribution.v1."
  [{:keys [gross-amount policy policy-root parameter-context resolved-awards
           extension-map schema-registry runtime-profile context]}]
  (let [extension-map (or extension-map (core-extension-map))
        schema-registry (or schema-registry schemas/core-schemas)
        v []
        v (if (and (integer? gross-amount) (not (neg? gross-amount))) v
              (conj v {:violation/id :violation/invalid-gross-amount
                       :details {:gross-amount gross-amount}}))
        ;; validate policy
        {:keys [valid? violations] :as policy-result}
        (when policy (validate-policy policy extension-map))
        v (if policy
            (if valid?
              v
              (into v violations))
            (conj v {:violation/id :violation/missing-policy}))
        ;; policy-root binding
        computed-policy-root (when policy (policy-hash policy))
        v (if (and policy policy-root)
            (if (= policy-root computed-policy-root)
              v
              (conj v {:violation/id :violation/policy-root-mismatch
                       :details {:supplied policy-root
                                 :computed computed-policy-root}}))
            v)
        effective-policy-root (or policy-root computed-policy-root)
        ;; validate resolved awards structurally
        resolved-ids (mapv :award/id resolved-awards)
        v (let [dups (->> resolved-ids frequencies (keep (fn [[id n]] (when (> n 1) id))) vec)]
            (if (seq dups)
              (conj v {:violation/id :violation/duplicate-resolved-award-id
                       :details {:duplicate-ids dups}})
              v))
        policy-award-ids (set (mapv :award/id (:awards policy)))
        v (let [unknown (seq (remove policy-award-ids resolved-ids))]
            (if unknown
              (conj v {:violation/id :violation/unknown-award-id
                       :details {:unknown-ids (vec unknown)
                                 :known-ids (vec policy-award-ids)}})
              v))
        ;; parameter validation for rate-of-gross awards
        param-values (:values parameter-context {})
        v (reduce (fn [vs ra]
                    (let [award-id (:award/id ra)
                          policy-award (some #(when (= award-id (:award/id %)) %)
                                             (:awards policy))
                          amount-spec (:amount policy-award)]
                      (if (and amount-spec (= :rate-of-gross (:method amount-spec)))
                        (let [param-key (:parameter-key amount-spec)
                              scale (:scale amount-spec)
                              resolved (get param-values param-key)]
                          (cond-> vs
                            (nil? resolved)
                            (conj {:violation/id :violation/missing-parameter
                                   :details {:award/id award-id
                                             :parameter-key param-key}})
                            (and resolved
                                 (or (not (integer? resolved))
                                     (neg? resolved)))
                            (conj {:violation/id :violation/invalid-parameter-value
                                   :details {:award/id award-id
                                             :parameter-key param-key
                                             :value resolved}})
                            (and resolved
                                 (integer? resolved)
                                 (pos? scale)
                                 (> resolved scale))
                            (conj {:violation/id :violation/rate-out-of-range
                                   :details {:award/id award-id
                                             :parameter-key param-key
                                             :value resolved
                                             :minimum 0
                                             :maximum scale}})))
                        vs)))
                  v resolved-awards)
        ;; extension resolution for the capabilities the policy requires
        extension-resolution (when policy
                               (resolve-policy-extensions
                                extension-map policy
                                {:schema-registry schema-registry
                                 :runtime-profile runtime-profile}))
        v (if (and policy extension-resolution
                   (not (:valid? extension-resolution)))
            (conj v {:violation/id :violation/extension-resolution-failed
                     :details {:violations (:violations extension-resolution)}})
            v)]
    (if (seq v)
      {:status :invalid :violations v}
      (let [allocation-spec (:allocation policy)
            base (compute-allocation extension-map gross-amount allocation-spec)
                ;; resolve each award
            resolved (mapv (fn [ra]
                             (let [pa (some #(when (= (:award/id ra) (:award/id %)) %)
                                            (:awards policy))]
                               (resolve-award extension-map gross-amount pa ra param-values)))
                           resolved-awards)
            award-violations (mapcat :violations resolved)]
        (if (seq award-violations)
          {:status :invalid :violations award-violations}
          (let [active-awards (keep :award resolved)
                sorted-awards (vec (sort-by :award/id active-awards))
                    ;; every rate-derived calculation, including zero outcomes
                calculations (vec (sort-by :award/id (keep :calculation resolved)))
                summary (build-rate-derived-summary calculations)
                    ;; aggregate deductions by source
                aggregate-deductions (reduce (fn [acc award]
                                               (let [funding (:funding award)]
                                                 (merge-with + acc funding)))
                                             {}
                                             sorted-awards)
                ;; validate no source overdrawn
                overdraws (keep (fn [[source deduction]]
                                  (let [base-val (get base source 0)]
                                    (when (> deduction base-val)
                                      {:violation/id :violation/source-overdrawn
                                       :details {:source-id source
                                                 :available base-val
                                                 :required deduction}})))
                                aggregate-deductions)]
            (if (seq overdraws)
              {:status :invalid :violations (vec overdraws)}
              (let [;; aggregate settlement inflows by destination
                    settlement-inflows (reduce (fn [acc award]
                                                 (let [dest (get-in award [:settlement :allocation-id])
                                                       amt (:award/amount award)]
                                                   (if dest
                                                     (update acc dest (fnil + 0) amt)
                                                     acc)))
                                               {}
                                               sorted-awards)
                    ;; compute final allocations
                    all-ids (into (keys base)
                                  (into (keys aggregate-deductions)
                                        (keys settlement-inflows)))
                    final-alloc (into {}
                                      (map (fn [id]
                                             (let [b (get base id 0)
                                                   d (get aggregate-deductions id 0)
                                                   s (get settlement-inflows id 0)
                                                   f (+ b (- d) s)]
                                               [id f]))
                                           all-ids))
                    ;; check non-negative
                    negatives (keep (fn [[id v]]
                                      (when (neg? v)
                                        {:violation/id :violation/negative-final-allocation
                                         :details {:allocation-id id :value v}}))
                                    final-alloc)]
                (if (seq negatives)
                  {:status :invalid :violations (vec negatives)}
                  (let [final-total (reduce + 0 (vals final-alloc))]
                    (if (not= final-total gross-amount)
                      {:status :invalid
                       :violations [{:violation/id :violation/conservation-violation
                                     :details {:gross-amount gross-amount
                                               :final-total final-total
                                               :categories {:base-total (reduce + 0 (vals base))
                                                            :deduction-total (reduce + 0 (vals aggregate-deductions))
                                                            :settlement-total (reduce + 0 (vals settlement-inflows))
                                                            :award-total (reduce + 0 (map :award/amount sorted-awards))
                                                            :retained 0}}}]}
                      (let [distribution
                            {:schema-version distribution-schema-version
                             :distribution/gross-amount gross-amount
                             :distribution/context context
                             :distribution/policy-root effective-policy-root
                             :distribution/parameter-context parameter-context
                             :distribution/extension-resolution-root
                             (get-in extension-resolution
                                     [:resolution :extensions/resolution-root])
                             :distribution/extension-packages
                             (get-in extension-resolution
                                     [:resolution :extensions/packages])
                             :distribution/base-allocations base
                             :distribution/awards sorted-awards
                             :distribution/calculations calculations
                             :distribution/summary summary
                             :distribution/final-allocations final-alloc}
                            distribution (assoc distribution
                                                :distribution/hash
                                                (distribution-hash distribution))]
                        {:status :valid
                         :distribution distribution}))))))))))))

;; ── independent verifier ─────────────────────────────────────────────────

(defn- verify-recomputed-against-stored
  "Compare independently recomputed values against stored distribution values.
   Returns accumulated violation vector for any mismatches."
  [v desc stored recomputed]
  (if (= stored recomputed)
    v
    (let [details {:field desc :stored stored :recomputed recomputed}
          details (if (sequential? stored)
                    (assoc details :index
                           (first (keep-indexed (fn [i s]
                                                  (when (not= s (nth recomputed i nil))
                                                    i))
                                                stored)))
                    details)]
      (conj v {:violation/id :violation/recomputation-mismatch
               :details details}))))

(defn- independent-award
  "Recompute award amount and funding from policy award spec and parameters.
   For :rate-of-gross, the amount is recomputed from the parameter.
   For :resolved-amount, the stored award amount is accepted as given.

   Returns {:award-amount <int>
            :calculation <calc-or-nil>
            :funding <map>
            :violations [...]}
   or {:award-amount nil :violations [...]} on error."
  [extension-map gross-amount policy-award param-values stored-award]
  (let [amount-spec (:amount policy-award)]
    (case (:method amount-spec)
      :rate-of-gross
      (let [param-key (:parameter-key amount-spec)
            resolved (get param-values param-key)]
        (if (nil? resolved)
          {:award-amount nil
           :violations [{:violation/id :violation/missing-parameter
                         :details {:award/id (:award/id policy-award)
                                   :parameter-key param-key}}]}
          (let [award-amount (compute-award-amount extension-map gross-amount
                                                   amount-spec param-values nil)
                calculation (award-calculation (:award/id policy-award)
                                               gross-amount amount-spec param-values)]
            (if (nil? award-amount)
              {:award-amount nil
               :violations [{:violation/id :violation/invalid-parameter-value
                             :details {:award/id (:award/id policy-award)
                                       :parameter-key param-key
                                       :value resolved
                                       :reason "outside valid scaled-share domain"}}]}
              ;; funding is recomputed from the award amount
              {:award-amount award-amount
               :calculation calculation
               :funding (compute-award-funding extension-map award-amount
                                               (:funding policy-award))
               :violations []}))))
      :resolved-amount
      (let [award-amount (:award/amount stored-award)]
        (if (and (integer? award-amount) (not (neg? award-amount)))
          {:award-amount award-amount
           :funding (compute-award-funding extension-map award-amount
                                           (:funding policy-award))
           :violations []}
          {:award-amount nil
           :violations [{:violation/id :violation/invalid-parameter-value
                         :details {:award/id (:award/id policy-award)
                                   :reason "stored award-amount is not a non-negative integer"
                                   :value award-amount}}]}))
      ;; extension-backed or unsupported methods dispatch through the registry
      (let [award-amount (compute-award-amount extension-map gross-amount
                                               amount-spec param-values stored-award)]
        (if (nil? award-amount)
          {:award-amount nil
           :violations [{:violation/id :violation/unsupported-amount-method
                         :details {:award/id (:award/id policy-award)
                                   :method (:method amount-spec)}}]}
          {:award-amount award-amount
           :funding (compute-award-funding extension-map award-amount
                                           (:funding policy-award))
           :violations []})))))

(defn- independent-distribution
  "Independently compute a full distribution from policy and parameters.

   For :rate-of-gross awards, the amount is recomputed from the parameter.
   For :resolved-amount awards, the stored award amount from the distribution
   artifact is accepted as given (it was resolved externally).

   stored-awards — the :distribution/awards vector from the artifact (optional,
   required only when any policy award uses :resolved-amount).

   Returns {:awards [<award-map> ...] :base <map> :deductions <map>
            :settlements <map> :final <map> :violations [...]}."
  [extension-map gross-amount policy param-values & [stored-awards]]
  (let [allocation-spec (:allocation policy)
        base (compute-allocation extension-map gross-amount allocation-spec)
        stored-by-id (when stored-awards
                       (into {} (map (fn [a] [(:award/id a) a]) stored-awards)))
        processed (mapv (fn [policy-award]
                          (let [award-id (:award/id policy-award)
                                stored (get stored-by-id award-id)
                                {:keys [award-amount calculation funding violations]}
                                (independent-award extension-map gross-amount
                                                   policy-award param-values stored)]
                            (when (and (some? award-amount) (pos? award-amount))
                              (cond-> {:award/id award-id
                                       :award/amount award-amount
                                       :funding funding
                                       :settlement (:settlement policy-award)}
                                calculation (assoc :calculation calculation)))))
                        (:awards policy))
        award-violations (mapcat identity
                                 (map (fn [policy-award]
                                        (let [award-id (:award/id policy-award)
                                              stored (get stored-by-id award-id)
                                              {:keys [violations]}
                                              (independent-award extension-map gross-amount
                                                                 policy-award param-values stored)]
                                          violations))
                                      (:awards policy)))
        active-awards (remove nil? processed)
        sorted-awards (vec (sort-by :award/id active-awards))]
    (if (seq award-violations)
      {:violations (vec award-violations)}
      (let [deductions (reduce (fn [acc award]
                                 (merge-with + acc (:funding award)))
                               {} sorted-awards)
            ;; settlement inflows
            settlements (reduce (fn [acc award]
                                  (let [dest (get-in award [:settlement :allocation-id])
                                        amt (:award/amount award)]
                                    (if dest
                                      (update acc dest (fnil + 0) amt)
                                      acc)))
                                {} sorted-awards)
            ;; final allocations
            all-ids (into (keys base)
                          (into (keys deductions)
                                (keys settlements)))
            final (into {}
                        (map (fn [id]
                               (let [b (get base id 0)
                                     d (get deductions id 0)
                                     s (get settlements id 0)]
                                 [id (+ b (- d) s)]))
                             all-ids))]
        {:awards sorted-awards
         :base base
         :deductions deductions
         :settlements settlements
         :final final
         :violations []}))))

(defn- verify-recomputed-calculations
  "Recompute every stored rate-derived calculation record against the policy
   and parameter values, accumulating a recomputation-mismatch violation for
   any record that cannot be reproduced exactly. This covers both positive
   awards and zero-outcome records that produced no transfer."
  [v policy gross-amount param-values stored-calculations]
  (reduce (fn [vs calc]
            (let [policy-award (some #(when (= (:award/id calc) (:award/id %)) %)
                                     (:awards policy))
                  recomputed (when (and policy-award
                                        (= :rate-of-gross (get-in policy-award [:amount :method])))
                               (award-calculation (:award/id calc)
                                                  gross-amount
                                                  (:amount policy-award)
                                                  param-values))]
              (if (= calc recomputed)
                vs
                (conj vs {:violation/id :violation/recomputation-mismatch
                          :details {:field (str "calculation " (:award/id calc))
                                    :stored calc
                                    :recomputed recomputed}}))))
          v stored-calculations))

(defn- verify-extension-resolution-root
  "Recompute the extension resolution root for a policy against the
   verification extension-map and compare it with the root committed in the
   distribution artifact. When the artifact predates resolution commitment
   (no stored root), the check is skipped."
  [v stored-root policy extension-map schema-registry]
  (if (nil? stored-root)
    v
    (let [resolution (resolve-policy-extensions extension-map policy
                                                {:schema-registry schema-registry})]
      (if (and (:valid? resolution)
               (= stored-root (get-in resolution [:resolution :extensions/resolution-root])))
        v
        (conj v {:violation/id :violation/extension-resolution-mismatch
                 :details {:stored stored-root
                           :recomputed (get-in resolution
                                               [:resolution :extensions/resolution-root])
                           :resolution-valid? (:valid? resolution)}})))))

(defn verify-distribution
  "Independently verify a slash-distribution.v1 artifact.

   When called with just the artifact, performs consistency-only checks
   (hash, ordering, conservation, funding sums, source capacity).

   When called with the optional verification context containing :policy
   and :policy-root, performs full recomputation verification:
   - policy hash matches committed root
   - every stored value is independently recomputed from the policy
     and parameter context, then compared
   - all consistency checks follow

   Second arg: optional {:policy <policy-map>
                         :parameter-context {:values {<kw> <int>}}
                         :extension-map <optional frozen extension-map>
                         :schema-registry <optional schema-id -> root map>}

   The optional :extension-map supplies the capability registry used for
   recomputation; it defaults to the built-in core package. When the artifact
   commits an extension resolution root, it is recomputed and compared.

   Returns {:valid? true} or {:valid? false :violations [...]}."
  ([distribution]
   (verify-distribution distribution nil))
  ([distribution verification-ctx]
   (let [v []
         v (if verification-ctx
             (let [policy (:policy verification-ctx)
                   param-ctx (:parameter-context verification-ctx)
                   param-values (get-in param-ctx [:values] {})
                   extension-map (or (:extension-map verification-ctx)
                                     (core-extension-map))]
               (if (nil? policy)
                 (conj v {:violation/id :violation/missing-policy
                          :details {:reason "verification context has no :policy"}})
                 (let [computed-policy-root (policy-hash policy)
                       stored-root (:distribution/policy-root distribution)
                       v (if (= computed-policy-root stored-root) v
                             (conj v {:violation/id :violation/policy-root-mismatch
                                      :details {:computed computed-policy-root
                                                :stored stored-root}}))
                       gross-amount (:distribution/gross-amount distribution)
                       stored-awards (:distribution/awards distribution)
                       independent (independent-distribution extension-map gross-amount
                                                             policy param-values stored-awards)]
                   (into v (:violations independent)))))
             v)
         ;; hash check
         computed-hash (distribution-hash distribution)
         v (if (= computed-hash (:distribution/hash distribution)) v
               (conj v {:violation/id :violation/distribution-hash-mismatch
                        :details {:computed computed-hash
                                  :embedded (:distribution/hash distribution)}}))
         ;; gross amount
         gross-amount (:distribution/gross-amount distribution)
         v (if (and (integer? gross-amount) (not (neg? gross-amount))) v
               (conj v {:violation/id :violation/invalid-gross-amount
                        :details {:gross-amount gross-amount}}))

         stored-base (:distribution/base-allocations distribution)
         v (if (= gross-amount (reduce + 0 (vals stored-base))) v
               (conj v {:violation/id :violation/base-conservation
                        :details {:gross-amount gross-amount
                                  :base-sum (reduce + 0 (vals stored-base))}}))

         ;; summary is derived from the committed calculation trace
         stored-calcs (:distribution/calculations distribution)
         stored-summary (:distribution/summary distribution)
         v (if (and (some? stored-calcs) (some? stored-summary))
             (let [recomputed-summary (build-rate-derived-summary stored-calcs)]
               (if (= recomputed-summary stored-summary)
                 v
                 (conj v {:violation/id :violation/summary-mismatch
                          :details {:stored stored-summary
                                    :recomputed recomputed-summary}})))
             v)

         ;; recomputation comparison when policy was supplied and valid
         v (if verification-ctx
             (let [policy (:policy verification-ctx)
                   param-ctx (:parameter-context verification-ctx)
                   param-values (get-in param-ctx [:values] {})
                   extension-map (or (:extension-map verification-ctx)
                                     (core-extension-map))
                   schema-registry (or (:schema-registry verification-ctx)
                                       schemas/core-schemas)
                   gross-amount (:distribution/gross-amount distribution)]
               (if (nil? policy)
                 v
                 (let [stored-awards (:distribution/awards distribution)
                       independent (independent-distribution extension-map gross-amount
                                                             policy param-values stored-awards)]
                   (if (seq (:violations independent))
                     v
                     (let [v (verify-recomputed-against-stored
                              v :base stored-base (:base independent))
                           stored-awards (:distribution/awards distribution)
                           indep-awards (:awards independent)
                           v (reduce
                              (fn [vs [stored indep]]
                                (-> vs
                                    (verify-recomputed-against-stored
                                     (str "award " (:award/id stored) " amount")
                                     (:award/amount stored)
                                     (:award/amount indep))
                                    (verify-recomputed-against-stored
                                     (str "award " (:award/id stored) " funding")
                                     (:funding stored)
                                     (:funding indep))))
                              v (map vector stored-awards indep-awards))
                           stored-final (:distribution/final-allocations distribution)
                           indep-final (:final independent)]
                       (-> (verify-recomputed-against-stored
                            v :final-allocations stored-final indep-final)
                           (verify-recomputed-calculations
                            policy gross-amount param-values
                            (:distribution/calculations distribution))
                           (verify-extension-resolution-root
                            (:distribution/extension-resolution-root distribution)
                            policy extension-map schema-registry)))))))
             v)

         ;; consistency checks (run regardless)
         awards (:distribution/awards distribution)

         ;; award ordering
         award-ids (mapv :award/id awards)
         v (if (= award-ids (vec (sort award-ids))) v
               (conj v {:violation/id :violation/award-order-invalid
                        :details {:received award-ids
                                  :expected (vec (sort award-ids))}}))

         ;; each award's embedded calculation must equal its record in the
         ;; committed calculation trace
         v (reduce (fn [vs award]
                     (let [calc (:calculation award)
                           trace (some #(when (= (:award/id %) (:award/id award)) %)
                                       stored-calcs)]
                       (if (= calc trace)
                         vs
                         (conj vs {:violation/id :violation/recomputation-mismatch
                                   :details {:field (str "award " (:award/id award)
                                                         " calculation binding")
                                             :stored calc
                                             :recomputed trace}}))))
                   v awards)

         ;; per-award funding conservation
         v (reduce (fn [vs award]
                     (let [funding (:funding award)
                           funding-total (reduce + 0 (vals funding))
                           award-amount (:award/amount award)]
                       (if (= funding-total award-amount)
                         vs
                         (conj vs {:violation/id :violation/award-funding-conservation
                                   :details {:award/id (:award/id award)
                                             :award/amount award-amount
                                             :funding-total funding-total}}))))
                   v awards)

         ;; aggregate deductions
         aggregate-deductions (reduce (fn [acc award]
                                        (merge-with + acc (:funding award)))
                                      {}
                                      awards)
         v (reduce (fn [vs [source deduction]]
                     (let [base-val (get stored-base source 0)]
                       (if (> deduction base-val)
                         (conj vs {:violation/id :violation/source-overdrawn
                                   :details {:source-id source
                                             :available base-val
                                             :required deduction}})
                         vs)))
                   v aggregate-deductions)

         ;; settlement inflows
         settlement-inflows (reduce (fn [acc award]
                                      (let [dest (get-in award [:settlement :allocation-id])
                                            amt (:award/amount award)]
                                        (if dest
                                          (update acc dest (fnil + 0) amt)
                                          acc)))
                                    {}
                                    awards)

         ;; reconciliation: final = base - deductions + settlements
         stored-final (:distribution/final-allocations distribution)
         all-ids (into (keys stored-base)
                       (into (keys aggregate-deductions)
                             (keys settlement-inflows)))
         v (reduce (fn [vs id]
                     (let [b (get stored-base id 0)
                           d (get aggregate-deductions id 0)
                           s (get settlement-inflows id 0)
                           f (get stored-final id)]
                       (if (= f (+ b (- d) s))
                         vs
                         (conj vs {:violation/id :violation/allocation-reconciliation
                                   :details {:allocation-id id
                                             :base b
                                             :deductions d
                                             :settlements s
                                             :expected (+ b (- d) s)
                                             :actual f}}))))
                   v all-ids)

         ;; non-negative final
         v (reduce (fn [vs [id val]]
                     (if (neg? val)
                       (conj vs {:violation/id :violation/negative-final-allocation
                                 :details {:allocation-id id :value val}})
                       vs))
                   v stored-final)

         ;; conservation
         v (if (= gross-amount (reduce + 0 (vals stored-final))) v
               (conj v {:violation/id :violation/conservation-violation
                        :details {:gross-amount gross-amount
                                  :final-total (reduce + 0 (vals stored-final))
                                  :categories {:base-total (reduce + 0 (vals stored-base))
                                               :deduction-total (reduce + 0 (vals aggregate-deductions))
                                               :settlement-total (reduce + 0 (vals settlement-inflows))
                                               :award-total (reduce + 0 (map :award/amount awards))
                                               :retained 0}}}))

         ;; nonzero award requirements
         v (reduce (fn [vs award]
                     (let [amount (:award/amount award)]
                       (if (pos? amount)
                         (cond-> vs
                           (nil? (get-in award [:beneficiary :participant/id]))
                           (conj {:violation/id :violation/missing-beneficiary
                                  :details {:award/id (:award/id award)}})
                           (nil? (get-in award [:eligibility :evidence-reference]))
                           (conj {:violation/id :violation/missing-eligibility-reference
                                  :details {:award/id (:award/id award)}}))
                         vs)))
                   v awards)]
     {:valid? (empty? v)
      :violations (vec v)})))

;; ── application receipt ─────────────────────────────────────────────────

(def ^:private receipt-schema-version
  "slash-distribution-application-receipt.v1")

(defn receipt-hash-projection
  "Return only the committed fields of an application receipt.
   This projection is the subset used to compute :receipt/hash."
  [receipt]
  (select-keys receipt
               [:schema-version
                :receipt/distribution-root
                :receipt/policy-root
                :receipt/parameter-context-root
                :receipt/pre-state-root
                :receipt/post-state-root
                :receipt/idempotency-key
                :receipt/status
                :receipt/abstract-effects
                :receipt/obligations]))

(defn receipt-hash
  "Compute the SLASH_DISTRIBUTION_APPLICATION_RECEIPT_V1 domain hash."
  [receipt]
  (hc/domain-hash :slash-distribution-application-receipt-v1
                  (receipt-hash-projection receipt)))

(defn build-application-receipt
  "Build a slash-distribution-application-receipt.v1 artifact.

   Input:
     :distribution-root   — hash of the applied distribution artifact
     :policy-root         — hash of the policy used
     :parameter-context-root — root reference for resolved parameters
     :pre-state-root      — world state hash before application
     :post-state-root     — world state hash after application
     :idempotency-key     — key used for replay protection
     :status              — :applied | :skipped (idempotent replay)
     :abstract-effects    — [{:allocation/id <kw> :amount <int>} ...]
     :concrete-effects    — [{:target {:target/type <kw> :target/key <kw>} :delta <int>} ...]
     :obligations         — [{:obligation/kind <kw> :beneficiary <id>
                               :amount <int> :obligation-reference <string>} ...]

   Returns the completed receipt artifact with embedded :receipt/hash."
  [{:keys [distribution-root policy-root parameter-context-root
           pre-state-root post-state-root idempotency-key status
           abstract-effects concrete-effects obligations]}]
  (let [receipt (merge
                 {:schema-version receipt-schema-version
                  :receipt/distribution-root distribution-root
                  :receipt/policy-root policy-root
                  :receipt/parameter-context-root parameter-context-root
                  :receipt/pre-state-root pre-state-root
                  :receipt/post-state-root post-state-root
                  :receipt/idempotency-key idempotency-key
                  :receipt/status status
                  :receipt/abstract-effects (vec abstract-effects)
                  :receipt/obligations (vec obligations)}
                 (when (seq concrete-effects)
                   {:receipt/concrete-effects (vec concrete-effects)}))]
    (assoc receipt :receipt/hash (receipt-hash receipt))))
