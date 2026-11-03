(ns resolver-sim.privacy.initialization
  "Offline stablecoin privacy bootstrap records. A bootstrap root is trusted
   only when supplied by an independently configured trust anchor."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]
            [resolver-sim.privacy.policy :as policy]))

(def schema-version "stablecoin-privacy-initialization.v1")
(def ^:private fields
  #{:privacy-initialization/schema :privacy-initialization/issuer
    :privacy-initialization/asset-root :privacy-initialization/currency
    :privacy-initialization/jurisdiction :privacy-initialization/policy-root
    :privacy-initialization/authority-root :privacy-initialization/bootstrap-root})
(defn initialization-root [artifact]
  (ref/sha256-ref
   (hc/domain-hash "prf.stablecoin-privacy-initialization.v1"
                   (dissoc artifact :privacy-initialization/bootstrap-root))))
(defn build-initialization
  "Create an integrity-protected bootstrap record. This does not approve it."
  [{:keys [issuer asset-root currency jurisdiction policy-root authority-root]}]
  (let [body {:privacy-initialization/schema schema-version
              :privacy-initialization/issuer issuer
              :privacy-initialization/asset-root asset-root
              :privacy-initialization/currency currency
              :privacy-initialization/jurisdiction jurisdiction
              :privacy-initialization/policy-root policy-root
              :privacy-initialization/authority-root authority-root}
        artifact (assoc body :privacy-initialization/bootstrap-root
                        (initialization-root body))]
    (when-not (and (string? issuer) (seq issuer)
                   (ref/valid-sha256-ref? asset-root)
                   (string? currency) (seq currency)
                   (string? jurisdiction) (seq jurisdiction)
                   (ref/valid-sha256-ref? policy-root)
                   (ref/valid-sha256-ref? authority-root))
      (throw (ex-info "invalid stablecoin initialization fields" {})))
    artifact))
(defn verify-initialization
  "Verify initialization integrity and anchoring. `trusted-bootstrap-roots`
   must come from out-of-band trusted configuration, not this artifact."
  [artifact policy-body trusted-bootstrap-roots]
  (let [root (:privacy-initialization/bootstrap-root artifact)
        policy-result (policy/validate-policy policy-body)
        structural? (and (map? artifact)
                         (= fields (set (keys artifact)))
                         (= schema-version (:privacy-initialization/schema artifact))
                         (= root (initialization-root artifact))
                         (:valid? policy-result)
                         (= (:privacy-initialization/policy-root artifact)
                            (:privacy-policy/root policy-body))
                         (= (:privacy-initialization/issuer artifact)
                            (:privacy-policy/issuer policy-body))
                         (= (:privacy-initialization/asset-root artifact)
                            (:privacy-policy/asset-root policy-body))
                         (some #{(:privacy-initialization/jurisdiction artifact)}
                               (:privacy-policy/jurisdictions policy-body)))
        anchored? (and structural? (contains? (set trusted-bootstrap-roots) root))]
    {:valid? (boolean anchored?)
     :status (cond anchored? :historical-verified
                   structural? :authority-unverified
                   :else :invalid)
     :bootstrap-root root
     :policy-root (:privacy-initialization/policy-root artifact)
     :currentness :unknown
     :errors (cond-> []
               (not structural?) (conj :initialization-integrity-or-policy-mismatch)
               (and structural? (not anchored?)) (conj :bootstrap-root-not-trusted))}))

(defn resolve-initial-policy
  "Resolve the immutable policy named by initialization. The trusted bootstrap
   roots are deployment inputs; without a matching root this returns no policy.
   This is historical resolution only and never establishes live currentness."
  [initialization policy-body trusted-bootstrap-roots]
  (let [verification (verify-initialization initialization policy-body
                                             trusted-bootstrap-roots)]
    (if (:valid? verification)
      {:resolved? true
       :status :historical-verified
       :policy policy-body
       :policy-root (:privacy-policy/root policy-body)
       :bootstrap-root (:bootstrap-root verification)
       :currentness :unknown}
      {:resolved? false
       :status (:status verification)
       :policy nil
       :policy-root (:policy-root verification)
       :currentness :unknown
       :errors (:errors verification)})))
