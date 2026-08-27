(ns resolver-sim.pro-rata.proposed-realization
  "Core-authorized modeled reconstruction boundary.

   The native-after value is a proposed/modelled reconstruction only. This
   namespace performs no persistence, write-back, read-back, transaction, or
   execution-history attestation."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.pro-rata.canonical-effects :as effects]
            [resolver-sim.pro-rata.execution-context :as context]
            [resolver-sim.pro-rata.target-map :as target-map]))

(def schema-version "core-authorized-proposed-realization.v1")
(def write-set-schema "exact-native-leaf-write-set.v1")
(def actual-write-set-schema "actual-native-leaf-write-set.v1")
(def ^:private absent (Object.))

(defn- canonical-key [value]
  (mapv #(bit-and (int %) 0xff) (hc/canonical-bytes value)))

(defn- sort-paths [paths]
  (vec (sort-by canonical-key paths)))

(defn- location-index [location-map]
  (into {} (map (juxt :quantity/root :native/path) (:locations location-map))))

(defn exact-write-set-root [paths]
  (hc/domain-hash :exact-native-leaf-write-set
                  {:schema-version write-set-schema :paths (sort-paths paths)}))

(defn actual-write-set-root [paths]
  (hc/domain-hash :actual-native-leaf-write-set
                  {:schema-version actual-write-set-schema :paths (sort-paths paths)}))

(defn changed-leaf-paths
  "Map-only exact-leaf diff that distinguishes absent keys from keys whose
   values are nil. Vectors/lists are treated as indivisible leaves."
  [before after]
  (letfn [(walk [path left right]
            (cond
              (= left right) []
              (and (map? left) (map? right))
              (mapcat (fn [key]
                        (walk (conj path key)
                              (get left key absent)
                              (get right key absent)))
                      (sort-by canonical-key (into #{} (concat (keys left) (keys right)))))
              :else [path]))]
    (sort-paths (walk [] before after))))

(defn derive-authorized-write-set
  "Core-derived write authority: every normalized nonzero affected quantity
   must have one validated exact native leaf location."
  [canonical-transition native-location-map]
  (let [locations (location-index native-location-map)
        normalized (effects/normalize-effects (:effects canonical-transition))
        quantities (map :quantity/root normalized)
        paths (mapv #(get locations %) quantities)]
    (when (or (some nil? paths) (not= (count paths) (count (distinct paths))))
      (throw (ex-info "effects do not map one-to-one to validated native leaves"
                      {:quantities quantities :paths paths})))
    (sort-paths paths)))

(defn proposed-realization-root [realization]
  (hc/domain-hash :core-authorized-proposed-realization
                  (select-keys realization [:schema-version
                                            :adapter-execution-context/root
                                            :target-map-validation/root
                                            :canonical-transition/root
                                            :native-state-before/root
                                            :native-state-after/root
                                            :authorized-write-set/root
                                            :actual-write-set/root])))

(defn build
  "Validate a proposed native reconstruction against core-derived exact leaves.

   `propose-native-after` may later be fed a bounded read view and return an
   exact-leaf patch proposal; this initial API receives the full modeled state
   and returns a modeled state only."
  [{:keys [execution-context target-map-validation native-location-map
           canonical-transition native-before native-state-root
           propose-native-after]}]
  (when-not (and (context/valid-context? execution-context)
                 (= (:target-map-validation/root target-map-validation)
                    (target-map/validation-root target-map-validation))
                 (= (:native-location-map/root native-location-map)
                    (target-map/location-map-root native-location-map))
                 (= (:adapter/descriptor-root execution-context)
                    (:adapter/descriptor-root target-map-validation)
                    (:adapter/descriptor-root native-location-map))
                 (= (:native-state-before/root target-map-validation)
                    (native-state-root native-before))
                 (= (:canonical-effect-transition/root canonical-transition)
                    (effects/transition-root canonical-transition))
                 (fn? propose-native-after))
    (throw (ex-info "invalid proposed realization dependencies" {})))
  (let [authorized-paths (derive-authorized-write-set canonical-transition native-location-map)
        native-after (propose-native-after native-before (:state-after canonical-transition))
        actual-paths (changed-leaf-paths native-before native-after)]
    (when-not (every? (set authorized-paths) actual-paths)
      (throw (ex-info "proposed native reconstruction changed unauthorized leaf"
                      {:authorized-paths authorized-paths :actual-paths actual-paths})))
    (let [base {:schema-version schema-version
                :adapter-execution-context/root (:adapter-execution-context/root execution-context)
                :target-map-validation/root (:target-map-validation/root target-map-validation)
                :canonical-transition/root (:canonical-effect-transition/root canonical-transition)
                :native-state-before/root (native-state-root native-before)
                :native-state-after/root (native-state-root native-after)
                :authorized-write-set/root (exact-write-set-root authorized-paths)
                :actual-write-set/root (actual-write-set-root actual-paths)}]
      (assoc base
             :proposed-native-after native-after
             :core-authorized-proposed-realization/root (proposed-realization-root base)))))
