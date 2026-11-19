(ns resolver-sim.protocols.sew.action-context
  "Shared action precondition helpers for Sew handlers."
  (:require [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.protocols.common.action-context :as common-actx]
            [resolver-sim.time.context :as time-ctx]))

(defn resolve-address
  "Return {:ok true :address addr} or {:ok false :error :unknown-agent}.
   Never throws."
  [agent-index agent-id]
  (if-let [agent (get agent-index agent-id)]
    {:ok true :address (:address agent)}
    {:ok false :error :unknown-agent :detail {:agent-id agent-id}}))

(defn check-paused
  [world]
  (if (:paused? world)
    (t/fail :protocol-paused)
    {:ok true}))

(defn with-resolved-actor
  "Resolve event actor and call (f actor-address).
   Returns the unresolved actor error map unchanged when unknown."
  [agent-index event f]
  (common-actx/with-resolved-actor resolve-address agent-index event f))

(defn check-unfrozen
  [world actor]
  (if (> (get-in world [:resolver-frozen-until actor] 0) (time-ctx/block-ts world))
    (t/fail :resolver-frozen)
    {:ok true}))

(defn with-resolved-actor-and-unpaused
  "Resolve event actor, reject when world is paused or resolver frozen, then call (f actor-address)."
  [agent-index world event f]
  (common-actx/with-resolved-actor-and-check
    resolve-address
    #(let [paused? (check-paused world)
           unfrozen? (check-unfrozen world (get-in event [:params :resolver] (:agent event)))]
       (if (:ok paused?) unfrozen? paused?))
    agent-index event f))

(defn with-governance-actor
  "Resolve actor, run a reason-carrying governance check, then call
   (f actor-address actor-map check-result) when the check returns {:ok true}.
   The check must return either {:ok true ...} or {:ok false :error ...}; a
   non-ok check result is propagated unchanged (so e.g.
   :governance-identity-not-configured and :not-governance are distinguished).
   Never throws."
  [agent-index event check-fn f]
  (common-actx/with-resolved-actor
    resolve-address agent-index event
    (fn [addr]
      (let [actor (get agent-index (:agent event))
            result (check-fn addr actor)]
        (if (:ok result)
          (f addr actor result)
          result)))))
