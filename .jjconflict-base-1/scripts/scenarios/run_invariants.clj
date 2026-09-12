(require 'resolver-sim.protocols.sew.invariant-runner)
(let [runner (resolve 'resolver-sim.protocols.sew.invariant-runner/run-and-report)]
  (System/exit (runner)))
