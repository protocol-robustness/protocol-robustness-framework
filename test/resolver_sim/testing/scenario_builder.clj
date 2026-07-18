(ns resolver-sim.testing.scenario-builder
  (:require [resolver-sim.protocols.sew :as sew]))

(def alice    {:id "alice"    :type "honest"        :address "0xAlice"})
(def bob      {:id "bob"      :type "honest"        :address "0xBob"})
(def mallory  {:id "mallory"  :type "attacker"      :address "0xMallory"})
(def resolver {:id "resolver" :type "honest"        :address "0xResolver"})

(def default-params
  {:resolver-fee-bps 50 :appeal-window-duration 0
   :max-dispute-duration 2592000 :appeal-bond-protocol-fee-bps 0
   :resolver-bond-bps 0})

(defn sc [& {:keys [agents params init-time events schema-version allow-open-disputes?]
             :or   {agents       [alice bob resolver]
                    params       default-params
                    init-time    1000
                    schema-version "1.0"}}]
  (cond-> {:scenario-id        "test"
           :schema-version     schema-version
           :seed               42
           :agents             agents
           :protocol-params    params
           :initial-block-time init-time
           :events             events}
    allow-open-disputes? (assoc :allow-open-disputes? true)))

(defn spe-projection
  "Build a minimal projection suitable for SPE evaluation, with a real raw-trace
   and decisions so subgame_counterfactual fires.

   :pre-wealth  — agent wealth at the decision node's pre-state (default 100)
   :chosen-wealth — agent wealth at the decision node's post-state (default 200)
   Regret = max(0, pre-wealth - chosen-wealth) when chosen < pre."
  [{:keys [pre-wealth chosen-wealth agent action regret-threshold]
    :or {pre-wealth 0 chosen-wealth 200 agent "resolver"
         action "execute_resolution" regret-threshold 0}}]
  (let [attr {:ctx/run-id "test-run" :ctx/scenario-id "test" :ctx/event-index 1 :ctx/event-type action}]
    {:raw-trace [{:world {:claimable {"e1" {agent pre-wealth}}} :seq 0 :attribution attr}
                 {:world {:claimable {"e1" {agent chosen-wealth}}} :seq 1 :attribution attr}]
     :decisions [{:seq 1 :agent agent :action action}]
     :terminal-world {:terminal? true
                      :total-held-by-token {}
                      :escrow-count 1}
     :metrics {:attack-attempts 0 :attack-successes 0 :funds-lost 0
               :invariant-violations 0}
     :trace-summary {:halt-reason :all-terminal :events-count 2
                     :actors [agent] :terminal-time 1100}
     :deviation-bundle {:meets-minimum? true}
     :spe-config {:regret-threshold regret-threshold}
     :protocol (sew/->SewProtocol)
     :agents [alice bob resolver]}))
