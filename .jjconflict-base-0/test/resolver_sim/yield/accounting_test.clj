(ns resolver-sim.yield.accounting-test
  (:require [clojure.test :refer :all]
            [resolver-sim.yield.accounting :as acct]
            [resolver-sim.yield.invariants :as inv]
            [resolver-sim.yield.modules.liquid-lending :as liquid]
            [resolver-sim.yield.modules.fixed :as fixed]))

(defn- run-liquid-lending-fragmented
  [world module token dt n]
  (let [per-step (quot dt n)
        remainder (- dt (* per-step n))
        world* (nth (iterate #(liquid/accrue % module {:token token :dt per-step}) world) n)]
    (if (pos? remainder)
      (liquid/accrue world* module {:token token :dt remainder})
      world*)))

(defn- run-fixed-fragmented
  [world module token dt n]
  (let [per-step (quot dt n)
        remainder (- dt (* per-step n))
        world* (nth (iterate #(fixed/fixed-accrue % module {:token token :dt per-step}) world) n)]
    (if (pos? remainder)
      (fixed/fixed-accrue world* module {:token token :dt remainder})
      world*)))

(deftest token-key-consistency-rejects-split-accounting
  (let [split {:total-held {:USDC 100 "USDC" 50}}
        consistent {:total-held {:USDC 150}}]
    (is (false? (:holds? (inv/check-token-key-consistency split))))
    (is (= [{:path [:total-held] :token "USDC" :keys [:USDC "USDC"]}]
           (:violations (inv/check-token-key-consistency split))))
    (is (true? (:holds? (inv/check-token-key-consistency consistent))))))

(deftest test-liquid-lending-deposit-mints-shares-from-entry-index
  (testing "Deposit mints shares = principal / entry share price (Model A)"
    (let [world {:yield/indices {:aave-v3 {"USDC" 1.25}}
                 :yield/risk {:aave-v3 {"USDC" {:liquidity-mode :available}}}}
          module {:module/id :aave-v3}
          world' (liquid/deposit world module {:owner/id "user1" :amount 10000 :token "USDC"})
          pos (get-in world' [:yield/positions "user1"])]
      (is (== 8000 (:shares pos)))
      (is (== 1.25 (:entry-index pos)))
      (is (= 10000 (:principal pos))))))

(deftest test-liquid-lending-accrual-math
  (testing "Liquid-lending index-based accrual (:aave-v3 module id)"
    (let [world {:yield/indices {:aave-v3 {"USDC" 1.0}}
                 :yield/rates   {:aave-v3 {"USDC" 0.10}}
                 :yield/positions {"user1" {:owner/id "user1" :module/id :aave-v3 :token "USDC"
                                            :principal 1000 :shares 1000 :entry-index 1.0 :status :active :unrealized-yield 0 :realized-yield 0}}}
          world' (liquid/accrue world {:module/id :aave-v3} {:token "USDC" :dt 31536000})]
      (is (== 21/20 (get-in world' [:yield/indices :aave-v3 "USDC"]))
          "index capped at 5% max-delta (1/20) even with 10% APY")
      (is (== 50 (get-in world' [:yield/positions "user1" :unrealized-yield]))
          "unrealized = 1000 * 0.05 = 50, capped at 5% max-delta-ratio"))))

(deftest test-liquid-lending-withdraw-crystallizes-yield
  (testing "Liquid-lending withdraw realizes current unrealized yield before marking withdrawn"
    (let [world {:yield/indices {:aave-v3 {"USDC" 1.1}}
                 :yield/risk {:aave-v3 {"USDC" {:liquidity-mode :available
                                                :shortfall {:available-ratio 1.0}}}}
                 :total-held {:USDC 1100}
                 :yield/positions {"user1" {:owner/id "user1" :module/id :aave-v3 :token "USDC"
                                            :principal 1000 :shares 1000 :entry-index 1.0
                                            :status :active :unrealized-yield 100 :realized-yield 0}}}
          world' (liquid/withdraw world {:module/id :aave-v3} {:owner/id "user1"})
          pos    (get-in world' [:yield/positions "user1"])]
      (is (= :withdrawn (:status pos)))
      (is (== 100 (:realized-yield pos))
          "withdrawal crystallizes the marked unrealized yield into realized-yield")
      (is (zero? (:unrealized-yield pos))))))

(deftest test-fixed-rate-accrual-math
  (testing "Fixed rate principal-based accrual"
    (let [world {:yield/rates {:fixed-rate {"USDC" 0.05}}
                 :yield/positions {[:sew/escrow "user1"] {:owner/id [:sew/escrow "user1"] :module/id :fixed-rate :token "USDC"
                                                          :principal 1000 :shares 1000 :entry-index 1.0 :status :active :unrealized-yield 0 :realized-yield 0}}}
          world' (fixed/fixed-accrue world {:module/id :fixed-rate} {:token "USDC" :dt 31536000})]
      (is (== 50 (get-in world' [:yield/positions [:sew/escrow "user1"] :unrealized-yield]))))))

(deftest test-liquid-lending-accrual-partition-equivalence-bounded-drift
  (testing "One-shot vs fragmented liquid-lending accrual stays within explicit drift budget"
    (let [token "USDC"
          module {:module/id :aave-v3}
          dt 31536000
          n 365
          max-rounding-drift 365
          world {:yield/indices {:aave-v3 {token 1.0}}
                 :yield/rates   {:aave-v3 {token 0.10}}
                 :yield/positions {"user1" {:owner/id "user1" :module/id :aave-v3 :token token
                                            :principal 1000 :shares 1000 :entry-index 1.0
                                            :status :active :unrealized-yield 0 :realized-yield 0}}}
          one-shot    (liquid/accrue world module {:token token :dt dt})
          fragmented  (run-liquid-lending-fragmented world module token dt n)
          y1          (get-in one-shot [:yield/positions "user1" :unrealized-yield])
          y2          (get-in fragmented [:yield/positions "user1" :unrealized-yield])
          drift       (Math/abs (long (- y1 y2)))]
      (is (<= drift max-rounding-drift)
          (str "Liquid-lending accrual drift " drift " exceeded budget " max-rounding-drift)))))

(deftest test-fixed-accrual-partition-equivalence-bounded-drift
  (testing "One-shot vs fragmented fixed accrual stays within explicit drift budget"
    (let [token "USDC"
          module {:module/id :fixed-rate}
          dt 31536000
          n 365
          ;; index-based accrual keeps cumulative rounding error well below 1 unit per step
          max-rounding-drift n
          world {:yield/rates {:fixed-rate {token 0.05}}
                 :yield/positions {[:sew/escrow "user1"] {:owner/id [:sew/escrow "user1"]
                                                          :module/id :fixed-rate :token token
                                                          :principal 1000 :shares 1000 :entry-index 1.0
                                                          :status :active :unrealized-yield 0 :realized-yield 0}}}
          one-shot   (fixed/fixed-accrue world module {:token token :dt dt})
          fragmented (run-fixed-fragmented world module token dt n)
          y1         (get-in one-shot [:yield/positions [:sew/escrow "user1"] :unrealized-yield])
          y2         (get-in fragmented [:yield/positions [:sew/escrow "user1"] :unrealized-yield])
          drift      (Math/abs (long (- y1 y2)))]
      (is (<= drift max-rounding-drift)
          (str "Fixed accrual drift " drift " exceeded budget " max-rounding-drift)))))

(deftest test-floor-to-asset-decimals-basic
  (testing "Floor helper is deterministic and non-negative"
    (is (= 0 (acct/floor-to-asset-decimals -1.2 6)))
    (is (= 1 (acct/floor-to-asset-decimals 1.9999 6)))
    (is (= 42 (acct/floor-to-asset-decimals 42 18))))
  (testing "Low-decimal token support (6 decimals)"
    (is (= 1 (acct/floor-to-asset-decimals 1.234567 6)))
    (is (= 0 (acct/floor-to-asset-decimals 0.999999 6)))
    (is (= -1 (acct/floor-to-asset-decimals-signed -1.234567 6)))))

(deftest test-token-decimals-resolution
  (testing "Token decimals resolve from world metadata with fallback"
    (is (= 6 (acct/token-decimals {:token/decimals {"USDC" 6}} "USDC")))
    (is (= 18 (acct/token-decimals {:yield/token-decimals {"WETH" 18}} "WETH")))
    (is (= 18 (acct/token-decimals {} "UNKNOWN")))))

(deftest test-update-position-yield-with-world-decimals
  (testing "Yield update path accepts world and applies shared floor policy"
    (let [world {:token/decimals {"USDC" 6 "WETH" 18}}
          pos-usdc {:owner/id "u1" :module/id :aave-v3 :token "USDC"
                    :principal 1000 :shares 1000 :entry-index 1.0 :status :active
                    :unrealized-yield 0 :realized-yield 0}
          pos-weth {:owner/id "u2" :module/id :aave-v3 :token "WETH"
                    :principal 1000 :shares 1000 :entry-index 1.0 :status :active
                    :unrealized-yield 0 :realized-yield 0}
          usdc' (acct/update-position-yield world pos-usdc 1.101)
          weth' (acct/update-position-yield world pos-weth 1.101)]
      (is (= 101 (:unrealized-yield usdc')))
      (is (= 101 (:unrealized-yield weth')))
      (is (= 1101 (:current-value usdc')))
      (is (= 1.101 (:current-index usdc'))))))

(deftest test-update-position-yield-entry-index-share-minting
  (testing "Shares minted as principal/entry-index; current-value = shares × current price"
    (let [world {:yield/risk {:mod {:USDC {:loss-mode :none}}}}
          pos {:owner/id :o :module/id :mod :token :USDC
               :principal 10000 :shares 8000 :entry-index 1.25 :status :active
               :unrealized-yield 0 :realized-yield 0}
          pos' (acct/update-position-yield world pos 1.30)]
      (is (= 10400 (:current-value pos')))
      (is (= 400 (:unrealized-yield pos')))
      (is (= 1.30 (:current-index pos'))))))

(deftest test-update-position-yield-requires-world
  (testing "2-arg and nil-world 3-arg reject silent optimistic clamping without risk context"
    (let [pos {:owner/id :o :module/id :mod :token :USDC
               :principal 10000 :shares 10000 :entry-index 1.0 :status :active}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"world is required"
                            (acct/update-position-yield pos 0.98)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"world is required"
                            (acct/update-position-yield nil pos 0.98)))
      (try
        (acct/update-position-yield pos 0.98)
        (is false "expected ex-info")
        (catch clojure.lang.ExceptionInfo e
          (is (= 'update-position-yield (:fn (ex-data e))))
          (is (= pos (:position (ex-data e))))
          (is (= 0.98 (:current-index (ex-data e)))))))))

(deftest test-update-position-yield-optimistic-clamp
  (testing "Default loss mode clamps negative PnL to zero unrealized yield"
    (let [world {:yield/risk {:mod {:USDC {:loss-mode :none}}}}
          pos {:owner/id :o :module/id :mod :token :USDC
               :principal 10000 :shares 10000 :entry-index 1.0 :status :active
               :unrealized-yield 500 :realized-yield 0}
          pos' (acct/update-position-yield world pos 0.98)]
      (is (= 0 (:unrealized-yield pos')))
      (is (= 9800 (:current-value pos')))
      (is (neg? (- (:current-value pos') (:principal pos')))))))

(deftest test-update-position-yield-current-value-invariant
  (testing "Unrealized yield equals floored current-value minus principal"
    (let [world {:yield/risk {:mod {:USDC {:loss-mode :mark-to-market}}}}
          pos {:owner/id :o :module/id :mod :token :USDC
               :principal 1000 :shares 1001 :entry-index 1.0 :status :active
               :unrealized-yield 0 :realized-yield 0}
          pos' (acct/update-position-yield world pos 1.0009)]
      (is (= (- (:current-value pos') (:principal pos'))
             (:unrealized-yield pos'))))))

(deftest test-position-current-value
  (is (= 9800.0 (acct/position-current-value {:shares 10000} 0.98))))

(deftest test-liquid-lending-accrue-negative-yield-mark-to-market
  (testing "Liquid-lending negative APY produces negative unrealized under mark-to-market"
    (let [world {:yield/indices {:aave-v3 {"USDC" 1.0}}
                 :yield/rates   {:aave-v3 {"USDC" -0.05}}
                 :yield/risk    {:aave-v3 {"USDC" {:loss-mode :mark-to-market}}}
                 :yield/positions {"user1" {:owner/id "user1" :module/id :aave-v3 :token "USDC"
                                            :principal 1000 :shares 1000 :entry-index 1.0
                                            :status :active :unrealized-yield 0 :realized-yield 0}}}
          world' (liquid/accrue world {:module/id :aave-v3} {:token "USDC" :dt 31536000})
          pos     (get-in world' [:yield/positions "user1"])]
      (is (< (:current-index pos) 1.0)
          (str "Expected index below 1.0 after negative APY, got " (:current-index pos)))
      (is (< (:current-value pos) (:principal pos))
          (str "Expected current-value < principal, got " (:current-value pos)))
      (is (neg? (:unrealized-yield pos))
          (str "Expected negative unrealized-yield under mark-to-market, got " (:unrealized-yield pos))))))

(deftest test-liquid-lending-accrue-negative-yield-clamped-under-none
  (testing "Liquid-lending negative APY clamps unrealized to zero under :none loss-mode"
    (let [world {:yield/indices {:aave-v3 {"USDC" 1.0}}
                 :yield/rates   {:aave-v3 {"USDC" -0.05}}
                 :yield/risk    {:aave-v3 {"USDC" {:loss-mode :none}}}
                 :yield/positions {"user1" {:owner/id "user1" :module/id :aave-v3 :token "USDC"
                                            :principal 1000 :shares 1000 :entry-index 1.0
                                            :status :active :unrealized-yield 0 :realized-yield 0}}}
          world' (liquid/accrue world {:module/id :aave-v3} {:token "USDC" :dt 31536000})
          pos     (get-in world' [:yield/positions "user1"])]
      (is (== 1.0 (:current-index pos))
          "index unchanged when loss-mode is :none and APY is negative")
      (is (= 0 (:unrealized-yield pos))
          (str "Expected unrealized clamped to 0 under :none loss-mode, got " (:unrealized-yield pos)))
      (is (= (:current-value pos) (:principal pos))
          (str "current-value=" (:current-value pos) " = principal=" (:principal pos) " under :none loss-mode")))))

(deftest test-fixed-accrue-negative-apy-mark-to-market
  (testing "Fixed-rate negative APY produces negative unrealized under mark-to-market"
    (let [world {:yield/rates {:fixed-rate {"USDC" -0.05}}
                 :yield/risk  {:fixed-rate {"USDC" {:loss-mode :mark-to-market}}}
                 :yield/positions {[:sew/escrow "user1"] {:owner/id [:sew/escrow "user1"]
                                                          :module/id :fixed-rate :token "USDC"
                                                          :principal 1000 :shares 1000 :entry-index 1.0
                                                          :status :active :unrealized-yield 0 :realized-yield 0}}}
          world' (fixed/fixed-accrue world {:module/id :fixed-rate} {:token "USDC" :dt 31536000})
          pos     (get-in world' [:yield/positions [:sew/escrow "user1"]])]
      (is (< (:current-index pos) 1.0))
      (is (neg? (:unrealized-yield pos))
          (str "Expected negative unrealized under mark-to-market, got " (:unrealized-yield pos)))
      (is (= (- (:current-value pos) (:principal pos))
             (:unrealized-yield pos))))))

(deftest test-fixed-accrue-negative-apy-clamped-under-none
  (testing "Fixed-rate negative APY clamps unrealized to zero under :none loss-mode"
    (let [world {:yield/rates {:fixed-rate {"USDC" -0.05}}
                 :yield/risk  {:fixed-rate {"USDC" {:loss-mode :none}}}
                 :yield/positions {[:sew/escrow "user1"] {:owner/id [:sew/escrow "user1"]
                                                          :module/id :fixed-rate :token "USDC"
                                                          :principal 1000 :shares 1000 :entry-index 1.0
                                                          :status :active :unrealized-yield 0 :realized-yield 0}}}
          world' (fixed/fixed-accrue world {:module/id :fixed-rate} {:token "USDC" :dt 31536000})
          pos     (get-in world' [:yield/positions [:sew/escrow "user1"]])]
      (is (< (:current-index pos) 1.0))
      (is (= 0 (:unrealized-yield pos))
          (str "Expected unrealized clamped to 0 under :none, got " (:unrealized-yield pos)))
      (is (= (:current-value pos) 950)
          (str "Expected current-value 950, got " (:current-value pos))))))

(deftest test-partial-liquidity-split-ratios
  (testing "Partial-liquidity with separate yield and principal availability ratios"
    (let [result (acct/apply-liquidity-stress
                  {:yield/risk {:test-mod {"USDC" {:failure-modes #{:partial-liquidity}
                                                   :shortfall {:yield-available-ratio 0.8
                                                               :principal-available-ratio 1.0
                                                               :available-ratio 0.8}}}}}
                  :test-mod "USDC" 10000 :principal 8000)]
      (is (= 0 (get-in result [:shortfall :haircut-amount] 0))
          "No haircut under partial-liquidity")
      (is (pos? (get-in result [:shortfall :deferred-amount] 0))
          "Deferred amount present under partial-liquidity")
      (is (= 0.8 (double (get-in result [:shortfall :yield-available-ratio])))
          "Yield availability ratio honored")
      (is (= 1.0 (double (get-in result [:shortfall :principal-available-ratio])))
          "Principal availability ratio honored (principal fully covered)"))))

(deftest test-haircut-liquidity-mode
  (testing ":haircut liquidity mode applies loss ratio"
    (let [result (acct/apply-liquidity-stress
                  {:yield/risk {:test-mod {"USDC" {:liquidity-mode :haircut
                                                   :haircut {:loss-ratio 0.15}}}}}
                  :test-mod "USDC" 10000)]
      (is (pos? (get-in result [:shortfall :haircut-amount]))
          "Haircut amount should be positive under :haircut mode")
      (is (= 1500 (get-in result [:shortfall :haircut-amount]))
          "Haircut should be 15% of 10000 = 1500")
      (is (= 8500 (:fulfilled result))
          "Fulfilled amount should be 10000 - 1500 = 8500"))))

(deftest test-haircut-liquidity-mode-default-ratio
  (testing ":haircut liquidity mode defaults to 10% loss ratio"
    (let [result (acct/apply-liquidity-stress
                  {:yield/risk {:test-mod {"USDC" {:liquidity-mode :haircut}}}}
                  :test-mod "USDC" 10000)]
      (is (pos? (get-in result [:shortfall :haircut-amount]))
          "Haircut amount should be positive under :haircut mode")
      (is (= 1000 (get-in result [:shortfall :haircut-amount]))
          "Default haircut should be 10% of 10000 = 1000")
      (is (= 9000 (:fulfilled result))
          "Fulfilled amount should be 10000 - 1000 = 9000"))))
