(ns resolver-sim.economics.slash-distribution-steps-test
  "Phase 4: typed sequential composition via an ordered :steps policy.

   Steps are order-significant: each resolves a declarative basis
   (:distribution/gross, :remaining, or :step/output), computes through the
   capability registry, and consumes a running pool. Order is committed in
   evidence (:distribution/steps), never normalised."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.economics.slash-distribution :as sd]
            [resolver-sim.extensions.registry :as reg]))

(defn constant-award
  "Extension capability: returns a fixed :amount from the amount spec,
   ignoring the resolved basis. Used to force pool overdrafts."
  [{:keys [amount-spec]}]
  {:amount (:amount amount-spec)
   :calculation nil})

(def constant-cap
  {:capability/kind :economics/award-amount
   :capability/id :fixture/constant-amount
   :capability/version 1
   :capability/contract-version 1
   :entrypoint 'resolver-sim.economics.slash-distribution-steps-test/constant-award
   :input-schema :prf/award-amount-context.v1
   :output-schema :prf/calculation-result.v1
   :composition-contract {:composition-contract/version 1
                          :composition/input {:schema-ref :prf/award-amount-context.v1
                                              :semantic-type :amount
                                              :cardinality :one}
                          :composition/output {:schema-ref :prf/calculation-result.v1
                                               :semantic-type :amount
                                               :cardinality :one}
                          :composition/roles #{:step}
                          :composition/modes #{:sequential}}})

(def constant-pack
  {:extension/id :fixture/constant-pack
   :extension/version "1.0.0"
   :extension/api-version 1
   :extension/manifest-version 1
   :extension/capabilities [constant-cap]})

(defn- constant-map
  []
  (reg/register-package (sd/core-extension-map) constant-pack))

(defn- step
  [id basis amount-param & {:keys [on-ineligible trigger resolved-trigger]
                            :or {trigger :test.trigger/qualified-event}}]
  (let [rt (or resolved-trigger trigger)]
    {:step/id id
     :basis basis
     :amount {:method :rate-of-gross
              :parameter-key (keyword "test.parameter" (name amount-param))
              :scale 10000
              :rounding :floor}
     :eligibility {:trigger trigger
                   :beneficiary-role :test.role/reporter
                   :requires-evidence-reference? true}
     :resolved {:trigger rt
                :evidence-reference (str "sha256:step-" (name id))
                :beneficiary {:participant/id :test.participant/alice
                              :participant/role :test.role/reporter}}
     :funding {:method :weighted-deduction :scale 10000
               :weights {:test.allocation/a 10000}
               :remainder-to :test.allocation/a}
     :settlement {:allocation-id :test.allocation/reward-pool
                  :obligation-kind :test.obligation/reward}
     :on-ineligible (or on-ineligible :omit)}))

(defn- policy-with-steps
  [steps]
  {:schema-version "slash-distribution-policy.v1"
   :policy/id :test.policy/steps
   :policy/version 1
   :allocation {:method :weighted :scale 10000
                :weights {:test.allocation/a 10000}
                :remainder-to :test.allocation/a}
   :steps steps})

(defn- params
  [& values]
  {:source-root "sha256:test"
   :values (into {} (map (fn [[k v]] [(keyword "test.parameter" (name k)) v]))
                 (partition 2 values))})

;; ── single step, gross basis ──────────────────────────────────────────────

(deftest single-step-gross-basis
  (let [policy (policy-with-steps [(step :step/one {:source :distribution/gross} :p :rate 500)])
        result (sd/build-slash-distribution
                {:gross-amount 1000 :policy policy
                 :parameter-context (params :p 500)
                 :resolved-awards [] :extension-map (sd/core-extension-map)
                 :context {}})]
    (is (= :valid (:status result)))
    (let [dist (:distribution result)
          steps (:distribution/steps dist)]
      (is (= 50 (-> dist :distribution/awards first :award/amount)))
      (is (= [{:step/id :step/one
               :step/index 0
               :step/status :applied
               :step/basis {:source :distribution/gross}
               :step/basis-value 1000
               :step/amount 50
               :step/remaining 950
               :step/on-ineligible :omit
               :step/on-failure :abort}]
             steps))
      (is (re-matches #"[0-9a-f]{64}" (:distribution/hash dist))))))

;; ── sequential :remaining chaining ────────────────────────────────────────

(deftest sequential-remaining-chaining
  (let [policy (policy-with-steps [(step :step/one {:source :distribution/gross} :p :rate 500)
                                   (step :step/two {:source :remaining} :q :rate 500)])
        result (sd/build-slash-distribution
                {:gross-amount 1000 :policy policy
                 :parameter-context (params :p 500 :q 500)
                 :resolved-awards [] :extension-map (sd/core-extension-map)
                 :context {}})
        dist (:distribution result)
        steps (:distribution/steps dist)]
    (is (= :valid (:status result)))
    (is (= [50 47] (mapv :award/amount (:distribution/awards dist)))
        "step 2 basis = pool after step 1 (950) → floor(950*500/10000) = 47")
    (is (= [950 903] (mapv :step/remaining steps)))
    (is (= [1000 950] (mapv :step/basis-value steps)))
    (is (= {:test.allocation/a 903 :test.allocation/reward-pool 97}
           (:distribution/final-allocations dist)))
    (is (= 1000 (reduce + 0 (vals (:distribution/final-allocations dist)))))))

;; ── :step/output reference ────────────────────────────────────────────────

(deftest step-output-reference
  (let [policy (policy-with-steps [(step :step/one {:source :distribution/gross} :p :rate 500)
                                   (step :step/two {:source :step/output
                                                    :step-id :step/one
                                                    :field :remaining} :q :rate 500)])
        result (sd/build-slash-distribution
                {:gross-amount 1000 :policy policy
                 :parameter-context (params :p 500 :q 500)
                 :resolved-awards [] :extension-map (sd/core-extension-map)
                 :context {}})]
    (is (= :valid (:status result)))
    (let [dist (:distribution result)
          steps (:distribution/steps dist)]
      (is (= 950 (-> steps second :step/basis-value)))
      (is (= 47 (-> dist :distribution/awards second :award/amount))))))

;; ── ineligible step omitted, pool unchanged ───────────────────────────────

(deftest ineligible-step-omitted
  (let [policy (policy-with-steps [(step :step/one {:source :distribution/gross} :p
                                         :rate 500 :resolved-trigger :test.trigger/other)
                                   (step :step/two {:source :remaining} :q :rate 500)])
        result (sd/build-slash-distribution
                {:gross-amount 1000 :policy policy
                 :parameter-context (params :p 500 :q 500)
                 :resolved-awards [] :extension-map (sd/core-extension-map)
                 :context {}})]
    (is (= :valid (:status result)))
    (let [dist (:distribution result)
          steps (:distribution/steps dist)]
      (is (= [:skipped-ineligible :applied] (mapv :step/status steps)))
      (is (= [50] (mapv :award/amount (:distribution/awards dist)))
          "skipped step consumed nothing; step 2 basis = full pool (1000)")
      (is (= 1000 (-> steps second :step/basis-value))))))

;; ── failure semantics ─────────────────────────────────────────────────────

(deftest forward-step-reference-rejected
  (let [policy (policy-with-steps [(step :step/one {:source :distribution/gross} :p)
                                   (step :step/two {:source :step/output
                                                    :step-id :step/three
                                                    :field :remaining} :q)])
        {:keys [valid? violations]} (sd/validate-policy policy)]
    (is (not valid?))
    (is (some #(= :violation/forward-step-reference (:violation/id %)) violations))))

(deftest mixed-awards-and-steps-rejected
  (let [policy (assoc (policy-with-steps [(step :step/one {:source :distribution/gross} :p)])
                      :awards [])
        {:keys [valid? violations]} (sd/validate-policy policy)]
    (is (not valid?))
    (is (some #(= :violation/mixed-awards-and-steps (:violation/id %)) violations))))

(deftest invalid-step-basis-rejected
  (let [policy (policy-with-steps [(step :step/one {:source :nonsense} :p)])
        {:keys [valid? violations]} (sd/validate-policy policy)]
    (is (not valid?))
    (is (some #(= :violation/invalid-step-basis (:violation/id %)) violations))))

(deftest unresolved-step-basis-at-runtime
  (testing "referencing a skipped step's output resolves to nil and aborts"
    (let [policy (policy-with-steps
                  [(step :step/one {:source :distribution/gross} :p
                         :rate 500 :resolved-trigger :test.trigger/other)
                   (step :step/two {:source :step/output
                                    :step-id :step/one
                                    :field :remaining} :q :rate 500)])
          result (sd/build-slash-distribution
                  {:gross-amount 1000 :policy policy
                   :parameter-context (params :p 500 :q 500)
                   :resolved-awards [] :extension-map (sd/core-extension-map)
                   :context {}})]
      (is (= :invalid (:status result)))
      (is (some #(= :violation/unresolved-step-basis (:violation/id %))
                (:violations result))))))

(deftest step-overdraws-pool-rejected
  (let [policy (assoc (policy-with-steps
                       [(step :step/one {:source :distribution/gross} :p :rate 10000)
                        {:step/id :step/two
                         :basis {:source :remaining}
                         :amount {:method :fixture/constant-amount :amount 100}
                         :eligibility {:trigger :test.trigger/qualified-event
                                       :beneficiary-role :test.role/reporter
                                       :requires-evidence-reference? true}
                         :resolved {:trigger :test.trigger/qualified-event
                                    :evidence-reference "sha256:step-two"
                                    :beneficiary {:participant/id :test.participant/bob
                                                  :participant/role :test.role/reporter}}
                         :funding {:method :weighted-deduction :scale 10000
                                   :weights {:test.allocation/a 10000}
                                   :remainder-to :test.allocation/a}
                         :settlement {:allocation-id :test.allocation/reward-pool
                                      :obligation-kind :test.obligation/reward}}])
                      :policy/id :test.policy/steps)
        result (sd/build-slash-distribution
                {:gross-amount 100 :policy policy
                 :parameter-context (params :p 10000)
                 :resolved-awards [] :extension-map (constant-map)
                 :context {}})]
    ;; step 1 consumes the full pool (100); step 2 requires 100 of 0 remaining
    (is (= :invalid (:status result)))
    (is (some #(= :violation/step-overdraws-pool (:violation/id %))
              (:violations result)))))

;; ── ordering significance ─────────────────────────────────────────────────

(deftest reordering-steps-changes-policy-and-result
  (let [s1 (step :step/one {:source :distribution/gross} :p :rate 500)
        s2 (step :step/two {:source :remaining} :q :rate 1000)
        p-a (policy-with-steps [s1 s2])
        p-b (policy-with-steps [s2 s1])
        build (fn [p] (sd/build-slash-distribution
                       {:gross-amount 1000 :policy p
                        :parameter-context (params :p 500 :q 1000)
                        :resolved-awards [] :extension-map (sd/core-extension-map)
                        :context {}}))
        r-a (build p-a) r-b (build p-b)]
    (is (= :valid (:status r-a)))
    (is (= :valid (:status r-b)))
    (is (not= (sd/policy-hash p-a) (sd/policy-hash p-b)))
    (is (not= (mapv :award/amount (:distribution/awards (:distribution r-a)))
              (mapv :award/amount (:distribution/awards (:distribution r-b))))
        "declared order is semantically significant")))

;; ── verification ──────────────────────────────────────────────────────────

(deftest steps-distribution-verifies
  (let [policy (policy-with-steps [(step :step/one {:source :distribution/gross} :p :rate 500)
                                   (step :step/two {:source :remaining} :q :rate 500)])
        params (params :p 500 :q 500)
        dist (:distribution (sd/build-slash-distribution
                             {:gross-amount 1000 :policy policy
                              :parameter-context params
                              :resolved-awards [] :extension-map (sd/core-extension-map)
                              :context {}}))
        {:keys [valid? violations]}
        (sd/verify-distribution dist
                                {:policy policy
                                 :parameter-context params
                                 :extension-map (sd/core-extension-map)})]
    (is valid? (str "unexpected violations: " (pr-str violations)))
    (is (= 2 (count (:distribution/steps dist))))))

(deftest steps-evidence-tampering-detected
  (let [policy (policy-with-steps [(step :step/one {:source :distribution/gross} :p :rate 500)])
        params (params :p 500)
        dist (:distribution (sd/build-slash-distribution
                             {:gross-amount 1000 :policy policy
                              :parameter-context params
                              :resolved-awards [] :extension-map (sd/core-extension-map)
                              :context {}}))
        tampered (-> dist
                     (assoc-in [:distribution/steps 0 :step/amount] 999)
                     (assoc :distribution/hash "ignored"))
        {:keys [valid? violations]}
        (sd/verify-distribution tampered
                                {:policy policy
                                 :parameter-context params
                                 :extension-map (sd/core-extension-map)})]
    (is (not valid?))
    (is (some #(and (= :violation/recomputation-mismatch (:violation/id %))
                    (= :steps-evidence (get-in % [:details :field])))
              violations))))
