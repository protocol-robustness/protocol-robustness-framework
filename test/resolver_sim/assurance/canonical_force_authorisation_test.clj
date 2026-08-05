(ns resolver-sim.assurance.canonical-force-authorisation-test
  "Phase A/B tests for the force-authorisation reconciliation layer (ADR-0007)."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.assurance.canonical-force-authorisation :as cfa]))

(deftest profile-conformance
  (is (= :canonical (cfa/classify-profile 3 2)))
  (is (= :canonical-unanimous (cfa/classify-profile 3 3)))
  (is (= :nonconforming-one-approval (cfa/classify-profile 3 1)))
  (is (= :noncanonical-quorum (cfa/classify-profile 5 3)))
  (let [p (cfa/declare-profile {:member-count 3 :threshold 2 :profile-id "p"})]
    (is (cfa/three-member-standard-conforming? p))
    (is (cfa/canonical-profile-conforming? p)))
  (let [p (cfa/declare-profile {:member-count 3 :threshold 3 :named-policy? true})]
    (is (cfa/three-member-standard-conforming? p))
    (is (not (cfa/canonical-profile-conforming? p))))
  (is (not (cfa/three-member-standard-conforming?
            (cfa/declare-profile {:member-count 3 :threshold 3})))))

(deftest policy-reconciliation-test
  (let [r (cfa/reconcile-policy {:member_count 3 :threshold 2 :policy_id "p"})]
    (is (= :canonical (:profile r)))
    (is (true? (:conforming? r))))
  (let [r (cfa/reconcile-policy {:member_count 5 :threshold 3})]
    (is (= :noncanonical-quorum (:profile r)))
    (is (false? (:conforming? r)))))

(deftest representation-classification-test
  (is (= :canonical-research
         (cfa/classify-representation "researcher-force-authorisation.v1")))
  (is (= :canonical-policy
         (cfa/classify-representation "force-authorisation-policy.v1")))
  (doseq [v ["force-auth-add-held.v2" "force-auth-lifecycle.v1"
             "force-auth-add-held-summary.v2"]]
    (is (= :legacy-evidence (cfa/classify-representation v))))
  (is (= :unknown (cfa/classify-representation nil)))
  (is (= :unknown (cfa/classify-representation ""))))

(deftest legacy-projection-one-way-test
  (let [p (cfa/legacy-as-canonical-projection
           {:authorization/id "fa-1" :authorization/status :active} nil)]
    (is (= :legacy-evidence (:representation/class p)))
    (is (false? (:projection/normative? p)))
    (is (false? (:canonical-emission-eligible? p)))
    (is (not (cfa/projection-normative? p)))
    (is (= "fa-1" (:authorization/id p))))
  (is (cfa/representation-emission-eligible? :canonical-research))
  (is (not (cfa/representation-emission-eligible? :legacy-evidence)))
  (is (not (cfa/representation-emission-eligible? :unknown)))
  (is (not (cfa/representation-emission-eligible? :canonical-policy))))

(deftest assess-representation-test
  (let [a (cfa/assess-representation "researcher-force-authorisation.v1")]
    (is (= :canonical-research (:representation/class a)))
    (is (true? (:canonical-model-compatible? a)))
    (is (true? (:canonical-emission-eligible? a)))
    (is (empty? (:missing-claims a))))
  (let [a (cfa/assess-representation "force-auth-add-held.v2")]
    (is (= :legacy-evidence (:representation/class a)))
    (is (false? (:canonical-model-compatible? a)))
    (is (false? (:canonical-emission-eligible? a)))
    (is (= :legacy-projection (:schema-change a)))
    (is (= :read-and-verify-only (:migration-action a)))
    (is (some #(= :member-count %) (:missing-claims a))))
  (let [a (cfa/assess-representation "weird-thing.v9")]
    (is (= :unknown (:representation/class a)))
    (is (= :fail-closed (:schema-change a)))
    (is (false? (:canonical-emission-eligible? a)))))

(deftest boundary-context-test
  (is (false? (cfa/boundary-decision?
               {:decision-boundary/kind :routing
                :selects-materially-incompatible-outcomes? true
                :uses-discretionary-judgement? true})))
  (is (false? (cfa/boundary-decision?
               {:decision-boundary/kind :initiation
                :selects-materially-incompatible-outcomes? true
                :uses-discretionary-judgement? true})))
  (is (true? (cfa/boundary-decision?
              {:decision-boundary/kind :contested-adjudication
               :selects-materially-incompatible-outcomes? true
               :uses-discretionary-judgement? true})))
  (is (= :deterministic-execution
         (cfa/declared-boundary-kind {:decision-boundary/kind :nonsense})))
  (is (false? (cfa/boundary-decision?
               {:decision-boundary/kind :nonsense
                :selects-materially-incompatible-outcomes? true
                :uses-discretionary-judgement? true})))
  (is (= :canonical-contested
         (cfa/decision-context {:decision-boundary/kind :contested-adjudication})))
  (is (= :deterministic-probabilistic
         (cfa/decision-context {:decision-boundary/kind :deterministic-execution})))
  (is (= :canonical-contested (cfa/decision-context {:contested? true})))
  (is (= :deterministic-probabilistic (cfa/decision-context {:probabilistic? true})))
  (is (= :historical-artifact-verification
         (cfa/decision-context {:historical-artifact? true})))
  (is (= :experimental-simulation (cfa/decision-context {:experimental? true})))
  (is (= :post-certification-execution
         (cfa/decision-context {:post-certification-execution? true})))
  (is (= :not-a-contested-boundary (cfa/decision-context {}))))

(deftest schema-versioning-test
  (let [r (cfa/schema-change-compatibility {})]
    (is (:schema-stable? r))
    (is (= :stable (:schema-bump r)))
    (is (= :preserve-version (:required-action r)))
    (is (empty? (:reasons r))))
  (let [r (cfa/schema-change-compatibility
           {:reader-interpretation-changed true
            :commits-policy-profile-id true
            :affected-schema "x.v2"})]
    (is (not (:schema-stable? r)))
    (is (= :bump-required (:schema-bump r)))
    (is (= :new-version (:required-action r)))
    (is (= #{:reader-interpretation-changed :commits-policy-profile-id} (:reasons r)))
    (is (= "x.v2" (:affected-schema r))))
  (is (= :bump-required
         (:schema-bump (cfa/schema-change-compatibility
                        {:certificate-controls-membership true}))))
  (is (= :bump-required
         (:schema-bump (cfa/schema-change-compatibility {:role-semantics-changed true}))))
  (is (= :bump-required
         (:schema-bump (cfa/schema-change-compatibility
                        {:whole-outcome-meaning-changed true}))))
  (let [r (cfa/schema-change-compatibility {:reader-interpretation-changed true}
                                          {:affected-schema "y.v3"})]
    (is (= "y.v3" (:affected-schema r)))))

(deftest cancellation-decision-v1
  (is (= "cancellation-decision.v1" cfa/cancellation-decision-schema))
  (testing "window classification (cutpoint)"
    (is (= :open (cfa/cancellation-window :proposed)))
    (is (= :open (cfa/cancellation-window :reservation-issued)))
    (is (= :closed (cfa/cancellation-window :consumed)))
    (is (= :closed (cfa/cancellation-window :outcome-released)))
    (is (= :closed (cfa/cancellation-window :consumption-receipt-terminal)))
    (is (= :invalid (cfa/cancellation-window nil)))
    (is (= :invalid (cfa/cancellation-window :not-a-real-state))))
  (testing "cancellation-possible? fails closed off-window"
    (is (true? (cfa/cancellation-possible? :reservation-issued)))
    (is (not (cfa/cancellation-possible? :consumed)))
    (is (not (cfa/cancellation-possible? nil))))
  (testing "canonical profile + open window -> cancellable"
    (let [r (cfa/classify-cancellation {:profile-id "cfa/2-3"} :proposed)]
      (is (:cancellation/profile-conforming? r))
      (is (= :canonical (:cancellation/profile r)))
      (is (= :open (:cancellation/window r)))
      (is (true? (:cancellation/possible? r)))
      (is (empty? (:cancellation/blocking-reasons r)))))
  (testing "after the cutpoint cancellation is not possible (machine-visible)"
    (let [r (cfa/classify-cancellation {:profile-id "cfa/2-3"} :consumed)]
      (is (:cancellation/profile-conforming? r))
      (is (= :closed (:cancellation/window r)))
      (is (false? (:cancellation/possible? r)))
      (is (not (empty? (:cancellation/blocking-reasons r))))))
  (testing "non-conforming profile blocks even inside the window"
    (let [r (cfa/classify-cancellation {:member-count 3 :threshold 1} :proposed)]
      (is (not (:cancellation/profile-conforming? r)))
      (is (= :nonconforming-one-approval (:cancellation/profile r)))
      (is (false? (:cancellation/possible? r)))))
  (testing "conformance requires an explicit declaration (D4)"
    (is (false? (:cancellation/possible?
                 (cfa/classify-cancellation {:member-count 3 :threshold 2}
                                            :proposed))))
    (is (true? (:cancellation/possible?
                (cfa/classify-cancellation {:member-count 3 :threshold 2
                                            :profile-id "cfa/2-3"}
                                           :proposed)))))
  (testing "bare 3-of-3 needs a declared profile to conform (D4)"
    (is (false? (:cancellation/possible?
                 (cfa/classify-cancellation {:member-count 3 :threshold 3}
                                            :proposed))))
    (is (true? (:cancellation/possible?
                (cfa/classify-cancellation {:member-count 3 :threshold 3
                                            :profile-id "p/3-3"}
                                           :proposed)))))
  (testing "unrecognised target state fails closed even with a conforming profile"
    (let [r (cfa/classify-cancellation {:profile-id "cfa/2-3"} :unrecognised)]
      (is (:cancellation/profile-conforming? r))
      (is (= :invalid (:cancellation/window r)))
      (is (false? (:cancellation/possible? r))))))

(deftest cancellation-window-primitive-v1
  (is (= "cancellation-window.v1" cfa/cancellation-window-v1-schema))
  (testing "generic classifier owns only window mechanics (explicit open-states)"
    (let [w (cfa/lifecycle-window-profile
             {:profile/id :t/primitive
              :profile/version 1
              :valid-states #{:a :b :done}
              :open-states #{:a :b}
              :irreversible-states #{:done}
              :blocking-reason-by-state {:done :mapped-reason}})]
      (is (= :open (:window/state (cfa/classify-lifecycle-window w :a))))
      (is (true? (:window/possible? (cfa/classify-lifecycle-window w :b))))
      (is (= :closed (:window/state (cfa/classify-lifecycle-window w :done))))
      (is (= [:mapped-reason]
             (:window/blocking-reasons (cfa/classify-lifecycle-window w :done))))
      (is (= :invalid (:window/state (cfa/classify-lifecycle-window w :nope))))
      (is (= [:unknown-target-state]
             (:window/blocking-reasons (cfa/classify-lifecycle-window w :nope))))
      (is (= :invalid (:window/state (cfa/classify-lifecycle-window w nil))))))
  (testing "no 2-of-3 semantics in the primitive"
    (let [w (cfa/lifecycle-window-profile
             {:profile/id :t/primitive
              :profile/version 1
              :valid-states #{:a :done}
              :open-states #{:a}
              :irreversible-states #{:done}
              :blocking-reason-by-state {:done :done}})]
      (is (not (contains? (cfa/classify-lifecycle-window w :a) :member-count)))
      (is (not (contains? (cfa/classify-lifecycle-window w :a) :threshold))))))

(deftest lifecycle-profile-validation-contracts
  (testing "contract 4: built profiles validate"
    (is (:valid? (cfa/validate-lifecycle-profile cfa/force-authorisation-window)))
    (is (:valid? (cfa/validate-lifecycle-profile cfa/probabilistic-allocation-window))))
  (testing "contract 4: structural failures are surfaced"
    (is (contains? (set (:errors (cfa/validate-lifecycle-profile
                                  (cfa/lifecycle-window-profile
                                   {:valid-states #{:a :done}
                                    :open-states #{:a}
                                    :irreversible-states #{:done}
                                    :blocking-reason-by-state {:done :done}}))))
                   :missing-profile-id))
    (is (contains? (set (:errors (cfa/validate-lifecycle-profile
                                  (cfa/lifecycle-window-profile
                                   {:profile/id :t/x
                                    :valid-states #{:a :done}
                                    :open-states #{:a}
                                    :irreversible-states #{:done}
                                    :blocking-reason-by-state {:done :done}}))))
                   :missing-profile-version))
    (is (contains? (set (:errors (cfa/validate-lifecycle-profile
                                  (cfa/lifecycle-window-profile
                                   {:profile/id :t/x :profile/version 1
                                    :valid-states #{:a :done}
                                    :open-states #{:a :done}
                                    :irreversible-states #{:done}
                                    :blocking-reason-by-state {:done :done}}))))
                   :open-irreversible-overlap))
    (is (contains? (set (:errors (cfa/validate-lifecycle-profile
                                  (cfa/lifecycle-window-profile
                                   {:profile/id :t/x :profile/version 1
                                    :valid-states #{:a}
                                    :open-states #{:a}
                                    :irreversible-states #{:done}}))))
                   :irreversible-state-not-valid))
    (is (contains? (set (:errors (cfa/validate-lifecycle-profile
                                  (cfa/lifecycle-window-profile
                                   {:profile/id :t/x :profile/version 1
                                    :valid-states #{:a :done}
                                    :open-states #{:a}
                                    :irreversible-states #{:done}}))))
                   :missing-blocking-reason)))
  (testing "contract 3: the cutpoint state is closed, not last-open"
    (is (= :closed (:window/state
                    (cfa/classify-lifecycle-window cfa/probabilistic-allocation-window
                                                   :randomness-requested))))
    (is (= :closed (:window/state
                    (cfa/classify-lifecycle-window cfa/force-authorisation-window
                                                   :consumed)))))
  (testing "contract 2: :reservation-issued remains cancellable (Option A)"
    (is (= :open (:window/state
                  (cfa/classify-lifecycle-window cfa/force-authorisation-window
                                                 :reservation-issued)))))
  (testing "contract 5: monotonic window (closed -> open forbidden)"
    (is (:valid? (cfa/validate-lifecycle-monotonicity
                  (assoc cfa/probabilistic-allocation-window
                         :transitions
                         {:randomness-requested #{:randomness-fulfilled}
                          :randomness-fulfilled #{:result-accepted}
                          :result-accepted #{}}))))
    (let [bad (cfa/validate-lifecycle-monotonicity
               (assoc cfa/probabilistic-allocation-window
                      :transitions
                      {:randomness-requested #{:allocation-committed}}))]
      (is (not (:valid? bad)))
      (is (= [[:randomness-requested :allocation-committed]] (:violations bad))))))

(deftest cancellation-taxonomy-and-binding-contracts
  (testing "contract 1: only explicit cancellation of a valid authorisation decides"
    (is (true? (cfa/cancellation-decision-required?
                :decide-cancel-valid-authorisation)))
    (doseq [op [:submit-cancel-request :expire-at-deadline
                :deterministic-invalidation :reject-post-cutpoint-cancellation
                :execute-certified-cancellation :apply-deterministic-fallback]]
      (is (not (cfa/cancellation-decision-required? op)))))
  (testing "contract 6: cancellation and the cutpoint transition contend on one key"
    (is (= {:target/id "alloc/9"
            :lifecycle/profile-id :prf.lifecycle-window/probabilistic-allocation
            :lifecycle/profile-version 1}
           (cfa/cancellation-conflict-key "alloc/9"
                                          cfa/probabilistic-allocation-window))))
  (testing "contract 7: whole-outcome binding over the exact target snapshot"
    (let [complete {:target/id "alloc/9" :target/hash "sha256:0"
                    :lifecycle/profile-id :x :lifecycle/profile-version 1
                    :target/state-evidence-root "sha256:1"
                    :cancellation/action :cancel :cancellation/effects :prevent
                    :cancellation/reason :mistake :decision/profile-id "2-3"
                    :policy/instance "pol/1" :decision/validity-window "t0..t1"
                    :conflict/consumption-key "ck"}]
      (is (cfa/cancellation-binding-complete? complete))
      (is (empty? (cfa/missing-cancellation-binding-fields complete)))
      (is (not (cfa/cancellation-binding-complete? (dissoc complete :target/hash))))
      (is (= [:target/hash] (cfa/missing-cancellation-binding-fields
                             (dissoc complete :target/hash)))))))

(deftest probabilistic-allocation-cancellation-cutpoint
  (testing "allocation state mapping"
    (is (= :open (:window/state
                  (cfa/classify-lifecycle-window cfa/probabilistic-allocation-window
                                                 :allocation-committed))))
    (doseq [st [:randomness-requested :randomness-fulfilled :result-proposed
                :result-accepted :claim-consumption-started]]
      (is (= :closed (:window/state
                      (cfa/classify-lifecycle-window cfa/probabilistic-allocation-window
                                                     st))))
      (is (false? (:window/possible?
                   (cfa/classify-lifecycle-window cfa/probabilistic-allocation-window
                                                  st))))))
  (testing "randomness request is the authoritative cutpoint"
    (let [r (cfa/classify-lifecycle-window cfa/probabilistic-allocation-window
                                           :randomness-requested)]
      (is (= :closed (:window/state r)))
      (is (= [:authoritative-randomness-requested]
             (:window/blocking-reasons r)))))
  (testing "contract 8: replay path recomputes from committed evidence"
    (let [evidence {:target-evidence {:allocation/id 9 :phase :randomness-requested}
                    :lifecycle-profile cfa/probabilistic-allocation-window
                    :domain-projection #(-> % :phase)
                    :decision-opts {:profile-id "alloc/2-3"}}
          a (cfa/cancellation-window-assertion evidence)]
      (is (= :passing (:status a)))
      (is (= :independent-replay (:assurance a)))
      (is (= "cancellation-decision.v1" (:decision-schema a)))
      (is (= :closed (:cancellation/window a)))
      (is (false? (:cancellation/possible? a)))
      (is (= [:authoritative-randomness-requested] (:blocking-reasons a)))
      (is (= :randomness-requested (:evidence/derived-state a)))
      (is (= {:assertion/id :cancellation/window-respected
              :status :passing
              :assurance :independent-replay
              :decision-schema "cancellation-decision.v1"
              :cancellation/window :closed
              :cancellation/possible? false
              :blocking-reasons [:authoritative-randomness-requested]
              :evidence/derived-state :randomness-requested}
             a))))
  (testing "contract 8: supplied classification is only a structural check"
    (let [r (cfa/classify-cancellation
             {:profile-id "alloc/2-3" :window cfa/probabilistic-allocation-window}
             :randomness-requested)
          a (cfa/cancellation-window-assertion r)]
      (is (= :passing (:status a)))
      (is (= :structural-check (:assurance a)))
      (is (not (contains? a :evidence/derived-state)))
      (is (= :closed (:cancellation/window a)))
      (is (= [:authoritative-randomness-requested] (:blocking-reasons a)))))
  (testing "post-cutpoint cancellation is refused whatever the decision profile"
    (let [r (cfa/classify-cancellation
             {:profile-id "alloc/2-3" :window cfa/probabilistic-allocation-window}
             :randomness-fulfilled)]
      (is (:cancellation/profile-conforming? r))
      (is (false? (:cancellation/possible? r)))))
  (testing "allocation states are never force-authorisation states directly"
    (is (= :invalid (:window/state
                     (cfa/classify-lifecycle-window cfa/probabilistic-allocation-window
                                                    :consumed))))
    (is (= :invalid (:window/state
                     (cfa/classify-lifecycle-window cfa/force-authorisation-window
                                                    :randomness-requested))))))
