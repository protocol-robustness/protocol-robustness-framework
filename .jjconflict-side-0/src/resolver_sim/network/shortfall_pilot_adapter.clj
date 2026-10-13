(ns resolver-sim.network.shortfall-pilot-adapter
  "Application adapter composing the shortfall method gate with researcher-network replay.
   The arithmetic/method namespace stays independent of network actions and controls."
  (:require [resolver-sim.evidence.forensic-graph :as forensic]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as reference]
            [resolver-sim.network.researcher-challenges :as challenges]
            [resolver-sim.research.shortfall-pilot :as pilot]))

(defn- rooted [schema fields]
  (let [body (assoc fields :artifact/schema schema)]
    (assoc body :artifact/root
           (reference/sha256-ref (canonical/domain-hash schema body)))))

(defn build-profile
  "Validate a network configuration and build its unchanged method-specific pilot profile."
  [config comparison]
  (challenges/replay config [])
  (pilot/build-profile config comparison))

(defn build-controlled-profile
  "Compose the existing pilot profile with a frozen network control activation."
  [config profile control-policy activation-history]
  (pilot/validate-profile! config profile)
  (when-not (= control-policy (challenges/build-control-policy config activation-history))
    (throw (ex-info "invalid-control-policy" {:reason :invalid-control-policy})))
  (rooted "researcher-shortfall-controlled-pilot.v1"
          {:configuration/root (:artifact/root config)
           :pilot/profile profile
           :control-policy/root (:artifact/root control-policy)
           :activation/history-root (:activation/history-root control-policy)}))

(defn replay
  "Replay network history and apply the method-specific validation gate to submissions."
  [config profile history evidence]
  (let [state (challenges/replay config history)
        nodes (mapv #(pilot/validate-context! config profile (:challenge/root %)
                                              (get-in % [:action/payload :evidence/context]) evidence)
                    (filter #(= :submit (:action/kind %)) history))]
    (pilot/validate-profile! config profile)
    {:state state :replay/nodes nodes}))

(defn replay-controlled
  "Replay network controls and validate every submission without revising V1 network semantics."
  [config controlled-profile control-policy history evidence]
  (let [pilot-profile (:pilot/profile controlled-profile)
        activation-count (or (:activation/action-count control-policy) 0)
        expected (build-controlled-profile config pilot-profile control-policy
                                           (subvec history 0 (min (count history) activation-count)))
        state (challenges/replay-controlled config control-policy history)
        nodes (mapv #(pilot/validate-context! config pilot-profile (:challenge/root %)
                                              (get-in % [:action/payload :evidence/context]) evidence)
                    (filter #(= :submit (:action/kind %)) history))]
    (when-not (= expected controlled-profile)
      (throw (ex-info "controlled-pilot-policy-substitution"
                      {:reason :controlled-pilot-policy-substitution})))
    (let [composition-node
          (forensic/build-forensic-evidence-node
           {:subject {:subject/kind :research/controlled-shortfall-pilot
                      :subject/root (:history/root state)}
            :role :validated :visibility :public
            :causal-parent-roots (vec (sort (distinct [(:artifact/root controlled-profile)
                                                       (:artifact/root control-policy)
                                                       (:artifact/root config)])))
            :parent-nodes (mapv :node-hash nodes)})]
      {:state state :replay/nodes (conj nodes composition-node)
       :replay/composition-node composition-node})))
