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
            [resolver-sim.hash.canonical :as hc]))

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
  (let [round {:benchmark/content-root "sha256:c" :review-round/id "rr:t"
               :review-round/members [{:researcher/id "a" :role :model-steward}
                                      {:researcher/id "b" :role :independent-reproducer}
                                      {:researcher/id "c" :role :adversarial-reviewer}]}
        result (rfa/build-authorisation
                {:review-round round
                 :target-case-hash "sha256:tgt"
                 :approvals [{:researcher/id "a" :signed-content-hash "sha256:s1"}]
                 :dissents []})]
    (is (= :blocked (:status result)) "1/3 approvals should be blocked")))

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

(deftest pre-condition-force-auth-non-member-approval-ignored
  (let [round {:benchmark/content-root "sha256:c" :review-round/id "rr:t"
               :review-round/members [{:researcher/id "a" :role :model-steward}
                                      {:researcher/id "b" :role :independent-reproducer}
                                      {:researcher/id "c" :role :adversarial-reviewer}]}
        result (rfa/build-authorisation
                {:review-round round
                 :target-case-hash "sha256:tgt"
                 :approvals [{:researcher/id "a" :signed-content-hash "sha256:s1"}
                             {:researcher/id "d" :signed-content-hash "sha256:s2"}]
                 :dissents []})]
    (is (= :blocked (:status result)) "non-member approval should not count to threshold")))

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
