(ns resolver-sim.validation.root
  "Validation-root builder built on resolver-sim.validation.state.

   This is the only namespace that should integrate the semantic state API
   with the finalized validation root shape. It does NOT call
   resolver-sim.util.state-monad directly — all state operations go through
   resolver-sim.validation.state.

   ── public API ──

     empty-root           — initial validation state map
     derive-root-status   — :passed | :warning | :failed from accumulated keys
     finalize-root        — add :status and :validation/root-version
     build-root           — run a state computation, finalize, return root
     merge-suite-result   — absorb a suite result map into current state

   ── finalized root shape ──

     {:validation/root-version \"validation-root.v1\"
      :status               :passed | :warning | :failed
      :status-keys          #{...}
      :error-keys           #{...}
      :warning-keys         #{...}
      :checks               [...]
      :errors               [...]
      :warnings             [...]
      :evidence             [...]
      :metrics              {:checks N :passed N :failed N :warnings N}
      :extra                {:run-id ...}
      :suite/id             keyword|nil
      :suite/type           keyword|nil}"

  (:require [resolver-sim.validation.state :as vs]))

(def status-precedence
  "Ordered vector of status keywords from most severe to least.
   Status derivation follows this order — the first matching condition wins.

     [:failed :warning :passed]"
  [:failed :warning :passed])

(def empty-root
  "Initial validation state before any checks are executed."
  {:status       :unknown
   :status-keys  #{}
   :error-keys   #{}
   :warning-keys #{}
   :checks       []
   :errors       []
   :warnings     []
   :evidence     []
   :metrics      {:checks 0 :passed 0 :failed 0 :warnings 0}
   :extra        {}
   :suite/id     nil
   :suite/type   nil})

(def empty-validation-root
  "Alias for empty-root. Retained for backward compatibility."
  empty-root)

(defn derive-root-status
  "Derive final root status from accumulated state.
   Precedence order (defined in status-precedence):
     :failed  — any :error-keys present
     :warning — any :warning-keys present, no errors
     :passed  — no error or warning keys

   See also status-precedence for the ordered priority ladder."
  [root]
  (cond
    (seq (:error-keys root))   :failed
    (seq (:warning-keys root)) :warning
    :else                      :passed))

(defn finalize-root
  "Finalize a validation state map by deriving :status and adding
   :validation/root-version. Returns the completed root map."
  [state]
  (-> state
      (assoc :status (derive-root-status state))
      (assoc :validation/root-version "validation-root.v1")))

(defn build-root
  "Run a state computation (built from resolver-sim.validation.state
   semantic operations) against empty-root and finalize the result.

   Example:
     (build-root
       (vs/bind (vs/record-pass)
         (fn [_]
           (vs/record-evidence {:check/id :yield/solvency
                                :status :passed}))))"
  [computation]
  (-> (vs/exec-state computation empty-root)
      finalize-root))

(defn merge-suite-result
  "Return a state computation that absorbs a suite-result map into the
   current validation state. The suite-result should have:
     :status-keys, :error-keys, :warning-keys,
     :errors, :warnings, :evidence, :checks, :metrics,
     :suite/id, :suite/type (all optional, anything present is merged)"
  [suite-result]
  (fn [state]
    (let [state' (-> state
                     (update :status-keys  into (:status-keys  suite-result #{}))
                     (update :error-keys   into (:error-keys   suite-result #{}))
                     (update :warning-keys into (:warning-keys suite-result #{}))
                     (update :errors       into (:errors       suite-result []))
                     (update :warnings     into (:warnings     suite-result []))
                     (update :evidence     into (:evidence     suite-result []))
                     (update :checks       into (:checks       suite-result []))
                     (update :metrics (fn [m]
                                        (merge-with + m (:metrics suite-result))))
                     (cond-> (:suite/id suite-result)
                       (assoc :suite/id (:suite/id suite-result)))
                     (cond-> (:suite/type suite-result)
                       (assoc :suite/type (:suite/type suite-result))))]
      [nil state'])))
