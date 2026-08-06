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
                                                        :parent-rejection-not-resubmittable
     6. current-head check                         -> :rejected :parent-not-current-head
      7. successor existence                        -> :rejected :parent-already-has-successor
      8. sequence validation                        -> :rejected :sequence-gap / :sequence-regression
      9. child already committed under another
         parent (prior-state integrity)             -> :rejected :receipt-already-committed
      9b. cycle validation                          -> :rejected :cycle-detected
      10. commit contention (expected chain version)-> :rejected :commit-contention"
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.resubmission.receipt :as receipt]))

(declare commit-admit)

(def state-domain "prf.resubmission-chain-state.v1")
(def effects-domain "prf.transaction-effects.v1")

(def actions
  "Namespaced action vocabulary (canonical, independent of Clojure source ns)."
  #{:prf.resubmission/admit-child
    :prf.resubmission/apply-disposition})

(defn empty-state
  "Initial versioned chain state for a resubmission family."
  [family-id]
  {:chain/family-id family-id
   :chain/version 0
   :transaction/commit-index 0
   :transaction/last-hash nil
   :chain/head nil
   :chain/successor-by-parent {}
   :chain/effective-disposition-by-receipt {}
   :chain/disposition-head-by-receipt {}
   :chain/idempotency-index {}   ; idempotency-key -> {:content-key :receipt-hash}
   :chain/content-index {}       ; content-key -> {:parent-receipt-hash :receipt-hash}
   :chain/attempt-receipts {}})  ; receipt-hash -> {:attempt-receipt :sequence :parent-receipt-hash}

(defn- chain-state-projection
  "The domain state committed by the state root. EXCLUDES the attempt receipts
   (the receipt commits the transaction ordering hash, so including it would
   create a cycle) and :transaction/last-hash (the ordering hash itself)."
  [state]
  {:chain/family-id (:chain/family-id state)
   :chain/version (:chain/version state)
   :transaction/commit-index (:transaction/commit-index state)
   :chain/head (:chain/head state)
   :chain/successor-by-parent (:chain/successor-by-parent state)
   :chain/effective-disposition-by-receipt (:chain/effective-disposition-by-receipt state)
   :chain/disposition-head-by-receipt (:chain/disposition-head-by-receipt state)
   :chain/idempotency-index (:chain/idempotency-index state)
   :chain/content-index (:chain/content-index state)})

(defn state-root
  "Domain-separated state root (stable across :transaction/last-hash)."
  [state]
  (str "sha256:" (hc/domain-hash state-domain (chain-state-projection state))))

(defn effects-root
  "Domain-separated root over the emitted effects vector."
  [effects]
  (str "sha256:" (hc/domain-hash effects-domain (vec effects))))

(defn effective-disposition
  "Effective lifecycle status of a receipt from the disposition index (default
   :active when no disposition has been applied)."
  [state receipt-hash]
  (get (:chain/effective-disposition-by-receipt state) receipt-hash :active))

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
                                             :chain-version version}}}))

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
      {:status :rejected :reason :parent-rejection-not-resubmittable}

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

(defn- apply-disposition
  "Pure transition for :prf.resubmission/apply-disposition. Updates the
   effective lifecycle of an attempt. Increments the commit index WITHOUT
   creating a new resubmission sequence (the chain head/successor are
   untouched)."
  [state input]
  (let [{:keys [attempt-receipt-hash disposition-artifact-hash disposition-status
                expected-disposition-head expected-chain-version]} input
        cur-head (get (:chain/disposition-head-by-receipt state) attempt-receipt-hash)]
    (cond
      (not (contains? (:chain/attempt-receipts state) attempt-receipt-hash))
      {:status :rejected :reason :attempt-not-found}

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
