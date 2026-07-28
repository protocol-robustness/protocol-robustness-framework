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

   Boundaries:
   - This namespace proves arithmetic and structural validity.
   - A protocol adapter proves eligibility (challenge succeeded, beneficiary
     is legitimate, parameter matches governance snapshot).
   - An accounting adapter maps abstract allocations to concrete state effects."

  (:require [resolver-sim.hash.canonical :as hc]))

(def ^:private policy-schema-version "slash-distribution-policy.v1")
(def ^:private distribution-schema-version "slash-distribution.v1")

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
                :distribution/base-allocations
                :distribution/awards
                :distribution/final-allocations]))

(defn distribution-hash
  "Compute the SLASH_DISTRIBUTION_V1 domain hash of the committed
   subset of a distribution artifact."
  [distribution]
  (hc/domain-hash :slash-distribution-v1
                  (distribution-hash-projection distribution)))

;; ── policy validation ────────────────────────────────────────────────────

(defn- supported-award-amount-method?
  [method]
  (= :rate-of-gross method))

(defn- supported-allocation-method?
  [method]
  (= :weighted method))

(defn- supported-funding-method?
  [method]
  (= :weighted-deduction method))

(defn- supported-rounding?
  [rounding]
  (= :floor rounding))

(defn- bips-sum-to-scale?
  [weights scale]
  (= scale (reduce + 0 (vals weights))))

(defn- validate-policy-award
  [{:keys [allocation]} award]
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
              (if (supported-award-amount-method? method)
                (let [scale (:scale amount-spec)]
                  (cond-> v
                    (or (nil? scale) (not (integer? scale)) (not (pos? scale)))
                    (conj {:violation/id :violation/invalid-award-scale
                           :details {:award/id (:award/id award)
                                     :scale scale}})
                    (not= :floor (:rounding amount-spec))
                    (conj {:violation/id :violation/unsupported-rounding
                           :details {:award/id (:award/id award)
                                     :rounding (:rounding amount-spec)
                                     :supported [:floor]}})
                    (nil? (:parameter-key amount-spec))
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
              (if (supported-funding-method? method)
                (let [f-scale (:scale funding-spec)
                      f-weights (:weights funding-spec)
                      f-remainder (:remainder-to funding-spec)
                      base-keys (set (keys (:weights allocation)))
                      funding-keys (set (keys f-weights))
                      unknown (seq (remove base-keys funding-keys))]
                  (cond-> v
                    (or (nil? f-scale) (not (integer? f-scale)) (not (pos? f-scale)))
                    (conj {:violation/id :violation/invalid-funding-scale
                           :details {:award/id (:award/id award)
                                     :scale f-scale}})
                    (not (bips-sum-to-scale? f-weights f-scale))
                    (conj {:violation/id :violation/funding-weights-do-not-sum-to-scale
                           :details {:award/id (:award/id award)
                                     :weights f-weights
                                     :scale f-scale
                                     :sum (reduce + 0 (vals f-weights))}})
                    (nil? f-remainder)
                    (conj {:violation/id :violation/missing-funding-remainder
                           :details {:award/id (:award/id award)}})
                    (and (some? f-remainder) (not (contains? f-weights f-remainder)))
                    (conj {:violation/id :violation/invalid-funding-remainder
                           :details {:award/id (:award/id award)
                                     :remainder-to f-remainder
                                     :available-keys (vec (keys f-weights))}})
                    (boolean unknown)
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
   economically meaningful value must be supplied by the policy."
  [policy]
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
              (if (supported-allocation-method? method)
                (let [scale (:scale allocation)
                      weights (:weights allocation)
                      remainder-to (:remainder-to allocation)]
                  (cond-> v
                    (or (nil? scale) (not (integer? scale)) (not (pos? scale)))
                    (conj {:violation/id :violation/invalid-allocation-scale
                           :details {:scale scale}})
                    (nil? weights)
                    (conj {:violation/id :violation/missing-allocation-weights})
                    (and weights (not (bips-sum-to-scale? weights scale)))
                    (conj {:violation/id :violation/allocation-weights-sum-mismatch
                           :details {:weights weights
                                     :scale scale
                                     :sum (reduce + 0 (vals weights))}})
                    (nil? remainder-to)
                    (conj {:violation/id :violation/missing-allocation-remainder})
                    (and remainder-to
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
                    (into vs (validate-policy-award policy award)))
                  v
                  (:awards policy))]
    {:valid? (empty? v)
     :violations (vec v)}))

;; ── award calculation ────────────────────────────────────────────────────

(defn- compute-allocation
  "Compute weighted base allocation with floor rounding and a single
   remainder lump assigned to remainder-to."
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

(defn- compute-award-amount
  "Compute a single award amount from gross amount and a rate-of-gross parameter."
  [gross-amount amount-spec param-values]
  (let [{:keys [parameter-key scale rounding]} amount-spec
        resolved (get param-values parameter-key)]
    (when (and resolved (pos? scale))
      (let [amount (quot (* gross-amount resolved) scale)]
        ;; floor rounding: amount is already truncated; remainder is implicitly 0
        amount))))

(defn- compute-award-funding
  "Compute per-source funding deductions for a single award."
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

;; ── distribution builder ─────────────────────────────────────────────────

(defn- resolve-award
  "Process a single resolved award against the matching policy award spec
   and gross amount.

   Returns {:award <award-entry> :violations [...]}
   or {:award nil :violations [...]} on validation failure or zero amount."
  [gross-amount policy-award resolved-award param-values]
  (let [award-id (:award/id resolved-award)
        v []
        amount-spec (:amount policy-award)
        award-amount (compute-award-amount gross-amount amount-spec param-values)]
    (if (zero? award-amount)
      {:award nil
       :violations v}
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
          {:award nil :violations v}
          (let [funding-spec (:funding policy-award)
                settlement-spec (:settlement policy-award)
                funding (compute-award-funding award-amount funding-spec)]
            {:award {:award/id award-id
                     :award/amount award-amount
                     :eligibility {:trigger (:trigger elig)
                                   :evidence-reference (:evidence-reference elig)}
                     :beneficiary (:beneficiary resolved-award)
                     :funding funding
                     :settlement {:allocation-id (:allocation-id settlement-spec)}}
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
      :context        <any-map>}

   Returns:
     {:status :valid
      :distribution <artifact-map>}
     | {:status :invalid
        :violations [<violation-maps>]}

   Where <artifact-map> conforms to slash-distribution.v1."
  [{:keys [gross-amount policy policy-root parameter-context resolved-awards context]}]
  (let [v []
        v (if (and (integer? gross-amount) (not (neg? gross-amount))) v
              (conj v {:violation/id :violation/invalid-gross-amount
                       :details {:gross-amount gross-amount}}))
        ;; validate policy
        {:keys [valid? violations] :as policy-result}
        (when policy (validate-policy policy))
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
                  v resolved-awards)]
    (if (seq v)
      {:status :invalid :violations v}
      (let [allocation-spec (:allocation policy)
            base (compute-allocation gross-amount allocation-spec)
            ;; resolve each award
            resolved (mapv (fn [ra]
                             (let [pa (some #(when (= (:award/id ra) (:award/id %)) %)
                                            (:awards policy))]
                               (resolve-award gross-amount pa ra param-values)))
                           resolved-awards)
            award-violations (mapcat :violations resolved)]
        (if (seq award-violations)
          {:status :invalid :violations award-violations}
          (let [active-awards (keep :award resolved)
                sorted-awards (vec (sort-by :award/id active-awards))
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
                                               :final-total final-total}}]}
                      (let [distribution
                            {:schema-version distribution-schema-version
                             :distribution/gross-amount gross-amount
                             :distribution/context context
                             :distribution/policy-root effective-policy-root
                             :distribution/parameter-context parameter-context
                             :distribution/base-allocations base
                             :distribution/awards sorted-awards
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

(defn- independent-base
  "Recompute base allocations from a policy allocation spec.
   Returns {:base-allocations <map> :violations [...]}."
  [gross-amount allocation-spec]
  (let [base (compute-allocation gross-amount allocation-spec)]
    {:base-allocations base
     :violations []}))

(defn- independent-award
  "Recompute award amount and funding from policy award spec and parameters.
   Returns {:award-amount <int>
            :funding <map>
            :violations [...]}
   or {:award-amount nil :violations [...]} on error."
  [gross-amount policy-award param-values]
  (let [amount-spec (:amount policy-award)]
    (if (= :rate-of-gross (:method amount-spec))
      (let [param-key (:parameter-key amount-spec)
            resolved (get param-values param-key)]
        (if (nil? resolved)
          {:award-amount nil
           :violations [{:violation/id :violation/missing-parameter
                         :details {:award/id (:award/id policy-award)
                                   :parameter-key param-key}}]}
          (let [award-amount (compute-award-amount gross-amount amount-spec param-values)
                funding (compute-award-funding award-amount (:funding policy-award))]
            {:award-amount award-amount
             :funding funding
             :violations []})))
      {:award-amount nil
       :violations [{:violation/id :violation/unsupported-amount-method
                     :details {:award/id (:award/id policy-award)
                               :method (:method amount-spec)}}]})))

(defn- independent-distribution
  "Independently compute a full distribution from the same inputs the builder
   would receive: gross amount, policy, and resolved parameters.
   Returns {:awards [<award-map> ...] :base <map> :deductions <map>
            :settlements <map> :final <map> :violations [...]}."
  [gross-amount policy param-values]
  (let [allocation-spec (:allocation policy)
        base (compute-allocation gross-amount allocation-spec)
        processed (mapv (fn [policy-award]
                         (let [{:keys [award-amount funding violations]}
                               (independent-award gross-amount policy-award param-values)]
                           (when (and (some? award-amount) (pos? award-amount))
                             {:award/id (:award/id policy-award)
                              :award/amount award-amount
                              :funding funding
                              :settlement (:settlement policy-award)})))
                       (:awards policy))
        award-violations (mapcat identity
                                 (map (fn [policy-award]
                                       (let [{:keys [violations]}
                                             (independent-award gross-amount policy-award param-values)]
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
                         :parameter-context {:values {<kw> <int>}}}

   Returns {:valid? true} or {:valid? false :violations [...]}."
  ([distribution]
   (verify-distribution distribution nil))
  ([distribution verification-ctx]
   (let [v []
         v (if verification-ctx
             (let [policy (:policy verification-ctx)
                   param-ctx (:parameter-context verification-ctx)
                   param-values (get-in param-ctx [:values] {})]
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
                       independent (independent-distribution gross-amount policy param-values)]
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

         ;; recomputation comparison when policy was supplied and valid
         v (if verification-ctx
             (let [policy (:policy verification-ctx)
                   param-ctx (:parameter-context verification-ctx)
                   param-values (get-in param-ctx [:values] {})
                   gross-amount (:distribution/gross-amount distribution)]
               (if (nil? policy)
                 v
                 (let [independent (independent-distribution gross-amount policy param-values)]
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
                       (verify-recomputed-against-stored
                        v :final-allocations stored-final indep-final))))))
             v)

         ;; consistency checks (run regardless)
         awards (:distribution/awards distribution)

         ;; award ordering
         award-ids (mapv :award/id awards)
         v (if (= award-ids (vec (sort award-ids))) v
               (conj v {:violation/id :violation/award-order-invalid
                        :details {:received award-ids
                                  :expected (vec (sort award-ids))}}))

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
                                  :final-total (reduce + 0 (vals stored-final))}}))

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
