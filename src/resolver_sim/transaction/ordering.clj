(ns resolver-sim.transaction.ordering
  "Generic transaction-ordering evidence (transaction-ordering.v1).

   This primitive is domain-neutral. It knows about action identity, scope,
   conflict keys, commit index, previous-transaction linkage, state roots,
   effects roots, and expected/observed snapshots — it knows nothing about
   resubmission parents, findings, or researcher policy.

   Canonical contract:

     ordering-hash = \"sha256:\" + domain-hash(
         \"prf.transaction-ordering.v1\",
         canonical-bytes-v2(unsigned-ordering-projection))

   The unsigned projection excludes ONLY the self :transaction-ordering/hash.
   Every authoritative field — action, scope, conflict-key, commit-index,
   previous-transaction-hash, state-before/state-after roots, effects-root,
   expected, observed — is inside the identity hash.

   IMPORTANT (no hash cycle): the state-after-root is derived from the domain
   chain state projection which EXCLUDES :transaction/last-hash (the ordering
   hash itself). The transaction ordering therefore commits the chain-state
   transition; a signed attempt receipt commits the resulting ordering hash;
   the ordering never commits the receipt artifact."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def ordering-schema "transaction-ordering.v1")
(def ordering-domain "prf.transaction-ordering.v1")

(defn unsigned-ordering-projection
  "Everything except the self ordering hash."
  [ordering]
  (dissoc ordering :transaction-ordering/hash))

(defn ordering-hash
  "Content-derived identity of a transaction-ordering record."
  [ordering]
  (hash-ref/sha256-ref (hc/domain-hash ordering-domain (unsigned-ordering-projection ordering))))

(defn transaction-ordering
  "Build a transaction-ordering record from authoritative inputs and attach its
   self hash.

   inputs:
     :transaction/action              namespaced action keyword/string
     :transaction/scope               scope keyword
     :transaction/conflict-key        vector
     :transaction/commit-index        int
     :transaction/previous-transaction-hash str|nil
     :transaction/state-before-root   str
     :transaction/state-after-root    str
     :transaction/effects-root        str
     :transaction/expected            map
     :transaction/observed            map"
  [inputs]
  (let [ordering (merge {:transaction-ordering/schema ordering-schema} inputs)]
    (assoc ordering :transaction-ordering/hash (ordering-hash ordering))))

(defn- malformed-root-fields
  "Authoritative root-reference fields whose value is not a well-formed canonical
   sha256 reference. `:transaction/previous-transaction-hash` is included only
   when present (nil is valid — the chain origin)."
  [ordering]
  (into []
        (keep (fn [[k v]]
                (when (and (some? v)
                           (not (hash-ref/valid-sha256-ref? v)))
                  k))
              [[:transaction/state-before-root (:transaction/state-before-root ordering)]
               [:transaction/state-after-root (:transaction/state-after-root ordering)]
               [:transaction/effects-root (:transaction/effects-root ordering)]
               [:transaction/previous-transaction-hash (:transaction/previous-transaction-hash ordering)]])))

(defn verify-ordering
  "Recompute the ordering hash and compare. Returns {:valid? bool :reason kw
   :detail str}.

   Beyond self-hash recomputation this also rejects an ordering whose
   authoritative root references are not well-formed canonical sha256 refs
   (state-before/state-after/effects roots, and the previous-transaction-hash
   when present). A self-hash-consistent ordering that commits a malformed root
   is not valid."
  [ordering]
  (let [missing (remove #(some? (get ordering %))
                        [:transaction-ordering/schema
                         :transaction/action
                         :transaction/scope
                         :transaction/conflict-key
                         :transaction/commit-index
                         :transaction/state-before-root
                         :transaction/state-after-root
                         :transaction/effects-root])
        bad-roots (malformed-root-fields ordering)]
    (cond
      (seq missing)
      {:valid? false :reason :missing-required-fields
       :detail (str "missing fields: " (pr-str missing))}

      (not= ordering-schema (:transaction-ordering/schema ordering))
      {:valid? false :reason :ordering-schema-mismatch
       :detail (str "expected schema " ordering-schema
                    " got " (:transaction-ordering/schema ordering))}

      (seq bad-roots)
      {:valid? false :reason :malformed-root-reference
       :detail (str "malformed sha256 root reference(s): " (pr-str bad-roots))}

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

     - itself verify (self-hash recomputes and roots are well-formed);
     - commit to the previous ordering hash via
       :transaction/previous-transaction-hash (nil for the first ordering);
     - commit to the previous ordering's resulting state via
       :transaction/state-before-root == prior :transaction/state-after-root
       (prior-state fixed point);
     - strictly increase :transaction/commit-index (monotonic commit order);
     - share the previous ordering's :transaction/conflict-key (single chain).

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
