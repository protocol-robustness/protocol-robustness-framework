(ns resolver-sim.extensions.force-authorisation-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.extensions.force-authorisation :as fa]
            [resolver-sim.extensions.registry :as reg]
            [resolver-sim.extensions.manifest :as manifest]
            [resolver-sim.extensions.resolution :as resolution]))

(def schemas
  {:sew/force-authorisation-command.v2 "sha256:command"
   :sew/force-authorisation-record.v2 "sha256:record"
   :sew/force-authorisation-governed-provenance.v1 "sha256:provenance"
   :resolver-sim/governed-authority-input.v1 "sha256:authority-input"
   :resolver-sim/governed-authority-report.v1 "sha256:authority-report"
   :prf/force-authorisation-scope.v1 "sha256:scope-input"
   :prf/force-authorisation-scope-verification.v1 "sha256:scope-output"
   :prf/governed-force-authorisation-permit-input.v1 "sha256:permit-input"
   :prf/governed-force-authorisation-permit.v1 "sha256:permit-output"
   :sew/force-authorised-custody-execution-input.v1 "sha256:custody-input"
   :sew/force-authorised-custody-execution-result.v1 "sha256:custody-output"
   :force-authorisation/effect-evidence-input.v1 "sha256:effect-input"
   :force-authorisation/effect-evidence-output.v1 "sha256:effect-output"})

(def effect-evidence-capability
  {:capability/kind :force-authorisation/effect-evidence
   :capability/id :held-custody/mutation
   :capability/version 1
   :capability/contract-version 1
   :entrypoint 'resolver-sim.protocols.sew/effect-evidence
   :input-schema :force-authorisation/effect-evidence-input.v1
   :output-schema :force-authorisation/effect-evidence-output.v1
   :composition-contract {:composition-contract/version 1
                           :composition/input {:schema-ref :force-authorisation/effect-evidence-input.v1}
                           :composition/output {:schema-ref :force-authorisation/effect-evidence-output.v1}}})

(def effect-evidence-package
  {:extension/id :force-authorisation/effect-evidence
   :extension/version "0.1.0"
   :extension/api-version 1
   :extension/manifest-version 1
   :extension/capabilities [effect-evidence-capability]
   :extension/license "Apache-2.0"
   :extension/maintainers ["PRF core"]
   :extension/support-policy :core
   :extension/funding-status :core
   :extension/status {:lifecycle :active :distribution :core
                       :conformance :conformant :reproduction :artifact-replayable
                       :verification :replayed :maintenance :supported
                       :adoption :multi-adapter}})

(defn register-effect-evidence
  "Register a mock effect-evidence provider into the extension-map."
  [extension-map]
  (reg/register-package extension-map effect-evidence-package))

(deftest governed-dependency-is-required-at-installation
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"governed authority dependency"
                        (fa/install (reg/empty-extension-map))))
  (let [emap (-> (reg/empty-extension-map)
                 fa/install-governed-authority
                 register-effect-evidence
                 fa/install)
        resolved (resolution/resolve-requested
                  emap [[(fa/capability-kind) (fa/capability-id)]] {:schemas schemas})]
    (is (fa/installed? emap))
    (is (:valid? resolved))))

(deftest wrong-governed-provider-identity-version-or-profile-is-rejected
  (doseq [bad-cap [(assoc @fa/governed-authority-capability :capability/id :resolver-sim/other-authority)
                    (assoc @fa/governed-authority-capability :capability/version 2)
                    (assoc @fa/governed-authority-capability :capability/profile :local-compatibility)]]
    (let [bad-package (assoc @fa/governed-authority-package :extension/capabilities [bad-cap])
          emap (reg/register-package (reg/empty-extension-map) bad-package)]
      (is (thrown? clojure.lang.ExceptionInfo (fa/install emap))))))

(deftest component-identity-commits-capability-and-dependency-identity
  (let [base (fa/component-root)
        changed-id (manifest/package-root
                    (assoc @fa/package :extension/capabilities
                           [(assoc @fa/capability :capability/id :sew/force-authorisation-v2)]))
        changed-dependency (manifest/package-root
                            (assoc @fa/package :extension/capabilities
                                   [(assoc-in @fa/capability
                                              [:declared-dependencies 0 :capability/id]
                                              :resolver-sim/other-authority-v1)]))
        ]
    (is (not= base changed-id))
    (is (not= base changed-dependency))))

(deftest facade-preserves-physical-identity
  (let [physical? @(ns-resolve 'resolver-sim.extensions.force-authorisation 'physical-available?)]
    (testing "capability and package are aligned with physical manifest when available"
      (when physical?
        (is (= @(ns-resolve 'prf.extensions.force-authorisation.manifest 'custody-execution-capability)
               @fa/capability))
        (is (= @fa/package @(ns-resolve 'prf.extensions.force-authorisation.manifest 'package)))))
    (testing "governed-authority package presents legacy identity in physical mode"
      (when physical?
        (let [governed-cap @fa/governed-authority-capability]
          (is (= (:capability/kind governed-cap) :assurance/governed-authority))
          (is (= (:capability/id governed-cap) :resolver-sim/three-member-v1))
          (is (nil? (:declared-dependencies governed-cap))))))))
