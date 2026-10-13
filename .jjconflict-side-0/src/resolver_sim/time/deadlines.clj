(ns resolver-sim.time.deadlines
  "Deterministic deadline arithmetic helpers for simulation time.")

(defn deadline
  "Compute absolute deadline from start-ts + duration-seconds."
  [start-ts duration-seconds]
  (+ (long start-ts) (long duration-seconds)))

(defn before-deadline?
  "True when now-ts is strictly before deadline-ts (window still open)."
  [now-ts deadline-ts]
  (< (long now-ts) (long deadline-ts)))

(defn deadline-expired?
  "True when now-ts >= deadline-ts (window closed; action is executable).
   This is the standard protocol predicate: at-or-after means expired."
  [now-ts deadline-ts]
  (>= (long now-ts) (long deadline-ts)))

(defn at-deadline?
  "True when now-ts is exactly at deadline-ts."
  [now-ts deadline-ts]
  (= (long now-ts) (long deadline-ts)))

(defn deadline-passed?
  "True when now-ts is strictly after deadline-ts.
   Prefer deadline-expired? for protocol enforcement (uses >=, not >)."
  [now-ts deadline-ts]
  (> (long now-ts) (long deadline-ts)))

(defn boundary-times
  "Return canonical boundary probes around a deadline: t-1, t, t+1."
  [deadline-ts]
  {:t-1 (dec (long deadline-ts))
   :t   (long deadline-ts)
   :t+1 (inc (long deadline-ts))})
