(ns resolver-sim.economics.progress
  "Generic runtime progress-event utilities.

   Provides a protocol-agnostic progress atom adapter, an event observer
   that reduces events into an atom, and a safe dispatch primitive.

   This namespace is the sole owner of the progress-atom / observer
   machinery.  resolver-sim.pro-rata.progress delegates here for the
   adapter functions; resolver-sim.economics.payoffs may require this
   namespace directly without creating a dependency on pro-rata.*.")

(def schema-version "economics-progress.v1")

(def statuses
  "Documented progress statuses. :completed/:failed/:cancelled are terminal."
  #{:pending :running :completed :failed :cancelled})

(def phases
  "Documented progress phases."
  #{:preparing :requesting :allocating :redistributing
    :validating :evidence :proving :verifying :admitting :completed})

(defn initial-progress
  "Initial progress snapshot.
   Optional :programme/id and :allocation/id are preserved operationally when
   supplied; neither affects canonical identity."
  ([] (initial-progress {}))
  ([opts]
   (let [pid (:programme/id opts)
         aid (:allocation/id opts)]
     (cond-> {:progress/schema schema-version
              :status :pending
              :phase :pending
              :current 0
              :total 0}
       pid (assoc :programme/id pid)
       aid (assoc :allocation/id aid)))))

(defn make-progress-atom
  "Caller-owned progress atom adapter. Pass the returned atom as
   :progress-atom (or as the argument to progress-atom-observer). The atom holds
   the current snapshot; it is operational state and never canonical."
  ([] (atom (initial-progress)))
  ([opts] (atom (initial-progress opts))))

(def ^:private snapshot-event-keys
  "Snapshot fields an event may contribute directly (operational only)."
  [:status :phase :current :total :pass-index :elapsed-ms])

(defn reducer
  "Progress event → snapshot reducer.

   Monotonic where it must be: :claimants-completed advances :current by an
   atomic :delta; :allocation-completed forces :completed. Terminal statuses are
   applied by callers/programmes that observe failures or cancellation.

   Operational and noncanonical: this never affects request/result/evidence/
   statement identity, and :event/sequence is never used to decide order."
  [snapshot event]
  (let [s (or snapshot (initial-progress))
        e (or event {})]
    (case (:event e)
      :claimants-completed
      (update s :current + (long (or (:delta e) 0)))

      :allocation-completed
      (assoc s :status :completed :phase :completed :current (:total s))

      :proving
      (merge s {:status :running :phase :proving}
             (select-keys e [:elapsed-ms]))

      :redistribution-started
      (assoc s :status :running :phase :redistributing
             :pass-index (or (:pass-index e) (:pass-index s)))

      :redistribution-pass-completed
      (assoc s :phase :redistributing
             :pass-index (or (:pass-index e) (:pass-index s)))

      ;; default: merge declared snapshot fields directly
      (reduce (fn [s k]
                (if (contains? e k) (assoc s k (get e k)) s))
              s
              snapshot-event-keys))))

(defn progress-atom-observer
  "Return an event observer that reduces events into `progress-atom`, the
   current snapshot. This is an adapter: the public abstraction is the event
   stream, not the atom. Observer exceptions are swallowed (semantic
   non-interference) and counted in the snapshot's operational :observer-errors."
  [progress-atom]
  (fn [event]
    (try
      (swap! progress-atom reducer event)
      (catch Exception _
        (swap! progress-atom update :observer-errors (fnil inc 0))
        nil))))

(defn counting-observer
  "Wrap a raw progress consumer so observer exceptions are swallowed (semantic
   non-interference) and counted in a caller-visible operational atom. Usable
   without any progress atom: a callback-only caller retains the same
   non-interference guarantee while still observing :observer-errors.

   Returns {:observe (fn [event]) :errors (atom n)}."
  [consumer]
  (let [errors (atom 0)]
    {:observe (fn [event]
                (try (consumer event)
                     (catch Exception _ (swap! errors inc) nil)))
     :errors errors}))

(defn normalize-progress-observer
  "Normalize a runtime progress observer to an event-consuming function.
   An atom is adapted to the reducer-based progress-atom-observer; a function is
   used directly; anything else observes nothing."
  [observer]
  (cond
    (fn? observer) observer
    (instance? clojure.lang.IAtom observer) (progress-atom-observer observer)
    :else nil))

(defn report-progress!
  "Small safe dispatch primitive for runtime progress events.

   The allocator emits events and knows nothing about how progress is stored.
   Observer exceptions are swallowed so a broken observer can never influence
   allocation semantics; callers that want the error count use
   counting-observer (usable without a progress atom)."
  [observer event]
  (when-let [emit (normalize-progress-observer observer)]
    (try (emit event)
         (catch Exception _ nil))))

(defn terminal-failed
  "Force a terminal :failed snapshot for the given phase. Operational only; kept
   free of stack traces and machine-specific exception strings."
  ([phase] (terminal-failed phase {}))
  ([phase {:keys [error-category]}]
   (cond-> (assoc (initial-progress)
                  :status :failed
                  :phase phase)
     error-category (assoc :error/category error-category))))
