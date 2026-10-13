(ns resolver-sim.research.remote-execution
  "Future-compatible remote-runner policy, job, assignment, and evidence seams.

   This namespace schedules independent whole-job runs over rooted runner
   participation declarations. It does not perform networking, authenticate
   identities, validate qualifications, establish primitive correctness, or
   implement consensus."
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.research.execution-environment :as environment]
            [resolver-sim.research.runner-participation :as participation]))

(def ^:const policy-schema "research-execution-policy.v1")
(def ^:const job-schema "research-execution-job.v1")
(def ^:const assignment-schema "research-run-assignment.v1")
(def ^:const evidence-schema "research-run-evidence.v1")

(def objectives #{:fastest :corroborated})
(def distinctness-dimensions #{:runner-identity :site})
(def attribution-modes #{:include-runner-reference :omit-from-published-result})
(def ^:private policy-fields
  #{:artifact/schema :execution/objective :execution/required-runs
    :execution/required-matching-runs :execution/distinct-by
    :execution/repeat-runner-policy :eligibility/opt-in-required?
    :eligibility/allowed-disclosure-modes :eligibility/required-capabilities
    :eligibility/qualification-references :eligibility/environment-kinds
    :attribution/mode :correctness/meaning :research-execution-policy/root})

(defn- root [domain value]
  (hash-ref/sha256-ref (hc/domain-hash domain value)))

(defn- root? [value] (hash-ref/valid-sha256-ref? value))

(defn- canonical-distinct-vector? [values]
  (and (vector? values)
       (= values (vec (sort values)))
       (= (count values) (count (set values)))))

(defn execution-policy-root [execution-policy]
  (root :research-execution-policy-v1
        (dissoc execution-policy :research-execution-policy/root)))

(defn valid-execution-policy? [execution-policy]
  (and (map? execution-policy)
       (= policy-schema (:artifact/schema execution-policy))
       (= policy-fields (set (keys execution-policy)))
       (contains? objectives (:execution/objective execution-policy))
       (pos-int? (:execution/required-runs execution-policy))
       (pos-int? (:execution/required-matching-runs execution-policy))
       (<= (:execution/required-matching-runs execution-policy)
           (:execution/required-runs execution-policy))
       (canonical-distinct-vector? (:execution/distinct-by execution-policy))
       (every? distinctness-dimensions (:execution/distinct-by execution-policy))
       (contains? #{:permitted :prohibited}
                  (:execution/repeat-runner-policy execution-policy))
       (or (= :permitted (:execution/repeat-runner-policy execution-policy))
           (some #{:runner-identity} (:execution/distinct-by execution-policy)))
       (boolean? (:eligibility/opt-in-required? execution-policy))
       (seq (:eligibility/allowed-disclosure-modes execution-policy))
       (canonical-distinct-vector?
        (:eligibility/allowed-disclosure-modes execution-policy))
       (every? participation/disclosure-modes
               (:eligibility/allowed-disclosure-modes execution-policy))
       (canonical-distinct-vector? (:eligibility/required-capabilities execution-policy))
       (every? keyword? (:eligibility/required-capabilities execution-policy))
       (canonical-distinct-vector? (:eligibility/qualification-references execution-policy))
       (every? root? (:eligibility/qualification-references execution-policy))
       (seq (:eligibility/environment-kinds execution-policy))
       (canonical-distinct-vector? (:eligibility/environment-kinds execution-policy))
       (every? keyword? (:eligibility/environment-kinds execution-policy))
       (contains? attribution-modes (:attribution/mode execution-policy))
       (= :corroboration-does-not-establish-validity
          (:correctness/meaning execution-policy))
       (= (:research-execution-policy/root execution-policy)
          (try (execution-policy-root execution-policy) (catch Exception _ nil)))))

(defn build-execution-policy
  [{:keys [objective required-runs required-matching-runs distinct-by
           repeat-runner-policy opt-in-required? allowed-disclosure-modes
           required-capabilities qualification-references environment-kinds
           attribution-mode]}]
  (let [distinct-by (vec (sort distinct-by))
        allowed-disclosure-modes (vec (sort allowed-disclosure-modes))
        required-capabilities (vec (sort required-capabilities))
        qualification-references (vec (sort qualification-references))
        environment-kinds (vec (sort environment-kinds))]
    (when-not (and (contains? objectives objective)
                   (pos-int? required-runs)
                   (pos-int? required-matching-runs)
                   (<= required-matching-runs required-runs)
                   (every? distinctness-dimensions distinct-by)
                   (= (count distinct-by) (count (set distinct-by)))
                   (contains? #{:permitted :prohibited} repeat-runner-policy)
                   (or (= :permitted repeat-runner-policy)
                       (some #{:runner-identity} distinct-by))
                   (boolean? opt-in-required?)
                   (seq allowed-disclosure-modes)
                   (every? participation/disclosure-modes allowed-disclosure-modes)
                   (every? keyword? required-capabilities)
                   (every? root? qualification-references)
                   (seq environment-kinds)
                   (every? keyword? environment-kinds)
                   (contains? attribution-modes attribution-mode))
      (throw (ex-info "Invalid research execution policy"
                      {:objective objective :required-runs required-runs
                       :required-matching-runs required-matching-runs
                       :distinct-by distinct-by
                       :repeat-runner-policy repeat-runner-policy})))
    (let [body {:artifact/schema policy-schema
                :execution/objective objective
                :execution/required-runs required-runs
                :execution/required-matching-runs required-matching-runs
                :execution/distinct-by distinct-by
                :execution/repeat-runner-policy repeat-runner-policy
                :eligibility/opt-in-required? opt-in-required?
                :eligibility/allowed-disclosure-modes allowed-disclosure-modes
                :eligibility/required-capabilities required-capabilities
                :eligibility/qualification-references qualification-references
                :eligibility/environment-kinds environment-kinds
                :attribution/mode attribution-mode
                :correctness/meaning :corroboration-does-not-establish-validity}
          rooted (assoc body :research-execution-policy/root
                        (execution-policy-root body))]
      (when-not (valid-execution-policy? rooted)
        (throw (ex-info "Invalid rooted research execution policy" {})))
      rooted)))

(defn job-root [job]
  (root :research-execution-job-v1
        (dissoc job :research-execution-job/root)))

(defn valid-job? [job]
  (and (map? job)
       (= job-schema (:artifact/schema job))
       (= #{:artifact/schema :job/class :workload/root
            :distributed-experiment-profile/root
            :distributed-scheduler-policy/root
            :research-execution-environment/root
            :research-execution-policy/root :research-execution-job/root}
          (set (keys job)))
       (keyword? (:job/class job))
       (every? #(root? (get job %))
               [:workload/root :distributed-experiment-profile/root
                :distributed-scheduler-policy/root
                :research-execution-environment/root
                :research-execution-policy/root])
       (= (:research-execution-job/root job)
          (try (job-root job) (catch Exception _ nil)))))

(defn build-job
  [{:keys [job-class workload-root distributed-profile-root
           distributed-policy-root execution-environment-root
           execution-policy-root]}]
  (when-not (and (keyword? job-class)
                 (every? root? [workload-root distributed-profile-root
                                distributed-policy-root execution-environment-root
                                execution-policy-root]))
    (throw (ex-info "Invalid research execution job inputs" {})))
  (let [body {:artifact/schema job-schema
              :job/class job-class
              :workload/root workload-root
              :distributed-experiment-profile/root distributed-profile-root
              :distributed-scheduler-policy/root distributed-policy-root
              :research-execution-environment/root execution-environment-root
              :research-execution-policy/root execution-policy-root}
        rooted (assoc body :research-execution-job/root (job-root body))]
    (when-not (valid-job? rooted)
      (throw (ex-info "Invalid rooted research execution job" {})))
    rooted))

(defn run-slots [job execution-policy]
  (mapv (fn [index]
          {:run-slot/index index
           :research-run-slot/root
           (root :research-run-slot-v1
                 {:research-execution-job/root (:research-execution-job/root job)
                  :research-execution-policy/root
                  (:research-execution-policy/root execution-policy)
                  :run-slot/index index})})
        (range (:execution/required-runs execution-policy))))

(defn eligibility
  "Evaluate selection eligibility without executing a job.

   Qualification references are matched as declared roots only. Their truth or
   issuer authority is intentionally outside this slice."
  [job execution-environment execution-policy runner-participation]
  (when-not (and (valid-job? job)
                 (:valid? (environment/validate-environment execution-environment))
                 (valid-execution-policy? execution-policy)
                 (:valid? (participation/validate-participation runner-participation))
                 (= (:research-execution-environment/root job)
                    (:research-execution-environment/root execution-environment))
                 (= (:research-execution-policy/root job)
                    (:research-execution-policy/root execution-policy)))
    (throw (ex-info "Invalid eligibility input artifact" {})))
  (let [capabilities (set (:runner/capabilities runner-participation))
        qualifications (set (:runner/qualification-references runner-participation))
        reasons
        (cond-> []
          (and (:eligibility/opt-in-required? execution-policy)
               (not (:participation/opted-in? runner-participation)))
          (conj :runner-not-opted-in)
          (not (:participation/available? runner-participation))
          (conj :runner-unavailable)
          (not (contains? (set (:participation/job-classes runner-participation))
                          (:job/class job)))
          (conj :job-class-not-accepted)
          (not (contains? (set (:runner/environment-kinds runner-participation))
                          (:execution-environment/kind execution-environment)))
          (conj :environment-not-supported)
          (not (contains? (set (:eligibility/environment-kinds execution-policy))
                          (:execution-environment/kind execution-environment)))
          (conj :environment-policy-mismatch)
          (not (contains? (set (:eligibility/allowed-disclosure-modes execution-policy))
                          (:runner/disclosure-mode runner-participation)))
          (conj :disclosure-mode-ineligible)
          (not (set/subset? (set (:eligibility/required-capabilities execution-policy))
                            capabilities))
          (conj :required-capability-missing)
          (not (set/subset? (set (:eligibility/qualification-references execution-policy))
                            qualifications))
          (conj :qualification-reference-missing))]
    {:eligible? (empty? reasons) :ineligibility/reasons (vec reasons)}))

(defn- assignment-root [assignment]
  (root :research-run-assignment-v1
        (dissoc assignment :research-run-assignment/root)))

(defn valid-assignment? [assignment]
  (and (map? assignment)
       (= assignment-schema (:artifact/schema assignment))
       (= #{:artifact/schema :research-execution-job/root
            :research-run-slot/root :run-slot/index
            :research-execution-environment/root
            :research-execution-policy/root :runner-participation/root
            :runner/reference :runner/site-id :assignment/status
            :research-run-assignment/root}
          (set (keys assignment)))
       (= :assigned (:assignment/status assignment))
       (every? #(root? (get assignment %))
               [:research-execution-job/root :research-run-slot/root
                :research-execution-environment/root
                :research-execution-policy/root :runner-participation/root
                :runner/reference])
       (keyword? (:runner/site-id assignment))
       (integer? (:run-slot/index assignment))
       (not (neg? (:run-slot/index assignment)))
       (= (:research-run-assignment/root assignment)
          (try (assignment-root assignment) (catch Exception _ nil)))))

(defn- used-values [assignments key]
  (set (map key assignments)))

(defn distinctness-allows?
  [execution-policy assignments runner-participation]
  (let [dimensions (set (:execution/distinct-by execution-policy))]
    (and (or (not (contains? dimensions :runner-identity))
             (not (contains? (used-values assignments :runner/reference)
                             (:runner/reference runner-participation))))
         (or (not (contains? dimensions :site))
             (not (contains? (used-values assignments :runner/site-id)
                             (:runner/site-id runner-participation)))))))

(defn valid-assignment-set?
  "Validate slot uniqueness and the policy's explicit identity/site basis.
   Different worker IDs are never treated as runner or site independence."
  [execution-policy assignments]
  (let [slot-roots (map :research-run-slot/root assignments)
        runner-refs (map :runner/reference assignments)
        sites (map :runner/site-id assignments)
        dimensions (set (:execution/distinct-by execution-policy))]
    (and (valid-execution-policy? execution-policy)
         (every? valid-assignment? assignments)
         (<= (count assignments) (:execution/required-runs execution-policy))
         (= (count slot-roots) (count (set slot-roots)))
         (or (not (contains? dimensions :runner-identity))
             (= (count runner-refs) (count (set runner-refs))))
         (or (not (contains? dimensions :site))
             (= (count sites) (count (set sites)))))))

(defn steal-next
  "Assign the first pending rooted run slot to an idle runner when eligible.

   Returning nil means selection exclusion, not an execution failure. Existing
   assignments enforce runner/site distinctness before work is stolen."
  [job execution-environment execution-policy runner-participation assignments]
  (when-not (and (valid-job? job)
                 (:valid? (environment/validate-environment execution-environment))
                 (valid-execution-policy? execution-policy)
                 (:valid? (participation/validate-participation runner-participation))
                 (valid-assignment-set? execution-policy assignments)
                 (every? #(= (:research-execution-job/root job)
                             (:research-execution-job/root %)) assignments)
                 (= (:research-execution-environment/root job)
                    (:research-execution-environment/root execution-environment))
                 (= (:research-execution-policy/root job)
                    (:research-execution-policy/root execution-policy)))
    (throw (ex-info "Job environment or execution policy substitution" {})))
  (let [assigned-slots (set (map :research-run-slot/root assignments))
        pending (remove #(contains? assigned-slots (:research-run-slot/root %))
                        (run-slots job execution-policy))
        eligibility-result (eligibility job execution-environment execution-policy
                                        runner-participation)]
    (when (and (seq pending)
               (:eligible? eligibility-result)
               (distinctness-allows? execution-policy assignments runner-participation))
      (let [slot (first pending)
            body {:artifact/schema assignment-schema
                  :research-execution-job/root (:research-execution-job/root job)
                  :research-run-slot/root (:research-run-slot/root slot)
                  :run-slot/index (:run-slot/index slot)
                  :research-execution-environment/root
                  (:research-execution-environment/root execution-environment)
                  :research-execution-policy/root
                  (:research-execution-policy/root execution-policy)
                  :runner-participation/root
                  (:runner-participation/root runner-participation)
                  :runner/reference (:runner/reference runner-participation)
                  :runner/site-id (:runner/site-id runner-participation)
                  :assignment/status :assigned}
            rooted (assoc body :research-run-assignment/root
                          (assignment-root body))]
        rooted))))

(defn valid-run-evidence? [evidence]
  (and (map? evidence)
       (= evidence-schema (:artifact/schema evidence))
       (= :complete (:execution/outcome evidence))
       (= :declared-simulation (:forensic/assurance evidence))
       (= :not-evaluated (:primitive-validation/status evidence))
       (= :not-evaluated (:corroboration/status evidence))
       (every? #(root? (get evidence %))
               [:research-execution-job/root :research-run-assignment/root
                :research-execution-environment/root
                :research-execution-policy/root :runner-participation/root
                :runner/reference :distributed-experiment-profile/root
                :distributed-scheduler-policy/root :distributed-scheduler-run/root
                :environment-before/root :action-input/root
                :environment-after/root :semantic-result/root])
       (= (:research-run-evidence/root evidence)
          (try (root :research-run-evidence-v1
                     (dissoc evidence :research-run-evidence/root))
               (catch Exception _ nil)))))

(defn build-run-evidence
  "Bind one existing successful simulated distributed run to its environment,
   runner, job slot, and before/action/after commitments.

   The commitments are declaration-only in this slice. A future Anvil adapter
   must derive and verify them at execution cutpoints."
  [job execution-environment execution-policy runner-participation assignment
   distributed-run {:keys [environment-before-root action-input-root
                           environment-after-root]}]
  (when-not (and (valid-job? job)
                 (:valid? (environment/validate-environment execution-environment))
                 (valid-execution-policy? execution-policy)
                 (:valid? (participation/validate-participation runner-participation))
                 (valid-assignment? assignment)
                 (= :complete (:execution/status distributed-run))
                 (= (:research-execution-job/root job)
                    (:research-execution-job/root assignment))
                 (= (:runner-participation/root runner-participation)
                    (:runner-participation/root assignment))
                 (= (:workload/root job) (:workload/root distributed-run))
                 (= (:distributed-experiment-profile/root job)
                    (:distributed-experiment-profile/root distributed-run))
                 (= (:distributed-scheduler-policy/root job)
                    (:distributed-scheduler-policy/root distributed-run))
                 (= (:research-execution-environment/root job)
                    (:research-execution-environment/root execution-environment))
                 (= (:research-execution-policy/root job)
                    (:research-execution-policy/root execution-policy))
                 (every? root? [environment-before-root action-input-root
                                environment-after-root])
                 (= (:workload/root job) action-input-root))
    (throw (ex-info "Run evidence provenance mismatch" {})))
  (let [body (cond->
              {:artifact/schema evidence-schema
               :research-execution-job/root (:research-execution-job/root job)
               :research-run-assignment/root (:research-run-assignment/root assignment)
               :research-execution-environment/root
               (:research-execution-environment/root execution-environment)
               :research-execution-policy/root
               (:research-execution-policy/root execution-policy)
               :runner-participation/root
               (:runner-participation/root runner-participation)
               :runner/reference (:runner/reference runner-participation)
               :runner/disclosure-mode (:runner/disclosure-mode runner-participation)
               :runner/site-id (:runner/site-id runner-participation)
               :distributed-experiment-profile/root
               (:distributed-experiment-profile/root distributed-run)
               :distributed-scheduler-policy/root
               (:distributed-scheduler-policy/root distributed-run)
               :distributed-scheduler-run/root
               (:distributed-scheduler-run/root distributed-run)
               :execution/attempt-roots
               (mapv :distributed-execution-attempt/root
                     (:execution/attempts distributed-run))
               :execution/failure-receipt-roots
               (mapv :distributed-execution-failure-receipt/root
                     (:failure-receipts distributed-run))
               :environment-before/root environment-before-root
               :action-input/root action-input-root
               :environment-after/root environment-after-root
               :semantic-result/root (:committed/root distributed-run)
               :execution/outcome :complete
               :forensic/assurance :declared-simulation
               :primitive-validation/status :not-evaluated
               :corroboration/status :not-evaluated}
               (= :include-runner-reference (:attribution/mode execution-policy))
               (assoc :published/runner-reference
                      (:runner/reference runner-participation)))
        rooted (assoc body :research-run-evidence/root
                      (root :research-run-evidence-v1 body))]
    (when-not (valid-run-evidence? rooted)
      (throw (ex-info "Invalid rooted research run evidence" {})))
    rooted))

(defn corroboration-view
  "Non-authoritative presentation of constituent result groups.

   This does not accept a result, validate a primitive, establish a proof, or
   produce a canonical corroboration artifact."
  [run-evidence]
  {:constituent-run-evidence-roots (mapv :research-run-evidence/root run-evidence)
   :matching-result-groups
   (into (sorted-map)
         (map (fn [[result-root evidence]]
                [result-root (mapv :research-run-evidence/root evidence)]))
         (group-by :semantic-result/root run-evidence))
   :primitive-validation/status :not-evaluated
   :proof/status :not-established})
