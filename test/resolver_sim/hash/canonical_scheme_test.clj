(ns resolver-sim.hash.canonical-scheme-test
  "Regression coverage for the explicit versioned canonical hash scheme.

   Proves that pinning the hash scheme into the intent-resolution contract:
     1. resolves the current scheme to SHA-256 (canonical-hash-scheme.v1),
     2. leaves representative canonical roots byte-for-byte unchanged
        (literals captured from the pre-refactor implementation),
     3. fails closed on unknown/unsupported schemes,
     4. exposes the resolved scheme on every registered hash intent."
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [resolver-sim.hash.algorithm :as halgo]
            [resolver-sim.hash.canonical :as hc])
  (:import [java.security MessageDigest]))

(defn -main [& _]
  (run-tests 'resolver-sim.hash.canonical-scheme-test))

(defn- hex
  "Byte array → lowercase hex string."
  [^bytes ba]
  (apply str (map #(format "%02x" (bit-and % 0xFF)) ba)))

;; Representative preimages — keep in sync with the pinned literals below.
(def ^:private ev   {:action/type "submit" :result {:status "pass"} :step 7})
(def ^:private ws   {:positions {"alice" 10} :balances {"bob" 3}})
(def ^:private mf   {:name "run-1" :artifacts ["a" "b"]})
(def ^:private prar {:allocations [{:claimant "a" :amount 40} {:claimant "b" :amount 60}]
                     :round 3})

;; Captured BEFORE the explicit-hash-scheme refactor. These literals are the
;; before/after invariant evidence: before-root(x) == after-root(x).
(def ^:private pinned-roots
  {:evidence-record           "89f96b9fd8d72e424bf67627037c95883d747fde23183048f0d594a26fa3ef82"
   :world-structure           "2e4e77554aaf08417e4f8fddf791be16b5c18550dd44d635019b7b5d58e05a45"
   :manifest                  "06dd478e6c51684f397c58913b2fdea8c5cbcfc20ba3aa176ef43c51b5c726b6"
   :pro-rata-allocation-result "f42f0da6afa628321fb8844000a45a48cf7a24e54b76b974e16113d9f34f06c3"
   :domain-hash-registry      "36fa65674f95a230b50d1b16fe884dd80fe728beff27000aec35b46fbe10a613"
   :domain-hash-allocation-context "47ab7096c2002547a73d8c921665810b580e6f23fc6e68ed537e53f6cf125a0e"})

(deftest current-scheme-is-pinned-sha256
  (testing "the immutable registry binds canonical-hash-scheme.v1 → SHA-256"
    (is (= {"canonical-hash-scheme.v1" {:algorithm :sha256}}
           hc/canonical-hash-schemes))
    (is (= {:scheme-version "canonical-hash-scheme.v1"
            :algorithm      :sha256}
           hc/canonical-hash-scheme-v1))
    (is (= hc/canonical-hash-scheme-v1
           (hc/resolve-canonical-hash-scheme "canonical-hash-scheme.v1")))
    (is (= :sha256 halgo/default-hash-algorithm))
    (is (halgo/supported-hash-algorithm? (:algorithm hc/canonical-hash-scheme-v1))))
  (testing "hash-bytes really is plain SHA-256 (known-answer vector)"
    ;; SHA256("abc") per FIPS 180-4.
    (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
           (hex (hc/hash-bytes (.getBytes "abc" "UTF-8"))))
        "hash-bytes must be a single unkeyed SHA-256 over the input bytes"))
  (testing "scheme-dispatched digest executes the bound algorithm"
    (let [in (.getBytes "abc" "UTF-8")]
      (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
             (hex (hc/hash-bytes-with-scheme hc/canonical-hash-scheme-v1 in))))
      ;; domain hashing under the explicit scheme equals the v1 convenience API.
      (is (= (hc/domain-hash :registry {:k [:a "b" 1 true nil]})
             (hc/domain-hash-with-scheme hc/canonical-hash-scheme-v1
                                         :registry {:k [:a "b" 1 true nil]}))))))

(deftest validate-hash-scheme-fails-closed
  (testing "supported scheme passes through unchanged"
    (is (= hc/canonical-hash-scheme-v1
           (hc/validate-hash-scheme! hc/canonical-hash-scheme-v1))))
  (testing "unregistered scheme versions are rejected — no silent future drift"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unregistered canonical hash scheme version"
                          (hc/validate-hash-scheme!
                           {:scheme-version "canonical-hash-scheme.v2"
                            :algorithm :keccak256})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unregistered canonical hash scheme version"
                          (hc/resolve-canonical-hash-scheme "canonical-hash-scheme.v2"))))
  (testing "version and algorithm are inseparable: mismatch is rejected even if the algorithm is plausible"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not match its registered algorithm binding"
                          (hc/validate-hash-scheme!
                           {:scheme-version "canonical-hash-scheme.v1"
                            :algorithm :poseidon2})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not match its registered algorithm binding"
                          (hc/validate-hash-scheme!
                           {:scheme-version "canonical-hash-scheme.v1"
                            :algorithm :sha3-256}))))
  (testing "missing or empty scheme version is rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":scheme-version must be a non-empty string"
                          (hc/validate-hash-scheme! {:algorithm :sha256})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":scheme-version must be a non-empty string"
                          (hc/validate-hash-scheme! {:scheme-version "" :algorithm :sha256}))))
  (testing "the underlying algorithm vocabulary itself rejects unknown algorithms"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unsupported hash algorithm"
                          (halgo/validate-hash-algorithm! :md5)))))

(deftest representative-roots-unchanged
  (testing "hash-with-intent and domain-hash outputs match pre-refactor literals"
    (is (= (:evidence-record pinned-roots)
           (hc/hash-with-intent {:hash/intent :evidence-record} ev)))
    (is (= (:world-structure pinned-roots)
           (hc/hash-with-intent {:hash/intent :world-structure} ws)))
    (is (= (:manifest pinned-roots)
           (hc/hash-with-intent {:hash/intent :manifest} mf)))
    (is (= (:pro-rata-allocation-result pinned-roots)
           (hc/hash-with-intent {:hash/intent :pro-rata-allocation-result} prar)))
    (is (= (:domain-hash-registry pinned-roots)
           (hc/domain-hash :registry {:k [:a "b" 1 true nil]})))
    (is (= (:domain-hash-allocation-context pinned-roots)
           (hc/domain-hash :allocation-context {:available 100 :requested [40 60]})))))

(deftest resolved-intents-expose-pinned-scheme
  (testing "every registered intent resolves with the immutable v1 scheme attached"
    (let [expected hc/canonical-hash-scheme-v1]
      (is (pos? (count (keys hc/hash-intents))))
      (doseq [kw (keys hc/hash-intents)]
        (is (= expected (:intent/hash-scheme (hc/resolve-intent kw)))
            (str "intent resolution must expose the scheme for " kw)))))
  (testing "resolution still fails closed on unknown intents"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown hash intent"
                          (hc/resolve-intent :does-not-exist)))))
