(ns resolver-sim.generators.yield.core
  "test.check generators for yield provider scenarios (market layer only)."
  (:require [clojure.test.check.generators :as gen]
            [resolver-sim.yield.presets :as presets]
            [resolver-sim.yield.registry :as yreg]))

(def known-profile-ids
  "Registry profile ids resolvable via `yield.registry/resolve-yield-profile`."
  [:aave-v3 :fixed-rate :none :adversarial :yield.profile/aave-v3-like])

(def known-preset-ids
  (vec (keys presets/presets)))

(def known-failure-modes
  "Subset used for generative stress (see `yield.evidence/default-supported-failure-modes`)."
  [:partial-liquidity :withdraw-fails :negative-yield :deposit-fails
   :provider-paused :emergency-unwind-fails
   :oracle-stale :withdrawal-queue])

(def known-tokens
  ["USDC" "USDT" "DAI"])

(def gen-yield-profile
  (gen/elements known-profile-ids))

(def gen-yield-preset
  (gen/elements known-preset-ids))

(def gen-yield-failure
  (gen/elements known-failure-modes))

(def gen-yield-failure-set
  (gen/set gen-yield-failure))

(def gen-yield-token
  (gen/elements known-tokens))

(def gen-apy-bps
  (gen/large-integer* {:min -500 :max 2000}))

(def gen-liquidity-mode
  (gen/elements [:available :shortfall :haircut]))

(def gen-shortfall-ratio-bps
  (gen/large-integer* {:min 1000 :max 9000}))

(defn- apy-from-bps [bps]
  (/ (double bps) 10000.0))

(defn gen-yield-token-config
  "Token-level yield-config fragment (keyword keys, same shape as scenario JSON after load)."
  [token]
  (gen/fmap
   (fn [[apy-bps liq-mode failures ratio-bps]]
     (cond-> {:apy (apy-from-bps apy-bps)
              :liquidity-mode liq-mode}
       (seq failures)
       (assoc :failure-modes failures)

       (= liq-mode :shortfall)
       (assoc :shortfall {:available-ratio (/ (double ratio-bps) 10000.0)
                          :reason :liquidity-shortfall})))
   (gen/tuple gen-apy-bps gen-liquidity-mode gen-yield-failure-set gen-shortfall-ratio-bps)))

(defn yield-config-for-profile
  "Build a minimal {:modules ...} yield-config for a profile id and token."
  [profile-id token & {:keys [failure-modes liquidity-mode shortfall-ratio apy-bps]
                       :or {apy-bps 500
                            liquidity-mode :available
                            shortfall-ratio 0.5}}]
  (let [mid (name (or (:profile-id (yreg/resolve-yield-profile profile-id)) profile-id))
        tok (if (string? token) token (name token))
        token-cfg (cond-> {:apy (apy-from-bps apy-bps)
                           :liquidity-mode liquidity-mode}
                    (seq failure-modes)
                    (assoc :failure-modes (set failure-modes))
                    (= liquidity-mode :shortfall)
                    (assoc :shortfall {:available-ratio (double shortfall-ratio)
                                       :reason :liquidity-shortfall}))]
    {:modules {mid {:tokens {tok token-cfg}}}}))

(def gen-yield-config
  (gen/bind gen-yield-token
            (fn [token]
              (gen/bind (gen/tuple gen-yield-profile (gen-yield-token-config token))
                        (fn [[profile cfg]]
                          (let [mid (name (or (:module-id (yreg/resolve-yield-profile profile))
                                              profile))]
                            (gen/return {:modules {mid {:tokens {token cfg}}}})))))))

(def gen-yield-config-from-preset
  (gen/elements
   (for [preset-id known-preset-ids
         :let [cfg (presets/preset->yield-config preset-id)]
         :when cfg]
     cfg)))
