(ns resolver-sim.transaction.protocol
  "Generic atomic transaction boundary.

   `transact!` is the ONLY mutation surface for a versioned state. A store
   implementation owns:

     - loading the snapshot for a conflict-key;
     - invoking the pure transition function;
     - atomically comparing the expected version;
     - committing the returned state and evidence;
     - retrying after CAS contention.

   It must NOT own domain rules (resubmission parents, eligibility, findings,
   researcher policy). Those live in the pure transition function passed in.

   `transition-fn` takes the current `state` and returns a domain transition
   result, conventionally:

     {:status :committed | :rejected | :idempotent-replay
      :state      new-state        ; when :committed
      :public-result {...}
      :effects    [...]
      :ordering-input {...}}

   A store returning the committed result must attach
   `:transaction-ordering` (resolver-sim.transaction.ordering) built from the
   committed transition so that ordering evidence and the state commit become
   visible atomically.")

(defprotocol TransactionStore
  (transact!
    [store conflict-key expected-version transition-fn]
    [store conflict-key transition-fn]
    "Atomically apply `transition-fn` to the state at `conflict-key`.

     `expected-version` (optional): when provided and the current version does
     not match, returns {:status :contention :reason :version-mismatch}
     WITHOUT invoking the transition (the caller is reading stale state).
     When nil, the store CAS-retries internally so the transition always runs
     against a fresh snapshot."))
