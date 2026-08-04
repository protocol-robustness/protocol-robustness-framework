(ns resolver-sim.conformance.bundle-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.conformance.bundle :as bundle]
            [resolver-sim.conformance.reconciliation :as rec]
            [resolver-sim.conformance.coverage :as cov]
            [resolver-sim.conformance.claim :as claim]
            [resolver-sim.conformance.environment :as environment]))

(defn- standard-evidence []
  (let [reconciliation (rec/reconcile
                        {:plan/root "sha256:plan"
                         :steps [{:step/id :replay :requires [] :produces [:replay-receipt]}]}
                        [{:step/id :replay :subject/id "a" :subject/root "sha256:a"
                          :subject-set/root "sha256:g" :status :pass}]
                        {:subject-set/root "sha256:g" :subjects ["a"]})
        coverage (cov/coverage-receipt
                  {:universe-root "sha256:u"
                   :required-subjects ["a"]
                   :validated-subjects ["a"]
                   :executed-subjects ["a"]
                   :compared-subjects ["a"]
                   :excluded-subjects []})
        claim (claim/claim-with-evidence
               coverage reconciliation
               (claim/claim-result :attested :attested :pass {}))]
    {:reconciliation reconciliation
     :coverage coverage
     :claim claim}))

(deftest bundle-builds-and-verifies
  (let [ev (standard-evidence)
        b (bundle/build-bundle
           {:profile {:profile/id :sew-trace-equivalence.v1}
            :environment (environment/environment
                          {:canonicalisation/id :prf-canonical-edn.v1})
            :plan {:plan/root "sha256:plan" :environment/root (environment/current-environment-root)}
            :reconciliation (:reconciliation ev)
            :coverage (:coverage ev)
            :claim (:claim ev)})
        v (bundle/verify-bundle b)]
    (is (= "conformance.bundle/v1" (:bundle/schema-version b)))
    (is (string? (:bundle/root b)))
    (is (= :pass (:status v)))
    (is (:claimable? v))
    (is (empty? (:issues v)))))

(deftest bundle-removed-claim-derived-identically
  (testing "removing the supplied claim lets the verifier derive the identical claim root"
    (let [ev (standard-evidence)
          b (bundle/build-bundle
             {:profile {:profile/id :sew-trace-equivalence.v1}
              :environment (environment/environment {:canonicalisation/id :prf-canonical-edn.v1})
              :plan {:plan/root "sha256:plan" :environment/root (environment/current-environment-root)}
              :reconciliation (:reconciliation ev)
              :coverage (:coverage ev)
              :claim nil})
          derived (bundle/derive-claim-from-bundle b)]
      (is (= (:claim/class (claim/claim-result :attested :attested :pass {}))
             (:claim/class derived)))
      (is (= (:reconciliation/root (:reconciliation ev)) (:reconciliation/root derived))))))

(deftest bundle-tampered-claim-rejected
  (testing "tampering the supplied claim is rejected even though the receipts pass"
    (let [ev (standard-evidence)
          tampered (assoc-in (:claim ev) [:claim/class] :reproduced)
          b (bundle/build-bundle
             {:profile {:profile/id :sew-trace-equivalence.v1}
              :environment (environment/environment {:canonicalisation/id :prf-canonical-edn.v1})
              :plan {:plan/root "sha256:plan" :environment/root (environment/current-environment-root)}
              :reconciliation (:reconciliation ev)
              :coverage (:coverage ev)
              :claim tampered})
          v (bundle/verify-bundle b)]
      (is (= :rejected (:status v)))
      (is (not (:claimable? v)))
      (is (some #(= :derived-claim-mismatch (:issue/code %)) (:issues v))))))

(deftest bundle-tampered-reconciliation-rejected
  (testing "tampering the reconciliation breaks root reproducibility"
    (let [ev (standard-evidence)
          bad-recon (assoc (:reconciliation ev) :reconciliation/root "sha256:tampered")
          b (bundle/build-bundle
             {:profile {:profile/id :sew-trace-equivalence.v1}
              :environment (environment/environment {:canonicalisation/id :prf-canonical-edn.v1})
              :plan {:plan/root "sha256:plan" :environment/root (environment/current-environment-root)}
              :reconciliation bad-recon
              :coverage (:coverage ev)
              :claim (:claim ev)})
          v (bundle/verify-bundle b)]
      (is (= :rejected (:status v)))
      (is (some #(= :reconciliation-not-reproducible (:issue/code %)) (:issues v))))))

(deftest bundle-unsupported-version-rejected
  (is (= :unsupported-version
         (:status (bundle/verify-bundle {:bundle/schema-version "conformance.bundle/v2"})))))
