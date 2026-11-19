(ns resolver-sim.research.researcher-challenge-payout-approval-cli
  "Prepare verified call arguments for the existing BountySettlement contract."
  (:require [clojure.edn :as edn]
            [resolver-sim.research.researcher-challenge-payout-approval :as approval]))

(defn- read-edn-file [path]
  (let [value (edn/read-string (slurp path))]
    (when-not (map? value)
      (throw (ex-info "Expected one EDN map" {:path path})))
    value))

(defn -main [& args]
  (let [[operation config-path history-path policy-path authorization-path
         operator-public-path] args]
    (when-not (and (contains? #{"prepare" "calldata"} operation)
                   (= 6 (count args))
                   (every? some? [config-path history-path policy-path
                                  authorization-path operator-public-path]))
      (throw (ex-info "Usage: prepare|calldata CONFIG HISTORY POLICY AUTHORIZATION OPERATOR_PUBLIC"
                      {:usage :invalid-arguments})))
    (let [config (read-edn-file config-path)
          result (approval/build-approval-arguments
                  config
                  (edn/read-string (slurp history-path))
                  (read-edn-file policy-path)
                  (read-edn-file authorization-path)
                  {:operator (slurp operator-public-path)
                   :reviewer (:reviewer/public-key config)})]
      (if (= "calldata" operation)
        (println (:calldata/hex result))
        (prn result)))))
