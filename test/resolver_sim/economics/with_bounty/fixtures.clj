(ns resolver-sim.economics.with-bounty.fixtures
  "Stage A sealed fixture package for the with-bounty reference (ADR-0006 D7).

   Data-only manifest: entrypoints are symbols implemented in
   resolver-sim.economics.with-bounty.fixture. Lives under the test boundary
   and is never shipped as a built-in. Sealed roots are committed so a sealed
   frozen resolution can be demonstrated (dev and sealed modes)."
  (:require [buddy.core.codecs :as codecs]
            [resolver-sim.economics.bounty-payable :as payable]
            [resolver-sim.economics.with-bounty.funding-evidence :as funding]
            [resolver-sim.economics.with-bounty.payout-evidence :as payout]
            [resolver-sim.economics.with-bounty.obligation-settlement :as settlement]
            [resolver-sim.extensions.registry :as ext-reg])
  (:import [java.security KeyPairGenerator]
           [java.util Arrays]))

(def ^:private funding-authority-keypair
  (.generateKeyPair (KeyPairGenerator/getInstance "Ed25519")))
(def ^:private payout-authority-keypair
  (.generateKeyPair (KeyPairGenerator/getInstance "Ed25519")))
(def ^:private chain-observer-keypair
  (.generateKeyPair (KeyPairGenerator/getInstance "Ed25519")))

(defn- trusted-public-key [pair]
  (let [encoded (.getEncoded (.getPublic pair))]
    (codecs/bytes->hex (Arrays/copyOfRange encoded (- (alength encoded) 32)
                                           (alength encoded)))))

(def funding-trust-policy
  {:trusted-keys [{:key/id :test-bounty-funder
                   :key/public (trusted-public-key funding-authority-keypair)
                   :key/role :bounty-funding-authority
                   :key/status :active}]})

(def payout-trust-policy
  {:trusted-keys [{:key/id :test-bounty-payout
                   :key/public (trusted-public-key payout-authority-keypair)
                   :key/role :bounty-payout-authority
                   :key/status :active}]})

(def chain-observer-trust-policy
  {:trusted-keys [{:key/id :test-chain-observer
                   :key/public (trusted-public-key chain-observer-keypair)
                   :key/role :bounty-chain-observer
                   :key/status :active}]})

(defn chain-observation
  [overrides]
  (settlement/sign-chain-observation
   {:observation (merge
                  {:observation/chain-id 31337
                   :observation/asset :token/usdc
                   :observation/contract "0x000000000000000000000000000000000000b017"
                   :observation/chain-instance-root (str "sha256:" (apply str (repeat 64 "b")))
                   :observation/genesis-hash (str "0x" (apply str (repeat 64 "0")))
                   :observation/contract-code-sha256 (str "sha256:" (apply str (repeat 64 "a")))
                   :observation/source-id "test-rpc-observer"
                   :observation/transaction-hash "tx:fixture:payable-test"
                   :observation/block-number 10
                   :observation/block-hash "0xblock"
                   :observation/log-index 2
                   :observation/finality :controlled-execution
                   :observation/payable-id "payable-test"
                   :observation/operation-root "sha256:operation"
                   :observation/funding-source-root "sha256:source"
                   :observation/prf-funding-source-root "sha256:source"
                   :observation/beneficiary "researcher/alice"
                   :observation/amount 70
                   :observation/sequence 1
                   :observation/reserve-before 100
                   :observation/reserve-after 30
                   :observation/beneficiary-before 5
                   :observation/beneficiary-after 75
                   :observation/fee-amount 0
                   :observation/fee-payer :relayer}
                  overrides)
    :private-key (.getPrivate chain-observer-keypair)
    :key-id :test-chain-observer}))

(def chain-observer-policy-using-payout-key
  {:trusted-keys [{:key/id :test-bounty-payout
                   :key/public (get-in payout-trust-policy [:trusted-keys 0 :key/public])
                   :key/role :bounty-chain-observer
                   :key/status :active}]})

(defn payout-signed-chain-observation
  [overrides]
  (settlement/sign-chain-observation
   {:observation (merge
                  {:observation/chain-id 31337
                   :observation/asset :token/usdc
                   :observation/contract "0x000000000000000000000000000000000000b017"
                   :observation/chain-instance-root (str "sha256:" (apply str (repeat 64 "b")))
                   :observation/genesis-hash (str "0x" (apply str (repeat 64 "0")))
                   :observation/contract-code-sha256 (str "sha256:" (apply str (repeat 64 "a")))
                   :observation/source-id "test-rpc-observer"
                   :observation/transaction-hash "tx:fixture:payable-test"
                   :observation/block-number 10
                   :observation/block-hash "0xblock"
                   :observation/log-index 2
                   :observation/finality :controlled-execution
                   :observation/payable-id "payable-test"
                   :observation/operation-root "sha256:operation"
                   :observation/funding-source-root "sha256:source"
                   :observation/prf-funding-source-root "sha256:source"
                   :observation/beneficiary "researcher/alice"
                   :observation/amount 70
                   :observation/sequence 1
                   :observation/reserve-before 100
                   :observation/reserve-after 30
                   :observation/beneficiary-before 5
                   :observation/beneficiary-after 75
                   :observation/fee-amount 0
                   :observation/fee-payer :relayer}
                  overrides)
    :private-key (.getPrivate payout-authority-keypair)
    :key-id :test-bounty-payout}))

(defn funding-source
  [{:keys [operation-root beneficiary token available]
    :or {operation-root "sha256:op" beneficiary "researcher/alice"
         token :token/usdc available 1000}}]
  (funding/build-source
   {:source-id (str "test-reserve:" operation-root ":" beneficiary ":" token)
    :source-kind :declared-reserve
    :account :bounty-reserve :token token :available available
    :operation-root operation-root :beneficiary beneficiary
    :parameter-context-root "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    :parameter-address [:bounties :review-reserve]
    :private-key (.getPrivate funding-authority-keypair)
    :key-id :test-bounty-funder}))

(defn payout-receipt
  ([payable-artifact backing-artifact]
   (payout-receipt payable-artifact backing-artifact {}))
  ([payable-artifact backing-artifact overrides]
  (let [settled (assoc payable-artifact :payable/lifecycle :settled)
        settled (assoc settled :payable/hash (payable/payable-hash settled))]
    (payout/sign-payout
     {:payout-id (or (:payout-id overrides)
                     (str "payout:" (:payable/id payable-artifact)))
      :sequence (get overrides :sequence 1)
      :payable-id (get overrides :payable-id (:payable/id payable-artifact))
      :predecessor-root (get overrides :predecessor-root (:payable/hash payable-artifact))
      :successor-root (get overrides :successor-root (:payable/hash settled))
      :backing-root (get overrides :backing-root (:backing/hash backing-artifact))
      :beneficiary (get overrides :beneficiary (:payable/beneficiary payable-artifact))
      :token (get overrides :token (get-in backing-artifact [:backing/context :funding/token]))
      :amount (get overrides :amount (:payable/amount payable-artifact))
      :operation-root (get overrides :operation-root
                            (get-in backing-artifact [:backing/context :funding/operation-root]))
      :payment-reference (or (:payment-reference overrides)
                             (str "tx:fixture:" (:payable/id payable-artifact)))
      :private-key (.getPrivate payout-authority-keypair)
      :key-id :test-bounty-payout}))))


(def sealed-roots
  {:extension/source {:type :git
                      :repository "fixture"
                      :commit "stage-a"
                      :source-root "sha256:with-bounty-source"}
   :extension/artifact {:type :jar :artifact-root "sha256:with-bounty-artifact"}
   :extension/dependencies {:dependency-resolution-root "sha256:with-bounty-deps"}
   :extension/runtime {:prf/version "0.0.0-snapshot"
                       :clojure/version "1.12"
                       :jvm/profile :jvm-21}})

(def eligibility-cap
  {:capability/kind :economics/eligibility
   :capability/id :fixture/review-bounty-eligible
   :capability/version 1
   :capability/contract-version 1
   :entrypoint 'resolver-sim.economics.with-bounty.fixture/eligible?
   :input-schema :prf/eligibility-context.v1
   :output-schema :prf/eligibility-result.v1})

(def amount-cap
  {:capability/kind :economics/award-amount
   :capability/id :fixture/review-bounty-amount
   :capability/version 1
   :capability/contract-version 1
   :entrypoint 'resolver-sim.economics.with-bounty.fixture/calculate
   :input-schema :prf/with-bounty-amount-context.v1
   :output-schema :prf/calculation-result.v1})

(def review-bounties-pack
  "Sealed fixture package providing the eligibility and amount capabilities
   the with-bounty reference policy resolves."
  (merge {:extension/id :fixture/with-bounty-review
          :extension/version "0.1.0"
          :extension/api-version 1
          :extension/manifest-version 1
          :extension/capabilities [eligibility-cap amount-cap]
          :extension/license "MIT"
          :extension/maintainers ["prf-stage-a"]
          :extension/support-policy :unsupported
          :extension/funding-status :unfunded}
         sealed-roots))

(defn extension-map
  "Pure extension-map seeded with the Stage A fixture package."
  []
  (ext-reg/register-package (ext-reg/empty-extension-map) review-bounties-pack))
