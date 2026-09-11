(ns resolver-sim.allocation.context-test
  "Tests for allocation context construction and validation."
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.allocation.context :as context]
            [resolver-sim.allocation.test-fixtures :as fixtures]
            [resolver-sim.hash.canonical :as hc]))

(defn- rejection-classification [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo e
      (:rejection/classification (ex-data e)))))

(deftest happy-path-context-builds
  (let [ctx (context/build-context (fixtures/happy-input))]
    (is (= "allocation-context.v1" (:schema-version ctx)))
    (is (= :allocation-context (:artifact-kind ctx)))
    (is (= "allocation-kernel.v1" (:allocation-kernel-version ctx)))
    (is (= :domain-hash-rejection-v1 (:selection-algorithm ctx)))
    (is (= 50N (:capacity ctx)))
    (is (= 100N (:total-eligible-weight ctx)))
    (is (= 100N (:exact-pro-rata-denominator ctx)))
    (is (= ["A" "B" "C"] (mapv :claim/id (:claimants ctx))))
    (is (= ["O1" "O2"] (mapv :outcome/id (:outcomes ctx))))
    (is (= 2 (count (:proposed-rates ctx))))))

(deftest context-commits-all-identity-fields
  (let [ctx (context/build-context (fixtures/happy-input))
        preimage (context/context-preimage ctx)]
    (doseq [k [:schema-version :artifact-kind :allocation/id :allocation-kernel-version
               :selection-algorithm :policy :claimants :outcomes :proposed-rates
               :capacity :total-eligible-weight :exact-pro-rata-denominator
               :authoritative-randomness]]
      (is (contains? preimage k) (str "preimage must commit " k)))
    (is (= 64 (count (context/context-hash ctx))))
    (is (= (context/context-hash ctx) (context/context-hash ctx)))))

(deftest context-hash-is-domain-separated
  (let [a (context/context-hash (context/build-context (fixtures/happy-input)))]
    (is (= 64 (count a)))
    (is (not= a (hc/domain-hash
                 :claimant-set (context/context-preimage
                                (context/build-context (fixtures/happy-input))))))))

(deftest canonical-claimant-ordering-is-stable
  (let [forward (fixtures/happy-input)
        reversed (assoc forward "claimants" (vec (reverse (get forward "claimants"))))
        a (context/build-context forward)
        b (context/build-context reversed)]
    (is (= (mapv :claim/id (:claimants a))
           (mapv :claim/id (:claimants b))))
    (is (= (context/context-hash a) (context/context-hash b)))))

(deftest canonical-outcome-ordering-is-stable
  (let [forward (fixtures/happy-input)
        reversed (assoc forward "outcomes" (vec (reverse (get forward "outcomes"))))
        a (context/build-context forward)
        b (context/build-context reversed)]
    (is (= (mapv :outcome/id (:outcomes a))
           (mapv :outcome/id (:outcomes b))))
    (is (= (context/context-hash a) (context/context-hash b)))))

(deftest duplicate-claim-ids-are-rejected
  (let [input (assoc-in (fixtures/happy-input)
                        ["claimants" 1 "claim-id"] "A")]
    (is (= :duplicate-claim-id (rejection-classification #(context/build-context input))))))

(deftest duplicate-owners-forbidden-by-policy
  (let [input (-> (fixtures/happy-input)
                  (assoc-in ["claimants" 1 "economic-owner-id"] "owner-A")
                  (assoc-in ["policy" "forbid-duplicate-owners"] true))]
    (is (= :duplicate-economic-owner (rejection-classification #(context/build-context input))))))

(deftest negative-amounts-and-weights-are-rejected
  (is (= :negative-amount
         (rejection-classification
          #(context/build-context
            (assoc-in (fixtures/happy-input) ["claimants" 0 "amount"] "-5")))))
  (is (= :negative-amount
         (rejection-classification
          #(context/build-context
            (assoc-in (fixtures/happy-input) ["claimants" 0 "weight"] "-5"))))))

(deftest zero-total-weight-is-rejected
  (let [input (-> (fixtures/happy-input)
                  (assoc-in ["claimants" 0 "weight"] "0")
                  (assoc-in ["claimants" 1 "weight"] "0")
                  (assoc-in ["claimants" 2 "weight"] "0"))]
    (is (= :zero-total-weight (rejection-classification #(context/build-context input))))))

(deftest non-positive-capacity-is-rejected
  (is (= :non-positive-capacity
         (rejection-classification
          #(context/build-context (assoc (fixtures/happy-input) "capacity" "0")))))
  (is (= :negative-amount
         (rejection-classification
          #(context/build-context (assoc (fixtures/happy-input) "capacity" "-10"))))))

(deftest malformed-randomness-is-rejected
  (doseq [rnd ["not-hex"
               "0x0102"
               (str "0x" (apply str (repeat 32 "G")))
               (str "0x" (apply str (repeat 32 "AB")))]]
    (is (= :malformed-randomness
           (rejection-classification
            #(context/build-context (assoc (fixtures/happy-input) "authoritative-randomness" rnd)))))))

(deftest inconsistent-declared-totals-are-rejected
  (is (= :inconsistent-total-weight
         (rejection-classification
          #(context/build-context (assoc (fixtures/happy-input) "total-eligible-weight" "99")))))
  (is (= :inconsistent-pro-rata-denominator
         (rejection-classification
          #(context/build-context (assoc (fixtures/happy-input) "exact-pro-rata-denominator" "99"))))))

(deftest empty-claimant-and-outcome-sets-are-rejected
  (is (= :empty-claimant-set
         (rejection-classification
          #(context/build-context (assoc (fixtures/happy-input) "claimants" [])))))
  (is (= :empty-outcome-set
         (rejection-classification
          #(context/build-context (assoc (fixtures/happy-input) "outcomes" []))))))

(deftest unsupported-kernel-version-and-algorithm-are-rejected
  (is (= :unsupported-kernel-version
         (rejection-classification
          #(context/build-context (assoc (fixtures/happy-input) "kernel-version" "other")))))
  (is (= :unsupported-selection-algorithm
         (rejection-classification
          #(context/build-context (assoc (fixtures/happy-input) "selection-algorithm" "other"))))))

(deftest outcome-missing-claim-entry-is-preserved
  (let [input (assoc-in (fixtures/happy-input)
                        ["outcomes" 0 "allocations"]
                        [{"claim-id" "A" "allocated" "50"}])]
    (is (nil? (rejection-classification #(context/build-context input)))
        "Outcome with a subset of claims must build; kernel checks handle completeness.")))
