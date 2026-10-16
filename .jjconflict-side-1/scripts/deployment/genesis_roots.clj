(ns scripts.deployment.genesis-roots
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [resolver-sim.authority-state :as authority-state]
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

(defn- json-key [key]
  (if (keyword? key)
    (if-let [namespace (namespace key)]
      (str namespace "/" (name key))
      (name key))
    key))

(defn- initial-configuration-artifact [materials]
  (merge {:configuration/schema "chain-configuration.v1"
          :verifier-registry/root (genesis/canonical-verifier-registry-root [])}
         (into {}
               (map (fn [[component field]]
                      [field (str "sha256:" (canonical/hash-with-intent
                                             {:hash/intent :evidence-content}
                                             (get materials component)))]))
               initial-configuration-components)))

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
    (let [configuration (initial-configuration-artifact materials)
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

          :generic-authority-state
          ["authority-state.v1" (authority-state/authority-state-root artifact)]

          :local-authority-policy
          [(:authority-policy/schema artifact) (authority-state/local-authority-policy-root artifact)]

          (throw (ex-info "unsupported deployment root kind" {:kind kind})))]
    {"schema" "prf-deployment-canonical-root-result.v1"
     "artifact" schema
     "root" root
     "qualification" "derived-only-not-approved"}))

(defn derive-candidate-package-set
  "Recompute all candidate roots and cross-package scope bindings together.
  Exposes the exact seven-root C0 artifact, not an arbitrary environment root.
  This record is review material and does not authenticate owner approval."
  [packages]
  (when-not (and (map? packages)
                 (= #{:protocol-genesis :authority-state :initial-configuration}
                    (set (keys packages))))
    (throw (ex-info "candidate package set must contain exactly three packages" {})))
  (let [roots (into {} (for [[kind artifact] packages]
                         [kind (get (derive-root kind artifact) "root")]))
        protocol-root (:protocol-genesis roots)
        authority (get-in packages [:authority-state :authority-state])
        initial (:initial-configuration packages)]
    (when-not (= protocol-root (:protocol/genesis-root authority)
                 (:protocol/genesis-root initial))
      (throw (ex-info "candidate packages disagree on protocol genesis" {})))
    (when-not (= 31337 (:execution/chain-id authority) (:execution/chain-id initial))
      (throw (ex-info "candidate packages disagree on local execution scope" {})))
    {"schema" "prf-deployment-candidate-package-set.v1"
     "qualification" "derived-only-not-approved"
     "roots" (into {} (map (fn [[k v]] [(name k) v])) roots)
     "initialConfiguration" (initial-configuration-artifact (:component-material initial))
     "artifactDigests" (into {} (for [[kind artifact] packages]
                                  [(name kind)
                                   (str "sha256:" (canonical/hash-with-intent
                                                   {:hash/intent :evidence-content}
                                                   artifact))]))}))

(defn derive-generic-candidate-package-set
  "Derive a candidate package set using generic authority-state.v1 and
  explicitly versioned local-authority-policy.v2 or v3. This is intentionally
  distinct from the legacy EAE demo path above; it never reinterprets the old
  package schema."
  [packages]
  (when-not (and (map? packages)
                 (= #{:protocol-genesis :authority-state :local-authority-policy :initial-configuration}
                    (set (keys packages))))
    (throw (ex-info "generic candidate package set must contain exactly four packages" {})))
  (let [protocol-package (:protocol-genesis packages)
        authority-package (:authority-state packages)
        policy-package (:local-authority-policy packages)
        initial-package (:initial-configuration packages)
        state (:authority-state authority-package)
        policy (:local-authority-policy policy-package)
        candidate-status :candidate-owner-approval-required]
    (when-not (= "eae-controlled-entitlement-demo.protocol-genesis-package.v1"
                 (:package/schema protocol-package))
      (throw (ex-info "protocol-genesis candidate package schema is invalid" {})))
    (when-not (= candidate-status (:package/status protocol-package))
      (throw (ex-info "protocol-genesis package must remain an explicit candidate" {})))
    (when-not (and (= "prf-authority-state-package.v1" (:package/schema authority-package))
                   (= candidate-status (:package/status authority-package))
                   (= #{:package/schema :package/status :authority-state :authority-state/root}
                      (set (keys authority-package))))
      (throw (ex-info "generic authority-state package schema/status is invalid" {})))
    (when-not (and (contains? #{"prf-local-authority-policy-package.v2"
                               "prf-local-authority-policy-package.v3"}
                             (:package/schema policy-package))
                   (= (if (= "local-authority-policy.v2" (:authority-policy/schema policy))
                        "prf-local-authority-policy-package.v2"
                        "prf-local-authority-policy-package.v3")
                      (:package/schema policy-package))
                   (= candidate-status (:package/status policy-package))
                   (= #{:package/schema :package/status :local-authority-policy :local-authority-policy/root}
                      (set (keys policy-package))))
      (throw (ex-info "generic local-authority-policy package schema/status is invalid" {})))
    (when-not (= "eae-controlled-entitlement-demo.initial-configuration-package.v1"
                 (:package/schema initial-package))
      (throw (ex-info "initial configuration candidate package schema is invalid" {})))
    (let [protocol-result (derive-root :protocol-genesis protocol-package)
          protocol-root (get protocol-result "root")
          state-root (authority-state/authority-state-root state)
          policy-root (authority-state/local-authority-policy-root policy)
          initial-root (derive-root :initial-configuration initial-package)
          initial-root (get initial-root "root")]
      (when-not (= "authority-state.v1" (:authority-state/schema state))
        (throw (ex-info "generic authority-state package must use authority-state.v1" {})))
      (when-not (contains? #{"local-authority-policy.v2" "local-authority-policy.v3"}
                           (:authority-policy/schema policy))
        (throw (ex-info "generic candidate package set requires local-authority-policy.v2 or v3" {})))
      (when-not (= state-root (:authority-state/root authority-package))
        (throw (ex-info "generic authority-state declared root mismatch"
                        {:declared (:authority-state/root authority-package) :computed state-root})))
      (when-not (= policy-root (:local-authority-policy/root policy-package))
        (throw (ex-info "generic local-authority-policy declared root mismatch"
                        {:declared (:local-authority-policy/root policy-package) :computed policy-root})))
      (when-not (:valid? (authority-state/validate-policy-binding state policy))
        (throw (ex-info "generic authority state does not bind its local authority policy"
                        {:errors (:errors (authority-state/validate-policy-binding state policy))})))
      (when-not (= protocol-root (:protocol/genesis-root state) (:protocol/genesis-root policy)
                   (:protocol/genesis-root initial-package))
        (throw (ex-info "generic candidate packages disagree on protocol genesis" {})))
      (when-not (= 31337 (:execution/chain-id state) (:execution/chain-id policy)
                   (:execution/chain-id initial-package))
        (throw (ex-info "generic candidate packages disagree on local execution scope" {})))
      (cond-> {"schema" "prf-deployment-generic-candidate-package-set.v1"
       "qualification" "derived-only-not-approved"
       "roots" {"protocol-genesis" protocol-root
                "authority-state" state-root
                "local-authority-policy" policy-root
                "initial-configuration" initial-root}
       "initialConfiguration" (initial-configuration-artifact (:component-material initial-package))
       "artifactDigests" (into {}
                               (for [[kind artifact] packages]
                                 [(name kind)
                                  (str "sha256:" (canonical/hash-with-intent
                                                  {:hash/intent :evidence-content}
                                                  artifact))]))}
        (= "local-authority-policy.v3" (:authority-policy/schema policy))
        (assoc "authorityTransitionValidatorAddress" (:authority/transition-validator-address policy))))))

(defn -main [& args]
  (let [[kind input-path & extra] args]
    (when (or (nil? kind) (seq extra)
              (and (not= kind "empty-verifier-registry") (nil? input-path))
              (and (= kind "empty-verifier-registry") input-path))
      (binding [*out* *err*]
        (println "Usage: clojure -M:test -m scripts.deployment.genesis-roots <protocol-genesis|authority-state|generic-authority-state|local-authority-policy|initial-configuration|candidate-package-set|generic-candidate-package-set|chain-configuration|chain-instance-genesis|chain-configuration-transition> <artifact.edn>; empty-verifier-registry takes no file"))
      (System/exit 2))
    (let [artifact (when input-path (edn/read-string (slurp (io/file input-path))))
          result (case kind
                   "candidate-package-set" (derive-candidate-package-set artifact)
                   "generic-candidate-package-set" (derive-generic-candidate-package-set artifact)
                   (derive-root (keyword kind) artifact))]
      (println (json/write-str result {:key-fn json-key})))))
