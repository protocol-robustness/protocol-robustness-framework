(ns resolver-sim.resubmission.authoritative-store
  "Authoritative resubmission chain store with checkpoint-head CAS fencing.

  The authoritative store wraps a resubmission chain store with an
  authenticated current-checkpoint-head. This root binds the chain state-root
  to an admitted authority context and provides the CAS fence that prevents
  replay or reordering of authoritative dispositions across checkpoint
  boundaries.

  Two disjoint result modes:
    :authoritative — disposition verified against the admitted authority key,
      checkpoint-bound, and committed under checkpoint CAS.
    :local-replay — same state transition effect, but disposition verified
      only against the chain-configured public key (no authority context,
      no checkpoint CAS). Used for local replay and testing.

  Phase 0 (in-process only): the authoritative store is an in-memory atom
  implementation. Portable verification of checkpoint progression is a future
  stage."
  (:require [resolver-sim.resubmission.authoritative-checkpoint :as auth-ckpt]
            [resolver-sim.resubmission.authority-context :as authority-context]
            [resolver-sim.resubmission.disposition :as disposition]
            [resolver-sim.resubmission.genesis-authorization :as genesis-authz]
            [resolver-sim.resubmission.transition :as transition]
            [resolver-sim.transaction.ordering :as ordering]))

;; ── Types ──────────────────────────────────────────────────────────────

(deftype AuthoritativeStore
         [genesis
          authority-context
          ^clojure.lang.Atom checkpoint-atom]
  Object
  (toString [_]
    (str "#<AuthoritativeStore family-id="
         (:family/id genesis) ">")))

;; ── Construction ────────────────────────────────────────────────────────

(defn new-authoritative-store
  "Create an in-memory authoritative resubmission chain store from a validated
   genesis, a verified genesis authorization, and the authority context they
   admit.

   The store is realized with:
   - an underlying ResubmissionChainStore (for admit-child and local-replay),
   - a checkpoint atom initialized to the genesis authoritative checkpoint C0.

   Args:
     genesis       — a structurally valid resubmission-chain-genesis.v1
     authz         — a verified resubmission-chain-genesis-authorization.v1
     ctx           — the admitted resubmission-disposition-authority-context.v1"
  [genesis authz ctx]
  (let [cfg (:configuration genesis)
        family-id (:family/id genesis)
        disp-k (:disposition-authority/public-key cfg)
        c0 (auth-ckpt/build-initial-checkpoint genesis authz ctx)
        initial-state (assoc (transition/empty-state family-id disp-k)
                             :chain/disposition-authority-context ctx)
        ;; One atom is the authoritative publication boundary. The underlying
        ;; store is retained only for non-authoritative compatibility APIs.
        checkpoint-atom (atom {:checkpoint c0
                               :entries {[:resubmission-family family-id]
                                         {:state initial-state :version 0}}})]
    (AuthoritativeStore. genesis ctx checkpoint-atom)))

(defn admit-authoritative-store
  "The public authoritative realization boundary. It verifies genesis
   authorization, derives the rooted authority context itself, and only then
   creates the initial checkpoint head."
  [genesis authz package-resolver governance-context]
  (let [verified (genesis-authz/verify-genesis-authorization
                  genesis authz package-resolver governance-context)]
    (when-not (:valid? verified)
      (throw (ex-info "genesis authorization verification failed"
                      {:type :authorization/failed :errors (:errors verified)})))
    (new-authoritative-store genesis authz
                             (:authorized-disposition-context verified))))

;; ── Accessors ───────────────────────────────────────────────────────────

(defn current-checkpoint
  "The current authoritative checkpoint map."
  [store]
  (:checkpoint @(.checkpoint-atom store)))

(defn current-checkpoint-root
  "The current authoritative checkpoint root, or nil."
  [store]
  (:checkpoint/root (current-checkpoint store)))

(defn authority-context-of
  "The admitted authority context carried by the store."
  [store]
  (.authority-context store))

(defn genesis-of
  "The canonical genesis artifact declared on the store."
  [store]
  (.genesis store))

;; ── Authoritative admission ─────────────────────────────────────────────

(defn apply-authoritative-disposition!
  "Apply a v2 resubmission-authoritative-disposition.v2 to the authoritative
   store under checkpoint CAS fencing.

   The disposition must carry a :attempt-disposition/parent-checkpoint-root
   that matches the store's current checkpoint root. If it matches, the
   pure transition runs; on commit a new checkpoint is built as a successor
   of the current one and both the chain state and checkpoint are updated
   atomically via compare-and-set!.

   Returns {:mode :authoritative ...} with the same shape as the pure
   transition result, plus :checkpoint-root for committed results."
  [store input]
  (let [family-id (:family/id (.genesis store))
        conflict-key [:resubmission-family family-id]
        expected-ckpt (:attempt-disposition/parent-checkpoint-root
                       (:disposition-artifact input))
        expected-seq (:attempt-disposition/sequence input)]
    (loop []
      (let [current @(.checkpoint-atom store)
            entry (get-in current [:entries conflict-key])
            {:keys [state version]} entry
            cur-checkpoint (:checkpoint current)
            cur-ckpt-root (:checkpoint/root cur-checkpoint)]
        (cond
          ;; An authoritative operation always names the exact accepted head.
          ;; Omitting it must never weaken the checkpoint CAS fence.
          (nil? expected-ckpt)
          {:mode :authoritative
           :status :rejected
           :reason :missing-checkpoint-root
           :observed-checkpoint-root cur-ckpt-root}

          ;; Checkpoint CAS fence.
          (not= expected-ckpt cur-ckpt-root)
          {:mode :authoritative
           :status :contention
           :reason :checkpoint-root-mismatch
           :observed-checkpoint-root cur-ckpt-root
           :expected-checkpoint-root expected-ckpt}

          ;; Chain version CAS fence.
          (and (some? expected-seq)
               (not= expected-seq version))
          {:mode :authoritative
           :status :contention
           :reason :version-mismatch
           :observed-version version
           :expected-version expected-seq}

          :else
          (let [result (transition/apply-action state
                                                {:transaction/action
                                                 :prf.resubmission/apply-authoritative-disposition
                                                 :transaction/input
                                                 (assoc input
                                                        :expected-checkpoint-root cur-ckpt-root
                                                        :expected-chain-version version)})]
            (if-not (= :committed (:status result))
              (assoc result :mode :authoritative)
              (let [state-after-root (transition/state-root (:state result))
                    effects-root (transition/effects-root (:effects result))
                    ordering
                    (ordering/transaction-ordering
                     (merge (:ordering-input result)
                            {:transaction/commit-index
                             (:transaction/commit-index (:state result))
                             :transaction/previous-transaction-hash
                             (:transaction/last-hash state)
                             :transaction/state-before-root
                             (transition/state-root state)
                             :transaction/state-after-root state-after-root
                             :transaction/effects-root effects-root}))
                    final-state (assoc (:state result)
                                       :transaction/last-hash
                                       (:transaction-ordering/hash ordering))
                    new-checkpoint
                    (auth-ckpt/build-successor-checkpoint
                     cur-checkpoint
                     state-after-root)
                    new-current (assoc current
                                       :checkpoint new-checkpoint
                                       :entries (assoc (:entries current) conflict-key
                                                       {:state final-state
                                                        :version (inc version)}))]
                (if (compare-and-set! (.checkpoint-atom store) current new-current)
                  (assoc result
                         :mode :authoritative
                         :transaction-ordering ordering
                         :checkpoint-root (:checkpoint/root new-checkpoint))
                  (recur))))))))))

;; ── Local replay admission ──────────────────────────────────────────────

(defn apply-local-replay-disposition!
  "Local replay is intentionally unavailable through an AuthoritativeStore.
   Callers must use a distinct local/replay store, so a checkpointed chain can
   never be mutated without advancing its authoritative checkpoint head."
  [_store _input]
  {:mode :local-replay
   :status :rejected
   :reason :local-replay-forbidden-on-authoritative-store})

;; ── Store accessors ─────────────────────────────────────────────────────

(defn state-of
  "The authoritative state paired atomically with the accepted checkpoint."
  [store]
  (get-in @(.checkpoint-atom store)
          [:entries [:resubmission-family (:family/id (.genesis store))] :state]))

(defn chain-head [store] (:chain/head (state-of store)))

(defn chain-version [store]
  (get-in @(.checkpoint-atom store)
          [:entries [:resubmission-family (:family/id (.genesis store))] :version]))

(defn family-id-of
  "The family-id served by this store."
  [store]
  (:family/id (.genesis store)))

(defn is-authoritative?
  "True for authoritative stores (always true for this type)."
  [_store]
  true)
