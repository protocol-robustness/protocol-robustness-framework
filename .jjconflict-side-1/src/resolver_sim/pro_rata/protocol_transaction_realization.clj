(ns resolver-sim.pro-rata.protocol-transaction-realization
  "Join commitment for two independent views of one canonical transition.

   This namespace intentionally has no transition, trace, or protocol-state
   semantics of its own. It only establishes that a bounded transaction witness
   and a protocol realization refer to the same canonical transition."
  (:require [resolver-sim.hash.canonical :as hc]))

(def schema-version "protocol-transaction-realization.v1")

(defn realization-root [realization]
  (hc/domain-hash :protocol-transaction-realization
                  (select-keys realization [:schema-version
                                            :canonical-transition/root
                                            :transition-binding/root
                                            :protocol-effect-realization/root
                                            :binding/mode])))

(defn build
  [{:keys [canonical-transition-root transition-binding protocol-effect-realization]}]
  (let [binding-transition (:canonical-transition/root transition-binding)
        protocol-transition (:canonical-transition/root protocol-effect-realization)
        binding-mode (:binding/mode transition-binding)]
    (when-not (and (string? canonical-transition-root)
                   (= canonical-transition-root binding-transition protocol-transition)
                   (contains? #{:effect-exact :net-equivalent} binding-mode)
                   (string? (:transition-binding/root transition-binding))
                   (string? (:protocol-effect-realization/root protocol-effect-realization)))
      (throw (ex-info "transaction binding and protocol realization disagree"
                      {:canonical-transition/root canonical-transition-root
                       :binding-transition binding-transition
                       :protocol-transition protocol-transition})))
    (let [base {:schema-version schema-version
                :canonical-transition/root canonical-transition-root
                :transition-binding/root (:transition-binding/root transition-binding)
                :protocol-effect-realization/root
                (:protocol-effect-realization/root protocol-effect-realization)
                ;; Derived projection only. This composition never selects mode.
                :binding/mode binding-mode}]
      (assoc base :protocol-transaction-realization/root (realization-root base)))))

(defn valid?
  [realization transition-binding protocol-effect-realization]
  (try
    (= realization
       (build {:canonical-transition-root (:canonical-transition/root realization)
               :transition-binding transition-binding
               :protocol-effect-realization protocol-effect-realization}))
    (catch Exception _ false)))
