(ns resolver-sim.transaction.ordering
  "Generic transaction-ordering evidence (transaction-ordering.v1 and v2).

   This primitive is domain-neutral. It knows about action identity, scope,
   conflict keys, commit index, previous-transaction linkage, state roots,
   effects roots, and expected/observed snapshots — it knows nothing about
   resubmission parents, findings, or researcher policy.

   v2 adds a declared, chain-scoped change identity (transaction/
   change-identity) derived internally from the pre-change canonical operation,
   plus a committed transaction/input-root (the canonical command/intent root).
   change-identity is derived (never caller-arbitrary) and excludes chain
   position and the state-after root, so that state-after-root is stabilised as
   a deterministic function of the change request plus its application context:

       apply(change-identity, state-before-root) -> state-after-root

   Canonical contract:

       ordering-hash = \"sha256:\" + domain-hash(
           \"prf.transaction-ordering.<v>\",
           canonical-bytes-v2(unsigned-ordering-projection-v<v>))

   The unsigned projection excludes ONLY the self :transaction-ordering/hash.
   Every authoritative field — action, scope, conflict-key, commit-index,
   previous-transaction-hash, state-before/state-after roots, effects-root,
   expected, observed — is inside the identity hash. For v2, transaction/
   input-root and the derived transaction/change-identity are inside the v2
   identity hash as well.

   IMPORTANT (no hash cycle): the state-after-root is derived from the domain
   chain state projection which EXCLUDES :transaction/last-hash (the ordering
   hash itself). The transaction ordering therefore commits the chain-state
   transition; a signed attempt receipt commits the resulting ordering hash;
   the ordering never commits the receipt artifact. Neither transaction/
   input-root nor transaction/change-identity is part of chain state, so their
   inclusion cannot create a cycle in the state root."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

;; ── schema / domain constants ────────────────────────────────────────────────

(def ^:const ordering-schema "transaction-ordering.v1")
(def ^:const ordering-domain :prf-transaction-ordering-v1)

(def ^:const ordering-v2-schema "transaction-ordering.v2")
(def ^:const ordering-v2-domain :prf-transaction-ordering-v2)

(def ^:const change-identity-domain
  "Domain tag for the internally-derived, chain-scoped change identity."
  :prf-transaction-ordering-change-identity-v1)

(def ^:const change-identity-required-fields
  "Basis of transaction/change-identity. These describe the canonical
   pre-change operation (chain scope, action, and the committed command/input
   root). Deliberately excluded: state-before-root (application context),
   state-after-root, effects-root, the ordering self-hash, commit-index, and
   previous-transaction-hash (sequencing — verified by the ordering-chain rules
   but not part of the change identity)."
  [:transaction/scope
   :transaction/conflict-key
   :transaction/action
   :transaction/input-root])

(def ^:private authoritative-fields
  "Authoritative root-reference fields whose value must be a well-formed canonical
   sha256 reference. `:transaction/previous-transaction-hash` is included only
   when present (nil is valid — the chain origin)."
  [[:transaction/state-before-root :transaction/state-before-root]
   [:transaction/state-after-root :transaction/state-after-root]
   [:transaction/effects-root :transaction/effects-root]
   [:transaction/input-root :transaction/input-root]
   [:transaction/change-identity :transaction/change-identity]
   [:transaction/previous-transaction-hash :transaction/previous-transaction-hash]])

;; ── schema dispatch ───────────────────────────────────────────────────────────

(defn ordering-schema-of
  "Declared schema of an ordering record (defaults to v1 for bare v1 records)."
  [ordering]
  (or (:transaction-ordering/schema ordering) ordering-schema))

(defn v2?
  "True when `ordering` declares the transaction-ordering.v2 schema."
  [ordering]
  (= ordering-v2-schema (ordering-schema-of ordering)))

;; ── unsigned projections (versioned — v1 is never perturbed by v2 additions) ──

(defn unsigned-ordering-projection-v1
  "v1 unsigned projection: everything except the self ordering hash. v1 records
   never carry transaction/input-root or transaction/change-identity, so this is
   byte-identical to the former (dissoc :transaction-ordering/hash)."
  [ordering]
  (dissoc ordering :transaction-ordering/hash))

(defn unsigned-ordering-projection-v2
  "v2 unsigned projection: the v1 projection additionally commits
   transaction/input-root and the derived transaction/change-identity. Only the
   self ordering hash is excluded."
  [ordering]
  (dissoc ordering :transaction-ordering/hash))

(defn unsigned-ordering-projection
  "Schema-dispatched unsigned projection. v1 path is byte-identical to
   unsigned-ordering-projection-v1; v2 adds input-root/change-identity coverage."
  [ordering]
  (if (v2? ordering)
    (unsigned-ordering-projection-v2 ordering)
    (unsigned-ordering-projection-v1 ordering)))

;; ── hashing and change identity ───────────────────────────────────────────────

(defn- ordering-domain-for
  [ordering]
  (if (v2? ordering) ordering-v2-domain ordering-domain))

(defn ordering-hash
  "Content-derived identity of a transaction-ordering record. Schema-dispatched
   domain tag + projection keep the v1 hash byte-stable."
  [ordering]
  (hash-ref/sha256-ref
   (hc/domain-hash (ordering-domain-for ordering)
                   (unsigned-ordering-projection ordering))))

(defn change-identity-basis
  "Canonical pre-change basis map for a chain-scoped change request. Excludes
   state-before-root (application context), state-after-root, effects-root,
   commit-index, previous-transaction-hash, and the ordering self-hash."
  [ordering]
  (select-keys ordering change-identity-required-fields))

(defn change-identity-hash
  "Internally-derived, chain-scoped identity of a change request. It is a pure
   function of {scope, conflict-key, action, input-root}, never of chain
   position or of any state root, so the same requested change retains its
   identity across resequencing."
  [ordering]
  (hash-ref/sha256-ref
   (hc/domain-hash change-identity-domain (change-identity-basis ordering))))

;; ── construction ──────────────────────────────────────────────────────────────

(defn transaction-ordering
  "Build a transaction-ordering record from authoritative inputs and attach its
   self hash.

   inputs:
      :transaction-ordering/schema        \"transaction-ordering.v1\" | \"v2\" (defaults to v1)
      :transaction/action                 namespaced action keyword/string
      :transaction/scope                  scope keyword
      :transaction/conflict-key           vector
      :transaction/commit-index           int
      :transaction/previous-transaction-hash str|nil
      :transaction/state-before-root      str
      :transaction/state-after-root       str
      :transaction/effects-root           str
      :transaction/expected               map
      :transaction/observed               map
      :transaction/input-root             str  (v2 only, authoritative)

   For v2, :transaction/change-identity is DERIVED internally from the canonical
   basis (a stale value carried through a projection is overwritten, not rejected);
   independent rejection of a mismatched committed identity lives in
   verify-ordering (recompute + compare), mirroring the command/hash discipline."
  [inputs]
  (let [schema (ordering-schema-of inputs)
        base (merge {:transaction-ordering/schema schema} inputs)]
    (if (= schema ordering-v2-schema)
      ;; v2: change-identity is DERIVED internally (authoritative producer); a
      ;; stale value carried through a projection is overwritten, not rejected.
      ;; Independent rejection of a mismatched committed identity lives in
      ;; verify-ordering (recompute + compare).
      (let [v2 (assoc base :transaction/change-identity (change-identity-hash base))]
        (assoc v2 :transaction-ordering/hash (ordering-hash v2)))
      ;; v1: unchanged
      (assoc base :transaction-ordering/hash (ordering-hash base)))))

;; ── verification ───────────────────────────────────────────────────────────────

(defn- malformed-root-fields
  "Authoritative root-reference fields whose value is present but is not a
   well-formed canonical sha256 reference. `:transaction/previous-transaction-hash`
   is valid as nil (the chain origin). `:transaction/input-root` and
   `:transaction/change-identity` are v2-only but are simply skipped when absent
   on v1 records, so this check serves both schemas."
  [ordering]
  (into []
        (keep (fn [[field path]]
                (let [v (get ordering path)]
                  (when (and (some? v)
                             (not (hash-ref/valid-sha256-ref? v)))
                    field)))
              authoritative-fields)))

(defn verify-ordering
  "Recompute the ordering hash and compare. Returns {:valid? bool :reason kw
   :detail str}.

   Beyond self-hash recomputation this also rejects an ordering whose
   authoritative root references are not well-formed canonical sha256 refs
   (state-before/state-after/effects roots, input-root and change-identity on
   v2, and the previous-transaction-hash when present). A self-hash-consistent
   ordering that commits a malformed root is not valid.

   For v2, additionally recomputes transaction/change-identity from the declared
   basis and rejects `:change-identity-mismatch`."
  [ordering]
  (let [schema (ordering-schema-of ordering)
        supported #{ordering-schema ordering-v2-schema}
        required (if (= schema ordering-v2-schema)
                   (into [:transaction-ordering/schema
                          :transaction/action
                          :transaction/scope
                          :transaction/conflict-key
                          :transaction/commit-index
                          :transaction/state-before-root
                          :transaction/state-after-root
                          :transaction/effects-root]
                         [:transaction/input-root :transaction/change-identity])
                   [:transaction-ordering/schema
                    :transaction/action
                    :transaction/scope
                    :transaction/conflict-key
                    :transaction/commit-index
                    :transaction/state-before-root
                    :transaction/state-after-root
                    :transaction/effects-root])
        missing (remove #(some? (get ordering %)) required)
        bad-roots (malformed-root-fields ordering)]
    (cond
      (seq missing)
      {:valid? false :reason :missing-required-fields
       :detail (str "missing fields: " (pr-str missing))}

      (not (contains? supported schema))
      {:valid? false :reason :ordering-schema-mismatch
       :detail (str "expected schema " ordering-schema " or " ordering-v2-schema
                    " got " schema)}

      (seq bad-roots)
      {:valid? false :reason :malformed-root-reference
       :detail (str "malformed sha256 root reference(s): " (pr-str bad-roots))}

      (and (= schema ordering-v2-schema)
           (not= (:transaction/change-identity ordering)
                 (change-identity-hash ordering)))
      {:valid? false :reason :change-identity-mismatch
       :detail (str "change-identity mismatch: stored "
                    (:transaction/change-identity ordering)
                    " recomputed " (change-identity-hash ordering))}

      :else
      (let [recomputed (ordering-hash ordering)]
        (if (= recomputed (:transaction-ordering/hash ordering))
          {:valid? true :reason :ok}
          {:valid? false :reason :ordering-hash-mismatch
           :detail (str "stored " (:transaction-ordering/hash ordering)
                        " recomputed " recomputed)})))))

(defn verify-ordering-chain
  "Verify the prior-state fixed-point linkage across a chain of consecutive
   transaction orderings (ordered by commit). Each ordering must:

     - itself verify (self-hash recomputes and roots are well-formed, and v2
       recomputes its change-identity);
     - commit to the previous ordering hash via
       :transaction/previous-transaction-hash (nil for the first ordering);
     - commit to the previous ordering's resulting state via
       :transaction/state-before-root == prior :transaction/state-after-root
       (prior-state fixed point);
     - strictly increase :transaction/commit-index (monotonic commit order);
     - share the previous ordering's :transaction/conflict-key (single chain).

   v2 change-identity is verified per-record by verify-ordering; it is NOT a
   chain positional check — it identifies the requested change independent of its
   place in the chain. previous-transaction-hash, commit-index, and conflict-key
   remain the chain linkage rules and are checked separately.

   CHAIN ORIGIN — The first ordering's :transaction/state-before-root is the
   trusted origin.  When the caller supplies `origin-state-root` (the root of
   the true origin state, e.g. a domain's empty state), it is asserted that the
   first ordering's state-before-root equals it, anchoring the whole chain to a
   known origin.  Without it, chain verification proves internal continuity but
   not origin correctness.

   FAIL-CLOSED SKIP — When an ordering fails its self-hash, state-root
   continuity, commit-index monotonicity, conflict-key continuity, and
   previous-hash linkage against it are intentionally skipped: the chain is
   already known invalid and further checks add no safety.  The forensic
   consequence is that you cannot distinguish \"prior was tampered AND current
   state-root is mismatched\" from \"prior was tampered alone\".

   The first ordering must carry a nil :transaction/previous-transaction-hash.
   Returns {:valid? bool :errors [str]}."
  [orderings & [origin-state-root]]
  (let [vs (mapv (fn [o] {:ordering o :v (verify-ordering o)}) orderings)]
    (loop [i 0
           errors []]
      (if (>= i (count vs))
        {:valid? (empty? errors) :errors errors}
        (let [{:keys [ordering v]} (nth vs i)
              self-valid? (:valid? v)
              errors (if self-valid?
                       errors
                       (conj errors (str "ordering[" i "] " (:detail v))))
              errors (if (zero? i)
                       ;; chain origin: no prior to validate against
                       (cond-> errors
                         (some? (:transaction/previous-transaction-hash ordering))
                         (conj "ordering[0] must carry nil :transaction/previous-transaction-hash")
                         (and origin-state-root
                              (not= (:transaction/state-before-root ordering)
                                    origin-state-root))
                         (conj (str "ordering[0] state-before-root is not the "
                                    "chain origin state root")))
                       ;; subsequent ordering: check linkage when both sides are valid
                       (let [prior (nth vs (dec i))
                             prior-valid? (:valid? (:v prior))
                             prior-ordering (:ordering prior)]
                         (if (and prior-valid? self-valid?)
                           (cond-> errors
                             (not= (:transaction-ordering/hash prior-ordering)
                                   (:transaction/previous-transaction-hash ordering))
                             (conj (str "ordering[" i "] previous-transaction-hash does not "
                                        "match prior ordering hash"))
                             (not= (:transaction/state-after-root prior-ordering)
                                   (:transaction/state-before-root ordering))
                             (conj (str "ordering[" i "] state-before-root is not the "
                                        "prior-state fixed point of ordering[" (dec i)
                                        "] (prior state-after-root)"))
                             (not (> (:transaction/commit-index ordering)
                                     (:transaction/commit-index prior-ordering)))
                             (conj (str "ordering[" i "] commit-index does not strictly "
                                        "increase over ordering[" (dec i) "]"))
                             (not= (:transaction/conflict-key ordering)
                                   (:transaction/conflict-key prior-ordering))
                             (conj (str "ordering[" i "] conflict-key differs from "
                                        "ordering[" (dec i) "]")))
                           errors)))]
          (recur (inc i) errors))))))
