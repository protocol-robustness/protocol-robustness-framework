(ns resolver-sim.cancellation.ordinary-admission-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.cancellation.admission :as admission]
            [resolver-sim.cancellation.operation :as operation]
            [resolver-sim.cancellation.party-command :as command]
            [resolver-sim.cancellation.ordinary-planner :as planner]
            [resolver-sim.cancellation.semantic :as semantic]
            [resolver-sim.cancellation.sew-escrow-snapshot :as snapshot]
            [resolver-sim.signed-external-decision :as signed]
            [resolver-sim.support.ed25519 :as keys]))

(defn sha [n] (format "sha256:%064x" n))
(defn request []
  (let [kp (keys/keypair :alice-key)
        s0 {:snapshot/schema snapshot/schema-version :workflow/id "escrow-7" :escrow/sender "alice" :escrow/recipient "bob" :escrow/state :pending :sender/cancellation-status :none :recipient/cancellation-status :agree-to-cancel}
        s (assoc s0 :snapshot/root (snapshot/snapshot-root s0))
        p0 {:policy/schema semantic/policy-schema :policy/can-cancel? true :policy/unilateral-cancel? false}
        p (assoc p0 :policy/root (semantic/policy-root p0))
        bare {:operation/schema operation/schema-version :operation/purpose :cancellation/execution :event/id "cancel-7" :protocol/id :sew
              :target {:kind :sew/escrow :id "escrow-7" :snapshot-root (:snapshot/root s) :state-before-root (sha 1) :lifecycle-head-root (sha 2)}
              :request {:caller/id "alice" :party :sender :action :cancel :requested-at 1}
              :policy {:id :sew/party-cancellation :root (:policy/root p)} :evaluation {:inputs-root (sha 4) :base-decision :ordinary :decision {}}
              :preconditions/root (sha 5) :authorization {:kind :ordinary :root (sha 5)} :execution {:status :applied :effects-root (sha 6) :state-after-root (sha 7)} :operation/root (sha 10)}
        plan (planner/plan {:operation bare :snapshot s :policy p :principal "alice"})
        op0 (-> bare
                (assoc-in [:preconditions/root] (get-in plan [:preconditions :preconditions/root]))
                (assoc-in [:authorization :root] (get-in plan [:preconditions :preconditions/root]))
                (assoc-in [:evaluation :decision :derived-effects-root] (get-in plan [:derived-effects :effects/root])))
        ee0 {:execution-effects/schema semantic/execution-effects-schema :derived-effects/root (get-in plan [:derived-effects :effects/root])}
        ee (assoc ee0 :execution-effects/root (semantic/execution-effects-root ee0))
        op0 (assoc-in op0 [:execution :effects-root] (:execution-effects/root ee))
        op (assoc op0 :operation/root (operation/operation-root (dissoc op0 :operation/root)))
        c0 {:command/schema command/schema-version :command/action :cancel :command/principal "alice" :operation/root (:operation/root op)}
        c (signed/sign-envelope (assoc c0 :command/root (command/command-root c0)) command/decision-domain (:private-key kp) (:key/id kp))
        opaque-roots [(get-in op [:target :state-before-root]) (get-in op [:target :lifecycle-head-root])
                      (get-in op [:evaluation :inputs-root]) (get-in op [:preconditions/root])
                      (get-in op [:execution :state-after-root])]
        artifacts (merge (zipmap opaque-roots (map #(hash-map :artifact/root %) opaque-roots))
                         {(:snapshot/root s) s
                          (:policy/root p) p
                          (get-in plan [:derived-effects :effects/root]) (:derived-effects plan)
                          (:execution-effects/root ee) ee
                          (get-in op [:authorization :root]) {:artifact/root (get-in op [:authorization :root]) :party-command c}})]
    {:operation op :resolve-artifact #(get artifacts %)
     :trust-policy (keys/trust-policy kp command/authority-role :active) :key->principal {:alice-key "alice"}}))

(deftest admits-only-resolved-recomputed-party-cancellation
  (let [result (admission/admit (request))]
    (is (admission/admitted? result))
    (is (admission/admission-root-valid? result))
    (is (true? (get-in result [:verification :roots :snapshot :valid?])))
    (is (re-matches #"sha256:[0-9a-f]{64}" (:admission/root result)))))

(deftest unresolved-operation-root-role-blocks-admission
  (let [r (request)
        result (admission/admit (assoc r :resolve-artifact (constantly nil)))]
    (is (false? (:admitted? result)))
    (is (some #{:snapshot/unresolved} (:blocking-reasons result)))))

(deftest wrong-semantic-role-blocks-admission
  (let [r (request)
        resolve (:resolve-artifact r)
        policy-root (get-in r [:operation :policy :root])
        result (admission/admit (assoc r :resolve-artifact #(if (= % policy-root)
                                                              {:artifact/root policy-root}
                                                              (resolve %))))]
    (is (false? (:admitted? result)))
    (is (some #{:policy/invalid-artifact} (:blocking-reasons result)))))

(deftest injected-admission-boolean-is-not-trusted
  (let [r (request)
        result (admission/admit (assoc r :admitted? true :resolve-artifact (constantly nil)))]
    (is (false? (:admitted? result)))
    (is (= :rejected (:admission/status result)))))

(deftest key-binding-fails-closed
  (let [r (request) result (admission/admit (assoc r :key->principal {:alice-key "mallory"}))]
    (is (= :rejected (:admission/status result)))
    (is (some #{:authority/forbidden} (:blocking-reasons result)))))
