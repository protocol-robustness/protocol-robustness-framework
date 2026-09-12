(ns resolver-sim.protocols.sew.research-models.delegation
  "Delegation and multi-resolver collusion mechanics (Phase F).
   
   Key concepts:
   - Senior resolver: posts large bond, covers multiple juniors
   - Junior resolver: delegates to senior, uses senior's coverage
   - Waterfall slashing: junior bond → senior coverage → senior bond
   - Coverage multiplier: M=3 (senior bond × 3 = total coverage available)
   - Utilization cap: U=50% (max 50% of coverage can be reserved)")

;; Resolver registry: tracks bond status and delegation
;; Maps resolver-id -> {:bond, :senior, :reserved-coverage, :status}
(defn create-resolver-registry
  "Create initial resolver registry with given resolvers.
   
   Example:
   (create-resolver-registry
     {\"senior-1\" {:bond 10000, :tier :senior}
      \"junior-1\" {:bond 1000, :delegated-to \"senior-1\", :tier :junior}
      \"junior-2\" {:bond 1000, :delegated-to \"senior-1\", :tier :junior}})"
  [resolver-specs]
  (reduce
   (fn [registry [resolver-id spec]]
     (assoc registry resolver-id
            {:bond (:bond spec)
             :original-bond (:bond spec)  ; Track for reference
             :senior (:delegated-to spec)
             :tier (:tier spec :solo)
             :status :active
             :reserved-coverage 0
             :slashed-amount 0}))
   {}
   resolver-specs))

(defn coverage-available
  "Calculate coverage available from a senior resolver.
   
   Coverage = (senior bond) × M × U
   where M=3 (multiplier), U=0.5 (utilization cap)
   
   Returns: {:total-coverage, :reserved, :available}"
  [registry senior-id]
  (let [senior (registry senior-id)
        _ (assert senior (str "Senior not found: " senior-id))
        total-coverage (long (* (:bond senior) 3 0.5))  ; M=3, U=50%
        reserved (reduce
                  (fn [sum [resolver-id resolver]]
                    (if (= (:senior resolver) senior-id)
                      (+ sum (:reserved-coverage resolver))
                      sum))
                  0
                  registry)]
    {:senior senior-id
     :total-coverage total-coverage
     :reserved reserved
     :available (max 0 (- total-coverage reserved))}))

(defn can-reserve-coverage?
  "Check if junior can reserve coverage from senior.
   Returns: {:can-reserve bool, :reason str, :available-amount}"
  [registry junior-id senior-id coverage-needed]
  (let [junior (registry junior-id)
        senior (registry senior-id)
        _ (assert junior (str "Junior not found: " junior-id))
        _ (assert senior (str "Senior not found: " senior-id))]

    (cond
      (not= (:tier junior) :junior)
      {:can-reserve false :reason "Resolver is not a junior"}

      (not= (:tier senior) :senior)
      {:can-reserve false :reason "Senior is not a senior resolver"}

      (not= (:status junior) :active)
      {:can-reserve false :reason "Junior is not active"}

      (not= (:status senior) :active)
      {:can-reserve false :reason "Senior is not active"}

      :else
      (let [coverage-info (coverage-available registry senior-id)
            available (:available coverage-info)]
        (if (>= available coverage-needed)
          {:can-reserve true :available available}
          {:can-reserve false
           :reason "Insufficient senior coverage"
           :available available
           :requested coverage-needed})))))

(defn waterfall-slash
  "Apply slashing with waterfall logic: junior → senior coverage → senior bond.
   
   Returns updated registry with slash applied."
  [registry resolver-id slash-amount]
  (let [resolver (registry resolver-id)
        _ (assert resolver (str "Resolver not found: " resolver-id))]

    (if (zero? slash-amount)
      registry
      (case (:tier resolver)
        ;; Solo resolver: slash their own bond only
        :solo
        (update registry resolver-id
                (fn [r]
                  (let [available (:bond r)
                        actual-slash (min slash-amount available)
                        remaining (max 0 (- (:bond r) actual-slash))]
                    (assoc r
                           :bond remaining
                           :slashed-amount (+ (:slashed-amount r) actual-slash)))))

        ;; Junior resolver: waterfall logic
        :junior
        (let [senior-id (:senior resolver)
              _ (assert senior-id (str "Junior has no senior: " resolver-id))

              ;; Step 1: Slash junior's own bond
              junior-available (:bond resolver)
              slash-from-junior (min slash-amount junior-available)
              remaining-slash (- slash-amount slash-from-junior)

              registry-after-junior
              (if (zero? slash-from-junior)
                registry
                (update registry resolver-id
                        (fn [r]
                          (assoc r
                                 :bond (- (:bond r) slash-from-junior)
                                 :slashed-amount (+ (:slashed-amount r) slash-from-junior)))))

              ;; Step 2: Slash senior's reserved coverage
              senior (registry-after-junior senior-id)
              coverage-reserved (:reserved-coverage senior)
              slash-from-senior-coverage (min remaining-slash coverage-reserved)
              remaining-slash-2 (- remaining-slash slash-from-senior-coverage)]

          (cond
            ;; No senior slashing needed
            (zero? slash-from-senior-coverage)
            registry-after-junior

            ;; Senior coverage is depleted
            :else
            (update registry-after-junior senior-id
                    (fn [s]
                      (assoc s
                             :reserved-coverage (- (:reserved-coverage s) slash-from-senior-coverage)
                             :slashed-amount (+ (:slashed-amount s) slash-from-senior-coverage))))))

        ;; Senior resolver: slash own bond
        :senior
        (update registry resolver-id
                (fn [r]
                  (let [available (:bond r)
                        actual-slash (min slash-amount available)
                        remaining (max 0 (- (:bond r) actual-slash))]
                    (assoc r
                           :bond remaining
                           :slashed-amount (+ (:slashed-amount r) actual-slash)))))))))

(defn freeze-resolver
  "Mark resolver as frozen (cannot participate in new disputes).
   Used to model freeze period after slashing."
  [registry resolver-id freeze-duration-epochs]
  (update registry resolver-id assoc :status :frozen :frozen-until freeze-duration-epochs))

(defn unfreeze-resolver
  "Unfreeze resolver (can participate in new disputes)."
  [registry resolver-id]
  (update registry resolver-id assoc :status :active :frozen-until 0))
