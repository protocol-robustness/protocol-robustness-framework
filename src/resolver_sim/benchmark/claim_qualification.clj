(ns resolver-sim.benchmark.claim-qualification
  "Deterministic composition of independently executed PRF verification results.

   This is a derived qualification view, not a second evidence graph, evidence
   identity, admission decision, or trust anchor. Validator functions are
   selected by a verifier-controlled profile and must be present in the
   separately supplied trusted registry at the pinned revision."
  (:require [resolver-sim.benchmark.verification-result :as vr]
            [resolver-sim.hash.canonical :as hc]))

(def profile-schema "prf-claim-qualification-profile.v1")
(def result-schema "prf-claim-qualification.v1")

(def dimensions
  #{:integrity :source-binding :semantic-accounting :cryptographic-proof
    :chain-observation :chain-inclusion :finality})

(defn profile-root
  "Commit to the verifier-selected check policy using the existing canonical
   hashing implementation. The root is a derived policy identity, not an
   evidence or deployment identity."
  [profile]
  (hc/domain-hash profile-schema (dissoc profile :qualification-profile/root)))

(defn- valid-profile? [profile]
  (let [checks (:qualification-profile/checks profile)
        ids (map :check/id checks)
        allowed-profile-keys #{:qualification-profile/schema
                               :qualification-profile/id
                               :qualification-profile/checks
                               :qualification-profile/root}]
    (and (map? profile)
         (contains? #{allowed-profile-keys
                      (disj allowed-profile-keys :qualification-profile/root)}
                    (set (keys profile)))
         (= profile-schema (:qualification-profile/schema profile))
         (keyword? (:qualification-profile/id profile))
         (vector? checks) (seq checks)
         (= (count ids) (count (set ids)))
         (or (not (contains? profile :qualification-profile/root))
             (= (:qualification-profile/root profile) (profile-root profile)))
         (every? (fn [check]
                   (and (= #{:check/id :check/dimension :check/validator-id
                             :check/validator-revision :check/required?
                             :check/source-root-keys :check/required-level}
                           (set (keys check)))
                        (keyword? (:check/id check))
                        (contains? dimensions (:check/dimension check))
                        (keyword? (:check/validator-id check))
                        (string? (:check/validator-revision check))
                        (boolean? (:check/required? check))
                        (vector? (:check/source-root-keys check))
                        (every? keyword? (:check/source-root-keys check))
                        (some #{(:check/required-level check)} vr/levels)))
                 checks))))

(defn- valid-subject? [subject]
  (let [cutpoint (:claim/cutpoint subject)
        source-roots (:claim/source-roots subject)]
    (and (map? subject)
         (keyword? (:claim/id subject))
         (map? (:claim/subject subject))
         (map? source-roots)
         (map? cutpoint)
         (keyword? (:cutpoint/id cutpoint))
         (string? (:cutpoint/source-root cutpoint))
         (some #{(:cutpoint/source-root cutpoint)} (vals source-roots)))))

(defn- callback-status [result]
  (let [shape (vr/validate-result result)]
    (if-not (:valid? shape)
      {:status :invalid :reason :malformed-validator-result :errors (:errors shape)}
      {:status (:status result)
       :reason (when-not (= :verified (:status result))
                 (or (:reason result) :validator-did-not-verify))})))

(defn- achieved-required-level? [result required-level]
  (let [levels vr/levels
        required-index (.indexOf ^java.util.List levels required-level)
        achieved-index (.indexOf ^java.util.List levels (:achieved-level result))]
    (and (= required-level (:requested-level result))
         (not (neg? achieved-index))
         (>= achieved-index required-index))))

(defn- run-check [check subject evidence validators trust]
  (let [check-id (:check/id check)
        dimension (:check/dimension check)
        validator-id (:check/validator-id check)
        validator-revision (:check/validator-revision check)
        required-level (:check/required-level check)
        required? (:check/required? check)
        validator (get validators validator-id)
        trusted-revision (get trust validator-id)]
    (cond
      (not= validator-revision trusted-revision)
      {:check/id check-id :check/dimension dimension
       :check/required? required? :check/status :blocked
       :check/reason :validator-not-in-trusted-context
       :check/required-level required-level
       :check/source-root-keys (:check/source-root-keys check)
       :validator {:id validator-id :revision validator-revision}}

      (not (and (map? validator) (= validator-revision (:revision validator))
                (fn? (:run validator))))
      {:check/id check-id :check/dimension dimension
       :check/required? required? :check/status :blocked
       :check/reason :validator-unavailable-or-revision-mismatch
       :check/required-level required-level
       :check/source-root-keys (:check/source-root-keys check)
       :validator {:id validator-id :revision validator-revision}}

      :else
      (try
        (let [{:keys [verification-result observed-subject source-roots findings observations gaps]}
              ((:run validator) evidence check)
              result-state (callback-status verification-result)
              subject-match? (and (= (:claim/subject subject) observed-subject)
                                  (= observed-subject (:subject verification-result)))
              expected-roots (select-keys (:claim/source-roots subject)
                                          (:check/source-root-keys check))
              roots-match? (and (= (set (keys expected-roots))
                                   (set (:check/source-root-keys check)))
                                (= expected-roots source-roots)
                                (= expected-roots
                                   (select-keys (:source-roots verification-result)
                                                (:check/source-root-keys check))))
              verifier-match? (= {:id validator-id :revision validator-revision}
                                 (select-keys (:verifier verification-result)
                                              [:id :revision]))
              level-match? (achieved-required-level? verification-result required-level)
              result-status (:status result-state)
              status (cond
                       (= :invalid result-status) :fail
                       (not verifier-match?) :fail
                       (= :verified result-status)
                       (if (and subject-match? roots-match? level-match?) :pass :fail)
                       (= :not-applicable result-status) :not-applicable
                       (= :not-run result-status) :not-run
                       :else :blocked)]
          {:check/id check-id :check/dimension dimension
           :check/required? required? :check/status status
           :check/reason (when-not (= :pass status)
                           (cond
                             (not (:valid? (vr/validate-result verification-result)))
                             :invalid-verification-result
                             (not verifier-match?) :validator-identity-mismatch
                             (not subject-match?) :claim-subject-mismatch
                             (not roots-match?) :claim-source-root-mismatch
                             (not level-match?) :required-level-not-achieved
                             :else (:reason result-state)))
           :check/required-level required-level
           :check/source-root-keys (:check/source-root-keys check)
           :validator {:id validator-id :revision validator-revision}
           :verification-result verification-result
           :observed-subject observed-subject
           :source-roots source-roots
           :observations (or observations {})
           :findings (or findings {})
           :gaps (vec gaps)})
        (catch Exception e
          {:check/id check-id :check/dimension dimension
           :check/required? required? :check/status :blocked
           :check/reason :validator-execution-failed
           :check/required-level required-level
           :check/source-root-keys (:check/source-root-keys check)
           :validator {:id validator-id :revision validator-revision}
           :error-class (.getName (class e))})))))

(defn qualify
  "Run checks selected by a versioned profile and compose the actual validator
   responses for an exact claim subject and source-root set. The validators map
   binds validator IDs to runner functions and revisions; trusted-validators is
   separately supplied verifier context pinning those revisions. Runners must
   execute PRF validators and return checked subject and roots. Producer labels
   in the evidence are never inputs to the decision.

   Dimensions are independent. Overall status means only that required checks
   in this profile passed; it is not a scalar trust score."
  [{:keys [profile subject evidence validators trusted-validators]}]
  (when-not (valid-profile? profile)
    (throw (ex-info "Invalid claim qualification profile" {})))
  (when-not (valid-subject? subject)
    (throw (ex-info "Claim qualification requires an exact claim subject" {})))
  (let [initial-checks (mapv #(run-check % subject evidence validators trusted-validators)
                             (:qualification-profile/checks profile))
        observation-rows (for [check initial-checks
                               [observation-key value] (:observations check)]
                           [observation-key value (:check/id check)])
        conflict-values (->> observation-rows
                             (group-by first)
                             (keep (fn [[observation-key rows]]
                                     (let [values (set (map second rows))]
                                       (when (> (count values) 1)
                                         [observation-key
                                          {:values (vec (sort-by pr-str values))
                                           :checks (vec (sort (distinct (map #(nth % 2) rows))))}]))))
                             (into (sorted-map)))
        conflicting-check-ids (set (mapcat :checks (vals conflict-values)))
        checks (mapv (fn [check]
                       (if (contains? conflicting-check-ids (:check/id check))
                         (assoc check :check/status :fail
                                :check/reason :conflicting-independent-observations)
                         check))
                     initial-checks)
        required (filter :check/required? checks)
        statuses (map :check/status required)
        status (cond
                 (some #{:fail} statuses) :invalid
                 (some #{:blocked} statuses) :blocked
                 (some #{:not-run :not-applicable} statuses) :blocked
                 (and (seq required) (every? #{:pass} statuses)) :verified
                 :else :not-run)
        dimension-results
        (into (sorted-map)
              (map (fn [[dimension group]]
                     [dimension {:status (cond
                                           (some #(= :fail (:check/status %)) group) :invalid
                                           (some #(= :blocked (:check/status %)) group) :blocked
                                           (some #(and (:check/required? %)
                                                       (not= :pass (:check/status %))) group) :not-run
                                           (every? #(= :not-applicable (:check/status %)) group)
                                           :not-applicable
                                           (some #(not= :pass (:check/status %)) group) :not-run
                                           (seq group) :verified
                                           :else :not-applicable)
                                 :checks (mapv :check/id group)}]))
              (group-by :check/dimension checks))]
    {:schema result-schema :qualification-profile/id (:qualification-profile/id profile) :qualification-profile/root (profile-root profile) :claim subject :status status :dimensions dimension-results :checks checks :conflicts conflict-values :gaps (vec (mapcat (fn [check] (map #(assoc % :check/id (:check/id check)) (:gaps check))) checks)) :producer-assertions (into (sorted-map) (keep (fn [check] (when-let [result (:verification-result check)] [(:check/id check) (:producer-assertions result)])) checks)) :independent-findings (into (sorted-map) (map (juxt :check/id :findings) checks)) :limitations [:derived-report-not-canonical-evidence :trust-context-must-be-sourced-out-of-band :no-chain-inclusion-or-finality-unless-explicitly-checked :status-means-required-profile-checks-passed-not-overall-trust]}))

(defn validate-qualification
  "Check the derived report's shape and status consistency. This does not
   authenticate validator execution or the trust context."
  [qualification]
  (let [checks (:checks qualification)
        required (filter :check/required? checks)
        statuses (map :check/status required)
        expected (cond
                   (some #{:fail} statuses) :invalid
                   (some #{:blocked} statuses) :blocked
                   (some #{:not-run :not-applicable} statuses) :blocked
                   (and (seq required) (every? #{:pass} statuses)) :verified
                   :else :not-run)
        errors (cond-> []
                 (not= result-schema (:schema qualification))
                 (conj :unsupported-schema)
                 (not (valid-subject? (:claim qualification)))
                 (conj :invalid-claim-subject)
                 (not= expected (:status qualification))
                 (conj :status-check-mismatch)
                 (not= (count checks) (count (set (map :check/id checks))))
                 (conj :duplicate-check-id))]
    {:valid? (empty? errors) :errors errors :authenticated? false}))
