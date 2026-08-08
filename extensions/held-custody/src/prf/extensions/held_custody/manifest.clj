(ns prf.extensions.held-custody.manifest
  "Extension package manifest for the held-custody mutation capability.

   Aligned strictly with the existing core extension contract
   (resolver-sim.extensions.manifest / resolver-sim.extensions.registry):
   namespaced capability identity, the existing composition-contract shape,
   map-form declared dependencies, a symbol-form entrypoint, pure package
   registration, and content-addressed package/capability roots.

   Explicit registration only — the extension is never discovered from the
   classpath.

   BOUNDARY GUARD — This namespace MUST NOT import or depend on:
     - resolver-sim.protocols.sew
     - any form under protocols_src/"
  (:require [prf.extensions.held-custody.mutation :as mutation]
            [prf.extensions.held-custody.aggregate :as aggregate]))

(def capability
  "Capability descriptor for the held-custody mutation evidence capability."
  {:capability/kind :force-authorisation/effect-evidence
   :capability/id :held-custody/mutation
   :capability/version 1
   :capability/contract-version 1
   :entrypoint 'prf.extensions.held-custody.manifest/extension
   :input-schema :prf/force-authorised-held-mutation-input.v1
   :output-schema :prf/force-authorised-held-mutation-artifact.v1
   :verification/contract :prf/force-authorised-effect-verification.v1
   :composition-contract
   {:composition-contract/version 1
    :composition/input {:schema-ref :prf/force-authorised-held-mutation-input.v1
                        :semantic-type :mutation
                        :cardinality :one}
    :composition/output {:schema-ref :prf/force-authorised-held-mutation-artifact.v1
                         :semantic-type :artifact
                         :cardinality :one}}
   :declared-dependencies
   [{:capability/kind :prf/content-addressed-artifacts
     :capability/id :envelope}
    {:capability/kind :prf/force-authorisation
     :capability/id :scope-verification}]})

(def historical-read-contract
  "Permanent, machine-readable historical-read contract for this package.

   Distinguishes the current production capability from the frozen historical
   artifact classes the extension is contractually committed to verifying, and
   marks historical PRODUCTION as forbidden. Future cleanup may not remove a
   listed historical reader without changing this explicit contract (the
   contract is validated by the extension registry and committed into the
   package root).

     current production:      held-custody mutation v1
     historical read support: add-held v1/v2 verification,
                              summary v1/v2 verification
     historical production:   forbidden"
  {:current-production
   {:capability/id :held-custody/mutation
    :schema-version "force-auth-held-custody-mutation.v1"}

   :historical-read
   [{:schema-version "force-auth-add-held.v1"
     :artifact/kind :force-auth-add-held
     :read-only true}
    {:schema-version "force-auth-add-held.v2"
     :artifact/kind :force-auth-add-held
     :read-only true}
    {:schema-version "force-auth-add-held-summary.v1"
     :artifact/kind :force-auth-add-held-summary
     :read-only true}
    {:schema-version "force-auth-add-held-summary.v2"
     :artifact/kind :force-auth-add-held-summary
     :read-only true}]

   :historical-production :forbidden})

(def package
  "Extension package manifest. Register explicitly via
   resolver-sim.extensions.registry/register-package (pure) or
   register-package! (live registry)."
  {:extension/id :prf.extensions/held-custody
   :extension/version "0.1.0"
   :extension/api-version 1
   :extension/manifest-version 1
   :extension/capabilities [capability]
   :extension/historical-read historical-read-contract})

(defn extension
  "Capability map returned by the manifest entrypoint. Names and shapes match
   the registry's established expectations for effect-evidence capabilities.
   Entrypoints are recorded as symbols in Phase 1; Var resolution and runtime
   dispatch are deferred."
  []
  {:build-member mutation/build-force-auth-held-mutation
   :check-member mutation/check-force-auth-held-mutation
   :build-summary aggregate/build-held-mutation-summary
   :recompute-summary aggregate/recompute-held-mutation-summary
   :check-aggregate aggregate/check-held-mutation-aggregate
   :supported-actions mutation/supported-actions})
