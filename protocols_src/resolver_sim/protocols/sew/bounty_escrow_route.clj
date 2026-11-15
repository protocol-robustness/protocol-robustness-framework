(ns resolver-sim.protocols.sew.bounty-escrow-route
  "Fail-closed routing metadata for optional Sew bounty escrow.

   This adapter records a one-to-one bounty/workflow binding and checks
   independently reconstructed withdrawal categories. It does not verify a
   Sew receipt, authenticate a chain observation, or settle an obligation;
   callers must use canonical obligation-settlement.v1 for that boundary."
  (:require [resolver-sim.hash.canonical :as hc]))

(def schema "sew-bounty-escrow-route.v1")
(def domain "SEW_BOUNTY_ESCROW_ROUTE_V1")

(def required-fields
  #{:route/schema :route/obligation-root :route/payable-root
    :route/backing-root :route/beneficiary :route/asset
    :route/chain-instance-root :route/amount :route/funding-source-root
    :route/workflow-id :route/trust-class :route/events})

(def event-fields
  #{:event/type :event/sequence :event/predecessor-root
    :event/workflow-id :event/asset :event/chain-instance-root
    :event/amount :event/funding-source-root :event/evidence-root})

(def event-types #{:deposit :release :claimable :cancellation :dispute
                   :withdrawal-submitted :withdrawal-failed})

(def claim-categories
  #{:settlement/principal :settlement/yield :bond/refund
    :liability/challenge-bounty})

(defn route-body [route]
  (select-keys route required-fields))

(defn route-root [route]
  (hc/domain-hash domain (route-body route)))

(defn- non-empty-string? [x]
  (and (string? x) (seq x)))

(defn- sha256-root? [x]
  (and (string? x) (re-matches #"sha256:[0-9a-f]{64}" x)))

(defn- digest? [x]
  (and (string? x) (re-matches #"[0-9a-f]{64}" x)))

(defn- evm-address? [x]
  (and (string? x) (re-matches #"0x[0-9a-fA-F]{40}" x)))

(declare verify-events)

(defn- route-fields-valid? [route]
  (and (every? sha256-root?
              (map route [:route/obligation-root :route/payable-root
                          :route/backing-root :route/chain-instance-root
                          :route/funding-source-root]))
       (evm-address? (:route/beneficiary route))
       (or (keyword? (:route/asset route))
           (non-empty-string? (:route/asset route)))
       (integer? (:route/amount route))
       (pos? (:route/amount route))
       (or (non-empty-string? (:route/workflow-id route))
           (and (integer? (:route/workflow-id route))
                (not (neg? (:route/workflow-id route)))))
       (= :model-only (:route/trust-class route))))

(defn build-route
  "Create a model-only Sew routing artifact from explicit reviewed inputs.

   A workflow ID is not derived from an obligation ID. The caller must supply
   the reviewed mapping, including the exact eligible payout address."
  [{:keys [obligation-root payable-root backing-root beneficiary asset
           chain-instance-root amount funding-source-root workflow-id]}]
  (let [route {:route/schema schema
               :route/obligation-root obligation-root
               :route/payable-root payable-root
               :route/backing-root backing-root
               :route/beneficiary beneficiary
               :route/asset asset
               :route/chain-instance-root chain-instance-root
               :route/amount amount
               :route/funding-source-root funding-source-root
               :route/workflow-id workflow-id
               :route/trust-class :model-only
               :route/events []}]
    (when-not (and (every? sha256-root?
                           [obligation-root payable-root backing-root
                            chain-instance-root funding-source-root])
                   (evm-address? beneficiary)
                   (or (non-empty-string? workflow-id)
                       (and (integer? workflow-id) (not (neg? workflow-id))))
                   (or (keyword? asset) (non-empty-string? asset))
                   (integer? amount) (pos? amount))
      (throw (ex-info "invalid Sew bounty route mapping"
                      {:route/reason :invalid-or-missing-mapping})))
    (assoc route :route/root (route-root route))))

(defn verify-route
  "Check route shape, root, and unique mapping across the supplied route set.
   This proves only local model consistency, never Sew or EVM authenticity."
  [route routes]
  (let [route-set (when (sequential? routes) routes)
        same-obligation (filter #(= (:route/obligation-root route)
                                    (:route/obligation-root %)) route-set)
        same-workflow (filter #(= (:route/workflow-id route)
                                  (:route/workflow-id %)) route-set)
        exact-shape? (and (map? route)
                          (= (conj required-fields :route/root)
                             (set (keys route))))
        event-check (verify-events route)]
    (cond
      (not exact-shape?) {:valid? false :reason :invalid-route-shape}
      (nil? route-set) {:valid? false :reason :invalid-route-set}
      (not= schema (:route/schema route)) {:valid? false :reason :unsupported-route-schema}
      (not (route-fields-valid? route)) {:valid? false :reason :invalid-route-fields}
      (not= (:route/root route) (route-root route)) {:valid? false :reason :route-root-mismatch}
      (not (:valid? event-check)) event-check
      (not= 1 (count same-obligation))
      {:valid? false :reason :ambiguous-obligation-mapping}
      (not= 1 (count same-workflow))
      {:valid? false :reason :workflow-already-mapped}
      :else {:valid? true :trust-class :model-only
             :route-state (:state event-check)})))

(defn- event-state
  [events]
  (reduce (fn [state event]
            (case [state (:event/type event)]
              [:unfunded :deposit] :funded
              [:funded :release] :released
              [:funded :dispute] :disputed
              [:released :dispute] :disputed
              [:claimable :dispute] :disputed
              [:disputed :release] :released
              [:released :claimable] :claimable
              [:funded :cancellation] :cancelled
              [:disputed :cancellation] :cancelled
              [:released :cancellation] :cancelled
              [:claimable :cancellation] :cancelled
              [:claimable :withdrawal-submitted] :withdrawal-submitted
              [:withdrawal-submitted :withdrawal-failed] :claimable
              [:withdrawal-failed :withdrawal-submitted] :withdrawal-submitted
              :invalid))
          :unfunded events))

(defn- verify-events [route]
  (let [events (:route/events route)
        event-reason (fn [index event]
                       (cond
                         (not (map? event)) :invalid-route-event
                         (not= event-fields (set (keys event))) :invalid-route-event
                         (not (and (integer? (:event/sequence event))
                                   (= (inc index) (:event/sequence event))))
                         :stale-or-duplicate-route-event
                         (not= (route-root
                                (assoc route :route/events (subvec events 0 index)))
                               (:event/predecessor-root event))
                         :stale-route-predecessor
                         (not (contains? event-types (:event/type event))) :invalid-route-event
                         (not= (:route/workflow-id route) (:event/workflow-id event))
                         :invalid-route-event
                         (not= (:route/asset route) (:event/asset event)) :invalid-route-event
                         (not= (:route/chain-instance-root route)
                               (:event/chain-instance-root event)) :invalid-route-event
                         (not= (:route/funding-source-root route)
                               (:event/funding-source-root event)) :invalid-route-event
                         (or (not (integer? (:event/amount event)))
                             (neg? (:event/amount event))) :invalid-route-event
                         (not (sha256-root? (:event/evidence-root event))) :invalid-route-event
                         (not (digest? (:event/predecessor-root event)))
                         :invalid-route-event
                         (not= (:route/amount route) (:event/amount event))
                         :invalid-route-event
                         :else nil))
        reasons (when (vector? events)
                  (map-indexed event-reason events))
        state (when (and (vector? events) (not-any? identity reasons))
                (event-state events))]
    (cond
      (not (vector? events)) {:valid? false :reason :route-events-missing}
      (some identity reasons) {:valid? false :reason (first (remove nil? reasons))}
      (= :invalid state) {:valid? false :reason :stale-or-invalid-route-transition}
      :else {:valid? true :state state :trust-class :model-only})))

(defn append-event
  "Append a route event as source-bound model data. Event evidence roots are
   references only; sequence and predecessor root must come from the recorded
   event lineage. This function does not authenticate receipts or authority."
  [route event]
  (if-not (map? route)
    {:valid? false :reason :invalid-route}
    (let [candidate (-> route
                        (update :route/events conj event)
                        (dissoc :route/root))
          event-check (verify-events candidate)]
      (cond
        (not (:valid? (verify-route route [route])))
        {:valid? false :reason :invalid-route}
        (not (:valid? event-check)) event-check
        :else
        {:valid? true :trust-class :model-only
         :route (assoc candidate :route/root (route-root candidate))}))))

(defn verify-withdrawal-attribution
  "Require a reconstructed category ledger to show that the entire withdrawal
   is the bounty category, and bind it to the caller's expected obligation.
   Returns typed failures for incomplete or mixed claims. This is a necessary
   attribution check, not payment evidence."
  [{:keys [route expected-obligation-root workflow-id token recipient category-deltas withdrawn-amount
           independently-reconstructed?]}]
  (let [deltas (when (map? category-deltas) category-deltas)
        bounty-amount (:liability/challenge-bounty deltas)
        unrelated (when deltas
                    (into {} (filter (fn [[category amount]]
                                       (and (not= category :liability/challenge-bounty)
                                            (number? amount) (pos? amount)))) deltas))]
    (cond
      (not (:valid? (verify-route route [route])))
      {:valid? false :reason :invalid-route}
      (not= expected-obligation-root (:route/obligation-root route))
      {:valid? false :reason :obligation-mismatch}
      (not (true? independently-reconstructed?))
      {:valid? false :reason :claim-categories-unverified}
      (and deltas (not= claim-categories (set (keys deltas))))
      {:valid? false :reason :claim-categories-incomplete}
      (not= workflow-id (:route/workflow-id route))
      {:valid? false :reason :workflow-mismatch}
      (not= token (:route/asset route))
      {:valid? false :reason :asset-mismatch}
      (not= recipient (:route/beneficiary route))
      {:valid? false :reason :beneficiary-mismatch}
      (nil? deltas) {:valid? false :reason :claim-categories-missing}
      (some #(or (not (integer? %)) (neg? %)) (vals deltas))
      {:valid? false :reason :invalid-claim-category-amount}
      (seq unrelated) {:valid? false :reason :unrelated-claim-categories
                       :categories unrelated}
      (not= (:route/amount route) bounty-amount)
      {:valid? false :reason :bounty-amount-mismatch}
      (not= withdrawn-amount bounty-amount)
      {:valid? false :reason :withdrawal-amount-mismatch}
      :else {:valid? true :attribution :bounty-category-only
             :amount bounty-amount :trust-class :model-only})))
