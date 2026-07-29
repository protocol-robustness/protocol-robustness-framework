(ns resolver-sim.economics.slash-distribution-test
  "Phase 1 tests for the implementation-independent slash distribution engine.

   All fixtures use opaque, neutral identifiers:
     :test.allocation/a, :test.allocation/b, :test.allocation/c
     :test.award/reward, :test.award/bonus

   No SEW identifiers appear anywhere."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.economics.slash-distribution :as sd]))

;; ── test policy fixture ──────────────────────────────────────────────────

(def ^:private all-scales-10000-policy
  "Policy with scale=10000 for allocation, award amount, and funding."
  {:schema-version "slash-distribution-policy.v1"
   :policy/id      :test.policy/default
   :policy/version 1
   :allocation
   {:method       :weighted
    :scale        10000
    :weights      {:test.allocation/a 5000
                   :test.allocation/b 3000
                   :test.allocation/c 2000}
    :remainder-to :test.allocation/c}
   :awards
   [{:award/id :test.award/reward
     :amount
     {:method        :rate-of-gross
      :parameter-key :test.parameter/reward-rate
      :scale         10000
      :rounding      :floor}
     :eligibility
     {:trigger                    :test.trigger/qualified-event
      :beneficiary-role           :test.role/reporter
      :requires-evidence-reference? true}
     :funding
     {:method       :weighted-deduction
      :scale        10000
      :weights      {:test.allocation/a 5000
                     :test.allocation/b 5000}
      :remainder-to :test.allocation/b}
     :settlement
     {:allocation-id   :test.allocation/reward-pool
      :obligation-kind :test.obligation/reward}}]})

(def ^:private resolved-reward
  "Standard resolved award matching all-scales-10000-policy."
  {:award/id :test.award/reward
   :eligibility {:trigger :test.trigger/qualified-event
                 :evidence-reference "sha256:test-eligibility-001"}
   :beneficiary {:participant/id :test.participant/alice
                 :participant/role :test.role/reporter}})

(def ^:private reward-param-500
  {:source-root "sha256:test-params-001"
   :values {:test.parameter/reward-rate 500}})

;; ═════════════════════════════════════════════════════════════════════════
;; 1. Policy validation
;; ═════════════════════════════════════════════════════════════════════════

(deftest validate-policy-accepts-valid
  (let [{:keys [valid? violations]} (sd/validate-policy all-scales-10000-policy)]
    (is valid?)
    (is (= 0 (count violations)))))

(deftest validate-policy-rejects-non-v1-schema
  (let [policy (assoc all-scales-10000-policy :schema-version "slash-distribution-policy.v99")
        {:keys [valid? violations]} (sd/validate-policy policy)]
    (is (not valid?))
    (is (= 1 (count violations)))
    (is (= :violation/invalid-policy-schema-version (-> violations first :violation/id)))))

(deftest validate-policy-rejects-missing-allocation
  (let [policy (dissoc all-scales-10000-policy :allocation)
        {:keys [valid? violations]} (sd/validate-policy policy)]
    (is (not valid?))
    (is (= :violation/missing-allocation (-> violations first :violation/id)))))

(deftest validate-policy-rejects-invalid-allocation-scale
  (let [policy (assoc-in all-scales-10000-policy [:allocation :scale] 0)
        {:keys [valid? violations]} (sd/validate-policy policy)]
    (is (not valid?))
    (is (= :violation/invalid-allocation-scale (-> violations first :violation/id)))))

(deftest validate-policy-rejects-weights-sum-mismatch
  (let [policy (assoc-in all-scales-10000-policy [:allocation :weights] {:test.allocation/a 5000 :test.allocation/b 3000})
        {:keys [valid? violations]} (sd/validate-policy policy)]
    (is (not valid?))
    (is (= :violation/allocation-weights-sum-mismatch (-> violations first :violation/id)))))

(deftest validate-policy-rejects-invalid-remainder
  (let [policy (assoc-in all-scales-10000-policy [:allocation :remainder-to] :test.allocation/NONEXISTENT)
        {:keys [valid? violations]} (sd/validate-policy policy)]
    (is (not valid?))
    (is (= :violation/invalid-allocation-remainder (-> violations first :violation/id)))))

(deftest validate-policy-rejects-duplicate-award-ids
  (let [award (first (:awards all-scales-10000-policy))
        policy (assoc all-scales-10000-policy :awards [award award])
        {:keys [valid? violations]} (sd/validate-policy policy)]
    (is (not valid?))
    (is (= :violation/duplicate-award-id (-> violations first :violation/id)))))

(deftest validate-policy-rejects-missing-eligibility
  (let [award (-> (first (:awards all-scales-10000-policy)) (dissoc :eligibility))
        policy (assoc all-scales-10000-policy :awards [award])
        {:keys [valid? violations]} (sd/validate-policy policy)]
    (is (not valid?))
    (is (= :violation/missing-eligibility (-> violations first :violation/id)))))

(deftest validate-policy-rejects-missing-funding-spec
  (let [award (-> (first (:awards all-scales-10000-policy)) (dissoc :funding))
        policy (assoc all-scales-10000-policy :awards [award])
        {:keys [valid? violations]} (sd/validate-policy policy)]
    (is (not valid?))
    (is (= :violation/missing-funding-spec (-> violations first :violation/id)))))

(deftest validate-policy-rejects-unsupported-rounding
  (let [policy (assoc-in all-scales-10000-policy [:awards 0 :amount :rounding] :floor-with-largest-remainder)
        {:keys [valid? violations]} (sd/validate-policy policy)]
    (is (not valid?))
    (is (= :violation/unsupported-rounding (-> violations first :violation/id)))))

(deftest validate-policy-rejects-unknown-funding-source
  (let [policy (-> all-scales-10000-policy
                   (assoc-in [:awards 0 :funding :weights] {:test.allocation/NONEXISTENT 10000})
                   (assoc-in [:awards 0 :funding :remainder-to] :test.allocation/NONEXISTENT))
        {:keys [valid? violations]} (sd/validate-policy policy)]
    (is (not valid?))
    (is (= :violation/unknown-funding-source (-> violations first :violation/id)))))

(deftest validate-policy-rejects-missing-settlement
  (let [award (-> (first (:awards all-scales-10000-policy)) (dissoc :settlement))
        policy (assoc all-scales-10000-policy :awards [award])
        {:keys [valid? violations]} (sd/validate-policy policy)]
    (is (not valid?))
    (is (= :violation/missing-settlement-spec (-> violations first :violation/id)))))

;; ═════════════════════════════════════════════════════════════════════════
;; 2. Base allocation
;; ═════════════════════════════════════════════════════════════════════════

(deftest base-allocation-simple-split
  (let [result (sd/build-slash-distribution
                {:gross-amount      100
                 :policy            all-scales-10000-policy
                 :parameter-context reward-param-500
                 :resolved-awards   [resolved-reward]
                 :context           {:test.context/source-reference "source-1"}})]
    (is (= :valid (:status result)))
    (let [{:keys [:distribution/base-allocations]} (:distribution result)]
      (is (= {:test.allocation/a 50
              :test.allocation/b 30
              :test.allocation/c 20}
             base-allocations))
      (is (= 100 (reduce + 0 (vals base-allocations)))))))

(deftest base-allocation-zero-gross
  (let [result (sd/build-slash-distribution
                {:gross-amount      0
                 :policy            all-scales-10000-policy
                 :parameter-context reward-param-500
                 :resolved-awards   []
                 :context           {}})]
    (is (= :valid (:status result)))
    (is (= 0 (apply + (vals (:distribution/base-allocations (:distribution result))))))))

(deftest base-allocation-remainder
  (testing "remainder is assigned to :remainder-to destination"
    (let [policy (-> all-scales-10000-policy
                     (assoc-in [:allocation :weights] {:test.allocation/a 1
                                                       :test.allocation/b 1
                                                       :test.allocation/c 1})
                     (assoc-in [:allocation :scale] 3)
                     (assoc-in [:allocation :remainder-to] :test.allocation/a))
          result (sd/build-slash-distribution
                  {:gross-amount      10
                   :policy            policy
                   :parameter-context reward-param-500
                   :resolved-awards   []
                   :context           {}})]
      (is (= :valid (:status result)))
      (let [base (:distribution/base-allocations (:distribution result))]
        ;; quot(10*1,3)=3 per source → sum=9, remainder=1 → added to :a
        (is (= 4 (get base :test.allocation/a)))
        (is (= 3 (get base :test.allocation/b)))
        (is (= 3 (get base :test.allocation/c)))
        (is (= 10 (reduce + 0 (vals base))))))))

;; ═════════════════════════════════════════════════════════════════════════
;; 3. Single award, single source
;; ═════════════════════════════════════════════════════════════════════════

(deftest single-award-single-source
  (let [policy (-> all-scales-10000-policy
                   (assoc-in [:awards 0 :funding :weights] {:test.allocation/a 10000})
                   (assoc-in [:awards 0 :funding :remainder-to] :test.allocation/a))
        params {:source-root "sha256:test"
                :values {:test.parameter/reward-rate 1000}}
        result (sd/build-slash-distribution
                {:gross-amount      1000
                 :policy            policy
                 :parameter-context params
                 :resolved-awards   [resolved-reward]
                 :context           {}})]
    (is (= :valid (:status result)))
    (let [dist (:distribution result)
          award (first (:distribution/awards dist))]
      (is (= 100 (:award/amount award)))
      (is (= {:test.allocation/a 100} (:funding award)))
      (let [final (:distribution/final-allocations dist)]
        (is (= 400 (get final :test.allocation/a)))
        (is (= 300 (get final :test.allocation/b)))
        (is (= 200 (get final :test.allocation/c)))
        (is (= 100 (get final :test.allocation/reward-pool)))))))

;; ═════════════════════════════════════════════════════════════════════════
;; 4. Single award, multi-source (50/50 pattern)
;; ═════════════════════════════════════════════════════════════════════════

(deftest single-award-multi-source
  (let [params {:source-root "sha256:test"
                :values {:test.parameter/reward-rate 1000}}
        result (sd/build-slash-distribution
                {:gross-amount      100
                 :policy            all-scales-10000-policy
                 :parameter-context params
                 :resolved-awards   [resolved-reward]
                 :context           {}})]
    (is (= :valid (:status result)))
    (let [dist (:distribution result)
          award (first (:distribution/awards dist))]
      (is (= 10 (:award/amount award)))
      ;; 50/50 split; even so insurance=5, protocol=5
      (is (= {:test.allocation/a 5 :test.allocation/b 5} (:funding award)))
      (let [final (:distribution/final-allocations dist)]
        (is (= 45 (get final :test.allocation/a)))
        (is (= 25 (get final :test.allocation/b)))
        (is (= 20 (get final :test.allocation/c)))
        (is (= 10 (get final :test.allocation/reward-pool)))
        (is (= 100 (reduce + 0 (vals final))))))))

(deftest single-award-odd-bounty-split
  (testing "odd award amount: remainder goes to remainder-to (protocol)"
    (let [params {:source-root "sha256:test"
                  :values {:test.parameter/reward-rate 500}}   ;; 1000 * 500 / 10000 = 50
          result (sd/build-slash-distribution
                  {:gross-amount      1000
                   :policy            all-scales-10000-policy
                   :parameter-context params
                   :resolved-awards   [resolved-reward]
                   :context           {}})]
      (is (= :valid (:status result)))
      (let [award (first (:distribution/awards (:distribution result)))
            funding (:funding award)]
        (is (= 50 (:award/amount award)))
        ;; quot(50, 2) = 25 for each, but remainder 0 since 50 is even
        (is (= 25 (get funding :test.allocation/a)))
        (is (= 25 (get funding :test.allocation/b)))))))

(deftest single-award-odd-remainder-bounty
  (testing "odd award amount with remainder: remainder goes to remainder-to"
    (let [params {:source-root "sha256:test"
                  :values {:test.parameter/reward-rate 7}}     ;; 1000 * 7 / 10000 = 0 (floor)
          gross 10000
          params {:source-root "sha256:test"
                  :values {:test.parameter/reward-rate 50}}    ;; 10000 * 50 / 10000 = 50
          result (sd/build-slash-distribution
                  {:gross-amount      gross
                   :policy            all-scales-10000-policy
                   :parameter-context params
                   :resolved-awards   [resolved-reward]
                   :context           {}})]
      ;; 50 / 2 = 25 each, even → no remainder
      (is (= :valid (:status result))))))

(deftest zero-award-omitted
  (let [params {:source-root "sha256:test"
                :values {:test.parameter/reward-rate 0}}
        result (sd/build-slash-distribution
                {:gross-amount      100
                 :policy            all-scales-10000-policy
                 :parameter-context params
                 :resolved-awards   [resolved-reward]
                 :context           {}})]
    (is (= :valid (:status result)))
    (let [dist (:distribution result)]
      ;; zero award is omitted
      (is (= [] (:distribution/awards dist)))
      ;; base = final (no deductions)
      (is (= (:distribution/base-allocations dist)
             (:distribution/final-allocations dist))))))

;; ═════════════════════════════════════════════════════════════════════════
;; 5. Multiple awards
;; ═════════════════════════════════════════════════════════════════════════

(def ^:private two-award-policy
  (update all-scales-10000-policy :awards
          conj {:award/id :test.award/bonus
                :amount
                {:method        :rate-of-gross
                 :parameter-key :test.parameter/bonus-rate
                 :scale         10000
                 :rounding      :floor}
                :eligibility
                {:trigger                    :test.trigger/secondary-event
                 :beneficiary-role           :test.role/validator
                 :requires-evidence-reference? true}
                :funding
                {:method       :weighted-deduction
                 :scale        10000
                 :weights      {:test.allocation/a 10000}
                 :remainder-to :test.allocation/a}
                :settlement
                {:allocation-id   :test.allocation/bonus-pool
                 :obligation-kind :test.obligation/bonus}}))

(deftest multiple-awards-non-overlapping-sources
  (let [params {:source-root "sha256:test"
                :values {:test.parameter/reward-rate 500
                         :test.parameter/bonus-rate  300}}
        bonus-award {:award/id :test.award/bonus
                     :eligibility {:trigger :test.trigger/secondary-event
                                   :evidence-reference "sha256:test-bonus-001"}
                     :beneficiary {:participant/id :test.participant/bob
                                   :participant/role :test.role/validator}}
        result (sd/build-slash-distribution
                {:gross-amount      1000
                 :policy            two-award-policy
                 :parameter-context params
                 :resolved-awards   [resolved-reward bonus-award]
                 :context           {}})]
    (is (= :valid (:status result)))
    (let [dist (:distribution result)
          awards (:distribution/awards dist)
          bw-award (first (filter #(= :test.award/bonus (:award/id %)) awards))
          rw-award (first (filter #(= :test.award/reward (:award/id %)) awards))]
      ;; reward: 1000 * 500 / 10000 = 50, funding from a+b
      (is (= 50 (:award/amount rw-award)))
      (is (= 25 (get-in rw-award [:funding :test.allocation/a])))
      (is (= 25 (get-in rw-award [:funding :test.allocation/b])))
      ;; bonus: 1000 * 300 / 10000 = 30, funding all from a
      (is (= 30 (:award/amount bw-award)))
      (is (= 30 (get-in bw-award [:funding :test.allocation/a])))
      ;; aggregate deductions: a=55, b=25
      ;; base: a=500, b=300, c=200
      ;; final: a=445, b=275, c=200, reward=50, bonus=30
      (let [final (:distribution/final-allocations dist)]
        (is (= 445 (get final :test.allocation/a)))
        (is (= 275 (get final :test.allocation/b)))
        (is (= 200 (get final :test.allocation/c)))
        (is (= 50 (get final :test.allocation/reward-pool)))
        (is (= 30 (get final :test.allocation/bonus-pool)))
        (is (= 1000 (reduce + 0 (vals final))))))))

(deftest multiple-awards-shared-settlement
  (testing "two awards settling into the same destination"
    (let [policy (-> two-award-policy
                     (assoc-in [:awards 1 :settlement :allocation-id] :test.allocation/reward-pool))
          params {:source-root "sha256:test"
                  :values {:test.parameter/reward-rate 500
                           :test.parameter/bonus-rate  300}}
          bonus-award {:award/id :test.award/bonus
                       :eligibility {:trigger :test.trigger/secondary-event
                                     :evidence-reference "sha256:test-bonus-001"}
                       :beneficiary {:participant/id :test.participant/bob
                                     :participant/role :test.role/validator}}
          result (sd/build-slash-distribution
                  {:gross-amount      1000
                   :policy            policy
                   :parameter-context params
                   :resolved-awards   [resolved-reward bonus-award]
                   :context           {}})]
      (is (= :valid (:status result)))
      ;; both awards settle into :test.allocation/reward-pool → 50+30=80
      (let [final (:distribution/final-allocations (:distribution result))]
        (is (= 80 (get final :test.allocation/reward-pool)))))))

;; ═════════════════════════════════════════════════════════════════════════
;; 6. Settlement overlapping base
;; ═════════════════════════════════════════════════════════════════════════

(deftest settlement-overlaps-base
  (testing "award settles into an existing base allocation"
    (let [policy (-> all-scales-10000-policy
                     (assoc-in [:awards 0 :settlement :allocation-id] :test.allocation/c))
          params {:source-root "sha256:test"
                  :values {:test.parameter/reward-rate 1000}}
          result (sd/build-slash-distribution
                  {:gross-amount      100
                   :policy            policy
                   :parameter-context params
                   :resolved-awards   [resolved-reward]
                   :context           {}})]
      (is (= :valid (:status result)))
      ;; base: a=50, b=30, c=20
      ;; award=10, funding a=5, b=5
      ;; settlement into c → c gets +10
      ;; final: a=45, b=25, c=20+10=30
      (let [final (:distribution/final-allocations (:distribution result))]
        (is (= 45 (get final :test.allocation/a)))
        (is (= 25 (get final :test.allocation/b)))
        (is (= 30 (get final :test.allocation/c)))
        (is (= 100 (reduce + 0 (vals final))))))))

;; ═════════════════════════════════════════════════════════════════════════
;; 7. Source overdraft
;; ═════════════════════════════════════════════════════════════════════════

(deftest source-overdraft
  (testing "two awards drawing from same under-capacity source"
    (let [policy two-award-policy
          params {:source-root "sha256:test"
                  :values {:test.parameter/reward-rate 5000  ;; 1000 * 5000/10000 = 500
                           :test.parameter/bonus-rate  5000}} ;; 1000 * 5000/10000 = 500
          bonus-award {:award/id :test.award/bonus
                       :eligibility {:trigger :test.trigger/secondary-event
                                     :evidence-reference "sha256:test-bonus-001"}
                       :beneficiary {:participant/id :test.participant/bob
                                     :participant/role :test.role/validator}}
          result (sd/build-slash-distribution
                  {:gross-amount      1000
                   :policy            policy
                   :parameter-context params
                   :resolved-awards   [resolved-reward bonus-award]
                   :context           {}})]
      (is (= :invalid (:status result)))
      (is (= :violation/source-overdrawn (-> result :violations first :violation/id)))
      ;; reward funds a at 250, bonus funds a at 500 → total 750 > base a=500
      (is (= :test.allocation/a (get-in result [:violations 0 :details :source-id]))))))

;; ═════════════════════════════════════════════════════════════════════════
;; 8. Missing / incorrect inputs
;; ═════════════════════════════════════════════════════════════════════════

(deftest missing-beneficiary
  (let [award (-> resolved-reward (assoc-in [:beneficiary :participant/id] nil))
        result (sd/build-slash-distribution
                {:gross-amount      100
                 :policy            all-scales-10000-policy
                 :parameter-context reward-param-500
                 :resolved-awards   [award]
                 :context           {}})]
    (is (= :invalid (:status result)))
    (is (= :violation/missing-beneficiary (-> result :violations first :violation/id)))))

(deftest missing-eligibility-reference
  (let [award (-> resolved-reward (assoc-in [:eligibility :evidence-reference] nil))
        result (sd/build-slash-distribution
                {:gross-amount      100
                 :policy            all-scales-10000-policy
                 :parameter-context reward-param-500
                 :resolved-awards   [award]
                 :context           {}})]
    (is (= :invalid (:status result)))
    (is (= :violation/missing-eligibility-reference (-> result :violations first :violation/id)))))

(deftest unknown-award-id
  (let [award {:award/id :test.award/NONEXISTENT
               :eligibility {:trigger :test.trigger/qualified-event
                             :evidence-reference "sha256:test"}
               :beneficiary {:participant/id :test.participant/alice
                             :participant/role :test.role/reporter}}
        result (sd/build-slash-distribution
                {:gross-amount      100
                 :policy            all-scales-10000-policy
                 :parameter-context reward-param-500
                 :resolved-awards   [award]
                 :context           {}})]
    (is (= :invalid (:status result)))
    (is (= :violation/unknown-award-id (-> result :violations first :violation/id)))))

(deftest duplicate-resolved-award-ids
  (let [result (sd/build-slash-distribution
                {:gross-amount      100
                 :policy            all-scales-10000-policy
                 :parameter-context reward-param-500
                 :resolved-awards   [resolved-reward resolved-reward]
                 :context           {}})]
    (is (= :invalid (:status result)))
    (is (= :violation/duplicate-resolved-award-id (-> result :violations first :violation/id)))))

(deftest trigger-mismatch
  (let [award (assoc-in resolved-reward [:eligibility :trigger] :test.trigger/WRONG)
        result (sd/build-slash-distribution
                {:gross-amount      100
                 :policy            all-scales-10000-policy
                 :parameter-context reward-param-500
                 :resolved-awards   [award]
                 :context           {}})]
    (is (= :invalid (:status result)))
    (is (= :violation/trigger-mismatch (-> result :violations first :violation/id)))))

(deftest beneficiary-role-mismatch
  (let [award (assoc-in resolved-reward [:beneficiary :participant/role] :test.role/WRONG)
        result (sd/build-slash-distribution
                {:gross-amount      100
                 :policy            all-scales-10000-policy
                 :parameter-context reward-param-500
                 :resolved-awards   [award]
                 :context           {}})]
    (is (= :invalid (:status result)))
    (is (= :violation/beneficiary-role-mismatch (-> result :violations first :violation/id)))))

;; ═════════════════════════════════════════════════════════════════════════
;; 9. Parameter resolution
;; ═════════════════════════════════════════════════════════════════════════

(deftest missing-parameter
  (let [result (sd/build-slash-distribution
                {:gross-amount      100
                 :policy            all-scales-10000-policy
                 :parameter-context {:source-root "sha256:empty"
                                     :values {}}
                 :resolved-awards   [resolved-reward]
                 :context           {}})]
    (is (= :invalid (:status result)))
    (is (= :violation/missing-parameter (-> result :violations first :violation/id)))))

(deftest invalid-parameter-value-negative
  (let [params {:source-root "sha256:test"
                :values {:test.parameter/reward-rate -1}}
        result (sd/build-slash-distribution
                {:gross-amount      100
                 :policy            all-scales-10000-policy
                 :parameter-context params
                 :resolved-awards   [resolved-reward]
                 :context           {}})]
    (is (= :invalid (:status result)))
    (is (= :violation/invalid-parameter-value (-> result :violations first :violation/id)))))

(deftest rate-out-of-range
  (let [params {:source-root "sha256:test"
                :values {:test.parameter/reward-rate 15000}}
        result (sd/build-slash-distribution
                {:gross-amount      100
                 :policy            all-scales-10000-policy
                 :parameter-context params
                 :resolved-awards   [resolved-reward]
                 :context           {}})]
    (is (= :invalid (:status result)))
    (is (= :violation/rate-out-of-range (-> result :violations first :violation/id)))))

;; ═════════════════════════════════════════════════════════════════════════
;; 10. Separate scales
;; ═════════════════════════════════════════════════════════════════════════

(deftest separate-scales
  (let [policy {:schema-version "slash-distribution-policy.v1"
                :policy/id      :test.policy/separate-scales
                :policy/version 1
                :allocation
                {:method       :weighted
                 :scale        1000
                 :weights      {:test.allocation/a 500
                                :test.allocation/b 300
                                :test.allocation/c 200}
                 :remainder-to :test.allocation/c}
                :awards
                [{:award/id :test.award/reward
                  :amount
                  {:method        :rate-of-gross
                   :parameter-key :test.parameter/reward-rate
                   :scale         100
                   :rounding      :floor}
                  :eligibility
                  {:trigger                    :test.trigger/qualified-event
                   :beneficiary-role           :test.role/reporter
                   :requires-evidence-reference? true}
                  :funding
                  {:method       :weighted-deduction
                   :scale        10000
                   :weights      {:test.allocation/a 5000
                                  :test.allocation/b 5000}
                   :remainder-to :test.allocation/b}
                  :settlement
                  {:allocation-id   :test.allocation/reward-pool
                   :obligation-kind :test.obligation/reward}}]}
        params {:source-root "sha256:test"
                :values {:test.parameter/reward-rate 50}}   ;; 50/100 = 50% of gross
        result (sd/build-slash-distribution
                {:gross-amount      1000
                 :policy            policy
                 :parameter-context params
                 :resolved-awards   [resolved-reward]
                 :context           {}})]
    (is (= :valid (:status result)))
    (let [dist (:distribution result)
          base (:distribution/base-allocations dist)
          award (first (:distribution/awards dist))]
      ;; allocation scale=1000: 1000*500/1000=500, 1000*300/1000=300, 1000*200/1000=200
      (is (= {:test.allocation/a 500 :test.allocation/b 300 :test.allocation/c 200} base))
      ;; award amount: 1000 * 50 / 100 = 500 (using award scale, not allocation scale)
      (is (= 500 (:award/amount award)))
      ;; funding: 500 * 5000/10000 = 250 from each
      (is (= 250 (get-in award [:funding :test.allocation/a])))
      (is (= 250 (get-in award [:funding :test.allocation/b]))))))

;; ═════════════════════════════════════════════════════════════════════════
;; 11. Ordering independence
;; ═════════════════════════════════════════════════════════════════════════

(deftest ordering-independence
  (let [params {:source-root "sha256:test"
                :values {:test.parameter/reward-rate 500
                         :test.parameter/bonus-rate  300}}
        bonus-award {:award/id :test.award/bonus
                     :eligibility {:trigger :test.trigger/secondary-event
                                   :evidence-reference "sha256:test-bonus-001"}
                     :beneficiary {:participant/id :test.participant/bob
                                   :participant/role :test.role/validator}}
        r1 (sd/build-slash-distribution
            {:gross-amount      1000
             :policy            two-award-policy
             :parameter-context params
             :resolved-awards   [resolved-reward bonus-award]
             :context           {}})
        r2 (sd/build-slash-distribution
            {:gross-amount      1000
             :policy            two-award-policy
             :parameter-context params
             :resolved-awards   [bonus-award resolved-reward]
             :context           {}})]
    (is (= :valid (:status r1)))
    (is (= :valid (:status r2)))
    ;; final allocations identical regardless of input order
    (is (= (:distribution/final-allocations (:distribution r1))
           (:distribution/final-allocations (:distribution r2))))
    ;; awards sorted canonically by :award/id
    (is (= (mapv :award/id (:distribution/awards (:distribution r1)))
           [:test.award/bonus :test.award/reward]))
    (is (= (mapv :award/id (:distribution/awards (:distribution r2)))
           [:test.award/bonus :test.award/reward]))
    ;; hash identical
    (is (= (:distribution/hash (:distribution r1))
           (:distribution/hash (:distribution r2))))))

;; ═════════════════════════════════════════════════════════════════════════
;; 12. Policy-root binding
;; ═════════════════════════════════════════════════════════════════════════

(deftest policy-root-absent-is-computed
  (let [result (sd/build-slash-distribution
                {:gross-amount      100
                 :policy            all-scales-10000-policy
                 :parameter-context reward-param-500
                 :resolved-awards   [resolved-reward]
                 :context           {}})]
    (is (= :valid (:status result)))
    (is (string? (:distribution/policy-root (:distribution result))))
    (is (= (sd/policy-hash all-scales-10000-policy)
           (:distribution/policy-root (:distribution result))))))

(deftest policy-root-matching-accepted
  (let [root (sd/policy-hash all-scales-10000-policy)
        result (sd/build-slash-distribution
                {:gross-amount      100
                 :policy            all-scales-10000-policy
                 :policy-root       root
                 :parameter-context reward-param-500
                 :resolved-awards   [resolved-reward]
                 :context           {}})]
    (is (= :valid (:status result)))
    (is (= root (:distribution/policy-root (:distribution result))))))

(deftest policy-root-mismatch-rejected
  (let [result (sd/build-slash-distribution
                {:gross-amount      100
                 :policy            all-scales-10000-policy
                 :policy-root       "sha256:wrong-root"
                 :parameter-context reward-param-500
                 :resolved-awards   [resolved-reward]
                 :context           {}})]
    (is (= :invalid (:status result)))
    (is (= :violation/policy-root-mismatch (-> result :violations first :violation/id)))))

;; ═════════════════════════════════════════════════════════════════════════
;; 13. Hash determinism
;; ═════════════════════════════════════════════════════════════════════════

(deftest hash-determinism
  (let [r1 (sd/build-slash-distribution
            {:gross-amount      100
             :policy            all-scales-10000-policy
             :parameter-context reward-param-500
             :resolved-awards   [resolved-reward]
             :context           {:test.context/id :test-ctx}})
        r2 (sd/build-slash-distribution
            {:gross-amount      100
             :policy            all-scales-10000-policy
             :parameter-context reward-param-500
             :resolved-awards   [resolved-reward]
             :context           {:test.context/id :test-ctx}})]
    (is (= :valid (:status r1)))
    (is (= :valid (:status r2)))
    (is (= (:distribution/hash (:distribution r1))
           (:distribution/hash (:distribution r2))))))

;; ═════════════════════════════════════════════════════════════════════════
;; 14. General reconciliation
;; ═════════════════════════════════════════════════════════════════════════

(deftest allocation-reconciliation
  (testing "final + deductions = base + settlement-inflows for every allocation id"
    (let [result (sd/build-slash-distribution
                  {:gross-amount      1000
                   :policy            two-award-policy
                   :parameter-context {:source-root "sha256:test"
                                       :values {:test.parameter/reward-rate 500
                                                :test.parameter/bonus-rate  300}}
                   :resolved-awards   [resolved-reward
                                       {:award/id :test.award/bonus
                                        :eligibility {:trigger :test.trigger/secondary-event
                                                      :evidence-reference "sha256:test-bonus-001"}
                                        :beneficiary {:participant/id :test.participant/bob
                                                      :participant/role :test.role/validator}}]
                   :context           {}})]
      (is (= :valid (:status result)))
      (let [dist (:distribution result)
            base (:distribution/base-allocations dist)
            final (:distribution/final-allocations dist)
            awards (:distribution/awards dist)
            ;; aggregate deductions
            deductions (reduce (fn [acc award] (merge-with + acc (:funding award))) {} awards)
            ;; aggregate settlements
            settlements (reduce (fn [acc award]
                                  (let [dest (get-in award [:settlement :allocation-id])
                                        amt (:award/amount award)]
                                    (if dest (update acc dest (fnil + 0) amt) acc)))
                                {} awards)
            all-ids (into (keys base) (into (keys deductions) (keys settlements)))]
        (doseq [id all-ids]
          (let [b (get base id 0)
                d (get deductions id 0)
                s (get settlements id 0)
                f (get final id)]
            (is (= f (+ b (- d) s))
                (str "reconciliation failed for " id))))))))

(deftest conservation
  (testing "sum(final) = gross-amount"
    (let [result (sd/build-slash-distribution
                  {:gross-amount      1000
                   :policy            all-scales-10000-policy
                   :parameter-context reward-param-500
                   :resolved-awards   [resolved-reward]
                   :context           {}})]
      (is (= :valid (:status result)))
      (is (= 1000 (reduce + 0 (vals (:distribution/final-allocations (:distribution result)))))))))

;; ═════════════════════════════════════════════════════════════════════════
;; 15. Invalid result shape
;; ═════════════════════════════════════════════════════════════════════════

(deftest invalid-result-has-no-distribution
  (let [result (sd/build-slash-distribution
                {:gross-amount      100
                 :policy            all-scales-10000-policy
                 :parameter-context reward-param-500
                 :resolved-awards   [resolved-reward resolved-reward]  ;; duplicate
                 :context           {}})]
    (is (= :invalid (:status result)))
    (is (not (contains? result :distribution)))))

(deftest invalid-result-has-violations
  (let [result (sd/build-slash-distribution
                {:gross-amount      -1
                 :policy            all-scales-10000-policy
                 :parameter-context reward-param-500
                 :resolved-awards   []
                 :context           {}})]
    (is (= :invalid (:status result)))
    (is (seq (:violations result)))
    (is (= :violation/invalid-gross-amount (-> result :violations first :violation/id)))))

;; ═════════════════════════════════════════════════════════════════════════
;; 16. verify-distribution (independent verifier)
;; ═════════════════════════════════════════════════════════════════════════

(deftest verify-valid-distribution
  (let [result (sd/build-slash-distribution
                {:gross-amount      1000
                 :policy            two-award-policy
                 :parameter-context {:source-root "sha256:test"
                                     :values {:test.parameter/reward-rate 500
                                              :test.parameter/bonus-rate  300}}
                 :resolved-awards   [resolved-reward
                                     {:award/id :test.award/bonus
                                      :eligibility {:trigger :test.trigger/secondary-event
                                                    :evidence-reference "sha256:test-bonus-001"}
                                      :beneficiary {:participant/id :test.participant/bob
                                                    :participant/role :test.role/validator}}]
                 :context           {:test.context/id :test}})
        dist (:distribution result)]
    (is (= :valid (:status result)))
    (let [{:keys [valid? violations]} (sd/verify-distribution dist)]
      (is valid? (str "unexpected violations: " (pr-str violations)))
      (is (empty? violations)))))

(deftest verify-rejects-tampered-hash
  (let [result (sd/build-slash-distribution
                {:gross-amount      100
                 :policy            all-scales-10000-policy
                 :parameter-context reward-param-500
                 :resolved-awards   [resolved-reward]
                 :context           {}})
        dist (assoc-in (:distribution result) [:distribution/hash] "sha256:tampered")
        {:keys [valid? violations]} (sd/verify-distribution dist)]
    (is (not valid?))
    (is (= :violation/distribution-hash-mismatch (-> violations first :violation/id)))))

(deftest verify-rejects-tampered-amount
  (let [result (sd/build-slash-distribution
                {:gross-amount      100
                 :policy            all-scales-10000-policy
                 :parameter-context reward-param-500
                 :resolved-awards   [resolved-reward]
                 :context           {}})
        dist (-> (:distribution result)
                 (update :distribution/final-allocations
                         (fn [m] (update m :test.allocation/a inc)))
                 (update :distribution/hash (fn [_] "ignored")))  ;; reset hash
        {:keys [valid? violations]} (sd/verify-distribution dist)]
    (is (not valid?))
    (is (some #(= :violation/distribution-hash-mismatch (:violation/id %)) violations))))

(deftest verify-rejects-out-of-order-awards
  (let [result (sd/build-slash-distribution
                {:gross-amount      1000
                 :policy            two-award-policy
                 :parameter-context {:source-root "sha256:test"
                                     :values {:test.parameter/reward-rate 500
                                              :test.parameter/bonus-rate  300}}
                 :resolved-awards   [resolved-reward
                                     {:award/id :test.award/bonus
                                      :eligibility {:trigger :test.trigger/secondary-event
                                                    :evidence-reference "sha256:test-bonus-001"}
                                      :beneficiary {:participant/id :test.participant/bob
                                                    :participant/role :test.role/validator}}]
                 :context           {}})
        ;; builder already sorts awards; reverse them to simulate tampering
        dist (update (:distribution result) :distribution/awards (comp vec reverse))
        {:keys [valid? violations]} (sd/verify-distribution dist)]
    (is (not valid?))
    (is (some #(= :violation/award-order-invalid (:violation/id %)) violations))))

(deftest verify-rejects-source-overdrawn
  (let [result (sd/build-slash-distribution
                {:gross-amount      100
                 :policy            all-scales-10000-policy
                 :parameter-context reward-param-500
                 :resolved-awards   [resolved-reward]
                 :context           {}})
        ;; tamper: inflate deductions on allocation/a beyond base
        dist (-> (:distribution result)
                 (assoc-in [:distribution/awards 0 :funding :test.allocation/a] 100)
                 (update :distribution/final-allocations (fn [m] (assoc m :test.allocation/a -95))))
        {:keys [valid? violations]} (sd/verify-distribution dist)]
    (is (not valid?))
    (is (some #(= :violation/source-overdrawn (:violation/id %)) violations))))

(deftest verify-requires-beneficiary
  (let [result (sd/build-slash-distribution
                {:gross-amount      100
                 :policy            all-scales-10000-policy
                 :parameter-context reward-param-500
                 :resolved-awards   [resolved-reward]
                 :context           {}})
        ;; tamper: remove beneficiary from active award
        dist (-> (:distribution result)
                 (assoc-in [:distribution/awards 0 :beneficiary] nil)
                 (update :distribution/hash (fn [_] "ignored")))
        {:keys [valid? violations]} (sd/verify-distribution dist)]
    ;; hash will also mismatch, but specific violation should exist
    (is (some #(= :violation/missing-beneficiary (:violation/id %)) violations))))

;; ═════════════════════════════════════════════════════════════════════════
;; 17. Full recomputation verification (policy supplied)
;; ═════════════════════════════════════════════════════════════════════════

(deftest verify-full-recomputation-success
  (let [result (sd/build-slash-distribution
                {:gross-amount      100
                 :policy            all-scales-10000-policy
                 :policy-root       (sd/policy-hash all-scales-10000-policy)
                 :parameter-context reward-param-500
                 :resolved-awards   [resolved-reward]
                 :context           {:test.context/id :test}})
        dist (:distribution result)]
    (is (= :valid (:status result)))
    (let [{:keys [valid? violations]}
          (sd/verify-distribution dist
                                  {:policy            all-scales-10000-policy
                                   :parameter-context reward-param-500})]
      (is valid? (str "unexpected violations: " (pr-str violations)))
      (is (empty? violations)))))

(deftest verify-recomputation-catches-tampered-amount
  (let [result (sd/build-slash-distribution
                {:gross-amount      100
                 :policy            all-scales-10000-policy
                 :parameter-context reward-param-500
                 :resolved-awards   [resolved-reward]
                 :context           {}})
        dist (assoc-in (:distribution result) [:distribution/awards 0 :award/amount] 9999)
        {:keys [valid? violations]}
        (sd/verify-distribution dist
                                {:policy            all-scales-10000-policy
                                 :parameter-context reward-param-500})]
    (is (not valid?))
    (is (some #(= :violation/recomputation-mismatch (:violation/id %)) violations))
    (is (some #(= "award :test.award/reward amount" (get-in % [:details :field]))
              violations))))

(deftest verify-recomputation-catches-tampered-funding
  (let [result (sd/build-slash-distribution
                {:gross-amount      100
                 :policy            all-scales-10000-policy
                 :parameter-context reward-param-500
                 :resolved-awards   [resolved-reward]
                 :context           {}})
        dist (assoc-in (:distribution result) [:distribution/awards 0 :funding :test.allocation/a] 9999)
        {:keys [valid? violations]}
        (sd/verify-distribution dist
                                {:policy            all-scales-10000-policy
                                 :parameter-context reward-param-500})]
    (is (not valid?))
    (is (some #(= :violation/recomputation-mismatch (:violation/id %)) violations))
    (is (some #(= "award :test.award/reward funding" (get-in % [:details :field]))
              violations))))

(deftest verify-recomputation-catches-tampered-base
  (let [result (sd/build-slash-distribution
                {:gross-amount      100
                 :policy            all-scales-10000-policy
                 :parameter-context reward-param-500
                 :resolved-awards   [resolved-reward]
                 :context           {}})
        dist (assoc-in (:distribution result) [:distribution/base-allocations :test.allocation/a] 9999)
        {:keys [valid? violations]}
        (sd/verify-distribution dist
                                {:policy            all-scales-10000-policy
                                 :parameter-context reward-param-500})]
    (is (not valid?))
    (is (some #(= :violation/recomputation-mismatch (:violation/id %)) violations))
    (is (some #(= :base (get-in % [:details :field])) violations))))

(deftest verify-recomputation-catches-tampered-final
  (let [result (sd/build-slash-distribution
                {:gross-amount      100
                 :policy            all-scales-10000-policy
                 :parameter-context reward-param-500
                 :resolved-awards   [resolved-reward]
                 :context           {}})
        dist (assoc-in (:distribution result) [:distribution/final-allocations :test.allocation/a] 9999)
        {:keys [valid? violations]}
        (sd/verify-distribution dist
                                {:policy            all-scales-10000-policy
                                 :parameter-context reward-param-500})]
    (is (not valid?))
    (is (some #(= :violation/recomputation-mismatch (:violation/id %)) violations))
    (is (some #(= :final-allocations (get-in % [:details :field])) violations))))

(deftest verify-recomputation-wrong-policy
  (let [wrong-policy (assoc all-scales-10000-policy :policy/id :test.policy/wrong)
        result (sd/build-slash-distribution
                {:gross-amount      100
                 :policy            all-scales-10000-policy
                 :parameter-context reward-param-500
                 :resolved-awards   [resolved-reward]
                 :context           {}})
        {:keys [valid? violations]}
        (sd/verify-distribution (:distribution result)
                                {:policy            wrong-policy
                                 :parameter-context reward-param-500})]
    (is (not valid?))
    (is (some #(= :violation/policy-root-mismatch (:violation/id %)) violations))))

(deftest verify-recomputation-missing-parameter
  (let [result (sd/build-slash-distribution
                {:gross-amount      100
                 :policy            all-scales-10000-policy
                 :parameter-context reward-param-500
                 :resolved-awards   [resolved-reward]
                 :context           {}})
        {:keys [valid? violations]}
        (sd/verify-distribution (:distribution result)
                                {:policy            all-scales-10000-policy
                                 :parameter-context {:source-root "sha256:empty" :values {}}})]
    (is (not valid?))
    (is (some #(= :violation/missing-parameter (:violation/id %)) violations))))

(deftest verify-without-policy-fallback
  (let [result (sd/build-slash-distribution
                {:gross-amount      100
                 :policy            all-scales-10000-policy
                 :parameter-context reward-param-500
                 :resolved-awards   [resolved-reward]
                 :context           {}})
        dist (:distribution result)
        ;; two-arg call with nil verification context
        single-arg-result (sd/verify-distribution dist)
        two-arg-result (sd/verify-distribution dist nil)]
    (is (:valid? single-arg-result))
    (is (:valid? two-arg-result))
    (is (= (:violations single-arg-result) (:violations two-arg-result)))))

;; ═════════════════════════════════════════════════════════════════════════
;; 18. Boundary: consistency verification ≠ eligibility verification
;; ═════════════════════════════════════════════════════════════════════════

(deftest verify-distribution-does-not-validate-evidence-authenticity
  (testing "verify-distribution checks evidence-reference presence, not authenticity"
    (let [;; Artifact with a fabricated but structurally valid evidence reference
          fabricated-ref "sha256:fabricated-eligibility-evidence"
          award (-> resolved-reward
                    (assoc-in [:eligibility :evidence-reference] fabricated-ref)
                    (assoc-in [:beneficiary :participant/id] :test.participant/eve))
          result (sd/build-slash-distribution
                  {:gross-amount      100
                   :policy            all-scales-10000-policy
                   :parameter-context reward-param-500
                   :resolved-awards   [award]
                   :context           {}})
          dist (:distribution result)]
      (is (= :valid (:status result)))
      ;; verify-distribution (consistency mode) passes — it checks presence, not truth
      (let [{:keys [valid? violations]} (sd/verify-distribution dist)]
        (is valid? (str "consistency mode rejected fabricated ref: " (pr-str violations))))
      ;; verify-distribution (recomputation mode) also passes — same reason
      (let [{:keys [valid? violations]}
            (sd/verify-distribution dist
                                    {:policy all-scales-10000-policy
                                     :parameter-context reward-param-500})]
        (is valid? (str "recomputation mode rejected fabricated ref: " (pr-str violations))))
      ;; The stored evidence-reference is preserved and accessible
      (is (= fabricated-ref
             (get-in dist [:distribution/awards 0 :eligibility :evidence-reference]))))))

(deftest verify-distribution-rejects-missing-eligibility-reference
  (testing "verify-distribution rejects missing evidence-reference when policy requires it"
    (let [award (-> resolved-reward
                    (assoc-in [:eligibility :evidence-reference] nil))
          result (sd/build-slash-distribution
                  {:gross-amount      100
                   :policy            all-scales-10000-policy
                   :parameter-context reward-param-500
                   :resolved-awards   [award]
                   :context           {}})]
      (is (= :invalid (:status result)))
      (is (some #(= :violation/missing-eligibility-reference (:violation/id %))
                (:violations result))))))

(deftest one-award-id-one-obligation
  (testing "each positive award produces exactly one entry in the awards vector"
    (doseq [[amount rate] [[100 1000] [1000 500] [10000 100]]]
      (let [params {:source-root "sha256:test" :values {:test.parameter/reward-rate rate}}
            result (sd/build-slash-distribution
                    {:gross-amount      amount
                     :policy            all-scales-10000-policy
                     :parameter-context params
                     :resolved-awards   [resolved-reward]
                     :context           {}})]
        (is (= :valid (:status result)))
        (let [awards (:distribution/awards (:distribution result))]
          (is (<= (count awards) 1))
          (when (pos? (count awards))
            (is (= :test.award/reward (:award/id (first awards))))
            (is (pos? (:award/amount (first awards))))))))))

;; ═════════════════════════════════════════════════════════════════════════
;; 19. Application receipt
;; ═════════════════════════════════════════════════════════════════════════

(deftest build-receipt-has-expected-structure
  (testing "build-application-receipt produces a valid receipt artifact"
    (let [receipt (sd/build-application-receipt
                   {:distribution-root "sha256:test-dist"
                    :policy-root "sha256:test-policy"
                    :parameter-context-root "sha256:test-params"
                    :pre-state-root "sha256:pre"
                    :post-state-root "sha256:post"
                    :idempotency-key [:test-key 0]
                    :status :applied
                    :abstract-effects [{:allocation/id :test.allocation/a :amount 45}]
                    :concrete-effects [{:target {:target/type :test.target/world-ledger
                                                 :target/key :test-ledger}
                                        :delta 45}]
                    :obligations [{:obligation/kind :test.obligation/reward
                                   :beneficiary "0xalice"
                                   :amount 5
                                   :obligation-reference "claimable:0:0xalice"}]})]
      (is (= "slash-distribution-application-receipt.v1" (:schema-version receipt)))
      (is (= "sha256:test-dist" (:receipt/distribution-root receipt)))
      (is (= "sha256:pre" (:receipt/pre-state-root receipt)))
      (is (= "sha256:post" (:receipt/post-state-root receipt)))
      (is (= :applied (:receipt/status receipt)))
      (is (= [{:allocation/id :test.allocation/a :amount 45}]
             (:receipt/abstract-effects receipt)))
      (is (= 1 (count (:receipt/concrete-effects receipt))))
      (is (= 1 (count (:receipt/obligations receipt))))
      (is (string? (:receipt/hash receipt)))
      (is (= 64 (count (:receipt/hash receipt)))))))

(deftest build-receipt-empty-obligations
  (testing "receipt can be built with zero obligations (no bounty case)"
    (let [receipt (sd/build-application-receipt
                   {:distribution-root "sha256:test-dist"
                    :policy-root "sha256:test-policy"
                    :parameter-context-root "sha256:test-params"
                    :pre-state-root "sha256:pre"
                    :post-state-root "sha256:post"
                    :idempotency-key [:test-key 1]
                    :status :applied
                    :abstract-effects [{:allocation/id :test.allocation/a :amount 50}]
                    :concrete-effects [{:target {:target/type :test.target/world-ledger
                                                 :target/key :test-ledger}
                                        :delta 50}]
                    :obligations []})]
      (is (= :applied (:receipt/status receipt)))
      (is (= [] (:receipt/obligations receipt)))
      (is (string? (:receipt/hash receipt))))))

(deftest build-receipt-idempotent-skip
  (testing "receipt can represent a skipped idempotent application"
    (let [receipt (sd/build-application-receipt
                   {:distribution-root "sha256:existing-dist"
                    :policy-root "sha256:policy"
                    :parameter-context-root "sha256:params"
                    :pre-state-root "sha256:pre"
                    :post-state-root "sha256:pre"
                    :idempotency-key [:test-key 2]
                    :status :skipped
                    :abstract-effects []
                    :obligations []})]
      (is (= :skipped (:receipt/status receipt)))
      (is (= [] (:receipt/abstract-effects receipt)))
      (is (= nil (:receipt/concrete-effects receipt)))
      (is (string? (:receipt/hash receipt))))))

(deftest build-receipt-hash-deterministic
  (testing "same inputs produce identical receipt hash"
    (let [inputs {:distribution-root "sha256:test-dist"
                  :policy-root "sha256:test-policy"
                  :parameter-context-root "sha256:test-params"
                  :pre-state-root "sha256:pre"
                  :post-state-root "sha256:post"
                  :idempotency-key [:test-key 0]
                  :status :applied
                  :abstract-effects [{:allocation/id :test.allocation/a :amount 45}]
                  :obligations [{:obligation/kind :test.obligation/reward
                                 :beneficiary "0xalice"
                                 :amount 5
                                 :obligation-reference "claimable:0:0xalice"}]}
          r1 (sd/build-application-receipt inputs)
          r2 (sd/build-application-receipt inputs)]
      (is (= (:receipt/hash r1) (:receipt/hash r2))))))
