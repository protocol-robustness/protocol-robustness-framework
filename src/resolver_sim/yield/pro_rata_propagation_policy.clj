(ns resolver-sim.yield.pro-rata-propagation-policy
  "Canonical, domain-specific policy contracts for pro-rata propagation."
  (:require [resolver-sim.hash.canonical :as hc]))

(def shared-withdrawal-policy
  {:schema-version "pro-rata-propagation-policy.v1"
   :policy/id :shared-withdrawal-propagation
   :policy/version 1
   :policy/domain :shared-withdrawal
   :shortfall {:classification :deferred :next-position/type :deferred-withdrawal
               :next-position/eligibility :later-liquidity
               :next-round-weight-policy :residual-entitlement}
   :priority {:propagation-policy :preserve-original}
   :rounding {:propagation-policy :independent-rounds}
   :fulfilled-position {:terminal-state :closed}
   :residual-liquidity {:destination :remain-in-shared-liquidity}
   :accounting-contract {:source-account :shared-liquidity
                         :participant-credit-account :withdrawn
                         :deferred-position-account :deferred-withdrawal}
   :idempotency {:identity-components [:calculation-id :outcome-hash :policy-hash]}})

(def ^:private registry {:shared-withdrawal-propagation shared-withdrawal-policy})
(def ^:private required-fields
  {:shortfall #{:classification :next-position/type :next-position/eligibility :next-round-weight-policy}
   :priority #{:propagation-policy} :rounding #{:propagation-policy}
   :fulfilled-position #{:terminal-state} :residual-liquidity #{:destination}
   :accounting-contract #{:source-account :participant-credit-account :deferred-position-account}
   :idempotency #{:identity-components}})
(def ^:private top-level-fields
  (into #{:schema-version :policy/id :policy/version :policy/domain} (keys required-fields)))

(defn policy-hash [policy]
  (str "sha256:" (hc/hash-with-intent {:hash/intent :evidence-record}
                                       (dissoc policy :policy/hash))))

(defn- ensure-fields! [policy section]
  (let [actual (get policy section)
        required (get required-fields section)
        unknown (seq (remove required (keys (or actual {}))))
        missing (seq (remove #(contains? actual %) required))]
    (when unknown (throw (ex-info "Unknown pro-rata propagation policy field"
                                  {:reason :unknown-policy-field :section section :fields (vec unknown)})))
    (when missing (throw (ex-info "Missing pro-rata propagation policy field"
                                  {:reason :missing-policy-field :section section :fields (vec missing)})))))

(defn validate-policy-semantics [policy]
  (when-not (= "pro-rata-propagation-policy.v1" (:schema-version policy))
    (throw (ex-info "Unsupported pro-rata propagation policy schema" {:reason :unsupported-policy-schema})))
  (when-not (= :shared-withdrawal-propagation (:policy/id policy))
    (throw (ex-info "Propagation policy ID does not match shared-withdrawal contract" {:reason :policy-id-mismatch})))
  (when-not (= 1 (:policy/version policy))
    (throw (ex-info "Unsupported propagation policy version" {:reason :unsupported-policy-version})))
  (when-not (= :shared-withdrawal (:policy/domain policy))
    (throw (ex-info "Unsupported propagation policy domain" {:reason :unsupported-policy-domain})))
  (doseq [section (keys required-fields)] (ensure-fields! policy section))
  (when-not (and (= :deferred (get-in policy [:shortfall :classification]))
                 (= :deferred-withdrawal (get-in policy [:shortfall :next-position/type]))
                 (= :later-liquidity (get-in policy [:shortfall :next-position/eligibility]))
                 (= :residual-entitlement (get-in policy [:shortfall :next-round-weight-policy]))
                 (= :preserve-original (get-in policy [:priority :propagation-policy]))
                 (= :independent-rounds (get-in policy [:rounding :propagation-policy]))
                 (= :closed (get-in policy [:fulfilled-position :terminal-state]))
                 (= :remain-in-shared-liquidity (get-in policy [:residual-liquidity :destination]))
                 (= {:source-account :shared-liquidity :participant-credit-account :withdrawn
                     :deferred-position-account :deferred-withdrawal} (:accounting-contract policy))
                 (= [:calculation-id :outcome-hash :policy-hash] (get-in policy [:idempotency :identity-components])))
    (throw (ex-info "Unsupported shared-withdrawal propagation policy semantics"
                    {:reason :unsupported-policy-enum})))
  policy)

(defn normalize-and-validate [policy]
  (when-not (map? policy) (throw (ex-info "Pro-rata propagation policy is required" {:reason :missing-policy})))
  (let [unknown (seq (remove (conj top-level-fields :policy/hash) (keys policy)))]
    (when unknown (throw (ex-info "Unknown pro-rata propagation policy field" {:reason :unknown-policy-field :fields (vec unknown)}))))
  (assoc (validate-policy-semantics (dissoc policy :policy/hash)) :policy/hash (policy-hash policy)))

(defn verify-policy-hash [policy]
  (let [expected (policy-hash policy)]
    (when-not (= expected (:policy/hash policy))
      (throw (ex-info "Pro-rata propagation policy hash mismatch" {:reason :policy-hash-mismatch
                                                                      :expected expected :actual (:policy/hash policy)})))
    (validate-policy-semantics (dissoc policy :policy/hash))))

(defn resolve-policy [policy-id]
  (let [policy (get registry policy-id)]
    (when-not policy (throw (ex-info "Unknown pro-rata propagation policy" {:reason :unknown-policy :policy/id policy-id})))
    (normalize-and-validate policy)))

(defn policy-reference [policy]
  (let [normalized (normalize-and-validate policy)]
    {:schema-version (:schema-version normalized) :policy/id (:policy/id normalized)
     :policy/version (:policy/version normalized) :policy/hash (:policy/hash normalized)
     :policy/snapshot (dissoc normalized :policy/hash)}))
