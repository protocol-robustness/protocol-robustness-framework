(ns prf.capital-lifecycle.transition
  "Pure, provisional Protected Capital lifecycle state transitions.

   This kernel owns lifecycle guards and accounting state only. Callers supply
   source, assurance, and custody results; this namespace performs no I/O,
   hashing, proof verification, or token transfers.")

(def initial-state
  {:status "none"
   :operation-id nil
   :reserved-amount 0N
   :expires-at nil
   :funded-amount 0N
   :entitlement-count 0
   :claimed-count 0
   :liability-total 0N
   :liability-remaining 0N
   :claimed-ids []})

(defn- reject [reason]
  {:accepted? false :reason reason})

(defn- accepted [state]
  {:accepted? true :state state})

(defn- amount-value [value]
  (cond
    (string? value) (try
                      (bigint (java.math.BigInteger. value))
                      (catch NumberFormatException _ nil))
    (integer? value) (bigint value)
    :else nil))

(defn transition
  "Apply one closed lifecycle command to state.

   External assurance and exact custody outcomes are explicit command inputs;
   a rejection returns no changed state. Amounts are arbitrary precision
   non-negative integers."
  [state command]
  (let [s (merge initial-state state)
        op (:operation-id command)
        same-op? (and (string? op) (not-empty op) (= op (:operation-id s)))
        amount (or (amount-value (:amount command)) 0N)]
    (case (:type command)
      "reserve"
      (cond
        (not= "none" (:status s)) (reject "operation-not-new")
        (not (and (string? op) (not-empty op))) (reject "invalid-operation-id")
        (not (:authorized? command)) (reject "not-authorized")
        (not (pos? amount)) (reject "invalid-amount")
        (<= (:expires-at command 0) (:now command 0)) (reject "invalid-expiry")
        :else (accepted (assoc s :status "reserved" :operation-id op
                               :reserved-amount amount
                               :expires-at (:expires-at command))))

      "release"
      (cond
        (not same-op?) (reject "operation-mismatch")
        (not= "reserved" (:status s)) (reject "reservation-not-live")
        (not (:authorized? command)) (reject "not-authorized")
        :else (accepted (assoc s :status "released" :reserved-amount 0N)))

      "expire"
      (cond
        (not same-op?) (reject "operation-mismatch")
        (not= "reserved" (:status s)) (reject "reservation-not-live")
        (< (:now command 0) (:expires-at s)) (reject "reservation-not-expired")
        :else (accepted (assoc s :status "expired" :reserved-amount 0N)))

      "fund"
      (cond
        (not same-op?) (reject "operation-mismatch")
        (not= "reserved" (:status s)) (reject "reservation-not-live")
        (>= (:now command 0) (:expires-at s)) (reject "reservation-expired")
        (not= (:reserved-amount s) amount) (reject "funding-amount-mismatch")
        (not= (:reserved-amount s) (amount-value (:custody-received command)))
        (reject "custody-delta-mismatch")
        :else (accepted (assoc s :status "funded" :reserved-amount 0N
                               :funded-amount amount)))

      "activate"
      (cond
        (not same-op?) (reject "operation-mismatch")
        (not= "funded" (:status s)) (reject "operation-not-funded")
        (not (:admitted? command)) (reject "basis-not-admitted")
        (not= (:funded-amount s) (amount-value (:basis-total command)))
        (reject "basis-total-mismatch")
        (not (pos-int? (:entitlement-count command))) (reject "invalid-entitlement-count")
        :else (accepted (assoc s :status "activated"
                               :entitlement-count (:entitlement-count command)
                               :liability-total (:funded-amount s)
                               :liability-remaining (:funded-amount s))))

      "cancel"
      (cond
        (not same-op?) (reject "operation-mismatch")
        (not= "funded" (:status s)) (reject "operation-not-cancellable")
        (not (:authorized? command)) (reject "not-authorized")
        (not= (:funded-amount s) (amount-value (:custody-refunded command)))
        (reject "refund-delta-mismatch")
        :else (accepted (assoc s :status "cancelled-refunded" :funded-amount 0N)))

      "withdraw"
      (cond
        (not same-op?) (reject "operation-mismatch")
        (not= "activated" (:status s)) (reject "operation-not-active")
        (some #{(:entitlement-id command)} (:claimed-ids s)) (reject "entitlement-already-claimed")
        (not (and (string? (:entitlement-id command))
                  (not-empty (:entitlement-id command)))) (reject "invalid-entitlement-id")
        (not (:admitted? command)) (reject "withdrawal-not-admitted")
        (>= (:claimed-count s) (:entitlement-count s)) (reject "entitlement-count-exhausted")
        (not (pos? amount)) (reject "invalid-amount")
        (> amount (:liability-remaining s)) (reject "liability-exceeded")
        (not= amount (amount-value (:custody-sent command))) (reject "custody-delta-mismatch")
        :else (accepted (-> s
                            (update :claimed-count inc)
                            (update :claimed-ids conj (:entitlement-id command))
                            (update :liability-remaining - amount))))

      "close"
      (cond
        (not same-op?) (reject "operation-mismatch")
        (not= "activated" (:status s)) (reject "operation-not-active")
        (or (not= (:claimed-count s) (:entitlement-count s))
            (not (zero? (:liability-remaining s)))) (reject "liability-outstanding")
        :else (accepted (assoc s :status "closed")))

      (reject "unknown-command"))))
