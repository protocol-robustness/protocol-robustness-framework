(ns resolver-sim.pro-rata.semantic-admission
  "Ephemeral admission boundary for independently reconstructed pro-rata decisions.

   This namespace intentionally covers only simple, no-row pro-rata decisions.
   Row/cap, redistribution, multi-round, and concurrent state-transition
   assurance remain separate contracts.

   Reference independence: semantic reconstruction uses exact-math allocator
   primitives directly, NOT the producer functions. The exact-math layer is
   trusted shared arithmetic infrastructure — Gate 1-A proves independent
   reconstruction orchestration, not full implementation independence from
   shared allocation primitives."
  (:require [clojure.set :as set]
            [resolver-sim.yield.partial-fill :as partial-fill]))

(def supported-mode :pro-rata)
(def supported-persistence :ephemeral)

(def ^:private supported-rounding-policies
  #{:floor :largest-remainder :floor-and-carry
    :principal-protective-floor :adversarial-rounding})

(def ^:private allowed-decision-keys
  #{:settlement-mode :requested :filled :deferred :haircut :unrealized
    :policy :evidence
    :schema-version :artifact/kind :decision/source :position/id
    :module/id :token :decision/id :decision/hash :decision/preimage
    :decision/canonical-bytes :decision/canonical-hash
    :allocation/scope :allocation/domain
    ;; Provenance / allocation metadata from withdraw-shared decision artifacts
    :participants
    :allocation/effective-caps :allocation/effective-cap-source
    :allocation/ordering :allocation/rounding-tie-break
    :allocation/priority-witness :allocation/invocation-context
    ;; Residual disposition
    :residual/destination :residual/policy-root
    ;; Liquidity budget provenance
    :liquidity/schema-version :liquidity/source-custody
    :liquidity/available-ratio :liquidity/available
    :liquidity/evaluation-context
    :liquidity/source-state-root :liquidity/market-state-root})

(defn- unsupported-reason
  [{:keys [policy rows]}]
  (cond
    rows :rows-not-yet-supported
    (and policy (contains? policy :mode)
         (not= supported-mode (:mode policy))) :mechanism-not-yet-supported
    :else nil))

(defn- validate-input
  "Validate semantic inputs for simple pro-rata admission.
   Returns {:valid? false :reason <reason> :detail <detail>} for
   malformed inputs, nil for well-formed inputs."
  [{:keys [available requested policy]}]
  (cond
    (nil? available)
    {:valid? false :reason :missing-available :detail "available-liquidity must be present"}

    (not (integer? available))
    {:valid? false :reason :non-integer-available
     :detail (str "available must be an integer, got: " (type available))}

    (neg? available)
    {:valid? false :reason :negative-available :detail "available must be non-negative"}

    (nil? policy)
    {:valid? false :reason :missing-policy :detail "policy must be present"}

    (nil? (:mode policy))
    {:valid? false :reason :missing-mode :detail "policy must specify :mode"}

    (nil? requested)
    {:valid? false :reason :missing-requested :detail "requested must be present"}

    (not (map? requested))
    {:valid? false :reason :invalid-requested :detail "requested must be a map"}

    (some #(nil? (key %)) requested)
    {:valid? false :reason :invalid-claim-identity
     :detail "requested contains nil claim key"}

    (some #(not (integer? (val %))) requested)
    {:valid? false :reason :non-integer-request
     :detail "requested contains non-integer amount"}

    (some #(neg? (long (val %))) requested)
    {:valid? false :reason :negative-request
     :detail "requested contains negative amount"}

    (not (contains? supported-rounding-policies
                    (:rounding-policy policy :floor-and-carry)))
    {:valid? false :reason :unknown-rounding-policy
     :detail (str "unknown rounding policy: " (:rounding-policy policy))}

    :else nil))

(defn- validate-decision-shape
  "Check that the decision does not contain unexpected top-level keys."
  [decision]
  (let [extra (set/difference (set (keys decision))
                              allowed-decision-keys)]
    (when (seq extra)
      {:valid? false :reason :unexpected-decision-keys
       :detail (str "unexpected keys in decision: " (sort extra))})))

(defn- validate-decision-integrity
  "Fail-closed validation of semantic inputs and decision shape for the
   simple no-row pro-rata admission boundary.
   Returns {:valid? false :reason <reason> :detail <detail>} for malformed
   inputs, nil for well-formed inputs."
  [input decision]
  (or (validate-input input)
      (validate-decision-shape decision)))

(defn verify-semantic-decision
  "Verify a supplied decision against authoritative simple pro-rata inputs.

   Returns an ephemeral result with separate closed-form and semantic
   reconstruction surfaces. Unsupported mechanism scope is never admitted.

   Admission requires BOTH closed-form checks AND semantic reconstruction
   to pass. Malformed inputs are rejected fail-closed."
  [{:keys [policy] :as input} decision]
  (let [unsupported (unsupported-reason input)
        invalid (validate-decision-integrity input decision)
        reason (or unsupported (:reason invalid))
        admission-status (cond
                           unsupported :unsupported
                           invalid :rejected
                           :else
                           (let [reconstruction (partial-fill/semantic-reconstruction input decision)
                                 closed-form (try
                                               (partial-fill/partial-fill-closed-form-checks decision)
                                               (catch clojure.lang.ExceptionInfo e
                                                 (:check-results (ex-data e))))
                                 closed-form-valid? (every? #{:pass :not-applicable}
                                                            (map :status closed-form))]
                             (cond
                               (and closed-form-valid? (:valid? reconstruction)) :admitted
                               :else :rejected)))]
    (if (or unsupported invalid)
      (let [reconstruction {:valid? false
                            :supported? (nil? unsupported)
                            :reason reason
                            :detail (when invalid (:detail invalid))
                            :expected nil
                            :actual (select-keys decision
                                                 [:settlement-mode :requested :filled
                                                  :deferred :haircut :unrealized])
                            :mismatches [{:reason reason}]
                            :scope {:kind :independent-semantic-reconstruction
                                    :producer-independent? true
                                    :mechanism :yield/partial-fill
                                    :mode (:mode policy supported-mode)
                                    :rows-supported? false}}
            closed-form (try
                          (partial-fill/partial-fill-closed-form-checks decision)
                          (catch clojure.lang.ExceptionInfo e
                            (:check-results (ex-data e))))
            closed-form-valid? (every? #{:pass :not-applicable}
                                       (map :status closed-form))]
        {:valid? false
         :admission/status admission-status
         :closed-form {:valid? closed-form-valid?
                       :checks closed-form}
         :semantic-reconstruction reconstruction
         :input-violations (when invalid [(:reason invalid)])
         :scope {:mechanism :yield/partial-fill
                 :mode (:mode policy supported-mode)
                 :rows-supported? false
                 :persistence supported-persistence
                 :authority :caller-supplied-semantic-inputs}
         :input (select-keys input [:available :requested :policy])})
      (let [reconstruction (partial-fill/semantic-reconstruction input decision)
            closed-form (try
                          (partial-fill/partial-fill-closed-form-checks decision)
                          (catch clojure.lang.ExceptionInfo e
                            (:check-results (ex-data e))))
            closed-form-valid? (every? #{:pass :not-applicable}
                                       (map :status closed-form))
            status (cond
                     (and closed-form-valid? (:valid? reconstruction)) :admitted
                     :else :rejected)]
        {:valid? (= :admitted status)
         :admission/status status
         :closed-form {:valid? closed-form-valid?
                       :checks closed-form}
         :semantic-reconstruction reconstruction
         :scope {:mechanism :yield/partial-fill
                 :mode (:mode policy supported-mode)
                 :rows-supported? false
                 :persistence supported-persistence
                 :authority :caller-supplied-semantic-inputs}
         :input (select-keys input [:available :requested :policy])}))))
