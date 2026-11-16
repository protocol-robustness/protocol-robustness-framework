(ns resolver-sim.evidence.forensic-claims
  "Forensic-grade claim definitions for the Sew evidence chain.
   Registered dynamically into the passive claim-definition-registry.
   These claims reference resolver-sim.evidence.chain functions that
   live in protocols_src/ and are only available with :with-sew."
  (:require [resolver-sim.definitions.passive-registries :as registries]
            [resolver-sim.hash.canonical :as hc]))

(def forensic-claim-definitions
  "Forensic-grade claim definitions registered by the Sew protocol.
   Each corresponds to a forensic-grade acceptance criterion."
  [{:id :registry-hash-verifies
    :version 1
    :category :audit
    :scope {:protocols #{:sew}}
    :description "Evidence registry hash is consistent with its content. Recomputes the registry hash from the artifact entries and compares against the recorded :registry-hash."
    :inputs [:evidence-registry]
    :evaluation {:type :code-reference
                 :entry 'resolver-sim.evidence.chain/verify-registry-hash}
    :outputs [:passed? :computed-hash :recorded-hash]}
   {:id :registry-hash-signed
    :version 1
    :category :audit
    :scope {:protocols #{:sew}}
    :description "Evidence registry hash is signed by a known Ed25519 signer. The signature.json and envelope.json artifacts must be present and valid."
    :inputs [:evidence-registry :signature-map]
    :evaluation {:type :code-reference
                 :entry 'resolver-sim.evidence.chain/verify-registry-signature}
    :outputs [:passed? :hash :signer]}
   {:id :cursor-verifies
    :version 1
    :category :audit
    :scope {:protocols #{:sew}}
    :description "Chain cursor content hash matches the signed hash in the cursor's forensic data. Verifies the Ed25519 signature over the cursor snapshot."
    :inputs [:chain-cursor]
    :evaluation {:type :code-reference
                 :entry 'resolver-sim.evidence.chain/verify-cursor-signature}
    :outputs [:passed? :hash :cursor-seq]}
   {:id :tsa-token-verified
    :version 1
    :category :audit
    :scope {:protocols #{:sew}}
    :description "TSA response validates against the registry hash. The RFC 3161 timestamp binds the evidence chain to a wall-clock time."
    :inputs [:registry-hash :tsa-response :tsa-url]
    :evaluation {:type :code-reference
                 :entry 'resolver-sim.evidence.timestamping/verify-tsa-token}
    :outputs [:passed? :tsa-url :timestamp]}
   {:id :evidence-chain-reconciled
    :version 1
    :category :audit
    :scope {:protocols #{:sew}}
    :description "Evidence files on disk, the registry, and the chain cursor are consistent. No unregistered evidence files and the cursor is not behind disk evidence."
    :inputs [:artifact-dir]
    :evaluation {:type :code-reference
                 :entry 'resolver-sim.evidence.chain/reconcile-evidence!}
    :outputs [:passed? :disk-count :registry-count :cursor-seq :max-disk-seq]}
   {:id :forensic-grade
    :version 1
    :category :composite
    :scope {:protocols #{:sew}}
    :description "All five forensic-grade acceptance criteria pass: registry hash verifies, registry hash is signed, cursor verifies, TSA token is valid, and evidence chain is reconciled."
    :inputs [:evidence-registry :signature-map :chain-cursor :tsa-response :artifact-dir]
    :evaluation {:type :code-reference
                 :entry 'resolver-sim.evidence.chain/forensic-status}
    :depends-on [:registry-hash-verifies
                 :registry-hash-signed
                 :cursor-verifies
                 :tsa-token-verified
                 :evidence-chain-reconciled]
    :outputs [:passed? :criteria :failures]}])

(defn register-forensic-claims!
  "Register forensic claim definitions into the passive claim-definition-registry.
   Must be called after the passive registries namespace has loaded.
   Idempotent — re-registration with matching hashes is a no-op.
   After registration, recomputes concept-hashes across all claim definitions
   (passive + forensic) in topological order.
   
   This uses alter-var-root for startup registration (called once at load time
   from the protocols_src path), not for test isolation. The
   claim-definitions var in passive_registries.clj is NOT declared ^:dynamic
   because it is a static registry, not a per-test context.
    
   If this var ever needs per-test isolation, declare it ^:dynamic and use
   the same binding[] pattern as the with-fresh-registry macros."
  []
  (let [existing-ids (set (keep :id @(requiring-resolve 'resolver-sim.definitions.passive-registries/claim-definitions)))
        new-claims (remove #(contains? existing-ids (:id %)) forensic-claim-definitions)
        hashed (mapv (fn [entry]
                       (assoc entry :canonical-hash
                              (hc/hash-with-intent {:hash/intent :claim-definition} entry)))
                     new-claims)]
    (when (seq hashed)
      (let [enrich-fn (ns-resolve 'resolver-sim.definitions.passive-registries 'enrich-claim-definitions)]
        (alter-var-root (requiring-resolve 'resolver-sim.definitions.passive-registries/claim-definitions)
                        (fn [existing]
                          (let [combined (into existing hashed)]
                            (enrich-fn combined)))))
      (println (str "Registered " (count hashed) " forensic claim definitions")))
    (count hashed)))
