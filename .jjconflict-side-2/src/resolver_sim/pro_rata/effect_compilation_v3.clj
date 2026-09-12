(ns resolver-sim.pro-rata.effect-compilation-v3
  "Verified-body pro-rata effect compilation. V2 remains an opaque-root contract."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.pro-rata.allocation :as allocation]
            [resolver-sim.pro-rata.effect-compilation-semantics :as semantics]
            [resolver-sim.pro-rata.effect-compilation-v2 :as v2]
            [resolver-sim.pro-rata.execution-context :as execution-context]
            [resolver-sim.pro-rata.quantity :as quantity]
            [resolver-sim.pro-rata.target-map :as target-map]))

(def schema-version "pro-rata-effect-compilation.v3")

(defn compilation-root [compilation]
  (hc/domain-hash
   :pro-rata-effect-compilation-v3
   (cond-> (select-keys compilation [:schema-version :realized-allocation/root
                                     :allocation-policy/root :target-map/root
                                     :effect-compilation-semantics/root :effects/root])
     (contains? compilation :target-map-validation/root)
     (assoc :target-map-validation/root (:target-map-validation/root compilation)))))

(defn- validated-aggregate!
  [{:keys [allocation target-map target-map-validation native-location-map
           aggregate-quantity adapter-descriptor]}]
  (when-not (and (= (:adapter/descriptor-root adapter-descriptor)
                    (execution-context/descriptor-root adapter-descriptor))
                 (quantity/valid-identity? aggregate-quantity)
                 (= (:native-location-map/root native-location-map)
                    (target-map/location-map-root native-location-map))
                 (= (:target-map-validation/root target-map-validation)
                    (target-map/aggregate-validation-root target-map-validation)))
    (throw (ex-info "invalid retained aggregate validation body" {})))
  (let [expected (target-map/validate-aggregate-target-map
                  {:allocation allocation
                   :target-map target-map
                   :allocation-scope-root (:allocation-scope/root target-map-validation)
                   :aggregate-custody-scope-root (:aggregate-custody-scope/root target-map-validation)
                   :adapter-descriptor-root (:adapter/descriptor-root adapter-descriptor)
                   :native-state-before-root (:native-state-before/root target-map-validation)
                   :native-location-map native-location-map
                   :aggregate-quantity aggregate-quantity
                   :expected-identity
                   (assoc (select-keys aggregate-quantity
                                       [:protocol-instance/root :state-domain/root
                                        :subject/root :quantity-kind :asset/root :scope/root])
                          :mapping/profile target-map/many-to-one-profile)})]
    (when-not (= target-map-validation expected)
      (throw (ex-info "aggregate validation body does not recompute" {})))
    expected))

(defn normalized-effects
  "Recompute normalized effects from verified compiler inputs. The V2 EVM
   statement uses this function rather than accepting an effects or post-state
   body from its caller."
  [{:keys [allocation target-map semantics allocation-policy-root target-map-validation
           native-location-map aggregate-quantity adapter-descriptor]}]
  (case (:effect-compilation/profile semantics)
    :all-active (v2/all-active-effects allocation target-map)
    :aggregate-held-credit
    (:effects (v2/compile-aggregate-held-credit
               {:allocation allocation
                :aggregate-target-map target-map
                :target-map-validation
                (validated-aggregate! {:allocation allocation :target-map target-map
                                       :target-map-validation target-map-validation
                                       :native-location-map native-location-map
                                       :aggregate-quantity aggregate-quantity
                                       :adapter-descriptor adapter-descriptor})
                :aggregate-semantics-root (:effect-compilation-semantics/root semantics)
                :allocation-policy-root allocation-policy-root}))
    (throw (ex-info "unsupported verified effect compilation profile" {}))))

(defn compile
  "Compile only after retained source bodies recompute at their declared roots.
   Aggregate V3 additionally retains the complete Stage A validation root; the
   all-active V3 projection remains unchanged."
  [{:keys [allocation target-map semantics allocation-policy-root
           target-map-validation native-location-map aggregate-quantity
           adapter-descriptor] :as input}]
  (let [profile (:effect-compilation/profile semantics)]
    (when-not (and (allocation/allocation-hash-valid? allocation)
                   (= (:target-map/root target-map)
                      (case profile
                        :all-active (target-map/target-map-root target-map)
                        :aggregate-held-credit (target-map/aggregate-target-map-root target-map)
                        nil))
                   (semantics/valid? semantics)
                   (= (:target-map/schema semantics) (:schema-version target-map)))
      (throw (ex-info "invalid verified effect compilation bodies" {})))
    (let [v2-artifact
          (case profile
            :all-active
            (v2/compile-all-active {:allocation allocation
                                    :target-map target-map
                                    :allocation-policy-root allocation-policy-root
                                    :effect-compilation-semantics-root (:effect-compilation-semantics/root semantics)})
            :aggregate-held-credit
            (v2/compile-aggregate-held-credit
             {:allocation allocation
              :aggregate-target-map target-map
              :target-map-validation
              (validated-aggregate! {:allocation allocation :target-map target-map
                                     :target-map-validation target-map-validation
                                     :native-location-map native-location-map
                                     :aggregate-quantity aggregate-quantity
                                     :adapter-descriptor adapter-descriptor})
              :aggregate-semantics-root (:effect-compilation-semantics/root semantics)
              :allocation-policy-root allocation-policy-root}))]
      (let [base (cond-> (assoc (dissoc v2-artifact :effect-compilation/root :mapping-profile/root)
                                :schema-version schema-version)
                   (= profile :aggregate-held-credit)
                   (assoc :target-map-validation/root
                          (:target-map-validation/root target-map-validation)))]
        (assoc base :effect-compilation/root (compilation-root base))))))
