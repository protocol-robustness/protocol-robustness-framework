(ns resolver-sim.execution.budget
  "Runtime-only shared execution permit budget.

  A single JVM execution budget K that bounds TOTAL concurrent execution across
  cooperating layers: the outer benchmark scenario-worker pool and the inner
  claimant executors. Without it, outer parallelism N multiplied by inner claimant
  parallelism M can approach N x M threads under a busy workload.

  Model:
    - outer benchmark work CONSUMES capacity (acquires a permit for its duration);
    - inner claimant work BORROWS spare capacity (uses permits not currently held);
    - when no spare permits remain, claimant work executes serially.

  Permits affect physical execution ONLY. They never enter a canonical request,
  allocation, result, evidence, or package root. All acquisition is non-blocking
  for inner borrowing so nested waits can never deadlock a saturated budget;
  top-level outer work may block on acquisition because nothing depends on it to
  make progress."
  (:import [java.util.concurrent Semaphore]))

(def ^:dynamic *execution-budget*
  "The shared execution budget (a java.util.concurrent.Semaphore), or nil when
   unbounded (the default per-pool behavior). Bind via with-execution-budget or by
   placing a Semaphore on the runtime execution context."
  nil)

(defmacro with-execution-budget
  "Bind a shared execution budget of `permits` permits for the dynamic extent.
   Intended for tests and explicit opt-in executions."
  [permits & body]
  `(binding [*execution-budget* (Semaphore. (int ~permits))]
     ~@body))

(defn current
  "The currently-bound budget Semaphore, or nil when unbounded."
  []
  *execution-budget*)

(defn acquire-permit!
  "Blocking consumption for TOP-LEVEL outer work. Returns the budget so the caller
   can release it. No-op (returns nil) when no budget is bound."
  []
  (when-let [b *execution-budget*]
    (.acquire b)
    b))

(defn try-acquire-permit!
  "Non-blocking consumption of a single permit. Returns the budget on success, or
   nil if no permit could be borrowed. Used where a task must not block."
  []
  (when-let [b *execution-budget*]
    (when (.tryAcquire b)
      b)))

(defn acquire-many!
  "Non-blockingly borrow up to `n` permits, returning how many were actually
   acquired. Guaranteed not to block or deadlock; used by inner borrowing layers."
  [n]
  (if-let [b *execution-budget*]
    (loop [i 0]
      (if (and (< i (long n)) (.tryAcquire b))
        (recur (inc i))
        i))
    0))

(defn release-many!
  "Release `n` previously acquired permits back to the budget."
  [n]
  (when-let [b *execution-budget*]
    (when (pos? (long n))
      (.release b (int n)))))

(defn release-permit!
  "Release a single budget permit (accepts nil for no-op when none was acquired)."
  [budget]
  (when budget
    (.release ^Semaphore budget)))

(defn available
  "Number of currently-unheld permit slots (spare capacity), or nil when unbounded."
  []
  (when *execution-budget*
    (.availablePermits *execution-budget*)))

(defn borrowed-parallelism
  "Maximum parallelism an inner (claimant) layer may borrow for `requested`
   parallel work. Returns 0 when there is no spare capacity (caller must run
   serially), and returns `requested` unchanged when no shared budget is bound."
  [requested]
  (if-let [b *execution-budget*]
    (let [spare (.availablePermits b)]
      (if (< spare 2) 0 (min (long (or requested 1)) (int spare))))
    (long (or requested 1))))