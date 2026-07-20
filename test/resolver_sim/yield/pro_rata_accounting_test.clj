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
            :propagation/hash "propagation-hash" :propagation/content-hash "content-hash"
            :propagation-policy (propagation-policy/policy-reference policy)
            :summary {:available 100 :allocated 60 :unallocated-residual 40}
            :residual {:destination :remain-in-shared-liquidity}
            :participants [{:participant-id "alice" :eligible-obligation 40 :fulfilled 40 :deferred 0 :unmet 0 :waived 0 :obligation-after 0 :origin {:obligation-id "oa"}}
                           {:participant-id "bob" :eligible-obligation 20 :fulfilled 20 :deferred 0 :unmet 0 :waived 0 :obligation-after 0 :origin {:obligation-id "ob"}}]
            :accounting-entries entries :accounting-entry-set-hash entry-hash}
app {:schema-version "pro-rata-propagation-application.v3" :propagation-id "p1"
               :propagation/reference {:propagation/id "p1" :propagation/hash "propagation-hash" :propagation/content-hash "content-hash"}
               :calculation-id "c1" :outcome-hash "o1" :policy-hash policy-hash
               :application-key [:pro-rata-propagation "c1" "o1" policy-hash]
              :application-order {:schema-version "pro-rata-application-order.v2" :step 1 :event-id 0}
              :accounting-entry-set-hash entry-hash
              :source-account {:account :shared-liquidity :token :USDC :before 100 :delta -60 :after 40}
                          :residual {:token :USDC :available 100 :allocated 60 :amount 40
                                     :destination :remain-in-shared-liquidity}
                          :participants [{:participant-id "alice" :obligation-id "oa" :withdrawn {:token :USDC :before 0 :delta 40 :after 40}
                                         :obligation {:before 40} :cumulative-fulfilled {:before 0 :delta 40 :after 40}}
                                        {:participant-id "bob" :obligation-id "ob" :withdrawn {:token :USDC :before 0 :delta 20 :after 20}
                                         :obligation {:before 20} :cumulative-fulfilled {:before 0 :delta 20 :after 20}}]}
        app (assoc app :application/hash (pf/application-hash app))]
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
   (let [a {:propagation-id "p1" :application-order {:schema-version "pro-rata-application-order.v2" :step 1 :event-id 0}
            :source-account {:token :USDC :before 100 :after 60}
            :participants [{:participant-id "alice" :obligation-id "oa" :withdrawn {:token :USDC :delta 40 :before 0 :after 40}}]}
         b {:propagation-id "p2" :application-order {:schema-version "pro-rata-application-order.v2" :step 2 :event-id 0}
            :source-account {:token :USDC :before 60 :after 30}
            :participants [{:participant-id "alice" :obligation-id "oa" :withdrawn {:token :USDC :delta 30 :before 40 :after 70}}]}
         world {:total-held {:USDC 30} :yield/withdrawn {:USDC {"alice" 70}}}]
     (is (empty? (inv/chain-violations world [a b])))
     (is (some #(= :source-balance-chain-broken (:reason %))
               (inv/chain-violations world [a (assoc-in b [:source-account :before] 59)])))
     (is (some #(= :participant-balance-chain-broken (:reason %))
               (inv/chain-violations world [a (assoc-in b [:participants 0 :withdrawn :before] 39)])))
     (is (some #(= :application-order-duplicate (:reason %))
               (inv/chain-violations world [a (assoc b :application-order {:schema-version "pro-rata-application-order.v2" :step 1 :event-id 0})])))
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

(deftest shared-withdrawal-position-classification
  (let [eligible-deferred {:position/type :deferred-withdrawal
                           :position/status :active
                           :position/eligibility :later-liquidity
                           :position/current-amount 20}]
    (testing "active base position without deferred lineage is an ordinary request"
      (is (= {:classification :ordinary-base-request :amount/source :base-position}
             (ll/classify-shared-withdrawal-position {:status :active}))))
    (testing "unwinding position with eligible deferred lineage uses its residual amount"
      (is (= {:classification :eligible-deferred-request :amount/source :deferred-position}
             (ll/classify-shared-withdrawal-position {:status :unwinding
                                                       :deferred-position eligible-deferred}))))
    (testing "active base position with stale deferred lineage is contradictory"
      (let [result (ll/classify-shared-withdrawal-position
                    {:status :active
                     :deferred-position (assoc eligible-deferred :position/status :closed)})]
        (is (= :position-state-contradiction (:classification result)))
        (is (= :active-base-with-stale-deferred-position (:reason result)))))
    (testing "unwinding position without eligible deferred lineage is incomplete"
      (let [result (ll/classify-shared-withdrawal-position
                    {:status :unwinding
                     :deferred-position (assoc eligible-deferred :position/status :closed)})]
        (is (= :invalid-incomplete-deferred-state (:classification result)))
        (is (= :unwinding-without-eligible-deferred-position (:reason result)))))))

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
    (is (some #(= :application-key-policy-mismatch (:reason %))
              (:violations (inv/check-pro-rata-accounting-reconciles
                            (assoc-in world [:yield/applied-pro-rata-propagations "p1" :application-key]
                                      [:pro-rata-propagation "o1" "c1" "policy-hash"])))))
    (is (some #(= :propagation-accounting-entry-hash-mismatch (:reason %))
              (:violations (inv/check-pro-rata-accounting-reconciles
                            (assoc-in world [:yield/pro-rata-propagations "p1" :accounting-entry-set-hash] "bad")))))
    (is (not (some #(= :latest-source-balance-mismatch (:reason %))
                   (:violations (inv/check-pro-rata-accounting-reconciles
                                 (assoc-in world [:total-held :USDC] 39))))))
    (is (some #(= :latest-authoritative-withdrawn-balance-mismatch (:reason %))
              (:violations (inv/check-pro-rata-accounting-reconciles
                            (assoc-in world [:yield/withdrawn :USDC "alice"] 39)))))
    (is (some #(= :residual-destination-policy-mismatch (:reason %))
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

;;; Output hash tests - token-dimensioned application output commitment

(defn- create-test-application-with-output []
  "Create a test application with output hash for use in output-hash tests."
  (let [entries valid-entries
        entry-hash (pf/accounting-entry-set-hash entries)
        policy (propagation-policy/normalize-and-validate propagation-policy/shared-withdrawal-policy)
        policy-hash (:policy/hash policy)
        p {:propagation/id "p1" :calculation-ref "c1" :outcome-ref "o1" :token :USDC
           :propagation/hash "propagation-hash" :propagation/content-hash "content-hash"
           :propagation-policy (propagation-policy/policy-reference policy)
           :summary {:available 100 :allocated 60 :unallocated-residual 40}
           :residual {:destination :remain-in-shared-liquidity}
           :participants [{:participant-id "alice" :eligible-obligation 40 :fulfilled 40 :deferred 0 :unmet 0 :waived 0 :obligation-after 0 :origin {:obligation-id "oa"}}
                          {:participant-id "bob" :eligible-obligation 20 :fulfilled 20 :deferred 0 :unmet 0 :waived 0 :obligation-after 0 :origin {:obligation-id "ob"}}]
           :accounting-entries entries :accounting-entry-set-hash entry-hash}
        app {:schema-version "pro-rata-propagation-application.v3" :propagation-id "p1"
             :propagation/reference {:propagation/id "p1" :propagation/hash "propagation-hash" :propagation/content-hash "content-hash"}
             :calculation-id "c1" :outcome-hash "o1" :policy-hash policy-hash
             :application-key [:pro-rata-propagation "c1" "o1" policy-hash]
             :application-order {:schema-version "pro-rata-application-order.v2" :step 1 :event-id 0}
             :accounting-entry-set-hash entry-hash
             :source-account {:account :shared-liquidity :token :USDC :before 100 :delta -60 :after 40}
             :residual {:token :USDC :available 100 :allocated 60 :amount 40
                        :destination :remain-in-shared-liquidity}
             :participants [{:participant-id "alice" :obligation-id "oa" :withdrawn {:token :USDC :before 0 :delta 40 :after 40}
                            :obligation {:before 40 :fulfilled 40 :deferred 0 :unmet 0 :waived 0 :after 0}
                            :cumulative-fulfilled {:before 0 :delta 40 :after 40}}
                           {:participant-id "bob" :obligation-id "ob" :withdrawn {:token :USDC :before 0 :delta 20 :after 20}
                            :obligation {:before 20 :fulfilled 20 :deferred 0 :unmet 0 :waived 0 :after 0}
                            :cumulative-fulfilled {:before 0 :delta 20 :after 20}}]}
        app (assoc app :application/hash (pf/application-hash app)
                      :application/output {:schema-version "pro-rata-application-output.v1"
                                           :hash-algorithm "sha256"
                                           :hash (pf/pro-rata-application-output-hash app p)})]
    [app p]))

(deftest output-hash-generation-stable
  (testing "Same semantic output produces identical hash on repeated generation"
    (let [[app p] (create-test-application-with-output)
          hash1 (pf/pro-rata-application-output-hash app p)
          hash2 (pf/pro-rata-application-output-hash app p)]
      (is (= hash1 hash2))))
  
  (testing "Output projection is deterministically ordered"
    (let [[app p] (create-test-application-with-output)
          proj1 (pf/pro-rata-application-output-projection app p)
          proj2 (pf/pro-rata-application-output-projection app p)]
      (is (= proj1 proj2))))
  
  (testing "Reordering participants does not change hash"
    (let [[app p] (create-test-application-with-output)
          participants-reversed (assoc app :participants (reverse (:participants app)))
          hash1 (pf/pro-rata-application-output-hash app p)
          hash2 (pf/pro-rata-application-output-hash participants-reversed p)]
      (is (= hash1 hash2)))))

(deftest output-hash-token-separation
  (testing "Different tokens produce different hashes"
    (let [[app p] (create-test-application-with-output)
          app-dai (assoc-in app [:source-account :token] :DAI)
          p-dai (assoc p :token :DAI)
          hash-usdc (pf/pro-rata-application-output-hash app p)
          hash-dai (pf/pro-rata-application-output-hash app-dai p-dai)]
      (is (not= hash-usdc hash-dai))))
  
  (testing "Changing only source token invalidates hash"
    (let [[app p] (create-test-application-with-output)
          app-wrong-token (assoc-in app [:source-account :token] :DAI)
          hash1 (pf/pro-rata-application-output-hash app p)
          hash2 (pf/pro-rata-application-output-hash app-wrong-token p)]
      (is (not= hash1 hash2))))
  
  (testing "Changing only one participant credit token invalidates hash"
    (let [[app p] (create-test-application-with-output)
          alice-with-dai (update-in app [:participants 0] assoc-in [:withdrawn :token] :DAI)
          hash1 (pf/pro-rata-application-output-hash app p)
          hash2 (pf/pro-rata-application-output-hash alice-with-dai p)]
      (is (not= hash1 hash2))))
  
  (testing "Changing only one accounting entry token invalidates hash"
    (let [[app p] (create-test-application-with-output)
          entries-wrong-token (assoc (vec (:accounting-entries p)) 1 (assoc (nth (:accounting-entries p) 1) :token :DAI))
          p-wrong-token (assoc p :accounting-entries entries-wrong-token)
          hash1 (pf/pro-rata-application-output-hash app p)
          hash2 (pf/pro-rata-application-output-hash app p-wrong-token)]
      (is (not= hash1 hash2)))))

(deftest output-hash-output-integrity
  (testing "Mutating source balance-after invalidates hash"
    (let [[app p] (create-test-application-with-output)
          app-mut (assoc-in app [:source-account :after] 41)
          hash1 (pf/pro-rata-application-output-hash app p)
          hash2 (pf/pro-rata-application-output-hash app-mut p)]
      (is (not= hash1 hash2))))
  
  (testing "Mutating source debit invalidates hash"
    (let [[app p] (create-test-application-with-output)
          app-mut (assoc-in app [:source-account :delta] -61)
          hash1 (pf/pro-rata-application-output-hash app p)
          hash2 (pf/pro-rata-application-output-hash app-mut p)]
      (is (not= hash1 hash2))))
  
  (testing "Mutating participant credit invalidates hash"
    (let [[app p] (create-test-application-with-output)
          app-mut (assoc-in app [:participants 0 :withdrawn :delta] 41)
          hash1 (pf/pro-rata-application-output-hash app p)
          hash2 (pf/pro-rata-application-output-hash app-mut p)]
      (is (not= hash1 hash2))))
  
  (testing "Mutating participant balance-after invalidates hash"
    (let [[app p] (create-test-application-with-output)
          app-mut (assoc-in app [:participants 0 :withdrawn :after] 41)
          hash1 (pf/pro-rata-application-output-hash app p)
          hash2 (pf/pro-rata-application-output-hash app-mut p)]
      (is (not= hash1 hash2))))
  
  (testing "Mutating fulfilled amount invalidates hash"
    (let [[app p] (create-test-application-with-output)
          app-mut (assoc-in app [:participants 0 :obligation :fulfilled] 41)
          hash1 (pf/pro-rata-application-output-hash app p)
          hash2 (pf/pro-rata-application-output-hash app-mut p)]
      (is (not= hash1 hash2))))
  
  (testing "Mutating deferred amount invalidates hash"
    (let [[app p] (create-test-application-with-output)
          app-mut (assoc-in app [:participants 0 :obligation :deferred] 1)
          hash1 (pf/pro-rata-application-output-hash app p)
          hash2 (pf/pro-rata-application-output-hash app-mut p)]
      (is (not= hash1 hash2))))
  
  (testing "Mutating accounting entry amount invalidates hash"
    (let [[app p] (create-test-application-with-output)
          entries-mut (assoc (vec (:accounting-entries p)) 1 (assoc (nth (:accounting-entries p) 1) :delta 41))
          p-mut (assoc p :accounting-entries entries-mut)
          hash1 (pf/pro-rata-application-output-hash app p)
          hash2 (pf/pro-rata-application-output-hash app p-mut)]
      (is (not= hash1 hash2))))
  
  (testing "Mutating accounting entry set invalidates hash"
    (let [[app p] (create-test-application-with-output)
          entries-mut (vec (conj (:accounting-entries p) {:entry/type :credit :account :withdrawn :token :USDC :participant-id "carol" :obligation-id "oc" :delta 1}))
          p-mut (assoc p :accounting-entries entries-mut)
          hash1 (pf/pro-rata-application-output-hash app p)
          hash2 (pf/pro-rata-application-output-hash app p-mut)]
      (is (not= hash1 hash2))))
  
  (testing "Mutating participant identity invalidates hash"
    (let [[app p] (create-test-application-with-output)
          app-mut (assoc-in app [:participants 0 :participant-id] "eve")
          hash1 (pf/pro-rata-application-output-hash app p)
          hash2 (pf/pro-rata-application-output-hash app-mut p)]
      (is (not= hash1 hash2)))))

(deftest output-hash-in-accounting-verification
  (testing "Application with valid output hash passes verification"
    (let [world (accounting-world)
          [app p] (create-test-application-with-output)
          world-with-output (assoc-in world [:yield/applied-pro-rata-propagations "p1"] 
                                     (assoc app :propagation-id "p1"))
          world-with-prop (assoc-in world-with-output [:yield/pro-rata-propagations "p1"] p)
          results (inv/check-pro-rata-accounting-reconciles world-with-prop)]
      (is (true? (:holds? results)))
      (is (= :pass (get-in results [:checks :application-output-schema-valid])))
      (is (= :pass (get-in results [:checks :application-output-hash-valid])))))
  
  (testing "Application without output hash still passes verification (backward compatible)"
    (let [world (accounting-world)
          [app p] (create-test-application-with-output)
          app-no-output (dissoc app :application/output)
          world-with-output (assoc-in world [:yield/applied-pro-rata-propagations "p1"] 
                                     (assoc app-no-output :propagation-id "p1"))
          world-with-prop (assoc-in world-with-output [:yield/pro-rata-propagations "p1"] p)
          results (inv/check-pro-rata-accounting-reconciles world-with-prop)]
      (is (true? (:holds? results)))
      (is (= :pass (get-in results [:checks :application-output-hash-valid])))))
  
  (testing "Application with mismatched output hash fails verification"
    (let [world (accounting-world)
          [app p] (create-test-application-with-output)
          app-bad-hash (assoc-in app [:application/output :hash] "sha256:wronghash")
          world-with-output (assoc-in world [:yield/applied-pro-rata-propagations "p1"] 
                                     (assoc app-bad-hash :propagation-id "p1"))
          world-with-prop (assoc-in world-with-output [:yield/pro-rata-propagations "p1"] p)
          results (inv/check-pro-rata-accounting-reconciles world-with-prop)]
(is (false? (:holds? results)))
       (is (= :fail (get-in results [:checks :application-output-hash-valid])))))))
     (testing "Application with missing output schema fails verification"
       (let [world (accounting-world)

(deftest outcome-preimage-mutation-detected
  (testing "Decision preimage tampering is detected during verification"
  (let [decision-base {:settlement-mode :partial-fill
                       :requested {:alice 40 :bob 20}
                       :filled {:alice 40 :bob 20}
                       :deferred {:alice 0 :bob 0}
                       :haircut {}
                       :policy {:mode :pro-rata}
                       :evidence {:available-liquidity 100}}
        position {:owner/id "shared-pool" :position/id "shared-pool" :module/id :mod :token :USDC}
        decision (pf/decision-artifact position decision-base {:decision-source :test})
        p {:propagation/id "p1" :calculation-ref (:decision/id decision) :outcome-ref (:decision/hash decision)
           :token :USDC
           :participants [{:participant-id "alice" :fulfilled 40 :origin {:obligation-id "oa"}}
                          {:participant-id "bob" :fulfilled 20 :origin {:obligation-id "ob"}}]
           :accounting-entries valid-entries
           :accounting-entry-set-hash (pf/accounting-entry-set-hash valid-entries)}
        app {:schema-version "pro-rata-propagation-application.v3"
             :propagation-id "p1"
             :propagation/reference {:propagation/id "p1" :propagation/hash "propagation-hash" :propagation/content-hash "content-hash"}
             :calculation-id (:decision/id decision)
             :outcome-hash (:decision/hash decision)
             :application-key [:pro-rata-propagation (:decision/id decision) (:decision/hash decision) (:policy/hash (propagation-policy/normalize-and-validate propagation-policy/shared-withdrawal-policy))]
             :application-order {:schema-version "pro-rata-application-order.v2" :step 1 :event-id 0}
             :accounting-entry-set-hash (pf/accounting-entry-set-hash valid-entries)
             :source-account {:account :shared-liquidity :token :USDC :before 100 :delta -60 :after 40}
             :participants [{:participant-id "alice" :obligation-id "oa" :withdrawn {:token :USDC :before 0 :delta 40 :after 40}
                             :obligation {:before 40 :fulfilled 40 :deferred 0 :unmet 0 :waived 0 :after 0}
                             :cumulative-fulfilled {:before 0 :delta 40 :after 40}}
                            {:participant-id "bob" :obligation-id "ob" :withdrawn {:token :USDC :before 0 :delta 20 :after 20}
                             :obligation {:before 20 :fulfilled 20 :deferred 0 :unmet 0 :waived 0 :after 0}
                             :cumulative-fulfilled {:before 0 :delta 20 :after 20}}]}
        app (assoc app :application/hash (pf/application-hash app)
                      :application/output {:schema-version "pro-rata-application-output.v1"
                                           :hash-algorithm "sha256"
                                           :hash (pf/pro-rata-application-output-hash app p)})]
    (let [world {:yield/partial-fill-decisions {(:decision/id decision) decision}
                 :yield/pro-rata-propagations {"p1" p}
                 :yield/applied-pro-rata-propagations {"p1" app}
                 :total-held {:USDC 40}
                 :yield/withdrawn {:USDC {"alice" 40 "bob" 20}}
                 :yield/positions {"alice" {:status :withdrawn :token :USDC}
                                   "bob" {:status :withdrawn :token :USDC}}}
          results-valid (inv/check-pro-rata-accounting-reconciles world)
          world-tampered (assoc-in world [:yield/partial-fill-decisions (:decision/id decision) :decision/preimage] "tampered")
          results-tampered (inv/check-pro-rata-accounting-reconciles world-tampered)]
      (is (true? (:holds? results-valid)))
      (is (false? (:holds? results-tampered)))
      (is (some #(= :decision-hash-mismatch (:reason %)) (:violations results-tampered))))))
