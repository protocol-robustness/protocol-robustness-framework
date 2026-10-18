(ns resolver-sim.economics.with-bounty.verification-test
  "Stage B: structural verification accompanies every with-bounty artifact
   (ADR-0006 D8). These are structural checks, never independent verification."
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.economics.bounty-payable :as bp]
            [resolver-sim.economics.bounty-payable-backing :as bpb]
            [resolver-sim.economics.with-bounty.policy :as policy]
            [resolver-sim.economics.with-bounty.proof :as proof]
            [resolver-sim.economics.with-bounty.fixtures :as fixtures]
            [resolver-sim.economics.with-bounty.transition-evidence :as wb-transition]
            [resolver-sim.economics.with-bounty.verification :as verification]
            [resolver-sim.protocols.sew.with-bounty :as sew-with-bounty]))

;; ── policy root ───────────────────────────────────────────────────────────

(deftest policy-root-verifies
  (let [root (policy/with-bounty-policy-root proof/review-policy)]
    (is (:valid? (verification/verify-policy-root proof/review-policy root)))
    (is (not (:valid? (verification/verify-policy-root proof/review-policy
                                                       (str "deadbeef" root)))))))

;; ── invocation evidence ───────────────────────────────────────────────────

(deftest invocation-evidence-verifies
  (let [good {:invocation/evidence-envelope
              {:invocation/id (apply str (repeat 64 "a"))
               :capability/ref [:economics/eligibility :fixture/review-bounty-eligible]}}]
    (is (:valid? (verification/verify-invocation-evidence good)))
    (is (not (:valid? (verification/verify-invocation-evidence {}))))
    (is (not (:valid? (verification/verify-invocation-evidence
                       (assoc-in good [:invocation/evidence-envelope :invocation/id]
                                 "short")))))))

;; ── evaluation artifacts ──────────────────────────────────────────────────

(deftest evaluation-artifacts-verify
  (let [applied (proof/evaluate-bounty
                 {:event/context {:review/finalised? true
                                  :event/actor :researcher/alice}
                  :base/result {:resolved-amount 10000}})]
    (is (= :applied (:status applied)))
    (is (:valid? (verification/verify-effects (:effects applied))))
    (is (:valid? (verification/verify-effect (:effect applied))))
    (is (:valid? (verification/verify-application-plan (:plan applied))))
    (is (:valid? (verification/verify-composition-receipt (:receipt applied))))
    (is (:valid? (verification/verify-invocation-evidence
                  (get-in applied [:receipt :bounty/eligibility]))))))

(deftest skipped-receipt-verifies
  (let [skipped (proof/evaluate-bounty
                 {:event/context {:review/finalised? false}
                  :base/result {:resolved-amount 10000}})]
    (is (= :skipped (:status skipped)))
    (is (:valid? (verification/verify-composition-receipt (:receipt skipped))))))

(deftest tampered-plan-fails-verification
  (let [applied (proof/evaluate-bounty
                 {:event/context {:review/finalised? true}
                  :base/result {:resolved-amount 10000}})
        tampered (assoc (:plan applied) :plan/hash "ignored")]
    (is (not (:valid? (verification/verify-application-plan tampered))))))

;; ── transition evidence ───────────────────────────────────────────────────

(deftest transition-evidence-verifies
  (let [applied (proof/evaluate-bounty
                 {:event/context {:review/finalised? true}
                  :base/result {:resolved-amount 10000}})
        evidence (wb-transition/build-transition-evidence
                  {:plan (:plan applied)
                   :effect-root (first (:plan/effect-roots (:plan applied)))
                   :world-before-root "sha256:w0"
                   :world-after-root "sha256:w1"
                   :payable-roots ["sha256:p"]
                   :backing-roots ["sha256:b"]
                   :custody-adjustment-roots
                   [{:held-adjustment/id "held-adjustment-0"
                     :artifact/hash "sha256:artifact-0"}]
                   :idempotent? false})]
    (is (:valid? (verification/verify-transition-evidence evidence)))
    (is (= 64 (count (:transition/hash evidence))))
    (is (not (:valid? (verification/verify-transition-evidence
                       (assoc evidence :transition/hash "ignored")))))))

(deftest unbound-custody-artifact-fails-transition-validation
  (let [applied (proof/evaluate-bounty
                 {:event/context {:review/finalised? true}
                  :base/result {:resolved-amount 10000}})
        evidence (wb-transition/build-transition-evidence
                  {:plan (:plan applied)
                   :effect-root (first (:plan/effect-roots (:plan applied)))
                   :world-before-root "sha256:w0"
                   :world-after-root "sha256:w1"
                   :custody-adjustment-roots ["held-adjustment-0"]})]
    (is (not (:valid? (verification/verify-transition-evidence evidence))))))

(deftest malformed-transition-evidence-fails
  (is (not (:valid? (verification/verify-transition-evidence {})))))

;; ── receipt shape ─────────────────────────────────────────────────────────

(deftest applied-receipt-requires-effect-and-plan-roots
  (let [base {:composition/type :economics/with-bounty
              :composition/status :applied
              :composition/policy-root (apply str (repeat 64 "a"))
              :composition/base-operation-root (apply str (repeat 64 "b"))
              :extensions/resolution-root (apply str (repeat 64 "c"))
              :bounty/eligibility {}}
        missing-effect (assoc base :bounty/application-plan-root (apply str (repeat 64 "d")))]
    (is (not (:valid? (verification/verify-composition-receipt missing-effect))))))

;; ── cross-artifact reconciliation ─────────────────────────────────────────

(deftest receipt-plan-reconciliation
  (let [applied (proof/evaluate-bounty
                 {:event/context {:review/finalised? true}
                  :base/result {:resolved-amount 10000}})
        receipt (:receipt applied)
        plan (:plan applied)]
    (is (:valid? (verification/verify-receipt-with-plan receipt plan)))
    ;; wrong application-plan root bound to a valid effect root is caught
    (is (not (:valid? (verification/verify-receipt-with-plan
                       (assoc receipt :bounty/application-plan-root
                              (apply str (repeat 64 "f")))
                       plan))))
    ;; a resolution root that changed between evaluation and verification is caught
    (is (not (:valid? (verification/verify-receipt-with-plan
                       (assoc receipt :extensions/resolution-root
                              (apply str (repeat 64 "e")))
                       plan))))))

(deftest application-world-rejects-mutated-payable-and-backing-artifacts
  (let [plan (:plan (proof/evaluate-bounty
                     {:event/context {:review/finalised? true
                                      :event/actor :researcher/alice}
                      :base/result {:resolved-amount 10000}
                      :adapter-support sew-with-bounty/adapter-support}))
        world (:world (sew-with-bounty/apply-with-bounty-plan plan {} fixtures/funding-trust-policy))
        payable (first (vals (:with-bounty/payables world)))
        backing (first (vals (:with-bounty/backings world)))]
    (is (:valid? (verification/verify-application-world
                  world {:funding-trust-policy fixtures/funding-trust-policy})))
    (is (not (:valid? (verification/verify-application-world
                       (assoc-in world [:with-bounty/payables (:payable/id payable)
                                        :payable/amount] 99)))))
    (is (not (:valid? (verification/verify-application-world
                       (assoc-in world [:with-bounty/backings (:backing/id backing)
                                        :backing/source-allocations]
                                 {:bounty-reserve 99})))))
    (is (not (:valid? (verification/verify-application-world
                       (assoc-in world [:with-bounty/backings (:backing/id backing)
                                        :backing/context :custody/artifact-hash]
                                 "sha256:substituted-reservation")))))))

(deftest application-world-rejects-pending-payable-marked-claimable
  (let [pending (bp/build-bounty-payable
                 {:payable/id "obligation-pending"
                  :distribution-root "sha256:distribution"
                  :beneficiary "researcher/pseudonym-7"
                  :amount 100})
        backing (bpb/build-bounty-payable-backing
                 {:payable-root (:payable/hash pending)
                  :payable-id (:payable/id pending)
                  :distribution-root (:payable/distribution-root pending)
                  :amount 100
                  :source-allocations {:bounty-reserve 100}})
        world {:with-bounty/payables {(:payable/id pending) pending}
               :with-bounty/backings {(:backing/id backing) backing}
               :claimable-v2 {(:payable/id pending)
                              {:liability/bounty-payable
                               {(:payable/beneficiary pending) 100}}}}]
    (is (not (:valid? (verification/verify-application-world world))))))

(deftest application-world-rejects-settlement-label-without-payout-evidence
  (let [plan (:plan (proof/evaluate-bounty
                     {:event/context {:review/finalised? true
                                      :event/actor :researcher/pseudonym-7}
                      :base/result {:resolved-amount 10000}
                      :adapter-support sew-with-bounty/adapter-support}))
        world (:world (sew-with-bounty/apply-with-bounty-plan plan {} fixtures/funding-trust-policy))
        backed (first (vals (:with-bounty/payables world)))
        backing (first (vals (:with-bounty/backings world)))
        settled (bp/build-bounty-payable
                 {:payable/id (:payable/id backed)
                  :distribution-root (:payable/distribution-root backed)
                  :beneficiary (:payable/beneficiary backed)
                  :amount (:payable/amount backed)
                  :kind (:payable/kind backed)
                  :lifecycle :settled})
        consumed (bpb/build-bounty-payable-backing
                  {:payable-root (:payable/hash settled)
                   :payable-id (:payable/id settled)
                   :distribution-root (:backing/distribution-root backing)
                   :amount (:backing/amount backing)
                   :source-allocations (:backing/source-allocations backing)
                   :backing-lifecycle :consumed
                   :context (:backing/context backing)})
        settled-world (-> world
                          (assoc-in [:with-bounty/payables (:payable/id settled)] settled)
                          (assoc-in [:with-bounty/backings (:backing/id consumed)] consumed)
                          (update-in [:claimable-v2 (:payable/id settled)
                                      :liability/bounty-payable]
                                     dissoc (:payable/beneficiary settled)))]
    (is (not (:valid? (verification/verify-application-world settled-world))))))

(deftest transition-plan-reconciliation
  (let [applied (proof/evaluate-bounty
                 {:event/context {:review/finalised? true}
                  :base/result {:resolved-amount 10000}})
        plan (:plan applied)
        evidence (wb-transition/build-transition-evidence
                  {:plan plan
                   :effect-root (first (:plan/effect-roots plan))
                   :world-before-root "sha256:w0"
                   :world-after-root "sha256:w1"
                   :custody-adjustment-roots
                   [{:held-adjustment/id "held-adjustment-0"
                     :artifact/hash "sha256:artifact-0"}]})]
    (is (:valid? (verification/verify-transition-with-plan evidence plan)))
    (is (not (:valid? (verification/verify-transition-with-plan
                       (assoc evidence :combined-effect-root
                              (apply str (repeat 64 "9")))
                       plan))))))
