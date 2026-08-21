(ns resolver-sim.composition.semantic-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.composition.semantic :as semantic]
            [resolver-sim.protocols.protocol :as protocol]
            [resolver-sim.protocols.sew :as sew]))

(def resolution-root "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

(defn plain-composition []
  (semantic/build
   {:semantic-composition/protocol "sew-v1"
    :semantic-composition/profile :production-plain
    :semantic-composition/packages []
    :semantic-composition/capabilities []
    :semantic-composition/resolution-root resolution-root
    :semantic-composition/action-modules []
    :semantic-composition/state-region-modules []
    :semantic-composition/invariant-modules []
    :semantic-composition/policy-bindings {}}))

(defn force-composition []
  (semantic/build
   {:semantic-composition/protocol "sew-v1"
    :semantic-composition/profile :production-governed
    :semantic-composition/packages [{:extension/id :prf.extensions/force-authorisation
                                     :extension/package-root "sha256:force-package"}]
    :semantic-composition/capabilities
    [[:prf/force-authorisation :force-authorisation/scope-verification]
     [:assurance/force-authorisation :force-authorisation/governed-permit-v1]
     [:sew/force-authorisation :force-authorisation/custody-execution-v1]]
    :semantic-composition/resolution-root resolution-root
    :semantic-composition/action-modules [semantic/force-authorisation-action-module]
    :semantic-composition/state-region-modules [semantic/force-authorisation-state-module]
    :semantic-composition/invariant-modules [semantic/force-authorisation-invariant-module]
    :semantic-composition/policy-bindings
    {:force-authorisation {:policy/root "sha256:policy" :issuance-assurance :governed-research-authority}}}))

(deftest root-is-canonical-and-sensitive-to-active-semantics
  (let [plain (plain-composition)
        again (plain-composition)
        force (force-composition)]
    (is (:valid? (semantic/validate plain)))
    (is (= (:semantic-composition/root plain) (:semantic-composition/root again)))
    (is (not= (:semantic-composition/root plain) (:semantic-composition/root force)))
    (is (semantic/selected-capability? force [:sew/force-authorisation :force-authorisation/custody-execution-v1]))
    (is (not (semantic/selected-capability? plain [:sew/force-authorisation :force-authorisation/custody-execution-v1])))))

(deftest plain-composition-has-no-force-live-regions-or-actions
  (let [composition (plain-composition)
        world (protocol/init-world sew/protocol {:scenario-id :plain
                                                 :semantic-composition composition})]
    (is (empty? (semantic/active-actions composition)))
    (is (empty? (semantic/active-regions composition)))
    (is (nil? (:force-authorisations world)))
    (is (= :semantic-composition-action-not-permitted
           (:error (protocol/dispatch-action sew/protocol
                                             {:semantic-composition composition}
                                             world
                                             {:action "grant-force-authorisation" :params {}}))))))

(deftest force-module-selection-enables-only-declared-force-actions-and-regions
  (let [composition (force-composition)
        world (protocol/init-world sew/protocol {:scenario-id :force
                                                 :semantic-composition composition})]
    (is (contains? (semantic/active-actions composition) "grant-consensus-force-authorisation"))
    (is (contains? (semantic/active-regions composition) :force-authorisations))
    (is (= {} (:force-authorisations world)))
    (is (= :semantic-composition-action-not-permitted
           (:error (protocol/dispatch-action sew/protocol
                                             {:semantic-composition composition}
                                             world
                                             {:action "raise-dispute" :params {}}))))))
