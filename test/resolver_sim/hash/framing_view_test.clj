(ns resolver-sim.hash.framing-view-test
  "Tests for resolver-sim.hash.framing-view.

   These make the consecutive-concatenation invariant from
   CANONICAL_HASH_SPEC_V1 §9 concrete and machine-checkable: the framed walk
   tiles the concatenated stream with no gaps or overlaps, each boundary is
   uniquely determined by the previous component's tag + prefix, and the
   frames agree with an independent decode of the same stream."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.framing-view :as fv]))

(defn- bytes= [^bytes a ^bytes b]
  (java.util.Arrays/equals a b))

(deftest frame-stream-tiles-the-stream-exactly
  (testing "component frames are consecutive: next == following offset, no gaps"
    (doseq [values [[1 "active" :a/b [1 2] {:x 1}]
                    []
                    ["only"]
                    [nil true false 0 -1 Long/MIN_VALUE]
                    ["a" "ab" "abc"]
                    [1 [2 [3 [4]]] {:k "v" :j [1 2]}]]]
      (let [d (fv/decompose-values values)
            frames (:frames d)]
        (is (= (:total-bytes d)
               (:next (peek (:frames d)) 0))
            (str "last boundary lands at stream end: " (pr-str values)))
        (is (every? (fn [[a b]] (= (:next a) (:offset b)))
                    (partition 2 1 frames))
            (str "each boundary is the next frame's offset: " (pr-str values)))
        (is (= (map :value frames) (vec values))
            (str "frames decode back to the ordered input sequence: " (pr-str values)))))))

(deftest every-byte-has-exactly-one-annotation
  (let [values ["x" 1 :k [2 "y"]]
        d (fv/decompose-values values)
        n (:total-bytes d)]
    (is (= n (count (:roles d)))
        "every stream byte is annotated exactly once")
    (is (= (set (range n)) (set (keys (:roles d))))
        "annotations cover exactly the byte offsets 0..n-1")
    (is (every? (fn [{:keys [role component]}]
                  (and (contains? #{:tag :len :count :payload} role)
                       (pos-int? component)))
                (vals (:roles d)))
        "every byte has a known role and a component index")))

(deftest boundaries-uniquely-determined-by-prefix
  (testing "splitting the stream at any component boundary and re-decoding
            yields the same frames from both sides (no alternative boundary)"
    (let [values [1 "active" :a/b [1 2] {:x 1}]
          d (fv/decompose-values values)
          stream (:stream d)]
      (doseq [f (:frames d)]
        (let [left (fv/frame-stream (java.util.Arrays/copyOfRange ^bytes stream 0 (:next f)))
              right (fv/frame-stream (java.util.Arrays/copyOfRange ^bytes stream (:next f) (count stream)))]
          (is (= (:frames left) (subvec (:frames d) 0 (:component f))))
          (is (= (map :value (:frames right)) (subvec values (:component f)))))))))

(deftest frames-agree-with-independent-decoder
  (testing "frame values match the reference canonical encoder round-trip"
    (doseq [values [["active" :resolver/id 128 [1 "two" :three] {:a 1 :b 2}]
                    [nil false true 0 -1 Long/MAX_VALUE Long/MIN_VALUE]]]
      (let [d (fv/decompose-values values)]
        (is (every? (fn [[a b]] (bytes= a b))
                    (map vector (map hc/canonical-bytes values) (:component-bytes d)))
            "component-bytes are exactly the reference canonical encodings")
        (is (bytes= (fv/concat-bytes (:component-bytes d)) (:stream d)))
        (is (= (map :value (:frames d)) values))))))

(deftest counter-example-vector-framing-vs-naive-concat
  (testing "a vector's framing is never the naive concatenation of its parts,
            and the role dump shows why (count prefix + nested tags)"
    (let [d (fv/decompose-values [[1 2 3]])
          naive (fv/concat-bytes (map hc/canonical-bytes [1 2 3]))]
      (is (not (bytes= (:stream d) naive))
          "canonical-bytes([1 2 3]) != encode(1)||encode(2)||encode(3)")
      (is (= [1 2 3] (get-in d [:frames 0 :value])))
      (is (= :vector (get-in d [:frames 0 :tag-name])))
      (is (some (fn [e] (and (= :count (:role e)) (= 1 (:component e))))
                (vals (:roles d)))
          "the count prefix byte is annotated as :count"))))

(deftest byte-table-and-dump-render-without-error
  (let [d (fv/decompose-values [1 "active" :a/b [1 2]])
        table (fv/byte-table d)
        lines (fv/dump-lines d)]
    (is (= (:total-bytes d) (count table)))
    (is (= 4 (count lines)))
    (is (every? string? lines))
    (is (= (mapv :hex table)
           (mapv (fn [b] (format "%02x" (bit-and b 0xff))) (:stream d))))))

(deftest dump-lines-show-component-boundaries
  (let [lines (fv/dump-lines (fv/decompose-values [1 2 3]))]
    (is (re-find #"#1" (nth lines 0)))
    (is (re-find #"#3" (nth lines 2)))
    (is (re-find #"integer" (nth lines 0)))))

;; ── Nested paths ────────────────────────────────────────────────────────────

(deftest roles-carry-nested-paths-and-types
  (let [d (fv/decompose-values [[1 2]])
        roles (:roles d)]
    (is (= {:role :tag :path [1] :type :vector :slot nil}
           (dissoc (get roles 0) :component))
        "component root tag carries the root path and its own type")
    (is (= {:role :tag :path [1 0] :type :integer :slot :element}
           (dissoc (get roles 2) :component))
        "first element tag carries a nested path, type, and slot")
    (is (= {:role :len :path [1 1] :type :integer :slot :element}
           (dissoc (get roles 5) :component))
        "second element len prefix is annotated at its nested path")))

(deftest map-roles-distinguish-key-and-value-and-expose-order
  (let [d (fv/decompose-values [{:b 1 :a 2}])
        frame (first (:frames d))
        roles (:roles d)]
    (is (= :map (:tag-name frame)))
    (is (= [:a :b] (:map-keys frame))
        "canonical key order (a before b) is exposed")
    (is (= 2 (count (:map-key-bytes frame))))
    (is (some (fn [e] (= :map-key (:slot e))) (vals roles))
        "key bytes are slotted :map-key")
    (is (some (fn [e] (= :map-value (:slot e))) (vals roles))
        "value bytes are slotted :map-value")))

;; ── Fail-closed canonicality ────────────────────────────────────────────────

(deftest verify-ok-on-canonical-stream
  (let [v (fv/verify-stream (fv/concat-bytes (map hc/canonical-bytes [1 "active" [2 3]])))]
    (is (:well-framed? v))
    (is (:fully-consumed? v))
    (is (:canonical? v))
    (is (empty? (:issues v)))
    (is (= 3 (:component-count v)))))

(deftest verify-fails-closed-on-truncated-stream
  (let [ba (fv/concat-bytes (map hc/canonical-bytes ["active"]))
        truncated (java.util.Arrays/copyOfRange ^bytes ba 0 3)
        v (fv/verify-stream truncated)]
    (is (not (:well-framed? v)))
    (is (not (:canonical? v)))
    (is (some #(= :length-exceeds-bytes (:code %)) (:issues v)))))

(deftest verify-fails-closed-on-unknown-tag
  (let [v (fv/verify-stream (byte-array [0x77 0x00]))]
    (is (not (:well-framed? v)))
    (is (some #(= :unknown-tag (:code %)) (:issues v)))))

(deftest verify-fails-closed-on-non-minimal-varint
  (let [non-minimal (byte-array [0x10 0x80 0x00])  ;; integer 0 encoded in 2 bytes
        v (fv/verify-stream non-minimal)]
    (is (:well-framed? v) "parseable but non-canonical")
    (is (:fully-consumed? v))
    (is (not (:canonical? v)))
    (is (some #(= :non-minimal-varint (:code %)) (:issues v)))))

(deftest verify-fails-closed-on-noncanonical-map-order
  (let [noncanonical (byte-array [0x31 0x02 0x22 0x01 0x62 0x10 0x02
                                  0x22 0x01 0x61 0x10 0x04])
        ;; {:b 2 :a 4} — keys not in canonical ascending order
        v (fv/verify-stream noncanonical)]
    (is (not (:canonical? v)))
    (is (some #(= :noncanonical-map-order (:code %)) (:issues v)))))

(deftest verify-fails-closed-on-duplicate-map-keys
  (let [dup (byte-array [0x31 0x02 0x22 0x01 0x61 0x10 0x02
                         0x22 0x01 0x61 0x10 0x04])
        v (fv/verify-stream dup)]
    (is (not (:canonical? v)))
    (is (some #(= :duplicate-map-key (:code %)) (:issues v)))))

(deftest verify-single-rejects-trailing-bytes
  (let [d (fv/decompose-values [1 2])
        v (fv/verify-single (:stream d))]
    (is (:well-framed? v) "the stream is parseable as a sequence")
    (is (not (:single? v)) "but not a single value")
    (is (not (:canonical? v)) "canonical single-value acceptance fails closed")))

(deftest verify-single-rejects-multiple-components
  (let [d (fv/decompose-values [1 2])
        v (fv/verify-single (:stream d))]
    (is (not (:single? v)))))

(deftest verify-single-accepts-one-component
  (let [v (fv/verify-single (hc/canonical-bytes {:a 1}))]
    (is (:canonical? v))
    (is (:single? v))))

(deftest verify-values-roundtrip
  (is (:canonical? (fv/verify-values [nil false 0 -1 Long/MIN_VALUE "é" :k [1 [2]] {:a 1}]))))

;; ── Resource limits ─────────────────────────────────────────────────────────

(deftest frame-stream-respects-max-stream-bytes
  (let [ba (fv/concat-bytes (map hc/canonical-bytes ["abcdefgh"]))
        r (fv/frame-stream ba {:limits {:max-stream-bytes 4}})]
    (is (= :limit-exceeded (:status r)))
    (is (= :max-stream-bytes (:reason r)))))

(deftest frame-stream-respects-max-component-count
  (let [ba (fv/concat-bytes (map hc/canonical-bytes [1 2 3 4 5]))
        r (fv/frame-stream ba {:limits {:max-component-count 3}})]
    (is (= :limit-exceeded (:status r)))
    (is (= :max-component-count (:reason r)))))

(deftest frame-stream-respects-max-payload-bytes
  (let [ba (hc/canonical-bytes "abcdefghij")
        r (fv/frame-stream ba {:limits {:max-payload-bytes 4}})]
    (is (= :limit-exceeded (:status r)))
    (is (= :max-payload-bytes (:reason r)))))

(deftest frame-stream-respects-max-collection-depth
  (let [ba (hc/canonical-bytes [1 [2 [3 [4 [5 [6 [7 [8 [9 [10]]]]]]]]]])
        r (fv/frame-stream ba {:limits {:max-collection-depth 3}})]
    (is (= :limit-exceeded (:status r)))
    (is (= :max-collection-depth (:reason r)))))

(deftest frame-stream-respects-max-collection-members
  (let [ba (hc/canonical-bytes (vec (range 100)))
        r (fv/frame-stream ba {:limits {:max-collection-members 10}})]
    (is (= :limit-exceeded (:status r)))
    (is (= :max-collection-members (:reason r)))))

(deftest length-validated-before-allocation
  (let [ba (byte-array [0x20 0x64 0x61 0x62])  ;; string claims length 100, only 2 bytes remain
        v (fv/verify-stream ba)]
    (is (not (:well-framed? v)))
    (is (some #(= :length-exceeds-bytes (:code %)) (:issues v)))))

;; ── Redaction ───────────────────────────────────────────────────────────────

(deftest redacted-byte-table-masks-payloads
  (let [d (fv/decompose-values ["secret" 1])
        table (fv/byte-table d {:redact-payload? true})]
    (is (= "··" (:hex (nth table 2))) "payload bytes masked")
    (is (not= "··" (:hex (nth table 0))) "tag bytes not masked")))

(deftest redacted-dump-shows-payload-commitment-not-value
  (let [d (fv/decompose-values ["top-secret"])
        lines (fv/dump-lines d {:redact? true})
        line (first lines)]
    (is (re-find #"redacted" line))
    (is (re-find #"sha256:[0-9a-f]{64}" line))
    (is (not (re-find #"top-secret" line)))
    (is (some? (get-in d [:frames 0 :payload-hash])))))

(deftest payload-hash-is-deterministic
  (let [a (fv/decompose-values ["same"])
        b (fv/decompose-values ["same"])]
    (is (= (get-in a [:frames 0 :payload-hash])
           (get-in b [:frames 0 :payload-hash])))))

