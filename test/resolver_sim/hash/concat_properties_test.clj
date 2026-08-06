(ns resolver-sim.hash.concat-properties-test
  "Decodeability and concatenation-framing property tests for canonical hashing.

   Verifies the strongest form of the consecutive-concatenation invariant from
   CANONICAL_HASH_SPEC_V1 §9:

     Decodeability invariant: a concatenated canonical byte stream determines
     one and only one ordered sequence of canonical components.

   Because canonical encodings are type-tagged and length-prefixed (prefix-free),
   this is tested three independent ways:
     1. Round-trip:  decode(canonical-bytes(v)) == v
     2. Sequence:    decode(concat(encode(v1..vn))) == [v1..vn]
     3. Injectivity: distinct component sequences ⇒ distinct framed byte streams

   The decoder in this namespace is a *separate* implementation from the
   encoder, so the round-trip tests are not circular."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.sequence :as seq])
  (:import [java.util Arrays]))

(defn- bytes=
  [^bytes a ^bytes b]
  (Arrays/equals a b))

;; ──────────────────────────────────────────────────────────────────────────────
;; Decoder (independent re-implementation of the ABI)
;; ──────────────────────────────────────────────────────────────────────────────

(defn- read-varuint
  "Decode a LEB128 varuint starting at pos. Returns [value next-pos].
   Uses arbitrary-precision arithmetic so values whose ZigZag form exceeds
   64 bits (e.g. Long/MIN_VALUE) decode without host-width truncation."
  [^bytes ba pos]
  (loop [pos pos place (bigint 1) acc (bigint 0)]
    (let [b (bit-and (int (aget ba pos)) 0xFF)
          acc (+' acc (*' (bigint (bit-and b 0x7F)) place))]
      (if (zero? (bit-and b 0x80))
        [acc (inc pos)]
        (recur (inc pos) (*' place 128) acc)))))

(defn- zigzag-decode
  "Inverse of the encoder's ZigZag mapping: even→n/2, odd→-(n+1)/2."
  [n]
  (if (even? n)
    (quot n 2)
    (-' (quot (inc n) 2))))

(defn- decode-one
  "Decode a single canonical value at pos. Returns {:value v :next pos}."
  [^bytes ba pos]
  (let [tag (bit-and (int (aget ba pos)) 0xFF)
        pos (inc pos)]
    (case tag
      0x00 {:value nil :next pos}
      0x01 {:value false :next pos}
      0x02 {:value true :next pos}
      0x10 (let [[n pos] (read-varuint ba pos)]
             {:value (zigzag-decode n) :next pos})
      0x20 (let [[len pos] (read-varuint ba pos)]
             {:value (String. ba (int pos) (int len) "UTF-8") :next (+ pos len)})
      0x22 (let [[len pos] (read-varuint ba pos)
                 s (String. ba (int pos) (int len) "UTF-8")]
             {:value (keyword s) :next (+ pos len)})
      0x30 (let [[n pos] (read-varuint ba pos)
                 [vals pos] (loop [i 0 pos pos acc []]
                              (if (= i n)
                                [acc pos]
                                (let [{:keys [value next]} (decode-one ba pos)]
                                  (recur (inc i) next (conj acc value)))))]
             {:value (vec vals) :next pos})
      0x31 (let [[n pos] (read-varuint ba pos)
                 [m pos] (loop [i 0 pos pos acc {}]
                           (if (= i n)
                             [acc pos]
                             (let [{k :value pos1 :next} (decode-one ba pos)
                                   {v :value pos2 :next} (decode-one ba pos1)]
                               (recur (inc i) pos2 (assoc acc k v)))))]
             {:value m :next pos})
      (throw (ex-info "Unknown tag" {:tag tag :pos pos})))))

(defn- decode
  "Decode a single canonical value from its full byte encoding."
  [^bytes ba]
  (:value (decode-one ba 0)))

(defn- decode-stream
  "Decode an ordered sequence of consecutive canonical components."
  [^bytes ba]
  (loop [pos 0 acc []]
    (if (= pos (count ba))
      acc
      (let [{:keys [value next]} (decode-one ba pos)]
        (recur next (conj acc value))))))

(defn- concat-bytes
  "Consecutive byte-array concatenation."
  [bas]
  (let [total (reduce + (map count bas))
        out (byte-array total)]
    (loop [idx 0 bas bas]
      (when (seq bas)
        (System/arraycopy ^bytes (first bas) 0 out idx (count (first bas)))
        (recur (+ idx (count (first bas))) (rest bas))))
    out))

(defn- prefix?
  "True if byte-array a is a prefix of byte-array b (length-framing check)."
  [^bytes a ^bytes b]
  (and (<= (count a) (count b))
       (loop [i 0]
         (if (= i (count a))
           true
           (and (= (bit-and (int (aget a i)) 0xFF)
                   (bit-and (int (aget b i)) 0xFF))
                (recur (inc i)))))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Generators
;; ──────────────────────────────────────────────────────────────────────────────

(def gen-key
  "Map keys are restricted to String or Keyword."
  (gen/one-of [gen/string-alpha-numeric gen/keyword gen/keyword-ns]))

(def gen-scalar
  (gen/one-of [(gen/return nil)
               gen/boolean
               (gen/one-of [gen/large-integer (gen/fmap bigint gen/large-integer)])
               gen/string-alpha-numeric
               ;; multibyte UTF-8 strings: é, п, 中, space
               (gen/fmap #(apply str %)
                         (gen/vector (gen/elements (map char [0x61 0xE9 0x043F 0x4E2D 0x20])) 0 8))
               gen/keyword
               gen/keyword-ns]))

(def gen-value
  (gen/recursive-gen
   (fn [inner]
     (gen/one-of [gen-scalar
                  (gen/vector inner 0 8)
                  (gen/map gen-key inner)]))
   gen-scalar))

;; ──────────────────────────────────────────────────────────────────────────────
;; Decoder conformance (independent, non-circular)
;; ──────────────────────────────────────────────────────────────────────────────

(deftest test-decoder-hand-parsed-vectors
  (testing "decoder matches hand-computed encodings"
    (is (= 1 (decode (hc/canonical-bytes 1))))
    (is (= -1 (decode (hc/canonical-bytes -1))))
    (is (= "active" (decode (hc/canonical-bytes "active"))))
    (is (= :active (decode (hc/canonical-bytes :active))))
    (is (= :a/b (decode (hc/canonical-bytes :a/b))))
    (is (= [1 "two" :three] (decode (hc/canonical-bytes [1 "two" :three]))))
    (is (= {:a 1} (decode (hc/canonical-bytes {:a 1}))))
    (is (= {:b 2 :a 1} (decode (hc/canonical-bytes {:a 1 :b 2})))))
  (testing "decoder round-trips exact hand-built hex"
    (is (= {:a 1} (decode (byte-array (map #(Integer/parseInt % 16) (re-seq #".." "31012201611002"))))))
    (is (= [1 2 3] (decode (byte-array (map #(Integer/parseInt % 16) (re-seq #".." "3003100210041006"))))))
    (is (= 128 (decode (byte-array (map #(Integer/parseInt % 16) (re-seq #".." "108002"))))))))

(deftest test-decoder-arbitrary-precision
  (testing "decoder recovers huge bigints and Long extremes"
    (doseq [n [(bigint (bit-shift-left 1 200))
               (bigint (inc (bit-shift-left 1 200)))
               (bigint (- (bit-shift-left 1 200)))
               Long/MAX_VALUE Long/MIN_VALUE]]
      (is (= n (decode (hc/canonical-bytes n))) (str n)))))

(deftest test-decoder-utf8
  (testing "decoder recovers multibyte and NUL-containing strings"
    (doseq [s ["\u043F\u0440\u0438\u0432\u0435\u0442"
               (str "a" (String. (Character/toChars 0x1F600)) "b")
               "e\u0301e\u0300" "\u4E2D\u6587"
               (str "a" (char 0) "b")]]
      (is (= s (decode (hc/canonical-bytes s))) (pr-str s)))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Decodeability / framing properties
;; ──────────────────────────────────────────────────────────────────────────────

(def prop-roundtrip
  "decode(canonical-bytes(v)) == v for all canonical-safe values."
  (prop/for-all [v gen-value]
                (= v (decode (hc/canonical-bytes v)))))

(def prop-sequence-decode
  "A concatenated stream of component encodings decodes to exactly one sequence."
  (prop/for-all [s (gen/vector gen-value 0 20)]
                (= s (decode-stream (concat-bytes (map hc/canonical-bytes s))))))

(def prop-distinct-sequences
  "Distinct component sequences ⇒ distinct framed byte streams (injectivity)."
  (prop/for-all [[s1 s2] (gen/tuple (gen/vector gen-value 0 10)
                                    (gen/vector gen-value 0 10))]
                (let [b1 (concat-bytes (map hc/canonical-bytes s1))
                      b2 (concat-bytes (map hc/canonical-bytes s2))]
                  (if (= s1 s2)
                    (bytes= b1 b2)
                    (not (bytes= b1 b2))))))

(def prop-prefix-free
  "No canonical encoding is a proper prefix of another (unambiguous framing)."
  (prop/for-all [pairs (gen/vector (gen/tuple gen-value gen-value) 1 50)]
                (every?
                 (fn [[a b]]
                   (let [ba (hc/canonical-bytes a)
                         bb (hc/canonical-bytes b)]
                     (if (bytes= ba bb)
                       true
                       (and (not (prefix? ba bb)) (not (prefix? bb ba))))))
                 pairs)))

(def prop-framing
  "A vector's framing is never the same as the naive concatenation of its parts."
  (prop/for-all [s (gen/vector gen-value 0 10)]
                (not (bytes= (hc/canonical-bytes s)
                             (concat-bytes (map hc/canonical-bytes s))))))

(def prop-consecutive-injective
  "Decisive injectivity of consecutive concatenation:

     (bytes= (encode-consecutive xs) (encode-consecutive ys))  ⇒  (= xs ys)

   because the stream is prefix-free and self-delimiting.  Sequences of
   different lengths are generated freely, so [a b] vs [c], [a] vs [b c],
   and [] vs [v] collisions are exercised by the generator."
  (prop/for-all [xs (gen/vector gen-value 0 15)
                 ys (gen/vector gen-value 0 15)]
                (let [equal-bytes? (bytes= (concat-bytes (map hc/canonical-bytes xs))
                                           (concat-bytes (map hc/canonical-bytes ys)))]
                  (if equal-bytes?
                    (= xs ys)
                    (not= xs ys)))))

(deftest test-injectivity-edge-cases
  (testing "[a b] can never collide with a single-component stream [c]"
    (is (not (bytes= (concat-bytes (map hc/canonical-bytes [1 2]))
                     (concat-bytes (map hc/canonical-bytes [3]))))))
  (testing "[a] can never collide with [b c]"
    (is (not (bytes= (concat-bytes (map hc/canonical-bytes [1]))
                     (concat-bytes (map hc/canonical-bytes [2 3]))))))
  (testing "the empty sequence can never collide with any non-empty sequence"
    (is (not (bytes= (concat-bytes [])
                     (concat-bytes (map hc/canonical-bytes [nil]))))))
  (testing "equal sequences produce equal streams"
    (is (bytes= (concat-bytes (map hc/canonical-bytes ["a" 1 :b]))
                (concat-bytes (map hc/canonical-bytes ["a" 1 :b]))))))

(def prop-typed-injectivity
  "Typed injectivity over the ACCEPTED canonical domain:

     (= x y)  ⟺  (bytes= (encode x) (encode y))

   Clojure = restricted to canonical values IS the typed equality used here:
   integer widths (1 and 1N) are declared equivalent; vectors/maps compare
   structurally; keywords are distinct from strings.  Sets, seqs, records,
   map entries, and temporal values are outside the domain and can never
   reach the encoder, so they cannot defeat the equality (see the rejection
   tests below)."
  (prop/for-all [[x y] (gen/tuple gen-value gen-value)]
                (let [same? (= x y)
                      same-bytes? (bytes= (hc/canonical-bytes x)
                                          (hc/canonical-bytes y))]
                  (= same? same-bytes?))))

(deftest test-typed-injectivity-edges
  (testing "integer widths are equivalent canonical integers"
    (is (bytes= (hc/canonical-bytes 1) (hc/canonical-bytes (bigint 1))))
    (is (bytes= (hc/canonical-bytes 1) (hc/canonical-bytes (java.math.BigInteger. "1")))))
  (testing "keywords are distinct from strings"
    (is (not (bytes= (hc/canonical-bytes :active) (hc/canonical-bytes "active")))))
  (testing "distinct vectors and maps produce distinct bytes"
    (is (not (bytes= (hc/canonical-bytes [1 2]) (hc/canonical-bytes [2 1]))))
    (is (not (bytes= (hc/canonical-bytes {:a 1}) (hc/canonical-bytes {:a 2}))))
    (is (not (bytes= (hc/canonical-bytes {:a 1}) (hc/canonical-bytes {:b 1}))))))

(def out-of-domain-samples
  "One representative value per out-of-domain host type."
  [[#(hash-set :a :b) "IPersistentSet"]
   [(fn [] (list 1 2)) "ISeq"]
   [(fn [] (first {:a 1})) "IMapEntry"]
   [(fn [] (java.time.Instant/ofEpochSecond 0)) "TemporalAccessor"]])

(deftest test-out-of-domain-rejection-everywhere
  (testing "every public commitment boundary rejects out-of-domain values with
            a structured error — no value outside the canonical algebra
            produces commitment bytes"
    (let [rejects? (fn [f x]
                     (try (f x) false (catch clojure.lang.ExceptionInfo e
                                        (= :canonical/out-of-domain (:type (ex-data e))))))]
      (doseq [[make _] out-of-domain-samples]
        (let [x (make)]
          (is (rejects? hc/canonical-bytes x)
              (str "canonical-bytes rejects " (type x)))
          (is (rejects? hc/validate-canonical-value! x)
              (str "validate-canonical-value! rejects " (type x)))
          (is (rejects? #(seq/sequence-hash {:purpose :p} [%]) x)
              (str "sequence-hash rejects " (type x))))))))

(deftest test-recursive-rejection-reports-nested-path
  (let [nested {:a [1 {:b #{"in"}}]}]
    (try
      (hc/validate-canonical-value! nested)
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (let [d (ex-data e)]
          (is (= :canonical/out-of-domain (:type d)))
          (is (= [:a :vector :b :value] (vec (:path d)))
              "the exact nested path of the offending set is reported"))))))

(deftest test-decodeability-properties
  (doseq [[name p] {:roundtrip prop-roundtrip
                    :sequence-decode prop-sequence-decode
                    :distinct-sequences prop-distinct-sequences
                    :prefix-free prop-prefix-free
                    :framing prop-framing
                    :consecutive-injective prop-consecutive-injective
                    :typed-injectivity prop-typed-injectivity}]
    (let [result (tc/quick-check 200 p)]
      (is (:pass? result)
          (str name " failed: " (pr-str (select-keys result [:num-tests :shrunk :fail]))))
      (is (pos? (:num-tests result))))))
