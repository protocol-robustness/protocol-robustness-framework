(ns resolver-sim.lab.json
  "JSON-safe serialization for lab results.

   The lab normalizes PRF outputs (which may contain namespaced keywords,
   ratios, sets, and infinities) into plain JSON-safe data before they are
   written to the wire. Namespaced keywords are rendered as
   \"namespace/name\" so identities such as :party/alice survive round-trips."
  (:require [clojure.data.json :as json]
            [clojure.walk :as walk]))

(defn keyword->string
  [k]
  (cond
    (keyword? k)
    (if (namespace k) (str (namespace k) "/" (name k)) (name k))
    (string? k) k
    :else (str k)))

(defn- scalar->json-safe
  [v]
  (cond
    (keyword? v) (keyword->string v)
    (symbol? v) (str v)
    (instance? clojure.lang.Ratio v)
    [(numerator v) (denominator v)]
    (instance? Double v)
    (cond
      (Double/isNaN ^double v) "NaN"
      (= v Double/POSITIVE_INFINITY) "Infinity"
      (= v Double/NEGATIVE_INFINITY) "-Infinity"
      :else v)
    (instance? Float v)
    (cond
      (Float/isNaN ^float v) "NaN"
      (= v Float/POSITIVE_INFINITY) "Infinity"
      (= v Float/NEGATIVE_INFINITY) "-Infinity"
      :else v)
    (set? v) (vec v)
    (or (map? v) (vector? v)) v
    :else v))

(defn ->json-safe
  "Walk a value and produce JSON-safe data (strings/numbers/booleans/vectors/maps)."
  [value]
  (walk/postwalk (fn [x]
                   (if (and (map? x) (not (record? x)))
                     (into {}
                           (map (fn [[k v]]
                                  [(keyword->string (if (keyword? k) k (str k)))
                                   (scalar->json-safe v)]))
                           x)
                     (scalar->json-safe x)))
                 value))

(defn write-str
  "Serialize value as JSON. Keys are already converted by ->json-safe."
  [value]
  (json/write-str (->json-safe value)))

(defn read-str
  "Read JSON into a Clojure map with keyword keys."
  [s]
  (json/read-str s :key-fn keyword))
