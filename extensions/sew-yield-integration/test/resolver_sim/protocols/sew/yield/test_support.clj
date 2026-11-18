(ns resolver-sim.protocols.sew.yield.test-support
  "Test fixtures for the in-tree Sew yield integration extension."
  (:require [resolver-sim.protocols.sew.snapshot :as snapshot]))

(defn escrow-snapshot
  [params]
  (snapshot/validate-snapshot (snapshot/make-escrow-snapshot params)))
