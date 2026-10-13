(ns resolver-sim.contract-model.replay.checkpoints
  "Checkpoint creation and retention for replay results.

   Checkpoints are captured for every event during replay but may be trimmed
   before results are returned. SPE fork replay only needs pre-decision worlds
   at strategic action seqs."
  (:require [resolver-sim.evidence.chain :as chain]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.logging :as log]))

(defn secure-checkpoint-update
  "Update a checkpoint map (states/checkpoints) with overwrite detection
   and append-only logging to :checkpoint-log."
  [acc key-field event checkpoint-data]
  (let [m (get acc key-field)
        seq-val (:seq event)
        already-exists? (contains? m seq-val)]
    (-> acc
        (update key-field assoc seq-val checkpoint-data)
        (update :checkpoint-log (fnil conj [])
                {:checkpoint/index (count (get acc :checkpoint-log []))
                 :event/seq        seq-val
                 :event/id         (:event/id event)
                 :world/hash       (hc/hash-with-intent {:hash/intent :world-structure} checkpoint-data)
                 ;; Captured immediately before process-step dispatch.
                 :checkpoint/type  :pre-event})
        (cond-> already-exists?
          (update-in [:diagnostics :checkpoint-collisions] (fnil conj [])
                     {:seq seq-val :target key-field})))))

(def strategic-checkpoint-actions
  "Set of action names that trigger strategic decision checkpoints."
  #{"raise_dispute" "escalate_dispute" "execute_resolution"})

(defn strategic-action?
  "Returns true if the action name is a strategic decision checkpoint."
  [action-name]
  (contains? strategic-checkpoint-actions action-name))

(defn decision-node-seqs
  "Return the set of trace `:seq` values at strategic decision actions."
  [trace]
  (into #{}
        (keep (fn [entry]
                (when (contains? strategic-checkpoint-actions (:action entry))
                  (:seq entry)))
              trace)))

(defn apply-checkpoint-policy
  "Trim `:world-checkpoints` on a replay result according to `policy`.

   Policies:
   - `:retain-all` — keep every captured checkpoint (debug / fork tooling)
   - `:decision-nodes-only` — keep only pre-decision worlds at strategic seqs
   - `:omit` — drop all checkpoints (library-style / memory-sensitive replay)"
  [policy trace checkpoints]
  (let [cps (or checkpoints {})]
    (case policy
      :retain-all           cps
      :omit                 {}
      :decision-nodes-only  (select-keys cps (decision-node-seqs trace))
      cps)))

(defn apply-checkpoint-policy-to-result
  "Update `result` with checkpoint retention applied."
  [policy result]
  (update result :world-checkpoints
          #(apply-checkpoint-policy policy (:trace result) %)))

;; ──────────────────────────────────────────────────────────────────────────────
;; Checkpoint Evidence (Evidence Layer 9)
;; ──────────────────────────────────────────────────────────────────────────────

(defn build-checkpoint-evidence
  "Build a checkpoint evidence map from a checkpoint log entry."
  [checkpoint-log-entry]
  (when checkpoint-log-entry
    (let [data {:checkpoint-id (str "cp-" (:event/seq checkpoint-log-entry))
                :event-seq (:event/seq checkpoint-log-entry)
                :world-hash (:world/hash checkpoint-log-entry)
                :chain-head (:checkpoint/index checkpoint-log-entry)}
          h (hc/hash-with-intent {:hash/intent :checkpoint-evidence} data)]
      {:artifact-kind :checkpoint-evidence
       :evidence-hash h
       :checkpoint/id (:checkpoint/id data)
       :checkpoint/world-hash (:world-hash data)
       :checkpoint/chain-head (:chain-head data)})))

(defn emit-checkpoint-evidence!
  "Emit a checkpoint evidence record to the chain.
   Best-effort — failures are logged."
  [checkpoint-log-entry]
  (when-let [evidence (build-checkpoint-evidence checkpoint-log-entry)]
    (try
      (chain/register-evidence! evidence)
      (catch Exception e
        (log/error! :checkpoint-evidence-failed
                    {:seq (:event/seq checkpoint-log-entry)
                     :error (.getMessage e)})))))

(defn emit-checkpoint-evidence-at-strategic-point!
  "Emit checkpoint evidence only at strategic decision actions.
   Best-effort — failures are logged."
  [checkpoint-log event]
  (let [latest-cp (last checkpoint-log)]
    (when (and latest-cp (strategic-action? (:action event)))
      (emit-checkpoint-evidence! latest-cp))))
