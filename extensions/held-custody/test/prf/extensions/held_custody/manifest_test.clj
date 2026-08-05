(ns prf.extensions.held-custody.manifest-test
  "Tests for the held-custody extension package manifest: strict conformance to
   the existing core extension contract, pure package registration, and the
   entrypoint capability map."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.extensions.manifest :as em]
            [resolver-sim.extensions.registry :as registry]
            [prf.extensions.held-custody.manifest :as manifest]))

(deftest manifest-conforms-to-core-extension-contract
  (let [validation (em/validate-package manifest/package)]
    (is (:valid? validation)
        (str "manifest violations: " (pr-str (:violations validation)))))
  (testing "capability identity is namespaced"
    (is (= :force-authorisation/effect-evidence (:capability/kind manifest/capability)))
    (is (= :held-custody/mutation (:capability/id manifest/capability))))
  (testing "the entrypoint is a symbol"
    (is (symbol? (:entrypoint manifest/capability)))))

(deftest pure-registration-into-an-extension-map
  (let [extension-map (registry/register-package (registry/empty-extension-map)
                                                 manifest/package)
        entry (registry/lookup-capability extension-map
                                          :force-authorisation/effect-evidence
                                          :held-custody/mutation)]
    (is (some? entry))
    (is (false? (:builtin? entry)) "the extension is not a built-in core capability")
    (is (= (em/capability-descriptor-root manifest/capability)
           (:descriptor-root entry)))
    (testing "the descriptor root is stable and content-addressed"
      (is (string? (em/capability-descriptor-root manifest/capability)))
      (is (= (em/capability-descriptor-root manifest/capability)
             (em/capability-descriptor-root manifest/capability))))))

(deftest entrypoint-returns-the-capability-map
  (let [capability-map (manifest/extension)]
    (is (= #{:build-member :check-member :build-summary :recompute-summary
             :check-aggregate :supported-actions}
           (set (keys capability-map))))
    (is (= #{:add-held :sub-held :finalize-released :refund-held}
           (set (:supported-actions capability-map))))
    (is (fn? (:build-member capability-map)))
    (is (fn? (:check-member capability-map)))
    (is (fn? (:check-aggregate capability-map)))))
