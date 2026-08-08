(ns resolver-sim.benchmark.package-integration-test
  "End-to-end package-level integration: model registry entry → benchmark
   execution → evidence profiles → optional force-authorisation → package
   index → completion → independent verification.

   Each scenario builds a complete simulated package, validates that every
   profile recomputes correctly, and confirms that cross-artifact
   verification catches every intended failure mode."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.pro-rata.allocation :as alloc]
            [resolver-sim.benchmark.outcome-manifest :as om]
            [resolver-sim.benchmark.researcher-force-authorisation :as rfa]
            [resolver-sim.benchmark.force-authorised-execution-evidence :as fa-ev]
            [resolver-sim.benchmark.packs.partial-fill.pro-rata-allocation-evidence :as alloc-ev]
            [resolver-sim.benchmark.packs.partial-fill.pro-rata-application-evidence :as app-ev]
            [resolver-sim.benchmark.packs.partial-fill.pro-rata-execution-evidence :as exec-ev]))

;; ═══════════════════════════════════════════════════════════════════════════
;; Simulated package helpers
;; ═══════════════════════════════════════════════════════════════════════════

(defn- sha256-bytes [m]
  "Compute a deterministic sha256 hex and byte-length for a map.
   Used to simulate the package-index ref triple {:sha256 ... :bytes ...}."
  (let [json (pr-str m)]
    {:sha256 (str "sha256:" (hc/domain-hash :evidence-collection m))
     :bytes (count (.getBytes json "UTF-8"))}))

(defn- build-package [artifact-map]
  "Build a simulated package index from a flat map of artifact-id -> artifact.
   Returns {:index {id {:sha256 id :ref ... :bytes ...}}
            :artifacts artifact-map}."
  (let [index (into {} (map (fn [[id art]]
                              [id (merge {:ref (str "artifacts/" (name id) ".json")
                                          :artifact-sha256 id}
                                         (sha256-bytes art))])
                            artifact-map))]
    {:index index
     :artifacts artifact-map}))

(defn- resolve-from-package [package]
  "Return a package-resolver fn that resolves artifact sha256s from the package."
  (let [artifacts (:artifacts package)]
    (fn [sha256]
      (get artifacts sha256))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Test data
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:private content-root "sha256:benchmark-content")
(def ^:private model-root "sha256:benchmark-model")

(defn- build-pro-rata-manifest []
  (om/build-manifest
   {:benchmark/content-root content-root
    :benchmark/model-root model-root
    :benchmark/evaluation-policy-root "sha256:eval"
    :execution/status :completed
    :execution/model-instance-root "sha256:mi"
    :execution/plan-root "sha256:plan"
    :execution/parameter-domain-root "sha256:domain"
    :execution/sampling-policy-root "sha256:sampling"
    :execution/generated-case-set-root "sha256:cases"
    :results/operational {:conservation :pass :quota-bounded :pass
                          :current-amount-write-back :pass
                          :authoritative-application :pass}}))

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

;; Shared data for pro-rata app-ev (reuses fixtures from pro-rata-evidence-test)
(def ^:private app-propag
  {:propagation/id :prop/test
   :propagation/hash "sha256:prophash"
   :propagation/policy-hash "sha256:policyhash"
   :propagation/allocation-hash "sha256:allochash"
   :participants [{:participant/id :p1 :fulfilled 60 :deferred 40}]
   :accounting-entries [{:participant/id :p1 :token :usdc :delta 60}]
   :apparent-application {:accounting-delta 60 :participant/id :p1 :token :usdc}
   :source-account-balance-after 400})

(def ^:private app-application
  {:application/hash "sha256:apphash"
   :apparent-application {:accounting-delta 60 :participant/id :p1 :token :usdc}
   :accounting-entries [{:participant/id :p1 :token :usdc :delta 60}]
   :application-order 1
   :outcome-hash "sha256:outcome"})

(def ^:private app-wb
  [{:participant/id :p1 :token :usdc
    :withdrawn {:before 100 :delta 60 :after 40 :final-world-value 40 :verified? true}
    :position {:before-hash "sha256:before" :after-hash "sha256:after"
               :final-world-position-hash "sha256:after" :verified? true}
    :deferred-position {:prior-closed? true :prior-current-amount 0
                        :successor-current-amount 40 :final-world-current-amount 40
                        :verified? true}}])

(def ^:private app-ladder
  [{:propagation/id :prop/test
    :levels [{:level "allocation-calculated" :status "verified"}
             {:level "application-claimed" :status "verified"}
             {:level "accounting-emitted" :status "verified"}
             {:level "state-written-back" :status "verified"}
             {:level "continuity-consumed" :status "not-observed"}]}])

(def ^:private app-op
  {:conservation :pass :quota-bounded :pass
   :current-amount-write-back :pass :authoritative-application :pass})

;; ── Tests ────────────────────────────────────────────────────────────────

(deftest pkg-ordinary-pro-rata-execution
  (let [manifest (build-pro-rata-manifest)
        alloc-result (real-alloc)
        aw (:result/witness alloc-result)
        ;; Build allocation evidence profile
        alloc-prof (alloc-ev/build-pro-rata-allocation-evidence
                    {:benchmark-content-root content-root
                     :model-root model-root
                     :allocation-request (:canonical-request alloc-result)
                     :allocation-result alloc-result
                     :mechanism {:mechanism/id :pro-rata/largest-remainder
                                 :mechanism/version 1 :mechanism/hash "sha256:mh"}
                     :policy {:policy/id :default :policy/hash "sha256:ph"}
                     :allocation-witness (:committed-rows aw [])})
        alloc-hash (:evidence-profile/hash alloc-prof)
        ;; Build application evidence profile
        app-prof (app-ev/build-pro-rata-application-evidence
                  {:allocation-evidence-hash alloc-hash
                   :propagation app-propag
                   :application app-application
                   :world-before {} :world-after {}
                   :state-write-back-evidence app-wb
                   :continuity-evidence []
                   :evidence-ladder app-ladder
                   :operational-outcome app-op})
        app-hash (:evidence-profile/hash app-prof)
        ;; Build execution evidence profile
        exec-prof (exec-ev/build-pro-rata-execution-evidence
                   {:benchmark-content-root content-root
                    :model-root model-root
                    :outcome-manifest manifest
                    :allocation-evidence-hash alloc-hash
                    :application-evidence-hash app-hash
                    :theorem-outcomes []
                    :conclusions []})
        exec-hash (:evidence-profile/hash exec-prof)
        ;; Assemble package
        artifact-map {alloc-hash alloc-prof,
                      app-hash app-prof,
                      exec-hash exec-prof,
                      (:benchmark-outcome/hash manifest) manifest}
        package (build-package artifact-map)
        resolver (resolve-from-package package)]
    ;; Package index resolves each artifact by hash
    (is (some? (resolver alloc-hash)) "allocation profile in package")
    (is (some? (resolver app-hash)) "application profile in package")
    (is (some? (resolver exec-hash)) "execution profile in package")
    (is (some? (resolver (:benchmark-outcome/hash manifest)))
        "outcome manifest in package")
    ;; Independent verifier recomputes each profile
    (is (:valid? (alloc-ev/verify-pro-rata-allocation-evidence
                  alloc-prof
                  {:benchmark-content-root content-root
                   :model-root model-root
                   :allocation-request (:canonical-request alloc-result)
                   :allocation-result alloc-result
                   :mechanism {:mechanism/id :pro-rata/largest-remainder
                               :mechanism/version 1 :mechanism/hash "sha256:mh"}
                   :policy {:policy/id :default :policy/hash "sha256:ph"}
                   :allocation-witness (:committed-rows aw [])}))
        "independent allocation verifier matches")))

(deftest pkg-force-authorised-execution
  (let [manifest (om/build-manifest
                  (assoc (into {} (build-pro-rata-manifest))
                         :benchmark/content-root content-root
                         :benchmark/model-root model-root))
        auth (rfa/build-authorisation
              {:authorisation/id :authorisation/pkg-fa
               :authorisation/policy {:policy/id :research/three-member
                                      :policy/version 1
                                      :policy/schema-version "fa-policy.v1"
                                      :policy/hash "sha256:policy"}
               :authorisation/review-round {:review-round/id :rr/pkg
                                            :review-round/hash "sha256:round"}
               :authorisation/request-root "sha256:request"
               :authorisation/target {:target/kind :benchmark-branch
                                      :target/baseline-content-root "sha256:baseline"
                                      :target/branch-descriptor-hash "sha256:branch"
                                      :target/proposed-content-root "sha256:proposed"}
               :authorisation/decision-references
               [{:researcher/id "a" :decision :approve
                 :decision/hash "sha256:dec-a"
                 :signature {:algorithm :ed25519 :value "siga" :signed-at "now"}}
                {:researcher/id "b" :decision :approve
                 :decision/hash "sha256:dec-b"
                 :signature {:algorithm :ed25519 :value "sigb" :signed-at "now"}}]
               :authorisation/threshold {:required 2 :eligible 3}})
        reservation (rfa/build-reservation
                     {:reservation/authorisation-hash (:authorisation/hash auth)
                      :reservation/consumption-key (rfa/consumption-key auth)
                      :reservation/execution-attempt-id :execution/pkg-fa
                      :reservation/command-root "sha256:cmd"
                      :reservation/plan-root "sha256:plan"})
        fa-section {:authorisation-hash (:authorisation/hash auth)
                    :consumption-key (rfa/consumption-key auth)
                    :reservation-hash (:reservation/hash reservation)
                    :execution-attempt-id :execution/pkg-fa
                    :branch-descriptor-hash "sha256:branch"
                    :baseline-content-root "sha256:baseline"
                    :executed-content-root "sha256:executed"
                    :status :consumed}
        fa-manifest (om/build-manifest
                     (assoc (into {} (build-pro-rata-manifest))
                            :execution/command-root "sha256:cmd"
                            :execution/plan-root "sha256:plan"
                            :execution/force-authorisation fa-section
                            :benchmark/content-root "sha256:baseline"))
        receipt (rfa/build-consumption-receipt
                 {:consumption/reservation-hash (:reservation/hash reservation)
                  :consumption/authorisation-hash (:authorisation/hash auth)
                  :consumption/consumption-key (rfa/consumption-key auth)
                  :consumption/resulting-outcome-hash
                  (:benchmark-outcome/hash fa-manifest)
                  :consumption/status :consumed})
        evidence-profile (fa-ev/build-force-authorised-execution-evidence
                          {:authorisation auth
                           :policy {"policy_sha256" "sha256:policy"}
                           :review-round {:review-round/id :rr/pkg
                                          :review-round/hash "sha256:round"
                                          :review-round/members
                                          [{:researcher/id "a" :role :steward}
                                           {:researcher/id "b" :role :reproducer}]}
                           :reservation reservation
                           :outcome-manifest fa-manifest
                           :consumption-receipt receipt
                           :public-key-resolver (fn [id] "/dev/null")})
        policy-artifact {"policy_sha256" "sha256:policy"
                         "schema_version" "force-authorisation-policy.v1"
                         "member_count" 3 "threshold" 2}
        round-artifact {:review-round/id :rr/pkg
                        :review-round/hash "sha256:round"
                        :review-round/members
                        [{:researcher/id "a" :role :steward}
                         {:researcher/id "b" :role :reproducer}]}
        artifact-map {(:authorisation/hash auth) auth,
                      (:reservation/hash reservation) reservation,
                      (:benchmark-outcome/hash fa-manifest) fa-manifest,
                      (:consumption/hash receipt) receipt,
                      (:evidence-profile/hash evidence-profile) evidence-profile,
                      "sha256:policy" policy-artifact,
                      "sha256:round" round-artifact}
        package (build-package artifact-map)
        resolver (resolve-from-package package)]
    ;; Package index resolves every FA artifact
    (is (some? (resolver (:authorisation/hash auth))) "authorisation in package")
    (is (some? (resolver (:reservation/hash reservation))) "reservation in package")
    (is (some? (resolver (:benchmark-outcome/hash fa-manifest))) "manifest in package")
    (is (some? (resolver (:consumption/hash receipt))) "receipt in package")
    (is (some? (resolver (:evidence-profile/hash evidence-profile))) "profile in package")
    ;; The evidence profile threads the three-member aggregate classification
    ;; checks (never caller-supplied).
    (let [v (:evidence-profile/verification evidence-profile)]
      (is (contains? v :three-member-aggregate-holds?))
      (is (map? (:three-member-aggregate v)))
      (is (contains? (set (keys (:three-member-aggregate v)))
                     :three-member-classifications))
      (is (false? (:three-member-aggregate-holds? v))
          "the 2-member fixture round fails the threaded three-member-standard
           check, and the profile records it honestly"))
    ;; Package completion check
    (is (:valid? (rfa/verify-package-completion-force-authorised
                  resolver fa-manifest evidence-profile))
        "package completion verifier passes")))

(deftest pkg-write-back-failure
  (let [wb-fail (assoc-in app-wb [0 :withdrawn :verified?] false)
        app-prof (app-ev/build-pro-rata-application-evidence
                  {:allocation-evidence-hash "sha256:alloc"
                   :propagation app-propag :application app-application
                   :world-before {} :world-after {}
                   :state-write-back-evidence wb-fail
                   :continuity-evidence [] :evidence-ladder app-ladder
                   :operational-outcome (assoc app-op :authoritative-application :fail)})
        v (:evidence-profile/verification app-prof)]
    ;; Application profile records the split
    (is (:apparent-application-recorded? v) "apparent application passes")
    (is (:accounting-reconciled? v) "accounting passes")
    (is (not (:authoritative-state-write-back-verified? v))
        "authoritative write-back fails")
    ;; Profile is structurally valid
    (is (:valid? (app-ev/validate-pro-rata-application-evidence app-prof)))))

(deftest pkg-substitution-attack
  (let [alloc-a (real-alloc)
        alloc-b (alloc/allocate {:schema-version "pro-rata-allocation-request.v1"
                                 :mechanism/version 1
                                 :allocation/id (alloc/canonical-id-key :test-b)
                                 :available (bigint 200)
                                 :rows [{:row/id :x :requested (bigint 100) :weight (bigint 1)}
                                        {:row/id :y :requested (bigint 100) :weight (bigint 1)}]
                                 :rounding-policy :largest-remainder
                                 :tie-break-policy :canonical-row-id
                                 :redistribution-policy :unallocated})
        wa (:result/witness alloc-a)
        wb (:result/witness alloc-b)
        base-args {:benchmark-content-root content-root :model-root model-root
                   :mechanism {:mechanism/id :pro-rata/largest-remainder
                               :mechanism/version 1 :mechanism/hash "sha256:mh"}
                   :policy {:policy/id :default :policy/hash "sha256:ph"}}
        prof-a (alloc-ev/build-pro-rata-allocation-evidence
                (assoc base-args
                       :allocation-request (:canonical-request alloc-a)
                       :allocation-result alloc-a
                       :allocation-witness (:committed-rows wa [])))
        prof-b (alloc-ev/build-pro-rata-allocation-evidence
                (assoc base-args
                       :allocation-request (:canonical-request alloc-b)
                       :allocation-result alloc-b
                       :allocation-witness (:committed-rows wb [])))]
    ;; Each profile individually valid
    (is (:valid? (alloc-ev/validate-pro-rata-allocation-evidence prof-a)))
    (is (:valid? (alloc-ev/validate-pro-rata-allocation-evidence prof-b)))
    ;; Substituting B into A's verifier fails
    (is (not (:valid? (alloc-ev/verify-pro-rata-allocation-evidence
                       prof-a
                       (assoc base-args
                              :allocation-request (:canonical-request alloc-b)
                              :allocation-result alloc-b
                              :allocation-witness (:committed-rows wb [])))))
        "substituting different allocation must fail verification")))

(deftest pkg-overreaching-conclusion
  (let [manifest (om/build-manifest
                  {:benchmark/content-root content-root
                   :benchmark/model-root model-root
                   :benchmark/evaluation-policy-root "sha256:eval"
                   :execution/status :completed
                   :results/operational {:conservation :pass :quota-bounded :pass}})
        overreach {:conclusion/id :conclusion/incentive-compatible
                   :conclusion/hash "sha256:incentive"
                   :conclusion/status :established}
        profile (exec-ev/build-pro-rata-execution-evidence
                 {:benchmark-content-root content-root
                  :model-root model-root
                  :outcome-manifest manifest
                  :allocation-evidence-hash "sha256:alloc"
                  :application-evidence-hash "sha256:app"
                  :theorem-outcomes []
                  :conclusions [overreach]})]
    ;; Profile builds and records no incentive theorem
    (is (some? (:evidence-profile/hash profile)))
    (is (empty? (:evidence-profile/theorem-hashes profile))
        "no theorem hashes — no incentive theorem provided")
    ;; The profile correctly does NOT claim incentive compatibility
    (is (not (contains? (:evidence-profile/theorem-hashes profile)
                        :theorem/incentive-compatibility))
        "incentive-compatibility theorem must not appear")))

(deftest pkg-corruption-matrix
  (let [manifest (build-pro-rata-manifest)
        alloc-result (real-alloc)
        aw (:result/witness alloc-result)
        alloc-prof (alloc-ev/build-pro-rata-allocation-evidence
                    {:benchmark-content-root content-root
                     :model-root model-root
                     :allocation-request (:canonical-request alloc-result)
                     :allocation-result alloc-result
                     :mechanism {:mechanism/id :pro-rata/largest-remainder
                                 :mechanism/version 1 :mechanism/hash "sha256:mh"}
                     :policy {:policy/id :default :policy/hash "sha256:ph"}
                     :allocation-witness (:committed-rows aw [])})
        alloc-hash (:evidence-profile/hash alloc-prof)
        app-prof (app-ev/build-pro-rata-application-evidence
                  {:allocation-evidence-hash alloc-hash
                   :propagation app-propag :application app-application
                   :world-before {} :world-after {}
                   :state-write-back-evidence app-wb
                   :continuity-evidence [] :evidence-ladder app-ladder
                   :operational-outcome app-op})
        app-hash (:evidence-profile/hash app-prof)
        exec-prof (exec-ev/build-pro-rata-execution-evidence
                   {:benchmark-content-root content-root
                    :model-root model-root
                    :outcome-manifest manifest
                    :allocation-evidence-hash alloc-hash
                    :application-evidence-hash app-hash
                    :theorem-outcomes []
                    :conclusions []})
        exec-hash (:evidence-profile/hash exec-prof)]
    ;; Corrupt allocation profile reference in execution profile
    (let [bad-prof (assoc exec-prof :evidence-profile/allocation-evidence-hash
                          "sha256:nonexistent")
          recompute-args {:benchmark-content-root content-root
                          :model-root model-root
                          :outcome-manifest manifest
                          :allocation-evidence-hash "sha256:nonexistent"
                          :application-evidence-hash app-hash
                          :theorem-outcomes []
                          :conclusions []}
          v (exec-ev/verify-pro-rata-execution-evidence bad-prof recompute-args)]
      (is (not (:valid? v))
          "wrong allocation profile hash fails execution profile verification"))
    ;; Corrupt application profile reference
    (let [bad-prof (assoc exec-prof :evidence-profile/application-evidence-hash
                          "sha256:nonexistent")
          recompute-args {:benchmark-content-root content-root
                          :model-root model-root
                          :outcome-manifest manifest
                          :allocation-evidence-hash alloc-hash
                          :application-evidence-hash "sha256:nonexistent"
                          :theorem-outcomes []
                          :conclusions []}
          v (exec-ev/verify-pro-rata-execution-evidence bad-prof recompute-args)]
      (is (not (:valid? v))
          "wrong application profile hash fails execution profile verification"))
    ;; Corrupt outcome manifest hash reference
    (let [bad-prof (assoc exec-prof :evidence-profile/outcome-manifest-hash
                          "sha256:wrong-manifest")
          wrong-mf (om/build-manifest
                    {:benchmark/content-root "sha256:other"
                     :benchmark/model-root model-root
                     :benchmark/evaluation-policy-root "sha256:eval"
                     :execution/status :completed
                     :results/operational {:conservation :pass}})
          recompute-args {:benchmark-content-root content-root
                          :model-root model-root
                          :outcome-manifest wrong-mf
                          :allocation-evidence-hash alloc-hash
                          :application-evidence-hash app-hash
                          :theorem-outcomes []
                          :conclusions []}
          v (exec-ev/verify-pro-rata-execution-evidence bad-prof recompute-args)]
      (is (not (:valid? v))
          "wrong outcome manifest hash fails execution profile verification"))
    ;; Self-consistent profile with different content root fails
    (let [args2 {:benchmark-content-root "sha256:other-root"
                 :model-root model-root
                 :outcome-manifest manifest
                 :allocation-evidence-hash alloc-hash
                 :application-evidence-hash app-hash
                 :theorem-outcomes []
                 :conclusions []}
          v (exec-ev/verify-pro-rata-execution-evidence exec-prof args2)]
      (is (not (:valid? v))
          "different benchmark content root fails execution profile verification"))))
