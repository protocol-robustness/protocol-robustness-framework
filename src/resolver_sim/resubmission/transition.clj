(ns resolver-sim.resubmission.transition
  "Pure resubmission state transition.

   This is the SEMANTIC AUTHORITY for the mutable resubmission chain state. It
   owns:

     - namespaced action dispatch (:prf.resubmission/admit-child,
       :prf.resubmission/apply-disposition);
     - the PINNED rejection precedence (see `admit-child`);
     - deduplication semantics;
     - idempotent replay;
     - head and sequence validation;
     - effective-disposition validation;
     - cycle prevention;
     - state-root and effects-root derivation;
     - emitted effects.

   It is pure: given a state and a closed command it returns a result map and
   NEVER mutates anything. The persistent store (resolver-sim.transaction.
   protocol/transact!) executes this function atomically.

   Distinction (design §1):
     :chain/sequence            position in the research attempt chain
     :transaction/commit-index  position in the ordered mutation log
   A disposition action increments the commit index without creating a new
   resubmission sequence.

   PINNED REJECTION PRECEDENCE for admit-child (externally observable and
   pinned in tests/traces):
     1. same idempotency key + same content        -> :idempotent-replay
     2. same idempotency key + different content   -> :rejected :idempotency-content-mismatch
     3. already-observed identical content         -> :rejected :duplicate-content-submission
     4. transplant detection                       -> :rejected :idempotency-key-rebound
      5. parent / disposition eligibility           -> :rejected :previous-not-found /
                                                         :parent-rejection-not-final /
                                                         :parent-not-rejected /
                                                         :parent-not-resubmittable /
                                                         :parent-attempt-withdrawn
     6. current-head check                         -> :rejected :parent-not-current-head
      7. successor existence                        -> :rejected :parent-already-has-successor
      8. sequence validation                        -> :rejected :sequence-gap / :sequence-regression
      9. child already committed under another
         parent (prior-state integrity)             -> :rejected :receipt-already-committed
      9b. cycle validation                          -> :rejected :cycle-detected
      10. commit contention (expected chain version)-> :rejected :commit-contention"
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.resubmission.disposition :as disposition]
            [resolver-sim.resubmission.receipt :as receipt]
            [resolver-sim.transaction.ordering :as ordering]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:private input-root-domain
  "Domain tag for the canonical change-input (command) root."
  :prf-transaction-input-v1)

(declare commit-admit)

(def ^:private state-domain :prf-resubmission-chain-state-v1)
(def ^:private effects-domain :prf-transaction-effects-v1)

(def actions
  "Namespaced action vocabulary (canonical, independent of Clojure source ns)."
  #{:prf.resubmission/admit-child
    :prf.resubmission/apply-disposition})

(defn command-input-root
  "Canonical change-input root: a committed commitment to the command's intent,
   excluding chain-position/concurrency guards so the same requested change keeps
   a stable identity across resequencing. `expected-chain-version` and
   `expected-disposition-head` are concurrency preconditions, not change intent,
   and therefore remain in :transaction/expected/:transaction/observed and the
   ordering/admission preconditions rather than here.

   :prf.resubmission/admit-child
      -> commits {parent-receipt-hash, candidate-attempt-receipt-id,
      idempotency-key, content-key} (omits sequence, expected-chain-version)
   :prf.resubmission/apply-disposition
      -> commits {attempt-receipt-hash, disposition-artifact-hash}
      (omits expected-disposition-head, expected-chain-version)"
  [action input]
  (let [basis (case action
                :prf.resubmission/admit-child
                {:parent-receipt-hash (:parent-receipt-hash input)
                 :candidate-attempt-receipt-id (:candidate-attempt-receipt-id input)
                 :idempotency-key (:idempotency-key input)
                 :content-key (:content-key input)}
                :prf.resubmission/apply-disposition
                {:attempt-receipt-hash (:attempt-receipt-hash input)
                 :disposition-artifact-hash (when-let [artifact (:disposition-artifact input)]
                                              (disposition/disposition-hash artifact))})]
    (when (nil? basis)
      (throw (ex-info "command-input-root: unsupported action" {:action action})))
    (hash-ref/sha256-ref
     (hc/domain-hash input-root-domain basis))))

(defn empty-state
  "Initial versioned chain state for a resubmission family.

   `disposition-public-hex` is trusted chain configuration, not transaction
   input. Without it, disposition actions fail closed."
  ([family-id] (empty-state family-id nil))
  ([family-id disposition-public-hex]
   {:chain/family-id family-id
    :chain/disposition-public-hex disposition-public-hex
    :chain/version 0
    :transaction/commit-index 0
    :transaction/last-hash nil
    :chain/head nil
    :chain/successor-by-parent {}
    :chain/effective-disposition-by-receipt {} ; receipt lifecycle status
    :chain/disposition-head-by-receipt {}
    :chain/idempotency-index {}   ; idempotency-key -> {:content-key :receipt-hash}
    :chain/content-index {}       ; content-key -> {:parent-receipt-hash :receipt-hash}
    :chain/attempt-receipts {}})) ; receipt-hash -> {:attempt-receipt :sequence :parent-receipt-hash}

(def ^:const chain-state-projection-schema
  "Version identifier for the projected state fields. The version is carried
  by the domain tag (prf.resubmission-chain-state.v1) and this constant, NOT
  by a versioned key inside the hashed projection (which would alter roots)."
  "chain-state-projection.v1")

(defn- chain-state-projection
  "Exact fields projected for the v1 chain-state root (chain-state-projection.v1).

   Required projected fields (always present, in order):
     :chain/family-id
     :chain/version
     :transaction/commit-index
     :chain/head
     :chain/successor-by-parent
     :chain/effective-disposition-by-receipt
     :chain/disposition-head-by-receipt
     :chain/idempotency-index
     :chain/content-index

   Optional projected field:
     :chain/disposition-status-by-receipt
   Included ONLY when (contains? state :chain/disposition-status-by-receipt)
   is true. When present, the field's value is projected verbatim (even if
   the value is an empty map {}).

   Exclusion of unknown source fields:
     Only the keys above are selected via ->/cond->. Any other key in the
     source state (e.g. :transaction/last-hash, :chain/attempt-receipts,
     :chain/disposition-public-hex, :chain/version beyond the projected
     subset) never enters the projection.

   Absent-vs-empty semantics (v1, preserved):
     Absent :chain/disposition-status-by-receipt (key not in source state)
       -> the key is omitted from the projection.
     Present-but-empty {:chain/disposition-status-by-receipt {}}
       -> the key IS included in the projection with value {}.
     These are intentionally distinct semantic states and produce different
     state roots. This behavior is v1-stable; normalizing absent to empty
     (or vice-versa) would alter roots and is deferred to a potential v2
     migration."
  [state]
  (cond-> {:chain/family-id (:chain/family-id state)
           :chain/version (:chain/version state)
           :transaction/commit-index (:transaction/commit-index state)
           :chain/head (:chain/head state)
           :chain/successor-by-parent (:chain/successor-by-parent state)
           :chain/effective-disposition-by-receipt (:chain/effective-disposition-by-receipt state)
           :chain/disposition-head-by-receipt (:chain/disposition-head-by-receipt state)
           :chain/idempotency-index (:chain/idempotency-index state)
           :chain/content-index (:chain/content-index state)}
    (contains? state :chain/disposition-status-by-receipt)
    (assoc :chain/disposition-status-by-receipt
           (:chain/disposition-status-by-receipt state))))

(defn state-root
  "Domain-separated state root (stable across :transaction/last-hash)."
  [state]
  (hash-ref/sha256-ref (hc/domain-hash state-domain (chain-state-projection state))))

(defn effects-root
  "Domain-separated root over the emitted effects vector."
  [effects]
  (hash-ref/sha256-ref (hc/domain-hash effects-domain (vec effects))))

(defn effective-disposition
  "Effective lifecycle status of a receipt from the disposition index (default
   :active when no disposition has been applied)."
  [state receipt-hash]
  (get (:chain/effective-disposition-by-receipt state) receipt-hash :active))

(defn- current-disposition-status
  "Raw disposition event status, retained separately from effective lifecycle."
  [state receipt-hash]
  (get (:chain/disposition-status-by-receipt state) receipt-hash :active))

(defn- receipt-eligible-parent?
  "A stored receipt is a valid direct-resubmission parent."
  [entry]
  (and (map? entry)
       (receipt/direct-resubmission-parent? (:attempt-receipt entry))))

(defn- commit-admit
  [state input child-receipt-hash parent-receipt-hash]
  (let [new-state
        (-> state
            (assoc :chain/version (inc (:chain/version state))
                   :transaction/commit-index (inc (:transaction/commit-index state))
                   :chain/head child-receipt-hash)
            (assoc-in [:chain/successor-by-parent parent-receipt-hash] child-receipt-hash)
            (assoc-in [:chain/attempt-receipts child-receipt-hash]
                      {:attempt-receipt (:candidate-attempt-receipt input)
                       :sequence (:sequence input)
                       :parent-receipt-hash parent-receipt-hash})
            (assoc-in [:chain/idempotency-index (:idempotency-key input)]
                      {:content-key (:content-key input)
                       :receipt-hash child-receipt-hash})
            (assoc-in [:chain/content-index (:content-key input)]
                      {:parent-receipt-hash parent-receipt-hash
                       :receipt-hash child-receipt-hash}))
        effects [{:effect/type :chain-successor :parent parent-receipt-hash :child child-receipt-hash}
                 {:effect/type :chain-head :family-id (:chain/family-id state) :head child-receipt-hash}
                 {:effect/type :chain-version :family-id (:chain/family-id state)
                  :version (:chain/version new-state)}
                 {:effect/type :idempotency-index :key (:idempotency-key input) :child child-receipt-hash}
                 {:effect/type :content-index :key (:content-key input) :child child-receipt-hash}
                 {:effect/type :attempt-receipt :receipt-id child-receipt-hash}]
        version (:chain/version state)]
    {:status :committed
     :state new-state
     :public-result {:chain-head child-receipt-hash
                     :sequence (:sequence input)
                     :admission-status :admitted}
     :effects effects
     :ordering-input {:transaction/action :prf.resubmission/admit-child
                      :transaction/scope :resubmission-family
                      :transaction/conflict-key [:resubmission-family (:chain/family-id state)]
                      :transaction/expected {:chain-head parent-receipt-hash
                                             :chain-version version}
                      :transaction/observed {:chain-head parent-receipt-hash
                                             :chain-version version}
                      :transaction-ordering/schema ordering/ordering-v2-schema
                      :transaction/input-root (command-input-root :prf.resubmission/admit-child input)}}))

(defn- admit-child
  "Pure transition for :prf.resubmission/admit-child following the pinned
   rejection precedence."
  [state input]
  (let [{:keys [parent-receipt-hash candidate-attempt-receipt
                candidate-attempt-receipt-id idempotency-key content-key sequence
                expected-chain-version]} input
        child-receipt-hash (or (:attempt-receipt/id candidate-attempt-receipt)
                               candidate-attempt-receipt-id)
        idem (get (:chain/idempotency-index state) idempotency-key)
        content (get (:chain/content-index state) content-key)
        parent-entry (get (:chain/attempt-receipts state) parent-receipt-hash)
        parent-seq (:sequence parent-entry)
        expected-seq (if (and parent-receipt-hash parent-seq) (inc parent-seq) 1)]
    (cond
      ;; 1. idempotent replay (same key + same content)
      (and (some? idempotency-key) (some? idem))
      (if (= content-key (:content-key idem))
        {:status :idempotent-replay
         :reason :submission-already-observed
         :public-result {:existing (:receipt-hash idem)}}
        {:status :rejected
         :reason :idempotency-content-mismatch
         :public-result {:existing (:receipt-hash idem)}})

      ;; 3/4. duplicate content / transplant
      (some? content)
      (if (= (:parent-receipt-hash content) parent-receipt-hash)
        {:status :rejected
         :reason :duplicate-content-submission
         :public-result {:existing (:receipt-hash content)}}
        {:status :rejected
         :reason :idempotency-key-rebound
         :public-result {:prior-parent (:parent-receipt-hash content)}})

      ;; 5. parent / disposition eligibility
      (nil? parent-receipt-hash)
      (if (and (nil? (:chain/head state)) (= 1 sequence))
        (commit-admit state input child-receipt-hash nil)
        {:status :rejected :reason :initial-sequence-mismatch})

      (nil? parent-entry)
      {:status :rejected :reason :previous-not-found}

      (not= :active (effective-disposition state parent-receipt-hash))
      {:status :rejected :reason :parent-rejection-not-final}

      (not (receipt-eligible-parent? parent-entry))
      (let [parent-receipt (:attempt-receipt parent-entry)
            mismatch (receipt/resubmission-parent-requirement-mismatch parent-receipt)]
        {:status :rejected
         :reason (or mismatch :parent-rejection-not-resubmittable)
         :public-result {:parent-mismatch mismatch}})

      ;; 6. current-head
      (not= parent-receipt-hash (:chain/head state))
      {:status :rejected :reason :parent-not-current-head}

      ;; 7. successor existence
      (contains? (:chain/successor-by-parent state) parent-receipt-hash)
      {:status :rejected :reason :parent-already-has-successor}

      ;; 8. sequence
      (< sequence expected-seq)
      {:status :rejected :reason :sequence-regression
       :public-result {:sequence sequence :expected expected-seq}}

      (> sequence expected-seq)
      {:status :rejected :reason :sequence-gap
       :public-result {:sequence sequence :expected expected-seq}}

      ;; 9. child identity already committed under another parent (prior-state
      ;;    integrity): re-admitting an existing receipt hash would overwrite its
      ;;    prior parent/sequence and fork the chain.
      (contains? (:chain/attempt-receipts state) child-receipt-hash)
      {:status :rejected :reason :receipt-already-committed}

      ;; 9b. cycle
      (= child-receipt-hash parent-receipt-hash)
      {:status :rejected :reason :cycle-detected}

      ;; 10. commit contention
      (and (some? expected-chain-version)
           (not= expected-chain-version (:chain/version state)))
      {:status :rejected :reason :commit-contention
       :public-result {:expected-version expected-chain-version
                       :observed-version (:chain/version state)}}

      :else
      (commit-admit state input child-receipt-hash parent-receipt-hash))))

(def allowed-disposition-transitions
  "The disposition-event transitions accepted by the mutable chain."
  {:active disposition/disposition-statuses
   :pending-review #{:final :withdrawn :revoked :superseded}
   :final #{:withdrawn :revoked :superseded}
   :withdrawn #{}
   :revoked #{}
   :superseded #{}})

(defn- apply-disposition
  "Apply a complete, signed, receipt-bound disposition artifact. Caller-supplied
   status and hash fields are deliberately ignored: all committed values are
   derived from `:disposition-artifact` after verification with the chain's
   trusted disposition authority."
  [state input]
  (let [{:keys [attempt-receipt-hash disposition-artifact
                expected-disposition-head expected-chain-version]} input
        cur-head (get (:chain/disposition-head-by-receipt state) attempt-receipt-hash)
        disposition-artifact-hash (when disposition-artifact
                                    (disposition/disposition-hash disposition-artifact))
        disposition-status (:attempt-disposition/status disposition-artifact)
        declared-attempt (:attempt-disposition/attempt-receipt-hash disposition-artifact)
        declared-previous (:attempt-disposition/previous-disposition-hash disposition-artifact)
        verification (when (and disposition-artifact
                                (:chain/disposition-public-hex state))
                       (disposition/verify-disposition
                        disposition-artifact (:chain/disposition-public-hex state)))]
    (cond
      (not (contains? (:chain/attempt-receipts state) attempt-receipt-hash))
      {:status :rejected :reason :attempt-not-found}

      (nil? (:chain/disposition-public-hex state))
      {:status :rejected :reason :disposition-authority-not-configured}

      (not (map? disposition-artifact))
      {:status :rejected :reason :missing-disposition-artifact}

      (not (:valid? verification))
      {:status :rejected :reason (:reason verification)}

      (not= attempt-receipt-hash declared-attempt)
      {:status :rejected :reason :disposition-receipt-mismatch}

      (not= cur-head declared-previous)
      {:status :rejected :reason :disposition-previous-hash-mismatch
       :public-result {:expected-disposition-head cur-head
                       :declared-previous-disposition-hash declared-previous}}

      (not (contains? (get allowed-disposition-transitions
                           (current-disposition-status state attempt-receipt-hash) #{})
                      disposition-status))
      {:status :rejected :reason :invalid-disposition-transition
       :public-result {:from (current-disposition-status state attempt-receipt-hash)
                       :to disposition-status}}

      (and (some? expected-disposition-head)
           (not= expected-disposition-head cur-head))
      {:status :rejected :reason :disposition-head-mismatch
       :public-result {:expected-disposition-head expected-disposition-head
                       :observed-disposition-head cur-head}}

      (and (some? expected-chain-version)
           (not= expected-chain-version (:chain/version state)))
      {:status :rejected :reason :commit-contention
       :public-result {:expected-version expected-chain-version
                       :observed-version (:chain/version state)}}

      :else
      (let [new-state
            (-> state
                (assoc :chain/version (inc (:chain/version state))
                       :transaction/commit-index (inc (:transaction/commit-index state)))
                (assoc-in [:chain/effective-disposition-by-receipt attempt-receipt-hash]
                          (get disposition/disposition->lifecycle-status disposition-status))
                (assoc-in [:chain/disposition-status-by-receipt attempt-receipt-hash]
                          disposition-status)
                (assoc-in [:chain/disposition-head-by-receipt attempt-receipt-hash]
                          disposition-artifact-hash))
            effects [{:effect/type :disposition
                      :attempt-receipt-hash attempt-receipt-hash
                      :status disposition-status
                      :disposition-artifact-hash disposition-artifact-hash}]
            version (:chain/version state)]
        {:status :committed
         :state new-state
         :public-result {:attempt-receipt-hash attempt-receipt-hash
                         :disposition-status disposition-status}
         :effects effects
         :ordering-input {:transaction/action :prf.resubmission/apply-disposition
                          :transaction/scope :resubmission-family
                          :transaction/conflict-key [:resubmission-family (:chain/family-id state)]
                          :transaction-ordering/schema ordering/ordering-v2-schema
                          :transaction/input-root (command-input-root :prf.resubmission/apply-disposition input)
                          :transaction/expected {:disposition-head cur-head
                                                 :chain-version version}
                          :transaction/observed {:disposition-head cur-head
                                                 :chain-version version}}}))))

(defn apply-action
  "Pure transition dispatch. `command` is a closed map:
     {:transaction/action <namespaced action>
      :transaction/input  <action-specific input>}

   Returns {:status :committed | :rejected | :idempotent-replay
            :state ... (committed only)
            :public-result {...}
            :effects [...] (committed only)
            :ordering-input {...} (committed only)}."
  [state command]
  (let [action (:transaction/action command)]
    (case action
      :prf.resubmission/admit-child
      (admit-child state (:transaction/input command))

      :prf.resubmission/apply-disposition
      (apply-disposition state (:transaction/input command))

      {:status :rejected :reason :unknown-action})))
