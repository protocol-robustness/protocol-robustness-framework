(ns resolver-sim.shortfall.reserve-binding
  "Source-specific binding between a validated reserve observation and Shortfall V2."
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.shortfall.context-v2 :as context-v2]
            [resolver-sim.shortfall.evm-asset :as asset]
            [resolver-sim.shortfall.reserve-observation :as observation]))

(def ^:const schema-version "prf/onchain-shortfall-reserve-binding.v1")
(def ^:private domain "prf.onchain-shortfall-reserve-binding.v1")

(defn build-binding [{:keys [reserve-observation shortfall-context operation-id] :as input}]
  (when-not (= #{:reserve-observation :shortfall-context :operation-id} (set (keys input)))
    (throw (ex-info "binding requires exactly two child artifacts and operation ID" {})))
  (let [obs (observation/build-observation reserve-observation)
        ctx (context-v2/build-context shortfall-context)]
    (when-not (= (:availability/source ctx) observation/source)
      (throw (ex-info "Shortfall source does not identify reserve observation" {})))
    (when-not (= (:availability/root ctx) (:observation/root obs))
      (throw (ex-info "Shortfall availability root mismatch" {})))
    (when-not (= (:available-amount ctx) (:available-amount obs))
      (throw (ex-info "Shortfall amount mismatch" {})))
    (when-not (= operation-id (:operation-id obs))
      (throw (ex-info "operation identity mismatch" {})))
    (when-not (= (:asset/root ctx) (:asset/root (asset/build-asset {:chain-id (:chain-id obs)
                                                                    :token-address (:token-address obs)
                                                                    :unit :erc20-base-unit})))
      (throw (ex-info "Shortfall asset root mismatch" {})))
    (let [binding {:schema-version schema-version
                   :reserve-observation/root (:observation/root obs)
                   :shortfall/context-root (:shortfall/context-root ctx)
                   :operation-id operation-id}]
      (assoc binding :binding/root (str "sha256:" (hc/domain-hash domain binding))))))
