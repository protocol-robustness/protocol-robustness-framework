(ns resolver-sim.validation.state
  "Semantic validation state operations built on the state monad.

   This is the only namespace that should call sm/update-state for validation
   purposes. Production validation code uses the semantic operations here.

   ── monadic composition (re-exported) ──

     return bind
     run-state eval-state exec-state
     sequence-state traverse-state

   ── semantic writes ──

     record-check       — append a check result
     record-error       — append error, derive error-key, increment failed+checks
     record-warning     — append warning, derive warning-key, increment warnings+checks
     record-pass        — increment passed metric
     record-evidence    — append an evidence record
     add-status-key     — conj to status-keys
     add-error-key      — conj to error-keys
     add-warning-key    — conj to warning-keys
     increment-metric   — increment a named metric counter
     set-suite-id       — set :suite/id
     set-suite-type     — set :suite/type
     merge-extra        — merge under :extra (cannot overwrite validation keys)

   ── semantic reads ──

     snapshot           — return the full current state
     get-status-keys    — return :status-keys set
     get-error-keys     — return :error-keys set
     get-warning-keys   — return :warning-keys set
     get-evidence       — return :evidence vector
     get-checks         — return :checks vector
     get-metrics        — return :metrics map

   NOT re-exported (restricted):
     get-state          — use snapshot instead
     put-state          — not exposed; use semantic operations
     update-state       — not exposed; use semantic operations

   Design constraint: callers cannot replace or arbitrarily mutate state.
   All mutations go through named semantic operations."

  (:require
   [resolver-sim.util.state-monad :as sm]))

;; ── safe re-exports from state monad ─────────────────────────────────────────

(def return sm/return)
(def bind sm/bind)

(def run-state sm/run-state)
(def eval-state sm/eval-state)
(def exec-state sm/exec-state)

(def sequence-state sm/sequence-state)
(def traverse-state sm/traverse-state)

;; ── semantic writes ──────────────────────────────────────────────────────────

(defn record-check
  "Add a check map to the validation state.
   The check should have :check/id, :status, :message etc."
  [check]
  (sm/update-state update :checks conj check))

(defn record-error
  "Add a structured error map and automatically:
   - conj its :key into :error-keys
   - increment metrics :failed and :checks

   Error map expected shape:
     {:key    keyword    ;; error-key, e.g. :yield/deferred-mismatch
      :severity keyword  ;; :critical | :warning | :info
      :message string
      ...}"
  [error]
  (fn [state]
    (let [state' (-> state
                     (update :errors conj error)
                     (update :error-keys conj (:key error :unclassified-error))
                     (update-in [:metrics :failed] inc)
                     (update-in [:metrics :checks] inc))]
      [nil state'])))

(defn record-warning
  "Add a structured warning map and automatically:
   - conj its :key into :warning-keys
   - increment metrics :warnings and :checks

   Warning map expected shape:
     {:key    keyword    ;; warning-key, e.g. :yield/drift
      :severity keyword
      :message string
      ...}"
  [warning]
  (fn [state]
    (let [state' (-> state
                     (update :warnings conj warning)
                     (update :warning-keys conj (:key warning :unclassified-warning))
                     (update-in [:metrics :warnings] inc)
                     (update-in [:metrics :checks] inc))]
      [nil state'])))

(defn record-pass
  "Record a passed outcome: increments metrics :passed and :checks."
  []
  (fn [state]
    (let [state' (-> state
                     (update-in [:metrics :passed] inc)
                     (update-in [:metrics :checks] inc))]
      [nil state'])))

(defn record-evidence
  "Add an evidence record to :evidence.
   Evidence shape: {:check/id :evidence/type :path [...] :binding ...}"
  [evidence]
  (sm/update-state update :evidence conj evidence))

(defn add-status-key
  "Conj a keyword into :status-keys."
  [k]
  (sm/update-state update :status-keys conj k))

(defn add-error-key
  "Conj a keyword into :error-keys (without creating an error entry)."
  [k]
  (sm/update-state update :error-keys conj k))

(defn add-warning-key
  "Conj a keyword into :warning-keys (without creating a warning entry)."
  [k]
  (sm/update-state update :warning-keys conj k))

(defn increment-metric
  "Increment a named counter in :metrics, e.g. (increment-metric :checks)."
  [k]
  (sm/update-state update-in [:metrics k] (fnil inc 0)))

(defn set-suite-id
  "Set the :suite/id in the validation state."
  [id]
  (sm/update-state assoc :suite/id id))

(defn set-suite-type
  "Set the :suite/type in the validation state."
  [t]
  (sm/update-state assoc :suite/type t))

(defn merge-extra
  "Write metadata under the :extra key. Unlike a raw merge, this cannot
   overwrite validation state keys such as :error-keys, :status-keys, or
   :metrics. Reads via (get-in state [:extra :run-id])."
  [m]
  (sm/update-state update :extra merge m))

;; ── semantic reads ───────────────────────────────────────────────────────────

(defn snapshot
  "Return the full current validation state as the computation value.
   Prefer get-* readers when only specific keys are needed."
  []
  (fn [state]
    [state state]))

(defn get-status-keys
  "Return :status-keys from the current state."
  []
  (fn [state]
    [(:status-keys state) state]))

(defn get-error-keys
  "Return :error-keys from the current state."
  []
  (fn [state]
    [(:error-keys state) state]))

(defn get-warning-keys
  "Return :warning-keys from the current state."
  []
  (fn [state]
    [(:warning-keys state) state]))

(defn get-evidence
  "Return :evidence from the current state."
  []
  (fn [state]
    [(:evidence state) state]))

(defn get-checks
  "Return :checks from the current state."
  []
  (fn [state]
    [(:checks state) state]))

(defn get-metrics
  "Return :metrics from the current state."
  []
  (fn [state]
    [(:metrics state) state]))
