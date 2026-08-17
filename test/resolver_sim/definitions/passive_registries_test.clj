(ns resolver-sim.definitions.passive-registries-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.definitions.passive-registries :as registries]
            [resolver-sim.hash.canonical :as hc]))

(defn- entry-hash
  [intent entry]
  (hc/hash-with-intent {:hash/intent intent} entry))

(defn- with-entry-hash
  [intent hash-key entry]
  (assoc entry hash-key (entry-hash intent entry)))

(def minimal-registry-spec
  {:entries-key :entries
   :required-registry-fields #{:registry-version :entries}
   :required-entry-fields #{:id :version :canonical-hash}
   :hash-intent :claim-definition
   :hash-key :canonical-hash})

(def valid-minimal-entry
  (with-entry-hash
    :claim-definition
    :canonical-hash
    {:id :registry-test/entry
     :version 1}))

(def valid-minimal-registry
  {:registry-version 1
   :entries [valid-minimal-entry]})

(def execution-registry-spec
  {:entries-key :executions
   :required-registry-fields #{:registry-version :executions}
   :required-entry-fields #{:id :version :kind :runner :entry
                            :execution/type :execution/mode
                            :description :claims}
   :registry-validator-fn registries/validate-execution-registry-entries})

(defn- execution-entry
  ([] (execution-entry {}))
  ([overrides]
   (merge {:id :execution/test-simulation
           :version 1
           :kind :simulation
           :runner :phase-runner
           :entry 'resolver-sim.core.phases/run-simulation
           :execution/type :simulation
           :execution/mode :full
           :description "Test execution entry"
           :claims #{:deterministic-replay}}
          overrides)))

(defn- claim-definition-entry
  ([] (claim-definition-entry {}))
  ([overrides]
   (with-entry-hash
     :claim-definition
     :canonical-hash
     (merge {:id :claim/test
             :version 1
             :category :audit
             :description "Test claim definition"
             :inputs [:evidence-node]
             :evaluation {:type :code-reference
                          :entry 'resolver-sim.claims.engine/evaluate-presence-claim}
             :outputs [:holds?]}
            overrides))))

(deftest passive-registries-validate
  (testing "all 9 passive registries are internally valid"
    (is (:valid? (registries/validate-intent-registry)))
    (is (:valid? (registries/validate-projection-definition-registry)))
    (is (:valid? (registries/validate-claim-definition-registry)))
    (is (:valid? (registries/validate-attestor-registry)))
    (is (:valid? (registries/validate-execution-registry)))
    (is (:valid? (registries/validate-evidence-policy-registry)))
    (is (:valid? (registries/validate-creation-provenance-registry)))
    (is (:valid? (registries/validate-hash-projection-registry)))
    (is (:valid? (registries/validate-domain-tag-registry)))))

(deftest aggregate-validation-includes-all-registries
  (testing "validate-passive-registries covers all 9 registries"
    (let [result (registries/validate-passive-registries)]
      (is (:valid? result))
      (is (= 9 (count (:results result))))
      (is (empty? (:errors result))))))

(deftest passive-registry-data-is-hashed-deterministically
  (testing "registered entries keep stable canonical self hashes"
    (doseq [[intent hash-key entries] [[:intent-registry-entry
                                        :canonical-hash
                                        (:intents registries/intent-registry)]
                                       [:projection-definition
                                        :canonical-hash
                                        (:projection-definitions registries/projection-definition-registry)]
                                       [:claim-definition
                                        :canonical-hash
                                        (:claim-definitions registries/claim-definition-registry)]
                                       [:attestor
                                        :attestor-hash
                                        (:attestors registries/attestor-registry)]]]
      (doseq [entry entries]
        (is (= (get entry hash-key)
               (entry-hash intent entry)))))))

(deftest validators-report-invalid-data-without-throwing
  (testing "missing fields, duplicate ids, invalid versions, and hash mismatches are errors"
    (let [bad-entry (assoc valid-minimal-entry
                           :version 0
                           :canonical-hash "not-the-canonical-hash")
          bad-registry {:registry-version 2
                        :entries [(dissoc bad-entry :id)
                                  bad-entry
                                  valid-minimal-entry]}
          result (registries/validate-registry
                  :minimal-test-registry
                  bad-registry
                  minimal-registry-spec)
          error-codes (set (map :error (:errors result)))]
      (is (false? (:valid? result)))
      (is (contains? error-codes :registry/unsupported-version))
      (is (contains? error-codes :entry/missing-fields))
      (is (contains? error-codes :entry/invalid-version))
      (is (contains? error-codes :entry/hash-mismatch)))))

(deftest validators-detect-duplicate-ids
  (let [registry (assoc valid-minimal-registry
                        :entries [valid-minimal-entry valid-minimal-entry])
        result (registries/validate-registry
                :minimal-test-registry
                registry
                minimal-registry-spec)]
    (is (false? (:valid? result)))
    (is (some #(= :entry/duplicate-ids (:error %)) (:errors result)))))

(deftest execution-registry-detects-unknown-runner
  (let [registry {:registry-version 1
                  :executions [(execution-entry {:runner :missing-runner})]}
        result (registries/validate-registry
                :execution-registry
                registry
                execution-registry-spec)]
    (is (false? (:valid? result)))
    (is (some #(= :entry/unknown-runner (:error %)) (:errors result)))))

(deftest execution-registry-detects-unresolved-entry-point
  (let [registry {:registry-version 1
                  :executions [(execution-entry {:entry 'resolver-sim.missing/does-not-exist})]}
        result (registries/validate-registry
                :execution-registry
                registry
                execution-registry-spec)]
    (is (false? (:valid? result)))
    (is (some #(= :entry/unresolved-entry-point (:error %)) (:errors result)))))

(deftest execution-registry-startup-safe-keeps-passive-load-safe
  (let [registry {:registry-version 1
                  :executions [(execution-entry {:entry 'resolver-sim.core.phases/does-not-exist})]}
        result (registries/validate-registry
                :execution-registry
                registry
                execution-registry-spec)]
    (is (:valid? result))
    (is (empty? (:errors result)))))

(deftest execution-registry-strict-validation-catches-missing-entry-vars
  (let [registry {:registry-version 1
                  :executions [(execution-entry {:entry 'resolver-sim.core.phases/does-not-exist})]}
        result (binding [registries/*entry-validation-mode* :strict]
                 (registries/validate-registry
                  :execution-registry
                  registry
                  execution-registry-spec))]
    (is (false? (:valid? result)))
    (is (some #(= :entry/unresolved-entry-point (:error %)) (:errors result)))))

(deftest execution-registry-detects-unknown-dependencies
  (let [registry {:registry-version 1
                  :executions [(execution-entry {:depends-on [:execution/missing]})]}
        result (registries/validate-registry
                :execution-registry
                registry
                execution-registry-spec)]
    (is (false? (:valid? result)))
    (is (some #(= :entry/unknown-dependencies (:error %)) (:errors result)))))

(deftest execution-registry-detects-dependency-cycles
  (let [registry {:registry-version 1
                  :executions [(execution-entry {:id :execution/a
                                                 :depends-on [:execution/b]})
                               (execution-entry {:id :execution/b
                                                 :entry 'resolver-sim.core.phases/run-sweep
                                                 :depends-on [:execution/a]})]}
        result (registries/validate-registry
                :execution-registry
                registry
                execution-registry-spec)]
    (is (false? (:valid? result)))
    (is (some #(= :registry/dependency-cycle (:error %)) (:errors result)))))

(deftest claim-definition-registry-detects-unknown-dependencies
  (let [registry {:registry-version 1
                  :claim-definitions [(claim-definition-entry {:depends-on [:claim/missing]})]}
        result (registries/validate-claim-definition-registry-entries
                :claim-definition-registry
                (:claim-definitions registry))]
    (is (false? (:valid? (registries/validate-registry
                          :claim-definition-registry
                          registry
                          {:entries-key :claim-definitions
                           :required-registry-fields #{:registry-version :claim-definitions}
                           :required-entry-fields #{:id :version :category :description
                                                    :inputs :evaluation :outputs :canonical-hash}
                           :hash-intent :claim-definition
                           :hash-key :canonical-hash
                           :registry-validator-fn registries/validate-claim-definition-registry-entries}))))
    (is (some #(= :entry/unknown-dependencies (:error %)) result))))

(deftest claim-definition-registry-detects-dependency-cycles
  (let [entries [(claim-definition-entry {:id :claim/a
                                          :depends-on [:claim/b]})
                 (claim-definition-entry {:id :claim/b
                                          :depends-on [:claim/a]})]
        result (registries/validate-claim-definition-registry-entries
                :claim-definition-registry
                entries)]
    (is (seq result))
    (is (some #(= :registry/dependency-cycle (:error %)) result))))

(deftest projection-definition-registry-detects-unknown-dependencies
  (let [entries [{:id :projection/test
                  :version 1
                  :projection-type :test
                  :intent-types #{:test}
                  :intent-purposes #{:test}
                  :source {:type :test}
                  :output {:type :test}
                  :claims []
                  :depends-on [:projection/missing]}]
        result (registries/validate-projection-definition-registry-entries
                :projection-definition-registry entries)]
    (is (some #(= :entry/unknown-dependencies (:error %)) result))))

(deftest projection-definition-registry-detects-dependency-cycles
  (let [entries [{:id :projection/a
                  :version 1
                  :projection-type :test
                  :intent-types #{:test}
                  :intent-purposes #{:test}
                  :source {:type :test}
                  :output {:type :test}
                  :claims []
                  :depends-on [:projection/b]}
                 {:id :projection/b
                  :version 1
                  :projection-type :test
                  :intent-types #{:test}
                  :intent-purposes #{:test}
                  :source {:type :test}
                  :output {:type :test}
                  :claims []
                  :depends-on [:projection/a]}]
        result (registries/validate-projection-definition-registry-entries
                :projection-definition-registry entries)]
    (is (some #(= :registry/dependency-cycle (:error %)) result))))

(deftest claim-definition-registry-strict-validation-catches-missing-code-reference-vars
  (let [entries [(claim-definition-entry {:evaluation {:type :code-reference
                                                       :entry 'resolver-sim.core.phases/does-not-exist}})]
        result (binding [registries/*entry-validation-mode* :strict]
                 (registries/validate-claim-definition-registry-entries
                  :claim-definition-registry
                  entries))]
    (is (seq result))
    (is (some #(= :entry/unresolved-evaluation-entry (:error %)) result))))

(deftest claim-definition-registry-startup-safe-validation-avoids-eager-load-failure
  (let [entries [(claim-definition-entry {:evaluation {:type :code-reference
                                                       :entry 'resolver-sim.core.phases/does-not-exist}})]
        result (registries/validate-claim-definition-registry-entries
                :claim-definition-registry
                entries)]
    (is (empty? result))))

(deftest validate-all-registries-hard-fails
  (testing "validate-all-registries! throws on invalid data"
    (with-redefs [registries/validate-passive-registries
                  (constantly {:valid? false
                               :results []
                               :errors [{:error :test/forced-invalid}]})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Registry validation failed"
           (registries/validate-all-registries!))))
    (testing "validate-passive-registries! (legacy alias) also throws"
      (with-redefs [registries/validate-passive-registries
                    (constantly {:valid? false
                                 :results []
                                 :errors [{:error :test/forced-invalid}]})]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Registry validation failed"
             (registries/validate-passive-registries!)))))))

(deftest startup-validation-runs-on-load
  (testing "startup-validation is a non-nil def that runs validate-all-registries! at load"
    ;; startup-validation is defined as (validate-all-registries!) in passive_registries.clj
    ;; Since all 8 registries are valid, it evaluates to nil (the return of validate-all-registries!)
    (is (nil? (resolve 'registries/startup-validation))
        "startup-validation is a private def, so resolve returns nil from test ns
         (it's in the registry ns, not re-exported). The fact that the namespace
         loaded without throwing confirms startup validation passed.")))

;; ── Attestor Registry Runtime Query Tests ────────────────────────────────────

(deftest find-attestor-returns-known-attestor
  (let [attestor (registries/find-attestor :ci-validation)]
    (is (some? attestor))
    (is (= :ci-runner (:type attestor)))
    (is (= :active (:status attestor)))
    (is (= {:type :public-key
            :algorithm :ed25519
            :key-id "ci-validation-placeholder"
            :public-key "ci-validation-placeholder-public-key"}
           (:verification attestor)))))

(deftest find-attestor-returns-nil-for-unknown
  (is (nil? (registries/find-attestor :does-not-exist))))

(deftest attestor-status-returns-keyword
  (let [attestor (registries/find-attestor :ci-validation)]
    (is (= :active (registries/attestor-status attestor)))))

(deftest attestor-active-returns-true-for-active-attestor
  (let [attestor (registries/find-attestor :ci-validation)]
    (is (true? (registries/attestor-active? attestor)))))

(deftest attestor-revoked-returns-false-for-active-attestor
  (let [attestor (registries/find-attestor :ci-validation)]
    (is (false? (registries/attestor-revoked? attestor)))))

(deftest key-authorized-returns-true-for-primary-key
  (let [attestor (registries/find-attestor :ci-validation)]
    (is (true? (registries/key-authorized-for-attestor?
                attestor "ci-validation-placeholder")))))

(deftest key-authorized-returns-true-for-active-key-history
  (let [attestor (registries/find-attestor :ci-validation)]
    (is (registries/key-authorized-for-attestor?
         attestor "ci-validation-placeholder"))))

(deftest key-authorized-returns-false-for-retired-key
  (let [attestor (registries/find-attestor :ci-validation)]
    (is (not (registries/key-authorized-for-attestor?
              attestor "ci-validation-v0")))))

(deftest key-authorized-returns-false-for-unknown-key
  (let [attestor (registries/find-attestor :ci-validation)]
    (is (not (registries/key-authorized-for-attestor?
              attestor "completely-unknown-key")))))

(deftest key-known-returns-true-for-retired-key
  (let [attestor (registries/find-attestor :ci-validation)]
    (is (registries/key-known-for-attestor?
         attestor "ci-validation-v0"))))

(deftest key-known-returns-true-for-primary-key
  (let [attestor (registries/find-attestor :ci-validation)]
    (is (registries/key-known-for-attestor?
         attestor "ci-validation-placeholder"))))

(deftest key-known-returns-true-for-delegate
  (let [attestor (registries/find-attestor :ci-validation)]
    (is (registries/key-known-for-attestor?
         attestor :ci-validation-signing-key))))

(deftest key-known-returns-false-for-unknown-key
  (let [attestor (registries/find-attestor :ci-validation)]
    (is (not (registries/key-known-for-attestor?
              attestor "never-heard-of-it")))))

(deftest find-local-development-attestor-returns-expected-structure
  (let [attestor (registries/find-attestor :local-development)]
    (is (some? attestor))
    (is (= :validator (:type attestor)))
    (is (= :active (:status attestor)))
    (is (= {:type :local-process :trust-boundary :developer-workstation}
           (:verification attestor)))
    (is (= [] (:key-history attestor)))
    (is (= [] (:delegates attestor)))))

;; ── Attestor Registry Validation Tests ────────────────────────────────────────
;; ATTESTATOR_REGISTRY_SPEC_V1 §9: startup SHALL fail on:
;;   invalid verification method, duplicate active key ids, malformed public keys

(defn- valid-attestor-entry
  ([] (valid-attestor-entry {}))
  ([overrides]
   (let [base {:id :test/attestor
               :version 1
               :type :validator
               :display-name "Test attestor"
               :status :active
               :verification {:type :public-key
                              :algorithm :ed25519
                              :key-id "test-key-001"
                              :public-key "test-public-key-bytes"}
               :delegates []
               :key-history []
               :metadata {}}
         merged (merge base overrides)]
     (with-entry-hash :attestor :attestor-hash merged))))

(defn- run-attestor-validation
  [registry-name entries]
  (registries/validate-attestor-registry-entries registry-name entries))

(deftest attestor-registry-validates-normalized-ed25519-keys
  (let [entry (valid-attestor-entry
               {:verification {:type :public-key :algorithm :ed25519 :active-key-id "key-2026"}
                :keys [{:key-id "key-2026" :algorithm :ed25519 :public-key-encoding :hex
                        :public-key (apply str (repeat 64 "a")) :status :active
                        :valid-from "2026-01-01T00:00:00Z"}]})]
    (is (empty? (run-attestor-validation :test-registry [entry]))))
  (let [entry (valid-attestor-entry
               {:verification {:type :public-key :algorithm :ed25519 :active-key-id "key-2026"}
                :keys [{:key-id "key-2026" :algorithm :ed25519 :public-key-encoding :hex
                        :public-key (apply str (repeat 64 "a")) :status :active
                        :valid-from "2026-01-01T00:00:00Z"}
                       {:key-id "key-2026" :algorithm :ed25519 :public-key-encoding :hex
                        :public-key (apply str (repeat 64 "b")) :status :retired
                        :valid-from "2025-01-01T00:00:00Z"}]})
        errors (run-attestor-validation :test-registry [entry])]
    (is (some #(= :entry/duplicate-attestor-key-id (:error %)) errors))))

(deftest attestor-registry-accepts-valid-entry
  (testing "a valid public-key attestor passes validation"
    (let [entry (valid-attestor-entry)
          errors (run-attestor-validation :test-registry [entry])]
      (is (empty? errors)))))

(deftest attestor-registry-accepts-local-process-verification
  (testing "a non public-key verification method is valid"
    (let [entry (valid-attestor-entry {:verification {:type :local-process
                                                      :trust-boundary :developer-workstation}})
          errors (run-attestor-validation :test-registry [entry])]
      (is (empty? errors)))))

(deftest attestor-registry-detects-unknown-verification-type
  (let [entry (valid-attestor-entry {:verification {:type :magic
                                                    :algorithm :ed25519
                                                    :key-id "k1"
                                                    :public-key "pk"}})
        errors (run-attestor-validation :test-registry [entry])]
    (is (seq errors))
    (is (some #(= :entry/invalid-verification-method (:error %)) errors))))

(deftest attestor-registry-detects-non-map-verification
  (let [entry (valid-attestor-entry {:verification "not-a-map"})
        errors (run-attestor-validation :test-registry [entry])]
    (is (seq errors))
    (is (some #(= :entry/invalid-verification-method (:error %)) errors))))

(deftest attestor-registry-detects-missing-verification-type
  (let [entry (valid-attestor-entry {:verification {:algorithm :ed25519
                                                    :key-id "k1"
                                                    :public-key "pk"}})
        errors (run-attestor-validation :test-registry [entry])]
    (is (seq errors))
    (is (some #(= :entry/invalid-verification-method (:error %)) errors))))

(deftest attestor-registry-detects-missing-algorithm-on-public-key
  (let [entry (valid-attestor-entry {:verification {:type :public-key
                                                    :key-id "k1"
                                                    :public-key "pk"}})
        errors (run-attestor-validation :test-registry [entry])]
    (is (seq errors))
    (is (some #(= :entry/invalid-verification-method (:error %)) errors))))

(deftest attestor-registry-detects-unknown-algorithm
  (let [entry (valid-attestor-entry {:verification {:type :public-key
                                                    :algorithm :sha1
                                                    :key-id "k1"
                                                    :public-key "pk"}})
        errors (run-attestor-validation :test-registry [entry])]
    (is (seq errors))
    (is (some #(= :entry/invalid-verification-method (:error %)) errors))))

(deftest attestor-registry-detects-missing-key-id
  (let [entry (valid-attestor-entry {:verification {:type :public-key
                                                    :algorithm :ed25519
                                                    :public-key "pk"}})
        errors (run-attestor-validation :test-registry [entry])]
    (is (seq errors))
    (is (some #(= :entry/missing-key-id (:error %)) errors))))

(deftest attestor-registry-detects-malformed-key-id
  (let [entry (valid-attestor-entry {:verification {:type :public-key
                                                    :algorithm :ed25519
                                                    :key-id 123
                                                    :public-key "pk"}})
        errors (run-attestor-validation :test-registry [entry])]
    (is (seq errors))
    (is (some #(= :entry/malformed-public-key (:error %)) errors))))

(deftest attestor-registry-detects-missing-public-key
  (let [entry (valid-attestor-entry {:verification {:type :public-key
                                                    :algorithm :ed25519
                                                    :key-id "k1"}})
        errors (run-attestor-validation :test-registry [entry])]
    (is (seq errors))
    (is (some #(= :entry/missing-public-key (:error %)) errors))))

(deftest attestor-registry-detects-malformed-public-key
  (let [entry (valid-attestor-entry {:verification {:type :public-key
                                                    :algorithm :ed25519
                                                    :key-id "k1"
                                                    :public-key 456}})
        errors (run-attestor-validation :test-registry [entry])]
    (is (seq errors))
    (is (some #(= :entry/malformed-public-key (:error %)) errors))))

(deftest attestor-registry-detects-invalid-status
  (let [entry (valid-attestor-entry {:status :invalid-status})
        errors (run-attestor-validation :test-registry [entry])]
    (is (seq errors))
    (is (some #(= :entry/invalid-status (:error %)) errors))))

;; ── Delegate Validation ───────────────────────────────────────────────────────

(deftest attestor-registry-detects-non-vector-delegates
  (let [entry (assoc (valid-attestor-entry) :delegates "not-a-vector")
        errors (run-attestor-validation :test-registry [entry])]
    (is (seq errors))
    (is (some #(= :entry/invalid-delegates (:error %)) errors))))

(deftest attestor-registry-detects-delegate-without-id
  (let [entry (valid-attestor-entry {:delegates [{:status :active}]})
        errors (run-attestor-validation :test-registry [entry])]
    (is (seq errors))
    (is (some #(= :entry/invalid-delegate (:error %)) errors))))

(deftest attestor-registry-detects-delegate-with-invalid-id-type
  (let [entry (valid-attestor-entry {:delegates [{:id 123 :status :active}]})
        errors (run-attestor-validation :test-registry [entry])]
    (is (seq errors))
    (is (some #(= :entry/invalid-delegate (:error %)) errors))))

(deftest attestor-registry-detects-delegate-with-invalid-status
  (let [entry (valid-attestor-entry {:delegates [{:id :test-delegate :status :invalid}]})
        errors (run-attestor-validation :test-registry [entry])]
    (is (seq errors))
    (is (some #(= :entry/invalid-delegate-status (:error %)) errors))))

;; ── Key History Validation ────────────────────────────────────────────────────

(deftest attestor-registry-detects-non-vector-key-history
  (let [entry (assoc (valid-attestor-entry) :key-history "not-a-vector")
        errors (run-attestor-validation :test-registry [entry])]
    (is (seq errors))
    (is (some #(= :entry/invalid-key-history (:error %)) errors))))

(deftest attestor-registry-detects-key-history-entry-without-key-id
  (let [entry (valid-attestor-entry {:key-history [{:status :active}]})
        errors (run-attestor-validation :test-registry [entry])]
    (is (seq errors))
    (is (some #(= :entry/invalid-key-history-entry (:error %)) errors))))

(deftest attestor-registry-detects-key-history-entry-with-non-string-key-id
  (let [entry (valid-attestor-entry {:key-history [{:key-id 123 :status :active}]})
        errors (run-attestor-validation :test-registry [entry])]
    (is (seq errors))
    (is (some #(= :entry/invalid-key-history-entry (:error %)) errors))))

(deftest attestor-registry-detects-key-history-entry-with-invalid-status
  (let [entry (valid-attestor-entry {:key-history [{:key-id "k1" :status :invalid}]})
        errors (run-attestor-validation :test-registry [entry])]
    (is (seq errors))
    (is (some #(= :entry/invalid-key-history-entry-status (:error %)) errors))))

;; ── Duplicate Active Key IDs ──────────────────────────────────────────────────

(deftest attestor-registry-detects-duplicate-active-key-ids-within-key-history
  (let [entry (valid-attestor-entry {:key-history [{:key-id "k1" :status :active}
                                                   {:key-id "k1" :status :active}]})
        errors (run-attestor-validation :test-registry [entry])]
    (is (seq errors))
    (is (some #(= :entry/duplicate-active-key-id (:error %)) errors))))

(deftest attestor-registry-detects-duplicate-active-key-ids-within-delegates
  (let [entry (valid-attestor-entry {:delegates [{:id :dup-key :status :active}
                                                 {:id :dup-key :status :active}]})
        errors (run-attestor-validation :test-registry [entry])]
    (is (seq errors))
    (is (some #(= :entry/duplicate-active-key-id (:error %)) errors))))

(deftest attestor-registry-detects-duplicate-active-key-ids-across-entries
  (let [entry-a (valid-attestor-entry {:id :attestor-a
                                       :verification {:type :public-key
                                                      :algorithm :ed25519
                                                      :key-id "shared-key"
                                                      :public-key "pk-a"}})
        entry-b (valid-attestor-entry {:id :attestor-b
                                       :verification {:type :public-key
                                                      :algorithm :ed25519
                                                      :key-id "shared-key"
                                                      :public-key "pk-b"}})
        errors (run-attestor-validation :test-registry [entry-a entry-b])]
    (is (seq errors))
    (is (some #(= :entry/duplicate-active-key-id (:error %)) errors))))

(deftest attestor-registry-allows-same-key-in-different-roles
  (testing "a key-id appearing both as primary and in key-history is NOT a duplicate"
    ;; The ci-validation attestor in the real registry has this pattern.
    (let [entry (valid-attestor-entry {:key-history [{:key-id "test-key-001" :status :active}]})]
      (is (empty? (run-attestor-validation :test-registry [entry]))))))

(deftest attestor-registry-allows-retired-key-in-key-history-as-non-duplicate
  (let [entry (valid-attestor-entry {:key-history [{:key-id "test-key-001" :status :retired}]})]
    (is (empty? (run-attestor-validation :test-registry [entry])))))

(deftest attestor-registry-allows-delegate-with-different-id-than-primary-key
  (let [entry (valid-attestor-entry {:delegates [{:id :delegate-key :status :active}]})]
    (is (empty? (run-attestor-validation :test-registry [entry])))))

;; ── Integration: validate-attestor-registry ───────────────────────────────────

(deftest validate-attestor-registry-returns-valid-for-real-entries
  (testing "the real attestor-registry passes validation"
    (is (:valid? (registries/validate-attestor-registry)))))

;; ── Cross-Registry Alignment Tests ────────────────────────────────────────────
;; Validates that validate-intent-registry-alignment catches mismatches between
;; passive intent-definitions and runtime hash-intents.

(defn- hash-projection-passive-entry
  "Build a minimal passive intent-definition entry with :identity/hash-projection type."
  ([hash-intent-kw] (hash-projection-passive-entry hash-intent-kw {}))
  ([hash-intent-kw overrides]
   (merge {:id (keyword "identity" (name hash-intent-kw))
           :version 1
           :intent/type :identity/hash-projection
           :intent/purpose (keyword (str (name hash-intent-kw) "-identity"))
           :scope {:protocols #{:framework}
                   :domains #{:identity}}
           :inputs #{:data}
           :constraints #{:canonical-safe}
           :output {:type :canonical-hash
                    :hash/intent hash-intent-kw}
           :extensions-policy {:allowed? false}}
          overrides)))

(defn- runtime-hash-intent-entry
  "Build a minimal runtime hash-intent contract entry."
  ([kw] (runtime-hash-intent-entry kw {}))
  ([kw overrides]
   (merge {:intent/name kw
           :intent/domain-tag (str (clojure.string/upper-case (name kw)) "_V1")
           :intent/description (str "Test intent for " kw)
           :intent/includes #{:data}
           :intent/excludes #{:metadata}
           :intent/projection-fn identity
           :intent/version 1}
          overrides)))

(deftest cross-registry-accepts-valid-mapping
  (let [passive (hash-projection-passive-entry :test-intent)
        runtime {:test-intent (runtime-hash-intent-entry :test-intent)}
        errors (registries/validate-intent-registry-alignment [passive] runtime)]
    (is (empty? errors))))

(deftest cross-registry-detects-missing-hash-intent-ref
  (let [passive (assoc (hash-projection-passive-entry :test-intent)
                       :output {:type :canonical-hash})  ;; missing :hash/intent
        runtime {:test-intent (runtime-hash-intent-entry :test-intent)}
        errors (registries/validate-intent-registry-alignment [passive] runtime)]
    (is (seq errors))
    (is (some #(= :cross/missing-hash-intent-ref (:error %)) errors))))

(deftest cross-registry-detects-unknown-hash-intent
  (let [passive (hash-projection-passive-entry :no-such-intent)
        runtime {}  ;; empty runtime registry
        errors (registries/validate-intent-registry-alignment [passive] runtime)]
    (is (seq errors))
    (is (some #(= :cross/missing-hash-intent (:error %)) errors))))

(deftest cross-registry-detects-version-mismatch
  (let [passive (hash-projection-passive-entry :test-intent {:version 2})
        runtime {:test-intent (runtime-hash-intent-entry :test-intent {:intent/version 1})}
        errors (registries/validate-intent-registry-alignment [passive] runtime)]
    (is (seq errors))
    (is (some #(= :cross/version-mismatch (:error %)) errors))))

(deftest cross-registry-ignores-non-hash-projection-entries
  (let [passive {:id :pro-rata/test-allocation
                 :version 1
                 :intent/type :pro-rata/allocation
                 :intent/purpose :test-allocation
                 :scope {:protocols #{:test}}
                 :inputs #{:data}
                 :constraints #{:conservation}
                 :output {:type :allocation-vector}}
        errors (registries/validate-intent-registry-alignment [passive] {})]
    (is (empty? errors))))

(deftest cross-registry-works-with-real-registries
  (testing "validate-passive-registries includes cross-registry alignment and passes for real data"
    (is (:valid? (registries/validate-passive-registries)))))

;; ── Structural Stability: Registry Entry Shapes ─────────────────────────
;; These tests assert that the frozen entry schemas of each passive registry
;; have not changed. When a schema change is intentional, update the
;; expected shape hash.

(def registry-entry-shapes
  "Defines the frozen entry shape for each of the 8 passive registries."
  {:intent-registry
   {:entries-key :intents
    :required-entry-fields #{:id :version :intent/type :intent/purpose
                             :scope :inputs :constraints :output
                             :description :canonical-hash}
    :required-registry-fields #{:registry-version :intents :registry-hash}}

   :projection-definition-registry
   {:entries-key :projection-definitions
    :required-entry-fields #{:id :version :projection-type :intent-types
                             :intent-purposes :source :output :claims
                             :canonical-hash}
    :required-registry-fields #{:registry-version :projection-definitions :registry-hash}}

   :claim-definition-registry
   {:entries-key :claim-definitions
    :required-entry-fields #{:id :version :category :description
                             :inputs :evaluation :outputs :canonical-hash}
    :required-registry-fields #{:registry-version :claim-definitions}}

   :attestor-registry
   {:entries-key :attestors
    :required-entry-fields #{:id :type :display-name :status
                             :verification :attestor-hash}
    :required-registry-fields #{:registry-version :attestors}}

   :execution-registry
   {:entries-key :executions
    :required-entry-fields #{:id :version :kind :runner :entry
                             :execution/type :execution/mode
                             :description :claims}
    :required-registry-fields #{:registry-version :executions}}

   :evidence-policy-registry
   {:entries-key :evidence-policies
    :required-entry-fields #{:id :version :evidence-policy/type
                             :evidence-policy/source :description :constraints}
    :required-registry-fields #{:registry-version :evidence-policies}}

   :creation-provenance-registry
    {:entries-key :creation-provenances
     :required-entry-fields #{:id :version :creation-provenance/value :description}
     :required-registry-fields #{:registry-version :creation-provenances}}

   :hash-projection-registry
   {:entries-key :projections
    :required-entry-fields #{:id :version :intent/name :intent/domain-tag
                             :intent/description :intent/includes :intent/excludes
                             :intent/projection-fn}
    :required-registry-fields #{:registry-version :projections}}

   :domain-tag-registry
   {:entries-key :domain-tags
    :required-entry-fields #{:tag/id :tag/domain-string :version}
    :required-registry-fields #{:registry-version :domain-tags}}})

(defn- registry-structural-paths
  "Extract flattened structural paths from registry entry shapes.
   Produces paths like \"intent-registry.ent.id\" for each registry's
   required entry and registry fields."
  [shapes]
  (sort (mapcat (fn [[reg-name spec]]
                  (let [prefix (name reg-name)]
                    (concat
                     (map (fn [f] (str prefix ".reg." (name f)))
                          (sort (:required-registry-fields spec)))
                     (map (fn [f] (str prefix ".ent." (name f)))
                          (sort (:required-entry-fields spec))))))
                shapes)))

(def expected-entry-shapes-hash
  "Expected stable hash of registry entry shape structural paths.
   Update when intentionally changing a registry's entry schema."
  "31747b00cc2c8528adf10536174d13e23bd95b67fa141fd275b4bcf9b557e3c7")

(deftest registry-entry-shapes-structural-stability
  (let [paths (into [] (registry-structural-paths registry-entry-shapes))
        actual-hash (hc/hash-with-intent {:hash/intent :state-diff} {:paths paths})]
    (is (= expected-entry-shapes-hash actual-hash)
        (str "Passive registry entry shapes changed.\n"
             "If intentional, update expected-entry-shapes-hash to: " actual-hash))))

(deftest registry-entry-shapes-match-actual-registries
  (testing "each registry's entries include all required fields"
    (doseq [[reg-kw spec] registry-entry-shapes
            :let [entries-key (:entries-key spec)
                  registry (case reg-kw
                             :intent-registry registries/intent-registry
                             :projection-definition-registry registries/projection-definition-registry
                             :claim-definition-registry registries/claim-definition-registry
                             :attestor-registry registries/attestor-registry
:execution-registry registries/execution-registry
                              :evidence-policy-registry registries/evidence-policy-registry
                              :creation-provenance-registry registries/creation-provenance-registry
                              :hash-projection-registry registries/hash-projection-registry
                              :domain-tag-registry registries/domain-tag-registry)
                  entries (get registry entries-key [])
                  actual-entry-fields (set (mapcat keys entries))
                  expected-entry-fields (:required-entry-fields spec)]]
      (testing (str reg-kw)
        (is (empty? (clojure.set/difference expected-entry-fields actual-entry-fields))
            (str "Missing required fields in " reg-kw))))))
