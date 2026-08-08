(ns resolver-sim.hash.round-trip-test
  "Tests for the purpose-neutral canonical fixed-point primitive
   (resolver-sim.hash.round-trip)."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.framing-view :as fv]
            [resolver-sim.hash.round-trip :as rt]))

(defn- bytes=
  [^bytes a ^bytes b]
  (java.util.Arrays/equals a b))

(deftest canonical-round-trip-valid-for-canonical-value
  (testing "a canonical map round-trips as valid and exposes the decoded value"
    (let [v {:authority-status :authorised
             :outcome-source :authoritative-target
             :counted-support 2
             :valid-supporting-positions [{:researcher/id "a"} {:researcher/id "b"}]}
          r (rt/canonical-round-trip v)]
      (is (true? (:valid? r)))
      (is (empty? (:issues r)))
      (is (map? (:value r)))
      (testing "re-encoding the decoded value reproduces the original bytes"
        (is (bytes= (hc/canonical-bytes v)
                    (hc/canonical-bytes (:value r))))))))

(deftest canonical-round-trip-exposes-decoded-value-not-full-comparison
  (testing "the primitive does NOT compare full values (decode widens ints):
            it returns the decoded value for the caller to project/compare"
    (let [v {:counted-support 2 :status "ok"}
          r (rt/canonical-round-trip v)]
      (is (true? (:valid? r)))
      (is (= 2N (:counted-support (:value r)))
          "Long 2 decodes as BigInt 2N — full-value equality would false-fail"))))

(deftest canonical-round-trip-valid-for-scalars-and-vectors
  (doseq [v [nil false true 42 "text" :kw [1 2 3] {:a 1 :b [2 3]}]]
    (let [r (rt/canonical-round-trip v)]
      (is (true? (:valid? r)) (pr-str v))
      (is (empty? (:issues r)) (pr-str v)))))

(deftest canonical-round-trip-rejects-trailing-bytes
  (testing "a stream with trailing bytes is not a canonical single value"
    ;; encode a value then append a stray byte — verify-single must reject it
    (let [ba (fv/concat-bytes [(hc/canonical-bytes {:a 1})
                               (byte-array [(byte 0x00)])])]
      (is (false? (:canonical? (fv/verify-single ba))))
      (is (false? (:single? (fv/verify-single ba)))))))

(deftest canonical-round-trip-rejects-noncanonical-encoding
  (testing "non-minimal varints and noncanonical map ordering surface as issues"
    (let [non-minimal (byte-array [0x10 0x80 0x00])  ;; integer 0 in 2 bytes
          noncanonical-map (byte-array [0x31 0x02 0x22 0x01 0x62 0x10 0x02
                                        0x22 0x01 0x61 0x10 0x01])
          vs (fv/verify-single non-minimal)
          vm (fv/verify-single noncanonical-map)]
      (is (false? (:canonical? vs)))
      (is (some #(= :non-minimal-varint (:code %)) (:issues vs)))
      (is (false? (:canonical? vm)))
      (is (some #(= :noncanonical-map-order (:code %)) (:issues vm))))))

(deftest canonical-round-trip-single-value-is-fully-consumed
  (testing "a single value is a canonical single component (no trailing bytes)"
    (let [r (rt/canonical-round-trip {:a 1 :b [2 3]})]
      (is (true? (:valid? r)))
      (is (map? (:value r)))
      (is (= {:a 1 :b [2 3]} (:value r))))))

(deftest canonical-round-trip-rejects-two-component-stream
  (testing "a stream of two canonical values is not a canonical single value —
            the boundary enforces :single?"
    (let [two (fv/concat-bytes [(hc/canonical-bytes 1) (hc/canonical-bytes 2)])
          vs (fv/verify-single two)]
      (is (false? (:canonical? vs)))
      (is (false? (:single? vs))))))
