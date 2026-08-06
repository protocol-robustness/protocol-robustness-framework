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
  (let [recomputed (ordering-hash ordering)]
    (if (= recomputed (:transaction-ordering/hash ordering))
      {:valid? true :reason :ok}
      {:valid? false :reason :ordering-hash-mismatch
       :detail (str "stored " (:transaction-ordering/hash ordering)
                    " recomputed " recomputed)})))
