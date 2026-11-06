(ns resolver-sim.protocols.sew.runner-extension
  "Sew-specific catalogs and assurance adapters for PRF's optional runner."
  (:require [resolver-sim.protocols.sew.invariant-scenarios :as scenarios]
            [resolver-sim.protocols.sew.research-models.ring-batch :as ring-batch]
            [resolver-sim.protocols.sew.runner-replay :as runner-replay]
            [resolver-sim.protocols.registry :as protocol-registry]
            [resolver-sim.db.sew :as sew-db]
            [resolver-sim.protocols.sew.io.trace-export :as trace-export]
            [resolver-sim.sim.batch :as batch]
            [resolver-sim.sim.fixtures :as fixtures]))

(defn register-capabilities! []
  (fixtures/register-scenario-catalog! "sew-v1" scenarios/all-scenarios)
  (batch/register-ring-batch-adapter! ring-batch/run-ring-batch)
  (protocol-registry/register-capability!
   "sew-v1" :runner/replay runner-replay/replay-with-sew-protocol)
  (protocol-registry/register-capability!
   "sew-v1" :runner/summarise-batch sew-db/sew-summarise-batch)
  (protocol-registry/register-capability!
   "sew-v1" :runner/export-trace-fixture
   (fn [result scenario trace-path]
     (trace-export/write-fixture-file
      (trace-export/export-trace-fixture result scenario)
      trace-path))))

(protocol-registry/register-capability-provider! "sew-v1" :runner register-capabilities!)
(register-capabilities!)

(def installed? true)
