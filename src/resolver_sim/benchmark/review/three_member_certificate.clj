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
  (:require [resolver-sim.hash.canonical :as hc]))

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

(def ^:private absent-statuses
  #{:not-reviewed :insufficient-information :not-applicable})

(defn- member-group
  "Classify a member into one of the position groups."
  [status]
  (cond
    (nil? status) :absent
    (contains? absent-statuses status) status
    :else nil)) ;; non-absent: will be classified by comparison

(defn- group-members
  "Partition dimension statuses into position groups."
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
        {:positions entries
         :all-assessed assessed
         :absent-members (mapv :researcher/id (filter #(= :absent (member-group (:status %))) entries))
         :not-reviewed-members (mapv :researcher/id (filter #(= :not-reviewed (:status %)) entries))
         :insufficient-information-members (mapv :researcher/id (filter #(= :insufficient-information (:status %)) entries))
         :not-applicable-members (mapv :researcher/id (filter #(= :not-applicable (:status %)) entries))
         :assessed-members (mapv :researcher/id assessed)
         :assessed-statuses (mapv :status assessed)}
        (let [entry (first remaining)
              status (:status entry)]
          (if (or (nil? status) (contains? absent-statuses status))
            (recur (rest remaining) assessed)
            (recur (rest remaining) (conj assessed entry))))))))

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
  (let [{:keys [positions entries all-assessed assessed-members
                absent-members not-reviewed-members
                insufficient-information-members not-applicable-members
                assessed-statuses]}
        (group-members positions dimension-key)
        n-assessed (count assessed-statuses)]
    (if (< n-assessed 1)
      {:status :not-evaluable
       :positions positions
       :supporting-members []
       :qualifying-members []
       :dissenting-members []
       :absent-members absent-members
       :insufficient-information-members insufficient-information-members
       :not-reviewed-members not-reviewed-members
       :not-applicable-members not-applicable-members}
      (let [unique-statuses (set assessed-statuses)]
        (if (= 1 (count unique-statuses))
          {:status :unanimous
           :positions positions
           :supporting-members assessed-members
           :qualifying-members []
           :dissenting-members []
           :absent-members absent-members
           :insufficient-information-members insufficient-information-members
           :not-reviewed-members not-reviewed-members
           :not-applicable-members not-applicable-members}
          (let [freqs (frequencies assessed-statuses)
                sorted (sort-by (comp - val) freqs)
                [majority-status majority-count] (first sorted)
                [minority-status _] (second sorted)
                majority-members (mapv :researcher/id
                                       (filter #(= (:status %) majority-status) all-assessed))
                minority-members (mapv :researcher/id
                                       (filter #(not= (:status %) majority-status) all-assessed))]
            {:status (cond
                       (and (= majority-count 2) (some? minority-status)) :majority-with-dissent
                       (= majority-count 2) :qualified-majority
                       :else :contested)
             :positions positions
             :supporting-members majority-members
             :qualifying-members []
             :dissenting-members minority-members
             :absent-members absent-members
             :insufficient-information-members insufficient-information-members
             :not-reviewed-members not-reviewed-members
             :not-applicable-members not-applicable-members}))))))

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
                                   (contains? absent-statuses (:status %))))
                         entries)
        assessed-statuses (mapv :status assessed)
        participant-ids (set (map :researcher/id entries))
        non-participants (remove participant-ids all-researcher-ids)
        n-assessed (count assessed-statuses)]
    (if (< n-assessed 1)
      {:item/id item-id
       :item/kind kind
       :status :not-evaluable
       :entries entries
       :supporting-members []
       :qualifying-members []
       :dissenting-members []
       :absent-members (vec non-participants)
       :not-reviewed-members (mapv :researcher/id
                                   (filter #(= :not-reviewed (:status %)) entries))
       :insufficient-information-members (mapv :researcher/id
                                               (filter #(= :insufficient-information (:status %)) entries))
       :not-applicable-members (mapv :researcher/id
                                     (filter #(= :not-applicable (:status %)) entries))}
      (let [unique-statuses (set assessed-statuses)]
        (if (= 1 (count unique-statuses))
          {:item/id item-id
           :item/kind kind
           :status :unanimous
           :entries entries
           :supporting-members (mapv :researcher/id assessed)
           :qualifying-members []
           :dissenting-members []
           :absent-members (vec non-participants)
           :not-reviewed-members []
           :insufficient-information-members []
           :not-applicable-members []}
          (let [freqs (frequencies assessed-statuses)
                sorted (sort-by (comp - val) freqs)
                [majority-status majority-count] (first sorted)
                [minority-status _] (second sorted)
                majority-members (mapv :researcher/id
                                       (filter #(= (:status %) majority-status) assessed))
                minority-members (mapv :researcher/id
                                       (filter #(not= (:status %) majority-status) assessed))]
            {:item/id item-id
             :item/kind kind
             :status (cond
                       (and (= majority-count 2) (some? minority-status)) :majority-with-dissent
                       (= majority-count 2) :qualified-majority
                       :else :contested)
             :entries entries
             :supporting-members majority-members
             :qualifying-members []
             :dissenting-members minority-members
             :absent-members (vec non-participants)
             :not-reviewed-members []
             :insufficient-information-members []
             :not-applicable-members []}))))))

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

(defn build-certificate
  "Build a three-member research certificate.
   Runs pre-certificate-checks before building — throws on invalid input."
  [{:keys [review-round reports positions force-authorisations disagreements]
    :or {force-authorisations [] disagreements []}}]
  (let [pre-checks (pre-certificate-checks {:review-round review-round
                                            :reports reports
                                            :positions positions})]
    (when-not (:pre-certificate-valid? pre-checks)
      (throw (ex-info "Certificate pre-conditions not met"
                      {:errors (:errors pre-checks)})))
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
       :model-consensus
       (reduce (fn [m dim] (assoc m dim (per-dimension-consensus positions dim)))
               {} model-dims)
       :incentive-consensus
       (reduce (fn [m dim] (assoc m dim (per-dimension-consensus positions dim)))
               {} incentive-dims)
       :other-consensus
       (reduce (fn [m dim] (assoc m dim (per-dimension-consensus positions dim)))
               {} other-dims)
       :theorem-consensus
       (per-theorem-consensus positions)
       :conclusion-consensus
       (per-conclusion-consensus positions)
       :member-positions
       (mapv (fn [pos]
               (let [report (some #(when (= (:researcher/id %)
                                            (:researcher/id pos))
                                     %)
                                  reports)]
                 (when-not report
                   (throw (ex-info "No matching report found for position"
                                   {:researcher/id (:researcher/id pos)})))
                 {:researcher/id (:researcher/id pos)
                  :position/hash (:position/hash pos)
                  :outcome-hash (:position/outcome-hash pos)
                  :report-hash (:researcher-run-report/hash report)}))
             positions)
       :force-authorisations (vec force-authorisations)
       :unresolved-disagreements (vec disagreements)
       :certificate/hash nil})))

(defn finalise-certificate!
  "Compute the certificate hash and return the finalised certificate."
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
    {:valid? (empty? @errors) :errors @errors}))
