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

(deftest nested-contract-maps-are-closed
  (doseq [[field key] [[:composition/input :typo/schema]
                       [:composition/output :typo/cardinality]
                       [:composition/effects :typo/control]
                       [:composition/custody :typo/direction]
                       [:composition/control :typo/failure-mode]
                       [:composition/determinism :typo/read]
                       [:composition/adapters :typo/implicit?]
                       [:composition/verification :typo/committed?]]]
    (let [candidate (assoc-in (cc) [field key] true)
          result (c/validate-composition-contract candidate)]
      (is (not (:valid? result)) (str field " rejects unknown key " key))
      (is (some #(= :violation/unknown-composition-contract-nested-key
                    (:violation/id %))
                (:violations result)))))
  (doseq [[field value] [[:composition/input 42]
                         [:composition/effects "effects"]
                         [:composition/control []]
                         [:composition/determinism false]
                         [:composition/adapters :none]]]
    (is (not (:valid? (c/validate-composition-contract
                       (assoc (cc) field value))))
        (str field " rejects type confusion"))))

(deftest nested-execution-values-have-explicit-types
  (doseq [[path value] [[[:composition/control :terminal?] :yes]
                        [[:composition/control :may-short-circuit?] 1]
                        [[:composition/effects :emits] [:effect/x]]
                        [[:composition/effects :exclusive-effects] #{"effect/x"}]
                        [[:composition/determinism :required?] "true"]
                        [[:composition/determinism :context-reads] [:clock]]
                        [[:composition/adapters :accepted] #{:adapter/x}]]]
    (is (not (:valid? (c/validate-composition-contract
                       (assoc-in (cc) path value))))
        (str path " rejects malformed or unconsumed value"))))

(deftest malformed-contract-keys-return-typed-violations
  (doseq [candidate [(assoc (cc) :typo "top-level")
                     (assoc-in (cc) [:composition/effects :typo] true)
                     (assoc-in (cc) [:composition/effects "typo"] true)]]
    (is (not (:valid? (c/validate-composition-contract candidate))))))

(deftest v1-contract-fixtures-remain-compatible
  (let [fixture (cc)
        normalized (c/normalize-contract fixture)]
    (is (= 1 (:composition-contract/version fixture)))
    (is (:valid? (c/validate-composition-contract fixture)))
    (is (= 1 (:composition-contract/version normalized)))
    (is (= (c/composition-contract-root fixture)
           (c/composition-contract-root normalized))))
  (is (some #(= :violation/invalid-composition-contract-version (:violation/id %))
            (:violations (c/validate-composition-contract
                          (cc :mutate #(assoc % :composition-contract/version 2)))))))

(deftest nested-value-type-confusion-is-rejected
  (doseq [[path value] [[[:composition/input :schema-ref] "schema.v1"]
                        [[:composition/input :semantic-type] 7]
                        [[:composition/input :cardinality] "one"]
                        [[:composition/output :schema-ref] nil]
                        [[:composition/output :semantic-type] false]
                        [[:composition/output :cardinality] []]
                        [[:composition/effects :merge-strategy] "accumulate"]
                        [[:composition/control :failure-mode] 1]
                        [[:composition/custody :accounts] #{"escrow"}]]]
    (is (not (:valid? (c/validate-composition-contract
                       (assoc-in (cc) path value))))
        (str path " rejects type confusion"))))

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

(deftest custody-declaration-validated
  (is (:valid? (c/validate-composition-contract
                (cc :mutate #(assoc % :composition/custody
                                    {:direction :add :accounts #{:escrow}})))))
  (is (some #(= :violation/unsupported-custody-direction (:violation/id %))
            (:violations (c/validate-composition-contract
                          (cc :mutate #(assoc % :composition/custody
                                              {:direction :weird}))))))
  (is (some #(= :violation/invalid-composition-custody (:violation/id %))
            (:violations (c/validate-composition-contract
                          (cc :mutate #(assoc % :composition/custody 42))))))
  (is (some #(= :violation/invalid-custody-accounts (:violation/id %))
            (:violations (c/validate-composition-contract
                          (cc :mutate #(assoc % :composition/custody
                                              {:accounts [1 2]})))))))

(deftest verification-declaration-validated
  (is (:valid? (c/validate-composition-contract
                (cc :mutate #(assoc % :composition/verification
                                    {:intermediate-output-committed? false
                                     :evidence-contract-ref :prf/evidence.v1})))))
  (is (:valid? (c/validate-composition-contract (cc :mutate #(dissoc % :composition/verification))))
      "verification obligations are optional")
  (is (some #(= :violation/invalid-composition-verification (:violation/id %))
            (:violations (c/validate-composition-contract
                          (cc :mutate #(assoc % :composition/verification "garbage")))))
      "a non-map verification value is rejected, never silently defaulted")
  (is (some #(= :violation/unknown-composition-verification-key (:violation/id %))
            (:violations (c/validate-composition-contract
                          (cc :mutate #(assoc % :composition/verification
                                              {:intermediate-output-committed? true
                                               :evidence-contract-ref nil
                                               :typo/flag true}))))))
  (is (some #(= :violation/invalid-verification-intermediate-flag (:violation/id %))
            (:violations (c/validate-composition-contract
                          (cc :mutate #(assoc % :composition/verification
                                              {:intermediate-output-committed? "yes"}))))))
  (is (some #(= :violation/invalid-verification-evidence-contract-ref (:violation/id %))
            (:violations (c/validate-composition-contract
                          (cc :mutate #(assoc % :composition/verification
                                              {:evidence-contract-ref 42})))))))

(deftest contract-root-mutation
  (testing "every committed contract field changes the contract root"
    (doseq [[label mutate] [["version" #(assoc % :composition-contract/version 2)]
                            ["input-semantic" #(assoc-in % [:composition/input :semantic-type] :gross)]
                            ["input-schema-ref" #(assoc-in % [:composition/input :schema-ref] :prf/other-input.v1)]
                            ["input-cardinality" #(assoc-in % [:composition/input :cardinality] :many)]
                            ["output-semantic" #(assoc-in % [:composition/output :semantic-type] :gross)]
                            ["output-schema-ref" #(assoc-in % [:composition/output :schema-ref] :prf/other-output.v1)]
                            ["modes" #(assoc % :composition/modes #{:parallel})]
                            ["roles" #(assoc % :composition/roles #{:terminal})]
                            ["effects" #(assoc-in % [:composition/effects :emits] #{:prf.effect/x.v1})]
                            ["exclusive-effects" #(assoc-in % [:composition/effects :exclusive-effects] #{:x})]
                            ["terminal" #(assoc-in % [:composition/control :terminal?] true)]
                            ["failure-mode" #(assoc-in % [:composition/control :failure-mode] :continue)]
                            ["determinism" #(assoc-in % [:composition/determinism :required?] false)]
                            ["context-reads" #(assoc-in % [:composition/determinism :context-reads] #{:clock})]
                            ["external-reads" #(assoc-in % [:composition/determinism :external-reads] #{:rpc})]
                            ["implicit-adapter" #(assoc-in % [:composition/adapters :implicit?] true)]
                            ["verification-evidence-ref" #(assoc-in % [:composition/verification :evidence-contract-ref] :prf/evidence.v1)]
                            ["custody" #(assoc % :composition/custody {:direction :add :accounts #{:escrow}})]]]
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
