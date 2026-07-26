(ns resolver-sim.research.changelog-challenge-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [resolver-sim.research.changelog-challenge :as cc]))

(def sample-target
  {:changelog-entry-hash "sha256:abc123def456"
   :file "CHANGELOG.md"
   :start-line 21
   :end-line 22
   :release-or-version "Unreleased"})

(defn valid-challenge-params []
  {:challenge/target sample-target
   :challenge/category :scope-omitted
   :challenge/assertion "Entry claims five component states but six exist"
   :challenge/evidence [{:artifact/kind :source-code
                         :artifact/path "src/resolver_sim/benchmark/content_registry_entry.clj"
                         :artifact/line-range "42-50"}]
   :challenge/proposed-resolution :qualify-entry
   :challenge/proposed-wording "six component states including :provisional"
   :challenge/proposed-by "researcher-b"})

(deftest valid-challenge-builds
  (let [challenge (cc/build-challenge (valid-challenge-params))]
    (is (= "changelog-challenge.v1" (:schema-version challenge)))
    (is (some? (:challenge/id challenge)))
    (is (some? (:challenge/hash challenge)))
    (is (= :open (:challenge/status challenge)))
    (is (= :scope-omitted (:challenge/category challenge)))
    (is (= "researcher-b" (:challenge/proposed-by challenge)))))

(deftest valid-challenge-validates
  (let [challenge (cc/build-challenge (valid-challenge-params))
        result (cc/validate-challenge challenge)]
    (is (:valid? result))))

(deftest original-entry-unchanged
  (let [changelog-path (.getPath (io/file "CHANGELOG.md"))
        before (slurp changelog-path)
        _ (cc/build-challenge (valid-challenge-params))
        after (slurp changelog-path)]
    (is (= before after) "building a challenge must not modify CHANGELOG.md")))

(deftest challenge-hash-changes-on-assertion
  (let [a (cc/build-challenge (valid-challenge-params))
        params (valid-challenge-params)
        params (assoc params :challenge/assertion "different assertion")
        b (cc/build-challenge params)]
    (is (not= (:challenge/hash a) (:challenge/hash b)))))

(deftest unknown-category-rejected
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid challenge category"
                        (cc/build-challenge (assoc (valid-challenge-params)
                                                   :challenge/category :invalid-category)))))

(deftest invalid-target-hash-rejected-by-builder
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires :changelog-entry-hash"
                        (cc/build-challenge (assoc (valid-challenge-params)
                                                   :challenge/target {:file "CHANGELOG.md"})))))

(deftest missing-assertion-rejected
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires a non-blank"
                        (cc/build-challenge (dissoc (valid-challenge-params) :challenge/assertion)))))

(deftest multiple-challenges-coexist
  (let [a (cc/build-challenge (valid-challenge-params))
        b (cc/build-challenge (valid-challenge-params))]
    (is (not= (:challenge/id a) (:challenge/id b))
        "each challenge must have a unique id (content-addressed)")
    (is (= (:challenge/target a) (:challenge/target b))
        "both challenges target the same entry")))

(deftest supersession-without-deletion
  (let [original (cc/build-challenge (valid-challenge-params))
        original-hash (:challenge/hash original)
        params (valid-challenge-params)
        params (assoc params :challenge/supersedes original-hash)
        successor (cc/build-challenge params)]
    (is (= original-hash (:challenge/supersedes successor)))
    (is (some? (:challenge/hash successor)))
    (is (not= original-hash (:challenge/hash successor))
        "successor hash must differ from original")))

(deftest validate-detects-missing-target
  (let [challenge (cc/build-challenge (valid-challenge-params))
        stripped (dissoc challenge :challenge/target)
        result (cc/validate-challenge stripped)]
    (is (not (:valid? result)))
    (is (some #(re-find #"target" %) (:errors result)))))

(deftest normalize-changelog-ref-produces-stable-hash
  (let [a (cc/normalise-changelog-ref "CHANGELOG.md" 21 22)
        b (cc/normalise-changelog-ref "CHANGELOG.md" 21 22)]
    (is (= a b) "same file + lines → same target hash")
    (is (re-matches #"sha256:[0-9a-f]{64}" (:changelog-entry-hash a))
        "target hash should be a valid sha256")))
