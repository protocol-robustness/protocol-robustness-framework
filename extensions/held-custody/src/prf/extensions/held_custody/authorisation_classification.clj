(ns prf.extensions.held-custody.authorisation-classification
  "Forbidden / forbidden-authorized gate for held-custody force-authorisation.

   Establishes the core add-held / sub-held invariant, as a single deterministic
   classifier over a proposed held-custody operation:

     ordinary operation  -> :ordinary             (allowed by normal protocol)
     forbidden operation -> :forbidden            (cannot execute)
     forbidden + exact governed force-authorisation
                         -> :forbidden-authorized (may execute ONLY through the
                            force-authorisation override)
     governance disables the override
                         -> :forbidden even when an otherwise cryptographically
                            valid force-authorisation is presented

   The gate does NOT re-implement force-authorisation verification. Lifecycle
   usability is delegated to the authoritative core validator
   resolver-sim.assurance.force-authorisation/verify-authorisation-usable — the
   same engine the force-authorisation extension's scope-verification facade
   forwards to. Remote-authority-required classification is delegated to the
   sensitivity sentinel. The override-enabled posture MUST be resolved by the
   caller from authoritative governance/configuration state, never from the
   request payload; the gate fails closed when it is absent or ambiguous.

   Vocabulary (see `vocabulary`): :forbidden-authorized means \"would otherwise
   be forbidden, but exact verified authorization exists\" — it is never a
   caller-supplied enum/status and never merely \"some authorization field was
   present\".

   BOUNDARY GUARD — This namespace MUST NOT import or depend on:
     - resolver-sim.protocols.sew
     - any form under protocols_src/
     - resolver-sim.evidence.force-authorisation (deleted legacy core domain)"
  (:require [resolver-sim.assurance.force-authorisation :as fa]
            [resolver-sim.sensitivity.sentinel :as sentinel]
            [prf.extensions.held-custody.mutation :as mutation]))

(def vocabulary
  "The classification vocabulary. :forbidden-authorized is reserved for an
   operation that is forbidden-by-default but for which an exact verified
   force-authorisation override exists (and the override is enabled)."
  {:forbidden
   "cannot execute: either the operation is not force-auth-gated with an exact
    verified permit, or the governed override is disabled, or the permit is
    missing / unusable / wrong-scope."
   :forbidden-authorized
   "would otherwise be forbidden, but an exact verified force-authorisation
    override exists AND the governed override is enabled: may execute only
    through the override. Never a caller-provided enum/status."
   :ordinary
   "allowed by normal protocol semantics; force-authorisation is not required
    and, if presented, is ignored rather than consumed."})

(defn forbidden-action?
  "True when the held-custody action is remote-authority-required (forbidden by
   default): it may only execute through force-authorisation. Delegates to the
   sensitivity sentinel, the authoritative classifier. add-held is forbidden;
   sub-held / finalize-released / refund-held are not."
  [action]
  (sentinel/remote-authority-required-artifact? {:held/action action}))

(defn override-enabled?
  "Resolve the governed override-enabled posture from AUTHORITATIVE
   configuration state. Fails closed: returns false (never true) unless the
   authoritative config EXPLICITLY enables the held-custody force-authorisation
   override (:force-authorisation/override-enabled exactly true). A nil or
   ambiguous config cannot enable the override."
  [authoritative-config]
  (and (map? authoritative-config)
       (true? (:force-authorisation/override-enabled authoritative-config))))

(defn- lifecycle-error-codes
  "Extract the authoritative validator's error codes for a permit/scope pair."
  [permit scope consumption-registry now-ts]
  (mapv :code (:errors (fa/verify-authorisation-usable
                        permit consumption-registry scope now-ts))))

(defn usable-permit?
  "True when `permit` is an exact, verified, currently-usable force-authorisation
   for `scope` at `now-ts`. Delegates to the authoritative core validator; never
   re-implements verification. Covers scope-hash equality, exact scope equality,
   status, single-use consumption (:consumed? + consumption-registry), and
   starts-at/expires-at timing (so a permit that was valid at evaluation but is
   stale at commit time fails closed)."
  [permit scope consumption-registry now-ts]
  (boolean
   (:valid? (fa/verify-authorisation-usable
             permit (or consumption-registry {}) scope (or now-ts 0)))))

(defn blocking-reasons
  "Structured blocking reasons when an exact permit is not usable, or the
   override is disabled / missing. Delegates to the authoritative validator for
   lifecycle reasons."
  [{:keys [action scope permit consumption-registry now-ts authoritative-config]}]
  (let [action (mutation/normalize-action action)
        remote? (and (some? action) (forbidden-action? action))
        enabled? (override-enabled? authoritative-config)]
    (cond
      (nil? action) [:unknown-action]
      (not remote?) []
      (not (true? enabled?)) [:force-authorisation-override-disabled]
      (nil? permit) [:missing-force-authorisation]
      :else (lifecycle-error-codes permit scope (or consumption-registry {})
                                   (or now-ts 0)))))

(defn select-permit
  "Deterministic, non-caller-selectable choice among multiple permit candidates.
   Sorts by :authorization/id and returns the first. There is no generic-scope
   fallback: every candidate must still verify exactly against the requested
   scope, so a wrong-scope or wrong-direction candidate never satisfies a
   forbidden operation merely by appearing first."
  [permits]
  (first (sort-by (fn [p] (or (:authorization/id p) "")) (vec (or permits [])))))

(defn classify-operation
  "Classify a proposed held-custody operation under the forbidden/authorized gate.

   opts:
     :action               :add-held | :sub-held | :finalize-released | :refund-held
     :scope                the exact authorized scope being requested
     :permit               the single force-authorisation permit candidate (or nil)
     :consumption-registry {authorization-id consumption-entry} (default {})
     :now-ts               current block/commit time for lifecycle checks
     :authoritative-config governance/configuration state resolved from an
                           AUTHORITATIVE source (never the request payload)

   Returns {:classification <kw>
            :forbidden-action? bool
            :override-enabled? bool
            :usable-permit? bool
            :blocking-reasons [kw]}.

   Precedence (explicit, deterministic):
     1. Unknown action                                   -> :forbidden (fail closed)
     2. Ordinary (non-remote-authority-required) action  -> :ordinary (force-auth
        ignored, not consumed)
     3. Forbidden action + override disabled/absent      -> :forbidden
     4. Forbidden action + override enabled, no permit   -> :forbidden
     5. Forbidden action + permit not exactly usable     -> :forbidden
     6. Forbidden action + exact verified usable permit  -> :forbidden-authorized"
  [{:keys [action scope permit consumption-registry now-ts authoritative-config]
    :as opts}]
  (let [action (mutation/normalize-action action)
        remote? (and (some? action) (forbidden-action? action))
        enabled? (override-enabled? authoritative-config)
        registry (or consumption-registry {})
        now (or now-ts 0)
        usable? (boolean (and remote? (true? enabled?) (some? permit)
                              (usable-permit? permit scope registry now)))]
    (cond
      (nil? action)
      {:classification :forbidden
       :forbidden-action? false
       :override-enabled? enabled?
       :usable-permit? false
       :blocking-reasons [:unknown-action]}

      (not remote?)
      {:classification :ordinary
       :forbidden-action? false
       :override-enabled? enabled?
       :usable-permit? false
       :force-auth-ignored? true
       :blocking-reasons []}

      (not (true? enabled?))
      {:classification :forbidden
       :forbidden-action? true
       :override-enabled? enabled?
       :usable-permit? false
       :blocking-reasons [:force-authorisation-override-disabled]}

      (nil? permit)
      {:classification :forbidden
       :forbidden-action? true
       :override-enabled? true
       :usable-permit? false
       :blocking-reasons [:missing-force-authorisation]}

      (not usable?)
      {:classification :forbidden
       :forbidden-action? true
       :override-enabled? true
       :usable-permit? false
       :blocking-reasons (blocking-reasons opts)}

      :else
      {:classification :forbidden-authorized
       :forbidden-action? true
       :override-enabled? true
       :usable-permit? true
       :blocking-reasons []})))