(ns resolver-sim.contract-model.idempotency
  "Protocol-agnostic idempotency helpers for replay/kernel-level operations.

   This namespace is intentionally generic: it stores opaque operation keys and
   applies a caller-provided transition at most once for each key.")

(defn applied?
  "True if op-key has already been applied in this world snapshot."
  [world op-key]
  (contains? (get world :idempotency/applied #{}) op-key))

(defn mark-applied
  "Record op-key as applied."
  [world op-key]
  (update world :idempotency/applied (fnil conj #{}) op-key))

(defn apply-once
  "Apply apply-fn at most once for a given op-key.

   Returns:
     :applied-once      — first successful application (op-key recorded)
     :no-op-duplicate   — duplicate of a previously successful application
     :attempted-failed  — first attempt failed; op-key NOT recorded (retryable)

   Contract:
   - apply-fn must return a standard transition result map ({:ok bool ...}).
   - On success (:ok truthy), op-key is recorded in :idempotency/applied
     and the application is considered final for this key.
   - On failure (:ok falsey), op-key is NOT recorded and the caller MAY
     retry the same op-key later.  This preserves at-most-once-success
     semantics: idempotency means 'at most one successful application',
     not 'at most one attempted application'."
  [world op-key apply-fn]
  (if (applied? world op-key)
    {:ok true
     :world world
     :extra {:idempotency :no-op-duplicate
             :op-key op-key}}
    (let [result (apply-fn world)]
      (if (:ok result)
        (-> result
            (update :world mark-applied op-key)
            (update :extra (fnil merge {}) {:idempotency :applied-once}))
        (update result :extra
                (fnil merge {})
                {:idempotency :attempted-failed
                 :retryable? true
                 :op-key op-key})))))

(defn ensure-not-duplicate
  "Guard helper for operation handlers that need an explicit failure on duplicate.
   Returns {:ok false :error :duplicate-operation} when op-key already exists,
   otherwise {:ok true :world world}."
  [world op-key]
  (if (applied? world op-key)
    {:ok false :error :duplicate-operation}
    {:ok true :world world}))
