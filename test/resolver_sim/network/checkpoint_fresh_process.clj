(ns resolver-sim.network.checkpoint-fresh-process
  (:require [clojure.edn :as edn]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as reference]
            [resolver-sim.network.checkpoint :as checkpoint]))

(defn- hash-ref [domain value]
  (reference/sha256-ref (canonical/domain-hash domain value)))

(defn -main [package-path]
  (let [{:keys [checkpoint certificate predecessor policy membership events]}
        (edn/read-string (slurp package-path))
        context {:network/genesis-root (:network/genesis-root checkpoint)
                 :predecessor predecessor
                 :policy policy
                 :membership membership
                 :events events
                 :verify-event (fn [event]
                                 {:valid? (= (:event/root event)
                                             (hash-ref (:event/schema event)
                                                       (dissoc event :event/root)))})
                 :verify-completeness (fn [_prior roots _policy]
                                        {:valid? (= (set roots)
                                                    (set (:state/accepted-event-roots predecessor)))})
                 :replay (fn [_prior ordered _policy _membership]
                           (let [roots (mapv :event/root ordered)
                                 state-root (hash-ref "checkpoint-test-state.v1"
                                                      {:accepted-event-roots roots})]
                             {:valid? true
                              :dag-state-root state-root
                              :frontier-roots roots
                              :resulting-policy-root (:checkpoint/resulting-policy-root checkpoint)
                              :resulting-membership-root
                              (:checkpoint/resulting-membership-root checkpoint)
                              :resulting-epoch (:checkpoint/resulting-epoch checkpoint)
                              :state {:state/accepted-event-roots (set roots)}}))}
        result (checkpoint/verify-checkpoint checkpoint certificate context)]
    (println (pr-str (select-keys result [:status :valid? :errors :checkpoint/root])))))
