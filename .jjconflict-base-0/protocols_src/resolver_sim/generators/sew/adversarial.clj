(ns resolver-sim.generators.sew.adversarial
  "Sew-specific adversarial profile semantics for stateful generation."
  (:require [resolver-sim.generators.actions :as actions]))

(def ^:private profile-order
  {:phase1-lifecycle
   {"create_escrow" 0
    "raise_dispute" 1
    "execute_resolution" 2
    "execute_pending_settlement" 3}

   :timeout-boundary
   {"create_escrow" 0
    "raise_dispute" 1
    "execute_pending_settlement" 2
    "execute_resolution" 3}

   :same-block-ordering
   {"create_escrow" 0
    "raise_dispute" 1
    "execute_resolution" 2
    "execute_pending_settlement" 3}

   :dispute-flooding
   {"create_escrow" 0
    "raise_dispute" 1
    "execute_resolution" 2
    "execute_pending_settlement" 3}

   :withdrawal-under-exposure
   {"create_escrow" 0
    "raise_dispute" 1
    "withdraw_stake" 2
    "execute_resolution" 3
    "execute_pending_settlement" 4}})

(defn profile-priority
  [profile action]
  (get-in profile-order [profile action] 999))

(defn valid-next-actions
  "Delegates to canonical action validity and applies adversarial profile sort bias."
  [profile protocol context world seq time]
  (let [base (actions/valid-next-actions protocol context world seq time)]
    (sort-by #(profile-priority profile (:action %)) base)))

(defn next-time
  "Return next event timestamp according to profile semantics.
   - :same-block-ordering intentionally keeps same time on odd steps.
   - :timeout-boundary targets pending-settlement deadline at t-1/t/t+1 when known.
   - default increments by 1."
  [profile world prev-time step-idx]
  (let [next-deadline (some->> (:pending-settlements world)
                               vals
                               (filter :exists)
                               (map :appeal-deadline)
                               seq
                               sort
                               first)]
    (case profile
      :same-block-ordering (if (odd? step-idx) prev-time (inc prev-time))
      :timeout-boundary    (if next-deadline
                             (+ next-deadline (nth [-1 0 1] (mod step-idx 3)))
                             (+ prev-time (nth [0 1 2] (mod step-idx 3))))
      :dispute-flooding    (if (< step-idx 3) (inc prev-time) (+ prev-time 2))
      :withdrawal-under-exposure (inc prev-time)
      :phase1-lifecycle    (inc prev-time)
      (inc prev-time))))
