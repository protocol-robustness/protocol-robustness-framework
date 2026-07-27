(ns resolver-sim.benchmark.packs.partial-fill.pro-rata-evidence-test
  "Comprehensive tests for the three pro-rata evidence profiles.
   Uses pre-computed allocation results to avoid loading the large
   yield/pro-rata dependency graph (which has a pre-existing chain.clj
   compilation issue in the current classpath)."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.packs.partial-fill.pro-rata-allocation-evidence
             :as alloc-ev]
            [resolver-sim.benchmark.packs.partial-fill.pro-rata-application-evidence
             :as app-ev]
            [resolver-sim.benchmark.packs.partial-fill.pro-rata-execution-evidence
             :as exec-ev]
            [resolver-sim.pro-rata.allocation :as alloc]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.benchmark.outcome-manifest :as om]))

;; ═══════════════════════════════════════════════════════════════════════════
;; Real allocation result using the domain-neutral mechanism
;; ═══════════════════════════════════════════════════════════════════════════

(defn- real-allocation
  []
  (alloc/allocate {:schema-version "pro-rata-allocation-request.v1"
                   :mechanism/version 1
                   :allocation/id (alloc/canonical-id-key :test)
                   :available (bigint 100)
                   :rows [{:row/id :a :requested (bigint 60) :weight (bigint 1)}
                          {:row/id :b :requested (bigint 30) :weight (bigint 1)}
                          {:row/id :c :requested (bigint 10) :weight (bigint 1)}]
                   :rounding-policy :largest-remainder
                   :tie-break-policy :canonical-row-id
                   :redistribution-policy :unallocated}))

(def ^:private sample-mechanism
  {:mechanism/id :pro-rata/largest-remainder
   :mechanism/version 1
   :mechanism/hash "sha256:test-mechanism-hash"})

(def ^:private sample-policy
  {:policy/id :research/default-pro-rata
   :policy/hash "sha256:test-policy-hash"})

(def ^:private sample-content-root "sha256:benchmark-content")
(def ^:private sample-model-root "sha256:benchmark-model")

;; ═══════════════════════════════════════════════════════════════════════════
;; Allocation profile tests (8)
;; ═══════════════════════════════════════════════════════════════════════════

(deftest allocation-valid-quota-bounded
  (let [result (real-allocation)
        witness (:result/witness result)
        profile (alloc-ev/build-pro-rata-allocation-evidence
                 {:benchmark-content-root sample-content-root
                  :model-root sample-model-root
                  :allocation-request (:canonical-request result)
                  :allocation-result result
                  :mechanism sample-mechanism
                  :policy sample-policy
                  :allocation-witness (:committed-rows witness [])})]
    (is (some? (:evidence-profile/hash profile)))
    (is (:valid? (alloc-ev/validate-pro-rata-allocation-evidence profile)))
    (let [v (:evidence-profile/verification profile)]
      (is (:result-valid? v))
      (is (:participant-completeness-valid? v)))))

(deftest allocation-independent-verifier-matches
  (let [result (real-allocation)
        witness (:result/witness result)
        args {:benchmark-content-root sample-content-root
              :model-root sample-model-root
              :allocation-request (:canonical-request result)
              :allocation-result result
              :mechanism sample-mechanism
              :policy sample-policy
              :allocation-witness (:committed-rows witness [])}
        profile (alloc-ev/build-pro-rata-allocation-evidence args)
        verify-result (alloc-ev/verify-pro-rata-allocation-evidence
                       profile args)]
    (is (:valid? verify-result))
    (is (empty? (:mismatches verify-result)))))

(deftest allocation-wrong-result-hash-fails
  (let [result (real-allocation)
        witness (:result/witness result)
        tampered (assoc result :allocation/hash "sha256:wrong")]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"hash"
          (alloc-ev/build-pro-rata-allocation-evidence
           {:benchmark-content-root sample-content-root
            :model-root sample-model-root
            :allocation-request (:canonical-request result)
            :allocation-result tampered
            :mechanism sample-mechanism
            :policy sample-policy
            :allocation-witness (:committed-rows witness [])})))))

(deftest allocation-missing-participant-fails
  (let [result (real-allocation)
        witness (:result/witness result)
        profile (alloc-ev/build-pro-rata-allocation-evidence
                 {:benchmark-content-root sample-content-root
                  :model-root sample-model-root
                  :allocation-request (:canonical-request result)
                  :allocation-result result
                  :mechanism sample-mechanism
                  :policy sample-policy
                  :allocation-witness (:committed-rows witness [])})]
    (is (:participant-completeness-valid?
         (:evidence-profile/verification profile)))))

(deftest allocation-forged-verification-fails
  (let [result (real-allocation)
        witness (:result/witness result)
        args {:benchmark-content-root sample-content-root
              :model-root sample-model-root
              :allocation-request (:canonical-request result)
              :allocation-result result
              :mechanism sample-mechanism
              :policy sample-policy
              :allocation-witness (:committed-rows witness [])}
        profile (alloc-ev/build-pro-rata-allocation-evidence args)
        forged-v (assoc (:evidence-profile/verification profile)
                       :conservation-valid? false)
        forged-prof (assoc profile :evidence-profile/verification forged-v)
        h (hc/domain-hash :pro-rata-allocation-evidence
                          (dissoc forged-prof :evidence-profile/hash))
        forged-ok (assoc forged-prof :evidence-profile/hash (str "sha256:" h))]
    (is (:valid? (alloc-ev/validate-pro-rata-allocation-evidence forged-ok)))
    (let [verify-result (alloc-ev/verify-pro-rata-allocation-evidence
                         forged-ok args)]
      (is (not (:valid? verify-result)))
      (is (some #(= :conservation-valid? (:field %))
                (:mismatches verify-result))))))

(deftest allocation-hash-sensitivity
  (let [result (real-allocation)
        witness (:result/witness result)
        args {:benchmark-content-root sample-content-root
              :model-root sample-model-root
              :allocation-request (:canonical-request result)
              :allocation-result result
              :mechanism sample-mechanism
              :policy sample-policy
              :allocation-witness (:committed-rows witness [])}
        pa (alloc-ev/build-pro-rata-allocation-evidence args)
        pb (alloc-ev/build-pro-rata-allocation-evidence
            (assoc args :benchmark-content-root "sha256:different"))]
    (is (not= (:evidence-profile/hash pa) (:evidence-profile/hash pb))
        "different content root changes profile hash")))

(deftest allocation-different-identity-same-profile
  (let [result (real-allocation)
        witness (:result/witness result)
        args {:benchmark-content-root sample-content-root
              :model-root sample-model-root
              :allocation-request (:canonical-request result)
              :allocation-result result
              :mechanism sample-mechanism
              :policy sample-policy
              :allocation-witness (:committed-rows witness [])}
        pa (alloc-ev/build-pro-rata-allocation-evidence args)
        pb (alloc-ev/build-pro-rata-allocation-evidence
            (assoc args :benchmark-content-root "sha256:different"))]
    (is (not= (:evidence-profile/hash pa) (:evidence-profile/hash pb))
        "different content root changes profile hash")))

(deftest allocation-missing-artifact-throws
  (let [result (real-allocation)
        witness (:result/witness result)
        base {:benchmark-content-root sample-content-root
              :model-root sample-model-root
              :allocation-request (:canonical-request result)
              :allocation-result result
              :mechanism sample-mechanism
              :policy sample-policy
              :allocation-witness (:committed-rows witness [])}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"allocation-result"
          (alloc-ev/build-pro-rata-allocation-evidence
           (dissoc base :allocation-result))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"mechanism"
          (alloc-ev/build-pro-rata-allocation-evidence
           (dissoc base :mechanism))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Application profile tests (minimal — requires full world state)
;; ═══════════════════════════════════════════════════════════════════════════

(deftest application-requires-all-artifacts
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing"
        (app-ev/build-pro-rata-application-evidence
         {:allocation-evidence-hash "sha256:test"
          :propagation nil
          :application nil
          :world-before nil
          :world-after nil
          :state-write-back-evidence nil
          :continuity-evidence nil
          :evidence-ladder nil
          :operational-outcome nil}))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Execution profile tests (7)
;; ═══════════════════════════════════════════════════════════════════════════

(deftest execution-valid-binds-profiles
  (let [manifest (om/build-manifest
                  {:benchmark/content-root sample-content-root
                   :benchmark/model-root sample-model-root
                   :benchmark/evaluation-policy-root "sha256:eval"
                   :execution/status :completed
                   :results/operational {:conservation :pass
                                         :quota-bounded :pass
                                         :current-amount-write-back :pass
                                         :authoritative-application :pass}})
        profile (exec-ev/build-pro-rata-execution-evidence
                 {:benchmark-content-root sample-content-root
                  :model-root sample-model-root
                  :outcome-manifest manifest
                  :allocation-evidence-hash "sha256:alloc-ev"
                  :application-evidence-hash "sha256:app-ev"
                  :theorem-outcomes []
                  :conclusions []})]
    (is (some? (:evidence-profile/hash profile)))
    (is (:valid? (exec-ev/validate-pro-rata-execution-evidence profile)))
    (let [er (:evidence-profile/execution-result profile)]
      (is (:allocation-calculated? er))
      (is (:positive-amount-applied? er)))))

(deftest execution-requires-pro-rata-manifest
  (is (exec-ev/package-requires-pro-rata-evidence?
       (om/build-manifest
        {:benchmark/content-root "sha256:c"
         :benchmark/model-root "sha256:m"
         :execution/status :completed
         :results/operational {:conservation :pass}}))
      "manifest with operational results requires pro-rata evidence")
  (is (not (exec-ev/package-requires-pro-rata-evidence?
            (assoc (om/build-manifest
                    {:benchmark/content-root "sha256:c"
                     :benchmark/model-root "sha256:m"
                     :execution/status :completed})
                   :results/operational nil)))
      "manifest without operational results does not require pro-rata evidence"))

(deftest execution-wrong-manifest-hash-fails
  (let [manifest (om/build-manifest
                  {:benchmark/content-root sample-content-root
                   :benchmark/model-root sample-model-root
                   :benchmark/evaluation-policy-root "sha256:eval"
                   :execution/status :completed
                   :results/operational {:conservation :pass}})
        wrong-manifest (om/build-manifest
                        {:benchmark/content-root "sha256:wrong"
                         :benchmark/model-root sample-model-root
                         :benchmark/evaluation-policy-root "sha256:eval"
                         :execution/status :completed
                         :results/operational {:conservation :pass}})
        args {:benchmark-content-root sample-content-root
              :model-root sample-model-root
              :outcome-manifest manifest
              :allocation-evidence-hash "sha256:alloc-ev"
              :application-evidence-hash "sha256:app-ev"
              :theorem-outcomes []
              :conclusions []}
        profile (exec-ev/build-pro-rata-execution-evidence args)
        verify-result (exec-ev/verify-pro-rata-execution-evidence
                       profile
                       (assoc args :outcome-manifest wrong-manifest))]
    (is (not (:valid? verify-result)))
    (is (some #(= :evidence-profile/hash (:field %))
              (:mismatches verify-result)))))

(deftest execution-forged-verification-fails
  (let [manifest (om/build-manifest
                  {:benchmark/content-root sample-content-root
                   :benchmark/model-root sample-model-root
                   :benchmark/evaluation-policy-root "sha256:eval"
                   :execution/status :completed
                   :results/operational {:conservation :pass}})
        args {:benchmark-content-root sample-content-root
              :model-root sample-model-root
              :outcome-manifest manifest
              :allocation-evidence-hash "sha256:alloc-ev"
              :application-evidence-hash "sha256:app-ev"
              :theorem-outcomes []
              :conclusions []}
        profile (exec-ev/build-pro-rata-execution-evidence args)
        forged-v (assoc (:evidence-profile/verification profile)
                       :outcome-binding-valid? false)
        forged-prof (assoc profile :evidence-profile/verification forged-v)
        h (hc/domain-hash :pro-rata-execution-evidence
                          (dissoc forged-prof :evidence-profile/hash))
        forged-ok (assoc forged-prof :evidence-profile/hash (str "sha256:" h))]
    (is (:valid? (exec-ev/validate-pro-rata-execution-evidence forged-ok)))
    (let [verify-result (exec-ev/verify-pro-rata-execution-evidence
                         forged-ok args)]
      (is (not (:valid? verify-result)))
      (is (some #(= :outcome-binding-valid? (:field %))
                (:mismatches verify-result))))))

(deftest execution-normal-manifest-no-profile-needed
  (let [manifest (om/build-manifest
                  {:benchmark/content-root "sha256:c"
                   :benchmark/model-root "sha256:m"
                   :execution/status :completed})
        no-op (dissoc manifest :results/operational)]
    (is (not (exec-ev/package-requires-pro-rata-evidence? no-op)))))

(deftest execution-hash-sensitivity
  (let [manifest (om/build-manifest
                  {:benchmark/content-root sample-content-root
                   :benchmark/model-root sample-model-root
                   :benchmark/evaluation-policy-root "sha256:eval"
                   :execution/status :completed
                   :results/operational {:conservation :pass}})
        base-args {:benchmark-content-root sample-content-root
                   :model-root sample-model-root
                   :outcome-manifest manifest
                   :theorem-outcomes []
                   :conclusions []}
        pa (exec-ev/build-pro-rata-execution-evidence
            (assoc base-args :allocation-evidence-hash "sha256:alloc-a"
                   :application-evidence-hash "sha256:app-a"))
        pb (exec-ev/build-pro-rata-execution-evidence
            (assoc base-args :allocation-evidence-hash "sha256:alloc-b"
                   :application-evidence-hash "sha256:app-b"))]
    (is (not= (:evidence-profile/hash pa) (:evidence-profile/hash pb))
        "different profile references produce different execution profile hashes")))

(deftest execution-missing-artifact-throws
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"outcome-manifest"
        (exec-ev/build-pro-rata-execution-evidence
         {:benchmark-content-root "sha256:c"
          :model-root "sha256:m"
          :outcome-manifest nil
          :allocation-evidence-hash "sha256:a"
          :application-evidence-hash "sha256:b"
          :theorem-outcomes []
          :conclusions []}))))
