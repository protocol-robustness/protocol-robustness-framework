(ns prf.canonical.codec-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [prf.canonical.codec :as codec]
            [prf.canonical.hash :as canonical-hash]))

(defn- conformance-vectors []
  (->> (slurp (io/resource "canonical-v1-vectors.tsv"))
       str/split-lines
       (remove #(or (str/blank? %) (str/starts-with? % "#")))
       (map #(str/split % #"\t"))
       (map (fn [[id bytes domain hash]]
              [id {:bytes bytes :domain domain :hash hash}]))
       (into {})))

(deftest canonical-v1-shared-clojure-vectors
  (let [fixtures (conformance-vectors)
        values {"null" nil
                "false" false
                "true" true
                "negative-one" -1
                "utf8-e-acute" "é"
                "keyword-x" :x
                "array-int-one-string-x" [1 "x"]
                "sorted-keyword-map" {:b false :a 1}}]
    (doseq [[id value] values]
      (is (= (:bytes (fixtures id)) (codec/canonical-bytes-hex value))))))

(deftest canonical-v1-domain-hash-matches-rust-vector
  (let [{:keys [domain hash]} ((conformance-vectors) "sorted-keyword-map")]
    (is (= hash (canonical-hash/hash-with-domain domain {:a 1 :b false})))))
