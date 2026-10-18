(ns resolver-sim.economics.with-bounty.payout-evidence
  "Authenticated full payout receipts for bounty payables.

   The authority attests that a direct payment completed. This records no
   transfer itself; callers must supply a receipt from the configured payment
   authority and verify it against an external trust policy. Partial payouts
   are deliberately unsupported in v1."
  (:require [resolver-sim.economics.bounty-payable :as bp]
            [resolver-sim.economics.bounty-payable-backing :as bpb]
            [resolver-sim.signed-external-decision :as sed]))

(def domain "PRF_BOUNTY_DIRECT_PAYOUT_V1")
(def authority-role :bounty-payout-authority)
(def schema "bounty-direct-payout.v1")
(def receipt-fields
  #{:payout/schema-version :payout/id :payout/sequence :payout/payable-id
    :payout/predecessor-root :payout/successor-root :payout/backing-root
    :payout/beneficiary :payout/token :payout/amount :payout/operation-root
    :payout/payment-reference :payout/reason :payout/status :signature})

(defn sign-payout
  [{:keys [payout-id sequence payable-id predecessor-root successor-root backing-root
           beneficiary token amount operation-root payment-reference
           private-key key-id]}]
  (sed/sign-envelope
   {:payout/schema-version schema
    :payout/id payout-id
    :payout/sequence sequence
    :payout/payable-id payable-id
    :payout/predecessor-root predecessor-root
    :payout/successor-root successor-root
    :payout/backing-root backing-root
    :payout/beneficiary beneficiary
    :payout/token token
    :payout/amount amount
    :payout/operation-root operation-root
    :payout/payment-reference payment-reference
    :payout/reason :direct-payment-completed
    :payout/status :completed}
   domain private-key key-id))

(defn verify-payout
  "Verify the signed direct-payout receipt and all expected payable bindings."
  [receipt trust-policy expected]
  (let [signature (sed/verify-envelope receipt domain trust-policy authority-role)
        required? (and (= schema (:payout/schema-version receipt))
                       (= receipt-fields (set (keys receipt)))
                       (and (string? (:payout/id receipt)) (seq (:payout/id receipt)))
                       (integer? (:payout/sequence receipt))
                       (pos? (:payout/sequence receipt))
                       (and (string? (:payout/payment-reference receipt))
                            (seq (:payout/payment-reference receipt)))
                       (= :direct-payment-completed (:payout/reason receipt))
                       (= :completed (:payout/status receipt))
                       (integer? (:payout/amount receipt))
                       (pos? (:payout/amount receipt)))
        matches? (every? (fn [[k v]] (= v (get receipt k))) expected)]
    (cond
      (not required?) {:valid? false :reason :invalid-payout-receipt}
      (not (:valid? signature)) signature
      (not matches?) {:valid? false :reason :payout-binding-mismatch}
      :else {:valid? true :key-id (:key-id signature)
             :event-root (:signed-hash (:signature receipt))})))

(defn verify-settled-payout
  "Verify the single full-payment event retained by a settled payable."
  [payable backing events trust-policy]
  (let [context (:backing/context backing)
        event (when (= 1 (count events)) (first events))
        predecessor-payable (let [p (assoc payable :payable/lifecycle :backed)]
                              (assoc p :payable/hash (bp/payable-hash p)))
        predecessor-context (dissoc context :payout/evidence-root
                                    :payout/predecessor-root :payout/backing-root
                                    :payout/custody-adjustment-id
                                    :payout/custody-artifact-hash)
        predecessor-backing-base (assoc backing
                                        :backing/lifecycle :committed
                                        :backing/payable-root
                                        (:payout/predecessor-root context)
                                        :backing/context predecessor-context)
        predecessor-backing (assoc predecessor-backing-base :backing/hash
                                   (bpb/backing-hash predecessor-backing-base))
        expected-backing-base (assoc predecessor-backing
                                     :backing/lifecycle :consumed
                                     :backing/payable-root (:payable/hash payable)
                                     :backing/context context)
        expected-backing (assoc expected-backing-base :backing/hash
                                (bpb/backing-hash expected-backing-base))
        expected {:payout/sequence 1
                  :payout/payable-id (:payable/id payable)
                  :payout/predecessor-root (:payout/predecessor-root context)
                  :payout/successor-root (:payable/hash payable)
                  :payout/backing-root (:payout/backing-root context)
                  :payout/beneficiary (:payable/beneficiary payable)
                  :payout/token (:funding/token context)
                  :payout/amount (:payable/amount payable)
                  :payout/operation-root (:funding/operation-root context)}
        verified (when event (verify-payout event trust-policy expected))]
    (if (and event (:valid? verified)
             (= :settled (:payable/lifecycle payable))
             (= (:payable/hash predecessor-payable)
                (:payout/predecessor-root context))
             (= (:backing/hash predecessor-backing)
                (:payout/backing-root context))
             (= expected-backing backing)
             (= (:payout/evidence-root context) (:event-root verified)))
      {:valid? true :event event :event-root (:event-root verified)}
      {:valid? false :reason (or (:reason verified) :missing-or-invalid-payout-history)})))
