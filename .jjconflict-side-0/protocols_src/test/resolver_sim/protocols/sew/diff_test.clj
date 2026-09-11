(ns resolver-sim.protocols.sew.diff-test
  "Unit tests for the canonical world-state hashing and diff utilities.

   Tests:
     1. Determinism      — same logical world always produces same hash
     2. Map-order        — insertion-order differences don't affect hash
     3. Sensitivity      — single-field changes produce a different hash
     4. Diff correctness — diff-worlds reports only the changed field
     5. Projection       — projection-hash is stable for EVM-comparable fields
     6. Nil diff         — identical worlds return nil from diff-worlds"
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.protocols.sew.diff  :as diff]
            [resolver-sim.protocols.sew.types :as t]))

;; ---------------------------------------------------------------------------
;; Test worlds
;; ---------------------------------------------------------------------------

(def world-a
  (-> (t/empty-world 100)
      (assoc-in [:escrow-transfers 0] (t/make-escrow-transfer
                                       {:token "0xtoken" :to "0xto" :from "0xfrom"
                                        :amount-after-fee 1000 :escrow-state :pending}))
      (assoc-in [:total-held "0xtoken"] 1000)
      (assoc-in [:total-fees "0xtoken"] 10)
      (assoc-in [:dispute-levels 0] 0)))

(def world-a-reordered
  "Same logical content as world-a but maps built with different insertion order."
  (-> (t/empty-world 100)
      (assoc-in [:total-fees "0xtoken"] 10)
      (assoc-in [:dispute-levels 0] 0)
      (assoc-in [:total-held "0xtoken"] 1000)
      (assoc-in [:escrow-transfers 0] (t/make-escrow-transfer
                                       {:amount-after-fee 1000 :escrow-state :pending
                                        :token "0xtoken" :to "0xto" :from "0xfrom"}))))

(def world-b
  "world-a with one field changed: amount-after-fee 1000 → 999."
  (assoc-in world-a [:escrow-transfers 0 :amount-after-fee] 999))

;; ---------------------------------------------------------------------------
;; 1. Determinism
;; ---------------------------------------------------------------------------

(deftest hash-is-deterministic
  (is (= (hc/hash-with-intent {:hash/intent :world-structure} world-a) (hc/hash-with-intent {:hash/intent :world-structure} world-a))
      "Same world produces same hash on repeated calls"))

;; ---------------------------------------------------------------------------
;; 2. Map insertion order does not affect hash
;; ---------------------------------------------------------------------------

(deftest hash-is-order-independent
  (is (= (hc/hash-with-intent {:hash/intent :world-structure} world-a) (hc/hash-with-intent {:hash/intent :world-structure} world-a-reordered))
      "Logically identical worlds with different insertion order produce same hash"))

;; ---------------------------------------------------------------------------
;; 3. Single-field change produces a different hash
;; ---------------------------------------------------------------------------

(deftest hash-changes-on-mutation
  (is (not= (hc/hash-with-intent {:hash/intent :world-structure} world-a) (hc/hash-with-intent {:hash/intent :world-structure} world-b))
      "Changing amount-after-fee by 1 must change the hash"))

;; ---------------------------------------------------------------------------
;; 4. diff-worlds reports only the changed field
;; ---------------------------------------------------------------------------

(deftest diff-reports-changed-field
  (let [result (diff/diff-worlds world-a world-b)]
    (is (some? result)
        "Diff of non-identical worlds should be non-nil")
    (is (= 1000 (get-in result [:only-in-a :escrow-transfers 0 :amount-after-fee]))
        "only-in-a should contain world-a's value for the changed field")
    (is (= 999  (get-in result [:only-in-b :escrow-transfers 0 :amount-after-fee]))
        "only-in-b should contain world-b's value for the changed field")
    (is (= (hc/hash-with-intent {:hash/intent :world-structure} world-a) (:hash-a result)))
    (is (= (hc/hash-with-intent {:hash/intent :world-structure} world-b) (:hash-b result)))))

;; ---------------------------------------------------------------------------
;; 5. Identical worlds return nil
;; ---------------------------------------------------------------------------

(deftest diff-of-identical-worlds-is-nil
  (is (nil? (diff/diff-worlds world-a world-a))
      "diff-worlds should return nil when worlds are identical")
  (is (nil? (diff/diff-worlds world-a world-a-reordered))
      "diff-worlds should return nil for worlds that differ only in insertion order"))

;; ---------------------------------------------------------------------------
;; 6. projection-hash excludes non-EVM fields
;; ---------------------------------------------------------------------------

(deftest projection-hash-excludes-non-evm-fields
  (testing "Adding a non-comparable field doesn't change projection-hash"
    (let [world-extra (assoc world-a :escrow-settings {0 {:custom-resolver "0xX"}})]
      (is (= (diff/projection-hash world-a) (diff/projection-hash world-extra))
          "escrow-settings is not in comparable-keys; projection-hash must be equal")))
  (testing "Changing a comparable field does change projection-hash"
    (is (not= (diff/projection-hash world-a) (diff/projection-hash world-b))
        "amount-after-fee is inside :escrow-transfers which is comparable")))

;; ---------------------------------------------------------------------------
;; 7. canonical-world is idempotent
;; ---------------------------------------------------------------------------

(deftest canonical-world-is-idempotent
  (let [c1 (diff/canonical-world world-a)
        c2 (diff/canonical-world c1)]
    (is (= c1 c2) "Applying canonical-world twice produces same result")))
