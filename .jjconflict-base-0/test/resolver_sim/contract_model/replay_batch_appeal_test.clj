(ns resolver-sim.contract-model.replay-batch-appeal-test
  "Batch conflict tests for resolver-domain interactions.
   
   Tests bucket conflict resolution for actions sharing the :resolver domain:
   register-stake, withdraw-stake, execute-resolution, set-resolver-capacity, etc.
   
   Does NOT test the full slash governance pipeline — that requires a complex
   scenario setup that often fails at protocol invariants.  These tests focus on
   the BATCH CONFLICT DOMAIN behavior (shared [:resolver r] domain vectors)
   which is what the batch engine actually checks."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew :as sew]))

(def ^:private buyer     {:id "buyer"    :type "honest"    :address "0xB"})
(def ^:private seller    {:id "seller"   :type "honest"    :address "0xS"})
(def ^:private resolv0   {:id "resolv0"  :role "resolver"  :address "0xR0"})
(def ^:private resolv1   {:id "resolv1"  :role "resolver"  :address "0xR1"})

(def ^:private params
  {:resolver-fee-bps 0 :appeal-window-duration 0 :max-dispute-duration 300})

(defn- make-scenario
  "Build a deterministic-batch Sew scenario."
  [events]
  {:schema-version "1.0"
   :scenario-id "batch-resolver-test"
   :initial-block-time 1000
   :execution-mode :deterministic-batch
   :agents [buyer seller resolv0 resolv1]
   :protocol-params params
   :events (map-indexed (fn [i e] (assoc e :seq i)) events)
   :theory {:claim-id :test/batch-resolver
            :assumptions []
            :falsifies-if [{:metric :reverts :op :>= :value 0}]}})

(defn- event-by-seq [result seq-num]
  (some #(when (= seq-num (:seq %)) %) (:trace result)))

;; ---------------------------------------------------------------------------
;; Simple two-event batch: register-stake + withdraw-stake, same resolver
;; Both have [:resolver "0xR0"] domain → second rejected
;; ---------------------------------------------------------------------------

(deftest register-and-withdraw-same-resolver-conflict
  (testing "Register-stake and withdraw-stake for same resolver at same timestamp:
            second rejected as batch-conflict"
    (let [result (sew/replay-with-sew-protocol
                  (make-scenario
                   [{:time 1000 :agent "resolv0" :action "register_stake" :params {:amount 10000}}
                    {:time 1000 :agent "resolv0" :action "withdraw_stake" :params {:amount 5000}}]))
          e0 (event-by-seq result 0)
          e1 (event-by-seq result 1)]
      (is (= :ok (:result e0)) "register-stake accepted")
      (is (= :rejected (:result e1)) "withdraw-stake rejected")
      (is (= :batch-conflict (:error e1)))
      (is (= [:resolver "0xR0"] (:conflict-domain e1)))
      (is (= 1 (:batch-conflicts (:metrics result))))
      (is (= 0 (:conflict-with-seq e1))))))

;; ---------------------------------------------------------------------------
;; Register + withdraw for DIFFERENT resolvers at same timestamp
;; Different [:resolver r] domains → no conflict
;; ---------------------------------------------------------------------------

(deftest register-and-withdraw-different-resolvers-no-conflict
  (testing "Register-stake and withdraw-stake for different resolvers:
            both accepted (distinct [:resolver r] domains)"
    (let [result (sew/replay-with-sew-protocol
                  (make-scenario
                   [{:time 1000 :agent "resolv0" :action "register_stake" :params {:amount 10000}}
                    {:time 1000 :agent "resolv1" :action "register_stake" :params {:amount 10000}}]))
          e0 (event-by-seq result 0)
          e1 (event-by-seq result 1)]
      (is (= :ok (:result e0)) "first register-stake accepted")
      (is (= :ok (:result e1)) "second register-stake accepted (different resolver)")
      (is (zero? (:batch-conflicts (:metrics result))) "no batch conflicts"))))

;; ---------------------------------------------------------------------------
;; Two register-stake for the SAME resolver at the same timestamp
;; Same [:resolver r] → second rejected
;; ---------------------------------------------------------------------------

(deftest double-register-same-resolver-conflict
  (testing "Two register-stake for same resolver at same timestamp:
            second rejected"
    (let [result (sew/replay-with-sew-protocol
                  (make-scenario
                   [{:time 1000 :agent "resolv0" :action "register_stake" :params {:amount 10000}}
                    {:time 1000 :agent "resolv0" :action "register_stake" :params {:amount 10000}}]))
          e1 (event-by-seq result 1)]
      (is (= :rejected (:result e1)))
      (is (= :batch-conflict (:error e1)))
      (is (= [:resolver "0xR0"] (:conflict-domain e1))))))

;; ---------------------------------------------------------------------------
;; Three-register bucket: resolv0 + resolv1 + resolv0
;; resolv0 appears twice at index 0 and 2 → seq 1 accepted, seq 2 conflicts with seq 0
;; ---------------------------------------------------------------------------

(deftest three-registers-mixed-resolvers
  (testing "Three register-stake at same timestamp: resolv0, resolv1, resolv0.
            Third rejects (re-claims [:resolver 0xR0] after seq 0)"
    (let [result (sew/replay-with-sew-protocol
                  (make-scenario
                   [{:time 1000 :agent "resolv0" :action "register_stake" :params {:amount 10000}}
                    {:time 1000 :agent "resolv1" :action "register_stake" :params {:amount 10000}}
                    {:time 1000 :agent "resolv0" :action "register_stake" :params {:amount 10000}}]))
          e0 (event-by-seq result 0)
          e1 (event-by-seq result 1)
          e2 (event-by-seq result 2)]
      (is (= :ok (:result e0)) "resolv0 accepted")
      (is (= :ok (:result e1)) "resolv1 accepted (different resolver)")
      (is (= :rejected (:result e2)) "resolv0 rejected (conflicts with seq 0)")
      (is (= [:resolver "0xR0"] (:conflict-domain e2)))
      (is (= 0 (:conflict-with-seq e2)) "conflicts with first register-stake for 0xR0")
      (is (= 1 (:batch-conflicts (:metrics result)))))))

;; ---------------------------------------------------------------------------
;; Set-resolver-capacity + register-stake for same resolver at same timestamp
;; Both have [:resolver r] → second rejected
;; ---------------------------------------------------------------------------

(deftest set-capacity-and-register-same-resolver-conflict
  (testing "Set-resolver-capacity and register-stake for same resolver:
            second rejected"
    (let [result (sew/replay-with-sew-protocol
                  (make-scenario
                   [{:time 1000 :agent "resolv0" :action "set_resolver_capacity" :params {:max-concurrent 5}}
                    {:time 1000 :agent "resolv0" :action "register_stake" :params {:amount 10000}}]))
          e1 (event-by-seq result 1)]
      (is (= :rejected (:result e1)))
      (is (= :batch-conflict (:error e1))))))

;; ---------------------------------------------------------------------------
;; Register-stake for resolv0 at time 1000, THEN register-stake for resolv0 at time 1100
;; Different timestamps → different buckets → no conflict
;; ---------------------------------------------------------------------------

(deftest register-same-resolver-different-times-no-conflict
  (testing "Register-stake for same resolver at different timestamps:
            both accepted (different buckets)"
    (let [result (sew/replay-with-sew-protocol
                  (make-scenario
                   [{:time 1000 :agent "resolv0" :action "register_stake" :params {:amount 5000}}
                    {:time 1100 :agent "resolv0" :action "register_stake" :params {:amount 5000}}]))
          e0 (event-by-seq result 0)
          e1 (event-by-seq result 1)]
      (is (= :ok (:result e0)))
      (is (= :ok (:result e1)))
      (is (zero? (:batch-conflicts (:metrics result)))))))

;; ---------------------------------------------------------------------------
;; Execute-resolution for different workflows with the SAME resolver
;; Same [:resolver r] → second rejected
;; Previously tested in replay_batch_sew_test, repeated here for completeness
;; ---------------------------------------------------------------------------

(deftest same-resolver-two-resolutions-conflict
  (testing "Two execute-resolution for different workflows at same timestamp
            with the same resolver: second rejected on [:resolver r]"
    (let [result (sew/replay-with-sew-protocol
                  (make-scenario
                   [{:time 1000 :agent "buyer" :action "create_escrow"
                     :params {:token "USDC" :to "0xS" :amount 1000 :custom-resolver "0xR0"}}
                    {:time 1010 :agent "buyer" :action "create_escrow"
                     :params {:token "DAI" :to "0xS" :amount 1000 :custom-resolver "0xR0"}}
                    {:time 1060 :agent "buyer" :action "raise_dispute" :params {:workflow-id 0}}
                    {:time 1070 :agent "buyer" :action "raise_dispute" :params {:workflow-id 1}}
                    {:time 1120 :agent "resolv0" :action "execute_resolution"
                     :params {:workflow-id 0 :is-release true :resolution-hash "0x1"}}
                    {:time 1120 :agent "resolv0" :action "execute_resolution"
                     :params {:workflow-id 1 :is-release true :resolution-hash "0x2"}}]))
          e5 (event-by-seq result 5)]
      (is (= :fail (:outcome result)) "scenario fails: workflow 1 unresolved")
      (is (= :rejected (:result e5)))
      (is (= :batch-conflict (:error e5)))
      (is (= [:resolver "0xR0"] (:conflict-domain e5))))))
