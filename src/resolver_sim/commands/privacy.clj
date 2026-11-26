(ns resolver-sim.commands.privacy
  "Offline inspect, evaluate, and independently verify privacy artifacts."
  (:require [resolver-sim.commands.document :as doc]
            [resolver-sim.privacy.policy :as policy]
            [resolver-sim.privacy.resolution :as resolution]
            [resolver-sim.privacy.storage-query :as storage-query]))

(def ^:private evaluation-fields
  #{:policies :request :classification-report :classification-attestation
    :classification-trust-policy :evidence-attestations :evidence-trust-policy})
(def ^:private resolution-fields
  #{:binding :policy :request :head-observation :head-checkpoint
    :authority-trust-policy :observer-trust-policy :now
    :max-observation-age-seconds})
(def ^:private storage-query-fields
  #{:subject-ref :inventory :storage-trust-policy :authorization
    :authorization-trust-policy :now :max-observation-age-seconds})

(def ^:private stage-output-schema "stablecoin-privacy-stage-output.v2")
(def ^:private stage-decisions #{:allow :deny :indeterminate})
(def ^:private stage-classification-statuses #{:verified :unverified})
(def ^:private stage-evidence-statuses #{:verified :unverified})
(def ^:private stage-sentinel-statuses
  #{:unverified :denied :verified-allow :local-gate-passed})

(defn- has-reason? [reasons code]
  (some #(or (= code %)
             (and (vector? %) (= code (first %))))
        reasons))

(defn- policy-stage-status [evaluation]
  (let [reasons (:privacy-evaluation/reasons evaluation)]
    (cond
      (not= "stablecoin-privacy-evaluation.v1"
            (:privacy-evaluation/schema evaluation)) :not-run
      (has-reason? reasons :request-invalid) :not-run
      (has-reason? reasons :policy-invalid) :invalid
      (has-reason? reasons :policy-scope-or-validity-unresolved) :unresolved
      (or (has-reason? reasons :classification-unverified)
          (has-reason? reasons :evidence-unverified)) :not-run
      (has-reason? reasons :applicable-policy-deny) :denied
      (has-reason? reasons :policy-coverage-or-constraint-failed) :not-permitted
      (= :allow (:privacy-evaluation/decision evaluation)) :permitted
      :else :evaluated)))

(defn- stage-output-projection [evaluation]
  (let [evaluation-available? (= "stablecoin-privacy-evaluation.v1"
                                 (:privacy-evaluation/schema evaluation))
        reasons (:privacy-evaluation/reasons evaluation)
        request-invalid? (has-reason? reasons :request-invalid)
        request-status (cond
                         (not evaluation-available?) :not-run
                         request-invalid? :invalid
                         :else :valid)
        classification-status (cond
                                (or (not evaluation-available?) request-invalid?) :not-run
                                (stage-classification-statuses
                                 (:privacy-evaluation/classification-status evaluation))
                                (:privacy-evaluation/classification-status evaluation)
                                :else :unverified)
        evidence-status (cond
                          (or (not evaluation-available?) request-invalid?) :not-run
                          (stage-evidence-statuses
                           (:privacy-evaluation/evidence-status evaluation))
                          (:privacy-evaluation/evidence-status evaluation)
                          :else :unverified)
        policy-status (policy-stage-status evaluation)
        sentinel-status (cond
                          (or (not evaluation-available?) request-invalid?
                              (has-reason? reasons :classification-unverified)) :not-run
                          (stage-sentinel-statuses
                           (:privacy-evaluation/sentinel-status evaluation))
                          (:privacy-evaluation/sentinel-status evaluation)
                          :else :unverified)
        currentness (:privacy-evaluation/currentness evaluation)
        currentness (cond
                      (or (not evaluation-available?) request-invalid?) :not-run
                      (contains? #{:unknown :historical :current-observed} currentness)
                      currentness
                      :else :unknown)
        stages [{:stage :request :status request-status}
                {:stage :classification :status classification-status}
                {:stage :evidence :status evidence-status}
                {:stage :privacy-policy :status policy-status}
                {:stage :sentinel :status sentinel-status}
                {:stage :authority
                 :status (cond
                           (or (not evaluation-available?) request-invalid?) :not-run
                           (= :verified (:privacy-evaluation/authority-status evaluation))
                           :verified
                           :else :unverified)}
                {:stage :currentness :status currentness}
                {:stage :execution
                 :status (if (or (not evaluation-available?) request-invalid?)
                           :not-run
                           :not-executed)}]]
    {:privacy-stage-output/schema stage-output-schema
     :privacy-stage-output/status :available
     :privacy-stage-output/decision
     (if (stage-decisions (:privacy-evaluation/decision evaluation))
       (:privacy-evaluation/decision evaluation)
       :indeterminate)
     :privacy-stage-output/stages stages
     :privacy-stage-output/usable? false
     :privacy-stage-output/details-omitted? true
     :privacy-stage-output/receipt-included? false}))

(defn- evaluation-input [input]
  (doc/closed! input evaluation-fields #{:sentinel-decision :sentinel-trust-policy})
  (let [policies (:policies input)
        result (policy/evaluate policies (:request input)
                                (select-keys input
                                             [:classification-report
                                              :classification-attestation
                                              :classification-trust-policy
                                              :evidence-attestations
                                              :evidence-trust-policy
                                              :sentinel-decision
                                              :sentinel-trust-policy]))]
    result))

(defn policy-inspect [opts]
  (doc/execute
   opts
   (fn [input]
     (doc/closed! input #{:policy} #{})
     (let [result (policy/validate-policy (:policy input))
           artifact (:policy input)]
       (doc/emit opts
                 {:schema "stablecoin-privacy-policy-inspection.v1"
                  :valid? (:valid? result)
                  :errors (:errors result)
                  :policy/id (:privacy-policy/id artifact)
                  :policy/version (:privacy-policy/version artifact)
                  :policy/root (:privacy-policy/root artifact)
                  :authority-status :unverified
                  :currentness :unknown}
                 (if (:valid? result) 0 1))))))

(defn policy-resolve [opts]
  (let [result
        (try
          (let [input (doc/read-request (:input opts))]
            (doc/closed! input resolution-fields #{})
            (let [resolved (resolution/resolve-policy input)
                  matched (when (:privacy-resolution/valid? resolved)
                            (vec (policy/matching-rules (:policy input)
                                                        (:request input))))
                  denied? (some #(= :deny (:rule/effect %)) matched)
                  permitted? (and (seq matched)
                                  (not denied?)
                                  (every? #(= :allow (:rule/effect %)) matched))
                  retention-limit (when permitted?
                                    (apply min (map :rule/max-retention-seconds matched)))
                  retention-status (cond
                                     (not (:privacy-resolution/valid? resolved)) :unknown
                                     denied? :not-permitted
                                     (not (seq matched)) :no-matching-rule
                                     permitted? :bounded-by-current-observation
                                     :else :unresolved)]
              {:privacy-policy-query/schema "stablecoin-privacy-policy-query.v1"
               :privacy-policy-query/status :available
               :privacy-policy-query/policy-id
               (:privacy-policy/id (:policy input))
               :privacy-policy-query/policy-version
               (:privacy-policy/version (:policy input))
               :privacy-policy-query/policy-root
               (:privacy-policy/root (:policy input))
               :privacy-policy-query/resolution-valid?
               (:privacy-resolution/valid? resolved)
               :privacy-policy-query/currentness
               (:privacy-resolution/currentness resolved)
               :privacy-policy-query/authority-status
               (:privacy-resolution/authority-status resolved)
               :privacy-policy-query/observer-status
               (:privacy-resolution/observer-status resolved)
               :privacy-policy-query/trust-provenance
               (:privacy-resolution/trust-provenance resolved)
               :privacy-policy-query/retention-status retention-status
               :privacy-policy-query/retention-limit-seconds retention-limit
               :privacy-policy-query/matching-rules
               (mapv (fn [rule]
                       {:id (:rule/id rule)
                        :effect (:rule/effect rule)
                        :max-retention-seconds (:rule/max-retention-seconds rule)
                        :required-evidence (:rule/required-evidence rule)})
                     matched)
               :privacy-policy-query/operation-not-authorized? true
               :privacy-policy-query/trust-policy-identity
               {:authority (:privacy-resolution/authority-trust-policy-id resolved)
                :observer (:privacy-resolution/observer-trust-policy-id resolved)}
               :privacy-policy-query/resolution-root
               (:privacy-resolution/root resolved)}))
          (catch Exception _
            {:privacy-policy-query/schema "stablecoin-privacy-policy-query.v1"
             :privacy-policy-query/status :unavailable
             :privacy-policy-query/currentness :unknown
             :privacy-policy-query/retention-status :unknown
             :privacy-policy-query/operation-not-authorized? true}))
        code (if (= :available (:privacy-policy-query/status result))
               0 2)]
    (doc/emit opts result code)))

(defn evaluate [opts]
  (doc/execute
   opts
   (fn [input]
     (let [result (evaluation-input input)
           code (case (:privacy-evaluation/decision result)
                  :allow 0 :deny 1 :indeterminate 2 2)]
       (doc/emit opts result code)))))

(defn stage-output
  "Emit a fixed, privacy-hardened summary without roots or request details.

   Errors are deliberately reduced to the same indeterminate summary; parser
   and verifier exception messages are never copied to stdout or stderr. The
   output is a presentation only, not a receipt or authorization artifact."
  [opts]
  (let [{:keys [evaluation error?]}
        (try
          (let [input (doc/read-request (:input opts))]
            {:evaluation (evaluation-input input)})
          (catch Exception _ {:error? true}))
        result (stage-output-projection evaluation)
        result (cond-> result error? (assoc :privacy-stage-output/status :unavailable))
        code (if error? 2
                 (case (:privacy-stage-output/decision result)
                   :allow 0 :deny 1 :indeterminate 2 2))]
    (doc/emit opts result code)))

(defn- run-storage-query [opts scope]
  (let [result
        (try
          (let [input (doc/read-request (:input opts))
                fields (cond-> storage-query-fields (= scope :event) (conj :event-root))]
            (doc/closed! input fields #{})
            (if (= scope :event)
              (storage-query/query-event input)
              (storage-query/query-wallet input)))
          (catch Exception _
            {:privacy-storage-query/schema "stablecoin-privacy-storage-query.v1"
             :privacy-storage-query/scope scope
             :privacy-storage-query/status :unavailable
             :privacy-storage-query/event-status :unknown
             :privacy-storage-query/storage-trust-status :unverified
             :privacy-storage-query/authorization-status :denied
             :privacy-storage-query/trust-provenance :caller-supplied-policies
             :privacy-storage-query/current-policy-status :not-resolved-by-storage-query
             :privacy-storage-query/usable? false
             :privacy-storage-query/execution :not-executed
             :privacy-storage-query/details-omitted? true}))
        code (case (:privacy-storage-query/status result)
               :available 0 :authorization-denied 1 2)]
    (doc/emit opts result code)))

(defn query-event [opts] (run-storage-query opts :event))
(defn query-wallet [opts] (run-storage-query opts :wallet))

(defn verify [opts]
  (doc/execute
   opts
   (fn [input]
     (doc/closed! input (conj evaluation-fields :evaluation) #{:sentinel-decision
                                                               :sentinel-trust-policy})
     (let [supplied (:evaluation input)
           reconstructed (evaluation-input (dissoc input :evaluation))
           valid? (and (policy/verify-evaluation supplied)
                       (= supplied reconstructed))]
       (doc/emit opts
                 {:schema "stablecoin-privacy-evaluation-verification.v1"
                  :valid? (boolean valid?)
                  :evaluation/root (:privacy-evaluation/root supplied)
                  :reason (when-not valid? :evaluation-reconstruction-mismatch)}
                 (if valid? 0 1))))))
