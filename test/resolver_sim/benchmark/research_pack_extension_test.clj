(ns resolver-sim.benchmark.research-pack-extension-test
  "Extension-contributed research-pack members resolve before freeze and remain
   exact afterward. This uses the existing manifest, registry, resolution, and
   semantic-composition contracts; it introduces no parallel authority model."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.research-pack :as research-pack]
            [resolver-sim.composition.semantic :as semantic]
            [resolver-sim.extensions.fixtures :as fixtures]
            [resolver-sim.extensions.manifest :as manifest]
            [resolver-sim.extensions.registry :as registry]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def capability [:arithmetic/profile :prf/scaled-share-v1])

(defn- root [value]
  (hash-ref/sha256-ref (hc/domain-hash :research-benchmark-pack {:fixture value})))

(defn- extension-map [package]
  (registry/register-package (registry/empty-extension-map) package))

(defn- inputs [extension-map]
  {:pack-id :research/extension-member
   :command-root (root :command)
   :assignment-root (root :assignment)
   :plan-root (root :plan)
   :members [{:member/id :extension/scaled-share
              :member/contract "fixture.scaled-share.benchmark.v1"
              :member/capability capability
              :member/input-root (root :input)
              :member/parameters-root (root :parameters)
              :member/expected-outputs {:analysis/root (root :output)}}]
   :requested-capabilities [capability]
   :profile :development
   :resolution-options {:schemas {:prf/scaled-share-input.v1 (root :input-schema)
                                  :prf/calculation-result.v1 (root :output-schema)}
                        :effect-schemas {}}
   :extension-map extension-map})

(defn- freeze [package]
  (research-pack/freeze-pack (inputs (extension-map package))))

(deftest extension-member-is-resolved-frozen-and-portably-verifiable
  (let [pack (freeze fixtures/scaled-share-pack)
        member (first (:research-pack/members pack))
        provider-root (manifest/package-root fixtures/scaled-share-pack)]
    (is (= capability (:member/capability member)))
    (is (= [provider-root] (:member/provider-package-roots member)))
    (is (= (:research-pack/composition-root pack)
           (:semantic-composition/root
            (semantic/verify-portable! (:research-pack/composition pack)))))
    (is (:valid? (research-pack/validate-pack pack)))))

(deftest extension-member-resolution-fails-closed-after-freeze
  (let [pack (freeze fixtures/scaled-share-pack)
        opts (:resolution-options (inputs {}))
        alternate-map (extension-map fixtures/alt-scaled-share-pack)
        missing-map (registry/empty-extension-map)
        mutated-package (assoc fixtures/scaled-share-pack :extension/version "2.0.0")]
    (testing "the historical frozen artifact remains valid without local providers"
      (is (:valid? (research-pack/validate-pack pack))))
    (testing "the frozen provider is ready only in its exact environment"
      (is (= :ready
             (:classification
              (research-pack/verify-execution-environment
               pack (extension-map fixtures/scaled-share-pack) opts)))))
    (testing "a replacement provider does not rewrite frozen provider A"
      (is (= :execution-environment-mismatch
             (:classification
              (research-pack/verify-execution-environment pack alternate-map opts)))))
    (testing "a missing provider is unavailable, not a malformed historical pack"
      (is (= :unavailable
             (:classification
              (research-pack/verify-execution-environment pack missing-map opts)))))
    (testing "a changed package identity is an environment mismatch"
      (is (= :execution-environment-mismatch
             (:classification
              (research-pack/verify-execution-environment
               pack (extension-map mutated-package) opts)))))))

(deftest extension-member-ambiguity-is-rejected-before-freeze
  (let [ambiguous (-> (registry/empty-extension-map)
                      (registry/register-package fixtures/scaled-share-pack)
                      (registry/register-package fixtures/alt-scaled-share-pack))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (research-pack/freeze-pack (inputs ambiguous))))))

(deftest v2-pack-verifies-embedded-resolution-and-nested-tamper-ladder
  (let [pack (research-pack/freeze-pack-v2
              (inputs (extension-map fixtures/scaled-share-pack)))
        member-path [:research-pack/members 0 :member/provider-package-roots]
        changed-member (assoc-in pack member-path ["changed-provider"])
        changed-resolution (assoc-in pack [:research-pack/resolution :extensions/resolution-root]
                                     "changed-resolution")]
    (is (:valid? (research-pack/validate-pack-v2 pack)))
    (is (false? (:valid? (research-pack/validate-pack-v2 changed-member))))
    (is (false? (:valid? (research-pack/validate-pack-v2 changed-resolution))))
    (is (false? (:valid? (research-pack/validate-pack-v2
                          (assoc pack :research-pack/root "changed-pack-root")))))))

(deftest frozen-pack-member-set-cannot-be-rewritten-after-freeze
  (let [pack (freeze fixtures/scaled-share-pack)
        member (first (:research-pack/members pack))
        injected (update pack :research-pack/members conj
                         (assoc member :member/id :extension/injected))
        removed (assoc pack :research-pack/members [])]
    (is (not= (:research-pack/root pack) (research-pack/pack-root injected))
        "post-freeze member injection invalidates the frozen plan")
    (is (not= (:research-pack/root pack) (research-pack/pack-root removed))
        "post-freeze member removal invalidates the frozen plan")
    (is (= [member] (:research-pack/members pack))
        "the original frozen member set remains exact")))
