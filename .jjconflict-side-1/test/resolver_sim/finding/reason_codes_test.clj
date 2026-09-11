(ns resolver-sim.finding.reason-codes-test
  "Conformance tests for the finding reason registry."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.finding.reason-codes :as rc]))

(deftest registry-conformance
  (let [report (rc/conformance-report)]
    (testing "every entry conforms to :finding-reason.v1"
      (is (empty? (:invalid-codes report))
          (str "invalid entries: " (pr-str (:invalid-codes report)))))
    (testing "registry is non-trivial"
      (is (pos? (:code-count report))))
    (testing "classes and stabilities stay inside closed enums"
      (is (every? #(contains? rc/classes %) (:classes-used report)))
      (is (every? #(contains? rc/stabilities %) (:stabilities-used report))))))

(deftest codes-are-unique-and-describable
  (let [codes (rc/all-codes)]
    (is (= (count codes) (count (distinct codes))))
    (doseq [c codes]
      (is (= c (:reason/code (rc/describe c)))
          "entry carries its own code"))))

(deftest known-boundary-codes-are-present
  ;; Codes emitted by real subsystems must remain registered — removing one
  ;; is a breaking protocol change.
  (doseq [c [:receipt-authority-not-configured
             :parent-not-current-head
             :scope-hash-mismatch
             :invalid-parameter-attribution
             :held-custody/hash-integrity
             :package/completion-gate-failed
             :value-at-risk/validator-failed]]
    (testing (str c)
      (is (rc/registered? c)))))

(deftest finding-constructor
  (testing "registered code produces canonical shape"
    (is (= {:finding/reason-code :receipt-authority-not-configured
            :finding/detail "no trusted authority configured for family"
            :finding/caused-by []}
           (rc/finding :receipt-authority-not-configured
                       "no trusted authority configured for family"))))
  (testing "caused-by nests"
    (let [inner (rc/finding :package/missing-required-artifact "value-at-risk.json" [])
          outer (rc/finding :package/completion-gate-failed "gate failed" [inner])]
      (is (= [inner] (:finding/caused-by outer)))))
  (testing "unregistered code throws with guidance"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unregistered"
                          (rc/finding :made-up/reason "x" []))))
  (testing "non-string detail rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"detail"
                          (rc/finding :scope-mismatch 42 []))))
  (testing "provisional escape hatch tags unregistered codes"
    (is (true? (:finding/unregistered (rc/provisional-finding :made-up/x "y" []))))))

(deftest classification-helpers
  (is (= :rejection (rc/classify :parent-not-current-head)))
  (is (= :unavailable (rc/classify :value-at-risk/source-not-registered)))
  (is (nil? (rc/classify :not-a-real/code))))

(deftest boundary-rule-documentation-is-enforced-by-shape
  ;; The registry holds REASONS, not exceptions: every entry's class must be
  ;; interpretable by a protocol consumer without reading prose.
  (doseq [[_ entry] @#'rc/registry]
    (is (contains? #{:rejection :unavailable :invalid-input :internal}
                   (:reason/class entry)))))
