(ns resolver-sim.research.researcher-challenge-payee
  "Researcher-signed payout-destination consent for a replay-derived reward.

   This binds an EVM recipient to the key that earned one exact obligation. It
   does not move funds or verify an on-chain transfer."
  (:require [clojure.string :as str]
            [resolver-sim.benchmark.signing :as signing]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as reference]
            [resolver-sim.network.researcher-challenges :as challenges]
            [resolver-sim.research.researcher-challenge-payout-policy :as payout-policy]))

(def schema "researcher-challenge-payout-destination.v3")

(def ^:private authorization-fields
  #{:artifact/schema :network/root :researcher/root :obligation/root
    :challenge/root :submission/root :review/action-root :payout/policy-root :chain/id
    :chain/instance-root :asset/id :asset/amount
    :beneficiary/address :reward/amount :reward/currency :authorization/nonce
    :artifact/root :authorization/signature})

(defn- ensure! [condition reason]
  (when-not condition (throw (ex-info (name reason) {:reason reason}))))

(defn- authorization-root [authorization]
  (reference/sha256-ref
   (canonical/domain-hash schema (dissoc authorization :artifact/root
                                         :authorization/signature))))

(defn- valid-address? [address]
  (and (string? address)
       (re-matches #"0x[0-9a-f]{40}" address)
       (not= "0x0000000000000000000000000000000000000000" address)))

(defn- valid-asset-id? [asset]
  (or (= :token/native asset)
      (and (string? asset)
           (re-matches #"0x[0-9a-f]{40}" asset)
           (not= "0x0000000000000000000000000000000000000000" asset))))

(defn- accepted-obligation [state obligation-root allow-existing-payouts?]
  (let [matches (filter #(= obligation-root
                            (get-in % [:obligation :researcher-reward-obligation/root]))
                        (vals (:reviews state)))]
    (ensure! (= 1 (count matches)) :unknown-or-ambiguous-reward-obligation)
    (let [review (first matches)
          researcher (:researcher/root review)
          enrollment (get-in state [:researchers researcher])]
      (ensure! (and (= :accepted (:decision review))
                    (:incentive/eligible? review)
                    (get-in review [:obligation :researcher-reward-obligation/root])
                    enrollment
                    (not (contains? (:revocations state) researcher))
                    (not (contains? (:withdrawals state) (:submission/root review)))
                    (or allow-existing-payouts?
                        (empty? (get-in state [:payouts obligation-root] []))))
               :reward-not-payable)
      {:review review
       :obligation (:obligation review)
       :researcher/root researcher
       :public-key (:public-key enrollment)})))

(defn build-authorization
  "Build unsigned consent for an accepted obligation under a dual-signed
   payout policy. Only the beneficiary and nonce are researcher-selected."
  [config history policy trusted-key-content request]
  (let [obligation-root (:obligation-root request)
        beneficiary-address (when (string? (:beneficiary-address request))
                              (str/lower-case (:beneficiary-address request)))
        nonce (:nonce request)
        policy-context (payout-policy/verify-policy config policy trusted-key-content)
        _ (ensure! (= #{:obligation-root :beneficiary-address :nonce}
                      (set (keys request)))
                   :invalid-payee-authorization-request)
        state (challenges/replay config history)
        {:keys [review obligation researcher/root]}
        (accepted-obligation state obligation-root false)
        asset-amount (payout-policy/asset-amount policy-context (:reward/amount obligation))
        network-root (:network/root config)
        _ (ensure! (and (reference/valid-sha256-ref? obligation-root)
                        (= (:challenge/root review) (:challenge/root policy-context))
                        (integer? asset-amount) (pos? asset-amount)
                        (valid-address? beneficiary-address)
                        (string? nonce) (not (str/blank? nonce))
                        (integer? (:reward/amount obligation))
                        (pos? (:reward/amount obligation))
                        (string? (:reward/currency obligation)))
                   :invalid-payee-authorization)
        body {:artifact/schema schema
              :network/root network-root
              :researcher/root root
              :obligation/root obligation-root
              :challenge/root (:challenge/root review)
              :submission/root (:submission/root review)
              :review/action-root (:review/action-root review)
              :payout/policy-root (:policy/root policy-context)
              :chain/id (:chain/id policy-context)
              :chain/instance-root (:chain/instance-root policy-context)
              :asset/id (:asset/id policy-context)
              :asset/amount asset-amount
              :beneficiary/address beneficiary-address
              :reward/amount (:reward/amount obligation)
              :reward/currency (:reward/currency obligation)
              :authorization/nonce nonce}]
    (assoc body :artifact/root (authorization-root body))))

(defn sign-authorization
  "Sign a built consent artifact with the enrolled researcher's private key."
  [authorization private-key-path]
  (ensure! (and (map? authorization)
                (= schema (:artifact/schema authorization))
                (= (:artifact/root authorization) (authorization-root authorization)))
           :invalid-payee-authorization)
  (assoc authorization :authorization/signature
         (signing/sign-hash (:artifact/root authorization) private-key-path nil)))

(defn verify-authorization
  "Verify consent against replayed history and dual-signed payout policy."
  [config history authorization policy trusted-key-content]
  (ensure! (= authorization-fields (set (keys authorization)))
           :invalid-payee-authorization)
  (let [policy-context (payout-policy/verify-policy config policy trusted-key-content)]
    (ensure! (and (= schema (:artifact/schema authorization))
                  (reference/valid-sha256-ref? (:network/root authorization))
                  (= (:network/root config) (:network/root authorization))
                  (reference/valid-sha256-ref? (:researcher/root authorization))
                  (reference/valid-sha256-ref? (:obligation/root authorization))
                  (reference/valid-sha256-ref? (:challenge/root authorization))
                  (reference/valid-sha256-ref? (:submission/root authorization))
                  (reference/valid-sha256-ref? (:review/action-root authorization))
                  (= (:policy/root policy-context) (:payout/policy-root authorization))
                  (= (:challenge/root policy-context) (:challenge/root authorization))
                  (= (:reward/currency policy-context) (:reward/currency authorization))
                  (= (:artifact/root authorization) (authorization-root authorization))
                  (integer? (:asset/amount authorization))
                  (pos? (:asset/amount authorization))
                  (integer? (:reward/amount authorization))
                  (pos? (:reward/amount authorization))
                  (string? (:authorization/nonce authorization))
                  (not (str/blank? (:authorization/nonce authorization)))
                  (re-matches #"[0-9a-f]{128}" (:authorization/signature authorization))
                  (valid-address? (:beneficiary/address authorization))
                  (valid-asset-id? (:asset/id authorization))
                  (= (:chain/id policy-context) (:chain/id authorization))
                  (= (:chain/instance-root policy-context)
                     (:chain/instance-root authorization))
                  (= (:asset/id policy-context) (:asset/id authorization)))
             :payee-authorization-context-mismatch)
    (let [state (challenges/replay config history)
          {:keys [review obligation researcher/root public-key]}
        ;; Consent is signed before payout. It must remain independently
        ;; verifiable after a payout status/settlement record is appended.
          (accepted-obligation state (:obligation/root authorization) true)]
      (ensure! (and (= root (:researcher/root authorization))
                    (= (:challenge/root review) (:challenge/root authorization))
                    (= (:submission/root review) (:submission/root authorization))
                    (= (:review/action-root review) (:review/action-root authorization))
                    (= (:reward/amount obligation) (:reward/amount authorization))
                    (= (:reward/currency obligation) (:reward/currency authorization))
                    (= (:asset/amount authorization)
                       (payout-policy/asset-amount policy-context (:reward/amount obligation)))
                    (= root (challenges/researcher-reference (:network/root config) public-key))
                    (signing/verify-signature-with-public-key-content
                     (:artifact/root authorization) (:authorization/signature authorization) public-key))
               :payee-authorization-signature-or-obligation-mismatch)
      {:valid? true
       :authorization/root (:artifact/root authorization)
       :researcher/root root
       :obligation/root (:obligation/root authorization)
       :beneficiary/address (:beneficiary/address authorization)
       :chain/id (:chain/id authorization)
       :chain/instance-root (:chain/instance-root authorization)
       :asset/id (:asset/id authorization)
       :asset/amount (:asset/amount authorization)
       :reward/amount (:reward/amount authorization)
       :reward/currency (:reward/currency authorization)
       :payout/policy-root (:payout/policy-root authorization)
       :funding/backing-root (:funding/backing-root policy-context)
       :funding/source-root (:funding/source-root policy-context)
       :asset/code-sha256 (:asset/code-sha256 policy-context)
       :payout/executor-code-sha256 (:payout/executor-code-sha256 policy-context)
       :asset/decimals (:asset/decimals policy-context)
       :payout/sender (:payout/sender policy-context)
       :payout/required-finality (:payout/required-finality policy-context)
       :chain/genesis-hash (:chain/genesis-hash policy-context)
       :policy/demo-only? (:policy/demo-only? policy-context)})))
