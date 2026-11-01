(ns prf.extensions.sew-core-model.adapter
  "Operation-limited PRF model extension for the Solidity Sew Core profile.

   This candidate delegates supported escrow transitions to the full Sew model,
   while rejecting actions and configuration outside the Sew Core scope. It is
   an optional package boundary; the in-tree model dependency is still broad."
  (:require [clojure.string :as str]
            [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.compat :as compat]
            [resolver-sim.protocols.registry :as registry]))

(def protocol-profile :sew-core-v1)

(def solidity-module-identities
  "Symbolic contract identities expected by the pinned Sew Core deployment profile.
   Addresses remain deployment-specific and require bytecode verification outside
   this simulator."
  {:module-registry :module-snapshot-registry
   :resolution-module :default-resolution-module
   :release-strategy :default-release-strategy})

(def supported-actions
  #{"create-escrow"
    "release"
    "sender-cancel"
    "recipient-cancel"
    "raise-dispute"
    "execute-resolution"
    "auto-cancel-disputed"
    "withdraw-escrow"
    "set-paused"})

(def ^:private excluded-feature-key-pattern
  #"(?i)(appeal|kleros|partial|split|slash|stake|resolver.?bond|incentive.?module|pro.?rata|force.?authori[sz]ation|related.?claims|dr3|eae|multi.?l2|escalat|rotation|cancellation.?strategy|custom.?resolver|release.?address|auto.?release.?time|auto.?cancel.?time|resolution.?basis|resolution.?decision|ruling|finality|semantic.?composition|dispute.?level|disputes?.?per.?sender|rate.?limit)")

(def ^:private yield-config-key-pattern #"(?i)yield")
(def ^:private event-parameter-allowlist
  {"create-escrow" #{:token :to :amount :yield-preset :yield_preset
                      :auto-release-time :auto-cancel-time}
   "release" #{:workflow-id :id}
   "sender-cancel" #{:workflow-id :id}
   "recipient-cancel" #{:workflow-id :id}
   "raise-dispute" #{:workflow-id :id}
   "execute-resolution" #{:workflow-id :id :is-release :resolution-hash}
   "auto-cancel-disputed" #{:workflow-id :id}
   "withdraw-escrow" #{:workflow-id :id}
   "set-paused" #{:paused?}})

(defn- truthy-excluded-config? [key value]
  (let [key-name (if (or (keyword? key) (symbol? key) (string? key))
                   (name key)
                   (str key))]
    (let [empty-value? (or (nil? value)
                           (false? value)
                           (and (number? value) (zero? value))
                           (and (or (coll? value) (string? value)) (empty? value)))]
    (cond
      (re-find excluded-feature-key-pattern key-name)
      (not empty-value?)

      (re-find yield-config-key-pattern key-name)
      (not (or empty-value?
               (= :off value)
               (= "off" (some-> value str/lower-case))))

      :else false))))

(defn- excluded-config-paths
  ([value] (excluded-config-paths value []))
  ([value path]
   (cond
     (map? value)
     (mapcat (fn [[key child]]
               (let [child-path (conj path key)]
                 (concat
                  (when (truthy-excluded-config? key child) [child-path])
                  (excluded-config-paths child child-path))))
             value)

     (sequential? value)
     (mapcat (fn [[index child]]
               (excluded-config-paths child (conj path index)))
             (map-indexed vector value))

     :else [])))

(defn- fail!
  [message data]
  (throw (ex-info message (assoc data :protocol-profile protocol-profile))))

(defn validate-event!
  "Reject actions and option fields not represented by the Sew Core profile."
  [event]
  (let [action (compat/canonical-action event)
        params (:params event {})
        excluded (vec (excluded-config-paths params))
        unsupported-params (vec (remove (get event-parameter-allowlist action #{})
                                        (keys params)))]
    (when-not (contains? supported-actions action)
      (fail! "Action is outside the Sew Core profile"
             {:error :sew-core/action-not-supported :action action}))
    (when (seq excluded)
      (fail! "Event parameters enable a feature excluded from Sew Core"
             {:error :sew-core/feature-not-supported
              :action action
              :excluded-configuration-paths excluded}))
    (when (seq unsupported-params)
      (fail! "Event parameters are outside the Sew Core input contract"
             {:error :sew-core/event-parameters-not-supported
              :action action
              :unsupported-parameters unsupported-params}))
    (when (and (= action "set-paused")
               (not (boolean? (get-in event [:params :paused?] true))))
      (fail! "Sew Core pause state must be boolean"
             {:error :sew-core/invalid-pause-state :action action}))
    {:ok true :action action}))

(defn validate-scenario!
  "Validate the fixed Sew Core feature boundary before initializing a world."
  [scenario]
  (when-not (= protocol-profile (:protocol-profile scenario))
    (fail! "Scenario must opt into the Sew Core PRF profile"
           {:error :sew-core/profile-not-selected
            :scenario-profile (:protocol-profile scenario)}))
  (let [configuration (merge (:protocol-params scenario {})
                             (:yield-config scenario {}))
        excluded (vec (excluded-config-paths configuration))
        params (:protocol-params scenario {})
        governance-mode (keyword (or (:governance-mode params) :restricted))
        ;; These are the exact fields consumed by Sew's execution-context
        ;; builder. Accepting nested lookalikes here would validate a scenario
        ;; that the delegate later interprets as unconfigured.
        governance-identity (:governance/identity params)
        guardian-identity (:guardian/identity params)
        dispute-resolver (:dispute-resolver params)
        resolution-module (:resolution-module params)
        release-strategy (:release-strategy params)
        module-identities (:sew-core/module-identities params)
        token-params (:token-params scenario {})]
    (when (seq excluded)
      (fail! "Scenario configuration enables a feature excluded from Sew Core"
             {:error :sew-core/feature-not-supported
              :excluded-configuration-paths excluded}))
    (when-not (= :restricted governance-mode)
      (fail! "Sew Core requires address-bound restricted governance"
             {:error :sew-core/governance-mode-not-supported
              :governance-mode governance-mode}))
    (when-not (and (string? governance-identity) (not (str/blank? governance-identity)))
      (fail! "Sew Core requires a configured Timelock governance identity"
             {:error :sew-core/governance-identity-required}))
    (when-not (and (string? guardian-identity) (not (str/blank? guardian-identity)))
      (fail! "Sew Core requires a configured guardian identity"
             {:error :sew-core/guardian-identity-required}))
    (doseq [[field value] [[:dispute-resolver dispute-resolver]
                           [:resolution-module resolution-module]
                           [:release-strategy release-strategy]]]
      (when-not (and (string? value) (not (str/blank? value)))
        (fail! "Sew Core requires one explicitly configured resolver and its modules"
               {:error :sew-core/required-module-identity
                :field field})))
    (when-not (= solidity-module-identities module-identities)
      (fail! "Sew Core must declare the Solidity profile's default module identities"
             {:error :sew-core/module-identity-mismatch
              :expected solidity-module-identities
              :actual module-identities}))
    (when-not (= 0 (:max-dispute-level params))
      (fail! "Sew Core permits one resolver and no dispute escalation"
             {:error :sew-core/multiple-resolvers-not-supported
              :max-dispute-level (:max-dispute-level params)}))
    (when-not (nil? (:cancellation-strategy params))
      (fail! "Sew Core uses native bilateral cancellation without a strategy"
             {:error :sew-core/cancellation-strategy-not-supported}))
    (when-not (= 0 (:resolver-bond-bps params))
      (fail! "Sew Core does not model resolver staking or bonds"
             {:error :sew-core/resolver-bond-not-supported
              :resolver-bond-bps (:resolver-bond-bps params)}))
    (when-not (or (nil? (:fee-on-transfer token-params))
                  (= 0 (:fee-on-transfer token-params)))
      (fail! "Sew Core supports exact-transfer tokens only"
             {:error :sew-core/non-exact-transfer-token
              :fee-on-transfer (:fee-on-transfer token-params)}))
    (doseq [event (:events scenario [])]
      (validate-event! event))
    {:ok true :protocol-profile protocol-profile}))

(defn- pause-as-guardian
  [context world event]
  (let [agent (get-in context [:agent-index (:agent event)])
        addr (:address agent)
        configured (get-in world [:params :guardian/identity])]
    (if (and (= "guardian" (some-> (:role agent) name str/lower-case))
             (= configured addr))
      {:ok true :world (assoc world :paused? true)
       :extra {:authorization/type :guardian-pause
               :authorization/address addr}}
      {:ok false :error :sew-core/not-guardian
       :detail {:actor-address addr :configured-guardian-address configured}})))

(defn dispatch-action
  "Enforce the Sew Core action boundary, delegating supported behavior to Sew.
   The continuation preserves the base adapter's replay-idempotency handling."
  [context world event continue]
  (let [{:keys [action]} (validate-event! event)]
    (if (and (= action "set-paused")
             (get-in event [:params :paused?] true))
      (pause-as-guardian context world event)
      (continue world))))

(defrecord SewCoreAdapter [delegate]
  proto/SimulationAdapter
  (protocol-id [_] (proto/protocol-id delegate))
  (init-world [_ scenario]
    (validate-scenario! scenario)
    (assoc (proto/init-world delegate scenario) :protocol-profile protocol-profile))
  (build-execution-context [_ agents protocol-params]
    (proto/build-execution-context delegate agents protocol-params))
  (dispatch-action [_ context world event]
    (dispatch-action context world event
                     (fn [world']
                       ;; Sew's core adapter also routes marked worlds through
                       ;; this extension capability. Clear the marker for the
                       ;; single delegated call, then restore it on success so
                       ;; the wrapper cannot recursively invoke itself.
                       (let [result (proto/dispatch-action
                                     delegate context (dissoc world' :protocol-profile) event)]
                         (cond-> result
                           (:world result)
                           (update :world assoc :protocol-profile protocol-profile))))))
  (check-invariants-single [_ world]
    (proto/check-invariants-single delegate world))
  (check-invariants-transition [_ before after]
    (proto/check-invariants-transition delegate before after))
  (world-snapshot [_ world] (proto/world-snapshot delegate world))
  (available-actions [_ world actor]
    (filter (fn [event]
              (try (validate-event! event) true
                   (catch clojure.lang.ExceptionInfo _ false)))
            (proto/available-actions delegate world actor)))
  (resolve-id-alias [_ event aliases]
    (proto/resolve-id-alias delegate event aliases))
  (created-id [_ action extra] (proto/created-id delegate action extra))
  (open-entities [_ world] (proto/open-entities delegate world))
  (project-state [_ world query] (proto/project-state delegate world query))

  proto/TransitionAssurance
  (transition-basis [_ before after event]
    (proto/transition-basis delegate before after event))
  (check-transition-assurance [_ before after event basis]
    (proto/check-transition-assurance delegate before after event basis))

  proto/TemporalDeadlines
  (deadline-for [_ world deadline-kind subject context]
    (proto/deadline-for delegate world deadline-kind subject context))

  proto/EconomicModel
  (adversarial-event? [_ event agent]
    (proto/adversarial-event? delegate event agent))
  (classify-event [_ event result error]
    (proto/classify-event delegate event result error))
  (metric-vocabulary [_] (proto/metric-vocabulary delegate))
  (accum-protocol-metrics [_ metrics tags event accepted? attack? before after]
    (proto/accum-protocol-metrics delegate metrics tags event accepted? attack? before after))
  (summarise-batch [_ outcomes] (proto/summarise-batch delegate outcomes))
  (advisory [_ world request-type context]
    (proto/advisory delegate world request-type context))

  proto/AnalysisModule
  (compute-projection [_ world] (proto/compute-projection delegate world))
  (classify-transition [_ action result] (proto/classify-transition delegate action result))
  (trace-projection [_ result] (proto/trace-projection delegate result))
  (io-projection [_ data target-type]
    (proto/io-projection delegate data target-type))
  (mechanism-property-validators [_]
    (proto/mechanism-property-validators delegate))
  (equilibrium-concept-validators [_]
    (proto/equilibrium-concept-validators delegate))
  (reference-model [_ scenario] (proto/reference-model delegate scenario))

  proto/BatchConflictModel
  (event-conflict-domains [_ world event agent-index]
    (proto/event-conflict-domains delegate world event agent-index))

  proto/ValidatorDescriptorCatalog
  (validator-descriptors [_] (proto/validator-descriptors delegate)))

(def protocol (->SewCoreAdapter sew/protocol))

(defn prepare-scenario
  "Mark a scenario for validation and replay through this core extension."
  [scenario]
  (assoc scenario :protocol-profile protocol-profile))

(defn replay
  "Replay a validated scenario through the operation-limited Sew Core adapter."
  ([scenario] (replay scenario {}))
  ([scenario replay-options]
   (let [scenario (prepare-scenario scenario)]
     (validate-scenario! scenario)
     ((requiring-resolve 'resolver-sim.contract-model.replay/replay-with-protocol)
      protocol scenario replay-options))))

(defn register-capabilities! []
  (registry/register-capability! "sew-v1" :sew-core/dispatch-action dispatch-action)
  (registry/register-capability! "sew-v1" :sew-core/validate-scenario validate-scenario!))

(registry/register-capability-provider! "sew-v1" :sew-core register-capabilities!)
(register-capabilities!)
