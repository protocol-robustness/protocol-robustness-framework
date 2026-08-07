(ns resolver-sim.benchmark.review-aggregate-check
  "Aggregate invariant checks for benchmark review structures.
   Complementary to the per-structure validators in review-round and
   canonical-indices — these checks verify cross-structure consistency
   properties that span multiple artifacts."
  (:require [clojure.set]
            [resolver-sim.benchmark.review-round :as rr]
            [resolver-sim.benchmark.review-member-canonical-indices :as ci]))

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
        round-width (rr/member-bit-width round)]
    (when (and (rr/round-uses-member-keys? round) (nil? round-width))
      (swap! violations conj
             {:kind ::member-bit-width-mismatch
              :round-bit-width nil
              :artifact-bit-width nil
              :message "bit-width is nil for a keyed review round"}))
    (when canonical-indices
      (let [artifact-width (ci/member-bit-width canonical-indices)]
        (when (and round-width artifact-width (not= round-width artifact-width))
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
     2. :outcome-source :target-outcome-unavailable implies not :authorised.
     3. :counted-support equals the count of :valid-supporting-positions.
     4. no position appears in more than one position category.
     4b. member classifications are preserved: each constituted member is in at
         most one member-level category (no double-classification), and every
         constituted member is accounted for in at least one (none silently
         dropped).
     5. equivocating members never contribute valid-supporting or
        valid-dissenting positions.
     6. invalid / unknown / re-scoped / duplicate positions are excluded from
        the counted support but preserved in the report.
     7. the report's :constituted-member-count matches the round member count,
        and :identity-separate? matches round member distinctness.

   round  — review-round artifact carrying :review-round/members.
   report — an evaluate-three-member-authority result.

   Returns {:holds? bool :violations [...]}."
  [round report]
  (let [status (:authority-status report)
        outcome-source (:outcome-source report)
        members (:review-round/members round)
        round-count (count members)
        round-distinct? (= round-count
                           (count (distinct (map :researcher/id members))))
        cats (category-hash-map report)
        member-cats (member-category-map report)
        overlaps (overlapping-category-pairs cats)
        member-overlaps (overlapping-category-pairs member-cats)
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

    (when (and (= :target-outcome-unavailable outcome-source)
               (= :authorised status))
      (add! ::authorised-with-unavailable-outcome {}))

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

    ;; 6. excluded-but-preserved: never counted, still present
    (doseq [[label hs] [[:invalid (:invalid cats)]
                        [:re-scoped (:re-scoped cats)]
                        [:duplicate (:duplicate cats)]]]
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
     :violations (vec @violations)}))

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
                       supplied, the three-classifications-preserved check is
                       included (the threaded invariant for three-member).

   Returns {:holds? bool
            :checks {<check-name> {:holds? bool :violations [...]}}}
   where every named check must hold for the aggregate to hold."
  ([round] (run-review-aggregate-checks round nil))
  ([round canonical-indices]
   (run-review-aggregate-checks round canonical-indices nil))
  ([round canonical-indices report]
   (let [checks (cond-> {:member-bit-width
                         (check-aggregate-member-bit-width round canonical-indices)
                         :member-key-density
                         (check-aggregate-member-key-density round canonical-indices)
                         :three-member-standard
                         (check-aggregate-three-member-standard round)}
                  report
                  (assoc :three-member-classifications
                         (check-aggregate-three-member-classifications
                          round report)))]
     {:holds? (every? :holds? (vals checks))
      :checks checks})))

;; ── Aggregate member-key density consistency ───────────────────────────────

(defn check-aggregate-member-key-density
  "Verify that member key density (dense 0..n-1) is consistent between
   a review round and its canonical-indices artifact (if present).

   Returns {:holds? bool :violations [...]}."
  [round & [canonical-indices]]
  (let [violations (atom [])]
    (when (rr/round-uses-member-keys? round)
      (let [keys (rr/member-keys round)]
        (when (not= keys (range (count keys)))
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
