(ns resolver-sim.benchmark.review-aggregate-check
  "Aggregate invariant checks for benchmark review structures.
   Complementary to the per-structure validators in review-round and
   canonical-indices — these checks verify cross-structure consistency
   properties that span multiple artifacts."
  (:require [clojure.set]
            [resolver-sim.benchmark.review-round :as rr]
            [resolver-sim.benchmark.review-member-canonical-indices :as ci]
            [resolver-sim.hash.round-trip :as rt]))

(declare check-aggregate-member-key-density)

;; ── Aggregate member-bit-width consistency ─────────────────────────────────

(defn check-aggregate-member-bit-width
  "Verify that the member bit-width is consistent between a review round
   and its canonical-indices artifact (if present).

   Also verifies that the round's bit-width is non-negative and matches
   the expected width for the number of members.

   Returns {:holds? bool
            :violations [{:kind ::member-bit-width-mismatch
                          :round-bit-width <int-or-nil>
                          :artifact-bit-width <int-or-nil>
                          :message string} ...]}."
  [round & [canonical-indices]]
  (let [violations (atom [])
        keyed? (rr/round-uses-member-keys? round)
        round-width (rr/member-bit-width round)
        member-count (count (rr/round-members round))
        expected-width (when (pos? member-count)
                         (ci/member-bit-width {:review-member/count member-count}))]
    (when (and keyed? (nil? round-width))
      (swap! violations conj
             {:kind ::member-bit-width-mismatch
              :round-bit-width nil
              :artifact-bit-width nil
              :message "bit-width is nil for a keyed review round"}))
    (when (and keyed?
               (or (not (integer? round-width))
                   (neg? round-width)
                   (not= expected-width round-width)))
      (swap! violations conj
             {:kind ::member-bit-width-mismatch
              :round-bit-width round-width
              :expected-bit-width expected-width
              :member-count member-count
              :message (str "round bit-width " round-width
                            " does not match expected width " expected-width
                            " for " member-count " members")}))
    (when canonical-indices
      (let [artifact-width (ci/member-bit-width canonical-indices)]
        ;; a keyed round (round-width present) paired with an artifact whose width
        ;; is nil (e.g. an empty artifact) is a mismatch, not a silent skip
        (when (and (some? round-width)
                   (not= round-width artifact-width))
          (swap! violations conj
                 {:kind ::member-bit-width-mismatch
                  :round-bit-width round-width
                  :artifact-bit-width artifact-width
                  :message (str "bit-width mismatch between round (" round-width
                                ") and canonical-indices (" artifact-width ")")}))))
    {:holds? (empty? @violations)
     :violations (vec @violations)}))

;; ── Aggregate three-member standard consistency ────────────────────────────
;;
;; The canonical three-member standard (ADR-0007 D1) requires exactly three
;; DISTINCT constituted seats. Distinct identifiers are identity/key separation,
;; never real-world independence.

(defn check-aggregate-three-member-standard
  "Aggregate invariant: a canonical three-member review round must constitute
   exactly three DISTINCT member identities, with every role from the canonical
   role vocabulary and — for keyed rounds — dense unique member keys.

   Member / role / key identity semantics (what is a 'known member', a 'known
   role', and a valid member→role relation):

   A review round's constitution is exactly its :review-round/members vector;
   every entry there is a constituted member, identified by :researcher/id.  A
   member is 'known' by virtue of being in that vector; the aggregate check does
   not independently re-derive a separate known-member registry.  A role is
   'known' when its keyword is in rr/member-roles —
   #{:model-steward :independent-reproducer :adversarial-reviewer}.  A member→role
   relation is 'valid' exactly when the member map's :role is drawn from that
   controlled vocabulary (::unknown-member-role fires otherwise).

   The check deliberately does NOT enforce role uniqueness or a fixed
   one-to-one member→role assignment: a valid round may legitimately give the
   same role to two seats, and there is no required pairing of a role with a
   particular key or researcher.  Collection-level constraints beyond the
   vocabulary (distinct identities, dense unique keys for keyed rounds, count)
   are enforced separately and reported under their own kinds.  Key identity is
   a derived index (:review-member/key) only meaningful for keyed rounds, never
   part of the round's durable identity.

   Returns {:holds? bool
            :violations [{:kind kw :count/:ids/:roles ...} ...]}."
  [round]
  (let [members (:review-round/members round)
        ids (mapv :researcher/id members)
        roles (mapv :role members)
        violations (atom [])]
    (when-not (= 3 (count members))
      (swap! violations conj {:kind ::not-three-members
                              :count (count members)}))
    (when-not (= (count ids) (count (distinct ids)))
      (swap! violations conj {:kind ::non-distinct-member-identities
                              :ids ids}))
    (when-not (every? rr/member-roles roles)
      (swap! violations conj {:kind ::unknown-member-role
                              :roles roles}))
    (when (and (rr/round-uses-member-keys? round)
               (not (rr/unique-member-keys? members)))
      (swap! violations conj {:kind ::duplicate-member-keys}))
    {:holds? (empty? @violations)
     :violations (vec @violations)}))

;; ── Aggregate three-classifications-preserved ──────────────────────────────
;;
;; The three-member authority report (evaluate-three-member-authority) carries
;; THREE classification dimensions that must be preserved — never collapsed,
;; never dropped, never double-counted — when threaded through an aggregate
;; review check:
;;
;;   1. :authority-status   :authorised | :not-authorised
;;   2. :outcome-source     :authoritative-target | :target-outcome-unavailable
;;   3. position categories valid-supporting / valid-dissenting /
;;      valid-qualifying / invalid / equivocating / unknown / re-scoped /
;;      duplicate-seat / absent — disjoint, each position in at most one.

(defn- position-hashes
  [positions]
  (set (keep :decision/hash positions)))

(defn- category-hash-map
  "Collect committed position hashes per category from an authority report."
  [report]
  {:supporting   (position-hashes (:valid-supporting-positions report))
   :dissenting   (position-hashes (:valid-dissenting-positions report))
   :qualifying   (position-hashes (:valid-qualifying-positions report))
   :invalid      (position-hashes (:invalid-positions report))
   :re-scoped    (position-hashes (:re-scoped-positions report))
   :duplicate    (position-hashes (:duplicate-seat-positions report))
   :equivocating (position-hashes (mapcat :incompatible-positions
                                          (:equivocating-members report)))})

(defn- member-category-map
  "Member IDs per member-level classification category from an authority report.
   :unknown members are not constituted seats; the rest partition the
   constituted members."
  [report]
  {:supporting   (set (map :researcher/id (:valid-supporting-positions report)))
   :dissenting   (set (map :researcher/id (:valid-dissenting-positions report)))
   :qualifying   (set (map :researcher/id (:valid-qualifying-positions report)))
   :absent       (set (:absent-members report))
   :equivocating (set (map :member/id (:equivocating-members report)))
   :invalid      (set (map :researcher/id (:invalid-positions report)))
   :re-scoped    (set (map :researcher/id (:re-scoped-positions report)))
   :duplicate    (set (map :researcher/id (:duplicate-seat-positions report)))
   :unknown      (set (map :researcher/id (:unknown-members report)))})

(defn- overlapping-category-pairs
  "Pairs of categories that share a committed member id / position hash."
  [category-hash-map]
  (let [cats (keys category-hash-map)]
    (for [[i a] (map-indexed vector cats)
          b (drop (inc i) cats)
          :let [inter (clojure.set/intersection (get category-hash-map a)
                                                (get category-hash-map b))]
          :when (seq inter)]
      {:categories [a b] :hashes (vec inter)})))

(defn check-aggregate-three-member-classifications
  "Aggregate invariant: an authority report must preserve all three
   classification dimensions (authority status, outcome source, and the
   non-collapsed position categories) and they must be internally consistent
   and consistent with the review round.

   Cross-dimension rules verified:
     1. :authorised implies :outcome-source :authoritative-target, a
        counted-support at or above the required threshold, policy conformance,
        identity separation, and exactly three constituted members.
     2. :outcome-source :target-outcome-unavailable implies not :authorised
        (surfaced by ::authorised-without-authoritative-outcome, which is the
        single general finding for an authorised status with any non-authoritative
        outcome source).
     3. :counted-support equals the count of :valid-supporting-positions.
     4. no position appears in more than one position category.  The
        :duplicate-seat category is the sole exception: it re-commits the hash
        of the counted position it duplicates, so it legitimately co-occurs with
        the counted category and is excluded from this disjointness check.
     4b. member classifications are preserved: each constituted member is in at
         most one member-level category (no double-classification; a member with
         a duplicate seat is classified by its counted position, not by the
         duplicate copy), and every constituted member is accounted for in at
         least one (none silently dropped).
     5. equivocating members never contribute valid-supporting or
        valid-dissenting positions.
     6. invalid / unknown / re-scoped / duplicate positions are excluded from
        the counted support but preserved in the report.
     7. the report's :constituted-member-count matches the round member count,
        and :identity-separate? matches round member distinctness.

   round  — review-round artifact carrying :review-round/members.
   report — an evaluate-three-member-authority result.

   A degenerate error-fallback report (carrying :authority/error and lacking
   the classification fields) is surfaced as ::authority-evaluation-failed
   rather than emitting phantom field-level mismatches — such a report is not
   internally inconsistent, it was simply not produced because authority
   evaluation errored.

   Returns {:holds? bool :violations [...]}."
  [round report]
  (if-let [error (:authority/error report)]
    ;; Authority evaluation failed before a classification was produced.  The
    ;; report carries only :authority-status / :outcome-source / :authority/error
    ;; and none of the classification fields, so no field-level comparison is
    ;; meaningful.  Surface the evaluation failure explicitly and stop.
    {:holds? false
     :violations [{:kind ::authority-evaluation-failed
                   :error error}]}
    (let [status (:authority-status report)
          outcome-source (:outcome-source report)
          members (:review-round/members round)
          round-count (count members)
          round-distinct? (= round-count
                             (count (distinct (map :researcher/id members))))
          cats (category-hash-map report)
          member-cats (member-category-map report)
          ;; :duplicate-seat is a position-level PRESERVATION category: its
          ;; entries carry the same :decision/hash as the counted position they
          ;; duplicate, so it legitimately co-occurs with :supporting /
          ;; :dissenting / :qualifying — that is its whole purpose.  Exclude it
          ;; from the disjointness (overlap) checks so a legitimate duplicate
          ;; submission is never misread as double-classification.
          position-cats (dissoc cats :duplicate)
          member-level-cats (dissoc member-cats :duplicate)
          overlaps (overlapping-category-pairs position-cats)
          member-overlaps (overlapping-category-pairs member-level-cats)
          constituted-ids (set (map :researcher/id members))
          accounted-ids (apply clojure.set/union
                               (vals (dissoc member-cats :unknown)))
          unaccounted (vec (sort (remove accounted-ids constituted-ids)))
          violations (atom [])
          add! (fn [kind data] (swap! violations conj (assoc data :kind kind)))]
    ;; 1/2. classification dimensions present and well-typed
      (when-not (contains? #{:authorised :not-authorised} status)
        (add! ::invalid-authority-status {:status status}))
      (when-not (contains? #{:authoritative-target :target-outcome-unavailable}
                           outcome-source)
        (add! ::invalid-outcome-source {:outcome-source outcome-source}))

      (when (= :authorised status)
        (when-not (= :authoritative-target outcome-source)
          ;; rule 2: :target-outcome-unavailable implies not :authorised.  The
          ;; general finding (authorised + a non-authoritative outcome source) is
          ;; the single surface for this contradiction: the outcome-source
          ;; vocabulary has exactly one non-authoritative value
          ;; (:target-outcome-unavailable), so an explicitly-unavailable outcome
          ;; under an authorised status is a strict subset of this finding.  A
          ;; former dedicated ::authorised-with-unavailable-outcome kind always
          ;; co-fired with this one and carried strictly less information (empty
          ;; data), so it was merged here rather than kept as a redundant branch.
          (add! ::authorised-without-authoritative-outcome {:outcome-source outcome-source}))
        (when-not (>= (:counted-support report) (:required-threshold report))
          (add! ::authorised-below-threshold
                {:counted-support (:counted-support report)
                 :required (:required-threshold report)}))
        (when-not (true? (:policy-conforming? report))
          (add! ::authorised-policy-not-conforming {}))
        (when-not (true? (:identity-separate? report))
          (add! ::authorised-not-identity-separate {}))
        (when-not (= 3 (:constituted-member-count report))
          (add! ::authorised-not-three-members
                {:count (:constituted-member-count report)})))

    ;; 3. counted-support equals valid-supporting count
      (when-not (= (:counted-support report)
                   (count (:valid-supporting-positions report)))
        (add! ::counted-support-mismatch
              {:counted-support (:counted-support report)
               :supporting (count (:valid-supporting-positions report))}))

    ;; 4. position categories are disjoint
      (doseq [{:keys [categories hashes]} overlaps]
        (add! ::position-category-overlap {:categories categories :hashes hashes}))

    ;; 4b. member classifications are preserved: each constituted member is in
    ;; at most one member-level category (no double-classification), and every
    ;; constituted member is accounted for in at least one (none silently
    ;; dropped).
      (doseq [{:keys [categories hashes]} member-overlaps]
        (add! ::member-category-overlap {:categories categories :members hashes}))
      (when (seq unaccounted)
        (add! ::member-unaccounted {:members unaccounted}))

    ;; 5. equivocating members never support or dissent
      (when (seq (clojure.set/intersection (:equivocating cats) (:supporting cats)))
        (add! ::equivocator-counted-as-supporter {}))
      (when (seq (clojure.set/intersection (:equivocating cats) (:dissenting cats)))
        (add! ::equivocator-counted-as-dissenter {}))

    ;; 6. excluded-but-preserved: never counted, still present.
    ;;    :duplicate-seat is intentionally absent here — a duplicate copy by
    ;;    definition re-commits a counted position's hash, so it must be allowed
    ;;    to co-occur with the counted category.
      (doseq [[label hs] [[:invalid (:invalid cats)]
                          [:re-scoped (:re-scoped cats)]]]
        (when (seq (clojure.set/intersection hs (:supporting cats)))
          (add! ::excluded-position-counted {:category label})))

    ;; 7. report/round consistency
      (when-not (= round-count (:constituted-member-count report))
        (add! ::member-count-mismatch
              {:round round-count :report (:constituted-member-count report)}))
      (when-not (= round-distinct? (true? (:identity-separate? report)))
        (add! ::identity-separation-mismatch
              {:round-distinct? round-distinct?
               :report (:identity-separate? report)}))

      {:holds? (empty? @violations)
       :violations (vec @violations)})))

;; ── Three-classifications fixed-point (threaded through canonical bytes) ────

(def classification-dimensions
  "The three classification dimensions that must survive the canonical
   fixed-point (see check-aggregate-three-member-classifications)."
  [:authority-status
   :outcome-source
   :valid-supporting-positions
   :valid-dissenting-positions
   :valid-qualifying-positions
   :invalid-positions
   :re-scoped-positions
   :duplicate-seat-positions
   :equivocating-members
   :unknown-members])

(defn- classification-projection
  "Project the classification dimensions of an authority report."
  [report]
  (select-keys report classification-dimensions))

(defn classifications-fixed-point
  "Canonical fixed-point stage for an authority report's three classification
   dimensions.

   Composes the purpose-neutral canonical round-trip (resolver-sim.hash.round-trip)
   with the three-member classification projection comparison:

     {:holds? bool                  — the decoded report's classification
                                      dimensions equal the original's AND the
                                      canonical round-trip is valid
      :violations [...]             — canonicality and/or projection mismatches
      :report <decoded-or-nil>      — the actual decoded report (nil when the
                                      canonical round-trip is invalid)}

   The decoded report is first-class output so a downstream semantic check can
   consume the real fixed-point artifact (the value a verifier would recompute)
   instead of re-decoding or re-deriving an equivalent value."
  [report]
  (let [{:keys [valid? value issues]} (rt/canonical-round-trip report)
        orig (classification-projection report)
        dec  (when valid? (classification-projection value))
        projection-ok? (= orig dec)
        violations (cond-> []
                     (not valid?)
                     (conj {:kind ::classifications-fixed-point-invalid
                            :issues issues})
                     (and valid? (not projection-ok?))
                     (conj {:kind ::classifications-not-fixed-point
                            :original orig :decoded dec}))]
    {:holds? (and valid? projection-ok?)
     :violations violations
     :report (when valid? value)}))

(defn check-classifications-fixed-point
  "Verify the three classification dimensions of an authority report survive
   the canonical fixed-point: serializing the report to canonical bytes and
   decoding it back reproduces exactly the same classification, never a
   collapsed, dropped, or reordered variant.

   Thin wrapper over `classifications-fixed-point`; consumers that need the
   decoded report for downstream verification should call that directly.

   Returns {:holds? bool :violations [...]}."
  [report]
  (let [{:keys [holds? violations]} (classifications-fixed-point report)]
    {:holds? holds? :violations violations}))

;; ── Aggregate runner (threaded) ─────────────────────────────────────────────
;;
;; The aggregate checks are composed here so consumers can run the full
;; three-member review-aggregate surface in one call instead of scattering
;; individual checks.

(defn run-review-aggregate-checks
  "Compose all review-aggregate checks for a three-member review context.

   round             — review-round artifact.
   canonical-indices — optional canonical-indices artifact.
   report            — optional evaluate-three-member-authority result. When
                       supplied, the three-classifications-preserved check runs
                       on BOTH the stored report and (through the canonical
                       fixed-point stage) on the decoded report:

                         stored report
                            ├──> three-member-classifications
                            └──> classifications-fixed-point
                                       │
                                       └── decoded report
                                            └──> three-member-classifications-on-fixed-point

                       The decoded report consumed by the second semantic check
                       is the actual artifact returned by the fixed-point stage —
                       never an independent re-decode or a re-check of the
                       original input.

   Returns {:holds? bool
            :checks {<check-name> {:holds? bool :violations [...]}}}
   where every named check must hold for the aggregate to hold."
  ([round] (run-review-aggregate-checks round nil))
  ([round canonical-indices]
   (run-review-aggregate-checks round canonical-indices nil))
  ([round canonical-indices report]
   (let [fixed-point (when report (classifications-fixed-point report))
         decoded-report (:report fixed-point)
         checks (cond-> {:member-bit-width
                         (check-aggregate-member-bit-width round canonical-indices)
                         :member-key-density
                         (check-aggregate-member-key-density round canonical-indices)
                         :three-member-standard
                         (check-aggregate-three-member-standard round)}
                  report
                  (assoc :three-member-classifications
                         (check-aggregate-three-member-classifications
                          round report))
                  report
                  (assoc :classifications-fixed-point
                         {:holds? (:holds? fixed-point)
                          :violations (:violations fixed-point)})
                   ;; The semantic check on the decoded report consumes the actual
                   ;; fixed-point artifact (decoded-report) — a real data
                   ;; dependency, not a duplicate check of the stored report.
                   ;; A decode failure is LOUD: the check is always present and
                   ;; fails with ::fixed-point-unavailable rather than being
                   ;; silently omitted (which would be indistinguishable from
                   ;; "no report supplied").
                  report
                  (assoc :three-member-classifications-on-fixed-point
                         (if decoded-report
                           (check-aggregate-three-member-classifications
                            round decoded-report)
                           {:holds? false
                            :violations [{:kind ::fixed-point-unavailable}]})))]
     {:holds? (every? :holds? (vals checks))
      :checks checks})))

;; ── Aggregate member-key density consistency ───────────────────────────────

(defn check-aggregate-member-key-density
  "Verify that member key density (dense 0..n-1) is consistent between
   a review round and its canonical-indices artifact (if present).

   Density is a SET property (order-independent): the round's member-key set
   must equal #{0..n-1} regardless of the order its members are stored in, so a
   keyed round whose members are not key-sorted is still dense.

   Returns {:holds? bool :violations [...]}."
  [round & [canonical-indices]]
  (let [violations (atom [])]
    (when (rr/round-uses-member-keys? round)
      (let [keys (rr/member-keys round)]
        (when (not= (set keys) (set (range (count keys))))
          (swap! violations conj
                 {:kind ::non-dense-member-keys
                  :keys keys
                  :message (str "round keys " keys " are not dense 0.." (dec (count keys)))}))
        (when canonical-indices
          (let [n (:review-member/count canonical-indices)]
            (when (not= (count keys) n)
              (swap! violations conj
                     {:kind ::member-count-mismatch
                      :round-count (count keys)
                      :artifact-count n
                      :message (str "round member count " (count keys)
                                    " differs from artifact count " n)}))))))
    {:holds? (empty? @violations)
     :violations (vec @violations)}))
