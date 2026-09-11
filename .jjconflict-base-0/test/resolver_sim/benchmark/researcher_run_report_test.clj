(ns resolver-sim.benchmark.researcher-run-report-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.researcher-run-report :as rrr]
            [resolver-sim.benchmark.outcome-manifest :as om]))

(def sample-manifest
  (om/build-manifest
   {:benchmark/content-root "sha256:content"
    :benchmark/model-root "sha256:model"
    :benchmark/evaluation-policy-root "sha256:eval"
    :execution/parameter-domain-root "sha256:domain"
    :execution/sampling-policy-root "sha256:samp"
    :execution/generated-case-set-root "sha256:cases"
    :results/operational {:conservation :pass}}))

(def sample-runner-info
  {:runner/id :runner/default
   :source-tree-hash "sha256:tree"
   :distribution-hash "sha256:dist"
   :environment-hash "sha256:env"})

(def sample-evidence-refs
  {:evidence-dag-root "sha256:dag"
   :event-evidence-root "sha256:events"
   :execution-log-root "sha256:log"})

(deftest build-report-minimal
  (let [report (rrr/build-report
                {:outcome-manifest sample-manifest
                 :researcher-id "researcher-a"
                 :runner-info sample-runner-info
                 :evidence-refs sample-evidence-refs
                 :run-id "run-001"})]
    (is (rrr/report-valid? report))
    (is (= "researcher-a" (:researcher/id report)))
    (is (some? (:researcher-run-report/outcome-hash report)))))

(deftest outcome-hash-independent-of-researcher
  (let [a (rrr/build-report
           {:outcome-manifest sample-manifest
            :researcher-id "researcher-a"
            :runner-info sample-runner-info
            :evidence-refs sample-evidence-refs
            :run-id "run-001"})
        b (rrr/build-report
           {:outcome-manifest sample-manifest
            :researcher-id "researcher-b"
            :runner-info sample-runner-info
            :evidence-refs sample-evidence-refs
            :run-id "run-002"})]
    (is (= (:researcher-run-report/outcome-hash a)
           (:researcher-run-report/outcome-hash b)))
    (is (not= (:run/id a) (:run/id b)))))

(deftest report-execution-fields-match-manifest
  (let [report (rrr/build-report
                {:outcome-manifest sample-manifest
                 :researcher-id "researcher-a"
                 :runner-info sample-runner-info
                 :evidence-refs sample-evidence-refs
                 :run-id "run-001"})
        result (rrr/verify-against-manifest report sample-manifest)]
    (is (:valid? result))
    (is (:manifest-hash-match? result))
    (is (empty? (:mismatches result)))))

(deftest report-mismatch-detected
  (let [report (rrr/build-report
                {:outcome-manifest sample-manifest
                 :researcher-id "researcher-a"
                 :runner-info sample-runner-info
                 :evidence-refs sample-evidence-refs
                 :run-id "run-001"})
        other-manifest (om/build-manifest
                        (assoc sample-manifest
                               :execution/generated-case-set-root "sha256:other"))
        result (rrr/verify-against-manifest report other-manifest)]
    (is (not (:valid? result)))
    (is (seq (:mismatches result)))))

(deftest outcome-hash-matches-manifest
  (let [report (rrr/build-report
                {:outcome-manifest sample-manifest
                 :researcher-id "researcher-a"
                 :runner-info sample-runner-info
                 :evidence-refs sample-evidence-refs
                 :run-id "run-001"})]
    (is (= (:benchmark-outcome/hash sample-manifest)
           (:researcher-run-report/outcome-manifest-hash report)))))

(deftest validate-report-invalid-missing-id
  (is (not (:valid? (rrr/validate-report {:schema-version "researcher-run-report.v1"})))))

(deftest validate-report-valid
  (let [report (rrr/build-report
                {:outcome-manifest sample-manifest
                 :researcher-id "researcher-a"
                 :runner-info sample-runner-info
                 :evidence-refs sample-evidence-refs
                 :run-id "run-001"})]
    (is (:valid? (rrr/validate-report report)))))

(deftest mutated-outcome-hash-changes-report
  (let [report-a (rrr/build-report
                  {:outcome-manifest sample-manifest
                   :researcher-id "researcher-a"
                   :runner-info sample-runner-info
                   :evidence-refs sample-evidence-refs
                   :run-id "run-001"})
        diff-manifest (om/build-manifest
                       {:benchmark/content-root "sha256:other"
                        :benchmark/model-root "sha256:m"
                        :benchmark/evaluation-policy-root "sha256:e"})
        report-b (rrr/build-report
                  {:outcome-manifest diff-manifest
                   :researcher-id "researcher-a"
                   :runner-info sample-runner-info
                   :evidence-refs sample-evidence-refs
                   :run-id "run-001"})]
    (is (not= (:researcher-run-report/outcome-hash report-a)
              (:researcher-run-report/outcome-hash report-b)))))
