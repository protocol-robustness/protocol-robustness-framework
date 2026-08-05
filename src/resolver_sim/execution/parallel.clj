(ns resolver-sim.execution.parallel
  "Benchmark-safe ordered parallel map with owned executor lifecycle.

  Replaces unowned pmap/contextual-pmap in benchmark-reachable code paths.
  Guarantees:
  - Deterministic result ordering (input order preserved exactly)
  - Bounded concurrency via the shared execution budget when one is active
  - Owned executor lifecycle (create, submit, await, quiesce — fail closed)
  - Serial fallback when parallelism <= 1 or no spare budget permits

  All dynamic bindings from the submitting thread are conveyed to worker
  threads via bound-fn. This is a superset of what contextual-pmap explicitly
  preserved (attribution, evidence capture) — those bindings are conveyed
  automatically, along with execution-context/*context* and budget/*execution-budget*."
  (:require [resolver-sim.execution.budget :as budget]
            [resolver-sim.util.thread-quiescence :as quiesce])
  (:import [java.util.concurrent Executors Callable]))

(defn ^:private run-owned-parallel-tasks!
  "Submit `f` over `values` on a fresh owned fixed thread pool of size
   `parallelism`, collecting results in input order via Future.get.

   Dynamic bindings from the submitting thread are conveyed to workers
   via bound-fn, so attribution/evidence-context vars are visible without
   explicit rebinding.

   The executor is shut down and quiesced before return. If quiescence
   cannot be established, throws a quiescence-failed-exception (fail closed)."
  [parallelism quiescence-timeout-seconds f values]
  (let [executor (Executors/newFixedThreadPool (int parallelism))]
    (try
      (let [futures (mapv (fn [value]
                            (let [task (bound-fn [] (f value))]
                              (.submit executor ^Callable
                                       (reify Callable
                                         (call [_] (task))))))
                          values)]
        (mapv #(.get %) futures))
      (finally
        (let [timeout (or quiescence-timeout-seconds (quiesce/config-default-timeout-seconds))
              q (quiesce/quiesce-executor! executor timeout)]
          (when-not (= :terminated (:status q))
            (throw (quiesce/quiescence-failed-exception
                    "Owned parallel executor did not terminate before release"
                    {:quiescence/status (:status q)
                     :quiescence/remaining-tasks (:remaining-tasks q)
                     :quiescence/timeout-seconds timeout
                       :executor-parallelism parallelism}))))))))

(defn ordered-bounded-mapv
  "Benchmark-safe ordered parallel map with owned executor lifecycle.

   Computes independent facts across `values` with bounded parallelism,
   returning results in input order (identical to mapv semantics but
   parallel where permitted).

   Concurrency model:
   - When a shared execution budget is bound (budget/current), the caller
     BORROWS spare permits via acquire-many! up to `parallelism`. If fewer
     than 2 permits are available, falls back to serial execution (inline
     mapv). Borrowed permits are released in a finally block.
   - When no budget is bound, uses `parallelism` directly as the pool size.
     When `parallelism` <= 1, runs serially (inline mapv).

   The executor is owned: created, submitted, awaited, and quiesced within
   this function. On non-termination, fails closed with a quiescence
   exception. On task failure, propagates the exception after establishing
   quiescence.

   All dynamic bindings from the submitting thread are conveyed to workers
   via bound-fn (a superset of contextual-pmap's explicit rebinding)."
  ([f values]
   (ordered-bounded-mapv 1 f values))
  ([parallelism f values]
   (ordered-bounded-mapv parallelism nil f values))
  ([parallelism quiescence-timeout-seconds f values]
   (let [values (vec values)
         budgeted (budget/current)]
     (if budgeted
       (let [acquired (budget/acquire-many! parallelism)]
         (try
           (if (< acquired 2)
             (mapv f values)
             (run-owned-parallel-tasks! acquired quiescence-timeout-seconds f values))
           (finally
             (budget/release-many! acquired))))
       (if (<= parallelism 1)
         (mapv f values)
         (run-owned-parallel-tasks! parallelism quiescence-timeout-seconds f values))))))
