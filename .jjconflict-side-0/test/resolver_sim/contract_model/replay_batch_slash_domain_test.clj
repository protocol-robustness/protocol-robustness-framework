(ns resolver-sim.contract-model.replay-batch-slash-domain-test
  "Batch conflict tests using mock domains that mirror the slash governance pipeline.
   
   Tests the batch conflict detection logic for RESOLVER and WORKFLOW domain
   interactions that occur in appeal/slash scenarios, without requiring the full
   Sew protocol pipeline (which fails at protocol invariants).
   
   Domain patterns tested (from sew-event-conflict-domains):
     propose-fraud-slash  → #{[:resolver r] [:workflow wf]}
     appeal-slash         → #{[:resolver r] [:workflow wf]}
     resolve-appeal       → #{[:resolver r] [:workflow wf]}
     execute-fraud-slash  → #{[:resolver r] [:workflow wf]}
     withdraw-stake       → #{[:resolver r]}
     register-stake       → #{[:resolver r]}
     execute-resolution   → #{[:workflow wf] [:resolver r] [:token t]}"
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.protocols.protocol :as proto]))

(defrecord SlashDomainProtocol []
  proto/SimulationAdapter
  (protocol-id [_] "slash-domain-test")
  (init-world [_ _] {:block-time 1000 :applied []})
  (build-execution-context [_ agents _] {:agent-index (into {} (map (juxt :id identity) agents))})
  (dispatch-action [_ _ world event]
    {:ok true :world (update world :applied conj (:seq event))})
  (check-invariants-single [_ _] {:ok? true})
  (check-invariants-transition [_ _ _] {:ok? true})
  (world-snapshot [_ world] (select-keys world [:block-time :applied]))
  (available-actions [_ _ _] [])
  (resolve-id-alias [_ event _] {:ok true :event event})
  (created-id [_ _ _] nil)
  (open-entities [_ _] [])
  (project-state [_ _ _] nil)

  proto/BatchConflictModel
  (event-conflict-domains [_ _ event _]
    (set (get-in event [:params :domains] #{[:global :unknown]}))))

(def ^:private protocol (->SlashDomainProtocol))

(defn- make-scenario
  [events]
  {:schema-version "1.1"
   :id "slash-domain-test"
   :scenario-id "slash-domain-test"
   :title "Slash domain batch test"
   :scenario-author "@test"
   :purpose :regression
   :agents [{:id "a" :address "0xa" :role "actor"}
            {:id "b" :address "0xb" :role "actor"}]
   :execution-mode :deterministic-batch
   :events (map-indexed (fn [i e] (assoc e :seq i)) events)
   :theory {:claim-id :test/slash-domain
            :assumptions []
            :falsifies-if [{:metric :reverts :op :>= :value 0}]}})

(defn- event-by-seq [result seq-num]
  (some #(when (= seq-num (:seq %)) %) (:trace result)))

;; ---------------------------------------------------------------------------
;; A. Appeal-slash + withdraw-stake — same resolver
;;    Both #{[:resolver "0xR0"]} → CONFLICT
;; ---------------------------------------------------------------------------

(deftest appeal-slash-plus-withdraw-same-resolver
  (testing "Appeal-slash followed by withdraw-stake for the same resolver
            at the same timestamp: second rejected on [:resolver r]"
    (let [result (replay/replay-with-protocol
                  protocol
                  (make-scenario
                   [{:time 1000 :agent "a" :action "touch"
                     :params {:domains #{[:resolver "0xR0"]}}}
                    {:time 1000 :agent "a" :action "touch"
                     :params {:domains #{[:resolver "0xR0"]}}}]))
          e1 (event-by-seq result 1)]
      (is (= :rejected (:result e1)))
      (is (= :batch-conflict (:error e1)))
      (is (= [:resolver "0xR0"] (:conflict-domain e1)))
      (is (= 1 (:batch-conflicts (:metrics result)))))))

;; ---------------------------------------------------------------------------
;; B. Appeal-slash R0 + withdraw-stake R1 — different resolvers
;;    #{[:resolver "0xR0"]} vs #{[:resolver "0xR1"]} → NO CONFLICT
;; ---------------------------------------------------------------------------

(deftest appeal-slash-plus-withdraw-different-resolvers
  (testing "Appeal-slash for R0 and withdraw-stake for R1 at the same
            timestamp: both accepted"
    (let [result (replay/replay-with-protocol
                  protocol
                  (make-scenario
                   [{:time 1000 :agent "a" :action "touch"
                     :params {:domains #{[:resolver "0xR0"]}}}
                    {:time 1000 :agent "b" :action "touch"
                     :params {:domains #{[:resolver "0xR1"]}}}]))
          e0 (event-by-seq result 0)
          e1 (event-by-seq result 1)]
      (is (= :ok (:result e0)))
      (is (= :ok (:result e1)))
      (is (zero? (:batch-conflicts (:metrics result)))))))

;; ---------------------------------------------------------------------------
;; C. Propose-fraud-slash + appeal-slash — same workflow + resolver
;;    #{[:resolver "0xR0"] [:workflow 0]} — identical → CONFLICT
;; ---------------------------------------------------------------------------

(deftest propose-and-appeal-slash-same-workflow-resolver
  (testing "Propose-fraud-slash and appeal-slash for the same workflow
            and resolver at the same timestamp: second rejected"
    (let [result (replay/replay-with-protocol
                  protocol
                  (make-scenario
                   [{:time 1000 :agent "a" :action "touch"
                     :params {:domains #{[:resolver "0xR0"] [:workflow 0]}}}
                    {:time 1000 :agent "b" :action "touch"
                     :params {:domains #{[:resolver "0xR0"] [:workflow 0]}}}]))
          e1 (event-by-seq result 1)]
      (is (= :rejected (:result e1)))
      (is (= :batch-conflict (:error e1)))
      (is (contains? #{[:resolver "0xR0"] [:workflow 0]} (:conflict-domain e1)))
      (is (= 1 (:batch-conflicts (:metrics result)))))))

;; ---------------------------------------------------------------------------
;; D. Resolve-appeal + execute-fraud-slash — same workflow + resolver
;;    Identical domains to C → CONFLICT
;; ---------------------------------------------------------------------------

(deftest resolve-appeal-and-execute-slash-conflict
  (testing "Resolve-appeal and execute-fraud-slash for the same workflow
            and resolver at the same timestamp: second rejected"
    (let [result (replay/replay-with-protocol
                  protocol
                  (make-scenario
                   [{:time 1000 :agent "a" :action "touch"
                     :params {:domains #{[:workflow 0] [:resolver "0xR0"]}}}
                    {:time 1000 :agent "b" :action "touch"
                     :params {:domains #{[:workflow 0] [:resolver "0xR0"]}}}]))
          e1 (event-by-seq result 1)]
      (is (= :rejected (:result e1)))
      (is (= :batch-conflict (:error e1))))))

;; ---------------------------------------------------------------------------
;; E. Two appeal-slashes — DIFFERENT resolvers
;;    #{[:resolver "0xR0"]} vs #{[:resolver "0xR1"]} → NO CONFLICT
;; ---------------------------------------------------------------------------

(deftest two-appeal-slashes-different-resolvers
  (testing "Two appeal-slashes for different resolvers at the same timestamp:
            both accepted"
    (let [result (replay/replay-with-protocol
                  protocol
                  (make-scenario
                   [{:time 1000 :agent "a" :action "touch"
                     :params {:domains #{[:resolver "0xR0"] [:workflow 0]}}}
                    {:time 1000 :agent "b" :action "touch"
                     :params {:domains #{[:resolver "0xR1"] [:workflow 1]}}}]))
          e0 (event-by-seq result 0)
          e1 (event-by-seq result 1)]
      (is (= :ok (:result e0)))
      (is (= :ok (:result e1)))
      (is (zero? (:batch-conflicts (:metrics result)))))))

;; ---------------------------------------------------------------------------
;; F. Two appeal-slashes — SAME resolver
;;    #{[:resolver "0xR0"]} → CONFLICT
;; ---------------------------------------------------------------------------

(deftest two-appeal-slashes-same-resolver
  (testing "Two appeal-slashes for the same resolver at the same timestamp:
            second rejected on [:resolver r]"
    (let [result (replay/replay-with-protocol
                  protocol
                  (make-scenario
                   [{:time 1000 :agent "a" :action "touch"
                     :params {:domains #{[:resolver "0xR0"] [:workflow 0]}}}
                    {:time 1000 :agent "b" :action "touch"
                     :params {:domains #{[:resolver "0xR0"] [:workflow 1]}}}]))
          e1 (event-by-seq result 1)]
      (is (= :rejected (:result e1)))
      (is (= :batch-conflict (:error e1)))
      (is (= 0 (:conflict-with-seq e1)))
      (is (= [:resolver "0xR0"] (:conflict-domain e1))))))

;; ---------------------------------------------------------------------------
;; G. Three-event combat: propose + appeal + withdraw — all same resolver
;;    Seq 0 accepted, seq 1 conflicts with seq 0, seq 2 also conflicts
;;    with seq 0 (since seq 1 didn't claim [:resolver "0xR0"])
;; ---------------------------------------------------------------------------

(deftest three-event-slash-chain-same-resolver
  (testing "Three events for the same resolver at the same timestamp:
            first accepted, second and third rejected"
    (let [result (replay/replay-with-protocol
                  protocol
                  (make-scenario
                   [{:time 1000 :agent "a" :action "touch"
                     :params {:domains #{[:resolver "0xR0"] [:workflow 0]}}}
                    {:time 1000 :agent "b" :action "touch"
                     :params {:domains #{[:resolver "0xR0"]}}}
                    {:time 1000 :agent "b" :action "touch"
                     :params {:domains #{[:resolver "0xR0"] [:workflow 2]}}}]))
          e0 (event-by-seq result 0)
          e1 (event-by-seq result 1)
          e2 (event-by-seq result 2)]
      (is (= :ok (:result e0)) "propose accepted")
      (is (= :rejected (:result e1)) "appeal rejected — conflicts with propose")
      (is (= :batch-conflict (:error e1)))
      (is (= [:resolver "0xR0"] (:conflict-domain e1)))
      (is (= 0 (:conflict-with-seq e1)))
      ;; Second rejected event also conflicts with seq 0 (same resolver)
      (is (= :rejected (:result e2)) "withdraw rejected — same resolver")
      (is (= [:resolver "0xR0"] (:conflict-domain e2)))
      (is (= 0 (:conflict-with-seq e2)))
      (is (= 2 (:batch-conflicts (:metrics result)))
          "two conflicts in the three-event bucket"))))

;; ---------------------------------------------------------------------------
;; H. Cross-domain isolation: slash event + unrelated workflow event
;;    #{[:resolver "0xR0"] [:workflow 0]} vs #{[:workflow 1] [:resolver "0xR1"]}
;;    → NO CONFLICT (different resolver AND workflow)
;; ---------------------------------------------------------------------------

(deftest slash-and-resolution-different-domains
  (testing "A slash event and a resolution for different workflows and
            resolvers at the same timestamp: both accepted"
    (let [result (replay/replay-with-protocol
                  protocol
                  (make-scenario
                   [{:time 1000 :agent "a" :action "touch"
                     :params {:domains #{[:resolver "0xR0"] [:workflow 0]}}}
                    {:time 1000 :agent "b" :action "touch"
                     :params {:domains #{[:resolver "0xR1"] [:workflow 1]
                                         [:token :DAI]}}}]))
          e0 (event-by-seq result 0)
          e1 (event-by-seq result 1)]
      (is (= :ok (:result e0)))
      (is (= :ok (:result e1)))
      (is (zero? (:batch-conflicts (:metrics result)))))))

;; ---------------------------------------------------------------------------
;; I. Same resolver, different slash targets (different workflow-ids)
;;    Both share [:resolver "0xR0"] → CONFLICT despite different [:workflow]s
;; ---------------------------------------------------------------------------

(deftest same-resolver-different-workflow-both-conflict
  (testing "Two slash events for the same resolver but different workflows:
            second rejected on shared [:resolver r]"
    (let [result (replay/replay-with-protocol
                  protocol
                  (make-scenario
                   [{:time 1000 :agent "a" :action "touch"
                     :params {:domains #{[:resolver "0xR0"] [:workflow 0]}}}
                    {:time 1000 :agent "b" :action "touch"
                     :params {:domains #{[:resolver "0xR0"] [:workflow 1]}}}]))
          e1 (event-by-seq result 1)]
      (is (= :rejected (:result e1)))
      (is (= [:resolver "0xR0"] (:conflict-domain e1))))))

;; ---------------------------------------------------------------------------
;; J. Cross-event domain: slash workflow domain overlaps with resolution
;;    #{[:workflow 0]} in both → CONFLICT despite different resolvers
;; ---------------------------------------------------------------------------

(deftest slash-and-resolution-same-workflow-different-resolvers
  (testing "A slash and a resolution for the same workflow (different resolvers):
            conflict on shared [:workflow 0] domain"
    (let [result (replay/replay-with-protocol
                  protocol
                  (make-scenario
                   [{:time 1000 :agent "a" :action "touch"
                     :params {:domains #{[:resolver "0xR0"] [:workflow 0]}}}
                    {:time 1000 :agent "b" :action "touch"
                     :params {:domains #{[:resolver "0xR1"] [:workflow 0]}}}]))
          e1 (event-by-seq result 1)]
      (is (= :rejected (:result e1)))
      (is (= :batch-conflict (:error e1)))
      (is (= [:workflow 0] (:conflict-domain e1))))))

;; ---------------------------------------------------------------------------
;; K. Three-event: different resolvers, same workflow
;;    R0 slash, R1 slash, R2 resolution — shared [:workflow 0]
;;    Seq 0 accepted, seq 1 conflicts on [:workflow 0], seq 2 also on [:workflow 0]
;; ---------------------------------------------------------------------------

(deftest three-events-same-workflow-mixed-resolvers
  (testing "Three events for the same workflow but different resolvers:
            first accepted, second and third conflict on [:workflow 0]"
    (let [result (replay/replay-with-protocol
                  protocol
                  (make-scenario
                   [{:time 1000 :agent "a" :action "touch"
                     :params {:domains #{[:resolver "0xR0"] [:workflow 0]}}}
                    {:time 1000 :agent "b" :action "touch"
                     :params {:domains #{[:resolver "0xR1"] [:workflow 0]}}}
                    {:time 1000 :agent "a" :action "touch"
                     :params {:domains #{[:resolver "0xR2"] [:workflow 0]}}}]))
          e1 (event-by-seq result 1)
          e2 (event-by-seq result 2)]
      (is (= :rejected (:result e1)) "second conflicts on workflow")
      (is (= [:workflow 0] (:conflict-domain e1)))
      (is (= 0 (:conflict-with-seq e1)))
      (is (= :rejected (:result e2)) "third also conflicts on workflow")
      (is (= [:workflow 0] (:conflict-domain e2)))
      (is (= 0 (:conflict-with-seq e2)))
      (is (= 2 (:batch-conflicts (:metrics result)))))))

;; ---------------------------------------------------------------------------
;; L. Multi-agent same-workflow: buyer raises_dispute + seller releases
;;    Both on [:workflow 0] → CONFLICT (different agents, same workflow)
;; ---------------------------------------------------------------------------

(deftest multi-agent-same-workflow-conflict
  (testing "Two agents acting on the same workflow at the same timestamp:
            second rejected on [:workflow] domain"
    (let [result (replay/replay-with-protocol
                  protocol
                  (make-scenario
                   [{:time 1000 :agent "a" :action "touch"
                     :params {:domains #{[:workflow 0]}}}
                    {:time 1000 :agent "b" :action "touch"
                     :params {:domains #{[:workflow 0]}}}]))
          e1 (event-by-seq result 1)]
      (is (= :rejected (:result e1)))
      (is (= :batch-conflict (:error e1)))
      (is (= [:workflow 0] (:conflict-domain e1))))))

;; ---------------------------------------------------------------------------
;; M. Multi-agent same-token: two agents creating escrows with USDC
;;    Both on [:token :USDC] → CONFLICT (different workflows, same token)
;; ---------------------------------------------------------------------------

(deftest multi-agent-same-token-conflict
  (testing "Two agents creating escrows with the same token at the same
            timestamp: second rejected on [:token t] domain"
    (let [result (replay/replay-with-protocol
                  protocol
                  (make-scenario
                   [{:time 1000 :agent "a" :action "touch"
                     :params {:domains #{[:workflow 0] [:token :USDC]}}}
                    {:time 1000 :agent "b" :action "touch"
                     :params {:domains #{[:workflow 1] [:token :USDC]}}}]))
          e1 (event-by-seq result 1)]
      (is (= :rejected (:result e1)))
      (is (= :batch-conflict (:error e1)))
      (is (= [:token :USDC] (:conflict-domain e1))))))

;; ---------------------------------------------------------------------------
;; N. Multi-agent different-tokens-no-conflict: USDC + DAI
;;    Different [:token t] domains → NO CONFLICT
;; ---------------------------------------------------------------------------

(deftest multi-agent-different-tokens-no-conflict
  (testing "Two agents creating escrows with different tokens at the same
            timestamp: both accepted"
    (let [result (replay/replay-with-protocol
                  protocol
                  (make-scenario
                   [{:time 1000 :agent "a" :action "touch"
                     :params {:domains #{[:workflow 0] [:token :USDC]}}}
                    {:time 1000 :agent "b" :action "touch"
                     :params {:domains #{[:workflow 1] [:token :DAI]}}}]))
          e0 (event-by-seq result 0)
          e1 (event-by-seq result 1)]
      (is (= :ok (:result e0)))
      (is (= :ok (:result e1)))
      (is (zero? (:batch-conflicts (:metrics result)))))))

;; ---------------------------------------------------------------------------
;; O. Governance + slash-target: gov proposes slash for R0, R0 withdraws stake
;;    Both on [:resolver "0xR0"] → CONFLICT (different agents, same resolver target)
;; ---------------------------------------------------------------------------

(deftest governance-plus-resolver-same-target-conflict
  (testing "Governance proposes slash for resolver R0 while R0 withdraws stake:
            second rejected on [:resolver r] domain"
    (let [result (replay/replay-with-protocol
                  protocol
                  (make-scenario
                   [{:time 1000 :agent "a" :action "touch"
                     :params {:domains #{[:resolver "0xR0"] [:workflow 0]}}}
                    {:time 1000 :agent "b" :action "touch"
                     :params {:domains #{[:resolver "0xR0"]}}}]))
          e1 (event-by-seq result 1)]
      (is (= :rejected (:result e1)))
      (is (= :batch-conflict (:error e1)))
      (is (= [:resolver "0xR0"] (:conflict-domain e1))))))

;; ---------------------------------------------------------------------------
;; P. Governance + different resolver: gov proposes slash for R0, R1 withdraws
;;    Different [:resolver r] → NO CONFLICT
;; ---------------------------------------------------------------------------

(deftest governance-plus-different-resolver-no-conflict
  (testing "Governance proposes slash for R0 while R1 withdraws stake:
            both accepted (different resolver domains)"
    (let [result (replay/replay-with-protocol
                  protocol
                  (make-scenario
                   [{:time 1000 :agent "a" :action "touch"
                     :params {:domains #{[:resolver "0xR0"] [:workflow 0]}}}
                    {:time 1000 :agent "b" :action "touch"
                     :params {:domains #{[:resolver "0xR1"]}}}]))
          e0 (event-by-seq result 0)
          e1 (event-by-seq result 1)]
      (is (= :ok (:result e0)))
      (is (= :ok (:result e1)))
      (is (zero? (:batch-conflicts (:metrics result)))))))

;; ---------------------------------------------------------------------------
;; Q. Three-agent, three different workflows, different tokens
;;    All domains distinct → NO CONFLICT (full parallel throughput)
;; ---------------------------------------------------------------------------

(deftest three-agents-fully-isolated-no-conflict
  (testing "Three agents on three different workflows with different tokens:
            all accepted"
    (let [result (replay/replay-with-protocol
                  protocol
                  (make-scenario
                   [{:time 1000 :agent "a" :action "touch"
                     :params {:domains #{[:workflow 0] [:resolver "0xR0"] [:token :USDC]}}}
                    {:time 1000 :agent "b" :action "touch"
                     :params {:domains #{[:workflow 1] [:resolver "0xR1"] [:token :DAI]}}}
                    {:time 1000 :agent "a" :action "touch"
                     :params {:domains #{[:workflow 2] [:resolver "0xR2"] [:token :EURC]}}}]))
          e0 (event-by-seq result 0)
          e1 (event-by-seq result 1)
          e2 (event-by-seq result 2)]
      (is (= :ok (:result e0)))
      (is (= :ok (:result e1)))
      (is (= :ok (:result e2)))
      (is (zero? (:batch-conflicts (:metrics result)))))))
