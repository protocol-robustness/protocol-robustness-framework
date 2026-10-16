(ns scripts.deployment.successor-configuration
  "Derive the bounded C0 -> C1 successor configuration and its canonical
  transition identity. This is derivation only; it does not authorize a caller
  or execute a governance transition."
  (:require [clojure.edn :as edn]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [resolver-sim.genesis :as genesis]
            [resolver-sim.hash.reference :as reference]))

(defn derive-successor-configuration
  [{:keys [initial-configuration protocol-genesis-root chain-instance-genesis
           expected-chain-instance-genesis-root successor-verifier-registry-root
           successor-proof-mode epoch]}]
  (when-not (:valid? (genesis/validate-chain-configuration initial-configuration))
    (throw (ex-info "C0 must be a valid closed chain-configuration.v1 artifact"
                    {:errors (:errors (genesis/validate-chain-configuration initial-configuration))})))
  (when-not (and (:valid? (genesis/validate-chain-instance-genesis chain-instance-genesis))
                 (= 31337 (:execution/chain-id chain-instance-genesis))
                 (= protocol-genesis-root (:protocol/genesis-root chain-instance-genesis))
                 (= epoch 1)
                 (contains? #{0 1} successor-proof-mode)
                 (reference/valid-sha256-ref? successor-verifier-registry-root))
    (throw (ex-info "successor derivation requires a valid local chain-instance, matching protocol root, registry root, proof mode and epoch 1" {})))
  (when-not (= (genesis/canonical-verifier-registry-root [])
               (:verifier-registry/root initial-configuration))
    (throw (ex-info "C0 must select the canonical empty verifier registry" {})))
  (let [c0-root (genesis/chain-configuration-root initial-configuration)
        chain-instance-genesis-root (genesis/chain-instance-genesis-root chain-instance-genesis)
        _ (when-not (= c0-root (:configuration/initial-root chain-instance-genesis))
            (throw (ex-info "chain-instance genesis does not bind the supplied C0 root" {})))
        _ (when (and expected-chain-instance-genesis-root
                     (not= expected-chain-instance-genesis-root chain-instance-genesis-root))
            (throw (ex-info "supplied chain-instance genesis root differs from independently derived root"
                            {:expected expected-chain-instance-genesis-root
                             :computed chain-instance-genesis-root})))
        successor (assoc initial-configuration :verifier-registry/root successor-verifier-registry-root)
        c1-root (genesis/chain-configuration-root successor)]
    (when (= c0-root c1-root)
      (throw (ex-info "successor configuration must differ from C0" {})))
    (let [changed-fields (->> (keys initial-configuration)
                              (filter #(not= (get initial-configuration %) (get successor %)))
                              sort vec)
          transition {:transition/schema "chain-configuration-transition.v1"
                      :protocol/genesis-root protocol-genesis-root
                      :target {:target/type :chain-instance
                               :target/root chain-instance-genesis-root}
                      :configuration/parent-root c0-root
                      :configuration/new-root c1-root
                      :verifier-registry/root successor-verifier-registry-root
                      :epoch epoch}
          decision-root (genesis/chain-configuration-transition-root transition)]
      (when-not (= [:verifier-registry/root] changed-fields)
        (throw (ex-info "C1 may change only verifier-registry/root" {:changed-fields changed-fields})))
      {:schema "prf-successor-configuration-derivation.v1"
       :qualification "derived-only-not-approved"
       :protocol-genesis-root protocol-genesis-root
       :chain-instance-genesis chain-instance-genesis
       :chain-instance-genesis-root chain-instance-genesis-root
       :configuration/C0-root c0-root
       :configuration/C0 initial-configuration
       :configuration/C1-root c1-root
       :configuration/C1 successor
       :changed-fields changed-fields
       :successor-verifier-registry-root successor-verifier-registry-root
       :successor-proof-mode successor-proof-mode
       :configuration-transition transition
       :decision-root decision-root})))

(defn verify-successor-configuration
  "Independently recompute a retained C0/C1 derivation record from its exact
  inputs and require byte-for-byte EDN map equality."
  [record]
  (try
    (let [expected (derive-successor-configuration
                    {:initial-configuration (:configuration/C0 record)
                     :protocol-genesis-root (:protocol-genesis-root record)
                     :chain-instance-genesis (:chain-instance-genesis record)
                     :expected-chain-instance-genesis-root (:chain-instance-genesis-root record)
                     :successor-verifier-registry-root (:successor-verifier-registry-root record)
                     :successor-proof-mode (:successor-proof-mode record)
                     :epoch (get-in record [:configuration-transition :epoch])})]
      {:valid? (= expected record)
       :qualification (if (= expected record) :independently-recomputed :rejected)
       :errors (if (= expected record) [] ["successor derivation differs from independent recomputation"])
       :expected expected})
    (catch Exception error
      {:valid? false :qualification :rejected :errors [(.getMessage error)]})))

(defn- json-safe [value]
  (cond
    (keyword? value) (if-let [namespace (namespace value)] (str namespace "/" (name value)) (name value))
    (map? value) (into {} (map (fn [[key nested]] [(if (keyword? key) (json-safe key) (str key)) (json-safe nested)]) value))
    (set? value) (mapv json-safe (sort-by pr-str value))
    (sequential? value) (mapv json-safe value)
    :else value))

(defn -main [& args]
  (let [[mode input-path & extra] (if (= 1 (count args)) (cons "derive" args) args)]
  (when (or (nil? mode) (nil? input-path) (seq extra) (not (contains? #{"derive" "derive-json"} mode)))
    (binding [*out* *err*]
      (println "Usage: clojure -M:test -m scripts.deployment.successor-configuration <derive|derive-json> <derivation-input.edn|request.json>"))
    (System/exit 2))
  (let [input (if (= mode "derive-json")
                (json/read-str (slurp (io/file input-path)) :key-fn keyword)
                (edn/read-string (slurp (io/file input-path))))
        result (derive-successor-configuration input)]
    (if (= mode "derive-json")
      (println (json/write-str (json-safe result) :escape-slash false))
      (prn result)))))
