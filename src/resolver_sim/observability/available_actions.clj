(ns resolver-sim.observability.available-actions
  "Generic rooted observation of protocol action availability."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.protocols.protocol :as protocol]))

(def ^:const schema "available-actions-observation.v1")

(defn- action-key [action]
  (pr-str action))

(defn observation-root [observation]
  (hash-ref/sha256-ref
   (hc/domain-hash :available-actions-observation
                   (dissoc observation :observation/root))))

(defn build
  "Normalize and root protocol-owned action availability for one actor/state."
  [input]
  (let [protocol-id (:protocol/id input)
        state-root (:state/root input)
        actor-id (:actor/id input)
        actions (:actions input)]
    (when-not (and (string? protocol-id) (hash-ref/valid-sha256-ref? state-root)
                   (some? actor-id) (vector? actions)
                   (every? map? actions))
      (throw (ex-info "Invalid available-actions observation"
                      {:reason :available-actions/invalid-result})))
    (let [observation {:artifact/schema schema
                       :protocol/id protocol-id
                       :state/root state-root
                       :actor/id actor-id
                       :available-actions (vec (sort-by action-key actions))}]
      (assoc observation :observation/root (observation-root observation)))))

(defn observe
  "Call the protocol capability and immediately convert its result into a rooted
   observation. The protocol remains the owner of action semantics."
  [adapter world state-root actor]
  (build {:protocol/id (protocol/protocol-id adapter)
          :state/root state-root
          :actor/id actor
          :actions (vec (protocol/available-actions adapter world actor))}))

(defn acknowledge
  "Bind an acknowledgement to the exact observed action set."
  [observation actor]
  (when-not (= actor (:actor/id observation))
    (throw (ex-info "Available-actions acknowledgement actor mismatch"
                    {:reason :available-actions/actor-mismatch})))
  {:artifact/schema "available-actions-acknowledgement.v1"
   :observation/root (:observation/root observation)
   :actor/id actor
   :acknowledged? true})
