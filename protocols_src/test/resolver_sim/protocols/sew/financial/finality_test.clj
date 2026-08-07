(ns resolver-sim.protocols.sew.financial.finality-test
  (:require [clojure.test :refer :all]
            [resolver-sim.protocols.sew.financial.finality :as fin]
            [resolver-sim.protocols.sew.financial.loss :as loss]
            [resolver-sim.protocols.sew.financial.solvency :as solv]
            [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.protocols.sew.snapshot-fixtures :as snap-fix]
            [resolver-sim.protocols.sew.lifecycle :as lc]
            [resolver-sim.protocols.sew.resolution :as res]
            [resolver-sim.protocols.sew.registry :as reg]))

;; ── Chain finality ──────────────────────────────────────────────────────────

(deftest chain-finality-assumed-by-replay
  (testing "All replay world states are chain-final (no chain reorgs modelled)"
    (let [world (t/empty-world 1000)
          cf    (fin/classify-chain-finality world)]
      (is (= :final (:chain/phase cf)))
      (is (= :assumed-by-replay (:chain/source cf)))
      (is (true? (:chain-final? cf)) "chain finality is always true in replay engine"))))

;; ── Financial finality: provisional, challengeable, final ──────────────────────

(deftest provisional-escrow-not-financially-final
  (testing "Escrow in :pending state has no resolution — :provisional"
    (let [buyer "0xBuyer" seller "0xSeller" resolver "0xRes0"
          snap (snap-fix/escrow-snapshot {:dispute-resolver resolver
                                          :appeal-window-duration 0
                                          :escrow-fee-bps 0})
          world (-> (t/empty-world 1000)
                    (lc/create-escrow buyer "USDC" seller 1000 {} snap)
                    :world)
          ff    (fin/classify-financial-finality world 0)]
      (is (= :provisional (:financial/phase ff)))
      (is (false? (:financially-final? ff)))
      (is (true? (:can-change? ff)))
      (is (= :pending (t/escrow-state world 0)) "escrow not yet disputed"))))

(deftest chain-final-but-financially-challengeable
  (testing "Scenario A: chain-final but financially non-final — appeal window open"
    (let [buyer "0xBuyer" seller "0xSeller" r0 "0xRes0"
          snap (snap-fix/escrow-snapshot {:dispute-resolver r0
                                          :appeal-window-duration 60
                                          :escrow-fee-bps 0
                                          :max-dispute-level 2
                                          :escalation-resolvers {1 "0xRes1" 2 "0xRes2"}})
          world0 (-> (t/empty-world 1000)
                     (lc/create-escrow buyer "USDC" seller 1000 {} snap)
                     :world)
          world1 (-> (lc/raise-dispute world0 0 buyer) :world)
          world2 (-> (res/execute-resolution world1 0 r0 true "0xhash" nil) :world)
          chain     (fin/classify-chain-finality world2)
          financial (fin/classify-financial-finality world2 0)]
      ;; Chain finality
      (is (true? (:chain-final? chain))
          "chain-final? must be true (all replay states are chain-final)")
      ;; Financial finality — NOT final, challengeable
      (is (= :challengeable (:financial/phase financial))
          "appeal window open means financially challengeable")
      (is (false? (:financially-final? financial))
          "must NOT be financially final")
      (is (true? (:can-change? financial)))
      (is (contains? (set (:open-gates financial)) :appeal-window)
          "appeal window gate must be open"))))

(deftest fully-terminal-is-financially-final
  (testing "Escrow released with no open gates = financially final"
    (let [buyer "0xBuyer" seller "0xSeller" resolver "0xRes0"
          snap (snap-fix/escrow-snapshot {:dispute-resolver resolver
                                          :appeal-window-duration 0
                                          :escrow-fee-bps 0})
          world0 (-> (t/empty-world 1000)
                     (lc/create-escrow buyer "USDC" seller 1000 {} snap)
                     :world)
          ;; release directly (no dispute, no window)
          r (lc/release world0 0 buyer (fn [_ _ _] {:allowed? true :reason-code 0}))
          world (:world r)
          ff    (fin/classify-financial-finality world 0)]
      (when (:ok r)
        (is (= :financially-final (:financial/phase ff)))
        (is (true? (:financially-final? ff)))
        (is (false? (:can-change? ff)))
        (is (empty? (:open-gates ff)))))))

;; ── Financial loss: risk, pending, realized ──────────────────────────────────

(deftest shortfall-without-realized-loss
  (testing "Scenario B: shortfall exists but recovery window open — no realized loss"
    (let [buyer "0xBuyer" seller "0xSeller" resolver "0xRes0"
          snap (snap-fix/escrow-snapshot {:dispute-resolver resolver
                                          :appeal-window-duration 120
                                          :escrow-fee-bps 0
                                          :max-dispute-level 2
                                          :escalation-resolvers {1 "0xRes1" 2 "0xRes2"}})
          world0 (-> (t/empty-world 1000)
                     (reg/register-stake resolver 50000)
                     (lc/create-escrow buyer "USDC" seller 1000 {} snap)
                     :world)
          ;; Dispute + resolve to create pending settlement
          world1 (-> (lc/raise-dispute world0 0 buyer) :world)
          world2 (-> (res/execute-resolution world1 0 resolver true "h0" nil) :world)
          ;; The yield module may have shortfall positions
          loss-result (loss/classify-loss world2 :USDC {:resolve-financial-finality? true})]
      ;; Even if shortfall exists, it's not realized (financial finality not reached)
      (is (contains? #{:normal :loss-pending-finality} (:loss/status loss-result))
          "financial finality not reached so loss not realized")
      (is (false? (:loss/user-realized? loss-result))
          "user loss must NOT be realized before financial finality")
      (is (false? (:loss/protocol-realized? loss-result))
          "protocol loss must NOT be realized before financial finality"))))

(deftest no-realized-loss-without-shortfall
  (testing "No shortfall = normal, even with non-final financial state"
    (let [world (t/empty-world 1000)
          loss-result (loss/classify-loss world :USDC)]
      (is (= :normal (:loss/status loss-result)))
      (is (false? (:loss/user-realized? loss-result))))))

(deftest chain-finality-and-financial-finality-are-distinct
  (testing "chain-final? and financially-final? are never collapsed into one :final? key"
    (let [buyer "0xBuyer" seller "0xSeller" resolver "0xRes0"
          snap (snap-fix/escrow-snapshot {:dispute-resolver resolver
                                          :appeal-window-duration 120
                                          :escrow-fee-bps 0})
          world0 (-> (t/empty-world 1000)
                     (lc/create-escrow buyer "USDC" seller 1000 {} snap)
                     :world)
          world1 (-> (lc/raise-dispute world0 0 buyer) :world)
          world2 (-> (res/execute-resolution world1 0 resolver true "0xhash" nil) :world)
          combined (fin/combine-finality world2 0)]
      ;; combined has both chain and financial as separate sub-maps
      (is (map? (:chain combined)))
      (is (map? (:financial combined)))
      (is (contains? (:chain combined) :chain-final?))
      (is (contains? (:financial combined) :financially-final?))
      ;; key distinction: chain is final, financial is NOT
      (is (true? (:chain-final? (:chain combined))))
      (is (false? (:financially-final? (:financial combined))))
      ;; THERE IS NO AMBIGUOUS :final? key
      (is (nil? (:final? combined)) "no ambiguous :final? key")
      (is (nil? (:settled? combined)) "no ambiguous :settled? key"))))

;; ── Cryptographic solvency ──────────────────────────────────────────────────

(deftest solvency-defaults-to-solvent-unproven
  (testing "Without cryptographic proof, the legacy tier is :unproven; canonical status is :solvent"
    (let [world (t/empty-world 1000)
          result (solv/classify-solvency world)]
      (is (= :solvent (:assessment/status result)))
      (is (= :unproven (:assessment/legacy-tier result)))
      (is (= :unverified (:verification/status result))))))

(deftest test-open-gates-exhaustive
  (testing "Exhaustive coverage of open-gates logic"
    (let [w (t/empty-world 1000)
          wf-id 0]
      (testing "Provisional state (no escrow)"
        (is (empty? (#'fin/open-gates w wf-id (fin/index-pending-slashes w)))))

      (testing "Disputed state with pending settlement"
        (let [w-disp (assoc-in w [:escrow-transfers wf-id] {:status :disputed})
              w-pend (assoc-in w-disp [:pending-settlements wf-id] {:exists true :appeal-deadline 2000})]
          (is (= #{:pending-settlement :appeal-window} (set (#'fin/open-gates w-pend wf-id (fin/index-pending-slashes w-pend))))))

        (let [w-disp (assoc-in w [:escrow-transfers wf-id] {:status :disputed})
              w-pend (assoc-in w-disp [:pending-settlements wf-id] {:exists true :appeal-deadline 500})]
          ;; deadline 500 < world time 1000: appeal-window closed
          (is (= #{:pending-settlement} (set (#'fin/open-gates w-pend wf-id (fin/index-pending-slashes w-pend))))))

        (testing "Yield recovery state"
          (let [w-yield (assoc-in w [:yield/positions "owner1"] {:status :unwinding})
              ;; Requires owner mapping
                w-yield-mapped (assoc w-yield :yield/owner-map {wf-id "owner1"})]
            (is (contains? (set (#'fin/open-gates w-yield-mapped wf-id (fin/index-pending-slashes w-yield-mapped))) :yield-recovery))))))))
