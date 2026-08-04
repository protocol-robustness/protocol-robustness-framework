(ns resolver-sim.extensions.registry-test
  "Phase 1: extension-map registration semantics — idempotency on identical
   descriptor roots, hard collisions, built-in protection, freeze, and
   provider tracking."
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.extensions.fixtures :as fx]
            [resolver-sim.extensions.registry :as reg]))

;; ── core seeding ──────────────────────────────────────────────────────────

(deftest core-package-is-seeded
  (let [emap (reg/extension-map)]
    (is (contains? emap [:economics/award-amount :prf/rate-of-gross]))
    (is (contains? emap [:economics/allocation :prf/weighted]))
    (is (contains? emap [:economics/funding :prf/weighted-deduction]))
    (is (= 'resolver-sim.economics.slash-distribution/rate-of-gross-award-amount
           (:entrypoint (reg/capability-descriptor emap :economics/award-amount :prf/rate-of-gross))))
    (is (contains? (set (reg/known-capability-keys emap))
                   [:economics/award-amount :prf/resolved-amount]))))

;; ── pure registration ─────────────────────────────────────────────────────

(deftest pure-register-package-adds-capability
  (let [emap (reg/register-package (reg/empty-extension-map) fx/rate-with-cap-pack)]
    (is (= fx/rate-with-cap-cap
           (reg/capability-descriptor emap :economics/award-amount :fixture/rate-with-cap)))
    (is (some #(= :fixture/rate-with-cap-pack (:package/id %))
              (reg/providers-of emap :economics/award-amount :fixture/rate-with-cap)))))

(deftest pure-registration-is-idempotent-for-identical-descriptor
  (let [once (reg/register-package (reg/empty-extension-map) fx/scaled-share-pack)
        twice (reg/register-package once fx/scaled-share-pack)]
    (is (= once twice))
    (is (= 1 (count (reg/providers-of twice :arithmetic/profile :prf/scaled-share-v1))))))

(deftest identical-descriptor-across-packages-tracks-providers
  (let [emap (-> (reg/empty-extension-map)
                 (reg/register-package fx/scaled-share-pack)
                 (reg/register-package fx/alt-scaled-share-pack))]
    (is (= 2 (count (reg/providers-of emap :arithmetic/profile :prf/scaled-share-v1))))))

(deftest collision-on-different-descriptor-throws
  (let [emap (reg/register-package (reg/empty-extension-map) fx/scaled-share-pack)
        other (assoc fx/scaled-share-cap :capability/version 99)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"collision"
         (reg/register-capability emap fx/alt-scaled-share-pack other)))
    (let [e (try (reg/register-capability emap fx/alt-scaled-share-pack other)
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (= :extensions/error-capability-collision (:error (ex-data e)))))))

(deftest builtin-capability-cannot-be-replaced
  (let [emap (reg/extension-map)
        impostor (assoc {:capability/kind :economics/award-amount
                         :capability/id :prf/rate-of-gross
                         :capability/version 1
                         :capability/contract-version 1
                         :entrypoint 'fixture.impostor/calculate}
                        :input-schema :prf/award-amount-context.v1
                        :output-schema :prf/calculation-result.v1)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"built-in"
         (reg/register-capability emap fx/scaled-share-pack impostor)))
    (let [e (try (reg/register-capability emap fx/scaled-share-pack impostor)
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (= :extensions/error-replace-builtin (:error (ex-data e)))))))

(deftest invalid-package-throws-on-register
  (let [bad (assoc fx/scaled-share-pack :extension/manifest-version 2)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"invalid package"
         (reg/register-package (reg/empty-extension-map) bad)))))

;; ── atom-backed registry lifecycle ────────────────────────────────────────

(deftest live-registry-freeze-prevents-registration
  (reg/clear-extensions!)
  (try
    (is (not (reg/frozen?)))
    (reg/register-package! fx/scaled-share-pack)
    (let [snapshot (reg/freeze!)]
      (is (reg/frozen?))
      (is (contains? snapshot [:arithmetic/profile :prf/scaled-share-v1]))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"frozen"
           (reg/register-package! fx/rate-with-cap-pack))))
    (finally
      (reg/clear-extensions!))))

(deftest live-registry-unregister
  (reg/clear-extensions!)
  (try
    (reg/register-package! fx/scaled-share-pack)
    (is (some? (reg/unregister-capability! :arithmetic/profile :prf/scaled-share-v1)))
    (is (nil? (reg/unregister-capability! :arithmetic/profile :prf/scaled-share-v1)))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"built-in"
         (reg/unregister-capability! :economics/award-amount :prf/rate-of-gross)))
    (finally
      (reg/clear-extensions!))))

(deftest extension-map-is-inspectable-data
  (reg/clear-extensions!)
  (try
    (reg/register-package! fx/scaled-share-pack)
    (let [emap (reg/extension-map)]
      (is (map? emap))
      (is (contains? emap [:arithmetic/profile :prf/scaled-share-v1]))
      (is (= #{:prf/core-economics}
             (set (map :package/id
                       (:providers (get emap [:economics/award-amount :prf/rate-of-gross])))))))
    (finally
      (reg/clear-extensions!))))
