(ns prf.extensions.held-custody.aggregate
  "Held-custody mutation summary and aggregate checker (extension).

   A protocol-neutral aggregate over force-auth-held-custody-mutation members.
   The headline fields are explicit gross inflow / gross outflow / gross flow /
   net change — there is NO ambiguous :total-amount. Mixed directions are a
   normal custody history and produce no warning. Members that are not
   intrinsically valid are excluded from flows, reported in :invalid-artifacts,
   and make the aggregate non-passing.

   This extension depends ONLY on approved public PRF core namespaces
   (resolver-sim.evidence.artifact, resolver-sim.hash.sequence,
   resolver-sim.sensitivity.sentinel) and the sibling mutation namespace.

   BOUNDARY GUARD — This namespace MUST NOT import or depend on:
     - resolver-sim.protocols.sew
     - any form under protocols_src/
     - resolver-sim.evidence.force-authorisation (legacy core domain)"
  (:require [resolver-sim.evidence.artifact :as artifact]
            [resolver-sim.hash.sequence :as seq]
            [resolver-sim.sensitivity.sentinel :as sentinel]
            [prf.extensions.held-custody.mutation :as mutation]))

(declare check-held-mutation-sequence)

;; ── summary artifact contract ──────────────────────────────────────────────

(def summary-schema-version
  "Canonical schema version for a held-custody mutation summary artifact."
  "force-auth-held-custody-mutation-summary.v1")

(def summary-kind
  "Canonical :artifact/kind for a held-custody mutation summary artifact."
  :force-auth-held-custody-mutation-summary)

(def summary-verifier-id
  "Canonical verifier identifier for a held-custody mutation summary artifact."
  "force-auth-held-custody-mutation-summary.verifier.v1")

;; ── member classification ──────────────────────────────────────────────────

(def ^:private check-priority
  "Deterministic priority for surfacing the first member failure."
  [:artifact-integrity-valid?
   :action-direction-valid?
   :amount-valid?
   :identity-fields-valid?
   :projection-integrity-valid?
   :mutation-scope-compatible?])

(defn- first-failing-check [checks]
  (first (filter #(not (get checks %)) check-priority)))

(defn- classify-member
  "Primary per-member classification. Returns nil for an intrinsically valid
   held-custody mutation member, else a stable reason keyword."
  [m]
  (cond
    (not (map? m)) :not-held-custody-mutation
    (not= mutation/artifact-kind (:artifact/kind m)) :artifact-kind-mismatch
    (not= mutation/schema-version (:schema-version m)) :schema-version-mismatch
    (not= mutation/verifier-id (:artifact/verifier m)) :verifier-mismatch
    :else
    (let [result (mutation/check-force-auth-held-mutation m nil)]
      (if (:valid? result)
        nil
        (case (first-failing-check (:checks result))
          :artifact-integrity-valid? :content-hash-mismatch
          :action-direction-valid? :invalid-action-direction
          :amount-valid? :invalid-amount
          :identity-fields-valid? :missing-identity
          :projection-integrity-valid? :projection-invalid
          :mutation-scope-compatible? :mutation-scope-mismatch
          :invalid-member)))))

;; ── shared derivation ──────────────────────────────────────────────────────

(defn- amount-sums-by
  "Sorted sparse sums of :held/amount by a member field over valid members."
  [valid-members k]
  (into (sorted-map)
        (reduce (fn [m a]
                  (let [v (get a k)]
                    (if (some? v)
                      (update m v (fnil + 0) (:held/amount a))
                      m)))
                {}
                valid-members)))

(defn- counts-by
  "Sorted sparse counts of a member field over valid members."
  [valid-members k]
  (into (sorted-map) (frequencies (keep k valid-members))))

(defn held-mutation-summary-fields
  "SINGLE canonical summary-field derivation shared by the summary builder, the
   recomputation, and the aggregate checker. Given the same member set it always
   produces the same semantic fields, so builder and recompute cannot diverge.

   Only intrinsically valid members contribute to flows and projections.
   Triage (:invalid-artifacts) covers the complete supplied set. All amounts and
   gross fields are non-negative; :net-change may be negative."
  [members]
  (let [artifacts (vec (or members []))
        total (count artifacts)
        reasons (mapv classify-member artifacts)
        valid? (fn [r] (nil? r))
        valid-count (count (filter valid? reasons))
        invalid-count (- total valid-count)
        valid-members (into []
                            (keep-indexed (fn [i a] (when (valid? (nth reasons i)) a)))
                            artifacts)
        invalid-artifacts (into []
                                (keep-indexed (fn [i a]
                                                (when-let [r (nth reasons i)]
                                                  {:index i
                                                   :mutation/id (:mutation/id a)
                                                   :authorization/id (:authorization/id a)
                                                   :reason r})))
                                artifacts)
        inflow (reduce + 0 (map :held/amount
                                (filter #(= :in (:held/direction %)) valid-members)))
        outflow (reduce + 0 (map :held/amount
                                 (filter #(= :out (:held/direction %)) valid-members)))
        in-count (count (filter #(= :in (:held/direction %)) valid-members))
        out-count (count (filter #(= :out (:held/direction %)) valid-members))
        consumed-ats (->> valid-members (keep :held/consumed-at) (map long) vec)]
    {:total total
     :valid-count valid-count
     :invalid-count invalid-count
     :gross-inflow inflow
     :gross-outflow outflow
     :gross-flow (+ inflow outflow)
     :net-change (- inflow outflow)
     :amount-by-direction {:in inflow :out outflow}
     :by-direction {:in in-count :out out-count}
     :amount-by-action (amount-sums-by valid-members :held/action)
     :by-action (counts-by valid-members :held/action)
     :amount-by-token (amount-sums-by valid-members :held/token)
     :amount-by-account (amount-sums-by valid-members :held/account)
     :amount-by-owner (amount-sums-by valid-members :owner/address)
     :distinct-mutation-ids (count (distinct (keep :mutation/id valid-members)))
     :distinct-authorization-ids (count (distinct (keep :authorization/id valid-members)))
     :distinct-tokens (count (distinct (keep :held/token valid-members)))
     :distinct-accounts (count (distinct (keep :held/account valid-members)))
     :distinct-owners (count (distinct (keep :owner/address valid-members)))
     :consumed-at-earliest (when (seq consumed-ats) (apply min consumed-ats))
     :consumed-at-latest (when (seq consumed-ats) (apply max consumed-ats))
     :invalid-artifacts (vec invalid-artifacts)}))

(defn- summary-body
  "Canonical summary body (without the artifact envelope) from a member set."
  [members]
  (let [f (held-mutation-summary-fields members)]
    {:schema-version summary-schema-version
     :artifact/kind summary-kind
     :artifact/verifier summary-verifier-id
     :total (:total f)
     :valid-count (:valid-count f)
     :invalid-count (:invalid-count f)
     :gross-inflow (:gross-inflow f)
     :gross-outflow (:gross-outflow f)
     :gross-flow (:gross-flow f)
     :net-change (:net-change f)
     :amount-by-direction (:amount-by-direction f)
     :by-direction (:by-direction f)
     :amount-by-action (:amount-by-action f)
     :by-action (:by-action f)
     :amount-by-token (:amount-by-token f)
     :amount-by-account (:amount-by-account f)
     :amount-by-owner (:amount-by-owner f)
     :distinct-mutation-ids (:distinct-mutation-ids f)
     :distinct-authorization-ids (:distinct-authorization-ids f)
     :distinct-tokens (:distinct-tokens f)
     :distinct-accounts (:distinct-accounts f)
     :distinct-owners (:distinct-owners f)
     :consumed-at-earliest (:consumed-at-earliest f)
     :consumed-at-latest (:consumed-at-latest f)
     :invalid-artifacts (:invalid-artifacts f)}))

(defn- admit-members!
  "Fail-fast admission: any member that is not intrinsically valid throws
   ex-info with structured diagnostics."
  [members]
  (let [members (vec (or members []))
        invalid (into []
                      (keep-indexed (fn [i a]
                                      (when-let [r (classify-member a)]
                                        {:index i
                                         :mutation/id (:mutation/id a)
                                         :authorization/id (:authorization/id a)
                                         :reason r})))
                      members)]
    (when (seq invalid)
      (throw (ex-info "build-held-mutation-summary: member set contains non-passing members"
                      {:member-count (count members)
                       :invalid-count (count invalid)
                       :invalid-members (vec invalid)})))))

;; ── builders / recompute ───────────────────────────────────────────────────

(defn build-held-mutation-summary
  "Build a held-custody mutation summary artifact over an intrinsically valid
   member set. FAIL-FAST: non-passing members throw ex-info with structured
   diagnostics. Construction and validation are separated but aligned: builder
   and recompute share the same canonical derivation."
  [members _options]
  (admit-members! members)
  (artifact/finalize-artifact (summary-body members)))

(defn recompute-held-mutation-summary
  "Canonical recomputation of the held-custody mutation summary artifact from a
   member set. Permissive: accepts the complete supplied set and reproduces the
   triage views exactly as the builder does. Byte-equal to the builder for any
   admitted (intrinsically valid) member set."
  [members _options]
  (artifact/finalize-artifact (summary-body members)))

;; ── aggregate checker ──────────────────────────────────────────────────────

(def ^:private derivable-paths
  "Every summary field that must reconcile against the canonical recomputation."
  [[[:total] :total]
   [[:valid-count] :valid-count]
   [[:invalid-count] :invalid-count]
   [[:gross-inflow] :gross-inflow]
   [[:gross-outflow] :gross-outflow]
   [[:gross-flow] :gross-flow]
   [[:net-change] :net-change]
   [[:amount-by-direction] :amount-by-direction]
   [[:by-direction] :by-direction]
   [[:amount-by-action] :amount-by-action]
   [[:by-action] :by-action]
   [[:amount-by-token] :amount-by-token]
   [[:amount-by-account] :amount-by-account]
   [[:amount-by-owner] :amount-by-owner]
   [[:distinct-mutation-ids] :distinct-mutation-ids]
   [[:distinct-authorization-ids] :distinct-authorization-ids]
   [[:distinct-tokens] :distinct-tokens]
   [[:distinct-accounts] :distinct-accounts]
   [[:distinct-owners] :distinct-owners]
   [[:consumed-at-earliest] :consumed-at-earliest]
   [[:consumed-at-latest] :consumed-at-latest]
   [[:invalid-artifacts] :invalid-artifacts]])

(defn- reconcile
  "Compare every derivable summary field against the canonical recomputation.
   A missing stored field compares as nil, so a summary can never silently drop
   a derivable value."
  [summary fields]
  (into []
        (keep (fn [[path k]]
                (let [expected (get fields k)
                      actual (get-in summary path)]
                  (when-not (= expected actual)
                    {:path path :expected expected :actual actual}))))
        derivable-paths))

(defn- valid-member-summary
  "Summarize member validation: {valid-count invalid [entry...] valid-members [m...]}."
  [members]
  (let [members (vec (or members []))
        reasons (mapv classify-member members)
        invalid (into []
                      (keep-indexed (fn [i a]
                                      (when-let [r (nth reasons i)]
                                        {:index i
                                         :mutation/id (:mutation/id a)
                                         :authorization/id (:authorization/id a)
                                         :reason r})))
                      members)
        valid-members (into []
                            (keep-indexed (fn [i a] (when (nil? (nth reasons i)) a)))
                            members)]
    {:valid-count (count valid-members)
     :invalid invalid
     :valid-members valid-members}))

(defn check-held-mutation-aggregate
  "Structured, deterministic, data-only aggregate check for a
   force-auth-held-custody-mutation-summary artifact against the members it is
   claimed to aggregate over.

     (check-held-mutation-aggregate summary members options)

   options:
     :authorizations  optional map {authorization-id record}. When supplied,
                      every valid member's referenced authorisation is verified
                      (projection-hash == record :authorization/scope-hash) and
                      unverified ids are surfaced.
     :sequence        optional held-mutation-sequence artifact. When supplied,
                      the ordered consecutive mutation run is independently
                      verified (see check-held-mutation-sequence) and its
                      validity is folded into :valid?.

   Semantics:
     :valid?     — the summary is a well-formed summary consistent with an
                   intrinsically valid member set (identity, hash, flow
                   arithmetic, non-negativity, full reconciliation).
     :verified?  — :valid? AND every referenced authorisation was supplied and
                   verified. Absence of authorisation records is never reported
                   as fully verified.

   Returns {:valid? :verified? :status :checks :invalid-members
            :unverified-authorization-ids :mismatches :warnings}."
  [summary members options]
  (let [options (or options {})
        authorizations (:authorizations options)
        sequence (:sequence options)
        sequence-check (when sequence
                         (check-held-mutation-sequence sequence members {}))
        sequence-ok? (or (nil? sequence) (:valid? sequence-check))
        summary-map? (map? summary)
        identity-ok? (and summary-map?
                          (= summary-schema-version (:schema-version summary))
                          (= summary-kind (:artifact/kind summary))
                          (= summary-verifier-id (:artifact/verifier summary))
                          ;; held-custody .v1 summary uses the strict :exact policy
                          (artifact/valid-artifact? summary summary-schema-version
                                                    summary-kind summary-verifier-id
                                                    :exact))
        {:keys [invalid valid-members]} (valid-member-summary members)
        members-valid? (zero? (count invalid))
        fields (held-mutation-summary-fields members)
        mismatches (if summary-map?
                     (reconcile summary fields)
                     [{:path [] :expected summary-schema-version :actual summary}])
        summary-recomputes? (empty? mismatches)
        flow-ok? (and summary-map?
                      (= (:gross-flow summary)
                         (+ (or (:gross-inflow summary) 0)
                            (or (:gross-outflow summary) 0)))
                      (= (:net-change summary)
                         (- (or (:gross-inflow summary) 0)
                            (or (:gross-outflow summary) 0))))
        non-negative-ok? (and summary-map?
                              (not (neg? (or (:gross-inflow summary) 0)))
                              (not (neg? (or (:gross-outflow summary) 0)))
                              (not (neg? (or (:gross-flow summary) 0))))
        unverified-ids (vec (sort
                             (distinct
                              (keep (fn [m]
                                      (let [record (get authorizations (:authorization/id m))]
                                        (when-not (and record
                                                       (= (:authorization-scope/projection-hash m)
                                                          (:authorization/scope-hash record)))
                                          (:authorization/id m))))
                                    valid-members))))
        checks {:summary-identity-valid? identity-ok?
                :members-valid? members-valid?
                :summary-recomputes? summary-recomputes?
                :flow-reconciles? flow-ok?
                :amounts-non-negative? non-negative-ok?
                :authorizations-verified? (empty? unverified-ids)
                :sequence-valid? sequence-ok?}
        valid? (every? true? (vals (dissoc checks :authorizations-verified?)))
        verified? (and valid? (empty? unverified-ids))]
    {:valid? valid?
     :verified? verified?
     :status (cond
               (not valid?) :invalid
               verified? :valid-verified
               :else :valid-unverified)
     :checks checks
     :invalid-members (vec invalid)
     :unverified-authorization-ids unverified-ids
     :mismatches mismatches
     :warnings []}))

;; ── consecutive mutation-sequence commitment ────────────────────────────────
;;
;; The summary commits *flow aggregates* over the member set.  The sequence
;; artifact additionally commits the *ordered run* of consecutive held
;; mutations (add-held, sub-held, finalize-released, refund-held) under the
;; named canonical-value-sequence.v1 contract: the ordered member :artifact/hash
;; refs are bound to a purpose and component-count so the same bytes cannot be
;; silently reinterpreted as a different protocol object.

(def sequence-schema-version
  "Canonical schema version for a held-custody mutation sequence artifact."
  "force-auth-held-custody-mutation-sequence.v1")

(def sequence-kind
  "Canonical :artifact/kind for a held-custody mutation sequence artifact."
  :force-auth-held-custody-mutation-sequence)

(def sequence-verifier-id
  "Canonical verifier identifier for a held-custody mutation sequence artifact."
  "force-auth-held-custody-mutation-sequence.verifier.v1")

(def sequence-purpose
  "Domain purpose bound into the sequence commitment."
  :held-custody-mutations)

(def sequence-contract
  "Encoding-contract identifier for the named sequence framing."
  "canonical-value-sequence.v1")

(def legacy-add-held-kind
  "Legacy add-held artifact kind (:force-auth-add-held — the add-held-kind of
   the legacy evidence domain).  Used to attribute projected legacy members in
   the sequence commitment without depending on the legacy evidence namespace."
  :force-auth-add-held)

(defn member-kind
  "Artifact kind of a sequence member.  Native held-custody mutations carry
   :artifact/kind; projected legacy add-held members (which carry
   :legacy/classification instead) are attributed to add-held-kind
   :force-auth-add-held."
  [m]
  (or (:artifact/kind m)
      (when (some? (:legacy/classification m)) legacy-add-held-kind)))

(defn remote-authority-required?
  "True when a member is forbidden from local in-process authorization
   (force-auth-add evidence): its kind is add-held-kind :force-auth-add-held or
   its held action is :add-held.  Delegates to the sensitivity sentinel, the
   authoritative classifier for the remote-authority-required boundary."
  [m]
  (sentinel/remote-authority-required-artifact? m))

(defn force-auth-authorised?
  "True when every member is force-authorisation-backed — each carries a
   non-nil :authorization/id (native members are built only from a verified
   authorisation, so this is a documentation invariant, not a weak check)."
  [members]
  (every? (comp some? :authorization/id) members))

(defn member-order
  "Deterministic consecutive order of a member set for the sequence commitment.
   Sorted by :held/consumed-at (absent sorts first as 0), then :mutation/id,
   then :authorization/id.  The order is part of the committed sequence — the
   same member set in a different order commits to different bytes."
  [members]
  (sort-by (fn [m]
             [(or (:held/consumed-at m) 0)
              (:mutation/id m)
              (:authorization/id m)])
           members))

(defn held-mutation-sequence-body
  "Canonical sequence-commitment body (without the artifact envelope) from a
   member set.  Commits the ordered member :artifact/hash refs under the
   canonical-value-sequence.v1 contract, and self-describes the run: its
   actions, artifact kinds (including add-held-kind :force-auth-add-held for
   projected legacy members), force-authorisation boundary posture
   (:force-auth-add-forbidden? / :force-auth-authorised?), and the add-held
   amount posture (:add-held/count / :add-held/amount / :amount-by-action)."
  [members]
  (let [ordered (vec (member-order members))
        refs (mapv :artifact/hash ordered)
        remote-authority-required (filterv remote-authority-required? ordered)
        add-held-members (filterv #(= :add-held (:held/action %)) ordered)
        amount-by-action (into (sorted-map)
                               (reduce (fn [m a]
                                         (let [act (:held/action a)]
                                           (if (some? act)
                                             (update m act (fnil + 0) (:held/amount a))
                                             m)))
                                       {}
                                       ordered))]
    {:schema-version sequence-schema-version
     :artifact/kind sequence-kind
     :artifact/verifier sequence-verifier-id
     :encoding-contract sequence-contract
     :purpose sequence-purpose
     :component-count (count refs)
     :member-order (mapv :mutation/id ordered)
     :member-hashes refs
     :actions (vec (sort (distinct (keep :held/action ordered))))
     :kinds (vec (sort (distinct (keep member-kind ordered))))
     :action-counts (into (sorted-map)
                          (frequencies (keep :held/action ordered)))
     :kind-counts (into (sorted-map)
                        (frequencies (keep member-kind ordered)))
     :force-auth-add-forbidden? (boolean (seq remote-authority-required))
     :remote-authority-required-count (count remote-authority-required)
     :force-auth-authorised? (force-auth-authorised? ordered)
     :authorisation/id-count (count (distinct (keep :authorization/id ordered)))
     :add-held/count (count add-held-members)
     :add-held/amount (reduce + 0 (keep :held/amount add-held-members))
     :amount-by-action amount-by-action
     :sequence-hash (str "sha256:"
                         (seq/sequence-hash {:purpose sequence-purpose} refs))}))

(defn held-mutation-sequence-bytes
  "Raw canonical bytes of the bound sequence commitment (canonical-value-
   sequence.v1) over the ordered member refs.  Framing-view compatible."
  [members]
  (seq/canonical-sequence-bytes
   {:purpose sequence-purpose}
   (mapv :artifact/hash (member-order members))))

(defn build-held-mutation-sequence
  "Build a held-custody mutation sequence artifact over an intrinsically valid
   member set.  FAIL-FAST: non-passing members throw ex-info with structured
   diagnostics (same admission as the summary).  Construction and recomputation
   share the same canonical derivation."
  [members _options]
  (admit-members! members)
  (artifact/finalize-artifact (held-mutation-sequence-body members)))

(defn recompute-held-mutation-sequence
  "Canonical recomputation of the sequence artifact from a member set.
   Byte-equal to the builder for any admitted member set."
  [members _options]
  (artifact/finalize-artifact (held-mutation-sequence-body members)))

(defn check-held-mutation-sequence
  "Structured, deterministic, data-only check of a held-custody mutation
   sequence artifact against the members it claims to commit.

   :valid? requires artifact identity (schema/kind/verifier), exact content
   round-trip, membership validity, and byte-equal recomputation of the ordered
   run.  Returns {:valid? :status :checks :invalid-members :mismatches}."
  [sequence members _options]
  (let [identity-ok? (and (map? sequence)
                          (= sequence-schema-version (:schema-version sequence))
                          (= sequence-kind (:artifact/kind sequence))
                          (= sequence-verifier-id (:artifact/verifier sequence))
                          (artifact/valid-artifact? sequence sequence-schema-version
                                                    sequence-kind sequence-verifier-id
                                                    :exact))
        {:keys [invalid]} (valid-member-summary members)
        members-valid? (zero? (count invalid))
        expected-body (held-mutation-sequence-body members)
        recomputes? (and identity-ok?
                         (= (artifact/artifact-body expected-body)
                            (artifact/artifact-body sequence)))
        checks {:sequence-identity-valid? identity-ok?
                :members-valid? members-valid?
                :sequence-recomputes? recomputes?}
        valid? (every? true? (vals checks))]
    {:valid? valid?
     :status (if valid? :valid :invalid)
     :checks checks
     :invalid-members (vec invalid)
     :mismatches (when (and identity-ok? (not recomputes?))
                   [{:path [] :expected (artifact/artifact-body expected-body)
                     :actual (artifact/artifact-body sequence)}])}))
