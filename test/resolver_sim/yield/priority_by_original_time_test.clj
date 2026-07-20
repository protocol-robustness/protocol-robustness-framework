(ns resolver-sim.yield.priority-by-original-time-test
  "Fixture suite validating priority-by-original-time behavior.

   Tests cover:
   - Deposit sequence numbering (original-priority)
   - Shared withdrawal participant ordering by original-priority
   - Deferred position priority preservation across partial-fill cycles
   - Full lifecycle: deposit → shortfall → deferred → later withdrawal"
  (:require [clojure.test :refer :all]
            [resolver-sim.yield.modules.liquid-lending :as ll]
            [resolver-sim.yield.position :as pos]
            [resolver-sim.yield.invariants :as inv]))

(def test-mod
  (ll/make-liquid-lending-module :test-mod))

(def base-world
  {:yield/indices {:test-mod {"USDC" 1.0}}
   :yield/rates   {:test-mod {"USDC" 0.05}}
   :yield/risk    {:test-mod {"USDC" {:liquidity-mode :available
                                        :loss-mode :none}}}
   :yield/held-balances {"USDC" 1000000}
   :yield/module-status {:test-mod :active}
   :block-time 1000
   :run/id "test-run"
   :execution/id "test-execution"
   :params {:scenario-id "test-scenario"}})

(defn- deposit-owners
  [world owners amount]
  (reduce (fn [w owner]
            (ll/deposit w test-mod {:owner/id owner :amount amount :token "USDC"}))
          world
          owners))

(defn- participants-from
   [world]
   (->> (:yield/pro-rata-propagations world)
        vals
        (sort-by :propagation/id)
        last
        :participants))

;; ── Fixture suite 1: Deposit sequence numbering ──────────────────────────

(deftest deposit-assigns-incrementing-original-priority
  (testing "first deposit gets priority 0"
    (let [w (ll/deposit base-world test-mod {:owner/id "alice" :amount 100 :token "USDC"})]
      (is (= 0 (get-in w [:yield/positions "alice" :original-priority])))))
  (testing "second deposit gets priority 1"
    (let [w (ll/deposit base-world test-mod {:owner/id "alice" :amount 100 :token "USDC"})
          w (ll/deposit w test-mod {:owner/id "bob" :amount 100 :token "USDC"})]
      (is (= 0 (get-in w [:yield/positions "alice" :original-priority])))
      (is (= 1 (get-in w [:yield/positions "bob" :original-priority])))))
  (testing "deposits in reverse alphabetical order still produce monotonic priority"
    (let [owners ["zara" "yuki" "xander" "wendy"]
          w (deposit-owners base-world owners 100)]
      (is (= [0 1 2 3] (mapv #(get-in w [:yield/positions % :original-priority]) owners)))))
  (testing "deposit-seq counter uses keyword token keys"
    (let [w (ll/deposit base-world test-mod {:owner/id "alice" :amount 100 :token "USDC"})
          w (ll/deposit w test-mod {:owner/id "bob" :amount 100 :token "USDC"})]
      (is (= 2 (get-in w [:yield/deposit-seq :test-mod :USDC]))))))

;; ── Fixture suite 2: Priority ordering in shared withdrawals ─────────────

(deftest shared-withdrawal-orders-participants-by-original-priority
  (testing "participants ordered by deposit order when alice deposited first"
    (let [w (deposit-owners base-world ["alice" "bob" "carol"] 100)
          w (assoc-in w [:total-held :USDC] 300)
          w (ll/withdraw-shared w test-mod {:owner-ids ["carol" "bob" "alice"]
                                             :token "USDC"
                                             :allocation-mode :pro-rata})]
      (is (= ["alice" "bob" "carol"] (mapv :participant-id (participants-from w)))
          "ordered by original-priority, not by input order")))
  (testing "participants ordered by deposit order when deposited in reverse"
    (let [w (deposit-owners base-world ["carol" "bob" "alice"] 100)
          w (assoc-in w [:total-held :USDC] 300)
          w (ll/withdraw-shared w test-mod {:owner-ids ["alice" "bob" "carol"]
                                             :token "USDC"
                                             :allocation-mode :pro-rata})]
      (is (= ["carol" "bob" "alice"] (mapv :participant-id (participants-from w)))
          "carol priority 0, bob priority 1, alice priority 2")))
  (testing "participant order is not alphabetical"
    (let [w (deposit-owners base-world ["alice" "zara" "bob"] 100)
          w (assoc-in w [:total-held :USDC] 300)
          w (ll/withdraw-shared w test-mod {:owner-ids ["alice" "bob" "zara"]
                                             :token "USDC"
                                             :allocation-mode :pro-rata})]
      (is (= ["alice" "zara" "bob"] (mapv :participant-id (participants-from w)))
          "alice priority 0, zara priority 1, bob priority 2"))))

;; ── Fixture suite 3: Priority preservation across partial-fill cycles ────

(deftest deferred-position-preserves-original-priority
  (testing "deferred position inherits original-priority from main position"
    (let [w (deposit-owners base-world ["alice" "bob"] 100)
          w (assoc-in w [:total-held :USDC] 50)
          w (ll/withdraw-shared w test-mod {:owner-ids ["alice" "bob"]
                                             :token "USDC"
                                             :allocation-mode :pro-rata})
          pos-alice (get-in w [:yield/positions "alice"])
          pos-bob (get-in w [:yield/positions "bob"])]
      (is (= :unwinding (:status pos-alice)))
      (is (= :unwinding (:status pos-bob)))
      (is (= 0 (get-in pos-alice [:deferred-position :position/original-priority]))
          "alice deferred inherits original-priority 0")
      (is (= 1 (get-in pos-bob [:deferred-position :position/original-priority]))
          "bob deferred inherits original-priority 1")))
  (testing "second deferral reuses prior-lineage original-priority"
    (let [w (deposit-owners base-world ["alice" "bob"] 100)
          w (assoc-in w [:total-held :USDC] 50)
          w (ll/withdraw-shared w test-mod {:owner-ids ["alice" "bob"]
                                             :token "USDC"
                                             :allocation-mode :pro-rata})
          w (assoc-in w [:total-held :USDC] 30)
          w (ll/withdraw-shared w test-mod {:owner-ids ["alice" "bob"]
                                             :token "USDC"
                                             :allocation-mode :pro-rata})]
      (is (= 0 (get-in w [:yield/positions "alice" :deferred-position :position/original-priority])))
      (is (= 1 (get-in w [:yield/positions "bob" :deferred-position :position/original-priority]))))))

;; ── Fixture suite 4: Full lifecycle FIFO priority ────────────────────────

(deftest full-lifecycle-fifo-priority
  (testing "participant ordering in propagation evidence reflects deposit order"
    (let [w (deposit-owners base-world ["alice" "bob"] 100)
          w (assoc-in w [:total-held :USDC] 100)
          w (ll/withdraw-shared w test-mod {:owner-ids ["bob" "alice"]
                                             :token "USDC"
                                             :allocation-mode :pro-rata})]
      (is (= ["alice" "bob"] (mapv :participant-id (participants-from w)))
          "alice (priority 0) ordered before bob (priority 1)")))
  (testing "deferred-position holder ordered before newer depositor"
    (let [w (deposit-owners base-world ["alice"] 100)
          w (assoc-in w [:total-held :USDC] 50)
          w (ll/withdraw-shared w test-mod {:owner-ids ["alice"]
                                             :token "USDC"
                                             :allocation-mode :pro-rata})
          w (ll/deposit w test-mod {:owner/id "bob" :amount 100 :token "USDC"})
          w (assoc-in w [:total-held :USDC] 100)
          w (ll/withdraw-shared w test-mod {:owner-ids ["bob" "alice"]
                                             :token "USDC"
                                             :allocation-mode :pro-rata})]
      (is (= ["alice" "bob"] (mapv :participant-id (participants-from w)))
          "alice (priority 0) ordered before bob (priority 1)"))))

;; ── Fixture suite 5: Edge cases ─────────────────────────────────────────

(deftest priority-with-unknown-position-defaults
  (testing "positions via make-position get original-priority Long/MAX_VALUE"
    (let [pos (pos/make-position {:owner/id "legacy" :module/id :test-mod
                                  :token "USDC" :principal 100})]
      (is (= Long/MAX_VALUE (:original-priority pos)))))
  (testing "new deposits get lower priority than legacy positions"
    (let [pos (pos/make-position {:owner/id "legacy" :module/id :test-mod
                                  :token "USDC" :principal 100})
          w (assoc-in base-world [:yield/positions "legacy"] pos)
          w (ll/deposit w test-mod {:owner/id "new-user" :amount 100 :token "USDC"})
          w (assoc-in w [:total-held :USDC] 200)
          w (ll/withdraw-shared w test-mod {:owner-ids ["legacy" "new-user"]
                                             :token "USDC"
                                             :allocation-mode :pro-rata})]
      (is (= ["new-user" "legacy"] (mapv :participant-id (participants-from w)))
          "new-user (priority 0) ordered before legacy (MAX_VALUE)"))))

;; ── Fixture suite 6: Standard invariants pass with priority ordering ─────

(deftest standard-invariants-pass-with-priority-ordering
  (testing "pro-rata-accounting-reconciles passes with priority-ordered withdrawal"
    (let [w (deposit-owners base-world ["alice" "bob" "carol"] 100)
          w (assoc-in w [:total-held :USDC] 150)
          w (ll/withdraw-shared w test-mod {:owner-ids ["carol" "alice" "bob"]
                                             :token "USDC"
                                             :allocation-mode :pro-rata})]
      (is (:holds? (inv/check-pro-rata-accounting-reconciles w)))))
  (testing "accounting reconcile passes with shortfall under priority ordering"
    (let [w (deposit-owners base-world ["alice" "bob"] 100)
          w (assoc-in w [:total-held :USDC] 50)
          w (ll/withdraw-shared w test-mod {:owner-ids ["bob" "alice"]
                                             :token "USDC"
                                             :allocation-mode :pro-rata})]
      (is (:holds? (inv/check-pro-rata-accounting-reconciles w))))))

;; ── Fixture suite 7: Multi-token and multi-module scoping ────────────────

(deftest deposit-seq-is-scoped-by-module-and-token
  (testing "separate modules have independent deposit sequences"
    (let [mod-a (ll/make-liquid-lending-module :mod-a)
          mod-b (ll/make-liquid-lending-module :mod-b)
          w {:yield/indices {:mod-a {"USDC" 1.0} :mod-b {"USDC" 1.0}}
             :yield/rates   {:mod-a {"USDC" 0.05} :mod-b {"USDC" 0.05}}
             :yield/risk    {:mod-a {"USDC" {:liquidity-mode :available :loss-mode :none}}
                             :mod-b {"USDC" {:liquidity-mode :available :loss-mode :none}}}
             :yield/held-balances {"USDC" 1000000}
             :yield/module-status {:mod-a :active :mod-b :active}
             :block-time 1000}
          w (ll/deposit w mod-a {:owner/id "alice" :amount 100 :token "USDC"})
          w (ll/deposit w mod-b {:owner/id "bob" :amount 100 :token "USDC"})]
      (is (= 0 (get-in w [:yield/positions "alice" :original-priority])))
      (is (= 0 (get-in w [:yield/positions "bob" :original-priority]))
          "bob in mod-b also gets priority 0 — independent counter")
      (is (= 1 (get-in w [:yield/deposit-seq :mod-a :USDC])))
      (is (= 1 (get-in w [:yield/deposit-seq :mod-b :USDC])))))
  (testing "different tokens have independent deposit sequences"
    (let [w (ll/deposit base-world test-mod {:owner/id "alice" :amount 100 :token "USDC"})
          w (ll/deposit w test-mod {:owner/id "bob" :amount 100 :token "ETH"})
          w (ll/deposit w test-mod {:owner/id "carol" :amount 100 :token "USDC"})]
      (is (= 0 (get-in w [:yield/positions "alice" :original-priority])))
      (is (= 0 (get-in w [:yield/positions "bob" :original-priority]))
          "bob in ETH gets priority 0 — independent counter")
      (is (= 1 (get-in w [:yield/positions "carol" :original-priority]))
          "carol in USDC gets priority 1")
      (is (= 2 (get-in w [:yield/deposit-seq :test-mod :USDC])))
      (is (= 1 (get-in w [:yield/deposit-seq :test-mod :ETH]))))))
