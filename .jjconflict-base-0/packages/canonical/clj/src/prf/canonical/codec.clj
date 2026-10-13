(ns prf.canonical.codec
  "Standalone implementation of the PRF canonical binary value codec."
  (:import [java.io ByteArrayOutputStream]
           [java.math BigInteger]))

(def ^:const tag-null (byte 0x00))
(def ^:const tag-bool-false (byte 0x01))
(def ^:const tag-bool-true (byte 0x02))
(def ^:const tag-int (byte 0x10))
(def ^:const tag-string (byte 0x20))
(def ^:const tag-keyword (byte 0x22))
(def ^:const tag-array (byte 0x30))
(def ^:const tag-map (byte 0x31))

;; ──────────────────────────────────────────────────────────────────────────────
;; varuint Encoding (LEB128, little-endian base-128)
;; ──────────────────────────────────────────────────────────────────────────────

(defn- encode-varuint
  "Encode a non-negative integer as LEB128 varuint.
   Minimal representation: no leading zeros."
  [n]
  (let [bos (ByteArrayOutputStream.)
        n (biginteger n)]
    (loop [n n]
      (let [b (.byteValue (.and n (BigInteger/valueOf 0x7F)))
            n' (.shiftRight n 7)]
        (if (.equals n' BigInteger/ZERO)
          (do (.write bos (int b))
              (.toByteArray bos))
          (do (.write bos (int (bit-or (int b) 0x80)))
              (recur n')))))))

;; ──────────────────────────────────────────────────────────────────────────────
;; ZigZag Encoding (signed → unsigned for varuint)
;; ──────────────────────────────────────────────────────────────────────────────

(defn- zigzag
  "ZigZag encode a signed integer of arbitrary precision to an unsigned integer.
   n >= 0 → 2n
   n <  0 → -2n - 1"
  [n]
  (if (neg? n)
    (-' (*' -2 n) 1)
    (*' 2 n)))

;; ──────────────────────────────────────────────────────────────────────────────
;; UTF-8 Encoding
;; ──────────────────────────────────────────────────────────────────────────────

(defn- utf8-bytes
  "Encode a string as UTF-8 byte array."
  [s]
  (.getBytes s "UTF-8"))

;; ──────────────────────────────────────────────────────────────────────────────
;; Keyword Name
;; ──────────────────────────────────────────────────────────────────────────────

(defn- keyword-string
  "Return the portable string representation of a keyword.
   :resolver/id → \"resolver/id\"
   :active      → \"active\""
  [k]
  (if-let [ns (namespace k)]
    (str ns "/" (name k))
    (name k)))

;; ──────────────────────────────────────────────────────────────────────────────
;; Integer coercion
;; ──────────────────────────────────────────────────────────────────────────────

(defn- coerce-integer
  "Coerce any Clojure/Java integer type to BigInteger for uniform handling."
  [v]
  (cond
    (instance? BigInteger v) v
    (instance? Long v) (BigInteger/valueOf (long v))
    (instance? Integer v) (BigInteger/valueOf (int v))
    (instance? clojure.lang.BigInt v) (.toBigInteger v)
    (instance? Short v) (BigInteger/valueOf (short v))
    (instance? Byte v) (BigInteger/valueOf (byte v))
    :else (BigInteger/valueOf (long v))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Byte array helpers
;; ──────────────────────────────────────────────────────────────────────────────

(defn- ba-concat
  "Concatenate multiple byte-arrays into one."
  [& bas]
  (let [total (reduce + (map count bas))
        out (byte-array total)]
    (loop [idx 0, bas bas]
      (when (seq bas)
        (let [ba (first bas)]
          (System/arraycopy ba 0 out idx (count ba))
          (recur (+ idx (count ba)) (rest bas)))))
    out))

(defn- ba-concat-all
  "Concatenate a seq of byte-arrays into one byte-array in a single pass.
   Unlike (apply ba-concat bas), this does not realize the full argument
   sequence into an Object[] for the variadic apply, so very large flat
   collections (many array/map elements) do not pay the arg-list materialization
   cost.  Single allocation; each input is copied exactly once."
  [bas]
  (let [bas (seq bas)
        total (reduce + 0 (map count bas))
        out (byte-array total)]
    (loop [idx 0, bas bas]
      (when bas
        (let [ba (first bas)]
          (System/arraycopy ^bytes ba 0 out idx (count ba))
          (recur (+ idx (count ba)) (next bas)))))
    out))

(defn- ba-of
  "Create a byte-array from individual byte arguments."
  [& bs]
  (byte-array bs))

(defn- byte-compare
  "Lexicographic byte comparison. Returns a negative number if a < b,
   zero if a == b, and a positive number if a > b."
  [^bytes a ^bytes b]
  (let [alen (count a)
        blen (count b)
        minlen (min alen blen)]
    (loop [i 0]
      (if (= i minlen)
        (- alen blen)
        (let [ai (bit-and (int (aget a i)) 0xFF)
              bi (bit-and (int (aget b i)) 0xFF)]
          (if (= ai bi)
            (recur (inc i))
            (- ai bi)))))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Type Validation
;; ──────────────────────────────────────────────────────────────────────────────

(defn- map-key-type?
  "Return true if k is a permitted map key type."
  [k]
  (or (instance? String k)
      (instance? clojure.lang.Keyword k)))

(defn out-of-domain!
  "Fail closed: the value is outside the canonical type algebra
   (CANONICAL_HASH_SPEC_V1 §4/§5).  It must be projected to a canonical-safe
   representation before encoding — the encoder and validators never perform
   silent coercion.  Always throws; never returns.  When a path is supplied it
   is reported so recursive rejections name the exact nested location."
  [x host-type guidance & [path]]
  (throw (ex-info "Value is outside the canonical type domain; project it to a canonical-safe representation before hashing"
                  {:type :canonical/out-of-domain
                   :host-type host-type
                   :value-class (some-> x class .getName)
                   :guidance guidance
                   :path (vec path)})))

(defn validate-canonical-value!
  "Walk a value tree and validate that all values are supported
    canonical types. Throws ex-info on the first unsupported type with the
    exact nested :path of the offending value.  Map keys must be String or
    Keyword.

    STRICT DOMAIN: matches the encoder.  Records, map entries, sets, seqs,
    temporal values, and every other host type outside the canonical type
    algebra are rejected with a structured :canonical/out-of-domain error."
  [v]
  (let [walker (fn walk [x path]
                 (cond
                   (nil? x) x
                   (instance? Boolean x) x
                   (instance? Long x) x
                   (instance? Integer x) x
                   (instance? Short x) x
                   (instance? Byte x) x
                   (instance? clojure.lang.BigInt x) x
                   (instance? BigInteger x) x
                   (instance? String x) x
                   (instance? clojure.lang.Keyword x) x
                   (instance? clojure.lang.IMapEntry x)
                   (out-of-domain! x "clojure.lang.IMapEntry"
                                   "map entries are not canonical values; project to an explicit vector before hashing"
                                   (conj path :map-entry))
                   (instance? clojure.lang.IRecord x)
                   (out-of-domain! x "clojure.lang.IRecord"
                                   "records are prohibited (spec §5); convert to a plain map with (into {} x) before hashing"
                                   (conj path :record))
                   (instance? clojure.lang.IPersistentVector x)
                   (do (run! (fn [e] (walk e (conj path :vector))) x) x)
                   (instance? clojure.lang.IPersistentMap x)
                   (do (doseq [[k v] x]
                         (when-not (map-key-type? k)
                           (throw (ex-info "Map key must be String or Keyword"
                                           {:key k :type (type k) :path (conj path :key)})))
                         (walk v (conj path k)))
                       x)
                   :else
                   (out-of-domain! x (some-> x class .getName)
                                   "value is outside the canonical type algebra; project it to a canonical-safe representation before hashing"
                                   (conj path :value))))]
    (walker v [])
    nil))

;; ──────────────────────────────────────────────────────────────────────────────
;; Canonical Bytes
;; ──────────────────────────────────────────────────────────────────────────────

(defn canonical-bytes
  "Produce the canonical typed binary encoding of a value.
    Returns a byte-array per CANONICAL_HASH_SPEC_V1_BINARY_ENCODING_ABI.

    STRICT DOMAIN: this is the normative encoder.  Only the canonical type
    algebra (CANONICAL_HASH_SPEC_V1 §3) may reach it — nil, boolean, integer,
    string, keyword, vector, map.  Every host type outside that algebra — sets,
    seqs/lists/lazy-seqs, map entries, records, temporal values, ratios,
    floats, BigDecimal, Java collections/arrays — is REJECTED with a structured
    :canonical/out-of-domain error rather than silently coerced into an
    in-domain representation.  Callers that genuinely want a set→sorted-vector
    or list→vector conversion must perform it explicitly (see
    project-world-to-structure-view) before entering this boundary.

    Implemented iteratively over an explicit work stack so that deeply
    nested structures (per spec §8.4 concatenation stress) do not overflow
    the JVM stack. Output is byte-identical to a direct recursive walk."
  [v]
  (loop [work [[:encode v]]
         done []]
    (if (empty? work)
      (peek done)
      (let [task (peek work)
            work (pop work)]
        (case (nth task 0)
          :encode
          (let [x (nth task 1)]
            (cond
              (nil? x)
              (recur work (conj done (ba-of tag-null)))

              (instance? Boolean x)
              (recur work (conj done (ba-of (if x tag-bool-true tag-bool-false))))

              ;; Only integer types have a canonical representation.  Do not use
              ;; number? here: coercing floating-point values or ratios to long
              ;; silently aliases distinct semantic values (for example, 1 and 1.9).
              ;; Integer widths (Long/Integer/Short/Byte/BigInt/BigInteger) are
              ;; declared equivalent — they are numerically equal canonical
              ;; integers and encode identically.
              (or (instance? Long x)
                  (instance? Integer x)
                  (instance? Short x)
                  (instance? Byte x)
                  (instance? clojure.lang.BigInt x)
                  (instance? BigInteger x))
              (let [bi (coerce-integer x)
                    vu (encode-varuint (zigzag bi))]
                (recur work (conj done (ba-concat (ba-of tag-int) vu))))

              (instance? String x)
              (let [bs (utf8-bytes x)
                    len (encode-varuint (count bs))]
                (recur work (conj done (ba-concat (ba-of tag-string) len bs))))

              (instance? clojure.lang.Keyword x)
              (let [s (keyword-string x)
                    bs (utf8-bytes s)
                    len (encode-varuint (count bs))]
                (recur work (conj done (ba-concat (ba-of tag-keyword) len bs))))

              ;; ── out-of-domain host types (rejected, never coerced) ──────────

              (instance? java.time.temporal.TemporalAccessor x)
              (out-of-domain! x "java.time.temporal.TemporalAccessor"
                              "temporal values are outside the canonical domain; project to an ISO-8601 string before hashing")

              (instance? java.math.BigDecimal x)
              (out-of-domain! x "java.math.BigDecimal"
                              "BigDecimal is outside the canonical domain; project to a canonical-safe representation before hashing")

              (instance? clojure.lang.IMapEntry x)
              (out-of-domain! x "clojure.lang.IMapEntry"
                              "map entries are not canonical values; project to an explicit vector before hashing")

              (instance? clojure.lang.IRecord x)
              (out-of-domain! x "clojure.lang.IRecord"
                              "records are prohibited (spec §5); convert to a plain map with (into {} x) before hashing")

              (instance? clojure.lang.ISeq x)
              (out-of-domain! x "clojure.lang.ISeq"
                              "seqs/lists/lazy-seqs are outside the canonical domain; realize to a vector before hashing")

              (instance? clojure.lang.IPersistentSet x)
              (out-of-domain! x "clojure.lang.IPersistentSet"
                              "sets are outside the canonical domain; project to a sorted vector before hashing")

              ;; ── canonical composite types ───────────────────────────────────

              (instance? clojure.lang.IPersistentVector x)
              (let [n (count x)]
                (recur (into (conj work [:array n])
                             (map (fn [e] [:encode e]) (reverse x)))
                       done))

              (instance? clojure.lang.IPersistentMap x)
              (let [entries (into [] x)
                    n (count entries)
                    key-encodes (map (fn [e] [:encode (first e)]) (reverse entries))]
                (recur (into (conj work [:map-keys n entries]) key-encodes)
                       done))

              :else
              (out-of-domain! x (some-> x class .getName)
                              "value is outside the canonical type algebra; project it to a canonical-safe representation before hashing")))

          :array
          (let [n (nth task 1)
                split (- (count done) n)
                elems (subvec done split)
                rest-done (subvec done 0 split)
                combined (ba-concat-all (list* (ba-of tag-array) (encode-varuint n) elems))]
            (recur work (conj rest-done combined)))

          :map-keys
          (let [n (nth task 1)
                entries (nth task 2)
                split (- (count done) n)
                key-bytes (subvec done split)
                rest-done (subvec done 0 split)
                pairs (mapv (fn [e kb] {:key-bytes kb :val (second e)})
                            entries key-bytes)
                sorted (sort-by :key-bytes
                                (fn [^bytes a ^bytes b]
                                  (neg? (byte-compare a b)))
                                pairs)
                sorted-keys (mapv :key-bytes sorted)
                val-encodes (map (fn [v] [:encode v])
                                 (reverse (mapv :val sorted)))]
            (recur (into (conj work [:map-combine n sorted-keys]) val-encodes)
                   rest-done))

          :map-combine
          (let [n (nth task 1)
                key-bytes (nth task 2)
                split (- (count done) n)
                val-bytes (subvec done split)
                rest-done (subvec done 0 split)
                elements (mapcat (fn [kb vb] [kb vb]) key-bytes val-bytes)
                combined (ba-concat-all (list* (ba-of tag-map) (encode-varuint n) elements))]
            (recur work (conj rest-done combined))))))))

(defn canonical-bytes-hex
  "Lowercase hex of a value's canonical encoding (deterministic sort key and
   portable canonical-bytes commitment)."
  [v]
  (apply str (map #(format "%02x" (bit-and % 0xff)) (canonical-bytes v))))

