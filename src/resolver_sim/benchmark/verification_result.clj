(ns resolver-sim.benchmark.verification-result
  "Versioned, derived verification result shared by benchmark evidence validators.")

(def schema-version "prf-verification-result.v1")
(def statuses #{:verified :invalid :not-run :not-applicable :blocked})
(def levels [:structural :integrity :binding :semantic :deployment-bound])
(def check-statuses #{:pass :fail :not-run :not-applicable :blocked})

(defn- level-at-most? [achieved requested]
  (and (some #{achieved} levels)
       (some #{requested} levels)
       (<= (.indexOf ^java.util.List levels achieved)
           (.indexOf ^java.util.List levels requested))))

(defn- result-status [checks requested-level achieved-level]
  (let [required (filter :required? checks)
        statuses (map :check/status checks)]
    (cond
      (some #{:fail} statuses) :invalid
      (some #{:blocked} statuses) :blocked
      (some #(and (:required? %) (= :not-run (:check/status %))) checks) :blocked
      (and (seq required)
           (every? #(= :pass (:check/status %)) required))
      (if (= requested-level achieved-level) :verified :blocked)
      (and (seq checks) (every? #(= :not-applicable (:check/status %)) checks))
      :not-applicable
      :else :not-run)))

(defn build-result
  "Build the common derived verification result. Check outcomes and findings
   must come from the caller's validator execution; producer assertions are
   retained separately and never promote result status."
  [{:keys [subject requested-level achieved-level checks source-roots
           policy mechanism verifier producer-assertions independent-findings
           limitations]}]
  (let [checks (vec checks)
        status (result-status checks requested-level achieved-level)
        invalid-checks (filter #(or (not (keyword? (:check/id %)))
                                    (not (contains? check-statuses
                                                    (:check/status %)))
                                    (not (boolean? (:required? %)))
                                    (and (contains? #{:not-run :not-applicable :blocked}
                                                    (:check/status %))
                                         (not (string? (:check/reason %)))))
                               checks)]
    (when-not (and (map? subject)
                   (some #{requested-level} levels)
                   (or (nil? achieved-level)
                       (level-at-most? achieved-level requested-level))
                   (map? source-roots)
                   (map? verifier)
                   (seq checks)
                   (empty? invalid-checks)
                   (= (count checks) (count (set (map :check/id checks))))
                   (or (nil? policy) (map? policy))
                   (or (nil? mechanism) (map? mechanism))
                   (or (nil? producer-assertions) (map? producer-assertions))
                   (or (nil? independent-findings) (map? independent-findings))
                   (or (nil? limitations) (vector? limitations))
                   (or (not= :verified status)
                       (and achieved-level
                            (every? #(or (not (:required? %))
                                         (= :pass (:check/status %)))
                                    checks))))
      (throw (ex-info "Invalid PRF verification result inputs"
                      {:status status
                       :invalid-checks invalid-checks
                       :requested-level requested-level
                       :achieved-level achieved-level})))
    {:schema-version schema-version
     :subject subject
     :requested-level requested-level
     :achieved-level achieved-level
     :status status
     :checks {:required (->> checks (filter :required?) (map :check/id) sort vec)
              :performed (->> checks
                              (filter #(contains? #{:pass :fail}
                                                  (:check/status %)))
                              (sort-by :check/id) vec)
              :skipped (->> checks
                            (filter #(contains? #{:not-run :not-applicable :blocked}
                                                (:check/status %)))
                            (sort-by :check/id) vec)}
     :source-roots source-roots
     :policy policy
     :mechanism mechanism
     :verifier verifier
     :producer-assertions (or producer-assertions {})
     :independent-findings (or independent-findings {})
     :limitations (vec limitations)}))

(defn validate-result
  "Validate the shape and internal status consistency of a derived result.
   This checks structure only; it does not authenticate its producer."
  [result]
  (let [checks (concat (get-in result [:checks :performed])
                       (get-in result [:checks :skipped]))
        expected (when (seq checks)
                   (result-status checks
                                  (:requested-level result)
                                  (:achieved-level result)))
        errors (cond-> []
                 (not= schema-version (:schema-version result))
                 (conj :unsupported-schema)
                 (not (some #{(:requested-level result)} levels))
                 (conj :invalid-requested-level)
                 (and (:achieved-level result)
                      (not (level-at-most? (:achieved-level result)
                                           (:requested-level result))))
                 (conj :achieved-level-exceeds-request)
                 (not (contains? statuses (:status result)))
                 (conj :invalid-status)
                 (not= expected (:status result))
                 (conj :status-check-mismatch)
                 (not (map? (:subject result)))
                 (conj :missing-subject)
                 (not (map? (:source-roots result)))
                 (conj :missing-source-roots)
                 (not (map? (:verifier result)))
                 (conj :missing-verifier)
                 (not (map? (:independent-findings result)))
                 (conj :missing-independent-findings))]
    {:valid? (empty? errors)
     :errors errors
     :authenticated? false}))

(defn profile-checks
  "Normalize validator check outcomes. Not-applicable checks are optional;
   all other listed checks are required at the requested level."
  [check-statuses reason]
  (mapv (fn [[check-id check-status]]
          (cond-> {:check/id check-id
                   :check/status check-status
                   :required? (not= :not-applicable check-status)}
            (contains? #{:not-run :not-applicable :blocked} check-status)
            (assoc :check/reason (name (or reason :check-not-performed)))))
        (sort-by key check-statuses)))

(defn from-profile
  "Build a common result for a profile validator without changing the
   canonical profile or its hash."
  [{:keys [profile verifier-id verifier-revision requested-level achieved-level
           check-statuses source-roots policy mechanism independent-findings
           limitations reason]}]
  (build-result
   {:subject {:profile/id (:evidence-profile/id profile)
              :profile/schema (:schema-version profile)
              :profile/hash (:evidence-profile/hash profile)}
    :requested-level requested-level
    :achieved-level achieved-level
    :checks (profile-checks check-statuses reason)
    :source-roots source-roots
    :policy policy
    :mechanism mechanism
    :verifier {:id verifier-id
               :revision (or verifier-revision (:schema-version profile))}
    :producer-assertions
    {:verification-level (get-in profile [:evidence-profile/verification-level])
     :verification-status (get-in profile [:evidence-profile/verification-status])
     :verification-checks (get-in profile [:evidence-profile/verification-checks])
     :verification (get-in profile [:evidence-profile/verification])}
    :independent-findings (or independent-findings {})
    :limitations (vec limitations)}))
