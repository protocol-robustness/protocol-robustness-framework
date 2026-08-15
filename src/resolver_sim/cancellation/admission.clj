(ns resolver-sim.cancellation.admission
  "Admission derives its verdict from resolver-supplied artifacts; it never accepts
   caller-supplied verification or admission booleans."
  (:require [resolver-sim.cancellation.operation :as operation]
            [resolver-sim.cancellation.semantic :as semantic]
            [resolver-sim.cancellation.sew-escrow-snapshot :as snapshot]
            [resolver-sim.cancellation.party-command :as command]
            [resolver-sim.cancellation.ordinary-planner :as planner]
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
         :execution-effects/root :evaluation/root :preconditions/root]))

(defn- resolved-stage [resolver role root valid?]
  (let [artifact (resolve-root resolver root)
        root-valid? (= root (artifact-root artifact))
        semantic-valid? (and artifact (valid? artifact))]
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
               :state-after (get-in operation [:execution :state-after-root])}
        snapshot-stage (resolved-stage resolve-artifact :snapshot (:snapshot roots) snapshot/snapshot-root-valid?)
        policy-stage (resolved-stage resolve-artifact :policy (:policy roots) semantic/policy-root-valid?)
        derived-stage (resolved-stage resolve-artifact :derived-effects (:derived-effects roots) semantic/derived-effects-root-valid?)
        execution-stage (resolved-stage resolve-artifact :execution-effects (:execution-effects roots) semantic/execution-effects-root-valid?)
        opaque-stages (into {}
                            (for [[role root] (select-keys roots [:state-before :lifecycle-head :evaluation-inputs :preconditions :authorization :state-after])]
                              [role (opaque-stage resolve-artifact role root)]))
        stages (merge opaque-stages {:snapshot snapshot-stage :policy policy-stage
                                     :derived-effects derived-stage :execution-effects execution-stage})
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
                                   :authorized? (= :authorized (:decision/classification evaluation))}}
        root-reasons (invalid-stage-reasons stages)
        recomputed-reasons (let [r (:recomputed verification)]
                             (vec (remove nil?
                                          [(when-not (:available? r) :verification/unavailable)
                                           (when (and (:available? r) (not (:preconditions-valid? r))) :operation/preconditions-mismatch)
                                           (when (and (:available? r) (not (:authorization-valid? r))) :operation/authorization-mismatch)
                                           (when (and (:available? r) (not (:derived-effects-valid? r))) :operation/derived-effects-mismatch)
                                           (when (and (:available? r) (not (:execution-effects-valid? r))) :operation/execution-effects-mismatch)
                                           (when (and (:available? r) (not (:authorized? r))) :authority/forbidden)])))
        blocking-reasons (vec (concat
                               (when-not (get-in verification [:operation :valid?]) [:operation/root-invalid])
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
              :admission/reasons blocking-reasons}]
    (assoc base :admission/root (admission-root base))))

(defn admitted? [a] (true? (:admitted? a)))
(defn admission-root-valid? [a]
  (and (= schema-version (:admission/schema a))
       (boolean? (:admitted? a))
       (= (:admission/root a) (admission-root (dissoc a :admission/root)))))
