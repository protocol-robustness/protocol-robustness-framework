(ns resolver-sim.publish.manifest-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [resolver-sim.publish.manifest :as m]))

(defn tmpdir
  "A fresh empty temp directory."
  []
  (let [d (java.io.File/createTempFile "prf-pub-stage" "")]
    (.delete d)
    (.mkdirs d)
    d))

(defn with-stage [f]
  (let [dir (tmpdir)]
    (try (f dir)
         (finally (when (.exists dir) (io/delete-file dir :recursively))))))

(defn write-file [dir rel content]
  (let [f (io/file dir rel)]
    (.mkdirs (.getParentFile f))
    (spit f content)
    f))

(defn entry-for [dir rel]
  {:path rel :sha256 (m/file-sha256 (io/file dir rel))})

(deftest verify-set-accepts-complete-consistent-set
  (with-stage (fn [dir]
    (write-file dir "summary.json" "{}")
    (write-file dir "sub/result.json" "{\"a\":1}")
    (let [entries [(entry-for dir "summary.json")
                   (entry-for dir "sub/result.json")]
          res (m/verify-set {:root dir :entries entries
                             :required ["summary.json" "sub/result.json"]})]
      (is (true? (:ok res)))
      (is (= 2 (get-in res [:checks :entry-count])))
      (is (= #{"summary.json" "sub/result.json"} (set (get-in res [:checks :paths]))))))))

(deftest verify-set-rejects-required-not-declared
  (with-stage (fn [dir]
    (write-file dir "summary.json" "{}")
    (let [entries [(entry-for dir "summary.json")]
          res (m/verify-set {:root dir :entries entries
                             :required ["summary.json" "missing.json"]})]
      (is (false? (:ok res)))
      (is (= :undeclared-required (:reason res)))
      (is (= ["missing.json"] (:paths (:detail res))))))))

(deftest verify-set-rejects-missing-file
  (with-stage (fn [dir]
    (write-file dir "summary.json" "{}")
    (let [entries [(entry-for dir "summary.json")
                   {:path "ghost.json" :sha256 (apply str (repeat 64 "a"))}]
          res (m/verify-set {:root dir :entries entries
                             :required ["summary.json" "ghost.json"]})]
      (is (false? (:ok res)))
      (is (= :incomplete-or-modified (:reason res)))
      (is (= "ghost.json" (:path (first (:problems (:detail res))))))
      (is (= :missing-file (:issue (first (:problems (:detail res))))))))))

(deftest verify-set-rejects-hash-mismatch
  (with-stage (fn [dir]
    (write-file dir "summary.json" "original")
    (let [entries [(entry-for dir "summary.json")]]
      (write-file dir "summary.json" "tampered")
      (let [res (m/verify-set {:root dir :entries entries
                               :required ["summary.json"]})]
        (is (false? (:ok res)))
        (is (= :incomplete-or-modified (:reason res)))
        (let [p (first (:problems (:detail res)))]
          (is (= "summary.json" (:path p)))
          (is (= :hash-mismatch (:issue p)))
          (is (= (:expected p) (:sha256 (first entries))))
          (is (= (m/file-sha256 (io/file dir "summary.json")) (:actual p)))))))))

(deftest manifest-commit-is-deterministic-and-order-independent
  (let [a [{:path "b.json" :sha256 "ab"}
           {:path "a.json" :sha256 "cd"}]
        b [{:path "a.json" :sha256 "cd"}
           {:path "b.json" :sha256 "ab"}]]
    (is (= (m/manifest-commit "run-1" a) (m/manifest-commit "run-1" b)))
    (is (not= (m/manifest-commit "run-1" a) (m/manifest-commit "run-2" a)))))

(deftest file-sha256-stable
  (with-stage (fn [dir]
    (write-file dir "x.bin" "hello")
    (is (= (m/file-sha256 (io/file dir "x.bin"))
           "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824")))))
