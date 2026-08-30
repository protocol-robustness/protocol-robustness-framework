(ns prf.extensions.held-custody.semantic-admission
  "Semantic-operation admission boundary for held-custody mutations.

   Resolves the add-held caveat explicitly: \"add-held forbidden by default\" is
   a rule about FORCE-AUTHORISATION-GATED semantic operations (the exceptional
   held-custody force-auth mutation), NOT about the bare accounting action
   :add-held. Production Sew uses :add-held for ordinary custody ingress too
   (escrow-principal-deposited, appeal-bond-posted, resolver-yield-accrued,
   deferred-yield-reserved, yield-accrued). Each of those is ordinarily
   authorised and neither requires nor consumes a force-authorisation permit.

   This namespace pins the admission class to a SEMANTIC OPERATION IDENTITY
   (operation id + action + reason), never to the action name alone. It exposes
   the SINGLE production admission decision (`admit-held-mutation`) that protocol
   code must consult before any low-level held mutation reaches accounting.
   Only :proceed classifications may mutate or consume.

   Layering (defense-in-depth, NOT classifier-as-sole-check):

     semantic operation
       -> admission decision (this namespace)
       -> low-level accounting (resolver-sim.protocols.sew.accounting/add-held)
       -> existing exact-scope verification (ensure-force-authorisation-usable!)
       -> held adjustment
       -> force-authorisation consumption when applicable

   BOUNDARY GUARD — This namespace MUST NOT import or depend on:
     - resolver-sim.protocols.sew        (the accounting layer is the CONSUMER)
     - any form under protocols_src/
     - resolver-sim.evidence.force-authorisation (deleted legacy core domain)"
  (:require [prf.extensions.held-custody.authoritative-gate :as authoritative-gate]
            [prf.extensions.held-custody.authorisation-classification :as gate]))

;; ── semantic operation → admission class ────────────────────────────────────

(def semantic-operation-classes
  "Semantic operation identity -> admission class. The :class value is one of:
     :ordinary                   ordinarily authorised; no force permit required
                                 or consumed
     :force-authorisation-override  ordinarily forbidden; requires current
                                 authoritative override configuration AND exactly
                                 one usable exact force-authorisation permit
     :never-overrideable         forbidden regardless of any permit
   Each entry pins the class to the operation identity and the accounting action
   it is realised as, so the rule is not inferred from the action name."
  {:held-custody/force-auth-mutation
   {:action :add-held
    :reason :force-authorisation-gated
    :class :force-authorisation-override
    :note "the exceptional force-authorisation add-held mutation; forbidden by default"}
   :sew/escrow-principal-deposited
   {:action :add-held
    :reason :escrow-principal-deposited
    :class :ordinary
    :note "canonical ordinary custody ingress on escrow creation"}
   :sew/appeal-bond-posted
   {:action :add-held
    :reason :appeal-bond-posted
    :class :ordinary
    :note "resolver appeal-bond custody ingress; ordinary"}
   :sew/resolver-yield-accrued
   {:action :add-held
    :reason :resolver-yield-accrued
    :class :ordinary
    :note "resolver yield accrual; ordinary"}
   :sew/deferred-yield-reserved
   {:action :add-held
    :reason :deferred-yield-reserved
    :class :ordinary
    :note "deferred-yield reclassification (sub then add); ordinary"}
   :sew/yield-accrued
   {:action :add-held
    :reason :yield-accrued
    :class :ordinary
    :note "yield-accrual module flow; ordinary"}
   :sew/bounty-custody-reserve
   {:action :add-held
    :reason :custody-held-adjustment
    :class :ordinary
    :note "with-bounty custody reserve (:prf.effect/custody-held-adjustment); ordinary"}})

(defn semantic-operation-class
  "Admission class for a semantic operation identity. Fails closed: an unknown
   operation identity is :never-overrideable (forbidden regardless of permit),
   so a future operation must be explicitly classified before it can execute."
  [operation-id]
  (get-in semantic-operation-classes [operation-id :class] :never-overrideable))

(defn semantic-operation
  "Resolve a semantic operation identity to its {:action :reason :class} entry."
  [operation-id]
  (get semantic-operation-classes operation-id))

;; ── single production admission decision ────────────────────────────────────

(defn override-admission
  "Exceptional override admission implementation — the capability entrypoint for
   `[:assurance/force-authorisation :held-custody/override-admission-v1]`.

   This is the extension-contributed provider that CORE resolves through the
   declared, versioned capability contract (never through an ad-hoc map key).
   Given the authoritative override inputs it returns:

     {:admission :proceed-force-authorised :permit <exact single usable permit>}
       — current authoritative override enabled, exactly one exact usable permit
     {:admission :reject :classification <kw> :blocking-reasons [...]}
       — forbidden / ambiguous / disabled / consumed / stale, before mutation

   It does NOT classify the semantic operation (core owns that) and does NOT
   re-implement exact-scope verification (it delegates to the authoritative
   gate + core validator)."
  [{:keys [scope permits consumption-registry now-ts
           configuration-head extension-resolution]}]
  (let [classified (authoritative-gate/classify-under-current-configuration
                    configuration-head extension-resolution
                    {:action :add-held
                     :scope scope
                     :permits permits
                     :consumption-registry consumption-registry
                     :now-ts now-ts})
        usable (gate/usable-permits permits scope (or consumption-registry {})
                                    (or now-ts 0))]
    (case (:classification classified)
      :forbidden-authorized
      {:admission :proceed-force-authorised
       :classification :forbidden-authorized
       :permit (first usable)}

      :ambiguous-force-authorisation
      {:admission :reject
       :classification :ambiguous-force-authorisation
       :usable-permit-count (:usable-permit-count classified)
       :blocking-reasons (:blocking-reasons classified)}

      :forbidden
      {:admission :reject
       :classification :forbidden
       :blocking-reasons (:blocking-reasons classified)}

      :ordinary
      {:admission :reject
       :classification :unexpected-ordinary
       :blocking-reasons [:force-authorisation-operation-classified-ordinary]}

      :reject)))

(defn admit-held-mutation
  "The SINGLE production admission decision for a held-custody mutation. Protocol
   code must call this before any low-level held mutation reaches accounting.
   Returns a decision map; only :proceed classifications may mutate or consume.

   opts:
     :operation-id           semantic operation identity (see
                             semantic-operation-classes)
     :scope                  the exact authorized scope being requested
     :permits                candidate force-authorisation permits (collection)
     :consumption-registry   {authorization-id consumption-entry}
     :now-ts                 current block/commit time
     :configuration-head     current authoritative configuration-head-state.v1
     :extension-resolution   authoritative extension resolution (resolve-requested)

   Returns:
     :ordinary                      -> {:admission :proceed-ordinary}
     force-authorisation-override
        exact usable permit present -> {:admission :proceed-force-authorised
                                        :permit <the exact single usable permit>}
        no usable permit            -> {:admission :reject
                                        :classification :forbidden ...}
        >1 usable permit            -> {:admission :reject
                                        :classification :ambiguous-force-authorisation ...}
        override disabled/stale/missing -> {:admission :reject
                                            :classification :forbidden ...}
     :never-overrideable             -> {:admission :reject
                                        :classification :never-overrideable}"
  [{:keys [operation-id scope permits consumption-registry now-ts
           configuration-head extension-resolution]}]
  (let [class (semantic-operation-class operation-id)
        registry (or consumption-registry {})
        now (or now-ts 0)]
    (case class
      :ordinary
      {:admission :proceed-ordinary
       :semantic-operation-class :ordinary
       :operation-id operation-id}

      :never-overrideable
      {:admission :reject
       :semantic-operation-class :never-overrideable
       :operation-id operation-id
       :classification :never-overrideable
       :blocking-reasons [:operation-not-overrideable]}

      :force-authorisation-override
      (let [classified (authoritative-gate/classify-under-current-configuration
                        configuration-head extension-resolution
                        {:action :add-held
                         :scope scope
                         :permits permits
                         :consumption-registry registry
                         :now-ts now})
            usable (gate/usable-permits permits scope registry now)]
        (case (:classification classified)
          :forbidden-authorized
          {:admission :proceed-force-authorised
           :semantic-operation-class :force-authorisation-override
           :operation-id operation-id
           :classification :forbidden-authorized
           :permit (first usable)}

          :ambiguous-force-authorisation
          {:admission :reject
           :semantic-operation-class :force-authorisation-override
           :operation-id operation-id
           :classification :ambiguous-force-authorisation
           :usable-permit-count (:usable-permit-count classified)
           :blocking-reasons (:blocking-reasons classified)}

          :forbidden
          {:admission :reject
           :semantic-operation-class :force-authorisation-override
           :operation-id operation-id
           :classification :forbidden
           :blocking-reasons (:blocking-reasons classified)}

          :ordinary
          {:admission :reject
           :semantic-operation-class :force-authorisation-override
           :operation-id operation-id
           :classification :unexpected-ordinary
           :blocking-reasons [:force-authorisation-operation-classified-ordinary]}

          :reject)))))