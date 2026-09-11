(ns resolver-sim.hash.canonical-byte-contract-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.hash.canonical :as hc]))

(deftest byte-contract-exposes-value-and-framed-preimage
  (let [value {:a 1 :b "café"}
        value-bytes (hc/canonical-bytes value)
        framed (hc/domain-hash-preimage-bytes "byte-contract.v1" value)]
    (is (= (hc/canonical-bytes-hex value)
           (hc/bytes->hex value-bytes)))
    (is (= (str "627974652d636f6e74726163742e7631"
                (hc/canonical-bytes-hex value))
           (hc/bytes->hex framed)))
    (is (= (hc/domain-hash "byte-contract.v1" value)
           (hc/bytes->hex (hc/hash-bytes framed))))))

(deftest canonical-adversarial-distinctions
  (is (= (hc/canonical-bytes-hex {:a 1 :b 2})
         (hc/canonical-bytes-hex {:b 2 :a 1})))
  (is (not= (hc/canonical-bytes-hex :foo)
            (hc/canonical-bytes-hex "foo")))
  (is (not= (hc/canonical-bytes-hex {})
            (hc/canonical-bytes-hex {:x nil})))
  (is (not= (hc/canonical-bytes-hex {})
            (hc/canonical-bytes-hex [])))
  (is (not= (hc/canonical-bytes-hex 0)
            (hc/canonical-bytes-hex "0")))
  (is (not= (hc/canonical-bytes-hex "café")
            (hc/canonical-bytes-hex "cafe"))))
