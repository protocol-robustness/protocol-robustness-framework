(ns resolver-sim.validation.validator-descriptor-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.validation.classes :as classes]
            [resolver-sim.validation.validator-descriptor :as vd]))

(def valid-descriptor
  {:validator/schema vd/validator-schema
   :validator/id :test/no-profitable-withholding
   :validator/version 1
   :validator/kind :deviation-resistance
   :validator/validation-class :validation.class/deviation-resistance
   :validator/input-schema-root nil
   :validator/output-schema-root nil
   :validator/epistemic-contract {:claim-strength :bounded-falsification
                                  :universal-claim? false
                                  :falsification? true
                                  :limitations [:bounded-domain
                                                :no-equilibrium-proof]}
   :validator/dependencies [{:kind :utility-model :id :sew/claimant-utility-v1}
                            {:kind :deviation-contract :id :partial-fill/claimant-split-merge-sybil}]
   :validator/execution-model {:horizon :single-trace
                               :state-model :deterministic
                               :history-required? false}
   :validator/origin :protocol})

(deftest descriptor-schema-is-stable
  (testing "schema identifier is the canonical prf v1"
    (is (= :prf/game-theoretic-validator.v1 vd/validator-schema))))

(deftest well-formed-descriptor-passes-validation
  (is (vd/valid-descriptor? valid-descriptor))
  (is (empty? (vd/validate-descriptor valid-descriptor))))

(deftest descriptor-requires-compulsory-epistemic-contract
  (testing "an epistemic contract is required — no silent claim-strength inflation"
    (is (not (empty? (vd/validate-descriptor (dissoc valid-descriptor :validator/epistemic-contract))))))
  (testing "an epistemic contract missing its required fields fails"
    (is (not (empty? (vd/validate-descriptor
                      (assoc valid-descriptor
                             :validator/epistemic-contract {:claim-strength :x})))))))

(deftest validation-class-ladder-is-framework-governed
  (testing "a protocol cannot insert a new class into the framework ladder"
    (let [errors (vd/validate-descriptor
                  (assoc valid-descriptor
                         :validator/validation-class :validation.class/super-equilibrium))]
      (is (some #(re-find #"framework-governed" %) errors))))
  (testing "every framework class is accepted"
    (doseq [c classes/class-order]
      (is (empty? (vd/validate-descriptor (assoc valid-descriptor :validator/validation-class c)))))))

(deftest closed-shape-rejects-unknown-keys
  (is (some #(re-find #"unknown descriptor keys" %)
            (vd/validate-descriptor (assoc valid-descriptor :validator/bogus 1)))))

(deftest descriptor-kind-and-origin-are-constrained
  (is (some #(re-find #":validator/kind" %) (vd/validate-descriptor (assoc valid-descriptor :validator/kind :bogus))))
  (is (some #(re-find #":validator/origin" %) (vd/validate-descriptor (assoc valid-descriptor :validator/origin :bogus)))))

(deftest descriptor-root-is-deterministic-and-order-independent
  (let [root (vd/descriptor-root valid-descriptor)
        reordered (into (sorted-map-by (fn [a b] (compare (str b) (str a))))
                        valid-descriptor)]
    (is (re-matches #"[0-9a-f]{64}" root))
    (is (= root (vd/descriptor-root reordered)))))

(deftest descriptor-root-excludes-self-referential-root
  (testing "with-root derives the root; the committed identity excludes :validator/root"
    (let [with-r (vd/with-root valid-descriptor)]
      (is (= (vd/descriptor-root valid-descriptor) (:validator/root with-r)))
      (is (= (vd/descriptor-root valid-descriptor)
             (vd/descriptor-root with-r))))))

(deftest schema-mismatch-is-rejected
  (is (not (empty? (vd/validate-descriptor
                    (assoc valid-descriptor :validator/schema :bogus/schema))))))

(deftest dependencies-and-execution-model-are-validated
  (testing "unknown dependency kind fails"
    (is (not (empty? (vd/validate-descriptor
                      (assoc valid-descriptor
                             :validator/dependencies [{:kind :bogus-kind :id :x}]))))))
  (testing "unknown execution horizon fails"
    (is (not (empty? (vd/validate-descriptor
                      (assoc valid-descriptor
                             :validator/execution-model {:horizon :bogus-horizon})))))))

(deftest horizon-classification-follows-execution-model
  (testing "no execution model defaults to single-trace satisfiable"
    (is (true? (vd/single-trace-satisfiable? valid-descriptor)))
    (is (false? (vd/multi-epoch-required? valid-descriptor))))
  (testing "single-trace horizon is satisfiable"
    (is (true? (vd/single-trace-satisfiable?
                (assoc valid-descriptor
                       :validator/execution-model {:horizon :single-trace})))))
  (testing "multi-epoch horizon requires multi-epoch evidence"
    (let [d (assoc valid-descriptor
                   :validator/execution-model {:horizon :multi-epoch})]
      (is (true? (vd/multi-epoch-required? d)))
      (is (false? (vd/single-trace-satisfiable? d)))))
  (testing "multi-trace horizon requires multiple traces"
    (let [d (assoc valid-descriptor
                   :validator/execution-model {:horizon :multi-trace})]
      (is (true? (vd/multi-trace-required? d)))
      (is (false? (vd/single-trace-satisfiable? d))))))