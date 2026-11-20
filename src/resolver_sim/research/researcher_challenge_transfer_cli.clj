(ns resolver-sim.research.researcher-challenge-transfer-cli
  "Recompute an ERC-20 transfer report for an existing reward obligation."
  (:require [clojure.edn :as edn]
            [resolver-sim.economics.with-bounty.rpc-observation :as rpc-observation]
            [resolver-sim.research.researcher-challenge-transfer :as transfer]))

(defn- read-edn-file [path]
  (let [value (edn/read-string (slurp path))]
    (when-not (map? value)
      (throw (ex-info "Expected one EDN map" {:path path})))
    value))

(defn -main [& args]
  (let [[operation config-path history-path authorization-path payout-policy-path
         operator-public-path rpc-context-path transfer-path rpc-url] args]
    (when-not (and (= "verify" operation)
                   (= 9 (count args))
                   (every? some? [config-path history-path authorization-path
                                  payout-policy-path operator-public-path rpc-context-path
                                  transfer-path rpc-url]))
      (throw (ex-info "Usage: verify CONFIG HISTORY AUTHORIZATION PAYOUT_POLICY OPERATOR_PUBLIC RPC_CONTEXT TRANSFER RPC_URL"
                      {:usage :invalid-arguments})))
    (let [config (read-edn-file config-path)]
      (prn (transfer/verify-transfer
            {:config config
             :history (edn/read-string (slurp history-path))
             :authorization (read-edn-file authorization-path)
             :payout-policy (read-edn-file payout-policy-path)
             :trusted-key-content {:operator (slurp operator-public-path)
                                   :reviewer (:reviewer/public-key config)}
             :rpc-context (read-edn-file rpc-context-path)
             :transfer (read-edn-file transfer-path)
             :rpc (rpc-observation/http-rpc rpc-url)})))))
