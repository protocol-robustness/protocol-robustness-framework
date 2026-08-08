(ns resolver-sim.resubmission.issuance
  "Attempt-receipt issuance helpers.

   Receipt issuance binds a signed submission-attempt receipt to the committed
   transaction ordering WITHOUT creating a hash cycle:

     - the transaction ordering commits the chain-state transition
       (state-before/state-after roots), excluding the receipt artifact;
     - the attempt receipt's :attempt-receipt/chain block commits the resulting
       :transaction-ordering/hash;
     - the validator signature is an ATTESTATION over the immutable unsigned
       receipt projection (attached after commit; it does not change the
       receipt identity).

    These helpers are pure. The signer authority
    (resolver-sim.commands.resubmission-issue) independently re-derives the
    transition from the presented pre-state and command, then issues here."
  (:require [resolver-sim.transaction.ordering :as ordering]))

(defn admission-status-for
  "Map a pure transition :status to the receipt's :attempt-receipt/chain
   :admission-status. Only a :committed transition admits a chain successor."
  [transition-status]
  (case transition-status
    :committed :admitted
    :not-admitted))

(defn receipt-candidate
  "Attach the :attempt-receipt/chain block to a candidate receipt.

   `candidate` must already carry roots, results status, submitter, outcome,
   finality, eligibility, findings, evaluation, and validator authority (per
   valid-receipt-shape?).

   `chain` facts:
     {:admission-status kw
      :family-id str
      :sequence int
      :parent-receipt-hash str|nil
      :transaction-ordering-hash str}"
  [candidate {:keys [admission-status family-id sequence parent-receipt-hash
                     transaction-ordering-hash]}]
  (assoc candidate :attempt-receipt/chain
         {:admission-status admission-status
          :family-id family-id
          :sequence sequence
          :parent-receipt-hash parent-receipt-hash
          :transaction-ordering-hash transaction-ordering-hash}))

(defn transition-outcome-matches?
  "The receipt's claimed admission status must be consistent with the pure
   transition outcome."
  [transition-result claimed-admission]
  (= (admission-status-for (:status transition-result)) claimed-admission))

(defn receipt-binds-ordering?
  "The receipt's :attempt-receipt/chain must commit the ordering hash and claim
   :admitted. The ordering is authoritative for what actually committed.

   The binding is only trustworthy if the ordering itself is sound, so this
   additionally requires the ordering's self-hash to recompute (ordering/
   verify-ordering) and the action to be the admit-child admission it claims.

   NOTE: the admit-child action gate is a whitelist of exactly one ordering
   action. If new ordering actions are introduced, this predicate must be
   extended to admit them."
  [receipt ordering]
  (and (map? ordering)
       (map? receipt)
       (:valid? (ordering/verify-ordering ordering))
       (= :prf.resubmission/admit-child (:transaction/action ordering))
       (= (:transaction-ordering/hash ordering)
          (get-in receipt [:attempt-receipt/chain :transaction-ordering-hash]))
       (= :admitted (get-in receipt [:attempt-receipt/chain :admission-status]))))
