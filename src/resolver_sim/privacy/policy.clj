(ns resolver-sim.privacy.policy
  "Content-addressed offline privacy policies and request/evaluation receipts.

   Evaluation verifies signed classification and evidence attestations against
   caller-configured trust policies. It never establishes active policy
   authority/currentness and never authorizes a real operation."
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]
            [resolver-sim.signed-external-decision :as signed-decision]
            [resolver-sim.sensitivity.contract :as sentinel-contract]
            [resolver-sim.sensitivity.sentinel-client :as sentinel-client]
            [resolver-sim.sensitivity.sentinel :as sentinel]))

(def schema-version "stablecoin-privacy-policy.v1")
(def request-schema-version "stablecoin-privacy-request.v1")
(def classification-domain "PRF_STABLECOIN_CLASSIFICATION_ATTESTATION_V1")
(def evidence-domain "PRF_STABLECOIN_EVIDENCE_ATTESTATION_V1")
(def ^:private policy-fields
  #{:privacy-policy/schema :privacy-policy/id :privacy-policy/version
    :privacy-policy/issuer :privacy-policy/asset-root
    :privacy-policy/jurisdictions :privacy-policy/valid-from
    :privacy-policy/valid-until :privacy-policy/rules
    :privacy-policy/authority-ref :privacy-policy/root})
(def ^:private rule-fields
  #{:rule/id :rule/effect :rule/data-categories :rule/operations
    :rule/purposes :rule/recipient-ids :rule/recipient-classes :rule/sinks
    :rule/max-sensitivity :rule/max-retention-seconds :rule/required-evidence})
(def ^:private request-required-fields
  #{:request/schema :request/id :request/artifact-root
    :request/classification-report-root :request/issuer :request/asset-root
    :request/jurisdiction :request/at :request/data-category :request/operation
    :request/purpose :request/recipient-id :request/recipient-class
    :request/sink :request/retention-seconds :request/evidence :request/root})
(def ^:private request-optional-fields #{:request/artifact :request/risk-meta})
(def effects #{:allow :deny})
(def sensitivity-levels
  #{:sensitivity/public :sensitivity/internal :sensitivity/private
    :sensitivity/embargoed :sensitivity/critical-private})
(def ^:private sensitivity-rank
  (zipmap [:sensitivity/public :sensitivity/internal :sensitivity/private
           :sensitivity/embargoed :sensitivity/critical-private] (range)))

(defn- non-empty-string? [x] (and (string? x) (seq x)))
(defn- string-vector? [x]
  (and (vector? x) (seq x) (every? non-empty-string? x)
       (= (count x) (count (distinct x)))))
(defn- hash-ref? [x] (ref/valid-sha256-ref? x))

(defn policy-root [p]
  (ref/sha256-ref
   (hc/domain-hash "prf.stablecoin-privacy-policy.v1"
                   (dissoc p :privacy-policy/root))))

(defn- validate-rule [rule]
  (cond
    (not (map? rule)) ["rule is not a map"]
    (not= rule-fields (set (keys rule))) ["rule fields are not closed"]
    (not (non-empty-string? (:rule/id rule))) ["rule id must be a non-empty string"]
    (not (contains? effects (:rule/effect rule))) ["unknown rule effect"]
    (not-every? string-vector? (map rule [:rule/data-categories :rule/operations
                                          :rule/purposes :rule/recipient-ids
                                          :rule/recipient-classes]))
    ["rule match dimensions must be non-empty unique string vectors"]
    (not (and (vector? (:rule/sinks rule)) (seq (:rule/sinks rule))
              (every? sentinel/all-sinks (:rule/sinks rule))))
    ["rule sinks must be known Sentinel sink keywords"]
    (not (contains? sensitivity-levels (:rule/max-sensitivity rule)))
    ["unknown maximum sensitivity"]
    (not (and (integer? (:rule/max-retention-seconds rule))
              (<= 0 (:rule/max-retention-seconds rule))))
    ["maximum retention must be non-negative integer seconds"]
    (not (and (vector? (:rule/required-evidence rule))
              (every? non-empty-string? (:rule/required-evidence rule))
              (= (count (:rule/required-evidence rule))
                 (count (distinct (:rule/required-evidence rule))))))
    ["required evidence must be a vector of unique type names"]
    :else []))

(defn validate-policy [p]
  (let [rules (when (map? p) (:privacy-policy/rules p))
        errors
        (cond-> []
          (not (map? p)) (conj "policy is not a map")
          (and (map? p) (not= policy-fields (set (keys p)))) (conj "policy fields are not closed")
          (and (map? p) (not= schema-version (:privacy-policy/schema p))) (conj "unsupported schema")
          (and (map? p) (not (non-empty-string? (:privacy-policy/id p)))) (conj "policy id required")
          (and (map? p) (not (pos-int? (:privacy-policy/version p)))) (conj "positive policy version required")
          (and (map? p) (not (non-empty-string? (:privacy-policy/issuer p)))) (conj "issuer principal required")
          (and (map? p) (not (hash-ref? (:privacy-policy/asset-root p)))) (conj "asset root must be sha256 ref")
          (and (map? p) (not (string-vector? (:privacy-policy/jurisdictions p)))) (conj "jurisdictions must be non-empty unique vector")
          (and (map? p) (not (integer? (:privacy-policy/valid-from p)))) (conj "valid-from must be epoch seconds")
          (and (map? p) (not (integer? (:privacy-policy/valid-until p)))) (conj "valid-until must be epoch seconds")
          (and (map? p) (integer? (:privacy-policy/valid-from p))
               (integer? (:privacy-policy/valid-until p))
               (>= (:privacy-policy/valid-from p) (:privacy-policy/valid-until p)))
          (conj "validity interval must be non-empty")
          (and (map? p) (not (vector? rules))) (conj "rules must be a vector")
          (and (map? p) (vector? rules) (some seq (map validate-rule rules)))
          (conj "one or more rules are invalid")
          (and (map? p) (vector? rules)
               (not= (count (map :rule/id rules)) (count (distinct (map :rule/id rules)))))
          (conj "duplicate rule id")
          (and (map? p) (not (non-empty-string? (:privacy-policy/authority-ref p))))
          (conj "authority reference required")
          (and (map? p) (not= (:privacy-policy/root p) (policy-root p)))
          (conj "policy root mismatch"))]
    {:valid? (empty? errors) :errors errors}))

(defn policy-valid? [p] (:valid? (validate-policy p)))

(defn build-policy [body]
  (let [p (assoc body :privacy-policy/schema schema-version)
        p (assoc p :privacy-policy/root (policy-root p))
        result (validate-policy p)]
    (when-not (:valid? result)
      (throw (ex-info "invalid stablecoin privacy policy" {:errors (:errors result)})))
    p))

(defn request-root [request]
  (ref/sha256-ref
   (hc/domain-hash "prf.stablecoin-privacy-request.v1"
                   (dissoc request :request/root))))

(defn validate-request [request]
  (let [request-key-set (when (map? request) (set (keys request)))
        evidence (when (map? request) (:request/evidence request))
        valid-evidence? (and (vector? evidence)
                             (every? #(and (map? %)
                                           (= #{:evidence/type :evidence/root} (set (keys %)))
                                           (non-empty-string? (:evidence/type %))
                                           (hash-ref? (:evidence/root %))) evidence)
                             (= (count evidence) (count (distinct (map :evidence/root evidence)))))
        expected (set/union request-required-fields request-optional-fields)
        errors
        (cond-> []
          (not (map? request)) (conj :request-not-map)
          (and (map? request) (not (set/subset? request-required-fields request-key-set)))
          (conj :required-fields-missing)
          (and (map? request) (seq (set/difference request-key-set expected)))
          (conj :unknown-fields)
          (and (map? request) (not= request-schema-version (:request/schema request)))
          (conj :unsupported-schema)
          (and (map? request) (not (non-empty-string? (:request/id request))))
          (conj :request-id-invalid)
          (and (map? request) (not (hash-ref? (:request/artifact-root request))))
          (conj :artifact-root-invalid)
          (and (map? request) (not (hash-ref? (:request/classification-report-root request))))
          (conj :classification-root-invalid)
          (and (map? request) (not (every? non-empty-string?
                                           (map request [:request/issuer :request/jurisdiction
                                                         :request/data-category :request/operation
                                                         :request/purpose :request/recipient-id
                                                         :request/recipient-class]))))
          (conj :request-context-invalid)
          (and (map? request) (not (hash-ref? (:request/asset-root request))))
          (conj :asset-root-invalid)
          (and (map? request) (not (integer? (:request/at request))))
          (conj :request-time-invalid)
          (and (map? request) (not (contains? sentinel/all-sinks (:request/sink request))))
          (conj :sink-invalid)
          (and (map? request) (not (and (integer? (:request/retention-seconds request))
                                        (<= 0 (:request/retention-seconds request)))))
          (conj :retention-invalid)
          (and (map? request) (not valid-evidence?)) (conj :evidence-invalid)
          (and (map? request) (contains? request :request/artifact)
               (not (map? (:request/artifact request)))) (conj :artifact-projection-invalid)
          (and (map? request) (contains? request :request/risk-meta)
               (not (map? (:request/risk-meta request)))) (conj :risk-meta-invalid)
          (and (map? request) (not= (:request/root request) (request-root request)))
          (conj :request-root-mismatch))]
    {:valid? (empty? errors) :errors errors}))

(defn build-request [body]
  (let [request (assoc body :request/schema request-schema-version)
        request (assoc request :request/root (request-root request))
        verification (validate-request request)]
    (when-not (:valid? verification)
      (throw (ex-info "invalid stablecoin privacy request" verification)))
    request))

(defn sensitivity-report-root [report]
  (try
    (when (and (map? report) (= "sensitivity-report.v2" (:schema-version report)))
      (let [body (dissoc report :evaluated-at :report-hash :report-byte-hash
                         :report/semantic-hash)
            expected (hc/hash-with-intent {:hash/intent :evidence-record} body)]
        (when (and (= expected (:report-hash report))
                   (= expected (:report/semantic-hash report)))
          (ref/sha256-ref expected))))
    (catch Exception _ nil)))

(defn verify-classification-attestation
  [request report attestation trust-policy]
  (let [root (sensitivity-report-root report)
        signature (when (map? attestation)
                    (signed-decision/verify-envelope attestation classification-domain
                                                     trust-policy :sensitivity-classifier))
        level (:classification/level attestation)]
    {:valid? (boolean (and (:valid? signature)
                           (= "stablecoin-classification-attestation.v1"
                              (:classification/schema attestation))
                           (= root (:request/classification-report-root request))
                           (= (:request/artifact-root request) (:classification/artifact-root attestation))
                           (= root (:classification/report-root attestation))
                           (contains? sensitivity-levels level)
                           (= (name level) (:run-level report))))
     :report-root root
     :level level
     :reason (cond
               (nil? root) :classification-report-invalid
               (not (:valid? signature)) (or (:reason signature) :classification-attestation-invalid)
               (not= "stablecoin-classification-attestation.v1"
                     (:classification/schema attestation))
               :classification-attestation-schema-invalid
               (not= root (:request/classification-report-root request)) :classification-root-mismatch
               (not= (:request/artifact-root request) (:classification/artifact-root attestation)) :classification-artifact-mismatch
               (not (contains? sensitivity-levels level)) :classification-level-invalid
               (not= (name level) (:run-level report)) :classification-level-report-mismatch
               :else nil)}))

(defn verify-evidence-attestations
  [request attestations trust-policy]
  (let [attestations (if (sequential? attestations) attestations ::invalid)
        items (:request/evidence request)
        verified
        (into {}
              (keep (fn [attestation]
                      (let [signature (signed-decision/verify-envelope
                                       attestation evidence-domain trust-policy
                                       :privacy-evidence-verifier)]
                        (when (and (:valid? signature)
                                   (= "stablecoin-evidence-attestation.v1" (:evidence/schema attestation))
                                   (= :verified (:evidence/status attestation))
                                   (hash-ref? (:evidence/root attestation))
                                   (non-empty-string? (:evidence/type attestation)))
                          [(:evidence/root attestation) (:evidence/type attestation)]))))
              (if (= ::invalid attestations) [] attestations))
        exact? (= (set (map (juxt :evidence/root :evidence/type) items))
                  (set (map (fn [[root type]] [root type]) verified)))]
    {:valid? (and (not= ::invalid attestations) exact?
                  (= (count verified) (count attestations)))
     :verified-roots (set (keys verified))
     :reason (when-not (and (not= ::invalid attestations) exact?
                            (= (count verified) (count attestations)))
               :evidence-attestation-set-invalid)}))

(defn matching-rules [policy request]
  (filter (fn [rule]
            (and (some #{(:request/data-category request)} (:rule/data-categories rule))
                 (some #{(:request/operation request)} (:rule/operations rule))
                 (some #{(:request/purpose request)} (:rule/purposes rule))
                 (some #{(:request/recipient-id request)} (:rule/recipient-ids rule))
                 (some #{(:request/recipient-class request)} (:rule/recipient-classes rule))
                 (some #{(:request/sink request)} (:rule/sinks rule))))
          (:privacy-policy/rules policy)))

(defn evaluation-root [evaluation]
  (ref/sha256-ref
   (hc/domain-hash "prf.stablecoin-privacy-evaluation.v1"
                   (dissoc evaluation :privacy-evaluation/root))))

(defn verify-evaluation [evaluation]
  (and (map? evaluation)
       (= "stablecoin-privacy-evaluation.v1" (:privacy-evaluation/schema evaluation))
       (= (:privacy-evaluation/root evaluation) (evaluation-root evaluation))))

(defn- policies-vector [policies]
  (cond (map? policies) [policies]
        (and (sequential? policies) (every? map? policies)) (vec policies)
        :else []))

(defn evaluate
  "Evaluate one or more immutable policies against a rooted request.

   Options require a signed sensitivity-classifier attestation bound to the
   exact artifact/report roots and signed attestations for every evidence root.
   Results are content-rooted but remain offline and unusable for operations."
  ([policies request] (evaluate policies request {}))
  ([policies request {:keys [classification-report classification-attestation
                             classification-trust-policy evidence-attestations
                             evidence-trust-policy sentinel-decision
                             sentinel-trust-policy]}]
   (let [policies (policies-vector policies)
         request-check (validate-request request)
         policy-checks (mapv validate-policy policies)
         policy-roots (map :privacy-policy/root policies)
         policies-valid? (and (seq policies) (every? :valid? policy-checks)
                              (= (count policy-roots) (count (distinct policy-roots))))
         classification (when (:valid? request-check)
                          (verify-classification-attestation
                           request classification-report classification-attestation
                           classification-trust-policy))
         evidence (when (:valid? request-check)
                    (verify-evidence-attestations request evidence-attestations
                                                  evidence-trust-policy))
         level (:level classification)
         contexts? (and policies-valid?
                        (every? (fn [policy]
                                  (and (= (:privacy-policy/issuer policy) (:request/issuer request))
                                       (= (:privacy-policy/asset-root policy) (:request/asset-root request))
                                       (some #{(:request/jurisdiction request)}
                                             (:privacy-policy/jurisdictions policy))
                                       (<= (:privacy-policy/valid-from policy) (:request/at request))
                                       (< (:request/at request) (:privacy-policy/valid-until policy))))
                                policies))
         per-policy (when (and contexts? (:valid? classification) (:valid? evidence))
                      (mapv (fn [policy]
                              (let [rules (vec (matching-rules policy request))]
                                {:policy-root (:privacy-policy/root policy)
                                 :rules rules
                                 :covered? (seq rules)
                                 :denied? (some #(= :deny (:rule/effect %)) rules)
                                 :allowed? (and (seq rules)
                                                (not-any? #(= :deny (:rule/effect %)) rules)
                                                (some #(= :allow (:rule/effect %)) rules)
                                                (every? #(<= (sensitivity-rank level)
                                                             (sensitivity-rank (:rule/max-sensitivity %))) rules)
                                                (every? #(<= (:request/retention-seconds request)
                                                             (:rule/max-retention-seconds %)) rules)
                                                (every? (set (map :evidence/type
                                                                  (:request/evidence request)))
                                                        (mapcat :rule/required-evidence rules)))}))
                            policies))
         sink-allowed? (and (:valid? classification)
                            (sentinel/disclosure-allowed? level (:request/sink request)))
         remote-required? (or (sentinel/remote-authority-required? (:request/sink request))
                              (sentinel/remote-authority-required-artifact?
                               (:request/artifact request)))
         sentinel-request (when (and remote-required? (:request/artifact request))
                            (sentinel-contract/build-request
                             {:artifact-id (:request/id request)
                              :content {:disclosure/artifact-root (:request/artifact-root request)
                                        :disclosure/view (:request/artifact request)
                                        :privacy/request-root (:request/root request)}
                              :sink (:request/sink request)
                              :declared-level level
                              :risk-meta (:request/risk-meta request)
                              :policy-hash-str (sentinel/policy-hash)}))
         sentinel-verification (when (and remote-required? sentinel-decision
                                          sentinel-trust-policy sentinel-request)
                                 (sentinel-client/verify-decision
                                  {:decision sentinel-decision
                                   :request/id (:request/id sentinel-request)
                                   :request/hash (:request/hash sentinel-request)}
                                  sentinel-request sentinel-trust-policy))
         sentinel-allowed? (or (not remote-required?)
                               (and (:valid? sentinel-verification)
                                    (:allowed? sentinel-verification)))
         all-policy-allow? (and (seq per-policy) (every? :allowed? per-policy))
         any-policy-deny? (some :denied? per-policy)
         decision (cond
                    (not (:valid? request-check)) :indeterminate
                    (not policies-valid?) :indeterminate
                    (not contexts?) :indeterminate
                    (not (:valid? classification)) :indeterminate
                    (not (:valid? evidence)) :indeterminate
                    (not sink-allowed?) :deny
                    (and remote-required? (not sentinel-allowed?)) :indeterminate
                    any-policy-deny? :deny
                    all-policy-allow? :allow
                    :else :deny)
         result {:privacy-evaluation/schema "stablecoin-privacy-evaluation.v1"
                 :privacy-evaluation/request-root (:request/root request)
                 :privacy-evaluation/artifact-root (:request/artifact-root request)
                 :privacy-evaluation/classification-report-root (:request/classification-report-root request)
                 :privacy-evaluation/policy-roots (vec (sort (map :privacy-policy/root policies)))
                 :privacy-evaluation/evidence-roots
                 (vec (sort (map :evidence/root (:request/evidence request))))
                 :privacy-evaluation/decision decision
                 :privacy-evaluation/matched-rule-ids
                 (vec (sort (mapcat (fn [result] (map :rule/id (:rules result))) per-policy)))
                 :privacy-evaluation/authority-status :unverified
                 :privacy-evaluation/currentness :unknown
                 :privacy-evaluation/classification-status (if (:valid? classification) :verified :unverified)
                 :privacy-evaluation/evidence-status (if (:valid? evidence) :verified :unverified)
                 :privacy-evaluation/sentinel-status (cond
                                                       (not (:valid? classification)) :unverified
                                                       (not sink-allowed?) :denied
                                                       remote-required? (if sentinel-allowed? :verified-allow :unverified)
                                                       :else :local-gate-passed)
                 :privacy-evaluation/execution :not-executed
                 :privacy-evaluation/usable? false
                 :privacy-evaluation/reasons
                 (cond-> []
                   (not (:valid? request-check)) (conj [:request-invalid (:errors request-check)])
                   (not policies-valid?) (conj :policy-invalid)
                   (not contexts?) (conj :policy-scope-or-validity-unresolved)
                   (not (:valid? classification)) (conj [:classification-unverified (:reason classification)])
                   (not (:valid? evidence)) (conj [:evidence-unverified (:reason evidence)])
                   (and (:valid? classification) (not sink-allowed?))
                   (conj :sentinel-disclosure-denied)
                   (and remote-required? (not sentinel-allowed?)) (conj :sentinel-remote-signature-unavailable)
                   any-policy-deny? (conj :applicable-policy-deny)
                   (and (seq per-policy) (not all-policy-allow?) (not any-policy-deny?))
                   (conj :policy-coverage-or-constraint-failed))}
         result (assoc result :privacy-evaluation/root (evaluation-root result))]
     result)))
