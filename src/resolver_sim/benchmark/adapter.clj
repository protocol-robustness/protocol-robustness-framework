(ns resolver-sim.benchmark.adapter)

(defprotocol RepositoryAdapter
  (load-scenarios [this benchmark] "Load scenarios based on benchmark manifest")
  (execute-benchmark
    [this benchmark scenarios]
    "Compatibility execution hook for legacy/direct callers.

     This method alone does not authorize canonical benchmark-package execution.
     Canonical packages must enter through `benchmark.runner/run-benchmark` and
     `commands.run-benchmark/run-with-root!`, which own plan freezing,
     reconciliation, closure, and publication.")
  (collect-metrics [this results] "Collect metrics from execution results"))
