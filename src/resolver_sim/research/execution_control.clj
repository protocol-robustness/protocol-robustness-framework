(ns resolver-sim.research.execution-control
  "Transport-independent cancellation, realized reachability, and early
   execution-sufficiency semantics for remote research jobs.

   Publication is not delivery, corroboration is not correctness, and result
   readiness is not research-claim acceptance or application authority."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.research.accelerated-accounting :as accounting]
            [resolver-sim.research.execution-assurance :as assurance]
            [resolver-sim.research.proof-generation :as proof]
            [resolver-sim.research.remote-execution :as remote]))

(def ^:const cancellation-schema "research-job-cancellation.v1")
(def ^:const reachability-schema "research-realized-reachability.v1")
(def ^:const observation-schema "research-runner-control-observation.v1")
(def ^:const lifecycle-schema "research-work-lifecycle-event.v1")
(def ^:const cancellation-policy-schema "research-cancellation-policy.v1")
(def ^:const proof-verification-schema "research-proof-verification-evidence.v1")
(def ^:const sufficiency-policy-schema "research-execution-sufficiency-policy.v1")
(def ^:const sufficiency-evaluation-schema "research-execution-sufficiency-evaluation.v1")
(def ^:const analysis-schema "research-execution-control-analysis.v1")

(def cancellation-reasons
  #{:user-requested :execution-sufficient :superseded :resource-limit})
(def cancellation-scopes #{:entire-job :surplus-run-slots :proof-jobs :verification-work})
(def cancellation-modes #{:immediate :cooperative :atomic})
(def route-kinds #{:dependency-retrieval :job-delivery :cancellation-delivery
                   :proof-job-delivery :verification-delivery})
(def reachability-outcomes #{:realized :unreachable})
(def lifecycle-kinds #{:started :completed :stopped})

(defn- root [domain value]
  (hash-ref/sha256-ref (hc/domain-hash domain value)))
(defn- root? [value] (hash-ref/valid-sha256-ref? value))
(defn- canonical-roots? [xs]
  (and (vector? xs) (= xs (vec (sort xs)))
       (= (count xs) (count (set xs))) (every? root? xs)))

(defn cancellation-root [event]
  (root :research-job-cancellation-v1
        (dissoc event :research-job-cancellation/root)))

(defn build-cancellation
  [{:keys [job-root control-epoch reason requested-cutpoint scope target-roots]}]
  (let [targets (vec (sort target-roots))]
    (when-not (and (root? job-root) (integer? control-epoch) (not (neg? control-epoch))
                   (contains? cancellation-reasons reason)
                   (integer? requested-cutpoint) (not (neg? requested-cutpoint))
                   (contains? cancellation-scopes scope)
                   (canonical-roots? targets)
                   (or (= scope :entire-job) (seq targets)))
      (throw (ex-info "Invalid research job cancellation" {})))
    (let [body {:artifact/schema cancellation-schema
                :research-execution-job/root job-root
                :control/epoch control-epoch
                :cancellation/reason reason
                :cancellation/requested-cutpoint requested-cutpoint
                :cancellation/scope scope
                :cancellation/target-roots targets
                :control/transport-semantics :publish-then-observe}
          rooted (assoc body :research-job-cancellation/root
                        (cancellation-root body))]
      rooted)))

(defn ingest-cancellation
  "Index canonical control events by job and epoch. Exact replay is a no-op;
   two different events claiming the same job/epoch are a hard conflict."
  [control-state event]
  (let [key [(:research-execution-job/root event) (:control/epoch event)]
        existing (get control-state key)]
    (when-not (= (:research-job-cancellation/root event)
                 (try (cancellation-root event) (catch Exception _ nil)))
      (throw (ex-info "Invalid cancellation event" {})))
    (cond
      (nil? existing) (assoc control-state key event)
      (= (:research-job-cancellation/root existing)
         (:research-job-cancellation/root event)) control-state
      :else (throw (ex-info "Conflicting cancellation event at control epoch"
                            {:control/conflict :same-job-epoch
                             :research-execution-job/root (first key)
                             :control/epoch (second key)})))))

(defn reachability-root [evidence]
  (root :research-realized-reachability-v1
        (dissoc evidence :research-realized-reachability/root)))

(defn build-reachability
  [{:keys [job-root route-kind subject-root source-root destination-root
           declared-reachable? outcome attempt-cutpoint]}]
  (when-not (and (every? root? [job-root subject-root source-root destination-root])
                 (contains? route-kinds route-kind) (boolean? declared-reachable?)
                 (contains? reachability-outcomes outcome)
                 (integer? attempt-cutpoint) (not (neg? attempt-cutpoint)))
    (throw (ex-info "Invalid realized reachability evidence" {})))
  (let [body {:artifact/schema reachability-schema
              :research-execution-job/root job-root
              :reachability/route-kind route-kind
              :reachability/subject-root subject-root
              :reachability/source-root source-root
              :reachability/destination-root destination-root
              :reachability/declared? declared-reachable?
              :reachability/delivery-attempted? true
              :reachability/outcome outcome
              :reachability/realized? (= :realized outcome)
              :reachability/attempt-cutpoint attempt-cutpoint
              :reachability/meaning :simulated-usable-path-observation}
        rooted (assoc body :research-realized-reachability/root
                      (reachability-root body))]
    rooted))

(defn control-observation-root [observation]
  (root :research-runner-control-observation-v1
        (dissoc observation :research-runner-control-observation/root)))

(defn build-control-observation
  [{:keys [job-root assignment-root runner-participation-root runner-reference
           highest-observed-epoch cancellation-root observation-cutpoint
           last-work-unit-started-root last-work-unit-completed-root status
           reachability]}]
  (when-not (and (every? root? [job-root runner-participation-root runner-reference])
                 (or (nil? assignment-root) (root? assignment-root))
                 (= reachability-schema (:artifact/schema reachability))
                 (= (:research-realized-reachability/root reachability)
                    (try (reachability-root reachability) (catch Exception _ nil)))
                 (= :cancellation-delivery (:reachability/route-kind reachability))
                 (:reachability/realized? reachability)
                 (= job-root (:research-execution-job/root reachability))
                 (= runner-reference (:reachability/destination-root reachability))
                 (integer? highest-observed-epoch) (not (neg? highest-observed-epoch))
                 (integer? observation-cutpoint) (not (neg? observation-cutpoint))
                 (contains? #{:observed :stopped-acknowledged} status)
                 (or (nil? cancellation-root) (root? cancellation-root))
                 (or (nil? last-work-unit-started-root) (root? last-work-unit-started-root))
                 (or (nil? last-work-unit-completed-root) (root? last-work-unit-completed-root)))
    (throw (ex-info "Invalid runner control observation" {})))
  (let [body {:artifact/schema observation-schema
              :research-execution-job/root job-root
              :research-run-assignment/root assignment-root
              :runner-participation/root runner-participation-root
              :runner/reference runner-reference
              :control/highest-observed-epoch highest-observed-epoch
              :research-job-cancellation/root cancellation-root
              :control/observation-cutpoint observation-cutpoint
              :work-unit/last-started-root last-work-unit-started-root
              :work-unit/last-completed-root last-work-unit-completed-root
              :control-observation/status status
              :research-realized-reachability/root
              (:research-realized-reachability/root reachability)}
        rooted (assoc body :research-runner-control-observation/root
                      (control-observation-root body))]
    rooted))

(defn cancellation-observed?
  [cancellation observation]
  (and (= (:research-execution-job/root cancellation)
          (:research-execution-job/root observation))
       (= (:research-job-cancellation/root cancellation)
          (:research-job-cancellation/root observation))
       (>= (:control/highest-observed-epoch observation)
           (:control/epoch cancellation))))

(defn lifecycle-root [event]
  (root :research-work-lifecycle-event-v1
        (dissoc event :research-work-lifecycle-event/root)))

(defn build-lifecycle-event
  [{:keys [job-root assignment-root work-unit-root runner-reference kind cutpoint
           highest-observed-control-epoch observed-cancellation-root consequence-root]}]
  (when-not (and (every? root? [job-root assignment-root work-unit-root runner-reference])
                 (contains? lifecycle-kinds kind)
                 (integer? cutpoint) (not (neg? cutpoint))
                 (integer? highest-observed-control-epoch)
                 (not (neg? highest-observed-control-epoch))
                 (or (nil? observed-cancellation-root)
                     (root? observed-cancellation-root))
                 (or (nil? consequence-root) (root? consequence-root)))
    (throw (ex-info "Invalid work lifecycle event" {})))
  (let [body {:artifact/schema lifecycle-schema
              :research-execution-job/root job-root
              :research-run-assignment/root assignment-root
              :work-unit/root work-unit-root
              :runner/reference runner-reference
              :work/event-kind kind :work/event-cutpoint cutpoint
              :control/highest-observed-epoch highest-observed-control-epoch
              :research-job-cancellation/root observed-cancellation-root
              :work/consequence-root consequence-root}]
    (assoc body :research-work-lifecycle-event/root (lifecycle-root body))))

(defn cancellation-policy-root [policy]
  (root :research-cancellation-policy-v1
        (dissoc policy :research-cancellation-policy/root)))

(defn build-cancellation-policy [default-mode work-unit-modes]
  (when-not (and (contains? cancellation-modes default-mode)
                 (map? work-unit-modes) (every? root? (keys work-unit-modes))
                 (every? cancellation-modes (vals work-unit-modes)))
    (throw (ex-info "Invalid cancellation policy" {})))
  (let [body {:artifact/schema cancellation-policy-schema
              :cancellation/default-mode default-mode
              :cancellation/work-unit-modes (into (sorted-map) work-unit-modes)
              :cancellation/safe-cutpoint-semantics :declared-operation-boundaries}]
    (assoc body :research-cancellation-policy/root
           (cancellation-policy-root body))))

(defn cancellation-stop-decision
  "Deterministically model completed atomic operations around an observed
   cancellation. Operations carry :operation/root, :start, :end and
   :safe-cutpoint?. Only completed roots are returned as canonical work."
  [mode operations observed-cutpoint]
  (when-not (and (contains? cancellation-modes mode)
                 (integer? observed-cutpoint)
                 (every? #(and (root? (:operation/root %))
                               (integer? (:start %)) (integer? (:end %))
                               (< (:start %) (:end %))
                               (boolean? (:safe-cutpoint? %))) operations))
    (throw (ex-info "Invalid cancellation stop simulation" {})))
  (let [before (filter #(<= (:end %) observed-cutpoint) operations)
        current (first (filter #(and (< (:start %) observed-cutpoint)
                                     (< observed-cutpoint (:end %))) operations))
        completed (case mode
                    :immediate before
                    :atomic (cond-> (vec before) current (conj current))
                    :cooperative
                    (let [at-safe-boundary? (and (seq before)
                                                 (= observed-cutpoint
                                                    (:end (last before)))
                                                 (:safe-cutpoint? (last before)))
                          remaining (if at-safe-boundary?
                                      [] (drop (count before) operations))
                          through-safe (loop [xs remaining acc []]
                                         (if-let [op (first xs)]
                                           (let [acc* (conj acc op)]
                                             (if (:safe-cutpoint? op) acc*
                                                 (recur (rest xs) acc*))) acc))]
                      (concat before through-safe)))
        completed (vec (distinct completed))]
    {:execution/status :cancelled
     :cancellation/mode mode
     :work/completed-operation-roots (mapv :operation/root completed)
     :work/discarded-incomplete? (boolean (and current (= :immediate mode)))
     :work/no-next-operation-started? true
     :cancellation/observed-cutpoint observed-cutpoint}))

(defn cancellation-applies?
  [cancellation target-root]
  (or (= :entire-job (:cancellation/scope cancellation))
      (and target-root
           (contains? (set (:cancellation/target-roots cancellation)) target-root))))

(defn scheduling-eligibility
  "Cancellation is a selection/control outcome, never an execution failure.
   Scoped cancellations apply only to an explicitly targeted rooted unit."
  ([job-root cancellation observation action]
   (scheduling-eligibility job-root cancellation observation action nil))
  ([job-root cancellation observation action target-root]
   (let [blocked? (and cancellation observation
                       (= job-root (:research-execution-job/root cancellation))
                       (cancellation-observed? cancellation observation)
                       (cancellation-applies? cancellation target-root))]
     {:eligible? (not blocked?)
      :selection/outcome (if blocked? :cancelled :eligible)
      :selection/action action
      :selection/target-root target-root
      :execution/failure? false})))

(defn controlled-steal-next
  [job environment execution-policy runner assignments cancellation observation]
  (let [assigned-roots (set (map :research-run-slot/root assignments))
        target-root (some (fn [slot]
                            (when-not (contains? assigned-roots
                                                 (:research-run-slot/root slot))
                              (:research-run-slot/root slot)))
                          (remote/run-slots job execution-policy))
        eligibility (scheduling-eligibility
                     (:research-execution-job/root job) cancellation observation
                     :work-steal target-root)]
    (when (:eligible? eligibility)
      (remote/steal-next job environment execution-policy runner assignments))))

(defn proof-verification-root [evidence]
  (root :research-proof-verification-evidence-v1
        (dissoc evidence :research-proof-verification-evidence/root)))

(defn valid-proof-verification? [evidence]
  (and (= proof-verification-schema (:artifact/schema evidence))
       (= :verified (:proof-verification/status evidence))
       (= :not-established (:primitive-validation/status evidence))
       (= :not-established (:application-authority/status evidence))
       (every? root? [(:research-proof-generation-receipt/root evidence)
                      (:semantic-result/root evidence)
                      (:proof-verification/root evidence)
                      (:verifier/reference evidence)
                      (:verifier-registry/root evidence)])
       (integer? (:proof-verification/cutpoint evidence))
       (= (:research-proof-verification-evidence/root evidence)
          (try (proof-verification-root evidence) (catch Exception _ nil)))))

(defn build-proof-verification
  [{:keys [proof-receipt verification-root verifier-reference
           verifier-registry-root verification-cutpoint]}]
  (when-not (and (proof/valid-receipt? proof-receipt)
                 (every? root? [verification-root verifier-reference verifier-registry-root])
                 (= verifier-registry-root (:verifier-registry/root proof-receipt))
                 (integer? verification-cutpoint) (not (neg? verification-cutpoint)))
    (throw (ex-info "Invalid proof verification evidence" {})))
  (let [body {:artifact/schema proof-verification-schema
              :research-proof-generation-receipt/root
              (:research-proof-generation-receipt/root proof-receipt)
              :semantic-result/root (:semantic-result/root proof-receipt)
              :proof-verification/root verification-root
              :verifier/reference verifier-reference
              :verifier-registry/root verifier-registry-root
              :proof-verification/status :verified
              :proof-verification/cutpoint verification-cutpoint
              :primitive-validation/status :not-established
              :application-authority/status :not-established}]
    (assoc body :research-proof-verification-evidence/root
           (proof-verification-root body))))

(defn sufficiency-policy-root [policy]
  (root :research-execution-sufficiency-policy-v1
        (dissoc policy :research-execution-sufficiency-policy/root)))

(defn build-sufficiency-policy
  [{:keys [execution-policy-root assurance-policy-root assured-job-root required-valid-runs
           required-matching-runs minimum-distinct-runners minimum-distinct-sites
           minimum-distinct-implementations proof-required?
           verified-proof-required? verifier-registry-root cancel-surplus?]}]
  (when-not (and (every? root? [execution-policy-root assurance-policy-root
                                assured-job-root])
                 (pos-int? required-valid-runs) (pos-int? required-matching-runs)
                 (<= required-matching-runs required-valid-runs)
                 (every? #(and (integer? %) (not (neg? %)) (<= % required-valid-runs))
                         [minimum-distinct-runners minimum-distinct-sites
                          minimum-distinct-implementations])
                 (boolean? proof-required?) (boolean? verified-proof-required?)
                 (or (not verified-proof-required?) proof-required?)
                 (if proof-required? (root? verifier-registry-root)
                     (nil? verifier-registry-root))
                 (boolean? cancel-surplus?))
    (throw (ex-info "Invalid execution sufficiency policy" {})))
  (let [body {:artifact/schema sufficiency-policy-schema
              :research-execution-policy/root execution-policy-root
              :research-execution-assurance-policy/root assurance-policy-root
              :research-execution-job-assurance/root assured-job-root
              :sufficiency/required-valid-runs required-valid-runs
              :sufficiency/required-matching-runs required-matching-runs
              :sufficiency/minimum-distinct-runners minimum-distinct-runners
              :sufficiency/minimum-distinct-sites minimum-distinct-sites
              :sufficiency/minimum-distinct-implementations
              minimum-distinct-implementations
              :sufficiency/proof-required? proof-required?
              :sufficiency/verified-proof-required? verified-proof-required?
              :verifier-registry/root verifier-registry-root
              :sufficiency/cancel-surplus? cancel-surplus?
              :correctness/meaning :result-readiness-not-claim-acceptance}]
    (assoc body :research-execution-sufficiency-policy/root
           (sufficiency-policy-root body))))

(defn sufficiency-evaluation-root [evaluation]
  (root :research-execution-sufficiency-evaluation-v1
        (dissoc evaluation :research-execution-sufficiency-evaluation/root)))

(defn evaluate-sufficiency
  [job-root policy run-evidence proof-receipts proof-verifications cutpoint]
  (when-not (and (root? job-root)
                 (= (:research-execution-sufficiency-policy/root policy)
                    (sufficiency-policy-root policy))
                 (integer? cutpoint) (not (neg? cutpoint))
                 (every? assurance/valid-run-assurance-evidence? run-evidence)
                 (every? proof/valid-receipt? proof-receipts)
                 (every? valid-proof-verification? proof-verifications)
                 (every? #(= (:research-execution-job-assurance/root policy)
                             (:research-execution-job-assurance/root %)) run-evidence)
                 (every? #(= (:research-execution-job-assurance/root policy)
                             (:research-execution-job-assurance/root %)) proof-receipts))
    (throw (ex-info "Invalid execution sufficiency inputs" {})))
  (let [runs (vals (into {} (map (juxt :research-run-assurance-evidence/root identity)
                                  run-evidence)))
        proofs (vals (into {} (map (juxt :research-proof-generation-receipt/root identity)
                                   proof-receipts)))
        verifications (vals (into {} (map (juxt :research-proof-verification-evidence/root
                                                identity) proof-verifications)))
        groups (group-by :semantic-result/root runs)
        [matching-root matching-runs]
        (first (sort-by (fn [[result-root members]] [(- (count members)) result-root]) groups))
        matching-runs (vec matching-runs)
        runners (set (map :runner/reference matching-runs))
        sites (set (map :runner/site-id matching-runs))
        implementations (set (map :research-implementation-identity/root matching-runs))
        matching-proofs (filter #(= matching-root (:semantic-result/root %)) proofs)
        matching-proof-roots (set (map :research-proof-generation-receipt/root matching-proofs))
        valid-verifications (filter #(and (= :verified (:proof-verification/status %))
                                          (= (:verifier-registry/root policy)
                                             (:verifier-registry/root %))
                                          (contains? matching-proof-roots
                                                     (:research-proof-generation-receipt/root %)))
                                    verifications)
        checks {:valid-runs (>= (count runs) (:sufficiency/required-valid-runs policy))
                :matching-runs (>= (count matching-runs)
                                   (:sufficiency/required-matching-runs policy))
                :distinct-runners (>= (count runners)
                                      (:sufficiency/minimum-distinct-runners policy))
                :distinct-sites (>= (count sites)
                                    (:sufficiency/minimum-distinct-sites policy))
                :distinct-implementations
                (>= (count implementations)
                    (:sufficiency/minimum-distinct-implementations policy))
                :proof-generated (boolean
                                  (or (not (:sufficiency/proof-required? policy))
                                      (seq matching-proofs)))
                :proof-verified (boolean
                                 (or (not (:sufficiency/verified-proof-required? policy))
                                     (seq valid-verifications)))}
        sufficient? (every? true? (vals checks))
        body {:artifact/schema sufficiency-evaluation-schema
              :research-execution-job/root job-root
              :research-execution-sufficiency-policy/root
              (:research-execution-sufficiency-policy/root policy)
              :sufficiency/evaluated-cutpoint cutpoint
              :sufficiency/unique-run-roots
              (vec (sort (map :research-run-assurance-evidence/root runs)))
              :sufficiency/unique-proof-roots
              (vec (sort (map :research-proof-generation-receipt/root proofs)))
              :sufficiency/unique-verification-roots
              (vec (sort (map :research-proof-verification-evidence/root verifications)))
              :sufficiency/matching-result-root matching-root
              :sufficiency/counts {:valid-runs (count runs)
                                   :matching-runs (count matching-runs)
                                   :distinct-runners (count runners)
                                   :distinct-sites (count sites)
                                   :distinct-implementations (count implementations)
                                   :proofs-generated (count matching-proofs)
                                   :proofs-verified (count valid-verifications)}
              :sufficiency/checks (into (sorted-map) checks)
              :sufficiency/status (if sufficient? :result-ready :not-ready)
              :primitive-validation/status :not-established
              :claim-acceptance/status :not-evaluated
              :application-authority/status :not-established}]
    (assoc body :research-execution-sufficiency-evaluation/root
           (sufficiency-evaluation-root body))))

(defn cancellation-on-sufficiency
  [job-root policy evaluation next-epoch cutpoint outstanding-roots]
  (when (and (= :result-ready (:sufficiency/status evaluation))
             (:sufficiency/cancel-surplus? policy)
             (seq outstanding-roots))
    (build-cancellation {:job-root job-root :control-epoch next-epoch
                         :reason :execution-sufficient :requested-cutpoint cutpoint
                         :scope :surplus-run-slots :target-roots outstanding-roots})))

(defn cancellation-on-accounting-obligation-sufficiency
  "Create a scoped surplus cancellation only for the satisfied obligation.
   A caller cannot substitute another partition root into this derivation."
  [job-root policy obligation-evaluation next-epoch cutpoint outstanding-roots]
  (let [unit-root (:accounting-work-unit/root obligation-evaluation)
        targets (vec (sort outstanding-roots))]
    (when-not (every? #(= unit-root %) targets)
      (throw (ex-info "Accounting surplus cancellation target substitution"
                      {:expected-accounting-work-unit/root unit-root
                       :target-roots targets})))
    (when (and (= :result-ready (:sufficiency/status obligation-evaluation))
               (:sufficiency/cancel-surplus? policy)
               (seq targets))
      (build-cancellation {:job-root job-root :control-epoch next-epoch
                           :reason :execution-sufficient
                           :requested-cutpoint cutpoint
                           :scope :surplus-run-slots :target-roots targets}))))

(defn evaluate-accounting-obligation-sufficiency
  "Evaluate assurance for exactly one rooted accounting work unit.

   `executions` contains `{:partial <accounting-partial-result>
   :run-assurance <research-run-assurance-evidence>}`.  Only executions whose
   partial and run evidence bind the supplied work unit/policy participate.
   Different semantic partial roots or explicit non-comparability prevent a
   matching threshold from being satisfied; unrelated partitions never count.
   This is a derived integration view, not a new authoritative artifact."
  [job request plan work-unit policy executions proof-receipts proof-verifications]
  (when-not (and (remote/valid-job? job)
                 (= (:workload/root job) (:accounting-work-unit/root work-unit))
                 (= (:accounting-request/root request) (:accounting-request/root work-unit))
                 (some #(= (:accounting-work-unit/root work-unit)
                           (:accounting-work-unit/root %))
                       (:partition/work-units plan))
                 (= (:research-execution-sufficiency-policy/root policy)
                    (sufficiency-policy-root policy))
                 (every? (fn [{:keys [partial run-assurance comparison/status]}]
                           (and (assurance/valid-run-assurance-evidence? run-assurance)
                                (= (:research-execution-job-assurance/root policy)
                                   (:research-execution-job-assurance/root run-assurance))
                                (contains? #{nil :equal :different :not-comparable
                                             :missing :failed :cancelled}
                                           status)
                                (if (contains? #{nil :equal} status)
                                  (and (accounting/valid-partial-result? request work-unit partial)
                                       (= (:research-run-assurance-evidence/root run-assurance)
                                          (:research-run-assurance-evidence/root partial))
                                       (= (:semantic-result/root run-assurance)
                                          (accounting/partial-semantic-root partial)))
                                  (or (nil? partial)
                                      (accounting/valid-partial-result? request work-unit partial))
                                  )))
                         executions)
                 (every? proof/valid-receipt? proof-receipts)
                 (every? valid-proof-verification? proof-verifications))
    (throw (ex-info "Invalid accounting obligation sufficiency inputs" {})))
  (let [valid-executions (filter #(= :equal (:comparison/status %)) executions)
        ;; Omitted comparison status means a canonical exact partial comparison.
        valid-executions (concat valid-executions
                                 (filter #(nil? (:comparison/status %)) executions))
        unique-executions (vals
                           (into {}
                                 (map (juxt (comp :research-run-assurance-evidence/root
                                                  :run-assurance)
                                             identity)
                                      valid-executions)))
        semantic-groups (group-by #(accounting/partial-semantic-root (:partial %))
                                  unique-executions)
        [semantic-root matching] (first (sort-by (fn [[root members]]
                                                    [(- (count members)) root])
                                                  semantic-groups))
        matching (vec matching)
        disagreement? (or (> (count semantic-groups) 1)
                          (some #(contains? #{:different :not-comparable}
                                             (:comparison/status %)) executions))
        runners (set (map #(get-in % [:run-assurance :runner/reference]) matching))
        sites (set (map #(get-in % [:run-assurance :runner/site-id]) matching))
        implementations (set (map #(get-in % [:run-assurance
                                               :research-implementation-identity/root]) matching))
        matching-proofs (filter #(= semantic-root (:semantic-result/root %)) proof-receipts)
        matching-proof-roots (set (map :research-proof-generation-receipt/root matching-proofs))
        verified (filter #(and (= :verified (:proof-verification/status %))
                               (= (:verifier-registry/root policy)
                                  (:verifier-registry/root %))
                               (contains? matching-proof-roots
                                          (:research-proof-generation-receipt/root %)))
                         proof-verifications)
        checks {:semantic-result-present (boolean (seq matching))
                :comparable-results (not disagreement?)
                :valid-runs (>= (count unique-executions)
                                 (:sufficiency/required-valid-runs policy))
                :matching-runs (>= (count matching)
                                    (:sufficiency/required-matching-runs policy))
                :distinct-runners (>= (count runners)
                                       (:sufficiency/minimum-distinct-runners policy))
                :distinct-sites (>= (count sites)
                                     (:sufficiency/minimum-distinct-sites policy))
                :distinct-implementations
                (>= (count implementations)
                    (:sufficiency/minimum-distinct-implementations policy))
                :proof-generated (boolean (or (not (:sufficiency/proof-required? policy))
                                              (seq matching-proofs)))
                :proof-verified (boolean (or (not (:sufficiency/verified-proof-required? policy))
                                             (seq verified)))}
        sufficient? (every? true? (vals checks))]
    {:accounting-request/root (:accounting-request/root request)
     :accounting-work-unit/root (:accounting-work-unit/root work-unit)
     :sufficiency/semantic-result-root semantic-root
     :sufficiency/status (if sufficient? :result-ready :not-ready)
     :sufficiency/checks (into (sorted-map) checks)
     :sufficiency/counts {:valid-runs (count unique-executions)
                          :matching-runs (count matching)
                          :distinct-runners (count runners)
                          :distinct-sites (count sites)
                          :distinct-implementations (count implementations)
                          :proofs-generated (count matching-proofs)
                          :proofs-verified (count verified)}
     :sufficiency/nonmatching-statuses
     (vec (sort (keep :comparison/status executions)))}))

(defn classify-accounting-work-unit
  "Classify one work unit without granting a runner discretionary semantics.
   A canonical partial is necessary for semantic completeness; assurance can
   become redundant only after that partial and the unit-local policy hold."
  [request plan ingestion work-unit obligation-sufficiency]
  (when-not (some #(= (:accounting-work-unit/root work-unit)
                       (:accounting-work-unit/root %))
                  (:partition/work-units plan))
    (throw (ex-info "Accounting work unit is outside partition plan" {})))
  (let [partial (get ingestion (:accounting-work-unit/root work-unit))
        semantic-complete? (and partial
                                (accounting/valid-partial-result? request work-unit partial))
        assurance-ready? (= :result-ready (:sufficiency/status obligation-sufficiency))]
    {:accounting-work-unit/root (:accounting-work-unit/root work-unit)
     :work/classification (cond
                            (not semantic-complete?) :semantic-required
                            assurance-ready? :assurance-redundant
                            :else :assurance-required)
     :accounting/completeness (if semantic-complete? :complete :incomplete)
     :sufficiency/status (if semantic-complete?
                           (:sufficiency/status obligation-sufficiency)
                           :not-ready)}))

(defn accounting-work-eligibility
  "Selection gate for partition work. Semantic-required and assurance-required
   work remain stealable; surplus work and observed scoped cancellation do not."
  [job-root cancellation observation classification]
  (let [unit-root (:accounting-work-unit/root classification)
        control (scheduling-eligibility job-root cancellation observation
                                        :accounting-work-steal unit-root)]
    (assoc control :eligible?
           (and (:eligible? control)
                (contains? #{:semantic-required :assurance-required}
                           (:work/classification classification)))
           :selection/outcome
           (cond
             (not (:eligible? control)) :cancelled
             (= :assurance-redundant (:work/classification classification)) :surplus
             :else :eligible))))

(defn select-accounting-work-unit
  "Deterministically select the first rooted accounting unit that remains
   necessary. This is the work-stealing eligibility projection; dispatch stays
   with the existing runner/transport layer."
  [job-root cancellation observation classifications]
  (some (fn [classification]
          (when (:eligible? (accounting-work-eligibility
                             job-root cancellation observation classification))
            classification))
        (sort-by :accounting-work-unit/root classifications)))

(defn accounting-retry-eligibility
  "A failed partition may retry while its semantic partial is absent. After a
   semantic partial exists, only remaining unit-local assurance can justify a
   further execution; failures never count as successful constituents."
  [job-root cancellation observation classification]
  (let [selection (accounting-work-eligibility job-root cancellation observation classification)]
    (assoc selection :selection/action :accounting-retry)))

(defn execution-control-analysis-root [analysis]
  (root :research-execution-control-analysis-v1
        (dissoc analysis :research-execution-control-analysis/root)))

(defn build-control-analysis
  "Compile deterministic model-time facts. Absent milestones remain :missing;
   terminal outcome categories are never coerced to numeric values."
  [{:keys [job-root eligible-runner-roots assignments reachability-events
           lifecycle-events cancellation sufficiency-evaluation
           proof-receipts proof-verifications full-corroboration-cutpoint
           terminal-status control-observations]}]
  (let [realized-runners (->> reachability-events
                              (filter #(and (= :job-delivery (:reachability/route-kind %))
                                            (:reachability/realized? %)))
                              (map :reachability/destination-root) set)
        events-by-kind (group-by :work/event-kind lifecycle-events)
        cutpoints #(map :work/event-cutpoint (get events-by-kind %))
        first-or-missing (fn [xs] (if (seq xs) (apply min xs) :missing))
        valid-run-roots (set (:sufficiency/unique-run-roots sufficiency-evaluation))
        valid-result-cutpoints (->> (get events-by-kind :completed)
                                    (filter #(contains? valid-run-roots
                                                        (:work/consequence-root %)))
                                    (map :work/event-cutpoint))
        started-roots (set (map :work-unit/root (get events-by-kind :started)))
        stopped-events (get events-by-kind :stopped)
        cancel-cutpoint (:cancellation/requested-cutpoint cancellation)
        cancellation-lags (when cancellation
                            (map #(- (:control/observation-cutpoint %)
                                     cancel-cutpoint)
                                 (filter #(= (:research-job-cancellation/root cancellation)
                                             (:research-job-cancellation/root %))
                                         control-observations)))
        post-cancel (if cancellation
                      (filter #(> (:work/event-cutpoint %) cancel-cutpoint)
                              lifecycle-events) [])
        _ (when-not (or (nil? terminal-status)
                        (contains? #{:failed :missing :not-comparable} terminal-status))
            (throw (ex-info "Invalid execution control analysis status"
                            {:terminal-status terminal-status})))
        body {:artifact/schema analysis-schema
              :research-execution-job/root job-root
              :analysis/status (cond
                                 (= :result-ready (:sufficiency/status sufficiency-evaluation))
                                 :complete
                                 cancellation :cancelled
                                 terminal-status terminal-status
                                 :else :missing)
              :analysis/counts
              {:eligible-runners (count (set eligible-runner-roots))
               :assigned-runners (count (set (map :runner/reference assignments)))
               :realized-reachable-runners (count realized-runners)
               :started-runs (count (get events-by-kind :started))
               :completed-runs (count (get events-by-kind :completed))
               :cancelled-before-start
               (count (remove #(contains? started-roots (:work-unit/root %))
                              stopped-events))
               :cancelled-in-flight
               (count (filter #(contains? started-roots (:work-unit/root %))
                              stopped-events))
               :proofs-generated (count (set (map :research-proof-generation-receipt/root
                                                  proof-receipts)))
               :proofs-verified (count (set (map :research-proof-verification-evidence/root
                                                 proof-verifications)))
               :distinct-implementations-realized
               (get-in sufficiency-evaluation [:sufficiency/counts :distinct-implementations] 0)
               :post-cancellation-excess-events (count post-cancel)}
              :analysis/model-time
              {:time-to-first-result (first-or-missing (cutpoints :completed))
               :time-to-first-valid-result
               (first-or-missing valid-result-cutpoints)
               :time-to-first-valid-proof
               (first-or-missing (map :proof-verification/cutpoint proof-verifications))
               :time-to-policy-sufficiency
               (if (= :result-ready (:sufficiency/status sufficiency-evaluation))
                 (:sufficiency/evaluated-cutpoint sufficiency-evaluation) :missing)
               :time-to-full-requested-corroboration
               (or full-corroboration-cutpoint :missing)
               :cancellation-observation-lag
               (if (seq cancellation-lags) (apply max cancellation-lags) :missing)}
              :analysis/first-stage-observations
              {:first-complete-runner
               (if-let [event (first (sort-by (juxt :work/event-cutpoint
                                                     :runner/reference)
                                              (get events-by-kind :completed)))]
                 {:runner/reference (:runner/reference event)
                  :cutpoint (:work/event-cutpoint event)
                  :semantic-authority :none}
                 :missing)}
              :analysis/cancellation-root (:research-job-cancellation/root cancellation)
              :analysis/sufficiency-evaluation-root
              (:research-execution-sufficiency-evaluation/root sufficiency-evaluation)
              :analysis/time-semantics :deterministic-model-cutpoints
              :analysis/meaning :research-performance-not-authority}]
    (assoc body :research-execution-control-analysis/root
           (execution-control-analysis-root body))))
