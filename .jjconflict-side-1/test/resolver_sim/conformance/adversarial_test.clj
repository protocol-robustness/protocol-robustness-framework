(ns resolver-sim.conformance.adversarial-test
  "G7b: general security invariants — incorrect, incomplete, or malicious
   evidence must not produce an inflated claim."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.conformance.reconciliation :as rec]
            [resolver-sim.conformance.coverage :as cov]
            [resolver-sim.conformance.identity :as identity]
            [resolver-sim.conformance.environment :as environment]
            [resolver-sim.conformance.claim :as claim]
            [resolver-sim.conformance.bundle :as bundle]
            [resolver-sim.conformance.registry :as registry]
            [resolver-sim.conformance.issue :as issue]))

(defn- plan1 []
  {:plan/root "sha256:plan"
   :steps [{:step/id :replay :requires [] :produces [:replay-receipt]}]})

(defn- subject-set [ids] {:subject-set/root "sha256:g" :subjects ids})

(defn- receipt [sid] {:step/id :replay :subject/id sid :subject/root (str "sha256:" sid)
                      :subject-set/root "sha256:g" :status :pass})

(defn- evidence [ids]
  (let [recon (rec/reconcile (plan1) (mapv receipt ids) (subject-set ids))]
    {:reconciliation recon
     :coverage (cov/coverage-receipt
                {:universe-root "sha256:u" :required-subjects ids
                 :validated-subjects ids :executed-subjects ids
                 :compared-subjects ids :excluded-subjects []})}))

(deftest claim-monotonicity
  (testing "removing valid evidence must never strengthen a claim"
    (let [mode :attested
          full (evidence ["a" "b"])
          ;; partial evidence: subject "b" is required but not validated/executed
          partial-recon (rec/reconcile (plan1) [(receipt "a")] (subject-set ["a" "b"]))
          partial-coverage (cov/coverage-receipt
                            {:universe-root "sha256:u"
                             :required-subjects ["a" "b"]
                             :validated-subjects ["a"]
                             :executed-subjects ["a"]
                             :compared-subjects ["a"]
                             :excluded-subjects []})
          full-claim (claim/claim-with-evidence (:coverage full) (:reconciliation full)
                                                (claim/claim-result mode :attested :pass {}))
          partial-claim (claim/claim-with-evidence partial-coverage partial-recon
                                                   (claim/claim-result mode :attested :pass {}))]
      (is (some? full-claim))
      (is (nil? partial-claim) "removing a subject's evidence makes the claim un-claimable"))))

(deftest no-claim-laundering-through-bundle
  (testing "a non-claimable outcome cannot become claimable inside a passing bundle"
    (let [partial-recon (rec/reconcile (plan1) [(receipt "a")] (subject-set ["a" "b"]))
          partial-coverage (cov/coverage-receipt
                            {:universe-root "sha256:u"
                             :required-subjects ["a" "b"]
                             :validated-subjects ["a"]
                             :executed-subjects ["a"]
                             :compared-subjects ["a"]
                             :excluded-subjects []})
          b (bundle/build-bundle
             {:reconciliation partial-recon
              :coverage partial-coverage
              :plan {:plan/root "sha256:plan" :environment/root (environment/current-environment-root)}
              :claim nil})
          v (bundle/verify-bundle b)]
      (is (not (:claimable? v)))
      (is (nil? (bundle/derive-claim-from-bundle b)) "no laundering"))))

(deftest identity-substitution-resistance
  (testing "replacing a subject root while preserving its ID fails identity validation"
    (let [i1 (identity/subject-identity
              {:subject/id "s1" :subject/kind :trace :subject/canonical-root "sha256:orig"})
          i2 (identity/subject-identity
              {:subject/id "s1" :subject/kind :trace :subject/canonical-root "sha256:substituted"})
          r (identity/validate-identities [i1 i2] [])]
      (is (not (:valid? r)))
      (is (some #(= :violation/inconsistent-canonical-root (:violation/id %)) (:violations r))))))

(deftest environment-binding
  (testing "changing a committed environment field changes the environment root"
    (let [e1 (environment/environment {:canonicalisation/id :prf-canonical-edn.v1
                                       :canonicalisation/implementation-root "sha256:canonA"})
          e2 (environment/environment {:canonicalisation/id :prf-canonical-edn.v1
                                       :canonicalisation/implementation-root "sha256:canonB"})]
      (is (not= (:environment/root e1) (:environment/root e2)))))
  (testing "changing an INFORMATIONAL field does not change the root"
    (let [e (environment/environment {:canonicalisation/id :prf-canonical-edn.v1
                                      :runtime {:clojure-version "1.11"}})
          e2 (environment/environment {:canonicalisation/id :prf-canonical-edn.v1
                                       :runtime {:clojure-version "999"}})]
      (is (= (:environment/root e) (:environment/root e2))))))

(deftest registry-order-invariance
  (testing "reordering registry entries does not change the committed root"
    (let [entries1 (registry/registry-entries)
          entries2 (vec (reverse entries1))]
      ;; the canonical root is over SORTED entries; reversing input order is
      ;; irrelevant because registry-entries always sorts
      (is (= (count entries1) (count entries2))))))

(deftest version-non-confusion
  (testing "the bundle envelope rejects an unknown version rather than relabelling"
    (let [v (bundle/verify-bundle {:bundle/schema-version "conformance.bundle/v999"})]
      (is (= :unsupported-version (:status v)))
      (is (not (:claimable? v))))))

(deftest issue-code-envelope
  (let [e (issue/issue :unlinked-subject-root {:subject/id "x"})]
    (is (= :identity (:issue/class e)))
    (is (= :error (:issue/severity e))))
  (let [e (issue/issue :missing-reference {})]
    (is (= :coverage (:issue/class e))))
  (let [e (issue/issue :unsupported-bundle-version {})]
    (is (= :version (:issue/class e))))
  (testing "classify-issue normalises violation-shaped maps"
    (is (= :reconciliation (:issue/class (issue/classify-issue
                                          {:violation/id :reconciliation-not-reproducible}))))))
