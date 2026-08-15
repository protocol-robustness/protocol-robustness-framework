(ns resolver-sim.world.authority
  "Authoritative world holder with CAS-based commit boundary.

  The world itself remains a pure data structure with no revision field.
  The holder owns revision tracking, step counting, and atomic commit semantics,
  providing a compare-and-set boundary that prevents concurrent callers from
  both persisting successor worlds computed from the same predecessor state.

  withdraw-shared (and all dispatch-action implementations) remain pure:
  they take a world and return a new world. All side effects (evidence capture,
  state mutation) are either moved to the post-commit phase or are idempotent
  by contract.

  The CAS pattern follows the established convention in
  resolver-sim.resubmission.store and resolver-sim.resubmission.admission-store."

  (:require [clojure.tools.logging :as log]))

(defprotocol WorldAuthority
  "Protocol for an authoritative world holder with CAS commit semantics."

  (world-at
    [holder]
    "Return the current canonical world snapshot.")

  (snapshot
    [holder]
    "Return {:world W :revision R :step-count N} for CAS validation.")

  (cas-step!
    [holder transform-fn]
    "Atomically commit a pure world transformation with bounded retry.

    Reads the current snapshot (world + revision R). Applies transform-fn
    a pure World -> {:ok bool :world World' :step ...} function to obtain
    the successor. Validates that no other commit has occurred between read and
    CAS. On success, advances revision to R+1 and step-count by 1.

    If transform-fn returns {:ok false}, the step is a semantic rejection no
    revision advance, the holder state is unchanged.

    If the successor world is value-equal to the current world (excluding
    temporal context keys that advance on every step), the dispatch is a
    semantic no-op (e.g. a propagation that was already applied). The holder
    returns {:status :no-op} without advancing the revision.

    If the CAS fails (stale another caller committed between read and CAS),
    re-reads the world and re-computes. Retries are bounded; exhaustion
    results in a thrown exception.

    Returns a map with:
      :status        :committed | :no-op | :rejected
      :world         the world to use (current world on no-op/rejected)
      :revision      the revision after commit (or observed on no-op/rejected)
      :step-count    the step-count after commit (or current on no-op/rejected)
      :step          the raw step result from transform-fn
      :attempts      number of CAS attempts (1 on first success)
      :conflict?     true if at least one CAS contention occurred"))

(def ^:private max-cas-attempts
  "Bounded retry limit for CAS contention. Under normal operation CAS succeeds
   on the first attempt; this limit prevents infinite loops under sustained
   concurrent writes or a bug in the transform-fn."
  100)

(def ^:private temporal-context-keys
  "World keys that change on every step due to time advancement. Excluded from
   no-op detection so that a time-only advance (e.g. same-propagation re-apply)
   is correctly identified as a semantic no-op rather than a state change."
  #{:context/time :block-time})

(defn- comparable-world-state
  "Project the world to exclude temporal context keys for no-op detection.
   Uses dissoc which creates a shallow structural-sharing copy O(1) for
   maps with structural sharing, and Clojure's = short-circuits on first
   differing key."
  [world]
  (if (map? world)
    (apply dissoc world temporal-context-keys)
    world))

(defn- no-op?
  "True if the dispatch did not change world state beyond temporal advancement.
   Compares the world excluding :context/time and :block-time, which change
   on every process-step call."
  [before after]
  (= (comparable-world-state before)
     (comparable-world-state after)))

(defrecord AtomicWorldHolder [state-atom]
  WorldAuthority

  (world-at [_]
    (:world @state-atom))

  (snapshot [_]
    @state-atom)

  (cas-step! [_ transform-fn]
    (loop [attempts 1]
      (let [snap @state-atom
            {:keys [world revision step-count]} snap
            result (transform-fn world)
            ok (:ok result)
            successor (:world result)
            step (:step result)]
        (cond
          (not ok)
          ;; Semantic rejection — no commit, no revision advance.
          ;; May occur on a retry iteration (after CAS contention), so report
          ;; the actual attempt count and conflict status like the other paths.
          {:status :rejected
           :world world
           :revision revision
           :step-count step-count
           :step step
           :attempts attempts
           :conflict? (pos? (dec attempts))}

          (no-op? world successor)
          ;; Semantic no-op (e.g. propagation already applied). Don't advance
          ;; revision — the world state did not change.
          {:status :no-op
           :world world
           :revision revision
           :step-count step-count
           :step step
           :attempts attempts
           :conflict? (pos? (dec attempts))}

          ;; CAS: validate revision R is unchanged AND commit successor R+1.
          (compare-and-set! state-atom
                            snap
                            {:world successor
                             :revision (inc revision)
                             :step-count (inc step-count)})
          ;; CAS succeeded
          {:status :committed
           :world successor
           :revision (inc revision)
           :step-count (inc step-count)
           :step step
           :attempts attempts
           :conflict? (pos? (dec attempts))}

          ;; CAS failed (stale) — retry with bounded attempts.
          (>= attempts max-cas-attempts)
          (throw (ex-info "CAS commit exceeded bounded retry attempts"
                          {:reason :cas-retry-exhausted
                           :attempts attempts
                           :observed-revision (:revision @state-atom)
                           :max max-cas-attempts}))

          :else
          (do
            (log/warn "CAS contention on world holder, retrying"
                      {:revision revision
                       :attempts attempts})
            (recur (inc attempts))))))))

(defn atomic-world-holder
  "Create an authoritative world holder for an initial world.
   Revision starts at 0, step-count starts at 0."
  [initial-world]
  (map->AtomicWorldHolder
   {:state-atom (atom {:world initial-world
                       :revision 0
                       :step-count 0})}))

(defn step-count-of
  "Return the current step-count from the holder."
  [holder]
  (:step-count @(:state-atom holder)))

(defn revision-of
  "Return the current revision from the holder."
  [holder]
  (:revision @(:state-atom holder)))
