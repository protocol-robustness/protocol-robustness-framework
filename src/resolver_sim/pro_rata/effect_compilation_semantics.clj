(ns resolver-sim.pro-rata.effect-compilation-semantics
  "Closed compiler-owned semantics for pro-rata effect compilation.

   Allocation policy remains allocation-owned; this body owns only the finite
   dispatch from a target-map contract to the compiler interpretation."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.pro-rata.target-map :as target-map]))

(def schema-version "pro-rata-effect-compilation-semantics.v1")

(def ^:private fields
  #{:schema-version :effect-compilation/profile :target-map/schema})

(def ^:private profiles
  {:all-active target-map/target-map-schema
   :aggregate-held-credit target-map/aggregate-target-map-schema})

(defn semantics-root [body]
  (hc/domain-hash :pro-rata-effect-compilation-semantics
                  (select-keys body fields)))

(defn build [profile]
  (let [target-map-schema (get profiles profile)]
    (when-not target-map-schema
      (throw (ex-info "unsupported effect compilation profile" {:profile profile})))
    (let [base {:schema-version schema-version
                :effect-compilation/profile profile
                :target-map/schema target-map-schema}]
      (assoc base :effect-compilation-semantics/root (semantics-root base)))))

(defn valid? [body]
  (and (= (conj fields :effect-compilation-semantics/root) (set (keys body)))
       (= schema-version (:schema-version body))
       (= (get profiles (:effect-compilation/profile body)) (:target-map/schema body))
       (= (:effect-compilation-semantics/root body) (semantics-root body))))
