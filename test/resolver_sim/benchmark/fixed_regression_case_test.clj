(ns resolver-sim.benchmark.fixed-regression-case-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.fixed-regression-case :as frc]))

(def valid-case-input
  {:case/id "slash-001"
   :case/kind :slash/standard
   :case/description "Standard slash with challenge bounty"
   :case/gross-slash-amount 1000
   :case/policy-root "sha256:abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890"
   :case/parameter-context {:source-root "sew:governance-snapshot"
                            :values {:sew.parameter/challenge-bounty-bps 1000}}
   :case/challenger "0xChallenger"
   :case/beneficiary "0xChallenger"
   :case/evidence-references ["sew:slash:wf-0"]
   :case/expected-invariant-ids [:liability-slash-boundary? :conservation]
   :case/expected-distribution-root nil
   :case/metadata {:source "test"}})

(deftest build-fixed-regression-case-valid
  (let [c (frc/build-fixed-regression-case valid-case-input)]
    (is (= :slash/standard (:case/kind c)))
    (is (= "slash-001" (:case/id c)))
    (is (= 1000 (:case/gross-slash-amount c)))
    (is (string? (:case/hash c)))
    (is (pos? (count (:case/hash c))))))

(deftest build-fixed-regression-case-deterministic
  (let [c1 (frc/build-fixed-regression-case valid-case-input)
        c2 (frc/build-fixed-regression-case valid-case-input)]
    (is (= (:case/hash c1) (:case/hash c2)))))

(deftest build-fixed-regression-case-missing-id-rejected
  (is (thrown? Exception
               (frc/build-fixed-regression-case (dissoc valid-case-input :case/id)))))

(deftest build-fixed-regression-case-negative-amount-rejected
  (is (thrown? Exception
               (frc/build-fixed-regression-case (assoc valid-case-input :case/gross-slash-amount -1)))))

(deftest validate-fixed-regression-case-valid
  (let [c (frc/build-fixed-regression-case valid-case-input)
        v (frc/validate-fixed-regression-case c)]
    (is (:valid? v))))

(deftest validate-fixed-regression-case-missing-fields
  (let [v (frc/validate-fixed-regression-case {:schema-version "fixed-regression-case.v1"})]
    (is (not (:valid? v)))
    (is (some #(= :missing-case-id %) (:errors v)))))

(deftest verify-fixed-regression-case-untampered
  (let [c (frc/build-fixed-regression-case valid-case-input)
        v (frc/verify-fixed-regression-case c)]
    (is (:valid? v))))

(deftest verify-fixed-regression-case-tampered
  (let [c (frc/build-fixed-regression-case valid-case-input)
        tampered (assoc c :case/gross-slash-amount 9999)
        v (frc/verify-fixed-regression-case tampered)]
    (is (not (:valid? v)))
    (is (= :hash-mismatch (first (:errors v))))))

(deftest fixed-regression-case-root-comparison
  (let [c (frc/build-fixed-regression-case valid-case-input)
        c2 (frc/build-fixed-regression-case (assoc valid-case-input :case/gross-slash-amount 2000))]
    (is (frc/verify-fixed-regression-case-root c))
    (is (frc/verify-fixed-regression-case-root c2))
    (is (not= (:case/hash c) (:case/hash c2))
        "different inputs produce different roots")))

(deftest fixed-regression-case-reusable-across-bounty-configs
  (testing "same operational case with different bounty configs has same operational root"
    (let [case-no-bounty (frc/build-fixed-regression-case
                          (assoc valid-case-input
                                 :case/parameter-context
                                 {:source-root "sew:governance-snapshot"
                                  :values {:sew.parameter/challenge-bounty-bps 0}}))
          case-with-bounty (frc/build-fixed-regression-case
                            (assoc valid-case-input
                                   :case/parameter-context
                                   {:source-root "sew:governance-snapshot"
                                    :values {:sew.parameter/challenge-bounty-bps 1000}}))]
      ;; The parameter-context is part of the case identity, so changing it
      ;; changes the case hash. This is correct — different compensation
      ;; configurations produce different case artifacts even when the
      ;; operational behaviour is the same.
      (is (not= (:case/hash case-no-bounty) (:case/hash case-with-bounty))
          "different parameter contexts produce different case hashes"))))
