(ns resolver-sim.benchmark.curiosity-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.curiosity :as curiosity]
            [resolver-sim.benchmark.governed-authority-state :as authority-state]
            [resolver-sim.benchmark.packs.partial-fill.pro-rata-execution-evidence :as execution-evidence]
            [resolver-sim.benchmark.outcome-manifest :as outcome-manifest]))

(defn- profile [operational?]
  (execution-evidence/build-pro-rata-execution-evidence-v2
   {:benchmark-content-root "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    :model-root "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    :outcome-manifest
    (outcome-manifest/build-manifest
     {:benchmark/content-root "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
      :benchmark/model-root "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
      :benchmark/evaluation-policy-root "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
      :execution/status :completed
      :results/operational {:conservation (if operational? :pass :fail)
                            :quota-bounded (if operational? :pass :fail)
                            :current-amount-write-back (if operational? :pass :fail)
                            :authoritative-application :pass}})
    :allocation-evidence-hash "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
    :application-evidence-hash "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
    :theorem-outcomes []
    :conclusions []}))

(deftest current-configuration-root-is-read-only-and-not-caller-asserted
  (let [store-state (atom {:unchanged true})
        current-result {:resolved? true
                        :context {:chain-configuration/root "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                                  :resolved-review-authority-context/root "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}}
        result (with-redefs [authority-state/resolve-governed-authority-context
                             (fn [store basis]
                               (is (= store-state store))
                               (is (= :real-basis basis))
                               current-result)]
                 (curiosity/resolve-curiosity
                  :currently-authorized-chain-configuration-root
                  {:authority-store store-state
                   :resolution-basis :real-basis}))]
    (is (= :true (:curiosity/status result)))
    (is (= (get-in current-result [:context :chain-configuration/root])
           (:curiosity/value result)))
    (is (false? (:curiosity/authority-granted? result)))
    (is (= {:unchanged true} @store-state))))

(deftest stale-current-admission-is-not-reported-as-current
  (with-redefs [authority-state/resolve-governed-authority-context
                (fn [_ _] {:resolved? false :reason :state-not-at-required-head})]
    (let [result (curiosity/resolve-curiosity
                  :currently-authorized-chain-configuration-root
                  {:authority-store (atom {}) :resolution-basis {}})]
      (is (= :stale (:curiosity/status result)))
      (is (nil? (:curiosity/value result)))
      (is (false? (:curiosity/authority-granted? result))))))

(deftest write-back-curiosity-distinguishes-true-false-absent-and-invalid-evidence
  (testing "authenticated V2 evidence exposes its exact boolean observation"
    (is (= :true (:curiosity/status
                  (curiosity/resolve-curiosity
                   :current-write-back-operationally-verified
                   {:execution-evidence-profile (profile true)}))))
    (is (= :false (:curiosity/status
                   (curiosity/resolve-curiosity
                    :current-write-back-operationally-verified
                    {:execution-evidence-profile (profile false)})))))
  (testing "a valid V1 profile does not define the V2 operational curiosity"
    (let [v1 (execution-evidence/build-pro-rata-execution-evidence
              {:benchmark-content-root "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
               :model-root "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
               :outcome-manifest (outcome-manifest/build-manifest
                                  {:benchmark/content-root "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                                   :benchmark/model-root "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                                   :benchmark/evaluation-policy-root "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
                                   :execution/status :completed
                                   :results/operational {}})
               :allocation-evidence-hash "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
               :application-evidence-hash "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
               :theorem-outcomes [] :conclusions []})]
      (is (= :absent (:curiosity/status
                      (curiosity/resolve-curiosity
                       :current-write-back-operationally-verified
                       {:execution-evidence-profile v1}))))))
  (testing "tampered evidence is rejected rather than read"
    (let [invalid (assoc (profile true) :evidence-profile/hash "sha256:0000000000000000000000000000000000000000000000000000000000000000")]
      (is (= :invalid-evidence (:curiosity/status
                                (curiosity/resolve-curiosity
                                 :current-write-back-operationally-verified
                                 {:execution-evidence-profile invalid})))))))

(deftest unknown-curiosities-fail-closed
  (let [result (curiosity/resolve-curiosity :not/a-curiosity {})]
    (is (= :unknown-curiosity (:curiosity/status result)))
    (is (= :unsupported-curiosity (:curiosity/reason result)))
    (is (false? (:curiosity/authority-granted? result)))))

(deftest use-case-requirements-are-explicit-and-closed
  (is (= #{:currently-authorized-chain-configuration-root}
         (get curiosity/use-case-required-curiosities :resubmission/new-chain)))
  (is (= #{:currently-authorized-chain-configuration-root
           :current-write-back-operationally-verified}
         (get curiosity/use-case-required-curiosities :governed-authority/current-admission))))
