(ns resolver-sim.build.default-attestation-verify-cli
  "Fail-closed verifier for signed default-build attestation bundles."
  (:require [clojure.edn :as edn]
            [resolver-sim.build.default-attestation :as att]))

(defn -main [& args]
  (let [[bundle-path artifact-root distribution policy-path & extra] args]
    (when (or (seq extra) (some nil? [bundle-path artifact-root distribution policy-path]))
      (throw (ex-info "Usage: ... default-attestation-verify-cli <bundle.edn> <artifact-root> <prf|sew> <trust-policy.edn>"
                      {:reason :invalid-default-build-verification-arguments})))
    (let [bundle (att/read-bundle bundle-path)
          policy (edn/read-string (slurp policy-path))
          result (att/verify-bundle
                  bundle artifact-root
                  {:distribution (keyword distribution)
                   :trust-policy policy
                   :require-release-authorization? true})]
      (println (pr-str result))
      (when-not (:verified? result)
        (throw (ex-info "Default build release authorization failed"
                        {:reason :default-build-release-verification-failed
                         :result result}))))))
