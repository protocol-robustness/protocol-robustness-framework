(ns resolver-sim.benchmark.review-member-canonical-indices
  "review-member-canonical-indices.v1

   Canonical, content-addressed, deterministically derived zero-based member
   index artifact for EVERY review round (keyed or unkeyed).

   THE SEMANTIC MODEL — DERIVED CANONICAL INDICES
   ==============================================
   Member indices are derived deterministically from durable researcher
   identity.  The canonical ordering rule is:

     1. :researcher/id MUST be a string.
     2. Sort members by :researcher/id using the project's canonical string
        ordering: case-sensitive lexicographic (Unicode code-point order).
        This matches Clojure's `sort-by` with default comparator on strings.
     3. Assign :review-member/index from each member's position.

   Caller-supplied :review-member/key values (when present on a keyed round)
   are compatibility assertions only.  They MUST equal the derived
   :review-member/index or construction is rejected with machine-readable
   error :review-member-key-derived-index-mismatch.

   ARTIFACT PERSISTENCE
   ====================
   The builder returns the full artifact body.  Callers are responsible
   for persisting it (content-addressed store, package, etc.) before the
   hash commit in a downstream certificate is independently verifiable.

   PRODUCTION LIFECYCLE
   ====================
   review round → build-canonical-indices → persisted artifact body
   → certificate (commits artifact hash) → verify-canonical-indices
   (resolves artifact by hash, rederives expected entries, compares)."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.benchmark.review-round :as rr]))

(def ^:const schema-version "review-member-canonical-indices.v1")

;; ── Researcher-ID validation ──────────────────────────────────────────────

(defn- valid-researcher-id?
  "True when id is a non-empty string.
   The project's canonical researcher identifier type is string.
   Keywords and other types are rejected."
  [id]
  (and (string? id) (not= "" id)))

;; ── Indented indices-hash ─────────────────────────────────────────────────

(defn indices-hash
  "Compute the indented integer hash — a content hash of the
   :review-member/canonical-indices entries vector only.

   This is an \"indented\" (nested/subordinate) hash: it commits to the
   entries independently of the full artifact, so downstream verifiers
   can check index integrity without resolving the full artifact hash.

   Returns a \"sha256:...\" string, or nil for an empty entries vector."
  [entries]
  (when (seq entries)
    (str "sha256:" (hc/domain-hash :review-member-canonical-indices-entries entries))))

;; ── Builder ───────────────────────────────────────────────────────────────

(defn- build-canonical-entries
  "Derive canonical-ordered entries from a review round.

   Sorts by :researcher/id using canonical string ordering (case-sensitive
   Unicode code-point lexicographic).

   When a member carries a :review-member/key, verifies it equals the
   derived index.  Mismatch throws with
   :type :review-member-key-derived-index-mismatch.

   Returns a vector of {:researcher/id <string>
                        :review-member/index <int>}."
  [round]
  (let [members (rr/round-members round)]
    (doseq [m members]
      (when-not (string? (:researcher/id m))
        (throw (ex-info (str "Invalid :researcher/id type: " (pr-str (:researcher/id m))
                             " — must be a string")
                        {:type :invalid-researcher-id-type
                         :researcher/id (:researcher/id m)})))
      (when-not (valid-researcher-id? (:researcher/id m))
        (throw (ex-info (str "Invalid :researcher/id: " (pr-str (:researcher/id m))
                             " — must be a non-empty string")
                        {:type :invalid-researcher-id
                         :researcher/id (:researcher/id m)}))))
    (let [sorted (vec (sort-by :researcher/id members))]
      (mapv (fn [idx m]
              (let [kid (:review-member/key m)
                    id-val (:researcher/id m)]
                (when (and (some? kid) (not= kid idx))
                  (throw (ex-info (str ":review-member/key " kid " does not match "
                                       "derived :review-member/index " idx
                                       " for researcher " id-val)
                                  {:type :review-member-key-derived-index-mismatch
                                   :researcher/id id-val
                                   :supplied-key kid
                                   :derived-index idx})))
                {:researcher/id id-val
                 :review-member/index idx}))
            (range) sorted))))

(defn build-canonical-indices
  "Build a review-member-canonical-indices.v1 artifact.

   Required:
     round — an authoritative review round.

   The canonical ordering is determined by :researcher/id (string
   lexicographic), NOT by :review-member/key.  Supplied keys are
   verified against the derived index and rejected on mismatch.

   Works for both keyed and unkeyed rounds.  For unkeyed rounds,
   no key cross-check occurs (there are no keys).

   Returns the complete artifact with :review-member-canonical-indices/hash
   computed.

   Throws on invalid researcher IDs or key/index disagreement."
  [round]
  (let [entries (build-canonical-entries round)
        count (count entries)
        i-hash (indices-hash entries)
        preimage {:schema/version schema-version
                  :review-round/id (:review-round/id round)
                  :review-round/hash (:review-round/hash round)
                  :review-member/count count
                  :review-member/canonical-indices entries
                  :review-member/indices-hash i-hash}
        artifact (dissoc preimage :review-member-canonical-indices/hash)
        chash (str "sha256:" (hc/domain-hash :review-member-canonical-indices artifact))]
    (assoc artifact :review-member-canonical-indices/hash chash)))

;; ── Structural validator ──────────────────────────────────────────────────

(defn validate-canonical-indices
  "Validate a review-member-canonical-indices.v1 artifact in isolation.

   Checks schema version, required fields, member count consistency,
   string researcher IDs, integer indices, uniqueness, dense indices,
   index order, and hash integrity.

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
        (when-not (valid-researcher-id? (:researcher/id e))
          (swap! errors conj (str "invalid or missing :researcher/id: " (pr-str (:researcher/id e)))))
        (let [idx (:review-member/index e)]
          (when-not (and (integer? idx) (not (neg? idx)))
            (swap! errors conj (str "invalid index: " idx)))))
      (let [ids (map :researcher/id entries)]
        (when-not (= (count ids) (count (set ids)))
          (swap! errors conj "duplicate researcher ids")))
      (let [idxs (map :review-member/index entries)]
        (when-not (= (count idxs) (count (set idxs)))
          (swap! errors conj "duplicate member indices"))
        (let [sorted-idxs (sort idxs)]
          (when-not (= sorted-idxs (range (count sorted-idxs)))
            (swap! errors conj (str "non-dense indices: " sorted-idxs)))))
      (let [expected-order (map :review-member/index entries)]
        (when (seq entries)
          (when (not= expected-order (range (count entries)))
            (swap! errors conj "entries are not in canonical order by index")))))
    (let [declared-hash (:review-member-canonical-indices/hash artifact)]
      (when declared-hash
        (let [preimage (dissoc artifact :review-member-canonical-indices/hash)
              computed (str "sha256:" (hc/domain-hash :review-member-canonical-indices preimage))]
          (when-not (= declared-hash computed)
            (swap! errors conj (str "hash mismatch: declared " declared-hash
                                    " computed " computed))))))
    ;; Indented indices-hash integrity
    (let [declared-ihash (:review-member/indices-hash artifact)]
      (when declared-ihash
        (let [computed-ihash (indices-hash (:review-member/canonical-indices artifact))]
          (when (and computed-ihash (not= declared-ihash computed-ihash))
            (swap! errors conj (str "indices-hash mismatch: declared " declared-ihash
                                    " computed " computed-ihash))))))
    {:valid? (empty? @errors) :errors @errors}))

;; ── Independent verifier ──────────────────────────────────────────────────

(defn- verify-round-binding
  "Check review-round identity binding.  Returns errors vector."
  [artifact round errors]
  (let [round-id (:review-round/id round)
        artifact-round-id (:review-round/id artifact)]
    (when-not (= round-id artifact-round-id)
      (swap! errors conj (str "review-round/id mismatch: artifact "
                              artifact-round-id " round " round-id))))
  (let [round-hash (:review-round/hash round)
        artifact-hash (:review-round/hash artifact)]
    (when (and artifact-hash (not= round-hash artifact-hash))
      (swap! errors conj (str "review-round/hash mismatch: artifact "
                              artifact-hash " round " round-hash)))))

(defn- verify-artifact-hash
  "Check artifact self-hash integrity.  Returns errors vector."
  [artifact errors]
  (let [declared-hash (:review-member-canonical-indices/hash artifact)]
    (when declared-hash
      (let [preimage (dissoc artifact :review-member-canonical-indices/hash)
            computed (str "sha256:" (hc/domain-hash :review-member-canonical-indices preimage))]
        (when-not (= declared-hash computed)
          (swap! errors conj (str "artifact hash mismatch: declared "
                                  declared-hash " computed " computed)))))))

(defn- verify-indices-hash
  "Check indented indices-hash integrity.  Returns errors vector."
  [artifact errors]
  (let [declared-ihash (:review-member/indices-hash artifact)]
    (when declared-ihash
      (let [computed-ihash (indices-hash (:review-member/canonical-indices artifact))]
        (when (and computed-ihash (not= declared-ihash computed-ihash))
          (swap! errors conj (str "indices-hash mismatch: declared " declared-ihash
                                  " computed " computed-ihash)))))))

(defn- verify-derived-mapping
  "Rederive expected entries from the review round and compare against
   artifact entries.  Returns {:checks [...] :match? bool}."
  [artifact round]
  (let [expected-entries (build-canonical-entries round)
        actual-entries (:review-member/canonical-indices artifact [])]
    (if (not= (count expected-entries) (count actual-entries))
      {:checks [] :match? false
       :error (str "entry count mismatch: expected "
                   (count expected-entries) " got " (count actual-entries))}
      (let [checks (mapv (fn [e a]
                           {:researcher/id (:researcher/id e)
                            :expected e :actual a
                            :match? (= e a)})
                         expected-entries actual-entries)]
        {:checks checks
         :match? (every? :match? checks)
         :error (when (some (complement :match?) checks)
                  "derived mapping mismatch — at least one entry differs from expected")}))))

(defn verify-canonical-indices
  "Verify a canonical-indices artifact against its authoritative review round.

   Performs three independent checks:
     1. Source-round binding — artifact round ID and hash match the round.
     2. Derived mapping validity — rederives expected entries from the round
        and compares without trusting the artifact's own ordering.
     3. Artifact self-hash integrity — recomputes and compares the hash.

   Returns {:status :valid
            | :round-mismatch
            | :canonical-indices-derived-mapping-mismatch
            | :hash-mismatch
            :checks [{:researcher/id <id> :expected <map> :actual <map> :match? bool}]
            :errors [string]
            :round-binding-valid? bool
            :derived-mapping-valid? bool
            :artifact-hash-valid? bool | nil
            :indices-hash-valid? bool | nil}"
  [artifact round]
  (let [errors (atom [])]
    ;; 1. Source-round binding
    (verify-round-binding artifact round errors)
    (let [round-binding-valid? (empty? (filter #(re-find #"mismatch" %) @errors))]
      ;; 2. Indented indices-hash integrity (fast gate before full derivation)
      (verify-indices-hash artifact errors)
      (let [indices-hash-valid? (nil? (first (filter #(re-find #"indices-hash mismatch" %) @errors)))]
        ;; 3. Derived mapping validity (independent of trust in artifact ordering)
        (let [{:keys [checks match? error]} (verify-derived-mapping artifact round)]
          (when error (swap! errors conj error))
          (let [derived-mapping-valid? match?]
            ;; 4. Artifact self-hash integrity
            (verify-artifact-hash artifact errors)
            (let [artifact-hash-valid? (nil? (first (filter #(re-find #"artifact hash mismatch" %) @errors)))]
              (let [status (cond
                             (not round-binding-valid?) :round-mismatch
                             (not derived-mapping-valid?) :canonical-indices-derived-mapping-mismatch
                             (not artifact-hash-valid?) :hash-mismatch
                             (seq @errors) :invalid
                             :else :valid)]
                {:status status
                 :checks checks
                 :errors @errors
                 :round-binding-valid? round-binding-valid?
                 :derived-mapping-valid? derived-mapping-valid?
                 :artifact-hash-valid? artifact-hash-valid?
                 :indices-hash-valid? indices-hash-valid?}))))))))

;; ── Lookup operations — sole authoritative source for indices ─────────────

(defn derived-index
  "Return the canonical derived index for a given researcher-id, or nil.

   The name emphasises that the index is deterministically derived from
   durable researcher identity, not from caller-supplied integers.

   Returns nil when the member is not found or the artifact is empty."
  [artifact researcher-id]
  (let [entries (:review-member/canonical-indices artifact)]
    (when (seq entries)
      (:review-member/index
       (first (filter #(= (:researcher/id %) researcher-id) entries))))))

(defn review-member-index
  "Return the canonical index for a given researcher-id, or nil.
   Alias for derived-index — both names are equivalent.

   Returns nil when the member is not found or the artifact is empty."
  [artifact researcher-id]
  (derived-index artifact researcher-id))

(defn review-member-at-index
  "Return the member entry for a given index, or nil.

   Returns nil when the index is out of range or the artifact is empty."
  [artifact index]
  (let [entries (:review-member/canonical-indices artifact)]
    (first (filter #(= (:review-member/index %) index) entries))))

;; ── Bit-width ───────────────────────────────────────────────────────────────

(defn member-bit-width
  "Minimum bits needed to represent any canonical index in the artifact.
   Returns nil for an empty artifact.
   For a 3-member artifact this returns 2."
  [artifact]
  (let [n (:review-member/count artifact 0)]
    (when (pos? n)
      (let [max-idx (dec n)]
        (if (<= max-idx 1)
          1
          (inc (int (Math/floor (/ (Math/log max-idx) (Math/log 2))))))))))


