(ns resolver-sim.validation.game-theory-profile-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.validation.game-theory-profile :as gtp]))

(def base-validator
  {:validator/schema :prf/game-theoretic-validator.v1
   :validator/id :test/v1
   :validator/version 1
   :validator/kind :deviation-resistance
   :validator/validation-class :validation.class/deviation-resistance
   :validator/epistemic-contract {:claim-strength :bounded-falsification
                                  :universal-claim? false
                                  :falsification? true
                                  :limitations [:bounded-domain]}})

(def good-profile
  {:game-theory/profile :test/profile-v1
   :claims [{:claim/id :test/c1
             :deviation-contract-ids [:test/contract1]
             :validator-ids [:test/v1]
             :equilibrium-concept-ids [:test/concept1]}]
   :validators [base-validator]
   :deviation-contracts [{:contract/id :test/contract1
                          :contract/version 1
                          :mechanism :yield/partial-fill
                          :actor/type :claimant
                          :reference-action :submit-claim-amount
                          :deviation-generators [:gen1]
                          :utility-model :utility/token-linear-v1
                          :parameter-scope {:claim-count-max 5}
                          :epsilon 0
                          :exclusions []}]
   :deviation-generators [{:generator/id :gen1
                           :property :strategy/split-invariance
                           :evaluate (fn [_ _ _] [])}]
   :equilibrium-concepts [{:equilibrium/concept :test/concept1
                           :definition/root "abc"}]})

(deftest complete-profile-passes
  (let [r (gtp/validate-game-theory-profile good-profile)]
    (is (true? (:ok? r)))
    (is (empty? (:violations r)))
    (is (re-matches #"[0-9a-f]{64}" (:profile/root r)))))

(deftest profile-root-excludes-executable-generators
  (testing "profile root is stable and does not commit generator :evaluate fns"
    (let [r1 (gtp/profile-root good-profile)
          r2 (gtp/profile-root (assoc good-profile
                                      :deviation-generators
                                      [{:generator/id :gen1
                                        :property :strategy/split-invariance
                                        :evaluate (fn [_ _ _] [:different])}]))]
      (is (= r1 r2)))
    (testing "changing committed generator identity changes the root"
      (is (not= (gtp/profile-root good-profile)
                (gtp/profile-root (assoc good-profile
                                         :deviation-generators
                                         [{:generator/id :gen1
                                           :property :strategy/other-property
                                           :evaluate (fn [_ _ _] [])}])))))))

(deftest unresolved-references-fail-closed
  (testing "unresolved deviation contract"
    (let [r (gtp/validate-game-theory-profile
             (assoc-in good-profile [:claims 0 :deviation-contract-ids] [:missing-contract]))]
      (is (false? (:ok? r)))
      (is (some #(= :violation/unresolved-deviation-contract (:violation/id %))
                (:violations r)))))
  (testing "unresolved validator"
    (let [r (gtp/validate-game-theory-profile
             (assoc-in good-profile [:claims 0 :validator-ids] [:missing-validator]))]
      (is (some #(= :violation/unresolved-validator (:violation/id %))
                (:violations r)))))
  (testing "unresolved equilibrium concept"
    (let [r (gtp/validate-game-theory-profile
             (assoc-in good-profile [:claims 0 :equilibrium-concept-ids] [:missing-concept]))]
      (is (some #(= :violation/unresolved-equilibrium-concept (:violation/id %))
                (:violations r)))))
  (testing "unresolved deviation generator referenced by a contract"
    (let [r (gtp/validate-game-theory-profile
             (assoc-in good-profile [:deviation-contracts 0 :deviation-generators] [:missing-gen]))]
      (is (some #(= :violation/unresolved-deviation-generator (:violation/id %))
                (:violations r))))))

(deftest epistemic-contract-is-mandatory
  (let [r (gtp/validate-game-theory-profile
           (assoc-in good-profile [:validators 0 :validator/epistemic-contract] nil))]
    (is (false? (:ok? r)))
    (is (some #(= :violation/invalid-validator-descriptor (:violation/id %))
              (:violations r)))))

(deftest unrecognized-validation-class-is-rejected
  (let [r (gtp/validate-game-theory-profile
           (assoc-in good-profile [:validators 0 :validator/validation-class]
                     :validation.class/super-equilibrium))]
    (is (some #(= :violation/unrecognized-validation-class (:violation/id %))
              (:violations r)))))

(deftest duplicate-ids-are-rejected
  (let [dup-claims (assoc good-profile :claims
                          [{:claim/id :test/c1 :deviation-contract-ids []}
                           {:claim/id :test/c1 :deviation-contract-ids []}])
        r (gtp/validate-game-theory-profile dup-claims)]
    (is (some #(= :violation/duplicate-id (:violation/id %))
              (:violations r)))))

(deftest dependency-cycles-are-detected-and-deduplicated
  (let [a (assoc base-validator :validator/id :test/a
                 :validator/dependencies [{:kind :validator :id :test/b}])
        b (assoc base-validator :validator/id :test/b
                 :validator/dependencies [{:kind :validator :id :test/a}])
        p (assoc good-profile :validators [a b])
        r (gtp/validate-game-theory-profile p)]
    (is (false? (:ok? r)))
    (is (= 1 (count (filter #(= :violation/dependency-cycle (:violation/id %))
                            (:violations r))))
        "a→b→a and b→a→b describe the same cycle and must dedupe")))

(deftest self-loop-cycle-is-detected
  (let [self (assoc base-validator :validator/id :test/self
                    :validator/dependencies [{:kind :validator :id :test/self}])
        p (assoc good-profile :validators [self])
        r (gtp/validate-game-theory-profile p)]
    (is (some #(= :violation/dependency-cycle (:violation/id %))
              (:violations r)))))

(deftest profile-root-mismatch-is-detected
  (let [r (gtp/validate-game-theory-profile (assoc good-profile :profile/root "deadbeef"))]
    (is (false? (:ok? r)))
    (is (false? (:recomputed-root? r)))
    (is (some #(= :violation/profile-root-mismatch (:violation/id %))
              (:violations r)))))

(deftest profile-root-recomputes-when-absent
  (let [r (gtp/validate-game-theory-profile (dissoc good-profile :profile/root))]
    (is (true? (:ok? r)))
    (is (true? (:recomputed-root? r)))))