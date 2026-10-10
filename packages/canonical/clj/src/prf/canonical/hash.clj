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
