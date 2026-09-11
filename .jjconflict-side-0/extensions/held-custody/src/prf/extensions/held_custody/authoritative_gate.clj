(ns prf.extensions.held-custody.authoritative-gate
  "Authoritative production entry point for the held-custody force-authorisation
   override gate.

   The pure classifier (prf.extensions.held-custody.authorisation-classification)
   owns semantics and accepts an `:authoritative-config` carrying the override
   posture. THIS namespace owns currentness / governance: it resolves that
   posture from authoritative configuration and extension state and never accepts
   a caller-asserted boolean.

       AuthorityStateStore / current configuration
                    ↓
       extension resolution (resolver-sim.extensions.resolution/resolve-requested)
                    ↓
       held-custody authoritative wrapper  (this namespace)
                    ↓
       pure classify-operation

   The override is enabled ONLY when ALL of the following hold, otherwise it
   fails closed (not enabled):

     - the configuration head is a valid, self-rooted configuration-head-state.v1
       (content-rooted; a stale or forged head is not valid),
     - the extension resolution is valid (resolver-sim.extensions.resolution:
       `:valid? true`, which itself rejects missing capabilities, ambiguous
       providers, dependency cycles, and unresolved schemas),
     - the held-custody force-authorisation override capability
       :held-custody/force-auth-mutation] is present
       in the resolved capabilities.

   BOUNDARY GUARD — This namespace MUST NOT import or depend on:
     - resolver-sim.protocols.sew
     - any form under protocols_src/
     - resolver-sim.evidence.force-authorisation (deleted legacy core domain)"
  (:require [resolver-sim.configuration-head :as configuration-head]
            [prf.extensions.held-custody.authorisation-classification :as gate]))

(def override-capability
  "Capability reference whose resolution authorises the held-custody exceptional
   override. The selected physical package contributes this capability; the
   authoritative resolution must select it (exactly once, non-ambiguously) for
   the override to be enabled."
  [:assurance/force-authorisation :held-custody/override-admission-v1])

(defn override-enabled-under-configuration
  "Derive the governed override posture from AUTHORITATIVE state. Returns a
   boolean. Never a caller-asserted value.

   configuration-head   — a configuration-head-state.v1 (the current head owned by
                          the AuthorityStateStore). Validated by
                          configuration-head/valid-head-state? (self-rooted,
                          content-rooted); an invalid/stale/forged head fails closed.
   extension-resolution — the authoritative resolution result from
                          resolver-sim.extensions.resolution/resolve-requested
                          ({:valid? true :resolution <snapshot>}), or a compatible
                          map. Absent, `:valid? false`, or missing the override
                          capability all fail closed."
  [configuration-head extension-resolution]
  (let [head-ok? (and (map? configuration-head)
                      (configuration-head/valid-head-state? configuration-head))
        resolution-ok? (and (map? extension-resolution)
                            (true? (:valid? extension-resolution)))
        capabilities (when resolution-ok?
                       (:extensions/capabilities (:resolution extension-resolution)))
        capability-present? (contains? capabilities override-capability)]
    (and head-ok? resolution-ok? capability-present?)))

(defn classify-under-current-configuration
  "Production entry point. Resolves the override posture from authoritative
   configuration + extension resolution, then delegates classification to the
   pure gate. The classifier stays pure and reusable; this wrapper supplies the
   posture.

   configuration-head   — the current authoritative configuration-head-state.v1.
   extension-resolution — the authoritative extension resolution result
                          (resolve-requested), or a compatible map.
opts                 — the pure classifier's opts: :operation-id :scope
                           :permits|:permit :consumption-registry :now-ts.
                           :operation-id determines override-eligibility (semantic,
                           never :in/:out or the action name). :authoritative-config
                           is supplied here.

   Returns the pure classifier result, with :override-enabled? reflecting the
   authoritative posture. Fails closed on invalid/stale configuration or
   missing/ambiguous extension: an otherwise-valid permit is classified
   :forbidden (override disabled)."
  [configuration-head extension-resolution opts]
  (let [enabled? (override-enabled-under-configuration
                  configuration-head extension-resolution)]
    (gate/classify-operation
     (merge opts
            {:authoritative-config {:force-authorisation/override-enabled enabled?}}))))