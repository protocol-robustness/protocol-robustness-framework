(ns resolver-sim.extensions.resolution-test
  "Phase 1: frozen resolution — transitive closure, failure conditions, and
   the content-addressed resolution root."
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.economics.effects :as effects]
            [resolver-sim.extensions.core :as core]
            [resolver-sim.extensions.fixtures :as fx]
            [resolver-sim.extensions.registry :as reg]
            [resolver-sim.extensions.resolution :as res]))

(defn- core-map
  []
  (reg/register-package (reg/empty-extension-map) core/core-economics-package))

(deftest resolves-builtin-capability
  (let [{:keys [valid? resolution]} (res/resolve-requested
                                     (core-map)
                                     [[:economics/award-amount :prf/rate-of-gross]]
                                     {:schemas fx/schemas})]
    (is valid?)
    (is (contains? (:extensions/capabilities resolution)
                   [:economics/award-amount :prf/rate-of-gross]))
    (is (contains? (:extensions/packages resolution) :prf/core-economics))
    (is (= "sha256:award-amount-context"
           (get-in resolution [:extensions/schema-roots :prf/award-amount-context.v1])))
    (is (= 64 (count (:extensions/resolution-root resolution))))))

(deftest resolves-transitive-closure
  (let [emap (-> (reg/empty-extension-map)
                 (reg/register-package fx/rate-with-cap-pack)
                 (reg/register-package fx/scaled-share-pack))
        {:keys [valid? resolution]}
        (res/resolve-requested emap
                               [[:economics/award-amount :fixture/rate-with-cap]]
                               {:schemas fx/schemas})]
    (is valid?)
    (is (contains? (:extensions/capabilities resolution)
                   [:arithmetic/profile :prf/scaled-share-v1]))
    (is (contains? (:extensions/packages resolution) :fixture/scaled-share-pack))
    (is (some #(and (= [:economics/award-amount :fixture/rate-with-cap] (:from %))
                    (= [:arithmetic/profile :prf/scaled-share-v1] (:to %)))
              (:extensions/dependencies resolution)))))

(deftest missing-requested-capability
  (let [r (res/resolve-requested (core-map)
                                 [[:economics/award-amount :fixture/nope]]
                                 {:schemas fx/schemas})]
    (is (not (:valid? r)))
    (is (some #(= :extensions/error-missing-capability (:violation/id %))
              (:violations r)))))

(deftest missing-dependency
  (let [emap (reg/register-package (reg/empty-extension-map) fx/missing-dep-pack)
        r (res/resolve-requested emap
                                 [[:economics/allocation :fixture/priority-order]]
                                 {:schemas fx/schemas})]
    (is (not (:valid? r)))
    (is (some #(= :extensions/error-missing-dependency (:violation/id %))
              (:violations r)))
    (is (some #(= [:arithmetic/profile :prf/does-not-exist]
                  (get-in % [:details :capability]))
              (:violations r)))))

(deftest dependency-cycle-rejected
  (let [emap (reg/register-package (reg/empty-extension-map) fx/cycle-pack)
        r (res/resolve-requested emap
                                 [[:economics/award-amount :fixture/cycle-a]]
                                 {:schemas fx/schemas})]
    (is (not (:valid? r)))
    (is (some #(= :extensions/error-dependency-cycle (:violation/id %))
              (:violations r)))))

(deftest incompatible-contract-version-rejected
  (let [emap (-> (reg/empty-extension-map)
                 (reg/register-package fx/rate-with-cap-v2-pack)
                 (reg/register-package fx/scaled-share-pack))
        r (res/resolve-requested emap
                                 [[:economics/award-amount :fixture/rate-with-cap]]
                                 {:schemas fx/schemas})]
    (is (not (:valid? r)))
    (is (some #(= :extensions/error-incompatible-contract-version (:violation/id %))
              (:violations r)))))

(deftest unsealed-provider-rejected-in-sealed-run
  (let [emap (reg/register-package (reg/empty-extension-map) fx/unsealed-pack)
        request [[:economics/funding :fixture/weighted-remainder]]
        sealed-run (res/resolve-requested emap request
                                          {:sealed? true :schemas fx/schemas})
        dev-run (res/resolve-requested emap request
                                       {:sealed? false :schemas fx/schemas})]
    (is (not (:valid? sealed-run)))
    (is (some #(= :extensions/error-unsealed-in-sealed-run (:violation/id %))
              (:violations sealed-run)))
    (is (:valid? dev-run))))

(deftest ambiguous-provider-for-requested-capability
  (let [emap (-> (reg/empty-extension-map)
                 (reg/register-package fx/scaled-share-pack)
                 (reg/register-package fx/alt-scaled-share-pack))
        r (res/resolve-requested emap
                                 [[:arithmetic/profile :prf/scaled-share-v1]]
                                 {:schemas fx/schemas})]
    (is (not (:valid? r)))
    (is (some #(= :extensions/error-ambiguous-provider (:violation/id %))
              (:violations r)))))

(deftest multiple-roots-for-exact-dependency
  (let [emap (-> (reg/empty-extension-map)
                 (reg/register-package fx/rate-with-cap-pack)
                 (reg/register-package fx/scaled-share-pack)
                 (reg/register-package fx/alt-scaled-share-pack))
        r (res/resolve-requested emap
                                 [[:economics/award-amount :fixture/rate-with-cap]]
                                 {:schemas fx/schemas})]
    (is (not (:valid? r)))
    (is (some #(= :extensions/error-multiple-dependency-roots (:violation/id %))
              (:violations r)))))

(deftest unresolved-schema-root-fails-closed
  (let [emap (reg/register-package (reg/empty-extension-map) fx/scaled-share-pack)
        r (res/resolve-requested emap
                                 [[:arithmetic/profile :prf/scaled-share-v1]]
                                 {:schemas {}})]
    (is (not (:valid? r)))
    (is (some #(= :extensions/error-unresolved-schema-root (:violation/id %))
              (:violations r)))
    (is (some #(contains? (set (get-in % [:details :unresolved]))
                          :prf/scaled-share-input.v1)
              (:violations r)))))

(deftest invalid-requested-shape-rejected
  (let [r (res/resolve-requested (core-map) [[:economics/award-amount]] {:schemas fx/schemas})]
    (is (not (:valid? r)))
    (is (some #(= :extensions/error-invalid-requested-capability (:violation/id %))
              (:violations r)))))

(deftest resolution-root-deterministic-and-sensitive
  (let [base (res/resolve-requested (reg/register-package (reg/empty-extension-map)
                                                          fx/scaled-share-pack)
                                    [[:arithmetic/profile :prf/scaled-share-v1]]
                                    {:schemas fx/schemas})
        again (res/resolve-requested (reg/register-package (reg/empty-extension-map)
                                                           fx/scaled-share-pack)
                                     [[:arithmetic/profile :prf/scaled-share-v1]]
                                     {:schemas fx/schemas})
        bumped (reg/register-package (reg/empty-extension-map)
                                     (assoc fx/scaled-share-pack :extension/version "1.0.1"))
        changed (res/resolve-requested bumped
                                       [[:arithmetic/profile :prf/scaled-share-v1]]
                                       {:schemas fx/schemas})]
    (is (= (:extensions/resolution-root (:resolution base))
           (:extensions/resolution-root (:resolution again))))
    (is (not= (:extensions/resolution-root (:resolution base))
              (:extensions/resolution-root (:resolution changed))))))

(deftest runtime-profile-committed
  (let [emap (reg/register-package (reg/empty-extension-map) fx/scaled-share-pack)
        r (res/resolve-requested emap
                                 [[:arithmetic/profile :prf/scaled-share-v1]]
                                 {:schemas fx/schemas
                                  :runtime-profile {:prf/version "0.0.0-snapshot"
                                                    :jvm/profile :jvm-21}})]
    (is (:valid? r))
    (is (= {:prf/version "0.0.0-snapshot" :jvm/profile :jvm-21}
           (get-in r [:resolution :extensions/runtime-profile])))))

(deftest empty-request-yields-empty-resolution
  (let [r (res/resolve-requested (core-map) [] {:schemas fx/schemas})]
    (is (:valid? r))
    (is (empty? (get-in r [:resolution :extensions/capabilities])))
    (is (string? (get-in r [:resolution :extensions/resolution-root])))))

;; ── fix #1: diamond deduplication ─────────────────────────────────────────

(deftest diamond-dependency-roots-deduplicated
  "The shared bottom capability is reached by two edges (diamond) and has two
   providers: the multiple-roots violation must be emitted exactly once."
  (let [emap (-> (reg/empty-extension-map)
                 (reg/register-package fx/diamond-top-pack)
                 (reg/register-package fx/diamond-bottom-pack)
                 (reg/register-package fx/alt-diamond-bottom-pack))
        r (res/resolve-requested emap
                                 [[:economics/allocation :fixture/diamond-root]]
                                 {:schemas fx/schemas})
        dep-root-violations
        (filter #(= :extensions/error-multiple-dependency-roots (:violation/id %))
                (:violations r))]
    (is (not (:valid? r)))
    (is (= 1 (count dep-root-violations)))
    (is (= [:arithmetic/profile :prf/diamond-bottom-v1]
           (get-in (first dep-root-violations) [:details :capability])))))

;; ── fix #2: effect-schemas fail-closed ────────────────────────────────────

(deftest unknown-effect-schema-id-rejected
  (let [r (res/resolve-requested (core-map)
                                 [[:economics/award-amount :prf/rate-of-gross]]
                                 {:schemas fx/schemas
                                  :effect-schemas {:prf.effect/bogus-typo.v1 "sha256:nope"}})]
    (is (not (:valid? r)))
    (is (some #(and (= :extensions/error-unknown-effect-schema (:violation/id %))
                    (= [:prf.effect/bogus-typo.v1] (get-in % [:details :unknown])))
              (:violations r)))))

(deftest known-effect-schema-committed-to-snapshot
  (let [effect-schemas (select-keys effects/effect-schema-roots
                                    [:prf.effect/balance-credit.v1])
        r (res/resolve-requested (core-map)
                                 [[:economics/award-amount :prf/rate-of-gross]]
                                 {:schemas fx/schemas
                                  :effect-schemas effect-schemas})]
    (is (:valid? r))
    (is (= effect-schemas
           (get-in r [:resolution :extensions/effect-schema-roots])))))

;; ── fix #3: sealed run rejects :source-pinned ─────────────────────────────

(deftest source-pinned-provider-rejected-in-sealed-run
  (let [emap (reg/register-package (reg/empty-extension-map) fx/source-pinned-pack)
        request [[:economics/funding :fixture/weighted-remainder]]
        sealed-run (res/resolve-requested emap request
                                          {:sealed? true :schemas fx/schemas})
        dev-run (res/resolve-requested emap request
                                       {:sealed? false :schemas fx/schemas})]
    (is (not (:valid? sealed-run)))
    (is (some #(= [:fixture/source-pinned-pack]
                  (get-in % [:details :unsealed-providers]))
              (:violations sealed-run)))
    (is (:valid? dev-run))))

;; ── fix #4: optional dependencies ─────────────────────────────────────────

(deftest absent-optional-dependency-resolves
  (let [emap (reg/register-package (reg/empty-extension-map)
                                   fx/optional-dep-absent-pack)
        r (res/resolve-requested emap
                                 [[:economics/allocation :fixture/opt-dep-absent]]
                                 {:schemas fx/schemas})]
    (is (:valid? r))
    (is (contains? (get-in r [:resolution :extensions/capabilities])
                   [:economics/allocation :fixture/opt-dep-absent]))))

(deftest present-optional-dependency-resolved-into-closure
  (let [emap (-> (reg/empty-extension-map)
                 (reg/register-package fx/optional-dep-present-pack)
                 (reg/register-package fx/scaled-share-pack))
        r (res/resolve-requested emap
                                 [[:economics/allocation :fixture/opt-dep-present]]
                                 {:schemas fx/schemas})]
    (is (:valid? r))
    (is (contains? (get-in r [:resolution :extensions/capabilities])
                   [:arithmetic/profile :prf/scaled-share-v1]))
    (is (some #(= [:arithmetic/profile :prf/scaled-share-v1] (:to %))
              (get-in r [:resolution :extensions/dependencies])))))

;; ── fix #5: transitive ambiguity ──────────────────────────────────────────

(deftest ambiguous-transitive-provider-reported
  "The ambiguity is one hop below the requested capability: it must still be
   reported as ambiguous."
  (let [emap (-> (reg/empty-extension-map)
                 (reg/register-package fx/rate-with-cap-pack)
                 (reg/register-package fx/scaled-share-pack)
                 (reg/register-package fx/alt-scaled-share-pack))
        r (res/resolve-requested emap
                                 [[:economics/award-amount :fixture/rate-with-cap]]
                                 {:schemas fx/schemas})]
    (is (not (:valid? r)))
    (is (some #(and (= :extensions/error-ambiguous-provider (:violation/id %))
                    (= [:arithmetic/profile :prf/scaled-share-v1]
                       (get-in % [:details :capability])))
              (:violations r)))))

;; ── cycle coverage gaps ───────────────────────────────────────────────────

(deftest self-loop-cycle-rejected
  (let [emap (reg/register-package (reg/empty-extension-map) fx/self-cycle-pack)
        r (res/resolve-requested emap
                                 [[:economics/award-amount :fixture/self-cycle]]
                                 {:schemas fx/schemas})]
    (is (not (:valid? r)))
    (is (some #(= :extensions/error-dependency-cycle (:violation/id %))
              (:violations r)))
    (is (some #(= #{[:economics/award-amount :fixture/self-cycle]}
                  (set (:cycle (:details %))))
              (filter #(= :extensions/error-dependency-cycle (:violation/id %))
                      (:violations r))))))

(deftest transitive-only-cycle-rejected
  (let [emap (reg/register-package (reg/empty-extension-map)
                                   fx/transitive-cycle-pack)
        r (res/resolve-requested emap
                                 [[:economics/award-amount :fixture/tc-root]]
                                 {:schemas fx/schemas})]
    (is (not (:valid? r)))
    (is (some #(= :extensions/error-dependency-cycle (:violation/id %))
              (:violations r)))
    (is (some #(= #{[:economics/funding :fixture/tc-b]
                    [:economics/allocation :fixture/tc-c]}
                  (set (:cycle (:details %))))
              (filter #(= :extensions/error-dependency-cycle (:violation/id %))
                      (:violations r))))))

;; ── requirement coverage gaps ─────────────────────────────────────────────

(deftest nil-requirement-dependency-resolves
  (let [consumer {:capability/kind :economics/award-amount
                  :capability/id :fixture/no-req-consumer
                  :capability/version 1
                  :capability/contract-version 1
                  :entrypoint 'fixture.noreq/consumer
                  :input-schema :prf/award-amount-context.v1
                  :output-schema :prf/calculation-result.v1
                  :declared-dependencies
                  [{:capability/kind :arithmetic/profile
                    :capability/id :prf/scaled-share-v1}]}
        consumer-pack {:extension/id :fixture/no-req-consumer-pack
                       :extension/version "1.0.0"
                       :extension/api-version 1
                       :extension/manifest-version 1
                       :extension/capabilities [consumer]}
        emap (-> (reg/empty-extension-map)
                 (reg/register-package consumer-pack)
                 (reg/register-package fx/scaled-share-pack))
        r (res/resolve-requested emap
                                 [[:economics/award-amount :fixture/no-req-consumer]]
                                 {:schemas fx/schemas})]
    (is (:valid? r))))

(deftest profile-mismatch-rejected
  (let [emap (-> (reg/empty-extension-map)
                 (reg/register-package fx/profiled-scaled-share-pack)
                 (reg/register-package fx/profile-mismatch-consumer-pack))
        r (res/resolve-requested emap
                                 [[:economics/award-amount :fixture/profile-mismatch-consumer]]
                                 {:schemas fx/schemas})]
    (is (not (:valid? r)))
    (is (some #(= :extensions/error-incompatible-contract-version (:violation/id %))
              (:violations r)))
    (is (some #(= {:capability/profile :jvm-17}
                  (:requirement (:details %)))
              (filter #(= :extensions/error-incompatible-contract-version
                          (:violation/id %))
                      (:violations r))))))
