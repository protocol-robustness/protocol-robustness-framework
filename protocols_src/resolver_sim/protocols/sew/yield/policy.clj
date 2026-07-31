(ns resolver-sim.protocols.sew.yield.policy
  "Sew-specific rules for yield distribution and fee capture."
  (:require [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.protocols.sew.accounting :as acct]
            [resolver-sim.yield.accounting :as yield-acct]))

(defn apply-yield-policy
  "Allocate realized yield to claimable balances and protocol fees based on policy.
   settlement-outcome: :released, :refunded, :resolved-release, :resolved-refund, etc."
  [world escrow-id settlement-outcome]
  (let [et       (t/get-transfer world escrow-id)
        snap     (t/get-snapshot world escrow-id)
        settings (t/get-settings world escrow-id)
        token    (:token et)
        owner-id [:sew/escrow escrow-id]
        pos-key  [:yield/positions owner-id]
        position (get-in world pos-key)
        shortfall (:shortfall position)
        ;; Under partial-yield shortfall only the liquid (realized) leg is claimable now.
        yield    (if (yield-acct/partial-yield-shortfall? position shortfall)
                   (:realized-yield position 0)
                   (+ (:realized-yield position 0) (:unrealized-yield position 0)))]
    (if (or (nil? position) (zero? yield))
      world
      (let [fee-bps (or (:yield-protocol-fee-bps snap) 0)
            ;; Protocol fee applies only to positive yield: a negative (mark-to-
            ;; market) yield is a principal drawdown, not income, so no fee is
            ;; assessed. Charging a negative fee would corrupt the token fee
            ;; accumulator (total-fees is monotonic non-negative).
            fee     (max 0 (t/compute-fee yield fee-bps))
            net     (- yield fee)
            preset  (t/normalize-yield-preset (:yield-preset settings :off))

            ;; Determine who gets the net yield based on outcome and preset
            [sender-amt recipient-amt]
            (case [settlement-outcome preset]
              ;; If released, recipient usually gets it if enabled
              [:released :to-recipient] [0 net]
              [:released :to-sender]    [net 0]
              [:released :split-50-50]  [(quot net 2) (- net (quot net 2))]

              ;; If refunded, sender usually gets it; to-recipient preset does not
              ;; pay yield to recipient on refund (escrow returned to sender).
              [:refunded :to-sender]    [net 0]
              [:refunded :to-recipient] [0 0]
              [:refunded :split-50-50]  [(quot net 2) (- net (quot net 2))]

              ;; Defaults for :off or other combinations
              [0 0])
            unallocated-net (- net sender-amt recipient-amt)
            fee-recipient (acct/resolve-fee-recipient world token)
            debit-yield (fn [w amount owner destination]
                          (if (pos? amount)
                            (acct/sub-held w token amount
                                           {:action "apply-yield-policy"
                                            :reason :yield-distributed
                                            :extra {:held/action "apply-yield-policy"
                                                    :held/workflow-id escrow-id
                                                    :owner/address owner
                                                    :held/recipient owner
                                                    :held/destination destination
                                                    :held/settlement-outcome settlement-outcome
                                                    :held/yield-preset preset}})
                            w))]
        (let [world' (-> world
                         (cond-> (pos? fee)
                           (acct/record-fee token fee))
                         (cond-> (pos? sender-amt)
                           (acct/record-claimable-v2 escrow-id :settlement/yield (:from et) sender-amt))
                         (cond-> (pos? recipient-amt)
                           (acct/record-claimable-v2 escrow-id :settlement/yield (:to et) recipient-amt))
                         (debit-yield sender-amt (:from et) :sender-yield)
                         (debit-yield recipient-amt (:to et) :recipient-yield)
                         (debit-yield fee fee-recipient :protocol-fee)
                         (debit-yield unallocated-net fee-recipient :unallocated-yield)
                         (cond-> (pos? unallocated-net)
                           (acct/record-fee token unallocated-net)))]
          (let [settled-position
                (when (and position (not shortfall))
                  (assoc position :status :settled :realized-yield 0 :unrealized-yield 0))]
            (cond-> world'
              shortfall
              (assoc-in pos-key (assoc position :realized-yield 0 :unrealized-yield 0))
              settled-position
              (assoc-in pos-key settled-position))))))))
