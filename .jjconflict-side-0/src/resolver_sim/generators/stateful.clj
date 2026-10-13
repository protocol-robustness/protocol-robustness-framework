(ns resolver-sim.generators.stateful
  "Deterministic stateful scenario sequence generation."
  (:require [resolver-sim.generators.actions :as actions]
            [resolver-sim.generators.adversarial :as adv]
            [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.protocols.registry :as preg]))

(def ^:private phase1-action-order
  {"create_escrow" 0
   "raise_dispute" 1
   "execute_resolution" 2
   "execute_pending_settlement" 3})

(def ^:private phase1-allowed-actions
  (set (keys phase1-action-order)))

(declare generate-event-sequence-from-intents)

(defn- choose-event
  "Pick one valid event deterministically from preferred lifecycle phase,
   then seeded tie-break among same-action candidates."
  [valid-events rng profile]
  (when (seq valid-events)
    (let [rank-fn (fn [ev]
                    (if profile
                      (adv/profile-priority profile (:action ev))
                      (get phase1-action-order (:action ev) 999)))
          sorted (sort-by rank-fn valid-events)
          best-k (rank-fn (first sorted))
          cohort (vec (filter #(= best-k (rank-fn %)) sorted))]
      (nth cohort (.nextInt rng (count cohort))))))

(defn generate-event-sequence
  "Generate a deterministic event sequence and evolved world.
   Returns {:events [...], :world world-final}."
  [{:keys [seed max-steps context world0 initial-time profile protocol]
    :or {seed 42 max-steps 4 initial-time 1000 profile :phase1-lifecycle}}]
  (let [rng (java.util.Random. (long seed))
        protocol (or protocol (preg/get-protocol preg/default-protocol-id))]
    (loop [i 0
           world world0
           events []]
      (if (>= i max-steps)
        {:events events :world world}
        (let [seqn  i
              prev-t (or (:time (last events)) initial-time)
              t     (if (zero? i)
                      initial-time
                      (if (#{:same-block-ordering :timeout-boundary} profile)
                        (adv/next-time profile world prev-t i)
                        (inc prev-t)))
              valid (if (= profile :phase1-lifecycle)
                      (->> (actions/valid-next-actions protocol context world seqn t)
                           (filter #(contains? phase1-allowed-actions (:action %)))
                           vec)
                      (adv/valid-next-actions profile protocol context world seqn t))
              ev    (choose-event valid rng profile)]
          (if-not ev
            {:events events :world world}
            (let [r (proto/dispatch-action protocol context world ev)]
              (if (:ok r)
                (recur (inc i) (:world r) (conj events ev))
                {:events events :world world}))))))))

(defn generate-event-sequence-from-intents
  "Shrink-friendly interpreter: given an intent vector (nat indices),
   deterministically selects among currently valid actions and executes them.
   Shorter/changed intent vectors naturally shrink failing traces."
  [{:keys [intents context world0 initial-time profile protocol]
    :or {initial-time 1000 profile :phase1-lifecycle}}]
  (let [protocol (or protocol (preg/get-protocol preg/default-protocol-id))]
    (loop [i 0
           world world0
           events []]
      (if (>= i (count intents))
        {:events events :world world}
        (let [seqn  i
              prev-t (or (:time (last events)) initial-time)
              t     (if (zero? i)
                      initial-time
                      (if (#{:same-block-ordering :timeout-boundary} profile)
                        (adv/next-time profile world prev-t i)
                        (inc prev-t)))
              valid (if (= profile :phase1-lifecycle)
                      (->> (actions/valid-next-actions protocol context world seqn t)
                           (filter #(contains? phase1-allowed-actions (:action %)))
                           vec)
                      (adv/valid-next-actions profile protocol context world seqn t))
              idx   (nth intents i 0)
              ev    (when (seq valid)
                      (nth (vec valid) (mod idx (count valid))))]
          (if-not ev
            {:events events :world world}
            (let [r (proto/dispatch-action protocol context world ev)]
              (if (:ok r)
                (recur (inc i) (:world r) (conj events ev))
                {:events events :world world}))))))))
