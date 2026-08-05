(ns resolver-sim.conformance.validation-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [resolver-sim.conformance.validation :as validation]))

(def ^:private always-pass
  {:validator/id :test.always-pass
   :validator/kind :schema
   :validator/input-contract :trace-fixture.v2
   :validator/version 1
   :validator/implementation-root "sha256:pass"
   :validator/run (fn [subject] (validation/pass-result
                                 {:validator/id :test.always-pass
                                  :validator/kind :schema
                                  :validator/version 1
                                  :validator/implementation-root "sha256:pass"}
                                 subject))})

(def ^:private always-reject
  {:validator/id :test.always-reject
   :validator/kind :semantic
   :validator/input-contract :trace-fixture.v2
   :validator/version 2
   :validator/implementation-root "sha256:reject"
   :validator/run (fn [subject] (validation/reject-result
                                 {:validator/id :test.always-reject
                                  :validator/kind :semantic
                                  :validator/version 2
                                  :validator/implementation-root "sha256:reject"}
                                 subject
                                 [(validation/validation-issue :test.rejected)]))})

;; Tests do not run in definition order, so register the fixture validators in
;; a :once fixture (registration is idempotent).
(use-fixtures :once
  (fn [f]
    (validation/register-validator! always-pass)
    (validation/register-validator! always-reject)
    (f)))

(deftest resolve-validator-closed-registry
  (testing "validators are registered and resolvable by id"
    (is (= :test.always-pass (:validator/id (validation/resolve-validator :test.always-pass))))
    (is (= :test.always-reject (:validator/id (validation/resolve-validator :test.always-reject))))
    (is (nil? (validation/resolve-validator :test.never-registered)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (validation/require-validator-resolvable! [:test.never-registered])))))

(deftest pass-result-shape
  (let [r (validation/pass-result always-pass {:a 1})]
    (is (= :pass (:validation/status r)))
    (is (= :schema (:validation/kind r)))
    (is (= 1 (:validation/version r)))
    (is (empty? (:validation/issues r)))
    (is (= "sha256:pass" (:validation/implementation-root r)))
    (is (string? (:validation/subject-root r)))
    (is (validation/valid? r))))

(deftest reject-result-shape
  (let [r (validation/reject-result always-reject {:a 1} [(validation/validation-issue :x)])]
    (is (= :rejected (:validation/status r)))
    (is (= 2 (:validation/version r)))
    (is (= 1 (count (:validation/issues r))))
    (is (not (validation/valid? r)))))

(deftest validate-layers-rules
  (testing "duplicate validator ids are rejected"
    (let [{:keys [valid? issues]}
          (validation/validate-layers [:test.always-pass :test.always-pass] [:schema] {})]
      (is (not valid?))
      (is (some #(= :duplicate-validator-id (:issue/code %)) issues))))
  (testing "two distinct validators of the same kind are legitimate"
    (let [r (validation/validate-layers
             [:test.always-pass :test.always-reject] [:schema :semantic] {})]
      (is (= 2 (count (:results r))))))
  (testing "required layers cannot be skipped"
    (let [{:keys [valid? issues]}
          (validation/validate-layers [:test.always-pass] [:schema :semantic] {})]
      (is (not valid?))
      (is (some #(= :required-layer-skipped (:issue/code %)) issues))))
  (testing "unresolvable validators fail closed"
    (let [{:keys [valid? issues]}
          (validation/validate-layers [:test.missing] [:schema] {})]
      (is (not valid?))
      (is (some #(= :validator-not-resolved (:issue/code %)) issues))))
  (testing "rejected layer fails the run"
    (let [{:keys [valid? results]}
          (validation/validate-layers [:test.always-pass :test.always-reject] [:schema :semantic] {})]
      (is (not valid?))
      (is (= 2 (count results)))
      (is (= :rejected (get-in results [1 :validation/status])))))
  (testing "all-pass run"
    (let [{:keys [valid? results]}
          (validation/validate-layers [:test.always-pass] [:schema] {})]
      (is valid?)
      (is (= 1 (count results))))))
