(ns resolver-sim.pro-rata.realized-allocation-aggregate-held-credit
  "Narrow bridge from V1 realized-statement inputs to aggregate held-credit.

   This bridge is deliberately downstream of both the V1 allocation statement
   and caller-supplied target artifacts.  In particular, V1 has no asset field:
   the aggregate quantity body binds an asset identity for this bridge, but the
   bridge does not claim that V1 selected that asset or any target artifact."
  (:require [resolver-sim.adapters.sew.aggregate-held-credit :as held-credit]
            [resolver-sim.allocation.context :as context]
            [resolver-sim.allocation.realized-statement :as statement]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.pro-rata.allocation :as allocation]
            [resolver-sim.pro-rata.effect-compilation-v2 :as compilation]
            [resolver-sim.pro-rata.execution-context :as execution-context]
            [resolver-sim.pro-rata.quantity :as quantity]
            [resolver-sim.pro-rata.target-map :as target-map]))

(def schema-version "realized-allocation-aggregate-held-credit-bridge.v1")

(defn- reject! [reason data]
  (throw (ex-info "invalid realized-allocation aggregate held-credit bridge"
                  (assoc data :reason reason))))

(defn- exact-positive! [field value]
  (when-not (and (integer? value) (pos? value))
    (reject! :non-positive-amount {:field field :value value}))
  (bigint value))

(defn- closed-body! [body]
  (when-not (= #{:allocation-context-input :decision :round-lifecycle
                 :aggregate-quantity :aggregate-target-map :native-location-map
                 :adapter-descriptor :target-map-validation}
               (set (keys body)))
    (reject! :invalid-body-shape {:keys (set (keys body))})))

(defn- derive-rows [ctx decision]
  (let [requested (:requested decision)
        filled (:filled decision)
        deferred (:deferred decision)
        haircut (:haircut decision)
        claimant-ids (mapv :claim/id (:claimants ctx))
        claimant-set (set claimant-ids)]
    (when-not (and (map? requested) (map? filled) (map? deferred) (map? haircut)
                   (= claimant-set (set (keys requested)) (set (keys filled)))
                   (reject! :claim-set-mismatch {:claimants claimant-set
                                                 :requested (set (keys requested))
                                                 :filled (set (keys filled))}))
      (when-not (and (every? zero? (vals deferred)) (every? zero? (vals haircut)))
        (reject! :not-all-active {:deferred deferred :haircut haircut}))
      (mapv (fn [{:keys [claim/id amount weight]}]
              (let [requested-amount (get requested id)
                    filled-amount (get filled id)]
                (when-not (= amount requested-amount filled-amount)
                  (reject! :not-full-fill {:claim/id id :context-amount amount
                                           :requested requested-amount :filled filled-amount}))
                {:row/id id
                 :obligation/id id
                 :requested (exact-positive! :requested requested-amount)
                 :weight (exact-positive! :weight weight)}))
            (:claimants ctx)))))

(defn- derive-allocation [ctx decision statement-body]
  (let [rows (derive-rows ctx decision)
        available (reduce + 0N (map :requested rows))
        result (allocation/allocate
                {:allocation/id [:realized-allocation-statement (:statement/root statement-body)]
                 :available available
                 :rows rows
                 :rounding-policy :largest-remainder
                 :tie-break-policy :canonical-row-id
                 :redistribution-policy :unallocated})]
    (when-not (and (zero? (:unallocated-residual result))
                   (= (into {} (map (juxt :row/id :requested) rows))
                      (into {} (map (juxt :row/id :allocated) (:rows result)))))
      (reject! :allocation-replay-mismatch {:allocation result}))
    result))

(defn- verify-target-artifacts!
  [{:keys [allocation aggregate-quantity aggregate-target-map native-location-map
           adapter-descriptor target-map-validation]}]
  (when-not (and (quantity/valid-identity? aggregate-quantity)
                 (= (:adapter/descriptor-root adapter-descriptor)
                    (execution-context/descriptor-root adapter-descriptor))
                 (= (:target-map/root aggregate-target-map)
                    (target-map/aggregate-target-map-root aggregate-target-map))
                 (= (:native-location-map/root native-location-map)
                    (target-map/location-map-root native-location-map)))
    (reject! :invalid-target-artifact-root {}))
  ;; Re-run the existing closed aggregate validator from persisted bodies. This
  ;; checks row coverage, one aggregate quantity, scopes, descriptor, and asset.
  (let [revalidated (target-map/validate-aggregate-target-map
                     {:allocation allocation
                      :target-map aggregate-target-map
                      :allocation-scope-root (:allocation-scope/root target-map-validation)
                      :aggregate-custody-scope-root (:aggregate-custody-scope/root target-map-validation)
                      :adapter-descriptor-root (:adapter/descriptor-root adapter-descriptor)
                      :native-state-before-root (:native-state-before/root target-map-validation)
                      :native-location-map native-location-map
                      :aggregate-quantity aggregate-quantity
                      :expected-identity (assoc (select-keys aggregate-quantity
                                                             [:protocol-instance/root :state-domain/root
                                                              :subject/root :quantity-kind :asset/root :scope/root])
                                                :mapping/profile target-map/many-to-one-profile)})]
    (when-not (= target-map-validation revalidated)
      (reject! :target-validation-mismatch {}))
    revalidated))

(defn bridge-root [bridge]
  (hc/hash-with-intent
   {:hash/intent :projection-artifact}
   (dissoc bridge :bridge/root)))

(defn build
  "Build a body-persisted bridge. Target/asset inputs are supplied by the caller;
   this function validates their consistency but grants them no upstream authority."
  [body]
  (closed-body! body)
  (let [ctx (context/build-context (:allocation-context-input body))
        s (statement/build-statement {:ctx ctx :decision (:decision body)
                                      :round-lifecycle (:round-lifecycle body)})]
    (when-not (and (:statement/all-active? s)
                   (true? (get-in s [:statement/verification-equalities
                                     :all-active-all-full-fill])))
      (reject! :not-all-active-statement {}))
    (let [realized-allocation (derive-allocation ctx (:decision body) s)
          validation (verify-target-artifacts!
                      (assoc body :allocation realized-allocation))
          compiled (compilation/compile-aggregate-held-credit
                    {:allocation realized-allocation
                     :aggregate-target-map (:aggregate-target-map body)
                     :target-map-validation validation
                     :aggregate-semantics-root (held-credit/compilation-semantics-root)
                     :allocation-policy-root (:allocation-policy-root s)})
          aggregate-amount (reduce + 0N (map :delta (:effects compiled)))]
      (when-not (pos? aggregate-amount)
        (reject! :non-positive-aggregate {:effects (:effects compiled)}))
      (let [base (assoc body
                        :schema-version schema-version
                        :allocation-context ctx
                        :statement s
                        :realized-allocation realized-allocation
                        :compilation compiled
                        :aggregate-amount aggregate-amount)]
        (assoc base :bridge/root (bridge-root base))))))

(defn verify
  "Fail-closed verifier for a persisted bridge body. It rebuilds every derived
   body from raw V1 and full target artifacts; therefore rows cannot be silently
   substituted, omitted, or duplicated after persistence."
  [bridge]
  (try
    (let [body (select-keys bridge [:allocation-context-input :decision :round-lifecycle
                                    :aggregate-quantity :aggregate-target-map :native-location-map
                                    :adapter-descriptor :target-map-validation])
          rebuilt (build body)]
      {:valid? (= bridge rebuilt)
       :reason (when-not (= bridge rebuilt) :persisted-body-mismatch)})
    (catch clojure.lang.ExceptionInfo error
      {:valid? false :reason (:reason (ex-data error))})))
