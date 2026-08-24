(ns resolver-sim.cancellation.admission-transplant-test
  "M-follow-up #4: transplant REJECTION through the public entry point
   (cancellation.admission/admit) — not merely root inequality.

   - A transplanted AGREEMENT (snapshot content edited while declaring the
     original snapshot root) fails the snapshot stage's semantic re-validation
     and blocks admission with a typed stage reason.
   - A transplanted EFFECT (execution-effects bound to a different derived-
     effects root than the operation declares) fails the recomputation match
     and blocks admission with :operation/execution-effects-mismatch."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.cancellation.admission :as admission]
            [resolver-sim.cancellation.operation :as operation]
            [resolver-sim.cancellation.party-command :as command]
            [resolver-sim.cancellation.ordinary-planner :as planner]
            [resolver-sim.cancellation.semantic :as semantic]
            [resolver-sim.cancellation.sew-escrow-snapshot :as snapshot]
            [resolver-sim.signed-external-decision :as signed]
            [resolver-sim.support.ed25519 :as keys]))

(defn- sha [n] (format "sha256:%064x" n))

(defn- build-context
  "Build a complete ordinary cancellation context. `recipient-status`
   selects the counterparty agreement state of the live snapshot."
  [recipient-status]
  (let [kp (keys/keypair :alice-key)
        s0 {:snapshot/schema snapshot/schema-version
            :workflow/id "escrow-t" :escrow/sender "alice" :escrow/recipient "bob"
            :escrow/state :pending
            :sender/cancellation-status :none
            :recipient/cancellation-status recipient-status}
        s (assoc s0 :snapshot/root (snapshot/snapshot-root s0))
        p0 {:policy/schema semantic/policy-schema
            :policy/can-cancel? true :policy/unilateral-cancel? false}
        p (assoc p0 :policy/root (semantic/policy-root p0))
        bare {:operation/schema operation/schema-version
              :operation/purpose :cancellation/execution
              :event/id "cancel-t" :protocol/id :sew
              :target {:kind :sew/escrow :id "escrow-t"
                       :snapshot-root (:snapshot/root s)
                       :state-before-root (sha 1) :lifecycle-head-root (sha 2)}
              :request {:caller/id "alice" :party :sender :action :cancel :requested-at 1}
              :policy {:id :sew/party-cancellation :root (:policy/root p)}
              :evaluation {:inputs-root (sha 4) :base-decision :ordinary :decision {}}
              :preconditions/root (sha 5)
              :authorization {:kind :ordinary :root (sha 5)}
              :execution {:status :applied :effects-root (sha 6) :state-after-root (sha 7)}
              :operation/root (sha 10)}
        plan (planner/plan {:operation bare :snapshot s :policy p :principal "alice"})
        op0 (-> bare
                (assoc-in [:preconditions/root] (get-in plan [:preconditions :preconditions/root]))
                (assoc-in [:authorization :root] (get-in plan [:preconditions :preconditions/root]))
                (assoc-in [:evaluation :decision :derived-effects-root]
                          (get-in plan [:derived-effects :effects/root])))
        ee0 {:execution-effects/schema semantic/execution-effects-schema
             :derived-effects/root (get-in plan [:derived-effects :effects/root])}
        ee (assoc ee0 :execution-effects/root (semantic/execution-effects-root ee0))
        op0 (assoc-in op0 [:execution :effects-root] (:execution-effects/root ee))
        op (assoc op0 :operation/root (operation/operation-root (dissoc op0 :operation/root)))
        c0 {:command/schema command/schema-version :command/action :cancel
            :command/principal "alice" :operation/root (:operation/root op)}
        c (signed/sign-envelope (assoc c0 :command/root (command/command-root c0))
                                command/decision-domain (:private-key kp) (:key/id kp))
        opaque-roots [(get-in op [:target :state-before-root])
                      (get-in op [:target :lifecycle-head-root])
                      (get-in op [:evaluation :inputs-root])
                      (get-in op [:preconditions/root])
                      (get-in op [:execution :state-after-root])]
        artifacts (merge (zipmap opaque-roots (map #(hash-map :artifact/root %) opaque-roots))
                         {(:snapshot/root s) s
                          (:policy/root p) p
                          (get-in plan [:derived-effects :effects/root]) (:derived-effects plan)
                          (:execution-effects/root ee) ee
                          (get-in op [:authorization :root])
                          {:artifact/root (get-in op [:authorization :root]) :party-command c}})]
    {:operation op
     :resolve-artifact #(get artifacts %)
     :trust-policy (keys/trust-policy kp command/authority-role :active)
     :key->principal {(:key/id kp) "alice"}
     :plan plan
     :declared-derived-root (get-in plan [:derived-effects :effects/root])
     :declared-execution-root (:execution-effects/root ee)}))

(deftest honest-context-is-admitted-control
  (let [result (admission/admit (build-context :agree-to-cancel))]
    (is (true? (:admitted? result))
        (str "control must admit; reasons: " (:admission/reasons result)))))

(deftest transplanted-agreement-content-is-rejected-not-just-distinguishable
  ;; Attacker keeps the DECLARED (agreed) snapshot root but serves content
  ;; where the recipient never agreed. The stage validator recomputes the root
  ;; over the actual content and refuses the artifact.
  (let [ctx (build-context :agree-to-cancel)
        declared-root (get-in ctx [:operation :target :snapshot-root])
        tampered (-> ((:resolve-artifact ctx) declared-root)
                     (assoc :recipient/cancellation-status :none))
        orig (:resolve-artifact ctx)
        resolve-artifact (fn [root] (if (= root declared-root) tampered (orig root)))
        result (admission/admit (assoc ctx :resolve-artifact resolve-artifact))]
    (is (false? (:admitted? result))
        "admission must reject edited agreement content declared under the original root")
    (is (contains? (set (:admission/reasons result)) :snapshot/invalid-artifact)
        (str "typed stage reason present; got: " (:admission/reasons result)))))

(deftest transplanted-terminal-effect-binding-is-rejected-by-recomputation
  ;; The operation declares derived-effects root D1 (from ITS snapshot).
  ;; The attacker binds execution-effects to D2 computed over a DIFFERENT
  ;; snapshot state. Stage validation alone passes (schema + reference
  ;; syntax); the recomputation match catches it.
  (let [ctx (build-context :agree-to-cancel)
        alt-snapshot (-> ((:resolve-artifact ctx) (get-in ctx [:operation :target :snapshot-root]))
                         (assoc :recipient/cancellation-status :none)
                         ((fn [s] (assoc s :snapshot/root (snapshot/snapshot-root s)))))
        alt-plan (planner/plan {:operation {:request {:party :sender}
                                            :operation/root (sha 30)}
                                :snapshot alt-snapshot
                                :policy {:policy/schema semantic/policy-schema
                                         :policy/root (get-in ctx [:operation :policy :root])
                                         :policy/can-cancel? true
                                         :policy/unilateral-cancel? false}
                                :principal "alice"})
        alt-derived-root (get-in alt-plan [:derived-effects :effects/root])]
    (is (not= alt-derived-root (:declared-derived-root ctx))
        "setup: the two states really commit different effect roots")
    (let [swapped-ee {:execution-effects/schema semantic/execution-effects-schema
                      :derived-effects/root alt-derived-root
                      :execution-effects/root (:declared-execution-root ctx)}
          orig (:resolve-artifact ctx)
          exec-root (:declared-execution-root ctx)
          resolve-artifact (fn [root] (if (= root exec-root) swapped-ee (orig root)))
          result (admission/admit (assoc ctx :resolve-artifact resolve-artifact))]
      (is (false? (:admitted? result))
          "binding execution-effects to someone else's derived root must block")
      (is (contains? (set (:admission/reasons result)) :operation/execution-effects-mismatch)
          (str "recomputation mismatch typed reason present; got: "
               (:admission/reasons result))))))
