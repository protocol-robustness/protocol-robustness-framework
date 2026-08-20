(ns resolver-sim.pro-rata.quantity
  "Globally scoped protocol-neutral quantity identities. A quantity root is not
   an account label: it commits the protocol instance, state domain, subject,
   kind, asset (when applicable), and allocation scope."
  (:require [resolver-sim.hash.canonical :as hc]))

(def schema-version "canonical-quantity-identity.v1")

(defn quantity-root [identity]
  (hc/domain-hash :canonical-quantity-identity
                  (select-keys identity [:schema-version :protocol-instance/root
                                         :state-domain/root :subject/root
                                         :quantity-kind :asset/root :scope/root])))

(defn build-identity
  [{:keys [protocol-instance-root state-domain-root subject-root quantity-kind
           asset-root scope-root] :as identity}]
  (when-not (and (every? string? [protocol-instance-root state-domain-root subject-root scope-root])
                 (keyword? quantity-kind)
                 (or (nil? asset-root) (string? asset-root)))
    (throw (ex-info "incomplete canonical quantity identity" {:identity identity})))
  (let [base {:schema-version schema-version
              :protocol-instance/root protocol-instance-root
              :state-domain/root state-domain-root
              :subject/root subject-root
              :quantity-kind quantity-kind
              :asset/root asset-root
              :scope/root scope-root}]
    (assoc base :quantity/root (quantity-root base))))

(defn valid-identity? [identity]
  (and (= schema-version (:schema-version identity))
       (= (:quantity/root identity) (quantity-root identity))))
