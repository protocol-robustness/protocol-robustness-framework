(ns resolver-sim.resubmission.attempt-subject-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.resubmission.attempt-subject :as subject]
            [resolver-sim.resubmission.issuance :as issuance]
            [resolver-sim.resubmission.receipt :as receipt]))

(def e "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
(def b "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
(def app-root-a "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc")
(def app-root-b "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd")

(def t-a {:attempt-target/type :use-case-application
          :attempt-target/root app-root-a})
(def t-b {:attempt-target/type :use-case-application
          :attempt-target/root app-root-b})

(deftest subject-is-closed-rooted-and-sensitive
  (testing "valid subject has matching subject-root"
    (let [s (subject/build e b t-a)]
      (is (subject/valid? s))
      (is (= (:attempt/subject-root s) (subject/root s)))))

  (testing "extra keys are rejected (closed shape)"
    (is (not (subject/valid? (assoc (subject/build e b t-a) :reservation/id "r")))))

  (testing "different evaluation roots produce different subject roots"
    (is (not= (:attempt/subject-root (subject/build e b t-a))
              (:attempt/subject-root (subject/build "sha256:9999999999999999999999999999999999999999999999999999999999999999"
                                                    b t-a)))))

  (testing "different submitted-bundle roots produce different subject roots"
    (is (not= (:attempt/subject-root (subject/build e b t-a))
              (:attempt/subject-root (subject/build e
                                                    "sha256:9999999999999999999999999999999999999999999999999999999999999999"
                                                    t-a)))))

  (testing "different application roots produce different subject roots"
    (is (not= (:attempt/subject-root (subject/build e b t-a))
              (:attempt/subject-root (subject/build e b t-b))))))

(deftest target-validation
  (testing "valid target shape"
    (is (subject/valid-target? t-a))
    (is (subject/valid-target? t-b)))

  (testing "unknown target type is rejected"
    (is (not (subject/valid-target? {:attempt-target/type :unknown-type
                                     :attempt-target/root app-root-a}))))

  (testing "non-sha256 root is rejected"
    (is (not (subject/valid-target? {:attempt-target/type :use-case-application
                                     :attempt-target/root "not-a-root"}))))

  (testing "missing root key is rejected"
    (is (not (subject/valid-target? {:attempt-target/type :use-case-application}))))

  (testing "extra root key is rejected"
    (is (not (subject/valid-target? (assoc t-a :attempt-target/extra "rogue"))))))

(deftest subject-rejects-invalid-inputs
  (testing "non-sha256 evaluation root is rejected"
    (is (thrown? Exception (subject/build "bad" b t-a))))

  (testing "non-sha256 submitted-bundle root is rejected"
    (is (thrown? Exception (subject/build e "bad" t-a))))

  (testing "invalid target is rejected"
    (is (thrown? Exception (subject/build e b {:attempt-target/type :bad
                                               :attempt-target/root "not-a-root"}))))

  (testing "nil roots are rejected"
    (is (not (subject/valid-target? {:attempt-target/type :use-case-application
                                     :attempt-target/root nil})))))

(deftest receipt-subject-binding-is-explicit
  (let [s (subject/build e b t-a)
        receipt-v1 {:attempt-receipt/attempt-subject-root (:attempt/subject-root s)}
        receipt-v2 {:attempt-receipt/attempt-subject-root (:attempt/subject-root s)}]
    (is (= (:attempt/subject-root s)
           (:attempt-receipt/attempt-subject-root
            (issuance/bind-attempt-subject {} s))))
    (is (issuance/receipt-binds-attempt-subject? receipt-v1 s))
    (is (receipt/receipt-binds-attempt-subject? receipt-v1 s))
    (is (receipt/receipt-binds-attempt-subject? receipt-v2 s))
    (testing "mismatched subject root is rejected"
      (is (not (receipt/receipt-binds-attempt-subject?
                (assoc receipt-v1 :attempt-receipt/attempt-subject-root "sha256:bad") s))))))
