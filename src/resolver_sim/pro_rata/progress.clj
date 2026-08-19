(ns resolver-sim.pro-rata.progress
  "Runtime-only progress event model for pro-rata work.

   This namespace owns the *event vocabulary*, a deterministic *reducer* from
   events to a snapshot, and the *atom adapter*. It is deliberately operational:
   nothing here participates in, or is hashed into, a canonical request, result,
   evidence, or statement root.

   Model
   -----
     allocator ──emits──▶ event ──▶ observer
                                       ├── progress-atom-observer ──reducer──▶ atom (snapshot)
                                       └── raw :on-progress callback

   The allocator knows nothing about how progress is stored: it emits typed
   events and a small safe dispatch primitive forwards them. A caller may
   observe them as an event stream (logs, channels, notebooks, CLIs, metrics)
   or, via the atom adapter, as the current snapshot.

   Concurrency
   -----------
   Parallel claimant work reports *atomic deltas*, never competing absolute
   counters, so the reducer advances `:current` monotonically regardless of
   arrival order:

     {:event :claimants-completed :delta 1}

   `:event/sequence` is assigned by a caller/programme-owned atomic sequencer at
   the emission boundary. It is purely operational and is never used to decide
   semantic order, and it never leaks into any canonical root.

   Truthfulness
   ------------
   An indeterminate stage (e.g. external proving that cannot report a
   percentage) is reported as `{:status :running :elapsed-ms ...}` rather than a
   fabricated figure.

   Contract (typed vs legacy)
   --------------------------
   *Normative* — the event vocabulary in `event-types`, reduced by each explicit
   `reducer` case, IS the pro-rata-progress.v1 API. It is the only path SP-A and
   SP-C are allowed to emit.
   *Legacy compatibility-only* — pre-SP-A callers may still feed untyped field
   maps or unknown event keywords. Those DO NOT participate in pro-rata-progress
   .v1; they are routed through the isolated `legacy-untyped-event->snapshot`
   adapter, which stamps `:progress/compat :untyped-event` so any such use is
   visibly detectable. Do not extend that adapter with new behavior. New work
   adds a typed event to `event-types` and a `reducer` case.")

(def schema-version "pro-rata-progress.v1")

(def statuses
  "Documented progress statuses. :completed/:failed/:cancelled are terminal."
  #{:pending :running :completed :failed :cancelled})

(def phases
  "Documented progress phases. Not every allocator uses every phase; a programme
   composes them. :proving and the admitting phases are vocabulary for later
   proof/programme work."
  #{:preparing :requesting :allocating :redistributing
    :validating :evidence :proving :verifying :admitting :completed})

(def event-types
  "Typed progress event vocabulary.
   Lifecycle events are emitted by allocation today; proof-pipeline events are
   vocabulary reserved for later phases and are not yet wired."
  #{:phase-started
    :phase-completed
    :claimants-completed
    :redistribution-started
    :redistribution-pass-completed
    :allocation-completed
    :proving
    ;; proof-pipeline vocabulary (not yet wired)
    :statement-constructed
    :guest-input-constructed
    :sp1-execution-completed
    :proof-generation-started
    :proof-generated
    :sdk-verification-completed
    :evm-verification-completed
    :statement-admitted})

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

(declare reducer)

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

(def ^:private snapshot-event-keys
  "Snapshot fields an event may contribute directly (operational only)."
  [:status :phase :current :total :pass-index :elapsed-ms])

(defn- typed-event?
  "True when `event` is a normative pro-rata-progress.v1 typed event (its
   `:event` keyword belongs to `event-types`). Anything else is legacy."
  [event]
  (and (keyword? (:event event))
       (contains? event-types (:event event))))

(defn- merge-typed-snapshot
  "Normative merge of snapshot fields for typed events without a dedicated
   reducer case (e.g. :phase-started/:phase-completed). Broadcast their declared
   snapshot fields; never treated as a terminal state on its own."
  [snapshot event]
  (reduce (fn [s k]
            (if (contains? event k) (assoc s k (get event k)) s))
          snapshot
          snapshot-event-keys))

(defn- legacy-untyped-event->snapshot
  "LEGACY compatibility-only adapter — NOT part of pro-rata-progress.v1.

   Pre-SP-A callers fed untyped field maps or unknown event keywords into the
   reducer. That degrades to a best-effort field merge here, and the resulting
   snapshot is stamped `:progress/compat :untyped-event` so future code can
   detect that a non-normative event was processed instead of silently relying
   on it. The legacy `:redistribution-pass` key is remapped to the canonical
   `:pass-index`.

   Do not extend this adapter with new behavior. New features add a typed event
   to `event-types` and a `reducer` case."
  [snapshot event]
  (let [event (if (contains? event :redistribution-pass)
                (assoc event :pass-index (:redistribution-pass event))
                event)]
    (assoc
     (reduce (fn [s k]
               (if (contains? event k) (assoc s k (get event k)) s))
             snapshot
             snapshot-event-keys)
     :progress/compat :untyped-event)))

(defn reducer
  "Progress event → snapshot reducer.

   Monotonic where it must be: :claimants-completed advances :current by an
   atomic :delta; :allocation-completed forces :completed. Terminal statuses are
   applied by callers/programmes that observe failures or cancellation.

   Normative flow: typed events (see `event-types`) are dispatched through the
   explicit cases below. The isolated legacy adapter is ONLY the compatibility
   path for pre-SP-A untyped maps / unknown event keywords.

   Operational and noncanonical: this never affects request/result/evidence/
   statement identity, and :event/sequence is never used to decide order."
  [snapshot event]
  (let [s (or snapshot (initial-progress))
        e (or event {})]
    (if (typed-event? e)
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

        (merge-typed-snapshot s e))
      (legacy-untyped-event->snapshot s e))))

(defn terminal-failed
  "Force a terminal :failed snapshot for the given phase. Operational only; kept
   free of stack traces and machine-specific exception strings."
  ([phase] (terminal-failed phase {}))
  ([phase {:keys [error-category]}]
   (cond-> (assoc (initial-progress)
                  :status :failed
                  :phase phase)
     error-category (assoc :error/category error-category))))
