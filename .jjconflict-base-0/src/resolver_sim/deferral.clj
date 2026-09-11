(ns resolver-sim.deferral
  "deferral.v1 — first-class hashed deferral state snapshot.

   A deferral is an independently meaningful obligation object: a stable logical
   id plus separately hashed state snapshots, committed with a grounded amount,
   eligibility, lineage, and lifecycle status. This namespace formalizes the
   deferred position already committed by the yield accounting layer
   (see :deferred-position in resolver-sim.yield.modules.liquid-lending and
   validate-deferred-position-schema in resolver-sim.yield.pro-rata-propagation-policy)
   into a reusable, content-addressed artifact.

   Model: stable :deferral/id plus immutable snapshots. The snapshot hash is the
   state identity; the logical id persists across successor snapshots. This is a
   deliberate choice over a mutable lifecycle object whose hash would be
   meaningless as identity.

   This namespace does NOT own consumer policy: eligibility rules, recovery,
   accounting reconciliation, claims, or attestations remain with each consumer.
   It is framework-neutral and depends only on the hash infrastructure."
  (:require [resolver-sim.hash.algorithm :as halgo]
            [resolver-sim.hash.canonical :as hash]
            [resolver-sim.grounded-amount :as ga]))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(def ^:const deferral-schema-version
  "deferral.v1")

(def ^:const deferral-hash-domain
  :deferral-v1)

;; ---------------------------------------------------------------------------
;; Keys
;; ---------------------------------------------------------------------------

(def schema-version-key :deferral/schema-version)
(def id-key :deferral/id)
(def root-obligation-root-key :deferral/root-obligation-root)
(def source-position-root-key :deferral/source-position-root)
(def amount-key :deferral/amount)
(def eligibility-key :deferral/eligibility)
(def round-key :deferral/round)
(def original-priority-key :deferral/original-priority)
(def status-key :deferral/status)
(def lineage-root-key :deferral/lineage-root)
(def predecessor-hash-key :deferral/predecessor-hash)
(def successor-id-key :deferral/successor-id)
(def closed-from-amount-key :deferral/closed-from-amount)
(def hash-algorithm-key :deferral/hash-algorithm)
(def hash-key :deferral/hash)

;; ---------------------------------------------------------------------------
;; Grounded amount projection contract
;; ---------------------------------------------------------------------------

(defn grounded-amount
  "Backward-compatible re-export of the shared grounded-amount projection
   contract (resolver-sim.grounded-amount). Prefer requiring
   resolver-sim.grounded-amount directly for new call sites."
  [& args]
  (apply ga/grounded-amount args))

;; ---------------------------------------------------------------------------
;; Hash
;; ---------------------------------------------------------------------------

(defn deferral-hash
  "Canonical content hash of a deferral.v1 snapshot. Hashes the snapshot minus
   :deferral/hash (the self-reference), committing schema version, grounded
   amount, eligibility, round/priority, status, lineage, and hash algorithm.
   Rejects unsupported hash algorithms rather than silently falling back to
   SHA-256."
  ([snapshot]
   (deferral-hash snapshot (get snapshot hash-algorithm-key halgo/default-hash-algorithm)))
  ([snapshot hash-algorithm]
   (let [algo (halgo/validate-hash-algorithm! hash-algorithm)]
     (hash/domain-hash deferral-hash-domain
                       (assoc (dissoc snapshot hash-key) hash-algorithm-key algo)))))

;; ---------------------------------------------------------------------------
;; Builder
;; ---------------------------------------------------------------------------

(defn deferral
  "Construct a deferral.v1 snapshot from `fields`, filling :deferral/schema-version,
   the default hash algorithm, a default :active status, and the committed
   :deferral/hash. `:deferral/id` is the stable logical id; the snapshot hash is
   the state identity. Pass a grounded amount (resolver-sim.grounded-amount/grounded-amount)
   as :deferral/amount."
  [fields]
  (let [snapshot (assoc fields
                        schema-version-key deferral-schema-version
                        hash-algorithm-key halgo/default-hash-algorithm
                        status-key (or (get fields status-key) :active))]
    (assoc snapshot hash-key (deferral-hash snapshot))))

;; ---------------------------------------------------------------------------
;; Lifecycle snapshot chain (pure, immutable)
;; ---------------------------------------------------------------------------

(defn deferral-close
  "Return `snapshot` closed: status :closed, :deferral/closed-from-amount records
   the prior grounded amount value, and the snapshot's committed amount value is
   zeroed (mirroring the yield deferred-position close semantics). The hash is
   recomputed over the new state. Pure."
  [snapshot]
  (let [prior-amount (get-in snapshot [amount-key :amount/value])
        closed (-> snapshot
                   (assoc status-key :closed
                          closed-from-amount-key prior-amount)
                   (update-in [amount-key :amount/value] (constantly 0)))]
    (assoc closed hash-key (deferral-hash closed))))

(defn deferral-successor
  "Create the next snapshot of the same logical deferral. Returns
   [closed-prior next-active]: the prior is closed (with successor-id linked and
   hash recomputed) and the next snapshot is created from `next-fields`, reusing
   the same :deferral/id, inheriting :deferral/lineage-root, and binding
   :deferral/predecessor-hash to the prior snapshot's hash. Pure."
  [prior next-fields]
  (let [next-active (deferral (assoc next-fields
                                     id-key (get prior id-key)
                                     lineage-root-key (get prior lineage-root-key)
                                     predecessor-hash-key (get prior hash-key)))
        closed (deferral-close (assoc prior successor-id-key (get next-active id-key)))]
    [closed next-active]))
