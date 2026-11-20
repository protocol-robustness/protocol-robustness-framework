(ns resolver-sim.protocols.sew.invariant-scenarios.forking-strategist
  "Shared metadata, workflow aliases, and expectations for forking-strategist scenarios."
  (:require [resolver-sim.protocols.sew.invariant-scenarios.common :refer [kleros-appeal]]))

(def escrow-afa-6000 5910)

(defn with-workflow-alias
  "Bind first create_escrow to wf0 and use string workflow-id refs (alias-safe)."
  [events]
  (mapv (fn [e]
          (let [e (if (and (= "create_escrow" (:action e))
                           (not (:save-id-as e)))
                    (assoc e :save-id-as "wf0")
                    e)]
            (if-let [wf (:workflow-id (:params e))]
              (update-in e [:params :workflow-id]
                         #(if (number? %) "wf0" %))
              e)))
        events))

(defn theory-block
  [{:keys [claim-id claim assumptions falsifies-if-extra]}]
  {:claim-id     claim-id
   :claim        claim
   :assumptions  (or assumptions [:appeal-window-60s :kleros-escalation-ladder])
   :falsifies-if (into [{:metric :funds-lost :op :> :value 0}
                        {:metric :double-settlements :op :> :value 0}]
                       (or falsifies-if-extra []))})

(defn single-wf-expectations
  [{:keys [final-state claim-address claim-amount dispute-level step-terminal]}]
  (cond-> {:terminal (into [{:path ["live-states" 0] :equals final-state}
                            {:path ["claimable" 0 claim-address] :equals claim-amount}]
                           (when dispute-level
                             [{:path ["dispute-levels" 0] :equals dispute-level}]))}
    (seq step-terminal) (assoc :step-terminal step-terminal)))

(defn enrich-scenario
  "Merge evidence-bearing metadata and optional workflow aliases onto a scenario map."
  [scenario
   {:keys [scenario-title scenario-purpose description threat-model
           expected-outcome security-properties expectations notes theory
           strict-expected-errors? expected-errors alias-workflow-ids?]
    :or {alias-workflow-ids? true
         security-properties [:escalation-level-monotonic
                              :appeal-window-enforcement
                              :resolution-hash-isolation
                              :claimable-balance-isolation]}}]
  (cond-> (merge scenario
                 {:scenario-title     scenario-title
                  :scenario-family    "forking-strategist"
                  :scenario-purpose   scenario-purpose
                  :description        description
                  :purpose            :adversarial-robustness
                  :threat-tags        ["forking-strategist" "appeal-escalation"]
                  :security-properties security-properties
                  :threat-model       threat-model
                  :expected-outcome   expected-outcome
                  :notes              notes
                  :theory             theory
                  :expectations       expectations})
    (not (:protocol-params scenario)) (assoc :protocol-params kleros-appeal)
    strict-expected-errors? (assoc :strict-expected-errors? true)
    expected-errors (assoc :expected-errors expected-errors)
    alias-workflow-ids? (update :events with-workflow-alias)))
