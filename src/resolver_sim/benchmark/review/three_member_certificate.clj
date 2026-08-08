(ns resolver-sim.benchmark.review.three-member-certificate
  "Three-member research certificate.

   Aggregates three independently produced researcher run reports and
   positions into a review-round package.

   Replication types (in precedence order):
     :exact-replication     — same content-root, model-instance, plan,
                              parameter-domain, sampling-policy,
                              generated-cases, evaluation-policy
     :independent-sampling  — same model and sampling policy, different
                              generated case sets
     :model-corroboration   — same primary model root, different
                              parameterisations; requires explicit policy
     :incompatible-scope    — insufficient common basis for comparison

   Per-dimension consensus classifies member positions into groups:
     supporting-members, qualifying-members, dissenting-members,
     absent-members, insufficient-information-members, not-reviewed-members,
     not-applicable-members

   This prevents absent statuses from being incorrectly folded into
   majority disagreement.

   Theorem/conclusion consensus:
     Researchers submit positions against individual theorem and
     conclusion hashes. The certificate reports per-theorem and
     per-conclusion consensus with the same position-group classification.

   The certificate preserves both aggregate consensus and member-level
   per-dimension detail so the result is independently recomputable
   from referenced positions."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.benchmark.review-round :as rr]
            [resolver-sim.benchmark.review.position-group :as pg]
            [resolver-sim.benchmark.researcher-position :as rp]
            [resolver-sim.benchmark.review-member-canonical-indices :as ci]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const schema-version "three-member-research-certificate.v3")
(def ^:const legacy-schema-version "three-member-research-certificate.v2")
(def ^:const deprecated-schema-version "three-member-research-certificate.v1")

(def ^:const model-dimensions
  [:model-state :model-transitions :model-authority
   :model-adversary :model-parameters :model-cases
   :model-invariants :temporal-fidelity :sampling-policy])

(def ^:const incentive-dimensions
  [:incentives-participants :incentives-strategies :incentives-coalitions])

(def ^:const other-dimensions
  [:reproduction :evidence :claims :publication
   :determinism :provenance])

(def consensus-dimensions
  "Dimensions surfaced as per-dimension consensus in the certificate."
  (into #{} cat [model-dimensions incentive-dimensions other-dimensions]))

(def ^:const qualifying-dimension-statuses
  "Dimension statuses that express assessment-with-qualification rather than
   endorsement (supporting) or rejection (dissenting)."
  #{:publish-with-qualification})

(def ^:const qualifying-target-statuses
  "Theorem/conclusion target statuses expressing assessment-with-qualification."
  #{:qualified})

(defn- hash-reference?
  "A portable content-addressed hash reference.  Older fixtures use short
   digests, so validation deliberately does not impose a digest length here."
  [value]
  (and (string? value) (boolean (re-matches #"sha256:.+" value))))

(defn- position-hash-valid? [position]
  (and (rp/position-valid? position)
       (= (:position/hash position)
          (hash-ref/sha256-ref
           (hc/domain-hash :researcher-position
                           (dissoc position :position/hash))))))

(defn- report-hash-valid? [report]
  ;; A report builder hashes its unsigned, unfinalised representation.
  (let [declared (:researcher-run-report/hash report)
        preimage (assoc report
                        :researcher-run-report/hash nil
                        :researcher/signature nil)]
    (and (hash-reference? declared)
         (= declared (hash-ref/sha256-ref
                      (hc/domain-hash :researcher-run-report preimage))))))

;; ── Outcome grouping ──────────────────────────────────────────────────────

(defn group-outcomes
  "Group researchers by their outcome hash.
   Never derives a synthetic outcome from field-level majorities."
  [reports]
  (let [by-hash (group-by :researcher-run-report/outcome-hash reports)]
    (mapv (fn [[hash rs]]
            {:outcome-hash hash
             :members (mapv :researcher/id rs)
             :count (count rs)})
          by-hash)))

(defn execution-status
  "Determine execution reproducibility status from outcome groups."
  [groups]
  (let [total (reduce + 0 (map :count groups))]
    (cond
      (not= total 3) :incomplete-review-cell
      (= 1 (count groups)) :three-member-replicated
      (= 2 (count groups)) :two-member-corroborated-one-divergent
      :else :three-way-divergent)))

;; ── Replication type ──────────────────────────────────────────────────────

(defn replication-type
  "Classify the comparison scope across three run reports.
   Precedence: exact-replication > independent-sampling > model-corroboration > incompatible-scope."
  [reports]
  (let [n (count reports)]
    (if (not= 3 n)
      :incompatible-scope
      (let [content-roots (set (map :execution/content-root reports))
            model-roots (set (map :execution/model-root reports))
            mi-roots (set (map :execution/model-instance-root reports))
            plan-roots (set (map :execution/plan-root reports))
            domain-roots (set (map :execution/parameter-domain-root reports))
            sampling-roots (set (map :execution/sampling-policy-root reports))
            case-roots (set (map :execution/generated-case-set-root reports))
            eval-policies (set (map :benchmark/evaluation-policy-root reports))]
        (cond
          (and (= 1 (count content-roots))
               (= 1 (count mi-roots))
               (= 1 (count plan-roots))
               (= 1 (count domain-roots))
               (= 1 (count sampling-roots))
               (= 1 (count case-roots))
               (= 1 (count eval-policies)))
          :exact-replication
          (and (= 1 (count content-roots))
               (= 1 (count model-roots))
               (= 1 (count mi-roots))
               (= 1 (count plan-roots))
               (= 1 (count domain-roots))
               (= 1 (count sampling-roots))
               (not= 1 (count case-roots)))
          :independent-sampling
          (and (= 1 (count content-roots))
               (= 1 (count model-roots))
               (= 1 (count mi-roots))
               (= 1 (count plan-roots))
               (or (not= 1 (count domain-roots))
                   (not= 1 (count sampling-roots))
                   (not= 1 (count case-roots))
                   (not= 1 (count eval-policies))))
          :model-corroboration
          :else :incompatible-scope)))))

;; ── Per-dimension consensus with position-groups ─────────────────────────

(declare sort-ids)

(defn- group-members
  "Partition dimension statuses into position groups.
   Returns {:positions entries :all-assessed assessed
            :assessed-members [id] :assessed-statuses [keyword]
            :position-group pg}."
  [positions dimension-key]
  (let [entries (mapv (fn [pos]
                        {:researcher/id (:researcher/id pos)
                         :position-hash (:position/hash pos)
                         :status (get-in pos [:position/dimensions dimension-key :status])
                         :targets (get-in pos [:position/dimensions dimension-key :targets] [])})
                      positions)]
    (loop [remaining entries
           assessed []]
      (if (empty? remaining)
        (let [absent (filter #(nil? (:status %)) entries)
              nr (filter #(= :not-reviewed (:status %)) entries)
              ;; :insufficient-information and :not-evaluable are both
              ;; non-assessments (excluded from majority computation) but are
              ;; kept as distinct groups so the certificate never conflates
              ;; “evidence insufficient” with “cannot meaningfully evaluate”.
              ii (filter #(= :insufficient-information (:status %)) entries)
              ne (filter #(= :not-evaluable (:status %)) entries)
              na (filter #(= :not-applicable (:status %)) entries)]
          {:positions entries
           :all-assessed assessed
           :assessed-members (mapv :researcher/id assessed)
           :assessed-statuses (mapv :status assessed)
           :position-group (pg/position-group
                            :absent (sort-ids (map :researcher/id absent))
                            :not-reviewed (sort-ids (map :researcher/id nr))
                            :insufficient-information (sort-ids (map :researcher/id ii))
                            :not-applicable (sort-ids (map :researcher/id na))
                            :not-evaluable (sort-ids (map :researcher/id ne)))})
        (let [entry (first remaining)
              status (:status entry)]
          (if (or (nil? status) (contains? pg/absent-statuses status))
            (recur (rest remaining) assessed)
            (recur (rest remaining) (conj assessed entry))))))))

(defn- merge-pg
  "Merge computed consensus values into a position-group, preserving
   computed :supporting-members, :qualifying-members, :dissenting-members,
   and other groups."
  [result pg-map]
  (-> (select-keys pg-map
                   [:supporting-members :qualifying-members :dissenting-members
                    :absent-members :not-reviewed-members
                    :insufficient-information-members :not-applicable-members
                    :not-evaluable-members])
      (merge result)))

(defn- sort-ids
  "Canonical member-id ordering (researcher ids are strings).  Every consensus
   member group is emitted in this order so consensus is a pure function of the
   member set, independent of input order."
  [ids]
  (vec (sort ids)))

(defn- classify-assessed
  "Split assessed entries (each with :researcher/id and :status) relative to
   the plurality status into member-id groups:

     supporting — status equals the plurality status
     qualifying — explicit qualification status (:publish-with-qualification,
                  :qualified), distinct from a dissent
     dissenting — remaining assessed (rejection or alternate view)

   Returns {:supporting-members [id] :qualifying-members [id]
            :dissenting-members [id]}."
  [assessed plurality-status qualifying-statuses]
  (let [remaining (remove #(= plurality-status (:status %)) assessed)
        qualifying (filterv #(contains? qualifying-statuses (:status %)) remaining)
        dissenting (remove #(contains? qualifying-statuses (:status %)) remaining)]
    {:supporting-members (sort-ids
                          (map :researcher/id
                               (filter #(= plurality-status (:status %)) assessed)))
     :qualifying-members (sort-ids (map :researcher/id qualifying))
     :dissenting-members (sort-ids (map :researcher/id dissenting))}))

(defn- classify-consensus
  "Classify assessed entries into a full consensus result.

   A plurality status (highest frequency) is never treated as a majority when
   every assessed status is distinct: in that case the cell is :contested, no
   member is labelled supporting, and the per-status breakdown is reported in
   :contested-statuses (which is absent in non-contested cells).

   Returns {:status keyword
            :supporting-members [id]
            :qualifying-members [id]
            :dissenting-members [id]
            :assessed-members [id]
            :assessed-statuses [keyword]
            :contested-statuses [{:status keyword :members [id]}...]}.

   Contract: :unanimous means unanimous among the ASSESSED members — a single
   assessor produces :single-assessment (never the misleading :unanimous), and
   callers attach :assessed-count / :member-count so a 1/3 assessment is
   externally legible as such, not as three researchers agreeing."
  [assessed qualifying-statuses]
  (let [assessed-statuses (mapv :status assessed)]
    (if (= 1 (count (set assessed-statuses)))
      (let [assessed-members (sort-ids (map :researcher/id assessed))]
        {:status (if (= 1 (count assessed)) :single-assessment :unanimous)
         :supporting-members assessed-members
         :qualifying-members []
         :dissenting-members []
         :assessed-members assessed-members
         :assessed-statuses assessed-statuses})
      (let [[plurality-status plurality-count]
            (first (sort-by (comp - val) (frequencies assessed-statuses)))
            {:keys [supporting-members qualifying-members dissenting-members]}
            (classify-assessed assessed plurality-status qualifying-statuses)]
        (if (>= plurality-count 2)
          (let [status (if (seq dissenting-members)
                         :majority-with-dissent
                         :qualified-majority)]
            {:status status
             :supporting-members supporting-members
             :qualifying-members qualifying-members
             :dissenting-members dissenting-members
             :assessed-members (sort-ids (map :researcher/id assessed))
             :assessed-statuses assessed-statuses})
          {:status :contested
           :supporting-members []
           :qualifying-members qualifying-members
           :dissenting-members []
           :assessed-members (sort-ids (map :researcher/id assessed))
           :assessed-statuses assessed-statuses
           :contested-statuses
           (->> assessed
                (group-by :status)
                (map (fn [[st ms]] {:status st :members (sort-ids (map :researcher/id ms))}))
                (sort-by (juxt (comp - count) (comp str :status)))
                vec)})))))

(defn per-dimension-consensus
  "Compute consensus for one dimension, with position-group classification.

   Returns {:status keyword
            :positions [{:researcher/id :position-hash :status :targets}]
            :supporting-members [id...]
            :qualifying-members [id...]
            :dissenting-members [id...]
            :assessed-members [id...]
            :absent-members [id...]
            :insufficient-information-members [id...]
            :not-reviewed-members [id...]
            :not-applicable-members [id...]
            :not-evaluable-members [id...]
            :assessed-count int
            :member-count int
            :contested-statuses [{:status :members}]}  ;; contested cells only

   NOTE: here :absent-members are researchers whose dimension status is nil
   (no position on the dimension). This differs from per-item-consensus, where
   :absent-members are researchers who did not target that theorem/conclusion.
   :member-count is the total panel size and :assessed-count the number who
   formed an assessment, so e.g. a :single-assessment from a 3-member panel is
   legible as 1/3 rather than as three researchers agreeing."
  [positions dimension-key]
  (let [{:keys [positions all-assessed assessed-members
                assessed-statuses position-group]}
        (group-members positions dimension-key)
        n-assessed (count assessed-statuses)
        member-count (count positions)]
    (if (< n-assessed 1)
      (merge-pg {:status :not-evaluable :positions positions
                 :assessed-members assessed-members
                 :assessed-count 0 :member-count member-count}
                position-group)
      (let [{:keys [status supporting-members qualifying-members
                    dissenting-members assessed-members assessed-statuses
                    contested-statuses]}
            (classify-consensus all-assessed qualifying-dimension-statuses)]
        (merge-pg (cond-> {:status status
                           :positions positions
                           :supporting-members supporting-members
                           :qualifying-members qualifying-members
                           :dissenting-members dissenting-members
                           :assessed-members assessed-members
                           :assessed-statuses assessed-statuses
                           :assessed-count (count assessed-members)
                           :member-count member-count}
                    contested-statuses (assoc :contested-statuses contested-statuses))
                  position-group)))))

;; ── Theorem/conclusion-level consensus ────────────────────────────────────

(defn- collect-targets
  "Collect targets by their complete content identity [kind id hash].
   A certificate never silently treats distinct content under one item ID as
   one consensus item."
  [positions kind]
  (let [entries (mapcat (fn [pos]
                          (keep (fn [t]
                                  (when (= kind (:kind t))
                                    {:target/id (:id t)
                                     :target/hash (:hash t)
                                     :researcher/id (:researcher/id pos)
                                     :position/hash (:position/hash pos)
                                     :status (:status t)
                                     :rationale (:rationale t)}))
                                (:position/targets pos [])))
                        positions)]
    (group-by (juxt :kind :target/id :target/hash) entries)))

(defn per-item-consensus
  "Compute consensus for one theorem or conclusion across positions.
   Uses the same position-group classification as per-dimension-consensus.

   Returns {:item/id keyword
            :item/kind :theorem | :conclusion
            :status keyword
            :entries [{:researcher/id :position/hash :status :rationale}]
            :supporting-members [id...]
            :qualifying-members [id...]
            :dissenting-members [id...]
            :assessed-members [id...]
            :absent-members [id...]  ;; researchers who didn't target this item
            :not-reviewed-members [id...]
            :insufficient-information-members [id...]
            :not-applicable-members [id...]
            :not-evaluable-members [id...]
            :assessed-count int
            :member-count int
            :contested-statuses [{:status :members}]}  ;; contested cells only

   NOTE: here :absent-members are researchers who did not target this item
   (non-participants), which differs from per-dimension-consensus where
   :absent-members are researchers whose dimension status is nil.
   :member-count is the total panel size and :assessed-count the number who
   formed an assessment, so e.g. a :single-assessment from a 3-member panel is
   legible as 1/3 rather than as three researchers agreeing."
  [item-id kind entries all-researcher-ids]
  (let [assessed (filter #(not (or (nil? (:status %))
                                   (contains? pg/absent-statuses (:status %))))
                         entries)
        participant-ids (set (map :researcher/id entries))
        non-participants (remove participant-ids all-researcher-ids)
        base-pg (pg/position-group
                 :absent (sort-ids non-participants)
                 :not-reviewed (sort-ids (map :researcher/id
                                              (filter #(= :not-reviewed (:status %)) entries)))
                 ;; :insufficient-information and :not-evaluable are both
                 ;; non-assessments but are kept as distinct groups.
                 :insufficient-information (sort-ids (map :researcher/id
                                                          (filter #(= :insufficient-information (:status %)) entries)))
                 :not-evaluable (sort-ids (map :researcher/id
                                               (filter #(= :not-evaluable (:status %)) entries)))
                 :not-applicable (sort-ids (map :researcher/id
                                                (filter #(= :not-applicable (:status %)) entries))))
        member-count (count all-researcher-ids)]
    (if (< (count assessed) 1)
      (merge base-pg
             {:item/id item-id :item/kind kind :status :not-evaluable :entries entries
              :assessed-members [] :assessed-count 0 :member-count member-count})
      (let [{:keys [status supporting-members qualifying-members
                    dissenting-members assessed-members contested-statuses]}
            (classify-consensus assessed qualifying-target-statuses)]
        (merge base-pg
               (cond-> {:item/id item-id :item/kind kind
                        :entries entries
                        :status status
                        :supporting-members supporting-members
                        :qualifying-members qualifying-members
                        :dissenting-members dissenting-members
                        :assessed-members assessed-members
                        :assessed-count (count assessed-members)
                        :member-count member-count}
                 contested-statuses (assoc :contested-statuses contested-statuses)))))))

(defn per-theorem-consensus
  "Compute consensus for every theorem targeted across positions."
  [positions]
  (let [all-ids (mapv :researcher/id positions)
        theorems (collect-targets positions :theorem)]
    (reduce-kv (fn [m [_ theorem-id theorem-hash] entries]
                 (assoc m [theorem-id theorem-hash]
                        (assoc (per-item-consensus theorem-id :theorem entries all-ids)
                               :item/hash theorem-hash)))
               {}
               theorems)))

(defn per-conclusion-consensus
  "Compute consensus for every conclusion targeted across positions."
  [positions]
  (let [all-ids (mapv :researcher/id positions)
        conclusions (collect-targets positions :conclusion)]
    (reduce-kv (fn [m [_ conclusion-id conclusion-hash] entries]
                 (assoc m [conclusion-id conclusion-hash]
                        (assoc (per-item-consensus conclusion-id :conclusion entries all-ids)
                               :item/hash conclusion-hash)))
               {}
               conclusions)))

;; ── Member-key enrichment ─────────────────────────────────────────────────

(defn- enrich-consensus-with-keys
  "Add integer index vectors to a consensus result.

   Derives indices from the canonical-indices artifact — the sole
   authoritative source for integer member references.
   Always adds indices when canonical-indices is present."
  [consensus canonical-indices]
  (if canonical-indices
    (let [index-fn (fn [id] (ci/review-member-index canonical-indices id))]
      (merge consensus
             (when-let [ids (seq (:supporting-members consensus))]
               {:supporting-member-indices (mapv index-fn ids)})
             (when-let [ids (seq (:qualifying-members consensus))]
               {:qualifying-member-indices (mapv index-fn ids)})
             (when-let [ids (seq (:dissenting-members consensus))]
               {:dissenting-member-indices (mapv index-fn ids)})
             (when-let [ids (seq (:absent-members consensus))]
               {:absent-member-indices (mapv index-fn ids)})
             (when-let [ids (seq (:not-reviewed-members consensus))]
               {:not-reviewed-member-indices (mapv index-fn ids)})
             (when-let [ids (seq (:insufficient-information-members consensus))]
               {:insufficient-information-member-indices (mapv index-fn ids)})
             (when-let [ids (seq (:not-applicable-members consensus))]
               {:not-applicable-member-indices (mapv index-fn ids)})
             (when-let [ids (seq (:not-evaluable-members consensus))]
               {:not-evaluable-member-indices (mapv index-fn ids)})
             (when-let [ids (seq (:assessed-members consensus))]
               {:assessed-member-indices (mapv index-fn ids)})))
    consensus))

;; ── Certificate pre-conditions ───────────────────────────────────────────

(defn- validate-disagreements
  "Validate unresolved-disagreement records against the resolved input cell.

   Each record must bind a round member to a known consensus dimension with a
   valid dimension status and a non-empty rationale, and must be linked to the
   computed consensus: a member who supports the majority on that dimension
   cannot be recorded as disagreeing, and no (researcher/id, dimension) pair
   may be duplicated.

   Returns {:valid? bool :errors [string]}."
  [positions member-ids disagreements]
  (let [errors (atom [])]
    (doseq [[i d] (map-indexed vector (vec (or disagreements [])))]
      (when-not (map? d)
        (swap! errors conj (str "disagreement[" i "] must be a map")))
      (when (map? d)
        (let [member (:researcher/id d)
              dim (:dimension d)
              st (:status d)
              rationale (:rationale d)]
          (when-not (contains? (set member-ids) member)
            (swap! errors conj (str "disagreement[" i "] researcher/id "
                                    (pr-str member) " is not a review-round member")))
          (when-not (contains? consensus-dimensions dim)
            (swap! errors conj (str "disagreement[" i "] dimension "
                                    (pr-str dim) " is not a consensus dimension")))
          (when (and (contains? consensus-dimensions dim)
                     (not (rp/valid-dimension-status? dim st)))
            (swap! errors conj (str "disagreement[" i "] status " (pr-str st)
                                    " is invalid for dimension " (pr-str dim))))
          (when-not (and (string? rationale) (seq rationale))
            (swap! errors conj (str "disagreement[" i "] requires a non-empty :rationale string")))
          (when (and (contains? consensus-dimensions dim) (seq positions))
            (try
              (let [cons (per-dimension-consensus positions dim)
                    supporting (set (:supporting-members cons))]
                (when (contains? supporting member)
                  (swap! errors conj (str "disagreement[" i "] member " (pr-str member)
                                          " is recorded as disagreeing on " (pr-str dim)
                                          " but supports the consensus on that dimension"))))
              (catch clojure.lang.ExceptionInfo _))))))
    (let [pairs (map (juxt :researcher/id :dimension) (vec (or disagreements [])))]
      (when-not (= (count pairs) (count (set pairs)))
        (swap! errors conj "duplicate disagreement for the same (researcher/id, dimension) pair")))
    {:valid? (empty? @errors) :errors @errors}))

(defn pre-certificate-checks
  "Validate the complete, membership-bound three-member input cell.
   This is deliberately stricter than a count check: every supplied artifact
   must join one-to-one with the frozen round membership and canonical index.
   Unresolved-disagreement records are validated and linked to the computed
   per-dimension consensus."
  [{:keys [review-round reports positions canonical-indices disagreements]}]
  (let [errors (atom [])
        member-ids (vec (rr/member-ids review-round))
        report-ids (mapv :researcher/id reports)
        position-ids (mapv :researcher/id positions)
        index-ids (mapv :researcher/id
                        (:review-member/canonical-indices canonical-indices))
        content-root (:benchmark/content-root review-round)
        disagreement-result (validate-disagreements positions member-ids disagreements)]
    (when-not (rr/round-valid? review-round)
      (swap! errors conj "review-round is not a valid frozen three-member round"))
    (when-not (and (= 3 (count member-ids)) (= 3 (count (set member-ids)))
                   (every? string? member-ids))
      (swap! errors conj "review-round must authorize exactly three distinct researcher IDs"))
    (doseq [[label ids] [[:reports report-ids] [:positions position-ids]
                         [:canonical-indices index-ids]]]
      (when-not (and (= 3 (count ids)) (= 3 (count (set ids))))
        (swap! errors conj (str label " must contain exactly one artifact per distinct member")))
      (when-not (= (set member-ids) (set ids))
        (swap! errors conj (str label " researcher IDs must exactly match review-round members"))))
    (when-not (and (= (set report-ids) (set position-ids))
                   (= (set report-ids) (set index-ids)))
      (swap! errors conj "reports, positions, and canonical-indices must have one-to-one member joins"))
    (when-not (= :valid (:status (ci/verify-canonical-indices canonical-indices review-round)))
      (swap! errors conj "canonical-indices is not valid for review-round"))
    (doseq [r reports]
      (when-not (report-hash-valid? r)
        (swap! errors conj (str "report " (:researcher/id r) " has invalid content hash")))
      (when-not (= content-root (:benchmark/content-root r))
        (swap! errors conj (str "report " (:researcher/id r) " content-root does not bind review-round")))
      (when-not (hash-reference? (:researcher-run-report/outcome-hash r))
        (swap! errors conj (str "report " (:researcher/id r) " has missing or malformed outcome-hash"))))
    (doseq [p positions]
      (when-not (position-hash-valid? p)
        (swap! errors conj (str "position " (:researcher/id p) " has invalid content hash")))
      (when-not (= content-root (:benchmark/content-root p))
        (swap! errors conj (str "position " (:researcher/id p) " content-root does not bind review-round")))
      (when-not (hash-reference? (:position/outcome-hash p))
        (swap! errors conj (str "position " (:researcher/id p) " has missing or malformed outcome-hash")))
      (doseq [target (rp/position-targets p)]
        (when-not (and (contains? #{:theorem :conclusion} (:kind target))
                       (keyword? (:id target))
                       (hash-reference? (:hash target)))
          (swap! errors conj (str "position " (:researcher/id p)
                                  " has malformed theorem/conclusion target")))))
    (doseq [id member-ids]
      (let [report (first (filter #(= id (:researcher/id %)) reports))
            position (first (filter #(= id (:researcher/id %)) positions))]
        (when (and report position
                   (not= (:researcher-run-report/outcome-hash report)
                         (:position/outcome-hash position)))
          (swap! errors conj (str "report and position outcome-hash mismatch for " id)))))
    ;; ── Dimension-support divergence (informational, not blocking) ──
    ;; Researchers may legitimately agree on a dimension while citing
    ;; distinct, independently valid evidence.  Support divergence is
    ;; therefore surfaced as a distinct classification (support-divergence)
    ;; that does NOT invalidate the certificate — it records that members
    ;; did not attest to the same evidentiary object.  It is distinct from
    ;; invalid or unreconciled support, which are blocking.
    (let [support-roots (vec (keep :position/dimension-support-root positions))
          distinct-support-roots (set support-roots)
          support-divergence (when (and (> (count support-roots) 1)
                                        (> (count distinct-support-roots) 1))
                               {:support-divergence? true
                                :member-support-roots support-roots
                                :distinct-support-roots (vec distinct-support-roots)})]
      (doseq [e (:errors disagreement-result)]
        (swap! errors conj e))
      {:pre-certificate-valid? (empty? @errors)
       :errors @errors
       :support-divergence support-divergence})))

;; ── Certificate builder ───────────────────────────────────────────────────

(defn build-certificate
  "Build a three-member research certificate.
   Runs pre-certificate-checks before building — throws on invalid input.

   ALWAYS builds and packages a review-member-canonical-indices.v1 artifact
   for every review round (keyed or unkeyed).  The artifact body is returned
   as :review-member-canonical-indices so it can be persisted or
   content-addressed before the certificate hash is committed.

   :canonical-indices — an optional externally-built artifact.  When supplied,
   it is verified against the review round.  When absent, it is auto-produced.

   :supersedes-certificate-root — an optional sha256 reference to the
   certificate this one re-certifies (e.g. a prior schema version of the same
   review scope).  The reference is bound into the certificate body and hash,
   making re-certification an explicit relationship rather than an implicit
   schema bump.

   ORDER-CANONICAL: reports and positions are canonicalized to `:researcher/id`
   order before any derivation, so the certificate is a pure function of the
   member SET — the same three members in any input order commit to the same
   certificate root (consensus member lists, :member-positions, and
   :certificate/inputs are all emitted in canonical member order)."
  [{:keys [review-round reports positions force-authorisations disagreements canonical-indices
           supersedes-certificate-root]
    :or {force-authorisations [] disagreements []}}]
  ;; Every certificate carries the exact resolved source inputs.  This makes
  ;; loaded semantic validation possible without trusting summary fields.  The
  ;; inputs are canonicalized to member-id order so the certificate hash does
  ;; not depend on caller-supplied input order.
  (let [reports (vec (sort-by :researcher/id reports))
        positions (vec (sort-by :researcher/id positions))
        ci-artifact (or canonical-indices (ci/build-canonical-indices review-round))
        _ (when (and supersedes-certificate-root
                     (not (hash-reference? supersedes-certificate-root)))
            (throw (ex-info "Certificate :supersedes-certificate-root must be a sha256 content reference"
                            {:supersedes-certificate-root supersedes-certificate-root})))
        pre-checks (pre-certificate-checks {:review-round review-round
                                            :canonical-indices ci-artifact
                                            :reports reports :positions positions
                                            :disagreements disagreements})]
    (when-not (:pre-certificate-valid? pre-checks)
      (throw (ex-info "Certificate pre-conditions not met"
                      {:errors (:errors pre-checks)})))
    (when (nil? ci-artifact)
      (throw (ex-info "Failed to produce canonical-indices artifact"
                      {:review-round/id (:review-round/id review-round)})))
    (let [outcome-groups (group-outcomes reports)
          exec-status (execution-status outcome-groups)
          rep-type (replication-type reports)
          model-dims model-dimensions
          incentive-dims incentive-dimensions
          other-dims other-dimensions
          support-divergence (:support-divergence pre-checks)]
      (let [body {:schema-version schema-version
                  :benchmark/content-root (:benchmark/content-root review-round)
                  :review-round/id (:review-round/id review-round)
                  :review-round/purpose (:review-round/purpose review-round)
                  :supersedes-certificate-root supersedes-certificate-root
                  :execution
                  {:status exec-status
                   :replication-type rep-type
                   :outcome-groups outcome-groups}
                  :review-member-canonical-indices ci-artifact
                  :review-member-canonical-indices/hash
                  (:review-member-canonical-indices/hash ci-artifact)
                  :certificate/inputs
                  {:version 1
                   :review-round review-round
                   :canonical-indices ci-artifact
                   :reports (vec reports)
                   :positions (vec positions)}
                  :model-consensus
                  (reduce (fn [m dim]
                            (assoc m dim (enrich-consensus-with-keys
                                          (per-dimension-consensus positions dim)
                                          ci-artifact)))
                          {} model-dims)
                  :incentive-consensus
                  (reduce (fn [m dim]
                            (assoc m dim (enrich-consensus-with-keys
                                          (per-dimension-consensus positions dim)
                                          ci-artifact)))
                          {} incentive-dims)
                  :other-consensus
                  (reduce (fn [m dim]
                            (assoc m dim (enrich-consensus-with-keys
                                          (per-dimension-consensus positions dim)
                                          ci-artifact)))
                          {} other-dims)
                  :theorem-consensus
                  (reduce-kv (fn [m k v]
                               (assoc m k (enrich-consensus-with-keys v ci-artifact)))
                             {}
                             (per-theorem-consensus positions))
                  :conclusion-consensus
                  (reduce-kv (fn [m k v]
                               (assoc m k (enrich-consensus-with-keys v ci-artifact)))
                             {}
                             (per-conclusion-consensus positions))
                  :member-positions
                  (mapv (fn [pos]
                          (let [report (get (into {} (map (juxt :researcher/id identity) reports))
                                            (:researcher/id pos))]
                            {:researcher/id (:researcher/id pos)
                             :position/hash (:position/hash pos)
                             :outcome-hash (:position/outcome-hash pos)
                             :report-hash (:researcher-run-report/hash report)
                             :review-member/index (ci/review-member-index
                                                   ci-artifact (:researcher/id pos))}))
                        positions)
                  :force-authorisations (vec force-authorisations)
                  :unresolved-disagreements (vec disagreements)
                  :certificate/hash nil}]
        ;; Support-divergence is bound into the certificate ONLY when it is
        ;; non-nil.  This keeps the v3 hash projection unchanged for
        ;; certificates without divergent support roots (preserving committed
        ;; v2→v3 fixture hashes), while making divergence an explicit committed
        ;; relationship when it occurs.
        (cond-> (assoc body :support-divergence support-divergence)
          (nil? support-divergence) (dissoc :support-divergence))))))

(defn finalise-certificate!
  "Compute the certificate hash and return the finalised certificate.
   The hash projection excludes :certificate/hash only.
   The full :review-member-canonical-indices map is excluded from the
   hash projection; only its committed hash is bound.
   This hash projection is schema-versioned (three-member-research-certificate.v3)
   and must not be changed without a schema version migration."
  [certificate]
  (let [hash-input (dissoc certificate :certificate/hash :review-member-canonical-indices)
        c-hash (hc/domain-hash :three-member-certificate hash-input)]
    (assoc certificate :certificate/hash (hash-ref/sha256-ref  c-hash))))

(defn certificate-valid?
  "Quick structural check for a recomputable v3 certificate."
  [certificate]
  (and (= schema-version (:schema-version certificate))
       (some? (:review-round/id certificate))
       (some? (:benchmark/content-root certificate))
       (map? (:certificate/inputs certificate))
       (some? (:execution certificate))))

(defn certificate-finalised?
  "True when the certificate has been finalised with a hash."
  [certificate]
  (some? (:certificate/hash certificate)))

(defn legacy-certificate-status
  "Distinguish the two legacy verdicts for v1/v2 certificates:

   - :legacy-signature-verifiable — the stored certificate/hash still verifies
     against the stored body (the certificate was signed/committed with the
     original bytes; its self-hash can be checked even though the consensus
     cannot be recomputed from normalized inputs).
   - :legacy-not-recomputable — the stored self-hash cannot be verified.

   Possession of the original signed bytes always permits the first check;
   the current implementation simply cannot reconstruct a v1/v2 body from
   normalized v3 inputs.  This is never reported as validated consensus."
  [certificate]
  (let [body (dissoc certificate :certificate/hash :review-member-canonical-indices)
        declared (:certificate/hash certificate)
        verifies? (and (hash-reference? declared)
                       (= declared
                          (hash-ref/sha256-ref
                           (hc/domain-hash :three-member-certificate body))))]
    (if verifies?
      {:valid? true
       :status :legacy-signature-verifiable
       :errors []
       :note "legacy self-hash verifies against the stored body; consensus is not recomputable from normalized inputs"}
      {:valid? false
       :status :legacy-not-recomputable
       :errors ["legacy certificate self-hash cannot be verified from the stored body"]})))

(defn validate-certificate
  "Validate a loaded certificate and, for v3, independently recompute it from
   its embedded resolved input block.  v1 and v2 are retained as readable
   legacy data but are never reported as validated consensus; they are
   distinguished into :legacy-signature-verifiable (self-hash verifies against
   the stored body) and :legacy-not-recomputable."
  [certificate]
  (cond
    (or (= legacy-schema-version (:schema-version certificate))
        (= deprecated-schema-version (:schema-version certificate)))
    (legacy-certificate-status certificate)

    (not= schema-version (:schema-version certificate))
    {:valid? false :status :invalid-schema
     :errors [(str "expected schema-version " schema-version
                   " got " (:schema-version certificate))]}

    :else
    (let [errors (atom [])
          inputs (:certificate/inputs certificate)]
      (when-not (and (= 1 (:version inputs))
                     (map? (:review-round inputs))
                     (map? (:canonical-indices inputs))
                     (vector? (:reports inputs))
                     (vector? (:positions inputs)))
        (swap! errors conj "missing or malformed recomputable :certificate/inputs"))
      (when (empty? @errors)
        (try
          (let [recomputed (build-certificate
                            {:review-round (:review-round inputs)
                             :canonical-indices (:canonical-indices inputs)
                             :reports (:reports inputs)
                             :positions (:positions inputs)
                             :force-authorisations (:force-authorisations certificate)
                             :disagreements (:unresolved-disagreements certificate)
                             :supersedes-certificate-root
                             (:supersedes-certificate-root certificate)})
                stored-body (dissoc certificate :certificate/hash)
                recomputed-body (dissoc recomputed :certificate/hash)]
            (when-not (= stored-body recomputed-body)
              (swap! errors conj "certificate consensus or source bindings do not recompute from inputs")))
          (catch clojure.lang.ExceptionInfo e
            (swap! errors conj (str "certificate inputs invalid: " (.getMessage e)))))
        (when-not (hash-reference? (:certificate/hash certificate))
          (swap! errors conj "missing or malformed :certificate/hash"))
        (when (hash-reference? (:certificate/hash certificate))
          (let [hash-input (dissoc certificate :certificate/hash :review-member-canonical-indices)
                expected (hash-ref/sha256-ref (hc/domain-hash :three-member-certificate hash-input))]
            (when-not (= expected (:certificate/hash certificate))
              (swap! errors conj "certificate/hash mismatch"))))
        {:valid? (empty? @errors)
         :status (if (empty? @errors) :valid :invalid)
         :errors @errors}))))
