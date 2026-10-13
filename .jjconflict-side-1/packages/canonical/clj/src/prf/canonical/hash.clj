(ns prf.canonical.hash
  "Generic domain-separated SHA-256 over canonical-hash-scheme.v1 bytes."
  (:require [prf.canonical.codec :as codec])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(defn hash-with-domain
  "Hash UTF-8 domain-tag bytes followed by canonical value bytes.

   The domain is not length-prefixed or delimiter-separated. Domains identify
   artifact types and are owned by their semantic packages."
  [domain-tag value]
  (when-not (string? domain-tag)
    (throw (ex-info "Canonical hash domain must be a string"
                    {:reason :invalid-canonical-domain :domain domain-tag})))
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (.update digest (.getBytes ^String domain-tag StandardCharsets/UTF_8))
    (let [bytes (.digest digest ^bytes (codec/canonical-bytes value))]
      (apply str (map #(format "%02x" (bit-and 0xff %)) bytes)))))

(defn resolve-domain-tag
  "Resolve a legacy keyword domain through the owning module's domain map.

   Domain names remain owned by semantic modules. The caller supplies that
   module's immutable keyword-to-tag mapping; literal string tags pass through."
  [domain domain-tags]
  (cond
    (string? domain) domain
    (keyword? domain) (or (get domain-tags domain)
                          (throw (ex-info "Unknown canonical domain keyword"
                                          {:reason :unknown-canonical-domain
                                           :domain domain})))
    :else (throw (ex-info "Canonical domain must be a string or keyword"
                          {:reason :invalid-canonical-domain
                           :domain domain}))))

(defn domain-hash
  "Compatibility name for a domain-separated root.

   String domains need no registry. Keyword domains resolve only through the
   semantic owner's supplied mapping, keeping the foundation's domain table
   empty while preserving legacy domain bytes."
  ([domain value]
   (domain-hash domain value {}))
  ([domain value domain-tags]
   (hash-with-domain (resolve-domain-tag domain domain-tags) value)))

(defn project-canonical-safe
  "Apply the legacy generic canonical-safe projection (sets sorted by `str`).
   This does not select domain fields or define artifact semantics."
  [value]
  (cond
    (set? value) (vec (sort-by str value))
    (map? value) (into {} (map (fn [[k v]] [k (project-canonical-safe v)]) value))
    (sequential? value) (mapv project-canonical-safe value)
    :else value))

(defn project-committable-content
  "Apply legacy runtime-value coercions used by content commitments."
  [value]
  (letfn [(walk [x]
            (cond
              (instance? clojure.lang.Ratio x)
              {:canonical/type "ratio" :canonical/numerator (numerator x)
               :canonical/denominator (denominator x)}
              (instance? Double x)
              {:canonical/type "float64" :canonical/hex (Double/toHexString x)}
              (instance? Float x)
              {:canonical/type "float32" :canonical/hex (Float/toHexString x)}
              (instance? java.math.BigDecimal x)
              {:canonical/type "big-decimal" :canonical/value (.toPlainString x)}
              (instance? java.time.temporal.TemporalAccessor x) (str x)
              (set? x) (let [projected (map walk x)]
                         (vec (sort-by #(apply str
                                               (map (fn [b] (format "%02x" (bit-and 0xff b)))
                                                    (codec/canonical-bytes %)))
                                       projected)))
              (map? x) (into {} (map (fn [[k v]] [(walk k) (walk v)]) x))
              (vector? x) (mapv walk x)
              (sequential? x) (mapv walk x)
              :else x))]
    (walk value)))

(defn intent-hash=
  "Compare legacy hash strings or intent-aware {:hash/intent :hash/hex} maps."
  ([a b] (intent-hash= a b nil))
  ([a b {:keys [allow-cross-intent?] :or {allow-cross-intent? false}}]
   (let [a-intent (when (map? a) (:hash/intent a))
         b-intent (when (map? b) (:hash/intent b))
         a-hash (if (map? a) (:hash/hex a) a)
         b-hash (if (map? b) (:hash/hex b) b)]
     (and (or allow-cross-intent? (not (and a-intent b-intent (not= a-intent b-intent))))
          (= a-hash b-hash)))))

(defn hash-with-intent
  "Hash using a domain contract supplied by its semantic owner.

   Contract fields: :hash/intent, :intent/domain-tag, and optional
   :intent/projection-fn. Projection functions may accept value alone or
   value plus intent. An optional :intent/validate! callback enforces
   owner-specific admission constraints."
  [{:keys [hash/intent intent/domain-tag intent/projection-fn intent/validate!]} value]
  (when-not (and intent (string? domain-tag))
    (throw (ex-info "Intent contract requires :hash/intent and :intent/domain-tag"
                    {:reason :invalid-intent-contract :intent intent
                     :domain-tag domain-tag})))
  (when validate! (validate! value))
  (let [projected (if projection-fn
                    (try
                      (projection-fn value intent)
                      (catch clojure.lang.ArityException _
                        (projection-fn value)))
                    value)]
    (hash-with-domain domain-tag projected)))
