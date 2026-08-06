(ns resolver-sim.resubmission.basis
  "Package commitment cutpoints for the resubmission contract.

   Removes the revision-2 circularity (submission-package-root -> envelope ->
   resubmission-link -> body -> submission-package-root) by defining TWO package
   commitments:

     - :submission-basis-root  commits the pre-link submission material under a
                               precisely defined cutpoint; placed in the link.
     - :final-bundle-root      commits the complete assembled submitted bundle
                               (basis + link + envelope + signatures); NEVER
                               placed in the link — committed only by the
                               validator-issued attempt receipt as
                               :attempt-receipt/submitted-bundle-root.

   Acyclic dependency chain:

     submission basis -> resubmission link -> publisher envelope ->
     complete submitted bundle -> validator-issued attempt receipt

   All roots are domain-separated canonical hashes over the typed binary
   encoding (CANONICAL_HASH_SPEC_V1_BINARY_ENCODING_ABI)."
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]))

(def basis-schema "submission-basis-projection.v1")
(def bundle-schema "submission-bundle-projection.v1")
(def basis-domain "prf.submission-basis.v1")
(def final-bundle-domain "prf.submission-bundle.v1")

;; Keys that MUST NOT be present in the basis projection (the cutpoint).
(def basis-excluded-keys
  "Keys the submission-basis projection must never contain."
  #{:resubmission-link :resubmission-link-hash :final-bundle-root
    :final-publisher-signature :attempt-receipt :submitted-bundle-root})

(defn basis-shape-valid?
  "True when the basis input map has all required components and none of the
   excluded items."
  [basis]
  (and (map? basis)
       (contains? basis :results-artifact)
       (contains? basis :certificate)
       (contains? basis :registry-entries)
       (contains? basis :publisher-policy)
       (empty? (set/intersection basis-excluded-keys (set (keys basis))))))

(defn submission-basis-projection
  "Canonical value tree committing the pre-link submission material under the
   cutpoint.

   `basis` shape:
     {:results-artifact       the pre-link results artifact (map or hash ref)
      :certificate            the current allocation certificate (map or hash ref)
      :execution-evidence     execution evidence (map or hash ref)
      :registry-entries       registry artifact entries OTHER than the link
      :publisher-envelope-unsigned  publisher-envelope fields WITHOUT the final
                                    signature (optional; nil -> empty map)
      :publisher-policy       publisher policy + key identity (map)

   Excluded at this boundary: the resubmission link, the final publisher
   signature, the final submitted-bundle root, and the attempt receipt."
  [basis]
  (if-not (basis-shape-valid? basis)
    (throw (ex-info "invalid submission-basis shape"
                    {:reason :invalid-basis-shape :basis basis}))
    {:schema-version basis-schema
     :results-artifact (:results-artifact basis)
     :certificate (:certificate basis)
     :execution-evidence (:execution-evidence basis)
     :registry-entries (vec (:registry-entries basis))
     :publisher-envelope-unsigned (or (:publisher-envelope-unsigned basis) {})
     :publisher-policy (:publisher-policy basis)}))

(defn submission-basis-root
  "The submission-basis-root committed by the pre-link material under the
   cutpoint. This value is placed IN the resubmission link."
  [basis]
  (str "sha256:" (hc/domain-hash basis-domain (submission-basis-projection basis))))

(defn final-bundle-projection
  "Canonical value tree committing the COMPLETE submitted bundle.

   This is the receipt-side commitment (the attempt receipt's
   :attempt-receipt/submitted-bundle-root). It references the basis root, the
   resubmission link hash, the signed publisher envelope hash, and the registry
   root — it never appears inside the resubmission link."
  [{:keys [submission-basis-root resubmission-link-hash publisher-envelope-hash
           registry-root]}]
  {:schema-version bundle-schema
   :submission-basis-root submission-basis-root
   :resubmission-link-hash resubmission-link-hash
   :publisher-envelope-hash publisher-envelope-hash
   :registry-root registry-root})

(defn final-bundle-root
  "The final submitted-bundle root committed by the validator-issued attempt
   receipt. Never placed in the resubmission link."
  [projection]
  (str "sha256:" (hc/domain-hash final-bundle-domain projection)))
