(ns resolver-sim.evidence.artifact-test
  "Direct contract tests for the neutral content-addressed-artifact namespace.
   The force-authorisation suites exercise these primitives indirectly; these
   tests pin the generic contract so a future domain refactor cannot be the only
   effective coverage for a core primitive."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.evidence.artifact :as artifact]))

(def ^:private schema-version "test.artifact.v1")
(def ^:private kind :test/artifact)
(def ^:private verifier "test.artifact.verifier.v1")

(defn- build
  "Finalize a small artifact body with the identity fields committed before
   hashing."
  [body]
  (artifact/finalize-artifact
   (assoc body :schema-version schema-version
          :artifact/kind kind
          :artifact/verifier verifier)))

(def ^:private body {:id 1 :name "a" :tags [:x :y]})

(deftest exact-preimage-round-trip
  (let [a (build body)]
    (is (artifact/valid-artifact? a schema-version kind verifier))
    (testing "the preimage is the exact pr-str of the envelope-stripped body"
      (is (= (:artifact/preimage a) (pr-str (artifact/artifact-body a)))))))

(deftest body-mutation-invalidates
  (let [a (build body)]
    (is (not (artifact/valid-artifact? (assoc a :name "mutated") schema-version kind verifier)))
    (is (not (artifact/valid-artifact? (dissoc a :name) schema-version kind verifier)))))

(deftest forged-preimage-invalidates
  (let [a (build body)]
    (is (not (artifact/valid-artifact? (assoc a :artifact/preimage "forged")
                                       schema-version kind verifier)))
    (is (not (artifact/valid-artifact? (assoc a :artifact/preimage 42)
                                       schema-version kind verifier)))))

(deftest forged-hash-invalidates
  (let [a (build body)]
    (is (not (artifact/valid-artifact? (assoc a :artifact/hash "sha256:forged")
                                       schema-version kind verifier)))
    (is (not (artifact/valid-artifact? (dissoc a :artifact/hash)
                                       schema-version kind verifier)))))

(deftest exact-kind-schema-verifier-required
  (let [a (build body)]
    (is (not (artifact/valid-artifact? a "wrong.v1" kind verifier)))
    (is (not (artifact/valid-artifact? a schema-version :other verifier)))
    (is (not (artifact/valid-artifact? a schema-version kind "wrong.verifier.v1")))
    (is (not (artifact/valid-artifact? nil schema-version kind verifier)))
    (is (not (artifact/valid-artifact? 42 schema-version kind verifier)))))

(deftest optional-canonical-commitment
  (let [a (build body)
        committed (artifact/attach-canonical-commitment a)]
    (testing "attaching the commitment never changes :artifact/hash or preimage"
      (is (= (:artifact/hash a) (:artifact/hash committed)))
      (is (= (:artifact/preimage a) (:artifact/preimage committed))))
    (testing "the committed artifact validates"
      (is (artifact/valid-artifact? committed schema-version kind verifier)))
    (testing "a forged commitment is rejected"
      (is (not (artifact/valid-artifact? (assoc committed :artifact/canonical-bytes-v2 "00ff")
                                         schema-version kind verifier)))
      (is (not (artifact/valid-artifact? (assoc committed :artifact/canonical-hash-v2 "sha256:forged")
                                         schema-version kind verifier))))))

(deftest envelope-exclusion
  (let [a (build body)
        stripped (artifact/artifact-body a)]
    (is (not (contains? stripped :artifact/hash)))
    (is (not (contains? stripped :artifact/preimage)))
    (is (not (contains? stripped :artifact/canonical-bytes-v2)))
    (is (not (contains? stripped :artifact/canonical-hash-v2)))
    (testing "envelope keys never participate in the committed hash"
      (is (= (:artifact/hash (build body))
             (:artifact/hash (-> (build body)
                                 (assoc :artifact/canonical-bytes-v2 "x"
                                        :artifact/canonical-hash-v2 "y"))))))))

(deftest unknown-key-behaviour
  (testing "unknown body keys are part of the committed content (exact shape is
            a caller policy, not enforced here)"
    (let [a (build (assoc body :extra :value))]
      (is (artifact/valid-artifact? a schema-version kind verifier))
      (is (not= (:artifact/hash (build body)) (:artifact/hash a))))))

(deftest deterministic-finalization
  (testing "the same body finalizes to the same hash and preimage"
    (let [a (build body)
          b (build body)]
      (is (= (:artifact/hash a) (:artifact/hash b)))
      (is (= (:artifact/preimage a) (:artifact/preimage b))))
    (testing "key order does not affect the hash"
      (is (= (:artifact/hash (build body))
             (:artifact/hash (build (into (sorted-map) body))))))))
