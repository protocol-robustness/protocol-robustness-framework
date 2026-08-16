(ns resolver-sim.cancellation.role-substitution-probe
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.cancellation.admission :as admission]
            [resolver-sim.cancellation.operation :as operation]
            [resolver-sim.cancellation.party-command :as command]
            [resolver-sim.cancellation.ordinary-planner :as planner]
            [resolver-sim.cancellation.semantic :as semantic]
            [resolver-sim.cancellation.sew-escrow-snapshot :as snapshot]
            [resolver-sim.assurance.canonical-force-authorisation :as cfa]
            [resolver-sim.signed-external-decision :as signed]
            [resolver-sim.support.ed25519 :as keys]))

(defn sha [n] (format "sha256:%064x" n))

(defn make-setup [sender-status recipient-status]
  (let [kp (keys/keypair :alice-key)
        s0 {:snapshot/schema snapshot/schema-version
            :workflow/id "escrow-7"
            :escrow/sender "alice" :escrow/recipient "bob"
            :escrow/state :pending
            :sender/cancellation-status sender-status
            :recipient/cancellation-status recipient-status}
        s (assoc s0 :snapshot/root (snapshot/snapshot-root s0))
        p0 {:policy/schema semantic/policy-schema
            :policy/can-cancel? true
            :policy/unilateral-cancel? false}
        p (assoc p0 :policy/root (semantic/policy-root p0))
        bare {:operation/schema operation/schema-version
              :operation/purpose :cancellation/execution
              :event/id "cancel-7" :protocol/id :sew
              :target {:kind :sew/escrow :id "escrow-7"
                       :snapshot-root (:snapshot/root s)
                       :state-before-root (sha 1)
                       :lifecycle-head-root (sha 2)}
              :request {:caller/id "alice" :party :sender :action :cancel :requested-at 1}
              :policy {:id :sew/party-cancellation :root (:policy/root p)}
              :evaluation {:inputs-root (sha 4) :base-decision :ordinary :decision {}}
              :preconditions/root (sha 5)
              :authorization {:kind :ordinary :root (sha 5)}
              :execution {:status :applied :effects-root (sha 6) :state-after-root (sha 7)}
              :operation/root (sha 10)}
        plan (planner/plan {:operation bare :snapshot s :policy p :principal "alice"})
        op0 (assoc-in bare [:preconditions/root] (get-in plan [:preconditions :preconditions/root]))
        op0 (assoc-in op0 [:authorization :root] (get-in plan [:preconditions :preconditions/root]))
        op0 (assoc-in op0 [:evaluation :decision :derived-effects-root]
                      (get-in plan [:derived-effects :effects/root]))
        ee0 {:execution-effects/schema semantic/execution-effects-schema
             :derived-effects/root (get-in plan [:derived-effects :effects/root])}
        ee (assoc ee0 :execution-effects/root (semantic/execution-effects-root ee0))
        op0 (assoc-in op0 [:execution :effects-root] (:execution-effects/root ee))
        op0 (dissoc op0 :operation/root)
        op (assoc op0 :operation/root (operation/operation-root op0))
        c0 {:command/schema command/schema-version
            :command/action :cancel
            :command/principal "alice"
            :operation/root (:operation/root op)}
        c (signed/sign-envelope (assoc c0 :command/root (command/command-root c0))
                                command/decision-domain
                                (:private-key kp) (:key/id kp))
        opaque-roots [(get-in op [:target :state-before-root])
                      (get-in op [:target :lifecycle-head-root])
                      (get-in op [:evaluation :inputs-root])
                      (get-in op [:preconditions/root])
                      (get-in op [:execution :state-after-root])
                      (get-in op [:authorization :root])]
        artifacts (merge
                   (zipmap opaque-roots (map #(hash-map :artifact/root %) opaque-roots))
                   {(:snapshot/root s) s
                    (:policy/root p) p
                    (get-in plan [:derived-effects :effects/root]) (:derived-effects plan)
                    (:execution-effects/root ee) ee
                    (get-in op [:authorization :root])
                    {:artifact/root (get-in op [:authorization :root]) :party-command c}})
        trust-policy (keys/trust-policy kp command/authority-role :active)
        key->principal {:alice-key "alice"}]
    {:kp kp :op op :artifacts artifacts
     :trust-policy trust-policy :key->principal key->principal
     :snapshot s :policy p :plan plan :ee ee :command c}))

(defn base-setup [] (make-setup :none :agree-to-cancel))
(defn nonterminal-setup [] (make-setup :none :none))

(defn rehash-op [op]
  (assoc (dissoc op :operation/root)
         :operation/root (operation/operation-root (dissoc op :operation/root))))

(defn do-admit [ctx op overrides]
  (let [resolve-art (or (:resolve-artifact overrides)
                        (fn [r] (get-in ctx [:artifacts r])))
        extra (dissoc overrides :resolve-artifact)]
    (admission/admit
     (merge {:operation op
             :resolve-artifact resolve-art
             :trust-policy (:trust-policy ctx)
             :key->principal (:key->principal ctx)}
            extra))))

;; ============= CHECK 1: Root-role substitution =============

(deftest check1-state-before-and-state-after-swappable
  (let [ctx (base-setup)
        op (:op ctx)
        artifacts (:artifacts ctx)
        sa-root (get-in op [:execution :state-after-root])
        sb-root (get-in op [:target :state-before-root])
        swapped (-> op
                    (assoc-in [:target :state-before-root] sa-root)
                    (assoc-in [:execution :state-after-root] sb-root))
        swapped-op (rehash-op swapped)
        result (do-admit ctx swapped-op {})]
    (is (true? (:admitted? result))
        (str "FAIL CHECK 1: state-before-root and state-after-root are both opaque stages.\n"
             "Swapping roots passes because both resolve to {:artifact/root root}\n"
             "and only 'artifact-exists && hash==requested' is checked.\n"
             "No domain-specific semantic validator distinguishes state-before from state-after."))
    (is (true? (get-in result [:verification :roots :state-before :valid?]))
        "state-before stage accepts the state-after root")
    (is (true? (get-in result [:verification :roots :state-after :valid?]))
        "state-after stage accepts the state-before root")))

(deftest check1-state-before-and-lifecycle-head-swappable
  (let [ctx (base-setup)
        op (:op ctx)
        root-to-steal (get-in op [:target :state-before-root])
        sub-op (-> op (assoc-in [:target :lifecycle-head-root] root-to-steal))
        sub-op (rehash-op sub-op)
        result (do-admit ctx sub-op {})]
    (is (true? (:admitted? result))
        (str "FAIL CHECK 1: lifecycle-head-root accepts the same root as state-before-root.\n"
             "Both are opaque — no role-specific projection prevents substitution."))
    (is (true? (get-in result [:verification :roots :lifecycle-head :valid?]))
        "lifecycle-head stage accepts the state-before root")))

(deftest check1-snapshot-has-semantic-guard
  (let [ctx (base-setup)
        op (:op ctx)
        s (:snapshot ctx)
        fake-snap {:artifact/root (:snapshot/root s)
                   :snapshot/schema "wrong-schema"
                   :workflow/id "escrow-7"
                   :escrow/sender "alice" :escrow/recipient "bob"
                   :escrow/state :pending
                   :sender/cancellation-status :none
                   :recipient/cancellation-status :agree-to-cancel}
        result (do-admit ctx op {:resolve-artifact
                                 (fn [r] (if (= r (:snapshot/root s))
                                           fake-snap
                                           (get-in ctx [:artifacts r])))})]
    (is (false? (:admitted? result))
        "snapshot stage has semantic guard that rejects wrong-schema artifacts")
    (is (some #{:snapshot/invalid-artifact} (:blocking-reasons result))
        "blocking-reasons contains :snapshot/invalid-artifact")
    (is (true? (get-in result [:verification :roots :policy :valid?]))
        "policy stage (semantic guard) still valid")
    (is (true? (get-in result [:verification :roots :state-before :valid?]))
        "opaque state-before stage still accepts generic artifact")))

(deftest check1-derived-effects-has-semantic-guard
  (let [ctx (base-setup)
        op (:op ctx)
        effects-root (get-in ctx [:plan :derived-effects :effects/root])
        fake-effects {:effects/root effects-root
                      :effects/schema "wrong-schema"
                      :effects/kind :refund-sender
                      :effects/by :sender}
        result (do-admit ctx op {:resolve-artifact
                                 (fn [r] (if (= r effects-root)
                                           fake-effects
                                           (get-in ctx [:artifacts r])))})]
    (is (false? (:admitted? result))
        "derived-effects stage has semantic guard")
    (is (some #{:derived-effects/invalid-artifact} (:blocking-reasons result))
        "blocking-reasons contains :derived-effects/invalid-artifact")))

(deftest check1-policy-has-semantic-guard
  (let [ctx (base-setup)
        op (:op ctx)
        p (:policy ctx)
        fake-policy {:policy/root (:policy/root p)
                     :policy/schema "wrong-schema"
                     :policy/can-cancel? true
                     :policy/unilateral-cancel? false}
        result (do-admit ctx op {:resolve-artifact
                                 (fn [r] (if (= r (:policy/root p))
                                           fake-policy
                                           (get-in ctx [:artifacts r])))})]
    (is (false? (:admitted? result))
        "policy stage has semantic guard")
    (is (some #{:policy/invalid-artifact} (:blocking-reasons result))
        "blocking-reasons contains :policy/invalid-artifact")))

;; ============= CHECK 2: Nonterminal-consent distinction =============

(deftest check2-nonterminal-effect-kind
  (let [ctx (nonterminal-setup)
        s (:snapshot ctx)
        p (:policy ctx)
        plan (planner/plan {:operation (:op ctx) :snapshot s :policy p :principal "alice"})
        effects (:derived-effects plan)]
    (is (= :none (:sender/cancellation-status s))
        "sender has not yet agreed")
    (is (= :none (:recipient/cancellation-status s))
        "recipient has not yet agreed")
    (is (= :record-party-agreement (:effects/kind effects))
        (str "one party requesting, neither has pre-agreed:\n"
             "effect kind is :record-party-agreement (NONTERMINAL)"))
    (is (not= :refund-sender (:effects/kind effects))
        (str "FAIL CHECK 2: nonterminal consent (single party, no pre-agreement,\n"
             "no unilateral policy) must produce :record-party-agreement,\n"
             "never :refund-sender"))))

(deftest check2-terminal-effect-kind
  (let [ctx (base-setup)
        s (:snapshot ctx)
        plan (planner/plan {:operation (:op ctx) :snapshot s :policy (:policy ctx) :principal "alice"})
        effects (:derived-effects plan)]
    (is (= :agree-to-cancel (:recipient/cancellation-status s))
        "recipient has already agreed")
    (is (= :refund-sender (:effects/kind effects))
        "both parties effectively agreed: effect kind is :refund-sender (TERMINAL)")
    (is (not= :record-party-agreement (:effects/kind effects))
        "terminal should not be :record-party-agreement")))

(deftest check2-effect-roots-differ
  (let [ctx (base-setup)
        s (:snapshot ctx)
        p (:policy ctx)
        op (:op ctx)
        nt-snap (-> s (assoc :sender/cancellation-status :none) (assoc :recipient/cancellation-status :none))
        nt-snap (assoc nt-snap :snapshot/root (snapshot/snapshot-root (dissoc nt-snap :snapshot/root)))
        nt-plan (planner/plan {:operation op :snapshot nt-snap :policy p :principal "alice"})
        nt-effects (:derived-effects nt-plan)
        both-agree (-> s
                       (assoc :sender/cancellation-status :agree-to-cancel)
                       (assoc :recipient/cancellation-status :agree-to-cancel))
        both-agree (assoc both-agree :snapshot/root
                          (snapshot/snapshot-root (dissoc both-agree :snapshot/root)))
        t-plan (planner/plan {:operation op :snapshot both-agree :policy p :principal "alice"})
        t-effects (:derived-effects t-plan)]
    (is (not= (:effects/root nt-effects) (:effects/root t-effects))
        (str "FAIL CHECK 2: nonterminal and terminal effect roots must differ.\n"
             "Both include :effects/kind in the domain hash, so they cannot collide."))
    (is (= :record-party-agreement (:effects/kind nt-effects))
        "nonterminal effects kind is :record-party-agreement")
    (is (= :refund-sender (:effects/kind t-effects))
        "terminal effects kind is :refund-sender")))

(deftest check2-canonical-cancellation-taxonomy
  (is (true? (cfa/cancellation-decision-required? :decide-cancel-valid-authorisation))
      "canonical cancellation decision taxonomy includes :decide-cancel-valid-authorisation")
  (doseq [op [:submit-cancel-request :expire-at-deadline :deterministic-invalidation
              :reject-post-cutpoint-cancellation :execute-certified-cancellation
              :apply-deterministic-fallback]]
    (is (false? (cfa/cancellation-decision-required? op))
        (str "FAIL CHECK 2: " (name op) " is deterministic, not canonical")))
  (is (false? (cfa/cancellation-decision-required? :record-party-agreement))
      (str "FAIL CHECK 2: :record-party-agreement is NOT a canonical cancellation\n"
           "decision — it is a deterministic nonterminal consent, never canonical-cancellation.v1"))
  (is (false? (cfa/cancellation-decision-required? :refund-sender))
      ":refund-sender is an effect kind, not a decision taxonomy entry"))

(deftest check2-admitted-nonterminal-excludes-terminal
  (let [ctx (nonterminal-setup)
        op (:op ctx)
        result (do-admit ctx op {})
        rec (get-in result [:verification :recomputed])
        nt-root (get-in ctx [:plan :derived-effects :effects/root])
        s (:snapshot ctx)
        p (:policy ctx)
        both-agree (-> s
                       (assoc :sender/cancellation-status :agree-to-cancel)
                       (assoc :recipient/cancellation-status :agree-to-cancel))
        both-agree (assoc both-agree :snapshot/root
                          (snapshot/snapshot-root (dissoc both-agree :snapshot/root)))
        t-plan (planner/plan {:operation op :snapshot both-agree :policy p :principal "alice"})
        t-root (get-in t-plan [:derived-effects :effects/root])]
    (is (true? (:admitted? result))
        "nonterminal consent is admitted")
    (is (true? (:derived-effects-valid? rec))
        (str "FAIL CHECK 2: admitted nonterminal consent's recomputed root matches\n"
             "the declared :record-party-agreement root (not terminal)."))
    (is (not= nt-root t-root)
        (str "FAIL CHECK 2: terminal and nonterminal roots differ —\n"
             "admission does not conflate them."))))

(deftest check2-unilateral-cancel-is-terminal
  (let [ctx (base-setup)
        p (-> (:policy ctx)
              (assoc :policy/unilateral-cancel? true)
              (assoc :policy/can-cancel? true))
        p (assoc p :policy/root (semantic/policy-root (dissoc p :policy/root)))
        s (:snapshot ctx)
        op (:op ctx)
        plan (planner/plan {:operation op :snapshot s :policy p :principal "alice"})
        effects (:derived-effects plan)]
    (is (= :refund-sender (:effects/kind effects))
        "unilateral-cancel is terminal: :refund-sender")
    (is (not= :record-party-agreement (:effects/kind effects))
        "unilateral cancel is NOT nonterminal")))

;; ============= CHECK 3: Admission root excludes resolver/runtime provenance =============

(deftest check3-root-stable-across-resolver-objects
  (let [ctx (base-setup)
        op (:op ctx)
        artifacts (:artifacts ctx)
        r1 (do-admit ctx op {})
        r2 (do-admit ctx op {:resolve-artifact (fn [r] (get artifacts r))})
        r3 (do-admit ctx op {:resolve-artifact
                             (let [tbl artifacts] (fn [r] (get tbl r)))})]
    (is (= (:admission/root r1) (:admission/root r2))
        (str "FAIL CHECK 3: admission root changed when resolver function changed\n"
             "but returns identical artifacts. Root must depend only on resolved\n"
             "semantic evidence, not resolver identity."))
    (is (= (:admission/root r1) (:admission/root r3))
        "admission root stable across different resolver closures")
    (is (= (:admitted? r1) (:admitted? r2))
        "admission verdict stable across resolver identity")))

(deftest check3-no-runtime-provenance-fields
  (let [ctx (base-setup)
        op (:op ctx)
        result (do-admit ctx op {})
        provenance-keys [:resolver/id :resolver-identity :resolver/lookup-order
                         :filesystem/path :filesystem/location :db/id :timestamp
                         :uuid :run-id :hostname :resolver/response-window
                         :resolution/time :resolution/at :resolver/name
                         :resolver/implementation :resolver/version
                         :run/id :run-name :process-id :pid :thread-name]]
    (doseq [k provenance-keys]
      (is (not (contains? result k))
          (str "FAIL CHECK 3: admission record contains runtime field: " k)))))

(deftest check3-authority-has-no-resolver-identity
  (let [ctx (base-setup)
        op (:op ctx)
        authority (:authority (do-admit ctx op {}))]
    (is (contains? authority :command-root))
    (is (contains? authority :verified?))
    (is (contains? authority :principal))
    (is (contains? authority :reason))
    (doseq [k [:resolver/id :resolver/name :resolver/implementation :hostname :uuid :run-id]]
      (is (not (contains? authority k))
          (str "FAIL CHECK 3: authority stage contains: " k)))))

(deftest check3-stages-exclude-resolver-identity
  (let [ctx (base-setup)
        op (:op ctx)
        stages (get-in (do-admit ctx op {}) [:verification :roots])
        bad [:resolver/id :resolver/name :filesystem/path :db/id :hostname :uuid :run-id]]
    (doseq [[role stage] stages
            provenance-keys bad]
      (is (not (contains? stage provenance-keys))
          (str "FAIL CHECK 3: stage " (name role) " contains forbidden " (name provenance-keys))))))

;; ============= CHECK 4: Compatibility fields are projections only =============

(deftest check4-status-derived-from-admitted
  (let [ctx (base-setup)
        op (:op ctx)
        result (do-admit ctx op {})]
    (is (= (if (:admitted? result) :admitted :rejected) (:admission/status result))
        ":admission/status matches :admitted?"))
  (testing "Caller-supplied :admission/status at the admit boundary is ignored"
    (let [ctx (base-setup)
          op (:op ctx)
          result (do-admit ctx op {:admission/status :rejected})]
      (is (= :admitted (:admission/status result))
          (str "FAIL CHECK 4: caller-supplied :admission/status at the admit boundary\n"
               "is ignored — status is derived from :admitted?, not from input")))))

(deftest check4-reasons-derived-from-blocking-reasons
  (let [ctx (base-setup)
        op (:op ctx)
        result (do-admit ctx op {})]
    (is (= (:blocking-reasons result) (:admission/reasons result))
        ":admission/reasons equals :blocking-reasons (derived projection)")))

(deftest check4-injected-admitted-boolean-ignored
  (let [ctx (base-setup)
        op (:op ctx)
        result (do-admit ctx op {:admitted? true
                                 :resolve-artifact (constantly nil)})]
    (is (false? (:admitted? result))
        (str "FAIL CHECK 4: caller-supplied :admitted? true is NOT trusted.\n"
             "With nothing resolvable, admission is rejected."))
    (is (= :rejected (:admission/status result))
        ":admission/status is :rejected, not :admitted")))

(deftest check4-injected-compatibility-fields-ignored
  (let [ctx (base-setup)
        op (:op ctx)
        result (do-admit ctx op {:admission/status :rejected
                                 :admission/reasons [:fake/bogus-blocker]})]
    (is (true? (:admitted? result))
        (str "FAIL CHECK 4: caller-supplied :admission/status/:admission/reasons\n"
             "at the boundary are ignored — operation still admits on valid evidence"))
    (is (empty? (:admission/reasons result))
        "caller-supplied :admission/reasons ignored — derived reasons empty")
    (is (= :admitted (:admission/status result))
        "caller-supplied :admission/status ignored — derived status :admitted")))

(deftest check4-compat-fields-are-projections
  (let [ctx (base-setup)
        op (:op ctx)
        result (do-admit ctx op {})]
    (is (= (:blocking-reasons result) (:admission/reasons result))
        "reasons projection consistent")
    (is (= (if (:admitted? result) :admitted :rejected) (:admission/status result))
        "status projection consistent")
    (is (admission/admission-root-valid? result)
        "admission/root recomputes from base including projected fields")))

(deftest check4-tampering-detected
  (let [ctx (base-setup)
        op (:op ctx)
        result (do-admit ctx op {})]
    (is (false? (admission/admission-root-valid?
                 (assoc result :admission/status :rejected)))
        (str "FAIL CHECK 4: tampering :admission/status breaks the root —\n"
             "this field IS part of the hash, so it must be a derived projection,\n"
             "never an independent input. admit constructs it correctly;\n"
             "manual mismatch is detected."))
    (is (false? (admission/admission-root-valid?
                 (assoc result :admission/reasons [:fake/bogus-blocker])))
        "tampering :admission/reasons breaks the root — confirms projection binding")))
