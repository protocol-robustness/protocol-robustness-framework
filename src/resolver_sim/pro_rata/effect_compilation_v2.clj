(ns resolver-sim.pro-rata.effect-compilation-v2
  "Additive, target-map-bound pro-rata compilation commitment.

   v1 remains unchanged. v2 makes allocation-policy and target-map identity
   explicit while reusing canonical-effects for all delta normalization."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.pro-rata.canonical-effects :as effects]
            [resolver-sim.pro-rata.target-map :as target-map]))

(def schema-version "pro-rata-effect-compilation.v2")

(defn compilation-root [compilation]
  (hc/domain-hash :pro-rata-effect-compilation-v2
                  (select-keys compilation [:schema-version :realized-allocation/root
                                            :allocation-policy/root :target-map/root
                                            :mapping-profile/root
                                            :effect-compilation-semantics/root
                                            :effects/root])))

(defn- targets-for [target-map]
  (let [by-key (into {} (map (fn [target]
                               [[(:allocation/subject-id target) (:mapping/role target)]
                                (:quantity/root target)]))
                     (:targets target-map))
        liquidity (get by-key [:allocation/liquidity :available])]
    (when-not liquidity
      (throw (ex-info "target map lacks allocation liquidity target" {})))
    {:liquidity/root liquidity
     :by-key by-key}))

(defn compile-all-active
  "Compile only the currently frozen all-active profile. Deferred, haircut,
   rejected, residual, and other disposition semantics are intentionally not
   inferred by this v2 profile."
  [{:keys [allocation target-map allocation-policy-root effect-compilation-semantics-root]}]
  (when-not (and (zero? (:unallocated-residual allocation))
                 (every? #(zero? (:unmet %)) (:rows allocation)))
    (throw (ex-info "all-active compilation rejects non-full allocation" {})))
  (let [{:keys [liquidity/root by-key]} (targets-for target-map)
        targets (assoc (into {:liquidity/root root}
                             (map (fn [row]
                                    (let [row-id (:row/id row)]
                                      [row-id {:filled/root (get by-key [row-id :filled])
                                               :outstanding/root (get by-key [row-id :outstanding])}]))
                                  (:rows allocation)))
                       :liquidity/root root)
        _ (doseq [[row-id mapping] (dissoc targets :liquidity/root)]
            (when-not (every? string? (vals mapping))
              (throw (ex-info "target map lacks all-active row targets" {:row/id row-id}))))
        effects (effects/compile-pro-rata-effects allocation targets)
        base {:schema-version schema-version
              :realized-allocation/root (:allocation/hash allocation)
              :allocation-policy/root allocation-policy-root
              :target-map/root (:target-map/root target-map)
              :mapping-profile/root (:mapping-profile/root target-map)
              :effect-compilation-semantics/root effect-compilation-semantics-root
              :effects/root (effects/effect-root effects)}]
    (when-not (and (every? string? (vals (dissoc base :schema-version)))
                   (= (:target-map/root target-map) (target-map/target-map-root target-map)))
      (throw (ex-info "invalid v2 compilation dependencies" {})))
    (assoc base :effect-compilation/root (compilation-root base))))
