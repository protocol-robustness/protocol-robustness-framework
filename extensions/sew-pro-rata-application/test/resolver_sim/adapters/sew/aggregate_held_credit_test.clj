(ns resolver-sim.adapters.sew.aggregate-held-credit-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.adapters.sew.aggregate-held-credit :as sew]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.pro-rata.allocation :as allocation]
            [resolver-sim.pro-rata.effect-compilation-v2 :as compilation]
            [resolver-sim.pro-rata.target-map :as target-map]))

(defn- root [n] (str "sha256:" (apply str (repeat 63 "0")) n))
(def allocation-scope (root "1"))
(def custody-scope (root "2"))
(def protocol-instance (root "3"))
(def state-domain (root "4"))
(def aggregate-subject (root "5"))
(def asset (root "6"))
(def descriptor (sew/descriptor))
(def aggregate-quantity (sew/aggregate-quantity
                         {:protocol-instance-root protocol-instance :state-domain-root state-domain
                          :aggregate-subject-root aggregate-subject :asset-root asset
                          :aggregate-custody-scope-root custody-scope}))
(def allocation-result
  (allocation/allocate {:allocation/id :sew-aggregate-all-active :available 100
                        :rows [{:row/id :alice :obligation/id :alice :requested 60 :weight 60}
                               {:row/id :bob :obligation/id :bob :requested 40 :weight 40}]
                        :rounding-policy :largest-remainder
                        :tie-break-policy :canonical-row-id
                        :redistribution-policy :unallocated}))
(def mapping-profile-root (hc/domain-hash :sew-aggregate-held-credit-semantics
                                          {:profile :allocation-target-map/many-to-one.v1}))
(def target-map
  (target-map/build-aggregate-target-map
   {:allocation-subjects-root (root "7") :allocation-scope-root allocation-scope
    :aggregate-custody-scope-root custody-scope :mapping-profile-root mapping-profile-root
    :targets [{:allocation/subject-id :alice :mapping/role :aggregate-held-credit :quantity/root (:quantity/root aggregate-quantity)}
              {:allocation/subject-id :bob :mapping/role :aggregate-held-credit :quantity/root (:quantity/root aggregate-quantity)}]}))
(def locations (target-map/build-location-map
                {:scope-root custody-scope :adapter-descriptor-root (:adapter/descriptor-root descriptor)
                 :locations [{:quantity/root (:quantity/root aggregate-quantity)
                              :native/path [:held-ledger/index :by-token :USDC]}]}))
(def validation
  (target-map/validate-aggregate-target-map
   {:allocation allocation-result :target-map target-map
    :allocation-scope-root allocation-scope :aggregate-custody-scope-root custody-scope
    :adapter-descriptor-root (:adapter/descriptor-root descriptor)
    :native-state-before-root (root "8") :native-location-map locations
    :aggregate-quantity aggregate-quantity
    :expected-identity {:protocol-instance/root protocol-instance :state-domain/root state-domain
                        :subject/root aggregate-subject :quantity-kind :held-credit :asset/root asset
                        :scope/root custody-scope :mapping/profile :allocation-target-map/many-to-one.v1}}))

(deftest aggregate-profile-commits-claimant-provenance-and-one-quantity
  (let [compiled (compilation/compile-aggregate-held-credit
                  {:allocation allocation-result :aggregate-target-map target-map
                   :target-map-validation validation :aggregate-semantics-root (sew/compilation-semantics-root)
                   :allocation-policy-root (root "9")})]
    (is (= "allocation-quantity-target-map.v2" (:schema-version target-map)))
    (is (= "allocation-quantity-target-map-validation.v2" (:schema-version validation)))
    (is (= #{:alice :bob} (set (map :allocation/subject-id (:targets target-map)))))
    (is (= #{(:quantity/root aggregate-quantity)} (set (map :quantity/root (:targets target-map)))))
    (is (= [{:quantity/root (:quantity/root aggregate-quantity) :delta 100N}]
           (mapv #(select-keys % [:quantity/root :delta]) (:effects compiled))))
    (is (= (:target-map/root target-map) (:target-map/root compiled)))
    (is (not= (:allocation/hash allocation-result) (:effect-compilation/root compiled)))))

(deftest aggregate-profile-rejects-row-substitution-and-unsupported-outcomes
  (is (thrown? clojure.lang.ExceptionInfo
               (target-map/validate-aggregate-target-map
                {:allocation (update allocation-result :rows #(subvec % 0 1)) :target-map target-map
                 :allocation-scope-root allocation-scope :aggregate-custody-scope-root custody-scope
                 :adapter-descriptor-root (:adapter/descriptor-root descriptor)
                 :native-state-before-root (root "8") :native-location-map locations
                 :aggregate-quantity aggregate-quantity
                 :expected-identity {:protocol-instance/root protocol-instance :state-domain/root state-domain
                                     :subject/root aggregate-subject :quantity-kind :held-credit :asset/root asset
                                     :scope/root custody-scope :mapping/profile :allocation-target-map/many-to-one.v1}})))
  (is (thrown? clojure.lang.ExceptionInfo
               (compilation/compile-aggregate-held-credit
                {:allocation (assoc allocation-result :unallocated-residual 1)
                 :aggregate-target-map target-map :target-map-validation validation
                 :aggregate-semantics-root (sew/compilation-semantics-root)
                 :allocation-policy-root (root "9")}))))
