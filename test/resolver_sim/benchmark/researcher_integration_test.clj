(ns resolver-sim.benchmark.researcher-integration-test
  "Cross-namespace integration tests for the researcher-led benchmark
   content registry, outcome manifests, run reports, positions and
   three-member certificate lifecycle."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.content-registry-entry :as cre]
            [resolver-sim.benchmark.outcome-manifest :as om]
            [resolver-sim.benchmark.researcher-run-report :as rrr]
            [resolver-sim.benchmark.researcher-position :as rp]
            [resolver-sim.benchmark.review-round :as rr]
            [resolver-sim.benchmark.review.three-member-certificate :as tmc]
            [resolver-sim.benchmark.researcher-force-authorisation :as rfa]
            [resolver-sim.benchmark.research-theorem-outcome :as rto]
            [resolver-sim.benchmark.research-conclusion :as rc]
            [resolver-sim.benchmark.research-command :as rcmd]
            [resolver-sim.benchmark.force-authorised-execution-evidence :as fa-ev]
            [resolver-sim.hash.canonical :as hc])
  (:import [org.bouncycastle.crypto.generators Ed25519KeyPairGenerator]
           [org.bouncycastle.crypto.params Ed25519KeyGenerationParameters]
           [org.bouncycastle.crypto.util PrivateKeyInfoFactory SubjectPublicKeyInfoFactory]
           [java.security SecureRandom]
           [java.util Base64]))

;; ═══════════════════════════════════════════════════════════════════════════
;; Shared test data
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:const base-input
  {:benchmark/content-root "sha256:content"
   :benchmark/model-root "sha256:model"
   :benchmark/evaluation-policy-root "sha256:eval-policy"
   :execution/status :completed
   :execution/model-instance-root "sha256:model-instance"
   :execution/plan-root "sha256:plan"
   :execution/parameter-domain-root "sha256:param-domain"
   :execution/sampling-policy-root "sha256:sampling-policy"
   :execution/realised-parameter-set-root "sha256:realised-params"
   :execution/generated-case-set-root "sha256:generated-cases"
   :results/operational {:conservation :pass :quota-bounded :pass}})

(defn manifest []
  (om/build-manifest base-input))

(defn manifest-variant [field value]
  (om/build-manifest (assoc base-input field value)))

(def ^:const runner-info
  {:runner/id :runner/default
   :source-tree-hash "sha256:tree"
   :distribution-hash "sha256:dist"
   :environment-hash "sha256:env"})

(def ^:const evidence-refs
  {:evidence-dag-root "sha256:dag"
   :event-evidence-root "sha256:events"
   :execution-log-root "sha256:log"})

(defn report-for [id m & {:keys [ri er ru]
                          :or {ri runner-info er evidence-refs ru "run-001"}}]
  (rrr/build-report {:outcome-manifest m :researcher-id id
                     :runner-info ri :evidence-refs er :run-id ru}))

;; ═══════════════════════════════════════════════════════════════════════════
;; 0. Pre-conditions — builders reject invalid inputs before constructing
;; ═══════════════════════════════════════════════════════════════════════════

(deftest pre-condition-registry-entry-requires-model-root
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"model-root"
                        (cre/build-entry {:benchmark/id :test/bm}))))

(deftest pre-condition-registry-entry-invalid-component-status
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires :reason-code"
                        (cre/build-entry
                         {:benchmark/id :test/bm
                          :benchmark/model-root "sha256:m"
                          :benchmark/generator-root {:status :deferred :root nil}
                          :benchmark/evaluation-policy-root "sha256:e"}))))

(deftest pre-condition-registry-entry-content-root-mismatch
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"content-root"
                        (cre/build-entry
                         {:benchmark/id :test/bm
                          :benchmark/model-root "sha256:m"
                          :benchmark/evaluation-policy-root "sha256:e"
                          :benchmark/content-root "sha256:wrong"}))))

(deftest pre-condition-review-round-invalid-purpose
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"review-round purpose"
                        (rr/build-review-round
                         {:benchmark/content-root "sha256:c"
                          :review-round/purpose :invalid
                          :review-round/members [{:researcher/id "a" :role :model-steward}
                                                 {:researcher/id "b" :role :independent-reproducer}
                                                 {:researcher/id "c" :role :adversarial-reviewer}]
                          :review-round/membership-frozen-at "..."
                          :review-round/policy-root "sha256:p"}))))

(deftest pre-condition-review-round-missing-creation-inputs
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"creation requirements"
                        (rr/build-review-round
                         {:benchmark/content-root "sha256:c"
                          :review-round/purpose :model-admission
                          :review-round/members [{:researcher/id "a" :role :model-steward}
                                                 {:researcher/id "b" :role :independent-reproducer}
                                                 {:researcher/id "c" :role :adversarial-reviewer}]
                          :review-round/membership-frozen-at "..."}))))

(deftest pre-condition-position-unknown-dimension
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown position dimensions"
                        (rp/build-position
                         {:benchmark/content-root "sha256:c"
                          :researcher/id "a"
                          :outcome-hash "sha256:o"
                          :dimensions {:nonexistent {:status :ok}}}))))

(deftest pre-condition-force-auth-invalid-approvals
  (let [auth (rfa/build-authorisation
              {:authorisation/id :authorisation/test
               :authorisation/policy {:policy/id :research/three-member
                                       :policy/version 1
                                       :policy/schema-version "fa-policy.v1"
                                       :policy/hash "sha256:policy"}
               :authorisation/review-round {:review-round/id "rr:t"
                                             :review-round/hash "sha256:round"}
               :authorisation/request-root "sha256:req"
               :authorisation/target {:target/kind :benchmark-branch
                                       :target/baseline-content-root "sha256:base"
                                       :target/branch-descriptor-hash "sha256:br"
                                       :target/proposed-content-root "sha256:prop"}
               :authorisation/decision-references
               [{:researcher/id "a" :decision :approve
                 :decision/hash "sha256:mock"
                 :signature {:algorithm :ed25519 :value "x" :signed-at "now"}}]
               :authorisation/threshold {:required 2 :eligible 3}})]
    (is (= :declined (rfa/authorisation-status auth)) "1/3 approvals should be declined")))

(deftest pre-sign-checks-accept-complete-report
  (let [m (manifest)
        report (report-for "a" m)
        result (rrr/pre-sign-checks report)]
    (is (:pre-sign-valid? result))))

(deftest pre-sign-checks-reject-missing-content-root
  (let [incomplete {:schema-version "researcher-run-report.v1" :researcher/id "a"}
        result (rrr/pre-sign-checks incomplete)]
    (is (not (:pre-sign-valid? result)))
    (is (some #(re-find #"content-root" %) (:errors result)))))

(deftest pre-sign-checks-reject-missing-model-root
  (let [m (om/build-manifest (dissoc base-input :benchmark/model-root))
        report (report-for "a" m)
        result (rrr/pre-sign-checks report)]
    (is (not (:pre-sign-valid? result)))
    (is (some #(re-find #"model-root" %) (:errors result)))))

(deftest pre-sign-checks-reject-missing-execution-field
  (let [m (om/build-manifest (dissoc base-input :execution/parameter-domain-root))
        report (report-for "a" m)
        result (rrr/pre-sign-checks report)]
    (is (not (:pre-sign-valid? result)))
    (is (some #(re-find #"parameter-domain-root" %) (:errors result)))))

(deftest pre-sign-checks-require-nine-execution-fields
  (let [m (manifest)
        report (report-for "a" m)
        result (rrr/pre-sign-checks report)]
    (is (:pre-sign-valid? result))))

(deftest cross-artifact-roots-consistent
  (let [entry {:benchmark/content-root "sha256:content" :benchmark/model-root "sha256:model"
               :benchmark/evaluation-policy-root "sha256:eval-policy"}
        manifest (manifest)]
    (is (:consistent? (om/cross-artifact-roots-consistent? entry manifest)))))

(deftest cross-artifact-roots-inconsistent-content
  (let [entry {:benchmark/content-root "sha256:content-a" :benchmark/model-root "sha256:model"
               :benchmark/evaluation-policy-root "sha256:eval-policy"}
        manifest (manifest)
        result (om/cross-artifact-roots-consistent? entry manifest)]
    (is (not (:consistent? result)))
    (is (some #(= :benchmark/content-root (:field %)) (:mismatches result)))))

(deftest cross-artifact-roots-inconsistent-model
  (let [entry {:benchmark/content-root "sha256:content" :benchmark/model-root "sha256:model-a"
               :benchmark/evaluation-policy-root "sha256:eval-policy"}
        manifest (manifest)
        result (om/cross-artifact-roots-consistent? entry manifest)]
    (is (not (:consistent? result)))
    (is (some #(= :benchmark/model-root (:field %)) (:mismatches result)))))

(deftest cross-artifact-roots-inconsistent-content
  (let [entry {:benchmark/content-root "sha256:content-a" :benchmark/model-root "sha256:model"}
        manifest (manifest)
        result (om/cross-artifact-roots-consistent? entry manifest)]
    (is (not (:consistent? result)))
    (is (some #(= :benchmark/content-root (:field %)) (:mismatches result)))))

(deftest cross-artifact-roots-inconsistent-model
  (let [entry {:benchmark/content-root "sha256:content" :benchmark/model-root "sha256:model-a"}
        manifest (manifest)
        result (om/cross-artifact-roots-consistent? entry manifest)]
    (is (not (:consistent? result)))
    (is (some #(= :benchmark/model-root (:field %)) (:mismatches result)))))

(deftest verify-against-round-detects-non-member
  (let [auth (rfa/build-authorisation
              {:authorisation/id :authorisation/test
               :authorisation/policy {:policy/id :research/three-member
                                       :policy/version 1
                                       :policy/schema-version "fa-policy.v1"
                                       :policy/hash "sha256:policy"}
               :authorisation/review-round {:review-round/id "rr:t"
                                             :review-round/hash "sha256:round"}
               :authorisation/request-root "sha256:req"
               :authorisation/target {:target/kind :benchmark-branch
                                       :target/baseline-content-root "sha256:base"
                                       :target/branch-descriptor-hash "sha256:br"
                                       :target/proposed-content-root "sha256:prop"}
               :authorisation/decision-references
               [{:researcher/id "a" :decision :approve
                 :decision/hash "sha256:mock1"
                 :signature {:algorithm :ed25519 :value "x" :signed-at "now"}}
                {:researcher/id "d" :decision :approve
                 :decision/hash "sha256:mock2"
                 :signature {:algorithm :ed25519 :value "y" :signed-at "now"}}]
               :authorisation/threshold {:required 2 :eligible 3}})
        round {:review-round/id "rr:t" :review-round/hash "sha256:round"
               :review-round/members [{:researcher/id "a" :role :model-steward}
                                      {:researcher/id "b" :role :independent-reproducer}
                                      {:researcher/id "c" :role :adversarial-reviewer}]}
        result (rfa/verify-against-round round auth)]
    (is (not (:valid? result)) "non-member 'd' should be detected by verify-against-round")))

(deftest pre-application-checks-accepts-complete-manifest
  (let [result (om/pre-application-checks (om/build-manifest base-input))]
    (is (:pre-application-valid? result))))

(deftest pre-application-checks-rejects-missing-content-root
  (let [manifest (om/build-manifest (dissoc base-input :benchmark/content-root))
        result (om/pre-application-checks manifest)]
    (is (not (:pre-application-valid? result)))
    (is (some #(re-find #"content-root" %) (:errors result)))))

(deftest pre-application-checks-rejects-missing-model-root
  (let [manifest (om/build-manifest (dissoc base-input :benchmark/model-root))
        result (om/pre-application-checks manifest)]
    (is (not (:pre-application-valid? result)))
    (is (some #(re-find #"model-root" %) (:errors result)))))

(deftest pre-application-checks-rejects-missing-eval-policy
  (let [manifest (om/build-manifest (dissoc base-input :benchmark/evaluation-policy-root))
        result (om/pre-application-checks manifest)]
    (is (not (:pre-application-valid? result)))))

(deftest pre-application-checks-rejects-missing-param-domain
  (let [manifest (om/build-manifest (dissoc base-input :execution/parameter-domain-root))
        result (om/pre-application-checks manifest)]
    (is (not (:pre-application-valid? result)))))

(deftest pre-application-checks-rejects-missing-generated-cases
  (let [manifest (om/build-manifest (dissoc base-input :execution/generated-case-set-root))
        result (om/pre-application-checks manifest)]
    (is (not (:pre-application-valid? result)))))

(deftest pre-application-checks-does-not-require-outcome-hash
  (let [manifest (om/build-manifest base-input)
        without-hash (dissoc manifest :benchmark-outcome/hash)
        result (om/pre-application-checks without-hash)]
    (is (:pre-application-valid? result)
        "pre-application should not require outcome-hash — it is a result, not a precondition")))

(deftest pre-condition-report-missing-content-root
  (let [m (om/build-manifest {:benchmark/content-root nil :benchmark/model-root "sha256:m"})]
    (is (not (rrr/report-valid? (rrr/build-report
                                 {:outcome-manifest m
                                  :researcher-id "a"
                                  :runner-info runner-info
                                  :evidence-refs evidence-refs
                                  :run-id "r"}))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; 1. Cross-artifact reconciliation
;; ═══════════════════════════════════════════════════════════════════════════

(deftest reconciliation-passes
  (let [m (manifest)
        r (report-for "a" m)]
    (is (:valid? (rrr/verify-against-manifest r m)))))

(defn- register-mismatch-detected
  "Test body for each reconciliation mismatch case.
   Builds a report from the baseline manifest, then checks against a
   manifest whose single execution field has been corrupted."
  [corrupt-field corrupt-value]
  (let [good (manifest)
        report (report-for "a" good)
        ;; Corrupt the manifest field AFTER building the report
        bad-manifest (assoc good corrupt-field corrupt-value)
        result (rrr/verify-against-manifest report bad-manifest)]
    (is (not (:valid? result))
        (str "verify-against-manifest should fail when " corrupt-field " differs"))
    (is (some #(= corrupt-field (:field %)) (:mismatches result))
        (str "mismatch should identify field " corrupt-field))))

(deftest content-root-mismatch-detected
  (register-mismatch-detected :benchmark/content-root "sha256:wrong-content"))

(deftest model-root-mismatch-detected
  (register-mismatch-detected :benchmark/model-root "sha256:wrong-model"))

(deftest model-instance-mismatch-detected
  (register-mismatch-detected :execution/model-instance-root "sha256:wrong-mi"))

(deftest plan-root-mismatch-detected
  (register-mismatch-detected :execution/plan-root "sha256:wrong-plan"))

(deftest parameter-domain-mismatch-detected
  (register-mismatch-detected :execution/parameter-domain-root "sha256:wrong-domain"))

(deftest sampling-policy-mismatch-detected
  (register-mismatch-detected :execution/sampling-policy-root "sha256:wrong-sampling"))

(deftest realised-params-mismatch-detected
  (register-mismatch-detected :execution/realised-parameter-set-root "sha256:wrong-params"))

(deftest generated-cases-mismatch-detected
  (register-mismatch-detected :execution/generated-case-set-root "sha256:wrong-cases"))

(deftest eval-policy-mismatch-detected
  (register-mismatch-detected :benchmark/evaluation-policy-root "sha256:wrong-eval"))

(deftest manifest-hash-mismatch-detected
  (let [m-a (manifest)
        m-b (manifest-variant :execution/plan-root "sha256:different-plan")
        report (report-for "a" m-a)
        result (rrr/verify-against-manifest report m-b)]
    (is (not (:valid? result)))
    (is (not (:manifest-hash-match? result)))))

;; ═══════════════════════════════════════════════════════════════════════════
;; 2. Compatibility matrix
;; ═══════════════════════════════════════════════════════════════════════════

(deftest compat-researcher-identity-only
  (let [m (manifest)
        r1 (report-for "a" m)
        r2 (report-for "b" m)
        r3 (report-for "c" m)]
    (is (= :exact-replication (tmc/replication-type [r1 r2 r3])))))

(deftest compat-realised-params-not-exact
  (let [a (manifest) b (manifest-variant :execution/realised-parameter-set-root "sha256:diff")]
    (is (not= :exact-replication (om/classify-outcome-compatibility a b)))))

(deftest compat-generated-case-set-sampling
  (let [a (manifest) b (manifest-variant :execution/generated-case-set-root "sha256:diff")]
    (is (= :independent-sampling (om/classify-outcome-compatibility a b)))))

(deftest compat-sampling-policy-not-exact
  (let [a (manifest) b (manifest-variant :execution/sampling-policy-root "sha256:diff")]
    (is (not= :exact-replication (om/classify-outcome-compatibility a b)))))

(deftest compat-parameter-domain-not-exact
  (let [a (manifest) b (manifest-variant :execution/parameter-domain-root "sha256:diff")]
    (is (not= :exact-replication (om/classify-outcome-compatibility a b)))))

(deftest compat-content-root-not-exact
  (let [a (manifest) b (manifest-variant :benchmark/content-root "sha256:diff")]
    (is (not= :exact-replication (om/classify-outcome-compatibility a b)))))

(deftest compat-primary-model-incompatible
  (let [a (manifest) b (manifest-variant :benchmark/model-root "sha256:diff-model")]
    (is (= :incompatible-scope (om/classify-outcome-compatibility a b)))))

(deftest compat-evaluation-policy-not-exact
  (let [a (manifest) b (manifest-variant :benchmark/evaluation-policy-root "sha256:diff")]
    (is (not= :exact-replication (om/classify-outcome-compatibility a b)))))

(deftest compat-all-symmetric
  (let [fields [:execution/realised-parameter-set-root
                :execution/generated-case-set-root
                :execution/sampling-policy-root
                :execution/parameter-domain-root
                :execution/model-instance-root
                :execution/plan-root
                :benchmark/evaluation-policy-root
                :benchmark/model-root
                :benchmark/content-root]]
    (doseq [f fields]
      (let [a (manifest) b (manifest-variant f (str "sha256:diff-" (name f)))]
        (is (= (om/classify-outcome-compatibility a b)
               (om/classify-outcome-compatibility b a))
            (str "symmetry failure for: " f))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; 3. Three-member mixed positions
;; ═══════════════════════════════════════════════════════════════════════════

(defn- pos [id & {:keys [authority evidence] :or {authority :adequate evidence :sufficient}}]
  (rp/build-position
   {:benchmark/content-root "sha256:c" :researcher/id id :outcome-hash "sha256:o"
    :dimensions {:reproduction {:status :reproduced}
                 :model-authority {:status authority}
                 :evidence {:status evidence}
                 :publication {:status :publish}}}))

(deftest not-reviewed-not-dissent
  (let [c (tmc/per-dimension-consensus [(pos "a") (pos "b") (pos "c" :authority :not-reviewed)]
                                       :model-authority)]
    (is (= :unanimous (:status c)) "not-reviewed is not dissent")
    (is (= ["a" "b"] (:supporting-members c)))
    (is (= ["c"] (:not-reviewed-members c)))
    (is (empty? (:dissenting-members c)))))

(deftest insufficient-info-not-dissent
  (let [c (tmc/per-dimension-consensus [(pos "a") (pos "b")
                                        (pos "c" :authority :insufficient-information)]
                                       :model-authority)]
    (is (= ["a" "b"] (:supporting-members c)))
    (is (= ["c"] (:insufficient-information-members c)))
    (is (empty? (:dissenting-members c)))))

(deftest not-applicable-not-dissent
  (let [c (tmc/per-dimension-consensus [(pos "a") (pos "b")
                                        (pos "c" :authority :not-applicable)]
                                       :model-authority)]
    (is (= ["a" "b"] (:supporting-members c)))
    (is (= ["c"] (:not-applicable-members c)))
    (is (empty? (:dissenting-members c)))))

(deftest three-classifications-preserved
  (let [c (tmc/per-dimension-consensus [(pos "a") (pos "b" :authority :incomplete)
                                        (pos "c" :authority :insufficient-information)]
                                       :model-authority)]
    (is (= :contested (:status c)) "three-way disagreement -> contested")
    (is (= ["a"] (:supporting-members c)) "a said adequate -> supports")
    (is (= ["b"] (:dissenting-members c)) "b said incomplete -> dissent")
    (is (= ["c"] (:insufficient-information-members c)) "c -> separate group")))

(deftest all-absent-not-evaluable
  (let [c (tmc/per-dimension-consensus [(pos "a" :authority :not-reviewed)
                                        (pos "b" :authority :not-reviewed)
                                        (pos "c" :authority :not-applicable)]
                                       :model-authority)]
    (is (= :not-evaluable (:status c)))
    (is (= 2 (count (:not-reviewed-members c))))
    (is (= 1 (count (:not-applicable-members c))))))

(deftest majority-with-dissent
  (let [c (tmc/per-dimension-consensus [(pos "a") (pos "b")
                                        (pos "c" :authority :incomplete)]
                                       :model-authority)]
    (is (= :majority-with-dissent (:status c)))
    (is (= ["a" "b"] (:supporting-members c)))
    (is (= ["c"] (:dissenting-members c)))))

(deftest pre-certificate-checks-valid
  (let [m (manifest)
        round {:benchmark/content-root "sha256:content" :review-round/id "rr:t"
               :review-round/purpose :model-admission}
        reports [(report-for "a" m) (report-for "b" m) (report-for "c" m)]
        positions [(pos "a") (pos "b") (pos "c")]
        result (tmc/pre-certificate-checks {:review-round round :reports reports :positions positions})]
    (is (:pre-certificate-valid? result))))

(deftest pre-certificate-checks-reject-fewer-than-three-reports
  (let [m (manifest)
        round {:benchmark/content-root "sha256:c" :review-round/id "rr:t"
               :review-round/purpose :model-admission}
        result (tmc/pre-certificate-checks {:review-round round
                                            :reports [(report-for "a" m)]
                                            :positions [(pos "a") (pos "b") (pos "c")]})]
    (is (not (:pre-certificate-valid? result)))))

(deftest pre-certificate-checks-reject-content-root-mismatch
  (let [m (manifest)
        m2 (om/build-manifest (assoc base-input :benchmark/content-root "sha256:different"))
        round {:benchmark/content-root "sha256:c" :review-round/id "rr:t"
               :review-round/purpose :model-admission}
        result (tmc/pre-certificate-checks {:review-round round
                                            :reports [(report-for "a" m)
                                                      (report-for "b" m)
                                                      (report-for "c" m2)]
                                            :positions [(pos "a") (pos "b") (pos "c")]})]
    (is (not (:pre-certificate-valid? result)))))

(deftest pre-certificate-checks-reject-missing-outcome-hash
  (let [m (manifest)
        round {:benchmark/content-root "sha256:c" :review-round/id "rr:t"
               :review-round/purpose :model-admission}
        report-no-hash (dissoc (report-for "a" m) :researcher-run-report/outcome-hash)
        result (tmc/pre-certificate-checks {:review-round round
                                            :reports [report-no-hash
                                                      (report-for "b" m)
                                                      (report-for "c" m)]
                                            :positions [(pos "a") (pos "b") (pos "c")]})]
    (is (not (:pre-certificate-valid? result)))))

;; ═══════════════════════════════════════════════════════════════════════════
;; 4. End-to-end lifecycle
;; ═══════════════════════════════════════════════════════════════════════════

(deftest lifecycle-three-exact-replication
  (let [m (manifest)
        reports [(report-for "a" m) (report-for "b" m) (report-for "c" m)]
        checks (mapv #(rrr/verify-against-manifest % m) reports)]
    (is (every? :valid? checks) "all reports reconcile")
    (is (= :exact-replication (tmc/replication-type reports)))
    (let [groups (tmc/group-outcomes reports)]
      (is (= 1 (count groups)))
      (is (= 3 (:count (first groups)))))))

(deftest lifecycle-independent-sampling
  (let [m1 (manifest) m2 (manifest-variant :execution/generated-case-set-root "sha256:diff")
        reports [(report-for "a" m1) (report-for "b" m1) (report-for "c" m2)]]
    (is (= :independent-sampling (tmc/replication-type reports)))
    (is (= 2 (count (tmc/group-outcomes reports))))))

(deftest lifecycle-model-corroboration
  (let [a (manifest) b (manifest-variant :execution/model-instance-root "sha256:diff")]
    (is (= :model-corroboration (om/classify-outcome-compatibility a b)))))

(deftest lifecycle-positions-and-certificate
  (let [m (manifest)
        round {:benchmark/content-root "sha256:content" :review-round/id "rr:int"
               :review-round/purpose :model-admission}
        reports [(report-for "a" m) (report-for "b" m) (report-for "c" m)]
        positions [(pos "a") (pos "b") (pos "c" :authority :not-reviewed)]
        cert (tmc/build-certificate {:review-round round :reports reports :positions positions})
        auth (get-in cert [:model-consensus :model-authority])]
    (is (= :unanimous (:status auth)))
    (is (= ["a" "b"] (:supporting-members auth)))
    (is (= ["c"] (:not-reviewed-members auth)))))

;; ═══════════════════════════════════════════════════════════════════════════
;; 5. Corruption detection
;; ═══════════════════════════════════════════════════════════════════════════

(deftest corruption-validate-manifest-rejects-hash-tamper
  (let [m (manifest)
        bad (assoc m :benchmark-outcome/hash "sha256:fake")]
    (is (not (:valid? (om/validate-manifest bad))))))

(deftest corruption-validate-certificate-rejects-hash-tamper
  (let [m (manifest)
        round {:benchmark/content-root "sha256:c" :review-round/id "rr:t" :review-round/purpose :model-admission}
        reports [(report-for "a" m) (report-for "b" m) (report-for "c" m)]
        pos [(pos "a") (pos "b") (pos "c")]
        cert (-> (tmc/build-certificate {:review-round round :reports reports :positions pos})
                 (tmc/finalise-certificate!))
        bad (assoc cert :certificate/hash "sha256:fake")]
    (is (not (:valid? (tmc/validate-certificate bad))))))

(deftest corruption-validate-report-rejects-hash-tamper
  (let [m (manifest)
        r (report-for "a" m)
        bad (assoc r :researcher-run-report/hash "sha256:fake")]
    (is (not (:valid? (rrr/validate-report bad))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; 6. Hierarchical outcome lifecycle (theorem → conclusion → certificate)
;; ═══════════════════════════════════════════════════════════════════════════

(deftest theorem-outcome-built-and-referenced
  (let [th (rto/build-theorem-outcome
            {:theorem/id :theorem/quota-bounded
             :theorem/type :boundedness
             :theorem/statement
             {:if {:claim :partial-fill-calculated}
              :then {:claim :quota-bounded}}
             :theorem/scope {:benchmark/content-root "sha256:content"
                             :model/root "sha256:model"}
             :theorem/conclusion {:status :established :claim-id :claim/quota-bounded}})]
    (is (rto/theorem-valid? th))
    (is (some? (:theorem/hash th)))))

(deftest conclusion-built-and-validated
  (let [c (rc/build-conclusion
           {:conclusion/id :conclusion/partial-fill-correctness
            :conclusion/premise {:x "Quota bounds and state write-back hold."}
            :conclusion/result {:y "Partial-fill preserves authoritative state."}
            :conclusion/qualifications ["No coalition conclusion."]})]
    (is (rc/conclusion-valid? c))
    (is (not (rc/conclusion-overreaches? c)))))

(deftest command-built-with-includes
  (let [cmd (rcmd/build-command
             {:command/id :command/incentive-compatibility
              :command/type :benchmark-evaluation
              :command/argv ["prf" "benchmark" "run-and-report"
                             "--include" "incentive-compatibility"]
              :command/include [:incentive-compatibility]})]
    (is (rcmd/command-valid? cmd))))

(deftest full-hierarchical-outcome-lifecycle
  (let [th (rto/build-theorem-outcome
            {:theorem/id :theorem/quota-bounded
             :theorem/type :boundedness
             :theorem/statement
             {:if {:claim :partial-fill-calculated}
              :then {:claim :quota-bounded}}
             :theorem/scope {:benchmark/content-root "sha256:content"
                             :model/root "sha256:model"}
             :theorem/conclusion {:status :established :claim-id :claim/quota-bounded}})
        c (rc/build-conclusion
           {:conclusion/id :conclusion/partial-fill-correctness
            :conclusion/premise {:x "Quota bounds hold."}
            :conclusion/result {:y "Partial-fill is correct."}})
        cmd (rcmd/build-command
             {:command/id :command/evaluation
              :command/type :benchmark-evaluation
              :command/argv ["prf" "benchmark" "run-and-report"]
              :command/include [:incentive :incentive-compatibility]})
        manifest (om/build-manifest
                  (assoc base-input
                         :execution/command-root (:command/hash cmd)
                         :outcomes/operational-root "sha256:oper"
                         :outcomes/incentive-root "sha256:inc"
                         :outcomes/incentive-compatibility-root "sha256:ic"
                         :outcomes/theorems
                         [{:theorem/id :theorem/quota-bounded
                           :theorem/hash (:theorem/hash th)
                           :status :established}]
                         :outcomes/conclusions
                         [{:conclusion/id :conclusion/partial-fill-correctness
                           :conclusion/hash (:conclusion/hash c)}]))
        round {:benchmark/content-root "sha256:content"
               :review-round/id "rr:hier"
               :review-round/purpose :model-admission}
        reports [(report-for "a" manifest)
                 (report-for "b" manifest)
                 (report-for "c" manifest)]
        positions [(rp/build-position
                    {:benchmark/content-root "sha256:content"
                     :researcher/id "a" :outcome-hash "sha256:o"
                     :dimensions {:publication {:status :publish}}
                     :position/targets
                     [{:kind :theorem :id :theorem/quota-bounded
                       :hash (:theorem/hash th) :status :reproduced}]})
                   (rp/build-position
                    {:benchmark/content-root "sha256:content"
                     :researcher/id "b" :outcome-hash "sha256:o"
                     :dimensions {:publication {:status :publish}}
                     :position/targets
                     [{:kind :theorem :id :theorem/quota-bounded
                       :hash (:theorem/hash th) :status :reproduced}]})
                   (rp/build-position
                    {:benchmark/content-root "sha256:content"
                     :researcher/id "c" :outcome-hash "sha256:o"
                     :dimensions {:publication {:status :publish}}
                     :position/targets
                     [{:kind :theorem :id :theorem/quota-bounded
                       :hash (:theorem/hash th) :status :qualified
                       :rationale "Matches but coalition not evaluated."}]})]
        cert (tmc/build-certificate
              {:review-round round :reports reports :positions positions})]
    (is (om/manifest-valid? manifest))
    (is (contains? manifest :outcome-hashes))
    (is (some? (get-in manifest [:outcome-hashes :theorem-root])))
    (is (some? (get-in manifest [:outcome-hashes :conclusion-root])))
    (is (tmc/certificate-valid? cert))
    (is (contains? cert :theorem-consensus))
    (is (contains? cert :conclusion-consensus))
    (let [th-cons (get-in cert [:theorem-consensus :theorem/quota-bounded])]
      (is (= :majority-with-dissent (:status th-cons)))
      (is (= 2 (count (:supporting-members th-cons))))
      (is (= 1 (count (:dissenting-members th-cons)))))))

(deftest plural-outcome-hashes-allow-per-item-dispute
  (let [th1 (rto/build-theorem-outcome
             {:theorem/id :theorem/quota-bounded
              :theorem/type :boundedness
              :theorem/statement {:if {:claim :c1} :then {:claim :r1}}
              :theorem/scope {:model/root "sha256:m"}
              :theorem/conclusion {:status :established :claim-id :claim/r1}})
        th2 (rto/build-theorem-outcome
             {:theorem/id :theorem/incentive-compatibility
              :theorem/type :incentive-compatibility
              :theorem/statement {:if {:claim :c2} :then {:claim :r2}}
              :theorem/scope {:model/root "sha256:m"}
              :theorem/conclusion {:status :supported-within-domain
                                   :claim-id :claim/r2}})
        manifest (om/build-manifest
                  (assoc base-input
                         :outcomes/theorems
                         [{:theorem/id :theorem/quota-bounded
                           :theorem/hash (:theorem/hash th1)
                           :status :established}
                          {:theorem/id :theorem/incentive-compatibility
                           :theorem/hash (:theorem/hash th2)
                           :status :supported-within-domain}]
                         :outcomes/conclusions []))]
    (is (om/manifest-valid? manifest))
    (is (contains? manifest :outcome-hashes))
    (is (some? (get-in manifest [:outcome-hashes :theorem-root])))
    (is (not= (:benchmark-outcome/hash manifest)
              (get-in manifest [:outcome-hashes :theorem-root]))
        "plural theorem-root differs from singular outcome-hash")))

;; ═══════════════════════════════════════════════════════════════════════════
;; 7. Force-authorisation lifecycle (real signatures)
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:private fa-test-keys
  (let [encoder (Base64/getMimeEncoder)
        cleanup-files (atom [])
        gen-fn (fn [label]
                 (let [gen (Ed25519KeyPairGenerator.)
                       _ (.init gen (Ed25519KeyGenerationParameters.
                                     (SecureRandom.)))
                       pair (.generateKeyPair gen)
                       priv-pk (.getPrivate pair)
                       pub-pk (.getPublic pair)
                       priv-der (.getEncoded
                                  (PrivateKeyInfoFactory/createPrivateKeyInfo priv-pk))
                       pub-der (.getEncoded
                                 (SubjectPublicKeyInfoFactory/createSubjectPublicKeyInfo pub-pk))
                       priv-file (java.io.File/createTempFile
                                  (str "fa-" label "-priv") ".pem")
                       pub-file (java.io.File/createTempFile
                                 (str "fa-" label "-pub") ".pem")]
                   ;; Write PKCS8 private key
                   (spit priv-file
                         (str "-----BEGIN PRIVATE KEY-----\n"
                              (.encodeToString encoder priv-der)
                              "\n-----END PRIVATE KEY-----\n"))
                   ;; Write X509 public key
                   (spit pub-file
                         (str "-----BEGIN PUBLIC KEY-----\n"
                              (.encodeToString encoder pub-der)
                              "\n-----END PUBLIC KEY-----\n"))
                   ;; Lock down private key permissions: owner read-only
                   (.setReadable priv-file true false)
                   (.setWritable priv-file false false)
                   (.setExecutable priv-file false false)
                   ;; Public key: owner read, others readable
                   (.setReadable pub-file true false)
                   (.setWritable pub-file false false)
                   (.setExecutable pub-file false false)
                   (.setReadable pub-file true true)
                   ;; Register for cleanup
                   (swap! cleanup-files conj priv-file pub-file)
                   {:researcher/id label
                    :private-key-path (.getPath priv-file)
                    :public-key-path (.getPath pub-file)}))
        keys (vec (map gen-fn ["researcher-a" "researcher-b" "researcher-c"]))]
    ;; Register JVM shutdown hook to ensure cleanup even on test failure
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (doseq [f @cleanup-files]
                                   (when (.exists f) (.delete f))))))
    keys))

(defn- fa-key-for
  "Look up a test researcher's keypair by id."
  [id]
  (first (filter #(= id (:researcher/id %)) fa-test-keys)))

(def ^:private fa-policy-artifact
  "A minimal resolved force-authorisation policy artifact (string-keyed, as
   resolved from the run-layer)."
  {"schema_version" "force-authorisation-policy.v1"
   "policy_id" "research/three-member-force-authorisation"
   "policy_version" 1
   "member_count" 3
   "threshold" 2
   "single_use?" true
   "preserve_dissent?" true
   "scope_required?" true
   "expiry_required?" true
   "policy_sha256" "sha256:test-policy-hash"})

(def ^:private fa-policy-ref
  {:policy/id :research/three-member-force-authorisation
   :policy/version 1
   :policy/schema-version "force-authorisation-policy.v1"
   :policy/hash (get fa-policy-artifact "policy_sha256")})

(def ^:private fa-round-artifact
  "A resolved review-round artifact for cross-artifact verification."
  {:review-round/id :review-round/fa-lifecycle
   :review-round/hash "sha256:test-round-hash"
   :benchmark/content-root "sha256:content"
   :review-round/members
   [{:researcher/id "researcher-a" :role :model-steward}
    {:researcher/id "researcher-b" :role :independent-reproducer}
    {:researcher/id "researcher-c" :role :adversarial-reviewer}]})

(def ^:private fa-round-ref
  {:review-round/id (:review-round/id fa-round-artifact)
   :review-round/hash (:review-round/hash fa-round-artifact)})

(def ^:private fa-target
  {:target/kind :benchmark-branch
   :target/baseline-content-root "sha256:baseline-root"
   :target/branch-descriptor-hash "sha256:branch-descriptor"
   :target/proposed-content-root "sha256:proposed-root"})

(def ^:private fa-request-root "sha256:authorisation-request")

(defn- fa-sign-decision!
  "Sign a researcher decision using the real Ed25519 signing path.
   Returns a decision reference map suitable for :authorisation/decision-references."
  [researcher-id authorisation-id decision & {:keys [dissent-reason]}]
  (let [keypair (fa-key-for researcher-id)]
    (rfa/build-signed-decision
     researcher-id authorisation-id decision
     (:private-key-path keypair)
     :dissent-reason dissent-reason)))

(defn- fa-build-auth!
  "Build a force-authorisation artifact with the given decision map.
   decisions is a vector of {:researcher/id :decision :dissent/reason?}."
  [authorisation-id decisions]
  (let [signed (mapv (fn [d] (apply fa-sign-decision!
                                     (:researcher/id d) authorisation-id
                                     (:decision d)
                                     (when (:dissent/reason d)
                                       [:dissent-reason (:dissent/reason d)])))
                     decisions)]
    (rfa/build-authorisation
     {:authorisation/id authorisation-id
      :authorisation/policy fa-policy-ref
      :authorisation/review-round fa-round-ref
      :authorisation/request-root fa-request-root
      :authorisation/target fa-target
      :authorisation/decision-references signed
      :authorisation/threshold {:required 2 :eligible 3}
      :authorisation/valid-from "2020-01-01T00:00:00Z"
      :authorisation/expires-at "2099-12-31T23:59:59Z"})))

(defn- fa-lifecycle-summary
  "Test-local summary helper. Derives values from verification functions
   — does NOT merely copy fields from the artifact."
  [auth & {:keys [consumption-checker revocation-checker scope-validator]
           :or {consumption-checker (constantly false)
                revocation-checker (constantly false)}}]
  (let [struct (rfa/validate-authorisation auth)
        sig-resolver (fn [id] (:public-key-path (fa-key-for id)))]
    {:authorisation-hash (:authorisation/hash auth)
     :decision-status (rfa/authorisation-status auth)
     :approved? (rfa/authorisation-approved? auth)
     :threshold (:authorisation/threshold auth)
     :structurally-valid? (:valid? struct)
     :signatures-verified? (:valid? (rfa/verify-decision-signatures
                                     sig-resolver auth))
     :consumption-key (rfa/consumption-key auth)
     :usable? (:usable? (rfa/verify-authorisation-usable
                         auth :consumption-checker consumption-checker
                         :revocation-checker revocation-checker
                         :scope-validator scope-validator))}))

;; ── Scenario: clean approval (3 approve, 0 dissent) ─────────────────────

(deftest lifecycle-clean-approval
  (let [auth (fa-build-auth! :authorisation/fa-clean
              [{:researcher/id "researcher-a" :decision :approve}
               {:researcher/id "researcher-b" :decision :approve}
               {:researcher/id "researcher-c" :decision :approve}])
        summary (fa-lifecycle-summary auth)]
    (is (= :approved (:decision-status summary)))
    (is (:approved? summary))
    (is (= {:required 2 :eligible 3 :approved 3 :dissented 0}
           (:threshold summary)))
    (is (= 3 (count (get-in auth [:authorisation/decision-references]))))
    (is (some? (:authorisation-hash summary)))))

;; ── Scenario: approval with preserved dissent (2 approve, 1 dissent) ────

(deftest lifecycle-approval-with-dissent
  (let [authorisation-id :authorisation/fa-dissent
        auth (fa-build-auth! authorisation-id
              [{:researcher/id "researcher-a" :decision :approve}
               {:researcher/id "researcher-b" :decision :approve}
               {:researcher/id "researcher-c" :decision :dissent
                :dissent/reason "scope concern"}])
        ;; Fresh usability
        summary-fresh (fa-lifecycle-summary auth)
        ;; Consumed usability
        consumed-checker (fn [ck]
                           (= ck (rfa/consumption-key auth)))
        summary-consumed (fa-lifecycle-summary auth
                           :consumption-checker consumed-checker)
        ;; Expired usability
        auth-expired (assoc auth
                            :authorisation/expires-at "2020-01-01T00:00:00Z")
        summary-expired (fa-lifecycle-summary auth-expired)
        ;; Cross-artifact verification
        struct (rfa/validate-authorisation auth)
        policy-ok (rfa/verify-against-policy fa-policy-artifact auth)
        round-ok (rfa/verify-against-round fa-round-artifact auth)
        sig-resolver (fn [id] (:public-key-path (fa-key-for id)))
        sigs-ok (rfa/verify-decision-signatures sig-resolver auth)]

    ;; ── Decision status ────────────────────────────────────────────────
    (is (= :approved-with-dissent (:decision-status summary-fresh)))
    (is (:approved? summary-fresh))
    (is (= {:required 2 :eligible 3 :approved 2 :dissented 1}
           (:threshold summary-fresh)))
    (is (= 3 (count (get-in auth [:authorisation/decision-references]))))
    (let [dissent-ref (some #(when (= :dissent (:decision %)) %)
                            (get-in auth [:authorisation/decision-references]))]
      (is (some? dissent-ref) "dissent must be retained")
      (is (= "scope concern" (:dissent/reason dissent-ref))))

    ;; ── Authenticity ───────────────────────────────────────────────────
    (is (:valid? struct) "validate-authorisation")
    (is (:valid? policy-ok) "verify-against-policy")
    (is (:valid? round-ok) "verify-against-round")
    (is (:valid? sigs-ok) "verify-decision-signatures")
    (is (every? :valid? (:results sigs-ok))
        "every decision signature must verify")

    ;; ── Exact binding ──────────────────────────────────────────────────
    (is (= fa-request-root
           (:authorisation/request-root auth)))
    (is (= (:target/baseline-content-root fa-target)
           (get-in auth [:authorisation/target :target/baseline-content-root])))
    (is (= (:target/proposed-content-root fa-target)
           (get-in auth [:authorisation/target :target/proposed-content-root])))

    ;; ── Deterministic consumption key ──────────────────────────────────
    (let [ck (rfa/consumption-key auth)]
      (is (re-find #"^sha256:" ck))
      (is (= ck (rfa/consumption-key
                 (fa-build-auth! authorisation-id
                   [{:researcher/id "researcher-a" :decision :approve}
                    {:researcher/id "researcher-b" :decision :approve}
                    {:researcher/id "researcher-c" :decision :dissent
                     :dissent/reason "scope concern"}])))
          "same inputs must produce same consumption-key"))

    ;; ── Canonical hash integrity ───────────────────────────────────────
    (let [recomputed (rfa/build-authorisation
                      {:authorisation/id authorisation-id
                       :authorisation/policy fa-policy-ref
                       :authorisation/review-round fa-round-ref
                       :authorisation/request-root fa-request-root
                       :authorisation/target fa-target
                       :authorisation/decision-references
                       (get-in auth [:authorisation/decision-references])
                       :authorisation/threshold {:required 2 :eligible 3}
                       :authorisation/valid-from "2020-01-01T00:00:00Z"
                       :authorisation/expires-at "2099-12-31T23:59:59Z"})]
      (is (= (:authorisation/hash auth)
             (:authorisation/hash recomputed))
          "rebuilding with same inputs must produce same hash"))

    ;; ── Usability lifecycle ───────────────────────────────────────────
    (is (:usable? summary-fresh) "fresh auth must be usable")
    (is (not (:usable? summary-consumed)) "consumed auth must not be usable")
    (is (not (:usable? summary-expired)) "expired auth must not be usable")

    ;; ── Immutable artifact unaffected by consumption ───────────────────
    (is (:valid? (rfa/validate-authorisation auth)))
    (is (some? (:authorisation/hash auth)))
    (is (rfa/authorisation-approved? auth)
        "approval status is immutable — consumption does not erase it")))

;; ── Scenario: declined (1 approve, 2 dissent) ───────────────────────────

(deftest lifecycle-declined
  (let [auth (fa-build-auth! :authorisation/fa-declined
              [{:researcher/id "researcher-a" :decision :approve}
               {:researcher/id "researcher-b" :decision :dissent
                :dissent/reason "methodology concern"}
               {:researcher/id "researcher-c" :decision :dissent
                :dissent/reason "insufficient evidence"}])
        summary (fa-lifecycle-summary auth)]
    (is (= :declined (:decision-status summary)))
    (is (not (:approved? summary)))
    (is (= {:required 2 :eligible 3 :approved 1 :dissented 2}
           (:threshold summary)))
    (is (= 3 (count (get-in auth [:authorisation/decision-references]))))
    (is (some? (:authorisation-hash summary)))))

;; ── Tamper detection ────────────────────────────────────────────────────

(deftest lifecycle-tamper-detection
  (let [authorisation-id :authorisation/fa-tamper
        auth (fa-build-auth! authorisation-id
              [{:researcher/id "researcher-a" :decision :approve}
               {:researcher/id "researcher-b" :decision :approve}
               {:researcher/id "researcher-c" :decision :dissent
                :dissent/reason "original concern"}])
        sig-resolver (fn [id] (:public-key-path (fa-key-for id)))]

    ;; ── Baseline: valid before tamper ─────────────────────────────────
    (let [sigs-ok (rfa/verify-decision-signatures sig-resolver auth)]
      (is (:valid? sigs-ok) "all three signatures valid before tamper"))

    ;; ── Tamper 1: mutate decision from dissent to approve ─────────────
    (let [tampered-decisions
          (mapv (fn [d]
                  (if (and (= (:researcher/id d) "researcher-c")
                           (= :dissent (:decision d)))
                    (-> d
                        (assoc :decision :approve)
                        (dissoc :dissent/reason))
                    d))
                (get-in auth [:authorisation/decision-references]))
          tampered-auth (assoc auth
                               :authorisation/decision-references
                               tampered-decisions)
          sigs-tampered (rfa/verify-decision-signatures
                         sig-resolver tampered-auth)]
      (is (not (:valid? sigs-tampered)) "tampered signature must fail")
      (let [researcher-c-result
            (first (filter #(= "researcher-c" (:researcher/id %))
                           (:results sigs-tampered)))]
        (is (some? researcher-c-result))
        (is (not (:valid? researcher-c-result)))
        (is (= "decision/hash mismatch"
               (:reason researcher-c-result)))
        (is (every? :valid?
                    (remove #(= "researcher-c" (:researcher/id %))
                            (:results sigs-tampered)))
            "other two researchers' signatures must remain valid")))

    ;; ── Tamper 2: rebind target to different proposed root ────────────
    (let [tampered-target (assoc-in auth
                            [:authorisation/target
                             :target/proposed-content-root]
                            "sha256:evil-proposed-root")
          scope-validator (fn [a]
                            (when-not (= (:target/proposed-content-root
                                          (:authorisation/target a))
                                         (:target/proposed-content-root fa-target))
                              "proposed-content-root mismatch"))
          usable (rfa/verify-authorisation-usable
                  tampered-target
                  :scope-validator scope-validator)]
      (is (not (:usable? usable))
          "tampered target must fail usability scope check")
      (is (some #(re-find #"scope mismatch" %)
                (:blocking-reasons usable))))

    ;; ── Tamper 3: change request root ─────────────────────────────────
    (let [tampered-auth (assoc auth
                               :authorisation/request-root
                               "sha256:different-request")
          usable (rfa/verify-authorisation-usable tampered-auth)]
      ;; Changing request root doesn't affect signatures (it's not in the
      ;; decision preimage) but affects the structural commitment.
      ;; validate-authorisation should still pass if the hash is recomputed,
      ;; but the stored hash will mismatch.
      (let [v (rfa/validate-authorisation tampered-auth)]
         (is (not (:valid? v))
             "changed request root must invalidate the hash commitment")))))

;; ═══════════════════════════════════════════════════════════════════════════
;; 8. Force-authorisation consumption and manifest binding
;; ═══════════════════════════════════════════════════════════════════════════

(defn- fa-build-consumption-auth!
  "Build a 3-approve FA artifact for consumption tests."
  [authorisation-id]
  (fa-build-auth! authorisation-id
    [{:researcher/id "researcher-a" :decision :approve}
     {:researcher/id "researcher-b" :decision :approve}
     {:researcher/id "researcher-c" :decision :approve}]))

(def ^:private fa-consumption-cmd-root "sha256:execution-command")
(def ^:private fa-consumption-plan-root "sha256:execution-plan")
(def ^:private fa-consumption-attempt-id :execution/fa-consumption-test)
(def ^:private fa-consumption-executed-root
  (get-in fa-target [:target/proposed-content-root]))

(defn- fa-consume-flow
  "Simulate the reserve-execute-finalise consumption flow.
   Acyclic artifact order:
     1. build-reservation (pre-execution, before manifest)
     2. outcome manifest references reservation-hash
     3. build-consumption-receipt (post-execution, references outcome hash)

   Returns {:reservation map
            :outcome-manifest map
            :consumption-receipt map}"
  [registration auth command-root plan-root executed-content-root attempt-id
   & {:keys [fail-after-reservation?]
      :or {fail-after-reservation? false}}]
  ;; 1. Verify usable
  (let [check (rfa/verify-authorisation-usable
               auth :consumption-checker
               #(rfa/registration-consumed? registration %))]
    (when-not (:usable? check)
      (throw (ex-info "Authorisation not usable" {:check check}))))
  ;; 2. Reserve atomically
  (let [reserve (rfa/reserve-consumption! registration
                                          (rfa/consumption-key auth))]
    (when-not (:reserved? reserve)
      (throw (ex-info "Reservation failed" {:reserve reserve}))))
  ;; 3. Build reservation artifact (pre-execution)
  (let [reservation (rfa/build-reservation
                     {:reservation/authorisation-hash (:authorisation/hash auth)
                      :reservation/consumption-key (rfa/consumption-key auth)
                      :reservation/execution-attempt-id attempt-id
                      :reservation/command-root command-root
                      :reservation/plan-root plan-root})]
    ;; 4. Simulate execution (or fail)
    (let [fail? fail-after-reservation?
          resulting-outcome-hash (if fail? "sha256:failed-outcome"
                                    (str "sha256:"
                                         (hc/domain-hash :benchmark-outcome
                                                         {:type :executed
                                                          :plan plan-root
                                                          :content executed-content-root})))
          status (if fail? :failed-after-consumption :consumed)
          ;; 5. Build outcome manifest (references reservation, NOT receipt)
          fa-section {:authorisation-hash (:authorisation/hash auth)
                       :consumption-key (rfa/consumption-key auth)
                       :reservation-hash (:reservation/hash reservation)
                       :execution-attempt-id attempt-id
                       :branch-descriptor-hash
                       (get-in auth [:authorisation/target
                                     :target/branch-descriptor-hash])
                       :baseline-content-root
                       (get-in auth [:authorisation/target
                                     :target/baseline-content-root])
                       :executed-content-root executed-content-root
                       :status status}
          manifest (om/build-manifest
                    (assoc base-input
                           :execution/plan-root plan-root
                           :execution/command-root command-root
                           :execution/force-authorisation fa-section
                           :benchmark/content-root
                           (get-in auth [:authorisation/target
                                         :target/baseline-content-root])))
          ;; 6. Build terminal consumption receipt (references outcome hash)
          receipt (rfa/build-consumption-receipt
                   (cond-> {:consumption/reservation-hash (:reservation/hash reservation)
                            :consumption/authorisation-hash (:authorisation/hash auth)
                            :consumption/consumption-key (rfa/consumption-key auth)
                            :consumption/resulting-outcome-hash
                            (:benchmark-outcome/hash manifest)
                            :consumption/status status}
                     (= :failed-after-consumption status)
                     (assoc :consumption/terminal-evidence-hash
                            (str "sha256:" (hc/domain-hash :evidence-collection
                                                           {:status :not-captured
                                                            :reason-code
                                                             :simulated-failure})))))
          _ (rfa/finalise-consumption! registration (rfa/consumption-key auth)
                                        status)]
      {:reservation reservation
       :outcome-manifest manifest
       :consumption-receipt receipt})))

;; ── 8a. Successful authorised execution ─────────────────────────────────

(deftest consumption-successful-execution
  (let [reg (atom {})
        auth (fa-build-consumption-auth! :authorisation/fa-consumption-success)
        result (fa-consume-flow reg auth
                 fa-consumption-cmd-root fa-consumption-plan-root
                 fa-consumption-executed-root fa-consumption-attempt-id)
        fa-sec (get-in result [:outcome-manifest :execution/force-authorisation])]
    ;; Reservation is valid and bound to auth
    (is (rfa/reservation-valid? (:reservation result)))
    (is (= (:authorisation/hash auth)
           (:reservation/authorisation-hash (:reservation result))))
    ;; Manifest is valid and references reservation
    (is (om/manifest-valid? (:outcome-manifest result)))
    (is (some? fa-sec))
    (is (= (:authorisation/hash auth) (:authorisation-hash fa-sec)))
    (is (= (:reservation/hash (:reservation result))
           (:reservation-hash fa-sec)))
    (is (= :consumed (:status fa-sec)))
    ;; Consumption receipt is valid and references outcome
    (is (rfa/receipt-valid? (:consumption-receipt result)))
    (is (= (:reservation/hash (:reservation result))
           (:consumption/reservation-hash (:consumption-receipt result))))
    (is (= (:benchmark-outcome/hash (:outcome-manifest result))
           (:consumption/resulting-outcome-hash (:consumption-receipt result))))
    ;; Registration records terminal status
    (is (rfa/registration-consumed? reg (rfa/consumption-key auth)))
    ;; Authorisation remains structurally valid
    (is (rfa/authorisation-valid? auth))
    (is (rfa/authorisation-approved? auth))
    ;; Usability now false due to consumption
    (let [consume-checker (fn [ck] (rfa/registration-consumed? reg ck))]
      (is (not (:usable?
                (rfa/verify-authorisation-usable
                 auth :consumption-checker consume-checker)))))))

;; ── 8b. Tampered field fails cross-artifact reconciliation ──────────────

(deftest consumption-tampered-field-fails-reconciliation
  (let [reg (atom {})
        auth (fa-build-consumption-auth! :authorisation/fa-consumption-tamper)
        result (fa-consume-flow reg auth
                 fa-consumption-cmd-root fa-consumption-plan-root
                 fa-consumption-executed-root fa-consumption-attempt-id)
        valid-manifest (:outcome-manifest result)
        ;; Build a fresh manifest with a WRONG authorisation-hash.
        ;; The manifest self-hash will be valid (build-manifest recomputes it),
        ;; but cross-artifact verification must catch the mismatch.
        tampered-fa (assoc (get-in valid-manifest
                                   [:execution/force-authorisation])
                           :authorisation-hash "sha256:different-auth")
        tampered-valid-manifest (om/build-manifest
                                 (assoc base-input
                                        :execution/plan-root fa-consumption-plan-root
                                        :execution/command-root fa-consumption-cmd-root
                                        :execution/force-authorisation tampered-fa
                                        :benchmark/content-root
                                        (:baseline-content-root tampered-fa)))]
    ;; 1. The tampered manifest is internally self-consistent
    (is (om/manifest-valid? tampered-valid-manifest)
        "tampered manifest with recomputed hash is internally valid")
    ;; 2. Cross-artifact verification catches the mismatch
    (let [binding (rfa/verify-fa-binding tampered-valid-manifest auth
                                          (:reservation result))]
      (is (not (:consistent? binding))
          "cross-artifact binding must fail for wrong authorisation-hash")
      (is (some #(= :authorisation-hash (:field %)) (:mismatches binding))))
    ;; 3. Same structure for consumption-key tamper
    (let [tampered-fa (assoc (get-in valid-manifest
                                     [:execution/force-authorisation])
                             :consumption-key "sha256:different-key")
          tampered-valid-manifest (om/build-manifest
                                   (assoc base-input
                                          :execution/plan-root fa-consumption-plan-root
                                          :execution/command-root fa-consumption-cmd-root
                                          :execution/force-authorisation tampered-fa
                                          :benchmark/content-root (:baseline-content-root tampered-fa)))]
      (is (om/manifest-valid? tampered-valid-manifest)
          "internally valid despite wrong consumption-key")
      (let [binding (rfa/verify-fa-binding tampered-valid-manifest auth
                                            (:reservation result))]
        (is (not (:consistent? binding)))
        (is (some #(= :consumption-key (:field %)) (:mismatches binding)))))
    ;; 4. Tampering executed-content-root changes the manifest hash
    ;;    (different executed-content-root = different manifest content)
    (let [tampered-fa (assoc (get-in valid-manifest
                                     [:execution/force-authorisation])
                             :executed-content-root "sha256:evil-root")
          tampered-valid-manifest (om/build-manifest
                                   (assoc base-input
                                          :execution/plan-root fa-consumption-plan-root
                                          :execution/command-root fa-consumption-cmd-root
                                          :execution/force-authorisation tampered-fa
                                          :benchmark/content-root (:baseline-content-root tampered-fa)))]
      ;; Even though self-valid, the executed-content-root differs from the
      ;; one recorded in the reservation and receipt — cross-check against
      ;; the original reservation catches this
      (is (om/manifest-valid? tampered-valid-manifest))
      (is (not= (:executed-content-root
                 (get-in valid-manifest [:execution/force-authorisation]))
                (:executed-content-root
                 (get-in tampered-valid-manifest [:execution/force-authorisation])))
          "manifests differ in executed-content-root"))))

;; ── 8c. Second execution with same consumption key rejected ────────────

(deftest consumption-second-execution-rejected
  (let [reg (atom {})
        auth (fa-build-consumption-auth! :authorisation/fa-consumption-second)]
    ;; First execution succeeds
    (let [r1 (fa-consume-flow reg auth
               fa-consumption-cmd-root fa-consumption-plan-root
               fa-consumption-executed-root fa-consumption-attempt-id)]
      (is (some? r1) "first execution must succeed"))
    ;; Second execution with same auth must fail before side effects
    (let [usable-check (rfa/verify-authorisation-usable
                        auth :consumption-checker
                        #(rfa/registration-consumed? reg %))]
      (is (not (:usable? usable-check))
          "authorisation must not be usable after consumption")
      (is (some #(re-find #"consumed" %) (:blocking-reasons usable-check))
          "blocking reason must mention consumption"))
    ;; Reserve must also fail
    (let [reserve (rfa/reserve-consumption! reg (rfa/consumption-key auth))]
      (is (not (:reserved? reserve))
          "reserve must fail for already-consumed key"))))

;; ── 8d. Failed execution after reservation ─────────────────────────────

(deftest consumption-failed-after-reservation
  (let [reg (atom {})
        auth (fa-build-consumption-auth! :authorisation/fa-consumption-fail)]
    ;; Execution fails after reservation
    (let [result (fa-consume-flow reg auth
                   fa-consumption-cmd-root fa-consumption-plan-root
                   fa-consumption-executed-root fa-consumption-attempt-id
                   :fail-after-reservation? true)]
      (is (some? result) "flow must complete with failed status")
      (let [fa-sec (get-in result [:outcome-manifest
                                    :execution/force-authorisation])
            receipt (:consumption-receipt result)]
        (is (= :failed-after-consumption (:status fa-sec))
            "status must be :failed-after-consumption")
        (is (= :failed-after-consumption (:consumption/status receipt))
            "receipt must record :failed-after-consumption")
        (is (rfa/receipt-valid? receipt))))
    ;; Key is consumed (terminal), so subsequent fresh execution is rejected
    (let [usable-check (rfa/verify-authorisation-usable
                        auth :consumption-checker
                        #(rfa/registration-consumed? reg %))]
      (is (not (:usable? usable-check))
          "authorisation must not be usable after :failed-after-consumption"))
    ;; Authorisation artifact is still structurally valid
    (is (rfa/authorisation-valid? auth)
        "authorisation remains structurally valid after failed consumption")
    (is (rfa/authorisation-approved? auth)
        "approval status is immutable even after consumption failure")))

;; ── 8e. Normal (non-FA) outcome omits force-authorisation ──────────────

(deftest consumption-normal-outcome-omits-fa
  (let [manifest (om/build-manifest base-input)]
    (is (om/manifest-valid? manifest))
    (is (nil? (:execution/force-authorisation manifest))
        "normal outcome must not contain :execution/force-authorisation")))

;; ── 8f. FA outcome replication classification ──────────────────────────

(deftest consumption-fa-replication-classification
  (let [reg (atom {})
        auth (fa-build-consumption-auth! :authorisation/fa-consumption-classify)
        result (fa-consume-flow reg auth
                 fa-consumption-cmd-root fa-consumption-plan-root
                 fa-consumption-executed-root fa-consumption-attempt-id)
        fa-manifest (:outcome-manifest result)
        baseline (om/build-manifest
                   (assoc base-input
                          :execution/plan-root fa-consumption-plan-root
                          :execution/command-root fa-consumption-cmd-root))
        fa-section (get-in fa-manifest [:execution/force-authorisation])]
    ;; FA manifest has different content-root and plan-root from baseline
    (is (not (om/exact-replication-scope? fa-manifest baseline))
        "FA manifest with different content-root must not be exact replication")
    ;; Same execution scope + same FA section = exact replication
    (let [same-scope-baseline (om/build-manifest
                                (assoc base-input
                                       :execution/plan-root fa-consumption-plan-root
                                       :execution/command-root fa-consumption-cmd-root
                                       :benchmark/content-root
                                       (:baseline-content-root fa-section)
                                       :execution/force-authorisation fa-section))]
      (is (om/exact-replication-scope? fa-manifest same-scope-baseline)
          "same execution fields + same FA section = exact replication scope"))
    ;; Same execution scope but different FA section = NOT exact replication
    (let [diff-fa (assoc fa-section :executed-content-root
                         "sha256:different-execution")
          diff-fa-manifest (om/build-manifest
                            (assoc base-input
                                   :execution/plan-root fa-consumption-plan-root
                                   :execution/command-root fa-consumption-cmd-root
                                   :benchmark/content-root
                                   (:baseline-content-root fa-section)
                                   :execution/force-authorisation diff-fa))]
      (is (not (om/exact-replication-scope? fa-manifest diff-fa-manifest))
          "different :execution/force-authorisation = not exact replication"))))

;; ═══════════════════════════════════════════════════════════════════════════
;; 9. Force-authorised execution evidence profile
;; ═══════════════════════════════════════════════════════════════════════════

(defn- fa-ev-resolver
  "Mock public-key-resolver for evidence profile tests."
  [researcher-id]
  (:public-key-path (fa-key-for researcher-id)))

(defn- fa-ev-build!
  "Build an evidence profile from the result of fa-consume-flow."
  [auth flow-result & {:keys [override-auth override-policy override-round
                              override-reservation override-manifest override-receipt]
                       :or {override-auth nil override-policy nil
                            override-round nil override-reservation nil
                            override-manifest nil override-receipt nil}}]
  (fa-ev/build-force-authorised-execution-evidence
   {:authorisation (or override-auth auth)
    :policy (or override-policy fa-policy-artifact)
    :review-round (or override-round fa-round-artifact)
    :reservation (or override-reservation (:reservation flow-result))
    :outcome-manifest (or override-manifest (:outcome-manifest flow-result))
    :consumption-receipt (or override-receipt (:consumption-receipt flow-result))
    :public-key-resolver fa-ev-resolver}))

;; ── 9a. Valid consumed execution ────────────────────────────────────────

(deftest evidence-profile-valid-consumed
  (let [reg (atom {})
        auth (fa-build-consumption-auth! :authorisation/fa-ev-valid)
        flow (fa-consume-flow reg auth
               fa-consumption-cmd-root fa-consumption-plan-root
               fa-consumption-executed-root fa-consumption-attempt-id)
        profile (fa-ev-build! auth flow)]
    ;; Profile is structurally valid
    (is (some? (:evidence-profile/hash profile)))
    (is (:valid? (fa-ev/validate-force-authorised-execution-evidence profile)))
    ;; Verification map is all-true for valid flow
    (let [v (:evidence-profile/verification profile)]
      (is (:authorisation-valid? v))
      (is (:decision-signatures-valid? v))
      (is (:policy-binding-valid? v))
      (is (:review-round-binding-valid? v))
      (is (:manifest-binding-valid? v))
      (is (:receipt-binding-valid? v)))
    ;; Execution result
    (let [er (:evidence-profile/execution-result profile)]
      (is (= :consumed (:terminal-status er)))
      (is (:outcome-produced? er))
      (is (:successful-authorised-outcome? er)))
    ;; Direct reference fields present
    (is (some? (:evidence-profile/policy-hash profile)))
    (is (some? (:evidence-profile/review-round-hash profile)))
    ;; Independent verifier recomputes and matches
    (let [verify-result (fa-ev/verify-force-authorised-execution-evidence
                         profile
                         {:authorisation auth
                          :policy fa-policy-artifact
                          :review-round fa-round-artifact
                          :reservation (:reservation flow)
                          :outcome-manifest (:outcome-manifest flow)
                          :consumption-receipt (:consumption-receipt flow)
                          :public-key-resolver fa-ev-resolver})]
      (is (:valid? verify-result))
      (is (empty? (:mismatches verify-result))))))

;; ── 9b. Failed-after-consumption with terminal evidence ────────────────

(deftest evidence-profile-failed-after-consumption
  (let [reg (atom {})
        auth (fa-build-consumption-auth! :authorisation/fa-ev-fail)
        flow (fa-consume-flow reg auth
               fa-consumption-cmd-root fa-consumption-plan-root
               fa-consumption-executed-root fa-consumption-attempt-id
               :fail-after-reservation? true)
        profile (fa-ev-build! auth flow)]
    (is (some? (:evidence-profile/hash profile)))
    (let [v (:evidence-profile/verification profile)]
      (is (:authorisation-valid? v))
      (is (:decision-signatures-valid? v)))
    (let [er (:evidence-profile/execution-result profile)]
      (is (= :failed-after-consumption (:terminal-status er)))
      (is (:outcome-produced? er))
      (is (not (:successful-authorised-outcome? er))))))

;; ── 9c. Missing artifact fails with precise reason ─────────────────────

(deftest evidence-profile-missing-artifact
  (let [reg (atom {})
        auth (fa-build-consumption-auth! :authorisation/fa-ev-missing)
        flow (fa-consume-flow reg auth
               fa-consumption-cmd-root fa-consumption-plan-root
               fa-consumption-executed-root fa-consumption-attempt-id)
        base-args {:policy fa-policy-artifact
                   :review-round fa-round-artifact
                   :reservation (:reservation flow)
                   :outcome-manifest (:outcome-manifest flow)
                   :consumption-receipt (:consumption-receipt flow)
                   :public-key-resolver fa-ev-resolver}]
    ;; Missing authorisation
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Evidence profile"
          (fa-ev/build-force-authorised-execution-evidence
           (assoc base-args :authorisation nil))))
    ;; Missing policy
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Evidence profile"
          (fa-ev/build-force-authorised-execution-evidence
           (assoc base-args :authorisation auth :policy nil))))
    ;; Missing review-round
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Evidence profile"
          (fa-ev/build-force-authorised-execution-evidence
           (assoc base-args :authorisation auth :review-round nil))))
    ;; Missing reservation
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Evidence profile"
          (fa-ev/build-force-authorised-execution-evidence
           (assoc base-args :authorisation auth :reservation nil))))
    ;; Missing outcome-manifest
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Evidence profile"
          (fa-ev/build-force-authorised-execution-evidence
           (assoc base-args :authorisation auth :outcome-manifest nil))))
    ;; Missing consumption-receipt
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Evidence profile"
          (fa-ev/build-force-authorised-execution-evidence
           (assoc base-args :authorisation auth :consumption-receipt nil))))))

;; ── 9d. Wrong manifest fails recomputation ─────────────────────────────

(deftest evidence-profile-wrong-manifest
  (let [reg (atom {})
        auth (fa-build-consumption-auth! :authorisation/fa-ev-wrong-mf)
        flow (fa-consume-flow reg auth
               fa-consumption-cmd-root fa-consumption-plan-root
               fa-consumption-executed-root fa-consumption-attempt-id)
        profile (fa-ev-build! auth flow)
        ;; Build a DIFFERENT manifest (different content-root)
        other-manifest (om/build-manifest
                        (assoc base-input
                               :benchmark/content-root "sha256:different-content"
                               :execution/plan-root "sha256:other-plan"))]
    ;; Self-consistent profile with wrong manifest
    (is (some? (:evidence-profile/hash profile)))
    (let [verify-result (fa-ev/verify-force-authorised-execution-evidence
                         profile
                         {:authorisation auth
                          :policy fa-policy-artifact
                          :review-round fa-round-artifact
                          :reservation (:reservation flow)
                          :outcome-manifest other-manifest
                          :consumption-receipt (:consumption-receipt flow)
                          :public-key-resolver fa-ev-resolver})]
      (is (not (:valid? verify-result)))
      (is (some #(= :evidence-profile/hash (:field %))
                (:mismatches verify-result))))))

;; ── 9e. Forged verification map fails ──────────────────────────────────

(deftest evidence-profile-forged-verification
  (let [reg (atom {})
        auth (fa-build-consumption-auth! :authorisation/fa-ev-forged)
        flow (fa-consume-flow reg auth
               fa-consumption-cmd-root fa-consumption-plan-root
               fa-consumption-executed-root fa-consumption-attempt-id)
        profile (fa-ev-build! auth flow)
        ;; Forge: alter a verification value and recompute the profile hash
        forged-ver (assoc (:evidence-profile/verification profile)
                         :decision-signatures-valid? false)
        forged-profile (assoc profile
                              :evidence-profile/verification forged-ver)
        ;; Recompute the profile's own hash so it's self-consistent
        without-hash (dissoc forged-profile :evidence-profile/hash)
        recomputed-hash (str "sha256:"
                              (hc/domain-hash
                               :force-authorised-execution-evidence
                               without-hash))
        forged-self-consistent (assoc forged-profile
                                      :evidence-profile/hash
                                      recomputed-hash)]
    ;; The forged profile is structurally self-consistent
    (is (:valid? (fa-ev/validate-force-authorised-execution-evidence
                  forged-self-consistent)))
    ;; Independent recomputation detects the forgery
    (let [verify-result (fa-ev/verify-force-authorised-execution-evidence
                         forged-self-consistent
                         {:authorisation auth
                          :policy fa-policy-artifact
                          :review-round fa-round-artifact
                          :reservation (:reservation flow)
                          :outcome-manifest (:outcome-manifest flow)
                          :consumption-receipt (:consumption-receipt flow)
                          :public-key-resolver fa-ev-resolver})]
      (is (not (:valid? verify-result)))
      (is (some #(= :decision-signatures-valid? (:field %))
                (:mismatches verify-result))))))

;; ── 9f. FA manifest without profile fails package verification ─────────

(deftest evidence-profile-missing-from-package
  (let [reg (atom {})
        auth (fa-build-consumption-auth! :authorisation/fa-ev-pkg)
        flow (fa-consume-flow reg auth
               fa-consumption-cmd-root fa-consumption-plan-root
               fa-consumption-executed-root fa-consumption-attempt-id)
        manifest (:outcome-manifest flow)
        ;; Package resolver that returns nil for everything
        nil-resolver (fn [_] nil)]
    ;; Package verification fails because no artifacts can be resolved
    (let [result (fa-ev/verify-package-force-authorised-execution
                  nil-resolver nil manifest)]
      (is (not (:valid? result)))
      (is (some #(re-find #"authorisation" %) (:errors result))))))

;; ── 9g. Normal manifest requires no profile ────────────────────────────

(deftest evidence-profile-normal-manifest-no-profile
  (let [manifest (om/build-manifest base-input)]
    (is (not (fa-ev/package-requires-evidence-profile? manifest)))
    ;; The FA section is nil — no FA artifacts or profile needed
    (is (nil? (:execution/force-authorisation manifest)))))

;; ── 9h. Changing referenced hashes changes profile hash ────────────────

(deftest evidence-profile-hash-sensitivity
  (let [reg (atom {})
        auth (fa-build-consumption-auth! :authorisation/fa-ev-hash)
        flow (fa-consume-flow reg auth
               fa-consumption-cmd-root fa-consumption-plan-root
               fa-consumption-executed-root fa-consumption-attempt-id)
        profile (fa-ev-build! auth flow)]
    ;; Profile with different auth-hash fails verification
    (let [profile2 (fa-ev-build! auth flow
                     :override-auth (assoc auth
                                           :authorisation/hash
                                           "sha256:different-auth"))]
      (is (not= (:evidence-profile/hash profile)
                (:evidence-profile/hash profile2))
          "different auth-hash must produce different profile hash"))
    ;; Profile with different reservation-hash fails verification
    (let [diff-res (assoc (:reservation flow)
                          :reservation/hash "sha256:different-res")
          profile3 (fa-ev-build! auth flow
                    :override-reservation diff-res)]
      (is (not= (:evidence-profile/hash profile)
                (:evidence-profile/hash profile3))
          "different reservation-hash must produce different profile hash"))
    ;; Package verification succeeds with correct artifacts
    (let [artifact-index {(:authorisation/hash auth) auth
                          (:policy/hash (:authorisation/policy auth))
                          fa-policy-artifact
                          (:review-round/hash
                           (:authorisation/review-round auth))
                          fa-round-artifact
                          (:reservation/hash (:reservation flow))
                          (:reservation flow)
                          (:consumption/hash (:consumption-receipt flow))
                          (:consumption-receipt flow)}
          resolver (fn [hash] (get artifact-index hash))
          pkg-result (fa-ev/verify-package-force-authorised-execution
                      resolver profile (:outcome-manifest flow)
                      :public-key-resolver fa-ev-resolver)]
      (is (:valid? pkg-result))
      (is (get-in pkg-result [:checks :evidence-recomputed?]))
      (is (get-in pkg-result [:checks :profile-hash-match?])))))
