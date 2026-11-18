(ns resolver-sim.contract-model.replay-batch-sew-test
  "Batch conflict resolution tests against the real SewProtocol.
   
   Verifies that sew-event-conflict-domains produces correct domain vectors
   and that the replay engine's deterministic-batch mode correctly accepts
   non-conflicting events and rejects conflicting ones."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew :as sew]))

(def ^:private alice0   {:id "alice0"   :type "honest"   :address "0xA0"})
(def ^:private alice1   {:id "alice1"   :type "honest"   :address "0xA1"})
(def ^:private alice2   {:id "alice2"   :type "honest"   :address "0xA2"})
(def ^:private bob0     {:id "bob0"     :type "honest"   :address "0xB0"})
(def ^:private bob1     {:id "bob1"     :type "honest"   :address "0xB1"})
(def ^:private bob2     {:id "bob2"     :type "honest"   :address "0xB2"})
(def ^:private resolv0  {:id "resolv0"  :role "resolver" :address "0xR0"})
(def ^:private resolv1  {:id "resolv1"  :role "resolver" :address "0xR1"})
(def ^:private resolv2  {:id "resolv2"  :role "resolver" :address "0xR2"})
(def ^:private keeper   {:id "keeper"   :role "keeper"   :address "0xK"})

(def ^:private dispute-timeout-params
  {:resolver-fee-bps 0 :appeal-window-duration 0 :max-dispute-duration 300})

(defn make-scenario
  "Build a deterministic-batch Sew scenario with the given events."
  [events]
  {:schema-version "1.0"
   :scenario-id "batch-sew-test"
   :title "Batch Sew integration test"
   :scenario-author "@test"
   :purpose :regression
   :initial-block-time 1000
   :execution-mode :deterministic-batch
   :agents [alice0 alice1 alice2 bob0 bob1 bob2 resolv0 resolv1 resolv2 keeper]
   :protocol-params dispute-timeout-params
   :events events
   :theory {:claim-id :test/batch-sew
            :assumptions []
            :falsifies-if [{:metric :reverts :op :>= :value 0}]}})

(defn- event-by-seq
  [result seq-num]
  (some #(when (= seq-num (:seq %)) %) (:trace result)))

;; ---------------------------------------------------------------------------
;; 1. Same-workflow conflict
;; ---------------------------------------------------------------------------

(deftest same-workflow-execute-resolution-conflicts
  (testing "Two execute-resolution for same workflow at same timestamp:
            seq 1 rejected as batch-conflict (shared [:workflow X] domain)"
    (let [result (sew/replay-with-sew-protocol
                  (make-scenario
                   [{:seq 0 :time 1000 :agent "alice0" :action "create_escrow"
                     :params {:token "USDC" :to "0xbob0" :amount 1000
                              :custom-resolver "0xR0"}}
                    {:seq 1 :time 1060 :agent "alice0" :action "raise_dispute"
                     :params {:workflow-id 0}}
                    ;; Two execute-resolution for workflow 0 at same timestamp
                    {:seq 2 :time 1120 :agent "resolv0" :action "execute_resolution"
                     :params {:workflow-id 0 :is-release true :resolution-hash "0x1"}}
                    {:seq 3 :time 1120 :agent "resolv0" :action "execute_resolution"
                     :params {:workflow-id 0 :is-release true :resolution-hash "0x2"}}]))
          e2 (event-by-seq result 2)
          e3 (event-by-seq result 3)]
      (is (= :pass (:outcome result))
          "first resolution succeeds, scenario closes workflow")
      (is (= :ok (:result e2)) "first resolution accepted")
      (is (= :rejected (:result e3)) "second resolution rejected")
      (is (= :batch-conflict (:error e3)))
      (is (contains? #{[:workflow 0] [:resolver "0xR0"]} (:conflict-domain e3))
          "conflict on shared workflow or resolver domain")
      (is (= 2 (:conflict-with-seq e3)))
      (is (= 1 (:batch-conflicts (:metrics result)))))))

;; ---------------------------------------------------------------------------
;; 2. Different-workflow no-conflict
;; ---------------------------------------------------------------------------

(deftest different-workflow-resolutions-no-conflict
  (testing "Two execute-resolution for different workflows at same timestamp:
            both accepted (distinct [:workflow X] domains)"
    (let [result (sew/replay-with-sew-protocol
                  (make-scenario
                   [{:seq 0 :time 1000 :agent "alice0" :action "create_escrow"
                     :params {:token "USDC" :to "0xbob0" :amount 1000
                              :custom-resolver "0xR0"}}
                    {:seq 1 :time 1010 :agent "alice1" :action "create_escrow"
                     :params {:token "DAI" :to "0xbob1" :amount 1000
                              :custom-resolver "0xR1"}}
                    {:seq 2 :time 1060 :agent "alice0" :action "raise_dispute"
                     :params {:workflow-id 0}}
                    {:seq 3 :time 1070 :agent "alice1" :action "raise_dispute"
                     :params {:workflow-id 1}}
                    ;; Two execute-resolution for different workflows at same timestamp
                    {:seq 4 :time 1120 :agent "resolv0" :action "execute_resolution"
                     :params {:workflow-id 0 :is-release true :resolution-hash "0x1"}}
                    {:seq 5 :time 1120 :agent "resolv1" :action "execute_resolution"
                     :params {:workflow-id 1 :is-release true :resolution-hash "0x2"}}]))
          e4 (event-by-seq result 4)
          e5 (event-by-seq result 5)]
      (is (= :pass (:outcome result)))
      (is (= :ok (:result e4)) "resolution for wf0 accepted")
      (is (= :ok (:result e5)) "resolution for wf1 accepted")
      (is (zero? (:batch-conflicts (:metrics result))) "no conflicts"))))

;; ---------------------------------------------------------------------------
;; 3. Same-resolver conflict across different workflows
;; ---------------------------------------------------------------------------

(deftest same-resolver-execute-resolution-conflicts
  (testing "Two execute-resolution for different workflows with the same resolver:
            second rejected (shared [:resolver r] domain), scenario fails
            because the unresolved workflow remains open"
    (let [result (sew/replay-with-sew-protocol
                  (make-scenario
                   [{:seq 0 :time 1000 :agent "alice0" :action "create_escrow"
                     :params {:token "USDC" :to "0xbob0" :amount 1000
                              :custom-resolver "0xR0"}}
                    ;; Resolver 0 assigned to BOTH workflows
                    {:seq 1 :time 1010 :agent "alice1" :action "create_escrow"
                     :params {:token "DAI" :to "0xbob1" :amount 1000
                              :custom-resolver "0xR0"}}
                    {:seq 2 :time 1060 :agent "alice0" :action "raise_dispute"
                     :params {:workflow-id 0}}
                    {:seq 3 :time 1070 :agent "alice1" :action "raise_dispute"
                     :params {:workflow-id 1}}
                    ;; Both resolved by the same resolver at the same timestamp
                    {:seq 4 :time 1120 :agent "resolv0" :action "execute_resolution"
                     :params {:workflow-id 0 :is-release true :resolution-hash "0x1"}}
                    {:seq 5 :time 1120 :agent "resolv0" :action "execute_resolution"
                     :params {:workflow-id 1 :is-release true :resolution-hash "0x2"}}]))
          e4 (event-by-seq result 4)
          e5 (event-by-seq result 5)]
      (is (= :fail (:outcome result)) "scenario fails: open entity left by conflict")
      (is (= :open-entities-at-end (:halt-reason result)))
      (is (= :ok (:result e4)) "first resolution accepted")
      (is (= :rejected (:result e5)) "second resolution rejected (same resolver)")
      (is (= :batch-conflict (:error e5)))
      (is (= [:resolver "0xR0"] (:conflict-domain e5)))
      (is (= 1 (:batch-conflicts (:metrics result)))))))

;; ---------------------------------------------------------------------------
;; 4. Three-event bucket — all different workflows, different resolvers
;; ---------------------------------------------------------------------------

(deftest resolver-capacity-batch-less-restrictive-than-seq
  (testing "Batch mode with same resolver: batch-conflict on [:resolver r] preempts
            capacity guard.  Only 1 raise_dispute per resolver per batch succeeds,
            even when max-concurrent=2 would allow 2 in sequential mode."
    (let [result (sew/replay-with-sew-protocol
                  (make-scenario
                   [{:seq 0 :time 1000 :agent "resolv0" :action "set_resolver_capacity"
                     :params {:max-concurrent 2}}
                    {:seq 1 :time 1000 :agent "alice0" :action "create_escrow"
                     :params {:token "USDC" :to "0xbob0" :amount 2000 :custom-resolver "0xR0"}}
                    {:seq 2 :time 1010 :agent "alice1" :action "create_escrow"
                     :params {:token "DAI" :to "0xbob1" :amount 3000 :custom-resolver "0xR0"}}
                    {:seq 3 :time 1020 :agent "alice0" :action "create_escrow"
                     :params {:token "USDC" :to "0xbob0" :amount 2500 :custom-resolver "0xR0"}}
                    ;; All three raise_dispute at the same timestamp
                    {:seq 4 :time 1060 :agent "alice0" :action "raise_dispute"
                     :params {:workflow-id 0}}
                    {:seq 5 :time 1060 :agent "alice1" :action "raise_dispute"
                     :params {:workflow-id 1}}
                    {:seq 6 :time 1060 :agent "alice0" :action "raise_dispute"
                     :params {:workflow-id 2}}]))
          e4 (event-by-seq result 4)
          e5 (event-by-seq result 5)
          e6 (event-by-seq result 6)]
      (is (= :ok (:result e4)) "first raise_dispute accepted")
      (is (= :rejected (:result e5)) "second raise_dispute rejected (batch-conflict on resolver)")
      (is (= :batch-conflict (:error e5)))
      (is (= [:resolver "0xR0"] (:conflict-domain e5)))
      (is (= :rejected (:result e6)) "third raise_dispute also rejected (same resolver)")
      (is (= :batch-conflict (:error e6)))
      (is (= [:resolver "0xR0"] (:conflict-domain e6)))
      (is (= 2 (:batch-conflicts (:metrics result)))
          "two batch conflicts in the raise-dispute bucket"))))

(deftest three-resolutions-all-accepted
  (testing "Three execute-resolution for different workflows with different resolvers
            and different tokens: all accepted (no shared domains)"
    (let [result (sew/replay-with-sew-protocol
                  (make-scenario
                   [{:seq 0 :time 1000 :agent "alice0" :action "create_escrow"
                     :params {:token "USDC" :to "0xbob0" :amount 1000
                              :custom-resolver "0xR0"}}
                    {:seq 1 :time 1010 :agent "alice1" :action "create_escrow"
                     :params {:token "DAI" :to "0xbob1" :amount 1000
                              :custom-resolver "0xR1"}}
                    {:seq 2 :time 1020 :agent "alice2" :action "create_escrow"
                     :params {:token "EURC" :to "0xbob2" :amount 1000
                              :custom-resolver "0xR2"}}
                    {:seq 3 :time 1060 :agent "alice0" :action "raise_dispute"
                     :params {:workflow-id 0}}
                    {:seq 4 :time 1070 :agent "alice1" :action "raise_dispute"
                     :params {:workflow-id 1}}
                    {:seq 5 :time 1080 :agent "alice2" :action "raise_dispute"
                     :params {:workflow-id 2}}
                    ;; All three resolved at the same timestamp
                    {:seq 6 :time 1120 :agent "resolv0" :action "execute_resolution"
                     :params {:workflow-id 0 :is-release true :resolution-hash "0x1"}}
                    {:seq 7 :time 1120 :agent "resolv1" :action "execute_resolution"
                     :params {:workflow-id 1 :is-release true :resolution-hash "0x2"}}
                    {:seq 8 :time 1120 :agent "resolv2" :action "execute_resolution"
                     :params {:workflow-id 2 :is-release true :resolution-hash "0x3"}}]))
          e6 (event-by-seq result 6)
          e7 (event-by-seq result 7)
          e8 (event-by-seq result 8)]
      (is (= :pass (:outcome result)))
      (is (= :ok (:result e6)) "resolution for wf0 accepted")
      (is (= :ok (:result e7)) "resolution for wf1 accepted")
      (is (= :ok (:result e8)) "resolution for wf2 accepted")
      (is (zero? (:batch-conflicts (:metrics result))) "no conflicts"))))
