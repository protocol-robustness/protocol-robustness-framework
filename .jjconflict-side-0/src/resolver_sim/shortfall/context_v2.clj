(ns resolver-sim.shortfall.context-v2
  "Shortfall V2: the V1 context with an unambiguous canonical EVM asset root."
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.shortfall.context :as v1]))

(def ^:const schema-version "prf/shortfall-context.v2")

(defn- v1-fields [input]
  (dissoc input :asset/root :asset/id :asset/unit))

(defn context-preimage [context]
  (select-keys context [:schema-version :shortfall/profile :asset/root
                        :allocation-context-root :candidates
                        :availability/source :availability/root :available-amount
                        :allocation-policy :temporal-basis-root]))

(defn build-context [input]
  (let [allowed #{:shortfall/profile :asset/root :allocation-context-root
                  :candidates :availability/source :availability/root
                  :available-amount :allocation-policy :temporal-basis-root}
        extra (set/difference (set (keys input)) allowed)]
    (when (seq extra) (throw (ex-info "unknown Shortfall V2 field" {:extra extra})))
    (when-not (= (:shortfall/profile input) v1/profile)
      (throw (ex-info "unsupported Shortfall V2 profile" {})))
    (when-not (string? (:asset/root input))
      (throw (ex-info "Shortfall V2 requires an asset root" {})))
    ;; The child asset artifact is validated by the source-specific integration;
    ;; V2 commits only its root and deliberately does not duplicate its fields.
    (when-not (re-matches #"sha256:[0-9a-f]{64}" (:asset/root input))
      (throw (ex-info "invalid asset root" {})))
    (let [base (v1/build-context (merge (v1-fields input)
                                        {:asset/id "v2-asset-root"
                                         :asset/unit :erc20-base-unit}))
          context (-> (dissoc base :schema-version :asset/id :asset/unit
                              :shortfall/context-root)
                      (assoc :schema-version schema-version
                             :asset/root (:asset/root input)))]
      (assoc context :shortfall/context-root
             (str "sha256:" (hc/domain-hash "PRF_SHORTFALL_CONTEXT_V2"
                                            (context-preimage context)))))))
