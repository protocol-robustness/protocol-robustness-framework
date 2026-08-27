(ns resolver-sim.pro-rata.modeled-numeric-realization
  "Typed, modeled numeric realization for an aggregate canonical quantity.

   This boundary proves only a proposed numeric projection over exact native
   leaves. It performs no persistence, write-back, read-back(ns resolver-sim.pro-rata.modeled-numeric-realization), append-history,
   custody-artifact, transaction, or execution-history operation."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.pro-rata.canonical-effects :as effects]
            [resolver-sim.pro-rata.effect-compilation-v2 :as compilation]
            [resolver-sim.pro-rata.proposed-realization :as proposed]
            [resolver-sim.pro-rata.quantity :as quantity]
            [resolver-sim.pro-rata.target-map :as target-map]))

(def schema-version "aggregate-numeric-custody-realization.v1")
(def numeric-projection-schema "aggregate-custody-numeric-projection.v1")
(def assurance-mode :modeled-numeric-projection)
(def ^:private absent (Object.))

(defn native-state-root
  "Root the complete modeled native snapshot through the established canonical
   world-structure projection; raw Sew worlds can carry runtime temporal values."
  [native-state]
  (hc/domain-hash :world-state
                  (hc/project-world-to-structure-view native-state :world-structure)))

(defn numeric-projection-root [quantity-root amount]
  (hc/domain-hash :aggregate-custody-numeric-projection
                  {:schema-version numeric-projection-schema
                   :quantity/root quantity-root
                   :quantity/amount amount}))

(defn numeric-realization-root [realization]
  (hc/domain-hash :aggregate-numeric-custody-realization
                  (select-keys realization [:schema-version
                                            :assurance/mode
                                            :adapter/descriptor-root
                                            :target-map-validation/root
                                            :effect-compilation/root
                                            :canonical-transition/root
                                            :aggregate-quantity/root
                                            :native-state-before/root
                                            :numeric-projection-before/root
                                            :numeric-projection-after/root
                                            :authoritative-native-location
                                            :derived-mirror-profile/root
                                            :authorized-numeric-write-set/root])))

(defn- present-value [state path]
  (reduce (fn [value key]
            (if (map? value)
              (get value key absent)
              absent))
          state path))

(defn- valid-amount? [amount]
  (and (integer? amount) (not (neg? amount))))

(defn- require-amount! [native-state path label]
  (let [amount (present-value native-state path)]
    (when (or (identical? absent amount) (nil? amount) (not (valid-amount? amount)))
      (throw (ex-info "numeric native leaf must be present and non-negative"
                      {:label label :path path :value (when-not (identical? absent amount) amount)})))
    amount))

(defn- authoritative-path [native-location-map quantity-root token]
  (let [matches (filter #(= quantity-root (:quantity/root %)) (:locations native-location-map))
        path (:native/path (first matches))
        expected [:held-ledger/index :by-token token]]
    (when-not (and (= 1 (count matches)) (= expected path))
      (throw (ex-info "aggregate quantity does not have its approved authoritative native path"
                      {:expected expected :matches (vec matches)})))
    path))

(defn- checked-dependencies!
  [{:keys [adapter-descriptor target-map-validation aggregate-target-map native-location-map
           aggregate-quantity compilation canonical-transition canonical-before token]}]
  (let [checks {:quantity-identity (quantity/valid-identity? aggregate-quantity)
                :descriptor-roots (= (:adapter/descriptor-root adapter-descriptor)
                                     (:adapter/descriptor-root target-map-validation)
                                     (:adapter/descriptor-root native-location-map))
                :validation-root (= (:target-map-validation/root target-map-validation)
                                    (target-map/aggregate-validation-root target-map-validation))
                :target-map-roots (= (:target-map/root aggregate-target-map)
                                     (:target-map/root target-map-validation)
                                     (:target-map/root compilation))
                :mapping-profile-roots (= (:mapping-profile/root aggregate-target-map)
                                          (:mapping-profile/root target-map-validation)
                                          (:mapping-profile/root compilation))
                :aggregate-quantity (= (:aggregate-quantity/root target-map-validation)
                                       (:quantity/root aggregate-quantity))
                :location-map-root (= (:native-location-map/root native-location-map)
                                      (target-map/location-map-root native-location-map))
                :compilation-root (= (:effect-compilation/root compilation)
                                     (compilation/compilation-root compilation))
                :compilation-effects (= (:effects/root compilation) (effects/effect-root (:effects compilation)))
                :transition-root (= (:canonical-effect-transition/root canonical-transition)
                                    (effects/transition-root canonical-transition))
                :transition-effects (= (:effects/root compilation) (:effects/root canonical-transition))
                :transition-before (= (:state-before/root canonical-transition) (effects/state-root canonical-before))
                :transition-after (= (:state-after/root canonical-transition)
                                     (effects/state-root (:state-after canonical-transition)))
                :token (keyword? token)}]
    (when-not (every? true? (vals checks))
      (throw (ex-info "numeric realization typed dependency mismatch"
                      {:failed (vec (for [[key valid?] checks :when (not valid?)] key))})))))

(defn build
  "Build a numeric-only proposed SEW realization.

   The core derives exactly two authorized leaves from the validated authoritative
   quantity location and the fixed token mirror rule. The returned candidate is
   not a complete legacy Sew state and its full after-state root is intentionally
   not committed by the typed realization artifact."
  [{:keys [adapter-descriptor target-map-validation native-location-map
           aggregate-quantity compilation canonical-transition canonical-before native-before token
           derived-mirror-profile-root] :as input}]
  (when (contains? input :complete-legacy-state?)
    (throw (ex-info "numeric candidate cannot claim complete legacy realization" {})))
  (checked-dependencies! input)
  (when-not (and (string? derived-mirror-profile-root)
                 (= (:quantity/root aggregate-quantity)
                    (:aggregate-quantity/root target-map-validation)))
    (throw (ex-info "invalid numeric realization profile dependency" {})))
  (let [quantity-root (:quantity/root aggregate-quantity)
        authoritative (authoritative-path native-location-map quantity-root token)
        mirror [:total-held token]
        before-authoritative (require-amount! native-before authoritative :authoritative)
        before-mirror (require-amount! native-before mirror :mirror)
        canonical-before-amount (get canonical-before quantity-root absent)
        canonical-after-amount (get (:state-after canonical-transition) quantity-root absent)]
    (when-not (and (= before-authoritative before-mirror canonical-before-amount)
                   (valid-amount? canonical-before-amount)
                   (valid-amount? canonical-after-amount))
      (throw (ex-info "native numeric projection does not agree with canonical transition"
                      {:authoritative before-authoritative :mirror before-mirror
                       :canonical-before canonical-before-amount
                       :canonical-after canonical-after-amount})))
    (let [candidate (-> native-before
                        (assoc-in authoritative canonical-after-amount)
                        (assoc-in mirror canonical-after-amount))
          authorized-paths [authoritative mirror]
          actual-paths (proposed/changed-leaf-paths native-before candidate)]
      (when-not (= (set authorized-paths) (set actual-paths))
        (throw (ex-info "numeric candidate changed leaves outside exact derived write set"
                        {:authorized authorized-paths :actual actual-paths})))
      (let [base {:schema-version schema-version
                  :assurance/mode assurance-mode
                  :adapter/descriptor-root (:adapter/descriptor-root adapter-descriptor)
                  :target-map-validation/root (:target-map-validation/root target-map-validation)
                  :effect-compilation/root (:effect-compilation/root compilation)
                  :canonical-transition/root (:canonical-effect-transition/root canonical-transition)
                  :aggregate-quantity/root quantity-root
                  :native-state-before/root (native-state-root native-before)
                  :numeric-projection-before/root (numeric-projection-root quantity-root before-authoritative)
                  :numeric-projection-after/root (numeric-projection-root quantity-root canonical-after-amount)
                  :authoritative-native-location authoritative
                  :derived-mirror-profile/root derived-mirror-profile-root
                  :authorized-numeric-write-set/root (proposed/exact-write-set-root authorized-paths)}]
        (assoc base
               :modeled-numeric-candidate candidate
               :aggregate-numeric-custody-realization/root (numeric-realization-root base))))))
