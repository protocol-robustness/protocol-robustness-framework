(ns resolver-sim.contract-model.replay.profile-adapter
  "Replay profile adapter dispatch — multimethod on [profile protocol-id].

   Separates protocol-ID routing from the public `simple-replay` entry point.
   Each protocol can register its own adapter method; the default implementation
   calls `replay-events`.

   === Adapter contract ===
   Every adapter must:
   1. Accept (protocol scenario replay-opts)
   2. Honour the common result contract (:replay-profile, :protocol-id, etc.)
   3. Explicitly reject unsupported options via structured ex-info
   4. Perform no persistent evidence or diagnostic I/O"
  (:require [clojure.string :as str]
            [resolver-sim.contract-model.replay.flags :as replay-flags]
            [resolver-sim.protocols.protocol :as proto]))

;; ---------------------------------------------------------------------------
;; Unsupported/prohibited option validation
;; ---------------------------------------------------------------------------

(def prohibited-simple-options
  "Options that the simple replay profile must reject."
  #{:evidence-mode :signing-key :signing-password :tsa-url
    :skip-finalize :allow-dirty?})

(def option-type
  "Classify a replay option for error reporting."
  {:evidence-mode    :profile-flag
   :signing-key      :persistence
   :signing-password :persistence
   :tsa-url          :timestamping
   :skip-finalize    :evidence-chain
   :allow-dirty?     :evidence-chain})

(defn collect-unsupported-options
  "Return a sequence of unsupported option keys found in `replay-opts`.
   Each entry is {:option <k> :type <option-type> :rejected-by <adapter-origin>}."
  [replay-opts adapter-origin]
  (keep (fn [k]
          (when (contains? replay-opts k)
            {:option k
             :type (get option-type k :unknown)
             :rejected-by adapter-origin}))
        prohibited-simple-options))

(defn reject-unsupported!
  "Throw ex-info if `replay-opts` contains any prohibited options.
   Reports all violations together so callers get a complete picture."
  [replay-opts adapter-origin]
  (when (map? replay-opts)
    (let [violations (collect-unsupported-options replay-opts adapter-origin)]
      (when (seq violations)
        (throw (ex-info "Simple replay does not support evidence-chain, persistence or timestamping options"
                        {:violations violations
                         :replay-profile :simple
                         :adapter adapter-origin
                         :n-unsupported (count violations)}))))))

(def prohibited-simple-flags
  "Flag keys that the simple replay profile must not allow callers to override.
   These would re-enable evidence, persistence, telemetry, or checkpoint I/O."
  #{:evidence-mode :include-telemetry-evidence? :world-checkpoint-policy
    :projection-mode})

(def allowed-simple-option-keys
  "Top-level options accepted by simple replay. All replay behavior options
   must be nested under :flags so the profile boundary is explicit."
  #{:run-id :flags})

;; ---------------------------------------------------------------------------
;; Simple replay options that are allowed
;; ---------------------------------------------------------------------------

(defn extract-simple-opts
  "Validate and extract simple replay options.
   Returns {:run-id <nonblank string or nil> :flags <map or nil>}. Unknown
   top-level options, malformed values, and profile-escaping nested flags are
   rejected at the public profile boundary rather than ignored downstream."
  [replay-opts adapter-origin]
  (when (and (some? replay-opts) (not (map? replay-opts)))
    (throw (ex-info "Simple replay options must be a map"
                    {:type :invalid-simple-replay-options
                     :replay-profile :simple
                     :adapter adapter-origin
                     :expected :map
                     :actual-type (str (class replay-opts))})))
  (reject-unsupported! replay-opts adapter-origin)
  (let [opts (or replay-opts {})
        raw-flags (:flags opts)
        run-id (:run-id opts)
        unknown-options (seq (remove allowed-simple-option-keys (keys opts)))
        prohibited-flags (when (map? raw-flags)
                           (seq (select-keys raw-flags prohibited-simple-flags)))]
    (when (and (some? raw-flags) (not (map? raw-flags)))
      (throw (ex-info "Simple replay :flags must be a map"
                      {:type :invalid-simple-replay-options
                       :replay-profile :simple
                       :adapter adapter-origin
                       :field :flags
                       :expected :map
                       :actual-type (str (class raw-flags))})))
    (when (and (some? run-id)
               (or (not (string? run-id)) (str/blank? run-id)))
      (throw (ex-info "Simple replay :run-id must be a nonblank string"
                      {:type :invalid-simple-replay-options
                       :replay-profile :simple
                       :adapter adapter-origin
                       :field :run-id
                       :expected :nonblank-string
                       :actual run-id})))
    (when unknown-options
      (throw (ex-info "Simple replay received unsupported options"
                      {:replay-profile :simple
                       :adapter adapter-origin
                       :unknown-options (vec (sort unknown-options))
                       :allowed-options (vec (sort allowed-simple-option-keys))})))
    (when prohibited-flags
      (throw (ex-info "Simple replay cannot override enforced profile flags"
                      {:replay-profile :simple
                       :adapter adapter-origin
                       :prohibited-flags (vec (sort (keys prohibited-flags)))})))
    {:run-id run-id :flags raw-flags}))

;; ---------------------------------------------------------------------------
;; Execution plan dispatch — one source of truth for execution and provenance.
;; ---------------------------------------------------------------------------

(defn- canonical-simple-run
  [protocol scenario replay-opts forced-flags]
  (let [replay-events (requiring-resolve 'resolver-sim.contract-model.replay/replay-events)
        opts (-> (or replay-opts {})
                 (update :flags #(merge (or % {}) forced-flags))
                 (merge {:profile :replay/simple :minimal true}))]
    (replay-events protocol scenario opts)))

(defmulti simple-execution-plan
  "Return the complete simple-profile execution plan for a protocol.

   A plan contains:
   - :execution — reviewer-facing provenance descriptor
   - :run       — (fn [protocol scenario replay-opts] result)

   Keeping these together prevents an adapter implementation from drifting from
   the execution descriptor published by `simple-replay`."
  (fn [profile protocol] [profile (proto/protocol-id protocol)]))

(defmethod simple-execution-plan :default
  [profile protocol]
  {:execution {:profile :simple :engine :canonical-loop}
   :run (fn [protocol scenario replay-opts]
          (canonical-simple-run protocol scenario replay-opts {}))})

(def ^:private required-simple-result-keys
  #{:outcome :trace :metrics :events-processed})

(def ^:private simple-outcomes #{:pass :fail :invalid})

(defn validate-simple-adapter-result!
  "Validate the stable result contract required from a simple execution plan.
   Throws structured ex-info rather than allowing malformed adapter output to be
   partially normalized into a misleading public replay result."
  [result adapter-id]
  (let [violations
        (if-not (map? result)
          [{:field :result :expected :map :actual-type (str (class result))}]
          (let [missing (vec (sort (remove #(contains? result %)
                                           required-simple-result-keys)))]
            (vec
             (concat
              (when (seq missing)
                [{:field :required-keys
                  :expected required-simple-result-keys
                  :missing missing}])
              (when (and (contains? result :outcome)
                         (not (contains? simple-outcomes (:outcome result))))
                [{:field :outcome :expected simple-outcomes :actual (:outcome result)}])
              (when (and (contains? result :trace) (not (sequential? (:trace result))))
                [{:field :trace :expected :sequential :actual-type (str (class (:trace result)))}])
              (when (and (contains? result :metrics) (not (map? (:metrics result))))
                [{:field :metrics :expected :map :actual-type (str (class (:metrics result)))}])
              (when (and (contains? result :events-processed)
                         (or (not (integer? (:events-processed result)))
                             (neg? (:events-processed result))))
                [{:field :events-processed
                  :expected :nonnegative-integer
                  :actual (:events-processed result)}])))))]
    (when (seq violations)
      (throw (ex-info "Simple replay adapter returned an invalid result"
                      {:type :simple-replay-invalid-adapter-result
                       :replay-profile :simple
                       :adapter adapter-id
                       :violations violations})))
    result))

(defn run-simple-profile
  "Compatibility wrapper that executes the selected simple-profile plan."
  [profile protocol scenario replay-opts]
  (let [{:keys [run]} (simple-execution-plan profile protocol)]
    (run protocol scenario replay-opts)))

(defn simple-execution-descriptor
  "Compatibility wrapper returning the descriptor from the selected plan."
  [profile protocol]
  (:execution (simple-execution-plan profile protocol)))

;; ---------------------------------------------------------------------------
;; Yield adapter — canonical-loop path (replay-events + yield-dt-validation flag)
;;
;; Yield-v1 now routes through replay-events (the canonical loop) with the
;; :yield-dt-validation? flag enabled. The temporary thin-runner path has been
;; replaced.
;;
;; REMOVAL CONDITION (for this method definition): when yield achieves full
;; EconomicModel/AnalysisModule parity and no longer needs the
;; :yield-dt-validation? flag or :yield-provider metrics-profile,
;; this method can be removed and the default will handle yield-v1.
;; ---------------------------------------------------------------------------

(defmethod simple-execution-plan [:simple "yield-v1"]
  [profile protocol]
  {:execution {:profile :simple
               :engine :canonical-loop
               :adapter/id :yield-v1-canonical
               :required-flags {:yield-dt-validation? true
                                :metrics-profile :yield-provider}}
   :run (fn [protocol scenario replay-opts]
          ;; Required yield flags merge after caller flags and therefore cannot
          ;; be removed by an otherwise safe simple-profile override.
          (canonical-simple-run protocol scenario replay-opts
                                {:yield-dt-validation? true
                                 :metrics-profile :yield-provider}))})


