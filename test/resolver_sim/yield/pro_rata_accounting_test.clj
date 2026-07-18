(ns resolver-sim.yield.pro-rata-accounting-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.yield.invariants :as inv]
                        [resolver-sim.yield.partial-fill :as pf]
                                    [resolver-sim.yield.modules.liquid-lending :as ll]
            [resolver-sim.yield.pro-rata-propagation-policy :as propagation-policy]
                                                [resolver-sim.yield.invariant-catalog :as catalog]))

(defn- propagation [entries]
  {:propagation/id "p1" :token :USDC
   :participants [{:participant-id "alice" :fulfilled 40 :origin {:obligation-id "oa"}}
                  {:participant-id "bob" :fulfilled 20 :origin {:obligation-id "ob"}}]
   :accounting-entries entries})

(def valid-entries
  [{:entry/type :debit :account :shared-liquidity :token :USDC :delta -60}
   {:entry/type :credit :account :withdrawn :token :USDC :participant-id "alice" :obligation-id "oa" :delta 40}
   {:entry/type :credit :account :withdrawn :token :USDC :participant-id "bob" :obligation-id "ob" :delta 20}])

(defn- accounting-world []
  (let [entries valid-entries
        entry-hash (pf/accounting-entry-set-hash entries)
        policy (propagation-policy/normalize-and-validate propagation-policy/shared-withdrawal-policy)
        policy-hash (:policy/hash policy)
        p {:propagation/id "p1" :calculation-ref "c1" :outcome-ref "o1" :token :USDC
           :propagation-policy (propagation-policy/policy-reference policy)
           :summary {:available 100 :allocated 60 :unallocated-residual 40}
                                 :residual {:destination :remain-in-shared-liquidity}
                                 :participants [{:participant-id "alice" :eligible-obligation 40 :fulfilled 40 :deferred 0 :unmet 0 :waived 0 :obligation-after 0 :origin {:obligation-id "oa"}}
                                                {:participant-id "bob" :eligible-obligation 20 :fulfilled 20 :deferred 0 :unmet 0 :waived 0 :obligation-after 0 :origin {:obligation-id "ob"}}]
                                                :accounting-entries entries :accounting-entry-set-hash entry-hash}
        app {:schema-version "pro-rata-propagation-application.v2" :propagation-id "p1"
             :calculation-id "c1" :outcome-hash "o1" :policy-hash policy-hash
             :application-key [:pro-rata-propagation "c1" "o1" policy-hash]
             :application-order {:schema-version "pro-rata-application-order.v1" :step 1 :event-id 0}
             :accounting-entry-set-hash entry-hash
             :source-account {:account :shared-liquidity :token :USDC :before 100 :delta -60 :after 40}
                          :residual {:token :USDC :available 100 :allocated 60 :amount 40
                                     :destination :remain-in-shared-liquidity}
                          :participants [{:participant-id "alice" :obligation-id "oa" :withdrawn {:token :USDC :before 0 :delta 40 :after 40}
                                         :obligation {:before 40} :cumulative-fulfilled {:before 0 :delta 40 :after 40}}
                                        {:participant-id "bob" :obligation-id "ob" :withdrawn {:token :USDC :before 0 :delta 20 :after 20}
                                         :obligation {:before 20} :cumulative-fulfilled {:before 0 :delta 20 :after 20}}]}]
    {:yield/pro-rata-propagations {"p1" p}
     :yield/applied-pro-rata-propagations {"p1" app}
     :total-held {:USDC 40}
     :yield/withdrawn {:USDC {"alice" 40 "bob" 20}}
     :yield/positions {"alice" {:status :withdrawn :token :USDC}
                       "bob" {:status :withdrawn :token :USDC}}}))

(deftest accounting-invariant-is-runtime-registered
  (let [world (accounting-world)
        results (inv/check-all world)]
    (is (some #{:yield/pro-rata-accounting-reconciles} catalog/default-runtime-invariant-ids))
    (is (contains? results :yield/pro-rata-accounting-reconciles))
    (is (true? (get-in results [:yield/pro-rata-accounting-reconciles :holds?])))))

(deftest accounting-chain-mutations
  (let [a {:propagation-id "p1" :application-order {:schema-version "pro-rata-application-order.v1" :step 1 :event-id 0}
           :source-account {:token :USDC :before 100 :after 60}
           :participants [{:participant-id "alice" :obligation-id "oa" :withdrawn {:token :USDC :delta 40 :before 0 :after 40}}]}
        b {:propagation-id "p2" :application-order {:schema-version "pro-rata-application-order.v1" :step 2 :event-id 0}
           :source-account {:token :USDC :before 60 :after 30}
           :participants [{:participant-id "alice" :obligation-id "oa" :withdrawn {:token :USDC :delta 30 :before 40 :after 70}}]}
        world {:total-held {:USDC 30} :yield/withdrawn {:USDC {"alice" 70}}}]
    (is (empty? (inv/chain-violations world [a b])))
    (is (some #(= :source-balance-chain-broken (:reason %))
              (inv/chain-violations world [a (assoc-in b [:source-account :before] 59)])))
    (is (some #(= :participant-balance-chain-broken (:reason %))
              (inv/chain-violations world [a (assoc-in b [:participants 0 :withdrawn :before] 39)])))
    (is (some #(= :application-order-duplicate (:reason %))
              (inv/chain-violations world [a (assoc b :application-order {:schema-version "pro-rata-application-order.v1" :step 1 :event-id 0})])))
    (is (some #(= :application-order-missing (:reason %))
              (inv/chain-violations world [a (assoc b :application-order {:step 2})])))))

(deftest closure-history-mutations
  (let [prior {:position/id "alice/deferred/1" :position/root-obligation-id "oa" :position/current-amount 20}
          closed {:position/id "alice/deferred/1" :position/root-obligation-id "oa"
                  :position/token :USDC :position/participant-id "alice" :position/current-amount 20
                :position/status :closed :position/closed-by-propagation-id "p2"}
        app {:propagation-id "p2" :participants [{:participant-id "alice"
                                                    :position-before {:token :USDC :deferred-position prior}
                                                    :position-after {:deferred-position-history {"alice/deferred/1" closed}}}]}]
    (is (empty? (inv/closed-history-violations [app])))
    (is (some #(= :closed-position-history-missing (:reason %))
              (inv/closed-history-violations [(assoc-in app [:participants 0 :position-after :deferred-position-history] {})])))
    (is (some #(= :closed-position-history-identity-mismatch (:reason %))
              (inv/closed-history-violations [(assoc-in app [:participants 0 :position-after :deferred-position-history "alice/deferred/1" :position/root-obligation-id] "wrong")])))
    (is (some #(= :closed-position-history-closure-mismatch (:reason %))
              (inv/closed-history-violations [(assoc-in app [:participants 0 :position-after :deferred-position-history "alice/deferred/1" :position/closed-by-propagation-id] "wrong")])))))

(deftest cumulative-fulfilment-mutations
  (let [application {:propagation-id "p1"
                     :participants [{:participant-id "alice" :obligation {:before 100}
                                     :cumulative-fulfilled {:before 20 :delta 30 :after 50}}]}]
    (is (empty? (inv/cumulative-fulfilment-violations [application])))
    (is (some #(= :cumulative-fulfilment-arithmetic-failed (:reason %))
              (inv/cumulative-fulfilment-violations [(assoc-in application [:participants 0 :cumulative-fulfilled :after] 49)])))
    (is (some #(= :cumulative-fulfilment-exceeded (:reason %))
              (inv/cumulative-fulfilment-violations [(assoc-in application [:participants 0 :cumulative-fulfilled :after] 101)])))))

(deftest closed-history-is-keyed-and-immutable
  (let [a {:position/id "alice/deferred/1" :position/status :closed :position/current-amount 0}
        b {:position/id "alice/deferred/2" :position/status :closed :position/current-amount 0}
        history (ll/record-closed-deferred-position {} a)]
    (is (= {"alice/deferred/1" a} history))
    (is (= history (ll/record-closed-deferred-position history a)))
    (is (= #{"alice/deferred/1" "alice/deferred/2"}
           (set (keys (ll/record-closed-deferred-position history b)))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"history conflict"
                          (ll/record-closed-deferred-position history
                                                               (assoc a :position/current-amount 1))))))

(deftest deferred-position-state-mutations
  (let [prop [{:propagation/id "p1" :token :USDC
               :participants [{:participant-id "alice" :deferred 20 :origin {:obligation-id "oa"}}]}]
        valid {:yield/positions {"alice" {:token :USDC :deferred-position {:position/current-amount 20 :position/root-obligation-id "oa" :position/type :deferred-withdrawal :position/eligibility :later-liquidity :position/origin-propagation-id "p1"}}}}
        active {:yield/positions {"alice" {:token :USDC :deferred-position {:position/current-amount 20 :position/root-obligation-id "oa" :position/type :deferred-withdrawal :position/eligibility :later-liquidity :position/origin-propagation-id "p1"}}}}
        closed-prop [{:propagation/id "p1" :token :USDC :participants [{:participant-id "alice" :deferred 0}]}]]
    (is (empty? (inv/deferred-state-violations valid prop)))
    (is (some #(= :deferred-position-missing (:reason %))
              (inv/deferred-state-violations {:yield/positions {}} prop)))
    (is (some #(= :deferred-position-amount-mismatch (:reason %))
              (inv/deferred-state-violations (assoc-in valid [:yield/positions "alice" :deferred-position :position/current-amount] 19) prop)))
    (is (some #(= :deferred-position-token-mismatch (:reason %))
              (inv/deferred-state-violations (assoc-in valid [:yield/positions "alice" :token] :DAI) prop)))
    (is (some #(= :deferred-position-root-obligation-mismatch (:reason %))
              (inv/deferred-state-violations (assoc-in valid [:yield/positions "alice" :deferred-position :position/root-obligation-id] "wrong") prop)))
    (is (some #(= :deferred-position-type-mismatch (:reason %))
              (inv/deferred-state-violations (assoc-in valid [:yield/positions "alice" :deferred-position :position/type] :other) prop)))
    (is (some #(= :deferred-position-eligibility-mismatch (:reason %))
              (inv/deferred-state-violations (assoc-in valid [:yield/positions "alice" :deferred-position :position/eligibility] :never) prop)))
    (is (some #(= :deferred-position-origin-mismatch (:reason %))
              (inv/deferred-state-violations (assoc-in valid [:yield/positions "alice" :deferred-position :position/origin-propagation-id] "wrong") prop)))
    (is (some #(= :fulfilled-position-still-active (:reason %))
              (inv/deferred-state-violations active closed-prop)))))

(deftest full-accounting-invariant
  (let [world (accounting-world)]
    (is (:holds? (inv/check-pro-rata-accounting-reconciles world)))
    (is (some #(= :participant-credit-mismatch (:reason %))
              (:violations (inv/check-pro-rata-accounting-reconciles
                            (assoc-in world [:yield/pro-rata-propagations "p1" :accounting-entries 1 :delta] 20)))))
    (is (some #(= :application-obligation-id-mismatch (:reason %))
              (:violations (inv/check-pro-rata-accounting-reconciles
                            (assoc-in world [:yield/applied-pro-rata-propagations "p1" :participants 0 :obligation-id] "wrong")))))
    (is (some #(= :application-obligation-token-mismatch (:reason %))
              (:violations (inv/check-pro-rata-accounting-reconciles
                            (assoc-in world [:yield/applied-pro-rata-propagations "p1" :participants 0 :withdrawn :token] :DAI)))))
    (is (some #(= :application-obligation-participant-mismatch (:reason %))
              (:violations (inv/check-pro-rata-accounting-reconciles
                            (assoc-in world [:yield/applied-pro-rata-propagations "p1" :participants 0 :participant-id] "mallory")))))
    (is (some #(= :application-withdrawn-delta-mismatch (:reason %))
              (:violations (inv/check-pro-rata-accounting-reconciles
                            (-> world
                                (assoc-in [:yield/applied-pro-rata-propagations "p1" :participants 0 :withdrawn :delta] 39)
                                (assoc-in [:yield/applied-pro-rata-propagations "p1" :participants 0 :withdrawn :after] 39))))))
    (is (= :fail (get-in (inv/check-pro-rata-accounting-reconciles
                           (assoc-in world [:yield/applied-pro-rata-propagations "p1" :participants 0 :withdrawn :after] 39))
                          [:checks :participant-withdrawn-arithmetic])))
    (is (some #(= :application-key-mismatch (:reason %))
              (:violations (inv/check-pro-rata-accounting-reconciles
                            (assoc-in world [:yield/applied-pro-rata-propagations "p1" :application-key]
                                      [:pro-rata-propagation "o1" "c1" "policy-hash"])))))
    (is (some #(= :propagation-accounting-entry-hash-mismatch (:reason %))
              (:violations (inv/check-pro-rata-accounting-reconciles
                            (assoc-in world [:yield/pro-rata-propagations "p1" :accounting-entry-set-hash] "bad")))))
    (is (some #(= :latest-source-balance-mismatch (:reason %))
              (:violations (inv/check-pro-rata-accounting-reconciles
                            (assoc-in world [:total-held :USDC] 39)))))
    (is (some #(= :latest-authoritative-withdrawn-balance-mismatch (:reason %))
              (:violations (inv/check-pro-rata-accounting-reconciles
                            (assoc-in world [:yield/withdrawn :USDC "alice"] 39)))))
    (is (some #(= :residual-destination-mismatch (:reason %))
              (:violations (inv/check-pro-rata-accounting-reconciles
                            (assoc-in world [:yield/applied-pro-rata-propagations "p1" :residual :destination] :refund)))))
    (is (some #(= :residual-token-mismatch (:reason %))
              (:violations (inv/check-pro-rata-accounting-reconciles
                            (assoc-in world [:yield/applied-pro-rata-propagations "p1" :residual :token] :DAI)))))
    (is (some #(= :source-account-policy-mismatch (:reason %))
              (:violations (inv/check-pro-rata-accounting-reconciles
                            (assoc-in world [:yield/applied-pro-rata-propagations "p1" :source-account :account] :other-source)))))
    (is (some #(= :application-source-account-policy-mismatch (:reason %))
              (:violations (inv/check-pro-rata-accounting-reconciles
                            (assoc-in world [:yield/applied-pro-rata-propagations "p1" :source-account :account] :other-source)))))
    (is (some #(= :propagation-policy-hash-mismatch (:reason %))
              (:violations (inv/check-pro-rata-accounting-reconciles
                            (assoc-in world [:yield/pro-rata-propagations "p1" :propagation-policy :policy/hash] "bad")))))
    (is (some #(= :shortfall-classification-policy-mismatch (:reason %))
              (:violations (inv/check-pro-rata-accounting-reconciles
                            (assoc-in world [:yield/pro-rata-propagations "p1" :participants 0 :unmet] 1)))))
    (is (some #(= :participant-account-policy-mismatch (:reason %))
              (:violations (inv/check-pro-rata-accounting-reconciles
                            (assoc-in world [:yield/pro-rata-propagations "p1" :accounting-entries 1 :account] :other-credit)))))))

(deftest exact-credit-reconciliation
  (testing "valid credits form a bijection with fulfilments"
    (is (empty? (inv/exact-credit-violations [(propagation valid-entries)]))))
  (testing "amount swap is detected without relying on totals"
    (let [entries (assoc valid-entries 1 (assoc (nth valid-entries 1) :delta 20))
          entries (assoc entries 2 (assoc (nth entries 2) :delta 40))]
      (is (some #(= :participant-credit-mismatch (:reason %))
                (inv/exact-credit-violations [(propagation entries)])))))
  (testing "duplicate and orphan credits are visible"
    (is (some #(= :participant-credit-duplicate (:reason %))
              (inv/exact-credit-violations [(propagation (conj valid-entries (nth valid-entries 1)))])))
    (is (some #(= :orphan-participant-credit (:reason %))
              (inv/exact-credit-violations [(propagation (conj valid-entries
                                                               {:entry/type :credit :account :withdrawn :token :USDC
                                                                :participant-id "carol" :obligation-id "oc" :delta 1}))]))))
  (testing "identity-near matches diagnose the dimension that changed"
    (let [token-mismatch (assoc valid-entries 1 (assoc (nth valid-entries 1) :token :DAI))
          owner-mismatch (assoc valid-entries 1 (assoc (nth valid-entries 1) :participant-id "mallory"))
          obligation-mismatch (assoc valid-entries 1 (assoc (nth valid-entries 1) :obligation-id "other"))]
      (is (some #(= :participant-credit-token-mismatch (:reason %)) (inv/exact-credit-violations [(propagation token-mismatch)])))
      (is (some #(= :participant-credit-owner-mismatch (:reason %)) (inv/exact-credit-violations [(propagation owner-mismatch)])))
      (is (some #(= :participant-credit-obligation-mismatch (:reason %)) (inv/exact-credit-violations [(propagation obligation-mismatch)])))))
  (testing "missing obligation identities cannot match"
    (let [p (assoc-in (propagation valid-entries) [:participants 0 :origin :obligation-id] nil)]
      (is (some #(= :participant-obligation-id-missing (:reason %))
                (inv/exact-credit-violations [p]))))))
