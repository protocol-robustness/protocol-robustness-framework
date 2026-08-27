(ns resolver-sim.benchmark.governed-authority-result-receipt-store
  "D3 durable, detached persistence for governed-authority result receipts.

   Receipt bytes are stored under the receipt semantic root, never an incidental
   content hash. Readback dispatches on the receipt schema, validates the receipt
   independently, then resolves and verifies C/P/S bodies from caller-supplied
   immutable dependencies; it never consults an AuthorityStateStore."
  (:require [resolver-sim.benchmark.authority-semantics-policy :as policy]
            [resolver-sim.benchmark.governed-authority-result-receipt :as receipt]
            [resolver-sim.benchmark.governed-authority-semantics :as semantics]
            [resolver-sim.genesis :as genesis]
            [resolver-sim.io.content-addressed-store :as cas]))

(def ^:private receipt-verifiers
  {receipt/schema receipt/verify-receipt})

(def ^:private lineage-sides
  [{:name :pre
    :configuration :pre-chain-configuration/root
    :policy :pre-authority-semantics-policy/root
    :semantics :pre-governed-authority-semantics/root}
   {:name :successor
    :configuration :successor-chain-configuration/root
    :policy :successor-authority-semantics-policy/root
    :semantics :successor-governed-authority-semantics/root}])

(defn- reject! [reason message data]
  (throw (ex-info message (assoc data :reason reason))))

(defn- verifier-for [artifact]
  (or (get receipt-verifiers (:artifact/schema artifact))
      (reject! :unsupported-receipt-schema "Unsupported governed-authority result receipt schema"
               {:schema (:artifact/schema artifact)})))

(defn- verify-receipt! [artifact]
  (when-not ((verifier-for artifact) artifact)
    (reject! :receipt-verification-failed "Governed-authority result receipt failed verification" {}))
  artifact)

(defn- dependency-resolver [dependencies]
  (cond
    (fn? dependencies) dependencies
    (map? dependencies) #(get dependencies %)
    :else (reject! :invalid-dependency-resolver
                   "Dependencies must be a root-to-body resolver or map" {})))

(defn- dependency! [resolve-body root side kind]
  (or (resolve-body root)
      (reject! :missing-dependency "Receipt dependency is unavailable"
               {:root root :side side :kind kind})))

(defn- verify-root! [expected computed side kind]
  (when-not (= expected computed)
    (reject! :dependency-root-mismatch "Receipt dependency body does not match its declared root"
             {:expected expected :computed computed :side side :kind kind})))

(defn- verify-cps-side! [artifact resolve-body {:keys [name configuration policy semantics]}]
  (let [configuration-root (get artifact configuration)
        policy-root (get artifact policy)
        semantics-root (get artifact semantics)
        configuration-body (dependency! resolve-body configuration-root name :configuration)]
    (when-not (genesis/supported-chain-configuration? configuration-body)
      (reject! :invalid-configuration-dependency "Receipt configuration dependency is invalid"
               {:root configuration-root :side name}))
    (verify-root! configuration-root (genesis/chain-configuration-root configuration-body)
                  name :configuration)
    (case (:configuration/schema configuration-body)
      "chain-configuration.v1"
      (when (or policy-root semantics-root)
        (reject! :unexpected-cps-lineage "V1 configuration receipt must not carry C/P/S lineage"
                 {:side name}))

      "chain-configuration.v2"
      (do
        (when-not (and policy-root semantics-root)
          (reject! :incomplete-cps-lineage "V2 configuration receipt requires policy and semantics dependencies"
                   {:side name}))
        (let [policy-body (dependency! resolve-body policy-root name :policy)
              semantics-body (dependency! resolve-body semantics-root name :semantics)]
          (when-not (:valid? (policy/validate-policy policy-body))
            (reject! :invalid-policy-dependency "Receipt semantics-policy dependency is invalid"
                     {:root policy-root :side name}))
          (verify-root! policy-root (policy/policy-root policy-body) name :policy)
          (when-not (:valid? (semantics/validate-semantics semantics-body))
            (reject! :invalid-semantics-dependency "Receipt semantics dependency is invalid"
                     {:root semantics-root :side name}))
          (verify-root! semantics-root (semantics/semantics-root semantics-body) name :semantics)
          (when-not (= policy-root (:authority-semantics-policy/root configuration-body))
            (reject! :configuration-policy-mismatch "Receipt configuration does not select its policy dependency"
                     {:side name :configuration-root configuration-root :policy-root policy-root}))
          (when-not (:valid? (policy/verify-policy-selection policy-body semantics-body))
            (reject! :policy-semantics-mismatch "Receipt policy does not select its semantics dependency"
                     {:side name :policy-root policy-root :semantics-root semantics-root}))))
      (reject! :unsupported-configuration-schema "Receipt configuration schema is unsupported"
               {:side name :schema (:configuration/schema configuration-body)}))))

(defn verify-receipt-with-dependencies!
  "Verify a receipt and its detached C/P/S lineage. `dependencies` is either a
   function of receipt-root to body or a map keyed by receipt-root."
  [artifact dependencies]
  (let [artifact (verify-receipt! artifact)
        resolve-body (dependency-resolver dependencies)]
    (doseq [side lineage-sides]
      (verify-cps-side! artifact resolve-body side))
    artifact))

(defn persist-receipt!
  "Verify a governed-authority result receipt and persist canonical EDN under its
   semantic receipt root. Unsupported schemas and invalid roots fail before CAS
   insertion."
  [store artifact]
  (let [artifact (verify-receipt! artifact)
        root (:governed-authority-result-receipt/root artifact)]
    (cas/put-if-absent! store {:hash-reference root
                               :artifact artifact
                               :verify receipt/verify-receipt})))

(defn read-receipt!
  "Read a receipt from CAS and verify its semantic address, schema, receipt body,
   and detached C/P/S lineage. Does not use an AuthorityStateStore."
  [store receipt-root dependencies]
  (let [artifact (cas/resolve-artifact store receipt-root)]
    (when-not artifact
      (reject! :receipt-not-found "Governed-authority result receipt is unavailable"
               {:root receipt-root}))
    (when-not (= receipt-root (:governed-authority-result-receipt/root artifact))
      (reject! :receipt-address-mismatch "Stored receipt does not match its CAS address"
               {:address receipt-root
                :receipt-root (:governed-authority-result-receipt/root artifact)}))
    (verify-receipt-with-dependencies! artifact dependencies)))
