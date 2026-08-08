(ns resolver-sim.demos.not-admitted.scenario
  "Demo scenario: an escrow deposit and its committed evidence.

   Clerk-free. This is the 'before' state of the demonstration and the single
   intervention applied to it. The evidence is derived from a canonical
   held-adjustment ledger via the real custody machinery; no demo-specific
   data shape is invented."
  (:require [resolver-sim.assurance.custody :as custody]))

(defn baseline-adjustments
  "The canonical held-adjustment ledger for the demo's before-state: one escrow
   deposit of 1,000 USDC into workflow 0."
  []
  [{:held-adjustment/id "held-adjustment-1"
    :held/direction :in
    :token :USDC
    :amount 1000
    :held/before 0
    :held/after 1000
    :held/reason :escrow-principal-deposited
    :held/action "deposit-wf-0"
    :held/account :escrow-principal
    :held/workflow-id 0
    :owner/address "0xAlice"}])

(defn baseline-artifacts
  "The committed evidence chain derived from the ledger. Each artifact carries a
   content-addressed signature (:artifact/hash) over its own fields."
  [adjustments]
  (vals (custody/rebuild-held-custody-artifacts adjustments)))

(defn change-recorded-amount
  "The single intervention: edit the recorded amount and the resulting balance
   on the committed evidence, leaving the committed signature (:artifact/hash)
   untouched. This is exactly what a direct write to the on-record evidence
   looks like after a result was verified."
  [artifacts to]
  (mapv #(assoc % :amount to :held/after to) artifacts))
