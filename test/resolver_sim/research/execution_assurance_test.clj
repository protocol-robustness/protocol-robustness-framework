(ns resolver-sim.research.execution-assurance-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.research.dependency-assurance :as dependency]
            [resolver-sim.research.distributed-scheduler :as distributed]
            [resolver-sim.research.execution-assurance :as assurance]
            [resolver-sim.research.proof-generation :as proof]
            [resolver-sim.research.remote-execution :as remote]
            [resolver-sim.research.remote-execution-test :as fixture]
            [resolver-sim.verification.basis :as verification-basis]))

(defn dep
  ([id class digit] (dep id class digit :public :public-by-root {}))
  ([id class digit visibility acquisition metadata]
   {:dependency/id id :dependency/class class
    :dependency/visibility visibility
    :dependency/acquisition-mode acquisition
    :dependency/artifact-root (fixture/fake-root digit)
    :dependency/provenance-roots [(fixture/fake-root \e)]
    :dependency/metadata metadata}))

(def dependency-entries
  [(dep :dep/prf :prf-framework \1 :public :public-by-root
        {:semantic-version "1.x"})
   (dep :dep/extensions :prf-extension \2)
   (dep :dep/registry :registry-snapshot \3)
   (dep :dep/clojure :clojure-model \4 :public :public-by-root
        {:logical-version "model-v1"})
   (dep :dep/scenarios :scenario-corpus \5)
   (dep :dep/solidity :solidity-source-build \6)
   (dep :dep/contract :deployed-evm-contract \7 :public :public-by-root
        {:chain-id 31337 :address "0x01"
         :runtime-bytecode-root (fixture/fake-root \8)
         :implementation-root (fixture/fake-root \9)
         :state-basis-root (fixture/fake-root \a)})
   (dep :dep/anvil :anvil-foundry \b)
   (dep :dep/proof :proof-program-verifier \c)
   (dep :dep/toolchain :runtime-toolchain \d)
   (dep :dep/private-model :local-ai-model \a
        :runner-readable-private :authenticated-private
        {:ciphertext-bundle-root (fixture/fake-root \b)})
   (dep :dep/sealed-data :dataset-fixture \c
        :sealed-to-execution-environment :attestation-gated-sealed
        {:encrypted-bundle-root (fixture/fake-root \d)})
   (dep :dep/container :hermetic-environment \f)])

(defn dependency-context [environment-root]
  (let [manifest (dependency/build-manifest dependency-entries)
        policy (dependency/build-policy
                {:required-classes (vec (sort (set (map :dependency/class dependency-entries))))
                 :allowed-visibility [:public :runner-readable-private
                                      :sealed-to-execution-environment]
                 :allowed-acquisition-modes [:public-by-root :authenticated-private
                                             :attestation-gated-sealed]
                 :sealed-environment-root environment-root})
        resolutions
        (mapv (fn [entry]
                (cond-> {:dependency/id (:dependency/id entry)
                         :dependency/artifact-root (:dependency/artifact-root entry)
                         :resolution/acquisition-mode (:dependency/acquisition-mode entry)
                         :resolution/status :resolved
                         :resolution/evidence-root (fixture/fake-root \f)}
                  (= :sealed-to-execution-environment (:dependency/visibility entry))
                  (assoc :resolution/environment-root environment-root)))
              dependency-entries)
        resolved (dependency/resolve-dependencies manifest policy resolutions)]
    {:manifest manifest :policy policy :resolutions resolutions :resolved resolved}))

(deftest dependency-classes-and-substitutions-are-root-sensitive
  (let [base (dependency/build-manifest dependency-entries)]
    (is (= dependency/dependency-classes
           (set (map :dependency/class (:dependencies base)))))
    (doseq [id [:dep/prf :dep/extensions :dep/registry :dep/clojure
                :dep/scenarios :dep/solidity :dep/contract :dep/anvil
                :dep/toolchain]]
      (let [changed (mapv #(if (= id (:dependency/id %))
                             (assoc % :dependency/artifact-root (fixture/fake-root \0)) %)
                          dependency-entries)]
        (is (not= (:research-dependency-manifest/root base)
                  (:research-dependency-manifest/root
                   (dependency/build-manifest changed))))))))

(deftest logical-version-label-cannot-hide-different-dependency-bytes
  (let [original (dependency/build-manifest dependency-entries)
        changed (dependency/build-manifest
                 (mapv #(if (= :dep/clojure (:dependency/id %))
                          (assoc % :dependency/artifact-root (fixture/fake-root \0)) %)
                       dependency-entries))]
    (is (= "model-v1"
           (->> (:dependencies original)
                (filter #(= :dep/clojure (:dependency/id %))) first
                :dependency/metadata :logical-version)))
    (is (not= (:research-dependency-manifest/root original)
              (:research-dependency-manifest/root changed)))))

(deftest private-and-sealed-dependencies-require-no-public-location
  (let [private (some #(when (= :dep/private-model (:dependency/id %)) %) dependency-entries)
        sealed (some #(when (= :dep/sealed-data (:dependency/id %)) %) dependency-entries)]
    (is (dependency/valid-dependency? private))
    (is (dependency/valid-dependency? sealed))
    (is (nil? (:dependency/public-uri private)))
    (is (nil? (:dependency/plaintext sealed)))
    (is (= :attestation-gated-sealed (:dependency/acquisition-mode sealed)))))

(deftest ordinary-readable-resolution-cannot-satisfy-sealed-dependency
  (let [env (fixture/anvil-environment)
        {:keys [manifest policy resolutions]} (dependency-context
                                               (:research-execution-environment/root env))
        ordinary (mapv #(if (= :dep/sealed-data (:dependency/id %))
                          (-> %
                              (assoc :resolution/acquisition-mode :authenticated-private)
                              (dissoc :resolution/environment-root)) %)
                       resolutions)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (dependency/resolve-dependencies manifest policy ordinary)))))

(deftest immutable-resolution-is-idempotent
  (let [env (fixture/anvil-environment)
        {:keys [manifest policy resolutions resolved]} (dependency-context
                                                        (:research-execution-environment/root env))
        again (dependency/resolve-dependencies manifest policy resolutions)]
    (is (= (:resolved-research-dependency-set/root resolved)
           (:resolved-research-dependency-set/root again)))
    (is (= resolved again))))

(deftest participant-index-is-independent-of-arrival-order-and-remains-nominal
  (let [participants [{:participant/identity-root (fixture/fake-root \a)
                       :participant/role :execution-runner}
                      {:participant/identity-root (fixture/fake-root \c)
                       :participant/role :prover}
                      {:participant/identity-root (fixture/fake-root \b)
                       :participant/role :verifier}]
        forward (assurance/build-participant-set participants)
        reverse (assurance/build-participant-set (reverse participants))]
    (is (= forward reverse))
    (is (= :nominal-root-bound (:participant-index/semantics forward)))
    (is (= (mapv :participant/identity-root (:participants forward))
           (vec (sort (map :participant/identity-root participants)))))
    (is (every? #(not (contains? % :weight)) (:participants forward)))))

(defn assurance-context []
  (let [env (fixture/anvil-environment)
        profile (fixture/local-profile)
        execution-policy (fixture/execution-policy)
        job (fixture/job env execution-policy profile)
        dependencies (dependency-context (:research-execution-environment/root env))
        implementation-a
        (assurance/build-implementation
         {:algorithm-id :research/scheduler-reference
          :artifact-root (fixture/fake-root \1)
          :language-runtime-family :clojure
          :resolved-dependency-set-root
          (:resolved-research-dependency-set/root (:resolved dependencies))
          :semantic-version "1.0.0"})
        implementation-b
        (assurance/build-implementation
         {:algorithm-id :research/scheduler-independent
          :artifact-root (fixture/fake-root \2)
          :language-runtime-family :rust
          :resolved-dependency-set-root
          (:resolved-research-dependency-set/root (:resolved dependencies))
          :semantic-version "1.0.0"})
        assurance-policy
        (assurance/build-assurance-policy
         {:execution-policy-root (:research-execution-policy/root execution-policy)
          :dependency-policy-root
          (:research-dependency-policy/root (:policy dependencies))
          :required-runs 3 :minimum-distinct-runners 3
          :minimum-distinct-sites 2 :minimum-distinct-implementations 2
          :additional-validation-roots [(fixture/fake-root \3)]
          :proof-generation-required? true
          :verifier-registry-root (verification-basis/verifier-registry-root {})})
        assured-job
        (assurance/build-assured-job
         {:job-root (:research-execution-job/root job)
          :dependency-manifest-root
          (:research-dependency-manifest/root (:manifest dependencies))
          :dependency-policy-root
          (:research-dependency-policy/root (:policy dependencies))
          :resolved-dependency-set-root
          (:resolved-research-dependency-set/root (:resolved dependencies))
          :implementation-roots [(:research-implementation-identity/root implementation-a)
                                 (:research-implementation-identity/root implementation-b)]
          :assurance-policy-root
          (:research-execution-assurance-policy/root assurance-policy)})]
    {:env env :profile profile :execution-policy execution-policy :job job
     :dependencies dependencies :implementation-a implementation-a
     :implementation-b implementation-b :assurance-policy assurance-policy
     :assured-job assured-job}))

(defn run-for [context runner assignments implementation]
  (let [{:keys [env profile execution-policy job dependencies assured-job
                assurance-policy]} context
        assignment (remote/steal-next job env execution-policy runner assignments)
        distributed-run (distributed/run fixture/workload profile
                                         fixture/distributed-policy-artifact)
        run-evidence (remote/build-run-evidence
                      job env execution-policy runner assignment distributed-run
                      {:environment-before-root (:environment/initial-state-root env)
                       :action-input-root (:workload/root fixture/workload)
                       :environment-after-root (fixture/fake-root \8)})
        validation-input
        {:assured-job assured-job :job job :execution-environment env
         :execution-policy execution-policy :assignment assignment
         :run-evidence run-evidence
         :dependency-manifest (:manifest dependencies)
         :dependency-policy (:policy dependencies)
         :resolved-dependencies (:resolved dependencies)
         :implementation implementation
         :assurance-policy assurance-policy
         :distributed-run distributed-run}
        validation (assurance/validate-and-build-fixed-receipt validation-input)
        run-assurance (assurance/build-run-assurance-evidence
                       assured-job run-evidence (:resolved dependencies)
                       implementation validation)]
    {:assignment assignment :run-evidence run-evidence :distributed-run distributed-run
     :validation-input validation-input
     :validation validation :run-assurance run-assurance}))

(deftest implementation-and-runner-distinctness-are-independent
  (let [context (assurance-context)
        a (fixture/runner \a :identified :site/a)
        b (fixture/runner \b :pseudonymous :site/b)
        run-a (run-for context a [] (:implementation-a context))
        run-b-same-implementation
        (run-for context b [(:assignment run-a)] (:implementation-a context))
        run-a-other-implementation
        (run-for context a [] (:implementation-b context))]
    (is (not= (-> run-a :run-assurance :research-run-assurance-evidence/root)
              (-> run-b-same-implementation :run-assurance
                  :research-run-assurance-evidence/root)))
    (is (= (-> run-a :run-assurance :research-implementation-identity/root)
           (-> run-b-same-implementation :run-assurance
               :research-implementation-identity/root)))
    (is (not= (:research-implementation-identity/root (:implementation-a context))
              (:research-implementation-identity/root (:implementation-b context))))
    (is (= (:runner/reference a)
           (-> run-a-other-implementation :run-evidence :runner/reference)))
    (is (not= (-> run-a :run-assurance :research-run-assurance-evidence/root)
              (-> run-a-other-implementation :run-assurance
                  :research-run-assurance-evidence/root)))))

(deftest assurance-thresholds-deduplicate-constituents
  (let [policy (:assurance-policy (assurance-context))
        constituents [{:research-run-assurance-evidence/root (fixture/fake-root \1)
                       :runner/reference (fixture/fake-root \a) :runner/site-id :site/a
                       :research-implementation-identity/root (fixture/fake-root \7)}
                      {:research-run-assurance-evidence/root (fixture/fake-root \2)
                       :runner/reference (fixture/fake-root \b) :runner/site-id :site/b
                       :research-implementation-identity/root (fixture/fake-root \7)}
                      {:research-run-assurance-evidence/root (fixture/fake-root \3)
                       :runner/reference (fixture/fake-root \c) :runner/site-id :site/c
                       :research-implementation-identity/root (fixture/fake-root \8)}]
        progress (assurance/assurance-progress policy constituents)
        duplicate (assurance/assurance-progress policy (conj constituents (first constituents)))]
    (is (:thresholds-satisfied? progress))
    (is (= progress duplicate))
    (is (= 3 (:distinct-runner-count progress)))
    (is (= 2 (:distinct-implementation-count progress)))
    (is (= :not-evaluated (:primitive-validation/status progress)))))

(deftest fixed-validation-floor-cannot-be-weakened-by-policy
  (let [context (assurance-context)
        policy (:assurance-policy context)
        weakened (-> policy
                     (assoc :validation/fixed-floor [])
                     (dissoc :research-execution-assurance-policy/root))
        weakened (assoc weakened :research-execution-assurance-policy/root
                        (assurance/assurance-policy-root weakened))]
    (is (= assurance/fixed-validation-checks (:validation/fixed-floor policy)))
    (is (false? (assurance/valid-assurance-policy? weakened)))
    (is (= [(fixture/fake-root \3)] (:validation/additional-policy-roots policy)))))

(deftest fixed-validation-rejects-result-root-tampering
  (let [context (assurance-context)
        execution (run-for context (fixture/runner \a :identified :site/a)
                           [] (:implementation-a context))
        tampered (assoc-in (:validation-input execution)
                           [:distributed-run :committed/root]
                           (fixture/fake-root \0))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (assurance/validate-and-build-fixed-receipt tampered)))))

(deftest proof-generation-binds-job-dependencies-result-program-and-independent-prover
  (let [context (assurance-context)
        execution-runner (fixture/runner \a :identified :site/a)
        prover (fixture/runner \d :anonymous :site/d)
        execution (run-for context execution-runner [] (:implementation-a context))
        run-assurance (:run-assurance execution)
        registry-root (verification-basis/verifier-registry-root {})
        receipt (proof/build-receipt
                 {:assured-job-root
                  (get-in context [:assured-job :research-execution-job-assurance/root])
                  :resolved-dependency-set-root
                  (get-in context [:dependencies :resolved
                                   :resolved-research-dependency-set/root])
                  :run-assurance-evidence-root
                  (:research-run-assurance-evidence/root run-assurance)
                  :semantic-result-root (:semantic-result/root run-assurance)
                  :proof-system-identity-root (fixture/fake-root \4)
                  :proof-program-identity-root (fixture/fake-root \5)
                  :public-values-input-root (:semantic-result/root run-assurance)
                  :proof-artifact-root (fixture/fake-root \6)
                  :prover-participation prover
                  :execution-environment-root
                  (get-in context [:env :research-execution-environment/root])
                  :verifier-registry-root registry-root})]
    (is (not= (:runner-participation/root execution-runner)
              (:prover-participation/root receipt)))
    (is (proof/receipt-matches-context?
         receipt
         (get-in context [:assured-job :research-execution-job-assurance/root])
         (get-in context [:dependencies :resolved
                          :resolved-research-dependency-set/root])
         (:research-run-assurance-evidence/root run-assurance)))
    (is (false? (proof/receipt-matches-context?
                 receipt (fixture/fake-root \0)
                 (get-in context [:dependencies :resolved
                                  :resolved-research-dependency-set/root])
                 (:research-run-assurance-evidence/root run-assurance))))
    (is (false? (proof/receipt-matches-context?
                 receipt
                 (get-in context [:assured-job :research-execution-job-assurance/root])
                 (fixture/fake-root \0)
                 (:research-run-assurance-evidence/root run-assurance))))
    (is (proof/verifier-registry-matches? receipt {}))
    (is (false? (proof/verifier-registry-matches?
                 receipt {:fixture/verifier {:descriptor-root "different"
                                             :builtin? true :providers []}})))
    (is (= 1 (proof/unique-proof-count [receipt receipt])))
    (is (= :not-verified (:proof-verification/status receipt)))
    (is (= :not-established (:application-authority/status receipt)))))
