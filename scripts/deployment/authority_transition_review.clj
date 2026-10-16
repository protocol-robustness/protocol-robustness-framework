(ns scripts.deployment.authority-transition-review
  "Independently verify a policy-v3 authority-state transition request for the
  bounded local C0 -> C1 deployment. This verifier does not submit transactions."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [resolver-sim.authority-state :as authority]
            [resolver-sim.genesis :as genesis]))

(def request-schema "prf-authority-transition-request.v1")

(defn- evm-bytes32 [value]
  (if (and (string? value) (re-matches #"^(sha256|keccak256):[0-9a-f]{64}$" value))
    (str "0x" (subs value (- (count value) 64)))
    value))

(defn- json-safe [value]
  (cond
    (keyword? value) (if-let [ns (namespace value)] (str ns "/" (name value)) (name value))
    (map? value) (into {} (map (fn [[key nested]] [(if (keyword? key) (json-safe key) (str key)) (json-safe nested)]) value))
    (set? value) (mapv json-safe (sort-by pr-str value))
    (sequential? value) (mapv json-safe value)
    (instance? java.util.UUID value) (str value)
    :else value))

(defn verify-request
  "Verify canonical state semantics, exact C1 preimage, deployment instance,
  and policy-v3 EIP-191 attestation using the externally trusted key policy."
  [request trusted-context]
  (try
    (let [{:keys [previous-state successor-state current-policy successor-policy
                  chain-instance envelope expected-configuration-transition
                  expected-decision-root expected-parent-verifier-registry-root]} request
          trust-policy (:authority-transition-trust-policy trusted-context)
          expected-scope (:expected-scope trusted-context)
          preimages (:authority-transition/decision-preimages envelope)
          semantic (authority/verify-authority-transition-attestation
                    envelope previous-state successor-state current-policy successor-policy
                    chain-instance trust-policy)
          instance-root (genesis/chain-instance-genesis-root chain-instance)
          previous-root (authority/authority-state-root previous-state)
          successor-root (authority/authority-state-root successor-state)
          policy-root (authority/local-authority-policy-root current-policy)
          preimage (when (= 1 (count preimages)) (first preimages))
          preimage-transition (:decision/transition preimage)
          transition-root (when preimage-transition
                            (genesis/chain-configuration-transition-root preimage-transition))
          scope {:chain-instance-genesis-root instance-root
                 :adapter-address (get-in chain-instance [:governance :authority-adapter])
                 :previous-root previous-root
                 :successor-root successor-root
                 :policy-root policy-root
                 :decision-root (:decision/root preimage)
                 :transition-root transition-root
                 :execution-commitment (:authority-transition/execution-commitment envelope)
                 :previous-revision (:authority/revision previous-state)
                 :successor-revision (:authority/revision successor-state)}
          expected-transition-root (when expected-configuration-transition
                                     (genesis/chain-configuration-transition-root expected-configuration-transition))
          errors (cond-> (vec (:errors semantic))
                   (not= request-schema (:request/schema request))
                   (conj "request schema is invalid")
                   (not= 1 (count preimages))
                   (conj "bounded C0 -> C1 authorization requires exactly one decision preimage")
                   (and expected-decision-root (not= expected-decision-root (:decision/root preimage)))
                   (conj "authority decision root differs from the derived C0 -> C1 decision")
                   (and expected-transition-root (not= expected-transition-root transition-root))
                   (conj "authority preimage differs from the independently derived C0 -> C1 transition")
                   (and expected-parent-verifier-registry-root
                        (not= expected-parent-verifier-registry-root
                              (:decision/parent-verifier-registry-root preimage)))
                   (conj "authority preimage parent registry differs from approved C0")
                   (and expected-scope (not= expected-scope scope))
                   (conj "authority transition scope differs from the independently supplied expected scope"))
          valid? (and (:valid? semantic) (empty? errors))
          mutations (:authority-transition/execution-mutations envelope)
          output-mutations (mapv (fn [mutation]
                                   {:kind (:kind mutation)
                                    :decisionRoot (evm-bytes32 (:decision-root mutation))
                                    :targetMode (or (:target-mode mutation) 0)
                                    :targetRoot (evm-bytes32 (or (:target-root mutation) (str "0x" (apply str (repeat 64 "0")))))
                                    :parentConfigurationRoot (evm-bytes32 (or (:parent-configuration-root mutation) (str "0x" (apply str (repeat 64 "0")))))
                                    :newConfigurationRoot (evm-bytes32 (or (:new-configuration-root mutation) (str "0x" (apply str (repeat 64 "0")))))
                                    :parentVerifierRegistryRoot (evm-bytes32 (or (:parent-verifier-registry-root mutation) (str "0x" (apply str (repeat 64 "0")))))
                                    :newVerifierRegistryRoot (evm-bytes32 (or (:new-verifier-registry-root mutation) (str "0x" (apply str (repeat 64 "0")))))
                                    :epoch (or (:epoch mutation) 0)
                                    :proofMode (or (:proof-mode mutation) 0)})
                                 mutations)]
      {:valid? valid?
       :qualification (if valid? :authenticated-authority-transition :rejected)
       :errors errors
       :scope scope
       :decision-preimages-root (evm-bytes32 (:authority-transition/decision-preimages-root envelope))
       :execution-mutation-root (:authority-transition/execution-mutation-root envelope)
       :mutations output-mutations
       :attestation (get-in envelope [:signature :signature-bytes])
       :signature-key-id (get-in envelope [:signature :key-id])})
    (catch Exception error
      {:valid? false :qualification :rejected :errors [(.getMessage error)]})))

(defn -main [& [mode request-path trusted-context-path & extra]]
  (when-not (and (= mode "verify-json") request-path trusted-context-path (empty? extra))
    (binding [*out* *err*]
      (println "Usage: clojure -M:test -m scripts.deployment.authority-transition-review verify-json <request.edn> <trusted-context.edn>"))
    (System/exit 2))
  (let [request (edn/read-string (slurp (io/file request-path)))
        trusted-context (edn/read-string (slurp (io/file trusted-context-path)))
        result (verify-request request trusted-context)
        output (-> (json-safe result)
                   (dissoc "valid?")
                   (assoc "valid" (:valid? result)))]
    (println (json/write-str output :escape-slash false))
    (when-not (:valid? result) (System/exit 1))))
