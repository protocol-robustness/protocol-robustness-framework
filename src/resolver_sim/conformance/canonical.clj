(ns resolver-sim.conformance.canonical
  "Canonical serialization + content roots shared across implementations.

   Envelope roots (reconciliation, plan, coverage, identity, environment) are
   computed over a deterministic canonical-JSON form (sorted keys, keywords as
   names), so they are STABLE across JSON serialization round-trips and can be
   reproduced byte-for-byte by the Python bundle verifier.  This makes a bundle
   offline-verifiable from its serialized form."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]))

(defn key->json-name
  [k]
  (if (keyword? k)
    (if-let [ns (namespace k)] (str ns "/" (name k)) (name k))
    (str k)))

(defn sort-keys
  "Recursively sort map keys for deterministic canonical JSON."
  [x]
  (cond
    (map? x) (into (sorted-map)
                   (map (fn [[k v]] [(key->json-name k) (sort-keys v)])) x)
    (vector? x) (mapv sort-keys x)
    (seq? x) (mapv sort-keys x)
    (set? x) (mapv sort-keys (sort-by str x))
    :else x))

(defn canonical-json-str
  "Deterministic canonical JSON serialization shared with the Python verifier."
  [x]
  (str/replace (json/write-str (sort-keys x)) "\\/" "/"))

(defn root
  "sha256 (hex) of the canonical JSON serialization of x (the content root)."
  [x]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        digest (.digest md (.getBytes (canonical-json-str x) "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and (int %) 0xFF)) digest))))
