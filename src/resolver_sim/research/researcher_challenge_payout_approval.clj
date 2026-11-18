(ns resolver-sim.research.researcher-challenge-payout-approval
  "Derive BountySettlement approval arguments from existing challenge evidence.

   This is an invocation adapter, not a new reward ledger or signed artifact.
   The settlement contract remains the idempotency authority."
  (:require [resolver-sim.network.researcher-challenges :as challenges]
            [resolver-sim.research.researcher-challenge-payee :as payee]
            [resolver-sim.research.researcher-challenge-payout-policy :as policy]))

(defn- ensure! [condition reason]
  (when-not condition (throw (ex-info (name reason) {:reason reason}))))

(defn- root-bytes32 [root]
  (when (and (string? root) (re-matches #"sha256:[0-9a-f]{64}" root))
    (str "0x" (subs root 7))))

(defn- padded-word [hex]
  (when (and (string? hex) (re-matches #"[0-9a-f]{1,64}" hex))
    (str (apply str (repeat (- 64 (count hex)) "0")) hex)))

(defn- address-word [address]
  (when (and (string? address) (re-matches #"0x[0-9a-f]{40}" address))
    (str (apply str (repeat 24 "0")) (subs address 2))))

(defn- amount-word [amount]
  (when (and (integer? amount) (pos? amount))
    (padded-word (.toString (biginteger amount) 16))))

(defn build-approval-arguments
  "Verify the accepted obligation, payout policy and researcher consent, then
   derive the exact arguments for `BountySettlement.approveTokenBounty`.

   The payout contract must have been funded under the source root signed into
   the policy. On-chain authorization still requires the contract's configured
   award authority; this function only prepares verified call data."
  [config history payout-policy authorization trusted-key-content]
  (let [policy-context (policy/verify-policy config payout-policy trusted-key-content)
        consent (payee/verify-authorization config history authorization
                                            payout-policy trusted-key-content)
        state (challenges/replay config history)
        obligation-root (:obligation/root consent)
        payout-records (get-in state [:payouts obligation-root] [])
        payable-id (root-bytes32 obligation-root)
        operation-root (root-bytes32 (:authorization/root consent))
        funding-source-root (:funding/source-root policy-context)
        asset-address (:asset/id consent)
        beneficiary-address (:beneficiary/address consent)
        amount (:asset/amount consent)
        arguments [payable-id operation-root funding-source-root
                   asset-address beneficiary-address amount]
        words (mapv padded-word [(some-> payable-id (subs 2))
                                 (some-> operation-root (subs 2))
                                 (some-> funding-source-root (subs 2))])
        call-data (when (and (every? some? words)
                             (address-word asset-address)
                             (address-word beneficiary-address)
                             (amount-word amount))
                    (str "0xc1537572" (apply str words)
                         (address-word asset-address)
                         (address-word beneficiary-address)
                         (amount-word amount)))]
    (ensure! (and payable-id operation-root
                  (re-matches #"0x[0-9a-f]{64}" funding-source-root)
                  (= true (:policy/demo-only? policy-context))
                  (= (:payout/sender policy-context)
                     (:payout/sender payout-policy))
                  (= (:policy/root policy-context)
                     (:payout/policy-root consent))
                  call-data
                  (empty? payout-records))
             :challenge-reward-not-approvable)
    {:contract/address (:payout/sender policy-context)
     :contract/code-sha256 (:payout/executor-code-sha256 policy-context)
     :payout/policy-root (:policy/root policy-context)
     :funding/backing-root (:funding/backing-root policy-context)
     :funding/source-root funding-source-root
     :payable/id payable-id
     :operation/root operation-root
     :asset/address (:asset/id consent)
     :beneficiary/address (:beneficiary/address consent)
     :asset/amount (:asset/amount consent)
     :calldata/signature
     "approveTokenBounty(bytes32,bytes32,bytes32,address,address,uint256)"
     :calldata/arguments arguments
     :calldata/hex call-data}))
