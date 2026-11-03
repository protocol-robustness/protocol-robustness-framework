(ns prf.extensions.force-authorisation.manifest
  "Candidate package manifest for protocol-neutral scope verification.
   Governed permit issuance and Sew custody execution are not advertised here
   until their standalone implementations are qualified.

   BOUNDARY GUARD — This namespace MUST NOT import or depend on:
      - resolver-sim.protocols.sew
      - any form under protocols_src/"
  (:require [prf.extensions.force-authorisation.scope-verification :as scope]))

;; ── Scope verification ──────────────────────────────────────────────────

(def scope-verification-capability
  "Protocol-neutral force-authorisation scope verification capability.
   Consumed by held-custody to verify scope projections without importing
   Sew."
  {:capability/kind :prf/force-authorisation
   :capability/id :force-authorisation/scope-verification
   :capability/version 1
   :capability/contract-version 1
   :entrypoint 'prf.extensions.force-authorisation.manifest/scope-verification
   :input-schema :prf/force-authorisation-scope.v1
   :output-schema :prf/force-authorisation-scope-verification.v1
   :composition-contract {:composition-contract/version 1
                           :composition/input {:schema-ref :prf/force-authorisation-scope.v1}
                           :composition/output {:schema-ref :prf/force-authorisation-scope-verification.v1}}})

;; ── Package ───────────────────────────────────────────────────────────

(def package
  "Extension package manifest. Register explicitly via
   resolver-sim.extensions.registry/register-package."
  {:extension/id :prf.extensions/force-authorisation
   :extension/version "0.2.0"
   :extension/api-version 1
   :extension/manifest-version 1
   :extension/capabilities [scope-verification-capability]
   :extension/license "Apache-2.0"
   :extension/maintainers ["PRF core"]
   :extension/support-policy :core
   :extension/funding-status :core
   :extension/status {:lifecycle :incubating :distribution :source-candidate
                      :conformance :partial :reproduction :source-pinned
                      :verification :in-repository :maintenance :supported
                      :adoption :single-adapter}})

;; ── Entrypoints (symbols only — resolution deferred) ───────────────────

(defn scope-verification
  "Evaluate a single permit/scope pair through the protocol-neutral contract."
  [{:keys [permit scope-map]}]
  (if (and (map? permit) (map? scope-map))
    (assoc (scope/verify-scope permit scope-map)
           :scope/root (scope/scope-hash scope-map))
    {:valid? false :reason :invalid-scope-verification-input}))
