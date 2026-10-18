(ns resolver-sim.economics.with-bounty.obligation-settlement
  "Canonical settlement evidence for a bounty payable.

   The chain observer signature authenticates parsed chain execution facts
   under an explicit trust policy. This verifier does not itself query a node,
   validate a consensus proof, or establish finality."
  (:require [resolver-sim.economics.bounty-payable :as bp]
            [resolver-sim.economics.bounty-payable-backing :as bpb]
            [resolver-sim.economics.with-bounty.payout-evidence :as payout]
            [resolver-sim.economics.with-bounty.rpc-observation :as rpc-observation]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.signed-external-decision :as sed]))

(def schema "obligation-settlement.v1")
(def observation-domain "PRF_BOUNTY_CHAIN_OBSERVATION_V1")
(def observer-role :bounty-chain-observer)
(def observation-schema "bounty-chain-observation.v1")

(def observation-fields
  #{:observation/schema :observation/chain-id :observation/asset
    :observation/contract :observation/chain-instance-root
    :observation/genesis-hash :observation/contract-code-sha256
    :observation/source-id :observation/transaction-hash :observation/block-number
    :observation/block-hash :observation/log-index :observation/finality
    :observation/payable-id :observation/operation-root
    :observation/funding-source-root :observation/prf-funding-source-root
    :observation/beneficiary
    :observation/amount :observation/sequence
    :observation/reserve-before :observation/reserve-after
    :observation/beneficiary-before :observation/beneficiary-after
    :observation/fee-amount :observation/fee-payer :signature})

(def settlement-fields
  [:settlement/schema :settlement/payable-id :settlement/payable-root-before
   :settlement/payable-root-after :settlement/backing-root
   :settlement/funding-source-root :settlement/onchain-funding-source-root
   :settlement/operation-root
   :settlement/asset :settlement/beneficiary :settlement/route
   :settlement/payout-evidence-root :settlement/observation-root
   :settlement/chain-id :settlement/contract :settlement/chain-instance-root
   :settlement/genesis-hash :settlement/contract-code-sha256
   :settlement/observation-source-id
   :settlement/transaction-hash :settlement/sequence :settlement/block-number
   :settlement/block-hash :settlement/log-index :settlement/finality
   :settlement/obligation-before :settlement/paid
   :settlement/remaining-liability :settlement/authorized-adjustments
   :settlement/fee-amount :settlement/fee-payer])

(defn sign-chain-observation
  "Sign a parsed chain observation. The observer's key and policy are external
   trust anchors; this operation does not attest chain data by itself."
  [{:keys [observation private-key key-id]}]
  (sed/sign-envelope (assoc observation :observation/schema observation-schema)
                     observation-domain private-key key-id))

(defn- observation-shape? [observation]
  (and (= observation-schema (:observation/schema observation))
       (= observation-fields (set (keys observation)))
       (every? #(and (integer? (get observation %)) (not (neg? (get observation %))))
               [:observation/chain-id :observation/block-number
                :observation/log-index :observation/amount
                :observation/sequence :observation/reserve-before
                :observation/reserve-after :observation/beneficiary-before
                :observation/beneficiary-after :observation/fee-amount])
       (every? #(and (string? (get observation %)) (seq (get observation %)))
               [:observation/contract :observation/chain-instance-root
                :observation/genesis-hash :observation/contract-code-sha256
                :observation/source-id
                :observation/transaction-hash :observation/block-hash
                :observation/payable-id :observation/operation-root
                :observation/funding-source-root :observation/prf-funding-source-root
                :observation/beneficiary])
       (re-matches #"sha256:[0-9a-f]{64}"
                   (:observation/chain-instance-root observation))
       (re-matches #"0x[0-9a-fA-F]{64}" (:observation/genesis-hash observation))
       (re-matches #"sha256:[0-9a-f]{64}"
                   (:observation/contract-code-sha256 observation))
       (or (keyword? (:observation/asset observation))
           (and (string? (:observation/asset observation))
                (seq (:observation/asset observation))))
       (contains? #{:controlled-execution :included :finalized}
                  (:observation/finality observation))
       (contains? #{:beneficiary :sponsor :relayer}
                  (:observation/fee-payer observation))))

(defn verify-chain-observation
  [observation trust-policy]
  (let [signature (sed/verify-envelope observation observation-domain
                                        trust-policy observer-role)]
    (cond
      (not (observation-shape? observation))
      {:valid? false :reason :invalid-chain-observation}
      (not (:valid? signature)) signature
      (not= (- (:observation/reserve-before observation)
               (:observation/reserve-after observation))
            (:observation/amount observation))
      {:valid? false :reason :reserve-delta-mismatch}
      (not= (+ (:observation/beneficiary-before observation)
               (:observation/amount observation))
            (:observation/beneficiary-after observation))
      {:valid? false :reason :beneficiary-delta-mismatch}
      :else {:valid? true
             :key-id (:key-id signature)
             :observation-root (:signed-hash (:signature observation))})))

(defn settlement-body
  "Project a settlement into the exact canonical, signature-free body."
  [settlement]
  (select-keys settlement settlement-fields))

(defn settlement-root [settlement]
  (hc/domain-hash :obligation-settlement-v1 (settlement-body settlement)))

(defn- independent-authorities? [payout-check observation-check
                                 payout-trust-policy observation-trust-policy]
  (let [payout-key (some #(when (= (:key/id %) (:key-id payout-check)) %)
                         (:trusted-keys payout-trust-policy))
        observer-key (some #(when (= (:key/id %) (:key-id observation-check)) %)
                           (:trusted-keys observation-trust-policy))]
    (and payout-key observer-key
         (not= (:key/id payout-key) (:key/id observer-key))
         (not= (:key/public payout-key) (:key/public observer-key)))))

(defn build-settlement
  "Build a canonical full-payment settlement from a signed payout receipt and
   a separately trusted parsed chain observation. `payable-before` and
   `payable-after` are canonical bounty-payable artifacts."
  [{:keys [payable-before payable-after backing payout-receipt payout-trust-policy
           observation observation-trust-policy expected-context]}]
  (let [context (:backing/context backing)
        payout-check (payout/verify-payout
                      payout-receipt payout-trust-policy
                      {:payout/sequence 1
                       :payout/payable-id (:payable/id payable-before)
                       :payout/predecessor-root (:payable/hash payable-before)
                       :payout/successor-root (:payable/hash payable-after)
                       :payout/backing-root (:backing/hash backing)
                       :payout/beneficiary (:payable/beneficiary payable-before)
                       :payout/token (:funding/token context)
                       :payout/amount (:payable/amount payable-before)
                       :payout/operation-root (:funding/operation-root context)})
        observation-check (verify-chain-observation observation observation-trust-policy)
        event-match? (and (= (:payable/id payable-before)
                             (:observation/payable-id observation))
                          (= (:funding/operation-root context)
                             (:observation/operation-root observation))
                          (= (:funding/source-root context)
                             (:observation/prf-funding-source-root observation))
                          (= (:funding/token context) (:observation/asset observation))
                          (= (:chain-id expected-context)
                             (:observation/chain-id observation))
                          (= (:contract expected-context)
                             (:observation/contract observation))
                          (= (:chain-instance-root expected-context)
                             (:observation/chain-instance-root observation))
                          (= (:genesis-hash expected-context)
                             (:observation/genesis-hash observation))
                          (= (:contract-code-sha256 expected-context)
                             (:observation/contract-code-sha256 observation))
                          (= (:observation-source-id expected-context)
                             (:observation/source-id observation))
                          (= (:asset expected-context)
                             (:observation/asset observation))
                          (= (:funding-source-root expected-context)
                             (:observation/funding-source-root observation))
                          (= (:payable/beneficiary payable-before)
                             (:observation/beneficiary observation))
                          (= (:payable/amount payable-before)
                             (:observation/amount observation))
                          (= (:payout/payment-reference payout-receipt)
                             (:observation/transaction-hash observation)))
        obligations {:settlement/obligation-before (:payable/amount payable-before)
                     :settlement/paid (:observation/amount observation)
                     :settlement/remaining-liability 0
                     :settlement/authorized-adjustments 0}
        base (merge
              {:settlement/schema schema
               :settlement/payable-id (:payable/id payable-before)
               :settlement/payable-root-before (:payable/hash payable-before)
               :settlement/payable-root-after (:payable/hash payable-after)
               :settlement/backing-root (:backing/hash backing)
               :settlement/funding-source-root (:funding/source-root context)
               :settlement/onchain-funding-source-root
               (:observation/funding-source-root observation)
               :settlement/operation-root (:funding/operation-root context)
               :settlement/asset (:observation/asset observation)
               :settlement/beneficiary (:observation/beneficiary observation)
               :settlement/route :direct-native-transfer
               :settlement/payout-evidence-root (:event-root payout-check)
               :settlement/observation-root (:observation-root observation-check)
               :settlement/chain-id (:observation/chain-id observation)
               :settlement/contract (:observation/contract observation)
               :settlement/chain-instance-root
               (:observation/chain-instance-root observation)
               :settlement/genesis-hash (:observation/genesis-hash observation)
               :settlement/contract-code-sha256
               (:observation/contract-code-sha256 observation)
               :settlement/observation-source-id (:observation/source-id observation)
               :settlement/transaction-hash (:observation/transaction-hash observation)
               :settlement/sequence (:observation/sequence observation)
               :settlement/block-number (:observation/block-number observation)
               :settlement/block-hash (:observation/block-hash observation)
               :settlement/log-index (:observation/log-index observation)
               :settlement/finality (:observation/finality observation)
               :settlement/fee-amount (:observation/fee-amount observation)
               :settlement/fee-payer (:observation/fee-payer observation)}
              obligations)
        artifact (assoc base :settlement/root (settlement-root base))]
    (when-not (and (:valid? payout-check) (:valid? observation-check)
                   (independent-authorities? payout-check observation-check
                                             payout-trust-policy observation-trust-policy)
                   event-match?
                   (:valid? (bp/verify-bounty-payable payable-before))
                   (:valid? (bp/verify-bounty-payable payable-after))
                   (= payable-after
                      (bp/transition-payable-lifecycle payable-before :settled))
                   (:valid? (bpb/verify-bounty-payable-backing backing))
                   (= :backed (:payable/lifecycle payable-before))
                   (= (:payable/amount payable-before) (:backing/amount backing))
                   (= :committed (:backing/lifecycle backing))
                   (= (:payable/hash payable-before) (:backing/payable-root backing))
                   (= :settled (:payable/lifecycle payable-after))
                   (= (- (:observation/reserve-before observation)
                         (:observation/reserve-after observation))
                      (:payable/amount payable-before)))
      (throw (ex-info "bounty settlement evidence rejected"
                      {:payout payout-check
                       :observation observation-check
                       :event-match? event-match?})))
    artifact))

(defn verify-settlement
  "Independently replay the settlement's signed payout and observer evidence,
   recompute conservation, then verify the canonical artifact root."
  [{:keys [settlement payable-before payable-after backing payout-receipt
           payout-trust-policy observation observation-trust-policy
           expected-context rpc-client expected-rpc-context
           require-rpc-observation?]}]
  (try
    (let [context (:backing/context backing)
          payout-check (payout/verify-payout
                        payout-receipt payout-trust-policy
                        {:payout/sequence 1
                         :payout/payable-id (:payable/id payable-before)
                         :payout/predecessor-root (:payable/hash payable-before)
                         :payout/successor-root (:payable/hash payable-after)
                         :payout/backing-root (:backing/hash backing)
                         :payout/beneficiary (:payable/beneficiary payable-before)
                         :payout/token (:funding/token context)
                         :payout/amount (:payable/amount payable-before)
                         :payout/operation-root (:funding/operation-root context)})
          observation-check (verify-chain-observation observation
                                                       observation-trust-policy)
          amount (:payable/amount payable-before)
          bindings? (and (= (:payable/id payable-before)
                            (:observation/payable-id observation))
                         (= (:funding/operation-root context)
                            (:observation/operation-root observation))
                         (= (:funding/source-root context)
                            (:observation/prf-funding-source-root observation))
                         (= (:funding/token context) (:observation/asset observation))
                         (= (:chain-id expected-context)
                            (:observation/chain-id observation))
                         (= (:contract expected-context)
                            (:observation/contract observation))
                         (= (:chain-instance-root expected-context)
                            (:observation/chain-instance-root observation))
                         (= (:genesis-hash expected-context)
                            (:observation/genesis-hash observation))
                         (= (:contract-code-sha256 expected-context)
                            (:observation/contract-code-sha256 observation))
                         (= (:observation-source-id expected-context)
                            (:observation/source-id observation))
                         (= (:asset expected-context)
                            (:observation/asset observation))
                         (= (:funding-source-root expected-context)
                            (:observation/funding-source-root observation))
                         (= (:payable/beneficiary payable-before)
                            (:observation/beneficiary observation))
                         (= amount (:observation/amount observation))
                         (= (:payout/payment-reference payout-receipt)
                            (:observation/transaction-hash observation)))
          payable-chain? (and (:valid? (bp/verify-bounty-payable payable-before))
                              (:valid? (bp/verify-bounty-payable payable-after))
                              (= payable-after
                                 (bp/transition-payable-lifecycle
                                  payable-before :settled))
                              (:valid? (bpb/verify-bounty-payable-backing backing))
                              (= :backed (:payable/lifecycle payable-before))
                              (= :settled (:payable/lifecycle payable-after))
                              (= amount (:backing/amount backing))
                              (= (:payable/hash payable-before)
                                 (:backing/payable-root backing))
                              (= :committed (:backing/lifecycle backing)))
          deltas? (and (= amount
                         (- (:observation/reserve-before observation)
                            (:observation/reserve-after observation)))
                       (= amount
                          (+ (:settlement/remaining-liability settlement)
                             (:settlement/authorized-adjustments settlement)
                             (:settlement/paid settlement))))
          expected-body
          {:settlement/schema schema
           :settlement/payable-id (:payable/id payable-before)
           :settlement/payable-root-before (:payable/hash payable-before)
           :settlement/payable-root-after (:payable/hash payable-after)
           :settlement/backing-root (:backing/hash backing)
           :settlement/funding-source-root (:funding/source-root context)
           :settlement/onchain-funding-source-root
           (:observation/funding-source-root observation)
           :settlement/operation-root (:funding/operation-root context)
           :settlement/asset (:observation/asset observation)
           :settlement/beneficiary (:observation/beneficiary observation)
           :settlement/route :direct-native-transfer
           :settlement/payout-evidence-root (:event-root payout-check)
           :settlement/observation-root (:observation-root observation-check)
           :settlement/chain-id (:observation/chain-id observation)
           :settlement/contract (:observation/contract observation)
           :settlement/chain-instance-root
           (:observation/chain-instance-root observation)
           :settlement/genesis-hash (:observation/genesis-hash observation)
           :settlement/contract-code-sha256
           (:observation/contract-code-sha256 observation)
           :settlement/observation-source-id (:observation/source-id observation)
           :settlement/transaction-hash (:observation/transaction-hash observation)
           :settlement/sequence (:observation/sequence observation)
           :settlement/block-number (:observation/block-number observation)
           :settlement/block-hash (:observation/block-hash observation)
           :settlement/log-index (:observation/log-index observation)
           :settlement/finality (:observation/finality observation)
           :settlement/obligation-before amount
           :settlement/paid amount
           :settlement/remaining-liability 0
           :settlement/authorized-adjustments 0
           :settlement/fee-amount (:observation/fee-amount observation)
           :settlement/fee-payer (:observation/fee-payer observation)}
          expected-artifact (assoc expected-body
                                   :settlement/root (settlement-root expected-body))]
      (let [rpc-check (when (and rpc-client expected-rpc-context)
                        (rpc-observation/verify-rpc-observation
                         rpc-client observation expected-rpc-context))]
      (cond
        (not (:valid? payout-check)) {:valid? false :reason :invalid-payout-receipt}
        (not (:valid? observation-check)) {:valid? false :reason :invalid-chain-observation}
        (not (independent-authorities? payout-check observation-check
                                       payout-trust-policy observation-trust-policy))
        {:valid? false :reason :non-independent-authorities}
        (not (and bindings? payable-chain? deltas?))
        {:valid? false :reason :settlement-bindings-or-conservation-failed}
        (not= settlement expected-artifact)
        {:valid? false :reason :settlement-replay-mismatch}
        (and require-rpc-observation? (not (:valid? rpc-check)))
        (merge {:valid? false :reason :rpc-observation-required}
               (select-keys rpc-check [:reason :detail]))
        :else {:valid? true :root (:settlement/root expected-artifact)
               :rpc-observation rpc-check})))
    (catch Exception e
      {:valid? false :reason :settlement-evidence-invalid
       :detail (ex-data e)})))
