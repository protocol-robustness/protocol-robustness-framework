(ns resolver-sim.research.execution-assurance
  "Compositional implementation, participant, validation, and run assurance.

   These artifacts extend existing remote-execution V1 identities by root;
   they do not widen or reinterpret those schemas."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.research.dependency-assurance :as dependency]
            [resolver-sim.research.distributed-scheduler :as distributed]
            [resolver-sim.research.execution-environment :as environment]
            [resolver-sim.research.remote-execution :as remote]))

(def ^:const implementation-schema "research-implementation-identity.v1")
(def ^:const assurance-policy-schema "research-execution-assurance-policy.v1")
(def ^:const assured-job-schema "research-execution-job-assurance.v1")
(def ^:const participant-set-schema "research-participant-set.v1")
(def ^:const validation-schema "research-fixed-validation-receipt.v1")
(def ^:const run-assurance-schema "research-run-assurance-evidence.v1")

(def fixed-validation-checks
  [:schema-version-supported
   :canonical-roots-recomputed
   :job-assignment-run-slot-bound
   :dependency-manifest-resolution-bound
   :environment-identity-bound
   :action-input-bound
   :result-canonicalized
   :result-job-provenance-bound
   :control-state-consistent])

(defn- root [domain value]
  (hash-ref/sha256-ref (hc/domain-hash domain value)))

(defn- root? [value] (hash-ref/valid-sha256-ref? value))

(defn implementation-root [implementation]
  (root :research-implementation-identity-v1
        (dissoc implementation :research-implementation-identity/root)))

(defn build-implementation
  [{:keys [algorithm-id artifact-root language-runtime-family
           resolved-dependency-set-root semantic-version]}]
  (when-not (and (qualified-keyword? algorithm-id) (root? artifact-root)
                 (keyword? language-runtime-family)
                 (root? resolved-dependency-set-root)
                 (string? semantic-version) (seq semantic-version))
    (throw (ex-info "Invalid research implementation identity" {})))
  (let [body {:artifact/schema implementation-schema
              :implementation/algorithm-id algorithm-id
              :implementation/artifact-root artifact-root
              :implementation/language-runtime-family language-runtime-family
              :resolved-research-dependency-set/root resolved-dependency-set-root
              :implementation/semantic-version semantic-version
              :implementation/distinctness-meaning :artifact-level}
        rooted (assoc body :research-implementation-identity/root
                      (implementation-root body))]
    rooted))

(defn valid-implementation? [implementation]
  (and (map? implementation)
       (= implementation-schema (:artifact/schema implementation))
       (= :artifact-level (:implementation/distinctness-meaning implementation))
       (qualified-keyword? (:implementation/algorithm-id implementation))
       (root? (:implementation/artifact-root implementation))
       (keyword? (:implementation/language-runtime-family implementation))
       (root? (:resolved-research-dependency-set/root implementation))
       (string? (:implementation/semantic-version implementation))
       (= (:research-implementation-identity/root implementation)
          (try (implementation-root implementation) (catch Exception _ nil)))))

(defn assurance-policy-root [policy]
  (root :research-execution-assurance-policy-v1
        (dissoc policy :research-execution-assurance-policy/root)))

(defn build-assurance-policy
  [{:keys [execution-policy-root dependency-policy-root required-runs
           minimum-distinct-runners minimum-distinct-sites
           minimum-distinct-implementations additional-validation-roots
           proof-generation-required? verifier-registry-root]}]
  (let [additional-validation-roots (vec (sort additional-validation-roots))]
    (when-not (and (every? root? [execution-policy-root dependency-policy-root])
                   (pos-int? required-runs)
                   (every? #(and (integer? %) (not (neg? %)) (<= % required-runs))
                           [minimum-distinct-runners minimum-distinct-sites
                            minimum-distinct-implementations])
                   (every? root? additional-validation-roots)
                   (= (count additional-validation-roots)
                      (count (set additional-validation-roots)))
                   (boolean? proof-generation-required?)
                   (or (not proof-generation-required?) (root? verifier-registry-root))
                   (or proof-generation-required? (nil? verifier-registry-root)))
      (throw (ex-info "Invalid research execution assurance policy" {})))
    (let [body {:artifact/schema assurance-policy-schema
                :research-execution-policy/root execution-policy-root
                :research-dependency-policy/root dependency-policy-root
                :execution/required-runs required-runs
                :assurance/minimum-distinct-runners minimum-distinct-runners
                :assurance/minimum-distinct-sites minimum-distinct-sites
                :assurance/minimum-distinct-implementations
                minimum-distinct-implementations
                :validation/fixed-floor fixed-validation-checks
                :validation/additional-policy-roots additional-validation-roots
                :proof-generation/required? proof-generation-required?
                :verifier-registry/root verifier-registry-root
                :correctness/meaning :assurance-does-not-establish-primitive-validity}
          rooted (assoc body :research-execution-assurance-policy/root
                        (assurance-policy-root body))]
      rooted)))

(defn valid-assurance-policy? [policy]
  (and (map? policy)
       (= assurance-policy-schema (:artifact/schema policy))
       (pos-int? (:execution/required-runs policy))
       (every? #(and (integer? %) (not (neg? %))
                     (<= % (:execution/required-runs policy)))
               [(:assurance/minimum-distinct-runners policy)
                (:assurance/minimum-distinct-sites policy)
                (:assurance/minimum-distinct-implementations policy)])
       (= fixed-validation-checks (:validation/fixed-floor policy))
       (vector? (:validation/additional-policy-roots policy))
       (= (:validation/additional-policy-roots policy)
          (vec (sort (:validation/additional-policy-roots policy))))
       (= (count (:validation/additional-policy-roots policy))
          (count (set (:validation/additional-policy-roots policy))))
       (every? root? (:validation/additional-policy-roots policy))
       (boolean? (:proof-generation/required? policy))
       (if (:proof-generation/required? policy)
         (root? (:verifier-registry/root policy))
         (nil? (:verifier-registry/root policy)))
       (= :assurance-does-not-establish-primitive-validity
          (:correctness/meaning policy))
       (every? root? [(:research-execution-policy/root policy)
                      (:research-dependency-policy/root policy)])
       (= (:research-execution-assurance-policy/root policy)
          (try (assurance-policy-root policy) (catch Exception _ nil)))))

(defn assured-job-root [job]
  (root :research-execution-job-assurance-v1
        (dissoc job :research-execution-job-assurance/root)))

(defn build-assured-job
  [{:keys [job-root dependency-manifest-root dependency-policy-root
           resolved-dependency-set-root implementation-roots assurance-policy-root]}]
  (let [implementation-roots (vec (sort implementation-roots))]
    (when-not (and (every? root? [job-root dependency-manifest-root
                                  dependency-policy-root resolved-dependency-set-root
                                  assurance-policy-root])
                   (seq implementation-roots) (every? root? implementation-roots)
                   (= (count implementation-roots) (count (set implementation-roots))))
      (throw (ex-info "Invalid assured research execution job" {})))
    (let [body {:artifact/schema assured-job-schema
                :research-execution-job/root job-root
                :research-dependency-manifest/root dependency-manifest-root
                :research-dependency-policy/root dependency-policy-root
                :resolved-research-dependency-set/root resolved-dependency-set-root
                :research-implementation-identity/roots implementation-roots
                :research-execution-assurance-policy/root assurance-policy-root}
          rooted (assoc body :research-execution-job-assurance/root
                        (assured-job-root body))]
      rooted)))

(defn valid-assured-job? [job]
  (and (map? job) (= assured-job-schema (:artifact/schema job))
       (every? root? [(:research-execution-job/root job)
                      (:research-dependency-manifest/root job)
                      (:research-dependency-policy/root job)
                      (:resolved-research-dependency-set/root job)
                      (:research-execution-assurance-policy/root job)])
       (seq (:research-implementation-identity/roots job))
       (= (:research-implementation-identity/roots job)
          (vec (sort (:research-implementation-identity/roots job))))
       (= (:research-execution-job-assurance/root job)
          (try (assured-job-root job) (catch Exception _ nil)))))

(defn participant-set-root [participant-set]
  (root :research-participant-set-v1
        (dissoc participant-set :research-participant-set/root)))

(defn build-participant-set
  "Build nominal canonical indices from stable identity roots.

   Indices are dense lookup coordinates only. They carry no order, distance,
   speed, reputation, eligibility, or reward-weight meaning."
  [participants]
  (let [by-root (into {} (map (juxt :participant/identity-root identity) participants))
        roots (vec (sort (keys by-root)))]
    (when-not (and (= (count participants) (count roots))
                   (every? root? roots)
                   (every? #(and (keyword? (:participant/role %))
                                 (= #{:participant/identity-root :participant/role}
                                    (set (keys %)))) participants))
      (throw (ex-info "Invalid canonical research participant set" {})))
    (let [body {:artifact/schema participant-set-schema
                :participant-index/semantics :nominal-root-bound
                :participants
                (mapv (fn [index identity-root]
                        {:participant/index index
                         :participant/identity-root identity-root
                         :participant/role (:participant/role (by-root identity-root))})
                      (range) roots)}]
      (assoc body :research-participant-set/root (participant-set-root body)))))

(defn assurance-progress
  "Count unique rooted constituents once. Duplicate proof/result/run evidence
   cannot advance a threshold. No application/economic consumption is implied."
  [assurance-policy run-constituents]
  (when-not (valid-assurance-policy? assurance-policy)
    (throw (ex-info "Invalid assurance policy" {})))
  (let [unique-by-evidence
        (vals (into {} (map (juxt :research-run-assurance-evidence/root identity)
                            run-constituents)))
        runners (set (map :runner/reference unique-by-evidence))
        sites (set (map :runner/site-id unique-by-evidence))
        implementations (set (map :research-implementation-identity/root
                                  unique-by-evidence))
        run-count (count unique-by-evidence)]
    {:unique-run-count run-count
     :distinct-runner-count (count runners)
     :distinct-site-count (count sites)
     :distinct-implementation-count (count implementations)
     :thresholds-satisfied?
     (and (>= run-count (:execution/required-runs assurance-policy))
          (>= (count runners) (:assurance/minimum-distinct-runners assurance-policy))
          (>= (count sites) (:assurance/minimum-distinct-sites assurance-policy))
          (>= (count implementations)
              (:assurance/minimum-distinct-implementations assurance-policy)))
     :primitive-validation/status :not-evaluated
     :application-authority/status :not-established}))

(defn validation-receipt-root [receipt]
  (root :research-fixed-validation-receipt-v1
        (dissoc receipt :research-fixed-validation-receipt/root)))

(defn validate-and-build-fixed-receipt
  [{:keys [assured-job job execution-environment execution-policy assignment
           run-evidence dependency-manifest dependency-policy resolved-dependencies
           implementation assurance-policy distributed-run]}]
  (let [slot (some #(when (= (:run-slot/index assignment) (:run-slot/index %)) %)
                   (remote/run-slots job execution-policy))
        valid? (and (valid-assured-job? assured-job)
                    (valid-assurance-policy? assurance-policy)
                    (remote/valid-job? job)
                    (:valid? (environment/validate-environment execution-environment))
                    (remote/valid-execution-policy? execution-policy)
                    (remote/valid-assignment? assignment)
                    (remote/valid-run-evidence? run-evidence)
                    (distributed/valid-run? distributed-run)
                    (:valid? (dependency/validate-manifest dependency-manifest))
                    (:valid? (dependency/validate-policy dependency-policy))
                    (dependency/valid-resolved-set? resolved-dependencies)
                    (= resolved-dependencies
                       (try (dependency/resolve-dependencies
                             dependency-manifest dependency-policy
                             (:resolved/dependencies resolved-dependencies))
                            (catch Exception _ nil)))
                    (valid-implementation? implementation)
                    (= (:research-execution-job/root assured-job)
                       (:research-execution-job/root job)
                       (:research-execution-job/root assignment)
                       (:research-execution-job/root run-evidence))
                    (= (:distributed-scheduler-run/root run-evidence)
                       (:distributed-scheduler-run/root distributed-run))
                    (= (:semantic-result/root run-evidence)
                       (:committed/root distributed-run))
                    (= (:workload/root job) (:workload/root distributed-run))
                    (= (:distributed-experiment-profile/root job)
                       (:distributed-experiment-profile/root distributed-run))
                    (= (:distributed-scheduler-policy/root job)
                       (:distributed-scheduler-policy/root distributed-run))
                    (= (:research-execution-assurance-policy/root assured-job)
                       (:research-execution-assurance-policy/root assurance-policy))
                    (= (:research-execution-policy/root assurance-policy)
                       (:research-execution-policy/root execution-policy))
                    (= (:research-dependency-policy/root assurance-policy)
                       (:research-dependency-policy/root dependency-policy))
                    (= (:execution/required-runs assurance-policy)
                       (:execution/required-runs execution-policy))
                    (= (:research-run-slot/root assignment)
                       (:research-run-slot/root slot))
                    (= (:research-execution-environment/root job)
                       (:research-execution-environment/root execution-environment)
                       (:research-execution-environment/root assignment)
                       (:research-execution-environment/root run-evidence))
                    (= (:workload/root job) (:action-input/root run-evidence))
                    (= (:research-dependency-manifest/root assured-job)
                       (:research-dependency-manifest/root dependency-manifest)
                       (:research-dependency-manifest/root resolved-dependencies))
                    (= (:research-dependency-policy/root assured-job)
                       (:research-dependency-policy/root dependency-policy)
                       (:research-dependency-policy/root resolved-dependencies))
                    (= (:resolved-research-dependency-set/root assured-job)
                       (:resolved-research-dependency-set/root resolved-dependencies)
                       (:resolved-research-dependency-set/root implementation))
                    (contains? (set (:research-implementation-identity/roots assured-job))
                               (:research-implementation-identity/root implementation))
                    (= :assigned (:assignment/status assignment))
                    (= :complete (:execution/outcome run-evidence)))]
    (when-not valid?
      (throw (ex-info "Framework-fixed research execution validation failed" {})))
    (let [body {:artifact/schema validation-schema
                :research-execution-job-assurance/root
                (:research-execution-job-assurance/root assured-job)
                :research-run-evidence/root (:research-run-evidence/root run-evidence)
                :resolved-research-dependency-set/root
                (:resolved-research-dependency-set/root resolved-dependencies)
                :research-implementation-identity/root
                (:research-implementation-identity/root implementation)
                :validation/checks (mapv (fn [check] {:check/id check :check/status :pass})
                                         fixed-validation-checks)
                :validation/status :passed
                :primitive-validation/status :execution-result-only}
          rooted (assoc body :research-fixed-validation-receipt/root
                        (validation-receipt-root body))]
      rooted)))

(defn run-assurance-root [evidence]
  (root :research-run-assurance-evidence-v1
        (dissoc evidence :research-run-assurance-evidence/root)))

(defn valid-run-assurance-evidence?
  "Validate the closed V1 assurance consequence without re-running execution."
  [evidence]
  (and (map? evidence)
       (= run-assurance-schema (:artifact/schema evidence))
       (= #{:artifact/schema :research-execution-job-assurance/root
            :research-run-evidence/root :resolved-research-dependency-set/root
            :research-implementation-identity/root
            :research-fixed-validation-receipt/root :runner/reference
            :runner/site-id :semantic-result/root :semantic-result/status
            :application-authority/status
            :research-run-assurance-evidence/root}
          (set (keys evidence)))
       (every? root? [(:research-execution-job-assurance/root evidence)
                      (:research-run-evidence/root evidence)
                      (:resolved-research-dependency-set/root evidence)
                      (:research-implementation-identity/root evidence)
                      (:research-fixed-validation-receipt/root evidence)
                      (:runner/reference evidence) (:semantic-result/root evidence)])
       (keyword? (:runner/site-id evidence))
       (= :execution-result-only (:semantic-result/status evidence))
       (= :not-established (:application-authority/status evidence))
       (= (:research-run-assurance-evidence/root evidence)
          (try (run-assurance-root evidence) (catch Exception _ nil)))))

(defn build-run-assurance-evidence
  [assured-job run-evidence resolved-dependencies implementation validation-receipt]
  (when-not (and (= (:research-execution-job-assurance/root assured-job)
                    (:research-execution-job-assurance/root validation-receipt))
                 (= (:research-run-evidence/root run-evidence)
                    (:research-run-evidence/root validation-receipt))
                 (= (:resolved-research-dependency-set/root resolved-dependencies)
                    (:resolved-research-dependency-set/root implementation)
                    (:resolved-research-dependency-set/root validation-receipt))
                 (= (:research-implementation-identity/root implementation)
                    (:research-implementation-identity/root validation-receipt))
                 (= :passed (:validation/status validation-receipt)))
    (throw (ex-info "Run assurance evidence provenance mismatch" {})))
  (let [body {:artifact/schema run-assurance-schema
              :research-execution-job-assurance/root
              (:research-execution-job-assurance/root assured-job)
              :research-run-evidence/root (:research-run-evidence/root run-evidence)
              :resolved-research-dependency-set/root
              (:resolved-research-dependency-set/root resolved-dependencies)
              :research-implementation-identity/root
              (:research-implementation-identity/root implementation)
              :research-fixed-validation-receipt/root
              (:research-fixed-validation-receipt/root validation-receipt)
              :runner/reference (:runner/reference run-evidence)
              :runner/site-id (:runner/site-id run-evidence)
              :semantic-result/root (:semantic-result/root run-evidence)
              :semantic-result/status :execution-result-only
              :application-authority/status :not-established}
        rooted (assoc body :research-run-assurance-evidence/root
                      (run-assurance-root body))]
    rooted))
