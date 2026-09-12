(ns resolver-sim.assurance.namespace-load-test
  "Repository-level namespace load test.
   Ensures every production namespace in the core assurance, commands,
   evidence, forensic, and selected protocol layers compiles independently.
   Reports read-failure, compile-failure, and expected-public-var status
   separately so that a missing var in a dynamically-loaded namespace
   does not mask a compile error in another."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]))

(defn namespace-entries
  "Build the list of [ns-sym expected-public-vars] pairs at runtime
   to avoid AOT compiler resolution of symbol names containing dots."
  []
  [[(symbol "resolver-sim.assurance.trust-sequence-definition")
    '[build-definition validate-definition]]
   [(symbol "resolver-sim.assurance.procedure-evidence")
    '[correlation-id correlation-id-all-same? expected-evidence-type]]
   [(symbol "resolver-sim.assurance.procedure-execution-witness")
    '[build-witness]]
   [(symbol "resolver-sim.assurance.witness-verifier")
    '[verify-witness verify-witness-from-finalised-evidence
      build-evidence-index]]
   [(symbol "resolver-sim.assurance.custody")
    '[held-custody-closed-form-checks build-held-custody-artifact]]
   [(symbol "resolver-sim.assurance.force-authorisation")
    '[verify-authorisation-usable force-authorisation-scope-hash]]
   [(symbol "resolver-sim.assurance.canonical-force-authorisation")
    '[classify-profile reconcile-policy classify-representation
      schema-change-compatibility decision-context
      cancellation-possible? cancellation-window classify-cancellation
      classify-lifecycle-window cancellation-window-assertion
      validate-lifecycle-profile validate-lifecycle-monotonicity
      cancellation-decision-required? cancellation-binding-complete?
      cancellation-conflict-key cancellation-certificate-profile-conforming?
      current-snapshot-binding-valid? cancellation-executable?
      cancellation-committable? classify-cancellation-gates
      deterministic-operation-evidence deterministic-operation-evidence-valid?
      deterministic-operation-verified?]]
   [(symbol "resolver-sim.assurance.three-member-authority")
    '[decision-scope-projection equivocation-key
      incompatible-positions? detect-equivocation
      classify-equivocating-seat evaluate-three-member-authority
      position-valid? authority-report-root recompute-authority-report]]
   [(symbol "resolver-sim.commands.witness-build")
    '[build-and-write! canonical-witness-verification
      witness-requirement configured-root load-adapter]]
   [(symbol "resolver-sim.commands.run-lifecycle")
    '[build-run-envelope acquire-run-lock!]]
   [(symbol "resolver-sim.commands.run-benchmark") '[run run-with-root!]]
   [(symbol "resolver-sim.commands.benchmark-orchestration") '[run!]]
   [(symbol "resolver-sim.commands.benchmark-run")
    '[build-run-context initialize!]]
   [(symbol "resolver-sim.commands.scenario-registry") '[finalize!]]
   [(symbol "resolver-sim.commands.assure-package") '[run]]
   [(symbol "resolver-sim.evidence.chain")
    '[chain-link-hash verify-registry-hash verify-scenario-chain
      finalize-and-attest!]]
   [(symbol "resolver-sim.evidence.capture")
    '[finalize-evidence cap-field]]
   [(symbol "resolver-sim.evidence.config") '[artifact-dir schema]]
   [(symbol "resolver-sim.evidence.attestation-bundle")
    '[build-attestation-bundle verify-attestation-bundle]]
   [(symbol "resolver-sim.evidence.attestation-dag")
    '[build-attestation-dag-node emit-attestation-dag-node!]]
   [(symbol "resolver-sim.forensic.execution-dag")
    '[build-dag validate-persisted-dag]]
   [(symbol "resolver-sim.forensic.evidence-pack") '[pack! verify]]
   [(symbol "resolver-sim.run.package-index")
    '[build write! validate-completeness validate-integrity]]
   [(symbol "resolver-sim.run.bundle-root")
    '[build-bundle-root structurally-valid?]]
   [(symbol "resolver-sim.run.runner-finalization") '[build valid?]]
   [(symbol "resolver-sim.run.force-authorisation-policy")
    '[build validate verify-artifact default-research-policy]]
   [(symbol "resolver-sim.run.verdict-policy") '[build-artifact verify!]]
   [(symbol "resolver-sim.benchmark.runner")
    '[run-benchmark build-execution-plan]]
   [(symbol "resolver-sim.benchmark.content-registry-entry") '[build-entry]]
   [(symbol "resolver-sim.benchmark.signing") '[sign-hash]]
   [(symbol "resolver-sim.benchmark.researcher-force-authorisation")
    '[build-signed-decision build-signed-decision-v2
      verify-signed-decision verify-signed-decision-v2
      classify-decision-version decision-outcome-binding
      complete-outcome-verified? position-outcome-root
      authorisation-outcome-consistency]]
   [(symbol "resolver-sim.benchmark.decision-subject")
    '[build-decision-subject decision-subject-hash validate-decision-subject
      decision-subject-valid? verify-decision-subject-root
      subject-commitment-summary]]
   [(symbol "resolver-sim.benchmark.research-conclusion")
    '[build-conclusion validate-conclusion conclusion-valid?
      conclusion-overreaches? conclusion-collective-hash
      verify-conclusion-support valid-falsifier?]]
   [(symbol "resolver-sim.benchmark.review-round")
    '[build-review-round validate-round round-valid? member-keys]]
   [(symbol "resolver-sim.benchmark.review-aggregate-check")
    '[check-aggregate-member-bit-width check-aggregate-member-key-density
      check-aggregate-three-member-standard
      check-aggregate-three-member-classifications run-review-aggregate-checks]]
   [(symbol "resolver-sim.hash.canonical")
    '[hash-with-intent domain-hash domain-tags]]
   ;; Protocol-specific adapter — dynamically resolved at runtime
   [(symbol "resolver-sim.protocols.sew.procedure-evidence")
    '[sew-evidence-adapter]]
   ;; Allocation coprocessor round-state mapper (consumes cancellation vocabulary)
   [(symbol "resolver-sim.allocation.round-state")
    '[coprocessor-round-states lifecycle-target-state classify-round-state
      classify-round-cancellation cancellation-assertion]]
   [(symbol "resolver-sim.allocation.certificate")
    '[schema-version compose-certificate]]
   [(symbol "resolver-sim.allocation.claim-consumption-receipt")
    '[schema-version valid-claim-consumption-status?
      claim-consumption-conflict-key build-claim-consumption-receipt
      claim-consumption-receipt-valid? validate-claim-consumption-receipt]]])

(deftest all-production-namespaces-load
  (let [results (atom [])]
    (doseq [[ns-sym expected-vars] (namespace-entries)]
      (let [compile-status (try
                             (require ns-sym :reload)
                             nil
                             (catch java.io.FileNotFoundException e
                               (str "read-failed: " (.getMessage e)))
                             (catch Exception e
                               (str "compile-failed: " (.getMessage e))))
            ns-loaded? (nil? compile-status)
            var-results (when ns-loaded?
                          (mapv (fn [var-sym]
                                  (let [qualified (symbol (str ns-sym) (str var-sym))]
                                    (try
                                      (if (resolve qualified)
                                        (str var-sym ":present")
                                        (str var-sym ":absent"))
                                      (catch Exception _
                                        (str var-sym ":error")))))
                                expected-vars))]
        (swap! results conj {:namespace ns-sym
                             :compile-status (if (nil? compile-status) :ok compile-status)
                             :loaded? ns-loaded?
                             :var-statuses var-results})))
    (doseq [r @results]
      (testing (str (:namespace r))
        (is (= :ok (:compile-status r))
            (str "compile: " (:compile-status r)))
        (when (:loaded? r)
          (doseq [var-status (:var-statuses r)]
            (is (str/includes? var-status ":present")
                var-status)))))))