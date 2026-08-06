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
  (:require [resolver-sim.hash.canonical :as hc]))

(def ordering-schema "transaction-ordering.v1")
(def ordering-domain "prf.transaction-ordering.v1")

(defn unsigned-ordering-projection
  "Everything except the self ordering hash."
  [ordering]
  (dissoc ordering :transaction-ordering/hash))

(defn ordering-hash
  "Content-derived identity of a transaction-ordering record."
  [ordering]
  (str "sha256:" (hc/domain-hash ordering-domain (unsigned-ordering-projection ordering))))

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

   The first ordering must carry a nil :transaction/previous-transaction-hash.
   Returns {:valid? bool :errors [str]}."
  [orderings]
  (let [errors (atom [])
        vs (mapv (fn [o] {:ordering o :v (verify-ordering o)}) orderings)]
    (doseq [[i {:keys [ordering v]}] (map-indexed vector vs)]
      (when-not (:valid? v)
        (swap! errors conj (str "ordering[" i "] " (:detail v)))))
    (doseq [[i {:keys [ordering]}] (map-indexed vector vs)]
      (when (zero? i)
        (when (some? (:transaction/previous-transaction-hash ordering))
          (swap! errors conj (str "ordering[0] must carry nil "
                                  ":transaction/previous-transaction-hash"))))
      (when (pos? i)
        (let [prior (get vs (dec i))
              prior-ordering (:ordering prior)
              prior-valid? (:valid? (:v prior))]
          (when (and prior-valid? (:valid? (:v (get vs i))))
            (when-not (= (:transaction-ordering/hash prior-ordering)
                         (:transaction/previous-transaction-hash ordering))
              (swap! errors conj (str "ordering[" i "] previous-transaction-hash does not "
                                      "match prior ordering hash")))
            (when-not (= (:transaction/state-after-root prior-ordering)
                         (:transaction/state-before-root ordering))
              (swap! errors conj (str "ordering[" i "] state-before-root is not the "
                                      "prior-state fixed point of ordering[" (dec i)
                                      "] (prior state-after-root) ")))))))
    {:valid? (empty? @errors) :errors @errors}))
