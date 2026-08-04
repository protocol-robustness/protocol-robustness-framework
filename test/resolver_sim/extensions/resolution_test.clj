(ns resolver-sim.extensions.resolution-test
  "Phase 1: frozen resolution — transitive closure, failure conditions, and
   the content-addressed resolution root."
  (:require [clojure.test :refer [deftest is]]
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
