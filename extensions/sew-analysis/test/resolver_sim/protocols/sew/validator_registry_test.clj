(ns resolver-sim.protocols.sew.validator-registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.validator-registry :as vreg]
            [resolver-sim.validation.validator-descriptor :as vd]))

(deftest sew-validator-registry-exposes-all-validators
  (testing "registry covers mechanism-property and equilibrium-concept validators"
    (is (= 17 (count (:entries vreg/sew-validator-registry))))
    (is (re-matches #"[0-9a-f]{64}" (:root vreg/sew-validator-registry)))))

(deftest sew-validator-descriptors-are-well-formed
  (testing "every Sew descriptor is a valid prf/game-theoretic-validator.v1"
    (doseq [d vreg/all-validator-descriptors]
      (is (empty? (vd/validate-descriptor d))
          (str (:validator/id d) " must be a well-formed descriptor"))
      (is (= :protocol (:validator/origin d))))))

(deftest sew-validator-descriptors-carry-validation-classes
  (testing "descriptors preserve the validation-class taxonomy"
    (is (= :validation.class/payoff-property
           (get-in (vreg/resolve-sew-validator :individual-rationality)
                   [:validator/validation-class])))
    (is (= :validation.class/deviation-resistance
           (get-in (vreg/resolve-sew-validator :cancellation-dominance)
                   [:validator/validation-class])))
    (is (= :validation.class/equilibrium
           (get-in (vreg/resolve-sew-validator :subgame-perfect-equilibrium)
                   [:validator/validation-class])))))

(deftest sew-validator-executables-resolve
  (testing "executable association maps validator-id → fn"
    (is (fn? (vreg/resolve-sew-validator-executable :individual-rationality)))
    (is (fn? (vreg/resolve-sew-validator-executable :subgame-perfect-equilibrium)))))

(deftest sew-validator-registry-is-order-independent
  (testing "same descriptors in any source order produce the same root"
    (let [reg-a (vreg/build-validator-registry-standalone
                 :mech (subvec vreg/mechanism-property-validator-descriptors 0 2)
                 :eq (subvec vreg/equilibrium-concept-validator-descriptors 0 2))
          reg-b (vreg/build-validator-registry-standalone
                 :mech (subvec vreg/mechanism-property-validator-descriptors 1 3)
                 :eq (subvec vreg/equilibrium-concept-validator-descriptors 2 4))]
      (is (re-matches #"[0-9a-f]{64}" (:root reg-a)))
      (is (re-matches #"[0-9a-f]{64}" (:root reg-b))))))

(deftest sew-protocol-exposes-validator-descriptors
  (testing "SewProtocol implements the optional ValidatorDescriptorCatalog"
    (is (satisfies? proto/ValidatorDescriptorCatalog sew/protocol))
    (is (= 17 (count (proto/validator-descriptors sew/protocol))))))

(deftest multi-trace-horizon-is-declared-on-collusion-resistance
  (testing "collusion-resistance declares a multi-trace execution horizon"
    (is (= :multi-trace
           (get-in (vreg/resolve-sew-validator :collusion-resistance)
                   [:validator/execution-model :horizon])))
    (is (true? (vd/multi-trace-required? (vreg/resolve-sew-validator :collusion-resistance))))))