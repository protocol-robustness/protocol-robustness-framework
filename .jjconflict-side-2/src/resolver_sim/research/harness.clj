(ns resolver-sim.research.harness
  "Researcher-facing scoped execution helpers.

   This namespace establishes run infrastructure only. It does not select,
   execute, or interpret protocol actions."
  (:require [resolver-sim.evidence.chain]
            [resolver-sim.execution.budget]
            [resolver-sim.util.attribution]))

(defmacro with-reloaded-namespaces
  "Reload namespaces before evaluating body.

   namespaces is a literal collection of namespace symbols. Reloading happens
   in the caller's thread and the body returns its normal value."
  [namespaces & body]
  `(do
     ~@(map (fn [namespace-sym]
              `(require '~namespace-sym :reload))
            namespaces)
     ~@body))

(defmacro with-research-run
  "Run body in an isolated, attributed research context.

   opts is evaluated once and may contain:
   :run-id             stable run identifier
   :scenario-id        scenario identifier
   :execution-budget   optional positive permit count
   :attribution        additional attribution entries

   Returns the value of body. Evidence and cursor state are isolated for the
   dynamic extent and the outer contexts are restored afterward."
  [opts & body]
  `(let [opts# ~opts
         run-id# (:run-id opts#)
         scenario-id# (:scenario-id opts#)
         attribution# (merge {:ctx/run-id run-id#
                              :ctx/scenario-id scenario-id#}
                             (:attribution opts#))
         run-body# (fn []
                     (resolver-sim.util.attribution/with-attribution
                       attribution#
                       ~@body))]
     (resolver-sim.evidence.chain/with-fresh-evidence-context*
       (if-let [permits# (:execution-budget opts#)]
         (fn []
           (resolver-sim.execution.budget/with-execution-budget
             permits#
             (run-body#)))
         run-body#))))
