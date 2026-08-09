(ns resolver-sim.protocols.sew.types
  "Clojure mirror of EscrowTypes.sol and BaseEscrow storage structs.

   All enum values are keywords. Struct fields use kebab-case.
   Arithmetic follows uint256 truncation (integer division, no rounding).

   World-state shape:
     {:escrow-transfers    {workflow-id EscrowTransfer-map}
      :escrow-settings     {workflow-id EscrowSettings-map}
      :total-held          {token-addr   nat-int}
      :total-fees          {token-addr   nat-int}
      :pending-settlements {workflow-id  PendingSettlement-map}
      ;; Frozen at create_escrow — built via snapshot/make-escrow-snapshot or
      ;; snapshot/snapshot-from-protocol-params (see docs/architecture/YIELD_AND_SNAPSHOT_MODULES.md).
      :module-snapshots    {workflow-id  ModuleSnapshot-map}
      :dispute-timestamps  {workflow-id  nat-int}   ; block.timestamp of raiseDispute
      :claimable           {workflow-id {addr nat-int}}
      :amount-released     {workflow-id nat-int}   ; cumulative partial release amounts
      :resolver-bonds      {addr {:stable nat-int :sew nat-int}} ; DR3 80/20 mix
      :senior-bonds        {addr {:coverage-max nat-int :reserved-coverage nat-int}}
      :resolver-frozen-until {addr nat-int}          ; freeze expiry (0 = not frozen)
      :resolver-epoch-slashed {addr {:epoch-start nat-int :amount nat-int}}
      :resolver-capacities {addr {:max-concurrent nat-int   ; 0 = unlimited (matches DRM setResolverCapacity unlimited=true)
                                  :current-active nat-int}} ; mirrors DRM.resolverCapacity.currentDisputes
      :paused?             boolean                   ; protocol pause state
      :block-time          nat-int}                  ; injected clock

   Every operation function signature:
     (fn [world workflow-id ...args] -> {:ok bool :world world' :error keyword})"
  (:require [clojure.string :as str]
            [resolver-sim.time.context :as time-ctx]
            [resolver-sim.logging :as log]))

;; ---------------------------------------------------------------------------
;; Safe numeric coercion (shared across invariants, projection, classification)
;; ---------------------------------------------------------------------------

(defn safe-parse-long
  "Coerce a value to long, handling JSON-deserialized strings.
   Uses Long/parseLong for exact integer parsing — avoids Double/parseDouble
   which loses precision for uint256-scale values (> 2^53).
   Logs a warning on unparseable input — 0 return is ambiguous but safe
   (0 is a valid workflow-id in the Sew domain)."
  [x]
  (cond
    (number? x) (long x)
    (string? x) (try (Long/parseLong x)
                     (catch Exception e
                       (log/warn! :safe-parse-failed {:input x :error (.getMessage e)})
                       0))
    :else (do (log/warn! :safe-parse-unexpected-type {:input x :type (type x)}) 0)))

;; ---------------------------------------------------------------------------
;; Semantic ID Phantom Types
;; ---------------------------------------------------------------------------

(defrecord TransferId [id]
  java.lang.Comparable
  (compareTo [_ other] (compare id (:id other))))

(defrecord DisputeId [id]
  java.lang.Comparable
  (compareTo [_ other] (compare id (:id other))))

(defrecord ResolverDecisionId [id]
  java.lang.Comparable
  (compareTo [_ other] (compare id (:id other))))

(defrecord WatchdogChallengeId [id]
  java.lang.Comparable
  (compareTo [_ other] (compare id (:id other))))

(defrecord ClaimableWithdrawalId [id]
  java.lang.Comparable
  (compareTo [_ other] (compare id (:id other))))

;; ---------------------------------------------------------------------------
;; Enum sets (canonical values)
;; ---------------------------------------------------------------------------

(def escrow-states
  "EscrowState enum values."
  #{:none :pending :released :refunded :disputed :resolved})

(def allowed-transitions
  "Authoritative EscrowState → #{reachable states} transition graph.
   Mirrors BaseEscrow.sol call sites and StateManagementLibrary.sol guards.
   States with #{} outgoing edges are terminal (absorbing).

   :resolved — reachable from :pending via acceptSplit (mutual split settlement)
               and from :disputed via acceptSplit or resolution.  Guards in
               transition-to-resolved enforce that accounting is settled before
               it may be entered."
  {:none      #{:pending}
   :pending   #{:disputed :released :refunded}
   :disputed  #{:released :refunded :resolved}
   :resolved  #{}
   :released  #{}
   :refunded  #{}})

(def live-states
  "Escrow states that are not yet terminal — can still transition.
   Complement of terminal-states."
  #{:pending :disputed})

(def sender-statuses
  "SenderStatus enum values."
  #{:none :agree-to-cancel :raise-dispute})

(def recipient-statuses
  "RecipientStatus enum values — identical to sender-statuses."
  sender-statuses)

(def resolution-outcomes
  "ResolutionOutcome enum values."
  #{:none :release :cancel})

(def execution-sources
  "ExecutionSource enum values."
  #{:user :keeper :governance})

;; ---------------------------------------------------------------------------
;; Constructor helpers — build correctly-typed maps
;; ---------------------------------------------------------------------------

(defn make-escrow-transfer
  "Construct an EscrowTransfer map (mirrors the Solidity struct).

   Required keys:
     :token       — token address string
     :to          — recipient address string
     :from        — sender address string
     :amount-after-fee — uint256, amount held after fee deduction

   Optional keys (default nil/0):
     :dispute-resolver  — address string, or nil
     :auto-release-time — uint64, epoch seconds, 0 = disabled
     :auto-cancel-time  — uint64, epoch seconds, 0 = disabled
     :escrow-state      — keyword, default :pending
     :sender-status     — keyword, default :none
     :recipient-status  — keyword, default :none"
  [{:keys [token to from amount-after-fee dispute-resolver
           auto-release-time auto-cancel-time
           escrow-state sender-status recipient-status initial-fee] :as args}]
  (when (nil? amount-after-fee)
    (throw (ex-info "make-transfer: :amount-after-fee is required" {:args args})))
  {:token             token
   :to                to
   :from              from
    :amount-after-fee  amount-after-fee
   :initial-fee       (or initial-fee 0)
   :dispute-resolver  dispute-resolver
   :auto-release-time (or auto-release-time 0)
   :auto-cancel-time  (or auto-cancel-time 0)
   :accumulated-yield 0                ; uint256 — total yield accrued to this escrow
   :last-accrual-time (or (:last-accrual-time args) 0) ; uint64 — timestamp of last yield update
   :escrow-state      (or escrow-state :pending)
   :sender-status     (or sender-status :none)
   :recipient-status  (or recipient-status :none)})

(defn normalize-yield-preset
  "Coerce JSON/EDN yield-preset values to keywords.

   Recognized distribution presets are normalized to kebab-case keywords.
   Other values (e.g. a mis-labeled module id) are keywordized as-is so
   `create-escrow` can still treat them as yield-enabled (not :off)."
  [v]
  (cond
    (nil? v)     :off
    (keyword? v) v
    (string? v)  (keyword (str/replace v "_" "-"))
    :else        (keyword (str v))))

(defn yield-preset-yield-enabled?
  "True when escrow principal should be deposited into the yield module."
  [preset]
  (not= (normalize-yield-preset preset) :off))

(defn make-escrow-settings
  "Construct an EscrowSettings map (per-escrow settings, frozen at creation).

   :custom-resolver   — address or nil (overrides module resolver)
   :release-address   — address or nil (authorised to release; nil = sender only)
   :yield-preset      — keyword (:off :to-sender :to-recipient :split-50-50) or string alias
   :yield_preset      — snake_case alias (JSON scenarios)
   :auto-release-time — 0 = use default
   :auto-cancel-time  — 0 = use default"
  [{:keys [custom-resolver release-address yield-preset yield_preset
           auto-release-time auto-cancel-time]}]
  {:custom-resolver   custom-resolver
   :release-address   release-address
   :yield-preset      (normalize-yield-preset (or yield-preset yield_preset))
   :auto-release-time (or auto-release-time 0)
   :auto-cancel-time  (or auto-cancel-time 0)})

(defn make-pending-settlement
  "Construct a PendingSettlement map.

   :exists         — boolean (mirrors the Solidity bool)
   :is-release     — true = release to recipient, false = refund to sender
   :appeal-deadline — block timestamp after which settlement may execute
   :resolution-hash — bytes32 hex string (opaque in the model)"
  [{:keys [exists is-release appeal-deadline resolution-hash]}]
  {:exists          (boolean exists)
   :is-release      (boolean is-release)
   :appeal-deadline (or appeal-deadline 0)
   :resolution-hash resolution-hash})

(def empty-pending-settlement
  "Zero value for PendingSettlement (mirrors default mapping value)."
  {:exists false :is-release false :appeal-deadline 0 :resolution-hash nil})

(defn make-timeout-config
  "Construct a TimeoutConfig map (protocol-level defaults, NOT per-escrow).
   Per-escrow values come from ModuleSnapshot."
  [{:keys [default-auto-release-delay default-auto-cancel-delay
           max-dispute-duration appeal-window-duration]}]
  {:default-auto-release-delay (or default-auto-release-delay 0)
   :default-auto-cancel-delay  (or default-auto-cancel-delay 0)
   :max-dispute-duration       (or max-dispute-duration 0)
   :appeal-window-duration     (or appeal-window-duration 0)})

;; ---------------------------------------------------------------------------
;; World state constructor
;; ---------------------------------------------------------------------------

;; Maximum escalation round — mirrors DecentralizedResolutionModule.MAX_ROUND.
;; Round 0 = initial resolver, 1 = senior resolver, 2 = external (Kleros).
(def ^:const max-dispute-level 2)

(def zero-address
  "Canonical zero-address sentinel — used as 'no resolver assigned' marker
   and for unconditional resolver-available checks."
  "0x0000000000000000000000000000000000000000")

(defn empty-world
  "Create an empty world-state map at a given block time."
  ([] (empty-world 0))
  ([block-time]
   (time-ctx/ensure-temporal-context
    {:escrow-transfers    {}
     :escrow-settings     {}
     :total-held          {}
     :total-fees          {}
     :total-released      {}   ; {token-addr nat-int} — cumulative AFAs finalized via release
     :total-refunded      {}   ; {token-addr nat-int} — cumulative AFAs finalized via refund
     :total-withdrawn     {}   ; {token-addr nat-int} — cumulative withdrawals by users/resolvers
     :total-principal-deposited {} ; {token-addr nat-int} — cumulative gross amount deposited
     :pending-settlements {}
     :module-snapshots    {}
     :dispute-timestamps  {}
     :dispute-levels      {}   ; {workflow-id nat-int} — current escalation round (0–2)
      :claimable           {}
      :amount-released     {}   ; {workflow-id nat-int} — cumulative partial release amounts
      :resolver-stakes     {}   ; {addr nat-int} — for Tiered Authority (Phase K)
     :resolver-slash-total {}  ; {addr nat-int} — cumulative stake slashed (distinguishes slash from withdrawal)
     ;; Canonical slash registry. Each slash has an entity-local integer ID and
     ;; records its workflow relationship explicitly. Legacy scenario ingestion
     ;; is responsible for normalizing pre-v2 string slash identifiers.
     :pending-fraud-slashes {} ; {slash-id {:slash/id :slash/workflow-id ...}}
     :slash-by-context     {} ; {[workflow-id kind level] slash-id}

     :next-slash-id        0
     :previous-decisions  {}   ; {wf-id {level {:resolver :is-release}}}
     :challengers         {}   ; {wf-id {level challenger-addr}} — for Phase L Bounties
     :bond-balances       {}   ; {workflow-id {addr amount}}
     :bond-fees           {}   ; {token amount}
     :total-bonds-posted  {}   ; {token amount} — cumulative bonds ever posted
     :bond-slashed        {}   ; {workflow-id amount}
     :bond-distribution   {:insurance 0 :protocol 0 :burned 0} ; 50/30/20 split
      :retained-slash-reserves 0 ; explicit accounting for retained slash residue
      :slash-credit-liabilities {} ; {addr nat-int} — protocol obligation to restore resolver stake when slash is reversed-with-credit
     :resolver-bonds      {}   ; {addr {:stable nat-int :sew nat-int}} — DR3 80/20 mix invariant
      :senior-bonds        {}   ; {addr {:coverage-max nat-int :reserved-coverage nat-int}}
      :resolver-senior     {}   ; {addr senior-addr} — reverse mapping from each junior resolver to their delegated senior
     :resolver-frozen-until {} ; {addr nat-int} — resolver freeze expiry (0 = not frozen)
     :resolver-epoch-slashed {} ; {addr {:epoch-start nat-int :amount nat-int}} — per-epoch slash cap
     :resolver-capacities   {} ; {addr {:max-concurrent nat-int :current-active nat-int}} — mirrors DRM.resolverCapacity
     :resolver-unavailable #{} ; #{resolver-addr} currently marked unavailable
      :resolver-overflows   {} ; {overflow-id -> overflow-record} — scoped resolver overflow failover
      :next-overflow-id     0 ; counter for resolver-overflow records
      :force-authorisations {} ; {auth-id -> force-authorisation-record} — explicit auth for exceptional actions
      :next-force-authorisation-id 0 ; counter for force-authorisation records
     :unavailability-stats {:total-resolvers 0 :unavailable-count 0 :last-update block-time}
     :circuit-breaker {:active? false :last-trigger 0 :cooldown 3600 :threshold-bps 3000}
     :token-fot-bps          {} ; {token-addr nat-int} — Fee-on-Transfer BPS per token (0 = normal ERC20)
     :token-liquidity-crunch #{} ; #{token-addr} — currently insolvent yield pools
     :last-escalation-block-time-per-addr {} ; {addr block-time} — Sybil mitigation Layer A
     :escalation-counts-per-addr          {} ; {addr count} — Sybil mitigation Layer B
      :yield-rates            {} ; {token-addr rate-bps} — Current annualized yield rate
      :total-yield-generated  {} ; {token-addr nat-int} — All-time yield accrued
      :fee-recipients         {:default "0x0000000000000000000000000000000000000000"
                               :by-token {}} ; default + per-token override
      :fee-payouts            {} ; {token {recipient-addr nat-int}} — cumulative per-recipient payouts
      :total-fees-withdrawn   {} ; {token-addr nat-int} — cumulative fees withdrawn (parallel to fee-payouts)
       :next-workflow-id       0
      :related-claims        {}   ; {relationship-id -> relationship-record}
      :next-related-claim-id 0
      :paused?                false
      :block-time          block-time})))

;; ---------------------------------------------------------------------------
;; Result constructors
;; ---------------------------------------------------------------------------

(def ^:const max-supported-id
  "Largest unsigned integer representable by Solidity uint256."
  (dec (reduce *' 1N (repeat 256 2N))))

(defn valid-entity-id?
  "True for canonical protocol entity IDs: non-negative uint256 integers.
   Strings, keywords, ratios, and floating-point values are intentionally not
   accepted at mutation boundaries."
  [x]
  (and (integer? x)
       (not (neg? x))
       (<= x max-supported-id)))

(defn slash-context-key
  "Canonical secondary-index key for a slash's semantic context."
  [workflow-id kind level]
  [workflow-id kind level])

(defn allocate-slash-id
  "Allocate the next canonical slash ID without mutating the world.
   Callers must insert the entity and increment :next-slash-id in the same
   transition before emitting evidence."
  [world]
  (let [slash-id (get world :next-slash-id 0)]
    (when-not (valid-entity-id? slash-id)
      (throw (ex-info "Invalid next slash ID" {:next-slash-id slash-id})))
    slash-id))

(defn insert-slash
  "Insert a canonical slash entry and update its context index atomically.
   Rejects identifier reuse, mismatched embedded IDs, and duplicate semantic
   contexts rather than overwriting existing state."
  [world {:slash/keys [id workflow-id kind level] :as slash}]
  (let [context (slash-context-key workflow-id kind level)]
    (cond
      (not (valid-entity-id? id))
      (throw (ex-info "Invalid slash ID" {:slash-id id}))

      (not (valid-entity-id? workflow-id))
      (throw (ex-info "Invalid slash workflow ID" {:workflow-id workflow-id}))

      (contains? (:pending-fraud-slashes world) id)
      (throw (ex-info "Slash ID already exists" {:slash-id id}))

      (contains? (:slash-by-context world) context)
      (throw (ex-info "Slash context already exists" {:context context}))

      :else
      (-> world
          (assoc-in [:pending-fraud-slashes id] slash)
          (assoc-in [:slash-by-context context] id)
          (assoc :next-slash-id (inc id))))))

(defn slash-for-workflow
  "Return the canonical slash when `slash-id` exists and belongs to workflow-id.
   Returns nil for absent or mismatched references; transition functions can
   convert those states into distinct public errors."
  [world workflow-id slash-id]
  (when (and (valid-entity-id? workflow-id)
             (valid-entity-id? slash-id))
    (let [slash (get-in world [:pending-fraud-slashes slash-id])]
      (when (= workflow-id (:slash/workflow-id slash)) slash))))

(defn slash-registry->canonical
  "Project the internal integer-keyed slash registry into portable, canonical
   data. Each record is ordered by :slash/id and embeds its ID as a value,
   avoiding JSON's lossy conversion of object keys into strings.

   The projection rejects malformed or legacy entries; callers must normalize
   those at an ingestion boundary rather than coercing them while hashing."
  [world]
  (let [slashes (:pending-fraud-slashes world {})]
    (->> slashes
         (map (fn [[slash-id slash]]
                (when-not (valid-entity-id? slash-id)
                  (throw (ex-info "Slash registry contains a non-canonical key"
                                  {:slash-id slash-id})))
                (when-not (= slash-id (:slash/id slash))
                  (throw (ex-info "Slash registry key and embedded ID differ"
                                  {:key slash-id :embedded-id (:slash/id slash)})))
                (when-not (valid-entity-id? (:slash/workflow-id slash))
                  (throw (ex-info "Slash contains an invalid workflow reference"
                                  {:slash-id slash-id
                                   :workflow-id (:slash/workflow-id slash)})))
                slash))
         (sort-by :slash/id)
         vec)))

(defn ok
  "Successful transition result."
  [world]
  {:ok true :world world})

(defn fail
  "Failed transition result — mirrors a Solidity revert."
  [error-kw]
  {:ok false :error error-kw})

;; ---------------------------------------------------------------------------
;; Fee arithmetic (uint256 truncating integer division)
;; ---------------------------------------------------------------------------

(def ^:const default-max-dispute-duration
  "Default max-dispute-duration in seconds (30 days).
   Mirrors BaseEscrow.DEFAULT_MAX_DISPUTE_DURATION."
  2592000)

(def ^:const default-resolver-response-window
  "Default resolver-response-window in seconds.
   0 = disabled (no response-window bound; a lazy resolver is only caught by
   max-dispute-duration).  A positive value bounds how long the assigned
   resolver has to submit a resolution after dispute-raise."
  0)

(def ^:const fee-denominator
  "ESCROW_FEE_DENOMINATOR = 10000 bps."
  10000)

(defn compute-fee
  "Compute fee from amount and fee bps using integer division (uint256 semantics)."
  [amount fee-bps]
  (quot (* amount fee-bps) fee-denominator))

(defn compute-amount-after-fee
  "Amount held in escrow = amount - fee."
  [amount fee-bps]
  (- amount (compute-fee amount fee-bps)))

(defn- try-parse-id [v]
  (cond
    (integer? v) v
    (string? v)  (let [s (str/trim v)
                       s (if (.startsWith s ":") (subs s 1) s)]
                   (when (re-matches #"\d+" s)
                     (Long/parseLong s)))
    (keyword? v) (try-parse-id (name v))
    :else nil))

(defn normalize-workflow-id
  "Normalize workflow IDs across call-sites to a canonical plain integer.

   Supports integer IDs (canonical), numeric strings (e.g. \"0\"), and
   keyword-like values with a leading colon (e.g. \":0\").
   Also accepts TransferId and DisputeId records (unwrapped to their integer :id).

   Returns the parsed integer when the input is parseable, otherwise returns
   the original value so callers can still fail cleanly via map lookup/guards.

   NOTE: all world-state maps (escrow-transfers, dispute-levels, etc.) use plain
   integers as keys, so this function returns plain integers — not TransferId records —
   to ensure storage lookups always succeed."
  [workflow-id]
  (cond
    (instance? TransferId workflow-id)
    (:id workflow-id)

    (instance? DisputeId workflow-id)
    (:id workflow-id)

    :else
    (or (try-parse-id workflow-id) workflow-id)))

;; ---------------------------------------------------------------------------
;; Yield owner-id constructors
;; ---------------------------------------------------------------------------

(defn escrow-yield-owner-id
  "Canonical owner-id for an escrow's yield position.
   Used as the :owner/id key when registering or withdrawing yield positions."
  [escrow-id]
  [:sew/escrow (normalize-workflow-id escrow-id)])

(defn resolver-yield-owner-id?
  "True when owner-id denotes a resolver staking position (string prefixed 'resolver:')."
  [oid]
  (and (string? oid) (.startsWith ^String oid "resolver:")))

;; ---------------------------------------------------------------------------
;; World-state accessors
;; ---------------------------------------------------------------------------

(defn get-transfer
  "Retrieve EscrowTransfer map for workflow-id, or nil."
  [world workflow-id]
  (let [wf-id (normalize-workflow-id workflow-id)]
    (get-in world [:escrow-transfers wf-id])))

(defn get-settings
  "Retrieve EscrowSettings map for workflow-id, or nil."
  [world workflow-id]
  (let [wf-id (normalize-workflow-id workflow-id)]
    (get-in world [:escrow-settings wf-id])))

(defn get-snapshot
  "Retrieve ModuleSnapshot for workflow-id, or nil."
  [world workflow-id]
  (let [wf-id (normalize-workflow-id workflow-id)]
    (get-in world [:module-snapshots wf-id])))

(defn get-pending
  "Retrieve PendingSettlement for workflow-id (defaults to empty)."
  [world workflow-id]
  (let [wf-id (normalize-workflow-id workflow-id)]
    (get-in world [:pending-settlements wf-id] empty-pending-settlement)))

(defn escrow-state
  "Current EscrowState keyword for workflow-id."
  [world workflow-id]
  (let [wf-id (normalize-workflow-id workflow-id)]
    (get-in world [:escrow-transfers wf-id :escrow-state])))

(def terminal-states
  "Set of terminal (absorbing) escrow states derived from allowed-transitions.
   Single authoritative source — all downstream code MUST reference this def
   instead of inlining the literal set."
  (into #{} (keep (fn [[k v]] (when (empty? v) k))) allowed-transitions))

(defn terminal-state?
  "True if the escrow is in an absorbing (terminal) state."
  [world workflow-id]
  (contains? terminal-states (escrow-state world workflow-id)))

(defn valid-workflow-id?
  "True if workflow-id exists in escrow-transfers."
  [world workflow-id]
  (let [wf-id (normalize-workflow-id workflow-id)]
    (contains? (:escrow-transfers world) wf-id)))

(defn dispute-level
  "Current escalation round for workflow-id (0 = initial, 1 = senior, 2 = external).
   Defaults to 0 when no escalation has occurred."
  [world workflow-id]
  (let [wf-id (normalize-workflow-id workflow-id)]
    (get-in world [:dispute-levels wf-id] 0)))

(defn final-round?
  "True when the escrow is at the maximum escalation round (no further appeals).
   Max level read from world params, falling back to the compiled constant."
  [world workflow-id]
  (>= (dispute-level world workflow-id)
      (get-in world [:params :max-dispute-level] max-dispute-level)))

;; ---------------------------------------------------------------------------
;; Resolver capacity accessors
;; ---------------------------------------------------------------------------

(defn resolver-capacity
  "Return the capacity entry for resolver-addr, or nil if not configured.
   Structure: {:max-concurrent nat-int :current-active nat-int}
   A missing entry means unlimited (matches DRM setResolverCapacity with unlimited=true)."
  [world resolver-addr]
  (get-in world [:resolver-capacities resolver-addr]))

(defn resolver-at-capacity?
  "True when resolver-addr has a finite capacity limit AND current-active >= max-concurrent.
   Returns false when capacity is not configured (unlimited)."
  [world resolver-addr]
  (when-let [{:keys [max-concurrent current-active]} (resolver-capacity world resolver-addr)]
    (and (pos? max-concurrent)
         (>= current-active max-concurrent))))

(defn set-resolver-capacity
  "Configure or overwrite the capacity for resolver-addr.
   max-concurrent=0 means unlimited (mirrors DRM setResolverCapacity unlimited=true)."
  [world resolver-addr max-concurrent]
  (assoc-in world [:resolver-capacities resolver-addr]
            {:max-concurrent (long max-concurrent) :current-active 0}))

(defn increment-resolver-capacity
  "Increment current-active for resolver-addr.
   No-op if resolver has no configured capacity (unlimited)."
  [world resolver-addr]
  (if (contains? (:resolver-capacities world) resolver-addr)
    (update-in world [:resolver-capacities resolver-addr :current-active] inc)
    world))

(defn decrement-resolver-capacity
  "Decrement current-active for resolver-addr, clamped to 0.
   No-op if resolver has no configured capacity (unlimited)."
  [world resolver-addr]
  (if (contains? (:resolver-capacities world) resolver-addr)
    (update-in world [:resolver-capacities resolver-addr :current-active]
               (fn [n] (max 0 (dec n))))
    world))

(defrecord SlashEvent
           [slash-id
            workflow-id
            escrow-contract
            resolver
            reason
            basis-amount
            basis-kind
            slash-bps
            slash-track
            amount
            proposed-at
            executed-at
            appeal-deadline
            status
            proposer
            evidence-root
            original-decision-hash
            reversal-decision-hash])
