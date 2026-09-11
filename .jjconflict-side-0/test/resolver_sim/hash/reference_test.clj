(ns resolver-sim.hash.reference-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.hash.reference :as hr]
            [clojure.java.io :as io]))

(def ^:private valid-hex "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
(def ^:private valid-ref (str "sha256:" valid-hex))

;; ── sha256-ref (construction) ─────────────────────────────────────────────────

(deftest sha256-ref-from-hex
  (is (= valid-ref (hr/sha256-ref valid-hex)))
  (is (= "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
         (hr/sha256-ref "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))))

(deftest sha256-ref-already-prefixed
  (is (= valid-ref (hr/sha256-ref valid-ref))))

(deftest sha256-ref-fails-closed-on-invalid-input
  (testing "the constructor owns format validation and throws on non-canonical input"
    (is (thrown? clojure.lang.ExceptionInfo (hr/sha256-ref nil)))
    (is (thrown? clojure.lang.ExceptionInfo (hr/sha256-ref :abc)))
    (is (thrown? clojure.lang.ExceptionInfo (hr/sha256-ref 12345)))
    (is (thrown? clojure.lang.ExceptionInfo (hr/sha256-ref "")))
    (is (thrown? clojure.lang.ExceptionInfo (hr/sha256-ref (subs valid-hex 0 63))))
    (is (thrown? clojure.lang.ExceptionInfo (hr/sha256-ref (str valid-hex "a"))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (hr/sha256-ref (str "sha256:" (clojure.string/upper-case valid-hex)))))))

;; ── parse-sha256-ref ──────────────────────────────────────────────────────────

(deftest parse-valid-ref-returns-hex
  (is (= valid-hex (hr/parse-sha256-ref valid-ref))))

(deftest parse-rejects-ref-with-63-char-hex
  (is (nil? (hr/parse-sha256-ref (str "sha256:" (subs valid-hex 0 63))))))

(deftest parse-rejects-ref-with-65-char-hex
  (is (nil? (hr/parse-sha256-ref (str "sha256:" valid-hex "a")))))

(deftest parse-rejects-non-hex-characters
  (is (nil? (hr/parse-sha256-ref "sha256:zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"))))

(deftest parse-rejects-missing-prefix
  (is (nil? (hr/parse-sha256-ref valid-hex))))

(deftest parse-rejects-wrong-prefix
  (is (nil? (hr/parse-sha256-ref (str "sha257:" valid-hex)))))

(deftest parse-rejects-uppercase
  (is (nil? (hr/parse-sha256-ref (str "sha256:" (clojure.string/upper-case valid-hex))))))

(deftest parse-rejects-nil
  (is (nil? (hr/parse-sha256-ref nil))))

(deftest parse-rejects-non-string
  (is (nil? (hr/parse-sha256-ref 12345)))
  (is (nil? (hr/parse-sha256-ref [])))
  (is (nil? (hr/parse-sha256-ref {:a 1}))))

;; ── valid-sha256-ref? ─────────────────────────────────────────────────────────

(deftest valid-ref-returns-true
  (is (true? (hr/valid-sha256-ref? valid-ref))))

(deftest valid-ref-rejects-short
  (is (false? (hr/valid-sha256-ref? (str "sha256:" (subs valid-hex 0 63))))))

(deftest valid-ref-rejects-long
  (is (false? (hr/valid-sha256-ref? (str "sha256:" valid-hex "a")))))

(deftest valid-ref-rejects-bare-hex
  (is (false? (hr/valid-sha256-ref? valid-hex))))

(deftest valid-ref-rejects-nil
  (is (false? (hr/valid-sha256-ref? nil))))

(deftest valid-ref-rejects-non-string
  (is (false? (hr/valid-sha256-ref? :sha256:abc))))

(deftest valid-ref-rejects-uppercase
  (is (false? (hr/valid-sha256-ref? (str "sha256:" (clojure.string/upper-case valid-hex))))))

;; ── sha256-ref-file ───────────────────────────────────────────────────────────

(deftest file-ref-computes-correctly
  (let [tmp (doto (java.io.File/createTempFile "prf-ref-test" ".txt")
              (.deleteOnExit))]
    (spit tmp "hello world")
    (let [ref (hr/sha256-ref-file (.getPath tmp))]
      (is (string? ref))
      (is (true? (hr/valid-sha256-ref? ref)))
      (is (= (hr/sha256-ref (hr/parse-sha256-ref ref)) ref)))))

(deftest file-ref-missing-returns-nil
  (is (nil? (hr/sha256-ref-file hr/nonexistent-file-path))))

(deftest file-ref-directory-returns-nil
  (let [tmp (doto (io/file (System/getProperty "java.io.tmpdir") "prf-ref-test-dir")
              (.mkdirs)
              (.deleteOnExit))]
    (is (nil? (hr/sha256-ref-file (.getPath tmp))))))

;; ── Round-trip ────────────────────────────────────────────────────────────────

(deftest sha256-ref-representation-preservation
  (testing "sha256-ref produces the same output as raw string concatenation
            for a valid 64-char lowercase hex digest"
    (let [digest (apply str (repeat 64 "a"))]
      (is (= (str "sha256:" digest) (hr/sha256-ref digest))))))

(deftest sha256-ref-round-trip
  (is (= valid-ref (-> valid-ref hr/parse-sha256-ref hr/sha256-ref))))

;; ── Constructor/parser algebra ──────────────────────────────────────────────
;; parse(sha256-ref(digest)) = digest and sha256-ref(parse(ref)) = ref for every
;; admitted canonical reference.  Lowercase and 64-hex length are canonicality
;; properties of the format, not incidental regex details.

(deftest reference-algebra-construct-parse-identity
  (testing "parse(sha256-ref(digest)) = digest for any 64-char lowercase hex digest"
    (doseq [digest [valid-hex
                    "0000000000000000000000000000000000000000000000000000000000000000"
                    "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
                    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"]]
      (is (= digest (hr/parse-sha256-ref (hr/sha256-ref digest)))))))

(deftest reference-algebra-parse-construct-identity
  (testing "sha256-ref(parse(ref)) = ref for every admitted canonical reference"
    (is (= valid-ref (-> valid-ref hr/parse-sha256-ref hr/sha256-ref)))
    (doseq [r [(str "sha256:" valid-hex)
               (str "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")]]
      (is (= r (hr/sha256-ref (hr/parse-sha256-ref r)))))))

(deftest reference-algebra-rejects-non-canonical-forms
  (testing "uppercase prefix, uppercase hex, wrong lengths, empty, and trailing
            junk are all rejected by both parse and construct"
    (let [bad-forms [(str "SHA256:" valid-hex)
                     (str "sha256:" (clojure.string/upper-case valid-hex))
                     (str "sha256:" (subs valid-hex 0 63))
                     (str "sha256:" valid-hex "a")
                     "sha256:"
                     (str "sha256:" valid-hex "trailing")]]
      (doseq [bad bad-forms]
        (is (nil? (hr/parse-sha256-ref bad)) (str "parse rejects " bad))
        (is (false? (hr/valid-sha256-ref? bad)) (str "valid rejects " bad))
        (is (thrown? clojure.lang.ExceptionInfo (hr/sha256-ref bad))
            (str "construct throws on " bad))))))

(deftest sha256-ref-file-round-trip
  (let [tmp (doto (java.io.File/createTempFile "prf-ref-roundtrip" ".txt")
              (.deleteOnExit))]
    (spit tmp "round trip test")
    (let [ref (hr/sha256-ref-file (.getPath tmp))
          parsed (hr/parse-sha256-ref ref)]
      (is (string? parsed))
      (is (= 64 (count parsed))))))
