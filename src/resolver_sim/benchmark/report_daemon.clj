(ns resolver-sim.benchmark.report-daemon
  "Polling report daemon for integer-domain researcher interactions.

   BOUNDARY
   A long-running process that consumes submitted researcher interactions,
   enforces the integer-domain boundary, verifies the researcher-run report
   chain, and emits a rooted public-result-admission.  It follows the
   receipt-daemon lifecycle (bounded pull polling, fair cursor, sanitized
   summaries) and makes logical idempotency explicit: interaction-id ↦
   admission-root, so a retry can never produce a second logical admission.

   INTEGER-DOMAIN ENFORCEMENT
   The daemon validates the CANONICAL SUBMITTED representation and
   independently reconstructs the expected semantic result.  A fractional
   input is rejected — never transformed through a helper and then accepted.
   Caller-provided result roots are evidence to compare against, never
   authoritative inputs: every semantic/report commitment is recomputed.

   PARTICIPATION
   Anonymous-vs-named is NOT the same dimension as authenticated-vs-
   unauthenticated.  The daemon validates the interaction's declared
   participation against the signed report and commits the derived projection
   in the rooted admission, so presentation can never be mistaken for identity
   assurance.  :identity-assurance :authenticated is refused in V1 (no
   researcher↔key binding verifier exists).

   TRANSPORT BOUNDARY
   The queue is a store protocol, deliberately transport-independent.  A
   future UDS/push adapter can enqueue the same canonical interaction objects
   without changing daemon semantics."
  (:require [resolver-sim.benchmark.public-result-admission :as pra]
            [resolver-sim.benchmark.public-results-epoch :as pra-epoch]
            [resolver-sim.benchmark.research-definition :as research]
            [resolver-sim.benchmark.verified-researcher-run :as verified]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.pro-rata.allocation :as allocation]))

(def interaction-payload-domain "report-daemon.interaction-payload.v1")
(def processing-domain "report-daemon.processing.v1")
(def research-margins-domain "report-daemon.research-margins.v1")

(declare integer-domain-verify)

;; ─────────────────────────────────────────────────────────────────────────────
;; Interaction store (transport-independent queue boundary)
;; ─────────────────────────────────────────────────────────────────────────────

(defprotocol ReportInteractionStore
  (pending-interactions* [store])
  (resolve-processing* [store interaction-id])
  (record-processing!* [store interaction-id processing])
  (head-chain* [store])
  (admissions* [store])
  (enqueue!* [store interaction]))

(deftype InMemoryReportInteractionStore [state-atom]
  ReportInteractionStore
  (pending-interactions* [_]
    (let [{:keys [interactions processing]} @state-atom]
      (->> interactions
           (remove (fn [[id _]] (contains? processing id)))
           (sort-by key)
           (mapv val))))
  (resolve-processing* [_ interaction-id]
    (get-in @state-atom [:processing interaction-id]))
  (record-processing!* [_ interaction-id processing]
    (loop []
      (let [current @state-atom
            existing (get-in current [:processing interaction-id])
            payload-root (:processing/payload-root processing)]
        (cond
          (and existing (not= (:processing/payload-root existing) payload-root))
          {:status :payload-conflict :existing existing}

          :else
          (let [admission-root (:processing/admission-root processing)
                next (assoc-in current [:processing interaction-id] processing)
                next (if admission-root
                       (let [chain (:head current)
                             present? (some #(= admission-root
                                                (:head/admission-root %))
                                            chain)
                             chain (if present?
                                     chain
                                     (conj chain
                                           (pra-epoch/successor-head
                                            (peek chain) admission-root)))]
                         (-> next
                             (assoc :head chain)
                             (assoc-in [:admissions admission-root]
                                       (:processing/admission-input processing))))
                       next)]
            (if (compare-and-set! state-atom current next)
              {:status :recorded}
              (recur)))))))
  (head-chain* [_] (:head @state-atom))
  (admissions* [_] (:admissions @state-atom))
  (enqueue!* [_ interaction]
    (swap! state-atom update :interactions
           assoc (:interaction/id interaction) interaction)
    interaction))

(defn new-report-interaction-store
  "Create an in-memory report-interaction store (genesis head, empty queue)."
  []
  (InMemoryReportInteractionStore.
   (atom {:interactions {}
          :processing {}
          :admissions {}
          :head [(pra-epoch/genesis-head)]})))

(defn enqueue!
  "Transport boundary: submit one interaction for processing.  Requires a
   stable :interaction/id; a duplicate id replaces the queued interaction
   (idempotency/conflict is resolved at processing time)."
  [store interaction]
  (when-not (and (string? (:interaction/id interaction))
                 (seq (:interaction/id interaction)))
    (throw (ex-info "interaction requires a stable string :interaction/id"
                    {:interaction/id (:interaction/id interaction)})))
  (enqueue!* store interaction))

;; ─────────────────────────────────────────────────────────────────────────────
;; Canonical interaction envelope (P0: the consumed thing has stable identity)
;; ─────────────────────────────────────────────────────────────────────────────

(defn interaction-content-root
  "Recompute the canonical payload root over the interaction content, projected
   through the canonical structure view so even a non-canonical submitted
   representation (e.g. a fractional double) has a stable, deterministic
   identity.  The projection TAGS float/ratio values — it never coerces them to
   integers — so the integer-domain check over the raw representation still
   rejects them.  The caller-declared :interaction/payload-root is evidence to
   compare against, never an authoritative input."
  [content]
  (hash-ref/sha256-ref
   (hc/domain-hash interaction-payload-domain
                   (:structure
                    (hc/project-world-to-structure-view
                     content interaction-payload-domain)))))

(defn- positive-long [label value]
  (when-not (and (integer? value) (pos? value) (<= value Long/MAX_VALUE))
    (throw (IllegalArgumentException. (str label " must be a positive long"))))
  (long value))

(defn build-envelope
  "Rebuild the canonical interaction envelope from a submitted interaction.
   Returns {:ok? true :envelope {...}} or {:ok? false :reason ...}."
  [interaction]
  (let [id (:interaction/id interaction)
        kind (:interaction/kind interaction)]
    (cond
      (not (and (string? id) (seq id)))
      {:ok? false :reason :missing-interaction-id}

      (not (contains? (methods integer-domain-verify) kind))
      {:ok? false :reason :unsupported-interaction-kind}

      (not (map? (:interaction/content interaction)))
      {:ok? false :reason :missing-interaction-content}

      :else
      (let [root (interaction-content-root (:interaction/content interaction))
            declared (:interaction/payload-root interaction)]
        (cond
          (not (hash-ref/valid-sha256-ref? (or declared "")))
          {:ok? false :reason :malformed-payload-root}

          (not= root declared)
          {:ok? false :reason :payload-root-mismatch}

          :else
          {:ok? true
           :envelope {:interaction/id id
                      :interaction/kind kind
                      :interaction/payload-root root
                      :interaction/participation
                      (:interaction/participation interaction)}})))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Integer-domain verification (validate submitted, reconstruct expected)
;; ─────────────────────────────────────────────────────────────────────────────

(defmulti integer-domain-verify
  "Verify the integer domain of an interaction's payload.  Dispatches on
   :interaction/kind.  Returns {:valid? true :semantic-result <sha256-root>}
   or {:valid? false :reason kw :detail map}.  Fractional inputs are rejected,
   never coerced."
  (fn [kind _] kind))

(defn- fractional-rows
  "Rows whose listed fields carry a non-integer value (rejection detail)."
  [rows fields]
  (vec (for [row rows
             k fields
             :let [v (get row k)]
             :when (some? v)
             :when (not (integer? v))]
         {:row/id (:row/id row) :key k :observed v :type-of (type v)})))

(defn- pro-rata-fractional-violations
  "Enumerate every integer field in the canonical submitted allocation that
   carries a fractional value.  The submitted representation is validated
   as-is; nothing is transformed."
  [request result]
  (vec (concat
        (when-let [av (:available request)]
          (when-not (integer? av)
            [{:key :available :observed av :type-of (type av)}]))
        (fractional-rows (:rows request) [:requested :weight :cap])
        (fractional-rows (:rows result)
                         [:requested :weight :effective-cap :allocated :unmet])
        (when-let [av (:available result)]
          (when-not (integer? av)
            [{:key :available :observed av :type-of (type av)}]))
        (when-let [at (:allocated-total result)]
          (when-not (integer? at)
            [{:key :allocated-total :observed at :type-of (type at)}]))
        (when-let [ur (:unallocated-residual result)]
          (when-not (integer? ur)
            [{:key :unallocated-residual :observed ur :type-of (type ur)}])))))

(defmethod integer-domain-verify :pro-rata/allocation
  [_ content]
  (let [request (:allocation/request content)
        result (:allocation/result content)]
    (cond
      (not (map? request)) {:valid? false :reason :pro-rata/missing-request}
      (not (map? result)) {:valid? false :reason :pro-rata/missing-result}
      :else
      (let [violations (pro-rata-fractional-violations request result)]
        (if (seq violations)
          {:valid? false :reason :pro-rata/fractional-field
           :detail {:violations violations}}
          (try
            (let [reconstructed (allocation/allocate request)
                  declared-hash (:allocation/hash result)]
              (cond
                (not= (:allocation/hash reconstructed) declared-hash)
                {:valid? false :reason :pro-rata/reconstruction-mismatch
                 :detail {:declared-hash declared-hash
                          :reconstructed-hash (:allocation/hash reconstructed)}}

                (not (allocation/allocation-hash-valid? result))
                {:valid? false :reason :pro-rata/result-hash-invalid}

                :else
                {:valid? true :semantic-result (:allocation/hash reconstructed)}))
            (catch clojure.lang.ExceptionInfo e
              {:valid? false :reason :pro-rata/reconstruction-failed
               :detail {:cause (:reason (ex-data e))}})))))))

(defn- research-observation-violations
  "Enumerate missing and fractional observations in the canonical submitted
   representation.  The frozen definition pins the required case × measure
   cell set; nothing is filled in or coerced."
  [frozen observations]
  (let [definition (:research-definition frozen)
        measures (:research/measures definition)]
    (vec (concat
          (for [case-entry (:research/cases definition)
                measure measures
                :let [v (get-in observations
                                [(:case/id case-entry) (:measure/id measure)])]
                :when (nil? v)]
            {:type :missing-observation
             :case/id (:case/id case-entry)
             :measure/id (:measure/id measure)})
          (for [[case-id measure-values] observations
                [measure-id v] measure-values
                :when (not (integer? v))]
            {:type :fractional-observation
             :case/id case-id :measure/id measure-id
             :observed v :type-of (type v)})))))

(defn- reconstruct-margins
  "Independently recompute every requirement margin from the frozen definition
   and the submitted integer observations."
  [frozen observations]
  (let [definition (:research-definition frozen)
        measures (:research/measures definition)]
    (into {}
          (for [case-entry (:research/cases definition)
                measure measures
                :let [case-id (:case/id case-entry)
                      measure-id (:measure/id measure)
                      observed (get-in observations [case-id measure-id])]]
            [[case-id measure-id]
             (:requirement/margin
              (research/evaluate-requirement measure observed))]))))

(defmethod integer-domain-verify :research/observation
  [_ content]
  (let [frozen (:definition content)
        observations (:observations content)
        submitted-margins (:requirement-margins content)]
    (cond
      (not (map? frozen)) {:valid? false :reason :research/missing-definition}
      (not (map? observations)) {:valid? false :reason :research/missing-observations}
      (not (map? submitted-margins))
      {:valid? false :reason :research/missing-margins}
      :else
      (let [violations (research-observation-violations frozen observations)]
        (if (seq violations)
          {:valid? false :reason :research/invalid-observation
           :detail {:violations violations}}
          (try
            (let [margins (reconstruct-margins frozen observations)
                  mismatches (into {}
                                   (for [[cell margin] margins
                                         :when (not= margin
                                                     (get submitted-margins cell))]
                                     [cell {:declared (get submitted-margins cell)
                                            :reconstructed margin}]))]
              (if (seq mismatches)
                {:valid? false :reason :research/margin-mismatch
                 :detail {:mismatches mismatches}}
                {:valid? true
                 :semantic-result
                 (hash-ref/sha256-ref
                  (hc/domain-hash research-margins-domain margins))}))
            (catch clojure.lang.ExceptionInfo e
              {:valid? false :reason :research/reconstruction-failed
               :detail {:cause (:reason (ex-data e))}})))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Participation (presentation is never identity assurance)
;; ─────────────────────────────────────────────────────────────────────────────

(defn- participation-rejection
  "Validate the interaction's declared participation against the signed report.
   Returns nil when consistent, or {:reason kw :detail map} for a rejection.
   An :authenticated claim fails closed even before comparison — there is no
   verified researcher↔key binding in V1."
  [{:keys [interaction/participation]} report]
  (let [derived (pra/participation-from-report report)]
    (cond
      (= :authenticated (:identity-assurance participation))
      {:reason :identity-assurance-escalation
       :detail {:declared participation}}

      (not (pra/valid-participation? participation))
      {:reason :participation-invalid
       :detail {:declared participation}}

      (not= participation derived)
      {:reason :participation-mismatch
       :detail {:declared participation :derived derived}}

      :else nil)))

;; ─────────────────────────────────────────────────────────────────────────────
;; One deterministic processing pass
;; ─────────────────────────────────────────────────────────────────────────────

(defn process-interaction
  "One deterministic processing pass over a validated interaction.  Pure: no
   store side effects.  Returns an admitted or rejected processing result map."
  [{:keys [interaction/kind interaction/content] :as interaction}]
  (let [semantic (integer-domain-verify kind content)]
    (if-not (:valid? semantic)
      {:status :rejected :stage :integer-domain
       :reason (:reason semantic) :detail (:detail semantic)}
      (let [p-rejection (participation-rejection interaction (:report content))]
        (if p-rejection
          {:status :rejected :stage :participation
           :reason (:reason p-rejection) :detail (:detail p-rejection)}
          (let [verification
                (try
                  (verified/verify {:report (:report content)
                                    :manifest (:manifest content)
                                    :public-key (:public-key content)})
                  (catch clojure.lang.ExceptionInfo e
                    {:reject (:reason (ex-data e))
                     :detail (ex-data e)})
                  (catch Exception e
                    {:reject :verification-error
                     :detail {:message (.getMessage e)}}))]
            (if (:reject verification)
              {:status :rejected :stage :researcher-run
               :reason (:reject verification)
               :detail (:detail verification)}
              (let [admission (pra/build (:report content)
                                         (:manifest content)
                                         (:public-key content))]
                {:status :admitted
                 :semantic-result (:semantic-result semantic)
                 :verification-result
                 (select-keys verification
                              [:report-root :manifest-root :outcome-root
                               :verifying-key])
                 :admission admission
                 :admission-input {:report (:report content)
                                   :manifest (:manifest content)
                                   :public-key (:public-key content)}}))))))))

(defn processing-root
  "One interaction has one deterministic processing result:
   H(interaction-id, interaction-payload-root, semantic-result,
     verification-result, admission-root-or-rejection)."
  [{:keys [interaction/id interaction/payload-root]}
   {:keys [status semantic-result verification-result admission reason stage]}]
  (let [body {:interaction/id id
              :interaction/payload-root payload-root
              :processing/status status
              :processing/semantic-result semantic-result
              :processing/verification-result verification-result
              :processing/admission-root (get-in admission [:admission/root])
              :processing/rejection
              (when-not (= :admitted status)
                {:stage stage :reason reason})}]
    (hash-ref/sha256-ref (hc/domain-hash processing-domain body))))

(defn build-processing-record
  "Assemble the rooted processing record for one interaction/result pair."
  [envelope result]
  (let [admission (:admission result)]
    {:processing/interaction-id (:interaction/id envelope)
     :processing/payload-root (:interaction/payload-root envelope)
     :processing/status (:status result)
     :processing/stage (:stage result)
     :processing/reason (:reason result)
     :processing/semantic-result (:semantic-result result)
     :processing/verification-result (:verification-result result)
     :processing/admission-root (get-in admission [:admission/root])
     :processing/admission admission
     :processing/admission-input (:admission-input result)
     :processing/root (processing-root envelope result)}))

(defn process-one!
  "Process one submitted interaction deterministically and record the result
   (admission or rooted rejection).

   Logical idempotency is explicit (crash/retry safe):
     * same interaction-id + same payload-root → the recorded result
       (:idempotent);
     * same interaction-id + different payload-root → fails closed
       (:payload-conflict), never a second logical admission.

   Returns {:status :recorded|:idempotent|:payload-conflict|:malformed ...}."
  [store interaction]
  (let [{:keys [ok? reason envelope]} (build-envelope interaction)]
    (if-not ok?
      {:status :malformed :reason reason}
      (let [existing (resolve-processing* store (:interaction/id envelope))]
        (cond
          (and existing
               (not= (:processing/payload-root existing)
                     (:interaction/payload-root envelope)))
          {:status :payload-conflict
           :interaction/id (:interaction/id envelope)
           :expected (:processing/payload-root existing)
           :observed (:interaction/payload-root envelope)}

          existing
          {:status :idempotent
           :interaction/id (:interaction/id envelope)
           :processing existing}

          :else
          (let [result (process-interaction interaction)
                record (build-processing-record envelope result)
                outcome (record-processing!* store (:interaction/id envelope)
                                             record)]
            (if (= :payload-conflict (:status outcome))
              {:status :payload-conflict
               :interaction/id (:interaction/id envelope)}
              {:status (:status result) :processing record})))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Epoch collector (admission-root based, deduplicated)
;; ─────────────────────────────────────────────────────────────────────────────

(defn current-epoch
  "Build the cumulative public-results-epoch over the daemon's admission head.
   Membership is admission-root based and re-enumerated from the head chain;
   duplicates are rejected by the epoch constructor."
  [store]
  (let [chain (head-chain* store)
        n (dec (count chain))]
    (pra-epoch/build-epoch {:head/root (:head/root (peek chain))
                            :through-sequence n}
                           chain
                           (admissions* store))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Polling lifecycle (bounded work, fair cursor, sanitized summaries)
;; ─────────────────────────────────────────────────────────────────────────────

(defn- outcome [status]
  (case status
    :admitted [:admitted :success]
    :idempotent [:idempotent :success]
    :rejected [:rejected :semantic]
    :payload-conflict [:payload-conflict :semantic]
    :malformed [:malformed :semantic]
    [:unknown :semantic]))

(defn- scan!
  [store batch-size after-id stopped]
  (let [summary (atom {:attempted 0 :counts {} :outcomes []
                       :next-after-id after-id})]
    (try
      (let [entries (pending-interactions* store)
            id-of #(get % :interaction/id)
            [before after] (if after-id
                             (split-with #(not (pos? (compare (id-of %) after-id)))
                                         entries)
                             [[] entries])]
        (doseq [entry (take batch-size (concat after before))
                :while (not @stopped)]
          (let [id (id-of entry)
                [status classification]
                (try
                  (let [result (process-one! store entry)]
                    (outcome (:status result)))
                  (catch InterruptedException _
                    (reset! stopped true)
                    [:interrupted :shutdown])
                  (catch Exception _
                    [:exception :retryable]))]
            (swap! summary
                   (fn [m]
                     (-> m
                         (update :attempted inc)
                         (assoc :next-after-id id)
                         (update-in [:counts classification] (fnil inc 0))
                         (update :outcomes conj
                                 {:status status :classification classification})))))))
      (catch InterruptedException _
        (reset! stopped true)
        (swap! summary assoc :scan-error :interrupted))
      (catch Exception _
        (swap! summary assoc :scan-error :retryable)))
    @summary))

(defn run-once!
  "Attempt at most :batch-size (default 64) pending interactions, sequentially.
   Pass the returned :next-after-id as :after-id on the next call for fairness.
   Outcomes contain only fixed diagnostic keywords, never report data.
   Discovery reads the store's full pending snapshot; only processing is
   bounded."
  ([store] (run-once! store {}))
  ([store {:keys [batch-size after-id] :or {batch-size 64}}]
   (scan! store (positive-long "batch-size" batch-size) after-id (atom false))))

(defn start!
  "Start an immediate scan followed by fixed-delay polling.
   Options: :batch-size 64, :poll-interval-ms 1000, :stop-timeout-ms 1000.
   Returns an explicit handle with :summary (latest sanitized scan atom).
   A handle owns one sequential thread and a fair cursor; no global state."
  ([store] (start! store {}))
  ([store {:keys [batch-size poll-interval-ms stop-timeout-ms]
           :or {batch-size 64 poll-interval-ms 1000 stop-timeout-ms 1000}}]
   (let [batch-size (positive-long "batch-size" batch-size)
         interval (positive-long "poll-interval-ms" poll-interval-ms)
         timeout (positive-long "stop-timeout-ms" stop-timeout-ms)
         stopped (atom false)
         wake (Object.)
         summary (atom nil)
         thread (Thread.
                 ^Runnable
                 (fn []
                   (try
                     (loop [cursor nil]
                       (when-not @stopped
                         (let [result (scan! store batch-size cursor stopped)]
                           (reset! summary result)
                           (locking wake
                             (when-not @stopped (.wait wake interval)))
                           (recur (:next-after-id result)))))
                     (catch InterruptedException _ (reset! stopped true))))
                 "report-daemon")
         handle {:thread thread :stopped stopped :wake wake
                 :summary summary :stop-timeout-ms timeout}]
     (.setDaemon thread true)
     (.start thread)
     handle)))

(defn stop!
  "Request shutdown and wake polling, without interrupting in-flight processing.
   Wait at most the configured timeout (or explicit positive timeout-ms).
   Returns :stopped or :stopping; a timed-out signing call may still complete.
   Repeat safely to await completion.  Caller interruption is preserved."
  ([handle] (stop! handle (:stop-timeout-ms handle)))
  ([{:keys [thread stopped wake]} timeout-ms]
   (let [timeout (positive-long "timeout-ms" timeout-ms)]
     (reset! stopped true)
     (locking wake (.notifyAll ^Object wake))
     (when-not (identical? thread (Thread/currentThread))
       (try
         (.join ^Thread thread timeout)
         (catch InterruptedException _ (.interrupt (Thread/currentThread)))))
     (if (.isAlive ^Thread thread) :stopping :stopped))))