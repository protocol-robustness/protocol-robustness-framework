(ns resolver-sim.compare.canonical-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.compare.canonical :as canonical]))

(defn- tmp-file
  [content ext]
  (let [f (doto (java.io.File/createTempFile "canonical-compare" ext)
            (.deleteOnExit))]
    (spit f content)
    (.getPath f)))

;; ── canonical-equivalent? ──────────────────────────────────────────────────

(deftest equivalent-ignores-map-insertion-order
  (is (canonical/canonical-equivalent? {:a 1 :b [1 2 3]} {:b [1 2 3] :a 1})))

(deftest equivalent-treats-int-representations-uniformly
  (is (canonical/canonical-equivalent? 1 1N)))

(deftest equivalent-is-type-sensitive
  (is (not (canonical/canonical-equivalent? :active "active")))
  (is (not (canonical/canonical-equivalent? 1 "1")))
  (is (not (canonical/canonical-equivalent? true 1)))
  (is (canonical/canonical-equivalent? 1 (bigint 1))))

(deftest equivalent-distinguishes-keyword-and-string-keys
  (is (not (canonical/canonical-equivalent? {:a 1} {"a" 1})))
  (is (canonical/canonical-equivalent? {:a 1} {:a 1})))

(deftest equivalent-requires-exact-sequences
  (is (not (canonical/canonical-equivalent? [1 2 3] [1 2 4])))
  (is (not (canonical/canonical-equivalent? [1 2] [1 2 3]))))

;; ── first-divergence ───────────────────────────────────────────────────────

(deftest divergence-nil-when-equivalent
  (is (nil? (canonical/first-divergence {:a 1} {:a 1}))))

(deftest divergence-reports-type-mismatch
  (let [d (canonical/first-divergence :active "active")]
    (is (= [] (:path d)))
    (is (= :type (:kind d)))
    (is (= :active (:left d)))
    (is (= "active" (:right d)))))

(deftest divergence-reports-value-path
  (let [d (canonical/first-divergence {:a [1 {:x 1}]} {:a [1 {:x 2}]})]
    (is (= [:a 1 :x] (:path d)))
    (is (= :value (:kind d)))))

(deftest divergence-reports-missing-keys
  (let [d (canonical/first-divergence {:a 1} {:a 1 :b 2})]
    (is (= [:b] (:path d)))
    (is (= :map-key-missing-left (:kind d)))
    (is (= :missing (:left d)))
    (is (= 2 (:right d))))
  (let [d (canonical/first-divergence {:a 1 :b 2} {:a 1})]
    (is (= :map-key-missing-right (:kind d)))))

(deftest divergence-reports-vector-length
  (let [d (canonical/first-divergence [1 2] [1 2 3])]
    (is (= :vector-length (:kind d)))
    (is (= 2 (:left d)))
    (is (= 3 (:right d)))))

;; ── compare-values ─────────────────────────────────────────────────────────

(deftest compare-values-reports-hashes
  (let [r (canonical/compare-values {:a 1} {:a 1})]
    (is (:equivalent? r))
    (is (= (:left-hash r) (:right-hash r)))
    (is (= 64 (count (:left-hash r))))
    (is (nil? (:divergence r)))))

(deftest compare-values-divergence-non-nil-when-different
  (let [r (canonical/compare-values {:a 1} {:a 2})]
    (is (not (:equivalent? r)))
    (is (some? (:divergence r)))
    (is (not= (:left-hash r) (:right-hash r)))))

(deftest content-hash-deterministic
  (is (= (canonical/content-hash {:a [1 2] :b "x"})
         (canonical/content-hash {:b "x" :a [1 2]}))))

;; ── compare-files ──────────────────────────────────────────────────────────

(deftest compare-files-equivalent-across-reordered-json
  (let [a (tmp-file "{\"a\": 1, \"b\": [1, 2, 3]}" ".json")
        b (tmp-file "{\"b\": [1, 2, 3], \"a\": 1}" ".json")
        r (canonical/compare-files a b)]
    (is (:equivalent? r))
    (is (= a (:left-file r)))
    (is (= b (:right-file r)))))

(deftest compare-files-detects-value-change
  (let [a (tmp-file "{\"a\": 1}" ".json")
        b (tmp-file "{\"a\": 2}" ".json")]
    (is (not (:equivalent? (canonical/compare-files a b))))))

(deftest compare-files-equivalent-across-edn-json
  (let [a (tmp-file "{:a 1 :b [1 2 3]}" ".edn")
        b (tmp-file "{\"a\": 1, \"b\": [1, 2, 3]}" ".json")]
    (is (:equivalent? (canonical/compare-files a b)))))

(deftest compare-files-missing-file-throws
  (let [a (tmp-file "{:a 1}" ".edn")]
    (is (thrown? Exception (canonical/compare-files a "/nonexistent/artifact.edn")))))

(deftest compare-files-format-inference
  (let [json-file (tmp-file "{\"a\": 1}" ".json")
        edn-file  (tmp-file "{:a 1}" ".edn")]
    (is (= {:a 1} (canonical/parse-file json-file)))
    (is (= {:a 1} (canonical/parse-file edn-file)))
    (is (= {:a 1} (canonical/parse-file json-file {:format :json})))
    (let [unknown (tmp-file "{:a 1}" ".dat")]
      (is (thrown? Exception (canonical/parse-file unknown))))))
