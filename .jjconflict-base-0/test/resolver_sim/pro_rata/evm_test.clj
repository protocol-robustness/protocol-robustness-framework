(ns resolver-sim.pro-rata.evm-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.pro-rata.allocation :as allocation]
            [resolver-sim.pro-rata.canonical-effects :as effects]
            [resolver-sim.pro-rata.effect-compilation-binding-v2 :as binding]
            [resolver-sim.pro-rata.effect-compilation-semantics :as semantics]
            [resolver-sim.pro-rata.effect-compilation-v3 :as compilation]
            [resolver-sim.pro-rata.target-map :as target-map]
            [resolver-sim.pro-rata.evm :as sut]))

(defn- root [n]
  (str "sha256:" (apply str (repeat 63 "0")) n))

(defn- fixture []
  (let [allocation-root (root "1")
        before-root (root "2")
        policy-root (root "3")
        after-root (root "4")
        configuration-root (root "5")
        application (sut/build-application
                     {:state-before-root before-root
                      :allocation-root allocation-root
                      :application-policy-root policy-root
                      :state-after-root after-root
                      :applications [{:account :escrow
                                      :direction :credit
                                      :amount-before 0
                                      :allocated 10
                                      :amount-after 10
                                      :disposition :held-credit}]})
        transition (assoc (sut/build-transition
                           {:state-before-root before-root
                            :allocation-root allocation-root
                            :application-policy-root policy-root
                            :application-root (:application/root application)
                            :state-after-root after-root})
                          :application application)
        provenance (sut/build-provenance
                    {:configuration-root configuration-root
                     :allocation-input-root (root "6")
                     :allocation-root allocation-root
                     :state-before-root before-root
                     :application-root (:application/root application)
                     :state-after-root after-root
                     :program-identity-root (root "7")
                     :statement-schema-root (root "8")
                     :asserted-provenance {:creation/provenance :out-of-band}})
        statement (sut/build-statement
                   {:allocation-root allocation-root
                    :transition-root (:transition/root transition)
                    :provenance-root (:provenance/root provenance)
                    :configuration-root configuration-root})]
    {:application application :transition transition
     :provenance provenance :statement statement}))

(deftest evm-statement-composes-derived-application-facts
  (let [{:keys [application transition provenance statement]} (fixture)]
    (is (sut/application-valid? application))
    (is (sut/transition-valid? transition application))
    (is (sut/provenance-valid? provenance))
    (is (sut/statement-valid? statement transition provenance))
    (is (= "pro-rata-evm-v1" (:schema-version statement)))
    (is (= (:state-after/root application) (:state-after/root transition)))))

(deftest state-after-substitution-invalidates-the-committed-composition
  (let [{:keys [application transition provenance statement]} (fixture)
        self-consistent-application
        (assoc application :state-after/root (root "9"))
        self-consistent-application
        (assoc self-consistent-application :application/root
               (sut/application-root self-consistent-application))]
    (testing "a new application hash is not an acceptable substitution"
      (is (sut/application-valid? self-consistent-application))
      (is (not (sut/transition-valid? transition self-consistent-application)))
      (is (sut/statement-valid? statement transition provenance)
          "the original composed statement remains valid only for its original application"))))

(deftest external-provenance-is-labelled-but-not-elevated-to-a-proved-fact
  (let [{:keys [provenance]} (fixture)]
    (is (= :out-of-band (get-in provenance [:asserted-provenance :creation/provenance])))
    (is (sut/provenance-valid? provenance))))

(defn- v2-fixture []
  (let [allocation (allocation/allocate
                    {:allocation/id :evm-v2/two-claim-60-40
                     :available 100N
                     :rows [{:row/id :claim/alice :requested 60N :weight 60N}
                            {:row/id :claim/bob :requested 40N :weight 40N}]})
        target-map (target-map/build-target-map
                    {:allocation-subjects-root (root "a")
                     :scope-root (root "b")
                     :mapping-profile-root (root "c")
                     :targets [{:allocation/subject-id :allocation/liquidity
                                :mapping/role :available :quantity/root (root "d")}
                               {:allocation/subject-id :claim/alice
                                :mapping/role :filled :quantity/root (root "e")}
                               {:allocation/subject-id :claim/alice
                                :mapping/role :outstanding :quantity/root (root "f")}
                               {:allocation/subject-id :claim/bob
                                :mapping/role :filled :quantity/root (root "7")}
                               {:allocation/subject-id :claim/bob
                                :mapping/role :outstanding :quantity/root (root "8")}]})
        compiler-semantics (semantics/build :all-active)
        compilation (compilation/compile
                     {:allocation allocation
                      :target-map target-map
                      :semantics compiler-semantics
                      :allocation-policy-root (root "9")})
        bodies {(:allocation/hash allocation) allocation
                (:target-map/root target-map) target-map
                (:effect-compilation-semantics/root compiler-semantics) compiler-semantics}
        resolve-body #(get bodies %)
        state-before {(root "d") 100N (root "e") 0N (root "f") 60N
                      (root "7") 0N (root "8") 40N}
        canonical-transition (effects/transition
                              state-before
                              (binding/recompute-effects compilation resolve-body))
        effect-compilation-binding (binding/build compilation canonical-transition)]
    {:state-before state-before
     :compilation compilation
     :binding effect-compilation-binding
     :resolve-body resolve-body
     :canonical-transition canonical-transition}))

(deftest evm-v2-derives-the-two-claim-60-40-post-state
  (let [{:keys [state-before compilation binding resolve-body canonical-transition]} (v2-fixture)
        statement (sut/build-statement-v2
                   {:state-before state-before
                    :effect-compilation compilation
                    :effect-compilation-binding binding
                    :resolve-body resolve-body
                    :configuration-root (root "1")})]
    (is (= "pro-rata-evm-v2" (:schema-version statement)))
    (is (= (:canonical-effect-transition/root canonical-transition)
           (:canonical-transition/root statement)))
    (is (= (:state-after/root canonical-transition) (:state-after/root statement)))
    (is (sut/statement-v2-valid? statement state-before compilation binding resolve-body))))

(deftest evm-v2-rejects-a-self-hashed-forged-post-state
  (let [{:keys [state-before compilation binding resolve-body]} (v2-fixture)
        statement (sut/build-statement-v2
                   {:state-before state-before
                    :effect-compilation compilation
                    :effect-compilation-binding binding
                    :resolve-body resolve-body
                    :configuration-root (root "1")})
        forged-base (assoc statement :state-after/root (root "2"))
        forged (assoc forged-base :pro-rata-evm-v2/root (sut/statement-v2-root forged-base))]
    (is (not= statement forged))
    (is (not (sut/statement-v2-valid? forged state-before compilation binding resolve-body))
        "V2 rejects a self-consistent post-state claim because it reconstructs the transition")))
