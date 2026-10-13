(ns resolver-sim.allocation.proposal-test
  "Tests for proposal-level derivation and structural checks."
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.allocation.context :as context]
            [resolver-sim.allocation.proposal :as proposal]
            [resolver-sim.allocation.test-fixtures :as fixtures]))

(defn- ctx [& [mutations]]
  (context/build-context (merge (fixtures/happy-input) mutations)))

(deftest rates-normalisation-reduces-by-gcd
  (is (= {:numerator 1 :denominator 2}
         (proposal/canonical-rate {:numerator 2 :denominator 4})))
  (is (= {:numerator 0 :denominator 1}
         (proposal/canonical-rate {:numerator 0 :denominator 7})))
  (is (= {:numerator 3 :denominator 4}
         (proposal/canonical-rate {:numerator 6 :denominator 8}))))

(deftest ratio-normalised-detects-non-reduced
  (is (true? (proposal/ratio-normalised? {:numerator 1 :denominator 2})))
  (is (false? (proposal/ratio-normalised? {:numerator 2 :denominator 4})))
  (is (false? (proposal/ratio-normalised? {:numerator 1 :denominator 0})))
  (is (false? (proposal/ratio-normalised? {:numerator -1 :denominator 2}))))

(deftest common-denominator-derivation
  (let [rates (context/canonical-proposed-rates
               [{"outcome-id" "O1" "numerator" "1" "denominator" "2"}
                {"outcome-id" "O2" "numerator" "1" "denominator" "3"}]
               [{:outcome/id "O1"} {:outcome/id "O2"}])]
    (is (= {:common-denominator 6N
            :scaled-numerators [3N 2N]
            :sum 5N}
           (-> (proposal/ratio-sum-common rates)
               (select-keys [:common-denominator :scaled-numerators :sum]))))))

(deftest rates-sum-to-one
  (is (true? (proposal/rates-sum-to-one? (:proposed-rates (ctx)))))
  (is (false? (proposal/rates-sum-to-one?
               (:proposed-rates
                (ctx {"proposed-rates"
                      [{"outcome-id" "O1" "numerator" "1" "denominator" "3"}
                       {"outcome-id" "O2" "numerator" "1" "denominator" "3"}]}))))))

(deftest expected-allocation-numerators
  (let [c (ctx)
        {:keys [common-denominator numerators]} (proposal/expected-allocation-numerators c)]
    (is (= 2N common-denominator))
    (is (= [50N 30N 20N] numerators))))

(deftest rate-derived-summary-is-derived-not-trusted
  (let [c (ctx)
        summary (proposal/build-rate-derived-summary c)]
    (is (= 2N (:common-rate-denominator summary)))
    (is (= 2N (:rates-sum summary)))
    (is (true? (:rates-sum-to-one? summary)))
    (is (= [50N 30N 20N]
           (mapv :expected-allocation-numerator (:expected-allocations summary))))
    (is (= [2500N 1500N 1000N]
           (mapv :exact-pro-rata-numerator (:expected-allocations summary))))
    (is (= [100N 100N 100N]
           (mapv :exact-pro-rata-denominator (:expected-allocations summary))))))

(deftest proportional-proposed-exact-cross-product
  (is (true? (proposal/proportional-proposed? (ctx))))
  ;; break proportionality by changing a rate while keeping sum-to-one
  (let [c (ctx {"proposed-rates"
                [{"outcome-id" "O1" "numerator" "1" "denominator" "4"}
                 {"outcome-id" "O2" "numerator" "3" "denominator" "4"}]})]
    (is (false? (proposal/proportional-proposed? c)))))

(deftest structural-checks
  (is (true? (proposal/outcomes-eligible-only? (ctx))))
  (is (true? (proposal/outcomes-no-duplicate-claims? (ctx))))
  (is (true? (proposal/outcomes-all-or-nothing? (ctx))))
  (is (true? (proposal/outcomes-exact-capacity? (ctx))))
  (is (true? (proposal/rates-canonical-exact? (ctx)))))

(deftest ineligible-claimant-detected
  (let [c (ctx {"outcomes"
                [{"outcome-id" "O1"
                  "allocations" [{"claim-id" "A" "allocated" "50"}
                                 {"claim-id" "B" "allocated" "0"}
                                 {"claim-id" "C" "allocated" "0"}
                                 {"claim-id" "D" "allocated" "0"}]}
                 (get-in (fixtures/happy-input) ["outcomes" 1])]})]
    (is (false? (proposal/outcomes-eligible-only? c)))))

(deftest duplicate-claim-in-outcome-detected
  (let [c (ctx {"outcomes"
                [{"outcome-id" "O1"
                  "allocations" [{"claim-id" "A" "allocated" "50"}
                                 {"claim-id" "A" "allocated" "0"}
                                 {"claim-id" "B" "allocated" "0"}
                                 {"claim-id" "C" "allocated" "0"}]}
                 (get-in (fixtures/happy-input) ["outcomes" 1])]})]
    (is (false? (proposal/outcomes-no-duplicate-claims? c)))))

(deftest partial-claimant-allocation-detected
  (let [c (ctx {"outcomes"
                [{"outcome-id" "O1"
                  "allocations" [{"claim-id" "A" "allocated" "25"}
                                 {"claim-id" "B" "allocated" "0"}
                                 {"claim-id" "C" "allocated" "0"}]}
                 (get-in (fixtures/happy-input) ["outcomes" 1])]})]
    (is (false? (proposal/outcomes-all-or-nothing? c)))))

(deftest over-and-under-capacity-detected
  (let [over (ctx {"outcomes"
                   [{"outcome-id" "O1"
                     "allocations" [{"claim-id" "A" "allocated" "50"}
                                    {"claim-id" "B" "allocated" "30"}
                                    {"claim-id" "C" "allocated" "0"}]}
                    (get-in (fixtures/happy-input) ["outcomes" 1])]})
        under (ctx {"outcomes"
                    [{"outcome-id" "O1"
                      "allocations" [{"claim-id" "A" "allocated" "0"}
                                     {"claim-id" "B" "allocated" "0"}
                                     {"claim-id" "C" "allocated" "0"}]}
                     (get-in (fixtures/happy-input) ["outcomes" 1])]})]
    (is (false? (proposal/outcomes-exact-capacity? over)))
    (is (false? (proposal/outcomes-exact-capacity? under)))))
