(ns resolver-sim.yield.pro-rata-propagation-policy
  "Canonical, domain-specific policy contracts for pro-rata propagation.

   Semantics:
     :priority {:propagation-policy :preserve-original}
       The allocation order in shared withdrawals is determined by each
       position's `:original-priority` (deposit sequence number).  Deferred
       positions inherit the original priority from their prior lineage so
       that earlier depositors maintain priority across partial-fill cycles.
       See `liquid-lending/withdraw-shared` for the ordering implementation."
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
  (let [schema "pro-rata-propagation-policy.v1"]
    (when-not (= schema (:schema-version policy))
      (throw (ex-info "Unsupported pro-rata propagation policy schema"
                      {:reason :unsupported-policy-schema :expected schema}))))
  (doseq [[k expected] {:policy/id :shared-withdrawal-propagation
                        :policy/version 1
                        :policy/domain :shared-withdrawal}]
    (when-not (= expected (k policy))
      (throw (ex-info (str "Propagation policy " (name k) " does not match contract")
                      {:reason (keyword "policy" (name k)) :expected expected}))))
  (doseq [section (keys required-fields)] (ensure-fields! policy section))
  (let [canonical shared-withdrawal-policy
        checks [[:shortfall :classification]
                [:shortfall :next-position/type]
                [:shortfall :next-position/eligibility]
                [:shortfall :next-round-weight-policy]
                [:priority :propagation-policy]
                [:rounding :propagation-policy]
                [:fulfilled-position :terminal-state]
                [:residual-liquidity :destination]]]
    (doseq [[section field] checks]
      (let [path [section field]
            expected (get-in canonical path)
            actual (get-in policy path)]
        (when-not (= expected actual)
          (throw (ex-info (str "Unsupported " (name section) " " (name field))
                          {:reason :unsupported-policy-enum
                           :section section :field field
                           :expected expected :actual actual}))))))
  (let [canonical shared-withdrawal-policy
        expected-acct (:accounting-contract canonical)
        actual-acct (:accounting-contract policy)]
    (when-not (= expected-acct actual-acct)
      (throw (ex-info "Unsupported accounting-contract"
                      {:reason :unsupported-policy-enum
                       :expected expected-acct :actual actual-acct}))))
  (let [canonical shared-withdrawal-policy
        expected-ids (:identity-components (:idempotency canonical))
        actual-ids (get-in policy [:idempotency :identity-components])]
    (when-not (= expected-ids actual-ids)
      (throw (ex-info "Unsupported idempotency identity-components"
                      {:reason :unsupported-policy-enum
                       :expected expected-ids :actual actual-ids}))))
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
