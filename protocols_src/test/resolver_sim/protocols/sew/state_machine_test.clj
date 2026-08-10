(ns resolver-sim.protocols.sew.state-machine-test
  "Unit tests for contract_model/state_machine.clj.

   Every transition is tested for:
     - the happy path
     - every guard failure path

   No mocking — pure functions over world-state maps."
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.types        :as t]
            [resolver-sim.protocols.sew.state-machine :as sm]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def alice "0xAlice")
(def bob   "0xBob")
(def carol "0xCarol") ; third party, not a participant
(def usdc  "0xUSDC")

(defn- base-world
  "World with one :pending escrow at workflow-id 0."
  ([] (base-world 1000))
  ([block-time]
   (-> (t/empty-world block-time)
       (assoc-in [:escrow-transfers 0]
                 (t/make-escrow-transfer
                  {:token            usdc
                   :from             alice
                   :to               bob
                   :amount-after-fee 950
                   :escrow-state     :pending})))))

(defn- disputed-world
  "World with one :disputed escrow at workflow-id 0."
  ([] (disputed-world 1000))
  ([block-time]
   (-> (base-world block-time)
       (assoc-in [:escrow-transfers 0 :escrow-state] :disputed)
       (assoc-in [:escrow-transfers 0 :sender-status] :raise-dispute)
       (assoc-in [:dispute-timestamps 0] block-time))))

;; ---------------------------------------------------------------------------
;; transition-to-disputed
;; ---------------------------------------------------------------------------

(deftest transition-to-disputed-happy-sender
  (let [w  (base-world 1000)
        r  (sm/transition-to-disputed w 0 alice)]
    (is (true? (:ok r)) "should succeed")
    (is (= :disputed (t/escrow-state (:world r) 0)) "state must be :disputed")
    (is (= :raise-dispute (get-in (:world r) [:escrow-transfers 0 :sender-status]))
        "sender-status must be :raise-dispute when sender raises")
    (is (= :none (get-in (:world r) [:escrow-transfers 0 :recipient-status]))
        "recipient-status must remain :none")
    (is (= 1000 (get-in (:world r) [:dispute-timestamps 0]))
        "dispute-raised-timestamp must be set to block-time")))

(deftest transition-to-disputed-happy-recipient
  (let [w  (base-world 1000)
        r  (sm/transition-to-disputed w 0 bob)]
    (is (true? (:ok r)))
    (is (= :raise-dispute (get-in (:world r) [:escrow-transfers 0 :recipient-status]))
        "recipient-status must be :raise-dispute when recipient raises")
    (is (= :none (get-in (:world r) [:escrow-transfers 0 :sender-status])))))

(deftest transition-to-disputed-invalid-workflow
  (let [w (base-world)
        r (sm/transition-to-disputed w 99 alice)]
    (is (false? (:ok r)))
    (is (= :invalid-workflow-id (:error r)))))

(deftest transition-to-disputed-not-pending
  (testing "already disputed"
    (let [r (sm/transition-to-disputed (disputed-world) 0 alice)]
      (is (false? (:ok r)))
      (is (= :transfer-not-pending (:error r)))))
  (testing "already released"
    (let [w (assoc-in (base-world) [:escrow-transfers 0 :escrow-state] :released)
          r (sm/transition-to-disputed w 0 alice)]
      (is (false? (:ok r)))
      (is (= :transfer-not-pending (:error r))))))

(deftest transition-to-disputed-not-participant
  (let [r (sm/transition-to-disputed (base-world) 0 carol)]
    (is (false? (:ok r)))
    (is (= :not-participant (:error r)))))

;; ---------------------------------------------------------------------------
;; transition-to-released
;; ---------------------------------------------------------------------------

(deftest transition-to-released-from-pending
  (let [r (sm/transition-to-released (base-world) 0)]
    (is (true? (:ok r)))
    (is (= :released (t/escrow-state (:world r) 0)))))

(deftest transition-to-released-from-disputed
  (let [r (sm/transition-to-released (disputed-world) 0)]
    (is (true? (:ok r)))
    (is (= :released (t/escrow-state (:world r) 0)))))

(deftest transition-to-released-invalid-workflow
  (let [r (sm/transition-to-released (base-world) 99)]
    (is (false? (:ok r)))
    (is (= :invalid-workflow-id (:error r)))))

(deftest transition-to-released-wrong-state
  (doseq [terminal (conj t/terminal-states :none)]
    (testing (str "from " terminal)
      (let [w (assoc-in (base-world) [:escrow-transfers 0 :escrow-state] terminal)
            r (sm/transition-to-released w 0)]
        (is (false? (:ok r)))
        (is (= :invalid-state-for-release (:error r)))))))

;; ---------------------------------------------------------------------------
;; transition-to-refunded
;; ---------------------------------------------------------------------------

(deftest transition-to-refunded-from-pending
  (let [r (sm/transition-to-refunded (base-world) 0)]
    (is (true? (:ok r)))
    (is (= :refunded (t/escrow-state (:world r) 0)))))

(deftest transition-to-refunded-from-disputed
  (let [r (sm/transition-to-refunded (disputed-world) 0)]
    (is (true? (:ok r)))
    (is (= :refunded (t/escrow-state (:world r) 0)))))

(deftest transition-to-refunded-invalid-workflow
  (let [r (sm/transition-to-refunded (base-world) 99)]
    (is (false? (:ok r)))
    (is (= :invalid-workflow-id (:error r)))))

(deftest transition-to-refunded-wrong-state
  (doseq [terminal (conj t/terminal-states :none)]
    (testing (str "from " terminal)
      (let [w (assoc-in (base-world) [:escrow-transfers 0 :escrow-state] terminal)
            r (sm/transition-to-refunded w 0)]
        (is (false? (:ok r)))
        (is (= :invalid-state-for-refund (:error r)))))))

;; ---------------------------------------------------------------------------
;; transition-to-resolved
;; ---------------------------------------------------------------------------

(deftest transition-to-resolved-happy
  (let [r (sm/transition-to-resolved (disputed-world) 0)]
    (is (true? (:ok r)))
    (is (= :resolved (t/escrow-state (:world r) 0)))))

(deftest transition-to-resolved-invalid-workflow
  (let [r (sm/transition-to-resolved (base-world) 99)]
    (is (false? (:ok r)))
    (is (= :invalid-workflow-id (:error r)))))

(deftest transition-to-resolved-wrong-state
  (doseq [state [:pending :released :refunded :resolved :none]]
    (testing (str "from " state)
      (let [w (assoc-in (base-world) [:escrow-transfers 0 :escrow-state] state)
            r (sm/transition-to-resolved w 0)]
        (is (false? (:ok r)))
        (is (= :transfer-not-in-dispute (:error r)))))))

;; ---------------------------------------------------------------------------
;; mutual-cancel setters
;; ---------------------------------------------------------------------------

(deftest sender-agree-to-cancel-happy
  (let [r (sm/set-sender-agree-to-cancel (base-world) 0 alice)]
    (is (true? (:ok r)))
    (is (= :agree-to-cancel (get-in (:world r) [:escrow-transfers 0 :sender-status])))))

(deftest sender-agree-to-cancel-not-sender
  (let [r (sm/set-sender-agree-to-cancel (base-world) 0 bob)]
    (is (false? (:ok r)))
    (is (= :not-sender (:error r)))))

(deftest sender-agree-to-cancel-not-pending
  (let [w (assoc-in (base-world) [:escrow-transfers 0 :escrow-state] :disputed)
        r (sm/set-sender-agree-to-cancel w 0 alice)]
    (is (false? (:ok r)))
    (is (= :transfer-not-pending (:error r)))))

(deftest recipient-agree-to-cancel-happy
  (let [r (sm/set-recipient-agree-to-cancel (base-world) 0 bob)]
    (is (true? (:ok r)))
    (is (= :agree-to-cancel (get-in (:world r) [:escrow-transfers 0 :recipient-status])))))

(deftest recipient-agree-to-cancel-not-recipient
  (let [r (sm/set-recipient-agree-to-cancel (base-world) 0 alice)]
    (is (false? (:ok r)))
    (is (= :not-recipient (:error r)))))

(deftest both-agreed-to-cancel-predicate
  (let [w (-> (base-world)
              (assoc-in [:escrow-transfers 0 :sender-status] :agree-to-cancel)
              (assoc-in [:escrow-transfers 0 :recipient-status] :agree-to-cancel))]
    (is (true? (sm/both-agreed-to-cancel? w 0))))
  (let [w (assoc-in (base-world) [:escrow-transfers 0 :sender-status] :agree-to-cancel)]
    (is (false? (sm/both-agreed-to-cancel? w 0)) "only sender agreed")))

;; ---------------------------------------------------------------------------
;; Timed-action predicates
;; ---------------------------------------------------------------------------

(deftest auto-release-due-true
  (let [w (assoc-in (base-world 2000) [:escrow-transfers 0 :auto-release-time] 1500)]
    (is (true? (sm/auto-release-due? w 0)))))

(deftest auto-release-due-false-not-yet
  (let [w (assoc-in (base-world 1000) [:escrow-transfers 0 :auto-release-time] 1500)]
    (is (false? (sm/auto-release-due? w 0)))))

(deftest auto-release-due-false-wrong-state
  (let [w (-> (base-world 2000)
              (assoc-in [:escrow-transfers 0 :auto-release-time] 1500)
              (assoc-in [:escrow-transfers 0 :escrow-state] :disputed))]
    (is (false? (sm/auto-release-due? w 0)))))

(deftest auto-cancel-due-true
  (let [w (assoc-in (base-world 2000) [:escrow-transfers 0 :auto-cancel-time] 1500)]
    (is (true? (sm/auto-cancel-due? w 0)))))

;; ---------------------------------------------------------------------------
;; auto-cancel-due-on-disputed?
;; ---------------------------------------------------------------------------

(deftest auto-cancel-due-on-disputed-true
  (let [w (-> (disputed-world 2000)
              (assoc-in [:escrow-transfers 0 :auto-cancel-time] 1500))]
    (is (true? (sm/auto-cancel-due-on-disputed? w 0)))))

(deftest auto-cancel-due-on-disputed-false-not-disputed
  (let [w (-> (base-world 2000)
              (assoc-in [:escrow-transfers 0 :auto-cancel-time] 1500))]
    (is (false? (sm/auto-cancel-due-on-disputed? w 0)))))

(deftest auto-cancel-due-on-disputed-false-time-not-passed
  (let [w (-> (disputed-world 1000)
              (assoc-in [:escrow-transfers 0 :auto-cancel-time] 1500))]
    (is (false? (sm/auto-cancel-due-on-disputed? w 0)))))

(deftest auto-cancel-due-on-disputed-false-has-pending-settlement
  (let [pending {:exists true :is-release true :appeal-deadline 9999 :resolution-hash nil}
        w       (-> (disputed-world 2000)
                    (assoc-in [:escrow-transfers 0 :auto-cancel-time] 1500)
                    (assoc-in [:pending-settlements 0] pending))]
    (is (false? (sm/auto-cancel-due-on-disputed? w 0)))))

(deftest auto-cancel-due-on-disputed-false-zero-time
  (let [w (-> (disputed-world 2000)
              (assoc-in [:escrow-transfers 0 :auto-cancel-time] 0))]
    (is (false? (sm/auto-cancel-due-on-disputed? w 0)))))

;; ---------------------------------------------------------------------------
;; dispute-timeout-exceeded?
;; ---------------------------------------------------------------------------

(deftest dispute-timeout-exceeded-true
  (let [snap {:max-dispute-duration 3600 :appeal-window-duration 0}
        ;; dispute raised at t=1000; now t=5000 > 1000+3600
        w    (-> (disputed-world 5000)
                 (assoc-in [:dispute-timestamps 0] 1000)
                 (assoc-in [:module-snapshots 0] snap))]
    (is (true? (sm/dispute-timeout-exceeded? w 0)))))

(deftest dispute-timeout-exceeded-false-pending-settlement
  (let [snap    {:max-dispute-duration 3600}
        pending {:exists true :is-release true :appeal-deadline 9999 :resolution-hash nil}
        w       (-> (disputed-world 5000)
                    (assoc-in [:dispute-timestamps 0] 1000)
                    (assoc-in [:module-snapshots 0] snap)
                    (assoc-in [:pending-settlements 0] pending))]
    (is (false? (sm/dispute-timeout-exceeded? w 0))
        "must not timeout when a pending-settlement exists")))

(deftest dispute-timeout-not-yet-exceeded
  (let [snap {:max-dispute-duration 3600}
        w    (-> (disputed-world 2000)
                 (assoc-in [:dispute-timestamps 0] 1000)
                 (assoc-in [:module-snapshots 0] snap))]
    (is (false? (sm/dispute-timeout-exceeded? w 0)))))

(deftest pending-settlement-executable-true
  (let [pending {:exists true :is-release true :appeal-deadline 1500 :resolution-hash nil}
        w       (-> (disputed-world 2000)
                    (assoc-in [:pending-settlements 0] pending))]
    (is (true? (sm/pending-settlement-executable? w 0)))))

(deftest pending-settlement-executable-false-before-deadline
  (let [pending {:exists true :is-release true :appeal-deadline 3000 :resolution-hash nil}
        w       (-> (disputed-world 1000)
                    (assoc-in [:pending-settlements 0] pending))]
    (is (false? (sm/pending-settlement-executable? w 0)))))

(deftest pending-settlement-executable-false-not-disputed
  (let [pending {:exists true :is-release true :appeal-deadline 500 :resolution-hash nil}
        w       (-> (base-world 1000)
                    (assoc-in [:escrow-transfers 0 :escrow-state] :released)
                    (assoc-in [:pending-settlements 0] pending))]
    (is (false? (sm/pending-settlement-executable? w 0)))))

;; ---------------------------------------------------------------------------
;; Absorbing-state invariant: no transition escapes terminal states
;; ---------------------------------------------------------------------------

(deftest terminal-states-are-absorbing
  (doseq [terminal t/terminal-states]
    (testing (str terminal " is absorbing")
      (let [w (assoc-in (base-world) [:escrow-transfers 0 :escrow-state] terminal)]
        (is (false? (:ok (sm/transition-to-disputed  w 0 alice))))
        (is (false? (:ok (sm/transition-to-released  w 0))))
        (is (false? (:ok (sm/transition-to-refunded  w 0))))
        (is (false? (:ok (sm/transition-to-resolved  w 0))))))))

(deftest allowed-transitions-graph-is-acyclic
  (is (true? sm/transition-graph-acyclic))
  (is (false? (sm/valid-transition? :disputed :pending))
      "backward edge would create circular invalid_states")
  (is (false? (sm/valid-transition? :released :pending))
      "terminal must not re-enter live states"))

;; ---------------------------------------------------------------------------
;; resolver-response-exceeded?
;; ---------------------------------------------------------------------------

(deftest resolver-response-exceeded-true-after-window
  (let [w (-> (disputed-world 1000)
              (assoc-in [:module-snapshots 0 :resolver-response-window] 60)
              (assoc-in [:context/time :block-ts] 1061))]
    (is (true? (sm/resolver-response-exceeded? w 0)))))

(deftest resolver-response-exceeded-false-before-window
  (let [w (-> (disputed-world 1000)
              (assoc-in [:module-snapshots 0 :resolver-response-window] 60)
              (assoc-in [:context/time :block-ts] 1050))]
    (is (false? (sm/resolver-response-exceeded? w 0)))))

(deftest resolver-response-exceeded-at-deadline
  (let [w (-> (disputed-world 1000)
              (assoc-in [:module-snapshots 0 :resolver-response-window] 60)
              (assoc-in [:context/time :block-ts] 1060))]
    (is (true? (sm/resolver-response-exceeded? w 0))
        "at-or-after the deadline means the window is closed")))

(deftest resolver-response-exceeded-window-disabled
  (let [w (-> (disputed-world 1000)
              (assoc-in [:module-snapshots 0 :resolver-response-window] 0)
              (assoc-in [:context/time :block-ts] 2000))]
    (is (false? (sm/resolver-response-exceeded? w 0))
        "window=0 (default) disables the deadline; lazy resolver caught only by max-dispute-duration")))

(deftest resolver-response-exceeded-not-disputed
  (let [w (-> (base-world 1000)
              (assoc-in [:module-snapshots 0 :resolver-response-window] 60)
              (assoc-in [:context/time :block-ts] 2000))]
    (is (false? (sm/resolver-response-exceeded? w 0))
        "must be :disputed to have a response deadline")))

;; ---------------------------------------------------------------------------
;; TemporalDeadlines :resolver-response kind
;; ---------------------------------------------------------------------------

(deftest resolver-response-deadline-for-exposed
  (testing "deadline-for :resolver-response returns raise-time + window"
    (let [w (-> (disputed-world 1000)
                (assoc-in [:module-snapshots 0 :resolver-response-window] 60))]
      (is (= 1060 (proto/deadline-for sew/protocol w :resolver-response 0 nil))
          "raise-time 1000 + window 60 = deadline 1060"))))

(deftest resolver-response-deadline-for-disabled
  (testing "deadline-for :resolver-response is nil when the window is disabled"
    (let [w (-> (disputed-world 1000)
                (assoc-in [:module-snapshots 0 :resolver-response-window] 0))]
      (is (nil? (proto/deadline-for sew/protocol w :resolver-response 0 nil))))))
