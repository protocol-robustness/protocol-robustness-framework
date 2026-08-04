(ns resolver-sim.conformance.registry-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
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
