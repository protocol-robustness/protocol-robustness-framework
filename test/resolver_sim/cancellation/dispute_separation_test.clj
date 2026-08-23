(ns resolver-sim.cancellation.dispute-separation-test
  "M2 evidence: ordinary party cancellation is enforced against PENDING escrow
   state only. A disputed escrow is not cancellable by the ordinary party path
   no matter how strong the agreement or policy looks, because the snapshot
   guard refuses any non-pending state before planning and admission run.

   Dispute REMEDIATION (timeout/automatic vs governance-authorized) has no
   implemented contract path in this repository — see
   docs/cancellation/CANCELLATION_ARTIFACT_MAP.md. These tests pin the
   existing separation that does exist."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.cancellation.ordinary-planner :as planner]
            [resolver-sim.cancellation.party-preconditions :as party]
            [resolver-sim.cancellation.sew-escrow-snapshot :as snapshot]))

(defn- snapshot-for [state statuses]
  (let [s0 {:snapshot/schema snapshot/schema-version
            :workflow/id "escrow-d" :escrow/sender "alice" :escrow/recipient "bob"
            :escrow/state state :sender/cancellation-status (:sender statuses)
            :recipient/cancellation-status (:recipient statuses)}]
    ;; snapshot-root THROWS on invalid snapshots — that refusal is itself part
    ;; of what these tests exercise — so only valid snapshots get rooted here.
    (if (snapshot/valid-snapshot? s0)
      (assoc s0 :snapshot/root (snapshot/snapshot-root s0))
      s0)))

(def pending (snapshot-for :pending {:sender :none :recipient :agree-to-cancel}))
(def disputed (snapshot-for :disputed {:sender :none :recipient :none}))

(deftest disputed-state-fails-the-snapshot-guard
  (is (true? (snapshot/valid-snapshot? pending)) "control: pending snapshots are valid")
  (is (false? (snapshot/valid-snapshot? disputed)))
  (is (contains? (set (snapshot/snapshot-errors disputed)) :snapshot/not-pending)
      "a disputed escrow is refused as not-pending, before any party logic"))

(deftest disputed-state-cannot-reach-admissible-planning-even-with-full-agreement
  ;; Both parties agreeing + unilateral policy would make an ordinary
  ;; cancellation terminal IF the state were pending. From a disputed state it
  ;; must never become admissible: preconditions carry the typed error, no
  ;; effects are derived, and the planner refuses to mint ANY rooted evaluation
  ;; artifact at all (fail closed — there is no admissible-looking object left
  ;; behind for a consumer to misread).
  (let [policy {:policy/schema "sew-party-cancellation-policy.v1"
                :policy/can-cancel? true :policy/unilateral-cancel? true}
        errors (:preconditions/errors (party/preconditions disputed :sender "alice"))
        plan-or-throw (try {:plan (planner/plan {:operation {:request {:party :sender}}
                                                 :snapshot disputed
                                                 :policy policy
                                                 :principal "alice"})}
                           (catch clojure.lang.ExceptionInfo e
                             {:thrown true :message (.getMessage e)}))
        ;; Control mirrors ordinary-admission-test: a stand-in op/policy with
        ;; syntactically valid placeholder roots so the planner can mint its
        ;; rooted evaluation artifact.
        policy+root (assoc policy :policy/root "sha256:0000000000000000000000000000000000000000000000000000000000000031")
        op-standin {:request {:party :sender}
                    :operation/root "sha256:0000000000000000000000000000000000000000000000000000000000000030"}
        pending-plan (planner/plan {:operation op-standin
                                    :snapshot pending
                                    :policy policy+root
                                    :principal "alice"})]
    (is (contains? (set errors) :precondition/invalid-snapshot)
        "the disputed snapshot surfaces as a typed precondition error")
    (is (:thrown plan-or-throw)
        "planning from a disputed snapshot does not produce an evaluation artifact")
    (is (.contains ^String (:message plan-or-throw) "invalid party cancellation evaluation"))
    ;; control A: the same parties/policy from a PENDING snapshot remain
    ;; admissible; unilateral policy makes the ordinary path terminal.
    (is (= :refund-sender (get-in pending-plan [:derived-effects :effects/kind])))
    ;; control B: without unilateral policy AND without counterparty
    ;; agreement, the path stays nonterminal consent — two distinct rooted
    ;; effect kinds.
    (let [pending-unagreed (snapshot-for :pending {:sender :none :recipient :none})
          nonterminal-plan (planner/plan {:operation op-standin
                                          :snapshot pending-unagreed
                                          :policy (assoc policy+root :policy/unilateral-cancel? false)
                                          :principal "alice"})]
      (is (= :record-party-agreement (get-in nonterminal-plan [:derived-effects :effects/kind])))
      (is (not= (get-in nonterminal-plan [:derived-effects :effects/root])
                (get-in pending-plan [:derived-effects :effects/root]))
          "terminal refund and nonterminal consent effects are distinct identities"))))

(deftest dispute-and-agreement-transplant-is-blocked-by-content-addressing
  ;; A disputed escrow's cancellation-status fields cannot be transplanted into
  ;; a pending snapshot to smuggle agreement: the snapshot root covers those
  ;; fields, so any edit produces a different root.
  (let [tampered (assoc pending :recipient/cancellation-status :none)
        re-rooted (assoc tampered :snapshot/root (snapshot/snapshot-root tampered))]
    (is (not= (:snapshot/root pending) (:snapshot/root re-rooted))
        "editing agreement status changes the committed snapshot identity")
    (is (false? (= (:snapshot/root pending)
                   (try (snapshot/snapshot-root tampered) (catch Exception _ nil))))
        "re-rooting the tampered snapshot is detectable: the original root no longer verifies")
    (is (= "alice" (party/party-principal disputed :sender))
        "party principals are read from the snapshot regardless of state")))
