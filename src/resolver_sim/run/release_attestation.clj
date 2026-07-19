(ns resolver-sim.run.release-attestation
  "Canonical release-attestation payload and fail-closed trust-policy checks."
  (:require [resolver-sim.hash.canonical :as canonical]))

(def payload-schema "prf-release-attestation-payload.v1")
(def signature-schema "prf-release-signature.v1")
(def verification-schema "prf-release-verification.v1")

(defn payload-hash [payload]
  (str "sha256:"
       (canonical/domain-hash "PRF_RELEASE_ATTESTATION_PAYLOAD_V1"
                              (dissoc payload :payload/hash))))

(defn build-payload [{:keys [distribution implementation release]}]
  (let [payload {:schema-version payload-schema
                 :distribution distribution
                 :implementation implementation
                 :release release}]
    (assoc payload :payload/hash (payload-hash payload))))

(defn verify-policy
  "Validate policy structure. An empty trusted-key set is valid but cannot
   authorize a release; this keeps production trust explicit and fail-closed."
  [policy]
  {:valid? (and (= "prf-release-trust-policy.v1" (:schema-version policy))
                (keyword? (:policy-id policy))
                (integer? (:policy-version policy))
                (vector? (:trusted-keys policy))
                (map? (:requirements policy))
                (= payload-schema (get-in policy [:canonicalization :payload-profile])))
   :reason (when-not (= "prf-release-trust-policy.v1" (:schema-version policy))
             :release-trust-policy-invalid)})

(defn unsigned-verification [distribution policy]
  {:schema-version verification-schema
   :distribution distribution
   :authorization {:status :missing
                   :reason-code :release-signature-missing
                   :valid-signature-count 0
                   :required-signatures (get-in policy [:requirements :distribution/prf :minimum-valid-signatures])
                   :trust-policy-id (:policy-id policy)
                   :trust-policy-version (:policy-version policy)}
   :verdict :integrity-verified-distribution})
