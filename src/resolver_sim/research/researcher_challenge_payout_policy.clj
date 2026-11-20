(ns resolver-sim.research.researcher-challenge-payout-policy
  "Dual-signed asset and chain policy for researcher reward payouts.

   This is policy authorization, not a settlement artifact. Its funding root
   must be independently verified against the existing backing system."
  (:require [clojure.string :as str]
            [resolver-sim.benchmark.signing :as signing]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as reference]
            [resolver-sim.network.researcher-challenges :as challenges]))

(def schema "researcher-challenge-payout-policy.v2")
(def ^:private roles #{:operator :reviewer})
(def ^:private policy-fields
  #{:artifact/schema :configuration/root :network/root :challenge/root
    :reward/currency :chain/id :chain/instance-root :chain/genesis-hash
    :asset/id :asset/decimals :asset/code-sha256
    :asset/amount-per-reward-unit :payout/sender
    :payout/executor-code-sha256 :payout/required-finality
    :funding/backing-root :funding/source-root :policy/demo-only?
    :artifact/root :policy/signatures})

(defn- ensure! [condition reason]
  (when-not condition (throw (ex-info (name reason) {:reason reason}))))

(defn- valid-address? [address]
  (and (string? address)
       (re-matches #"0x[0-9a-f]{40}" address)
       (not= "0x0000000000000000000000000000000000000000" address)))

(defn- policy-root [policy]
  (reference/sha256-ref
   (canonical/domain-hash schema
                          (dissoc policy :artifact/root :policy/signatures))))

(defn build-policy
  "Build an unsigned payout policy bound to one exact challenge configuration.

   The required backing root is only a commitment here; callers must verify
   that root using the existing PRO-80/81 backing evidence before signing."
  [config request]
  (challenges/replay config [])
  (ensure! (= #{:challenge/root :chain/id :chain/instance-root :chain/genesis-hash
                :asset/id :asset/decimals :asset/code-sha256
                :asset/amount-per-reward-unit :payout/sender
                :payout/executor-code-sha256 :payout/required-finality
                :funding/backing-root :funding/source-root :policy/demo-only?}
              (set (keys request)))
           :invalid-payout-policy)
  (let [challenge-root (:challenge/root request)
        challenge (some #(when (= challenge-root (:artifact/root %)) %)
                        (:challenges config))
        ratio (:asset/amount-per-reward-unit request)
        asset-id (when (string? (:asset/id request))
                   (str/lower-case (:asset/id request)))
        payout-sender (when (string? (:payout/sender request))
                        (str/lower-case (:payout/sender request)))
        funding-source-root (when (string? (:funding/source-root request))
                              (str/lower-case (:funding/source-root request)))
        body {:artifact/schema schema
              :configuration/root (:artifact/root config)
              :network/root (:network/root config)
              :challenge/root challenge-root
              :reward/currency (get-in config [:reward/policy :reward/currency])
              :chain/id (:chain/id request)
              :chain/instance-root (:chain/instance-root request)
              :chain/genesis-hash (some-> (:chain/genesis-hash request) str/lower-case)
              :asset/id asset-id
              :asset/decimals (:asset/decimals request)
              :asset/code-sha256 (:asset/code-sha256 request)
              :asset/amount-per-reward-unit ratio
              :payout/sender payout-sender
              :payout/executor-code-sha256 (:payout/executor-code-sha256 request)
              :payout/required-finality (:payout/required-finality request)
              :funding/backing-root (:funding/backing-root request)
              :funding/source-root funding-source-root
              :policy/demo-only? (:policy/demo-only? request)}]
    (ensure! (and challenge
                  (reference/valid-sha256-ref? (:configuration/root body))
                  (reference/valid-sha256-ref? (:network/root body))
                  (reference/valid-sha256-ref? challenge-root)
                  (string? (:reward/currency body))
                  (integer? (:chain/id body)) (pos? (:chain/id body))
                  (reference/valid-sha256-ref? (:chain/instance-root body))
                  (re-matches #"0x[0-9a-f]{64}" (:chain/genesis-hash body))
                  (valid-address? (:asset/id body))
                  (integer? (:asset/decimals body))
                  (<= 0 (:asset/decimals body) 36)
                  (reference/valid-sha256-ref? (:asset/code-sha256 body))
                  (= #{:numerator :denominator} (set (keys ratio)))
                  (integer? (:numerator ratio)) (pos? (:numerator ratio))
                  (integer? (:denominator ratio)) (pos? (:denominator ratio))
                  (valid-address? (:payout/sender body))
                  (reference/valid-sha256-ref? (:payout/executor-code-sha256 body))
                  (contains? #{:included :finalized}
                             (:payout/required-finality body))
                  (reference/valid-sha256-ref? (:funding/backing-root body))
                  (re-matches #"0x[0-9a-f]{64}" (:funding/source-root body))
                  ;; This schema and CLI are for the current development
                  ;; demonstration only. Production authority needs a distinct
                  ;; reviewed policy version and trust configuration.
                  (true? (:policy/demo-only? body)))
             :invalid-payout-policy)
    (assoc body :artifact/root (policy-root body)
           :policy/signatures {})))

(defn sign-policy
  "Attach one role signature to an unsigned policy."
  [policy role private-key-path]
  (ensure! (and (contains? roles role)
                (= schema (:artifact/schema policy))
                (= (:artifact/root policy) (policy-root policy))
                (not (contains? (:policy/signatures policy) role)))
           :invalid-payout-policy-signature-request)
  (assoc-in policy [:policy/signatures role]
            (signing/sign-hash (:artifact/root policy) private-key-path nil)))

(defn verify-policy
  "Verify exact demo-only policy contents, challenge/config binding, and
   distinct operator/reviewer signatures against externally pinned keys. The
   reviewer key must be the configuration key."
  [config policy trusted-key-content]
  (ensure! (= policy-fields (set (keys policy))) :invalid-payout-policy)
  (ensure! (= roles (set (keys (:policy/signatures policy))))
           :missing-payout-policy-signatures)
  (ensure! (= roles (set (keys trusted-key-content)))
           :missing-payout-policy-trust-keys)
  (let [operator-key (challenges/canonical-public-key (:operator trusted-key-content))
        reviewer-key (challenges/canonical-public-key (:reviewer trusted-key-content))
        config-reviewer (challenges/canonical-public-key (:reviewer/public-key config))
        unsigned (build-policy config (select-keys policy
                                                   [:challenge/root :chain/id
                                                    :chain/instance-root :chain/genesis-hash
                                                    :asset/id :asset/decimals
                                                    :asset/code-sha256
                                                    :asset/amount-per-reward-unit
                                                    :payout/sender
                                                    :payout/executor-code-sha256
                                                    :payout/required-finality
                                                    :funding/backing-root
                                                    :funding/source-root
                                                    :policy/demo-only?]))
        signatures (:policy/signatures policy)
        valid-signatures?
        (every? (fn [role]
                  (and (string? (get signatures role))
                       (re-matches #"[0-9a-f]{128}" (get signatures role))
                       (signing/verify-signature-with-public-key-content
                        (:artifact/root policy) (get signatures role)
                        (get {:operator operator-key :reviewer reviewer-key} role))))
                roles)
        challenge (some #(when (= (:challenge/root policy) (:artifact/root %)) %)
                        (:challenges config))]
    (ensure! (and (= (dissoc unsigned :policy/signatures)
                     (dissoc policy :policy/signatures))
                  challenge
                  (= config-reviewer reviewer-key)
                  (not= operator-key reviewer-key)
                  valid-signatures?)
             :payout-policy-verification-failed)
    {:valid? true
     :policy/root (:artifact/root policy)
     :configuration/root (:configuration/root policy)
     :network/root (:network/root policy)
     :challenge/root (:challenge/root policy)
     :reward/currency (:reward/currency policy)
     :chain/id (:chain/id policy)
     :chain/instance-root (:chain/instance-root policy)
     :chain/genesis-hash (:chain/genesis-hash policy)
     :asset/id (:asset/id policy)
     :asset/decimals (:asset/decimals policy)
     :asset/code-sha256 (:asset/code-sha256 policy)
     :asset/amount-per-reward-unit (:asset/amount-per-reward-unit policy)
     :payout/sender (:payout/sender policy)
     :payout/executor-code-sha256 (:payout/executor-code-sha256 policy)
     :payout/required-finality (:payout/required-finality policy)
     :funding/backing-root (:funding/backing-root policy)
     :funding/source-root (:funding/source-root policy)
     :policy/demo-only? (:policy/demo-only? policy)}))

(defn asset-amount
  "Convert an integer reward amount under a verified exact rational policy.
   Reject fractions rather than silently rounding token base units."
  [policy-context reward-amount]
  (let [{:keys [numerator denominator]}
        (:asset/amount-per-reward-unit policy-context)
        product (*' reward-amount numerator)]
    (ensure! (and (integer? reward-amount) (pos? reward-amount)
                  (integer? numerator) (pos? numerator)
                  (integer? denominator) (pos? denominator)
                  (zero? (mod product denominator)))
             :non-integral-payout-conversion)
    (quot product denominator)))
