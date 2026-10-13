(ns resolver-sim.hash.algorithm-test
  "Tests for the minimal explicit hash-algorithm representation."
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [resolver-sim.hash.algorithm :as halgo]))

(defn -main [& _]
  (run-tests 'resolver-sim.hash.algorithm-test))

(deftest supported-algorithms
  (testing ":sha256 is the supported algorithm"
    (is (true? (halgo/supported-hash-algorithm? :sha256)))
    (is (= :sha256 halgo/default-hash-algorithm)))
  (testing "unsupported algorithms are not supported"
    (is (false? (halgo/supported-hash-algorithm? :md5)))
    (is (false? (halgo/supported-hash-algorithm? :sha1)))))

(deftest validate-hash-algorithm
  (testing "accepted algorithm returns unchanged"
    (is (= :sha256 (halgo/validate-hash-algorithm! :sha256))))
  (testing "unsupported algorithm is rejected, not silently defaulted"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"unsupported hash algorithm"
                          (halgo/validate-hash-algorithm! :md5)))))

(deftest hash-algorithm-string
  (testing "stable textual form"
    (is (= "sha256" (halgo/hash-algorithm-string :sha256))))
  (testing "rejects unsupported algorithm"
    (is (thrown? clojure.lang.ExceptionInfo
                 (halgo/hash-algorithm-string :md5)))))
