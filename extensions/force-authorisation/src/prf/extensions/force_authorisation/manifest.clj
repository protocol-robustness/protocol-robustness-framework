(ns prf.extensions.force-authorisation.manifest
  "Physical force-authorisation extension package. It owns pure permit/scope
   contracts and capability identities; Sew remains the temporary transactional
   adapter and is intentionally never required here."
  (:require [prf.extensions.force-authorisation.scope-verification :as scope]))

(def scope-verification-capability
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

(def governed-permit-capability
  {:capability/kind :assurance/force-authorisation
   :capability/id :force-authorisation/governed-permit-v1
   :capability/version 1
   :capability/contract-version 1
   :capability/profile :production-governed
   :entrypoint 'prf.extensions.force-authorisation.manifest/governed-permit
   :input-schema :prf/governed-force-authorisation-permit-input.v1
   :output-schema :prf/governed-force-authorisation-permit.v1
   :declared-dependencies
   [{:capability/kind :assurance/governed-authority
     :capability/id :resolver-sim/three-member-v1
     :requirement {:capability/version 1 :capability/contract-version 1
                   :capability/profile :production-governed}}]
   :composition-contract {:composition-contract/version 1
                          :composition/input {:schema-ref :prf/governed-force-authorisation-permit-input.v1}
                          :composition/output {:schema-ref :prf/governed-force-authorisation-permit.v1}}})

(def custody-execution-capability
  {:capability/kind :sew/force-authorisation
   :capability/id :force-authorisation/custody-execution-v1
   :capability/version 1
   :capability/contract-version 1
   :capability/profile :production-governed
   ;; A descriptor, not a Sew entrypoint: the adapter remains protocol-owned.
   :entrypoint 'prf.extensions.force-authorisation.manifest/custody-execution-contract
   :input-schema :sew/force-authorised-custody-execution-input.v1
   :output-schema :sew/force-authorised-custody-execution-result.v1
   :declared-dependencies
   [{:capability/kind :assurance/force-authorisation
     :capability/id :force-authorisation/governed-permit-v1
     :requirement {:capability/version 1 :capability/contract-version 1
                   :capability/profile :production-governed}}
    {:capability/kind :force-authorisation/effect-evidence
     :capability/id :held-custody/mutation
     :requirement {:capability/version 1 :capability/contract-version 1}}]
   :composition-contract {:composition-contract/version 1
                          :composition/input {:schema-ref :sew/force-authorised-custody-execution-input.v1}
                          :composition/output {:schema-ref :sew/force-authorised-custody-execution-result.v1}}})

(def package
  {:extension/id :prf.extensions/force-authorisation
   :extension/version "0.1.0"
   :extension/api-version 1
   :extension/manifest-version 1
   :extension/capabilities [scope-verification-capability
                            governed-permit-capability
                            custody-execution-capability]})

(defn scope-verification []
  {:normalize-scope scope/normalize-scope
   :normalize-permit scope/normalize-permit
   :scope-hash scope/scope-hash
   :verify-scope scope/verify-scope
   :verify-usability scope/verify-usability})

(defn governed-permit []
  {:required-assurance :governed-research-authority
   :authority-capability [:assurance/governed-authority :resolver-sim/three-member-v1]})

(defn custody-execution-contract []
  {:transaction-owner :sew-adapter
   :requires [[:assurance/force-authorisation :force-authorisation/governed-permit-v1]
              [:force-authorisation/effect-evidence :held-custody/mutation]]})
