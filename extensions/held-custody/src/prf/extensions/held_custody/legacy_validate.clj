(ns prf.extensions.held-custody.legacy-validate
  "Permanent historical-read verification for frozen force-auth-add-held
   artifacts (extension-owned, read-only).

   This is the machine-readable historical read contract declared in
   :extension/historical-read on the held-custody package manifest:

     current production:      held-custody mutation v1
     historical read support: force-auth-add-held v1, v2
                              force-auth-add-held-summary v1, v2
     historical production:   forbidden

   It contains NO producers and NO builders. Every function here either
   verifies an already-produced historical artifact or deterministically
   recomputes a summary from members for verification. The v2->v1 summary
   projection (:downgrade-add-held-summary-v2->v1) is PRIVATE to historical
   verification: it is a read projection used only by the v1 migration reader
   and the v1 recomputation; there is no path from its output into hashing or
   finalization as a newly authoritative v1 artifact, because the v1/v2
   summary producers do not exist in this namespace.

   These functions are faithful ports of the frozen historical verifiers
   (previously resolver-sim.evidence.force-authorisation). They call the same
   surviving core primitives with the same constant values, so the historical
   verification contract is unchanged — the verifier moved, the contract did
   not.

   BOUNDARY GUARD — This namespace MUST NOT import or depend on:
     - resolver-sim.protocols.sew
     - any form under protocols_src/
     - resolver-sim.evidence.force-authorisation (deleted legacy core domain)"
  (:require [clojure.string :as str]
            [resolver-sim.hash.canonical :as hash]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.evidence.artifact :as artifact]
            [resolver-sim.assurance.force-authorisation :as fa]))

(declare normalize-direction
         valid-held-direction?)

;; ── historical schema/verifier constants ──────────────────────────────────

(def add-held-schema-version
  "Canonical schema version for a force-auth-add-held evidence artifact (v1)."
  "force-auth-add-held.v1")

(def add-held-verifier-id
  "Canonical verifier identifier for a force-auth-add-held evidence artifact."
  "force-auth-add-held-verifier.v1")

(def add-held-v2-schema-version
  "Canonical schema version for a force-auth-add-held.v2 member that commits its
   canonical scope projection so the scope binding is independently re-derivable."
  "force-auth-add-held.v2")

(def add-held-v2-verifier-id
  "Canonical verifier identifier for a force-auth-add-held.v2 member."
  "force-auth-add-held-verifier.v2")

(def add-held-scope-derivation-id
  "Algorithm/version identifier for the :authorization/scope-hash commitment
   (:authorization/scope-derivation on v2 members)."
  "force-authorisation-scope-hash.v1")

(def add-held-kind
  "Canonical :artifact/kind for a force-authorised add-held custody mutation."
  :force-auth-add-held)

(def add-held-summary-schema-version
  "Canonical schema version for the force-auth-add-held-summary aggregate (v2)."
  "force-auth-add-held-summary.v2")

(def add-held-summary-v1-schema-version
  "Canonical schema version for the force-auth-add-held-summary aggregate (v1)."
  "force-auth-add-held-summary.v1")

(def add-held-summary-verifier-id
  "Canonical verifier identifier for the force-auth-add-held-summary aggregate."
  "force-auth-add-held-summary-verifier.v1")

(def add-held-summary-kind
  "Canonical :artifact/kind for the force-auth add-held summary."
  :force-auth-add-held-summary)

(def add-held-summary-v2-only-keys
  "Top-level keys introduced in v2 (absent from v1)."
  [:invalid-artifacts :unverified-authorization-ids :min-amount :max-amount
   :consumed-at-earliest :consumed-at-latest :distinct-tokens :distinct-accounts
   :distinct-owners :amount-by-token :amount-by-direction :amount-by-account
   :amount-by-owner :missing-amount-count :non-numeric-amount-count
   :negative-amount-count :amount-issues])

(def add-held-summary-v1-category-keys
  "The sub-categories catalogued in v1 (v2 added :by-owner, :by-position-id,
   :by-authorization-type)."
  [:by-account :by-reason :by-authorization :by-consumed-by :by-token-direction])

(def ^:private add-held-summary-v2-category-keys
  "Sub-category dimensions introduced in v2 (absent from v1)."
  [:by-owner :by-position-id :by-authorization-type])

(def ^:private add-held-summary-v1-body-keys
  "Exact set of top-level keys that a force-auth-add-held-summary.v1 body may
   carry (excluding the :artifact/hash / :artifact/preimage envelope)."
  #{:schema-version :artifact/kind :artifact/verifier
    :total :valid-count :invalid-count
    :scope-verified-count :scope-unverified-count
    :total-amount
    :distinct-adjustment-ids
    :by-token :by-direction
    :categories})

(def ^:private category-field
  "Mapping from summary category key to the member field it catalogues."
  {:by-account :held/account
   :by-reason :held/reason
   :by-authorization :authorization/id
   :by-consumed-by :held/consumed-by
   :by-owner :owner/address
   :by-position-id :held/position-id
   :by-authorization-type :authorization/type})

;; ── member validators ─────────────────────────────────────────────────────

(defn valid-force-auth-add-held?
  "Re-verify a force-auth-add-held evidence artifact (v1)."
  [report]
  (artifact/valid-artifact? report add-held-schema-version add-held-kind
                            add-held-verifier-id))

(defn force-auth-add-held-scope-verifies?
  "Derive whether a member's authorization-scope binding verifies. The result is
   NEVER read from a stored boolean that cannot be checked.

   - v2 members (:force-auth-add-held.v2): derived from the committed
     three-part scope commitment — the :authorization/scope-hash must equal
     hash(canonical :authorization/scope-projection) under the declared
     :authorization/scope-derivation algorithm. A v2 member cannot assert a
     verified binding that its committed projection does not authenticate.
   - v1 members (:force-auth-add-held.v1): the scope map is not committed in v1,
     so the flag cannot be re-derived from the member alone; the hash-committed
     :authorization/scope-verifies? boolean is used. This is a documented v1
     limitation; only v2 members give full independent re-derivability."
  [m]
  (if (= add-held-v2-schema-version (:schema-version m))
    (and (= add-held-scope-derivation-id (:authorization/scope-derivation m))
         (map? (:authorization/scope-projection m))
         (string? (:authorization/scope-hash m))
         (= (:authorization/scope-hash m)
            (fa/force-authorisation-scope-hash (:authorization/scope-projection m))))
    (true? (:authorization/scope-verifies? m))))

(defn valid-force-auth-add-held-v2?
  "Re-verify a force-auth-add-held.v2 member: full content round trip (exact
   canonical preimage + content hash), the supported scope-derivation id, the
   three-part scope commitment — :authorization/scope-hash must equal
   hash(canonical :authorization/scope-projection) — a supported held direction,
   a string :held/action, and consistency between the recorded mutation
   direction and the committed projection direction. Also rejects any member
   that stores an :authorization/scope-verifies? boolean, which must be derived."
  [report]
  (and (artifact/valid-artifact? report add-held-v2-schema-version add-held-kind
                                add-held-v2-verifier-id)
       (= add-held-scope-derivation-id (:authorization/scope-derivation report))
       (map? (:authorization/scope-projection report))
       (string? (:authorization/scope-hash report))
       (string? (:held/action report))
       (valid-held-direction? (:held/direction report))
       (not (contains? report :authorization/scope-verifies?))
       (= (normalize-direction (:held/direction report))
          (normalize-direction (get-in report [:authorization/scope-projection
                                               :held/direction])))
       (= (:authorization/scope-hash report)
          (fa/force-authorisation-scope-hash (:authorization/scope-projection report)))))

(defn exact-force-auth-add-held?
  "EXACT-kind predicate for the :force-auth-add-held member artifact: the
   exact supported schema version, artifact kind, verifier id, and full content
   round trip must all agree. Dispatches on the member schema version (v1 via
   valid-force-auth-add-held?, v2 via valid-force-auth-add-held-v2?). This is
   the predicate boundary-sensitive code uses so a lower layer can never
   masquerade as a member."
  [report]
  (cond
    (= add-held-schema-version (:schema-version report)) (valid-force-auth-add-held? report)
    (= add-held-v2-schema-version (:schema-version report)) (valid-force-auth-add-held-v2? report)
    :else false))

;; ── private direction/identity helpers ────────────────────────────────────

(defn- normalize-direction
  "Normalize a held-custody mutation direction to a keyword (:in / :out),
   accepting keyword or string spellings. Returns nil for nil input."
  [d]
  (when (some? d)
    (if (keyword? d) d (keyword (name d)))))

(defn- valid-held-direction?
  "True when a held-custody mutation direction is a supported add (:in) or
   sub (:out) direction."
  [d]
  (contains? #{:in :out} (normalize-direction d)))

(defn- content-hash-valid?
  "True when the artifact's full content round trip holds: the exact canonical
   preimage (pr-str of the body with the envelope removed) and a content hash
   re-derived from that same body. Independent of schema/kind/verifier identity
   checks."
  [report]
  (and (map? report)
       (artifact/preimage-and-hash-valid? report)))

(defn- member-family-version?
  "True when a schema-version string belongs to the force-auth-add-held member
   artifact family (a different version of the member), as opposed to an
   unrelated artifact or a summary version."
  [v]
  (and (string? v)
       (str/starts-with? v "force-auth-add-held.")
       (not (str/starts-with? v "force-auth-add-held-summary"))))

(defn- member-schema-supported?
  "True when a member schema version is a supported force-auth-add-held version."
  [v]
  (or (= add-held-schema-version v)
      (= add-held-v2-schema-version v)))

(defn- member-verifier-supported?
  "True when a member verifier id matches the schema version's verifier."
  [v]
  (or (= add-held-verifier-id v)
      (= add-held-v2-verifier-id v)))

(defn- canonical-member-valid?
  "Canonical exact-kind content verification for a member, dispatching on the
   member's schema version."
  [m]
  (cond
    (= add-held-schema-version (:schema-version m)) (valid-force-auth-add-held? m)
    (= add-held-v2-schema-version (:schema-version m)) (valid-force-auth-add-held-v2? m)
    :else false))

(defn- classify-member
  "Primary per-member classification. Returns nil when the member is a fully
   valid force-auth-add-held artifact (canonical content-addressed verification
   for its schema version, plus required identity fields and a verified
   authorization binding — derived for v2 members, committed for v1), else a
   stable reason keyword.

   Priority: not-a-map → kind → version → verifier → content hash → missing
   identity → authorization binding. Set-level conditions (duplicate members,
   duplicate ids, conflicting scope bindings) are applied in validate-member-set."
  [m]
  (cond
    (not (map? m)) :not-force-auth-add-held
    (not= add-held-kind (:artifact/kind m))
    (if (some? (:artifact/kind m))
      :artifact-kind-mismatch
      :not-force-auth-add-held)
    (not (member-schema-supported? (:schema-version m)))
    (if (member-family-version? (:schema-version m))
      :unsupported-member-version
      :schema-version-mismatch)
    (not (member-verifier-supported? (:artifact/verifier m)))
    :verifier-mismatch
    (not (canonical-member-valid? m))
    :content-hash-mismatch
    (nil? (:authorization/id m))
    :missing-authorization-id
    (nil? (:held/adjustment-id m))
    :missing-adjustment-id
    (not (valid-held-direction? (:held/direction m)))
    :invalid-direction
    (not (force-auth-add-held-scope-verifies? m))
    :authorization-binding-mismatch
    :else nil))

(defn- duplicate-indexes-by
  "Indexes of members (in the valid subset) whose key-extracted value repeats
   across the group. Returns a set of original member indexes (every member of a
   duplicated group)."
  [valid-indexed key-fn]
  (->> (group-by key-fn valid-indexed)
       (keep (fn [[v ms]]
               (when (and (some? v) (> (count ms) 1))
                 (map :index ms))))
       (mapcat identity)
       set))

(defn- validate-member-set
  "Classify every supplied member and apply the set-level integrity checks.

   Returns
     {:member-count n
      :valid-count   number of fully valid members
      :valid-indexed [{:index i :member m} ...]   fully valid members with original indexes
      :valid-members [m ...]
      :invalid       [{:index :adjustment-id :authorization-id :reason} ...] (index-ordered)
      :duplicate-member-indexes          set of original indexes
      :duplicate-adjustment-indexes      set of original indexes
      :duplicate-authorization-indexes   set of original indexes
      :warnings        [{:kind :duplicate-adjustment-id|:duplicate-authorization-id ...}]}

   Set-level rules:
     - identical duplicate members (:duplicate-member) always fail;
     - conflicting authorization bindings (same authorization id, different
       scope hashes) always fail (:authorization-binding-mismatch);
     - duplicate adjustment / authorization ids fail only when the corresponding
       :unique-*? option is set; otherwise they are reported as warnings."
   [members options]
   (let [members (vec (or members []))
         opts (or options {})
         unique-adj? (true? (:unique-adjustment-ids? opts))
         unique-auth? (true? (:unique-authorization-ids? opts))
         indexed (mapv (fn [i m] {:index i :member m}) (range) members)
         per-member (mapv (fn [{:keys [member]}] (classify-member member)) indexed)
         with-reason (mapv (fn [e r] (assoc e :reason r)) indexed per-member)
         with-reason (mapv (fn [e r] (assoc e :reason r)) indexed per-member)
        per-valid? (fn [{:keys [reason]}] (nil? reason))
        pass-a (filterv per-valid? with-reason)
        pass-a-indexed (mapv (fn [{:keys [index member]}] {:index index :member member}) pass-a)
        dup-member-idxs (duplicate-indexes-by pass-a-indexed (comp :artifact/hash :member))
        binding-conflict-idxs
        (->> (group-by (comp :authorization/id :member) pass-a-indexed)
             (keep (fn [[id ms]]
                     (when (and (some? id)
                                (> (count ms) 1)
                                (> (count (distinct (map (comp :authorization/scope-hash :member) ms))) 1))
                       (map :index ms))))
             (mapcat identity)
             set)
        dup-adj-idxs (duplicate-indexes-by pass-a-indexed (comp :held/adjustment-id :member))
        dup-auth-idxs (duplicate-indexes-by pass-a-indexed (comp :authorization/id :member))
        final-reason (fn [e]
                       (let [i (:index e)]
                         (cond
                           (contains? binding-conflict-idxs i) :authorization-binding-mismatch
                           (contains? dup-member-idxs i) :duplicate-member
                           (and unique-adj? (contains? dup-adj-idxs i)) :duplicate-adjustment-id
                           (and unique-auth? (contains? dup-auth-idxs i)) :duplicate-authorization-id
                           :else (:reason e))))
        final (mapv (fn [e] (assoc e :reason (final-reason e))) with-reason)
        valid-final (filterv (comp nil? :reason) final)
        invalid (into []
                      (keep (fn [e]
                              (when-let [r (:reason e)]
                                {:index (:index e)
                                 :adjustment-id (:held/adjustment-id (:member e))
                                 :authorization-id (:authorization/id (:member e))
                                 :reason r})))
                      final)
        adj-groups (->> (group-by (comp :held/adjustment-id :member) pass-a-indexed) vals)
        auth-id-groups (->> (group-by (comp :authorization/id :member) pass-a-indexed) vals)
        dup-adj-warn (when-not unique-adj?
                       (keep (fn [ms]
                               (let [v (:held/adjustment-id (:member (first ms)))]
                                 (when (and (some? v) (> (count ms) 1))
                                   {:kind :duplicate-adjustment-id
                                    :value v
                                    :indexes (mapv :index ms)})))
                             adj-groups))
        dup-auth-warn (when-not unique-auth?
                        (keep (fn [ms]
                                (let [v (:authorization/id (:member (first ms)))]
                                  (when (and (some? v) (> (count ms) 1))
                                    {:kind :duplicate-authorization-id
                                     :value v
                                     :indexes (mapv :index ms)})))
                              auth-id-groups))]
    {:member-count (count members)
     :valid-count (count valid-final)
     :valid-indexed (mapv (fn [{:keys [index member]}] {:index index :member member}) valid-final)
     :valid-members (mapv :member valid-final)
     :invalid (vec invalid)
     :duplicate-member-indexes dup-member-idxs
     :duplicate-adjustment-indexes dup-adj-idxs
     :duplicate-authorization-indexes dup-auth-idxs
     :warnings (vec (sort-by pr-str (concat dup-adj-warn dup-auth-warn)))}))

;; ── summary field derivation ──────────────────────────────────────────────

(defn- force-auth-add-held-summary-fields
  "SINGLE canonical summary-body derivation shared by the recomputation and the
   aggregate checker. Given the same member set it always produces the same
   semantic field set, so recomputation and reconciliation cannot diverge.

   Per-member classification (classify-member) drives :valid-count,
   :invalid-artifacts, and the financial/category projections: members that do
   not verify as force-auth-add-held artifacts (or that lack identity fields or
   a verified authorization binding) are never counted as valid, and their
   amounts never enter monetary totals. Triage views (:invalid-artifacts, scope
   counts, unverified authorization ids, amount issues) are computed over the
   COMPLETE supplied member set. Only numeric amounts contribute to monetary
   totals; a missing or non-numeric amount is never coerced to zero."
  [members]
  (let [artifacts (vec (or members []))
        total (count artifacts)
        reasons (mapv classify-member artifacts)
        per-valid? (fn [r] (nil? r))
        valid-count (count (filter per-valid? reasons))
        invalid-count (- total valid-count)
        invalid-artifacts (into []
                                (keep-indexed (fn [i a]
                                                (when-let [r (nth reasons i)]
                                                  {:index i
                                                   :adjustment-id (:held/adjustment-id a)
                                                   :authorization-id (:authorization/id a)
                                                   :reason r})))
                                artifacts)
        valid-members (into []
                            (keep-indexed (fn [i a]
                                            (when (per-valid? (nth reasons i)) a)))
                            artifacts)
        scope-verified (count (filter force-auth-add-held-scope-verifies? artifacts))
        unverified-auth-ids (vec (sort
                                  (distinct
                                   (keep (fn [a]
                                           (when-not (force-auth-add-held-scope-verifies? a)
                                             (:authorization/id a)))
                                         artifacts))))
        amount-issues (into []
                            (keep-indexed (fn [i a]
                                            (let [amt (:held/amount a)
                                                  issue (cond
                                                          (nil? amt) :missing-amount
                                                          (not (number? amt)) :non-numeric-amount
                                                          (neg? (double amt)) :negative-amount
                                                          :else nil)]
                                              (when issue
                                                {:index i
                                                 :adjustment-id (:held/adjustment-id a)
                                                 :amount-issue issue}))))
                            artifacts)
        issue-count (fn [issue-kind]
                      (count (filter #(= issue-kind (:amount-issue %)) amount-issues)))
        amounts (vec (keep (fn [a]
                             (let [amt (:held/amount a)]
                               (when (and (some? amt) (number? amt))
                                 (long amt))))
                           valid-members))
        total-amount (reduce + 0 amounts)
        min-amount (when (seq amounts) (apply min amounts))
        max-amount (when (seq amounts) (apply max amounts))
        consumed-ats (->> valid-members (keep :held/consumed-at) (map long) vec)
        consumed-at-earliest (when (seq consumed-ats) (apply min consumed-ats))
        consumed-at-latest (when (seq consumed-ats) (apply max consumed-ats))
        sorted-freq (fn [field] (into (sorted-map) (frequencies (keep field valid-members))))
        sum-by (fn [k]
                 (into (sorted-map)
                       (reduce (fn [m a]
                                 (let [v (get a k)]
                                   (if (some? v)
                                     (let [amt (:held/amount a)]
                                       (if (and (some? amt) (number? amt))
                                         (update m v (fnil + 0) (long amt))
                                         m))
                                     m)))
                               {}
                               valid-members)))
        by-token-direction (frequencies
                            (keep (fn [a]
                                    (when-let [t (:held/token a)]
                                      (when-let [d (:held/direction a)]
                                        [(keyword t) (keyword d)])))
                                  valid-members))
        categories (into {}
                         (map (fn [[cat-k field]] [cat-k (sorted-freq field)]))
                         category-field)
        categories (assoc categories :by-token-direction (into (sorted-map) by-token-direction))]
    {:total total
     :valid-count valid-count
     :invalid-count invalid-count
     :invalid-artifacts (vec invalid-artifacts)
     :scope-verified-count scope-verified
     :scope-unverified-count (- total scope-verified)
     :unverified-authorization-ids unverified-auth-ids
     :total-amount total-amount
     :min-amount min-amount
     :max-amount max-amount
     :missing-amount-count (issue-count :missing-amount)
     :non-numeric-amount-count (issue-count :non-numeric-amount)
     :negative-amount-count (issue-count :negative-amount)
     :amount-issues (vec amount-issues)
     :consumed-at-earliest consumed-at-earliest
     :consumed-at-latest consumed-at-latest
     :distinct-adjustment-ids (count (distinct (keep :held/adjustment-id valid-members)))
     :distinct-tokens (count (distinct (keep :held/token valid-members)))
     :distinct-accounts (count (distinct (keep :held/account valid-members)))
     :distinct-owners (count (distinct (keep :owner/address valid-members)))
     :by-token (into (sorted-map) (frequencies (keep :held/token valid-members)))
     :by-direction (into (sorted-map) (frequencies (keep :held/direction valid-members)))
     :amount-by-token (sum-by :held/token)
     :amount-by-direction (sum-by :held/direction)
     :amount-by-account (sum-by :held/account)
     :amount-by-owner (sum-by :owner/address)
     :categories categories}))

;; ── read-only v2->v1 projection ───────────────────────────────────────────

(defn- downgrade-add-held-summary-v2->v1
  "PRIVATE read projection of a v2 summary artifact body to the v1 shape (for
   migration verification). Discards the v2-only keys and v2-only category
   dimensions.

   Read-projection only: it returns a body without a committed content hash.
   It exists solely so the v1 migration reader can recompute the v1 hash of a
   persisted v1-labeled artifact that carries v2 keys, and so the :v1
   recomputation reproduces the v1 shape. There is no producer in this
   namespace that finalizes this projection into a newly authoritative v1
   artifact."
  [report]
  (let [v1-categories (select-keys (:categories report)
                                   add-held-summary-v1-category-keys)
        stripped (reduce dissoc
                         (reduce dissoc report add-held-summary-v2-only-keys)
                         artifact/artifact-envelope-keys)]
    (assoc stripped
           :schema-version add-held-summary-v1-schema-version
           :artifact/kind add-held-summary-kind
           :artifact/verifier add-held-summary-verifier-id
           :categories v1-categories)))

(defn- summary-body-v2
  "Canonical v2 summary body (without :artifact/hash / :artifact/preimage)
   derived from a member set via the shared derivation."
  [members]
  (let [f (force-auth-add-held-summary-fields members)]
    {:schema-version add-held-summary-schema-version
     :artifact/kind add-held-summary-kind
     :artifact/verifier add-held-summary-verifier-id
     :total (:total f)
     :valid-count (:valid-count f)
     :invalid-count (:invalid-count f)
     :invalid-artifacts (:invalid-artifacts f)
     :scope-verified-count (:scope-verified-count f)
     :scope-unverified-count (:scope-unverified-count f)
     :unverified-authorization-ids (:unverified-authorization-ids f)
     :total-amount (:total-amount f)
     :min-amount (:min-amount f)
     :max-amount (:max-amount f)
     :missing-amount-count (:missing-amount-count f)
     :non-numeric-amount-count (:non-numeric-amount-count f)
     :negative-amount-count (:negative-amount-count f)
     :amount-issues (:amount-issues f)
     :consumed-at-earliest (:consumed-at-earliest f)
     :consumed-at-latest (:consumed-at-latest f)
     :distinct-adjustment-ids (:distinct-adjustment-ids f)
     :distinct-tokens (:distinct-tokens f)
     :distinct-accounts (:distinct-accounts f)
     :distinct-owners (:distinct-owners f)
     :by-token (:by-token f)
     :by-direction (:by-direction f)
     :amount-by-token (:amount-by-token f)
     :amount-by-direction (:amount-by-direction f)
     :amount-by-account (:amount-by-account f)
     :amount-by-owner (:amount-by-owner f)
     :categories (:categories f)}))

(defn- summary-body-v1
  "Canonical v1 summary body (without :artifact/hash / :artifact/preimage)
   derived from a member set: the v2 body projected to the v1 shape."
  [members]
  (downgrade-add-held-summary-v2->v1 (summary-body-v2 members)))

;; ── summary shape / reconciliation helpers ────────────────────────────────

(defn- v1-summary-shape-valid?
  "Exact-shape check for a v1 summary body: no v2-only key, no unknown top-level
   key, and category keys within the v1 category set."
  [report]
  (and (map? report)
       (let [body (artifact/artifact-body report)
             v1-category-set (set add-held-summary-v1-category-keys)]
         (and (every? (fn [k] (not (contains? body k))) add-held-summary-v2-only-keys)
              (every? (fn [k] (contains? add-held-summary-v1-body-keys k)) (keys body))
              (let [cats (:categories body)]
                (and (map? cats)
                     (every? (fn [k] (contains? v1-category-set k)) (keys cats))))))))

(defn- v2-summary-shape-valid?
  "Exact-shape check for a v2 summary body: no unknown top-level key, and
   category keys within the v1 ∪ v2 category set."
  [report]
  (and (map? report)
       (let [body (artifact/artifact-body report)
             v2-body-keys (into add-held-summary-v1-body-keys add-held-summary-v2-only-keys)
             v2-category-set (set (into add-held-summary-v1-category-keys
                                        add-held-summary-v2-category-keys))]
         (and (every? (fn [k] (contains? v2-body-keys k)) (keys body))
              (let [cats (:categories body)]
                (and (map? cats)
                     (every? (fn [k] (contains? v2-category-set k)) (keys cats))))))))

(def ^:private v1-simple-paths
  "Derivable summary fields committed by the v1 shape."
  [[[:total] :total]
   [[:valid-count] :valid-count]
   [[:invalid-count] :invalid-count]
   [[:scope-verified-count] :scope-verified-count]
   [[:scope-unverified-count] :scope-unverified-count]
   [[:total-amount] :total-amount]
   [[:distinct-adjustment-ids] :distinct-adjustment-ids]
   [[:by-token] :by-token]
   [[:by-direction] :by-direction]])

(def ^:private v2-simple-paths
  "Derivable summary fields committed by the v2 shape (superset of v1).
   Includes the triage views (:invalid-artifacts, :unverified-authorization-ids,
   :amount-issues) so reconciliation never silently excludes a committed field."
  (into v1-simple-paths
        [[[:min-amount] :min-amount]
         [[:max-amount] :max-amount]
         [[:consumed-at-earliest] :consumed-at-earliest]
         [[:consumed-at-latest] :consumed-at-latest]
         [[:missing-amount-count] :missing-amount-count]
         [[:non-numeric-amount-count] :non-numeric-amount-count]
         [[:negative-amount-count] :negative-amount-count]
         [[:distinct-tokens] :distinct-tokens]
         [[:distinct-accounts] :distinct-accounts]
         [[:distinct-owners] :distinct-owners]
         [[:amount-by-token] :amount-by-token]
         [[:amount-by-direction] :amount-by-direction]
         [[:amount-by-account] :amount-by-account]
         [[:amount-by-owner] :amount-by-owner]
         [[:invalid-artifacts] :invalid-artifacts]
         [[:unverified-authorization-ids] :unverified-authorization-ids]
         [[:amount-issues] :amount-issues]]))

(defn- reconcile
  "Compare every derivable field against the canonical recomputation. Returns a
   vector of {:path [...] :expected <recomputed> :actual <stored>}. A missing
   stored field compares as nil, so an aggregate can never silently drop a
   derivable value."
  [summary fields simple-paths category-keys]
  (let [simple (mapcat (fn [[path k]]
                         (let [expected (get fields k)
                               actual (get-in summary path)]
                           (when-not (= expected actual)
                             [{:path path :expected expected :actual actual}])))
                       simple-paths)
        cats (mapcat (fn [k]
                       (let [expected (get-in fields [:categories k])
                             actual (get-in summary [:categories k])]
                         (when-not (= expected actual)
                           [{:path [:categories k] :expected expected :actual actual}])))
                     category-keys)]
    (vec (concat simple cats))))

(defn- compute-member-root
  "Deterministic informational root over the supplied member multiset. The v1
   and v2 summary shapes do NOT commit this root, so it cannot be verified
   against the artifact — it is surfaced for machine-readable diagnostics."
  [members]
  (hash/domain-hash :evidence-collection
                    (vec (sort-by (fn [m] (or (:artifact/hash m) "")) members))))

(defn- resolve-summary-version
  "Normalize the :summary-version option (:v1 / :v2 keywords or the schema
   version strings). Throws ex-info only for unsupported option values — this is
   a configuration error, not malformed artifact input."
  [options]
  (let [v (or (:summary-version options) :v1)]
    (cond
      (contains? #{:v1 :v2} v) v
      (= v add-held-summary-v1-schema-version) :v1
      (= v add-held-summary-schema-version) :v2
      :else (throw (ex-info "Unsupported summary version"
                            {:summary-version v
                             :supported #{:v1 :v2 add-held-summary-v1-schema-version
                                          add-held-summary-schema-version}})))))

;; ── summary verification (read/recompute only) ────────────────────────────

(defn recompute-force-auth-add-held-summary
  "Canonical recomputation of the force-auth-add-held-summary artifact from a
   member set. Pure projection: it reads ONLY members and options and never
   copies identity fields, totals, triage, or category values from any supplied
   summary.

   options:
     :summary-version  :v1 (default) or :v2

   Invalid members are excluded from financial totals but are represented in the
   :invalid-artifacts / :unverified-authorization-ids triage views exactly as
   the historical producers committed them. Returns a finalized content-
   addressed artifact in the requested shape. This is a verification/recompute
   helper, not a producer — it never constructs new historical production
   evidence; it reproduces the deterministic body a verifier must agree with."
  [members options]
  (let [options (or options {})
        version (resolve-summary-version options)
        members (vec (or members []))
        body (if (= version :v1)
               (summary-body-v1 members)
               (summary-body-v2 members))]
    (artifact/finalize-artifact body)))

(defn check-aggregate
  "Historical aggregate checker for force-auth-add-held-summary.v1 / .v2 only
   (extension-owned historical read support). New production uses
   prf.extensions.held-custody.aggregate/check-held-mutation-aggregate.

   Structured, deterministic, data-only aggregate check for a
   force-auth-add-held-summary artifact against the member artifacts it is
   claimed to aggregate over.

     (check-aggregate summary members options)

   summary  — a force-auth-add-held-summary artifact (v1 or v2 body).
   members  — a seq of force-auth-add-held member artifacts.
   options  — optional map:
     :summary-version            :v1 (default) or :v2
     :unique-adjustment-ids?     when true, duplicate held adjustment ids fail
                                 the aggregate (otherwise a warning)
     :unique-authorization-ids?  when true, duplicate authorization ids fail
                                 the aggregate (otherwise a warning)

   Returns
     {:valid? bool
      :status :valid | :invalid
      :aggregate-kind :force-auth-add-held-summary
      :schema-version \"...\"
      :member-count n :valid-member-count n :invalid-member-count n
      :checks {...}            one boolean gate per required check
      :invalid-members [...]   per-member failure classification
      :mismatches [...]        stored-vs-recomputed derivable field differences
      :warnings [...]          non-fatal conditions (duplicate ids, amount
                               integrity triage, mixed add/sub-held direction,
                               uncommitted member root)
      :member-root {...}}      informational (v1/v2 do not commit a member root)

   Every gate in :checks must be true for :valid? to be true. Ordinary malformed
   input (nil/non-map summary, nil/duplicated members) yields a non-passing
   result and never throws; only unsupported option values throw ex-info."
  [summary members options]
  (let [options (or options {})
        version (resolve-summary-version options)
        expected-schema-version (if (= version :v1)
                                  add-held-summary-v1-schema-version
                                  add-held-summary-schema-version)
        summary-map? (map? summary)
        kind-valid? (and summary-map? (= add-held-summary-kind (:artifact/kind summary)))
        schema-valid? (and summary-map? (= expected-schema-version (:schema-version summary)))
        verifier-valid? (and summary-map?
                             (= add-held-summary-verifier-id (:artifact/verifier summary)))
        hash-valid? (and summary-map? (content-hash-valid? summary))
        shape-valid? (and summary-map?
                          (if (= version :v1)
                            (v1-summary-shape-valid? summary)
                            (v2-summary-shape-valid? summary)))
        {:keys [member-count valid-count invalid
                duplicate-member-indexes duplicate-adjustment-indexes
                duplicate-authorization-indexes warnings]}
        (validate-member-set members options)
        invalid-count (- member-count valid-count)
        members-valid? (zero? invalid-count)
        fields (force-auth-add-held-summary-fields members)
        simple-paths (if (= version :v1) v1-simple-paths v2-simple-paths)
        category-keys (if (= version :v1)
                        add-held-summary-v1-category-keys
                        (into add-held-summary-v1-category-keys
                              add-held-summary-v2-category-keys))
        mismatches (if summary-map?
                     (reconcile summary fields simple-paths category-keys)
                     [{:path [] :expected expected-schema-version :actual summary}])
        summary-recomputes? (empty? mismatches)
        identities-unique? (and (empty? duplicate-member-indexes)
                                (or (not (:unique-adjustment-ids? options))
                                    (empty? duplicate-adjustment-indexes))
                                (or (not (:unique-authorization-ids? options))
                                    (empty? duplicate-authorization-indexes)))
        member-set-complete? (and summary-map?
                                  (= (:total summary) member-count)
                                  (= (:valid-count summary) valid-count)
                                  (= (:invalid-count summary) invalid-count)
                                  identities-unique?)
        checks {:aggregate-kind-valid? kind-valid?
                :aggregate-schema-valid? schema-valid?
                :aggregate-verifier-valid? verifier-valid?
                :aggregate-hash-valid? hash-valid?
                :aggregate-shape-valid? shape-valid?
                :members-valid? members-valid?
                :member-identities-unique? identities-unique?
                :member-set-complete? member-set-complete?
                :summary-recomputes? summary-recomputes?}
        amount-warnings (mapv (fn [{:keys [index adjustment-id amount-issue]}]
                                {:kind amount-issue
                                 :index index
                                 :adjustment-id adjustment-id})
                              (:amount-issues fields))
        direction-warnings
        (let [dirs (set (keep (fn [m]
                                (when (valid-held-direction? (:held/direction m))
                                  (normalize-direction (:held/direction m))))
                              members))]
          (when (and (contains? dirs :in) (contains? dirs :out))
            [{:kind :mixed-direction
              :detail (str "member set mixes :in (add-held) and :out (sub-held) flows; "
                           ":total-amount is gross flow — use :by-direction / "
                           ":amount-by-direction for net interpretation")}]))
        all-warnings (into []
                           (sort-by pr-str
                                    (concat warnings
                                            amount-warnings
                                            direction-warnings
                                            [{:kind :member-root-not-committed
                                              :detail (str expected-schema-version
                                                           " does not commit a member-set root; completeness is "
                                                           "reconciled by counts and per-member validation only.")}])))
        valid? (every? true? (vals checks))]
    {:valid? valid?
     :status (if valid? :valid :invalid)
     :aggregate-kind add-held-summary-kind
     :schema-version expected-schema-version
     :member-count member-count
     :valid-member-count valid-count
     :invalid-member-count invalid-count
     :checks checks
     :invalid-members invalid
     :mismatches mismatches
     :warnings all-warnings
     :member-root {:committed? false
                   :computed (compute-member-root members)}}))

;; ── public summary validators ─────────────────────────────────────────────

(defn valid-force-auth-add-held-summary?
  "Two arities.

   (valid-force-auth-add-held-summary? report) — content-addressed reader:
   re-verifies a force-auth-add-held-summary artifact (v2) by checking schema
   version, kind, verifier, exact canonical preimage, and content hash. This
   does NOT check aggregate membership or reconciliation.

   (valid-force-auth-add-held-summary? summary members options) — aggregate
   predicate: delegates to check-aggregate for the v2 target. True only when
   the summary is a well-formed v2 aggregate consistent with the member set."
  ([report]
   (artifact/valid-artifact? report add-held-summary-schema-version
                             add-held-summary-kind add-held-summary-verifier-id))
  ([summary members options]
   (:valid? (check-aggregate summary members
                             (assoc (or options {}) :summary-version :v2)))))

(defn valid-force-auth-add-held-summary-v1?
  "Two arities.

   (valid-force-auth-add-held-summary-v1? report) — EXACT v1 reader and the
   boundary gate for persisted v1 artifacts. Verifies schema version, kind,
   verifier, exact canonical preimage, content hash, AND the exact v1 shape
   (no v2-only key, no unknown key, v1 category keys only). A .v2 artifact — or
   a v1-labeled artifact carrying v2-only fields — can therefore never validate
   as v1. The projection-based migration reader is
   `valid-force-auth-add-held-summary-v1-migration?` and must be invoked
   explicitly for legacy migration.

   (valid-force-auth-add-held-summary-v1? summary members options) — aggregate
   predicate: delegates to check-aggregate for the v1 target."
  ([report]
   (and (map? report)
        (v1-summary-shape-valid? report)
        (artifact/valid-artifact? report add-held-summary-v1-schema-version
                                  add-held-summary-kind add-held-summary-verifier-id)))
  ([summary members options]
   (:valid? (check-aggregate summary members options))))

(defn valid-force-auth-add-held-summary-v1-migration?
  "MIGRATION reader for persisted v1 artifacts created through the projection
   path. Verifies schema version, kind, and verifier, then recomputes the v1
   content hash by projecting away the v2-only fields.

   This is intentionally PERMISSIVE: it accepts v2-only keys by projecting them
   away before hashing, so it is NOT an exact boundary gate. Boundary-sensitive
   code must use `valid-force-auth-add-held-summary-v1?` (exact) or
   `check-aggregate`. Invoke this function only when explicitly migrating
   legacy content that was stored with v2-only keys under a v1 label."
  [report]
  (and (map? report)
       (= add-held-summary-v1-schema-version (:schema-version report))
       (= add-held-summary-kind (:artifact/kind report))
       (= add-held-summary-verifier-id (:artifact/verifier report))
       (string? (:artifact/hash report))
       (string? (:artifact/preimage report))
       (let [v1-body (downgrade-add-held-summary-v2->v1 report)]
         (= (:artifact/hash report)
            (hash-ref/sha256-ref (hash/domain-hash :evidence-record v1-body))))))
