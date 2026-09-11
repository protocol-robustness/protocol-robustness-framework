(ns resolver-sim.pro-rata.effect-compilation-v3
  "Verified-body pro-rata effect compilation. V2 remains an opaque-root contract."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.pro-rata.allocation :as allocation]
            [resolver-sim.pro-rata.effect-compilation-semantics :as semantics]
            [resolver-sim.pro-rata.effect-compilation-v2 :as v2]
            [resolver-sim.pro-rata.target-map :as target-map]))

(def schema-version "pro-rata-effect-compilation.v3")

(defn compilation-root [compilation]
  (hc/domain-hash :pro-rata-effect-compilation-v3
                  (select-keys compilation [:schema-version :realized-allocation/root
                                            :allocation-policy/root :target-map/root
                                            :effect-compilation-semantics/root
                                            :effects/root])))

(defn compile
  "Compile only after the allocation, target map, and compiler semantics bodies
   all recompute at their declared roots."
  [{:keys [allocation target-map semantics allocation-policy-root]}]
  (when-not (and (allocation/allocation-hash-valid? allocation)
                 (= (:target-map/root target-map) (target-map/target-map-root target-map))
                 (semantics/valid? semantics)
                 (= (:target-map/schema semantics) (:schema-version target-map)))
    (throw (ex-info "invalid verified effect compilation bodies" {})))
  (let [v2-artifact
        (case (:effect-compilation/profile semantics)
          :all-active
          (v2/compile-all-active {:allocation allocation
                                  :target-map target-map
                                  :allocation-policy-root allocation-policy-root
                                  :effect-compilation-semantics-root (:effect-compilation-semantics/root semantics)})
          :aggregate-held-credit
          (let [validation {:target-map/root (:target-map/root target-map)
                            :realized-allocation/root (:allocation/hash allocation)}
                validation (assoc validation :target-map-validation/root
                                  (target-map/aggregate-validation-root validation))]
            (v2/compile-aggregate-held-credit
             {:allocation allocation :aggregate-target-map target-map
              :target-map-validation validation
              :aggregate-semantics-root (:effect-compilation-semantics/root semantics)
              :allocation-policy-root allocation-policy-root})))]
    (let [base (assoc (dissoc v2-artifact :effect-compilation/root :mapping-profile/root)
                      :schema-version schema-version)]
      (assoc base :effect-compilation/root (compilation-root base)))))
