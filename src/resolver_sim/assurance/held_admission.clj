(ns resolver-sim.assurance.held-admission
  "Core-owned held-custody mutation admission boundary.

   Separates two concerns:

     A. SEMANTIC OPERATION CLASSIFICATION (core-owned, no optional extension):
        maps a semantic operation identity to :ordinary /
        :force-authorisation-override / :never-overrideable. Ordinary ingress
        (escrow-principal-deposited, appeal-bond-posted, resolver-yield-accrued,
        deferred-yield-reserved, yield-accrued, bounty-custody-reserve,
        pro-rata-held-credit) is available WITHOUT the optional held-custody
        override extension. Only :held-custody/force-auth-mutation requires the
        exceptional capability.

     B. EXCEPTIONAL OVERRIDE RESOLUTION (authoritatively selected physical
        package): for :force-authorisation-override operations, the boundary
        resolves the override implementation from an EXPLICIT, ROOTED
        extension-resolution snapshot supplied by the caller (the same
        resolve-requested architecture used across core), NOT from the global
        live registry. The implementation is resolved through the package's
        declared, versioned capability contract — not an ad-hoc map key.

        The distinction is enforced:
          physically installed  (package present / registered)          - NOT sufficient
          selected              (named in some resolution)              - NOT sufficient
          authoritatively selected/current (named by the supplied rooted
                                          resolution, exact identity pinned) - ONLY this
        authorises exceptional execution.

        The boundary fails closed on: capability absent; wrong version / contract;
        duplicate/ambiguous providers; descriptor/root disagreement; selected
        provider unavailable. A merely-registered alternate implementation can
        never substitute for the provider named by the explicit rooted resolution.

   Core Sew calls `admit-held-mutation` (this boundary) before any low-level
   held mutation reaches accounting. The low-level accounting mutation
   (resolver-sim.protocols.sew.accounting/add-held) remains the effect
   implementation and independently verifies exact force-authorisation scope
   and consumption (defense-in-depth, not classifier-as-sole-check).

   BOUNDARY GUARD — This namespace MUST NOT import or depend on:
     - resolver-sim.protocols.sew         (the accounting layer is the CONSUMER)
     - any form under protocols_src/
     - prf.extensions.*                   (resolved only at runtime via the
                                          extension registry; never a compile dep)
     - resolver-sim.evidence.force-authorisation (deleted legacy core domain)"
  (:require [resolver-sim.extensions.resolution :as resolution]))

;; ── A. semantic operation classification (core-owned) ───────────────────────

(def semantic-operation-classes
  "Semantic operation identity -> admission class. Ordinary ingress operations
   are classified here and are available without the optional held-custody
   override extension. Only :held-custody/force-auth-mutation requires the
   exceptional capability. Unknown operations fail closed to
   :never-overrideable. Closed by design (see changelog: governed
   extension-contributed operation classes are a future, non-implemented
   authority expansion)."
  {:held-custody/force-auth-mutation :force-authorisation-override
   :sew/escrow-principal-deposited   :ordinary
   :sew/appeal-bond-posted           :ordinary
   :sew/resolver-yield-accrued       :ordinary
   :sew/deferred-yield-reserved      :ordinary
   :sew/yield-accrued                :ordinary
   :sew/bounty-custody-reserve       :ordinary
   :sew/pro-rata-held-credit         :ordinary})

(defn semantic-operation-class
  "Admission class for a semantic operation identity. Fails closed: an unknown
   operation identity is :never-overrideable (forbidden regardless of any
   permit), so a future operation must be explicitly classified before it can
   execute."
  [operation-id]
  (get semantic-operation-classes operation-id :never-overrideable))

;; ── B. exceptional override resolution (rooted selection) ───────────────────

(def override-capability
  "The EXACT declared capability identity the authoritative resolution must
   select for the exceptional override. Version, contract-version, and
   verification-contract are pinned here; a selected capability that disagrees
   fails closed."
  {:capability/kind :assurance/force-authorisation
   :capability/id :held-custody/override-admission-v1
   :capability/version 1
   :capability/contract-version 1
   :verification/contract :prf/held-custody-override-admission-verification.v1})

(def override-capability-key
  "The [capability-kind capability-id] key of the override capability."
  [(:capability/kind override-capability) (:capability/id override-capability)])

(defn- normalize-extension-resolution
  "Normalize a caller-supplied extension-resolution to {:valid? bool
   :resolution <snapshot>}. Accepts the resolve-requested result
   ({:valid? true :resolution <snapshot>}) or a bare snapshot. Returns nil when
   the input is unusable (not a map / invalid result)."
  [extension-resolution]
  (cond
    (and (map? extension-resolution) (contains? extension-resolution :valid?))
    (when (true? (:valid? extension-resolution))
      {:valid? true :resolution (:resolution extension-resolution)})

    (map? extension-resolution)
    {:valid? true :resolution extension-resolution}

    :else nil))

(defn- capability-projections
  "The resolved capability projections from a snapshot (map key -> projection)."
  [snapshot]
  (:extensions/capabilities snapshot))

(defn- provider-roots-of
  "Provider package roots for a capability key from the snapshot's
   capability-providers binding."
  [snapshot key]
  (:providers (get-in snapshot [:extensions/capability-providers key] [])))

(defn- projection-pinned?
  "True when the selected capability projection agrees with the pinned override
   capability identity (kind, id, version, contract-version, verification)."
  [projection]
  (and (= (:capability/kind projection) (:capability/kind override-capability))
       (= (:capability/id projection) (:capability/id override-capability))
       (= (:capability/version projection) (:capability/version override-capability))
       (= (:capability/contract-version projection)
          (:capability/contract-version override-capability))
       (= (:verification/contract projection)
          (:verification/contract override-capability))))

(defn- resolve-entrypoint
  "Resolve the entrypoint symbol named by a capability projection to its Var
   value (a function), lazily requiring the namespace. Returns nil when the
   namespace or Var is unavailable (selected provider unavailable)."
  [projection]
  (when-let [entrypoint-str (:entrypoint projection)]
    (try
      (let [sym (symbol entrypoint-str)]
        (require (symbol (namespace sym)))
        (when-let [v (resolve sym)]
          @v))
      (catch java.io.FileNotFoundException _ nil)
      (catch clojure.lang.ExceptionInfo _ nil))))

(defn resolve-exceptional-override-implementation
  "Resolve the authoritatively-selected physical held-custody override admission
   implementation from an EXPLICIT, ROOTED extension-resolution snapshot.

   extension-resolution — the resolve-requested result ({:valid? true
                          :resolution <snapshot>}) or a bare snapshot, derived by
                          the caller from authoritative configuration/extension
                          state (Slice C). Never read from the global registry.

   Returns {:available? bool
            :implementation <fn or nil>
            :reason <kw or nil>}
   where :reason ∈ #{:resolution-invalid :resolution-unrooted :capability-absent
                     :capability-wrong-identity :ambiguous-providers
                     :provider-unavailable}."
  [extension-resolution]
  (let [normalized (normalize-extension-resolution extension-resolution)]
    (if (nil? normalized)
      {:available? false :implementation nil :reason :resolution-invalid}
      (let [snapshot (:resolution normalized)]
        (if-not (try (resolution/verify-portable! snapshot) true
                     (catch Throwable _ false))
          {:available? false :implementation nil :reason :resolution-unrooted}
          (let [projection (get (capability-projections snapshot) override-capability-key)]
            (cond
              (nil? projection)
              {:available? false :implementation nil :reason :capability-absent}

              (not (projection-pinned? projection))
              {:available? false :implementation nil :reason :capability-wrong-identity}

              (not= 1 (count (provider-roots-of snapshot override-capability-key)))
              {:available? false :implementation nil :reason :ambiguous-providers}

              :else
              (if-let [impl (resolve-entrypoint projection)]
                {:available? true :implementation impl :reason nil}
                {:available? false :implementation nil :reason :provider-unavailable}))))))))

;; ── single core admission decision ──────────────────────────────────────────

(defn admit-held-mutation
  "The single core-owned admission decision for a held-custody mutation. Core
   Sew calls this before any low-level held mutation reaches accounting.

   opts:
     :operation-id           semantic operation identity (see
                             semantic-operation-classes)
     :scope                  the exact authorized scope being requested
     :permits                candidate force-authorisation permits (collection)
     :consumption-registry   {authorization-id consumption-entry}
     :now-ts                 current block/commit time
     :configuration-head     current authoritative configuration-head-state.v1
                             (used only by the override path)
     :extension-resolution   the EXPLICIT, ROOTED extension-resolution snapshot
                             (resolve-requested result) naming the authoritatively
                             selected override capability (used only by the
                             override path)

   Returns:
     :ordinary                      -> {:admission :proceed-ordinary}
     :force-authorisation-override  -> delegated to the authoritatively selected
                                       physical override implementation;
                                       :proceed-force-authorised with the exact
                                       permit, or :reject (forbidden / ambiguous /
                                       disabled / consumed / not-selected /
                                       unavailable)
     :never-overrideable             -> {:admission :reject}
   Rejected classifications change nothing (no mutation, no consumption)."
  [{:keys [operation-id scope permits consumption-registry now-ts
           configuration-head extension-resolution]}]
  (let [class (semantic-operation-class operation-id)]
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
      (let [{:keys [available? implementation reason]}
            (resolve-exceptional-override-implementation extension-resolution)]
        (if-not available?
          {:admission :reject
           :semantic-operation-class :force-authorisation-override
           :operation-id operation-id
           :classification :forbidden
           :blocking-reasons [(or reason :exceptional-override-capability-unavailable)]}
          (implementation
           {:scope scope
            :permits permits
            :consumption-registry consumption-registry
            :now-ts now-ts
            :configuration-head configuration-head
            :extension-resolution extension-resolution}))))))