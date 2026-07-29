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
              [resolver-sim.benchmark.review-member-canonical-indices :as ci]
              [resolver-sim.trace-metadata :as tm]))

(def ^:const schema-version "three-member-research-certificate.v1")

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
            :absent-members [id...]
            :insufficient-information-members [id...]
            :not-reviewed-members [id...]
            :not-applicable-members [id...]}"
  [positions dimension-key]
  (let [{:keys [positions all-assessed assessed-members
                assessed-statuses position-group]}
        (group-members positions dimension-key)
        n-assessed (count assessed-statuses)]
    (if (< n-assessed 1)
      (merge-pg {:status :not-evaluable :positions positions} position-group)
      (let [unique-statuses (set assessed-statuses)]
        (if (= 1 (count unique-statuses))
          (merge-pg {:status :unanimous :positions positions
                     :supporting-members assessed-members}
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
                       :dissenting-members minority-members}
                      position-group)))))))

;; ── Theorem/conclusion-level consensus ────────────────────────────────────

(defn- collect-targets
  "Collect all targets of a given :kind from positions.
   Returns a map of target-id -> [{:researcher/id :position/hash :status :rationale}]."
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
    (group-by :target/id entries)))

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
            :absent-members [id...]  ;; researchers who didn't target this item
            :not-reviewed-members [id...]
            :insufficient-information-members [id...]
            :not-applicable-members [id...]}"
  [item-id kind entries all-researcher-ids]
  (let [assessed (filter #(not (or (nil? (:status %))
                                   (contains? pg/absent-statuses (:status %))))
                         entries)
        assessed-statuses (mapv :status assessed)
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
             {:item/id item-id :item/kind kind :status :not-evaluable :entries entries})
      (let [unique-statuses (set assessed-statuses)]
        (if (= 1 (count unique-statuses))
          (merge base-pg
                 {:item/id item-id :item/kind kind :status :unanimous :entries entries
                  :supporting-members (mapv :researcher/id assessed)})
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
                    :dissenting-members minority-members})))))))

(defn per-theorem-consensus
  "Compute consensus for every theorem targeted across positions."
  [positions]
  (let [all-ids (mapv :researcher/id positions)
        theorems (collect-targets positions :theorem)]
    (reduce-kv (fn [m theorem-id entries]
                 (assoc m theorem-id
                        (per-item-consensus theorem-id :theorem entries all-ids)))
               {}
               theorems)))

(defn per-conclusion-consensus
  "Compute consensus for every conclusion targeted across positions."
  [positions]
  (let [all-ids (mapv :researcher/id positions)
        conclusions (collect-targets positions :conclusion)]
    (reduce-kv (fn [m conclusion-id entries]
                 (assoc m conclusion-id
                        (per-item-consensus conclusion-id :conclusion entries all-ids)))
               {}
               conclusions)))

;; ── Member-key enrichment ─────────────────────────────────────────────────

(defn- enrich-consensus-with-keys
  "Add integer key vectors to a consensus result when the review round
   has member keys.  Derives keys from the existing researcher-ID vectors
   via canonical-indices when available, or the round's membership table.

   Returns the consensus map unchanged when the round is not keyed."
  [consensus round canonical-indices]
  (if (rr/round-uses-member-keys? round)
    (let [key-fn (if canonical-indices
                   (fn [id] (ci/review-member-index canonical-indices id))
                   (fn [id] (rr/member-key-for-researcher round id)))]
      (merge consensus
             (when-let [ids (seq (:supporting-members consensus))]
               {:supporting-member-keys (mapv key-fn ids)})
             (when-let [ids (seq (:qualifying-members consensus))]
               {:qualifying-member-keys (mapv key-fn ids)})
             (when-let [ids (seq (:dissenting-members consensus))]
               {:dissenting-member-keys (mapv key-fn ids)})
             (when-let [ids (seq (:absent-members consensus))]
               {:absent-member-keys (mapv key-fn ids)})
             (when-let [ids (seq (:not-reviewed-members consensus))]
               {:not-reviewed-member-keys (mapv key-fn ids)})
             (when-let [ids (seq (:insufficient-information-members consensus))]
               {:insufficient-information-member-keys (mapv key-fn ids)})
             (when-let [ids (seq (:not-applicable-members consensus))]
               {:not-applicable-member-keys (mapv key-fn ids)})
             (when-let [ids (seq (:assessed-members consensus))]
               {:assessed-member-keys (mapv key-fn ids)})))
    consensus))

;; ── Certificate pre-conditions ───────────────────────────────────────────

(defn pre-certificate-checks
  "Pre-condition checks that must pass BEFORE building a certificate.
   
   Verifies:
     1. Review round has a content-root
     2. Three reports are provided
     3. Three positions are provided (may contain absent-statuses)
     4. All reports have consistent content-roots
     5. All reports have outcome-hashes (required for grouping)
     6. All positions reference the same content-root
     7. All positions have outcome-hashes
   
   Returns {:pre-certificate-valid? bool :errors [string]}."
  [{:keys [review-round reports positions]}]
  (let [errors (atom [])]
    ;; 1. Review round content root
    (when-not (some? (:benchmark/content-root review-round))
      (swap! errors conj "review-round missing :benchmark/content-root"))
    ;; 2. Three reports
    (when-not (= 3 (count reports))
      (swap! errors conj (str "expected 3 reports, got " (count reports))))
    ;; 3. Three positions
    (when-not (= 3 (count positions))
      (swap! errors conj (str "expected 3 positions, got " (count positions))))
    ;; 4. Report content root consistency
    (let [roots (set (map :benchmark/content-root reports))]
      (when (> (count roots) 1)
        (swap! errors conj (str "inconsistent content-roots across reports: " roots))))
    ;; 5. Reports have outcome-hashes
    (doseq [r reports]
      (when-not (some? (:researcher-run-report/outcome-hash r))
        (swap! errors conj (str "report " (:researcher/id r) " missing outcome-hash"))))
    ;; 6. Position content root consistency
    (let [pos-roots (set (map :benchmark/content-root positions))]
      (when (> (count pos-roots) 1)
        (swap! errors conj (str "inconsistent content-roots across positions: " pos-roots))))
    ;; 7. Positions have outcome-hashes
    (doseq [p positions]
      (when-not (some? (:position/outcome-hash p))
        (swap! errors conj (str "position " (:researcher/id p) " missing outcome-hash"))))
    {:pre-certificate-valid? (empty? @errors) :errors @errors}))

;; ── Certificate builder ───────────────────────────────────────────────────

;; ── Resolution quality derivation ─────────────────────────────────────────

(defn- derive-resolution-quality
  "Derive resolution quality from certificate execution facts.
   Uses classify-resolution-quality from core trace-metadata with
   available evidence: outcome group consistency and execution status.

   Returns a keyword from resolution-outcome-values."
  [{:keys [execution-status outcome-groups]}]
  (let [all-same? (= 1 (count outcome-groups))
        exec-failed? (= :failed execution-status)
        has-dissent? (some #(= :three-way-divergent %) [execution-status])
        _ (when (and has-dissent? all-same?)
            (throw (ex-info "Contradictory: all outcomes same but have dissent" {})))]
    (tm/classify-resolution-quality
     {:authoritative-expected-outcome (when all-same? (first (first outcome-groups)))
      :actual-outcome (first (first outcome-groups))
      :has-unresolved-dissent? has-dissent?
      :verification-facts-complete? (not exec-failed?)})))

(defn build-certificate
  "Build a three-member research certificate.
   Runs pre-certificate-checks before building — throws on invalid input.

   For keyed review rounds, :canonical-indices is REQUIRED — the system
   must have a committed canonical ordering to bind into the certificate.
   For legacy unkeyed rounds, :canonical-indices is prohibited.

   :canonical-indices — a review-member-canonical-indices.v1 artifact.
   When supplied (keyed rounds), it is verified against the review round
   and used for member-key derivation."
  [{:keys [review-round reports positions force-authorisations disagreements canonical-indices]
    :or {force-authorisations [] disagreements []}}]
  (let [pre-checks (pre-certificate-checks {:review-round review-round
                                             :reports reports
                                             :positions positions})]
    (when-not (:pre-certificate-valid? pre-checks)
      (throw (ex-info "Certificate pre-conditions not met"
                      {:errors (:errors pre-checks)})))
    (when (and (rr/round-uses-member-keys? review-round) (nil? canonical-indices))
      (throw (ex-info "Keyed review round requires canonical-indices artifact"
                      {:review-round/id (:review-round/id review-round)})))
    (when (and (not (rr/round-uses-member-keys? review-round)) (some? canonical-indices))
      (throw (ex-info "Canonical-indices supplied for unkeyed legacy round"
                      {:review-round/id (:review-round/id review-round)})))
    (when canonical-indices
      (let [verification (ci/verify-canonical-indices canonical-indices review-round)]
        (when-not (= :valid (:status verification))
          (throw (ex-info "Canonical-indices verification failed"
                          {:errors (:errors verification)
                           :status (:status verification)})))))
    (let [outcome-groups (group-outcomes reports)
          exec-status (execution-status outcome-groups)
           rep-type (replication-type reports)
           ci-artifact (or canonical-indices nil)
           quality (derive-resolution-quality {:execution-status exec-status
                                               :outcome-groups outcome-groups})
           model-dims [:model-state :model-transitions :model-authority
                      :model-adversary :model-parameters :model-cases]
          incentive-dims [:incentives-participants :incentives-strategies
                          :incentives-coalitions]
          other-dims [:reproduction :evidence :claims :publication]]
      (cond-> {:schema-version schema-version
               :benchmark/content-root (:benchmark/content-root review-round)
               :review-round/id (:review-round/id review-round)
               :review-round/purpose (:review-round/purpose review-round)
               :execution
               {:status exec-status
                :replication-type rep-type
                :outcome-groups outcome-groups}
                :resolution/quality quality
                :resolution/confidence (tm/resolution-quality->confidence quality)
               :model-consensus
               (reduce (fn [m dim]
                         (assoc m dim (enrich-consensus-with-keys
                                       (per-dimension-consensus positions dim)
                                       review-round ci-artifact)))
                       {} model-dims)
               :incentive-consensus
               (reduce (fn [m dim]
                         (assoc m dim (enrich-consensus-with-keys
                                       (per-dimension-consensus positions dim)
                                       review-round ci-artifact)))
                       {} incentive-dims)
               :other-consensus
               (reduce (fn [m dim]
                         (assoc m dim (enrich-consensus-with-keys
                                       (per-dimension-consensus positions dim)
                                       review-round ci-artifact)))
                       {} other-dims)
               :member-positions
               (mapv (fn [pos]
                       (let [report (some #(when (= (:researcher/id %)
                                                    (:researcher/id pos))
                                             %)
                                          reports)]
                         (when-not report
                           (throw (ex-info "No matching report found for position"
                                           {:researcher/id (:researcher/id pos)})))
                         (cond-> {:researcher/id (:researcher/id pos)
                                  :position/hash (:position/hash pos)
                                  :outcome-hash (:position/outcome-hash pos)
                                  :report-hash (:researcher-run-report/hash report)}
                           (rr/round-uses-member-keys? review-round)
                           (assoc :review-member/key
                                  (if ci-artifact
                                    (ci/review-member-index ci-artifact (:researcher/id pos))
                                    (rr/member-key-for-researcher
                                     review-round (:researcher/id pos)))))))
                       positions)
               :force-authorisations (vec force-authorisations)
               :unresolved-disagreements (vec disagreements)}
        (rr/round-uses-member-keys? review-round)
        (assoc :theorem-consensus
               (reduce-kv (fn [m k v]
                            (assoc m k (enrich-consensus-with-keys v review-round ci-artifact)))
                          {}
                          (per-theorem-consensus positions))
               :conclusion-consensus
               (reduce-kv (fn [m k v]
                            (assoc m k (enrich-consensus-with-keys v review-round ci-artifact)))
                          {}
                          (per-conclusion-consensus positions)))
        (not (rr/round-uses-member-keys? review-round))
        (assoc :theorem-consensus (per-theorem-consensus positions)
               :conclusion-consensus (per-conclusion-consensus positions))
        ci-artifact
        (assoc :review-member-canonical-indices/hash
               (:review-member-canonical-indices/hash ci-artifact))
        :always
        (assoc :certificate/hash nil)))))

(defn finalise-certificate!
  "Compute the certificate hash and return the finalised certificate.
   The hash projection excludes :certificate/hash only.
   When :review-member-canonical-indices/hash is present, it is included
   in the projection so that a change in canonical ordering changes the
   certificate hash deterministically."
  [certificate]
  (let [hash-input (dissoc certificate :certificate/hash)
        c-hash (hc/domain-hash :three-member-certificate hash-input)]
    (assoc certificate :certificate/hash (str "sha256:" c-hash))))

(defn certificate-valid?
  "Quick structural check for builder-produced certificates."
  [certificate]
  (and (= schema-version (:schema-version certificate))
       (some? (:review-round/id certificate))
       (some? (:benchmark/content-root certificate))
       (some? (:execution certificate))))

(defn certificate-finalised?
  "True when the certificate has been finalised with a hash."
  [certificate]
  (some? (:certificate/hash certificate)))

(defn validate-certificate
  "Standalone validator for a loaded three-member certificate.
   
   Checks schema version, required fields, execution status,
   consensus structure, and member-position references.
   
   Returns {:valid? bool :errors [string]}."
  [certificate]
  (let [errors (atom [])]
    (when-not (= schema-version (:schema-version certificate))
      (swap! errors conj (str "expected schema-version " schema-version
                              " got " (:schema-version certificate))))
    (when-not (some? (:review-round/id certificate))
      (swap! errors conj "missing :review-round/id"))
    (when-not (some? (:benchmark/content-root certificate))
      (swap! errors conj "missing :benchmark/content-root"))
    (when-not (some? (:execution certificate))
      (swap! errors conj "missing :execution"))
    (let [exec (:execution certificate)]
      (when-not (:status exec)
        (swap! errors conj "missing :execution/status"))
      (when-not (:outcome-groups exec)
        (swap! errors conj "missing :execution/outcome-groups")))
     (doseq [f [:model-consensus :incentive-consensus :other-consensus
                :theorem-consensus :conclusion-consensus :member-positions]]
       (when-not (contains? certificate f)
         (swap! errors conj (str "missing " (name f)))))
     (when (some? (:certificate/hash certificate))
       (let [hash-input (dissoc certificate :certificate/hash)
             expected (str "sha256:" (hc/domain-hash :three-member-certificate hash-input))]
         (when-not (= expected (:certificate/hash certificate))
           (swap! errors conj (str "certificate/hash mismatch: declared "
                                   (:certificate/hash certificate)
                                   " computed " expected)))))
      ;; Resolution quality consistency
      (let [quality (:resolution/quality certificate)]
        (when (and quality (not (tm/valid-resolution-quality-for-schema?
                                (:schema-version certificate) quality)))
          (swap! errors conj (str "invalid :resolution/quality: " quality
                                  " for schema " (:schema-version certificate)))))
      (when (= :correct (:resolution/quality certificate))
        (let [exec (:execution certificate)]
          (when (and exec (= :failed (:status exec)))
            (swap! errors conj ":resolution/quality is :correct but execution status is :failed — inconsistent"))))
      (when (= :contested (:resolution/quality certificate))
        (let [exec (:execution certificate)]
          (when (and exec (= 1 (count (:outcome-groups exec))))
            (swap! errors conj ":resolution/quality is :contested but all outcome groups are identical — inconsistent"))))
      ;; Resolution confidence consistency with quality
      (let [quality (:resolution/quality certificate)
            confidence (:resolution/confidence certificate)]
        (when (and quality confidence)
          (let [expected (tm/resolution-quality->confidence quality)]
            (when (and expected (not= expected confidence))
              (swap! errors conj (str ":resolution/confidence " confidence
                                      " does not match derived confidence " expected
                                      " for quality " quality))))))
     {:valid? (empty? @errors) :errors @errors}))
