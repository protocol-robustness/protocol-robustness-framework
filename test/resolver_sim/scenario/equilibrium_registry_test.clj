(ns resolver-sim.scenario.equilibrium-registry-test
  "Registry-driven executable dispatch for game-theoretic validators.

   Verifies that adding a validator is a registration act (descriptor +
   executable + strategic-claim), never a dispatch-logic edit, and that the
   dispatch fails closed on missing executable, incompatible horizon,
   malformed descriptor, and descriptor substitution."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.scenario.equilibrium :as eq]
            [resolver-sim.scenario.equilibrium-result :as eq-result]
            [resolver-sim.benchmark.strategic-claim-validation :as scv]
            [resolver-sim.validation.validator-descriptor :as vd]
            [resolver-sim.validation.strategic-registry :as sr]))

(def synthetic-descriptor
  {:validator/schema vd/validator-schema
   :validator/id :synthetic/external-honesty-bonus
   :validator/version 1
   :validator/kind :mechanism-property
   :validator/validation-class :validation.class/payoff-property
   :validator/execution-model {:horizon :single-trace
                               :state-model :deterministic
                               :history-required? false}
   :validator/epistemic-contract {:claim-strength :single-trace-proxy
                                  :universal-claim? false
                                  :falsification? true
                                  :limitations [:single-trace]}
   :validator/origin :benchmark})

(defn synthetic-executable
  "External validator executable: passes when no adversarial attack succeeded.
   Carries its own validation-class (the dispatch does not re-decorate)."
  [{:keys [metrics]}]
  (let [successes (get-in metrics [:attack-successes] 0)]
    (if (pos? successes)
      (eq-result/fail-result :synthetic/external-honesty-bonus :single-trace-metric-proxy
                             {:attack-successes successes} {:attack-successes 0}
                             [{:metric :attack-successes :observed successes}]
                             :validation-class :validation.class/payoff-property)
      (eq-result/pass-result :synthetic/external-honesty-bonus :single-trace-metric-proxy
                             {:attack-successes 0} "no attack succeeded"
                             :validation-class :validation.class/payoff-property))))

(def synthetic-registry
  "Registry with the synthetic external validator composed on the builtin."
  (eq/compose-validator-registry
   :sources [{:origin :benchmark :entries [synthetic-descriptor]}]
   :executables {:synthetic/external-honesty-bonus synthetic-executable}))

;; ---------------------------------------------------------------------------
;; Descriptor registration + executable registration + strategic-claim
;; registration is sufficient for normal evaluation
;; ---------------------------------------------------------------------------

(deftest synthetic-validator-evaluates-via-registration-only
  (testing "a synthetic validator with descriptor + executable + claim registered
            evaluates normally with zero dispatch-code changes"
    (let [proj {:metrics {:attack-successes 0 :attack-attempts 1}}
          result (get (eq/evaluate-mechanism-properties
                       [:synthetic/external-honesty-bonus] proj {} {:registry synthetic-registry})
                      :synthetic/external-honesty-bonus)]
      (is (= :pass (:status result)))
      (is (= :single-trace-metric-proxy (:basis result)))
      (is (= :validation.class/payoff-property (:validation-class result))))))

(deftest synthetic-validator-failure-propagates
  (testing "the synthetic executable's fail result flows through dispatch"
    (let [proj {:metrics {:attack-successes 2 :attack-attempts 2}}
          result (get (eq/evaluate-mechanism-properties
                       [:synthetic/external-honesty-bonus] proj {} {:registry synthetic-registry})
                      :synthetic/external-honesty-bonus)]
      (is (= :fail (:status result)))
      (is (= :property-violated (:reason result))))))

(deftest synthetic-validator-horizon-gates
  (testing "the descriptor's declared horizon is honoured even for a synthetic validator"
    (let [multi-epoch-desc (assoc synthetic-descriptor
                                  :validator/execution-model {:horizon :multi-epoch
                                                              :state-model :stochastic
                                                              :history-required? true})
          reg (eq/compose-validator-registry
               :sources [{:origin :benchmark :entries [multi-epoch-desc]}]
               :executables {:synthetic/external-honesty-bonus synthetic-executable})
          result (get (eq/evaluate-mechanism-properties
                       [:synthetic/external-honesty-bonus] {} {} {:registry reg})
                      :synthetic/external-honesty-bonus)]
      (is (= :inconclusive (:status result)))
      (is (= :multi-epoch-required (:basis result))))))

(deftest synthetic-validator-registers-as-strategic-claim
  (testing "a strategic claim can reference the registered synthetic validator"
    (let [claim {:claim/id :claim/synthetic-external
                 :claim/title "Synthetic external validator claim"
                 :claim/description "Proves claim registration is sufficient"
                 :claim/interpretation "Pass means the external validator ran."
                 :mechanism-levels [:allocation/partial-fill]
                 :validator-ids [:synthetic/external-honesty-bonus]
                 :required-threat-tags #{"shortfall"}
                 :match-dimensions #{:allocation/partial-fill}}
          registry (scv/build-strategic-claim-registry
                    :sources [{:origin :application :entries [claim]}])]
      (is (= claim (scv/resolve-strategic-claim registry :claim/synthetic-external)))
      (is (re-matches #"[0-9a-f]{64}" (:root registry))))))

;; ---------------------------------------------------------------------------
;; Fail-closed: missing executable
;; ---------------------------------------------------------------------------

(deftest missing-executable-fails-closed
  (testing "a descriptor registered without an executable throws at dispatch"
    (let [reg (eq/compose-validator-registry
               :sources [{:origin :benchmark :entries [synthetic-descriptor]}]
               :executables {})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"registered without executable"
           (eq/evaluate-mechanism-properties [:synthetic/external-honesty-bonus] {} {} {:registry reg}))))))

(deftest top-level-evaluate-equilibrium-is-registry-driven
  (testing "evaluate-equilibrium composes protocol descriptors + executables into
            a registry and dispatches through it (no fixed validator ids)"
    (let [theory {:mechanism-properties [:synthetic/external-honesty-bonus]}
          result {:metrics {:attack-successes 0 :attack-attempts 1}
                  :protocol (reify
                              resolver-sim.protocols.protocol/SimulationAdapter
                              (protocol-id [_] "synthetic")
                              (init-world [_ scenario] scenario)
                              (build-execution-context [_ _ _] {:agent-index {}})
                              (dispatch-action [_ _ w _] {:ok true :world w})
                              (check-invariants-single [_ _] {:ok? true :violations nil})
                              (check-invariants-transition [_ _ _] {:ok? true :violations nil})
                              (world-snapshot [_ _] {})
                              (available-actions [_ _ _] [])
                              (resolve-id-alias [_ e _] {:ok true :event e})
                              (created-id [_ _ _] nil)
                              (open-entities [_ _] [])
                              (project-state [_ _ _] nil)
                              resolver-sim.protocols.protocol/AnalysisModule
                              (compute-projection [_ w] [w nil])
                              (classify-transition [_ _ _] {})
                              (trace-projection [_ result] {:metrics (:metrics result)})
                              (io-projection [_ _ _] nil)
                              (mechanism-property-validators [_]
                                {:synthetic/external-honesty-bonus synthetic-executable})
                              (equilibrium-concept-validators [_] {})
                              (reference-model [_ _] nil)
                              resolver-sim.protocols.protocol/ValidatorDescriptorCatalog
                              (validator-descriptors [_] [synthetic-descriptor]))}
          eq-out (eq/evaluate-equilibrium theory result)]
      (is (= :pass (get-in eq-out [:mechanism-results :synthetic/external-honesty-bonus :status])))
      (is (= :pass (:mechanism-status eq-out))))))

(deftest top-level-evaluate-equilibrium-fails-closed-on-missing-executable
  (testing "evaluate-equilibrium fails closed when a protocol exposes a descriptor
            without the matching executable"
    (let [theory {:mechanism-properties [:synthetic/external-honesty-bonus]}
          result {:metrics {:attack-successes 0 :attack-attempts 1}
                  :protocol (reify
                              resolver-sim.protocols.protocol/SimulationAdapter
                              (protocol-id [_] "synthetic")
                              (init-world [_ scenario] scenario)
                              (build-execution-context [_ _ _] {:agent-index {}})
                              (dispatch-action [_ _ w _] {:ok true :world w})
                              (check-invariants-single [_ _] {:ok? true :violations nil})
                              (check-invariants-transition [_ _ _] {:ok? true :violations nil})
                              (world-snapshot [_ _] {})
                              (available-actions [_ _ _] [])
                              (resolve-id-alias [_ e _] {:ok true :event e})
                              (created-id [_ _ _] nil)
                              (open-entities [_ _] [])
                              (project-state [_ _ _] nil)
                              resolver-sim.protocols.protocol/AnalysisModule
                              (compute-projection [_ w] [w nil])
                              (classify-transition [_ _ _] {})
                              (trace-projection [_ result] {:metrics (:metrics result)})
                              (io-projection [_ _ _] nil)
                              (mechanism-property-validators [_] {})
                              (equilibrium-concept-validators [_] {})
                              (reference-model [_ _] nil)
                              resolver-sim.protocols.protocol/ValidatorDescriptorCatalog
                              (validator-descriptors [_] [synthetic-descriptor]))}]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"registered without executable"
           (eq/evaluate-equilibrium theory result))))))

;; ---------------------------------------------------------------------------
;; Fail-closed: incompatible horizon
;; ---------------------------------------------------------------------------

(deftest incompatible-horizon-fails-closed
  (testing "a descriptor declaring :multi-epoch horizon is not run on single-trace
            evidence; it reports :multi-epoch-required instead"
    (let [multi-epoch-desc (assoc synthetic-descriptor
                                  :validator/execution-model {:horizon :multi-epoch
                                                              :state-model :stochastic
                                                              :history-required? true})
          reg (eq/compose-validator-registry
               :sources [{:origin :benchmark :entries [multi-epoch-desc]}]
               :executables {:synthetic/external-honesty-bonus synthetic-executable})
          result (get (eq/evaluate-mechanism-properties
                       [:synthetic/external-honesty-bonus] {:metrics {:attack-successes 0}}
                       {} {:registry reg})
                      :synthetic/external-honesty-bonus)]
      (is (= :inconclusive (:status result)))
      (is (= :multi-epoch-required (:basis result)))
      (is (= :multi-epoch-required (:reason result))))))

;; ---------------------------------------------------------------------------
;; Fail-closed: malformed descriptor
;; ---------------------------------------------------------------------------

(deftest malformed-descriptor-fails-closed-at-registration
  (testing "a descriptor missing a compulsory epistemic contract is rejected at
            registry build time"
    (let [malformed (dissoc synthetic-descriptor :validator/epistemic-contract)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Invalid game-theoretic validator descriptor"
           (sr/build-validator-registry
            :sources [{:origin :benchmark :entries [malformed]}]
            :executables {}))))))

;; ---------------------------------------------------------------------------
;; Fail-closed: descriptor substitution
;; ---------------------------------------------------------------------------

(deftest descriptor-substitution-fails-closed
  (testing "registering the same validator id with a different committed identity
            (e.g. a substituted descriptor) is rejected"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Duplicate validator id with different identity"
         (sr/build-validator-registry
          :sources [{:origin :a :entries [synthetic-descriptor]}
                    {:origin :b :entries [(assoc synthetic-descriptor :validator/version 2)]}]
          :executables {})))))

;; ---------------------------------------------------------------------------
;; Semantic-reconstruction / economic-evaluation boundary + grim-trigger scope
;; ---------------------------------------------------------------------------

(deftest grim-trigger-remains-model-specific
  (testing "the grim-trigger / folk-theorem evaluator is NOT generalized into a
            Folk-theorem validator and is not registered in the equilibrium
            dispatch registry"
    (is (nil? (eq/resolve-validator-descriptor eq/builtin-validator-registry
                                               :repeated-game/grim-trigger-deterrence)))
    (let [result (get (eq/evaluate-equilibrium-concepts
                       [:repeated-game/grim-trigger-deterrence] {})
                      :repeated-game/grim-trigger-deterrence)]
      (is (= :inconclusive (:status result)))
      (is (= :unsupported-concept (:reason result)))
      "single-trace dispatcher does not claim a Folk-theorem result")))

(deftest builtin-registry-has-no-general-folk-theorem-validator
  (testing "no descriptor in the builtin registry is a Folk-theorem validator"
    (is (not-any? (fn [id]
                    (or (= :folk-theorem-cooperation-region id)
                        (= :repeated-game/grim-trigger-deterrence id)))
                  (eq/registered-validator-ids eq/builtin-validator-registry)))))