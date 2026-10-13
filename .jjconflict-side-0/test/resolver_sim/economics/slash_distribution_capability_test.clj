(ns resolver-sim.economics.slash-distribution-capability-test
  "Phase 2: registry-backed dispatch of slash-distribution methods.

   Built-in economics methods are extension-backed capabilities of the virtual
   :prf/core-economics package and dispatch through the same path as external
   extensions. An external extension supplies its implementation via an
   explicit extension-map; when it is unavailable, the engine reports a loud
   violation rather than silently falling back to core behaviour."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.economics.slash-distribution :as sd]
            [resolver-sim.extensions.execution :as ext-exec]
            [resolver-sim.extensions.registry :as reg]))

;; A real extension implementation living in this test namespace.
(defn quarter-gross-award
  "Capability implementation for [:economics/award-amount :fixture/quarter-gross].
   Reads an extension-specific :divisor field from the amount spec."
  [{:keys [gross-amount amount-spec]}]
  {:amount (quot gross-amount (:divisor amount-spec))
   :calculation nil})

(def quarter-gross-cap
  {:capability/kind :economics/award-amount
   :capability/id :fixture/quarter-gross
   :capability/version 1
   :capability/contract-version 1
   :entrypoint 'resolver-sim.economics.slash-distribution-capability-test/quarter-gross-award
   :input-schema :prf/award-amount-context.v1
   :output-schema :prf/calculation-result.v1})

(def quarter-gross-pack
  {:extension/id :fixture/quarter-gross-pack
   :extension/version "1.0.0"
   :extension/api-version 1
   :extension/manifest-version 1
   :extension/capabilities [quarter-gross-cap]
   :extension/status {:lifecycle :experimental
                      :distribution :external
                      :conformance :unknown
                      :reproduction :unsealed
                      :verification :structural
                      :maintenance :unmaintained
                      :adoption :untested}})

(def quarter-gross-policy
  {:schema-version "slash-distribution-policy.v1"
   :policy/id :test.policy/extension
   :policy/version 1
   :allocation {:method :weighted :scale 10000
                :weights {:test.allocation/a 10000}
                :remainder-to :test.allocation/a}
   :awards
   [{:award/id :test.award/quarter
     :amount {:method :fixture/quarter-gross :divisor 4}
     :eligibility {:trigger :test.trigger/qualified-event
                   :beneficiary-role :test.role/reporter
                   :requires-evidence-reference? true}
     :funding {:method :weighted-deduction :scale 10000
               :weights {:test.allocation/a 10000}
               :remainder-to :test.allocation/a}
     :settlement {:allocation-id :test.allocation/reward-pool
                  :obligation-kind :test.obligation/reward}}]})

(defn- extension-map
  "Core built-ins plus the quarter-gross extension."
  []
  (reg/register-package (sd/core-extension-map) quarter-gross-pack))

(defn- resolved-quarter-award
  []
  [{:award/id :test.award/quarter
    :eligibility {:trigger :test.trigger/qualified-event
                  :evidence-reference "sha256:test-evidence"}
    :beneficiary {:participant/id :test.participant/alice
                  :participant/role :test.role/reporter}}])

;; ── method → capability mapping ───────────────────────────────────────────

(deftest method->capability-key-mapping
  (is (= [:economics/award-amount :prf/rate-of-gross]
         (sd/method->capability-key :economics/award-amount :rate-of-gross)))
  (is (= [:economics/allocation :prf/weighted]
         (sd/method->capability-key :economics/allocation :weighted)))
  (is (= [:economics/funding :prf/weighted-deduction]
         (sd/method->capability-key :economics/funding :weighted-deduction)))
  (is (= [:economics/award-amount :fixture/quarter-gross]
         (sd/method->capability-key :economics/award-amount :fixture/quarter-gross))))

;; ── built-in entrypoints resolve and invoke ───────────────────────────────

(deftest built-in-entrypoints-resolve-and-invoke
  (let [rate-of-gross (get (sd/core-extension-map)
                           [:economics/award-amount :prf/rate-of-gross])]
    (is (fn? (ext-exec/resolve-entrypoint rate-of-gross)))
    (let [result (ext-exec/invoke-capability
                  rate-of-gross
                  {:gross-amount 1000
                   :amount-spec {:method :rate-of-gross
                                 :parameter-key :p
                                 :scale 10000
                                 :rounding :floor}
                   :param-values {:p 500}
                   :resolved-award nil})]
      (is (= 50 (:amount result)))
      (is (= :positive-award (get-in result [:calculation :classification]))))))

;; ── extension dispatch ────────────────────────────────────────────────────

(deftest extension-method-dispatches-through-extension-map
  (let [result (sd/build-slash-distribution
                {:gross-amount      100
                 :policy            quarter-gross-policy
                 :parameter-context {:source-root "sha256:test" :values {}}
                 :resolved-awards   (resolved-quarter-award)
                 :extension-map     (extension-map)
                 :context           {}})]
    (is (= :valid (:status result)))
    (let [dist (:distribution result)
          award (first (:distribution/awards dist))]
      (is (= 25 (:award/amount award)))
      (is (= {:test.allocation/a 25} (:funding award)))
      (is (= {:test.allocation/a 75 :test.allocation/reward-pool 25}
             (:distribution/final-allocations dist)))
      (is (= 100 (reduce + 0 (vals (:distribution/final-allocations dist))))))))

(deftest extension-method-unavailable-without-extension-map
  (testing "a method not resolvable in the dispatch registry is a loud violation"
    (let [result (sd/build-slash-distribution
                  {:gross-amount      100
                   :policy            quarter-gross-policy
                   :parameter-context {:source-root "sha256:test" :values {}}
                   :resolved-awards   (resolved-quarter-award)
                   :context           {}})]
      (is (= :invalid (:status result)))
      (is (some #(= :violation/unsupported-amount-method (:violation/id %))
                (:violations result))))))

(deftest extension-distribution-verifies-through-extension-map
  (let [dist (:distribution (sd/build-slash-distribution
                             {:gross-amount      100
                              :policy            quarter-gross-policy
                              :parameter-context {:source-root "sha256:test" :values {}}
                              :resolved-awards   (resolved-quarter-award)
                              :extension-map     (extension-map)
                              :context           {}}))
        with-map (sd/verify-distribution
                  dist
                  {:policy quarter-gross-policy
                   :parameter-context {:source-root "sha256:test" :values {}}
                   :extension-map (extension-map)})
        without-map (sd/verify-distribution
                     dist
                     {:policy quarter-gross-policy
                      :parameter-context {:source-root "sha256:test" :values {}}})]
    (is (:valid? with-map) (str "unexpected violations: " (pr-str (:violations with-map))))
    (is (not (:valid? without-map)))
    (is (some #(= :violation/unsupported-amount-method (:violation/id %))
              (:violations without-map)))))

(deftest frozen-live-registry-snapshot-drives-execution
  (reg/clear-extensions!)
  (try
    (reg/register-package! quarter-gross-pack)
    (let [snapshot (reg/freeze!)
          result (sd/build-slash-distribution
                  {:gross-amount      100
                   :policy            quarter-gross-policy
                   :parameter-context {:source-root "sha256:test" :values {}}
                   :resolved-awards   (resolved-quarter-award)
                   :extension-map     snapshot
                   :context           {}})]
      (is (= :valid (:status result)))
      (is (= 25 (-> result :distribution :distribution/awards first :award/amount))))
    (finally
      (reg/clear-extensions!))))

(deftest extension-provenance-disclosed
  (let [entry (get (extension-map) [:economics/award-amount :fixture/quarter-gross])]
    (is (reg/extension-backed? entry))
    (is (= {:kind :extension-backed
            :extension/id :fixture/quarter-gross
            :extension/version 1
            :extension/implementation-hash (:descriptor-root entry)}
           (reg/extension-backed-provenance entry)))))

;; ── extension resolution committed into distribution evidence ─────────────

(deftest distribution-commits-extension-resolution
  (let [result (sd/build-slash-distribution
                {:gross-amount      100
                 :policy            quarter-gross-policy
                 :parameter-context {:source-root "sha256:test" :values {}}
                 :resolved-awards   (resolved-quarter-award)
                 :extension-map     (extension-map)
                 :context           {}})
        dist (:distribution result)]
    (is (= :valid (:status result)))
    (is (re-matches #"[0-9a-f]{64}" (:distribution/extension-resolution-root dist)))
    (is (contains? (:distribution/extension-packages dist) :fixture/quarter-gross-pack))
    (is (contains? (:distribution/extension-packages dist) :prf/core-economics))))

(deftest builtin-only-distribution-resolution-root-deterministic
  (let [policy (assoc-in quarter-gross-policy
                         [:awards 0 :amount]
                         {:method :rate-of-gross :parameter-key :p :scale 10000 :rounding :floor})
        params {:source-root "sha256:test" :values {:p 500}}
        build #(sd/build-slash-distribution
                {:gross-amount 100 :policy policy
                 :parameter-context params
                 :resolved-awards (resolved-quarter-award)
                 :extension-map (sd/core-extension-map)
                 :context {}})
        r1 (build) r2 (build)]
    (is (= :valid (:status r1)))
    (is (= (:distribution/extension-resolution-root (:distribution r1))
           (:distribution/extension-resolution-root (:distribution r2))))
    (is (contains? (:distribution/extension-packages (:distribution r1)) :prf/core-economics))))

(deftest resolution-root-is-verified
  (let [dist (:distribution (sd/build-slash-distribution
                             {:gross-amount      100
                              :policy            quarter-gross-policy
                              :parameter-context {:source-root "sha256:test" :values {}}
                              :resolved-awards   (resolved-quarter-award)
                              :extension-map     (extension-map)
                              :context           {}}))
        ok (sd/verify-distribution
            dist
            {:policy quarter-gross-policy
             :parameter-context {:source-root "sha256:test" :values {}}
             :extension-map (extension-map)})
        tampered (-> dist
                     (assoc :distribution/extension-resolution-root "sha256:tampered")
                     (assoc :distribution/hash "ignored"))
        bad (sd/verify-distribution
             tampered
             {:policy quarter-gross-policy
              :parameter-context {:source-root "sha256:test" :values {}}
              :extension-map (extension-map)})]
    (is (:valid? ok) (str "unexpected violations: " (pr-str (:violations ok))))
    (is (not (:valid? bad)))
    (is (some #(= :violation/extension-resolution-mismatch (:violation/id %))
              (:violations bad)))))
