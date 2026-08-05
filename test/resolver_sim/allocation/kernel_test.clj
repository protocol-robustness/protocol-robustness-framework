(ns resolver-sim.allocation.kernel-test
  "Tests for the public PRF reference allocation kernel."
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.allocation.context :as context]
            [resolver-sim.allocation.kernel :as kernel]
            [resolver-sim.allocation.proposal :as proposal]
            [resolver-sim.allocation.selection :as selection]
            [resolver-sim.allocation.vectors :as vectors]
            [resolver-sim.allocation.test-fixtures :as fixtures]))

(deftest happy-path-all-fourteen-assertions-pass
  (let [result (fixtures/kernel-result)]
    (is (= :passing (:result/status result)))
    (is (= 14 (count (:assertions result))))
    (is (every? :assertion/result (:assertions result)))
    (is (= kernel/assertion-ids (mapv :assertion/id (:assertions result))))
    (is (= (set kernel/assertion-ids)
           (set (mapv :assertion/id (:assertions result)))))))

(deftest happy-path-public-values-are-stable
  (let [a (fixtures/kernel-result)
        b (fixtures/kernel-result)]
    (doseq [k [:allocation-context-hash :claimant-set-root :outcome-set-root
               :proposed-rates-root :rate-derived-summary-hash :result-root
               :total-allocated :residual-capacity :certificate-assertions-digest
               :selected-outcome-id :selected-outcome-index]]
      (is (= (get a k) (get b k)) (str k " must be deterministic")))))

(deftest happy-path-expected-allocations-are-exact
  (let [ctx (context/build-context (fixtures/happy-input))
        summary (proposal/build-rate-derived-summary ctx)]
    (is (= {:claim/id "A" :expected-allocation-numerator 50N
            :expected-allocation-denominator 2N
            :exact-pro-rata-numerator 2500N :exact-pro-rata-denominator 100N}
           (first (:expected-allocations summary))))
    (is (= 50N (:total-allocated (fixtures/kernel-result))))
    (is (= 0N (:residual-capacity (fixtures/kernel-result))))))

(deftest selection-is-deterministic-and-in-range
  (let [result (fixtures/kernel-result)
        receipt (:selection-receipt result)]
    (is (= "domain-hash-rejection-v1" (:selection-algorithm result)))
    (is (integer? (:selected-index receipt)))
    (is (<= 0 (:selected-index receipt)))
    (is (< (:selected-index receipt) 2))
    (is (some? (:candidate-digest receipt)))
    (is (integer? (:accepted-counter receipt)))))

(deftest rejection-sampling-retries-on-rejection
  (let [bytes (vec (range 1 33))]
    (with-redefs [selection/candidate-digest-hex
                  (fn [_rand counter _outcome-count]
                    (if (zero? counter)
                      (str "0x" (apply str (repeat 64 "f")))
                      (str "0x" (apply str (repeat 64 "0")))))]
      (let [receipt (selection/select-index bytes 3)]
        (is (>= (:accepted-counter receipt) 1))))))

(deftest changed-randomness-changes-selection
  (let [a (resolver-sim.allocation.selection/select-index
           (context/bytes->byte-ints (context/hex->bytes "0x0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20")) 2)
        b (resolver-sim.allocation.selection/select-index
           (context/bytes->byte-ints (context/hex->bytes "0x0000000000000000000000000000000000000000000000000000000000000001")) 2)]
    (is (not= (:selected-index a) (:selected-index b)))))

(deftest rejected-vectors-produce-stable-classification
  (let [vecs (vectors/all-vectors)
        happy (first vecs)
        rejected (filter #(= :rejected (get-in % [:expected :result/status])) vecs)]
    (is (= :passing (get-in happy [:expected :result/status])))
    (is (= 11 (count rejected)))
    (doseq [v rejected]
      (let [classification (get-in v [:expected :rejection/classification])]
        (is (some? classification) (:vector_id v))
        (is (keyword? classification) (:vector_id v))))))

(deftest every-vector-expected-projection-recomputes
  (doseq [v (vectors/all-vectors)]
    (let [result (kernel/run-kernel (:input v))
          projection (vectors/public-value-projection result)
          expected (:expected v)]
      (is (= (set (keys expected)) (set (keys projection)))
          (:vector_id v))
      (doseq [k (keys expected)]
        (is (= (get expected k) (get projection k))
            (str (:vector_id v) " " k))))))

(deftest certificate-assertions-digest-commits-contract-fields
  (let [result (fixtures/kernel-result)
        digest (:certificate-assertions-digest result)]
    (is (= 64 (count digest)))
    (is (string? digest))
    (let [ctx-hash (:allocation-context-hash result)]
      (is (some? ctx-hash)))))
