(ns resolver-sim.validation.gate
  "Phase-gate model for closed-form validation.

   Provides three sequential gates: integrity, economic-model, and strategic.
   Downstream gates are blocked when upstream gates fail, preventing weak
   algebraic checks from being cited as supporting stronger claims.

   Each gate function accepts existing check results and returns a verdict
   map.  No existing checks are modified — the gating is an aggregation
   layer on top."
  (:require [resolver-sim.validation.classes :as vc]))

;; ---------------------------------------------------------------------------
;; Status vocabulary
;; ---------------------------------------------------------------------------

(def pass-statuses
  "Status values that count toward a passing gate."
  #{:pass :not-applicable :verified})

(def block-statuses
  "Status values that block a gate from passing."
  #{:fail :violated :not-exercised})

;; ---------------------------------------------------------------------------
;; Integrity Gate
;; ---------------------------------------------------------------------------

(defn- algebraic-integrity-results
  "Filter check results to only those with :validation.class/algebraic-integrity."
  [check-results]
  (filter #(= (:validation-class %) :validation.class/algebraic-integrity)
          check-results))

(defn- check-failures
  "Return checks with a failing status."
  [check-results]
  (filter #(contains? block-statuses (:status %)) check-results))

(defn- exercised-witnesses
  "Return witnesses where the mechanism branch was actually exercised."
  [witnesses]
  (filter #(true? (:exercised-fill? %)) witnesses))

(defn evaluate-integrity-gate
  "Evaluate the integrity gate.

   Accepts:
     `check-results` — a seq of {:check/id kw, :status kw, :validation-class kw, ...}
     `witnesses` — a seq of {:settlement-mode kw, :fill-mode kw, :exercised-fill? bool, ...}
     `required-mechanisms` — a set of mechanism-level keywords that must have
                             at least one exercised witness (default nil)

   Returns {:gate :integrity
            :verdict :pass | :blocked
            :checks-executed N
            :checks-passed N
            :checks-blocked [{:check/id kw, :status kw, :details map} ...]
            :witnesses-exercised N
            :witnesses-total N
            :blocked-reason nil | string}"
  [check-results & {:keys [witnesses required-mechanisms]
                    :or {witnesses [], required-mechanisms nil}}]
  (let [integrity-checks (algebraic-integrity-results check-results)
        failures (check-failures integrity-checks)
        exercised (exercised-witnesses witnesses)
        total-witnesses (count witnesses)
        exercised-count (count exercised)
        ;; Check required mechanisms have at least one exercised witness
        mechanism-coverage (when required-mechanisms
                             (reduce (fn [acc mech]
                                       (let [covered? (some #(= (:fill-mode %) mech) exercised)]
                                         (assoc acc mech covered?)))
                                     {} required-mechanisms))
        missing-mechanisms (when required-mechanisms
                             (keep (fn [[mech covered?]]
                                     (when-not covered? mech))
                                   mechanism-coverage))
        blocked? (or (seq failures)
                     (and required-mechanisms (seq missing-mechanisms))
                     (and required-mechanisms (empty? exercised)))
        blocked-reason (cond
                         (seq failures)
                         (str (count failures) " algebraic-integrity check(s) failed")
                         (seq missing-mechanisms)
                         (str "required mechanism(s) not exercised: " (vec missing-mechanisms))
                         (and required-mechanisms (empty? exercised))
                         "no exercised witnesses found for required mechanisms"
                         :else nil)]
    {:gate :integrity
     :verdict (if blocked? :blocked :pass)
     :checks-executed (count integrity-checks)
     :checks-passed (- (count integrity-checks) (count failures))
     :checks-blocked (vec (take 10 (map (fn [f]
                                          {:check/id (:check/id f)
                                           :status (:status f)
                                           :details (:details f)})
                                        failures)))
     :witnesses-total total-witnesses
     :witnesses-exercised exercised-count
     :mechanism-coverage mechanism-coverage
     :blocked-reason blocked-reason}))

;; ---------------------------------------------------------------------------
;; Economic-Model Gate
;; ---------------------------------------------------------------------------

(defn evaluate-economic-model-gate
  "Evaluate the economic-model gate.

   Requires an upstream integrity verdict.  When the integrity gate is
   `:blocked`, this gate is also `:blocked`.

   Accepts:
     `integrity-verdict` — result from `evaluate-integrity-gate`
     `check-results` — a seq of check result maps
     `assumptions` — a map of economic assumptions used

   Returns {:gate :economic-model
            :verdict :pass | :blocked | :inconclusive
            :checks-executed N
            :checks-passed N
            :assumptions map
            :blocked-reason nil | string}"
  [integrity-verdict check-results & {:keys [assumptions]
                                      :or {assumptions {}}}]
  (if (= :blocked (:verdict integrity-verdict))
    {:gate :economic-model
     :verdict :blocked
     :blocked-reason (str "upstream integrity gate blocked: " (:blocked-reason integrity-verdict))
     :checks-executed 0
     :checks-passed 0
     :assumptions assumptions}
    (let [payoff-checks (filter #(#{:validation.class/payoff-property
                                    :validation.class/allocation-property}
                                  (:validation-class %))
                                check-results)
          failures (check-failures payoff-checks)
          inconclusive (filter #(= :inconclusive (:status %)) payoff-checks)
          blocked? (or (seq failures)
                       (seq inconclusive))
          blocked-reason (cond
                           (seq failures)
                           (str (count failures) " payoff/allocation check(s) failed")
                           (seq inconclusive)
                           (str (count inconclusive) " check(s) inconclusive")
                           :else nil)]
      {:gate :economic-model
       :verdict (if blocked? :blocked :pass)
       :checks-executed (count payoff-checks)
       :checks-passed (- (count payoff-checks) (count failures) (count inconclusive))
       :checks-blocked (vec (take 10 (map (fn [f]
                                            {:check/id (:check/id f)
                                             :status (:status f)
                                             :details (:details f)})
                                          (concat failures inconclusive))))
       :assumptions assumptions
       :blocked-reason blocked-reason})))

;; ---------------------------------------------------------------------------
;; Strategic Gate
;; ---------------------------------------------------------------------------

(defn evaluate-strategic-gate
  "Evaluate the strategic gate.

   Requires an upstream economic-model verdict.  When the economic-model
   gate is `:blocked`, this gate is also `:blocked`.

   Accepts:
     `economic-verdict` — result from `evaluate-economic-model-gate`
     `deviation-results` — a seq of {:property kw, :verdict :verified | :violated, ...}
     `equilibrium-results` — a seq of {:property kw, :status kw, ...}
     `contract-id` — a single deviation contract id (legacy form)
     `contract-ids` — the resolved deviation contract ids used; when supplied,
                      `:contract-id` is derived from the first member for
                      single-contract compatibility
     `scope` — the enumeration scope

   Contract provenance fails closed: when both `contract-id` and `contract-ids`
   are supplied they must agree — `contract-id` must be the sole member of the
   deduplicated `contract-ids` vector — otherwise an exception is thrown rather
   than silently recording a misleading contract label.

   Returns {:gate :strategic
            :verdict :verified | :violated | :inconclusive | :blocked
            :properties [...]
            :contract-id kw | nil
            :contract-ids [kw ...]
            :scope map
            :blocked-reason nil | string}"
  [economic-verdict deviation-results equilibrium-results
   & {:keys [contract-id contract-ids scope]}]
  (let [ids (vec (distinct (or contract-ids
                               (when contract-id [contract-id]))))
        _ (when (and contract-id (some? contract-ids) (seq ids)
                     (not (and (= 1 (count ids))
                               (= contract-id (first ids)))))
            (throw (ex-info "contract-id disagrees with contract-ids"
                            {:contract-id contract-id
                             :contract-ids ids})))
        effective-id (or contract-id (first ids))]
    (if (= :blocked (:verdict economic-verdict))
      {:gate :strategic
       :verdict :blocked
       :blocked-reason (str "upstream economic-model gate blocked: " (:blocked-reason economic-verdict))
       :properties []
       :contract-id effective-id
       :contract-ids ids
       :scope scope}
      (let [all-results (concat deviation-results
                                (map (fn [er] {:property (:property er)
                                               :verdict (case (:status er)
                                                          :pass :verified
                                                          :fail :violated
                                                          :inconclusive)
                                               :status (:status er)})
                                     equilibrium-results))
            violated (filter #(= :violated (:verdict %)) all-results)
            inconclusive (filter #(= :inconclusive (:verdict %)) all-results)]
        {:gate :strategic
         :verdict (cond
                    (seq violated) :violated
                    (seq inconclusive) :inconclusive
                    :else :verified)
         :properties (vec all-results)
         :contract-id effective-id
         :contract-ids ids
         :scope scope
         :blocked-reason (cond
                           (seq violated) (str (count violated) " property/properties violated")
                           (seq inconclusive) (str (count inconclusive) " check(s) inconclusive")
                           :else nil)}))))

;; ---------------------------------------------------------------------------
;; Combined evaluation
;; ---------------------------------------------------------------------------

(defn evaluate-gates
  "Run all three gates sequentially.

   Each downstream gate is skipped when the upstream gate is :blocked.

   Returns {:gates [{:gate kw, :verdict kw, ...} ...]
            :overall-verdict kw} where overall-verdict is the weakest
   of the three gate verdicts."
  [integrity-checks economic-checks strategic-deviation-results strategic-equilibrium-results
   & {:keys [witnesses required-mechanisms assumptions contract-id contract-ids scope]}]
  (let [integrity (evaluate-integrity-gate integrity-checks
                                           :witnesses witnesses
                                           :required-mechanisms required-mechanisms)
        economic (evaluate-economic-model-gate integrity economic-checks
                                               :assumptions assumptions)
        strategic (evaluate-strategic-gate economic
                                           strategic-deviation-results
                                           strategic-equilibrium-results
                                           :contract-id contract-id
                                           :contract-ids contract-ids
                                           :scope scope)
        verdicts [(:verdict integrity) (:verdict economic) (:verdict strategic)]
        ;; Weakest verdict wins
        overall (cond
                  (some #{:blocked} verdicts) :blocked
                  (some #{:violated} verdicts) :violated
                  (some #{:inconclusive} verdicts) :inconclusive
                  (every? #{:pass :verified} verdicts) :verified
                  :else :inconclusive)]
    {:gates [integrity economic strategic]
     :overall-verdict overall}))
