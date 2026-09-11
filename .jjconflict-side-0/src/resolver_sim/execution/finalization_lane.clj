(ns resolver-sim.execution.finalization-lane
  "Cardinality-one finalization lane — P0 invariant.

   Semantic serialization boundary: arbitrary bounded parallelism may exist
   upstream, but all semantic contribution is sealed before exactly one fenced
   publication attempt can acquire authority.

   State machine:

     OPEN ──── close-execution! ────→ EXECUTION_CLOSED
       │                                 │
       │ work / completion /             │ finalize-lane! CAS
       │ quiescence facts                │   finalizer nil → ticket
       │                                 ▼
       │                            FINALIZING
       │                           /         \\
       │                  committed        rejected/throws
       │                      │                │
       │                      ▼                ▼
       │                  FINALIZED        ABORTED

   Invariants:
     FG-1  Closure monotonicity: after execution-closed, no operation can
           add, remove, or modify a contributing semantic result.
     FG-2  Closure completeness: closure only when contributors=0 and
           required phases quiesced.
     FG-3  Finalizer cardinality: at most one EXECUTION_CLOSED → FINALIZING
           CAS succeeds per generation.
     FG-4  Ticket binding: ticket valid only for the exact generation + basis
           it was issued for.
     FG-5  Fenced publication: write succeeds only if observed parent equals
           expected parent at authoritative mutation boundary.
     FG-6  No publication before commitment validation: write only for a valid
           finalization basis whose chain reaches the closed result set.
     FG-7  Publication singularity: one generation → at most one authoritative
           write and one observation emission.
     FG-8  Parallelism independence: execution parallelism cannot change
           canonical closed result, reconciliation, reduction, authorization,
           candidate, or finalization identities."
  (:import [java.util.concurrent.atomic AtomicInteger]))

(def lane-states
  "Valid lifecycle states for a finalization lane."
  #{:open :execution-closed :finalizing :finalized :aborted})

(def abort-reasons
  "Terminal abort reasons."
  #{:stale-parent :invalid-finalization-basis :write-rejected :write-threw
    :invariant-violation})

(def ^:private global-generation-counter
  "Global monotonic counter for lane generations. Each open call produces a
   unique generation, ensuring tickets are bound to a specific lane (FG-4)."
  (AtomicInteger. 0))

(defn open
  "Open a finalization lane for a given expected parent/head root.

   `opts` is a map with optional keys:
     :expected-parent-root  — the parent/head the finalizer must observe.
                              When nil, parent-fence check is skipped (FG-5).
     :requires-quiescence?  — when true (default), close-execution! requires
                              all executor-backed phases to be quiesced."
  ([] (open nil))
  ([opts]
   (let [generation (.incrementAndGet global-generation-counter)]
     (atom {:lifecycle :open
            :lane/generation generation
            :lane/expected-parent-root (:expected-parent-root opts)
            :lane/requires-quiescence? (if (contains? opts :requires-quiescence?)
                                         (:requires-quiescence? opts)
                                         true)
            :lane/contributing-open 0
            :lane/executor-backed-phases 0
            :lane/quiesced-phases 0
            :lane/finalizer nil
            :lane/result nil
            :lane/abort-reason nil}))))

(defn state
  "Current lifecycle state of the lane."
  [lane]
  (:lifecycle @lane))

(defn generation
  "Current lane generation (monotonically increasing integer)."
  [lane]
  (:lane/generation @lane))

(defn begin-contributor!
  "Register a new unit of contributing work. Returns true on success.

   FG-1: Rejected after execution closure — no new contributing work can be
   created, claimed, retried, accepted, or resurrected once the lane is closed.

   Throws if the lane is aborted or already finalized."
  [lane]
  (loop []
    (let [s @lane]
      (case (:lifecycle s)
        :open
        (let [updated (update s :lane/contributing-open inc)]
          (if (compare-and-set! lane s updated)
            true
            (recur)))
        :execution-closed
        (throw (ex-info "Cannot register contributor after execution closure (FG-1)"
                        {:lifecycle :execution-closed
                         :contributing-open (:lane/contributing-open s)}))
        (throw (ex-info "Cannot register contributor in terminal state"
                        {:lifecycle (:lifecycle s)}))))))

(defn end-contributor!
  "Complete a unit of contributing work. Returns true on success.

   Silently returns nil if the lane is in terminal state (ABORTED/FINALIZED)
   to allow in-flight contributors to drain cleanly without side effects."
  [lane]
  (loop []
    (let [s @lane]
      (case (:lifecycle s)
        (:open :execution-closed :finalizing)
        (let [updated (update s :lane/contributing-open #(max 0 (dec %)))]
          (if (compare-and-set! lane s updated)
            true
            (recur)))
        nil))))

(defn record-executor-dispatch!
  "Record that a new executor-backed phase has been dispatched. Returns true.

   FG-1: Rejected after execution closure — no executor-backed phase can be
   introduced once the lane is closed."
  [lane]
  (loop []
    (let [s @lane]
      (case (:lifecycle s)
        :open
        (let [updated (update s :lane/executor-backed-phases inc)]
          (if (compare-and-set! lane s updated)
            true
            (recur)))
        :execution-closed
        (throw (ex-info "Cannot dispatch executor after execution closure (FG-1)"
                        {:lifecycle :execution-closed}))
        (throw (ex-info "Cannot dispatch executor in terminal state"
                        {:lifecycle (:lifecycle s)}))))))

(defn record-quiesced!
  "Record that an executor-backed phase has successfully quiesced. Returns true.
   Must pair 1:1 with record-executor-dispatch! calls."
  [lane]
  (loop []
    (let [s @lane]
      (case (:lifecycle s)
        (:open :execution-closed)
        (let [updated (update s :lane/quiesced-phases inc)]
          (if (compare-and-set! lane s updated)
            true
            (recur)))
        (throw (ex-info "Cannot record quiescence in terminal state"
                        {:lifecycle (:lifecycle s)}))))))

(defn close-execution!
  "Atomically close execution: seal the semantic result set.

   FG-2: Succeeds IFF:
     contributing-open == 0
     AND (!requires-quiescence? OR executor-backed-phases == quiesced-phases)

   Does NOT issue a finalizer ticket. Closure and finalizer election are
   conceptually distinct invariants: closure proves immutability;
   EXECUTION_CLOSED → FINALIZING proves cardinality one.

   Returns {:closed? true} on success.
   Returns {:closed? false :reason ...} on precondition failure (safe to retry).
   Returns {:closed? false :reason :already-closed :lifecycle ...} when lane
   is already closed or terminal."
  [lane]
  (loop []
    (let [s @lane]
      (case (:lifecycle s)
        :open
        (let [issues (cond
                       (pos? (:lane/contributing-open s))
                       {:reason :outstanding-contributors
                        :contributing-open (:lane/contributing-open s)}
                       (and (:lane/requires-quiescence? s)
                            (not= (:lane/executor-backed-phases s)
                                  (:lane/quiesced-phases s)))
                       {:reason :quiescence-incomplete
                        :executor-backed (:lane/executor-backed-phases s)
                        :quiesced (:lane/quiesced-phases s)}
                       :else nil)]
          (if issues
            {:closed? false}
            (let [closed (assoc s :lifecycle :execution-closed)]
              (if (compare-and-set! lane s closed)
                {:closed? true}
                (recur)))))
        :execution-closed
        {:closed? false :reason :already-closed :lifecycle :execution-closed}
        {:closed? false :reason :already-closed :lifecycle (:lifecycle s)}))))

(defn- make-ticket
  "Build a finalizer ticket bound to the exact generation and basis."
  [lane-state]
  (let [id (java.util.UUID/randomUUID)]
    {:finalizer/id id
     :finalizer/lane-generation (:lane/generation lane-state)
     :finalizer/closed-root (:lane/closed-root lane-state)
     :finalizer/basis-root (:lane/basis-root lane-state)
     :finalizer/expected-parent (:lane/expected-parent-root lane-state)}))

(defn- finalize-preconditions
  "Check preconditions for finalization. Returns nil on success, rejection map on failure."
  [lane-state opts]
  (cond
    (not= :execution-closed (:lifecycle lane-state))
    {:status :rejected :reason :not-execution-closed :lifecycle (:lifecycle lane-state)}

    (some? (:lane/finalizer lane-state))
    {:status :rejected :reason :finalizer-already-active
     :finalizer-id (:finalizer/id (:lane/finalizer lane-state))}

    ;; FG-5: parent fence check
    (and (some? (:lane/expected-parent-root lane-state))
         (not= (:lane/expected-parent-root lane-state) (:observed-parent-root opts)))
    {:status :rejected :reason :stale-parent
     :expected (:lane/expected-parent-root lane-state)
     :observed (:observed-parent-root opts)}

    ;; FG-6: basis validation
    (and (contains? opts :valid-basis?)
         (not (:valid-basis? opts)))
    {:status :rejected :reason :invalid-finalization-basis}

    :else nil))

(defn finalize-lane!
  "Enter the cardinality-one finalization lane and produce the authoritative write.

   FG-3: The single CAS — at most one EXECUTION_CLOSED → FINALIZING transition
   succeeds per generation. CAS(
     lifecycle = :execution-closed AND finalizer = nil
     →
     lifecycle = :finalizing AND finalizer = ticket
   )

   Preconditions checked inside the CAS loop, atomically with the transition:
     - lane is :execution-closed
     - no finalizer is active (cardinality one)
     - observed parent matches expected parent (FG-5, when expected set)
     - finalization basis is valid (FG-6, when basis supplied)

   `opts` is a map with keys:
     :observed-parent-root  — the current parent/head at the mutation boundary.
                              Required when :expected-parent-root was set at open.
     :closed-result-set-root — the sealed result set root (for ticket binding).
     :basis-root            — the finalization basis root (proves commitment chain).
     :valid-basis?          — when true, basis validation passed. Default true.

   `write-fn` receives the finalizer ticket and must return
   {:committed result} on success, throw on failure, or return
   {:rejected reason} for domain-level rejection.

   Returns {:status :committed :result ... :ticket ...} on success.
   Returns {:status :rejected :reason ...} on precondition failure (no state change).
   On write-fn throw or rejection, transitions to ABORTED — publishes nothing (FG-7)."
  [lane opts write-fn]
  (loop []
    (let [s @lane
          pre (finalize-preconditions s opts)]
      (if pre
        ;; Precondition failed — return rejection without state change
        pre
        (if-not (= :execution-closed (:lifecycle s))
          {:status :rejected :reason :not-execution-closed :lifecycle (:lifecycle s)}
          (if (some? (:lane/finalizer s))
            {:status :rejected :reason :finalizer-already-active
             :finalizer-id (:finalizer/id (:lane/finalizer s))}
            (let [ticket (-> s
                             (assoc :lane/closed-root (:closed-result-set-root opts)
                                    :lane/basis-root (:basis-root opts))
                             make-ticket)
                  gate (assoc s
                              :lifecycle :finalizing
                              :lane/finalizer ticket)]
              (if (compare-and-set! lane s gate)
                ;; CAS succeeded — write or abort
                (try
                  (let [write-result (write-fn ticket)]
                    (if (:committed write-result)
                      (do
                        (swap! lane assoc
                               :lifecycle :finalized
                               :lane/result (:committed write-result))
                        {:status :committed
                         :result (:committed write-result)
                         :ticket ticket})
                      (do
                        (swap! lane assoc
                               :lifecycle :aborted
                               :lane/abort-reason (or (:reason write-result)
                                                      :write-rejected))
                        {:status :rejected
                         :reason (or (:reason write-result) :write-rejected)})))
                  (catch Exception e
                    (swap! lane assoc
                           :lifecycle :aborted
                           :lane/abort-reason {:reason :write-threw
                                               :error (ex-message e)})
                    {:status :rejected
                     :reason :write-threw
                     :error (ex-message e)}))
                ;; CAS failed — retry
                (recur)))))))))

(defn finalized?
  "True when the lane reached FINALIZED."
  [lane]
  (= :finalized (:lifecycle @lane)))

(defn aborted?
  "True when the lane reached ABORTED."
  [lane]
  (= :aborted (:lifecycle @lane)))

(defn result
  "The committed result, or nil if not yet finalized."
  [lane]
  (:lane/result @lane))

(defn ticket
  "The active or completed finalizer ticket, or nil."
  [lane]
  (:lane/finalizer @lane))

(defn abort-reason
  "The abort reason, or nil."
  [lane]
  (:lane/abort-reason @lane))
