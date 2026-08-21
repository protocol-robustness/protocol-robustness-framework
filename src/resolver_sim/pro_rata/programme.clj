(ns resolver-sim.pro-rata.programme
  "SP-C: exact allocation programme — plan, identity, reconciliation, receipt.

   PROGRAMME SCOPE: pro-rata-programme.v1 runs ONE canonical allocation per
   programme (single-allocation vertical slice). The multi-allocation batch
   programme is open; when it lands, reconciliation MUST generalize from
   per-stage id equality (v1) to planned/executed/receipt request-root SET
   equality, so a different request cannot satisfy reconciliation under the
   same semantic id.

   ORDER OF DEPENDENCY (built 'claim first'):
     plan (SP-C.1) -> stage algebra / exact-set reconciliation (SP-C.2)
     -> receipt verifier (SP-C.3) -> runner producing verifiable receipts (SP-C.4)
     -> proof adapters (SP-C.5, deferred).

   AGGREGATE VERDICT: receipt semantic fields (:semantic/status,
   :programme/status, :summary, :stages, :exact-set-complete,
   :validation/status) are DERIVED by derive-programme-verdict from artifacts,
   never trusted from a stored claim. An exact-verifier :unsupported verdict
   propagates as :unsupported into the aggregate (never :failed nor :pass).

   Programme identity is established BEFORE the runner exists, so every later
   programme operation has a stable thing to refer to.

   FROZEN into programme identity (SP-C.1): programme/id, exact allocation
   request root (:allocation-request-root), semantic allocation set/order
   (:semantic-ids), requested stages (:stages), validation profile/root, and
   (when requested) statement profile/root, proof profile identity, admission
   profile identity.

   EXPLICITLY EXCLUDED from identity (operational, never hashed): parallelism,
   progress callbacks, worker pools, host, timestamps, paths, cancel atom.

   INVARIANT (SP-A + SP-B tie-in): the programme validation result equals the
   exact verifier result for the exact request/result pair, and execution
   settings (serial/parallel, progress atom, callback, worker budget) cannot
   change request-root, result-root, validation status/details, evidence-root,
   or receipt semantic fields."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.economics.payoffs :as payoffs]
            [resolver-sim.pro-rata.exact-verifier :as exact-verifier]
            [resolver-sim.pro-rata.progress :as progress]
            [resolver-sim.execution.budget :as budget]))

;; ---------------------------------------------------------------------------
;; SP-C.2 — stage vocabulary (the valid stage alphabet)
;; ---------------------------------------------------------------------------

(def programme-stage-keys
  "Ordered semantic stage vocabulary of a programme."
  [:allocation :validation :evidence :statement :proof :verification :admission])

(def required-stage-keys
  "Stages every programme must execute."
  [:allocation :validation :evidence])

(def optional-stage-keys
  "Stages that may be :requested or :not-requested (proof adapters, SP-C.5)."
  [:statement :proof :verification :admission])

(def stage-allowed-statuses
  "Statuses valid for each stage. Not every status is valid for every stage;
   this is what disambiguates receipt verification."
  {:allocation #{:completed :failed :cancelled :error}
   :validation #{:passed :failed :unsupported :error}
   :evidence #{:completed :failed :cancelled :error}
   :statement #{:completed :not-requested :failed :cancelled :error}
   :proof #{:completed :not-requested :failed :cancelled :error}
   :verification #{:passed :not-requested :failed :unsupported :error}
   :admission #{:completed :not-requested :failed :cancelled :error}})

(defn valid-stage-status?
  "True when `status` is a legal state for `stage`."
  [stage status]
  (contains? (get stage-allowed-statuses stage #{}) status))

(defn- normalize-stages
  "Normalize a plan's `:stages` map. Required stages default to :requested;
   optional stages default to :not-requested. Rejects unknown stages and
   non-request flags."
  [stages]
  (let [stages (or stages {})
        unknown (vec (remove (set programme-stage-keys) (keys stages)))]
    (when (seq unknown)
      (throw (ex-info "Unknown programme stage" {:unknown unknown
                                                 :known programme-stage-keys})))
    (reduce (fn [acc k]
              (let [v (get stages k (if (contains? (set required-stage-keys) k)
                                      :requested
                                      :not-requested))]
                (when-not (#{:requested :not-requested} v)
                  (throw (ex-info "Stage flag must be :requested or :not-requested"
                                  {:stage k :value v})))
                (assoc acc k v)))
            {}
            programme-stage-keys)))

;; ---------------------------------------------------------------------------
;; SP-C.1 — canonical programme plan and identity
;; ---------------------------------------------------------------------------

(def ^:const plan-schema-version "programme-plan.v1")

(defn allocation-request-root
  "Root identity of the exact allocation request. Frozen and independent of any
   execution state (no parallelism/progress/budget/timestamps)."
  [{:keys [schema-version amount participants policy use-case unit]}]
  (hc/domain-hash :programme-allocation-request-v1
                  {:schema-version (or schema-version 1)
                   :amount amount
                   :participants (mapv #(select-keys % [:id :weight :cap]) participants)
                   :policy (or policy {})
                   :use-case use-case
                   :unit unit}))

(defn semantic-allocation-ids
  "The semantic allocation set/order: participant IDs in request order."
  [request]
  (mapv :id (:participants request)))

(defn- profile-root
  "Root identity of an optional profile when its stage is :requested and the
   profile/identity is present, else nil."
  [plan profile-key stage-key tag]
  (when (and (= :requested (get-in plan [:stages stage-key]))
             (some? (get plan profile-key)))
    (hc/domain-hash tag (get plan profile-key))))

(defn canonical-programme-plan
  "Normalize a programme plan into its frozen, identity-bearing form.

   Two modes:
     * raw input (plan has :request)   — request-root / semantic-ids are derived.
     * identity mode (no :request)     — an already-canonical plan is revalidated
       against its own frozen fields, so canonicalization is idempotent.

   Only the frozen field set survives; operational keys (parallelism, progress,
   worker pools, host, timestamps, paths, cancel atom) are deliberately dropped."
  [plan]
  (let [has-request (some? (:request plan))
        cid (or (:programme/id plan) (get-in plan [:request :programme/id]))
        stages (normalize-stages (:stages plan))]
    (when (nil? cid)
      (throw (ex-info "Programme requires :programme/id" {})))
    (when-not (or (keyword? cid) (string? cid) (symbol? cid) (integer? cid))
      (throw (ex-info "Programme id must be a canonical scalar" {:programme/id cid})))
    (when-not (or has-request (:allocation-request-root plan))
      (throw (ex-info "Programme requires an exact allocation :request (or a canonical plan)"
                      {})))
    {:schema-version plan-schema-version
     :programme/id cid
     :allocation-request-root (if has-request
                                (allocation-request-root (:request plan))
                                (:allocation-request-root plan))
     :semantic-ids (if has-request
                     (semantic-allocation-ids (:request plan))
                     (:semantic-ids plan))
     :stages stages
     :validation-profile-root (if has-request
                                (profile-root plan :validation-profile :validation
                                              "PROGRAMME_VALIDATION_PROFILE_V1")
                                (:validation-profile-root plan))
     :statement-profile-root (if has-request
                               (profile-root plan :statement-profile :statement
                                             "PROGRAMME_STATEMENT_PROFILE_V1")
                               (:statement-profile-root plan))
     :proof-profile-id (if has-request
                         (get-in plan [:proof-profile :id])
                         (:proof-profile-id plan))
     :admission-profile-id (if has-request
                             (get-in plan [:admission-profile :id])
                             (:admission-profile-id plan))}))

(defn programme-plan-root
  "Root identity of the canonical programme plan (the stable thing later stages
   refer to). Pure function of the frozen plan fields."
  [canonical-plan]
  (hc/domain-hash :programme-plan-v1 canonical-plan))

(defn verify-programme-plan
  "Verify a programme plan is canonical and canonicalization is idempotent.
   Returns {:status :passed|:invalid :programme-plan-root ...}. Never throws."
  [plan]
  (try
    (let [canonical (canonical-programme-plan plan)
          re-derived (canonical-programme-plan canonical)]
      (if (= canonical re-derived)
        {:status :passed
         :programme-plan-root (programme-plan-root canonical)
         :plan canonical}
        {:status :invalid
         :details {:reason :not-canonical :canonical canonical :re-derived re-derived}}))
    (catch Exception e
      {:status :invalid
       :details {:reason :invalid-plan :error (.getMessage e)}})))

;; ---------------------------------------------------------------------------
;; SP-C.2 — exact-set reconciliation (planned = executed = recorded)
;; ---------------------------------------------------------------------------

(defn- dupes
  [coll]
  (->> coll frequencies (keep (fn [[k n]] (when (> n 1) k))) vec))

(defn reconcile-programme-stages
  "Reconcile the sets of stage keys that were planned, executed, and recorded.
   Pass {:planned [...] :executed [...] :recorded [...]} (order-independent).
   Returns a passed/failed map listing missing (planned but not executed/
   recorded), unexpected (executed but not planned) and duplicate (executed
   twice) entries."
  [{:keys [planned executed recorded]}]
  (let [planned (vec (or planned []))
        executed (vec (or executed []))
        recorded (vec (or recorded []))
        pset (set planned)
        eset (set executed)
        rset (set recorded)
        missing-from-exec (vec (remove eset planned))
        missing-from-record (vec (remove rset planned))
        unexpected-exec (vec (remove pset executed))
        unexpected-record (vec (remove pset recorded))
        duplicates-exec (dupes executed)
        fail? (or (seq missing-from-exec) (seq missing-from-record)
                  (seq unexpected-exec) (seq unexpected-record)
                  (seq duplicates-exec))]
    {:status (if fail? :failed :passed)
     :planned planned
     :executed executed
     :recorded recorded
     :missing {:from-execution missing-from-exec :from-receipt missing-from-record}
     :unexpected {:in-execution unexpected-exec :in-receipt unexpected-record}
     :duplicates duplicates-exec}))

(defn reconcile-programme-ids
  "Exact-set reconciliation over per-stage artifact IDs:
   {:planned {stage id} :executed {stage id} :recorded {stage id}}.
   Rejects duplicate/missing/unexpected IDs and any stage whose executed or
   recorded id deviates from its planned id."
  [{:keys [planned executed recorded]}]
  (let [planned (or planned {})
        executed (or executed {})
        recorded (or recorded {})
        stage-reconcile (reconcile-programme-stages
                         {:planned (keys planned)
                          :executed (keys executed)
                          :recorded (keys recorded)})
        stage-mismatch (vec (keep (fn [k]
                                    (let [p (get planned k)
                                          x (get executed k)
                                          r (get recorded k)]
                                      (when (or (nil? x) (nil? r) (not= p x r))
                                        {:stage k :planned p :executed x :recorded r})))
                                  (keys planned)))
        fail? (or (= :failed (:status stage-reconcile))
                  (seq stage-mismatch))]
    {:status (if fail? :failed :passed)
     :stage-id-mismatches stage-mismatch
     :reconciliation stage-reconcile}))

;; ---------------------------------------------------------------------------
;; SP-C.3 — evidence + receipt verifier (built before the runner)
;; ---------------------------------------------------------------------------

(def ^:const evidence-schema-version "programme-mechanism-evidence.v1")

(defn programme-evidence-artifact
  "Hash-committed programme evidence envelope derived from an evaluation. The
   evidence root commits the request-root, result-root and the independent
   exact-verifier validation verdict."
  [{:keys [programme/id request evaluation validation-status validation-details]}]
  (let [result-root (get-in evaluation [:result :artifact/hash])
        base {:schema-version evidence-schema-version
              :programme/id id
              :request-root (allocation-request-root request)
              :result-root result-root
              :validation-status validation-status
              :validation-details validation-details
              :evidence/id [:programme-evidence id
                            (allocation-request-root request)
                            result-root]}]
    (assoc base :evidence/hash
           (hc/domain-hash :programme-evidence-v1 (dissoc base :evidence/hash)))))

(defn evidence-root
  "The evidence root (hash) of a programme evidence artifact."
  [artifact]
  (:evidence/hash artifact))

(def ^:const receipt-schema-version "programme-receipt.v1")

(defn- stage-success-status
  "The terminal status that counts as success for a required stage (per the
   stage vocabulary: allocation/evidence are :completed, validation is :passed)."
  [stage]
  (case stage :allocation :completed :validation :passed :evidence :completed))

(defn derive-programme-verdict
  "Derive the programme's AGGREGATE verdict from its leaves — per-stage statuses
   and exact-set reconciliation — NOT trusted from the receipt. Used identically
   by the runner (build) and the receipt verifier (verify), so an aggregate
   claim can never diverge from the per-stage facts.

   Leaves: :stage-statuses {stage -> status} and :reconciliation (from
   reconcile-programme-ids). Validation status is read from
   (:stage-statuses :validation), so an exact-verifier :unsupported stays an
   :unsupported programme semantic — never flattened to :failed or :pass.

   Returns {:semantic/status :programme/status :summary {...} :exact-set-complete?}."
  [{:keys [stage-statuses reconciliation]}]
  (let [required [:allocation :validation :evidence]
        status-of (fn [stage] (get stage-statuses stage))
        successes (filterv (fn [stage] (= (stage-success-status stage) (status-of stage)))
                           required)
        failed (filterv (fn [stage] (contains? #{:failed :error} (status-of stage))) required)
        cancelled (filterv (fn [stage] (= :cancelled (status-of stage))) required)
        validation-status (status-of :validation)
        exact-set-complete (= :passed (:status reconciliation))
        complete? (and (= (count required) (count successes)) exact-set-complete)
        semantic-status (cond
                          (= :unsupported validation-status) :unsupported
                          (= :error validation-status) :error
                          (seq failed) :fail
                          complete? :pass
                          :else :incomplete)
        programme-status (cond
                           (seq cancelled) :cancelled
                           (seq failed) :failed
                           (= :error validation-status) :error
                           (= :unsupported validation-status) :completed
                           complete? :completed
                           :else :executing)]
    {:semantic/status semantic-status
     :programme/status programme-status
     :summary {:required-total (count required)
               :required-completed (count successes)
               :failed (count failed)
               :cancelled (count cancelled)
               :unsupported (if (= :unsupported validation-status) 1 0)}
     :exact-set-complete exact-set-complete
     :validation/status validation-status}))

(defn- derived-exact-request
  "Rebuild the exact-verifier request from an evaluation's canonical request."
  [evaluation]
  (let [n (get-in evaluation [:result :artifact/value :canonical-request])
        policy (:policy n)]
    {:amount (:amount n)
     :items (:participants n)
     :rounding (:rounding policy)
     :cap-treatment (:cap-treatment policy)
     :ordering-policy (:tie-break policy)}))

(defn programme-validation-result
  "The programme validation result = the exact verifier verdict for the exact
   request/result pair of this evaluation. This is the single authoritative
   validation verdict, independent of execution settings."
  [evaluation]
  (exact-verifier/verify-weighted-proportionality
   (derived-exact-request evaluation)
   (:allocation evaluation)))

(defn build-programme-receipt
  "Construct the claim (receipt) that verify-programme-receipt must reconstruct
   from the execution artifacts. The aggregate verdict fields
   (:semantic/status :programme/status :summary :exact-set-complete) are DERIVED
   via derive-programme-verdict from the per-stage statuses and the exact-set
   reconciliation, never authored independently."
  [execution]
  (let [{:keys [programme/id request evaluation stages validation-status
                validation-details evidence-artifact stage-ids programme-plan-root
                reconciliation]}
        execution
        derived (derive-programme-verdict {:stage-statuses stages
                                           :reconciliation reconciliation})
        base (merge
              {:schema-version receipt-schema-version
               :programme/id id
               :programme-plan-root programme-plan-root
               :request-root (allocation-request-root request)
               :result-root (get-in evaluation [:result :artifact/hash])
               :stages stages
               :stage-ids stage-ids
               :validation-status validation-status
               :validation-details validation-details
               :evidence-root (evidence-root evidence-artifact)
               :evidence-id (:evidence/id evidence-artifact)}
              (select-keys derived [:semantic/status :programme/status :summary
                                    :exact-set-complete :validation/status]))]
    (assoc base :receipt-hash
           (hc/domain-hash :programme-receipt-v1 (dissoc base :receipt-hash)))))

(defn verify-programme-receipt
  "Independently derive the receipt's semantic fields AND its aggregate verdict
   from the execution artifacts, then compare both against the recorded claim.
   Built before the runner, so the runner must produce something this accepts.

   `artifacts` is {:request ... :evaluation ... :evidence-artifact ...}.
   Returns {:status :passed|:failed :mismatches [...]}."
  [artifacts receipt]
  (let [{:keys [request evaluation]} artifacts
        derived-request-root (allocation-request-root request)
        derived-result-root (hc/domain-hash :pro-rata-evaluation-v1
                                            (get-in evaluation [:result :artifact/value]))
        derived-verdict (programme-validation-result evaluation)
        derived-validation-status (:status derived-verdict)
        derived-validation-details (:details derived-verdict)
        expected-evidence (programme-evidence-artifact
                           {:programme/id (:programme/id receipt)
                            :request request
                            :evaluation evaluation
                            :validation-status derived-validation-status
                            :validation-details derived-validation-details})
        ;; reconstruct stage statuses strictly from what the artifacts prove
        reconstructed-stages (merge {:allocation :completed
                                     :validation derived-validation-status
                                     :evidence :completed}
                                    {:statement (or (:statement (:stages receipt)) :not-requested)
                                     :proof (or (:proof (:stages receipt)) :not-requested)
                                     :verification (or (:verification (:stages receipt)) :not-requested)
                                     :admission (or (:admission (:stages receipt)) :not-requested)})
        reconstructed-reconciliation (reconcile-programme-ids
                                      {:planned (:stage-ids receipt)
                                       :executed (:stage-ids receipt)
                                       :recorded (:stage-ids receipt)})
        derived-verdict-aggregate (derive-programme-verdict
                                   {:stage-statuses reconstructed-stages
                                    :reconciliation reconstructed-reconciliation})
        mismatch (fn [label expected actual]
                   (when-not (= expected actual)
                     {:field label :expected expected :actual actual}))
        mismatches (vec (remove nil?
                                [(mismatch :request-root derived-request-root (:request-root receipt))
                                 (mismatch :result-root derived-result-root (:result-root receipt))
                                 (mismatch :validation-status derived-validation-status (:validation-status receipt))
                                 (mismatch :validation-details derived-validation-details (:validation-details receipt))
                                 (mismatch :evidence-root (:evidence/hash expected-evidence) (:evidence-root receipt))
                                 (mismatch :evidence-id (:evidence/id expected-evidence) (:evidence-id receipt))
                                 (mismatch :stages reconstructed-stages (:stages receipt))
                                 (mismatch :semantic/status (:semantic/status derived-verdict-aggregate) (:semantic/status receipt))
                                 (mismatch :programme/status (:programme/status derived-verdict-aggregate) (:programme/status receipt))
                                 (mismatch :summary (:summary derived-verdict-aggregate) (:summary receipt))
                                 (mismatch :exact-set-complete (:exact-set-complete derived-verdict-aggregate) (:exact-set-complete receipt))]))]
    (if (seq mismatches)
      {:status :failed
       :mismatches mismatches}
      {:status :passed
       :programme-plan-root (:programme-plan-root receipt)
       :semantic/status (:semantic/status receipt)})))

;; ---------------------------------------------------------------------------
;; SP-C.4 — runner (allocate -> validate -> evidence -> complete)
;; ---------------------------------------------------------------------------

(defn- emit
  "SP-A typed progress dispatch: forward to an :on-progress callback and reduce
   typed events into a caller-owned :progress-atom. Observer failure never affects
   results."
  [opts event]
  (when-let [cb (:on-progress opts)]
    (try (cb event) (catch Exception _ nil)))
  (when-let [atom-progress (:progress-atom opts)]
    (try (swap! atom-progress progress/reducer event) (catch Exception _ nil)))
  nil)

(defn- execute-required-stages
  [plan canonical opts on-complete]
  (let [request (:request plan)]
    (emit opts {:event :phase-started :phase :allocating :total 3})
    (let [evaluation (payoffs/evaluate-pro-rata-allocation request)]
      (emit opts {:event :allocation-completed :phase :allocating})
      (emit opts {:event :phase-started :phase :validating})
      (let [verdict (programme-validation-result evaluation)
            validation-status (:status verdict)
            validation-details (:details verdict)]
        (emit opts {:event :phase-completed :phase :validating
                    :validation-status validation-status})
        (emit opts {:event :phase-started :phase :evidence})
        (let [evidence (programme-evidence-artifact
                        {:programme/id (:programme/id canonical)
                         :request request
                         :evaluation evaluation
                         :validation-status validation-status
                         :validation-details validation-details})]
          (emit opts {:event :phase-completed :phase :evidence})
          (emit opts {:event :phase-completed :phase :completed :status :completed})
          (let [stage-ids {:allocation (:allocation-request-root canonical)
                           :validation (:allocation-request-root canonical)
                           :evidence (:evidence/id evidence)}]
            (on-complete {:request request
                          :evaluation evaluation
                          :evidence-artifact evidence
                          :validation-status validation-status
                          :validation-details validation-details
                          :stage-ids stage-ids})))))))

(defn run-programme
  "Execute a programme: allocate -> validate -> evidence -> complete, producing a
   receipt that verify-programme-receipt accepts. Proof/statement/verification/
   admission stages are :not-requested until SP-C.5.

   Operational options (excluded from identity): :on-progress (SP-A typed event
   callback), :progress-atom, :budget-permits (execution budget)."
  [plan & {:keys [on-progress progress-atom budget-permits]}]
  (let [opts {:on-progress on-progress :progress-atom progress-atom}
        canonical (canonical-programme-plan plan)
        stages (:stages canonical)
        run (fn []
              (let [execution (execute-required-stages plan canonical opts identity)
                    programme-plan-root (programme-plan-root canonical)
                    finished-stages (merge stages
                                           {:allocation :completed
                                            :validation (:validation-status execution)
                                            :evidence :completed})
                    stage-ids (:stage-ids execution)
                    reconciliation (reconcile-programme-ids
                                    {:planned stage-ids
                                     :executed stage-ids
                                     :recorded stage-ids})
                    receipt (build-programme-receipt
                             (assoc execution
                                    :programme/id (:programme/id canonical)
                                    :programme-plan-root programme-plan-root
                                    :stages finished-stages
                                    :reconciliation reconciliation))
                    verification (verify-programme-receipt
                                  {:request (:request execution)
                                   :evaluation (:evaluation execution)
                                   :evidence-artifact (:evidence-artifact execution)}
                                  receipt)]
                {:canonical-plan canonical
                 :stages finished-stages
                 :evaluation (:evaluation execution)
                 :evidence-artifact (:evidence-artifact execution)
                 :reconciliation reconciliation
                 :receipt receipt
                 :verification verification}))]
    (if (and budget-permits (pos? budget-permits))
      (budget/with-execution-budget budget-permits (run))
      (run))))