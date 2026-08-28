(ns resolver-sim.pro-rata.realized-allocation-aggregate-held-credit-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.adapters.sew.aggregate-held-credit :as held-credit]
            [resolver-sim.allocation.context :as context]
            [resolver-sim.allocation.realized-statement :as statement]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.pro-rata.allocation :as allocation]
            [resolver-sim.pro-rata.realized-allocation-aggregate-held-credit :as sut]
            [resolver-sim.pro-rata.target-map :as target-map]))

(defn- root [n] (str "sha256:" (apply str (repeat 63 "0")) n))

(def raw-context
  {"allocation-id" "aggregate-held-credit-bridge"
   "kernel-version" "allocation-kernel.v1"
   "selection-algorithm" "domain-hash-rejection-v1"
   "policy" {"policy-id" "bridge-policy"
             "policy-hash" "0xabababababababababababababababababababababababababababababababab"
             "forbid-duplicate-owners" false}
   "claimants" [{"claim-id" "alice" "economic-owner-id" "owner-a" "amount" "60" "weight" "60"}
                {"claim-id" "bob" "economic-owner-id" "owner-b" "amount" "40" "weight" "40"}]
   "outcomes" [{"outcome-id" "selected"
                "allocations" [{"claim-id" "alice" "allocated" "60"}
                               {"claim-id" "bob" "allocated" "40"}]}]
   "proposed-rates" [{"outcome-id" "selected" "numerator" "1" "denominator" "1"}]
   "capacity" "100"
   "total-eligible-weight" "100"
   "exact-pro-rata-denominator" "100"
   "authoritative-randomness" "0x0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20"})

(def decision
  {:requested {"alice" 60N "bob" 40N}
   :filled {"alice" 60N "bob" 40N}
   :deferred {}
   :haircut {}
   :policy {:mode :pro-rata :rounding-policy :largest-remainder}})

(def lifecycle {:round/id "bridge-round" :round/status :result-accepted})

(defn- bridge-body []
  (let [ctx (context/build-context raw-context)
        s (statement/build-statement {:ctx ctx :decision decision :round-lifecycle lifecycle})
        allocation (allocation/allocate
                    {:allocation/id [:realized-allocation-statement (:statement/root s)]
                     :available 100N
                     :rows [{:row/id "alice" :obligation/id "alice" :requested 60N :weight 60N}
                            {:row/id "bob" :obligation/id "bob" :requested 40N :weight 40N}]
                     :rounding-policy :largest-remainder
                     :tie-break-policy :canonical-row-id
                     :redistribution-policy :unallocated})
        descriptor (held-credit/descriptor)
        quantity (held-credit/aggregate-quantity
                  {:protocol-instance-root (root "1") :state-domain-root (root "2")
                   :aggregate-subject-root (root "3") :asset-root (root "4")
                   :aggregate-custody-scope-root (root "5")})
        profile-root (hc/domain-hash :sew-aggregate-held-credit-semantics
                                     {:profile :allocation-target-map/many-to-one.v1})
        targets (target-map/build-aggregate-target-map
                 {:allocation-subjects-root (root "6") :allocation-scope-root (root "7")
                  :aggregate-custody-scope-root (root "5") :mapping-profile-root profile-root
                  :targets [{:allocation/subject-id "alice" :mapping/role :aggregate-held-credit
                             :quantity/root (:quantity/root quantity)}
                            {:allocation/subject-id "bob" :mapping/role :aggregate-held-credit
                             :quantity/root (:quantity/root quantity)}]})
        locations (target-map/build-location-map
                   {:scope-root (root "5")
                    :adapter-descriptor-root (:adapter/descriptor-root descriptor)
                    :locations [{:quantity/root (:quantity/root quantity)
                                 :native/path [:held-ledger/index :by-token :USDC]}]})
        validation (target-map/validate-aggregate-target-map
                    {:allocation allocation :target-map targets
                     :allocation-scope-root (root "7") :aggregate-custody-scope-root (root "5")
                     :adapter-descriptor-root (:adapter/descriptor-root descriptor)
                     :native-state-before-root (root "8") :native-location-map locations
                     :aggregate-quantity quantity
                     :expected-identity {:protocol-instance/root (root "1")
                                         :state-domain/root (root "2")
                                         :subject/root (root "3") :quantity-kind :held-credit
                                         :asset/root (root "4") :scope/root (root "5")
                                         :mapping/profile :allocation-target-map/many-to-one.v1}})]
    {:allocation-context-input raw-context :decision decision :round-lifecycle lifecycle
     :aggregate-quantity quantity :aggregate-target-map targets
     :native-location-map locations :adapter-descriptor descriptor
     :target-map-validation validation}))

(deftest bridge-derives-one-asset-bound-aggregate-credit-from-v1-bodies
  (let [bridge (sut/build (bridge-body))]
    (is (= sut/schema-version (:schema-version bridge)))
    (is (= 100N (:aggregate-amount bridge)))
    (is (= [{:quantity/root (get-in bridge [:aggregate-quantity :quantity/root]) :delta 100N}]
           (mapv #(select-keys % [:quantity/root :delta])
                 (get-in bridge [:compilation :effects]))))
    (is (= (root "4") (get-in bridge [:aggregate-quantity :asset/root]))
        "asset is target-bound by the aggregate quantity, not selected by V1")
    (is (:valid? (sut/verify bridge)))))

(deftest verifier-rejects-persisted-row-and-target-substitution
  (let [bridge (sut/build (bridge-body))]
    (testing "a substituted derived row is not trusted"
      (is (false? (:valid? (sut/verify
                            (update-in bridge [:realized-allocation :rows]
                                       #(assoc % 0 (assoc (first %) :allocated 59N))))))))
    (testing "an omitted target is rejected even if its old root is retained"
      (is (false? (:valid? (sut/verify
                            (update-in bridge [:aggregate-target-map :targets] pop))))))
    (testing "a duplicate persisted target is rejected"
      (is (false? (:valid? (sut/verify
                            (update-in bridge [:aggregate-target-map :targets]
                                       conj (first (get-in bridge [:aggregate-target-map :targets]))))))))))
