(ns resolver-sim.pro-rata.evm-test
  (:require [clojure.test :refer [deftest is testing]]
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
