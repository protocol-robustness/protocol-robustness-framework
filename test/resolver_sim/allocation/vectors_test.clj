(ns resolver-sim.allocation.vectors-test
  "Tests for conformance vector serialization and round-trip."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [resolver-sim.allocation.kernel :as kernel]
            [resolver-sim.allocation.vectors :as vectors]
            [resolver-sim.allocation.test-fixtures :as fixtures]))

(deftest vector-wire-shape
  (let [v (first (vectors/all-vectors))]
    (is (= "allocation-kernel-vector.v1" (:vector_version v)))
    (is (string? (:vector_id v)))
    (is (string? (:description v)))
    (is (map? (:input v)))
    (is (map? (:expected v)))
    (is (contains? (:expected v) :result/status))
    (is (contains? (:expected v) :claimant-set-root))
    (is (contains? (:expected v) :certificate-assertions-digest))))

(deftest vector-json-round-trip-preserves-expected
  (doseq [v (vectors/all-vectors)]
    (let [wire (vectors/write-json v)
          parsed (json/read-str wire :key-fn identity)
          expected (get parsed "expected")
          projection (vectors/public-value-projection (kernel/run-kernel (get parsed "input")))]
      (is (= (:vector_id v) (get parsed "vector_id")) (:vector_id v))
      (is (= (vectors/project-json projection)
             (vectors/project-json expected))
          (:vector_id v)))))

(deftest project-json-hash-normalization
  (is (= "0xabc" (vectors/project-json "0xabc")))
  (is (= (str "0x" (apply str (repeat 64 "0")))
         (vectors/project-json (apply str (repeat 64 "0")))))
  (is (= (str "0x" (apply str (repeat 64 "0")))
         (vectors/project-json (str "0x" (apply str (repeat 64 "0"))))))
  (is (= "50" (vectors/project-json 50)))
  (is (= "allocation.assertion/x" (vectors/project-json :allocation.assertion/x))))

(deftest base-committed-recomputes
  (let [input (fixtures/happy-with-committed)
        result (kernel/run-kernel input)]
    (is (= :passing (:result/status result)))))

(deftest all-vectors-have-unique-ids
  (let [ids (mapv :vector_id (vectors/all-vectors))]
    (is (= (count ids) (count (distinct ids))))))

(deftest vector-suite-matrix-is-stable
  "Pin the full 14-vector table: vector id -> expected status -> classification.
   Vectors 7 and 8 intentionally share outcome-not-exact-capacity, so 11
   rejected vectors map to 10 distinct classifications."
  (let [expected
        {"a-vs-b-plus-c-happy-path" {:status :passing :classification nil}
         "a-vs-b-plus-c-claimant-order-permutation" {:status :passing :classification nil}
         "a-vs-b-plus-c-outcome-order-permutation" {:status :passing :classification nil}
         "a-vs-b-plus-c-malformed-rate-total" {:status :rejected :classification :rates-not-sum-to-one}
         "a-vs-b-plus-c-non-reduced-ratio" {:status :rejected :classification :rates-not-canonical}
         "a-vs-b-plus-c-partial-claimant-allocation" {:status :rejected :classification :allocation-not-all-or-nothing}
         "a-vs-b-plus-c-over-capacity-outcome" {:status :rejected :classification :outcome-not-exact-capacity}
         "a-vs-b-plus-c-under-capacity-outcome" {:status :rejected :classification :outcome-not-exact-capacity}
         "a-vs-b-plus-c-ineligible-claimant" {:status :rejected :classification :ineligible-claimant}
         "a-vs-b-plus-c-duplicate-claim-in-outcome" {:status :rejected :classification :duplicate-claim-in-outcome}
         "a-vs-b-plus-c-proportionality-failure" {:status :rejected :classification :proportionality-failure}
         "a-vs-b-plus-c-changed-authoritative-randomness" {:status :rejected :classification :selected-outcome-mismatch}
         "a-vs-b-plus-c-forged-expected-root" {:status :rejected :classification :claimant-set-root-mismatch}
         "a-vs-b-plus-c-empty-outcome-set" {:status :rejected :classification :empty-outcome-set}}]
    (let [actual (into {}
                       (map (fn [v]
                              [(:vector_id v)
                               {:status (get-in v [:expected :result/status])
                                :classification (get-in v [:expected :rejection/classification])}]))
                       (vectors/all-vectors))]
      (is (= expected actual)))
    (is (= 14 (count expected)))
    (is (= 3 (count (filter #(= :passing (:status (val %))) expected))))
    (is (= 11 (count (filter #(= :rejected (:status (val %))) expected))))
    (is (= 10 (count (distinct (keep :classification (vals expected))))))))
