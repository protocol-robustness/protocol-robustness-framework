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

(defn verify-ordering
  "Recompute the ordering hash and compare. Returns {:valid? bool :reason kw
   :detail str}."
  [ordering]
  (let [missing (remove #(some? (get ordering %))
                        [:transaction-ordering/schema
                         :transaction/action
                         :transaction/scope
                         :transaction/conflict-key
                         :transaction/commit-index
                         :transaction/state-before-root
                         :transaction/state-after-root
                         :transaction/effects-root])]
    (cond
      (seq missing)
      {:valid? false :reason :missing-required-fields
       :detail (str "missing fields: " (pr-str missing))}

      (not= ordering-schema (:transaction-ordering/schema ordering))
      {:valid? false :reason :ordering-schema-mismatch
       :detail (str "expected schema " ordering-schema
                    " got " (:transaction-ordering/schema ordering))}

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

     - itself verify (self-hash recomputes);
     - commit to the previous ordering hash via
       :transaction/previous-transaction-hash (nil for the first ordering);
     - commit to the previous ordering's resulting state via
       :transaction/state-before-root == prior :transaction/state-after-root
       (prior-state fixed point).

   CHAIN ORIGIN — The first ordering's :transaction/state-before-root is not
   independently verifiable (there is no prior to check against).  Chain
   verification proves internal continuity, not origin correctness.

   FAIL-CLOSED SKIP — When an ordering fails its self-hash, state-root
   continuity and previous-hash linkage against it are intentionally skipped:
   the chain is already known invalid and further checks add no safety.
   The forensic consequence is that you cannot distinguish \"prior was tampered
   AND current state-root is mismatched\" from \"prior was tampered alone\".

   The first ordering must carry a nil :transaction/previous-transaction-hash.
   Returns {:valid? bool :errors [str]}."
  [orderings]
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
                       (if (some? (:transaction/previous-transaction-hash ordering))
                         (conj errors "ordering[0] must carry nil :transaction/previous-transaction-hash")
                         errors)
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
                                        "] (prior state-after-root)")))
                           errors)))]
          (recur (inc i) errors))))))
