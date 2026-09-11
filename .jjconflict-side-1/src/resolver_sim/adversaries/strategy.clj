(ns resolver-sim.adversaries.strategy
  "Pluggable adversary strategies for attack modeling."
  (:require [resolver-sim.stochastic.rng :as rng]))

;; ============ HELPER FUNCTIONS ============

(defn- next-double
  "Sample a double from the RNG in params, falling back to bare rand.
   Callers should always provide :rng in params for reproducibility."
  [params]
  (rng/roll-double (:rng params)))

(defn estimate-bribery-cost
  "Estimate bribery cost from parameters."
  [params bribe-cost-ratio]
  (let [escrow (:escrow-size params 10000)
        detection (:fraud-detection-probability params 0.0)
        cost (* escrow detection (- bribe-cost-ratio 1.0))]
    (long (Math/ceil cost))))

(defn estimate-bribery-profit
  "Estimate bribery profit from parameters."
  [params]
  (:escrow-size params 10000))

(defn estimate-evidence-cost
  "Estimate evidence cost from parameters."
  [params difficulty]
  (case difficulty
    :easy 1000
    :medium 5000
    :hard 15000
    5000))

(defn estimate-evidence-profit
  "Estimate evidence profit from parameters."
  [params]
  (:escrow-size params 10000))

;; ============ PROTOCOL ============

(defprotocol Adversary
  "Attack strategy that decides whether and how to attack."

  (should-attack?
    [this dispute-params]
    "Decide whether to attack this dispute.
     dispute-params may include :rng (a SplittableRandom) for reproducible decisions.")

  (attack-type
    [this dispute-params]
    "Choose attack type given constraints.")

  (budget-allocation
    [this dispute-params]
    "Allocate available budget across attack types.")

  (expected-profit
    [this attack-type dispute-params]
    "Estimate profit from chosen attack.")

  (observe-outcome!
    [this outcome]
    "Notify the adversary of an observed attack outcome so it can update internal state.
     outcome — {:attack-type kw :success? bool :profit num :detected? bool}
     Stateless attackers should implement this as a no-op."))

;; ============ STATIC ATTACKER (Phase H baseline) ============

(deftype StaticAttacker []
  Adversary

  (should-attack? [_ params]
    (< (next-double params) 0.5))

  (attack-type [_ params]
    :none)

  (budget-allocation [_ params]
    {:bribery 0.0 :evidence 0.0 :collusion 0.0})

  (expected-profit [_ attack-type params]
    0)

  (observe-outcome! [_ _outcome]
    nil))

;; ============ BRIBERY ATTACKER (Phase P) ============

(deftype BriberyAttacker [bribe-cost-ratio]
  Adversary

  (should-attack? [_ params]
    (< (next-double params) 0.5))

  (attack-type [_ params]
    (let [available-budget (:attacker-budget params 0)
          bribery-cost (estimate-bribery-cost params bribe-cost-ratio)]
      (if (and (> available-budget 0)
               (< bribery-cost available-budget))
        :bribery
        :none)))

  (budget-allocation [_ params]
    {:bribery 1.0 :evidence 0.0 :collusion 0.0})

  (expected-profit [_ attack-type params]
    (case attack-type
      :bribery (estimate-bribery-profit params)
      0))

  (observe-outcome! [_ _outcome]
    nil))

;; ============ EVIDENCE ATTACKER (Phase Q) ============

(deftype EvidenceAttacker [bribe-cost-ratio evidence-difficulty]
  Adversary

  (should-attack? [_ params]
    (< (next-double params) 0.5))

  (attack-type [_ params]
    (let [bribery-cost (estimate-bribery-cost params bribe-cost-ratio)
          evidence-cost (estimate-evidence-cost params evidence-difficulty)
          available-budget (:attacker-budget params 0)]
      (cond
        (and (< evidence-cost bribery-cost)
             (< evidence-cost available-budget)) :evidence
        (< bribery-cost available-budget) :bribery
        :else :none)))

  (budget-allocation [_ params]
    {:bribery 0.4 :evidence 0.6 :collusion 0.0})

  (expected-profit [_ attack-type params]
    (case attack-type
      :evidence (estimate-evidence-profit params)
      :bribery (estimate-bribery-profit params)
      0))

  (observe-outcome! [_ _outcome]
    nil))

;; ============ ADAPTIVE ATTACKER (Phase R) ============
;;
;; Beliefs are stored in an atom so observe-outcome! can update them between calls.
;; Construct with (->AdaptiveAttacker (atom [b0 b1 b2]) learning-rate).

(deftype AdaptiveAttacker [beliefs-atom learning-rate]
  Adversary

  (should-attack? [_ params]
    (some #(> % 0) @beliefs-atom))

  (attack-type [_ params]
    (let [beliefs @beliefs-atom]
      (if (< (next-double params) 0.1)
        ; Exploration: pick a random type (use rng when available).
        (let [idx (long (Math/floor (* (next-double params) 3)))]
          (nth [:bribery :evidence :collusion] (min idx 2)))
        ; Exploitation: pick the highest-belief type.
        (let [best-idx (.indexOf ^java.util.List beliefs (apply max beliefs))]
          (nth [:bribery :evidence :collusion] best-idx)))))

  (budget-allocation [_ params]
    (let [beliefs @beliefs-atom
          total   (apply + beliefs)
          norm    #(if (pos? total) (/ (double %) (double total)) 0.0)]
      {:bribery   (norm (nth beliefs 0))
       :evidence  (norm (nth beliefs 1))
       :collusion (norm (nth beliefs 2))}))

  (expected-profit [_ attack-type params]
    (let [beliefs @beliefs-atom]
      (case attack-type
        :bribery   (nth beliefs 0)
        :evidence  (nth beliefs 1)
        :collusion (nth beliefs 2)
        0)))

  (observe-outcome! [_ outcome]
    ; Update the belief for the observed attack type using a simple gradient step:
    ;   belief' = belief + learning-rate × (observed-profit - belief)
    ; This implements exponential moving average towards the observed return.
    (let [{:keys [attack-type profit]} outcome
          idx (case attack-type :bribery 0 :evidence 1 :collusion 2 nil)]
      (when idx
        (swap! beliefs-atom
               (fn [bs]
                 (update bs idx
                         #(+ % (* learning-rate (- profit %))))))))))

(defn make-adaptive-attacker
  "Construct an AdaptiveAttacker with initial equal beliefs and the given learning rate."
  [initial-belief learning-rate]
  (->AdaptiveAttacker (atom [initial-belief initial-belief initial-belief]) learning-rate))
