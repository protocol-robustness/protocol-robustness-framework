(ns resolver-sim.protocols.sew.authority
  "Pure Clojure port of _isAuthorizedDisputeResolver and ModuleSnapshot
   resolver dispatch logic from BaseEscrow.sol.

   Resolution priority (matches Solidity exactly):
     1. If escrowSettings[wf].customResolver != address(0)
           → only that address is authorized (no override possible)
     2. Else if moduleSnapshot[wf].resolutionModule != address(0)
           → query the module via resolution-module-fn stub
     3. Else fallback
           → authorize if caller == et.disputeResolver

   Module stubs:
     resolution-module-fn — (fn [workflow-id caller] → {:authorized? bool})
       Replaces the staticcall to IResolutionModule.isAuthorizedDisputeResolver.
       Pass nil to simulate 'no module configured'.

   DefaultResolutionModule stub (single-resolver):
     (make-default-resolution-module resolver-addr)
     Returns a resolution-module-fn that authorizes only resolver-addr.

   KlerosArbitrableProxy stub (escalating resolver per level):
      (make-kleros-module resolver-by-level)
      resolver-by-level — map of {level → resolver-addr}
      Returns a resolution-module-fn that authorizes based on current level.

   Stub scope limitations (June 2026):
     - PNK token economics (staking, coin voting) are NOT modelled.
       The stub maps level → address without simulating juror selection,
       PNK deposit/withdrawal, or vote commitment/reveal.
     - Juror coherence tracking is NOT modelled.
       The stub does not track whether a given resolver was coherent
       in prior rounds; no coherence-based penalty or reward exists.
     - Court economics (case fees, appeal fees, juror payouts) are NOT
       modelled.  The stub assumes resolver willingness without economic
       incentives.
     - The stub is correct for authorization-flow testing only.  Any
       evidence pack that cites Kleros-level economic security must
       replace this stub with a full court model."
  (:require [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.time.context :as time-ctx]))

;; ---------------------------------------------------------------------------
;; Module stubs
;; ---------------------------------------------------------------------------

(defn make-default-resolution-module
  "DefaultResolutionModule stub: single configured resolver address.
   Authorizes only that address."
  [resolver-addr]
  (fn [_workflow-id caller]
    {:authorized? (= caller resolver-addr)}))

(defn make-kleros-module
  "KlerosArbitrableProxy stub: maps dispute level → resolver address.

   resolver-by-level — {0 addr0, 1 addr1, ...}
   current-level     — current escalation level for a given workflow-id

   Returns a resolution-module-fn that reads the level from a mutable atom
   (or a fn that takes level explicitly).

   Usage:
     (make-kleros-module {0 \"0xJuror1\" 1 \"0xJuror2\"} level-fn)
     where level-fn is (fn [workflow-id] → int)"
  [resolver-by-level level-fn]
  (fn [workflow-id caller]
    (let [level    (level-fn workflow-id)
          expected (get resolver-by-level level)]
      {:authorized? (and (some? expected) (= caller expected))})))

;; ---------------------------------------------------------------------------
;; _isAuthorizedDisputeResolver
;; ---------------------------------------------------------------------------

(defn authorized-resolver?
  "Return true when caller is authorized to resolve the dispute for workflow-id.

   Priority order (mirrors BaseEscrow._isAuthorizedDisputeResolver exactly):
     1. customResolver set in escrowSettings → exclusive authority
     2. resolutionModule in moduleSnapshot → query module via resolution-module-fn
     3. Fallback → caller must equal et.disputeResolver

    resolution-module-fn — (fn [workflow-id caller] → {:authorized? bool})
                           or nil when no module is configured / module is not a contract

   Fail-closed: if the module callback throws, returns nil, or returns a malformed
   map (without :authorized?), caller is denied authorization (returns false
   without falling through to Priority 3). A well-formed {:authorized? false}
   still falls through to the direct-resolver check, mirroring Solidity."
   [world workflow-id caller resolution-module-fn]
   (let [settings (t/get-settings  world workflow-id)
         snap     (t/get-snapshot  world workflow-id)
         et       (t/get-transfer  world workflow-id)]

     (cond
       ;; Priority 1: custom-resolver is exclusive
       (and (some? (:custom-resolver settings))
            (not= "" (:custom-resolver settings)))
       (= caller (:custom-resolver settings))

       ;; Priority 2: resolution module.
       ;; Mirrors BaseEscrow._isAuthorizedDisputeResolver exactly:
       ;;   if (authorized) return true;          ← only short-circuits on TRUE
       ;;   return disputeResolver == et.disputeResolver;  ← fallthrough on false
       ;; If the module returns false, execution falls through to Priority 3.
       ;; This is safe because escalateDispute() always writes
       ;;   et.disputeResolver = newRes
       ;; so Priority 3 always resolves to the current round's resolver.
       (and (some? resolution-module-fn)
            (some? (:resolution-module snap))
            (not= "" (:resolution-module snap)))
       (let [module-result
             (try (resolution-module-fn workflow-id caller)
                  (catch Exception _ nil))]
         (if (and (map? module-result) (contains? module-result :authorized?))
           (or (:authorized? module-result)
               (= caller (:dispute-resolver et)))
           false))

       ;; Priority 3: direct resolver fallback
       :else
       (= caller (:dispute-resolver et)))))

;; ---------------------------------------------------------------------------
;; ResolutionMode dispatch
;; ---------------------------------------------------------------------------

(defn resolution-mode
  "Determine which resolution mode is active for workflow-id.

   Returns :custom-resolver, :resolution-module, or :direct."
  [world workflow-id]
  (let [settings (t/get-settings world workflow-id)
        snap     (t/get-snapshot world workflow-id)]
    (cond
      (and (some? (:custom-resolver settings))
           (not= "" (:custom-resolver settings)))
      :custom-resolver

      (and (some? (:resolution-module snap))
           (not= "" (:resolution-module snap)))
      :resolution-module

      :else
      :direct)))

;; ---------------------------------------------------------------------------
;; Resolver overflow authorization
;;
;; A resolver-overflow record is created by governance when a primary resolver
;; is overcapacity.  Listed failover resolvers can then resolve disputes that
;; would normally be assigned to the overloaded primary.
;;
;; Authorization is scoped by:
;;   :overflow-id        — explicit record lookup (no search-based auth)
;;   :failover-resolvers — only listed actors
;;   :resolver           — only workflows whose primary matches
;;   :expires-at         — time-bounded
;;   :max-workflows      — quantity-bounded
;;   :used-workflows     — single-use per workflow
;; ---------------------------------------------------------------------------

(defn authorized-overflow-resolver?
  "True when caller is authorized to resolve workflow-id under a specific
   resolver-overflow record.  Returns the overflow record or nil."
  [world workflow-id caller overflow-id]
  (let [record  (get-in world [:resolver-overflows overflow-id])
        now     (time-ctx/block-ts world)
        et      (t/get-transfer world workflow-id)
        primary (:dispute-resolver et)]
    (when (and record
               (= :active (:status record))
               (<= (:starts-at record 0) now)
               (< now (:expires-at record))
               (< (count (:used-workflows record #{}))
                  (:max-workflows record 0))
               (not (contains? (:used-workflows record #{}) workflow-id))
               (contains? (:failover-resolvers record) caller)
               (= (:resolver record) primary)
               (= :disputed (:escrow-state et)))
      record)))

(defn active-overflows-for
  "Return all active resolver-overflow records that apply to workflow-id.
   Used for invariant checks, liveness display, and available-actions surfacing."
  [world workflow-id]
  (let [now     (time-ctx/block-ts world)
        et      (t/get-transfer world workflow-id)
        primary (:dispute-resolver et)]
    (when (= :disputed (:escrow-state et))
      (vec (for [[_ record] (:resolver-overflows world)
                 :when (and (= :active (:status record))
                            (<= (:starts-at record 0) now)
                            (< now (:expires-at record))
                            (< (count (:used-workflows record #{}))
                               (:max-workflows record 0))
                            (not (contains? (:used-workflows record #{}) workflow-id))
                            (= (:resolver record) primary))]
              record)))))

;; ---------------------------------------------------------------------------
;; ModuleSnapshot immutability assertion
;;
;; Used in tests to demonstrate that governance config changes post-creation
;; cannot affect an in-flight escrow.
;; ---------------------------------------------------------------------------

(defn snapshot-frozen?
  "True when the moduleSnapshot stored for workflow-id matches expected-snap.
   Used to assert that governance changes have not altered the frozen snapshot."
  [world workflow-id expected-snap]
  (= (t/get-snapshot world workflow-id) expected-snap))
