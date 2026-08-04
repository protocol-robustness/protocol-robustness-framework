(ns resolver-sim.composition.contract-test
  "Composition contract: validation and content-addressed root mutation."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.composition.contract :as c]
            [resolver-sim.composition.fixtures :as fx]))

(defn- cc
  [& {:keys [mutate]}]
  (let [base (get-in (fx/cap :fixture/base) [:composition-contract])]
    (if mutate (mutate base) base)))

(deftest valid-contract-passes
  (is (:valid? (c/validate-composition-contract (cc))))
  (is (empty? (:violations (c/validate-composition-contract (cc))))))

(deftest missing-version-rejected
  (is (some #(= :violation/invalid-composition-contract-version (:violation/id %))
            (:violations (c/validate-composition-contract
                          (cc :mutate #(dissoc % :composition-contract/version)))))))

(deftest unknown-keys-rejected
  (is (some #(= :violation/unknown-composition-contract-key (:violation/id %))
            (:violations (c/validate-composition-contract
                          (cc :mutate #(assoc % :unknown-key 1)))))))

(deftest malformed-schema-reference-rejected
  (is (some #(= :violation/malformed-schema-reference (:violation/id %))
            (:violations (c/validate-composition-contract
                          (cc :mutate #(assoc-in % [:composition/input :schema-ref] nil)))))))

(deftest missing-semantic-type-rejected
  (is (some #(= :violation/missing-composition-semantic-type (:violation/id %))
            (:violations (c/validate-composition-contract
                          (cc :mutate #(assoc-in % [:composition/output :semantic-type] nil)))))))

(deftest unsupported-mode-and-failure-rejected
  (is (some #(= :violation/unsupported-effect-merge-strategy (:violation/id %))
            (:violations (c/validate-composition-contract
                          (cc :mutate #(assoc-in % [:composition/effects :merge-strategy] :weird))))))
  (is (some #(= :violation/unsupported-failure-mode (:violation/id %))
            (:violations (c/validate-composition-contract
                          (cc :mutate #(assoc-in % [:composition/control :failure-mode] :weird)))))))

(deftest non-map-contract-rejected
  (is (some #(= :violation/non-map-composition-contract (:violation/id %))
            (:violations (c/validate-composition-contract 42)))))

(deftest contract-root-mutation
  (testing "every committed contract field changes the contract root"
    (doseq [[label mutate] [["version" #(assoc % :composition-contract/version 2)]
                            ["input-semantic" #(assoc-in % [:composition/input :semantic-type] :gross)]
                            ["output-semantic" #(assoc-in % [:composition/output :semantic-type] :gross)]
                            ["modes" #(assoc % :composition/modes #{:parallel})]
                            ["roles" #(assoc % :composition/roles #{:terminal})]
                            ["effects" #(assoc-in % [:composition/effects :emits] #{:prf.effect/x.v1})]
                            ["exclusive-effects" #(assoc-in % [:composition/effects :exclusive-effects] #{:x})]
                            ["terminal" #(assoc-in % [:composition/control :terminal?] true)]
                            ["failure-mode" #(assoc-in % [:composition/control :failure-mode] :continue)]
                            ["determinism" #(assoc-in % [:composition/determinism :required?] false)]
                            ["implicit-adapter" #(assoc-in % [:composition/adapters :implicit?] true)]]]
      (is (not= (c/composition-contract-root (cc))
                (c/composition-contract-root (cc :mutate mutate)))
          (str label " must change the contract root")))))

(deftest contract-root-deterministic-and-order-insensitive
  (let [base (cc)
        reordered (-> base
                      (update :composition/effects (fn [m] (into (sorted-map) m)))
                      (update :composition/control (fn [m] (into (sorted-map) m))))]
    (is (= (c/composition-contract-root base) (c/composition-contract-root base)))
    (is (= (c/composition-contract-root base) (c/composition-contract-root reordered)))))
