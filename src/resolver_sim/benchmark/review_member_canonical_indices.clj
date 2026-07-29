(ns resolver-sim.benchmark.review-member-canonical-indices
  "review-member-canonical-indices.v1

   Content-addressed artifact that binds the members of one keyed review
   round to a deterministic canonical ordering by :review-member/key.

   The review round remains authoritative; this artifact is a deterministic
   projection and witness derived from it.  Downstream certificates,
   signatures, position vectors, dissent vectors, and comparisons can
   independently verify member positions against this canonical ordering.

   For unkeyed legacy review rounds, construction is not applicable —
   callers that support legacy rounds must check applicability before
   calling the builder."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.benchmark.review-round :as rr]))

(def ^:const schema-version "review-member-canonical-indices.v1")

;; ── Predicates ────────────────────────────────────────────────────────────

(defn applicable-round?
  "True when the review round is keyed and suitable for canonical indexing."
  [round]
  (rr/round-uses-member-keys? round))

;; ── Builder ───────────────────────────────────────────────────────────────

(defn- build-canonical-entries
  "Derive canonical-ordered entries from a keyed review round.
   Sorts by :review-member/key and assigns :review-member/index
   from the position in the sorted vector.

   Returns a vector of {:review-member/id <kw>
                        :review-member/key <int>
                        :review-member/index <int>}."
  [round]
  (let [members (rr/round-members round)
        sorted (vec (sort-by :review-member/key members))]
    (mapv (fn [idx m]
            {:review-member/id (:researcher/id m)
             :review-member/key (:review-member/key m)
             :review-member/index idx})
          (range) sorted)))

(defn build-canonical-indices
  "Build a review-member-canonical-indices.v1 artifact.

   Required:
     round — an authoritative review round with keyed members.

   Returns the complete artifact with :review-member-canonical-indices/hash
   computed.

   Throws on unkeyed legacy rounds or invalid input."
  [round]
  (when-not (rr/round-uses-member-keys? round)
    (throw (ex-info "Canonical indices require a keyed review round"
                    {:review-round/id (:review-round/id round)})))
  (let [entries (build-canonical-entries round)
        count (count entries)
        preimage {:schema/version schema-version
                  :review-round/id (:review-round/id round)
                  :review-round/hash (:review-round/hash round)
                  :review-member/count count
                  :review-member/canonical-indices entries}
        artifact (dissoc preimage :review-member-canonical-indices/hash)
        chash (str "sha256:" (hc/domain-hash :review-member-canonical-indices artifact))]
    (assoc artifact :review-member-canonical-indices/hash chash)))

;; ── Structural validator ──────────────────────────────────────────────────

(defn validate-canonical-indices
  "Validate a review-member-canonical-indices.v1 artifact in isolation.

   Checks schema version, required fields, member count consistency,
   valid identities, integer keys and indices, uniqueness, dense
   indices, ordering, and hash integrity.

   Returns {:valid? bool :errors [string]}."
  [artifact]
  (let [errors (atom [])]
    (when-not (= schema-version (:schema/version artifact))
      (swap! errors conj (str "expected schema-version " schema-version
                              " got " (:schema/version artifact))))
    (when-not (some? (:review-round/id artifact))
      (swap! errors conj "missing :review-round/id"))
    (when-not (some? (:review-round/hash artifact))
      (swap! errors conj "missing :review-round/hash"))
    (let [c (:review-member/count artifact)]
      (when-not (and (integer? c) (not (neg? c)))
        (swap! errors conj (str "invalid or missing :review-member/count: " c))))
    (let [entries (:review-member/canonical-indices artifact [])
          declared-count (:review-member/count artifact)]
      (when-not (= (count entries) declared-count)
        (swap! errors conj (str "declared count " declared-count
                                " does not match entry count " (count entries))))
      (doseq [e entries]
        (when-not (qualified-keyword? (:review-member/id e))
          (swap! errors conj (str "invalid member id: " (:review-member/id e))))
        (let [k (:review-member/key e)]
          (when-not (and (integer? k) (not (neg? k)))
            (swap! errors conj (str "invalid key: " k))))
        (let [idx (:review-member/index e)]
          (when-not (and (integer? idx) (not (neg? idx)))
            (swap! errors conj (str "invalid index: " idx)))))
      (let [ids (map :review-member/id entries)]
        (when-not (= (count ids) (count (set ids)))
          (swap! errors conj "duplicate member ids")))
      (let [ks (map :review-member/key entries)]
        (when-not (= (count ks) (count (set ks)))
          (swap! errors conj "duplicate member keys")))
      (let [idxs (map :review-member/index entries)]
        (when-not (= (count idxs) (count (set idxs)))
          (swap! errors conj "duplicate member indices")))
      (let [idxs (sort (map :review-member/index entries))]
        (when-not (= idxs (range (count idxs)))
          (swap! errors conj (str "non-dense indices: " idxs))))
      (when (seq entries)
        (when (not= (map :review-member/index entries) (range (count entries)))
          (swap! errors conj "entries are not in canonical order by index"))))
    (let [declared-hash (:review-member-canonical-indices/hash artifact)]
      (when declared-hash
        (let [preimage (dissoc artifact :review-member-canonical-indices/hash)
              computed (str "sha256:" (hc/domain-hash :review-member-canonical-indices preimage))]
          (when-not (= declared-hash computed)
            (swap! errors conj (str "hash mismatch: declared " declared-hash
                                    " computed " computed))))))
    {:valid? (empty? @errors) :errors @errors}))

;; ── Independent verifier ──────────────────────────────────────────────────

(defn verify-canonical-indices
  "Verify a canonical-indices artifact against its authoritative review round.

   Recomputes the expected canonical ordering from the review round
   and compares every member identity, key, and index.

   Returns {:status :valid | :round-mismatch | :ordering-mismatch
            :hash-mismatch | :not-applicable
            :checks [{:member/id <kw> :expected <map> :actual <map> :match? bool}]
            :errors [string]}."
  [artifact round]
  (let [errors (atom [])]
    (when-not (rr/round-uses-member-keys? round)
      (swap! errors conj "review round is not keyed"))
    (let [round-id (:review-round/id round)
          artifact-round-id (:review-round/id artifact)]
      (when-not (= round-id artifact-round-id)
        (swap! errors conj (str "review-round/id mismatch: artifact "
                                artifact-round-id " round " round-id))))
    (let [round-hash (hc/domain-hash :review-round-identity
                                     {:benchmark/content-root (:benchmark/content-root round)
                                       :members (if (rr/round-uses-member-keys? round)
                                                  (vec (sort-by :review-member/key (rr/round-members round)))
                                                  (vec (sort-by :researcher/id (rr/round-members round))))
                                      :membership-frozen-at (:review-round/membership-frozen-at round)
                                      :policy-root (:review-round/policy-root round)
                                      :purpose (:review-round/purpose round)})
          round-hash-str (str "sha256:" round-hash)
          artifact-hash (:review-round/hash artifact)]
      (when (and artifact-hash (not= round-hash-str artifact-hash))
        (swap! errors conj (str "review-round/hash mismatch: artifact "
                                artifact-hash " computed " round-hash-str))))
    (let [expected-entries (build-canonical-entries round)
          actual-entries (:review-member/canonical-indices artifact [])
          checks (mapv (fn [e a]
                         {:member/id (:review-member/id e)
                          :expected e :actual a
                          :match? (= e a)})
                       expected-entries actual-entries)]
      (when (some (complement :match?) checks)
        (swap! errors conj "ordering mismatch — at least one entry differs from expected"))
      (let [declared-hash (:review-member-canonical-indices/hash artifact)]
        (when declared-hash
          (let [preimage (dissoc artifact :review-member-canonical-indices/hash)
                computed (str "sha256:" (hc/domain-hash :review-member-canonical-indices preimage))]
            (when-not (= declared-hash computed)
              (swap! errors conj (str "artifact hash mismatch: declared "
                                      declared-hash " computed " computed))))))
      (let [status (cond
                     (some #(re-find #"review round is not keyed" %) @errors) :not-applicable
                     (some #(re-find #"review-round/id mismatch" %) @errors) :round-mismatch
                     (some #(re-find #"ordering mismatch" %) @errors) :ordering-mismatch
                     (some #(re-find #"artifact hash mismatch" %) @errors) :hash-mismatch
                     (seq @errors) :invalid
                     :else :valid)]
        {:status status
         :checks checks
         :errors @errors}))))

;; ── Lookup operations ─────────────────────────────────────────────────────

(defn review-member-index
  "Return the canonical index for a given researcher-id, or nil.

   Returns nil when the artifact is malformed (ambiguous or empty)."
  [artifact researcher-id]
  (let [entries (:review-member/canonical-indices artifact)]
    (when (seq entries)
      (:review-member/index
       (first (filter #(= (:review-member/id %) researcher-id) entries))))))

(defn review-member-at-index
  "Return the member entry for a given index, or nil.

   Returns nil when the index is out of range or the artifact is empty."
  [artifact index]
  (let [entries (:review-member/canonical-indices artifact)]
    (first (filter #(= (:review-member/index %) index) entries))))

;; ── Hash reference helper (shared, no custom validator needed) ────────────
