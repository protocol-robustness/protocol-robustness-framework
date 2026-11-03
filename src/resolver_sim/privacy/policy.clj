(ns resolver-sim.privacy.policy
  "Immutable, closed-shape stablecoin privacy policy contracts. This namespace
   validates policy integrity and evaluates caller-supplied context offline.
   It does not verify the data classification, establish policy authority or
   currentness, or authorize an operation; every result remains unusable."
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]
            [resolver-sim.sensitivity.contract :as sentinel-contract]
            [resolver-sim.sensitivity.sentinel-client :as sentinel-client]
            [resolver-sim.sensitivity.sentinel :as sentinel]))

(def schema-version "stablecoin-privacy-policy.v1")
(def ^:private policy-fields
  #{:privacy-policy/schema :privacy-policy/id :privacy-policy/version
    :privacy-policy/issuer :privacy-policy/asset-root
    :privacy-policy/jurisdictions :privacy-policy/valid-from
    :privacy-policy/valid-until :privacy-policy/rules
    :privacy-policy/authority-ref :privacy-policy/root})
(def ^:private rule-fields
  #{:rule/id :rule/effect :rule/data-categories :rule/operations
    :rule/purposes :rule/recipients :rule/sinks :rule/max-sensitivity
    :rule/max-retention-seconds :rule/required-evidence})
(def effects #{:allow :deny})
(def sensitivity-levels
  #{:sensitivity/public :sensitivity/internal :sensitivity/private
    :sensitivity/embargoed :sensitivity/critical-private})
(def ^:private sensitivity-rank
  (zipmap [:sensitivity/public :sensitivity/internal :sensitivity/private
           :sensitivity/embargoed :sensitivity/critical-private] (range)))
(defn- non-empty-string? [x] (and (string? x) (seq x)))
(defn- set-of-strings? [x] (and (vector? x) (seq x) (every? non-empty-string? x)))
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
    (not-every? set-of-strings? (map rule [:rule/data-categories :rule/operations
                                           :rule/purposes :rule/recipients]))
    ["rule match dimensions must be non-empty vectors of strings"]
    (not (and (vector? (:rule/sinks rule)) (seq (:rule/sinks rule))
              (every? sentinel/all-sinks (:rule/sinks rule))))
    ["rule sinks must be known Sentinel sink keywords"]
    (not (contains? sensitivity-levels (:rule/max-sensitivity rule)))
    ["unknown maximum sensitivity"]
    (not (and (integer? (:rule/max-retention-seconds rule))
              (<= 0 (:rule/max-retention-seconds rule))))
    ["maximum retention must be non-negative integer seconds"]
    (not (vector? (:rule/required-evidence rule))) ["required evidence must be a vector"]
    (not-every? non-empty-string? (:rule/required-evidence rule)) ["required evidence entries must be non-empty strings"]
    :else []))
(defn validate-policy [p]
  (let [errors
        (cond-> []
          (not (map? p)) (conj "policy is not a map")
          (and (map? p) (not= policy-fields (set (keys p)))) (conj "policy fields are not closed")
          (and (map? p) (not= schema-version (:privacy-policy/schema p))) (conj "unsupported schema")
          (and (map? p) (not (non-empty-string? (:privacy-policy/id p)))) (conj "policy id required")
          (and (map? p) (not (pos-int? (:privacy-policy/version p)))) (conj "positive policy version required")
          (and (map? p) (not (non-empty-string? (:privacy-policy/issuer p)))) (conj "issuer principal required")
          (and (map? p) (not (ref/valid-sha256-ref? (:privacy-policy/asset-root p)))) (conj "asset root must be sha256 ref")
          (and (map? p) (not (set-of-strings? (:privacy-policy/jurisdictions p)))) (conj "jurisdictions must be non-empty vector")
          (and (map? p) (not (integer? (:privacy-policy/valid-from p)))) (conj "valid-from must be epoch seconds")
          (and (map? p) (not (integer? (:privacy-policy/valid-until p)))) (conj "valid-until must be epoch seconds")
          (and (map? p) (integer? (:privacy-policy/valid-from p))
               (integer? (:privacy-policy/valid-until p))
               (>= (:privacy-policy/valid-from p) (:privacy-policy/valid-until p))) (conj "validity interval must be non-empty")
          (and (map? p) (not (vector? (:privacy-policy/rules p)))) (conj "rules must be a vector")
          (and (map? p) (not (non-empty-string? (:privacy-policy/authority-ref p)))) (conj "authority reference required")
          (and (map? p) (vector? (:privacy-policy/rules p))
               (some seq (map validate-rule (:privacy-policy/rules p)))) (conj "one or more rules are invalid")
          (and (map? p) (vector? (:privacy-policy/rules p))
               (not= (count (map :rule/id (:privacy-policy/rules p)))
                     (count (distinct (map :rule/id (:privacy-policy/rules p)))))) (conj "duplicate rule id")
          (and (map? p) (not= (:privacy-policy/root p) (policy-root p))) (conj "policy root mismatch"))]
    {:valid? (empty? errors) :errors errors}))
(defn policy-valid? [p] (:valid? (validate-policy p)))
(defn build-policy [body]
  (let [p (assoc body :privacy-policy/schema schema-version)
        p (assoc p :privacy-policy/root (policy-root p))
        result (validate-policy p)]
    (when-not (:valid? result)
      (throw (ex-info "invalid stablecoin privacy policy" {:errors (:errors result)})))
    p))
(defn matching-rules [policy request]
  (filter (fn [r]
            (and (some #{(:request/data-category request)} (:rule/data-categories r))
                 (some #{(:request/operation request)} (:rule/operations r))
                 (some #{(:request/purpose request)} (:rule/purposes r))
                 (some #{(:request/recipient request)} (:rule/recipients r))
                 (some #{(:request/sink request)} (:rule/sinks r))))
          (:privacy-policy/rules policy)))
(defn evaluate
  "Deterministic offline rule evaluation. Authority remains unverified; this
   function never authorizes a live operation."
  ([policy request] (evaluate policy request {}))
  ([policy request {:keys [sentinel-decision sentinel-trust-policy]}]
  (let [validity (validate-policy policy)
        sensitivity-valid? (contains? sensitivity-levels (:request/sensitivity request))
        request-valid? (and (map? request)
                            (every? non-empty-string? (map request [:request/issuer
                                                                     :request/jurisdiction
                                                                     :request/data-category
                                                                     :request/operation
                                                                     :request/purpose
                                                                     :request/recipient]))
                            (ref/valid-sha256-ref? (:request/asset-root request))
                            (integer? (:request/at request))
                            sensitivity-valid?
                            (contains? sentinel/all-sinks (:request/sink request))
                            (integer? (:request/retention-seconds request))
                            (<= 0 (:request/retention-seconds request))
                            (vector? (:request/verified-evidence request))
                            (every? non-empty-string? (:request/verified-evidence request)))
        context (and request-valid?
                     (= (:privacy-policy/issuer policy) (:request/issuer request))
                     (= (:privacy-policy/asset-root policy) (:request/asset-root request))
                     (some #{(:request/jurisdiction request)} (:privacy-policy/jurisdictions policy))
                     (<= (:privacy-policy/valid-from policy) (:request/at request))
                     (< (:request/at request) (:privacy-policy/valid-until policy)))
        matches (when (and (:valid? validity) context) (vec (matching-rules policy request)))
        sink-allowed? (and sensitivity-valid?
                           (sentinel/disclosure-allowed? (:request/sensitivity request)
                                                         (:request/sink request)))
        remote-required? (or (sentinel/remote-authority-required? (:request/sink request))
                            (sentinel/remote-authority-required-artifact?
                             (:request/artifact request)))
        sentinel-request (when (and remote-required? (map? (:request/artifact request))
                                    (string? (:request/id request)))
                           (sentinel-contract/build-request
                            {:artifact-id (:request/id request)
                             :content {:disclosure/artifact (:request/artifact request)
                                       :privacy/context (select-keys request
                                                                     [:request/issuer
                                                                      :request/asset-root
                                                                      :request/jurisdiction
                                                                      :request/operation
                                                                      :request/purpose
                                                                      :request/recipient
                                                                      :request/retention-seconds])}
                             :sink (:request/sink request)
                             :declared-level (:request/sensitivity request)
                             :risk-meta (:request/risk-meta request)
                             :policy-hash-str (sentinel/policy-hash)}))
        remote-result (when (and remote-required? sentinel-decision sentinel-trust-policy
                                 sentinel-request)
                        (sentinel-client/verify-decision
                         {:decision sentinel-decision
                          :request/id (:request/id sentinel-request)
                          :request/hash (:request/hash sentinel-request)}
                         sentinel-request sentinel-trust-policy))
        remote-allowed? (and (:valid? remote-result) (:allowed? remote-result))
        allowed (and request-valid? sink-allowed? (or (not remote-required?) remote-allowed?) (seq matches)
                     (not-any? #(= :deny (:rule/effect %)) matches)
                     (some #(= :allow (:rule/effect %)) matches)
                     (every? #(<= (sensitivity-rank (:request/sensitivity request))
                                  (sensitivity-rank (:rule/max-sensitivity %))) matches)
                     (every? #(<= (:request/retention-seconds request)
                                  (:rule/max-retention-seconds %)) matches)
                     (every? (set (:request/verified-evidence request))
                             (mapcat :rule/required-evidence matches)))
        result (cond (not (:valid? validity)) :indeterminate
                     (not request-valid?) :indeterminate
                     (not context) :indeterminate
                     (not sensitivity-valid?) :indeterminate
                     (not sink-allowed?) :deny
                     (and remote-required? (not remote-allowed?)) :indeterminate
                     allowed :allow
                     :else :deny)]
    {:privacy-evaluation/schema "stablecoin-privacy-evaluation.v1"
     :privacy-evaluation/policy-root (:privacy-policy/root policy)
     :privacy-evaluation/request request
     :privacy-evaluation/decision result
     :privacy-evaluation/matched-rule-ids (mapv :rule/id matches)
     :privacy-evaluation/authority-status :unverified
     :privacy-evaluation/currentness :unknown
     :privacy-evaluation/sentinel-decision (cond (not sink-allowed?) :denied
                                                 remote-allowed? :verified-allow
                                                 remote-required? (or (:reason remote-result) :remote-decision-required)
                                                 :else :local-gate-passed)
     :privacy-evaluation/execution :not-executed
     :privacy-evaluation/usable? false
     :privacy-evaluation/reasons (cond-> []
                                  (not (:valid? validity)) (conj :policy-invalid)
                                  (not request-valid?) (conj :request-invalid)
                                  (not context) (conj :scope-or-validity-unresolved)
                                  (not sensitivity-valid?) (conj :sensitivity-unverified)
                                  (and remote-required? (not remote-allowed?)) (conj :sentinel-remote-signature-unavailable)
                                  (not sink-allowed?) (conj :sentinel-disclosure-denied)
                                  (and context (not allowed)) (conj :no-complete-permitting-rule))})))
