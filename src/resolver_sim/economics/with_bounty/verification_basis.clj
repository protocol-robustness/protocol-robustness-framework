(ns resolver-sim.economics.with-bounty.verification-basis
  "with-bounty-verification-basis.v1 (ADR-0006 R1).

   A first-class, versioned, content-addressed artifact committing exactly what
   a verifier evaluated: subject/package/artifact roots, the verification
   contract and version, entrypoint and invocation parameters, dependency /
   lockfile root, runtime / environment root, vector-set root, resource-limit
   profile, expected public-result schema, and the classification policy
   profile.

   Two verifier attestations can only legitimately disagree when they bind the
   same basis root; a different basis is classified :basis-mismatch and
   excluded from status derivation (ADR-0006 R2). The basis is included
   directly in every verifier attestation, never reconstructed from surrounding
   context."
  (:require [resolver-sim.hash.canonical :as hc]))

(def schema-version
  "with-bounty-verification-basis.v1")

(def basis-domain-tag
  :with-bounty-verification-basis-v1)

(def basis-projection-fields
  [:schema-version
   :basis/subject-root
   :basis/package-root
   :basis/artifact-root
   :basis/verification-contract
   :basis/verification-contract-version
   :basis/entrypoint
   :basis/invocation-parameters
   :basis/dependency-lockfile-root
   :basis/runtime-root
   :basis/environment-root
   :basis/vector-set-root
   :basis/resource-limit-profile
   :basis/expected-public-result-schema
   :basis/classification-policy-root])

(defn verification-basis-root
  "Content-addressed root of a committed verification basis."
  [basis]
  (hc/domain-hash basis-domain-tag
                  (select-keys basis basis-projection-fields)))

(defn build-verification-basis
  "Assemble a verification-basis.v1 from its committed fields and attach
   :basis/root."
  [{:keys [subject-root package-root artifact-root
           verification-contract verification-contract-version
           entrypoint invocation-parameters
           dependency-lockfile-root runtime-root environment-root
           vector-set-root resource-limit-profile
           expected-public-result-schema classification-policy-root]}]
  (let [base {:schema-version schema-version
              :basis/subject-root subject-root
              :basis/package-root package-root
              :basis/artifact-root artifact-root
              :basis/verification-contract verification-contract
              :basis/verification-contract-version verification-contract-version
              :basis/entrypoint entrypoint
              :basis/invocation-parameters (or invocation-parameters {})
              :basis/dependency-lockfile-root dependency-lockfile-root
              :basis/runtime-root runtime-root
              :basis/environment-root environment-root
              :basis/vector-set-root vector-set-root
              :basis/resource-limit-profile (or resource-limit-profile {})
              :basis/expected-public-result-schema expected-public-result-schema
              :basis/classification-policy-root classification-policy-root}]
    (assoc base :basis/root (verification-basis-root base))))

(defn validate-verification-basis
  "Structurally validate a verification basis: exact committed shape (unknown
   top-level keys rejected) and required roots."
  [basis]
  (if-not (map? basis)
    {:valid? false :errors [:non-map-basis]}
    (let [known (set (conj basis-projection-fields :basis/root))
          unknown (vec (sort (remove known (keys basis))))
          required-roots [:basis/subject-root :basis/package-root
                          :basis/verification-contract
                          :basis/entrypoint
                          :basis/dependency-lockfile-root
                          :basis/runtime-root
                          :basis/vector-set-root
                          :basis/expected-public-result-schema]
          missing (vec (remove #(string? (get basis %)) required-roots))
          errors (cond-> []
                   (not= schema-version (:schema-version basis))
                   (conj :unsupported-schema-version)

                   (seq unknown)
                   (conj [:unknown-keys unknown])

                   (seq missing)
                   (conj [:missing-roots missing]))]
      (if (seq errors)
        {:valid? false :errors errors}
        {:valid? true}))))

(defn verify-verification-basis
  "Verify a committed verification basis: shape plus recomputed root match."
  [basis]
  (let [v (validate-verification-basis basis)]
    (if-not (:valid? v)
      v
      (let [computed (verification-basis-root basis)
            stored (:basis/root basis)]
        (if (= computed stored)
          {:valid? true}
          {:valid? false :errors [:hash-mismatch]
           :computed computed :stored stored})))))
