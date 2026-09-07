(ns resolver-sim.pro-rata.effect-compilation-binding
  "Minimal commitment joining a reproducible effect compilation to its canonical
   transition. Reproducibility is limited to the closed compiler profiles below."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.pro-rata.canonical-effects :as effects]
            [resolver-sim.pro-rata.effect-compilation-v2 :as compilation]
            [resolver-sim.pro-rata.target-map :as target-map]))

(def schema-version "effect-compilation-binding.v1")

(def ^:private fields
  #{:effect-compilation-binding/schema :effect-compilation/root
    :canonical-transition/root})

(defn binding-root [binding]
  (hc/domain-hash :effect-compilation-binding-v1
                  (select-keys binding fields)))

(defn build [compilation-artifact canonical-transition]
  (let [base {:effect-compilation-binding/schema schema-version
              :effect-compilation/root (:effect-compilation/root compilation-artifact)
              :canonical-transition/root (:canonical-effect-transition/root canonical-transition)}]
    (when-not (and (= (:effect-compilation/root compilation-artifact)
                      (compilation/compilation-root compilation-artifact))
                   (= (:effects/root compilation-artifact)
                      (:effects/root canonical-transition))
                   (= (:canonical-effect-transition/root canonical-transition)
                      (effects/transition-root canonical-transition)))
      (throw (ex-info "effect compilation does not match canonical transition" {})))
    (assoc base :effect-compilation-binding/root (binding-root base))))

(def ^:private profile-by-target-map-schema
  {target-map/target-map-schema :all-active
   target-map/aggregate-target-map-schema :aggregate-held-credit})

(defn- resolve-body! [resolve-body root dependency]
  (let [body (resolve-body root)]
    (when-not body
      (throw (ex-info "retained effect compilation dependency is unavailable"
                      {:dependency dependency :root root})))
    body))

(defn- aggregate-validation [allocation aggregate-target-map]
  (let [base {:target-map/root (:target-map/root aggregate-target-map)
              :realized-allocation/root (:allocation/hash allocation)}]
    (assoc base :target-map-validation/root
           (target-map/aggregate-validation-root base))))

(defn- all-active-targets [allocation retained-target-map]
  (let [by-key (into {} (map (fn [target]
                               [[(:allocation/subject-id target) (:mapping/role target)]
                                (:quantity/root target)]))
                     (:targets retained-target-map))
        liquidity (get by-key [:allocation/liquidity :available])]
    (assoc (into {:liquidity/root liquidity}
                 (map (fn [row]
                        (let [row-id (:row/id row)]
                          [row-id {:filled/root (get by-key [row-id :filled])
                                   :outstanding/root (get by-key [row-id :outstanding])}]))
                      (:rows allocation)))
           :liquidity/root liquidity)))

(defn recompute
  "Reproduce a compilation from retained allocation and target-map bodies.
   The target-map schema is the closed profile discriminator; unknown schemas
   are rejected rather than inferred."
  [compilation-artifact resolve-body]
  (let [allocation (resolve-body! resolve-body (:realized-allocation/root compilation-artifact)
                                  :realized-allocation)
        retained-target-map (resolve-body! resolve-body (:target-map/root compilation-artifact)
                                           :target-map)
        profile (get profile-by-target-map-schema (:schema-version retained-target-map))]
    (when-not (= (:allocation/hash allocation)
                 (:realized-allocation/root compilation-artifact))
      (throw (ex-info "retained allocation does not match compilation"
                      {:allocation/root (:realized-allocation/root compilation-artifact)})))
    (when-not (= (:target-map/root retained-target-map)
                 (:target-map/root compilation-artifact))
      (throw (ex-info "retained target map does not match compilation"
                      {:target-map/root (:target-map/root compilation-artifact)})))
    (case profile
      :all-active
      (with-meta
        (compilation/compile-all-active
         {:allocation allocation
          :target-map retained-target-map
          :allocation-policy-root (:allocation-policy/root compilation-artifact)
          :effect-compilation-semantics-root (:effect-compilation-semantics/root compilation-artifact)})
        {:effects (effects/compile-pro-rata-effects allocation
                                                    (all-active-targets allocation retained-target-map))})

      :aggregate-held-credit
      (compilation/compile-aggregate-held-credit
       {:allocation allocation
        :aggregate-target-map retained-target-map
        :target-map-validation (aggregate-validation allocation retained-target-map)
        :aggregate-semantics-root (:effect-compilation-semantics/root compilation-artifact)
        :allocation-policy-root (:allocation-policy/root compilation-artifact)})

      (throw (ex-info "unsupported effect compilation profile"
                      {:target-map/schema (:schema-version retained-target-map)})))))

(defn valid?
  "Verify a binding only when its compilation is exactly reproducible from
   retained bodies. The resolver is `(fn [root] retained-body-or-nil)`; without
   it verification fails closed."
  ([binding compilation-artifact canonical-transition]
   false)
  ([binding compilation-artifact canonical-transition resolve-body]
   (try
     (let [expected (recompute compilation-artifact resolve-body)
           expected-effects (or (:effects expected) (-> expected meta :effects))]
       (and (= (conj fields :effect-compilation-binding/root) (set (keys binding)))
            (= schema-version (:effect-compilation-binding/schema binding))
            (= (:effect-compilation-binding/root binding) (binding-root binding))
            (= (:effect-compilation/root binding) (:effect-compilation/root compilation-artifact))
            (= (:canonical-transition/root binding)
               (:canonical-effect-transition/root canonical-transition))
            (= expected compilation-artifact)
            (= (:effect-compilation/root compilation-artifact)
               (compilation/compilation-root compilation-artifact))
            (= (:effects/root expected) (effects/effect-root expected-effects))
            (= (:effects/root expected) (:effects/root canonical-transition))
            (= expected-effects (:effects canonical-transition))
            (= (:canonical-effect-transition/root canonical-transition)
               (effects/transition-root canonical-transition))))
     (catch clojure.lang.ExceptionInfo _ false))))
