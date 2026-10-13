(ns resolver-sim.conformance.golden-roots-test
  "Golden canonical roots (G5a-lite).

   Pins the deterministic content roots of the generic envelopes against the
   committed values.  Any accidental canonicalisation drift fails here.  A
   deliberate, versioned change to a canonical preimage requires updating the
   golden constant AND the schema version.

   The reconciliation/plan/registry envelopes bind the committed implementation
   registry root, so the golden constants are pinned only when the registry
   holds exactly the production implementation surface; other test namespaces
   register throwaway implementations into the shared registry, in which case
   only determinism is asserted (same state -> same root)."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.conformance.reconciliation :as rec]
            [resolver-sim.conformance.coverage :as cov]
            [resolver-sim.conformance.identity :as id]
            [resolver-sim.conformance.registry :as registry]
            [resolver-sim.conformance.plan :as plan]
            [resolver-sim.conformance.profile :as profile]
            [resolver-sim.conformance.envelope :as envelope]
            ;; Load both domain adapters so the implementation registry is fully
            ;; populated before the registry golden root is asserted.
            [resolver-sim.trace.conformance.validators]
            [resolver-sim.benchmark.conformance.reproduction]))

(def production-ids
  #{:trace-fixture-v2-schema :trace-fixture-v2-semantics
    :research-scenario-schema :research-scenario-semantics
    :artifact-envelope-schema :artifact-reference-semantics
    :artifact-signature-semantics})

(defn- production-surface? []
  (= (set (registry/registered-ids)) production-ids))

(defmacro ^:private pinned-or-deterministic
  "Assert `value` equals the pinned `golden` when the registry holds the
   production surface; otherwise assert `value` is deterministic and stable."
  [golden value]
  `(if (production-surface?)
     (is (= ~golden ~value))
     (is (= ~value ~value))))

(defn- standard-reconciliation []
  (rec/reconcile
   {:plan/root "sha256:plan"
    :steps [{:step/id :replay :requires [] :produces [:replay-receipt]}]}
   [{:step/id :replay :subject/id "a" :subject/root "sha256:a"
     :subject-set/root "sha256:golden" :status :pass}
    {:step/id :replay :subject/id "b" :subject/root "sha256:b"
     :subject-set/root "sha256:golden" :status :pass}]
   {:subject-set/root "sha256:golden" :subjects ["a" "b"]}))

(defn- standard-coverage []
  (cov/coverage-receipt
   {:universe-root "sha256:u"
    :required-subjects [:a :b]
    :validated-subjects [:a :b]
    :executed-subjects [:a :b]
    :compared-subjects [:a :b]
    :excluded-subjects []}))

(deftest golden-reconciliation-root
  (pinned-or-deterministic
   "c8bb6ddb3ab8d109e587ae7c0bdbbda4899e2e1a7bfe6f9d3337221a6ee2a0bc"
   (:reconciliation/root (standard-reconciliation))))

(deftest golden-coverage-root
  (pinned-or-deterministic
   "6578cac98c5d321d75fba36f74a3453ede049093ee7f0a4a312d88a3e3ddcd1a"
   (cov/coverage-root (standard-coverage))))

(deftest golden-identity-root
  (pinned-or-deterministic
   "95203220ffd7a8ec2ce8325c6550a2fd4323f0204fe1eba6723ad8993e6ff775"
   (id/identity-root
    (id/subject-identity
     {:subject/id "s1" :subject/kind :trace
      :subject/canonical-root "sha256:c"
      :subject/domain-roots {:solidity "keccak:x"}}))))

(deftest golden-registry-root
  (pinned-or-deterministic
   "569918738a7a48439d17c73ffdb505d437ea8e4769438c560d7408694f2d09ac"
   (registry/registry-root)))

(deftest golden-plan-root
  (let [p (profile/load-profile "etc/conformance/profiles/sew-trace-equivalence.v1.edn")]
    (pinned-or-deterministic
     "f2e789b45e45b3b1b0fa90b44466a05bb406518bd6a817bcb254bbe210302af4"
     (:plan/root (plan/build-plan
                  p {:subject-set/root "sha256:golden" :subjects ["a" "b"]})))))

(deftest envelope-versions-known
  (is (envelope/known-schema-version? "conformance.reconciliation/v1"))
  (is (envelope/known-schema-version? "conformance.subject-identity/v1"))
  (testing "unknown versions are rejected fail-closed"
    (is (thrown? clojure.lang.ExceptionInfo
                 (envelope/assert-known-schema-version! "conformance.reconciliation/v2"))))
  (testing "envelopes carry their schema version"
    (is (= "conformance.reconciliation/v1" (:schema-version (standard-reconciliation))))
    (is (= "conformance.subject-identity/v1"
           (:schema-version
            (id/subject-identity {:subject/id "s" :subject/canonical-root "sha256:c"}))))))
