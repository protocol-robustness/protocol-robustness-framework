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
     >1 distinct usable exact permits
                         -> :ambiguous-force-authorisation (fail closed)

   The gate does NOT re-implement force-authorisation verification. Lifecycle
   usability is delegated to the authoritative core validator
   resolver-sim.assurance.force-authorisation/verify-authorisation-usable — the
   same engine the force-authorisation extension's scope-verification facade
   forwards to. Override-eligibility is a property of the SEMANTIC OPERATION
   (held-admission/semantic-operation-class), never of :in/:out, add-held/sub-held,
   or disclosure sensitivity. The disclosure/evidence sentinel is deliberately
   NOT consulted for authority: a disclosure-policy change must not alter mutation
   authority. The override-enabled posture MUST be resolved by the caller from
   authoritative governance/configuration state, never from the request payload;
   the gate fails closed when it is absent or ambiguous.

   Multiple simultaneously usable permits are NOT silently resolved by a lexical
   tie-breaker: unless permit ordering is itself part of the governed semantic
   contract, a choice among distinct usable permits would hide an ambiguous
   authority state. The gate therefore fails closed on ambiguity
   (:ambiguous-force-authorisation). Selection is left to the governed semantic
   contract; the classifier never invents one.

   Vocabulary (see `vocabulary`): :forbidden-authorized means \"would otherwise
   be forbidden, but exact verified authorization exists\" — it is never a
   caller-supplied enum/status and never merely \"some authorization field was
   present\".

   BOUNDARY GUARD — This namespace MUST NOT import or depend on:
     - resolver-sim.protocols.sew
     - any form under protocols_src/
     - resolver-sim.evidence.force-authorisation (deleted legacy core domain)"
  (:require [resolver-sim.assurance.force-authorisation :as fa]
            [resolver-sim.assurance.held-admission :as held-admission]))

(def vocabulary
  "The classification vocabulary. :forbidden-authorized is reserved for an
   operation that is forbidden-by-default but for which an EXACT ONE verified
   force-authorisation override exists (and the override is enabled).
   :ambiguous-force-authorisation rejects more than one simultaneously usable
   permit (fail closed) rather than silently choosing one."
  {:forbidden
   "cannot execute: either the operation is not force-auth-gated with an exact
    verified permit, or the governed override is disabled, or no permit is
    usable / present."
   :forbidden-authorized
   "would otherwise be forbidden, but exactly one exact verified
    force-authorisation override exists AND the governed override is enabled:
    may execute only through the override. Never a caller-provided enum/status."
   :ambiguous-force-authorisation
   "would otherwise be forbidden and more than one distinct permit is currently
    usable for the exact scope: authority is ambiguous and the operation is
    rejected (fail closed). No lexical/ID tie-breaker is applied unless permit
    ordering is itself part of the governed semantic contract."
   :ordinary
   "allowed by normal protocol semantics; force-authorisation is not required
    and, if presented, is ignored rather than consumed."})

(declare candidate-permits)

(defn override-eligible-operation?
  "True when a SEMANTIC OPERATION requires an exceptional governed override
   (is force-authorisation-override eligible). Override-eligibility is a property
   of the semantic operation, never of :in/:out, add-held/sub-held, or disclosure
   sensitivity. The disclosure/evidence sentinel is deliberately NOT consulted:
   a disclosure-policy change must never alter mutation authority."
  [operation-id]
  (= :force-authorisation-override
     (held-admission/semantic-operation-class operation-id)))

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

(defn usable-permits
  "The vector of permits that are exactly usable for `scope` at `now-ts`.
   Pure and deterministic: the candidate collection is always evaluated in a
   sorted (by :authorization/id) order so the count and the returned set are
   stable regardless of caller ordering."
  [permits scope consumption-registry now-ts]
  (into []
        (filter #(usable-permit? % scope consumption-registry now-ts))
        (sort-by (fn [p] (or (:authorization/id p) "")) (vec (or permits [])))))

(defn blocking-reasons
  "Structured blocking reasons when no exact permit is usable, or the override is
   disabled / missing. Delegates to the authoritative validator for lifecycle
   reasons; with multiple unusable candidates, distinct reasons are unioned so
   the operator sees the full failure surface."
  [{:keys [operation-id scope permits permit consumption-registry now-ts
           authoritative-config]}]
  (let [override-eligible? (override-eligible-operation? operation-id)
        enabled? (override-enabled? authoritative-config)
        candidates (candidate-permits permits permit)]
    (cond
      (nil? operation-id) [:unknown-operation]
      (not override-eligible?) []
      (not (true? enabled?)) [:force-authorisation-override-disabled]
      (empty? candidates) [:missing-force-authorisation]
      :else
      (vec (distinct
            (mapcat #(lifecycle-error-codes % scope (or consumption-registry {})
                                            (or now-ts 0))
                    candidates))))))

(defn- candidate-permits
  "Normalize the supplied permit(s) into a vector. `:permits` (a collection)
   takes precedence; `:permit` (a single candidate) is accepted as a one-element
   collection for convenience. Nil input yields an empty vector."
  [permits permit]
  (cond
    (some? permits) (vec (or (seq permits) []))
    (some? permit) [permit]
    :else []))

(defn classify-operation
  "Classify a proposed held-custody operation under the forbidden/authorized gate.

   opts:
     :operation-id          semantic operation identity (held-admission
                            semantic-operation-classes); the ONLY authority input.
     :action                :add-held | :sub-held | ... (accounting effect only,
                            NOT an authority input).
     :scope                the exact authorized scope being requested
     :permits              the candidate force-authorisation permit collection
     :permit               convenience: a single permit candidate (treated as
                           a one-element :permits)
     :consumption-registry {authorization-id consumption-entry} (default {})
     :now-ts               current block/commit time for lifecycle checks
     :authoritative-config governance/configuration state resolved from an
                           AUTHORITATIVE source (never the request payload)

   Returns {:classification <kw>
            :override-eligible? bool
            :override-enabled? bool
            :usable-permit? bool
            :usable-permit-count int
            :blocking-reasons [kw]}.

   Override-eligibility is a property of the SEMANTIC OPERATION, never of
   :in/:out, add-held/sub-held, or disclosure sensitivity. add-held / sub-held
   are purely accounting effects.

   Precedence (explicit, deterministic, ambiguity fails closed):
     1. Unknown operation                                    -> :forbidden
     2. Ordinary (non-override-eligible) operation           -> :ordinary
        (force-auth ignored, not consumed)
     3. Override operation + override disabled/absent        -> :forbidden
     4. Override operation + override enabled, no permits    -> :forbidden
     5. Override operation + zero exact usable permits       -> :forbidden
     6. Override operation + exactly one exact usable permit -> :forbidden-authorized
     7. Override operation + >1 distinct exact usable permits -> :ambiguous-force-authorisation"
  [{:keys [operation-id scope permit consumption-registry now-ts
           authoritative-config permits]
    :as opts}]
  (let [override-eligible? (override-eligible-operation? operation-id)
        enabled? (override-enabled? authoritative-config)
        registry (or consumption-registry {})
        now (or now-ts 0)
        candidates (candidate-permits permits permit)
        usable (if (and override-eligible? (true? enabled?) (seq candidates))
                 (usable-permits candidates scope registry now)
                 [])
        usable-count (count usable)]
    (cond
      (nil? operation-id)
      {:classification :forbidden
       :override-eligible? false
       :override-enabled? enabled?
       :usable-permit? false
       :usable-permit-count 0
       :blocking-reasons [:unknown-operation]}

      (not override-eligible?)
      {:classification :ordinary
       :override-eligible? false
       :override-enabled? enabled?
       :usable-permit? false
       :usable-permit-count 0
       :force-auth-ignored? true
       :blocking-reasons []}

      (not (true? enabled?))
      {:classification :forbidden
       :override-eligible? true
       :override-enabled? enabled?
       :usable-permit? false
       :usable-permit-count 0
       :blocking-reasons [:force-authorisation-override-disabled]}

      (not (seq candidates))
      {:classification :forbidden
       :override-eligible? true
       :override-enabled? true
       :usable-permit? false
       :usable-permit-count 0
       :blocking-reasons [:missing-force-authorisation]}

      (zero? usable-count)
      {:classification :forbidden
       :override-eligible? true
       :override-enabled? true
       :usable-permit? false
       :usable-permit-count 0
       :blocking-reasons (blocking-reasons opts)}

      (= 1 usable-count)
      {:classification :forbidden-authorized
       :override-eligible? true
       :override-enabled? true
       :usable-permit? true
       :usable-permit-count 1
       :blocking-reasons []}

      :else
      {:classification :ambiguous-force-authorisation
       :override-eligible? true
       :override-enabled? true
       :usable-permit? true
       :usable-permit-count usable-count
       :usable-permits (mapv :authorization/id usable)
       :blocking-reasons [:ambiguous-force-authorisation]})))