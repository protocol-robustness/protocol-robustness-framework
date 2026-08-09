(ns resolver-sim.hash.canonical-test
  (:require [clojure.test :refer :all]
            [clojure.data.json :as json]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.economics.payoffs :as payoffs])
  (:import [java.util Arrays]
           [java.security MessageDigest]
           [java.time Instant]))

(defn bytes= [a b]
  (Arrays/equals a b))

(defn- to-hex
  "Byte array → lowercase hex string."
  [^bytes ba]
  (apply str (map #(format "%02x" (bit-and % 0xFF)) ba)))

;; ──────────────────────────────────────────────────────────────────────────────
;; Primitives
;; ──────────────────────────────────────────────────────────────────────────────

(deftest test-null
  (is (bytes= (byte-array [0x00]) (hc/canonical-bytes nil))))

(deftest test-booleans
  (is (bytes= (byte-array [0x02]) (hc/canonical-bytes true)))
  (is (bytes= (byte-array [0x01]) (hc/canonical-bytes false))))

(deftest test-integers
  (is (bytes= (byte-array [0x10 0x00]) (hc/canonical-bytes 0)))
  (is (bytes= (byte-array [0x10 0x02]) (hc/canonical-bytes 1)))
  (is (bytes= (byte-array [0x10 0x01]) (hc/canonical-bytes -1)))
  (is (bytes= (byte-array [0x10 (unchecked-byte 0xF6) 0x01]) (hc/canonical-bytes 123)))
  (is (bytes= (byte-array [0x10 (unchecked-byte 0xF5) 0x01]) (hc/canonical-bytes -123))))

(deftest test-rejects-lossy-numeric-types
  (testing "canonical hashing must not alias non-integral numeric values"
    (is (thrown? Exception (hc/canonical-bytes 1.0)))
    (is (thrown? Exception (hc/canonical-bytes 1.9)))
    (is (thrown? Exception (hc/canonical-bytes (/ 1 3))))))

(deftest test-evidence-content-projects-floating-point-values-exactly
  (let [hash-content #(hc/hash-with-intent {:hash/intent :evidence-content} %)]
    (testing "content hashing accepts projected IEEE floating-point values"
      (is (= (hash-content {:index 1.0})
             (hash-content {:index 1.0}))))
    (testing "the tagged floating-point representation preserves numeric type"
      (is (not= (hash-content {:index 1.0})
                (hash-content {:index (float 1.0)}))))))

(deftest test-evidence-content-preserves-keyword-namespaces
  (let [hash-content #(hc/hash-with-intent {:hash/intent :evidence-content} %)]
    (testing "namespaced and bare keywords must NOT collide in content hashing"
      (is (not= (hash-content {:a/b 1})
                (hash-content {:b 1})))
      (is (not= (hash-content {:foo/seq 1})
                (hash-content {:bar/seq 1})))
      (is (not= (hash-content {:k :a/b})
                (hash-content {:k :b})))
      (is (not= (hash-content {:k [:x/y :x/z]})
                (hash-content {:k [:y :z]}))))
    (testing "keyword-to-string JSON round-trip equivalence is preserved"
      (is (= (hash-content {:a 1})
             (hash-content {"a" 1})))
      (is (= (hash-content {:a/b 1})
             (hash-content {"a/b" 1}))))))

(deftest test-concat-utf8-byte-length-prefix
  (testing "string length prefix counts UTF-8 bytes, not characters"
    (let [emoji (str "a\u1F600" "b")
          nul (str "a" (char 0) "b")
          emoji-bytes (hc/canonical-bytes emoji)
          nul-bytes (hc/canonical-bytes nul)]
      ;; "a" (1) + emoji (4) + "b" (1) = 6 UTF-8 bytes
      (is (= 6 (bit-and (int (aget emoji-bytes 1)) 0xFF)))
      ;; 1 + 1 + 1 = 3 UTF-8 bytes
      (is (= 3 (bit-and (int (aget nul-bytes 1)) 0xFF)))
      (is (bytes= emoji-bytes (hc/canonical-bytes emoji)) "deterministic")
      (is (bytes= nul-bytes (hc/canonical-bytes nul)) "deterministic"))))

(deftest test-concat-multibyte-varuint-boundaries
  (testing "varuint multi-byte boundaries encode with minimal LEB128 length"
    (is (= "10feff01"     (to-hex (hc/canonical-bytes 16383))))
    (is (= "10808002"     (to-hex (hc/canonical-bytes 16384))))
    (is (= "10feffff01"   (to-hex (hc/canonical-bytes 2097151))))
    (is (= "1080808002"   (to-hex (hc/canonical-bytes 2097152)))))
  (testing "arbitrary-precision integers beyond long range are distinct"
    (let [a (bigint (bit-shift-left 1 100))
          b (bigint (inc (bit-shift-left 1 100)))]
      (is (not (bytes= (hc/canonical-bytes a) (hc/canonical-bytes b)))))))

(deftest test-concat-set-mixed-type-ordering
  (testing "sets are outside the canonical domain and rejected, not coerced"
    (is (thrown? Exception (hc/canonical-bytes #{:z :a "str" 5 10})))
    (is (thrown? Exception (hc/canonical-bytes #{:a :b}))))
  (testing "distinct projected sorted vectors remain distinct"
    (is (not (bytes= (hc/canonical-bytes [:a :b])
                     (hc/canonical-bytes [:a :b "c"]))))))

(deftest test-string
  (let [expected (byte-array [0x20 0x06 0x61 0x63 0x74 0x69 0x76 0x65])]
    (is (bytes= expected (hc/canonical-bytes "active")))))

(deftest test-keyword
  (let [expected (byte-array [0x22 0x06 0x61 0x63 0x74 0x69 0x76 0x65])]
    (is (bytes= expected (hc/canonical-bytes :active)))))

(deftest test-keyword-vs-string-distinct
  (is (not (bytes= (hc/canonical-bytes :active) (hc/canonical-bytes "active")))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Composites
;; ──────────────────────────────────────────────────────────────────────────────

(deftest test-empty-vector
  (is (bytes= (byte-array [0x30 0x00]) (hc/canonical-bytes []))))

(deftest test-empty-map
  (is (bytes= (byte-array [0x31 0x00]) (hc/canonical-bytes {}))))

(deftest test-vector-of-ints
  (is (bytes= (byte-array [0x30 0x03 0x10 0x02 0x10 0x04 0x10 0x06])
              (hc/canonical-bytes [1 2 3]))))

(deftest test-map-a-1
  (is (bytes= (byte-array [0x31 0x01 0x22 0x01 0x61 0x10 0x02])
              (hc/canonical-bytes {:a 1}))))

(deftest test-map-ordered-by-key
  (let [unordered {:b 2 :a 1}
        ordered (hc/canonical-bytes unordered)]
    (is (bytes= (byte-array [0x31 0x02
                             0x22 0x01 0x61 0x10 0x02
                             0x22 0x01 0x62 0x10 0x04])
                ordered))))

(deftest test-vector-hetero
  (let [bytes (hc/canonical-bytes [1 "two" :three])]
    (is (= 0x30 (aget bytes 0)))
    (is (= 3 (aget bytes 1)))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Consecutive Concatenation Coverage
;; ──────────────────────────────────────────────────────────────────────────────
;; Exercises the byte-concatenation model in CANONICAL_HASH_SPEC_V1 §8.4
;; (Concatenation Stress) and §9 (Critical Concatenation Edge Cases):
;;   - self-delimiting concatenation (no delimiter ambiguity)
;;   - boundary ambiguity protection  ("ab"+"c" vs "a"+"bc", mixed types)
;;   - empty-value collision guards
;;   - stress (long strings, many-element lists/maps, deep nesting)
;;   - varuint/ZigZag integer boundaries feeding concatenated output

(defn- varuint-byte-count
  "LEB128 length (in bytes) of a non-negative integer."
  [n]
  (loop [n n c 1]
    (if (< n 128) c (recur (quot n 128) (inc c)))))

(defn- read-varuint
  "Decode a LEB128 varuint from a byte array starting at offset."
  [^bytes ba offset]
  (loop [i offset shift 0 acc 0]
    (let [b (bit-and (int (aget ba i)) 0xFF)
          acc (bit-or acc (bit-shift-left (bit-and b 0x7F) shift))]
      (if (zero? (bit-and b 0x80))
        acc
        (recur (inc i) (+ shift 7) acc)))))

(deftest test-concat-boundary-ambiguity
  (testing "\"ab\"+\"c\" vs \"a\"+\"bc\" must differ (length-prefix delimiters)"
    (is (not (bytes= (hc/canonical-bytes ["ab" "c"])
                     (hc/canonical-bytes ["a" "bc"])))))
  (testing "mixed-type [\"1\", 1] vs [1, \"1\"] must differ (type tags)"
    (is (not (bytes= (hc/canonical-bytes ["1" 1])
                     (hc/canonical-bytes [1 "1"]))))))

(deftest test-concat-no-delimiter-required
  (testing "nested concatenation is self-delimiting: [\"a\" \"b\" \"c\"] groups differently"
    (is (not (bytes= (hc/canonical-bytes [["a" "b"] "c"])
                     (hc/canonical-bytes ["a" ["b" "c"]]))))))

(deftest test-concat-empty-value-collisions
  (testing "empty containers and empty primitives are mutually distinct"
    (is (not (bytes= (hc/canonical-bytes []) (hc/canonical-bytes {}))))
    (is (not (bytes= (hc/canonical-bytes []) (hc/canonical-bytes [nil]))))
    (is (not (bytes= (hc/canonical-bytes "") (hc/canonical-bytes []))))
    (is (not (bytes= (hc/canonical-bytes "") (hc/canonical-bytes [""]))))
    (is (not (bytes= (hc/canonical-bytes nil) (hc/canonical-bytes false))))
    (is (not (bytes= (hc/canonical-bytes nil) (hc/canonical-bytes ""))))))

(deftest test-concat-element-sensitivity
  (testing "same-length vectors differing by one element must differ"
    (is (not (bytes= (hc/canonical-bytes [1 2 3])
                     (hc/canonical-bytes [1 2 4])))))
  (testing "adding an element must change the concatenation"
    (is (not (bytes= (hc/canonical-bytes [1 2])
                     (hc/canonical-bytes [1 2 3])))))
  (testing "integer elements vs string elements with identical glyphs"
    (is (not (bytes= (hc/canonical-bytes [1 2])
                     (hc/canonical-bytes [1 "2"]))))))

(deftest test-concat-long-string-stress
  (testing "long strings (>10k bytes) encode with correct length prefix"
    (let [n 20000
          s (apply str (repeat n "x"))
          bytes (hc/canonical-bytes s)]
      (is (= (+ 1 (varuint-byte-count n) n) (count bytes)))
      (is (bytes= bytes (hc/canonical-bytes s)) "deterministic"))))

(deftest test-concat-large-vector-stress
  (testing "many-element lists (>10k items) are deterministic and bounded"
    (let [n 10000
          v (vec (range n))
          bytes (hc/canonical-bytes v)
          tampered (update v 5000 inc)]
      (is (bytes= bytes (hc/canonical-bytes v)) "deterministic")
      (is (not (bytes= bytes (hc/canonical-bytes tampered))) "single change propagates")
      (is (bytes= (hc/canonical-bytes [])
                  (byte-array [0x30 0x00])) "empty vector unaffected"))))

(deftest test-concat-many-entry-map-stress
  (testing "many-entry maps sort deterministically independent of insertion order"
    (let [n 2000
          ks (mapv #(str "key-" %) (range n))
          m (into {} (map (fn [k] [k (count k)]) ks))
          m-shuffled (into {} (shuffle (seq m)))
          b1 (hc/canonical-bytes m)
          b2 (hc/canonical-bytes m-shuffled)]
      (is (bytes= b1 b2) "order independence")
      (is (= n (read-varuint b1 1)) "count decodes to element count"))))

(deftest test-concat-deep-nesting
  (testing "deeply nested concatenation chains remain deterministic at practical depth"
    (let [deep (nth (iterate (fn [x] [x]) 1) 100)
          b1 (hc/canonical-bytes deep)
          b2 (hc/canonical-bytes deep)]
      (is (bytes= b1 b2))
      (is (> (count b1) 100))
      ;; Structural equivalence regardless of map vs vector at a given level
      (is (bytes= (hc/canonical-bytes {:a {:b {:c 1}}})
                  (hc/canonical-bytes {:a {:b {:c 1}}}))))))

(deftest test-concat-deep-nesting-stack-safe
  (testing "canonical-bytes is stack-safe for deep nesting (spec §8.4)"
    ;; Previously overflowed the JVM stack at ~200 levels; must now encode
    ;; arbitrarily deep chains without throwing StackOverflowError.
    (doseq [depth [1000 10000 50000]]
      (let [v (nth (iterate (fn [x] [x]) 1) depth)
            b1 (hc/canonical-bytes v)
            b2 (hc/canonical-bytes v)]
        (is (bytes= b1 b2) (str "depth " depth))
        (is (= (+ (* 2 depth) 2) (count b1)) (str "encoded length at depth " depth))))
    (testing "deep nesting hash determinism through domain-hash"
      (let [v (nth (iterate (fn [x] [x]) 10000) 1)]
        (is (= (hc/domain-hash :evidence-record v)
               (hc/domain-hash :evidence-record v)))))))

(deftest test-concat-set-vector-equivalence
  (testing "sets are rejected by the strict encoder (must be projected first)"
    (is (thrown? Exception (hc/canonical-bytes #{:a :b})))
    (is (thrown? Exception (hc/canonical-bytes #{:b :a}))))
  (testing "the explicit projection normalizes sets to sorted vectors"
    (is (= [:a :b]
           (get-in (hc/project-world-to-structure-view {:s #{:b :a}} :test)
                   [:structure :s]))))
  (testing "validate-canonical-value! rejects sets (must be projected first)"
    (is (thrown? Exception (hc/validate-canonical-value! #{:a :b})))))

(deftest test-concat-integer-boundaries
  (testing "varuint/ZigZag boundaries round-trip into concatenated byte stream"
    (is (= "1000" (to-hex (hc/canonical-bytes 0))))
    (is (= "1001" (to-hex (hc/canonical-bytes -1))))
    (is (= "10fe01" (to-hex (hc/canonical-bytes 127))))
    (is (= "108002" (to-hex (hc/canonical-bytes 128))))
    (is (= "10ff01" (to-hex (hc/canonical-bytes -128)))))
  (testing "large and extreme integers are distinct and non-degenerate"
    (let [s #{Long/MAX_VALUE Long/MIN_VALUE 0 1 -1 127 128}]
      (is (apply distinct? (map (comp to-hex hc/canonical-bytes) s))))
    (is (= "10feffffffffffffffff01"
           (to-hex (hc/canonical-bytes Long/MAX_VALUE))))))

(deftest test-concat-domain-hash-concatenation
  (testing "domain-hash = SHA256(domain_tag || canonical_bytes) is deterministic, 64-hex"
    (let [v {:nested [{:a [1 2 3] :b "long string here"}] :s "x"}
          h1 (hc/domain-hash :evidence-record v)
          h2 (hc/domain-hash :evidence-record v)]
      (is (= 64 (count h1)))
      (is (= h1 h2))
      (is (not= h1 (hc/domain-hash :registry v)) "domain tag separation"))))

(deftest test-concat-rejects-lossy-bigdecimal
  (testing "BigDecimal is not a canonical type and must be projected first"
    (is (thrown? Exception (hc/canonical-bytes (bigdec "1.5"))))
    (is (thrown? Exception (hc/validate-canonical-value! (bigdec "1.5"))))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Consecutive Concatenation Checklist (spec §8.4–§9)
;; ──────────────────────────────────────────────────────────────────────────────

(deftest test-concat-empty-vs-absent-segments
  (testing "empty collection and zero-length string are structurally distinct"
    (is (not (bytes= (hc/canonical-bytes []) (hc/canonical-bytes [""]))))
    (is (not (bytes= (hc/canonical-bytes []) (hc/canonical-bytes [nil])))))
  (testing "position of zero-length segments matters"
    (is (not (bytes= (hc/canonical-bytes ["" "a"])
                     (hc/canonical-bytes ["a" ""]))))
    (is (not (bytes= (hc/canonical-bytes [nil "a"])
                     (hc/canonical-bytes ["a" nil]))))
    (is (not (bytes= (hc/canonical-bytes [[] "a"])
                     (hc/canonical-bytes ["a" []]))))))

(deftest test-concat-utf8-multiscript-lengths
  (letfn [(len-prefix [s] (bit-and (int (aget (hc/canonical-bytes s) 1)) 0xFF))]
    (testing "length prefix counts UTF-8 encoded bytes across scripts"
      (is (= 5  (len-prefix "hello")))
      (is (= 6  (len-prefix (str "a" (String. (Character/toChars 0x1F600)) "b")))) ; emoji = 4 bytes
      (is (= 6  (len-prefix "e\u0301e\u0300")))                                  ; combining marks
      (is (= 12 (len-prefix "\u043F\u0440\u0438\u0432\u0435\u0442")))             ; Cyrillic
      (is (= 6  (len-prefix "\u4E2D\u6587")))                                    ; CJK
      (is (= 4  (len-prefix "\u00E9\u00FC")))                                    ; accented
      (is (= 10 (len-prefix "\u0645\u0631\u062D\u0628\u0627")))                   ; Arabic
      (is (= 3  (len-prefix (str "a" (char 0) "b")))))                           ; embedded NUL
    (testing "every multiscript string is deterministic"
      (doseq [s ["\u043F\u0440\u0438\u0432\u0435\u0442" "e\u0301" "\u4E2D\u6587"]]
        (is (bytes= (hc/canonical-bytes s) (hc/canonical-bytes s)))))))

(deftest test-concat-length-prefix-boundary-stress
  (letfn [(frame [^bytes ba] (to-hex (byte-array (take 5 ba))))]
    (testing "string length prefixes cross varuint boundaries"
      (doseq [n [127 128 16383 16384 2097151 2097152]
              :let [s (apply str (repeat n "x"))]]
        (is (= (+ 1 (varuint-byte-count n) n) (count (hc/canonical-bytes s)))
            (str "string len " n))))
    (testing "collection counts cross varuint boundaries"
      (is (str/starts-with? (frame (hc/canonical-bytes (vec (range 127)))) "307f"))
      (is (str/starts-with? (frame (hc/canonical-bytes (vec (range 128)))) "308001"))
      (is (str/starts-with? (frame (hc/canonical-bytes (vec (range 16384)))) "30808001"))
      (is (str/starts-with? (frame (hc/canonical-bytes (vec (range 2097152)))) "3080808001"))
      (is (str/starts-with? (frame (hc/canonical-bytes
                                    (into {} (map (fn [i] [(str "k" i) i]) (range 128)))))
                            "318001")))))

(deftest test-concat-type-separation
  (testing "adjacent values whose textual forms coincide remain type-distinct"
    (is (not (bytes= (hc/canonical-bytes [1])   (hc/canonical-bytes ["1"]))))
    (is (not (bytes= (hc/canonical-bytes [true]) (hc/canonical-bytes ["true"]))))
    (is (not (bytes= (hc/canonical-bytes [false]) (hc/canonical-bytes ["false"]))))
    (is (not (bytes= (hc/canonical-bytes [nil]) (hc/canonical-bytes [""]))))
    (is (not (bytes= (hc/canonical-bytes [:a])  (hc/canonical-bytes ["a"]))))
    (is (not (bytes= (hc/canonical-bytes [true]) (hc/canonical-bytes [1]))))
    (is (not (bytes= (hc/canonical-bytes [false]) (hc/canonical-bytes [0])))))
  (testing "type tag is committed before each component's payload"
    (doseq [[v tag] [[nil 0x00] [false 0x01] [true 0x02]
                     [1 0x10] ["s" 0x20] [:k 0x22]
                     [[] 0x30] [{} 0x31]]]
      (is (= tag (bit-and (int (aget (hc/canonical-bytes v) 0)) 0xFF))
          (str "tag for " (pr-str v))))))

(deftest test-concat-sequence-boundaries
  (testing "one two-element sequence vs two consecutive single-element sequences"
    (is (not (bytes= (hc/canonical-bytes [1 2])
                     (hc/canonical-bytes [[1] [2]]))))
    (is (not (bytes= (hc/canonical-bytes ["a" "b"])
                     (hc/canonical-bytes [["a"] ["b"]])))))
  (testing "nested empty/singleton collections stay distinct"
    (is (not (bytes= (hc/canonical-bytes [])      (hc/canonical-bytes [[]]))))
    (is (not (bytes= (hc/canonical-bytes [[]])    (hc/canonical-bytes [[] []]))))
    (is (not (bytes= (hc/canonical-bytes [[] []]) (hc/canonical-bytes [[[]]]))))
    (is (not (bytes= (hc/canonical-bytes [[1 2]]) (hc/canonical-bytes [[1] [2]]))))))

(deftest test-concat-associativity-traps
  (testing "nested grouping cannot flatten into an equivalent stream"
    (is (not (bytes= (hc/canonical-bytes [["a" "b"] "c"])
                     (hc/canonical-bytes ["a" ["b" "c"]]))))
    (is (not (bytes= (hc/canonical-bytes [[1 2] [3 4]])
                     (hc/canonical-bytes [1 [2 3] 4]))))
    (is (not (bytes= (hc/canonical-bytes [[[1]]])
                     (hc/canonical-bytes [[1]]))))
    (is (not (bytes= (hc/canonical-bytes [[1 2 3]])
                     (hc/canonical-bytes [1 2 3]))))))

(deftest test-concat-map-entry-boundaries
  (testing "map entries cannot be confused with a flat vector of key/value parts"
    (is (not (bytes= (hc/canonical-bytes {:k1 "v1" :k2 "v2"})
                     (hc/canonical-bytes [:k1 "v1" :k2 "v2"])))))
  (testing "alternate key/value partitioning cannot collide"
    (is (not (bytes= (hc/canonical-bytes {"a" "bc" "ab" "c"})
                     (hc/canonical-bytes {"a" "b" "c" "ab"}))))
    (is (not (bytes= (hc/canonical-bytes {:a {:b 1}})
                     (hc/canonical-bytes {:a/bb 1})))))
  (testing "variable-length keys and values sort deterministically"
    (is (bytes= (hc/canonical-bytes {"a" "bc" "ab" "c"})
                (hc/canonical-bytes {"ab" "c" "a" "bc"})))))

(deftest test-concat-set-canonical-byte-order
  (testing "sets are rejected by the strict encoder regardless of contents"
    (doseq [s [#{:a 1 "b" :a/b -2} #{:x 9 "a" :a/b} #{"\u043F" "z" "\u4E2D"} #{:a/b :a :b}]]
      (is (thrown? Exception (hc/canonical-bytes s)))
      (is (thrown? Exception (hc/validate-canonical-value! s))))))

(deftest test-concat-numeric-edge-cases
  (testing "extreme and adjacent longs are distinct and non-degenerate"
    (is (not (bytes= (hc/canonical-bytes Long/MAX_VALUE)
                     (hc/canonical-bytes (dec Long/MAX_VALUE)))))
    (is (not (bytes= (hc/canonical-bytes Long/MIN_VALUE)
                     (hc/canonical-bytes (inc Long/MIN_VALUE)))))
    (is (not (bytes= (hc/canonical-bytes Long/MAX_VALUE)
                     (hc/canonical-bytes Long/MIN_VALUE))))
    (is (not (bytes= (hc/canonical-bytes 0) (hc/canonical-bytes 1))))
    (is (not (bytes= (hc/canonical-bytes -1) (hc/canonical-bytes 1)))))
  (testing "adjacent huge bigints are distinct (no truncation to host width)"
    (doseq [[a b] [[(bigint (bit-shift-left 1 200)) (bigint (inc (bit-shift-left 1 200)))]
                   [(bigint (- (bit-shift-left 1 200))) (bigint (dec (- (bit-shift-left 1 200))))]]]
      (is (not (bytes= (hc/canonical-bytes a) (hc/canonical-bytes b))))))
  (testing "narrow integer types encode exactly like their long counterparts"
    (is (bytes= (hc/canonical-bytes (short 127)) (hc/canonical-bytes 127)))
    (is (bytes= (hc/canonical-bytes (byte -5)) (hc/canonical-bytes -5)))
    (is (bytes= (hc/canonical-bytes (int 42)) (hc/canonical-bytes 42)))))

(deftest test-concat-prefix-freeness-corpus
  (testing "no canonical encoding is a byte-prefix of another for a diverse corpus"
    (let [candidates (vec (distinct [nil true false 0 1 -1 127 128 16383 16384 ""
                                     "a" "ab" :a :a/b [] [[]] [1] [1 2] ["a" "b"]
                                     {:a 1} {"a" "bc" "ab" "c"} ["a" "bc"] ["ab" "c"]
                                     [""] [nil] [[]] [[] []]]))
          encs (map hc/canonical-bytes candidates)
          prefix? (fn [^bytes p ^bytes s]
                    (and (<= (count p) (count s))
                         (loop [i 0]
                           (if (= i (count p)) true
                               (and (= (bit-and (int (aget p i)) 0xFF)
                                       (bit-and (int (aget s i)) 0xFF))
                                    (recur (inc i)))))))]
      (doseq [[i a] (map-indexed vector encs)
              [j b] (map-indexed vector encs)
              :when (not= i j)]
        (is (not (prefix? a b))
            (str "encoding of " (pr-str (nth candidates i))
                 " is a prefix of " (pr-str (nth candidates j))))))))

(deftest test-concat-cross-runtime-equivalence
  (let [content #(hc/hash-with-intent {:hash/intent :evidence-content} %)]
    (testing "EDN string round-trip hash matches the equivalent JSON-shaped map"
      (is (= (-> (hc/read-string "{:scenario/id \"test\" :event/seq 1 :evidence/type \"stake-registered\"}")
                 :hash)
             (content {:scenario/id "test" :event/seq 1 :evidence/type "stake-registered"}))))
    (testing "intended keyword→string equivalence holds"
      (is (= (content {:a 1}) (content {"a" 1})))
      (is (= (content {:a/b 1}) (content {"a/b" 1}))))
    (testing "distinct logical content remains distinct"
      (is (not= (content {:a/b 1}) (content {:b 1})))
      (is (not= (content {:event/seq 1}) (content {:event/seq 2})))
      (is (not= (content [1 2]) (content [2 1]))))))

(deftest test-concat-domain-separation-wide
  (testing "the same concatenated payload differs across many hash domains"
    (let [fixtures {:world-structure {:a 1} :evidence-record {:a 1}
                    :evidence-chain {:a 1} :registry {:a 1}
                    :manifest {:a 1} :provenance {:a 1}
                    :state-diff {:a 1} :bundle-root {:a 1}}]
      (is (apply distinct?
                 (map (fn [[intent v]] (hc/hash-with-intent {:hash/intent intent} v))
                      fixtures))))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Domain Hashing
;; ──────────────────────────────────────────────────────────────────────────────

(deftest test-domain-hash-deterministic
  (is (= (hc/domain-hash :evidence-record {:a 1})
         (hc/domain-hash :evidence-record {:a 1}))))

(deftest test-domain-hash-differs-by-tag
  (is (not (= (hc/domain-hash :evidence-record {:a 1})
              (hc/domain-hash :registry {:a 1})))))

(deftest test-domain-hash-differs-by-input
  (is (not (= (hc/domain-hash :evidence-record 1)
              (hc/domain-hash :evidence-record 2)))))

(deftest test-hash-bytes-returns-32-bytes
  (is (= 32 (count (hc/hash-bytes (hc/canonical-bytes nil))))))

(deftest test-hash-bytes-raw-not-hex
  (is (instance? (Class/forName "[B") (hc/hash-bytes (hc/canonical-bytes nil)))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Type Validation
;; ──────────────────────────────────────────────────────────────────────────────

(deftest test-validates-canonical-types
  (is (nil? (hc/validate-canonical-value! {:a 1 :b [1 2 3]})))
  (is (nil? (hc/validate-canonical-value! nil)))
  (is (nil? (hc/validate-canonical-value! "hello")))
  (is (nil? (hc/validate-canonical-value! :keyword))))

(deftest test-rejects-ratio
  (is (thrown? Exception (hc/validate-canonical-value! (/ 1 3)))))

(deftest test-rejects-symbol
  (is (thrown? Exception (hc/validate-canonical-value! 'foo))))

(deftest test-rejects-set
  (is (thrown? Exception (hc/validate-canonical-value! #{:a :b}))))

(deftest test-rejects-non-string-map-key
  (is (thrown? Exception (hc/validate-canonical-value! {1 "one"}))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Conformance Test Vectors
;; ──────────────────────────────────────────────────────────────────────────────

(defn- load-vector [id]
  (let [path (str "resources/test-vectors/canonical-hash-v1/" id ".json")
        file (clojure.java.io/file path)]
    (if (.exists file)
      (json/read-str (slurp file) :key-fn keyword)
      (throw (ex-info (str "Missing conformance test vector — run scripts/generate-test-vectors to create: " path)
                      {:path path :test-vector-id id})))))

(deftest test-conformance-null
  (let [v (load-vector "null")]
    (is (= (:hash_hex v) (hc/domain-hash :evidence-record nil)))))

(deftest test-conformance-true
  (let [v (load-vector "true")]
    (is (= (:hash_hex v) (hc/domain-hash :evidence-record true)))))

(deftest test-conformance-false
  (let [v (load-vector "false")]
    (is (= (:hash_hex v) (hc/domain-hash :evidence-record false)))))

(deftest test-conformance-keyword-active
  (let [v (load-vector "keyword-active")]
    (is (= (:hash_hex v) (hc/domain-hash :evidence-record :active)))))

(deftest test-conformance-string-active
  (let [v (load-vector "string-active")]
    (is (= (:hash_hex v) (hc/domain-hash :evidence-record "active")))))

(deftest test-conformance-int-123
  (let [v (load-vector "int-123")]
    (is (= (:hash_hex v) (hc/domain-hash :evidence-record 123)))))

(deftest test-conformance-map-a-1
  (let [v (load-vector "map-a-1")]
    (is (= (:hash_hex v) (hc/domain-hash :evidence-record {:a 1})))))

(deftest test-projected-value-passes-validation
  (let [input {:a 1 :b "hello" :c [1 2 3]}
        result (hc/project-world-to-structure-view input :test-intent)]
    (is (= :test-intent (:intent result)))
    (is (= input (:structure result)))))

(deftest test-projection-converts-set-to-vector
  (let [result (hc/project-world-to-structure-view {:tags #{:a :b :c}} :test-intent)]
    (is (vector? (get-in result [:structure :tags])))
    (is (= [:a :b :c] (get-in result [:structure :tags])))))

(deftest test-projection-converts-empty-set
  (let [result (hc/project-world-to-structure-view #{} :test-intent)]
    (is (= [] (:structure result)))))

(deftest test-projection-converts-instant-to-string
  (let [now (Instant/now)
        result (hc/project-world-to-structure-view {:ts now} :test-intent)]
    (is (string? (get-in result [:structure :ts])))
    (is (= (.toString now) (get-in result [:structure :ts])))))

(deftest test-projection-converts-double-to-tagged
  (let [result (hc/project-world-to-structure-view {:rate 3.14} :test-intent)
        rate (get-in result [:structure :rate])]
    (is (= :float64 (:type rate)))
    (is (string? (:value-str rate)))
    (is (re-matches #"3\.14.*" (:value-str rate)))))

(deftest test-projection-converts-float-to-tagged
  (let [result (hc/project-world-to-structure-view {:rate (float 1.5)} :test-intent)
        rate (get-in result [:structure :rate])]
    (is (= :float64 (:type rate)))
    (is (= "1.5000000000000000" (:value-str rate)))))

(deftest test-projection-converts-function-to-struct-marker
  (let [r1 (hc/project-world-to-structure-view (fn [x] x) :test-intent)
        r2 (hc/project-world-to-structure-view map :test-intent)]
    (is (= {:type :fn} (:structure r1)))
    (is (= {:type :fn} (:structure r2)))))

(deftest test-projection-converts-list-to-vector
  (let [result (hc/project-world-to-structure-view '(1 2 3) :test-intent)]
    (is (= [1 2 3] (:structure result)))))

(deftest test-projection-nested-map-with-sets
  (let [input {:yield/risk {"m1" {"t1" {:failure-modes #{:a :b :c
                                                         :liquidity-crunch}
                                        :rate-mode :fixed}}}
               :resolver-unavailable #{:addr1 :addr2}
               :token-liquidity-crunch #{}}
        s (get-in (hc/project-world-to-structure-view input :test-intent) [:structure])]
    (is (vector? (get-in s [:yield/risk "m1" "t1" :failure-modes])))
    (is (vector? (:resolver-unavailable s)))
    (is (= [] (:token-liquidity-crunch s)))))

(deftest test-projection-idempotent
  (let [input {:nested {:set #{3 1 2}
                        :vec [1 2 3]
                        :kw :hello}
               :instant (Instant/now)
               :fn (fn [x] x)}
        once (hc/project-world-to-structure-view input :test-intent)
        twice (hc/project-world-to-structure-view (:structure once) :test-intent)]
    (is (= once twice))
    (is (nil? (hc/validate-canonical-value! once)))))

(deftest test-projection-rejects-unsupported
  (is (thrown? Exception (hc/project-world-to-structure-view (java.util.Date.) :test-intent))))

(deftest test-projected-value-passes-validation
  (let [world {:step 42
               :run-id "test-run"
               :resolver-unavailable #{:a :b}
               :context/time {:instant (Instant/now)
                              :step 42}
               :yield/risk {"m1" {"t1" {:failure-modes #{:liquidity-crunch}
                                        :rate-mode (fn [x] x)}}}}
        result (hc/project-world-to-structure-view world :test-intent)]
    (is (nil? (hc/validate-canonical-value! result)))
    (is (= :test-intent (:intent result)))))

(deftest test-world-hash-roundtrip
  (testing "a raw set is rejected by direct domain-hash (must be projected)"
    (is (thrown? Exception (hc/domain-hash :world-state {:step 1 :resolver-unavailable #{:addr1}}))))
  (testing "the world-structure projection normalizes sets to sorted vectors,
            so a set and its projected vector hash identically"
    (let [world-1 (get-in (hc/project-world-to-structure-view
                           {:step 1 :resolver-unavailable #{:addr1}} :world-structure)
                          [:structure])
          world-2 (get-in (hc/project-world-to-structure-view
                           {:step 1 :resolver-unavailable [:addr1]} :world-structure)
                          [:structure])]
      (is (= (hc/domain-hash :world-state world-1)
             (hc/domain-hash :world-state world-2)))
      (is (= 64 (count (hc/domain-hash :world-state world-1)))))))

(deftest test-projection-converts-ratio-to-tagged
  (let [result (hc/project-world-to-structure-view {:ratio (/ 1 3)} :test-intent)
        ratio (get-in result [:structure :ratio])]
    (is (= :ratio (:type ratio)))
    (is (string? (:value-str ratio)))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Hash Intent Declaration
;; ──────────────────────────────────────────────────────────────────────────────

(deftest test-hash-with-intent-basic
  (is (string? (hc/hash-with-intent {:hash/intent :evidence-record} {:a 1})))
  (is (= 64 (count (hc/hash-with-intent {:hash/intent :evidence-record} {:a 1})))))

(deftest test-hash-with-intent-world-structure
  (let [world {:step 1 :resolver-unavailable #{:addr1}}]
    (is (string? (hc/hash-with-intent {:hash/intent :world-structure} world)))))

(deftest test-hash-with-intent-evidence-content
  (let [data {:scenario/id "test" :event/seq 1}]
    (is (string? (hc/hash-with-intent {:hash/intent :evidence-content} data)))))

(deftest test-hash-with-intent-rejects-unknown
  (is (thrown? Exception (hc/hash-with-intent {:hash/intent :does-not-exist} {:a 1}))))

(deftest test-hash-intents-differ
  (is (not= (hc/hash-with-intent {:hash/intent :evidence-record} {:a 1})
            (hc/hash-with-intent {:hash/intent :world-structure} {:a 1})))
  (is (not= (hc/hash-with-intent {:hash/intent :evidence-record} {:a 1})
            (hc/hash-with-intent {:hash/intent :evidence-content} {:a 1}))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Intent Registry Contract Tests
;; ──────────────────────────────────────────────────────────────────────────────

(deftest test-resolve-intent-returns-contract
  (let [contract (hc/resolve-intent :world-structure)]
    (is (= :world-structure (:intent/name contract)))
    (is (string? (:intent/description contract)))
    (is (set? (:intent/includes contract)))
    (is (set? (:intent/excludes contract)))
    (is (fn? (:intent/projection-fn contract)))
    (is (string? (:intent/domain-tag contract)))
    (is (integer? (:intent/version contract)))))

(deftest test-resolve-intent-rejects-unknown
  (is (thrown? Exception (hc/resolve-intent :does-not-exist))))

(deftest test-all-intents-have-contract-fields
  (doseq [[kw contract] hc/hash-intents]
    (testing (str "Intent " kw)
      (is (= kw (:intent/name contract)))
      (is (string? (:intent/description contract))
          (str "Missing :intent/description for " kw))
      (is (set? (:intent/includes contract))
          (str "Missing :intent/includes for " kw))
      (is (set? (:intent/excludes contract))
          (str "Missing :intent/excludes for " kw))
      (is (fn? (:intent/projection-fn contract))
          (str "Missing :intent/projection-fn for " kw))
      (is (string? (:intent/domain-tag contract))
          (str "Missing :intent/domain-tag for " kw))
      (is (integer? (:intent/version contract))
          (str "Missing :intent/version for " kw)))))

(deftest test-intent-includes-excludes-are-disjoint
  (doseq [[kw contract] hc/hash-intents]
    (testing (str "Intent " kw " has disjoint includes and excludes")
      (is (empty? (set/intersection (:intent/includes contract)
                                    (:intent/excludes contract)))
          (str "Includes and excludes overlap for " kw)))))

(deftest test-intent-descriptions-are-unique
  (let [descriptions (set (map :intent/description (vals hc/hash-intents)))]
    (is (= (count hc/hash-intents) (count descriptions))
        "Every intent must have a unique description")))

;; ──────────────────────────────────────────────────────────────────────────────
;; Intent Constraint Enforcement
;; ──────────────────────────────────────────────────────────────────────────────

(deftest test-validate-intent-constraints-passes-clean-data
  (is (nil? (hc/validate-intent-constraints! :evidence-record {:a 1 :b "hello"})))
  (is (nil? (hc/validate-intent-constraints! :world-structure {:step 42 :run-id "r"}))))

(deftest test-world-structure-accepts-projectable-types
  (is (nil? (hc/validate-intent-constraints! :world-structure
                                             {:fn (fn [x] x)})))
  (is (nil? (hc/validate-intent-constraints! :world-structure
                                             {:set #{:a}})))
  (is (nil? (hc/validate-intent-constraints! :world-structure
                                             {:instant (Instant/now)})))
  (is (nil? (hc/validate-intent-constraints! :world-structure
                                             {:ratio (/ 1 3)})))
  (is (nil? (hc/validate-intent-constraints! :world-structure
                                             {:double 3.14})))
  (testing "hash-with-intent succeeds for worlds with projectable runtime types"
    (is (string? (hc/hash-with-intent {:hash/intent :world-structure}
                                      {:fn (fn [x] x)
                                       :set #{:a}
                                       :instant (Instant/now)
                                       :ratio (/ 1 3)
                                       :double 3.14})))
    (is (string? (hc/hash-with-intent {:hash/intent :world-structure}
                                      {:resolver-unavailable #{:addr1}
                                       :total-held {"0xtoken" 1000}
                                       :liquid-locked (/ 1 3)
                                       :price 3.14
                                       :updated-at (Instant/now)})))))

(deftest test-validate-intent-constraints-catches-root-structural-violations
  (is (thrown? Exception
               (hc/validate-intent-constraints! :evidence-record {:evidence/hash "abc"
                                                                  :a 1})))
  (is (thrown? Exception
               (hc/validate-intent-constraints! :evidence-record {:evidence/timestamp "2024"
                                                                  :a 1}))))

(deftest test-validate-intent-constraints-allows-keywords-for-evidence-content
  ;; Keywords and hash-like keys are projected (keyword→string) by
  ;; project-for-content-hash, so they are legitimate evidence content and
  ;; must not be rejected by intent-constraint validation.
  (is (nil? (hc/validate-intent-constraints! :evidence-content {:evidence/type :core}))))

(deftest test-validate-intent-constraints-rejects-unknown-intent
  (is (thrown? Exception
               (hc/validate-intent-constraints! :does-not-exist {:a 1}))))

(deftest test-validate-intent-constraints-allows-hash-fields
  (is (nil? (hc/validate-intent-constraints! :evidence-content
                                             {:some-hash "abc" :data "test"}))))

(deftest test-validate-intent-constraints-nested-excludes
  (is (nil? (hc/validate-intent-constraints! :world-structure
                                             {:nested {:fn (fn [x] x)}})))
  (is (nil? (hc/validate-intent-constraints! :world-structure
                                             {:nested {:a 1}})))
  (is (thrown? Exception
               (hc/validate-intent-constraints! :evidence-record
                                                {:evidence/hash "abc" :a 1}))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Dynamic Var Validation in hash-with-intent
;; ──────────────────────────────────────────────────────────────────────────────

(deftest test-dynamic-var-validation-inline
  (let [clean-data {:a 1}
        dirty-data {:evidence/hash "abc" :a 1}]
    ;; Validation disabled — no throw (bound explicitly; the parallel test
    ;; runner enables validation by default, so the ambient default must not
    ;; be relied upon)
    (binding [hc/*validate-intent-constraints* false]
      (is (string? (hc/hash-with-intent {:hash/intent :evidence-record} dirty-data))))
    ;; Validation enabled — throw
    (binding [hc/*validate-intent-constraints* true]
      (is (thrown? Exception
                   (hc/hash-with-intent {:hash/intent :evidence-record} dirty-data)))
      ;; Clean data passes even with validation enabled
      (is (string? (hc/hash-with-intent {:hash/intent :evidence-record} clean-data))))))

;; ──────────────────────────────────────────────────────────────────────────────
;; intent-hash=
;; ──────────────────────────────────────────────────────────────────────────────

(deftest test-intent-hash=-same-strings
  (let [h (hc/hash-with-intent {:hash/intent :evidence-record} {:a 1})]
    (is (hc/intent-hash= h h))))

(deftest test-intent-hash=-different-strings
  (is (not (hc/intent-hash= "abc" "def"))))

(deftest test-intent-hash=-with-maps
  (let [h (hc/hash-with-intent {:hash/intent :evidence-record} {:a 1})
        h2 (hc/hash-with-intent {:hash/intent :evidence-record} {:a 1})
        h3 (hc/hash-with-intent {:hash/intent :world-structure} {:a 1})]
    (is (hc/intent-hash= {:hash/intent :evidence-record :hash/hex h}
                         {:hash/intent :evidence-record :hash/hex h2}))
    (is (not (hc/intent-hash= {:hash/intent :evidence-record :hash/hex h}
                              {:hash/intent :world-structure :hash/hex h3})))))

(deftest test-intent-hash=-string-and-map
  (let [h (hc/hash-with-intent {:hash/intent :evidence-record} {:a 1})]
    (is (hc/intent-hash= h {:hash/intent :evidence-record :hash/hex h}))))

(deftest test-intent-hash=-allows-cross-intent
  (let [h-same "abc123"
        h-diff "def456"]
    ;; string vs map with same hex, different intents — blocked by default
    (is (not (hc/intent-hash= {:hash/intent :evidence-record :hash/hex h-same}
                              {:hash/intent :world-structure :hash/hex h-same})))
    ;; allow-cross-intent? true — compare hex regardless
    (is (hc/intent-hash= {:hash/intent :evidence-record :hash/hex h-same}
                         {:hash/intent :world-structure :hash/hex h-same}
                         {:allow-cross-intent? true}))
    ;; different hex still false even with allow-cross-intent?
    (is (not (hc/intent-hash= {:hash/intent :evidence-record :hash/hex h-same}
                              {:hash/intent :world-structure :hash/hex h-diff}
                              {:allow-cross-intent? true})))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Intent Golden Hash Vectors
;; ──────────────────────────────────────────────────────────────────────────────
;; Each fixture is hashed with its intent and the result is compared against a
;; known golden value. If the intent contract (projection, domain tag, encoding)
;; changes, the golden value will break — alerting the developer.

(def ^:private golden-fixtures
  "Intent-specific fixtures for golden hash regression testing."
  {:world-structure   {:step 42 :resolver-unavailable #{:addr1} :total-held {"0xtoken" 1000}}
   :evm-projection    (let [w {:step 42 :escrow-transfers {0 {:amount-after-fee 1000
                                                              :escrow-state :pending}}
                               :total-held {"0xtoken" 1000}
                               :total-fees {"0xtoken" 10}
                               :dispute-levels {0 0}
                               :block-time 1000}]
                        (assoc (select-keys w [:escrow-transfers :total-held
                                               :total-fees :dispute-levels
                                               :block-time])
                               :accounting-consistent? true))
   :evidence-record   {:a 1 :b "hello" :scenario/id "test"}
   :evidence-content  {:scenario/id "test" :event/seq 1 :evidence/type "stake-registered"}
   :evidence-chain    {:cursor/scope :targeted-evidence :cursor/final-seq 5
                       :cursor/final-self-hash "abc123" :cursor/total-captured 3}
   :manifest          {:suite {:scenario "sew-core"} :run-id "test-run" :status :pass}
   :bundle-root       {:benchmark/id "bm-1" :passed 10 :total 10}
   :registry          {:registry-hash nil :evidence-count 3 :artifacts []}
   :provenance        {:provenance/lineage [:evidence-chain]
                       :provenance/links [{:from "a" :to "b"}]}
   :state-diff        {:changes [{:path [:escrow :state] :before nil :after nil}]}
   :params-manifest   {:scenario "sew-core" :seed 42 :n-epochs 10}})

(def ^:private golden-hashes
  "Known golden hash values for each intent's fixture.
   If an intent contract changes, these must be recomputed."
  {:world-structure   "7bd2385f6f282de3016059bd6e2cedd14ae1733c1c2b09fe766362346efbe091"
   :evm-projection    "0e8665e204c8f3833773b6b68d458ccd6346acd621ab9c7f5e4219ba6e3cdd2a"
   :evidence-record   "bb94b6ff9457c613af3fefce33130c4b4e68a8a0f5b587c00124c18cfa848ace"
   :evidence-content  "644a06aff128e2324d7769de979d7a9c8f8d79235556027506c23193a0001b48"
   :evidence-chain    "365e311bef1cd758d3961a2b08dc59fffc8d636f8826ff4901328cfd4a49a84b"
   :manifest          "b6398eb7538ee05172ce62d656c2c4042819ad7d62fe1694c5dfafbf3ab242b7"
   :bundle-root       "ebf3a96c2062e800866f6c353c28d4ebd340a538fbb630d0c75fa3b2420ff057"
   :registry          "ec2edbfaec899d9134ed51aec519e683d23cd7c58e36d9c6f42d5e1af15a6e37"
   :provenance        "43c51800d4418215e9a0c1c4b25a25733bf4c38e1288fe2e1cdc3be35578196a"
   :state-diff        "ece887daefd0565ff3770592def75aceb969d8903d8be7156617daea2d1e13a1"
   :params-manifest   "bf3cd3d2e0b9bf288bad087217c8868ef68e0d5d56e453ff2eaea24972cbc54e"})

(deftest test-golden-hash-vectors
  (doseq [[intent fixture] golden-fixtures]
    (testing (str "Golden hash for intent " intent)
      (let [expected (get golden-hashes intent)
            actual (hc/hash-with-intent {:hash/intent intent} fixture)]
        (is (= expected actual)
            (str "Golden hash mismatch for " intent " — intent contract or encoding may have changed"))))))

(deftest test-golden-hash-vectors-deterministic
  (doseq [[intent fixture] golden-fixtures]
    (testing (str "Deterministic hash for intent " intent)
      (is (= (hc/hash-with-intent {:hash/intent intent} fixture)
             (hc/hash-with-intent {:hash/intent intent} fixture))))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Intent Registry Validation (IA-level)
;; ──────────────────────────────────────────────────────────────────────────────

(deftest test-validate-registry-passes
  (is (nil? (hc/validate-registry!))
      "validate-registry! must pass on well-formed registry"))

(deftest test-validate-registry-detects-missing-field
  (with-redefs [hc/hash-intents (assoc hc/hash-intents
                                       :bad-intent
                                       {:intent/name :bad-intent})]
    (is (thrown? clojure.lang.ExceptionInfo
                 (hc/validate-registry!)))))

(deftest test-validate-registry-detects-wrong-type
  (with-redefs [hc/hash-intents (assoc hc/hash-intents
                                       :bad-intent
                                       {:intent/name         :bad-intent
                                        :intent/domain-tag   :not-a-string
                                        :intent/description  "bad"
                                        :intent/includes     #{}
                                        :intent/excludes     #{}
                                        :intent/projection-fn identity
                                        :intent/version      1})]
    (is (thrown? clojure.lang.ExceptionInfo
                 (hc/validate-registry!)))))

(deftest test-validate-registry-detects-negative-version
  (with-redefs [hc/hash-intents (assoc hc/hash-intents
                                       :bad-intent
                                       {:intent/name         :bad-intent
                                        :intent/domain-tag   "BAD_V1"
                                        :intent/description  "bad"
                                        :intent/includes     #{}
                                        :intent/excludes     #{}
                                        :intent/projection-fn identity
                                        :intent/version      -1})]
    (is (thrown? clojure.lang.ExceptionInfo
                 (hc/validate-registry!)))))

(def ^:private phase-1-projection-intents
  [:intent-dsl
   :intent-registry-entry
   :intent-registry
   :projection-definition
   :projection-definition-registry
   :projection-artifact
   :claim-definition
   :attestor
   :pro-rata-allocation-result])

(def ^:private phase-1-projection-fixtures
  {:intent-dsl
   {:intent/type :pro-rata/allocation
    :intent/version 1
    :intent/purpose :slash-obligation-allocation
    :intent/scope {:protocol :sew :module :slashing :domain :economic-allocation}
    :intent/inputs #{:obligations :weights :caps}
    :intent/constraints #{:conservation :non-negative}
    :intent/output :allocation}

   :intent-registry-entry
   {:intent/name :projection-definition
    :intent/domain-tag "PROJECTION_DEFINITION_V1"
    :intent/description "Projection definition identity"
    :intent/includes #{:id :version :projection-type}
    :intent/excludes #{:canonical-hash}
    :intent/projection-fn 'resolver-sim.hash.canonical/project-projection-definition
    :intent/version 1}

   :intent-registry
   {:registry-version 1
    :intent-definitions [{:intent/name :intent-dsl
                          :intent/domain-tag "INTENT_DSL_V1"}]
    :intent-hashes ["abc"]
    :registry-hash "self-hash-to-strip"}

   :projection-definition
   {:id :projection/pro-rata-slash-obligation
    :version 1
    :projection-type :pro-rata-allocation
    :intent-types #{:pro-rata/allocation}
    :intent-purposes #{:slash-obligation-allocation}
    :source {:type :world-state}
    :include-paths [[:resolver-stakes] [:pending-slashes]]
    :exclude-paths [[:runtime/cache]]
    :transforms [{:op 'resolver-sim.projection/sort-by-id}]
    :output {:type :allocation-basis}
    :claims [{:claim-id :projection-deterministic}]
    :canonical-hash "self-hash-to-strip"}

   :projection-definition-registry
   {:registry-version 1
    :projection-definitions [{:id :projection/world-structure :version 1}]
    :definition-hashes ["def"]
    :registry-hash "self-hash-to-strip"}

   :projection-artifact
   {:schema-version 1
    :projection-id "proj-1"
    :projection-type :pro-rata-allocation
    :projection-version 1
    :intent {:intent/type :pro-rata/allocation
             :intent/version 1
             :intent/purpose :slash-obligation-allocation}
    :projection-definition-hash "definition-hash"
    :source {:type :allocation-input
             :world-before-hash "world-before-hash"
             :action-hash-at "action-hash-at-placeholder"
             :basis :slashable-stake
             :cap-field :available-slashable
             :unmet-policy :record-only
             :slash-policy nil}
    :projection {:liable-parties [{:id :alice :weight 2} {:id :bob :weight 1}]
                 :observed-at (Instant/ofEpochSecond 0)}
    :summary {:party-count 2}
    :claims [{:claim-id :projection-canonical-safe}]
    :metadata {:debug "not identity"}
    :projection-hash "self-hash-to-strip"}

   :pro-rata-allocation-result
   {:schema-version 1
    :artifact-kind :pro-rata/allocation-result
    :allocation-result-id "result-1"
    :allocation-result-type :pro-rata-allocation
    :allocation-result-version 1
    :projection-artifact-hash "proj-hash-1"
    :projection-definition-id :projection/pro-rata-slash-obligation
    :projection-definition-hash "def-hash-1"
    :source {:type :allocation-input
             :basis :slashable-stake
             :cap-field :available-slashable}
    :provenance {:world-before-hash "world-before"
                 :world-after-hash "world-after"
                 :action-hash "action-hash"
                 :action-hash-at "action-hash-at"
                 :evidence-record-hash "evidence-hash"}
    :allocation-result {:allocations [{:id :alice :allocated 100 :unmet 0}
                                      {:id :bob :allocated 50 :unmet 0}]
                        :total-requested 150
                        :total-allocated 150
                        :total-unmet 0
                        :remainder 0
                        :policy {:rounding :floor
                                 :remainder-policy :unallocated
                                 :ordering-policy :input-order}}
    :shortfall-outcome nil
    :claims []
    :invariant-links []
    :metadata {:debug "not identity"}
    :allocation-result-hash "self-hash-to-strip"}

   :claim-definition
   {:id :accounting-consistency
    :version 1
    :category :invariant
    :description "Accounting balances sum correctly"
    :inputs [:world-state :projection-artifact]
    :evaluation {:type :pure-predicate
                 :fn 'resolver-sim.claims/accounting-consistency}
    :outputs [:passed? :violations]
    :canonical-hash "self-hash-to-strip"}

   :attestor
   {:id :github-actions
    :type :ci-runner
    :display-name "GitHub Actions"
    :status :active
    :verification {:type :public-key
                   :algorithm :ed25519
                   :key-id "key-001"
                   :public-key "abc"}
    :metadata {:owner :protocol-robustness}
    :attestor-hash "self-hash-to-strip"}})

(deftest test-phase-1-projection-intents-registered
  (doseq [intent phase-1-projection-intents]
    (testing (str intent)
      (let [contract (hc/resolve-intent intent)]
        (is (= intent (:intent/name contract)))
        (is (string? (:intent/domain-tag contract)))
        (is (fn? (:intent/projection-fn contract)))))))

(deftest test-phase-1-domain-tags-registered
  (doseq [intent phase-1-projection-intents]
    (testing (str intent)
      (let [contract (hc/resolve-intent intent)]
        (is (= (:intent/domain-tag contract) (get hc/domain-tags intent)))))))

(deftest test-phase-1-projections-produce-canonical-safe-data
  (doseq [[intent fixture] phase-1-projection-fixtures]
    (testing (str intent)
      (let [project-fn (:intent/projection-fn (hc/resolve-intent intent))
            projection (project-fn fixture intent)]
        (is (nil? (hc/validate-canonical-value! projection)))
        (is (= intent (:intent projection)))))))

(deftest test-phase-1-hashes-are-deterministic-and-domain-separated
  (doseq [[intent fixture] phase-1-projection-fixtures]
    (testing (str intent)
      (let [project-fn (:intent/projection-fn (hc/resolve-intent intent))
            projected (project-fn fixture intent)
            hash-a (hc/hash-with-intent {:hash/intent intent} fixture)
            hash-b (hc/hash-with-intent {:hash/intent intent} fixture)
            other-domain-hash (hc/domain-hash "EVIDENCE_RECORD_V1" projected)]
        (is (= hash-a hash-b))
        (is (= 64 (count hash-a)))
        (is (not= hash-a other-domain-hash))))))

(deftest test-phase-1-self-hash-fields-do-not-affect-artifact-identity
  (doseq [[intent self-key] {:intent-registry :registry-hash
                             :projection-definition :canonical-hash
                             :projection-definition-registry :registry-hash
                             :projection-artifact :projection-hash
                             :claim-definition :canonical-hash
                             :attestor :attestor-hash
                             :pro-rata-allocation-result :allocation-result-hash}]
    (testing (str intent)
      (let [fixture (get phase-1-projection-fixtures intent)
            a (assoc fixture self-key "aaa")
            b (assoc fixture self-key "bbb")]
        (is (= (hc/hash-with-intent {:hash/intent intent} a)
               (hc/hash-with-intent {:hash/intent intent} b)))))))

(deftest test-self-hash-cancellation-is-root-only
  (testing "root-level self-hash keys are cancelled before hashing"
    (let [proj (hc/project-self-hash-stripped {:a 1 :node-hash "self"} nil)]
      (is (not (contains? proj :node-hash)))
      (is (= 1 (:a proj)))))
  (testing "nested self-hash keys are ordinary content (root-only contract)"
    (let [nested {:a {:node-hash "nested"} :b 2}]
      (is (= {:node-hash "nested"} (:a (hc/project-self-hash-stripped nested nil))))
      (is (not= (hc/canonical-bytes-hex nested)
                (hc/canonical-bytes-hex (assoc nested :node-hash "top")))
          "a nested self-hash participates in the hash as content")))
  (testing "evidence-finalization-v2 projection cancels a root self-hash"
    (let [proj (hc/project-self-hash-stripped
                {:scenario-id :s1 :head-hash "h" :self-hash "self"} nil)]
      (is (not (contains? proj :self-hash)))
      (is (= :s1 (:scenario-id proj)))
      (is (= "h" (:head-hash proj)) "reference hashes are preserved")))
  (testing "bare :hash is intent-excluded on actions, not globally stripped"
    (is (= (hc/hash-with-intent {:hash/intent :action} {:action/type :release :hash "x"})
           (hc/hash-with-intent {:hash/intent :action} {:action/type :release})))))

(deftest test-hash-fields-checker-does-not-flag-reference-hashes
  (testing "the :hash-fields checker flags only exact self-hash keys, never
            -hash-suffixed reference keys (HASH_INTENT_REGISTRY_SPEC_V1 §2.6)"
    (let [checker (get @#'hc/exclude-root-checkers :hash-fields)]
      (is (nil? (checker {:action-hash "ref"
                          :before-hash "ref"
                          :after-hash "ref"
                          :claim-definition-hash "ref"}))
          "reference hashes must not be flagged")
      (is (some? (checker {:node-hash "self"}))
          "exact self-hash keys are flagged")
      (is (nil? (checker {:a 1 :b 2})) "plain maps are not flagged"))))

(deftest test-phase-1-identity-fields-affect-artifact-identity
  (let [fixture (:projection-artifact phase-1-projection-fixtures)
        changed (assoc fixture :projection-id "proj-2")]
    (is (not= (hc/hash-with-intent {:hash/intent :projection-artifact} fixture)
              (hc/hash-with-intent {:hash/intent :projection-artifact} changed)))))

(deftest test-pro-rata-allocation-result-identity-fields-affect-hash
  (let [fixture (:pro-rata-allocation-result phase-1-projection-fixtures)
        changed-proj (assoc fixture :projection-artifact-hash "different-proj-hash")
        changed-result (assoc fixture :allocation-result
                              {:allocations [] :total-requested 0
                               :total-allocated 0 :total-unmet 0 :remainder 0
                               :policy {}})]
    (testing "different projection artifact hash changes identity"
      (is (not= (hc/hash-with-intent {:hash/intent :pro-rata-allocation-result} fixture)
                (hc/hash-with-intent {:hash/intent :pro-rata-allocation-result} changed-proj))))
    (testing "different allocation result changes identity"
      (is (not= (hc/hash-with-intent {:hash/intent :pro-rata-allocation-result} fixture)
                (hc/hash-with-intent {:hash/intent :pro-rata-allocation-result} changed-result))))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Claim Definition Projection Property Tests
;; ──────────────────────────────────────────────────────────────────────────────

(defn- claim-def-fixture
  ([] (claim-def-fixture {}))
  ([overrides]
   (merge {:id :test-claim
           :version 1
           :category :invariant
           :description "Test claim"
           :inputs [:world-state]
           :evaluation {:type :pure-predicate :fn 'test-claims/check}
           :outputs [:passed? :violations]}
          overrides)))

(deftest test-claim-definition-projection-includes-required-fields
  (let [fixture (claim-def-fixture)
        projected (:artifact (hc/project-claim-definition fixture :claim-definition))]
    (is (= :test-claim (:id projected)))
    (is (= 1 (:version projected)))
    (is (= :invariant (:category projected)))
    (is (= [:world-state] (:inputs projected)))
    (is (= {:type :pure-predicate :fn {:type :symbol :value "test-claims/check"}}
           (:evaluation projected)))
    (is (= [:passed? :violations] (:outputs projected)))))

(deftest test-claim-definition-projection-excludes-description
  (let [fixture (claim-def-fixture)
        projected (:artifact (hc/project-claim-definition fixture :claim-definition))]
    (is (nil? (:description projected))
        "Description must not be part of canonical identity")))

(deftest test-claim-definition-projection-excludes-canonical-hash
  (let [fixture (claim-def-fixture {:canonical-hash "abc123"})
        projected (:artifact (hc/project-claim-definition fixture :claim-definition))]
    (is (nil? (:canonical-hash projected))
        "Self hash field must not be part of canonical identity")))

(deftest test-claim-definition-projection-includes-depends-on-when-present
  (let [fixture (claim-def-fixture {:depends-on [:projection-deterministic]})
        projected (:artifact (hc/project-claim-definition fixture :claim-definition))]
    (is (= [:projection-deterministic] (:depends-on projected))
        "depends-on must be included in projection when present")))

(deftest test-claim-definition-projection-excludes-depends-on-when-absent
  (let [fixture (claim-def-fixture)
        projected (:artifact (hc/project-claim-definition fixture :claim-definition))]
    (is (nil? (:depends-on projected))
        "depends-on must not appear in projection when absent from source")))

(deftest test-claim-definition-hash-deterministic
  (let [fixture (claim-def-fixture)
        h1 (hc/hash-with-intent {:hash/intent :claim-definition} fixture)
        h2 (hc/hash-with-intent {:hash/intent :claim-definition} fixture)]
    (is (= h1 h2))))

(deftest test-claim-definition-hash-identical-for-identical-inputs
  (let [a (claim-def-fixture)
        b (claim-def-fixture)]
    (is (= (hc/hash-with-intent {:hash/intent :claim-definition} a)
           (hc/hash-with-intent {:hash/intent :claim-definition} b)))))

(deftest test-claim-definition-hash-changes-on-semantic-change
  (let [base (claim-def-fixture)
        different-id (assoc base :id :other-claim)
        different-version (assoc base :version 2)
        different-category (assoc base :category :safety)
        different-inputs (assoc base :inputs [:projection-artifact])
        different-outputs (assoc base :outputs [:passed?])
        base-hash (hc/hash-with-intent {:hash/intent :claim-definition} base)]
    (is (not= base-hash (hc/hash-with-intent {:hash/intent :claim-definition} different-id)))
    (is (not= base-hash (hc/hash-with-intent {:hash/intent :claim-definition} different-version)))
    (is (not= base-hash (hc/hash-with-intent {:hash/intent :claim-definition} different-category)))
    (is (not= base-hash (hc/hash-with-intent {:hash/intent :claim-definition} different-inputs)))
    (is (not= base-hash (hc/hash-with-intent {:hash/intent :claim-definition} different-outputs)))))

(deftest test-claim-definition-hash-not-affected-by-key-ordering
  (let [a (claim-def-fixture)
        ;; Same content, different insertion order
        b (into {} (reverse (seq a)))]
    (is (= (hc/hash-with-intent {:hash/intent :claim-definition} a)
           (hc/hash-with-intent {:hash/intent :claim-definition} b)))))

(deftest test-claim-definition-hash-not-affected-by-metadata
  (let [base (claim-def-fixture)
        with-meta (assoc base :generated-at "2026-06-23T00:00:00Z"
                         :display-name "Test Display"
                         :notes "some notes")]
    (is (= (hc/hash-with-intent {:hash/intent :claim-definition} base)
           (hc/hash-with-intent {:hash/intent :claim-definition} with-meta)))))

(deftest test-claim-definition-hash-changes-when-depends-on-changes
  (let [a (claim-def-fixture {:depends-on [:proj-a]})
        b (claim-def-fixture {:depends-on [:proj-b]})]
    (is (not= (hc/hash-with-intent {:hash/intent :claim-definition} a)
              (hc/hash-with-intent {:hash/intent :claim-definition} b)))))

(deftest test-claim-definition-hash-domain-separated
  (let [fixture (claim-def-fixture)
        claim-hash (hc/hash-with-intent {:hash/intent :claim-definition} fixture)
        other-hash (hc/hash-with-intent {:hash/intent :attestor} fixture)]
    (is (not= claim-hash other-hash))
    (is (not= claim-hash (hc/hash-with-intent {:hash/intent :projection-definition} fixture)))))

(defn- attestor-fixture
  ([] (attestor-fixture {}))
  ([overrides]
   (merge {:id :ci-validation
           :version 1
           :type :ci-runner
           :display-name "CI validation runner"
           :status :active
           :verification {:type :public-key
                          :algorithm :ed25519
                          :key-id "ci-validation-v1"
                          :public-key "ci-validation-placeholder-public-key"}
           :delegates [{:id :ci-validation-signing-key
                        :status :active}]
           :key-history [{:key-id "ci-validation-v0"
                          :status :retired}
                         {:key-id "ci-validation-v1"
                          :status :active}]
           :attestor-hash "self-hash-placeholder"
           :canonical-hash "legacy-self-hash-placeholder"
           :cached-verification-data {:last-checked-at "2026-06-23T00:00:00Z"
                                      :verification-result :ok}
           :runtime-state {:pid 42}
           :metadata {:intended-use #{:validation :attestation}}}
          overrides)))

(defn- reorder-map
  [m]
  (into {} (reverse (seq m))))

(deftest test-attestor-projection-includes-canonical-fields-only
  (let [fixture (attestor-fixture)
        projected (:artifact (hc/project-attestor fixture :attestor))]
    (is (= #{:id :type :status :verification :delegates :key-history}
           (set (keys projected))))
    (is (= :ci-validation (:id projected)))
    (is (= :ci-runner (:type projected)))
    (is (= :active (:status projected)))
    (is (= [{:id :ci-validation-signing-key :status :active}]
           (:delegates projected)))
    (is (= [{:key-id "ci-validation-v0" :status :retired}
            {:key-id "ci-validation-v1" :status :active}]
           (:key-history projected)))
    (is (nil? (:display-name projected)))
    (is (nil? (:metadata projected)))
    (is (nil? (:attestor-hash projected)))
    (is (nil? (:canonical-hash projected)))
    (is (nil? (:cached-verification-data projected)))
    (is (nil? (:runtime-state projected)))))

(deftest test-attestor-projection-normalizes-missing-collections
  (let [fixture (-> (attestor-fixture)
                    (dissoc :delegates)
                    (dissoc :key-history))
        projected (:artifact (hc/project-attestor fixture :attestor))]
    (is (= [] (:delegates projected)))
    (is (= [] (:key-history projected)))))

(deftest attestor-hash-stable-under-map-ordering-changes
  (let [p (prop/for-all [key-id gen/string-alphanumeric
                         public-key gen/string-alphanumeric
                         delegate-id gen/string-alphanumeric]
                        (let [base (attestor-fixture
                                    {:verification {:type :public-key
                                                    :algorithm :ed25519
                                                    :key-id key-id
                                                    :public-key public-key}
                                     :delegates [{:id delegate-id
                                                  :status :active}]
                                     :key-history [{:key-id "v0" :status :retired}
                                                   {:key-id key-id :status :active}]})
                              reordered (-> base
                                            (update :verification reorder-map)
                                            (update :delegates #(mapv reorder-map %))
                                            (update :key-history #(mapv reorder-map %))
                                            reorder-map)]
                          (= (hc/hash-with-intent {:hash/intent :attestor} base)
                             (hc/hash-with-intent {:hash/intent :attestor} reordered))))
        result (tc/quick-check 50 p)]
    (is (:pass? result) (pr-str result))))

(deftest attestor-hash-changes-when-verification-keys-change
  (let [p (prop/for-all [key-a (gen/such-that seq gen/string-alphanumeric)
                         key-b (gen/such-that seq gen/string-alphanumeric)]
                        (if (= key-a key-b)
                          true
                          (let [base (attestor-fixture {:verification {:type :public-key
                                                                       :algorithm :ed25519
                                                                       :key-id "ci-validation-v1"
                                                                       :public-key key-a}})
                                changed (attestor-fixture {:verification {:type :public-key
                                                                          :algorithm :ed25519
                                                                          :key-id "ci-validation-v1"
                                                                          :public-key key-b}})]
                            (not= (hc/hash-with-intent {:hash/intent :attestor} base)
                                  (hc/hash-with-intent {:hash/intent :attestor} changed)))))
        result (tc/quick-check 50 p)]
    (is (:pass? result) (pr-str result))))

(deftest attestor-hash-changes-when-delegates-change
  (let [p (prop/for-all [delegate-a (gen/such-that seq gen/string-alphanumeric)
                         delegate-b (gen/such-that seq gen/string-alphanumeric)]
                        (if (= delegate-a delegate-b)
                          true
                          (let [base (attestor-fixture {:delegates [{:id delegate-a :status :active}]})
                                changed (attestor-fixture {:delegates [{:id delegate-b :status :active}]})]
                            (not= (hc/hash-with-intent {:hash/intent :attestor} base)
                                  (hc/hash-with-intent {:hash/intent :attestor} changed)))))
        result (tc/quick-check 50 p)]
    (is (:pass? result) (pr-str result))))

(deftest attestor-hash-changes-when-status-changes
  (let [p (prop/for-all [status (gen/elements [:revoked :retired])]
                        (not= (hc/hash-with-intent {:hash/intent :attestor}
                                                   (attestor-fixture {:status :active}))
                              (hc/hash-with-intent {:hash/intent :attestor}
                                                   (attestor-fixture {:status status}))))
        result (tc/quick-check 50 p)]
    (is (:pass? result) (pr-str result))))

(deftest test-validate-registry-detects-duplicate-domain-tags
  (with-redefs [hc/hash-intents (assoc-in hc/hash-intents
                                          [:attestor :intent/domain-tag]
                                          "INTENT_DSL_V1")]
    (is (thrown? clojure.lang.ExceptionInfo
                 (hc/validate-registry!)))))

;; ──────────────────────────────────────────────────────────────────────────────
;; New Intent Contract Tests (Evidence Layers 2, 7, 8, 9)
;; ──────────────────────────────────────────────────────────────────────────────

(deftest test-validate-registry-detects-unregistered-domain-tags
  (with-redefs [hc/domain-tags (dissoc hc/domain-tags :attestor)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (hc/validate-registry!)))))

(deftest test-validate-registry-detects-nondeterministic-projections
  (with-redefs [hc/hash-intents (assoc-in hc/hash-intents
                                          [:attestor :intent/projection-fn]
                                          (fn [_ intent]
                                            {:intent intent
                                             :artifact {:nonce (str (java.util.UUID/randomUUID))}}))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (hc/validate-registry!)))))

(deftest test-invariant-attestation-intent
  (let [contract (hc/resolve-intent :invariant-attestation)]
    (is (= :invariant-attestation (:intent/name contract)))
    (is (string? (:intent/domain-tag contract)))
    (is (contains? (:intent/includes contract) :invariants))
    (is (contains? (:intent/includes contract) :invariant-set-hash))))

(deftest test-projection-evidence-intent
  (let [contract (hc/resolve-intent :projection-evidence)]
    (is (= :projection-evidence (:intent/name contract)))
    (is (contains? (:intent/includes contract) :projection-hash))))

(deftest test-checkpoint-evidence-intent
  (let [contract (hc/resolve-intent :checkpoint-evidence)]
    (is (= :checkpoint-evidence (:intent/name contract)))
    (is (contains? (:intent/includes contract) :checkpoint-id))))

(deftest test-benchmark-certification-intent
  (let [contract (hc/resolve-intent :benchmark-certification)]
    (is (= :benchmark-certification (:intent/name contract)))
    (is (contains? (:intent/includes contract) :all-invariants-pass))))

(deftest test-invariant-attestation-hash-is-deterministic
  (let [data {:step 1
              :invariants [{:id :conservation-of-value :result :pass}
                           {:id :no-negative-balances :result :pass}]
              :passed 2 :failed 0}
        h1 (hc/hash-with-intent {:hash/intent :invariant-attestation} data)
        h2 (hc/hash-with-intent {:hash/intent :invariant-attestation} data)]
    (is (= h1 h2))))

(deftest test-invariant-attestation-hash-changes-on-result-change
  (let [pass-data {:step 1 :invariants [{:id :test :result :pass}] :passed 1 :failed 0}
        fail-data {:step 1 :invariants [{:id :test :result :fail}] :passed 0 :failed 1}
        pass-hash (hc/hash-with-intent {:hash/intent :invariant-attestation} pass-data)
        fail-hash (hc/hash-with-intent {:hash/intent :invariant-attestation} fail-data)]
    (is (not= pass-hash fail-hash))))

(deftest test-invariant-attestation-domain-separated
  (let [data {:step 1 :invariants [] :passed 0 :failed 0}
        attest-hash (hc/hash-with-intent {:hash/intent :invariant-attestation} data)
        world-hash (hc/hash-with-intent {:hash/intent :world-structure} data)]
    (is (not= attest-hash world-hash))))

(deftest test-projection-evidence-hash-is-deterministic
  (let [data {:step 1 :world-hash "abc" :projection-hash "def" :projection-version 1}
        h1 (hc/hash-with-intent {:hash/intent :projection-evidence} data)
        h2 (hc/hash-with-intent {:hash/intent :projection-evidence} data)]
    (is (= h1 h2))))

(deftest test-checkpoint-evidence-hash-is-deterministic
  (let [data {:checkpoint-id "cp-1" :event-seq 1 :world-hash "abc" :chain-head 0}
        h1 (hc/hash-with-intent {:hash/intent :checkpoint-evidence} data)
        h2 (hc/hash-with-intent {:hash/intent :checkpoint-evidence} data)]
    (is (= h1 h2))))

(deftest test-benchmark-certification-hash-is-deterministic
  (let [data {:benchmark-id "bm-1" :scenario-count 10 :all-invariants-pass true
              :final-state-hash nil :evidence-chain-root nil
              :invariant-summary {:conservation {:passed 10 :total 10}}}
        h1 (hc/hash-with-intent {:hash/intent :benchmark-certification} data)
        h2 (hc/hash-with-intent {:hash/intent :benchmark-certification} data)]
    (is (= h1 h2))))

;; ──────────────────────────────────────────────────────────────────────────────
;; New Intent Contract Tests (Evidence Layers 4, 6)
;; ──────────────────────────────────────────────────────────────────────────────

(deftest test-decision-evidence-intent
  (let [contract (hc/resolve-intent :decision-evidence)]
    (is (= :decision-evidence (:intent/name contract)))
    (is (contains? (:intent/includes contract) :decision-id))
    (is (contains? (:intent/includes contract) :alternatives))
    (is (contains? (:intent/includes contract) :selected))))

(deftest test-invariant-failure-intent
  (let [contract (hc/resolve-intent :invariant-failure)]
    (is (= :invariant-failure (:intent/name contract)))
    (is (contains? (:intent/includes contract) :invariant-ids))
    (is (contains? (:intent/includes contract) :halt-reason))))

(deftest test-decision-evidence-hash-is-deterministic
  (let [data {:decision-id "resolve-0-1" :step 5
              :alternatives [:release :refund] :selected :release
              :reasoning "Resolver alice released escrow 0"
              :caller "alice" :workflow-id "0"}
        h1 (hc/hash-with-intent {:hash/intent :decision-evidence} data)
        h2 (hc/hash-with-intent {:hash/intent :decision-evidence} data)]
    (is (= h1 h2))))

(deftest test-decision-evidence-hash-changes-on-different-selection
  (let [release-data {:decision-id "resolve-0-1" :step 5
                      :alternatives [:release :refund] :selected :release
                      :reasoning "Release" :caller "alice" :workflow-id "0"}
        refund-data {:decision-id "resolve-0-1" :step 5
                     :alternatives [:release :refund] :selected :refund
                     :reasoning "Refund" :caller "alice" :workflow-id "0"}
        release-hash (hc/hash-with-intent {:hash/intent :decision-evidence} release-data)
        refund-hash (hc/hash-with-intent {:hash/intent :decision-evidence} refund-data)]
    (is (not= release-hash refund-hash))))

(deftest test-invariant-failure-hash-is-deterministic
  (let [data {:step 5 :scenario-id "scen-1"
              :invariant-ids [:no-negative-balances]
              :details {"no-negative-balances" "balance -5"}
              :halt-reason :invariant-violation}
        h1 (hc/hash-with-intent {:hash/intent :invariant-failure} data)
        h2 (hc/hash-with-intent {:hash/intent :invariant-failure} data)]
    (is (= h1 h2))))

(deftest test-invariant-failure-hash-changes-on-different-invariant
  (let [data-a {:step 5 :scenario-id "scen-1"
                :invariant-ids [:no-negative-balances]
                :details {} :halt-reason :invariant-violation}
        data-b {:step 5 :scenario-id "scen-1"
                :invariant-ids [:conservation-of-value]
                :details {} :halt-reason :invariant-violation}
        hash-a (hc/hash-with-intent {:hash/intent :invariant-failure} data-a)
        hash-b (hc/hash-with-intent {:hash/intent :invariant-failure} data-b)]
    (is (not= hash-a hash-b))))

(deftest test-decision-evidence-domain-separated
  (let [data {:decision-id "d1" :step 1 :alternatives [] :selected :none
              :reasoning "test" :caller "alice" :workflow-id "0"}
        dec-hash (hc/hash-with-intent {:hash/intent :decision-evidence} data)
        world-hash (hc/hash-with-intent {:hash/intent :world-structure} data)]
    (is (not= dec-hash world-hash))))

(deftest test-invariant-failure-domain-separated
  (let [data {:step 1 :scenario-id "s" :invariant-ids [] :details {} :halt-reason :invariant-violation}
        fail-hash (hc/hash-with-intent {:hash/intent :invariant-failure} data)
        world-hash (hc/hash-with-intent {:hash/intent :world-structure} data)]
    (is (not= fail-hash world-hash))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Action / Action-At Hash Properties
;; ──────────────────────────────────────────────────────────────────────────────

(deftest test-action-hash-stable-for-equivalent-payloads
  (let [action {:type :release :escrow-id "0xabc" :actor "0xres"}
        a (hc/hash-with-intent {:hash/intent :action} action)
        b (hc/hash-with-intent {:hash/intent :action} action)]
    (is (= a b))))

(deftest test-action-hash-changes-when-payload-changes
  (let [action-a {:type :release :escrow-id "0xabc" :actor "0xres"}
        action-b {:type :release :escrow-id "0xdef" :actor "0xres"}
        a (hc/hash-with-intent {:hash/intent :action} action-a)
        b (hc/hash-with-intent {:hash/intent :action} action-b)]
    (is (not= a b))))

(deftest test-action-hash-at-changes-with-execution-position
  (let [action-hash "abc123"
        a (hc/hash-with-intent {:hash/intent :action-at}
                               {:action-hash action-hash :step 1 :block-time 100})
        b (hc/hash-with-intent {:hash/intent :action-at}
                               {:action-hash action-hash :step 2 :block-time 200})]
    (is (not= a b))))

(deftest test-action-hash-at-includes-action-hash
  (let [a (hc/hash-with-intent {:hash/intent :action-at}
                               {:action-hash "abc123" :step 1 :block-time 100})
        b (hc/hash-with-intent {:hash/intent :action-at}
                               {:action-hash "def456" :step 1 :block-time 100})]
    (is (not= a b))
    (testing "action-hash-at and action-hash are distinct values"
      (is (not= a "abc123"))
      (is (not= b "def456")))))

(deftest test-action-hash-ignores-self-reference-fields
  (let [action {:action/type :release :escrow-id "0xabc"}
        with-self-hash (assoc action :canonical-hash "should-be-ignored"
                              :node-hash "also-ignored"
                              :hash "ignored-too")
        a (hc/hash-with-intent {:hash/intent :action} action)
        b (hc/hash-with-intent {:hash/intent :action} with-self-hash)]
    (is (= a b))))

(deftest test-action-at-rejects-extra-keys
  (is (thrown? clojure.lang.ExceptionInfo
               (hc/hash-with-intent {:hash/intent :action-at}
                                    {:action-hash "abc"
                                     :step 1
                                     :block-time 100
                                     :unexpected-key "nope"}))))

(deftest test-action-at-requires-all-fields
  (is (thrown? clojure.lang.ExceptionInfo
               (hc/hash-with-intent {:hash/intent :action-at}
                                    {:action-hash "abc" :step 1}))))

(deftest test-action-at-hash-deterministic
  (let [input {:action-hash "abc" :step 1 :block-time 100}
        a (hc/hash-with-intent {:hash/intent :action-at} input)
        b (hc/hash-with-intent {:hash/intent :action-at} input)]
    (is (= a b))))

(deftest test-action-at-rejects-block-time-only
  (is (thrown? clojure.lang.ExceptionInfo
               (hc/hash-with-intent {:hash/intent :action-at}
                                    {:block-time 100}))))

(deftest test-action-normalizes-type-keyword
  (let [action-a {:type :release :escrow-id "0xabc"}
        action-b {:action/type :release :escrow-id "0xabc"}
        a (hc/hash-with-intent {:hash/intent :action} action-a)
        b (hc/hash-with-intent {:hash/intent :action} action-b)]
    (is (= a b))))

(deftest test-action-rejects-missing-type
  (is (thrown? clojure.lang.ExceptionInfo
               (hc/hash-with-intent {:hash/intent :action}
                                    {:escrow-id "0xabc"}))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Pro-Rata Allocation Result Artifact Tests
;; ──────────────────────────────────────────────────────────────────────────────

(deftest test-build-pro-rata-allocation-result-artifact
  (let [proj-fixture (:pro-rata-allocation-result phase-1-projection-fixtures)
        projection-artifact (:projection-artifact phase-1-projection-fixtures)
        allocation-result {:allocations [{:id :alice :allocated 100 :unmet 0}
                                         {:id :bob :allocated 50 :unmet 0}]
                           :total-requested 150
                           :total-allocated 150
                           :total-unmet 0
                           :remainder 0
                           :policy {:rounding :floor
                                    :remainder-policy :unallocated
                                    :ordering-policy :input-order}}
        artifact (payoffs/build-pro-rata-allocation-result-artifact
                  {:projection-artifact projection-artifact
                   :allocation-result allocation-result
                   :world-before-hash "world-before"
                   :world-after-hash "world-after"
                   :action-hash "action-hash"
                   :action-hash-at "action-hash-at"})]
    (testing "artifact has allocation-result-hash"
      (is (string? (:allocation-result-hash artifact))))
    (testing "artifact has schema-version"
      (is (= 1 (:schema-version artifact))))
    (testing "artifact includes allocation-result"
      (is (= allocation-result (:allocation-result artifact))))
    (testing "artifact includes provenance"
      (is (= "world-before" (get-in artifact [:provenance :world-before-hash])))
      (is (= "world-after" (get-in artifact [:provenance :world-after-hash])))
      (is (= "action-hash" (get-in artifact [:provenance :action-hash])))
      (is (= "action-hash-at" (get-in artifact [:provenance :action-hash-at]))))
    (testing "artifact includes projection reference"
      (is (= (:projection-hash projection-artifact)
             (:projection-artifact-hash artifact))))
    (testing "artifact is canonical-safe"
      (is (nil? (hc/validate-canonical-value! artifact))))))

(deftest test-pro-rata-allocation-result-validates-successfully
  (let [proj-fixture (:projection-artifact phase-1-projection-fixtures)
        allocation-result {:allocations [{:id :alice :allocated 100 :unmet 0}
                                         {:id :bob :allocated 0 :unmet 50}]
                           :total-requested 150
                           :total-allocated 100
                           :total-unmet 50
                           :remainder 0
                           :policy {:rounding :floor
                                    :remainder-policy :unallocated
                                    :ordering-policy :input-order}}
        artifact (payoffs/build-pro-rata-allocation-result-artifact
                  {:projection-artifact proj-fixture
                   :allocation-result allocation-result
                   :world-before-hash "wb"
                   :world-after-hash "wa"
                   :action-hash "ah"
                   :action-hash-at "aha"})]
    (is (some? (payoffs/validate-pro-rata-allocation-result-artifact! artifact))
        "validate should return truthy on success")))

(deftest test-pro-rata-allocation-result-rejects-hash-mismatch
  (let [proj-fixture (:projection-artifact phase-1-projection-fixtures)
        allocation-result {:allocations []
                           :total-requested 0
                           :total-allocated 0
                           :total-unmet 0
                           :remainder 0
                           :policy {}}
        artifact (payoffs/build-pro-rata-allocation-result-artifact
                  {:projection-artifact proj-fixture
                   :allocation-result allocation-result
                   :world-before-hash "wb"
                   :world-after-hash "wa"
                   :action-hash "ah"
                   :action-hash-at "aha"})
        tampered (assoc artifact :allocation-result-hash "tampered")]
    (is (thrown? clojure.lang.ExceptionInfo
                 (payoffs/validate-pro-rata-allocation-result-artifact! tampered)))))

(deftest test-pro-rata-allocation-result-rejects-missing-projection-hash
  (let [proj-fixture (:projection-artifact phase-1-projection-fixtures)
        allocation-result {:allocations []
                           :total-requested 0
                           :total-allocated 0
                           :total-unmet 0
                           :remainder 0
                           :policy {}}
        artifact (payoffs/build-pro-rata-allocation-result-artifact
                  {:projection-artifact proj-fixture
                   :allocation-result allocation-result
                   :world-before-hash "wb"
                   :world-after-hash "wa"
                   :action-hash "ah"
                   :action-hash-at "aha"})
        tampered (dissoc artifact :projection-artifact-hash)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (payoffs/validate-pro-rata-allocation-result-artifact! tampered)))))

(deftest test-pro-rata-allocation-result-rejects-missing-allocation-result
  (let [proj-fixture (:projection-artifact phase-1-projection-fixtures)
        artifact (payoffs/build-pro-rata-allocation-result-artifact
                  {:projection-artifact proj-fixture
                   :allocation-result {:allocations []
                                       :total-requested 0
                                       :total-allocated 0
                                       :total-unmet 0
                                       :remainder 0
                                       :policy {}}
                   :world-before-hash "wb"
                   :world-after-hash "wa"
                   :action-hash "ah"
                   :action-hash-at "aha"})
        tampered (dissoc artifact :allocation-result)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (payoffs/validate-pro-rata-allocation-result-artifact! tampered)))))

(deftest test-pro-rata-allocation-result-rejects-bad-totals
  (let [proj-fixture (:projection-artifact phase-1-projection-fixtures)
        allocation-result {:allocations [{:id :alice :allocated 100 :unmet 0}]
                           :total-requested 150
                           :total-allocated 100
                           :total-unmet 0
                           :remainder 0
                           :policy {}}
        artifact (payoffs/build-pro-rata-allocation-result-artifact
                  {:projection-artifact proj-fixture
                   :allocation-result allocation-result
                   :world-before-hash "wb"
                   :world-after-hash "wa"
                   :action-hash "ah"
                   :action-hash-at "aha"})]
    (is (thrown? clojure.lang.ExceptionInfo
                 (payoffs/validate-pro-rata-allocation-result-artifact! artifact)))))

(deftest test-pro-rata-allocation-result-rejects-missing-provenance
  (let [proj-fixture (:projection-artifact phase-1-projection-fixtures)
        allocation-result {:allocations []
                           :total-requested 0
                           :total-allocated 0
                           :total-unmet 0
                           :remainder 0
                           :policy {}}
        artifact (payoffs/build-pro-rata-allocation-result-artifact
                  {:projection-artifact proj-fixture
                   :allocation-result allocation-result
                   :world-before-hash "wb"
                   :world-after-hash nil
                   :action-hash "ah"
                   :action-hash-at "aha"})]
    (is (thrown? clojure.lang.ExceptionInfo
                 (payoffs/validate-pro-rata-allocation-result-artifact! artifact)))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Demo Rendering Tests
;; ──────────────────────────────────────────────────────────────────────────────

(deftest test-format-pro-rata-result-table-includes-allocation-hash
  (let [proj-fixture (:projection-artifact phase-1-projection-fixtures)
        allocation-result {:allocations [{:id :alice :weight 200 :cap nil :allocated 100 :unmet 0}
                                         {:id :bob :weight 100 :cap nil :allocated 50 :unmet 0}]
                           :total-requested 150
                           :total-allocated 150
                           :total-unmet 0
                           :remainder 0
                           :policy {}}
        artifact (payoffs/build-pro-rata-allocation-result-artifact
                  {:projection-artifact proj-fixture
                   :allocation-result allocation-result
                   :world-before-hash "wb"
                   :world-after-hash "wa"
                   :action-hash "ah"
                   :action-hash-at "aha"})
        table (payoffs/format-pro-rata-result-table artifact)
        panel (payoffs/format-proof-panel artifact)]
    (testing "result table includes allocation fields"
      (is (string? table))
      (is (.contains table "Allocated"))
      (is (.contains table "Unmet"))
      (is (.contains table "150")))
    (testing "proof panel includes allocation-result-hash"
      (is (string? panel))
      (is (.contains panel "Allocation Result Hash"))
      (is (.contains panel (:allocation-result-hash artifact))))
    (testing "proof panel includes action-hash-at"
      (is (.contains panel "Action Hash-At"))
      (is (.contains panel "aha")))
    (testing "proof panel includes world hashes"
      (is (.contains panel "World Before Hash"))
      (is (.contains panel "World After Hash"))
      (is (.contains panel "wb"))
      (is (.contains panel "wa")))))

(deftest test-shortfall-outcome-changes-result-hash
  (let [proj-fixture (:projection-artifact phase-1-projection-fixtures)
        base-allocation {:allocations [] :total-requested 0 :total-allocated 0
                         :total-unmet 0 :remainder 0 :policy {}}
        artifact-no-sf (payoffs/build-pro-rata-allocation-result-artifact
                        {:projection-artifact proj-fixture
                         :allocation-result base-allocation
                         :world-before-hash "wb" :world-after-hash "wa"
                         :action-hash "ah" :action-hash-at "aha"})
        artifact-with-sf (payoffs/build-pro-rata-allocation-result-artifact
                          {:projection-artifact proj-fixture
                           :allocation-result base-allocation
                           :world-before-hash "wb" :world-after-hash "wa"
                           :action-hash "ah" :action-hash-at "aha"
                           :shortfall-outcome {:fulfilled 0 :deferred 50 :haircut 30}})]
    (is (not= (:allocation-result-hash artifact-no-sf)
              (:allocation-result-hash artifact-with-sf))
        "Adding shortfall outcome must change the result hash")))

(deftest test-allocation-domain-tags-registered-and-unique
  (let [allocation-tags [:allocation-context
                         :claimant-set
                         :outcome-set
                         :proposed-rates
                         :rate-derived-summary
                         :selected-outcome
                         :result-root
                         :certificate-assertions
                         :allocation-assurance-certificate]]
    (doseq [tag allocation-tags]
      (is (contains? hc/domain-tags tag)
          (str "Domain tag " tag " must be registered")))
    (is (= (count allocation-tags)
           (count (distinct (map hc/domain-tags allocation-tags))))
        "Allocation domain tag strings must be distinct")
    (let [all-values (set (map hc/domain-tags allocation-tags))
          existing-values (set (vals (apply dissoc hc/domain-tags allocation-tags)))]
      (is (empty? (clojure.set/intersection all-values existing-values))
          "Allocation domain tag strings must not collide with existing tags"))
    (is (= 64 (count (hc/domain-hash :allocation-context {:sample :value}))))
    (is (not= (hc/domain-hash :allocation-context {:sample :value})
              (hc/domain-hash :claimant-set {:sample :value}))
        "Allocation domains must be domain-separated")))

;; ──────────────────────────────────────────────────────────────────────────────
;; Domain-tag framing (consecutive concatenation of DOMAIN_TAG || CANONICAL_BYTES)
;; ──────────────────────────────────────────────────────────────────────────────

(defn- byte-prefix?
  "True if the byte array `a` is a strict prefix of the byte array `b`."
  [^bytes a ^bytes b]
  (and (< (count a) (count b))
       (loop [i 0]
         (if (= i (count a))
           true
           (and (= (bit-and (int (aget a i)) 0xFF)
                   (bit-and (int (aget b i)) 0xFF))
                (recur (inc i)))))))

(deftest test-map-keys-encode-iteratively
  (testing "scalar map keys (including integer keys) encode identically to a
            direct recursive walk — backward compatible"
    (is (bytes= (hc/canonical-bytes {0 1})
                (byte-array (map #(Integer/parseInt % 16)
                                 (re-seq #".." "310110001002")))))
    (is (bytes= (hc/canonical-bytes {0 {:a 1}})
                (hc/canonical-bytes {0 {:a 1}})) "deterministic"))
  (testing "composite map keys (vector/map) encode deterministically via the
            iterative stack — no recursion, no rejection (backward compatible)"
    (is (bytes= (hc/canonical-bytes {[:a] 1})
                (hc/canonical-bytes {[:a] 1})) "deterministic vector key")
    (is (bytes= (hc/canonical-bytes {{:a 1} :v})
                (hc/canonical-bytes {{:a 1} :v})) "deterministic map key")
    (is (bytes= (hc/canonical-bytes {[1 2] {:x [3]}})
                (hc/canonical-bytes {[1 2] {:x [3]}})) "nested composite keys")
    (testing "key order independence is preserved for composite keys"
      (is (bytes= (hc/canonical-bytes {[1] 1 [2] 2})
                  (hc/canonical-bytes {[2] 2 [1] 1})))))
  (testing "sets as map keys remain rejected (sets are outside the canonical domain)"
    (is (thrown? Exception (hc/canonical-bytes {#{:a} :v}))))
  (testing "deep map-key nesting is stack-safe (iterative key encoding)"
    (let [deep (reduce (fn [acc _] {acc 1}) [:a] (range 1000))]
      (is (= (hc/canonical-bytes-hex deep) (hc/canonical-bytes-hex deep)))))
  (testing "nil, boolean, and keyword scalar keys remain supported"
    (is (bytes= (hc/canonical-bytes {nil 1}) (hc/canonical-bytes {nil 1})))
    (is (bytes= (hc/canonical-bytes {true 1}) (hc/canonical-bytes {true 1})))
    (is (bytes= (hc/canonical-bytes {:a 1}) (hc/canonical-bytes {:a 1})))))

(deftest test-confidence-and-command-trace-intents-registered
  (doseq [[intent tag] {:confidence-composition-v1 "CONFIDENCE_COMPOSITION_V1"
                        :research-command-trace-v1 "RESEARCH_COMMAND_TRACE_V1"
                        :research-command-trace-v2 "RESEARCH_COMMAND_TRACE_V2"}]
    (testing (str intent)
      (let [contract (hc/resolve-intent intent)]
        (is (= tag (:intent/domain-tag contract)))
        (is (= tag (get hc/domain-tags intent)))
        (is (string? (:intent/description contract)))))))

(deftest test-production-string-tags-are-registered
  (testing "production domain tags previously passed as bare strings are now
            registered, so prefix-freeness enforcement covers them"
    (doseq [tag ["EXTENSION_ENVELOPE_SHAPE_V1"
                 "EXTENSION_LOCKFILE_V1"
                 "EXTENSION_RESOLUTION_V1"
                 "EXTENSION_CAPABILITY_DESCRIPTOR_V1"
                 "EXTENSION_PACKAGE_MANIFEST_V1"
                 "BENCHMARK_CONSERVATION_V1"
                 "BENCHMARK_INPUT_SET_V1"
                 "BENCHMARK_CONTENT_REGISTRY_V1"
                 "BENCHMARK_FINALIZATION_V1"
                 "SUITE_DEFINITION_V1"
                 "NATIVE_EXACT_REPLICATION_V1"
                 "conformance.reproduction-lineage.v1"
                 "conformance.validator-implementation.v1"
                 "evidence-package-admission.v1"]]
      (is (some #(= % tag) (vals hc/domain-tags))
          (str tag " must be registered in domain-tags")))))

(deftest test-domain-tags-prefix-free
  (testing "no registered domain tag is a strict prefix of another
            (requirement for unambiguous DOMAIN_TAG || CANONICAL_BYTES framing)"
    (let [tags (vec (vals hc/domain-tags))]
      (doseq [[i t1] (map-indexed vector tags)
              t2 (drop (inc i) tags)]
        (is (not (or (byte-prefix? (.getBytes t1 "UTF-8") (.getBytes t2 "UTF-8"))
                     (byte-prefix? (.getBytes t2 "UTF-8") (.getBytes t1 "UTF-8"))))
            (str "domain tags \"" t1 "\" and \"" t2 "\" violate prefix-freeness"))))))

(deftest test-registry-rejects-prefix-violating-domain-tags
  (testing "prefix-freeness validation fails closed on a prefix relationship"
    (let [e (try (#'hc/validate-prefix-free-domain-tags! #{"PREFIX"
                                                           "PREFIX_SUFFIX"})
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e))
      (is (re-find #"prefix-free" (.getMessage e)))
      (is (= "PREFIX" (:shorter (ex-data e))))
      (is (= "PREFIX_SUFFIX" (:longer (ex-data e)))))
    (testing "reversed order is also rejected"
      (is (thrown? clojure.lang.ExceptionInfo
                   (#'hc/validate-prefix-free-domain-tags! #{"PREFIX_SUFFIX"
                                                             "PREFIX"}))))
    (testing "prefix-free sets pass"
      (is (nil? (#'hc/validate-prefix-free-domain-tags! #{"ALPHA" "BETA"})))
      (is (nil? (#'hc/validate-prefix-free-domain-tags! #{"EVIDENCE_RECORD_V1"
                                                          "EVIDENCE_RECORD_V2"})))))
  (testing "prefix-freeness holds for the live registry"
    (is (nil? (hc/validate-registry!)))))
