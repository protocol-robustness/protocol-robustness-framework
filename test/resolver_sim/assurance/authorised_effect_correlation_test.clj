(ns resolver-sim.assurance.authorised-effect-correlation-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.assurance.authorised-effect-correlation :as correlation]))

(defn- hash-ref [label]
  (str "sha256:" (hc/domain-hash :evidence-record {:label label})))

(defn- fields []
  {:protocol/id :sew
   :research-assignment/hash (hash-ref :assignment)
   :researcher-force-authorisation/hash (hash-ref :authorisation)
   :reservation/hash (hash-ref :reservation)
   :reservation/execution-attempt-id :attempt/held-custody
   :public-authorisation/id "fa-0"
   :public-authorisation/scope-hash (hash-ref :scope)
   :effect/type :held-adjustment
   :effect/id "held-0"
   :effect/artifact-hash (hash-ref :custody-artifact)})

(deftest correlation-is-canonical-and-tamper-evident
  (let [built (correlation/build-correlation (fields))]
    (is (correlation/valid-correlation? built))
    (is (= (:correlation/hash built) (correlation/correlation-hash built)))
    (is (= :correlation-hash-mismatch
           (correlation/correlation-error
            (assoc built :effect/id "held-other"))))))

(deftest correlation-rejects-noncanonical-and-unknown-fields
  (let [base (correlation/build-correlation (fields))]
    (is (= :invalid-artifact-type
           (correlation/correlation-error (assoc base :artifact/type :wrong))))
    (is (= :unsupported-artifact-version
           (correlation/correlation-error (assoc base :artifact/version 2))))
    (is (= :unsupported-artifact-version
           (correlation/correlation-error (dissoc base :artifact/version))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (correlation/build-correlation
                  (assoc base :public-authorisation/scope-hash "bare-digest"))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (correlation/build-correlation
                  (assoc base :effect/artifact-hash "blake3:deadbeef"))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (correlation/build-correlation (assoc base :effect/type :unknown-effect))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (correlation/build-correlation
                  (assoc base :reservation/execution-attempt-id :unqualified))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (correlation/build-correlation (assoc base :effect/id {:runtime :map}))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (correlation/build-correlation (assoc base :unknown/value :injected))))))

(deftest every-committed-field-changes-the-hash-and-invalidates-the-original
  (let [original (correlation/build-correlation (fields))
        replacements {:protocol/id :other-protocol
                      :research-assignment/hash (hash-ref :assignment-other)
                      :researcher-force-authorisation/hash (hash-ref :authorisation-other)
                      :reservation/hash (hash-ref :reservation-other)
                      :reservation/execution-attempt-id :attempt/other
                      :public-authorisation/id "fa-other"
                      :public-authorisation/scope-hash (hash-ref :scope-other)
                      :effect/type :unknown-effect
                      :effect/id "held-other"
                      :effect/artifact-hash (hash-ref :artifact-other)}]
    (doseq [[field replacement] replacements]
      (let [changed (assoc original field replacement)]
        (is (not= (:correlation/hash original) (correlation/correlation-hash changed))
            (str "hash commits " field))
        (is (false? (correlation/valid-correlation? changed))
            (str "stored hash invalid after " field))))))

(deftest correlation-rebuild-is-deterministic-and-domain-separated
  (let [input (fields)
        a (correlation/build-correlation input)
        b (correlation/build-correlation (into {} (reverse (seq input))))]
    (is (= a b))
    (is (not= (:correlation/hash a)
              (str "sha256:" (hc/domain-hash :force-authorisation-reservation input))))
    (is (not= (:correlation/hash a)
              (str "sha256:" (hc/domain-hash :force-authorisation-consumption input))))
    (is (not= (:correlation/hash a)
              (str "sha256:" (hc/domain-hash :force-authorised-execution-evidence input))))))
