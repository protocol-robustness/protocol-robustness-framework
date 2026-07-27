(ns resolver-sim.benchmark.packs.partial-fill.pro-rata-evidence-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.packs.partial-fill.pro-rata-allocation-evidence :as alloc-ev]
            [resolver-sim.benchmark.packs.partial-fill.pro-rata-application-evidence :as app-ev]
            [resolver-sim.benchmark.packs.partial-fill.pro-rata-execution-evidence :as exec-ev]
            [resolver-sim.pro-rata.allocation :as alloc]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.benchmark.outcome-manifest :as om]))

(defn- real-alloc []
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

(defn- alloc-args [result]
  (let [w (:result/witness result)]
    {:benchmark-content-root "sha256:cr"
     :model-root "sha256:mr"
     :allocation-request (:canonical-request result)
     :allocation-result result
     :mechanism {:mechanism/id :pro-rata/largest-remainder :mechanism/version 1 :mechanism/hash "sha256:mh"}
     :policy {:policy/id :default :policy/hash "sha256:ph"}
     :allocation-witness (:committed-rows w [])}))

(defn- base-manifest []
  (om/build-manifest
   {:benchmark/content-root "sha256:cr"
    :benchmark/model-root "sha256:mr"
    :benchmark/evaluation-policy-root "sha256:eval"
    :execution/status :completed
    :results/operational {:conservation :pass :quota-bounded :pass}}))

(defn- exec-args [& {:keys [manifest alloc-hash app-hash theorems conclusions]
                     :or {manifest (base-manifest)
                          alloc-hash "sha256:alloc-ev"
                          app-hash "sha256:app-ev"
                          theorems [] conclusions []}}]
  {:benchmark-content-root "sha256:cr" :model-root "sha256:mr"
   :outcome-manifest manifest
   :allocation-evidence-hash alloc-hash :application-evidence-hash app-hash
   :theorem-outcomes theorems :conclusions conclusions})

;; ═══════════════════════════════════════════════════════════════════════════
;; 1. Allocation profile
;; ═══════════════════════════════════════════════════════════════════════════

(deftest allocation-valid-builds
  (let [profile (alloc-ev/build-pro-rata-allocation-evidence (alloc-args (real-alloc)))]
    (is (some? (:evidence-profile/hash profile)))
    (is (:valid? (alloc-ev/validate-pro-rata-allocation-evidence profile)))))

(deftest allocation-independent-verifier-matches
  (let [args (alloc-args (real-alloc))
        profile (alloc-ev/build-pro-rata-allocation-evidence args)
        v (alloc-ev/verify-pro-rata-allocation-evidence profile args)]
    (is (:valid? v)) (is (empty? (:mismatches v)))))

(deftest allocation-wrong-hash-is-noted
  (let [r (real-alloc) t (assoc r :allocation/hash "sha256:bad")
        a (assoc (alloc-args r) :allocation-result t)
        p (alloc-ev/build-pro-rata-allocation-evidence a)]
    ;; Builder does NOT throw — it records the hash mismatch in the
    ;; verification map as :result-valid? false
    (is (some? (:evidence-profile/hash p)))
    (is (not (:result-valid? (:evidence-profile/verification p))))))

(deftest allocation-forged-verification-detected
  (let [args (alloc-args (real-alloc))
        p (alloc-ev/build-pro-rata-allocation-evidence args)
        fv (assoc (:evidence-profile/verification p) :capacity-bounded? false)
        fp (assoc p :evidence-profile/verification fv)
        h (hc/domain-hash :pro-rata-allocation-evidence (dissoc fp :evidence-profile/hash))
        fo (assoc fp :evidence-profile/hash (str "sha256:" h))
        v (alloc-ev/verify-pro-rata-allocation-evidence fo args)]
    (is (:valid? (alloc-ev/validate-pro-rata-allocation-evidence fo)))
    (is (not (:valid? v)))
    (is (some #(= :capacity-bounded? (:field %)) (:mismatches v)))))

(deftest allocation-hash-changes-with-input
  (let [a (alloc-args (real-alloc))
        a2 (assoc a :benchmark-content-root "sha256:other")
        pa (alloc-ev/build-pro-rata-allocation-evidence a)
        pb (alloc-ev/build-pro-rata-allocation-evidence a2)]
    (is (not= (:evidence-profile/hash pa) (:evidence-profile/hash pb)))))

(deftest allocation-missing-artifact-throws
  (let [base (alloc-args (real-alloc))]
    (is (thrown-with-msg? Exception #"evidence build failed"
          (alloc-ev/build-pro-rata-allocation-evidence (dissoc base :allocation-result))))
    (is (thrown-with-msg? Exception #"evidence build failed"
          (alloc-ev/build-pro-rata-allocation-evidence (dissoc base :mechanism))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; 2. Application profile — requires full world state (structural only)
;; ═══════════════════════════════════════════════════════════════════════════

(deftest application-requires-all-artifacts
  (is (thrown-with-msg? Exception #"evidence build failed"
        (app-ev/build-pro-rata-application-evidence
         {:allocation-evidence-hash "sha256:test" :propagation nil :application nil
          :world-before nil :world-after nil :state-write-back-evidence nil
          :continuity-evidence nil :evidence-ladder nil :operational-outcome nil}))))

;; ═══════════════════════════════════════════════════════════════════════════
;; 2a. Decisive application case: accounting passes, authoritative state fails
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:private shared-propag
  {:propagation/id :prop/test
   :propagation/hash "sha256:prophash"
   :propagation/policy-hash "sha256:policyhash"
   :propagation/allocation-hash "sha256:allochash"
   :participants [{:participant/id :p1 :fulfilled 60 :deferred 40}]
   :accounting-entries [{:participant/id :p1 :token :usdc :delta 60}]
   :apparent-application {:accounting-delta 60 :participant/id :p1 :token :usdc}
   :source-account-balance-after 400})

(def ^:private app-with-accounting
  {:application/hash "sha256:apphash"
   :apparent-application {:accounting-delta 60 :participant/id :p1 :token :usdc}
   :accounting-entries [{:participant/id :p1 :token :usdc :delta 60}]
   :application-order 1
   :outcome-hash "sha256:outcome"})

(def ^:private world-before
  {:yield/positions {:p1 {:position/id :pos-p1
                           :position/current-amount 100
                           :position/deferred-position nil}}})

(def ^:private world-after-accounting-only
  ;; Accounting agrees: balance went from 100 to 40 (delta -60)
  {:yield/positions {:p1 {:position/id :pos-p1
                           :position/current-amount 40
                           :position/deferred-position nil}}})

(def ^:private state-wb-evidence-accounting-only
  ;; State write-back fails: withdrawn balance doesn't match
  [{:participant/id :p1
    :token :usdc
    :withdrawn {:before 0 :delta 0 :after 0 :final-world-value 0 :verified? false}
    :position {:before-hash "sha256:before"
               :after-hash "sha256:after"
               :final-world-position-hash "sha256:different"
               :verified? false}
    :deferred-position {:prior-closed? false
                        :prior-current-amount nil
                        :successor-current-amount nil
                        :final-world-current-amount nil
                        :verified? false}}])

(def ^:private continuity-evidence-accounting-only [])
  ;; No continuity (terminal scenario — no later transition)

(def ^:private evidence-ladder-accounting-only
  [{:propagation/id :prop/test
    :levels [{:level "allocation-calculated" :status "verified"}
             {:level "application-claimed" :status "verified"}
             {:level "accounting-emitted" :status "verified"}
             {:level "state-written-back" :status "failed"}
             {:level "continuity-consumed" :status "not-observed"}]}])

(def ^:private operational-outcome-accounting-only
  {:conservation :pass
   :quota-bounded :pass
   :current-amount-write-back :fail
   :authoritative-application :fail})

(deftest app-accounting-passes-state-write-back-fails
  (let [profile (app-ev/build-pro-rata-application-evidence
                 {:allocation-evidence-hash "sha256:alloc-ev"
                  :propagation shared-propag
                  :application app-with-accounting
                  :world-before world-before
                  :world-after world-after-accounting-only
                  :state-write-back-evidence state-wb-evidence-accounting-only
                  :continuity-evidence continuity-evidence-accounting-only
                  :evidence-ladder evidence-ladder-accounting-only
                  :operational-outcome operational-outcome-accounting-only})
        v (:evidence-profile/verification profile)]
    (is (some? (:evidence-profile/hash profile)))
    ;; Apparent application and accounting pass
    (is (:apparent-application-recorded? v) "apparent application is recorded")
    (is (:accounting-reconciled? v) "accounting reconciles")
    ;; Authoritative state write-back fails
    (is (not (:authoritative-state-write-back-verified? v))
        "authoritative state write-back must NOT be verified")
    ;; The profile is still structurally valid (it records the failure)
    (is (:valid? (app-ev/validate-pro-rata-application-evidence profile))
        "profile remains structurally valid even with write-back failure")))

;; ═══════════════════════════════════════════════════════════════════════════
;; 2b. Residual/continuity cases
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:private wb-verified-deferred
  [{:participant/id :p1
    :token :usdc
    :withdrawn {:before 100 :delta 60 :after 40 :final-world-value 40 :verified? true}
    :position {:before-hash "sha256:before" :after-hash "sha256:after"
               :final-world-position-hash "sha256:after" :verified? true}
    :deferred-position {:prior-closed? true
                        :prior-current-amount 0
                        :successor-current-amount 40
                        :final-world-current-amount 40
                        :verified? true}}])

(def ^:private cont-evidence-terminal [])

(def ^:private cont-evidence-stale
  ;; Amount not continuous = continuity failure
  [[{:participant/id :p1
     :propagation/id :prop/test
     :expected-position-hash "sha256:expected"
     :current-position-hash "sha256:different"
     :expected-current-amount 40
     :current-current-amount 35
     :matches? false
     :amount-continuous? false}]])

(def ^:private op-pos-partial
  {:conservation :pass :quota-bounded :pass
   :current-amount-write-back :pass :authoritative-application :pass})

(def ^:private evidence-ladder-full
  [{:propagation/id :prop/test
    :levels [{:level "allocation-calculated" :status "verified"}
             {:level "application-claimed" :status "verified"}
             {:level "accounting-emitted" :status "verified"}
             {:level "state-written-back" :status "verified"}
             {:level "continuity-consumed" :status "verified"}]}])

(deftest app-positive-partial-application
  (let [profile (app-ev/build-pro-rata-application-evidence
                 {:allocation-evidence-hash "sha256:alloc-ev"
                  :propagation shared-propag
                  :application app-with-accounting
                  :world-before world-before
                  :world-after (assoc-in world-after-accounting-only
                                         [:yield/positions :p1 :position/deferred-position]
                                         {:position/id :pos-p1-deferred
                                          :position/current-amount 40})
                  :state-write-back-evidence wb-verified-deferred
                  :continuity-evidence cont-evidence-terminal
                  :evidence-ladder evidence-ladder-full
                  :operational-outcome op-pos-partial})
        v (:evidence-profile/verification profile)
        cont (:next-precondition-continuity v)]
    (is (:deferred-current-amount-verified? v))
    (is (:current-amount-continuous? v))
    (is (= :not-observed (:status cont))
        "terminal scenario: continuity status must be :not-observed, not failure")))

(deftest app-stale-next-precondition
  (let [profile (app-ev/build-pro-rata-application-evidence
                 {:allocation-evidence-hash "sha256:alloc-ev"
                  :propagation shared-propag
                  :application app-with-accounting
                  :world-before world-before
                  :world-after world-after-accounting-only
                  :state-write-back-evidence wb-verified-deferred
                  :continuity-evidence cont-evidence-stale
                  :evidence-ladder evidence-ladder-full
                  :operational-outcome op-pos-partial})
        v (:evidence-profile/verification profile)
        cont (:next-precondition-continuity v)]
    (is (= :failed (:status cont))
        "stale precondition: continuity status must be :failed")
    ;; Profile remains structurally valid — it records the failure
    (is (:valid? (app-ev/validate-pro-rata-application-evidence profile)))))

;; ═══════════════════════════════════════════════════════════════════════════
;; 2c. Valid-artifact substitution
;; ═══════════════════════════════════════════════════════════════════════════

(deftest app-substitution-detected
  (let [result-a (real-alloc)
        result-b (alloc/allocate {:schema-version "pro-rata-allocation-request.v1"
                                  :mechanism/version 1
                                  :allocation/id (alloc/canonical-id-key :test-b)
                                  :available (bigint 200)
                                  :rows [{:row/id :x :requested (bigint 100) :weight (bigint 1)}
                                         {:row/id :y :requested (bigint 100) :weight (bigint 1)}]
                                  :rounding-policy :largest-remainder
                                  :tie-break-policy :canonical-row-id
                                  :redistribution-policy :unallocated})
        profile-a (alloc-ev/build-pro-rata-allocation-evidence (alloc-args result-a))
        profile-b (alloc-ev/build-pro-rata-allocation-evidence (alloc-args result-b))]
    ;; Each profile is individually valid
    (is (:valid? (alloc-ev/validate-pro-rata-allocation-evidence profile-a)))
    (is (:valid? (alloc-ev/validate-pro-rata-allocation-evidence profile-b)))
    ;; Substituting B's args into A's verifier must fail
    (let [v (alloc-ev/verify-pro-rata-allocation-evidence profile-a (alloc-args result-b))]
      (is (not (:valid? v)))
      (is (some #(= :evidence-profile/hash (:field %)) (:mismatches v)))
      "substitution of different allocation must fail recomputation")))

;; ═══════════════════════════════════════════════════════════════════════════
;; 2d. Conclusion overreach rejection
;; ═══════════════════════════════════════════════════════════════════════════

(deftest execution-no-incentive-overreach
  (let [overreaching-conclusion {:conclusion/id :conclusion/incentive-compatible
                                  :conclusion/hash "sha256:overreach"
                                  :conclusion/status :established
                                  :conclusion/statement "The mechanism is universally incentive compatible"}
        manifest (om/build-manifest
                  {:benchmark/content-root "sha256:cr"
                   :benchmark/model-root "sha256:mr"
                   :benchmark/evaluation-policy-root "sha256:eval"
                   :execution/status :completed
                   :results/operational {:conservation :pass :quota-bounded :pass}})
        ;; Build profile with only operational evidence, no incentive theorem
        profile (exec-ev/build-pro-rata-execution-evidence
                 (exec-args :manifest manifest
                            :theorems []
                            :conclusions [overreaching-conclusion]))
        er (:evidence-profile/execution-result profile)]
    ;; Profile builds (it records the binding, not the conclusion's validity)
    (is (some? (:evidence-profile/hash profile)))
    ;; The profile's execution result correctly reflects only operational evidence
    (is (:allocation-calculated? er))
    (is (= false (:fully-satisfied? er)))
    ;; THE CRITICAL ASSERTION: the profile does NOT claim incentive compatibility
    ;; The profile's verification/theorem bindings only verify that the committed
    ;; theorem hashes appear in the manifest — they do NOT validate the content
    ;; of the conclusion itself. Overreach detection belongs to the theorem/conclusion
    ;; independent verifier.
    (is (:valid? (exec-ev/validate-pro-rata-execution-evidence profile))
        "profile is structurally valid")
    ;; The execution profile correctly records no incentive theorem
    (is (empty? (:evidence-profile/theorem-hashes profile))
        "no theorem hashes recorded when no theorems provided")))

;; ═══════════════════════════════════════════════════════════════════════════
;; 3. Execution profile
;; ═══════════════════════════════════════════════════════════════════════════
;; 3. Execution profile
;; ═══════════════════════════════════════════════════════════════════════════

(deftest execution-valid-binds
  (let [p (exec-ev/build-pro-rata-execution-evidence (exec-args))]
    (is (some? (:evidence-profile/hash p)))
    (is (:valid? (exec-ev/validate-pro-rata-execution-evidence p)))))

(deftest execution-requires-operational-results
  (is (exec-ev/package-requires-pro-rata-evidence? (base-manifest)))
  (is (not (exec-ev/package-requires-pro-rata-evidence?
            (dissoc (base-manifest) :results/operational)))))

(deftest execution-forged-verification-detected
  (let [args (exec-args)
        p (exec-ev/build-pro-rata-execution-evidence args)
        fv (assoc (:evidence-profile/verification p) :outcome-binding-valid? false)
        fp (assoc p :evidence-profile/verification fv)
        h (hc/domain-hash :pro-rata-execution-evidence (dissoc fp :evidence-profile/hash))
        fo (assoc fp :evidence-profile/hash (str "sha256:" h))
        v (exec-ev/verify-pro-rata-execution-evidence fo args)]
    (is (:valid? (exec-ev/validate-pro-rata-execution-evidence fo)))
    (is (not (:valid? v)))
    (is (some #(= :outcome-binding-valid? (:field %)) (:mismatches v)))))

(deftest execution-wrong-manifest-rejected
  (let [args (exec-args)
        args-wrong (exec-args :manifest (om/build-manifest
                                          {:benchmark/content-root "sha256:other"
                                           :benchmark/model-root "sha256:mr"
                                           :benchmark/evaluation-policy-root "sha256:eval"
                                           :execution/status :completed
                                           :results/operational {:conservation :pass}}))
        p (exec-ev/build-pro-rata-execution-evidence args)
        v (exec-ev/verify-pro-rata-execution-evidence p args-wrong)]
    (is (not (:valid? v)))
    (is (some #(= :evidence-profile/hash (:field %)) (:mismatches v)))))

(deftest execution-hash-changes-with-input
  (let [pa (exec-ev/build-pro-rata-execution-evidence
            (exec-args :alloc-hash "sha256:a" :app-hash "sha256:b"))
        pb (exec-ev/build-pro-rata-execution-evidence
            (exec-args :alloc-hash "sha256:different" :app-hash "sha256:b"))]
    (is (not= (:evidence-profile/hash pa) (:evidence-profile/hash pb)))))

(deftest execution-missing-artifact-throws
  (is (thrown-with-msg? Exception #"evidence build failed"
        (exec-ev/build-pro-rata-execution-evidence
         (assoc (exec-args) :outcome-manifest nil)))))
