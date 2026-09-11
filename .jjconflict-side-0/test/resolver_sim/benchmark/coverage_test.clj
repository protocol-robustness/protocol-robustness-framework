(ns resolver-sim.benchmark.coverage-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.coverage :as coverage]))

(def known-claims
  #{:claim/funds-conserved :evidence-root-present})

(def valid-active
  {:benchmark/status :active
   :benchmark/property-types #{:safety}
   :benchmark/property-claims {:safety [:claim/funds-conserved]}
   :benchmark/claims [{:claim/id :claim/funds-conserved}]})

(deftest active-lifecycle-requires-runnable-semantic-coverage
  (testing "a narrow active benchmark with a runnable semantic claim is valid"
    (is (empty? (coverage/active-benchmark-errors valid-active known-claims))))

  (testing "deferred, unknown, unrunnable, and mechanical-only coverage is rejected"
    (is (contains? (set (coverage/active-benchmark-errors
                         (assoc valid-active :benchmark/deferred-scenario-claims
                                #{:claim/funds-conserved})
                         known-claims))
                   :active/deferred-claims))
    (is (contains? (set (coverage/active-benchmark-errors
                         (assoc valid-active :benchmark/required-claims [:unknown/claim])
                         known-claims))
                   :active/unknown-required-claims))
    (is (contains? (set (coverage/active-benchmark-errors
                         (assoc valid-active :benchmark/required-claims [:evidence-root-present])
                         known-claims))
                   :active/mechanical-only))
    (is (contains? (set (coverage/active-benchmark-errors
                         (assoc valid-active :benchmark/property-claims {})
                         known-claims))
                   :active/unmapped-advertised-property))))

(deftest concept-maturity-is-derived
  (let [concept {:concept/id :concept/example
                 :concept/maps-to {:scenarios ["scenario-a"]}}
        active (assoc valid-active
                      :benchmark/id :benchmark/example
                      :benchmark/concepts [:concept/example])
        defined {:concept/id :concept/defined}]
    (is (= :benchmarked (:concept/maturity (coverage/concept-coverage concept [active]))))
    (is (= :defined (:concept/maturity (coverage/concept-coverage defined []))))))

(deftest concept-coverage-retains-direct-mappings-and-loads-the-catalogue
  (let [concept {:concept/id :concept/example
                 :concept/maps-to {:scenarios ["scenario-a"]
                                   :claims [:claim/funds-conserved]}}
        record (coverage/concept-coverage concept [])
        manifests (coverage/catalogue-manifests)]
    (is (= ["scenario-a"] (:concept/scenario-mappings record)))
    (is (= [:claim/funds-conserved] (:concept/claim-ids record)))
    (is (= :evaluated (:concept/maturity record)))
    (is (some #(= :benchmark/prf-deterministic-replay-v1 (:benchmark/id %)) manifests))))

(deftest required-claim-outcomes-and-pack-capabilities-are-enforced
  (testing "an active benchmark cannot pass when its required claim was not exercised"
    (is (coverage/required-claims-passed?
         valid-active
         [{:claim/id :claim/funds-conserved :claim/outcome :pass}]))
    (is (not (coverage/required-claims-passed?
              valid-active
              [{:claim/id :claim/funds-conserved :claim/outcome :not-exercised}]))))

  (testing "demonstrated pack capabilities require an active supported benchmark"
    (let [pack {:pack/capabilities [{:capability/id :capability/example
                                     :capability/status :demonstrated
                                     :capability/benchmarks [:benchmark/example]}]}
          manifests {:benchmark/example (assoc valid-active :benchmark/id :benchmark/example)}]
      (is (empty? (coverage/pack-capability-errors pack manifests known-claims)))
      (is (= [:pack/demonstrated-capability-unsupported]
             (vec (coverage/pack-capability-errors
                   pack
                   {:benchmark/example (assoc valid-active :benchmark/status :experimental)}
                   known-claims)))))))
