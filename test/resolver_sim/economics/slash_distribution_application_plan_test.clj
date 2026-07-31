(ns resolver-sim.economics.slash-distribution-application-plan-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.economics.slash-distribution :as sd]
            [resolver-sim.economics.slash-distribution-application-plan :as plan]))

(def sew-default-policy
  {:schema-version "slash-distribution-policy.v1"
   :policy/id :sew.policy/default-slash-distribution
   :policy/version 1
   :allocation
   {:method :weighted
    :scale 10000
    :weights {:sew.allocation/insurance 5000
              :sew.allocation/protocol 3000
              :sew.allocation/retained 2000}
    :remainder-to :sew.allocation/retained}
   :awards
   [{:award/id :sew.award/challenge-bounty
     :amount {:method :rate-of-gross
              :parameter-key :sew.parameter/challenge-bounty-bps
              :scale 10000
              :rounding :floor}
     :eligibility {:trigger :sew.trigger/successful-challenge
                   :beneficiary-role :sew.participant/challenger
                   :requires-evidence-reference? true}
     :funding {:method :weighted-deduction
               :scale 10000
               :weights {:sew.allocation/insurance 5000
                         :sew.allocation/protocol 5000}
               :remainder-to :sew.allocation/protocol}
     :settlement {:allocation-id :sew.allocation/challenge-bounty
                  :obligation-kind :sew.obligation/challenge-bounty}}]})

(def param-ctx
  {:source-root "test"
   :values {:sew.parameter/challenge-bounty-bps 1000}})

(def resolved-awards
  [{:award/id :sew.award/challenge-bounty
    :eligibility {:trigger :sew.trigger/successful-challenge
                  :evidence-reference "sew:slash:test"}
    :beneficiary {:participant/id "0xChallenger"
                  :participant/role :sew.participant/challenger}}])

(deftest build-application-plan-valid
  (let [dist-result (sd/build-slash-distribution
                     {:gross-amount 1000
                      :policy sew-default-policy
                      :parameter-context param-ctx
                      :resolved-awards resolved-awards
                      :context {:source-reference "test"}})
        _ (is (= :valid (:status dist-result)))
        distribution (:distribution dist-result)
        result (plan/build-application-plan
                {:distribution distribution
                 :policy sew-default-policy
                 :idempotency-key [:slash-dist-applied 0 "0xChallenger"]
                 :context {:source "test"}})]
    (is (= :valid (:status result)))
    (let [p (:plan result)]
      (is (= (:plan/distribution-root p) (:distribution/hash distribution)))
      (is (= (:plan/gross-amount p) 1000))
      (is (seq (:plan/payables p)))
      (is (= 1 (count (:plan/payables p))))
      (is (= 100 (:payable/amount (first (:plan/payables p)))))
      (is (seq (:plan/backing-records p)))
      (is (= 1 (count (:plan/backing-records p))))
      (is (string? (:plan/hash p))))))

(deftest build-application-plan-single-award
  (let [dist-result (sd/build-slash-distribution
                     {:gross-amount 1000
                      :policy sew-default-policy
                      :parameter-context param-ctx
                      :resolved-awards resolved-awards
                      :context {:source-reference "test"}})
        distribution (:distribution dist-result)
        result (plan/build-application-plan
                {:distribution distribution
                 :policy sew-default-policy
                 :idempotency-key [:slash-dist-applied 0 "0xChallenger"]})]
    (is (= :valid (:status result)))
    (let [p (:plan result)]
      (is (= (:payable/amount (first (:plan/payables p))) 100))
      (is (= (:plan/gross-amount p) 1000))
      ;; Conservation preconditions
      (is (:final-conservation (:plan/preconditions p)))
      (is (:funding-conservation (:plan/preconditions p)))
      ;; Funding deductions
      (is (= (get (:plan/funding-deductions p) :sew.allocation/insurance) 50))
      (is (= (get (:plan/funding-deductions p) :sew.allocation/protocol) 50)))))

(deftest build-application-plan-odd-bounty
  (let [param-ctx-odd {:source-root "test"
                       :values {:sew.parameter/challenge-bounty-bps 500}}
        dist-result (sd/build-slash-distribution
                     {:gross-amount 100
                      :policy sew-default-policy
                      :parameter-context param-ctx-odd
                      :resolved-awards [(assoc (first resolved-awards)
                                               :beneficiary {:participant/id "0xChallenger"
                                                             :participant/role :sew.participant/challenger})]
                      :context {:source-reference "test"}})
        distribution (:distribution dist-result)
        result (plan/build-application-plan
                {:distribution distribution
                 :policy sew-default-policy
                 :idempotency-key [:slash-dist-applied 1 "0xChallenger"]})]
    (is (= :valid (:status result)))
    (let [p (:plan result)
          award (first (:distribution/awards distribution))]
      (is (= (:award/amount award) 5))
      (is (= (:payable/amount (first (:plan/payables p))) 5))
      ;; Odd remainder: floor(5/2)=2 from insurance, 3 from protocol
      (is (= (get (:plan/funding-deductions p) :sew.allocation/insurance) 2))
      (is (= (get (:plan/funding-deductions p) :sew.allocation/protocol) 3)))))

(deftest build-application-plan-deterministic
  (let [dist-result (sd/build-slash-distribution
                     {:gross-amount 1000
                      :policy sew-default-policy
                      :parameter-context param-ctx
                      :resolved-awards resolved-awards
                      :context {:source-reference "test"}})
        distribution (:distribution dist-result)
        args {:distribution distribution
              :policy sew-default-policy
              :idempotency-key [:slash-dist-applied 0 "0xChallenger"]}
        r1 (plan/build-application-plan args)
        r2 (plan/build-application-plan args)]
    (is (= (:plan/hash (:plan r1)) (:plan/hash (:plan r2))))))

(deftest build-application-plan-invalid-distribution-rejected
  (let [result (plan/build-application-plan
                {:distribution {:schema-version "slash-distribution.v1"
                                :distribution/hash "sha256:bad"
                                :distribution/gross-amount -1}
                 :policy sew-default-policy
                 :idempotency-key [:slash-dist-applied 0 "0xChallenger"]})]
    (is (= :invalid (:status result)))))

(deftest build-application-plan-no-bounty
  (let [param-ctx-zero {:source-root "test"
                        :values {:sew.parameter/challenge-bounty-bps 0}}
        dist-result (sd/build-slash-distribution
                     {:gross-amount 1000
                      :policy sew-default-policy
                      :parameter-context param-ctx-zero
                      :resolved-awards []
                      :context {:source-reference "test"}})
        _ (is (= :valid (:status dist-result)))
        distribution (:distribution dist-result)
        result (plan/build-application-plan
                {:distribution distribution
                 :policy sew-default-policy
                 :idempotency-key [:slash-dist-applied 1 nil]})]
    (is (= :valid (:status result)))
    (let [p (:plan result)]
      (is (empty? (:plan/payables p)))
      (is (empty? (:plan/backing-records p))))))

(deftest verify-application-plan-untampered
  (let [dist-result (sd/build-slash-distribution
                     {:gross-amount 1000
                      :policy sew-default-policy
                      :parameter-context param-ctx
                      :resolved-awards resolved-awards
                      :context {:source-reference "test"}})
        distribution (:distribution dist-result)
        result (plan/build-application-plan
                {:distribution distribution
                 :policy sew-default-policy
                 :idempotency-key [:slash-dist-applied 0 "0xChallenger"]})
        p (:plan result)
        v (plan/verify-application-plan p)]
    (is (:valid? v))))

(deftest verify-application-plan-tampered
  (let [dist-result (sd/build-slash-distribution
                     {:gross-amount 1000
                      :policy sew-default-policy
                      :parameter-context param-ctx
                      :resolved-awards resolved-awards
                      :context {:source-reference "test"}})
        distribution (:distribution dist-result)
        result (plan/build-application-plan
                {:distribution distribution
                 :policy sew-default-policy
                 :idempotency-key [:slash-dist-applied 0 "0xChallenger"]})
        p (:plan result)
        tampered (assoc p :plan/gross-amount 9999)
        v (plan/verify-application-plan tampered)]
    (is (not (:valid? v)))))

(deftest payout-state-conservation
  (testing "payout-state equation: non-obligation final allocations + outstanding payables = gross"
    (doseq [{:keys [gross-amount param-ctx idempotency-key expected-bounty]}
            [{:gross-amount 1000
              :param-ctx param-ctx
              :idempotency-key [:slash-dist-applied 0 "0xChallenger"]
              :expected-bounty 100}
             {:gross-amount 100
              :param-ctx {:source-root "test"
                          :values {:sew.parameter/challenge-bounty-bps 500}}
              :idempotency-key [:slash-dist-applied 1 "0xChallenger"]
              :expected-bounty 5}]]
      (let [dist-result (sd/build-slash-distribution
                         {:gross-amount gross-amount
                          :policy sew-default-policy
                          :parameter-context param-ctx
                          :resolved-awards resolved-awards
                          :context {:source-reference "test"}})
            _ (is (= :valid (:status dist-result)))
            distribution (:distribution dist-result)
            result (plan/build-application-plan
                    {:distribution distribution
                     :policy sew-default-policy
                     :idempotency-key idempotency-key})
            p (:plan result)
            awards (:distribution/awards distribution)
            payable-destinations (set (keep (comp :allocation-id :settlement) awards))
            non-obligation-credits (reduce + 0
                                           (for [[alloc-id amt] (:plan/allocation-credits p)
                                                 :when (not (contains? payable-destinations alloc-id))]
                                             amt))
            outstanding (reduce + 0 (map :payable/amount (:plan/payables p)))]
        ;; The award value appears exactly once: as the payable-settlement allocation
        ;; credit AND as the outstanding payable.  Summing the full final allocations
        ;; plus the payables would double-count; the non-obligation allocations plus
        ;; outstanding payables must equal gross.
        (is (= expected-bounty outstanding))
        (is (= expected-bounty
               (reduce + 0 (vals (select-keys (:plan/allocation-credits p)
                                              payable-destinations)))))
        (is (= gross-amount (+ non-obligation-credits outstanding))
            "non-obligation final allocations + outstanding payables = gross"))))
  (testing "backing reconciles with payables and funding deductions (no value creation)"
    (let [dist-result (sd/build-slash-distribution
                       {:gross-amount 1000
                        :policy sew-default-policy
                        :parameter-context param-ctx
                        :resolved-awards resolved-awards
                        :context {:source-reference "test"}})
          distribution (:distribution dist-result)
          result (plan/build-application-plan
                  {:distribution distribution
                   :policy sew-default-policy
                   :idempotency-key [:slash-dist-applied 0 "0xChallenger"]})
          p (:plan result)
          payables (:plan/payables p)
          backings (:plan/backing-records p)
          payable-total (reduce + 0 (map :payable/amount payables))
          backing-total (reduce + 0 (map :backing/amount backings))
          funding-total (reduce + 0 (vals (:plan/funding-deductions p)))
          awards-by-id (into {} (map (fn [a] [(:award/id a) a]) (:distribution/awards distribution)))]
      (is (= payable-total backing-total funding-total)
          "payables = backing = funding deductions")
      (doseq [b backings]
        (let [award (get awards-by-id (:backing/award-id b))]
          (is (= (:backing/amount b)
                 (reduce + 0 (vals (:backing/source-allocations b))))
              "backing amount equals its source-allocation sum (restricted, not new credit)")
          (is (= (:funding award) (:backing/source-allocations b))
              "backing source allocations match the award's funding deduction split"))))))
