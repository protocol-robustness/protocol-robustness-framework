(ns resolver-sim.resubmission.chain
  "Linear-chain admission facade over the resubmission transaction layer.

   The semantic authority is `resolver-sim.resubmission.transition/apply-action`,
   executed atomically by `resolver-sim.resubmission.store` (which implements
   resolver-sim.transaction.protocol/transact!). This namespace is a thin
   adapter that keeps the historical chain API (new-chain / admit! /
   current-head / observed?) and maps the transition's result contract to the
   legacy {:admission-status :admitted|:not-admitted :reason kw} shape.

   The facade synthesizes a minimal parent-compatible attempt receipt for bare
   hash-based callers; production callers supply real validator-issued receipts
   through the transition directly."
  (:require [resolver-sim.resubmission.receipt :as receipt]
            [resolver-sim.resubmission.store :as store]
            [resolver-sim.resubmission.transition :as transition]
            [resolver-sim.transaction.protocol :as protocol]))

(defn new-chain
  "Create an in-memory linear chain (a TransactionStore) for a family."
  [family-id]
  (store/new-resubmission-store family-id))

(defn- bare-receipt
  "Minimal direct-resubmission-parent-compatible receipt for facade callers."
  [receipt-hash]
  {:attempt-receipt/schema receipt/receipt-schema
   :attempt-receipt/id receipt-hash
   :attempt-receipt/outcome :rejected
   :attempt-receipt/finality :final
   :attempt-receipt/resubmission-eligibility :eligible
   :attempt-receipt/lifecycle-status :active})

(defn- admit-command
  [request]
  {:transaction/action :prf.resubmission/admit-child
   :transaction/input
   {:parent-receipt-hash (:parent-receipt-hash request)
    :link-artifact-hash (:link-hash request)
    :candidate-attempt-receipt (bare-receipt (:receipt-hash request))
    :candidate-attempt-receipt-id (:receipt-hash request)
    :idempotency-key (:idempotency-key request)
    :content-key (:basis-root request)
    :sequence (:sequence request)
    :expected-chain-version (:expected-chain-version request)}})

(defn- to-result
  [r]
  (case (:status r)
    :committed
    {:admission-status :admitted
     :reason :ok
     :transaction-ordering (:transaction-ordering r)}

    :idempotent-replay
    {:admission-status :not-admitted
     :reason (:reason r)
     :existing (get-in r [:public-result :existing])}

    :contention
    {:admission-status :not-admitted :reason (:reason r)}

    :rejected
    {:admission-status :not-admitted
     :reason (:reason r)
     :existing (get-in r [:public-result :existing])}))

(defn admit!
  "Atomically attempt to admit a child as the next chain successor.

   request: {:receipt-hash :sequence :parent-receipt-hash
             :link-hash :idempotency-key :basis-root}
   Returns {:admission-status :admitted|:not-admitted :reason kw ...}."
  [chain request]
  (to-result
   (protocol/transact! chain
                       nil
                       nil
                       (fn [state]
                         (transition/apply-action state (admit-command request))))))

(defn current-head
  "The receipt-hash of the current chain head (nil before the first attempt)."
  [chain]
  (store/chain-head chain))

(defn observed?
  "True when the chain has already admitted a child with this idempotency key
   or basis root."
  [chain {:keys [idempotency-key basis-root]}]
  (let [state (store/state-of chain)]
    (boolean
     (or (and idempotency-key
              (contains? (:chain/idempotency-index state) idempotency-key))
         (and basis-root
              (contains? (:chain/content-index state) basis-root))))))
