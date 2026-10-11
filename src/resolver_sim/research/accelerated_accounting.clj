(ns resolver-sim.research.accelerated-accounting
  "Compatibility aliases for the accounting-reductions semantic API.

   New consumers should require `resolver-sim.accounting.reductions` directly.
   These aliases preserve the existing V1 call surface during migration."
  (:require [resolver-sim.accounting.reductions :as reductions]))

(def reduction-contract-schema reductions/reduction-contract-schema)
(def request-schema reductions/request-schema)
(def work-unit-schema reductions/work-unit-schema)
(def partition-plan-schema reductions/partition-plan-schema)
(def partial-schema reductions/partial-schema)
(def reconciliation-schema reductions/reconciliation-schema)
(def supported-operation reductions/supported-operation)
(def supported-properties reductions/supported-properties)

(def reduction-contract-root reductions/reduction-contract-root)
(def build-reduction-contract reductions/build-reduction-contract)
(def valid-reduction-contract? reductions/valid-reduction-contract?)
(def request-root reductions/request-root)
(def build-request reductions/build-request)
(def valid-request? reductions/valid-request?)
(def serial-values reductions/serial-values)
(def work-unit-root reductions/work-unit-root)
(def partition-plan-root reductions/partition-plan-root)
(def build-partition-plan reductions/build-partition-plan)
(def valid-partition-plan? reductions/valid-partition-plan?)
(def partial-root reductions/partial-root)
(def partial-semantic-root reductions/partial-semantic-root)
(def partial-values reductions/partial-values)
(def valid-partial-result? reductions/valid-partial-result?)
(def build-partial-result reductions/build-partial-result)
(def ingest-partial reductions/ingest-partial)
(def semantic-partial-equivalent? reductions/semantic-partial-equivalent?)
(def accounting-completeness reductions/accounting-completeness)
(def reconciliation-root reductions/reconciliation-root)
(def reconcile reductions/reconcile)
