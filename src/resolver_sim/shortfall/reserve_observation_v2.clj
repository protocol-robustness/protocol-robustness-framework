(ns resolver-sim.shortfall.reserve-observation-v2
  "Canonical observation of the economic state exposed by OnchainShortfallReserveV2."
  (:require [clojure.set :as set]
            [resolver-sim.genesis :as genesis]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.shortfall.evm-asset :as asset])
  (:import [org.bouncycastle.jcajce.provider.digest Keccak$Digest256]))

(def ^:const schema-version "prf/onchain-shortfall-reserve-observation.v2")
(def ^:const source :onchain-shortfall-reserve-observation.v2)
(def ^:private domain "prf.onchain-shortfall-reserve-observation.v2")
(def ^:private economic-domain "prf.economic-reserve-state.v2")
(def ^:private observation-domain "prf.authoritative-reserve-observation.v2")

(def ^:private uint256-max
  115792089237316195423570985008687907853269984665640564039457584007913129639935N)
(defn- uint! [field n]
  (when-not (and (integer? n) (<= 0 n) (<= n uint256-max))
    (throw (ex-info "invalid uint256" {:field field :value n}))) n)
(defn- uint-width! [field n bits]
  (when-not (and (integer? n) (<= 0 n)
                 (< (bigint n) (bigint (.shiftLeft java.math.BigInteger/ONE (int bits)))))
    (throw (ex-info "integer exceeds Solidity field width"
                    {:field field :bits bits :value n}))) n)
(defn- bytes32! [field value]
  (when-not (and (string? value) (re-matches #"0x[0-9a-f]{64}" value))
    (throw (ex-info "invalid bytes32" {:field field :value value}))) value)
(defn- hex-bytes [hex] (hc/hex->bytes hex))
(defn- keccak [bytes]
  (let [digest (Keccak$Digest256.)]
    (.update digest ^bytes bytes 0 (alength ^bytes bytes))
    (str "0x" (hc/bytes->hex (.digest digest)))))
(defn- keccak-utf8 [s] (keccak (hc/utf8-bytes s)))
(defn- word [n] (format "%064x" n))
(defn- address-word [address]
  (when-not (genesis/valid-evm-address? address)
    (throw (ex-info "invalid EVM address" {:address address})))
  (str (apply str (repeat 24 "0")) (subs address 2)))
(defn- abi-words-hash [words]
  (keccak (hex-bytes (str "0x" (apply str (map #(subs % 2) words))))))

(defn economic-state-commitment
  "Recompute the exact Solidity abi.encode commitment used by reserve v2."
  [{:keys [prepared-operation-root reserve token-address gross-amount
           reserved-amount protected-amount available-amount availability-revision]}]
  (bytes32! :prepared-operation-root prepared-operation-root)
  (let [reserve-word (str "0x" (address-word reserve))
        token-word (str "0x" (address-word token-address))]
    (doseq [[field value bits] [[:gross-amount gross-amount 128]
                                [:reserved-amount reserved-amount 128]
                                [:protected-amount protected-amount 128]
                                [:available-amount available-amount 128]
                                [:availability-revision availability-revision 64]]]
      (uint-width! field value bits))
    (abi-words-hash [(keccak-utf8 economic-domain) prepared-operation-root
                     reserve-word token-word (str "0x" (word gross-amount))
                     (str "0x" (word reserved-amount))
                     (str "0x" (word protected-amount))
                     (str "0x" (word available-amount))
                     (str "0x" (word availability-revision))])))

(defn solidity-observation-root [commitment snapshot-id created-at-block]
  (bytes32! :reserve-state-commitment commitment)
  (doseq [[field value] [[:snapshot-id snapshot-id] [:created-at-block created-at-block]]]
    (uint! field value))
  (abi-words-hash [(keccak-utf8 observation-domain) commitment
                   (str "0x" (word snapshot-id)) (str "0x" (word created-at-block))]))

(defn build-observation [input]
  (let [allowed #{:protocol-genesis-root :chain-instance-genesis-root :chain-id
                  :configuration-root :configuration-epoch :operation-id
                  :prepared-operation-root :reserve :token-address :allocator
                  :gross-amount :reserved-amount :protected-amount
                  :available-amount :availability-revision :reserve-state-commitment
                  :snapshot-id :created-at-block :authoritative-observation-root}
        extra (set/difference (set (keys input)) allowed)]
    (when (seq extra) (throw (ex-info "unknown reserve v2 observation field" {:extra extra})))
    (doseq [field [:protocol-genesis-root :chain-instance-genesis-root
                   :configuration-root :operation-id :prepared-operation-root
                   :reserve-state-commitment :authoritative-observation-root]]
      (bytes32! field (get input field)))
    (doseq [field [:protocol-genesis-root :chain-instance-genesis-root
                   :configuration-root :operation-id :prepared-operation-root]]
      (when (= (get input field) (str "0x" (apply str (repeat 64 "0"))))
        (throw (ex-info "identity roots and operation IDs must be nonzero" {:field field}))))
    (doseq [[field bits] [[:chain-id 128] [:configuration-epoch 128]
                          [:gross-amount 128] [:reserved-amount 128]
                          [:protected-amount 128] [:available-amount 128]
                          [:availability-revision 64] [:snapshot-id 64]]]
      (uint-width! field (get input field) bits))
    (uint! :created-at-block (:created-at-block input))
    (doseq [field [:reserve :token-address :allocator]]
      (when-not (genesis/valid-evm-address? (get input field))
        (throw (ex-info "invalid EVM address" {:field field :value (get input field)}))))
    (when-not (= (:available-amount input)
                 (- (:gross-amount input) (:reserved-amount input) (:protected-amount input)))
      (throw (ex-info "reserve v2 accounting mismatch" {})))
    (when (or (> (+ (:reserved-amount input) (:protected-amount input)) (:gross-amount input))
              (not (pos? (:available-amount input))))
      (throw (ex-info "reserve v2 state has no positive consistent availability" {})))
    (let [commitment (economic-state-commitment input)
          observation-root (solidity-observation-root commitment (:snapshot-id input) (:created-at-block input))]
      (when-not (= commitment (:reserve-state-commitment input))
        (throw (ex-info "reserve v2 economic commitment mismatch"
                        {:expected commitment :actual (:reserve-state-commitment input)})))
      (when-not (= observation-root (:authoritative-observation-root input))
        (throw (ex-info "reserve v2 Solidity observation root mismatch"
                        {:expected observation-root :actual (:authoritative-observation-root input)})))
      (let [asset-artifact (asset/build-asset {:chain-id (:chain-id input)
                                               :token-address (:token-address input)
                                               :unit :erc20-base-unit})
            observation (assoc input :schema-version schema-version :source source
                               :asset/root (:asset/root asset-artifact))]
        (assoc observation :observation/root
               (str "sha256:" (hc/domain-hash domain observation)))))))
