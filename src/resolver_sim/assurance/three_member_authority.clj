(ns resolver-sim.assurance.three-member-authority
  "Canonical three-member authority evaluation over signed researcher positions
   (ADR-0007 D1; THREE_MEMBER_RESEARCHER_APPLICATION §6/§11).

   Layer boundaries (never collapsed):
     - detect-equivocation          — machine-visible incompatibility, no policy;
     - classify-equivocating-seat   — policy consequence, no re-detection;
     - evaluate-three-member-authority — the full recomputable authority report.

   Detection is deliberately NOT buried inside a threshold counter. Position
   validity is established from a recomputed decision hash (integrity) plus an
   externally supplied signature check (authenticity — keys are outside this
   layer). Input ordering never changes the classification."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.benchmark.researcher-force-authorisation :as rfa]
            [resolver-sim.assurance.canonical-force-authorisation :as cfa]
            [resolver-sim.hash.reference :as hash-ref]))

;; ═══════════════════════════════════════════════════════════════════════════
;; Decision scope and equivocation key
;; ═══════════════════════════════════════════════════════════════════════════

(defn decision-scope-projection
  "Derive the decision-scope identity of a position from the documented
   COMPLETE committed projection: authorisation/id (embedded for v2, else the
   containing authorisation id), review-round/hash, and authorisation/request-root.

   The outcome is deliberately NOT part of the scope: two positions over the
   same scope but different outcomes are exactly the equivocation case. v1
   legacy positions embed no authorisation/id, so their derived scope is weaker
   (:outcome-binding-unavailable)."
  [position authorisation-id]
  (hash-ref/sha256-ref
   (hc/domain-hash :researcher-decision-scope
                   {:authorisation/id (or (:authorisation/id position)
                                          authorisation-id)
                    :review-round/hash (:review-round/hash position)
                    :authorisation/request-root (:authorisation/request-root position)})))

(defn equivocation-key
  "The semantic equivocation key:
     [review-round/hash, authorisation/id, decision-scope, member/id]"
  [position authorisation-id]
  [(:review-round/hash position)
   (or (:authorisation/id position) authorisation-id)
   (decision-scope-projection position authorisation-id)
   (:researcher/id position)])

(defn incompatible-positions?
  "True when two positions under one key cannot simultaneously be the member's
   ONE position for the scope. Any two positions with different committed
   decision hashes are materially different (approve vs dissent, approval of
   different outcome roots, or distinct dissents). Identical duplicates (same
   :decision/hash) are compatible."
  [a b]
  (not= (:decision/hash a) (:decision/hash b)))

(defn incompatibility-reasons
  "Human/machine-readable divergence reasons between two positions."
  [a b]
  (cond
    (not= (:decision a) (:decision b)) [:decision-divergence]
    (and (= :approve (:decision a))
         (let [ra (rfa/position-outcome-root a)
               rb (rfa/position-outcome-root b)]
           (and ra rb (not= ra rb)))) [:distinct-outcome-roots]
    (not= (:dissent/reason a) (:dissent/reason b)) [:distinct-dissent-reasons]
    :else [:distinct-position]))

;; ═══════════════════════════════════════════════════════════════════════════
;; Detection (no policy)
;; ═══════════════════════════════════════════════════════════════════════════

(defn detect-equivocation
  "Detect machine-visible equivocation among VALID positions.

   Groups positions by equivocation key; a member equivocates when it holds two
   or more materially different positions under one key. Identical duplicates
   are compatible and never flagged. Deterministic — input order does not
   change which members are classified as equivocating.

   Returns a vector of groups:
     [{:equivocation/key [...]
       :member/id ...
       :incompatible-positions [pos ...]
       :incompatibility-reasons #{...}} ...]"
  [positions authorisation-id]
  (let [groups (vals (group-by #(equivocation-key % authorisation-id) positions))]
    (vec
     (keep (fn [group]
             (let [distinct-hashes (set (map :decision/hash group))
                   pairs (for [[i a] (map-indexed vector group)
                               b (drop (inc i) group)
                               :when (incompatible-positions? a b)]
                           [a b])]
               (when (> (count distinct-hashes) 1)
                 {:equivocation/key (equivocation-key (first group) authorisation-id)
                  :member/id (:researcher/id (first group))
                  :incompatible-positions
                  (vec (sort-by (juxt :researcher/id :decision/hash)
                                (distinct (mapcat (fn [[a b]] [a b]) pairs))))
                  :incompatibility-reasons
                  (vec (distinct (mapcat (fn [[a b]] (incompatibility-reasons a b)) pairs)))})))
           groups))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Policy consequence (no re-detection)
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:const default-equivocation-policy
  "Default policy: an equivocating seat becomes :invalid — it can neither
   support nor dissent, and it never creates votes."
  :invalid-seat)

(defn classify-equivocating-seat
  "Policy consequence for an equivocating seat. Equivocation never creates
   additional votes and never silently selects the first or last position.

     :invalid-seat        (default) — the seat supports nothing;
     :count-as-dissent    — the seat is counted as one dissent;
     :fail-certificate    — equivocation fails the whole certificate."
  ([_equivocation]
   (classify-equivocating-seat _equivocation default-equivocation-policy))
  ([_equivocation policy]
   (case policy
     :invalid-seat :invalid
     :count-as-dissent :dissent
     :fail-certificate :certificate-fail
     :invalid)))

;; ═══════════════════════════════════════════════════════════════════════════
;; Full authority evaluation
;; ═══════════════════════════════════════════════════════════════════════════

(defn position-valid?
  "A position is valid only when its decision hash recomputes (integrity) AND
   the supplied signature check passes (authenticity). A valid hash alone is
   never sufficient."
  [position authorisation-id signature-valid?]
  (and (rfa/decision-hash-valid? position authorisation-id)
       (true? (signature-valid? position))))

(defn- authoritative-target-outcome
  "The authoritative outcome being decided: the FA target's
   :target/proposed-content-root, obtained independently of any submitted
   position. Plurality NEVER manufactures an outcome identity — two members
   agreeing on an outcome that was never the actual authorisation target is
   exactly the failure this must prevent."
  [authorisation]
  (get-in authorisation [:authorisation/target :target/proposed-content-root]))

(defn evaluate-three-member-authority
  "Evaluate signed positions against the canonical three-member standard.

   opts:
     :authorisation        FA artifact carrying :authorisation/id,
                           :authorisation/request-root,
                           :authorisation/review-round {:review-round/hash},
                           :authorisation/target {:target/proposed-content-root},
                           :authorisation/decision-references
     :review-round         resolved round carrying :review-round/members
     :signature-valid?     fn position -> bool (signature authenticity; keys are
                           external to this layer)
     :profile-opts         optional cfa/declare-profile opts (defaults to the
                           canonical 3/2 profile, declared via :profile-id)
     :equivocation-policy  optional policy for classify-equivocating-seat

   The report recomputes decision-hash validity, scope and outcome concurrence,
   equivocation, seat distinctness, and threshold from committed roots — none
   of these are caller-supplied. Signature authenticity is the only external
   input.

   Outcome concurrence requires EVERY supporter to have signed exactly the
   authoritative target outcome (:target/proposed-content-root, obtained
   independently of the positions). Plurality never manufactures an outcome
   identity; when no authoritative target outcome exists the result classifies
   `:outcome-source :target-outcome-unavailable` and authority is not reached.

   The equivocation-policy consequence is committed: it is taken from the
   review-round's `:review-round/equivocation-policy` when present, else the
   supplied :equivocation-policy, else the canonical default
   `default-equivocation-policy`. The applied policy is reported so the
   consequence is not an implicit verifier-time choice."
  [& {:keys [authorisation review-round signature-valid? profile-opts
             equivocation-policy]}]
  (let [auth-id (:authorisation/id authorisation)
        auth-request-root (:authorisation/request-root authorisation)
        auth-round-hash (get-in authorisation
                                [:authorisation/review-round :review-round/hash])
        authoritative-target-root (authoritative-target-outcome authorisation)
        target-outcome-available? (some? authoritative-target-root)
        members (vec (:review-round/members review-round))
        constituted (set (map :researcher/id members))
        constituted-count (count constituted)
        profile (cfa/declare-profile
                 (or profile-opts
                     {:member-count cfa/canonical-member-count
                      :threshold cfa/canonical-threshold
                      :profile-id "canonical/3-2"}))
        policy-conforming? (cfa/three-member-standard-conforming? profile)
        required (or (and (integer? (:threshold profile)) (:threshold profile))
                     cfa/canonical-threshold)
        positions (vec (:authorisation/decision-references authorisation))
        scope-matches? (fn [p]
                         (and (= auth-request-root (:authorisation/request-root p))
                              (= auth-round-hash (:review-round/hash p))))
        ;; Classification pass over every position
        {unknown-members :unknown-members
         re-scoped :re-scoped
         invalid-positions :invalid-positions
         valid-positions :valid-positions}
        (reduce (fn [acc p]
                  (cond
                    (not (contains? constituted (:researcher/id p)))
                    (update acc :unknown-members conj p)

                    (not (scope-matches? p))
                    (update acc :re-scoped conj p)

                    (not (position-valid? p auth-id signature-valid?))
                    (update acc :invalid-positions conj {:position p
                                                         :reason "integrity-or-signature-failed"})

                    :else
                    (update acc :valid-positions conj p)))
                {:unknown-members [] :re-scoped [] :invalid-positions []
                 :valid-positions []}
                positions)
        valid (vec valid-positions)
        ;; Committed equivocation policy: round policy > supplied option >
        ;; canonical default. The applied policy is surfaced in the report.
        applied-equivocation-policy (or (:review-round/equivocation-policy review-round)
                                        equivocation-policy
                                        default-equivocation-policy)
        ;; Equivocation among valid positions
        equivocations (detect-equivocation valid auth-id)
        equivocating-ids (set (map :member/id equivocations))
        fail-certificate? (= :certificate-fail
                             (classify-equivocating-seat nil
                                                         applied-equivocation-policy))
        equivocating-as-dissent?
        (= :dissent (classify-equivocating-seat nil applied-equivocation-policy))
        non-equivocating (vec (remove #(contains? equivocating-ids (:researcher/id %))
                                      valid))
        ;; Single position per non-equivocating member; identical dupes count once
        by-member (vals (group-by :researcher/id non-equivocating))
        single-positions (vec (keep (fn [ms]
                                      (when (seq ms)
                                        (let [h (first (distinct (map :decision/hash ms)))]
                                          (first (filter #(= h (:decision/hash %)) ms)))))
                                    by-member))
        all-member-positions (group-by :researcher/id valid)
        ;; Identical duplicate submissions (same :decision/hash) from one seat:
        ;; preserved in the report but never counted as extra votes.
        duplicate-seat-positions
        (vec (sort-by (juxt :researcher/id :decision/hash)
                      (mapcat (fn [[mid ps]]
                                (when-not (contains? equivocating-ids mid)
                                  (let [by-hash (group-by :decision/hash ps)]
                                    (mapcat (fn [[_ hs]]
                                              (vec (rest (sort-by :signed-at hs))))
                                            (seq by-hash)))))
                              (seq all-member-positions))))
        supporters (vec (sort-by :researcher/id
                                 (filter #(= :approve (:decision %)) single-positions)))
        dissenters (vec (sort-by :researcher/id
                                 (filter #(= :dissent (:decision %)) single-positions)))
        supporter-outcome-roots (set (map rfa/position-outcome-root supporters))
        ;; Authority requires EVERY supporter to have signed exactly the
        ;; authoritative target root: (= supporter-outcome-roots #{target}).
        ;; A supporter signing any other root (or a v1 position with none) is a
        ;; non-target approver — evidence of non-concurrence on the subject, so
        ;; the concurrence fails closed rather than counting a majority.
        outcome-concurrence?
        (and target-outcome-available?
             (= supporter-outcome-roots #{authoritative-target-root}))
        {target-concurring :counted
         qualifying :qualifying}
        (reduce (fn [acc s]
                  (if (and target-outcome-available?
                           (= authoritative-target-root
                              (rfa/position-outcome-root s)))
                    (update acc :counted conj s)
                    (update acc :qualifying conj s)))
                {:counted [] :qualifying []}
                supporters)
        counted-support (count target-concurring)
        dissent-count (count dissenters)
        effective-dissent-count (if equivocating-as-dissent?
                                  (+ dissent-count (count equivocating-ids))
                                  dissent-count)
        absent (vec (sort (remove (set (map :researcher/id
                                            (concat single-positions
                                                    (mapcat :incompatible-positions
                                                            equivocations))))
                                  (map :researcher/id members))))
        identity-separate? (= constituted-count
                              (count (set (map :researcher/id positions))))
        authority-reached?
        (and (not fail-certificate?)
             policy-conforming?
             (= 3 constituted-count)
             identity-separate?
             outcome-concurrence?
             (>= counted-support required))
        authority-status (if authority-reached? :authorised :not-authorised)
        reasons (cond-> []
                  fail-certificate? (conj :equivocation-fails-certificate)
                  (seq equivocations) (conj :equivocation-present)
                  (not policy-conforming?) (conj :policy-not-conforming)
                  (not= 3 constituted-count) (conj :not-three-constituted-seats)
                  (not identity-separate?) (conj :non-distinct-identities)
                  (not target-outcome-available?)
                  (conj :target-outcome-unavailable)
                  (and target-outcome-available? (seq supporters)
                       (not outcome-concurrence?))
                  (conj :non-target-outcome-concurrence)
                  (< counted-support required) (conj :insufficient-support))]
    {:constituted-member-count constituted-count
     :required-threshold required
     :counted-support counted-support
     :outcome-root (when target-outcome-available?
                     authoritative-target-root)
     :outcome-source (if target-outcome-available?
                       :authoritative-target
                       :target-outcome-unavailable)
     :authoritative-target-root authoritative-target-root
     :decision-scope/root (when (seq valid)
                            (decision-scope-projection (first valid) auth-id))
     :policy-conforming? policy-conforming?
     :identity-separate? identity-separate?
     :valid-supporting-positions target-concurring
     :valid-dissenting-positions dissenters
     :valid-qualifying-positions (vec (sort-by :researcher/id qualifying))
     :effective-dissent-count effective-dissent-count
     :absent-members absent
     :invalid-positions (vec (sort-by :researcher/id
                                      (mapv :position invalid-positions)))
     :equivocating-members equivocations
     :unknown-members (vec (sort-by :researcher/id unknown-members))
     :re-scoped-positions (vec (sort-by :researcher/id re-scoped))
     :duplicate-seat-positions duplicate-seat-positions
     :equivocation-policy-applied applied-equivocation-policy
     :authority-status authority-status
     :authority/reasons reasons}))

;; ═══════════════════════════════════════════════════════════════════════════
;; Authority-report trust boundary
;; ═══════════════════════════════════════════════════════════════════════════
;;
;; Every consumer must either recompute the authority report from committed
;; inputs or bind it into a canonical artifact with a recomputing verifier.
;; A stored classification is never trusted on its own.

(defn authority-report-root
  "Canonical content-addressed root over the recomputed authority report. Binds
   the classification into a consumer artifact so it can be recomputed and
   compared, rather than trusted from storage."
  [report]
  (hash-ref/sha256-ref (hc/domain-hash :three-member-authority-report
                                       (dissoc report :authority/report-root))))

(defn recompute-authority-report
  "The trusted recomputation path for a consumer: re-evaluate the authority
   report from the committed inputs (a map of the same options as
   evaluate-three-member-authority) and compare the recomputed root against a
   previously bound `expected-root`.

   Consumers must either call `evaluate-three-member-authority` directly or
   recompute + compare against this root. A stored classification that fails to
   recompute is rejected.

   Returns
     {:recomputed? bool
      :authority-report-root ...
      :authority-status ...
      :mismatch? bool}."
  [expected-root opts-map]
  (let [report (apply evaluate-three-member-authority
                      (mapcat identity opts-map))
        computed (authority-report-root report)]
    {:recomputed? (= expected-root computed)
     :authority-report-root computed
     :authority-status (:authority-status report)
     :mismatch? (not= expected-root computed)}))
