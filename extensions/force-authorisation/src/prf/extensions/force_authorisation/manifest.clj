(ns prf.extensions.force-authorisation.manifest
  "Extension package manifest for the production force-authorisation package.

   Exposes THREE distinct capabilities with a capability-granular dependency
   graph (not a package-import cycle):

   A. Pure scope verification:
      [:prf/force-authorisation :force-authorisation/scope-verification]
      Protocol-neutral; consumed by held-custody.

   B. Governed permit:
      [:assurance/force-authorisation :force-authorisation/governed-permit-v1]
      Requires [:assurance/governed-authority :resolver-sim/three-member-v1].

   C. Sew custody execution:
      [:sew/force-authorisation :force-authorisation/custody-execution-v1]
      Requires governed-permit + [:force-authorisation/effect-evidence :held-custody/mutation].

   BOUNDARY GUARD — This namespace MUST NOT import or depend on:
      - resolver-sim.protocols.sew
      - any form under protocols_src/"
  (:require [resolver-sim.extensions.manifest :as em]))

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

;; ── Governed permit ───────────────────────────────────────────────────

(def governed-permit-capability
  "Governed force-authorisation permit capability. Requires a production
   governed-authority provider. This IS NOT the governed-authority itself —
   it is a CONSUMER of governed-authority that issues a permit."
  {:capability/kind :assurance/force-authorisation
   :capability/id :force-authorisation/governed-permit-v1
   :capability/version 1
   :capability/contract-version 1
   :entrypoint 'prf.extensions.force-authorisation.manifest/governed-permit
   :input-schema :prf/governed-force-authorisation-permit-input.v1
   :output-schema :prf/governed-force-authorisation-permit.v1
   :composition-contract {:composition-contract/version 1
                           :composition/input {:schema-ref :prf/governed-force-authorisation-permit-input.v1}
                           :composition/output {:schema-ref :prf/governed-force-authorisation-permit.v1}}
   :declared-dependencies
   [{:capability/kind :assurance/governed-authority
     :capability/id :resolver-sim/three-member-v1
     :requirement {:capability/version 1
                   :capability/contract-version 1
                   :capability/profile :production-governed}}]
   :verification/contract :prf/governed-force-authorisation-permit-verification.v1})

;; ── Sew custody execution ─────────────────────────────────────────────

(def custody-execution-capability
  "Sew custody-execution capability. The primary force-authorisation entry
   point for Sew, owned by :sew-adapter transaction ownership. Requires both
   a governed-permit and a held-custody/mutation effect-evidence provider."
  {:capability/kind :sew/force-authorisation
   :capability/id :force-authorisation/custody-execution-v1
   :capability/version 1
   :capability/contract-version 1
   :entrypoint 'prf.extensions.force-authorisation.manifest/custody-execution-contract
   :input-schema :sew/force-authorised-custody-execution-input.v1
   :output-schema :sew/force-authorised-custody-execution-result.v1
   :composition-contract {:composition-contract/version 1
                           :composition/input {:schema-ref :sew/force-authorised-custody-execution-input.v1}
                           :composition/output {:schema-ref :sew/force-authorised-custody-execution-result.v1}}
   :declared-dependencies
   [{:capability/kind :assurance/force-authorisation
     :capability/id :force-authorisation/governed-permit-v1
     :requirement {:capability/version 1
                   :capability/contract-version 1
                   :capability/profile :production-governed}}
    {:capability/kind :force-authorisation/effect-evidence
     :capability/id :held-custody/mutation
     :requirement {:capability/version 1
                   :capability/contract-version 1}}]
   :verification/contract :sew/force-authorisation-governed-provenance.v1
   :transaction-owner :sew-adapter})

;; ── Package ───────────────────────────────────────────────────────────

(def package
  "Extension package manifest. Register explicitly via
   resolver-sim.extensions.registry/register-package."
  {:extension/id :prf.extensions/force-authorisation
   :extension/version "0.1.0"
   :extension/api-version 1
   :extension/manifest-version 1
   :extension/capabilities [scope-verification-capability
                            governed-permit-capability
                            custody-execution-capability]
   :extension/license "Apache-2.0"
   :extension/maintainers ["PRF core"]
   :extension/support-policy :core
   :extension/funding-status :core
   :extension/status {:lifecycle :active :distribution :core
                      :conformance :conformant :reproduction :artifact-replayable
                      :verification :replayed :maintenance :supported
                      :adoption :multi-adapter}})

;; ── Entrypoints (symbols only — resolution deferred) ───────────────────

(defn scope-verification
  "Scope verification entrypoint. Returns a capability-map of verification
   functions. Actual implementation deferred."
  []
  {})

(defn governed-permit
  "Governed-permit entrypoint."
  []
  {})

(defn custody-execution-contract
  "Sew custody-execution contract entrypoint."
  []
  {})
