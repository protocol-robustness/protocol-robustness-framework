(ns resolver-sim.pro-rata.realized-allocation-aggregate-held-credit-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.adapters.sew.aggregate-held-credit :as held-credit]
            [resolver-sim.allocation.context :as context]
            [resolver-sim.allocation.realized-statement :as statement]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.pro-rata.allocation :as allocation]
            [resolver-sim.pro-rata.canonical-effects :as effects]
            [resolver-sim.pro-rata.effect-compilation-binding :as binding]
            [resolver-sim.pro-rata.effect-compilation-v2 :as compilation]
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

(defn- canonical-fixture []
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
    {:statement s
     :realized-allocation allocation
     :target-map-validation validation
     :bridge-body {:allocation-context-input raw-context :decision decision :round-lifecycle lifecycle
                   :aggregate-quantity quantity :aggregate-target-map targets
                   :native-location-map locations :adapter-descriptor descriptor
                   :target-map-validation validation}}))

(defn- bridge-body []
  (:bridge-body (canonical-fixture)))

(defn- standalone-validation [{:keys [realized-allocation bridge-body]}]
  (let [{:keys [aggregate-quantity aggregate-target-map native-location-map adapter-descriptor
                target-map-validation]} bridge-body]
    (target-map/validate-aggregate-target-map
     {:allocation realized-allocation
      :target-map aggregate-target-map
      :allocation-scope-root (:allocation-scope/root target-map-validation)
      :aggregate-custody-scope-root (:aggregate-custody-scope/root target-map-validation)
      :adapter-descriptor-root (:adapter/descriptor-root adapter-descriptor)
      :native-state-before-root (:native-state-before/root target-map-validation)
      :native-location-map native-location-map
      :aggregate-quantity aggregate-quantity
      :expected-identity (assoc (select-keys aggregate-quantity
                                             [:protocol-instance/root :state-domain/root
                                              :subject/root :quantity-kind :asset/root :scope/root])
                                :mapping/profile target-map/many-to-one-profile)})))

(deftest derive-rows-enforces-closed-claimant-inputs
  (let [ctx (context/build-context raw-context)
        derive-rows #'resolver-sim.pro-rata.realized-allocation-aggregate-held-credit/derive-rows
        rows (derive-rows ctx decision)]
    (is (vector? rows))
    (is (= ["bob" "alice"] (mapv :row/id rows)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (derive-rows ctx (assoc decision :requested {"alice" 60N}))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (derive-rows ctx (assoc decision :filled {"alice" 60N "bob" 40N "carol" 1N}))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (derive-rows ctx (assoc decision :requested []))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (derive-rows ctx (assoc decision :deferred {"bob" 1N}))))))

(deftest diagnostic-bridge-validator-boundary
  (let [fixture (canonical-fixture)
        expected-root (:allocation/hash (:realized-allocation fixture))
        body (:bridge-body fixture)
        result (try
                 {:value (sut/build body)}
                 (catch clojure.lang.ExceptionInfo error
                   {:error (ex-data error)}))]
    (is (= expected-root (:allocation/hash (:realized-allocation fixture))))
    (is (= expected-root
           (or (get-in result [:value :realized-allocation :allocation/hash])
               (get-in result [:error :allocation-root]))))
    (when-let [coverage (get-in result [:error :coverage])]
      (is (= #{"alice" "bob"} (:allocation-row-id-set coverage)))
      (is (= #{"alice" "bob"} (:target-subject-id-set coverage)))
      (is (= 2 (:allocation-row-count coverage)))
      (is (= 2 (:target-count coverage))))))

(deftest bridge-derives-one-asset-bound-aggregate-credit-from-v1-bodies
  (let [fixture (canonical-fixture)
        bridge (sut/build (:bridge-body fixture))
        standalone (standalone-validation fixture)]
    (is (= sut/schema-version (:schema-version bridge)))
    (is (= (:allocation/hash (:realized-allocation fixture))
           (:allocation/hash (:realized-allocation bridge))))
    (is (= standalone (:target-map-validation bridge)))
    (is (= (:target-map-validation/root standalone)
           (get-in bridge [:target-map-validation :target-map-validation/root])))
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

(deftest aggregate-effects-and-transition-reject-substitution
  (let [bridge (sut/build (bridge-body))
        compiled (:compilation bridge)
        quantity-root (get-in bridge [:aggregate-quantity :quantity/root])
        state-before {quantity-root 25N}
        canonical (effects/transition state-before (:effects compiled))
        bodies {(:allocation/hash (:realized-allocation bridge)) (:realized-allocation bridge)
                (:target-map/root (:aggregate-target-map bridge)) (:aggregate-target-map bridge)}
        resolve-body #(get bodies %)
        bound (binding/build compiled canonical)
        mutated-effects [(effects/delta quantity-root 99N)]
        mutated-transition (effects/transition state-before mutated-effects)
        mutated-compilation-base (assoc compiled :effects/root (:effects/root mutated-transition))
        mutated-compilation (assoc mutated-compilation-base
                                   :effect-compilation/root
                                   (compilation/compilation-root mutated-compilation-base))
        mutated-binding (binding/build mutated-compilation mutated-transition)
        mutated-after (assoc canonical :state-after/root (root "9"))]
    (is (binding/valid? bound compiled canonical resolve-body))
    (is (not (binding/valid? mutated-binding mutated-compilation mutated-transition resolve-body)))
    (is (not= (:canonical-effect-transition/root canonical)
              (effects/transition-root mutated-after)))
    (is (not= (:state-before/root canonical)
              (:state-before/root (effects/transition {quantity-root 26N}
                                                      (:effects compiled)))))))

(deftest aggregate-target-map-substitution-matrix
  (let [fixture (canonical-fixture)
        body (:bridge-body fixture)
        valid (:target-map-validation body)
        targets (:aggregate-target-map body)
        reject (fn [mutation]
                 (try
                   (target-map/validate-aggregate-target-map (mutation body))
                   false
                   (catch clojure.lang.ExceptionInfo _ true)))]
    (is (reject #(assoc-in % [:aggregate-target-map :targets] (pop (:targets targets)))))
    (is (reject #(update-in % [:aggregate-target-map :targets]
                            conj {:allocation/subject-id "carol"
                                  :mapping/role :aggregate-held-credit
                                  :quantity/root (get-in body [:aggregate-quantity :quantity/root])})))
    (is (reject #(update-in % [:aggregate-target-map :targets]
                            conj (first (:targets targets)))))
    (is (reject #(assoc-in % [:aggregate-target-map :targets 0 :allocation/subject-id] "carol")))
    (is (reject #(assoc-in % [:aggregate-target-map :targets 0 :quantity/root] (root "9"))))
    (is (reject #(assoc-in % [:target-map-validation :allocation-scope/root] (root "a"))))
    (is (reject #(assoc-in % [:target-map-validation :aggregate-custody-scope/root] (root "b"))))
    (is (reject #(assoc-in % [:target-map-validation :adapter/descriptor-root] (root "c"))))
    (is (= valid (:target-map-validation (sut/build body))))))

(deftest canonical-effects-and-binding-are-order-and-lineage-sensitive
  (let [fixture (canonical-fixture)
        bridge (sut/build (:bridge-body fixture))
        compilation (:compilation bridge)
        quantity-root (get-in bridge [:aggregate-quantity :quantity/root])
        state-before {quantity-root 25N}
        canonical (effects/transition state-before (:effects compilation))
        permuted (effects/transition state-before (reverse (:effects compilation)))
        other-before (effects/transition {quantity-root 26N} (:effects compilation))
        binding (binding/build compilation canonical)
        crossed (binding/build compilation other-before)]
    (is (= (:effects/root canonical) (:effects/root permuted)))
    (is (= (:canonical-effect-transition/root canonical)
           (:canonical-effect-transition/root permuted)))
    (is (not= (:state-before/root canonical) (:state-before/root other-before)))
    (is (not (binding/valid? binding compilation other-before (constantly nil))))
    (is (not (binding/valid? crossed compilation other-before (constantly nil))))))
