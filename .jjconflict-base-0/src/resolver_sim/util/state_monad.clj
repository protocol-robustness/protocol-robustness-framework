(ns resolver-sim.util.state-monad
  "Opt-in state monad for composing state-transforming functions.

  **Status:** opt-in utility. NOT required anywhere in the codebase.

  A state computation is a function of state → [value new-state].
  The monad lets you thread state implicitly through a chain of operations,
  making local accumulation patterns (validation roots, evidence collection,
  check execution) more readable without threading state explicitly.

  **When to use:**
  - Local accumulation: collecting validation results, building evidence lists.
  - Small interpreters: composing a sequence of state updates.
  - Readability: when explicit let-threading of state obscures intent.

  **When NOT to use:**
  - World-state changes in Sew, yield, financial finality, replay, or slashing.
  - Any code where auditability of state transitions is critical.
  - Hiding important mutations — prefer explicit threading in protocol logic.

  **Design:**
    Each computation is a function:
      (fn [state] [value new-state])

  **API:**
    (return x)           — inject a value, state unchanged
    (bind m f)           — thread m into f, which receives m's value
    (get-state)          — capture current state as the value
    (put-state s)        — replace state, value is nil
    (update-state f args) — apply f to state, value is nil
    (run-state m s)      — run computation m with initial state s, return [v s']
    (eval-state m s)     — run, return only the value
    (exec-state m s)     — run, return only the final state
    (sequence-state ms)  — run a vector of computations in order, collect values
    (traverse-state f xs) — map f over xs threading state through each"

  (:refer-clojure :exclude [sequence]))

(defn return
  "Wrap a value into a state computation that leaves state unchanged."
  [value]
  (fn [state]
    [value state]))

(defn bind
  "Thread computation m through f. f receives m's result value and returns
   a new state computation."
  [m f]
  (fn [state]
    (let [[value state'] (m state)]
      ((f value) state'))))

(defn get-state
  "Return a computation whose value is the current state."
  []
  (fn [state]
    [state state]))

(defn put-state
  "Replace the state. Returns nil as the value."
  [new-state]
  (fn [_]
    [nil new-state]))

(defn update-state
  "Apply f to the current state with optional args. Returns nil as the value."
  [f & args]
  (fn [state]
    [nil (apply f state args)]))

(defn run-state
  "Run a state computation with an initial state.
   Returns [value final-state]."
  [computation initial-state]
  (computation initial-state))

(defn eval-state
  "Run a state computation, returning only the final value."
  [computation initial-state]
  (first (run-state computation initial-state)))

(defn exec-state
  "Run a state computation, returning only the final state."
  [computation initial-state]
  (second (run-state computation initial-state)))

(defn sequence-state
  "Run a vector of state computations in order.
   Returns a computation whose value is a vector of each step's result."
  [computations]
  (fn [state]
    (loop [cs computations
           acc []
           s state]
      (if (empty? cs)
        [acc s]
        (let [[v s'] ((first cs) s)]
          (recur (rest cs) (conj acc v) s'))))))

(defn traverse-state
  "Map f over xs, threading state through each call.
   f should return a state computation for each element.
   Returns a computation whose value is a vector of results."
  [f xs]
  (sequence-state (mapv f xs)))
