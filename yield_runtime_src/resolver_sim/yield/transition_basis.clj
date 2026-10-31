(ns resolver-sim.yield.transition-basis
  "Content-addressed, fail-closed yield transition addressing."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.yield.commitment-projection :as cp]))

(def schema cp/transition-basis-schema)

(defn- finite-number? [x]
  (and (number? x) (not (and (instance? Double x) (not (Double/isFinite x))))
       (not (and (instance? Float x) (not (Float/isFinite x))))))

(defn- integer-valued?
  "True when a committed amount is a whole-unit value regardless of host
   representation: an integer, or an exact ratio with denominator 1 (e.g.
   `10000/1`).  Genuinely fractional amounts are rejected by design."
  [v]
  (or (integer? v)
      (and (ratio? v) (= 1 (denominator v)))))

(defn validate-yield-state [world]
  (let [bad-amounts (mapcat (fn [[owner pos]]
                              (concat
                               (for [field [:principal :shares :realized-yield :unrealized-yield]
                                     :let [v (get pos field 0)] :when (not (integer-valued? v))]
                                 {:path [:yield/positions owner field] :observed v})
                               (for [[field v] (:shortfall pos)
                                     :when (and (#{:basis-amount :fulfilled-amount :deferred-amount
                                                   :haircut-amount :settlement-value :basis-negative-unrealized} field)
                                                (not (integer-valued? v)))]
                                 {:path [:yield/positions owner :shortfall field] :observed v})))
                            (:yield/positions world {}))
        bad-ledger-amounts (mapcat (fn [[ledger-index ledger]]
                                     (concat
                                      (for [field [:ledger/available :ledger/requested :ledger/filled
                                                   :ledger/deferred :ledger/haircut]
                                            :let [v (get ledger field 0)] :when (not (integer-valued? v))]
                                        {:path [:yield/withdrawal-ledger ledger-index field] :observed v})
                                      (mapcat (fn [[row-index row]]
                                                (for [field [:requested :filled :deferred :haircut]
                                                      :let [v (get row field 0)] :when (not (integer-valued? v))]
                                                  {:path [:yield/withdrawal-ledger ledger-index :ledger/rows row-index field]
                                                   :observed v}))
                                              (map-indexed vector (:ledger/rows ledger [])))))
                                   (map-indexed vector (:yield/withdrawal-ledger world [])))
        bad-decision-amounts (mapcat (fn [[decision-id decision]]
                                       (mapcat (fn [field]
                                                 (for [[owner amount] (get decision field {})
                                                       :when (not (integer-valued? amount))]
                                                   {:path [:yield/partial-fill-decisions decision-id field owner]
                                                    :observed amount}))
                                               [:requested :filled :deferred :haircut :unrealized]))
                                     (:yield/partial-fill-decisions world {}))
        bad-indices (for [[module tokens] (:yield/indices world {}) [token value] tokens
                          :when (not (finite-number? value))]
                      {:path [:yield/indices module token] :observed value})]
    {:holds? (and (empty? bad-amounts) (empty? bad-ledger-amounts)
                  (empty? bad-decision-amounts) (empty? bad-indices))
     :violations (vec (concat bad-amounts bad-ledger-amounts bad-decision-amounts bad-indices))}))

(defn yield-state-root [world]
  (hc/domain-hash :prf-yield-state-v1
                  (cp/project-yield-state world)))

(defn effective-policy-root [world]
  (hc/domain-hash :prf-yield-effective-policy-v1
                  (cp/project-effective-policy world)))

(defn event-root [event]
  (hc/domain-hash :prf-yield-transition-event-v1
                  (cp/project-event event)))

(defn transition-root [basis]
  (hc/domain-hash :prf-yield-transition-basis-v1
                  (select-keys basis [:yield-transition/schema :state-before/root :state-after/root
                                      :event/root :yield/effective-policy-root])))

(defn- build-impl
  "Pure transition-basis construction.  `validate` recomputes its expected
   basis from this private implementation so a redefined/forged public `build`
   cannot poison the reconstruction it is supposed to check against."
  [world-before world-after event]
  (let [basis {:yield-transition/schema schema
               :state-before/root (yield-state-root world-before)
               :state-after/root (yield-state-root world-after)
               :event/root (event-root event)
               ;; Existing transition semantics consult policy-before; bind it explicitly.
               :yield/effective-policy-root (effective-policy-root world-before)}]
    (assoc basis :transition/root (transition-root basis) :root (transition-root basis))))

(defn build [world-before world-after event]
  (build-impl world-before world-after event))

(defn validate [world-before world-after event basis]
  (let [before (validate-yield-state world-before) after (validate-yield-state world-after)
        expected (build-impl world-before world-after event)
        errors (cond-> (vec (concat (map #(assoc % :reason :invalid-state-before) (:violations before))
                                    (map #(assoc % :reason :invalid-state-after) (:violations after))))
                 (not= schema (:yield-transition/schema basis)) (conj {:reason :invalid-schema})
                 (not= (:state-before/root expected) (:state-before/root basis)) (conj {:reason :state-before-root-mismatch})
                 (not= (:state-after/root expected) (:state-after/root basis)) (conj {:reason :state-after-root-mismatch})
                 (not= (:event/root expected) (:event/root basis)) (conj {:reason :event-root-mismatch})
                 (not= (:yield/effective-policy-root expected) (:yield/effective-policy-root basis)) (conj {:reason :effective-policy-root-mismatch})
                 (not= (:transition/root expected) (:transition/root basis)) (conj {:reason :transition-root-mismatch}))]
    {:holds? (empty? errors) :violations errors}))
