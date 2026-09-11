(ns resolver-sim.evidence.timestamping-test
  (:require [clojure.test :refer :all]
            [resolver-sim.evidence.timestamping :as ts]
            [resolver-sim.hash.canonical :as hc]))

(defn- test-tsa-url []
  (or (System/getenv "TSA_URL") "https://freetsa.org/tsr"))

(deftest tsa-request-freetsa
  (let [url (test-tsa-url)
        test-hash (hc/hash-with-intent {:hash/intent :evidence-record} {:test "freetsa-integration" :time (str (java.time.Instant/now))})
        result (ts/tsa-request test-hash :tsa-url url :timeout-ms 30000)]
    (if (:error result)
      (do
        (println "TSA request note:" (:error result))
        (is (string? (:error result)) "TSA error is a string (network/TSA may be unavailable)"))
      (do
        (is (:response-hex result) "Response hex is present")
        (is (:time result) "Timestamp time is present")
        (is (:serial result) "Serial number is present")
        (is (= test-hash (:hash result)) "Hash matches")
        (println "TSA timestamp obtained: time:" (:time result) "serial:" (:serial result))))))

(deftest tsa-request-freetsa-empty-hash
  (let [url (test-tsa-url)
        result (ts/tsa-request "" :tsa-url url :timeout-ms 10000)]
    (if (:error result)
      (println "Empty hash result:" (:error result))
      (do
        (is (:response-hex result) "Empty hash still produces a valid TSA response")
        (println "Empty hash produces a valid TSA response (TSA accepts any SHA-256 hash)")))))

(deftest tsa-request-invalid-url
  (let [result (ts/tsa-request "abcd" :tsa-url "https://invalid-tsa-url.example/tsa" :timeout-ms 5000)]
    (is (:error result) "Invalid TSA URL returns error")))

(deftest tsa-request-no-url
  (let [result (ts/tsa-request "abcd")]
    (is (:error result) "No TSA URL returns error")
    (is (.contains (:error result) "No TSA URL configured"))))

(deftest write-tsa-timestamp-creates-files
  (let [tmp-dir (str (System/getProperty "java.io.tmpdir") "/tsa-test-" (java.util.UUID/randomUUID))
        test-hash (hc/hash-with-intent {:hash/intent :evidence-record} {:test "write-test"})
        result (ts/write-tsa-timestamp! test-hash :dir tmp-dir :tsa-url (test-tsa-url) :timeout-ms 30000)]
    (if (:error result)
      (println "TSA write test note:" (:error result))
      (do
        (is (.exists (java.io.File. (:tsr-path result))) "registry.tsr exists")
        (is (.exists (java.io.File. (:tsq-path result))) "registry.tsq exists")
        (is (.exists (java.io.File. (:tsa-path result))) "registry.tsa.json exists")
        (is (:tsa-envelope result) "TSA envelope metadata present")
        (is (= :registry/hash (get-in result [:tsa-envelope :tsa/input-kind])) "Input kind is registry/hash")
        (println "Wrote TSA artifacts to:" tmp-dir)))))

(deftest verify-tsa-token-round-trip
  (let [url (test-tsa-url)
        test-hash (hc/hash-with-intent {:hash/intent :evidence-record} {:test "verify-round-trip"})
        req-result (ts/tsa-request test-hash :tsa-url url :timeout-ms 30000)]
    (if (:error req-result)
      (println "TSA verify test note: TSA unavailable, skipping:" (:error req-result))
      (let [verify-result (ts/verify-tsa-token (:response-hex req-result) test-hash)]
        (is (:valid verify-result) "Timestamp token verifies against original hash")
        (is (= test-hash (:hash verify-result)) "Hash matches in verify result")
        (is (:time verify-result) "Time present in verify result")
        (println "TSA token verified: time" (:time verify-result) "serial" (:serial verify-result))))))

(deftest verify-tsa-token-tampered-hash
  (let [url (test-tsa-url)
        test-hash (hc/hash-with-intent {:hash/intent :evidence-record} {:test "tamper-test"})
        req-result (ts/tsa-request test-hash :tsa-url url :timeout-ms 30000)]
    (if (:error req-result)
      (println "TSA tamper test note: TSA unavailable, skipping:" (:error req-result))
      (let [wrong-hash (hc/hash-with-intent {:hash/intent :evidence-record} {:test "tampered-data"})
            verify-result (ts/verify-tsa-token (:response-hex req-result) wrong-hash)]
        (is (not (:valid verify-result)) "Tampered hash does NOT verify")
        (is (:error verify-result) "Error message present for tampered hash")))))

(deftest verify-tsa-token-from-file-round-trip
  (let [tmp-dir (str (System/getProperty "java.io.tmpdir") "/tsa-verify-" (java.util.UUID/randomUUID))
        test-hash (hc/hash-with-intent {:hash/intent :evidence-record} {:test "verify-from-file"})
        write-result (ts/write-tsa-timestamp! test-hash :dir tmp-dir :tsa-url (test-tsa-url) :timeout-ms 30000)]
    (if (:error write-result)
      (println "TSA verify-from-file test note: TSA unavailable, skipping:" (:error write-result))
      (let [verify-result (ts/verify-tsa-token-from-file test-hash :dir tmp-dir)]
        (is (:timestamp/verified? verify-result) "TSA token verifies from file")
        (is (= test-hash (:registry/hash verify-result)) "Registry hash matches")
        (is (:timestamp/gen-time verify-result) "Gen time present in file verify")
        (println "TSA token verified from file: time" (:timestamp/gen-time verify-result))))))
