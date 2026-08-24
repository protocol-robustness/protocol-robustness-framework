(ns resolver-sim.pro-rata.target-map
  "Closed target-map and native-location contracts.

   Target maps relate allocation subjects to canonical quantities. Native
   locations are a separate, adapter-specific relation from quantities to exact
   native leaf paths. Neither artifact performs native reconstruction."
  (:require [resolver-sim.hash.canonical :as hc]))

(def target-map-schema "allocation-quantity-target-map.v1")
(def location-map-schema "canonical-quantity-native-location-map.v1")
(def validation-schema "allocation-quantity-target-map-validation.v1")
(def one-to-one-profile :allocation-target-map/one-to-one.v1)

(defn- root? [value]
  (and (string? value) (re-matches #"(?:sha256:)?[0-9a-f]{64}" value)))

(defn- canonical-key [value]
  (mapv #(bit-and (int %) 0xff) (hc/canonical-bytes value)))

(defn- exact-leaf-path? [path]
  (and (vector? path) (seq path)
       (try
         (doseq [component path] (hc/validate-canonical-value! component))
         true
         (catch clojure.lang.ExceptionInfo _ false))))

(defn target-map-root [target-map]
  (hc/domain-hash :allocation-quantity-target-map
                  (select-keys target-map [:schema-version :allocation-subjects/root
                                           :scope/root :mapping-profile/root :targets])))

(defn build-target-map
  "Build the initial one-to-one target map. A subject can have distinct mapping
   roles (for example :filled and :outstanding), but each [subject role] and
   each quantity are unique."
  [{:keys [allocation-subjects-root scope-root mapping-profile-root targets] :as input}]
  (when-not (and (root? allocation-subjects-root) (root? scope-root)
                 (root? mapping-profile-root) (vector? targets))
    (throw (ex-info "invalid target-map inputs" {:input input})))
  (doseq [target targets]
    (when-not (= #{:allocation/subject-id :mapping/role :quantity/root} (set (keys target)))
      (throw (ex-info "target-map target must have closed shape" {:target target})))
    (when-not (and (keyword? (:mapping/role target)) (root? (:quantity/root target)))
      (throw (ex-info "invalid target-map target" {:target target})))
    (hc/validate-canonical-value! (:allocation/subject-id target)))
  (let [subject-roles (map (juxt :allocation/subject-id :mapping/role) targets)
        quantities (map :quantity/root targets)]
    (when-not (= (count targets) (count (distinct subject-roles)))
      (throw (ex-info "duplicate allocation subject/role target" {:targets targets})))
    (when-not (= (count targets) (count (distinct quantities)))
      (throw (ex-info "duplicate canonical quantity target" {:targets targets})))
    (let [base {:schema-version target-map-schema
                :allocation-subjects/root allocation-subjects-root
                :scope/root scope-root
                :mapping-profile/root mapping-profile-root
                :targets (vec (sort-by (juxt (comp canonical-key :allocation/subject-id)
                                             :mapping/role)
                                       targets))}]
      (assoc base :target-map/root (target-map-root base)))))

(defn location-map-root [location-map]
  (hc/domain-hash :canonical-quantity-native-location-map
                  (select-keys location-map [:schema-version :scope/root
                                             :adapter/descriptor-root :locations])))

(defn build-location-map
  "Bind canonical quantities to exact native-map leaves. Paths are data only;
   their interpretation is restricted to exact map leaves by the proposed
   realization verifier."
  [{:keys [scope-root adapter-descriptor-root locations] :as input}]
  (when-not (and (root? scope-root) (root? adapter-descriptor-root) (vector? locations))
    (throw (ex-info "invalid native-location-map inputs" {:input input})))
  (doseq [location locations]
    (when-not (= #{:quantity/root :native/path} (set (keys location)))
      (throw (ex-info "native location must have closed shape" {:location location})))
    (when-not (and (root? (:quantity/root location))
                   (exact-leaf-path? (:native/path location)))
      (throw (ex-info "invalid native quantity location" {:location location}))))
  (when-not (= (count locations) (count (distinct (map :quantity/root locations))))
    (throw (ex-info "duplicate native location quantity" {:locations locations})))
  (when-not (= (count locations) (count (distinct (map :native/path locations))))
    (throw (ex-info "duplicate native location path" {:locations locations})))
  (let [base {:schema-version location-map-schema
              :scope/root scope-root
              :adapter/descriptor-root adapter-descriptor-root
              :locations (vec (sort-by (comp canonical-key :quantity/root) locations))}]
    (assoc base :native-location-map/root (location-map-root base))))

(defn validation-root [validation]
  (hc/domain-hash :allocation-quantity-target-map-validation
                  (select-keys validation [:schema-version :target-map/root
                                           :realized-allocation/root :scope/root
                                           :adapter/descriptor-root :mapping-profile/root
                                           :native-state-before/root
                                           :native-location-map/root])))

(defn validate-target-map
  "Bind a validated target map to an allocation, native-before snapshot, scope,
   adapter descriptor, and independently validated exact native locations.
   This validates closed identities and location coverage, not claim persistence
   or native-state read-back."
  [{:keys [target-map realized-allocation-root scope-root adapter-descriptor-root
           mapping-profile-root native-state-before-root native-location-map] :as input}]
  (when-not (and (= target-map-schema (:schema-version target-map))
                 (= (:target-map/root target-map) (target-map-root target-map))
                 (= location-map-schema (:schema-version native-location-map))
                 (= (:native-location-map/root native-location-map)
                    (location-map-root native-location-map))
                 (every? root? [realized-allocation-root scope-root adapter-descriptor-root
                                mapping-profile-root native-state-before-root]))
    (throw (ex-info "invalid target-map validation inputs" {:input input})))
  (when-not (and (= scope-root (:scope/root target-map) (:scope/root native-location-map))
                 (= adapter-descriptor-root (:adapter/descriptor-root native-location-map))
                 (= mapping-profile-root (:mapping-profile/root target-map)))
    (throw (ex-info "target-map validation dependency mismatch" {})))
  (let [target-quantities (set (map :quantity/root (:targets target-map)))
        location-quantities (set (map :quantity/root (:locations native-location-map)))]
    (when-not (= target-quantities location-quantities)
      (throw (ex-info "target-map and native-location-map quantities differ"
                      {:target-quantities target-quantities
                       :location-quantities location-quantities})))
    (let [base {:schema-version validation-schema
                :target-map/root (:target-map/root target-map)
                :realized-allocation/root realized-allocation-root
                :scope/root scope-root
                :adapter/descriptor-root adapter-descriptor-root
                :mapping-profile/root mapping-profile-root
                :native-state-before/root native-state-before-root
                :native-location-map/root (:native-location-map/root native-location-map)}]
      (assoc base :target-map-validation/root (validation-root base)))))
