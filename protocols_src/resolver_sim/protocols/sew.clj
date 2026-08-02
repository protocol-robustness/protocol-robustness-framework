(ns resolver-sim.protocols.sew
  "SewProtocol — Sew reference adapter/reference implementation of tiered protocol interfaces.

   Boundary note:
   - This namespace is the canonical Sew adapter for the framework substrate.
   - Reusable adapter contracts are defined in protocols/protocol.clj.
   - Domain semantics implemented here remain Sew-specific unless explicitly
     promoted via adapter-facing contracts."
  (:require [resolver-sim.protocols.protocol             :as proto]
            [resolver-sim.protocols.sew.types            :as t]
            [resolver-sim.protocols.sew.diff             :as diff]
            [resolver-sim.protocols.sew.state-machine    :as sm]
            [resolver-sim.protocols.sew.lifecycle        :as lc]
            [resolver-sim.protocols.sew.resolution       :as res]
            [resolver-sim.protocols.sew.registry         :as reg]
            [resolver-sim.protocols.sew.accounting       :as acct]
            [resolver-sim.accounting.held-adjustment     :as held-adjustment]
            [resolver-sim.protocols.sew.authority        :as auth]
            [resolver-sim.protocols.sew.trace-metadata   :as meta]
            [resolver-sim.protocols.sew.invariants       :as inv]
            [resolver-sim.protocols.sew.related-claims   :as rc]
            [resolver-sim.protocols.sew.projection       :as sew-proj]
            [resolver-sim.protocols.sew.equilibrium      :as sew-eq]
            [resolver-sim.protocols.sew.advisory         :as sew-adv]
            [resolver-sim.db.sew                         :as sew-db]
            [resolver-sim.db.temporal                    :as temporal]
            [resolver-sim.protocols.sew.yield.policy     :as yield-policy]
            [resolver-sim.util.attribution            :as attr]
            [resolver-sim.evidence.capture             :as cap]
            [resolver-sim.contract-model.replay          :as replay]
            [resolver-sim.contract-model.idempotency     :as idem]
            [resolver-sim.protocols.sew.snapshot         :as sew-snapshot]
            [resolver-sim.yield.protocols                :as yield-proto]
            [resolver-sim.yield.module                   :as yield-module]
            [resolver-sim.yield.risk                     :as yield-risk]
            [resolver-sim.protocols.sew.compat           :as compat]
            [resolver-sim.protocols.sew.action-context   :as actx]
            [resolver-sim.benchmark.researcher-force-authorisation :as researcher-fa]
            [resolver-sim.benchmark.research-assignment :as research-assignment]
            [resolver-sim.yield.expectations             :as yield-exp]
            [resolver-sim.yield.evidence                 :as yield-evi]
            [resolver-sim.time.context                   :as time-ctx]
            [resolver-sim.hash.canonical                 :as hash]
            [resolver-sim.hash.reference                 :as hash-ref]))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(def ^:private max-safe-amount 922337203685477)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- sender-only-release [world workflow-id caller]
  (let [et       (t/get-transfer world workflow-id)
        settings (t/get-settings world workflow-id)]
    {:allowed? (or (= caller (:from et))
                   (= caller (:release-address settings)))
     :reason-code 0}))

(defn force-authorisation-held-scope
  "Build the canonical Sew held-adjustment scope for a planned individual
   force-authorisation. The consensus bridge uses this before grant, while the
   normal grant records the same projection. `parameter-attribution` must be
   the optional validated pair, or {}."
  [auth-id escrow workflow-id is-release parameter-attribution]
  (let [recipient (if is-release (:to escrow) (:from escrow))
        reason (if is-release :force-authorised-release :force-authorised-refund)]
    (held-adjustment/project-held-adjustment-scope
     (merge {:authorization/id auth-id
             :authorization/type :force-authorisation
             :held/direction :out
             :token (:token escrow)
             :amount (:amount-after-fee escrow)
             :held/account :escrow-principal
             :held/position-id [:held/position (:token escrow) :escrow-principal workflow-id]
             :owner/address recipient
             :held/reason reason
             :held/workflow-id workflow-id}
            parameter-attribution))))

(defn qualify-sew-scope-hash
  "Convert the canonical internal Sew scope digest to its external SHA-256
   reference. Bare digests are retained only inside legacy Sew records."
  [bare-digest]
  (when-not (and (string? bare-digest) (re-matches #"[0-9a-f]{64}" bare-digest))
    (throw (ex-info "Invalid bare Sew scope digest" {:reason :invalid-sew-scope-hash})))
  (str "sha256:" bare-digest))

(defn strip-sew-scope-hash-reference
  "Validate and remove the external SHA-256 prefix from a Sew scope reference."
  [reference]
  (when-not (and (string? reference) (re-matches #"sha256:[0-9a-f]{64}" reference))
    (throw (ex-info "Invalid qualified Sew scope reference" {:reason :invalid-sew-scope-reference})))
  (subs reference (count "sha256:")))

(defn public-force-authorisation-scope-ref
  "Compatibility name for `qualify-sew-scope-hash`."
  [scope-hash]
  (qualify-sew-scope-hash scope-hash))

(defn- has-active-dispute-for-resolver?
  [world resolver-addr]
  (boolean
   (some (fn [[_ et]]
           (and (= :disputed (:escrow-state et))
                (= resolver-addr (:dispute-resolver et))))
         (:escrow-transfers world))))

(defn- governance-actor?
  [agent]
  (let [r (or (:role agent) (:type agent) "")]
    (contains? #{"governance" :governance} r)))

(defn normalize-governance-identity
  "Canonicalize the :governance/identity configuration. Accepts a string address
   or a map {:governance/address <addr>}. Returns nil when not configured.
   Throws on malformed configuration (before any action is attempted)."
  [identity]
  (cond
    (nil? identity) nil
    (string? identity) {:governance/address identity}
    (map? identity)
    (let [addr (:governance/address identity)]
      (if (and (string? addr) (not= "" addr))
        {:governance/address addr}
        (throw (ex-info "malformed :governance/identity — missing non-empty :governance/address"
                        {:type :invalid-governance-identity
                         :governance-identity identity}))))
    :else
    (throw (ex-info "malformed :governance/identity — expected string address or map"
                    {:type :invalid-governance-identity
                     :governance-identity identity}))))

(defn- governance-check
  "Build a reason-carrying governance check from the execution context.

   Returns {:ok true :authentication-mode <kw> :configured-governance-address <addr?>
            :address-bound? <bool> :basis <kw>} on success, or
   {:ok false :error <kw> :detail {...}} on failure.

   Modes:
     :restricted (default) — requires a configured :governance/identity record.
        An actor is governance iff its resolved role/type is governance AND its
        resolved address equals the configured governance address. A missing
        identity fails closed with :governance-identity-not-configured (never a
        silent role-only fallback).
     :open / :full         — any resolved actor (explicitly open).
     :legacy               — explicit role-only governance (weaker; recorded in
        provenance as :scenario-declared-role).
   Any other mode fails closed with :unsupported-governance-mode."
  [context]
  (let [mode (keyword (or (get context :governance-mode) :restricted))
        identity (normalize-governance-identity (:governance-identity context))
        configured-addr (get-in identity [:governance/address])]
    (case mode
      :restricted
      (fn [addr agent]
        (cond
          (nil? configured-addr)
          {:ok false :error :governance-identity-not-configured
           :detail {:mode :restricted}}

          (not (governance-actor? agent))
          {:ok false :error :not-governance
           :detail {:mode :restricted :actor-address addr :address-bound? true}}

          (not= configured-addr addr)
          {:ok false :error :not-governance
           :detail {:mode :restricted :actor-address addr
                    :configured-governance-address configured-addr
                    :address-bound? true}}

          :else
          {:ok true :authentication-mode :address-bound
           :configured-governance-address configured-addr
           :address-bound? true
           :basis :scenario-configured-address-binding}))

      (:open :full)
      (fn [_addr _agent]
        {:ok true :authentication-mode :open
         :configured-governance-address configured-addr
         :address-bound? false
         :basis :scenario-declared-open})

      :legacy
      (fn [addr agent]
        (if (governance-actor? agent)
          {:ok true :authentication-mode :role-declared
           :configured-governance-address configured-addr
           :address-bound? false
           :basis :scenario-declared-role}
          {:ok false :error :not-governance
           :detail {:mode :legacy :actor-address addr}}))

      (fn [_addr _agent]
        {:ok false :error :unsupported-governance-mode
         :detail {:mode mode}}))))

(defn- governance-authorization-provenance
  [context event addr auth-info]
  (let [action-name (.replace (name (:action event)) "_" "-")
        mode (keyword (or (get context :governance-mode) :restricted))
        configured-addr (:configured-governance-address auth-info)]
    {:authorization/schema-version "governance-authorization.v2"
     :authorization/type :governance
     :authorization/basis (or (:basis auth-info) :scenario-declared)
     :authorization/actor-id (:agent event)
     :authorization/address addr
     :authorization/actor-address addr
     :authorization/configured-governance-address configured-addr
     :authorization/check :with-governance-actor
     :authorization/source :replay-context/agent-index
     :authorization/action action-name
     :authorization/governance-mode mode
     :authorization/authentication-mode (:authentication-mode auth-info)
     :authorization/assurance (:authentication-mode auth-info)
     :authorization/address-bound? (boolean (:address-bound? auth-info))
     :authorization/registry-verified? false
     :authorization/limitation
     "Governance identity is scenario/protocol-configured address equality, not registry or cryptographic control of the address."}))

(def ^:private forced-authorisation-policy
  {   "execute-resolution"
   {:reasons #{:missing-resolver
               :resolver-overcapacity
               :resolver-frozen
               :circuit-breaker-active
               :resolver-unavailable
               :manual-override}
    :authorization/class :interactive-override
    :authorization/path :exceptional
     :checks #{:force-authorised :force-authorisation-record}
    :sources #{:repl-interactive-session :force-authorisation-record}
    :capacity-context-required? true}

   "activate-resolver-overflow"
   {:reasons #{:resolver-overcapacity}
    :authorization/class :capacity-failover
    :authorization/path :capacity-failover
    :checks #{:with-governance-actor}
    :sources #{:replay-context/agent-index}
    :capacity-context-required? true}

   "appeal-slash"
   {:reasons #{:appeal-bond-custody}
    :authorization/class :governance-intervention
    :authorization/path :exceptional
    :checks #{:with-governance-actor}
    :sources #{:replay-context/agent-index}
    :capacity-context-required? true}

   "force-reversal-slash"
   {:reasons #{:governance-force-reversal-slash}
    :authorization/class :governance-intervention
    :authorization/path :exceptional
    :checks #{:with-governance-actor}
    :sources #{:replay-context/agent-index}
    :capacity-context-required? true}})

(defn build-force-authorisation-provenance
  "Build the canonical forced-authorization envelope for a narrow allowlisted
   exceptional path. Rejects unknown actions, reasons, checks, and sources so
   `:authorization/class :forced` cannot silently spread to ordinary flows."
  [context event addr {:keys [reason capacity-context check source limitation authorization-provenance]
                       :or {check :with-governance-actor
                            source :replay-context/agent-index
                            limitation "Forced authorization is governance-gated; not registry-verified."}}]
  (let [action-name (.replace (name (:action event)) "_" "-")
        {:keys [authorization/class authorization/path] :as policy}
        (get forced-authorisation-policy action-name)]
    (when-not policy
      (throw (ex-info "forced authorisation is not allowed for this action"
                      {:type :invalid-force-authorisation
                       :action action-name})))
    (when-not (contains? (:reasons policy) reason)
      (throw (ex-info "forced authorisation reason is not allowed for this action"
                      {:type :invalid-force-authorisation
                       :action action-name
                       :reason reason
                       :allowed-reasons (:reasons policy)})))
    (when-not (contains? (:checks policy) check)
      (throw (ex-info "forced authorisation check is not allowed for this action"
                      {:type :invalid-force-authorisation
                       :action action-name
                       :check check
                       :allowed-checks (:checks policy)})))
    (when-not (contains? (:sources policy) source)
      (throw (ex-info "forced authorisation source is not allowed for this action"
                      {:type :invalid-force-authorisation
                       :action action-name
                       :source source
                       :allowed-sources (:sources policy)})))
    (when (and (:capacity-context-required? policy)
               (nil? capacity-context))
      (throw (ex-info "forced authorisation requires capacity context"
                      {:type :invalid-force-authorisation
                       :action action-name
                       :reason reason})))
    (cond-> (or authorization-provenance
                (governance-authorization-provenance
                 context event addr
                 {:authentication-mode :interactive-session
                  :address-bound? false
                  :basis :repl-interactive-session
                  :configured-governance-address nil}))
      true
      (assoc :authorization/class class
             :authorization/path path
             :authorization/check check
             :authorization/source source
             :authorization/reason reason
             :authorization/limitation limitation)

      capacity-context
      (assoc :authorization/capacity-context capacity-context))))

(defn- run-governance-action
  "Single wrapper over with-governance-actor for governance-sensitive dispatch.
   Every governance-sensitive defmethod apply-action MUST pass through this
   helper so the governance dispatch audit test can verify coverage.
   Builds authorization-provenance (recording the authentication mode and
   address binding) and passes it to the callback, then stores it on the
   result's :extra for audit trail."
  [{:keys [agent-index] :as context} world event f]
  (actx/with-governance-actor
    agent-index event
    (governance-check context)
    (fn [addr _agent auth-info]
      (let [provenance (governance-authorization-provenance context event addr auth-info)
            result (f addr auth-info provenance)]
        (if (:ok result)
          (update result :extra
                  (fn [extra]
                    (let [extra' (or extra {})]
                      (if (:authorization/provenance extra')
                        extra'
                        (assoc extra' :authorization/provenance provenance)))))
          result)))))

(defn- event-workflow-id
  [event]
  (let [p (:params event)]
    (or (:workflow-id p) (compat/wf-id event))))

(defn- event-slash-id
  "Return the slash identity from event params for dedupe-key and business-logic use.

   Resolution order:
     1. Explicit :slash-id on the event params.
     2. :workflow-id on the event params (used when slash-id = workflow-id
        for non-slash actions such as execute-resolution).
     3. Legacy fallback via compat/wf-id for :id-based workflows.

   The world is intentionally NOT consulted: the dedupe key must be
   deterministic across retries regardless of world state changes.
   Slash identity is an event-level property, not a world-derived one."
  [event]
  (let [p (:params event)]
    (or (:slash-id p) (:workflow-id p) (compat/wf-id event))))

(defn- event-slash-bps
  [event]
  (let [p (:params event)]
    (get p :slash-bps nil)))

(defn- event-id
  "Optional logical event identifier used for replay dedupe."
  [event]
  (compat/event-id event))

(def governance-sensitive-actions
  "Actions that modify protocol-level governance state and must be dispatched
   through with-governance-actor (via run-governance-action).
   Source of truth for the governance dispatch audit test."
  #{"rotate-dispute-resolver"
    "activate-resolver-overflow"    "unfreeze-resolver"
    "withdraw-fees"
    "governance-update-fee"
    "set-token-liquidity-crunch"
    "set-fee-recipient"
    "set-paused"
    "delegate-to-senior"
    "declare-fraud-incident"
    "propose-fraud-slash"
    "propose-fraud-group-slash"
    "resolve-fraud-group-appeal"
    "appeal-slash"
    "resolve-appeal"
    "set-yield-risk"
    "force-reversal-slash"
    "grant-force-authorisation"
    "grant-consensus-force-authorisation"
    "grant-related-claims-force-authorisation"
    "revoke-force-authorisation"
    "grant-related-claims"
    "set-resolution-module"})

(def replay-sensitive-actions
  "Actions that should be replay-idempotent when a logical event-id is provided.
   17 canonical actions + 3 backward-compat aliases = 19 entries."
  #{"escalate-dispute"
    "challenge-resolution"
    "execute-resolution"
    "execute-pending-settlement"
    "rotate-dispute-resolver"
    "declare-fraud-incident"
    "propose-fraud-slash"
    "propose-fraud-group-slash"
    "appeal-fraud-group-slash"
    "resolve-fraud-group-appeal"
    "resolve-appeal"
    "execute-fraud-slash"
    "execute-fraud-group-slash"
    "force-reversal-slash"
    "grant-force-authorisation"
    "grant-consensus-force-authorisation"
    "revoke-force-authorisation"
    "execute-force-authorised-action"
    ;; backward compatibility: old action names (normalize to canonical above)
    "grant-force-authorization"
    "revoke-force-authorization"
    "execute-force-authorized-action"})

(defn replay-sensitive-action?
  "True when `event` is subject to replay-boundary dedupe when event-id is present."
  [event]
  (contains? replay-sensitive-actions (compat/canonical-action event)))

(defn dedupe-op-key
  "Build a stable, fixed-length dedupe key for replay-sensitive events.

   Key schema: :sew/replay-dedupe-v1
   Returns an 8-element vector:
     [:sew :replay-dedupe <action> <agent> <wf-id> <slash-id> <hop-scope> <event-id>]

   Field semantics:
     action     – canonical action name (string, kebab-case, normalized by compat/canonical-action)
     agent      – event envelope agent identifier (string from (:agent event))
     workflow-id – stable workflow identity (string or number; from :workflow-id or legacy :id)
     slash-id   – slash operation identity  (string or number; explicit :slash-id, or = wf-id for non-slash actions)
     hop-scope  – escalation/dispute level (string or nil; nil means no hop scope)
     event-id   – logical event identity    (string; must be non-nil for dedupe to activate)

   Determinism guarantee: every field is derived solely from the event and stable
   world state (dispute level for hop-scope lookup).  The key does NOT depend on
   mutable incidental state such as timestamps, iteration order, or derived
   non-authoritative values."
  [world event]
  (let [action (compat/canonical-action event)
        agent  (:agent event)
        wf     (event-workflow-id event)
        sid    (event-slash-id event)
        eid    (event-id event)
        explicit-hop (compat/hop-id event)
        hop-level (when (and (nil? explicit-hop)
                             (contains? #{"escalate-dispute" "challenge-resolution"} action))
                    (t/dispute-level world wf))
        hop-scope (or explicit-hop hop-level)
        ;; Named map for reviewability — each field is explicit.
        key-map {:action action, :agent agent, :workflow-id wf,
                 :slash-id sid, :hop-scope hop-scope, :event-id eid}]
    [:sew :replay-dedupe
     (:action key-map) (:agent key-map) (:workflow-id key-map)
     (:slash-id key-map) (:hop-scope key-map) (:event-id key-map)]))

(defn- sew-event-conflict-domains
  "Conservative serialization/conflict domains for same-timestamp batch replay.

   Actions are grouped by what state they modify:
     Group 1 — workflow-scoped (escrow, dispute, resolution state)
              [:workflow wf-id] + optional [:resolver r] + optional [:token t]
              Note: [:token t] only resolves when the event carries :token in
              params (e.g. create-escrow).  Actions like withdraw-escrow or
              auto-cancel-disputed don't carry :token — the token is derived
              from escrow state, not event params — so they remain workflow-only.
     Group 2 — resolver-scoped (stake, bond, slash state)
     Group 3 — token-scoped (liquidity, yield risk)
     Group 4 — global (paused, fees, governance params)

   agent-index is used to derive the resolver address from the event's :agent
   field for actions like register-stake where the actor IS the resolver."
  [world event agent-index]
  (let [action (compat/canonical-action event)
        wf-id  (event-workflow-id event)
        token  (some-> (get-in event [:params :token]) keyword)
        agent-addr (some-> agent-index (get (:agent event)) :address)
        resolver (or (get-in event [:params :resolver-addr])
                     (get-in event [:params :resolver])
                     (get-in event [:params :senior-addr])
                     (get-in world [:escrow-transfers wf-id :dispute-resolver]))]
    (cond
      (contains? #{"create-escrow" "raise-dispute" "release" "sender-cancel" "recipient-cancel"
                   "execute-resolution" "execute-pending-settlement" "escalate-dispute"
                   "challenge-resolution" "automate-timed-actions" "withdraw-escrow"
                   "rotate-dispute-resolver" "auto-cancel-disputed" "claim-deferred-yield"
                   "trigger-accrue" "submit-evidence" "execute-reentrant-withdraw"}
                 action)
      (cond-> #{[:workflow wf-id]}
        resolver (conj [:resolver resolver])
        token (conj [:token token]))

      (contains? #{"declare-fraud-incident" "set-resolver-capacity" "register-stake" "withdraw-stake"
                    "register-resolver-bond" "register-senior-bond"
                    "propose-fraud-slash" "propose-fraud-group-slash" "appeal-fraud-group-slash"
                    "resolve-fraud-group-appeal" "appeal-slash" "resolve-appeal"
                    "execute-fraud-slash" "execute-fraud-group-slash" "force-reversal-slash" "delegate-to-senior" "unfreeze-resolver"
                    "compute-prorata-slash-allocation"}
                 action)
      ;; Group 2: resolver-scoped actions.  Derive the resolver address from
      ;; the performing actor when the event params don't specify it directly
      ;; (e.g. register-stake, withdraw-stake use the acting agent's address).
      (let [group-action? (contains? #{"propose-fraud-group-slash"
                                        "appeal-fraud-group-slash"
                                        "resolve-fraud-group-appeal"
                                        "execute-fraud-group-slash"}
                                      action)
            group-members (mapv #(if (map? %) (:id %) %)
                                (get-in event [:params :liable-resolvers] []))
            ;; Proposal carries every member.  Later lifecycle events derive
            ;; the immutable member set from the stored slash record.
            slash-members (when group-action?
                            (some-> (get-in world [:pending-fraud-slashes
                                                   (event-slash-id event)
                                                   :members])
                                    (->> (map :id))))
            g2-resolvers (if (seq group-members)
                           group-members
                           (or slash-members [(or resolver agent-addr)]))]
        (cond-> (into #{} (keep #(when % [:resolver %]) g2-resolvers))
          wf-id (conj [:workflow wf-id])))

      (contains? #{"set-token-liquidity-crunch" "set-yield-risk"} action)
      (if token #{[:token token]} #{[:global :unknown]})

      (contains? #{"set-paused" "withdraw-fees" "governance-update-fee"} action)
      #{[:global :protocol]}

      :else
      #{[:global :unknown]})))

;; ---------------------------------------------------------------------------
;; Dispatch
;; ---------------------------------------------------------------------------

(defmulti apply-action
  (fn [_ctx _world event]
    (compat/canonical-action event)))

(defmethod apply-action "create-escrow"
  [{:keys [agent-index snapshot]} world event]
  (let [p (:params event)]
    (if-let [cb-failure (:error (res/circuit-breaker-active? world))]
      (t/fail cb-failure)
      (actx/with-resolved-actor-and-unpaused
        agent-index world event
        (fn [caller]
          (let [token  (keyword (:token p))
                to     (:to p)
                amount (:amount p)]
            (if (or (nil? amount) (<= amount 0) (> amount max-safe-amount))
              {:ok false :error :amount-out-of-safe-range
               :detail {:amount amount :max max-safe-amount}}
              (let [cres     (or (get p :custom-resolver) (get p :dispute-resolver))
                    settings (t/make-escrow-settings
                              {:custom-resolver cres
                               :release-address (:release-address p)
                               :yield-preset (or (:yield-preset p) (:yield_preset p))
                               :auto-release-time (:auto-release-time p)
                               :auto-cancel-time (:auto-cancel-time p)})
                    result   (lc/create-escrow world caller token to amount settings snapshot)]
                (if (:ok result)
                  (assoc result :extra {:workflow-id (:workflow-id result)})
                  result)))))))))

(defmethod apply-action "raise-dispute"
  [{:keys [agent-index]} world event]
  (if-let [cb-failure (:error (res/circuit-breaker-active? world))]
    (t/fail cb-failure)
    (actx/with-resolved-actor-and-unpaused
      agent-index world event
      (fn [addr]
        (lc/raise-dispute world (compat/wf-id event) addr)))))

(defmethod apply-action "execute-resolution"
  [{:keys [agent-index resolution-module-fn resolution-level-map]} world event]
  (actx/with-resolved-actor-and-unpaused
    agent-index world event
    (fn [addr]
      (let [p                (:params event)
            workflow-id      (:workflow-id p)
            resolution-outcome (get p :resolution-outcome nil)
            is-release       (get p :is-release true)
            resolution-hash  (get p :resolution-hash "0xsimhash")
            effective-rm-fn  (or (when resolution-level-map
                                   (auth/make-kleros-module
                                    resolution-level-map
                                    #(t/dispute-level world %)))
                                 resolution-module-fn)
            wf-id            (or workflow-id (compat/wf-id event))]
        (if (= resolution-outcome "cannot-resolve")
          (res/execute-resolution-refused world wf-id addr resolution-hash effective-rm-fn)
          (res/execute-resolution world wf-id addr
                                  is-release resolution-hash effective-rm-fn))))))

(defmethod apply-action "execute-pending-settlement"
  [_ctx world event]
  (if (:paused? world)
    (t/fail :protocol-paused)
    (res/execute-pending-settlement world (compat/wf-id event))))

(defmethod apply-action "automate-timed-actions"
  [_ctx world event]
  (res/automate-timed-actions world (compat/wf-id event)))

(defmethod apply-action "release"
  [{:keys [agent-index]} world event]
  (actx/with-resolved-actor-and-unpaused
    agent-index world event
    (fn [addr]
      (lc/release world (compat/wf-id event) addr sender-only-release))))

(defmethod apply-action "sender-cancel"
  [{:keys [agent-index]} world event]
  (actx/with-resolved-actor-and-unpaused
    agent-index world event
    (fn [addr]
      (let [wf-id (compat/wf-id event)
            snap  (t/get-snapshot world wf-id)
            cs    (:cancellation-strategy snap)]
        (lc/sender-cancel world wf-id addr cs)))))

(defmethod apply-action "recipient-cancel"
  [{:keys [agent-index]} world event]
  (actx/with-resolved-actor-and-unpaused
    agent-index world event
    (fn [addr]
      (let [wf-id (compat/wf-id event)
            snap  (t/get-snapshot world wf-id)
            cs    (:cancellation-strategy snap)]
        (lc/recipient-cancel world wf-id addr cs)))))

(defmethod apply-action "auto-cancel-disputed"
  [_ctx world event]
  (lc/auto-cancel-disputed-escrow world (compat/wf-id event)))

(defmethod apply-action "escalate-dispute"
  [{:keys [agent-index escalation-fn]} world event]
  (actx/with-resolved-actor-and-unpaused
    agent-index world event
    (fn [addr]
      (let [workflow-id (compat/wf-id event)
            result      (res/escalate-dispute world workflow-id addr escalation-fn)]
        (if (:ok result)
          (assoc result :extra (merge (:extra result)
                                      (when (:new-level result)
                                        {:new-level    (:new-level result)
                                         :new-resolver (:new-resolver result)})))
          result)))))

(defmethod apply-action "rotate-dispute-resolver"
  [context world event]
  (run-governance-action context world event
    (fn [addr _agent _provenance]
      (let [workflow-id   (compat/wf-id event)
            new-resolver  (get-in event [:params :new-resolver])
            result        (res/rotate-dispute-resolver world workflow-id new-resolver)]
        (if (:ok result)
          (assoc result :extra {:old-resolver (:old-resolver result)
                                :new-resolver (:new-resolver result)})
          result)))))

(defmethod apply-action "set-resolution-module"
  [context world event]
  (run-governance-action context world event
    (fn [addr _agent _provenance]
      (let [new-module (get-in event [:params :resolution-module])]
        (if (or (nil? new-module) (= "" new-module))
          (t/fail :invalid-module-address)
          (t/ok (assoc-in world [:params :resolution-module] new-module)))))))

(defmethod apply-action "activate-resolver-overflow"
  [context world event]
  (run-governance-action context world event
    (fn [addr _agent governance-prov]
      (let [pp       (:params event)
            now      (time-ctx/block-ts world)
            policy   (:resolver-overflow-policy context)
            allowed  (:allowed-reasons policy #{:resolver-overcapacity})
            reason   (:reason pp)
            resolver (:resolver pp)
            max-wf   (:default-max-workflows policy 100)
            resolvers (seq (:failover-resolvers policy))
            overflow-id (get world :next-overflow-id 0)]
        (cond
          (not resolver)
          (t/fail :missing-resolver-address)
          (not (contains? allowed reason))
          (t/fail :unauthorized-overflow-reason)
          (not resolvers)
          (t/fail :no-failover-resolvers)
          :else
          (let [cap (get-in world [:resolver-capacities resolver])]
            (when (and cap (< (:current-active cap 0) (:max-concurrent cap 1)))
              (attr/log-with-attr :warn "activate-resolver-overflow"
                {:resolver resolver :reason reason
                 :message "Resolver is not at capacity — activation may be premature"}))
            (let [capacity-context {:resolver resolver
                                    :reason reason
                                    :current-active (get cap :current-active 0)
                                    :max-concurrent (get cap :max-concurrent 0)}
                  provenance (build-force-authorisation-provenance
                              context event addr
                              {:reason reason
                               :capacity-context capacity-context
                               :authorization-provenance governance-prov})
                  record {:overflow-id        overflow-id
                          :resolver           resolver
                          :reason             reason
                          :authorized-by      addr
                          :created-at         now
                          :starts-at          now
                          :expires-at         (+ now (:default-duration policy 3600))
                          :max-workflows      max-wf
                          :failover-resolvers (set resolvers)
                          :used-workflows     #{}
                          :status             :active
                          :authorization/provenance provenance
                          :authorization/last-provenance provenance
                          :authorization/last-action "activate-resolver-overflow"
                          :authorization/history
                          [{:authorization/action "activate-resolver-overflow"
                            :authorization/provenance provenance}]}
                  world' (-> world
                             (assoc-in [:resolver-overflows overflow-id] record)
                             (update :next-overflow-id inc))]
              (assoc (t/ok world') :extra {:overflow-id overflow-id}))))))))

(defn- grant-force-authorisation*
  "Construct, persist, and evidence a final Sew force-authorisation grant.
   `consensus-provenance` is constructed exclusively from trusted context
   resolvers by the consensus adapter; ordinary grants pass nil."
  [context world event consensus-provenance]
  (run-governance-action context world event
    (fn [addr _agent provenance]
      (let [pp               (:params event)
            fa-policy        (:force-authorisation-policy context)
            now              (time-ctx/block-ts world)
            workflow-id      (:workflow-id pp)
            escrow           (t/get-transfer world workflow-id)
            reason           (:reason pp)
            allowed          (:allowed-reasons fa-policy)
            starts-at        (or (:starts-at pp) now)
            duration         (:duration pp)
            expires-at-param (:expires-at pp)
            def-dur          (:default-duration fa-policy)
            expires-at       (or expires-at-param
                                 (when (and (number? starts-at) (number? duration))
                                   (+ starts-at duration))
                                 (when (and (number? starts-at) (number? def-dur))
                                   (+ starts-at def-dur))
                                 nil)
            max-dur          (:max-duration fa-policy)
            allowed-action   (:allowed-action pp "execute-resolution")
            is-release       (get pp :is-release true)]
        (cond
          (nil? escrow) (t/fail :force-authorisation-workflow-not-found)
          (not= :disputed (:escrow-state escrow)) (t/fail :force-authorisation-workflow-not-disputed)
          (not (keyword? reason)) (t/fail :force-authorisation-invalid-reason)
          (and allowed (not (contains? allowed reason))) (t/fail :force-authorisation-reason-not-allowed)
          (not= "execute-resolution" allowed-action) (t/fail :force-authorisation-action-not-allowed)
          (not (boolean? is-release)) (t/fail :force-authorisation-invalid-settlement-direction)
          (not (number? starts-at)) (t/fail :force-authorisation-invalid-start-time)
          (and duration (or (not (number? duration)) (neg? duration)))
          (t/fail :force-authorisation-invalid-duration)
          (and expires-at-param (not (number? expires-at-param)))
          (t/fail :force-authorisation-invalid-expiry)
          (and expires-at (<= expires-at starts-at))
          (t/fail :force-authorisation-invalid-time-window)
          (and expires-at-param duration) (t/fail :force-authorisation-conflicting-timing)
          (and expires-at max-dur (> (- expires-at starts-at) max-dur))
          (t/fail :force-authorisation-duration-exceeds-max)
          (held-adjustment/parameter-attribution-error pp)
          (t/fail :force-authorisation-invalid-parameter-attribution)
          :else
          (let [auth-id         (str "fa-" (get world :next-force-authorisation-id 0))
                recipient       (if is-release (:to escrow) (:from escrow))
                reason-for-scope (if is-release :force-authorised-release :force-authorised-refund)
                scope           (force-authorisation-held-scope
                                 auth-id escrow workflow-id is-release
                                 (select-keys pp [:parameter/context :parameter/address]))
                scope-hash      (hash/domain-hash acct/force-authorisation-scope-domain scope)
                grant-prov      (merge provenance
                                       {:authorization/type :force-authorisation
                                        :authorization/id auth-id
                                        :authorization/source :governance
                                        :authorization/check :with-governance-actor
                                        :authorization/scope-hash scope-hash}
                                       consensus-provenance)
                record          {:authorization/id auth-id
                                 :authorization/version "force-authorisation.v2"
                                 :authorization/type :force-authorisation
                                 :authorization/source :governance
                                 :authorization/status :active
                                 :workflow-id workflow-id
                                 :allowed-action allowed-action
                                 :authorization/scope scope
                                 :authorization/scope-hash scope-hash
                                 :nonce auth-id
                                 :starts-at starts-at
                                 :expires-at expires-at
                                 :created-at now
                                 :created-by addr
                                 :reason reason
                                 :consumed? false
                                 :authorization/provenance grant-prov
                                 :authorization/last-provenance grant-prov
                                 :authorization/last-action "grant-force-authorisation"
                                 :authorization/history
                                 [{:authorization/action "grant-force-authorisation"
                                   :authorization/provenance grant-prov}]}
                world' (-> world
                           (assoc-in [:force-authorisations auth-id] record)
                           (update :next-force-authorisation-id inc))]
            (attr/with-attribution {:subject/type :force-authorisation
                                    :subject/id auth-id
                                    :action/type :force-authorisation/grant
                                    :evidence/reason :force-authorisation-granted}
              (cap/capture-event-evidence!
               :force-authorisation-granted
               {:force-auth/before {:next-force-authorisation-id (:next-force-authorisation-id world)}}
               {:force-auth/after {:next-force-authorisation-id (:next-force-authorisation-id world')
                                   :created-auth-id auth-id
                                   :status :active
                                   :starts-at starts-at
                                   :expires-at expires-at
                                   :scope-hash scope-hash}}
               {:force-auth/auth-id auth-id
                :force-auth/workflow-id workflow-id
                :force-auth/allowed-action allowed-action
                :force-auth/reason reason
                :force-auth/created-by addr
                :force-auth/starts-at starts-at
                :force-auth/expires-at expires-at
                :force-auth/scope scope
                :force-auth/scope-hash scope-hash
                :force-auth/nonce auth-id
                :force-auth/consensus-provenance consensus-provenance}
               nil
               {:world-before world
                :world-after world'}))
            (assoc (t/ok world') :extra {:authorization/id auth-id
                                         :authorization/scope-hash scope-hash})))))))

(defmethod apply-action "grant-force-authorisation"
  [context world event]
  (grant-force-authorisation* context world event nil))

(defmethod apply-action "grant-force-authorization"
  [context world event]
  (grant-force-authorisation* context world event nil))

(defn- resolved-artifact
  [resolver reference]
  (when (and (fn? resolver) (some? reference))
    (try (resolver reference)
         (catch Exception _ nil))))

(defn- parse-instant
  [value]
  (try
    (cond
      (instance? java.time.Instant value) value
      (string? value) (java.time.Instant/parse value)
      :else nil)
    (catch Exception _ nil)))

(defn- consensus-governance-preflight
  "Authenticate the public governance actor before a consensus reservation can
   be claimed. Grant construction repeats the check while producing its final
   governance provenance; this preflight closes the CAS contention boundary."
  [context event]
  (actx/with-governance-actor
    (:agent-index context) event (governance-check context)
    (fn [_addr _actor _check] {:ok true})))

(defn- consensus-authorisation-checks
  "Fail-closed verification for the consensus-to-Sew bridge. Resolvers live in
   the trusted action context; events contain only the researcher artifact hash.
   The researcher target must commit the exact scope hash this Sew grant will
   record."
  [context world event]
  (let [pp (:params event)
        workflow-id (:workflow-id pp)
        escrow (t/get-transfer world workflow-id)
        auth-id (str "fa-" (get world :next-force-authorisation-id 0))
        attribution (select-keys pp [:parameter/context :parameter/address])
        auth-hash (:researcher-force-authorisation/hash pp)
        assignment-hash (:research-assignment/hash pp)
        assignment (resolved-artifact (:research-assignment-resolver context) assignment-hash)
        auth (resolved-artifact (:researcher-force-authorisation-resolver context) auth-hash)
        policy-ref (get-in auth [:authorisation/policy :policy/hash])
        round-ref (get-in auth [:authorisation/review-round :review-round/hash])
        policy (resolved-artifact (:researcher-force-authorisation-policy-resolver context) policy-ref)
        round (resolved-artifact (:researcher-force-authorisation-round-resolver context) round-ref)
        scope (when (and escrow (nil? (held-adjustment/parameter-attribution-error attribution)))
                (force-authorisation-held-scope auth-id escrow workflow-id
                                                (get pp :is-release true) attribution))
        scope-hash (when scope (hash/domain-hash acct/force-authorisation-scope-domain scope))
        structural (when auth (researcher-fa/validate-authorisation auth))
        signatures (when auth
                     (researcher-fa/verify-decision-signatures
                      (:researcher-public-key-resolver context) auth))
        policy-check (when (and policy auth) (researcher-fa/verify-against-policy policy auth))
        round-check (when (and round auth) (researcher-fa/verify-against-round round auth))
        consumed? (when-let [checker (:researcher-force-authorisation-consumed? context)]
                    (try (checker (:authorisation/consumption-key auth))
                         (catch Exception _ true)))
        now (parse-instant (:researcher-force-authorisation/now context))
        valid-from (parse-instant (:authorisation/valid-from auth))
        expires-at (parse-instant (:authorisation/expires-at auth))
        actual-policy-hash (or (:policy/hash policy) (get policy "policy_sha256"))
        target (get auth :authorisation/target)
        checks {:authorisation-resolved? (and auth (= auth-hash (:authorisation/hash auth)))
                :structurally-valid? (true? (:valid? structural))
                :approved? (boolean (and auth (researcher-fa/authorisation-approved? auth)))
                :decision-signatures-valid? (true? (:valid? signatures))
                :policy-binding-valid? (and policy-check (:valid? policy-check)
                                            (= policy-ref actual-policy-hash))
                :review-round-binding-valid? (and round-check (:valid? round-check)
                                                    (= round-ref (:review-round/hash round)))
                :target-kind-valid? (= :governance-mandated (:target/kind target))
                :assignment-resolved? (and assignment
                                            (= assignment-hash (:research-assignment/hash assignment)))
                :assignment-valid? (boolean (and assignment
                                                  (research-assignment/assignment-valid? assignment)))
                :assignment-binding-valid?
                (and assignment auth
                     (research-assignment/assignment-matches-authorisation? assignment auth)
                     (= policy-ref (:research-assignment/policy-hash assignment))
                     (= round-ref (:research-assignment/review-round-hash assignment))
                     (= workflow-id (get-in assignment [:research-assignment/target :target/workflow-id]))
                     (= (:reason pp) (get-in assignment [:research-assignment/target :target/reason])))
                ;; Boundaries are inclusive: valid-from <= now <= expires-at.
                :time-valid? (and now
                                  (or (nil? valid-from) (not (.isAfter valid-from now)))
                                  (or (nil? expires-at) (not (.isBefore expires-at now))))
                :public-sew-binding-valid? (and scope-hash
                                                  (= (public-force-authorisation-scope-ref scope-hash)
                                                     (:target/public-force-authorisation-scope-hash target)))
                :consumption-key-unconsumed? (and (fn? (:researcher-force-authorisation-consumed? context))
                                                  (false? consumed?))}]
    {:valid? (every? true? (vals checks))
     :checks checks
     :authorisation auth
     :research-assignment assignment
     :scope scope
     :scope-hash scope-hash}))

(defn- build-consensus-reservation
  "Build the immutable pre-grant reservation artifact. These command and plan
   roots are references supplied by the public command, while the researcher
   authorisation/hash and consumption key are resolved and verified internally."
  [authorisation auth-id scope-hash pp]
  (try
    (researcher-fa/build-reservation
     {:reservation/authorisation-hash (:authorisation/hash authorisation)
      :reservation/consumption-key (:authorisation/consumption-key authorisation)
      :reservation/execution-attempt-id
      (:researcher-force-authorisation/execution-attempt-id pp)
      :reservation/command-root (:researcher-force-authorisation/command-root pp)
      :reservation/plan-root (:researcher-force-authorisation/plan-root pp)})
    (catch Exception _ nil)))

(defn- reserve-consensus-grant!
  "Atomically claim a researcher consumption key with its complete Sew grant
   binding. The registry is an action-context owned atom; no event value can
   replace it."
  [registry consumption-key binding]
  (when (instance? clojure.lang.IAtom registry)
    (loop []
      (let [state @registry]
        (if-let [existing (get state consumption-key)]
          (if (= existing binding)
            {:reserved? true :mode :resumed :binding binding}
            {:reserved? false :reason :binding-conflict :existing existing})
          (if (compare-and-set! registry state (assoc state consumption-key binding))
            {:reserved? true :mode :new :binding binding}
            (recur)))))))

(defn- release-consensus-grant-reservation!
  "Release only the exact reservation made by this failed grant attempt."
  [registry consumption-key binding]
  (when (instance? clojure.lang.IAtom registry)
    (loop []
      (let [state @registry]
        (if-not (= binding (get state consumption-key))
          false
          (if (compare-and-set! registry state (dissoc state consumption-key))
            true
            (recur)))))))

(def ^:private terminal-consensus-statuses
  #{:consumed :failed-after-consumption :rolled-back-after-consumption})

(def ^:private terminal-reservation-root-keys
  [:outcome-manifest/hash :consumption/receipt-hash :execution-evidence/hash
   :sew/world-before-root :sew/world-after-root :sew/world-snapshot-hash
   :event-evidence/root :consumption/effect-outcome])

(def ^:private terminal-reservation-allowed-keys
  (conj (set terminal-reservation-root-keys) :status :correlation/hash))

(defn- terminal-reservation-hash [record]
  (str "sha256:" (hash/domain-hash :consensus-terminal-reservation
                                    (dissoc record :terminal/hash))))

(def ^:private terminal-reservation-anchor-keys
  [:research-assignment/hash :researcher-force-authorisation/hash
   :reservation/hash :reservation/execution-attempt-id
   :researcher-force-authorisation/consumption-key
   :sew/authorization-id :sew/scope-hash])

(def ^:private terminal-reservation-record-keys
  (into #{:artifact/type :artifact/version :terminal/hash}
        (concat terminal-reservation-anchor-keys terminal-reservation-allowed-keys)))

(declare terminal-status-outcome-valid?)

(defn valid-consensus-terminal-reservation?
  "Validate the complete terminal reservation artifact contract: strict schema,
   original reservation anchors, status/outcome and correlation presence rules,
   canonical SHA-256 references, and the domain-separated self-hash. It does
   not resolve the referenced prepared artifacts."
  [record]
  (let [status (:status record)
        outcome (:consumption/effect-outcome record)
        correlation? (contains? record :correlation/hash)
        required-ref-keys (cond-> (concat [:research-assignment/hash
                                           :researcher-force-authorisation/hash
                                           :reservation/hash]
                                          (remove #{:consumption/effect-outcome}
                                                  terminal-reservation-root-keys))
                            correlation? (conj :correlation/hash))]
    (and (map? record)
         (every? terminal-reservation-record-keys (keys record))
         (= :consensus-terminal-reservation (:artifact/type record))
         (= 1 (:artifact/version record))
         (terminal-status-outcome-valid? status outcome)
         (or (not= outcome :not-produced) (not correlation?))
         (or (not (contains? #{:produced :reversed} outcome)) correlation?)
         (every? #(hash-ref/valid-sha256-ref? (get record %)) required-ref-keys)
         (and (keyword? (:researcher-force-authorisation/consumption-key record))
              (some? (namespace (:researcher-force-authorisation/consumption-key record))))
         (and (string? (:sew/authorization-id record))
              (not (empty? (:sew/authorization-id record))))
         (boolean (re-matches #"[0-9a-f]{64}" (:sew/scope-hash record)))
         (= (:terminal/hash record) (terminal-reservation-hash record)))))

(defn- terminal-status-outcome-valid? [status outcome]
  (contains? #{[:consumed :produced]
               [:failed-after-consumption :produced]
               [:failed-after-consumption :not-produced]
               [:rolled-back-after-consumption :reversed]}
             [status outcome]))

(defn finalize-consensus-reservation!
  "Atomically transition one exact consensus reservation to an immutable terminal
   binding. The caller supplies the reservation observed during preparation and
   a fully prepared terminal record. A matching completed record resumes
   idempotently; any different terminal binding conflicts. This function never
   publishes indexes and never returns a candidate world."
  [registry consumption-key reserved-binding terminal]
  (when-not (instance? clojure.lang.IAtom registry)
    (throw (ex-info "Consensus reservation registry is unavailable"
                    {:reason :reservation-registry-unavailable})))
  (when-not (= :reserved (:status reserved-binding))
    (throw (ex-info "Terminal transition requires a reserved binding"
                    {:reason :reservation-not-reserved})))
  (when-let [unknown (seq (remove terminal-reservation-allowed-keys (keys terminal)))]
    (throw (ex-info "Terminal reservation has unknown fields"
                    {:reason :unknown-terminal-key :keys unknown})))
  (when-let [missing (seq (remove #(contains? terminal %) terminal-reservation-root-keys))]
    (throw (ex-info "Terminal reservation binding is incomplete"
                    {:reason :missing-terminal-root :keys missing})))
  (let [status (:status terminal)
        effect-outcome (:consumption/effect-outcome terminal)
        correlation? (contains? terminal :correlation/hash)]
    (when-not (and (contains? terminal-consensus-statuses status)
                   (terminal-status-outcome-valid? status effect-outcome))
      (throw (ex-info "Invalid terminal status/effect-outcome combination"
                      {:reason :invalid-terminal-status-outcome
                       :status status :effect-outcome effect-outcome})))
    (when (and (contains? #{:produced :reversed} effect-outcome) (not correlation?))
      (throw (ex-info "Terminal effect requires a correlation root"
                      {:reason :missing-effect-correlation})))
    (when (and (= :not-produced effect-outcome) correlation?)
      (throw (ex-info "No-effect terminal binding must omit correlation"
                      {:reason :unexpected-effect-correlation})))
    (doseq [key (cond-> terminal-reservation-root-keys
                  correlation? (conj :correlation/hash))
            :when (and (not= key :consumption/effect-outcome)
                       (not (hash-ref/valid-sha256-ref? (get terminal key))))]
      (throw (ex-info "Terminal reservation has malformed hash reference"
                      {:reason :invalid-terminal-root :key key})))
    (loop []
      (let [state @registry
            current (get state consumption-key)]
        (cond
          (and (map? current) (valid-consensus-terminal-reservation? current)
               (= (dissoc current :terminal/hash) (dissoc (merge current terminal) :terminal/hash)))
          {:committed? true :mode :resumed :binding current}

          (= current reserved-binding)
          (let [base (merge current terminal
                            {:artifact/type :consensus-terminal-reservation
                             :artifact/version 1})
                terminal-record (assoc base :terminal/hash (terminal-reservation-hash base))]
            (if (compare-and-set! registry state (assoc state consumption-key terminal-record))
              {:committed? true :mode :new :binding terminal-record}
              (recur)))

          :else
          {:committed? false :reason :terminal-binding-conflict :existing current})))))

(defmethod apply-action "grant-consensus-force-authorisation"
  [context world event]
  (let [governance-result (consensus-governance-preflight context event)
        {:keys [valid? checks authorisation research-assignment scope-hash]}
        (consensus-authorisation-checks context world event)
        pp (:params event)
        auth-id (str "fa-" (get world :next-force-authorisation-id 0))
        reservation (when valid?
                      (build-consensus-reservation authorisation auth-id scope-hash pp))
        consumption-key (:authorisation/consumption-key authorisation)
        registry (:researcher-force-authorisation-reservation-registry context)
        trusted-at (:researcher-force-authorisation/now context)
        decision-hashes (mapv :decision/hash (:authorisation/decision-references authorisation))
        binding (when reservation
                  {:status :reserved
                   :research-assignment/hash (:research-assignment/hash research-assignment)
                   :researcher-force-authorisation/hash (:authorisation/hash authorisation)
                   :researcher-force-authorisation/consumption-key consumption-key
                   :sew/authorization-id auth-id
                   :sew/scope-hash scope-hash
                   :reservation/hash (:reservation/hash reservation)
                   :reservation/execution-attempt-id
                   (:reservation/execution-attempt-id reservation)})]
    (cond
      (not (:ok governance-result))
      governance-result

      (not valid?)
      (t/fail :consensus-force-authorisation-invalid)

      (nil? reservation)
      (t/fail :consensus-force-authorisation-invalid-reservation)

      (not (instance? clojure.lang.IAtom registry))
      (t/fail :consensus-force-authorisation-reservation-registry-unavailable)

      :else
      (let [claim (reserve-consensus-grant! registry consumption-key binding)]
        (if-not (:reserved? claim)
          (t/fail :consensus-force-authorisation-reservation-binding-conflict)
          (let [researcher-provenance
                {:authorization/assurance :consensus-grant-reserved
                 :consensus/assurance :researcher-threshold-authenticated
                 :research-assignment/hash (:research-assignment/hash research-assignment)
                 :researcher-force-authorisation/hash (:authorisation/hash authorisation)
                 :researcher-force-authorisation/policy-hash
                 (get-in authorisation [:authorisation/policy :policy/hash])
                 :researcher-force-authorisation/review-round-hash
                 (get-in authorisation [:authorisation/review-round :review-round/hash])
                 :researcher-force-authorisation/request-root
                 (:authorisation/request-root authorisation)
                 :researcher-force-authorisation/decision-reference-hashes decision-hashes
                 :researcher-force-authorisation/consumption-key consumption-key
                 :researcher-force-authorisation/valid-from (:authorisation/valid-from authorisation)
                 :researcher-force-authorisation/expires-at (:authorisation/expires-at authorisation)
                 :researcher-force-authorisation/public-scope-hash
                 (public-force-authorisation-scope-ref scope-hash)
                 :researcher-force-authorisation/internal-scope-hash scope-hash
                 :researcher-force-authorisation/reservation-hash (:reservation/hash reservation)
                 :researcher-force-authorisation/execution-attempt-id
                 (:reservation/execution-attempt-id reservation)
                 :researcher-force-authorisation/trusted-verification-instant trusted-at
                 :researcher-force-authorisation/checks checks}
                grant (grant-force-authorisation* context world event researcher-provenance)]
            (if (:ok grant)
              (assoc grant :extra (assoc (:extra grant)
                                         :researcher-force-authorisation/reservation reservation
                                         :researcher-force-authorisation/checks checks))
              (do
                ;; A resumed claimant does not own the existing reservation and
                ;; must never compensate it away. Only the call that won the
                ;; CAS may release its exact binding after constructor failure.
                (when (= :new (:mode claim))
                  (release-consensus-grant-reservation! registry consumption-key binding))
                grant))))))))

(defmethod apply-action "revoke-force-authorisation"
  [context world event]
  (run-governance-action context world event
    (fn [addr _agent provenance]
      (let [pp      (:params event)
            auth-id (:authorization-id pp)
            record  (get-in world [:force-authorisations auth-id])]
        (if (nil? record)
          (t/fail :force-authorisation-not-found)
          (let [now (time-ctx/block-ts world)
                revoke-prov (merge provenance
                                    {:authorization/type :force-authorisation
                                     :authorization/id auth-id
                                    :authorization/source :governance
                                    :authorization/check :with-governance-actor
                                    :authorization/action "revoke-force-authorisation"})
                world' (-> world
                           (assoc-in [:force-authorisations auth-id :authorization/status] :revoked)
                           (assoc-in [:force-authorisations auth-id :authorization/last-provenance] revoke-prov)
                           (assoc-in [:force-authorisations auth-id :authorization/last-action] "revoke-force-authorisation")
                           (update-in [:force-authorisations auth-id :authorization/history]
                                      (fnil conj [])
                                      {:authorization/action "revoke-force-authorisation"
                                       :authorization/provenance revoke-prov}))]
            (attr/with-attribution {:subject/type :force-authorisation
                                    :subject/id auth-id
                                    :action/type :force-authorisation/revoke
                                    :evidence/reason :force-authorisation-revoked}
              (cap/capture-event-evidence!
               :force-authorisation-revoked
               {:force-auth/before {:status (:authorization/status record)}}
               {:force-auth/after {:status :revoked}}
               {:force-auth/auth-id auth-id
                :force-auth/workflow-id (:workflow-id record)
                :force-auth/revoked-by addr
                :force-auth/revoked-at now}
               nil
               {:world-before world
                :world-after world'}))
            (assoc (t/ok world') :extra {:authorization/id auth-id})))))))

(defmethod apply-action "revoke-force-authorization"
  [context world event]
  ((get-method apply-action "revoke-force-authorisation") context world event))

(defmethod apply-action "grant-related-claims"
  ;; Authenticated related-claims creation (governance-gated): creator identity
  ;; and provenance are DERIVED from the resolved governance actor via the same
  ;; authentication/provenance surface as every governance action; caller-supplied
  ;; :created-by cannot override it. Emits a V2 relationship with
  ;; :relationship/authenticated? true; creator provenance is committed in the hash.
  [context world event]
  (run-governance-action context world event
    (fn [addr auth-info provenance]
      (let [pp         (:params event)
            type       (get pp :type :same-incident)
            workflow-ids (get pp :workflow-ids [])
            reason     (get pp :reason "related-claims")
            semantics  (get pp :semantics rc/default-semantics)
            created-at-step (time-ctx/block-ts world)
            created-by {:actor/type :governance
                        :actor/address addr
                        :authorization/type :governance
                        :authorization/check :with-governance-actor
                        :authorization/source :governance
                        :authorization/governance-mode
                        (get context :governance-mode :restricted)
                        :authorization/authentication-mode
                        (:authentication-mode auth-info)
                        :authorization/address-bound?
                        (boolean (:address-bound? auth-info))
                        :authorization/registry-verified? false
                        :authorization/provenance provenance}
            members    (for [wf-id workflow-ids]
                         {:claim/kind :sew/workflow
                          :workflow/id (t/normalize-workflow-id wf-id)})
            result     (rc/create-related-claims-with-assurance!
                        world
                        {:type type
                         :members members
                         :semantics semantics
                         :reason reason
                         :created-by created-by
                         :created-at-step created-at-step}
                        (:authentication-mode auth-info))]
        result))))

;; Governance-gated grant of one immutable force-authorisation scope per active
;; related-claims workflow member. New scopes bind the custody position and
;; optional parameter attribution through the shared held-adjustment projector.
(defmethod apply-action "grant-related-claims-force-authorisation"
  [context world event]
  (run-governance-action context world event
    (fn [addr _auth-info provenance]
      (let [pp (:params event)
            rel-id (:relationship-id pp)
            relationship (rc/get-related-claims world rel-id)
            is-release (get pp :is-release true)
            parameter-attribution (select-keys pp [:parameter/context :parameter/address])
            reason (:reason pp)
            fa-policy (:force-authorisation-policy context)
            now (time-ctx/block-ts world)
            starts-at (or (:starts-at pp) now)
            duration (:duration pp)
            expires-at (or (:expires-at pp)
                           (when (and (number? starts-at) (number? duration))
                             (+ starts-at duration))
                           (when (and (number? starts-at) (number? (:default-duration fa-policy)))
                             (+ starts-at (:default-duration fa-policy))))]
        (cond
          (not (rc/related-claims-active? world rel-id))
          (t/fail :force-authorisation-relationship-not-active)
          (not (keyword? reason))
          (t/fail :force-authorisation-invalid-reason)
          (and (:allowed-reasons fa-policy) (not (contains? (:allowed-reasons fa-policy) reason)))
          (t/fail :force-authorisation-reason-not-allowed)
          (not (boolean? is-release))
          (t/fail :force-authorisation-invalid-settlement-direction)
          (not (number? starts-at))
          (t/fail :force-authorisation-invalid-start-time)
          (and duration (or (not (number? duration)) (neg? duration)))
          (t/fail :force-authorisation-invalid-duration)
          (and expires-at (<= expires-at starts-at))
          (t/fail :force-authorisation-invalid-time-window)
          (held-adjustment/parameter-attribution-error parameter-attribution)
          (t/fail :force-authorisation-invalid-parameter-attribution)
          :else
          (let [auth-id (str "fa-related-" (get world :next-force-authorisation-id 0))
                reason-for-scope (if is-release :force-authorised-release :force-authorised-refund)
                scopes (mapv (fn [{:claim/keys [kind] :workflow/keys [id]}]
                               (let [escrow (t/get-transfer world id)
                                     recipient (if is-release (:to escrow) (:from escrow))]
                                 (held-adjustment/project-held-adjustment-scope
                                  (merge {:authorization/id auth-id
                                          :authorization/type :force-authorisation
                                          :held/direction :out
                                          :token (:token escrow)
                                          :amount (:amount-after-fee escrow)
                                          :held/account :escrow-principal
                                          :held/position-id [:held/position (:token escrow) :escrow-principal id]
                                          :owner/address recipient
                                          :held/reason reason-for-scope
                                          :held/workflow-id id}
                                         parameter-attribution))))
                             (:relationship/members relationship))
                missing-workflow? (some nil? (map #(t/get-transfer world (:workflow/id %))
                                                  (:relationship/members relationship)))
                disputed-members? (every? #(= :disputed (t/escrow-state world (:workflow/id %)))
                                           (:relationship/members relationship))
                member-hashes (mapv #(hash/domain-hash acct/force-authorisation-scope-domain %) scopes)]
            (if (or missing-workflow? (not disputed-members?) (empty? scopes))
              (t/fail :force-authorisation-invalid-related-members)
              (let [scope (first scopes)
                    scope-hash (first member-hashes)
                    grant-prov (merge provenance
                                      {:authorization/type :force-authorisation
                                       :authorization/id auth-id
                                       :authorization/source :governance
                                       :authorization/check :with-governance-actor
                                       :authorization/scope-hash scope-hash})
                    record {:authorization/id auth-id
                            :authorization/version "force-authorisation.v2"
                            :authorization/type :force-authorisation
                            :authorization/source :governance
                            :authorization/status :active
                            :authorization/scope-kind :related-claims
                            :relationship/id rel-id
                            :relationship/hash (:relationship/hash relationship)
                            :member-scope-hashes member-hashes
                            :allowed-action "execute-resolution"
                            :authorization/scope scope
                            :authorization/scope-hash scope-hash
                            :starts-at starts-at :expires-at expires-at :created-at now
                            :created-by addr :reason reason :consumed? false
                            :authorization/provenance grant-prov
                            :authorization/last-provenance grant-prov
                            :authorization/last-action "grant-related-claims-force-authorisation"
                            :authorization/history [{:authorization/action "grant-related-claims-force-authorisation"
                                                     :authorization/provenance grant-prov}]}
                    world' (-> world
                               (assoc-in [:force-authorisations auth-id] record)
                               (update :next-force-authorisation-id inc))]
                (assoc (t/ok world') :extra {:authorization/id auth-id})))))))))

(defmethod apply-action "execute-force-authorised-action"
  [{:keys [agent-index]} world event]
  (actx/with-resolved-actor
    agent-index event
    (fn [addr]
      (let [pp              (:params event)
            workflow-id     (:workflow-id pp)
            auth-id         (:authorization-id pp)
            is-release      (get pp :is-release true)
            resolution-hash (get pp :resolution-hash "0xf-authorized")
            record          (get-in world [:force-authorisations auth-id])
            related?        (= :related-claims (:authorization/scope-kind record))
            now             (time-ctx/block-ts world)]
        (cond
          (nil? record)
          (t/fail :force-authorisation-not-found)

          (not= :active (:authorization/status record))
          (t/fail :force-authorisation-not-active)

          (:consumed? record)
          (t/fail :force-authorisation-already-consumed)

          (and (not related?) (not= workflow-id (:workflow-id record)))
          (t/fail :force-authorisation-workflow-mismatch)

          (not= "execute-resolution" (:allowed-action record))
          (t/fail :force-authorisation-action-mismatch)

          (< now (:starts-at record))
          (t/fail :force-authorisation-not-yet-started)

          (and (:expires-at record)
               (>= now (:expires-at record)))
          (t/fail :force-authorisation-expired)

          (and (not related?) (get-in world [:force-authorisations/consumed auth-id]))
          (t/fail :force-authorisation-already-consumed)

          (held-adjustment/parameter-attribution-error pp)
          (t/fail :force-authorisation-invalid-parameter-attribution)

          :else
          (let [et        (t/get-transfer world workflow-id)
                token     (:token et)
                amount    (:amount-after-fee et)
                recipient (if is-release (:to et) (:from et))
                direction :out
                fa-reason (if is-release :force-authorised-release :force-authorised-refund)
                scope-fields (merge {:authorization/id auth-id
                                     :authorization/type :force-authorisation
                                     :held/direction direction
                                     :token token
                                     :amount amount
                                     :held/account :escrow-principal
                                     :held/position-id [:held/position token :escrow-principal workflow-id]
                                     :owner/address recipient
                                     :held/reason fa-reason
                                     :held/workflow-id workflow-id}
                                    (select-keys pp [:parameter/context :parameter/address]))
                positioned-scope (held-adjustment/project-held-adjustment-scope scope-fields)
                position-bound? (if related?
                                  (contains? (set (:member-scope-hashes record))
                                             (hash/domain-hash acct/force-authorisation-scope-domain positioned-scope))
                                  (contains? (:authorization/scope record) :held/position-id))
                scope-map (held-adjustment/project-held-adjustment-scope
                           (cond-> scope-fields
                             (not position-bound?) (dissoc :held/position-id)))
                scope-hash (hash/domain-hash acct/force-authorisation-scope-domain scope-map)
                scope-authorised? (if related?
                                    (and (= :related-claims (:authorization/scope-kind record))
                                         (rc/related-claims-active? world (:relationship/id record))
                                         (contains? (set (:member-scope-hashes record)) scope-hash))
                                    (and (= scope-map (:authorization/scope record))
                                         (= scope-hash (:authorization/scope-hash record))))]
            (if-not scope-authorised?
              (t/fail :force-authorisation-grant-scope-mismatch)
              (let [execution-prov
                {:authorization/schema-version "force-authorisation.v2"
                 :authorization/type :force-authorisation
                 :authorization/id auth-id
                 :authorization/scope-hash scope-hash
                 :authorization/source :governance
                 :authorization/check :force-authorisation-record
                 :authorization/workflow-id workflow-id
                 :authorization/allowed-action "execute-resolution"
                 :authorization/executed-by addr
                 :authorization/executed-at now
                 :authorization/governance-provenance
                 (:authorization/provenance record)
                 :parameter/context (:parameter/context scope-map)
                 :parameter/address (:parameter/address scope-map)}
                execution-prov (cond-> execution-prov
                                 related?
                                 (assoc :authorization/scope-kind :related-claims
                                        :relationship/id (:relationship/id record)
                                        :relationship/hash (:relationship/hash record)
                                        :member-scope-hashes (:member-scope-hashes record)))
                result (res/apply-resolution-transition
                        world workflow-id addr is-release resolution-hash nil
                        :resolution-source :force-authorised
                        :authorization-provenance execution-prov)]
            (if (:ok result)
              (let [world' (-> (:world result)
                                ;; Record execution provenance but do NOT mark consumed.
                                ;; Consumption happens at sub-held during finalization,
                                ;; where mark-force-authorisation-consumed binds the grant
                                ;; to the actual token, amount, recipient, and held-adjustment.
                                (assoc-in [:force-authorisations auth-id :executed-by] addr)
                                (assoc-in [:force-authorisations auth-id :executed-at] now)
                                (assoc-in [:force-authorisations auth-id :execution/is-release] is-release)
                                (assoc-in [:force-authorisations auth-id :execution/provenance] execution-prov)
                                (assoc-in [:force-authorisations auth-id :execution/last-provenance] execution-prov)
                                (assoc-in [:force-authorisations auth-id :execution/last-action] "execute-force-authorised-action")
                                (update-in [:force-authorisations auth-id :execution/history]
                                           (fnil conj [])
                                           {:execution/action "execute-force-authorised-action"
                                            :execution/provenance execution-prov}))]
                (attr/with-attribution {:subject/type :force-authorisation
                                        :subject/id auth-id
                                        :action/type :force-authorisation/execute
                                        :evidence/reason :force-authorisation-executed}
                  (cap/capture-event-evidence!
                   :force-authorisation-executed
                   {:force-auth/before {:status (:authorization/status record)
                                        :consumed? (:consumed? record)}}
                    {:force-auth/after {:status (:authorization/status record)
                                        :consumed? (:consumed? record)
                                        :execution-recorded? true
                                        :executed-by addr
                                        :executed-at now
                                        :is-release is-release}}
                   {:force-auth/auth-id auth-id
                    :force-auth/workflow-id workflow-id
                    :force-auth/executed-by addr
                    :force-auth/executed-at now
                    :force-auth/is-release is-release
                    :force-auth/token token
                    :force-auth/amount amount
                    :force-auth/recipient recipient
                    :force-auth/scope-hash scope-hash}
                   nil
                   {:world-before world
                    :world-after world'}))
                (-> result
                    (assoc :world world')
                    (assoc :extra
                           {:authorization/id auth-id
                            :authorization/provenance execution-prov})))
               result)))))))))

(defmethod apply-action "execute-force-authorized-action"
  [{:keys [agent-index]} world event]
  ((get-method apply-action "execute-force-authorised-action") {:agent-index agent-index} world event))

(defmethod apply-action "execute-overflow-resolution"
  [{:keys [agent-index]} world event]
  (actx/with-resolved-actor
    agent-index event
    (fn [addr]
      (let [pp          (:params event)
            workflow-id (:workflow-id pp)
            overflow-id (:overflow-id pp)
            is-release  (get pp :is-release true)
            resolution-hash (get pp :resolution-hash "0xoverflow")
            failover    (auth/authorized-overflow-resolver?
                          world workflow-id addr overflow-id)]
        (if-not failover
          (t/fail :not-authorized-resolver)
          (let [action (.replace (name (:action event)) "_" "-")
                used' (conj (:used-workflows failover) workflow-id)
                cap   (:max-workflows failover)
                status' (if (>= (count used') cap) :exhausted :active)
                execution-provenance
                {:execution/schema-version "execution-provenance.v1"
                 :execution/type :forced-capacity-failover
                 :execution/basis :overflow-record
                 :execution/actor-id (:agent event)
                 :execution/address addr
                 :execution/check :authorized-overflow-resolver?
                 :execution/source :resolver-overflow-record
                 :execution/action action
                 :execution/overflow-id overflow-id
                 :execution/reason (:reason failover)}
                world' (-> world
                           (assoc-in [:resolver-overflows overflow-id :used-workflows] used')
                           (assoc-in [:resolver-overflows overflow-id :status] status'))
                result (res/apply-resolution-transition
                        world' workflow-id addr is-release resolution-hash nil
                        :resolution-source :resolver-overflow)]
            (if (:ok result)
              (let [world'' (-> (:world result)
                                (assoc-in [:escrow-transfers workflow-id :resolution :execution/provenance]
                                          execution-provenance)
                                (assoc-in [:resolver-overflows overflow-id :execution/last-provenance]
                                          execution-provenance)
                                (assoc-in [:resolver-overflows overflow-id :execution/last-action]
                                          "execute-overflow-resolution")
                                (update-in [:resolver-overflows overflow-id :execution/history]
                                           (fnil conj [])
                                           {:execution/action "execute-overflow-resolution"
                                            :execution/provenance execution-provenance}))]
                (-> result
                    (assoc :world world'')
                    (update :extra #(merge (or % {}) {:execution/provenance execution-provenance}))))
              result)))))))

(defmethod apply-action "unfreeze-resolver"
  [{:keys [agent-index] :as context} world event]
  (run-governance-action context world event
    (fn [addr _agent _provenance]
      (let [resolver (get-in event [:params :resolver])]
        (res/unfreeze-resolver world resolver)))))

(defmethod apply-action "set-resolver-capacity"
  [{:keys [agent-index]} world event]
  (actx/with-resolved-actor
    agent-index event
    (fn [addr]
      (let [max-concurrent (get-in event [:params :max-concurrent] 0)]
        (attr/log-with-attr :debug "set-resolver-capacity"
                            {:resolver addr :max-concurrent max-concurrent})
        (t/ok (t/set-resolver-capacity world addr max-concurrent))))))

(defmethod apply-action "set-resolver-unavailable"
  [_ctx world event]
  (let [p (:params event)
        resolver (:resolver p)
        unavailable? (:unavailable? p true)]
    (t/ok (res/update-unavailability world resolver unavailable?))))

(defmethod apply-action "register-stake"
  [{:keys [agent-index]} world event]
  (actx/with-resolved-actor
    agent-index event
    (fn [addr]
      (let [p                (:params event)
            amount           (:amount p 0)
            token            (keyword (:token p "USDC"))
            yield-profile-id (:yield-profile-id p)
            world            (reg/register-stake world addr amount yield-profile-id)]
        (if yield-profile-id
          (let [{:keys [module-id]} (yield-proto/resolve-yield-profile yield-profile-id)]
            (if (get-in world [:yield/modules module-id :ops :yield/deposit])
              (let [world' (-> world
                               (yield-proto/apply-op {:op/type :yield/deposit
                                                      :owner/id (lc/resolver-yield-owner-id addr)
                                                      :module/id module-id
                                                      :amount amount
                                                      :token token})
                               (lc/init-resolver-yield-accrual-time addr))]
                (t/ok world'))
              (t/ok world)))
          (t/ok world))))))

(defmethod apply-action "withdraw-stake"
  [{:keys [agent-index]} world event]
  (actx/with-resolved-actor
    agent-index event
    (fn [resolver-addr]
      (let [p                    (:params event)
            amount               (:amount p)
            token                (keyword (:token p "USDC"))
            current              (reg/get-stake world resolver-addr)
            yield-profile-id     (reg/get-resolver-yield-profile world resolver-addr)
            pending-slash-amount (reduce + 0
                                         (for [[_ slash] (:pending-fraud-slashes world)
                                               :when (and (= (:resolver slash) resolver-addr)
                                                          (#{:pending :appealed} (:status slash)))]
                                           (:amount slash)))]
        (cond
          (or (nil? amount) (not (number? amount)) (<= amount 0))
          (t/fail :invalid-amount)

          (has-active-dispute-for-resolver? world resolver-addr)
          (t/fail :active-disputes-block-withdrawal)

          (> pending-slash-amount (- current amount))
          (t/fail :pending-slash-blocks-withdrawal)

          (> (get-in world [:resolver-frozen-until resolver-addr] 0) (time-ctx/block-ts world))
          (t/fail :resolver-frozen)

          :else
          (let [full-withdraw? (= amount current)
                world-accrued  (if yield-profile-id
                                 (lc/accrue-resolver-yield world resolver-addr token)
                                 world)
                owner-id       (lc/resolver-yield-owner-id resolver-addr)
                pos            (when yield-profile-id
                                 (get-in world-accrued [:yield/positions owner-id]))
                yield-amt      (when pos
                                 (+ (:unrealized-yield pos 0) (:realized-yield pos 0)))
                res            (reg/withdraw-stake world-accrued resolver-addr amount)]
            (if (:ok res)
              (let [world' (:world res)]
                (if (and yield-profile-id full-withdraw? (pos? yield-amt))
                  (let [{:keys [module-id]} (yield-proto/resolve-yield-profile yield-profile-id)
                        world'' (-> world'
                                    (acct/sub-held token
                                                   yield-amt
                                                   {:action "withdraw-stake"
                                                    :reason :resolver-yield-withdrawn
                                                    :extra {:held/action "withdraw-stake"
                                                            :held/resolver resolver-addr
                                                            :held/owner-id owner-id}})
                                    (update-in [:total-withdrawn token] (fnil + 0) yield-amt)
                                    (yield-proto/apply-op {:op/type :yield/withdraw
                                                           :owner/id owner-id
                                                           :module/id module-id}))]
                    (t/ok world''))
                  (t/ok world')))
              res)))))))

(defmethod apply-action "claim-deferred-yield"
  [{:keys [agent-index]} world event]
  (actx/with-resolved-actor
    agent-index event
    (fn [addr]
      (let [yield-id  (or (get-in event [:params :yield-profile-id])
                          (get-in world [:params :yield-generation-module]))
            module-id (:module-id (yield-proto/resolve-yield-profile yield-id))
            raw-owner (get-in event [:params :owner-id])
            owner-id  (cond
                        (nil? raw-owner) (str "resolver:" addr)
                        (and (string? raw-owner) (.startsWith raw-owner "escrow:"))
                        [:sew/escrow (Long/parseLong (subs raw-owner 7))]
                        :else raw-owner)
            pos-key  [:yield/positions owner-id]
            old-pos  (get-in world pos-key)
            world'   (yield-proto/apply-op world {:op/type :yield/claim-deferred
                                                  :owner/id owner-id
                                                  :module/id module-id})
            new-pos  (get-in world' pos-key)
            reclaimed (:reclaimed-amount new-pos 0)]
        (if (pos? reclaimed)
          (let [escrow-id (when (vector? owner-id) (second owner-id))
                world''   (if escrow-id
                            (let [et        (t/get-transfer world' escrow-id)
                                  state     (t/escrow-state world' escrow-id)
                                  recipient (if (#{:released :resolved-release} state) (:to et) (:from et))]
                              (lc/apply-deferred-yield-claim-settlement
                               world' escrow-id owner-id recipient reclaimed))
                            ;; Resolver stake: reclaimed deferred was already in :total-held via
                            ;; register-stake; closing the yield position is sufficient (no stake bump).
                            world')]
            (t/ok world''))
          (t/ok world'))))))

(defmethod apply-action "withdraw-escrow"
  [{:keys [agent-index]} world event]
  (actx/with-resolved-actor-and-unpaused
    agent-index world event
    (fn [addr]
      (acct/withdraw-escrow world (event-workflow-id event) addr))))

(defmethod apply-action "execute-reentrant-withdraw"
  [ctx world event]
  (let [p            (:params event)
        callback     (:callback p)
        guard-world  (assoc world :reentrancy-guard true)
        cb-res       (apply-action ctx guard-world callback)]
    (if-not (:ok cb-res)
      (assoc cb-res :world world)
      (let [final-res (apply-action ctx (:world cb-res) (assoc event :action "withdraw-escrow"))]
        final-res))))

(defmethod apply-action "withdraw-fees"
  [{:keys [agent-index] :as context} world event]
  (run-governance-action context world event
    (fn [addr _agent _provenance]
      (if (:paused? world)
        (t/fail :protocol-paused)
        (let [p         (:params event)
              token     (:token p)
              token     (when token (keyword token))
              recipient (acct/resolve-fee-recipient world token)]
          (acct/withdraw-fees world token recipient addr))))))

(defmethod apply-action "governance-update-fee"
  [{:keys [agent-index] :as context} world event]
  (run-governance-action context world event
    (fn [_addr _agent _provenance]
      (let [p         (:params event)
            fee-bps   (or (:fee-bps p) (:resolver-fee-bps p) (:escrow-fee-bps p))]
        (if (nil? fee-bps)
          (t/fail :missing-fee-bps)
          (t/ok (update world :params assoc :resolver-fee-bps fee-bps)))))))

(defmethod apply-action "set-token-liquidity-crunch"
  [{:keys [agent-index] :as context} world event]
  (run-governance-action context world event
    (fn [_addr _agent _provenance]
      (let [p       (:params event)
            token   (keyword (:token p))
            active? (get p :active? true)]
        (t/ok (update world :token-liquidity-crunch
                      (if active?
                        #(conj (or % #{}) token)
                        #(disj % token))))))))

(defmethod apply-action "set-fee-recipient"
  [{:keys [agent-index] :as context} world event]
  (run-governance-action context world event
    (fn [addr _agent _provenance]
      (let [p         (:params event)
            id        (or (:token p) :default)
            recipient (:recipient p)]
        (if (or (nil? recipient) (= recipient ""))
          (t/fail :missing-fee-recipient)
          (let [world' (acct/set-fee-recipient world id recipient)]
            (cap/capture-event-evidence!
             :fee-recipient-updated
             {:fee-recipient/before {:default (get-in world [:fee-recipients :default])
                                     :by-token (get-in world [:fee-recipients :by-token])}}
             {:fee-recipient/after {:default (get-in world' [:fee-recipients :default])
                                    :by-token (get-in world' [:fee-recipients :by-token])}}
             {:fee-recipient/token id
              :fee-recipient/address recipient
              :authorized-by addr})
            (t/ok world')))))))

(defmethod apply-action "set-paused"
  [{:keys [agent-index] :as context} world event]
  (run-governance-action context world event
    (fn [_addr _agent _provenance]
      (t/ok (assoc world :paused? (get-in event [:params :paused?] true))))))

(defmethod apply-action "register-resolver-bond"
  [{:keys [agent-index]} world event]
  (actx/with-resolved-actor
    agent-index event
    (fn [addr]
      (let [p      (:params event)
            stable (get p :stable 0)
            sew    (get p :sew 0)]
        (t/ok (assoc-in world [:resolver-bonds addr]
                        {:stable stable :sew sew}))))))

(defmethod apply-action "register-senior-bond"
  [{:keys [agent-index]} world event]
  (actx/with-resolved-actor
    agent-index event
    (fn [addr]
      (let [p            (:params event)
            coverage-max (get p :coverage-max 0)]
        (t/ok (assoc-in world [:senior-bonds addr]
                        {:coverage-max coverage-max :reserved-coverage 0}))))))

(defmethod apply-action "delegate-to-senior"
  [{:keys [agent-index] :as context} world event]
  (run-governance-action context world event
    (fn [_addr _agent _provenance]
      (let [p            (:params event)
            senior-addr  (:senior-addr p)
            resolver-addr (:resolver-addr p)
            coverage     (:coverage p 0)
            senior-bond  (get-in world [:senior-bonds senior-addr])]
        (if (nil? senior-bond)
          (t/fail :senior-not-registered)
          (if (nil? resolver-addr)
            (t/fail :invalid-resolver-addr)
            (let [new-reserved (+ (:reserved-coverage senior-bond) coverage)
                  max-coverage (:coverage-max senior-bond)]
              (if (> new-reserved max-coverage)
                (t/fail :senior-coverage-exceeded)
                (let [w (assoc-in world [:senior-bonds senior-addr :reserved-coverage]
                                  new-reserved)]
                  (t/ok (assoc-in w [:resolver-senior resolver-addr] senior-addr)))))))))))

(defmethod apply-action "propose-fraud-slash"
  [{:keys [agent-index] :as context} world event]
  (run-governance-action context world event
    (fn [addr _agent provenance]
      (let [p             (:params event)
            workflow-id   (event-workflow-id event)
            resolver-addr (:resolver-addr p)
            amount        (:amount p)]
        (res/propose-fraud-slash world workflow-id addr resolver-addr amount
                                 :authorization-provenance provenance)))))

(defmethod apply-action "declare-fraud-incident"
  [{:keys [agent-index] :as context} world event]
  (run-governance-action context world event
    (fn [addr _agent provenance]
      (res/declare-fraud-incident
       world addr (:params event) provenance
       {:provenance/source :scenario-declared
        :provenance/action "declare-fraud-incident"}))))

(defmethod apply-action "propose-fraud-group-slash"
  [{:keys [agent-index] :as context} world event]
  (run-governance-action context world event
    (fn [addr _agent provenance]
      (let [params (:params event)]
        (res/propose-fraud-group-slash
         world (event-workflow-id event) addr (:liable-resolvers params) (:amount params)
         (:liability-justification params) provenance
         {:provenance/source :scenario-declared
          :provenance/action "propose-fraud-group-slash"})))))

(defmethod apply-action "appeal-fraud-group-slash"
  [{:keys [agent-index]} world event]
  (actx/with-resolved-actor
    agent-index event
    (fn [addr]
      (res/appeal-fraud-group-slash world (event-workflow-id event) addr
                                    (event-slash-id event)
                                    :authorization-provenance
                                    {:authorization/type :member-appeal
                                     :authorization/source :scenario-declared}))))

(defmethod apply-action "resolve-fraud-group-appeal"
  [{:keys [agent-index] :as context} world event]
  (run-governance-action context world event
    (fn [addr _agent provenance]
      (let [params (:params event)]
        (res/resolve-fraud-group-appeal world (event-workflow-id event) addr
                                        (:member params) (boolean (:upheld? params))
                                        (event-slash-id event)
                                        :authorization-provenance provenance)))))

(defmethod apply-action "challenge-resolution"
  [{:keys [agent-index escalation-fn]} world event]
  (actx/with-resolved-actor
    agent-index event
    (fn [addr]
      (res/challenge-resolution world (compat/wf-id event) addr escalation-fn))))

(defmethod apply-action "submit-evidence"
  [{:keys [agent-index]} world event]
  (actx/with-resolved-actor
    agent-index event
    (fn [addr]
      (let [p (:params event)]
        (res/submit-evidence world (event-workflow-id event) addr
                             {:evidence-hash (:evidence-hash p)})))))

(defmethod apply-action "appeal-slash"
  [{:keys [agent-index] :as context} world event]
  (run-governance-action context world event
    (fn [addr _agent governance-prov]
      (let [workflow-id (event-workflow-id event)
            slash-id (event-slash-id event)
            slash-entry (get-in world [:pending-fraud-slashes slash-id])
            resolver-caller (or (:resolver slash-entry) addr)
            provenance (build-force-authorisation-provenance
                        context event addr
                        {:reason :appeal-bond-custody
                         :capacity-context {:workflow-id workflow-id
                                            :slash-id slash-id
                                            :resolver resolver-caller}
                         :authorization-provenance governance-prov})
                                                        result (res/appeal-slash world workflow-id resolver-caller slash-id
                                     :authorization-provenance provenance)]
        (if (:ok result)
          (update result :extra merge {:authorization/provenance provenance})
          result)))))

(defmethod apply-action "resolve-appeal"
  [{:keys [agent-index] :as context} world event]
  (run-governance-action context world event
    (fn [addr _agent provenance]
      (let [p (:params event)]
        (res/resolve-appeal world
                            (event-workflow-id event)
                            addr
                            (boolean (:upheld? p))
                            (event-slash-id event)
                            :authorization-provenance provenance)))))

(defmethod apply-action "execute-fraud-slash"
  [{:keys [agent-index]} world event]
  (actx/with-resolved-actor
    agent-index event
    (fn [addr]
      (let [action (.replace (name (:action event)) "_" "-")
            agent-id (:agent event)
            provenance {:execution/schema-version "execution-provenance.v1"
                        :execution/type :public-execution
                        :execution/basis :scenario-declared
                        :execution/actor-id agent-id
                        :execution/address addr
                        :execution/check :with-resolved-actor
                        :execution/source :replay-context/agent-index
                        :execution/action action}
            result (res/execute-fraud-slash world (event-workflow-id event)
                                            (event-slash-id event)
                                            :execution-provenance provenance)]
        (if (:ok result)
          (update result :extra merge {:execution/provenance provenance})
          result)))))

(defmethod apply-action "execute-fraud-group-slash"
  [{:keys [agent-index]} world event]
  (actx/with-resolved-actor
    agent-index event
    (fn [addr]
      (let [provenance {:execution/schema-version "execution-provenance.v1"
                        :execution/type :public-execution
                        :execution/basis :scenario-declared
                        :execution/address addr
                        :execution/action "execute-fraud-group-slash"}
            result (res/execute-fraud-group-slash world (event-workflow-id event)
                                                  (event-slash-id event)
                                                  provenance)]
        (if (:ok result)
          (update result :extra merge {:execution/provenance provenance})
          result)))))

(defmethod apply-action "compute-prorata-slash-allocation"
  [_ctx world event]
  (let [p (:params event)]
    (res/compute-prorata-slash-allocation world
                                          {:slash-obligation (:slash-obligation p)
                                           :liable-parties   (:liable-parties p)
                                           :basis            (:basis p)
                                           :cap-field        (:cap-field p)})))

(defmethod apply-action "trigger-accrue"
  [_ctx world event]
  (let [wf (event-workflow-id event)]
    (attr/log-with-attr :debug "trigger-accrue" {:workflow-id wf})
    (t/ok (lc/accrue-yield world wf))))

(defmethod apply-action "force-reversal-slash"
  [{:keys [agent-index] :as context} world event]
  (run-governance-action context world event
    (fn [addr _agent governance-prov]
      (let [wf   (event-workflow-id event)
            bps  (event-slash-bps event)
            provenance (build-force-authorisation-provenance
                        context event addr
                        {:reason :governance-force-reversal-slash
                         :capacity-context {:workflow-id wf
                                            :slash-bps bps}
                         :authorization-provenance governance-prov})]
        (attr/log-with-attr :debug "force-reversal-slash" {:workflow-id wf :slash-bps bps :caller addr})
        (let [result (t/ok (res/force-reversal-slash world wf
                                                     :slash-bps bps
                                                     :track :immediate
                                                     :authorization-provenance provenance))]
          (assoc result :extra {:authorization/provenance provenance}))))))

(defmethod apply-action "set-yield-risk"
  [{:keys [agent-index] :as context} world event]
  (run-governance-action context world event
    (fn [_addr _agent _provenance]
      (let [{:keys [module-id token]} (:params event)
            mid (yield-module/resolve-module-id world module-id)
            tok (keyword token)]
        (t/ok (yield-risk/apply-market-shock world mid tok (:params event)))))))

(defmethod apply-action :default
  [_ctx world event]
  (let [consistency (time-ctx/check-temporal-consistency world)]
    (when-not (:holds? consistency)
      (throw (ex-info "Temporal inconsistency detected" consistency)))
    {:ok false :error :unknown-action :detail {:action (:action event)}}))

;; ---------------------------------------------------------------------------
;; Invariant Checks
;; ---------------------------------------------------------------------------

(defn- run-single-invariants [world]
  (let [scenario-id (get-in world [:params :scenario-id])
        r (inv/check-all world scenario-id)
        exp-res (yield-exp/check-expectations world)
        ;; Surface invariants that could not be evaluated (e.g. held-adjustment
        ;; reconstruction when the history is incomplete). These are reported as
        ;; :status :not-evaluated with :holds? true (compatibility), so callers
        ;; that care can distinguish them from a genuine evaluated pass.
        not-evaluated (into {}
                            (filter (fn [[_ result]] (= :not-evaluated (:status result))))
                            (:results r))]
    {:ok?        (and (:all-hold? r) (:ok? exp-res))
     :violations (cond-> (if (:all-hold? r) {} (:results r))
                   (not (:ok? exp-res)) (assoc :expectations (:results exp-res)))
     :not-evaluated-invariants not-evaluated}))

(defn- run-transition-invariants [world-before world-after]
  (let [r (inv/check-transition world-before world-after)]
    {:ok?        (:all-hold? r)
     :violations (when-not (:all-hold? r) (:results r))}))

;; ---------------------------------------------------------------------------
;; Trace Snapshot
;; ---------------------------------------------------------------------------

(defn- world-snapshot [world]
  (let [time-ctx (time-ctx/temporal-context world)]
    (merge
     {:block-time         (:block-ts time-ctx)
      :time               time-ctx
      :escrow-count       (count (:escrow-transfers world))
      :total-held         (:total-held world)
      :total-fees         (:total-fees world)
      :pending-count      (count (filter #(:exists (val %)) (:pending-settlements world)))
      :live-states        (into {} (map (fn [[id et]] [id (:escrow-state et)])
                                        (:escrow-transfers world)))
      :dispute-levels     (into {} (:dispute-levels world))
      :dispute-resolvers  (into {} (map (fn [[id et]] [id (:dispute-resolver et)])
                                        (:escrow-transfers world)))
      :resolver-rotations (into {} (:resolver-rotations world))
      :escrow-amounts     (into {} (map (fn [[id et]] [id (:amount-after-fee et)])
                                        (:escrow-transfers world)))
      :escrow-transfers    (:escrow-transfers world {})
      :resolver-stakes     (:resolver-stakes world)
      :resolver-slash-total (:resolver-slash-total world)
      :bond-distribution   (:bond-distribution world)
      :appeal-bond-distributions-by-token (:appeal-bond-distributions-by-token world {})
      :claimable           (:claimable world {})
      :claimable-v2        (:claimable-v2 world {})
      :bond-balances       (:bond-balances world {})
      :held-adjustments    (:held-adjustments world [])
      :held-artifacts      (:held-artifacts world {})
       :held-ledger/index   (:held-ledger/index world {})
       :yield/positions     (:yield/positions world {})
       :yield-positions     (:yield-positions world {})
       :yield-evidence      (yield-evi/get-evidence world)})))

(def ^:private sew-state-error-codes
  ;; State machine / lifecycle transition rejections
  #{:transfer-not-pending
    :transfer-not-in-dispute
    :invalid-state-for-release
    :invalid-state-for-refund
    :resolution-without-settlement
    :invalid-resolver
    :invalid-workflow-id
    :transfer-not-finalized
    :has-pending-settlement
    :dispute-timeout-not-exceeded
    :invalid-token
    :amount-zero
    :invalid-amount
    :invalid-recipient
    :cannot-set-both-auto-times
    :insufficient-module-liquidity
    :token-liquidity-crunch
    :circuit-breaker-active
    :resolver-at-capacity
    :resolver-frozen
    :insufficient-resolver-stake
    :active-disputes-block-withdrawal
    :freeze-blocked-active-dispute
    :pending-slash-blocks-withdrawal
    :missing-fee-bps
    :no-fees-to-withdraw
    :liquidity-insufficient
    :no-claimable-balance
    :no-bond-to-slash
    :no-bond-to-return
    :senior-not-registered
    :senior-coverage-exceeded
    :insufficient-stake
    :protocol-paused})

(def ^:private sew-guard-error-codes
  ;; Precondition guard rejections
  #{:yield-position-unsettled
    :no-resolution-to-appeal
    :appeal-window-expired
    :appeal-window-not-expired
    :escalation-not-allowed
    :escalation-not-configured
    :resolution-already-pending
    :resolver-capacity-exceeded
    :insufficient-resolver-stake
    :not-participant
    :not-authorized-resolver
    :not-governance
    :not-resolver
    :not-sender
    :not-recipient
    :no-pending-slash
    :invalid-slash-state
    :slash-not-pending
    :slash-already-pending
    :invalid-slash-amount
    :invalid-resolver-addr
    :slash-resolver-mismatch
    :slash-exceeds-max-per-offense
    :slash-epoch-cap-exceeded
    :timelock-not-expired
    :workflow-not-slashable
    :missing-caller-context
    :invalid-new-resolver
    :evidence-deadline-exceeded
    :reentrancy-guard-violated})

(def ^:private adversarial-capable-actions
  "Actions that can constitute an attack when performed by a
   labeled adversarial agent. Benign setup/utility actions
   (create_escrow, release, register_stake, etc.) are excluded
   to prevent false positives in attack-attempts counting."
  #{:raise_dispute
    :execute_resolution
    :escalate_dispute
    :slash
    :auto_cancel_disputed
    :slash_resolver
    :freeze_resolver})

;; ---------------------------------------------------------------------------
;; SewProtocol Implementation (Tiered)
;; ---------------------------------------------------------------------------

(defn- resolve-scenario-bindings
  "Resolve {:from-binding key} values in an event before replay dispatch.
   Bindings live in the replay world, making the reference explicit and
   deterministic across scenario execution and replay."
  [world value]
  (cond
    (and (map? value) (= #{:from-binding} (set (keys value))))
    (let [binding-key (:from-binding value)
          resolved (get-in world [:scenario-bindings binding-key] ::missing)]
      (if (= ::missing resolved)
        (throw (ex-info "Scenario binding is not available"
                        {:binding binding-key}))
        resolved))

    (and (map? value) (= #{:slash-ref} (set (keys value))))
    (let [{:keys [workflow-id kind level]} (:slash-ref value)
          context-key (t/slash-context-key workflow-id kind level)
          slash-id (get-in world [:slash-by-context context-key] ::missing)]
      (if (= ::missing slash-id)
        (throw (ex-info "Scenario slash reference is not available"
                        {:slash-ref (:slash-ref value)}))
        slash-id))

    (map? value) (into (empty value)
                        (map (fn [[k v]] [k (resolve-scenario-bindings world v)]))
                        value)
    (vector? value) (mapv #(resolve-scenario-bindings world %) value)
    (sequential? value) (mapv #(resolve-scenario-bindings world %) value)
    :else value))

(defn- bind-scenario-result
  "Persist values declared by an event's :bind map after a successful action.
   A binding target is a top-level result key, e.g. {:slash-id :slash-id}."
  [world event result]
  (reduce-kv (fn [w binding-key result-key]
               (if (contains? result result-key)
                 (assoc-in w [:scenario-bindings binding-key] (get result result-key))
                 (throw (ex-info "Scenario binding target is absent from action result"
                                 {:binding binding-key :result-key result-key
                                  :action (:action event)}))))
             world
             (:bind event {})))

(defrecord SewProtocol []
  proto/SimulationAdapter

  (protocol-id [_] "sew-v1")

  (init-world [_ scenario]
    (let [init-time    (get scenario :initial-block-time 1000)
          tp           (:token-params scenario)
          pp           (assoc (:protocol-params scenario {})
                              :scenario-id (:scenario-id scenario)
                              :expected-failures (:expected-failures scenario {}))
          fot-bps      (when tp (get tp :fee-on-transfer 0))
          s-tokens     (into #{} (keep #(get-in % [:params :token]) (:events scenario)))
          base         (-> (t/empty-world init-time)
                           (assoc :params pp)
                           ;; Declare the held-adjustment completeness contract: a world
                           ;; born from t/empty-world begins with zero custody and is driven
                           ;; only through the protocol command layer (acct/adjust-held), so
                           ;; custody begins at zero, every held mutation is logged, and no
                           ;; history is truncated. This is the canonical construction point
                           ;; for replayed Sew scenarios; runs that break the contract (e.g.
                           ;; the adversarial yield module :drain/:bloat, or pro-rata
                           ;; propagation that assocs :total-held directly) must explicitly
                           ;; clear this flag, keeping strong replay honestly :not-evaluated.
                           (assoc-in [:params :held-adjustments/complete?] true)
                           (yield-proto/init-world pp (:yield-config scenario)))]
      (if (and fot-bps (pos? fot-bps) (seq s-tokens))
        (reduce (fn [w tok] (assoc-in w [:token-fot-bps tok] fot-bps)) base s-tokens)
        base)))

  (build-execution-context [_ agents protocol-params]
    (let [pp         protocol-params
          snapshot   (sew-snapshot/snapshot-from-protocol-params pp)
          rm-addr    (get pp :resolution-module nil)
          esc-map    (get pp :escalation-resolvers nil)
           level-map  (when esc-map
                        (into {} (map (fn [[k v]] [(parse-long (if (keyword? k) (name k) (str k))) v]) esc-map)))
          rm-fn      (when (and rm-addr (not= rm-addr "") (nil? level-map))
                       (auth/make-default-resolution-module rm-addr))
          esc-fn     (when level-map
                       (fn [_world _wf-id _caller current-level]
                         (let [next-level   (inc current-level)
                               new-resolver (get level-map next-level)]
                           (if new-resolver
                             {:ok true :new-resolver new-resolver}
                             {:ok false :error :escalation-not-allowed}))))]
        (let [of-policy  (get pp :resolver-overflow-policy {})
              def-policy {:enabled?            true
                          :default-duration    3600
                          :max-duration        86400
                          :default-max-workflows 100
                          :max-workflows       500
                          :failover-resolvers  #{}
                          :allowed-reasons     #{:resolver-overcapacity}}
              fa-policy  (get pp :force-authorisation-policy {})
              def-fa-policy {:enabled?         true
                             :default-duration 3600
                             :max-duration     86400
                             :allowed-reasons  #{:missing-resolver
                                                  :resolver-overcapacity
                                                  :resolver-frozen
                                                  :circuit-breaker-active
                                                  :resolver-unavailable
                                                  :manual-override}}]
          {:agent-index          (into {} (map (juxt :id identity) agents))
           :snapshot             snapshot
           :escalation-fn        esc-fn
           :resolution-module-fn rm-fn
           :resolution-level-map level-map
           :governance-mode      (get pp :governance-mode :restricted)
           :governance-identity  (normalize-governance-identity (get pp :governance/identity))
           :resolver-overflow-policy (merge def-policy of-policy)
           :force-authorisation-policy (merge def-fa-policy fa-policy)})))

  (dispatch-action [_ context world event]
    (let [event       (resolve-scenario-bindings world event)
          flags       (:replay-flags context {})
          require-id? (:require-event-id? flags false)
          eid         (event-id event)
          apply!      (fn [w]
                        (let [result (apply-action context w event)]
                          (if (:ok result)
                            (update result :world bind-scenario-result event result)
                            result)))]
      (cond
        (and require-id? (replay-sensitive-action? event) (nil? eid))
        {:ok false :error :missing-event-id
         :detail {:action (:action event) :seq (:seq event)}}

        (and eid (replay-sensitive-action? event))
        (idem/apply-once world (dedupe-op-key world event) apply!)

        :else
        (apply! world))))

  (check-invariants-single [_ world]
    (run-single-invariants world))

  (check-invariants-transition [_ world-before world-after]
    (run-transition-invariants world-before world-after))

  (world-snapshot [_ world]
    (world-snapshot world))

  (available-actions [_ world actor]
    (let [wfs (keys (:escrow-transfers world))]
      (mapcat identity
              (for [wf wfs]
                (let [et (t/get-transfer world wf)]
                  (cond
              ;; Terminal escrows produce no available actions (explicit boundary)
                    (t/terminal-state? world wf)
                    []

                    (= :pending (:escrow-state et))
                    (into (cond-> []
                            (or (= actor (:from et)) (= actor (:to et)))
                            (conj {:action "raise-dispute" :params {:workflow-id wf}})

                            (= actor (:from et))
                            (into [{:action "release" :params {:workflow-id wf}}
                                   {:action "sender-cancel" :params {:workflow-id wf}}])

                            (= actor (:to et))
                            (conj {:action "recipient-cancel" :params {:workflow-id wf}})))

             ;; Disputed actions
                    (and (= :disputed (:escrow-state et))
                         (not (and (= actor (:dispute-resolver et))
                                   (> (get-in world [:resolver-frozen-until actor] 0) (time-ctx/block-ts world)))))
                    (into (let [pending (t/get-pending world wf)
                                resolver (:dispute-resolver et)]
                            (cond-> []
                       ;; Resolver verdict
                              (and (not (:exists pending)) (= actor resolver))
                              (into [{:action "execute-resolution" :params {:workflow-id wf :is-release true :resolution-hash "0xrelease"}}
                                     {:action "execute-resolution" :params {:workflow-id wf :is-release false :resolution-hash "0xrefund"}}])

                       ;; Escalation/Challenge (if pending exists and not expired)
                              (and (:exists pending) (< (time-ctx/block-ts world) (:appeal-deadline pending)))
                              (into (cond-> []
                                      (or (= actor (:from et)) (= actor (:to et)))
                                      (conj {:action "escalate-dispute" :params {:workflow-id wf}})

                                ;; Anyone can challenge (Phase L)
                                       true (conj {:action "challenge-resolution" :params {:workflow-id wf}})))

                        ;; Resolver overflow: failover actors can resolve under active overflow
                               :always
                               (into (for [o (auth/active-overflows-for world wf)
                                           :when (contains? (:failover-resolvers o) actor)]
                                       [{:action "execute-overflow-resolution"
                                         :params {:workflow-id wf :overflow-id (:overflow-id o) :is-release true}}
                                        {:action "execute-overflow-resolution"
                                         :params {:workflow-id wf :overflow-id (:overflow-id o) :is-release false}}])))))))))))

  (created-id [_ action extra]
    (when (= (compat/canonical-action {:action action}) "create-escrow")
      (:workflow-id extra)))

  (resolve-id-alias [_ event id-alias-map]
    (tap> {:debug :resolve-id-alias :event event :map id-alias-map})
    (if (empty? id-alias-map)
      {:ok true :event event}
      (let [params   (:params event)
            agent    (:agent event)
            resolved-agent (if (and (string? agent) (contains? id-alias-map agent))
                             (get id-alias-map agent)
                             agent)
            resolved-params (into {} (map (fn [[k v]]
                                            [k (if (and (string? v) (contains? id-alias-map v))
                                                 (get id-alias-map v)
                                                 v)])
                                          params))]
        (tap> {:debug :resolved-agent :agent agent :resolved-agent resolved-agent})
        {:ok true :event (assoc event
                                :agent  resolved-agent
                                :params resolved-params)})))

  (open-entities [_ world]
    (vec (for [[wf et] (:escrow-transfers world)
               :when (= :disputed (:escrow-state et))]
           wf)))

  (project-state [_ world query]
    (case (first query)
      :party/net-position
      (let [params (second query)
            actor  (:party params)
            hold   (get-in world [:total-held (get params :token "USDC")] 0)
            claim  (reduce + 0 (for [[_ wf] (:claimable world {})
                                     :when (contains? wf actor)]
                                 (get wf actor 0)))]
        (+ hold claim))
      nil))

  proto/TemporalDeadlines

  (deadline-for [_ world deadline-kind subject _context]
    (let [wf-id subject]
      (case deadline-kind
        :evidence-submission
        (let [snap       (t/get-snapshot world wf-id)
              window-dur (:evidence-window-duration snap 0)]
          (when (pos? window-dur)
            (let [dispute-ts (get-in world [:dispute-timestamps wf-id] 0)]
              (+ dispute-ts window-dur))))
        :settlement
        (let [pending (t/get-pending world wf-id)]
          (if (:exists pending)
            (:appeal-deadline pending)
            (let [current-level (t/dispute-level world wf-id)]
              (some (fn [entry]
                      (when (= (:level entry) current-level)
                        (:appeal-deadline (:pending entry))))
                    (get-in world [:superseded-pending-settlements wf-id] [])))))
        :appeal
        (let [pending (t/get-pending world wf-id)]
          (when (:exists pending)
            (:appeal-deadline pending)))
        :earliest-execution
        (let [pending (get-in world [:pending-fraud-slashes subject])]
          (when pending
            (:appeal-deadline pending)))
        nil)))

  proto/BatchConflictModel

  (event-conflict-domains [_ world event agent-index]
    (sew-event-conflict-domains world event agent-index))

  proto/EconomicModel

  (adversarial-event? [_ event agent]
    (let [action-kw (keyword (:action event))]
      (boolean (or (:adversarial? event)
                   (and (contains? adversarial-capable-actions action-kw)
                        (or (= "malicious" (:strategy agent))
                            (= "attacker"  (:role agent))
                            (= "attacker"  (:type agent))))))))

  (classify-event [_ event result-kw error-kw]
    (let [action    (:action event)
          accepted? (= result-kw :ok)]
      (cond-> #{}
        (and accepted? (= action "create_escrow"))           (conj :entity-created)
        (and accepted? (= action "raise_dispute"))           (conj :dispute-raised)
        (and accepted? (= action "execute_resolution"))      (conj :dispute-resolved)
        (and accepted? (= action "execute_pending_settlement")) (conj :settlement-executed)
        ;; Cancellation action classification
        (and accepted? (= action "sender_cancel"))           (conj :cancellation-sender)
        (and accepted? (= action "recipient_cancel"))        (conj :cancellation-recipient)
        (and accepted? (= action "auto_cancel_disputed"))    (conj :cancellation-auto-dispute)
        (and (= result-kw :rejected)
             (contains? sew-state-error-codes error-kw))       (conj :invalid-state-transition)
        (and (= result-kw :rejected)
             (contains? sew-guard-error-codes error-kw))       (conj :invalid-guard-condition))))

  (metric-vocabulary [_]
    #{:total-escrows
      :total-volume
      :disputes-triggered
      :resolutions-executed
      :pending-settlements-executed
      :double-settlements
      :invalid-state-transitions
      :invalid-guard-conditions
      :expected-reverts
      :unexpected-reverts
      ;; Negative payoff count is terminal-payoff-ledger derived by projection;
      ;; it must not be initialized as a replay accumulator.
      :coalition-net-profit
      :funds-lost
      ;; Cancellation-specific metrics
      :cancellations-sender
      :cancellations-recipient
      :cancellations-auto-dispute})

  (accum-protocol-metrics [_ metrics event-tags event accepted? attack? world-before world-after]
    (let [double-settle? (and accepted?
                              (or (contains? event-tags :dispute-resolved)
                                  (contains? event-tags :settlement-executed))
                              (pos? (:resolutions-executed metrics)))
          held-fn        (fn [w]
                           (let [held (:total-held w {})]
                             (if (map? held) (apply + (vals held)) 0)))
          funds-lost-delta (when (and attack? accepted?)
                             (max 0 (- (held-fn world-before) (held-fn world-after))))]
      (cond-> metrics
        (contains? event-tags :entity-created)
        (-> (update :total-escrows inc)
            (update :total-volume + (get-in event [:params :amount] 0)))

        (contains? event-tags :dispute-raised)
        (update :disputes-triggered inc)

        (contains? event-tags :dispute-resolved)
        (update :resolutions-executed inc)

        (contains? event-tags :settlement-executed)
        (update :pending-settlements-executed inc)

        (contains? event-tags :cancellation-sender)
        (update :cancellations-sender inc)

        (contains? event-tags :cancellation-recipient)
        (update :cancellations-recipient inc)

        (contains? event-tags :cancellation-auto-dispute)
        (update :cancellations-auto-dispute inc)

        double-settle?
        (update :double-settlements inc)

        (contains? event-tags :invalid-state-transition)
        (#(if (contains? event-tags :expected-revert)
            (update % :expected-reverts inc)
            (update % :invalid-state-transitions inc)))

        (contains? event-tags :invalid-guard-condition)
        (#(if (contains? event-tags :expected-revert)
            (update % :expected-reverts inc)
            (update % :invalid-guard-conditions (fnil inc 0))))

        (contains? event-tags :unexpected-revert)
        (#(update % :unexpected-reverts inc))

        (and funds-lost-delta (pos? funds-lost-delta))
        (update :funds-lost + funds-lost-delta))))

  (summarise-batch [_ outcomes]
    (sew-db/sew-summarise-batch outcomes))

  (advisory [_ world request-type context]
    (case request-type
      :suggest-actions          (sew-adv/suggest-actions          world context)
      :session-signals          (sew-adv/session-signals          world context)
      :evaluate-payoff          (sew-adv/evaluate-payoff          world context)
      :evaluate-attack-objective (sew-adv/evaluate-attack-objective world context)
      {:not-supported true}))

  proto/AnalysisModule

  (compute-projection [_ world]
    [(diff/projection world) (diff/projection-hash world)])

  (classify-transition [_ action result-kw]
    {:transition/type (meta/transition-type action)
     :resolution/path (meta/resolution-path action)})

  (trace-projection [this result]
    (sew-proj/trace-end-projection this result))

  (io-projection [_ data target-type]
    (case target-type
      :funds-ledger-view
      (sew-proj/funds-ledger-view data)

      :world-view
      {:block-time    (:block-time data)
       :entity-count  (count (:escrow-transfers data {}))
       :pending-count (count (filter #(:exists (val %)) (:pending-settlements data {})))}

      :telemetry-record
      (let [cm  (if (contains? data :contract) (:contract data) data)
            div (get data :divergence {})]
        {:strategy          (get cm :strategy :honest)
         :dispute-correct?  (boolean (get cm :dispute-correct?))
         :appeal-triggered? (boolean (get cm :appeal-triggered?))
         :slashed?          (boolean (get cm :slashed?))
         :profit-honest     (long (get cm :profit-honest 0))
         :profit-malice     (long (get cm :profit-malice 0))
         :cm-fee            (long (get cm :cm/fee 0))
         :cm-afa            (long (get cm :cm/afa 0))
         :diffs             (get div :diffs)})

      :event-records
      (let [{:keys [trial-id params result]} data
            cm    (if (contains? result :contract) (:contract result) result)
            btime (long (get params :block-time 1000))
            fstate (get cm :cm/final-state :pending)
            mk    (fn [step etype estate t]
                    {:id           (str trial-id "-" (name step))
                     :trial-id     trial-id
                     :entity-id    "0"
                     :event-type   etype
                     :entity-state estate
                     :block-time   t
                     :valid-from   (java.util.Date. (* ^long t 1000))})]
        [(mk :created  :sew/escrow-created   :pending  btime)
         (mk :disputed :sew/dispute-raised   :disputed (+ btime 10))
         (mk :final    :sew/escrow-finalized  fstate    (+ btime 200))])

      :forge-trace
      (let [scenario (:scenario data)]
        (do
          (require 'resolver-sim.protocols.sew.io.trace-export)
          ((resolve 'resolver-sim.protocols.sew.io.trace-export/export-trace-fixture)
           data scenario)))

      nil))

  (mechanism-property-validators [_]
    sew-eq/mechanism-property-validators)

  (equilibrium-concept-validators [_]
    sew-eq/equilibrium-concept-validators)

  (reference-model [_ scenario]
    nil))

(def protocol (SewProtocol.))

(defn replay-with-sew-protocol
  ([scenario] (replay-with-sew-protocol scenario {}))
  ([scenario replay-opts]
   (let [scenario*
         (if-let [te (:temporal-evidence scenario)]
           (if (and (:enabled? te) (not (:recorder te)))
             (assoc scenario :temporal-evidence
                    (assoc te :recorder temporal/record-from-replay!
                           :protocol protocol))
             scenario)
           scenario)]
     (replay/replay-with-protocol protocol scenario* replay-opts))))
