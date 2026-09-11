(ns resolver-sim.validation.classes
  "Validation-class taxonomy for closed-form and game-theoretic checks.

   Prevents conflating algebraic integrity with game-theoretic claims.
   Every check result produced by the framework should carry one of these
   classification keywords so that consumers can determine what type of
   evidence a `:pass` verdict represents.

   Classes (ordered by strength of claim):

     :validation.class/algebraic-integrity
       Arithmetic or structural consistency. Verifies that data is internally
       coherent — conservation holds, hashes match, amounts are non-negative.
       Does NOT establish any strategic property.
       Example: filled + deferred + haircut = requested.

     :validation.class/allocation-property
       Allocation rule correctness. Verifies that an output matches the
       specified allocation policy — proportional fill, priority ordering,
       rounding policy bounds. Does NOT establish that agents cannot profitably
       manipulate the mechanism.
       Example: pro-rata cross-product equality, waterfall priority ordering.

     :validation.class/payoff-property
       Payoff boundary correctness. Verifies that participant payoffs are
       computed consistently with a declared economic model — terminal wealth
       decomposition, fee accrual, bond flow. Does NOT establish incentive
       compatibility.
       Example: budget balance, individual rationality (with declared
       outside option).

     :validation.class/deviation-resistance
       Bounded strategyproofness. Verifies that no deviation from a prescribed
       action within a declared deviation set improves the actor's utility
       beyond an epsilon threshold. The deviation set and utility model must
       be explicitly declared.
       Example: split-invariance, merge-invariance, sybil-equivalent allocation.

     :validation.class/equilibrium
       Sequential rationality. Verifies that no player has a profitable
       unilateral deviation at any decision node or information set within
       the modelled game tree or trace-conditioned subgame.
       Example: trace-conditioned epsilon-SPE, bounded Nash diagnostic.")

(def class-order
  "Ordered sequence for aggregation — earlier classes are weaker claims."
  [:validation.class/algebraic-integrity
   :validation.class/allocation-property
   :validation.class/payoff-property
   :validation.class/deviation-resistance
   :validation.class/equilibrium])

(def class-labels
  "Human-readable labels for each class."
  {:validation.class/algebraic-integrity    "Algebraic integrity"
   :validation.class/allocation-property     "Allocation property"
   :validation.class/payoff-property         "Payoff property"
   :validation.class/deviation-resistance    "Deviation resistance"
   :validation.class/equilibrium             "Equilibrium"})

(def class-descriptions
  "Human-readable descriptions of what each class establishes."
  {:validation.class/algebraic-integrity
   "Data is internally coherent — conservation, hash integrity, non-negativity."
   :validation.class/allocation-property
   "Output matches the declared allocation policy — proportional fill, priority ordering."
   :validation.class/payoff-property
   "Participant payoffs are consistent with the declared economic model."
   :validation.class/deviation-resistance
   "No declared deviation improves utility beyond epsilon for the declared deviation set."
   :validation.class/equilibrium
   "No player has a profitable unilateral deviation in the modelled game or subgame."})

(defn class-strength
  "Return the strength index of a validation class (higher = stronger claim)."
  [k]
  (or (some (fn [[i c]] (when (= c k) i))
            (map-indexed (fn [i c] [i c]) class-order))
      -1))

(defn stronger?
  "True if class a makes a strictly stronger claim than class b."
  [a b]
  (> (class-strength a) (class-strength b)))
