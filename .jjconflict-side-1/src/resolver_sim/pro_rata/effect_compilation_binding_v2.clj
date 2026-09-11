(ns resolver-sim.pro-rata.effect-compilation-binding-v2
  "Closed binding for V3 compiler artifacts and their verified source bodies."
  (:require [resolver-sim.pro-rata.canonical-effects :as effects]
            [resolver-sim.pro-rata.effect-compilation-v3 :as compilation]
            [resolver-sim.hash.canonical :as hc]))

(def schema-version "effect-compilation-binding.v2")
(def ^:private fields #{:effect-compilation-binding/schema :effect-compilation/root
                        :canonical-transition/root})

(defn binding-root [binding]
  (hc/domain-hash :effect-compilation-binding-v2 (select-keys binding fields)))

(defn- body! [resolve-body root kind]
  (or (resolve-body root)
      (throw (ex-info "retained effect compilation body is unavailable" {:root root :kind kind}))))

(defn recompute [artifact resolve-body]
  (let [allocation (body! resolve-body (:realized-allocation/root artifact) :allocation)
        target-map (body! resolve-body (:target-map/root artifact) :target-map)
        semantics (body! resolve-body (:effect-compilation-semantics/root artifact) :semantics)]
    (when-not (and (= (:allocation/hash allocation) (:realized-allocation/root artifact))
                   (= (:target-map/root target-map) (:target-map/root artifact))
                   (= (:effect-compilation-semantics/root semantics)
                      (:effect-compilation-semantics/root artifact)))
      (throw (ex-info "retained body root does not match compilation" {})))
    (compilation/compile {:allocation allocation :target-map target-map :semantics semantics
                          :allocation-policy-root (:allocation-policy/root artifact)})))

(defn build [artifact canonical-transition]
  (let [base {:effect-compilation-binding/schema schema-version
              :effect-compilation/root (:effect-compilation/root artifact)
              :canonical-transition/root (:canonical-effect-transition/root canonical-transition)}]
    (assoc base :effect-compilation-binding/root (binding-root base))))

(defn valid? [binding artifact canonical-transition resolve-body]
  (try
    (let [expected (recompute artifact resolve-body)]
      (and (= (conj fields :effect-compilation-binding/root) (set (keys binding)))
           (= schema-version (:effect-compilation-binding/schema binding))
           (= (:effect-compilation-binding/root binding) (binding-root binding))
           (= (:effect-compilation/root binding) (:effect-compilation/root artifact))
           (= expected artifact)
           (= (:effects/root artifact) (:effects/root canonical-transition))
           (= (:canonical-effect-transition/root canonical-transition)
              (effects/transition-root canonical-transition))
           (= (:canonical-transition/root binding) (:canonical-effect-transition/root canonical-transition))))
    (catch clojure.lang.ExceptionInfo _ false)))
