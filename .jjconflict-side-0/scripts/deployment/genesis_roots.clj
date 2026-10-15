(ns scripts.deployment.genesis-roots
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.genesis :as genesis]))

(def ^:private protocol-component-roots
  {:canonicalisation :canonicalisation/root
   :semantics :semantics/root
   :constitution :governance/constitution-root
   :evolution :governance/evolution-policy-root
   :configuration :configuration/contract-root
   :evidence :evidence/contract-root
   :verification :verification/contract-root
   :cross-domain :cross-domain/authority-policy-root})

(def ^:private initial-configuration-components
  {:module-registry :module-registry/root
   :evidence-policy :evidence-policy/root
   :escrow-template-registry :escrow-template-registry/root
   :parameter-policy :parameter-policy/root
   :governance-policy :governance-policy/root
   :interoperability-policy :interoperability-policy/root})

(defn- derive-initial-configuration-package-root [package]
  (let [materials (:component-material package)
        expected-components (set (keys initial-configuration-components))
        protocol-root (:protocol/genesis-root package)
        chain-id (:execution/chain-id package)]
    (when-not (= "eae-controlled-entitlement-demo.initial-configuration-package.v1"
                 (:package/schema package))
      (throw (ex-info "initial-configuration package schema is invalid"
                      {:kind :initial-configuration :schema (:package/schema package)})))
    (when-not (= :candidate-owner-approval-required (:package/status package))
      (throw (ex-info "initial-configuration package must remain an explicit candidate"
                      {:kind :initial-configuration :status (:package/status package)})))
    (when-not (and (map? materials) (= expected-components (set (keys materials))))
      (throw (ex-info "initial-configuration component set is incomplete or unknown"
                      {:kind :initial-configuration :expected expected-components
                       :actual (set (keys materials))})))
    (let [component-roots (into {}
                                (map (fn [[component config-field]]
                                       [config-field
                                        (str "sha256:" (canonical/hash-with-intent
                                                        {:hash/intent :evidence-content}
                                                        (get materials component)))]))
                                initial-configuration-components)
          configuration (merge {:configuration/schema "chain-configuration.v1"
                                :verifier-registry/root
                                (genesis/canonical-verifier-registry-root [])}
                               component-roots)
          computed (genesis/chain-configuration-root configuration)
          declared (:initial-configuration/root package)]
      (when-not (and (string? protocol-root) (re-matches #"^sha256:[0-9a-f]{64}$" protocol-root))
        (throw (ex-info "initial-configuration package protocol root is invalid"
                        {:kind :initial-configuration :protocol-root protocol-root})))
      (when-not (= 31337 chain-id)
        (throw (ex-info "candidate initial configuration is restricted to local chain 31337"
                        {:kind :initial-configuration :chain-id chain-id})))
      (when (and declared (not= declared computed))
        (throw (ex-info "initial-configuration declared root mismatch"
                        {:kind :initial-configuration :declared declared :computed computed})))
      computed)))

(defn- derive-authority-state-package-root [package]
  (when-not (= "eae-controlled-entitlement-demo.authority-state-package.v1"
               (:package/schema package))
    (throw (ex-info "authority-state package schema is invalid"
                    {:kind :authority-state :schema (:package/schema package)})))
  (when-not (= :candidate-owner-approval-required (:package/status package))
    (throw (ex-info "authority-state package must remain an explicit candidate"
                    {:kind :authority-state :status (:package/status package)})))
  (let [state (:authority-state package)
        _ (when-not (= 31337 (:execution/chain-id state))
            (throw (ex-info "candidate authority state is restricted to local chain 31337"
                            {:kind :authority-state
                             :chain-id (:execution/chain-id state)})))
        computed (genesis/eae-demo-authority-state-root state)
        declared (:authority-state/root package)]
    (when (and declared (not= declared computed))
      (throw (ex-info "authority-state declared root mismatch"
                      {:kind :authority-state :declared declared :computed computed})))
    computed))

(defn- verify-protocol-package-components! [package]
  (let [materials (:component-material package)
        protocol (:protocol-genesis package)
        expected-components (set (keys protocol-component-roots))]
    (when-not (and (map? materials) (= expected-components (set (keys materials))))
      (throw (ex-info "protocol-genesis package component set is incomplete or unknown"
                      {:kind :protocol-genesis :expected expected-components
                       :actual (set (keys materials))})))
    (doseq [[component protocol-field] protocol-component-roots]
      (let [computed (str "sha256:" (canonical/hash-with-intent
                                      {:hash/intent :evidence-content}
                                      (get materials component)))
            declared (get protocol protocol-field)]
        (when-not (= computed declared)
          (throw (ex-info "protocol-genesis component root does not match its material"
                          {:kind :protocol-genesis
                           :component component
                           :field protocol-field
                           :declared declared
                           :computed computed})))))))

(defn derive-root
  "Derive a PRF-owned canonical genesis/configuration root for deployment review.
   Results are derivations only; this function does not establish artifact approval."
  [kind artifact]
  (let [[schema root]
        (case kind
          :protocol-genesis
          (let [package? (contains? artifact :protocol-genesis)
                body (if package? (:protocol-genesis artifact) artifact)
                _status (when (and package?
                                   (not= :candidate-owner-approval-required (:package/status artifact)))
                          (throw (ex-info "protocol-genesis package must remain an explicit candidate"
                                          {:kind :protocol-genesis :status (:package/status artifact)})))
                _components (when package? (verify-protocol-package-components! artifact))
                computed (genesis/protocol-genesis-root body)
                declared (when package? (:protocol-genesis/root artifact))]
            (when (and declared (not= declared computed))
              (throw (ex-info "protocol-genesis declared root mismatch"
                              {:kind kind :declared declared :computed computed})))
            ["protocol-genesis.v1" computed])

          :chain-configuration
          ["chain-configuration.v1" (genesis/chain-configuration-root artifact)]

          :initial-configuration
          ["chain-configuration.v1" (derive-initial-configuration-package-root artifact)]

          :chain-instance-genesis
          ["chain-instance-genesis.v1" (genesis/chain-instance-genesis-root artifact)]

          :chain-configuration-transition
          ["chain-configuration-transition.v1" (genesis/chain-configuration-transition-root artifact)]

          :empty-verifier-registry
          ["verifier-registry.v1" (genesis/canonical-verifier-registry-root [])]

          :authority-state
          (let [computed (derive-authority-state-package-root artifact)]
            ["eae-controlled-entitlement-demo.authority-state.v1" computed])

          (throw (ex-info "unsupported deployment root kind" {:kind kind})))]
    {"schema" "prf-deployment-canonical-root-result.v1"
     "artifact" schema
     "root" root
     "qualification" "derived-only-not-approved"}))

(defn -main [& args]
  (let [[kind input-path & extra] args]
    (when (or (nil? kind) (seq extra)
              (and (not= kind "empty-verifier-registry") (nil? input-path))
              (and (= kind "empty-verifier-registry") input-path))
      (binding [*out* *err*]
        (println "Usage: clojure -M -m scripts.deployment.genesis-roots <protocol-genesis|authority-state|chain-configuration|chain-instance-genesis|chain-configuration-transition> <artifact.edn>"))
      (System/exit 2))
    (let [artifact (when input-path (edn/read-string (slurp (io/file input-path))))
          result (derive-root (keyword kind) artifact)]
      (println (json/write-str result)))))
