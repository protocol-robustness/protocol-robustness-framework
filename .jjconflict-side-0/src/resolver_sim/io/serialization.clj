(ns resolver-sim.io.serialization
  "Protocol and utilities for JSON-safe data conversion."
  (:require [clojure.data.json :as json]))

(defprotocol ToJsonData
  (to-json-data [this] "Convert this record or object to a JSON-serializable structure."))

(extend-protocol ToJsonData
  clojure.lang.IPersistentMap
  (to-json-data [this]
    (into {} (map (fn [[k v]] [k (to-json-data v)]) this)))

  clojure.lang.IPersistentVector
  (to-json-data [this]
    (mapv to-json-data this))

  clojure.lang.IPersistentSet
  (to-json-data [this]
    (mapv to-json-data this))

  clojure.lang.Keyword
  (to-json-data [this]
    (name this))

  clojure.lang.Symbol
  (to-json-data [this]
    (str this))

  ;; Generic fallback for other records or types
  clojure.lang.IRecord
  (to-json-data [this]
    (into {} (map (fn [[k v]] [k (to-json-data v)]) this)))

  java.lang.Object
  (to-json-data [this]
    (cond
      (fn? this) (str "fn:" (.getName (class this)))
      (instance? clojure.lang.IRecord this)
      (into {} (map (fn [[k v]] [k (to-json-data v)]) this))
      :else (str this)))

  nil
  (to-json-data [this] nil))

(defn- json-key [k]
  (if (keyword? k) (name k) (str k)))

(defn serialize-artifact
  "JSON string for artifacts that may contain yield module ops (fns), records, etc."
  ([artifact]
   (serialize-artifact artifact {}))
  ([artifact {:keys [pretty?]}]
   (let [data (to-json-data artifact)]
     (if pretty?
       (json/write-str data :key-fn json-key :indent true)
       (json/write-str data :key-fn json-key)))))
