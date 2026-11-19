(ns resolver-sim.protocols.sew.yield-capabilities
  "Fail-closed access to shared yield runtime capabilities."
  (:require [resolver-sim.protocols.registry :as protocol-registry]))

(defn require-capability
  "Resolve one yield capability or fail visibly when the integration is absent."
  [capability-id]
  (or (protocol-registry/get-capability "sew-v1" capability-id)
      (throw (ex-info "Sew operation requires the yield integration"
                      {:protocol/id "sew-v1"
                       :capability/id capability-id}))))

(defn- yield-state-present?
  [world]
  (or (seq (:yield/positions world))
      (seq (:yield-positions world))
      (some pos? (vals (:total-yield-generated world {})))
      (some (fn [[k v]]
              (and (keyword? k)
                   (= "yield" (namespace k))
                   (coll? v)
                   (seq v)))
            world)))

(defn resolve-profile
  [profile-id]
  (if (nil? profile-id)
    (if-let [resolve (protocol-registry/get-capability "sew-v1" :yield/resolve-profile)]
      (resolve nil)
      {:profile-id nil :archetype nil :module-id nil})
    ((require-capability :yield/resolve-profile) profile-id)))

(defn partial-yield-shortfall?
  [position shortfall]
  (if-let [partial? (protocol-registry/get-capability "sew-v1"
                                                      :yield/partial-yield-shortfall?)]
    (partial? position shortfall)
    (if (or position shortfall)
      (throw (ex-info "Sew operation requires the yield integration"
                      {:protocol/id "sew-v1"
                       :capability/id :yield/partial-yield-shortfall?}))
      false)))

(defn sum-recognized-losses
  [world token]
  (if-let [sum-losses (protocol-registry/get-capability "sew-v1"
                                                        :yield/sum-recognized-losses)]
    (sum-losses world token)
    (if (yield-state-present? world)
      (throw (ex-info "Sew operation requires the yield integration"
                      {:protocol/id "sew-v1"
                       :capability/id :yield/sum-recognized-losses}))
      0)))

(defn check-position-consistency
  [world]
  (if-let [check (protocol-registry/get-capability "sew-v1"
                                                   :yield/check-position-consistency)]
    (check world)
    (if (yield-state-present? world)
      (throw (ex-info "Sew operation requires the yield integration"
                      {:protocol/id "sew-v1"
                       :capability/id :yield/check-position-consistency}))
      {:holds? true :violations []})))

(defn check-sew-invariants
  [world]
  (if-let [check (protocol-registry/get-capability "sew-v1" :yield/check-sew-invariants)]
    (check world)
    {:sew/yield-exposure
     (if (yield-state-present? world)
       {:holds? false
        :violations [{:error :missing-yield-integration}]}
       {:holds? true :violations []})}))
