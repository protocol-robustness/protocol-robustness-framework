(ns resolver-sim.adapters.sew.aggregate-held-credit
  "Stage A adapter data for SEW's aggregate per-token held custody.

   This namespace constructs identities and profile commitments only. It does
   not project/reconstruct native state, authorize mirrors, append adjustments,
   or write to a store."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.pro-rata.execution-context :as context]
            [resolver-sim.pro-rata.quantity :as quantity]))

(def profile :sew/aggregate-held-credit.v1)
(def numeric-realization-profile :sew/aggregate-held-credit-numeric-realization.v1)

(defn compilation-semantics-root []
  (hc/domain-hash :sew-aggregate-held-credit-semantics
                  {:schema-version "sew-aggregate-held-credit-semantics.v1"
                   :profile profile
                   :outcomes [:allocated]
                   :asset-cardinality :single
                   :direction :credit
                   :custody :aggregate-token-held}))

(defn descriptor []
  (context/build-descriptor {:adapter-id :sew/pro-rata-held-credit
                             :adapter-version 1
                             :projection-profile :sew-aggregate-held-credit.v1
                             :reconstruction-profile :sew-aggregate-held-credit.v1
                             :frame-profile :exact-native-leaf-paths.v1}))

(defn numeric-realization-semantics-root []
  (hc/domain-hash :sew-aggregate-held-credit-numeric-realization-semantics
                  {:schema-version "sew-aggregate-held-credit-numeric-realization-semantics.v1"
                   :profile numeric-realization-profile
                   :assurance/mode :modeled-numeric-projection
                   :authoritative-location [:held-ledger/index :by-token :token]
                   :derived-mirror-location [:total-held :token]
                   :frame/profile :exact-native-leaf-paths.v1
                   :claims [:numeric-projection]
                   :excludes [:append-history :custody-artifacts :persistence
                              :write-back :read-back :external-execution]}))

(defn aggregate-quantity
  "Build one aggregate custody quantity. The subject and scope are supplied as
   explicit roots for the aggregate custody holder and token-level ledger;
   allocation round scope is intentionally not used as the native quantity
   scope because the real SEW leaf is shared across rounds."
  [{:keys [protocol-instance-root state-domain-root aggregate-subject-root
           asset-root aggregate-custody-scope-root]}]
  (quantity/build-identity {:protocol-instance-root protocol-instance-root
                            :state-domain-root state-domain-root
                            :subject-root aggregate-subject-root
                            :quantity-kind :held-credit
                            :asset-root asset-root
                            :scope-root aggregate-custody-scope-root}))
