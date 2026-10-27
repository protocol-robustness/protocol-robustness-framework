(ns resolver-sim.economics.bounty-shortfall
  "Policy-bound allocation evidence for bounty obligations sharing a reserve."
  (:require [clojure.set :as set]
            [resolver-sim.economics.bounty-payable-backing :as backing]
            [resolver-sim.economics.bounty-payable :as payable]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.pro-rata.allocation :as pro-rata]
            [resolver-sim.pro-rata.exact-verifier :as exact-verifier]
            [resolver-sim.economics.with-bounty.funding-evidence :as funding]
            [resolver-sim.economics.with-bounty.obligation-settlement :as settlement]))

(def policy-schema "bounty-shortfall-policy.v1")
(def allocation-schema "bounty-shortfall-allocation.v1")
(declare verify verify-funded-settlements)

(defn policy-hash [policy]
  (hc/domain-hash "PRF_BOUNTY_SHORTFALL_POLICY_V1" (dissoc policy :policy/hash)))

(defn build-policy
  "Create a versioned policy bound to one authenticated source snapshot. A
   recovery must carry an authorized successor policy with a new source root."
  [{:keys [policy/id authority-root authority-provenance token snapshot-root
           version parent-root rounding fee-allocation
           partial-payment? residual-liability recovery]}]
  (when-not (and (string? id) (seq id) (string? authority-root) (seq authority-root)
                 (map? authority-provenance) (seq authority-provenance)
                 (or (keyword? token) (and (string? token) (seq token)))
                 (string? snapshot-root) (seq snapshot-root)
                 (integer? (or version 1)) (pos? (or version 1))
                 (if (= 1 (or version 1)) (nil? parent-root)
                     (and (string? parent-root) (seq parent-root)))
                 (= :largest-remainder rounding)
                 (= :outside-reserve fee-allocation)
                 (false? partial-payment?) (= :retain residual-liability)
                 (= :remaining-liability recovery))
    (throw (ex-info "Unsupported or incomplete bounty shortfall policy"
                    {:reason :invalid-policy})))
  (let [base {:policy/schema policy-schema :policy/id id :policy/version (or version 1)
              :policy/authority-root authority-root
              :policy/authority-provenance authority-provenance
              :policy/token token
              :policy/snapshot-root snapshot-root
              :policy/parent-root parent-root
              :policy/eligible-claims :all-approved-obligations
              :policy/ratio :approved-obligation-amount
              :policy/rounding rounding :policy/tie-break :canonical-claim-id
              :policy/fee-allocation fee-allocation :policy/partial-payment? false
              :policy/residual-liability residual-liability :policy/recovery recovery}]
    (assoc base :policy/hash (policy-hash base))))

(defn- authorized-policy? [policy authorities]
  (let [version (:policy/version policy)
        parent-root (:policy/parent-root policy)]
    (and (= policy-schema (:policy/schema policy))
         (= (:policy/hash policy) (policy-hash policy))
         (integer? version)
         (pos? version)
         (if (= version 1)
           (nil? parent-root)
           (and (> version 1) (string? parent-root) (seq parent-root)))
         (contains? (set authorities) (:policy/authority-root policy)))))

(defn- valid-obligations? [obligations]
  (and (vector? obligations) (seq obligations)
       (= (count obligations) (count (set (map :obligation/id obligations))))
       (= (count obligations)
          (count (set (map #(get-in % [:obligation/payable :payable/hash]) obligations))))
       (every? (fn [o]
                 (let [p (:obligation/payable o)]
                   (and (= #{:obligation/id :obligation/payable} (set (keys o)))
                        (string? (:obligation/id o)) (seq (:obligation/id o))
                        (integer? (:payable/amount p)) (pos? (:payable/amount p))
                        (= :pending-backing (:payable/lifecycle p))
                        (:valid? (payable/verify-bounty-payable p)))))
               obligations)))

(defn- obligation-view [{:keys [obligation/id obligation/payable]}]
  {:obligation/id id :obligation/root (:payable/hash payable)
   :obligation/amount (:payable/amount payable) :obligation/payable payable})

(defn- body [artifact] (dissoc artifact :allocation/hash))

(defn allocate
  "Allocate available committed backing pro rata. Recovery preserves obligation
   roots and subtracts prior allocations and verified payments before replay."
  [{:keys [policy authorities obligations funding-source funding-trust-policy
           available recovery-of previous-inputs previous-settlements
           prior-reservations event-id]}]
  (when-not (authorized-policy? policy authorities)
    (throw (ex-info "Unauthorized bounty shortfall policy" {:reason :unauthorized-policy})))
  (when-not (valid-obligations? obligations)
    (throw (ex-info "Invalid or duplicate approved obligations" {:reason :invalid-obligations})))
  (when (and (> (:policy/version policy) 1) (nil? recovery-of))
    (throw (ex-info "Successor policy requires a verified recovery predecessor"
                    {:reason :stale-recovery})))
  (when-not (and (vector? prior-reservations)
                 (every? (fn [{:keys [allocation inputs]}]
                           (and (= allocation (:allocation inputs))
                                (:valid? (verify inputs))))
                         prior-reservations))
    (throw (ex-info "Invalid prior reservation history" {:reason :invalid-reservation-history})))
  (let [source-check (funding/verify-source funding-source funding-trust-policy
                                            {:funding/token (:policy/token policy)
                                             :funding/source-root (:policy/snapshot-root policy)})]
    (when-not (:valid? source-check)
      (throw (ex-info "Unauthenticated reserve source"
                      {:reason (:reason source-check)})))
    (when (some #(= (:funding/source-root funding-source)
                    (:allocation/funding-source-root (:allocation %)))
                prior-reservations)
      (throw (ex-info "Funding source is already reserved"
                      {:reason :duplicate-reservation})))
    (when-not (and (integer? available) (not (neg? available))
                   (<= available (:funding/available funding-source))
                   (string? event-id) (seq event-id)
                   (= event-id (:funding/operation-root funding-source)))
      (throw (ex-info "Invalid available backing or event ID"
                      {:reason :invalid-allocation-input})))
    (let [funding-token (:funding/token funding-source)
          funding-root (:funding/source-root funding-source)
          operation-root (:funding/operation-root funding-source)]
      (when recovery-of
        (when-not (and (= (:policy/parent-root policy) (:allocation/policy-root recovery-of))
                       (= (:policy/version policy) (inc (:allocation/policy-version recovery-of)))
                       (= recovery-of (:allocation previous-inputs))
                       (:valid? (verify previous-inputs))
                       (or (nil? previous-settlements)
                           (:valid? (verify-funded-settlements
                                     {:allocation recovery-of
                                      :allocation-input previous-inputs
                                      :settlements previous-settlements})))
                       (= (:allocation/hash recovery-of)
                          (hc/domain-hash "PRF_BOUNTY_SHORTFALL_ALLOCATION_V1" (body recovery-of)))
                       (not= event-id (:allocation/event-id recovery-of))
                       (not (some #{event-id} (:allocation/event-history recovery-of)))
                       (not= (:allocation/funding-source-root recovery-of)
                             funding-root)
                       (not (some #{(:funding/source-root funding-source)}
                                  (:allocation/funding-source-history recovery-of)))
                       (= (into {} (map (juxt :obligation/id :obligation/root)
                                        (map obligation-view obligations)))
                          (into {} (map (juxt :obligation/id :obligation/root)
                                        (:allocation/obligations recovery-of)))))
          (throw (ex-info "Recovery predecessor or original obligations changed"
                          {:reason :stale-recovery}))))
      (let [obligation-rows
            (mapv (fn [{:obligation/keys [id root amount]}]
                    (let [paid-amount (get-in recovery-of [:allocation/paid id] 0)
                          previously-allocated (get-in recovery-of
                                                       [:allocation/cumulative-allocations id]
                                                       (get-in recovery-of [:allocation/allocations id] 0))
                          allocatable (- amount previously-allocated)]
                      (when (or (neg? allocatable)
                                (> paid-amount previously-allocated))
                        (throw (ex-info "Paid and allocated amounts exceed approved obligation"
                                        {:reason :obligation-overcommitment :id id})))
                      {:obligation/id id :obligation/root root :obligation/approved amount
                       :obligation/paid paid-amount
                       :obligation/previously-allocated previously-allocated
                   ;; This is the still-unfunded portion eligible for a later
                   ;; recovery allocation, not the total claimant liability.
                       :obligation/liability allocatable})) (map obligation-view obligations))
            rows (mapv (fn [o] {:row/id (:obligation/id o)
                                :obligation/id (:obligation/id o)
                                :requested (:obligation/liability o)
                                :weight (:obligation/liability o)}) obligation-rows)
            allocation (pro-rata/allocate
                        {:schema-version "pro-rata-allocation-request.v1"
                         :mechanism/version 1 :allocation/id (:policy/id policy)
                         :available available :rows rows
                         :rounding-policy :largest-remainder
                         :tie-break-policy :canonical-row-id
                         :redistribution-policy :unallocated})
            allocated (into {} (map (fn [r] [(:row/id r) (:allocated r)])
                                    (:rows allocation)))
            cumulative (merge-with +
                                   (or (:allocation/cumulative-allocations recovery-of)
                                       (:allocation/allocations recovery-of) {})
                                   allocated)
            previous-payment-batch (when previous-settlements
                                     (verify-funded-settlements
                                      {:allocation recovery-of
                                       :allocation-input previous-inputs
                                       :settlements previous-settlements}))
            paid (merge-with + (or (:allocation/paid recovery-of) {})
                             (or (get-in previous-payment-batch
                                         [:settlement-batch :settlement-batch/paid]) {}))
            funded-claims
            (into {}
                  (keep (fn [{:obligation/keys [id root]}]
                          (let [amount (get allocated id 0)
                                parent (:obligation/payable (some #(when (= id (:obligation/id %)) %)
                                                                  obligations))]
                            (when (pos? amount)
                              (let [child (payable/build-bounty-payable
                                           {:payable/id (str "shortfall/" event-id "/" id)
                                            :award-id (:payable/award-id parent)
                                            :distribution-root (:payable/distribution-root parent)
                                            :beneficiary (:payable/beneficiary parent)
                                            :amount amount :kind (:payable/kind parent)
                                            :lifecycle :pending-backing
                                            :context {:shortfall/policy-root (:policy/hash policy)
                                                      :shortfall/source-root funding-root
                                                      :shortfall/event-id event-id
                                                      :shortfall/obligation-root root}})
                                    child (payable/transition-payable-lifecycle child :backed)
                                    child-backing (backing/build-bounty-payable-backing
                                                   {:payable-root (:payable/hash child)
                                                    :payable-id (:payable/id child)
                                                    :distribution-root (:payable/distribution-root child)
                                                    :amount amount
                                                    :source-allocations {funding-root amount}
                                                    :context {:funding/token funding-token
                                                              :funding/source-root funding-root
                                                              :funding/operation-root operation-root
                                                              :shortfall/policy-root (:policy/hash policy)
                                                              :shortfall/event-id event-id
                                                              :shortfall/obligation-root root}})]
                                [id {:payable child :backing child-backing
                                     :funded-amount amount :obligation-root root}]))))
                        obligation-rows))
            funded-claims-history
            (assoc (or (:allocation/funded-claims-history recovery-of) {})
                   event-id (into (sorted-map) funded-claims))
            liabilities
            (into (sorted-map)
                  (map (fn [o]
                         [(:obligation/id o)
                          (- (:obligation/approved o)
                             (get paid (:obligation/id o) 0))])
                       obligation-rows))
            funded-unpaid
            (into (sorted-map)
                  (map (fn [[id amount]]
                         [id (- amount (get paid id 0))]) cumulative))
            unfunded-liabilities
            (into (sorted-map)
                  (map (fn [o]
                         [(:obligation/id o)
                          (- (:obligation/approved o)
                             (get cumulative (:obligation/id o) 0))])
                       obligation-rows))
            total-approved (reduce + 0 (map :obligation/approved obligation-rows))
            total-paid (reduce + 0 (vals paid))
            total-funded-unpaid (reduce + 0 (vals funded-unpaid))
            total-unfunded-liability (reduce + 0 (vals unfunded-liabilities))
            total-outstanding-liability (reduce + 0 (vals liabilities))
            _ (when-not (= total-approved
                           (+ total-paid total-funded-unpaid total-unfunded-liability))
                (throw (ex-info "Bounty liability conservation failed"
                                {:reason :liability-conservation-failed})))
            accounted-obligation-rows
            (mapv (fn [o]
                    (assoc o
                           :obligation/paid (get paid (:obligation/id o) 0)
                           :obligation/outstanding-liability
                           (get liabilities (:obligation/id o) 0)
                           :obligation/funded-unpaid
                           (get funded-unpaid (:obligation/id o) 0)
                           :obligation/unfunded-liability
                           (get unfunded-liabilities (:obligation/id o) 0)))
                  obligation-rows)
            result {:allocation/schema allocation-schema
                    :allocation/qualification :model-only
                    :allocation/evm-qualification :not-verified
                    :allocation/policy-root (:policy/hash policy)
                    :allocation/policy-version (:policy/version policy)
                    :allocation/snapshot-root funding-root
                    :allocation/funding-source-root
                    (get-in funding-source [:funding/source-root])
                    :allocation/prior-reservation-roots
                    (vec (sort (map #(get-in % [:allocation :allocation/hash]) prior-reservations)))
                    :allocation/token funding-token
                    :allocation/available available
                    :allocation/event-id event-id
                    :allocation/event-history (conj (vec (:allocation/event-history recovery-of)) event-id)
                    :allocation/funding-source-history
                    (conj (vec (:allocation/funding-source-history recovery-of))
                          (:funding/source-root funding-source))
                    :allocation/recovery-of (:allocation/hash recovery-of)
                    :allocation/obligations accounted-obligation-rows
                    :allocation/allocations allocated
                    :allocation/cumulative-allocations cumulative
                    :allocation/paid paid
                    :allocation/payment-batch-root
                    (:settlement-batch/root previous-payment-batch)
                    :allocation/funded-claims funded-claims
                    :allocation/funded-claims-history funded-claims-history
                ;; Total approved liabilities remain outstanding until
                ;; canonical settlement evidence verifies payment. The
                ;; unfunded map is the amount eligible for future allocation.
                    :allocation/liabilities liabilities
                    :allocation/funded-unpaid funded-unpaid
                    :allocation/unfunded-liabilities unfunded-liabilities
                    :allocation/total-allocated (reduce + 0 (vals allocated))
                    :allocation/total-approved total-approved
                    :allocation/total-paid total-paid
                    :allocation/total-funded-unpaid total-funded-unpaid
                    :allocation/total-unfunded-liability total-unfunded-liability
                    :allocation/total-outstanding-liability total-outstanding-liability
                    :allocation/algorithm-root (:allocation/hash allocation)}]
        (assoc result :allocation/hash
               (hc/domain-hash "PRF_BOUNTY_SHORTFALL_ALLOCATION_V1" result))))))

(defn- verify-pro-rata-math [allocation]
  (let [obligations (:allocation/obligations allocation)
        items (mapv (fn [o]
                      {:id (:obligation/id o)
                       :weight (:obligation/liability o)
                       :cap (:obligation/liability o)}) obligations)
        request {:amount (:allocation/available allocation)
                 :items items
                 :rounding :floor-with-largest-remainder
                 :ordering-policy :canonical-id
                 :cap-treatment :unallocated}
        claimed {:allocations (mapv (fn [o]
                                      (let [id (:obligation/id o)]
                                        {:id id
                                         :allocated (get-in allocation
                                                            [:allocation/allocations id] 0)
                                         :unmet 0}))
                                    obligations)
                 :total-allocated (:allocation/total-allocated allocation)
                 :total-unmet (- (:allocation/available allocation)
                                 (:allocation/total-allocated allocation))
                 :remainder 0}]
    (exact-verifier/verify-weighted-proportionality request claimed)))

(defn verify
  "Independently replay an allocation and return a typed result; never throw."
  [{:keys [allocation] :as inputs}]
  (try
    (let [replay (allocate inputs)
          independent (verify-pro-rata-math allocation)]
      (if (and (= allocation replay) (= :passed (:status independent)))
        {:valid? true :reason :verified}
        {:valid? false :reason :evidence-mismatch
         :independent-check independent}))
    (catch Exception e
      {:valid? false :reason (or (:reason (ex-data e)) :invalid-evidence)})))

(defn verify-funded-settlements
  "Verify direct full-payment settlements for funded portions through the
   canonical obligation-settlement.v1 interface. This function checks the
   allocation-to-payable binding and delegates all payout and chain evidence
   verification to PRO-81. `settlements` maps obligation IDs to the complete
   input map accepted by settlement/verify-settlement."
  [{:keys [allocation allocation-input settlements]}]
  (try
    (let [allocation-check (verify (assoc allocation-input :allocation allocation))
          funded (into {} (filter (fn [[_ amount]] (pos? amount))
                                  (:allocation/allocations allocation)))
          supplied (set (keys settlements))]
      (cond
        (not (:valid? allocation-check))
        {:valid? false :reason :allocation-evidence-invalid}

        (or (empty? supplied) (not (set/subset? supplied (set (keys funded)))))
        {:valid? false :reason :settlement-set-mismatch}

        :else
        (let [checks
              (into {}
                    (map (fn [[id amount]]
                           (let [{:keys [settlement payable-before] :as settlement-input}
                                 (get settlements id)
                                 result (settlement/verify-settlement (get settlements id))
                                 funded-payable (get-in allocation [:allocation/funded-claims id :payable])
                                 obligation-root (some (fn [o]
                                                         (when (= id (:obligation/id o))
                                                           (:obligation/root o)))
                                                       (:allocation/obligations allocation))
                                 matches? (and (= amount (:payable/amount payable-before))
                                               (= (:payable/id funded-payable)
                                                  (:payable/id payable-before))
                                               (= (:payable/hash funded-payable)
                                                  (:payable/hash payable-before))
                                               (= (get-in allocation [:allocation/funded-claims id :backing
                                                                      :backing/hash])
                                                  (get-in settlement-input [:backing :backing/hash]))
                                               (= obligation-root
                                                  (get-in payable-before
                                                          [:payable/context :shortfall/obligation-root]))
                                               (= (:allocation/policy-root allocation)
                                                  (get-in payable-before
                                                          [:payable/context :shortfall/policy-root]))
                                               (= (:allocation/event-id allocation)
                                                  (get-in payable-before
                                                          [:payable/context :shortfall/event-id]))
                                               (= amount (:settlement/paid settlement))
                                               (= (:allocation/token allocation)
                                                  (:settlement/asset settlement))
                                               (= (:allocation/funding-source-root allocation)
                                                  (:settlement/funding-source-root settlement)))]
                             [id (if (and (:valid? result) matches?)
                                   {:valid? true :settlement-root (:root result) :paid amount}
                                   {:valid? false :reason :settlement-binding-mismatch
                                    :settlement-result result})]))
                         (select-keys funded supplied)))]
          (if (every? (comp :valid? val) checks)
            (let [body {:settlement-batch/schema "bounty-shortfall-settlement-batch.v1"
                        :settlement-batch/allocation-root (:allocation/hash allocation)
                        :settlement-batch/paid
                        (into {} (map (fn [[id result]] [id (:paid result)]) checks))
                        :settlement-batch/settlements
                        (into {} (map (fn [[id result]] [id (:settlement-root result)]) checks))}]
              {:valid? true :settlement-batch body
               :settlement-batch/root
               (hc/domain-hash "PRF_BOUNTY_SHORTFALL_SETTLEMENT_BATCH_V1" body)})
            {:valid? false :reason :unsupported-or-invalid-payment-evidence
             :settlement-checks checks}))))
    (catch Exception _
      {:valid? false :reason :malformed-settlement-input})))
