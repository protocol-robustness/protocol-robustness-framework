(ns resolver-sim.benchmark.curiosity-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.assurance.governed-authority-consumer :as governed-authority-consumer]
            [resolver-sim.benchmark.curiosity :as curiosity]
            [resolver-sim.benchmark.governed-authority-resolution :as resolution]
            [resolver-sim.benchmark.governed-authority-state :as authority-state]
            [resolver-sim.benchmark.packs.partial-fill.pro-rata-execution-evidence :as execution-evidence]
            [resolver-sim.benchmark.outcome-manifest :as outcome-manifest]
            [resolver-sim.benchmark.researcher-force-authorisation :as rfa]))

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

(deftest bootstrap-declarations-are-transitional-compatibility-data
  (is (= #{:currently-authorized-chain-configuration-root}
         (get curiosity/bootstrap-use-case-required-curiosities :resubmission/new-chain)))
  (is (= #{:currently-authorized-chain-configuration-root
           :current-write-back-operationally-verified}
         (get curiosity/bootstrap-use-case-required-curiosities :governed-authority/current-admission))))

(deftest dual-declaration-disagreement-guard-fails-closed
  (let [bootstrap {:acme/a #{:currently-authorized-chain-configuration-root}}
        external {:acme/a #{:currently-authorized-chain-configuration-root}}]
    (testing "neither present → no curiosity requirements"
      (is (= #{} (curiosity/resolve-use-case-required-curiosities {} {} :acme/unknown))))
    (testing "bootstrap only → bootstrap requirements"
      (is (= #{:currently-authorized-chain-configuration-root}
             (curiosity/resolve-use-case-required-curiosities bootstrap {} :acme/a))))
    (testing "external only → external requirements"
      (is (= #{:currently-authorized-chain-configuration-root}
             (curiosity/resolve-use-case-required-curiosities {} external :acme/a))))
    (testing "both and equal → committed/external requirements"
      (is (= #{:currently-authorized-chain-configuration-root}
             (curiosity/resolve-use-case-required-curiosities bootstrap external :acme/a))))
    (testing "both and unequal → fail closed (no union, no precedence, no fallback)"
      (let [drifted {:acme/a #{:current-write-back-operationally-verified}}
            error (try (curiosity/resolve-use-case-required-curiosities bootstrap drifted :acme/a)
                       nil
                       (catch clojure.lang.ExceptionInfo e e))]
        (is (some? error))
        (is (= :curiosity-requirements/disagreement
               (get-in (ex-data error) [:error/code])))
        (is (= :acme/a (get-in (ex-data error) [:use-case/id])))
        (is (= #{:currently-authorized-chain-configuration-root}
               (get-in (ex-data error) [:bootstrap/required-curiosities])))
        (is (= #{:current-write-back-operationally-verified}
               (get-in (ex-data error) [:declared/required-curiosities])))))))

(deftest migration-equivalence-proves-bootstrap-removal-is-semantics-preserving
  (testing "temporary: documents the eventual bootstrap-curiosities migration path"
    (let [bootstrap {:governed-authority/current-admission
                     #{:currently-authorized-chain-configuration-root
                       :current-write-back-operationally-verified}}
          external {:governed-authority/current-admission
                    #{:currently-authorized-chain-configuration-root
                      :current-write-back-operationally-verified}}
          committed (get external :governed-authority/current-admission)]
      (testing "bootstrap declaration exists + external declares same set → accepted"
        (is (= committed
               (curiosity/resolve-use-case-required-curiosities
                bootstrap external :governed-authority/current-admission))))
      (testing "external definition changes one curiosity → disagreement"
        (let [drifted (assoc-in external [:governed-authority/current-admission]
                                #{:currently-authorized-chain-configuration-root})]
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"disagree between bootstrap and committed"
               (curiosity/resolve-use-case-required-curiosities
                bootstrap drifted :governed-authority/current-admission)))))
      (testing "bootstrap entry removed + external unchanged → same resolved requirements"
        (is (= committed
               (curiosity/resolve-use-case-required-curiosities
                (dissoc bootstrap :governed-authority/current-admission)
                external
                :governed-authority/current-admission)))
        (is (= committed
               (curiosity/resolve-use-case-required-curiosities
                {} external :governed-authority/current-admission)))))))

(deftest authority-laundering-results-cannot-occupy-authoritative-slots
  (let [laundered {:curiosity/id :currently-authorized-chain-configuration-root
                   :curiosity/status :resolved
                   :curiosity/value true
                   :curiosity/authority-granted? false}]
    (testing "governed authority context slot rejects by closed shape"
      (is (false? (:valid? (resolution/validate-resolved-context laundered)))))
    (testing "authorization evidence slot rejects by structural validation"
      (is (false? (:valid? (rfa/validate-authorisation laundered)))))
    (testing "authority fence slot: consumer finalization requires a store-issued fence"
      (let [outcome (governed-authority-consumer/finalise-governed-authority-current!
                     nil laundered nil nil nil)]
        (is (false? (:finalised? outcome)))
        (is (contains? #{:authority-not-authorised :missing-authority-fence}
                       (:reason outcome)))))
    (testing "native authorization artifact: the C4f consumer boundary also rejects a non-fence"
      (let [outcome (governed-authority-consumer/finalise-governed-authority-current-under-authoritative-configuration!
                     nil laundered nil nil nil)]
        (is (false? (:finalised? outcome)))
        (is (contains? #{:authority-not-authorised :missing-authority-fence}
                       (:reason outcome)))))))
