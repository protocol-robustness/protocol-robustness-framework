(ns resolver-sim.yield.liquid-lending-v2-test
  "Integration tests for the decision-based liquid-lending yield module.

   These tests cover the v2-merged-into-v1 module shape (decision-based accrual,
   ratio-based entry-index, partial-fill withdrawals). The module was previously
   referred to as 'liquid-lending-v2' and has been merged into the main
   liquid-lending module."
  (:require [clojure.test :refer :all]
            [clojure.test.check :as tc]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [resolver-sim.yield.modules.liquid-lending :as ll]
            [resolver-sim.yield.partial-fill :as partial-fill]
            [resolver-sim.yield.position :as pos]
            [resolver-sim.yield.registry :as reg]
            [resolver-sim.yield.invariants :as inv]
            [resolver-sim.util.attribution :as attr]
            [resolver-sim.hash.canonical :as hc]))

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

(deftest batch-accrual-respects-module-wide-recoverable-cap
  (testing "Module-wide recoverable-liquidity cap is shared across the batch, not per position"
    ;; net-solvent = held (21000) - liabilities (2 x 10000) = 1000.  Both positions
    ;; accrue ~10000 each; the combined realized yield must not exceed net-solvent.
    (let [w (-> test-world
                (assoc-in [:yield/held-balances "USDC"] 21000)
                (assoc-in [:yield/accrual-config :test-mod :max-index-delta-ratio] 2)
                (assoc :yield/rates {:test-mod {"USDC" 1.0}}))
          w (ll/deposit w test-mod {:owner/id "user1" :amount 10000 :token "USDC"})
          w (ll/deposit w test-mod {:owner/id "user2" :amount 10000 :token "USDC"})
          w (ll/accrue w test-mod {:token "USDC" :dt 31536000})
          p1 (get-in w [:yield/positions "user1"])
          p2 (get-in w [:yield/positions "user2"])
          total-unrealized (+ (:unrealized-yield p1 0) (:unrealized-yield p2 0))]
      (is (pos? (:unrealized-yield p1 0)))
      (is (pos? (:unrealized-yield p2 0)))
      (is (<= total-unrealized 1000)
          (str "combined realized " total-unrealized " must not exceed net-solvent 1000")))))

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

;; ---------------------------------------------------------------------------
;; withdraw-many pool coordination
;; ---------------------------------------------------------------------------

(defn- constrained-world
  "A two-position world (each 80 USDC) with `liquidity` in the shared pool."
  [liquidity]
  {:yield/indices {:test-mod {"USDC" 1}}
   :yield/rates   {:test-mod {"USDC" 0.05}}
   :yield/risk    {:test-mod {"USDC" {:liquidity-mode :available
                                      :loss-mode :none}}}
   :yield/held-balances {"USDC" liquidity}
   :yield/module-status {:test-mod :active}
   :block-time 1000
   :run/id "test-run"
   :execution/id "test-execution"
   :params {:scenario-id "test-scenario"}})

(defn- deposit-two
  "Deposit two 80-unit positions (u1, u2) into the world."
  [w]
  (-> w
      (ll/deposit test-mod {:owner/id "u1" :amount 80 :token "USDC"})
      (ll/deposit test-mod {:owner/id "u2" :amount 80 :token "USDC"})))

(defn- owner-settlement-totals
  "Return per-owner {:filled n :deferred n :haircut n} from a position's
   shortfall (or full value when fully withdrawn)."
  [pos]
  (if-let [sf (:shortfall pos)]
    {:filled (long (:fulfilled-amount sf 0))
     :deferred (long (:deferred-amount sf 0))
     :haircut (long (:haircut-amount sf 0))}
    {:filled (long (+ (:principal pos 0) (:realized-yield pos 0)))
     :deferred 0
     :haircut 0}))

(deftest withdraw-many-respects-module-wide-liquidity-cap
  (testing "A constrained pool is shared across the batch, never per position"
    (let [w (deposit-two (constrained-world 100))
          w (ll/withdraw-many w test-mod
                              [{:owner/id "u1" :token "USDC"}
                               {:owner/id "u2" :token "USDC"}])
          u1 (get-in w [:yield/positions "u1"])
          u2 (get-in w [:yield/positions "u2"])
          t1 (owner-settlement-totals u1)
          t2 (owner-settlement-totals u2)
          total-filled (+ (:filled t1) (:filled t2))
          total-requested (+ 80 80)
          ledger (get w :yield/withdrawal-ledger [])]
      (is (<= total-filled 100)
          (str "combined filled " total-filled " must not exceed the 100-unit pool"))
      (is (= 160 (+ (:filled t1) (:filled t2) (:deferred t1) (:deferred t2) (:haircut t1) (:haircut t2)))
          "per-owner filled + deferred + haircut reconciles to the 160 requested")
      (is (some #{:withdrawn} [(:status u1)]) "u1 fully settled first (FCFS)")
      (is (= :unwinding (:status u2)) "u2 draws the remaining pool and defers the rest")
      (is (some? (:shortfall u2)) "u2's shortfall records the pool constraint")
      (is (seq ledger) "withdraw-many records a withdrawal ledger entry")
      (is (= 100 (:ledger/filled (last ledger)))
          "ledger filled equals the batch pool")
      (is (<= (:ledger/filled (last ledger)) (:ledger/available (last ledger)))
          "ledger filled never exceeds the batch available pool")
      (is (inv/holds? :yield/withdrawal-ledger-conservation w)
          "withdrawal-ledger-conservation holds on the coordinated batch"))))

(deftest withdraw-many-fully-funded-settles-all
  (testing "Adequate liquidity fully settles every owner in the batch"
    (let [w (deposit-two (constrained-world 160))
          w (ll/withdraw-many w test-mod
                              [{:owner/id "u1" :token "USDC"}
                               {:owner/id "u2" :token "USDC"}])]
      (is (= :withdrawn (get-in w [:yield/positions "u1" :status])))
      (is (= :withdrawn (get-in w [:yield/positions "u2" :status])))
      (is (every? :holds? (vals (inv/check-all w)))
          "all yield invariants hold on the fully-funded batch"))))

(deftest withdraw-many-ledger-conservation-detects-overcommit
  (testing "withdrawal-ledger-conservation fails when a batch over-fills the pool"
    (let [w (deposit-two (constrained-world 100))
          w (ll/withdraw-many w test-mod
                              [{:owner/id "u1" :token "USDC"}
                               {:owner/id "u2" :token "USDC"}])
          ;; Fabricate a regression: the coordinator records the pool but the
          ;; batch over-filled it (the pre-fix double-spend signature).
          buggy (assoc w :yield/withdrawal-ledger
                       [{:ledger/kind :yield/withdrawal-batch
                         :ledger/id [:withdrawal-batch :test-mod :USDC 0 0]
                         :ledger/module-id :test-mod :ledger/token :USDC
                         :ledger/owner-ids ["u1" "u2"]
                         :ledger/available 100 :ledger/requested 160
                         :ledger/filled 160 :ledger/deferred 0 :ledger/haircut 0}])
          result (inv/holds? :yield/withdrawal-ledger-conservation buggy)]
      (is (false? result)
          "over-fill against the available pool is rejected")
      (is (= :withdrawal-exceeds-available-pool
             (-> (inv/run-invariants buggy [:yield/withdrawal-ledger-conservation])
                 (get-in [:yield/withdrawal-ledger-conservation :violations 0 :issues 0])))
          "the over-commit issue is surfaced explicitly"))))

(deftest withdraw-single-uses-escrow-own-custody
  (testing "Single withdraw settles against the escrow's own custody, not the aggregate pool"
    (let [w {:yield/indices {:test-mod {"USDC" 1}}
             :yield/rates   {:test-mod {"USDC" 0.05}}
             :yield/risk    {:test-mod {"USDC" {:liquidity-mode :available
                                                :loss-mode :none}}}
             ;; Escrow 0 owes 80 but its own custody is only 60; the aggregate
             ;; pool (100, incl. another escrow's custody) must NOT mask the
             ;; shortfall — otherwise the Sew finalize guard would hard-revert.
             :held/positions {[:held/position :USDC :escrow-principal 0] 60
                              [:held/position :USDC :escrow-principal 1] 40}
             :total-held {:USDC 100}
             :yield/module-status {:test-mod :active}
             :block-time 1000
             :run/id "test-run"
             :execution/id "test-execution"
             :params {:scenario-id "test-scenario"}}
          w (ll/deposit w test-mod {:owner/id [:sew/escrow 0] :amount 80 :token "USDC"})
          w (ll/withdraw w test-mod {:owner/id [:sew/escrow 0] :token "USDC"})
          pos (get-in w [:yield/positions [:sew/escrow 0]])
          sf (:shortfall pos)]
      (is (= :unwinding (:status pos))
          "under-funded escrow is deferred, not silently fully settled")
      (is (= 60 (:fulfilled-amount sf))
          "fulfilled amount bounded by the escrow's own 60-unit custody")
      (is (= 20 (:deferred-amount sf))
          "the 20-unit shortfall is deferred rather than masked by the aggregate pool")
      (is (inv/holds? :yield/withdrawal-ledger-conservation w)
          "withdrawal-ledger-conservation holds for the escrow-scoped withdrawal"))))

(defspec withdraw-many-pool-conservation-property 40
  (prop/for-all [pool (gen/choose 0 400)
                 n1   (gen/choose 0 160)
                 n2   (gen/choose 0 160)]
                (let [w (-> (constrained-world pool)
                            (ll/deposit test-mod {:owner/id "u1" :amount n1 :token "USDC"}))
                      w (if (pos? n2)
                          (ll/deposit w test-mod {:owner/id "u2" :amount n2 :token "USDC"})
                          w)
                      w (ll/withdraw-many w test-mod
                                          (cond-> [{:owner/id "u1" :token "USDC"}]
                                            (pos? n2) (conj {:owner/id "u2" :token "USDC"})))
                      u1 (get-in w [:yield/positions "u1"])
                      u2 (when (pos? n2) (get-in w [:yield/positions "u2"]))
                      t1 (owner-settlement-totals u1)
                      t2 (if u2 (owner-settlement-totals u2) {:filled 0 :deferred 0 :haircut 0})
                      total-filled (+ (:filled t1) (:filled t2))
                      total-requested (+ n1 n2)
                      ledger (last (get w :yield/withdrawal-ledger []))]
                  (and (<= total-filled pool)
                       (= total-requested
                          (+ (:filled t1) (:deferred t1) (:haircut t1)
                             (:filled t2) (:deferred t2) (:haircut t2)))
                       (<= (:ledger/filled ledger) (:ledger/available ledger))
                       (inv/holds? :yield/withdrawal-ledger-conservation w)))))

;; ---------------------------------------------------------------------------
;; check-aggregate / shortfall-splits reconciliation under negative yield
;; ---------------------------------------------------------------------------

(defn- negative-yield-world
  "A mark-to-market world with a single 800-unit position, negative APY accrued,
   then a constrained withdrawal (500 available)."
  [withdraw-fn]
  (-> {:yield/indices {:test-mod {:USDC 1}}
       :yield/rates   {:test-mod {:USDC 0.05}}
       :yield/risk    {:test-mod {:USDC {:liquidity-mode :available
                                         :loss-mode :mark-to-market}}}
       :yield/held-balances {:USDC 1000}
       :yield/module-status {:test-mod :active}
       :block-time 1000
       :run/id "r"
       :execution/id "e"
       :params {:scenario-id "s"}}
      (ll/deposit test-mod {:owner/id "u1" :amount 800 :token "USDC"})
      (assoc-in [:yield/rates :test-mod :USDC] -0.1)
      (ll/accrue test-mod {:token "USDC" :dt 31536000})
      (assoc-in [:yield/held-balances :USDC] 500)
      (withdraw-fn)))

(deftest negative-yield-single-withdraw-reconciles-aggregate-invariants
  (testing "check-aggregate / shortfall-splits reconcile the folded-basis shortfall"
    (let [w (negative-yield-world #(ll/withdraw % test-mod {:owner/id "u1" :token "USDC"}))
          pos (get-in w [:yield/positions "u1"])
          sf (:shortfall pos)
          results (inv/run-invariants
                   w [:yield/aggregate :yield/shortfall-splits
                      :yield/aggregate-shortfall-cap])]
      (is (= :unwinding (:status pos)))
      (is (neg? (:basis-negative-unrealized sf 0))
          "the negative-unrealized fold is recorded on the shortfall")
      (is (every? (fn [id] (get-in results [id :holds?]))
                  [:yield/aggregate :yield/shortfall-splits
                   :yield/aggregate-shortfall-cap])
          "aggregate and splits invariants reconcile the folded-basis shortfall"))))

(deftest negative-yield-shared-withdrawal-reconciles-aggregate-invariants
  (testing "shared-withdrawal shortfalls (no basis fold) reconcile without a fold term"
    (let [w (-> {:yield/indices {:test-mod {:USDC 1}}
                 :yield/rates   {:test-mod {:USDC 0.05}}
                 :yield/risk    {:test-mod {:USDC {:liquidity-mode :available
                                                   :loss-mode :mark-to-market}}}
                 :yield/held-balances {:USDC 100}
                 :yield/module-status {:test-mod :active}
                 :block-time 1000
                 :run/id "r"
                 :execution/id "e"
                 :params {:scenario-id "s"}}
                (ll/deposit test-mod {:owner/id "u1" :amount 80 :token "USDC"})
                (ll/deposit test-mod {:owner/id "u2" :amount 80 :token "USDC"})
                (assoc-in [:yield/rates :test-mod :USDC] -0.1)
                (ll/accrue test-mod {:token "USDC" :dt 31536000})
                (ll/withdraw-shared test-mod {:token "USDC"
                                              :owner-ids ["u1" "u2"]
                                              :allocation-mode :pro-rata}))
          results (inv/run-invariants
                   w [:yield/aggregate :yield/shortfall-splits
                      :yield/aggregate-shortfall-cap])]
      (is (every? (fn [id] (get-in results [id :holds?]))
                  [:yield/aggregate :yield/shortfall-splits
                   :yield/aggregate-shortfall-cap])
          "shared-withdrawal negative-yield shortfalls reconcile"))))

(deftest aggregate-rejects-shortfall-basis-exceeding-settlement-value
  (testing "check-aggregate uses the recorded settlement value as the over-count bound"
    (let [w {:yield/positions
             {"u" {:module/id :m :token :t :status :unwinding
                   :principal 100 :realized-yield 0 :unrealized-yield 0
                   :shortfall {:basis-amount 120 :fulfilled-amount 100
                               :deferred-amount 20 :haircut-amount 0
                               :basis-negative-unrealized 0
                                ;; exact value at settlement — 20 above the real 100
                               :settlement-value 100}}}}
          results (inv/run-invariants w [:yield/aggregate
                                         :yield/aggregate-shortfall-cap])]
      (is (false? (get-in results [:yield/aggregate :holds?]))
          "basis above settlement value is rejected")
      (is (false? (get-in results [:yield/aggregate-shortfall-cap :holds?]))
          "aggregate-shortfall-cap also rejects the over-claim")
      (is (= :aggregate-shortfall-over-value
             (get-in results [:yield/aggregate :violations 0 :issues 0]))
          "the over-claim issue is surfaced explicitly")))
  (testing "a correct settlement (basis == settlement value) is accepted"
    (let [w {:yield/positions
             {"u" {:module/id :m :token :t :status :unwinding
                   :principal 100 :realized-yield 0 :unrealized-yield 0
                   :shortfall {:basis-amount 100 :fulfilled-amount 80
                               :deferred-amount 20 :haircut-amount 0
                               :basis-negative-unrealized 0
                               :settlement-value 100}}}}
          results (inv/run-invariants w [:yield/aggregate])]
      (is (get-in results [:yield/aggregate :holds?])
          "basis equal to settlement value is accepted"))))

(deftest withdrawal-ledger-is-content-addressed-certificate
  (testing "withdraw-many and single withdraw ledger records carry a fixed-point certificate"
    (let [w (deposit-two (constrained-world 100))
          w (ll/withdraw-many w test-mod
                              [{:owner/id "u1" :token "USDC"}
                               {:owner/id "u2" :token "USDC"}])
          rec (last (get w :yield/withdrawal-ledger []))]
      (is (contains? rec :ledger/hash) "batch ledger is hash-committed")
      (is (contains? rec :ledger/preimage) "batch ledger carries the canonical preimage fixed point")
      (is (ll/ledger-hash-valid? rec) "batch ledger hash reconciles to its preimage")
      (is (inv/holds? :yield/withdrawal-ledger-conservation w)
          "conservation invariant accepts the certified batch")))
  (testing "a tampered ledger record (over-fill) fails the certificate and the pool bound"
    (let [w (deposit-two (constrained-world 100))
          w (ll/withdraw-many w test-mod
                              [{:owner/id "u1" :token "USDC"}
                               {:owner/id "u2" :token "USDC"}])
          rec (assoc (last (get w :yield/withdrawal-ledger []))
                     :ledger/filled 160 :ledger/deferred 0)
          w' (assoc w :yield/withdrawal-ledger [rec])
          result (inv/run-invariants w' [:yield/withdrawal-ledger-conservation])]
      (is (false? (ll/ledger-hash-valid? rec)) "tampering invalidates the certificate")
      (is (false? (get-in result [:yield/withdrawal-ledger-conservation :holds?]))
          "the invariant rejects the tampered record")
      (is (= #{:withdrawal-ledger-certificate-invalid
               :withdrawal-exceeds-available-pool
               :withdrawal-row-total-mismatch}
             (set (get-in result [:yield/withdrawal-ledger-conservation
                                  :violations 0 :issues])))
          "certificate, pool bound, and row-total reconciliation issues are surfaced"))))

(defn- re-certify-ledger
  "Recompute the fixed-point certificate (:ledger/hash / :ledger/preimage) after
   tampering a ledger record's fields (mirrors content-address-ledger)."
  [rec]
  (let [base (dissoc rec :ledger/hash :ledger/preimage)]
    (assoc rec
           :ledger/preimage (pr-str base)
           :ledger/hash (str "sha256:"
                             (hc/hash-with-intent {:hash/intent :evidence-record} base)))))

(deftest withdraw-many-row-envelope-reconstruction
  (testing "withdraw-many ledger records per-row settlement; the invariant reconstructs the FCFS batch"
    (let [w (deposit-two (constrained-world 100))
          w (ll/withdraw-many w test-mod
                              [{:owner/id "u1" :token "USDC"}
                               {:owner/id "u2" :token "USDC"}])
          rec (last (get w :yield/withdrawal-ledger []))]
      (is (= [{:owner-id "u1" :requested 80 :filled 80 :deferred 0 :haircut 0}
              {:owner-id "u2" :requested 80 :filled 20 :deferred 60 :haircut 0}]
             (:ledger/rows rec))
          "per-row filled/deferred/requested are recorded")
      (is (ll/ledger-hash-valid? rec) "per-row envelope keeps a valid certificate")
      (is (inv/holds? :yield/withdrawal-ledger-conservation w)
          "reconstruction checks pass on the genuine batch")))
  (testing "a row over-filling the FCFS pool is rejected by prefix reconstruction"
    (let [w (deposit-two (constrained-world 100))
          w (ll/withdraw-many w test-mod
                              [{:owner/id "u1" :token "USDC"}
                               {:owner/id "u2" :token "USDC"}])
          rec (last (get w :yield/withdrawal-ledger []))
          rows (update-in (:ledger/rows rec) [1] assoc :filled 80 :deferred 0)
          tampered (assoc rec :ledger/rows rows :ledger/filled 160 :ledger/deferred 0)
          result (inv/run-invariants
                  (assoc w :yield/withdrawal-ledger [tampered])
                  [:yield/withdrawal-ledger-conservation])]
      (is (contains? (set (get-in result [:yield/withdrawal-ledger-conservation
                                          :violations 0 :issues]))
                     :withdrawal-fcfs-over-commit)
          "FCFS prefix reconstruction detects the over-fill")))
  (testing "totals that disagree with the rows are rejected"
    (let [w (deposit-two (constrained-world 100))
          w (ll/withdraw-many w test-mod
                              [{:owner/id "u1" :token "USDC"}
                               {:owner/id "u2" :token "USDC"}])
          rec (last (get w :yield/withdrawal-ledger []))
          tampered (assoc rec :ledger/filled 160)
          result (inv/run-invariants
                  (assoc w :yield/withdrawal-ledger [tampered])
                  [:yield/withdrawal-ledger-conservation])]
      (is (contains? (set (get-in result [:yield/withdrawal-ledger-conservation
                                          :violations 0 :issues]))
                     :withdrawal-row-total-mismatch)
          "row-total reconciliation detects the fabricated total")))
  (testing "a negative row filled is rejected even with a valid certificate"
    (let [w (deposit-two (constrained-world 100))
          w (ll/withdraw-many w test-mod
                              [{:owner/id "u1" :token "USDC"}
                               {:owner/id "u2" :token "USDC"}])
          rec (last (get w :yield/withdrawal-ledger []))
          rows (update-in (:ledger/rows rec) [1] assoc :filled -60 :deferred 60)
          forged (re-certify-ledger (assoc rec :ledger/rows rows
                                           :ledger/filled 20 :ledger/deferred 60))
          result (inv/run-invariants
                  (assoc w :yield/withdrawal-ledger [forged])
                  [:yield/withdrawal-ledger-conservation])]
      (is (ll/ledger-hash-valid? forged) "forged record carries a valid certificate")
      (is (contains? (set (get-in result [:yield/withdrawal-ledger-conservation
                                          :violations 0 :issues]))
                     :withdrawal-negative-filled)
          "negative row filled is rejected despite the valid certificate"))))

(deftest withdrawal-ledger-per-run-address-binding
  (testing "withdrawal certificate is bound per-run and per-address"
    (let [w (deposit-two (constrained-world 100))
          w (ll/withdraw-many w test-mod
                              [{:owner/id "u1" :token "USDC"}
                               {:owner/id "u2" :token "USDC"}])
          rec (last (get w :yield/withdrawal-ledger []))]
      (is (= "test-run" (:ledger/run-id rec)) "certificate commits to the run id")
      (is (= "test-execution" (:ledger/execution-id rec)) "certificate commits to the execution id")
      (is (= ["u1" "u2"] (:ledger/owner-ids rec)) "per-address owners are declared")
      (is (= #{"u1" "u2"} (set (map :owner-id (:ledger/rows rec))))
          "every declared address has a per-address row")
      (is (ll/ledger-hash-valid? rec) "run/address binding is content-addressed")
      (is (inv/holds? :yield/withdrawal-ledger-conservation w)
          "genuine per-run per-address certificate passes")))
  (testing "a certificate transplanted to another run is rejected"
    (let [w (deposit-two (constrained-world 100))
          w (ll/withdraw-many w test-mod
                              [{:owner/id "u1" :token "USDC"}
                               {:owner/id "u2" :token "USDC"}])
          rec (last (get w :yield/withdrawal-ledger []))
          w' (assoc w :run/id "other-run" :yield/withdrawal-ledger [rec])
          result (inv/run-invariants w' [:yield/withdrawal-ledger-conservation])]
      (is (contains? (set (get-in result [:yield/withdrawal-ledger-conservation
                                          :violations 0 :issues]))
                     :withdrawal-run-mismatch)
          "run mismatch is detected")))
  (testing "a missing per-address row is rejected"
    (let [w (deposit-two (constrained-world 100))
          w (ll/withdraw-many w test-mod
                              [{:owner/id "u1" :token "USDC"}
                               {:owner/id "u2" :token "USDC"}])
          rec (last (get w :yield/withdrawal-ledger []))
          dropped (assoc rec :ledger/rows (subvec (:ledger/rows rec) 0 1))
          result (inv/run-invariants
                  (assoc w :yield/withdrawal-ledger [dropped])
                  [:yield/withdrawal-ledger-conservation])]
      (is (contains? (set (get-in result [:yield/withdrawal-ledger-conservation
                                          :violations 0 :issues]))
                     :withdrawal-address-coverage-mismatch)
          "dropped per-address row is detected"))))
