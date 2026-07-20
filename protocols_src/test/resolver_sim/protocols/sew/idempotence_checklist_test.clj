(ns resolver-sim.protocols.sew.idempotence-checklist-test
  (:require [resolver-sim.protocols.sew.snapshot-fixtures :as snap-fix]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.protocols.sew.lifecycle :as lc]
            [resolver-sim.protocols.sew.resolution :as res]
            [resolver-sim.protocols.sew.accounting :as acct]
            [resolver-sim.protocols.sew.registry :as reg]
            [resolver-sim.protocols.sew.compat :as compat]
            [resolver-sim.contract-model.replay      :as replay]
            [resolver-sim.contract-model.idempotency :as idem]
            [resolver-sim.protocols.protocol         :as proto]
            [resolver-sim.protocols.sew              :as sew]
            [resolver-sim.time.context               :as time-ctx]))

(def alice "0xAlice")
(def bob "0xBob")
(def resolver "0xResolver")
(def usdc "0xUSDC")

(defn- base-world
  [appeal-window-duration]
  (let [snap (snap-fix/escrow-snapshot {:escrow-fee-bps 50
                                        :max-dispute-duration 3600
                                        :appeal-window-duration appeal-window-duration})
        r    (lc/create-escrow (t/empty-world 1000) alice usdc bob 1000
                               (t/make-escrow-settings {}) snap)
        w    (:world r)]
    (-> w
        (assoc-in [:escrow-transfers 0 :escrow-state] :disputed)
        (assoc-in [:escrow-transfers 0 :sender-status] :raise-dispute)
        (assoc-in [:escrow-transfers 0 :dispute-resolver] resolver)
        (assoc-in [:dispute-timestamps 0] 1000))))

(deftest checklist-clear-claimable-v2-kind-idempotent
  (let [w0 (-> (t/empty-world 1000)
               (assoc-in [:claimable-v2 0 :settlement/principal bob] 100)
               (assoc-in [:claimable 0 bob] 100))
        w1 (acct/clear-claimable-v2-kind w0 0 :settlement/principal)
        w2 (acct/clear-claimable-v2-kind w1 0 :settlement/principal)]
    (is (nil? (get-in w1 [:claimable-v2 0 :settlement/principal bob])))
    (is (nil? (get-in w2 [:claimable-v2 0 :settlement/principal bob])))
    (is (nil? (get-in w2 [:claimable-v2 0 :settlement/principal]))
        "clear helper must not synthesize nil claimant keys")
    (is (empty? (get-in w2 [:claimable 0] {}))
        "legacy principal mirror remains clear after repeated cleanup")))

(deftest checklist-unfreeze-resolver-idempotent
  (let [w0 (-> (t/empty-world 1000)
               (assoc-in [:resolver-frozen-until resolver] 2000)
               (update :resolver-unavailable conj resolver))
        w1 (:world (res/unfreeze-resolver w0 resolver))
        w2 (:world (res/unfreeze-resolver w1 resolver))]
    (is (= 0 (get-in w1 [:resolver-frozen-until resolver])))
    (is (= 0 (get-in w2 [:resolver-frozen-until resolver])))
    (is (not (contains? (:resolver-unavailable w2) resolver)))))

(deftest checklist-execute-fraud-slash-single-execution
  (let [w0 (-> (t/empty-world 1000)
         (assoc-in [:resolver-stakes resolver] 5000)
         (assoc-in [:escrow-transfers 0] {:token usdc})
         (t/insert-slash
          {:slash/id 0
           :slash/workflow-id 0
           :slash/kind :fraud
           :slash/level 0
           :resolver resolver
           :amount 100
           :reason :fraud
           :status :pending
           :proposed-at 900
           :appeal-deadline 999
           :appeal-bond-held 0
           :contest-deadline 0}))
        r1 (res/execute-fraud-slash w0 0)
        r2 (res/execute-fraud-slash (:world r1) 0)]
    (is (:ok r1))
    (is (false? (:ok r2)))
    (is (= :already-executed (:error r2)))))

(deftest checklist-execute-pending-settlement-single-finalization
  (let [w0 (-> (base-world 120)
               (time-ctx/advance-time {:to 1240})
               (assoc-in [:pending-settlements 0]
                         (t/make-pending-settlement {:exists true
                                                     :is-release true
                                                     :appeal-deadline 1240
                                                     :resolution-hash "0xhash"})))
        r1 (res/execute-pending-settlement w0 0)
        r2 (res/execute-pending-settlement (:world r1) 0)]
    (is (:ok r1))
    (is (= :released (t/escrow-state (:world r1) 0)))
    (is (false? (:ok r2)))
    (is (= :no-pending-settlement (:error r2)))))

(deftest checklist-force-reversal-slash-idempotent
  ;; Simpler guard-only test: verify that pre-populating :slash-by-context
  ;; causes force-reversal-slash to short-circuit without mutating stake.
  ;; Full slash-accounting test lives in slashing_test.clj:873.
  (let [res-addr "0xChecklistResolver"
        snap (snap-fix/escrow-snapshot
              {:reversal-slash-bps 2500 :appeal-window-duration 120})
        w0 (-> (t/empty-world 1000)
               (assoc-in [:resolver-stakes res-addr] 10000))
        {:keys [world workflow-id]}
        (lc/create-escrow w0 alice usdc bob 1000
                          (t/make-escrow-settings {}) snap)
        ;; Pre-populate :slash-by-context to simulate an existing force-reversal
        w-primed (assoc-in world [:slash-by-context [workflow-id :force-reversal 0]] 42)
        w-result (res/force-reversal-slash w-primed workflow-id :slash-bps 2500 :track :immediate)]
    (is (= w-primed w-result) "world returned unchanged when guard fires")
    (is (= 10000 (get-in w-result [:resolver-stakes res-addr]))
        "stake not debited — guard short-circuited before slash logic")))

(deftest checklist-superseded-pending-single-finalization
  (let [w0 (-> (base-world 120)
               (time-ctx/advance-time {:to 1300})
               (assoc-in [:pending-settlements 0]
                         (t/make-pending-settlement {:exists true
                                                     :is-release true
                                                     :appeal-deadline 1300
                                                     :resolution-hash "0xhash"})))
        ;; Archive the active pending (simulating escalation), then clear it
        w1 (-> w0
               (update :pending-settlements dissoc 0)
               (update-in [:superseded-pending-settlements 0]
                          (fnil conj [])
                          {:pending (t/make-pending-settlement {:exists true
                                                                :is-release true
                                                                :appeal-deadline 1300
                                                                :resolution-hash "0xhash"})
                           :superseded-at 1180
                           :level 0}))
        ;; First execute — should succeed via superseded fallback
        r1 (res/execute-pending-settlement w1 0)
        ;; Second execute — should fail, escrow now terminal
        r2 (res/execute-pending-settlement (:world r1) 0)]
    (is (:ok r1))
    (is (= :released (t/escrow-state (:world r1) 0)))
    (is (false? (:ok r2)))
    (is (= :transfer-not-in-dispute (:error r2)))))

(deftest checklist-cross-layer-idempotence
  (testing "replay-boundary dedup fires before business-logic guard"
    (let [agents [{:id "buyer" :address "0xBuyer" :type "honest"}
                  {:id "seller" :address "0xSeller" :type "honest"}
                  {:id "resolver" :address "0xResolver" :type "resolver"}]
          ctx (proto/build-execution-context
               sew/protocol agents
               {:resolver-fee-bps 50 :appeal-window-duration 200})
          w0 (t/empty-world 1000)
          s1 (replay/process-step sew/protocol ctx w0
                                  {:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
                                   :params {:token "0xUSDC" :to "0xSeller" :amount 1000
                                            :custom-resolver "0xResolver"}})
          s2 (replay/process-step sew/protocol ctx (:world s1)
                                  {:seq 1 :time 1010 :agent "buyer" :action "raise_dispute"
                                   :params {:workflow-id 0}})
          s3 (replay/process-step sew/protocol ctx (:world s2)
                                  {:seq 2 :time 1020 :agent "resolver" :action "execute_resolution"
                                   :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}})
          w-with-pending (:world s3)
          ;; Advance time past appeal deadline so settlement is executable
          w-ready (time-ctx/advance-time w-with-pending {:to 1300})
          event {:seq 3 :time 1300 :agent "buyer" :action "execute_pending_settlement"
                 :params {:workflow-id 0}}
          event-with-id (assoc-in event [:params :event-id] "evt-settle-1")
          ;; First call with event-id — business guard passes
          r1 (replay/process-step sew/protocol ctx w-ready event-with-id)
          ;; Second call with same event-id — replay-boundary dedup fires
          r2 (replay/process-step sew/protocol ctx (:world r1) event-with-id)
          ;; Third call without event-id — business-logic guard rejects
          r3 (replay/process-step sew/protocol ctx (:world r2) event)]
      (is (= :ok (get-in r1 [:trace-entry :result])) "first call succeeds")
      (is (= :released (t/escrow-state (:world r1) 0)) "escrow released")
      (is (= :ok (get-in r2 [:trace-entry :result])) "duplicate with same event-id returns :ok")
      (is (= :no-op-duplicate (get-in r2 [:trace-entry :extra :idempotency]))
          "replay-boundary dedup returns :no-op-duplicate, not business-logic error")
      (is (= :released (t/escrow-state (:world r2) 0))
          "world unchanged after dedup — escrow still released")
      (is (not= :ok (get-in r3 [:trace-entry :result])) "third call without event-id fails")
      (is (= :no-pending-settlement (get-in r3 [:trace-entry :error]))
          "business-logic guard rejects after settlement consumed"))))

(deftest checklist-force-reversal-slash-replay-dedup
  (testing "force-reversal-slash is deduplicated when dispatched through dispatch-action"
    (let [gov-addr "0xGov"
          res-addr "0xResolver"
          l1-resolver "0xResolver1"
          agents [{:id "buyer" :address "0xBuyer" :type "honest"}
                  {:id "seller" :address "0xSeller" :type "honest"}
                  {:id "resolver" :address res-addr :type "resolver"}
                  {:id "gov" :address gov-addr :role "governance"}]
          snap (snap-fix/escrow-snapshot
                {:reversal-slash-bps 2500 :appeal-window-duration 200})
          ctx (proto/build-execution-context
               sew/protocol agents
               {:resolver-fee-bps 50 :appeal-window-duration 200
                :escalation-resolvers {:1 l1-resolver}})
          w0 (-> (t/empty-world 1000)
                 (reg/register-stake res-addr 10000))
          s0 (replay/process-step sew/protocol ctx w0
                                  {:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
                                   :params {:token "0xUSDC" :to "0xSeller" :amount 5000
                                            :custom-resolver res-addr}})
          s1 (replay/process-step sew/protocol ctx (:world s0)
                                  {:seq 1 :time 1010 :agent "buyer" :action "raise_dispute"
                                   :params {:workflow-id 0}})
          s2 (replay/process-step sew/protocol ctx (:world s1)
                                  {:seq 2 :time 1020 :agent "resolver" :action "execute_resolution"
                                   :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}})
          s3 (replay/process-step sew/protocol ctx (:world s2)
                                  {:seq 3 :time 1030 :agent "buyer" :action "escalate_dispute"
                                   :params {:workflow-id 0}})
          w-ready (:world s3)
          force-ev {:seq 4 :time 1040 :agent "gov" :action "force_reversal_slash"
                    :params {:workflow-id 0 :slash-bps 2500 :event-id "evt-force-1"}}
          r1 (proto/dispatch-action sew/protocol ctx w-ready force-ev)
          r2 (proto/dispatch-action sew/protocol ctx (:world r1) force-ev)]
      (is (:ok r1) "first force-reversal-slash succeeds")
      (let [slash-id (get-in (:world r1) [:slash-by-context [0 :force-reversal 0]])]
        (is (some? slash-id) "slash entry created after first apply")
        (is (some? (get-in (:world r1) [:pending-fraud-slashes slash-id]))
            "slash entry stored in pending-fraud-slashes"))
      (is (:ok r2) "duplicate returns truthy :ok")
      (is (= :no-op-duplicate (get-in r2 [:extra :idempotency]))
          "replay dedup fires, not business-logic guard")
      (is (= (:world r1) (:world r2))
          "world unchanged after duplicate — no second slash entry created"))))

(deftest checklist-apply-once-retry-after-failure
  (testing "apply-once: failed attempt does not consume key; retry may succeed; further duplicates rejected"
    (let [w0 (t/empty-world 1000)
          op-key [:test :dedupe "test-action" "agent0" 0 0 nil "evt-retry"]
          attempt (atom 0)
          apply-fn (fn [w]
                     (swap! attempt inc)
                     (if (= 1 @attempt)
                       {:ok false :error :simulated-failure}
                       {:ok true :world (assoc w :applied true)}))
          r1 (idem/apply-once w0 op-key apply-fn)]
      (is (false? (:ok r1)) "first attempt fails")
      (is (= :simulated-failure (:error r1)) "first attempt returns the failure error")
      (is (= :attempted-failed (get-in r1 [:extra :idempotency]))
          "first attempt tagged :attempted-failed for diagnostics")
      (is (true? (get-in r1 [:extra :retryable?]))
          "first attempt marked retryable")
      (is (nil? (get-in (:world r1) [:idempotency/applied]))
          "op-key NOT recorded after failure — retry allowed")

      ;; Retry with same key — should succeed
      (let [r2 (idem/apply-once (:world r1) op-key apply-fn)]
        (is (:ok r2) "retry succeeds")
        (is (= :applied-once (get-in r2 [:extra :idempotency]))
            "retry tagged :applied-once")
        (is (true? (get-in (:world r2) [:applied]))
            "state transition executed on retry")

        ;; Third call with same key — dedup fires
        (let [r3 (idem/apply-once (:world r2) op-key apply-fn)]
          (is (:ok r3) "third call returns :ok")
          (is (= :no-op-duplicate (get-in r3 [:extra :idempotency]))
              "third call is :no-op-duplicate")
          (is (nil? (get-in r3 [:extra :retryable?]))
              "no-op-duplicate is not retryable")
          (is (= (:world r2) (:world r3))
              "world unchanged after duplicate")))

      ;; Exactly two attempts (first + retry); no third attempt
      (is (= 2 @attempt) "apply-fn called exactly twice — once for failure, once for retry"))))

(deftest checklist-different-slash-ids-different-keys
  (testing "different slash-ids produce different dedupe keys"
    (let [gov-addr "0xGov"
          res-addr "0xResolver"
          l1-resolver "0xResolver1"
          agents [{:id "buyer" :address "0xBuyer" :type "honest"}
                  {:id "seller" :address "0xSeller" :type "honest"}
                  {:id "resolver" :address res-addr :type "resolver"}
                  {:id "gov" :address gov-addr :role "governance"}]
          snap (snap-fix/escrow-snapshot
                {:reversal-slash-bps 2500 :appeal-window-duration 200})
          ctx (proto/build-execution-context
               sew/protocol agents
               {:resolver-fee-bps 50 :appeal-window-duration 200
                :escalation-resolvers {:1 l1-resolver}})
          w0 (-> (t/empty-world 1000)
                 (reg/register-stake res-addr 10000))
          setup (fn []
                  (let [s0 (replay/process-step sew/protocol ctx w0
                                                {:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
                                                 :params {:token "0xUSDC" :to "0xSeller" :amount 5000
                                                          :custom-resolver res-addr}})
                        s1 (replay/process-step sew/protocol ctx (:world s0)
                                                {:seq 1 :time 1010 :agent "buyer" :action "raise_dispute"
                                                 :params {:workflow-id 0}})
                        s2 (replay/process-step sew/protocol ctx (:world s1)
                                                {:seq 2 :time 1020 :agent "resolver" :action "execute_resolution"
                                                 :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}})
                        s3 (replay/process-step sew/protocol ctx (:world s2)
                                                {:seq 3 :time 1030 :agent "buyer" :action "escalate_dispute"
                                                 :params {:workflow-id 0}})]
                    (:world s3)))
          w-ready (setup)
          ;; Two force-reversal events with distinct event-ids on the same workflow
          ev-a {:seq 3 :time 1030 :agent "gov" :action "force_reversal_slash"
                :params {:workflow-id 0 :slash-bps 2500 :event-id "force-a"
                         :slash-id "slash-a"}}
          ev-b {:seq 4 :time 1040 :agent "gov" :action "force_reversal_slash"
                :params {:workflow-id 0 :slash-bps 1500 :event-id "force-b"
                         :slash-id "slash-b"}}
          ra (proto/dispatch-action sew/protocol ctx w-ready ev-a)
          rb (proto/dispatch-action sew/protocol ctx (:world ra) ev-b)]
      (is (:ok ra) "first force-reversal (A) succeeds")
      (is (not= :no-op-duplicate (get-in ra [:extra :idempotency]))
          "A is not a duplicate")
      (is (:ok rb) "second force-reversal (B) also returns :ok (not deduped — different event-id)")
      (is (not= :no-op-duplicate (get-in rb [:extra :idempotency]))
          "B is NOT deduped against A — distinct event-ids produce distinct keys")
      ;; Both A and B execute dispatch-action; the business-logic guard prevents
      ;; a second force-reversal slash on the same workflow, so only one entry exists.
      (let [slash-id-a (get-in (:world ra) [:slash-by-context [0 :force-reversal 0]])]
        (is (some? slash-id-a) "slash A entry created")
        (is (some? (get-in (:world ra) [:pending-fraud-slashes slash-id-a]))
            "slash A stored in pending-fraud-slashes")
        (let [slash-id-after-b (get-in (:world rb) [:slash-by-context [0 :force-reversal 0]])]
          (is (= slash-id-a slash-id-after-b)
              "business-logic guard prevents a second force-reversal — same slash-id as A"))))))

(deftest checklist-equivalent-wire-representations
  (testing "same logical operation produces identical key across equivalent wire representations"
    (let [gov-addr "0xGov"
          res-addr "0xResolver"
          l1-resolver "0xResolver1"
          agents [{:id "buyer" :address "0xBuyer" :type "honest"}
                  {:id "seller" :address "0xSeller" :type "honest"}
                  {:id "resolver" :address res-addr :type "resolver"}
                  {:id "gov" :address gov-addr :role "governance"}]
          snap (snap-fix/escrow-snapshot
                {:reversal-slash-bps 2500 :appeal-window-duration 200})
          ctx (proto/build-execution-context
               sew/protocol agents
               {:resolver-fee-bps 50 :appeal-window-duration 200
                :escalation-resolvers {:1 l1-resolver}})
          w0 (-> (t/empty-world 1000)
                 (reg/register-stake res-addr 10000))
          setup (fn []
                  (let [s0 (replay/process-step sew/protocol ctx w0
                                                {:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
                                                 :params {:token "0xUSDC" :to "0xSeller" :amount 5000
                                                          :custom-resolver res-addr}})
                        s1 (replay/process-step sew/protocol ctx (:world s0)
                                                {:seq 1 :time 1010 :agent "buyer" :action "raise_dispute"
                                                 :params {:workflow-id 0}})
                        s2 (replay/process-step sew/protocol ctx (:world s1)
                                                {:seq 2 :time 1020 :agent "resolver" :action "execute_resolution"
                                                 :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}})
                        s3 (replay/process-step sew/protocol ctx (:world s2)
                                                {:seq 3 :time 1030 :agent "buyer" :action "escalate_dispute"
                                                 :params {:workflow-id 0}})]
                    (:world s3)))
          w-ready (setup)
          ;; String event-id
          ev-string {:seq 3 :time 1030 :agent "gov" :action "force_reversal_slash"
                     :params {:workflow-id 0 :slash-bps 2500 :event-id "evt-force-1"
                              :slash-id "slash-1"}}
          ;; Keyword event-id — compat/event-id normalizes keyword to string
          ;; slash-id remains string (consistent type for dedupe key comparison)
          ev-keyword {:seq 4 :time 1040 :agent "gov" :action "force_reversal_slash"
                      :params {:workflow-id 0 :slash-bps 2500 :event-id :evt-force-1
                               :slash-id "slash-1"}}
          r-string (proto/dispatch-action sew/protocol ctx w-ready ev-string)
          w-after-string (:world r-string)
          r-keyword (proto/dispatch-action sew/protocol ctx w-after-string ev-keyword)]
      (is (:ok r-string) "string event-id succeeds")
      (is (:ok r-keyword) "keyword event-id returns :ok")
      (is (= :no-op-duplicate (get-in r-keyword [:extra :idempotency]))
          "keyword event-id is recognized as duplicate of string event-id — normalization works")

      ;; Also verify normalizing the action name (underscore vs hyphen) via canonical-action
      (is (= "force-reversal-slash"
             (compat/canonical-action {:action "force_reversal_slash"}))
          "canonical-action normalizes underscore to hyphen")
      (is (= "force-reversal-slash"
             (compat/canonical-action {:action "force-reversal-slash"}))
          "canonical-action preserves already-canonical name"))))

(deftest checklist-backward-compat-action-alias
  (testing "backward-compatible action aliases normalize to the same canonical identity"
    (let [gov-addr "0xGov"
          agents [{:id "gov" :address gov-addr :role "governance"}]
          snap (snap-fix/escrow-snapshot
                {:reversal-slash-bps 2500 :appeal-window-duration 200
                 :max-dispute-duration 3600})
          ctx (proto/build-execution-context
               sew/protocol agents
               {:resolver-fee-bps 50 :appeal-window-duration 200})
          w0 (t/empty-world 1000)

          ;; "grant-force-authorization" (US spelling, alias) -> normalizes to
          ;; "grant-force-authorisation" (canonical, Commonwealth spelling).
          ;; Both should match the same set entry via canonical-action.
          ev-canon {:action "grant-force-authorisation"}
          ev-alias {:action "grant-force-authorization"}]
      (is (sew/replay-sensitive-action? ev-canon)
          "canonical 'grant-force-authorisation' is replay-sensitive")
      (is (sew/replay-sensitive-action? ev-alias)
          "alias 'grant-force-authorization' is replay-sensitive (same canonical identity)")
      (is (contains? sew/replay-sensitive-actions (compat/canonical-action ev-canon))
          "canonical form is a direct member of the set")
      (is (contains? sew/replay-sensitive-actions (compat/canonical-action ev-alias))
          "alias form is also a direct member of the set (backward-compat entry)")
      (is (not= (compat/canonical-action ev-alias)
                (compat/canonical-action ev-canon))
          "alias and canonical are different strings (US/UK spelling); both must be in the set"))))

(deftest checklist-key-shape-nil-hop-scope
  (testing "fixed key shape: non-hop actions contain nil in hop-scope; hop-scoped actions differ by hop"
    (let [agents [{:id "buyer" :address "0xBuyer" :type "honest"}
                  {:id "seller" :address "0xSeller" :type "honest"}
                  {:id "resolver0" :address "0xResolver0" :type "resolver"}
                  {:id "resolver1" :address "0xResolver1" :type "resolver"}
                  {:id "watchdog" :address "0xWatchdog" :type "attacker"}]
          ctx (proto/build-execution-context
               sew/protocol agents
               {:resolver-fee-bps 50 :bond-bps 0 :appeal-window-duration 200
                :max-dispute-duration 3600
                :escalation-resolvers {:1 "0xResolver1"}})
          w0 (t/empty-world 1000)
          s0 (replay/process-step sew/protocol ctx w0
                                  {:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
                                   :params {:token "0xUSDC" :to "0xSeller" :amount 1000
                                            :custom-resolver "0xResolver0"}})
          s1 (replay/process-step sew/protocol ctx (:world s0)
                                  {:seq 1 :time 1010 :agent "buyer" :action "raise_dispute"
                                   :params {:workflow-id 0}})
          s2 (replay/process-step sew/protocol ctx (:world s1)
                                  {:seq 2 :time 1020 :agent "resolver0" :action "execute_resolution"
                                   :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}})
          w-after-resolve (:world s2)

          ;; Escalate to level 1 then 2 — dispute must be active, not settled
          esc-hop0 {:seq 3 :time 1030 :agent "buyer" :action "escalate_dispute"
                    :params {:workflow-id 0 :event-id "evt-esc-shared" :hop-id "0"}}
          esc-hop1 {:seq 4 :time 1040 :agent "buyer" :action "escalate_dispute"
                    :params {:workflow-id 0 :event-id "evt-esc-shared" :hop-id "1"}}
          r-hop0 (replay/process-step sew/protocol ctx w-after-resolve esc-hop0)
          w-after-hop0 (:world r-hop0)
          ;; Seed a pending settlement at level 1 so escalation to level 2 is possible
          w-with-pending-l1 (assoc-in w-after-hop0 [:pending-settlements 0]
                                      (t/make-pending-settlement {:exists true
                                                                  :is-release true
                                                                  :appeal-deadline 1200
                                                                  :resolution-hash "0xhash-l1"}))
          esc-hop1' {:seq 4 :time 1040 :agent "buyer" :action "escalate_dispute"
                     :params {:workflow-id 0 :event-id "evt-esc-shared" :hop-id "1"}}
          w-after-hop0 (:world r-hop0)]
      ;; Non-hop action key shape — test via dedupe-op-key directly
      (let [key (sew/dedupe-op-key
                 w0
                 {:action "execute_pending_settlement" :agent "buyer"
                  :params {:workflow-id 0 :event-id "evt-settle-1"}})]
        (is (= 8 (count key)) "dedupe key is fixed-length 8-element vector")
        (is (= :sew (nth key 0)) "key prefix [:sew :replay-dedupe]")
        (is (= :replay-dedupe (nth key 1)) "key prefix [:sew :replay-dedupe]")
        (is (= "execute-pending-settlement" (nth key 2)) "action at position 2")
        (is (= "buyer" (nth key 3)) "agent at position 3")
        (is (= 0 (nth key 4)) "workflow-id at position 4")
        (is (= 0 (nth key 5)) "slash-id at position 5 (= wf-id for non-slash actions)")
        (is (nil? (nth key 6)) "hop-scope at position 6 is nil for non-hop actions")
        (is (= "evt-settle-1" (nth key 7)) "event-id at position 7"))

      ;; Hop-scoped actions with different hop-ids produce different keys
      ;; (verified via key construction, not replay pipeline)
      (is (= :ok (get-in r-hop0 [:trace-entry :result])) "first escalate (hop 0) succeeds")
      (is (= 1 (t/dispute-level w-after-hop0 0)) "escalated to level 1")
      (let [key-hop0 (sew/dedupe-op-key w-after-hop0
                       {:action "escalate_dispute" :agent "buyer"
                        :params {:workflow-id 0 :event-id "evt-esc" :hop-id "0"}})
            key-hop1 (sew/dedupe-op-key w-after-hop0
                       {:action "escalate_dispute" :agent "buyer"
                        :params {:workflow-id 0 :event-id "evt-esc" :hop-id "1"}})
            key-no-hop (sew/dedupe-op-key w-after-hop0
                         {:action "execute_pending_settlement" :agent "buyer"
                          :params {:workflow-id 0 :event-id "evt-settle-1"}})]
        (is (= (nth key-hop0 6) "0") "hop-scope = '0' when hop-id provided")
        (is (= (nth key-hop1 6) "1") "hop-scope = '1' for different hop-id")
        (is (nil? (nth key-no-hop 6)) "hop-scope = nil for non-hop action")
        (is (not= key-hop0 key-hop1) "different hop-ids produce different keys")
        (is (not= key-hop0 key-no-hop) "hop and non-hop keys differ in hop-scope position"))

      ;; Verify settlement dedup through the replay boundary
      (let [w-pending w-after-hop0
            w-advanced (time-ctx/advance-time w-pending {:to 1400})
            settle-ev {:seq 5 :time 1400 :agent "buyer" :action "execute_pending_settlement"
                       :params {:workflow-id 0 :event-id "evt-settle-1"}}
            r-settle (replay/process-step sew/protocol ctx w-advanced settle-ev)
            settle-dup {:seq 6 :time 1410 :agent "buyer" :action "execute_pending_settlement"
                        :params {:workflow-id 0 :event-id "evt-settle-1"}}
            r-dup (replay/process-step sew/protocol ctx (:world r-settle) settle-dup)]
        (is (= :ok (get-in r-settle [:trace-entry :result])) "first settlement succeeds")
        (is (= :no-op-duplicate (get-in r-dup [:trace-entry :extra :idempotency]))
            "settlement duplicate deduped (non-hop key with nil hop-scope is stable)")))))
