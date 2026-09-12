(ns resolver-sim.economics.schemas-test
  "Phase 3: explicit capability contract schemas and structural conformance."
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.economics.schemas :as s]
            [resolver-sim.economics.slash-distribution :as sd]))

(deftest schema-root-deterministic-and-sensitive
  (is (= (s/schema-root s/award-amount-context)
         (s/schema-root s/award-amount-context)))
  (is (not= (s/schema-root s/award-amount-context)
            (s/schema-root (assoc s/award-amount-context :gross-amount :string))))
  (is (= 64 (count (s/schema-root s/award-amount-context)))))

(deftest core-schemas-cover-core-capabilities
  (doseq [entry (vals (sd/core-extension-map))]
    (is (contains? s/core-schemas (:input-schema (:capability entry))))
    (is (contains? s/core-schemas (:output-schema (:capability entry))))))

(deftest validate-against-schema-accepts-valid
  (is (:valid? (s/validate-against-schema
                s/award-amount-context
                {:gross-amount 100 :amount-spec {} :param-values {} :resolved-award {}}))))

(deftest validate-against-schema-rejects-missing-key
  (let [{:keys [valid? violations]}
        (s/validate-against-schema s/award-amount-context
                                   {:gross-amount 100 :amount-spec {}})]
    (is (not valid?))
    (is (some #(= :violation/missing-schema-key (:violation/id %)) violations))))

(deftest validate-against-schema-rejects-type-mismatch
  (let [{:keys [valid? violations]}
        (s/validate-against-schema
         s/award-amount-context
         {:gross-amount "100" :amount-spec {} :param-values {} :resolved-award {}})]
    (is (not valid?))
    (is (some #(= :violation/schema-type-mismatch (:violation/id %)) violations))))

(deftest conformance-check-on-builtin-rate-of-gross
  (let [entry (get (sd/core-extension-map) [:economics/award-amount :prf/rate-of-gross])
        {:keys [valid? result violations]}
        (s/conformance-check
         entry
         {:gross-amount 1000
          :amount-spec {:method :rate-of-gross
                        :parameter-key :p
                        :scale 10000
                        :rounding :floor}
          :param-values {:p 500}
          :resolved-award {}})]
    (is valid? (str "unexpected violations: " (pr-str violations)))
    (is (= 50 (:amount result)))
    (is (= :positive-award (get-in result [:calculation :classification])))))

(deftest conformance-check-fails-on-malformed-input
  (let [entry (get (sd/core-extension-map) [:economics/award-amount :prf/rate-of-gross])
        {:keys [valid? violations]}
        (s/conformance-check
         entry
         {:gross-amount "not-an-integer"
          :amount-spec {}
          :param-values {}
          :resolved-award {}})]
    (is (not valid?))
    (is (some #(= :violation/schema-type-mismatch (:violation/id %)) violations))))
