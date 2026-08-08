(ns resolver-sim.hash.sequence-test
  "Tests for the named canonical-value-sequence.v1 framing contract.

   Bare consecutive concatenation is prefix-free but *unbound*: the same
   parseable byte stream can be interpreted as different protocol objects.
   The named contract binds every commitment to a purpose, version, and
   component structure, and keeps the canonical vector encoding distinct from
   the sequence framing."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.sequence :as seq]
            [resolver-sim.hash.framing-view :as fv]))

(defn- bytes= [^bytes a ^bytes b]
  (java.util.Arrays/equals a b))

(deftest bound-sequence-carries-the-contract
  (let [b (seq/bound-sequence {:purpose :evidence-content} [1 "a" :b])]
    (is (= seq/sequence-contract (:encoding-contract b)))
    (is (= :evidence-content (:purpose b)))
    (is (= 3 (:component-count b)))
    (is (= [1 "a" :b] (:components b)))))

(deftest bound-sequence-requires-purpose
  (is (thrown? clojure.lang.ExceptionInfo
               (seq/canonical-sequence-bytes {} [1]))))

(deftest bound-sequence-validates-expected-count
  (is (thrown? clojure.lang.ExceptionInfo
               (seq/canonical-sequence-bytes
                {:purpose :x :expected-component-count 2} [1 2 3]))))

(deftest bound-sequence-rejects-nil-components
  (is (thrown? clojure.lang.ExceptionInfo
               (seq/bound-sequence {:purpose :x} nil))
      "nil must not silently commit as the empty sequence (nil ≡ [])"))

(deftest bound-sequence-rejects-non-sequential-components
  (doseq [bad ["ab" {:k 1} 42 :kw]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (seq/bound-sequence {:purpose :x} bad))
        (str "non-sequential components must be rejected: " (pr-str bad)))))

(deftest bound-sequence-validates-expected-count-type
  (is (thrown? clojure.lang.ExceptionInfo
               (seq/bound-sequence {:purpose :x :expected-component-count "2"} [1 2]))
      "expected-component-count must be an integer")
  (is (thrown? clojure.lang.ExceptionInfo
               (seq/bound-sequence {:purpose :x :expected-component-count -1} []))
      "expected-component-count must be non-negative"))

(deftest bound-sequence-allows-explicit-empty-sequence
  (let [b (seq/bound-sequence {:purpose :x :expected-component-count 0} [])]
    (is (= 0 (:component-count b)))
    (is (= [] (:components b)))))

(deftest purpose-is-domain-separated
  (testing "different purposes never collide"
    (is (not (bytes= (seq/canonical-sequence-bytes {:purpose :a} [1 2])
                     (seq/canonical-sequence-bytes {:purpose :b} [1 2]))))
    (is (not= (seq/sequence-hash {:purpose :a} [1 2])
              (seq/sequence-hash {:purpose :b} [1 2])))))

(deftest component-count-is-bound
  (is (not (bytes= (seq/canonical-sequence-bytes {:purpose :a} [1])
                   (seq/canonical-sequence-bytes {:purpose :a} [1 1]))))
  (is (not= (seq/sequence-hash {:purpose :a} [1])
            (seq/sequence-hash {:purpose :a} [1 1]))))

(deftest same-input-same-commitment
  (is (= (seq/sequence-hash {:purpose :a} [1 "x" :k])
         (seq/sequence-hash {:purpose :a} [1 "x" :k])))
  (is (bytes= (seq/canonical-sequence-bytes {:purpose :a} [1 "x" :k])
              (seq/canonical-sequence-bytes {:purpose :a} [1 "x" :k]))))

(deftest sequence-hash-is-domain-separated-from-bare-concat
  (let [bare-hex (apply str (map #(format "%02x" (bit-and % 0xff))
                                 (hc/hash-bytes (seq/encode-sequence [1 2]))))]
    (is (not= (seq/sequence-hash {:purpose :a} [1 2]) bare-hex))
    (is (not= (seq/sequence-hash {:purpose :a} [1 2])
              (apply str (map #(format "%02x" (bit-and % 0xff))
                              (hc/hash-bytes (hc/canonical-bytes [1 2])))))
        "and differs from hashing the same values as a vector")))

(deftest encode-sequence-is-bare-concatenation
  (testing "encode-sequence is exactly the consecutive concatenation"
    (is (bytes= (seq/encode-sequence [1 "a" :b])
                (fv/concat-bytes (map hc/canonical-bytes [1 "a" :b]))))))

(deftest vector-encoding-is-distinct-from-sequence-framing
  (testing "canonical-bytes of a vector ≠ encode-sequence of its parts"
    (is (not (bytes= (hc/canonical-bytes [1 2 3])
                     (seq/encode-sequence [1 2 3]))))
    (is (not (bytes= (hc/canonical-bytes [1 2 3])
                     (seq/canonical-sequence-bytes {:purpose :a} [1 2 3])))
        "the bound sequence is likewise distinct from a vector value")
    (is (not (bytes= (hc/canonical-bytes [1 2 3])
                     (hc/canonical-bytes {:encoding-contract seq/sequence-contract
                                          :purpose :a :component-count 3
                                          :components [1 2 3]}))))))

(deftest bound-sequence-verifies-as-a-single-canonical-value
  (let [ba (seq/canonical-sequence-bytes {:purpose :a} [1 "x"])
        v (fv/verify-single ba)]
    (is (:canonical? v))
    (is (:single? v))))

(deftest sequence-commitment-decodes-back
  (let [ba (seq/canonical-sequence-bytes {:purpose :a} [1 "x" :k])
        decoded (fv/decode-one ba 0)
        m (:value decoded)]
    (is (= :map (:tag-name decoded)))
    (is (= seq/sequence-contract (:encoding-contract m)))
    (is (= :a (:purpose m)))
    (is (= 3 (:component-count m)))
    (is (= [1 "x" :k] (:components m)))))

(deftest sequence-domain-tag-registered
  (is (= "CANONICAL_VALUE_SEQUENCE_V1"
         (get hc/domain-tags :canonical-value-sequence))))

;; ── Commitment fixed-point verification ────────────────────────────────────

(deftest verify-sequence-commitment-accepts-genuine-commitment
  (let [ba (seq/canonical-sequence-bytes {:purpose :evidence-content} [1 "a" :b])
        r (seq/verify-sequence-commitment ba)]
    (is (:valid? r))
    (is (= :evidence-content (:purpose (:value r))))
    (is (= 3 (:component-count (:value r))))
    (is (= [1 "a" :b] (:components (:value r))))
    (is (empty? (:errors r)))))

(deftest verify-sequence-commitment-rejects-inconsistent-count
  (let [m {:encoding-contract seq/sequence-contract
           :purpose :a :component-count 2 :components [1]}
        r (seq/verify-sequence-commitment (hc/canonical-bytes m))]
    (is (not (:valid? r)))
    (is (some #(re-find #"does not match" %) (:errors r)))))

(deftest verify-sequence-commitment-rejects-non-keyword-purpose
  (let [m {:encoding-contract seq/sequence-contract
           :purpose "a" :component-count 1 :components [1]}
        r (seq/verify-sequence-commitment (hc/canonical-bytes m))]
    (is (not (:valid? r)))
    (is (some #(re-find #"purpose must be a keyword" %) (:errors r)))))

(deftest verify-sequence-commitment-rejects-wrong-contract
  (let [m {:encoding-contract "some-other-contract"
           :purpose :a :component-count 1 :components [1]}
        r (seq/verify-sequence-commitment (hc/canonical-bytes m))]
    (is (not (:valid? r)))
    (is (some #(re-find #"encoding-contract" %) (:errors r)))))

(deftest verify-sequence-commitment-rejects-non-map
  (let [r (seq/verify-sequence-commitment (hc/canonical-bytes [1 2 3]))]
    (is (not (:valid? r)))
    (is (some #(re-find #"not a map" %) (:errors r)))))

(deftest verify-sequence-commitment-rejects-extra-key
  (let [m {:encoding-contract seq/sequence-contract
           :purpose :a :component-count 1 :components [1] :evil :x}
        r (seq/verify-sequence-commitment (hc/canonical-bytes m))]
    (is (not (:valid? r)))
    (is (some #(re-find #"not a fixed point" %) (:errors r)))))

(deftest verify-sequence-commitment-rejects-trailing-bytes
  (let [ba (seq/canonical-sequence-bytes {:purpose :a} [1 2])
        trailing (byte-array (conj (vec ba) 0x00))
        r (seq/verify-sequence-commitment trailing)]
    (is (not (:valid? r)))
    (is (some #(re-find #"trailing byte" %) (:errors r)))))

(deftest verify-sequence-commitment-rejects-non-canonical-encoding
  (let [ba (seq/canonical-sequence-bytes {:purpose :a} [1])
        idx (loop [i 0]
              (if (and (= 0x10 (get ba i)) (= 0x02 (get ba (inc i))))
                i
                (recur (inc i))))
        ;; a non-minimal varint for the component-count: 0x10 0x82 0x00
        noncanonical (byte-array (concat (take idx ba) [0x10 0x82 0x00]
                                         (drop (+ idx 2) ba)))
        r (seq/verify-sequence-commitment noncanonical)]
    (is (not (:valid? r)))
    (is (some #(re-find #"canonical fixed point" %) (:errors r)))))

(deftest verify-sequence-commitment-classifies-resource-rejection
  (testing "a commitment that exceeds the decoder's collection-depth is
            classified as inadmissible (resource policy), not malformed"
    (let [deep (loop [i 0 v 1] (if (< i 66) (recur (inc i) [v]) v))
          ba (seq/canonical-sequence-bytes {:purpose :a} [deep])
          r (seq/verify-sequence-commitment ba)]
      (is (not (:valid? r)))
      (is (some #(re-find #"inadmissible under the admission profile" %) (:errors r)))
      (is (some #(re-find #"limit-exceeded" %) (:errors r)))
      (is (not-any? #(re-find #"decode failed" %) (:errors r))
          "resource rejection must not be misreported as a decode failure"))))

(deftest verify-sequence-commitment-rejects-over-stream-limit
  (testing "a genuine commitment whose bytes exceed :max-stream-bytes is
            inadmissible (resource policy), exactly as verify-stream rejects
            it — decode-one and verify-stream share one admission profile"
    (let [ba (seq/canonical-sequence-bytes {:purpose :a}
               [{:a (apply str (repeat 700000 \a))}
                {:b (apply str (repeat 700000 \b))}])
          r (seq/verify-sequence-commitment ba)]
      (is (> (count ba) (:max-stream-bytes fv/default-limits))
          "the commitment is genuinely over the stream bound")
      (is (not (:valid? r)))
      (is (some #(re-find #"inadmissible under the admission profile" %) (:errors r)))
      (is (some #(re-find #"max-stream-bytes" %) (:errors r)))
      (is (not-any? #(re-find #"decode failed" %) (:errors r))
          "resource rejection must not be misreported as a decode failure"))))

(deftest verify-sequence-commitment-round-trips-hash
  (let [ba (seq/canonical-sequence-bytes {:purpose :a} [1 "x" :k])
        r (seq/verify-sequence-commitment ba)]
    (is (:valid? r))
    (is (bytes= ba (hc/canonical-bytes (:value r)))
        "decode∘encode is the identity on a genuine commitment")))

;; ── Component-type binding: no silent set/list coercion ─────────────────────
;;
;; canonical-bytes normalizes a set to a sorted vector (§11.2 projection), so
;; #{1 2} and [1 2] produce identical bytes there.  A sequence commitment must
;; NOT inherit that coercion — a set component would collide with a
;; same-element sorted vector component and defeat the component-structure
;; binding.  The contract therefore rejects non-canonical components.

(deftest sequence-rejects-set-component
  (testing "a set component is rejected, not silently projected to a vector"
    (is (thrown? clojure.lang.ExceptionInfo
                 (seq/canonical-sequence-bytes {:purpose :p} [#{1 2}])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (seq/sequence-hash {:purpose :p} [#{1 2}]))))
  (testing "the equivalent explicitly-projected sorted vector IS acceptable"
    (is (string? (seq/sequence-hash {:purpose :p} [[1 2]])))
    (is (bytes= (seq/canonical-sequence-bytes {:purpose :p} [[1 2]])
                (seq/canonical-sequence-bytes {:purpose :p} [[1 2]])))))

(deftest sequence-rejects-list-component
  (testing "a list/seq component is rejected (lists are not canonical values)"
    (is (thrown? clojure.lang.ExceptionInfo
                 (seq/canonical-sequence-bytes {:purpose :p} [(list 1 2)])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (seq/sequence-hash {:purpose :p} [(list 1 2)])))))

(deftest sequence-rejects-non-canonical-scalar-components
  (testing "ratios and other non-canonical values are rejected as components"
    (is (thrown? clojure.lang.ExceptionInfo
                 (seq/canonical-sequence-bytes {:purpose :p} [(/ 1 3)])))))

(deftest set-vs-vector-collision-cannot-enter-a-commitment
  (testing "because sets are rejected, #{1 2} and [1 2] cannot collide inside a
            sequence commitment — the caller must project explicitly"
    (is (not= (seq/sequence-hash {:purpose :p} [[1 2]])
              (seq/sequence-hash {:purpose :p} [[1 2 3]])))))
