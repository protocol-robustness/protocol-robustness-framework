(ns resolver-sim.conformance.lifecycle-test
  "G5b: profile valid -> satisfiable -> executable lifecycle, hermetic
   environment receipt, and committed registry snapshot reconciliation."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [resolver-sim.conformance.profile :as profile]
            [resolver-sim.conformance.environment :as environment]
            [resolver-sim.conformance.registry :as registry]
            ;; load all domain adapters so implementations resolve
            [resolver-sim.trace.conformance.validators]
            [resolver-sim.benchmark.conformance.reproduction]
            [resolver-sim.evidence-package.conformance.admission]))

(defn- trace-profile [] (profile/load-profile "etc/conformance/profiles/sew-trace-equivalence.v1.edn"))
(defn- benchmark-profile [] (profile/load-profile "etc/conformance/profiles/research-benchmark-reproduction.v1.edn"))

(deftest profiles-satisfiable
  (doseq [p [(trace-profile) (benchmark-profile)]]
    (let [r (profile/profile-satisfiable? p)]
      (is (:satisfiable? r) (pr-str r))
      (is (string? (:implementation-registry/root r)))
      (is (seq (:resolved-implementations r)))
      (is (empty? (:missing-implementations r)))
      (is (empty? (:kind-mismatches r))))))

(deftest profile-executable-preflight
  (let [p (trace-profile)
        ok-subjects {:subject-set/root "sha256:g" :subjects ["sew-001"]}]
    (is (:executable? (profile/profile-executable? p ok-subjects :attested)))
    (testing "empty subject set is not executable"
      (is (not (:executable? (profile/profile-executable? p {:subject-set/root "sha256:g" :subjects []} :attested))))
      (is (some #(= :empty-subject-set (:reason %))
                (:reasons (profile/profile-executable? p {:subject-set/root "sha256:g" :subjects []} :attested)))))
    (testing "mode outside verdict policy is not executable"
      (let [r (profile/profile-executable? p ok-subjects :bogus-mode)]
        (is (not (:executable? r)))
        (is (some #(= :mode-not-in-verdict-policy (:reason %)) (:reasons r)))))))

(deftest environment-receipt-committed-informational
  (let [e (environment/environment
           {:profile/root "sha256:profile"
            :implementation-registry/root "sha256:reg"
            :schema-catalog/root "sha256:schema"
            :claim-policy-catalog/root "sha256:claims"
            :canonicalisation/id :prf-canonical-edn.v1
            :canonicalisation/implementation-root "sha256:canon"
            :runtime {:clojure-version "1.11" :jvm-version "17"}
            :source-revisions {:prf "abc" :sew "def"}})]
    (is (= "conformance.environment/v1" (:environment/schema-version e)))
    (is (string? (:environment/root e)))
    (testing "informational runtime details do not enter the committed root"
      (let [e2 (assoc-in e [:environment/informational :runtime :clojure-version] "999")]
        (is (= (:environment/root e) (:environment/root e2)))))
    (testing "committed canonicalisation change does change the root"
      (let [e3 (environment/environment
                (assoc (environment/environment
                        {:profile/root "sha256:profile"
                         :implementation-registry/root "sha256:reg"
                         :schema-catalog/root "sha256:schema"
                         :claim-policy-catalog/root "sha256:claims"
                         :canonicalisation/id :prf-canonical-edn.v1
                         :canonicalisation/implementation-root "sha256:OTHER"})
                       :canonicalisation/implementation-root "sha256:OTHER"))]
        (is (not= (:environment/root e) (:environment/root e3)))))))

(deftest registry-snapshot-reconciles-committed-descriptor
  (let [declared (edn/read-string (slurp "etc/conformance/registry.v1.edn"))
        r (registry/reconcile-registry-snapshot declared)]
    (is (contains? #{:pass :fail} (:status r)))
    (is (= (:registry/id declared) (:registry/id r)))
    (is (string? (:runtime-root r)))
    (testing "declared root is committed"
      (is (= "569918738a7a48439d17c73ffdb505d437ea8e4769438c560d7408694f2d09ac"
             (:registry/declared-root declared))))
    (testing "production ids all resolve in the runtime registry"
      (doseq [id [:trace-fixture-v2-schema :trace-fixture-v2-semantics
                  :research-scenario-schema :research-scenario-semantics
                  :artifact-envelope-schema :artifact-reference-semantics
                  :artifact-signature-semantics]]
        (is (registry/resolve-implementation id))))))
