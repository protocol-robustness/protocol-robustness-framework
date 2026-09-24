(ns resolver-sim.research.remote-execution-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.research.distributed-policy :as distributed-policy]
            [resolver-sim.research.distributed-profile :as distributed-profile]
            [resolver-sim.research.distributed-scheduler :as distributed]
            [resolver-sim.research.execution-environment :as environment]
            [resolver-sim.research.remote-execution :as remote]
            [resolver-sim.research.runner-participation :as participation]
            [resolver-sim.research.scheduler-harness :as harness]))

(defn fake-root [digit]
  (str "sha256:" (apply str (repeat 64 digit))))

(defn anvil-environment
  ([] (anvil-environment {}))
  ([overrides]
   (environment/build-anvil-environment
    (merge {:tool-version "1.4.0"
            :chain-id 31337
            :state-source {:state-source/kind :genesis
                           :state-source/root (fake-root \1)}
            :code-identities [{:deployment/address "0x0000000000000000000000000000000000000001"
                               :deployment/code-root (fake-root \2)}]
            :initial-state-root (fake-root \3)
            :config {:block-time-ms 0 :automine? true}}
           overrides))))

(def qualification-root (fake-root \4))

(defn runner
  [digit disclosure site & {:keys [opted-in? capabilities qualifications]
                            :or {opted-in? true
                                 capabilities [:capability/anvil :capability/evm]
                                 qualifications [qualification-root]}}]
  (participation/build-participation
   {:runner-reference (fake-root digit)
    :disclosure-mode disclosure
    :site-id site
    :opted-in? opted-in?
    :available? true
    :capabilities capabilities
    :job-classes [:job/research-calculation]
    :environment-kinds [:anvil]
    :qualification-references qualifications}))

(defn execution-policy
  ([] (execution-policy {}))
  ([overrides]
   (remote/build-execution-policy
    (merge {:objective :corroborated
            :required-runs 3
            :required-matching-runs 2
            :distinct-by [:runner-identity]
            :repeat-runner-policy :prohibited
            :opt-in-required? true
            :allowed-disclosure-modes [:anonymous :identified :pseudonymous]
            :required-capabilities [:capability/anvil]
            :qualification-references [qualification-root]
            :environment-kinds [:anvil]
            :attribution-mode :omit-from-published-result}
           overrides))))

(def workload (harness/workload {:id :remote-runner-seam :transfer-bytes 4}))
(def distributed-policy-artifact
  (distributed-policy/build-policy {:kind :local-only}))

(defn local-profile []
  (distributed-profile/build-profile
   {:sites [{:site/id :site/a :site/available? true}]
    :workers (mapv (fn [{:keys [id kind]}]
                     {:worker/id id :worker/site-id :site/a
                      :worker/available? true :worker/resource-kinds [kind]})
                   harness/workers)
    :links [] :dependency-replicas {}}))

(defn job [env policy profile]
  (remote/build-job
   {:job-class :job/research-calculation
    :workload-root (:workload/root workload)
    :distributed-profile-root (:distributed-experiment-profile/root profile)
    :distributed-policy-root
    (:distributed-scheduler-policy/root distributed-policy-artifact)
    :execution-environment-root (:research-execution-environment/root env)
    :execution-policy-root (:research-execution-policy/root policy)}))

(deftest anvil-environment-identity-is-stable-and-sensitive
  (let [base (anvil-environment)]
    (is (= base (anvil-environment)))
    (is (:valid? (environment/validate-environment base)))
    (is (every?
         #(not= (:research-execution-environment/root base)
                (:research-execution-environment/root %))
         [(anvil-environment {:tool-version "1.5.0"})
          (anvil-environment {:config {:block-time-ms 1 :automine? true}})
          (anvil-environment {:initial-state-root (fake-root \5)})
          (anvil-environment {:state-source {:state-source/kind :fork
                                             :state-source/root (fake-root \6)
                                             :fork/block-number 19000000}})]))))

(deftest runner-identity-is-independent-from-environment-identity
  (let [env (anvil-environment)
        a (runner \a :identified :site/a)
        b (runner \b :anonymous :site/b)]
    (is (not= (:runner/reference a) (:runner/reference b)))
    (is (= (:research-execution-environment/root env)
           (:research-execution-environment/root (anvil-environment))))
    (is (nil? (:runner/real-world-identity b)))
    (is (= :anonymous (:runner/disclosure-mode b)))))

(deftest opt-in-capability-and-policy-eligibility-are-independent
  (let [env (anvil-environment)
        profile (local-profile)
        policy (execution-policy)
        j (job env policy profile)
        opted-out (runner \a :identified :site/a :opted-in? false)
        missing-capability (runner \b :pseudonymous :site/b :capabilities [:capability/cpu])
        missing-qualification (runner \c :anonymous :site/c :qualifications [])
        eligible-anonymous (runner \d :anonymous :site/d)]
    (is (= [:runner-not-opted-in]
           (:ineligibility/reasons (remote/eligibility j env policy opted-out))))
    (is (some #{:required-capability-missing}
              (:ineligibility/reasons
               (remote/eligibility j env policy missing-capability))))
    (is (some #{:qualification-reference-missing}
              (:ineligibility/reasons
               (remote/eligibility j env policy missing-qualification))))
    (is (:eligible? (remote/eligibility j env policy eligible-anonymous)))
    (is (nil? (remote/steal-next j env policy opted-out [])))
    (is (some? (remote/steal-next j env policy eligible-anonymous [])))))

(deftest distinct-runner-policy-constrains-job-level-stealing
  (let [env (anvil-environment)
        profile (local-profile)
        policy (execution-policy)
        j (job env policy profile)
        a (runner \a :identified :site/a)
        b (runner \b :pseudonymous :site/b)
        c (runner \c :anonymous :site/c)
        assignment-a (remote/steal-next j env policy a [])
        assignment-b (remote/steal-next j env policy b [assignment-a])
        assignment-c (remote/steal-next j env policy c [assignment-a assignment-b])]
    (is assignment-a)
    (is (nil? (remote/steal-next j env policy a [assignment-a])))
    (is (false? (remote/distinctness-allows?
                 policy [assignment-a assignment-b] a)))
    (is assignment-b)
    (is assignment-c)
    (is (remote/valid-assignment-set? policy
                                      [assignment-a assignment-b assignment-c]))
    (is (false? (remote/valid-assignment-set?
                 policy [assignment-a assignment-b
                         (assoc assignment-c :runner/reference
                                (:runner/reference assignment-a))])))))

(deftest distinct-site-does-not-treat-workers-or-runners-at-one-site-as-independent
  (let [env (anvil-environment)
        profile (local-profile)
        policy (execution-policy {:distinct-by [:runner-identity :site]})
        j (job env policy profile)
        a1 (runner \a :identified :site/a)
        a2 (runner \b :pseudonymous :site/a)
        b (runner \c :anonymous :site/b)
        assignment-a (remote/steal-next j env policy a1 [])]
    (is (nil? (remote/steal-next j env policy a2 [assignment-a])))
    (is (some? (remote/steal-next j env policy b [assignment-a])))))

(deftest multi-run-policy-and-job-identities-bind-semantic-substitutions
  (let [env (anvil-environment)
        profile (local-profile)
        base (execution-policy)
        substitutions [(execution-policy {:required-runs 4
                                          :required-matching-runs 3})
                       (execution-policy {:distinct-by [:runner-identity :site]})
                       (execution-policy {:qualification-references [(fake-root \7)]})
                       (execution-policy {:environment-kinds [:anvil :future-evm]})]]
    (is (every? #(not= (:research-execution-policy/root base)
                       (:research-execution-policy/root %)) substitutions))
    (is (every? #(not= (:research-execution-job/root (job env base profile))
                       (:research-execution-job/root (job env % profile))) substitutions))
    (is (= 3 (count (remote/run-slots (job env base profile) base))))))

(deftest distinct-runners-retain-distinct-forensic-provenance-for-the-same-result
  (let [env (anvil-environment)
        profile (local-profile)
        policy (execution-policy)
        j (job env policy profile)
        a (runner \a :identified :site/a)
        b (runner \b :anonymous :site/b)
        assignment-a (remote/steal-next j env policy a [])
        assignment-b (remote/steal-next j env policy b [assignment-a])
        run (distributed/run workload profile distributed-policy-artifact)
        commitments {:environment-before-root (:environment/initial-state-root env)
                     :action-input-root (:workload/root workload)
                     :environment-after-root (fake-root \8)}
        evidence-a (remote/build-run-evidence j env policy a assignment-a run commitments)
        evidence-b (remote/build-run-evidence j env policy b assignment-b run commitments)]
    (is (= (:semantic-result/root evidence-a) (:semantic-result/root evidence-b)))
    (is (not= (:research-run-evidence/root evidence-a)
              (:research-run-evidence/root evidence-b)))
    (is (= :declared-simulation (:forensic/assurance evidence-a)))
    (is (= :not-evaluated (:primitive-validation/status evidence-a)))
    (is (= :not-evaluated (:corroboration/status evidence-a)))
    (is (:runner/reference evidence-b))
    (is (nil? (:published/runner-reference evidence-b)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (remote/build-run-evidence j env policy b assignment-a run commitments)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (remote/build-run-evidence
                  j env policy a assignment-a run
                  (assoc commitments :action-input-root (fake-root \9)))))))

(deftest matching-runs-do-not-promote-correctness-proof-or-acceptance
  (let [env (anvil-environment)
        profile (local-profile)
        policy (execution-policy)
        j (job env policy profile)
        runners [(runner \a :identified :site/a)
                 (runner \b :pseudonymous :site/b)
                 (runner \c :anonymous :site/c)]
        assignments (reduce (fn [assigned r]
                              (conj assigned (remote/steal-next j env policy r assigned)))
                            [] runners)
        run (distributed/run workload profile distributed-policy-artifact)
        commitments {:environment-before-root (:environment/initial-state-root env)
                     :action-input-root (:workload/root workload)
                     :environment-after-root (fake-root \8)}
        evidence (mapv #(remote/build-run-evidence j env policy %1 %2 run commitments)
                       runners assignments)
        view (remote/corroboration-view evidence)]
    (is (= 1 (count (:matching-result-groups view))))
    (is (= :not-evaluated (:primitive-validation/status view)))
    (is (= :not-established (:proof/status view)))
    (is (nil? (:claim/accepted view)))
    (is (nil? (:general-ic/proven view)))))

(deftest unsupported-economic-objective-is-not-pretended-to-work
  (is (thrown? clojure.lang.ExceptionInfo
               (execution-policy {:objective :lowest-cost}))))
