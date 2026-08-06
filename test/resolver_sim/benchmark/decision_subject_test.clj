(ns resolver-sim.benchmark.decision-subject-test
  "Tests for decision-subject.v1 — the reusable content-addressed subject
   artifact (content / parameters / effects / branch / intended state
   transition). Three-member task: Decision-subject contract."
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.benchmark.decision-subject :as ds]
            [resolver-sim.hash.canonical :as hc]))

(defn- h
  "Valid sha256 ref from a hex-only pattern."
  [pattern]
  (str "sha256:" (apply str (take 64 (cycle pattern)))))

(def ^:private content-root (h "c3"))
(def ^:private params-root  (h "a1"))
(def ^:private effects-root (h "e7"))
(def ^:private branch-hash  (h "b5"))
(def ^:private transition-root (h "0f"))

(defn- valid-subject []
  (ds/build-decision-subject
   {:subject/id :decision/test-001
    :subject/content-root content-root
    :subject/parameters-root params-root
    :subject/effects-root effects-root
    :subject/branch-descriptor-hash branch-hash
    :subject/transition-root transition-root}))

(deftest builds-and-commits-all-subject-fields
  (let [s (valid-subject)]
    (is (= "decision-subject.v1" (:schema-version s)))
    (is (some? (:subject/hash s)))
    (is (= content-root (:subject/content-root s)))
    (is (= params-root (:subject/parameters-root s)))
    (is (= effects-root (:subject/effects-root s)))
    (is (= branch-hash (:subject/branch-descriptor-hash s)))
    (is (= transition-root (:subject/transition-root s)))
    (is (ds/decision-subject-valid? s))
    (is (re-find #"^sha256:" (:subject/hash s)))))

(deftest hash-is-domain-separated-and-deterministic
  (let [a (valid-subject)
        b (valid-subject)]
    (is (= (:subject/hash a) (:subject/hash b))
        "identical subjects share one root")
    (is (not= (:subject/hash a)
              (str "sha256:" (hc/domain-hash :researcher-decision-v2
                                             {:content-root content-root})))
        "subject hashing is domain-separated from decisions")))

(deftest any-committed-field-change-changes-the-root
  (let [base (valid-subject)]
    (doseq [[label mutated]
            {"content" (assoc base :subject/content-root (h "d4"))
             "parameters" (assoc base :subject/parameters-root (h "d5"))
             "effects" (assoc base :subject/effects-root (h "d6"))
             "branch" (assoc base :subject/branch-descriptor-hash (h "d7"))
             "transition" (assoc base :subject/transition-root (h "d8"))}]
      (is (not= (:subject/hash base)
                (:subject/hash (ds/build-decision-subject
                                (dissoc mutated :subject/hash))))
          (str "changing " label " must change the subject root")))))

(deftest rejects-missing-or-invalid-roots
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"build failed"
                        (ds/build-decision-subject
                         {:subject/content-root content-root
                          :subject/parameters-root params-root
                          :subject/effects-root effects-root
                          :subject/branch-descriptor-hash branch-hash})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"build failed"
                        (ds/build-decision-subject
                         {:subject/content-root "not-a-hash"
                          :subject/parameters-root params-root
                          :subject/effects-root effects-root
                          :subject/branch-descriptor-hash branch-hash
                          :subject/transition-root transition-root}))))

(deftest rejects-declared-hash-mismatch
  (let [s (valid-subject)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"hash mismatch"
                          (ds/build-decision-subject
                           (assoc (dissoc s :subject/hash)
                                  :subject/hash (h "99")))))))

(deftest verifier-recomputes-the-claimed-root
  (let [s (valid-subject)]
    (is (true? (:valid? (ds/verify-decision-subject-root s (:subject/hash s)))))
    (is (false? (:valid? (ds/verify-decision-subject-root s (h "99")))))
    (is (false? (:valid? (ds/verify-decision-subject-root s nil))))
    (is (false? (:valid? (ds/verify-decision-subject-root (dissoc s :subject/transition-root)
                                                          (:subject/hash s)))))))

(deftest corrupt-subject-fails-validation
  (let [s (valid-subject)
        tampered (assoc s :subject/effects-root (h "ff"))]
    (is (not (ds/decision-subject-valid? tampered))
        "a tampered subject is invalid and its hash will not recompute")))

(deftest summary-lists-commitments
  (let [s (ds/subject-commitment-summary (valid-subject))]
    (is (= #{:content :parameters :effects :branch :intended-state-transition}
           (:commits s)))
    (is (= (:subject/hash (valid-subject)) (:subject/root s)))))
