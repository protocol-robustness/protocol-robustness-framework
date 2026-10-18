(ns resolver-sim.commands.protocol-pro-rata
  "PRF-owned source derivation, receipt admission and retained execution checks."
  (:require [resolver-sim.commands.document :as doc]
            [clojure.java.io :as io]
            [resolver-sim.allocation.source-bound-entitlement :as source]
            [resolver-sim.allocation.persisted-statement-admission :as persisted]
            [resolver-sim.allocation.proof-admission :as admission]
            [resolver-sim.evidence.deployment-bound :as deployment]))

(defn derive-operation [opts]
  (doc/execute opts
               (fn [request]
                 (doc/closed! request #{:realized-statement-input :reserve-binding-root
                                        :prepared-operation :economic-reserve-state
                                        :beneficiary-mapping :openings} #{})
                 (doc/emit opts {:schema-version "protocol-pro-rata-operation.v1"
                                 :scope :controlled-source-conformance
                                 :transaction-submitted? false
                                 :operation (source/fixture-projection (source/derive-controlled-source request))}
                           0))))

(defn check-admission [opts]
  (doc/execute opts
               (fn [request]
                 (doc/closed! request #{:artifact :receipt :realized-input :proof-dir} #{})
                 (doc/require! (and (:trust-context opts) (not= "-" (:trust-context opts)))
                               "Specify an independent --trust-context FILE")
                 (let [trust (doc/read-request (:trust-context opts))
                       _ (doc/closed! trust #{:trust-policy :program-registry} #{})
                       input-path (doc/resolve-path (:input opts) (:realized-input request))
                       input-bytes (java.nio.file.Files/readAllBytes (.toPath (io/file input-path)))
                       rebuilt (persisted/reconstruct-input (String. input-bytes "UTF-8"))
                       input-digest (persisted/sha256-bytes-ref input-bytes)
                       input-bound? (= input-digest (get-in request [:receipt :persisted-input/sha256]))
                       proof-valid? (boolean
                                     (admission/verify-proof-file!
                                      (doc/resolve-path (:input opts) (:proof-dir request)) (:artifact request)))
                       admitted? (boolean
                                  (and input-bound? proof-valid?
                                       (admission/cryptographic-computation-admitted?
                                        (merge (select-keys request [:artifact :receipt]) rebuilt trust))))]
                   (doc/emit opts {:schema-version "protocol-pro-rata-admission-check.v1"
                                   :valid? admitted? :proof-file-valid? proof-valid?
                                   :persisted-input-bound? input-bound? :persisted-input/sha256 input-digest
                                   :profile admission/proof-profile
                                   :scope :signed-external-verifier-receipt
                                   :trust-context-source (:trust-context opts)
                                   :transaction-submitted? false}
                             (if admitted? 0 1))))))

(defn verify-execution [opts]
  (doc/execute opts
               (fn [request]
                 (doc/closed! request #{:bundle-dir} #{:source-bundle})
                 (let [resolve #(doc/resolve-path (:input opts) %)
                       result (deployment/reconstruct-bundle
                               (resolve (:bundle-dir request))
                               (cond-> {} (:source-bundle request)
                                       (assoc :source-bundle (resolve (:source-bundle request)))))
                       validation (:validation result)]
                   (doc/emit opts {:schema-version "protocol-pro-rata-execution-check.v1"
                                   :scope :retained-local-execution-evidence
                                   :live-observation? false
                                   :validation validation :artifact-hashes (:artifact-hashes result)
                                   :forensic-view (:view result)}
                             (if (:valid? validation) 0 1))))))
