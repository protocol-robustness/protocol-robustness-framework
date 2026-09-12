(ns resolver-sim.util.attributed-monad
  "Monadic helpers for working with AttributedState.
   Provides a thin layer over state-monad that automatically handles
   wrapping/unwrapping and context propagation."
  (:require [resolver-sim.util.state-monad :as sm]
            [resolver-sim.util.attribution :as attr]))

(defn run-attributed
  "Run a state computation with an initial state and attribution context.
   Returns [value final-attributed-state]."
  [m state attribution]
  (sm/run-state m (attr/wrap-state state attribution)))

(defn exec-attributed
  "Run a state computation and return the final AttributedState."
  [m state attribution]
  (sm/exec-state m (attr/wrap-state state attribution)))

(defn eval-attributed
  "Run a state computation and return only the resulting value."
  [m state attribution]
  (sm/eval-state m (attr/wrap-state state attribution)))

(defn update-attributed
  "Monadic version of update-state that operates on the inner state
   while preserving/updating attribution.
   f is (fn [state] new-state)."
  [f]
  (sm/update-state
   (fn [attributed]
     (let [state (attr/unwrap-state attributed)
           attr  (attr/get-attribution attributed)]
       (attr/with-attribution attr
         (attr/wrap-state (f state) attr))))))

(defn update-with-context
  "Monadic version of update-state that receives both state and attribution.
   f is (fn [state attribution] new-state)."
  [f]
  (sm/update-state
   (fn [attributed]
     (let [state (attr/unwrap-state attributed)
           attr  (attr/get-attribution attributed)]
       (attr/wrap-state (f state attr) attr)))))

(defn with-context
  "Wraps a state computation m such that it runs with an additional
   merged attribution context.
   Note: This doesn't use dynamic binding, it merges into the AttributedState.
   Invalid keys in ctx are warned at merge time."
  [ctx m]
  (fn [attributed]
    (let [state (attr/unwrap-state attributed)
          attr  (attr/get-attribution attributed)
          _     (attr/warn-invalid-attribution! ctx)
          new-attr (merge attr ctx)]
      (m (attr/wrap-state state new-attr)))))

(defn update-with-result
  "Monadic update that handles functions returning {:ok bool :world world' ...}.
   f is (fn [world & args] result-map).
   The value of the computation becomes the result map.
   The state becomes the new world (if :ok) wrapped in AttributedState."
  [f & args]
  (fn [attributed]
    (let [world (attr/unwrap-state attributed)
          attr  (attr/get-attribution attributed)]
      (attr/with-attribution attr
        (let [result (apply f world args)]
          (if (:ok result)
            [result (attr/wrap-state (:world result) attr)]
            [result attributed]))))))

(defn get-inner-state []
  (sm/bind (sm/get-state) (fn [a] (sm/return (attr/unwrap-state a)))))

(defn get-inner-attribution []
  (sm/bind (sm/get-state) (fn [a] (sm/return (attr/get-attribution a)))))
