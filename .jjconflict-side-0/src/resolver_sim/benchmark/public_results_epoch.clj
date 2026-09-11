(ns resolver-sim.benchmark.public-results-epoch
  "Cumulative public-results epoch over a dedicated public-result-admission head.

   SEMANTIC STATEMENT
     public-results-epoch E at sequence N
     =
     exactly every valid public-result admission
     committed by admission basis/head H through sequence N

   Completeness is RELATIVE to the referenced verified admission basis through
   N.  This artifact does NOT claim:
     * every valid report in existence was published;
     * the referenced head is globally canonical;
     * researcher identity is governed / authenticated.
   Those belong to later publication / canonical-head / key-governance layers.

   LAYERING
   * public-result-admission  — one signed report admitted (verified, conservative
                                authorship; never agreement-filtered).
   * public-result-admission-head (this namespace) — a typed, chained, append-only
     sequence of admission roots (sequence + predecessor + self-root), following
     the configuration-head / publication-head lineage pattern.  It is NOT a
     semantic reuse of an unrelated economic/configuration head: it is dedicated
     to public-result admissions, and it does not route admission data through
     transaction-ordering's transaction-specific fields.
   * public-results-epoch (this namespace) — a cumulative snapshot that references
     a head basis {:head/root ... :through-sequence N} and derives its member set
     from the basis.

   MEMBERSHIP DISCIPLINE
   The constructor derives :epoch/members by re-enumerating the head chain through
   N and re-verifying each public-result-admission.  A caller-supplied member set
   is never trusted; verification re-enumerates and requires exact equality.

   CUMULATIVE LINEAGE
   Epoch at N cumulatively extends the epoch at M < N: membership is monotonic
   (no removal), through-sequence never moves backward, and the predecessor root
   must resolve.  Monotonic membership is derived from verified basis lineage,
   never a caller-supplied subset assertion.

   SET SEMANTICS
   Members are canonically sorted by :report/root; duplicate report roots and
   duplicate admission roots are rejected; no outcome/agreement filtering —
   divergent reports remain valid members.

   PARTICIPATION CLASSES
   Each member carries :admission/participation (derived from the re-verified
   admission), and the epoch exposes :epoch/participation as three separate
   classes — :anonymous, :declared-unresolved, and :authenticated.  A future
   consumer must never read a declared presentation as identity evidence; the
   classes are deliberately not collapsed into a single named-researcher count.

   Identity vocabulary: :report/root and :admission/root in :epoch/members are
   exact projections of the admission's roots.  The only new identities are the
   epoch set/root commitments.  Domain tags are strings used directly via
   domain-hash; nothing is added to the shared domain-tags registry."
  (:require [resolver-sim.benchmark.public-result-admission :as pra]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def head-schema "public-result-admission-head.v1")
(def head-domain "public-result-admission-head.v1")
(def epoch-schema "public-results-epoch.v1")
(def epoch-domain "public-results-epoch.v1")
(def members-domain "public-results-epoch-members.v1")

(defn- fail! [reason data]
  (throw (ex-info (str "Invalid public-results epoch: " (name reason))
                  (assoc data :reason reason))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Public-result-admission head (typed, chained, append-only)
;; ─────────────────────────────────────────────────────────────────────────────

(defn head-root
  "The self commitment of a head node body."
  [head-body]
  (hash-ref/sha256-ref (hc/domain-hash head-domain head-body)))

(defn head-node
  "Build a head node carrying its self-root.
   :head/sequence 0 with no admission is the genesis node."
  [opts]
  (let [body {:schema-version head-schema
              :head/sequence (:head/sequence opts)
              :head/predecessor (:head/predecessor opts)
              :head/admission-root (:head/admission-root opts)}]
    (assoc body :head/root (head-root body))))

(defn genesis-head []
  (head-node {:head/sequence 0
              :head/predecessor nil
              :head/admission-root nil}))

(defn successor-head
  "Append one admission to a head; returns the next node at sequence N+1."
  [prev-head admission-root]
  (head-node {:head/sequence (inc (:head/sequence prev-head))
              :head/predecessor (:head/root prev-head)
              :head/admission-root admission-root}))

(defn verify-head-chain
  "Verify a head chain (vector of nodes n0..nN).  Checks sequence continuity,
   predecessor linking, self-root recomputation, and well-formed admission roots.
   Returns {:valid? bool :reason}."
  [chain]
  (loop [remaining (seq chain)
         prev-root nil
         expected-seq 0]
    (if-not remaining
      {:valid? true}
      (let [node (first remaining)
            nxt (next remaining)]
        (cond
          (not= expected-seq (:head/sequence node))
          {:valid? false :reason :sequence-gap}
          (not= prev-root (:head/predecessor node))
          {:valid? false :reason :predecessor-mismatch}
          (not= (:head/root node) (head-root (dissoc node :head/root)))
          {:valid? false :reason :head-root-mismatch}
          (and (some? (:head/admission-root node))
               (not (hash-ref/valid-sha256-ref? (:head/admission-root node))))
          {:valid? false :reason :malformed-admission-root}
          :else
          (recur nxt (:head/root node) (inc expected-seq)))))))

(defn head-admission-roots
  "The ordered admission roots admitted through sequence N (genesis nil skipped)."
  [chain through]
  (->> chain
       (take-while #(<= (:head/sequence %) through))
       (keep :head/admission-root)
       vec))

;; ─────────────────────────────────────────────────────────────────────────────
;; Public-results epoch (cumulative snapshot over the head)
;; ─────────────────────────────────────────────────────────────────────────────

(defn epoch-set-root
  "Canonical commitment of the epoch's canonical member vector."
  [members]
  (hash-ref/sha256-ref (hc/domain-hash members-domain members)))

(defn epoch-root
  "The self commitment of an epoch body (body excludes :epoch/root)."
  [epoch-body]
  (hash-ref/sha256-ref (hc/domain-hash epoch-domain epoch-body)))

(defn- verify-and-pair
  "Re-verify one admission (by its data) and require its committed root equals the
   admission root from the head.  Returns {:report/root :admission/root
   :admission/participation}."
  [admission-root data]
  (let [adm (pra/build (:report data) (:manifest data) (:public-key data))]
    (when-not (= admission-root (:admission/root adm))
      (fail! :admission-verification-mismatch
             {:expected admission-root :actual (:admission/root adm)}))
    {:report/root (:report/root adm)
     :admission/root (:admission/root adm)
     :admission/participation (:admission/participation adm)}))

(defn- participation-classification
  "Derive the participation classes exposed by an epoch from its members.
   Presentation is never collapsed into identity assurance: anonymous,
   declared-but-unresolved, and authenticated-researcher admissions are
   separate classes, so a display name can never be read as identity evidence."
  [members]
  (reduce (fn [acc m]
            (let [{:keys [presentation identity-assurance]}
                  (:admission/participation m)]
              (case presentation
                :anonymous (update acc :anonymous inc)
                :declared (if (= :authenticated identity-assurance)
                            (update acc :authenticated inc)
                            (update acc :declared-unresolved inc)))))
          {:anonymous 0 :declared-unresolved 0 :authenticated 0}
          members))

(defn- build-members
  "Derive canonical members from the ordered admission roots, re-verifying each
   admission against its data.  Rejects duplicate report and admission roots and
   canonical-sorts by :report/root."
  [admission-roots admissions]
  (let [members (mapv (fn [ar]
                        (when-not (contains? admissions ar)
                          (fail! :missing-admission {:admission-root ar}))
                        (verify-and-pair ar (get admissions ar)))
                      admission-roots)
        n (count members)]
    (when-not (= n (count (distinct (map :report/root members))))
      (fail! :duplicate-report-root {:members members}))
    (when-not (= n (count (distinct (map :admission/root members))))
      (fail! :duplicate-admission-root {:members members}))
    (vec (sort-by :report/root members))))

(defn build-epoch
  "Derive a cumulative public-results-epoch from a verified admission basis.

   basis       — {:head/root H :through-sequence N}
   chain       — the head chain n0..nM (M >= N) that commits H at sequence N
   admissions  — {admission-root {:report :manifest :public-key}} for every
                 admission in the chain through N

   Membership is re-enumerated from the chain and re-verified admission by
   admission; the caller cannot supply members.  Optionally pass :predecessor
   for the epoch root of the previous cumulative epoch."
  [{:keys [through-sequence] :as basis} chain admissions & {:keys [predecessor]}]
  (let [head-check (verify-head-chain chain)]
    (when-not (:valid? head-check)
      (fail! :invalid-admission-head {:detail head-check}))
    (let [basis-node (some #(when (= (:head/sequence %) through-sequence) %) chain)]
      (when-not (and basis-node (= (:head/root basis) (:head/root basis-node)))
        (fail! :wrong-admission-head
               {:basis-root (:head/root basis)
                :sequence through-sequence}))
      (let [admission-roots (head-admission-roots chain through-sequence)
            members (build-members admission-roots admissions)
            body {:schema-version epoch-schema
                  :epoch/sequence through-sequence
                  :epoch/predecessor predecessor
                  :epoch/admission-basis basis
                  :epoch/participation (participation-classification members)
                  :epoch/members members}]
        (assoc body
               :epoch/set-root (epoch-set-root members)
               :epoch/root (epoch-root body))))))

(defn verify-epoch
  "Re-enumerate the epoch's basis through the head chain, re-derive the epoch,
   and require exact equality with the supplied epoch.  Caller-supplied derived
   fields (members, set-root, root) are never trusted."
  [epoch chain admissions]
  (try
    (let [expected (build-epoch (:epoch/admission-basis epoch) chain admissions
                                :predecessor (:epoch/predecessor epoch))]
      {:valid? (= expected epoch)
       :reason (when-not (= expected epoch) :derived-epoch-mismatch)
       :expected expected})
    (catch clojure.lang.ExceptionInfo e
      {:valid? false :reason (:reason (ex-data e)) :errors (ex-data e)})
    (catch Exception e
      {:valid? false :reason :verification-error
       :errors {:message (.getMessage e)}})))

(defn verify-cumulative-lineage
  "Verify that `successor` (epoch at N) cumulatively extends `predecessor`
   (epoch at M, M < N).  Both are recomputed from their verified bases; membership
   is derived, never trusted.  Checks predecessor-root linking, sequence
   advancement, and monotonic membership (no removal)."
  [successor predecessor chain admissions]
  (try
    (let [succ (build-epoch (:epoch/admission-basis successor) chain admissions
                            :predecessor (:epoch/predecessor successor))
          pred (build-epoch (:epoch/admission-basis predecessor) chain admissions
                            :predecessor (:epoch/predecessor predecessor))
          succ-seq (:through-sequence (:epoch/admission-basis successor))
          pred-seq (:through-sequence (:epoch/admission-basis predecessor))
          pred-basis-root (get-in predecessor [:epoch/admission-basis :head/root])
          succ-chain-node (some #(when (= (:head/sequence %) pred-seq) %) chain)]
      (cond
        (not= (:epoch/predecessor successor) (:epoch/root predecessor))
        {:valid? false :reason :predecessor-link-mismatch}
        (>= pred-seq succ-seq)
        {:valid? false :reason :sequence-not-advancing}
        (or (nil? succ-chain-node)
            (not= pred-basis-root (:head/root succ-chain-node)))
        {:valid? false :reason :admission-basis-not-extension}
        (not (every? (set (map :report/root (:epoch/members succ)))
                     (map :report/root (:epoch/members pred))))
        {:valid? false :reason :member-removal}
        :else
        {:valid? true}))
    (catch clojure.lang.ExceptionInfo e
      {:valid? false :reason (:reason (ex-data e)) :errors (ex-data e)})
    (catch Exception _
      {:valid? false :reason :verification-error})))