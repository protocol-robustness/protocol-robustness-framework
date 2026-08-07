(ns resolver-sim.protocols.sew.invariants.solvency
  "Solvency-related invariant predicates for the Sew contract model.

   These are the ACCOUNTING / OBSERVED-COVERAGE guarantees:

     - solvency-holds?        strict held-custody reconciliation: total-held[t]
                              exactly equals live custody obligations. This is an
                              accounting-consistency predicate, NOT a complete
                              economic-solvency test. Economic solvency is
                              measured by
                              resolver-sim.protocols.sew.financial.solvency/
                              economic-solvency? over the canonical liability set.

     - contract-payout-solvency?  observed coverage: do externally evidenced
                              custody balances cover modeled outstanding
                              obligations? Absence of evidence is fail-closed
                              (:status :not-evaluated, :coverage :unavailable) —
                              never a silent pass.

   The canonical liability universe lives in
   resolver-sim.protocols.sew.financial.liabilities. The extractors below
   delegate to it so no invariant independently re-decides which buckets count."
  (:require [resolver-sim.protocols.sew.financial.liabilities :as liab]))

(defn- tk
  "Normalize a token key to a keyword so string/keyword representations of the
   same asset concatenate into one bucket (matching the liability primitive)."
  [x]
  (if (keyword? x) x (keyword (str x))))

(defn- normalize-token-keys
  [m]
  (into {} (map (fn [[k v]] [(tk k) v])) (or m {})))

(defn get-escrow-afa-sum [world token live-states]
  (get (liab/escrow-liability-by-token world) token 0))

(defn get-bond-held-sum [world token]
  (get (liab/bond-liability-by-token world) token 0))

(defn get-slash-appeal-bond-sum [world token]
  (get (liab/slash-appeal-bond-liability-by-token world) token 0))

(defn get-yield-held-sum [world token live-states]
  (get (liab/yield-liability-by-token world) token 0))

(defn- claimable-v2-by-token
  "Sum every outstanding v2 claimable by the token of its workflow. The legacy
   :claimable map is deliberately excluded because settlement principal/yield
   are dual-written there and including it would double-count liabilities."
  [world]
  (liab/claimable-v2-liability-by-token world))

(defn- contract-balance
  "Read a contract/token balance from the external snapshot. The canonical
   shape is {[:contract-id token] amount}; nested {contract-id {token amount}}
   is accepted for convenient RPC adapters."
  [balances contract-id token]
  (or (get balances [contract-id token])
      (get-in balances [contract-id token])))

(defn contract-payout-solvency?
  "Check whether each Solidity custody contract has enough observed ERC-20
   balance to pay modeled outstanding obligations.

   External balance evidence is read from :solvency/contract-balances using
   {[:contract-id token] amount}, or its nested equivalent. Token routing is
   read from [:params :solvency/token-custody-contracts token], defaulting to
   :escrow-vault.

   Liability calculation is deliberately conservative:
   :total-held + all :claimable-v2 entries + unwithdrawn :total-fees +
   unwithdrawn :bond-fees. The legacy :claimable map is excluded because it
   dual-writes settlement claims.

   ABSENCE OF EVIDENCE IS NOT A SILENT PASS. With no snapshot the invariant
   returns :holds? true (vacuous — nothing evaluated) but :status :not-evaluated
   and :coverage :unavailable so consumers can never mistake it for verified
   coverage. run-single-invariants surfaces :not-evaluated separately, and
   the assessment layer reports :evidence/status :unavailable (optionally
   downgrading to :unassessable when external coverage is required)."
  [world]
  (let [balances (:solvency/contract-balances world)
        claimables (claimable-v2-by-token world)
        tokens (-> (set (map tk (keys (:total-held world))))
                   (into (keys claimables))
                   (into (map tk (keys (:total-fees world))))
                   (into (map tk (keys (:bond-fees world)))))]
    (cond
      (nil? balances)
      {:holds? true
       :status :not-evaluated
       :coverage :unavailable
       :violations []
       :note "No external Solidity custody balance snapshot supplied — coverage cannot be assessed"}

      (not (map? balances))
      {:holds? false
       :status :evaluated
       :coverage :invalid-evidence
       :violations [{:type :invalid-balance-snapshot :snapshot balances}]
       :note "External balance snapshot is not a map"}

      :else
      (let [violations
            (vec
             (keep (fn [token]
                     (let [contract-id (or (get-in world [:params :solvency/token-custody-contracts token])
                                           (get-in world [:params :solvency/token-custody-contracts (name token)])
                                           :escrow-vault)
                           assets (contract-balance balances contract-id token)
                           held (get-in world [:total-held token] 0)
                           held* (or held (get-in world [:total-held (name token)] 0))
                           claimable (get claimables token 0)
                           fees (get-in world [:total-fees token] 0)
                           fees* (or fees (get-in world [:total-fees (name token)] 0))
                           bond-fees (get-in world [:bond-fees token] 0)
                           bond-fees* (or bond-fees (get-in world [:bond-fees (name token)] 0))
                           liabilities (+ held* claimable fees* bond-fees*)]
                       (cond
                         (nil? assets)
                         {:type :missing-contract-balance
                          :contract contract-id :token token
                          :liabilities liabilities}

                         (< assets liabilities)
                         {:type :contract-payout-shortfall
                          :contract contract-id :token token
                          :assets assets :liabilities liabilities
                          :shortfall (- liabilities assets)
                          :held-custody held
                          :claimable claimable
                          :fees fees
                          :bond-fees bond-fees})))
                   tokens))
            incomplete? (some #(= :missing-contract-balance (:type %)) violations)]
        {:holds? (empty? violations)
         :status :evaluated
         :coverage (cond
                     (seq violations) (if incomplete? :invalid-evidence :insufficient)
                     :else :verified)
         :violations violations}))))

(defn held-custody-reconciles?
  "Strict held-custody reconciliation: total-held[token] exactly equals the sum
   of live custody obligations.

   Liabilities = [Live Escrow AFAs] + [Active Bonds] + [Slash Appeal Bonds]
                 + [Yield component on live positions]

   Recognized (haircut) losses are deliberately NOT added: a realized loss is a
   flow that has already reduced both entitlement and custody; adding it again
   would double-count. Value destruction is tracked by conservation-of-funds?.

   The internal invariant is STRICT EQUALITY (=). Surplus custody (held >
   obligations) is an accounting anomaly here, though it may be economically
   benign — economic sufficiency is measured by economic-solvency?.

   This predicate answers \"does the custody ledger balance?\" (accounting
   consistency), NOT \"is the protocol economically solvent?\".
   solvency-holds? is retained only as a deprecated compatibility alias.
   Do not use it to establish economic solvency."
  ([world] (held-custody-reconciles? world nil))
  ([world token-balances]
   (let [held-by-token (normalize-token-keys (:total-held world))
         all-tokens    (-> (set (keys held-by-token))
                           (into (map #(tk (:token %)) (vals (:escrow-transfers world))))
                           (into (map tk (keys (:total-bonds-posted world)))))
         liabilities-by-token
         (merge-with +
                     (liab/escrow-liability-by-token world)
                     (liab/bond-liability-by-token world)
                     (liab/slash-appeal-bond-liability-by-token world)
                     (liab/yield-liability-by-token world))
         violations
         (for [token all-tokens
               :let  [held       (get held-by-token token 0)
                      liabilities (get liabilities-by-token token 0)
                      ext-bal    (when token-balances (get (normalize-token-keys token-balances) token 0))
                      internal-ok? (= liabilities held)
                      external-ok? (or (nil? ext-bal) (<= held ext-bal))]
               :when (not (and internal-ok? external-ok?))]
           {:token       token
            :liabilities liabilities
            :held        held
            :ext-bal     ext-bal
            :internal-ok? internal-ok?
            :external-ok? external-ok?})]
     {:holds?     (empty? violations)
      :violations (vec violations)})))

(defn solvency-holds?
  "DEPRECATED: renamed to held-custody-reconciles?. This predicate is the
   strict held-custody ACCOUNTING reconciliation, not economic solvency.
   Retained as a compatibility alias only — do not use it to establish
   economic solvency."
  ([world] (held-custody-reconciles? world))
  ([world token-balances] (held-custody-reconciles? world token-balances)))
