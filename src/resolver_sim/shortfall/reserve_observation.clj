(ns resolver-sim.shortfall.reserve-observation
  "Canonical observation of an immutable OnchainShortfallReserveV1 snapshot."
  (:require [clojure.set :as set]
            [resolver-sim.genesis :as genesis]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.shortfall.evm-asset :as asset])
  (:import [org.bouncycastle.jcajce.provider.digest Keccak$Digest256]))

(def ^:const schema-version "prf/onchain-shortfall-reserve-observation.v1")
(def ^:const source :onchain-shortfall-reserve-observation.v1)
(def ^:private domain "prf.onchain-shortfall-reserve-observation.v1")
(def ^:private snapshot-domain "prf.onchain-shortfall-reserve.snapshot.v1")

(defn- uint! [k n]
  (when-not (and (integer? n) (<= 0 n) (<= n 115792089237316195423570985008687907853269984665640564039457584007913129639935))
    (throw (ex-info "invalid uint256" {:field k :value n}))) n)
(defn- bytes32! [k s]
  (when-not (and (string? s) (re-matches #"0x[0-9a-f]{64}" s) (not= s (str "0x" (apply str (repeat 64 "0")))))
    (throw (ex-info "invalid bytes32" {:field k :value s}))) s)
(defn- word-hex [n] (format "%064x" n))
(defn- address-word [a]
  (when-not (genesis/valid-evm-address? a) (throw (ex-info "invalid address" {:address a})))
  (str (apply str (repeat 24 "0")) (subs a 2)))
(defn- keccak-bytes [bytes]
  (let [d (Keccak$Digest256.)]
    (.update d ^bytes bytes 0 (alength ^bytes bytes))
    (.digest d)))
(defn- keccak-hex [hex]
  (str "0x" (hc/bytes->hex (keccak-bytes (hc/hex->bytes hex)))))
(defn- keccak-utf8 [s]
  (str "0x" (hc/bytes->hex (keccak-bytes (hc/utf8-bytes s)))))
(defn solidity-snapshot-hash [{:keys [chain-id reserve token-address snapshot-id operation-id allocator created-at-block gross-amount reserved-amount-before protected-amount available-amount]}]
  (let [domain-h (keccak-utf8 snapshot-domain)
        words [domain-h (str "0x" (word-hex chain-id)) (str "0x" (address-word reserve))
               (str "0x" (address-word token-address)) (str "0x" (word-hex snapshot-id)) operation-id
               (str "0x" (address-word allocator)) (str "0x" (word-hex created-at-block))
               (str "0x" (word-hex gross-amount)) (str "0x" (word-hex reserved-amount-before))
               (str "0x" (word-hex protected-amount)) (str "0x" (word-hex available-amount))]]
    (keccak-hex (str "0x" (apply str (map #(subs % 2) words))))))

(defn build-observation [input]
  (let [allowed #{:chain-id :reserve :token-address :snapshot-id :snapshot-hash :operation-id :allocator :created-at-block :gross-amount :reserved-amount-before :protected-amount :available-amount}
        extra (set/difference (set (keys input)) allowed)]
    (when (seq extra) (throw (ex-info "unknown reserve observation field" {:extra extra})))
    (doseq [k [:chain-id :snapshot-id :created-at-block :gross-amount :reserved-amount-before :protected-amount :available-amount]] (uint! k (get input k)))
    (doseq [k [:operation-id :snapshot-hash]] (bytes32! k (get input k)))
    (let [{:keys [gross-amount reserved-amount-before protected-amount available-amount]} input]
      (when (> (+ reserved-amount-before protected-amount) gross-amount) (throw (ex-info "reserve accounting inconsistent" {})))
      (when (not= available-amount (- gross-amount reserved-amount-before protected-amount)) (throw (ex-info "available amount mismatch" {})))
      (when-not (pos? available-amount) (throw (ex-info "V1 observation requires positive availability" {}))))
    (let [hash (solidity-snapshot-hash input)]
      (when-not (= hash (:snapshot-hash input)) (throw (ex-info "Solidity snapshot hash mismatch" {:expected hash :actual (:snapshot-hash input)})))
      (let [asset (asset/build-asset {:chain-id (:chain-id input) :token-address (:token-address input) :unit :erc20-base-unit})
            observation (assoc input :schema-version schema-version :source source :asset/root (:asset/root asset))]
        (assoc observation :observation/root (str "sha256:" (hc/domain-hash domain observation)))))))
