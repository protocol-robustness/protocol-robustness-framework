(ns resolver-sim.resubmission.chain
  "Linear-chain admission facade over the resubmission transaction layer.

   The semantic authority is `resolver-sim.resubmission.transition/apply-action`,
   executed atomically by `resolver-sim.resubmission.store` (which implements
   resolver-sim.transaction.protocol/transact!). This namespace is a thin
   adapter that keeps the historical chain API (new-chain / admit! /
   current-head / observed?) and maps the transition's result contract to the
   {:admission-status :admitted|:not-admitted :reason kw} shape.

   `admit!` is the canonical path and accepts only the exact signed validator
   receipt. The old hash-only behavior is isolated in `admit-compat!` for
   fixtures and demos; it is never an authority admission path."
  (:require [resolver-sim.resubmission.genesis :as genesis]
             [resolver-sim.resubmission.receipt :as receipt]
             [resolver-sim.resubmission.store :as store]
             [resolver-sim.resubmission.transition :as transition]
             [resolver-sim.transaction.protocol :as protocol]))

(def ^:dynamic *admit-compat-guard*
  "Runtime guard for admit-compat!. Bind to nil in fixtures/demos to allow
   use; leave unbound (truthy) to fail closed in production code."
  true)

(defn new-chain-from-genesis
  "Explicit canonical realization from a resubmission-chain-genesis.v1.

   Requires a structurally and cryptographically self-consistent genesis:
   validates strict closed-shape (fail-closed), verifies chain-id derivation
   consistency, and verifies initial-state/root against the computed
   empty-state root.

   NOTE: this validates well-formedness and canonical rooting, not
   governance authorization. It is the canonical validated genesis
   realization path, distinct from future authority-bearing realization."
  [genesis]
  (store/new-resubmission-store-from-genesis genesis))

(defn new-chain
  "Convenience/local realization of an in-memory linear chain (a
   TransactionStore) for a family.

   Supply the trusted disposition authority public key to permit signed
   disposition events; chains without one reject such events fail closed.

   Convenience status: new-chain constructs a genesis for provenance (via
   genesis/->genesis) and stores it without structural validation. It does NOT
   validate closed-shape or root consistency, and it does NOT confer
   governance authorization. For the canonical validated path use
   new-chain-from-genesis."
  ([family-id] (new-chain family-id nil))
  ([family-id disposition-public-hex]
   (new-chain family-id disposition-public-hex nil))
  ([family-id disposition-public-hex receipt-public-hex]
   (let [g (genesis/->genesis family-id disposition-public-hex receipt-public-hex)]
     (store/new-resubmission-store family-id disposition-public-hex receipt-public-hex g))))

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
  [request candidate-receipt]
  {:transaction/action :prf.resubmission/admit-child
   :transaction/input
   {:parent-receipt-hash (:parent-receipt-hash request)
    :link-artifact-hash (:link-hash request)
    :candidate-attempt-receipt candidate-receipt
    :candidate-attempt-receipt-id (:attempt-receipt/id candidate-receipt)
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
  "Canonical admission path. Requires the exact signed attempt receipt and a
   chain configured with the trusted validator public key.

   request: {:candidate-attempt-receipt signed-receipt :sequence
             :parent-receipt-hash :link-hash :idempotency-key :basis-root}.
   The supplied :receipt-hash is ignored; chain identity is always derived from
   the signed receipt itself."
  [chain request]
  (let [candidate (:candidate-attempt-receipt request)
        public-hex (.receipt-public-hex chain)]
    (cond
      (nil? public-hex)
      {:admission-status :not-admitted :reason :receipt-authority-not-configured}

      (not (receipt/valid-receipt-shape? candidate))
      {:admission-status :not-admitted :reason :invalid-candidate-receipt}

      (not (:valid? (receipt/verify-receipt-signature candidate public-hex)))
      {:admission-status :not-admitted :reason :invalid-candidate-receipt}

      (not= :final (:attempt-receipt/finality candidate))
      {:admission-status :not-admitted :reason :receipt-not-final}

      :else
      (to-result
       (protocol/transact! chain nil nil
                           (fn [state]
                             (transition/apply-action state (admit-command request candidate))))))))

(defn admit-compat!
  "Legacy hash-only façade for fixtures and demonstrations only. It synthesizes
   an unsigned receipt and MUST NOT be used as an authority admission path.

   A runtime guard fails closed when *admit-compat-guard* is bound to a
   non-nil sentinel. Fixtures and demos must bind it to nil explicitly."
  [chain request]
  (when *admit-compat-guard*
    (throw (ex-info "admit-compat! is forbidden outside fixtures/demos"
                    {:admission-status :not-admitted
                     :reason :admit-compat-forbidden})))
  (to-result
   (protocol/transact! chain nil nil
                       (fn [state]
                         (transition/apply-action state
                                                  (admit-command request
                                                                 (bare-receipt (:receipt-hash request))))))))

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
