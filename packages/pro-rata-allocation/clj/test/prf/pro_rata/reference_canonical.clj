(ns prf.pro-rata.reference-canonical
  "Test-only independent implementation of canonical-hash-scheme.v1."
  (:import [java.io ByteArrayOutputStream]
           [java.math BigInteger]
           [java.security MessageDigest]))

(def ^:private tags {:null 0x00 :false 0x01 :true 0x02 :integer 0x10
                     :string 0x20 :keyword 0x22 :vector 0x30 :map 0x31})

(defn- byte-array-of [& values]
  (byte-array (map unchecked-byte values)))

(defn- concat-bytes [parts]
  (let [out (ByteArrayOutputStream.)]
    (doseq [^bytes part parts] (.write out part 0 (alength part)))
    (.toByteArray out)))

(defn- varuint [n]
  (let [out (ByteArrayOutputStream.)]
    (loop [n (biginteger n)]
      (let [low (.intValue (.and n (BigInteger/valueOf 127)))
            next (.shiftRight n 7)]
        (.write out (if (.equals next BigInteger/ZERO) low (bit-or low 128)))
        (if (.equals next BigInteger/ZERO) (.toByteArray out) (recur next))))))

(defn- zigzag [n] (if (neg? n) (- (*' -2 n) 1) (*' 2 n)))
(defn- keyword-string [value]
  (if-let [namespace (namespace value)] (str namespace "/" (name value)) (name value)))

(defn- compare-bytes [^bytes left ^bytes right]
  (loop [index 0]
    (cond
      (= index (min (alength left) (alength right))) (compare (alength left) (alength right))
      (= (aget left index) (aget right index)) (recur (inc index))
      :else (compare (bit-and 255 (aget left index)) (bit-and 255 (aget right index))))))

(declare canonical-bytes)

(defn canonical-bytes [value]
  (cond
    (nil? value) (byte-array-of (:null tags))
    (false? value) (byte-array-of (:false tags))
    (true? value) (byte-array-of (:true tags))
    (integer? value) (concat-bytes [(byte-array-of (:integer tags)) (varuint (zigzag value))])
    (string? value) (let [encoded (.getBytes ^String value "UTF-8")]
                      (concat-bytes [(byte-array-of (:string tags)) (varuint (alength encoded)) encoded]))
    (keyword? value) (let [encoded (.getBytes ^String (keyword-string value) "UTF-8")]
                       (concat-bytes [(byte-array-of (:keyword tags)) (varuint (alength encoded)) encoded]))
    (vector? value) (concat-bytes (into [(byte-array-of (:vector tags)) (varuint (count value))]
                                        (map canonical-bytes value)))
    (map? value) (let [entries (sort (fn [[left] [right]]
                                       (neg? (compare-bytes (canonical-bytes left)
                                                            (canonical-bytes right))))
                                     value)]
                   (when-not (every? #(or (string? %) (keyword? %)) (keys value))
                     (throw (ex-info "Canonical map keys must be strings or keywords"
                                     {:reason :portable-contract/noncanonical-input})))
                   (concat-bytes (into [(byte-array-of (:map tags)) (varuint (count value))]
                                       (mapcat (fn [[key item]]
                                                 [(canonical-bytes key) (canonical-bytes item)]) entries))))
    :else (throw (ex-info "Value is outside the portable canonical domain"
                          {:reason :portable-contract/noncanonical-input
                           :value-class (some-> value class .getName)}))))

(defn hex [^bytes value]
  (apply str (map #(format "%02x" (bit-and 255 %)) value)))

(defn sha256 [^bytes value]
  (hex (.digest (MessageDigest/getInstance "SHA-256") value)))

(defn domain-hash [domain value]
  (sha256 (concat-bytes [(.getBytes ^String domain "UTF-8") (canonical-bytes value)])))
