(ns resolver-sim.build.default-attestation-sign-cli
  "Local operator signing entrypoint for default-build release authorization."
  (:require [clojure.edn :as edn]
            [resolver-sim.benchmark.signing :as signing]
            [resolver-sim.build.default-attestation :as att]
            [resolver-sim.run.release-attestation :as release]))

(defn -main [& args]
  (let [[bundle-path distribution key-id private-key-path release-edn & extra] args]
    (when (or (seq extra) (some nil? [bundle-path distribution key-id private-key-path release-edn]))
      (throw (ex-info "Usage: ... default-attestation-sign-cli <bundle.edn> <prf|sew> <key-id> <private-key> <release-metadata.edn>"
                      {:reason :invalid-default-build-signing-arguments})))
    (let [bundle (att/read-bundle bundle-path)
          payload (att/release-payload-for-bundle bundle (keyword distribution)
                                                  (edn/read-string release-edn))
          private-key (signing/load-private-key! private-key-path nil)
          signature (release/sign-payload payload private-key key-id)
          updated (att/attach-release-authorization bundle payload [signature])]
      (att/write-bundle! updated bundle-path)
      (println "Attached release authorization to:" bundle-path)
      (println "Signed payload:" (:payload/hash payload)))))
