(ns resolver-sim.definitions.runner-execution-registry
  "Execution modes contributed by the optional PRF runner component."
  (:require [resolver-sim.definitions.passive-registries :as registries]))

(def ^:private server-entry
  {:id :execution/server
   :version registries/entry-version
   :kind :service
   :runner :grpc-server
   :entry 'resolver-sim.server.grpc/start!
   :execution/type :server
   :execution/mode :long-running
   :description "Long-running gRPC server for interactive simulation."
   :claims #{:evidence-completeness}})

(def entries
  (cond-> [{:id :execution/simulation
            :version registries/entry-version
            :kind :simulation
            :runner :phase-runner
            :entry 'resolver-sim.core.phases/run-simulation
            :execution/type :simulation
            :execution/mode :full
            :description "Full Monte Carlo simulation from protocol params."
            :claims #{:deterministic-replay :evidence-completeness}}
           {:id :execution/replay
            :version registries/entry-version
            :kind :replay
            :runner :scenario-runner
            :entry 'resolver-sim.io.scenario-runner/run-and-report
            :execution/type :replay
            :execution/mode :deterministic
            :description "Deterministic replay from recorded trace."
            :claims #{:deterministic-replay :trace-fidelity}}
           {:id :execution/batch
            :version registries/entry-version
            :kind :batch
            :runner :phase-runner
            :entry 'resolver-sim.core.phases/run-sweep
            :execution/type :batch
            :execution/mode :sweep
            :depends-on [:execution/simulation]
            :description "Parameter sweep or batch execution over multiple trials."
            :claims #{:deterministic-replay}}
           {:id :execution/diff
            :version registries/entry-version
            :kind :differential
            :runner :differential-runner
            :entry 'resolver-sim.io.diff-runner/run-diff-traces!
            :execution/type :diff
            :execution/mode :comparison
            :depends-on [:execution/replay]
            :description "Trace diff between two simulation runs."
            :claims #{:trace-fidelity}}
           {:id :evidence/commitment-root
            :version registries/entry-version
            :kind :commitment-root
            :runner :scenario-runner
            :entry 'resolver-sim.io.scenario-runner/run-and-report
            :execution/type :commitment-root
            :execution/mode :inline
            :description "Evidence commitment root for a completed scenario run."
            :claims #{}}]
    (try
      (requiring-resolve 'resolver-sim.server.grpc/start!)
      true
      (catch Exception _ false))
    (conj server-entry)))

(def registry
  (registries/register-execution-registry-extension!
   :prf/runner-v1 entries))
