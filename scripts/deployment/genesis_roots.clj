(ns scripts.deployment.genesis-roots
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [resolver-sim.genesis :as genesis]))

(defn derive-root
  "Derive a PRF-owned canonical genesis/configuration root for deployment review.
   Results are derivations only; this function does not establish artifact approval."
  [kind artifact]
  (let [[schema root]
        (case kind
          :protocol-genesis
          (let [package? (contains? artifact :protocol-genesis)
                body (if package? (:protocol-genesis artifact) artifact)
                computed (genesis/protocol-genesis-root body)
                declared (when package? (:protocol-genesis/root artifact))]
            (when (and declared (not= declared computed))
              (throw (ex-info "protocol-genesis declared root mismatch"
                              {:kind kind :declared declared :computed computed})))
            ["protocol-genesis.v1" computed])

          :chain-configuration
          ["chain-configuration.v1" (genesis/chain-configuration-root artifact)]

          :chain-instance-genesis
          ["chain-instance-genesis.v1" (genesis/chain-instance-genesis-root artifact)]

          :chain-configuration-transition
          ["chain-configuration-transition.v1" (genesis/chain-configuration-transition-root artifact)]

          :empty-verifier-registry
          ["verifier-registry.v1" (genesis/canonical-verifier-registry-root [])]

          :authority-state
          (throw (ex-info "no authoritative authority-state canonical artifact/schema is registered"
                          {:kind kind :status :blocked}))

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
        (println "Usage: clojure -M -m scripts.deployment.genesis-roots <protocol-genesis|chain-configuration|chain-instance-genesis|chain-configuration-transition> <artifact.edn>"))
      (System/exit 2))
    (let [artifact (when input-path (edn/read-string (slurp (io/file input-path))))
          result (derive-root (keyword kind) artifact)]
      (println (json/write-str result)))))
