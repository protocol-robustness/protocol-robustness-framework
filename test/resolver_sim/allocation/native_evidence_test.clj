(ns resolver-sim.allocation.native-evidence-test
  "Tests for native Rust evidence binding to exact-replication classification.
   A mock result never satisfies a proof-backed predicate, and evidence bound to
   another result or another pinned implementation is deterministically
   downgraded."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.allocation.certificate :as cert]
            [resolver-sim.allocation.context :as context]
            [resolver-sim.allocation.native-evidence :as native-evidence]
            [resolver-sim.allocation.test-fixtures :as fixtures]))

(defn- native
  "Build a well-formed native-evidence map, with `overrides` applied last."
  [result overrides]
  (merge
   {:native-evidence/schema "native-evidence.v1"
    :native-evidence/kind :exact-replication
    :native-evidence/source :native-executed
    :native-evidence/verifier-version native-evidence/native-verifier-version
    :native-evidence/results-artifact-hash "sha256:RESULTS-1"
    :native-evidence/input-root (:allocation-context-hash result)
    :native-evidence/prf-identity
    {:artifact-kind :prf-allocation-kernel
     :kernel-version context/kernel-version
     :schema-version context/schema-version
     :canonical-abi-version "CANONICAL_HASH_SPEC_V1_BINARY_ENCODING_ABI"}
    :native-evidence/rust-identity
    {:implementation "prf-allocation-kernel-rust" :version "rust.v1" :commit "c1"}
    :native-evidence/run-id "vector-1"
    :native-evidence/comparison :match}
   overrides))

(defn- with-reference
  "Attach the committed native reference binding to a kernel result."
  ([result] (with-reference result {}))
  ([result {:keys [results-artifact-hash pinned-rust]}]
   (assoc result :native/reference
          {:results-artifact-hash (or results-artifact-hash "sha256:RESULTS-1")
           :pinned-rust (or pinned-rust
                            {:implementation "prf-allocation-kernel-rust"
                             :version "rust.v1" :commit "c1"})})))

(deftest prf-only-no-native-evidence
  (let [result (with-reference (fixtures/kernel-result))
        c (cert/compose-certificate result)
        class (get-in c [:exact-replication])]
    (is (= :pending-independent-replay (:classification class)))
    (is (false? (:proof-backed? class)))
    (is (= :no-native-evidence (:reason class)))
    (is (= :not-yet-evaluated (get-in c [:proof :status])))
    (is (= :mock-native (get-in c [:proof :proof-mode])))))

(deftest matching-native-execution-is-proof-backed
  (let [result (with-reference (fixtures/kernel-result))
        evidence (native result {})
        c (cert/compose-certificate result nil evidence)
        class (get-in c [:exact-replication])]
    (is (= :native-exact-match (:classification class)))
    (is (true? (:proof-backed? class)))
    (is (= :ok (:reason class)))
    (is (= :valid (get-in c [:proof :status])))
    (is (= :native-rust (get-in c [:proof :proof-mode])))
    (is (string? (get-in c [:proof :proof-hash])))
    (testing "the proof binds the full evidence (result hash, roots, identities)"
      (let [bound (get-in c [:proof :native-evidence])]
        (is (= "sha256:RESULTS-1" (:native-evidence/results-artifact-hash bound)))
        (is (= :match (:native-evidence/comparison bound)))
        (is (= "vector-1" (:native-evidence/run-id bound)))))))

(deftest mismatching-native-execution-downgraded
  (let [result (with-reference (fixtures/kernel-result))
        evidence (native result {:native-evidence/comparison :mismatch})
        c (cert/compose-certificate result nil evidence)
        class (get-in c [:exact-replication])]
    (is (= :independent-replay (:classification class)))
    (is (false? (:proof-backed? class)))
    (is (= :comparison-mismatch (:reason class)))
    (is (= :not-yet-evaluated (get-in c [:proof :status])))))

(deftest mock-native-never-proof-backed
  (let [result (with-reference (fixtures/kernel-result))
        evidence (native result {:native-evidence/source :mock
                                 :native-evidence/comparison :match})
        c (cert/compose-certificate result nil evidence)
        class (get-in c [:exact-replication])]
    (is (= :mock-native (:classification class)))
    (is (false? (:proof-backed? class)))
    (is (= :mock-not-proof (:reason class)))
    (is (not= :valid (get-in c [:proof :status])))
    (is (= :mock-native (get-in c [:proof :proof-mode])))))

(deftest native-evidence-for-another-result-downgraded
  (let [result (with-reference (fixtures/kernel-result)
                 {:results-artifact-hash "sha256:EXPECTED-1"})
        evidence (native result {:native-evidence/results-artifact-hash "sha256:OTHER-1"})
        c (cert/compose-certificate result nil evidence)
        class (get-in c [:exact-replication])]
    (is (= :independent-replay (:classification class)))
    (is (= :bound-to-another-result (:reason class)))))

(deftest native-evidence-for-another-pinned-implementation-downgraded
  (let [result (with-reference (fixtures/kernel-result)
                 {:pinned-rust {:implementation "official-rust-kernel"
                                :version "rust.v2" :commit "c2"}})
        evidence (native result {:native-evidence/rust-identity
                                 {:implementation "prf-allocation-kernel-rust"
                                  :version "rust.v1" :commit "c1"}})
        c (cert/compose-certificate result nil evidence)
        class (get-in c [:exact-replication])]
    (is (= :independent-replay (:classification class)))
    (is (= :other-pinned-rust-implementation (:reason class)))))

(deftest native-evidence-for-another-pinned-prf-downgraded
  (let [result (with-reference (fixtures/kernel-result)
                 {:pinned-prf {:implementation "official-prf-kernel"
                               :version "prf.v2"}})
        evidence (native result {:native-evidence/prf-identity
                                 {:implementation "prf-allocation-kernel"
                                  :version "prf.v1"}})
        c (cert/compose-certificate result nil evidence)
        class (get-in c [:exact-replication])]
    (is (= :independent-replay (:classification class)))
    (is (= :other-pinned-prf-implementation (:reason class)))))

(deftest malformed-and-stale-evidence-not-evaluated
  (let [result (with-reference (fixtures/kernel-result))]
    (testing "malformed evidence (wrong schema)"
      (let [bad (assoc (native result {}) :native-evidence/schema "old.v1")
            c (cert/compose-certificate result nil bad)]
        (is (= :not-yet-evaluated (get-in c [:exact-replication :classification])))))
    (testing "stale verifier version"
      (let [stale (assoc (native result {}) :native-evidence/verifier-version "old-verifier")
            c (cert/compose-certificate result nil stale)]
        (is (= :not-yet-evaluated (get-in c [:exact-replication :classification])))
        (is (= :stale-verifier (get-in c [:exact-replication :reason])))))))

(deftest result-mutation-after-native-comparison-downgraded
  (testing "a result changed after the native comparison is no longer proof-backed"
    (let [result (with-reference (fixtures/kernel-result))
          evidence (native result {:native-evidence/result-root (:result-root result)})
          mutated (update result :result-root
                          (constantly "0x0000000000000000000000000000000000000000000000000000000000000000"))
          c (cert/compose-certificate mutated nil evidence)
          class (get-in c [:exact-replication])]
      (is (false? (:proof-backed? class)))
      (is (= :bound-to-another-result (:reason class)))
      (is (contains? #{:independent-replay :not-yet-evaluated} (:classification class)))))
  (testing "a results artifact changed after comparison is downgraded via its hash"
    (let [result (with-reference (fixtures/kernel-result)
                   {:results-artifact-hash "sha256:RESULTS-2"})
          evidence (native result {:native-evidence/results-artifact-hash "sha256:RESULTS-1"})
          c (cert/compose-certificate result nil evidence)]
      (is (= :bound-to-another-result (get-in c [:exact-replication :reason]))))))

(deftest native-classifier-direct
  (testing "classifier downgrades when reference pins a different result"
    (let [r (native-evidence/exact-replication-classification
             {:native-evidence/schema "native-evidence.v1"
              :native-evidence/kind :exact-replication
              :native-evidence/source :native-executed
              :native-evidence/verifier-version native-evidence/native-verifier-version
              :native-evidence/results-artifact-hash "sha256:A"
              :native-evidence/rust-identity {:implementation "r" :version "v1"}
              :native-evidence/prf-identity {:implementation "p" :version "v1"}
              :native-evidence/comparison :match}
             {:results-artifact-hash "sha256:B"})]
      (is (= :independent-replay (:classification r)))
      (is (= :bound-to-another-result (:reason r)))))
  (testing "nil evidence is pending-independent-replay"
    (let [r (native-evidence/exact-replication-classification nil {:results-artifact-hash "sha256:A"})]
      (is (= :pending-independent-replay (:classification r))))))
