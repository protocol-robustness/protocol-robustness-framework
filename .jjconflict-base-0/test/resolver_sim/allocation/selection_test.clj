(ns resolver-sim.allocation.selection-test
  "Tests for the :domain-hash-rejection-v1 selection algorithm."
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.allocation.context :as context]
            [resolver-sim.allocation.selection :as selection]))

(defn- rnd [n]
  (context/bytes->byte-ints
   (context/hex->bytes (format "0x%064x" n))))

(deftest selection-requires-positive-outcome-count
  (is (thrown? clojure.lang.ExceptionInfo (selection/select-index (rnd 0) 0)))
  (is (thrown? clojure.lang.ExceptionInfo (selection/select-index (rnd 0) -1))))

(deftest selection-index-in-range
  (doseq [n (range 1 20)]
    (let [receipt (selection/select-index (rnd 42) n)]
      (is (<= 0 (:selected-index receipt)))
      (is (< (:selected-index receipt) n)))))

(deftest selection-accepts-counter-zero-when-no-rejection
  (let [receipt (selection/select-index (rnd 7) 8)]
    (is (= 0 (:accepted-counter receipt)))
    (is (= 64 (count (:candidate-digest receipt))))
    (is (re-matches #"[0-9a-f]{64}" (:candidate-digest receipt)))))

(deftest selection-rejection-sampling-retries
  ;; n = 3 has M mod n = 1, so candidate == 2^256 - 1 is rejected; force it for
  ;; counter 0 and confirm the loop advances to counter 1.
  (with-redefs [selection/candidate-digest-hex
                (fn [_rand counter _n]
                  (if (zero? counter)
                    (str "0x" (apply str (repeat 64 "f")))
                    (str "0x" (apply str (repeat 63 "0")) "1")))]
    (let [receipt (selection/select-index (rnd 0) 3)]
      (is (= 1 (:accepted-counter receipt)))
      (is (= (mod (selection/digest->big-int
                   (str "0x" (apply str (repeat 63 "0")) "1")) 3)
             (:selected-index receipt))))))

(deftest selection-candidate-value-is-unsigned-big-endian
  (let [hex "0x0000000000000000000000000000000000000000000000000000000000000001"
        value (selection/digest->big-int hex)]
    (is (= 1 value)))
  (let [hex "0x00000000000000000000000000000000000000000000000000000000000000ff"
        value (selection/digest->big-int hex)]
    (is (= 255 value))))

(deftest selection-candidate-below-limit-never-rejects
  (doseq [i (range 32)]
    (let [receipt (selection/select-index (rnd i) 2)]
      (is (= 0 (:accepted-counter receipt))))))

(deftest selection-deterministic
  (is (= (selection/select-index (rnd 5) 7)
         (selection/select-index (rnd 5) 7)))
  (is (not= (selection/select-index (rnd 5) 7)
            (selection/select-index (rnd 6) 7))))
