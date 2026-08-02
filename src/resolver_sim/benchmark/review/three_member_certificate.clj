(ns resolver-sim.benchmark.review.three-member-certificate
  "Three-member research certificate.

   Aggregates three independently produced researcher run reports and
   positions into a review-round package.

   Replication types (in precedence order):
     :exact-replication     — same content-root, model-instance, plan,
                              parameter-domain, sampling-policy,
                              realised-parameters, generated-cases, evaluation-policy
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
            [resolver-sim.benchmark.review-member-canonical-indices :as ci]))

(def ^:const schema-version "three-member-research-certificate.v2")
(def ^:const legacy-schema-version "three-member-research-certificate.v1")

(defn- hash-reference?
  "A portable content-addressed hash reference.  Older fixtures use short
   digests, so validation deliberately does not impose a digest length here."
  [value]
  (and (string? value) (boolean (re-matches #"sha256:.+" value))))

(defn- position-hash-valid? [position]
  (and (rp/position-valid? position)
       (= (:position/hash position)
          (str "sha256:"
               (hc/domain-hash :researcher-position
                               (dissoc position :position/hash))))))

(defn- report-hash-valid? [report]
  ;; A report builder hashes its unsigned, unfinalised representation.
  (let [declared (:researcher-run-report/hash report)
        preimage (assoc report
                        :researcher-run-report/hash nil
                        :researcher/signature nil)]
    (and (hash-reference? declared)
         (= declared (str "sha256:"
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
            realised-parameter-roots (set (map :execution/realised-parameter-set-root reports))
            case-roots (set (map :execution/generated-case-set-root reports))
            eval-policies (set (map :benchmark/evaluation-policy-root reports))]
        (cond
          (and (= 1 (count content-roots))
               (= 1 (count mi-roots))
               (= 1 (count plan-roots))
               (= 1 (count domain-roots))
               (= 1 (count sampling-roots))
               (= 1 (count realised-parameter-roots))
               (= 1 (count case-roots))
               (= 1 (count eval-policies)))
          :exact-replication
          (and (= 1 (count content-roots))
               (= 1 (count model-roots))
               (= 1 (count mi-roots))
               (= 1 (count plan-roots))
               (= 1 (count domain-roots))
               (= 1 (count sampling-roots))
               (= 1 (count realised-parameter-roots))
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
              ii (filter #(= :insufficient-information (:status %)) entries)
              na (filter #(= :not-applicable (:status %)) entries)]
          {:positions entries
           :all-assessed assessed
           :assessed-members (mapv :researcher/id assessed)
           :assessed-statuses (mapv :status assessed)
           :position-group (pg/position-group
                            :absent (mapv :researcher/id absent)
                            :not-reviewed (mapv :researcher/id nr)
                            :insufficient-information (mapv :researcher/id ii)
                            :not-applicable (mapv :researcher/id na))})
        (let [entry (first remaining)
              status (:status entry)]
          (if (or (nil? status) (contains? pg/absent-statuses status))
            (recur (rest remaining) assessed)
            (recur (rest remaining) (conj assessed entry))))))))

(defn- merge-pg
  "Merge computed consensus values into a position-group, preserving
   computed :supporting-members, :dissenting-members, and other groups."
  [result pg-map]
  (-> (select-keys pg-map
                   [:supporting-members :qualifying-members :dissenting-members
                    :absent-members :not-reviewed-members
                    :insufficient-information-members :not-applicable-members])
      (merge result)))

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
            :not-applicable-members [id...]}

   NOTE: here :absent-members are researchers whose dimension status is nil
   (no position on the dimension). This differs from per-item-consensus, where
   :absent-members are researchers who did not target that theorem/conclusion."
  [positions dimension-key]
  (let [{:keys [positions all-assessed assessed-members
                assessed-statuses position-group]}
        (group-members positions dimension-key)
        n-assessed (count assessed-statuses)]
    (if (< n-assessed 1)
      (merge-pg {:status :not-evaluable :positions positions
                 :assessed-members assessed-members}
                position-group)
      (let [unique-statuses (set assessed-statuses)]
        (if (= 1 (count unique-statuses))
          (merge-pg {:status :unanimous :positions positions
                     :supporting-members assessed-members
                     :assessed-members assessed-members}
                    position-group)
          (let [freqs (frequencies assessed-statuses)
                sorted (sort-by (comp - val) freqs)
                [majority-status majority-count] (first sorted)
                [minority-status _] (second sorted)
                majority-members (mapv :researcher/id
                                       (filter #(= (:status %) majority-status) all-assessed))
                minority-members (mapv :researcher/id
                                       (filter #(not= (:status %) majority-status) all-assessed))]
            (merge-pg {:status (cond
                                 (and (= majority-count 2) (some? minority-status)) :majority-with-dissent
                                 (= majority-count 2) :qualified-majority
                                 :else :contested)
                       :positions positions
                       :supporting-members majority-members
                       :dissenting-members minority-members
                       :assessed-members assessed-members}
                      position-group)))))))

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
            :not-applicable-members [id...]}

   NOTE: here :absent-members are researchers who did not target this item
   (non-participants), which differs from per-dimension-consensus where
   :absent-members are researchers whose dimension status is nil."
  [item-id kind entries all-researcher-ids]
  (let [assessed (filter #(not (or (nil? (:status %))
                                   (contains? pg/absent-statuses (:status %))))
                         entries)
        assessed-statuses (mapv :status assessed)
        assessed-member-ids (mapv :researcher/id assessed)
        participant-ids (set (map :researcher/id entries))
        non-participants (remove participant-ids all-researcher-ids)
        base-pg (pg/position-group
                 :absent (vec non-participants)
                 :not-reviewed (mapv :researcher/id
                                     (filter #(= :not-reviewed (:status %)) entries))
                 :insufficient-information (mapv :researcher/id
                                                 (filter #(= :insufficient-information (:status %)) entries))
                 :not-applicable (mapv :researcher/id
                                       (filter #(= :not-applicable (:status %)) entries)))
        n-assessed (count assessed-statuses)]
    (if (< n-assessed 1)
      (merge base-pg
             {:item/id item-id :item/kind kind :status :not-evaluable :entries entries
              :assessed-members []})
      (let [unique-statuses (set assessed-statuses)]
        (if (= 1 (count unique-statuses))
          (merge base-pg
                 {:item/id item-id :item/kind kind :status :unanimous :entries entries
                  :supporting-members assessed-member-ids
                  :assessed-members assessed-member-ids})
          (let [freqs (frequencies assessed-statuses)
                sorted (sort-by (comp - val) freqs)
                [majority-status majority-count] (first sorted)
                [minority-status _] (second sorted)
                majority-members (mapv :researcher/id
                                       (filter #(= (:status %) majority-status) assessed))
                minority-members (mapv :researcher/id
                                       (filter #(not= (:status %) majority-status) assessed))]
            (merge base-pg
                   {:item/id item-id :item/kind kind
                    :status (cond
                              (and (= majority-count 2) (some? minority-status)) :majority-with-dissent
                              (= majority-count 2) :qualified-majority
                              :else :contested)
                    :entries entries
                    :supporting-members majority-members
                    :dissenting-members minority-members
                    :assessed-members assessed-member-ids})))))))

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
             (when-let [ids (seq (:assessed-members consensus))]
               {:assessed-member-indices (mapv index-fn ids)})))
    consensus))

;; ── Certificate pre-conditions ───────────────────────────────────────────

(defn pre-certificate-checks
  "Validate the complete, membership-bound three-member input cell.
   This is deliberately stricter than a count check: every supplied artifact
   must join one-to-one with the frozen round membership and canonical index."
  [{:keys [review-round reports positions canonical-indices]}]
  (let [errors (atom [])
        member-ids (vec (rr/member-ids review-round))
        report-ids (mapv :researcher/id reports)
        position-ids (mapv :researcher/id positions)
        index-ids (mapv :researcher/id
                        (:review-member/canonical-indices canonical-indices))
        content-root (:benchmark/content-root review-round)]
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
    {:pre-certificate-valid? (empty? @errors) :errors @errors}))

;; ── Certificate builder ───────────────────────────────────────────────────

(defn build-certificate
  "Build a three-member research certificate.
   Runs pre-certificate-checks before building — throws on invalid input.

   ALWAYS builds and packages a review-member-canonical-indices.v1 artifact
   for every review round (keyed or unkeyed).  The artifact body is returned
   as :review-member-canonical-indices so it can be persisted or
   content-addressed before the certificate hash is committed.

   :canonical-indices — an optional externally-built artifact.  When supplied,
   it is verified against the review round.  When absent, it is auto-produced."
  [{:keys [review-round reports positions force-authorisations disagreements canonical-indices]
    :or {force-authorisations [] disagreements []}}]
  ;; Every certificate carries the exact resolved source inputs.  This makes
  ;; loaded semantic validation possible without trusting summary fields.
  (let [ci-artifact (or canonical-indices (ci/build-canonical-indices review-round))
        pre-checks (pre-certificate-checks {:review-round review-round
                                            :canonical-indices ci-artifact
                                            :reports reports :positions positions})]
    (when-not (:pre-certificate-valid? pre-checks)
      (throw (ex-info "Certificate pre-conditions not met"
                      {:errors (:errors pre-checks)})))
    (when (nil? ci-artifact)
      (throw (ex-info "Failed to produce canonical-indices artifact"
                      {:review-round/id (:review-round/id review-round)})))
    (let [outcome-groups (group-outcomes reports)
          exec-status (execution-status outcome-groups)
          rep-type (replication-type reports)
          model-dims [:model-state :model-transitions :model-authority
                      :model-adversary :model-parameters :model-cases]
          incentive-dims [:incentives-participants :incentives-strategies
                          :incentives-coalitions]
          other-dims [:reproduction :evidence :claims :publication]]
      {:schema-version schema-version
       :benchmark/content-root (:benchmark/content-root review-round)
       :review-round/id (:review-round/id review-round)
       :review-round/purpose (:review-round/purpose review-round)
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
       :certificate/hash nil})))

(defn finalise-certificate!
  "Compute the certificate hash and return the finalised certificate.
   The hash projection excludes :certificate/hash only.
   The full :review-member-canonical-indices map is excluded from the
   hash projection; only its committed hash is bound.
   This hash projection is schema-versioned (three-member-research-certificate.v1)
   and must not be changed without a schema version migration."
  [certificate]
  (let [hash-input (dissoc certificate :certificate/hash :review-member-canonical-indices)
        c-hash (hc/domain-hash :three-member-certificate hash-input)]
    (assoc certificate :certificate/hash (str "sha256:" c-hash))))

(defn certificate-valid?
  "Quick structural check for a recomputable v2 certificate."
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

(defn validate-certificate
  "Validate a loaded certificate and, for v2, independently recompute it from
   its embedded resolved input block.  v1 is retained as readable legacy data
   but is never reported as validated consensus."
  [certificate]
  (cond
    (= legacy-schema-version (:schema-version certificate))
    {:valid? false :status :legacy-not-recomputable
     :errors ["legacy certificate lacks resolvable certificate inputs"]}

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
                             :disagreements (:unresolved-disagreements certificate)})
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
                expected (str "sha256:" (hc/domain-hash :three-member-certificate hash-input))]
            (when-not (= expected (:certificate/hash certificate))
              (swap! errors conj "certificate/hash mismatch"))))
        {:valid? (empty? @errors)
         :status (if (empty? @errors) :valid :invalid)
         :errors @errors}))))
