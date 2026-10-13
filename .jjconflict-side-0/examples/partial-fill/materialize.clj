(require '[clojure.data.json :as json]
         '[clojure.walk :as walk]
         '[clojure.string :as str]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[resolver-sim.genesis :as genesis]
         '[resolver-sim.hash.canonical :as canonical]
         '[resolver-sim.allocation.source-bound-entitlement :as entitlement]
         '[resolver-sim.allocation.persisted-statement-admission :as persisted])

(defn read-json [path]
  (walk/postwalk #(if (and (string? %) (re-matches #"0x[0-9a-fA-F]+" %))
                   (str/lower-case %) %)
                 (json/read-str (slurp path) :key-fn keyword)))
(defn write-json [directory name value]
  (let [file (io/file directory name)]
    (io/make-parents file)
    (spit file (str (json/write-str value :escape-slash false) "\n"))))
(defn content-root [value] (str "sha256:" (canonical/domain-hash :evidence-content value)))
(defn word [root] (str "0x" (subs root 7)))
(defn sha-ref [hex] (str "sha256:" (subs hex 2)))
(defn code-ref [hex] (str "keccak256:" (subs hex 2)))

(let [[mode directory input-path prefix] *command-line-args*]
  (case mode
    "init"
    (let [base (:base-input (read-json "etc/conformance/vectors/source-bound-entitlement-v1.json"))
          policy {:policy/id "controlled-partial-fill.v1"
                  :allocation "pro-rata" :rounding "largest-remainder"
                  :tie-break "canonical claim order" :caps "requested amounts"
                  :unfilled "unmet request only; no deferred claim, haircut or future obligation"
                  :available 70 :requested {"claim-a" 50 "claim-b" 30 "claim-c" 20}}
          policy-root (content-root policy)
          input (-> base
                    (assoc-in [:realized-statement-input :fail-action-policy] {:mode "unmet-request"})
                    (assoc-in [:realized-statement-input :allocation-context :policy :policy-hash]
                              (word policy-root)))
          source (:realized-statement-input input)
          statement (:statement (persisted/reconstruct-input (json/write-str source)))
          derived (entitlement/derive input)
          bound (assoc-in input [:prepared-operation :policy-root]
                          (str "0x" (:realized-statement-root derived)))]
      (write-json directory "policy.json" policy)
      ;; Preserve keyword namespaces and scalar types for direct canonical hashing.
      (spit (io/file directory "policy.edn") (str (pr-str policy) "\n"))
      (write-json directory "source.json" source)
      (write-json directory "identity-input.json" bound)
      (write-json directory "source-roots.json"
                  {:sourceRoot (str "0x" (:realized-statement-root derived))
                   :policyRoot (word policy-root) :statement statement}))

    "configuration"
    (let [bootstrap (read-json input-path)
          source-roots (read-json (str directory "/source-roots.json"))
          original (edn/read-string (slurp "etc/deployments/eae-controlled-entitlement-demo/protocol-genesis-package.edn"))
          components (-> (:component-material original)
                         (assoc :semantics {:scope "local controlled requests, not continuing obligations"
                                            :source-root (:sourceRoot source-roots)
                                            :funded-liability 70 :unmet-requests 30})
                         (assoc :verification {:program "source-bound-entitlement-controlled-sp1-program.v1"
                                               :vkey (:vkey bootstrap) :elf (:elf bootstrap)
                                               :proof-system "SP1 Groth16; 288 public bytes"})
                         (assoc :constitution {:scope "local test governor; no production governance claim"
                                               :governor (:governor bootstrap)}))
          keys-by-component {:canonicalisation :canonicalisation/root :semantics :semantics/root
                             :constitution :governance/constitution-root :evolution :governance/evolution-policy-root
                             :configuration :configuration/contract-root :evidence :evidence/contract-root
                             :verification :verification/contract-root :cross-domain :cross-domain/authority-policy-root}
          protocol (reduce-kv (fn [m k v] (assoc m (keys-by-component k) (content-root v)))
                              {:genesis/schema "protocol-genesis.v1" :protocol/id :protocol/eae-partial-fill-local}
                              components)
          protocol-root (genesis/protocol-genesis-root protocol)
          authority {:scope "controlled local test" :governor (:governor bootstrap) :chain 31337}
          authority-root (content-root authority)
          material {:module-registry {:source-adapter (:sourceAdapter bootstrap) :program (:vkey bootstrap)
                                      :elf (:elf bootstrap) :token (:token bootstrap)}
                    :evidence-policy {:source "initiator committed canonical requests" :proof "SP1 Groth16"
                                      :withdrawal "Required caller minimum; table opening and claim proof"}
                    :escrow-template-registry {:scope "no escrow templates in this demonstration"}
                    :parameter-policy (read-json (str directory "/policy.json"))
                    :governance-policy authority
                    :interoperability-policy {:scope "local chain only; no bridge"}}
          config (reduce-kv (fn [m k v] (assoc m (keyword (name k) "root") (content-root v)))
                            {:configuration/schema "chain-configuration.v1"
                             :verifier-registry/root (sha-ref (:registryRoot bootstrap))} material)
          config-root (genesis/chain-configuration-root config)]
      (spit (io/file directory "canonical-configuration.edn")
            (pr-str {:components components :protocol protocol :protocol-root protocol-root
                     :authority authority :authority-root authority-root :material material
                     :configuration config :configuration-root config-root}))
      (write-json directory "configuration.json"
                  {:protocolRoot (word protocol-root) :authorityRoot (word authority-root)
                   :configurationRoot (word config-root) :sourceRoot (:sourceRoot source-roots)}))

    "genesis"
    (let [context (read-json input-path)
          config (read-json (str directory "/configuration.json"))
          record {:genesis/schema "chain-instance-genesis.v1"
                  :protocol/genesis-root (sha-ref (:protocolRoot config))
                  :execution/chain-id 31337 :settlement/chain-id 31337
                  :control-plane {:address (:genesis context)
                                  :runtime-code-keccak256 (code-ref (:genesisCodeHash context))}
                  :governance {:authority-adapter (:authority context)
                               :authority-adapter-code-keccak256 (code-ref (:authorityCodeHash context))
                               :initial-authority-state-root (sha-ref (:authorityRoot config))}
                  :configuration/initial-root (sha-ref (:configurationRoot config))}
          root (genesis/chain-instance-genesis-root record)]
      (spit (io/file directory "canonical-genesis.edn") (pr-str {:record record :root root}))
      (write-json directory "genesis.json" {:chainRoot (word root)}))

    "witness"
    (let [context (read-json input-path)
          input (read-json (str directory "/identity-input.json"))
          binding {:scope "controlled local reserve"
                   :chain 31337 :reserve (get-in context [:prepared-operation :reserve])
                   :token (get-in context [:prepared-operation :token])
                   :operation (get-in context [:prepared-operation :operation-id])
                   :availability "live token balance minus protected capital and existing reservations"}
          economic-state (:economic-reserve-state context)
          economic-state (if (= "v3" (:commitment-version economic-state))
                           (update economic-state :policy-root #(subs % 2))
                           economic-state)
          witness (assoc input :prepared-operation (:prepared-operation context)
                               :economic-reserve-state economic-state
                               :reserve-binding-root (content-root binding))
          derived (entitlement/derive-controlled-source witness)]
      (write-json directory (str (or prefix "") "reserve-binding.json") binding)
      (write-json directory (str (or prefix "") "witness.json") witness)
      (write-json directory (str (or prefix "") "clojure-replay.json") (entitlement/fixture-projection derived)))
    (throw (ex-info "init|configuration|genesis|witness DIR [CONTEXT_JSON]" {}))))
