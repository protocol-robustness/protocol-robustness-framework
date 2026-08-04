(ns resolver-sim.conformance.registry-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [resolver-sim.conformance.registry :as registry]))

(defn- impl [id & [status]]
  {:implementation/id id
   :implementation/kind :validator
   :implementation/version 1
   :implementation/root (str "sha256:" (name id))
   :implementation/status (or status :active)})

;; Registry state persists and tests do not run in definition order, so a
;; :once fixture registers the full fixture set up front.
(use-fixtures :once
  (fn [f]
    (registry/register! (impl :test.validator-a))
    (registry/register! (impl :test.experimental :experimental))
    (registry/register! (impl :test.deprecated :deprecated))
    (registry/register! (impl :test.orphaned))
    (f)))

(deftest register-and-resolve
  (is (= :test.validator-a
         (:implementation/id (registry/resolve-implementation :test.validator-a))))
  (is (nil? (registry/resolve-implementation :test.never)))
  (is (thrown? clojure.lang.ExceptionInfo
               (registry/register! {:implementation/kind :validator}))))

(deftest completeness-proof
  (let [r (registry/completeness [:test.validator-a :test.validator-b])]
    (is (not (:ok? r)))
    (is (= [:test.validator-b] (:missing r))))
  (let [r (registry/completeness [:test.validator-a])]
    (is (:ok? r))
    (is (empty? (:missing r)))))

(deftest classification
  (let [cls (registry/classify [:test.validator-a])]
    (is (some #{:test.validator-a} (:active cls)))
    (is (some #{:test.experimental} (:experimental cls)))
    (is (some #{:test.deprecated} (:deprecated cls)))
    (is (some #{:test.orphaned} (:orphaned cls)))))

(deftest implementation-root-excludes-run
  (is (string? (registry/implementation-root (impl :test.with-run)))))

(deftest registry-root-deterministic
  (let [r1 (registry/registry-root)
        r1' (registry/registry-root)]
    (is (string? r1))
    (is (= r1 r1')) ; same committed state -> same root
    (is (registry/committed-registry-root-matches? r1))))

(deftest duplicate-id-rejected
  (testing "re-registering an id with a different entry throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (registry/register! (assoc (impl :test.validator-a) :implementation/version 99))))
    (testing "idempotent re-registration is allowed"
      (is (= :test.validator-a (registry/register! (impl :test.validator-a)))))))

(deftest required-implementations-resolve-with-kind
  (let [r (registry/required-implementations-ok?
           [{:implementation/id :test.validator-a :implementation/kind :validator}
            {:implementation/id :test.missing :implementation/kind :validator}])]
    (is (not (:ok? r)))
    (is (some #(= :violation/unresolved-implementation (:violation/id %)) (:violations r))))
  (testing "kind mismatch rejected"
    (let [r (registry/required-implementations-ok?
             [{:implementation/id :test.validator-a :implementation/kind :transformer}])]
      (is (not (:ok? r)))
      (is (some #(= :violation/implementation-kind-mismatch (:violation/id %)) (:violations r)))))
  (testing "all resolve"
    (let [r (registry/required-implementations-ok?
             [{:implementation/id :test.validator-a :implementation/kind :validator}])]
      (is (:ok? r))
      (is (string? (:registry/root r))))))

(deftest experimental-not-allowed-unless-permitted
  (let [req [{:implementation/id :test.experimental :implementation/kind :validator}]]
    (is (seq (registry/experimental-violations req #{})))
    (is (empty? (registry/experimental-violations req #{:test.experimental})))))
