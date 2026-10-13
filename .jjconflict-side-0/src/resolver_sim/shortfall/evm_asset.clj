(ns resolver-sim.shortfall.evm-asset
  "Canonical computational identity for one ERC-20 contract on one EVM chain.
   This does not assert token authenticity or human naming."
  (:require [clojure.set :as set]
            [resolver-sim.genesis :as genesis]
            [resolver-sim.hash.canonical :as hc]))

(def ^:const schema-version "prf/evm-erc20-asset.v1")
(def ^:private domain "prf.evm-erc20-asset.v1")

(defn- uint! [field n]
  (when-not (and (integer? n) (<= 0 n) (<= n 18446744073709551615))
    (throw (ex-info "invalid EVM chain ID" {:field field :value n})))
  n)

(defn build-asset
  "Build the closed canonical EVM ERC-20 identity."
  [{:keys [chain-id token-address unit] :as input}]
  (let [allowed #{:chain-id :token-address :unit}]
    (when (seq (set/difference (set (keys input)) allowed))
      (throw (ex-info "unknown EVM asset field" {:input input})))
    (uint! :chain-id chain-id)
    (when-not (genesis/valid-evm-address? token-address)
      (throw (ex-info "token address must be a canonical non-zero EVM address"
                      {:token-address token-address})))
    (when-not (= unit :erc20-base-unit)
      (throw (ex-info "EVM asset must use ERC-20 base units" {:unit unit})))
    (let [asset {:schema-version schema-version
                 :chain-id chain-id
                 :token-address token-address
                 :unit unit}]
      (assoc asset :asset/root (str "sha256:" (hc/domain-hash domain asset))))))

(defn validate-asset [asset]
  (let [rebuilt (build-asset (select-keys asset [:chain-id :token-address :unit]))]
    (when-not (= schema-version (:schema-version asset))
      (throw (ex-info "wrong EVM asset schema" {})))
    (when-not (= (:asset/root rebuilt) (:asset/root asset))
      (throw (ex-info "EVM asset root mismatch" {:expected (:asset/root rebuilt)
                                                 :actual (:asset/root asset)})))
    rebuilt))
