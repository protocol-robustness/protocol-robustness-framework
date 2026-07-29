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

(deftest sha256-ref-short-hex-creates-invalid-ref
  (testing "63 chars is not a valid sha256 ref"
    (is (false? (hr/valid-sha256-ref? (hr/sha256-ref (subs valid-hex 0 63)))))))

(deftest sha256-ref-empty-string
  (is (= "sha256:" (hr/sha256-ref ""))))

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

(deftest sha256-ref-uppercase-hex
  (testing "sha256-ref passes through a prefixed string unchanged,
            so uppercase within a prefixed string is not rejected"
    (let [upper (str "sha256:" (clojure.string/upper-case (apply str (repeat 64 "a"))))]
      (is (= upper (hr/sha256-ref upper))))))

(deftest sha256-ref-short-hex
  (testing "sha256-ref on a short hex produces an invalid ref"
    (let [ref (hr/sha256-ref "abc")]
      (is (false? (hr/valid-sha256-ref? ref))))))

(deftest sha256-ref-nil
  (testing "sha256-ref on nil produces a string but not a valid ref"
    (let [ref (hr/sha256-ref nil)]
      (is (string? ref))
      (is (false? (hr/valid-sha256-ref? ref))))))

(deftest sha256-ref-non-string
  (testing "sha256-ref on a keyword produces a string but not a valid ref"
    (let [ref (hr/sha256-ref :abc)]
      (is (string? ref))
      (is (false? (hr/valid-sha256-ref? ref))))))

(deftest sha256-ref-round-trip
  (is (= valid-ref (-> valid-ref hr/parse-sha256-ref hr/sha256-ref))))

(deftest sha256-ref-file-round-trip
  (let [tmp (doto (java.io.File/createTempFile "prf-ref-roundtrip" ".txt")
              (.deleteOnExit))]
    (spit tmp "round trip test")
    (let [ref (hr/sha256-ref-file (.getPath tmp))
          parsed (hr/parse-sha256-ref ref)]
      (is (string? parsed))
      (is (= 64 (count parsed))))))
