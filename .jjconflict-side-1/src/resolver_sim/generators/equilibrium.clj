(ns resolver-sim.generators.equilibrium
  "Phase 3: generator-driven equilibrium evaluation helpers."
  (:require [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.protocols.registry       :as preg]
            [resolver-sim.generators.scenario :as gsc]
            [resolver-sim.scenario.equilibrium :as eq]))

(def default-theory
  {:mechanism-properties [:budget-balance :incentive-compatibility :individual-rationality]
   :equilibrium-concept  [:dominant-strategy-equilibrium :nash-equilibrium]})

(defn- convergence-class
  [{:keys [outcome halt-reason metrics]}]
  (cond
    (= :fail outcome) :invariant-failed
    (#{:open-entities-at-end :open-disputes-at-end} halt-reason) :stuck
    (pos? (get metrics :attack-successes 0)) :attack-success
    (pos? (get metrics :invariant-violations 0)) :invariant-failed
    (nil? halt-reason) :terminal
    :else :inconclusive))

(defn- payoff-view
  [metrics]
  (let [honest-full (:honest-payoff metrics)
        malicious-full (:malicious-payoff metrics)]
    (if (and (number? honest-full) (number? malicious-full))
      {:basis :full-ledger
       :honest honest-full
       :malicious malicious-full
       :delta (- honest-full malicious-full)}
      (let [honest-proxy (get metrics :total-fees 0)
            malicious-proxy (get metrics :attack-successes 0)]
        {:basis :proxy-metric
         :honest honest-proxy
         :malicious malicious-proxy
         :delta (- honest-proxy malicious-proxy)}))))

(defn evaluate-generated-scenario
  "Build one generated scenario, replay it, and evaluate trace-end equilibrium proxies."
  [{:keys [seed max-steps profile theory protocol]
    :or {seed 42 max-steps 6 profile :phase1-lifecycle theory default-theory}}]
  (let [protocol (or protocol (preg/get-protocol preg/default-protocol-id))
        scenario (gsc/build-scenario {:seed seed :max-steps max-steps :profile profile :protocol protocol})
        result   (replay/replay-with-protocol protocol scenario)
        eqr      (eq/evaluate-equilibrium theory result)]
    {:seed seed
     :profile profile
     :scenario-id (:scenario-id scenario)
     :outcome (:outcome result)
     :halt-reason (:halt-reason result)
     :metrics (:metrics result)
     :payoff (payoff-view (:metrics result))
     :convergence (convergence-class result)
     :mechanism-status (:mechanism-status eqr)
     :equilibrium-status (:equilibrium-status eqr)
     :mechanism-results (:mechanism-results eqr)
     :equilibrium-results (:equilibrium-results eqr)}))

(defn evaluate-generated-batch
  "Evaluate a deterministic seed range and return aggregate status summary."
  [{:keys [start-seed n max-steps profile theory protocol]
    :or {start-seed 1 n 20 max-steps 6 profile :phase1-lifecycle theory default-theory}}]
  (let [seeds   (range start-seed (+ start-seed n))
        runs    (mapv (fn [s] (evaluate-generated-scenario {:seed s :max-steps max-steps :profile profile :theory theory :protocol protocol})) seeds)
        counts  (fn [k] (frequencies (map k runs)))
        malicious-payoff-total (reduce + (map #(get-in % [:payoff :malicious] 0) runs))
        honest-payoff-total (reduce + (map #(get-in % [:payoff :honest] 0) runs))
        terminal-count (count (filter #(nil? (:halt-reason %)) runs))
        stuck-count (- (count runs) terminal-count)
        convergence-counts (frequencies (map :convergence runs))]
    {:profile profile
     :seed-range [start-seed (+ start-seed (dec n))]
     :runs runs
     :summary {:outcome-counts (counts :outcome)
               :liveness {:terminal terminal-count :stuck stuck-count}
               :payoffs {:malicious-total malicious-payoff-total
                         :honest-total honest-payoff-total
                         :delta-total (- honest-payoff-total malicious-payoff-total)}
               :payoff-basis-counts (frequencies (map #(get-in % [:payoff :basis]) runs))
               :convergence-counts convergence-counts
               :mechanism-status-counts (counts :mechanism-status)
               :equilibrium-status-counts (counts :equilibrium-status)}}))
