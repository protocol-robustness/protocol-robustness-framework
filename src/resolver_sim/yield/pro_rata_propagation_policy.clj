(ns resolver-sim.yield.pro-rata-propagation-policy
  "Canonical, domain-specific policy contracts for pro-rata propagation.

   Semantics:
     :priority {:propagation-policy :preserve-original}
       The allocation order in shared withdrawals is determined by each
       position's `:original-priority` (deposit sequence number).  Deferred
       positions inherit the original priority from their prior lineage so
       that earlier depositors maintain priority across partial-fill cycles.
       See `liquid-lending/withdraw-shared` for the ordering implementation.

     :deferred {:classification ...}
       The deferred class is deterministically derived from the shortfall
       reason and this policy mapping.  The caller does not supply the class.

     :deferred {:max-lineage-round N}
       The highest lineage-round value that may appear on a deferred position.
       Creation rejects when attempted-next-round > max-lineage-round."
  (:require [resolver-sim.hash.canonical :as hc]))

;; ---------------------------------------------------------------------------
;; Supported shortfall reasons and their deferred-class mapping
;; ---------------------------------------------------------------------------

(def shortfall-reason->deferred-class
  "Closed mapping from shortfall reason to deferred class.
   Every supported shortfall reason must appear here.  Unknown or nil
   reasons fail creation.  This is the sole derivation authority."
  {:liquidity-shortfall :liquidity-shortfall})

(def supported-deferred-classes
  "Set of all valid deferred-class values."
  (into #{} (vals shortfall-reason->deferred-class)))

(defn derive-deferred-class
  "Deterministically derive the deferred class from a committed shortfall
   reason map.  The reason must contain a `:reason` key whose value is a
   supported shortfall reason keyword.  Returns the corresponding class or
   throws if unmapped."
  [shortfall-reason]
  (let [reason (:reason shortfall-reason)
        cls (get shortfall-reason->deferred-class reason)]
    (when-not cls
      (throw (ex-info (str "Unsupported shortfall reason for deferred class: " reason)
                      {:reason :unsupported-shortfall-reason
                       :shortfall-reason reason
                       :supported (vec (keys shortfall-reason->deferred-class))})))
    cls))

;; ---------------------------------------------------------------------------
;; Canonical policy
;; ---------------------------------------------------------------------------

(def shared-withdrawal-policy
  {:schema-version "pro-rata-propagation-policy.v1"
   :policy/id :shared-withdrawal-propagation
   :policy/version 1
   :policy/domain :shared-withdrawal
   :shortfall {:classification :deferred :next-position/type :deferred-withdrawal
               :next-position/eligibility :later-liquidity
               :next-round-weight-policy :residual-entitlement
               :deferral-duration-seconds 2592000}
   :deferred {:max-lineage-round 255
              :supported-classes supported-deferred-classes}
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
  {:shortfall #{:classification :next-position/type :next-position/eligibility :next-round-weight-policy :deferral-duration-seconds}
   :deferred #{:max-lineage-round :supported-classes}
   :priority #{:propagation-policy} :rounding #{:propagation-policy}
   :fulfilled-position #{:terminal-state} :residual-liquidity #{:destination}
   :accounting-contract #{:source-account :participant-credit-account :deferred-position-account}
   :idempotency #{:identity-components}})
(def ^:private top-level-fields
  (into #{:schema-version :policy/id :policy/version :policy/domain} (keys required-fields)))

(defn- canonical-bytes-hex
  "Lowercase hex of a value's canonical encoding (used as a deterministic,
   byte-lexicographic sort key for sets)."
  [v]
  (apply str (map #(format "%02x" (bit-and % 0xff)) (hc/canonical-bytes v))))

(defn- project-sets
  "Recursively project sets to sorted vectors.  Sets are outside the canonical
   type algebra; the caller requests the set→sorted-vector normalization
   explicitly here (it is never performed silently by the encoder).  Sorting by
   canonical bytes reproduces the encoder's former byte-lexicographic set order
   exactly, so policy hashes are unchanged."
  [v]
  (cond
    (set? v) (vec (sort-by canonical-bytes-hex (map project-sets v)))
    (map? v) (into {} (map (fn [[k val]] [k (project-sets val)]) v))
    (vector? v) (mapv project-sets v)
    :else v))

(defn policy-hash [policy]
  (str "sha256:" (hc/hash-with-intent {:hash/intent :evidence-record}
                                      (project-sets (dissoc policy :policy/hash)))))

(defn- ensure-fields! [policy section]
  (let [actual (get policy section)
        required (get required-fields section)
        unknown (seq (remove required (keys (or actual {}))))
        missing (seq (remove #(contains? actual %) required))]
    (when unknown (throw (ex-info "Unknown pro-rata propagation policy field"
                                  {:reason :unknown-policy-field :section section :fields (vec unknown)})))
    (when missing (throw (ex-info "Missing pro-rata propagation policy field"
                                  {:reason :missing-policy-field :section section :fields (vec missing)})))))

(defn- validate-deferred-section!
  "Validate deferred section semantics:
   - max-lineage-round is a non-negative integer
   - supported-classes matches the derivation table"
  [policy]
  (let [def-section (:deferred policy)
        mlr (:max-lineage-round def-section)]
    (when (not (and (integer? mlr) (not (neg? mlr))))
      (throw (ex-info "max-lineage-round must be a non-negative integer"
                      {:reason :invalid-max-lineage-round
                       :actual mlr})))
    (let [expected-classes (set (vals shortfall-reason->deferred-class))
          policy-classes (get def-section :supported-classes)]
      (when-not (= expected-classes policy-classes)
        (throw (ex-info "supported-classes does not match derivation table"
                        {:reason :supported-classes-mismatch
                         :expected expected-classes
                         :actual policy-classes}))))))

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
  (validate-deferred-section! policy)
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

(defn check-max-lineage-round!
  "Reject position creation when the attempted next round exceeds the
   policy's max-lineage-round.  Uses strictly greater-than so the max
   value itself is an admissible round number.
   Throws on violation; returns nil on acceptance."
  [attempted-round policy-snapshot]
  (let [max-round (get-in policy-snapshot [:deferred :max-lineage-round])]
    (when (and (some? max-round) (> attempted-round max-round))
      (throw (ex-info (str "Deferred position lineage round " attempted-round
                           " exceeds policy max " max-round)
                      {:reason :exceeded-max-lineage-round
                       :attempted-round attempted-round
                       :max-lineage-round max-round})))))

(defn validate-deferred-position-schema
  "Strict validation for newly created deferred positions.
   Rejects positions that are missing required deferred-specific fields
   or have nil values for fields that must be present.
   Throws on violation; returns nil on acceptance."
  [deferred-pos]
  (let [required-fields [:deferred/class :deferred/lineage-root :deferred/predecessor-hash
                         :position/round :position/original-priority
                         :position/current-amount :position/type]
        missing (remove (fn [f] (contains? deferred-pos f)) required-fields)
        nil-fields (keep (fn [f] (when (nil? (get deferred-pos f)) f)) required-fields)]
    (when (seq missing)
      (throw (ex-info "New deferred position is missing required fields"
                      {:reason :deferred-position-missing-fields
                       :missing (vec missing)})))
    (when (seq nil-fields)
      (throw (ex-info "New deferred position has nil required fields"
                      {:reason :deferred-position-nil-fields
                       :nil-fields (vec nil-fields)}))))
  nil)
