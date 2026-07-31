(ns resolver-sim.grounded-amount
  "Cross-artifact grounded-amount projection contract.

   An amount is only meaningful together with its token, basis, and the roots
   that give it context. A bare number is deliberately NOT promoted to an
   artifact. This namespace owns the reusable projection contract used across
   economic artifacts (pool availability, distributions, deferrals, etc.).

   Contract: {:amount/value <n>, :amount/token <t>, :amount/basis <k>,
              :amount/source-root <root>, :amount/as-of-root <root>?}.

   Amounts are deliberately NOT first-class hashed artifacts: an amount is
   committed as part of the artifact that gives it meaning (e.g. deferral.v1
   commits its :deferral/amount inside its own preimage). This namespace owns
   only the reusable projection and validation contract.")

(def amount-value-key :amount/value)
(def amount-token-key :amount/token)
(def amount-basis-key :amount/basis)
(def amount-source-root-key :amount/source-root)
(def amount-as-of-root-key :amount/as-of-root)

(defn grounded-amount
  "Construct a grounded amount projection: an amount with the token, basis, and
   the roots that give it context. `source-root` is the committed root that the
   amount is derived from; `:as-of-root` (optional) is the committed state root
   the amount is measured at."
  [value token basis source-root & {:keys [as-of-root]}]
  (cond-> {amount-value-key value
           amount-token-key token
           amount-basis-key basis
           amount-source-root-key source-root}
    as-of-root (assoc amount-as-of-root-key as-of-root)))

(defn grounded-amount?
  "True when `amount` is a well-formed grounded amount: it carries a value, token,
   basis, and source root. A bare number or a partial projection is not grounded."
  [amount]
  (and (map? amount)
       (some? (get amount amount-value-key))
       (some? (get amount amount-token-key))
       (some? (get amount amount-basis-key))
       (some? (get amount amount-source-root-key))))

(defn validate-grounded-amount!
  "Return `amount` when it is a well-formed grounded amount, otherwise throw.
   Never silently accepts a bare or partial amount where grounding is required."
  [amount]
  (when-not (grounded-amount? amount)
    (throw (ex-info "amount is not a grounded amount projection"
                    {:type :invalid-grounded-amount
                     :amount amount})))
  amount)
