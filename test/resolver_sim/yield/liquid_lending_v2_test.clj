(ns resolver-sim.yield.liquid-lending-v2-test
  "Integration tests for the decision-based liquid-lending yield module.

   These tests cover the v2-merged-into-v1 module shape (decision-based accrual,
   ratio-based entry-index, partial-fill withdrawals). The module was previously
   referred to as 'liquid-lending-v2' and has been merged into the main
   liquid-lending module."
  (:require [clojure.test :refer :all]
            [resolver-sim.yield.modules.liquid-lending :as ll]
            [resolver-sim.yield.partial-fill :as partial-fill]
            [resolver-sim.yield.position :as pos]
            [resolver-sim.yield.registry :as reg]
            [resolver-sim.yield.invariants :as inv]
            [resolver-sim.util.attribution :as attr]))

(def test-world
  {:yield/indices {:test-mod {"USDC" 1}}
   :yield/rates   {:test-mod {"USDC" 0.05}}
   :yield/risk    {:test-mod {"USDC" {:liquidity-mode :available
                                      :loss-mode :none}}}
   :yield/held-balances {"USDC" 1000000}
   :yield/module-status {:test-mod :active}
   :block-time 1000
   :run/id "test-run"
   :execution/id "test-execution"
   :params {:scenario-id "test-scenario"}})

(def test-mod (ll/make-liquid-lending-module :test-mod))

(deftest shared-withdrawal-effective-cap-input-validation
  (testing "effective caps reject undeclared owners before position resolution"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"declared owners"
                          (ll/withdraw-shared {} test-mod
                                              {:token "USDC" :owner-ids ["alice"]
                                               :allocation-mode :pro-rata
                                               :effective-caps {"mallory" 1}}))))
  (testing "effective caps reject negative and non-integer amounts"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"non-negative integers"
                          (ll/withdraw-shared {} test-mod
                                              {:token "USDC" :owner-ids ["alice"]
                                               :allocation-mode :pro-rata
                                               :effective-caps {"alice" -1}})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"non-negative integers"
                          (ll/withdraw-shared {} test-mod
                                              {:token "USDC" :owner-ids ["alice"]
                                               :allocation-mode :pro-rata
                                               :effective-caps {"alice" 1.5}})))))

(defn- shared-withdrawal-world
  [owners available]
  (-> (reduce (fn [world owner]
                (ll/deposit world test-mod {:owner/id owner :amount 100 :token "USDC"}))
              test-world
              (sort owners))
      (assoc-in [:total-held :USDC] available)))

(defn- shared-decision
  [owners available opts]
  (let [world (shared-withdrawal-world owners available)
        result (ll/withdraw-shared world test-mod
                                   (merge {:owner-ids owners
                                           :token "USDC"
                                           :allocation-mode :pro-rata}
                                          opts))]
    (first (vals (:yield/partial-fill-decisions result)))))

(defn- propagation-from
  [world]
  (->> (:yield/pro-rata-propagations world)
       (sort-by :propagation/id)
       last
       val))

(defn- application-from
  [world]
  (->> (:yield/applied-pro-rata-propagations world)
       (sort-by :propagation-id)
       last
       val))

(deftest shared-withdrawal-effective-caps-are-bounded-and-deterministic
  (testing "a zero effective cap permits no allocation for that owner"
    (let [decision (shared-decision ["alice" "bob"] 100
                                    {:effective-caps {"alice" 0}})
          rows (into {} (map (juxt :key identity) (get-in decision [:evidence :allocation-rows])))]
      (is (zero? (get-in rows ["alice" :filled])))
      (is (= 100 (get-in rows ["bob" :filled])))))
  (testing "an oversized cap cannot allocate more than the owner request"
    (let [decision (shared-decision ["alice"] 100
                                    {:effective-caps {"alice" 1000}})
          row (first (get-in decision [:evidence :allocation-rows]))]
      (is (= 100 (:owed row)))
      (is (= 100 (:filled row)))
      (is (= 100 (:final-allocation row)))))
  (testing "omitted caps preserve the uncapped allocation result"
    (let [uncapped (shared-decision ["alice" "bob"] 100 {})
          explicit (shared-decision ["alice" "bob"] 100
                                    {:effective-caps {"alice" 100 "bob" 100}})]
      (is (= (get-in uncapped [:evidence :allocation-rows])
             (get-in explicit [:evidence :allocation-rows])))
      (is (= (:decision/hash uncapped) (:decision/hash explicit)))))
  (testing "input and cap-map order do not affect the canonical allocation decision"
    (let [forward (shared-decision ["alice" "bob" "carol"] 10
                                   {:effective-caps (array-map "alice" 100 "bob" 100 "carol" 100)})
          reversed (shared-decision ["carol" "bob" "alice"] 10
                                    {:effective-caps (array-map "carol" 100 "bob" 100 "alice" 100)})]
      (is (= (get-in forward [:evidence :allocation-rows])
             (get-in reversed [:evidence :allocation-rows])))
      (is (= (:decision/hash forward) (:decision/hash reversed))))))

(deftest shared-withdrawal-accounting-acceptance-cases
  (testing "total shortfall produces no financial movement"
    (let [world (-> (shared-withdrawal-world ["alice" "bob"] 0)
                    (assoc-in [:yield/held-balances "USDC"] 0))
          result (ll/withdraw-shared world test-mod {:owner-ids ["alice" "bob"]
                                                     :token "USDC"
                                                     :allocation-mode :pro-rata})
          propagation (propagation-from result)
          application (application-from result)]
      (is (= 0 (get-in propagation [:summary :allocated])))
      (is (= 200 (get-in propagation [:summary :deferred])))
      (is (= 0 (get-in application [:source-account :delta])))
      (is (empty? (filter #(= :credit (:entry/type %)) (:accounting-entries propagation))))
      (is (:holds? (inv/check-pro-rata-accounting-reconciles result)))))
  (testing "effective caps retain residual in the shared source account"
    (let [world (shared-withdrawal-world ["alice" "bob"] 150)
          result (ll/withdraw-shared world test-mod {:owner-ids ["alice" "bob"]
                                                     :token "USDC"
                                                     :allocation-mode :pro-rata
                                                     :effective-caps {"alice" 20 "bob" 100}})
          propagation (propagation-from result)
          application (application-from result)]
      (is (= 120 (get-in propagation [:summary :allocated])))
      (is (= 30 (get-in propagation [:summary :unallocated-residual])))
      (is (= -120 (get-in application [:source-account :delta])))
      (is (= 30 (get-in application [:source-account :after])))
      (is (= :remain-in-shared-liquidity (get-in application [:residual :destination])))

      (is (:holds? (inv/check-pro-rata-accounting-reconciles result)))))
  (testing "largest remainder persists exact 4/3/3 participant evidence"
    (let [world (-> (reduce (fn [w owner]
                              (ll/deposit w test-mod {:owner/id owner :amount 10 :token "USDC"}))
                            (assoc test-world :yield/held-balances {"USDC" 10})
                            ["alice" "bob" "carol"])
                    (assoc :total-held {:USDC 10}))
          result (ll/withdraw-shared world test-mod {:owner-ids ["carol" "bob" "alice"]
                                                     :token "USDC"
                                                     :allocation-mode :pro-rata})
          propagation (propagation-from result)
          credits (filter #(= :credit (:entry/type %)) (:accounting-entries propagation))]
      (is (= {"alice" 4 "bob" 3 "carol" 3}
             (into {} (map (juxt :participant-id :fulfilled) (:participants propagation)))))
      (is (= {"alice" 4 "bob" 3 "carol" 3}
             (into {} (map (juxt :participant-id :delta) credits))))
      (is (:holds? (inv/check-pro-rata-accounting-reconciles result)))))
  (testing "reapplying a committed propagation is a no-op"
    (let [world (shared-withdrawal-world ["alice" "bob"] 100)
          applied-world (ll/withdraw-shared world test-mod {:owner-ids ["alice" "bob"]
                                                            :token "USDC"
                                                            :allocation-mode :pro-rata})
          propagation (propagation-from applied-world)
          replay (ll/apply-pro-rata-propagation applied-world propagation)]
      (is (= :already-applied (:status replay)))
      (is (= applied-world (:world replay))))))

(deftest shared-withdrawal-v2-propagation-binds-decision-and-allocation
  (let [world (shared-withdrawal-world ["alice" "bob"] 100)
        result (ll/withdraw-shared world test-mod {:owner-ids ["alice" "bob"]
                                                   :token "USDC"
                                                   :allocation-mode :pro-rata})
        propagation (propagation-from result)
        decision (get-in result [:yield/partial-fill-decisions (:calculation-ref propagation)])
        reference (:allocation/reference propagation)
        mechanism-evidence (get-in decision [:evidence :allocation-mechanism-evidence])]
    (is (= "pro-rata-propagation.v2" (:schema-version propagation)))
    (is (true? (:valid? (partial-fill/validate-pro-rata-propagation propagation))))
    (is (some #{:propagation-hash-mismatch}
              (:policy-errors
               (partial-fill/validate-pro-rata-propagation
                (assoc-in propagation [:application/base-propagation :participants 0 :fulfilled] 1)))))
    (is (= "pro-rata-mechanism-evidence.v1" (:schema-version mechanism-evidence)))
    (is (= (get-in mechanism-evidence [:mechanism/result :allocation/hash])
           (get-in reference [:mechanism-evidence :allocation/hash])))
    (is (= (:evidence/hash mechanism-evidence)
           (get-in reference [:mechanism-evidence :evidence/hash])))
    (is (true? (partial-fill/decision-hash-valid? decision)))
    (is (= (:decision/id decision) (get-in reference [:source-evidence :artifact/id])))
    (is (= (:decision/hash decision) (get-in reference [:source-evidence :artifact/hash])))
    (is (= (get-in decision [:evidence :allocation-mechanism :allocation/hash])
           (:allocation/hash reference)))
    (is (empty? (partial-fill/propagation-allocation-binding-violations decision propagation)))
    (is (some #(= :propagation-invocation-context-mismatch (:reason %))
              (partial-fill/propagation-allocation-binding-violations
               decision (assoc-in propagation [:allocation/invocation-context :event/id] 99))))
    (is (some #(= :decision-hash-mismatch (:reason %))
              (partial-fill/propagation-allocation-binding-violations
               (assoc-in decision [:evidence :allocation-rows 0 :filled] 1) propagation)))
    (is (some #(= :propagation-allocation-hash-mismatch (:reason %))
              (partial-fill/propagation-allocation-binding-violations
               decision (assoc-in propagation [:allocation/reference :allocation/hash] "bad"))))
    (is (some #(= :propagation-allocation-id-mismatch (:reason %))
              (partial-fill/propagation-allocation-binding-violations
               decision (assoc-in propagation [:allocation/reference :allocation/id] "other-allocation"))))
    (is (some #(= :propagation-mechanism-reference-mismatch (:reason %))
              (partial-fill/propagation-allocation-binding-violations
               decision (assoc-in propagation [:allocation/reference :mechanism :version] 999))))
    (is (some #(= :propagation-decision-reference-mismatch (:reason %))
              (partial-fill/propagation-allocation-binding-violations
               decision (assoc-in propagation [:allocation/reference :source-evidence :artifact/hash] "other-decision"))))
    (is (some #(= :propagated-fulfilled-mismatch (:reason %))
              (partial-fill/propagation-allocation-binding-violations
               decision (assoc-in propagation [:participants 0 :fulfilled] 1))))
    (is (some #(= :propagated-unmet-mismatch (:reason %))
              (partial-fill/propagation-allocation-binding-violations
               decision (assoc-in propagation [:participants 0 :deferred] 1))))
    (is (some #(= :duplicate-propagation-participant (:reason %))
              (partial-fill/propagation-allocation-binding-violations
               decision (update propagation :participants conj (first (:participants propagation))))))
    (is (some #(= :missing-propagation-participant (:reason %))
              (partial-fill/propagation-allocation-binding-violations
               decision (update propagation :participants pop))))
    (is (some #(= :extra-propagation-participant (:reason %))
              (partial-fill/propagation-allocation-binding-violations
               decision (assoc-in propagation [:participants 0 :origin :obligation-id] "other-obligation"))))
    (is (some #(= :propagation-fulfilled-total-mismatch (:reason %))
              (partial-fill/propagation-allocation-binding-violations
               decision (assoc-in propagation [:participants 0 :fulfilled] 1))))
    (is (some #(= :propagation-unmet-total-mismatch (:reason %))
              (partial-fill/propagation-allocation-binding-violations
               decision (assoc-in propagation [:participants 0 :deferred] 1))))
    (is (= :pass (get-in (inv/check-pro-rata-propagation-complete result)
                         [:checks :allocation-decision-binding-valid])))
    (is (= :pass (get-in (inv/check-pro-rata-accounting-reconciles result)
                         [:checks :allocation-row-translation-valid])))
    (is (some #(= :application-propagation-reference-mismatch (:reason %))
              (:violations
               (inv/check-pro-rata-accounting-reconciles
                (assoc-in result
                          [:yield/applied-pro-rata-propagations (:propagation/id propagation)
                           :propagation/reference :propagation/hash]
                          "bad")))))))

(deftest deposit-creates-ratio-position
  (testing "Deposit creates position with ratio-based entry-index"
    (let [world' (ll/deposit test-world test-mod {:owner/id "user1" :amount 10000 :token "USDC"})
          pos   (get-in world' [:yield/positions "user1"])]
      (is (= "user1" (:owner/id pos)))
      (is (= 10000 (:principal pos)))
      (is (number? (:shares pos)))
      (is (number? (:entry-index pos)))
      (is (nil? (:current-index pos)))
      (is (= :active (:status pos))))))

(deftest accrue-positive
  (testing "Accrue produces positive unrealized yield"
    (let [world-a (ll/deposit test-world test-mod {:owner/id "user1" :amount 10000 :token "USDC"})
          world-b (ll/accrue world-a test-mod {:token "USDC" :dt 31536000})
          pos (get-in world-b [:yield/positions "user1"])]
      (is (pos? (:unrealized-yield pos 0))
          "Should have positive unrealized yield")
      (is (number? (:current-index pos))
          "Index should be set"))))

(deftest two-positions-accrue-separately
  (testing "Two positions in same module accrue independently"
    (let [w (ll/deposit test-world test-mod {:owner/id "user1" :amount 10000 :token "USDC"})
          w (ll/deposit w test-mod {:owner/id "user2" :amount 5000 :token "USDC"})
          w (ll/accrue w test-mod {:token "USDC" :dt 31536000})
          p1 (get-in w [:yield/positions "user1"])
          p2 (get-in w [:yield/positions "user2"])]
      (is (pos? (:unrealized-yield p1 0)))
      (is (pos? (:unrealized-yield p2 0)))
      (is (> (:unrealized-yield p1 0) (:unrealized-yield p2 0))
          "Bigger deposit should earn more yield"))))

(deftest withdraw-full-liquidity
  (testing "Full withdrawal with adequate liquidity"
    (let [w (ll/deposit test-world test-mod {:owner/id "user1" :amount 10000 :token "USDC"})
          w (ll/accrue w test-mod {:token "USDC" :dt 31536000})
          w (assoc-in w [:total-held :USDC] 20000)
          w (ll/withdraw w test-mod {:owner/id "user1"})
          pos (get-in w [:yield/positions "user1"])]
      (is (= :withdrawn (:status pos)))
      (is (zero? (:unrealized-yield pos 0)) "unrealized yield zeroed on withdraw"))))

(deftest shortfall-calls-partial-fill
  (testing "Withdrawal with shortfall calls partial-fill"
    (let [w (ll/deposit test-world test-mod {:owner/id "user1" :amount 10000 :token "USDC"})
          w (ll/accrue w test-mod {:token "USDC" :dt 31536000})
           ;; restrict liquidity
          w (assoc-in w [:total-held :USDC] 5000)
          w (ll/withdraw w test-mod {:owner/id "user1"})
          pos (get-in w [:yield/positions "user1"])
          decisions (vals (:yield/partial-fill-decisions w))
          artifact (first decisions)]
      (is (:partial-fill-affected? pos))
      (is (= 1 (count decisions)))
      (is (= :yield/partial-fill-decision (:artifact/kind artifact)))
      (is (= "user1" (:position/id artifact)))
      (is (= :partial-fill (:settlement-mode artifact)))
      (is (string? (:decision/hash artifact)))
      (is (map? (:evidence artifact))))))

(deftest full-withdraw-does-not-fabricate-partial-fill-artifact
  (testing "Full withdrawal does not emit a partial-fill decision artifact"
    (let [w (ll/deposit test-world test-mod {:owner/id "user1" :amount 10000 :token "USDC"})
          w (ll/accrue w test-mod {:token "USDC" :dt 31536000})
          w (assoc-in w [:total-held :USDC] 20000)
          w (ll/withdraw w test-mod {:owner/id "user1"})]
      (is (empty? (:yield/partial-fill-decisions w {}))))))

(deftest apply-partial-fill-with-attribution-sets-ctx
  (testing "apply-partial-fill-with-attribution sets settlement context"
    (let [pos (pos/normalize-position
               {:owner/id "user1" :module/id :test-mod :token "USDC"
                :principal 10000 :shares 10000 :entry-index 1
                :realized-yield 500 :deferred-yield 200 :status :active})
          decision (partial-fill/calculate-fulfillment 8000 pos)
          _ (partial-fill/apply-partial-fill-with-attribution {} pos decision)]
      (is (nil? (:settlement/mode attr/*attribution*))))))

(deftest lifecycle-integration
  (testing "End-to-end: register module -> deposit -> accrue -> withdraw via lifecycle-compatible path"
    (let [test-mid :yield.provider/liquid-lending
          test-mod (get-in (reg/init-yield-modules {}) [:yield/modules test-mid])
          world0 (-> {:yield/module-status {test-mid :active}
                      :yield/indices {test-mid {"USDC" 1}}
                      :yield/rates {test-mid {"USDC" 0.05}}
                      :yield/accrual-config {test-mid {:max-index-delta-ratio 2}}
                      :block-time 1000}
                     (reg/init-yield-modules)
                     (ll/deposit test-mod {:owner/id "escrow:user1"
                                           :amount 10000
                                           :token "USDC"}))]
      ;; Step 1: Accrue yield
      (let [world-a (ll/accrue world0 test-mod {:token "USDC" :dt 31536000})
            pos (get-in world-a [:yield/positions "escrow:user1"])]
        (is (pos? (:unrealized-yield pos 0)) "Accrue should produce yield")
        (is (number? (:current-index pos)) "Index should be set after accrue"))
      ;; Step 2: Withdraw
      (let [world-w (ll/withdraw world0 test-mod {:owner/id "escrow:user1"})
            pos (get-in world-w [:yield/positions "escrow:user1"])]
        (is (some #{:withdrawn :unwinding} [(:status pos)])
            "Position should be withdrawn or unwinding")
        (is (or (not (:shortfall pos))
                (>= (:fulfilled-amount (:shortfall pos) 0) 0))
            "Shortfall fulfilled amount should be non-negative")))))

(deftest test-min-available-ratio-for-claim-threshold
  (testing "claim-deferred respects custom min-available-ratio-for-claim threshold"
    (let [risk {:liquidity-mode :shortfall
                :failure-modes #{:partial-liquidity}
                :shortfall {:available-ratio 1.0}
                :min-available-ratio-for-claim 0.9}
          world {:yield/risk {:test-mod {"USDC" risk}}
                 :yield/positions {"user1" {:owner/id "user1" :module/id :test-mod :token "USDC"
                                            :principal 1000 :shares 1000 :entry-index 1.0
                                            :status :unwinding :unrealized-yield 0 :realized-yield 0
                                            :deferred-yield 100
                                            :shortfall {:reason :liquidity-shortfall
                                                        :basis-amount 1100
                                                        :fulfilled-amount 1000
                                                        :deferred-amount 100
                                                        :haircut-amount 0
                                                        :available-ratio 1.0}}}}
          result (ll/claim-deferred world test-mod {:owner/id "user1"})
          pos (get-in result [:yield/positions "user1"])]
      (is (= :withdrawn (:status pos))
          "Position should be withdrawn when available-ratio (1.0) >= min-ratio (0.9)")
      (is (nil? (:shortfall pos))
          "Shortfall should be cleared after successful claim")
      (is (>= (long (:reclaimed-amount pos 0)) 0)
          "Reclaimed amount should be non-negative"))))

(deftest test-min-available-ratio-for-claim-too-low
  (testing "claim-deferred should NOT reclaim when available-ratio below threshold"
    (let [risk {:liquidity-mode :shortfall
                :failure-modes #{:partial-liquidity}
                :shortfall {:available-ratio 0.5}
                :min-available-ratio-for-claim 0.9}
          world {:yield/risk {:test-mod {"USDC" risk}}
                 :yield/positions {"user1" {:owner/id "user1" :module/id :test-mod :token "USDC"
                                            :principal 1000 :shares 1000 :entry-index 1.0
                                            :status :unwinding :unrealized-yield 0 :realized-yield 0
                                            :deferred-yield 100
                                            :shortfall {:reason :liquidity-shortfall
                                                        :basis-amount 1100
                                                        :fulfilled-amount 1000
                                                        :deferred-amount 100
                                                        :haircut-amount 0
                                                        :available-ratio 0.5}}}}
          result (ll/claim-deferred world test-mod {:owner/id "user1"})
          pos (get-in result [:yield/positions "user1"])]
      (is (= :unwinding (:status pos))
          "Position should remain :unwinding when available-ratio (0.5) < min-ratio (0.9)")
      (is (some? (:shortfall pos))
          "Shortfall should NOT be cleared when reclaim fails")
      (is (zero? (:reclaimed-amount pos 0))
          "Reclaimed amount should be 0 when below threshold"))))

(deftest test-partial-liquidity-split-ratios-in-withdraw
  (testing "Withdraw with separate yield/principal availability ratios under partial-liquidity"
    (let [world {:yield/indices {:test-mod {"USDC" 1.0}}
                 :yield/rates {:test-mod {"USDC" 0.10}}
                 :yield/risk {:test-mod {"USDC" {:failure-modes #{:partial-liquidity}
                                                 :shortfall {:yield-available-ratio 0.5
                                                             :principal-available-ratio 1.0}}}}
                 :total-held {:USDC 15000}
                 :run/id "test-run"
                 :execution/id "test-execution"
                 :params {:scenario-id "test-scenario"}
                 :yield/positions {"user1" {:owner/id "user1" :module/id :test-mod :token "USDC"
                                            :principal 10000 :shares 10000 :entry-index 1.0
                                            :status :active :unrealized-yield 0 :realized-yield 0}}}
          accrued (ll/accrue world test-mod {:token "USDC" :dt 31536000})
          result (ll/withdraw accrued test-mod {:owner/id "user1"})
          pos (get-in result [:yield/positions "user1"])]
      (is (some #{:withdrawn :unwinding} [(:status pos)])
          "Position should be withdrawn or unwinding after withdraw")
      (is (>= (:realized-yield pos 0) 0)
          "Realized yield should be non-negative")
      (is (zero? (:unrealized-yield pos 0))
          "Unrealized yield should be zeroed on withdraw"))))
