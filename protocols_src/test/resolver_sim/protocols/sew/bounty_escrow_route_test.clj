(ns resolver-sim.protocols.sew.bounty-escrow-route-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.bounty-escrow-route :as route]))

(defn- root-ref [c]
  (str "sha256:" (apply str (repeat 64 c))))

(defn- mapping
  ([] (mapping {}))
  ([overrides]
   (merge {:obligation-root (root-ref \a)
           :payable-root (root-ref \b)
           :backing-root (root-ref \c)
           :beneficiary "0x000000000000000000000000000000000000a11c"
           :asset :token/usdc
           :chain-instance-root (root-ref \d)
           :amount 70
           :funding-source-root (root-ref \e)
           :workflow-id "workflow-17"}
          overrides)))

(defn- build [] (route/build-route (mapping)))

(defn- categories [bounty & {:as overrides}]
  (merge {:settlement/principal 0 :settlement/yield 0 :bond/refund 0
          :liability/challenge-bounty bounty}
         overrides))

(defn- append-event [r event]
  (route/append-event
   r (assoc event
            :event/sequence (inc (count (:route/events r)))
            :event/predecessor-root (:route/root r))))

(defn- event-data [event-type evidence-char]
  {:event/type event-type
   :event/workflow-id "workflow-17"
   :event/asset :token/usdc
   :event/chain-instance-root (root-ref \d)
   :event/amount 70
   :event/funding-source-root (root-ref \e)
   :event/evidence-root (root-ref evidence-char)})

(deftest route-requires-a-complete-explicit-mapping
  (testing "missing identifiers and ambiguous workflow bindings fail closed"
    (is (= :invalid-or-missing-mapping
           (:route/reason (ex-data (try
                                     (route/build-route (mapping {:workflow-id nil}))
                                     (catch clojure.lang.ExceptionInfo e e))))))
    (let [r (build)
          duplicate (route/build-route
                     (mapping {:obligation-root (root-ref \f)}))]
      (is (= {:valid? false :reason :workflow-already-mapped}
             (route/verify-route r [r duplicate]))))
    (let [r (build)
          duplicate (route/build-route
                     (mapping {:payable-root (root-ref \f)}))]
      (is (= {:valid? false :reason :ambiguous-obligation-mapping}
             (route/verify-route r [r duplicate]))))))

(deftest bulk-withdrawal-requires-complete-category-reconstruction
  (let [r (build)
        base {:route r :expected-obligation-root (:route/obligation-root r)
              :workflow-id "workflow-17" :token :token/usdc
              :recipient (:route/beneficiary r) :withdrawn-amount 70
              :independently-reconstructed? true}]
    (is (= :unrelated-claim-categories
           (:reason (route/verify-withdrawal-attribution
                     (assoc base :category-deltas
                            (categories 70 :settlement/yield 5)))))
        "an aggregate withdrawal is not wholly attributable to the bounty")
    (is (= :claim-categories-unverified
           (:reason (route/verify-withdrawal-attribution
                     (assoc base :independently-reconstructed? false
                            :category-deltas (categories 70)))))
        "workflow isolation alone is insufficient")
    (is (= :claim-categories-incomplete
           (:reason (route/verify-withdrawal-attribution
                     (assoc base :category-deltas
                            {:liability/challenge-bounty 70})))))
    (is (= :invalid-claim-category-amount
           (:reason (route/verify-withdrawal-attribution
                     (assoc base :category-deltas (categories "70"))))))
    (is (:valid? (route/verify-withdrawal-attribution
                  (assoc base :category-deltas (categories 70)))))
    (let [result (route/verify-withdrawal-attribution
                  (assoc base :category-deltas (categories 70)))]
      (is (= :model-only (:trust-class result)))
      (is (not (contains? result :settled?)))
      (is (not (contains? result :payment-verified?))))))

(deftest attribution-binds-workflow-token-address-and-exact-amount
  (let [r (build)
        base {:route r :expected-obligation-root (:route/obligation-root r)
              :workflow-id "workflow-17" :token :token/usdc
              :recipient (:route/beneficiary r) :withdrawn-amount 70
              :independently-reconstructed? true
              :category-deltas (categories 70)}]
    (is (= :workflow-mismatch
           (:reason (route/verify-withdrawal-attribution
                     (assoc base :workflow-id "workflow-other")))))
    (is (= :asset-mismatch
           (:reason (route/verify-withdrawal-attribution
                     (assoc base :token :token/other)))))
    (is (= :beneficiary-mismatch
           (:reason (route/verify-withdrawal-attribution
                     (assoc base :recipient "0x000000000000000000000000000000000000b00b")))))
    (is (= :bounty-amount-mismatch
           (:reason (route/verify-withdrawal-attribution
                     (assoc base :category-deltas (categories 69)
                            :withdrawn-amount 69)))))
    (is (= :withdrawal-amount-mismatch
           (:reason (route/verify-withdrawal-attribution
                     (assoc base :withdrawn-amount 71)))))))

(deftest route-tampering-is-rejected
  (let [r (build)]
    (is (= :route-root-mismatch
           (:reason (route/verify-route (assoc r :route/amount 71) [r]))))
    (is (= :invalid-route-shape
           (:reason (route/verify-route (assoc r :uncommitted :field) [r]))))
    (let [malformed (assoc r :route/events [42])
          malformed (assoc malformed :route/root (route/route-root malformed))]
      (is (= :invalid-route-event
             (:reason (route/verify-route malformed [malformed])))))
    (is (= :invalid-route-shape
           (:reason (route/verify-route 42 [42]))))
    (is (= {:valid? false :reason :invalid-route}
           (route/append-event 42 {})))
    (is (= :invalid-route-set
           (:reason (route/verify-route r nil))))))

(deftest payout-context-substitution-is-rejected
  (let [r (build)
        base {:route r :expected-obligation-root (:route/obligation-root r)
              :workflow-id "workflow-17" :token :token/usdc
              :recipient (:route/beneficiary r) :withdrawn-amount 70
              :independently-reconstructed? true
              :category-deltas (categories 70)}]
    (testing "a route cannot be rebound to a different obligation or trust context"
      (doseq [field [:route/obligation-root :route/payable-root
                     :route/backing-root :route/chain-instance-root
                     :route/funding-source-root]]
        (let [mutated (assoc r field (root-ref \f))]
          (is (= :invalid-route
                 (:reason (route/verify-withdrawal-attribution
                           (assoc base :route mutated))))
              (str "reject substituted " field))))
      (let [other-obligation (root-ref \f)]
        (is (= :obligation-mismatch
               (:reason (route/verify-withdrawal-attribution
                         (assoc base :expected-obligation-root other-obligation))))
            "payout attribution must use the route for the requested obligation")))
    (testing "unknown or negative reconstructed categories fail closed"
      (is (= :claim-categories-incomplete
             (:reason (route/verify-withdrawal-attribution
                       (assoc base :category-deltas
                              (assoc (categories 70) :unclassified/credit 0))))))
      (is (= :invalid-claim-category-amount
             (:reason (route/verify-withdrawal-attribution
                       (assoc base :category-deltas
                              (categories 70 :settlement/yield -1)))))))))

(deftest substituted-event-reference-is-not-receipt-authentication
  (let [r (build)
        deposited (:route (append-event r (event-data :deposit \a)))
        substituted-event (assoc (first (:route/events deposited))
                                 :event/evidence-root (root-ref \f))
        substituted (assoc deposited :route/events [substituted-event])
        substituted (assoc substituted :route/root (route/route-root substituted))
        result (route/verify-route substituted [substituted])]
    ;; The route only validates that this is a well-formed referenced root.
    ;; It has no receipt verifier or trusted source binding, so the pointer
    ;; substitution must not be described as detected/authenticated here.
    (is (:valid? result))
    (is (= :model-only (:trust-class result)))))

(deftest escrow-input-events-are-bound-and-transitions-fail-closed
  (let [r (build)
        deposit (:route (append-event r (event-data :deposit \a)))
        release (append-event deposit (event-data :release \b))]
    (is (:valid? release))
    (is (= :model-only (:trust-class (route/verify-route (:route release)
                                                          [(:route release)]))))
    (is (= :stale-or-invalid-route-transition
           (:reason (append-event r (event-data :release \b)))))
    (is (= :stale-or-duplicate-route-event
           (:reason (route/append-event
                     r (assoc (event-data :deposit \c)
                              :event/sequence 1.5
                              :event/predecessor-root (:route/root r))))))
    (is (= :invalid-route-event
           (:reason (append-event r (assoc (event-data :deposit \c)
                                           :event/funding-source-root (root-ref \f))))))
    (is (= :invalid-route-event
           (:reason (append-event r (assoc (event-data :deposit \c)
                                           :event/chain-instance-root (root-ref \f))))))
    (is (= :invalid-route-event
           (:reason (append-event r (assoc (event-data :deposit \c)
                                           :event/amount 69)))))
    (is (= :invalid-route-event
           (:reason (append-event r (assoc (event-data :deposit \c)
                                           :event/workflow-id "workflow-other")))))
    (is (= :invalid-route-event
           (:reason (append-event r (assoc (event-data :deposit \c)
                                           :event/asset :token/other)))))
    (is (= :stale-or-duplicate-route-event
           (:reason (route/append-event deposit
                                        (assoc (first (:route/events deposit))
                                               :event/predecessor-root (:route/root r))))))
    (let [cancelled (:route (append-event deposit (event-data :cancellation \c)))]
      (is (= :stale-or-invalid-route-transition
             (:reason (append-event cancelled (event-data :release \d))))))))

(deftest failed-withdrawal-and-dispute-retain-unsettled-liability
  (let [r (build)
        deposited (:route (append-event r (event-data :deposit \a)))
        released (:route (append-event deposited (event-data :release \b)))
        claimable (:route (append-event released (event-data :claimable \c)))
        submitted (:route (append-event claimable (event-data :withdrawal-submitted \d)))
        failed (:route (append-event submitted (event-data :withdrawal-failed \e)))
        checked (route/verify-route failed [failed])]
    (is (:valid? checked))
    (is (= :claimable (:route-state checked)))
    (is (= 70 (:route/amount failed)) "failed withdrawal does not clear liability")
    (is (= :stale-or-invalid-route-transition
           (:reason (append-event submitted (event-data :withdrawal-submitted \f))))
        "a second withdrawal submission cannot be recorded before the first resolves")
    (is (= :withdrawal-amount-mismatch
           (:reason (route/verify-withdrawal-attribution
                     {:route r :expected-obligation-root (:route/obligation-root r)
                      :workflow-id "workflow-17" :token :token/usdc
                      :recipient (:route/beneficiary r) :withdrawn-amount 69
                      :independently-reconstructed? true
                      :category-deltas (categories 70)})))
        "a partial transfer does not discharge the full obligation")
    (let [disputed (:route (append-event released (event-data :dispute \f)))]
      (is (= :disputed (:route-state (route/verify-route disputed [disputed]))))
      (is (= :stale-or-invalid-route-transition
             (:reason (append-event disputed (event-data :claimable \a))))))))

(deftest submitted-withdrawal-evidence-reference-is-not-payment
  (let [r (build)
        deposited (:route (append-event r (event-data :deposit \a)))
        released (:route (append-event deposited (event-data :release \b)))
        claimable (:route (append-event released (event-data :claimable \c)))
        ;; This is only a syntactically valid pointer; no receipt or signature
        ;; is supplied to or authenticated by the route adapter.
        submitted (:route (append-event claimable
                                        (event-data :withdrawal-submitted \f)))
        verified (route/verify-route submitted [submitted])]
    (is (:valid? verified))
    (is (= :model-only (:trust-class verified)))
    (is (= :withdrawal-submitted (:route-state verified)))
    (is (= 70 (:route/amount submitted)))
    (is (not (contains? verified :paid)))
    (is (not (contains? verified :settlement/root)))))
