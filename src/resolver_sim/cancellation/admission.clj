(ns resolver-sim.cancellation.admission
  "Admission derives its verdict from resolver-supplied artifacts; it never accepts
   caller-supplied verification or admission booleans."
  (:require [resolver-sim.cancellation.operation :as operation]
            [resolver-sim.cancellation.semantic :as semantic]
            [resolver-sim.cancellation.sew-escrow-snapshot :as snapshot]
            [resolver-sim.cancellation.party-command :as command]
            [resolver-sim.cancellation.ordinary-planner :as planner]
            [resolver-sim.cancellation.state-roles :as roles]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def schema-version "cancellation-operation-admission.v1")
(def admission-domain "CANCELLATION_OPERATION_ADMISSION_V1")

(defn admission-root [a]
  (hash-ref/sha256-ref (hc/domain-hash admission-domain (dissoc a :admission/root))))

(defn- resolve-root [resolver root]
  (try
    (when (and (fn? resolver) (hash-ref/valid-sha256-ref? root))
      (resolver root))
    (catch Exception _ nil)))

(defn- artifact-root [artifact]
  (some #(get artifact %)
        [:artifact/root :snapshot/root :policy/root :effects/root
         :execution-effects/root :evaluation/root :preconditions/root
         :cancellation-state-before/root :cancellation-lifecycle-head/root]))

(defn- resolved-stage [resolver role root valid?]
  (let [artifact (resolve-root resolver root)
        root-valid? (= root (artifact-root artifact))
        validation (and artifact (valid? artifact))
        semantic-valid? (if (map? validation) (:valid? validation) (boolean validation))]
    {:role role
     :root root
     :resolved? (some? artifact)
     :root-valid? root-valid?
     :semantic-valid? semantic-valid?
     :valid? (and root-valid? semantic-valid?)}))

(defn- opaque-stage [resolver role root]
  (resolved-stage resolver role root (constantly true)))

(defn- invalid-stage-reasons [stages]
  (->> stages
       (keep (fn [[role {:keys [resolved? root-valid? semantic-valid?]}]]
               (cond
                 (not resolved?) (keyword (name role) "unresolved")
                 (not root-valid?) (keyword (name role) "root-mismatch")
                 (not semantic-valid?) (keyword (name role) "invalid-artifact"))))
       vec))

(defn admit
  "Admit an operation only from artifacts resolved by `:resolve-artifact`.

   `:operation` remains the asserted statement being examined. Every root declared
   by that statement is resolved independently. Root-only state commitments use an
   artifact carrying `:artifact/root`; semantic roles additionally validate their
   existing semantic artifact schemas."
  [{:keys [operation resolve-artifact trust-policy key->principal]}]
  (let [roots {:snapshot (get-in operation [:target :snapshot-root])
               :state-before (get-in operation [:target :state-before-root])
               :lifecycle-head (get-in operation [:target :lifecycle-head-root])
               :policy (get-in operation [:policy :root])
               :evaluation-inputs (get-in operation [:evaluation :inputs-root])
               :derived-effects (get-in operation [:evaluation :decision :derived-effects-root])
               :preconditions (get-in operation [:preconditions/root])
               :authorization (get-in operation [:authorization :root])
               :execution-effects (get-in operation [:execution :effects-root])
               :state-after (get-in operation [:execution :state-after-root])
               :state-transition-binding (get-in operation [:execution :state-after-root])}
        snapshot-stage (resolved-stage resolve-artifact :snapshot (:snapshot roots) snapshot/snapshot-root-valid?)
        policy-stage (resolved-stage resolve-artifact :policy (:policy roots) semantic/policy-root-valid?)
        derived-stage (resolved-stage resolve-artifact :derived-effects (:derived-effects roots) semantic/derived-effects-root-valid?)
        execution-stage (resolved-stage resolve-artifact :execution-effects (:execution-effects roots) semantic/execution-effects-root-valid?)
        v2? (= operation/schema-v2 (:operation/schema operation))
        state-before-stage (if v2?
                             (resolved-stage resolve-artifact :state-before (:state-before roots) roles/validate-state-before)
                             (opaque-stage resolve-artifact :state-before (:state-before roots)))
        lifecycle-head-stage (if v2?
                               (resolved-stage resolve-artifact :lifecycle-head (:lifecycle-head roots) roles/validate-lifecycle-head)
                               (opaque-stage resolve-artifact :lifecycle-head (:lifecycle-head roots)))
        opaque-stages (merge {:state-before state-before-stage :lifecycle-head lifecycle-head-stage}
                             (into {}
                                   (for [[role root] (select-keys roots [:evaluation-inputs :preconditions :authorization :state-after])]
                                     [role (opaque-stage resolve-artifact role root)])))
        stages (merge opaque-stages {:snapshot snapshot-stage :policy policy-stage
                                     :derived-effects derived-stage :execution-effects execution-stage})
        resolved-state-before (get stages :state-before)
        resolved-execution-effects (get stages :execution-effects)
        resolved-state-after (get stages :state-after)
        state-transition-binding
        (let [state-before-valid? (:valid? resolved-state-before)
              execution-effects-valid? (:valid? resolved-execution-effects)
              state-after-valid? (:valid? resolved-state-after)
              state-before-root (:root resolved-state-before)
              execution-effects-root (:root resolved-execution-effects)
              state-after-root (:root resolved-state-after)]
          {:stage :state-transition-binding
           :state-before-root state-before-root
           :execution-effects-root execution-effects-root
           :state-after-root state-after-root
           :state-before-valid? (boolean state-before-valid?)
           :execution-effects-valid? (boolean execution-effects-valid?)
           :state-after-valid? (boolean state-after-valid?)
           :binding-valid? (boolean
                            (and state-before-valid?
                                 execution-effects-valid?
                                 state-after-valid?
                                 (some? state-before-root)
                                 (some? execution-effects-root)
                                 (some? state-after-root)))
           :reason :transition/unimplemented
           :detail "state-before + execution-effects -> state-after transition derivation is not implemented in this repository; the stage exists as a placeholder slot for when the cancellation transition kernel is defined (see O1)."})
        resolved-snapshot (resolve-root resolve-artifact (:snapshot roots))
        resolved-policy (resolve-root resolve-artifact (:policy roots))
        authorization-artifact (resolve-root resolve-artifact (:authorization roots))
        party-command (:party-command authorization-artifact)
        authority (let [verification (command/verify-command party-command trust-policy key->principal)]
                    {:stage :authority
                     :command-root (:authorization roots)
                     :verified? (boolean (:valid? verification))
                     :principal (:principal verification)
                     :reason (:reason verification)})
        recomputed (when (and (:valid? snapshot-stage) (:valid? policy-stage) (:verified? authority))
                     (planner/plan {:operation operation :snapshot resolved-snapshot
                                    :policy resolved-policy :principal (:principal authority)}))
        derived (:derived-effects recomputed)
        evaluation (:evaluation recomputed)
        verification {:operation {:valid? (operation/operation-root-valid? operation)
                                  :root (:operation/root operation)}
                      :roots stages
                      :recomputed {:available? (some? recomputed)
                                   :preconditions-valid? (= (:preconditions roots) (get-in recomputed [:preconditions :preconditions/root]))
                                   :authorization-valid? (= (:authorization roots) (get-in recomputed [:preconditions :preconditions/root]))
                                   :derived-effects-valid? (= (:derived-effects roots) (:effects/root derived))
                                   :execution-effects-valid? (= (:derived-effects/root (resolve-root resolve-artifact (:execution-effects roots)))
                                                                (:effects/root derived))
                                   :authorized? (= :authorized (:decision/classification evaluation))}
                      :state-transition-binding {:available? (some? state-transition-binding)
                                                 :binding-valid? (:binding-valid? state-transition-binding)
                                                 :reason (:reason state-transition-binding)
                                                 :verified? (and (:binding-valid? state-transition-binding)
                                                                 (not= :transition/unimplemented (:reason state-transition-binding)))}
                      :state-after-integrity {:available? (boolean (:valid? (get stages :state-after)))
                                              :root-valid? (boolean (:valid? (get stages :state-after)))
                                              :verified? (boolean (:valid? (get stages :state-after)))}}
        root-reasons (invalid-stage-reasons stages)
        recomputed-reasons (let [r (:recomputed verification)]
                             (vec (remove nil?
                                          [(when-not (:available? r) :verification/unavailable)
                                           (when (and (:available? r) (not (:preconditions-valid? r))) :operation/preconditions-mismatch)
                                           (when (and (:available? r) (not (:authorization-valid? r))) :operation/authorization-mismatch)
                                           (when (and (:available? r) (not (:derived-effects-valid? r))) :operation/derived-effects-mismatch)
                                           (when (and (:available? r) (not (:execution-effects-valid? r))) :operation/execution-effects-mismatch)
                                           (when (and (:available? r) (not (:authorized? r))) :authority/forbidden)])))
        lifecycle-join-valid? (or (not v2?)
                                  (let [head (resolve-root resolve-artifact (:lifecycle-head roots))]
                                    (= (:cancellation/represented-state-before-root head)
                                       (:state-before roots))))
        blocking-reasons (vec (concat
                               (when-not (get-in verification [:operation :valid?]) [:operation/root-invalid])
                               (when-not lifecycle-join-valid? [:lifecycle-head/state-before-mismatch])
                               root-reasons
                               (when-not (:verified? authority) [:authority/forbidden])
                               recomputed-reasons))
        admitted? (empty? blocking-reasons)
        base {:admission/schema schema-version
              :operation/root (:operation/root operation)
              :authority authority
              :snapshot snapshot-stage
              :verification verification
              :blocking-reasons blocking-reasons
              :admitted? admitted?
              :admission/status (if admitted? :admitted :rejected)
              :admission/reasons blocking-reasons
              :state-after-integrity (get-in verification [:state-after-integrity])
              :state-transition-binding (get-in verification [:state-transition-binding])}]
    (assoc base :admission/root (admission-root base))))

(defn admitted? [a] (true? (:admitted? a)))
(defn admission-root-valid? [a]
  (and (= schema-version (:admission/schema a))
       (boolean? (:admitted? a))
       (= (:admission/root a) (admission-root (dissoc a :admission/root)))))
