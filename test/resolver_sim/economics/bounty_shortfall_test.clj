(ns resolver-sim.economics.bounty-shortfall-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.economics.bounty-payable :as payable]
            [resolver-sim.economics.bounty-shortfall :as shortfall]
            [resolver-sim.economics.with-bounty.obligation-settlement :as settlement]
            [resolver-sim.economics.with-bounty.fixtures :as fixtures]))

(defn policy-for [source previous-policy]
  (shortfall/build-policy {:policy/id "bounty-shortfall-1"
                           :authority-root "sha256:board"
                           :authority-provenance {:decision "board-minute-17"}
                           :token :token/usdc
                           :snapshot-root (:funding/source-root source)
                           :version (if previous-policy (inc (:policy/version previous-policy)) 1)
                           :parent-root (:policy/hash previous-policy)
                           :rounding :largest-remainder :fee-allocation :outside-reserve
                           :partial-payment? false :residual-liability :retain
                           :recovery :remaining-liability}))
(defn approved-obligation [id amount]
  {:obligation/id id
   :obligation/payable
   (payable/build-bounty-payable {:payable/id id
                                  :award-id (str "award-" id)
                                  :distribution-root (str "sha256:distribution-" id)
                                  :beneficiary (str "researcher/" id)
                                  :amount amount})})
(def obligations [(approved-obligation "alice" 6000)
                  (approved-obligation "bob" 4000)])
(def base {:authorities #{"sha256:board"} :obligations obligations
           :funding-trust-policy fixtures/funding-trust-policy})

(defn input
  ([amount event-id] (input amount event-id nil))
  ([amount event-id previous-policy]
   (let [source (fixtures/funding-source {:beneficiary "shared-reserve"
                                          :operation-root event-id :token :token/usdc
                                          :available amount})]
     (assoc base :policy (policy-for source previous-policy)
            :funding-source source :available amount :event-id event-id
            :prior-reservations []))))

(defn reason [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))

(deftest positive-source-bound-shortfall-and-independent-replay
  (let [args (input 6000 "allocation-1")
        result (shortfall/allocate args)]
    (is (= 10000 (reduce + (map :obligation/approved (:allocation/obligations result)))))
    (is (= 6000 (:allocation/total-allocated result)))
    (is (= {"alice" 3600 "bob" 2400} (:allocation/allocations result)))
    (is (= {"alice" 6000 "bob" 4000} (:allocation/liabilities result))
        "approved claimant liabilities remain open until verified payment")
    (is (= {"alice" 2400 "bob" 1600}
           (:allocation/unfunded-liabilities result)))
    (is (= {"alice" 3600 "bob" 2400} (:allocation/funded-unpaid result)))
    (is (= 10000 (:allocation/total-outstanding-liability result)))
    (is (= 10000 (+ (:allocation/total-paid result)
                    (:allocation/total-funded-unpaid result)
                    (:allocation/total-unfunded-liability result))))
    (is (= :token/usdc (:allocation/token result)))
    (is (= "d69ac6ccd64356631b1e27cdbffe92dc94f9a45fd4333a55f054057b6506b60b"
           (:funding/source-root (:funding-source args))))
    (is (= "a0da305f2229834316bc3e49299e64b19becc444ccadaae84fcd5ea4b3836fd1"
           (:allocation/policy-root result)))
    (is (re-matches #"[0-9a-f]{64}" (:allocation/hash result)))
    (is (= #{"alice" "bob"} (set (keys (:allocation/funded-claims result)))))
    (is (every? (fn [[_ claim]]
                  (and (= :backed (get-in claim [:payable :payable/lifecycle]))
                       (= :committed (get-in claim [:backing :backing/lifecycle]))))
                (:allocation/funded-claims result)))
    (is (:valid? (shortfall/verify (assoc args :allocation result)))))
  (testing "funded portions use canonical full-payment settlement v1"
    (let [args (input 6000 "allocation-1")
          allocation (shortfall/allocate args)
          settlements
          (into {}
                (map (fn [[id {:keys [payable backing]}]]
                       (let [amount (:payable/amount payable)
                             source-root (get-in backing [:backing/context :funding/source-root])
                             observation (fixtures/chain-observation
                                          {:observation/payable-id (:payable/id payable)
                                           :observation/operation-root
                                           (get-in backing [:backing/context :funding/operation-root])
                                           :observation/funding-source-root source-root
                                           :observation/prf-funding-source-root source-root
                                           :observation/beneficiary (:payable/beneficiary payable)
                                           :observation/amount amount
                                           :observation/reserve-before amount
                                           :observation/reserve-after 0
                                           :observation/beneficiary-before 0
                                           :observation/beneficiary-after amount
                                           :observation/transaction-hash
                                           (str "tx:fixture:" (:payable/id payable))})
                             inputs {:payable-before payable
                                     :payable-after (payable/transition-payable-lifecycle
                                                     payable :settled)
                                     :backing backing
                                     :payout-receipt (fixtures/payout-receipt payable backing)
                                     :payout-trust-policy fixtures/payout-trust-policy
                                     :observation observation
                                     :observation-trust-policy fixtures/chain-observer-trust-policy
                                     :expected-context {:chain-id 31337
                                                        :contract "0x000000000000000000000000000000000000b017"
                                                        :chain-instance-root (str "sha256:" (apply str (repeat 64 "b")))
                                                        :genesis-hash (str "0x" (apply str (repeat 64 "0")))
                                                        :contract-code-sha256 (str "sha256:" (apply str (repeat 64 "a")))
                                                        :observation-source-id "test-rpc-observer"
                                                        :asset :token/usdc
                                                        :funding-source-root source-root}}]
                         [id (assoc inputs :settlement (settlement/build-settlement inputs))]))
                     (:allocation/funded-claims allocation)))
          result (shortfall/verify-funded-settlements
                  {:allocation allocation :allocation-input args :settlements settlements})]
      (is (:valid? result))
      (is (string? (:settlement-batch/root result)))
      (is (= (:allocation/allocations allocation)
             (get-in result [:settlement-batch :settlement-batch/paid])))
      (let [recovery-input (assoc (input 4000 "allocation-2" (:policy args))
                                  :recovery-of allocation
                                  :previous-inputs (assoc args :allocation allocation)
                                  :previous-settlements settlements)
            recovered (shortfall/allocate recovery-input)]
        (is (= (:allocation/allocations allocation) (:allocation/paid recovered)))
        (is (= {"alice" 2400 "bob" 1600}
               (:allocation/liabilities recovered)))
        (is (= {"alice" 0 "bob" 0}
               (:allocation/unfunded-liabilities recovered)))
        (is (= {"alice" 2400 "bob" 1600}
               (:allocation/funded-unpaid recovered)))
        (is (= #{"allocation-1" "allocation-2"}
               (set (keys (:allocation/funded-claims-history recovered)))))
        (is (= (:settlement-batch/root result) (:allocation/payment-batch-root recovered)))
        (is (= (mapv #(select-keys % [:obligation/id :obligation/root :obligation/approved])
                     (:allocation/obligations allocation))
               (mapv #(select-keys % [:obligation/id :obligation/root :obligation/approved])
                     (:allocation/obligations recovered)))))
      (is (= :settlement-set-mismatch
             (:reason (shortfall/verify-funded-settlements
                       {:allocation allocation :allocation-input args :settlements {}}))))
      (let [partial (shortfall/verify-funded-settlements
                     {:allocation allocation :allocation-input args
                      :settlements (select-keys settlements ["alice"])})]
        (is (:valid? partial) (pr-str partial))
        (is (= {"alice" 3600}
               (get-in partial [:settlement-batch :settlement-batch/paid])))
        (let [partial-recovery-input
              (assoc (input 4000 "allocation-3" (:policy args))
                     :recovery-of allocation
                     :previous-inputs (assoc args :allocation allocation)
                     :previous-settlements (select-keys settlements ["alice"]))
              partial-recovery (shortfall/allocate partial-recovery-input)]
          (is (= {"alice" 3600} (:allocation/paid partial-recovery)))
          (is (= 6400 (:allocation/total-outstanding-liability partial-recovery)))))
      (is (not (:valid? (shortfall/verify-funded-settlements
                         {:allocation allocation :allocation-input args
                          :settlements (update-in settlements ["alice" :settlement
                                                               :settlement/paid] inc)}))))))
  (testing "caller edited allocations fail replay"
    (let [args (input 6000 "allocation-1")
          result (shortfall/allocate args)]
      (is (= :evidence-mismatch
             (:reason (shortfall/verify
                       (assoc args :allocation
                              (assoc result :allocation/allocations {"alice" 3601 "bob" 2399})))))))))

(deftest recovery-uses-a-new-authenticated-source-and-retains-liability-roots
  (let [previous-input (input 6000 "allocation-1")
        previous (shortfall/allocate previous-input)
        recovery-input (assoc (input 4000 "allocation-2" (:policy previous-input))
                              :recovery-of previous
                              :previous-inputs (assoc previous-input :allocation previous))
        recovered (shortfall/allocate recovery-input)]
    (is (= 4000 (:allocation/total-allocated recovered)))
    (is (= 10000 (reduce + (vals (:allocation/cumulative-allocations recovered)))))
    (is (= 0 (reduce + (vals (:allocation/unfunded-liabilities recovered)))))
    (is (= 10000 (reduce + (vals (:allocation/liabilities recovered))))
        "funding a claim does not discharge the unpaid obligation")
    (is (= 10000 (:allocation/total-funded-unpaid recovered)))
    (is (= (mapv :obligation/root (:allocation/obligations previous))
           (mapv :obligation/root (:allocation/obligations recovered))))
    (is (= (:allocation/hash previous) (:allocation/recovery-of recovered)))
    (is (:valid? (shortfall/verify (assoc recovery-input :allocation recovered)))))
  (testing "old source replay and duplicate recovery event are rejected"
    (let [previous-input (input 6000 "allocation-1")
          previous (shortfall/allocate previous-input)
          predecessor (assoc previous-input :allocation previous)
          same-source (assoc (input 6000 "allocation-1" (:policy previous))
                             :recovery-of previous :previous-inputs predecessor)]
      (is (= :stale-recovery (reason #(shortfall/allocate same-source))))
      (is (= :stale-recovery
             (reason #(shortfall/allocate
                       (assoc (input 4000 "allocation-1" (:policy previous-input))
                              :recovery-of previous :previous-inputs predecessor))))))))

(deftest successor-policy-requires-a-verified-recovery-predecessor
  (let [previous-input (input 6000 "lineage-1")
        successor-input (input 4000 "lineage-2" (:policy previous-input))]
    (is (= :stale-recovery
           (reason #(shortfall/allocate successor-input))))))

(deftest policy-source-and-obligation-substitution-fail-closed
  (is (= :unauthorized-policy
         (reason #(shortfall/allocate (assoc (input 6000 "unauthorized") :authorities #{})))))
  (is (= :invalid-obligations
         (reason #(shortfall/allocate
                   (assoc (input 6000 "duplicate") :obligations [first obligations first obligations])))))
  (is (= :invalid-obligations
         (reason #(shortfall/allocate
                   (assoc (input 6000 "cancel")
                          :obligations [(assoc (first obligations) :obligation/status :cancelled)])))))
  (is (= :invalid-obligations
         (reason #(shortfall/allocate
                   (assoc (input 6000 "adjustment")
                          :obligations [(assoc (first obligations) :obligation/adjustment 1)])))))
  (let [args (input 6000 "wrong-token")
        wrong-source (fixtures/funding-source {:beneficiary "shared-reserve"
                                               :operation-root "wrong-token" :token :token/eth
                                               :available 6000})]
    (is (= :funding-source-binding-mismatch
           (reason #(shortfall/allocate (assoc args :funding-source wrong-source))))))
  (let [args (input 10000 "overcommit")]
    (is (= :invalid-allocation-input
           (reason #(shortfall/allocate (assoc args :available 10001))))))
  (let [first-input (input 6000 "reserved-1")
        first-allocation (shortfall/allocate first-input)
        replay (assoc (input 6000 "reserved-1")
                      :prior-reservations [{:allocation first-allocation
                                            :inputs (assoc first-input
                                                           :allocation first-allocation)}])]
    (is (= :duplicate-reservation (reason #(shortfall/allocate replay)))))
  (is (= :invalid-obligations
         (reason #(shortfall/allocate
                   (assoc (input 6000 "adjustment")
                          :obligations [(assoc (first obligations)
                                               :obligation/adjustment {:amount 1})]))))))

(deftest zero-source-and-unsupported-partial-payment-are-explicit
  (let [zero (shortfall/allocate (input 0 "zero"))]
    (is (= 0 (:allocation/total-allocated zero)))
    (is (= 10000 (reduce + (vals (:allocation/liabilities zero)))))
    (is (= 10000 (reduce + (vals (:allocation/unfunded-liabilities zero))))))
  (is (= :invalid-policy
         (reason #(shortfall/build-policy
                   {:policy/id "partial" :authority-root "sha256:board"
                    :authority-provenance {:decision "unsupported"}
                    :token :token/usdc
                    :snapshot-root "sha256:source"
                    :rounding :largest-remainder :fee-allocation :outside-reserve
                    :partial-payment? true :residual-liability :retain
                    :recovery :remaining-liability})))))

(deftest inadequate-new-funding-retains-liability-and-missing-source-is-typed
  (let [args (input 5000 "late-funding")
        result (shortfall/allocate args)]
    (is (= 5000 (:allocation/total-allocated result)))
    (is (= 10000 (reduce + (vals (:allocation/liabilities result)))))
    (is (= 5000 (reduce + (vals (:allocation/unfunded-liabilities result)))))
    (is (= :invalid-funding-source
           (:reason (shortfall/verify (assoc args :funding-source nil)))))))

(deftest deterministic-largest-remainder-rounding-is-replay-stable
  (let [args (assoc (input 1 "rounding")
                    :obligations [(approved-obligation "a" 1)
                                  (approved-obligation "b" 1)
                                  (approved-obligation "c" 1)])
        first-result (shortfall/allocate args)
        second-result (shortfall/allocate args)]
    (is (= {"a" 1 "b" 0 "c" 0} (:allocation/allocations first-result)))
    (is (= (:allocation/hash first-result) (:allocation/hash second-result)))
    (is (= 1 (reduce + (vals (:allocation/allocations first-result)))))))
