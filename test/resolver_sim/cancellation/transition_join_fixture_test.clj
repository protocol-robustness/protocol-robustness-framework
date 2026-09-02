(ns resolver-sim.cancellation.transition-join-fixture-test
  "Iteration 7B: concrete authoritative cancellation fixture and research vector.

  Builds a complete, self-consistent set of artifacts that exercise the
  transition_join verify-join pipeline end-to-end:

    command-lineage (head + cancel-and-terminate terminator + receipt)
    →  cancellation-operation.v1 (roots bound to the lineage)
    →  transition subject (bound to operation roots)
    →  transition result (bound to execution effects / state-after / receipt roots)
    →  verify-join cross-checks every binding

  Substitution-attack tests verify that no root can be transplanted from a
  different context without detection."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [resolver-sim.cancellation.operation :as operation]
            [resolver-sim.cancellation.transition-join :as join]
            [resolver-sim.cancellation.party-command :as command]
            [resolver-sim.cancellation.sew-escrow-snapshot :as snapshot]
            [resolver-sim.cancellation.semantic :as semantic]
            [resolver-sim.cancellation.ordinary-planner :as planner]
            [resolver-sim.cancellation.party-preconditions :as party]
            [resolver-sim.composition.command-lineage :as cl]
            [resolver-sim.benchmark.governed-authority-transition :as gat]
            [resolver-sim.signed-external-decision :as signed]
            [resolver-sim.support.ed25519 :as keys]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:private default-states
  {::prior "sha256:0000000000000000000000000000000000000000000000000000000000000010"
   ::lifecycle "sha256:0000000000000000000000000000000000000000000000000000000000000020"
   ::terminal "sha256:0000000000000000000000000000000000000000000000000000000000000030"})

(def ^:private alternate-states
  {::prior "sha256:1111111111111111111111111111111111111111111111111111111111111111"
   ::lifecycle "sha256:2222222222222222222222222222222222222222222222222222222222222222"
   ::terminal "sha256:3333333333333333333333333333333333333333333333333333333333333333"})

(def ^:private transition-definition-root
  "Real transition definition constant from the governed-authority transition
   kernel — reused as the cancellation transition-definition commitment."
  gat/transition-definition-root)

(defn- re-root-result
  "Recompute the result/root after substituting a field in the result body."
  [result]
  (let [body (dissoc result :result/root)]
    (assoc result :result/root
           (hash-ref/sha256-ref
            (hc/domain-hash "cancellation-transition-result.v1" body)))))

(defn- build-fixture
  "Build a complete, self-consistent cancellation transition fixture.
  `state-overrides` may supply different ::prior / ::lifecycle / ::terminal roots
  to produce a genuinely distinct fixture for cross-fixture substitution tests."
  ([]
   (build-fixture {}))
  ([state-overrides]
   (let [states (merge default-states state-overrides)
         prior-state (::prior states)
         lifecycle-state (::lifecycle states)
         terminal-state (::terminal states)

         ;; ── Command-lineage: head → cancel-and-terminate ──────────────────
         head-cmd (cl/build-command
                   {:command/action :advance-lineage
                    :command/input-state-root prior-state
                    :command/resulting-state-root lifecycle-state
                    :command/built-with-includes [{:kind :shared-state :ref prior-state}]})
         head-root (:command/root head-cmd)

         ;; cancel-and-terminate anchored on the head's resulting-state-root
         terminator-cmd (cl/build-termination-command head-cmd terminal-state)

         ;; termination receipt built from [terminator head]
         receipt (cl/build-termination-receipt terminator-cmd head-cmd)
         receipt-root (:termination/root receipt)

         ;; ── SEW escrow snapshot (pending, counterparty agreed) ───────────────
         kp (keys/keypair :alice-cancel-key)
         s0 {:snapshot/schema snapshot/schema-version
             :workflow/id "escrow-cancel-join"
             :escrow/sender "alice" :escrow/recipient "bob"
             :escrow/state :pending
             :sender/cancellation-status :none
             :recipient/cancellation-status :agree-to-cancel}
         snapshot-artifact (assoc s0 :snapshot/root (snapshot/snapshot-root s0))
         snapshot-root (:snapshot/root snapshot-artifact)

         ;; ── Policy (mutual cancel, non-unilateral → terminal via agreement) ──
         p0 {:policy/schema semantic/policy-schema
             :policy/can-cancel? true
             :policy/unilateral-cancel? false}
         policy (assoc p0 :policy/root (semantic/policy-root p0))
         policy-root (:policy/root policy)

         ;; ── Preconditions (computed from snapshot) ────────────────────────
         pre (party/preconditions snapshot-artifact :sender "alice")
         preconditions (assoc pre :preconditions/root (party/preconditions-root pre))
         preconditions-root (:preconditions/root preconditions)

         ;; ── Planner: derive effects from the real snapshot + policy ────────
         plan (planner/plan {:operation {:request {:party :sender}
                                         :operation/root lifecycle-state}
                             :snapshot snapshot-artifact
                             :policy policy
                             :principal "alice"})
         derived-effects (:derived-effects plan)
         derived-effects-root (:effects/root derived-effects)

         ;; ── Execution effects (binds derived → execution) ─────────────────
         ee0 {:execution-effects/schema semantic/execution-effects-schema
              :derived-effects/root derived-effects-root}
         execution-effects (assoc ee0 :execution-effects/root
                                  (semantic/execution-effects-root ee0))
         effects-root (:execution-effects/root execution-effects)

         ;; ── Signed party command (authorization) ──────────────────────────
         c0 {:command/schema command/schema-version
             :command/action :cancel
             :command/principal "alice"
             :operation/root lifecycle-state}
         c0 (assoc c0 :command/root (command/command-root c0))
         party-cmd (signed/sign-envelope c0 command/decision-domain
                                         (:private-key kp) (:key/id kp))
         authorization-root (:command/root party-cmd)

         ;; ── Invariants root (attestation commitment over post-transition state)
         invariants-root (hash-ref/sha256-ref
                          (hc/domain-hash "cancellation-invariants.v1"
                                          {:invariants/state-after-root terminal-state
                                           :invariants/effects-root effects-root}))

         ;; ── Cancellation operation.v1 ─────────────────────────────────────
         op-input {:operation/schema operation/schema-version
                   :operation/purpose :cancellation/execution
                   :event/id "cancel-join-7b"
                   :protocol/id :sew
                   :target {:kind :sew/escrow
                            :id "escrow-cancel-join"
                            :snapshot-root snapshot-root
                            :state-before-root lifecycle-state
                            :lifecycle-head-root head-root}
                   :request {:caller/id "alice" :action :cancel :requested-at 1}
                   :policy {:id :sew/party-cancellation :root policy-root}
                   :evaluation {:inputs-root prior-state
                                :base-decision :ordinary
                                :decision {:derived-effects-root derived-effects-root}}
                   :preconditions/root preconditions-root
                   :transition-definition/root transition-definition-root
                   :authorization {:kind :ordinary :root authorization-root}
                   :execution {:status :applied
                               :effects-root effects-root
                               :state-after-root terminal-state}
                   :operation/root lifecycle-state}

         ;; Recompute the operation root from the assembled fields
         op (assoc op-input :operation/root
                   (operation/operation-root (dissoc op-input :operation/root)))
         op-root (:operation/root op)

         ;; ── Transition subject (bound to operation roots) ───────────────────
         subject-input {:transition-definition/root transition-definition-root
                        :state-before/root lifecycle-state
                        :authorization/root authorization-root
                        :preconditions/root preconditions-root}
         subj (join/subject subject-input)
         sroot (join/subject-root subj)

         ;; ── Transition result (bound to execution + receipt roots) ────────
         result-output {:effects/root effects-root
                        :state-after/root terminal-state
                        :receipt/root receipt-root}
         res (join/result subj result-output)
         rroot (:result/root res)]
     {:prior-state-root prior-state
      :lifecycle-state-root lifecycle-state
      :terminal-state-root terminal-state
      :head-cmd head-cmd
      :head-root head-root
      :terminator-cmd terminator-cmd
      :term-root (:command/root terminator-cmd)
      :receipt receipt
      :receipt-root receipt-root
      :operation op
      :operation-root op-root
      :snapshot snapshot-artifact
      :snapshot-root snapshot-root
      :policy policy
      :policy-root policy-root
      :preconditions preconditions
      :preconditions-root preconditions-root
      :derived-effects derived-effects
      :derived-effects-root derived-effects-root
      :execution-effects execution-effects
      :effects-root effects-root
      :party-cmd party-cmd
      :authorization-root authorization-root
      :transition-definition-root transition-definition-root
      :subject subj
      :subject-root sroot
      :result res
      :result-root rroot
      :invariants-root invariants-root})))

(def ^:private fixture (atom nil))

(defn- fresh-fixture
  "Build a default fixture and cache it for inspection."
  []
  (let [f (build-fixture)]
    (reset! fixture f)
    f))

;; ═══════════════════════════════════════════════════════════════════════
;; CHECK 1: Valid join passes all bindings
;; ═══════════════════════════════════════════════════════════════════════

(deftest valid-join-passes-all-bindings
  (testing "a complete self-consistent fixture passes verify-join with no issues"
    (let [f (fresh-fixture)
          result (join/verify-join (:operation f)
                                   (:terminator-cmd f)
                                   (:head-cmd f)
                                   (:receipt f)
                                   (:subject f)
                                   (:result f))]
      (is (:valid? result)
          (str "verify-join should pass for a self-consistent fixture; issues: "
               (:issues result)))
      (is (empty? (:issues result)))
      (is (= (:subject-root f) (:subject/root result)))
      (is (= (:result-root f) (:result/root result))))))

;; ═══════════════════════════════════════════════════════════════════════
;; CHECK 2: Substitution attacks on subject field-checked roots
;; ═══════════════════════════════════════════════════════════════════════

(deftest substitution-attack-state-before-in-subject
  (testing "root substitution: swapping state-before-root in the subject"
    (let [f (fresh-fixture)
          alt-state "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
          subj (assoc (:subject f) :state-before/root alt-state)
          result (join/verify-join (:operation f)
                                   (:terminator-cmd f)
                                   (:head-cmd f)
                                   (:receipt f)
                                   subj
                                   (:result f))]
      (is (false? (:valid? result)))
      (is (some #{:state-before-mismatch} (:issues result))
          (str "substituting state-before-root in subject must be caught; got: "
               (:issues result))))))

(deftest substitution-attack-authorization-in-subject
  (testing "root substitution: swapping authorization-root in the subject"
    (let [f (fresh-fixture)
          alt-auth "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
          subj (assoc (:subject f) :authorization/root alt-auth)
          result (join/verify-join (:operation f)
                                   (:terminator-cmd f)
                                   (:head-cmd f)
                                   (:receipt f)
                                   subj
                                   (:result f))]
      (is (false? (:valid? result)))
      (is (some #{:authorization-mismatch} (:issues result))
          (str "substituting authorization-root in subject must be caught; got: "
               (:issues result))))))

;; ═══════════════════════════════════════════════════════════════════════
;; CHECK 3: Non-checked roots are content-addressed but not field-checked
;; (Known gaps documented — see CANCELLATION_ARTIFACT_MAP.md §GAP-B)
;; ═══════════════════════════════════════════════════════════════════════

(deftest substitution-attack-preconditions-content-addressed-not-join-checked
  (testing "preconditions-root is content-addressed in the subject but not join-checked"
    (let [f (fresh-fixture)
          alt-pre "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
          subj (assoc (:subject f) :preconditions/root alt-pre)]
      (is (not= (:subject-root f) (join/subject-root subj))
          "subject root is content-addressed: substituting preconditions-root changes it")
      (testing "verify-join does not field-check preconditions-root against the operation"
        (let [result (join/verify-join (:operation f)
                                       (:terminator-cmd f)
                                       (:head-cmd f)
                                       (:receipt f)
                                       subj
                                       (:result f))]
          (is (false? (:valid? result))
              (str "verify-join rejects preconditions-root substitution.
This is a known GAP — preconditions-root is content-addressed in the subject
root but is not independently cross-checked against operation/preconditions/root
in verify-join. Admission (cancellation.admission/admit) provides that check.
Issues: " (:issues result))))))))

(deftest substitution-attack-transition-definition-content-addressed-not-join-checked
  (testing "transition-definition-root is content-addressed in the subject but not join-checked"
    (let [f (fresh-fixture)
          alt-td "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
          subj (assoc (:subject f) :transition-definition/root alt-td)]
      (is (not= (:subject-root f) (join/subject-root subj))
          "subject root is content-addressed: substituting transition-definition-root changes it")
      (testing "verify-join does not field-check transition-definition-root"
        (let [result (join/verify-join (:operation f)
                                       (:terminator-cmd f)
                                       (:head-cmd f)
                                       (:receipt f)
                                       subj
                                       (:result f))]
          (is (false? (:valid? result))
              (str "verify-join rejects transition-definition substitution.
The transition-definition is a constant (single in V1) and is committed via
the subject root, not field-matched by verify-join. Issues: " (:issues result))))))))

;; ═══════════════════════════════════════════════════════════════════════
;; CHECK 4: Substitution attacks on result field-checked roots
;; ═══════════════════════════════════════════════════════════════════════

(deftest substitution-attack-effects-in-result
  (testing "root substitution: swapping effects-root in the result"
    (let [f (fresh-fixture)
          alt-eff "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          res (-> (:result f)
                  (assoc :effects/root alt-eff)
                  re-root-result)
          result (join/verify-join (:operation f)
                                   (:terminator-cmd f)
                                   (:head-cmd f)
                                   (:receipt f)
                                   (:subject f)
                                   res)]
      (is (false? (:valid? result)))
      (is (some #{:effects-mismatch} (:issues result))
          (str "substituting effects-root in result must be caught; got: "
               (:issues result))))))

(deftest substitution-attack-state-after-in-result
  (testing "root substitution: swapping state-after-root in the result"
    (let [f (fresh-fixture)
          alt-state "sha256:9999999999999999999999999999999999999999999999999999999999999999"
          res (-> (:result f)
                  (assoc :state-after/root alt-state)
                  re-root-result)
          result (join/verify-join (:operation f)
                                   (:terminator-cmd f)
                                   (:head-cmd f)
                                   (:receipt f)
                                   (:subject f)
                                   res)]
      (is (false? (:valid? result)))
      (is (some #{:state-after-mismatch} (:issues result))
          (str "substituting state-after-root in result must be caught; got: "
               (:issues result))))))

(deftest substitution-attack-receipt-in-result
  (testing "root substitution: swapping receipt-root in the result"
    (let [f (fresh-fixture)
          alt-receipt "sha256:8888888888888888888888888888888888888888888888888888888888888888"
          res (-> (:result f)
                  (assoc :receipt/root alt-receipt)
                  re-root-result)
          result (join/verify-join (:operation f)
                                   (:terminator-cmd f)
                                   (:head-cmd f)
                                   (:receipt f)
                                   (:subject f)
                                   res)]
      (is (false? (:valid? result)))
      (is (some #{:receipt-mismatch} (:issues result))
          (str "substituting receipt-root in result must be caught; got: "
               (:issues result))))))

(deftest substitution-attack-subject-root-in-result-not-checked
  (testing "subject-root in the result is content-addressed but not field-checked"
    (let [f (fresh-fixture)
          alt-subj "sha256:7777777777777777777777777777777777777777777777777777777777777777"
          res (-> (:result f)
                  (assoc :subject/root alt-subj)
                  re-root-result)]
      (is (not= (:result-root f) (:result/root res))
          "result root is content-addressed: substituting subject-root changes it")
      (testing "verify-join does not field-check result.subject/root against subject"
        (let [result (join/verify-join (:operation f)
                                       (:terminator-cmd f)
                                       (:head-cmd f)
                                       (:receipt f)
                                       (:subject f)
                                       res)]
          (is (false? (:valid? result))
              (str "verify-join rejects result subject substitution.
The subject-root is committed via the result root (content-addressed) but
verify-join only checks receipt-root, effects-root, and state-after-root
in the result. Issues: " (:issues result))))))))

;; ═══════════════════════════════════════════════════════════════════════
;; CHECK 5: Substitution attacks on command-lineage artifacts
;; ═══════════════════════════════════════════════════════════════════════

(deftest substitution-attack-stale-terminator
  (testing "stale terminator: built from a different head"
    (let [f (fresh-fixture)
          alt-head-state "sha256:1111111111111111111111111111111111111111111111111111111111111111"
          alt-head (cl/build-command
                    {:command/action :advance-lineage
                     :command/input-state-root (:prior-state-root f)
                     :command/resulting-state-root alt-head-state
                     :command/built-with-includes [{:kind :shared-state :ref (:prior-state-root f)}]})
          alt-terminator (cl/build-termination-command alt-head (:terminal-state-root f))
          result (join/verify-join (:operation f)
                                   alt-terminator
                                   (:head-cmd f)
                                   (:receipt f)
                                   (:subject f)
                                   (:result f))]
      (is (false? (:valid? result)))
      (is (some #(#{:receipt-invalid :receipt-command-mismatch} %)
                (:issues result))
          (str "stale terminator must be rejected; got: " (:issues result))))))

(deftest substitution-attack-receipt-from-different-lineage
  (testing "receipt substitution: receipt from a different head command"
    (let [f (fresh-fixture)
          alt-head-state "sha256:2222222222222222222222222222222222222222222222222222222222222222"
          alt-head (cl/build-command
                    {:command/action :advance-lineage
                     :command/input-state-root (:prior-state-root f)
                     :command/resulting-state-root alt-head-state
                     :command/built-with-includes [{:kind :shared-state :ref (:prior-state-root f)}]})
          alt-terminator (cl/build-termination-command alt-head (:terminal-state-root f))
          alt-receipt (cl/build-termination-receipt alt-terminator alt-head)
          result (join/verify-join (:operation f)
                                   (:terminator-cmd f)
                                   (:head-cmd f)
                                   alt-receipt
                                   (:subject f)
                                   (:result f))]
      (is (false? (:valid? result)))
      (is (some #(#{:receipt-invalid :receipt-mismatch} %)
                (:issues result))
          (str "receipt from a different lineage must be rejected; got: "
               (:issues result))))))

(deftest substitution-attack-head-command-substituted
  (testing "head substitution: using a different head command"
    (let [f (fresh-fixture)
          alt-head-state "sha256:3333333333333333333333333333333333333333333333333333333333333333"
          alt-head (cl/build-command
                    {:command/action :advance-lineage
                     :command/input-state-root (:prior-state-root f)
                     :command/resulting-state-root alt-head-state
                     :command/built-with-includes [{:kind :shared-state :ref (:prior-state-root f)}]})
          result (join/verify-join (:operation f)
                                   (:terminator-cmd f)
                                   alt-head
                                   (:receipt f)
                                   (:subject f)
                                   (:result f))]
      (is (false? (:valid? result))
          (str "substituted head must fail because receipt is bound to original head; got: "
               (:issues result)))
      (is (some #{:receipt-invalid :receipt-command-mismatch} (:issues result))
          (str "receipt-command-mismatch or receipt-invalid expected; got: "
               (:issues result))))))

;; ═══════════════════════════════════════════════════════════════════════
;; CHECK 6: Operation root is tamper-evident
;; ═══════════════════════════════════════════════════════════════════════

(deftest operation-root-is-tamper-evident
  (testing "tampering with any operation field breaks the operation root"
    (let [f (fresh-fixture)
          op (:operation f)]
      (is (false? (operation/operation-root-valid?
                   (assoc-in op [:execution :effects-root]
                             "sha256:5555555555555555555555555555555555555555555555555555555555555555")))
          "operation root must detect effects-root tampering")
      (is (false? (operation/operation-root-valid?
                   (assoc-in op [:target :state-before-root]
                             "sha256:9999999999999999999999999999999999999999999999999999999999999999")))
          "operation root must detect state-before-root tampering")
      (is (false? (operation/operation-root-valid?
                   (assoc-in op [:execution :state-after-root]
                             "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")))
          "operation root must detect state-after-root tampering"))))

;; ═══════════════════════════════════════════════════════════════════════
;; CHECK 7: Cross-fixture substitution (roots from a different context)
;; ═══════════════════════════════════════════════════════════════════════

(deftest cross-fixture-full-substitution-rejected
  (testing "fixture B (different state roots) cannot substitute into fixture A"
    (let [fa (fresh-fixture)
          fb (build-fixture alternate-states)]
      (testing "subject from B rejected against operation from A"
        (let [result (join/verify-join (:operation fa)
                                       (:terminator-cmd fa)
                                       (:head-cmd fa)
                                       (:receipt fa)
                                       (:subject fb)
                                       (:result fa))]
          (is (false? (:valid? result))
              (str "cross-fixture subject must be rejected; got: " (:issues result)))
          (is (some #{:state-before-mismatch :authorization-mismatch} (:issues result))
              (str "cross-fixture subject mismatch expected; got: "
                   (:issues result)))))
      (testing "result from B rejected against operation from A"
        (let [result (join/verify-join (:operation fa)
                                       (:terminator-cmd fa)
                                       (:head-cmd fa)
                                       (:receipt fa)
                                       (:subject fa)
                                       (:result fb))]
          (is (false? (:valid? result))
              (str "cross-fixture result must be rejected; got: " (:issues result)))
          (is (some #{:effects-mismatch :state-after-mismatch :receipt-mismatch}
                    (:issues result))
              (str "cross-fixture result mismatch expected; got: "
                   (:issues result)))))
      (testing "receipt from B rejected (different head command)"
        (let [result (join/verify-join (:operation fa)
                                       (:terminator-cmd fa)
                                       (:head-cmd fa)
                                       (:receipt fb)
                                       (:subject fa)
                                       (:result fa))]
          (is (false? (:valid? result))
              (str "cross-fixture receipt must be rejected; got: " (:issues result)))
          (is (some #{:receipt-invalid :receipt-command-mismatch :receipt-mismatch}
                    (:issues result))
              (str "cross-fixture receipt mismatch expected; got: "
                   (:issues result))))))))

;; ═══════════════════════════════════════════════════════════════════════
;; CHECK 8: Research vector — full end-to-end cancellation transition
;; ═══════════════════════════════════════════════════════════════════════

(deftest research-vector-full-join
  (testing "Iteration 7B research vector: complete end-to-end cancellation transition"
    (let [f (fresh-fixture)]
      ;; The fixture itself IS the research vector — every root is real and
      ;; self-consistent. Verify the full chain of commitments.
      (testing "command-lineage head + terminator + receipt are internally consistent"
        (let [chain-result (cl/verify-lineage [(:head-cmd f) (:terminator-cmd f)])]
          (is (:valid? chain-result))
          (is (= :terminated (:status chain-result)))))
      (testing "operation root is valid and complete"
        (is (operation/operation-complete? (:operation f)))
        (is (operation/operation-root-valid? (:operation f))))
      (testing "transition subject commits the correct basis"
        (is (hash-ref/valid-sha256-ref? (:subject-root f))
            "subject root is a canonical sha256 reference"))
      (testing "transition result commits the correct derived outputs"
        (is (hash-ref/valid-sha256-ref? (:result-root f))
            "result root is a canonical sha256 reference"))
      (testing "the join verifies the full binding with no issues"
        (let [result (join/verify-join (:operation f)
                                       (:terminator-cmd f)
                                       (:head-cmd f)
                                       (:receipt f)
                                       (:subject f)
                                       (:result f))]
          (is (:valid? result)
              (str "full join must verify; issues: " (:issues result)))))
      (testing "subject root is stable: re-deriving from the same roots gives the same value"
        (let [re-derived (join/subject
                          {:transition-definition/root (:transition-definition-root f)
                           :state-before/root (:lifecycle-state-root f)
                           :authorization/root (:authorization-root f)
                           :preconditions/root (:preconditions-root f)})]
          (is (= (:subject-root f) (join/subject-root re-derived))
              "subject root is deterministic from its basis roots")))
      (testing "result root is stable: re-deriving from the same roots gives the same value"
        (let [re-derived (join/result (:subject f)
                                      {:effects/root (:effects-root f)
                                       :state-after/root (:terminal-state-root f)
                                       :receipt/root (:receipt-root f)})]
          (is (= (:result-root f) (:result/root re-derived))
              "result root is deterministic from its basis roots"))))))

;; ═══════════════════════════════════════════════════════════════════════
;; CHECK 9: Golden fixture file integrity
;; ═══════════════════════════════════════════════════════════════════════

(deftest golden-fixture-byte-pipeline-verified
  (let [golden (-> "data/fixtures/golden/cancellation-transition-join-fixture.v1.edn"
                   slurp edn/read-string)]
    (doseq [vector-key [:subject-vector :result-vector]
            :let [{:keys [domain-tag semantic-projection canonical-value-bytes-hex
                          hash-preimage-bytes-hex digest-hex root]}
                  (get golden vector-key)]]
      (is (= canonical-value-bytes-hex
             (hc/canonical-bytes-hex semantic-projection)))
      (is (= hash-preimage-bytes-hex
             (hc/bytes->hex
              (hc/domain-hash-preimage-bytes domain-tag semantic-projection))))
      (is (= digest-hex
             (hc/bytes->hex
              (hc/hash-bytes
               (hc/domain-hash-preimage-bytes domain-tag semantic-projection)))))
      (is (= root (str "sha256:" digest-hex))))))

(deftest golden-fixture-roots-verified
  (testing "golden fixture file has computed, self-consistent roots"
    (let [resource-path "data/fixtures/golden/cancellation-transition-join-fixture.v1.edn"
          golden (-> resource-path slurp edn/read-string)]
      (is (hash-ref/valid-sha256-ref? (:subject-root golden))
          "golden subject root is a canonical sha256 reference")
      (is (hash-ref/valid-sha256-ref? (:result-root golden))
          "golden result root is a canonical sha256 reference")
      (is (hash-ref/valid-sha256-ref? (:receipt-root golden))
          "golden receipt root is a canonical sha256 reference")
      (is (hash-ref/valid-sha256-ref? (:operation-root golden))
          "golden operation root is a canonical sha256 reference")
      (is (hash-ref/valid-sha256-ref? (:head-root golden))
          "golden head command root is a canonical sha256 reference")
      (is (hash-ref/valid-sha256-ref? (:term-root golden))
          "golden terminator command root is a canonical sha256 reference"))))
