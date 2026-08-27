(ns resolver-sim.adapters.sew.aggregate-held-credit-numeric-realization-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.adapters.sew.aggregate-held-credit :as sew]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.pro-rata.allocation :as allocation]
            [resolver-sim.pro-rata.canonical-effects :as effects]
            [resolver-sim.pro-rata.effect-compilation-v2 :as compilation]
            [resolver-sim.pro-rata.modeled-numeric-realization :as numeric]
            [resolver-sim.pro-rata.proposed-realization :as proposed]
            [resolver-sim.pro-rata.target-map :as target-map]
            [resolver-sim.protocols.sew.pro-rata-application :as legacy]
            [resolver-sim.protocols.sew.types :as sew-types]))

(defn- root [n] (str "sha256:" (apply str (repeat 63 "0")) n))
(def allocation-scope (root "1"))
(def custody-scope (root "2"))
(def protocol-instance (root "3"))
(def state-domain (root "4"))
(def aggregate-subject (root "5"))
(def asset (root "6"))
(def token :USDC)
(def descriptor (sew/descriptor))
(def aggregate-quantity
  (sew/aggregate-quantity {:protocol-instance-root protocol-instance
                           :state-domain-root state-domain
                           :aggregate-subject-root aggregate-subject
                           :asset-root asset
                           :aggregate-custody-scope-root custody-scope}))
(def allocation-result
  (allocation/allocate {:allocation/id :sew-aggregate-numeric-all-active
                        :available 100
                        :rows [{:row/id :alice :obligation/id :alice :requested 60 :weight 60}
                               {:row/id :bob :obligation/id :bob :requested 40 :weight 40}]
                        :rounding-policy :largest-remainder
                        :tie-break-policy :canonical-row-id
                        :redistribution-policy :unallocated}))
(def mapping-profile-root
  (hc/domain-hash :sew-aggregate-held-credit-semantics
                  {:profile :allocation-target-map/many-to-one.v1}))
(def aggregate-target-map
  (target-map/build-aggregate-target-map
   {:allocation-subjects-root (root "7")
    :allocation-scope-root allocation-scope
    :aggregate-custody-scope-root custody-scope
    :mapping-profile-root mapping-profile-root
    :targets [{:allocation/subject-id :alice :mapping/role :aggregate-held-credit
               :quantity/root (:quantity/root aggregate-quantity)}
              {:allocation/subject-id :bob :mapping/role :aggregate-held-credit
               :quantity/root (:quantity/root aggregate-quantity)}]}))

(defn- native-before [amount]
  (-> (sew-types/empty-world)
      (assoc :held-adjustments [] :held-artifacts {})
      (assoc-in [:held-ledger/index :by-token token] amount)
      (assoc-in [:total-held token] amount)))

(defn fixture [amount]
  (let [before (native-before amount)
        locations (target-map/build-location-map
                   {:scope-root custody-scope
                    :adapter-descriptor-root (:adapter/descriptor-root descriptor)
                    :locations [{:quantity/root (:quantity/root aggregate-quantity)
                                 :native/path [:held-ledger/index :by-token token]}]})
        validation (target-map/validate-aggregate-target-map
                    {:allocation allocation-result
                     :target-map aggregate-target-map
                     :allocation-scope-root allocation-scope
                     :aggregate-custody-scope-root custody-scope
                     :adapter-descriptor-root (:adapter/descriptor-root descriptor)
                     :native-state-before-root (numeric/native-state-root before)
                     :native-location-map locations
                     :aggregate-quantity aggregate-quantity
                     :expected-identity {:protocol-instance/root protocol-instance
                                         :state-domain/root state-domain
                                         :subject/root aggregate-subject
                                         :quantity-kind :held-credit
                                         :asset/root asset
                                         :scope/root custody-scope
                                         :mapping/profile :allocation-target-map/many-to-one.v1}})
        compiled (compilation/compile-aggregate-held-credit
                  {:allocation allocation-result
                   :aggregate-target-map aggregate-target-map
                   :target-map-validation validation
                   :aggregate-semantics-root (sew/compilation-semantics-root)
                   :allocation-policy-root (root "9")})
        canonical-before {(:quantity/root aggregate-quantity) amount}
        transition (effects/transition canonical-before (:effects compiled))]
    {:before before :locations locations :validation validation :compiled compiled
     :canonical-before canonical-before :transition transition}))

(defn realization [fx]
  (numeric/build (assoc fx
                        :adapter-descriptor descriptor
                        :target-map-validation (:validation fx)
                        :aggregate-target-map aggregate-target-map
                        :native-location-map (:locations fx)
                        :aggregate-quantity aggregate-quantity
                        :compilation (:compiled fx)
                        :canonical-transition (:transition fx)
                        :canonical-before (:canonical-before fx)
                        :native-before (:before fx)
                        :token token
                        :derived-mirror-profile-root (sew/numeric-realization-semantics-root))))

(deftest modeled-numeric-realization-derives-exact-authoritative-and-mirror-leaves
  (let [fx (fixture 100)
        result (realization fx)
        candidate (:modeled-numeric-candidate result)
        qroot (:quantity/root aggregate-quantity)]
    (is (= :modeled-numeric-projection (:assurance/mode result)))
    (is (= [[:held-ledger/index :by-token :USDC] [:total-held :USDC]]
           (sort-by pr-str (proposed/changed-leaf-paths (:before fx) candidate))))
    (is (= 200N (get-in candidate [:held-ledger/index :by-token token])))
    (is (= 200N (get-in candidate [:total-held token])))
    (is (= (:state-after/root (:transition fx))
           (effects/state-root {qroot 200N})))
    (is (= (numeric/numeric-projection-root qroot 200N)
           (:numeric-projection-after/root result)))
    (is (not (contains? result :native-state-after/root))
        "numeric-only artifact intentionally does not claim a complete native after-state")))

(deftest numeric-projection-agrees-with-pure-legacy-application-only-on-numeric-leaves
  (let [fx (fixture 100)
        result (realization fx)
        legacy-after (legacy/apply-effects-to-world
                      (:before fx)
                      [{:effect {:effect/token token :effect/amount 60 :held/kind :credit :effect/account :alice}}
                       {:effect {:effect/token token :effect/amount 40 :held/kind :credit :effect/account :bob}}])]
    (is (= (get-in legacy-after [:held-ledger/index :by-token token])
           (get-in (:modeled-numeric-candidate result) [:held-ledger/index :by-token token])))
    (is (= (get-in legacy-after [:total-held token])
           (get-in (:modeled-numeric-candidate result) [:total-held token])))
    (is (not= legacy-after (:modeled-numeric-candidate result))
        "Stage B intentionally excludes legacy adjustment and artifact surfaces")))

(deftest numeric-realization-rejects-substitution-and-invalid-native-numeric-state
  (let [fx (fixture 100)
        base (assoc fx :adapter-descriptor descriptor :target-map-validation (:validation fx)
                    :aggregate-target-map aggregate-target-map :native-location-map (:locations fx)
                    :aggregate-quantity aggregate-quantity :compilation (:compiled fx)
                    :canonical-transition (:transition fx) :canonical-before (:canonical-before fx)
                    :native-before (:before fx) :token token
                    :derived-mirror-profile-root (sew/numeric-realization-semantics-root))]
    (doseq [bad [(assoc base :token :DAI)
                 (assoc-in base [:native-before :total-held token] 99)
                 (update base :native-before #(update % :total-held dissoc token))
                 (assoc-in base [:native-before :held-ledger/index :by-token token] nil)
                 (assoc base :aggregate-quantity (assoc aggregate-quantity :scope/root (root "f")))
                 (assoc base :compilation (assoc (:compiled fx) :effects/root (root "e")))
                 (assoc base :canonical-transition (assoc (:transition fx) :effects/root (root "d")))
                 (assoc base :complete-legacy-state? true)]]
      (is (thrown? clojure.lang.ExceptionInfo (numeric/build bad))))))

(deftest numeric-realization-rejects-location-validation-and-diff-substitution
  (let [fx (fixture 100)
        base (assoc fx :adapter-descriptor descriptor :target-map-validation (:validation fx)
                    :aggregate-target-map aggregate-target-map :native-location-map (:locations fx)
                    :aggregate-quantity aggregate-quantity :compilation (:compiled fx)
                    :canonical-transition (:transition fx) :canonical-before (:canonical-before fx)
                    :native-before (:before fx) :token token
                    :derived-mirror-profile-root (sew/numeric-realization-semantics-root))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (numeric/build (assoc base :target-map-validation
                                       (assoc (:validation fx) :target-map/root (root "a"))))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (numeric/build (assoc base :native-location-map
                                       (assoc (:locations fx) :locations
                                              [{:quantity/root (:quantity/root aggregate-quantity)
                                                :native/path [:held-ledger/index :by-token :DAI]}])))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (with-redefs [proposed/changed-leaf-paths
                               (fn [_ _] [[:held-ledger/index :by-token token]
                                          [:total-held token]
                                          [:unrelated]])]
                   (numeric/build base))))))
