(ns prf.canonical.decode
  "Bounded canonical-hash-scheme.v1 byte decoding and admission."
  (:require [prf.canonical.codec :as codec]))

(def default-limits
  "Portable V1 admission defaults; callers may supply a host profile."
  {:max-stream-bytes 1048576
   :max-component-count 10000
   :max-payload-bytes 1048576
   :max-collection-depth 64
   :max-collection-members 100000})

(def ^:private tag-types {0x00 :null 0x01 :boolean 0x02 :boolean
                          0x10 :integer 0x20 :string 0x22 :keyword
                          0x30 :vector 0x31 :map})

(defn- ubyte [^bytes bytes i]
  (bit-and (int (aget bytes i)) 0xff))

(defn- issue [code offset role & [data]]
  (merge {:code code :offset offset :role role} data))

(defn- read-varuint [^bytes bytes pos total]
  (loop [i pos place (bigint 1) value (bigint 0) count 0]
    (if (>= i total)
      nil
      (let [b (ubyte bytes i)
            value (+' value (*' (bigint (bit-and b 0x7f)) place))]
        (if (zero? (bit-and b 0x80))
          {:value value :next (inc i)
           :minimal? (or (= 1 (inc count))
                         (not (zero? (bit-and b 0x7f))))}
          (recur (inc i) (*' place 128) value (inc count)))))))

(defn- byte-compare [^bytes a ^bytes b]
  (loop [i 0]
    (if (= i (min (count a) (count b)))
      (compare (count a) (count b))
      (let [x (ubyte a i) y (ubyte b i)]
        (if (= x y) (recur (inc i)) (compare x y))))))

(defn- parse-value [^bytes bytes pos total depth limits]
  (when (>= pos total)
    (throw (ex-info "truncated canonical tag" {:code :truncated-tag :offset pos :role :tag})))
  (let [tag (ubyte bytes pos)
        payload-pos (inc pos)
        type (tag-types tag)]
    (when-not type
      (throw (ex-info "unknown canonical tag" {:code :unknown-tag :offset pos :role :tag :tag tag})))
    (case tag
      0x00 {:value nil :next payload-pos :issues []}
      0x01 {:value false :next payload-pos :issues []}
      0x02 {:value true :next payload-pos :issues []}
      0x10 (let [vu (read-varuint bytes payload-pos total)]
             (when-not vu
               (throw (ex-info "truncated canonical integer" {:code :truncated-length :offset payload-pos :role :len})))
             {:value (if (even? (:value vu))
                       (quot (:value vu) 2)
                       (-' (quot (inc (:value vu)) 2)))
              :next (:next vu)
              :issues (if (:minimal? vu) []
                          [(issue :non-minimal-varint payload-pos :len)])})
      (0x20 0x22) (let [vu (read-varuint bytes payload-pos total)]
                    (when-not vu
                      (throw (ex-info "truncated canonical string length" {:code :truncated-length :offset payload-pos :role :len})))
                    (let [length (:value vu)
                          start (:next vu)
                          end (+ start length)]
                      (when (> length (:max-payload-bytes limits))
                        (throw (ex-info "canonical payload limit exceeded"
                                        {:type :limit-exceeded :code :limit-exceeded
                                         :reason :max-payload-bytes
                                         :limit (:max-payload-bytes limits)
                                         :offset payload-pos :role :payload})))
                      (when (> end total)
                        (throw (ex-info "canonical string exceeds remaining bytes"
                                        {:code :length-exceeds-bytes :offset start
                                         :role :payload :declared length
                                         :available (- total start)})))
                      (let [text (String. bytes (int start) (int length) java.nio.charset.StandardCharsets/UTF_8)]
                        {:value (if (= tag 0x22) (keyword text) text)
                         :next end
                         :issues (if (:minimal? vu) []
                                     [(issue :non-minimal-varint payload-pos :len)])})))
      (0x30 0x31) (let [vu (read-varuint bytes payload-pos total)]
                    (when-not vu
                      (throw (ex-info "truncated canonical collection count"
                                      {:code :truncated-count :offset payload-pos :role :count})))
                    (let [n (:value vu)
                          count-end (:next vu)
                          initial-issues (if (:minimal? vu) []
                                             [(issue :non-minimal-varint payload-pos :count)])]
                      (when (> n (:max-collection-members limits))
                        (throw (ex-info "canonical collection limit exceeded"
                                        {:type :limit-exceeded :code :limit-exceeded
                                         :reason :max-collection-members
                                         :limit (:max-collection-members limits)
                                         :offset payload-pos :role :count})))
                      (when (> depth (:max-collection-depth limits))
                        (throw (ex-info "canonical nesting limit exceeded"
                                        {:type :limit-exceeded :code :limit-exceeded
                                         :reason :max-collection-depth
                                         :limit (:max-collection-depth limits)
                                         :offset pos :role :collection})))
                      (if (= tag 0x30)
                        (loop [i 0 cursor count-end values [] issues initial-issues]
                          (if (= i n)
                            {:value values :next cursor :issues issues}
                            (let [child (parse-value bytes cursor total (inc depth) limits)]
                              (recur (inc i) (:next child) (conj values (:value child))
                                     (into issues (:issues child))))))
                        (loop [i 0 cursor count-end values {} issues initial-issues
                               previous nil]
                          (if (= i n)
                            {:value values :next cursor :issues issues}
                            (let [key-start cursor
                                  key-result (parse-value bytes cursor total (inc depth) limits)
                                  key (:value key-result)
                                  key-bytes (codec/canonical-bytes key)
                                  key-type? (or (string? key) (keyword? key))
                                  order (when previous (byte-compare previous key-bytes))
                                  key-issues (cond
                                               (not key-type?) [(issue :noncanonical-map-key-type key-start :map-key {:key key})]
                                               (= order 0) [(issue :duplicate-map-key key-start :map-key {:key key})]
                                               (and order (pos? order)) [(issue :noncanonical-map-order key-start :map-key {:key key})]
                                               :else [])
                                  value-result (parse-value bytes (:next key-result) total (inc depth) limits)]
                              (recur (inc i) (:next value-result)
                                     (assoc values key (:value value-result))
                                     (into (into (into issues (:issues key-result)) key-issues)
                                           (:issues value-result))
                                     key-bytes))))))))))

(defn verify-single
  "Decode, canonicality-check and resource-check a byte stream as one value.

   Optional `{:limits ...}` overrides the portable admission defaults."
  ([bytes] (verify-single bytes nil))
  ([^bytes bytes options]
   (let [limits (merge default-limits (:limits options))
         total (count bytes)]
     (if (> total (:max-stream-bytes limits))
       {:valid? false :value nil
        :issues [(issue :limit-exceeded 0 :stream
                        {:reason :max-stream-bytes :limit (:max-stream-bytes limits)})]
        :resource-limit? true :resource-reason :max-stream-bytes
        :single? false :fully-consumed? false}
       (loop [pos 0 values [] issues []]
         (if (= pos total)
           (let [single? (= 1 (count values))]
             {:valid? (and single? (empty? issues))
              :value (when single? (first values))
              :issues issues :resource-limit? false :resource-reason nil
              :single? single? :fully-consumed? true})
           (if (>= (count values) (:max-component-count limits))
             {:valid? false :value nil
              :issues [(issue :limit-exceeded pos :component
                              {:reason :max-component-count
                               :limit (:max-component-count limits)})]
              :resource-limit? true :resource-reason :max-component-count
              :single? false :fully-consumed? false}
             (let [decoded (try
                             {:decoded (parse-value bytes pos total 0 limits)}
                             (catch clojure.lang.ExceptionInfo e
                               {:error (ex-data e)}))]
               (if-let [data (:error decoded)]
                 (let [limited? (= :limit-exceeded (:type data))]
                   {:valid? false :value nil :issues [data]
                    :resource-limit? limited?
                    :resource-reason (when limited? (:reason data))
                    :single? false :fully-consumed? false})
                 (let [{:keys [value next] parsed-issues :issues} (:decoded decoded)
                       encoded (try (codec/canonical-bytes value)
                                    (catch clojure.lang.ExceptionInfo _ nil))
                       same? (and encoded
                                  (java.util.Arrays/equals
                                   encoded
                                   (java.util.Arrays/copyOfRange bytes (int pos) (int next))))
                       next-issues (cond-> (into issues parsed-issues)
                                     (and (not same?) (empty? parsed-issues))
                                     (conj (issue :non-canonical-encoding pos :value)))]
                   (recur next (conj values value) next-issues)))))))))))
