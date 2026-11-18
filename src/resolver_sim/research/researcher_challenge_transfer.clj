(ns resolver-sim.research.researcher-challenge-transfer
  "Re-verifiable ERC-20 transfer checks for existing researcher obligations.

   This composes the existing replay-derived obligation and researcher payee
   consent with an RPC receipt observation. It creates no settlement schema or
   reward ledger and does not prove chain consensus."
  (:require [clojure.string :as str]
            [resolver-sim.economics.with-bounty.rpc-observation :as rpc-observation]
            [resolver-sim.network.researcher-challenges :as challenges]
            [resolver-sim.research.researcher-challenge-payee :as payee]))

(defn verify-transfer
  "Verify an ERC-20 transfer against consent, frozen payout context, and the
   current canonical challenge history.

   A successful report is recomputable from the supplied inputs and configured
   RPC endpoint. `:recorded-paid` history entries, when present, must all name
   this transaction."
  [{:keys [config history authorization payout-policy trusted-key-content
           rpc-context transfer rpc]}]
  (let [consent (payee/verify-authorization config history authorization
                                            payout-policy trusted-key-content)
        to-bytes32 (fn [root]
                     (when (and (string? root)
                                (re-matches #"sha256:[0-9a-f]{64}" root))
                       (str "0x" (subs root 7))))
        context-ok? (and (string? (:asset/id consent))
                         (string? (:beneficiary/address consent))
                         (string? (:token-contract rpc-context))
                         (string? (:beneficiary rpc-context))
                         (= (:chain/id consent) (:chain-id rpc-context))
                         (= (:chain/instance-root consent)
                            (:chain-instance-root rpc-context))
                         (= (:chain/genesis-hash consent)
                            (some-> (:genesis-hash rpc-context) str/lower-case))
                         (= (str/lower-case (:asset/id consent))
                            (str/lower-case (:token-contract rpc-context)))
                         (= (:asset/code-sha256 consent)
                            (:token-code-sha256 rpc-context))
                         (= (:asset/decimals consent)
                            (:asset-decimals rpc-context))
                         (= (:asset/amount consent) (:amount rpc-context))
                         (= (:payout/sender consent)
                            (some-> (:sender rpc-context) str/lower-case))
                         (= (:payout/required-finality consent)
                            (:required-finality rpc-context))
                         (= (:beneficiary/address consent)
                            (str/lower-case (:beneficiary rpc-context))))
        _ (when-not context-ok?
            (throw (ex-info "ERC-20 payout context differs from researcher consent"
                            {:reason :researcher-transfer-context-mismatch})))
        chain-check (rpc-observation/verify-erc20-transfer
                     rpc transfer
                     (assoc rpc-context
                            :payable-id (to-bytes32 (:obligation/root consent))
                            :operation-root (to-bytes32 (:authorization/root consent))
                            :funding-source-root (:funding/source-root consent)
                            :payout-code-sha256
                            (:payout/executor-code-sha256 consent)))
        _ (when-not (:valid? chain-check)
            (throw (ex-info "ERC-20 transfer did not verify"
                            {:reason (:reason chain-check)
                             :observation chain-check})))
        state (challenges/replay config history)
        paid-records (filter #(= :recorded-paid (:payout/status %))
                             (get-in state [:payouts (:obligation/root consent)] []))
        tx-hash (:transaction-hash chain-check)
        transaction-records (for [[obligation-root records] (:payouts state)
                                  record records
                                  :when (= (str/lower-case tx-hash)
                                           (str/lower-case (:external/reference record)))]
                              obligation-root)
        same-transaction? (every? #(= (str/lower-case tx-hash)
                                      (str/lower-case (:external/reference %)))
                                  paid-records)
        single-obligation-use? (every? #(= (:obligation/root consent) %)
                                       transaction-records)]
    (when-not same-transaction?
      (throw (ex-info "A paid history record refers to a different transaction"
                      {:reason :payout-history-transaction-mismatch
                       :obligation/root (:obligation/root consent)})))
    (when-not single-obligation-use?
      (throw (ex-info "Transaction is already referenced by another reward obligation"
                      {:reason :transaction-reused-across-obligations
                       :obligation/root (:obligation/root consent)})))
    {:valid? true
     :verification/kind :recomputed-rpc-transfer-observation
     :researcher/root (:researcher/root consent)
     :obligation/root (:obligation/root consent)
     :authorization/root (:authorization/root consent)
     :payout/policy-root (:payout/policy-root consent)
     :funding/backing-root (:funding/backing-root consent)
     :funding/source-state (:funding-source-state chain-check)
     :challenge/root (:challenge/root authorization)
     :submission/root (:submission/root authorization)
     :review/action-root (:review/action-root authorization)
     :reward/amount (:reward/amount consent)
     :reward/currency (:reward/currency consent)
     :chain/id (:chain-id chain-check)
     :chain/instance-root (:chain-instance-root chain-check)
     :chain/genesis-hash (:genesis-hash chain-check)
     :asset/id (:asset/id consent)
     :asset/amount (:asset/amount consent)
     :asset/code-sha256 (:token-code-sha256 chain-check)
     :asset/decimals (:asset/decimals consent)
     :beneficiary/address (:beneficiary/address consent)
     :sender/address (:sender chain-check)
     :transaction/hash tx-hash
     :block/number (:block-number chain-check)
     :block/hash (:block-hash chain-check)
     :log/index (:log-index chain-check)
     :history/payout-recorded? (boolean (seq paid-records))
     :verification/finality (:finality chain-check)
     :verification/trust-class (:trust-class chain-check)
     :verification/policy-demo-only? (:policy/demo-only? consent)
     :verification/rpc-head (:rpc-head chain-check)
     :verification/ancestry-block-count (:ancestry-block-count chain-check)}))
